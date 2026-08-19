package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
 * The production Side-button entry point, driven directly.
 *
 * <p>Instrumentation added for the launch trace has to be purely observational: the same session is
 * constructed, the same value is returned, and nothing about the invocation changes because a file
 * is being written alongside it. There is no emulator here, so this covers the part of the lifecycle
 * Robolectric can genuinely run — {@code OrbitSessionService.onNewSession} — rather than
 * re-implementing the rest of it in the test and asserting against the copy.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class OverlayLaunchLifecycleTest {

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

    @Test public void onNewSessionStillReturnsAnOrbitSession() {
        OrbitSessionService service =
                Robolectric.buildService(OrbitSessionService.class).create().get();
        VoiceInteractionSession session = service.onNewSession(null);
        assertNotNull("instrumentation must not change what onNewSession returns", session);
        assertTrue(session instanceof OrbitSession);
    }

    @Test public void onNewSessionOpensExactlyOneTracedAttempt() {
        OrbitSessionService service =
                Robolectric.buildService(OrbitSessionService.class).create().get();
        service.onNewSession(null);

        List<OverlayLaunchTrace.Attempt> all = OverlayLaunchTrace.attempts(context);
        assertEquals(1, all.size());
        OverlayLaunchTrace.Attempt attempt = all.get(0);
        assertTrue(attempt.reached(OverlayLaunchTrace.STAGE_NEW_SESSION));
        assertTrue(attempt.reached(OverlayLaunchTrace.STAGE_SESSION_CONSTRUCTED));
        assertFalse(attempt.hasException());
        assertEquals(OverlayLaunchTrace.processToken(), attempt.processToken);
    }

    @Test public void theEarliestEvidenceOutlivesTheProcessThatWroteIt() {
        OrbitSessionService service =
                Robolectric.buildService(OrbitSessionService.class).create().get();
        service.onNewSession(null);
        // Nothing else happens: the invocation dies before it is ever prepared.
        OverlayLaunchTrace.detach();

        String report = OverlayLaunchTrace.report(context);
        assertTrue(report.contains(OverlayLaunchTrace.STAGE_NEW_SESSION));
        assertTrue(report.contains("Stored attempts: 1"));
        assertEquals(OverlayLaunchTrace.NEVER_SHOWN,
                OverlayLaunchTrace.status(OverlayLaunchTrace.attempts(context).get(0), true));
    }

    @Test public void repeatedInvocationsAreEachTraceableAndBounded() {
        OrbitSessionService service =
                Robolectric.buildService(OrbitSessionService.class).create().get();
        for (int i = 0; i < 6; i++) assertNotNull(service.onNewSession(null));
        List<OverlayLaunchTrace.Attempt> all = OverlayLaunchTrace.attempts(context);
        assertEquals(6, all.size());
        assertTrue(all.size() <= OverlayLaunchTrace.MAX_ATTEMPTS);
    }
}
