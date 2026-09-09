package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The blue cube, measured.
 *
 * <p>Beta 8 accepted a large near-empty card carrying a small blue graphic as the second photograph
 * in a Mallard answer. It was a valid image, it was big enough, it decoded, and it was not a
 * photograph of anything. This file is the arithmetic that tells those two apart, tested from real
 * decoded pixels rather than from a mock.
 *
 * <p>The assertions run in both directions on purpose. A check that refuses graphics and also
 * refuses photographs has not fixed anything, it has only moved the failure somewhere harder to
 * see, so every rejection here is paired with a photograph that must survive it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class RichAnswerVisualQualityTest {

    private static Bitmap decode(byte[] bytes) {
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }

    private static RichAnswerVisualQuality.Measurement measure(byte[] bytes) {
        return RichAnswerVisualQuality.measure(decode(bytes));
    }

    // ---- the failure ------------------------------------------------------------------------------

    /** A small shape on a large flat field is a graphic, whatever its dimensions say. */
    @Test public void aSmallShapeOnAFlatFieldIsAGraphic() {
        RichAnswerVisualQuality.Measurement cube = measure(TestPng.graphic(900, 700, 0.13));
        assertTrue("a cube occupies a fraction of its frame",
                cube.contentRatio <= RichAnswerVisualQuality.MAX_GRAPHIC_CONTENT);
        assertTrue("and varies only along its own border",
                cube.textureRatio <= RichAnswerVisualQuality.MAX_GRAPHIC_TEXTURE);
        assertTrue(cube.isGraphic());
        assertEquals(RichAnswerTrace.Reason.GRAPHIC_OR_PLACEHOLDER,
                RichAnswerVisualQuality.judge(decode(TestPng.graphic(900, 700, 0.13))));
    }

    /** Being large is not being a photograph. The cube on the device was larger than the duck. */
    @Test public void aLargeGraphicIsStillAGraphic() {
        assertFalse(RichAnswerVisualQuality.isPhotographLike(
                decode(TestPng.graphic(1600, 1200, 0.1))));
    }

    /** A blank card is refused before any of the other rules are consulted. */
    @Test public void aUniformImageIsRefused() {
        RichAnswerVisualQuality.Measurement flat = measure(TestPng.rgb(800, 600));
        assertTrue("a solid fill spans nothing", flat.span < RichAnswerVisualQuality.MIN_SPAN);
        assertTrue(flat.isGraphic());
    }

    // ---- and the photographs that must survive it ------------------------------------------------

    /** A photograph fills its frame and varies across it. */
    @Test public void aPhotographIsNotAGraphic() {
        RichAnswerVisualQuality.Measurement photo = measure(TestPng.photo(900, 700, 1));
        assertTrue("a photograph is not one flat tone",
                photo.contentRatio > RichAnswerVisualQuality.MAX_GRAPHIC_CONTENT);
        assertTrue("and varies almost everywhere",
                photo.textureRatio > RichAnswerVisualQuality.MAX_GRAPHIC_TEXTURE);
        assertFalse(photo.isGraphic());
        assertEquals(RichAnswerTrace.Reason.NONE,
                RichAnswerVisualQuality.judge(decode(TestPng.photo(900, 700, 1))));
    }

    /** Every fixture the rest of the Rich Answers suite serves has to pass this. */
    @Test public void everyTestPhotographSurvives() {
        for (int seed = 1; seed <= 8; seed++) {
            assertFalse("seed " + seed + " must read as a photograph",
                    measure(TestPng.photo(900, 700, seed)).isGraphic());
        }
    }

    /** A re-encoded copy of a photograph is still a photograph. */
    @Test public void aRecompressedPhotographSurvives() {
        assertFalse(measure(TestPng.recompressed(640, 498, 1, 6)).isGraphic());
    }

    /** A small photograph is judged on its structure, not on its size. Size is judged elsewhere. */
    @Test public void aSmallPhotographIsStillAPhotograph() {
        assertFalse(measure(TestPng.photo(240, 200, 2)).isGraphic());
    }

    // ---- safety ----------------------------------------------------------------------------------

    /** An unmeasurable picture is never refused. Wrong in the direction that costs least. */
    @Test public void anUnreadablePictureIsNotRefused() {
        assertEquals(RichAnswerTrace.Reason.NONE, RichAnswerVisualQuality.judge(null));
        assertTrue(RichAnswerVisualQuality.isPhotographLike(null));
    }
}
