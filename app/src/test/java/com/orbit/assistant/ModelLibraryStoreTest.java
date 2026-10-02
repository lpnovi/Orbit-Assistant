package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.Collections;

/** Favorites, Recents, provider defaults, unavailable references, and searchable metadata. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class ModelLibraryStoreTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        OrbitModelCatalog.clearDynamicForTest();
    }

    @Test public void favoritesPersistAcrossProvidersAndMissingDynamicModelsRemainRemembered() {
        ModelLibraryStore.setFavorite(context, Prefs.PROVIDER_CHATGPT,
                OrbitModelCatalog.GPT_5_6_TERRA, true);
        ModelLibraryStore.setFavorite(context, Prefs.PROVIDER_ANTHROPIC, "claude-retired", true);
        assertTrue(ModelLibraryStore.isFavorite(context, Prefs.PROVIDER_CHATGPT,
                OrbitModelCatalog.GPT_5_6_TERRA));
        List<AiSelection> favorites = ModelLibraryStore.favorites(context);
        assertEquals(2, favorites.size());
        assertEquals("claude-retired", favorites.get(1).model);
        assertFalse("a missing favorite is preserved but cannot be selected",
                OrbitModelCatalog.unavailableReference(Prefs.PROVIDER_ANTHROPIC,
                        favorites.get(1).model).selectable());
    }

    @Test public void recentsAreDeduplicatedNewestFirstAndBounded() {
        for (int i = 0; i < 9; i++) {
            ModelLibraryStore.recordRecent(context, AiSelection.of(Prefs.PROVIDER_XAI,
                    "grok-test-" + i, null));
        }
        ModelLibraryStore.recordRecent(context, AiSelection.of(Prefs.PROVIDER_XAI,
                "grok-test-5", null));
        List<AiSelection> recent = ModelLibraryStore.recents(context);
        assertEquals(ModelLibraryStore.MAX_RECENTS, recent.size());
        assertEquals("grok-test-5", recent.get(0).model);
        int count = 0;
        for (AiSelection item : recent) if ("grok-test-5".equals(item.model)) count++;
        assertEquals(1, count);
    }

    @Test public void providerDefaultsAreIndependentAndDoNotChangeTheGlobalDefault() {
        AiSelection global = AiSelections.setGlobalDefault(context, AiSelection.of(
                Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.GPT_5_6_TERRA, AiStrength.MEDIUM));
        AiSelection claude = AiSelection.of(Prefs.PROVIDER_ANTHROPIC,
                OrbitModelCatalog.CLAUDE_SONNET_5_5, AiStrength.HIGH);
        ModelLibraryStore.rememberProviderDefault(context, claude);
        assertEquals(claude, ModelLibraryStore.providerDefault(context, Prefs.PROVIDER_ANTHROPIC));
        assertEquals("switching providers restores that provider's model and strength", claude,
                AiSelections.withProvider(context, global, Prefs.PROVIDER_ANTHROPIC));
        assertEquals(global, AiSelections.globalDefault(context));
    }

    @Test public void portableStateRoundTripsButCatalogCacheDoesNotEnterPrefsBackup() throws Exception {
        AiSelection claude = AiSelection.of(Prefs.PROVIDER_ANTHROPIC,
                OrbitModelCatalog.CLAUDE_SONNET_5_5, AiStrength.HIGH);
        ModelLibraryStore.setFavorite(context, claude.provider, claude.model, true);
        ModelLibraryStore.recordRecent(context, claude);
        org.json.JSONObject snapshot = Prefs.backupSnapshot(context);
        String raw = snapshot.toString();
        assertTrue(raw.contains(Prefs.AI_MODEL_FAVORITES));
        assertTrue(raw.contains(Prefs.AI_MODEL_RECENTS));
        assertTrue(raw.contains(Prefs.AI_PROVIDER_DEFAULTS));
        assertFalse(raw.contains("orbit_model_catalog_cache"));
        Prefs.get(context).edit().clear().commit();
        assertTrue(Prefs.restoreBackupSnapshot(context, snapshot));
        assertTrue(ModelLibraryStore.isFavorite(context, claude.provider, claude.model));
        assertEquals(claude.model, ModelLibraryStore.recents(context).get(0).model);
        assertEquals(claude, ModelLibraryStore.providerDefault(context, claude.provider));
    }

    @Test public void dynamicUnknownSelectionIsPreservedRatherThanSilentlySubstituted() {
        AiSelection missing = AiSelection.of(Prefs.PROVIDER_XAI, "grok-removed", AiStrength.HIGH);
        AiSelection resolved = AiSelections.resolve(missing);
        assertEquals(Prefs.PROVIDER_XAI, resolved.provider);
        assertEquals("grok-removed", resolved.model);
        assertEquals(null, resolved.strength);
    }

    @Test public void modelSearchCoversNameFamilyAndProvider() {
        AiModelSpec claude = OrbitModelCatalog.spec(Prefs.PROVIDER_ANTHROPIC,
                OrbitModelCatalog.CLAUDE_OPUS_5_5);
        assertTrue(ModelLibraryDialog.matches(claude, "Anthropic Claude", "opus"));
        assertTrue(ModelLibraryDialog.matches(claude, "Anthropic Claude", "anthropic"));
        assertTrue(ModelLibraryDialog.matches(claude, "Anthropic Claude", "claude 5"));
        assertFalse(ModelLibraryDialog.matches(claude, "Anthropic Claude", "grok"));
    }

    @Test public void contextMetadataBelongsToTheSelectedProviderModel() {
        assertEquals(1_050_000, AiSelections.specFor(AiSelection.of(Prefs.PROVIDER_CHATGPT,
                OrbitModelCatalog.LUNA, AiStrength.MEDIUM)).contextWindowTokens);
        assertEquals(1_000_000, AiSelections.specFor(AiSelection.of(Prefs.PROVIDER_ANTHROPIC,
                OrbitModelCatalog.CLAUDE_SONNET_5_5, AiStrength.HIGH)).contextWindowTokens);
        assertEquals(500_000, AiSelections.specFor(AiSelection.of(Prefs.PROVIDER_XAI,
                OrbitModelCatalog.GROK_4_7, AiStrength.HIGH)).contextWindowTokens);
    }

    @Test public void responseDetailsKeepTheHistoricalDynamicDisplayName() throws Exception {
        AiModelSpec dynamic = new AiModelSpec("grok-account-model", "Grok Account Model", "Grok",
                Prefs.PROVIDER_XAI, "Account model", null, true, 250_000);
        OrbitModelCatalog.installDynamic(Prefs.PROVIDER_XAI, Collections.singletonList(dynamic));
        ResponseDetails written = ResponseDetails.sentWith(AiSelection.of(Prefs.PROVIDER_XAI,
                dynamic.id, null));
        ResponseDetails restored = ResponseDetails.fromJson(written.toJson());
        OrbitModelCatalog.clearDynamicForTest();
        assertEquals("Grok Account Model", restored.rows().get(1)[1]);
        assertEquals("grok-account-model", restored.model);
    }
}
