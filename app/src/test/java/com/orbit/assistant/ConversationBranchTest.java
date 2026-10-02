package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Conversation branches and answer variants (0.8.3.0-beta.3): the stored model.
 *
 * <p>The promises, in the order they matter: a chat that never branched is stored and read exactly
 * as before; branching never destroys the original timeline; only the visible path is ever the
 * conversation a request is built from; and damaged or stale branch data costs hidden alternatives,
 * never the conversation.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class ConversationBranchTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        ConversationStore.clear(context);
        context.getSharedPreferences("orbit_action_results", Context.MODE_PRIVATE).edit().clear().commit();
    }

    private static AssistantClient.History user(String text) {
        return new AssistantClient.History("user", text);
    }

    private static AssistantClient.History answer(String text, String requestId) {
        return new AssistantClient.History("assistant", text)
                .withReplyProvenance(requestId, Collections.emptyList());
    }

    private String linearChat(String... texts) {
        String id = ConversationStore.newId();
        List<AssistantClient.History> history = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            history.add(i % 2 == 0 ? user(texts[i]) : answer(texts[i], "r" + i));
        }
        ConversationStore.save(context, id, history);
        return id;
    }

    private static List<String> contents(List<AssistantClient.History> messages) {
        List<String> out = new ArrayList<>();
        for (AssistantClient.History h : messages) out.add(h.content);
        return out;
    }

    private ConversationStore.Conversation load(String id) {
        return ConversationStore.load(context, id);
    }

    private String key(String id, int index) {
        return ConversationBranches.fingerprint(load(id).messages.get(index));
    }

    // ---- old chats -------------------------------------------------------------------------------

    @Test public void aLinearChatIsStoredExactlyAsBeforeWithNoBranchKeys() throws Exception {
        String id = linearChat("Plan a trip", "Here is a plan", "Make it shorter", "Shorter plan");
        JSONObject stored = new JSONArray(ConversationStore.backupJsonForTest(context)).getJSONObject(0);
        assertFalse("a chat that never branched carries no forks key", stored.has("forks"));
        assertFalse("a chat that keeps nothing carries no kept key", stored.has("kept"));
        ConversationStore.Conversation chat = load(id);
        assertFalse(chat.isBranched());
        assertTrue(chat.keptItems().isEmpty());
        assertEquals(4, chat.messages.size());
    }

    @Test public void aBeta2RecordWithoutBranchDataLoadsAsOnePath() throws Exception {
        JSONObject legacy = new JSONObject().put("id", "old").put("title", "Old chat")
                .put("updatedAt", 5L).put("messages", new JSONArray()
                        .put(new JSONObject().put("role", "user").put("content", "Hi"))
                        .put(new JSONObject().put("role", "assistant").put("content", "Hello")));
        ConversationStore.restoreBackupJson(context, new JSONArray().put(legacy).toString());
        ConversationStore.Conversation chat = load("old");
        assertEquals(Arrays.asList("Hi", "Hello"), contents(chat.messages));
        assertFalse(chat.isBranched());
        assertNull("an old answer has no versions", chat.forkAt(1));
    }

    // ---- editing makes a real branch ----------------------------------------------------------------

    @Test public void editingAnEarlierMessageKeepsTheOriginalBranchIntact() {
        String id = linearChat("Recipe for bread", "Flour, water, salt", "With rye?", "Use 30% rye");
        ConversationStore.BranchResult result = ConversationStore.branchFromUserMessage(context, id,
                0, key(id, 0), user("Recipe for sourdough"));
        assertTrue(result.error, result.ok());
        assertEquals("the visible path ends at the edited message, waiting for its own answer",
                Collections.singletonList("Recipe for sourdough"), contents(result.messages));

        ConversationStore.Conversation chat = load(id);
        ConversationBranches.Fork fork = chat.forkAt(0);
        assertNotNull(fork);
        assertEquals(2, fork.count());
        assertEquals(1, fork.selected);
        assertEquals("the original timeline is stored whole",
                Arrays.asList("Recipe for bread", "Flour, water, salt", "With rye?", "Use 30% rye"),
                contents(fork.variants.get(0).messages));

        ConversationStore.BranchResult back = ConversationStore.selectVariant(context, id, 0, 0);
        assertTrue(back.ok());
        assertEquals(Arrays.asList("Recipe for bread", "Flour, water, salt", "With rye?", "Use 30% rye"),
                contents(back.messages));
        assertEquals("switching does not lose the edited branch", 2, load(id).forkAt(0).count());
    }

    @Test public void anEditedBranchGetsItsOwnAnswerAndBothBranchesSurviveARestart() {
        String id = linearChat("Name a color", "Blue", "Another", "Green");
        ConversationStore.branchFromUserMessage(context, id, 2, key(id, 2), user("A warm one"));
        ConversationStore.appendMessage(context, id, answer("Orange", "r9"));
        assertEquals(Arrays.asList("Name a color", "Blue", "A warm one", "Orange"),
                contents(load(id).messages));

        // A fresh read is what a restart or a process death sees.
        ConversationStore.Conversation reread = ConversationStore.list(context).get(0);
        assertEquals(Arrays.asList("Another", "Green"),
                contents(reread.forkAt(2).variants.get(0).messages));
        assertEquals(Arrays.asList("Name a color", "Blue", "A warm one", "Orange"),
                contents(reread.messages));
    }

    @Test public void anEditIsRefusedWhenTheMessageChangedUnderIt() {
        String id = linearChat("First", "One", "Second", "Two");
        ConversationStore.BranchResult wrong = ConversationStore.branchFromUserMessage(context, id,
                2, key(id, 0), user("Edited"));
        assertFalse(wrong.ok());
        assertFalse("nothing is written for a refused edit", load(id).isBranched());
        assertEquals(4, load(id).messages.size());
    }

    @Test public void editingNeverDuplicatesTheUserPromptOnTheVisiblePath() {
        String id = linearChat("Hello", "Hi there");
        ConversationStore.branchFromUserMessage(context, id, 0, key(id, 0), user("Hello again"));
        int users = 0;
        for (AssistantClient.History h : load(id).messages) if ("user".equals(h.role)) users++;
        assertEquals(1, users);
    }

    // ---- retry becomes a variant ------------------------------------------------------------------

    @Test public void aRetryAddsAVariantBesideTheOriginalAnswer() {
        String id = linearChat("Explain gravity", "Answer A");
        String parent = ConversationBranches.parentKey(load(id).messages, 1);
        assertTrue(ConversationStore.commitAnswerVariant(context, id, 1, parent, answer("Answer B", "rB")));
        assertTrue(ConversationStore.commitAnswerVariant(context, id, 1, parent, answer("Answer C", "rC")));

        ConversationStore.Conversation chat = load(id);
        assertEquals("the question appears once", Arrays.asList("Explain gravity", "Answer C"),
                contents(chat.messages));
        ConversationBranches.Fork fork = chat.forkAt(1);
        assertEquals(3, fork.count());
        assertEquals(2, fork.selected);
        assertEquals("Answer A", fork.variants.get(0).messages.get(0).content);
        assertEquals("Answer B", fork.variants.get(1).messages.get(0).content);

        ConversationStore.selectVariant(context, id, 1, 0);
        assertEquals("Answer A", load(id).messages.get(1).content);
        assertEquals(0, load(id).forkAt(1).selected);
    }

    @Test public void aVariantKeepsWhichModelAnsweredIt() {
        String id = linearChat("Summarize", "Short summary");
        String parent = ConversationBranches.parentKey(load(id).messages, 1);
        ResponseDetails astra = ResponseDetails.sentWith(AiSelection.of(Prefs.PROVIDER_CHATGPT,
                OrbitModelCatalog.ASTRA, AiStrength.HIGH));
        ConversationStore.commitAnswerVariant(context, id, 1, parent,
                answer("Deep summary", "rA").withDetails(astra));
        assertEquals(OrbitModelCatalog.ASTRA, load(id).messages.get(1).details.model);
        ConversationStore.selectVariant(context, id, 1, 0);
        assertNull("the original answer still reports its own provenance",
                load(id).messages.get(1).details);
        ConversationStore.selectVariant(context, id, 1, 1);
        assertEquals(OrbitModelCatalog.ASTRA, load(id).messages.get(1).details.model);
    }

    @Test public void laterTurnsBelongToTheVariantTheyFollowed() {
        String id = linearChat("Pick a number", "Seven");
        String parent = ConversationBranches.parentKey(load(id).messages, 1);
        ConversationStore.commitAnswerVariant(context, id, 1, parent, answer("Three", "r3"));
        ConversationStore.appendMessage(context, id, user("Double it"));
        ConversationStore.appendMessage(context, id, answer("Six", "r6"));

        ConversationStore.BranchResult first = ConversationStore.selectVariant(context, id, 1, 0);
        assertEquals("switching to the first answer shows the turns that followed it",
                Arrays.asList("Pick a number", "Seven"), contents(first.messages));
        ConversationStore.BranchResult second = ConversationStore.selectVariant(context, id, 1, 1);
        assertEquals(Arrays.asList("Pick a number", "Three", "Double it", "Six"),
                contents(second.messages));
    }

    @Test public void aVariantForAQuestionThatIsNoLongerThereIsNotWritten() {
        String id = linearChat("Question", "Answer");
        assertFalse(ConversationStore.commitAnswerVariant(context, id, 1, "not-the-parent",
                answer("Stray", "rx")));
        assertFalse(load(id).isBranched());
        assertEquals("Answer", load(id).messages.get(1).content);
    }

    @Test public void variantCountsAreBounded() {
        String id = linearChat("Q", "A0");
        String parent = ConversationBranches.parentKey(load(id).messages, 1);
        for (int i = 1; i < ConversationBranches.MAX_VARIANTS; i++) {
            assertTrue(ConversationStore.commitAnswerVariant(context, id, 1, parent, answer("A" + i, "r" + i)));
        }
        assertFalse(ConversationStore.canAddVariant(context, id, 1));
        assertFalse(ConversationStore.commitAnswerVariant(context, id, 1, parent, answer("Too many", "rz")));
        assertEquals(ConversationBranches.MAX_VARIANTS, load(id).forkAt(1).count());
    }

    // ---- stale saves and clipping -----------------------------------------------------------------

    @Test public void aStaleCopyFromBeforeABranchSwitchCannotOverwriteTheNewPath() {
        String id = linearChat("Q", "Old answer");
        List<AssistantClient.History> staleCopy = new ArrayList<>(load(id).messages);
        String parent = ConversationBranches.parentKey(staleCopy, 1);
        ConversationStore.commitAnswerVariant(context, id, 1, parent, answer("New answer", "rN"));

        staleCopy.add(user("follow up from an old screen"));
        ConversationStore.save(context, id, staleCopy);
        ConversationStore.Conversation chat = load(id);
        assertEquals(Arrays.asList("Q", "New answer"), contents(chat.messages));
        assertEquals("both versions survive", 2, chat.forkAt(1).count());
    }

    @Test public void appendingToABranchedChatKeepsItsForks() {
        String id = linearChat("Q", "A");
        String parent = ConversationBranches.parentKey(load(id).messages, 1);
        ConversationStore.commitAnswerVariant(context, id, 1, parent, answer("B", "rB"));
        List<AssistantClient.History> copy = new ArrayList<>(load(id).messages);
        copy.add(user("Next"));
        ConversationStore.save(context, id, copy);
        assertEquals(3, load(id).messages.size());
        assertEquals(2, load(id).forkAt(1).count());
    }

    @Test public void clippingALongBranchedChatMovesItsForksWithTheMessages() {
        String id = ConversationStore.newId();
        List<AssistantClient.History> history = new ArrayList<>();
        for (int i = 0; i < 38; i++) history.add(i % 2 == 0 ? user("u" + i) : answer("a" + i, "r" + i));
        ConversationStore.save(context, id, history);
        String parent = ConversationBranches.parentKey(load(id).messages, 37);
        ConversationStore.commitAnswerVariant(context, id, 37, parent, answer("a37b", "rb"));

        List<AssistantClient.History> longer = new ArrayList<>(load(id).messages);
        for (int i = 0; i < 4; i++) longer.add(i % 2 == 0 ? user("more" + i) : answer("more" + i, "m" + i));
        ConversationStore.save(context, id, longer);
        ConversationStore.Conversation chat = load(id);
        assertEquals(40, chat.messages.size());
        ConversationBranches.Fork fork = chat.forkAt(35);
        assertNotNull("the fork moved by exactly the two clipped messages", fork);
        assertEquals("a37", fork.variants.get(0).messages.get(0).content);
        assertEquals("a37b", chat.messages.get(35).content);
    }

    @Test public void aCopyReadBeforeABackgroundClipStillAppendsToABranchedChat() {
        String id = ConversationStore.newId();
        List<AssistantClient.History> history = new ArrayList<>();
        for (int i = 0; i < 40; i++) history.add(i % 2 == 0 ? user("u" + i) : answer("a" + i, "r" + i));
        ConversationStore.save(context, id, history);
        String parent = ConversationBranches.parentKey(load(id).messages, 39);
        ConversationStore.commitAnswerVariant(context, id, 39, parent, answer("a39b", "rb"));
        List<AssistantClient.History> screenCopy = new ArrayList<>(load(id).messages);

        // A background append clips the stored path by two from the front.
        ConversationStore.appendMessage(context, id, user("background question"));
        ConversationStore.appendMessage(context, id, answer("background answer", "rbg"));
        assertEquals("u2", load(id).messages.get(0).content);

        // The screen's older copy, plus what the user just typed, still lands.
        screenCopy.add(user("background question"));
        screenCopy.add(answer("background answer", "rbg"));
        screenCopy.add(user("typed on the old screen"));
        ConversationStore.save(context, id, screenCopy);
        ConversationStore.Conversation chat = load(id);
        assertEquals("typed on the old screen", chat.messages.get(chat.messages.size() - 1).content);
        assertNotNull("and the variants are still there", chat.forkAt(36));
    }

    // ---- damaged data ------------------------------------------------------------------------------

    @Test public void corruptedBranchDataCostsOnlyTheHiddenAlternatives() throws Exception {
        String id = linearChat("Q", "A");
        String parent = ConversationBranches.parentKey(load(id).messages, 1);
        ConversationStore.commitAnswerVariant(context, id, 1, parent, answer("B", "rB"));
        JSONArray stored = new JSONArray(ConversationStore.backupJsonForTest(context));
        stored.getJSONObject(0).put("forks", "not an array");
        ConversationStore.restoreBackupJson(context, stored.toString());
        ConversationStore.Conversation chat = load(id);
        assertNotNull("the conversation still opens", chat);
        assertEquals(Arrays.asList("Q", "B"), contents(chat.messages));
        assertFalse(chat.isBranched());

        JSONArray wrongParent = new JSONArray(ConversationStore.backupJsonForTest(context));
        wrongParent.getJSONObject(0).put("forks", new JSONArray().put(new JSONObject()
                .put("at", 1).put("parent", "someone-else").put("selected", 1)
                .put("variants", new JSONArray()
                        .put(new JSONObject().put("messages", new JSONArray()
                                .put(new JSONObject().put("role", "assistant").put("content", "graft"))))
                        .put(new JSONObject().put("active", true)))));
        ConversationStore.restoreBackupJson(context, wrongParent.toString());
        assertFalse("a fork that no longer fits its path is dropped, not grafted",
                load(id).isBranched());
    }

    // ---- titles, selection, deletion ---------------------------------------------------------------

    @Test public void branchingLeavesOneTitleAndAManualTitleAlone() {
        String id = linearChat("Trip to Lisbon", "Here is a plan");
        ConversationStore.rename(context, id, "My Lisbon plan");
        ConversationStore.branchFromUserMessage(context, id, 0, key(id, 0), user("Trip to Porto"));
        ConversationStore.Conversation chat = load(id);
        assertEquals("My Lisbon plan", chat.title);
        assertEquals(ConversationStore.TITLE_MANUAL, chat.titleOwner);
        ConversationStore.setPinned(context, id, true);
        assertTrue("pinning a branched chat keeps its branches", load(id).isBranched());
        ConversationStore.setSelection(context, id, AiSelection.of(Prefs.PROVIDER_CHATGPT,
                OrbitModelCatalog.SOL, AiStrength.HIGH));
        assertTrue("changing its AI keeps its branches", load(id).isBranched());
        assertEquals(OrbitModelCatalog.SOL, load(id).aiSelection.model);
    }

    @Test public void deletingAChatRemovesFilesOnlyItsHiddenBranchesUsed() throws Exception {
        File dir = new File(context.getFilesDir(), "orbit_attachments/history");
        dir.mkdirs();
        File hiddenOnly = new File(dir, "hidden.jpg");
        try (FileOutputStream out = new FileOutputStream(hiddenOnly)) { out.write(new byte[]{1, 2, 3}); }

        String id = ConversationStore.newId();
        AssistantClient.History withPhoto = new AssistantClient.History("user", "Look at this", true,
                hiddenOnly.getAbsolutePath(), "image", "Photo", "");
        ConversationStore.save(context, id, Arrays.asList(withPhoto, answer("Nice photo", "r1")));
        ConversationStore.branchFromUserMessage(context, id, 0, key(id, 0), user("Never mind"));
        assertTrue(hiddenOnly.exists());

        ConversationStore.delete(context, id);
        assertFalse("a hidden branch's photo goes with its chat", hiddenOnly.exists());
    }

    @Test public void actionCardsStayWithTheAnswerThatProducedThem() {
        String id = linearChat("Turn on the flashlight", "Done");
        AssistantReply.Action action = new AssistantReply.Action("flashlight",
                new JSONObject(), false);
        ActionResultStore.record(context, id, 1, action,
                new DeviceActionExecutor.Result(DeviceActionExecutor.STATUS_SUCCESS, "On", true, true), 0, 1);
        AssistantClient.History original = load(id).messages.get(1);
        assertEquals(1, ActionResultStore.forAssistant(context, id, 1, original).size());

        String parent = ConversationBranches.parentKey(load(id).messages, 1);
        ConversationStore.commitAnswerVariant(context, id, 1, parent, answer("Flashlight is on", "rF"));
        AssistantClient.History variant = load(id).messages.get(1);
        assertTrue("another version at the same position does not inherit the card",
                ActionResultStore.forAssistant(context, id, 1, variant).isEmpty());
        assertEquals(1, ActionResultStore.forAssistant(context, id, 1, original).size());
    }

    // ---- the request only ever sees the visible path -----------------------------------------------

    @Test public void aHiddenSiblingBranchNeverReachesTheRequest() throws Exception {
        String id = linearChat("SECRET-ORIGINAL question", "SECRET-ORIGINAL answer");
        ConversationStore.branchFromUserMessage(context, id, 0, key(id, 0), user("Visible question"));
        ConversationStore.Conversation chat = load(id);
        JSONObject body = ChatGptClient.requestBody(context, "Visible question", "", null,
                chat.messages, AiSelections.globalDefault(context), false, "", "", "", false, false,
                OrbitModelCatalog.LUNA);
        assertFalse(body.toString().contains("SECRET-ORIGINAL"));
        assertTrue(body.toString().contains("Visible question"));
    }

    @Test public void aHiddenAnswerVariantNeverReachesTheRequest() throws Exception {
        String id = linearChat("Question", "HIDDEN-VARIANT text");
        String parent = ConversationBranches.parentKey(load(id).messages, 1);
        ConversationStore.commitAnswerVariant(context, id, 1, parent, answer("Shown answer", "rS"));
        List<AssistantClient.History> history = new ArrayList<>(load(id).messages);
        history.add(user("Follow up"));
        JSONObject body = ChatGptClient.requestBody(context, "Follow up", "", null, history,
                AiSelections.globalDefault(context), false, "", "", "", false, false,
                OrbitModelCatalog.LUNA);
        assertFalse(body.toString().contains("HIDDEN-VARIANT"));
        assertTrue(body.toString().contains("Shown answer"));
    }
}
