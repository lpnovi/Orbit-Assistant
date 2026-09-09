package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Whether a picture question is allowed to be answered from memory.
 *
 * <p><b>The bug this exists to close.</b> Every Rich Answers release from Beta 3 onward fixed a
 * stage that only runs once a page is known. Discovery reads cited pages, ranks what they contain,
 * downloads, decodes, de-duplicates. None of it happens without provenance, and provenance only
 * exists when the provider actually searched. "Show me pictures of a mallard duck" is exactly the
 * kind of question a language model answers happily and entirely from memory: no search call, no
 * cited page, no {@code Source:} line, and therefore no picture, on a build where every part of
 * the picture machinery is working perfectly. The trace said "Sources received: 0" and was right.
 *
 * <p><b>So the search is required rather than offered.</b> Every other request keeps the
 * arrangement it has always had - the hosted tool is available and the model decides. For the
 * narrow set of questions where the picture <em>is</em> the answer, {@link RichAnswerTrace.Intent
 * #STRONG_VISUAL}, the request tells the backend the tool must be used. That is the whole change.
 * It is one field in one request, it buys real cited pages, and real cited pages are the one thing
 * discovery cannot manufacture.
 *
 * <p><b>Narrow by construction.</b> Forcing is asked for only when the user has Rich Answers on,
 * only when the question is strongly visual on its own words, and never when the turn carries an
 * attached picture - a question about an image the user is holding is answered by that image, and
 * sending Orbit off to the web for it would be worse than useless.
 *
 * <p><b>It degrades instead of failing.</b> The Codex-backed endpoint is not a documented public
 * API and its handling of a forced hosted tool is not something Orbit may assume. A rejection that
 * names the field steps down one rung - the hosted-tool object, then the generic requirement, then
 * off - and the turn is retried immediately. The rung is committed, so the ladder is bounded at two
 * retries and the device stops asking for a form its backend refused. A user whose account will not
 * force a search gets exactly the behaviour of Beta 7, never an error.
 *
 * <p><b>It records counts, never content.</b> How many requests forced a search, how many of those
 * actually produced search events, and when the last one was. No prompt, no answer, no query.
 */
public final class HostedSearchPolicy {

    private static final String FILE = "orbit_hosted_search";
    private static final String KEY_MODE = "forced_mode";
    private static final String KEY_FORCED = "forced_requests";
    private static final String KEY_SEARCHED = "forced_requests_that_searched";
    private static final String KEY_LAST_AT = "last_forced_at";

    /** Ask for the hosted tool by name. The precise form, and the first thing tried. */
    public static final int MODE_HOSTED_TOOL = 0;
    /** Ask only that some tool be used. One tool is offered, so it means the same thing. */
    public static final int MODE_REQUIRED = 1;
    /** This backend refused both. Orbit stops asking and offers the tool as before. */
    public static final int MODE_UNAVAILABLE = 2;

    private HostedSearchPolicy() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Which rung of the ladder this device is on. */
    public static int mode(Context c) {
        return c == null ? MODE_UNAVAILABLE : prefs(c).getInt(KEY_MODE, MODE_HOSTED_TOOL);
    }

    /** Whether this backend has not yet refused every way of requiring a search. */
    public static boolean available(Context c) {
        return mode(c) != MODE_UNAVAILABLE;
    }

    /**
     * Whether this turn should require the hosted search rather than merely offer it.
     *
     * @param hasAttachedImages whether the user attached a picture to this turn. A question about
     *                          an image in hand is answered by that image.
     */
    public static boolean shouldForce(Context context, String prompt, boolean hasAttachedImages) {
        if (context == null || hasAttachedImages) return false;
        // Explicit "open a browser" intent keeps the external WEB_SEARCH action and is not offered
        // the hosted tool at all, so there is nothing here to require.
        if (!ChatGptClient.shouldOfferHostedWebSearch(prompt)) return false;
        // A user who has turned Rich Answers off has asked not to be given sourced pictures, and
        // must not be given slower answers to buy them.
        if (!RichAnswerCoordinator.enabled(context)) return false;
        if (!available(context)) return false;
        return RichAnswerRelevance.intentFor(prompt, "") == RichAnswerTrace.Intent.STRONG_VISUAL;
    }

    /**
     * The {@code tool_choice} value for the current rung.
     *
     * <p>A {@link JSONObject} naming the hosted tool, or the string {@code "required"}. Never
     * called on the unavailable rung, because {@link #shouldForce} has already said no.
     */
    public static Object toolChoice(Context context) throws Exception {
        return mode(context) == MODE_REQUIRED
                ? "required"
                : new JSONObject().put("type", "web_search");
    }

    /**
     * Whether a rejected request looks like a refusal of the forced tool choice specifically.
     *
     * <p>Deliberately narrow, for the same reason
     * {@link ReasoningSummarySupport#looksLikeSummaryRefusal} is. A 400 naming the tool-choice
     * field is the backend saying it will not do this; a 401, a 429, a 500, or a 400 about
     * anything else are real failures the user still has to be told about. Reading this
     * permissively would hide genuine errors behind a silent retry, so the default answer is no.
     */
    public static boolean looksLikeForcedSearchRefusal(int code, String body) {
        if (code != 400 && code != 422) return false;
        String lower = body == null ? "" : body.toLowerCase(Locale.US);
        if (lower.isEmpty()) return false;
        boolean namesField = lower.contains("tool_choice") || lower.contains("tool choice");
        if (!namesField) return false;
        return lower.contains("unsupported") || lower.contains("not supported")
                || lower.contains("unknown") || lower.contains("invalid")
                || lower.contains("unexpected") || lower.contains("allowed")
                || lower.contains("must be") || lower.contains("expected");
    }

    /**
     * Steps down one rung and commits, so the retry that follows reads the new value.
     *
     * <p>Committed rather than applied for exactly the reason the summary refusal is: the caller
     * re-enters the request on this thread and asks {@link #shouldForce} again, so "written" has to
     * mean written. It is a rare path on a background thread and the blocking write costs nothing.
     */
    public static void degrade(Context context) {
        if (context == null) return;
        int next = Math.min(MODE_UNAVAILABLE, mode(context) + 1);
        prefs(context).edit().putInt(KEY_MODE, next).commit();
    }

    /**
     * Records the outcome of one request that required a search.
     *
     * @param searchObserved whether anything in the response stream identified itself as hosted
     *                       web search. A forced request that produced no search at all is the
     *                       finding, and it is the one this whole class exists to make visible.
     */
    public static void recordForcedRequest(Context context, boolean searchObserved) {
        if (context == null) return;
        SharedPreferences p = prefs(context);
        SharedPreferences.Editor edit = p.edit()
                .putInt(KEY_FORCED, p.getInt(KEY_FORCED, 0) + 1)
                .putLong(KEY_LAST_AT, System.currentTimeMillis());
        if (searchObserved) edit.putInt(KEY_SEARCHED, p.getInt(KEY_SEARCHED, 0) + 1);
        edit.apply();
    }

    public static int forcedRequests(Context c) {
        return c == null ? 0 : prefs(c).getInt(KEY_FORCED, 0);
    }

    public static int forcedRequestsThatSearched(Context c) {
        return c == null ? 0 : prefs(c).getInt(KEY_SEARCHED, 0);
    }

    public static long lastForcedAt(Context c) {
        return c == null ? 0L : prefs(c).getLong(KEY_LAST_AT, 0L);
    }

    /** Empties the store. Used by tests and by a local-data reset. */
    public static void clear(Context context) {
        if (context == null) return;
        try {
            prefs(context).edit().clear().apply();
        } catch (Exception ignored) {}
    }

    // ---- reporting -------------------------------------------------------------------------------

    /** How this device is currently asking, in words. */
    public static String modeLabel(Context c) {
        switch (mode(c)) {
            case MODE_REQUIRED: return "required (generic form)";
            case MODE_UNAVAILABLE: return "off (backend refused)";
            default: return "required (hosted tool)";
        }
    }

    /** The readable block the Diagnostics section shows above the observed schema. */
    public static String body(Context context) {
        StringBuilder b = new StringBuilder();
        b.append("Search for picture requests: ").append(modeLabel(context)).append('\n');
        b.append("Requests that required a search: ").append(forcedRequests(context)).append('\n');
        b.append("Of those, searches observed: ").append(forcedRequestsThatSearched(context));
        long last = lastForcedAt(context);
        if (last > 0L) b.append("\nLast one: ").append(RichAnswerTrace.ago(last));
        if (forcedRequests(context) == 0) {
            b.append("\n\nOrbit requires a web search only for questions whose answer is a picture, "
                    + "and only while Rich Answers is on. Ask to see a picture of something, then "
                    + "come back here.");
        }
        return b.toString();
    }

    /** The one line the Diagnostics summary carries, or empty when nothing has been forced. */
    public static String summaryLine(Context context) {
        int forced = forcedRequests(context);
        if (forced == 0) return "";
        return String.format(Locale.US, "Forced visual search: %d requests, %d searched, %s",
                forced, forcedRequestsThatSearched(context), modeLabel(context));
    }
}
