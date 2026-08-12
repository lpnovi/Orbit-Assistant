package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

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
    public static final String VOICE_PAUSE_FRIENDLY = "voice_pause_friendly";
    public static final String ACCENT = "accent";
    public static final String NEW_CHAT_ON_OPEN = "new_chat_on_open";
    public static final String HISTORY_ENABLED = "history_enabled";
    public static final String SAVE_SCREEN_THUMBNAILS = "save_screen_thumbnails";
    public static final String KEYBOARD_AWARE_ASSISTANT = "keyboard_aware_assistant";
    public static final String USER_BUBBLE_COLOR = "user_bubble_color";
    public static final String ASSISTANT_BUBBLE_COLOR = "assistant_bubble_color";
    public static final String LELO_MODE = "lelo_mode";
    public static final String BACKGROUND_NOTIFICATIONS = "background_notifications";
    public static final String WEATHER_LOCATION = "weather_location";
    public static final String WEATHER_USE_DEVICE_LOCATION = "weather_use_device_location";
    public static final String MEMORY_ENABLED = "memory_enabled";
    public static final String MEMORY_USAGE_INDICATOR = "memory_usage_indicator";
    public static final String MEMORY_SUGGESTIONS = "memory_suggestions";
    public static final String NOTIFICATION_AI_ENABLED = "notification_ai_enabled";
    public static final String NOTIFICATION_RETENTION_DAYS = "notification_retention_days";
    public static final String AMOLED_MODE = "amoled_mode";
    public static final String APP_FONT = "app_font";

    public static final String PROVIDER_CHATGPT = "chatgpt";
    public static final String PROVIDER_RELAY = "relay";

    public static final String MODE_AUTO = "auto";
    public static final String MODE_FAST = "fast";
    public static final String MODE_BALANCED = "balanced";
    public static final String MODE_DEEP = "deep";
    public static final String MODE_CUSTOM = "custom";

    private Prefs() {}

    public static SharedPreferences get(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String model(Context c) { return get(c).getString(MODEL, "gpt-5.6-terra"); }
    public static String reasoning(Context c) { return get(c).getString(REASONING, "low"); }
    public static String intelligenceMode(Context c) { return get(c).getString(INTELLIGENCE_MODE, MODE_BALANCED); }
    public static String provider(Context c) { return get(c).getString(PROVIDER, PROVIDER_CHATGPT); }
    public static String backendUrl(Context c) { return get(c).getString(BACKEND_URL, "").trim(); }
    public static String token(Context c) { return SecureStore.loadRelayToken(c); }
    public static boolean screenContext(Context c) { return get(c).getBoolean(SCREEN_CONTEXT, true); }
    public static boolean screenshot(Context c) { return get(c).getBoolean(SCREENSHOT, true); }
    public static boolean contextChips(Context c) { return get(c).getBoolean(CONTEXT_CHIPS, true); }
    public static boolean attachScreenByDefault(Context c) { return get(c).getBoolean(ATTACH_SCREEN_BY_DEFAULT, false); }
    public static boolean speak(Context c) { return get(c).getBoolean(SPEAK, true); }
    public static boolean haptics(Context c) { return get(c).getBoolean(HAPTICS, true); }
    public static boolean autoListen(Context c) { return get(c).getBoolean(AUTO_LISTEN, false); }
    public static boolean voicePauseFriendly(Context c) { return get(c).getBoolean(VOICE_PAUSE_FRIENDLY, true); }
    public static boolean newChatOnOpen(Context c) { return get(c).getBoolean(NEW_CHAT_ON_OPEN, true); }
    public static boolean historyEnabled(Context c) { return get(c).getBoolean(HISTORY_ENABLED, true); }
    public static boolean saveScreenThumbnails(Context c) { return get(c).getBoolean(SAVE_SCREEN_THUMBNAILS, false); }
    public static boolean keyboardAwareAssistant(Context c) { return get(c).getBoolean(KEYBOARD_AWARE_ASSISTANT, true); }
    public static String userBubbleColor(Context c) { return get(c).getString(USER_BUBBLE_COLOR, "classic"); }
    public static String assistantBubbleColor(Context c) { return get(c).getString(ASSISTANT_BUBBLE_COLOR, "classic"); }
    public static boolean leloMode(Context c) { return get(c).getBoolean(LELO_MODE, false); }
    public static boolean backgroundNotifications(Context c) { return get(c).getBoolean(BACKGROUND_NOTIFICATIONS, false); }
    public static String weatherLocation(Context c) { return get(c).getString(WEATHER_LOCATION, "").trim(); }
    public static boolean weatherUseDeviceLocation(Context c) { return get(c).getBoolean(WEATHER_USE_DEVICE_LOCATION, false); }
    public static boolean memoryEnabled(Context c) { return get(c).getBoolean(MEMORY_ENABLED, true); }
    public static boolean memoryUsageIndicator(Context c) { return get(c).getBoolean(MEMORY_USAGE_INDICATOR, false); }
    public static boolean memorySuggestions(Context c) { return get(c).getBoolean(MEMORY_SUGGESTIONS, true); }
    public static boolean notificationAiEnabled(Context c) { return get(c).getBoolean(NOTIFICATION_AI_ENABLED, true); }
    public static int notificationRetentionDays(Context c) { return Math.max(1, Math.min(30, get(c).getInt(NOTIFICATION_RETENTION_DAYS, 7))); }
    public static boolean amoledMode(Context c) { return get(c).getBoolean(AMOLED_MODE, false); }
    public static String appFont(Context c) { return get(c).getString(APP_FONT, "orbit_default"); }

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
