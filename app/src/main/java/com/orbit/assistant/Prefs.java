package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class Prefs {
    private static final String FILE = "orbit_prefs";
    public static final String MODEL = "model";
    public static final String REASONING = "reasoning";
    public static final String INTELLIGENCE_MODE = "intelligence_mode";
    public static final String PROVIDER = "provider";
    public static final String BACKEND_URL = "backend_url";
    public static final String BACKEND_TOKEN = "backend_token";
    public static final String SCREEN_CONTEXT = "screen_context";
    public static final String SCREENSHOT = "screenshot";
    public static final String CONTEXT_CHIPS = "context_chips";
    public static final String ATTACH_SCREEN_BY_DEFAULT = "attach_screen_by_default";
    public static final String SPEAK = "speak";
    public static final String HAPTICS = "haptics";
    public static final String AUTO_LISTEN = "auto_listen";
    /**
     * Starts the first voice turn automatically when the assistant overlay opens. Deliberately
     * separate from {@link #AUTO_LISTEN}, which continues to own hands-free follow-up turns
     * after Orbit finishes speaking. The two stages are controlled independently.
     */
    public static final String AUTO_LISTEN_ON_OPEN = "auto_listen_on_open";
    /**
     * Subordinate to {@link #AUTO_LISTEN}: when the master is on, reopen the microphone only for a
     * reply that is actually waiting for an answer. Turning this off restores the older behaviour
     * where every eligible spoken reply opened the microphone. It has no effect while
     * {@link #AUTO_LISTEN} is off, and its stored value is kept either way.
     */
    public static final String SMART_FOLLOW_UPS = "smart_follow_ups";
    public static final String VOICE_PAUSE_FRIENDLY = "voice_pause_friendly";
    public static final String ACCENT = "accent";
    public static final String NEW_CHAT_ON_OPEN = "new_chat_on_open";
    public static final String HISTORY_ENABLED = "history_enabled";
    public static final String SAVE_SCREEN_THUMBNAILS = "save_screen_thumbnails";
    public static final String KEYBOARD_AWARE_ASSISTANT = "keyboard_aware_assistant";
    /**
     * Turns the composer's Send control into Stop while Orbit is generating a reply, on both
     * surfaces. Off leaves the composer exactly as it was before this option existed.
     */
    public static final String SHOW_STOP_BUTTON = "show_stop_button";
    public static final String USER_BUBBLE_COLOR = "user_bubble_color";
    public static final String ASSISTANT_BUBBLE_COLOR = "assistant_bubble_color";
    public static final String CHAT_TEXT_SIZE = "chat_text_size";
    public static final String GALLERY_APP_PACKAGE = "gallery_app_package";
    public static final String LELO_MODE = "lelo_mode";
    public static final String BACKGROUND_NOTIFICATIONS = "background_notifications";
    public static final String WEATHER_LOCATION = "weather_location";
    public static final String WEATHER_USE_DEVICE_LOCATION = "weather_use_device_location";
    public static final String WEATHER_UNITS = "weather_units";
    public static final String MEMORY_ENABLED = "memory_enabled";
    public static final String MEMORY_USAGE_INDICATOR = "memory_usage_indicator";
    public static final String MEMORY_SUGGESTIONS = "memory_suggestions";
    public static final String NOTIFICATION_AI_ENABLED = "notification_ai_enabled";
    public static final String NOTIFICATION_RETENTION_DAYS = "notification_retention_days";
    public static final String AMOLED_MODE = "amoled_mode";
    public static final String APP_FONT = "app_font";
    public static final String QUICK_SETTINGS_ROUTINE_ID = "quick_settings_routine_id";
    public static final String UPDATE_NOTIFICATIONS = "update_notifications";
    public static final String PAGE_TRANSITION = "page_transition";
    // Onboarding keys intentionally remain outside Backup & Restore. A backup cannot
    // restore account credentials, Android permissions, or default-assistant state.

    public static final String PROVIDER_CHATGPT = "chatgpt";
    public static final String PROVIDER_RELAY = "relay";
    public static final String PROVIDER_LOCAL = "local";
    public static final String PROVIDER_OPENROUTER = "openrouter";

    public static final String MODE_AUTO = "auto";
    public static final String MODE_FAST = "fast";
    public static final String MODE_BALANCED = "balanced";
    public static final String MODE_DEEP = "deep";
    public static final String MODE_CUSTOM = "custom";

    public static final String CHAT_TEXT_SMALL = "small";
    public static final String CHAT_TEXT_DEFAULT = "default";
    public static final String CHAT_TEXT_LARGE = "large";
    public static final String CHAT_TEXT_EXTRA_LARGE = "extra_large";

    public static final String WEATHER_UNITS_SYSTEM = "system";
    public static final String WEATHER_UNITS_FAHRENHEIT = "fahrenheit";
    public static final String WEATHER_UNITS_CELSIUS = "celsius";

    /** Page-transition styles. Slide is Orbit's default for fresh installs and existing users. */
    public static final String PAGE_TRANSITION_SLIDE = "slide";
    public static final String PAGE_TRANSITION_FADE = "fade";
    public static final String PAGE_TRANSITION_NONE = "none";

    private Prefs() {}

    private static final Set<String> BACKUP_STRING_KEYS = new HashSet<>(Arrays.asList(
            MODEL, REASONING, INTELLIGENCE_MODE, ACCENT, USER_BUBBLE_COLOR,
            ASSISTANT_BUBBLE_COLOR, CHAT_TEXT_SIZE, WEATHER_LOCATION, WEATHER_UNITS, APP_FONT,
            QUICK_SETTINGS_ROUTINE_ID, PAGE_TRANSITION));
    private static final Set<String> BACKUP_BOOLEAN_KEYS = new HashSet<>(Arrays.asList(
            SCREEN_CONTEXT, SCREENSHOT, CONTEXT_CHIPS, ATTACH_SCREEN_BY_DEFAULT,
            SPEAK, HAPTICS, AUTO_LISTEN, AUTO_LISTEN_ON_OPEN, SMART_FOLLOW_UPS,
            VOICE_PAUSE_FRIENDLY, NEW_CHAT_ON_OPEN,
            HISTORY_ENABLED, SAVE_SCREEN_THUMBNAILS, KEYBOARD_AWARE_ASSISTANT, SHOW_STOP_BUTTON,
            LELO_MODE, BACKGROUND_NOTIFICATIONS, WEATHER_USE_DEVICE_LOCATION,
            MEMORY_ENABLED, MEMORY_USAGE_INDICATOR, MEMORY_SUGGESTIONS,
            NOTIFICATION_AI_ENABLED, AMOLED_MODE, UPDATE_NOTIFICATIONS));
    private static final Set<String> BACKUP_INTEGER_KEYS = new HashSet<>(
            Arrays.asList(NOTIFICATION_RETENTION_DAYS));

    public static SharedPreferences get(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Explicitly excludes provider connection details, tokens, and encrypted credentials. */
    static JSONObject backupSnapshot(Context c) throws Exception {
        JSONObject out = new JSONObject();
        SharedPreferences p = get(c);
        for (String key : BACKUP_STRING_KEYS) if (p.contains(key)) out.put(key, p.getString(key, ""));
        for (String key : BACKUP_BOOLEAN_KEYS) if (p.contains(key)) out.put(key, p.getBoolean(key, false));
        for (String key : BACKUP_INTEGER_KEYS) if (p.contains(key)) out.put(key, p.getInt(key, 0));
        return out;
    }

    static boolean validBackupSnapshot(JSONObject values) {
        if (values == null) return false;
        java.util.Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = values.opt(key);
            if (BACKUP_STRING_KEYS.contains(key)) {
                if (!(value instanceof String)) return false;
            } else if (BACKUP_BOOLEAN_KEYS.contains(key)) {
                if (!(value instanceof Boolean)) return false;
            } else if (BACKUP_INTEGER_KEYS.contains(key)) {
                if (!(value instanceof Number)) return false;
            } else {
                return false;
            }
        }
        return true;
    }

    static boolean restoreBackupSnapshot(Context c, JSONObject values) {
        if (!validBackupSnapshot(values)) return false;
        SharedPreferences.Editor e = get(c).edit();
        for (String key : BACKUP_STRING_KEYS) e.remove(key);
        for (String key : BACKUP_BOOLEAN_KEYS) e.remove(key);
        for (String key : BACKUP_INTEGER_KEYS) e.remove(key);
        java.util.Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (BACKUP_STRING_KEYS.contains(key)) e.putString(key, values.optString(key, ""));
            else if (BACKUP_BOOLEAN_KEYS.contains(key)) e.putBoolean(key, values.optBoolean(key));
            else if (BACKUP_INTEGER_KEYS.contains(key)) e.putInt(key, values.optInt(key));
        }
        return e.commit();
    }

    public static String model(Context c) { return get(c).getString(MODEL, "gpt-5.6-terra"); }
    public static String reasoning(Context c) { return get(c).getString(REASONING, "low"); }
    public static String intelligenceMode(Context c) { return get(c).getString(INTELLIGENCE_MODE, MODE_BALANCED); }
    public static String provider(Context c) {
        return normalizeProvider(get(c).getString(PROVIDER, PROVIDER_CHATGPT));
    }

    /** Unknown stored provider ids resolve to ChatGPT rather than to undefined behavior. */
    public static String normalizeProvider(String provider) {
        if (PROVIDER_CHATGPT.equals(provider) || PROVIDER_RELAY.equals(provider) ||
                PROVIDER_LOCAL.equals(provider) || PROVIDER_OPENROUTER.equals(provider)) return provider;
        return PROVIDER_CHATGPT;
    }
    public static String backendUrl(Context c) { return get(c).getString(BACKEND_URL, "").trim(); }
    public static String token(Context c) { return SecureStore.loadRelayToken(c); }
    public static boolean relayConfigured(Context c) {
        return backendUrl(c).startsWith("https://");
    }

    /** Save the existing HTTPS-relay configuration without ever handling a raw OpenAI API key. */
    public static String saveRelaySettings(Context c, String rawUrl, String relayToken) {
        String url = rawUrl == null ? "" : rawUrl.trim();
        if (!url.isEmpty() && !url.startsWith("https://")) return "Use an HTTPS relay URL.";
        SecureStore.saveRelayToken(c, relayToken == null ? "" : relayToken.trim());
        get(c).edit().putString(BACKEND_URL, url).apply();
        return "";
    }
    public static boolean screenContext(Context c) { return get(c).getBoolean(SCREEN_CONTEXT, true); }
    public static boolean screenshot(Context c) { return get(c).getBoolean(SCREENSHOT, true); }
    public static boolean contextChips(Context c) { return get(c).getBoolean(CONTEXT_CHIPS, true); }
    public static boolean attachScreenByDefault(Context c) { return get(c).getBoolean(ATTACH_SCREEN_BY_DEFAULT, false); }
    public static boolean speak(Context c) { return get(c).getBoolean(SPEAK, true); }
    public static boolean haptics(Context c) { return get(c).getBoolean(HAPTICS, true); }
    public static boolean autoListen(Context c) { return get(c).getBoolean(AUTO_LISTEN, false); }
    public static boolean autoListenOnOpen(Context c) { return get(c).getBoolean(AUTO_LISTEN_ON_OPEN, false); }
    public static boolean smartFollowUps(Context c) { return get(c).getBoolean(SMART_FOLLOW_UPS, true); }
    public static boolean voicePauseFriendly(Context c) { return get(c).getBoolean(VOICE_PAUSE_FRIENDLY, true); }
    public static boolean newChatOnOpen(Context c) { return get(c).getBoolean(NEW_CHAT_ON_OPEN, true); }
    public static boolean historyEnabled(Context c) { return get(c).getBoolean(HISTORY_ENABLED, true); }
    public static boolean saveScreenThumbnails(Context c) { return get(c).getBoolean(SAVE_SCREEN_THUMBNAILS, false); }
    public static boolean keyboardAwareAssistant(Context c) { return get(c).getBoolean(KEYBOARD_AWARE_ASSISTANT, true); }
    public static boolean showStopButton(Context c) { return get(c).getBoolean(SHOW_STOP_BUTTON, true); }
    public static String userBubbleColor(Context c) { return get(c).getString(USER_BUBBLE_COLOR, "classic"); }
    public static String assistantBubbleColor(Context c) { return get(c).getString(ASSISTANT_BUBBLE_COLOR, "classic"); }
    public static String chatTextSize(Context c) {
        String value = get(c).getString(CHAT_TEXT_SIZE, CHAT_TEXT_DEFAULT);
        if (CHAT_TEXT_SMALL.equals(value) || CHAT_TEXT_LARGE.equals(value) ||
                CHAT_TEXT_EXTRA_LARGE.equals(value)) return value;
        return CHAT_TEXT_DEFAULT;
    }
    public static float chatTextScale(Context c) {
        String value = chatTextSize(c);
        if (CHAT_TEXT_SMALL.equals(value)) return 0.88f;
        if (CHAT_TEXT_LARGE.equals(value)) return 1.15f;
        if (CHAT_TEXT_EXTRA_LARGE.equals(value)) return 1.30f;
        return 1f;
    }
    public static float chatTextSp(Context c, float defaultSizeSp) {
        return defaultSizeSp * chatTextScale(c);
    }
    public static boolean leloMode(Context c) { return get(c).getBoolean(LELO_MODE, false); }
    public static boolean backgroundNotifications(Context c) { return get(c).getBoolean(BACKGROUND_NOTIFICATIONS, false); }
    public static String weatherLocation(Context c) { return get(c).getString(WEATHER_LOCATION, "").trim(); }
    public static boolean weatherUseDeviceLocation(Context c) { return get(c).getBoolean(WEATHER_USE_DEVICE_LOCATION, false); }
    /**
     * Selected page-transition style, falling back to Slide for anyone who has never chosen one
     * and for any unrecognised stored value.
     */
    public static String pageTransition(Context c) {
        String value = get(c).getString(PAGE_TRANSITION, PAGE_TRANSITION_SLIDE);
        if (PAGE_TRANSITION_FADE.equals(value) || PAGE_TRANSITION_NONE.equals(value)) return value;
        return PAGE_TRANSITION_SLIDE;
    }

    public static String weatherUnits(Context c) {
        String value = get(c).getString(WEATHER_UNITS, WEATHER_UNITS_SYSTEM);
        if (WEATHER_UNITS_FAHRENHEIT.equals(value) || WEATHER_UNITS_CELSIUS.equals(value))
            return value;
        return WEATHER_UNITS_SYSTEM;
    }
    public static boolean memoryEnabled(Context c) { return get(c).getBoolean(MEMORY_ENABLED, true); }
    public static boolean memoryUsageIndicator(Context c) { return get(c).getBoolean(MEMORY_USAGE_INDICATOR, false); }
    public static boolean memorySuggestions(Context c) { return get(c).getBoolean(MEMORY_SUGGESTIONS, true); }
    public static boolean notificationAiEnabled(Context c) { return get(c).getBoolean(NOTIFICATION_AI_ENABLED, true); }
    public static int notificationRetentionDays(Context c) { return Math.max(1, Math.min(30, get(c).getInt(NOTIFICATION_RETENTION_DAYS, 7))); }
    public static boolean amoledMode(Context c) { return get(c).getBoolean(AMOLED_MODE, false); }
    public static String appFont(Context c) { return get(c).getString(APP_FONT, "orbit_default"); }
    public static boolean updateNotifications(Context c) { return get(c).getBoolean(UPDATE_NOTIFICATIONS, true); }
    public static String quickSettingsRoutineId(Context c) {
        return get(c).getString(QUICK_SETTINGS_ROUTINE_ID, "").trim();
    }
    public static boolean setQuickSettingsRoutineId(Context c, String routineId) {
        SharedPreferences.Editor editor = get(c).edit();
        String clean = routineId == null ? "" : routineId.trim();
        if (clean.isEmpty()) editor.remove(QUICK_SETTINGS_ROUTINE_ID);
        else editor.putString(QUICK_SETTINGS_ROUTINE_ID, clean);
        return editor.commit();
    }

    public static String effectiveModel(Context c, String prompt) {
        return effectiveModelForMode(c, intelligenceMode(c), prompt);
    }

    public static String effectiveModelForMode(Context c, String mode, String prompt) {
        String chosen = normalizeMode(mode);
        if (MODE_FAST.equals(chosen)) return "gpt-5.6-luna";
        if (MODE_BALANCED.equals(chosen)) return "gpt-5.6-terra";
        if (MODE_DEEP.equals(chosen)) return "gpt-5.6-sol";
        if (MODE_CUSTOM.equals(chosen)) return model(c);
        return autoIsDeep(prompt) ? "gpt-5.6-sol" : autoIsQuick(prompt) ? "gpt-5.6-luna" : "gpt-5.6-terra";
    }

    public static String effectiveReasoning(Context c, String prompt) {
        return effectiveReasoningForMode(c, intelligenceMode(c), prompt);
    }

    public static String effectiveReasoningForMode(Context c, String mode, String prompt) {
        String chosen = normalizeMode(mode);
        if (MODE_FAST.equals(chosen)) return "low";
        if (MODE_BALANCED.equals(chosen)) return "medium";
        if (MODE_DEEP.equals(chosen)) return "high";
        if (MODE_CUSTOM.equals(chosen)) return reasoning(c);
        return autoIsDeep(prompt) ? "high" : autoIsQuick(prompt) ? "low" : "medium";
    }

    public static String modeLabel(Context c) { return modeLabel(intelligenceMode(c)); }

    public static String modeLabel(String mode) {
        String chosen = normalizeMode(mode);
        if (MODE_FAST.equals(chosen)) return "Fast";
        if (MODE_DEEP.equals(chosen)) return "Deep";
        if (MODE_CUSTOM.equals(chosen)) return "Custom";
        if (MODE_AUTO.equals(chosen)) return "Auto";
        return "Balanced";
    }

    public static String normalizeMode(String mode) {
        if (MODE_AUTO.equals(mode) || MODE_FAST.equals(mode) || MODE_BALANCED.equals(mode) ||
                MODE_DEEP.equals(mode) || MODE_CUSTOM.equals(mode)) return mode;
        return MODE_BALANCED;
    }

    private static boolean autoIsQuick(String prompt) {
        String p = prompt == null ? "" : prompt.trim().toLowerCase(Locale.US);
        if (p.length() > 90) return false;
        return !containsAny(p, "analyze", "compare", "explain why", "reason", "plan", "evaluate", "pros and cons", "deep", "thorough", "research");
    }

    private static boolean autoIsDeep(String prompt) {
        String p = prompt == null ? "" : prompt.trim().toLowerCase(Locale.US);
        return p.length() > 420 || containsAny(p, "think deeply", "reason carefully", "deep analysis", "thorough analysis", "work this out carefully", "complex", "evaluate in depth");
    }

    private static boolean containsAny(String s, String... needles) {
        for (String n : needles) if (s.contains(n)) return true;
        return false;
    }
}
