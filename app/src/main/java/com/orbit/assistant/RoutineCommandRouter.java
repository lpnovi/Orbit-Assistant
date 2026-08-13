package com.orbit.assistant;

import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Natural-language local trigger for saved routines. */
public final class RoutineCommandRouter {
    private RoutineCommandRouter() {}

    private static final class MatchResult {
        final RoutineStore.Routine routine;
        final List<RoutineStore.Routine> ambiguous;

        MatchResult(RoutineStore.Routine routine, List<RoutineStore.Routine> ambiguous) {
            this.routine = routine;
            this.ambiguous = ambiguous == null ? new ArrayList<>() : ambiguous;
        }
    }

    public static AssistantReply tryHandle(Context context, String raw) {
        if (context == null || raw == null) return null;
        String prompt = clean(raw);
        if (prompt.isEmpty()) return null;

        String lower = prompt.toLowerCase(Locale.US);
        if (lower.equals("list routines") || lower.equals("list my routines") ||
                lower.equals("what routines do i have") || lower.equals("what are my routines") ||
                lower.equals("which routines do i have") || lower.equals("what routines can i run") ||
                lower.equals("show my routines") || lower.equals("show me my routines") ||
                lower.equals("show routines")) {
            return routineListReply(context);
        }

        String candidate = extractRunName(prompt);
        if (candidate == null || candidate.isEmpty()) return null;

        List<RoutineStore.Routine> routines = RoutineStore.list(context);
        MatchResult match = findRunTarget(routines, candidate);
        if (match.routine != null) {
            return replyForRoutine(context, match.routine);
        }

        if (!match.ambiguous.isEmpty()) {
            StringBuilder text = new StringBuilder("I found more than one routine that could mean that: ");
            appendRoutineNames(text, match.ambiguous, 5);
            text.append(". Which one should I run?");
            return new AssistantReply(text.toString());
        }

        // If the user explicitly said “routine”, keep the miss local. This avoids a
        // routine command falling through to web search or a network model merely
        // because the saved name was not found. Commands such as “Run Spotify” that
        // do not mention a routine are still allowed to fall through normally.
        if (looksExplicitlyRoutineSpecific(prompt)) {
            if (routines.isEmpty()) {
                return new AssistantReply("You don't have any saved routines yet. You can create one in Settings → Routines.");
            }
            StringBuilder text = new StringBuilder("I couldn't find a saved routine matching that. You have: ");
            appendRoutineNames(text, routines, 5);
            if (routines.size() > 5) text.append(", and ").append(routines.size() - 5).append(" more");
            text.append('.');
            return new AssistantReply(text.toString());
        }
        return null;
    }

    /** Shared handoff used by explicit Routine syntax and user-defined Custom Commands. */
    static AssistantReply replyForRoutine(Context context, RoutineStore.Routine routine) {
        if (context == null || routine == null) return null;
        RoutineStore.markRun(context, routine.id);
        List<AssistantReply.Action> actions = RoutineStore.copyActions(routine.actions);
        String steps = actions.size() == 1 ? "1 step" : actions.size() + " steps";
        return new AssistantReply("Running " + routine.name + " · " + steps + ".", actions);
    }

    static boolean isReservedCommandPhrase(String raw) {
        String prompt = clean(raw);
        if (prompt.isEmpty()) return false;
        String lower = prompt.toLowerCase(Locale.US);
        return lower.equals("list routines") || lower.equals("list my routines") ||
                lower.equals("what routines do i have") || lower.equals("what are my routines") ||
                lower.equals("which routines do i have") || lower.equals("what routines can i run") ||
                lower.equals("show my routines") || lower.equals("show me my routines") ||
                lower.equals("show routines") || extractRunName(prompt) != null;
    }

    private static AssistantReply routineListReply(Context context) {
        List<RoutineStore.Routine> routines = RoutineStore.list(context);
        if (routines.isEmpty()) {
            return new AssistantReply("You don't have any saved routines yet. You can create one in Settings → Routines.");
        }
        StringBuilder text = new StringBuilder("Your saved routines are: ");
        appendRoutineNames(text, routines, routines.size());
        text.append('.');
        return new AssistantReply(text.toString());
    }

    private static void appendRoutineNames(StringBuilder text, List<RoutineStore.Routine> routines, int limit) {
        int count = Math.min(limit, routines.size());
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                if (count == 2) text.append(" and ");
                else text.append(i == count - 1 ? ", and " : ", ");
            }
            text.append(routines.get(i).name);
        }
    }

    static String extractRunName(String raw) {
        String s = clean(raw);
        if (s.isEmpty()) return null;

        // Accept polite wrappers while keeping execution gated behind an explicit
        // run/start/execute/activate verb.
        String lower;
        String[] polite = {"please ", "can you ", "could you ", "would you ", "will you ", "orbit "};
        boolean stripped;
        do {
            stripped = false;
            lower = s.toLowerCase(Locale.US);
            for (String wrapper : polite) {
                if (lower.startsWith(wrapper)) {
                    s = s.substring(wrapper.length()).trim();
                    stripped = true;
                    break;
                }
            }
        } while (stripped && !s.isEmpty());

        lower = s.toLowerCase(Locale.US);
        String prefix;
        if (lower.startsWith("run ")) prefix = "run ";
        else if (lower.startsWith("start ")) prefix = "start ";
        else if (lower.startsWith("execute ")) prefix = "execute ";
        else if (lower.startsWith("activate ")) prefix = "activate ";
        else return null;

        String name = s.substring(prefix.length()).trim();
        name = stripLeadingWord(name, "my");
        name = stripLeadingWord(name, "the");
        name = stripTrailingPhrase(name, "for me");
        name = stripTrailingWord(name, "please");
        name = stripTrailingWord(name, "now");
        return RoutineStore.sanitizeName(name);
    }

    private static MatchResult findRunTarget(List<RoutineStore.Routine> routines, String candidate) {
        if (routines == null || routines.isEmpty()) return new MatchResult(null, null);

        String direct = normalize(candidate);
        if (direct.isEmpty()) return new MatchResult(null, null);

        // Exact name always wins.
        for (RoutineStore.Routine routine : routines) {
            if (normalize(routine.name).equals(direct)) return new MatchResult(routine, null);
        }

        String target = normalizeTarget(candidate);
        if (!target.isEmpty()) {
            for (RoutineStore.Routine routine : routines) {
                if (normalize(routine.name).equals(target)) return new MatchResult(routine, null);
            }
        }

        // A target with no descriptive words is only considered a routine reference
        // when the candidate actually says “routine”. This keeps a generic phrase
        // such as “Run that one” available to other Orbit handling.
        if (target.isEmpty()) {
            if (!looksExplicitlyRoutineSpecific(candidate)) return new MatchResult(null, null);
            if (routines.size() == 1) return new MatchResult(routines.get(0), null);
            return new MatchResult(null, routines);
        }

        int bestScore = 0;
        List<RoutineStore.Routine> best = new ArrayList<>();
        for (RoutineStore.Routine routine : routines) {
            int score = matchScore(target, normalize(routine.name));
            if (score <= 0) continue;
            if (score > bestScore) {
                bestScore = score;
                best.clear();
                best.add(routine);
            } else if (score == bestScore) {
                best.add(routine);
            }
        }

        if (best.size() == 1) return new MatchResult(best.get(0), null);
        if (best.size() > 1) return new MatchResult(null, best);
        return new MatchResult(null, null);
    }

    /**
     * Fuzzy routine matching is intentionally conservative. It only happens after
     * an explicit execution verb and only runs automatically when one saved routine
     * is the unique best target.
     */
    static int matchScore(String target, String routineName) {
        if (target == null || routineName == null || target.isEmpty() || routineName.isEmpty()) return 0;
        if (target.equals(routineName)) return 1000;
        if (routineName.startsWith(target + " ") || routineName.endsWith(" " + target)) return 900;
        if (routineName.contains(" " + target + " ")) return 880;
        if (target.length() >= 4 && routineName.contains(target)) return 820;

        Set<String> targetTokens = meaningfulTokens(target);
        Set<String> routineTokens = meaningfulTokens(routineName);
        if (targetTokens.isEmpty() || routineTokens.isEmpty()) return 0;

        int exact = 0;
        int prefix = 0;
        for (String wanted : targetTokens) {
            if (routineTokens.contains(wanted)) {
                exact++;
                continue;
            }
            if (wanted.length() >= 4) {
                boolean found = false;
                for (String actual : routineTokens) {
                    if (actual.startsWith(wanted) || wanted.startsWith(actual)) {
                        found = true;
                        break;
                    }
                }
                if (found) prefix++;
            }
        }
        int matched = exact + prefix;
        if (matched == 0) return 0;
        if (matched == targetTokens.size()) return 700 + (exact * 20) + (prefix * 10) - routineTokens.size();
        // Partial multi-word overlap is useful for ambiguity detection but should
        // rank below a routine that satisfies every meaningful requested token.
        if (targetTokens.size() > 1 && matched >= Math.max(1, targetTokens.size() - 1)) {
            return 400 + (exact * 15) + (prefix * 7);
        }
        return targetTokens.size() == 1 ? 650 + (exact * 20) + (prefix * 10) : 0;
    }

    static String normalizeTarget(String value) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) return "";
        List<String> words = new ArrayList<>();
        for (String word : normalized.split(" ")) {
            if (word.isEmpty()) continue;
            if (word.equals("that") || word.equals("this") || word.equals("the") ||
                    word.equals("my") || word.equals("saved") || word.equals("routine") ||
                    word.equals("routines") || word.equals("one") || word.equals("called") ||
                    word.equals("named") || word.equals("please") || word.equals("now")) {
                continue;
            }
            words.add(word);
        }
        return String.join(" ", words).trim();
    }

    private static Set<String> meaningfulTokens(String value) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String normalized = normalize(value);
        if (normalized.isEmpty()) return out;
        for (String token : normalized.split(" ")) {
            if (token.length() >= 2) out.add(token);
        }
        return out;
    }

    private static boolean looksExplicitlyRoutineSpecific(String prompt) {
        String normalized = " " + normalize(prompt) + " ";
        return normalized.contains(" routine ") || normalized.contains(" routines ");
    }

    private static String stripLeadingWord(String value, String word) {
        String lower = value.toLowerCase(Locale.US);
        String prefix = word.toLowerCase(Locale.US) + " ";
        return lower.startsWith(prefix) ? value.substring(prefix.length()).trim() : value;
    }

    private static String stripTrailingWord(String value, String word) {
        String lower = value.toLowerCase(Locale.US);
        String suffix = " " + word.toLowerCase(Locale.US);
        return lower.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()).trim() : value;
    }

    private static String stripTrailingPhrase(String value, String phrase) {
        String lower = value.toLowerCase(Locale.US);
        String suffix = " " + phrase.toLowerCase(Locale.US);
        return lower.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()).trim() : value;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim().replaceAll("[.!?]+$", "").trim().replaceAll("\\s+", " ");
    }
}
