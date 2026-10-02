package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.util.Base64;
import android.view.View;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Smart Routing end to end (0.8.3.0-beta.5): Auto as a per-chat selection, provider permissions
 * and their cost safety, resolution before queueing, durability, Recents, Response details, Send
 * with, Retry with, the context meter, and the internal jobs that must never use Auto.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class SmartRoutingTest {
    private Context context;

    private static final AiSelection TERRA = AiSelection.of(Prefs.PROVIDER_CHATGPT,
            OrbitModelCatalog.GPT_5_6_TERRA, AiStrength.MEDIUM);
    private static final AiSelection TERRA_HIGH = AiSelection.of(Prefs.PROVIDER_CHATGPT,
            OrbitModelCatalog.GPT_5_6_TERRA, AiStrength.HIGH);
    private static final AiSelection SONNET = AiSelection.of(Prefs.PROVIDER_ANTHROPIC,
            OrbitModelCatalog.CLAUDE_SONNET_5_5, AiStrength.HIGH);
    private static final AiSelection LUNA_MEDIUM = AiSelection.of(Prefs.PROVIDER_CHATGPT,
            OrbitModelCatalog.LUNA, AiStrength.MEDIUM);

    @Before public void setUp() {
        TestKeystore.install();
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().putBoolean(Prefs.BACKGROUND_NOTIFICATIONS, false).commit();
        ConversationStore.clear(context);
        OrbitRequestManager.resetForTest();
        OrbitRequestManager.setWorkCanceller(name -> {});
        TestWorkManager.ensureInitialized(context);
        context.getSharedPreferences("orbit_pending_requests", Context.MODE_PRIVATE).edit().clear().commit();
        SecureStore.clearChatGpt(context);
    }

    @After public void tearDown() {
        OrbitRequestManager.resetForTest();
        AiProviders.installForTest(null);
        OrbitModelCatalog.clearDynamicForTest();
        TestKeystore.uninstall();
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static String jwt(long exp) throws Exception {
        JSONObject auth = new JSONObject().put("chatgpt_account_id", "acct-test")
                .put("chatgpt_plan_type", "plus");
        JSONObject claims = new JSONObject().put("email", "user@example.com").put("exp", exp)
                .put("https://api.openai.com/auth", auth);
        String payload = Base64.encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        return "e30." + payload + ".sig";
    }

    private void signInChatGpt() throws Exception {
        long exp = System.currentTimeMillis() / 1000L + 3600L;
        ChatGptAuth.storeSignIn(context, jwt(exp), jwt(exp), "refresh-test");
        assertTrue(ChatGptAuth.isSignedIn(context));
    }

    /** A chat whose first message has been saved, as the composer does before queueing. */
    private String chat(AiSelection selection, String prompt) {
        String id = ConversationStore.newId();
        ConversationStore.save(context, id, Collections.singletonList(
                new AssistantClient.History("user", prompt)));
        ConversationStore.setSelection(context, id, selection);
        return id;
    }

    /** A chat that already has one answered exchange. */
    private String answered(AiSelection selection, ResponseDetails details) {
        String id = ConversationStore.newId();
        ConversationStore.save(context, id, Arrays.asList(
                new AssistantClient.History("user", "Explain tides"),
                new AssistantClient.History("assistant", "Answer A").withDetails(details)
                        .withReplyProvenance("first", Collections.emptyList())));
        ConversationStore.setSelection(context, id, selection);
        return id;
    }

    private PendingRequestStore.Item send(String id, String prompt, AiSelection selection) {
        String requestId = OrbitRequestManager.enqueue(context, id, prompt, "",
                new ArrayList<>(), false, false, selection, false, null);
        return PendingRequestStore.load(context, requestId);
    }

    private Map<String, Object> autoPrefs() {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<String, ?> e : Prefs.get(context).getAll().entrySet()) {
            if (e.getKey().startsWith("auto_use_")) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** A provider that records what it was asked and answers nothing. */
    private static final class Recorder implements AiProvider {
        final List<AiRequest> seen = new ArrayList<>();
        @Override public String id() { return Prefs.PROVIDER_CHATGPT; }
        @Override public String displayName() { return "Recorder"; }
        @Override public String description() { return ""; }
        @Override public AiCapabilities capabilities() { return AiCapabilities.builder().build(); }
        @Override public Status status(Context c) { return Status.READY; }
        @Override public String statusDetail(Context c) { return ""; }
        @Override public boolean selectable(Context c) { return true; }
        @Override public void send(Context c, AiRequest r, AssistantClient.Callback cb) { seen.add(r); }
        @Override public void plan(Context c, String p, AiSelection s, AssistantClient.PlanCallback cb) {}
    }

    private static final class Outcome implements AssistantClient.Callback {
        String error = "";
        @Override public void onSuccess(AssistantReply reply) {}
        @Override public void onError(String message) { error = message; }
    }

    private static String source(String file) {
        return ComponentUninstallTest.readRepositoryFile("app/src/main/java/com/orbit/assistant/" + file);
    }

    // ---- Auto as a selection ---------------------------------------------------------------------

    @Test public void autoIsAFirstClassSelectionWithNoModelOrStrength() {
        AiSelection decoded = AiSelection.decode(AiSelection.AUTO.encode());
        assertNotNull(decoded);
        assertTrue(decoded.isAuto());
        assertEquals(AiSelection.AUTO, AiSelections.resolve(decoded));
        assertTrue(AiSelections.isValid(AiSelection.AUTO));
        assertEquals("Auto", AiSelection.AUTO.label());
        assertTrue(AiSelections.strengthsFor(AiSelection.AUTO).isEmpty());
        assertNull(AiSelections.specFor(AiSelection.AUTO));
        assertEquals("a strength cannot be attached to Auto", AiSelection.AUTO,
                AiSelections.withStrength(AiSelection.AUTO, AiStrength.HIGH));
        assertFalse("an explicit model is never Auto", TERRA.isAuto());
        assertEquals("leaving Auto for a named model lands on that model's own provider",
                Prefs.PROVIDER_ANTHROPIC,
                AiSelections.withModel(AiSelection.AUTO, OrbitModelCatalog.CLAUDE_SONNET_5_5).provider);
    }

    @Test public void autoIsPerChatAndChangesNothingElse() {
        String a = chat(TERRA, "first");
        String b = chat(TERRA, "second");
        AiSelection global = AiSelections.globalDefault(context);
        String defaultsBefore = Prefs.get(context).getString(Prefs.AI_PROVIDER_DEFAULTS, "{}");
        AiSelections.setForConversation(context, a, AiSelection.AUTO);
        assertTrue(AiSelections.forConversation(context, a).isAuto());
        assertTrue("it survives a reload", ConversationStore.load(context, a).aiSelection.isAuto());
        assertEquals(TERRA, AiSelections.forConversation(context, b));
        assertEquals(global, AiSelections.globalDefault(context));
        assertFalse(AiSelections.newChatsUseAuto(context));
        assertEquals("Auto is nobody's provider default", defaultsBefore,
                Prefs.get(context).getString(Prefs.AI_PROVIDER_DEFAULTS, "{}"));
    }

    @Test public void anUpgradedBeta4ChatKeepsItsExplicitModel() {
        // Beta 4 state: migrated, an explicit default, a chat on Terra, no Auto keys at all.
        Prefs.get(context).edit().putBoolean(AiSelections.MIGRATED_CHATS, true)
                .putString(Prefs.PROVIDER, Prefs.PROVIDER_CHATGPT)
                .putString(Prefs.AI_DEFAULT_MODEL, OrbitModelCatalog.SOL)
                .putString(Prefs.AI_DEFAULT_STRENGTH, "high").commit();
        String id = chat(TERRA, "old chat");
        AiSelections.ensureMigrated(context);
        assertEquals(TERRA, AiSelections.forConversation(context, id));
        assertFalse(AiSelections.newChatsUseAuto(context));
        assertFalse(AiSelections.newChatSelection(context).isAuto());
        assertFalse(AutoPermissions.allows(context, Prefs.PROVIDER_ANTHROPIC));
        assertFalse(AutoPermissions.allows(context, Prefs.PROVIDER_XAI));
    }

    @Test public void autoCanBeTheDefaultForNewChatsWithoutReplacingTheExplicitDefault() {
        AiSelections.setGlobalDefault(context, TERRA_HIGH);
        AiSelections.setGlobalDefault(context, AiSelection.AUTO);
        assertTrue(AiSelections.newChatsUseAuto(context));
        assertEquals("planning and other non-chat work keep an exact model", TERRA_HIGH,
                AiSelections.globalDefault(context));
        assertTrue(AiSelections.newChatSelection(context).isAuto());
        String fresh = ConversationStore.newId();
        ConversationStore.save(context, fresh, Collections.singletonList(
                new AssistantClient.History("user", "hello")));
        assertTrue("a new chat starts on Auto", AiSelections.forConversation(context, fresh).isAuto());
        AiSelections.setGlobalDefault(context, TERRA);
        assertFalse(AiSelections.newChatsUseAuto(context));
        assertTrue("an existing chat keeps Auto", AiSelections.forConversation(context, fresh).isAuto());
    }

    // ---- permissions and cost safety -------------------------------------------------------------

    @Test public void meteredProvidersStartOffAndTheOthersOn() {
        assertTrue(AutoPermissions.allows(context, Prefs.PROVIDER_CHATGPT));
        assertTrue(AutoPermissions.allows(context, Prefs.PROVIDER_LOCAL));
        assertFalse(AutoPermissions.allows(context, Prefs.PROVIDER_ANTHROPIC));
        assertFalse(AutoPermissions.allows(context, Prefs.PROVIDER_XAI));
        assertFalse("the relay is never an Auto provider",
                AutoPermissions.allows(context, Prefs.PROVIDER_RELAY));
        assertFalse(AutoPermissions.allows(context, Prefs.PROVIDER_OPENROUTER));
    }

    @Test public void aConnectedProviderIsNotAnEnabledOne() {
        assertTrue(SecureStore.saveAnthropicKey(context, "sk-ant-test-routing"));
        assertTrue(SecureStore.saveXaiKey(context, "xai-test-routing"));
        assertFalse(AutoPermissions.allows(context, Prefs.PROVIDER_ANTHROPIC));
        assertFalse(AutoPermissions.allows(context, Prefs.PROVIDER_XAI));
        for (SmartRouter.Option o : SmartRouter.options(context)) {
            // The two providers connected above; OpenRouter (also metered since 0.8.3.0-beta.6) is
            // not connected here, and SmartRouting11Test covers it.
            if (Prefs.PROVIDER_ANTHROPIC.equals(o.candidate.provider)
                    || Prefs.PROVIDER_XAI.equals(o.candidate.provider)) {
                assertTrue(o.candidate.model + " is connected", o.ready);
                assertFalse(o.candidate.model + " is not enabled", o.permitted);
            }
        }
        // With nothing else ready, Auto names Anthropic and still does not use it.
        SmartRouter.Route route = SmartRouter.route(context,
                new SmartRouter.Request("Explain tides", 0, 0, 2_000, 200));
        assertFalse(route.ok());
        assertTrue(route.error, route.error.contains("is not enabled for Auto"));
    }

    @Test public void enablingWorksAndRemovingTheKeyTurnsItBackOff() {
        assertTrue(SecureStore.saveAnthropicKey(context, "sk-ant-test-routing"));
        AutoPermissions.set(context, Prefs.PROVIDER_ANTHROPIC, true);
        SmartRouter.Route route = SmartRouter.route(context,
                new SmartRouter.Request("Explain tides", 0, 0, 2_000, 200));
        assertTrue(route.ok());
        assertEquals(Prefs.PROVIDER_ANTHROPIC, route.selection.provider);

        SecureStore.clearAnthropicKey(context);
        assertFalse("removed key: excluded", AutoPermissions.allows(context, Prefs.PROVIDER_ANTHROPIC));
        assertFalse(SmartRouter.route(context,
                new SmartRouter.Request("Explain tides", 0, 0, 2_000, 200)).ok());
        assertTrue(SecureStore.saveAnthropicKey(context, "sk-ant-test-replacement"));
        assertFalse("a new key never re-enables Auto by itself",
                AutoPermissions.allows(context, Prefs.PROVIDER_ANTHROPIC));

        assertTrue(SecureStore.saveXaiKey(context, "xai-test-routing"));
        AutoPermissions.set(context, Prefs.PROVIDER_XAI, true);
        assertEquals(Prefs.PROVIDER_XAI, SmartRouter.route(context,
                new SmartRouter.Request("Explain tides", 0, 0, 2_000, 200)).selection.provider);
        SecureStore.clearXaiKey(context);
        assertFalse(AutoPermissions.allows(context, Prefs.PROVIDER_XAI));
    }

    @Test public void migrationAndOtherSettingsNeverEnableAPaidProvider() {
        Prefs.get(context).edit().putString(Prefs.INTELLIGENCE_MODE, "auto").commit();
        AiSelections.ensureMigrated(context);
        AiSelections.setGlobalDefault(context, AiSelection.AUTO);
        AiSelections.setGlobalDefault(context, SONNET);
        AiProviders.select(context, Prefs.PROVIDER_ANTHROPIC);
        assertFalse(AutoPermissions.allows(context, Prefs.PROVIDER_ANTHROPIC));
        assertFalse(AutoPermissions.allows(context, Prefs.PROVIDER_XAI));
    }

    @Test public void routingCannotChangeAPermission() throws Exception {
        signInChatGpt();
        assertTrue(SecureStore.saveAnthropicKey(context, "sk-ant-test-routing"));
        Map<String, Object> before = autoPrefs();
        for (String prompt : new String[]{"hi", "Explain tides", "Prove it step by step and debug it"}) {
            SmartRouter.route(context, new SmartRouter.Request(prompt, 0, 0, 2_000, 200));
            SmartRouter.route(context, new SmartRouter.Request(prompt, 3, 0, 900_000, 900_000));
        }
        assertEquals(before, autoPrefs());
        for (String file : new String[]{"SmartRouter.java", "OrbitRequestManager.java",
                "OrbitRequestWorker.java", "AssistantClient.java"}) {
            assertFalse(file, source(file).contains("AutoPermissions.set("));
            assertFalse(file, source(file).contains("AUTO_USE_"));
        }
    }

    @Test public void onlyNonMeteredAutoChoicesTravelInABackup() throws Exception {
        for (String provider : AutoPermissions.PROVIDERS) AutoPermissions.set(context, provider, true);
        AiSelections.setGlobalDefault(context, AiSelection.AUTO);
        String backup = Prefs.backupSnapshot(context).toString();
        assertTrue(backup.contains(Prefs.AUTO_USE_CHATGPT));
        assertTrue(backup.contains(Prefs.AUTO_USE_LOCAL));
        assertTrue(backup.contains(Prefs.AI_DEFAULT_AUTO));
        assertFalse(backup.contains(Prefs.AUTO_USE_ANTHROPIC));
        assertFalse(backup.contains(Prefs.AUTO_USE_XAI));
    }

    // ---- resolution before queueing --------------------------------------------------------------

    @Test public void anExplicitSelectionNeverReachesTheRouter() throws Exception {
        signInChatGpt();
        String id = chat(TERRA, "Prove it step by step and debug it rigorously");
        PendingRequestStore.Item item = send(id, "Prove it step by step and debug it rigorously", TERRA);
        assertNull(item.route);
        assertEquals("exactly the chosen model, however hard the question", TERRA, item.selection);
        assertFalse(item.autoRouted());
        // The router is called from one place only: queueing an Auto request.
        for (String file : new String[]{"AssistantClient.java", "OrbitRequestWorker.java",
                "ChatActivity.java", "OrbitSession.java", "AiSelectorDialog.java",
                "ModelLibraryDialog.java", "AnthropicProvider.java", "XaiProvider.java",
                "ChatGptProvider.java", "OrbitLocalProvider.java", "ApiKeyProviderClient.java"}) {
            assertFalse(file + " must not route", source(file).contains("SmartRouter.route("));
        }
        assertTrue(source("OrbitRequestManager.java").contains("SmartRouter.route("));
    }

    @Test public void autoResolvesOnceWhenQueuedAndNothingLaterReroutes() throws Exception {
        signInChatGpt();
        String id = chat(AiSelection.AUTO, "Explain tides");
        PendingRequestStore.Item item = send(id, "Explain tides", AiSelection.AUTO);
        assertNotNull(item.route);
        assertTrue(item.route.ok());
        assertEquals(LUNA_MEDIUM, item.selection);
        assertTrue(item.requestedSelection().isAuto());
        assertEquals(SmartRouter.NORMAL, item.route.reason);
        assertEquals("a new Auto route is written with Smart Routing 1.1's policy", 2, item.route.policy);

        // Everything that might tempt a re-route changes underneath the queued request.
        AutoPermissions.set(context, Prefs.PROVIDER_CHATGPT, false);
        AiSelections.setForConversation(context, id, SONNET);
        OrbitModelCatalog.installDynamic(Prefs.PROVIDER_ANTHROPIC, Collections.singletonList(
                OrbitModelCatalog.spec(Prefs.PROVIDER_ANTHROPIC, OrbitModelCatalog.CLAUDE_OPUS_5_5)));
        PendingRequestStore.markRunning(context, item.id);
        PendingRequestStore.Item reread = PendingRequestStore.load(context, item.id);
        assertEquals(LUNA_MEDIUM, reread.selection);
        assertEquals(SmartRouter.NORMAL, reread.route.reason);
        assertTrue(reread.autoRouted());
        // The worker sends the stored selection and is handed the stored route; it has no router.
        assertFalse(source("OrbitRequestWorker.java").contains("SmartRouter.route("));
        assertTrue(source("OrbitRequestWorker.java").contains("AssistantClient.Routing.of(item)"));
    }

    @Test public void anAutoRequestWithNoPermittedModelSendsNothing() {
        // ChatGPT is signed out, Local is not installed, and Anthropic/xAI are off.
        String id = chat(AiSelection.AUTO, "Explain tides");
        PendingRequestStore.Item item = send(id, "Explain tides", AiSelection.AUTO);
        assertTrue("nothing was chosen", item.selection.isAuto());
        assertFalse(item.route.ok());
        assertTrue(item.route.error.startsWith("No Auto-enabled provider is currently available."));

        Recorder recorder = new Recorder();
        AiProviders.installForTest(recorder);
        Outcome outcome = new Outcome();
        AssistantClient.send(context, "Tell me about tides and the moon", "", new ArrayList<>(),
                new ArrayList<>(), item.selection, false, "", KeptContext.Prepared.NONE,
                () -> false, AssistantClient.Routing.of(item), outcome);
        assertTrue("no provider is asked anything", recorder.seen.isEmpty());
        assertEquals(item.route.error, outcome.error);
    }

    @Test public void routedTurnsNeverEnterRecentsButChosenOnesDo() {
        Recorder recorder = new Recorder();
        AiProviders.installForTest(recorder);
        AssistantClient.send(context, "Tell me about tides and the moon", "", new ArrayList<>(),
                new ArrayList<>(), LUNA_MEDIUM, false, "", KeptContext.Prepared.NONE, () -> false,
                new AssistantClient.Routing(true, ""), new Outcome());
        assertEquals("the routed request was sent", 1, recorder.seen.size());
        assertEquals(LUNA_MEDIUM, recorder.seen.get(0).selection);
        assertTrue("Auto's choice is not a Recent", ModelLibraryStore.recents(context).isEmpty());
        assertEquals("nor a provider default", "{}",
                Prefs.get(context).getString(Prefs.AI_PROVIDER_DEFAULTS, "{}"));

        AssistantClient.send(context, "Tell me about tides and the moon", "", new ArrayList<>(),
                new ArrayList<>(), TERRA, false, "", KeptContext.Prepared.NONE, () -> false,
                AssistantClient.Routing.EXPLICIT, new Outcome());
        assertEquals(Collections.singletonList(TERRA), ModelLibraryStore.recents(context));
        ModelLibraryStore.recordRecent(context, AiSelection.AUTO);
        assertEquals("Auto itself is never a Recent", 1, ModelLibraryStore.recents(context).size());
    }

    // ---- Send with and Retry with ----------------------------------------------------------------

    @Test public void sendWithAutoRoutesOneMessageAndTheChatStaysExplicit() throws Exception {
        signInChatGpt();
        String id = chat(TERRA, "Explain tides");
        PendingRequestStore.Item once = send(id, "Explain tides", AiSelection.AUTO);
        assertTrue(once.autoRouted());
        assertFalse(once.selection.isAuto());
        assertEquals(TERRA, AiSelections.forConversation(context, id));
        PendingRequestStore.Item next = send(id, "And the moon?", AiSelections.forConversation(context, id));
        assertNull("the next normal turn is explicit again", next.route);
        assertEquals(TERRA, next.selection);
    }

    @Test public void sendWithAModelFromAnAutoChatIsExactAndTheChatStaysAuto() throws Exception {
        signInChatGpt();
        String id = chat(AiSelection.AUTO, "Explain tides");
        PendingRequestStore.Item once = send(id, "Explain tides", SONNET);
        assertNull(once.route);
        assertEquals(SONNET, once.selection);
        assertTrue(AiSelections.forConversation(context, id).isAuto());
        PendingRequestStore.Item next = send(id, "And the moon?", AiSelections.forConversation(context, id));
        assertTrue("the next normal turn is Auto again", next.autoRouted());
    }

    @Test public void retryWithAutoAddsARoutedVersionBesideTheOriginal() throws Exception {
        signInChatGpt();
        String id = answered(TERRA, ResponseDetails.sentWith(TERRA));
        String requestId = OrbitRequestManager.enqueueAnswerVariant(context, id, 1, "Explain tides",
                "", new ArrayList<>(), AiSelection.AUTO, false, null);
        PendingRequestStore.Item item = PendingRequestStore.load(context, requestId);
        assertTrue(item.isAnswerVariant());
        assertTrue(item.autoRouted());
        assertEquals(LUNA_MEDIUM, item.selection);

        // What the worker does with a model's answer to a routed request.
        ResponseDetails details = ResponseDetails.sentWith(item.selection).withElapsed(900)
                .withRoute(item.route);
        OrbitRequestWorker.completeProviderReply(context, item,
                new AssistantReply("Answer B").withDetails(details), WorkerAttempt.of(1, false));
        ConversationStore.Conversation chat = ConversationStore.load(context, id);
        assertEquals("Answer B", chat.messages.get(1).content);
        assertTrue(chat.messages.get(1).details.autoRouted());
        assertEquals(OrbitModelCatalog.LUNA, chat.messages.get(1).details.model);
        assertEquals("the original answer is kept", "Answer A",
                chat.forkAt(1).variants.get(0).messages.get(0).content);
        assertFalse(chat.forkAt(1).variants.get(0).messages.get(0).details.autoRouted());
        assertEquals("Retry with never changes the chat's AI", TERRA,
                AiSelections.forConversation(context, id));
    }

    @Test public void retryWithAModelOnAnAutoAnswerIsExact() throws Exception {
        signInChatGpt();
        SmartRouter.Route route = new SmartRouter.Route(LUNA_MEDIUM, SmartRouter.NORMAL, 1, "");
        String id = answered(AiSelection.AUTO, ResponseDetails.sentWith(LUNA_MEDIUM).withRoute(route));
        String requestId = OrbitRequestManager.enqueueAnswerVariant(context, id, 1, "Explain tides",
                "", new ArrayList<>(), SONNET, false, null);
        PendingRequestStore.Item item = PendingRequestStore.load(context, requestId);
        assertTrue(item.isAnswerVariant());
        assertNull(item.route);
        assertEquals(SONNET, item.selection);
    }

    @Test public void retryingAFailedAutoRequestAsksAutoAgain() throws Exception {
        String id = chat(AiSelection.AUTO, "Explain tides");
        PendingRequestStore.Item failed = send(id, "Explain tides", AiSelection.AUTO);
        assertFalse(failed.route.ok());
        PendingRequestStore.markFailed(context, failed.id, failed.route.error);
        signInChatGpt();
        String retried = OrbitRequestManager.retry(context, failed.id, null);
        PendingRequestStore.Item item = PendingRequestStore.load(context, retried);
        assertTrue(item.route.ok());
        assertEquals(LUNA_MEDIUM, item.selection);
    }

    @Test public void retryOfAnExplicitRequestStaysExact() throws Exception {
        signInChatGpt();
        String id = chat(TERRA, "Explain tides");
        PendingRequestStore.Item failed = send(id, "Explain tides", TERRA);
        PendingRequestStore.markFailed(context, failed.id, "network");
        PendingRequestStore.Item item = PendingRequestStore.load(context,
                OrbitRequestManager.retry(context, failed.id, null));
        assertNull(item.route);
        assertEquals(TERRA, item.selection);
    }

    // ---- branches and context --------------------------------------------------------------------

    @Test public void onlyTheActivePathIsMeasuredForRouting() throws Exception {
        signInChatGpt();
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 70_000; i++) huge.append("tidal flats ");
        String id = ConversationStore.newId();
        AssistantClient.History original = new AssistantClient.History("user", "Summarize this",
                true, Collections.emptyList(), "file_text", "notes.txt", huge.toString(), "", "", "", "");
        ConversationStore.save(context, id, Arrays.asList(original,
                new AssistantClient.History("assistant", "Long summary")));
        ConversationStore.BranchResult branched = ConversationStore.branchFromUserMessage(context, id,
                0, ConversationBranches.fingerprint(original),
                new AssistantClient.History("user", "Explain tides"));
        assertTrue(branched.ok());
        ConversationStore.Conversation chat = ConversationStore.load(context, id);
        SmartRouter.Route hidden = OrbitRequestManager.routeAuto(context, id, chat.messages,
                "Explain tides", "", new ArrayList<>(), false);
        assertTrue(hidden.ok());
        assertEquals("the hidden branch's document is not part of the request", SmartRouter.NORMAL,
                hidden.reason);

        SmartRouter.Request active = SmartRouter.describe(context, chat.messages, "Explain tides", "",
                new ArrayList<>(), false, KeptContext.Prepared.NONE);
        SmartRouter.Request withDoc = SmartRouter.describe(context,
                Collections.singletonList(original), "Summarize this", huge.toString(),
                new ArrayList<>(), true, KeptContext.Prepared.NONE);
        assertTrue("the hidden branch's document is not measured", active.contentTokens < 2_000);
        // The same document on the active path is counted, exactly as the request builder places
        // it: an attachment is capped at its 105,000-character share, never counted in full.
        assertTrue(withDoc.contentTokens > 20_000);
        assertTrue(withDoc.contentTokens < 40_000);
    }

    @Test public void theMeterNeverInventsALimitForAuto() {
        String id = chat(AiSelection.AUTO, "Explain tides in some detail please");
        ContextEstimate auto = ContextEstimate.measure(context, id, AiSelection.AUTO,
                ContextEstimate.Draft.EMPTY);
        assertTrue(auto.auto);
        assertEquals(0, auto.limit);
        assertEquals(-1, auto.percent());
        assertEquals(ContextEstimate.Level.UNKNOWN, auto.level());
        assertFalse(auto.nearlyFull());
        assertTrue(auto.tokens > 0);
        assertTrue(ContextMeterView.describe(auto).contains("Auto chooses the model per request"));

        ContextEstimate explicit = ContextEstimate.measure(context, id, TERRA, ContextEstimate.Draft.EMPTY);
        assertFalse(explicit.auto);
        assertEquals(OrbitModelCatalog.OPENAI_CONTEXT_WINDOW, explicit.limit);
        assertEquals("the same content is measured either way", explicit.tokens, auto.tokens);
    }

    // ---- Response details ------------------------------------------------------------------------

    @Test public void responseDetailsShowWhatAutoChoseAndWhy() throws Exception {
        SmartRouter.Route route = new SmartRouter.Route(SONNET, "Large context + complex reasoning",
                1, "");
        ResponseDetails d = ResponseDetails.sentWith(SONNET).withElapsed(2400).withRoute(route);
        List<String> rows = new ArrayList<>();
        for (String[] row : d.rows()) rows.add(row[0] + "=" + row[1]);
        assertEquals(Arrays.asList("Selection=Auto", "Provider=Anthropic Claude",
                "Model=Claude Sonnet 5.5", "Strength=High", "Why=Large context + complex reasoning",
                "Router policy=1", "Response time=2.4 s"), rows);

        JSONObject stored = d.toJson();
        assertEquals(new java.util.TreeSet<>(Arrays.asList("provider", "model", "modelName",
                "strength", "elapsedMs", "requested", "routeReason", "routerPolicy")),
                new java.util.TreeSet<>(keys(stored)));
        ResponseDetails back = ResponseDetails.fromJson(stored);
        assertTrue(back.autoRouted());
        assertEquals(1, back.routerPolicy);
        assertEquals(d.rows().size(), back.rows().size());

        JSONObject explicit = ResponseDetails.sentWith(TERRA).withElapsed(1000).toJson();
        assertFalse("an explicit answer's record is unchanged", explicit.has("requested"));
        assertFalse(explicit.has("routeReason"));
        List<String> plain = new ArrayList<>();
        for (String[] row : ResponseDetails.fromJson(explicit).rows()) plain.add(row[0]);
        assertEquals(Arrays.asList("Provider", "Model", "Strength", "Response time"), plain);
    }

    private static List<String> keys(JSONObject o) {
        List<String> out = new ArrayList<>();
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) out.add(it.next());
        return out;
    }

    // ---- surfaces --------------------------------------------------------------------------------

    @Test public void thePickerOffersAutoFirstWithoutStrengthControls() {
        AiSelectorDialog picker = AiSelectorDialog.forTest(context, TERRA);
        assertNotNull(picker.autoRowForTest());
        assertEquals(View.GONE, picker.autoProvidersForTest().getVisibility());
        assertFalse(picker.offeredStrengths().isEmpty());
        picker.chooseAutoForTest();
        assertTrue(picker.current().isAuto());
        assertTrue("Auto has no strength chips", picker.offeredStrengths().isEmpty());
        assertFalse("and no 'no strength' note either", picker.noStrengthNoteShownForTest());
        assertEquals(View.VISIBLE, picker.autoProvidersForTest().getVisibility());
        assertTrue(picker.autoProvidersForTest().getText().toString().contains("ChatGPT"));
        assertFalse(picker.autoProvidersForTest().getText().toString().contains("Anthropic"));
        assertTrue(picker.autoRowForTest().getContentDescription().toString().endsWith("Selected"));
    }

    @Test public void leavingAutoRestoresTheStrengthLastUsedWithThatModel() {
        ModelLibraryStore.rememberProviderDefault(context, TERRA_HIGH);
        AiSelectorDialog picker = AiSelectorDialog.forTest(context, AiSelection.AUTO);
        assertTrue(picker.offeredModels().contains(OrbitModelCatalog.GPT_5_6_TERRA));
        picker.chooseSpecForTest(OrbitModelCatalog.spec(Prefs.PROVIDER_CHATGPT,
                OrbitModelCatalog.GPT_5_6_TERRA));
        assertEquals(TERRA_HIGH, picker.current());
        assertFalse(picker.offeredStrengths().isEmpty());
    }

    @Test public void anAutoChatHeaderShowsAutoAndNoStrengthPill() {
        String id = ConversationStore.newId();
        ConversationStore.save(context, id, Arrays.asList(new AssistantClient.History("user", "hello"),
                new AssistantClient.History("assistant", "hi")));
        AiSelections.setForConversation(context, id, AiSelection.AUTO);
        ChatActivity chat = Robolectric.buildActivity(ChatActivity.class,
                new Intent(context, ChatActivity.class)
                        .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, id)).setup().get();
        assertTrue(chat.currentSelectionForTest().isAuto());
        assertTrue(chat.modelPillForTest().getText().toString().startsWith("Auto"));
        assertTrue(chat.modelPillForTest().getContentDescription().toString().contains("Auto"));
        assertEquals(View.GONE, chat.strengthPillForTest().getVisibility());
        chat.applySelection(TERRA_HIGH);
        assertEquals(View.VISIBLE, chat.strengthPillForTest().getVisibility());
        assertTrue(chat.strengthPillForTest().getText().toString().startsWith("High"));
        chat.applySelection(AiSelection.AUTO);
        assertEquals(View.GONE, chat.strengthPillForTest().getVisibility());
        assertTrue(AiSelections.forConversation(context, id).isAuto());
    }

    @Test public void theAutoSheetStartsSafeAndNotesCreditsOnlyWhileOff() {
        View sheet = AutoSheets.settingsContent(context, null);
        Map<String, Boolean> switches = AutoSheets.switchesForTest(sheet);
        assertEquals(Boolean.TRUE, switches.get(Prefs.PROVIDER_CHATGPT));
        assertEquals(Boolean.FALSE, switches.get(Prefs.PROVIDER_ANTHROPIC));
        assertEquals(Boolean.FALSE, switches.get(Prefs.PROVIDER_XAI));
        assertTrue(AutoSheets.describe(context, Prefs.PROVIDER_ANTHROPIC, false)
                .contains(AutoSheets.METERED_NOTE));
        assertFalse("no repeated warning once opted in",
                AutoSheets.describe(context, Prefs.PROVIDER_XAI, true).contains("credits"));
        assertTrue(AutoSheets.introNeeded(context));
        AutoSheets.markIntroSeen(context);
        assertFalse(AutoSheets.introNeeded(context));
    }

    // ---- internal jobs and privacy ---------------------------------------------------------------

    @Test public void internalJobsKeepTheirFixedExplicitPolicy() {
        assertFalse(ConversationTitlePolicy.CHATGPT_TITLE_SELECTION.isAuto());
        assertFalse(ContinueChat.SUMMARY_SELECTION.isAuto());
        assertFalse(AiSelections.SMART_VAULT_ENRICHMENT.isAuto());
        for (String file : new String[]{"ConversationTitleManager.java", "ConversationTitlePolicy.java",
                "ContinueChat.java", "SmartVaultEnrichment.java", "ProviderCatalogRepository.java"}) {
            String source;
            try {
                source = source(file);
            } catch (RuntimeException missing) {
                continue;
            }
            assertFalse(file, source.contains("SmartRouter"));
            assertFalse(file, source.contains("AiSelection.AUTO"));
            assertFalse(file, source.contains("recordRecent"));
        }
    }

    @Test public void routingNeverSendsAnythingAnywhere() {
        String router = source("SmartRouter.java");
        for (String call : new String[]{"HttpURLConnection", "URL(", ".send(", ".complete(",
                ".plan(", "ApiKeyProviderClient", "ChatGptClient.send", "SecureStore.load"}) {
            assertFalse("the router must not call " + call, router.contains(call));
        }
    }
}
