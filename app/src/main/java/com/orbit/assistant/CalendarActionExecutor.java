package com.orbit.assistant;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Orbit's direct Calendar writer.
 *
 * <p>This is the difference between {@code CREATE_EVENT} and {@code ADD_CALENDAR_EVENTS}.
 * {@code CREATE_EVENT} opens Android's event composer and the user presses Save; it remains
 * useful and is untouched. {@code ADD_CALENDAR_EVENTS} is Orbit persisting events itself through
 * {@link CalendarContract}, after explicit confirmation and a real permission grant.
 *
 * <p>The rule that shapes everything here is that a language model must never be the thing that
 * says a calendar write succeeded. A model can find a schedule and propose it; only this class,
 * having inserted a row and then read it back, may produce "Added 12 events to Personal." Every
 * count in the final message comes from the provider, not from the plan.
 *
 * <p>The other rule is that nothing is invented. A game with no announced kickoff becomes an
 * all-day event marked Time TBA on the correct date, never a 9:00 AM event that looks authoritative
 * and is wrong. An impossible date is rejected rather than rolled forward into a different day.
 */
public final class CalendarActionExecutor {
    public static final String ACTION_TYPE = "ADD_CALENDAR_EVENTS";

    /** A malformed plan must not be able to start an unbounded write. */
    public static final int MAX_EVENTS = 50;
    /**
     * The neutral length Android's required end time gets when only a start time is known.
     *
     * <p>Deliberately one documented default rather than a guess dressed up as a source fact: it
     * is never written into the event's description and never described as coming from anywhere.
     * What the acceptance case cares about is the date and the announced start, and both are
     * preserved exactly.
     */
    public static final int DEFAULT_DURATION_MINUTES = 60;
    public static final int MAX_DURATION_MINUTES = 24 * 60;
    /** How an all-day event whose start time is genuinely unknown is marked. */
    public static final String TBA_MARKER = "Time TBA";

    private static final int MAX_TITLE_CHARS = 240;
    private static final int MAX_TEXT_CHARS = 1000;
    /** Duplicate scanning window around a candidate start, wide enough to survive timezones. */
    private static final long DUPLICATE_WINDOW_MS = 36L * 60L * 60L * 1000L;

    private CalendarActionExecutor() {}

    public static boolean isCalendarWrite(AssistantReply.Action action) {
        return action != null && alwaysConfirms(action.type);
    }

    /**
     * True for an action that may never run without an explicit confirmation, whatever the
     * envelope that carried it happened to say.
     */
    public static boolean alwaysConfirms(String type) {
        return type != null && ACTION_TYPE.equalsIgnoreCase(type.trim());
    }

    // ---- validated plan -------------------------------------------------------------------------

    /** One event after validation. Every field here is already known to be usable. */
    public static final class Event {
        public final String title;
        public final LocalDate date;
        public final boolean allDay;
        public final boolean timeTba;
        public final int hour;
        public final int minute;
        public final int durationMinutes;
        public final ZoneId zone;
        public final String location;
        public final String description;
        public final String sourceUrl;

        Event(String title, LocalDate date, boolean allDay, boolean timeTba, int hour, int minute,
              int durationMinutes, ZoneId zone, String location, String description,
              String sourceUrl) {
            this.title = title;
            this.date = date;
            this.allDay = allDay;
            this.timeTba = timeTba;
            this.hour = hour;
            this.minute = minute;
            this.durationMinutes = durationMinutes;
            this.zone = zone;
            this.location = location;
            this.description = description;
            this.sourceUrl = sourceUrl;
        }

        /**
         * The start instant Android stores.
         *
         * <p>All-day events follow {@link CalendarContract}'s documented rule exactly: midnight
         * <em>UTC</em> on the event's date, paired with {@code EVENT_TIMEZONE = "UTC"}. That pairing
         * is what stops a timezone conversion from moving a Saturday game onto Friday or Sunday,
         * which is the classic way all-day calendar imports go wrong.
         */
        public long startMillis() {
            if (allDay) return date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            return date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli();
        }

        public long endMillis() {
            if (allDay) return date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            return startMillis() + durationMinutes * 60_000L;
        }

        /** The timezone written alongside the instants, per the all-day contract above. */
        public String timezoneId() { return allDay ? "UTC" : zone.getId(); }

        /** The stored description, including the Time TBA marker when the start is unknown. */
        public String storedDescription() {
            StringBuilder out = new StringBuilder();
            if (!description.isEmpty()) out.append(description);
            if (timeTba) {
                if (out.length() > 0) out.append("\n\n");
                out.append(TBA_MARKER).append(" · start time not announced yet.");
            }
            if (!sourceUrl.isEmpty()) {
                if (out.length() > 0) out.append("\n\n");
                out.append(sourceUrl);
            }
            return out.toString();
        }

        public String shortLabel() {
            String when = date.format(DateTimeFormatter.ofPattern("MMM d", Locale.US));
            if (allDay) return when + " · " + title + (timeTba ? " (" + TBA_MARKER + ")" : "");
            return when + " " + String.format(Locale.US, "%d:%02d", hour, minute) + " · " + title;
        }
    }

    /** The outcome of validating an action's params. Either a usable plan, or one clear reason. */
    public static final class Plan {
        public final List<Event> events;
        public final String error;

        Plan(List<Event> events, String error) {
            this.events = events == null ? new ArrayList<>() : events;
            this.error = error == null ? "" : error;
        }

        public boolean ok() { return error.isEmpty() && !events.isEmpty(); }
        public int size() { return events.size(); }

        public int tbaCount() {
            int count = 0;
            for (Event e : events) if (e.timeTba) count++;
            return count;
        }

        public LocalDate firstDate() {
            LocalDate first = null;
            for (Event e : events) if (first == null || e.date.isBefore(first)) first = e.date;
            return first;
        }

        public LocalDate lastDate() {
            LocalDate last = null;
            for (Event e : events) if (last == null || e.date.isAfter(last)) last = e.date;
            return last;
        }

        /** A compact human date range, e.g. {@code Sep 5 - Nov 28, 2026}. */
        public String dateRange() {
            LocalDate first = firstDate();
            LocalDate last = lastDate();
            if (first == null || last == null) return "";
            DateTimeFormatter dayMonth = DateTimeFormatter.ofPattern("MMM d", Locale.US);
            if (first.equals(last)) {
                return first.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US));
            }
            if (first.getYear() == last.getYear()) {
                return first.format(dayMonth) + " - " + last.format(dayMonth) + ", " + last.getYear();
            }
            DateTimeFormatter full = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);
            return first.format(full) + " - " + last.format(full);
        }
    }

    /**
     * Validates an action's params without touching the device.
     *
     * <p>Everything a model can get wrong is caught here: too many events, a missing or impossible
     * date, an hour that is not an hour, a timezone id that does not exist. Rejection is always
     * preferred to normalisation, because a silently corrected date produces a confident,
     * plausible, wrong calendar entry.
     */
    public static Plan parse(JSONObject params) {
        return parse(params, ZoneId.systemDefault());
    }

    /** The same validation with an explicit device zone, so the fallback is testable. */
    public static Plan parse(JSONObject params, ZoneId deviceZone) {
        if (params == null) return new Plan(null, "No calendar events were provided");
        JSONArray raw = params.optJSONArray("events");
        if (raw == null || raw.length() == 0) {
            return new Plan(null, "No calendar events were provided");
        }
        if (raw.length() > MAX_EVENTS) {
            return new Plan(null, "That is more than " + MAX_EVENTS
                    + " events; Orbit will not add an unbounded schedule in one step");
        }
        ZoneId fallbackZone = deviceZone == null ? ZoneId.systemDefault() : deviceZone;
        List<Event> events = new ArrayList<>();
        for (int i = 0; i < raw.length(); i++) {
            JSONObject item = raw.optJSONObject(i);
            if (item == null) return new Plan(null, "Event " + (i + 1) + " is not readable");

            String title = clip(item.optString("title", "").trim(), MAX_TITLE_CHARS);
            if (title.isEmpty()) return new Plan(null, "Event " + (i + 1) + " has no title");

            String dateText = item.optString("date", "").trim();
            LocalDate date = strictDate(dateText);
            if (date == null) {
                return new Plan(null, "Event " + (i + 1) + " has an invalid date"
                        + (dateText.isEmpty() ? "" : " (" + clip(dateText, 40) + ")"));
            }

            // A supplied timezone must be real. Silently accepting a nonsense id and falling back
            // to the device zone would put the event at the wrong hour with nothing to show for it.
            String zoneText = item.optString("timezone", "").trim();
            ZoneId zone = fallbackZone;
            if (!zoneText.isEmpty()) {
                try {
                    zone = ZoneId.of(zoneText);
                } catch (DateTimeException | IllegalArgumentException invalid) {
                    return new Plan(null, "Event " + (i + 1) + " has an unknown timezone ("
                            + clip(zoneText, 40) + ")");
                }
            }

            boolean timeTba = item.optBoolean("timeTba", false);
            boolean requestedAllDay = item.optBoolean("allDay", false);
            boolean hasHour = item.has("hour") && !item.isNull("hour");
            boolean hasMinute = item.has("minute") && !item.isNull("minute");
            int hour = item.optInt("hour", -1);
            int minute = hasMinute ? item.optInt("minute", 0) : 0;

            // The date is known and the start genuinely is not: an all-day event on the right day,
            // marked Time TBA. No invented 9:00 AM, no invented noon.
            boolean allDay = requestedAllDay || timeTba || !hasHour;
            if (allDay) {
                if (!requestedAllDay) timeTba = true;
                hour = 0;
                minute = 0;
            } else {
                if (hour < 0 || hour > 23) {
                    return new Plan(null, "Event " + (i + 1) + " has an invalid hour");
                }
                if (minute < 0 || minute > 59) {
                    return new Plan(null, "Event " + (i + 1) + " has an invalid minute");
                }
            }

            int duration = DEFAULT_DURATION_MINUTES;
            if (item.has("durationMinutes") && !item.isNull("durationMinutes")) {
                duration = item.optInt("durationMinutes", DEFAULT_DURATION_MINUTES);
                if (duration < 1 || duration > MAX_DURATION_MINUTES) {
                    return new Plan(null, "Event " + (i + 1) + " has an invalid duration");
                }
            }

            events.add(new Event(title, date, allDay, allDay && timeTba, hour, minute, duration,
                    zone, clip(item.optString("location", "").trim(), MAX_TEXT_CHARS),
                    clip(item.optString("description", "").trim(), MAX_TEXT_CHARS),
                    httpsOnly(item.optString("sourceUrl", "").trim())));
        }
        return new Plan(events, "");
    }

    // ---- execution ------------------------------------------------------------------------------

    /** What actually happened, counted from the provider rather than from the plan. */
    public static final class Outcome {
        public final int added;
        public final int alreadyPresent;
        public final int failed;
        public final String calendarName;
        public final String lastError;

        Outcome(int added, int alreadyPresent, int failed, String calendarName, String lastError) {
            this.added = added;
            this.alreadyPresent = alreadyPresent;
            this.failed = failed;
            this.calendarName = calendarName == null ? "" : calendarName;
            this.lastError = lastError == null ? "" : lastError;
        }

        public int requested() { return added + alreadyPresent + failed; }
    }

    /**
     * Adds the requested events, then reports what the calendar actually contains.
     *
     * <p>Permission is re-checked here even though the surfaces resolve it before confirming.
     * This is the single place a write can happen, so it is the place the guarantee "a denial
     * produces zero writes" has to hold, whatever route the action arrived by.
     */
    public static DeviceActionExecutor.Result execute(Context c, JSONObject params) {
        if (c == null) return DeviceActionExecutor.Result.failed("Calendar is unavailable");
        if (!OrbitCalendarStore.hasAccess(c)) {
            CalendarDiagnostics.recordBlocked(c, "permission", plannedCount(params));
            return DeviceActionExecutor.Result.permission(
                    "Allow Calendar access so Orbit can add these events.");
        }

        Plan plan = parse(params);
        if (!plan.ok()) {
            CalendarDiagnostics.recordBlocked(c, "invalid plan", plannedCount(params));
            return DeviceActionExecutor.Result.failed(
                    plan.error.isEmpty() ? "Those calendar events are not valid" : plan.error);
        }

        List<OrbitCalendarStore.Target> writable = OrbitCalendarStore.writableCalendars(c);
        OrbitCalendarStore.Target target = OrbitCalendarStore.resolveTarget(
                writable, OrbitCalendarStore.storedTargetId(c));
        if (target == null && writable.size() == 1) target = writable.get(0);
        if (target == null) {
            String reason = writable.isEmpty()
                    ? "No writable calendar is set up on this phone, so Orbit has nowhere to add these events."
                    : "Choose which calendar Orbit should use before adding these events.";
            CalendarDiagnostics.recordBlocked(c,
                    writable.isEmpty() ? "no writable calendar" : "no target chosen", plan.size());
            return DeviceActionExecutor.Result.unavailable(reason);
        }

        Outcome outcome = write(c, plan, target);
        CalendarDiagnostics.recordWrite(c, writable.size(), outcome);
        return resultFor(outcome);
    }

    /** The insert-and-verify loop. Package-private so tests can drive it with a fake provider. */
    static Outcome write(Context c, Plan plan, OrbitCalendarStore.Target target) {
        ContentResolver resolver = c.getContentResolver();
        if (resolver == null) {
            return new Outcome(0, 0, plan.size(), target.label(), "Calendar provider unavailable");
        }
        int added = 0;
        int duplicates = 0;
        int failed = 0;
        String lastError = "";
        for (Event event : plan.events) {
            try {
                // An existing event is never touched, only recognised. Repeating the same import
                // therefore converges on one copy of each event rather than doubling the schedule.
                if (existingEventId(resolver, target.id, event) >= 0) {
                    duplicates++;
                    continue;
                }
                ContentValues values = new ContentValues();
                values.put(CalendarContract.Events.CALENDAR_ID, target.id);
                values.put(CalendarContract.Events.TITLE, event.title);
                values.put(CalendarContract.Events.DTSTART, event.startMillis());
                values.put(CalendarContract.Events.DTEND, event.endMillis());
                values.put(CalendarContract.Events.ALL_DAY, event.allDay ? 1 : 0);
                values.put(CalendarContract.Events.EVENT_TIMEZONE, event.timezoneId());
                values.put(CalendarContract.Events.HAS_ALARM, 0);
                if (!event.location.isEmpty()) {
                    values.put(CalendarContract.Events.EVENT_LOCATION, event.location);
                }
                String description = event.storedDescription();
                if (!description.isEmpty()) {
                    values.put(CalendarContract.Events.DESCRIPTION, description);
                }

                Uri inserted = resolver.insert(CalendarContract.Events.CONTENT_URI, values);
                // The insert returning a Uri is a claim, not proof. Only a successful read-back
                // lets this event be counted as added.
                if (inserted == null || !verify(resolver, inserted, event)) {
                    failed++;
                    if (lastError.isEmpty()) lastError = "the calendar did not keep the event";
                    continue;
                }
                added++;
            } catch (SecurityException denied) {
                failed++;
                lastError = "calendar permission was withdrawn";
            } catch (Exception failure) {
                failed++;
                if (lastError.isEmpty()) lastError = shortReason(failure);
            }
        }
        return new Outcome(added, duplicates, failed, target.label(), lastError);
    }

    /** Reads the inserted row back and confirms it is really there with the intended start. */
    private static boolean verify(ContentResolver resolver, Uri inserted, Event event) {
        long id = idOf(inserted);
        if (id < 0) return false;
        Cursor cursor = null;
        try {
            cursor = resolver.query(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
                    new String[]{CalendarContract.Events._ID, CalendarContract.Events.DTSTART,
                            CalendarContract.Events.DELETED},
                    null, null, null);
            if (cursor == null || !cursor.moveToFirst()) return false;
            int deletedIndex = cursor.getColumnIndex(CalendarContract.Events.DELETED);
            if (deletedIndex >= 0 && !cursor.isNull(deletedIndex)
                    && cursor.getInt(deletedIndex) == 1) {
                return false;
            }
            int startIndex = cursor.getColumnIndex(CalendarContract.Events.DTSTART);
            if (startIndex < 0 || cursor.isNull(startIndex)) return false;
            return cursor.getLong(startIndex) == event.startMillis();
        } catch (Exception ignored) {
            return false;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /**
     * The id of an equivalent event already on the target calendar, or -1.
     *
     * <p>Identity is the target calendar, the normalised title, the all-day state, and the local
     * day the event starts on. Matching on the day rather than the exact millisecond is what makes
     * a re-import converge even when a re-researched kickoff time has drifted by a few minutes.
     */
    private static long existingEventId(ContentResolver resolver, long calendarId, Event event) {
        long start = event.startMillis();
        Cursor cursor = null;
        try {
            cursor = resolver.query(CalendarContract.Events.CONTENT_URI,
                    new String[]{CalendarContract.Events._ID, CalendarContract.Events.TITLE,
                            CalendarContract.Events.DTSTART, CalendarContract.Events.ALL_DAY,
                            CalendarContract.Events.EVENT_TIMEZONE},
                    CalendarContract.Events.CALENDAR_ID + " = ? AND "
                            + CalendarContract.Events.DELETED + " != 1 AND "
                            + CalendarContract.Events.DTSTART + " >= ? AND "
                            + CalendarContract.Events.DTSTART + " <= ?",
                    new String[]{String.valueOf(calendarId),
                            String.valueOf(start - DUPLICATE_WINDOW_MS),
                            String.valueOf(start + DUPLICATE_WINDOW_MS)},
                    null);
            if (cursor == null) return -1L;
            String wantedTitle = OrbitCalendarStore.normalizeTitle(event.title);
            while (cursor.moveToNext()) {
                String title = string(cursor, CalendarContract.Events.TITLE);
                if (!OrbitCalendarStore.normalizeTitle(title).equals(wantedTitle)) continue;
                boolean existingAllDay = number(cursor, CalendarContract.Events.ALL_DAY, 0L) == 1L;
                if (existingAllDay != event.allDay) continue;
                long existingStart = number(cursor, CalendarContract.Events.DTSTART, Long.MIN_VALUE);
                if (existingStart == Long.MIN_VALUE) continue;
                ZoneId existingZone = event.allDay
                        ? ZoneOffset.UTC
                        : zoneOrDefault(string(cursor, CalendarContract.Events.EVENT_TIMEZONE),
                                event.zone);
                LocalDate existingDate = java.time.Instant.ofEpochMilli(existingStart)
                        .atZone(existingZone).toLocalDate();
                if (!existingDate.equals(event.date)) continue;
                long id = number(cursor, CalendarContract.Events._ID, -1L);
                if (id >= 0) return id;
            }
            return -1L;
        } catch (Exception ignored) {
            // Unable to check means Orbit cannot prove this is new. Reporting it as a failure
            // rather than inserting keeps a repeated import from quietly doubling a schedule.
            throw new IllegalStateException("could not check for an existing event");
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    // ---- result wording --------------------------------------------------------------------------

    /**
     * The only place Orbit is allowed to say a calendar write happened, and it says exactly what
     * the provider reported.
     */
    static DeviceActionExecutor.Result resultFor(Outcome outcome) {
        if (outcome == null) return DeviceActionExecutor.Result.failed("No events were added.");
        String calendar = outcome.calendarName.isEmpty() ? "your calendar" : outcome.calendarName;
        if (outcome.added > 0) {
            StringBuilder message = new StringBuilder("Added ")
                    .append(outcome.added).append(outcome.added == 1 ? " event to " : " events to ")
                    .append(calendar).append(".");
            if (outcome.alreadyPresent > 0) {
                message.append(" ").append(outcome.alreadyPresent)
                        .append(outcome.alreadyPresent == 1 ? " was" : " were")
                        .append(" already on your calendar.");
            }
            if (outcome.failed > 0) {
                message.append(" ").append(outcome.failed)
                        .append(outcome.failed == 1 ? " could" : " could")
                        .append(" not be added.");
            }
            return DeviceActionExecutor.Result.success(message.toString());
        }
        if (outcome.alreadyPresent > 0 && outcome.failed == 0) {
            return DeviceActionExecutor.Result.success("No events were added. All "
                    + outcome.alreadyPresent + (outcome.alreadyPresent == 1 ? " was" : " were")
                    + " already on " + calendar + ".");
        }
        String reason = outcome.lastError.isEmpty() ? "" : " (" + outcome.lastError + ")";
        return DeviceActionExecutor.Result.failed("No events were added." + reason);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static int plannedCount(JSONObject params) {
        if (params == null) return 0;
        JSONArray events = params.optJSONArray("events");
        return events == null ? 0 : events.length();
    }

    /**
     * Strict {@code yyyy-MM-dd}. February 30th is not a date, and must not become March 2nd:
     * {@link LocalDate#parse} rejects it rather than rolling it forward.
     */
    static LocalDate strictDate(String text) {
        if (text == null) return null;
        String value = text.trim();
        if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeException invalid) {
            return null;
        }
    }

    private static ZoneId zoneOrDefault(String id, ZoneId fallback) {
        if (id == null || id.trim().isEmpty()) return fallback;
        try {
            return ZoneId.of(id.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static long idOf(Uri uri) {
        try {
            return ContentUris.parseId(uri);
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private static String string(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) return "";
        String value = cursor.getString(index);
        return value == null ? "" : value;
    }

    private static long number(Cursor cursor, String column, long fallback) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) return fallback;
        try {
            return cursor.getLong(index);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String clip(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Only a real HTTPS link is kept; anything else is dropped rather than stored as text. */
    private static String httpsOnly(String url) {
        if (url == null) return "";
        String value = url.trim();
        return value.startsWith("https://") ? clip(value, 400) : "";
    }

    private static String shortReason(Exception failure) {
        String message = failure.getMessage();
        if (message == null || message.trim().isEmpty()) return failure.getClass().getSimpleName();
        return clip(message.trim(), 90);
    }
}
