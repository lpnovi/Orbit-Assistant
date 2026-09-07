package com.orbit.assistant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Every Orbit setting somebody might go looking for, written down once.
 *
 * <p>Orbit Settings crossed the line where browsing stops working. Ten categories, forty-odd
 * controls, and finding AMOLED means remembering that it lives inside Theme Studio inside Look &amp;
 * Feel. At that size a settings page is not a page any more, it is a search problem, and the answer
 * to a search problem is an index.
 *
 * <p><b>Written by hand, and deliberately not scraped.</b> The tempting implementation is to walk
 * the built view tree collecting every {@code TextView} - it needs no maintenance and it is wrong.
 * A scraped index searches whatever a screen happens to have drawn, which includes help paragraphs,
 * privacy notes and button labels; it cannot know a control's section once the view is detached; it
 * cannot carry an alias, so "dark mode" finds nothing because nothing on screen says it; and it
 * silently changes behaviour every time somebody rewords a caption. Structured entries cost a line
 * per setting and are the only version that can answer the question people actually ask.
 *
 * <p>Entirely local. No network, no provider, no AI, no database: forty entries in memory, matched
 * with string comparisons. Typing in this field must never be a reason for Orbit to contact
 * anything.
 */
public final class SettingsSearchIndex {

    /** How many results are worth showing before a list stops being a shortcut. */
    public static final int MAX_RESULTS = 8;

    /** One thing the user can go and change. */
    public static final class Entry {
        /** The control's own words, as they appear on the settings row. */
        public final String title;
        /** One short line of what it does, or empty. Matched, and shown when it helps. */
        public final String description;
        /** The Settings section holding it, in Orbit's own display words. */
        public final String sectionName;
        /** The section id this entry's screen opens with. */
        public final String section;
        /**
         * The control key this entry scrolls to, or empty for a section-level result.
         *
         * <p>The difference between "took me to Look &amp; Feel" and "took me to AMOLED". A result
         * with a key scrolls to and pulses that exact row; one without opens the section, which is
         * the honest outcome when a setting lives behind another screen entirely.
         */
        public final String controlKey;
        /**
         * Words somebody would type that the row does not contain.
         *
         * <p>Where most of this index's value is. "dark mode" is not written anywhere in Orbit
         * because Orbit's control is called AMOLED; "saved stuff" is not written anywhere because
         * the feature is called Vault. Without aliases a search for either finds nothing at all,
         * which reads as the setting not existing.
         */
        public final List<String> aliases;

        Entry(String title, String description, String sectionName, String section,
              String controlKey, String... aliases) {
            this.title = title == null ? "" : title;
            this.description = description == null ? "" : description;
            this.sectionName = sectionName == null ? "" : sectionName;
            this.section = section == null ? "" : section;
            this.controlKey = controlKey == null ? "" : controlKey;
            List<String> words = new ArrayList<>();
            if (aliases != null) {
                for (String alias : aliases) {
                    if (alias != null && !alias.trim().isEmpty()) {
                        words.add(alias.trim().toLowerCase(Locale.US));
                    }
                }
            }
            this.aliases = Collections.unmodifiableList(words);
        }

        /** Whether choosing this result can land on an exact control rather than a section. */
        public boolean hasControl() { return !controlKey.isEmpty(); }

        /** The second line of a result row: which part of Settings this lives in. */
        public String subtitle() { return sectionName; }
    }

    // ---- control keys (navigation identity: never rename) ---------------------------------------

    public static final String KEY_DEFAULT_ASSISTANT = "default_assistant";
    public static final String KEY_QUICK_TILES = "quick_tiles";
    public static final String KEY_WIDGETS = "widgets";
    public static final String KEY_PROVIDER = "provider";
    public static final String KEY_CHATGPT_ACCOUNT = "chatgpt_account";
    public static final String KEY_MODE = "intelligence_mode";
    public static final String KEY_MODEL = "custom_model";
    public static final String KEY_REASONING = "custom_reasoning";
    public static final String KEY_THINKING_UPDATES = "thinking_updates";
    public static final String KEY_RICH_ANSWERS = "rich_answers";
    public static final String KEY_SCREEN_TEXT = "screen_text";
    public static final String KEY_SCREENSHOTS = "screenshots";
    public static final String KEY_ATTACH_SCREEN = "attach_screen";
    public static final String KEY_CONTEXT_CHIPS = "context_chips";
    public static final String KEY_SPEAK = "speak_replies";
    public static final String KEY_VOICE_PAUSE = "voice_pause";
    public static final String KEY_AUTO_LISTEN = "auto_listen";
    public static final String KEY_FOLLOW_UPS = "follow_ups";
    public static final String KEY_SMART_FOLLOW_UPS = "smart_follow_ups";
    public static final String KEY_KEYBOARD_AWARE = "keyboard_aware";
    public static final String KEY_PERMISSIONS = "permissions";
    public static final String KEY_MEMORY = "memory";
    public static final String KEY_REMINDERS = "reminders";
    public static final String KEY_PLACES = "saved_places";
    public static final String KEY_APP_PROFILES = "app_profiles";
    public static final String KEY_NOTIFICATIONS = "notifications";
    public static final String KEY_VAULT = "vault";
    public static final String KEY_WEATHER = "weather";
    public static final String KEY_GALLERY = "gallery";
    public static final String KEY_BACKUP = "backup";
    public static final String KEY_DECK = "deck";
    public static final String KEY_NEW_CHAT = "new_chat_on_open";
    public static final String KEY_HISTORY = "chat_history";
    public static final String KEY_THUMBNAILS = "chat_thumbnails";
    public static final String KEY_STOP_BUTTON = "stop_button";
    public static final String KEY_CLEAR_HISTORY = "clear_history";
    public static final String KEY_THEME_STUDIO = "theme_studio";
    public static final String KEY_FONT = "app_font";
    public static final String KEY_CHAT_TEXT_SIZE = "chat_text_size";
    public static final String KEY_HAPTICS = "haptics";
    public static final String KEY_PAGE_TRANSITIONS = "page_transitions";
    public static final String KEY_SWIPE_BACK = "swipe_back";
    public static final String KEY_CHAT_SWIPE = "chat_swipe";
    public static final String KEY_DIAGNOSTICS = "diagnostics";
    public static final String KEY_ROUTINES = "routines";
    public static final String KEY_EXTENSIONS = "extensions";
    public static final String KEY_UPDATES = "updates";

    private static final String S_ASSISTANT = "Assistant setup";
    private static final String S_AI = "AI & account";
    private static final String S_VOICE = "Voice, context & permissions";
    private static final String S_DATA = "Personalization & data";
    private static final String S_DECK = "Orbit Deck";
    private static final String S_CHATS = "Conversations";
    private static final String S_LOOK = "Look & Feel";
    private static final String S_ADVANCED = "Advanced";
    private static final String S_ROUTINES = "Routines";
    private static final String S_EXTENSIONS = "Extensions";
    private static final String S_UPDATES = "About & updates";

    private static final List<Entry> ENTRIES = buildEntries();

    private SettingsSearchIndex() {}

    /** Every indexed setting. Stable order, so equal-scoring results never shuffle. */
    public static List<Entry> all() { return ENTRIES; }

    /**
     * The settings matching what somebody has typed, best first.
     *
     * <p>Substring matching over four fields, weighted by which one matched and how completely. It
     * is not clever and it does not need to be: with forty entries, "amo" reaching AMOLED and
     * "swipe" reaching both swipe settings is the entire requirement, and a cleverer ranker would
     * be harder to predict without finding anything a simple one misses.
     */
    public static List<Entry> search(String query) {
        List<Entry> results = new ArrayList<>();
        String text = normalize(query);
        if (text.isEmpty()) return results;
        String[] terms = text.split(" ");

        List<int[]> scored = new ArrayList<>();
        for (int i = 0; i < ENTRIES.size(); i++) {
            int score = score(ENTRIES.get(i), terms);
            if (score > 0) scored.add(new int[]{score, i});
        }
        // Descending by score, and by original position when scores tie, so the list is stable and
        // a keystroke that changes nothing does not reorder what is on screen.
        scored.sort((a, b) -> a[0] != b[0] ? Integer.compare(b[0], a[0]) : Integer.compare(a[1], b[1]));
        for (int[] row : scored) {
            if (results.size() >= MAX_RESULTS) break;
            results.add(ENTRIES.get(row[1]));
        }
        return results;
    }

    /**
     * How well one entry answers a query, or zero for no match.
     *
     * <p>Every term has to match something, which is what stops "chat text" scoring on any entry
     * containing the word "chat". Where a term matched decides the weight: a title is what the user
     * is looking for, an alias is what they typed instead of it, and a description is the weakest
     * because a word in a help sentence is usually incidental.
     */
    static int score(Entry entry, String[] terms) {
        if (entry == null || terms == null || terms.length == 0) return 0;
        String title = normalize(entry.title);
        String description = normalize(entry.description);
        String section = normalize(entry.sectionName);
        int total = 0;
        for (String term : terms) {
            if (term.isEmpty()) continue;
            int best = 0;
            if (title.equals(term)) best = 100;
            else if (title.startsWith(term)) best = 70;
            else if (containsWordStart(title, term)) best = 55;
            else if (title.contains(term)) best = 40;
            for (String alias : entry.aliases) {
                int aliasScore = alias.equals(term) ? 65
                        : alias.startsWith(term) ? 50
                        : alias.contains(term) ? 30 : 0;
                if (aliasScore > best) best = aliasScore;
            }
            if (best == 0 && containsWordStart(section, term)) best = 22;
            if (best == 0 && containsWordStart(description, term)) best = 18;
            if (best == 0 && description.contains(term)) best = 10;
            // Every term has to land somewhere, or this entry is not what was asked for.
            if (best == 0) return 0;
            total += best;
        }
        // A result that can take somebody to the exact control is worth more than one that can only
        // open the section it lives in, because that is the whole promise of settings search.
        return entry.hasControl() ? total + 3 : total;
    }

    /** Whether a term begins a word inside a phrase, rather than landing mid-word. */
    static boolean containsWordStart(String haystack, String term) {
        if (haystack.isEmpty() || term.isEmpty()) return false;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(term, from);
            if (at < 0) return false;
            if (at == 0 || !Character.isLetterOrDigit(haystack.charAt(at - 1))) return true;
            from = at + 1;
        }
    }

    static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US).replaceAll("\\s+", " ").trim();
    }

    /**
     * The index itself.
     *
     * <p>One line per control the user can reach, in roughly the order Settings presents them. Kept
     * bounded on purpose: this is the list of things somebody goes looking for, not a transcript of
     * the settings screen. A control that is only ever reached by first choosing something else -
     * an individual Theme Studio swatch, a per-routine option - belongs to the screen that owns it
     * rather than here.
     */
    private static List<Entry> buildEntries() {
        List<Entry> out = new ArrayList<>();

        out.add(new Entry("Make Orbit default assistant",
                "Set Orbit as the Digital assistant app and map the Side button.",
                S_ASSISTANT, "assistant", KEY_DEFAULT_ASSISTANT,
                "side button", "bixby", "assistant", "default", "long press", "setup"));
        out.add(new Entry("Quick Settings tiles",
                "Open Orbit or run a Routine from the Quick Settings panel.",
                S_ASSISTANT, "assistant", KEY_QUICK_TILES,
                "tile", "quick settings", "shade", "pull down"));
        out.add(new Entry("Home-screen widgets",
                "Add the Ask Orbit, Run Routine and Quick Actions widgets.",
                S_ASSISTANT, "assistant", KEY_WIDGETS,
                "widget", "home screen", "launcher"));

        out.add(new Entry("AI provider",
                "Choose which AI backend Orbit sends requests to.",
                S_AI, "ai", KEY_PROVIDER,
                "provider", "backend", "openrouter", "relay", "orbit local", "chatgpt", "account"));
        out.add(new Entry("ChatGPT sign-in",
                "Connect or disconnect your ChatGPT account.",
                S_AI, "ai", KEY_CHATGPT_ACCOUNT,
                "chatgpt", "sign in", "log in", "sign out", "account", "openai", "codex"));
        out.add(new Entry("Default mode for new chats",
                "Auto, Fast, Balanced, Deep or Custom.",
                S_AI, "ai", KEY_MODE,
                "auto", "fast", "balanced", "deep", "intelligence", "strength", "mode"));
        out.add(new Entry("Custom model",
                "Pick the exact model Custom mode uses.",
                S_AI, "ai", KEY_MODEL,
                "model", "ai model", "astra", "gpt-6 astra", "sol", "terra", "luna", "gpt"));
        out.add(new Entry("Custom reasoning",
                "How much reasoning effort Custom mode asks for.",
                S_AI, "ai", KEY_REASONING,
                "reasoning", "effort", "thinking", "low", "medium", "high"));
        out.add(new Entry("Thinking updates",
                "Brief status while Orbit prepares an answer.",
                S_AI, "ai", KEY_THINKING_UPDATES,
                "thinking", "status", "progress", "updates"));
        out.add(new Entry("Sourced images in answers",
                "Show a useful sourced picture inside an answer when it helps.",
                S_AI, "ai", KEY_RICH_ANSWERS,
                "images", "pictures", "photos", "rich answers", "visual", "web images"));

        out.add(new Entry("Allow Orbit to read current-screen text",
                "Screen awareness for the Side-button assistant.",
                S_VOICE, "voice", KEY_SCREEN_TEXT,
                "screen", "context", "read screen", "awareness", "privacy"));
        out.add(new Entry("Allow Orbit to receive screenshots",
                "Send a picture of the screen with a request.",
                S_VOICE, "voice", KEY_SCREENSHOTS,
                "screenshot", "screen image", "capture", "privacy"));
        out.add(new Entry("Attach current screen by default",
                "Start each overlay request with the screen already attached.",
                S_VOICE, "voice", KEY_ATTACH_SCREEN,
                "attach", "screen", "default"));
        out.add(new Entry("Show contextual screen-action chips when attached",
                "Suggested actions for whatever is on screen.",
                S_VOICE, "voice", KEY_CONTEXT_CHIPS,
                "chips", "suggestions", "actions", "context"));
        out.add(new Entry("Speak replies to voice requests",
                "Read answers aloud when you asked by voice.",
                S_VOICE, "voice", KEY_SPEAK,
                "speak", "voice", "tts", "read aloud", "sound", "talk"));
        out.add(new Entry("Allow longer pauses while speaking",
                "Keep listening through a pause in a sentence.",
                S_VOICE, "voice", KEY_VOICE_PAUSE,
                "pause", "voice", "dictation", "listening"));
        out.add(new Entry("Start listening when overlay opens",
                "Begin voice input as soon as Orbit appears.",
                S_VOICE, "voice", KEY_AUTO_LISTEN,
                "listen", "microphone", "mic", "voice", "auto"));
        out.add(new Entry("Hands-free voice follow-ups",
                "Keep the conversation going without tapping.",
                S_VOICE, "voice", KEY_FOLLOW_UPS,
                "follow up", "hands free", "voice", "conversation"));
        out.add(new Entry("Smart follow-ups",
                "Offer a sensible next question after an answer.",
                S_VOICE, "voice", KEY_SMART_FOLLOW_UPS,
                "follow up", "suggestions", "smart"));
        out.add(new Entry("Keyboard-aware assistant invocation",
                "How Orbit opens while a keyboard is showing.",
                S_VOICE, "voice", KEY_KEYBOARD_AWARE,
                "keyboard", "ime", "invocation"));
        out.add(new Entry("Permissions & capabilities",
                "Microphone, contacts, camera, notifications and calendar access.",
                S_VOICE, "voice", KEY_PERMISSIONS,
                "permission", "microphone", "mic", "camera", "contacts", "calendar", "access"));

        out.add(new Entry("Orbit Memory",
                "What Orbit remembers about you between chats.",
                S_DATA, "data", KEY_MEMORY,
                "memory", "remember", "personalization", "facts", "forget"));
        out.add(new Entry("Orbit reminders",
                "Reminders Orbit has scheduled.",
                S_DATA, "data", KEY_REMINDERS,
                "reminder", "alarm", "notify", "schedule"));
        out.add(new Entry("Saved places",
                "Home, work and other places Orbit knows.",
                S_DATA, "data", KEY_PLACES,
                "places", "location", "home", "work", "address", "map"));
        out.add(new Entry("App profiles",
                "How Orbit behaves over each app.",
                S_DATA, "data", KEY_APP_PROFILES,
                "apps", "per app", "profile", "privacy"));
        out.add(new Entry("Notification intelligence",
                "What Orbit may read from your notifications.",
                S_DATA, "data", KEY_NOTIFICATIONS,
                "notifications", "alerts", "privacy", "retention"));
        out.add(new Entry("Orbit Vault",
                "The things you have chosen to keep.",
                S_DATA, "data", KEY_VAULT,
                "vault", "saved", "saved stuff", "keep", "collection", "clips", "bookmarks"));
        out.add(new Entry("Weather",
                "Default weather location and units.",
                S_DATA, "data", KEY_WEATHER,
                "weather", "temperature", "celsius", "fahrenheit", "units", "forecast"));
        out.add(new Entry("Gallery app",
                "Which app Orbit opens for photos.",
                S_DATA, "data", KEY_GALLERY,
                "gallery", "photos", "pictures", "album"));
        out.add(new Entry("Backup & restore",
                "Export or restore your Orbit data.",
                S_DATA, "data", KEY_BACKUP,
                "backup", "restore", "export", "import", "transfer", "copy"));

        out.add(new Entry("Orbit Deck",
                "Shortcuts to the things Orbit already does.",
                S_DECK, "deck", KEY_DECK,
                "deck", "shortcuts", "tiles", "suggestions"));

        out.add(new Entry("Start a new chat each time Orbit opens",
                "Whether the overlay resumes the last chat.",
                S_CHATS, "conversations", KEY_NEW_CHAT,
                "new chat", "resume", "continue", "overlay"));
        out.add(new Entry("Save recent chats on this device",
                "Keep conversation history locally.",
                S_CHATS, "conversations", KEY_HISTORY,
                "history", "chats", "save", "privacy"));
        out.add(new Entry("Save screen attachment thumbnails in chat history",
                "Keep the pictures attached to past turns.",
                S_CHATS, "conversations", KEY_THUMBNAILS,
                "thumbnails", "attachments", "screenshots", "storage"));
        out.add(new Entry("Show Stop button while replying",
                "Offer a Stop control during a reply.",
                S_CHATS, "conversations", KEY_STOP_BUTTON,
                "stop", "cancel", "interrupt"));
        out.add(new Entry("Clear Orbit conversation history",
                "Delete every stored chat on this device.",
                S_CHATS, "conversations", KEY_CLEAR_HISTORY,
                "clear", "delete chats", "erase", "history", "wipe"));

        out.add(new Entry("Theme Studio",
                "Accent, AMOLED, surfaces and bubble colors.",
                S_LOOK, "appearance", KEY_THEME_STUDIO,
                "amoled", "dark mode", "dark", "light", "theme", "accent", "color", "colour",
                "black", "appearance", "bubble", "nova", "palette"));
        out.add(new Entry("App font",
                "The typeface Orbit uses everywhere.",
                S_LOOK, "appearance", KEY_FONT,
                "font", "typeface", "typography", "text"));
        out.add(new Entry("Chat text size",
                "How large answers and messages are drawn.",
                S_LOOK, "appearance", KEY_CHAT_TEXT_SIZE,
                "text size", "font size", "bigger text", "larger", "small", "readable"));
        out.add(new Entry("Haptic feedback",
                "Whether Orbit vibrates on interactions.",
                S_LOOK, "appearance", KEY_HAPTICS,
                "haptics", "vibrate", "vibration", "feedback", "buzz"));
        out.add(new Entry("Page transitions",
                "How screens move when Orbit changes page.",
                S_LOOK, "appearance", KEY_PAGE_TRANSITIONS,
                "transition", "animation", "motion", "effects"));
        out.add(new Entry("Swipe to go back",
                "Interactive back gesture inside Orbit.",
                S_LOOK, "appearance", KEY_SWIPE_BACK,
                "swipe", "gesture", "back", "navigation"));
        out.add(new Entry("Chat swipe actions",
                "Swipe a chat in the list to act on it.",
                S_LOOK, "appearance", KEY_CHAT_SWIPE,
                "swipe", "gesture", "chats", "delete", "pin", "actions"));

        out.add(new Entry("Orbit diagnostics",
                "Local routing, context and troubleshooting information.",
                S_ADVANCED, "advanced", KEY_DIAGNOSTICS,
                "diagnostics", "debug", "troubleshoot", "logs", "developer", "astra", "model"));

        // Three destinations that are whole screens rather than rows. They carry no control key,
        // because there is no row to pulse: choosing one opens the screen it names, which is the
        // exact place the user was heading.
        out.add(new Entry("Routines",
                "Saved multi-step Routines, triggers and Custom Commands.",
                S_ROUTINES, "routines", "",
                "routine", "automation", "trigger", "custom command", "schedule"));
        out.add(new Entry("Extensions",
                "Declarative Orbit extensions and their setup.",
                S_EXTENSIONS, "extensions", "",
                "extension", "orbitext", "integration", "plugin", "api"));
        out.add(new Entry("About & updates",
                "Version, update channel and release notes.",
                S_UPDATES, "updates", "",
                "update", "version", "beta", "stable", "channel", "release notes", "changelog",
                "about", "upgrade"));

        return Collections.unmodifiableList(out);
    }
}
