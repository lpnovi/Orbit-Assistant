package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The one thing Rich Answers could never manufacture: a page the answer actually used.
 *
 * <p>Beta 3 through Beta 7 each fixed a stage that runs after provenance exists. The stage before
 * all of them was never addressed, and on a real device it is where "show me pictures of a mallard
 * duck" ends: the model answers from memory, no search happens, no page is cited, and the entire
 * picture pipeline correctly does nothing. These pin the fix and, just as hard, its edges - because
 * requiring a web search is a real cost paid on the user's turn, and paying it on the wrong turns
 * would be a worse bug than the one being fixed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public class ForcedVisualSearchTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        Prefs.get(context).edit().putBoolean(Prefs.RICH_ANSWERS, true).commit();
        HostedSearchPolicy.clear(context);
    }

    private boolean forces(String prompt) {
        return HostedSearchPolicy.shouldForce(context, prompt, false);
    }

    // ---- which questions require a search --------------------------------------------------------

    /** The questions whose answer is a picture. Text alone is a failure for these. */
    @Test public void aQuestionWhoseAnswerIsAPictureRequiresASearch() {
        assertTrue(forces("show me pictures of a mallard duck"));
        assertTrue(forces("what does a northern black widow look like"));
        assertTrue(forces("mallard duck photos"));
        assertTrue(forces("how do i identify a northern cardinal"));
        assertTrue(forces("show me a picture of the Hagia Sophia"));
    }

    /**
     * A question a picture merely improves is left exactly as it was.
     *
     * <p>The distinction is {@link RichAnswerRelevance}'s existing three-step intent scale, asked
     * of the prompt alone because the answer does not exist yet at request time. Only the strongest
     * step forces anything.
     */
    @Test public void aMerelyVisualQuestionIsNotForced() {
        assertEquals(RichAnswerTrace.Intent.VISUAL,
                RichAnswerRelevance.intentFor("best hotel in lisbon", ""));
        assertFalse(forces("best hotel in lisbon"));
    }

    /** And an ordinary question is untouched, which is almost every question. */
    @Test public void ordinaryQuestionsAreUntouched() {
        assertFalse(forces("what is a hash map"));
        assertFalse(forces("convert 20 usd to eur"));
        assertFalse(forces("summarize this email"));
        assertFalse(forces("set a timer for ten minutes"));
        assertFalse(forces(""));
        assertFalse(forces(null));
    }

    /**
     * A question about a picture the user is holding is answered by that picture.
     *
     * <p>Sending Orbit to the web for "what is this" while the user has attached a photograph of it
     * would be slower and wrong at once, so an attachment refuses the whole path.
     */
    @Test public void aTurnCarryingAPictureNeverForcesASearch() {
        assertTrue(forces("what does this look like"));
        assertFalse(HostedSearchPolicy.shouldForce(context, "what does this look like", true));
    }

    /** Asking for a browser is asking for the external action, which was never the hosted tool. */
    @Test public void askingToOpenABrowserIsNotForced() {
        assertFalse(forces("google pictures of a mallard duck"));
        assertFalse(forces("open google and show me pictures of a mallard duck"));
    }

    /** Rich Answers off means no sourced pictures, so there is nothing to buy a search for. */
    @Test public void richAnswersOffChangesNothing() {
        Prefs.get(context).edit().putBoolean(Prefs.RICH_ANSWERS, false).commit();
        assertFalse(forces("show me pictures of a mallard duck"));
    }

    // ---- what the request carries ------------------------------------------------------------------

    private JSONObject bodyFor(String prompt, boolean force) throws Exception {
        JSONObject root = new JSONObject();
        ChatGptClient.applyHostedSearch(root, context, prompt, force);
        return root;
    }

    /** Every request that was offered the tool is still offered it on the same terms. */
    @Test public void anOrdinaryRequestStillOffersTheToolAndChoosesAuto() throws Exception {
        JSONObject root = bodyFor("what is a hash map", false);
        assertEquals(1, root.getJSONArray("tools").length());
        assertEquals("web_search", root.getJSONArray("tools").getJSONObject(0).getString("type"));
        assertEquals("auto", root.getString("tool_choice"));
        assertEquals("and it carries no search instruction", "", ChatGptClient.searchPolicy(false));
    }

    /** A forced request names the hosted tool, and says so in words as well as in a field. */
    @Test public void aForcedRequestNamesTheHostedTool() throws Exception {
        JSONObject root = bodyFor("show me pictures of a mallard duck", true);
        assertEquals("web_search", root.getJSONArray("tools").getJSONObject(0).getString("type"));
        assertEquals("web_search", root.getJSONObject("tool_choice").getString("type"));

        String instruction = ChatGptClient.searchPolicy(true);
        assertTrue(instruction.contains("required for it rather than optional"));
        assertTrue("the Source line is what saves an unreadable search envelope",
                instruction.contains("Source:"));
        assertTrue(instruction.contains("never answer this one from memory alone"));
    }

    /** A request nobody offers the tool to gets neither the tool nor a choice. */
    @Test public void aBrowserRequestGetsNoToolAtAll() throws Exception {
        JSONObject root = bodyFor("google mallard duck", true);
        assertFalse(root.has("tools"));
        assertFalse(root.has("tool_choice"));
    }

    // ---- the ladder ---------------------------------------------------------------------------------

    /**
     * A backend that refuses the precise form is asked in the general one, and then left alone.
     *
     * <p>The Codex-backed endpoint is not a documented public API, so how it handles a forced
     * hosted tool is not something Orbit may assume. Each refusal costs one immediate retry and
     * nothing else: the request was rejected before anything was generated.
     */
    @Test public void aRefusedFormStepsDownAndThenStops() throws Exception {
        assertEquals(HostedSearchPolicy.MODE_HOSTED_TOOL, HostedSearchPolicy.mode(context));
        assertEquals("web_search",
                ((JSONObject) HostedSearchPolicy.toolChoice(context)).getString("type"));

        HostedSearchPolicy.degrade(context);
        assertEquals(HostedSearchPolicy.MODE_REQUIRED, HostedSearchPolicy.mode(context));
        assertEquals("required", HostedSearchPolicy.toolChoice(context));
        assertEquals("required", bodyFor("show me pictures of a mallard duck", true)
                .getString("tool_choice"));

        HostedSearchPolicy.degrade(context);
        assertEquals(HostedSearchPolicy.MODE_UNAVAILABLE, HostedSearchPolicy.mode(context));
        assertFalse("a refused backend goes back to Beta 7 behaviour, never to an error",
                HostedSearchPolicy.available(context));
        assertFalse(forces("show me pictures of a mallard duck"));

        HostedSearchPolicy.degrade(context);
        assertEquals("and the ladder has a bottom",
                HostedSearchPolicy.MODE_UNAVAILABLE, HostedSearchPolicy.mode(context));
    }

    /**
     * Only a refusal of the field itself counts as one.
     *
     * <p>Reading this permissively would hide real failures behind a silent retry, which is the
     * expensive direction to be wrong in.
     */
    @Test public void onlyARefusalOfTheToolChoiceFieldIsTreatedAsOne() {
        assertTrue(HostedSearchPolicy.looksLikeForcedSearchRefusal(400,
                "{\"error\":{\"message\":\"Unsupported value for 'tool_choice'\"}}"));
        assertTrue(HostedSearchPolicy.looksLikeForcedSearchRefusal(422,
                "{\"error\":{\"message\":\"tool_choice must be one of: auto, none\"}}"));

        assertFalse("a rate limit is a rate limit", HostedSearchPolicy.looksLikeForcedSearchRefusal(
                429, "{\"error\":{\"message\":\"tool_choice\"}}"));
        assertFalse("a server error is a server error",
                HostedSearchPolicy.looksLikeForcedSearchRefusal(500, "tool_choice unsupported"));
        assertFalse("and a 400 about something else must still be reported",
                HostedSearchPolicy.looksLikeForcedSearchRefusal(400,
                        "{\"error\":{\"message\":\"Invalid model for this account\"}}"));
        assertFalse(HostedSearchPolicy.looksLikeForcedSearchRefusal(400, ""));
        assertFalse(HostedSearchPolicy.looksLikeForcedSearchRefusal(400, null));
    }

    /** The retry path lives where a rejection is read, and is wired to the ladder. */
    @Test public void theClientRetriesARefusedForcedSearch() {
        String client = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/ChatGptClient.java");
        assertTrue(client.contains("HostedSearchPolicy.shouldForce(context, prompt"));
        assertTrue(client.contains("HostedSearchPolicy.looksLikeForcedSearchRefusal(code, err)"));
        assertTrue(client.contains("HostedSearchPolicy.degrade(context)"));
    }

    // ---- diagnostics ---------------------------------------------------------------------------------

    /**
     * Counts, and nothing that was said.
     *
     * <p>A forced request that produced no search at all is the finding this reports, and it is the
     * one thing no earlier release could have shown anybody.
     */
    @Test public void diagnosticsCountForcedRequestsWithoutRecordingContent() {
        assertTrue(HostedSearchPolicy.summaryLine(context).isEmpty());
        assertTrue(HostedSearchPolicy.body(context).contains("Requests that required a search: 0"));

        HostedSearchPolicy.recordForcedRequest(context, true);
        HostedSearchPolicy.recordForcedRequest(context, false);

        String body = HostedSearchPolicy.body(context);
        assertTrue(body.contains("Requests that required a search: 2"));
        assertTrue(body.contains("Of those, searches observed: 1"));
        assertTrue(body.contains("Search for picture requests: required (hosted tool)"));
        assertFalse("nothing a user typed has anywhere to be stored", body.contains("mallard"));
        assertFalse(HostedSearchPolicy.summaryLine(context).isEmpty());
    }

    /** A refused backend says so, so a device with no pictures is explainable. */
    @Test public void diagnosticsSayWhenTheBackendRefusedToBeToldWhatToUse() {
        HostedSearchPolicy.degrade(context);
        HostedSearchPolicy.degrade(context);
        assertTrue(HostedSearchPolicy.body(context).contains("off (backend refused)"));
    }
}
