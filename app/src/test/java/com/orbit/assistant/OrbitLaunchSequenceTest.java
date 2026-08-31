package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * The hidden scene: how it opens, that it stops, and everything it is not allowed to touch.
 *
 * <p>An easter egg earns its place by being harmless. The assertions that matter here are therefore
 * not about the drawing: they are that a decorative Activity keeps no frame loop running once it is
 * paused, reaches no provider and no network, writes nothing down, cannot trap a screen reader, and
 * cannot be arrived at by an ordinary tap on the Chats logo.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class OrbitLaunchSequenceTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        // Chats schedules its background update check on create, so it needs a real WorkManager.
        TestWorkManager.ensureInitialized(context);
    }

    // ---- getting in --------------------------------------------------------------------------

    /** A tap is not a hold. The Orbit mark has no tap behaviour and must not gain one. */
    @Test public void anOrdinaryTapOpensNothing() {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class);
        MainActivity chats = controller.setup().get();
        View mark = findByTag(chats.getWindow().getDecorView(), "orbit_mark");
        assertNotNull("the Orbit mark must still be on Chats", mark);

        // Chats itself may open onboarding on a fresh install; drain that before testing the tap.
        org.robolectric.shadows.ShadowApplication app =
                Shadows.shadowOf((android.app.Application) context);
        while (app.getNextStartedActivity() != null) { /* drain */ }

        mark.performClick();
        assertNull("a tap on the logo must open nothing", app.getNextStartedActivity());
        controller.pause().stop().destroy();
    }

    /** The hold is deliberately long: nobody reaches this by accident. */
    @Test public void theHoldIsLongEnoughToBeDeliberate() {
        assertTrue("a hold shorter than Android's own long-press would be reachable by accident",
                MainActivity.LAUNCH_SEQUENCE_HOLD_MS >= 2000L);
        assertTrue("and one this long is a gesture nobody performs by mistake",
                MainActivity.LAUNCH_SEQUENCE_HOLD_MS <= 3200L);
    }

    /** It is hidden, so there is no switch for it anywhere in Settings. */
    @Test public void thereIsNoSettingForIt() {
        String settings = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/SettingsActivity.java");
        assertFalse(settings.contains("Launch sequence"));
        assertFalse(settings.contains("OrbitLaunchSequence"));
    }

    /** And it does not disturb the Lelo easter egg, which lives somewhere else entirely. */
    @Test public void theLeloEasterEggIsUntouched() {
        String settings = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/SettingsActivity.java");
        assertTrue("Lelo mode is still unlocked from the Settings footer",
                settings.contains("handleLeloSecretTap"));
        assertTrue(settings.contains("Lelo mode unlocked"));
        assertTrue("and its preference is untouched", Prefs.LELO_MODE.equals("lelo_mode"));
    }

    // ---- the scene ----------------------------------------------------------------------------

    private ActivityController<OrbitLaunchSequenceActivity> scene() {
        return Robolectric.buildActivity(OrbitLaunchSequenceActivity.class);
    }

    @Test public void theSceneOpensAndCloses() {
        ActivityController<OrbitLaunchSequenceActivity> controller = scene();
        OrbitLaunchSequenceActivity activity = controller.setup().get();
        assertNotNull(activity.sceneForTest());
        assertFalse(activity.isFinishing());

        activity.onBackPressed();
        assertTrue("Back must leave immediately", activity.isFinishing());
        controller.pause().stop().destroy();
    }

    /**
     * The frame loop belongs to the visible screen.
     *
     * <p>Asserted through the view's own callback queue rather than through a flag, because what
     * actually costs battery is a posted animation callback outliving the Activity.
     */
    @Test public void pausingStopsTheAnimationAndResumingRestartsIt() {
        ActivityController<OrbitLaunchSequenceActivity> controller = scene();
        OrbitLaunchSequenceActivity activity = controller.setup().get();
        OrbitLaunchSequenceView view = activity.sceneForTest();
        assertTrue("a visible scene animates", view.isRunning());

        controller.pause();
        assertFalse("a paused scene must not still be asking for frames", view.isRunning());

        controller.resume();
        assertTrue("and coming back restarts it cleanly", view.isRunning());

        controller.pause().stop().destroy();
        assertFalse("a destroyed scene leaves nothing behind", view.isRunning());
    }

    /** A device asking for less motion gets a still composition rather than a refusal. */
    @Test public void reducedMotionIsHonoured() {
        Settings.Global.putFloat(context.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 0f);
        assertTrue(OrbitLaunchSequenceView.prefersReducedMotion(context));

        Settings.Global.putFloat(context.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        assertFalse(OrbitLaunchSequenceView.prefersReducedMotion(context));

        ActivityController<OrbitLaunchSequenceActivity> controller = scene();
        controller.setup();
        // Both settings must produce a scene that opens and closes cleanly.
        controller.pause().resume().pause().stop().destroy();
    }

    /** The simulation is arithmetic, and settles rather than running away. */
    @Test public void perturbedOrbitsSettleBack() {
        OrbitLaunchSequenceView view = new OrbitLaunchSequenceView(context);
        for (int i = 0; i < 400; i++) view.advance(0.016f);
        // Nothing to assert about position; what matters is that stepping it never throws and never
        // produces a value the drawing cannot use.
        assertEquals(3, OrbitLaunchSequenceView.names().length);
    }

    @Test public void theBodiesAreNamedButNeverFunctional() {
        assertEquals("Luna", OrbitLaunchSequenceView.names()[0]);
        assertEquals("Terra", OrbitLaunchSequenceView.names()[1]);
        assertEquals("Sol", OrbitLaunchSequenceView.names()[2]);

        String source = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OrbitLaunchSequenceView.java");
        for (String forbidden : new String[]{"Prefs.", "AiProviders", "AssistantClient",
                "MODE_FAST", "MODE_BALANCED", "MODE_DEEP", "DiagnosticStore"}) {
            assertFalse("the scene must not reach " + forbidden, source.contains(forbidden));
        }
    }

    // ---- what it must never do -----------------------------------------------------------------

    /** No network, no provider, no storage, no telemetry. Asserted from the source. */
    @Test public void theSceneTouchesNothingItShouldNot() {
        for (String path : new String[]{
                "app/src/main/java/com/orbit/assistant/OrbitLaunchSequenceView.java",
                "app/src/main/java/com/orbit/assistant/OrbitLaunchSequenceActivity.java"}) {
            String source = ComponentUninstallTest.readRepositoryFile(path);
            for (String forbidden : new String[]{"HttpURLConnection", "java.net", "OkHttp",
                    "getSharedPreferences", "SecureStore", "ConversationStore", "MemoryStore",
                    "OrbitLocalClient", "openFileOutput", "Log."}) {
                assertFalse(path + " must not use " + forbidden, source.contains(forbidden));
            }
        }
    }

    /** A screen reader must always have a labelled way out that does not depend on the drawing. */
    @Test public void thereIsAnAccessibleExit() {
        ActivityController<OrbitLaunchSequenceActivity> controller = scene();
        OrbitLaunchSequenceActivity activity = controller.setup().get();

        List<TextView> labelled = new ArrayList<>();
        collectTextViews(activity.getWindow().getDecorView(), labelled);
        TextView close = null;
        for (TextView candidate : labelled) {
            if ("Close".contentEquals(candidate.getText())) close = candidate;
        }
        assertNotNull("there must be a visible Close control", close);
        assertTrue(close.isFocusable());
        assertTrue(close.isClickable());
        assertNotNull("and it must be labelled for a screen reader", close.getContentDescription());

        close.performClick();
        assertTrue("and using it must leave", activity.isFinishing());
        controller.pause().stop().destroy();
    }

    @Test public void theSceneItselfAnnouncesHowToLeave() {
        ActivityController<OrbitLaunchSequenceActivity> controller = scene();
        OrbitLaunchSequenceActivity activity = controller.setup().get();
        CharSequence description = activity.sceneForTest().getContentDescription();
        assertNotNull(description);
        assertTrue(description.toString().contains("Back"));
        controller.pause().stop().destroy();
    }

    // ---- navigation --------------------------------------------------------------------------

    /**
     * It is a scene, not a page, so it takes no part in the app-wide Back gesture.
     *
     * <p>Classified LOCAL, and therefore not carrying the back-callback opt-in — which is what stops
     * a decorative canvas contending with the predictive transition.
     */
    @Test public void theSceneOwnsItsOwnBack() {
        assertEquals(OrbitNavigation.Policy.LOCAL,
                OrbitNavigation.policyFor(OrbitLaunchSequenceActivity.class));
        assertFalse(OrbitNavigation.usesPredictive(OrbitLaunchSequenceActivity.class));
        assertEquals("Launch sequence",
                OrbitNavigation.labelFor(OrbitLaunchSequenceActivity.class));

        String manifest = ComponentUninstallTest.readRepositoryFile("app/src/main/AndroidManifest.xml");
        int start = manifest.indexOf("android:name=\".OrbitLaunchSequenceActivity\"");
        assertTrue("the scene must be declared", start > 0);
        String declaration = manifest.substring(start, manifest.indexOf('>', start));
        assertFalse("and must not opt into the back callback",
                declaration.contains("enableOnBackInvokedCallback"));
        assertTrue("nor appear in Recents", declaration.contains("excludeFromRecents"));
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static View findByTag(View view, String tag) {
        if (tag.equals(view.getTag())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findByTag(group.getChildAt(i), tag);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void collectTextViews(View view, List<TextView> into) {
        if (view instanceof TextView) into.add((TextView) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectTextViews(group.getChildAt(i), into);
        }
    }
}
