package com.orbit.assistant;

import static org.junit.Assert.*;

import android.content.Context;

import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The hosted-search envelopes Orbit accepts, the ones it refuses, and the shape report.
 *
 * <p>Three releases of green provenance tests sat alongside a device receiving nothing, because the
 * fixtures were the shapes Orbit already handled. These pin the wider range instead - the same
 * search reported under several field names and event names - and they pin the boundary just as
 * hard, because widening a parser is exactly how a URL a model wrote becomes a page Orbit fetches.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public class HostedSearchShapeTest {
    private static final String PAGE = "https://pubs.example.edu/entry";
    private static final String OTHER = "https://other.example.edu/entry";

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        HostedSearchSchemaTrace.clear(context);
    }

    private static Set<String> collect(String json) throws Exception {
        Set<String> urls = new LinkedHashSet<>();
        ChatGptClient.collectHostedProvenance(new JSONObject(json), urls);
        return urls;
    }

    private static void accepts(String json) throws Exception {
        assertEquals("this shape reports a consulted page", java.util.Collections.singleton(PAGE),
                collect(json));
    }

    private static void refuses(String json) throws Exception {
        assertTrue("this shape is not provenance", collect(json).isEmpty());
    }

    // ---- accepted ---------------------------------------------------------------------------------

    @Test public void searchCallResultsAreSources() throws Exception {
        accepts("{\"type\":\"response.web_search_call.completed\",\"item\":{\"results\":[{\"url\":\"" + PAGE + "\"}]}}");
    }

    @Test public void searchCallSourcesAreSources() throws Exception {
        accepts("{\"type\":\"response.web_search_call.completed\",\"sources\":[{\"type\":\"url\",\"url\":\"" + PAGE + "\"}]}");
    }

    @Test public void actionSourcesAreSources() throws Exception {
        accepts("{\"type\":\"response.output_item.done\",\"item\":{\"type\":\"web_search_call\","
                + "\"action\":{\"type\":\"search\",\"sources\":[{\"url\":\"" + PAGE + "\"}]}}}");
    }

    /** A search that opened a page consulted that page. Beta 4 read open_page and nothing else. */
    @Test public void anOpenedPageIsASource() throws Exception {
        accepts("{\"type\":\"response.output_item.done\",\"item\":{\"type\":\"web_search_call\","
                + "\"action\":{\"type\":\"open_page\",\"url\":\"" + PAGE + "\"}}}");
        accepts("{\"type\":\"response.output_item.done\",\"item\":{\"type\":\"web_search_call\","
                + "\"action\":{\"type\":\"find_in_page\",\"url\":\"" + PAGE + "\",\"pattern\":\"widow\"}}}");
    }

    /** The family name is not stable across backends; the envelope inside it is. */
    @Test public void theSearchEventFamilyNameMayVary() throws Exception {
        accepts("{\"type\":\"response.web_search.completed\",\"results\":[{\"url\":\"" + PAGE + "\"}]}");
    }

    @Test public void urlCitationAnnotationsAreSources() throws Exception {
        accepts("{\"type\":\"response.completed\",\"response\":{\"output\":[{\"type\":\"message\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"ignored\","
                + "\"annotations\":[{\"type\":\"url_citation\",\"url\":\"" + PAGE + "\"}]}]}]}}");
    }

    /** The Chat Completions shape nests the citation one level down. */
    @Test public void nestedUrlCitationObjectsAreSources() throws Exception {
        accepts("{\"type\":\"response.output_text.done\",\"annotations\":[{\"type\":\"url_citation\","
                + "\"url_citation\":{\"url\":\"" + PAGE + "\",\"title\":\"Entry\"}}]}");
    }

    /** Some builds call the same list attached to output text "citations". */
    @Test public void citationListsAttachedToOutputTextAreSources() throws Exception {
        accepts("{\"type\":\"response.content_part.done\",\"part\":{\"type\":\"output_text\","
                + "\"citations\":[{\"url\":\"" + PAGE + "\"}]}}");
    }

    /** One annotation event may inline its annotation instead of nesting it. */
    @Test public void anInlinedAnnotationEventIsASource() throws Exception {
        accepts("{\"type\":\"response.output_text.annotation.added\",\"url\":\"" + PAGE + "\"}");
        accepts("{\"type\":\"response.output_text.annotation.added\","
                + "\"annotation\":{\"type\":\"url_citation\",\"url\":\"" + PAGE + "\"}}");
    }

    // ---- refused ----------------------------------------------------------------------------------

    /** What a search typed is not what it read, even when the query looks like an address. */
    @Test public void aSearchQueryIsNeverASource() throws Exception {
        refuses("{\"type\":\"response.output_item.done\",\"item\":{\"type\":\"web_search_call\","
                + "\"action\":{\"type\":\"search\",\"query\":\"" + PAGE + "\"}}}");
    }

    @Test public void answerProseIsNeverASource() throws Exception {
        refuses("{\"type\":\"response.completed\",\"response\":{\"output\":[{\"type\":\"message\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"see " + PAGE + "\"}]}]}}");
    }

    @Test public void deviceActionArgumentsAreNeverSources() throws Exception {
        refuses("{\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\","
                + "\"name\":\"OPEN_URL\",\"arguments\":{\"url\":\"" + PAGE + "\"}}}");
    }

    /** An annotation of some other kind is not a page, whatever it carries. */
    @Test public void nonUrlCitationAnnotationsAreRefused() throws Exception {
        refuses("{\"type\":\"response.output_text.done\",\"annotations\":[{\"type\":\"file_citation\","
                + "\"url\":\"" + PAGE + "\"}]}");
    }

    /** Provenance is still policy-checked at the point it is read. */
    @Test public void unsafeAddressesAreRefusedEvenFromASearchEnvelope() throws Exception {
        refuses("{\"type\":\"response.web_search_call.completed\",\"item\":{\"results\":["
                + "{\"url\":\"http://127.0.0.1/entry\"},{\"url\":\"file:///etc/hosts\"},"
                + "{\"url\":\"javascript:alert(1)\"},{\"url\":\"http://u:p@example.com/entry\"}]}}");
    }

    // ---- the shape report --------------------------------------------------------------------------

    /** Names and counts, and nothing that was said. */
    @Test public void theSchemaReportDescribesShapeWithoutContent() throws Exception {
        HostedSearchSchemaTrace.Snapshot snapshot = new HostedSearchSchemaTrace.Snapshot();
        HostedSearchSchemaTrace.observe(snapshot,
                new JSONObject("{\"type\":\"response.web_search_call.in_progress\",\"id\":\"ws_1\"}"));
        HostedSearchSchemaTrace.observe(snapshot, new JSONObject(
                "{\"type\":\"response.output_item.done\",\"item\":{\"type\":\"web_search_call\","
                        + "\"id\":\"ws_1\",\"status\":\"completed\",\"action\":{\"type\":\"search\","
                        + "\"query\":\"northern black widow spider identification\","
                        + "\"sources\":[{\"url\":\"" + PAGE + "\"},{\"url\":\"" + OTHER + "\"}]}}}"));
        HostedSearchSchemaTrace.observe(snapshot, new JSONObject(
                "{\"type\":\"response.output_text.done\",\"annotations\":[{\"type\":\"url_citation\","
                        + "\"url\":\"" + PAGE + "\"}]}"));
        HostedSearchSchemaTrace.recognized(snapshot, PAGE);
        HostedSearchSchemaTrace.record(context, snapshot);

        String body = HostedSearchSchemaTrace.body(context);
        assertTrue(body.contains("response.web_search_call.in_progress"));
        assertTrue(body.contains("response.output_item.done"));
        assertTrue(body.contains("url_citation"));
        assertTrue(body.contains("- action"));
        assertTrue(body.contains("- sources"));
        assertTrue(body.contains("- query"));
        assertTrue(body.contains("Source objects seen: 2"));
        assertTrue(body.contains("Source URLs recognized: 1"));
        assertTrue(body.contains("pubs.example.edu"));

        assertFalse("the search query itself must never be recorded",
                body.contains("northern black widow"));
        assertFalse("nor any path", body.contains("/entry"));
        assertFalse(body.contains(PAGE));
        assertFalse(HostedSearchSchemaTrace.summaryLine(context).isEmpty());
    }

    /** A response that never searched leaves nothing behind. */
    @Test public void anUnsearchedResponseStoresNothing() throws Exception {
        HostedSearchSchemaTrace.Snapshot snapshot = new HostedSearchSchemaTrace.Snapshot();
        HostedSearchSchemaTrace.observe(snapshot,
                new JSONObject("{\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}"));
        HostedSearchSchemaTrace.record(context, snapshot);
        assertNull(HostedSearchSchemaTrace.last(context));
        assertEquals("", HostedSearchSchemaTrace.summaryLine(context));
        assertTrue(HostedSearchSchemaTrace.body(context).contains("No hosted web search"));
    }

    /** Anything that is not an identifier is not a schema name and is not stored. */
    @Test public void onlyIdentifierShapedNamesAreKept() throws Exception {
        HostedSearchSchemaTrace.Snapshot snapshot = new HostedSearchSchemaTrace.Snapshot();
        StringBuilder sentence = new StringBuilder("{\"type\":\"response.web_search_call.completed\",");
        sentence.append("\"the user asked about spiders\":1,");
        for (int i = 0; i < HostedSearchSchemaTrace.MAX_NAMES + 12; i++) {
            sentence.append("\"key_").append(i).append("\":1,");
        }
        sentence.append("\"status\":\"completed\"}");
        HostedSearchSchemaTrace.observe(snapshot, new JSONObject(sentence.toString()));
        assertTrue(snapshot.searchCallKeys.size() <= HostedSearchSchemaTrace.MAX_NAMES);
        assertFalse(snapshot.searchCallKeys.contains("the user asked about spiders"));
    }
}
