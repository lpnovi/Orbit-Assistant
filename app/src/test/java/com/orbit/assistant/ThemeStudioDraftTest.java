package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
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
 * The editing model: what changes a draft, what changes the app, and what asks first.
 *
 * <p>Everything here follows from one rule. Editing changes a draft; only Apply changes Orbit. A
 * screen that got this wrong would write a preference on every drag of a colour picker, which is
 * both a performance problem and — worse — a screen you cannot back out of.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class ThemeStudioDraftTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        OrbitThemeStore.clearForTests(context);
        UiPresence.clearForTests();
        OrbitThemeStore.applyActive(context, OrbitTheme.orbitDefault());
    }

    private ActivityController<ThemeStudioActivity> open() {
        return Robolectric.buildActivity(ThemeStudioActivity.class).setup();
    }

    // ---- opening -------------------------------------------------------------------------------

    @Test public void openingTheStudioStartsFromTheAppliedTheme() {
        OrbitThemeStore.applyActive(context, OrbitTheme.builtIn(OrbitTheme.ID_NEBULA));
        ActivityController<ThemeStudioActivity> controller = open();
        ThemeStudioActivity activity = controller.get();

        assertFalse("a freshly opened editor has nothing to lose", activity.isDirty());
        assertTrue(texts(activity).contains("Theme Studio"));
        assertTrue(texts(activity).contains("Make Orbit yours."));
        controller.pause().stop().destroy();
    }

    @Test public void theStudioLaunchesOnADefaultInstall() {
        ActivityController<ThemeStudioActivity> controller = open();
        assertNotNull(controller.get().getWindow().getDecorView());
        assertFalse(controller.get().isFinishing());
        controller.pause().stop().destroy();
    }

    /** Every Orbit preset must be offered by name, or the gallery is not a gallery. */
    @Test public void theGalleryOffersOrbitsPresets() {
        ActivityController<ThemeStudioActivity> controller = open();
        List<String> shown = texts(controller.get());
        for (OrbitTheme preset : OrbitTheme.builtIns()) {
            assertTrue(preset.name + " is missing from the gallery", shown.contains(preset.name));
        }
        controller.pause().stop().destroy();
    }

    @Test public void everyColourOrbitLetsYouEditIsOnTheScreen() {
        ActivityController<ThemeStudioActivity> controller = open();
        List<String> shown = texts(controller.get());
        assertTrue(shown.contains("Accent"));
        assertTrue(shown.contains("Your messages"));
        assertTrue(shown.contains("Orbit's replies"));
        assertTrue(shown.contains("Cards"));
        assertTrue(shown.contains("Background"));
        assertTrue(shown.contains("True black AMOLED background"));
        controller.pause().stop().destroy();
    }

    // ---- draft versus applied --------------------------------------------------------------------

    /**
     * The load-bearing assertion of the whole feature: the preview moves and the app does not.
     */
    @Test public void editingTheDraftDoesNotChangeTheAppliedTheme() {
        ActivityController<ThemeStudioActivity> controller = open();
        ThemeStudioActivity activity = controller.get();
        int surfaceBefore = UiKit.SURFACE;
        String accentBefore = Prefs.get(context).getString(Prefs.ACCENT, "");

        select(activity, OrbitTheme.builtIn(OrbitTheme.ID_EMBER));

        assertTrue("the editor now has something to lose", activity.isDirty());
        assertEquals("nothing may be written until Apply",
                accentBefore, Prefs.get(context).getString(Prefs.ACCENT, ""));
        assertEquals("and the live canvas must not move", surfaceBefore, UiKit.SURFACE);
        assertTrue(OrbitThemeStore.active(context).sameColours(OrbitTheme.orbitDefault()));
        controller.pause().stop().destroy();
    }

    @Test public void thePreviewReflectsTheDraftRatherThanTheAppliedTheme() {
        ActivityController<ThemeStudioActivity> controller = open();
        ThemeStudioActivity activity = controller.get();

        select(activity, OrbitTheme.builtIn(OrbitTheme.ID_MOSS));

        ThemePreviewView view = findPreview(activity.getWindow().getDecorView());
        assertNotNull("the studio must draw a preview", view);
        assertEquals("the preview must show the draft's page colour",
                OrbitTheme.hexTokenColor("#08110E"), previewBackground(view));
        assertEquals("while Orbit itself is unchanged", UiKit.classicBackground(), UiKit.BG);
        controller.pause().stop().destroy();
    }

    @Test public void applyCommitsTheDraft() {
        ActivityController<ThemeStudioActivity> controller = open();
        ThemeStudioActivity activity = controller.get();
        select(activity, OrbitTheme.builtIn(OrbitTheme.ID_TIDE));

        apply(activity);

        assertFalse("after applying there is nothing left to lose", activity.isDirty());
        OrbitTheme active = OrbitThemeStore.active(context);
        assertTrue(active.sameColours(OrbitTheme.builtIn(OrbitTheme.ID_TIDE)));
        assertEquals("Tide", active.name);
        assertEquals("and the live canvas has moved",
                OrbitTheme.hexTokenColor("#141C28"), UiKit.SURFACE);
        controller.pause().stop().destroy();
    }

    @Test public void leavingWithoutApplyingCommitsNothing() {
        ActivityController<ThemeStudioActivity> controller = open();
        ThemeStudioActivity activity = controller.get();
        select(activity, OrbitTheme.builtIn(OrbitTheme.ID_EMBER));

        controller.pause().stop().destroy();

        assertTrue("the applied theme must be untouched",
                OrbitThemeStore.active(context).sameColours(OrbitTheme.orbitDefault()));
        assertEquals(UiKit.classicSurface(), UiKit.SURFACE);
    }

    @Test public void revertReturnsTheDraftToWhatIsApplied() {
        ActivityController<ThemeStudioActivity> controller = open();
        ThemeStudioActivity activity = controller.get();
        select(activity, OrbitTheme.builtIn(OrbitTheme.ID_NEBULA));
        assertTrue(activity.isDirty());

        invoke(activity, "revertDraft");

        assertFalse(activity.isDirty());
        assertTrue(OrbitThemeStore.active(context).sameColours(OrbitTheme.orbitDefault()));
        controller.pause().stop().destroy();
    }

    // ---- dirty state ------------------------------------------------------------------------------

    @Test public void theEditorIsCleanUntilSomethingActuallyChanges() {
        ActivityController<ThemeStudioActivity> controller = open();
        ThemeStudioActivity activity = controller.get();
        assertFalse(activity.isDirty());

        // Selecting the theme that is already applied is not an edit.
        select(activity, OrbitTheme.orbitDefault());
        assertFalse(activity.isDirty());

        select(activity, OrbitTheme.builtIn(OrbitTheme.ID_BLURPLE));
        assertTrue(activity.isDirty());

        select(activity, OrbitTheme.orbitDefault());
        assertFalse("returning to the applied colours is not a change", activity.isDirty());
        controller.pause().stop().destroy();
    }

    /** A dirty editor must refuse the page-moving gesture so the confirmation is reached instead. */
    @Test public void aDirtyEditorStandsAsideFromThePageGesture() {
        assertEquals(OrbitNavigation.Policy.GUARDED,
                OrbitNavigation.policyFor(ThemeStudioActivity.class));
        assertEquals("Theme Studio", OrbitNavigation.labelFor(ThemeStudioActivity.class));
        assertTrue(OrbitNavigation.usesPredictive(ThemeStudioActivity.class));
    }

    // ---- presets -----------------------------------------------------------------------------------

    @Test public void selectingAPresetLoadsItWithoutApplyingIt() {
        ActivityController<ThemeStudioActivity> controller = open();
        ThemeStudioActivity activity = controller.get();

        for (OrbitTheme preset : OrbitTheme.builtIns()) {
            select(activity, preset);
            assertEquals("the draft must be the preset that was selected",
                    preset.id, draft(activity).id);
            assertTrue("and the app must be untouched",
                    OrbitThemeStore.active(context).sameColours(OrbitTheme.orbitDefault()));
        }
        controller.pause().stop().destroy();
    }

    /**
     * Editing a colour while a preset is selected produces a theme of the user's own. The shipped
     * preset is immutable and still exists; the gallery must stop claiming it is what is selected.
     */
    @Test public void editingAPresetRebadgesTheDraftAsTheirOwn() {
        ActivityController<ThemeStudioActivity> controller = open();
        ThemeStudioActivity activity = controller.get();

        select(activity, OrbitTheme.builtIn(OrbitTheme.ID_NEBULA));
        edit(activity, draft(activity).withAccent("#FF8A5B"));

        assertFalse(draft(activity).builtIn);
        assertFalse(OrbitTheme.isBuiltInId(draft(activity).id));
        assertTrue("Nebula itself must be unchanged",
                OrbitTheme.builtIn(OrbitTheme.ID_NEBULA).accent.equals("#8B7CFF"));
        controller.pause().stop().destroy();
    }

    /** Editing back onto a preset's exact colours re-binds to it, so the selection tells the truth. */
    @Test public void landingBackOnAPresetReselectsIt() {
        ActivityController<ThemeStudioActivity> controller = open();
        ThemeStudioActivity activity = controller.get();

        select(activity, OrbitTheme.builtIn(OrbitTheme.ID_NEBULA));
        edit(activity, draft(activity).withAccent("#FF8A5B"));
        assertFalse(OrbitTheme.isBuiltInId(draft(activity).id));

        edit(activity, draft(activity).withAccent("#8B7CFF"));
        assertEquals(OrbitTheme.ID_NEBULA, draft(activity).id);
        controller.pause().stop().destroy();
    }

    /**
     * Selecting an AMOLED preset has to move the AMOLED switch.
     *
     * <p>Otherwise the screen contradicts itself: a true-black preview sitting above a control that
     * says true black is off, and the next thing the user touches decides which one was lying.
     */
    @Test public void selectingAnAmoledPresetMovesTheAmoledSwitch() {
        ActivityController<ThemeStudioActivity> controller = open();
        ThemeStudioActivity activity = controller.get();
        OrbitSwitch amoled = (OrbitSwitch) field(activity, "amoledSwitch");
        assertNotNull(amoled);
        assertFalse(amoled.isChecked());

        select(activity, OrbitTheme.builtIn(OrbitTheme.ID_AMOLED));
        assertTrue("the switch must follow the preset", amoled.isChecked());
        assertTrue(draft(activity).amoled);

        select(activity, OrbitTheme.orbitDefault());
        assertFalse("and follow it back", amoled.isChecked());
        controller.pause().stop().destroy();
    }

    @Test public void savingAPresetStoresItWithoutApplyingIt() {
        ActivityController<ThemeStudioActivity> controller = open();
        ThemeStudioActivity activity = controller.get();
        select(activity, OrbitTheme.builtIn(OrbitTheme.ID_EMBER));

        OrbitTheme saved = OrbitThemeStore.savePreset(context,
                draft(activity).asCustomNamed("My Ember"));

        assertNotNull(saved);
        assertEquals(1, OrbitThemeStore.customPresetCount(context));
        assertTrue("saving must not change what Orbit is drawing",
                OrbitThemeStore.active(context).sameColours(OrbitTheme.orbitDefault()));
        controller.pause().stop().destroy();
    }

    @Test public void aSavedPresetAppearsInTheGalleryOnTheNextOpen() {
        OrbitTheme saved = OrbitThemeStore.savePreset(context,
                OrbitTheme.custom("Studio saved", "#45CCA6", "#1E4038", "#182722",
                        "#16211D", "#08110E", false));
        assertNotNull(saved);

        ActivityController<ThemeStudioActivity> controller = open();
        assertTrue(texts(controller.get()).contains("Studio saved"));
        controller.pause().stop().destroy();
    }

    /** Resetting the active theme is not a reason to throw away somebody's saved work. */
    @Test public void resettingToOrbitDefaultKeepsSavedPresets() {
        OrbitThemeStore.savePreset(context, OrbitTheme.custom("Keep me", "#8B7CFF",
                OrbitTheme.CLASSIC, OrbitTheme.CLASSIC, OrbitTheme.CLASSIC,
                OrbitTheme.CLASSIC, false));
        OrbitThemeStore.applyActive(context, OrbitTheme.builtIn(OrbitTheme.ID_EMBER));

        ActivityController<ThemeStudioActivity> controller = open();
        ThemeStudioActivity activity = controller.get();
        invoke(activity, "resetToOrbitDefault");
        apply(activity);

        assertTrue(OrbitThemeStore.active(context).sameColours(OrbitTheme.orbitDefault()));
        assertEquals("the saved theme must survive", 1, OrbitThemeStore.customPresetCount(context));
        assertNotNull(OrbitThemeStore.customPresets(context).get(0));
        assertEquals("Keep me", OrbitThemeStore.customPresets(context).get(0).name);
        controller.pause().stop().destroy();
    }

    /** Deleting the preset a theme came from does not change what Orbit looks like. */
    @Test public void deletingTheAppliedPresetLeavesTheAppearanceAlone() {
        OrbitTheme saved = OrbitThemeStore.savePreset(context,
                OrbitTheme.custom("Applied then deleted", "#45CCA6", "#1E4038", "#182722",
                        "#16211D", "#08110E", false));
        OrbitThemeStore.applyActive(context, saved);
        int surface = UiKit.SURFACE;

        assertTrue(OrbitThemeStore.deletePreset(context, saved.id));

        assertEquals(surface, UiKit.SURFACE);
        assertTrue(OrbitThemeStore.active(context).sameColours(saved));
    }

    // ---- warnings ----------------------------------------------------------------------------------

    @Test public void aLowContrastDraftIsWarnedAboutButStillAllowed() {
        ActivityController<ThemeStudioActivity> controller = open();
        ThemeStudioActivity activity = controller.get();

        // A mid grey bubble. It sits just under the cutoff that decides the foreground, so Orbit
        // puts light text on it at well under 4.5 to 1 — a real pairing a person could choose.
        edit(activity, OrbitTheme.orbitDefault().withUserBubble("#9A9A9A"));

        OrbitThemeTokens tokens = OrbitThemeTokens.resolve(context, draft(activity));
        assertTrue("the pairing must be reported", tokens.hasLowContrast());
        assertEquals("and only that pairing", 1, tokens.lowContrastChecks().size());
        assertEquals("Your messages", tokens.lowContrastChecks().get(0).label);
        assertTrue("the warning must be on screen", texts(activity).contains("Low contrast"));
        assertTrue("but applying it is still the user's decision", activity.isDirty());

        apply(activity);
        assertFalse("Orbit must not have refused it", activity.isDirty());
        assertEquals("#9A9A9A", OrbitThemeStore.active(context).userBubble);
        controller.pause().stop().destroy();
    }

    @Test public void aReadableDraftShowsNoWarning() {
        ActivityController<ThemeStudioActivity> controller = open();
        ThemeStudioActivity activity = controller.get();
        select(activity, OrbitTheme.builtIn(OrbitTheme.ID_TIDE));
        assertFalse(texts(activity).contains("Low contrast"));
        controller.pause().stop().destroy();
    }

    // ---- accessibility ------------------------------------------------------------------------------

    /** A screen made of colour must not communicate anything by colour alone. */
    @Test public void everyColourControlSaysWhatItIsInWords() {
        ActivityController<ThemeStudioActivity> controller = open();
        List<String> descriptions = descriptions(controller.get().getWindow().getDecorView());
        String all = String.join("\n", descriptions);

        assertTrue(all.contains("Accent"));
        assertTrue("a swatch must never be described only by its hex value",
                all.matches("(?s).*(Violet|violet|blue|Blue|grey|Grey|black|Black|pink|Pink"
                        + "|green|Green|red|Red|purple|Purple|teal|Teal|cyan|Cyan|orange|Orange"
                        + "|amber|Amber|yellow|Yellow|lime|Lime|magenta|Magenta|sky|White).*"));
        assertTrue("the preview must describe itself", all.contains("Theme preview"));
        controller.pause().stop().destroy();
    }

    @Test public void presetSelectionIsAnnouncedAsStateNotOnlyDrawn() {
        OrbitThemeStore.applyActive(context, OrbitTheme.builtIn(OrbitTheme.ID_MOSS));
        ActivityController<ThemeStudioActivity> controller = open();
        String all = String.join("\n", descriptions(controller.get().getWindow().getDecorView()));
        assertTrue(all.contains("Moss, Orbit preset, selected"));
        controller.pause().stop().destroy();
    }

    @Test public void colourNamesAreReadableRatherThanHexadecimal() {
        assertEquals("White", OrbitColorName.of(Color.WHITE));
        assertEquals("Black", OrbitColorName.of(Color.BLACK));
        assertEquals("Grey", OrbitColorName.of(Color.rgb(150, 150, 150)));
        assertEquals("Orbit's own violet must land in the violet family, not indigo or blue",
                "Light violet", OrbitColorName.of(Color.rgb(139, 124, 255)));
        assertEquals("Blue", OrbitColorName.of(Color.rgb(80, 151, 255)));
        assertEquals("Teal", OrbitColorName.of(Color.rgb(69, 204, 166)));
        assertTrue(OrbitColorName.of(Color.rgb(11, 7, 20)).toLowerCase().contains("dark"));
        assertTrue(OrbitColorName.describe("Accent", Color.WHITE).startsWith("Accent, White, #"));
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private OrbitTheme draft(ThemeStudioActivity activity) {
        return (OrbitTheme) field(activity, "draft");
    }

    private void select(ThemeStudioActivity activity, OrbitTheme preset) {
        invoke(activity, "selectPreset", new Class<?>[]{OrbitTheme.class}, preset);
    }

    private void edit(ThemeStudioActivity activity, OrbitTheme next) {
        invoke(activity, "edit", new Class<?>[]{OrbitTheme.class}, next);
    }

    private void apply(ThemeStudioActivity activity) {
        invoke(activity, "applyDraft");
    }

    private void invoke(Activity activity, String name) {
        invoke(activity, name, new Class<?>[0]);
    }

    private void invoke(Activity activity, String name, Class<?>[] types, Object... args) {
        try {
            java.lang.reflect.Method method =
                    ThemeStudioActivity.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(activity, args);
        } catch (Exception e) {
            throw new AssertionError("could not invoke " + name, e);
        }
    }

    private Object field(Activity activity, String name) {
        try {
            java.lang.reflect.Field field = ThemeStudioActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(activity);
        } catch (Exception e) {
            throw new AssertionError("could not read " + name, e);
        }
    }

    private static ThemePreviewView findPreview(View view) {
        if (view instanceof ThemePreviewView) return (ThemePreviewView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ThemePreviewView found = findPreview(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static int previewBackground(View view) {
        android.graphics.drawable.Drawable background = view.getBackground();
        if (background instanceof android.graphics.drawable.GradientDrawable) {
            android.content.res.ColorStateList colors =
                    ((android.graphics.drawable.GradientDrawable) background).getColor();
            if (colors != null) return colors.getDefaultColor();
        }
        return 0;
    }

    private static List<String> texts(Activity activity) {
        List<String> out = new ArrayList<>();
        collectText(activity.getWindow().getDecorView(), out);
        return out;
    }

    private static void collectText(View view, List<String> into) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null) into.add(text.toString());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectText(group.getChildAt(i), into);
        }
    }

    private static List<String> descriptions(View view) {
        List<String> out = new ArrayList<>();
        collectDescriptions(view, out);
        return out;
    }

    private static void collectDescriptions(View view, List<String> into) {
        CharSequence description = view.getContentDescription();
        if (description != null) into.add(description.toString());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectDescriptions(group.getChildAt(i), into);
            }
        }
    }
}
