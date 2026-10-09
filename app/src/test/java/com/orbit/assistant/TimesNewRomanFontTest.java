package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Typeface;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Times New Roman on One UI 9 (0.8.4.1): the face is taken from a registered font file, not the
 * generic "serif" alias Samsung remapped to the normal system face. These checks cover which files
 * count, that the choice and its preview resolve to the same face, and that the saved choice and
 * every other font are untouched. Real-device rendering is verified on the phone, not here.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class TimesNewRomanFontTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    @Test public void aRealTimesNewRomanFileWinsOverTheSystemSerif() {
        assertEquals(0, UiKit.serifFileRank("TimesNewRoman-Regular.ttf"));
        assertEquals(0, UiKit.serifFileRank("times.ttf"));
        assertEquals(0, UiKit.serifFileRank("timesbd.ttf"));
        assertEquals(0, UiKit.serifFileRank("TimesNewRomanPSMT.otf"));
        assertEquals(1, UiKit.serifFileRank("NotoSerif-Regular.ttf"));
        assertEquals(1, UiKit.serifFileRank("NotoSerif-BoldItalic.ttf"));
        assertEquals(1, UiKit.serifFileRank("NotoSerif[wght].ttf"));
        assertEquals(2, UiKit.serifFileRank("DroidSerif-Bold.ttf"));
    }

    @Test public void nothingElseIsMistakenForTheSerifFace() {
        for (String name : new String[]{"NotoSerifArmenian-Regular.ttf", "NotoSerifCJK-Regular.ttc",
                "NotoSerifDisplay-Regular.ttf", "NotoSerifHebrew-Bold.ttf", "Roboto-Regular.ttf",
                "SamsungOneUI-400.ttf", "NotoSans-Regular.ttf", "SECRobotoLight-Regular.ttf",
                "TimesSquare.ttf", "", null}) {
            assertEquals(String.valueOf(name), -1, UiKit.serifFileRank(name));
        }
    }

    @Test public void timesNewRomanResolvesWithoutTheGenericAliasAndNeverFails() {
        for (int style : new int[]{Typeface.NORMAL, Typeface.BOLD, Typeface.ITALIC, Typeface.BOLD_ITALIC}) {
            assertNotNull(UiKit.typefaceForFontChoice("times_new_roman", style));
        }
        String source = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/UiKit.java");
        assertFalse("the remappable alias is only a last resort, never the first choice",
                source.contains("return Typeface.create(Typeface.SERIF, style);"));
        assertTrue(source.contains("return Typeface.create(serifBase(), style);"));
    }

    @Test public void thePreviewShowsTheSameFaceTheAppUses() {
        Prefs.get(context).edit().putString(Prefs.APP_FONT, "times_new_roman").commit();
        TextView preview = UiKit.text(context, "Sample", 15, UiKit.TEXT, false);
        UiKit.applyFontPreview(preview, "times_new_roman", Typeface.NORMAL);
        assertEquals(UiKit.appTypeface(context, Typeface.NORMAL), preview.getTypeface());
        assertEquals(UiKit.typefaceForFontChoice("times_new_roman", Typeface.NORMAL), preview.getTypeface());
    }

    @Test public void theSavedChoiceAndTheOtherFontsAreUnchanged() {
        Prefs.get(context).edit().putString(Prefs.APP_FONT, "times_new_roman").commit();
        assertEquals("times_new_roman", Prefs.appFont(context));
        for (String font : new String[]{"orbit_default", "light", "condensed", "monospace", "casual"}) {
            assertNotNull(font, UiKit.typefaceForFontChoice(font, Typeface.NORMAL));
        }
        assertEquals(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL),
                UiKit.typefaceForFontChoice("orbit_default", Typeface.NORMAL));
        assertEquals(1.0f, UiKit.textScaleXForFontChoice("times_new_roman"), 0f);
        assertEquals(0.0f, UiKit.letterSpacingForFontChoice("times_new_roman"), 0f);
    }
}
