package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Durable local storage for automatic routine triggers. */
public final class RoutineTriggerStore {
    private static final String FILE = "orbit_routine_triggers";
    private static final String KEY = "triggers_v1";

    public static final int MAX_TRIGGERS_TOTAL = 200;
    public static final int MAX_TRIGGERS_PER_ROUTINE = 12;

    public static final String TYPE_TIME = "time";
    public static final String TYPE_LOCATION = "location";

    public static final String MODE_ONCE = "once";
    public static final String MODE_DAILY = "daily";
    public static final String MODE_WEEKDAYS = "weekdays";
    public static final String MODE_WEEKENDS = "weekends";
    public static final String MODE_WEEKLY = "weekly";
    public static final String MODE_CUSTOM = "custom";

    public static final String UNIT_DAYS = "days";
    public static final String UNIT_WEEKS = "weeks";
    public static final String UNIT_MONTHS = "months";

    public static final String LOCATION_ENTER = "enter";
    public static final String LOCATION_EXIT = "exit";

    public static final float MIN_LOCATION_RADIUS_METERS = 100f;
    public static final float MAX_LOCATION_RADIUS_METERS = 5000f;

    public static final class Trigger {
        public final String id;
        public final String routineId;
        public final String type;
        public final boolean enabled;

        // Time-trigger fields.
        public final String mode;
        public final int hour;
        public final int minute;
        public final int startYear;
        public final int startMonth;
        public final int startDay;
        /** Monday = bit 0 ... Sunday = bit 6. */
        public final int weekdayMask;
        public final int intervalCount;
        public final String intervalUnit;

        // Location-trigger fields.
        public final String locationName;
        public final double latitude;
        public final double longitude;
        public final float radiusMeters;
        public final String locationTransition;

        // Shared state.
        public final long createdAt;
        public final long updatedAt;
        public final long lastRunAt;
        public final long nextRunAt;
        public final String lastResult;

        public Trigger(String id, String routineId, String type, boolean enabled, String mode,
                       int hour, int minute, int startYear, int startMonth, int startDay,
                       int weekdayMask, int intervalCount, String intervalUnit,
                       String locationName, double latitude, double longitude, float radiusMeters,
                       String locationTransition, long createdAt, long updatedAt, long lastRunAt,
                       long nextRunAt, String lastResult) {
            this.id = clean(id);
            this.routineId = clean(routineId);
            this.type = clean(type).isEmpty() ? TYPE_TIME : clean(type).toLowerCase(Locale.US);
            this.enabled = enabled;
            this.mode = clean(mode).toLowerCase(Locale.US);
            this.hour = Math.max(0, Math.min(23, hour));
            this.minute = Math.max(0, Math.min(59, minute));
            this.startYear = startYear;
            this.startMonth = startMonth;
            this.startDay = startDay;
            this.weekdayMask = weekdayMask & 0x7f;
            this.intervalCount = Math.max(1, intervalCount);
            this.intervalUnit = clean(intervalUnit).toLowerCase(Locale.US);
            this.locationName = sanitizeLocationName(locationName);
            this.latitude = latitude;
            this.longitude = longitude;
            this.radiusMeters = Math.max(MIN_LOCATION_RADIUS_METERS,
                    Math.min(MAX_LOCATION_RADIUS_METERS, radiusMeters));
            String transition = clean(locationTransition).toLowerCase(Locale.US);
            this.locationTransition = LOCATION_EXIT.equals(transition) ? LOCATION_EXIT : LOCATION_ENTER;
            this.createdAt = createdAt <= 0 ? System.currentTimeMillis() : createdAt;
            this.updatedAt = updatedAt <= 0 ? this.createdAt : updatedAt;
            this.lastRunAt = Math.max(0L, lastRunAt);
            this.nextRunAt = Math.max(0L, nextRunAt);
            this.lastResult = lastResult == null ? "" : lastResult.trim();
        }

        public Trigger withSchedule(boolean newEnabled, String newMode, int newHour, int newMinute,
                                    int year, int month, int day, int newWeekdayMask,
                                    int newIntervalCount, String newIntervalUnit) {
            return new Trigger(id, routineId, type, newEnabled, newMode, newHour, newMinute,
                    year, month, day, newWeekdayMask, newIntervalCount, newIntervalUnit,
                    locationName, latitude, longitude, radiusMeters, locationTransition,
                    createdAt, System.currentTimeMillis(), lastRunAt, 0L, lastResult);
        }

        public Trigger withLocation(boolean newEnabled, String newLocationName, double newLatitude,
                                    double newLongitude, float newRadiusMeters,
                                    String newLocationTransition) {
            return new Trigger(id, routineId, type, newEnabled, mode, hour, minute,
                    startYear, startMonth, startDay, weekdayMask, intervalCount, intervalUnit,
                    newLocationName, newLatitude, newLongitude, newRadiusMeters,
                    newLocationTransition, createdAt, System.currentTimeMillis(), lastRunAt,
                    0L, lastResult);
        }

        public Trigger withEnabled(boolean newEnabled) {
            return new Trigger(id, routineId, type, newEnabled, mode, hour, minute,
                    startYear, startMonth, startDay, weekdayMask, intervalCount, intervalUnit,
                    locationName, latitude, longitude, radiusMeters, locationTransition,
                    createdAt, System.currentTimeMillis(), lastRunAt,
                    newEnabled && TYPE_TIME.equals(type) ? nextRunAt : 0L, lastResult);
        }

        public Trigger withRunState(long newLastRunAt, long newNextRunAt, String result, boolean newEnabled) {
            return new Trigger(id, routineId, type, newEnabled, mode, hour, minute,
                    startYear, startMonth, startDay, weekdayMask, intervalCount, intervalUnit,
                    locationName, latitude, longitude, radiusMeters, locationTransition,
                    createdAt, updatedAt, newLastRunAt, newNextRunAt, result);
        }

        public Trigger withNextRun(long newNextRunAt) {
            return new Trigger(id, routineId, type, enabled, mode, hour, minute,
                    startYear, startMonth, startDay, weekdayMask, intervalCount, intervalUnit,
                    locationName, latitude, longitude, radiusMeters, locationTransition,
                    createdAt, updatedAt, lastRunAt, newNextRunAt, lastResult);
        }
    }

    private RoutineTriggerStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static Trigger createTime(String routineId, String mode, int hour, int minute,
                                     int year, int month, int day, int weekdayMask,
                                     int intervalCount, String intervalUnit) {
        long now = System.currentTimeMillis();
        return new Trigger(UUID.randomUUID().toString(), routineId, TYPE_TIME, true, mode,
                hour, minute, year, month, day, weekdayMask, intervalCount, intervalUnit,
                "", 0d, 0d, 200f, LOCATION_ENTER,
                now, now, 0L, 0L, "");
    }

    public static Trigger createLocation(String routineId, String locationName, double latitude,
                                         double longitude, float radiusMeters, String transition) {
        long now = System.currentTimeMillis();
        return new Trigger(UUID.randomUUID().toString(), routineId, TYPE_LOCATION, true, "",
                0, 0, 0, 0, 0, 0, 1, UNIT_DAYS,
                locationName, latitude, longitude, radiusMeters, transition,
                now, now, 0L, 0L, "");
    }

    public static synchronized List<Trigger> list(Context c) {
        if (c == null) return Collections.emptyList();
        List<Trigger> out = new ArrayList<>();
        String raw = prefs(c).getString(KEY, "[]");
        try {
            JSONArray arr = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < arr.length() && out.size() < MAX_TRIGGERS_TOTAL; i++) {
                Trigger trigger = fromJson(arr.optJSONObject(i));
                if (trigger != null) out.add(trigger);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static synchronized List<Trigger> listForRoutine(Context c, String routineId) {
        if (routineId == null || routineId.trim().isEmpty()) return Collections.emptyList();
        List<Trigger> out = new ArrayList<>();
        for (Trigger trigger : list(c)) {
            if (routineId.equals(trigger.routineId)) out.add(trigger);
        }
        return out;
    }

    public static synchronized Trigger findById(Context c, String id) {
        if (id == null || id.trim().isEmpty()) return null;
        for (Trigger trigger : list(c)) if (id.equals(trigger.id)) return trigger;
        return null;
    }

    public static synchronized boolean hasEnabledScheduleConflict(Context c, Trigger candidate) {
        if (c == null || candidate == null || !candidate.enabled) return false;
        for (Trigger existing : listForRoutine(c, candidate.routineId)) {
            if (existing.id.equals(candidate.id) || !existing.enabled) continue;
            if (sameSchedule(existing, candidate)) return true;
        }
        return false;
    }

    private static boolean sameSchedule(Trigger a, Trigger b) {
        if (a == null || b == null) return false;
        if (!a.type.equals(b.type)) return false;
        if (TYPE_LOCATION.equals(a.type)) {
            return a.locationTransition.equals(b.locationTransition) &&
                    Double.compare(a.latitude, b.latitude) == 0 &&
                    Double.compare(a.longitude, b.longitude) == 0 &&
                    Float.compare(a.radiusMeters, b.radiusMeters) == 0;
        }
        if (!a.mode.equals(b.mode)) return false;
        if (a.hour != b.hour || a.minute != b.minute ||
                a.startYear != b.startYear || a.startMonth != b.startMonth || a.startDay != b.startDay) return false;
        if (MODE_WEEKLY.equals(a.mode)) {
            return a.weekdayMask == b.weekdayMask && a.intervalCount == b.intervalCount;
        }
        if (MODE_CUSTOM.equals(a.mode)) {
            if (!a.intervalUnit.equals(b.intervalUnit) || a.intervalCount != b.intervalCount) return false;
            return !UNIT_WEEKS.equals(a.intervalUnit) || a.weekdayMask == b.weekdayMask;
        }
        return true;
    }

    public static synchronized boolean upsert(Context c, Trigger trigger) {
        if (c == null || !valid(trigger)) return false;
        List<Trigger> triggers = new ArrayList<>(list(c));
        int replace = -1;
        int forRoutine = 0;
        for (int i = 0; i < triggers.size(); i++) {
            Trigger existing = triggers.get(i);
            if (existing.routineId.equals(trigger.routineId)) forRoutine++;
            if (existing.id.equals(trigger.id)) replace = i;
        }
        if (replace < 0 && triggers.size() >= MAX_TRIGGERS_TOTAL) return false;
        if (replace < 0 && forRoutine >= MAX_TRIGGERS_PER_ROUTINE) return false;
        if (replace >= 0) triggers.set(replace, trigger); else triggers.add(trigger);
        return write(c, triggers);
    }

    public static synchronized boolean delete(Context c, String id) {
        if (c == null || id == null) return false;
        List<Trigger> triggers = new ArrayList<>(list(c));
        boolean removed = false;
        for (int i = triggers.size() - 1; i >= 0; i--) {
            if (id.equals(triggers.get(i).id)) {
                triggers.remove(i);
                removed = true;
            }
        }
        return removed && write(c, triggers);
    }

    public static synchronized int deleteForRoutine(Context c, String routineId) {
        if (c == null || routineId == null) return 0;
        List<Trigger> triggers = new ArrayList<>(list(c));
        int removed = 0;
        for (int i = triggers.size() - 1; i >= 0; i--) {
            if (routineId.equals(triggers.get(i).routineId)) {
                triggers.remove(i);
                removed++;
            }
        }
        if (removed > 0) write(c, triggers);
        return removed;
    }

    public static synchronized boolean updateRunState(Context c, String id, long lastRunAt,
                                                      long nextRunAt, String result,
                                                      boolean enabled) {
        Trigger trigger = findById(c, id);
        if (trigger == null) return false;
        return upsert(c, trigger.withRunState(lastRunAt, nextRunAt, result, enabled));
    }

    private static boolean write(Context c, List<Trigger> triggers) {
        JSONArray arr = new JSONArray();
        for (Trigger trigger : triggers) {
            JSONObject obj = toJson(trigger);
            if (obj != null) arr.put(obj);
        }
        return prefs(c).edit().putString(KEY, arr.toString()).commit();
    }

    private static JSONObject toJson(Trigger t) {
        if (!valid(t)) return null;
        try {
            JSONObject o = new JSONObject();
            o.put("id", t.id);
            o.put("routineId", t.routineId);
            o.put("type", t.type);
            o.put("enabled", t.enabled);
            o.put("mode", t.mode);
            o.put("hour", t.hour);
            o.put("minute", t.minute);
            o.put("startYear", t.startYear);
            o.put("startMonth", t.startMonth);
            o.put("startDay", t.startDay);
            o.put("weekdayMask", t.weekdayMask);
            o.put("intervalCount", t.intervalCount);
            o.put("intervalUnit", t.intervalUnit);
            o.put("locationName", t.locationName);
            o.put("latitude", t.latitude);
            o.put("longitude", t.longitude);
            o.put("radiusMeters", t.radiusMeters);
            o.put("locationTransition", t.locationTransition);
            o.put("createdAt", t.createdAt);
            o.put("updatedAt", t.updatedAt);
            o.put("lastRunAt", t.lastRunAt);
            o.put("nextRunAt", t.nextRunAt);
            o.put("lastResult", t.lastResult);
            return o;
        } catch (Exception ignored) { return null; }
    }

    private static Trigger fromJson(JSONObject o) {
        if (o == null) return null;
        try {
            Trigger t = new Trigger(
                    o.optString("id", ""), o.optString("routineId", ""),
                    o.optString("type", TYPE_TIME), o.optBoolean("enabled", true),
                    o.optString("mode", MODE_DAILY), o.optInt("hour", 8),
                    o.optInt("minute", 0), o.optInt("startYear", 0),
                    o.optInt("startMonth", 0), o.optInt("startDay", 0),
                    o.optInt("weekdayMask", 0), o.optInt("intervalCount", 1),
                    o.optString("intervalUnit", UNIT_DAYS),
                    o.optString("locationName", ""), o.optDouble("latitude", 0d),
                    o.optDouble("longitude", 0d), (float) o.optDouble("radiusMeters", 200d),
                    o.optString("locationTransition", LOCATION_ENTER),
                    o.optLong("createdAt", 0), o.optLong("updatedAt", 0),
                    o.optLong("lastRunAt", 0), o.optLong("nextRunAt", 0),
                    o.optString("lastResult", ""));
            return valid(t) ? t : null;
        } catch (Exception ignored) { return null; }
    }

    private static boolean valid(Trigger t) {
        if (t == null || t.id.isEmpty() || t.routineId.isEmpty()) return false;
        if (TYPE_LOCATION.equals(t.type)) {
            if (t.locationName.isEmpty()) return false;
            if (Double.isNaN(t.latitude) || Double.isInfinite(t.latitude) || t.latitude < -90d || t.latitude > 90d) return false;
            if (Double.isNaN(t.longitude) || Double.isInfinite(t.longitude) || t.longitude < -180d || t.longitude > 180d) return false;
            if (t.radiusMeters < MIN_LOCATION_RADIUS_METERS || t.radiusMeters > MAX_LOCATION_RADIUS_METERS) return false;
            return LOCATION_ENTER.equals(t.locationTransition) || LOCATION_EXIT.equals(t.locationTransition);
        }
        if (!TYPE_TIME.equals(t.type)) return false;
        if (t.startYear < 2000 || t.startMonth < 1 || t.startMonth > 12 || t.startDay < 1 || t.startDay > 31) return false;
        try { LocalDate.of(t.startYear, t.startMonth, t.startDay); }
        catch (Exception invalidDate) { return false; }
        if (t.hour < 0 || t.hour > 23 || t.minute < 0 || t.minute > 59) return false;
        if (MODE_ONCE.equals(t.mode) || MODE_DAILY.equals(t.mode) || MODE_WEEKDAYS.equals(t.mode) ||
                MODE_WEEKENDS.equals(t.mode)) return true;
        if (MODE_WEEKLY.equals(t.mode)) return t.weekdayMask != 0 && t.intervalCount >= 1 && t.intervalCount <= 52;
        if (MODE_CUSTOM.equals(t.mode)) {
            if (UNIT_DAYS.equals(t.intervalUnit)) return t.intervalCount >= 1 && t.intervalCount <= 365;
            if (UNIT_WEEKS.equals(t.intervalUnit)) return t.intervalCount >= 1 && t.intervalCount <= 52 && t.weekdayMask != 0;
            if (UNIT_MONTHS.equals(t.intervalUnit)) return t.intervalCount >= 1 && t.intervalCount <= 60;
            return false;
        }
        return false;
    }

    public static String sanitizeLocationName(String value) {
        String clean = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (clean.length() > 60) clean = clean.substring(0, 60).trim();
        return clean;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
