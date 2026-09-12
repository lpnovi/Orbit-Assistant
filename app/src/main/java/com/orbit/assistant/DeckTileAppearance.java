package com.orbit.assistant;

import android.content.Context;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Stored visual choices for one action tile, deliberately separate from action configuration. */
public final class DeckTileAppearance {
    public static final String INHERIT = "inherit";
    public static final String ICON_ACCENT = "accent";
    public static final String ICON_MONOCHROME = "monochrome";

    public static final DeckTileAppearance DEFAULT = new DeckTileAppearance(
            INHERIT, INHERIT, INHERIT, true, null);

    public final String accent;
    public final String material;
    public final String iconTreatment;
    public final boolean showLabel;
    private final Map<String, Object> extras;

    public DeckTileAppearance(String accent, String material, String iconTreatment,
                              boolean showLabel) {
        this(accent, material, iconTreatment, showLabel, null);
    }

    private DeckTileAppearance(String accent, String material, String iconTreatment,
                               boolean showLabel, Map<String, Object> extras) {
        this.accent = validAccent(accent);
        this.material = validMaterial(material);
        this.iconTreatment = validIcon(iconTreatment);
        this.showLabel = showLabel;
        this.extras = extras == null ? new LinkedHashMap<>() : new LinkedHashMap<>(extras);
    }

    /** Stored values are always retained; only the effective rendering is entitlement-gated. */
    public DeckTileAppearance effective(Context context) {
        return context != null && OrbitProEntitlement.hasPro(context) ? this : DEFAULT;
    }

    public DeckTileAppearance withAccent(String value) {
        return new DeckTileAppearance(value, material, iconTreatment, showLabel, extras);
    }

    public DeckTileAppearance withMaterial(String value) {
        return new DeckTileAppearance(accent, value, iconTreatment, showLabel, extras);
    }

    public DeckTileAppearance withIconTreatment(String value) {
        return new DeckTileAppearance(accent, material, value, showLabel, extras);
    }

    public DeckTileAppearance withShowLabel(boolean value) {
        return new DeckTileAppearance(accent, material, iconTreatment, value, extras);
    }

    static DeckTileAppearance fromJson(JSONObject object) {
        if (object == null) return DEFAULT;
        Map<String, Object> extras = new LinkedHashMap<>();
        for (Iterator<String> it = object.keys(); it.hasNext();) {
            String key = it.next();
            if (!"accent".equals(key) && !"material".equals(key)
                    && !"icon".equals(key) && !"showLabel".equals(key)) {
                extras.put(key, object.opt(key));
            }
        }
        return new DeckTileAppearance(object.optString("accent", INHERIT),
                object.optString("material", INHERIT), object.optString("icon", INHERIT),
                object.optBoolean("showLabel", true), extras);
    }

    JSONObject toJson() throws Exception {
        JSONObject out = new JSONObject();
        for (Map.Entry<String, Object> entry : extras.entrySet()) out.put(entry.getKey(), entry.getValue());
        out.put("accent", accent);
        out.put("material", material);
        out.put("icon", iconTreatment);
        out.put("showLabel", showLabel);
        return out;
    }

    private static String validAccent(String value) {
        if (value == null || value.trim().isEmpty() || INHERIT.equals(value)) return INHERIT;
        String token = value.trim();
        return OrbitTheme.isHexToken(token) || OrbitPalette.DYNAMIC.equals(token)
                || OrbitPalette.entry(token) != null ? token : INHERIT;
    }

    private static String validMaterial(String value) {
        if (INHERIT.equals(value)) return INHERIT;
        String normalized = OrbitTheme.normalizeMaterial(value);
        return normalized.equals(value) ? value : INHERIT;
    }

    private static String validIcon(String value) {
        return ICON_ACCENT.equals(value) || ICON_MONOCHROME.equals(value) ? value : INHERIT;
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof DeckTileAppearance)) return false;
        DeckTileAppearance that = (DeckTileAppearance) other;
        return accent.equals(that.accent) && material.equals(that.material)
                && iconTreatment.equals(that.iconTreatment) && showLabel == that.showLabel
                && extras.equals(that.extras);
    }

    @Override public int hashCode() { return accent.hashCode() * 31 + material.hashCode(); }
}
