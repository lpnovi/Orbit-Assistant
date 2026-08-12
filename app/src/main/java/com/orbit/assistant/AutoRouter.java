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
 */
public final class AutoRouter {
    private static final Pattern PDF_PAGES =
            Pattern.compile("PDF page count:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    private AutoRouter() {}

    public static final class Decision {
        public final String mode;
        public final int confidence;
        public final String reason;

        Decision(String mode, int confidence, String reason) {
            this.mode = Prefs.normalizeMode(mode);
            this.confidence = Math.max(0, Math.min(100, confidence));
            this.reason = reason == null ? "" : reason;
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

        boolean analytical = containsAny(p,
                "analyze", "analysis", "critique", "evaluate", "compare", "contrast",
                "methodology", "rubric", "evidence", "citation", "citations",
                "pros and cons", "tradeoff", "trade-off", "diagnose", "debug",
                "why does", "reason through", "work through", "assess");
        boolean explicitDepth = containsAny(p,
                "think deeply", "deep analysis", "in depth", "in-depth",
                "be thorough", "thoroughly", "reason carefully", "work this out carefully",
                "detailed analysis", "comprehensive");
        boolean planning = containsAny(p,
                "plan", "strategy", "roadmap", "step by step", "best approach",
                "what should i do", "decision", "recommendation");
        boolean drafting = containsAny(p,
                "draft", "reply", "respond", "rewrite", "rephrase", "write a");
        boolean summary = containsAny(p,
                "summarize", "summary", "key points", "what matters", "tl;dr");
        boolean casual = containsAny(p,
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

        if (p.length() > 300) {
            deep += 2;
            deepReasons.add("long prompt");
        }
        if (p.length() > 700) {
            deep += 3;
            deepReasons.add("very detailed prompt");
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

        // Screen classifier gives a useful signal when the side-button assistant
        // is invoked over another app.
        SharedPreferences d = DiagnosticStore.prefs(c);
        String category = d.getString("context_category", AppProfileStore.CATEGORY_GENERIC);
        int contextConfidence = d.getInt("context_confidence", 0);

        if (contextConfidence >= 55 && screen.length() > 0) {
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

        if (screenshot != null && !explicitAttachment) {
            balanced += 2;
            balancedReasons.add("screen image context");
        }
        if (screen.length() > 10000 && !pdf) {
            deep += 2;
            deepReasons.add("large screen context");
        } else if (screen.length() > 1200 && !explicitAttachment) {
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
        if ((casual || quickFact) && Prefs.MODE_FAST.equals(mode)) confidence = Math.max(confidence, 90);
        if (Prefs.MODE_BALANCED.equals(mode) && margin <= 1) confidence = Math.min(confidence, 68);

        return new Decision(mode, confidence, joinReasons(reasons));
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
