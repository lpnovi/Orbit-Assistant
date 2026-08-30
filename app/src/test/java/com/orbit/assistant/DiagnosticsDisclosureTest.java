package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

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
 * Diagnostics has to stay powerful without making the reader wade through all of it every time.
 *
 * <p>Two things are being protected here at once, and they pull against each other. The screen
 * must be short by default, and the deep telemetry that has actually caught real bugs — duplicate
 * submissions, refused completions, overlapping WorkManager runs — must still be there and still
 * be copyable. These pin both, plus the privacy rule the Beta 2 audit added: the raw Routine
 * planner reply can echo what the user typed, so it is on the device but not in a copied report.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class DiagnosticsDisclosureTest {
    /** Distinctive enough that finding it in a copied report is unambiguous. */
    private static final String USER_WORDING =
            "{\"name\":\"text zzpersonzz when I leave zzplacezz\",\"steps\":[]}";

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        DiagnosticStore.prefs(context).edit().clear().commit();
        context.getSharedPreferences("orbit_reasoning_summary", Context.MODE_PRIVATE)
                .edit().clear().commit();
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

    private static List<String> texts(Activity activity) {
        List<String> out = new ArrayList<>();
        for (View v : descendants(activity.getWindow().getDecorView())) {
            if (v instanceof TextView) out.add(((TextView) v).getText().toString());
        }
        return out;
    }

    private static View viewWithText(Activity activity, String text) {
        for (View v : descendants(activity.getWindow().getDecorView())) {
            if (v instanceof TextView && text.contentEquals(((TextView) v).getText())) return v;
        }
        return null;
    }

    private ActivityController<DiagnosticsActivity> open() {
        return Robolectric.buildActivity(DiagnosticsActivity.class).setup();
    }

    /** A planning attempt whose raw reply carries the user's own routine wording. */
    private void seedRoutinePlan() {
        RoutineDraft.Outcome outcome = null;
        DiagnosticStore.recordRoutinePlan(context, "ChatGPT", USER_WORDING, outcome, false, "");
    }

    private void seedRequestFlow() {
        DiagnosticStore.prefs(context).edit()
                .putInt("submissions_accepted", 12)
                .putInt("submissions_suppressed", 3)
                .putInt("completions_committed", 11)
                .putInt("completions_ignored", 2)
                .putInt("completions_ignored_duplicate", 1)
                .putInt("worker_attempts_superseded", 4)
                .putString("submission_source", "composer")
                .commit();
    }

    // ---- the screen is short by default ---------------------------------------------------------

    @Test public void theScreenOpensWithAnOverviewAndCollapsedSections() {
        seedRequestFlow();
        ActivityController<DiagnosticsActivity> controller = open();
        List<String> visible = texts(controller.get());

        assertTrue("the Overview is the part that is always there", visible.contains("Overview"));
        for (String section : new String[]{"Request flow", "Thinking updates", "Auto routing",
                "Screen & app context", "Memory", "Calendar", "Orbit Local", "Routines", "Advanced"}) {
            assertTrue("section " + section + " must have a heading", visible.contains(section));
        }

        // The detail itself is not on screen until asked for.
        String joined = String.join("\n", visible);
        assertFalse("collapsed detail must not be rendered",
                joined.contains("Superseded worker runs"));
        assertFalse(joined.contains("Backend has produced a summary"));
        controller.pause().stop().destroy();
    }

    @Test public void theOverviewStaysCompactAndAnswersTheBasicQuestions() {
        ActivityController<DiagnosticsActivity> controller = open();
        String overview = null;
        for (String text : texts(controller.get())) {
            if (text.startsWith("Orbit version:")) overview = text;
        }
        assertNotNull("the Overview must be present", overview);
        for (String field : new String[]{"Orbit version:", "Provider:", "ChatGPT:",
                "Default mode:", "Pending requests:", "Thinking updates:"}) {
            assertTrue("the Overview must answer " + field, overview.contains(field));
        }
        assertTrue("the Overview must still say whether anything is wrong",
                overview.contains("Status: OK") || overview.contains("Last error:"));
        assertTrue("the Overview must stay something you can take in at a glance: "
                        + overview.split("\n").length + " lines",
                overview.split("\n").length <= 9);
        controller.pause().stop().destroy();
    }

    @Test public void expandingASectionRevealsItsDetail() {
        seedRequestFlow();
        ActivityController<DiagnosticsActivity> controller = open();
        DiagnosticsActivity activity = controller.get();

        View head = viewWithText(activity, "Request flow");
        assertNotNull(head);
        // The heading's parent row is the control, which is how the card is built.
        ((View) head.getParent()).performClick();

        String joined = String.join("\n", texts(activity));
        assertTrue("expanding must show the counters that catch duplicate answers",
                joined.contains("Superseded worker runs"));
        assertTrue(joined.contains("Completions refused"));
        controller.pause().stop().destroy();
    }

    @Test public void expandAllAndCollapseAllWork() {
        ActivityController<DiagnosticsActivity> controller = open();
        DiagnosticsActivity activity = controller.get();

        View expandAll = viewWithText(activity, "Expand all");
        assertNotNull(expandAll);
        expandAll.performClick();
        assertTrue(String.join("\n", texts(activity)).contains("Superseded worker runs"));

        View collapseAll = viewWithText(activity, "Collapse all");
        assertNotNull("the control must flip once something is open", collapseAll);
        collapseAll.performClick();
        assertFalse(String.join("\n", texts(activity)).contains("Superseded worker runs"));
        controller.pause().stop().destroy();
    }

    // ---- current problems versus things that already resolved themselves ---------------------------

    /** Returns the Overview block, which is the one that starts with the version. */
    private String overviewOf(DiagnosticsActivity activity) {
        for (String text : texts(activity)) if (text.startsWith("Orbit version:")) return text;
        return "";
    }

    /**
     * The reported problem: a healthy Orbit looked broken because of something it had already fixed.
     *
     * <p>{@code attachment_bridge_stale_recovered} describes a guard doing its job, and it sat in
     * Overview as "Last error" for as long as it was the most recent thing recorded.
     */
    @Test public void arecoveredConditionDoesNotMakeOverviewLookBroken() {
        DiagnosticStore.recordRecovered(context, "attachment_bridge_stale_recovered");

        ActivityController<DiagnosticsActivity> controller = open();
        String overview = overviewOf(controller.get());
        assertFalse("a resolved condition must not be presented as a current failure",
                overview.contains("Last error:"));
        assertFalse(overview.contains("attachment_bridge_stale_recovered"));
        assertTrue("and Overview must still answer the question", overview.contains("Status: OK"));
        controller.pause().stop().destroy();
    }

    /** The rule is about the vocabulary, not about one remembered string. */
    @Test public void anyConditionNamedRecoveredIsTreatedAsHistory() {
        assertTrue(DiagnosticStore.isRecoveredCondition("attachment_bridge_stale_recovered"));
        assertTrue(DiagnosticStore.isRecoveredCondition("some_future_guard_recovered"));
        assertFalse(DiagnosticStore.isRecoveredCondition("gallery_component_launch_failed: x"));

        // Even routed through the old entry point, which is how existing call sites reach it.
        DiagnosticStore.recordError(context, "some_future_guard_recovered");
        assertEquals("", DiagnosticStore.currentError(context));
        assertEquals("some_future_guard_recovered", DiagnosticStore.recoveredCondition(context));
    }

    /** Real failures are not hidden by any of this. */
    @Test public void aRealCurrentErrorIsStillReportedProminently() {
        DiagnosticStore.recordError(context, "gallery_component_launch_failed: com.example");

        ActivityController<DiagnosticsActivity> controller = open();
        String overview = overviewOf(controller.get());
        assertTrue("a current failure belongs in Overview", overview.contains("Last error:"));
        assertTrue(overview.contains("gallery_component_launch_failed: com.example"));
        assertFalse("and it must not be reported as fine", overview.contains("Status: OK"));
        controller.pause().stop().destroy();
    }

    /** A recovered condition must not displace a real error that is still outstanding. */
    @Test public void aLaterRecoveryDoesNotEraseAnOutstandingError() {
        DiagnosticStore.recordError(context, "gallery_component_launch_failed: com.example");
        DiagnosticStore.recordRecovered(context, "attachment_bridge_stale_recovered");

        ActivityController<DiagnosticsActivity> controller = open();
        assertTrue(overviewOf(controller.get()).contains("gallery_component_launch_failed"));
        controller.pause().stop().destroy();
    }

    /** The detail is the point of keeping it: Advanced still has the whole story. */
    @Test public void recoveredHistoryRemainsAvailableInTheDetailSections() {
        DiagnosticStore.recordRecovered(context, "attachment_bridge_stale_recovered");

        ActivityController<DiagnosticsActivity> controller = open();
        DiagnosticsActivity activity = controller.get();
        View expandAll = viewWithText(activity, "Expand all");
        assertNotNull(expandAll);
        expandAll.performClick();

        String joined = String.join("\n", texts(activity));
        assertTrue("the historical condition must still be findable",
                joined.contains("attachment_bridge_stale_recovered"));
        assertTrue("and must be labelled for what it was",
                joined.contains("resolved automatically"));
        controller.pause().stop().destroy();
    }

    // ---- the two reports -------------------------------------------------------------------------

    @Test public void copySummaryIsShortAndCarriesTheHighValueFields() {
        seedRequestFlow();
        ActivityController<DiagnosticsActivity> controller = open();
        String summary = controller.get().summaryReport();

        for (String field : new String[]{"Version:", "Provider:", "Default mode:",
                "Pending requests:", "Requests:", "Thinking updates:"}) {
            assertTrue("the summary must carry " + field, summary.contains(field));
        }
        assertTrue("the summary must still say whether anything is wrong",
                summary.contains("Status: OK") || summary.contains("Last error:"));
        assertTrue("the duplication counters are the whole point of pasting this",
                summary.contains("committed") && summary.contains("already answered")
                        && summary.contains("superseded runs"));
        assertTrue("a summary someone can paste into a chat: "
                        + summary.split("\n").length + " lines",
                summary.split("\n").length <= 14);
        controller.pause().stop().destroy();
    }

    /** Copy summary must not hand a support reply an old recovery dressed up as a live failure. */
    @Test public void copySummaryDoesNotPresentRecoveredHistoryAsACurrentFailure() {
        DiagnosticStore.recordRecovered(context, "attachment_bridge_stale_recovered");
        ActivityController<DiagnosticsActivity> controller = open();
        String summary = controller.get().summaryReport();

        assertFalse("nothing is currently failing", summary.contains("Last error:"));
        assertTrue(summary.contains("Status: OK"));
        assertTrue("but the recovery is worth mentioning, as history",
                summary.contains("Recent recovered issue: attachment_bridge_stale_recovered"));
        assertTrue(summary.contains("resolved automatically"));
        controller.pause().stop().destroy();
    }

    /** And the full report keeps the provenance a real investigation needs. */
    @Test public void copyFullRetainsRecoveredProvenance() {
        DiagnosticStore.recordRecovered(context, "attachment_bridge_stale_recovered");
        ActivityController<DiagnosticsActivity> controller = open();
        String full = controller.get().fullReport();

        assertTrue(full.contains("Last recovered condition: attachment_bridge_stale_recovered"));
        assertTrue(full.contains("resolved automatically"));
        controller.pause().stop().destroy();
    }

    @Test public void copyFullRetainsEveryDebuggingSection() {
        seedRequestFlow();
        seedRoutinePlan();
        ActivityController<DiagnosticsActivity> controller = open();
        String full = controller.get().fullReport();

        for (String heading : new String[]{"Overview", "Request flow", "Thinking updates",
                "Auto routing", "Screen & app context", "Memory", "Calendar", "Orbit Local",
                "Routines", "Advanced"}) {
            assertTrue("the full report must still contain " + heading, full.contains(heading));
        }
        // The specific fields that have diagnosed real failures.
        for (String field : new String[]{"Accepted submissions", "Suppressed duplicate submissions",
                "Completions committed", "Completions refused", "Of those, already answered",
                "Superseded worker runs", "Active requests",
                "Backend has produced a summary", "Last status handed over to an answer",
                "Component:", "Direct calendar writes", "Detected screen type"}) {
            assertTrue("the full report lost " + field, full.contains(field));
        }
        controller.pause().stop().destroy();
    }

    // ---- privacy ----------------------------------------------------------------------------------

    /**
     * The Beta 2 audit finding. {@code plan_raw} is the planner's reply to a description the user
     * typed, so it can name people and places they mentioned. It used to be appended to the one
     * copied report; now it is on the device only, behind its own control.
     */
    @Test public void rawPlannerOutputIsInNeitherCopiedReport() {
        seedRoutinePlan();
        ActivityController<DiagnosticsActivity> controller = open();
        DiagnosticsActivity activity = controller.get();

        assertFalse("the user's routine wording must not be in the summary",
                activity.summaryReport().contains("zzpersonzz"));
        assertFalse("nor in the full report, which is the one people paste",
                activity.fullReport().contains("zzpersonzz"));
        assertTrue("but the full report should say it exists",
                activity.fullReport().contains("Raw planner response"));
        controller.pause().stop().destroy();
    }

    @Test public void rawPlannerOutputIsStillAvailableOnTheDeviceBehindItsOwnControl() {
        seedRoutinePlan();
        ActivityController<DiagnosticsActivity> controller = open();
        DiagnosticsActivity activity = controller.get();

        View head = viewWithText(activity, "Raw planner response");
        assertNotNull("the raw response must still be reachable for debugging", head);
        assertFalse("but not shown until asked for",
                String.join("\n", texts(activity)).contains("zzpersonzz"));

        ((View) head.getParent().getParent()).performClick();
        String joined = String.join("\n", texts(activity));
        assertTrue("expanding shows it on the device", joined.contains("zzpersonzz"));
        assertTrue("and says plainly why it is not in the copies",
                joined.contains("Not included in either copied report"));
        controller.pause().stop().destroy();
    }

    /** No reasoning-summary text has anywhere to come from, and neither report may invent one. */
    @Test public void noThinkingSummaryTextAppearsInEitherReport() {
        ReasoningSummarySupport.recordDisplayed(context,
                ThinkingUpdate.providerSummary("zzsummaryzz comparing the approaches"));
        ActivityController<DiagnosticsActivity> controller = open();
        DiagnosticsActivity activity = controller.get();

        assertFalse(activity.summaryReport().contains("zzsummaryzz"));
        assertFalse(activity.fullReport().contains("zzsummaryzz"));
        // What it does carry is the shape: a source token and a count.
        assertTrue(activity.fullReport().contains("provider-summary"));
        assertTrue(activity.fullReport().contains("Thinking updates received: 1"));
        controller.pause().stop().destroy();
    }

    /** Credentials have never been in diagnostics and must not arrive through the new reports. */
    @Test public void noCredentialsOrTokensAppearInEitherReport() {
        ActivityController<DiagnosticsActivity> controller = open();
        DiagnosticsActivity activity = controller.get();
        String both = activity.summaryReport() + "\n" + activity.fullReport();
        for (String forbidden : new String[]{"Bearer ", "access_token", "refresh_token",
                "api_key", "apiKey", "accessToken", "ChatGPT-Account-ID"}) {
            assertFalse("a credential-shaped field reached diagnostics: " + forbidden,
                    both.contains(forbidden));
        }
        controller.pause().stop().destroy();
    }

    /** Conversation content has never been in diagnostics either. */
    @Test public void noConversationContentAppearsInEitherReport() {
        ConversationStore.clear(context);
        List<AssistantClient.History> history = new ArrayList<>();
        history.add(new AssistantClient.History("user", "zzpromptzz what is my bank balance"));
        history.add(new AssistantClient.History("assistant", "zzanswerzz I cannot see that"));
        ConversationStore.save(context, "c-diag", history);
        PendingRequestStore.create(context, "c-diag", "zzpromptzz what is my bank balance",
                "", "", false, false, Prefs.MODE_BALANCED, false, "");

        ActivityController<DiagnosticsActivity> controller = open();
        DiagnosticsActivity activity = controller.get();
        String both = activity.summaryReport() + "\n" + activity.fullReport();
        assertFalse(both.contains("zzpromptzz"));
        assertFalse(both.contains("zzanswerzz"));
        controller.pause().stop().destroy();
    }

    // ---- the sections still describe reality ----------------------------------------------------------

    @Test public void theThinkingSectionReportsTheNewDefault() {
        ActivityController<DiagnosticsActivity> controller = open();
        String full = controller.get().fullReport();
        assertTrue("with no stored preference the report must say enabled",
                full.contains("Setting: enabled"));

        Prefs.get(context).edit().putBoolean(Prefs.THINKING_UPDATES, false).commit();
        assertTrue("and must follow an explicit opt-out",
                controller.get().fullReport().contains("Setting: disabled"));
        controller.pause().stop().destroy();
    }

    @Test public void theCalendarSectionMatchesTheSharedCalendarReport() {
        ActivityController<DiagnosticsActivity> controller = open();
        // The section body and the standalone report must be the same lines, so the screen and
        // any other caller can never describe the calendar differently.
        assertTrue(CalendarDiagnostics.report(context)
                .endsWith(CalendarDiagnostics.body(context)));
        assertTrue(controller.get().fullReport().contains(CalendarDiagnostics.body(context)));
        controller.pause().stop().destroy();
    }
}
