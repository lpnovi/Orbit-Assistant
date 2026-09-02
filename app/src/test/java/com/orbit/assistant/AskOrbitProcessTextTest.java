package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowApplication;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Ask Orbit from Android's text-selection menu: what is accepted, what is refused, and the two
 * things that must never happen.
 *
 * <p>The first is writing back. {@code ACTION_PROCESS_TEXT} lets a handler return replacement text
 * that the source app puts over the user's own selection, and Orbit implements none of that. A
 * failure here would silently rewrite text inside somebody else's app - a message, a document, a
 * form - which is far worse than any failure to open, so it is asserted from both directions: no
 * result code and no result Intent, whether the selection claimed to be editable or not.
 *
 * <p>The second is sending. A selection opens an unsent composer and stops. Orbit does not prepend
 * a prompt, does not pick a question, and does not call a model: what the user wanted to ask about
 * their own paragraph is theirs to type.
 *
 * <p>This is Orbit's second exported surface reading content from another app, so the refusal cases
 * are about hostile or malformed input costing a rejection rather than a crash.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class AskOrbitProcessTextTest {

    private Context context;
    private final List<Intent> lastStarted = new ArrayList<>();
    private ShadowActivity lastShadow;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        DiagnosticStore.prefs(context).edit().clear().commit();
        ConversationStore.clear(context);
        SharedContentStore.clear();
        drain();
    }

    @After public void tearDown() {
        SharedContentStore.clear();
    }

    // ---- a valid selection ---------------------------------------------------------------------------

    /** The headline case: a selected sentence is staged, exactly as it was written. */
    @Test public void aselectionIsStagedForTheComposer() {
        SharedContentStore.Staged staged =
                ask(processText("The mitochondria is the powerhouse of the cell."));
        assertNotNull(staged);
        assertEquals("The mitochondria is the powerhouse of the cell.", staged.text);
        assertTrue("a selection carries no streams", staged.uris.isEmpty());
        assertEquals(SharedContentStore.SOURCE_PROCESS_TEXT, staged.source);
    }

    /**
     * Orbit does not decide what the user wanted to ask.
     *
     * <p>No "Explain this", no "Summarize this", no "What does this mean" - because the honest
     * answer is that Orbit does not know, and half the time the question is "is this actually
     * true".
     */
    @Test public void nothingIsPrependedToASelection() {
        String selection = "The mitochondria is the powerhouse of the cell.";
        String staged = ask(processText(selection)).text;
        assertEquals(selection, staged);
        String lower = staged.toLowerCase(java.util.Locale.US);
        for (String invented : new String[]{"explain", "summarize", "summarise", "what does",
                "reply to", "translate"}) {
            assertFalse("Orbit must not invent the question: " + invented,
                    lower.contains(invented));
        }
    }

    /** Whitespace, punctuation and line breaks inside a selection are preserved. */
    @Test public void aselectionIsPreservedCharacterForCharacter() {
        String selection = "line one\n  indented two\n\nhttps://example.com/a?b=1&c=2 — em dash";
        assertEquals(selection, ask(processText(selection)).text);
    }

    /** A selection reaches the composer itself, unsent, with nothing else in it. */
    @Test public void thecomposerOpensHoldingTheSelection() {
        runAsk(processText("Is this actually accurate?"));
        Intent chat = startedChatIntent();

        ActivityController<ChatActivity> controller =
                Robolectric.buildActivity(ChatActivity.class, chat).setup();
        assertEquals("Is this actually accurate?", controller.get().composerText());
        assertTrue("nothing is attached by a text selection",
                controller.get().pendingAttachments().isEmpty());
        controller.pause().stop().destroy();
    }

    // ---- read-only, and never writing back ---------------------------------------------------------------

    /** A read-only selection is accepted, and still produces no replacement. */
    @Test public void areadOnlySelectionIsAcceptedAndReplacesNothing() {
        Intent intent = processText("Some text from a web page");
        intent.putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true);

        assertNotNull("read-only text is still readable", ask(intent));
        assertNoWriteBack();
    }

    /** And an editable one is treated identically. Orbit is not offering to edit anything. */
    @Test public void aneditableSelectionAlsoReplacesNothing() {
        Intent intent = processText("Some text in a note the user can edit");
        intent.putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false);

        assertNotNull(ask(intent));
        assertNoWriteBack();
    }

    /** With the flag absent entirely, the answer is the same. There is no writing-back path. */
    @Test public void anabsentReadOnlyFlagChangesNothing() {
        assertNotNull(ask(processText("no flag at all")));
        assertNoWriteBack();
    }

    /** A malformed read-only flag is survived rather than believed. */
    @Test public void amalformedReadOnlyFlagIsSurvived() {
        Intent intent = processText("still fine");
        intent.putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, "not-a-boolean");
        assertEquals("still fine", ask(intent).text);
        assertNoWriteBack();
    }

    // ---- refusal ---------------------------------------------------------------------------------------------

    /** An action Orbit does not handle is refused rather than half-interpreted. */
    @Test public void anunsupportedActionIsRefused() {
        assertNull(ask(new Intent(Intent.ACTION_VIEW).setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, "hello")));
        assertNull(ask(new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, "hello")));
        assertNull(ask(new Intent(Intent.ACTION_MAIN)));
    }

    /**
     * A type Orbit does not read is refused, even named directly.
     *
     * <p>The manifest filter decides which menus Orbit appears in; it decides nothing about an app
     * that names this exported component itself, which is why the check exists in the code too.
     */
    @Test public void anunsupportedMimeTypeIsRefused() {
        Intent intent = new Intent(Intent.ACTION_PROCESS_TEXT)
                .setType("application/vnd.example.binary")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, "hello");
        assertNull(ask(intent));
        assertTrue(ProcessTextToOrbitActivity.isSupportedType("text/plain"));
        assertTrue(ProcessTextToOrbitActivity.isSupportedType("text/html"));
        assertTrue("Android is not obliged to set one",
                ProcessTextToOrbitActivity.isSupportedType(null));
        assertFalse(ProcessTextToOrbitActivity.isSupportedType("image/png"));
        assertFalse(ProcessTextToOrbitActivity.isSupportedType("application/pdf"));
    }

    /** Nothing selected is nothing to ask about. */
    @Test public void anemptySelectionIsRefused() {
        assertNull(ask(processText(null)));
        assertNull(ask(processText("")));
        assertNull(ask(processText("     \n\t  ")));
        assertNull("a missing extra is not a selection",
                ask(new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")));
    }

    /** A malformed extra costs the selection, never the process. */
    @Test public void amalformedExtraIsSurvived() {
        Intent intent = new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain");
        // An app may put anything at all under this key.
        intent.putExtra(Intent.EXTRA_PROCESS_TEXT, new int[]{1, 2, 3});
        assertNull(ask(intent));
        assertNoWriteBack();
    }

    /** A refusal is recorded as a refusal, and nothing is staged behind it. */
    @Test public void arefusalStagesNothing() {
        runAsk(new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"));
        assertTrue("a refused selection opens nothing", lastStarted.isEmpty());
        assertEquals("rejected",
                DiagnosticStore.prefs(context).getString("external_text_outcome", ""));
    }

    // ---- large selections -------------------------------------------------------------------------------------

    /**
     * An enormous selection is bounded, and the user is told it was.
     *
     * <p>Truncating in silence would answer a question about text the user did not select, so the
     * notice travels into the composer with the text.
     */
    @Test public void anenormousSelectionIsTruncatedAndSaysSo() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < SharedContentStore.MAX_TEXT_CHARS + 5000; i++) huge.append('x');

        SharedContentStore.Staged staged = ask(processText(huge.toString()));
        assertNotNull(staged);
        assertTrue(staged.text.length() < huge.length());
        assertTrue("the user must be told", staged.text.contains("truncated"));
        assertFalse("and most of it must not be dropped in silence",
                staged.text.length() < SharedContentStore.MAX_TEXT_CHARS);
    }

    /** One central limit governs every external door, so no route is looser than another. */
    @Test public void oneLimitGovernsEveryExternalDoor() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < SharedContentStore.MAX_TEXT_CHARS + 1000; i++) huge.append('y');

        String viaSelection = ask(processText(huge.toString())).text;
        String bounded = SharedContentStore.bound(huge.toString());
        assertEquals("Share and Ask Orbit must bound text identically", bounded, viaSelection);
    }

    // ---- what happens next ---------------------------------------------------------------------------------------

    /**
     * Ask Orbit opens the conversation with a real Chats stack behind it.
     *
     * <p>Back goes to Chats, not straight back to the app the text was selected in, because Chats
     * is where the user now is.
     */
    @Test public void aselectionOpensTheConversationWithAChatsStackBehindIt() {
        runAsk(processText("hello"));
        assertEquals("one stack, started at once", 2, lastStarted.size());
        assertEquals(MainActivity.class.getName(),
                lastStarted.get(0).getComponent().getClassName());
        assertEquals(ChatActivity.class.getName(),
                lastStarted.get(1).getComponent().getClassName());
        assertTrue("Chats is reused rather than duplicated behind every selection",
                (lastStarted.get(0).getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0
                        && (lastStarted.get(0).getFlags() & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0);
        assertTrue("and the conversation is never the root of its own task",
                (lastStarted.get(1).getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) == 0);
    }

    /** The composer is opened ready to type, because the user still has to write the question. */
    @Test public void thecomposerIsFocused() {
        runAsk(processText("something to ask about"));
        assertTrue(startedChatIntent().getBooleanExtra(ChatActivity.EXTRA_FOCUS_COMPOSER, false));
    }

    /** A selection never lands in an unrelated conversation the user was already writing. */
    @Test public void aselectionOpensItsOwnNewConversation() {
        ConversationStore.save(context, "c-existing", new ArrayList<>(
                java.util.Collections.singletonList(
                        new AssistantClient.History("user", "unrelated work in progress"))));

        runAsk(processText("selected material"));
        String conversationId = startedChatIntent()
                .getStringExtra(ChatActivity.EXTRA_CONVERSATION_ID);
        assertNotNull(conversationId);
        assertFalse("a selection must not be injected into an unrelated chat",
                "c-existing".equals(conversationId));
    }

    /** Two selections in a row are two conversations, never one growing one. */
    @Test public void twoSelectionsOpenTwoConversations() {
        runAsk(processText("first selection"));
        String first = startedChatIntent().getStringExtra(ChatActivity.EXTRA_CONVERSATION_ID);
        runAsk(processText("second selection"));
        String second = startedChatIntent().getStringExtra(ChatActivity.EXTRA_CONVERSATION_ID);
        assertFalse(first.equals(second));
    }

    /** Nothing larger than a token crosses the Binder. */
    @Test public void thecontentTravelsAsAPrivateToken() {
        runAsk(processText("a paragraph of selected text"));
        String token = startedChatIntent().getStringExtra(ChatActivity.EXTRA_SHARE_TOKEN);
        assertNotNull(token);
        assertTrue("the source is named by the token it mints",
                token.startsWith(SharedContentStore.SOURCE_PROCESS_TEXT + "-"));
    }

    /** Nothing is sent. Ask Orbit stages a composer and stops. */
    @Test public void askOrbitNeverSubmitsAnything() {
        runAsk(processText("please do not send this"));
        String conversationId = startedChatIntent()
                .getStringExtra(ChatActivity.EXTRA_CONVERSATION_ID);

        assertNull("no conversation may be written by a selection alone",
                ConversationStore.load(context, conversationId));
        assertFalse("and no request may be started",
                PendingRequestStore.hasActiveForConversation(context, conversationId));
    }

    /** Selected text is data. It is never read as a command, a package, a path or an action. */
    @Test public void aselectionIsNeverInterpreted() {
        for (String hostile : new String[]{
                "turn on the flashlight",
                "com.orbit.assistant.local",
                "/data/data/com.orbit.assistant/shared_prefs/x.xml",
                "DIAL 911",
                "am start -n com.example/.Main"}) {
            runAsk(processText(hostile));
            String conversationId = startedChatIntent()
                    .getStringExtra(ChatActivity.EXTRA_CONVERSATION_ID);
            assertNull("selected text must never execute: " + hostile,
                    ConversationStore.load(context, conversationId));
            assertFalse(PendingRequestStore.hasActiveForConversation(context, conversationId));
            // Exactly two Activities: Chats and the conversation. Never a dialer, never anything else.
            assertEquals(2, lastStarted.size());
            assertEquals(ChatActivity.class.getName(),
                    lastStarted.get(1).getComponent().getClassName());
        }
    }

    /** The staging token is one-shot, so a recreated composer cannot apply the same text twice. */
    @Test public void thestagingTokenIsOneShot() {
        String token = SharedContentStore.stage(SharedContentStore.SOURCE_PROCESS_TEXT,
                "selected", new ArrayList<>(), "text", 0);
        assertNotNull(SharedContentStore.consume(token));
        assertNull("a selection must never be replayed", SharedContentStore.consume(token));
    }

    /** A configuration change on the doorway itself re-stages nothing. */
    @Test public void arecreatedDoorwayStagesNothingAgain() {
        Intent intent = processText("selected once");
        intent.setComponent(new android.content.ComponentName(
                context, ProcessTextToOrbitActivity.class));
        ActivityController<ProcessTextToOrbitActivity> controller =
                Robolectric.buildActivity(ProcessTextToOrbitActivity.class, intent)
                        .create(new Bundle());
        assertNull("a recreated doorway opens nothing",
                Shadows.shadowOf(controller.get()).getNextStartedActivity());
        assertTrue(controller.get().isFinishing());
        controller.destroy();
    }

    // ---- diagnostics -------------------------------------------------------------------------------------------------

    /** Diagnostics records a length and an outcome, and never the selected text. */
    @Test public void diagnosticsRecordsTheLengthAndNotTheText() {
        String selection = "my private recovery code is hunter2-abcdef";
        runAsk(processText(selection));
        for (Object value : DiagnosticStore.prefs(context).getAll().values()) {
            if (!(value instanceof String)) continue;
            String stored = (String) value;
            assertFalse("selected text must never reach Diagnostics", stored.contains("hunter2"));
            assertFalse(stored.contains("recovery code"));
        }
        assertEquals(SharedContentStore.SOURCE_PROCESS_TEXT,
                DiagnosticStore.prefs(context).getString("external_text_source", ""));
        assertEquals("staged", DiagnosticStore.prefs(context).getString("external_text_outcome", ""));
        assertEquals("a length, which describes nothing that was in it",
                selection.length(), DiagnosticStore.prefs(context).getInt("external_text_length", 0));
    }

    /** A read-only selection is recorded as one, so a report can say which it was. */
    @Test public void diagnosticsDistinguishesAReadOnlySelection() {
        Intent intent = processText("from a web page");
        intent.putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true);
        runAsk(intent);
        assertEquals("staged-readonly",
                DiagnosticStore.prefs(context).getString("external_text_outcome", ""));
    }

    // ---- the exported surface -----------------------------------------------------------------------------------------

    /**
     * Ask Orbit adds exactly one exported component, and that is the whole list.
     *
     * <p>Exported means any installed app can reach it, so the set is asserted rather than
     * reviewed: a future component that quietly becomes exported fails here.
     */
    @Test public void askOrbitIsTheOnlyNewExportedComponent() {
        String manifest = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/AndroidManifest.xml");
        List<String> exported = new ArrayList<>();
        for (String block : manifest.split("<(activity|service|receiver|provider)")) {
            if (!block.contains("android:exported=\"true\"")) continue;
            int at = block.indexOf("android:name=\".");
            if (at < 0) continue;
            String name = block.substring(at + "android:name=\".".length());
            exported.add(name.substring(0, name.indexOf('"')));
        }
        java.util.Collections.sort(exported);
        assertEquals(Arrays.asList(
                "AskOrbitTileService",
                "MainActivity",
                "OrbitNotificationListenerService",
                "OrbitRoutineTileService",
                "OrbitSessionService",
                "OrbitVoiceInteractionService",
                "OrbitWidgetConfigureActivity",
                "ProcessTextToOrbitActivity",
                "ShareToOrbitActivity"), exported);
    }

    /** The intent filter is exactly one action and one text type, and nothing wider. */
    @Test public void theintentFilterIsNarrowlyScoped() {
        String manifest = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/AndroidManifest.xml");
        int start = manifest.indexOf("ProcessTextToOrbitActivity");
        String block = manifest.substring(start, manifest.indexOf("</activity>", start));

        assertTrue(block.contains("android.intent.action.PROCESS_TEXT"));
        assertTrue(block.contains("android:mimeType=\"text/plain\""));
        assertFalse("Orbit must not claim it can process every file type",
                block.contains("android:mimeType=\"*/*\""));
        assertFalse(block.contains("android:mimeType=\"image/"));
        assertFalse(block.contains("android:mimeType=\"application/"));
        assertFalse("one action only", block.contains("android.intent.action.SEND"));
        assertTrue("a consumed selection must not become a Recents card",
                block.contains("android:excludeFromRecents=\"true\""));
        assertTrue(block.contains("android:noHistory=\"true\""));
        assertTrue("the doorway must not carry the app-wide back opt-in",
                !block.contains("enableOnBackInvokedCallback"));
    }

    /** The menu label is short and names Orbit. */
    @Test public void themenuLabelIsAskOrbit() {
        assertEquals("Ask Orbit", context.getString(R.string.process_text_label));
        String manifest = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/AndroidManifest.xml");
        int start = manifest.indexOf("ProcessTextToOrbitActivity");
        String block = manifest.substring(start, manifest.indexOf("</activity>", start));
        assertTrue(block.contains("android:label=\"@string/process_text_label\""));
    }

    // ---- helpers --------------------------------------------------------------------------------------------------------

    /** Runs the doorway and returns what it staged, or null when it refused. */
    private SharedContentStore.Staged ask(Intent intent) {
        runAsk(intent);
        if (lastStarted.isEmpty()) return null;
        String token = lastStarted.get(lastStarted.size() - 1)
                .getStringExtra(ChatActivity.EXTRA_SHARE_TOKEN);
        return token == null ? null : SharedContentStore.consume(token);
    }

    private void runAsk(Intent intent) {
        drain();
        lastStarted.clear();
        intent.setComponent(new android.content.ComponentName(
                context, ProcessTextToOrbitActivity.class));
        ActivityController<ProcessTextToOrbitActivity> controller =
                Robolectric.buildActivity(ProcessTextToOrbitActivity.class, intent).create();
        // The doorway starts its stack from the Activity, so the Activity's shadow is what records
        // it; the application shadow would report nothing at all.
        lastShadow = Shadows.shadowOf(controller.get());
        Intent next;
        while ((next = lastShadow.getNextStartedActivity()) != null) lastStarted.add(next);
        // The shadow hands them back newest-first; these assertions are about the stack Orbit
        // built, so they are put back into the order startActivities was given them in.
        java.util.Collections.reverse(lastStarted);
        controller.destroy();
    }

    /**
     * The source app is told nothing was produced, so it replaces nothing.
     *
     * <p>Both halves matter: a result code other than cancelled, or a result Intent carrying
     * {@code EXTRA_PROCESS_TEXT}, would each be enough for the platform to overwrite the user's
     * own selection inside another app.
     */
    private void assertNoWriteBack() {
        assertNotNull("a doorway must have run", lastShadow);
        assertEquals("Orbit must never claim to have produced replacement text",
                Activity.RESULT_CANCELED, lastShadow.getResultCode());
        assertNull("and must never hand back a result Intent", lastShadow.getResultIntent());
    }

    private Intent startedChatIntent() {
        assertFalse("a selection should have opened a conversation", lastStarted.isEmpty());
        Intent chat = lastStarted.get(lastStarted.size() - 1);
        assertEquals(ChatActivity.class.getName(), chat.getComponent().getClassName());
        return chat;
    }

    private static Intent processText(String selection) {
        Intent intent = new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain");
        if (selection != null) intent.putExtra(Intent.EXTRA_PROCESS_TEXT, selection);
        return intent;
    }

    private void drain() {
        while (Shadows.shadowOf((Application) RuntimeEnvironment.getApplication())
                .getNextStartedActivity() != null) { /* drained */ }
    }
}
