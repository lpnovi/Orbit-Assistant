package com.orbit.assistant;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/** Context-aware quick actions driven by ScreenContextClassifier. */
public final class ScreenActionSuggester {
    private ScreenActionSuggester() {}

    public static final class Suggestion {
        public final String label;
        public final String prompt;
        Suggestion(String label, String prompt) {
            this.label = label;
            this.prompt = prompt;
        }
    }

    public static List<Suggestion> suggestions(Context context, String screenText,
                                               boolean hasScreenshot, String foregroundPackage,
                                               String foregroundAppLabel) {
        ArrayList<Suggestion> out = new ArrayList<>();
        String text = screenText == null ? "" : screenText.trim();
        if (text.isEmpty() && !hasScreenshot) return out;

        ScreenContextClassifier.Result result = ScreenContextClassifier.classify(
                context, text, hasScreenshot, foregroundPackage, foregroundAppLabel);
        DiagnosticStore.recordClassification(context, result.category, result.confidence,
                result.profileOverride, result.reason);

        addCategoryActions(out, result.category);

        AppProfileStore.Profile profile = AppProfileStore.get(context, foregroundPackage);
        applySlotOverride(out, 0, profile.action1);
        applySlotOverride(out, 1, profile.action2);
        applySlotOverride(out, 2, profile.action3);

        while (out.size() > 3) out.remove(out.size() - 1);

        StringBuilder actionLabels = new StringBuilder();
        for (Suggestion item : out) {
            if (actionLabels.length() > 0) actionLabels.append(" · ");
            actionLabels.append(item.label);
        }
        DiagnosticStore.recordAppBehavior(context,
                profile.isDefault() ? "Automatic profile" : "Custom app profile",
                AppProfileStore.effectivePrivacyLabel(context, foregroundPackage),
                AppProfileStore.screenBlocked(context, foregroundPackage) ? "Blocked" :
                        (AppProfileStore.PRIVACY_SENSITIVE.equals(
                                AppProfileStore.effectivePrivacy(context, foregroundPackage))
                                ? "Manual only" : AppProfileStore.screenLabel(profile.screenPolicy)),
                AppProfileStore.screenshotAllowed(context, foregroundPackage) ? "Allowed" : "Blocked",
                Prefs.modeLabel(AppProfileStore.defaultMode(context, foregroundPackage,
                        Prefs.intelligenceMode(context))),
                actionLabels.toString());
        return out;
    }

    private static void addCategoryActions(List<Suggestion> out, String category) {
        if (AppProfileStore.CATEGORY_CONVERSATION.equals(category)) {
            out.add(new Suggestion("Draft reply", ReplyDraftContext.CONVERSATION_DRAFT_PROMPT));
            out.add(new Suggestion("Summarize",
                    "Summarize the conversation on my screen, including the main point and anything I need to respond to."));
            out.add(new Suggestion("Explain tone",
                    "Explain the tone and subtext of the conversation on my screen. Be concise and point out anything easy to misread."));
            return;
        }

        if (AppProfileStore.CATEGORY_EMAIL.equals(category)) {
            out.add(new Suggestion("Draft reply", ReplyDraftContext.EMAIL_DRAFT_PROMPT));
            out.add(new Suggestion("Summarize",
                    "Summarize this email and tell me the main point."));
            out.add(new Suggestion("Needs action?",
                    "Tell me whether this email needs a response or other action, and what I should do."));
            return;
        }

        if (AppProfileStore.CATEGORY_PRODUCT.equals(category)) {
            out.add(new Suggestion("Worth it?",
                    "Evaluate the product or purchase shown on my screen. Tell me whether it looks worth it and the main tradeoffs."));
            out.add(new Suggestion("Compare",
                    "Evaluate what is on my screen against the most relevant alternatives or options shown, focusing on practical tradeoffs."));
            out.add(new Suggestion("Key specs",
                    "Pull out the important specifications, price details, limitations, and buying considerations from this screen."));
            return;
        }

        if (AppProfileStore.CATEGORY_ARTICLE.equals(category)) {
            out.add(new Suggestion("Summarize",
                    "Summarize the article or webpage on my screen. Focus on the information that matters most."));
            out.add(new Suggestion("Key claims",
                    "Identify the main claims or arguments on this screen and distinguish them from supporting details."));
            out.add(new Suggestion("Explain",
                    "Explain the article or webpage on my screen in plain language."));
            return;
        }

        if (AppProfileStore.CATEGORY_SETTINGS.equals(category)) {
            out.add(new Suggestion("What does this do?",
                    "Explain what the setting or options on my screen actually do and what changing them affects."));
            out.add(new Suggestion("Recommend",
                    "Look at the settings on my screen and recommend the option that makes the most practical sense. Explain why briefly."));
            out.add(new Suggestion("Explain",
                    "Explain the settings screen I am looking at in plain language."));
            return;
        }

        if (AppProfileStore.CATEGORY_DOCUMENT.equals(category)) {
            out.add(new Suggestion("Summarize",
                    "Summarize the document content visible on my screen."));
            out.add(new Suggestion("Key points",
                    "Pull out the key points, claims, and anything I should remember from this document."));
            out.add(new Suggestion("Explain",
                    "Explain the visible part of this document in plain language."));
            return;
        }

        if (AppProfileStore.CATEGORY_MAP.equals(category)) {
            out.add(new Suggestion("Route summary",
                    "Summarize the route or map information visible on my screen, including timing and major navigation details."));
            out.add(new Suggestion("What next?",
                    "Tell me the most useful next navigation step based on the map or route visible on my screen."));
            out.add(new Suggestion("Explain route",
                    "Explain the route, traffic, destination, or navigation information visible on my screen."));
            return;
        }

        if (AppProfileStore.CATEGORY_MEDIA.equals(category)) {
            out.add(new Suggestion("What is this?",
                    "Identify and explain the media or content shown on my screen."));
            out.add(new Suggestion("Summarize",
                    "Summarize what is shown about this video, song, episode, post, or other media."));
            out.add(new Suggestion("Worth my time?",
                    "Based on what is visible on my screen, tell me whether this media seems worth my time and why."));
            return;
        }

        out.add(new Suggestion("Summarize",
                "Summarize what is on my screen. Focus on the information that matters most."));
        out.add(new Suggestion("Explain",
                "Explain what I am looking at on my screen in plain language."));
        out.add(new Suggestion("What matters?",
                "Tell me what is important or actionable about what is on my screen."));
    }

    private static void applySlotOverride(List<Suggestion> out, int index, String action) {
        Suggestion override = actionForOverride(action);
        if (override == null) return;
        while (out.size() <= index) out.add(new Suggestion("Explain",
                "Explain what is on my screen in plain language."));
        for (int i = 0; i < out.size(); i++) {
            if (i != index && out.get(i).label.equalsIgnoreCase(override.label)) return;
        }
        out.set(index, override);
    }

    private static Suggestion actionForOverride(String action) {
        if (AppProfileStore.ACTION_DRAFT.equals(action))
            return new Suggestion("Draft reply", ReplyDraftContext.CONVERSATION_DRAFT_PROMPT);
        if (AppProfileStore.ACTION_SUMMARIZE.equals(action))
            return new Suggestion("Summarize", "Summarize what is on my screen and focus on what matters most.");
        if (AppProfileStore.ACTION_EXPLAIN.equals(action))
            return new Suggestion("Explain", "Explain what I am looking at on my screen in plain language.");
        if (AppProfileStore.ACTION_TONE.equals(action))
            return new Suggestion("Explain tone", "Explain the tone and subtext of what is on my screen and point out anything easy to misread.");
        if (AppProfileStore.ACTION_NEEDS_ACTION.equals(action))
            return new Suggestion("Needs action?", "Tell me whether what is on my screen needs a response or other action, and what I should do.");
        if (AppProfileStore.ACTION_WORTH.equals(action))
            return new Suggestion("Worth it?", "Evaluate what is shown on my screen and tell me whether it looks worth it.");
        if (AppProfileStore.ACTION_COMPARE.equals(action))
            return new Suggestion("Compare", "Compare the relevant options shown on my screen and explain the practical tradeoffs.");
        if (AppProfileStore.ACTION_KEY_SPECS.equals(action))
            return new Suggestion("Key specs", "Pull out the important specifications, limitations, and practical details from my screen.");
        if (AppProfileStore.ACTION_KEY_POINTS.equals(action))
            return new Suggestion("Key points", "Pull out the key points and anything I should remember from what is on my screen.");
        if (AppProfileStore.ACTION_RECOMMEND.equals(action))
            return new Suggestion("Recommend", "Recommend the most practical option based on what is on my screen and explain why briefly.");
        if (AppProfileStore.ACTION_WHICH_OPTION.equals(action))
            return new Suggestion("Which option?", "Tell me which option on my screen makes the most practical sense and why.");
        if (AppProfileStore.ACTION_WHAT_MATTERS.equals(action))
            return new Suggestion("What matters?", "Tell me what is important or actionable about what is on my screen.");
        if (AppProfileStore.ACTION_ROUTE.equals(action))
            return new Suggestion("Route summary", "Summarize the route or navigation information visible on my screen.");
        if (AppProfileStore.ACTION_WHAT_NEXT.equals(action))
            return new Suggestion("What next?", "Tell me the most useful next step based on what is visible on my screen.");
        return null;
    }

    private static boolean containsLabel(List<Suggestion> out, String label) {
        for (Suggestion s : out) if (s.label.equalsIgnoreCase(label)) return true;
        return false;
    }
}
