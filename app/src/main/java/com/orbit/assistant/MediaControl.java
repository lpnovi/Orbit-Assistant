package com.orbit.assistant;

import android.content.ComponentName;
import android.content.Context;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.view.KeyEvent;

import java.util.List;
import java.util.Locale;

/**
 * Play, pause, and skip, against whatever is actually playing.
 *
 * <p>Deliberately not aimed at any one music app. Android's media session is the supported way for
 * an assistant to reach playback, and every app that participates properly — Spotify, YouTube
 * Music, Samsung's own player, a podcast app, a browser tab — appears through the same interface.
 * Orbit therefore controls "the thing that is playing" rather than a package it was told about.
 *
 * <h2>Why the answers differ</h2>
 *
 * <p>Orbit already holds notification access for its notification history, and that same access is
 * what {@code MediaSessionManager.getActiveSessions} requires. With it, Orbit can see the session,
 * send the command <em>and read the playback state back</em>, so it can say what happened. Without
 * it, all Orbit can do is broadcast a media key, which usually works and cannot be observed at all
 * — so the wording says exactly that instead of reporting a success nobody checked. The one thing
 * this class will never do is claim a track was skipped because a method call did not throw.
 */
public final class MediaControl {
    private MediaControl() {}

    /** How long Orbit will wait for the player to actually change. Bounded on purpose. */
    private static final long CONFIRM_WINDOW_MS = 300L;
    private static final long CONFIRM_STEP_MS = 40L;

    /** The transport commands Orbit exposes. Nothing here can seek, queue, or change an app. */
    public enum Command { PLAY, PAUSE, PLAY_PAUSE, NEXT, PREVIOUS }

    /** The command written as {@code params.command}, or null when it is not one Orbit has. */
    public static Command parse(String raw) {
        if (raw == null) return null;
        switch (raw.trim().toUpperCase(Locale.US)) {
            case "PLAY": case "RESUME": return Command.PLAY;
            case "PAUSE": return Command.PAUSE;
            case "PLAY_PAUSE": case "TOGGLE": return Command.PLAY_PAUSE;
            case "NEXT": case "NEXT_TRACK": case "SKIP": return Command.NEXT;
            case "PREVIOUS": case "PREVIOUS_TRACK": case "BACK": return Command.PREVIOUS;
            default: return null;
        }
    }

    // ---- execution ---------------------------------------------------------------------------------

    /** Runs one transport command and reports what Orbit could actually observe. */
    public static DeviceActionExecutor.Result execute(Context c, Command command) {
        if (c == null || command == null) return DeviceActionExecutor.Result.failed("No media command");

        MediaController controller = activeController(c);
        if (controller == null) {
            if (!NotificationAccess.enabled(c)) return dispatchKey(c, command);
            // Orbit can see every session there is, and there are none.
            return DeviceActionExecutor.Result.unavailable("Nothing is playing right now.");
        }

        String app = appLabel(c, controller.getPackageName());
        try {
            MediaController.TransportControls controls = controller.getTransportControls();
            switch (command) {
                case PLAY: controls.play(); break;
                case PAUSE: controls.pause(); break;
                case PLAY_PAUSE:
                    if (isPlaying(controller)) controls.pause(); else controls.play();
                    break;
                case NEXT: controls.skipToNext(); break;
                case PREVIOUS: controls.skipToPrevious(); break;
                default: return DeviceActionExecutor.Result.failed("No media command");
            }
        } catch (SecurityException e) {
            return DeviceActionExecutor.Result.permission(
                    "Android would not let Orbit control " + app + ".");
        } catch (Exception e) {
            return DeviceActionExecutor.Result.failed("Could not reach " + app + ".");
        }

        return confirm(controller, command, app);
    }

    /**
     * Waits, briefly, for the player to agree that something happened.
     *
     * <p>Play and pause are confirmed from the playback state. A skip is confirmed from the track
     * identity changing, because a session that is already playing stays playing through a skip and
     * the state alone would prove nothing. Either way, an unconfirmed command is reported as sent,
     * never as done.
     */
    private static DeviceActionExecutor.Result confirm(MediaController controller,
                                                       Command command, String app) {
        boolean wantPlaying = command == Command.PLAY
                || (command == Command.PLAY_PAUSE && !isPlaying(controller));
        String before = trackIdentity(controller);

        long deadline = System.currentTimeMillis() + CONFIRM_WINDOW_MS;
        while (System.currentTimeMillis() < deadline) {
            switch (command) {
                case PLAY:
                    if (isPlaying(controller)) return DeviceActionExecutor.Result.success("Playing in " + app);
                    break;
                case PAUSE:
                    if (!isPlaying(controller)) return DeviceActionExecutor.Result.success("Paused " + app);
                    break;
                case PLAY_PAUSE:
                    if (isPlaying(controller) == wantPlaying) {
                        return DeviceActionExecutor.Result.success(
                                wantPlaying ? "Playing in " + app : "Paused " + app);
                    }
                    break;
                case NEXT:
                case PREVIOUS: {
                    String now = trackIdentity(controller);
                    if (!now.isEmpty() && !now.equals(before)) {
                        return DeviceActionExecutor.Result.success(
                                (command == Command.NEXT ? "Skipped to the next track in "
                                        : "Went back a track in ") + app);
                    }
                    break;
                }
                default:
                    break;
            }
            try { Thread.sleep(CONFIRM_STEP_MS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        // Sent, and not observed. That is what Orbit says.
        return DeviceActionExecutor.Result.success(sentWording(command) + " to " + app
                + ", but it did not report the change back.");
    }

    private static String sentWording(Command command) {
        switch (command) {
            case PLAY: return "Sent play";
            case PAUSE: return "Sent pause";
            case PLAY_PAUSE: return "Sent play/pause";
            case NEXT: return "Sent next track";
            default: return "Sent previous track";
        }
    }

    // ---- the fallback path ---------------------------------------------------------------------------

    /**
     * A media key, for a phone where Orbit has no notification access.
     *
     * <p>This is how the hardware buttons on a headset reach a player, and it works. It is also
     * completely unobservable — Android returns nothing about what received it — so the sentence
     * Orbit produces claims only that the command was sent, and names the one setting that would
     * let it say more.
     */
    private static DeviceActionExecutor.Result dispatchKey(Context c, Command command) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return DeviceActionExecutor.Result.unavailable("Audio service unavailable");
        int code = keyCode(command);
        try {
            long now = android.os.SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
        } catch (Exception e) {
            return DeviceActionExecutor.Result.failed("Could not send the media command.");
        }
        return DeviceActionExecutor.Result.success(sentWording(command)
                + " to whatever is playing. Orbit cannot confirm it without notification access.");
    }

    static int keyCode(Command command) {
        switch (command) {
            case PLAY: return KeyEvent.KEYCODE_MEDIA_PLAY;
            case PAUSE: return KeyEvent.KEYCODE_MEDIA_PAUSE;
            case NEXT: return KeyEvent.KEYCODE_MEDIA_NEXT;
            case PREVIOUS: return KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            default: return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        }
    }

    // ---- sessions --------------------------------------------------------------------------------------

    /**
     * The session a command should go to: whatever is playing, or the most recent one.
     *
     * <p>Android returns active sessions in priority order, so the first is the one the system
     * itself would route a headset button to. Orbit prefers a session that is genuinely playing, so
     * "pause" reaches the thing making noise rather than a paused video in another app.
     */
    static MediaController activeController(Context c) {
        if (!NotificationAccess.enabled(c)) return null;
        try {
            MediaSessionManager manager =
                    (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (manager == null) return null;
            List<MediaController> controllers = manager.getActiveSessions(
                    new ComponentName(c, OrbitNotificationListenerService.class));
            if (controllers == null || controllers.isEmpty()) return null;
            for (MediaController controller : controllers) {
                if (isPlaying(controller)) return controller;
            }
            return controllers.get(0);
        } catch (SecurityException e) {
            // Notification access was revoked between the check and the call.
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    static boolean isPlaying(MediaController controller) {
        if (controller == null) return false;
        PlaybackState state = controller.getPlaybackState();
        if (state == null) return false;
        int value = state.getState();
        return value == PlaybackState.STATE_PLAYING
                || value == PlaybackState.STATE_BUFFERING
                || value == PlaybackState.STATE_FAST_FORWARDING
                || value == PlaybackState.STATE_REWINDING;
    }

    /**
     * Something that changes when the track does.
     *
     * <p>Never shown to the user and never stored: it exists only to compare one moment with the
     * next, so a skip can be confirmed rather than assumed.
     */
    private static String trackIdentity(MediaController controller) {
        try {
            MediaMetadata metadata = controller.getMetadata();
            if (metadata == null) return "";
            String id = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            if (id != null && !id.isEmpty()) return id;
            String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            return (title == null ? "" : title) + "|" + (artist == null ? "" : artist);
        } catch (Exception e) {
            return "";
        }
    }

    /** The player's own name, for a sentence a person can read. */
    static String appLabel(Context c, String packageName) {
        if (packageName == null || packageName.isEmpty()) return "the player";
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0));
            String name = label == null ? "" : label.toString().trim();
            return name.isEmpty() ? "the player" : name;
        } catch (Exception e) {
            return "the player";
        }
    }
}
