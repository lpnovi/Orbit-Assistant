package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Live download progress on the Orbit Local screen (v0.8.2.0-beta.2).
 *
 * <p>Device report: while the page stayed open, the bar, the megabytes and the state label froze,
 * and leaving and returning made them catch up. Polling was running and storing fresh readings;
 * the screen only redrew when the chat model's state or byte count changed, so an action-model
 * download - or an error, a pause flag, the storage totals - never reached the screen until
 * onResume redrew everything. These tests pin the loop and what counts as a change.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class OrbitLocalLiveProgressTest {

    private Context context;
    private Handler main;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        main = new Handler(Looper.getMainLooper());
    }

    // ---- a scripted component -----------------------------------------------------------------------

    /** Answers each read with the next scripted status, synchronously or on demand. */
    private static final class Script implements OrbitLocalStatusPoller.Source {
        final List<OrbitLocalStatus> answers = new ArrayList<>();
        final List<Consumer<OrbitLocalStatus>> pending = new ArrayList<>();
        boolean holdAnswers;
        int index;

        @Override public void read(Consumer<OrbitLocalStatus> answer) {
            if (holdAnswers) { pending.add(answer); return; }
            answer.accept(next());
        }

        OrbitLocalStatus next() {
            if (answers.isEmpty()) return null;
            return answers.get(Math.min(index++, answers.size() - 1));
        }
    }

    private static final class Drawn implements OrbitLocalStatusPoller.Sink {
        final List<OrbitLocalStatus> redraws = new ArrayList<>();
        int deliveries;

        @Override public void onStatus(OrbitLocalStatus status, boolean changed) {
            deliveries++;
            if (changed) redraws.add(status);
        }
    }

    private static void advance(long ms) {
        ShadowLooper.idleMainLooper(ms, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    // ---- lifecycle -----------------------------------------------------------------------------------

    @Test public void startReadsAtOnceAndStopEndsTheLoop() {
        Script script = new Script();
        script.answers.add(status("DOWNLOADING", 100L, "READY", 1L));
        Drawn drawn = new Drawn();
        OrbitLocalStatusPoller poller = new OrbitLocalStatusPoller(main, script, drawn, null);

        poller.start();
        advance(0);
        assertEquals("the page shows the current status as soon as it is visible", 1, drawn.deliveries);

        advance(OrbitLocalStatusPoller.ACTIVE_MS);
        assertEquals(2, poller.reads());

        poller.stop();
        advance(60_000L);
        assertEquals("nothing runs behind another screen", 2, poller.reads());
        assertFalse(poller.isRunning());
    }

    @Test public void repeatedStartsAndResumesNeverDoubleTheLoop() {
        Script script = new Script();
        script.answers.add(status("DOWNLOADING", 100L, "READY", 1L));
        OrbitLocalStatusPoller poller = new OrbitLocalStatusPoller(main, script, new Drawn(), null);
        for (int i = 0; i < 5; i++) {
            poller.start();
            poller.stop();
            poller.start();
            poller.nudge();
        }
        advance(0);
        int afterStart = poller.reads();
        advance(OrbitLocalStatusPoller.ACTIVE_MS * 10);
        int perInterval = poller.reads() - afterStart;
        assertTrue("one loop reads about once per interval, got " + perInterval,
                perInterval <= 11);
    }

    @Test public void aReadStillInFlightIsNeverDuplicated() {
        Script script = new Script();
        script.holdAnswers = true;
        OrbitLocalStatusPoller poller = new OrbitLocalStatusPoller(main, script, new Drawn(), null);
        poller.start();
        poller.nudge();
        advance(OrbitLocalStatusPoller.IDLE_MS * 4);
        assertEquals("single-flight: no second read while one is outstanding", 1, script.pending.size());
    }

    @Test public void anAnswerLandingAfterStopSchedulesNothing() {
        Script script = new Script();
        script.holdAnswers = true;
        Drawn drawn = new Drawn();
        OrbitLocalStatusPoller poller = new OrbitLocalStatusPoller(main, script, drawn, null);
        poller.start();
        poller.stop();
        script.pending.get(0).accept(status("DOWNLOADING", 5L, "READY", 1L));
        advance(60_000L);
        assertEquals(0, drawn.deliveries);
        assertEquals(1, poller.reads());
    }

    // ---- cadence --------------------------------------------------------------------------------------

    @Test public void transientWorkIsWatchedQuicklyAndSettledWorkSlowly() {
        Script script = new Script();
        OrbitLocalStatusPoller poller = new OrbitLocalStatusPoller(main, script, new Drawn(), null);
        for (String state : new String[]{"QUEUED", "DOWNLOADING", "WAITING_FOR_NETWORK",
                "VALIDATING", "IMPORTING"}) {
            script.answers.clear();
            script.index = 0;
            script.answers.add(status(state, 5L, "READY", 1L));
            poller.stop();
            poller.start();
            advance(0);
            assertEquals(state, OrbitLocalStatusPoller.ACTIVE_MS, poller.nextDelay());
        }
        script.answers.clear();
        script.index = 0;
        script.answers.add(status("READY", 1L, "READY", 1L));
        poller.stop();
        poller.start();
        advance(0);
        assertEquals("nothing moving: no rapid polling", OrbitLocalStatusPoller.IDLE_MS,
                poller.nextDelay());
    }

    /** The exact Beta 1 miss: only the chat model counted as moving. */
    @Test public void anActionModelDownloadIsWatchedQuicklyToo() {
        Script script = new Script();
        script.answers.add(status("READY", 1L, "DOWNLOADING", 10L));
        OrbitLocalStatusPoller poller = new OrbitLocalStatusPoller(main, script, new Drawn(), null);
        poller.start();
        advance(0);
        assertEquals(OrbitLocalStatusPoller.ACTIVE_MS, poller.nextDelay());
    }

    @Test public void aNewDownloadStartedWhileIdleIsPickedUp() {
        Script script = new Script();
        script.answers.add(status("READY", 1L, "NOT_INSTALLED", 0L));
        script.answers.add(status("READY", 1L, "QUEUED", 0L));
        OrbitLocalStatusPoller poller = new OrbitLocalStatusPoller(main, script, new Drawn(), null);
        poller.start();
        advance(0);
        assertEquals(OrbitLocalStatusPoller.IDLE_MS, poller.nextDelay());
        advance(OrbitLocalStatusPoller.IDLE_MS);
        assertEquals(OrbitLocalStatusPoller.ACTIVE_MS, poller.nextDelay());
    }

    @Test public void orbitsOwnComponentDownloadAlsoKeepsTheFastRate() {
        OrbitLocalStatusPoller poller = new OrbitLocalStatusPoller(main, new Script(), new Drawn(),
                () -> true);
        assertEquals(OrbitLocalStatusPoller.ACTIVE_MS, poller.nextDelay());
    }

    // ---- what reaches the screen ------------------------------------------------------------------

    @Test public void theWholeJourneyRedrawsAtEveryStep() {
        Script script = new Script();
        script.answers.add(status("NOT_INSTALLED", 0L, "READY", 1L));
        script.answers.add(status("QUEUED", 0L, "READY", 1L));
        script.answers.add(status("DOWNLOADING", 100_000_000L, "READY", 1L));
        script.answers.add(status("DOWNLOADING", 900_000_000L, "READY", 1L));
        script.answers.add(status("VALIDATING", 1_598_556_720L, "READY", 1L));
        script.answers.add(status("READY", 1_598_556_720L, "READY", 1L));
        Drawn drawn = new Drawn();
        OrbitLocalStatusPoller poller = new OrbitLocalStatusPoller(main, script, drawn, null);
        poller.start();
        advance(OrbitLocalStatusPoller.IDLE_MS * 8);
        List<String> states = new ArrayList<>();
        for (OrbitLocalStatus s : drawn.redraws) states.add(s.modelState + ":" + s.modelBytes);
        assertEquals(6, drawn.redraws.size());
        assertEquals("READY", drawn.redraws.get(5).modelState);
        assertTrue(states.toString(), states.contains("DOWNLOADING:900000000"));
    }

    @Test public void everyFieldTheCardsShowCountsAsAChange() {
        OrbitLocalStatus base = status("READY", 1L, "DOWNLOADING", 10L);
        assertNotEquals("action-model bytes",
                base.displayFingerprint(), status("READY", 1L, "DOWNLOADING", 20L).displayFingerprint());
        assertNotEquals("action-model state",
                base.displayFingerprint(), status("READY", 1L, "VALIDATING", 10L).displayFingerprint());
        Bundle errored = bundle("READY", 1L, "DOWNLOADING", 10L);
        errored.putString("actionModelError", "No space left");
        assertNotEquals("an error message",
                base.displayFingerprint(), OrbitLocalStatus.from(errored).displayFingerprint());
        Bundle paused = bundle("PAUSED", 5L, "READY", 1L);
        paused.putBoolean("pauseRequested", true);
        assertNotEquals("a pause flag", status("PAUSED", 5L, "READY", 1L).displayFingerprint(),
                OrbitLocalStatus.from(paused).displayFingerprint());
        assertEquals("an identical reading does not redraw",
                base.displayFingerprint(), status("READY", 1L, "DOWNLOADING", 10L).displayFingerprint());
    }

    @Test public void anUnchangedReadingDoesNotRedrawEveryTick() {
        Script script = new Script();
        script.answers.add(status("READY", 1L, "READY", 1L));
        Drawn drawn = new Drawn();
        OrbitLocalStatusPoller poller = new OrbitLocalStatusPoller(main, script, drawn, null);
        poller.start();
        advance(OrbitLocalStatusPoller.IDLE_MS * 5);
        assertEquals(1, drawn.redraws.size());
        assertTrue(drawn.deliveries > 1);
    }

    @Test public void anUnreachableComponentIsAReadingToo() {
        Script script = new Script();
        Drawn drawn = new Drawn();
        OrbitLocalStatusPoller poller = new OrbitLocalStatusPoller(main, script, drawn, null);
        poller.start();
        advance(0);
        assertEquals(1, drawn.redraws.size());
        assertNull(drawn.redraws.get(0));
        assertEquals(OrbitLocalStatusPoller.IDLE_MS, poller.nextDelay());
    }

    // ---- the real screen -----------------------------------------------------------------------------

    @Test public void theScreenWatchesOnlyWhileVisible() {
        TestWorkManager.ensureInitialized(context);
        ActivityController<LocalAiActivity> controller =
                Robolectric.buildActivity(LocalAiActivity.class).setup();
        LocalAiActivity activity = controller.get();
        assertTrue("resume starts watching and reads at once", activity.poller.isRunning());
        advance(0);
        assertTrue(activity.poller.reads() >= 1);

        controller.pause();
        assertFalse("pause stops watching", activity.poller.isRunning());
        int reads = activity.poller.reads();
        advance(60_000L);
        assertEquals(reads, activity.poller.reads());

        controller.resume();
        assertTrue(activity.poller.isRunning());
        advance(0);
        assertTrue("coming back reads the current status immediately",
                activity.poller.reads() > reads);
        controller.pause().stop().destroy();
        assertFalse(activity.poller.isRunning());
    }

    @Test public void theDownloadDoesNotDependOnTheScreen() {
        String screen = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/LocalAiActivity.java");
        int pause = screen.indexOf("@Override protected void onPause()");
        String body = screen.substring(pause, screen.indexOf("    }", pause));
        assertTrue(body.contains("poller.stop();"));
        for (String forbidden : new String[]{"pauseModelDownload", "cancelModelDownload",
                "pauseActionModelDownload", "cancelActionModelDownload", "delete"}) {
            assertFalse("leaving the screen must never touch the download: " + forbidden,
                    body.contains(forbidden));
        }
        String poller = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OrbitLocalStatusPoller.java");
        assertFalse("the poller only reads", poller.contains("Download("));
        assertTrue("reads never block the main thread",
                screen.contains("OrbitLocalClient.statusAsync(this, answer::accept)"));
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private static Bundle bundle(String chat, long chatBytes, String action, long actionBytes) {
        Bundle b = new Bundle();
        b.putInt("protocol", OrbitLocalComponent.PROTOCOL_VERSION);
        b.putString("componentVersionName", BuildConfig.VERSION_NAME);
        b.putLong("componentVersionCode", BuildConfig.VERSION_CODE);
        b.putString("modelState", chat);
        b.putString("modelId", LocalModelStore.MODEL_ID);
        b.putString("modelDisplayName", LocalModelStore.MODEL_DISPLAY_NAME);
        b.putLong("modelBytes", chatBytes);
        b.putLong("modelTotalBytes", chatBytes);
        b.putLong("modelSizeBytes", LocalModelStore.MODEL_SIZE_BYTES);
        b.putString("modelError", "");
        b.putLong("freeBytes", 8_000_000_000L);
        b.putString("actionModelState", action);
        b.putLong("actionModelBytes", actionBytes);
        b.putLong("actionModelTotalBytes", actionBytes);
        b.putLong("actionModelSizeBytes", 546_660_344L);
        b.putString("actionModelError", "");
        return b;
    }

    private static OrbitLocalStatus status(String chat, long chatBytes, String action, long actionBytes) {
        return OrbitLocalStatus.from(bundle(chat, chatBytes, action, actionBytes));
    }
}
