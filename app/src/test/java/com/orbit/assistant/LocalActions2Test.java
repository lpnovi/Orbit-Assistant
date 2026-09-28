package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.media.AudioManager;

import org.robolectric.RuntimeEnvironment;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local Device Actions 2.0: multi-action plans, undo follow-ups and Settings destinations.
 *
 * <p>The model is never run. Everything it could write is fed straight to the same validator and
 * decision Orbit uses, which is the point: whatever the model says, these are the only outcomes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class LocalActions2Test {

    private static final LocalActionSchema.AppResolver APPS = wanted ->
            wanted != null && wanted.trim().equalsIgnoreCase("spotify") ? "Spotify" : null;

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        RecentActionContext.clear();
    }

    @After public void tearDown() {
        RecentActionContext.clear();
    }

    private static LocalActionSchema.Plan plan(String json, String userText) {
        return LocalActionSchema.validatePlan(json, APPS, userText);
    }

    private static String pair(String first, String second) {
        return "{\"actions\":[" + first + "," + second + "]}";
    }

    private static final String DND_ON = "{\"action\":\"SET_DND\",\"params\":{\"enabled\":true}}";
    private static final String DIM_30 = "{\"action\":\"SET_BRIGHTNESS\",\"params\":{\"percent\":30}}";
    private static final String TORCH_OFF = "{\"action\":\"FLASHLIGHT\",\"params\":{\"on\":false}}";

    // ---- multi-action plans ----------------------------------------------------------------------

    @Test public void aRequestedPairIsAcceptedInOrder() {
        LocalActionSchema.Plan p = plan(pair(DND_ON, DIM_30),
                "Turn on DND and lower my brightness to 30%");
        assertTrue(p.rejection, p.accepted());
        assertEquals(2, p.actions.size());
        assertEquals("SET_DND", p.actions.get(0).type);
        assertEquals("SET_BRIGHTNESS", p.actions.get(1).type);
        assertEquals("dnd+brightness", p.category);
    }

    @Test public void oneInvalidStepRejectsTheWholePlan() {
        String bad = "{\"action\":\"SET_BRIGHTNESS\",\"params\":{\"percent\":300}}";
        LocalActionSchema.Plan p = plan(pair(DND_ON, bad), "turn on dnd and brightness 300");
        assertFalse(p.accepted());
        assertEquals(LocalActionSchema.REJECT_OUT_OF_RANGE, p.rejection);
        assertTrue("nothing is partly obeyed", p.actions.isEmpty());

        String smuggled = "{\"action\":\"OPEN_URL\",\"params\":{}}";
        assertEquals(LocalActionSchema.REJECT_UNKNOWN_ACTION,
                plan(pair(DND_ON, smuggled), "turn on dnd and open the link").rejection);
        String forbidden = "{\"action\":\"FLASHLIGHT\",\"params\":{\"on\":true,\"intent\":\"x\"}}";
        assertEquals(LocalActionSchema.REJECT_FORBIDDEN_FIELD,
                plan(pair(DND_ON, forbidden), "turn on dnd and the torch").rejection);
    }

    @Test public void plansAreSmall() {
        String four = "{\"actions\":[" + DND_ON + "," + DIM_30 + "," + TORCH_OFF + ","
                + "{\"action\":\"SET_VOLUME\",\"params\":{\"percent\":10}}]}";
        assertEquals(LocalActionSchema.REJECT_BAD_PLAN,
                plan(four, "dnd on, dim the screen, torch off and volume down").rejection);
        String three = "{\"actions\":[" + DND_ON + "," + DIM_30 + "," + TORCH_OFF + "]}";
        assertTrue(plan(three, "dnd on, dim the screen and torch off").accepted());
    }

    @Test public void aTargetMayNotAppearTwice() {
        String again = "{\"action\":\"SET_BRIGHTNESS\",\"params\":{\"percent\":80}}";
        assertEquals(LocalActionSchema.REJECT_BAD_PLAN,
                plan(pair(DIM_30, again), "dim the screen and then brighten the screen").rejection);
    }

    @Test public void anActionTheUserNeverMentionedRejectsThePlan() {
        assertEquals(LocalActionSchema.REJECT_UNREQUESTED,
                plan(pair(DIM_30, DND_ON), "dim the screen a lot please").rejection);
    }

    @Test public void onlyOneScreenChangingStepAndOnlyLast() {
        String app = "{\"action\":\"OPEN_APP\",\"params\":{\"app\":\"Spotify\"}}";
        String settings = "{\"action\":\"OPEN_SETTINGS\",\"params\":{\"page\":\"bluetooth\"}}";
        assertTrue(plan(pair(DND_ON, app), "turn on dnd and open spotify").accepted());
        assertEquals(LocalActionSchema.REJECT_BAD_PLAN,
                plan(pair(app, DND_ON), "open spotify and turn on dnd").rejection);
        assertEquals(LocalActionSchema.REJECT_BAD_PLAN,
                plan(pair(app, settings), "open spotify and open bluetooth settings").rejection);
    }

    @Test public void theWrapperMayHoldNothingButTheList() {
        assertEquals(LocalActionSchema.REJECT_UNKNOWN_FIELD,
                plan("{\"actions\":[" + DND_ON + "],\"shell\":\"x\"}", "turn on dnd").rejection);
        assertEquals(LocalActionSchema.REJECT_NO_ACTION, plan("{\"actions\":[]}", "x").rejection);
    }

    @Test public void theSingleActionValidatorIsUnchanged() {
        assertEquals("validate() still refuses lists, so existing callers keep their contract",
                LocalActionSchema.REJECT_MULTIPLE_ACTIONS,
                LocalActionSchema.validate(pair(DND_ON, DIM_30), APPS).rejection);
    }

    // ---- Settings destinations -------------------------------------------------------------------

    @Test public void settingsPagesMapOntoActionsOrbitAlreadyHas() {
        assertEquals("OPEN_BLUETOOTH_SETTINGS", LocalActionSchema.validate(
                "{\"action\":\"OPEN_SETTINGS\",\"params\":{\"page\":\"bluetooth\"}}", APPS).action.type);
        assertEquals("OPEN_INTERNET_PANEL", LocalActionSchema.validate(
                "{\"action\":\"OPEN_SETTINGS\",\"params\":{\"page\":\"wifi\"}}", APPS).action.type);
        assertEquals("OPEN_SETTINGS", LocalActionSchema.validate(
                "{\"action\":\"OPEN_SETTINGS\",\"params\":{}}", APPS).action.type);
        assertEquals(LocalActionSchema.REJECT_BAD_PARAMS, LocalActionSchema.validate(
                "{\"action\":\"OPEN_SETTINGS\",\"params\":{\"page\":\"developer options\"}}", APPS)
                .rejection);
        assertEquals(0, LocalActionSchema.validate(
                "{\"action\":\"OPEN_SETTINGS\",\"params\":{\"page\":\"bluetooth\"}}", APPS)
                .action.params.length());
    }

    @Test public void theModelVocabularyGainedNothingDangerous() {
        for (String dangerous : new String[]{"SMS", "DIAL", "OPEN_URL", "WEB_SEARCH", "SHARE",
                "COPY", "CREATE_EVENT", "ADD_CALENDAR_EVENTS", "NAVIGATE", "EXTENSION_ACTION",
                "OPEN_INTERNET_PANEL", "OPEN_BLUETOOTH_SETTINGS"}) {
            assertFalse(dangerous, LocalActionSchema.ALLOWED_ACTIONS.contains(dangerous));
        }
    }

    // ---- undo ---------------------------------------------------------------------------------------

    @Test public void undoResolvesOnlyFromWhatOrbitRecorded() {
        String undo = "{\"action\":\"UNDO_LAST\"}";
        OrbitLocalActionRouter.Decision none =
                OrbitLocalActionRouter.decide("go back to how it was", undo, APPS);
        assertNull("with nothing recorded there is nothing to undo", none.reply);

        RecentActionContext.recordLevel(RecentActionContext.Target.VOLUME, 35);
        OrbitLocalActionRouter.Decision volume =
                OrbitLocalActionRouter.decide("actually go back to how it was", undo, APPS);
        assertNotNull(volume.reply);
        assertEquals("SET_VOLUME", volume.reply.actions.get(0).type);
        assertEquals(35, volume.reply.actions.get(0).params.optInt("percent"));
        assertEquals("undo", volume.category);
    }

    @Test public void undoCannotCarryParametersOrCompany() {
        RecentActionContext.recordLevel(RecentActionContext.Target.VOLUME, 35);
        assertEquals(LocalActionSchema.REJECT_UNKNOWN_FIELD, LocalActionSchema.validatePlan(
                "{\"action\":\"UNDO_LAST\",\"params\":{\"percent\":100}}", APPS, "undo").rejection);
        assertEquals(LocalActionSchema.REJECT_BAD_PLAN, LocalActionSchema.validatePlan(
                pair("{\"action\":\"UNDO_LAST\"}", DND_ON), APPS, "undo and turn on dnd").rejection);
    }

    @Test public void theModelCannotTurnAnUnrelatedSentenceIntoAnUndo() {
        RecentActionContext.recordLevel(RecentActionContext.Target.VOLUME, 35);
        assertNull(OrbitLocalActionRouter.decide("never mind, thanks",
                "{\"action\":\"UNDO_LAST\"}", APPS).reply);
    }

    @Test public void anAmbiguousUndoAsksInsteadOfGuessing() {
        RecentActionContext.beginBatch();
        RecentActionContext.recordDnd(Boolean.FALSE);
        RecentActionContext.recordLevel(RecentActionContext.Target.BRIGHTNESS, 70);
        RecentActionContext.endBatch();
        assertTrue(RecentActionContext.isAmbiguous());
        assertNull(RecentActionContext.current());

        OrbitLocalActionRouter.Decision d =
                OrbitLocalActionRouter.decide("put it back", "{\"action\":\"UNDO_LAST\"}", APPS);
        assertNotNull(d.reply);
        assertTrue("a question changes nothing", d.reply.actions.isEmpty());
        assertTrue(d.reply.text.contains("Do Not Disturb"));
        assertTrue(d.reply.text.contains("brightness"));
        assertEquals("clarify", d.outcome);

        AssistantReply deterministic = LocalCommandRouter.tryHandle(context, "put it back");
        assertNotNull("the deterministic path asks too", deterministic);
        assertTrue(deterministic.actions.isEmpty());
        assertNull("and a bare direction is not guessed either",
                firstAction(LocalCommandRouter.tryHandle(context, "a little more")));
    }

    private static AssistantReply.Action firstAction(AssistantReply reply) {
        return reply == null || reply.actions.isEmpty() ? null : reply.actions.get(0);
    }

    // ---- follow-ups for Do Not Disturb and the ringer --------------------------------------------------

    @Test public void doNotDisturbFollowUpsUseTheRecordedState() {
        RecentActionContext.recordDnd(Boolean.TRUE);   // it was on; Orbit turned it off
        AssistantReply back = LocalCommandRouter.tryHandle(context, "turn it back on");
        assertNotNull(back);
        assertEquals("SET_DND", back.actions.get(0).type);
        assertTrue(back.actions.get(0).params.optBoolean("enabled"));
        assertNull("'back off' contradicts what it was, so it is not guessed",
                LocalCommandRouter.tryHandle(context, "turn it back off"));
        AssistantReply undo = LocalCommandRouter.tryHandle(context, "undo that");
        assertEquals("SET_DND", undo.actions.get(0).type);
    }

    @Test public void anUnrestorableDoNotDisturbStateIsNotGuessed() {
        RecentActionContext.recordDnd(null);
        assertNull(LocalCommandRouter.tryHandle(context, "put it back"));
    }

    @Test public void ringerFollowUpsRestoreTheExactMode() {
        RecentActionContext.recordRinger("Vibrate");
        AssistantReply undo = LocalCommandRouter.tryHandle(context, "change it back");
        assertNotNull(undo);
        assertEquals("SET_RINGER_MODE", undo.actions.get(0).type);
        assertEquals("vibrate", undo.actions.get(0).params.optString("mode"));
        assertNull("the ringer has three states, so no bare direction applies",
                LocalCommandRouter.tryHandle(context, "a little more"));
    }

    @Test public void moreWaysToSayUndoAreRecognised() {
        for (String phrase : new String[]{"undo", "undo it", "revert that", "put it back",
                "change it back", "set it back to how it was", "go back to how it was",
                "put it back the way it was"}) {
            assertTrue(phrase, RecentActionContext.isRestore(phrase));
        }
        for (String phrase : new String[]{"put the kettle on", "go back", "back up my photos"}) {
            assertFalse(phrase, RecentActionContext.isRestore(phrase));
        }
    }

    @Test public void theActionEngineRemembersAMultiActionRunAsOneBatch() throws Exception {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        List<AssistantReply.Action> actions = new ArrayList<>();
        actions.add(new AssistantReply.Action("SET_VOLUME",
                new JSONObject().put("percent", 20), false));
        actions.add(new AssistantReply.Action("SET_RINGER_MODE",
                new JSONObject().put("mode", "normal"), false));
        AtomicBoolean finished = new AtomicBoolean(false);
        OrbitActionEngine.execute(context, actions, null, new OrbitActionEngine.Listener() {
            @Override public void onStep(AssistantReply.Action a, DeviceActionExecutor.Result r,
                                         int i, int t) {}
            @Override public void onFinished(boolean all, int done, int total) { finished.set(true); }
        });
        assertTrue(finished.get());
        assertTrue("two changes in one run cannot both be 'it'", RecentActionContext.isAmbiguous());
        assertTrue(RecentActionContext.clarification().contains("media volume"));

        // A later single change starts a fresh batch that is unambiguous again.
        OrbitActionEngine.execute(context, java.util.Collections.singletonList(
                new AssistantReply.Action("SET_VOLUME", new JSONObject().put("percent", 40), false)),
                null, null);
        assertEquals(RecentActionContext.Target.VOLUME, RecentActionContext.current());
    }

    // ---- the gate ------------------------------------------------------------------------------------

    @Test public void anUndoSentenceIsOfferedToTheModelOnlyWhileThereIsSomethingToUndo() {
        assertFalse(OrbitLocalActionRouter.looksLikeRecentFollowUp("go back to how it was before"));
        RecentActionContext.recordLevel(RecentActionContext.Target.BRIGHTNESS, 50);
        assertTrue(OrbitLocalActionRouter.looksLikeRecentFollowUp("go back to how it was before"));
        assertFalse("a question is never an undo",
                OrbitLocalActionRouter.looksLikeRecentFollowUp("what was it before?"));
        assertFalse("ordinary chat is left alone",
                OrbitLocalActionRouter.looksLikeRecentFollowUp("tell me a story about a dragon"));
    }

    @Test public void theDeterministicParserStillRunsFirst() {
        String pipeline = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/AssistantClient.java");
        int deterministic = pipeline.indexOf("LocalCommandRouter.tryHandle(context, prompt)");
        int model = pipeline.indexOf("OrbitLocalActionRouter.shouldTry(context, prompt)");
        assertTrue(deterministic > 0 && model > deterministic);
        assertNotNull("a plain command never wakes the model",
                LocalCommandRouter.tryHandle(context, "turn on do not disturb and set brightness to 30%"));
    }

    @Test public void theModelIsOnlyOfferedActionLikeRequests() {
        assertTrue(OrbitLocalActionRouter.looksActionable("open bluetooth settings for me"));
        assertTrue(OrbitLocalActionRouter.looksActionable("kill the torch and make it quieter"));
        assertFalse(OrbitLocalActionRouter.looksActionable("what is the capital of france"));
        assertFalse(OrbitLocalActionRouter.looksActionable("why is my screen so dim"));
    }

    @Test public void theSpokenLineIsOrbitsOwnForEveryStep() {
        OrbitLocalActionRouter.Decision d = OrbitLocalActionRouter.decide(
                "turn on dnd and dim the screen to 30", pair(DND_ON, DIM_30), APPS);
        assertEquals("Turning on Do Not Disturb. Setting brightness to 30%.", d.reply.text);
        assertEquals(2, d.reply.actions.size());
        assertFalse(d.reply.actions.get(0).requiresConfirmation);
    }
}
