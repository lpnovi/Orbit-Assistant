package com.orbit.assistant;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Versioned, local-only Orbit backup import/export. Credentials are never included. */
public final class OrbitBackupManager {
    private static final String FORMAT = "orbit-assistant-backup";
    private static final int SCHEMA_VERSION = 1;
    private static final long MAX_BACKUP_BYTES = 100L * 1024L * 1024L;
    private static final long MAX_ATTACHMENT_BYTES = 10L * 1024L * 1024L;
    private static final long MAX_TOTAL_ATTACHMENT_BYTES = 75L * 1024L * 1024L;

    private OrbitBackupManager() {}

    public static final class PreparedRestore {
        private final JSONObject data;
        private final String sourceVersion;
        private final long createdAt;

        private PreparedRestore(JSONObject data, String sourceVersion, long createdAt) {
            this.data = data;
            this.sourceVersion = sourceVersion;
            this.createdAt = createdAt;
        }

        public String confirmationMessage() {
            return "This valid Orbit " + sourceVersion + " backup contains " +
                    data.optJSONArray("conversations").length() + " chats, " +
                    data.optJSONArray("memories").length() + " memories, " +
                    data.optJSONArray("routines").length() + " routines and " +
                    optionalArray(data, "customCommands").length() + " Custom Commands, and " +
                    data.optJSONArray("reminders").length() + " reminders, plus " +
                    optionalArray(data, "extensions").length() + " extensions.\n\n" +
                    "Restoring will replace Orbit's backed-up local data on this device. " +
                    "Account credentials, Android permissions and default-assistant status are not included.";
        }

        public long createdAt() { return createdAt; }
    }

    public static String defaultFileName() {
        return "Orbit-Backup-" + LocalDate.now() + ".orbitbackup";
    }

    public static void exportTo(Context context, Uri uri) throws Exception {
        JSONObject data = currentSnapshot(context);
        addPortableAttachments(context, data);
        validateData(data);

        long now = System.currentTimeMillis();
        JSONObject root = new JSONObject()
                .put("format", FORMAT)
                .put("schemaVersion", SCHEMA_VERSION)
                .put("orbitVersionName", BuildConfig.VERSION_NAME)
                .put("orbitVersionCode", BuildConfig.VERSION_CODE)
                .put("createdAtEpochMillis", now)
                .put("createdAtUtc", Instant.ofEpochMilli(now).toString())
                .put("data", data);
        byte[] bytes = root.toString(2).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BACKUP_BYTES) throw new IllegalStateException("Backup is too large to export safely.");
        try (OutputStream out = context.getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IllegalStateException("Android could not open the selected file.");
            out.write(bytes);
            out.flush();
        }
    }

    public static PreparedRestore prepareRestore(Context context, Uri uri) throws Exception {
        byte[] bytes;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalArgumentException("Android could not open the selected file.");
            bytes = readLimited(in, MAX_BACKUP_BYTES);
        }
        JSONObject root;
        try {
            root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalArgumentException("The selected file is not valid JSON.");
        }
        if (!FORMAT.equals(root.optString("format", "")))
            throw new IllegalArgumentException("The selected file is not an Orbit backup.");
        if (root.optInt("schemaVersion", -1) != SCHEMA_VERSION)
            throw new IllegalArgumentException("This Orbit backup version is not supported.");
        String version = root.optString("orbitVersionName", "").trim();
        int versionCode = root.optInt("orbitVersionCode", -1);
        long created = root.optLong("createdAtEpochMillis", 0L);
        String createdUtc = root.optString("createdAtUtc", "").trim();
        JSONObject data = root.optJSONObject("data");
        if (version.isEmpty() || versionCode <= 0 || created <= 0L || createdUtc.isEmpty() || data == null)
            throw new IllegalArgumentException("The Orbit backup metadata is incomplete.");
        try { Instant.parse(createdUtc); }
        catch (Exception e) { throw new IllegalArgumentException("The Orbit backup creation time is invalid."); }
        validateData(data);
        return new PreparedRestore(new JSONObject(data.toString()), version, created);
    }

    public static void restore(Context context, PreparedRestore prepared) throws Exception {
        if (context == null || prepared == null) throw new IllegalArgumentException("No backup was selected.");
        // Revalidate the immutable prepared copy immediately before making any changes.
        validateData(prepared.data);
        JSONObject before = currentSnapshot(context);
        List<ReminderStore.Item> oldReminders = new ArrayList<>(ReminderStore.list(context));
        List<RoutineTriggerStore.Trigger> oldTriggers = new ArrayList<>(RoutineTriggerStore.list(context));
        List<File> createdFiles = new ArrayList<>();
        JSONObject restored = new JSONObject(prepared.data.toString());
        try {
            materializeAttachments(context, restored, createdFiles);
        } catch (Exception e) {
            for (File file : createdFiles) if (file != null) file.delete();
            throw e;
        }

        for (ReminderStore.Item reminder : oldReminders) ReminderScheduler.cancelScheduled(context, reminder.id);
        for (RoutineTriggerStore.Trigger trigger : oldTriggers) RoutineTriggerScheduler.cancel(context, trigger.id);

        boolean committed = applySnapshot(context, restored);
        if (!committed) {
            applySnapshot(context, before);
            for (File file : createdFiles) if (file != null) file.delete();
            RoutineTriggerScheduler.rescheduleAll(context);
            ReminderScheduler.rescheduleAll(context);
            throw new IllegalStateException("Orbit could not replace all local data; the previous data was restored.");
        }
        RoutineTriggerScheduler.rescheduleAll(context);
        ReminderScheduler.rescheduleAll(context);
    }

    private static JSONObject currentSnapshot(Context c) throws Exception {
        return new JSONObject()
                .put("preferences", Prefs.backupSnapshot(c))
                .put("conversations", parseArray("conversation history", ConversationStore.backupJson(c)))
                .put("actionResults", ActionResultStore.backupSnapshot(c))
                .put("memories", parseArray("Orbit Memory", MemoryStore.backupJson(c)))
                .put("routines", parseArray("Routines", RoutineStore.backupJson(c)))
                .put("customCommands", parseArray("Custom Commands", CustomCommandStore.backupJson(c)))
                .put("routineTriggers", parseArray("Routine triggers", RoutineTriggerStore.backupJson(c)))
                .put("reminders", parseArray("reminders", ReminderStore.backupJson(c)))
                .put("savedPlaces", parseArray("saved places", SavedPlaceStore.backupJson(c)))
                .put("appProfiles", parseArray("app profiles", AppProfileStore.backupJson(c)))
                .put("notificationConfiguration", NotificationStore.backupConfiguration(c))
                .put("extensions", OrbitExtensionStore.backupJson(c))
                .put("attachments", new JSONArray());
    }

    private static JSONArray parseArray(String name, String raw) throws Exception {
        try { return new JSONArray(raw == null ? "[]" : raw); }
        catch (Exception e) { throw new IllegalStateException("Orbit's " + name + " store is damaged."); }
    }

    private static boolean applySnapshot(Context c, JSONObject data) {
        try {
            boolean ok = Prefs.restoreBackupSnapshot(c, data.getJSONObject("preferences"));
            ok &= ConversationStore.restoreBackupJson(c, data.getJSONArray("conversations").toString());
            ok &= ActionResultStore.restoreBackupSnapshot(c, data.getJSONObject("actionResults"));
            ok &= MemoryStore.restoreBackupJson(c, data.getJSONArray("memories").toString());
            ok &= RoutineStore.restoreBackupJson(c, data.getJSONArray("routines").toString());
            ok &= CustomCommandStore.restoreBackupJson(c, optionalArray(data, "customCommands").toString());
            ok &= RoutineTriggerStore.restoreBackupJson(c, data.getJSONArray("routineTriggers").toString());
            ok &= ReminderStore.restoreBackupJson(c, data.getJSONArray("reminders").toString());
            ok &= SavedPlaceStore.restoreBackupJson(c, data.getJSONArray("savedPlaces").toString());
            ok &= AppProfileStore.restoreBackupJson(c, data.getJSONArray("appProfiles").toString());
            ok &= NotificationStore.restoreBackupConfiguration(c, data.getJSONObject("notificationConfiguration"));
            if (data.has("extensions"))
                ok &= OrbitExtensionStore.restoreBackupJson(c, data.getJSONArray("extensions"));
            return ok;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void validateData(JSONObject data) throws Exception {
        if (data == null || !Prefs.validBackupSnapshot(data.optJSONObject("preferences"))) invalid("settings");
        JSONArray conversations = requiredArray(data, "conversations");
        JSONObject actionResults = data.optJSONObject("actionResults");
        JSONArray memories = requiredArray(data, "memories");
        JSONArray routines = requiredArray(data, "routines");
        JSONArray customCommands = optionalArray(data, "customCommands");
        JSONArray triggers = requiredArray(data, "routineTriggers");
        JSONArray reminders = requiredArray(data, "reminders");
        JSONArray places = requiredArray(data, "savedPlaces");
        JSONArray profiles = requiredArray(data, "appProfiles");
        JSONObject notifications = data.optJSONObject("notificationConfiguration");
        JSONArray extensions = optionalArray(data, "extensions");
        JSONArray attachments = requiredArray(data, "attachments");
        if (actionResults == null || notifications == null) invalid("stored data");

        Set<String> conversationIds = validateConversations(conversations);
        validateActionResults(actionResults, conversationIds);
        validateMemories(memories);
        Set<String> routineIds = validateRoutines(routines);
        validateCustomCommands(customCommands);
        validateTriggers(triggers, routineIds);
        validateReminders(reminders);
        validatePlaces(places);
        validateProfiles(profiles);
        validateNotifications(notifications);
        if (!OrbitExtensionStore.isValidBackup(extensions)) invalid("extensions");
        validateAttachments(conversations, attachments);
    }

    private static Set<String> validateConversations(JSONArray a) throws Exception {
        if (a.length() > 100) invalid("conversation history");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || !addUnique(ids, o.optString("id", "")) ||
                    o.optString("title", "").trim().isEmpty()) invalid("conversation history");
            String mode = o.optString("intelligenceMode", "");
            if (!mode.isEmpty() && !mode.equals(Prefs.normalizeMode(mode))) invalid("conversation history");
            JSONArray messages = o.optJSONArray("messages");
            if (messages == null || messages.length() > 40) invalid("conversation history");
            for (int j = 0; j < messages.length(); j++) {
                JSONObject m = messages.optJSONObject(j);
                if (m == null) invalid("conversation history");
                String role = m.optString("role", "");
                String content = m.optString("content", "");
                if (!("user".equals(role) || "assistant".equals(role)) || content.isEmpty() ||
                        content.length() > 12000 || m.optString("attachmentText", "").length() > 105000)
                    invalid("conversation history");
                // Local paths are never accepted from an imported file.
                if (!m.optString("attachmentPath", "").isEmpty()) invalid("conversation attachments");
            }
        }
        return ids;
    }

    private static void validateActionResults(JSONObject results, Set<String> conversations) throws Exception {
        Iterator<String> keys = results.keys();
        int count = 0;
        while (keys.hasNext()) {
            String conversationId = keys.next();
            if (++count > 100 || !conversations.contains(conversationId)) invalid("action results");
            JSONArray entries = results.optJSONArray(conversationId);
            if (entries == null || entries.length() > 80) invalid("action results");
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < entries.length(); i++) {
                JSONObject e = entries.optJSONObject(i);
                JSONObject action = e == null ? null : e.optJSONObject("action");
                if (e == null || action == null || !addUnique(ids, e.optString("id", "")) ||
                        action.optString("type", "").trim().isEmpty() || action.optJSONObject("params") == null ||
                        e.optInt("assistantIndex", -1) < 0 || e.optInt("totalSteps", 0) < 1)
                    invalid("action results");
            }
        }
    }

    private static void validateMemories(JSONArray a) throws Exception {
        if (a.length() > 150) invalid("Orbit Memory");
        Set<String> ids = new HashSet<>();
        Set<String> categories = set("Preference", "Person", "Place", "Device", "Other");
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || !addUnique(ids, o.optString("id", "")) ||
                    o.optString("text", "").trim().isEmpty() || !categories.contains(o.optString("category", "")))
                invalid("Orbit Memory");
        }
    }

    private static Set<String> validateRoutines(JSONArray a) throws Exception {
        if (a.length() > RoutineStore.MAX_ROUTINES) invalid("Routines");
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            String name = o == null ? "" : o.optString("name", "").trim();
            JSONArray actions = o == null ? null : o.optJSONArray("actions");
            if (o == null || !addUnique(ids, o.optString("id", "")) || name.isEmpty() ||
                    !names.add(name.toLowerCase(Locale.US)) || actions == null || actions.length() == 0 ||
                    actions.length() > RoutineActionCatalog.MAX_STEPS) invalid("Routines");
            for (int j = 0; j < actions.length(); j++) {
                JSONObject action = actions.optJSONObject(j);
                if (action == null || action.optJSONObject("params") == null ||
                        !RoutineActionCatalog.isValid(new AssistantReply.Action(action.optString("type", ""),
                                action.optJSONObject("params"), action.optBoolean("requiresConfirmation", false))))
                    invalid("Routine action chains");
            }
        }
        return ids;
    }

    private static void validateCustomCommands(JSONArray a) throws Exception {
        if (a.length() > CustomCommandStore.MAX_COMMANDS) invalid("Custom Commands");
        Set<String> ids = new HashSet<>();
        Set<String> enabledPhrases = new HashSet<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            JSONArray aliases = o == null ? null : o.optJSONArray("aliases");
            String primary = o == null ? "" : o.optString("primaryPhrase", "").trim();
            String routineId = o == null ? "" : o.optString("routineId", "").trim();
            if (o == null || !addUnique(ids, o.optString("id", "")) || routineId.isEmpty() ||
                    primary.isEmpty() || primary.length() > CustomCommandStore.MAX_PHRASE_LENGTH ||
                    aliases == null || aliases.length() > CustomCommandStore.MAX_ALIASES)
                invalid("Custom Commands");

            Set<String> own = new HashSet<>();
            String normalized = CustomCommandStore.normalizeForMatch(primary);
            if (normalized.length() < 2 || !own.add(normalized)) invalid("Custom Commands");
            for (int j = 0; j < aliases.length(); j++) {
                String alias = aliases.optString(j, "").trim();
                normalized = CustomCommandStore.normalizeForMatch(alias);
                if (alias.isEmpty() || alias.length() > CustomCommandStore.MAX_PHRASE_LENGTH ||
                        normalized.length() < 2 || !own.add(normalized)) invalid("Custom Commands");
            }
            if (o.optBoolean("enabled", true)) {
                for (String phrase : own) if (!enabledPhrases.add(phrase)) invalid("Custom Commands");
            }
        }
    }

    private static void validateTriggers(JSONArray a, Set<String> routineIds) throws Exception {
        if (a.length() > RoutineTriggerStore.MAX_TRIGGERS_TOTAL) invalid("Routine triggers");
        Set<String> ids = new HashSet<>();
        Map<String, Integer> perRoutine = new HashMap<>();
        Set<String> modes = set("once", "daily", "weekdays", "weekends", "weekly", "custom");
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            String routineId = o == null ? "" : o.optString("routineId", "").trim();
            String type = o == null ? "" : o.optString("type", "");
            if (o == null || !addUnique(ids, o.optString("id", "")) || !routineIds.contains(routineId))
                invalid("Routine triggers");
            int nextCount = perRoutine.getOrDefault(routineId, 0) + 1;
            perRoutine.put(routineId, nextCount);
            if (nextCount > RoutineTriggerStore.MAX_TRIGGERS_PER_ROUTINE) invalid("Routine triggers");
            if (RoutineTriggerStore.TYPE_LOCATION.equals(type)) {
                double lat = o.optDouble("latitude", Double.NaN);
                double lon = o.optDouble("longitude", Double.NaN);
                double radius = o.optDouble("radiusMeters", -1d);
                String transition = o.optString("locationTransition", "");
                if (o.optString("locationName", "").trim().isEmpty() || !SavedPlaceStore.validCoordinates(lat, lon) ||
                        radius < RoutineTriggerStore.MIN_LOCATION_RADIUS_METERS ||
                        radius > RoutineTriggerStore.MAX_LOCATION_RADIUS_METERS ||
                        !(RoutineTriggerStore.LOCATION_ENTER.equals(transition) || RoutineTriggerStore.LOCATION_EXIT.equals(transition)))
                    invalid("location triggers");
            } else if (RoutineTriggerStore.TYPE_TIME.equals(type)) {
                if (!modes.contains(o.optString("mode", "")) || o.optInt("hour", -1) < 0 ||
                        o.optInt("hour", -1) > 23 || o.optInt("minute", -1) < 0 || o.optInt("minute", -1) > 59 ||
                        o.optInt("intervalCount", 0) < 1) invalid("time triggers");
            } else invalid("Routine triggers");
        }
    }

    private static void validateReminders(JSONArray a) throws Exception {
        if (a.length() > 100) invalid("reminders");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || !addUnique(ids, o.optString("id", "")) ||
                    o.optString("message", "").trim().isEmpty() || o.optLong("triggerAt", 0L) <= 0L)
                invalid("reminders");
        }
    }

    private static void validatePlaces(JSONArray a) throws Exception {
        if (a.length() > 50) invalid("saved places");
        Set<String> ids = new HashSet<>(), names = new HashSet<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            String name = o == null ? "" : o.optString("name", "").trim();
            if (o == null || !addUnique(ids, o.optString("id", "")) || name.isEmpty() ||
                    !names.add(name.toLowerCase(Locale.US)) || !SavedPlaceStore.validCoordinates(
                    o.optDouble("latitude", Double.NaN), o.optDouble("longitude", Double.NaN)))
                invalid("saved places");
        }
    }

    private static void validateProfiles(JSONArray a) throws Exception {
        if (a.length() > 500) invalid("app profiles");
        Set<String> packages = new HashSet<>();
        Set<String> categories = set("auto", "conversation", "product", "article", "settings",
                "media", "map", "document", "email", "generic");
        Set<String> privacy = set("auto", "normal", "sensitive", "never");
        Set<String> screen = set("global", "attach", "never");
        Set<String> screenshots = set("global", "allow", "block");
        Set<String> modes = set("global", "auto", "fast", "balanced", "deep", "custom");
        Set<String> actions = set("auto", "draft_reply", "summarize", "explain", "explain_tone",
                "needs_action", "worth_it", "compare", "key_specs", "key_points", "recommend",
                "what_matters", "which_option", "route_summary", "what_next");
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || !addUnique(packages, o.optString("packageName", "")) ||
                    !categories.contains(o.optString("category", "")) ||
                    !privacy.contains(o.optString("privacyPolicy", "")) ||
                    !screen.contains(o.optString("screenPolicy", "")) ||
                    !screenshots.contains(o.optString("screenshotPolicy", "")) ||
                    !modes.contains(o.optString("intelligenceMode", "")) ||
                    !actions.contains(o.optString("action1", "")) ||
                    !actions.contains(o.optString("action2", "")) ||
                    !actions.contains(o.optString("action3", ""))) invalid("app profiles");
        }
    }

    private static void validateNotifications(JSONObject o) throws Exception {
        JSONArray blocked = o.optJSONArray("blockedPackages");
        JSONObject known = o.optJSONObject("knownApps");
        if (blocked == null || known == null || blocked.length() > 1000 || known.length() > 1000)
            invalid("notification configuration");
        for (int i = 0; i < blocked.length(); i++) {
            Object value = blocked.opt(i);
            if (!(value instanceof String) || ((String) value).trim().isEmpty())
                invalid("notification configuration");
        }
        Iterator<String> keys = known.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.trim().isEmpty() || !(known.opt(key) instanceof String)) invalid("notification configuration");
        }
    }

    private static void validateAttachments(JSONArray conversations, JSONArray attachments) throws Exception {
        Map<String, byte[]> decoded = new HashMap<>();
        long total = 0L;
        for (int i = 0; i < attachments.length(); i++) {
            JSONObject item = attachments.optJSONObject(i);
            String id = item == null ? "" : item.optString("id", "").trim();
            if (item == null || id.isEmpty() || decoded.containsKey(id) ||
                    !"image/jpeg".equals(item.optString("mimeType", ""))) invalid("conversation attachments");
            byte[] bytes;
            try { bytes = Base64.decode(item.optString("data", ""), Base64.DEFAULT); }
            catch (Exception e) { throw new IllegalArgumentException("The backup contains a corrupt attachment."); }
            if (bytes.length == 0 || bytes.length > MAX_ATTACHMENT_BYTES || !isJpeg(bytes) ||
                    item.optLong("size", -1L) != bytes.length) invalid("conversation attachments");
            total += bytes.length;
            if (total > MAX_TOTAL_ATTACHMENT_BYTES) throw new IllegalArgumentException("The backup contains too many attachments.");
            decoded.put(id, bytes);
        }
        Set<String> referenced = new HashSet<>();
        for (int i = 0; i < conversations.length(); i++) {
            JSONArray messages = conversations.getJSONObject(i).getJSONArray("messages");
            for (int j = 0; j < messages.length(); j++) {
                String ref = messages.getJSONObject(j).optString("attachmentRef", "").trim();
                if (!ref.isEmpty()) {
                    if (!decoded.containsKey(ref)) invalid("conversation attachments");
                    referenced.add(ref);
                }
            }
        }
        if (referenced.size() != decoded.size()) invalid("conversation attachments");
    }

    private static void addPortableAttachments(Context c, JSONObject data) throws Exception {
        JSONArray conversations = data.getJSONArray("conversations");
        JSONArray attachments = new JSONArray();
        Map<String, String> refs = new HashMap<>();
        File historyRoot = new File(c.getFilesDir(), "orbit_attachments/history").getCanonicalFile();
        String rootPrefix = historyRoot.getPath() + File.separator;
        long total = 0L;
        for (int i = 0; i < conversations.length(); i++) {
            JSONArray messages = conversations.getJSONObject(i).getJSONArray("messages");
            for (int j = 0; j < messages.length(); j++) {
                JSONObject message = messages.getJSONObject(j);
                String path = message.optString("attachmentPath", "").trim();
                message.put("attachmentPath", "");
                if (path.isEmpty()) continue;
                File file;
                try { file = new File(path).getCanonicalFile(); }
                catch (Exception ignored) { continue; }
                if (!file.getPath().startsWith(rootPrefix) || !file.isFile()) continue;
                String ref = refs.get(file.getPath());
                if (ref == null) {
                    byte[] bytes;
                    try (InputStream in = new FileInputStream(file)) { bytes = readLimited(in, MAX_ATTACHMENT_BYTES); }
                    if (!isJpeg(bytes)) continue;
                    total += bytes.length;
                    if (total > MAX_TOTAL_ATTACHMENT_BYTES) throw new IllegalStateException("Conversation attachments are too large to back up safely.");
                    ref = UUID.randomUUID().toString();
                    refs.put(file.getPath(), ref);
                    attachments.put(new JSONObject().put("id", ref).put("mimeType", "image/jpeg")
                            .put("size", bytes.length).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)));
                }
                message.put("attachmentRef", ref);
            }
        }
        data.put("attachments", attachments);
    }

    private static void materializeAttachments(Context c, JSONObject data, List<File> created) throws Exception {
        JSONArray records = data.getJSONArray("attachments");
        Map<String, byte[]> bytesById = new HashMap<>();
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.getJSONObject(i);
            bytesById.put(record.getString("id"), Base64.decode(record.getString("data"), Base64.DEFAULT));
        }
        File directory = new File(c.getFilesDir(), "orbit_attachments/history");
        if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("Orbit could not prepare attachment storage.");
        Map<String, String> paths = new HashMap<>();
        for (Map.Entry<String, byte[]> entry : bytesById.entrySet()) {
            File file = new File(directory, "restored-" + UUID.randomUUID() + ".jpg");
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(entry.getValue());
                out.getFD().sync();
            }
            created.add(file);
            paths.put(entry.getKey(), file.getAbsolutePath());
        }
        JSONArray conversations = data.getJSONArray("conversations");
        for (int i = 0; i < conversations.length(); i++) {
            JSONArray messages = conversations.getJSONObject(i).getJSONArray("messages");
            for (int j = 0; j < messages.length(); j++) {
                JSONObject message = messages.getJSONObject(j);
                String ref = message.optString("attachmentRef", "");
                message.put("attachmentPath", ref.isEmpty() ? "" : paths.get(ref));
                message.remove("attachmentRef");
            }
        }
        data.put("attachments", new JSONArray());
    }

    private static JSONArray requiredArray(JSONObject o, String key) throws Exception {
        JSONArray value = o.optJSONArray(key);
        if (value == null) invalid(key);
        return value;
    }

    /** Schema-v1 backups created before Custom Commands remain valid and restore an empty command set. */
    private static JSONArray optionalArray(JSONObject o, String key) {
        JSONArray value = o == null ? null : o.optJSONArray(key);
        return value == null ? new JSONArray() : value;
    }

    private static boolean addUnique(Set<String> values, String raw) {
        String value = raw == null ? "" : raw.trim();
        return !value.isEmpty() && values.add(value);
    }

    private static Set<String> set(String... values) {
        Set<String> out = new HashSet<>();
        java.util.Collections.addAll(out, values);
        return out;
    }

    private static void invalid(String section) throws Exception {
        throw new IllegalArgumentException("The backup contains invalid " + section + ".");
    }

    private static byte[] readLimited(InputStream in, long limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0L;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw new IllegalArgumentException("The selected file is too large.");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static boolean isJpeg(byte[] bytes) {
        return bytes != null && bytes.length >= 3 && (bytes[0] & 0xff) == 0xff &&
                (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
    }
}
