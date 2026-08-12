package com.orbit.assistant;

import android.content.Context;
import android.location.Location;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;

/** Evaluates deterministic IF steps used inside saved Routine chains. */
public final class RoutineConditionEvaluator {
    public static final String MODE_TIME = "time";
    public static final String MODE_LOCATION = "location";
    public static final String MODE_TIME_AND_LOCATION = "time_and_location";

    public static final class Result {
        public final boolean evaluable;
        public final boolean matched;
        public final boolean permissionRequired;
        public final String message;

        private Result(boolean evaluable, boolean matched, boolean permissionRequired, String message) {
            this.evaluable = evaluable;
            this.matched = matched;
            this.permissionRequired = permissionRequired;
            this.message = message == null ? "" : message;
        }

        public static Result match(String message) { return new Result(true, true, false, message); }
        public static Result noMatch(String message) { return new Result(true, false, false, message); }
        public static Result permission(String message) { return new Result(false, false, true, message); }
        public static Result unavailable(String message) { return new Result(false, false, false, message); }
    }

    private RoutineConditionEvaluator() {}

    public static boolean isCondition(AssistantReply.Action action) {
        return action != null && RoutineActionCatalog.IF_CONDITION.equals(action.type);
    }

    public static int gatedSteps(AssistantReply.Action action) {
        if (!isCondition(action)) return 0;
        JSONObject p = action.params == null ? new JSONObject() : action.params;
        return Math.max(1, Math.min(5, p.optInt("nextSteps", 1)));
    }

    public static boolean needsLocation(AssistantReply.Action action) {
        if (!isCondition(action)) return false;
        String mode = mode(action);
        return MODE_LOCATION.equals(mode) || MODE_TIME_AND_LOCATION.equals(mode);
    }

    public static String mode(AssistantReply.Action action) {
        if (!isCondition(action)) return "";
        JSONObject p = action.params == null ? new JSONObject() : action.params;
        String mode = p.optString("mode", MODE_TIME).trim().toLowerCase(Locale.US);
        if (MODE_LOCATION.equals(mode) || MODE_TIME_AND_LOCATION.equals(mode)) return mode;
        return MODE_TIME;
    }

    public static Result evaluate(Context c, AssistantReply.Action action) {
        if (!isCondition(action)) return Result.unavailable("Not a Routine condition");
        JSONObject p = action.params == null ? new JSONObject() : action.params;
        if (p.has("_orbitLockedMatch")) {
            boolean locked = p.optBoolean("_orbitLockedMatch", false);
            String lockedMessage = p.optString("_orbitLockedMessage", locked ? "Condition matched" : "Condition not met");
            return locked ? Result.match(lockedMessage) : Result.noMatch(lockedMessage);
        }
        String mode = mode(action);

        boolean timeMatch = true;
        if (MODE_TIME.equals(mode) || MODE_TIME_AND_LOCATION.equals(mode)) {
            int start = p.optInt("startMinute", -1);
            int end = p.optInt("endMinute", -1);
            if (start < 0 || start > 1439 || end < 0 || end > 1439) {
                return Result.unavailable("This time condition is incomplete");
            }
            Calendar now = Calendar.getInstance();
            int minuteNow = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
            // Equal endpoints intentionally mean all day. Crossing midnight is supported.
            if (start == end) timeMatch = true;
            else if (start < end) timeMatch = minuteNow >= start && minuteNow < end;
            else timeMatch = minuteNow >= start || minuteNow < end;
        }

        boolean locationMatch = true;
        if (MODE_LOCATION.equals(mode) || MODE_TIME_AND_LOCATION.equals(mode)) {
            if (c == null || !RoutineLocationTriggerScheduler.hasFineLocation(c)) {
                return Result.permission("Precise location access is required for this condition");
            }
            if (!RoutineLocationTriggerScheduler.isLocationEnabled(c)) {
                return Result.unavailable("Android location is turned off");
            }
            double latitude = p.optDouble("latitude", Double.NaN);
            double longitude = p.optDouble("longitude", Double.NaN);
            float radius = (float) p.optDouble("radiusMeters", 200d);
            if (Double.isNaN(latitude) || latitude < -90d || latitude > 90d ||
                    Double.isNaN(longitude) || longitude < -180d || longitude > 180d) {
                return Result.unavailable("This location condition is incomplete");
            }
            radius = Math.max(RoutineTriggerStore.MIN_LOCATION_RADIUS_METERS,
                    Math.min(RoutineTriggerStore.MAX_LOCATION_RADIUS_METERS, radius));
            Location current = RoutineLocationTriggerScheduler.bestLastKnownLocation(c);
            if (current == null) {
                return Result.unavailable("Orbit could not determine the current location yet");
            }
            float[] distance = new float[1];
            Location.distanceBetween(current.getLatitude(), current.getLongitude(), latitude, longitude, distance);
            locationMatch = distance[0] <= radius;
        }

        boolean matched = timeMatch && locationMatch;
        if (matched) return Result.match("Condition matched");
        if (MODE_TIME_AND_LOCATION.equals(mode)) return Result.noMatch("Time/location condition not met");
        if (MODE_LOCATION.equals(mode)) return Result.noMatch("Location condition not met");
        return Result.noMatch("Time condition not met");
    }
}
