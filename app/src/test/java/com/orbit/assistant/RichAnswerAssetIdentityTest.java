package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * When two addresses are two renditions of one photograph, and when they are two photographs.
 *
 * <p>The central case is the one the device produced. "Show me pics of a mallard duck" ranked
 * three candidates whose addresses ended {@code 1920px-Mallard-Duck.jpg},
 * {@code 960px-Mallard-Duck.jpg} and {@code 1280px-Mallard-Duck.jpg}, offered by three different
 * origins on one page. Counting those as three pictures is how a plural request gets answered with
 * the same duck twice.
 *
 * <p>The other half of the file matters just as much: the pairs that must stay <em>different</em>.
 * Merging unrelated photographs costs a picture the user never gets to see, which is the more
 * expensive mistake, so the rules stop where the evidence in the address stops.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class RichAnswerAssetIdentityTest {

    private static final String COMMONS = "https://upload.wikimedia.org/wikipedia/commons/";

    /** The three Mallard renditions the real device ranked, in their real shapes. */
    private static final String MALLARD_1920 =
            COMMONS + "thumb/1/1e/Mallard-Duck.jpg/1920px-Mallard-Duck.jpg";
    private static final String MALLARD_1280 =
            COMMONS + "thumb/1/1e/Mallard-Duck.jpg/1280px-Mallard-Duck.jpg";
    private static final String MALLARD_960 =
            COMMONS + "thumb/1/1e/Mallard-Duck.jpg/960px-Mallard-Duck.jpg";
    private static final String MALLARD_ORIGINAL = COMMONS + "1/1e/Mallard-Duck.jpg";

    // ---- the Mallard case ------------------------------------------------------------------------

    /** Every width of the Mallard photograph is one photograph. */
    @Test public void everyWidthOfTheMallardPhotographIsOneAsset() {
        String canonical = RichAnswerAssetIdentity.canonical(MALLARD_1920);
        assertFalse("a Wikimedia thumbnail must resolve to something", canonical.isEmpty());
        assertEquals(canonical, RichAnswerAssetIdentity.canonical(MALLARD_1280));
        assertEquals(canonical, RichAnswerAssetIdentity.canonical(MALLARD_960));
        assertEquals("and to the original the scaler was resizing",
                canonical, RichAnswerAssetIdentity.canonical(MALLARD_ORIGINAL));
        assertEquals("upload.wikimedia.org/wikipedia/commons/1/1e/Mallard-Duck.jpg", canonical);
    }

    /** Said the way the resolver asks it. */
    @Test public void theSameAssetPredicateAgreesOnTheMallardRenditions() {
        assertTrue(RichAnswerAssetIdentity.sameAsset(MALLARD_1920, MALLARD_960));
        assertTrue(RichAnswerAssetIdentity.sameAsset(MALLARD_1280, MALLARD_ORIGINAL));
    }

    /** A different photograph in the same Commons directory is a different photograph. */
    @Test public void aDifferentCommonsFileIsADifferentAsset() {
        String other = COMMONS + "thumb/2/2f/Mallard-Drake.jpg/1920px-Mallard-Drake.jpg";
        assertFalse(RichAnswerAssetIdentity.sameAsset(MALLARD_1920, other));
    }

    // ---- ordinary CDN shapes ---------------------------------------------------------------------

    /** A width-prefixed filename outside Wikimedia collapses the same way. */
    @Test public void aWidthPrefixedFilenameIsARendition() {
        assertTrue(RichAnswerAssetIdentity.sameAsset(
                "https://cdn.example.org/img/640px-robin.jpg",
                "https://cdn.example.org/img/robin.jpg"));
    }

    /** So does a WordPress-style dimension suffix, including a stacked one. */
    @Test public void aDimensionSuffixIsARendition() {
        assertTrue(RichAnswerAssetIdentity.sameAsset(
                "https://example.org/uploads/robin-1024x768.jpg",
                "https://example.org/uploads/robin-300x225.jpg"));
        assertTrue(RichAnswerAssetIdentity.sameAsset(
                "https://example.org/uploads/robin-1024x768-scaled.jpg",
                "https://example.org/uploads/robin.jpg"));
        assertTrue(RichAnswerAssetIdentity.sameAsset(
                "https://example.org/uploads/robin@2x.jpg",
                "https://example.org/uploads/robin.jpg"));
    }

    /** A resize asked for in the query string is still a resize. */
    @Test public void queryResizeParametersAreDropped() {
        assertTrue(RichAnswerAssetIdentity.sameAsset(
                "https://images.example.com/robin.jpg?w=1200&q=80&fit=crop",
                "https://images.example.com/robin.jpg?w=400&q=50"));
        assertTrue(RichAnswerAssetIdentity.sameAsset(
                "https://images.example.com/robin.jpg?width=1200&height=900",
                "https://images.example.com/robin.jpg"));
    }

    /**
     * A query parameter that is not about rendering is left alone.
     *
     * <p>On plenty of sites the query string <em>is</em> the identity, so dropping it wholesale
     * would merge unrelated pictures. Only the known rendering keys go.
     */
    @Test public void aMeaningfulQueryParameterIsKept() {
        assertFalse(RichAnswerAssetIdentity.sameAsset(
                "https://images.example.com/serve?id=1001&w=800",
                "https://images.example.com/serve?id=2002&w=800"));
        assertTrue(RichAnswerAssetIdentity.sameAsset(
                "https://images.example.com/serve?id=1001&w=800",
                "https://images.example.com/serve?id=1001&w=200"));
    }

    /** A protocol-relative host difference is not merged away. */
    @Test public void adifferentHostIsADifferentAsset() {
        assertFalse(RichAnswerAssetIdentity.sameAsset(
                "https://cdn-a.example.org/img/robin.jpg",
                "https://cdn-b.example.org/img/robin.jpg"));
    }

    /** A leading {@code www.} is the same host, which browsers and users both already believe. */
    @Test public void wwwIsTheSameHost() {
        assertTrue(RichAnswerAssetIdentity.sameAsset(
                "https://www.example.org/img/robin.jpg",
                "https://example.org/img/robin.jpg"));
    }

    // ---- refusals --------------------------------------------------------------------------------

    /** An address Orbit would never fetch has no identity, so it collides with nothing. */
    @Test public void anUnfetchableAddressHasNoIdentity() {
        assertEquals("", RichAnswerAssetIdentity.canonical(""));
        assertEquals("", RichAnswerAssetIdentity.canonical(null));
        assertEquals("", RichAnswerAssetIdentity.canonical("not a url"));
        assertFalse(RichAnswerAssetIdentity.sameAsset("", ""));
        assertFalse(RichAnswerAssetIdentity.sameAsset(null, null));
    }

    /** Two entirely unrelated pictures are never the same one. */
    @Test public void unrelatedPicturesStayUnrelated() {
        assertNotEquals(RichAnswerAssetIdentity.canonical("https://example.org/a/robin.jpg"),
                RichAnswerAssetIdentity.canonical("https://example.org/a/bluebird.jpg"));
    }

    /**
     * A {@code thumb} directory alone is not a scaler, and neither is a rendition-shaped filename.
     *
     * <p>Both conditions together are Wikimedia's own URL shape; either one on its own is somebody
     * ordinary naming a directory or a file, and collapsing those would lose real pictures.
     */
    @Test public void aThumbDirectoryAloneDoesNotCollapse() {
        assertFalse(RichAnswerAssetIdentity.sameAsset(
                "https://example.org/thumb/gallery/robin.jpg",
                "https://example.org/gallery/robin.jpg"));
    }
}
