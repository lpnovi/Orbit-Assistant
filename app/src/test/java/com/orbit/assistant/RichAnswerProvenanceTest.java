package com.orbit.assistant;

import static org.junit.Assert.*;
import android.content.Context;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.ByteArrayInputStream;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Provider envelopes -> memory/narration -> real worker completion -> discovery/storage. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public class RichAnswerProvenanceTest {
    private static final String PROMPT = "Search the web and describe what a Northern black widow looks like, including its important identifying features.";
    private static final String ANSWER = "A shiny black spider with a red hourglass.";
    private static final String OLD = "https://pubs.example.edu/old";
    private static final String NEW = "https://pubs.example.edu/new";
    private Context context;
    private RichAnswerPageFetcher.PageSource previousPages;
    private RemoteImageLoader.Transport previousImages;
    private final List<String> pages = new CopyOnWriteArrayList<>();
    private CountDownLatch entered, release;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().putBoolean(Prefs.BACKGROUND_NOTIFICATIONS, false).commit();
        ConversationStore.clear(context);
        RichAnswerTrace.clear(context);
        OrbitRequestManager.resetForTest();
        previousPages = RichAnswerPageFetcher.installSourceForTest(url -> {
            pages.add(url);
            if (OLD.equals(url) && entered != null) {
                entered.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            }
            return RichAnswerPageFetcher.parse("<article><h1>Northern black widow</h1>"
                    + "<img src='" + url + "/northern-black-widow.png' width='800' height='600' "
                    + "alt='Northern black widow showing the hourglass'></article>", url, 200, "text/html", 0);
        });
        previousImages = RemoteImageLoader.installTransportForTest(new RemoteImageLoader.Transport() {
            public boolean allowsHost(String url) { return true; }
            public RemoteImageLoader.Response open(String url) {
                byte[] png = TestPng.rgb(800, 600);
                return new RemoteImageLoader.Response(200, "image/png", null, png.length,
                        new ByteArrayInputStream(png));
            }
        });
        ConversationStore.save(context, "chat", Collections.singletonList(new AssistantClient.History("user", PROMPT)));
    }

    @After public void tearDown() throws Exception {
        if (release != null) release.countDown();
        drainDiscovery();
        RichAnswerPageFetcher.installSourceForTest(previousPages);
        RemoteImageLoader.installTransportForTest(previousImages);
        OrbitRequestManager.resetForTest();
    }

    private static void drainDiscovery() throws Exception {
        Field field = RichAnswerCoordinator.class.getDeclaredField("EXECUTOR");
        field.setAccessible(true);
        ((ExecutorService) field.get(null)).submit(() -> {}).get(15, TimeUnit.SECONDS);
    }

    private AssistantReply streamed(String url, String shape) throws Exception {
        String event;
        if ("tool".equals(shape)) {
            event = "{\"type\":\"response.web_search_call.completed\",\"item\":{\"results\":[{\"url\":\"" + url + "\"}]}}";
        } else if ("item".equals(shape)) {
            event = "{\"type\":\"response.output_item.done\",\"item\":{\"type\":\"web_search_call\",\"action\":{\"sources\":[{\"url\":\"" + url + "\"}]}}}";
        } else {
            event = "{\"type\":\"response.completed\",\"response\":{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"ignored\",\"annotations\":[{\"type\":\"url_citation\",\"url\":\"" + url + "\"}]}]}]}}";
        }
        Method read = ChatGptClient.class.getDeclaredMethod("readSse", java.io.InputStream.class,
                AssistantClient.Callback.class, boolean.class);
        read.setAccessible(true);
        AssistantClient.Callback sink = new AssistantClient.Callback() {
            public void onSuccess(AssistantReply reply) {}
            public void onError(String message) { fail(message); }
        };
        Object stream = read.invoke(null, new ByteArrayInputStream(("data: " + event + "\n\ndata: [DONE]\n")
                .getBytes(StandardCharsets.UTF_8)), sink, false);
        Field sources = stream.getClass().getDeclaredField("sourceUrls"); sources.setAccessible(true);
        Field first = stream.getClass().getDeclaredField("sourceUrl"); first.setAccessible(true);
        assertEquals(url, first.get(stream));
        @SuppressWarnings("unchecked") List<String> urls = (List<String>) sources.get(stream);
        assertEquals(Collections.singletonList(url), urls);
        AssistantReply reply = new AssistantReply("  " + ANSWER + "  ").withSourceUrls(urls);
        AtomicReference<AssistantReply> delivered = new AtomicReference<>();
        Method decorate = AssistantClient.class.getDeclaredMethod("decorateMemoryMetadata",
                AssistantClient.Callback.class, MemoryStore.Selection.class, MemoryStore.Suggestion.class);
        decorate.setAccessible(true);
        AssistantClient.Callback output = new AssistantClient.Callback() {
            public void onSuccess(AssistantReply value) { delivered.set(value); }
            public void onError(String message) { fail(message); }
        };
        AssistantClient.Callback memory = (AssistantClient.Callback) decorate.invoke(null,
                ActionNarration.guard(output), null, null);
        memory.onSuccess(reply);
        assertEquals(urls, delivered.get().sourceUrls);
        return delivered.get();
    }

    private PendingRequestStore.Item complete(String url, String shape) throws Exception {
        PendingRequestStore.Item item = PendingRequestStore.create(context, "chat", PROMPT,
                "", "", false, false, Prefs.MODE_BALANCED, false, "");
        item = PendingRequestStore.load(context, item.id);
        OrbitRequestWorker.completeProviderReply(context, item, streamed(url, shape), WorkerAttempt.of(1, false));
        assertTrue(PendingRequestStore.isCommitted(context, item.id));
        return item;
    }

    @Test public void freshSendCarriesSearchSourcesThroughCompletionAndArticleDiscovery() throws Exception {
        PendingRequestStore.Item item = complete(OLD, "tool");
        drainDiscovery();
        AssistantClient.History stored = ConversationStore.load(context, "chat").messages.get(1);
        assertEquals(ANSWER, stored.content);
        assertEquals(item.id, stored.replyRequestId);
        assertEquals(Collections.singletonList(OLD), stored.sourceUrls);
        assertTrue(stored.hasRichImages());
        assertEquals(OLD, stored.richImages.get(0).sourceUrl);
        assertEquals(Collections.singletonList(OLD), pages);
        RichAnswerTrace.Attempt trace = RichAnswerTrace.last(context);
        assertEquals(1, trace.sourcesReceived);
        assertEquals(RichAnswerTrace.Intent.STRONG_VISUAL, trace.intent);
        assertEquals(RichAnswerTrace.Outcome.IMAGE_FOUND_AND_ATTACHED, trace.outcome);
    }

    @Test public void regenerateWithIdenticalTextCannotReceiveTheOldRequestsImage() throws Exception {
        entered = new CountDownLatch(1); release = new CountDownLatch(1);
        PendingRequestStore.Item original = complete(OLD, "tool");
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        ConversationStore.removeLastAssistantTurn(context, "chat");
        PendingRequestStore.Item regenerated = complete(NEW, "citation");
        assertNotEquals(original.id, regenerated.id);
        release.countDown(); drainDiscovery();
        AssistantClient.History stored = ConversationStore.load(context, "chat").messages.get(1);
        assertEquals(regenerated.id, stored.replyRequestId);
        assertEquals(Collections.singletonList(NEW), stored.sourceUrls);
        assertEquals(NEW, stored.richImages.get(0).sourceUrl);
        assertEquals(Arrays.asList(OLD, NEW), pages);
        assertEquals(2, RichAnswerTrace.attempts(context).size());
        assertTrue(RichAnswerTrace.attempts(context).stream().anyMatch(t ->
                t.outcome == RichAnswerTrace.Outcome.IMAGE_FOUND_BUT_MESSAGE_NOT_FOUND));
    }

    @Test public void completedToolItemCarriesSourcesToo() throws Exception {
        assertEquals(Collections.singletonList(NEW), streamed(NEW, "item").sourceUrls);
    }

    @Test public void visualAnswerWithoutProvenanceRecordsAnExplicitPrivateTrace() {
        RichAnswerCoordinator.discover(context, "chat", "request", PROMPT,
                new AssistantReply(ANSWER + "\nSource: " + OLD));
        RichAnswerTrace.Attempt trace = RichAnswerTrace.last(context);
        assertNotNull(trace);
        assertEquals(0, trace.sourcesReceived);
        assertEquals(RichAnswerTrace.Intent.STRONG_VISUAL, trace.intent);
        assertEquals(RichAnswerTrace.Outcome.NO_SOURCE_URLS_RECEIVED, trace.outcome);
        assertTrue(pages.isEmpty());
        assertFalse(RichAnswerTrace.report(context).contains(PROMPT));
        assertFalse(RichAnswerTrace.report(context).contains(ANSWER));
    }

    @Test public void modelWrittenUrlsAndActionArgumentsNeverBecomeProvenance() throws Exception {
        Set<String> urls = new LinkedHashSet<>();
        ChatGptClient.collectHostedProvenance(new JSONObject("{\"type\":\"response.completed\",\"response\":{\"output\":["
                + "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"" + OLD + "\"}]},"
                + "{\"type\":\"function_call\",\"arguments\":{\"url\":\"" + NEW + "\"}}]}}"), urls);
        assertTrue(urls.isEmpty());
        ChatGptClient.collectHostedProvenance(new JSONObject("{\"type\":\"response.output_item.done\","
                + "\"item\":{\"type\":\"web_search_call\",\"action\":{\"type\":\"search\","
                + "\"query\":\"" + OLD + "\"}}}"), urls);
        assertTrue("a search query URL is not a consulted source", urls.isEmpty());
    }

    @Test public void unsupportedProviderRecordsWhyEvenWithoutSources() {
        assertTrue(AiProviders.select(context, Prefs.PROVIDER_RELAY));
        RichAnswerCoordinator.discover(context, "chat", "request", PROMPT, new AssistantReply(ANSWER));
        assertEquals(RichAnswerTrace.Outcome.PROVIDER_UNSUPPORTED, RichAnswerTrace.last(context).outcome);
    }

    @Test public void disabledVisualRequestRecordsWhyEvenWithoutSources() {
        Prefs.get(context).edit().putBoolean(Prefs.RICH_ANSWERS, false).commit();
        RichAnswerCoordinator.discover(context, "chat", "request", PROMPT, new AssistantReply(ANSWER));
        assertEquals(RichAnswerTrace.Outcome.DISABLED, RichAnswerTrace.last(context).outcome);
    }

    @Test public void lifecycleSaveRetainsSourcesAndOwner() throws Exception {
        PendingRequestStore.Item item = complete(NEW, "tool");
        drainDiscovery();
        ConversationStore.save(context, "chat", Arrays.asList(new AssistantClient.History("user", PROMPT),
                new AssistantClient.History("assistant", ANSWER)));
        AssistantClient.History stored = ConversationStore.load(context, "chat").messages.get(1);
        assertEquals(item.id, stored.replyRequestId);
        assertEquals(Collections.singletonList(NEW), stored.sourceUrls);
        assertTrue(stored.hasRichImages());
        assertEquals(item.id, stored.withStoppedRequestId("stop").replyRequestId);
        assertEquals(stored.sourceUrls, stored.withRichImages(Collections.emptyList()).sourceUrls);
    }

    @Test public void cancelledCompletionDoesNotWriteAnAnswerOrStartDiscovery() throws Exception {
        OrbitRequestManager.setWorkCanceller(name -> {});
        PendingRequestStore.Item item = PendingRequestStore.create(context, "chat", PROMPT,
                "", "", false, false, Prefs.MODE_BALANCED, false, "");
        assertTrue(OrbitRequestManager.cancel(context, item.id));
        OrbitRequestWorker.completeProviderReply(context, item, streamed(NEW, "tool"), WorkerAttempt.of(1, false));
        drainDiscovery();
        assertTrue(pages.isEmpty());
        assertEquals(1, ConversationStore.load(context, "chat").messages.size());
    }

    @Test public void duplicateWorkerCompletionCannotStartAnotherDiscovery() throws Exception {
        PendingRequestStore.Item item = complete(OLD, "tool");
        OrbitRequestWorker.completeProviderReply(context, item, streamed(NEW, "tool"), WorkerAttempt.of(2, false));
        drainDiscovery();
        assertEquals(Collections.singletonList(OLD), pages);
        assertEquals(2, ConversationStore.load(context, "chat").messages.size());
    }
}
