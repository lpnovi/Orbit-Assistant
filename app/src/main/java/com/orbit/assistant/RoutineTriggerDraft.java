package com.orbit.assistant;

import android.content.Context;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * A proposed automatic trigger that has not been created or scheduled.
 *
 * <p>Nothing here reaches {@link RoutineTriggerStore}, {@link RoutineTriggerScheduler}, or the
 * location scheduler. Planning a routine registers no alarm and no geofence: this only records
 * what Orbit intends to create, so the user can see it before saving. Every field is validated
 * against the same trigger model the manual editor uses, and a saved place is resolved locally
 * from its label so coordinates never travel through the planner.
 */
public final class RoutineTriggerDraft {
    private static final int DEFAULT_RADIUS_METERS = 150;
    private static final int MIN_RADIUS_METERS = 50;
    private static final int MAX_RADIUS_METERS = 5000;

    public final String type;
    public final String mode;
    public final int hour;
    public final int minute;
    /** Monday = bit 0 … Sunday = bit 6, for weekly and custom weekday sets. */
    public final int weekdayMask;
    public final String placeLabel;
    public final String placeId;
    public final double latitude;
    public final double longitude;
    public final float radiusMeters;
    public final String transition;
    /** True when the trigger is fully specified and could be created as-is. */
    public final boolean resolved;

    RoutineTriggerDraft(String type, String mode, int hour, int minute, int weekdayMask,
                        String placeLabel, String placeId, double latitude, double longitude,
                        float radiusMeters, String transition, boolean resolved) {
        this.type = type == null ? "" : type;
        this.mode = mode == null ? "" : mode;
        this.hour = hour;
        this.minute = minute;
        this.weekdayMask = weekdayMask;
        this.placeLabel = placeLabel == null ? "" : placeLabel;
        this.placeId = placeId == null ? "" : placeId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.radiusMeters = radiusMeters;
        this.transition = transition == null ? "" : transition;
        this.resolved = resolved;
    }

    public boolean isTime() {
        return RoutineTriggerStore.TYPE_TIME.equals(type);
    }

    public boolean isLocation() {
        return RoutineTriggerStore.TYPE_LOCATION.equals(type);
    }

    /**
     * Validates one planner-proposed trigger, or returns null when it names automation Orbit does
     * not support. An unknown saved place yields an unresolved location draft rather than invented
     * coordinates.
     */
    static RoutineTriggerDraft fromJson(Context context, JSONObject object, List<String> warnings) {
        if (object == null) return null;
        String type = object.optString("type", "").trim().toLowerCase(Locale.US);

        if (RoutineTriggerStore.TYPE_TIME.equals(type)) {
            String mode = normalizeMode(object.optString("recurrence", ""));
            if (mode.isEmpty()) {
                addWarning(warnings, "Choose how often this should repeat before saving");
                return null;
            }
            int hour = object.optInt("hour", -1);
            int minute = object.optInt("minute", 0);
            // A vague time is never guessed at. Without a real clock value there is nothing to
            // schedule, so the user is asked instead.
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                addWarning(warnings, "Choose a specific time before saving");
                return null;
            }
            int mask = object.optInt("weekdayMask", 0);
            if (RoutineTriggerStore.MODE_WEEKLY.equals(mode) && (mask <= 0 || mask > 0x7F)) {
                addWarning(warnings, "Choose which days this should run before saving");
                return null;
            }
            if (mask < 0 || mask > 0x7F) mask = 0;
            return new RoutineTriggerDraft(RoutineTriggerStore.TYPE_TIME, mode, hour, minute, mask,
                    "", "", 0d, 0d, 0f, "", true);
        }

        if (RoutineTriggerStore.TYPE_LOCATION.equals(type)) {
            String transition = object.optString("transition", "").trim().toLowerCase(Locale.US);
            if (!"arrive".equals(transition) && !"leave".equals(transition)) {
                addWarning(warnings, "Choose arrive or leave for the location trigger before saving");
                return null;
            }
            float radius = (float) object.optDouble("radiusMeters", DEFAULT_RADIUS_METERS);
            if (radius < MIN_RADIUS_METERS || radius > MAX_RADIUS_METERS) radius = DEFAULT_RADIUS_METERS;

            String label = object.optString("place", "").trim();
            // Resolved here, from local storage. The planner only ever sees the label.
            SavedPlaceStore.Place place = findPlace(context, label);
            if (place == null) {
                addWarning(warnings, label.isEmpty()
                        ? "Choose a location for this trigger before saving"
                        : "Choose a location for \"" + label + "\" before saving");
                return new RoutineTriggerDraft(RoutineTriggerStore.TYPE_LOCATION, "", 0, 0, 0,
                        label, "", 0d, 0d, radius, transition, false);
            }
            return new RoutineTriggerDraft(RoutineTriggerStore.TYPE_LOCATION, "", 0, 0, 0,
                    place.name, place.id, place.latitude, place.longitude, radius, transition, true);
        }

        if (!type.isEmpty()) addWarning(warnings, "Orbit couldn't set up that kind of automation");
        return null;
    }

    private static SavedPlaceStore.Place findPlace(Context context, String label) {
        if (context == null || label == null || label.trim().isEmpty()) return null;
        String wanted = label.trim().toLowerCase(Locale.US);
        for (SavedPlaceStore.Place place : SavedPlaceStore.list(context)) {
            if (place != null && place.name != null
                    && place.name.trim().toLowerCase(Locale.US).equals(wanted)) return place;
        }
        return null;
    }

    /** Only recurrences the trigger store already understands. */
    private static String normalizeMode(String value) {
        String mode = value == null ? "" : value.trim().toLowerCase(Locale.US);
        switch (mode) {
            case RoutineTriggerStore.MODE_ONCE:
            case RoutineTriggerStore.MODE_DAILY:
            case RoutineTriggerStore.MODE_WEEKDAYS:
            case RoutineTriggerStore.MODE_WEEKENDS:
            case RoutineTriggerStore.MODE_WEEKLY:
            case RoutineTriggerStore.MODE_CUSTOM:
                return mode;
            default:
                return "";
        }
    }

    private static void addWarning(List<String> warnings, String message) {
        if (warnings != null && !warnings.contains(message)) warnings.add(message);
    }

    JSONObject toJson() {
        try {
            return new JSONObject()
                    .put("type", type).put("mode", mode)
                    .put("hour", hour).put("minute", minute)
                    .put("weekdayMask", weekdayMask)
                    .put("placeLabel", placeLabel).put("placeId", placeId)
                    .put("latitude", latitude).put("longitude", longitude)
                    .put("radiusMeters", radiusMeters)
                    .put("transition", transition).put("resolved", resolved);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Re-validated on arrival, so a payload cannot smuggle in unsupported automation. */
    static RoutineTriggerDraft fromPayload(Context context, JSONObject object) {
        if (object == null) return null;
        String type = object.optString("type", "");
        if (RoutineTriggerStore.TYPE_TIME.equals(type)) {
            String mode = normalizeMode(object.optString("mode", ""));
            int hour = object.optInt("hour", -1);
            int minute = object.optInt("minute", -1);
            if (mode.isEmpty() || hour < 0 || hour > 23 || minute < 0 || minute > 59) return null;
            int mask = object.optInt("weekdayMask", 0);
            if (mask < 0 || mask > 0x7F) mask = 0;
            return new RoutineTriggerDraft(type, mode, hour, minute, mask,
                    "", "", 0d, 0d, 0f, "", true);
        }
        if (RoutineTriggerStore.TYPE_LOCATION.equals(type)) {
            String transition = object.optString("transition", "");
            if (!"arrive".equals(transition) && !"leave".equals(transition)) return null;
            float radius = (float) object.optDouble("radiusMeters", DEFAULT_RADIUS_METERS);
            if (radius < MIN_RADIUS_METERS || radius > MAX_RADIUS_METERS) radius = DEFAULT_RADIUS_METERS;
            return new RoutineTriggerDraft(type, "", 0, 0, 0,
                    object.optString("placeLabel", ""), object.optString("placeId", ""),
                    object.optDouble("latitude", 0d), object.optDouble("longitude", 0d),
                    radius, transition, object.optBoolean("resolved", false));
        }
        return null;
    }

    /** Short human summary for the preview, e.g. "Weekdays at 11:00 PM". */
    public String summary(Context context) {
        if (isTime()) {
            String when = RoutineActionCatalog.timeLabel(hour, minute);
            switch (mode) {
                case RoutineTriggerStore.MODE_ONCE: return "Once at " + when;
                case RoutineTriggerStore.MODE_DAILY: return "Daily at " + when;
                case RoutineTriggerStore.MODE_WEEKDAYS: return "Weekdays at " + when;
                case RoutineTriggerStore.MODE_WEEKENDS: return "Weekends at " + when;
                case RoutineTriggerStore.MODE_WEEKLY: return weekdayNames() + " at " + when;
                default: return "Repeating at " + when;
            }
        }
        if (isLocation()) {
            String place = placeLabel.isEmpty() ? "a place" : placeLabel;
            return ("arrive".equals(transition) ? "Arrive at " : "Leave ") + place;
        }
        return "";
    }

    private String weekdayNames() {
        String[] names = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            if ((weekdayMask & (1 << i)) == 0) continue;
            if (out.length() > 0) out.append(", ");
            out.append(names[i]);
        }
        return out.length() == 0 ? "Weekly" : out.toString();
    }

    /** Readiness for the preview, using the existing capability checks. */
    public String readiness(Context context) {
        if (context == null) return "";
        if (!resolved) return "Needs a location";
        if (isLocation()) {
            if (!RoutineLocationTriggerScheduler.hasBackgroundLocation(context))
                return "Needs location access";
            if (!RoutineLocationTriggerScheduler.isLocationEnabled(context))
                return "Phone location is off";
            return "Ready";
        }
        return "Ready";
    }
}
