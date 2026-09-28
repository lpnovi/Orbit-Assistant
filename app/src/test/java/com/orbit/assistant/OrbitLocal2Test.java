package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.provider.Settings;

import org.robolectric.RuntimeEnvironment;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orbit Local 2.0 end to end on Orbit's side: what the provider actually hands the component for
 * Ask Vault, attachments, pictures, notifications and ordinary chat, and what it never does.
 *
 * <p>No model runs here, and none needs to. Every promise below is a property of the prompt Orbit
 * builds and of the paths the provider can take, and both are fully visible without inference.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class OrbitLocal2Test {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        NotificationStore.clear(context);
    }

    @After public void tearDown() {
        NotificationStore.clear(context);
    }

    private static String source(String simpleName) {
        return ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/" + simpleName + ".java");
    }

    private LocalContextBudget.Result fitted(AiRequest request) {
        return OrbitLocalProvider.buildPrompt(context, request);
    }

    // ---- ordinary chat keeps working ------------------------------------------------------------------

    @Test public void ordinaryChatCarriesMemoryHistoryAndTheQuestion() {
        List<AssistantClient.History> history = new ArrayList<>();
        history.add(new AssistantClient.History("user", "I'm planning a trip to Lisbon."));
        history.add(new AssistantClient.History("assistant", "Lovely. When are you going?"));
        history.add(new AssistantClient.History("user", "In May. What should I pack?"));
        LocalContextBudget.Result r = fitted(AiRequest.builder()
                .prompt("In May. What should I pack?")
                .history(history)
                .memoryContext("Orbit Memory: the user prefers light luggage.")
                .build());
        assertEquals(LocalContextBudget.PATH_CHAT, r.path);
        assertTrue(r.prompt.startsWith(OrbitLocalProvider.SYSTEM));
        assertTrue(r.prompt.contains("the user prefers light luggage"));
        assertTrue(r.prompt.contains("User: I'm planning a trip to Lisbon."));
        assertTrue(r.prompt.endsWith("User: In May. What should I pack?\nOrbit:"));
        assertTrue(r.memoryUsed);
        assertEquals(2, r.historyTurnsUsed);
    }

    @Test public void currentScreenTextIsUsedWhenScreenContextIsOn() {
        Prefs.get(context).edit().putBoolean(Prefs.SCREEN_CONTEXT, true).commit();
        LocalContextBudget.Result on = fitted(AiRequest.builder()
                .prompt("What is this page about?").screenText("Recipe: lemon tart").build());
        assertTrue(on.prompt.contains("<untrusted_screen_content>\nRecipe: lemon tart"));
        Prefs.get(context).edit().putBoolean(Prefs.SCREEN_CONTEXT, false).commit();
        LocalContextBudget.Result off = fitted(AiRequest.builder()
                .prompt("What is this page about?").screenText("Recipe: lemon tart").build());
        assertFalse(off.prompt.contains("lemon tart"));
    }

    // ---- Ask Vault ---------------------------------------------------------------------------------------

    @Test public void askVaultPassagesReachLocalWithTheirSourceLabels() {
        OrbitVaultItem salmon = OrbitVaultStore.saveText(context, "Salmon dinner",
                "Bake the salmon at 200C for 12 minutes, skin side down.", "t");
        OrbitVaultItem permit = OrbitVaultStore.saveText(context, "Parking permit",
                "Renew the parking permit online before the end of March.", "t");
        ComposerAttachment attachment = SmartVaultAsk.attachment(context,
                "What temperature do I bake the salmon at?",
                Arrays.asList(salmon.id, permit.id));
        assertNotNull(attachment);

        LocalContextBudget.Result r = fitted(AiRequest.builder()
                .prompt("What temperature do I bake the salmon at?")
                .screenText(attachment.contextText)
                .explicitAttachment(true)
                .build());
        assertEquals(LocalContextBudget.PATH_ASK_VAULT, r.path);
        assertTrue(r.prompt.contains("200C"));
        assertTrue(r.prompt.contains("[1: Salmon dinner]"));
        assertFalse("an item with nothing to do with the question is not spent on",
                r.prompt.contains("parking permit online"));
        assertEquals(1, r.vaultSources.size());

        String answer = OrbitLocalProvider.withNotes("Bake it at 200C [1: Salmon dinner].", r);
        assertTrue("Orbit names what the model read, whatever the model cites",
                answer.endsWith("Checked in your Vault: [1] Salmon dinner"));
    }

    @Test public void withLocalActiveAskVaultStagesOnlyWhatLocalWillRead() {
        assertEquals(LocalContextBudget.MAX_VAULT_ITEMS, 3);
        assertTrue(SmartVaultAsk.MAX_ITEMS > LocalContextBudget.MAX_VAULT_ITEMS);
        List<OrbitVaultItem> ranked = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ranked.add(OrbitVaultStore.saveText(context, "Note " + i, "body " + i, "t"));
        }
        assertEquals(3, SmartVaultAsk.pick(ranked, LocalContextBudget.MAX_VAULT_ITEMS).size());
        assertEquals("cloud providers keep the five they always had",
                5, SmartVaultAsk.pick(ranked).size());
        String vault = source("OrbitVaultActivity");
        assertTrue(vault.contains("SmartVaultAsk.pick(ranked, SmartVaultAsk.maxItems(this))"));
        assertTrue(source("SmartVaultAsk").contains("SmartVault.localProviderActive(c)"));
    }

    @Test public void askVaultExcerptsNoLongerCarryAStrayYear() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 60; i++) body.append("Filler sentence number ").append(i).append(". ");
        body.append("The spare key is under the blue flowerpot. ");
        for (int i = 0; i < 60; i++) body.append("More filler text ").append(i).append(". ");
        String part = SmartVaultAsk.relevantPart(body.toString(), "where is the spare key", null);
        assertFalse(part.contains("[2026]"));
        assertFalse(part.contains(" 2026 "));
    }

    // ---- attachments and pictures ------------------------------------------------------------------------

    @Test public void attachedTextReachesLocalAndOversizedTextIsBounded() {
        StringBuilder text = new StringBuilder("The user explicitly attached the text file \"log.txt\".\n\n");
        for (int i = 0; i < 5000; i++) text.append("line ").append(i).append(" of the log. ");
        text.append("ERROR: disk quota exceeded on volume B.");
        LocalContextBudget.Result r = fitted(AiRequest.builder()
                .prompt("Why did the backup fail? Look for the disk error.")
                .screenText(text.toString()).explicitAttachment(true).build());
        assertEquals(LocalContextBudget.PATH_ATTACHMENTS, r.path);
        assertTrue(r.evidenceTrimmed);
        assertTrue(r.estimatedTokens <= LocalContextBudget.inputBudgetTokens(4096));
        assertTrue(OrbitLocalProvider.withNotes("It ran out of space.", r)
                .endsWith(OrbitLocalProvider.EXCERPT_NOTE));
    }

    @Test public void aPictureWithNothingReadableIsExplainedNotGuessedAt() {
        Bitmap photo = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        LocalContextBudget.Result r = fitted(AiRequest.builder()
                .prompt("What breed is this dog?")
                .images(Collections.singletonList(photo))
                .explicitAttachment(true).build());
        assertTrue(r.imageOnly);
        assertTrue(OrbitLocalProvider.IMAGE_ONLY_REPLY.startsWith("Orbit Local can't look at pictures"));
        assertTrue(OrbitLocalProvider.IMAGE_ONLY_REPLY.contains("Nothing was sent anywhere"));
        String provider = source("OrbitLocalProvider");
        int imageOnly = provider.indexOf("if (fitted.imageOnly) {");
        int generate = provider.indexOf("OrbitLocalClient.generate(");
        assertTrue("the explanation is given before any generation is started",
                imageOnly > 0 && imageOnly < generate);
    }

    @Test public void textOrbitReadFromAPictureIsUsedAndTheAnswerSaysSo() {
        Bitmap photo = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        LocalContextBudget.Result r = fitted(AiRequest.builder()
                .prompt("What is the total?")
                .images(Collections.singletonList(photo))
                .screenText("Text Orbit recognised in the saved picture:\nTOTAL 18.20")
                .explicitAttachment(true).build());
        assertFalse(r.imageOnly);
        assertTrue(r.prompt.contains("TOTAL 18.20"));
        assertTrue(OrbitLocalProvider.withNotes("The total is 18.20.", r)
                .endsWith(OrbitLocalProvider.PICTURE_TEXT_NOTE));
    }

    @Test public void aPdfPreviewIsNotTreatedAsAPictureTheUserAskedAbout() {
        Bitmap preview = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        List<AssistantClient.History> history = new ArrayList<>();
        history.add(new AssistantClient.History("user", "Summarize this", true, "", "pdf",
                "lease.pdf", "PDF text"));
        AiRequest request = AiRequest.builder()
                .prompt("Summarize this")
                .history(history)
                .images(Collections.singletonList(preview))
                .screenText("===== PDF PAGE 1 OF 1 =====\nThe lease ends on 1 June.")
                .explicitAttachment(true).build();
        assertEquals(0, OrbitLocalProvider.picturesIn(request));
        LocalContextBudget.Result r = fitted(request);
        assertFalse(r.prompt.contains("You cannot see pictures"));
        assertFalse(OrbitLocalProvider.withNotes("It ends in June.", r)
                .contains(OrbitLocalProvider.PICTURE_TEXT_NOTE));
    }

    // ---- notifications -------------------------------------------------------------------------------------

    private void grantNotificationAccess() {
        Settings.Secure.putString(context.getContentResolver(), "enabled_notification_listeners",
                context.getPackageName() + "/" + OrbitNotificationListenerService.class.getName());
    }

    private void notification(String key, String pkg, String app, String title, String text) {
        NotificationStore.upsert(context, new NotificationStore.Item(key, pkg, app, title, text,
                "", "", System.currentTimeMillis() - 60_000L, 0L, false));
    }

    @Test public void aLocalNotificationQuestionReceivesThePreparedHistory() {
        grantNotificationAccess();
        notification("a", "com.whatsapp", "WhatsApp", "Jamie", "Running ten minutes late");
        notification("b", "com.bank", "Bank", "Card used", "Payment of 12.00 at Cafe");
        NotificationStore.setBlocked(context, "com.bank", true);

        NotificationQueryHelper.Prepared prepared =
                NotificationQueryHelper.prepare(context, "What did I miss?");
        assertTrue(prepared.recognized);
        assertNull(prepared.localReply);

        LocalContextBudget.Result r = fitted(AiRequest.builder()
                .prompt("What did I miss?")
                .notificationContext(prepared.context).build());
        assertEquals(LocalContextBudget.PATH_NOTIFICATIONS, r.path);
        assertTrue(r.prompt.contains("Running ten minutes late"));
        assertFalse("an app the user excluded never reaches the model",
                r.prompt.contains("Cafe"));
        assertEquals(1, r.notificationsUsed);
        assertTrue(r.prompt.contains("<untrusted_notifications"));
    }

    @Test public void notificationIntelligenceTurnedOffStillAnswersLocallyWithoutHistory() {
        grantNotificationAccess();
        notification("a", "com.whatsapp", "WhatsApp", "Jamie", "Running late");
        Prefs.get(context).edit().putBoolean(Prefs.NOTIFICATION_AI_ENABLED, false).commit();
        NotificationQueryHelper.Prepared prepared =
                NotificationQueryHelper.prepare(context, "What did I miss?");
        assertNotNull("Orbit answers itself; no provider sees a thing", prepared.localReply);
        assertEquals("", prepared.context);
    }

    @Test public void theProviderReadsNotificationContextThroughTheSharedRequest() {
        // One pipeline: the context Orbit prepares before any provider is the one Local uses.
        String pipeline = source("AssistantClient");
        assertTrue(pipeline.contains(".notificationContext(notificationContext)"));
        assertTrue(source("OrbitLocalProvider")
                .contains("in.notificationContext = request.notificationContext;"));
    }

    // ---- privacy -------------------------------------------------------------------------------------------

    @Test public void theLocalPathHasNoRouteToAnyOtherProviderOrTheNetwork() {
        for (String name : new String[]{"OrbitLocalProvider", "LocalContextBudget",
                "OrbitLocalCapabilities"}) {
            String text = source(name);
            for (String forbidden : new String[]{"ChatGptClient", "ChatGptProvider", "RelayProvider",
                    "OpenRouterProvider", "AiProviders.active(context)", "AiProviders.byId(", "HttpURLConnection", "java.net.",
                    "OkHttp"}) {
                assertFalse(name + " must not reference " + forbidden, text.contains(forbidden));
            }
        }
    }

    @Test public void aLocalFailureIsTerminalAndNeverRetriedElsewhere() {
        AtomicReference<String> error = new AtomicReference<>();
        AtomicReference<AssistantReply> reply = new AtomicReference<>();
        new OrbitLocalProvider().send(context, AiRequest.builder().prompt("hello").build(),
                new AssistantClient.Callback() {
                    @Override public void onSuccess(AssistantReply r) { reply.set(r); }
                    @Override public void onError(String message) { error.set(message); }
                });
        assertNull(reply.get());
        assertNotNull("without its component, Orbit Local says so", error.get());
        assertTrue(error.get().contains("Orbit Local"));
    }

    @Test public void diagnosticsRecordStructureNotContent() {
        DiagnosticStore.recordLocalRequest(context, "ask-vault", "vault 2/3, notifications 0/0",
                1800, 3072, true, 900L, 5200L, "answered");
        String store = DiagnosticStore.prefs(context).getAll().toString();
        assertTrue(store.contains("ask-vault"));
        assertTrue(store.contains("answered"));
        String recorder = source("DiagnosticStore");
        int at = recorder.indexOf("public static void recordLocalRequest(");
        String signature = recorder.substring(at, recorder.indexOf('{', at));
        for (String forbidden : new String[]{"prompt", "answer", "text", "memory", "title"}) {
            assertFalse("the recorder cannot receive " + forbidden,
                    signature.toLowerCase(java.util.Locale.US).contains("string " + forbidden));
        }
    }

    // ---- the component connection -----------------------------------------------------------------------

    @Test public void aGenerationEndsExactlyOnceEvenIfTheComponentDiesAsItFinishes() {
        List<String> events = new ArrayList<>();
        OrbitLocalClient.StreamCallback once = OrbitLocalClient.terminalOnce(
                new OrbitLocalClient.StreamCallback() {
                    @Override public void onPartial(String t) { events.add("partial"); }
                    @Override public void onDone(String t) { events.add("done"); }
                    @Override public void onError(String m) { events.add("error"); }
                });
        once.onPartial("a");
        once.onDone("ab");
        once.onError(OrbitLocalClient.STOPPED_UNEXPECTEDLY);
        once.onPartial("abc");
        once.onDone("abc");
        assertEquals(Arrays.asList("partial", "done"), events);
    }

    @Test public void aDeadComponentBecomesAClearLocalError() {
        String client = source("OrbitLocalClient");
        assertTrue(client.contains("binder.linkToDeath(death, 0)"));
        assertTrue(client.contains("once.onError(STOPPED_UNEXPECTEDLY)"));
        assertEquals("component-stopped",
                OrbitLocalProvider.failureCategory(OrbitLocalClient.STOPPED_UNEXPECTEDLY));
    }
}
