package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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

    // ---- copying one section --------------------------------------------------------------------

    /** Every Copy control on the screen, in the order they appear. */
    private static List<View> copyControls(Activity activity) {
        List<View> out = new ArrayList<>();
        for (View v : descendants(activity.getWindow().getDecorView())) {
            CharSequence description = v.getContentDescription();
            if (description != null && description.toString().startsWith("Copy ")
                    && description.toString().endsWith(" diagnostics")) {
                out.add(v);
            }
        }
        return out;
    }

    private static View copyControlFor(Activity activity, String title) {
        for (View v : copyControls(activity)) {
            if (("Copy " + title + " diagnostics").contentEquals(v.getContentDescription())) return v;
        }
        return null;
    }

    /** The clickable header row a section title sits in, which is what toggles disclosure. */
    private static View headerRowFor(Activity activity, String title) {
        View label = viewWithText(activity, title);
        for (View v = label; v != null; ) {
            CharSequence description = v.getContentDescription();
            if (description != null && description.toString().startsWith(title)) return v;
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return null;
    }

    private String clipboard() {
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                context.getSystemService(Context.CLIPBOARD_SERVICE);
        android.content.ClipData clip = cm == null ? null : cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return "";
        CharSequence text = clip.getItemAt(0).getText();
        return text == null ? "" : text.toString();
    }

    /**
     * Overview and every normal section can be copied on their own.
     *
     * <p>The complaint this answers is a real one: most visits to this screen are about a single
     * section, and until now the only way to send one was to copy the whole report and cut it down
     * by hand.
     */
    @Test public void everyNormalSectionHasItsOwnCopyControl() {
        seedRequestFlow();
        ActivityController<DiagnosticsActivity> controller = open();
        Activity activity = controller.get();

        for (String title : new String[]{"Overview", "Request flow", "Thinking updates",
                "Auto routing", "Screen & app context", "Memory", "Calendar", "Orbit Local",
                "Routines", "Gestures", "Advanced"}) {
            assertNotNull(title + " must be copyable on its own",
                    copyControlFor(activity, title));
        }
        controller.pause().stop().destroy();
    }

    /** What a section's Copy puts on the clipboard is that section, titled, and nothing else. */
    @Test public void copyingASectionCopiesThatSectionAlone() {
        seedRequestFlow();
        ActivityController<DiagnosticsActivity> controller = open();
        Activity activity = controller.get();

        copyControlFor(activity, "Gestures").performClick();
        String copied = clipboard();

        assertTrue("it must say which section this is",
                copied.startsWith("Orbit Diagnostics — Gestures"));
        assertTrue("and carry that section's own lines",
                copied.contains("Swipe back to Chats:"));
        assertFalse("a neighbouring section may not come with it",
                copied.contains("Routines"));
        assertFalse(copied.contains("Requests accepted"));
        assertFalse(copied.contains("Orbit Local"));
        controller.pause().stop().destroy();
    }

    /**
     * The clipboard and the screen read from one expression, so they cannot drift apart.
     *
     * <p>Asserted against the body the screen actually shows rather than against a second copy of
     * the formatting, which is the only version of this test that would catch the two diverging.
     */
    @Test public void whatIsCopiedIsWhatTheSectionShows() {
        seedRequestFlow();
        ActivityController<DiagnosticsActivity> controller = open();
        Activity activity = controller.get();

        headerRowFor(activity, "Request flow").performClick();
        String shown = null;
        for (String text : texts(activity)) {
            if (text.contains("Accepted submissions: 12")) shown = text;
        }
        assertNotNull("the expanded section must be on screen", shown);

        copyControlFor(activity, "Request flow").performClick();
        assertTrue("the clipboard must carry exactly the body that was displayed",
                clipboard().contains(shown));
        controller.pause().stop().destroy();
    }

    /** Copying does not require reading first, and does not expand anything. */
    @Test public void copyingWorksCollapsedAndLeavesTheSectionCollapsed() {
        seedRequestFlow();
        ActivityController<DiagnosticsActivity> controller = open();
        Activity activity = controller.get();

        View head = headerRowFor(activity, "Gestures");
        assertNotNull(head);
        assertTrue("the section starts collapsed",
                head.getContentDescription().toString().endsWith("collapsed"));

        copyControlFor(activity, "Gestures").performClick();
        assertTrue("copying carries the section even though nobody opened it",
                clipboard().contains("Swipe back to Chats:"));
        assertTrue("and tapping Copy must not also expand it",
                headerRowFor(activity, "Gestures").getContentDescription()
                        .toString().endsWith("collapsed"));
        controller.pause().stop().destroy();
    }

    /** A section with nothing in it yet copies the same answer it shows. */
    @Test public void anemptySectionCopiesAClearStandIn() {
        // The stand-in the screen shows for a section with nothing in it is the stand-in the
        // clipboard gets, because one expression produces both.
        assertEquals("Orbit Diagnostics — Example\nNothing recorded yet.",
                DiagnosticsActivity.sectionReport("Example", ""));
        assertEquals("whitespace is not content either",
                "Orbit Diagnostics — Example\nNothing recorded yet.",
                DiagnosticsActivity.sectionReport("Example", "\n   \n"));
        assertEquals("and a section that does have lines keeps every one of them, under its title",
                "Orbit Diagnostics — Example\n\n  Two: 2",
                DiagnosticsActivity.sectionReport("Example", "\n  Two: 2"));
    }

    /** Overview copies Overview, and Copy summary remains the different thing it always was. */
    @Test public void overviewCopiesOverviewAndNotTheSupportSummary() {
        seedRequestFlow();
        ActivityController<DiagnosticsActivity> controller = open();
        DiagnosticsActivity activity = controller.get();

        copyControlFor(activity, "Overview").performClick();
        String copied = clipboard();
        assertTrue(copied.startsWith("Orbit Diagnostics — Overview"));
        assertTrue(copied.contains("Orbit version:"));
        assertFalse("Overview is not the support summary and must not grow into it",
                copied.contains("Requests: 12 accepted"));
        assertTrue("while the support summary still carries the counters it exists for",
                activity.summaryReport().contains("Requests: 12 accepted"));
        controller.pause().stop().destroy();
    }

    /**
     * The raw planner block keeps its own deliberate control and gains no generic one.
     *
     * <p>It can contain the user's own routine wording, and copying is the step that sends it
     * somewhere else. Folding it into the section-copy behaviour would have quietly undone the
     * privacy boundary Beta 2 of v0.7.7.8 drew.
     */
    @Test public void therawPlannerBlockIsExcludedFromSectionCopying() {
        seedRoutinePlan();
        ActivityController<DiagnosticsActivity> controller = open();
        Activity activity = controller.get();

        assertNull("the raw planner block may not gain a generic Copy control",
                copyControlFor(activity, "Raw planner response"));
        for (View control : copyControls(activity)) {
            control.performClick();
            assertFalse("no section's copy may carry the user's own wording",
                    clipboard().contains("zzpersonzz"));
        }

        headerRowFor(activity, "Raw planner response").performClick();
        View deliberate = viewWithText(activity, "Copy raw planner response");
        assertNotNull("its own explicit control must remain", deliberate);
        deliberate.performClick();
        assertTrue("and must still be the one way to copy it",
                clipboard().contains("zzpersonzz"));
        controller.pause().stop().destroy();
    }

    /** Each Copy control says what it copies, for anyone who cannot see the header it sits in. */
    @Test public void everyCopyControlIsDescribed() {
        ActivityController<DiagnosticsActivity> controller = open();
        List<View> controls = copyControls(controller.get());
        assertTrue("there must be one per section plus Overview", controls.size() >= 11);
        for (View control : controls) {
            String description = control.getContentDescription().toString();
            assertTrue(description.startsWith("Copy "));
            assertTrue(description.endsWith(" diagnostics"));
            assertTrue("a description has to name something", description.length() > 16);
        }
        controller.pause().stop().destroy();
    }
}
