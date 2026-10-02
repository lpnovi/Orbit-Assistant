package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one place that decides what an AI selection means.
 *
 * <p>UI asks it what is legal, preferences and conversations store what it returns, and every
 * request path sends the result of {@link #resolve}. There is no routing here: a selection of
 * GPT-6 Luna goes to GPT-6 Luna, whatever the question looks like.
 *
 * <p><b>Auto (0.8.3.0-beta.5+).</b> {@link AiSelection#AUTO} is legal too, and is stored per chat
 * like any other selection. It is the only selection that permits routing, and the routing itself
 * lives in {@link SmartRouter}, never here: an explicit selection resolves to itself and nothing
 * else, and Auto resolves to Auto until the router turns one request into an exact selection.
 *
 * <p><b>Migration from intelligence modes.</b> Before 0.8.3.0 Orbit stored a mode (Auto, Fast,
 * Balanced, Deep, Custom) and decided the model per request. {@link #fromLegacy} maps those once,
 * deterministically, to an explicit selection; {@link #ensureMigrated} runs that mapping a single
 * time for the global default and for every saved chat, and never again once the new keys exist,
 * so a choice the user makes afterwards is never overwritten. The legacy keys are left in place,
 * unread, so a downgrade still finds what it expects.
 *
 * <p><b>Internal jobs.</b> {@link #SMART_VAULT_ENRICHMENT} is the one deliberate exception to "the
 * user's selection decides": Smart Vault's background suggestions are a small structured JSON job
 * that runs unattended for many items, so it always uses GPT-6 Luna at Low on ChatGPT, the cheapest
 * reliable option, rather than whatever expensive model a chat happens to be set to.
 */
public final class AiSelections {

    /** Present once the global default has been written in the new form. */
    static final String MIGRATED_CHATS = "ai_selection_chats_migrated";

    /** The broadly available default when nothing more specific can be preserved. */
    public static final AiSelection FALLBACK = AiSelection.of(Prefs.PROVIDER_CHATGPT,
            OrbitModelCatalog.LUNA, AiStrength.MEDIUM);

    /** Smart Vault enrichment's fixed configuration. See the class comment for why. */
    public static final AiSelection SMART_VAULT_ENRICHMENT = AiSelection.of(Prefs.PROVIDER_CHATGPT,
            OrbitModelCatalog.LUNA, AiStrength.LOW);

    // Legacy intelligence-mode values, read only by migration.
    static final String LEGACY_AUTO = "auto";
    static final String LEGACY_FAST = "fast";
    static final String LEGACY_BALANCED = "balanced";
    static final String LEGACY_DEEP = "deep";
    static final String LEGACY_CUSTOM = "custom";

    private AiSelections() {}

    // ---- validation ----------------------------------------------------------------------------

    /** Providers a selection may name. OpenRouter has no chat yet, so it is never one of them. */
    static String normalizeProvider(String provider) {
        String p = Prefs.normalizeProvider(provider);
        return OrbitModelCatalog.modelsFor(p).isEmpty() ? Prefs.PROVIDER_CHATGPT : p;
    }

    /**
     * The legal selection closest to what was asked for.
     *
     * <p>Unknown provider: ChatGPT. Retired model id: its successor. Model the provider does not
     * offer: the provider's first model, except that a dynamic-provider selection is preserved so
     * a temporarily absent catalog can report it unavailable rather than silently substituting.
     * Strength the model refuses: the nearest one it accepts
     * (None becomes Low). Model without strengths: no strength. Never returns null.
     */
    public static AiSelection resolve(AiSelection raw) {
        if (raw == null) return FALLBACK;
        // Auto is a legal selection in its own right, not a model to correct. It never carries a
        // strength: SmartRouter chooses one per request.
        if (raw.isAuto()) return AiSelection.AUTO;
        String provider = normalizeProvider(raw.provider);
        AiModelSpec spec = OrbitModelCatalog.spec(provider, raw.model);
        if (spec == null) spec = OrbitModelCatalog.spec(provider, OrbitModelCatalog.successorOf(raw.model));
        if (spec == null && (Prefs.PROVIDER_ANTHROPIC.equals(provider)
                || Prefs.PROVIDER_XAI.equals(provider))) {
            return AiSelection.of(provider, raw.model, null);
        }
        if (spec == null) spec = OrbitModelCatalog.defaultModel(provider);
        return AiSelection.of(provider, spec.id, spec.resolveStrength(raw.strength));
    }

    /** True when {@link #resolve} would return exactly this selection. */
    public static boolean isValid(AiSelection selection) {
        return selection != null && selection.equals(resolve(selection));
    }

    /** The selection after the user picks a provider: that provider's first model, at its default. */
    public static AiSelection withProvider(AiSelection current, String provider) {
        AiModelSpec spec = OrbitModelCatalog.defaultModel(normalizeProvider(provider));
        AiStrength keep = current == null ? null : current.strength;
        return resolve(AiSelection.of(provider, spec.id, keep));
    }

    /** Provider switch that restores that provider's last explicitly used model when possible. */
    public static AiSelection withProvider(Context c, AiSelection current, String provider) {
        String normalized = normalizeProvider(provider);
        AiSelection remembered = ModelLibraryStore.providerDefault(c, normalized);
        if (remembered == null) return withProvider(current, normalized);
        return resolve(remembered);
    }

    /**
     * The selection after the user picks a model. The current strength is kept when the new model
     * accepts it and otherwise moved to the nearest one it does, so Luna at None becomes Sol at Low.
     */
    public static AiSelection withModel(AiSelection current, String model) {
        AiSelection base = current == null ? FALLBACK : current;
        if (base.isAuto()) {
            // Leaving Auto for a named model: the model's own provider, at the model's default.
            for (String provider : new String[]{Prefs.PROVIDER_CHATGPT, Prefs.PROVIDER_ANTHROPIC,
                    Prefs.PROVIDER_XAI, Prefs.PROVIDER_LOCAL, Prefs.PROVIDER_RELAY}) {
                if (OrbitModelCatalog.spec(provider, model) != null) {
                    return resolve(AiSelection.of(provider, model, null));
                }
            }
            base = FALLBACK;
        }
        return resolve(AiSelection.of(base.provider, model, base.strength));
    }

    /** A strength belongs to a model, so Auto, which has neither, ignores it and stays Auto. */
    public static AiSelection withStrength(AiSelection current, AiStrength strength) {
        AiSelection base = current == null ? FALLBACK : current;
        return resolve(AiSelection.of(base.provider, base.model, strength));
    }

    /** What the pickers show for a selection's model: only strengths it really accepts. */
    public static List<AiStrength> strengthsFor(AiSelection selection) {
        AiSelection s = resolve(selection);
        AiModelSpec spec = OrbitModelCatalog.spec(s.provider, s.model);
        return spec == null ? new ArrayList<>() : spec.strengths;
    }

    public static AiModelSpec specFor(AiSelection selection) {
        AiSelection s = resolve(selection);
        return OrbitModelCatalog.spec(s.provider, s.model);
    }

    /** Providers the user may pick right now, in management order. */
    public static List<AiProvider> pickableProviders(Context c) {
        ProviderCatalogRepository.loadCached(c);
        List<AiProvider> result = new ArrayList<>();
        for (AiProvider provider : AiProviders.all()) {
            if (OrbitModelCatalog.modelsFor(provider.id()).isEmpty()) continue;
            if (provider.selectable(c)) result.add(provider);
        }
        return result;
    }

    // ---- global default ------------------------------------------------------------------------

    /** The selection new chats and the assistant start with. Migrates legacy settings first. */
    public static AiSelection globalDefault(Context c) {
        ProviderCatalogRepository.loadCached(c);
        ensureMigrated(c);
        SharedPreferences p = Prefs.get(c);
        AiSelection stored = resolve(AiSelection.of(p.getString(Prefs.PROVIDER, Prefs.PROVIDER_CHATGPT),
                p.getString(Prefs.AI_DEFAULT_MODEL, OrbitModelCatalog.LUNA),
                AiStrength.fromId(p.getString(Prefs.AI_DEFAULT_STRENGTH, ""))));
        // A default provider that can no longer answer (Orbit Local after its model was removed)
        // starts new chats on ChatGPT instead, the same rule AiProviders.active() follows, so
        // Settings and new chats agree. New chats show the model they use, so this is never
        // silent; existing chats keep their own selection and say plainly if it cannot run.
        if (!AiProviders.byId(stored.provider).selectable(c)) {
            return withProvider(stored, Prefs.PROVIDER_CHATGPT);
        }
        return stored;
    }

    /**
     * Changes the default for future chats. Existing chats keep their own selection.
     *
     * <p>Choosing Auto keeps the explicit default as it is (planning and other non-chat work still
     * use it) and only marks new chats as starting on Auto. Choosing an explicit selection clears
     * that mark, so the default is always exactly what the user chose last.
     */
    public static AiSelection setGlobalDefault(Context c, AiSelection selection) {
        ensureMigrated(c);
        AiSelection resolved = resolve(selection);
        if (resolved.isAuto()) {
            Prefs.get(c).edit().putBoolean(Prefs.AI_DEFAULT_AUTO, true).apply();
            return resolved;
        }
        Prefs.get(c).edit().putBoolean(Prefs.AI_DEFAULT_AUTO, false).apply();
        writeGlobal(c, resolved);
        ModelLibraryStore.rememberProviderDefault(c, resolved);
        return resolved;
    }

    /** True when the user chose Auto as the default for new chats. False after any upgrade. */
    public static boolean newChatsUseAuto(Context c) {
        return Prefs.get(c).getBoolean(Prefs.AI_DEFAULT_AUTO, false);
    }

    /**
     * What a brand-new chat starts with: Auto when the user chose it as the default, otherwise the
     * explicit {@link #globalDefault}. Planning, Smart Vault and other non-chat work never use this;
     * they keep the explicit default.
     */
    public static AiSelection newChatSelection(Context c) {
        return newChatsUseAuto(c) ? AiSelection.AUTO : globalDefault(c);
    }

    private static void writeGlobal(Context c, AiSelection s) {
        Prefs.get(c).edit()
                .putString(Prefs.PROVIDER, s.provider)
                .putString(Prefs.AI_DEFAULT_MODEL, s.model)
                .putString(Prefs.AI_DEFAULT_STRENGTH, s.effortId())
                .apply();
    }

    // ---- conversations -------------------------------------------------------------------------

    /** The selection one chat uses: its own when it has one, otherwise the global default. */
    public static AiSelection forConversation(Context c, String conversationId) {
        ensureMigrated(c);
        AiSelection stored = ConversationStore.selectionFor(c, conversationId);
        return stored == null ? newChatSelection(c) : resolve(stored);
    }

    /** Changes one chat. Every other chat and the global default are untouched. */
    public static AiSelection setForConversation(Context c, String conversationId, AiSelection selection) {
        AiSelection resolved = resolve(selection);
        ConversationStore.setSelection(c, conversationId, resolved);
        ModelLibraryStore.rememberProviderDefault(c, resolved);
        return resolved;
    }

    // ---- migration -----------------------------------------------------------------------------

    /**
     * The explicit selection an old mode stands for.
     *
     * <ul>
     *   <li>Custom: the stored model (retired ids moved to their successors) at the stored effort,
     *       resolved to the nearest legal strength.</li>
     *   <li>Fast: GPT-6 Luna at Low. Deep: GPT-6.1 Sol at High.</li>
     *   <li>Auto, Balanced, empty, unknown or corrupt: GPT-6 Luna at Medium. Auto's per-question
     *       routing is not preserved under any name.</li>
     *   <li>Orbit Local stays Orbit Local, which has no strength.</li>
     * </ul>
     */
    public static AiSelection fromLegacy(String provider, String mode, String customModel,
                                         String customReasoning) {
        String p = normalizeProvider(provider);
        if (Prefs.PROVIDER_LOCAL.equals(p)) {
            return resolve(AiSelection.of(p, OrbitModelCatalog.ORBIT_LOCAL, null));
        }
        String m = mode == null ? "" : mode.trim().toLowerCase(Locale.US);
        switch (m) {
            case LEGACY_CUSTOM:
                AiStrength effort = AiStrength.fromId(customReasoning);
                return resolve(AiSelection.of(p, OrbitModelCatalog.successorOf(customModel),
                        effort == null ? AiStrength.LOW : effort));
            case LEGACY_FAST:
                return resolve(AiSelection.of(p, OrbitModelCatalog.LUNA, AiStrength.LOW));
            case LEGACY_DEEP:
                return resolve(AiSelection.of(p, OrbitModelCatalog.SOL, AiStrength.HIGH));
            default:
                return resolve(AiSelection.of(p, OrbitModelCatalog.LUNA, AiStrength.MEDIUM));
        }
    }

    /**
     * Runs the one-time migration when it has not run yet. Cheap after the first call.
     *
     * <p>The global default migrates when the new model key is absent. Chats migrate once, behind
     * their own flag: a chat that had a mode keeps that mode's meaning, and a chat that followed the
     * global default is pinned to the migrated default, so changing the default later affects only
     * new chats.
     *
     * <p>Locks {@link ConversationStore}'s monitor, not this class's: a save already holding the
     * store's lock can arrive here, so taking the store's lock first everywhere is what keeps the
     * two from ever waiting on each other.
     */
    public static void ensureMigrated(Context c) {
        synchronized (ConversationStore.class) {
            migrate(c);
        }
    }

    private static void migrate(Context c) {
        SharedPreferences p = Prefs.get(c);
        if (!p.contains(Prefs.AI_DEFAULT_MODEL)) {
            writeGlobalNow(c, fromLegacy(p.getString(Prefs.PROVIDER, Prefs.PROVIDER_CHATGPT),
                    p.getString(Prefs.INTELLIGENCE_MODE, ""),
                    p.getString(Prefs.MODEL, ""), p.getString(Prefs.REASONING, "")));
        }
        if (!p.getBoolean(MIGRATED_CHATS, false)) {
            SharedPreferences fresh = Prefs.get(c);
            AiSelection global = resolve(AiSelection.of(
                    fresh.getString(Prefs.PROVIDER, Prefs.PROVIDER_CHATGPT),
                    fresh.getString(Prefs.AI_DEFAULT_MODEL, OrbitModelCatalog.LUNA),
                    AiStrength.fromId(fresh.getString(Prefs.AI_DEFAULT_STRENGTH, ""))));
            String provider = fresh.getString(Prefs.PROVIDER, Prefs.PROVIDER_CHATGPT);
            String model = fresh.getString(Prefs.MODEL, "");
            String reasoning = fresh.getString(Prefs.REASONING, "");
            ConversationStore.migrateSelections(c, legacyMode -> legacyMode == null
                    || legacyMode.trim().isEmpty()
                    ? global
                    : fromLegacy(provider, legacyMode, model, reasoning));
            Prefs.get(c).edit().putBoolean(MIGRATED_CHATS, true).commit();
        }
    }

    private static void writeGlobalNow(Context c, AiSelection s) {
        // commit, not apply: the chat migration below reads these values straight back.
        Prefs.get(c).edit()
                .putString(Prefs.PROVIDER, s.provider)
                .putString(Prefs.AI_DEFAULT_MODEL, s.model)
                .putString(Prefs.AI_DEFAULT_STRENGTH, s.effortId())
                .commit();
    }
}
