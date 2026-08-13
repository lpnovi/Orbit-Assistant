package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Local persistence and conservative phrase validation for user-defined Routine shortcuts. */
public final class CustomCommandStore {
    private static final String FILE = "orbit_custom_commands";
    private static final String KEY = "custom_commands_v1";

    public static final int MAX_COMMANDS = 50;
    public static final int MAX_ALIASES = 5;
    public static final int MAX_PHRASE_LENGTH = 80;

    public static final class Command {
        public final String id;
        public final boolean enabled;
        public final String routineId;
        public final String primaryPhrase;
        public final List<String> aliases;
        public final long createdAt;
        public final long updatedAt;

        public Command(String id, boolean enabled, String routineId, String primaryPhrase,
                       List<String> aliases, long createdAt, long updatedAt) {
            this.id = clean(id);
            this.enabled = enabled;
            this.routineId = clean(routineId);
            this.primaryPhrase = sanitizePhrase(primaryPhrase);
            this.aliases = sanitizeAliases(aliases);
            this.createdAt = createdAt <= 0L ? System.currentTimeMillis() : createdAt;
            this.updatedAt = updatedAt <= 0L ? this.createdAt : updatedAt;
        }

        public Command withEnabled(boolean value) {
            return new Command(id, value, routineId, primaryPhrase, aliases, createdAt,
                    System.currentTimeMillis());
        }
    }

    public static final class Validation {
        public final boolean valid;
        public final String message;

        private Validation(boolean valid, String message) {
            this.valid = valid;
            this.message = message == null ? "" : message;
        }

        static Validation ok() { return new Validation(true, ""); }
        static Validation error(String message) { return new Validation(false, message); }
    }

    private CustomCommandStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static synchronized List<Command> list(Context c) {
        if (c == null) return Collections.emptyList();
        List<Command> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs(c).getString(KEY, "[]"));
            for (int i = 0; i < array.length() && out.size() < MAX_COMMANDS; i++) {
                Command command = fromJson(array.optJSONObject(i));
                if (command != null) out.add(command);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static synchronized Command findById(Context c, String id) {
        String wanted = clean(id);
        if (wanted.isEmpty()) return null;
        for (Command command : list(c)) if (command.id.equals(wanted)) return command;
        return null;
    }

    public static Command create(boolean enabled, String routineId, String primaryPhrase,
                                 List<String> aliases) {
        long now = System.currentTimeMillis();
        return new Command(UUID.randomUUID().toString(), enabled, routineId, primaryPhrase,
                aliases, now, now);
    }

    public static synchronized Validation validate(Context c, Command command, String exceptId) {
        if (command == null || command.id.isEmpty()) return Validation.error("This command is incomplete.");
        if (command.routineId.isEmpty()) return Validation.error("Choose a Routine to run.");
        if (command.primaryPhrase.isEmpty()) return Validation.error("Enter a command phrase.");
        if (command.primaryPhrase.length() > MAX_PHRASE_LENGTH)
            return Validation.error("Command phrases can be up to " + MAX_PHRASE_LENGTH + " characters.");
        if (command.aliases.size() > MAX_ALIASES)
            return Validation.error("Add no more than " + MAX_ALIASES + " additional phrases.");

        List<String> phrases = phrases(command);
        Set<String> own = new LinkedHashSet<>();
        for (String phrase : phrases) {
            String normalized = normalizeForMatch(phrase);
            if (normalized.length() < 2 || !normalized.matches(".*[a-z0-9].*"))
                return Validation.error("Each phrase needs at least two meaningful characters.");
            if (phrase.length() > MAX_PHRASE_LENGTH)
                return Validation.error("Command phrases can be up to " + MAX_PHRASE_LENGTH + " characters.");
            if (!own.add(normalized))
                return Validation.error("The primary phrase and additional phrases must be unique.");
            if (isReservedPhrase(phrase))
                return Validation.error("\"" + phrase + "\" conflicts with an existing Orbit command. Choose a more specific phrase.");
        }

        if (command.enabled && c != null) {
            for (Command existing : list(c)) {
                if (existing.id.equals(exceptId) || existing.id.equals(command.id) || !existing.enabled) continue;
                for (String existingPhrase : phrases(existing)) {
                    if (own.contains(normalizeForMatch(existingPhrase)))
                        return Validation.error("That phrase is already used by another enabled Custom Command.");
                }
            }
        }
        return Validation.ok();
    }

    public static synchronized boolean upsert(Context c, Command command) {
        if (c == null || command == null || !validate(c, command, command.id).valid) return false;
        List<Command> commands = new ArrayList<>(list(c));
        int replace = -1;
        for (int i = 0; i < commands.size(); i++) {
            if (commands.get(i).id.equals(command.id)) { replace = i; break; }
        }
        if (replace < 0 && commands.size() >= MAX_COMMANDS) return false;
        if (replace >= 0) commands.set(replace, command);
        else commands.add(command);
        return write(c, commands);
    }

    public static synchronized boolean delete(Context c, String id) {
        if (c == null) return false;
        List<Command> commands = new ArrayList<>(list(c));
        boolean removed = commands.removeIf(command -> command.id.equals(clean(id)));
        return removed && write(c, commands);
    }

    static List<String> phrases(Command command) {
        List<String> out = new ArrayList<>();
        if (command == null) return out;
        if (!command.primaryPhrase.isEmpty()) out.add(command.primaryPhrase);
        out.addAll(command.aliases);
        return out;
    }

    /** Exact normalized match only. No spelling, substring, token, or fuzzy matching. */
    public static String normalizeForMatch(String raw) {
        String value = clean(raw).replaceAll("[.!?,;:…]+$", "").trim().replaceAll("\\s+", " ");
        boolean stripped;
        do {
            stripped = false;
            String lower = value.toLowerCase(Locale.US);
            String[] wrappers = {"hey orbit,", "hey orbit ", "orbit,", "orbit ", "please,", "please "};
            for (String wrapper : wrappers) {
                if (lower.startsWith(wrapper)) {
                    value = value.substring(wrapper.length()).trim();
                    stripped = true;
                    break;
                }
            }
        } while (stripped && !value.isEmpty());
        return value.toLowerCase(Locale.US).replaceAll("\\s+", " ").trim();
    }

    private static boolean isReservedPhrase(String phrase) {
        String normalized = normalizeForMatch(phrase);
        if (normalized.isEmpty() || normalized.equals("orbit") || normalized.equals("please") ||
                normalized.equals("hey orbit")) return true;
        if (MemoryCommandRouter.canHandle(normalized) || LocalCommandRouter.canHandle(normalized) ||
                RoutineCommandRouter.isReservedCommandPhrase(normalized)) return true;
        return normalized.equals("weather") || normalized.equals("forecast") ||
                normalized.startsWith("what is the weather") || normalized.startsWith("what's the weather") ||
                normalized.startsWith("show my notifications") || normalized.startsWith("what notifications") ||
                normalized.equals("check for updates") || normalized.equals("new chat");
    }

    static synchronized String backupJson(Context c) {
        return prefs(c).getString(KEY, "[]");
    }

    static synchronized boolean restoreBackupJson(Context c, String raw) {
        return prefs(c).edit().putString(KEY, raw == null ? "[]" : raw).commit();
    }

    private static boolean write(Context c, List<Command> commands) {
        JSONArray array = new JSONArray();
        for (Command command : commands) array.put(toJson(command));
        return prefs(c).edit().putString(KEY, array.toString()).commit();
    }

    private static JSONObject toJson(Command command) {
        JSONObject object = new JSONObject();
        try {
            object.put("id", command.id);
            object.put("enabled", command.enabled);
            object.put("routineId", command.routineId);
            object.put("primaryPhrase", command.primaryPhrase);
            object.put("aliases", new JSONArray(command.aliases));
            object.put("createdAt", command.createdAt);
            object.put("updatedAt", command.updatedAt);
        } catch (Exception ignored) {}
        return object;
    }

    private static Command fromJson(JSONObject object) {
        if (object == null) return null;
        String id = clean(object.optString("id", ""));
        String routineId = clean(object.optString("routineId", ""));
        String primary = sanitizePhrase(object.optString("primaryPhrase", ""));
        if (id.isEmpty() || routineId.isEmpty() || primary.isEmpty()) return null;
        List<String> aliases = new ArrayList<>();
        JSONArray array = object.optJSONArray("aliases");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                String alias = sanitizePhrase(array.optString(i, ""));
                if (!alias.isEmpty()) aliases.add(alias);
            }
        }
        return new Command(id, object.optBoolean("enabled", true), routineId, primary, aliases,
                object.optLong("createdAt", 0L), object.optLong("updatedAt", 0L));
    }

    private static List<String> sanitizeAliases(List<String> aliases) {
        List<String> out = new ArrayList<>();
        if (aliases == null) return out;
        for (String raw : aliases) {
            String value = sanitizePhrase(raw);
            if (!value.isEmpty()) out.add(value);
        }
        return Collections.unmodifiableList(out);
    }

    private static String sanitizePhrase(String phrase) {
        return clean(phrase).replaceAll("\\s+", " ");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
