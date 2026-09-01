package com.orbit.assistant;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What an earlier attachment-bearing turn contributes to a request being built now.
 *
 * <p>An attachment belongs to the turn the user shared it with. That is a statement about the
 * conversation, not about the wire: Orbit's ChatGPT transport is stateless, so nothing the user
 * shared three messages ago is still sitting on a server waiting to be referred to. Every request
 * is rebuilt from Orbit's own record, and until now that rebuild carried only role and text, so
 * the image the whole conversation was about silently ceased to exist the moment a follow-up was
 * asked. The visible symptom was the opposite of the cause: the overlay kept the screen armed
 * after sending, so the follow-up looked like it had attached the same screenshot a second time,
 * while the model was actually being told less each turn rather than more.
 *
 * <p>This decides what to put back, and where. A reconstructed attachment is attached to the
 * historical turn it originally belonged to, never to the new message, so the model sees the
 * conversation exactly as the user remembers having it. Nothing here creates, copies, or moves a
 * stored file; it only reads what the conversation already recorded.
 *
 * <p>The policy is deliberately bounded. A long conversation about one screenshot must not grow a
 * request without limit, so only turns already inside the provider's history window are eligible,
 * only the most recent few turns contribute image bytes, the same stored file is never sent twice
 * in one request, and older turns degrade to their extracted text. A turn whose file has since
 * been deleted keeps its text and contributes no image: Orbit would rather tell the model less
 * than invent a picture.
 */
public final class HistoryAttachments {
    /**
     * Images, across the whole reconstructed window, that may still be re-sent.
     *
     * <p>A budget of pictures, never a budget of turns, and that distinction is the whole reason
     * this number did not change when a turn stopped being able to hold only one. Three retained
     * turns used to mean at most three images because a turn was one image; leaving the rule
     * phrased that way once a turn can hold ten would have turned "3" into "30" without anyone
     * deciding to. The intent of the original policy was a bounded request, so the bound stays
     * exactly where it was and is now spent newest-first across however many images each turn has.
     */
    public static final int MAX_IMAGES = 3;
    /**
     * Turns nearest the present that may contribute image bytes at all.
     *
     * <p>The other half of the same intent. Without it, one turn holding ten photos could spend
     * the entire image budget and leave the two turns after it wordless pictures of nothing; with
     * it, the budget is still three images but they may come from up to three different turns.
     */
    public static final int MAX_IMAGE_TURNS = 3;
    /** Per-turn cap on reconstructed attachment text, well under the current turn's allowance. */
    public static final int MAX_TEXT_CHARS = 8000;

    /** Attachment kinds that are a capture of the phone's own screen. */
    private static final String KIND_SCREEN = "screen";
    private static final String KIND_SELECTION = "screen_selection";

    private HistoryAttachments() {}

    /** One historical turn's reconstructed attachment. */
    public static final class Turn {
        /** Index into the window handed to {@link #plan}. */
        public final int index;
        /** Orbit's own category name. Never a filename and never anything the user typed. */
        public final String kind;
        /** Bounded attachment text, or empty when the turn carried none. */
        public final String text;
        /**
         * The first stored image to re-send with this turn, or empty for none.
         *
         * <p>Always the head of {@link #imagePaths}. Kept as its own field because the common turn
         * has one image and reading one field is clearer than taking a head everywhere.
         */
        public final String imagePath;
        /**
         * Every stored image to re-send with this turn, in the order the user attached them.
         *
         * <p>Order is what makes the reconstruction faithful: a question about "the second photo"
         * only means anything if the second photo is still second.
         */
        public final List<String> imagePaths;
        /** True when the turn's record claimed an image and the file is no longer there. */
        public final boolean assetMissing;

        Turn(int index, String kind, String text, List<String> imagePaths, boolean assetMissing) {
            this.index = index;
            this.kind = kind;
            this.text = text;
            this.imagePaths = Collections.unmodifiableList(new ArrayList<>(imagePaths));
            this.imagePath = this.imagePaths.isEmpty() ? "" : this.imagePaths.get(0);
            this.assetMissing = assetMissing;
        }

        public boolean hasImage() { return !imagePaths.isEmpty(); }
        public int imageCount() { return imagePaths.size(); }
        public boolean isEmpty() { return text.isEmpty() && imagePaths.isEmpty(); }
    }

    /** Everything the request builder needs, plus the counts Diagnostics reports. */
    public static final class Plan {
        private final List<Turn> turns;
        public final int images;
        public final int missingAssets;
        /** Distinct safe category names, in the order first seen. Never contents, never paths. */
        public final List<String> kinds;

        Plan(List<Turn> turns, int images, int missingAssets, List<String> kinds) {
            this.turns = turns;
            this.images = images;
            this.missingAssets = missingAssets;
            this.kinds = Collections.unmodifiableList(kinds);
        }

        public List<Turn> turns() { return Collections.unmodifiableList(turns); }

        /** The reconstruction for a window index, or null when that turn carried no attachment. */
        public Turn at(int index) {
            for (Turn t : turns) if (t.index == index) return t;
            return null;
        }

        public int size() { return turns.size(); }

        /** A one-line, privacy-safe summary of the types involved. */
        public String kindLabel() {
            if (kinds.isEmpty()) return "none";
            StringBuilder out = new StringBuilder();
            for (String k : kinds) {
                if (out.length() > 0) out.append(", ");
                out.append(k);
            }
            return out.toString();
        }
    }

    private static final Plan EMPTY = new Plan(new ArrayList<>(), 0, 0, new ArrayList<>());

    public static Plan empty() { return EMPTY; }

    /**
     * Works out what each turn in the outgoing history window still carries.
     *
     * @param window            exactly the turns being sent as history, in order. The message
     *                          being asked right now is not part of it: its attachment travels the
     *                          current-turn path and must never also appear here.
     * @param allowScreenImages whether a stored capture of the phone's screen may be re-sent, which
     *                          follows the same screenshot preference the current turn obeys.
     *                          Explicit attachments the user chose are not governed by it.
     */
    public static Plan plan(List<AssistantClient.History> window, boolean allowScreenImages) {
        if (window == null || window.isEmpty()) return EMPTY;

        List<Turn> reversed = new ArrayList<>();
        Set<String> usedPaths = new HashSet<>();
        Set<String> kinds = new LinkedHashSet<>();
        int images = 0;
        int missing = 0;
        int imageTurns = 0;

        // Walked newest-first so the image budget is spent on the turns the user is most likely to
        // still be talking about. The result is put back in order before it is handed out.
        for (int i = window.size() - 1; i >= 0; i--) {
            AssistantClient.History h = window.get(i);
            if (h == null || !h.screenAttached) continue;
            if (!"user".equalsIgnoreCase(h.role)) continue;

            String kind = category(h.attachmentKind);
            String text = clip(h.attachmentText, MAX_TEXT_CHARS);

            List<String> imagePaths = new ArrayList<>();
            boolean assetMissing = false;
            boolean turnCharged = false;
            // Both bounds apply at once: the whole request may carry MAX_IMAGES pictures, and they
            // may come from at most MAX_IMAGE_TURNS different turns. A turn that shared ten photos
            // therefore contributes the first few of them rather than all ten, and never crowds
            // every other turn out of the budget.
            for (String stored : h.attachmentPaths) {
                if (stored == null || stored.trim().isEmpty()) continue;
                stored = stored.trim();
                if (!exists(stored)) {
                    // The record says an image was shared and the file is gone. The conversation
                    // survives on its text; nothing is fabricated to fill the hole.
                    assetMissing = true;
                    missing++;
                    continue;
                }
                // Present, readable, and not ours to re-send under the current settings.
                if (!imageAllowed(kind, allowScreenImages)) continue;
                // The same stored file already went into this request for another turn.
                if (usedPaths.contains(stored)) continue;
                if (images >= MAX_IMAGES) continue;
                if (!turnCharged && imageTurns >= MAX_IMAGE_TURNS) continue;
                imagePaths.add(stored);
                usedPaths.add(stored);
                images++;
                if (!turnCharged) {
                    turnCharged = true;
                    imageTurns++;
                }
            }

            Turn turn = new Turn(i, kind, text, imagePaths, assetMissing);
            if (turn.isEmpty() && !assetMissing) continue;
            reversed.add(turn);
            kinds.add(kind);
        }

        Collections.reverse(reversed);
        return new Plan(reversed, images, missing, new ArrayList<>(kinds));
    }

    /**
     * The XML-ish wrapper a reconstructed attachment is put inside.
     *
     * <p>Identical framing to the current turn's, including {@code untrusted="true"}, because a
     * screenshot does not become trustworthy by being a few messages old. Text found inside one is
     * information about the user's request and never an instruction to Orbit.
     */
    public static String tagFor(String kind) {
        return KIND_SCREEN.equals(kind) ? "orbit_screen_context" : "orbit_user_attachment";
    }

    /** Wraps a turn's attachment text onto that turn's own message. Empty in, empty out. */
    public static String wrap(String kind, String text) {
        if (text == null || text.trim().isEmpty()) return "";
        String tag = tagFor(kind);
        return "\n\n<" + tag + " untrusted=\"true\">\n" + text + "\n</" + tag + ">";
    }

    /**
     * Orbit's own name for what was attached, for Diagnostics and for nothing else.
     *
     * <p>A closed set of Orbit's categories. A filename, a label the user typed, or anything read
     * out of the attachment itself cannot reach this, so no line built from it can leak what was
     * shared.
     */
    public static String category(String attachmentKind) {
        String kind = attachmentKind == null ? "" : attachmentKind.trim();
        if (KIND_SELECTION.equals(kind)) return "selection";
        if (KIND_SCREEN.equals(kind)) return "screen";
        if ("image".equals(kind)) return "image";
        if ("pdf".equals(kind)) return "pdf";
        if ("file_text".equals(kind)) return "file";
        if ("clipboard".equals(kind)) return "clipboard";
        return kind.isEmpty() ? "attachment" : "attachment";
    }

    /**
     * A capture of the phone's screen follows the screenshot preference the way a live one does.
     * An image the user picked or shot themselves is theirs, and is not governed by it.
     */
    private static boolean imageAllowed(String kind, boolean allowScreenImages) {
        return allowScreenImages || !"screen".equals(kind);
    }

    private static boolean exists(String path) {
        try {
            File file = new File(path);
            return file.isFile() && file.length() > 0L;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String clip(String value, int max) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
