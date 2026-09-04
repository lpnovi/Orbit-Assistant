package com.orbit.assistant;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/**
 * Every notification category Orbit owns, declared in one place and registered up front.
 *
 * <p>Android only lists a channel under Settings once the app has created it, and Orbit used to
 * create each one lazily, on the way to posting that kind of notification. The effect on real
 * devices was that two installations of the same build showed different Notification categories: a
 * phone that had never been offered an update had no "Orbit updates" row, and there was no way for
 * the user to pre-emptively silence a category they had not yet received. Nothing was broken; the
 * settings screen was simply describing a subset of Orbit.
 *
 * <p>So registration moved off the notification path entirely. {@link #ensureAll(Context)} runs
 * during ordinary app start-up and declares all four, which is all Android needs to show them.
 *
 * <p>Two things this deliberately does not do. It posts nothing — creating a channel is metadata,
 * and a category appearing in Settings must never cost the user a notification they did not ask
 * for. And it neither requests nor requires {@code POST_NOTIFICATIONS}: channels can be created
 * without it, so a user who has refused notifications outright still gets an accurate list of what
 * Orbit would send if they changed their mind.
 *
 * <p>The IDs are the ones Orbit has always used and are not free to change. An ID is the identity
 * of a channel to Android: renaming one creates a second, empty channel and abandons whatever the
 * user had configured on the first. Names, descriptions and importances are passed through
 * {@code createNotificationChannel}, which updates the name and description of an existing channel
 * but leaves importance, sound and vibration exactly as the user set them. That is the intended
 * behaviour — after creation those belong to the user, not to Orbit — and it is why this never
 * deletes and recreates a channel to reassert a default.
 */
public final class OrbitNotificationChannels {

    /** Completions that arrive after the user has left the chat. */
    public static final String BACKGROUND_RESPONSES = "orbit_background_responses";
    /** Reminders the user asked Orbit to keep. */
    public static final String REMINDERS = "orbit_reminders";
    /** An automatic routine that needs attention before it can continue. */
    public static final String ROUTINE_TRIGGERS = "orbit_routine_triggers";
    /** A verified Orbit release being available. */
    public static final String UPDATES = "orbit_updates";

    private OrbitNotificationChannels() {}

    /** One channel's fixed identity, so the four definitions read as a list rather than as code. */
    private static final class Definition {
        final String id;
        final String name;
        final String description;
        final int importance;

        Definition(String id, String name, String description, int importance) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.importance = importance;
        }
    }

    private static List<Definition> definitions() {
        List<Definition> out = new ArrayList<>(4);
        out.add(new Definition(BACKGROUND_RESPONSES, "Background responses",
                "Notifies you when an Orbit response finishes after you leave the chat.",
                NotificationManager.IMPORTANCE_DEFAULT));
        out.add(new Definition(REMINDERS, "Reminders",
                "Reminders you create through Orbit.",
                NotificationManager.IMPORTANCE_HIGH));
        out.add(new Definition(ROUTINE_TRIGGERS, "Routine triggers",
                "Only alerts you when an automatic Orbit routine needs attention or a foreground step.",
                NotificationManager.IMPORTANCE_DEFAULT));
        out.add(new Definition(UPDATES, "Orbit updates",
                "Notifies you when a verified Orbit release is available.",
                NotificationManager.IMPORTANCE_DEFAULT));
        return out;
    }

    /**
     * Declares every Orbit channel. Safe to call as often as anything likes.
     *
     * <p>Idempotent because {@code createNotificationChannel} is: a channel that already exists is
     * updated in the ways Android permits and otherwise left alone.
     */
    public static void ensureAll(Context context) {
        if (context == null || Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = manager(context);
        if (manager == null) return;
        for (Definition definition : definitions()) create(manager, definition);
    }

    /** Declares one channel by ID, for the notifier that is about to use it. */
    public static void ensure(Context context, String channelId) {
        if (context == null || channelId == null || Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = manager(context);
        if (manager == null) return;
        for (Definition definition : definitions()) {
            if (definition.id.equals(channelId)) {
                create(manager, definition);
                return;
            }
        }
    }

    /** The IDs Orbit registers, in declaration order. Also the shape its tests assert against. */
    public static List<String> ids() {
        List<String> out = new ArrayList<>(4);
        for (Definition definition : definitions()) out.add(definition.id);
        return out;
    }

    private static NotificationManager manager(Context context) {
        Object service = context.getApplicationContext()
                .getSystemService(Context.NOTIFICATION_SERVICE);
        return service instanceof NotificationManager ? (NotificationManager) service : null;
    }

    private static void create(NotificationManager manager, Definition definition) {
        NotificationChannel channel = new NotificationChannel(
                definition.id, definition.name, definition.importance);
        channel.setDescription(definition.description);
        manager.createNotificationChannel(channel);
    }
}
