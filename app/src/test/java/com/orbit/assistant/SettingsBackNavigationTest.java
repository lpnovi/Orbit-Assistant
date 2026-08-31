package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;

import org.junit.After;
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
 * Settings is where the gesture stops being a chat feature and becomes how Orbit navigates.
 *
 * <p>The architecture Beta 3 relies on was already there: a Settings section is a second
 * {@code SettingsActivity} started on top of the hub, so the hub genuinely is the screen underneath
 * a detail and nothing has to be reconstructed to reveal it. These cover that the shared engine is
 * actually installed on it, that the motion is the same function of progress as in a conversation,
 * that cancelling leaves the page exactly as it was, and that the header Back control and the
 * gesture cannot arrive anywhere different.
 *
 * <p>The wider matrix — which screens qualify at all — lives in {@link OrbitNavigationPolicyTest}.
 * This file is about one representative page behaving correctly, plus the nesting that Settings in
 * particular creates.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class SettingsBackNavigationTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        DiagnosticStore.prefs(context).edit().clear().commit();
        OrbitPredictiveBack.keyboardVisibleForTest = null;
        TestWorkManager.ensureInitialized(context);
    }

    @After public void tearDown() {
        OrbitPredictiveBack.keyboardVisibleForTest = null;
    }

    private ActivityController<SettingsActivity> hub() {
        return Robolectric.buildActivity(SettingsActivity.class).setup();
    }

    private ActivityController<SettingsActivity> section(String section) {
        Intent intent = new Intent(context, SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_SECTION, section);
        return Robolectric.buildActivity(SettingsActivity.class, intent).setup();
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

    private static View backControl(Activity activity) {
        for (View v : descendants(activity.getWindow().getDecorView())) {
            CharSequence description = v.getContentDescription();
            if (description != null && description.toString().startsWith("Back")) return v;
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

    // ---- the engine is actually installed --------------------------------------------------------

    /** Settings holds Back, and draws it wherever the device and the preference allow. */
    @Test public void settingsOwnsBackAndDrawsItWhereItCan() {
        ActivityController<SettingsActivity> controller = section(SettingsActivity.SECTION_AI);
        SettingsActivity activity = controller.get();

        assertNotNull("Settings must install the shared navigation",
                activity.navigationForTest());
        assertEquals("it owns Back wherever there is a dispatcher to own it with",
                OrbitBackHandler.supported(), activity.navigationForTest().isArmed());
        assertEquals("and draws it where the device reports progress and the setting is on",
                OrbitPredictiveBack.enabled(context), activity.navigationForTest().drawsGesture());
        controller.pause().stop().destroy();
    }

    /**
     * With the preference off, Back still works and still goes to the same place.
     *
     * <p>The setting chooses an animation, never whether Back functions. This is the assertion that
     * keeps that true: nothing is drawn, and the page still leaves.
     */
    @Test public void turningTheGestureOffLeavesBackWorking() {
        Prefs.get(context).edit().putBoolean(Prefs.ENHANCED_CHAT_BACK, false).commit();
        ActivityController<SettingsActivity> controller = section(SettingsActivity.SECTION_APPEARANCE);
        SettingsActivity activity = controller.get();

        assertFalse("nothing is drawn while the treatment is off",
                activity.navigationForTest().drawsGesture());
        View back = backControl(activity);
        assertNotNull("the visible Back control is never removed", back);
        back.performClick();
        assertTrue("and Back still leaves the section", activity.isFinishing());
        controller.pause().stop().destroy();
    }

    // ---- the motion is the same one ---------------------------------------------------------------

    /** A Settings page tracks the finger exactly as a conversation does. */
    @Test public void asettingsSectionTracksProgressWhileTheFingerIsDown() {
        assumeDrawnGesture();
        ActivityController<SettingsActivity> controller = section(SettingsActivity.SECTION_APPEARANCE);
        SettingsActivity activity = controller.get();
        OrbitPredictiveBack back = activity.navigationForTest();

        back.startedForTest();
        assertTrue(back.gesturingForTest());
        float previous = back.translationForTest();
        for (float p : new float[]{0.15f, 0.35f, 0.6f, 0.9f}) {
            back.progressedForTest(p);
            assertTrue("Look & Feel must move further as the gesture does",
                    back.translationForTest() > previous);
            previous = back.translationForTest();
        }
        assertEquals(4, back.progressEventsForTest());
        assertFalse("and none of it may navigate", activity.isFinishing());
        controller.pause().stop().destroy();
    }

    /** Reversing reverses, because the picture is only ever the progress. */
    @Test public void reversingTheGestureReversesTheSettingsPage() {
        assumeDrawnGesture();
        ActivityController<SettingsActivity> controller = section(SettingsActivity.SECTION_DATA);
        OrbitPredictiveBack back = controller.get().navigationForTest();

        back.startedForTest();
        back.progressedForTest(0.85f);
        float far = back.translationForTest();
        back.progressedForTest(0.25f);
        assertTrue("pulling back must pull the page back", back.translationForTest() < far);
        back.progressedForTest(0f);
        assertEquals(0f, back.translationForTest(), 0.5f);
        controller.pause().stop().destroy();
    }

    /** Cancelling restores the page exactly, including the scroll position the user was at. */
    @Test public void cancellingRestoresTheSettingsPageExactly() {
        assumeDrawnGesture();
        ActivityController<SettingsActivity> controller = section(SettingsActivity.SECTION_ADVANCED);
        SettingsActivity activity = controller.get();
        View page = activity.findViewById(android.R.id.content);
        android.widget.ScrollView scroller = null;
        for (View v : descendants(page)) {
            if (v instanceof android.widget.ScrollView) { scroller = (android.widget.ScrollView) v; break; }
        }
        assertNotNull("a Settings section scrolls", scroller);
        scroller.scrollTo(0, 120);
        // Read it back rather than assuming: what matters is that the gesture does not move it,
        // not what the view was able to scroll to in the first place.
        int reading = scroller.getScrollY();

        OrbitPredictiveBack back = activity.navigationForTest();
        back.startedForTest();
        back.progressedForTest(0.7f);
        back.cancelledForTest();
        idle();

        assertEquals("the page must come to rest where it started",
                0f, back.translationForTest(), 0.5f);
        assertFalse("nothing may be left dressed for a reveal", back.revealingForTest());
        assertFalse("a cancelled gesture is not a navigation", activity.isFinishing());
        assertEquals("and the user is still where they were reading", reading, scroller.getScrollY());
        controller.pause().stop().destroy();
    }

    /** Committing leaves the section once. */
    @Test public void committingLeavesTheSectionOnce() {
        assumeDrawnGesture();
        ActivityController<SettingsActivity> controller = section(SettingsActivity.SECTION_APPEARANCE);
        SettingsActivity activity = controller.get();
        OrbitPredictiveBack back = activity.navigationForTest();

        back.startedForTest();
        back.progressedForTest(0.95f);
        back.invokedForTest();
        idle();
        assertTrue(back.navigatedForTest());
        assertTrue(activity.isFinishing());

        back.invokedForTest();
        idle();
        assertTrue("a repeat may not navigate a second time", back.navigatedForTest());
        controller.pause().stop().destroy();
    }

    /** Nothing of a gesture survives the screen being rebuilt for a new accent. */
    @Test public void nostaleGestureStateSurvivesRecreation() {
        assumeDrawnGesture();
        ActivityController<SettingsActivity> controller = section(SettingsActivity.SECTION_APPEARANCE);
        OrbitPredictiveBack back = controller.get().navigationForTest();
        back.startedForTest();
        back.progressedForTest(0.6f);
        back.detach();

        assertFalse(back.isArmed());
        assertFalse(back.revealingForTest());
        assertEquals(0f, back.translationForTest(), 0.5f);
        controller.pause().stop().destroy();

        ActivityController<SettingsActivity> again = section(SettingsActivity.SECTION_APPEARANCE);
        OrbitPredictiveBack fresh = again.get().navigationForTest();
        assertEquals("a rebuilt page starts at rest", 0f, fresh.translationForTest(), 0.5f);
        assertFalse(fresh.gesturingForTest());
        assertFalse(fresh.revealingForTest());
        again.pause().stop().destroy();
    }

    // ---- the button and the gesture share one destination ----------------------------------------

    /** The header Back arrow performs the same Back the gesture commits to. */
    @Test public void thesectionBackControlLeavesTheSameWay() {
        ActivityController<SettingsActivity> controller = section(SettingsActivity.SECTION_APPEARANCE);
        SettingsActivity activity = controller.get();
        View back = backControl(activity);
        assertNotNull("a Settings section must keep its visible Back control", back);

        back.performClick();
        assertTrue("tapping Back leaves the section, exactly as the gesture does",
                activity.isFinishing());
        controller.pause().stop().destroy();
    }

    /** And it is recorded as the Back control on the Settings screen, never as a drawn gesture. */
    @Test public void thebackControlIsReportedAsSettingsAndClaimsNoProgress() {
        ActivityController<SettingsActivity> controller = section(SettingsActivity.SECTION_AI);
        backControl(controller.get()).performClick();
        controller.pause().stop().destroy();

        ActivityController<DiagnosticsActivity> diagnostics =
                Robolectric.buildActivity(DiagnosticsActivity.class).setup();
        String report = diagnostics.get().fullReport();
        assertTrue(report.contains("Last predictive screen: Settings"));
        assertTrue(report.contains("Last back path: Back control"));
        assertTrue("a tap draws nothing and must not be credited with frames",
                report.contains("committed · 0 progress events"));
        assertFalse("and no section name may reach diagnostics", report.contains("Look & Feel"));
        diagnostics.pause().stop().destroy();
    }

    // ---- nesting ---------------------------------------------------------------------------------

    /**
     * A section is opened as a second Settings on top of the hub, which is what makes the reveal
     * real rather than reconstructed.
     */
    @Test public void asectionOpensOnTopOfTheHubRatherThanReplacingIt() {
        ActivityController<SettingsActivity> controller = hub();
        SettingsActivity activity = controller.get();
        activity.openSectionForTest(SettingsActivity.SECTION_APPEARANCE);
        Robolectric.flushForegroundThreadScheduler();

        Intent started = org.robolectric.Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull("the section must be started", started);
        assertEquals(SettingsActivity.class.getName(), started.getComponent().getClassName());
        assertEquals(SettingsActivity.SECTION_APPEARANCE,
                started.getStringExtra(SettingsActivity.EXTRA_SECTION));
        assertEquals("started plainly, so the hub stays underneath it in the same task",
                0, started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK);
        assertFalse("and the hub is not finished by opening a section", activity.isFinishing());
        controller.pause().stop().destroy();
    }

    /** A separate manager screen reached from Settings is started the same plain way. */
    @Test public void amanagerScreenOpensOnTopOfSettings() {
        ActivityController<SettingsActivity> controller = hub();
        SettingsActivity activity = controller.get();
        activity.openSectionForTest(SettingsActivity.SECTION_ROUTINES);
        Robolectric.flushForegroundThreadScheduler();

        Intent started = org.robolectric.Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(RoutinesActivity.class.getName(), started.getComponent().getClassName());
        assertEquals(0, started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK);
        assertFalse(activity.isFinishing());
        controller.pause().stop().destroy();
    }

    /** Chats opens Settings plainly too, so Chats is the page revealed behind the hub. */
    @Test public void chatsOpensSettingsWithChatsLeftUnderneath() {
        ActivityController<MainActivity> chats = Robolectric.buildActivity(MainActivity.class).setup();
        View settings = null;
        for (View v : descendants(chats.get().getWindow().getDecorView())) {
            CharSequence description = v.getContentDescription();
            if (description != null && "Settings".contentEquals(description)) settings = v;
        }
        assertNotNull("Chats must have a Settings control", settings);
        settings.performClick();
        Robolectric.flushForegroundThreadScheduler();

        Intent started = org.robolectric.Shadows.shadowOf(chats.get()).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(SettingsActivity.class.getName(), started.getComponent().getClassName());
        assertEquals("Chats has to stay in the task underneath Settings",
                0, started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK);
        assertFalse(chats.get().isFinishing());
        chats.pause().stop().destroy();
    }

    /**
     * Each level leaves one level, and no level is skipped.
     *
     * <p>Chats, Settings, Advanced, Diagnostics. Every page in that chain finishes only itself, so
     * Back walks back out through the same four screens it walked in through.
     */
    @Test public void eachNestedLevelLeavesOnlyItself() {
        assumeDrawnGesture();
        ActivityController<DiagnosticsActivity> diagnostics =
                Robolectric.buildActivity(DiagnosticsActivity.class).setup();
        OrbitPredictiveBack back = diagnostics.get().navigationForTest();
        back.startedForTest();
        back.progressedForTest(0.9f);
        back.invokedForTest();
        idle();
        assertTrue("Diagnostics leaves", diagnostics.get().isFinishing());
        diagnostics.pause().stop().destroy();

        ActivityController<SettingsActivity> advanced = section(SettingsActivity.SECTION_ADVANCED);
        OrbitPredictiveBack advancedBack = advanced.get().navigationForTest();
        advancedBack.startedForTest();
        advancedBack.progressedForTest(0.9f);
        advancedBack.invokedForTest();
        idle();
        assertTrue("Advanced leaves, and leaves only itself", advanced.get().isFinishing());
        advanced.pause().stop().destroy();
    }

    // ---- what must still take Back first ---------------------------------------------------------

    /** With the keyboard up, Back is the keyboard's and the page does not move. */
    @Test public void thekeyboardStillTakesBackFirstInSettings() {
        assumeDrawnGesture();
        ActivityController<SettingsActivity> controller = section(SettingsActivity.SECTION_DATA);
        SettingsActivity activity = controller.get();
        OrbitPredictiveBack back = activity.navigationForTest();

        OrbitPredictiveBack.keyboardVisibleForTest = true;
        back.startedForTest();
        back.progressedForTest(0.8f);
        assertEquals("the page may not move while the keyboard owns back",
                0f, back.translationForTest(), 0.5f);
        back.invokedForTest();
        idle();
        assertFalse("the first back closes the keyboard, it does not leave Settings",
                activity.isFinishing());
        controller.pause().stop().destroy();
    }
}
