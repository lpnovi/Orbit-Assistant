package com.orbit.assistant;

import java.util.Locale;

/**
 * How much reasoning a request asks its model to spend.
 *
 * <p>One ordered list for every model, so "nearest supported strength" means the same thing on every
 * screen. Which of these a given model accepts is a fact about the model and lives in
 * {@link AiModelSpec}; nothing outside the catalog decides it.
 */
public enum AiStrength {
    NONE("none", "None"),
    LOW("low", "Low"),
    MEDIUM("medium", "Medium"),
    HIGH("high", "High"),
    XHIGH("xhigh", "Extra High"),
    MAX("max", "Max");

    /** The value sent to the backend as {@code reasoning.effort}. Never renamed. */
    public final String id;
    /** What the user reads. */
    public final String label;

    AiStrength(String id, String label) {
        this.id = id;
        this.label = label;
    }

    /** The strength with this backend id or label, or null for anything else. */
    public static AiStrength fromId(String value) {
        if (value == null) return null;
        String v = value.trim().toLowerCase(Locale.US).replace("-", "").replace(" ", "");
        if (v.isEmpty()) return null;
        for (AiStrength s : values()) {
            if (s.id.equals(v) || s.label.toLowerCase(Locale.US).replace(" ", "").equals(v)) return s;
        }
        return null;
    }
}
