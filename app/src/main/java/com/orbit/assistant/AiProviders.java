package com.orbit.assistant;

import android.content.Context;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The provider registry and router.
 *
 * <p>This is the only place that knows which {@link AiProvider} implementations exist. The
 * active provider is the user's explicit choice in {@link Prefs#PROVIDER}; nothing here routes
 * between providers. Auto's routing (0.8.3.0-beta.5+) lives in {@link SmartRouter} and finishes
 * before a request is queued, so by the time a selection reaches this registry it already names
 * one exact provider and model, and a deliberately chosen provider is never silently substituted.
 */
public final class AiProviders {
    private static final ChatGptProvider CHATGPT = new ChatGptProvider();
    private static final AnthropicProvider ANTHROPIC = new AnthropicProvider();
    private static final XaiProvider XAI = new XaiProvider();
    private static final OrbitLocalProvider LOCAL = new OrbitLocalProvider();
    private static final OpenRouterProvider OPENROUTER = new OpenRouterProvider();
    private static final RelayProvider RELAY = new RelayProvider();

    /** Presentation order for management UI: recommended first, advanced fallback last. */
    private static final List<AiProvider> ALL = Collections.unmodifiableList(
            Arrays.asList(CHATGPT, ANTHROPIC, XAI, LOCAL, OPENROUTER, RELAY));

    private AiProviders() {}

    public static List<AiProvider> all() { return ALL; }

    public static AiProvider byId(String id) {
        for (AiProvider p : ALL) if (p.id().equals(id)) return p;
        return CHATGPT;
    }

    /** A provider tests put in place of the real ones; never set in production. */
    private static volatile AiProvider testOverride;

    static AiProvider installForTest(AiProvider provider) {
        AiProvider previous = testOverride;
        testOverride = provider;
        return previous;
    }

    /**
     * The provider one request goes to: the one its selection names.
     *
     * <p>Deliberately not {@link #active}'s silent fall-back to ChatGPT. A chat set to Orbit Local
     * whose model has since been removed gets Orbit Local's own plain explanation, rather than an
     * answer from a different provider under a header that still says Orbit Local.
     */
    public static AiProvider forSelection(Context c, AiSelection selection) {
        AiProvider override = testOverride;
        if (override != null) return override;
        return byId(AiSelections.resolve(selection).provider);
    }

    /** The default provider: the one new chats and the assistant start with. */
    public static AiProvider active(Context c) {
        AiProvider override = testOverride;
        if (override != null) return override;
        AiProvider chosen = byId(Prefs.provider(c));
        return chosen.selectable(c) ? chosen : CHATGPT;
    }

    /**
     * Makes a provider the active one. Refused for providers that cannot serve chat, so the
     * stored preference never points at a backend that answers every request with an error by
     * design.
     */
    public static boolean select(Context c, String id) {
        AiProvider chosen = byId(id);
        if (!chosen.id().equals(id) || !chosen.selectable(c)) return false;
        // Through the selection layer, so the default model and strength stay legal for the new
        // provider. Only the default for new chats changes; existing chats keep their own.
        if (OrbitModelCatalog.modelsFor(id).isEmpty()) {
            Prefs.get(c).edit().putString(Prefs.PROVIDER, id).apply();
        } else {
            // Compared with what is stored, not with the resolved default: a stored provider that can
            // no longer answer already resolves to ChatGPT, and must still be replaced on disk.
            if (!id.equals(Prefs.provider(c))) {
                AiSelections.setGlobalDefault(c,
                        AiSelections.withProvider(c, AiSelections.globalDefault(c), id));
            }
        }
        return true;
    }
}
