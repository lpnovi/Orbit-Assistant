package com.orbit.assistant;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public final class AssistantReply {
    public final String text;
    public final List<Action> actions;
    /** Human-readable snapshot of the memories supplied to the AI for this turn. */
    public final String memoryUsage;
    /** Optional local suggestion. Orbit never saves this without confirmation. */
    public final String suggestedMemoryText;
    public final String suggestedMemoryCategory;

    public AssistantReply(String text) {
        this(text, new ArrayList<>(), "", "", "");
    }

    public AssistantReply(String text, List<Action> actions) {
        this(text, actions, "", "", "");
    }

    public AssistantReply(String text, List<Action> actions, String memoryUsage,
                          String suggestedMemoryText, String suggestedMemoryCategory) {
        this.text = text == null ? "" : text;
        this.actions = actions == null ? new ArrayList<>() : actions;
        this.memoryUsage = memoryUsage == null ? "" : memoryUsage.trim();
        this.suggestedMemoryText = suggestedMemoryText == null ? "" : suggestedMemoryText.trim();
        this.suggestedMemoryCategory = suggestedMemoryCategory == null ? "" : suggestedMemoryCategory.trim();
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
