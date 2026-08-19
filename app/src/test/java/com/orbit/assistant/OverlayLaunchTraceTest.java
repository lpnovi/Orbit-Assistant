package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The persistent Side-button launch trace.
 *
 * <p>These test the instrument, not the bug. The overlay failure this exists for has never
 * reproduced on demand, so what has to be proven here is that when it does happen, the evidence is
 * still on disk afterwards, says which stage was the last to succeed, and contains nothing private.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class OverlayLaunchTraceTest {

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

    // ---- what a healthy launch looks like ---------------------------------------------------

    /** A fresh Side-button press that ends with the user looking at the overlay. */
    private void driveNormalLaunch() {
        OverlayLaunchTrace.begin(context, OverlayLaunchTrace.STAGE_NEW_SESSION);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_NEW_SESSION);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_SESSION_CONSTRUCTED);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_CREATE_START);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_CREATE_COMPLETE);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_CONTENT_START);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_CONTENT_COMPLETE);
        OverlayLaunchTrace.beginIfInvocationIsNew(context, OverlayLaunchTrace.STAGE_PREPARE_START);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_PREPARE_START,
                OverlayLaunchTrace.State.of().flag("internalResume", false));
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_RESET_START);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_RESET_COMPLETE);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_CONTEXT_START);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_CONTEXT_COMPLETE,
                OverlayLaunchTrace.State.of().foreground("com.example.chat", "Example Chat"));
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_HISTORY_START);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_HISTORY_COMPLETE,
                OverlayLaunchTrace.State.of().count("historyMessages", 4));
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_SHEET_START);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_SHEET_COMPLETE);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_HIDDEN_READY);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_INSETS_REQUESTED);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_PREPARE_COMPLETE);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_SHOW);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_ROOT_ATTACHED);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_FIRST_FRAME);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_STABLE_FRAME);
    }

    @Test public void normalLaunchReachesVisibleFrameAndIsComplete() {
        driveNormalLaunch();
        List<OverlayLaunchTrace.Attempt> all = OverlayLaunchTrace.attempts(context);
        assertEquals(1, all.size());
        OverlayLaunchTrace.Attempt attempt = all.get(0);
        assertTrue(attempt.visible());
        assertFalse(attempt.internalResume());
        assertEquals(OverlayLaunchTrace.STAGE_STABLE_FRAME, attempt.lastStage());
        assertEquals(OverlayLaunchTrace.COMPLETE_VISIBLE,
                OverlayLaunchTrace.status(attempt, false));
        assertEquals("com.example.chat (Example_Chat)", attempt.foreground);
    }

    @Test public void everyQuestionTheReportHasToAnswerIsAnswerable() {
        driveNormalLaunch();
        OverlayLaunchTrace.Attempt attempt = OverlayLaunchTrace.attempts(context).get(0);
        assertTrue(attempt.reached(OverlayLaunchTrace.STAGE_NEW_SESSION));
        assertTrue(attempt.reached(OverlayLaunchTrace.STAGE_CREATE_COMPLETE));
        assertTrue(attempt.reached(OverlayLaunchTrace.STAGE_CONTENT_COMPLETE));
        assertTrue(attempt.reached(OverlayLaunchTrace.STAGE_PREPARE_START));
        assertTrue(attempt.reached(OverlayLaunchTrace.STAGE_SHEET_COMPLETE));
        assertTrue(attempt.reached(OverlayLaunchTrace.STAGE_SHOW));
        assertTrue(attempt.reached(OverlayLaunchTrace.STAGE_FIRST_FRAME));
        assertFalse(attempt.reached(OverlayLaunchTrace.STAGE_HIDE));
        assertFalse(attempt.hasException());
    }

    // ---- an internal return is not a new launch ---------------------------------------------

    @Test public void screenSelectionResumeStaysOnTheSameAttempt() {
        driveNormalLaunch();
        // Screen Selection hides this same session, then shows it again.
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_TRANSITION,
                OverlayLaunchTrace.State.of().reason(OverlayLaunchTrace.REASON_SCREEN_SELECTION));
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_HIDE);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_PREPARE_RESUME,
                OverlayLaunchTrace.State.of().flag("internalResume", true));
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_SHOW);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_FIRST_FRAME);

        List<OverlayLaunchTrace.Attempt> all = OverlayLaunchTrace.attempts(context);
        assertEquals("an internal return must not open a second attempt", 1, all.size());
        OverlayLaunchTrace.Attempt attempt = all.get(0);
        assertTrue(attempt.internalResume());
        assertEquals(OverlayLaunchTrace.INTERNAL_TRANSITION,
                OverlayLaunchTrace.status(attempt, true));
        assertFalse(OverlayLaunchTrace.status(attempt, true)
                .equals(OverlayLaunchTrace.INCOMPLETE));
    }

    @Test public void aSecondExternalPressOpensItsOwnAttempt() {
        driveNormalLaunch();
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_DISMISS,
                OverlayLaunchTrace.State.of().reason(OverlayLaunchTrace.REASON_SWIPE_DOWN));
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_HIDE);
        // Android reuses the session, so only onPrepareShow marks the new invocation.
        OverlayLaunchTrace.beginIfInvocationIsNew(context, OverlayLaunchTrace.STAGE_PREPARE_START);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_PREPARE_START);
        assertEquals(2, OverlayLaunchTrace.attempts(context).size());
    }

    @Test public void constructionAndFirstPreparationShareOneAttempt() {
        OverlayLaunchTrace.begin(context, OverlayLaunchTrace.STAGE_NEW_SESSION);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_NEW_SESSION);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_SESSION_CONSTRUCTED);
        OverlayLaunchTrace.beginIfInvocationIsNew(context, OverlayLaunchTrace.STAGE_PREPARE_START);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_PREPARE_START);
        List<OverlayLaunchTrace.Attempt> all = OverlayLaunchTrace.attempts(context);
        assertEquals(1, all.size());
        assertTrue(all.get(0).reached(OverlayLaunchTrace.STAGE_NEW_SESSION));
        assertTrue(all.get(0).reached(OverlayLaunchTrace.STAGE_PREPARE_START));
    }

    // ---- endings ----------------------------------------------------------------------------

    @Test public void explicitDismissalIsRecordedAsExpected() {
        driveNormalLaunch();
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_DISMISS,
                OverlayLaunchTrace.State.of().reason(OverlayLaunchTrace.REASON_SWIPE_DOWN));
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_HIDE);
        OverlayLaunchTrace.Attempt attempt = OverlayLaunchTrace.attempts(context).get(0);
        assertEquals(OverlayLaunchTrace.REASON_SWIPE_DOWN, attempt.endingReason);
        assertEquals(OverlayLaunchTrace.EXPECTED_DISMISS,
                OverlayLaunchTrace.status(attempt, false));
    }

    @Test public void hideBeforeAnyVisibleFrameIsDistinguishable() {
        OverlayLaunchTrace.begin(context, OverlayLaunchTrace.STAGE_NEW_SESSION);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_PREPARE_START);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_SHEET_COMPLETE);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_PREPARE_COMPLETE);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_SHOW);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_HIDE);

        OverlayLaunchTrace.Attempt attempt = OverlayLaunchTrace.attempts(context).get(0);
        assertFalse(attempt.visible());
        assertEquals("", attempt.endingReason);
        assertEquals(OverlayLaunchTrace.HIDDEN_BEFORE_VISIBLE,
                OverlayLaunchTrace.status(attempt, false));
    }

    @Test public void hideAfterVisibleFrameWithoutDismissalIsASystemHide() {
        driveNormalLaunch();
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_HIDE);
        assertEquals(OverlayLaunchTrace.SYSTEM_HIDE,
                OverlayLaunchTrace.status(OverlayLaunchTrace.attempts(context).get(0), false));
    }

    @Test public void aSessionThatIsNeverAskedToAppearIsNotCalledAFailure() {
        OverlayLaunchTrace.begin(context, OverlayLaunchTrace.STAGE_NEW_SESSION);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_SESSION_CONSTRUCTED);
        OverlayLaunchTrace.Attempt attempt = OverlayLaunchTrace.attempts(context).get(0);
        assertEquals(OverlayLaunchTrace.NEVER_SHOWN, OverlayLaunchTrace.status(attempt, true));
        assertEquals(OverlayLaunchTrace.IN_PROGRESS, OverlayLaunchTrace.status(attempt, false));
    }

    // ---- the case the whole trace exists for ------------------------------------------------

    @Test public void unfinishedAttemptStaysReadableAfterTheStoreIsRecreated() {
        OverlayLaunchTrace.begin(context, OverlayLaunchTrace.STAGE_NEW_SESSION);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_PREPARE_START);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_SHEET_START);
        // Nothing else arrives. The overlay never appeared and nothing recorded an ending.

        OverlayLaunchTrace.detach();

        List<OverlayLaunchTrace.Attempt> all = OverlayLaunchTrace.attempts(context);
        assertEquals(1, all.size());
        OverlayLaunchTrace.Attempt attempt = all.get(0);
        assertEquals(OverlayLaunchTrace.STAGE_SHEET_START, attempt.lastStage());
        assertTrue(attempt.prepared());
        assertFalse(attempt.visible());
        assertEquals(OverlayLaunchTrace.INCOMPLETE, OverlayLaunchTrace.status(attempt, true));
    }

    @Test public void theNextLaunchAfterAFailureCanStillSeeIt() {
        OverlayLaunchTrace.begin(context, OverlayLaunchTrace.STAGE_NEW_SESSION);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_PREPARE_START);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_SHEET_COMPLETE);
        OverlayLaunchTrace.detach();

        driveNormalLaunch();
        String report = OverlayLaunchTrace.report(context);
        assertTrue(report.contains("Attempt 1 of 2"));
        assertTrue(report.contains(OverlayLaunchTrace.INCOMPLETE));
        assertTrue(report.contains(OverlayLaunchTrace.STAGE_SHEET_COMPLETE));
        assertTrue(report.contains(OverlayLaunchTrace.COMPLETE_VISIBLE));
    }

    @Test public void anAttemptFromAnotherProcessIsNotTreatedAsStillRunning() {
        OverlayLaunchTrace.Attempt other = new OverlayLaunchTrace.Attempt(
                "someone-else-1", System.currentTimeMillis(), "999.dead", "0.7.4.2 (712)",
                OverlayLaunchTrace.STAGE_NEW_SESSION);
        other.stages.add(OverlayLaunchTrace.STAGE_NEW_SESSION);
        other.stages.add(OverlayLaunchTrace.STAGE_PREPARE_START);
        assertEquals(OverlayLaunchTrace.INCOMPLETE, OverlayLaunchTrace.status(other, false));
    }

    // ---- exceptions -------------------------------------------------------------------------

    @Test public void exceptionIsPersistedWithBoundedStackInformation() {
        OverlayLaunchTrace.begin(context, OverlayLaunchTrace.STAGE_NEW_SESSION);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_PREPARE_START);
        OverlayLaunchTrace.exception(OverlayLaunchTrace.STAGE_SHEET_START, deepThrowable());
        OverlayLaunchTrace.detach();

        OverlayLaunchTrace.Attempt attempt = OverlayLaunchTrace.attempts(context).get(0);
        assertTrue(attempt.hasException());
        assertEquals(OverlayLaunchTrace.STAGE_SHEET_START, attempt.exceptionStage);
        assertTrue(attempt.exceptionText.contains("java.lang.IllegalStateException"));
        assertEquals(OverlayLaunchTrace.EXCEPTION, OverlayLaunchTrace.status(attempt, false));

        int frames = 0;
        for (String line : attempt.exceptionText.split("\n")) {
            if (line.trim().startsWith("at ")) frames++;
        }
        assertTrue("stack must be bounded, saw " + frames,
                frames <= OverlayLaunchTrace.MAX_STACK_FRAMES
                        + OverlayLaunchTrace.MAX_CAUSES * OverlayLaunchTrace.MAX_CAUSE_FRAMES);
        assertTrue("the whole record must stay small", attempt.exceptionText.length() < 4000);
    }

    @Test public void exceptionMessagesAreClippedRatherThanStoredWhole() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 400; i++) huge.append("abcdefghij");
        OverlayLaunchTrace.begin(context, OverlayLaunchTrace.STAGE_NEW_SESSION);
        OverlayLaunchTrace.exception(OverlayLaunchTrace.STAGE_SHOW,
                new IllegalArgumentException(huge.toString()));
        OverlayLaunchTrace.Attempt attempt = OverlayLaunchTrace.attempts(context).get(0);
        String firstLine = attempt.exceptionText.split("\n")[0];
        assertTrue(firstLine.length()
                < OverlayLaunchTrace.MAX_EXCEPTION_MESSAGE + 80);
    }

    @Test public void rethrowHandsBackTheSameThrowableUnchanged() {
        OverlayLaunchTrace.begin(context, OverlayLaunchTrace.STAGE_NEW_SESSION);
        IllegalStateException original = new IllegalStateException("sheet build failed");
        try {
            throw OverlayLaunchTrace.rethrow(OverlayLaunchTrace.STAGE_SHEET_START, original);
        } catch (IllegalStateException thrown) {
            assertSame("instrumentation must not swallow or wrap a crash", original, thrown);
        }
        assertTrue(OverlayLaunchTrace.attempts(context).get(0).hasException());
    }

    @Test public void rethrowPreservesErrorsToo() {
        OverlayLaunchTrace.begin(context, OverlayLaunchTrace.STAGE_NEW_SESSION);
        StackOverflowError original = new StackOverflowError();
        try {
            throw OverlayLaunchTrace.rethrow(OverlayLaunchTrace.STAGE_CREATE_START, original);
        } catch (StackOverflowError thrown) {
            assertSame(original, thrown);
        }
    }

    // ---- retention --------------------------------------------------------------------------

    @Test public void retentionIsBoundedAndPrunesTheOldest() {
        for (int i = 0; i < OverlayLaunchTrace.MAX_ATTEMPTS + 14; i++) {
            OverlayLaunchTrace.begin(context, OverlayLaunchTrace.STAGE_NEW_SESSION);
            OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_PREPARE_START);
            OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_SHOW);
            OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_FIRST_FRAME,
                    OverlayLaunchTrace.State.of().count("run", i));
            OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_HIDE);
        }
        List<OverlayLaunchTrace.Attempt> all = OverlayLaunchTrace.attempts(context);
        assertTrue("stored " + all.size(), all.size() <= OverlayLaunchTrace.MAX_ATTEMPTS);
        assertTrue(all.size() >= 2);

        String report = OverlayLaunchTrace.report(context);
        assertTrue("newest attempt must survive pruning", report.contains("run=23"));
        assertFalse("oldest attempt must be pruned away", report.contains("run=0\n"));

        File file = new File(context.getFilesDir(), "orbit-overlay-launch.log");
        assertTrue(file.length() < OverlayLaunchTrace.MAX_FILE_BYTES);
    }

    /**
     * The trace is on the overlay's launch path, so its cost has to stay a known quantity: a
     * couple of dozen short appends, not a write per frame, inset or layout pass.
     */
    @Test public void oneNormalLaunchWritesABoundedNumberOfRecords() {
        driveNormalLaunch();
        File file = new File(context.getFilesDir(), "orbit-overlay-launch.log");
        int lines = 0;
        for (String line : readLog(file).split("\n")) {
            if (!line.trim().isEmpty()) lines++;
        }
        assertEquals("one cold launch: 1 header plus 22 milestones", 23, lines);
        assertTrue("and it must stay small on disk", file.length() < 4096);
    }

    @Test public void oneAttemptCannotGrowWithoutBound() {
        OverlayLaunchTrace.begin(context, OverlayLaunchTrace.STAGE_NEW_SESSION);
        for (int i = 0; i < OverlayLaunchTrace.MAX_EVENTS_PER_ATTEMPT * 3; i++) {
            OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_INSETS_REQUESTED);
        }
        OverlayLaunchTrace.Attempt attempt = OverlayLaunchTrace.attempts(context).get(0);
        assertTrue(attempt.stages.size() <= OverlayLaunchTrace.MAX_EVENTS_PER_ATTEMPT);
    }

    // ---- report shape -----------------------------------------------------------------------

    @Test public void reportIsOrderedOldestFirst() {
        OverlayLaunchTrace.begin(context, OverlayLaunchTrace.STAGE_NEW_SESSION);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_CONTEXT_COMPLETE,
                OverlayLaunchTrace.State.of().foreground("com.first.app", "First"));
        OverlayLaunchTrace.begin(context, OverlayLaunchTrace.STAGE_NEW_SESSION);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_CONTEXT_COMPLETE,
                OverlayLaunchTrace.State.of().foreground("com.second.app", "Second"));

        String report = OverlayLaunchTrace.report(context);
        assertTrue(report.startsWith("Orbit overlay launch diagnostics"));
        assertTrue(report.contains("Stored attempts: 2"));
        assertTrue(report.indexOf("com.first.app") < report.indexOf("com.second.app"));
        assertTrue(report.indexOf("Attempt 1 of 2") < report.indexOf("Attempt 2 of 2"));
    }

    @Test public void reportExplainsEveryStatusItCanPrint() {
        for (String status : new String[]{
                OverlayLaunchTrace.COMPLETE_VISIBLE, OverlayLaunchTrace.EXPECTED_DISMISS,
                OverlayLaunchTrace.INTERNAL_TRANSITION, OverlayLaunchTrace.SYSTEM_HIDE,
                OverlayLaunchTrace.HIDDEN_BEFORE_VISIBLE, OverlayLaunchTrace.INCOMPLETE,
                OverlayLaunchTrace.NEVER_SHOWN, OverlayLaunchTrace.EXCEPTION,
                OverlayLaunchTrace.IN_PROGRESS}) {
            String explanation = OverlayLaunchTrace.explain(status);
            assertNotNull(explanation);
            assertTrue(status + " needs a plain-English explanation", explanation.length() > 12);
        }
    }

    @Test public void emptyStoreSaysSoRatherThanLookingBroken() {
        String report = OverlayLaunchTrace.report(context);
        assertTrue(report.contains("Stored attempts: 0"));
        assertTrue(report.contains("Side-button"));
        assertEquals("No overlay launch recorded yet", OverlayLaunchTrace.summary(context));
    }

    @Test public void summaryLineNamesTheMostRecentAttempt() {
        driveNormalLaunch();
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_DISMISS,
                OverlayLaunchTrace.State.of().reason(OverlayLaunchTrace.REASON_CLOSE_BUTTON));
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_HIDE);
        assertTrue(OverlayLaunchTrace.summary(context)
                .startsWith(OverlayLaunchTrace.EXPECTED_DISMISS + " at "));
    }

    @Test public void elapsedTimesAreNonNegativeAndNonDecreasing() {
        driveNormalLaunch();
        OverlayLaunchTrace.Attempt attempt = OverlayLaunchTrace.attempts(context).get(0);
        Pattern pattern = Pattern.compile("^\\[\\s*(\\d+) ms\\]");
        List<Long> offsets = new ArrayList<>();
        for (String line : attempt.lines) {
            Matcher matcher = pattern.matcher(line);
            assertTrue("every line needs a timestamp: " + line, matcher.find());
            offsets.add(Long.parseLong(matcher.group(1)));
        }
        assertFalse(offsets.isEmpty());
        long previous = -1;
        for (long offset : offsets) {
            assertTrue("offsets must never be negative", offset >= 0);
            assertTrue("offsets must not go backwards", offset >= previous);
            previous = offset;
        }
    }

    // ---- privacy ----------------------------------------------------------------------------

    /** Things that exist in a live session and must never reach this file. */
    private static final String[] PRIVATE_CONTENT = {
            "Tell my wife I will be late tonight",
            "Sure, here is the summary of your bank statement",
            "remind me to take my medication at eight",
            "Account balance 4,120.55",
            "sk-orbit-secret-token",
            "Password: hunter2",
            "Discord message from Alex about the party",
    };

    @Test public void noConversationVoiceOrScreenContentCanReachTheFile() {
        driveNormalLaunch();
        // Everything a session might be tempted to describe, offered through the real API.
        for (String content : PRIVATE_CONTENT) {
            OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_DISMISS,
                    OverlayLaunchTrace.State.of().reason(content));
        }
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_HIDE);
        OverlayLaunchTrace.detach();

        String report = OverlayLaunchTrace.report(context);
        for (String content : PRIVATE_CONTENT) {
            assertFalse("private content leaked: " + content, report.contains(content));
        }
    }

    @Test public void anUnknownReasonBecomesUnspecifiedRatherThanFreeText() {
        OverlayLaunchTrace.begin(context, OverlayLaunchTrace.STAGE_NEW_SESSION);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_DISMISS,
                OverlayLaunchTrace.State.of().reason("user said: meet me at 5 Cedar Lane"));
        OverlayLaunchTrace.Attempt attempt = OverlayLaunchTrace.attempts(context).get(0);
        assertEquals(OverlayLaunchTrace.REASON_UNSPECIFIED, attempt.endingReason);
        for (String line : attempt.lines) assertFalse(line.contains("Cedar"));
    }

    @Test public void stateAcceptsOnlyBooleansCountsReasonsAndTheForegroundApp() {
        String built = OverlayLaunchTrace.State.of()
                .flag("busy", true)
                .count("historyMessages", 7)
                .reason(OverlayLaunchTrace.REASON_BACK)
                .foreground("com.example.app", "Example")
                .build();
        assertEquals("busy=true historyMessages=7 reason=back fg=com.example.app fgLabel=Example",
                built);
    }

    @Test public void foregroundLabelsAreCapped() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 40; i++) huge.append("LongAppName");
        OverlayLaunchTrace.begin(context, OverlayLaunchTrace.STAGE_NEW_SESSION);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_CONTEXT_COMPLETE,
                OverlayLaunchTrace.State.of().foreground(huge.toString(), huge.toString()));
        OverlayLaunchTrace.Attempt attempt = OverlayLaunchTrace.attempts(context).get(0);
        assertTrue(attempt.foreground.length() <= 2 * (OverlayLaunchTrace.MAX_LABEL + 4) + 4);
    }

    @Test public void nothingIsRecordedBeforeAnAttemptHasBeenOpened() {
        OverlayLaunchTrace.detach();
        new File(context.getFilesDir(), "orbit-overlay-launch.log").delete();
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_SHOW);
        assertTrue(OverlayLaunchTrace.attempts(context).isEmpty());
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static Throwable deepThrowable() {
        try {
            recurse(60);
            fail("expected a throwable");
            return null;
        } catch (IllegalStateException e) {
            return new IllegalStateException("could not build the sheet", e);
        }
    }

    private static String readLog(File file) {
        try {
            return new String(java.nio.file.Files.readAllBytes(file.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("could not read the launch log", e);
        }
    }

    private static void recurse(int depth) {
        if (depth <= 0) throw new IllegalStateException("bottom");
        recurse(depth - 1);
    }
}
