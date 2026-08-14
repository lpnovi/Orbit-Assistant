package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Which appearance changes force Settings to rebuild.
 *
 * <p>A rebuild replaces the Activity content view, which is what briefly emptied the content frame
 * and flashed. Only appearance that is baked into views when they are built may appear in the
 * structural signature; anything applicable to the views already on screen must stay out of it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class AppearanceSignatureTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    private void set(String key, String value) {
        Prefs.get(context).edit().putString(key, value).commit();
    }

    private String structural() {
        return UiKit.structuralAppearanceSignature(context);
    }

    private String full() {
        return UiKit.appearanceSignature(context);
    }

    @Test public void accentChangeRequiresARebuild() {
        set(Prefs.ACCENT, "mint");
        String before = structural();

        set(Prefs.ACCENT, "rose");

        assertNotEquals("accent is baked into built views", before, structural());
    }

    @Test public void amoledChangeRequiresARebuild() {
        Prefs.get(context).edit().putBoolean(Prefs.AMOLED_MODE, false).commit();
        String before = structural();

        Prefs.get(context).edit().putBoolean(Prefs.AMOLED_MODE, true).commit();

        assertNotEquals("AMOLED changes Orbit's surface colours", before, structural());
    }

    @Test public void appFontChangeDoesNotRequireARebuild() {
        set(Prefs.APP_FONT, "orbit_default");
        String beforeStructural = structural();
        String beforeFull = full();

        set(Prefs.APP_FONT, "times_new_roman");

        // The font is re-applied to the existing hierarchy instead of rebuilding it.
        assertEquals("font must not trigger a content-view replacement",
                beforeStructural, structural());
        assertNotEquals("the change still has to be detected", beforeFull, full());
    }

    @Test public void userBubbleColourChangeDoesNotRequireARebuild() {
        set(Prefs.USER_BUBBLE_COLOR, "classic");
        String beforeStructural = structural();
        String beforeFull = full();

        set(Prefs.USER_BUBBLE_COLOR, "rose");

        assertEquals("bubble colours are not drawn anywhere in Settings",
                beforeStructural, structural());
        assertNotEquals(beforeFull, full());
    }

    @Test public void assistantBubbleColourChangeDoesNotRequireARebuild() {
        set(Prefs.ASSISTANT_BUBBLE_COLOR, "classic");
        String beforeStructural = structural();
        String beforeFull = full();

        set(Prefs.ASSISTANT_BUBBLE_COLOR, "nova");

        assertEquals(beforeStructural, structural());
        assertNotEquals(beforeFull, full());
    }

    @Test public void theTwoBubbleColoursStayIndependent() {
        set(Prefs.USER_BUBBLE_COLOR, "rose");
        set(Prefs.ASSISTANT_BUBBLE_COLOR, "blue");

        assertEquals("rose", Prefs.userBubbleColor(context));
        assertEquals("blue", Prefs.assistantBubbleColor(context));

        set(Prefs.USER_BUBBLE_COLOR, "mint");
        assertEquals("mint", Prefs.userBubbleColor(context));
        assertEquals("changing one bubble colour must not move the other",
                "blue", Prefs.assistantBubbleColor(context));
    }

    @Test public void repeatedBubbleAndFontChangesNeverRequireARebuild() {
        set(Prefs.ACCENT, "blurple");
        String structuralBaseline = structural();

        String[] userBubbles = {"accent", "rose", "mint", "blurple", "accent"};
        String[] assistantBubbles = {"blue", "nova", "accent", "pastel_pink", "blue"};
        String[] fonts = {"orbit_default", "times_new_roman", "light", "condensed",
                "monospace", "casual", "orbit_default"};

        for (String value : userBubbles) {
            set(Prefs.USER_BUBBLE_COLOR, value);
            assertEquals(structuralBaseline, structural());
        }
        for (String value : assistantBubbles) {
            set(Prefs.ASSISTANT_BUBBLE_COLOR, value);
            assertEquals(structuralBaseline, structural());
        }
        for (String value : fonts) {
            set(Prefs.APP_FONT, value);
            assertEquals(structuralBaseline, structural());
        }

        // The accent that was set at the start is still intact after all of it.
        assertEquals("blurple", Prefs.get(context).getString(Prefs.ACCENT, "dynamic"));
    }

    @Test public void everyAccentPaletteEntryIsDistinguishedFromTheOthers() {
        String previous = null;
        for (String key : UiKit.accentKeys()) {
            set(Prefs.ACCENT, key);
            String current = structural();
            if (previous != null) {
                // Each accent must be detected as a change, or the old accent would survive on
                // screen until Settings was reopened.
                assertNotEquals("accent " + key + " was not detected as a change",
                        previous, current);
            }
            previous = current;
        }
    }

    @Test public void mixedChangesOnlyRebuildForTheAccentAndAmoledSteps() {
        set(Prefs.ACCENT, "mint");
        String afterAccent = structural();

        set(Prefs.USER_BUBBLE_COLOR, "rose");
        assertEquals(afterAccent, structural());

        set(Prefs.APP_FONT, "condensed");
        assertEquals(afterAccent, structural());

        set(Prefs.ASSISTANT_BUBBLE_COLOR, "nova");
        assertEquals(afterAccent, structural());

        set(Prefs.ACCENT, "nova");
        String afterSecondAccent = structural();
        assertNotEquals(afterAccent, afterSecondAccent);

        set(Prefs.APP_FONT, "casual");
        assertEquals(afterSecondAccent, structural());
    }

    @Test public void chatTextSizeIsNotAnAppearanceRebuildTrigger() {
        String beforeStructural = structural();
        Prefs.get(context).edit().putString(Prefs.CHAT_TEXT_SIZE, Prefs.CHAT_TEXT_LARGE).commit();

        // Chat text size only affects conversation content, not the Settings hierarchy.
        assertEquals(beforeStructural, structural());
    }
}
