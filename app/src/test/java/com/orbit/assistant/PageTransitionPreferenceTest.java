package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONObject;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Page-transition preference: default, persistence, safe fallback, backup, and rebuild safety. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class PageTransitionPreferenceTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    private void set(String value) {
        Prefs.get(context).edit().putString(Prefs.PAGE_TRANSITION, value).commit();
    }

    @Test public void withNoStoredValueTheDefaultIsSlide() {
        // Fresh installs, and anyone updating who never chose a style, get Slide.
        assertEquals(Prefs.PAGE_TRANSITION_SLIDE, Prefs.pageTransition(context));
        assertEquals(R.style.OrbitWindowAnimation_Slide, UiKit.pageTransitionStyle(context));
    }

    @Test public void slidePersists() {
        set(Prefs.PAGE_TRANSITION_SLIDE);
        assertEquals(Prefs.PAGE_TRANSITION_SLIDE, Prefs.pageTransition(context));
        assertEquals(R.style.OrbitWindowAnimation_Slide, UiKit.pageTransitionStyle(context));
    }

    @Test public void fadeAndSettlePersists() {
        set(Prefs.PAGE_TRANSITION_FADE);
        assertEquals(Prefs.PAGE_TRANSITION_FADE, Prefs.pageTransition(context));
        assertEquals(R.style.OrbitWindowAnimation_Fade, UiKit.pageTransitionStyle(context));
    }

    @Test public void nonePersists() {
        set(Prefs.PAGE_TRANSITION_NONE);
        assertEquals(Prefs.PAGE_TRANSITION_NONE, Prefs.pageTransition(context));
        assertEquals(R.style.OrbitWindowAnimation_None, UiKit.pageTransitionStyle(context));
    }

    @Test public void anUnrecognisedStoredValueFallsBackToSlide() {
        set("parallax-warp");
        assertEquals(Prefs.PAGE_TRANSITION_SLIDE, Prefs.pageTransition(context));
        assertEquals(R.style.OrbitWindowAnimation_Slide, UiKit.pageTransitionStyle(context));

        set("");
        assertEquals(Prefs.PAGE_TRANSITION_SLIDE, Prefs.pageTransition(context));
    }

    @Test public void theThreeStylesAreDistinct() {
        set(Prefs.PAGE_TRANSITION_SLIDE);
        int slide = UiKit.pageTransitionStyle(context);
        set(Prefs.PAGE_TRANSITION_FADE);
        int fade = UiKit.pageTransitionStyle(context);
        set(Prefs.PAGE_TRANSITION_NONE);
        int none = UiKit.pageTransitionStyle(context);

        assertNotEquals(slide, fade);
        assertNotEquals(fade, none);
        assertNotEquals(slide, none);
    }

    @Test public void switchingBetweenEveryStyleResolvesCorrectlyEachTime() {
        String[] sequence = {Prefs.PAGE_TRANSITION_SLIDE, Prefs.PAGE_TRANSITION_FADE,
                Prefs.PAGE_TRANSITION_NONE, Prefs.PAGE_TRANSITION_SLIDE};
        for (String value : sequence) {
            set(value);
            assertEquals(value, Prefs.pageTransition(context));
        }
    }

    @Test public void pageTransitionNeverForcesASettingsRebuild() throws Exception {
        Prefs.get(context).edit().putString(Prefs.ACCENT, "mint").commit();
        String structural = UiKit.structuralAppearanceSignature(context);

        set(Prefs.PAGE_TRANSITION_FADE);
        assertEquals("page transitions must not replace the Settings content view",
                structural, UiKit.structuralAppearanceSignature(context));

        set(Prefs.PAGE_TRANSITION_NONE);
        assertEquals(structural, UiKit.structuralAppearanceSignature(context));

        set(Prefs.PAGE_TRANSITION_SLIDE);
        assertEquals(structural, UiKit.structuralAppearanceSignature(context));
    }

    @Test public void theChoiceTravelsThroughBackupAndRestore() throws Exception {
        set(Prefs.PAGE_TRANSITION_FADE);

        JSONObject backup = Prefs.backupSnapshot(context);
        assertTrue(Prefs.validBackupSnapshot(backup));

        Prefs.get(context).edit().clear().commit();
        assertEquals(Prefs.PAGE_TRANSITION_SLIDE, Prefs.pageTransition(context));

        assertTrue(Prefs.restoreBackupSnapshot(context, backup));
        assertEquals(Prefs.PAGE_TRANSITION_FADE, Prefs.pageTransition(context));
    }

    @Test public void olderBackupsWithoutTheChoiceRestoreAndFallBackToSlide() throws Exception {
        // A backup taken before this preference existed simply has no entry for it.
        Prefs.get(context).edit().putString(Prefs.ACCENT, "rose").commit();
        JSONObject olderBackup = Prefs.backupSnapshot(context);
        assertTrue(Prefs.validBackupSnapshot(olderBackup));

        set(Prefs.PAGE_TRANSITION_NONE);
        assertTrue(Prefs.restoreBackupSnapshot(context, olderBackup));

        assertEquals("a restore that never knew about page transitions must land on Slide",
                Prefs.PAGE_TRANSITION_SLIDE, Prefs.pageTransition(context));
        assertEquals("rose", Prefs.get(context).getString(Prefs.ACCENT, "dynamic"));
    }

    @Test public void otherAppearancePreferencesAreUnaffectedByTheChoice() {
        Prefs.get(context).edit()
                .putString(Prefs.APP_FONT, "condensed")
                .putString(Prefs.USER_BUBBLE_COLOR, "rose")
                .putString(Prefs.ASSISTANT_BUBBLE_COLOR, "nova")
                .commit();

        set(Prefs.PAGE_TRANSITION_NONE);

        assertEquals("condensed", Prefs.appFont(context));
        assertEquals("rose", Prefs.userBubbleColor(context));
        assertEquals("nova", Prefs.assistantBubbleColor(context));
    }
}
