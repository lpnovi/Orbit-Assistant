package com.orbit.assistant;

import static com.orbit.assistant.RoutineBranchTest.chain;
import static com.orbit.assistant.RoutineBranchTest.condition;
import static com.orbit.assistant.RoutineBranchTest.timer;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

/**
 * Removing a whole IF branch from the Routine editor.
 *
 * <p>v0.7.5.1 built and styled the confirmation dialog and then never showed it, because
 * {@code UiKit.styleOrbitDialog} only prepares the window and registers an on-show listener — the
 * caller has to call {@code show()}. The dialog was created and immediately discarded, so tapping
 * Remove branch on a branch that held any actions did nothing whatsoever, which is exactly what the
 * device reported. Only the empty-branch path worked, because it removes without asking.
 *
 * <p>These drive {@code RoutineEditorActivity.confirmRemoveBranch} itself rather than a copy of its
 * logic, so the assertion that the dialog reaches the screen is the thing that regressed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class RoutineBranchRemovalTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("orbit_routines", Context.MODE_PRIVATE).edit().clear().commit();
        ShadowDialog.reset();
    }

    /** The editor, opened on a saved routine, exactly as the Routines screen opens it. */
    private RoutineEditorActivity editorFor(String name, List<AssistantReply.Action> actions) {
        RoutineStore.Routine routine = RoutineStore.create(name, actions);
        assertTrue("the routine has to be storable", RoutineStore.upsert(context, routine));
        Intent intent = new Intent(context, RoutineEditorActivity.class)
                .putExtra(RoutineEditorActivity.EXTRA_ROUTINE_ID, routine.id);
        return Robolectric.buildActivity(RoutineEditorActivity.class, intent).setup().get();
    }

    private static List<Integer> seconds(List<AssistantReply.Action> actions) {
        List<Integer> out = new ArrayList<>();
        for (AssistantReply.Action action : actions) {
            out.add(RoutineConditionEvaluator.isCondition(action)
                    ? -1 : action.params.optInt("seconds", -2));
        }
        return out;
    }

    private static List<Integer> list(int... values) {
        List<Integer> out = new ArrayList<>();
        for (int value : values) out.add(value);
        return out;
    }

    private AlertDialog shownDialog() {
        return ShadowAlertDialog.getLatestAlertDialog();
    }

    /**
     * Presses one of the dialog's buttons.
     *
     * <p>Android's AlertController does not call the button listener directly; it posts a message
     * to a handler, so the looper has to run before anything the user chose has actually happened.
     */
    private void tap(int button) {
        shownDialog().getButton(button).performClick();
        ShadowLooper.idleMainLooper();
    }

    private void dismissDialog() {
        shownDialog().dismiss();
        ShadowLooper.idleMainLooper();
    }

    // ---- the reported bug ----------------------------------------------------------------------

    /**
     * The regression. On v0.7.5.1 no dialog ever appeared and the branch survived, so both halves
     * of this fail; it can only pass once the confirmation is actually shown.
     */
    @Test public void removingAPopulatedBranchShowsAConfirmationAndChangesNothingYet() {
        RoutineEditorActivity editor = editorFor("Home or away",
                chain(condition(1, 1), timer(11), timer(21), timer(90)));

        editor.confirmRemoveBranch(0);

        AlertDialog dialog = shownDialog();
        assertNotNull("Remove branch must present a confirmation", dialog);
        assertTrue("the confirmation has to actually be on screen", dialog.isShowing());
        assertEquals("nothing may be removed before the user confirms",
                list(-1, 11, 21, 90), seconds(editor.editingSteps()));
    }

    @Test public void theConfirmationSaysWhatWillBeLost() {
        RoutineEditorActivity editor = editorFor("Wording",
                chain(condition(1, 1), timer(11), timer(21)));
        editor.confirmRemoveBranch(0);

        ShadowAlertDialog shadow = Shadows.shadowOf(shownDialog());
        assertEquals("Remove branch?", shadow.getTitle().toString());
        String message = shadow.getMessage().toString();
        assertTrue(message, message.contains("IF condition"));
        assertTrue(message, message.contains("THEN"));
        assertTrue(message, message.contains("OTHERWISE"));
        assertEquals("Cancel",
                shownDialog().getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
        assertEquals("Remove",
                shownDialog().getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
    }

    // ---- cancelling -----------------------------------------------------------------------------

    @Test public void cancellingLeavesTheRoutineExactlyAsItWas() {
        RoutineEditorActivity editor = editorFor("Cancelled",
                chain(condition(2, 1), timer(11), timer(12), timer(21), timer(90)));
        String before = editor.editingSteps().get(0).params.toString();

        editor.confirmRemoveBranch(0);
        tap(AlertDialog.BUTTON_NEGATIVE);

        assertEquals(list(-1, 11, 12, 21, 90), seconds(editor.editingSteps()));
        assertEquals("the condition itself must be untouched too",
                before, editor.editingSteps().get(0).params.toString());
        assertTrue(RoutineBranch.structureValid(editor.editingSteps()));
    }

    @Test public void dismissingWithoutChoosingRemovesNothing() {
        RoutineEditorActivity editor = editorFor("Dismissed",
                chain(condition(1, 1), timer(11), timer(21)));
        editor.confirmRemoveBranch(0);
        dismissDialog();
        assertEquals(list(-1, 11, 21), seconds(editor.editingSteps()));
    }

    // ---- confirming ------------------------------------------------------------------------------

    @Test public void confirmingRemovesTheConditionAndBothPaths() {
        RoutineEditorActivity editor = editorFor("Confirmed",
                chain(condition(2, 2), timer(11), timer(12), timer(21), timer(22), timer(90)));

        editor.confirmRemoveBranch(0);
        tap(AlertDialog.BUTTON_POSITIVE);

        assertEquals("only the steps outside the branch survive",
                list(90), seconds(editor.editingSteps()));
        assertTrue(RoutineBranch.structureValid(editor.editingSteps()));
    }

    @Test public void stepsAroundTheBranchCloseTheGapCleanly() {
        RoutineEditorActivity editor = editorFor("Surrounded",
                chain(timer(1), condition(1, 1), timer(11), timer(21), timer(90), timer(91)));

        editor.confirmRemoveBranch(1);
        tap(AlertDialog.BUTTON_POSITIVE);

        assertEquals(list(1, 90, 91), seconds(editor.editingSteps()));
        assertTrue("nothing may be left claiming a branch",
                RoutineBranch.units(editor.editingSteps()).stream().noneMatch(u -> u.branch));
    }

    @Test public void aThenOnlyBranchIsRemovedWithConfirmation() {
        RoutineEditorActivity editor = editorFor("Then only",
                chain(condition(2, 0), timer(11), timer(12), timer(90)));

        editor.confirmRemoveBranch(0);
        assertNotNull("a THEN-only branch still holds actions worth confirming", shownDialog());
        tap(AlertDialog.BUTTON_POSITIVE);

        assertEquals(list(90), seconds(editor.editingSteps()));
    }

    @Test public void removingTheOnlyBranchLeavesAnEmptyRoutine() {
        RoutineEditorActivity editor = editorFor("Only branch",
                chain(condition(1, 1), timer(11), timer(21)));

        editor.confirmRemoveBranch(0);
        tap(AlertDialog.BUTTON_POSITIVE);

        assertTrue(editor.editingSteps().isEmpty());
    }

    @Test public void severalActionsOnEachPathAllGo() {
        RoutineEditorActivity editor = editorFor("Full paths",
                chain(condition(3, 3), timer(11), timer(12), timer(13),
                        timer(21), timer(22), timer(23), timer(90)));

        editor.confirmRemoveBranch(0);
        tap(AlertDialog.BUTTON_POSITIVE);

        assertEquals(list(90), seconds(editor.editingSteps()));
    }

    // ---- an empty branch needs no question ---------------------------------------------------------

    @Test public void anEmptyBranchIsRemovedWithoutAskingAnything() {
        RoutineEditorActivity editor = editorFor("Bare condition",
                chain(timer(1), condition(1, 0)));

        editor.confirmRemoveBranch(1);

        assertNull("there is nothing to lose, so nothing to ask about", shownDialog());
        assertEquals(list(1), seconds(editor.editingSteps()));
    }

    @Test public void theActionCountDecidesWhetherToAsk() {
        assertEquals(0, RoutineBranch.branchActionCount(chain(timer(1), condition(1, 0)), 1));
        assertEquals(1, RoutineBranch.branchActionCount(chain(condition(1, 0), timer(11)), 0));
        assertEquals(2, RoutineBranch.branchActionCount(
                chain(condition(1, 1), timer(11), timer(21)), 0));
        assertEquals("steps after the branch are not part of it", 2,
                RoutineBranch.branchActionCount(
                        chain(condition(1, 1), timer(11), timer(21), timer(90)), 0));
        assertEquals("a plain step owns no branch", 0,
                RoutineBranch.branchActionCount(chain(timer(1)), 0));
    }

    // ---- the editor afterwards ------------------------------------------------------------------------

    @Test public void theEditorRerendersAndTheMenuStillWorksAfterRemoval() {
        RoutineEditorActivity editor = editorFor("Two branches",
                chain(condition(1, 1), timer(11), timer(21),
                        condition(1, 1), timer(31), timer(41), timer(90)));

        editor.confirmRemoveBranch(0);
        tap(AlertDialog.BUTTON_POSITIVE);
        assertEquals(list(-1, 31, 41, 90), seconds(editor.editingSteps()));

        // The second branch has shifted to the front; its menu must operate on where it is now.
        editor.confirmRemoveBranch(0);
        assertNotNull("the menu has to keep working after a removal", shownDialog());
        tap(AlertDialog.BUTTON_POSITIVE);
        assertEquals(list(90), seconds(editor.editingSteps()));
    }

    @Test public void repeatedTapsCannotRemoveMoreThanTheBranch() {
        RoutineEditorActivity editor = editorFor("Repeated",
                chain(condition(1, 1), timer(11), timer(21), timer(90)));

        editor.confirmRemoveBranch(0);
        editor.confirmRemoveBranch(0);
        assertEquals("still nothing removed before confirming",
                list(-1, 11, 21, 90), seconds(editor.editingSteps()));

        tap(AlertDialog.BUTTON_POSITIVE);
        assertEquals(list(90), seconds(editor.editingSteps()));
    }

    @Test public void confirmingAgainstAStepThatIsNoLongerAConditionDoesNothing() {
        RoutineEditorActivity editor = editorFor("Stale",
                chain(condition(1, 1), timer(11), timer(21), timer(90)));

        editor.confirmRemoveBranch(0);
        tap(AlertDialog.BUTTON_POSITIVE);
        assertEquals(list(90), seconds(editor.editingSteps()));

        // Index 0 is now an ordinary step. Asking to remove a branch there must be a no-op.
        editor.confirmRemoveBranch(0);
        assertEquals(list(90), seconds(editor.editingSteps()));
    }

    // ---- what is left is a valid routine ----------------------------------------------------------------

    @Test public void whatRemainsSavesAndReloadsCleanly() {
        RoutineEditorActivity editor = editorFor("Persisted",
                chain(timer(1), condition(1, 1), timer(11), timer(21), timer(90)));

        editor.confirmRemoveBranch(1);
        tap(AlertDialog.BUTTON_POSITIVE);

        List<AssistantReply.Action> remaining = editor.editingSteps();
        assertTrue(RoutineBranch.structureValid(remaining));
        RoutineStore.Routine rewritten = RoutineStore.create("Rewritten", remaining);
        assertTrue("the remainder has to be a routine Orbit will store",
                RoutineStore.upsert(context, rewritten));

        RoutineStore.Routine loaded = RoutineStore.findById(context, rewritten.id);
        assertNotNull(loaded);
        assertEquals(list(1, 90), seconds(loaded.actions));
        for (AssistantReply.Action action : loaded.actions) {
            assertFalse("no orphaned branch counts may survive",
                    action.params.has(RoutineBranch.KEY_ELSE_STEPS));
        }
    }

    @Test public void removalLeavesNoOrphanedCountsBehindOnAnotherCondition() {
        RoutineEditorActivity editor = editorFor("Neighbour",
                chain(condition(1, 1), timer(11), timer(21),
                        condition(2, 1), timer(31), timer(32), timer(41)));

        editor.confirmRemoveBranch(0);
        tap(AlertDialog.BUTTON_POSITIVE);

        List<AssistantReply.Action> remaining = editor.editingSteps();
        assertEquals(list(-1, 31, 32, 41), seconds(remaining));
        assertEquals("the surviving branch keeps its own shape",
                2, RoutineBranch.trueSteps(remaining.get(0)));
        assertEquals(1, RoutineBranch.elseSteps(remaining.get(0)));
        assertTrue(RoutineBranch.structureValid(remaining));
    }
}
