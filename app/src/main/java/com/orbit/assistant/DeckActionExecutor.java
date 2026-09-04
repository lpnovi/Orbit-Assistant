package com.orbit.assistant;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The one place a Deck tile turns into something happening.
 *
 * <p>Every branch here delegates. Routines go to {@code OrbitWidgetExecutor.runRoutine}, which is
 * the same runner the Routine widget and the Quick Settings tile already use, so confirmations,
 * permission hand-offs, the Action Engine and run history all behave exactly as they do everywhere
 * else. The flashlight goes to the same executor's torch path. Media goes to {@link MediaControl}.
 * Conversations go through {@link ChatActivity}'s existing composer extras. Deck adds no second
 * implementation of anything, which is the point: a tile is a shortcut to Orbit's own behaviour,
 * not a reimplementation of it that can drift away from the original.
 *
 * <p>{@link DeckTileView} never contains any of this. It reports that it was tapped and this decides
 * what that means.
 */
public final class DeckActionExecutor {

    /** What happened, in the small amount of detail a tile needs to react. */
    public static final class Outcome {
        public final boolean success;
        /** Something short to show, or empty when the action speaks for itself by navigating. */
        public final String message;
        /** True when the tile cannot run until the user configures it. */
        public final boolean needsConfiguration;
        /** True when live state may have changed and the Deck should re-read it. */
        public final boolean stateChanged;

        Outcome(boolean success, String message, boolean needsConfiguration, boolean stateChanged) {
            this.success = success;
            this.message = message == null ? "" : message;
            this.needsConfiguration = needsConfiguration;
            this.stateChanged = stateChanged;
        }

        static Outcome navigated() { return new Outcome(true, "", false, false); }
        static Outcome ok(String message) { return new Outcome(true, message, false, true); }
        static Outcome failed(String message) { return new Outcome(false, message, false, false); }
        static Outcome configure() { return new Outcome(false, "", true, false); }
    }

    public interface Callback { void onOutcome(Outcome outcome); }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private DeckActionExecutor() {}

    /**
     * Runs one tile.
     *
     * <p>The callback always arrives on the main thread, and always arrives exactly once.
     */
    public static void execute(Activity activity, DeckTile tile, Callback callback) {
        if (activity == null || tile == null) {
            finish(callback, Outcome.failed("Orbit could not run this tile."));
            return;
        }

        DeckTileResolver.Resolved resolved = DeckTileResolver.resolve(activity, tile);
        // An unresolved tile is never run. Its Routine is gone, its app is uninstalled, or this
        // build does not know its type, so the useful response is to offer to fix it.
        if (resolved.availability == DeckTile.Availability.UNRESOLVED) {
            finish(callback, Outcome.configure());
            return;
        }
        if (resolved.availability == DeckTile.Availability.UNAVAILABLE) {
            finish(callback, Outcome.failed(resolved.subtitle.isEmpty()
                    ? "That is not available on this device." : resolved.subtitle));
            return;
        }

        DiagnosticStore.recordDeckAction(activity, tile.type);
        String type = tile.type;

        if (DeckTileRegistry.TYPE_NEW_CHAT.equals(type)) {
            // The canonical new-chat path, the same one the Chats button uses.
            open(activity, new Intent(activity, ChatActivity.class)
                    .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, ConversationStore.newId())
                    .putExtra(ChatActivity.EXTRA_FOCUS_COMPOSER, true), callback);
            return;
        }
        if (DeckTileRegistry.TYPE_PROMPT.equals(type)) {
            runPrompt(activity, tile, callback);
            return;
        }
        if (DeckTileRegistry.TYPE_ROUTINES.equals(type)) {
            open(activity, new Intent(activity, RoutinesActivity.class), callback); return;
        }
        if (DeckTileRegistry.TYPE_REMINDERS.equals(type)) {
            open(activity, new Intent(activity, RemindersActivity.class), callback); return;
        }
        if (DeckTileRegistry.TYPE_MEMORIES.equals(type)) {
            open(activity, new Intent(activity, MemoryActivity.class), callback); return;
        }
        if (DeckTileRegistry.TYPE_CAPABILITIES.equals(type)) {
            open(activity, new Intent(activity, CapabilitiesActivity.class), callback); return;
        }
        if (DeckTileRegistry.TYPE_EXTENSIONS.equals(type)) {
            open(activity, new Intent(activity, ExtensionsActivity.class), callback); return;
        }
        if (DeckTileRegistry.TYPE_THEME_STUDIO.equals(type)) {
            open(activity, new Intent(activity, ThemeStudioActivity.class), callback); return;
        }
        if (DeckTileRegistry.TYPE_SETTINGS.equals(type)) {
            open(activity, new Intent(activity, SettingsActivity.class), callback); return;
        }
        if (DeckTileRegistry.TYPE_APP.equals(type)) {
            runApp(activity, tile, callback); return;
        }
        if (DeckTileRegistry.TYPE_ROUTINE.equals(type)) {
            runRoutine(activity, tile, callback); return;
        }
        if (DeckTileRegistry.TYPE_FLASHLIGHT.equals(type)) {
            runFlashlight(activity, callback); return;
        }
        if (DeckTileRegistry.TYPE_MEDIA.equals(type)) {
            runMedia(activity, callback); return;
        }
        finish(callback, Outcome.failed("Orbit does not know how to run that tile."));
    }

    // ---- kinds ------------------------------------------------------------------------------------

    /**
     * Opens a conversation with the tile's text waiting in the composer.
     *
     * <p>It is put in the composer and left there. Deck never sends it, never asks a provider
     * anything, and never consumes any AI usage: the user reads what they are about to ask, edits
     * it if they want to, and presses send themselves.
     */
    private static void runPrompt(Activity activity, DeckTile tile, Callback callback) {
        String prompt = tile.config(DeckTile.CONFIG_PROMPT);
        if (prompt.isEmpty()) { finish(callback, Outcome.configure()); return; }
        open(activity, new Intent(activity, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, ConversationStore.newId())
                .putExtra(ChatActivity.EXTRA_INITIAL_DRAFT, prompt)
                .putExtra(ChatActivity.EXTRA_FOCUS_COMPOSER, true), callback);
    }

    private static void runApp(Activity activity, DeckTile tile, Callback callback) {
        Intent intent = DeckTileResolver.launchIntent(activity, tile.config(DeckTile.CONFIG_PACKAGE));
        // Always the launcher's own resolved intent, never one Deck assembles from a stored string.
        if (intent == null) { finish(callback, Outcome.configure()); return; }
        open(activity, intent, callback);
    }

    private static void runRoutine(Activity activity, DeckTile tile, Callback callback) {
        RoutineStore.Routine routine =
                RoutineStore.findById(activity, tile.config(DeckTile.CONFIG_ROUTINE_ID));
        if (routine == null) { finish(callback, Outcome.configure()); return; }
        String name = routine.name;
        // Straight to the shared runner. A step needing confirmation still opens the Routine
        // runner, and a permission failure still routes to the same place it always did.
        OrbitWidgetExecutor.runRoutine(activity, routine,
                () -> finish(callback, Outcome.ok("Ran " + name)));
    }

    private static void runFlashlight(Activity activity, Callback callback) {
        OrbitWidgetExecutor.toggleFlashlight(activity, result -> {
            if (result == null) { finish(callback, Outcome.failed("Orbit could not do that.")); return; }
            finish(callback, result.success
                    ? Outcome.ok(result.message)
                    : Outcome.failed(result.message));
        });
    }

    private static void runMedia(Activity activity, Callback callback) {
        Context app = activity.getApplicationContext();
        // MediaControl reads the playback state back to confirm what happened, which takes a few
        // hundred milliseconds. That never runs on the UI thread.
        EXEC.execute(() -> {
            DeviceActionExecutor.Result result;
            try {
                result = MediaControl.execute(app, MediaControl.Command.PLAY_PAUSE);
            } catch (Exception e) {
                result = DeviceActionExecutor.Result.failed("Orbit could not reach media playback.");
            }
            DeviceActionExecutor.Result finalResult = result;
            finish(callback, finalResult != null && finalResult.success
                    ? Outcome.ok(finalResult.message)
                    : Outcome.failed(finalResult == null
                            ? "Orbit could not reach media playback." : finalResult.message));
        });
    }

    // ---- plumbing ---------------------------------------------------------------------------------

    private static void open(Activity activity, Intent intent, Callback callback) {
        try {
            activity.startActivity(intent);
            UiKit.applyPageTransition(activity);
            finish(callback, Outcome.navigated());
        } catch (Exception e) {
            finish(callback, Outcome.failed("Orbit could not open that."));
        }
    }

    private static void finish(Callback callback, Outcome outcome) {
        if (callback == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) callback.onOutcome(outcome);
        else MAIN.post(() -> callback.onOutcome(outcome));
    }
}
