package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * What Orbit has actually observed about reasoning summaries on this device's ChatGPT backend.
 *
 * <p>{@link AiCapabilities#reasoningSummaries} is a statement about a provider's protocol: the
 * ChatGPT path speaks a Responses-shaped event stream, which defines user-facing reasoning-summary
 * events, so the capability is true. Whether the account-backed backend behind that protocol
 * honours the request is a different question, it is answered by the server rather than by Orbit,
 * and it cannot be assumed from the fact that the models are reasoning models.
 *
 * <p>So it is observed rather than assumed, and the observation is deliberately cheap and safe.
 * Asking is free when it works. When it does not, the request is rejected before anything is
 * generated, which costs the user nothing, and Orbit stops asking on this device and falls back to
 * describing its own execution instead. A single refusal is enough to stop asking; a single real
 * summary is enough to know it works.
 *
 * <p>Nothing here stores any summary. Only a tri-state answer to "does this backend produce them",
 * plus a count and a timestamp for Diagnostics. No text a model or a person produced is written by
 * this class, or readable from it.
 */
public final class ReasoningSummarySupport {
    private static final String FILE = "orbit_reasoning_summary";
    private static final String KEY_STATE = "state";
    private static final String KEY_COUNT = "updates_received";
    private static final String KEY_LAST_AT = "last_update_at";
    private static final String KEY_LAST_SOURCE = "last_source";
    private static final String KEY_HANDOFF = "last_request_reached_answer";

    /** Never asked, or the answer is not known yet. Orbit may ask. */
    public static final int UNKNOWN = 0;
    /** A real user-facing summary has arrived from this backend. */
    public static final int SUPPORTED = 1;
    /** This backend refused the summary request. Orbit stops asking. */
    public static final int UNSUPPORTED = -1;

    private ReasoningSummarySupport() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static int state(Context c) {
        return c == null ? UNKNOWN : prefs(c).getInt(KEY_STATE, UNKNOWN);
    }

    /** True unless this backend has already refused. Unknown counts as worth one attempt. */
    public static boolean mayRequest(Context c) {
        return state(c) != UNSUPPORTED;
    }

    /** A human answer for Diagnostics: yes, no, or not yet established. */
    public static String stateLabel(Context c) {
        switch (state(c)) {
            case SUPPORTED: return "yes";
            case UNSUPPORTED: return "no";
            default: return "unknown";
        }
    }

    /** Remembers the outcome of one request that asked for summaries. */
    static void record(Context c, boolean sawSummary) {
        if (c == null || !sawSummary) return;
        if (state(c) != SUPPORTED) prefs(c).edit().putInt(KEY_STATE, SUPPORTED).apply();
    }

    /**
     * Committed rather than applied. The refusal path immediately re-enters the request and reads
     * this back to decide not to ask again, so "written" has to mean written, not scheduled. It is
     * a rare path on a background thread, so the blocking write costs nothing that matters.
     */
    static void markUnsupported(Context c) {
        if (c != null) prefs(c).edit().putInt(KEY_STATE, UNSUPPORTED).commit();
    }

    /**
     * Whether a rejected request looks like a refusal of the summary field specifically.
     *
     * <p>Deliberately narrow. A 400 naming the reasoning-summary parameter is the backend saying
     * it will not do this; a 401, a 429, a 500, or a 400 about something else are ordinary
     * failures that Orbit must keep reporting exactly as it did before. Getting this wrong in the
     * permissive direction would silently swallow real errors behind a retry, so the default
     * answer is no.
     */
    static boolean looksLikeSummaryRefusal(int code, String body) {
        if (code != 400 && code != 422) return false;
        String lower = body == null ? "" : body.toLowerCase(Locale.US);
        if (lower.isEmpty()) return false;
        boolean namesSummary = lower.contains("summary");
        boolean namesReasoning = lower.contains("reasoning");
        return namesSummary && (namesReasoning || lower.contains("unsupported")
                || lower.contains("unknown") || lower.contains("not supported")
                || lower.contains("invalid"));
    }

    /**
     * Counts one update that actually reached a surface, and remembers which kind it was.
     *
     * <p>The stage token is Orbit's own enum name. The update's wording is never passed here and
     * has nowhere to go if it were: this store has no field for it.
     */
    public static void recordDisplayed(Context c, ThinkingUpdate update) {
        if (c == null || update == null) return;
        SharedPreferences p = prefs(c);
        p.edit()
                .putInt(KEY_COUNT, p.getInt(KEY_COUNT, 0) + 1)
                .putLong(KEY_LAST_AT, System.currentTimeMillis())
                .putString(KEY_LAST_SOURCE, update.fromProvider() ? "provider-summary" : "orbit-progress")
                .apply();
    }

    /** Whether the most recent request that showed a status went on to stream an answer. */
    public static void recordHandoff(Context c, boolean reachedAnswer) {
        if (c != null) prefs(c).edit().putBoolean(KEY_HANDOFF, reachedAnswer).apply();
    }

    public static int updatesReceived(Context c) {
        return c == null ? 0 : prefs(c).getInt(KEY_COUNT, 0);
    }

    public static long lastUpdateAt(Context c) {
        return c == null ? 0L : prefs(c).getLong(KEY_LAST_AT, 0L);
    }

    /** "provider-summary", "orbit-progress", or "none". Never the text of anything. */
    public static String lastSource(Context c) {
        String value = c == null ? "" : prefs(c).getString(KEY_LAST_SOURCE, "");
        return value == null || value.isEmpty() ? "none" : value;
    }

    public static boolean lastRequestReachedAnswer(Context c) {
        return c != null && prefs(c).getBoolean(KEY_HANDOFF, false);
    }
}
