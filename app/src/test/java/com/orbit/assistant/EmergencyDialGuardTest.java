package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowPackageManager;

import java.util.ArrayList;
import java.util.List;

/**
 * That Orbit cannot dial an emergency or crisis number on its own.
 *
 * <p>This exists because of something a real phone did. A model answered a safety question with
 * sensible advice and also returned a {@code DIAL} action for 911 with {@code requiresConfirmation}
 * false, and Orbit opened the dialer with 911 in it, unprompted. Nothing malfunctioned — every
 * layer did exactly what it was designed to do — and that is precisely why a test is the right
 * place to pin the rule down: the failure was in what the design permitted, not in an
 * implementation someone got wrong.
 *
 * <p>The assertion that matters most is negative and is repeated in several shapes: <em>no Intent
 * of any kind reaches Android before a person taps Confirm</em>. Everything else here — the
 * normalization, the one-shot grant, the stale dialog — exists to make sure that one holds under
 * every way it could be got around.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class EmergencyDialGuardTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        DiagnosticStore.prefs(context).edit().clear().commit();
        EmergencyDialGuard.reset();
        // A phone with no dialer installed refuses every dial for a reason that has nothing to do
        // with this gate, which would make the positive cases below pass for the wrong reason.
        installDialer();
        drainStartedActivities();
    }

    /** Gives the test device something that answers ACTION_DIAL, as a real phone does. */
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

    @After public void tearDown() {
        EmergencyDialGuard.reset();
    }

    // ---- what counts as a protected number ------------------------------------------------------

    @Test public void thePlainNumbersAreProtected() {
        assertEquals(EmergencyDialGuard.CATEGORY_EMERGENCY, EmergencyDialGuard.categoryFor("911"));
        assertEquals(EmergencyDialGuard.CATEGORY_CRISIS, EmergencyDialGuard.categoryFor("988"));
    }

    /** People and models write the same number several ways, and all of them are that number. */
    @Test public void ordinaryFormattingIsNormalisedRatherThanMissed() {
        for (String written : new String[]{"911", "9-1-1", "9 1 1", "(911)", "9.1.1", "tel:911",
                " 911 ", "tel:9-1-1"}) {
            assertEquals(written + " must be recognised as 911",
                    EmergencyDialGuard.CATEGORY_EMERGENCY, EmergencyDialGuard.categoryFor(written));
        }
        assertEquals(EmergencyDialGuard.CATEGORY_CRISIS, EmergencyDialGuard.categoryFor("9-8-8"));
    }

    /**
     * The other half, and it is not a nicety.
     *
     * <p>A gate built on "contains 911" would fire on ordinary numbers, and a confirmation that
     * appears when it should not is a confirmation people learn to tap straight through — which
     * would quietly destroy the protection this whole file exists to provide.
     */
    @Test public void aNumberThatMerelyContainsAProtectedOneIsNotProtected() {
        for (String ordinary : new String[]{"1911", "9110", "5559111", "+15559110988", "988123",
                "1988", "119"}) {
            assertFalse(ordinary + " must not be treated as an emergency number",
                    EmergencyDialGuard.isProtected(ordinary));
        }
    }

    @Test public void anOrdinaryNumberIsNotProtected() {
        assertFalse(EmergencyDialGuard.isProtected("5551234567"));
        assertFalse(EmergencyDialGuard.isProtected("+442071234567"));
        assertFalse(EmergencyDialGuard.isProtected(""));
        assertFalse(EmergencyDialGuard.isProtected(null));
    }

    /** Orbit implements two protected numbers and does not pretend to a worldwide database. */
    @Test public void theProtectedSetIsExactlyWhatIsImplemented() {
        assertEquals(2, EmergencyDialGuard.protectedNumbers().size());
        assertTrue(EmergencyDialGuard.protectedNumbers().containsKey("911"));
        assertTrue(EmergencyDialGuard.protectedNumbers().containsKey("988"));
    }

    // ---- the action itself carries the requirement -----------------------------------------------

    /** A model that omits requiresConfirmation cannot produce an unconfirmed protected dial. */
    @Test public void aProtectedDialAlwaysRequiresConfirmationHoweverItWasBuilt() {
        assertTrue(dialAction("911").requiresConfirmation);
        assertTrue(dialAction("988").requiresConfirmation);
        assertTrue(dialAction("9-1-1").requiresConfirmation);
    }

    /** Ordinary dialing is untouched: Beta 3 did not redesign the phone. */
    @Test public void anOrdinaryDialKeepsItsExistingBehaviour() {
        assertFalse(dialAction("5551234567").requiresConfirmation);
        assertFalse(EmergencyDialGuard.isProtectedDialAction(dialAction("5551234567")));
    }

    /** The same envelope a provider actually returns, with the flag absent. */
    @Test public void aProviderReplyOmittingTheFlagStillConfirms() throws Exception {
        AssistantReply reply = AssistantReply.fromJson(new JSONObject(
                "{\"text\":\"Call 911 if you are in immediate danger.\","
                        + "\"actions\":[{\"type\":\"DIAL\",\"params\":{\"number\":\"911\"}}]}"));
        assertEquals(1, reply.actions.size());
        assertTrue("a provider must not be able to produce an unconfirmed 911 dial",
                reply.actions.get(0).requiresConfirmation);
    }

    // ---- the gate: no Intent before a person says so ----------------------------------------------

    /** The central negative assertion, for the emergency number. */
    @Test public void aProviderDialFor911StartsNoIntentBeforeConfirmation() {
        DeviceActionExecutor.Result result =
                DeviceActionExecutor.executeDetailed(context, dialAction("911"));
        assertFalse(result.success);
        assertNull("no dialer Intent may reach Android before the user confirms",
                lastStartedActivity());
    }

    /** And for the crisis line, which is protected for exactly the same reason. */
    @Test public void aProviderDialFor988StartsNoIntentBeforeConfirmation() {
        DeviceActionExecutor.Result result =
                DeviceActionExecutor.executeDetailed(context, dialAction("988"));
        assertFalse(result.success);
        assertNull(lastStartedActivity());
    }

    /** A formatted protected number is caught by the same gate. */
    @Test public void aFormattedProtectedNumberIsGatedToo() {
        DeviceActionExecutor.executeDetailed(context, dialAction("9-1-1"));
        assertNull(lastStartedActivity());
    }

    /**
     * The user typing "call 911" reaches the same gate.
     *
     * <p>An explicit instruction may make the confirmation appear straight away; it does not
     * replace it. Orbit still asks, because the thing being confirmed is the phone acting, not the
     * user's intent being doubted.
     */
    @Test public void anExplicitUserCommandStillHasToBeConfirmed() {
        AssistantReply.Action action = dialAction("911");
        assertTrue(action.requiresConfirmation);
        DeviceActionExecutor.executeDetailed(context, action);
        assertNull(lastStartedActivity());
    }

    /** Cancelling is a real answer, and it does nothing at all outside Orbit. */
    @Test public void cancellingOpensNoDialer() {
        AssistantReply.Action action = dialAction("911");
        EmergencyDialGuard.Confirmation confirmation = EmergencyDialGuard.arm(action, "c-1");
        assertNotNull(confirmation);
        confirmation.cancel();

        DeviceActionExecutor.executeDetailed(context, action);
        assertNull("a cancelled confirmation must never open a dialer", lastStartedActivity());
    }

    /** Confirming opens exactly one dialer, and it is ACTION_DIAL. */
    @Test public void confirmingOpensExactlyOneDialIntent() {
        AssistantReply.Action action = dialAction("911");
        EmergencyDialGuard.Confirmation confirmation = EmergencyDialGuard.arm(action, "c-1");
        assertTrue(confirmation.confirm());

        DeviceActionExecutor.Result result = DeviceActionExecutor.executeDetailed(context, action);
        assertTrue(result.success);
        List<Intent> started = allStartedActivities();
        assertEquals("exactly one dialer, never two", 1, started.size());
        assertEquals(Intent.ACTION_DIAL, started.get(0).getAction());
        assertEquals("tel:911", String.valueOf(started.get(0).getData()));
    }

    /**
     * Orbit populates the dialer and stops there.
     *
     * <p>After the confirmation the decision to actually place the call still belongs to the
     * person and to Android's own dialer. {@code ACTION_CALL} would take that decision away, and
     * Orbit has never used it.
     */
    @Test public void orbitNeverUsesActionCall() {
        AssistantReply.Action action = dialAction("911");
        EmergencyDialGuard.arm(action, "c-1").confirm();
        DeviceActionExecutor.executeDetailed(context, action);

        for (Intent intent : allStartedActivities()) {
            assertFalse("Orbit must never place a call itself",
                    Intent.ACTION_CALL.equals(intent.getAction()));
        }
    }

    /** The grant is spent as it is read, so a duplicated callback dials once. */
    @Test public void aDuplicatedConfirmationCannotDialTwice() {
        AssistantReply.Action action = dialAction("911");
        EmergencyDialGuard.Confirmation confirmation = EmergencyDialGuard.arm(action, "c-1");
        assertTrue(confirmation.confirm());
        assertFalse("a second confirm on one dialog issues nothing", confirmation.confirm());

        DeviceActionExecutor.executeDetailed(context, action);
        DeviceActionExecutor.executeDetailed(context, action);
        assertEquals("one confirmation opens one dialer", 1, allStartedActivities().size());
    }

    /** A dialog left over from an earlier turn cannot answer for the current one. */
    @Test public void aStaleConfirmationCannotExecute() {
        AssistantReply.Action old = dialAction("911");
        EmergencyDialGuard.Confirmation stale = EmergencyDialGuard.arm(old, "c-old");

        // A newer turn produces a newer question, which supersedes the one still on screen.
        AssistantReply.Action fresh = dialAction("988");
        EmergencyDialGuard.arm(fresh, "c-new");

        assertFalse("a superseded dialog issues no grant", stale.confirm());
        DeviceActionExecutor.executeDetailed(context, old);
        assertNull(lastStartedActivity());
    }

    /** A grant for one protected number cannot be spent on the other. */
    @Test public void aGrantCannotBeSpentOnADifferentNumber() {
        EmergencyDialGuard.arm(dialAction("988"), "c-1").confirm();
        DeviceActionExecutor.executeDetailed(context, dialAction("911"));
        assertNull("988's confirmation must not open 911", lastStartedActivity());
    }

    /** Process recreation leaves nothing behind: the default state is always "not confirmed". */
    @Test public void nothingSurvivesAResetToAutoConfirmLater() {
        AssistantReply.Action action = dialAction("911");
        EmergencyDialGuard.arm(action, "c-1").confirm();
        EmergencyDialGuard.reset();

        DeviceActionExecutor.executeDetailed(context, action);
        assertNull("a recreated process must never carry a grant", lastStartedActivity());
    }

    /** An ordinary number is untouched by all of this and dials as it always did. */
    @Test public void anOrdinaryNumberStillDialsWithoutConfirmation() {
        DeviceActionExecutor.Result result =
                DeviceActionExecutor.executeDetailed(context, dialAction("5551234567"));
        assertTrue(result.success);
        Intent started = lastStartedActivity();
        assertNotNull(started);
        assertEquals(Intent.ACTION_DIAL, started.getAction());
    }

    // ---- nothing else can reach a protected dial --------------------------------------------------

    /**
     * Orbit Local cannot emit a dial at all, and that is asserted rather than assumed.
     *
     * <p>The on-device action model validates against a fixed allowlist, and nothing that places a
     * call is on it. Even if it were, the executor gate above would still stop it — but the
     * cheapest place to stop it is before it is ever a valid action.
     */
    @Test public void theLocalActionAllowlistCannotProduceADial() {
        assertFalse(LocalActionSchema.ALLOWED_ACTIONS.contains("DIAL"));
        assertFalse(LocalActionSchema.ALLOWED_ACTIONS.contains("DIAL_CONTACT"));
    }

    /**
     * A headless run — a widget, a tile, an automatic Routine trigger — cannot dial either.
     *
     * <p>Those paths reach the action engine with no confirmation handler, because there is nobody
     * present to ask. That is exactly the case where a UI-level rule would have no effect, so it is
     * the case worth proving: the executor refuses on its own, the step is reported as
     * unsuccessful, and Android is never asked for anything.
     */
    @Test public void aHeadlessRunWithNoConfirmationHandlerCannotDial() {
        assertTrue("the requirement rides on the action itself",
                dialAction("911").requiresConfirmation);

        final boolean[] succeeded = {true};
        OrbitActionEngine.execute(context,
                java.util.Collections.singletonList(dialAction("911")), null,
                new OrbitActionEngine.Listener() {
                    @Override public void onStep(AssistantReply.Action action,
                                                 DeviceActionExecutor.Result result,
                                                 int index, int total) {
                        succeeded[0] = result != null && result.success;
                    }
                    @Override public void onFinished(boolean all, int done, int total) {}
                });

        assertFalse("a headless run must not succeed at dialling 911", succeeded[0]);
        assertNull("and must start no Intent at all", lastStartedActivity());
    }

    /** The gate reports itself to Diagnostics in categories, and never as a phone number. */
    @Test public void diagnosticsRecordsTheOutcomeWithoutTheNumber() {
        DeviceActionExecutor.executeDetailed(context, dialAction("911"));
        String outcome = DiagnosticStore.lastProtectedDialOutcome(context);
        assertEquals("blocked", outcome);
        for (String value : DiagnosticStore.prefs(context).getAll().values().stream()
                .filter(v -> v instanceof String).map(String::valueOf)
                .toArray(String[]::new)) {
            assertFalse("Diagnostics must not store the dialled number", value.contains("911"));
            assertFalse(value.contains("988"));
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static AssistantReply.Action dialAction(String number) {
        JSONObject params = new JSONObject();
        try { params.put("number", number); } catch (Exception ignored) {}
        return new AssistantReply.Action("DIAL", params, false);
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
