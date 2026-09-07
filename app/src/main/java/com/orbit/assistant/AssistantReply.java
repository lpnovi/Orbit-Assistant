package com.orbit.assistant;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public final class AssistantReply {
    /** How many cited pages one reply carries forward. Beyond this, a citation list is noise. */
    public static final int MAX_SOURCE_URLS = 6;

    public final String text;
    public final List<Action> actions;
    /** Human-readable snapshot of the memories supplied to the AI for this turn. */
    public final String memoryUsage;
    /** Optional local suggestion. Orbit never saves this without confirmation. */
    public final String suggestedMemoryText;
    public final String suggestedMemoryCategory;
    /**
     * The pages this answer's hosted web search actually consulted, in the order they appeared.
     *
     * <p>Structured provenance rather than text scraped back out of the answer. Orbit already
     * showed one source chip under a web answer by pulling a URL out of the reply's own prose,
     * which works for the one address the model chose to write down and knows nothing about the
     * rest. Rich Answers needs the real list: a picture is only worth showing when it belongs to a
     * page the answer genuinely used, and "genuinely used" is a fact the provider reports rather
     * than something a regular expression can infer.
     *
     * <p>Empty for every provider without hosted search, and empty for an answer that did not use
     * it. Nothing downstream may treat empty as a failure; it means this answer came from the model
     * rather than from the web, which is an ordinary thing for an answer to be.
     */
    public final List<String> sourceUrls;

    public AssistantReply(String text) {
        this(text, new ArrayList<>(), "", "", "");
    }

    public AssistantReply(String text, List<Action> actions) {
        this(text, actions, "", "", "");
    }

    public AssistantReply(String text, List<Action> actions, String memoryUsage,
                          String suggestedMemoryText, String suggestedMemoryCategory) {
        this(text, actions, memoryUsage, suggestedMemoryText, suggestedMemoryCategory, null);
    }

    public AssistantReply(String text, List<Action> actions, String memoryUsage,
                          String suggestedMemoryText, String suggestedMemoryCategory,
                          List<String> sourceUrls) {
        this.text = text == null ? "" : text;
        this.actions = actions == null ? new ArrayList<>() : actions;
        this.memoryUsage = memoryUsage == null ? "" : memoryUsage.trim();
        this.suggestedMemoryText = suggestedMemoryText == null ? "" : suggestedMemoryText.trim();
        this.suggestedMemoryCategory = suggestedMemoryCategory == null ? "" : suggestedMemoryCategory.trim();
        List<String> sources = new ArrayList<>();
        if (sourceUrls != null) {
            for (String url : sourceUrls) {
                if (sources.size() >= MAX_SOURCE_URLS) break;
                // Bounded and validated here rather than at each reader, so nothing downstream has
                // to defend itself against a scheme a provider put in a citation field.
                if (url == null || !RichAnswerUrlPolicy.isOpenableWebUrl(url.trim())) continue;
                String clean = url.trim();
                if (!sources.contains(clean)) sources.add(clean);
            }
        }
        this.sourceUrls = java.util.Collections.unmodifiableList(sources);
    }

    /** The same reply carrying the pages its search consulted. Text and actions are untouched. */
    public AssistantReply withSourceUrls(List<String> urls) {
        return new AssistantReply(text, actions, memoryUsage, suggestedMemoryText,
                suggestedMemoryCategory, urls);
    }

    public static AssistantReply fromJson(JSONObject obj) {
        String text = obj.optString("text", "");
        List<Action> actions = new ArrayList<>();
        JSONArray arr = obj.optJSONArray("actions");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject a = arr.optJSONObject(i);
                if (a == null) continue;
                actions.add(new Action(a.optString("type", ""), a.optJSONObject("params"), a.optBoolean("requiresConfirmation", false)));
            }
        }
        return new AssistantReply(text, actions);
    }

    public static final class Action {
        public final String type;
        public final JSONObject params;
        public final boolean requiresConfirmation;
        public Action(String type, JSONObject params, boolean confirm) {
            this.type = type == null ? "" : type;
            this.params = params == null ? new JSONObject() : params;
            // Some actions are too consequential to let their confirmation be optional. A model
            // that forgets requiresConfirmation, a restored backup, or a hand-built action must
            // not be able to reach a calendar write unconfirmed, so the requirement is a property
            // of the action itself rather than something each execution path remembers to check.
            //
            // A dial to a protected emergency or crisis number joins it for the same reason and a
            // sharper one: a model writing "call 911 if you are in danger" once returned a DIAL
            // action alongside the advice, and Orbit opened the dialer on its own. Making the
            // requirement a property of the action means no provider, router, or routine can
            // produce one that skips the question.
            this.requiresConfirmation = confirm
                    || CalendarActionExecutor.alwaysConfirms(this.type)
                    || EmergencyDialGuard.alwaysConfirms(this.type, this.params);
        }
    }
}
