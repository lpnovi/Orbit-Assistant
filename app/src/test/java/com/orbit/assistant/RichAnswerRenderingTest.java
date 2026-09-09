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
 * What gets drawn when both image paths have something to say about the same answer.
 *
 * <p>Orbit has two ways a picture reaches a response: the structured Rich Answer, which Orbit found
 * and can attribute, and a Markdown image the model wrote into its own text. They can collide -
 * a model that writes {@code ![](…)} for the same picture Orbit already attached would produce it
 * twice, one above the other, which reads as a bug even though both halves worked.
 *
 * <p>The rule is deliberately narrow: an exact address match, after the same normalisation the
 * fetch uses, and nothing cleverer. Matching on subject or host would eventually hide a picture
 * that was genuinely different, which is worse than showing one twice.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class RichAnswerRenderingTest {

    private static ResponseBlocks.Block imageBlock(String markdown) {
        List<ResponseBlocks.Block> blocks = ResponseBlocks.parse(markdown);
        assertEquals("fixture must parse to one image block", 1, blocks.size());
        assertEquals(ResponseBlocks.Kind.IMAGE, blocks.get(0).kind);
        return blocks.get(0);
    }

    private static RichAnswerImage attached(String imageUrl, String sourceUrl) {
        return RichAnswerImage.webSource(imageUrl, sourceUrl, "A robin", "a robin", 0);
    }

    // ---- duplicate suppression ---------------------------------------------------------------------

    /** The same picture written twice is drawn once. */
    @Test public void aMarkdownImageMatchingAnAttachedOneIsSuppressed() {
        String url = "https://cdn.example.org/photographs/robin.jpg";
        assertTrue(OrbitRichResponseRenderer.duplicatesRichImage(
                imageBlock("![A robin](" + url + ")"),
                Collections.singletonList(attached(url, "https://example.org/birds/robin"))));
    }

    /**
     * A model writing the source page as though it were the picture is also a duplicate.
     *
     * <p>Exactly the shape that produced the failed card on a real device: the model wrote a
     * Commons file page inside Markdown image syntax, while Orbit had already resolved that same
     * page into its real picture. Drawing both would mean one good picture and one dead card.
     */
    @Test public void aMarkdownImagePointingAtTheSourcePageIsSuppressed() {
        String page = "https://commons.example.org/wiki/File:Robin.jpg";
        assertTrue(OrbitRichResponseRenderer.duplicatesRichImage(
                imageBlock("![A robin](" + page + ")"),
                Collections.singletonList(
                        attached("https://upload.example.org/commons/Robin.jpg", page))));
    }

    /** Addresses that differ only in escaping are the same address. */
    @Test public void suppressionSeesThroughEncodingDifferences() {
        assertTrue(OrbitRichResponseRenderer.duplicatesRichImage(
                imageBlock("![A robin](https://cdn.example.org/a/Café.jpg)"),
                Collections.singletonList(attached(
                        "https://cdn.example.org/a/Caf%C3%A9.jpg", "https://example.org/p"))));
    }

    /** A genuinely different picture is always drawn. */
    @Test public void aDifferentMarkdownImageIsNotSuppressed() {
        assertFalse(OrbitRichResponseRenderer.duplicatesRichImage(
                imageBlock("![A wren](https://cdn.example.org/photographs/wren.jpg)"),
                Collections.singletonList(attached(
                        "https://cdn.example.org/photographs/robin.jpg",
                        "https://example.org/birds/robin"))));
    }

    /** Same host, different file: still a different picture. */
    @Test public void suppressionDoesNotMatchOnHostAlone() {
        assertFalse(OrbitRichResponseRenderer.duplicatesRichImage(
                imageBlock("![Male](https://cdn.example.org/photographs/robin-male.jpg)"),
                Collections.singletonList(attached(
                        "https://cdn.example.org/photographs/robin-female.jpg",
                        "https://example.org/birds/robin"))));
    }

    /** With nothing attached, nothing is ever suppressed. */
    @Test public void anAnswerWithNoAttachedPictureSuppressesNothing() {
        ResponseBlocks.Block block =
                imageBlock("![A robin](https://cdn.example.org/photographs/robin.jpg)");
        assertFalse(OrbitRichResponseRenderer.duplicatesRichImage(block, null));
        assertFalse(OrbitRichResponseRenderer.duplicatesRichImage(
                block, Collections.<RichAnswerImage>emptyList()));
        assertFalse(OrbitRichResponseRenderer.duplicatesRichImage(null,
                Collections.singletonList(attached(
                        "https://cdn.example.org/photographs/robin.jpg",
                        "https://example.org/p"))));
    }

    /** A block that is not an image is never suppressed by this rule. */
    @Test public void onlyImageBlocksAreEverSuppressed() {
        List<ResponseBlocks.Block> blocks = ResponseBlocks.parse(
                "A robin is a small bird.\n\n![A robin](https://cdn.example.org/robin.jpg)");
        List<RichAnswerImage> attached = Collections.singletonList(
                attached("https://cdn.example.org/robin.jpg", "https://example.org/p"));
        assertFalse("prose must always be drawn",
                OrbitRichResponseRenderer.duplicatesRichImage(blocks.get(0), attached));
        assertTrue(OrbitRichResponseRenderer.duplicatesRichImage(blocks.get(1), attached));
    }

    /**
     * Suppression changes what is drawn and never what was said.
     *
     * <p>The answer's own text is what the user copies, what Orbit speaks, and what goes back to a
     * model as history. Rewriting it to remove a duplicate picture would corrupt all three.
     */
    @Test public void theAnswerTextIsNeverRewritten() {
        String renderer = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OrbitRichResponseRenderer.java");
        int at = renderer.indexOf("suppressesMarkdownImage(block, richImages)) continue;");
        assertTrue("suppression must be a skipped draw", at > 0);
        for (String rewrite : new String[]{
                "source = source.replace", "rawText.replace(\"![\"", "stripMarkdownImages"}) {
            assertFalse("the answer text must not be edited: " + rewrite,
                    renderer.contains(rewrite));
        }
    }

    // ---- the unavailable state -----------------------------------------------------------------------

    /**
     * A structured picture that will not load offers the source page, not the raw file.
     *
     * <p>The distinction matters. For a Markdown image all Orbit has is the address the model
     * wrote, so "Open image" is the only honest offer; for a structured one Orbit knows the page
     * the picture belongs to, and that page is what a person actually wants to see.
     */
    @Test public void theRichCardOffersTheSourcePageRatherThanTheRawImage() {
        String card = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/RichAnswerCardView.java");
        assertTrue(card.contains("\"Open source\""));
        assertFalse("the raw file is not a destination for a person",
                card.contains("\"Open image\""));
        assertTrue("and the address is revalidated at the tap",
                card.contains("RichAnswerUrlPolicy.isOpenableWebUrl(image.sourceUrl)"));
        assertTrue("failure must stay quiet rather than becoming a panel",
                card.contains("\"Image unavailable\""));
    }

    /** The Markdown card keeps the fallback it has always had. */
    @Test public void theMarkdownCardKeepsItsOwnFallback() {
        String renderer = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OrbitRichResponseRenderer.java");
        assertTrue(renderer.contains("\"Open image\""));
        assertTrue(renderer.contains("\"Image unavailable\""));
    }

    /** Both paths draw through the one loader, so their transport cannot drift apart. */
    @Test public void bothImagePathsUseTheCanonicalLoader() {
        String markdown = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OrbitRichResponseRenderer.java");
        String rich = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/RichAnswerCardView.java");
        assertTrue(markdown.contains("RemoteImageLoader.load("));
        assertTrue(rich.contains("RemoteImageLoader.loadDetailed("));
        for (String source : new String[]{markdown, rich}) {
            for (String ownTransport : new String[]{
                    "HttpURLConnection", "openConnection", "new URL(", "Socket"}) {
                assertFalse("a surface must never do its own HTTP: " + ownTransport,
                        source.contains(ownTransport));
            }
        }
    }

    /** An answer with no pictures renders exactly as it did before any of this existed. */
    @Test public void anOrdinaryAnswerIsUnaffected() {
        List<ResponseBlocks.Block> blocks = ResponseBlocks.parse(
                "## Robins\n\nA small bird.\n\n- red breast\n- common in Europe");
        for (ResponseBlocks.Block block : blocks) {
            assertFalse(OrbitRichResponseRenderer.duplicatesRichImage(block, null));
        }
        assertFalse(RichAnswerPlacement.hasAnyImage(null));
        assertFalse(RichAnswerPlacement.hasAnyImage(Collections.<RichAnswerImage>emptyList()));
    }

    // ---- Beta 6: renditions and quiet failures ----------------------------------------------------

    private static final String COMMONS_UPLOAD =
            "https://upload.wikimedia.org/wikipedia/commons/thumb/1/1e/Mallard-Duck.jpg/";

    /**
     * A model writing a different width of the picture Orbit attached is writing the same picture.
     *
     * <p>The shape the device produced: the structured image resolved to the 1920 rendition while
     * the model's own Markdown named the 1280. Two addresses, one photograph, and drawing both
     * would put the same duck on screen twice.
     */
    @Test public void aMarkdownImageAtADifferentResolutionIsSuppressed() {
        RichAnswerImage rich = attached(COMMONS_UPLOAD + "1920px-Mallard-Duck.jpg",
                "https://commons.wikimedia.org/wiki/File:Mallard-Duck.jpg");
        assertTrue(OrbitRichResponseRenderer.duplicatesRichImage(
                imageBlock("![A mallard](" + COMMONS_UPLOAD + "1280px-Mallard-Duck.jpg)"),
                Collections.singletonList(rich)));
    }

    /** A genuinely different photograph on the same host is still drawn. */
    @Test public void aDifferentPhotographIsNotSuppressedByTheRenditionRule() {
        RichAnswerImage rich = attached(COMMONS_UPLOAD + "1920px-Mallard-Duck.jpg",
                "https://commons.wikimedia.org/wiki/File:Mallard-Duck.jpg");
        assertFalse(OrbitRichResponseRenderer.duplicatesRichImage(
                imageBlock("![A mallard hen](https://upload.wikimedia.org/wikipedia/commons/"
                        + "thumb/2/2a/Mallard-Female.jpg/1280px-Mallard-Female.jpg)"),
                Collections.singletonList(rich)));
    }

    /**
     * A model-written image that will not load collapses to one quiet line.
     *
     * <p>The physical failure this replaces was a reserved 150dp box, a heavy card and the words
     * <i>HTTP 404</i> sitting in the middle of an otherwise correct answer. The answer looked
     * broken when only a decoration was.
     */
    @Test public void aFailedMarkdownImageCollapsesToOneQuietLine() {
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        android.widget.FrameLayout frame = new android.widget.FrameLayout(context);
        frame.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(context, 150)));
        frame.addView(new android.widget.ProgressBar(context));

        OrbitRichResponseRenderer.showImageFailure(context, frame, "A mallard duck",
                "https://cdn.example.org/photos/mallard.jpg", "HTTP 404", android.graphics.Color.BLACK);

        assertEquals("the reserved picture area must be released",
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                frame.getLayoutParams().height);
        String text = textOf(frame);
        assertTrue("it still says what happened", text.contains("Image unavailable"));
        assertFalse("but never in the transport's words", text.contains("404"));
        assertFalse(text.contains("could not be loaded"));
        assertTrue("and opening the original is still offered", text.contains("Open image"));
    }

    /** An address Orbit refused for safety is never offered for opening. */
    @Test public void aBlockedMarkdownImageOffersNothingToOpen() {
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        android.widget.FrameLayout frame = new android.widget.FrameLayout(context);
        frame.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(context, 150)));

        OrbitRichResponseRenderer.showImageFailure(context, frame, "", "https://127.0.0.1/a.jpg",
                "Orbit blocked this private or unsafe image address", android.graphics.Color.BLACK);

        String text = textOf(frame);
        assertTrue(text.contains("Image unavailable"));
        assertFalse("a refused address is not somewhere to send anybody",
                text.contains("Open image"));
    }

    /** No failure path may reserve a picture-sized hole in the answer. */
    @Test public void noFailurePathReservesAPictureSizedArea() {
        String renderer = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OrbitRichResponseRenderer.java");
        int failure = renderer.indexOf("static void showImageFailure");
        int end = renderer.indexOf("\n    private static View linkedFallback", failure);
        String body = renderer.substring(failure, end);
        assertTrue("the reserved height is released before anything is drawn",
                body.contains("ViewGroup.LayoutParams.WRAP_CONTENT"));
        assertFalse("and the transport category never reaches the chat",
                body.contains("error == null || error.isEmpty()"));
        assertFalse("no button-sized failure panel", body.contains("new Button(c)"));
    }

    private static String textOf(android.view.View view) {
        if (view instanceof android.widget.TextView) {
            return ((android.widget.TextView) view).getText().toString() + " ";
        }
        StringBuilder out = new StringBuilder();
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) out.append(textOf(group.getChildAt(i)));
        }
        return out.toString();
    }

    /** Two different attached pictures both survive the duplicate check. */
    @Test public void twoDistinctAttachedPicturesAreBothKept() {
        List<RichAnswerImage> attached = Arrays.asList(
                attached("https://cdn.example.org/a.jpg", "https://example.org/a"),
                attached("https://cdn.example.org/b.jpg", "https://example.org/b"));
        assertTrue(OrbitRichResponseRenderer.duplicatesRichImage(
                imageBlock("![](https://cdn.example.org/b.jpg)"), attached));
        assertFalse(OrbitRichResponseRenderer.duplicatesRichImage(
                imageBlock("![](https://cdn.example.org/c.jpg)"), attached));
    }
}
