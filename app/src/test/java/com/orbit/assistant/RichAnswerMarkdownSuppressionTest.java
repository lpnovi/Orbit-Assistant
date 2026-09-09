package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which of Orbit's two remote-image systems is allowed to draw.
 *
 * <p><b>The problem Beta 7 ends.</b> Orbit had two ways a picture from the web reached a response,
 * and they were both switched on at once. The structured Rich Answer is one Orbit found on a page
 * the answer cited, validated, attributed, and can open in its own viewer and save to the Vault. A
 * Markdown {@code ![](…)} is a URL the model typed from memory, with no page behind it, no
 * attribution, no viewer and a 404 far more often than anybody would like. On a real device an
 * answer about mallards drew both, twice over, and the user saw four pictures of one duck.
 *
 * <p>So the rule is decided rather than negotiated: when a message carries any structured picture,
 * structured wins and the model's own remote images are not drawn. Not only the ones whose
 * addresses match - all of them, because the whole reason Beta 6 was not enough is that the
 * model's URL and Orbit's URL were different addresses for the same photograph.
 *
 * <p>And when there is no structured picture, nothing changes at all.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class RichAnswerMarkdownSuppressionTest {

    /** The word that only appears if the model's own picture was drawn. */
    private static final String MARKDOWN_ALT = "Model written figure";
    private static final String MARKDOWN_URL = "https://cdn.example.net/model/figure.jpg";
    private static final String ANSWER =
            "A mallard drake has a green head.\n\n"
            + "![" + MARKDOWN_ALT + "](" + MARKDOWN_URL + ")\n\n"
            + "See the [field guide](https://example.org/guide) for more.";

    private Context context;
    private RemoteImageLoader.Transport previousTransport;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        // Installed so that nothing in this file can reach the network, whichever path draws.
        previousTransport = RemoteImageLoader.installTransportForTest(new OfflineTransport());
    }

    @After public void tearDown() {
        RemoteImageLoader.installTransportForTest(previousTransport);
    }

    private static final class OfflineTransport implements RemoteImageLoader.Transport {
        @Override public boolean allowsHost(String url) { return false; }

        @Override public RemoteImageLoader.Response open(String url) {
            return new RemoteImageLoader.Response(404, "text/plain", null, 0, null);
        }
    }

    private static RichAnswerImage structured(String imageUrl, String page, String caption) {
        return RichAnswerImage.webSource(imageUrl, page, caption, caption, 0);
    }

    private static List<RichAnswerImage> attached() {
        return Collections.singletonList(structured(
                "https://cdn.example.org/photos/drake.jpg", "https://example.org/birds/mallard",
                "Orbit sourced photograph"));
    }

    private View render(List<RichAnswerImage> richImages) {
        return OrbitRichResponseRenderer.render(context, ANSWER, UiKit.SURFACE, false, richImages);
    }

    private static String allText(View view) {
        if (view instanceof TextView) return ((TextView) view).getText() + " ";
        StringBuilder out = new StringBuilder();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) out.append(allText(group.getChildAt(i)));
        }
        return out.toString();
    }

    private static ResponseBlocks.Block imageBlock(String markdown) {
        List<ResponseBlocks.Block> blocks = ResponseBlocks.parse(markdown);
        assertEquals("fixture must parse to one image block", 1, blocks.size());
        return blocks.get(0);
    }

    // ---- structured wins ---------------------------------------------------------------------------

    /**
     * A structured picture removes the model's own image from the drawing, address or no address.
     *
     * <p>The Markdown URL here has nothing to do with the structured one. Beta 6 compared the two
     * addresses, correctly found them different, and drew both.
     */
    @Test public void aStructuredPictureSuppressesAnUnrelatedMarkdownImage() {
        String drawn = allText(render(attached()));
        assertFalse("the model's own picture must not be drawn", drawn.contains(MARKDOWN_ALT));
        assertFalse("and it must leave no residue behind either",
                drawn.contains("Image unavailable"));
        assertTrue("the structured picture is still there", drawn.contains("Orbit sourced photograph"));
        assertTrue("and so is every word of the answer", drawn.contains("green head"));
        assertTrue("ordinary links are untouched", drawn.contains("field guide"));
    }

    /** Two structured pictures suppress the model's image just as one does. */
    @Test public void twoStructuredPicturesAlsoSuppressIt() {
        List<RichAnswerImage> two = Arrays.asList(
                structured("https://cdn.example.org/photos/drake.jpg",
                        "https://example.org/birds/mallard", "First sourced photograph"),
                structured("https://cdn.example.org/photos/hen.jpg",
                        "https://example.org/birds/mallard", "Second sourced photograph"));
        String drawn = allText(render(two));
        assertFalse(drawn.contains(MARKDOWN_ALT));
        assertTrue(drawn.contains("First sourced photograph"));
        assertTrue(drawn.contains("Second sourced photograph"));
    }

    // ---- and loses gracefully ----------------------------------------------------------------------

    /** With nothing structured to show, the model's picture is the only picture and still renders. */
    @Test public void withNoStructuredPictureTheMarkdownImageStillRenders() {
        String drawn = allText(render(null));
        assertTrue("the fallback path is not removed, only outranked",
                drawn.contains(MARKDOWN_ALT));
        assertTrue(drawn.contains("green head"));
    }

    /** An empty list is the same as none. */
    @Test public void anEmptyStructuredListChangesNothing() {
        assertTrue(allText(render(new ArrayList<RichAnswerImage>())).contains(MARKDOWN_ALT));
    }

    // ---- the asynchronous transition ---------------------------------------------------------------

    /**
     * The two renders around an attachment, in order.
     *
     * <p>Rich Answers resolve after the answer is on screen, so a model-written image really is
     * drawn for a second or two before the structured one lands. What must never happen is the
     * settled state containing both, and a surface that redraws the message from storage gets that
     * for free - which is what this asserts, one render before and one render after.
     */
    @Test public void thererenderAfterAttachmentLeavesNoCompetingImage() {
        String before = allText(render(null));
        assertTrue("before the picture resolves, the model's own is what there is",
                before.contains(MARKDOWN_ALT));

        String after = allText(render(attached()));
        assertFalse("and after it resolves, it is gone rather than sitting above it",
                after.contains(MARKDOWN_ALT));
        assertTrue(after.contains("Orbit sourced photograph"));
    }

    /** Both surfaces redraw the whole message, so no old view can survive the transition. */
    @Test public void bothSurfacesRebuildTheMessageRatherThanPatchingIt() {
        String chat = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/ChatActivity.java");
        assertTrue("the chat list is cleared before it is redrawn",
                chat.contains("messages.removeAllViews();"));
        assertTrue(chat.contains("RichAnswerCoordinator.addListener(richImageListener)"));
        String session = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OrbitSession.java");
        assertTrue("and so is the overlay", session.contains("renderConversation();"));
    }

    // ---- what suppression is and is not ------------------------------------------------------------

    /** Suppression is a skipped draw. The answer's own text keeps every character. */
    @Test public void theStoredAnswerIsNeverRewritten() {
        assertTrue("the fixture itself still carries the Markdown", ANSWER.contains("!["));
        String renderer = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OrbitRichResponseRenderer.java");
        for (String rewrite : new String[]{
                "source = source.replace", "rawText.replace(\"![\"", "stripMarkdownImages"}) {
            assertFalse("the answer text must not be edited: " + rewrite,
                    renderer.contains(rewrite));
        }
    }

    /** Only image blocks are affected. Prose, headings, lists, code and links are not. */
    @Test public void onlyRemoteImageBlocksAreSuppressed() {
        List<ResponseBlocks.Block> blocks = ResponseBlocks.parse(ANSWER);
        int images = 0;
        for (ResponseBlocks.Block block : blocks) {
            boolean suppressed = OrbitRichResponseRenderer.suppressesMarkdownImage(block, attached());
            if (block.kind == ResponseBlocks.Kind.IMAGE) {
                images++;
                assertTrue("an image block is suppressed", suppressed);
            } else {
                assertFalse("everything else is drawn: " + block.kind, suppressed);
            }
        }
        assertEquals(1, images);
    }

    /** A picture that is already on this device is not competing with anything. */
    @Test public void aLocalImageBlockIsNotSuppressed() {
        assertFalse(OrbitRichResponseRenderer.isRemoteImageBlock(
                imageBlock("![A saved photo](content://com.orbit.assistant/vault/1)")));
        assertFalse(OrbitRichResponseRenderer.suppressesMarkdownImage(
                imageBlock("![A saved photo](content://com.orbit.assistant/vault/1)"), attached()));
        assertTrue(OrbitRichResponseRenderer.isRemoteImageBlock(
                imageBlock("![A photo](https://cdn.example.net/a.jpg)")));
    }

    /** With nothing structured attached, nothing is suppressed. */
    @Test public void nothingIsSuppressedWithoutAStructuredPicture() {
        ResponseBlocks.Block block = imageBlock("![A photo](" + MARKDOWN_URL + ")");
        assertFalse(OrbitRichResponseRenderer.suppressesMarkdownImage(block, null));
        assertFalse(OrbitRichResponseRenderer.suppressesMarkdownImage(
                block, Collections.<RichAnswerImage>emptyList()));
    }

    // ---- the instruction that stops it at the source -----------------------------------------------

    /**
     * The provider is told not to write an image when Orbit is going to supply one.
     *
     * <p>Renderer suppression is the defence that has to hold, because a model can always ignore an
     * instruction. This is the cheaper half: an image the model never writes is one that never
     * flashes on screen, never 404s, and never has to be suppressed.
     */
    @Test public void theModelIsToldNotToWriteImagesWhenRichAnswersIsOn() {
        Prefs.get(context).edit().putBoolean(Prefs.RICH_ANSWERS, true).commit();
        String policy = ChatGptClient.imagePolicy(context);
        assertTrue(policy.contains("Do not emit Markdown image syntax"));
        assertTrue("ordinary links must stay explicitly allowed",
                policy.contains("Ordinary Markdown links"));
        assertFalse("and the old permission must not be sent alongside it",
                policy.contains("Only use Markdown image syntax"));
    }

    /** With Rich Answers off, the model keeps the permission it has always had. */
    @Test public void theModelKeepsItsImagePermissionWhenRichAnswersIsOff() {
        Prefs.get(context).edit().putBoolean(Prefs.RICH_ANSWERS, false).commit();
        String policy = ChatGptClient.imagePolicy(context);
        assertTrue(policy.contains("Only use Markdown image syntax"));
        assertFalse(policy.contains("Do not emit Markdown image syntax"));
    }

    /**
     * The model is never allowed to say how many pictures will appear, or that they differ.
     *
     * <p>It cannot know either. Discovery runs after the answer is written and a plural request
     * that finds one photograph correctly shows one, so "these are two separate photographs" is a
     * sentence that is wrong exactly when the invariant is working.
     */
    @Test public void theModelIsToldNotToCountOrCompareThePictures() {
        Prefs.get(context).edit().putBoolean(Prefs.RICH_ANSWERS, true).commit();
        String policy = ChatGptClient.imagePolicy(context);
        assertTrue(policy.contains("never promise a number of pictures"));
        assertTrue(policy.contains("never claim the pictures are different from each other"));
        assertTrue("and it is given wording that works instead",
                policy.contains("Here are some useful visual references"));
    }

    /** The instruction is one or the other, never both, and is chosen per request. */
    @Test public void theTwoPoliciesAreMutuallyExclusiveAndReadAtRequestTime() {
        String client = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/ChatGptClient.java");
        assertTrue(client.contains("SYSTEM + imagePolicy(context)"));
        assertTrue(client.contains("RichAnswerCoordinator.enabled(context) ? RICH_ANSWERS_SYSTEM : MARKDOWN_IMAGES_SYSTEM"));
    }

    // ---- fixtures kept honest ----------------------------------------------------------------------

    /** The offline transport really is offline, so nothing here can have reached the web. */
    @Test public void noTestInThisFileCanReachTheNetwork() {
        Map<String, String> unused = new LinkedHashMap<>();
        assertTrue(unused.isEmpty());
        assertFalse(new OfflineTransport().allowsHost(MARKDOWN_URL));
        assertEquals(404, new OfflineTransport().open(MARKDOWN_URL).status);
    }
}
