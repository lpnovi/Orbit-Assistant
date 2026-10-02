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
    public static final String CLAUDE_FABLE_5_1 = "claude-fable-5-1";
    public static final String CLAUDE_OPUS_5_5 = "claude-opus-5-5";
    public static final String CLAUDE_SONNET_5_5 = "claude-sonnet-5-5";
    public static final String CLAUDE_HAIKU_4_5 = "claude-haiku-4-5-20251001";
    public static final String GROK_4_7 = "grok-4.7";
    /** OpenRouter's own router (0.8.3.0-beta.6+): an explicit OpenRouter model, never Orbit Auto. */
    public static final String OPENROUTER_AUTO = "openrouter/auto";
    /** Exact OpenRouter slugs Orbit Auto may use once OpenRouter is enabled for it. */
    public static final String OR_GPT_6_LUNA = "openai/gpt-6-luna";
    public static final String OR_GPT_6_1_SOL = "openai/gpt-6.1-sol";
    public static final String OR_CLAUDE_SONNET_5_5 = "anthropic/claude-sonnet-5.5";
    public static final String OR_CLAUDE_OPUS_5_5 = "anthropic/claude-opus-5.5";
    /** Orbit Local's one on-device chat model. Never sent anywhere. */
    public static final String ORBIT_LOCAL = "orbit-local";

    public static final int OPENAI_CONTEXT_WINDOW = 1_050_000;

    private static final AiStrength[] LUNA_STRENGTHS = {AiStrength.NONE, AiStrength.LOW,
            AiStrength.MEDIUM, AiStrength.HIGH, AiStrength.XHIGH, AiStrength.MAX};
    private static final AiStrength[] NO_NONE_STRENGTHS = {AiStrength.LOW, AiStrength.MEDIUM,
            AiStrength.HIGH, AiStrength.XHIGH, AiStrength.MAX};
    private static final AiStrength[] XAI_STRENGTHS = {AiStrength.LOW, AiStrength.MEDIUM,
            AiStrength.HIGH, AiStrength.XHIGH};

    private static final List<AiModelSpec> CHATGPT = Collections.unmodifiableList(Arrays.asList(
            fixedCloud(LUNA, "GPT-6 Luna", "GPT-6", Prefs.PROVIDER_CHATGPT,
                    "Quick everyday answers", AiStrength.MEDIUM, OPENAI_CONTEXT_WINDOW,
                    true, false, true, true, LUNA_STRENGTHS),
            fixedCloud(SOL, "GPT-6.1 Sol", "GPT-6", Prefs.PROVIDER_CHATGPT,
                    "Complex work and coding", AiStrength.MEDIUM, OPENAI_CONTEXT_WINDOW,
                    true, false, true, true, NO_NONE_STRENGTHS),
            cloud(ASTRA, "GPT-6 Astra", "GPT-6", Prefs.PROVIDER_CHATGPT,
                    "Deep reasoning; access varies by account", AiStrength.MEDIUM,
                    OPENAI_CONTEXT_WINDOW, true, false, true, true, NO_NONE_STRENGTHS),
            fixedCloud(GPT_5_6_LUNA, "GPT-5.6 Luna", "GPT-5.6", Prefs.PROVIDER_CHATGPT,
                    "Efficient everyday work", AiStrength.MEDIUM, OPENAI_CONTEXT_WINDOW,
                    true, false, true, true, LUNA_STRENGTHS),
            fixedCloud(GPT_5_6_TERRA, "GPT-5.6 Terra", "GPT-5.6", Prefs.PROVIDER_CHATGPT,
                    "Balanced intelligence and cost", AiStrength.MEDIUM, OPENAI_CONTEXT_WINDOW,
                    true, false, true, true, LUNA_STRENGTHS),
            fixedCloud(GPT_5_6_SOL, "GPT-5.6 Sol", "GPT-5.6", Prefs.PROVIDER_CHATGPT,
                    "Complex professional work", AiStrength.MEDIUM, OPENAI_CONTEXT_WINDOW,
                    true, false, true, true, LUNA_STRENGTHS)));

    /**
     * The relay forwards to the OpenAI API with the operator's own key, so it offers the models the
     * bundled relay server allows. Astra is absent: the relay path has not been validated against
     * it, and a picker entry that only produces a backend error is worse than none.
     */
    private static final List<AiModelSpec> RELAY = Collections.unmodifiableList(Arrays.asList(
            new AiModelSpec(LUNA, "GPT-6 Luna", "GPT-6", Prefs.PROVIDER_RELAY,
                    "Quick everyday answers", AiStrength.MEDIUM, false, OPENAI_CONTEXT_WINDOW,
                    true, false, true, true, false, false, "static_official", "active",
                    LUNA_STRENGTHS),
            new AiModelSpec(SOL, "GPT-6.1 Sol", "GPT-6", Prefs.PROVIDER_RELAY,
                    "Complex work and coding", AiStrength.MEDIUM, false, OPENAI_CONTEXT_WINDOW,
                    true, false, true, true, false, false, "static_official", "active",
                    NO_NONE_STRENGTHS)));

    /** Orbit Local has one model and no reasoning parameter, so it exposes no strengths. */
    private static final List<AiModelSpec> LOCAL = Collections.singletonList(
            new AiModelSpec(ORBIT_LOCAL, "Orbit Local", "Orbit Local", Prefs.PROVIDER_LOCAL,
                    "Private, on this phone", null, false, 0));

    /** Trusted offline baseline, verified against Anthropic's model overview on 2026-10-02. */
    private static final List<AiModelSpec> ANTHROPIC = Collections.unmodifiableList(Arrays.asList(
            cloud(CLAUDE_OPUS_5_5, "Claude Opus 5.5", "Claude 5", Prefs.PROVIDER_ANTHROPIC,
                    "Long-running knowledge and coding work", AiStrength.MEDIUM, 1_000_000,
                    true, true, true, false, NO_NONE_STRENGTHS),
            cloud(CLAUDE_SONNET_5_5, "Claude Sonnet 5.5", "Claude 5", Prefs.PROVIDER_ANTHROPIC,
                    "Fast, capable general work", AiStrength.HIGH, 1_000_000,
                    true, true, true, false, NO_NONE_STRENGTHS),
            cloud(CLAUDE_FABLE_5_1, "Claude Fable 5.1", "Claude 5", Prefs.PROVIDER_ANTHROPIC,
                    "Demanding reasoning and long tasks", AiStrength.HIGH, 1_000_000,
                    true, true, true, false, NO_NONE_STRENGTHS),
            cloud(CLAUDE_HAIKU_4_5, "Claude Haiku 4.5", "Claude 4.5", Prefs.PROVIDER_ANTHROPIC,
                    "Fast everyday answers", null, 200_000,
                    true, true, true, false, new AiStrength[0])));

    /** Trusted fallback while xAI's account-scoped language-model catalog is unavailable. */
    private static final List<AiModelSpec> XAI = Collections.singletonList(
            cloud(GROK_4_7, "Grok 4.7", "Grok 4", Prefs.PROVIDER_XAI,
                    "Current xAI chat model", AiStrength.HIGH, 500_000,
                    true, false, true, true, XAI_STRENGTHS));

    /**
     * Trusted offline OpenRouter baseline (0.8.3.0-beta.6+), checked against OpenRouter's public
     * model catalog on 2026-10-02: Orbit Auto's curated OpenRouter routes and OpenRouter Auto, so
     * OpenRouter is usable before its first catalog refresh. The live catalog replaces it. The
     * first entry is what choosing OpenRouter starts on, so it is a known model, not the router.
     *
     * <p>OpenRouter Auto's downstream model varies per request, so it has no fixed context window
     * and no strength control: neither its 2M routing figure nor any one model's efforts would be
     * true of every answer it gives.
     */
    private static final List<AiModelSpec> OPENROUTER = Collections.unmodifiableList(Arrays.asList(
            openRouter(OR_GPT_6_LUNA, "GPT-6 Luna", "OpenAI", "OpenAI via OpenRouter",
                    AiStrength.MEDIUM, OPENAI_CONTEXT_WINDOW, true, LUNA_STRENGTHS),
            openRouter(OR_GPT_6_1_SOL, "GPT-6.1 Sol", "OpenAI", "OpenAI via OpenRouter",
                    AiStrength.MEDIUM, OPENAI_CONTEXT_WINDOW, true, NO_NONE_STRENGTHS),
            openRouter(OR_CLAUDE_SONNET_5_5, "Claude Sonnet 5.5", "Anthropic",
                    "Anthropic via OpenRouter", AiStrength.HIGH, 1_000_000, true,
                    NO_NONE_STRENGTHS),
            openRouter(OR_CLAUDE_OPUS_5_5, "Claude Opus 5.5", "Anthropic",
                    "Anthropic via OpenRouter", AiStrength.HIGH, 1_000_000, true,
                    NO_NONE_STRENGTHS),
            openRouter(OPENROUTER_AUTO, "OpenRouter Auto", "OpenRouter",
                    "OpenRouter picks a model for each request", null, 0, true)));

    private static volatile List<AiModelSpec> dynamicAnthropic = Collections.emptyList();
    private static volatile List<AiModelSpec> dynamicXai = Collections.emptyList();
    private static volatile List<AiModelSpec> dynamicOpenRouter = Collections.emptyList();

    /** One OpenRouter catalog entry. Tools and web search stay off: Orbit sends neither there. */
    static AiModelSpec openRouter(String id, String name, String family, String description,
                                  AiStrength defaultStrength, int context, boolean vision,
                                  AiStrength... strengths) {
        return new AiModelSpec(id, name, family, Prefs.PROVIDER_OPENROUTER, description,
                defaultStrength, true, context, vision, false, true, false, false, true,
                "static_official", "active", strengths);
    }

    private static AiModelSpec cloud(String id, String name, String family, String provider,
                                     String description, AiStrength defaultStrength, int context,
                                     boolean vision, boolean nativeFiles, boolean tools,
                                     boolean webSearch, AiStrength... strengths) {
        return new AiModelSpec(id, name, family, provider, description, defaultStrength, true,
                context, vision, nativeFiles, true, tools, webSearch, true,
                "static_official", "active", strengths);
    }

    /** Static catalog entry whose availability is not described as account-discovered metadata. */
    private static AiModelSpec fixedCloud(String id, String name, String family, String provider,
                                          String description, AiStrength defaultStrength, int context,
                                          boolean vision, boolean nativeFiles, boolean tools,
                                          boolean webSearch, AiStrength... strengths) {
        return new AiModelSpec(id, name, family, provider, description, defaultStrength, false,
                context, vision, nativeFiles, true, tools, webSearch, true,
                "static_official", "active", strengths);
    }

    private OrbitModelCatalog() {}

    /** The models a provider offers, in picker order. Empty for a provider with no chat yet. */
    public static List<AiModelSpec> modelsFor(String providerId) {
        if (Prefs.PROVIDER_CHATGPT.equals(providerId)) return CHATGPT;
        if (Prefs.PROVIDER_RELAY.equals(providerId)) return RELAY;
        if (Prefs.PROVIDER_LOCAL.equals(providerId)) return LOCAL;
        if (Prefs.PROVIDER_ANTHROPIC.equals(providerId)) return dynamicAnthropic.isEmpty()
                ? ANTHROPIC : dynamicAnthropic;
        if (Prefs.PROVIDER_XAI.equals(providerId)) return dynamicXai.isEmpty() ? XAI : dynamicXai;
        if (Prefs.PROVIDER_OPENROUTER.equals(providerId)) return dynamicOpenRouter.isEmpty()
                ? OPENROUTER : dynamicOpenRouter;
        return Collections.emptyList();
    }

    /** Installs a validated cached/discovered catalog atomically; an empty refresh changes nothing. */
    static void installDynamic(String providerId, List<AiModelSpec> models) {
        if (models == null || models.isEmpty()) return;
        List<AiModelSpec> copy = Collections.unmodifiableList(new ArrayList<>(models));
        if (Prefs.PROVIDER_ANTHROPIC.equals(providerId)) dynamicAnthropic = copy;
        if (Prefs.PROVIDER_XAI.equals(providerId)) dynamicXai = copy;
        if (Prefs.PROVIDER_OPENROUTER.equals(providerId)) dynamicOpenRouter = copy;
    }

    /**
     * Providers whose model list comes from the account's live catalog. A selection naming a model
     * such a catalog does not list right now is kept as it is, so it can be reported unavailable
     * instead of silently becoming a different model.
     */
    /** True when a live or cached catalog, rather than the trusted baseline, is installed. */
    static boolean hasDynamic(String providerId) {
        if (Prefs.PROVIDER_ANTHROPIC.equals(providerId)) return !dynamicAnthropic.isEmpty();
        if (Prefs.PROVIDER_XAI.equals(providerId)) return !dynamicXai.isEmpty();
        if (Prefs.PROVIDER_OPENROUTER.equals(providerId)) return !dynamicOpenRouter.isEmpty();
        return false;
    }

    public static boolean isDynamicProvider(String providerId) {
        return Prefs.PROVIDER_ANTHROPIC.equals(providerId) || Prefs.PROVIDER_XAI.equals(providerId)
                || Prefs.PROVIDER_OPENROUTER.equals(providerId);
    }

    static void clearDynamicForTest() {
        dynamicAnthropic = Collections.emptyList();
        dynamicXai = Collections.emptyList();
        dynamicOpenRouter = Collections.emptyList();
    }

    /** The first model a provider offers, or null when it offers none. */
    public static AiModelSpec defaultModel(String providerId) {
        List<AiModelSpec> models = modelsFor(providerId);
        for (AiModelSpec model : models) if (model.selectable()) return model;
        return models.isEmpty() ? null : models.get(0);
    }

    /** This provider's spec for this model, or null when the provider does not offer it. */
    public static AiModelSpec spec(String providerId, String modelId) {
        if (modelId == null) return null;
        for (AiModelSpec spec : modelsFor(providerId)) if (spec.id.equals(modelId)) return spec;
        return null;
    }

    /** Display-only tombstone for a Favorite or old chat absent from the current dynamic catalog. */
    static AiModelSpec unavailableReference(String providerId, String modelId) {
        String id = modelId == null || modelId.trim().isEmpty() ? "Unknown model" : modelId.trim();
        return new AiModelSpec(id, id, "Unavailable", providerId,
                "Not reported by the provider's current catalog", null, true, 0,
                false, false, true, false, false, true,
                "historical_reference", "unavailable");
    }

    /** Whether this provider can be asked for this model at all. */
    public static boolean supports(String providerId, String modelId) {
        return spec(providerId, modelId) != null;
    }

    /** Every current model id across providers, without duplicates. For tests and audits. */
    public static List<String> currentModelIds() {
        List<String> ids = new ArrayList<>();
        for (String provider : new String[]{Prefs.PROVIDER_CHATGPT, Prefs.PROVIDER_ANTHROPIC,
                Prefs.PROVIDER_XAI, Prefs.PROVIDER_RELAY, Prefs.PROVIDER_LOCAL}) {
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
        for (String provider : new String[]{Prefs.PROVIDER_CHATGPT, Prefs.PROVIDER_ANTHROPIC,
                Prefs.PROVIDER_XAI, Prefs.PROVIDER_RELAY, Prefs.PROVIDER_LOCAL, Prefs.PROVIDER_OPENROUTER}) {
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
        if (name.isEmpty()) name = modelId == null || modelId.trim().isEmpty()
                ? "This model" : modelId.trim();
        String account = spec(Prefs.PROVIDER_CHATGPT, modelId) == null
                ? "the selected provider account" : "this ChatGPT account";
        return name + " is not available through " + account + " right now. "
                + "Your selection has not been changed. Choose another model from the AI menu, "
                + "or use Retry with to try this message on a different one.";
    }
}
