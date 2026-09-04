package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The classification matrix: what Back means on every screen Orbit ships.
 *
 * <p>Spreading the gesture across the app is not risky because of the animation. It is risky because
 * the wrong screen can get it — one that hands a result back to a caller, one that asks before
 * discarding, one that exists only to open an Android-owned surface. Those all look like ordinary
 * pages from the outside and are not, and the failure mode is silent: the page slides away and
 * something is lost.
 *
 * <p>So the classification is data, and this is the test that guards it. It pins the class of every
 * screen individually, checks that the whole set is accounted for, and — the assertion that matters
 * most for the future — fails when Orbit gains an Activity that nobody classified. A new screen that
 * is simply forgotten gets no gesture, which is safe; a new screen that is forgotten <i>and</i>
 * opted into the back-callback API in the manifest would not be, and that is checked here too.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class OrbitNavigationPolicyTest {

    /** Screens whose Back returns to the Orbit screen underneath, unconditionally. */
    private static final String[] PREDICTIVE = {
            // Deck persists every edit as it is made, so it is never dirty and Back is
            // unconditional. Its sheets and edit mode are closed by its own handler first.
            "DeckActivity",
            "SettingsActivity", "ChatActivity", "DiagnosticsActivity", "AiProvidersActivity",
            "LocalAiActivity", "CapabilitiesActivity", "MemoryActivity", "AppsActivity",
            "NotificationsActivity", "SavedPlacesActivity", "RemindersActivity",
            "RoutinesActivity", "RoutineTemplatesActivity", "RoutineTriggersActivity",
            "RoutineRunHistoryActivity", "CustomCommandsActivity", "ExtensionsActivity",
            "UpdateActivity", "WhatsNewActivity", "RoadmapActivity"
    };

    /** Editors: the gesture is offered while clean and stands aside once there is work to lose. */
    private static final String[] GUARDED = {
            "RoutineEditorActivity", "TimeTriggerEditorActivity", "LocationTriggerEditorActivity",
            "RoutineBuilderActivity", "CustomCommandEditorActivity", "AppProfileActivity",
            // Theme Studio edits a draft and writes nothing until Apply, so leaving with unapplied
            // colours would discard them silently.
            "ThemeStudioActivity"
    };

    /** Back cancels, denies, or steps within the screen. Deliberately unchanged by Beta 3. */
    private static final String[] LOCAL = {
            "ScreenSelectionActivity", "AttachmentPickerActivity", "CalendarPermissionActivity",
            "OrbitWidgetActionActivity", "OrbitWidgetConfigureActivity", "OnboardingActivity",
            // A decorative full-screen scene. Back closes it; it is not a page to be revealed from.
            "OrbitLaunchSequenceActivity",
            // The external Share doorway. It arrives from another app, draws nothing, and finishes
            // once it has opened a conversation, so there is no Orbit page beneath it to reveal.
            "ShareToOrbitActivity",
            // The external selected-text doorway, which is the same shape as the share bridge.
            "ProcessTextToOrbitActivity",
            // The full-screen attachment viewer. A detail surface rather than a page, and the one
            // screen where the classification is load-bearing beyond appearance: it owns live pan
            // and zoom across the whole window, so an app-wide gesture reading horizontal progress
            // would be competing for the same finger as panning a zoomed photo.
            "AttachmentViewerActivity", "DocumentViewerActivity"
    };

    /** Orbit's root. Back is the task's business and Orbit invents nothing behind it. */
    private static final String[] ROOT = { "MainActivity" };

    private static Class<?> type(String simpleName) throws Exception {
        return Class.forName("com.orbit.assistant." + simpleName);
    }

    // ---- the matrix ------------------------------------------------------------------------------

    @Test public void everyOrdinaryPageIsPredictive() throws Exception {
        for (String name : PREDICTIVE) {
            assertEquals(name + " must navigate back to the page underneath",
                    OrbitNavigation.Policy.PREDICTIVE, OrbitNavigation.policyFor(type(name)));
            assertTrue(name + " must offer the gesture", OrbitNavigation.usesPredictive(type(name)));
        }
    }

    @Test public void everyEditorIsGuarded() throws Exception {
        for (String name : GUARDED) {
            assertEquals(name + " holds unsaved work and must be guarded",
                    OrbitNavigation.Policy.GUARDED, OrbitNavigation.policyFor(type(name)));
            assertTrue(name + " may still offer the gesture while it is clean",
                    OrbitNavigation.usesPredictive(type(name)));
        }
    }

    /**
     * The screens that must not get it, named one by one.
     *
     * <p>Each of these means something other than "go up one page" by Back: cancel a selection, deny
     * a permission, answer the launcher with a result, or step backwards through setup. Animating a
     * page out of the way would misrepresent all four.
     */
    @Test public void transientAndSpecialPurposeScreensAreExcluded() throws Exception {
        for (String name : LOCAL) {
            assertEquals(name + " owns its own Back and must not be given the gesture",
                    OrbitNavigation.Policy.LOCAL, OrbitNavigation.policyFor(type(name)));
            assertFalse(name + " must not offer the gesture",
                    OrbitNavigation.usesPredictive(type(name)));
        }
    }

    @Test public void chatsIsTheRootAndGetsNoInventedParent() throws Exception {
        assertEquals(OrbitNavigation.Policy.ROOT, OrbitNavigation.policyFor(type("MainActivity")));
        assertFalse("Back from Chats belongs to Android, not to Orbit",
                OrbitNavigation.usesPredictive(type("MainActivity")));
    }

    /** An unlisted screen gets nothing, because the safe default is to change no behaviour. */
    @Test public void anunclassifiedScreenIsTreatedAsOwningItsOwnBack() {
        assertEquals(OrbitNavigation.Policy.LOCAL, OrbitNavigation.policyFor(Activity.class));
        assertFalse(OrbitNavigation.usesPredictive(Activity.class));
        assertEquals("Unknown", OrbitNavigation.labelFor(Activity.class));
        assertEquals(OrbitNavigation.Policy.LOCAL, OrbitNavigation.policyFor(null));
    }

    // ---- the set is complete ---------------------------------------------------------------------

    /** Every Activity in the source tree is classified. A forgotten screen fails here. */
    @Test public void everyActivityOrbitShipsIsClassified() throws Exception {
        List<String> unclassified = new ArrayList<>();
        for (String name : sourceActivities()) {
            if (OrbitNavigation.screenFor(type(name)) == null) unclassified.add(name);
        }
        assertTrue("every Orbit Activity needs a Back classification; missing: " + unclassified,
                unclassified.isEmpty());
    }

    /** And the table lists nothing that is not a real screen, so it cannot rot. */
    @Test public void theTableNamesOnlyRealScreens() throws Exception {
        Set<String> source = new HashSet<>(sourceActivities());
        for (String className : OrbitNavigation.all().keySet()) {
            String simple = className.substring(className.lastIndexOf('.') + 1);
            assertTrue(simple + " is classified but is not an Orbit Activity", source.contains(simple));
        }
        assertEquals("the four groups must account for the whole table",
                PREDICTIVE.length + GUARDED.length + LOCAL.length + ROOT.length,
                OrbitNavigation.all().size());
    }

    /** Diagnostics counts from the same table the screens install from. */
    @Test public void theEligibleCountMatchesTheTable() {
        assertEquals(PREDICTIVE.length + GUARDED.length, OrbitNavigation.eligibleScreenCount());
    }

    /** Every classified screen has a category name, and none of them is a placeholder. */
    @Test public void everyScreenHasAReportableCategoryName() {
        Set<String> seen = new HashSet<>();
        for (OrbitNavigation.Screen screen : OrbitNavigation.all().values()) {
            assertNotNull(screen.label);
            assertFalse("a screen category may not be blank", screen.label.trim().isEmpty());
            assertFalse("a screen category may not read as unknown", "Unknown".equals(screen.label));
            assertTrue("screen categories must be distinguishable: " + screen.label,
                    seen.add(screen.label));
        }
    }

    // ---- the manifest agrees with the table ------------------------------------------------------

    /**
     * The opt-in and the migration are the same set.
     *
     * <p>This is the Beta 1 lesson written down. {@code enableOnBackInvokedCallback} stops
     * {@code onBackPressed} being called at all, so an Activity that carries the flag without having
     * been migrated hands Back straight to the platform and skips whatever that screen used to do
     * with it. The flag may therefore appear on exactly the screens this table says are migrated.
     */
    @Test public void themanifestOptsInExactlyTheMigratedScreens() throws Exception {
        String manifest = new String(Files.readAllBytes(
                new File("src/main/AndroidManifest.xml").toPath()), StandardCharsets.UTF_8);

        for (String name : sourceActivities()) {
            boolean optedIn = optsIn(manifest, name);
            boolean migrated = OrbitNavigation.usesPredictive(type(name))
                    // Chats opts in as the root: it registers nothing and Back stays the task's.
                    || "MainActivity".equals(name);
            assertEquals(name + (optedIn
                            ? " carries enableOnBackInvokedCallback but is not migrated"
                            : " is migrated but does not carry enableOnBackInvokedCallback"),
                    migrated, optedIn);
        }
    }

    /** Whether the manifest grants one Activity the back-callback opt-in. */
    private static boolean optsIn(String manifest, String simpleName) {
        int start = manifest.indexOf("android:name=\"." + simpleName + "\"");
        if (start < 0) return false;
        int end = manifest.indexOf('>', start);
        String open = manifest.substring(Math.max(0, manifest.lastIndexOf('<', start)), end);
        return open.contains("enableOnBackInvokedCallback=\"true\"");
    }

    /** Every Activity class in the app source, by simple name. */
    private static List<String> sourceActivities() {
        File dir = new File("src/main/java/com/orbit/assistant");
        File[] files = dir.listFiles((d, name) -> name.endsWith("Activity.java"));
        assertNotNull("the source tree must be readable from the test working directory", files);
        List<String> names = new ArrayList<>();
        for (File file : files) names.add(file.getName().replace(".java", ""));
        assertTrue("Orbit has more than a handful of screens", names.size() > 20);
        return names;
    }

    // ---- the stack a surface outside the app has to build ---------------------------------------

    @Test public void astackFromOutsideTheAppPutsChatsUnderneath() {
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        android.content.Intent target = new android.content.Intent(context, UpdateActivity.class);
        android.content.Intent[] stack = OrbitNavigation.stackFor(context, target);

        assertEquals(2, stack.length);
        assertEquals(MainActivity.class.getName(), stack[0].getComponent().getClassName());
        assertEquals(UpdateActivity.class.getName(), stack[1].getComponent().getClassName());
        assertTrue("Chats has to be able to start the task",
                (stack[0].getFlags() & android.content.Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertTrue("and must reuse an existing Chats rather than duplicate it",
                (stack[0].getFlags() & android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0);
    }

    /** A Settings section reached from outside gets the hub between it and Chats. */
    @Test public void adeepSettingsStackKeepsTheHubInTheMiddle() {
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        android.content.Intent[] stack = OrbitNavigation.stackFor(context,
                new android.content.Intent(context, SettingsActivity.class),
                SettingsActivity.assistantSetupIntent(context));

        assertEquals("Chats, the Settings hub, then the section", 3, stack.length);
        assertEquals(MainActivity.class.getName(), stack[0].getComponent().getClassName());
        assertEquals(SettingsActivity.class.getName(), stack[1].getComponent().getClassName());
        assertEquals(SettingsActivity.class.getName(), stack[2].getComponent().getClassName());
        assertNotNull("only the deepest entry names a section",
                stack[2].getStringExtra(SettingsActivity.EXTRA_SECTION));
        assertEquals(Arrays.asList(true, false),
                Arrays.asList(stack[1].getStringExtra(SettingsActivity.EXTRA_SECTION) == null,
                        stack[2].getStringExtra(SettingsActivity.EXTRA_SECTION) == null));
    }
}
