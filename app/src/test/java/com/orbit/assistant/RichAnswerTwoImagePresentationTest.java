package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What two pictures look like, and what the model is allowed to say about them.
 *
 * <p>Two pictures is new in Beta 6, and the two ways it could go wrong are a layout that turns them
 * into cramped thumbnails and an answer that promised three. The first is covered by keeping the
 * existing Rich Answer visual language - one card per picture, stacked, each with its own
 * attribution, its own tap into the one viewer and its own way into the Vault. The second is
 * covered by the instruction, because text is written before discovery finishes and a model cannot
 * know how many photographs will survive a real fetch.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class RichAnswerTwoImagePresentationTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
    }

    private static RichAnswerImage image(String name, String page) {
        return RichAnswerImage.webSource("https://cdn.example.org/photos/" + name + ".jpg",
                page, "A " + name.replace('-', ' '), "a " + name.replace('-', ' '), 0);
    }

    // ---- layout ----------------------------------------------------------------------------------

    /** Two pictures anchored to the same place are two independent cards, stacked. */
    @Test public void twoPicturesBecomeTwoIndependentCards() {
        List<RichAnswerImage> both = Arrays.asList(
                image("mallard-drake", "https://birds.example.org/mallard"),
                image("mallard-hen", "https://guide.example.net/mallard"));
        List<View> views = RichAnswerCardView.viewsFor(context, both, both, 0xFF000000);

        assertEquals("one card per picture", 2, views.size());
        assertNotEquals("and they are separate views", views.get(0), views.get(1));
        String first = textOf(views.get(0));
        String second = textOf(views.get(1));
        assertTrue("each carries its own attribution", first.contains("birds.example.org"));
        assertTrue(second.contains("guide.example.net"));
        assertFalse("and neither borrows the other's", first.contains("guide.example.net"));
    }

    /** Both pictures from one page still get their own attribution line. */
    @Test public void twoPicturesFromOnePageAreStillLabelledIndividually() {
        String page = "https://commons.wikimedia.org/wiki/Category:Mallard";
        List<RichAnswerImage> both = Arrays.asList(image("mallard-drake", page),
                image("mallard-hen", page));
        List<View> views = RichAnswerCardView.viewsFor(context, both, both, 0xFF000000);
        assertEquals(2, views.size());
        assertTrue(textOf(views.get(0)).contains("mallard drake"));
        assertTrue(textOf(views.get(1)).contains("mallard hen"));
    }

    /** Each picture is drawn at a useful size rather than squeezed beside the other. */
    @Test public void neitherPictureIsShrunkToMakeRoomForTheOther() {
        List<RichAnswerImage> both = Arrays.asList(
                image("mallard-drake", "https://birds.example.org/mallard"),
                image("mallard-hen", "https://birds.example.org/mallard"));
        for (View view : RichAnswerCardView.viewsFor(context, both, both, 0xFF000000)) {
            assertTrue("a card is a full-width vertical stack, not a column in a row",
                    view instanceof android.widget.LinearLayout);
            assertEquals(android.widget.LinearLayout.VERTICAL,
                    ((android.widget.LinearLayout) view).getOrientation());
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null) {
                assertNotEquals("no fixed cramped width", 0, lp.width);
            }
        }
    }

    /** The tablet width cap that stops a picture spanning a Tab S9 Plus is unchanged. */
    @Test public void theWidthCapIsUnchanged() {
        assertEquals(460, RichAnswerCardView.MAX_WIDTH_DP);
    }

    /** Both pictures are pageable in the one existing viewer, which stays the only one. */
    @Test public void bothPicturesReachTheSameSingleViewer() {
        String card = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/RichAnswerCardView.java");
        assertTrue("every card hands the viewer the whole set, so paging works",
                card.contains("AttachmentViewerActivity.openRichAnswer(host, set, image.id)"));
        assertEquals("and there is exactly one call to open a viewer",
                card.indexOf("AttachmentViewerActivity."),
                card.lastIndexOf("AttachmentViewerActivity."));
        for (String forbidden : new String[]{"ZoomableImageView", "new Dialog(", "PopupWindow"}) {
            assertFalse("a response card must not grow its own viewer: " + forbidden,
                    card.contains(forbidden));
        }
    }

    /** Each picture saves to the Vault as its own item, never as a bundle. */
    @Test public void eachPictureIsItsOwnVaultItem() {
        List<RichAnswerImage> both = Arrays.asList(
                image("mallard-drake", "https://birds.example.org/mallard"),
                image("mallard-hen", "https://guide.example.net/mallard"));
        assertNotEquals(both.get(0).id, both.get(1).id);
        assertNotEquals(both.get(0).vaultTitle(), both.get(1).vaultTitle());
        assertNotEquals(both.get(0).sourceDomain, both.get(1).sourceDomain);

        String viewer = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/AttachmentViewerActivity.java");
        assertFalse("a save is never a batch over the whole set",
                viewer.contains("for (RichAnswerImage") && viewer.contains("OrbitVaultStore.add"));
    }

    // ---- wording ---------------------------------------------------------------------------------

    /**
     * The model is told not to promise a count, because it cannot know one.
     *
     * <p>The physical failure: "Here are a few mallard duck photos:" above a single photograph. The
     * text is written and streamed before discovery has fetched anything, so any number in it is a
     * guess that the web then gets to contradict.
     */
    @Test public void theModelIsToldNotToPromiseAPictureCount() {
        String client = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/ChatGptClient.java");
        assertTrue(client.contains("never promise a number of pictures"));
        assertTrue(client.contains("Here are some useful visual references"));
        assertTrue("and never describe what is about to appear",
                client.contains("never describe what is about to appear"));
    }

    /** The instruction says nothing about how Rich Answers works internally. */
    @Test public void theInstructionLeaksNoInternalArchitecture() {
        String client = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/ChatGptClient.java");
        int start = client.indexOf("When the user asks to see something");
        assertTrue(start > 0);
        String instruction = client.substring(start, client.indexOf("\" +", start));
        for (String internal : new String[]{
                "RichAnswer", "Rich Answers", "discovery", "provenance", "MAX_PER_MESSAGE",
                "resolver", "candidate", "canonical"}) {
            assertFalse("the user's model must not be told about " + internal,
                    instruction.contains(internal));
        }
        assertTrue("the count it must not promise is named only as a thing to avoid saying",
                instruction.contains("no I will attach two images"));
        assertFalse("and Orbit never writes an em dash", instruction.contains("—"));
    }

    /** The mandatory source line the whole recovery route depends on is untouched. */
    @Test public void theSourceMarkerInstructionIsUnchanged() {
        String client = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/ChatGptClient.java");
        assertTrue(client.contains(
                "include one best supporting source URL at the very end on its own line using "
                        + "exactly Source: https://..."));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static String textOf(View view) {
        if (view instanceof TextView) return ((TextView) view).getText().toString() + " ";
        StringBuilder out = new StringBuilder();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) out.append(textOf(group.getChildAt(i)));
        }
        return out.toString();
    }

    /** An answer with one picture still draws exactly one card. */
    @Test public void onePictureIsStillOneCard() {
        List<RichAnswerImage> one = Collections.singletonList(
                image("mallard-drake", "https://birds.example.org/mallard"));
        assertEquals(1, RichAnswerCardView.viewsFor(context, one, one, 0xFF000000).size());
    }
}
