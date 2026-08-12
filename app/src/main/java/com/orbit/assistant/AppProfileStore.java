package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** User-configurable overrides for Orbit behavior in individual Android apps. */
public final class AppProfileStore {
    private static final String FILE = "orbit_app_profiles";
    private static final String KEY = "profiles_v1";

    public static final String CATEGORY_AUTO = "auto";
    public static final String CATEGORY_CONVERSATION = "conversation";
    public static final String CATEGORY_PRODUCT = "product";
    public static final String CATEGORY_ARTICLE = "article";
    public static final String CATEGORY_SETTINGS = "settings";
    public static final String CATEGORY_MEDIA = "media";
    public static final String CATEGORY_MAP = "map";
    public static final String CATEGORY_DOCUMENT = "document";
    public static final String CATEGORY_EMAIL = "email";
    public static final String CATEGORY_GENERIC = "generic";

    public static final String PRIVACY_AUTO = "auto";
    public static final String PRIVACY_NORMAL = "normal";
    public static final String PRIVACY_SENSITIVE = "sensitive";
    public static final String PRIVACY_NEVER = "never";

    public static final String SCREEN_GLOBAL = "global";
    public static final String SCREEN_ATTACH = "attach";
    public static final String SCREEN_NEVER = "never";

    public static final String SCREENSHOT_GLOBAL = "global";
    public static final String SCREENSHOT_ALLOW = "allow";
    public static final String SCREENSHOT_BLOCK = "block";

    public static final String MODE_GLOBAL = "global";

    public static final String ACTION_AUTO = "auto";
    public static final String ACTION_DRAFT = "draft_reply";
    public static final String ACTION_SUMMARIZE = "summarize";
    public static final String ACTION_EXPLAIN = "explain";
    public static final String ACTION_TONE = "explain_tone";
    public static final String ACTION_NEEDS_ACTION = "needs_action";
    public static final String ACTION_WORTH = "worth_it";
    public static final String ACTION_COMPARE = "compare";
    public static final String ACTION_KEY_SPECS = "key_specs";
    public static final String ACTION_KEY_POINTS = "key_points";
    public static final String ACTION_RECOMMEND = "recommend";
    public static final String ACTION_WHAT_MATTERS = "what_matters";
    public static final String ACTION_WHICH_OPTION = "which_option";
    public static final String ACTION_ROUTE = "route_summary";
    public static final String ACTION_WHAT_NEXT = "what_next";

    private AppProfileStore() {}

    public static final class Profile {
        public final String packageName, label, category, privacyPolicy, screenPolicy,
                screenshotPolicy, intelligenceMode, action1, action2, action3, actionOverride;
        public final long updatedAt;

        /** Backward-compatible constructor used by older source branches. */
        public Profile(String packageName, String label, String category, String screenPolicy,
                       String screenshotPolicy, String intelligenceMode, String actionOverride,
                       long updatedAt) {
            this(packageName, label, category, PRIVACY_AUTO, screenPolicy, screenshotPolicy,
                    intelligenceMode, ACTION_AUTO, ACTION_AUTO, actionOverride, updatedAt);
        }

        public Profile(String packageName, String label, String category, String privacyPolicy,
                       String screenPolicy, String screenshotPolicy, String intelligenceMode,
                       String action1, String action2, String action3, long updatedAt) {
            this.packageName = safe(packageName);
            this.label = safe(label);
            this.category = normalizeCategory(category);
            this.privacyPolicy = normalizePrivacy(privacyPolicy);
            this.screenPolicy = normalizeScreen(screenPolicy);
            this.screenshotPolicy = normalizeScreenshot(screenshotPolicy);
            this.intelligenceMode = normalizeMode(intelligenceMode);
            this.action1 = normalizeAction(action1);
            this.action2 = normalizeAction(action2);
            this.action3 = normalizeAction(action3);
            this.actionOverride = this.action3;
            this.updatedAt = updatedAt;
        }

        public boolean isDefault() {
            return CATEGORY_AUTO.equals(category) && PRIVACY_AUTO.equals(privacyPolicy) &&
                    SCREEN_GLOBAL.equals(screenPolicy) && SCREENSHOT_GLOBAL.equals(screenshotPolicy) &&
                    MODE_GLOBAL.equals(intelligenceMode) && ACTION_AUTO.equals(action1) &&
                    ACTION_AUTO.equals(action2) && ACTION_AUTO.equals(action3);
        }
    }

    public static synchronized Profile get(Context c, String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return defaultProfile("", "");
        for (Profile p : readAll(c)) if (packageName.equals(p.packageName)) return p;
        return defaultProfile(packageName, "");
    }

    public static synchronized List<Profile> list(Context c) {
        List<Profile> all = readAll(c);
        all.sort((a,b) -> Long.compare(b.updatedAt, a.updatedAt));
        return all;
    }

    public static synchronized void save(Context c, Profile profile) {
        if (profile == null || profile.packageName.isEmpty()) return;
        List<Profile> all = readAll(c);
        all.removeIf(p -> p.packageName.equals(profile.packageName));
        Profile normalized = new Profile(profile.packageName, profile.label, profile.category,
                profile.privacyPolicy, profile.screenPolicy, profile.screenshotPolicy,
                profile.intelligenceMode, profile.action1, profile.action2, profile.action3,
                System.currentTimeMillis());
        if (!normalized.isDefault()) all.add(0, normalized);
        writeAll(c, all);
    }

    public static synchronized void reset(Context c, String packageName) {
        List<Profile> all = readAll(c);
        if (all.removeIf(p -> p.packageName.equals(packageName))) writeAll(c, all);
    }

    public static Profile defaultProfile(String pkg, String label) {
        return new Profile(pkg, label, CATEGORY_AUTO, PRIVACY_AUTO, SCREEN_GLOBAL,
                SCREENSHOT_GLOBAL, MODE_GLOBAL, ACTION_AUTO, ACTION_AUTO, ACTION_AUTO, 0);
    }

    public static String effectivePrivacy(Context c, String pkg) {
        Profile p = get(c, pkg);
        if (!PRIVACY_AUTO.equals(p.privacyPolicy)) return p.privacyPolicy;
        return looksSensitivePackage(pkg) ? PRIVACY_SENSITIVE : PRIVACY_NORMAL;
    }

    public static boolean shouldAttachByDefault(Context c, String pkg, boolean globalDefault) {
        String privacy = effectivePrivacy(c, pkg);
        if (PRIVACY_NEVER.equals(privacy) || PRIVACY_SENSITIVE.equals(privacy)) return false;
        Profile p = get(c, pkg);
        if (SCREEN_NEVER.equals(p.screenPolicy)) return false;
        if (SCREEN_ATTACH.equals(p.screenPolicy)) return true;
        return globalDefault;
    }

    public static boolean screenBlocked(Context c, String pkg) {
        String privacy = effectivePrivacy(c, pkg);
        return PRIVACY_NEVER.equals(privacy) || SCREEN_NEVER.equals(get(c, pkg).screenPolicy);
    }

    public static boolean screenshotAllowed(Context c, String pkg) {
        if (!Prefs.screenshot(c)) return false;
        String privacy = effectivePrivacy(c, pkg);
        if (PRIVACY_NEVER.equals(privacy) || PRIVACY_SENSITIVE.equals(privacy)) return false;
        return !SCREENSHOT_BLOCK.equals(get(c, pkg).screenshotPolicy);
    }

    public static String defaultMode(Context c, String pkg, String globalMode) {
        String mode = get(c, pkg).intelligenceMode;
        return MODE_GLOBAL.equals(mode) ? Prefs.normalizeMode(globalMode) : Prefs.normalizeMode(mode);
    }

    public static String categoryLabel(String c) {
        if (CATEGORY_CONVERSATION.equals(c)) return "Conversation";
        if (CATEGORY_PRODUCT.equals(c)) return "Product / shopping";
        if (CATEGORY_ARTICLE.equals(c)) return "Article / webpage";
        if (CATEGORY_SETTINGS.equals(c)) return "Settings";
        if (CATEGORY_MEDIA.equals(c)) return "Media";
        if (CATEGORY_MAP.equals(c)) return "Map / navigation";
        if (CATEGORY_DOCUMENT.equals(c)) return "Document";
        if (CATEGORY_EMAIL.equals(c)) return "Email";
        if (CATEGORY_GENERIC.equals(c)) return "Generic";
        return "Automatic";
    }

    public static String privacyLabel(String p) {
        if (PRIVACY_NORMAL.equals(p)) return "Normal";
        if (PRIVACY_SENSITIVE.equals(p)) return "Sensitive";
        if (PRIVACY_NEVER.equals(p)) return "No screen access";
        return "Automatic";
    }

    public static String effectivePrivacyLabel(Context c, String pkg) {
        return privacyLabel(effectivePrivacy(c, pkg));
    }

    public static String screenLabel(String s) {
        if (SCREEN_ATTACH.equals(s)) return "Attach by default";
        if (SCREEN_NEVER.equals(s)) return "Never use screen";
        return "Use global setting";
    }

    public static String modeLabel(String m) {
        return MODE_GLOBAL.equals(m) ? "Use global default" : Prefs.modeLabel(m);
    }

    public static String actionLabel(String action) {
        if (ACTION_DRAFT.equals(action)) return "Draft reply";
        if (ACTION_SUMMARIZE.equals(action)) return "Summarize";
        if (ACTION_EXPLAIN.equals(action)) return "Explain";
        if (ACTION_TONE.equals(action)) return "Explain tone";
        if (ACTION_NEEDS_ACTION.equals(action)) return "Needs action?";
        if (ACTION_WORTH.equals(action)) return "Worth it?";
        if (ACTION_COMPARE.equals(action)) return "Compare";
        if (ACTION_KEY_SPECS.equals(action)) return "Key specs";
        if (ACTION_KEY_POINTS.equals(action)) return "Key points";
        if (ACTION_RECOMMEND.equals(action)) return "Recommend";
        if (ACTION_WHAT_MATTERS.equals(action)) return "What matters?";
        if (ACTION_WHICH_OPTION.equals(action)) return "Which option?";
        if (ACTION_ROUTE.equals(action)) return "Route summary";
        if (ACTION_WHAT_NEXT.equals(action)) return "What next?";
        return "Automatic";
    }

    public static boolean hasCustomActions(Profile p) {
        return p != null && (!ACTION_AUTO.equals(p.action1) || !ACTION_AUTO.equals(p.action2) ||
                !ACTION_AUTO.equals(p.action3));
    }

    private static boolean looksSensitivePackage(String pkg) {
        String p = safe(pkg).toLowerCase(Locale.US);
        if (p.isEmpty()) return false;
        return containsAny(p, "authenticator", "password", "bitwarden", "1password", "lastpass",
                "keepersecurity", "dashlane", "proton.pass", "protonpass", "banking", ".bank.",
                "mobilebank", "bankmobile", "walletnfcrel", "samsung.android.spay", "paypal",
                "venmo", "cashapp", "robinhood", "coinbase");
    }

    private static boolean containsAny(String text, String... needles) {
        for (String n : needles) if (text.contains(n)) return true;
        return false;
    }

    private static String normalizeCategory(String v) {
        if (CATEGORY_CONVERSATION.equals(v) || CATEGORY_PRODUCT.equals(v) || CATEGORY_ARTICLE.equals(v) ||
                CATEGORY_SETTINGS.equals(v) || CATEGORY_MEDIA.equals(v) || CATEGORY_MAP.equals(v) ||
                CATEGORY_DOCUMENT.equals(v) || CATEGORY_EMAIL.equals(v) || CATEGORY_GENERIC.equals(v)) return v;
        return CATEGORY_AUTO;
    }
    private static String normalizePrivacy(String v) {
        if (PRIVACY_NORMAL.equals(v) || PRIVACY_SENSITIVE.equals(v) || PRIVACY_NEVER.equals(v)) return v;
        return PRIVACY_AUTO;
    }
    private static String normalizeScreen(String v) {
        return SCREEN_ATTACH.equals(v) || SCREEN_NEVER.equals(v) ? v : SCREEN_GLOBAL;
    }
    private static String normalizeScreenshot(String v) {
        return SCREENSHOT_ALLOW.equals(v) || SCREENSHOT_BLOCK.equals(v) ? v : SCREENSHOT_GLOBAL;
    }
    private static String normalizeMode(String v) {
        if (Prefs.MODE_AUTO.equals(v) || Prefs.MODE_FAST.equals(v) || Prefs.MODE_BALANCED.equals(v) ||
                Prefs.MODE_DEEP.equals(v) || Prefs.MODE_CUSTOM.equals(v)) return v;
        return MODE_GLOBAL;
    }
    private static String normalizeAction(String v) {
        if (ACTION_DRAFT.equals(v) || ACTION_SUMMARIZE.equals(v) || ACTION_EXPLAIN.equals(v) ||
                ACTION_TONE.equals(v) || ACTION_NEEDS_ACTION.equals(v) || ACTION_WORTH.equals(v) ||
                ACTION_COMPARE.equals(v) || ACTION_KEY_SPECS.equals(v) || ACTION_KEY_POINTS.equals(v) ||
                ACTION_RECOMMEND.equals(v) || ACTION_WHAT_MATTERS.equals(v) ||
                ACTION_WHICH_OPTION.equals(v) || ACTION_ROUTE.equals(v) || ACTION_WHAT_NEXT.equals(v)) return v;
        return ACTION_AUTO;
    }
    private static String safe(String s) { return s == null ? "" : s.trim(); }

    private static List<Profile> readAll(Context c) {
        ArrayList<Profile> out = new ArrayList<>();
        try {
            SharedPreferences p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(p.getString(KEY, "[]"));
            for (int i=0;i<arr.length();i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String pkg = o.optString("packageName", "").trim();
                if (pkg.isEmpty()) continue;
                String legacyAction = o.optString("actionOverride", ACTION_AUTO);
                out.add(new Profile(pkg, o.optString("label"), o.optString("category"),
                        o.optString("privacyPolicy", PRIVACY_AUTO), o.optString("screenPolicy"),
                        o.optString("screenshotPolicy"), o.optString("intelligenceMode"),
                        o.optString("action1", ACTION_AUTO), o.optString("action2", ACTION_AUTO),
                        o.optString("action3", legacyAction), o.optLong("updatedAt")));
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static void writeAll(Context c, List<Profile> all) {
        JSONArray arr = new JSONArray();
        try {
            for (Profile p : all) arr.put(new JSONObject().put("packageName", p.packageName)
                    .put("label", p.label).put("category", p.category)
                    .put("privacyPolicy", p.privacyPolicy).put("screenPolicy", p.screenPolicy)
                    .put("screenshotPolicy", p.screenshotPolicy).put("intelligenceMode", p.intelligenceMode)
                    .put("action1", p.action1).put("action2", p.action2).put("action3", p.action3)
                    .put("actionOverride", p.action3).put("updatedAt", p.updatedAt));
        } catch (Exception ignored) {}
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply();
    }
}
