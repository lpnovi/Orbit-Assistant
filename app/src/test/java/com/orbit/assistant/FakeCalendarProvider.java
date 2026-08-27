package com.orbit.assistant;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.CalendarContract;

import java.util.ArrayList;
import java.util.List;

/**
 * A stand-in for Android's Calendar Provider.
 *
 * <p>Orbit's Calendar work cannot be proven against a real provider here: there is no emulator in
 * this environment, and every OEM ships a different calendar implementation anyway. What these
 * tests can prove is that Orbit's own half of the contract is correct — that it discovers writable
 * calendars, resolves a target, converts dates the way {@link CalendarContract} documents, checks
 * for duplicates before inserting, and only counts an event as added once it has read the row back.
 *
 * <p>So this double is deliberately literal about the parts that matter. It honours the access-level
 * filter, stores what was inserted verbatim, answers the read-back query from what it stored, and
 * can be told to fail an insert or to lose the row afterwards, which is the case that decides
 * whether Orbit can be tricked into claiming a success it did not have.
 */
public final class FakeCalendarProvider extends ContentProvider {

    /** One row in the fake Calendars table. */
    public static final class Calendar {
        final long id;
        final String displayName;
        final String accountName;
        final String ownerAccount;
        final int accessLevel;
        final int isPrimary;

        public Calendar(long id, String displayName, String accountName, int accessLevel,
                        boolean primary) {
            this(id, displayName, accountName, accountName, accessLevel, primary);
        }

        public Calendar(long id, String displayName, String accountName, String ownerAccount,
                        int accessLevel, boolean primary) {
            this.id = id;
            this.displayName = displayName;
            this.accountName = accountName;
            this.ownerAccount = ownerAccount;
            this.accessLevel = accessLevel;
            this.isPrimary = primary ? 1 : 0;
        }
    }

    /** One row in the fake Events table. */
    public static final class Event {
        public final long id;
        public final long calendarId;
        public final String title;
        public final long dtStart;
        public final long dtEnd;
        public final int allDay;
        public final String timezone;
        public final String description;
        public final String location;
        int deleted;

        Event(long id, long calendarId, String title, long dtStart, long dtEnd, int allDay,
              String timezone, String description, String location) {
            this.id = id;
            this.calendarId = calendarId;
            this.title = title;
            this.dtStart = dtStart;
            this.dtEnd = dtEnd;
            this.allDay = allDay;
            this.timezone = timezone == null ? "" : timezone;
            this.description = description == null ? "" : description;
            this.location = location == null ? "" : location;
        }
    }

    public final List<Calendar> calendars = new ArrayList<>();
    public final List<Event> events = new ArrayList<>();

    /** Insert returns null, as a provider that refuses the write does. */
    public boolean refuseInserts;
    /** Insert returns a Uri but nothing is stored, so the read-back must fail. */
    public boolean loseInsertedRows;
    /** Every query throws, as a revoked-permission or broken provider does. */
    public boolean failQueries;
    public int insertAttempts;

    private long nextEventId = 1000L;

    @Override public boolean onCreate() { return true; }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        if (failQueries) throw new IllegalStateException("provider unavailable");
        if (isCalendars(uri)) return calendarCursor(projection, selectionArgs);
        Long single = eventIdIn(uri);
        if (single != null) return eventCursor(projection, singleton(single));
        if (isEvents(uri)) return eventCursor(projection, matchingEvents(selectionArgs));
        return null;
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        if (failQueries) throw new IllegalStateException("provider unavailable");
        if (!isEvents(uri) || values == null) return null;
        insertAttempts++;
        if (refuseInserts) return null;
        long id = nextEventId++;
        if (!loseInsertedRows) {
            events.add(new Event(id,
                    asLong(values.getAsLong(CalendarContract.Events.CALENDAR_ID), -1L),
                    values.getAsString(CalendarContract.Events.TITLE),
                    asLong(values.getAsLong(CalendarContract.Events.DTSTART), 0L),
                    asLong(values.getAsLong(CalendarContract.Events.DTEND), 0L),
                    asInt(values.getAsInteger(CalendarContract.Events.ALL_DAY), 0),
                    values.getAsString(CalendarContract.Events.EVENT_TIMEZONE),
                    values.getAsString(CalendarContract.Events.DESCRIPTION),
                    values.getAsString(CalendarContract.Events.EVENT_LOCATION)));
        }
        return ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id);
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) {
        return 0;
    }

    @Override public String getType(Uri uri) { return null; }

    // ---- helpers used by tests ------------------------------------------------------------------

    /** Seeds an event as though the user already had it, for duplicate cases. */
    public Event seedEvent(long calendarId, String title, long dtStart, long dtEnd, boolean allDay,
                           String timezone) {
        Event event = new Event(nextEventId++, calendarId, title, dtStart, dtEnd, allDay ? 1 : 0,
                timezone, "", "");
        events.add(event);
        return event;
    }

    public List<Event> eventsIn(long calendarId) {
        List<Event> found = new ArrayList<>();
        for (Event event : events) if (event.calendarId == calendarId) found.add(event);
        return found;
    }

    public int countTitled(long calendarId, String title) {
        int count = 0;
        for (Event event : eventsIn(calendarId)) {
            if (OrbitCalendarStore.normalizeTitle(event.title)
                    .equals(OrbitCalendarStore.normalizeTitle(title))) count++;
        }
        return count;
    }

    // ---- internals ------------------------------------------------------------------------------

    private static boolean isCalendars(Uri uri) {
        return uri != null && uri.toString().startsWith(
                CalendarContract.Calendars.CONTENT_URI.toString());
    }

    private static boolean isEvents(Uri uri) {
        return uri != null && uri.toString().equals(
                CalendarContract.Events.CONTENT_URI.toString());
    }

    private static Long eventIdIn(Uri uri) {
        if (uri == null) return null;
        String base = CalendarContract.Events.CONTENT_URI.toString();
        String value = uri.toString();
        if (!value.startsWith(base + "/")) return null;
        try {
            return Long.parseLong(value.substring(base.length() + 1));
        } catch (NumberFormatException notAnId) {
            return null;
        }
    }

    private List<Event> singleton(long id) {
        List<Event> found = new ArrayList<>();
        for (Event event : events) if (event.id == id) found.add(event);
        return found;
    }

    /**
     * The duplicate scan's selection, applied positionally: calendar id, then the start-time
     * window. Deleted rows are excluded, as the real selection asks.
     */
    private List<Event> matchingEvents(String[] args) {
        List<Event> found = new ArrayList<>();
        if (args == null || args.length < 3) return new ArrayList<>(events);
        long calendarId = Long.parseLong(args[0]);
        long from = Long.parseLong(args[1]);
        long to = Long.parseLong(args[2]);
        for (Event event : events) {
            if (event.calendarId != calendarId) continue;
            if (event.deleted == 1) continue;
            if (event.dtStart < from || event.dtStart > to) continue;
            found.add(event);
        }
        return found;
    }

    private Cursor calendarCursor(String[] projection, String[] args) {
        String[] columns = projection == null ? new String[]{
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.IS_PRIMARY,
                CalendarContract.Calendars.OWNER_ACCOUNT} : projection;
        int minimumAccess = args == null || args.length == 0 ? 0 : Integer.parseInt(args[0]);
        MatrixCursor cursor = new MatrixCursor(columns);
        for (Calendar calendar : calendars) {
            if (calendar.accessLevel < minimumAccess) continue;
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) row[i] = calendarValue(calendar, columns[i]);
            cursor.addRow(row);
        }
        return cursor;
    }

    private Cursor eventCursor(String[] projection, List<Event> rows) {
        String[] columns = projection == null ? new String[]{
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.ALL_DAY,
                CalendarContract.Events.EVENT_TIMEZONE,
                CalendarContract.Events.DELETED} : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        for (Event event : rows) {
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) row[i] = eventValue(event, columns[i]);
            cursor.addRow(row);
        }
        return cursor;
    }

    private static Object calendarValue(Calendar calendar, String column) {
        if (CalendarContract.Calendars._ID.equals(column)) return calendar.id;
        if (CalendarContract.Calendars.CALENDAR_DISPLAY_NAME.equals(column)) return calendar.displayName;
        if (CalendarContract.Calendars.ACCOUNT_NAME.equals(column)) return calendar.accountName;
        if (CalendarContract.Calendars.OWNER_ACCOUNT.equals(column)) return calendar.ownerAccount;
        if (CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL.equals(column)) return calendar.accessLevel;
        if (CalendarContract.Calendars.IS_PRIMARY.equals(column)) return calendar.isPrimary;
        return null;
    }

    private static Object eventValue(Event event, String column) {
        if (CalendarContract.Events._ID.equals(column)) return event.id;
        if (CalendarContract.Events.CALENDAR_ID.equals(column)) return event.calendarId;
        if (CalendarContract.Events.TITLE.equals(column)) return event.title;
        if (CalendarContract.Events.DTSTART.equals(column)) return event.dtStart;
        if (CalendarContract.Events.DTEND.equals(column)) return event.dtEnd;
        if (CalendarContract.Events.ALL_DAY.equals(column)) return event.allDay;
        if (CalendarContract.Events.EVENT_TIMEZONE.equals(column)) return event.timezone;
        if (CalendarContract.Events.DESCRIPTION.equals(column)) return event.description;
        if (CalendarContract.Events.EVENT_LOCATION.equals(column)) return event.location;
        if (CalendarContract.Events.DELETED.equals(column)) return event.deleted;
        return null;
    }

    private static long asLong(Long value, long fallback) {
        return value == null ? fallback : value;
    }

    private static int asInt(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
