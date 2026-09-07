package com.orbit.assistant;

import android.content.Context;

/**
 * What happened to the last picture Orbit tried to fetch, for Diagnostics and nowhere else.
 *
 * <p>Beta 1 shipped one message for every possible failure, which is the right thing to show a
 * person and the wrong thing to debug with. "Image could not be loaded" on a Galaxy S25 Ultra could
 * equally have meant a refused address, a 403 from a CDN, a redirect Orbit would not follow, a
 * format the device cannot decode, or a page returned where a picture was expected - and there was
 * no way to tell which without a cable and a log.
 *
 * <p><b>A category and a status, and nothing else.</b> No address, no host, no header, no server
 * message, no answer text. Where somebody was reading is not something Orbit writes down, so this
 * records that the last attempt ended in {@code HTTP 403} without recording what it was asking for.
 * It lives in the same private diagnostics store as every other counter, is overwritten by the next
 * attempt, and is cleared with the rest of the local data.
 */
public final class RichAnswerImageStatus {

    private static final String FILE = "orbit_diagnostics";

    private RichAnswerImageStatus() {}

    /** Records the outcome of one fetch. Called from the loader's own background thread. */
    static void record(Context context, RemoteImageLoader.Result result) {
        if (context == null || result == null) return;
        try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                    .putString("rich_image_outcome", describe(result))
                    .putLong("rich_image_updated", System.currentTimeMillis())
                    .apply();
        } catch (Exception ignored) {}
    }

    /**
     * The one line Diagnostics shows.
     *
     * <p>Deliberately built from the category rather than from anything the network said, so a
     * server that returns a chatty error body cannot put its own words into Orbit's diagnostics.
     */
    static String describe(RemoteImageLoader.Result result) {
        if (result == null) return "";
        if (result.loaded()) return "Loaded";
        if (result.status > 0) return "HTTP " + result.status;
        return result.failure.message;
    }

    /** What Diagnostics prints, or empty when nothing has been attempted on this device. */
    public static String lastOutcome(Context context) {
        if (context == null) return "";
        try {
            return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .getString("rich_image_outcome", "");
        } catch (Exception ignored) {
            return "";
        }
    }

    /** When that happened, or 0. */
    public static long lastUpdated(Context context) {
        if (context == null) return 0L;
        try {
            return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .getLong("rich_image_updated", 0L);
        } catch (Exception ignored) {
            return 0L;
        }
    }
}
