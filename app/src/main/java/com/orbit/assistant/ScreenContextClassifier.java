package com.orbit.assistant;

import android.content.Context;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local screen-context classifier for Orbit 0.5.10.
 *
 * App-profile categories always win. Automatic classification then combines
 * foreground-app signals with screen-text signals instead of relying on a
 * single keyword. No screen content is sent anywhere merely to classify it.
 */
public final class ScreenContextClassifier {
    private ScreenContextClassifier() {}

    private static final Pattern MONEY = Pattern.compile(
            "(?:[$€£¥]\\s?\\d|\\d[\\d,.]*\\s?(?:usd|eur|gbp|cad|aud))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PAGE_NUMBER = Pattern.compile(
            "\\bpage\\s+\\d+(?:\\s+of\\s+\\d+)?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_STAMP = Pattern.compile(
            "\\b(?:[01]?\\d|2[0-3]):[0-5]\\d\\b|\\b\\d{1,2}:\\d{2}\\s?(?:am|pm)\\b",
            Pattern.CASE_INSENSITIVE);

    public static final class Result {
        public final String category;
        public final String label;
        public final int confidence;
        public final boolean profileOverride;
        public final String reason;

        Result(String category, int confidence, boolean profileOverride, String reason) {
            this.category = category;
            this.label = AppProfileStore.categoryLabel(category);
            this.confidence = Math.max(0, Math.min(100, confidence));
            this.profileOverride = profileOverride;
            this.reason = reason == null ? "" : reason;
        }
    }

    public static Result classify(Context context, String screenText, boolean hasScreenshot,
                                  String foregroundPackage, String foregroundAppLabel) {
        AppProfileStore.Profile profile = AppProfileStore.get(context, foregroundPackage);
        if (!AppProfileStore.CATEGORY_AUTO.equals(profile.category)) {
            return new Result(profile.category, 100, true, "App profile override");
        }

        String text = screenText == null ? "" : screenText.trim();
        String lower = text.toLowerCase(Locale.US);
        String pkg = foregroundPackage == null ? "" :
                foregroundPackage.toLowerCase(Locale.US).trim();
        String label = foregroundAppLabel == null ? "" :
                foregroundAppLabel.toLowerCase(Locale.US).trim();

        ScoreBoard score = new ScoreBoard();

        scoreAppSignals(score, pkg, label);
        scoreContentSignals(score, lower, text.length());

        if (text.isEmpty() && hasScreenshot) {
            score.add(AppProfileStore.CATEGORY_GENERIC, 2, "Screenshot-only context");
        }

        ScoreBoard.Entry best = score.best();
        ScoreBoard.Entry second = score.secondBest();

        if (best == null || best.score < 5) {
            return new Result(AppProfileStore.CATEGORY_GENERIC,
                    text.isEmpty() ? 36 : 48, false, "No strong context signal");
        }

        int margin = second == null ? best.score : best.score - second.score;

        // A weak tie is more honestly treated as Generic instead of confidently
        // showing the wrong specialized actions.
        if (best.score < 9 && margin <= 1) {
            return new Result(AppProfileStore.CATEGORY_GENERIC, 50, false,
                    "Ambiguous automatic classification");
        }

        int confidence = 48 + Math.min(42, best.score * 3);
        if (margin >= 6) confidence += 6;
        else if (margin <= 2) confidence -= 8;
        confidence = Math.max(52, Math.min(96, confidence));

        return new Result(best.category, confidence, false, best.reason);
    }

    private static void scoreAppSignals(ScoreBoard s, String pkg, String label) {
        if (isEmailPackage(pkg) || containsAny(label, "gmail", "outlook", "email", "mail")) {
            s.add(AppProfileStore.CATEGORY_EMAIL, 14, "Email app");
        } else if (isMessagingPackage(pkg) || containsAny(label,
                "discord", "shiggy", "shiggycord", "kettu", "rain",
                "telegram", "whatsapp", "messenger", "messages", "signal",
                "slack", "teams", "aliucord", "vendetta", "revenge", "bunny")) {
            s.add(AppProfileStore.CATEGORY_CONVERSATION, 14, "Messaging app");
        }

        if (isShoppingPackage(pkg) || containsAny(label,
                "amazon", "ebay", "etsy", "walmart", "best buy", "target",
                "shop", "store", "aliexpress", "temu")) {
            s.add(AppProfileStore.CATEGORY_PRODUCT, 13, "Shopping app");
        }

        if (isSettingsPackage(pkg) || containsAny(label, "settings", "good lock")) {
            s.add(AppProfileStore.CATEGORY_SETTINGS, 15, "Settings app");
        }

        if (isMapPackage(pkg) || containsAny(label,
                "maps", "waze", "navigation", "mapquest")) {
            s.add(AppProfileStore.CATEGORY_MAP, 15, "Map / navigation app");
        }

        if (isDocumentPackage(pkg) || containsAny(label,
                "acrobat", "pdf", "drive", "docs", "word", "office",
                "one drive", "onedrive", "notion", "obsidian")) {
            s.add(AppProfileStore.CATEGORY_DOCUMENT, 11, "Document app");
        }

        if (isMediaPackage(pkg) || containsAny(label,
                "youtube", "spotify", "music", "photos", "gallery",
                "netflix", "twitch", "tiktok", "podcast")) {
            s.add(AppProfileStore.CATEGORY_MEDIA, 11, "Media app");
        }

        if (isBrowserPackage(pkg)) {
            // Browser alone is deliberately weak. The page contents decide whether
            // it is an article, product, document, or generic webpage.
            s.add(AppProfileStore.CATEGORY_ARTICLE, 2, "Browser app");
            s.add(AppProfileStore.CATEGORY_PRODUCT, 1, "Browser app");
        }
    }

    private static void scoreContentSignals(ScoreBoard s, String lower, int length) {
        if (lower.isEmpty()) return;

        // Email has a fairly distinct structure when multiple headers appear.
        int emailHeaders = countAny(lower, "subject:", "from:", "to:", "cc:", "bcc:");
        if (emailHeaders >= 2) s.add(AppProfileStore.CATEGORY_EMAIL, 12, "Email headers");
        else if (emailHeaders == 1) s.add(AppProfileStore.CATEGORY_EMAIL, 4, "Email header");
        if (containsAny(lower, "reply all", "forward", "compose", "inbox"))
            s.add(AppProfileStore.CATEGORY_EMAIL, 4, "Email actions");

        // Conversations: messaging controls, timestamps, mentions, and chat language.
        int chatSignals = countAny(lower, "typing", "message", "reply", "send",
                "dm", "direct message", "conversation", "online", "last seen",
                "react", "replied to", "voice message");
        if (chatSignals >= 3) s.add(AppProfileStore.CATEGORY_CONVERSATION, 10, "Multiple chat signals");
        else if (chatSignals == 2) s.add(AppProfileStore.CATEGORY_CONVERSATION, 7, "Chat signals");
        else if (chatSignals == 1) s.add(AppProfileStore.CATEGORY_CONVERSATION, 3, "Chat signal");
        if (TIME_STAMP.matcher(lower).find() && containsAny(lower, "message", "reply", "typing", "@"))
            s.add(AppProfileStore.CATEGORY_CONVERSATION, 4, "Chat timestamps");

        // Shopping / products.
        Matcher money = MONEY.matcher(lower);
        if (money.find()) s.add(AppProfileStore.CATEGORY_PRODUCT, 6, "Price detected");
        int productSignals = countAny(lower, "add to cart", "buy now", "checkout",
                "shipping", "delivery", "in stock", "out of stock", "reviews",
                "rating", "seller", "quantity", "size", "color", "returns");
        if (productSignals >= 3) s.add(AppProfileStore.CATEGORY_PRODUCT, 11, "Multiple product signals");
        else if (productSignals == 2) s.add(AppProfileStore.CATEGORY_PRODUCT, 7, "Product signals");
        else if (productSignals == 1) s.add(AppProfileStore.CATEGORY_PRODUCT, 3, "Product signal");

        // Documents / academic / PDF-like content.
        int documentSignals = countAny(lower, "abstract", "references", "bibliography",
                "doi:", "table of contents", "chapter ", "figure ", "appendix",
                "download pdf", ".pdf");
        if (PAGE_NUMBER.matcher(lower).find()) documentSignals += 2;
        if (documentSignals >= 4) s.add(AppProfileStore.CATEGORY_DOCUMENT, 13, "Document structure");
        else if (documentSignals >= 2) s.add(AppProfileStore.CATEGORY_DOCUMENT, 8, "Document signals");
        else if (documentSignals == 1) s.add(AppProfileStore.CATEGORY_DOCUMENT, 4, "Document signal");

        // Articles / webpages. Long prose is useful evidence, but not enough alone.
        int articleSignals = countAny(lower, "published", "updated", "author",
                "read time", "min read", "article", "headline", "subscribe",
                "newsletter", "breaking news", "opinion", "story");
        if (articleSignals >= 3) s.add(AppProfileStore.CATEGORY_ARTICLE, 11, "Article structure");
        else if (articleSignals == 2) s.add(AppProfileStore.CATEGORY_ARTICLE, 7, "Article signals");
        else if (articleSignals == 1) s.add(AppProfileStore.CATEGORY_ARTICLE, 3, "Article signal");
        if (length > 1200) s.add(AppProfileStore.CATEGORY_ARTICLE, 3, "Long-form text");
        if (length > 2200) s.add(AppProfileStore.CATEGORY_DOCUMENT, 2, "Long document-like text");

        // Settings screens.
        int settingsSignals = countAny(lower, "settings", "permission", "permissions",
                "notifications", "battery", "display", "privacy", "accessibility",
                "storage", "default app", "allow", "deny", "enabled", "disabled",
                "toggle", "advanced");
        if (settingsSignals >= 4) s.add(AppProfileStore.CATEGORY_SETTINGS, 11, "Multiple settings controls");
        else if (settingsSignals >= 2) s.add(AppProfileStore.CATEGORY_SETTINGS, 7, "Settings controls");
        else if (settingsSignals == 1) s.add(AppProfileStore.CATEGORY_SETTINGS, 2, "Settings signal");

        // Maps / directions.
        int mapSignals = countAny(lower, "directions", "route", "eta", "traffic",
                "destination", "arrive", "arrival", "start navigation", "avoid tolls",
                "miles", "kilometers", " km", " mi", "turn left", "turn right");
        if (mapSignals >= 3) s.add(AppProfileStore.CATEGORY_MAP, 11, "Navigation signals");
        else if (mapSignals == 2) s.add(AppProfileStore.CATEGORY_MAP, 7, "Navigation signals");
        else if (mapSignals == 1) s.add(AppProfileStore.CATEGORY_MAP, 3, "Navigation signal");

        // Media.
        int mediaSignals = countAny(lower, "play", "pause", "playlist", "album",
                "artist", "episode", "season", "watch", "video", "song", "track",
                "views", "subscribers", "podcast", "queue");
        if (mediaSignals >= 4) s.add(AppProfileStore.CATEGORY_MEDIA, 10, "Multiple media signals");
        else if (mediaSignals >= 2) s.add(AppProfileStore.CATEGORY_MEDIA, 6, "Media signals");
        else if (mediaSignals == 1) s.add(AppProfileStore.CATEGORY_MEDIA, 2, "Media signal");
    }

    private static boolean isMessagingPackage(String p) {
        return containsAny(p, "discord", "shiggy", "shiggycord", "kettu", "rain",
                "aliucord", "vendetta", "vendroid", "vencord", "revenge", "bunny",
                "replugged", "nekocord") ||
                p.equals("com.google.android.apps.messaging") ||
                p.startsWith("com.whatsapp") || p.startsWith("org.telegram.messenger") ||
                p.startsWith("org.thunderdog.challegram") || p.equals("com.facebook.orca") ||
                p.equals("com.instagram.android") || p.equals("com.snapchat.android") ||
                p.startsWith("com.slack") || p.startsWith("com.microsoft.teams") ||
                p.startsWith("org.thoughtcrime.securesms");
    }

    private static boolean isEmailPackage(String p) {
        return p.equals("com.google.android.gm") || p.contains("outlook") ||
                p.contains("protonmail") || p.contains("spark") ||
                p.contains("fairemail") || p.contains("email");
    }

    private static boolean isShoppingPackage(String p) {
        return containsAny(p, "amazon", "ebay", "etsy", "walmart", "bestbuy",
                "target", "aliexpress", "temu", "shopify", "shopping");
    }

    private static boolean isSettingsPackage(String p) {
        return p.equals("com.android.settings") || p.contains("settings.intelligence") ||
                p.contains("goodlock");
    }

    private static boolean isMapPackage(String p) {
        return p.equals("com.google.android.apps.maps") || p.contains("waze") ||
                p.contains("maps") || p.contains("navigation");
    }

    private static boolean isDocumentPackage(String p) {
        return containsAny(p, "acrobat", "pdf", "office", "word", "docs",
                "drive", "onedrive", "notion", "obsidian");
    }

    private static boolean isMediaPackage(String p) {
        return containsAny(p, "youtube", "spotify", "music", "gallery", "photos",
                "netflix", "twitch", "tiktok", "podcast");
    }

    private static boolean isBrowserPackage(String p) {
        return containsAny(p, "chrome", "firefox", "brave", "vivaldi", "browser",
                "edge", "zen", "helium");
    }

    private static int countAny(String text, String... needles) {
        int count = 0;
        for (String n : needles) if (text.contains(n)) count++;
        return count;
    }

    private static boolean containsAny(String text, String... needles) {
        if (text == null) return false;
        for (String n : needles) if (text.contains(n)) return true;
        return false;
    }

    private static final class ScoreBoard {
        private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();

        ScoreBoard() {
            seed(AppProfileStore.CATEGORY_CONVERSATION);
            seed(AppProfileStore.CATEGORY_PRODUCT);
            seed(AppProfileStore.CATEGORY_ARTICLE);
            seed(AppProfileStore.CATEGORY_SETTINGS);
            seed(AppProfileStore.CATEGORY_MEDIA);
            seed(AppProfileStore.CATEGORY_MAP);
            seed(AppProfileStore.CATEGORY_DOCUMENT);
            seed(AppProfileStore.CATEGORY_EMAIL);
            seed(AppProfileStore.CATEGORY_GENERIC);
        }

        private void seed(String category) {
            entries.put(category, new Entry(category));
        }

        void add(String category, int points, String reason) {
            Entry e = entries.get(category);
            if (e == null) {
                e = new Entry(category);
                entries.put(category, e);
            }
            e.score += Math.max(0, points);
            if (reason != null && !reason.isEmpty() &&
                    (e.reason.isEmpty() || points >= e.reasonWeight)) {
                e.reason = reason;
                e.reasonWeight = points;
            }
        }

        Entry best() {
            Entry best = null;
            for (Entry e : entries.values()) {
                if (AppProfileStore.CATEGORY_GENERIC.equals(e.category)) continue;
                if (best == null || e.score > best.score) best = e;
            }
            return best;
        }

        Entry secondBest() {
            Entry best = null, second = null;
            for (Entry e : entries.values()) {
                if (AppProfileStore.CATEGORY_GENERIC.equals(e.category)) continue;
                if (best == null || e.score > best.score) {
                    second = best;
                    best = e;
                } else if (second == null || e.score > second.score) {
                    second = e;
                }
            }
            return second;
        }

        static final class Entry {
            final String category;
            int score;
            int reasonWeight;
            String reason = "";

            Entry(String category) { this.category = category; }
        }
    }
}
