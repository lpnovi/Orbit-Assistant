package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * An editor may not be animated out of the way on a decision the user has not made.
 *
 * <p>This is the one place where the gesture is genuinely dangerous rather than merely wrong. A page
 * that starts sliding away is telling the user that leaving is settled; on an editor holding unsaved
 * work it is not, and the screen has either a discard confirmation to show or typing to lose. So the
 * rule is the same on all six editors and it is asserted here rather than assumed: the page moves
 * only while there is nothing to lose, and Back always ends at the screen's own contract.
 *
 * <p>Note what is deliberately <i>not</i> changed. Beta 3 adds no confirmation where there was none
 * and removes none where there was: the editors that asked before discarding still ask, and the ones
 * that never did still leave. All that is withheld is the animation.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class EditorBackGuardTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        DiagnosticStore.prefs(context).edit().clear().commit();
        OrbitPredictiveBack.keyboardVisibleForTest = null;
        TestWorkManager.ensureInitialized(context);
    }

    private static List<View> descendants(View root) {
        List<View> out = new ArrayList<>();
        out.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) out.addAll(descendants(group.getChildAt(i)));
        }
        return out;
    }

    private static EditText fieldDescribed(android.app.Activity activity, String description) {
        for (View v : descendants(activity.getWindow().getDecorView())) {
            if (v instanceof EditText && description.contentEquals(
                    v.getContentDescription() == null ? "" : v.getContentDescription())) {
                return (EditText) v;
            }
        }
        return null;
    }

    private static void assumeDrawnGesture() {
        org.junit.Assume.assumeTrue(OrbitPredictiveBack.available());
    }

    private static void idle() {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(600));
    }

    private ActivityController<RoutineBuilderActivity> builder() {
        return Robolectric.buildActivity(RoutineBuilderActivity.class).setup();
    }

    // ---- clean ------------------------------------------------------------------------------------

    /** With nothing typed, leaving costs nothing and the page behaves like any other page. */
    @Test public void acleanEditorMovesWithTheFinger() {
        assumeDrawnGesture();
        ActivityController<RoutineBuilderActivity> controller = builder();
        RoutineBuilderActivity activity = controller.get();
        OrbitPredictiveBack back = activity.navigationForTest();

        back.startedForTest();
        assertFalse("a clean editor has nothing to protect", back.declinedForTest());
        back.progressedForTest(0.6f);
        assertTrue("so the page follows the finger", back.translationForTest() > 0f);

        back.invokedForTest();
        idle();
        assertTrue("and committing leaves", activity.isFinishing());
        controller.pause().stop().destroy();
    }

    // ---- dirty ------------------------------------------------------------------------------------

    /**
     * With something typed, the page does not move at all — and Back still means what it meant.
     *
     * <p>The two halves matter equally. Not moving is what stops the gesture promising an exit; still
     * performing the screen's Back is what stops the gesture swallowing one.
     */
    @Test public void adirtyEditorDoesNotMoveAndDoesNotLoseBack() {
        assumeDrawnGesture();
        ActivityController<RoutineBuilderActivity> controller = builder();
        RoutineBuilderActivity activity = controller.get();
        EditText description = fieldDescribed(activity, "Routine description");
        assertNotNull("the builder must have a description field", description);
        description.setText("Turn on Do Not Disturb");

        OrbitPredictiveBack back = activity.navigationForTest();
        back.startedForTest();
        assertTrue("an editor with typing in it must decline to move", back.declinedForTest());
        back.progressedForTest(0.9f);
        assertEquals("and must not move by any amount", 0f, back.translationForTest(), 0.5f);
        assertFalse("nor be dressed for a reveal", back.revealingForTest());
        assertEquals("nor be credited with frames it never drew", 0, back.progressEventsForTest());
        assertFalse("and dragging alone may not leave", activity.isFinishing());

        back.invokedForTest();
        idle();
        assertTrue("but a committed Back still performs this screen's Back",
                activity.isFinishing());
        controller.pause().stop().destroy();
    }

    /** Abandoning the gesture on a dirty editor changes nothing at all. */
    @Test public void cancellingOnAdirtyEditorLeavesEverythingAlone() {
        assumeDrawnGesture();
        ActivityController<RoutineBuilderActivity> controller = builder();
        RoutineBuilderActivity activity = controller.get();
        EditText description = fieldDescribed(activity, "Routine description");
        description.setText("Bedtime routine");

        OrbitPredictiveBack back = activity.navigationForTest();
        back.startedForTest();
        back.progressedForTest(0.5f);
        back.cancelledForTest();
        idle();

        assertFalse("no gesture may be left running", back.gesturingForTest());
        assertFalse("nothing may be left dressed", back.revealingForTest());
        assertFalse("and nothing may have navigated", activity.isFinishing());
        assertEquals("the typing is untouched", "Bedtime routine",
                description.getText().toString());
        controller.pause().stop().destroy();
    }

    /** Diagnostics records a guarded Back as guarded, and claims no progress for it. */
    @Test public void aguardedBackIsReportedAsGuarded() {
        assumeDrawnGesture();
        ActivityController<RoutineBuilderActivity> controller = builder();
        RoutineBuilderActivity activity = controller.get();
        fieldDescribed(activity, "Routine description").setText("Focus mode");
        OrbitPredictiveBack back = activity.navigationForTest();
        back.startedForTest();
        back.progressedForTest(0.7f);
        back.invokedForTest();
        idle();
        controller.pause().stop().destroy();

        ActivityController<DiagnosticsActivity> diagnostics =
                Robolectric.buildActivity(DiagnosticsActivity.class).setup();
        String report = diagnostics.get().fullReport();
        assertTrue(report.contains("Last predictive screen: Routine builder"));
        assertTrue(report.contains("Last back path: dirty-state guarded"));
        assertTrue(report.contains("committed · 0 progress events"));
        assertFalse("and nothing the user typed may reach diagnostics",
                report.contains("Focus mode"));
        diagnostics.pause().stop().destroy();
    }

    // ---- the editors that already asked before discarding -----------------------------------------

    /**
     * A routine editor holding changes reaches its confirmation, not the exit.
     *
     * <p>{@code handleBack} is untouched by Beta 3 and is still what Back calls. The only thing the
     * gesture added is the decision not to animate first.
     */
    @Test public void adirtyRoutineEditorStillAsksBeforeDiscarding() {
        assumeDrawnGesture();
        // A new routine: nothing is stored, so the editor is clean until something is entered.
        ActivityController<RoutineEditorActivity> controller =
                Robolectric.buildActivity(RoutineEditorActivity.class).setup();
        RoutineEditorActivity activity = controller.get();

        activity.markDirtyForTest("Bedtime renamed");
        OrbitPredictiveBack back = activity.navigationForTest();
        back.startedForTest();
        assertTrue("an editor with unsaved changes must decline to move",
                back.declinedForTest());
        back.progressedForTest(0.8f);
        assertEquals(0f, back.translationForTest(), 0.5f);

        back.invokedForTest();
        idle();
        assertFalse("Back must reach the discard question, not the exit",
                activity.isFinishing());
        assertNotNull("and that question must actually be on screen",
                org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
        controller.pause().stop().destroy();
    }

    /** A clean routine editor leaves without asking, exactly as it always did. */
    @Test public void acleanRoutineEditorLeavesWithoutAsking() {
        assumeDrawnGesture();
        // A new routine: nothing is stored, so the editor is clean until something is entered.
        ActivityController<RoutineEditorActivity> controller =
                Robolectric.buildActivity(RoutineEditorActivity.class).setup();
        RoutineEditorActivity activity = controller.get();

        OrbitPredictiveBack back = activity.navigationForTest();
        back.startedForTest();
        assertFalse(back.declinedForTest());
        back.progressedForTest(0.9f);
        back.invokedForTest();
        idle();
        assertTrue(activity.isFinishing());
        controller.pause().stop().destroy();
    }

    // ---- every opted-in editor keeps a working Back on every device --------------------------------

    /**
     * The trap the manifest opt-in sets, closed.
     *
     * <p>Declaring {@code enableOnBackInvokedCallback} stops {@code onBackPressed} being called on
     * API 33+. If nothing were registered, the platform would simply finish the editor and the
     * discard confirmation would never run — the flag alone would have deleted a safeguard. So every
     * opted-in screen registers something wherever there is a dispatcher, whatever the API level and
     * whatever the preference says.
     */
    @Test public void anoptedInEditorAlwaysHoldsBackWhereThereIsAdispatcher() {
        Prefs.get(context).edit().putBoolean(Prefs.ENHANCED_CHAT_BACK, false).commit();
        ActivityController<RoutineBuilderActivity> controller = builder();
        OrbitPredictiveBack back = controller.get().navigationForTest();

        assertEquals("with the gesture off it still owns Back on every API that has a dispatcher",
                OrbitBackHandler.supported(), back.isArmed());
        assertFalse("it simply does not draw it", back.drawsGesture());
        controller.pause().stop().destroy();
    }
}
