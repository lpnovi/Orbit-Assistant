package com.orbit.assistant;

import org.json.JSONObject;

/** Shared definition of action-card reversals that are safe to expose as one-tap controls. */
public final class ReversibleActionHelper {
    private ReversibleActionHelper() {}

    public static boolean canTurnOff(AssistantReply.Action action) {
        if (action == null || action.type == null) return false;
        JSONObject p = action.params == null ? new JSONObject() : action.params;
        if ("FLASHLIGHT".equalsIgnoreCase(action.type)) return p.optBoolean("on", true);
        if ("SET_DND".equalsIgnoreCase(action.type)) return p.optBoolean("enabled", true);
        return false;
    }

    public static boolean isOffState(AssistantReply.Action action) {
        if (action == null || action.type == null) return false;
        JSONObject p = action.params == null ? new JSONObject() : action.params;
        if ("FLASHLIGHT".equalsIgnoreCase(action.type)) return !p.optBoolean("on", true);
        if ("SET_DND".equalsIgnoreCase(action.type)) return !p.optBoolean("enabled", true);
        return false;
    }

    public static AssistantReply.Action turnOffAction(AssistantReply.Action action) {
        if (action == null || action.type == null) return null;
        try {
            if ("FLASHLIGHT".equalsIgnoreCase(action.type)) {
                return new AssistantReply.Action("FLASHLIGHT", new JSONObject().put("on", false), false);
            }
            if ("SET_DND".equalsIgnoreCase(action.type)) {
                return new AssistantReply.Action("SET_DND", new JSONObject().put("enabled", false), false);
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static boolean isRedundantOffDetail(AssistantReply.Action action, String detail) {
        if (action == null || detail == null) return false;
        String normalized = detail.trim().toLowerCase(java.util.Locale.US);
        if ("FLASHLIGHT".equalsIgnoreCase(action.type)) return "flashlight off".equals(normalized);
        if ("SET_DND".equalsIgnoreCase(action.type)) return "do not disturb disabled".equals(normalized);
        return false;
    }
}
