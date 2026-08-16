package com.orbit.assistant;

import android.content.Context;

import java.util.List;
import java.util.Locale;

/** Deterministic local commands for explicit Orbit Memory changes. */
public final class MemoryCommandRouter {
    private MemoryCommandRouter() {}

    /**
     * Read-only phrasings. Flexible, because the worst outcome is listing memories the user
     * already owns.
     */
    private static final String[] LIST_ALL = {
            "what do you remember about me", "what do you remember", "what do you know about me",
            "what have you saved about me", "what have you remembered about me",
            "what have you stored about me", "show my memories", "show me my memories",
            "show what you remember", "show what you remember about me",
            "show me what you remember", "show me what you know about me",
            "list my memories", "what is in my memory", "what's in my memory"
    };
    private static final String[] LIST_ABOUT = {
            "what do you remember about ", "what do you know about ",
            "what have you saved about ", "what have you remembered about ",
            "what have you stored about ", "what have i told you about ",
            "show what you remember about ", "show me what you remember about ",
            "do you remember my ", "do you remember anything about ",
            "what do you have on "
    };
    /** Explicit save phrasings. Each must read as an instruction, never as reminiscing. */
    private static final String[] SAVE_PREFIXES = {
            "remember that ", "remember this about me: ", "remember this about me ",
            "save that ", "save this about me: ", "save this about me ",
            "keep in mind that ", "keep in mind ", "add this to memory: ",
            "add this to memory ", "add to memory: ", "add to my memory: ",
            "make a note that ", "note that ", "store that ", "remember "
    };
    /** Explicit delete phrasings. Destructive, so these stay tightly worded. */
    private static final String[] DELETE_ABOUT = {
            "forget everything about ", "forget memories about ",
            "forget what you know about ", "forget what you remember about ",
            "remove the memory about ", "delete the memory about ",
            "remove what you remember about ", "delete what you remember about ",
            "remove the memory that ", "delete the memory that ",
            "remove memories about ", "delete memories about "
    };
    private static final String[] CLEAR_ALL = {
            "forget everything you remember about me", "clear all memories", "forget all memories",
            "delete all saved memories", "delete all memories", "delete all my memories",
            "clear all my memories", "clear my memories", "forget everything about me",
            "forget all my memories", "erase all my memories", "erase all memories",
            "wipe all my memories"
    };

    public static boolean canHandle(String prompt) {
        String lower = canonical(prompt);
        if (lower.isEmpty()) return false;

        if (equalsAny(lower, LIST_ALL) || equalsAny(lower, CLEAR_ALL)) return true;
        if (startsWithAny(lower, LIST_ABOUT)) return true;
        if (startsWithAny(lower, DELETE_ABOUT)) return true;
        if (parseChangeIntent(lower) != null) return true;
        if (isExplicitSave(lower)) return true;
        return isExplicitForget(lower);
    }

    /**
     * Whether the sentence instructs Orbit to store something.
     *
     * <p>"Remember that my favourite game is GRIS" is an instruction. "Remember when we talked
     * about that?" and "I remember going there" are not, and a prefix test alone could not tell
     * them apart — the first word is the same.
     */
    static boolean isExplicitSave(String canonical) {
        if (canonical == null || canonical.isEmpty()) return false;
        if (!startsWithAny(canonical, SAVE_PREFIXES)) return false;
        // "remember when …" is reminiscing, and "remember to …" is a reminder, not a fact.
        if (canonical.matches("^remember\\s+(when|how|why|if|whether|the time)\\b.*")) return false;
        if (canonical.matches("^remember\\s+to\\b.*")) return false;
        if (canonical.matches("^(keep in mind|note that|store that)\\s+(when|how|why)\\b.*")) return false;
        // A question is never a save instruction.
        return !canonical.matches("^(do|does|did|can|could|would|will|have|has)\\b.*");
    }

    /**
     * Whether the sentence instructs Orbit to delete something. Stricter than saving, because a
     * wrong guess destroys data the user cannot recover from chat.
     */
    static boolean isExplicitForget(String canonical) {
        if (canonical == null || canonical.isEmpty()) return false;
        if (startsWithAny(canonical, DELETE_ABOUT)) return true;
        if (!canonical.startsWith("forget ")) return false;
        // "forget it", "forget about it" are dismissals, not deletions.
        if (canonical.equals("forget it") || canonical.equals("forget about it")) return false;
        // Questions and third-person musings never delete: "did you forget what I said",
        // "why do people forget things", "don't forget to remind me".
        if (canonical.matches("^forget\\s+(to|about it|it)\\b.*")) return false;
        return canonical.startsWith("forget that ") || canonical.startsWith("forget ");
    }

    public static AssistantReply tryHandle(Context c, String prompt) {
        return tryHandle(c, prompt, null);
    }

    public static AssistantReply tryHandle(Context c, String prompt,
                                           List<AssistantClient.History> history) {
        String raw = normalizedRaw(prompt);
        String lower = canonical(prompt);
        if (raw.isEmpty()) return null;

        if (equalsAny(lower, LIST_ALL)) {
            return listMemories(c, MemoryStore.list(c), "Here is what Orbit remembers locally:");
        }

        String subject = extractAfterAny(raw, lower, LIST_ABOUT);
        if (subject != null && !subject.isEmpty()) {
            if (subject.equalsIgnoreCase("me")) {
                return listMemories(c, MemoryStore.list(c), "Here is what Orbit remembers locally:");
            }
            List<MemoryStore.Memory> matches = MemoryStore.search(c, subject);
            if (matches.isEmpty()) {
                return new AssistantReply("I do not have any Orbit memories about " + subject + ".");
            }
            return listMemories(c, matches, "Here is what Orbit remembers about " + subject + ":");
        }

        if (equalsAny(lower, CLEAR_ALL)) {
            MemoryStore.clear(c);
            return new AssistantReply("I cleared all local Orbit memories.");
        }

        String deleteSubject = extractAfterAny(raw, lower, DELETE_ABOUT);
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

        String remember = isExplicitSave(lower) ? extractAfterAny(raw, lower, SAVE_PREFIXES) : null;
        if (remember != null && !remember.isEmpty()) {
            MemoryStore.Memory duplicate = MemoryStore.findDuplicate(c, remember, null);
            if (duplicate != null) {
                return new AssistantReply("I already have a similar Orbit memory saved: " + duplicate.text);
            }

            MemoryStore.Memory m = MemoryStore.add(c, MemoryStore.inferCategory(remember), remember);
            return m == null ? new AssistantReply("I could not save that memory.")
                    : new AssistantReply("I will remember that locally in Orbit: " + m.text);
        }

        String forget = null;
        if (isExplicitForget(lower)) {
            forget = extractAfter(raw, lower, "forget that ");
            if (forget == null) forget = extractAfter(raw, lower, "forget ");
        }
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

    /** Update phrasings, each pairing a prefix with the separator that splits subject from value. */
    private static final String[][] CHANGE_FORMS = {
            {"change what you remember about ", " to "},
            {"update what you remember about ", " to "},
            {"change memory about ", " to "},
            {"update memory about ", " to "},
            {"update my memory about ", " to "},
            {"change my memory about ", " to "},
            {"replace the memory about ", " with "},
            {"replace what you remember about ", " with "},
            {"correct the memory about ", " to "}
    };

    /** Recognition only, used by {@link #canHandle} without touching the store. */
    private static String parseChangeIntent(String lower) {
        for (String[] form : CHANGE_FORMS) {
            if (!lower.startsWith(form[0])) continue;
            if (lower.indexOf(form[1], form[0].length()) < 0) continue;
            return form[0];
        }
        return null;
    }

    private static Change parseChange(String raw, String lower) {
        for (String[] form : CHANGE_FORMS) {
            String prefix = form[0];
            String separator = form[1];
            if (!lower.startsWith(prefix)) continue;
            int split = lower.indexOf(separator, prefix.length());
            // Without a clear separator there is no unambiguous replacement value, so the
            // request is left alone rather than guessed at.
            if (split < 0) return null;

            String subject = raw.substring(prefix.length(), split).trim();
            String replacement = raw.substring(split + separator.length()).trim();
            if (subject.isEmpty() || replacement.isEmpty()) return null;
            return new Change(subject, replacement);
        }
        return null;
    }

    /**
     * Trimmed, whitespace-collapsed original text with trailing sentence punctuation removed.
     * {@link #canonical} is derived from this same value, so the two stay the same length and
     * prefix offsets found in one are valid substring offsets in the other.
     */
    private static String normalizedRaw(String prompt) {
        String value = prompt == null ? "" : prompt.trim().replaceAll("\\s+", " ");
        while (!value.isEmpty()) {
            char last = value.charAt(value.length() - 1);
            if (last != '?' && last != '!' && last != '.') break;
            value = value.substring(0, value.length() - 1).trim();
        }
        return value;
    }

    private static String canonical(String prompt) {
        return normalizedRaw(prompt).toLowerCase(Locale.US).replace('’', '\'');
    }

    private static boolean equalsAny(String value, String[] options) {
        for (String option : options) if (value.equals(option)) return true;
        return false;
    }

    private static boolean startsWithAny(String value, String[] prefixes) {
        for (String prefix : prefixes) if (value.startsWith(prefix)) return true;
        return false;
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
