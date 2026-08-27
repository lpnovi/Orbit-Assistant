package com.orbit.assistant;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.CalendarContract;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which calendar Orbit writes to, and whether it may write at all.
 *
 * <p>Discovery and target resolution live here rather than inside the executor because they are
 * the part a person can disagree with. Orbit never simply takes the first calendar Android hands
 * back: a phone commonly exposes holidays, birthdays, subscribed sports feeds, and shared work
 * calendars alongside the one the owner actually keeps their life in, and several of those are
 * writable. Choosing wrongly is not a small mistake, so when there is no clear answer this class
 * says so and lets the surfaces ask.
 *
 * <p>Only the chosen calendar's id is remembered. No calendar contents, event titles, account
 * addresses, or anything else read from the provider is ever written to preferences.
 */
public final class OrbitCalendarStore {
    private static final String FILE = "orbit_calendar";
    private static final String KEY_TARGET_ID = "target_calendar_id";
    /** Android's own threshold for "this account may add events to this calendar". */
    private static final int WRITABLE_ACCESS = CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR;

    private OrbitCalendarStore() {}

    /** One writable calendar, described only well enough to name and choose it. */
    public static final class Target {
        public final long id;
        public final String displayName;
        public final String accountName;
        public final boolean primary;

        public Target(long id, String displayName, String accountName, boolean primary) {
            this.id = id;
            this.displayName = displayName == null || displayName.trim().isEmpty()
                    ? "Calendar" : displayName.trim();
            this.accountName = accountName == null ? "" : accountName.trim();
            this.primary = primary;
        }

        /** The name shown in confirmations and results, e.g. {@code Personal}. */
        public String label() { return displayName; }

        /**
         * The name shown in a chooser, where two calendars can easily share a display name across
         * different accounts.
         */
        public String chooserLabel() {
            if (accountName.isEmpty() || accountName.equalsIgnoreCase(displayName)) {
                return displayName;
            }
            return displayName + " · " + accountName;
        }
    }

    // ---- access -----------------------------------------------------------------------------

    /** True only when Orbit holds both Calendar permissions. Neither one alone is enough. */
    public static boolean hasAccess(Context c) {
        return c != null
                && c.checkSelfPermission(Manifest.permission.READ_CALENDAR)
                        == PackageManager.PERMISSION_GRANTED
                && c.checkSelfPermission(Manifest.permission.WRITE_CALENDAR)
                        == PackageManager.PERMISSION_GRANTED;
    }

    // ---- discovery --------------------------------------------------------------------------

    /**
     * Every calendar on this device that Orbit could actually add an event to.
     *
     * <p>Empty when permission is missing, when the provider is unreachable, or when the device
     * genuinely has no writable calendar. All three are reported as "nothing to write to" rather
     * than being papered over, because writing to the wrong place would be worse than not writing.
     */
    public static List<Target> writableCalendars(Context c) {
        List<Target> found = new ArrayList<>();
        if (!hasAccess(c)) return found;
        ContentResolver resolver = c.getContentResolver();
        if (resolver == null) return found;
        Cursor cursor = null;
        try {
            cursor = resolver.query(CalendarContract.Calendars.CONTENT_URI,
                    new String[]{
                            CalendarContract.Calendars._ID,
                            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                            CalendarContract.Calendars.ACCOUNT_NAME,
                            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                            CalendarContract.Calendars.IS_PRIMARY,
                            CalendarContract.Calendars.OWNER_ACCOUNT
                    },
                    CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + " >= ?",
                    new String[]{String.valueOf(WRITABLE_ACCESS)},
                    CalendarContract.Calendars._ID + " ASC");
            if (cursor == null) return found;
            while (cursor.moveToNext()) {
                long id = readLong(cursor, CalendarContract.Calendars._ID, -1L);
                if (id < 0) continue;
                int access = (int) readLong(cursor,
                        CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, 0L);
                // Re-checked here as well: a provider that ignores the selection must not be
                // able to hand Orbit a read-only calendar to write into.
                if (access < WRITABLE_ACCESS) continue;
                String name = readString(cursor, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME);
                String account = readString(cursor, CalendarContract.Calendars.ACCOUNT_NAME);
                String owner = readString(cursor, CalendarContract.Calendars.OWNER_ACCOUNT);
                boolean primary = readLong(cursor, CalendarContract.Calendars.IS_PRIMARY, 0L) == 1L
                        || (!owner.isEmpty() && owner.equalsIgnoreCase(account));
                found.add(new Target(id, name, account, primary));
            }
        } catch (Exception ignored) {
            // A provider that refuses or throws means Orbit cannot see a safe destination.
            return new ArrayList<>();
        } finally {
            if (cursor != null) cursor.close();
        }
        return found;
    }

    // ---- target resolution ------------------------------------------------------------------

    /**
     * The calendar Orbit would write to right now, or null when the choice is genuinely the
     * user's to make.
     *
     * <p>Order: the calendar the user already chose, if it still exists and is still writable;
     * then the only writable calendar; then the single primary/default one. Anything past that is
     * ambiguous, and ambiguity is answered by a chooser, never by a guess.
     */
    public static Target resolveTarget(Context c) {
        return resolveTarget(writableCalendars(c), storedTargetId(c));
    }

    /** The resolution rule itself, separated from the provider so it can be reasoned about. */
    public static Target resolveTarget(List<Target> writable, long storedId) {
        if (writable == null || writable.isEmpty()) return null;
        if (storedId >= 0) {
            for (Target t : writable) if (t.id == storedId) return t;
        }
        if (writable.size() == 1) return writable.get(0);
        Target primary = null;
        for (Target t : writable) {
            if (!t.primary) continue;
            if (primary != null) return null;
            primary = t;
        }
        return primary;
    }

    /**
     * True when Orbit should ask which calendar to use before writing: more than one writable
     * calendar, and no clear default among them.
     */
    public static boolean needsChooser(Context c) {
        List<Target> writable = writableCalendars(c);
        return writable.size() > 1 && resolveTarget(writable, storedTargetId(c)) == null;
    }

    /** True when the user has a real choice to change, whatever the current target is. */
    public static boolean hasChoice(Context c) {
        return writableCalendars(c).size() > 1;
    }

    // ---- remembering the choice ---------------------------------------------------------------

    public static long storedTargetId(Context c) {
        if (c == null) return -1L;
        return prefs(c).getLong(KEY_TARGET_ID, -1L);
    }

    public static void rememberTarget(Context c, long id) {
        if (c == null || id < 0) return;
        prefs(c).edit().putLong(KEY_TARGET_ID, id).apply();
    }

    public static void forgetTarget(Context c) {
        if (c == null) return;
        prefs(c).edit().remove(KEY_TARGET_ID).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---- cursor helpers ------------------------------------------------------------------------

    private static String readString(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) return "";
        String value = cursor.getString(index);
        return value == null ? "" : value;
    }

    private static long readLong(Cursor cursor, String column, long fallback) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) return fallback;
        try {
            return cursor.getLong(index);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    /** Shared title normalisation, so duplicate detection and previews agree on identity. */
    static String normalizeTitle(String title) {
        if (title == null) return "";
        String lower = title.toLowerCase(Locale.US);
        StringBuilder out = new StringBuilder(lower.length());
        boolean pendingSpace = false;
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                if (pendingSpace && out.length() > 0) out.append(' ');
                pendingSpace = false;
                out.append(ch);
            } else {
                pendingSpace = true;
            }
        }
        return out.toString();
    }
}
