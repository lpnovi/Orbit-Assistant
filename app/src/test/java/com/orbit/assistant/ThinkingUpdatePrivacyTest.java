package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A thinking update is ephemeral, and that is a promise about where its text may end up.
 *
 * <p>Reasoning summaries are shown for a few seconds and then are gone. They are not part of the
 * conversation, they are never sent back to a model as an assistant turn, and they are not
 * something Orbit's own diagnostics may keep — a status line is the one piece of a request that
 * deliberately does not survive it. These tests push a very distinctive phrase through the real
 * progress path and then go looking for it everywhere it must not be.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class ThinkingUpdatePrivacyTest {
    /** Distinctive enough that finding it anywhere is unambiguous. */
    private static final String SUMMARY = "zzsummaryzz weighing the alternatives";

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        ConversationStore.clear(context);
        OrbitRequestManager.resetForTest();
        OrbitRequestManager.setWorkCanceller(name -> {});
    }

    @After public void tearDown() {
        OrbitRequestManager.resetForTest();
    }

    private PendingRequestStore.Item runRequestThatShowsAStatus(String conversationId) {
        List<AssistantClient.History> history = new ArrayList<>();
        history.add(new AssistantClient.History("user", "Compare these two architectures."));
        ConversationStore.save(context, conversationId, history);
        PendingRequestStore.Item item = PendingRequestStore.create(context, conversationId,
                "Compare these two architectures.", "", "", false, false,
                Prefs.MODE_DEEP, false, "");
        OrbitRequestManager.dispatchThinking(item.id, ThinkingUpdate.providerSummary(SUMMARY));
        ReasoningSummarySupport.recordDisplayed(context, ThinkingUpdate.providerSummary(SUMMARY));
        return item;
    }

    // ---- the conversation never learns about it --------------------------------------------------

    @Test public void noSummaryTextIsEverAppendedToTheConversation() {
        String conversationId = "c-privacy";
        PendingRequestStore.Item item = runRequestThatShowsAStatus(conversationId);
        OrbitRequestManager.dispatchSuccess(item.id,
                new AssistantReply("Both designs trade throughput for latency.", new ArrayList<>()));

        ConversationStore.Conversation chat = ConversationStore.load(context, conversationId);
        for (AssistantClient.History h : chat.messages) {
            assertFalse("a reasoning summary reached the conversation: " + h.content,
                    h.content != null && h.content.contains("zzsummaryzz"));
        }
    }

    /** Including on the stop path, which is the one place partial text is deliberately kept. */
    @Test public void stoppingARequestPersistsThePartialAnswerAndNeverTheStatus() {
        String conversationId = "c-privacy-stop";
        PendingRequestStore.Item item = runRequestThatShowsAStatus(conversationId);
        OrbitRequestManager.dispatchDelta(item.id, "Both designs trade");
        assertTrue(OrbitRequestManager.cancel(context, item.id));

        ConversationStore.Conversation chat = ConversationStore.load(context, conversationId);
        boolean keptPartial = false;
        for (AssistantClient.History h : chat.messages) {
            assertFalse("a reasoning summary was persisted by Stop",
                    h.content != null && h.content.contains("zzsummaryzz"));
            if (h.content != null && h.content.contains("Both designs trade")) keptPartial = true;
        }
        assertTrue("the partial answer is still kept, exactly as before", keptPartial);
    }

    /** History is what gets sent back to a model, so this is also "never re-sent as a turn". */
    @Test public void aSummaryNeverBecomesAnAssistantHistoryEntry() {
        String conversationId = "c-privacy-history";
        runRequestThatShowsAStatus(conversationId);
        ConversationStore.Conversation chat = ConversationStore.load(context, conversationId);
        assertEquals("no assistant turn may have been created by a status update",
                1, chat.messages.size());
        assertEquals("user", chat.messages.get(0).role);
    }

    // ---- neither do the stores -------------------------------------------------------------------

    @Test public void noSummaryTextReachesTheDiagnosticsStore() {
        runRequestThatShowsAStatus("c-privacy-diag");
        assertNoSummaryIn(DiagnosticStore.prefs(context), "diagnostics store");
    }

    @Test public void noSummaryTextReachesTheRequestTraceOrTheComposerTrace() {
        PendingRequestStore.Item item = runRequestThatShowsAStatus("c-privacy-trace");
        RequestTrace.lifecycle(item.id, "running");
        assertFalse("a reasoning summary reached the request trace",
                ComposerTrace.report().contains("zzsummaryzz"));
    }

    @Test public void theThinkingUpdateStoreItselfKeepsCountsAndNeverText() {
        runRequestThatShowsAStatus("c-privacy-store");
        assertTrue("the count must be recorded", ReasoningSummarySupport.updatesReceived(context) > 0);
        assertEquals("provider-summary", ReasoningSummarySupport.lastSource(context));
        assertNoSummaryIn(
                context.getSharedPreferences("orbit_reasoning_summary", Context.MODE_PRIVATE),
                "thinking update store");
    }

    @Test public void noSummaryTextReachesTheDurablePendingRequestRecord() {
        PendingRequestStore.Item item = runRequestThatShowsAStatus("c-privacy-pending");
        PendingRequestStore.Item stored = PendingRequestStore.load(context, item.id);
        assertFalse(stored.prompt.contains("zzsummaryzz"));
        assertFalse(stored.error != null && stored.error.contains("zzsummaryzz"));
    }

    private void assertNoSummaryIn(SharedPreferences prefs, String what) {
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String && ((String) value).contains("zzsummaryzz")) {
                fail("a reasoning summary was written to the " + what + " under " + entry.getKey());
            }
        }
    }

    // ---- and the setting is not backed up as content ----------------------------------------------

    /** The preference travels in a backup; nothing a model said ever does. */
    @Test public void onlyThePreferenceIsBackedUpNeverAnyUpdate() throws Exception {
        Prefs.get(context).edit().putBoolean(Prefs.THINKING_UPDATES, true).commit();
        runRequestThatShowsAStatus("c-privacy-backup");
        String snapshot = Prefs.backupSnapshot(context).toString();
        assertTrue("the user's choice should survive a restore",
                snapshot.contains(Prefs.THINKING_UPDATES));
        assertFalse("no update text may be in a backup", snapshot.contains("zzsummaryzz"));
    }

    // ---- the source itself says so ----------------------------------------------------------------

    private static Path repositoryRoot() {
        Path start = Paths.get("").toAbsolutePath();
        for (Path directory = start; directory != null; directory = directory.getParent()) {
            if (Files.isRegularFile(directory.resolve("settings.gradle"))) return directory;
        }
        throw new AssertionError("repository root was not found above " + start);
    }

    private static String read(String relativePath) {
        try {
            return new String(Files.readAllBytes(repositoryRoot().resolve(relativePath)),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read " + relativePath, e);
        }
    }

    /**
     * Orbit reads the reasoning <em>summary</em> events and nothing else.
     *
     * <p>The same stream can carry hidden chain-of-thought under names one character away from the
     * ones Orbit wants. This asserts against the source that the raw families are absent, because
     * "we only read summaries" is a claim that is very easy to break by accident and impossible to
     * notice on a device.
     */
    @Test public void theStreamReaderConsumesSummaryEventsAndNeverRawReasoning() {
        String client = read("app/src/main/java/com/orbit/assistant/ChatGptClient.java");
        assertTrue("the summary text events must be the ones Orbit reads",
                client.contains("response.reasoning_summary_text."));
        // Read-shaped tokens only: the class documents by name what it refuses to consume, and
        // saying so must not be mistaken for doing so.
        for (String forbidden : new String[]{
                "\"response.reasoning_text", "\"reasoning_text\"",
                "optString(\"encrypted_content\")", "opt(\"encrypted_content\")",
                "getString(\"encrypted_content\")", "optString(\"reasoning\")"}) {
            assertFalse("Orbit must never read " + forbidden + " from the response stream",
                    client.contains(forbidden));
        }
    }

    /** No surface may render an update through Orbit's markdown or rich-response renderers. */
    @Test public void aStatusLineIsPlainTextAndNeverRenderedAsContent() {
        String status = read("app/src/main/java/com/orbit/assistant/ThinkingStatusView.java");
        assertFalse("a status line must not be run through the markdown renderer",
                status.contains("OrbitMarkdown"));
        assertFalse("a status line must not be run through the rich response renderer",
                status.contains("OrbitRichResponseRenderer"));
    }

    /** Nor may one be spoken: this is a visual feature, and voice replies are unchanged. */
    @Test public void updatesAreNeverSpoken() {
        for (String file : new String[]{
                "app/src/main/java/com/orbit/assistant/OrbitSession.java",
                "app/src/main/java/com/orbit/assistant/ChatActivity.java"}) {
            String source = read(file);
            int at = source.indexOf("onThinking(String requestId");
            assertTrue(file + " must handle onThinking", at >= 0);
            String handler = source.substring(at, Math.min(source.length(), at + 900));
            assertFalse(file + " must not speak a thinking update", handler.contains("speak("));
        }
    }
}
