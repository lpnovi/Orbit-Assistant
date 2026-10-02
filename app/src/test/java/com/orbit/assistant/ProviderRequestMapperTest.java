package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Deterministic protocol fixtures for Anthropic Messages and xAI Chat Completions. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class ProviderRequestMapperTest {

    @Test public void anthropicMapsSystemHistoryExtractedTextImageAndEffort() throws Exception {
        Bitmap image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        List<AssistantClient.History> history = Arrays.asList(
                new AssistantClient.History("user", "Earlier question", true, "", "pdf",
                        "notes.pdf", "Extracted PDF text"),
                new AssistantClient.History("assistant", "Earlier answer"));
        AiRequest request = AiRequest.builder().prompt("Current question")
                .screenText("Current extracted text").images(Arrays.asList(image))
                .history(history).selection(AiSelection.of(Prefs.PROVIDER_ANTHROPIC,
                        OrbitModelCatalog.CLAUDE_OPUS_5_5, AiStrength.MEDIUM)).build();
        JSONObject root = ProviderRequestMapper.anthropic(null, request, true);
        assertEquals(OrbitModelCatalog.CLAUDE_OPUS_5_5, root.getString("model"));
        assertTrue(root.getBoolean("stream"));
        assertTrue(root.getString("system").startsWith("You are Orbit"));
        assertEquals("adaptive", root.getJSONObject("thinking").getString("type"));
        assertEquals("medium", root.getJSONObject("output_config").getString("effort"));
        JSONArray messages = root.getJSONArray("messages");
        assertEquals("user", messages.getJSONObject(0).getString("role"));
        assertTrue(messages.getJSONObject(0).getJSONArray("content").getJSONObject(0)
                .getString("text").contains("Extracted PDF text"));
        JSONArray current = messages.getJSONObject(messages.length() - 1).getJSONArray("content");
        assertTrue(current.getJSONObject(0).getString("text").contains("Current extracted text"));
        assertEquals("image", current.getJSONObject(1).getString("type"));
    }

    @Test public void xaiUsesSystemRoleOpenAiImagesAndOnlyValidReasoningEffort() throws Exception {
        Bitmap image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        AiRequest request = AiRequest.builder().prompt("Describe it").images(Arrays.asList(image))
                .selection(AiSelection.of(Prefs.PROVIDER_XAI, OrbitModelCatalog.GROK_4_7,
                        AiStrength.XHIGH)).build();
        JSONObject root = ProviderRequestMapper.xai(null, request, true);
        assertEquals("xhigh", root.getString("reasoning_effort"));
        JSONArray messages = root.getJSONArray("messages");
        assertEquals("system", messages.getJSONObject(0).getString("role"));
        JSONArray content = messages.getJSONObject(1).getJSONArray("content");
        assertEquals("image_url", content.getJSONObject(1).getString("type"));
        assertTrue(content.getJSONObject(1).getJSONObject("image_url").getString("url")
                .startsWith("data:image/jpeg;base64,"));
    }

    @Test public void streamReadersNormalizeCumulativeDeltas() throws Exception {
        List<String> anthropicDeltas = new ArrayList<>();
        String anthropic = "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}\n"
                + "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\" world\"}}\n";
        String a = ApiKeyProviderClient.readAnthropicStream(bytes(anthropic), callback(anthropicDeltas));
        assertEquals("Hello world", a);
        assertEquals(Arrays.asList("Hello", "Hello world"), anthropicDeltas);

        List<String> xaiDeltas = new ArrayList<>();
        String xai = "data: {\"choices\":[{\"delta\":{\"content\":\"One\"}}]}\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\" two\"}}]}\n"
                + "data: [DONE]\n";
        assertEquals("One two", ApiKeyProviderClient.readXaiStream(bytes(xai), callback(xaiDeltas)));
        assertEquals(Arrays.asList("One", "One two"), xaiDeltas);
    }

    @Test public void errorMappingIsUsefulAndNeverReturnsRawPayload() {
        String secretPayload = "{\"error\":\"secret-provider-payload\"}";
        assertTrue(ApiKeyProviderClient.error(Prefs.PROVIDER_ANTHROPIC, 401, secretPayload,
                OrbitModelCatalog.CLAUDE_OPUS_5_5).contains("API key is invalid"));
        assertTrue(ApiKeyProviderClient.error(Prefs.PROVIDER_XAI, 429, secretPayload,
                OrbitModelCatalog.GROK_4_7).contains("rate limit"));
        assertTrue(ApiKeyProviderClient.error(Prefs.PROVIDER_ANTHROPIC, 413, secretPayload,
                OrbitModelCatalog.CLAUDE_OPUS_5_5).contains("too large"));
        assertTrue(ApiKeyProviderClient.error(Prefs.PROVIDER_ANTHROPIC, 529, secretPayload,
                OrbitModelCatalog.CLAUDE_OPUS_5_5).contains("overloaded"));
        assertFalse(ApiKeyProviderClient.error(Prefs.PROVIDER_XAI, 500, secretPayload,
                OrbitModelCatalog.GROK_4_7).contains("secret-provider-payload"));
    }

    private static ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static AssistantClient.Callback callback(List<String> deltas) {
        return new AssistantClient.Callback() {
            @Override public void onDelta(String text) { deltas.add(text); }
            @Override public void onSuccess(AssistantReply reply) {}
            @Override public void onError(String message) {}
        };
    }
}
