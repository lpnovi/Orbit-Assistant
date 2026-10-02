package com.orbit.assistant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The models Orbit will send a request to, and what is true about each of them.
 *
 * <p>Since 0.8.3.0 the user picks a provider, a model and a strength directly, so this is the
 * authority every picker and every request builder consults. Capability facts (which strengths a
 * model accepts, whether its availability depends on the account) are data here rather than
 * {@code if (astra)} checks in screens.
 *
 * <p>Current ChatGPT facts, checked against official OpenAI model pages on 2026-10-01: every model
 * below has a 1,050,000-token context window. GPT-6 Luna and the GPT-5.6 family accept none through
 * max; GPT-6.1 Sol and GPT-6 Astra accept low through max and refuse none.
 *
 * <p>Nothing here claims to know what an account is entitled to. Orbit finds out by making the
 * request; {@link #looksUnavailable} turns that answer into something truthful and is deliberately
 * narrow, so an ordinary network failure is never reported as "your account cannot do this".
 */
public final class OrbitModelCatalog {

    // ---- model ids (sent to the backend: never renamed) ----------------------------------------

    public static final String LUNA = "gpt-6-luna";
    public static final String SOL = "gpt-6.1-sol";
    public static final String ASTRA = "gpt-6-astra";
    public static final String GPT_5_6_LUNA = "gpt-5.6-luna";
    public static final String GPT_5_6_TERRA = "gpt-5.6-terra";
    public static final String GPT_5_6_SOL = "gpt-5.6-sol";
    /** Orbit Local's one on-device chat model. Never sent anywhere. */
    public static final String ORBIT_LOCAL = "orbit-local";

    public static final int OPENAI_CONTEXT_WINDOW = 1_050_000;

    private static final AiStrength[] LUNA_STRENGTHS = {AiStrength.NONE, AiStrength.LOW,
            AiStrength.MEDIUM, AiStrength.HIGH, AiStrength.XHIGH, AiStrength.MAX};
    private static final AiStrength[] NO_NONE_STRENGTHS = {AiStrength.LOW, AiStrength.MEDIUM,
            AiStrength.HIGH, AiStrength.XHIGH, AiStrength.MAX};

    private static final List<AiModelSpec> CHATGPT = Collections.unmodifiableList(Arrays.asList(
            new AiModelSpec(LUNA, "GPT-6 Luna", "GPT-6", Prefs.PROVIDER_CHATGPT,
                    "Quick everyday answers", AiStrength.MEDIUM, false, OPENAI_CONTEXT_WINDOW,
                    LUNA_STRENGTHS),
            new AiModelSpec(SOL, "GPT-6.1 Sol", "GPT-6", Prefs.PROVIDER_CHATGPT,
                    "Complex work and coding", AiStrength.MEDIUM, false, OPENAI_CONTEXT_WINDOW,
                    NO_NONE_STRENGTHS),
            new AiModelSpec(ASTRA, "GPT-6 Astra", "GPT-6", Prefs.PROVIDER_CHATGPT,
                    "Deepest reasoning; access varies by account", AiStrength.MEDIUM, true,
                    OPENAI_CONTEXT_WINDOW, NO_NONE_STRENGTHS),
            new AiModelSpec(GPT_5_6_LUNA, "GPT-5.6 Luna", "GPT-5.6", Prefs.PROVIDER_CHATGPT,
                    "Efficient everyday work", AiStrength.MEDIUM, false, OPENAI_CONTEXT_WINDOW,
                    LUNA_STRENGTHS),
            new AiModelSpec(GPT_5_6_TERRA, "GPT-5.6 Terra", "GPT-5.6", Prefs.PROVIDER_CHATGPT,
                    "Balanced intelligence and cost", AiStrength.MEDIUM, false,
                    OPENAI_CONTEXT_WINDOW, LUNA_STRENGTHS),
            new AiModelSpec(GPT_5_6_SOL, "GPT-5.6 Sol", "GPT-5.6", Prefs.PROVIDER_CHATGPT,
                    "Complex professional work", AiStrength.MEDIUM, false, OPENAI_CONTEXT_WINDOW,
                    LUNA_STRENGTHS)));

    /**
     * The relay forwards to the OpenAI API with the operator's own key, so it offers the models the
     * bundled relay server allows. Astra is absent: the relay path has not been validated against
     * it, and a picker entry that only produces a backend error is worse than none.
     */
    private static final List<AiModelSpec> RELAY = Collections.unmodifiableList(Arrays.asList(
            new AiModelSpec(LUNA, "GPT-6 Luna", "GPT-6", Prefs.PROVIDER_RELAY,
                    "Quick everyday answers", AiStrength.MEDIUM, false, OPENAI_CONTEXT_WINDOW,
                    LUNA_STRENGTHS),
            new AiModelSpec(SOL, "GPT-6.1 Sol", "GPT-6", Prefs.PROVIDER_RELAY,
                    "Complex work and coding", AiStrength.MEDIUM, false, OPENAI_CONTEXT_WINDOW,
                    NO_NONE_STRENGTHS)));

    /** Orbit Local has one model and no reasoning parameter, so it exposes no strengths. */
    private static final List<AiModelSpec> LOCAL = Collections.singletonList(
            new AiModelSpec(ORBIT_LOCAL, "Orbit Local", "Orbit Local", Prefs.PROVIDER_LOCAL,
                    "Private, on this phone", null, false, 0));

    private OrbitModelCatalog() {}

    /** The models a provider offers, in picker order. Empty for a provider with no chat yet. */
    public static List<AiModelSpec> modelsFor(String providerId) {
        if (Prefs.PROVIDER_CHATGPT.equals(providerId)) return CHATGPT;
        if (Prefs.PROVIDER_RELAY.equals(providerId)) return RELAY;
        if (Prefs.PROVIDER_LOCAL.equals(providerId)) return LOCAL;
        return Collections.emptyList();
    }

    /** The first model a provider offers, or null when it offers none. */
    public static AiModelSpec defaultModel(String providerId) {
        List<AiModelSpec> models = modelsFor(providerId);
        return models.isEmpty() ? null : models.get(0);
    }

    /** This provider's spec for this model, or null when the provider does not offer it. */
    public static AiModelSpec spec(String providerId, String modelId) {
        if (modelId == null) return null;
        for (AiModelSpec spec : modelsFor(providerId)) if (spec.id.equals(modelId)) return spec;
        return null;
    }

    /** Whether this provider can be asked for this model at all. */
    public static boolean supports(String providerId, String modelId) {
        return spec(providerId, modelId) != null;
    }

    /** Every current model id across providers, without duplicates. For tests and audits. */
    public static List<String> currentModelIds() {
        List<String> ids = new ArrayList<>();
        for (String provider : new String[]{Prefs.PROVIDER_CHATGPT, Prefs.PROVIDER_RELAY,
                Prefs.PROVIDER_LOCAL}) {
            for (AiModelSpec spec : modelsFor(provider)) if (!ids.contains(spec.id)) ids.add(spec.id);
        }
        return ids;
    }

    /**
     * The current model a retired id stands for, or the id unchanged when it is current.
     * GPT-5.6 is selectable again, so its three ids deliberately pass through unchanged.
     */
    public static String successorOf(String modelId) {
        if (modelId == null) return "";
        String id = modelId.trim();
        return id;
    }

    /**
     * Orbit's name for a model, or empty for one it has no name for.
     *
     * <p>Never invents one: a status line guessing a friendly name for an unknown id would be Orbit
     * making something up about which model answered.
     */
    public static String displayName(String modelId) {
        if (modelId == null) return "";
        for (String provider : new String[]{Prefs.PROVIDER_CHATGPT, Prefs.PROVIDER_LOCAL}) {
            AiModelSpec spec = spec(provider, modelId);
            if (spec != null) return spec.displayName;
        }
        return "";
    }

    /**
     * Whether a provider error means "this account cannot reach this model", rather than anything
     * else that can go wrong.
     *
     * <p>Generic across models: any model can be withdrawn or rolled out gradually. Deliberately
     * narrow, because the cost of being wrong runs both ways. A dropped connection reported as an
     * entitlement problem sends somebody to check their subscription; a real entitlement problem
     * reported as a network failure has them retrying forever. So transport failures never match,
     * and the error has to both concern a model and refuse it.
     */
    public static boolean looksUnavailable(String modelId, String error) {
        if (modelId == null || modelId.trim().isEmpty() || error == null) return false;
        String message = error.toLowerCase(Locale.US);
        if (message.contains("timed out") || message.contains("timeout")
                || message.contains("interrupted") || message.contains("network")
                || message.contains("unable to resolve") || message.contains("connection")
                || message.contains("unknownhost") || message.contains("dns")
                || message.contains("could not reach") || message.contains("socket")) {
            return false;
        }
        String id = modelId.toLowerCase(Locale.US);
        String name = displayName(modelId).toLowerCase(Locale.US);
        boolean namesModel = message.contains("model") || message.contains(id)
                || (!name.isEmpty() && message.contains(name));
        boolean refuses = message.contains("did not make") || message.contains("not found")
                || message.contains("does not exist") || message.contains("unsupported")
                || message.contains("not supported") || message.contains("not available")
                || message.contains("no access") || message.contains("not allowed")
                || message.contains("did not allow") || message.contains("unavailable")
                || message.contains("not have access") || message.contains("not entitled");
        return namesModel && refuses;
    }

    /**
     * What Orbit says when an account cannot reach a model. Truthful, keeps the user's choice, and
     * says how to pick another one; Orbit never quietly answers with a different model instead.
     */
    public static String unavailableMessage(String modelId) {
        String name = displayName(modelId);
        if (name.isEmpty()) name = "This model";
        return name + " is not available through this ChatGPT account right now. "
                + "Your selection has not been changed. Choose another model from the AI menu, "
                + "or use Retry with to try this message on a different one.";
    }
}
