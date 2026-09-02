package com.orbit.assistant;

import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What an open attachment viewer is looking at, held between the surface that opened it and the
 * viewer screen itself.
 *
 * <p>The handoff needs somewhere to live for the same reason Share to Orbit needs one: the two are
 * different Activities, and the obvious channel is the wrong one. A decoded photo is megabytes and
 * a Binder transaction is kilobytes, so putting the image in an Intent extra works for one small
 * thumbnail and kills the launch for ten camera photos. What travels is a small private token; the
 * images stay here.
 *
 * <p>Unlike a staged share, a session is <em>not</em> consumed on read. A viewer is a screen the
 * user stays on, and it is recreated by every rotation; a one-shot token would leave a rotated
 * viewer looking at nothing. It is released explicitly when the viewer finishes, and abandoned
 * sessions are pruned, so the store cannot accumulate other people's pictures.
 *
 * <p>The two sources are genuinely different and are kept that way rather than flattened. A
 * composer session points at the live {@link ComposerAttachments} - the one collection that knows
 * what is attached - so removing an image from the viewer removes it from the message, not from a
 * copy of the message. A history session points at stored files, is read-only, and decodes them
 * lazily inside a small window so that opening a ten photo turn does not decode ten photos.
 */
public final class AttachmentViewerStore {

    /** An unsent message. Images are already decoded, and removal is real. */
    public static final String SOURCE_COMPOSER = "composer";
    /** A turn that has already been sent. Stored files, read-only. */
    public static final String SOURCE_HISTORY = "history";

    /** How many viewer sessions may exist at once. In practice one; two survives a fast reopen. */
    private static final int MAX_SESSIONS = 2;
    /** How long an abandoned session may sit here before it is dropped. */
    private static final long STALE_MS = 30L * 60L * 1000L;
    /**
     * How many stored images a history session keeps decoded.
     *
     * <p>Three, because the viewer draws the current page and pre-loads its two neighbours so a
     * swipe is not a blank frame. Beyond that a decoded bitmap is pure cost: Orbit stores history
     * images at up to 1280px, so ten of them decoded at once is on the order of fifty megabytes
     * for nine pictures nobody is looking at.
     */
    private static final int MAX_DECODED = 3;

    /** One open viewer. */
    public static final class Session {
        public final String source;
        public final AttachmentViewerModel model;
        /**
         * The live composer collection this viewer is showing, or null for a sent turn.
         *
         * <p>Deliberately the real object rather than a snapshot. A viewer that removed from a copy
         * would leave the strip beneath it still holding the photo, which is the exact class of bug
         * that a single canonical collection exists to prevent.
         */
        private final ComposerAttachments composer;
        /** Stored images most recently decoded for a history session, oldest first. */
        private final Map<String, Bitmap> decoded = new LinkedHashMap<>();
        final long createdAt = System.currentTimeMillis();

        Session(String source, AttachmentViewerModel model, ComposerAttachments composer) {
            this.source = source;
            this.model = model;
            this.composer = composer;
        }

        /** True when this viewer is allowed to remove what it is showing. */
        public boolean removable() {
            return composer != null && model.isRemovable();
        }

        /**
         * The image at a position, or null when it can no longer be produced.
         *
         * <p>Null is a real answer, not a failure: a conversation from six months ago may refer to
         * a thumbnail the user has since cleared, and the viewer says so rather than crashing or
         * skipping silently past it.
         */
        public Bitmap imageAt(int position) {
            AttachmentViewerModel.Item item = model.at(position);
            if (item == null) return null;
            if (composer != null) {
                for (ComposerAttachment attachment : composer.items()) {
                    if (attachment != null && attachment.id.equals(item.id)) return attachment.image;
                }
                return null;
            }
            return decodeStored(item.id);
        }

        /**
         * Drops a decoded image the viewer has scrolled away from.
         *
         * <p>The reference is released rather than the bitmap recycled. Recycling would be the
         * stronger claim and the wrong one: a page can still be mid-draw as the window moves, and
         * a recycled bitmap under a draw is a crash. Dropping the last reference is what actually
         * lets the memory go, and it cannot be wrong.
         */
        public void release(int position) {
            AttachmentViewerModel.Item item = model.at(position);
            if (item == null || composer != null) return;
            decoded.remove(item.id);
        }

        /**
         * Removes the image on screen from the message being written.
         *
         * <p>One instruction, carried out on the canonical collection and then reflected here.
         * Exactly the attachment shown is removed - by id, never by position in some other list -
         * and every sibling attachment, the composer text and any standing screen policy are
         * untouched. Returns false for a sent turn, which has no removal at all.
         */
        public boolean removeCurrent() {
            if (!removable()) return false;
            AttachmentViewerModel.Item item = model.current();
            if (item == null) return false;
            boolean removed = composer.remove(item.id);
            if (!removed) return false;
            model.removeCurrent();
            return true;
        }

        private Bitmap decodeStored(String path) {
            Bitmap cached = decoded.get(path);
            if (cached != null && !cached.isRecycled()) return cached;
            // Asked of the filesystem before it is asked of the decoder. A conversation can outlive
            // the thumbnails it refers to - the user clears them, or turns saved screenshots off -
            // and "the file is not there" is a cheaper and far more definite answer than handing a
            // missing path to an image decoder and interpreting what comes back.
            if (!exists(path)) return null;
            Bitmap loaded = AttachmentStore.load(path);
            if (loaded == null) return null;
            decoded.put(path, loaded);
            while (decoded.size() > MAX_DECODED) {
                String oldest = decoded.keySet().iterator().next();
                decoded.remove(oldest);
            }
            return loaded;
        }

        void clear() { decoded.clear(); }

        private static boolean exists(String path) {
            try {
                java.io.File file = new java.io.File(path);
                return file.isFile() && file.length() > 0L;
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    private static final Map<String, Session> SESSIONS = new LinkedHashMap<>();

    private AttachmentViewerStore() {}

    /**
     * Opens a viewer over the images of a message still being written.
     *
     * <p>Only the viewable images enter the list, in composer order, so a strip holding a PDF and
     * three photos opens a viewer of three photos. The opening position is resolved from the
     * tapped attachment's id rather than from its place in the strip, because those two lists are
     * not the same length.
     *
     * @return a private token, or empty when there is nothing that can be viewed.
     */
    public static synchronized String openComposer(ComposerAttachments attachments, String tappedId) {
        if (attachments == null) return "";
        List<AttachmentViewerModel.Item> items = new ArrayList<>();
        for (ComposerAttachment attachment : attachments.items()) {
            if (!AttachmentViewerModel.isViewable(attachment)) continue;
            items.add(new AttachmentViewerModel.Item(
                    attachment.id, attachment.kind, attachment.label));
        }
        if (items.isEmpty()) return "";
        AttachmentViewerModel model = new AttachmentViewerModel(items, 0, true);
        int at = model.indexOf(tappedId);
        model.moveTo(at < 0 ? 0 : at);
        return put(new Session(SOURCE_COMPOSER, model, attachments));
    }

    /**
     * Opens a read-only viewer over the stored images of a turn that has already been sent.
     *
     * <p>Every path the turn recorded is kept, including one whose file has since gone. Quietly
     * dropping it would make the viewer disagree with the message above it about how many photos
     * were shared; saying that one is no longer available is both true and useful.
     *
     * @param position which stored image was tapped, by index into {@code paths}.
     */
    public static synchronized String openHistory(List<String> paths, String kind, String label,
                                                  int position) {
        if (paths == null || paths.isEmpty()) return "";
        List<AttachmentViewerModel.Item> items = new ArrayList<>();
        for (String path : paths) {
            if (path == null || path.trim().isEmpty()) continue;
            items.add(new AttachmentViewerModel.Item(path.trim(), kind, label));
        }
        if (items.isEmpty()) return "";
        return put(new Session(SOURCE_HISTORY, new AttachmentViewerModel(items, position, false),
                null));
    }

    /** The session a token names, or null. Repeatable: a rotation must not lose the viewer. */
    public static synchronized Session peek(String token) {
        if (token == null || token.isEmpty()) return null;
        prune();
        return SESSIONS.get(token);
    }

    /** Releases a session and everything it had decoded. Called when the viewer finishes. */
    public static synchronized void close(String token) {
        if (token == null || token.isEmpty()) return;
        Session session = SESSIONS.remove(token);
        if (session != null) session.clear();
    }

    /** Test seam and lifecycle safety: forget every open session. */
    public static synchronized void clear() {
        for (Session session : SESSIONS.values()) session.clear();
        SESSIONS.clear();
    }

    /**
     * Stages a session and drops the oldest once there are too many.
     *
     * <p>Eviction goes by insertion order rather than by creation time, which is not a detail. Two
     * sessions opened in the same millisecond carry the same timestamp, so ordering by the clock
     * leaves them tied and the loser picked by hash order - which can be the session that was just
     * created, at which point the bound stops being enforced at all and the store keeps every
     * abandoned viewer alive. Insertion order is exact whatever the clock resolution is, and the
     * newest entry can never be chosen.
     */
    private static String put(Session session) {
        prune();
        String token = "view-" + UUID.randomUUID();
        SESSIONS.put(token, session);
        while (SESSIONS.size() > MAX_SESSIONS) {
            String oldest = null;
            for (String candidate : SESSIONS.keySet()) {
                // The head of insertion order, never the entry this call just added.
                if (!candidate.equals(token)) { oldest = candidate; break; }
            }
            if (oldest == null) break;
            Session dropped = SESSIONS.remove(oldest);
            if (dropped != null) dropped.clear();
        }
        return token;
    }

    private static void prune() {
        long now = System.currentTimeMillis();
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, Session> entry : SESSIONS.entrySet()) {
            if (now - entry.getValue().createdAt > STALE_MS) stale.add(entry.getKey());
        }
        for (String token : stale) {
            Session session = SESSIONS.remove(token);
            if (session != null) session.clear();
        }
    }

}
