package com.orbit.assistant;

import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What an external Share handed Orbit, held between the share bridge and the chat composer.
 *
 * <p>The handoff needs somewhere to live because the two are different Activities, and the obvious
 * place - Intent extras - is the wrong one. A shared photo decoded to a Bitmap is megabytes, and
 * Binder transactions are measured in kilobytes; putting the content itself in an extra would
 * work for one small image and crash the launch for four large ones. So what travels between them
 * is a small private token, and the content stays here.
 *
 * <p>The token is one-shot and internal. It is generated inside Orbit, handed only to a
 * non-exported Activity in the same process, and consumed as it is read, so a share cannot be
 * replayed by a recreated Activity and nothing outside Orbit is ever in a position to name one.
 *
 * <p>In memory, bounded, and deliberately not durable. A staged share is alive for the moment
 * between the share sheet closing and the composer opening; losing it to a process death is the
 * correct outcome - the user shared something and Orbit did not get there, which is visible and
 * recoverable by sharing again - and is far better than a queue of other apps' photos surviving on
 * disk indefinitely.
 */
public final class SharedContentStore {

    /** How many staged shares may be waiting at once. Abandoned entries are dropped from the end. */
    private static final int MAX_ENTRIES = 4;
    /** How long a staged share may wait to be picked up. */
    private static final long STALE_MS = 5L * 60L * 1000L;

    /** One external share, already validated and deduplicated. */
    public static final class Staged {
        /** Shared text, or empty. Never interpreted, only placed in the composer. */
        public final String text;
        /** Shared streams, in the order the sending app listed them, deduplicated. */
        public final List<Uri> uris;
        /** Orbit's own shape word for Diagnostics: text, image, images, file, files, mixed. */
        public final String shape;
        /** How many items the sender offered, before Orbit's per-message limit was applied. */
        public final int offered;

        Staged(String text, List<Uri> uris, String shape, int offered) {
            this.text = text == null ? "" : text;
            this.uris = Collections.unmodifiableList(new ArrayList<>(uris));
            this.shape = shape == null ? "" : shape;
            this.offered = offered;
        }

        public boolean isEmpty() { return text.isEmpty() && uris.isEmpty(); }
    }

    private static final class Entry {
        final Staged staged;
        final long createdAt = System.currentTimeMillis();
        Entry(Staged staged) { this.staged = staged; }
    }

    private static final Map<String, Entry> STAGED = new HashMap<>();

    private SharedContentStore() {}

    /** Stages one share and returns the private token that stands for it. */
    public static synchronized String stage(String text, List<Uri> uris, String shape, int offered) {
        prune();
        String token = "share-" + UUID.randomUUID();
        STAGED.put(token, new Entry(new Staged(text, uris == null ? new ArrayList<>() : uris,
                shape, offered)));
        return token;
    }

    /**
     * Reads a staged share and removes it, so the same share can only ever be applied once.
     *
     * <p>Consuming on read is what stops a configuration change or a recreated Activity attaching
     * the same four photos a second time; a token that has already been used simply resolves to
     * nothing.
     */
    public static synchronized Staged consume(String token) {
        if (token == null || token.isEmpty()) return null;
        prune();
        Entry entry = STAGED.remove(token);
        return entry == null ? null : entry.staged;
    }

    /** Test seam and lifecycle safety: forget everything staged. */
    public static synchronized void clear() { STAGED.clear(); }

    private static void prune() {
        long now = System.currentTimeMillis();
        STAGED.entrySet().removeIf(e -> now - e.getValue().createdAt > STALE_MS);
        while (STAGED.size() > MAX_ENTRIES) {
            String oldest = null;
            long oldestAt = Long.MAX_VALUE;
            for (Map.Entry<String, Entry> e : STAGED.entrySet()) {
                if (e.getValue().createdAt < oldestAt) {
                    oldestAt = e.getValue().createdAt;
                    oldest = e.getKey();
                }
            }
            if (oldest == null) return;
            STAGED.remove(oldest);
        }
    }
}
