package com.orbit.assistant;

import android.app.NotificationManager;
import android.app.SearchManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.provider.Settings;

import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class DeviceActionExecutor {
    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_PERMISSION = "permission_required";
    public static final String STATUS_UNAVAILABLE = "unavailable";
    public static final String STATUS_CANCELLED = "cancelled";

    public static final class Result {
        public final String status;
        public final String message;
        public final boolean success;
        public final boolean shouldContinue;

        public Result(String status, String message, boolean success, boolean shouldContinue) {
            this.status = status == null ? STATUS_FAILED : status;
            this.message = message == null ? "" : message;
            this.success = success;
            this.shouldContinue = shouldContinue;
        }

        public static Result success(String message) {
            return new Result(STATUS_SUCCESS, message, true, true);
        }

        public static Result cancelled(String message) {
            return new Result(STATUS_CANCELLED, message, false, true);
        }

        public static Result permission(String message) {
            return new Result(STATUS_PERMISSION, message, false, false);
        }

        public static Result unavailable(String message) {
            return new Result(STATUS_UNAVAILABLE, message, false, false);
        }

        public static Result failed(String message) {
            return new Result(STATUS_FAILED, message, false, false);
        }

        public Result withContinuation(boolean shouldContinue) {
            return new Result(status, message, success, shouldContinue);
        }
    }

    private DeviceActionExecutor() {}

    /**
     * The only place in Orbit that asks Android to open a dialer.
     *
     * <p>Every dial - from the cloud provider, from a deterministic router, from a saved Routine,
     * from a widget, from a contact lookup - arrives here, which is why the emergency gate is here
     * and not in a screen or a provider. A protected number without a live grant produces no
     * Intent at all: not a delayed one, not a silent one, none. The grant is issued only by
     * {@link EmergencyDialGuard.Confirmation#confirm()}, which only a person tapping a
     * confirmation can reach, and it is spent as it is read so one confirmation opens one dialer.
     *
     * <p>The Intent stays {@link Intent#ACTION_DIAL}. Orbit populates Android's dialer and stops;
     * it has never used {@code ACTION_CALL} and must not start, because after the confirmation the
     * decision to actually place the call still belongs to the user and to their phone.
     */
    private static Result dial(Context c, String number) {
        String category = EmergencyDialGuard.categoryFor(number);
        if (!EmergencyDialGuard.CATEGORY_NONE.equals(category)
                && !EmergencyDialGuard.consumeGrant(number)) {
            DiagnosticStore.recordProtectedDial(c, category, "blocked");
            return Result.unavailable("Orbit needs you to confirm before it opens the dialer for "
                    + EmergencyDialGuard.normalize(number) + ".");
        }
        start(c, new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))));
        if (EmergencyDialGuard.CATEGORY_NONE.equals(category)) return Result.success("Dialer opened");
        DiagnosticStore.recordProtectedDial(c, category, "confirmed");
        // Past tense, said here and only here. This line is written after the Intent has gone to
        // Android, which is the first moment at which "opened" is a true thing to say about a
        // protected number - and it names the number, because the whole complaint about the old
        // wording was that Orbit described this act before it had performed it.
        return Result.success(
                ActionNarration.dialerOpenedText(EmergencyDialGuard.normalize(number)));
    }

    public static String execute(Context c, AssistantReply.Action action) {
        return executeDetailed(c, action).message;
    }

    public static Result executeDetailed(Context c, AssistantReply.Action action) {
        if (action == null) return Result.failed("No action to execute");
        try {
            JSONObject p = action.params == null ? new JSONObject() : action.params;
            Result result;
            switch (action.type.toUpperCase(Locale.US)) {
                case "OPEN_SETTINGS":
                    start(c, new Intent(Settings.ACTION_SETTINGS));
                    result = Result.success("Opened Settings");
                    break;
                case "OPEN_APP":
                    result = openApp(c, p.optString("app", p.optString("package", "")));
                    break;
                case "SET_TIMER": {
                    int seconds = Math.max(1, p.optInt("seconds", 60));
                    Intent i = new Intent(AlarmClock.ACTION_SET_TIMER)
                            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                            .putExtra(AlarmClock.EXTRA_MESSAGE, p.optString("label", "Orbit timer"))
                            .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
                    start(c, i);
                    result = Result.success("Timer started");
                    break;
                }
                case "SET_ALARM": {
                    int hour = p.optInt("hour", 8), minute = p.optInt("minute", 0);
                    Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
                            .putExtra(AlarmClock.EXTRA_HOUR, hour)
                            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                            .putExtra(AlarmClock.EXTRA_MESSAGE, p.optString("label", "Orbit alarm"))
                            .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
                    start(c, i);
                    result = Result.success("Alarm set");
                    break;
                }
                case "SET_REMINDER": {
                    result = setReminder(c, p);
                    break;
                }
                case "CREATE_EVENT": {
                    Intent i = new Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                            .putExtra(CalendarContract.Events.TITLE, p.optString("title", "Event"))
                            .putExtra(CalendarContract.Events.DESCRIPTION, p.optString("description", ""));
                    long begin = p.optLong("beginMillis", 0);
                    long end = p.optLong("endMillis", 0);
                    if (begin > 0) i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin);
                    if (end > 0) i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end);
                    start(c, i);
                    result = Result.success("Calendar event composer opened");
                    break;
                }
                case "ADD_CALENDAR_EVENTS": {
                    // Deliberately distinct from CREATE_EVENT above. That opens Android's event
                    // composer and the user presses Save; this is Orbit persisting the events
                    // itself and then reading them back before it is allowed to say so. The whole
                    // implementation lives in CalendarActionExecutor, so this stays a routing
                    // layer and a future local action model can reuse the same writer.
                    result = CalendarActionExecutor.execute(c, p);
                    break;
                }
                case "NAVIGATE": {
                    String query = p.optString("query", p.optString("destination", ""));
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(query)));
                    start(c, i);
                    result = Result.success("Navigation opened");
                    break;
                }
                case "DIAL":
                    result = dial(c, p.optString("number", ""));
                    break;
                case "DIAL_CONTACT": {
                    String number = findPhoneNumber(c, p.optString("name", ""));
                    if (number == null) {
                        result = c.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                                ? Result.unavailable("Could not find that contact")
                                : Result.permission("Grant Contacts permission or use a phone number");
                        break;
                    }
                    // A contact can resolve to a protected number, and the confirmation upstream
                    // could not have known that, because until this moment neither could Orbit.
                    // The same gate applies to the resolved number.
                    result = dial(c, number);
                    break;
                }
                case "SMS": {
                    Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(p.optString("number", ""))));
                    i.putExtra("sms_body", p.optString("body", ""));
                    start(c, i);
                    result = Result.success("Message composer opened");
                    break;
                }
                case "SMS_CONTACT": {
                    String number = findPhoneNumber(c, p.optString("name", ""));
                    if (number == null) {
                        result = c.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                                ? Result.unavailable("Could not find that contact")
                                : Result.permission("Grant Contacts permission or use a phone number");
                        break;
                    }
                    Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)));
                    i.putExtra("sms_body", p.optString("body", ""));
                    start(c, i);
                    result = Result.success("Message composer opened");
                    break;
                }
                case "SET_VOLUME": {
                    AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
                    if (am == null) {
                        result = Result.unavailable("Audio service unavailable");
                        break;
                    }
                    int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    int current = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                    int percent;
                    int level;
                    if (!p.has("percent") && p.has("delta")) {
                        // Relative request: move from wherever the stream actually is.
                        int delta = p.optInt("delta", 0);
                        int base = max <= 0 ? 0 : Math.round((current / (float) max) * 100f);
                        percent = clampPercent(base + delta);
                        level = Math.round(max * (percent / 100f));
                        // Android's media stream has few coarse steps, so a small percentage
                        // move can round back onto the current level and do nothing visible.
                        if (level == current && delta != 0) {
                            level = delta < 0 ? current - 1 : current + 1;
                        }
                        level = Math.max(0, Math.min(max, level));
                    } else {
                        percent = clampPercent(p.optInt("percent", 50));
                        level = Math.round(max * (percent / 100f));
                    }
                    int previousPercent = max <= 0 ? -1 : Math.round((current / (float) max) * 100f);
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, level, AudioManager.FLAG_SHOW_UI);
                    result = Result.success("Media volume set to " + percent + "%");
                    // The level before the change is a real reading, which is what makes
                    // "put it back" possible without inventing history.
                    RecentActionContext.recordLevel(RecentActionContext.Target.VOLUME, previousPercent);
                    break;
                }
                case "SET_BRIGHTNESS": {
                    int before = currentBrightnessPercent(c);
                    if (!p.has("percent") && p.has("delta")) {
                        result = setBrightness(c, clampPercent(before + p.optInt("delta", 0)));
                    } else {
                        result = setBrightness(c, clampPercent(p.optInt("percent", 50)));
                    }
                    if (result.success) {
                        RecentActionContext.recordLevel(RecentActionContext.Target.BRIGHTNESS, before);
                    }
                    break;
                }
                case "SET_DND":
                    result = setDoNotDisturb(c, p.optBoolean("enabled", true));
                    break;
                case "MEDIA_CONTROL": {
                    // The whole implementation lives in MediaControl, so this stays a routing layer
                    // and every caller - cloud tool request, deterministic phrase, local action
                    // model, routine step - reaches the same one.
                    MediaControl.Command command = MediaControl.parse(p.optString("command", ""));
                    if (command == null) {
                        result = Result.failed("That is not a media command Orbit has");
                        break;
                    }
                    result = MediaControl.execute(c, command);
                    break;
                }
                case "SET_RINGER_MODE":
                    result = setRingerMode(c, p.optString("mode", ""));
                    break;
                case "OPEN_INTERNET_PANEL":
                    start(c, new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY));
                    result = Result.success("Internet controls opened");
                    break;
                case "OPEN_BLUETOOTH_SETTINGS":
                    start(c, new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                    result = Result.success("Bluetooth settings opened");
                    break;
                case "WEB_SEARCH": {
                    Intent i = new Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, p.optString("query", ""));
                    start(c, i);
                    result = Result.success("Search opened");
                    break;
                }
                case "OPEN_URL":
                    start(c, new Intent(Intent.ACTION_VIEW, Uri.parse(p.optString("url", "https://www.google.com"))));
                    result = Result.success("Link opened");
                    break;
                case "SHARE": {
                    Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, p.optString("text", ""));
                    Intent chooser = Intent.createChooser(i, "Share with");
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    c.startActivity(chooser);
                    result = Result.success("Share sheet opened");
                    break;
                }
                case "COPY": {
                    ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Orbit", p.optString("text", "")));
                    result = Result.success("Copied to clipboard");
                    break;
                }
                case "FLASHLIGHT": {
                    boolean on = p.optBoolean("on", true);
                    result = torch(c, on);
                    // Remembered only when the change actually happened, so a follow-up can
                    // never act on a device state Orbit did not reach.
                    if (result.success) RecentActionContext.recordFlashlight(on);
                    break;
                }
                default:
                    result = Result.unavailable("Unsupported action: " + action.type);
                    break;
            }
            boolean continueOnFailure = p.optBoolean("continueOnFailure", false);
            if (!result.success && !result.shouldContinue && continueOnFailure) {
                result = result.withContinuation(true);
            }
            return result;
        } catch (SecurityException e) {
            return Result.permission("Orbit does not currently have permission to complete that phone action.");
        } catch (Exception e) {
            String message = e.getMessage();
            if (message == null || message.trim().isEmpty()) message = e.getClass().getSimpleName();
            if (message.length() > 140) message = message.substring(0, 137) + "…";
            return Result.failed("Action failed: " + message);
        }
    }

    private static Result setReminder(Context c, JSONObject p) {
        if (!ReminderNotifier.notificationsAllowed(c)) {
            return Result.permission("Allow Orbit notifications so the reminder can alert you.");
        }
        String message = p.optString("message", p.optString("label", "Reminder")).trim();
        if (message.isEmpty()) message = "Reminder";

        long triggerAt = p.optLong("triggerAtMillis", 0L);
        if (triggerAt <= 0L) {
            int year = p.optInt("year", 0);
            int month = p.optInt("month", 0);
            int day = p.optInt("day", 0);
            int hour = p.optInt("hour", -1);
            int minute = p.optInt("minute", -1);
            if (year <= 0 || month < 1 || month > 12 || day < 1 || day > 31 ||
                    hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                return Result.failed("Reminder date or time is incomplete");
            }
            Calendar cal = Calendar.getInstance();
            cal.setLenient(false);
            cal.clear();
            cal.set(Calendar.YEAR, year);
            cal.set(Calendar.MONTH, month - 1);
            cal.set(Calendar.DAY_OF_MONTH, day);
            cal.set(Calendar.HOUR_OF_DAY, hour);
            cal.set(Calendar.MINUTE, minute);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            try { triggerAt = cal.getTimeInMillis(); }
            catch (Exception e) { return Result.failed("That reminder date is not valid"); }
        }
        if (triggerAt <= System.currentTimeMillis() + 500L) {
            return Result.failed("Reminder time must be in the future");
        }

        ReminderStore.Item item = ReminderStore.create(message, triggerAt);
        ReminderScheduler.ScheduleResult scheduled = ReminderScheduler.schedule(c, item);
        if (!scheduled.scheduled) return Result.failed(scheduled.message);
        try { p.put("reminderId", item.id).put("triggerAtMillis", triggerAt).put("message", message); }
        catch (Exception ignored) {}

        String when = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(new Date(triggerAt));
        String suffix = scheduled.exact ? "" : " (approximate timing)";
        return Result.success("Reminder set for " + when + suffix);
    }

    /**
     * The SMS/RCS "use this reply" path, and <em>only</em> the SMS/RCS one.
     *
     * <p>Android does not expose a generic API that lets a third-party assistant inject text into
     * an arbitrary app. It does define a standard SMS composer intent with a body extra, so on a
     * Messages screen Orbit can resolve the visible contact and open that composer prefilled. Every
     * step of that — reading a name off the screen, resolving it to a phone number, opening
     * {@code smsto:} — is correct there and wrong anywhere else.
     *
     * <p>The name says so since v0.7.8.0 Beta 2. It was called {@code openReplyComposer}, which read
     * like a universal helper, and the overlay called it for every reply draft regardless of app —
     * so an email reply drafted in Gmail opened an SMS to the sender. {@link ReplySurface} now
     * decides the medium before anything reaches here, and this method is reachable only for a
     * surface that is genuinely SMS/RCS.
     *
     * <p>Returns {@code OPENED:…} when the composer was actually opened. Anything else is a reason,
     * and the caller falls back to the clipboard.
     */
    public static String openSmsReplyComposer(Context c, String screenText, String body) {
        if (body == null || body.trim().isEmpty()) return "Nothing to insert";
        if (c.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "Copied instead · grant Contacts permission for Use in chat";
        }

        String contactName = findVisibleContactName(c, screenText);
        if (contactName == null) {
            return "Copied instead · I couldn't identify the current SMS/RCS recipient";
        }
        String number = findPhoneNumber(c, contactName);
        if (number == null) {
            return "Copied instead · I couldn't resolve " + contactName + " in Contacts";
        }

        try {
            Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)));
            i.putExtra("sms_body", body.trim());
            start(c, i);
            return "OPENED:Reply ready for " + contactName;
        } catch (Exception e) {
            return "Copied instead · your current messaging app did not accept a prefilled reply";
        }
    }

    /**
     * The current screen brightness as a percentage, so a relative request moves from what the
     * user is actually looking at. Falls back to a mid level when the value cannot be read.
     */
    private static int currentBrightnessPercent(Context c) {
        return currentBrightnessPercent(c, 50);
    }

    /**
     * The same reading, with the caller's own answer for "Android would not say".
     *
     * <p>{@link DeviceStatusReader} passes -1 because reporting a brightness has to fail honestly
     * when the value is unreadable, while a relative command passes a mid level because it needs
     * somewhere to move from. One interpretation of the raw value, two callers, so what Orbit says
     * the brightness is and what Orbit sets it to can never disagree.
     */
    static int currentBrightnessPercent(Context c, int fallback) {
        try {
            int value = Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1);
            if (value < 0) return fallback;
            return Math.max(0, Math.min(100, Math.round((value / 255f) * 100f)));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static Result setBrightness(Context c, int percent) {
        if (!Settings.System.canWrite(c)) {
            return Result.permission("Allow Modify system settings to let Orbit change brightness");
        }
        try {
            Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            int value = Math.max(1, Math.min(255, Math.round((percent / 100f) * 255f)));
            Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, value);
            return Result.success("Brightness set to " + percent + "%");
        } catch (Exception e) {
            return Result.failed("Could not change brightness");
        }
    }

    /**
     * Normal, vibrate, or silent, confirmed by reading the mode back.
     *
     * <p>Android's own policy decides whether this is allowed at all. Vibrate and silent both go
     * through the Do Not Disturb policy on a modern device, so a phone that has not granted Orbit
     * that access refuses the change — and Orbit reports that as the permission it is, with the
     * mode left exactly as it was. Nothing here works around the platform's decision.
     *
     * <p>The success line is the mode Android reported afterwards, not the mode Orbit asked for, so
     * a silently ignored request cannot be announced as a change.
     */
    private static Result setRingerMode(Context c, String requested) {
        String wanted = requested == null ? "" : requested.trim().toLowerCase(Locale.US);
        int mode;
        switch (wanted) {
            case "normal": case "ring": case "ringer": case "sound": case "loud":
                mode = AudioManager.RINGER_MODE_NORMAL; break;
            case "vibrate": case "vibration":
                mode = AudioManager.RINGER_MODE_VIBRATE; break;
            case "silent": case "mute": case "muted": case "off":
                mode = AudioManager.RINGER_MODE_SILENT; break;
            default:
                return Result.failed("That is not a ringer mode Orbit has");
        }

        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return Result.unavailable("Audio service unavailable");

        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        boolean policyGranted = nm != null && nm.isNotificationPolicyAccessGranted();
        if (!policyGranted && mode != AudioManager.RINGER_MODE_NORMAL) {
            // Android refuses a quiet mode without this access, and refuses it by throwing. Asking
            // first means the user is told what to grant instead of shown a failure.
            return Result.permission(
                    "Grant Do Not Disturb access to let Orbit silence or vibrate the ringer");
        }
        try {
            am.setRingerMode(mode);
        } catch (SecurityException e) {
            return Result.permission(
                    "Grant Do Not Disturb access to let Orbit change the ringer mode");
        } catch (Exception e) {
            return Result.failed("Could not change the ringer mode");
        }

        String actual = DeviceStatusReader.ringerModeName(c);
        if (actual.isEmpty()) return Result.failed("Orbit could not confirm the ringer mode");
        if (!actual.equalsIgnoreCase(modeName(mode))) {
            // The call went through and the phone is somewhere else. Say where it actually is.
            return Result.failed("Android kept the ringer on " + actual);
        }
        return Result.success("Ringer set to " + actual);
    }

    private static String modeName(int mode) {
        switch (mode) {
            case AudioManager.RINGER_MODE_NORMAL: return "Normal";
            case AudioManager.RINGER_MODE_VIBRATE: return "Vibrate";
            default: return "Silent";
        }
    }

    private static Result setDoNotDisturb(Context c, boolean enabled) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return Result.unavailable("Notification service unavailable");
        if (!nm.isNotificationPolicyAccessGranted()) {
            return Result.permission("Grant Do Not Disturb access to let Orbit change DND");
        }
        try {
            nm.setInterruptionFilter(enabled
                    ? NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    : NotificationManager.INTERRUPTION_FILTER_ALL);
            return Result.success(enabled ? "Do Not Disturb enabled" : "Do Not Disturb disabled");
        } catch (Exception e) {
            return Result.failed("Could not change Do Not Disturb");
        }
    }

    private static String findVisibleContactName(Context c, String screenText) {
        if (screenText == null || screenText.trim().isEmpty()) return null;
        String[] lines = screenText.split("\n");
        int checked = 0;
        for (String raw : lines) {
            if (checked++ > 35) break;
            if (raw == null) continue;
            String line = raw.replaceAll("[\\[\\]{}]", "").replaceAll("\\s+", " ").trim();
            if (!looksLikePersonName(line)) continue;
            String exact = exactContactDisplayName(c, line);
            if (exact != null) return exact;
        }
        return null;
    }

    private static boolean looksLikePersonName(String line) {
        if (line == null || line.length() < 2 || line.length() > 60) return false;
        if (line.matches(".*\\d.*")) return false;
        String lower = line.toLowerCase(Locale.US);
        String[] reject = {
                "message", "messages", "reply", "typing", "search", "settings", "back",
                "send", "camera", "gallery", "emoji", "today", "yesterday", "online",
                "screen", "conversation", "rcs", "sms", "orbit", "call", "video"
        };
        for (String word : reject) if (lower.equals(word) || lower.startsWith(word + " ")) return false;
        int words = line.split(" ").length;
        return words >= 1 && words <= 5;
    }

    private static String exactContactDisplayName(Context c, String wanted) {
        Cursor cursor = null;
        try {
            cursor = c.getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME},
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " = ? COLLATE NOCASE",
                    new String[]{wanted},
                    ContactsContract.CommonDataKinds.Phone.IS_PRIMARY + " DESC");
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                if (idx >= 0) return cursor.getString(idx);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    private static void start(Context c, Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (intent.resolveActivity(c.getPackageManager()) == null) throw new IllegalStateException("No compatible app installed");
        c.startActivity(intent);
    }

    private static Result openApp(Context c, String wanted) {
        if (wanted == null || wanted.trim().isEmpty()) return Result.failed("No app specified");
        PackageManager pm = c.getPackageManager();
        String w = wanted.trim().toLowerCase(Locale.US);
        Intent direct = pm.getLaunchIntentForPackage(wanted.trim());
        if (direct != null) {
            start(c, direct);
            return Result.success("Opened app");
        }
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(launcher, 0);
        ResolveInfo best = null;
        for (ResolveInfo info : infos) {
            String label = info.loadLabel(pm).toString().toLowerCase(Locale.US);
            if (label.equals(w)) { best = info; break; }
            if (best == null && (label.contains(w) || w.contains(label))) best = info;
        }
        if (best == null) return Result.unavailable("Could not find " + wanted);
        Intent i = pm.getLaunchIntentForPackage(best.activityInfo.packageName);
        if (i == null) return Result.unavailable("Could not launch " + wanted);
        start(c, i);
        return Result.success("Opened " + best.loadLabel(pm));
    }

    private static String findPhoneNumber(Context c, String name) {
        if (name == null || name.trim().isEmpty()) return null;
        if (c.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null;
        Cursor cursor = null;
        try {
            cursor = c.getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME},
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                    new String[]{"%" + name.trim() + "%"},
                    ContactsContract.CommonDataKinds.Phone.IS_PRIMARY + " DESC");
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                if (idx >= 0) return cursor.getString(idx);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    private static Result torch(Context c, boolean on) throws Exception {
        CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) return Result.unavailable("Camera service unavailable");
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics cc = cm.getCameraCharacteristics(id);
            Boolean flash = cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
            if (Boolean.TRUE.equals(flash) && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                cm.setTorchMode(id, on);
                return Result.success(on ? "Flashlight on" : "Flashlight off");
            }
        }
        return Result.unavailable("No flashlight found");
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
