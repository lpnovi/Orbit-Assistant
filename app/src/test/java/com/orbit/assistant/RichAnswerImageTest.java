package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The canonical rich-image record, and the rules that stop it lying about where a picture came from.
 *
 * <p>The one that matters most is the separation between {@link RichAnswerImage#WEB_SOURCE} and
 * {@link RichAnswerImage#GENERATED}. A sourced picture must have a real page behind it or it is not
 * showable; a generated one must never carry a page at all, because it does not have one and a
 * fabricated source link would be Orbit inventing provenance. Beta 1 produces only the first kind,
 * and these tests hold the second kind's contract so the later work cannot quietly break it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class RichAnswerImageTest {

    private static RichAnswerImage sourced() {
        return RichAnswerImage.webSource("https://cdn.example.com/robin.jpg",
                "https://www.example.org/birds/robin", "A European robin", "robin on a branch", 1);
    }

    @Test public void aSourcedImageKeepsItsPageAndDerivesItsDomain() {
        RichAnswerImage image = sourced();
        assertTrue(image.isWebSource());
        assertFalse(image.isGenerated());
        assertTrue(image.isUsable());
        assertEquals("https://www.example.org/birds/robin", image.sourceUrl);
        assertEquals("the www prefix is dropped for display", "example.org", image.sourceDomain);
        assertEquals("A European robin · example.org", image.attributionLine());
        assertEquals("robin on a branch", image.contentDescription());
        assertEquals(1, image.blockIndex);
        assertFalse("every image gets a stable id", image.id.isEmpty());
    }

    /**
     * A generated image may never carry a source page, even if one is handed to the constructor.
     *
     * <p>Enforced in the model rather than at each call site, so a future provider path cannot
     * present a picture the model drew as though a web page vouched for it.
     */
    @Test public void aGeneratedImageCanNeverCarryASourcePage() {
        RichAnswerImage generated = new RichAnswerImage("", "https://cdn.example.com/made.png",
                "https://www.example.org/somewhere", "A drawing", "", RichAnswerImage.GENERATED, 0);
        assertTrue(generated.isGenerated());
        assertEquals("the source page must be discarded", "", generated.sourceUrl);
        assertEquals("", generated.sourceDomain);
        assertEquals("Generated image", generated.attributionLine());
        assertTrue("and it is still a picture Orbit can draw", generated.isUsable());
    }

    /** Beta 1 ships web-sourced images only; the generated case is reserved, not used. */
    @Test public void betaOneOnlyProducesWebSourcedImages() {
        assertEquals(RichAnswerImage.WEB_SOURCE, sourced().provenance);
        assertEquals("web_source", RichAnswerImage.WEB_SOURCE);
        assertEquals("generated", RichAnswerImage.GENERATED);
        String coordinator = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/RichAnswerCoordinator.java");
        assertTrue("discovery must only ever mint web-sourced images",
                coordinator.contains("RichAnswerImage.webSource("));
        assertFalse("nothing may mint a generated image until a provider genuinely returns one",
                coordinator.contains("RichAnswerImage.GENERATED"));
    }

    /** An unknown provenance settles on the safe one rather than becoming a third kind. */
    @Test public void anUnknownProvenanceBecomesWebSource() {
        RichAnswerImage image = new RichAnswerImage("", "https://cdn.example.com/x.jpg",
                "https://example.org/p", "", "", "handcrafted", 0);
        assertEquals(RichAnswerImage.WEB_SOURCE, image.provenance);
        assertTrue(image.isWebSource());
    }

    // ---- usability -------------------------------------------------------------------------------

    /** A sourced picture with nothing to attribute it to is not a sourced picture. */
    @Test public void aSourcedImageWithoutARealPageIsNotUsable() {
        assertFalse(RichAnswerImage.webSource("https://cdn.example.com/x.jpg", "", "", "", 0)
                .isUsable());
        assertFalse(RichAnswerImage.webSource("https://cdn.example.com/x.jpg",
                "javascript:alert(1)", "", "", 0).isUsable());
        assertFalse(RichAnswerImage.webSource("https://cdn.example.com/x.jpg",
                "https://localhost/page", "", "", 0).isUsable());
    }

    /** And a record with no picture, or an unfetchable one, is not showable either. */
    @Test public void anUnfetchableImageAddressIsNotUsable() {
        assertFalse(RichAnswerImage.webSource("", "https://example.org/p", "", "", 0).isUsable());
        assertFalse(RichAnswerImage.webSource("http://cdn.example.com/x.jpg",
                "https://example.org/p", "", "", 0).isUsable());
        assertFalse(RichAnswerImage.webSource("file:///data/x.jpg",
                "https://example.org/p", "", "", 0).isUsable());
        assertFalse(RichAnswerImage.webSource("https://127.0.0.1/x.jpg",
                "https://example.org/p", "", "", 0).isUsable());
    }

    // ---- storage ---------------------------------------------------------------------------------

    @Test public void aRecordSurvivesARoundTripThroughJson() throws Exception {
        RichAnswerImage original = sourced();
        JSONObject json = original.toJson();
        RichAnswerImage restored = RichAnswerImage.fromJson(json);
        assertNotNull(restored);
        assertEquals(original.id, restored.id);
        assertEquals(original.imageUrl, restored.imageUrl);
        assertEquals(original.sourceUrl, restored.sourceUrl);
        assertEquals(original.caption, restored.caption);
        assertEquals(original.altText, restored.altText);
        assertEquals(original.provenance, restored.provenance);
        assertEquals(original.blockIndex, restored.blockIndex);
    }

    /** Empty fields are left out, so a record stays small and an older build reads it unchanged. */
    @Test public void onlyPopulatedFieldsAreWritten() throws Exception {
        JSONObject json = new RichAnswerImage("id-1", "https://cdn.example.com/made.png",
                "", "", "", RichAnswerImage.GENERATED, 0).toJson();
        assertFalse(json.has("sourceUrl"));
        assertFalse(json.has("caption"));
        assertFalse(json.has("altText"));
        assertTrue(json.has("imageUrl"));
        assertTrue(json.has("provenance"));
    }

    /**
     * A damaged record is dropped rather than drawn.
     *
     * <p>Losing a picture is a small thing. Drawing one that claims a source it does not have, or
     * failing a whole conversation because one record was written badly, are both much worse.
     */
    @Test public void malformedRecordsAreDroppedOnRead() throws Exception {
        assertNull(RichAnswerImage.fromJson(null));
        assertNull("no image address", RichAnswerImage.fromJson(new JSONObject()
                .put("sourceUrl", "https://example.org/p")));
        assertNull("no source page", RichAnswerImage.fromJson(new JSONObject()
                .put("imageUrl", "https://cdn.example.com/x.jpg")));
        assertNull("private image address", RichAnswerImage.fromJson(new JSONObject()
                .put("imageUrl", "https://192.168.1.5/x.jpg")
                .put("sourceUrl", "https://example.org/p")));
        assertNull("hostile scheme", RichAnswerImage.fromJson(new JSONObject()
                .put("imageUrl", "javascript:alert(1)")
                .put("sourceUrl", "https://example.org/p")));
    }

    // ---- bounds ----------------------------------------------------------------------------------

    @Test public void captionsAndAltTextAreBoundedAndCollapsed() {
        StringBuilder long1 = new StringBuilder();
        for (int i = 0; i < 400; i++) long1.append("x");
        RichAnswerImage image = RichAnswerImage.webSource("https://cdn.example.com/x.jpg",
                "https://example.org/p", long1.toString(), "a\n\n  b   c", 0);
        assertTrue(image.caption.length() <= RichAnswerImage.MAX_CAPTION_CHARS);
        assertEquals("whitespace is collapsed for a one-line caption", "a b c", image.altText);
    }

    /** A stored anchor is clamped rather than allowed to go negative. */
    @Test public void aNegativeBlockAnchorSettlesAtTheStart() {
        assertEquals(0, RichAnswerImage.webSource("https://cdn.example.com/x.jpg",
                "https://example.org/p", "", "", -7).blockIndex);
    }

    /** One answer may never hold more pictures than Orbit is willing to draw. */
    @Test public void oneAnswerIsBoundedToTwoPictures() {
        assertEquals(2, RichAnswerImage.MAX_PER_MESSAGE);
    }

    /** A useful Vault title is derived locally, with no request to anything. */
    @Test public void aVaultTitleIsDerivedFromWhatTheRecordAlreadyHolds() {
        assertEquals("A European robin", sourced().vaultTitle());
        assertEquals("robin on a branch", RichAnswerImage.webSource("https://cdn.example.com/x.jpg",
                "https://example.org/p", "", "robin on a branch", 0).vaultTitle());
        assertEquals("Image from example.org", RichAnswerImage.webSource(
                "https://cdn.example.com/x.jpg", "https://example.org/p", "", "", 0).vaultTitle());
    }
}
