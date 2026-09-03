package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

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
 * Deck as a screen: what is on it, what happens in edit mode, and how it is reached.
 *
 * <p>The Chats-header assertions matter as much as the Deck ones. Orbit is an assistant first, so
 * the promise is that a user who does not want Deck is left with the header they already had —
 * not the same header with a disabled control in it, and not one with a gap where a control would
 * be. That is checked by counting the header's actual children, not by looking at a flag.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class DeckSurfaceTest {

    private Context context;
    private ActivityController<DeckActivity> controller;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        DeckLayoutStore.clearForTest(context);
        ConversationStore.clear(context);
        OrbitRequestManager.resetForTest();
        OrbitRequestManager.setWorkCanceller(name -> {});
        TestWorkManager.ensureInitialized(context);
        DiagnosticStore.prefs(context).edit().clear().commit();
        // Robolectric reports no hardware by default, and the flashlight tile correctly refuses to
        // claim a torch on a device without one, so the feature is declared for these tests.
        shadowOf(context.getPackageManager()).setSystemFeature(
                android.content.pm.PackageManager.FEATURE_CAMERA_FLASH, true);
    }

    @After public void tearDown() {
        if (controller != null) controller.pause().stop().destroy();
        controller = null;
    }

    private DeckActivity deck() {
        controller = Robolectric.buildActivity(DeckActivity.class).setup();
        return controller.get();
    }

    private static List<DeckTileView> tilesIn(DeckActivity activity) {
        List<DeckTileView> out = new ArrayList<>();
        for (View child : activity.gridForTest().orderedChildren()) {
            if (child instanceof DeckTileView) out.add((DeckTileView) child);
        }
        return out;
    }

    private static List<View> allViews(View root) {
        List<View> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(View view, List<View> into) {
        into.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), into);
        }
    }

    // ---- the default surface -----------------------------------------------------------------------

    @Test public void deckOpensOnTheDefaultTiles() {
        DeckActivity activity = deck();
        List<DeckTileView> tiles = tilesIn(activity);

        assertEquals(DeckLayoutStore.defaults().size(), tiles.size());
        assertEquals("New chat", tiles.get(0).titleText().toString());
        assertEquals(View.GONE, activity.emptyForTest().getVisibility());
    }

    @Test public void theCustomizationHintAppearsOnlyUntilTheDeckIsTouched() {
        DeckActivity activity = deck();
        assertEquals("a fresh Deck explains itself once",
                View.VISIBLE, activity.firstRunHintForTest().getVisibility());

        DeckLayoutStore.add(context, DeckTile.of(DeckTileRegistry.TYPE_SETTINGS, DeckTile.Size.STANDARD));
        activity.refreshForTest();
        assertEquals("and never again once it has been customised",
                View.GONE, activity.firstRunHintForTest().getVisibility());
    }

    @Test public void withNothingToSuggestThereIsNoSuggestedSection() {
        DeckActivity activity = deck();
        assertEquals(View.GONE, activity.suggestedForTest().getVisibility());
        for (View view : allViews(activity.getWindow().getDecorView())) {
            if (!(view instanceof TextView)) continue;
            CharSequence text = ((TextView) view).getText();
            assertFalse("there must be no empty Suggested placeholder",
                    text != null && text.toString().toLowerCase().contains("no suggestions"));
        }
    }

    @Test public void aTileIsOneAccessibleNodeThatSaysWhatItIs() {
        DeckActivity activity = deck();
        DeckTileView newChat = tilesIn(activity).get(0);

        String description = String.valueOf(newChat.getContentDescription());
        assertTrue("it names itself", description.contains("New chat"));
        assertTrue("and says what kind of thing it is", description.contains("Orbit shortcut"));
        assertTrue(newChat.isFocusable());
        assertEquals("the icon is decorative, not a second node",
                View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                newChat.iconView().getImportantForAccessibility());
    }

    private void setAnimations(boolean enabled) {
        android.provider.Settings.Global.putFloat(context.getContentResolver(),
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, enabled ? 1f : 0f);
    }

    @Test public void normalModeLongPressPicksUpInTheSameGesture() {
        DeckActivity activity = deck();
        DeckTileView tile = tilesIn(activity).get(0);
        long downAt = SystemClock.uptimeMillis();
        tile.dispatchTouchEvent(MotionEvent.obtain(downAt, downAt,
                MotionEvent.ACTION_DOWN, 20f, 20f, 0));
        shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(600));

        assertTrue("the first hold enters Edit", activity.editingForTest());
        assertTrue("and that same still-down gesture owns the tile",
                activity.gridForTest().isDragging());

        tile.dispatchTouchEvent(MotionEvent.obtain(downAt, downAt + 650,
                MotionEvent.ACTION_CANCEL, 20f, 20f, 0));
        assertFalse(activity.gridForTest().isDragging());
    }

    /**
     * Live state is spoken, so a flashlight that is on says so rather than only showing it.
     *
     * <p>Run with motion off, because a changing state cross-fades: with animations on the new text
     * lands when the fade finishes, and asserting mid-transition would be asserting the animation
     * rather than the state.
     */
    @Test public void liveStateReachesTheSpokenDescription() {
        setAnimations(false);
        DeckActivity activity = deck();
        activity.applyLiveForTest(new DeckTileResolver.LiveState(true, null, ""));

        boolean found = false;
        for (DeckTileView tile : tilesIn(activity)) {
            if (!DeckTileRegistry.TYPE_FLASHLIGHT.equals(tile.tile().type)) continue;
            found = true;
            assertEquals("On", tile.subtitleText().toString());
            assertTrue(String.valueOf(tile.getContentDescription()).contains("On"));
        }
        assertTrue("the default Deck has a flashlight tile", found);
        setAnimations(true);
    }

    /**
     * With motion off, everything still works and nothing is left mid-transition.
     *
     * <p>Reduced motion must remove animation, not function: the same tiles, the same state, the
     * same edit affordances, and no view stranded at zero opacity or a scaled-down size by an
     * entrance that never ran.
     */
    @Test public void reducedMotionKeepsEveryFunctionAndLeavesNothingHalfAnimated() {
        setAnimations(false);
        try {
            DeckActivity activity = deck();
            List<DeckTileView> tiles = tilesIn(activity);
            assertEquals(DeckLayoutStore.defaults().size(), tiles.size());

            activity.applyLiveForTest(new DeckTileResolver.LiveState(false, true, "Music"));
            activity.setEditingForTest(true);
            for (DeckTileView tile : tiles) {
                assertTrue("edit affordances still appear", tile.removeVisible());
                assertEquals("no tile is left invisible", 1f, tile.getAlpha(), 0.001f);
            }
            activity.setEditingForTest(false);
            for (DeckTileView tile : tiles) {
                assertFalse(tile.removeVisible());
                assertEquals(1f, tile.getAlpha(), 0.001f);
            }
        } finally {
            setAnimations(true);
        }
    }

    /** Unknown state is never dressed up as "Off". */
    @Test public void unreadableStateIsNotInvented() {
        DeckActivity activity = deck();
        activity.applyLiveForTest(DeckTileResolver.LiveState.unknown());

        for (DeckTileView tile : tilesIn(activity)) {
            if (!DeckTileRegistry.TYPE_MEDIA.equals(tile.tile().type)) continue;
            String subtitle = tile.subtitleText().toString();
            assertFalse("no session is readable, so it must not claim one",
                    subtitle.contains("Playing") || subtitle.contains("Paused"));
        }
    }

    // ---- edit mode ---------------------------------------------------------------------------------

    @Test public void editModeShowsARemoveAffordanceOnEveryTile() {
        DeckActivity activity = deck();
        for (DeckTileView tile : tilesIn(activity)) {
            assertFalse("nothing to remove while browsing", tile.removeVisible());
        }

        activity.setEditingForTest(true);
        assertTrue(activity.editingForTest());
        for (DeckTileView tile : tilesIn(activity)) {
            assertTrue("every tile can be removed while editing", tile.removeVisible());
            assertTrue(tile.isEditing());
        }
    }

    @Test public void leavingEditModePutsTheTilesBack() {
        DeckActivity activity = deck();
        activity.setEditingForTest(true);
        activity.setEditingForTest(false);

        assertFalse(activity.editingForTest());
        for (DeckTileView tile : tilesIn(activity)) {
            assertFalse(tile.removeVisible());
        }
    }

    /** Suggested is a browsing affordance, so it stands aside while the Deck is being arranged. */
    @Test public void suggestedIsHiddenWhileEditing() {
        DeckActivity activity = deck();
        activity.setEditingForTest(true);
        assertEquals(View.GONE, activity.suggestedForTest().getVisibility());
    }

    @Test public void removingEveryTileLeavesThePolishedEmptyStateAndNotTheDefaults() {
        DeckActivity activity = deck();
        for (DeckTile tile : DeckLayoutStore.layout(context)) {
            DeckLayoutStore.remove(context, tile.instanceId);
        }
        activity.refreshForTest();

        assertTrue(tilesIn(activity).isEmpty());
        assertEquals(View.VISIBLE, activity.emptyForTest().getVisibility());

        boolean prompt = false;
        for (View view : allViews(activity.emptyForTest())) {
            if (!(view instanceof TextView)) continue;
            if ("Build your Deck".contentEquals(((TextView) view).getText())) prompt = true;
        }
        assertTrue("the empty state introduces itself", prompt);

        // And crucially, reopening does not quietly restore the defaults over the user's choice.
        controller.pause().stop().destroy();
        controller = Robolectric.buildActivity(DeckActivity.class).setup();
        assertTrue("an emptied Deck stays empty", tilesIn(controller.get()).isEmpty());
    }

    @Test public void aResetPutsTheDefaultDeckBack() {
        DeckLayoutStore.save(context, new ArrayList<>());
        DeckActivity activity = deck();
        assertTrue(tilesIn(activity).isEmpty());

        DeckLayoutStore.reset(context);
        activity.refreshForTest();
        assertEquals(DeckLayoutStore.defaults().size(), tilesIn(activity).size());
    }

    /** Resetting the Deck is a Deck operation and touches nothing else Orbit stores. */
    @Test public void resettingTheDeckLeavesTheRestOfOrbitAlone() throws Exception {
        RoutineStore.Routine routine = RoutineStore.create("Goodnight", oneAction());
        RoutineStore.upsert(context, routine);
        Prefs.get(context).edit().putString(Prefs.ACCENT, "mint").commit();

        DeckLayoutStore.reset(context);

        assertNotNull("Routines survive", RoutineStore.findById(context, routine.id));
        assertEquals("preferences survive", "mint",
                Prefs.get(context).getString(Prefs.ACCENT, ""));
    }

    // ---- the Chats entry point ---------------------------------------------------------------------

    private ActivityController<MainActivity> chats() {
        return Robolectric.buildActivity(MainActivity.class).setup();
    }

    private static List<View> deckControlsIn(MainActivity activity) {
        List<View> out = new ArrayList<>();
        for (View view : allViews(activity.getWindow().getDecorView())) {
            CharSequence description = view.getContentDescription();
            if (description != null && "Open Orbit Deck".contentEquals(description)) out.add(view);
        }
        return out;
    }

    @Test public void chatsCarriesExactlyOneDeckControlByDefault() {
        ActivityController<MainActivity> chats = chats();
        assertTrue("the shortcut is on by default", Prefs.deckShortcut(context));

        List<View> controls = deckControlsIn(chats.get());
        assertEquals("exactly one Deck control", 1, controls.size());
        assertEquals(View.VISIBLE, controls.get(0).getVisibility());
        chats.pause().stop().destroy();
    }

    @Test public void theDeckControlOpensDeck() {
        ActivityController<MainActivity> chats = chats();
        deckControlsIn(chats.get()).get(0).performClick();

        Intent started = shadowOf(chats.get()).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(DeckActivity.class.getName(), started.getComponent().getClassName());
        chats.pause().stop().destroy();
    }

    /**
     * Turned off, the control does not exist rather than being hidden.
     *
     * <p>A GONE view is still a view: it sits in the hierarchy, it can be found, and depending on
     * the layout it can still reserve space. The promise is that Chats is the screen it was before
     * Deck existed, so the assertion is that no such view is present at all.
     */
    @Test public void withTheShortcutOffThereIsNoDeckControlAtAll() {
        Prefs.get(context).edit().putBoolean(Prefs.DECK_SHORTCUT, false).commit();
        ActivityController<MainActivity> chats = chats();

        assertTrue("not hidden, not disabled, not present",
                deckControlsIn(chats.get()).isEmpty());
        chats.pause().stop().destroy();
    }

    @Test public void theShortcutAppearsAndDisappearsOnReturnFromSettings() {
        Prefs.get(context).edit().putBoolean(Prefs.DECK_SHORTCUT, false).commit();
        ActivityController<MainActivity> chats = chats();
        assertTrue(deckControlsIn(chats.get()).isEmpty());

        chats.pause();
        Prefs.get(context).edit().putBoolean(Prefs.DECK_SHORTCUT, true).commit();
        chats.resume();
        assertEquals("it comes back without restarting Orbit",
                1, deckControlsIn(chats.get()).size());

        chats.pause();
        Prefs.get(context).edit().putBoolean(Prefs.DECK_SHORTCUT, false).commit();
        chats.resume();
        assertTrue("and goes away again", deckControlsIn(chats.get()).isEmpty());
        chats.pause().stop().destroy();
    }

    /** Turning the shortcut off must never be able to strand Deck. */
    @Test public void deckStaysReachableFromSettingsWithTheShortcutOff() {
        Prefs.get(context).edit().putBoolean(Prefs.DECK_SHORTCUT, false).commit();
        String settings = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/SettingsActivity.java");
        assertTrue("Settings must offer a way into Deck",
                settings.contains("DeckActivity.class"));
    }

    /** The two Deck preferences are independent of each other. */
    @Test public void theShortcutAndSuggestionPreferencesAreSeparate() {
        assertTrue(Prefs.deckShortcut(context));
        assertTrue(Prefs.deckSuggestions(context));

        Prefs.get(context).edit().putBoolean(Prefs.DECK_SHORTCUT, false).commit();
        assertFalse(Prefs.deckShortcut(context));
        assertTrue("turning the shortcut off must not touch suggestions",
                Prefs.deckSuggestions(context));

        Prefs.get(context).edit().putBoolean(Prefs.DECK_SUGGESTIONS, false).commit();
        assertFalse(Prefs.deckSuggestions(context));

        // And neither one may disturb the layout.
        assertEquals(DeckLayoutStore.defaults().size(), DeckLayoutStore.layout(context).size());
    }

    // ---- navigation --------------------------------------------------------------------------------

    @Test public void deckIsAnOrdinaryPredictiveOrbitPage() {
        assertEquals(OrbitNavigation.Policy.PREDICTIVE,
                OrbitNavigation.policyFor(DeckActivity.class));
        assertEquals("Deck", OrbitNavigation.labelFor(DeckActivity.class));
        assertTrue(OrbitNavigation.usesPredictive(DeckActivity.class));
    }

    /** Deck is Orbit's own screen and is never opened from outside the app. */
    @Test public void deckIsNotExported() {
        String manifest = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/AndroidManifest.xml");
        int start = manifest.indexOf("android:name=\".DeckActivity\"");
        assertTrue("Deck must be declared", start > 0);
        String declaration = manifest.substring(start, manifest.indexOf('>', start));
        assertTrue(declaration.contains("android:exported=\"false\""));
        assertTrue("and it opts into the back callback like every migrated page",
                declaration.contains("android:enableOnBackInvokedCallback=\"true\""));
    }

    private static List<AssistantReply.Action> oneAction() {
        List<AssistantReply.Action> actions = new ArrayList<>();
        try {
            actions.add(new AssistantReply.Action(RoutineActionCatalog.FLASHLIGHT,
                    new org.json.JSONObject().put("on", true), false));
        } catch (Exception ignored) {}
        return actions;
    }
}
