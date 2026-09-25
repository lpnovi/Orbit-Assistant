package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;

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

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Smart Vault end to end on the device side: what is gated behind which switch, what background
 * work may and may not write, and how the Vault behaves at its ceiling.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class SmartVaultBehaviourTest {

    private Context context;
    private SmartVaultOcr.Engine previousOcr;
    private SmartVaultPageReader.Source previousPages;
    private FakeProvider provider;

    /** A provider that answers with whatever the test sets, and can act while "in flight". */
    static final class FakeProvider implements AiProvider {
        boolean supports = true;
        String reply = "{\"title\":\"Miso salmon dinner\",\"summary\":\"A quick baked salmon "
                + "recipe.\",\"topics\":[\"Recipes\",\"fish\"]}";
        String error;
        Runnable duringRequest;
        final AtomicInteger calls = new AtomicInteger();
        String lastPrompt = "";

        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "FakeAI"; }
        @Override public String description() { return ""; }
        @Override public AiCapabilities capabilities() { return new AiCapabilities.Builder().build(); }
        @Override public Status status(Context c) { return Status.READY; }
        @Override public String statusDetail(Context c) { return ""; }
        @Override public boolean selectable(Context c) { return true; }
        @Override public void send(Context c, AiRequest r, AssistantClient.Callback cb) {
            throw new AssertionError("Smart Vault must never send a chat request");
        }
        @Override public void plan(Context c, String p, String m, AssistantClient.PlanCallback cb) {
            throw new AssertionError("Smart Vault must never plan a Routine");
        }
        @Override public boolean supportsCompletion(Context c) { return supports; }
        @Override public void complete(Context c, String instructions, String prompt,
                                       AssistantClient.PlanCallback cb) {
            calls.incrementAndGet();
            lastPrompt = prompt;
            if (duringRequest != null) duringRequest.run();
            if (error != null) cb.onError(error);
            else cb.onText(reply, "FakeAI · test");
        }
    }

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        OrbitVaultStore.prefs(context).edit().clear().commit();
        Prefs.get(context).edit().clear().commit();
        SmartVaultDb.resetForTest();
        context.deleteDatabase(SmartVaultDb.NAME);
        SmartVaultIndex.invalidate();
        TestWorkManager.ensureInitialized(context);
        File[] files = OrbitVaultMedia.directory(context).listFiles();
        if (files != null) for (File file : files) file.delete();
        previousOcr = SmartVaultOcr.installForTest(new SmartVaultOcr.Engine() {
            @Override public String recognize(Context c, Bitmap bitmap) { return ""; }
            @Override public void prepare(Context c) {}
        });
        previousPages = SmartVaultPageReader.installForTest(url ->
                new SmartVaultPageReader.Page(false, "", "", "TEST"));
        provider = new FakeProvider();
        AiProviders.installForTest(provider);
    }

    @After public void tearDown() {
        SmartVaultOcr.installForTest(previousOcr);
        SmartVaultPageReader.installForTest(previousPages);
        AiProviders.installForTest(null);
        SmartVaultDb.resetForTest();
    }

    private Bitmap picture() {
        Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.BLUE);
        return bitmap;
    }

    private void enable(boolean ocr, boolean links, boolean ai) {
        SmartVault.enable(context, ocr, false, links, ai);
    }

    private void recognizeAs(String text, Runnable during) {
        SmartVaultOcr.installForTest(new SmartVaultOcr.Engine() {
            @Override public String recognize(Context c, Bitmap bitmap) {
                if (during != null) during.run();
                return text;
            }
            @Override public void prepare(Context c) {}
        });
    }

    // ---- off means off -----------------------------------------------------------------------------

    @Test public void smartVaultIsOffByDefaultAndCreatesNothing() {
        assertFalse(Prefs.smartVaultEnabled(context));
        OrbitVaultStore.saveText(context, "", "A note", "test");
        assertFalse("no index database exists until Smart Vault is turned on",
                SmartVault.databaseExists(context));
        assertEquals(0, provider.calls.get());
    }

    @Test public void turningTheVaultOffAlsoTurnsSmartVaultOff() {
        enable(true, false, false);
        assertTrue(Prefs.smartVaultEnabled(context));
        Prefs.get(context).edit().putBoolean(Prefs.VAULT_ENABLED, false).commit();
        assertFalse(Prefs.smartVaultEnabled(context));
        assertFalse(Prefs.smartVaultOcr(context));
    }

    @Test public void existingItemsAreNeverQueuedForTheProviderAutomatically() {
        OrbitVaultItem old = OrbitVaultStore.saveText(context, "", "An older note", "test");
        enable(true, false, true);
        assertEquals("turning suggestions on queues nothing already saved",
                0, SmartVaultDb.get(context).queuedCount());
        OrbitVaultItem fresh = OrbitVaultStore.saveText(context, "", "A brand new note", "test");
        assertEquals(1, SmartVaultDb.get(context).queuedCount());
        assertNotNull(SmartVaultDb.get(context).job(fresh.id));
        assertNull(SmartVaultDb.get(context).job(old.id));
    }

    @Test public void withSuggestionsOffNewItemsAreNotQueued() {
        enable(true, false, false);
        OrbitVaultStore.saveText(context, "", "A brand new note", "test");
        assertEquals(0, SmartVaultDb.get(context).queuedCount());
    }

    // ---- local indexing ------------------------------------------------------------------------------

    @Test public void recognisedTextBecomesSearchable() {
        OrbitVaultItem shot = OrbitVaultStore.saveImage(context, picture(), "", "Screen selection");
        enable(true, false, false);
        recognizeAs("HomeNet password blue-otter-4417", null);
        SmartVaultWorker.indexLocally(context, null);

        SmartVaultIndex.Snapshot snapshot = SmartVaultIndex.build(context);
        List<SmartVaultRanker.Result> found = SmartVaultIndex.search(context, snapshot, "blue-otter");
        assertEquals(1, found.size());
        assertEquals(shot.id, found.get(0).id);
        assertEquals(SmartVaultRanker.Reason.RECOGNIZED, found.get(0).reason);
        assertEquals("the attachment carries it too", "HomeNet password blue-otter-4417",
                OrbitVaultAttachment.recognizedText(context, shot));
        assertEquals("ordinary search is unchanged and does not see derived text", 0,
                OrbitVaultStore.search(context, "blue-otter", OrbitVaultStore.Sort.NEWEST).size());
    }

    @Test public void recognitionNeverResurrectsAnItemDeletedWhileItRan() {
        OrbitVaultItem shot = OrbitVaultStore.saveImage(context, picture(), "", "Screen selection");
        enable(true, false, false);
        recognizeAs("secret text", () -> OrbitVaultStore.delete(context, shot.id));
        SmartVaultWorker.indexLocally(context, null);
        assertNull(OrbitVaultStore.get(context, shot.id));
        assertNull("nothing was written for the deleted item",
                SmartVaultDb.get(context).derived(shot.id, SmartVaultDb.KIND_OCR));
    }

    @Test public void recognitionThatIsUnavailableIsRetriedLaterRatherThanGivenUpOn() {
        OrbitVaultItem shot = OrbitVaultStore.saveImage(context, picture(), "", "Screen selection");
        enable(true, false, false);
        SmartVaultOcr.installForTest(new SmartVaultOcr.Engine() {
            @Override public String recognize(Context c, Bitmap b) throws Exception {
                throw new SmartVaultOcr.Unavailable("preparing");
            }
            @Override public void prepare(Context c) {}
        });
        for (int i = 0; i < 5; i++) SmartVaultWorker.indexLocally(context, null);
        assertEquals("no failed attempt was recorded", 0, SmartVaultDb.get(context)
                .attempts(shot.id, SmartVaultDb.KIND_OCR, shot.mediaPath));
        recognizeAs("finally", null);
        SmartVaultWorker.indexLocally(context, null);
        assertEquals("finally", OrbitVaultAttachment.recognizedText(context, shot));
    }

    @Test public void savedLinksAreReadOnlyWhenTheUserAllowsIt() {
        OrbitVaultItem link = OrbitVaultStore.saveLink(context, "", "https://example.com/a", "t");
        List<String> read = new ArrayList<>();
        SmartVaultPageReader.installForTest(url -> {
            read.add(url);
            return new SmartVaultPageReader.Page(true, "Sourdough guide",
                    "Feed the starter twice a day with flour and water.", "");
        });
        enable(true, false, false);
        SmartVaultWorker.indexLocally(context, null);
        assertTrue("links are not read while the switch is off", read.isEmpty());

        SmartVault.setOption(context, Prefs.SMART_VAULT_READ_LINKS, true);
        SmartVaultWorker.indexLocally(context, null);
        assertEquals(Collections.singletonList("https://example.com/a"), read);
        OrbitVaultItem after = OrbitVaultStore.get(context, link.id);
        assertEquals("the page title is only a suggestion", "", after.title);
        assertEquals("Sourdough guide", after.displayTitle());
        assertTrue(after.titleIsSuggested());
        SmartVaultIndex.Snapshot snapshot = SmartVaultIndex.build(context);
        assertEquals(link.id, SmartVaultIndex.search(context, snapshot, "starter").get(0).id);
        SmartVaultWorker.indexLocally(context, null);
        assertEquals("a page is read once", 1, read.size());
    }

    // ---- AI suggestions -----------------------------------------------------------------------------

    @Test public void suggestionsArriveWithoutTouchingAnythingTheUserWrote() {
        OrbitVaultItem item = OrbitVaultStore.saveText(context, "My recipe",
                "Bake salmon with miso at 200C for 12 minutes", "test", "from Sam");
        enable(true, false, false);
        SmartVault.queueSuggestions(context, Collections.singletonList(item.id));
        assertEquals(SmartVaultWorker.Outcome.DONE,
                SmartVaultWorker.suggestQueued(context, null, 10));
        OrbitVaultItem after = OrbitVaultStore.get(context, item.id);
        assertEquals("My recipe", after.title);
        assertEquals("My recipe", after.displayTitle());
        assertEquals("from Sam", after.note);
        assertEquals(item.body, after.body);
        assertEquals("a suggestion is not an edit", item.modifiedAt, after.modifiedAt);
        assertEquals("Miso salmon dinner", after.suggestions.title);
        assertEquals(Arrays.asList("recipes", "fish"), after.suggestedTopics());
        assertTrue(after.suggestionsAreCurrent());
        assertTrue("the request fences the item as untrusted data",
                provider.lastPrompt.contains("<saved_item>"));
    }

    @Test public void anEditMadeWhileSuggestionsWereInFlightWins() {
        OrbitVaultItem item = OrbitVaultStore.saveText(context, "", "Original text for the note",
                "test");
        enable(true, false, false);
        SmartVault.queueSuggestions(context, Collections.singletonList(item.id));
        provider.duringRequest = () -> OrbitVaultStore.updateText(context, item.id, "",
                "The user rewrote this note");
        SmartVaultWorker.suggestQueued(context, null, 10);
        OrbitVaultItem after = OrbitVaultStore.get(context, item.id);
        assertEquals("The user rewrote this note", after.body);
        assertNull("stale suggestions were discarded", after.suggestions);
        assertEquals("and the item is looked at again", 1, SmartVaultDb.get(context).queuedCount());

        provider.duringRequest = null;
        SmartVaultWorker.suggestQueued(context, null, 10);
        assertNotNull(OrbitVaultStore.get(context, item.id).suggestions);
    }

    @Test public void suggestionsNeverResurrectAnItemDeletedWhileInFlight() {
        OrbitVaultItem item = OrbitVaultStore.saveText(context, "", "Doomed note text", "test");
        enable(true, false, false);
        SmartVault.queueSuggestions(context, Collections.singletonList(item.id));
        provider.duringRequest = () -> OrbitVaultStore.delete(context, item.id);
        SmartVaultWorker.suggestQueued(context, null, 10);
        assertNull(OrbitVaultStore.get(context, item.id));
        assertEquals(0, OrbitVaultStore.count(context));
    }

    @Test public void aProviderThatCannotAnswerIsReportedAndNeverCalled() {
        OrbitVaultItem item = OrbitVaultStore.saveText(context, "", "Some text here", "test");
        enable(true, false, false);
        provider.supports = false;
        SmartVault.queueSuggestions(context, Collections.singletonList(item.id));
        SmartVaultWorker.suggestQueued(context, null, 10);
        assertEquals(0, provider.calls.get());
        assertEquals(SmartVaultDb.STATE_FAILED, SmartVaultDb.get(context).job(item.id).state);
        assertFalse(SmartVault.suggestionStatus(context, item.id).isEmpty());
        assertNull(OrbitVaultStore.get(context, item.id).suggestions);
    }

    @Test public void offlineWaitsAndAFailureLeavesTheItemAlone() {
        OrbitVaultItem item = OrbitVaultStore.saveText(context, "", "Some text here", "test");
        enable(true, false, false);
        SmartVault.queueSuggestions(context, Collections.singletonList(item.id));
        provider.error = "ChatGPT request failed: Unable to resolve host chatgpt.com";
        assertEquals(SmartVaultWorker.Outcome.RETRY,
                SmartVaultWorker.suggestQueued(context, null, 10));
        assertEquals(SmartVaultDb.STATE_QUEUED, SmartVaultDb.get(context).job(item.id).state);

        provider.error = "ChatGPT returned HTTP 400";
        SmartVaultWorker.suggestQueued(context, null, 10);
        assertEquals(SmartVaultDb.STATE_FAILED, SmartVaultDb.get(context).job(item.id).state);
        OrbitVaultItem after = OrbitVaultStore.get(context, item.id);
        assertNull(after.suggestions);
        assertEquals(item.modifiedAt, after.modifiedAt);
    }

    @Test public void anUnusableAnswerIsNeverStored() {
        OrbitVaultItem item = OrbitVaultStore.saveText(context, "", "Some text here", "test");
        enable(true, false, false);
        SmartVault.queueSuggestions(context, Collections.singletonList(item.id));
        provider.reply = "Sure! Here is a summary of your note.";
        SmartVaultWorker.suggestQueued(context, null, 10);
        SmartVaultWorker.suggestQueued(context, null, 10);
        assertNull(OrbitVaultStore.get(context, item.id).suggestions);
        assertEquals(SmartVaultDb.STATE_FAILED, SmartVaultDb.get(context).job(item.id).state);
    }

    @Test public void reviewingSuggestionsKeepsAndRemovesExactlyWhatTheUserChose() {
        OrbitVaultItem item = OrbitVaultStore.saveText(context, "", "Bake salmon with miso", "t");
        enable(true, false, false);
        SmartVault.queueSuggestions(context, Collections.singletonList(item.id));
        SmartVaultWorker.suggestQueued(context, null, 10);

        assertTrue(OrbitVaultStore.keepSuggestedTitle(context, item.id));
        OrbitVaultItem kept = OrbitVaultStore.get(context, item.id);
        assertEquals("Miso salmon dinner", kept.title);
        assertFalse(kept.titleIsSuggested());

        OrbitVaultStore.setTopics(context, item.id, Collections.singletonList("recipes"));
        OrbitVaultStore.updateSuggestions(context, item.id,
                OrbitVaultStore.get(context, item.id).suggestions.rejecting("fish"));
        OrbitVaultItem reviewed = OrbitVaultStore.get(context, item.id);
        assertEquals(Collections.singletonList("recipes"), reviewed.topics);
        assertTrue(reviewed.suggestedTopics().isEmpty());

        // Regenerating cannot bring back the rejected topic or replace the kept title.
        SmartVault.queueSuggestions(context, Collections.singletonList(item.id));
        SmartVaultWorker.suggestQueued(context, null, 10);
        OrbitVaultItem again = OrbitVaultStore.get(context, item.id);
        assertEquals("Miso salmon dinner", again.title);
        assertFalse(again.suggestedTopics().contains("fish"));
        assertEquals(Collections.singletonList("recipes"), again.topics);

        assertEquals(1, SmartVault.deleteData(context));
        OrbitVaultItem cleared = OrbitVaultStore.get(context, item.id);
        assertNull(cleared.suggestions);
        assertEquals("the user's title and topics stay", "Miso salmon dinner", cleared.title);
        assertEquals(Collections.singletonList("recipes"), cleared.topics);
    }

    @Test public void topicsNarrowTheVaultAndAreCountedOnce() {
        OrbitVaultItem a = OrbitVaultStore.saveText(context, "", "Salmon", "t");
        OrbitVaultItem b = OrbitVaultStore.saveText(context, "", "Flights", "t");
        OrbitVaultStore.setTopics(context, a.id, Collections.singletonList("Recipes"));
        OrbitVaultStore.applySuggestions(context, b.id, b.contentFingerprint(),
                new VaultSuggestions("", "", Arrays.asList("travel", "recipes"), null, "", 1L, "x"));
        assertEquals(Integer.valueOf(2), OrbitVaultStore.topicsInUse(context).get("recipes"));
        List<OrbitVaultItem> shown = OrbitVaultStore.browse(context,
                OrbitVaultFilter.NONE.withTopic("recipes"), OrbitVaultStore.Sort.NEWEST);
        assertEquals(2, shown.size());
        assertEquals(1, OrbitVaultStore.browse(context, OrbitVaultFilter.NONE.withTopic("travel"),
                OrbitVaultStore.Sort.NEWEST).size());
    }

    // ---- the ceiling -------------------------------------------------------------------------------

    @Test public void aRestoredVaultAboveTheCeilingIsReadInFullAndNothingIsLost() throws Exception {
        JSONArray items = new JSONArray();
        for (int i = 0; i < OrbitVaultStore.MAX_ITEMS + 10; i++) {
            items.put(new JSONObject().put("id", "id" + i).put("type", "text")
                    .put("body", "note " + i).put("createdAt", 1000L + i)
                    .put("modifiedAt", 1000L + i));
        }
        assertTrue(OrbitVaultStore.restoreBackupJson(context, items.toString()));
        assertEquals(OrbitVaultStore.MAX_ITEMS + 10, OrbitVaultStore.count(context));
        assertNull("a new save is refused", OrbitVaultStore.saveText(context, "", "new", "t"));
        assertTrue(OrbitVaultStore.delete(context, "id0"));
        assertEquals("deleting one keeps the rest", OrbitVaultStore.MAX_ITEMS + 9,
                OrbitVaultStore.count(context));
        assertTrue(OrbitVaultStore.savedMessage(context).contains("Saved"));
    }

    @Test public void anUnreadableDocumentIsSetAsideBeforeAnythingOverwritesIt() {
        OrbitVaultStore.prefs(context).edit().putString("items_v1", "[{broken").commit();
        assertEquals(0, OrbitVaultStore.count(context));
        OrbitVaultStore.saveText(context, "", "new note", "t");
        assertEquals("[{broken",
                OrbitVaultStore.prefs(context).getString("items_v1_unreadable", ""));
        OrbitVaultStore.deleteAllData(context);
        assertFalse(OrbitVaultStore.prefs(context).contains("items_v1_unreadable"));
    }

    @Test public void nearlyFullIsSaidBeforeTheVaultIsFull() {
        for (int i = 0; i < OrbitVaultStore.WARN_AT; i++) {
            OrbitVaultStore.saveText(context, "", "n" + i, "t");
        }
        assertTrue(OrbitVaultStore.isNearlyFull(context));
        assertFalse(OrbitVaultStore.isFull(context));
        assertTrue(OrbitVaultStore.savedMessage(context).contains("spaces left"));
    }

    // ---- Ask Vault ---------------------------------------------------------------------------------

    @Test public void askVaultFramesItemsAsUntrustedDataAndNumbersThem() {
        OrbitVaultItem a = OrbitVaultStore.saveText(context, "Salmon", "Bake at 200C", "t",
                "Sam's recipe");
        OrbitVaultItem b = OrbitVaultStore.saveLink(context, "", "https://example.com/x", "t");
        String text = SmartVaultAsk.contextText("What temperature?", Arrays.asList(a, b), null,
                null);
        assertTrue(text.contains("untrusted data"));
        assertTrue(text.contains("<vault_item number=\"1\" title=\"Salmon\">"));
        assertTrue(text.contains("<vault_item number=\"2\""));
        assertTrue(text.contains("Bake at 200C"));
        assertTrue(text.contains("Sam's recipe"));
        assertTrue(text.contains("[2: Title]"));
    }

    @Test public void askVaultSendsThePartOfALongItemThatAnswersTheQuestion() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 40; i++) body.append("Filler sentence number ").append(i).append(". ");
        body.append("The spare key is under the blue flowerpot by the back door. ");
        for (int i = 0; i < 40; i++) body.append("More filler text ").append(i).append(". ");
        String part = SmartVaultAsk.relevantPart(body.toString(), "where is the spare key", null);
        assertTrue(part.contains("blue flowerpot"));
        assertTrue(part.length() <= SmartVaultAsk.MAX_CHARS_PER_ITEM + 220);
    }

    @Test public void askVaultStagesOneAttachmentAndSendsNothing() {
        OrbitVaultItem a = OrbitVaultStore.saveText(context, "Salmon", "Bake at 200C", "t");
        OrbitVaultItem b = OrbitVaultStore.saveText(context, "Cookies", "Bake at 180C", "t");
        Intent intent = new Intent(context, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, "ask-vault-test")
                .putExtra(ChatActivity.EXTRA_ASK_VAULT_IDS, new String[]{a.id, b.id})
                .putExtra(ChatActivity.EXTRA_ASK_VAULT_QUESTION, "What temperature?");
        ChatActivity chat = Robolectric.buildActivity(ChatActivity.class, intent).setup().get();
        List<ComposerAttachment> staged = chat.pendingAttachments();
        assertEquals(1, staged.size());
        assertEquals(OrbitVaultAttachment.KIND, staged.get(0).kind);
        assertTrue(staged.get(0).label.contains("2 saved items"));
        assertTrue(staged.get(0).contextText.contains("Bake at 180C"));
        assertEquals(0, provider.calls.get());
        assertNull("nothing is written to the conversation",
                ConversationStore.load(context, "ask-vault-test"));
        assertNull(PendingRequestStore.load(context, "ask-vault-test"));
    }

    // ---- Screen Selection ------------------------------------------------------------------------

    private static android.view.View findByDescription(android.view.View view, String description) {
        CharSequence d = view.getContentDescription();
        if (d != null && description.contentEquals(d) && view.isClickable()) return view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.view.View found = findByDescription(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test public void aFullScreenSaveKeepsTheScreenText() {
        String path = ScreenSelectionStore.saveSource(context, picture());
        Intent intent = ScreenSelectionStore.editorIntent(context, path, "", "", "", null,
                "Boarding pass gate B32 seat 14A");
        ScreenSelectionActivity editor = Robolectric.buildActivity(ScreenSelectionActivity.class,
                intent).setup().get();
        android.view.View save = findByDescription(
                editor.getWindow().getDecorView(), "Save this selection to your Vault");
        assertNotNull(save);
        save.performClick();
        List<OrbitVaultItem> saved = OrbitVaultStore.list(context);
        assertEquals(1, saved.size());
        assertEquals("Boarding pass gate B32 seat 14A", saved.get(0).capturedText);
        assertTrue(OrbitVaultStore.search(context, "gate b32", OrbitVaultStore.Sort.NEWEST)
                .size() == 1);
        assertTrue(OrbitVaultStore.clearCapturedText(context, saved.get(0).id));
        assertEquals("", OrbitVaultStore.get(context, saved.get(0).id).capturedText);
    }
}
