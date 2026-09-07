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
 * "Make Orbit yours", after Theme Studio.
 *
 * <p>This page predates Theme Studio and had grown three colour controls of its own: an accent
 * picker and two bubble-colour pickers, each writing the same preference keys the editor writes.
 * That was two authorities over one appearance, and the one somebody meets during their first five
 * minutes with Orbit was the one nobody would think to check.
 *
 * <p>So the value of this file is the word <em>canonical</em>. Onboarding must not own a colour
 * catalogue, must not keep theme state of its own, and must leave the appearance in a condition
 * Theme Studio describes truthfully the moment it is opened. The AMOLED case is the one that
 * decides whether that is really true: Orbit Default with true black on <em>is</em> Orbit AMOLED
 * and has to say so, while Nebula with true black on is not any shipped preset and must not claim
 * to be one.
 *
 * <p>The rest is about not breaking a first run: seven steps, the same page in the same place, the
 * font and text size still there, and nothing that restarts the Activity and loses somebody's
 * progress.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class OnboardingAppearanceTest {

    /** "Make Orbit yours" is step 6 of 7, and stays there. */
    private static final int PERSONALIZE_STEP = 5;

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        OrbitThemeStore.clearForTests(context);
    }

    private ActivityController<OnboardingActivity> personalize() {
        Prefs.get(context).edit().putInt("onboarding_current_step", PERSONALIZE_STEP).commit();
        ActivityController<OnboardingActivity> controller =
                Robolectric.buildActivity(OnboardingActivity.class);
        controller.setup();
        return controller;
    }

    private static String source() {
        return ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OnboardingActivity.java");
    }

    // ---- the flow is unchanged ---------------------------------------------------------------------

    /** Still seven steps, and the personalization page is still the sixth of them. */
    @Test public void thepersonalizationStepStaysWhereItWas() {
        String text = textOf(personalize().get());
        assertTrue("the step counter still reads six of seven", text.contains("6 of 7"));
        assertTrue("and the page is still the personalization page",
                text.contains("Make Orbit yours"));

        String source = source();
        assertTrue(source.contains("(step + 1) + \" of 7\""));
        assertTrue("no eighth page was added for Theme Studio",
                source.contains("else if (step == 5) buildPersonalize(page);"));
        assertTrue(source.contains("else if (step == 6) buildStarterRoutine(page);"));
        assertFalse(source.contains("buildThemeStudio"));
    }

    /** Resumable setup still resumes where it was left. */
    @Test public void onboardingStillResumesOnThisStep() {
        OnboardingState.setCurrentStep(context, PERSONALIZE_STEP);
        assertEquals(PERSONALIZE_STEP, OnboardingState.currentStep(context));
        Activity activity = personalize().get();
        assertTrue(textOf(activity).contains("Make Orbit yours"));
        assertEquals("rendering the page must not move the stored step",
                PERSONALIZE_STEP, OnboardingState.currentStep(context));
    }

    /**
     * Changing the appearance rebuilds the page rather than recreating the Activity.
     *
     * <p>A recreate here would restart the Activity underneath somebody mid-setup. The page has
     * always rebuilt itself instead, and that is what keeps the step, the progress dots and the
     * expanded-provider state intact while the colours change around them.
     */
    @Test public void changingAppearanceNeverRestartsSetup() {
        String source = source();
        int personalizeAt = source.indexOf("private void buildPersonalize");
        int nextSection = source.indexOf("private void buildStarterRoutine");
        String page = source.substring(personalizeAt, nextSection);
        assertFalse("the personalization step must never call recreate()",
                page.contains("recreate()"));
        assertTrue("it re-renders in place", source.contains("private void applyOnboardingAppearance"));
        assertTrue(source.contains("UiKit.notifyAppearanceChanged(this);"));
    }

    // ---- the duplicated colour controls are gone -----------------------------------------------------

    /** The three controls Theme Studio now owns are no longer part of first-run setup. */
    @Test public void theduplicatedColourControlsAreGone() {
        String text = textOf(personalize().get());
        assertFalse("the standalone Accent selector is gone", text.contains("Accent"));
        assertFalse("the user bubble selector is gone", text.contains("Your bubbles"));
        assertFalse("the Orbit bubble selector is gone", text.contains("Orbit bubbles"));
        assertFalse("and the section that held them is gone with them",
                text.contains("Conversation style"));

        String source = source();
        assertFalse("no onboarding control writes the accent key directly",
                source.contains("Prefs.ACCENT"));
        assertFalse(source.contains("Prefs.USER_BUBBLE_COLOR"));
        assertFalse(source.contains("Prefs.ASSISTANT_BUBBLE_COLOR"));
        assertFalse("nor the AMOLED key", source.contains("Prefs.AMOLED_MODE"));
        assertFalse("and the colour selector helper is gone rather than left unused",
                source.contains("appearanceColorSelector"));
    }

    /** Removing them from setup did not remove them from the editor that owns them. */
    @Test public void themeStudioKeepsEverythingOnboardingDropped() {
        String studio = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/ThemeStudioActivity.java");
        assertTrue(studio.contains("Accent"));
        assertTrue(studio.contains("amoledSwitch"));
        assertTrue("presets, custom themes and the live preview all stay",
                studio.contains("presetCard") && studio.contains("preview.render"));
        assertTrue(studio.contains("OrbitThemeStore.savePreset"));
    }

    // ---- the preset picker ---------------------------------------------------------------------------

    /** The presets offered are Orbit's own built-ins, not a second catalogue. */
    @Test public void thepresetsComeFromOrbitsOwnBuiltIns() {
        List<OrbitTheme> offered = OrbitThemeStore.onboardingPresets();
        assertTrue("a small, readable set", offered.size() >= 4 && offered.size() <= 6);
        for (OrbitTheme preset : offered) {
            assertTrue(preset.name + " must be a shipped preset", preset.builtIn);
            assertNotNull(OrbitTheme.builtIn(preset.id));
            assertFalse("an AMOLED variant is a duplicate of another preset here", preset.amoled);
        }
        assertEquals("Orbit's own default comes first", OrbitTheme.ID_DEFAULT, offered.get(0).id);

        String source = source();
        assertTrue("read from the canonical list",
                source.contains("OrbitThemeStore.onboardingPresets()"));
        assertTrue("and drawn through the canonical resolver",
                source.contains("OrbitThemeTokens.resolve(this, preset.withAmoled("));
        assertFalse("onboarding never constructs a theme of its own",
                source.contains("new OrbitTheme(") || source.contains("OrbitTheme.custom("));
        assertFalse("and holds no theme colours of its own",
                source.contains("\"#"));
    }

    /** Every preset is drawn and can be chosen. */
    @Test public void everyPresetIsOfferedAndSelectable() {
        Activity activity = personalize().get();
        String text = textOf(activity);
        assertTrue(text.contains("Choose a preset"));
        for (OrbitTheme preset : OrbitThemeStore.onboardingPresets()) {
            assertNotNull("every preset needs a card: " + preset.name,
                    findByDescriptionPrefix(activity.getWindow().getDecorView(),
                            preset.name + ", Orbit preset"));
        }
    }

    /** Choosing a preset applies it through the canonical store, and says so on the page. */
    @Test public void choosingApresetAppliesItCanonically() {
        Activity activity = personalize().get();
        assertTrue(choose(activity, "Nebula"));

        OrbitTheme active = OrbitThemeStore.active(context);
        OrbitTheme nebula = OrbitTheme.builtIn(OrbitTheme.ID_NEBULA);
        assertEquals("the active theme is the preset itself", OrbitTheme.ID_NEBULA, active.id);
        assertEquals("Nebula", active.name);
        assertTrue("with exactly the preset's colours", active.sameColours(nebula));
        assertEquals("and it is stored where every surface already reads it",
                nebula.accent, Prefs.get(context).getString(Prefs.ACCENT, ""));
    }

    // ---- AMOLED is its own switch ---------------------------------------------------------------------

    /** The switch exists, is separate, and is explained. */
    @Test public void amoledIsAseparateSwitch() {
        String text = textOf(personalize().get());
        assertTrue(text.contains("Use true black AMOLED backgrounds"));
        assertTrue("and is described as independent of the preset",
                text.contains("Works with any preset."));
        assertFalse("there is no separate AMOLED preset card to choose instead",
                text.contains("Orbit AMOLED"));
    }

    /** Turning true black on keeps the colours the user chose, and only changes the page. */
    @Test public void amoledPreservesTheChosenPresetsColours() {
        Activity activity = personalize().get();
        assertTrue(choose(activity, "Nebula"));
        OrbitTheme beforeAmoled = OrbitThemeStore.active(context);

        Activity again = personalize().get();
        assertTrue(toggleAmoled(again, true));
        OrbitTheme withAmoled = OrbitThemeStore.active(context);

        assertTrue("true black is on", withAmoled.amoled);
        assertEquals("and the accent is untouched", beforeAmoled.accent, withAmoled.accent);
        assertEquals(beforeAmoled.userBubble, withAmoled.userBubble);
        assertEquals(beforeAmoled.assistantBubble, withAmoled.assistantBubble);
        assertEquals(beforeAmoled.surface, withAmoled.surface);
        assertEquals(beforeAmoled.background, withAmoled.background);
    }

    /** Choosing a different preset leaves the AMOLED switch exactly where the user set it. */
    @Test public void changingPresetPreservesTheAmoledChoice() {
        Activity activity = personalize().get();
        assertTrue(toggleAmoled(activity, true));
        assertTrue(OrbitThemeStore.active(context).amoled);

        Activity again = personalize().get();
        assertTrue(choose(again, "Tide"));
        OrbitTheme active = OrbitThemeStore.active(context);
        assertTrue("the switch is still on", active.amoled);
        assertEquals("and Tide's own accent is what is drawing",
                OrbitTheme.builtIn(OrbitTheme.ID_TIDE).accent, active.accent);

        // And turning it off returns exactly the preset it was turned on over.
        Activity third = personalize().get();
        assertTrue(toggleAmoled(third, false));
        assertEquals(OrbitTheme.ID_TIDE, OrbitThemeStore.active(context).id);
    }

    // ---- what Theme Studio sees afterwards --------------------------------------------------------------

    /**
     * Orbit Default plus true black is Orbit AMOLED, and Theme Studio is told so.
     *
     * <p>This is the case the canonical rule exists for. Storing "Orbit Default, AMOLED on" would
     * leave the editor showing Orbit Default selected while Orbit drew a black page, which is a
     * screen lying about the user's own setting.
     */
    @Test public void theDefaultPresetWithTrueBlackIsRecordedAsOrbitAmoled() {
        Activity activity = personalize().get();
        assertTrue(choose(activity, "Orbit Default"));
        Activity again = personalize().get();
        assertTrue(toggleAmoled(again, true));

        OrbitTheme active = OrbitThemeStore.active(context);
        assertEquals(OrbitTheme.ID_AMOLED, active.id);
        assertEquals("Orbit AMOLED", active.name);
        assertTrue(active.amoled);
        assertTrue("and it is Orbit's shipped preset, not a copy of it",
                active.sameColours(OrbitTheme.builtIn(OrbitTheme.ID_AMOLED)));
    }

    /** A preset plus true black that is not any shipped theme does not pretend to be one. */
    @Test public void apresetWithTrueBlackThatIsNotShippedIsCalledTheUsersOwn() {
        Activity activity = personalize().get();
        assertTrue(choose(activity, "Ember"));
        Activity again = personalize().get();
        assertTrue(toggleAmoled(again, true));

        OrbitTheme active = OrbitThemeStore.active(context);
        assertFalse("it must not claim to be a shipped preset",
                OrbitTheme.isBuiltInId(active.id));
        assertFalse("nor borrow the preset's id", OrbitTheme.ID_EMBER.equals(active.id));
        assertEquals("it is described as the user's own, exactly as Theme Studio would describe it",
                "Your theme", active.name);
        assertTrue(active.amoled);
        assertEquals("while still being Ember's colours",
                OrbitTheme.builtIn(OrbitTheme.ID_EMBER).accent, active.accent);
    }

    /** Whatever was chosen, the editor opened afterwards shows exactly that. */
    @Test public void themeStudioOpensOnWhateverSetupChose() {
        Activity activity = personalize().get();
        assertTrue(choose(activity, "Moss"));

        ThemeStudioActivity studio =
                Robolectric.buildActivity(ThemeStudioActivity.class).setup().get();
        assertFalse("the editor opens with nothing to apply, because nothing has changed",
                studio.isDirty());
        assertNotNull("and with the chosen preset marked as the selected one",
                findByDescriptionContaining(studio.getWindow().getDecorView(),
                        "Moss, Orbit preset, selected"));
        assertEquals(OrbitTheme.ID_MOSS, OrbitThemeStore.active(context).id);
    }

    /** The same holds for a preset plus true black, which is where a wrong label would show. */
    @Test public void themeStudioIsHonestAboutTrueBlackChosenDuringSetup() {
        Activity activity = personalize().get();
        assertTrue(choose(activity, "Orbit Default"));
        Activity again = personalize().get();
        assertTrue(toggleAmoled(again, true));

        ThemeStudioActivity studio =
                Robolectric.buildActivity(ThemeStudioActivity.class).setup().get();
        assertFalse(studio.isDirty());
        assertNotNull("Orbit AMOLED is what is selected, not Orbit Default",
                findByDescriptionContaining(studio.getWindow().getDecorView(),
                        "Orbit AMOLED, Orbit preset, selected"));
        assertNull("and Orbit Default must not also claim to be selected",
                findByDescriptionContaining(studio.getWindow().getDecorView(),
                        "Orbit Default, Orbit preset, selected"));
    }

    // ---- typography stays -------------------------------------------------------------------------------

    /** The font selector is still here, with the same choices it always had. */
    @Test public void theappFontStays() {
        String text = textOf(personalize().get());
        assertTrue(text.contains("App font"));
        assertTrue("with Orbit's own default shown", text.contains("Orbit Default"));
        String source = source();
        for (String font : new String[]{"orbit_default", "times_new_roman", "light", "condensed",
                "monospace", "casual"}) {
            assertTrue("the font choice " + font + " must survive", source.contains("\"" + font + "\""));
        }
        assertTrue("and it still previews in the font it names",
                source.contains("UiKit.showOrbitFontMenu"));
    }

    /** Chat text size is still here, and is not reset by anything the preset picker does. */
    @Test public void thechatTextSizeStaysAndSurvivesAppearanceChanges() {
        Prefs.get(context).edit().putString(Prefs.CHAT_TEXT_SIZE, Prefs.CHAT_TEXT_LARGE).commit();
        Prefs.get(context).edit().putString(Prefs.APP_FONT, "condensed").commit();

        Activity activity = personalize().get();
        assertTrue(textOf(activity).contains("Chat text size"));
        assertTrue(choose(activity, "Nebula"));
        Activity again = personalize().get();
        assertTrue(toggleAmoled(again, true));

        assertEquals("the size the user chose is untouched",
                Prefs.CHAT_TEXT_LARGE, Prefs.chatTextSize(context));
        assertEquals("and so is the font", "condensed", Prefs.appFont(context));
    }

    // ---- Theme Studio is explained, not required ---------------------------------------------------------

    /** The page says where deeper customization lives, and does not send anybody there. */
    @Test public void themeStudioIsExplainedRatherThanOpened() {
        String text = textOf(personalize().get());
        assertTrue(text.contains(OnboardingActivity.THEME_STUDIO_NOTE));
        assertTrue("it names the place", OnboardingActivity.THEME_STUDIO_NOTE.contains("Theme Studio"));
        assertTrue("and says it is for later",
                OnboardingActivity.THEME_STUDIO_NOTE.contains("later"));

        String source = source();
        assertFalse("setup never opens the editor itself",
                source.contains("ThemeStudioActivity.class"));
        assertFalse("and adds no import or export step to a first run",
                source.contains("OrbitThemeFileCodec"));
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    private boolean choose(Activity activity, String presetName) {
        View card = findByDescriptionPrefix(activity.getWindow().getDecorView(),
                presetName + ", Orbit preset");
        if (card == null) return false;
        card.performClick();
        return true;
    }

    /** Flips the switch the way a finger does, so the listener actually runs. */
    private boolean toggleAmoled(Activity activity, boolean on) {
        OrbitSwitch found = findSwitch(activity.getWindow().getDecorView());
        if (found == null) return false;
        if (found.isChecked() == on) return true;
        found.toggle();
        return found.isChecked() == on;
    }

    private static OrbitSwitch findSwitch(View view) {
        if (view instanceof OrbitSwitch) return (OrbitSwitch) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                OrbitSwitch found = findSwitch(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View findByDescriptionContaining(View view, String fragment) {
        CharSequence description = view.getContentDescription();
        if (description != null && description.toString().contains(fragment)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findByDescriptionContaining(group.getChildAt(i), fragment);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View findByDescriptionPrefix(View view, String prefix) {
        CharSequence description = view.getContentDescription();
        if (view.isClickable() && description != null
                && description.toString().startsWith(prefix)) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findByDescriptionPrefix(group.getChildAt(i), prefix);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String textOf(Activity activity) {
        List<String> found = new ArrayList<>();
        collect(activity.getWindow().getDecorView(), found);
        return String.join("\n", found);
    }

    private static void collect(View view, List<String> out) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 0) out.add(text.toString());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out);
        }
    }
}
