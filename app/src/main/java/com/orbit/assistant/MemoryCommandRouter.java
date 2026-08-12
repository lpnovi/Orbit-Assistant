package com.orbit.assistant;

import android.content.Context;

import java.util.List;
import java.util.Locale;

/** Deterministic local commands for explicit Orbit Memory changes. */
public final class MemoryCommandRouter {
    private MemoryCommandRouter() {}

    public static boolean canHandle(String prompt) {
        String raw = prompt == null ? "" : prompt.trim();
        String lower = raw.toLowerCase(Locale.US);

        return lower.equals("what do you remember about me") ||
                lower.equals("what do you remember") ||
                lower.equals("show my memories") ||
                lower.equals("show me my memories") ||
                lower.equals("forget everything you remember about me") ||
                lower.equals("clear all memories") ||
                lower.equals("forget all memories") ||
                lower.startsWith("what do you remember about ") ||
                lower.startsWith("forget everything about ") ||
                lower.startsWith("forget memories about ") ||
                lower.startsWith("change what you remember about ") ||
                lower.startsWith("update what you remember about ") ||
                lower.startsWith("change memory about ") ||
                lower.startsWith("update memory about ") ||
                lower.startsWith("remember that ") ||
                lower.startsWith("remember ") ||
                lower.startsWith("forget that ") ||
                lower.startsWith("forget ");
    }

    public static AssistantReply tryHandle(Context c, String prompt) {
        return tryHandle(c, prompt, null);
    }

    public static AssistantReply tryHandle(Context c, String prompt,
                                           List<AssistantClient.History> history) {
        String raw = prompt == null ? "" : prompt.trim();
        String lower = raw.toLowerCase(Locale.US);
        if (raw.isEmpty()) return null;

        if (lower.equals("what do you remember about me") ||
                lower.equals("what do you remember") ||
                lower.equals("show my memories") ||
                lower.equals("show me my memories")) {
            return listMemories(c, MemoryStore.list(c), "Here is what Orbit remembers locally:");
        }

        if (lower.startsWith("what do you remember about ")) {
            String subject = raw.substring("what do you remember about ".length()).trim();
            if (subject.equalsIgnoreCase("me")) {
                return listMemories(c, MemoryStore.list(c), "Here is what Orbit remembers locally:");
            }
            List<MemoryStore.Memory> matches = MemoryStore.search(c, subject);
            if (matches.isEmpty()) {
                return new AssistantReply("I do not have any Orbit memories about " + subject + ".");
            }
            return listMemories(c, matches, "Here is what Orbit remembers about " + subject + ":");
        }

        if (lower.equals("forget everything you remember about me") ||
                lower.equals("clear all memories") || lower.equals("forget all memories")) {
            MemoryStore.clear(c);
            return new AssistantReply("I cleared all local Orbit memories.");
        }

        String deleteSubject = extractAfterAny(raw, lower,
                "forget everything about ", "forget memories about ");
        if (deleteSubject != null && !deleteSubject.isEmpty()) {
            int deleted = MemoryStore.deleteMatching(c, deleteSubject);
            if (deleted == 0) {
                return new AssistantReply("I could not find any Orbit memories about " + deleteSubject + ".");
            }
            return new AssistantReply("I forgot " + deleted + (deleted == 1 ? " Orbit memory" : " Orbit memories") +
                    " about " + deleteSubject + ".");
        }

        Change change = parseChange(raw, lower);
        if (change != null) {
            MemoryStore.Memory match = MemoryStore.findBest(c, change.subject);
            if (match == null) {
                return new AssistantReply("I could not find a matching Orbit memory about " +
                        change.subject + " to update.");
            }

            MemoryStore.Memory duplicate = MemoryStore.findDuplicate(c, change.replacement, match.id);
            if (duplicate != null) {
                return new AssistantReply("A very similar Orbit memory is already saved: " + duplicate.text);
            }

            String category = MemoryStore.inferCategory(change.replacement);
            if (MemoryStore.CATEGORY_OTHER.equals(category)) category = match.category;
            MemoryStore.update(c, match.id, category, change.replacement, match.pinned, match.enabled);
            return new AssistantReply("I updated that Orbit memory to: " + change.replacement);
        }

        if (lower.equals("remember that") || lower.equals("remember this")) {
            MemoryStore.Suggestion contextual = recentSuggestion(c, history, raw);
            if (contextual == null || contextual.text == null || contextual.text.trim().isEmpty()) {
                return new AssistantReply("I am not sure what you want me to remember. " +
                        "Say “Remember that …” with the detail, or use Save on a Remember this? card.");
            }

            MemoryStore.Memory duplicate =
                    MemoryStore.findDuplicate(c, contextual.text, null);
            if (duplicate != null) {
                return new AssistantReply("I already have a similar Orbit memory saved: " +
                        duplicate.text);
            }

            MemoryStore.Memory saved = MemoryStore.add(c, contextual.category, contextual.text);
            return saved == null
                    ? new AssistantReply("I could not save that memory.")
                    : new AssistantReply("I will remember that locally in Orbit: " + saved.text);
        }

        String remember = extractAfter(raw, lower, "remember that ");
        if (remember == null) remember = extractAfter(raw, lower, "remember ");
        if (remember != null && !remember.isEmpty()) {
            MemoryStore.Memory duplicate = MemoryStore.findDuplicate(c, remember, null);
            if (duplicate != null) {
                return new AssistantReply("I already have a similar Orbit memory saved: " + duplicate.text);
            }

            MemoryStore.Memory m = MemoryStore.add(c, MemoryStore.inferCategory(remember), remember);
            return m == null ? new AssistantReply("I could not save that memory.")
                    : new AssistantReply("I will remember that locally in Orbit: " + m.text);
        }

        String forget = extractAfter(raw, lower, "forget that ");
        if (forget == null) forget = extractAfter(raw, lower, "forget ");
        if (forget != null && !forget.isEmpty()) {
            MemoryStore.Memory match = MemoryStore.findBest(c, forget);
            if (match == null) {
                return new AssistantReply("I could not find a matching Orbit memory to forget.");
            }
            MemoryStore.delete(c, match.id);
            return new AssistantReply("I forgot this Orbit memory: " + match.text);
        }
        return null;
    }

    private static MemoryStore.Suggestion recentSuggestion(
            Context c, List<AssistantClient.History> history, String currentPrompt) {
        if (history == null || history.isEmpty()) return null;

        String current = currentPrompt == null ? "" : currentPrompt.trim();

        // Prefer suggestion metadata that was already computed for the most
        // recent assistant turn.
        for (int i = history.size() - 1; i >= 0; i--) {
            AssistantClient.History h = history.get(i);
            if (h == null) continue;
            if ("assistant".equalsIgnoreCase(h.role) &&
                    h.memorySuggestionText != null &&
                    !h.memorySuggestionText.trim().isEmpty()) {
                String category = h.memorySuggestionCategory == null ||
                        h.memorySuggestionCategory.trim().isEmpty()
                        ? MemoryStore.inferCategory(h.memorySuggestionText)
                        : h.memorySuggestionCategory;
                return new MemoryStore.Suggestion(h.memorySuggestionText, category);
            }
        }

        // Otherwise re-run the conservative local detector on the most recent
        // previous user statement. The current "Remember that" turn may already
        // be present in WorkManager's loaded conversation, so skip it.
        for (int i = history.size() - 1; i >= 0; i--) {
            AssistantClient.History h = history.get(i);
            if (h == null || !"user".equalsIgnoreCase(h.role)) continue;
            String content = h.content == null ? "" : h.content.trim();
            if (content.isEmpty() || content.equalsIgnoreCase(current)) continue;

            MemoryStore.Suggestion suggestion = MemoryStore.suggest(c, content);
            if (suggestion != null) return suggestion;

            // Only inspect the nearest genuine prior user turn. If it is not a
            // durable fact, do not reach far back and save something surprising.
            break;
        }
        return null;
    }

    private static AssistantReply listMemories(Context c, List<MemoryStore.Memory> all, String heading) {
        if (all == null || all.isEmpty()) {
            return new AssistantReply("I do not have any Orbit memories saved yet.");
        }

        StringBuilder b = new StringBuilder(heading);
        int shown = 0;
        for (MemoryStore.Memory m : all) {
            if (shown >= 20) {
                b.append("\n\nThere are more in Orbit > Memory.");
                break;
            }
            b.append("\n\n• ").append(m.text);
            if (m.pinned) b.append(" [pinned]");
            if (!m.enabled) b.append(" [disabled]");
            shown++;
        }
        return new AssistantReply(b.toString());
    }

    private static Change parseChange(String raw, String lower) {
        String[] prefixes = {
                "change what you remember about ",
                "update what you remember about ",
                "change memory about ",
                "update memory about "
        };

        for (String prefix : prefixes) {
            if (!lower.startsWith(prefix)) continue;
            int to = lower.indexOf(" to ", prefix.length());
            if (to < 0) return null;

            String subject = raw.substring(prefix.length(), to).trim();
            String replacement = raw.substring(to + 4).trim();
            if (subject.isEmpty() || replacement.isEmpty()) return null;
            return new Change(subject, replacement);
        }
        return null;
    }

    private static String extractAfterAny(String raw, String lower, String... prefixes) {
        for (String prefix : prefixes) {
            String found = extractAfter(raw, lower, prefix);
            if (found != null) return found;
        }
        return null;
    }

    private static String extractAfter(String raw, String lower, String prefix) {
        if (!lower.startsWith(prefix)) return null;
        return raw.substring(prefix.length()).trim();
    }

    private static final class Change {
        final String subject;
        final String replacement;

        Change(String subject, String replacement) {
            this.subject = subject;
            this.replacement = replacement;
        }
    }
}
