package com.orbit.assistant;

import java.util.Locale;

/**
 * The models Orbit will send a request to, and what is true about each of them.
 *
 * <p>Orbit had three models and knew them as three string literals scattered across a preference
 * reader, a settings list, a status line and a test. Adding a fourth made that untenable: Astra is
 * not simply another id in the list, because it is available to some accounts and not others, it
 * refuses one of the reasoning efforts the others accept, and it is deliberately reachable only
 * when the user asks for it by name. Those are facts about a model, so they live with the model.
 *
 * <p><b>Astra is opt-in and stays opt-in.</b> Auto routing is untouched by this class: Fast still
 * means Luna, Balanced still means Terra, Deep still means Sol, and nothing Orbit decides on the
 * user's behalf reaches Astra. A model whose availability depends on an account and whose allowance
 * is consumed faster is not something to route somebody into because their question looked hard.
 * Choosing Custom and picking it is the only way there.
 *
 * <p>Nothing here claims to know what an account is entitled to. Orbit finds out the only honest
 * way, which is by making the request and reading what comes back; {@link #looksUnavailable} is
 * what turns that answer into something truthful to show, and it is deliberately narrow so an
 * ordinary network failure is never reported as "your account cannot do this".
 */
public final class OrbitModelCatalog {

    // ---- model ids (sent to the backend: never renamed) ----------------------------------------

    public static final String LUNA = "gpt-5.6-luna";
    public static final String TERRA = "gpt-5.6-terra";
    public static final String SOL = "gpt-5.6-sol";
    /**
     * The most capable model Orbit can reach, and the one with a real cost attached.
     *
     * <p>Its allowance is consumed faster than Sol's and its availability follows the user's own
     * ChatGPT/Codex account rather than anything Orbit controls.
     */
    public static final String ASTRA = "gpt-6-astra";

    /**
     * Every model the ChatGPT/Codex provider offers for Custom selection, weakest first.
     *
     * <p>The order is the order the picker shows, and it is ascending capability on purpose: the
     * one at the bottom is the one that costs the most, so nobody lands on it by scrolling past.
     */
    public static final String[] CHATGPT_MODELS = {LUNA, TERRA, SOL, ASTRA};

    /**
     * The models every other provider offers.
     *
     * <p>Astra is deliberately absent. Orbit's relay and OpenRouter paths have not been validated
     * against it, and offering a model in a picker is a promise that choosing it works; a picker
     * entry that produces a backend error is worse than no entry at all.
     */
    public static final String[] DEFAULT_MODELS = {LUNA, TERRA, SOL};

    private OrbitModelCatalog() {}

    /** The models the given provider may be asked for, in picker order. */
    public static String[] modelsFor(String providerId) {
        return Prefs.PROVIDER_CHATGPT.equals(providerId) ? CHATGPT_MODELS : DEFAULT_MODELS;
    }

    /** Whether this provider can be asked for this model at all. */
    public static boolean supports(String providerId, String modelId) {
        for (String known : modelsFor(providerId)) if (known.equals(modelId)) return true;
        return false;
    }

    /** True for Orbit's most capable model, whatever else the id carries. */
    public static boolean isAstra(String modelId) {
        return modelId != null && modelId.toLowerCase(Locale.US).contains("astra");
    }

    /**
     * Orbit's own name for a model, or empty for one it has no name for.
     *
     * <p>Never invents one. A status line that said "Reasoning with gpt-6-astra" would be leaking
     * an internal id into the interface, and one that guessed a friendly name for an unknown id
     * would be Orbit making something up about which model answered.
     */
    public static String displayName(String modelId) {
        String id = modelId == null ? "" : modelId.toLowerCase(Locale.US);
        if (id.contains("luna")) return "Luna";
        if (id.contains("terra")) return "Terra";
        if (id.contains("sol")) return "Sol";
        if (id.contains("astra")) return "Astra";
        return "";
    }

    /** "GPT-6 Astra", the way a model is written in Settings. Empty for an unknown id. */
    public static String settingsLabel(String modelId) {
        String name = displayName(modelId);
        if (name.isEmpty()) return "";
        return ASTRA.equals(modelId) ? "GPT-6 Astra" : name;
    }

    /**
     * The reasoning effort Orbit will actually send for one model.
     *
     * <p>The only rule that differs between models, and the reason it is here rather than in the
     * request builder: Astra has no {@code none}. Sending one would be an error rather than a
     * degraded request, so a user who had already chosen {@code none} for Custom and then picked
     * Astra would find every request failing for a reason nothing on screen explained.
     *
     * <p>The substitute is {@code low}, which is the nearest thing Astra has to "do not spend time
     * thinking". Everything else is passed through untouched, and Luna, Terra and Sol keep
     * {@code none} exactly as they always have.
     */
    public static String reasoningFor(String modelId, String requested) {
        String effort = requested == null ? "" : requested.trim().toLowerCase(Locale.US);
        if (!isAstra(modelId)) return effort;
        return effort.isEmpty() || "none".equals(effort) ? "low" : effort;
    }

    /**
     * Whether a provider error means "this account cannot reach this model", rather than anything
     * else that can go wrong.
     *
     * <p>Deliberately narrow, because the cost of being wrong runs in both directions. Reporting a
     * flat network failure as an entitlement problem would send somebody to look at their ChatGPT
     * subscription over a dropped connection; reporting a real entitlement problem as a network
     * failure would have them retrying forever. So this matches only the language a backend uses
     * when a model is genuinely not there for this caller, and everything else stays the ordinary
     * error it already was.
     */
    public static boolean looksUnavailable(String modelId, String error) {
        if (!isAstra(modelId) || error == null) return false;
        String message = error.toLowerCase(Locale.US);
        if (message.contains("timed out") || message.contains("timeout")
                || message.contains("interrupted") || message.contains("network")
                || message.contains("unable to resolve") || message.contains("connection")) {
            return false;
        }
        boolean namesModel = message.contains("astra") || message.contains("model");
        boolean refuses = message.contains("did not make") || message.contains("not found")
                || message.contains("does not exist") || message.contains("unsupported")
                || message.contains("not available") || message.contains("no access")
                || message.contains("not allowed") || message.contains("did not allow")
                || message.contains("unavailable");
        return namesModel && refuses;
    }

    /** What Orbit says when an account cannot reach Astra. Truthful, and blames nothing else. */
    public static String unavailableMessage() {
        return "Astra is not available through this ChatGPT account here yet. "
                + "Choose Sol, Terra, or Luna in Settings, or leave Orbit on Auto.";
    }

    /**
     * The line prefixed to an answer Sol produced after Astra turned out to be unavailable.
     *
     * <p>Non-negotiable, and the reason the fallback is allowed to exist at all. A silent
     * substitution would mean Orbit showing an answer while the interface still said Astra, which
     * is Orbit stating something untrue about its own work. Saying it plainly costs one sentence.
     */
    public static String fallbackNotice() {
        return "Astra was unavailable, so Orbit used Sol for this request.";
    }
}
