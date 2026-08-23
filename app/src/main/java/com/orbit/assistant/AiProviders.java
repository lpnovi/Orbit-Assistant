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
 * automatically between providers yet. The registry is where future Hybrid Auto routing will
 * live — it already has the capability metadata and availability answers that decision needs —
 * but for this release explicit selection is the entire policy, so a deliberately chosen
 * provider is never silently substituted.
 */
public final class AiProviders {
    private static final ChatGptProvider CHATGPT = new ChatGptProvider();
    private static final OrbitLocalProvider LOCAL = new OrbitLocalProvider();
    private static final OpenRouterProvider OPENROUTER = new OpenRouterProvider();
    private static final RelayProvider RELAY = new RelayProvider();

    /** Presentation order for management UI: recommended first, advanced fallback last. */
    private static final List<AiProvider> ALL = Collections.unmodifiableList(
            Arrays.asList(CHATGPT, LOCAL, OPENROUTER, RELAY));

    private AiProviders() {}

    public static List<AiProvider> all() { return ALL; }

    public static AiProvider byId(String id) {
        for (AiProvider p : ALL) if (p.id().equals(id)) return p;
        return CHATGPT;
    }

    /** The provider the user has made active. Unknown or unselectable ids fall back to ChatGPT. */
    public static AiProvider active(Context c) {
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
        Prefs.get(c).edit().putString(Prefs.PROVIDER, id).apply();
        return true;
    }
}
