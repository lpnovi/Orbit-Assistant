package com.orbit.assistant;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

/**
 * What one stored tile should currently say, show, and be announced as.
 *
 * <p>All of the "is this still true?" work lives here rather than in the view. A Routine tile is
 * only a name until somebody checks the Routine still exists; an App tile is only a package name
 * until somebody asks whether it is still installed. Doing that in one place is what lets
 * {@link DeckTileView} stay a view and lets the same answers drive the grid, the editor and
 * TalkBack without three versions of the truth.
 *
 * <p>Nothing here ever removes a tile. A Routine the user deleted becomes
 * {@link DeckTile.Availability#UNRESOLVED} and stays on the Deck, because a tile vanishing on its
 * own is indistinguishable from Orbit losing the user's layout.
 */
public final class DeckTileResolver {

    /** Live device state a tile may reflect, computed off the UI thread and possibly unknown. */
    public static final class LiveState {
        /** Torch on, off, or null when Orbit could not read it. Never guessed. */
        public final Boolean flashlightOn;
        /** Media playing, paused, or null when there is no readable session. */
        public final Boolean mediaPlaying;
        /** The app that owns the readable media session, empty when there is none. */
        public final String mediaApp;

        public LiveState(Boolean flashlightOn, Boolean mediaPlaying, String mediaApp) {
            this.flashlightOn = flashlightOn;
            this.mediaPlaying = mediaPlaying;
            this.mediaApp = mediaApp == null ? "" : mediaApp;
        }

        public static LiveState unknown() { return new LiveState(null, null, ""); }
    }

    /** One tile's current presentation. Immutable, and safe to hand straight to a view. */
    public static final class Resolved {
        public final String title;
        /** Secondary line. Shown on a wide tile, or on any tile carrying live state. */
        public final String subtitle;
        public final int iconRes;
        /** Set only for an app shortcut, where the identity is the app's own icon. */
        public final Drawable appIcon;
        public final DeckTile.Availability availability;
        public final String contentDescription;
        /**
         * Whether the secondary line is worth showing on a standard tile too.
         *
         * <p>True for live state ("On", "Playing in Spotify") and for the reason an unavailable
         * tile cannot run — things a person needs to see at whatever size the tile happens to be.
         * False for descriptive metadata like "5 actions · Routine", which is what the wide size
         * exists to reveal and would only crowd a small tile.
         */
        public final boolean liveState;

        Resolved(String title, String subtitle, int iconRes, Drawable appIcon,
                 DeckTile.Availability availability, String contentDescription) {
            this(title, subtitle, iconRes, appIcon, availability, contentDescription, false);
        }

        Resolved(String title, String subtitle, int iconRes, Drawable appIcon,
                 DeckTile.Availability availability, String contentDescription, boolean liveState) {
            this.title = title;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.iconRes = iconRes;
            this.appIcon = appIcon;
            this.availability = availability;
            this.contentDescription = contentDescription;
            // An unusable tile always explains itself, whatever size it is.
            this.liveState = liveState || availability != DeckTile.Availability.AVAILABLE;
        }

        public boolean usable() { return availability == DeckTile.Availability.AVAILABLE; }
    }

    private DeckTileResolver() {}

    public static Resolved resolve(Context c, DeckTile tile) {
        return resolve(c, tile, LiveState.unknown());
    }

    public static Resolved resolve(Context c, DeckTile tile, LiveState live) {
        if (tile == null) return unresolved("Unavailable tile", "", R.drawable.ic_deck);
        if (live == null) live = LiveState.unknown();

        DeckTileRegistry.Definition definition = DeckTileRegistry.definition(tile.type);
        // A type this build has never heard of. Kept, shown honestly, and removable.
        if (definition == null) {
            return unresolved("Unavailable tile", "Not supported in this version", R.drawable.ic_deck);
        }

        if (DeckTileRegistry.TYPE_ROUTINE.equals(tile.type)) return routine(c, tile, definition);
        if (DeckTileRegistry.TYPE_APP.equals(tile.type)) return app(c, tile, definition);
        if (DeckTileRegistry.TYPE_PROMPT.equals(tile.type)) return prompt(tile, definition);
        if (DeckTileRegistry.TYPE_FLASHLIGHT.equals(tile.type)) return flashlight(c, tile, definition, live);
        if (DeckTileRegistry.TYPE_MEDIA.equals(tile.type)) return media(tile, definition, live);
        return destination(tile, definition);
    }

    // ---- kinds ------------------------------------------------------------------------------------

    private static Resolved destination(DeckTile tile, DeckTileRegistry.Definition definition) {
        String title = title(tile, definition.title);
        return new Resolved(title, definition.description, definition.iconRes, null,
                DeckTile.Availability.AVAILABLE, describe(title, definition.roleLabel, ""));
    }

    private static Resolved routine(Context c, DeckTile tile, DeckTileRegistry.Definition definition) {
        String routineId = tile.config(DeckTile.CONFIG_ROUTINE_ID);
        RoutineStore.Routine routine = routineId.isEmpty() ? null : RoutineStore.findById(c, routineId);
        if (routine == null) {
            String named = title(tile, "Routine");
            return new Resolved(named, "Routine deleted", definition.iconRes, null,
                    DeckTile.Availability.UNRESOLVED,
                    named + ", Routine, no longer available. Tap to choose another or remove it.");
        }
        // The Routine's own name wins over the stored one: renaming a Routine should rename its
        // tile, because the tile is a pointer to it rather than a copy of it.
        String title = tile.hasConfig(DeckTile.CONFIG_TITLE)
                ? tile.config(DeckTile.CONFIG_TITLE) : routine.name;
        int steps = routine.actions == null ? 0 : routine.actions.size();
        String subtitle = steps == 1 ? "1 action · Routine" : steps + " actions · Routine";
        return new Resolved(title, subtitle, definition.iconRes, null,
                DeckTile.Availability.AVAILABLE, describe(title, definition.roleLabel, ""));
    }

    private static Resolved app(Context c, DeckTile tile, DeckTileRegistry.Definition definition) {
        String packageName = tile.config(DeckTile.CONFIG_PACKAGE);
        String stored = title(tile, "App");
        if (packageName.isEmpty() || c == null) {
            return new Resolved(stored, "Not set up", definition.iconRes, null,
                    DeckTile.Availability.UNRESOLVED,
                    stored + ", app shortcut, not set up. Tap to choose an app.");
        }
        PackageManager pm = c.getPackageManager();
        try {
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            // Installed is not the same as launchable: a package with no launcher entry cannot be
            // opened, so it is unresolved rather than available.
            if (pm.getLaunchIntentForPackage(packageName) == null) {
                return new Resolved(stored, "Cannot be opened", definition.iconRes, null,
                        DeckTile.Availability.UNRESOLVED,
                        stored + ", app shortcut, cannot be opened. Tap to choose another or remove it.");
            }
            String label = String.valueOf(pm.getApplicationLabel(info));
            String title = tile.hasConfig(DeckTile.CONFIG_TITLE)
                    ? tile.config(DeckTile.CONFIG_TITLE) : label;
            Drawable icon = null;
            try { icon = pm.getApplicationIcon(info); } catch (Exception ignored) {}
            return new Resolved(title, "App shortcut", definition.iconRes, icon,
                    DeckTile.Availability.AVAILABLE, describe(title, definition.roleLabel, ""));
        } catch (PackageManager.NameNotFoundException e) {
            // Uninstalled. The package name stays stored, so reinstalling restores the tile.
            return new Resolved(stored, "App not installed", definition.iconRes, null,
                    DeckTile.Availability.UNRESOLVED,
                    stored + ", app shortcut, app not installed. Tap to choose another or remove it.");
        } catch (Exception e) {
            return new Resolved(stored, "Unavailable", definition.iconRes, null,
                    DeckTile.Availability.UNRESOLVED, stored + ", app shortcut, unavailable.");
        }
    }

    private static Resolved prompt(DeckTile tile, DeckTileRegistry.Definition definition) {
        String text = tile.config(DeckTile.CONFIG_PROMPT);
        String title = title(tile, "Prompt");
        int icon = DeckIcons.resFor(tile.config(DeckTile.CONFIG_ICON));
        if (text.isEmpty()) {
            return new Resolved(title, "No prompt yet", icon, null,
                    DeckTile.Availability.UNRESOLVED,
                    title + ", prompt shortcut, not set up. Tap to write its prompt.");
        }
        // The prompt itself is the tile's secondary line, trimmed to one glanceable phrase. It is
        // shown here and nowhere else: it never reaches Diagnostics or any provider on its own.
        return new Resolved(title, preview(text), icon, null,
                DeckTile.Availability.AVAILABLE, describe(title, definition.roleLabel, ""));
    }

    private static Resolved flashlight(Context c, DeckTile tile,
                                       DeckTileRegistry.Definition definition, LiveState live) {
        String title = title(tile, definition.title);
        boolean hasFlash = c != null && c.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH);
        if (!hasFlash) {
            return new Resolved(title, "Not on this device", definition.iconRes, null,
                    DeckTile.Availability.UNAVAILABLE,
                    title + ", action, not available on this device.");
        }
        // Null means Orbit could not read the torch. The tile still works; it simply does not
        // claim a state it does not know, which is why this falls back to the description.
        String state = live.flashlightOn == null ? definition.description
                : (live.flashlightOn ? "On" : "Off");
        return new Resolved(title, state, definition.iconRes, null,
                DeckTile.Availability.AVAILABLE,
                describe(title, definition.roleLabel, live.flashlightOn == null ? "" : state),
                live.flashlightOn != null);
    }

    private static Resolved media(DeckTile tile, DeckTileRegistry.Definition definition,
                                  LiveState live) {
        String title = title(tile, definition.title);
        String state;
        String spoken;
        if (live.mediaPlaying == null) {
            // No readable session: a neutral tile rather than an invented "Paused".
            state = definition.description;
            spoken = "";
        } else {
            String app = live.mediaApp.isEmpty() ? "" : " · " + live.mediaApp;
            state = (live.mediaPlaying ? "Playing" : "Paused") + app;
            spoken = state;
        }
        return new Resolved(title, state, definition.iconRes, null,
                DeckTile.Availability.AVAILABLE, describe(title, definition.roleLabel, spoken),
                live.mediaPlaying != null);
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private static Resolved unresolved(String title, String subtitle, int icon) {
        return new Resolved(title, subtitle, icon, null, DeckTile.Availability.UNRESOLVED,
                title + ", unavailable. Tap to remove it.");
    }

    private static String title(DeckTile tile, String fallback) {
        String custom = tile.config(DeckTile.CONFIG_TITLE);
        return custom.isEmpty() ? fallback : custom;
    }

    /**
     * One node, one sentence.
     *
     * <p>A tile is a single control, so TalkBack should meet it as "Goodnight, Routine" rather than
     * walking an icon, a title and a state line as three unrelated things. The icon is marked
     * decorative in the view for the same reason: it identifies the same tile the name already did.
     */
    private static String describe(String title, String role, String state) {
        StringBuilder out = new StringBuilder(title);
        if (role != null && !role.isEmpty()) out.append(", ").append(role);
        if (state != null && !state.isEmpty()) out.append(", ").append(state);
        return out.toString();
    }

    private static String preview(String text) {
        String flat = text.replace('\n', ' ').replace('\r', ' ').trim();
        while (flat.contains("  ")) flat = flat.replace("  ", " ");
        return flat.length() <= 42 ? flat : flat.substring(0, 41).trim() + "…";
    }

    /** The launch intent an app tile would use, or null when there is not one. */
    static Intent launchIntent(Context c, String packageName) {
        if (c == null || packageName == null || packageName.trim().isEmpty()) return null;
        try {
            return c.getPackageManager().getLaunchIntentForPackage(packageName.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
