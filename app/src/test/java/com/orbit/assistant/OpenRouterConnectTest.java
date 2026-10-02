package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OpenRouter Connect (0.8.3.0-beta.6) without a network: connection states, the catalog and its
 * cache, request mapping, the stream, error wording, Response Details, route identity in the
 * Model Library, Send with / Retry with / branches / kept context, migration, disconnect and
 * backup. Fixtures follow OpenRouter's documented model and stream shapes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class OpenRouterConnectTest {
    private Context context;

    private static final AiSelection OR_SONNET = AiSelection.of(Prefs.PROVIDER_OPENROUTER,
            OrbitModelCatalog.OR_CLAUDE_SONNET_5_5, AiStrength.HIGH);
    private static final AiSelection DIRECT_SONNET = AiSelection.of(Prefs.PROVIDER_ANTHROPIC,
            OrbitModelCatalog.CLAUDE_SONNET_5_5, AiStrength.HIGH);
    private static final AiSelection OR_AUTO = AiSelection.of(Prefs.PROVIDER_OPENROUTER,
            OrbitModelCatalog.OPENROUTER_AUTO, null);

    @Before public void setUp() {
        TestKeystore.install();
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().putBoolean(Prefs.BACKGROUND_NOTIFICATIONS, false).commit();
        ConversationStore.clear(context);
        OrbitRequestManager.resetForTest();
        OrbitRequestManager.setWorkCanceller(name -> {});
        TestWorkManager.ensureInitialized(context);
        context.getSharedPreferences("orbit_pending_requests", Context.MODE_PRIVATE).edit().clear().commit();
        new java.io.File(context.getNoBackupFilesDir(), "openrouter_catalog_v1.json").delete();
        OrbitModelCatalog.clearDynamicForTest();
    }

    @After public void tearDown() {
        OrbitRequestManager.resetForTest();
        AiProviders.installForTest(null);
        OrbitModelCatalog.clearDynamicForTest();
        TestKeystore.uninstall();
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    private static JSONObject model(String id, String name, int context, String[] input,
                                    String[] output, String[] efforts, String defaultEffort,
                                    String expires) throws Exception {
        JSONObject arch = new JSONObject().put("input_modalities", new JSONArray(Arrays.asList(input)))
                .put("output_modalities", new JSONArray(Arrays.asList(output)));
        JSONArray params = new JSONArray().put("max_tokens").put("tools");
        JSONObject o = new JSONObject().put("id", id).put("canonical_slug", id + "-20261002")
                .put("name", name).put("context_length", context).put("architecture", arch)
                .put("pricing", new JSONObject().put("prompt", "0.000001").put("completion", "0.000002"))
                .put("expiration_date", expires == null ? JSONObject.NULL : expires);
        if (efforts != null) {
            params.put("reasoning");
            JSONObject reasoning = new JSONObject().put("supported_efforts",
                    new JSONArray(Arrays.asList(efforts)));
            if (defaultEffort != null) reasoning.put("default_effort", defaultEffort);
            o.put("reasoning", reasoning);
        }
        return o.put("supported_parameters", params);
    }

    private static final String[] TEXT = {"text"};
    private static final String[] TEXT_IMAGE = {"text", "image", "file"};

    private static String catalog(JSONObject... models) throws Exception {
        JSONArray data = new JSONArray();
        for (JSONObject m : models) data.put(m);
        return new JSONObject().put("data", data).toString();
    }

    private static String standardCatalog() throws Exception {
        return catalog(
                model("openrouter/auto", "Auto Router", 2_000_000,
                        new String[]{"text", "image", "audio"}, new String[]{"text", "image"},
                        null, null, null),
                model("openrouter/fusion", "OpenRouter: Fusion", 1_000_000, TEXT, TEXT, null, null, null),
                model("openrouter/free", "Free Models Router", 200_000, TEXT_IMAGE, TEXT, null, null, null),
                model("anthropic/claude-sonnet-5.5", "Anthropic: Claude Sonnet 5.5", 1_000_000,
                        TEXT_IMAGE, TEXT, new String[]{"max", "xhigh", "high", "medium", "low"}, "high", null),
                model("anthropic/claude-sonnet-5.5:batch", "Anthropic: Claude Sonnet 5.5 (batch)",
                        1_000_000, TEXT_IMAGE, TEXT, null, null, null),
                model("openai/gpt-6-luna", "OpenAI: GPT-6 Luna", 1_050_000, TEXT_IMAGE, TEXT,
                        new String[]{"max", "xhigh", "high", "medium", "low", "none"}, "medium", null),
                model("deepseek/deepseek-v4-pro", "DeepSeek: DeepSeek V4 Pro", 1_048_576, TEXT, TEXT,
                        new String[]{"xhigh", "high", "minimal"}, "high", null),
                model("meta-llama/llama-3.3-70b-instruct", "Meta: Llama 3.3 70B Instruct", 131_072,
                        TEXT, TEXT, null, null, null),
                model("openai/gpt-5-image", "OpenAI: GPT-5 Image", 400_000, TEXT_IMAGE,
                        new String[]{"image", "text"}, new String[]{"high"}, null, null),
                model("openai/text-embedding-3-large", "OpenAI: Text Embedding 3 Large", 8_192, TEXT,
                        new String[]{"embeddings"}, null, null, null),
                model("google/gemini-2.5-pro", "Google: Gemini 2.5 Pro", 1_048_576, TEXT_IMAGE, TEXT,
                        new String[]{"high"}, null, "2026-10-20"),
                model("google/gemini-1.5-pro", "Google: Gemini 1.5 Pro", 1_000_000, TEXT_IMAGE, TEXT,
                        null, null, "2025-01-01"));
    }

    private static AiModelSpec find(List<AiModelSpec> models, String id) {
        for (AiModelSpec m : models) if (m.id.equals(id)) return m;
        return null;
    }

    private void connectManually() {
        assertTrue(SecureStore.saveOpenRouterKey(context, "sk-or-v1-manual-test"));
    }

    private static final class Collector implements AssistantClient.Callback {
        final List<String> deltas = new ArrayList<>();
        String error = "";
        AssistantReply reply;
        @Override public void onDelta(String text) { deltas.add(text); }
        @Override public void onSuccess(AssistantReply r) { reply = r; }
        @Override public void onError(String message) { error = message; }
    }

    private PendingRequestStore.Item send(String id, String prompt, AiSelection selection) {
        String requestId = OrbitRequestManager.enqueue(context, id, prompt, "",
                new ArrayList<>(), false, false, selection, false, null);
        return PendingRequestStore.load(context, requestId);
    }

    private String chat(AiSelection selection, String prompt) {
        String id = ConversationStore.newId();
        ConversationStore.save(context, id, Collections.singletonList(
                new AssistantClient.History("user", prompt)));
        ConversationStore.setSelection(context, id, selection);
        return id;
    }

    // ---- connection states ------------------------------------------------------------------------

    @Test public void withoutACredentialOpenRouterAsksToBeConnectedAndSendsNothing() {
        AiProvider provider = AiProviders.byId(Prefs.PROVIDER_OPENROUTER);
        assertEquals(AiProvider.Status.NEEDS_SETUP, provider.status(context));
        assertEquals("", SecureStore.openRouterKeySource(context));
        Collector out = new Collector();
        provider.send(context, AiRequest.builder().prompt("hi").selection(OR_SONNET).build(), out);
        assertTrue(out.error.contains("not connected"));
        assertNull(out.reply);
        assertFalse(provider.supportsCompletion(context));
    }

    @Test public void aBeta5ManualKeyKeepsWorkingAsAnApiKeyConnection() {
        // Exactly how Beta 5 saved a key: the two-argument call, no source recorded.
        assertTrue(SecureStore.saveOpenRouterKey(context, "sk-or-v1-from-beta5"));
        Prefs.get(context).edit().remove("openrouter_key_source").commit();
        AiProvider provider = AiProviders.byId(Prefs.PROVIDER_OPENROUTER);
        assertEquals(AiProvider.Status.READY, provider.status(context));
        assertEquals("Connected with API key", provider.statusDetail(context));
        assertEquals("sk-or-v1-from-beta5", SecureStore.loadOpenRouterKey(context));
        assertEquals(SecureStore.OPENROUTER_SOURCE_MANUAL, SecureStore.openRouterKeySource(context));
        assertFalse("an upgrade never enables Auto",
                AutoPermissions.allows(context, Prefs.PROVIDER_OPENROUTER));
        assertTrue(AiProviders.select(context, Prefs.PROVIDER_OPENROUTER));
    }

    @Test public void oauthAndManualConnectionsAreDistinguishedWithoutShowingTheKey() {
        assertTrue(SecureStore.saveOpenRouterKey(context, "sk-or-v1-oauth-key",
                SecureStore.OPENROUTER_SOURCE_OAUTH));
        AiProvider provider = AiProviders.byId(Prefs.PROVIDER_OPENROUTER);
        assertEquals("Connected with OpenRouter", provider.statusDetail(context));
        // Replacing with a manual key overwrites the same slot.
        assertTrue(SecureStore.saveOpenRouterKey(context, "sk-or-v1-manual",
                SecureStore.OPENROUTER_SOURCE_MANUAL));
        assertEquals("Connected with API key", provider.statusDetail(context));
        assertEquals("sk-or-v1-manual", SecureStore.loadOpenRouterKey(context));
        for (String shown : new String[]{provider.statusDetail(context), provider.description()}) {
            assertFalse(shown.contains("sk-or"));
        }
    }

    @Test public void disconnectRemovesTheKeyAndAutoButKeepsHistory() throws Exception {
        connectManually();
        AutoPermissions.set(context, Prefs.PROVIDER_OPENROUTER, true);
        String id = ConversationStore.newId();
        ConversationStore.save(context, id, Arrays.asList(
                new AssistantClient.History("user", "Explain tides"),
                new AssistantClient.History("assistant", "Tides follow the moon.")
                        .withDetails(ResponseDetails.sentWith(OR_SONNET))));
        ConversationStore.setSelection(context, id, OR_SONNET);

        SecureStore.clearOpenRouterKey(context);

        assertFalse(SecureStore.hasOpenRouterKey(context));
        assertEquals(AiProvider.Status.NEEDS_SETUP,
                AiProviders.byId(Prefs.PROVIDER_OPENROUTER).status(context));
        assertFalse("disconnect turns Auto off", AutoPermissions.allows(context, Prefs.PROVIDER_OPENROUTER));
        ConversationStore.Conversation chat = ConversationStore.load(context, id);
        assertEquals("history stays", 2, chat.messages.size());
        assertEquals(Prefs.PROVIDER_OPENROUTER, chat.messages.get(1).details.provider);
        assertEquals("the chat keeps its exact OpenRouter model", OR_SONNET,
                AiSelections.forConversation(context, id));
        // Reconnecting never brings Auto back on its own.
        connectManually();
        assertFalse(AutoPermissions.allows(context, Prefs.PROVIDER_OPENROUTER));
    }

    // ---- the catalog ------------------------------------------------------------------------------

    @Test public void theCatalogKeepsChatModelsAndOnlyStructuredCapabilities() throws Exception {
        List<AiModelSpec> models = ProviderCatalogRepository.parseOpenRouter(standardCatalog());
        assertEquals("a known model first, so it is what choosing OpenRouter starts on",
                OrbitModelCatalog.OR_GPT_6_LUNA, models.get(0).id);
        assertEquals("OpenRouter Auto right after Auto's curated routes",
                OrbitModelCatalog.OPENROUTER_AUTO, models.get(2).id);
        assertNull("Fusion is deferred and never offered", find(models, "openrouter/fusion"));
        assertNull("other OpenRouter routers are not offered", find(models, "openrouter/free"));
        assertNull("batch variants are not chat", find(models, "anthropic/claude-sonnet-5.5:batch"));
        assertNull("image generators are not chat models here", find(models, "openai/gpt-5-image"));
        assertNull("embeddings are not chat", find(models, "openai/text-embedding-3-large"));

        AiModelSpec sonnet = find(models, OrbitModelCatalog.OR_CLAUDE_SONNET_5_5);
        assertEquals("Claude Sonnet 5.5", sonnet.displayName);
        assertEquals("Anthropic", sonnet.familyLabel);
        assertEquals(Prefs.PROVIDER_OPENROUTER, sonnet.providerId);
        assertEquals(1_000_000, sonnet.contextWindowTokens);
        assertTrue(sonnet.vision);
        assertFalse("Orbit sends extracted text, not provider files", sonnet.nativeFiles);
        assertTrue(sonnet.extractedDocuments);
        assertFalse("Orbit sends no tools", sonnet.tools);
        assertFalse("Orbit invokes no OpenRouter web search", sonnet.webSearch);
        assertEquals(Arrays.asList(AiStrength.LOW, AiStrength.MEDIUM, AiStrength.HIGH,
                AiStrength.XHIGH, AiStrength.MAX), sonnet.strengths);
        assertEquals(AiStrength.HIGH, sonnet.defaultStrength);

        AiModelSpec deepseek = find(models, "deepseek/deepseek-v4-pro");
        assertFalse("text-only input means no images", deepseek.vision);
        assertEquals("only efforts Orbit has; 'minimal' is not one",
                Arrays.asList(AiStrength.HIGH, AiStrength.XHIGH), deepseek.strengths);

        AiModelSpec llama = find(models, "meta-llama/llama-3.3-70b-instruct");
        assertFalse("no reasoning metadata: no Strength control at all", llama.hasStrengths());

        AiModelSpec auto = find(models, OrbitModelCatalog.OPENROUTER_AUTO);
        assertEquals("no fixed window for a router", 0, auto.contextWindowTokens);
        assertFalse("no fixed strengths for a router", auto.hasStrengths());

        assertEquals("deprecated", find(models, "google/gemini-2.5-pro").availability);
        assertEquals("unavailable", find(models, "google/gemini-1.5-pro").availability);
    }

    @Test public void aModelsNameIsNeverUsedToInferCapabilities() throws Exception {
        List<AiModelSpec> models = ProviderCatalogRepository.parseOpenRouter(catalog(
                model("maker/vision-reasoning-pro", "Maker: Vision Reasoning Pro", 100_000, TEXT, TEXT,
                        null, null, null)));
        AiModelSpec spec = models.get(0);
        assertFalse(spec.vision);
        assertFalse(spec.hasStrengths());
    }

    @Test public void theOrderIsDeterministic() throws Exception {
        List<String> first = new ArrayList<>();
        for (AiModelSpec m : ProviderCatalogRepository.parseOpenRouter(standardCatalog())) first.add(m.id);
        List<String> second = new ArrayList<>();
        for (AiModelSpec m : ProviderCatalogRepository.parseOpenRouter(standardCatalog())) second.add(m.id);
        assertEquals(first, second);
        assertEquals(OrbitModelCatalog.OR_GPT_6_LUNA, first.get(0));
    }

    @Test public void aLargeCatalogIsParsedAndBounded() throws Exception {
        JSONObject[] many = new JSONObject[1000];
        for (int i = 0; i < many.length; i++) {
            many[i] = model("maker" + (i % 40) + "/model-" + i, "Maker: Model " + i, 128_000,
                    TEXT, TEXT, null, null, null);
        }
        List<AiModelSpec> models = ProviderCatalogRepository.parseOpenRouter(catalog(many));
        assertEquals(ProviderCatalogRepository.MAX_OPENROUTER_MODELS, models.size());
    }

    @Test public void withoutACatalogTheTrustedBaselineIsUsed() {
        List<AiModelSpec> baseline = OrbitModelCatalog.modelsFor(Prefs.PROVIDER_OPENROUTER);
        for (String id : new String[]{OrbitModelCatalog.OPENROUTER_AUTO, OrbitModelCatalog.OR_GPT_6_LUNA,
                OrbitModelCatalog.OR_GPT_6_1_SOL, OrbitModelCatalog.OR_CLAUDE_SONNET_5_5,
                OrbitModelCatalog.OR_CLAUDE_OPUS_5_5}) {
            assertNotNull(id, find(baseline, id));
        }
    }

    @Test public void anEmptyOrFailedRefreshNeverErasesTheInstalledCatalog() throws Exception {
        List<AiModelSpec> live = ProviderCatalogRepository.parseOpenRouter(standardCatalog());
        OrbitModelCatalog.installDynamic(Prefs.PROVIDER_OPENROUTER, live);
        OrbitModelCatalog.installDynamic(Prefs.PROVIDER_OPENROUTER, Collections.emptyList());
        assertNotNull(OrbitModelCatalog.spec(Prefs.PROVIDER_OPENROUTER, "deepseek/deepseek-v4-pro"));

        // A refresh without a credential fails with a message and changes nothing.
        AtomicInteger finished = new AtomicInteger();
        String[] error = new String[1];
        ProviderCatalogRepository.refreshAsync(context, Prefs.PROVIDER_OPENROUTER, (changed, e) -> {
            error[0] = e;
            finished.incrementAndGet();
        });
        long until = System.currentTimeMillis() + 5000;
        while (finished.get() == 0 && System.currentTimeMillis() < until) Thread.sleep(20);
        assertEquals("Connect OpenRouter first.", error[0]);
        assertNotNull(OrbitModelCatalog.spec(Prefs.PROVIDER_OPENROUTER, "deepseek/deepseek-v4-pro"));
    }

    @Test public void aCachedCatalogLoadsOfflineAfterARestart() throws Exception {
        java.lang.reflect.Method write = ProviderCatalogRepository.class.getDeclaredMethod(
                "writeOpenRouterCache", Context.class, List.class);
        write.setAccessible(true);
        write.invoke(null, context, ProviderCatalogRepository.parseOpenRouter(standardCatalog()));
        OrbitModelCatalog.clearDynamicForTest();
        assertNull(OrbitModelCatalog.spec(Prefs.PROVIDER_OPENROUTER, "deepseek/deepseek-v4-pro"));
        ProviderCatalogRepository.loadCached(context);
        AiModelSpec cached = OrbitModelCatalog.spec(Prefs.PROVIDER_OPENROUTER, "deepseek/deepseek-v4-pro");
        assertNotNull(cached);
        assertEquals(1_048_576, cached.contextWindowTokens);
        assertEquals(Arrays.asList(AiStrength.HIGH, AiStrength.XHIGH), cached.strengths);
        java.io.File file = new java.io.File(context.getNoBackupFilesDir(), "openrouter_catalog_v1.json");
        assertTrue("the cache lives in the no-backup directory", file.exists());
    }

    @Test public void aStaleSelectionStaysExactAndIsReportedUnavailableAtSendTime() {
        AiSelection retired = AiSelection.of(Prefs.PROVIDER_OPENROUTER, "maker/retired", null);
        assertEquals(retired, AiSelections.resolve(retired));
        Collector out = new Collector();
        connectManually();
        AiProviders.byId(Prefs.PROVIDER_OPENROUTER).send(context,
                AiRequest.builder().prompt("hi").selection(retired).build(), out);
        long until = System.currentTimeMillis() + 5000;
        while (out.error.isEmpty() && System.currentTimeMillis() < until) {
            try { Thread.sleep(20); } catch (InterruptedException e) { break; }
        }
        assertTrue(out.error, out.error.contains("is not available"));
        assertTrue(out.error.contains("has not been changed"));
    }

    // ---- request mapping ------------------------------------------------------------------------

    @Test public void requestsCarryOnlyParametersTheModelSupports() throws Exception {
        OrbitModelCatalog.installDynamic(Prefs.PROVIDER_OPENROUTER,
                ProviderCatalogRepository.parseOpenRouter(standardCatalog()));
        Bitmap image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);

        JSONObject sonnet = ProviderRequestMapper.openRouter(null, AiRequest.builder()
                .prompt("Describe it").images(Collections.singletonList(image))
                .selection(OR_SONNET).build(), true);
        assertEquals(OrbitModelCatalog.OR_CLAUDE_SONNET_5_5, sonnet.getString("model"));
        assertTrue(sonnet.getBoolean("stream"));
        assertEquals("high", sonnet.getJSONObject("reasoning").getString("effort"));
        assertTrue(sonnet.getJSONObject("reasoning").getBoolean("exclude"));
        assertFalse(sonnet.has("tools"));
        assertFalse(sonnet.has("reasoning_effort"));
        JSONArray messages = sonnet.getJSONArray("messages");
        assertEquals("system", messages.getJSONObject(0).getString("role"));
        JSONArray current = messages.getJSONObject(messages.length() - 1).getJSONArray("content");
        assertEquals("image_url", current.getJSONObject(1).getString("type"));

        AiSelection llama = AiSelections.resolve(AiSelection.of(Prefs.PROVIDER_OPENROUTER,
                "meta-llama/llama-3.3-70b-instruct", AiStrength.HIGH));
        assertNull("no strength survives for a model without reasoning metadata", llama.strength);
        JSONObject plain = ProviderRequestMapper.openRouter(null, AiRequest.builder()
                .prompt("Describe it").screenText("Extracted PDF text")
                .images(Collections.singletonList(image)).selection(llama).build(), true);
        assertFalse(plain.has("reasoning"));
        JSONArray content = plain.getJSONArray("messages").getJSONObject(1).getJSONArray("content");
        assertEquals("a text-only model never receives the image", 1, content.length());
        assertTrue("but it does receive Orbit's extracted text",
                content.getJSONObject(0).getString("text").contains("Extracted PDF text"));

        JSONObject router = ProviderRequestMapper.openRouter(null, AiRequest.builder()
                .prompt("hi").selection(OR_AUTO).build(), true);
        assertEquals(OrbitModelCatalog.OPENROUTER_AUTO, router.getString("model"));
        assertFalse("OpenRouter Auto gets no reasoning parameter", router.has("reasoning"));
    }

    @Test public void aStrengthTheModelDoesNotListIsMovedToOneItDoes() throws Exception {
        OrbitModelCatalog.installDynamic(Prefs.PROVIDER_OPENROUTER,
                ProviderCatalogRepository.parseOpenRouter(standardCatalog()));
        AiSelection resolved = AiSelections.resolve(AiSelection.of(Prefs.PROVIDER_OPENROUTER,
                "deepseek/deepseek-v4-pro", AiStrength.LOW));
        assertEquals(AiStrength.HIGH, resolved.strength);
        assertEquals(Arrays.asList(AiStrength.HIGH, AiStrength.XHIGH),
                AiSelections.strengthsFor(resolved));
    }

    @Test public void historyKeptContextAndOnlyTheActivePathAreSent() throws Exception {
        List<AssistantClient.History> history = Arrays.asList(
                new AssistantClient.History("user", "Earlier question"),
                new AssistantClient.History("assistant", "Earlier answer"));
        KeptContext kept = KeptContext.create("file_text", "Notes.txt", "KEPT NOTE BODY", "", "", false);
        AiRequest request = AiRequest.builder().prompt("Follow up").history(history)
                .keptContext(KeptContext.prepare(Collections.singletonList(kept), ""))
                .selection(OR_SONNET).build();
        String body = ProviderRequestMapper.openRouter(null, request, true).toString();
        assertTrue(body.contains("Earlier question"));
        assertTrue(body.contains("Earlier answer"));
        assertTrue(body.contains("KEPT NOTE BODY"));

        // After an edit, only the active path is in the conversation the request is built from.
        String id = ConversationStore.newId();
        ConversationStore.save(context, id, Arrays.asList(
                new AssistantClient.History("user", "ORIGINAL QUESTION"),
                new AssistantClient.History("assistant", "ORIGINAL ANSWER")));
        ConversationStore.Conversation before = ConversationStore.load(context, id);
        ConversationStore.branchFromUserMessage(context, id, 0,
                ConversationBranches.fingerprint(before.messages.get(0)),
                new AssistantClient.History("user", "EDITED QUESTION"));
        List<AssistantClient.History> active = ConversationStore.load(context, id).messages;
        String branched = ProviderRequestMapper.openRouter(null, AiRequest.builder()
                .prompt("EDITED QUESTION").history(active).selection(OR_SONNET).build(), true)
                .toString();
        assertTrue(branched.contains("EDITED QUESTION"));
        assertFalse("a hidden branch never leaks", branched.contains("ORIGINAL ANSWER"));
        assertFalse(branched.contains("ORIGINAL QUESTION"));
    }

    // ---- the stream ---------------------------------------------------------------------------------

    private static ByteArrayInputStream sse(String... lines) {
        return new ByteArrayInputStream(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    @Test public void theStreamSkipsKeepAlivesAndReportsTheServingModel() throws Exception {
        Collector out = new Collector();
        String[] servedBy = {""};
        String text = ApiKeyProviderClient.readOpenRouterStream(sse(
                ": OPENROUTER PROCESSING",
                "",
                "data: {\"id\":\"gen-1\",\"model\":\"anthropic/claude-sonnet-5.5\",\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"Hel\"}}]}",
                ": OPENROUTER PROCESSING",
                "data: {\"id\":\"gen-1\",\"model\":\"anthropic/claude-sonnet-5.5\",\"choices\":[{\"delta\":{\"reasoning\":\"secret thoughts\"}}]}",
                "data: {\"id\":\"gen-1\",\"model\":\"anthropic/claude-sonnet-5.5\",\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}",
                "data: {\"id\":\"gen-1\",\"choices\":[],\"usage\":{\"prompt_tokens\":3}}",
                "data: [DONE]"), out, () -> false, servedBy);
        assertEquals("Hello", text);
        assertEquals(Arrays.asList("Hel", "Hello"), out.deltas);
        assertEquals("anthropic/claude-sonnet-5.5", servedBy[0]);
        assertFalse("reasoning deltas are never shown", text.contains("secret"));
    }

    @Test public void aMidStreamErrorIsAnErrorNotAnAnswer() throws Exception {
        Collector out = new Collector();
        try {
            ApiKeyProviderClient.readOpenRouterStream(sse(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Par\"}}]}",
                    "data: {\"error\":{\"code\":402,\"message\":\"Insufficient credits\"},\"choices\":[{\"delta\":{\"content\":\"\"},\"finish_reason\":\"error\"}]}"),
                    out, () -> false, new String[]{""});
            fail("a mid-stream error must end the read");
        } catch (ApiKeyProviderClient.StreamError e) {
            assertEquals(402, e.code);
            assertTrue(ApiKeyProviderClient.error(context, Prefs.PROVIDER_OPENROUTER, e.code,
                    e.getMessage(), OrbitModelCatalog.OR_CLAUDE_SONNET_5_5).contains("enough credits"));
        }
    }

    @Test public void stopEndsTheReadAtOnce() throws Exception {
        Collector out = new Collector();
        AtomicInteger reads = new AtomicInteger();
        String text = ApiKeyProviderClient.readOpenRouterStream(sse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"One\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"content\":\" two\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"content\":\" three\"}}]}"),
                out, () -> reads.incrementAndGet() > 1, new String[]{""});
        assertEquals("One", text);
    }

    // ---- errors ----------------------------------------------------------------------------------

    @Test public void errorsArePlainAndNeverQuoteTheServer() {
        String raw = "{\"error\":{\"code\":400,\"message\":\"secret upstream detail\",\"metadata\":{}}}";
        String model = OrbitModelCatalog.OR_CLAUDE_SONNET_5_5;
        String p = Prefs.PROVIDER_OPENROUTER;
        assertTrue(SecureStore.saveOpenRouterKey(context, "k", SecureStore.OPENROUTER_SOURCE_OAUTH));
        assertTrue(ApiKeyProviderClient.error(context, p, 401, raw, model).contains("Reconnect OpenRouter"));
        assertTrue(SecureStore.saveOpenRouterKey(context, "k", SecureStore.OPENROUTER_SOURCE_MANUAL));
        assertTrue(ApiKeyProviderClient.error(context, p, 401, raw, model).contains("API key is invalid or revoked"));
        assertTrue(ApiKeyProviderClient.error(context, p, 402, raw, model).contains("enough credits"));
        assertTrue(ApiKeyProviderClient.error(context, p, 403, raw, model).contains("declined"));
        assertTrue(ApiKeyProviderClient.error(context, p, 404, raw, model).contains("is not available"));
        assertTrue(ApiKeyProviderClient.error(context, p, 408, raw, model).contains("timed out"));
        assertTrue(ApiKeyProviderClient.error(context, p, 413, raw, model).contains("too large"));
        assertTrue(ApiKeyProviderClient.error(context, p, 400,
                "This endpoint's maximum context length is 1000 tokens", model).contains("too large"));
        assertTrue(ApiKeyProviderClient.error(context, p, 429, raw, model).contains("rate limit"));
        assertTrue(ApiKeyProviderClient.error(context, p, 502, raw, model).contains("is down"));
        assertTrue(ApiKeyProviderClient.error(context, p, 503, raw, model).contains("no provider available"));
        assertTrue(ApiKeyProviderClient.error(context, p, 404,
                "No endpoints found that support image input", model).contains("cannot accept"));
        assertFalse("an unsupported attachment is not an unavailable model",
                ApiKeyProviderClient.modelUnavailable(p, 404, "No endpoints found that support image input", model));
        for (int code : new int[]{400, 401, 402, 403, 404, 408, 413, 429, 500, 502, 503}) {
            String message = ApiKeyProviderClient.error(context, p, code, raw, model);
            assertFalse(code + " echoes the server", message.contains("secret upstream detail"));
            assertFalse(message.contains("{"));
        }
    }

    // ---- Response Details --------------------------------------------------------------------------

    @Test public void anExplicitOpenRouterAnswerSaysWhichRouteAnswered() {
        List<String> rows = rows(ResponseDetails.sentWith(OR_SONNET).withElapsed(1200));
        assertEquals(Arrays.asList("Provider=OpenRouter", "Model=Claude Sonnet 5.5", "Strength=High",
                "Response time=1.2 s"), rows);
        assertFalse("an explicit model is never shown as Auto", rows.toString().contains("Selection"));
    }

    @Test public void openRouterAutoReportsTheDownstreamModelOnlyWhenOpenRouterDoes() throws Exception {
        ResponseDetails served = ResponseDetails.sentWith(OR_AUTO).withServedBy("anthropic/claude-sonnet-5.5");
        assertFalse("OpenRouter Auto is not Orbit Auto", served.autoRouted());
        assertEquals(Arrays.asList("Selection=OpenRouter Auto", "Provider=OpenRouter",
                "Model=OpenRouter Auto", "Answered by=Claude Sonnet 5.5"), rows(served));
        ResponseDetails back = ResponseDetails.fromJson(served.toJson());
        assertEquals("anthropic/claude-sonnet-5.5", back.servedBy);
        assertFalse(back.autoRouted());

        ResponseDetails unknown = ResponseDetails.sentWith(OR_AUTO);
        assertFalse("nothing is invented when OpenRouter names no model",
                rows(unknown).toString().contains("Answered by"));
        assertEquals("", ResponseDetails.sentWith(OR_SONNET).withServedBy("someone/else").servedBy);
    }

    @Test public void orbitAutoRoutingToOpenRouterShowsSelectionAutoAndThePolicy() {
        SmartRouter.Route route = new SmartRouter.Route(OR_SONNET, SmartRouter.NORMAL,
                SmartRouter.POLICY_VERSION, "");
        List<String> rows = rows(ResponseDetails.sentWith(OR_SONNET).withRoute(route));
        assertEquals(Arrays.asList("Selection=Auto", "Provider=OpenRouter", "Model=Claude Sonnet 5.5",
                "Strength=High", "Why=Normal conversation", "Router policy=2"), rows);
    }

    private static List<String> rows(ResponseDetails d) {
        List<String> out = new ArrayList<>();
        for (String[] row : d.rows()) out.add(row[0] + "=" + row[1]);
        return out;
    }

    // ---- route identity in the Model Library ---------------------------------------------------------

    @Test public void directAndOpenRouterRoutesStayDistinctEverywhere() {
        ModelLibraryStore.setFavorite(context, Prefs.PROVIDER_ANTHROPIC, OrbitModelCatalog.CLAUDE_SONNET_5_5, true);
        assertTrue(ModelLibraryStore.isFavorite(context, Prefs.PROVIDER_ANTHROPIC, OrbitModelCatalog.CLAUDE_SONNET_5_5));
        assertFalse(ModelLibraryStore.isFavorite(context, Prefs.PROVIDER_OPENROUTER, OrbitModelCatalog.OR_CLAUDE_SONNET_5_5));
        ModelLibraryStore.setFavorite(context, Prefs.PROVIDER_OPENROUTER, OrbitModelCatalog.OR_CLAUDE_SONNET_5_5, true);
        List<AiSelection> favorites = ModelLibraryStore.favorites(context);
        assertEquals(2, favorites.size());
        assertEquals(Prefs.PROVIDER_ANTHROPIC, favorites.get(0).provider);
        assertEquals(Prefs.PROVIDER_OPENROUTER, favorites.get(1).provider);
        assertEquals("a slug with a slash survives the favorite key",
                OrbitModelCatalog.OR_CLAUDE_SONNET_5_5, favorites.get(1).model);

        ModelLibraryStore.recordRecent(context, DIRECT_SONNET);
        ModelLibraryStore.recordRecent(context, OR_SONNET);
        List<AiSelection> recents = ModelLibraryStore.recents(context);
        assertEquals(Arrays.asList(OR_SONNET, DIRECT_SONNET), recents);

        assertNotEquals(DIRECT_SONNET.label(), OR_SONNET.label());
        assertEquals("Claude Sonnet 5.5 · OpenRouter · High", OR_SONNET.label());
        assertEquals("Claude Sonnet 5.5 · High", DIRECT_SONNET.label());
        assertEquals("OpenRouter Auto", OR_AUTO.label());
    }

    @Test public void recentsStayBoundedWithManyOpenRouterModels() throws Exception {
        for (int i = 0; i < 30; i++) {
            ModelLibraryStore.recordRecent(context, AiSelection.of(Prefs.PROVIDER_OPENROUTER,
                    "maker/model-" + i, null));
        }
        assertEquals(ModelLibraryStore.MAX_RECENTS, ModelLibraryStore.recents(context).size());
    }

    @Test public void searchFindsModelsByNameMakerSlugAndRoute() throws Exception {
        List<AiModelSpec> models = ProviderCatalogRepository.parseOpenRouter(standardCatalog());
        AiModelSpec sonnet = find(models, OrbitModelCatalog.OR_CLAUDE_SONNET_5_5);
        assertTrue(ModelLibraryDialog.matches(sonnet, "OpenRouter", "sonnet"));
        assertTrue(ModelLibraryDialog.matches(sonnet, "OpenRouter", "anthropic"));
        assertTrue("by slug", ModelLibraryDialog.matches(sonnet, "OpenRouter", "anthropic/claude-sonnet-5.5"));
        assertTrue("by route", ModelLibraryDialog.matches(sonnet, "OpenRouter", "openrouter sonnet"));
        assertFalse(ModelLibraryDialog.matches(sonnet, "OpenRouter", "deepseek"));

        AiModelSpec deepseek = find(models, "deepseek/deepseek-v4-pro");
        assertTrue(ModelLibraryDialog.passesCapabilities(sonnet, true, true));
        assertFalse("Vision filter", ModelLibraryDialog.passesCapabilities(deepseek, true, false));
        assertTrue(ModelLibraryDialog.passesCapabilities(deepseek, false, true));
        AiModelSpec llama = find(models, "meta-llama/llama-3.3-70b-instruct");
        assertFalse("Reasoning filter", ModelLibraryDialog.passesCapabilities(llama, false, true));
        assertEquals("Showing 60 of 412 models. Search to find more.",
                ModelLibraryDialog.moreLabel(60, 412));
    }

    @Test public void theQuickPickerStaysShortWithHundredsOfModels() throws Exception {
        connectManually();
        JSONObject[] many = new JSONObject[450];
        for (int i = 0; i < many.length; i++) {
            many[i] = model("maker" + (i % 30) + "/model-" + i, "Maker: Model " + i, 128_000,
                    TEXT, TEXT, null, null, null);
        }
        List<AiModelSpec> models = ProviderCatalogRepository.parseOpenRouter(catalog(many));
        OrbitModelCatalog.installDynamic(Prefs.PROVIDER_OPENROUTER, models);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        AiSelectorDialog picker = AiSelectorDialog.forTest(activity,
                AiSelection.of(Prefs.PROVIDER_OPENROUTER, models.get(0).id, null));
        assertTrue("the quick picker never lists the catalog: " + picker.offeredModels().size(),
                picker.offeredModels().size() <= 6 + 4);
    }

    @Test public void anUnavailableFavoriteIsATombstoneNotAnotherModel() {
        ModelLibraryStore.setFavorite(context, Prefs.PROVIDER_OPENROUTER, "maker/gone", true);
        AiSelection favorite = ModelLibraryStore.favorites(context).get(0);
        assertEquals("maker/gone", favorite.model);
        AiModelSpec tombstone = OrbitModelCatalog.unavailableReference(Prefs.PROVIDER_OPENROUTER, "maker/gone");
        assertFalse(tombstone.selectable());
        assertEquals(Prefs.PROVIDER_OPENROUTER, tombstone.providerId);
    }

    // ---- the conversation engine ------------------------------------------------------------------

    @Test public void sendWithOpenRouterIsExactAndTheChatKeepsItsModel() {
        connectManually();
        AiSelection terra = AiSelection.of(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.GPT_5_6_TERRA,
                AiStrength.MEDIUM);
        String id = chat(terra, "Explain tides");
        PendingRequestStore.Item once = send(id, "Explain tides", OR_SONNET);
        assertNull("an explicit OpenRouter model is never routed", once.route);
        assertEquals(OR_SONNET, once.selection);
        assertEquals(terra, AiSelections.forConversation(context, id));
    }

    @Test public void retryWithOpenRouterAddsAVariantAndRetryStaysExact() throws Exception {
        connectManually();
        String id = ConversationStore.newId();
        ConversationStore.save(context, id, Arrays.asList(
                new AssistantClient.History("user", "Explain tides"),
                new AssistantClient.History("assistant", "Answer A")
                        .withDetails(ResponseDetails.sentWith(DIRECT_SONNET))
                        .withReplyProvenance("first", Collections.emptyList())));
        ConversationStore.setSelection(context, id, DIRECT_SONNET);
        String requestId = OrbitRequestManager.enqueueAnswerVariant(context, id, 1, "Explain tides",
                "", new ArrayList<>(), OR_SONNET, false, null);
        PendingRequestStore.Item item = PendingRequestStore.load(context, requestId);
        assertTrue(item.isAnswerVariant());
        assertNull(item.route);
        assertEquals(OR_SONNET, item.selection);

        OrbitRequestWorker.completeProviderReply(context, item, new AssistantReply("Answer B")
                .withDetails(ResponseDetails.sentWith(OR_SONNET).withElapsed(800)), WorkerAttempt.of(1, false));
        ConversationStore.Conversation chat = ConversationStore.load(context, id);
        assertEquals("Answer B", chat.messages.get(1).content);
        assertEquals(Prefs.PROVIDER_OPENROUTER, chat.messages.get(1).details.provider);
        assertEquals("the direct answer is kept as the other version", "Answer A",
                chat.forkAt(1).variants.get(0).messages.get(0).content);
        assertEquals("Retry with never changes the chat's AI", DIRECT_SONNET,
                AiSelections.forConversation(context, id));

        PendingRequestStore.markFailed(context, item.id, "network");
        PendingRequestStore.Item retried = PendingRequestStore.load(context,
                OrbitRequestManager.retry(context, item.id, null));
        assertEquals(OR_SONNET, retried.selection);
    }

    @Test public void choosingAnOpenRouterModelIsPerChatAndNeverRecentIfRouted() {
        connectManually();
        String id = chat(AiSelection.AUTO, "Explain tides");
        AiSelections.setForConversation(context, id, OR_SONNET);
        assertEquals(OR_SONNET, AiSelections.forConversation(context, id));
        AiSelections.setForConversation(context, id, AiSelection.AUTO);
        assertTrue(AiSelections.forConversation(context, id).isAuto());
        assertEquals("Auto never becomes OpenRouter's provider default", OR_SONNET,
                ModelLibraryStore.providerDefault(context, Prefs.PROVIDER_OPENROUTER));
    }

    @Test public void theContextMeterUsesTheModelsWindowAndNeverInventsOneForOpenRouterAuto() {
        String id = chat(OR_SONNET, "Explain tides");
        ContextEstimate explicit = ContextEstimate.measure(context, id, OR_SONNET, ContextEstimate.Draft.EMPTY);
        assertEquals(1_000_000, explicit.limit);
        ContextEstimate router = ContextEstimate.measure(context, id, OR_AUTO, ContextEstimate.Draft.EMPTY);
        assertEquals("no fabricated denominator for OpenRouter Auto", 0, router.limit);
        assertEquals(-1, router.percent());
        ContextEstimate auto = ContextEstimate.measure(context, id, AiSelection.AUTO, ContextEstimate.Draft.EMPTY);
        assertTrue(auto.auto);
        assertEquals(0, auto.limit);
    }

    // ---- backup and restore -----------------------------------------------------------------------

    @Test public void backupsCarryChoicesButNeverTheCredentialOrAutoPermission() throws Exception {
        assertTrue(SecureStore.saveOpenRouterKey(context, "sk-or-v1-never-backed-up",
                SecureStore.OPENROUTER_SOURCE_OAUTH));
        AutoPermissions.set(context, Prefs.PROVIDER_OPENROUTER, true);
        ModelLibraryStore.setFavorite(context, Prefs.PROVIDER_OPENROUTER, OrbitModelCatalog.OR_CLAUDE_SONNET_5_5, true);
        ModelLibraryStore.recordRecent(context, OR_SONNET);
        JSONObject snapshot = Prefs.backupSnapshot(context);
        String backup = snapshot.toString();
        assertFalse(backup.contains("sk-or-v1-never-backed-up"));
        assertFalse(backup.contains("openrouter_key"));
        assertFalse("OpenRouter's Auto permission is never restored", backup.contains(Prefs.AUTO_USE_OPENROUTER));
        assertTrue("Favorites travel, route and slug intact",
                new JSONArray(snapshot.getString(Prefs.AI_MODEL_FAVORITES)).getString(0)
                        .equals(Prefs.PROVIDER_OPENROUTER + "/" + OrbitModelCatalog.OR_CLAUDE_SONNET_5_5));

        // Restored onto a phone with no credential: history and choices stay, requests do not go.
        SecureStore.clearOpenRouterKey(context);
        assertTrue(Prefs.restoreBackupSnapshot(context, Prefs.backupSnapshot(context)));
        assertFalse(SecureStore.hasOpenRouterKey(context));
        assertFalse(AutoPermissions.allows(context, Prefs.PROVIDER_OPENROUTER));
        assertTrue(ModelLibraryStore.isFavorite(context, Prefs.PROVIDER_OPENROUTER,
                OrbitModelCatalog.OR_CLAUDE_SONNET_5_5));
        assertEquals(AiProvider.Status.NEEDS_SETUP, AiProviders.byId(Prefs.PROVIDER_OPENROUTER).status(context));
    }

    @Test public void theKeyNeverReachesDiagnosticsOrResponseDetails() throws Exception {
        assertTrue(SecureStore.saveOpenRouterKey(context, "sk-or-v1-diagnostic-probe",
                SecureStore.OPENROUTER_SOURCE_OAUTH));
        assertFalse(ResponseDetails.sentWith(OR_SONNET).toJson().toString().contains("sk-or"));
        assertFalse(ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/DiagnosticsActivity.java")
                .contains("loadOpenRouterKey"));
        for (String file : new String[]{"ApiKeyProviderClient.java", "OpenRouterProvider.java",
                "ProviderCatalogRepository.java", "OpenRouterAuth.java"}) {
            String source = ComponentUninstallTest.readRepositoryFile("app/src/main/java/com/orbit/assistant/" + file);
            assertFalse(file + " must not log", source.contains("Log."));
        }
    }
}
