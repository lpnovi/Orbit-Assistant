package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.service.voice.VoiceInteractionSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.List;

/**
 * Who is allowed to hide the Side-button overlay.
 *
 * <p>Android reuses a single {@code VoiceInteractionSession} for every Side-button press, so the
 * dangerous case is not one invocation going wrong on its own - it is dismissal work armed by an
 * invocation that is already over finishing on top of the one the user just opened. That is the
 * failure captured on the device: a fresh overlay drew a real frame and was hidden 184 ms later by
 * an exit animation belonging to the previous invocation.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class OverlayDismissLifecycleTest {

    private static final long STABILIZATION_MS = 450L;

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        OverlayLaunchTrace.detach();
        new File(context.getFilesDir(), "orbit-overlay-launch.log").delete();
    }

    @After public void tearDown() {
        OverlayLaunchTrace.detach();
        new File(context.getFilesDir(), "orbit-overlay-launch.log").delete();
    }

    // ---- A. a close-system-dialogs callback that arrives while hidden -----------------------

    /**
     * The reproduced failure, at the point where it starts. The Side-button press itself makes
     * Android close system dialogs, and that callback reaches the still-hidden session just before
     * the new invocation is prepared. Arming an exit animation here is what later hid the fresh
     * overlay, so a hidden session must arm nothing at all.
     */
    @Test public void aHiddenSessionArmsNothingWhenAndroidClosesSystemDialogs() {
        OrbitSessionService service =
                Robolectric.buildService(OrbitSessionService.class).create().get();
        VoiceInteractionSession session = service.onNewSession(null);

        session.onCloseSystemDialogs();

        List<OverlayLaunchTrace.Attempt> all = OverlayLaunchTrace.attempts(context);
        assertFalse("the callback should have been recorded somewhere", all.isEmpty());
        OverlayLaunchTrace.Attempt attempt = all.get(all.size() - 1);
        assertTrue("a hidden close-system-dialogs callback must still be visible in diagnostics",
                attempt.reached(OverlayLaunchTrace.STAGE_CLOSE_DIALOGS_IGNORED));
        assertFalse("a hidden session must not arm a dismissal that can outlive it",
                attempt.reached(OverlayLaunchTrace.STAGE_DISMISS));
        assertTrue("the reason it was ignored has to be readable afterwards",
                joined(attempt).contains("sessionVisible=false"));
    }

    /** The same rule, stated directly. Hidden means hidden, whatever the clock says. */
    @Test public void closeSystemDialogsWhileHiddenNeverDismisses() {
        assertEquals(OverlayDismissOwnership.CloseSystemDialogs.IGNORE_NOT_VISIBLE,
                OverlayDismissOwnership.onCloseSystemDialogs(
                        false, 0L, 10_000L, STABILIZATION_MS));
        assertEquals("a long-hidden session is still hidden",
                OverlayDismissOwnership.CloseSystemDialogs.IGNORE_NOT_VISIBLE,
                OverlayDismissOwnership.onCloseSystemDialogs(
                        false, 1_000L, 9_000_000L, STABILIZATION_MS));
    }

    // ---- B. dismissal work that outlives the invocation that armed it -----------------------

    /**
     * An exit animation's end action calls {@code hide()} some hundreds of milliseconds after it
     * is armed. If a new invocation has taken the sheet over in the meantime, that end action must
     * do nothing. Rebuilding the sheet and returning it to its hidden state both hand ownership
     * over, which is exactly what preparing a fresh invocation does.
     */
    @Test public void dismissalArmedByAFinishedInvocationNoLongerOwnsTheSession() {
        int armedByOldInvocation = 7;

        // buildSheet() then prepareHiddenState() each take ownership as a new invocation prepares.
        int afterSheetRebuild = armedByOldInvocation + 1;
        int afterHiddenState = afterSheetRebuild + 1;

        assertFalse("an end action from a finished invocation must not hide the new overlay",
                OverlayDismissOwnership.dismissalStillOwnsSession(
                        armedByOldInvocation, afterHiddenState));
        assertFalse("cancelling the outgoing sheet must not let its end action through either",
                OverlayDismissOwnership.dismissalStillOwnsSession(
                        armedByOldInvocation, afterSheetRebuild));
    }

    /** The ordinary case still has to work: nothing took over, so the dismissal completes. */
    @Test public void dismissalStillOwnsTheSessionWhenNothingHasTakenOver() {
        assertTrue(OverlayDismissOwnership.dismissalStillOwnsSession(7, 7));
        assertTrue(OverlayDismissOwnership.dismissalStillOwnsSession(0, 0));
    }

    // ---- C. a legitimate close of a genuinely visible overlay -------------------------------

    /**
     * Orbit must not stop obeying Android. A visible overlay outside the fresh-show window closes
     * on close-system-dialogs exactly as it always did.
     */
    @Test public void aVisibleOverlayStillClosesWhenAndroidAsks() {
        long shownAt = 1_000L;
        assertEquals(OverlayDismissOwnership.CloseSystemDialogs.DISMISS,
                OverlayDismissOwnership.onCloseSystemDialogs(
                        true, shownAt, shownAt + STABILIZATION_MS, STABILIZATION_MS));
        assertEquals(OverlayDismissOwnership.CloseSystemDialogs.DISMISS,
                OverlayDismissOwnership.onCloseSystemDialogs(
                        true, shownAt, shownAt + 30_000L, STABILIZATION_MS));
    }

    /**
     * A Screen Selection or attachment return records no fresh external show, so there is no
     * stabilization window to sit inside and the callback is obeyed immediately.
     */
    @Test public void anInternallyResumedOverlayStillClosesWhenAndroidAsks() {
        assertEquals(OverlayDismissOwnership.CloseSystemDialogs.DISMISS,
                OverlayDismissOwnership.onCloseSystemDialogs(
                        true, 0L, 5_000L, STABILIZATION_MS));
    }

    // ---- D. the existing Samsung fresh-show stabilization window ----------------------------

    /**
     * Some Samsung builds deliver the tail of the Side-button invocation as close-system-dialogs
     * just after the fresh sheet appears. That workaround predates this fix and must survive it,
     * unchanged in length.
     */
    @Test public void theFreshShowStabilizationWindowIsUnchanged() {
        long shownAt = 2_000L;
        assertEquals(OverlayDismissOwnership.CloseSystemDialogs.IGNORE_STABILIZING,
                OverlayDismissOwnership.onCloseSystemDialogs(
                        true, shownAt, shownAt, STABILIZATION_MS));
        assertEquals(OverlayDismissOwnership.CloseSystemDialogs.IGNORE_STABILIZING,
                OverlayDismissOwnership.onCloseSystemDialogs(
                        true, shownAt, shownAt + STABILIZATION_MS - 1, STABILIZATION_MS));
        assertEquals("the window must not have grown",
                OverlayDismissOwnership.CloseSystemDialogs.DISMISS,
                OverlayDismissOwnership.onCloseSystemDialogs(
                        true, shownAt, shownAt + STABILIZATION_MS, STABILIZATION_MS));
    }

    /** A clock that appears to run backwards must not be read as being inside the window. */
    @Test public void aCallbackFromBeforeTheFreshShowIsNotTreatedAsStabilizing() {
        assertEquals(OverlayDismissOwnership.CloseSystemDialogs.DISMISS,
                OverlayDismissOwnership.onCloseSystemDialogs(
                        true, 5_000L, 4_000L, STABILIZATION_MS));
    }

    private static String joined(OverlayLaunchTrace.Attempt attempt) {
        StringBuilder text = new StringBuilder();
        for (String line : attempt.lines) text.append(line).append('\n');
        return text.toString();
    }
}
