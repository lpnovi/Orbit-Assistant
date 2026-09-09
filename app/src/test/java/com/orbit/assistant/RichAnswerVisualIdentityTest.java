package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The second identity layer, on its own.
 *
 * <p>The question this file answers is the one Beta 6 could not: given two pictures that have
 * already been downloaded and decoded, are they the same photograph? It is deliberately separated
 * from the resolver, because a threshold is a number and a number is worth pinning down before it
 * is wired into anything.
 *
 * <p>Both directions are tested, and both matter. Calling one photograph two is the bug the device
 * reported. Calling two photographs one is the more expensive failure, because the picture the user
 * never sees is gone silently.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class RichAnswerVisualIdentityTest {

    private static Bitmap decode(byte[] png) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
        assertTrue("fixture must decode", bitmap != null);
        return bitmap;
    }

    private static RichAnswerVisualIdentity.Fingerprint photo(int width, int height, int seed) {
        return RichAnswerVisualIdentity.fingerprint(decode(TestPng.photo(width, height, seed)));
    }

    // ---- the duplicate cases -----------------------------------------------------------------------

    /** A picture is itself. The floor everything else stands on. */
    @Test public void thesamePictureFingerprintsIdentically() {
        RichAnswerVisualIdentity.Fingerprint first = photo(800, 600, 1);
        RichAnswerVisualIdentity.Fingerprint second = photo(800, 600, 1);
        assertEquals(0, RichAnswerVisualIdentity.distance(first, second));
        assertTrue(RichAnswerVisualIdentity.sameImage(first, second));
    }

    /**
     * A resized copy is the same photograph.
     *
     * <p>The Wikimedia shape, and the one the URL layer already catches - repeated here because the
     * whole point of the second layer is that it must not need the URL to agree.
     */
    @Test public void aResizedCopyIsTheSamePhotograph() {
        RichAnswerVisualIdentity.Fingerprint large = photo(1600, 1200, 4);
        for (int[] size : new int[][]{{800, 600}, {640, 480}, {320, 240}}) {
            RichAnswerVisualIdentity.Fingerprint small = photo(size[0], size[1], 4);
            assertTrue("a " + size[0] + "-wide copy must still be the same photograph, distance "
                            + RichAnswerVisualIdentity.distance(large, small),
                    RichAnswerVisualIdentity.sameImage(large, small));
        }
    }

    /** A copy degraded the way a re-encode degrades one is still the same photograph. */
    @Test public void aRecompressedCopyIsTheSamePhotograph() {
        RichAnswerVisualIdentity.Fingerprint original = photo(900, 700, 6);
        RichAnswerVisualIdentity.Fingerprint noisy = RichAnswerVisualIdentity.fingerprint(
                decode(TestPng.recompressed(900, 700, 6, 8)));
        assertTrue("distance was " + RichAnswerVisualIdentity.distance(original, noisy),
                RichAnswerVisualIdentity.sameImage(original, noisy));
    }

    /** Resized and re-encoded at once, which is what a CDN rendition actually is. */
    @Test public void aResizedAndRecompressedCopyIsStillTheSamePhotograph() {
        RichAnswerVisualIdentity.Fingerprint original = photo(1280, 960, 2);
        RichAnswerVisualIdentity.Fingerprint rendition = RichAnswerVisualIdentity.fingerprint(
                decode(TestPng.recompressed(600, 450, 2, 6)));
        assertTrue("distance was " + RichAnswerVisualIdentity.distance(original, rendition),
                RichAnswerVisualIdentity.sameImage(original, rendition));
    }

    // ---- the distinct cases ------------------------------------------------------------------------

    /** Different photographs are different, and comfortably so. */
    @Test public void differentPhotographsAreDistinct() {
        for (int first = 1; first <= 6; first++) {
            for (int second = first + 1; second <= 6; second++) {
                RichAnswerVisualIdentity.Fingerprint a = photo(800, 600, first);
                RichAnswerVisualIdentity.Fingerprint b = photo(800, 600, second);
                // Asserted explicitly, because two weak fingerprints are also "not the same
                // photograph" and would make every assertion below pass for the wrong reason.
                assertTrue("fixture " + first + " must carry a real fingerprint", a.isStrong());
                assertTrue("fixture " + second + " must carry a real fingerprint", b.isStrong());
                assertFalse("seeds " + first + " and " + second + " are two photographs, distance "
                                + RichAnswerVisualIdentity.distance(a, b),
                        RichAnswerVisualIdentity.sameImage(a, b));
            }
        }
    }

    /**
     * Two pictures with the same palette, brightness and smooth composition are still two pictures.
     *
     * <p>This is the assertion that stops the fix becoming a worse bug. Every fixture in this file
     * shares a formula, a grey palette and a brightness range; what separates them is structure,
     * and structure is the only thing the hash is allowed to read.
     */
    @Test public void similarLookingButDifferentPhotographsAreNotMerged() {
        RichAnswerVisualIdentity.Fingerprint a = photo(1000, 750, 3);
        RichAnswerVisualIdentity.Fingerprint b = photo(1000, 750, 5);
        assertFalse(RichAnswerVisualIdentity.sameImage(a, b));
        assertTrue("and the gap must be a real one, not one bit over the line",
                RichAnswerVisualIdentity.distance(a, b) > RichAnswerVisualIdentity.MAX_DISTANCE * 2);
    }

    // ---- the refusal to guess ----------------------------------------------------------------------

    /**
     * A featureless picture is never a duplicate of anything, including another featureless one.
     *
     * <p>A solid colour has no gradient, so its hash is all zeroes and would collide with every
     * other flat image ever fetched. Merging on no evidence is how a genuinely different photograph
     * gets thrown away, so the fingerprint is marked weak and refuses to answer.
     */
    @Test public void solidPicturesNeverCollapseOntoEachOther() {
        RichAnswerVisualIdentity.Fingerprint first =
                RichAnswerVisualIdentity.fingerprint(decode(TestPng.rgb(600, 400)));
        RichAnswerVisualIdentity.Fingerprint second =
                RichAnswerVisualIdentity.fingerprint(decode(TestPng.rgb(900, 700)));
        assertFalse("a flat picture carries no identity", first.isStrong());
        assertFalse(RichAnswerVisualIdentity.sameImage(first, second));
        assertFalse(RichAnswerVisualIdentity.sameImage(first, first));
        assertEquals("and it names nothing in a report", "", first.token());
    }

    /** Nothing at all is not a duplicate of anything either. */
    @Test public void anAbsentPictureMatchesNothing() {
        RichAnswerVisualIdentity.Fingerprint none = RichAnswerVisualIdentity.fingerprint(null);
        assertFalse(none.isStrong());
        assertFalse(RichAnswerVisualIdentity.sameImage(none, photo(800, 600, 1)));
        assertFalse(RichAnswerVisualIdentity.sameImage(null, photo(800, 600, 1)));
        assertEquals(RichAnswerVisualIdentity.HASH_BITS,
                RichAnswerVisualIdentity.distance(none, photo(800, 600, 1)));
    }

    /** A picture too small to hold a gradient is refused rather than guessed at. */
    @Test public void aTinyPictureCarriesNoIdentity() {
        assertFalse(RichAnswerVisualIdentity.fingerprint(decode(TestPng.photo(6, 6, 1))).isStrong());
    }

    // ---- the diagnostics token ---------------------------------------------------------------------

    /** The report token names a picture without describing one. */
    @Test public void theTokenIsShortStableAndSaysNothingAboutTheContent() {
        RichAnswerVisualIdentity.Fingerprint print = photo(800, 600, 2);
        assertEquals("7 characters: four hex and an ellipsis", 5, print.token().length());
        assertEquals(print.token(), photo(400, 300, 2).token());
        assertNotEquals(print.token(), photo(800, 600, 5).token());
        assertTrue(print.token().matches("[0-9A-F]{4}…"));
    }

    // ---- the shape of the hash ---------------------------------------------------------------------

    /** Both halves of the hash are real: a picture is compared along both axes. */
    @Test public void thehashReadsBothDirections() {
        RichAnswerVisualIdentity.Fingerprint print = photo(800, 600, 4);
        assertNotEquals("a horizontal-only hash would miss a vertically mirrored copy",
                0L, print.horizontal);
        assertNotEquals(0L, print.vertical);
    }

    /** The resampler averages by area, so an uneven reduction keeps every row. */
    @Test public void resamplingIsAreaWeighted() {
        double[] grid = new double[4];
        grid[0] = 0; grid[1] = 100; grid[2] = 200; grid[3] = 300;
        double[] halved = RichAnswerVisualIdentity.resample(grid, 2, 2, 1, 1);
        assertEquals(150.0, halved[0], 0.0001);
        double[] widened = RichAnswerVisualIdentity.resample(grid, 2, 2, 3, 1);
        assertTrue("the left of a widened row must stay the darker side",
                widened[0] < widened[2]);
    }
}
