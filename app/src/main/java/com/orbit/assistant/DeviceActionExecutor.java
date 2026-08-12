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
                case "NAVIGATE": {
                    String query = p.optString("query", p.optString("destination", ""));
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(query)));
                    start(c, i);
                    result = Result.success("Navigation opened");
                    break;
                }
                case "DIAL":
                    start(c, new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(p.optString("number", "")))));
                    result = Result.success("Dialer opened");
                    break;
                case "DIAL_CONTACT": {
                    String number = findPhoneNumber(c, p.optString("name", ""));
                    if (number == null) {
                        result = c.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                                ? Result.unavailable("Could not find that contact")
                                : Result.permission("Grant Contacts permission or use a phone number");
                        break;
                    }
                    start(c, new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))));
                    result = Result.success("Dialer opened");
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
                    int percent = clampPercent(p.optInt("percent", 50));
                    int level = Math.round(max * (percent / 100f));
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, level, AudioManager.FLAG_SHOW_UI);
                    result = Result.success("Media volume set to " + percent + "%");
                    break;
                }
                case "SET_BRIGHTNESS":
                    result = setBrightness(c, clampPercent(p.optInt("percent", 50)));
                    break;
                case "SET_DND":
                    result = setDoNotDisturb(c, p.optBoolean("enabled", true));
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
                case "FLASHLIGHT":
                    result = torch(c, p.optBoolean("on", true));
                    break;
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
     * Best-effort safe "use this reply" path for SMS/RCS. Android does not expose
     * a generic API that lets a third-party assistant inject text into arbitrary
     * apps such as Discord or WhatsApp. For a Messages screen, however, we can
     * resolve the visible contact and open the system SMS/RCS composer with the
     * draft prefilled. The caller can fall back to clipboard when this returns a
     * non-OPENED result.
     */
    public static String openReplyComposer(Context c, String screenText, String body) {
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
