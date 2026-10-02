package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Kept chat context and the context-window estimate (0.8.3.0-beta.3).
 *
 * <p>Kept context is stored once at chat level, sent with later requests, never sent twice in one
 * request, and removable. The estimate is the real request builder measuring itself, so everything
 * it counts is something a request would actually carry, and nothing hidden is counted.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class ConversationContextTest {
    private Context context;
    private static final AiSelection LUNA = AiSelection.of(Prefs.PROVIDER_CHATGPT,
            OrbitModelCatalog.LUNA, AiStrength.MEDIUM);

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        ConversationStore.clear(context);
        OrbitRequestManager.resetForTest();
    }

    private static String repeat(String word, int times) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < times; i++) out.append(word).append(' ');
        return out.toString().trim();
    }

    private AssistantClient.History pdfTurn(String question, String text) {
        return new AssistantClient.History("user", question, true, Collections.emptyList(), "pdf",
                "Research.pdf", text, "", "", "", "");
    }

    private String chatWithPdf(String pdfText) {
        String id = ConversationStore.newId();
        ConversationStore.save(context, id, Arrays.asList(pdfTurn("Summarize this", pdfText),
                new AssistantClient.History("assistant", "It is about rye.")));
        return id;
    }

    private KeptContext keepPdf(String id, String pdfText) {
        AssistantClient.History origin = ConversationStore.load(context, id).messages.get(0);
        KeptContext item = KeptContext.create("pdf", "Research.pdf", pdfText, "",
                ConversationBranches.fingerprint(origin), true);
        assertTrue(ConversationStore.keep(context, id, item));
        return item;
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
    }

    // ---- what can be kept --------------------------------------------------------------------------

    @Test public void onlyDurableExplicitContextCanBeKept() {
        assertTrue(KeptContext.isKeepable("pdf"));
        assertTrue(KeptContext.isKeepable("pdf_page"));
        assertTrue(KeptContext.isKeepable("file_text"));
        assertTrue(KeptContext.isKeepable("clipboard"));
        assertTrue(KeptContext.isKeepable("vault"));
        assertFalse("the live screen is never kept", KeptContext.isKeepable("screen"));
        assertFalse("a screen selection is never kept", KeptContext.isKeepable("screen_selection"));
        assertFalse("photos are never kept", KeptContext.isKeepable("image"));
        assertFalse(KeptContext.isKeepable("camera"));
        assertFalse(KeptContext.isKeepable(new ComposerAttachment("pdf", "Empty.pdf", "", null)));
        assertTrue(KeptContext.isKeepable(new ComposerAttachment("pdf", "Doc.pdf", "words", null)));
    }

    @Test public void keptContextSurvivesARestartAndIsRemovable() {
        String id = chatWithPdf("Rye flour ratio is thirty percent.");
        KeptContext item = keepPdf(id, "Rye flour ratio is thirty percent.");
        ConversationStore.Conversation reread = ConversationStore.list(context).get(0);
        assertEquals(1, reread.keptItems().size());
        assertEquals("Research.pdf", reread.keptItems().get(0).label);
        assertTrue("keeping the same item twice keeps it once",
                ConversationStore.keep(context, id, KeptContext.create("pdf", "Research.pdf",
                        "Rye flour ratio is thirty percent.", "", item.originKey, true)));
        assertEquals(1, ConversationStore.kept(context, id).size());

        assertTrue(ConversationStore.removeKept(context, id, item.id));
        assertTrue(ConversationStore.kept(context, id).isEmpty());
    }

    @Test public void keptContextIsBounded() {
        String id = chatWithPdf("text");
        for (int i = 0; i < KeptContext.MAX_ITEMS; i++) {
            assertTrue(ConversationStore.keep(context, id,
                    KeptContext.create("clipboard", "Note " + i, "note " + i, "", "", false)));
        }
        assertFalse(ConversationStore.keep(context, id,
                KeptContext.create("clipboard", "One too many", "x", "", "", false)));
    }

    @Test public void keptContextIsNotRepeatedOnLaterMessages() {
        String id = chatWithPdf("Rye flour ratio is thirty percent.");
        keepPdf(id, "Rye flour ratio is thirty percent.");
        ConversationStore.appendMessage(context, id, new AssistantClient.History("user", "And spelt?"));
        for (AssistantClient.History h : ConversationStore.load(context, id).messages.subList(1, 3)) {
            assertFalse("later messages carry no copy of the kept item", h.screenAttached);
            assertEquals("", h.attachmentText);
        }
    }

    // ---- the request ------------------------------------------------------------------------------

    @Test public void aKeptItemTravelsWithLaterRequestsExactlyOnce() throws Exception {
        String pdf = "UNIQUE-KEPT-PASSAGE about fermentation.";
        String id = chatWithPdf(pdf);
        keepPdf(id, pdf);
        ConversationStore.appendMessage(context, id, new AssistantClient.History("user", "And spelt?"));
        ConversationStore.Conversation chat = ConversationStore.load(context, id);
        KeptContext.Prepared kept = OrbitRequestWorker.keptFor(chat, chat.messages);
        JSONObject body = ChatGptClient.requestBody(context, "And spelt?", "", null, chat.messages,
                LUNA, false, "", "", "", false, false, OrbitModelCatalog.LUNA, kept, null);
        assertEquals("sent once: in the kept block, not again on the turn it came from", 1,
                count(body.toString(), "UNIQUE-KEPT-PASSAGE"));
        assertTrue(body.toString().contains("orbit_kept_context"));
    }

    @Test public void theMessageAKeptItemCameWithDoesNotSendItTwice() throws Exception {
        String pdf = "ORIGIN-PASSAGE text.";
        String id = ConversationStore.newId();
        ConversationStore.save(context, id, Collections.singletonList(pdfTurn("Summarize this", pdf)));
        keepPdf(id, pdf);
        ConversationStore.Conversation chat = ConversationStore.load(context, id);
        KeptContext.Prepared kept = OrbitRequestWorker.keptFor(chat, chat.messages);
        assertTrue("the current message already carries it", kept.block.isEmpty());
        JSONObject body = ChatGptClient.requestBody(context, "Summarize this", pdf, null,
                chat.messages, LUNA, true, "", "", "", false, false, OrbitModelCatalog.LUNA, kept, null);
        assertEquals(1, count(body.toString(), "ORIGIN-PASSAGE"));
    }

    @Test public void aRemovedItemStopsReachingRequests() throws Exception {
        String pdf = "REMOVED-PASSAGE text.";
        String id = chatWithPdf(pdf);
        KeptContext item = keepPdf(id, pdf);
        ConversationStore.removeKept(context, id, item.id);
        ConversationStore.appendMessage(context, id, new AssistantClient.History("user", "Next"));
        ConversationStore.Conversation chat = ConversationStore.load(context, id);
        assertTrue(OrbitRequestWorker.keptFor(chat, chat.messages).block.isEmpty());
    }

    @Test public void keptContextFollowsTheChatAcrossBranches() throws Exception {
        String pdf = "BRANCH-KEPT passage.";
        String id = chatWithPdf(pdf);
        keepPdf(id, pdf);
        String key = ConversationBranches.fingerprint(ConversationStore.load(context, id).messages.get(0));
        ConversationStore.branchFromUserMessage(context, id, 0, key,
                new AssistantClient.History("user", "Different question"));
        ConversationStore.Conversation chat = ConversationStore.load(context, id);
        assertEquals("kept context belongs to the chat, not a branch", 1, chat.keptItems().size());
        KeptContext.Prepared kept = OrbitRequestWorker.keptFor(chat, chat.messages);
        assertTrue(kept.block.contains("BRANCH-KEPT"));
    }

    @Test public void orbitLocalFitsKeptContextAsEvidence() {
        AiRequest request = AiRequest.builder().prompt("What ratio?")
                .history(Collections.singletonList(new AssistantClient.History("user", "What ratio?")))
                .keptContext(KeptContext.prepare(Collections.singletonList(KeptContext.create("pdf",
                        "Bread.pdf", "The ratio is LOCAL-RATIO thirty percent.", "", "", false)), ""))
                .build();
        LocalContextBudget.Result fitted = OrbitLocalProvider.buildPrompt(context, request);
        assertTrue(fitted.prompt.contains("LOCAL-RATIO"));
        assertTrue(fitted.prompt.contains("untrusted_attachment"));
    }

    // ---- the estimate -----------------------------------------------------------------------------

    @Test public void aShortChatIsALowApproximateShareOfAKnownWindow() {
        String id = ConversationStore.newId();
        ConversationStore.save(context, id, Arrays.asList(new AssistantClient.History("user", "Hi"),
                new AssistantClient.History("assistant", "Hello")));
        ContextEstimate estimate = ContextEstimate.measure(context, id, LUNA, ContextEstimate.Draft.EMPTY);
        assertTrue(estimate.knowsLimit());
        assertEquals(OrbitModelCatalog.OPENAI_CONTEXT_WINDOW, estimate.limit);
        assertTrue(estimate.tokens > 0);
        assertEquals(ContextEstimate.Level.NORMAL, estimate.level());
        assertTrue("an estimate is always marked", estimate.summary().startsWith("~"));
        assertTrue(estimate.breakdown.containsKey(ContextLedger.Category.CONVERSATION));
    }

    @Test public void theBreakdownReconcilesWithTheTotal() {
        String pdf = repeat("fermentation", 3000);
        String id = chatWithPdf(pdf);
        keepPdf(id, pdf);
        ContextEstimate estimate = ContextEstimate.measure(context, id, LUNA,
                new ContextEstimate.Draft("And spelt?", Collections.emptyList(), null));
        int sum = 0;
        for (int value : estimate.breakdown.values()) sum += value;
        assertTrue("categories add up to the total, give or take rounding",
                Math.abs(sum - estimate.tokens) <= estimate.breakdown.size());
        assertTrue(estimate.breakdown.containsKey(ContextLedger.Category.KEPT));
        for (Map.Entry<ContextLedger.Category, Integer> row : estimate.breakdown.entrySet()) {
            assertTrue("no zero rows", row.getValue() > 0);
        }
    }

    @Test public void attachmentsAndKeptContextIncreaseUsage() {
        String id = ConversationStore.newId();
        ConversationStore.save(context, id, Arrays.asList(new AssistantClient.History("user", "Hi"),
                new AssistantClient.History("assistant", "Hello")));
        int base = ContextEstimate.measure(context, id, LUNA, ContextEstimate.Draft.EMPTY).tokens;

        ComposerAttachment doc = new ComposerAttachment("pdf", "Doc.pdf", repeat("word", 2000), null);
        ContextEstimate withDraft = ContextEstimate.measure(context, id, LUNA,
                new ContextEstimate.Draft("Read this", Collections.singletonList(doc), null));
        assertTrue(withDraft.tokens > base + 400);
        assertTrue(withDraft.breakdown.containsKey(ContextLedger.Category.DOCUMENTS));

        ComposerAttachment vault = new ComposerAttachment("vault", "Vault: Notes", repeat("note", 800), null);
        ContextEstimate withVault = ContextEstimate.measure(context, id, LUNA,
                new ContextEstimate.Draft("Use this", Collections.singletonList(vault), null));
        assertTrue(withVault.breakdown.containsKey(ContextLedger.Category.VAULT));

        assertEquals("a one-turn draft attachment does not stay once the draft is gone", base,
                ContextEstimate.measure(context, id, LUNA, ContextEstimate.Draft.EMPTY).tokens);

        ConversationStore.keep(context, id, KeptContext.create("file_text", "Notes.txt",
                repeat("kept", 2000), "", "", false));
        assertTrue(ContextEstimate.measure(context, id, LUNA, ContextEstimate.Draft.EMPTY).tokens > base + 400);
    }

    @Test public void aDraftImageIsCountedWithoutBeingEncoded() {
        String id = ConversationStore.newId();
        Bitmap photo = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
        ComposerAttachment image = new ComposerAttachment("image", "Photo", "", photo);
        ContextEstimate estimate = ContextEstimate.measure(context, id, LUNA,
                new ContextEstimate.Draft("What is this?", Collections.singletonList(image), null));
        assertEquals(Integer.valueOf(ContextLedger.TOKENS_PER_IMAGE),
                estimate.breakdown.get(ContextLedger.Category.IMAGES));
    }

    @Test public void hiddenBranchesAndVariantsAreNotCounted() {
        String big = repeat("HIDDEN", 4000);
        String id = ConversationStore.newId();
        ConversationStore.save(context, id, Arrays.asList(new AssistantClient.History("user", "Q"),
                new AssistantClient.History("assistant", big)));
        int withBig = ContextEstimate.measure(context, id, LUNA, ContextEstimate.Draft.EMPTY).tokens;
        String parent = ConversationBranches.parentKey(ConversationStore.load(context, id).messages, 1);
        ConversationStore.commitAnswerVariant(context, id, 1, parent,
                new AssistantClient.History("assistant", "Short").withReplyProvenance("r2", Collections.emptyList()));
        int withShort = ContextEstimate.measure(context, id, LUNA, ContextEstimate.Draft.EMPTY).tokens;
        assertTrue("only the visible variant counts", withShort < withBig - 500);
    }

    @Test public void theDenominatorFollowsTheSelectedModel() {
        String id = ConversationStore.newId();
        for (String model : OrbitModelCatalog.currentModelIds()) {
            AiModelSpec spec = OrbitModelCatalog.spec(Prefs.PROVIDER_CHATGPT, model);
            if (spec == null) continue;
            ContextEstimate estimate = ContextEstimate.measure(context, id,
                    AiSelection.of(Prefs.PROVIDER_CHATGPT, model, spec.defaultStrength),
                    ContextEstimate.Draft.EMPTY);
            assertEquals(spec.contextWindowTokens, estimate.limit);
        }
    }

    @Test public void anUnknownWindowShowsNoPercentage() {
        ContextEstimate local = new ContextEstimate(3000, 0, Collections.emptyMap(), 0, true, "Orbit Local");
        assertFalse(local.knowsLimit());
        assertEquals(-1, local.percent());
        assertEquals(ContextEstimate.Level.UNKNOWN, local.level());
        assertFalse(local.nearlyFull());
        assertEquals("~3K tokens", local.summary());
    }

    @Test public void levelsAreCalmAndNeverExceedAFullWindow() {
        assertEquals(ContextEstimate.Level.NORMAL, of(40).level());
        assertEquals(ContextEstimate.Level.FILLING, of(72).level());
        assertEquals(ContextEstimate.Level.HIGH, of(88).level());
        assertEquals(ContextEstimate.Level.CRITICAL, of(97).level());
        assertFalse(of(85).nearlyFull());
        assertTrue(of(91).nearlyFull());
        ContextEstimate over = new ContextEstimate(250_000, 128_000, Collections.emptyMap(), 0, false, "");
        assertEquals(100, over.percent());
        assertEquals(1f, over.fraction(), 0.0001f);
        assertEquals("~250K / 128K · 100%", over.summary());
    }

    @Test public void numbersAreCompactAndApproximate() {
        assertEquals("~850", ContextEstimate.approx(853));
        assertEquals("~4.2K", ContextEstimate.approx(4210));
        assertEquals("~38K", ContextEstimate.approx(38_400));
        assertEquals("1.05M", ContextEstimate.exact(OrbitModelCatalog.OPENAI_CONTEXT_WINDOW));
        assertEquals("128K", ContextEstimate.exact(128_000));
    }

    @Test public void theBreakdownExposesNoPromptText() {
        ContextLedger ledger = new ContextLedger(true);
        ledger.text(ContextLedger.Category.INSTRUCTIONS, "SECRET SYSTEM PROMPT");
        assertFalse(ledger.breakdown().toString().contains("SECRET"));
        for (ContextLedger.Category category : ContextLedger.Category.values()) {
            assertFalse(category.label.toLowerCase(java.util.Locale.US).contains("system prompt"));
        }
    }

    private static ContextEstimate of(int percent) {
        return new ContextEstimate(percent * 1000, 100_000, Collections.emptyMap(), 0, false, "");
    }

    // ---- continue in new chat ----------------------------------------------------------------------

    @Test public void aContinuationIsANewEmptyChatThatStartsWithItsContext() {
        String old = ConversationStore.newId();
        ConversationStore.save(context, old, Arrays.asList(new AssistantClient.History("user", "Q"),
                new AssistantClient.History("assistant", "A")));
        String fresh = ConversationStore.newId();
        assertTrue(ConversationStore.createContinuation(context, fresh, LUNA, Collections.singletonList(
                KeptContext.create(KeptContext.KIND_SUMMARY, "Summary of Q", "Facts so far.", "", "", false))));
        ConversationStore.Conversation chat = ConversationStore.load(context, fresh);
        assertTrue(chat.messages.isEmpty());
        assertEquals(ConversationStore.NEW_CHAT_TITLE, chat.title);
        assertEquals(ConversationStore.TITLE_DEFAULT, chat.titleOwner);
        assertEquals(1, chat.keptItems().size());
        assertEquals("the original is untouched", 2, ConversationStore.load(context, old).messages.size());

        ConversationStore.save(context, fresh, Collections.singletonList(new AssistantClient.History("user", "Go on")));
        assertEquals("the carried context survives the first message", 1,
                ConversationStore.kept(context, fresh).size());
    }

    @Test public void theSummaryPromptCarriesNoHiddenInstructions() {
        String prompt = ContinueChat.summaryPrompt("Trip", Arrays.asList(
                new AssistantClient.History("user", "Plan Lisbon"),
                new AssistantClient.History("assistant", "Day one: Alfama")), Collections.emptyList());
        assertTrue(prompt.contains("Plan Lisbon"));
        assertFalse("no Orbit instructions or request state travel with it",
                prompt.contains("Current local time") || prompt.contains("orbit_kept_context"));
        assertNull(ContinueChat.cleanSummary("   "));
        assertNotNull(ContinueChat.cleanSummary("Facts: one."));
    }
}
