package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import org.json.JSONObject;
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
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowPackageManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * What Orbit says and shows around a protected dial, as distinct from whether it is allowed.
 *
 * <p>{@link EmergencyDialGuardTest} owns the gate and is untouched by any of this. This file owns
 * the other half of the same promise, which a real device showed was missing: asked to dial 911,
 * Orbit answered "Opening the dialer for 911. If you can, stay on the line..." and only then put
 * the confirmation on screen. Nothing was dialled and nothing was permitted - and the user was
 * still told, in an emergency, that their phone had done something it had not done. A gate that
 * holds while the words in front of it are untrue is only half a safeguard.
 *
 * <p>So the assertions here are about states. A protected dial that is awaiting an answer may only
 * be described conditionally; past tense is available exactly once, after Android has the Intent;
 * and a cancelled action may not be described as anything at all. The other half of the file
 * checks that both surfaces ask the question with the same words, in the same order, with the safe
 * answer first.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class ProtectedDialPresentationTest {

    /** Phrases that assert the act is done or under way. None may appear before a confirmation. */
    private static final String[] EXECUTION_WORDING = {
            "opening the dialer", "opened the dialer", "dialer opened",
            "i'm opening", "i am opening", "i'm calling", "i'm dialing",
    };

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        DiagnosticStore.prefs(context).edit().clear().commit();
        EmergencyDialGuard.reset();
        installDialer();
        drainStartedActivities();
    }

    @After public void tearDown() {
        EmergencyDialGuard.reset();
    }

    // ---- the state a protected dial is actually in ------------------------------------------------

    /** A protected dial arrives awaiting an answer, and nothing has happened yet. */
    @Test public void aprotectedDialEntersTheAwaitingConfirmationState() {
        AssistantReply reply = new AssistantReply("Opening the dialer for 911.",
                Collections.singletonList(dialAction("911")));

        assertEquals(Collections.singletonList("911"),
                ActionNarration.awaitingProtectedNumbers(reply));
        assertTrue("the requirement is a property of the action, however it was built",
                reply.actions.get(0).requiresConfirmation);
        assertNull("nothing reaches Android while the question is unanswered",
                lastStartedActivity());
    }

    /** The exact sentence the phone produced, corrected. */
    @Test public void thereportedSentenceBecomesConditional() {
        AssistantReply corrected = ActionNarration.withTruthfulActionState(
                new AssistantReply("Opening the dialer for 911. If you can, stay on the line and "
                        + "tell them where you are.",
                        Collections.singletonList(dialAction("911"))));

        assertNoExecutionWording(corrected.text);
        assertTrue("the user is told what they can do, not what was done",
                corrected.text.startsWith("I can open the dialer for 911. Confirm below."));
        assertTrue("and the part of the answer that actually helps survives untouched",
                corrected.text.contains("stay on the line and tell them where you are."));
    }

    /** An answer that was only the false claim becomes the true one rather than becoming empty. */
    @Test public void aclaimOnItsOwnIsReplacedRatherThanDeleted() {
        AssistantReply corrected = ActionNarration.withTruthfulActionState(
                new AssistantReply("Opening the dialer for 911.",
                        Collections.singletonList(dialAction("911"))));

        assertEquals("I can open the dialer for 911. Confirm below.", corrected.text);
    }

    /** The same rule for the crisis line, with its own number. */
    @Test public void thecrisisLineIsCorrectedWithItsOwnNumber() {
        AssistantReply corrected = ActionNarration.withTruthfulActionState(
                new AssistantReply("I'm calling 988 for you now.",
                        Collections.singletonList(dialAction("988"))));

        assertNoExecutionWording(corrected.text);
        assertTrue(corrected.text.contains("988"));
        assertFalse("and never the other number", corrected.text.contains("911"));
    }

    /**
     * Advice is not a claim, and must survive completely.
     *
     * <p>This is the failure mode a careless fix would introduce. Orbit's whole emergency design
     * rests on it being free to say "call 911" as often and as plainly as it should; a correction
     * that quietly deleted safety advice would be worse than the wording it set out to fix.
     */
    @Test public void supportiveAdviceIsNeverRewritten() {
        String advice = "If you are in immediate danger, call 911 right now. "
                + "Calling 911 is the right thing to do when someone is hurt. "
                + "You can also reach the 988 line any time.";
        AssistantReply reply = new AssistantReply(advice,
                Collections.singletonList(dialAction("911")));

        assertEquals("advice is not an execution claim",
                advice, ActionNarration.withTruthfulActionState(reply).text);
    }

    /** A reply with no protected dial in it is not touched at all. */
    @Test public void anordinaryReplyIsLeftExactlyAsWritten() {
        AssistantReply ordinary = new AssistantReply("Opening the dialer for 555-0143.",
                Collections.singletonList(dialAction("5550143")));
        assertEquals("Opening the dialer for 555-0143.",
                ActionNarration.withTruthfulActionState(ordinary).text);

        AssistantReply noAction = new AssistantReply("I'm opening the app now.");
        assertEquals("I'm opening the app now.",
                ActionNarration.withTruthfulActionState(noAction).text);
    }

    /** Multi-line answers keep their shape; one bad line does not condemn the paragraph. */
    @Test public void onlyTheOffendingLineIsRemoved() {
        AssistantReply corrected = ActionNarration.withTruthfulActionState(
                new AssistantReply("Opening the dialer for 911.\n\nStay where you are.\n"
                        + "Keep the line clear.",
                        Collections.singletonList(dialAction("911"))));

        assertNoExecutionWording(corrected.text);
        assertTrue(corrected.text.contains("Stay where you are."));
        assertTrue(corrected.text.contains("Keep the line clear."));
    }

    // ---- what happens after the answer -------------------------------------------------------------

    /** Confirming opens exactly one dialer, and only then is past tense true. */
    @Test public void confirmingExecutesOnceAndOnlyThenSaysSo() {
        AssistantReply.Action action = dialAction("911");
        EmergencyDialGuard.Confirmation confirmation = EmergencyDialGuard.arm(action, "c-1");
        assertNotNull(confirmation);
        assertTrue(confirmation.confirm());

        DeviceActionExecutor.Result result = DeviceActionExecutor.executeDetailed(context, action);
        List<Intent> started = allStartedActivities();

        assertTrue(result.success);
        assertEquals("exactly one dialer, never two", 1, started.size());
        assertEquals(Intent.ACTION_DIAL, started.get(0).getAction());
        assertEquals("tel:911", String.valueOf(started.get(0).getData()));
        assertEquals("Opened the dialer for 911", result.message);
    }

    /** Cancelling says nothing that sounds like success, and starts nothing. */
    @Test public void cancellingProducesNoExecutionWording() {
        AssistantReply.Action action = dialAction("911");
        final List<DeviceActionExecutor.Result> results = new ArrayList<>();

        OrbitActionEngine.execute(context, Collections.singletonList(action),
                (a, allow, cancel) -> cancel.run(),
                new OrbitActionEngine.Listener() {
                    @Override public void onStep(AssistantReply.Action a,
                                                 DeviceActionExecutor.Result result,
                                                 int index, int total) {
                        results.add(result);
                    }

                    @Override public void onFinished(boolean all, int done, int total) {}
                });

        assertEquals(1, results.size());
        assertFalse("a refusal is not a success", results.get(0).success);
        assertEquals("Cancelled", results.get(0).message);
        assertNoExecutionWording(results.get(0).message);
        assertNull("and nothing at all reached Android", lastStartedActivity());
    }

    /** Blocked-before-confirmation wording explains the requirement without claiming an act. */
    @Test public void ablockedDialExplainsRatherThanClaims() {
        DeviceActionExecutor.Result result =
                DeviceActionExecutor.executeDetailed(context, dialAction("911"));

        assertFalse(result.success);
        assertNoExecutionWording(result.message);
        assertNull(lastStartedActivity());
    }

    // ---- one confirmation, two surfaces --------------------------------------------------------------

    /**
     * The full app and the Side-button overlay ask the identical question.
     *
     * <p>They are built from the same component with the same wording source, so this is checking
     * that neither surface has been allowed to re-word, re-order, or re-weight the most important
     * question Orbit asks. Only the sizing is permitted to differ.
     */
    @Test public void bothSurfacesAskTheSameQuestionInTheSameOrder() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        try {
            Activity activity = controller.get();
            View full = ProtectedDialConfirmationView.build(activity, "911", false, null, null);
            View overlay = ProtectedDialConfirmationView.build(activity, "911", true, null, null);

            assertEquals("Call 911?", title(full).getText().toString());
            assertEquals(title(full).getText().toString(), title(overlay).getText().toString());
            assertEquals("Open the phone dialer for 911?", body(full).getText().toString());
            assertEquals(body(full).getText().toString(), body(overlay).getText().toString());

            List<Button> fullActions = buttons(full);
            List<Button> overlayActions = buttons(overlay);
            assertEquals("two answers and no more", 2, fullActions.size());
            assertEquals(2, overlayActions.size());
            assertEquals("Cancel is first", "Cancel", fullActions.get(0).getText().toString());
            assertEquals("Open dialer", fullActions.get(1).getText().toString());
            assertEquals(fullActions.get(0).getText().toString(),
                    overlayActions.get(0).getText().toString());
            assertEquals(fullActions.get(1).getText().toString(),
                    overlayActions.get(1).getText().toString());

            assertEquals("Cancel, close this without opening the dialer",
                    String.valueOf(fullActions.get(0).getContentDescription()));
            assertEquals("Open dialer for 911",
                    String.valueOf(fullActions.get(1).getContentDescription()));
            assertEquals(String.valueOf(fullActions.get(0).getContentDescription()),
                    String.valueOf(overlayActions.get(0).getContentDescription()));
            assertEquals(String.valueOf(fullActions.get(1).getContentDescription()),
                    String.valueOf(overlayActions.get(1).getContentDescription()));
        } finally {
            controller.pause().stop().destroy();
        }
    }

    /** The question a screen reader hears the moment the card appears. */
    @Test public void thecardAnnouncesTheWholeQuestion() {
        assertEquals("Call 911? Open the phone dialer for 911?",
                ProtectedDialConfirmationView.announcement("911"));
        assertNoExecutionWording(ProtectedDialConfirmationView.announcement("911"));
    }

    /** Neither answer is pre-selected, so nothing can be confirmed by a stray press. */
    @Test public void noAnswerIsPreFocusedOrDefaulted() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        try {
            View card = ProtectedDialConfirmationView.build(controller.get(), "988", false,
                    null, null);
            for (Button button : buttons(card)) {
                assertFalse("nothing here may be armed by an accidental press",
                        button.isFocused());
            }
        } finally {
            controller.pause().stop().destroy();
        }
    }

    /** Tapping is what issues a grant, and the card is only what carries the tap. */
    @Test public void thecardsButtonsAreWhatDrivesTheGrant() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        try {
            final int[] answered = {0, 0};
            View card = ProtectedDialConfirmationView.build(controller.get(), "911", false,
                    () -> answered[0]++, () -> answered[1]++);
            List<Button> actions = buttons(card);

            actions.get(0).performClick();
            assertEquals("Cancel answers cancel", 1, answered[0]);
            assertEquals(0, answered[1]);

            actions.get(1).performClick();
            assertEquals(1, answered[1]);
        } finally {
            controller.pause().stop().destroy();
        }
    }

    // ---- the gate itself is untouched -----------------------------------------------------------------

    /** Restating the invariants the visual change was not allowed to move. */
    @Test public void theGuardsClassificationIsUnchanged() {
        assertEquals(EmergencyDialGuard.CATEGORY_EMERGENCY, EmergencyDialGuard.categoryFor("911"));
        assertEquals(EmergencyDialGuard.CATEGORY_CRISIS, EmergencyDialGuard.categoryFor("988"));
        assertEquals(EmergencyDialGuard.CATEGORY_EMERGENCY, EmergencyDialGuard.categoryFor("9-1-1"));
        assertFalse("1911 contains 911 and is not 911", EmergencyDialGuard.isProtected("1911"));
        assertFalse(EmergencyDialGuard.isProtected("5550143"));
        assertTrue(EmergencyDialGuard.alwaysConfirms("DIAL", numberParams("911")));
    }

    /** Correcting the words never issues, widens, or preserves a grant. */
    @Test public void narrationCannotAffectTheGrant() {
        AssistantReply reply = new AssistantReply("Opening the dialer for 911.",
                Collections.singletonList(dialAction("911")));
        ActionNarration.withTruthfulActionState(reply);

        assertFalse("wording is not permission", EmergencyDialGuard.hasArmedConfirmation());
        assertFalse(EmergencyDialGuard.consumeGrant("911"));
        DeviceActionExecutor.executeDetailed(context, reply.actions.get(0));
        assertNull(lastStartedActivity());
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private static void assertNoExecutionWording(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.US);
        for (String claim : EXECUTION_WORDING) {
            assertFalse("must not claim the act before it happened: \"" + claim + "\" in " + text,
                    lower.contains(claim));
        }
    }

    private static TextView title(View card) {
        return (TextView) ((ViewGroup) card).getChildAt(0);
    }

    private static TextView body(View card) {
        return (TextView) ((ViewGroup) card).getChildAt(1);
    }

    private static List<Button> buttons(View card) {
        ViewGroup actions = (ViewGroup) ((ViewGroup) card).getChildAt(2);
        List<Button> out = new ArrayList<>();
        for (int i = 0; i < actions.getChildCount(); i++) {
            View child = actions.getChildAt(i);
            if (child instanceof Button) out.add((Button) child);
        }
        return out;
    }

    private static JSONObject numberParams(String number) {
        JSONObject params = new JSONObject();
        try { params.put("number", number); } catch (Exception ignored) {}
        return params;
    }

    private static AssistantReply.Action dialAction(String number) {
        return new AssistantReply.Action("DIAL", numberParams(number), false);
    }

    private void installDialer() {
        ComponentName dialer = new ComponentName("com.orbit.test.dialer",
                "com.orbit.test.dialer.DialActivity");
        ShadowPackageManager packages = Shadows.shadowOf(context.getPackageManager());
        packages.addActivityIfNotPresent(dialer);
        IntentFilter filter = new IntentFilter(Intent.ACTION_DIAL);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        filter.addDataScheme("tel");
        packages.addIntentFilterForActivity(dialer, filter);
    }

    private ShadowApplication shadowApp() {
        return Shadows.shadowOf((Application) RuntimeEnvironment.getApplication());
    }

    private void drainStartedActivities() {
        while (shadowApp().getNextStartedActivity() != null) { /* drained */ }
    }

    private List<Intent> allStartedActivities() {
        List<Intent> started = new ArrayList<>();
        Intent next;
        while ((next = shadowApp().getNextStartedActivity()) != null) started.add(next);
        return started;
    }

    private Intent lastStartedActivity() {
        List<Intent> started = allStartedActivities();
        return started.isEmpty() ? null : started.get(started.size() - 1);
    }
}
