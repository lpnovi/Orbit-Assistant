package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

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

/**
 * Retry as a durable answer variant, and one-turn "Send with" (0.8.3.0-beta.3).
 *
 * <p>A retry is a request like any other until it finishes: it survives process death with its
 * target frozen, writes only a finished answer, and a retry that fails or is stopped early leaves
 * the answer it was asked beside exactly where it was. A one-turn selection is frozen with its
 * request and never becomes the chat's or the app's selection.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class AnswerVariantRequestTest {
    private Context context;
    private final List<String> cancelledWork = new ArrayList<>();
    private static final AiSelection ASTRA = AiSelection.of(Prefs.PROVIDER_CHATGPT,
            OrbitModelCatalog.ASTRA, AiStrength.HIGH);
    private static final AiSelection TERRA = AiSelection.of(Prefs.PROVIDER_CHATGPT,
            OrbitModelCatalog.GPT_5_6_TERRA, AiStrength.MEDIUM);

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().putBoolean(Prefs.BACKGROUND_NOTIFICATIONS, false).commit();
        ConversationStore.clear(context);
        OrbitRequestManager.resetForTest();
        OrbitRequestManager.setWorkCanceller(cancelledWork::add);
        context.getSharedPreferences("orbit_pending_requests", Context.MODE_PRIVATE).edit().clear().commit();
    }

    private String chat() {
        String id = ConversationStore.newId();
        ConversationStore.save(context, id, Arrays.asList(new AssistantClient.History("user", "Explain tides"),
                new AssistantClient.History("assistant", "Answer A")
                        .withReplyProvenance("first", Collections.emptyList())));
        ConversationStore.setSelection(context, id, TERRA);
        return id;
    }

    private PendingRequestStore.Item variantRequest(String id, AiSelection selection) {
        List<AssistantClient.History> path = ConversationStore.load(context, id).messages;
        String target = PendingRequestStore.variantTarget(1, ConversationBranches.parentKey(path, 1));
        return PendingRequestStore.createVariant(context, id, "Explain tides", "",
                Collections.emptyList(), false, false, selection, false, "", target);
    }

    @Test public void theRetryTargetIsFrozenWithTheRequestAndSurvivesARestart() {
        String id = chat();
        PendingRequestStore.Item item = variantRequest(id, ASTRA);
        PendingRequestStore.Item reread = PendingRequestStore.load(context, item.id);
        assertTrue(reread.isAnswerVariant());
        assertEquals(1, reread.variantAt());
        assertEquals(ASTRA, reread.selection);
        PendingRequestStore.markRunning(context, item.id);
        assertTrue("status changes keep the target", PendingRequestStore.load(context, item.id).isAnswerVariant());
        assertFalse("an ordinary request is not a variant", PendingRequestStore.create(context, id, "Hi", "",
                Collections.emptyList(), false, false, TERRA, false, "").isAnswerVariant());
    }

    @Test public void aFinishedRetryBecomesAVariantRecordingItsModel() {
        String id = chat();
        PendingRequestStore.Item item = variantRequest(id, ASTRA);
        OrbitRequestWorker.completeProviderReply(context, item,
                new AssistantReply("Answer B").withDetails(ResponseDetails.sentWith(ASTRA)),
                WorkerAttempt.of(1, false));
        ConversationStore.Conversation chat = ConversationStore.load(context, id);
        assertEquals("the question is not repeated", 2, chat.messages.size());
        assertEquals("Answer B", chat.messages.get(1).content);
        assertEquals(item.id, chat.messages.get(1).replyRequestId);
        assertEquals(OrbitModelCatalog.ASTRA, chat.messages.get(1).details.model);
        assertEquals("Answer A", chat.forkAt(1).variants.get(0).messages.get(0).content);
        assertEquals("Retry with never changes the chat's own AI", TERRA,
                AiSelections.forConversation(context, id));
    }

    @Test public void aRetryStoppedBeforeAnyTextChangesNothing() {
        String id = chat();
        PendingRequestStore.Item item = variantRequest(id, TERRA);
        assertTrue(OrbitRequestManager.cancel(context, item.id));
        ConversationStore.Conversation chat = ConversationStore.load(context, id);
        assertEquals("Answer A", chat.messages.get(1).content);
        assertFalse(chat.isBranched());
        assertFalse("the original answer is not marked stopped", chat.messages.get(1).isStopped());
    }

    @Test public void aRetryStoppedMidAnswerKeepsItsPartialAsAStoppedVariant() {
        String id = chat();
        PendingRequestStore.Item item = variantRequest(id, TERRA);
        OrbitRequestManager.dispatchDelta(item.id, "Partial B");
        assertTrue(OrbitRequestManager.cancel(context, item.id));
        ConversationStore.Conversation chat = ConversationStore.load(context, id);
        assertEquals("Partial B", chat.messages.get(1).content);
        assertTrue(chat.messages.get(1).isStopped());
        assertEquals("Answer A", chat.forkAt(1).variants.get(0).messages.get(0).content);
        assertFalse(chat.forkAt(1).variants.get(0).messages.get(0).isStopped());
    }

    @Test public void aRetryOfAChangedChatIsNotWritten() {
        String id = chat();
        PendingRequestStore.Item item = variantRequest(id, TERRA);
        ConversationStore.clearMessages(context, id);
        ConversationStore.save(context, id, Collections.singletonList(
                new AssistantClient.History("user", "Something else")));
        OrbitRequestWorker.completeProviderReply(context, item, new AssistantReply("Late"),
                WorkerAttempt.of(1, false));
        ConversationStore.Conversation chat = ConversationStore.load(context, id);
        assertEquals(1, chat.messages.size());
        assertEquals(PendingRequestStore.FAILED, PendingRequestStore.load(context, item.id).status);
    }

    @Test public void retryingAFailedRetryKeepsItAVariant() {
        String id = chat();
        PendingRequestStore.Item item = variantRequest(id, ASTRA);
        PendingRequestStore.markFailed(context, item.id, "network");
        // The durable record is written before the work is handed to WorkManager, so it is what is
        // asserted here whether or not a WorkManager is running in this JVM.
        try {
            OrbitRequestManager.retry(context, item.id, null);
        } catch (RuntimeException workManagerNotInitialized) {
            // Only the hand-off can fail here; the record is already written.
        }
        boolean found = false;
        for (PendingRequestStore.Item active : PendingRequestStore.activeForConversation(context, id)) {
            if (active.isAnswerVariant() && active.selection.equals(ASTRA)) found = true;
        }
        assertTrue(found);
    }

    // ---- send with -------------------------------------------------------------------------------

    @Test public void aOneTurnSelectionIsFrozenWithItsRequestOnly() {
        String id = chat();
        AiSelection global = AiSelections.globalDefault(context);
        PendingRequestStore.Item item = PendingRequestStore.create(context, id, "Deep question", "",
                Collections.emptyList(), false, false, ASTRA, false, "");
        assertEquals("the request goes to exactly what was chosen", ASTRA,
                PendingRequestStore.load(context, item.id).selection);
        assertEquals("the chat keeps its own AI", TERRA, AiSelections.forConversation(context, id));
        assertEquals("the default is untouched", global, AiSelections.globalDefault(context));
    }

    @Test public void anIllegalOneTurnStrengthIsResolvedBeforeTheRequest() {
        AiSelection none = AiSelection.of(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.ASTRA, AiStrength.NONE);
        PendingRequestStore.Item item = PendingRequestStore.create(context, "chat", "Q", "",
                Collections.emptyList(), false, false, none, false, "");
        assertNotNull(item.selection.strength);
        assertTrue(OrbitModelCatalog.spec(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.ASTRA)
                .supports(item.selection.strength));
    }
}
