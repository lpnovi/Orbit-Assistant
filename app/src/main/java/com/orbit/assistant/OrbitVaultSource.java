package com.orbit.assistant;

import java.util.Locale;

/**
 * Where a saved item came from, in the only words Orbit is willing to write.
 *
 * <p>A source is a small piece of provenance shown under a saved item and folded into local search:
 * "this is the thing I clipped from a PDF", "this is the paragraph I selected in a browser". Beta 1
 * and Beta 2 wrote those words as string literals at each save site, which was fine while there
 * were four of them and would not have survived Beta 3 adding four more - the same route would have
 * drifted into "Screen Selection" on one screen and "Screen selection" on another, and search would
 * have quietly stopped matching one of them.
 *
 * <p><b>This vocabulary is closed on purpose, and that is a privacy decision rather than a tidiness
 * one.</b> Every string here names something <em>Orbit itself</em> did: a door in Orbit the user
 * walked through, or a surface of Orbit they were looking at. None of them is ever derived from
 * content, from a filename, from a URL, or from another app.
 *
 * <p>In particular, a share is labelled {@link #SHARED} and never "Shared from &lt;app&gt;". Android
 * does not hand a share target a trustworthy identity for the app that sent it: what a receiver can
 * see is what the sender chose to put in the Intent, which any app can set to anything. Writing
 * "Shared from Chrome" into a durable store on that basis would be Orbit stating, permanently and
 * in the user's own collection, something it does not actually know. A page saved from Orbit's own
 * document viewer is different in kind: Orbit rendered it, so Orbit knows.
 *
 * <p>The one shaped label, {@link #documentPage(int)}, still contains nothing external. The document
 * name is a separate field on the item; the source line carries only the page number, which Orbit
 * counted itself.
 */
public final class OrbitVaultSource {

    /** Written by the user in the Vault's own capture dialog. */
    public static final String QUICK_CAPTURE = "Quick Capture";
    /** Read once from the clipboard, at the user's request, in Quick Capture. */
    public static final String CLIPBOARD = "Clipboard";
    /** Chosen by the user in their gallery picker, from Quick Capture. */
    public static final String PHOTO = "Photo";
    /** Arrived through Android's share sheet. Never names the sending app. */
    public static final String SHARED = "Shared to Orbit";
    /** Selected in another app and sent to Orbit through the text-selection menu. */
    public static final String SELECTED_TEXT = "Selected text";
    /** Cropped or marked up in Orbit's own screen selection editor. */
    public static final String SCREEN_SELECTION = "Screen selection";
    /** An answer Orbit gave, saved from a conversation. */
    public static final String ORBIT_REPLY = "Orbit answer";
    /** The prefix a page saved from Orbit's own document viewer carries. */
    public static final String DOCUMENT = "Document";

    /**
     * Every source a saved item may be filtered by, in the order the filter offers them.
     *
     * <p>The same closed list the constants above declare, written once so the filter cannot
     * invent a seventh source or quietly drop one when a save route is added. A shaped document
     * source collapses to {@link #DOCUMENT} here: "the pages I kept" is the question somebody
     * actually asks, and one filter per page number would be absurd.
     */
    public static final String[] FILTERABLE = {
            QUICK_CAPTURE, CLIPBOARD, PHOTO, SHARED, SELECTED_TEXT, SCREEN_SELECTION,
            ORBIT_REPLY, DOCUMENT};

    private OrbitVaultSource() {}

    /**
     * The canonical source this label belongs to, or empty when Orbit did not write it.
     *
     * <p>The one place a stored source string is turned back into something Orbit will act on, and
     * it is deliberately strict: a label from the closed vocabulary maps to itself, "Document ·
     * Page 7" maps to {@link #DOCUMENT}, and anything else - a hand-edited store, a restored backup
     * written by some other build, a string that arrived from another app - maps to nothing at all.
     * Filtering can therefore never be driven by a word Orbit did not choose.
     *
     * <p>It is not a gate on storage. An item whose source Orbit no longer recognises keeps that
     * source, keeps its place in the Vault, and is simply not offered as a source to filter by.
     */
    public static String family(String value) {
        if (value == null) return "";
        String source = value.trim();
        if (source.isEmpty()) return "";
        for (String known : FILTERABLE) {
            if (known.equals(source)) return known;
        }
        return isCanonical(source) ? DOCUMENT : "";
    }

    /**
     * "Document · Page 7", the line shown under a saved page.
     *
     * <p>The separator is the same middle dot the item screen and the picker already use between a
     * type and its detail, so a saved page reads in the same rhythm as everything else rather than
     * inventing a second way to join two facts. A page number Orbit could not work out degrades to
     * the bare word, which is honest and still fits.
     */
    public static String documentPage(int humanPageNumber) {
        if (humanPageNumber <= 0) return DOCUMENT;
        return DOCUMENT + " · Page " + humanPageNumber;
    }

    /**
     * Whether this is a label Orbit itself writes.
     *
     * <p>Exists so the vocabulary can be asserted rather than trusted: a save route added later
     * that invents its own words fails a test instead of shipping. It is not a gate on storage -
     * a restored backup may legitimately carry a source string written by an older Orbit, and
     * refusing those would lose provenance the user already had.
     */
    public static boolean isCanonical(String value) {
        if (value == null) return false;
        String source = value.trim();
        if (source.isEmpty()) return false;
        for (String known : new String[]{QUICK_CAPTURE, CLIPBOARD, PHOTO, SHARED, SELECTED_TEXT,
                SCREEN_SELECTION, ORBIT_REPLY, DOCUMENT}) {
            if (known.equals(source)) return true;
        }
        if (!source.startsWith(DOCUMENT + " · Page ")) return false;
        String number = source.substring((DOCUMENT + " · Page ").length());
        if (number.isEmpty()) return false;
        for (int i = 0; i < number.length(); i++) {
            if (!Character.isDigit(number.charAt(i))) return false;
        }
        return true;
    }

    /** Lower-cased, for the one place a source is compared rather than shown. */
    static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }
}
