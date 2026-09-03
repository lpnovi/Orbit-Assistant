package com.orbit.assistant;

import android.content.Context;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.List;

/**
 * The small Suggested row, worked out locally and deterministically.
 *
 * <p>Opening Deck asks no provider anything. Everything here is read from state Orbit already
 * holds — a live media session, the app Orbit was last invoked over, when a Routine was last run —
 * so the section costs one cheap local read and never a token of AI usage. There is no model, no
 * network call, no background service and no new permission behind any of it.
 *
 * <h2>Why these three and not "Summarize screen"</h2>
 *
 * <p>Orbit's existing contextual actions ({@code ScreenActionSuggester}) are all phrased about the
 * screen the overlay had captured — "summarize the conversation on my screen". They are correct
 * there because the screen is attached to the request. Deck runs in the full app, where that screen
 * is gone and cannot be re-attached, so offering them here would produce a question about content
 * Orbit no longer has. They are deliberately not reused for that reason.
 *
 * <p>What is left is the set of suggestions whose input is still genuinely available at the moment
 * the user taps: media that is playing right now, an app that is still installed, a Routine that
 * still exists. Each one is expressed as an ordinary {@link DeckTile}, so it runs through
 * {@link DeckActionExecutor} exactly as a permanent tile would and needs no second execution path.
 *
 * <p>Suggestions never touch My Deck. They are recomputed on each open, they are capped, and one is
 * suppressed entirely when the equivalent tile is already on the Deck, so the section can never
 * become a second copy of what the user already arranged.
 */
public final class DeckSuggestionEngine {

    /**
     * How recent Orbit's last screen context must be to say anything about it.
     *
     * <p>Fifteen minutes, chosen to be conservative rather than clever. The signal is only "which
     * app the user had open when they last asked Orbit something", and past this it says more about
     * yesterday than about now.
     */
    public static final long CONTEXT_FRESHNESS_MS = 15L * 60L * 1000L;

    /** How recently a Routine must have run to be worth offering again. */
    public static final long ROUTINE_RECENCY_MS = 24L * 60L * 60L * 1000L;

    /** Phone cap. Small on purpose: Suggested is a hint, not a feed. */
    public static final int MAX_PHONE = 2;
    /** Wider layouts have room for one more without the row becoming a list. */
    public static final int MAX_WIDE = 3;

    /** One offered shortcut. The tile is what runs; it is never persisted unless the user says so. */
    public static final class Suggestion {
        /** Stable within a build, so behaviour can be asserted without matching display text. */
        public final String id;
        public final String title;
        public final String subtitle;
        public final int iconRes;
        public final String contentDescription;
        /** The tile this suggestion runs when tapped. */
        public final DeckTile action;
        /** The tile "Add to Deck" would place, or null when it does not map to a permanent one. */
        public final DeckTile addable;

        Suggestion(String id, String title, String subtitle, int iconRes,
                   DeckTile action, DeckTile addable) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.iconRes = iconRes;
            this.action = action;
            this.addable = addable;
            this.contentDescription = title
                    + (this.subtitle.isEmpty() ? "" : ", " + this.subtitle) + ", suggested";
        }
    }

    private DeckSuggestionEngine() {}

    /**
     * What to offer right now.
     *
     * <p>Returns an empty list — meaning "draw no section at all" — whenever there is nothing
     * genuinely useful. There is deliberately no filler and no "No suggestions" state.
     */
    public static List<Suggestion> suggestions(Context c, DeckTileResolver.LiveState live,
                                               List<DeckTile> deck, int max, long now) {
        List<Suggestion> out = new ArrayList<>();
        if (c == null || !Prefs.deckSuggestions(c)) return out;
        if (live == null) live = DeckTileResolver.LiveState.unknown();

        addMedia(out, live, deck);
        addRecentApp(out, c, deck, now);
        addRecentRoutine(out, c, deck, now);

        int cap = Math.max(0, max);
        while (out.size() > cap) out.remove(out.size() - 1);
        return out;
    }

    /** The cap for a layout of this many grid columns. */
    public static int maxFor(int columns) {
        return columns >= 3 ? MAX_WIDE : MAX_PHONE;
    }

    // ---- sources ----------------------------------------------------------------------------------

    /**
     * Media, but only when something is genuinely playing.
     *
     * <p>A paused session is not offered: the user did not just do anything, and a Play tile that
     * resumes something they stopped an hour ago is noise. Playing right now is the one moment this
     * is the most useful control on the screen.
     */
    private static void addMedia(List<Suggestion> out, DeckTileResolver.LiveState live,
                                 List<DeckTile> deck) {
        if (!Boolean.TRUE.equals(live.mediaPlaying)) return;
        if (contains(deck, DeckTileRegistry.TYPE_MEDIA)) return;
        DeckTile tile = DeckTile.of(DeckTileRegistry.TYPE_MEDIA, DeckTile.Size.STANDARD);
        out.add(new Suggestion("media", "Pause",
                live.mediaApp.isEmpty() ? "Playing now" : "Playing in " + live.mediaApp,
                R.drawable.ic_deck_media, tile, tile));
    }

    /**
     * The app Orbit was last invoked over.
     *
     * <p>Three gates, all of which must pass. The record has to be fresh; it must not be Orbit
     * itself, because opening the full app makes Orbit the foreground app and classifying its own
     * Deck would produce a suggestion about nothing; and the app has to still be launchable, so a
     * suggestion can never point at something uninstalled.
     */
    private static void addRecentApp(List<Suggestion> out, Context c, List<DeckTile> deck, long now) {
        String packageName = DiagnosticStore.lastForegroundPackage(c);
        if (packageName == null || packageName.trim().isEmpty()) return;
        if (packageName.equals(c.getPackageName())) return;

        long updated = DiagnosticStore.lastScreenUpdatedAt(c);
        if (updated <= 0L || now - updated > CONTEXT_FRESHNESS_MS) return;

        if (containsApp(deck, packageName)) return;
        if (DeckTileResolver.launchIntent(c, packageName) == null) return;

        String label = packageName;
        try {
            PackageManager pm = c.getPackageManager();
            label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)));
        } catch (Exception ignored) {}

        DeckTile tile = DeckTile.of(DeckTileRegistry.TYPE_APP, DeckTile.Size.STANDARD)
                .withConfig(DeckTile.CONFIG_PACKAGE, packageName);
        out.add(new Suggestion("recent_app", label, "Recently used",
                R.drawable.ic_deck_app, tile, tile));
    }

    /** The Routine the user ran most recently, if it was recent enough to still be on their mind. */
    private static void addRecentRoutine(List<Suggestion> out, Context c,
                                         List<DeckTile> deck, long now) {
        RoutineStore.Routine best = null;
        for (RoutineStore.Routine routine : RoutineStore.list(c)) {
            if (routine == null || routine.lastRunAt <= 0L) continue;
            if (now - routine.lastRunAt > ROUTINE_RECENCY_MS) continue;
            if (containsRoutine(deck, routine.id)) continue;
            if (best == null || routine.lastRunAt > best.lastRunAt) best = routine;
        }
        if (best == null) return;
        DeckTile tile = DeckTile.of(DeckTileRegistry.TYPE_ROUTINE, DeckTile.Size.STANDARD)
                .withConfig(DeckTile.CONFIG_ROUTINE_ID, best.id);
        out.add(new Suggestion("recent_routine", best.name, "Ran recently",
                R.drawable.ic_routine_tile, tile, tile));
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private static boolean contains(List<DeckTile> deck, String type) {
        if (deck == null) return false;
        for (DeckTile tile : deck) if (type.equals(tile.type)) return true;
        return false;
    }

    private static boolean containsApp(List<DeckTile> deck, String packageName) {
        if (deck == null) return false;
        for (DeckTile tile : deck) {
            if (DeckTileRegistry.TYPE_APP.equals(tile.type)
                    && packageName.equals(tile.config(DeckTile.CONFIG_PACKAGE))) return true;
        }
        return false;
    }

    private static boolean containsRoutine(List<DeckTile> deck, String routineId) {
        if (deck == null) return false;
        for (DeckTile tile : deck) {
            if (DeckTileRegistry.TYPE_ROUTINE.equals(tile.type)
                    && routineId.equals(tile.config(DeckTile.CONFIG_ROUTINE_ID))) return true;
        }
        return false;
    }
}
