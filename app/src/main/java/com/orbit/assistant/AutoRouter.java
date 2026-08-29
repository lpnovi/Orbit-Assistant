package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local, explainable task router used only when the user selects Auto.
 * It never sends a second AI request just to choose a model.
 *
 * <p>Three kinds of evidence decide a request, in descending order of authority:
 *
 * <ol>
 *   <li><b>Reasoning dimensions</b> — {@link ReasoningDimension}, the distinct kinds of hard
 *       thinking the user actually asked for. Several at once is what earns Deep.</li>
 *   <li><b>Task shape</b> — the broad analytical/planning/drafting/summary buckets, which are what
 *       separate Fast from Balanced.</li>
 *   <li><b>Context</b> — attachments, notifications, and whatever app happened to be on screen.
 *       Useful, but never allowed to re-characterise a request whose own words are already
 *       clearly difficult.</li>
 * </ol>
 *
 * <p>Length is deliberately weak evidence and one polite "think carefully" is deliberately weaker
 * still: neither can reach Deep on its own.
 */
public final class AutoRouter {
    private static final Pattern PDF_PAGES =
            Pattern.compile("PDF page count:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    /**
     * How many distinct reasoning dimensions make a request decisively Deep.
     *
     * <p>One is ordinary work. Two is a hard question. Three or more means the answer has to stay
     * correct across several concerns at once, which is the only thing Deep is for.
     */
    static final int DECISIVE_DIMENSIONS = 3;

    /** Points each distinct reasoning dimension contributes. */
    private static final int POINTS_PER_DIMENSION = 3;

    /** Extra weight once dimensions combine, on a prompt long enough to have really asked. */
    private static final int COMBINATION_BONUS = 4;

    /** A prompt at least this long has room to genuinely state a multi-part problem. */
    private static final int SUBSTANTIVE_CHARS = 140;

    private AutoRouter() {}

    public static final class Decision {
        public final String mode;
        public final int confidence;
        public final String reason;
        /** The distinct kinds of hard reasoning detected in the prompt, in declaration order. */
        public final List<ReasoningDimension> dimensions;

        Decision(String mode, int confidence, String reason, List<ReasoningDimension> dimensions) {
            this.mode = Prefs.normalizeMode(mode);
            this.confidence = Math.max(0, Math.min(100, confidence));
            this.reason = reason == null ? "" : reason;
            this.dimensions = dimensions == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(dimensions));
        }
    }

    public static Decision route(Context c, String prompt, String screenText, Bitmap screenshot,
                                 List<AssistantClient.History> history,
                                 boolean explicitAttachment, String notificationContext) {
        String p = normalize(prompt);
        String screen = screenText == null ? "" : screenText;
        String screenLower = screen.toLowerCase(Locale.US);
        String notifications = notificationContext == null ? "" : notificationContext;

        int fast = 1;
        int balanced = 5;   // Deliberately Balanced-biased.
        int deep = 0;

        List<String> fastReasons = new ArrayList<>();
        List<String> balancedReasons = new ArrayList<>();
        List<String> deepReasons = new ArrayList<>();

        // What kinds of hard thinking the user actually asked for. This is the strongest signal
        // Orbit has, because it comes from the request itself rather than from its surroundings.
        List<ReasoningDimension> dimensions = ReasoningDimension.detect(p);
        int dimensionCount = dimensions.size();
        boolean substantive = p.length() >= SUBSTANTIVE_CHARS;
        // Once a request shows this many independent kinds of difficulty, its own words decide the
        // mode. Whatever app was in the foreground is incidental and stops contributing.
        boolean promptDominates = dimensionCount >= DECISIVE_DIMENSIONS;

        boolean analytical = containsAny(p,
                "analyze", "analysis", "critique", "evaluate", "compare", "contrast",
                "methodology", "rubric", "evidence", "citation", "citations",
                "pros and cons", "tradeoff", "trade-off", "diagnose", "debug",
                "why does", "reason through", "work through", "assess");
        boolean explicitDepth = containsAny(p,
                "think deeply", "deep analysis", "in depth", "in-depth",
                "be thorough", "thoroughly", "reason carefully", "work this out carefully",
                "detailed analysis", "comprehensive");
        // Politeness, not evidence. "Think carefully" and "step by step" are asked for constantly
        // and cost nothing to type, so on their own they move a request barely at all; they only
        // add weight once the prompt has already shown real reasoning dimensions.
        boolean depthHint = containsAny(p,
                "think carefully", "think this through", "take your time",
                "step by step", "step-by-step", "reason through");
        boolean planning = containsAny(p,
                "plan", "strategy", "roadmap", "step by step", "best approach",
                "what should i do", "decision", "recommendation");
        boolean drafting = containsAny(p,
                "draft", "reply", "respond", "rewrite", "rephrase", "write a");
        boolean summary = containsAny(p,
                "summarize", "summary", "key points", "what matters", "tl;dr");
        // Whole words only. Substring matching quietly classified anything containing "hi" -
        // "this", "which", "think" - as small talk, which sent ordinary short questions to Fast
        // for a reason that had nothing to do with them.
        boolean casual = containsWord(p,
                "hi", "hello", "hey", "how are you", "thanks", "thank you",
                "lol", "lmao", "what's up", "whats up");
        boolean quickFact = p.matches("^(what|who|when|where|define|is|are|can|does|do)\\b.*")
                && !analytical && !planning && p.length() <= 100;

        if (p.length() <= 45 && (casual || quickFact)) {
            fast += 7;
            fastReasons.add(casual ? "short casual request" : "short factual request");
        } else if (p.length() <= 100 && !analytical && !planning && !explicitDepth) {
            fast += 3;
            fastReasons.add("short straightforward prompt");
        }

        // Length is the weakest evidence there is: a rambling message is not a hard one. It is
        // kept only as a small tiebreaker and can never reach Deep by itself.
        if (p.length() > 300) {
            deep += 2;
            deepReasons.add("long prompt");
        }
        if (p.length() > 700) {
            deep += 3;
            deepReasons.add("very detailed prompt");
        }

        if (dimensionCount > 0) {
            deep += dimensionCount * POINTS_PER_DIMENSION;
            if (promptDominates && substantive) deep += COMBINATION_BONUS;
            if (depthHint) deep += 1;
        }

        if (analytical) {
            deep += 5;
            balanced += 1;
            deepReasons.add("analytical or troubleshooting request");
        }
        if (explicitDepth) {
            deep += 7;
            deepReasons.add("explicit request for deeper reasoning");
        }
        if (planning) {
            balanced += 3;
            deep += 2;
            balancedReasons.add("planning or decision task");
        }
        if (drafting) {
            balanced += 4;
            balancedReasons.add("drafting or reply task");
        }
        if (summary) {
            balanced += 4;
            balancedReasons.add("summary task");
        }

        // Explicit attachments matter more than prompt length.
        boolean pdf = screenLower.contains("explicitly attached the pdf") ||
                screenLower.contains("<orbit_pdf_text>") ||
                screenLower.contains("pdf page count:");
        int pdfPages = pdf ? pdfPages(screen) : 0;

        if (pdf) {
            if (pdfPages >= 10 || screen.length() >= 15000) {
                deep += 7;
                deepReasons.add(pdfPages > 0
                        ? "large PDF (" + pdfPages + " pages)"
                        : "large PDF attachment");
            } else {
                balanced += 5;
                balancedReasons.add("PDF attachment");
            }
        } else if (explicitAttachment) {
            if (screen.length() >= 12000) {
                deep += 5;
                deepReasons.add("large document attachment");
            } else if (screen.length() >= 3000) {
                balanced += 4;
                deep += 1;
                balancedReasons.add("document attachment");
            } else if (screenshot != null) {
                balanced += 4;
                balancedReasons.add("image attachment");
            } else {
                balanced += 3;
                balancedReasons.add("attachment context");
            }
        }

        if (!notifications.trim().isEmpty()) {
            balanced += 6;
            balancedReasons.add("notification summary context");
            if (notifications.length() > 12000) {
                deep += 2;
                deepReasons.add("large notification set");
            }
        }

        // Screen classifier gives a useful signal when the side-button assistant is invoked over
        // another app. It is suppressed for a request that has already shown several reasoning
        // dimensions: the launcher being classified as a document is not a reason to answer an
        // architecture question as though it were ordinary document work.
        SharedPreferences d = DiagnosticStore.prefs(c);
        String category = d.getString("context_category", AppProfileStore.CATEGORY_GENERIC);
        int contextConfidence = d.getInt("context_confidence", 0);

        if (!promptDominates && contextConfidence >= 55 && screen.length() > 0) {
            if (AppProfileStore.CATEGORY_DOCUMENT.equals(category) ||
                    AppProfileStore.CATEGORY_ARTICLE.equals(category)) {
                balanced += 3;
                if (analytical || screen.length() > 8000) deep += 3;
                balancedReasons.add("document or article screen");
            } else if (AppProfileStore.CATEGORY_CONVERSATION.equals(category) ||
                    AppProfileStore.CATEGORY_EMAIL.equals(category)) {
                balanced += drafting ? 5 : 3;
                balancedReasons.add("conversation or email screen");
            } else if (AppProfileStore.CATEGORY_PRODUCT.equals(category)) {
                balanced += 3;
                if (containsAny(p, "worth", "compare", "better", "buy")) deep += 1;
                balancedReasons.add("product comparison context");
            } else if (AppProfileStore.CATEGORY_MAP.equals(category) ||
                    AppProfileStore.CATEGORY_SETTINGS.equals(category)) {
                balanced += 2;
                balancedReasons.add("context-aware screen task");
            }
        }

        if (!promptDominates && screenshot != null && !explicitAttachment) {
            balanced += 2;
            balancedReasons.add("screen image context");
        }
        if (screen.length() > 10000 && !pdf) {
            deep += 2;
            deepReasons.add("large screen context");
        } else if (!promptDominates && screen.length() > 1200 && !explicitAttachment) {
            balanced += 2;
            balancedReasons.add("substantial screen context");
        }

        // Conversation awareness: a tiny follow-up should not drop to Fast in the
        // middle of a complex discussion.
        HistorySignal hs = historySignal(history, prompt);
        if (hs.characters > 2500) {
            balanced += 2;
            balancedReasons.add("ongoing conversation context");
        }
        if (hs.characters > 7000 || hs.complexTurns >= 2) {
            deep += 3;
            deepReasons.add("complex recent conversation");
        }
        if (p.length() <= 80 && hs.characters > 1800) {
            fast -= 2;
            balanced += 2;
        }

        // Fast is only allowed when there is genuinely little context.
        if (explicitAttachment || screenshot != null || screen.length() > 800 ||
                !notifications.trim().isEmpty() || hs.characters > 1600) {
            fast -= 2;
        }

        String mode;
        List<String> reasons;
        int top;
        int second;

        if (deep >= balanced + 3 && deep >= fast + 2) {
            mode = Prefs.MODE_DEEP;
            reasons = deepReasons;
            top = deep;
            second = Math.max(balanced, fast);
        } else if (fast >= balanced + 3 && deep <= balanced) {
            mode = Prefs.MODE_FAST;
            reasons = fastReasons;
            top = fast;
            second = Math.max(balanced, deep);
        } else {
            mode = Prefs.MODE_BALANCED;
            reasons = balancedReasons;
            top = balanced;
            second = Math.max(fast, deep);
        }

        // When several reasoning dimensions are what carried the decision, say which ones. A
        // report that reads "complex architecture + concurrency and race analysis + multi-option
        // evaluation" explains the choice; "planning or decision task" did not.
        if (Prefs.MODE_DEEP.equals(mode) && dimensionCount > 0) {
            List<String> named = new ArrayList<>();
            for (ReasoningDimension dimension : dimensions) named.add(dimension.label);
            named.addAll(reasons);
            reasons = named;
        }

        if (reasons.isEmpty()) {
            reasons = new ArrayList<>();
            reasons.add(Prefs.MODE_BALANCED.equals(mode)
                    ? "mixed or moderate task complexity"
                    : Prefs.MODE_FAST.equals(mode)
                    ? "simple low-context request"
                    : "high-complexity request");
        }

        int margin = Math.max(0, top - second);
        int confidence = Math.min(97, 62 + margin * 5);
        if (explicitDepth && Prefs.MODE_DEEP.equals(mode)) confidence = Math.max(confidence, 94);
        if (promptDominates && Prefs.MODE_DEEP.equals(mode)) confidence = Math.max(confidence, 88);
        if ((casual || quickFact) && Prefs.MODE_FAST.equals(mode)) confidence = Math.max(confidence, 90);
        if (Prefs.MODE_BALANCED.equals(mode) && margin <= 1) confidence = Math.min(confidence, 68);

        return new Decision(mode, confidence, joinReasons(reasons), dimensions);
    }

    private static HistorySignal historySignal(List<AssistantClient.History> history, String prompt) {
        if (history == null || history.isEmpty()) return new HistorySignal(0, 0);

        int end = history.size();
        if (end > 0) {
            AssistantClient.History last = history.get(end - 1);
            if (last != null && "user".equalsIgnoreCase(last.role) &&
                    normalize(prompt).equals(normalize(last.content))) end--;
        }

        int start = Math.max(0, end - 6);
        int chars = 0;
        int complex = 0;

        for (int i = start; i < end; i++) {
            AssistantClient.History h = history.get(i);
            if (h == null || h.content == null) continue;
            String text = h.content.trim();
            chars += Math.min(text.length(), 4000);
            String n = normalize(text);
            if (text.length() > 900 || containsAny(n,
                    "analyze", "compare", "evaluate", "methodology", "debug",
                    "reason", "strategy", "evidence", "paper", "citation")) complex++;
        }
        return new HistorySignal(chars, complex);
    }

    private static int pdfPages(String screen) {
        Matcher m = PDF_PAGES.matcher(screen == null ? "" : screen);
        if (!m.find()) return 0;
        try { return Integer.parseInt(m.group(1)); }
        catch (Exception ignored) { return 0; }
    }

    private static String joinReasons(List<String> input) {
        List<String> copy = new ArrayList<>();
        for (String r : input) {
            if (r != null && !r.trim().isEmpty() && !copy.contains(r.trim())) copy.add(r.trim());
        }
        if (copy.isEmpty()) return "task complexity";
        if (copy.size() > 3) copy = copy.subList(0, 3);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < copy.size(); i++) {
            if (i > 0) b.append(" + ");
            b.append(copy.get(i));
        }
        return b.toString();
    }

    private static boolean containsAny(String s, String... needles) {
        for (String n : needles) if (s.contains(n)) return true;
        return false;
    }

    /** Substring matching, but only where a word actually starts and ends. */
    private static boolean containsWord(String s, String... needles) {
        if (s == null || s.isEmpty()) return false;
        for (String n : needles) {
            int from = 0;
            while (true) {
                int at = s.indexOf(n, from);
                if (at < 0) break;
                boolean startsWord = at == 0 || !isWordChar(s.charAt(at - 1));
                int after = at + n.length();
                boolean endsWord = after >= s.length() || !isWordChar(s.charAt(after));
                if (startsWord && endsWord) return true;
                from = at + 1;
            }
        }
        return false;
    }

    private static boolean isWordChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '\'';
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US).trim()
                .replace('’', '\'').replaceAll("\\s+", " ");
    }

    private static final class HistorySignal {
        final int characters;
        final int complexTurns;
        HistorySignal(int characters, int complexTurns) {
            this.characters = characters;
            this.complexTurns = complexTurns;
        }
    }
}
