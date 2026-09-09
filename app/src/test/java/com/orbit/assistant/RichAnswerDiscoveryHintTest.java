package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What Orbit is willing to treat as "a picture might be here", and what it refuses.
 *
 * <p>The fallback this covers exists because Beta 5's successful route usually recovers exactly one
 * source page, so an explicitly plural request can run out of trusted places to look while still
 * being one picture short. It is a real widening of what Orbit fetches, so almost all of this file
 * is about the refusals.
 *
 * <p><b>The trust line is the point.</b> A hint says where a picture may be. It is never a citation
 * for anything the answer said, never merges into the answer's sources, and never becomes an "Open
 * source" claim about the answer's facts. Bare URLs in prose are not hints, the prompt is never
 * read, and everything that survives still has to pass the same URL policy a cited page does.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class RichAnswerDiscoveryHintTest {

    private static final List<String> MALLARD =
            RichAnswerSubject.tokensOf("show me pics of a mallard duck");

    private static List<RichAnswerDiscoveryHint> hints(String answer) {
        return RichAnswerDiscoveryHint.from(answer, MALLARD, Collections.<String>emptyList());
    }

    private static List<String> urls(List<RichAnswerDiscoveryHint> hints) {
        List<String> out = new java.util.ArrayList<>();
        for (RichAnswerDiscoveryHint hint : hints) out.add(hint.url);
        return out;
    }

    // ---- accepted --------------------------------------------------------------------------------

    /** A link whose label is about pictures is a place to look. */
    @Test public void aLinkLabelledAsPicturesIsAHint() {
        List<RichAnswerDiscoveryHint> found =
                hints("[Mallard photos](https://gallery.example.org/birds/mallard)");
        assertEquals(1, found.size());
        assertEquals(RichAnswerDiscoveryHint.Kind.PAGE, found.get(0).kind);
    }

    /** So is one whose path is, even when its label says nothing. */
    @Test public void aLinkWhosePathIsAboutPicturesIsAHint() {
        assertEquals(1, hints("[More](https://example.org/gallery/mallard-duck)").size());
        assertEquals(1, hints(
                "[Commons](https://commons.wikimedia.org/wiki/File:Mallard-Duck.jpg)").size());
    }

    /** A Markdown image is the picture itself and is followed as one. */
    @Test public void aMarkdownImageIsADirectImageHint() {
        List<RichAnswerDiscoveryHint> found =
                hints("![A mallard](https://cdn.example.org/photos/mallard.jpg)");
        assertEquals(1, found.size());
        assertTrue(found.get(0).isImage());
    }

    /** An ordinary link that happens to end in an image extension is treated as the picture. */
    @Test public void aLinkToAnImageFileIsADirectImageHint() {
        List<RichAnswerDiscoveryHint> found =
                hints("[See it](https://cdn.example.org/assets/mallard-duck.jpg)");
        assertEquals(1, found.size());
        assertTrue(found.get(0).isImage());
    }

    /** A link whose label names the subject strongly enough is worth reading. */
    @Test public void aLinkNamingTheSubjectIsAHint() {
        assertEquals(1, hints("[Mallard duck](https://birds.example.org/species/anas)").size());
        assertTrue("one shared word out of several is a coincidence, not a subject",
                hints("[Duck](https://birds.example.org/species/anas)").isEmpty());
    }

    // ---- refused ---------------------------------------------------------------------------------

    /** A bare URL in prose is a mention, not a pointer at a gallery. */
    @Test public void aBareUrlInProseIsNeverAHint() {
        assertTrue(hints("You can read more at https://gallery.example.org/photos/mallard "
                + "if you like.").isEmpty());
    }

    /** An ordinary link about the topic is not a link about pictures. */
    @Test public void anOrdinaryTopicLinkIsNotAHint() {
        assertTrue(hints("[Conservation status](https://iucn.example.org/status/22680186)")
                .isEmpty());
    }

    /** Everything the URL policy refuses for a cited page is refused here identically. */
    @Test public void unsafeAddressesAreRefusedExactlyAsCitedPagesAre() {
        String[] unsafe = {
                "[Photos](http://gallery.example.org/photos/mallard)",
                "[Photos](https://localhost/photos/mallard)",
                "[Photos](https://192.168.1.10/photos/mallard)",
                "[Photos](https://user:pass@gallery.example.org/photos)",
                "![A mallard](http://cdn.example.org/photos/mallard.jpg)",
                "![A mallard](https://10.0.0.5/photos/mallard.jpg)"};
        for (String answer : unsafe) assertTrue(answer, hints(answer).isEmpty());
    }

    /** A page already read as a trusted source is never fetched twice. */
    @Test public void aPageAlreadyReadIsNotOfferedAgain() {
        String page = "https://gallery.example.org/photos/mallard";
        assertTrue(RichAnswerDiscoveryHint.from("[Mallard photos](" + page + ")", MALLARD,
                Collections.singletonList(page)).isEmpty());
    }

    /** And neither is a resized copy of a picture already in hand. */
    @Test public void aRenditionOfAnAlreadyKnownPictureIsNotOfferedAgain() {
        String big = "https://upload.wikimedia.org/wikipedia/commons/"
                + "thumb/1/1e/Mallard-Duck.jpg/1920px-Mallard-Duck.jpg";
        String small = "https://upload.wikimedia.org/wikipedia/commons/"
                + "thumb/1/1e/Mallard-Duck.jpg/960px-Mallard-Duck.jpg";
        assertTrue(RichAnswerDiscoveryHint.from("![A mallard](" + small + ")", MALLARD,
                Collections.singletonList(big)).isEmpty());
    }

    /** The same link written twice is one place to look. */
    @Test public void repeatedLinksCollapse() {
        String page = "https://gallery.example.org/photos/mallard";
        assertEquals(1, hints("[Photos](" + page + ") and again [Photos](" + page + ")").size());
    }

    // ---- bounds ----------------------------------------------------------------------------------

    /** However many links an answer carries, only a handful are ever collected. */
    @Test public void hintsAreStrictlyBounded() {
        StringBuilder answer = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            answer.append("[Mallard photos ").append(i)
                    .append("](https://gallery.example.org/photos/mallard-").append(i)
                    .append(")\n");
        }
        List<RichAnswerDiscoveryHint> found = hints(answer.toString());
        assertTrue(found.size() <= RichAnswerDiscoveryHint.MAX_HINTS);
        assertEquals(4, RichAnswerDiscoveryHint.MAX_HINTS);
    }

    /** Nothing at all comes out of an answer with nothing in it. */
    @Test public void anAnswerWithNoLinksYieldsNothing() {
        assertTrue(hints("").isEmpty());
        assertTrue(hints(null).isEmpty());
        assertTrue(hints("The mallard is a dabbling duck of the genus Anas.").isEmpty());
    }

    // ---- the trust line --------------------------------------------------------------------------

    /**
     * A hint never becomes provenance for anything the answer said.
     *
     * <p>Asserted at the two places that could break it: the resolver that turns hints into
     * sources, and the provenance layer that decides what a citation is.
     */
    @Test public void hintsAreNeverPromotedIntoFactualProvenance() {
        String provenance = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/RichAnswerProvenance.java");
        assertFalse("provenance must know nothing about hints",
                provenance.contains("RichAnswerDiscoveryHint"));
        assertTrue("and must still refuse ordinary links in prose",
                provenance.contains("SourceLinkUtil.sourceUrl"));
        assertFalse(provenance.contains("SourceLinkUtil.firstUrl"));

        // Asserted on call shapes rather than on words, because the class documentation
        // legitimately names the very types it must never use.
        String hint = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/RichAnswerDiscoveryHint.java");
        for (String forbidden : new String[]{
                "reply.sourceUrls", "AssistantReply ", "ConversationStore.", "SourceLinkUtil.",
                "Prefs.", "OrbitRequestManager."}) {
            assertFalse("a hint must never reach " + forbidden, hint.contains(forbidden));
        }
    }

    /** Hints are collected from the answer, never from the prompt or an action's arguments. */
    @Test public void hintsAreReadFromTheAnswerAndNothingElse() {
        String coordinator = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/RichAnswerCoordinator.java");
        assertTrue(coordinator.contains("RichAnswerDiscoveryHint.from(answer, subject, pages)"));
        assertFalse("the prompt is never mined for links",
                coordinator.contains("RichAnswerDiscoveryHint.from(prompt"));
    }

    /** The gate is every condition at once, not any one of them. */
    @Test public void theFallbackIsGatedOnAnExplicitlyPluralStrongVisualRequest() {
        String coordinator = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/RichAnswerCoordinator.java");
        assertTrue(coordinator.contains(
                "images > 1 && strong && RichAnswerRelevance.requestsMultipleImages(prompt)"));
    }

    /** A hint fetch goes through the same policy every other fetch does. */
    @Test public void hintsUseTheSameSafetyPolicyAsCitedPages() {
        String hint = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/RichAnswerDiscoveryHint.java");
        assertTrue(hint.contains("RichAnswerUrlPolicy.isFetchablePageUrl"));
        assertTrue(hint.contains("RichAnswerUrlPolicy.isFetchableImageUrl"));
        for (String ownTransport : new String[]{
                "HttpURLConnection", "openConnection", "new URL(", "Socket"}) {
            assertFalse("hint extraction must never do its own HTTP",
                    hint.contains(ownTransport));
        }
    }

    /** Kinds are not interchangeable: a page is read as markup, a picture is fetched as one. */
    @Test public void everyHintKnowsWhichKindItIs() {
        List<RichAnswerDiscoveryHint> found = hints(
                "[Mallard photos](https://gallery.example.org/birds/mallard)\n"
                        + "![A mallard](https://cdn.example.org/photos/mallard.jpg)");
        assertEquals(2, found.size());
        assertEquals(Arrays.asList("https://cdn.example.org/photos/mallard.jpg",
                        "https://gallery.example.org/birds/mallard"),
                urls(found));
        assertTrue(found.get(0).isImage());
        assertFalse(found.get(1).isImage());
    }
}
