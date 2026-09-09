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
 * A picture belongs to the answer that produced it, and to no other.
 *
 * <p>Rich Answers arrives after the answer does. The reply is written, streamed, spoken and drawn;
 * a second or two later a picture resolves and has to find the message it belongs to again. That
 * gap is where the interesting failures live - a superseded request attaching to whatever is on
 * screen now, a lifecycle save rubbing out a picture that arrived a moment ago, an older
 * conversation refusing to load because it has no picture field at all.
 *
 * <p>Everything here is storage behaviour, which is why it is testable without a screen.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class RichAnswerPersistenceTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        context.getSharedPreferences("orbit_conversations", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    private static RichAnswerImage picture(String suffix) {
        return RichAnswerImage.webSource("https://cdn.example.org/" + suffix + ".jpg",
                "https://example.org/birds/" + suffix, "A " + suffix, "a " + suffix, 0);
    }

    private static AssistantClient.History user(String text) {
        return new AssistantClient.History("user", text);
    }

    private static AssistantClient.History assistant(String text) {
        return new AssistantClient.History("assistant", text);
    }

    // ---- round trip ------------------------------------------------------------------------------

    @Test public void picturesSurviveBeingSavedAndReloaded() {
        String id = ConversationStore.newId();
        RichAnswerImage image = picture("robin");
        ConversationStore.save(context, id, Arrays.asList(
                user("what does a robin look like"),
                assistant("A small brown bird.").withRichImages(
                        Collections.singletonList(image))));

        ConversationStore.Conversation stored = ConversationStore.load(context, id);
        assertNotNull(stored);
        AssistantClient.History answer = stored.messages.get(1);
        assertTrue(answer.hasRichImages());
        assertEquals(1, answer.richImages.size());
        assertEquals(image.imageUrl, answer.richImages.get(0).imageUrl);
        assertEquals(image.sourceUrl, answer.richImages.get(0).sourceUrl);
        assertEquals("A robin", answer.richImages.get(0).caption);
    }

    /**
     * A conversation written before Rich Answers existed loads exactly as it did.
     *
     * <p>Asserted against a hand-written record rather than a round trip, because the question is
     * about a document Orbit did not write: the key is simply absent, and absent has to mean "no
     * pictures" without anything being migrated.
     */
    @Test public void olderConversationsWithNoPictureFieldLoadUnchanged() throws Exception {
        String raw = new org.json.JSONArray().put(new org.json.JSONObject()
                .put("id", "legacy-chat")
                .put("title", "Older chat")
                .put("updatedAt", System.currentTimeMillis())
                .put("messages", new org.json.JSONArray()
                        .put(new org.json.JSONObject().put("role", "user").put("content", "hello"))
                        .put(new org.json.JSONObject().put("role", "assistant")
                                .put("content", "Hello. How can I help?")))).toString();
        context.getSharedPreferences("orbit_conversations", Context.MODE_PRIVATE)
                .edit().putString("items_v1", raw).commit();

        ConversationStore.Conversation stored = ConversationStore.load(context, "legacy-chat");
        assertNotNull("an older chat must still load", stored);
        assertEquals(2, stored.messages.size());
        assertEquals("Hello. How can I help?", stored.messages.get(1).content);
        assertFalse(stored.messages.get(1).hasRichImages());
    }

    /** A damaged picture record costs the picture, never the conversation. */
    @Test public void malformedPictureMetadataIsIgnoredSafely() throws Exception {
        String raw = new org.json.JSONArray().put(new org.json.JSONObject()
                .put("id", "damaged-chat")
                .put("title", "Damaged")
                .put("updatedAt", System.currentTimeMillis())
                .put("messages", new org.json.JSONArray()
                        .put(new org.json.JSONObject().put("role", "user").put("content", "hi"))
                        .put(new org.json.JSONObject().put("role", "assistant")
                                .put("content", "The answer text is intact.")
                                .put("richImages", new org.json.JSONArray()
                                        .put(new org.json.JSONObject())
                                        .put(new org.json.JSONObject()
                                                .put("imageUrl", "javascript:alert(1)")
                                                .put("sourceUrl", "https://example.org/p"))
                                        .put("not an object")
                                        .put(new org.json.JSONObject()
                                                .put("imageUrl", "https://cdn.example.org/x.jpg")))))
                ).toString();
        context.getSharedPreferences("orbit_conversations", Context.MODE_PRIVATE)
                .edit().putString("items_v1", raw).commit();

        ConversationStore.Conversation stored = ConversationStore.load(context, "damaged-chat");
        assertNotNull("a damaged picture must not break the chat", stored);
        assertEquals("The answer text is intact.", stored.messages.get(1).content);
        assertFalse("and nothing unusable is drawn", stored.messages.get(1).hasRichImages());
    }

    /** A message may never hold more pictures than Orbit will draw. */
    @Test public void aMessageIsBoundedToTheMaximumNumberOfPictures() {
        List<RichAnswerImage> many = new ArrayList<>();
        for (int i = 0; i < 8; i++) many.add(picture("bird" + i));
        AssistantClient.History answer = assistant("An answer.").withRichImages(many);
        assertEquals(RichAnswerImage.MAX_PER_MESSAGE, answer.richImages.size());
    }

    // ---- attaching after the fact -----------------------------------------------------------------

    @Test public void aPictureAttachesToTheAnswerThatProducedIt() {
        String id = ConversationStore.newId();
        ConversationStore.save(context, id, Arrays.asList(
                user("what does a robin look like"),
                assistant("A small brown bird with a red breast.")));

        assertTrue(ConversationStore.attachRichImages(context, id,
                "A small brown bird with a red breast.",
                Collections.singletonList(picture("robin"))));

        ConversationStore.Conversation stored = ConversationStore.load(context, id);
        assertTrue(stored.messages.get(1).hasRichImages());
        assertFalse("the question is untouched", stored.messages.get(0).hasRichImages());
    }

    /**
     * A late picture from a superseded request cannot decorate a different answer.
     *
     * <p>The core request-integrity property of this feature. Request A is superseded by request B;
     * A's image lookup finishes afterwards and tries to attach. It is matched by the exact answer
     * text it started from, so B's answer - a different answer - does not match and nothing is
     * written.
     */
    @Test public void aLatePictureCannotAttachToADifferentAnswer() {
        String id = ConversationStore.newId();
        ConversationStore.save(context, id, Arrays.asList(
                user("what does a robin look like"),
                assistant("A completely different answer arrived instead.")));

        assertFalse("the answer this lookup started from is no longer there",
                ConversationStore.attachRichImages(context, id,
                        "A small brown bird with a red breast.",
                        Collections.singletonList(picture("robin"))));

        ConversationStore.Conversation stored = ConversationStore.load(context, id);
        assertFalse(stored.messages.get(1).hasRichImages());
    }

    /**
     * A two-picture result from a superseded request cannot land on the request that replaced it.
     *
     * <p>Beta 6 makes the discovery window longer: a plural request may read a second page, or
     * follow a discovery hint, after the first picture is already in hand. A user who sends
     * something else during that window must not receive the first request's photographs, and
     * neither picture may leak across on its own.
     */
    @Test public void aLateTwoPictureResultCannotAttachToTheRequestThatSupersededIt() {
        String id = ConversationStore.newId();
        String first = "req-a";
        String second = "req-b";
        AssistantClient.History answerA =
                assistant("A mallard drake has a green head.").withReplyProvenance(first,
                        Collections.<String>emptyList());
        AssistantClient.History answerB =
                assistant("A bluebird is smaller than a robin.").withReplyProvenance(second,
                        Collections.<String>emptyList());
        ConversationStore.save(context, id, Arrays.asList(
                user("show me pics of a mallard duck"), answerA,
                user("and a bluebird"), answerB));

        List<RichAnswerImage> both = Arrays.asList(picture("mallard"), picture("mallard-hen"));
        assertFalse("the superseded request owns neither answer that is on screen now",
                ConversationStore.attachRichImages(context, id, first,
                        "A bluebird is smaller than a robin.", both));
        assertFalse("and cannot borrow the newer request's ownership either",
                ConversationStore.attachRichImages(context, id, second,
                        "A mallard drake has a green head.", both));

        ConversationStore.Conversation stored = ConversationStore.load(context, id);
        assertFalse(stored.messages.get(1).hasRichImages());
        assertFalse(stored.messages.get(3).hasRichImages());

        assertTrue("while its own answer still accepts both of its pictures",
                ConversationStore.attachRichImages(context, id, first,
                        "A mallard drake has a green head.", both));
        ConversationStore.Conversation after = ConversationStore.load(context, id);
        assertEquals(2, after.messages.get(1).richImages.size());
        assertFalse("and nothing reached the newer answer",
                after.messages.get(3).hasRichImages());
    }

    /** An answer that ends up with one picture is a complete answer, not a failed one. */
    @Test public void aPluralRequestThatFoundOnePictureStillAttachesIt() {
        String id = ConversationStore.newId();
        ConversationStore.save(context, id, Arrays.asList(
                user("show me pics of a mallard duck"),
                assistant("Here are some useful mallard references.")));

        assertTrue(ConversationStore.attachRichImages(context, id,
                "Here are some useful mallard references.",
                Collections.singletonList(picture("mallard"))));
        assertEquals(1, ConversationStore.load(context, id).messages.get(1).richImages.size());
    }

    /** And a second attach onto an answer that already has pictures is refused. */
    @Test public void anAnswerIsOnlyDecoratedOnce() {
        String id = ConversationStore.newId();
        ConversationStore.save(context, id, Arrays.asList(
                user("what does a robin look like"), assistant("A small brown bird.")));
        assertTrue(ConversationStore.attachRichImages(context, id, "A small brown bird.",
                Collections.singletonList(picture("robin"))));
        assertFalse(ConversationStore.attachRichImages(context, id, "A small brown bird.",
                Collections.singletonList(picture("bluebird"))));

        ConversationStore.Conversation stored = ConversationStore.load(context, id);
        assertEquals(1, stored.messages.get(1).richImages.size());
        assertEquals("A robin", stored.messages.get(1).richImages.get(0).caption);
    }

    /** Attaching to a chat, an answer or a picture that does not exist changes nothing. */
    @Test public void attachingNothingUsableIsRefusedQuietly() {
        String id = ConversationStore.newId();
        ConversationStore.save(context, id, Arrays.asList(user("hi"), assistant("Hello.")));
        assertFalse(ConversationStore.attachRichImages(context, "no-such-chat", "Hello.",
                Collections.singletonList(picture("robin"))));
        assertFalse(ConversationStore.attachRichImages(context, id, "",
                Collections.singletonList(picture("robin"))));
        assertFalse(ConversationStore.attachRichImages(context, id, "Hello.", null));
        assertFalse(ConversationStore.attachRichImages(context, id, "Hello.",
                Collections.<RichAnswerImage>emptyList()));
        assertFalse("an unusable picture is not an attachment",
                ConversationStore.attachRichImages(context, id, "Hello.",
                        Collections.singletonList(RichAnswerImage.webSource(
                                "https://cdn.example.org/x.jpg", "", "", "", 0))));
    }

    /** Attaching is not activity, so it must not reorder the chat list. */
    @Test public void attachingDoesNotMoveTheChatToTheTopOfTheList() {
        String id = ConversationStore.newId();
        ConversationStore.save(context, id, Arrays.asList(user("hi"), assistant("Hello.")));
        long before = ConversationStore.load(context, id).updatedAt;
        ConversationStore.attachRichImages(context, id, "Hello.",
                Collections.singletonList(picture("robin")));
        assertEquals(before, ConversationStore.load(context, id).updatedAt);
    }

    /**
     * A stale in-memory copy saved back must not rub a picture out.
     *
     * <p>The exact race the stopped mark already had. A screen holds the conversation from before
     * the picture arrived; the picture is written straight to disk; the screen's next lifecycle
     * save would otherwise overwrite it with a version that has none.
     */
    @Test public void aStaleSaveCannotEraseAPictureThatArrivedAfterIt() {
        String id = ConversationStore.newId();
        List<AssistantClient.History> onScreen = Arrays.asList(
                user("what does a robin look like"), assistant("A small brown bird."));
        ConversationStore.save(context, id, onScreen);
        assertTrue(ConversationStore.attachRichImages(context, id, "A small brown bird.",
                Collections.singletonList(picture("robin"))));

        // The screen, still holding its pre-picture copy, saves on pause.
        ConversationStore.save(context, id, onScreen);

        ConversationStore.Conversation stored = ConversationStore.load(context, id);
        assertTrue("the picture must survive the stale save",
                stored.messages.get(1).hasRichImages());
        assertEquals("A robin", stored.messages.get(1).richImages.get(0).caption);
    }

    /** And a genuinely different conversation never inherits another one's pictures. */
    @Test public void picturesAreNotCarriedOntoAnUnrelatedConversation() {
        String first = ConversationStore.newId();
        ConversationStore.save(context, first, Arrays.asList(
                user("what does a robin look like"), assistant("A small brown bird.")));
        ConversationStore.attachRichImages(context, first, "A small brown bird.",
                Collections.singletonList(picture("robin")));

        String second = ConversationStore.newId();
        ConversationStore.save(context, second, Arrays.asList(
                user("what is 2 + 2"), assistant("Four.")));
        assertFalse(ConversationStore.load(context, second).messages.get(1).hasRichImages());
    }

    // ---- the answer stays the answer ------------------------------------------------------------

    /**
     * A picture is never written into the text of a reply.
     *
     * <p>What the user copies, what Orbit reads aloud, and what a model is given back as history
     * all have to be exactly what was said. A URL smuggled into the prose would appear in all three.
     */
    @Test public void aPictureIsNeverEncodedIntoTheAnswerText() {
        String id = ConversationStore.newId();
        ConversationStore.save(context, id, Arrays.asList(
                user("what does a robin look like"), assistant("A small brown bird.")));
        ConversationStore.attachRichImages(context, id, "A small brown bird.",
                Collections.singletonList(picture("robin")));
        String text = ConversationStore.load(context, id).messages.get(1).content;
        assertEquals("A small brown bird.", text);
        assertFalse(text.contains("cdn.example.org"));
        assertFalse(text.contains("!["));
    }

    /** Attaching a picture leaves every other field of the message alone. */
    @Test public void attachingLeavesTheRestOfTheMessageUntouched() {
        AssistantClient.History original = new AssistantClient.History("assistant", "An answer.",
                false, Collections.singletonList("/data/x.jpg"), "image", "Photo", "context",
                "memory usage", "suggested", "category", "req-9", Collections.emptyList());
        AssistantClient.History decorated =
                original.withRichImages(Collections.singletonList(picture("robin")));
        assertEquals(original.content, decorated.content);
        assertEquals(original.attachmentPaths, decorated.attachmentPaths);
        assertEquals(original.attachmentKind, decorated.attachmentKind);
        assertEquals(original.attachmentLabel, decorated.attachmentLabel);
        assertEquals(original.attachmentText, decorated.attachmentText);
        assertEquals(original.memoryUsage, decorated.memoryUsage);
        assertEquals(original.memorySuggestionText, decorated.memorySuggestionText);
        assertEquals(original.stoppedRequestId, decorated.stoppedRequestId);
        assertTrue(decorated.hasRichImages());
    }

    // ---- ownership and lifecycle -------------------------------------------------------------------

    /**
     * Discovery only ever begins for a request that won its completion claim.
     *
     * <p>Asserted at the call site because that is the whole guarantee: started anywhere else, a
     * stopped or superseded execution could start a lookup for an answer that was never written.
     */
    @Test public void discoveryIsStartedOnlyInsideTheCompletionGate() {
        String worker = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OrbitRequestWorker.java");
        int gate = worker.indexOf("CompletionSource.WORKER_RESPONSE");
        int discover = worker.indexOf("RichAnswerCoordinator.discover(");
        assertTrue("discovery must exist", discover > 0);
        assertTrue("and must sit inside the success completion gate", discover > gate);
        assertEquals("started in exactly one place", discover,
                worker.lastIndexOf("RichAnswerCoordinator.discover("));

        String coordinator = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/RichAnswerCoordinator.java");
        assertTrue("a Stop landing mid-fetch must still be honoured",
                coordinator.contains("OrbitRequestManager.isCancelled"));
        assertTrue("and the result is only ever written through the ownership-checked attach",
                coordinator.contains("ConversationStore.attachRichImages"));
    }

    /** Nothing in the Rich Answer path touches the request lifecycle it runs beside. */
    @Test public void discoveryNeverTouchesRequestOwnership() {
        String coordinator = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/RichAnswerCoordinator.java");
        for (String forbidden : new String[]{
                "completeIfNotCancelled", "claimCompletion", "markDone", "markFailed",
                "dispatchSuccess", "dispatchError", "dispatchDelta", "SubmissionGate",
                "WorkManager", "appendMessage"}) {
            assertFalse("rich media must never reach " + forbidden,
                    coordinator.contains(forbidden));
        }
    }

    /** And it sends nothing of the user's anywhere. */
    @Test public void discoverySendsNoUserContentOutward() {
        String coordinator = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/RichAnswerCoordinator.java");
        for (String forbidden : new String[]{
                "OrbitVaultStore", "MemoryStore", "NotificationStore", "AttachmentStore",
                "ConversationStore.list", "setRequestProperty"}) {
            assertFalse("image discovery must not reach " + forbidden,
                    coordinator.contains(forbidden));
        }
        // Asserted on the API surface rather than on English, because the javadoc here legitimately
        // discusses prompts and answers while the code deliberately never touches either.
        String fetcher = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/RichAnswerPageFetcher.java");
        for (String forbidden : new String[]{
                "ConversationStore", "AssistantClient", "MemoryStore", "OrbitVaultStore",
                "Prefs.", "setDoOutput(true)"}) {
            assertFalse("a metadata read must not reach " + forbidden,
                    fetcher.contains(forbidden));
        }
    }

    /** Rich Answers is a declared provider capability, not something welded to one client. */
    @Test public void richMediaIsGatedOnAProviderCapability() {
        assertTrue("the ChatGPT path reports its sources and may offer rich media",
                new ChatGptProvider().capabilities().richWebMedia);
        for (AiProvider provider : AiProviders.all()) {
            if (provider.capabilities().richWebMedia) {
                assertTrue(provider.displayName()
                                + " cannot report sources it never searched for",
                        provider.capabilities().hostedWebSearch);
            }
        }
        String coordinator = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/RichAnswerCoordinator.java");
        assertTrue(coordinator.contains("capabilities().richWebMedia"));
        assertFalse("and it must not be gated on a provider id",
                coordinator.contains("PROVIDER_CHATGPT"));
    }

    /** The user can turn it off, and off means Orbit contacts nothing. */
    @Test public void richAnswersCanBeTurnedOff() {
        assertTrue("on by default", Prefs.richAnswers(context));
        Prefs.get(context).edit().putBoolean(Prefs.RICH_ANSWERS, false).commit();
        assertFalse(Prefs.richAnswers(context));
        assertFalse(RichAnswerCoordinator.enabled(context));
    }
}
