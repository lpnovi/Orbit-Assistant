package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Conversation Control in full chat (0.8.3.0-beta.3): what the user actually sees.
 *
 * <p>The first promise is the visual one: an ordinary chat looks as it did in Beta 2 - no version
 * navigator, no kept-context line, no notice. Everything else appears only when it is relevant,
 * once, and in the place it belongs.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class ConversationControlChatTest {
    private Context context;
    private static final AiSelection TERRA = AiSelection.of(Prefs.PROVIDER_CHATGPT,
            OrbitModelCatalog.GPT_5_6_TERRA, AiStrength.MEDIUM);
    private static final AiSelection ASTRA = AiSelection.of(Prefs.PROVIDER_CHATGPT,
            OrbitModelCatalog.ASTRA, AiStrength.HIGH);

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        ConversationStore.clear(context);
        OrbitRequestManager.resetForTest();
        OrbitRequestManager.setWorkCanceller(name -> {});
        TestWorkManager.ensureInitialized(context);
        context.getSharedPreferences("orbit_pending_requests", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void tearDown() {
        OrbitRequestManager.resetForTest();
    }

    private void seed(String id, String... turns) {
        List<AssistantClient.History> history = new ArrayList<>();
        for (int i = 0; i < turns.length; i++) {
            history.add(i % 2 == 0 ? new AssistantClient.History("user", turns[i])
                    : new AssistantClient.History("assistant", turns[i])
                            .withReplyProvenance("r" + i, Collections.emptyList()));
        }
        ConversationStore.save(context, id, history);
        ConversationStore.setSelection(context, id, TERRA);
    }

    private ActivityController<ChatActivity> open(String id) {
        return Robolectric.buildActivity(ChatActivity.class,
                new Intent(context, ChatActivity.class)
                        .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, id)).setup();
    }

    private static List<View> withTagPrefix(View root, String prefix) {
        List<View> out = new ArrayList<>();
        Object tag = root.getTag();
        if (tag instanceof String && ((String) tag).startsWith(prefix)) out.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) out.addAll(withTagPrefix(g.getChildAt(i), prefix));
        }
        return out;
    }

    private static List<TextView> texts(View root) {
        List<TextView> out = new ArrayList<>();
        if (root instanceof TextView) out.add((TextView) root);
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) out.addAll(texts(g.getChildAt(i)));
        }
        return out;
    }

    private static int countDescriptionPrefixes(View root, String prefix) {
        CharSequence description = root.getContentDescription();
        int n = description != null && description.toString().startsWith(prefix) ? 1 : 0;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                n += countDescriptionPrefixes(g.getChildAt(i), prefix);
            }
        }
        return n;
    }

    private static boolean shows(View root, String text) {
        for (TextView t : texts(root)) {
            if (t.getVisibility() == View.VISIBLE && t.getText().toString().contains(text)) return true;
        }
        return false;
    }

    // ---- an ordinary chat looks like Beta 2 ----------------------------------------------------------

    @Test public void anOrdinaryChatHasNoNewChrome() {
        seed("plain", "Hi", "Hello! How can I help?");
        ChatActivity chat = open("plain").get();
        assertTrue("no version navigator", withTagPrefix(chat.messagesForTest(), ChatActivity.BRANCH_NAV_TAG).isEmpty());
        assertEquals("no kept line", View.GONE, chat.keptIndicatorForTest().getVisibility());
        assertEquals("no notice", View.GONE, chat.contextNoticeForTest().getVisibility());
        assertNotNull("the context ring is in the header", chat.contextMeterForTest());
        assertNull("the ring carries no number", textOf(chat.contextMeterForTest()));
    }

    private static String textOf(View v) {
        return v instanceof TextView ? ((TextView) v).getText().toString() : null;
    }

    // ---- answer variants -------------------------------------------------------------------------

    @Test public void answerVariantsShowACompactNavigatorAndSwitchInPlace() {
        seed("v", "Explain tides", "Answer A");
        String parent = ConversationBranches.parentKey(ConversationStore.load(context, "v").messages, 1);
        ConversationStore.commitAnswerVariant(context, "v", 1, parent,
                new AssistantClient.History("assistant", "Answer B")
                        .withReplyProvenance("rB", Collections.emptyList())
                        .withDetails(ResponseDetails.sentWith(ASTRA)));
        ChatActivity chat = open("v").get();
        List<View> navs = withTagPrefix(chat.messagesForTest(), ChatActivity.BRANCH_NAV_TAG);
        assertEquals(1, navs.size());
        assertTrue(shows(navs.get(0), "2 / 2"));
        assertTrue(shows(chat.messagesForTest(), "Answer B"));
        assertEquals("the question is shown once", 1, countText(chat.messagesForTest(), "Explain tides"));

        ViewGroup nav = (ViewGroup) navs.get(0);
        View previous = nav.getChildAt(0);
        View next = nav.getChildAt(2);
        assertFalse("the last version has no next", next.isEnabled());
        assertEquals("Previous answer version", previous.getContentDescription().toString());
        previous.performClick();
        assertTrue(shows(chat.messagesForTest(), "Answer A"));
        assertFalse(shows(chat.messagesForTest(), "Answer B"));
        assertEquals(0, ConversationStore.load(context, "v").forkAt(1).selected);
        View newNav = withTagPrefix(chat.messagesForTest(), ChatActivity.BRANCH_NAV_TAG).get(0);
        assertTrue(shows(newNav, "1 / 2"));
        assertFalse("the first version has no previous", ((ViewGroup) newNav).getChildAt(0).isEnabled());
    }

    private static int countText(View root, String text) {
        int n = 0;
        for (TextView t : texts(root)) if (t.getText().toString().equals(text)) n++;
        return n;
    }

    @Test public void aRetryRunsAsAVariantAndLeavesTheChatsAiAlone() {
        seed("r", "Explain tides", "Answer A");
        ChatActivity chat = open("r").get();
        chat.retryLastResponse(ASTRA);
        PendingRequestStore.Item item = PendingRequestStore.activeForConversation(context, "r").get(0);
        assertTrue(item.isAnswerVariant());
        assertEquals(ASTRA, item.selection);
        assertEquals("nothing is removed while the retry runs", 2,
                ConversationStore.load(context, "r").messages.size());
        assertFalse("the answer being retried steps aside on screen",
                shows(chat.messagesForTest(), "Answer A"));
        assertEquals(TERRA, chat.currentSelectionForTest());
        assertEquals(TERRA, AiSelections.forConversation(context, "r"));
    }

    // ---- edit makes a branch -----------------------------------------------------------------------

    @Test public void editingAnEarlierMessageSendsANewBranch() {
        seed("e", "Plan Lisbon", "Day one: Alfama", "More food", "Try Time Out Market");
        ChatActivity chat = open("e").get();
        chat.beginEdit(0, ConversationStore.load(context, "e").messages.get(0));
        assertTrue(chat.isEditingPreviousMessage());
        assertEquals("Plan Lisbon", chat.composerText());
        assertEquals("nothing is sent by choosing Edit", 4,
                ConversationStore.load(context, "e").messages.size());
        chat.setDraftForTest("Plan Porto");
        chat.submitForTest();

        ConversationStore.Conversation stored = ConversationStore.load(context, "e");
        assertEquals(Collections.singletonList("Plan Porto"), contents(stored.messages));
        assertEquals(4, stored.forkAt(0).variants.get(0).messages.size());
        assertFalse(chat.isEditingPreviousMessage());
        List<View> navs = withTagPrefix(chat.messagesForTest(), ChatActivity.BRANCH_NAV_TAG);
        assertEquals("a navigator under the edited message", 1, navs.size());
        assertTrue(shows(navs.get(0), "2 / 2"));
    }

    private static List<String> contents(List<AssistantClient.History> messages) {
        List<String> out = new ArrayList<>();
        for (AssistantClient.History h : messages) out.add(h.content);
        return out;
    }

    @Test public void theUserMenuOffersEditInFullChatOnly() {
        assertTrue(Arrays.asList(MessageActions.userLabels(true, true)).contains(MessageActions.EDIT_MENU_LABEL));
        assertFalse("the overlay passes no edit action",
                Arrays.asList(MessageActions.userLabels(true)).contains(MessageActions.EDIT_MENU_LABEL));
        assertEquals(MessageActions.userLabels(true, true).length,
                MessageActions.userIcons(true, true).length);
    }

    // ---- send with --------------------------------------------------------------------------------

    @Test public void sendWithUsesItsAiForOneMessageOnly() {
        seed("s", "q", "a");
        ChatActivity chat = open("s").get();
        chat.setDraftForTest("Think hard about this");
        chat.submitWithForTest(ASTRA);
        PendingRequestStore.Item first = PendingRequestStore.activeForConversation(context, "s").get(0);
        assertEquals(ASTRA, first.selection);
        assertEquals("the chat keeps its own AI", TERRA, chat.currentSelectionForTest());
        assertEquals(TERRA, AiSelections.forConversation(context, "s"));

        PendingRequestStore.markDone(context, first.id);
        chat.setDraftForTest("And now an easy one");
        chat.submitForTest();
        PendingRequestStore.Item second = PendingRequestStore.activeForConversation(context, "s").get(0);
        assertEquals("the next ordinary Send uses the chat's AI again", TERRA, second.selection);
    }

    // ---- keep in this chat ----------------------------------------------------------------------

    @Test public void aKeptDocumentIsShownOnceAndIndicatedOnce() {
        seed("k", "Hello", "Hi");
        ChatActivity chat = open("k").get();
        ComposerAttachment doc = new ComposerAttachment("pdf", "Research.pdf",
                "Fermentation findings.", null);
        chat.addComposerAttachmentForTest(doc);
        chat.keepAttachmentForTest(doc.id);
        chat.setDraftForTest("Read this");
        chat.submitForTest();
        assertEquals(1, ConversationStore.kept(context, "k").size());
        assertEquals(View.VISIBLE, chat.keptIndicatorForTest().getVisibility());
        assertTrue(shows(chat.keptIndicatorForTest(), "Research.pdf"));

        // Two more turns later, the document is still drawn exactly once: on its own message.
        ConversationStore.appendMessage(context, "k", new AssistantClient.History("assistant", "Read it."));
        ConversationStore.appendMessage(context, "k", new AssistantClient.History("user", "Key points?"));
        ConversationStore.appendMessage(context, "k", new AssistantClient.History("assistant", "Three."));
        chat.renderForTest();
        // The compact card and its filename expose the same accessible label; neither later turn
        // receives another copy. The suffix now includes the quiet type and extraction status.
        assertEquals("one card and its accessible filename, never repeated under later messages", 2,
                countDescriptionPrefixes(chat.messagesForTest(), "Attached: Research.pdf"));
        assertEquals(View.VISIBLE, chat.keptIndicatorForTest().getVisibility());
    }

    @Test public void aOneTurnAttachmentIsNotKept() {
        seed("o", "Hello", "Hi");
        ChatActivity chat = open("o").get();
        chat.addComposerAttachmentForTest(new ComposerAttachment("pdf", "Once.pdf", "Text.", null));
        chat.setDraftForTest("Read this");
        chat.submitForTest();
        assertTrue(ConversationStore.kept(context, "o").isEmpty());
        assertEquals(View.GONE, chat.keptIndicatorForTest().getVisibility());
    }

    @Test public void theScreenIsNeverKept() {
        seed("sc", "Hello", "Hi");
        ChatActivity chat = open("sc").get();
        ComposerAttachment screen = new ComposerAttachment("screen", "Screen", "What is on screen", null);
        chat.addComposerAttachmentForTest(screen);
        chat.keepAttachmentForTest(screen.id);
        chat.setDraftForTest("What is this?");
        chat.submitForTest();
        assertTrue("even a forced mark cannot keep the live screen",
                ConversationStore.kept(context, "sc").isEmpty());
    }

    // ---- the context window ----------------------------------------------------------------------

    @Test public void theMeterReflectsTheChatAndRecedesAtLowUsage() {
        seed("m", "Hi", "Hello");
        ChatActivity chat = open("m").get();
        chat.measureContextForTest();
        ContextEstimate estimate = chat.latestEstimateForTest();
        assertNotNull(estimate);
        assertEquals(ContextEstimate.Level.NORMAL, estimate.level());
        assertTrue(chat.contextMeterForTest().getAlpha() < 1f);
        assertTrue(chat.contextMeterForTest().getContentDescription().toString().contains("percent"));
    }

    @Test public void theNearFullNoticeAppearsOnlyNearTheTopAndStaysDismissed() {
        seed("n", "Hi", "Hello");
        ChatActivity chat = open("n").get();
        chat.applyEstimateForTest(new ContextEstimate(85_000, 100_000, Collections.emptyMap(), 0, false, ""));
        assertEquals("not at 85%", View.GONE, chat.contextNoticeForTest().getVisibility());
        chat.applyEstimateForTest(new ContextEstimate(91_000, 100_000, Collections.emptyMap(), 0, false, ""));
        assertEquals(View.VISIBLE, chat.contextNoticeForTest().getVisibility());
        assertTrue(shows(chat.contextNoticeForTest(), "91% full"));

        ViewGroup notice = (ViewGroup) chat.contextNoticeForTest();
        notice.getChildAt(notice.getChildCount() - 1).performClick();
        assertEquals(View.GONE, notice.getVisibility());
        chat.applyEstimateForTest(new ContextEstimate(92_000, 100_000, Collections.emptyMap(), 0, false, ""));
        assertEquals("a dismissal holds at the same level", View.GONE, notice.getVisibility());
        chat.applyEstimateForTest(new ContextEstimate(97_000, 100_000, Collections.emptyMap(), 0, false, ""));
        assertEquals("and returns only when it gets fuller", View.VISIBLE, notice.getVisibility());
    }

    @Test public void anUnknownWindowNeverShowsTheNotice() {
        seed("u", "Hi", "Hello");
        ChatActivity chat = open("u").get();
        chat.applyEstimateForTest(new ContextEstimate(900_000, 0, Collections.emptyMap(), 0, true, "Orbit Local"));
        assertEquals(View.GONE, chat.contextNoticeForTest().getVisibility());
        assertEquals(ContextEstimate.Level.UNKNOWN, chat.contextMeterForTest().level());
    }

    // ---- small fixes ------------------------------------------------------------------------------

    @Test public void theRenameFieldSitsInsideTheDialogsContentMargins() {
        ChatActivity chat = open("rn").get();
        AlertDialog dialog = OrbitRenameDialog.show(chat, "Old name", name -> {});
        ShadowLooper.idleMainLooper();
        EditText field = findEditText(dialog.getWindow().getDecorView());
        assertNotNull(field);
        FrameLayout inset = (FrameLayout) field.getParent();
        int side = (int) (OrbitRenameDialog.CONTENT_INSET_DP
                * chat.getResources().getDisplayMetrics().density + 0.5f);
        assertTrue("20 to 24dp of inset on both sides",
                inset.getPaddingLeft() >= side - 1 && inset.getPaddingRight() >= side - 1);
        assertEquals("Old name", field.getText().toString());
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString().contains("Save"));
        dialog.dismiss();
        assertNotNull(ShadowDialog.getLatestDialog());
    }

    private static EditText findEditText(View root) {
        if (root instanceof EditText) return (EditText) root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                EditText found = findEditText(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test public void aRedundantSourcePillIsNotDrawnAndAUniqueOneSitsAboveTheActions() {
        String cited = "Ferns reproduce by spores ([commons.wikimedia.org](https://commons.wikimedia.org/wiki/Fern?utm_source=openai)).\n\nSource: https://commons.wikimedia.org/wiki/Fern";
        seed("src", "Tell me about ferns", cited);
        ChatActivity chat = open("src").get();
        assertFalse("already cited inline, so no separate pill",
                shows(chat.messagesForTest(), "Open source"));

        String unique = "Ferns are old.\n\nSource: https://example.org/plants/ferns";
        seed("src2", "Tell me about ferns", unique);
        ChatActivity second = open("src2").get();
        ViewGroup rows = second.messagesForTest();
        int pill = -1;
        int strip = -1;
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            if (row instanceof Button && ((Button) row).getText().toString().contains("Open source")) pill = i;
            if (MessageActions.STRIP_TAG.equals(row.getTag())) strip = i;
        }
        assertTrue("a unique source keeps its control", pill >= 0);
        assertTrue("and it sits with the answer, before the actions", pill < strip);
    }

    @Test public void sourcePagesCompareAsPagesNotDomains() {
        assertEquals(RichAnswerSourcePresentation.pageKey("https://www.example.org/a/?utm_source=openai#x"),
                RichAnswerSourcePresentation.pageKey("http://example.org/a"));
        assertFalse(RichAnswerSourcePresentation.pageKey("https://example.org/a")
                .equals(RichAnswerSourcePresentation.pageKey("https://example.org/b")));
        assertTrue(RichAnswerSourcePresentation.needsStandaloneSource("https://example.org/b",
                "See [a](https://example.org/a)", Collections.emptyList()));
    }

    @Test public void aLongAttachmentNameStaysOnOneQuietLine() {
        String id = "att";
        ConversationStore.save(context, id, Arrays.asList(
                new AssistantClient.History("user", "What is this?", true, Collections.emptyList(),
                        "file_text", "Screenshot_20261002_002836_Orbit Assistant extremely long name.txt",
                        "text", "", "", "", ""),
                new AssistantClient.History("assistant", "A screenshot.")));
        ChatActivity chat = open(id).get();
        TextView label = null;
        for (TextView t : texts(chat.messagesForTest())) {
            if (t.getText().toString().startsWith("Screenshot_2026")) label = t;
        }
        assertNotNull(label);
        assertEquals(1, label.getMaxLines());
        assertEquals(android.text.TextUtils.TruncateAt.MIDDLE, label.getEllipsize());
        assertTrue(label.getContentDescription().toString().contains("extremely long name.txt"));
    }
}
