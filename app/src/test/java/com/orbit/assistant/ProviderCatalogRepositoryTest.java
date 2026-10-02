package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Provider catalog fixtures: filtering, capabilities, stable order, and last-known-good behavior. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class ProviderCatalogRepositoryTest {
    @After public void resetCatalogs() { OrbitModelCatalog.clearDynamicForTest(); }

    @Test public void anthropicCatalogUsesOfficialFieldsAndFiltersNonChatModels() throws Exception {
        String fixture = "{\"data\":["
                + "{\"id\":\"claude-haiku-4-5-20251001\",\"display_name\":\"Claude Haiku 4.5\","
                + "\"max_input_tokens\":200000,\"capabilities\":{\"image_input\":{\"supported\":true},"
                + "\"pdf_input\":{\"supported\":true},\"effort\":{\"supported\":false}}},"
                + "{\"id\":\"claude-opus-5-5\",\"display_name\":\"Claude Opus 5.5\","
                + "\"max_input_tokens\":1000000,\"capabilities\":{\"image_input\":{\"supported\":true},"
                + "\"pdf_input\":{\"supported\":true},\"effort\":{\"supported\":true,"
                + "\"low\":{\"supported\":true},\"medium\":{\"supported\":true},"
                + "\"high\":{\"supported\":true}}}},"
                + "{\"id\":\"text-embedding-9\",\"display_name\":\"Embedding\"}]}";
        List<AiModelSpec> models = ProviderCatalogRepository.parseAnthropic(fixture);
        assertEquals(2, models.size());
        assertEquals("claude-opus-5-5", models.get(0).id);
        assertEquals(1_000_000, models.get(0).contextWindowTokens);
        assertTrue(models.get(0).vision);
        assertTrue(models.get(0).nativeFiles);
        assertEquals(Arrays.asList(AiStrength.LOW, AiStrength.MEDIUM, AiStrength.HIGH),
                models.get(0).strengths);
        assertFalse(models.get(1).hasStrengths());
    }

    @Test public void xaiCatalogFiltersToTextChatAndUsesReportedCapabilities() throws Exception {
        String fixture = "{\"models\":["
                + "{\"id\":\"grok-3\",\"input_modalities\":[\"text\"],\"output_modalities\":[\"text\"],"
                + "\"context_length\":131072,\"capabilities\":{}},"
                + "{\"id\":\"grok-4.7\",\"input_modalities\":[\"text\",\"image\"],"
                + "\"output_modalities\":[\"text\"],\"context_length\":500000,"
                + "\"capabilities\":{\"reasoning_effort\":[\"low\",\"medium\",\"high\",\"xhigh\"],"
                + "\"default_reasoning_effort\":\"high\"}},"
                + "{\"id\":\"grok-imagine\",\"input_modalities\":[\"text\"],"
                + "\"output_modalities\":[\"image\"],\"context_length\":1000}]}";
        List<AiModelSpec> models = ProviderCatalogRepository.parseXai(fixture);
        assertEquals(2, models.size());
        assertEquals(OrbitModelCatalog.GROK_4_7, models.get(0).id);
        assertTrue(models.get(0).vision);
        assertEquals(500_000, models.get(0).contextWindowTokens);
        assertEquals(AiStrength.HIGH, models.get(0).defaultStrength);
        assertEquals("grok-3", models.get(1).id);
        assertEquals(131_072, models.get(1).contextWindowTokens);
    }

    @Test public void emptyRefreshCannotEraseLastKnownGoodCatalog() {
        AiModelSpec cached = new AiModelSpec("grok-cached", "Grok Cached", "Grok",
                Prefs.PROVIDER_XAI, "Cached", null, true, 123_000);
        OrbitModelCatalog.installDynamic(Prefs.PROVIDER_XAI, Collections.singletonList(cached));
        OrbitModelCatalog.installDynamic(Prefs.PROVIDER_XAI, Collections.emptyList());
        assertEquals("grok-cached", OrbitModelCatalog.modelsFor(Prefs.PROVIDER_XAI).get(0).id);
    }
}
