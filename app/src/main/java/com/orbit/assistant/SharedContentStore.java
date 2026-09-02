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

    /** An Android share sheet. */
    public static final String SOURCE_SHARE = "share";
    /** Android's text-selection menu, through {@code ACTION_PROCESS_TEXT}. */
    public static final String SOURCE_PROCESS_TEXT = "process_text";

    /**
     * The most characters any external route may put into one composer.
     *
     * <p>One number for every door into Orbit from another app, so a selection cannot carry more
     * than a share can. It is a bound on Orbit's own memory and composer rather than a judgement
     * about the text: 36,000 characters is far more than anyone selects by hand and far less than
     * a hostile app could otherwise push across in a single Intent.
     */
    public static final int MAX_TEXT_CHARS = 36000;

    /** One piece of external content, already validated. */
    public static final class Staged {
        /**
         * Which door this came through: {@link #SOURCE_SHARE} or {@link #SOURCE_PROCESS_TEXT}.
         *
         * <p>Metadata about the route, never about the content, and it changes nothing about how
         * the content is treated. Both are untrusted external input, both are validated by the
         * same rules, and both end up as unsent material in a composer. Recording which is which
         * only makes Diagnostics able to say where the last one came from.
         */
        public final String source;
        /** Shared text, or empty. Never interpreted, only placed in the composer. */
        public final String text;
        /** Shared streams, in the order the sending app listed them, deduplicated. */
        public final List<Uri> uris;
        /** Orbit's own shape word for Diagnostics: text, image, images, file, files, mixed. */
        public final String shape;
        /** How many items the sender offered, before Orbit's per-message limit was applied. */
        public final int offered;

        Staged(String source, String text, List<Uri> uris, String shape, int offered) {
            this.source = source == null || source.isEmpty() ? SOURCE_SHARE : source;
            this.text = text == null ? "" : text;
            this.uris = Collections.unmodifiableList(new ArrayList<>(uris));
            this.shape = shape == null ? "" : shape;
            this.offered = offered;
        }

        public boolean isEmpty() { return text.isEmpty() && uris.isEmpty(); }
    }

    /**
     * Trims external text and bounds it, saying so when it had to.
     *
     * <p>Truncating in silence is the one thing this must not do. A user who selects six pages and
     * gets four of them without being told has been given a wrong answer about their own text, so
     * the notice is part of what lands in the composer and travels with it.
     */
    public static String bound(String value) {
        if (value == null) return "";
        String text = value.trim();
        if (text.length() <= MAX_TEXT_CHARS) return text;
        return text.substring(0, MAX_TEXT_CHARS)
                + "\n\n[Orbit truncated the shared text after "
                + String.format(java.util.Locale.US, "%,d", MAX_TEXT_CHARS) + " characters.]";
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
        return stage(SOURCE_SHARE, text, uris, shape, offered);
    }

    /**
     * Stages external content from a named door.
     *
     * <p>Deliberately one store rather than one per entry point. Share to Orbit and Ask Orbit
     * differ in what Android hands over, and in nothing else that matters here: both are content
     * from an app Orbit does not control, both are bounded before they reach this, both are
     * carried by a one-shot private token, and both end as unsent material in a new conversation.
     * A second store would be a second set of rules to keep in step, and the rules are the part
     * worth having exactly once.
     */
    public static synchronized String stage(String source, String text, List<Uri> uris,
                                            String shape, int offered) {
        prune();
        String kind = source == null || source.isEmpty() ? SOURCE_SHARE : source;
        String token = kind + "-" + UUID.randomUUID();
        STAGED.put(token, new Entry(new Staged(kind, text, uris == null ? new ArrayList<>() : uris,
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
