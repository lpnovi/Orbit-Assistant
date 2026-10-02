package com.orbit.assistant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The branch structure behind one conversation (0.8.3.0-beta.3+), as pure data and pure operations.
 *
 * <p><b>Shape.</b> A conversation's {@code messages} list is always its <em>active path</em>: the
 * one linear conversation the user is looking at and the only one any request is ever built from.
 * Everything else lives in {@link Fork}s. A fork sits at one position of the active path and holds
 * every alternative <em>tail</em> that diverges there - the messages from that position on, plus any
 * forks further down inside that tail. The alternative currently on screen is not stored in the fork
 * at all: it is the active path itself, and its slot in {@link Fork#variants} is {@code null}.
 *
 * <p>So the shared prefix of two branches is stored once, a hidden sibling can never be read by a
 * request builder, and a chat written before branches existed is simply a chat with no forks.
 *
 * <p><b>Two kinds, one structure.</b> Retry adds an alternative tail starting at an assistant answer
 * (answer variants); editing an earlier message adds one starting at that user message (an edited
 * branch). The role of the message at the fork position is what tells them apart.
 *
 * <p><b>Validation.</b> Each fork records a fingerprint of the message just before it. A fork whose
 * position or parent no longer matches the path it hangs off is dropped on read rather than allowed
 * to graft an alternative onto the wrong place. That is the one way branch data can be lost, and it
 * only happens to data that was already inconsistent.
 *
 * <p>No Context, no JSON: {@link ConversationStore} owns persistence and calls in here.
 */
final class ConversationBranches {
    /** Most alternatives one fork keeps, the visible one included. */
    static final int MAX_VARIANTS = 10;
    /** Most forks one conversation keeps across all of its branches. */
    static final int MAX_FORKS = 24;

    private ConversationBranches() {}

    /** One stored alternative: messages from the fork position on, and the forks inside them. */
    static final class Path {
        final List<AssistantClient.History> messages;
        /** Positions are relative to {@link #messages}. */
        final List<Fork> forks;

        Path(List<AssistantClient.History> messages, List<Fork> forks) {
            this.messages = Collections.unmodifiableList(new ArrayList<>(
                    messages == null ? Collections.emptyList() : messages));
            this.forks = Collections.unmodifiableList(new ArrayList<>(
                    forks == null ? Collections.emptyList() : forks));
        }
    }

    /** Every alternative that diverges at one position of the path it belongs to. */
    static final class Fork {
        final int at;
        /** Fingerprint of the message before {@link #at}, or "" when the fork is at the start. */
        final String parent;
        /** Which alternative is the visible one. */
        final int selected;
        /** Oldest first. Exactly one entry, at {@link #selected}, is null. */
        final List<Path> variants;

        Fork(int at, String parent, int selected, List<Path> variants) {
            this.at = at;
            this.parent = parent == null ? "" : parent;
            this.selected = selected;
            this.variants = Collections.unmodifiableList(new ArrayList<>(variants));
        }

        int count() { return variants.size(); }

        Fork moved(int newAt, String newParent) {
            return new Fork(newAt, newParent, selected, variants);
        }
    }

    /** An active path and the forks along it. */
    static final class State {
        final List<AssistantClient.History> messages;
        final List<Fork> forks;

        State(List<AssistantClient.History> messages, List<Fork> forks) {
            this.messages = Collections.unmodifiableList(new ArrayList<>(
                    messages == null ? Collections.emptyList() : messages));
            List<Fork> sorted = new ArrayList<>(forks == null ? Collections.emptyList() : forks);
            sorted.sort((a, b) -> Integer.compare(a.at, b.at));
            this.forks = Collections.unmodifiableList(sorted);
        }

        Fork forkAt(int at) {
            for (Fork fork : forks) if (fork.at == at) return fork;
            return null;
        }
    }

    // ---- identity --------------------------------------------------------------------------------

    /**
     * A short stable identity for one message: speaker, words, attachment label and the request
     * that answered it. Used to check that a fork still hangs off the message it was made under,
     * never to decide which of two identical questions the user meant.
     */
    static String fingerprint(AssistantClient.History h) {
        if (h == null) return "";
        String value = safe(h.role).toLowerCase(java.util.Locale.US) + '\u0000' + safe(h.content)
                + '\u0000' + safe(h.attachmentLabel) + '\u0000' + safe(h.replyRequestId);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(16);
            for (int i = 0; i < 8; i++) out.append(String.format(java.util.Locale.US, "%02x", hash[i]));
            return out.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    /** The parent fingerprint a fork at {@code at} of {@code messages} must carry. */
    static String parentKey(List<AssistantClient.History> messages, int at) {
        if (messages == null || at <= 0 || at > messages.size()) return "";
        return fingerprint(messages.get(at - 1));
    }

    // ---- validation ------------------------------------------------------------------------------

    /**
     * The forks of {@code messages} that still fit it, deepest structure checked too.
     *
     * <p>A fork survives only when its position is inside the path, its parent fingerprint matches
     * the message before it, it has between two and {@link #MAX_VARIANTS} alternatives, exactly the
     * selected one is the active path, and every stored alternative is a real, non-empty tail. Two
     * forks at one position cannot both be right, so the second is dropped. {@code allowStart} is
     * false inside a stored tail, whose position 0 belongs to the fork that holds it.
     */
    static List<Fork> validate(List<AssistantClient.History> messages, List<Fork> forks,
                               boolean allowStart) {
        List<Fork> out = new ArrayList<>();
        if (messages == null || forks == null) return out;
        List<Fork> sorted = new ArrayList<>(forks);
        sorted.sort((a, b) -> Integer.compare(a.at, b.at));
        int lastAt = Integer.MIN_VALUE;
        for (Fork fork : sorted) {
            if (fork == null || fork.at == lastAt) continue;
            if (fork.at < (allowStart ? 0 : 1) || fork.at >= messages.size()) continue;
            if (!parentKey(messages, fork.at).equals(fork.parent)) continue;
            if (fork.variants.size() < 2 || fork.variants.size() > MAX_VARIANTS) continue;
            if (fork.selected < 0 || fork.selected >= fork.variants.size()) continue;
            boolean ok = true;
            List<Path> variants = new ArrayList<>();
            for (int i = 0; i < fork.variants.size(); i++) {
                Path path = fork.variants.get(i);
                if (i == fork.selected) {
                    if (path != null) { ok = false; break; }
                    variants.add(null);
                    continue;
                }
                if (path == null || path.messages.isEmpty()) { ok = false; break; }
                variants.add(new Path(path.messages, validate(path.messages, path.forks, false)));
            }
            if (!ok) continue;
            out.add(new Fork(fork.at, fork.parent, fork.selected, variants));
            lastAt = fork.at;
        }
        return out;
    }

    /** How many forks this structure holds, counting those inside stored alternatives. */
    static int countForks(List<Fork> forks) {
        int total = 0;
        if (forks == null) return 0;
        for (Fork fork : forks) {
            total++;
            for (Path path : fork.variants) if (path != null) total += countForks(path.forks);
        }
        return total;
    }

    /** Every message stored anywhere in these forks' alternatives, for ownership and backups. */
    static void collectStoredMessages(List<Fork> forks, List<AssistantClient.History> into) {
        if (forks == null) return;
        for (Fork fork : forks) {
            for (Path path : fork.variants) {
                if (path == null) continue;
                into.addAll(path.messages);
                collectStoredMessages(path.forks, into);
            }
        }
    }

    // ---- operations ------------------------------------------------------------------------------

    /** Why a branch operation was refused, or null when it was not. */
    static final class Refusal extends Exception {
        Refusal(String message) { super(message); }
    }

    /**
     * Makes {@code tail} the visible continuation from {@code at}, keeping the current one.
     *
     * <p>The current tail - everything from {@code at} on, with the forks inside it - is stored as
     * an alternative of the fork at {@code at} (created if this is the first), and the new tail is
     * appended as the newest alternative and selected. Nothing before {@code at} changes.
     */
    static State branch(State state, int at, Path tail) throws Refusal {
        if (state == null || tail == null || tail.messages.isEmpty()) throw new Refusal("Nothing to add");
        if (at < 0 || at > state.messages.size()) throw new Refusal("That message is no longer here");
        if (at == state.messages.size()) {
            // Nothing visible to keep: the new tail simply continues the path.
            List<AssistantClient.History> messages = new ArrayList<>(state.messages);
            messages.addAll(tail.messages);
            List<Fork> forks = new ArrayList<>(state.forks);
            for (Fork nested : tail.forks) forks.add(nested.moved(nested.at + at, ""));
            return reparent(new State(messages, forks));
        }
        Fork existing = state.forkAt(at);
        Path current = packTail(state, at);
        List<Path> variants = new ArrayList<>();
        int created = 0;
        if (existing == null) {
            variants.add(current);
            created = 1;
        } else {
            variants.addAll(existing.variants);
            variants.set(existing.selected, current);
        }
        if (variants.size() + 1 > MAX_VARIANTS) {
            throw new Refusal("This message already has " + MAX_VARIANTS + " versions");
        }
        if (countForks(state.forks) + created + countForks(tail.forks) > MAX_FORKS) {
            throw new Refusal("This chat already has as many branches as Orbit keeps");
        }
        variants.add(null);
        Fork fork = new Fork(at, parentKey(state.messages, at), variants.size() - 1, variants);
        return unpack(state, fork, tail);
    }

    /** Shows alternative {@code target} of the fork at {@code at}, keeping the one it replaces. */
    static State select(State state, int at, int target) throws Refusal {
        Fork fork = state == null ? null : state.forkAt(at);
        if (fork == null) throw new Refusal("That message has no other versions");
        if (target < 0 || target >= fork.count()) throw new Refusal("No such version");
        if (target == fork.selected) return state;
        List<Path> variants = new ArrayList<>(fork.variants);
        Path chosen = variants.get(target);
        variants.set(fork.selected, packTail(state, at));
        variants.set(target, null);
        return unpack(state, new Fork(at, fork.parent, target, variants), chosen);
    }

    /**
     * Drops {@code shift} messages from the front of the path, as clipping a long chat does.
     *
     * <p>A fork that lands at position 0 loses its parent with them and is re-anchored to the start.
     * A fork whose position is clipped away is dropped along with the shared prefix it hung off,
     * exactly as those messages are; Orbit never invented history for a clipped chat and does not
     * start now.
     */
    static State clipFront(State state, int shift) {
        if (state == null || shift <= 0) return state;
        int start = Math.min(shift, state.messages.size());
        List<AssistantClient.History> messages = new ArrayList<>(
                state.messages.subList(start, state.messages.size()));
        List<Fork> forks = new ArrayList<>();
        for (Fork fork : state.forks) {
            int at = fork.at - start;
            if (at < 0) continue;
            forks.add(fork.moved(at, at == 0 ? "" : fork.parent));
        }
        return new State(messages, forks);
    }

    /** The active tail from {@code at}, with every fork inside it, as a storable alternative. */
    static Path packTail(State state, int at) {
        List<AssistantClient.History> tail = new ArrayList<>(
                state.messages.subList(Math.min(at, state.messages.size()), state.messages.size()));
        List<Fork> nested = new ArrayList<>();
        for (Fork fork : state.forks) {
            if (fork.at > at) nested.add(fork.moved(fork.at - at, fork.parent));
        }
        return new Path(tail, nested);
    }

    /** The prefix before {@code fork.at}, then {@code tail} as the visible continuation. */
    private static State unpack(State state, Fork fork, Path tail) {
        List<AssistantClient.History> messages = new ArrayList<>(state.messages.subList(0, fork.at));
        messages.addAll(tail.messages);
        List<Fork> forks = new ArrayList<>();
        for (Fork other : state.forks) if (other.at < fork.at) forks.add(other);
        forks.add(fork);
        for (Fork nested : tail.forks) forks.add(nested.moved(nested.at + fork.at, nested.parent));
        return new State(messages, forks);
    }

    /** Recomputes parents after a tail was appended, so a later read validates them correctly. */
    private static State reparent(State state) {
        List<Fork> forks = new ArrayList<>();
        for (Fork fork : state.forks) forks.add(fork.moved(fork.at, parentKey(state.messages, fork.at)));
        return new State(state.messages, forks);
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
