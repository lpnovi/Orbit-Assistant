package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * How many pictures a question asks for, which is not the same question as whether it wants any.
 *
 * <p><b>This file is the regression test for the Beta 6 headline bug.</b> On a Galaxy S25 Ultra,
 * "Show me pics of a mallard duck" produced a strongly visual attempt that recovered its source,
 * ranked seventeen article candidates, downloaded one picture and stopped, because the only shape
 * of question that had ever earned a second image was a comparison. The user wrote a plural noun
 * and Orbit asked for one photograph.
 *
 * <p>Every case named in the Beta 6 acceptance list is here, on both sides of the line: the
 * singular phrasings that must stay at one, the plural phrasings that must reach two, the written
 * and numeric counts, and the non-visual control that must ask for nothing at all.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class RichAnswerImageCountTest {

    private static int wanted(String prompt) {
        return RichAnswerRelevance.maxImagesFor(prompt);
    }

    private static RichAnswerTrace.Intent intent(String prompt, String answer) {
        return RichAnswerRelevance.intentFor(prompt, answer);
    }

    private static final String ANSWER =
            "The mallard is a dabbling duck. The drake has a green head and a white neck ring.";

    // ---- one picture -----------------------------------------------------------------------------

    /** A question about how something looks is answered by one excellent photograph. */
    @Test public void aLookLikeQuestionAsksForOnePicture() {
        assertEquals(1, wanted("What does a mallard duck look like?"));
        assertEquals(1, wanted("What does Neuschwanstein Castle look like?"));
        assertEquals(1, wanted("Show me a mallard duck"));
        assertEquals(1, wanted("How do I identify a Northern black widow?"));
    }

    /** A singular picture word is a request for one picture, however it is phrased. */
    @Test public void aSingularPictureWordAsksForOnePicture() {
        assertEquals(1, wanted("Show me one picture of a mallard duck"));
        assertEquals(1, wanted("Show me one photo of a mallard duck"));
        assertEquals(1, wanted("Show me a picture of a mallard duck"));
        assertEquals(1, wanted("Show me one picture of a black widow"));
    }

    /** The ordinary sourced-answer case that Beta 5 got right, and must keep getting right. */
    @Test public void anOrdinaryVisualQuestionStillAsksForOnePicture() {
        String prompt = "Search the web and describe what a Northern black widow looks like, "
                + "including its important identifying features.";
        assertEquals(1, wanted(prompt));
        assertEquals(RichAnswerTrace.Intent.STRONG_VISUAL,
                intent(prompt, "The northern black widow has a broken hourglass."));
    }

    // ---- two pictures ----------------------------------------------------------------------------

    /** The exact phrasings from the physical failure, and every plural form around them. */
    @Test public void anExplicitPluralRequestAsksForTwoPictures() {
        assertEquals(2, wanted("Show me pictures of a mallard duck"));
        assertEquals(2, wanted("Show me pics of a mallard duck"));
        assertEquals(2, wanted("Show me photos of a mallard duck"));
        assertEquals(2, wanted("Show me images of a mallard duck"));
        assertEquals(2, wanted("Show me some pictures of a mallard duck"));
        assertEquals(2, wanted("Show me a few photos of a mallard duck"));
        assertEquals(2, wanted("Show me several pictures of a mallard duck"));
        assertEquals(2, wanted("Show me multiple images of a mallard duck"));
        assertEquals(2, wanted("I want pictures of a mallard duck"));
        assertEquals(2, wanted("Mallard duck photos"));
        assertEquals(2, wanted("Can I see some pictures of a black widow?"));
    }

    /** A comparison still earns a second picture, because it has two subjects to show. */
    @Test public void aComparisonAsksForTwoPictures() {
        assertEquals(2, wanted("Compare a robin and a bluebird"));
        assertEquals(2, wanted(
                "Show me the visual differences between a Northern and Southern black widow"));
        assertEquals(2, wanted("Show me the visual differences between a robin and a bluebird"));
        assertEquals(2, wanted("What is the difference between a raven and a crow?"));
        assertEquals(2, wanted("Show them side by side"));
    }

    // ---- written numbers -------------------------------------------------------------------------

    /** A number written next to a picture word is respected, inside Orbit's own ceiling. */
    @Test public void anExplicitNumberIsRespectedUpToTheMaximum() {
        assertEquals(1, wanted("show me 1 photo of a black widow"));
        assertEquals(2, wanted("show me 2 photos of a black widow"));
        assertEquals(2, wanted("show me 3 photos of a black widow"));
        assertEquals(2, wanted("show me 10 pictures of a black widow"));
        assertEquals(1, wanted("Show me one picture of a black widow"));
        assertEquals(2, wanted("Show me two pictures of a black widow"));
        assertEquals(2, wanted("Show me five pictures of a black widow"));
    }

    /** Nothing here may ever exceed what Orbit is willing to draw. */
    @Test public void nothingEverExceedsTheMessageMaximum() {
        String[] prompts = {"show me 99 pictures", "show me ten photos of a duck",
                "show me pictures and photos and images of a duck",
                "compare six pictures of a mallard duck"};
        for (String prompt : prompts) {
            assertTrue(prompt, wanted(prompt) <= RichAnswerImage.MAX_PER_MESSAGE);
        }
        assertEquals(2, RichAnswerImage.MAX_PER_MESSAGE);
    }

    // ---- intent is a different question ----------------------------------------------------------

    /**
     * Whether to look and how many to look for are separate, and both are visible in the trace.
     *
     * <p>This is the distinction Beta 6 exists to draw. Both of these are strongly visual; only one
     * of them asked to see several pictures.
     */
    @Test public void strongVisualIntentDoesNotMakeARequestPlural() {
        String singular = "What does a mallard look like?";
        String plural = "Show me pictures of a mallard";
        assertEquals(RichAnswerTrace.Intent.STRONG_VISUAL, intent(singular, ANSWER));
        assertEquals(RichAnswerTrace.Intent.STRONG_VISUAL, intent(plural, ANSWER));
        assertEquals(1, wanted(singular));
        assertEquals(2, wanted(plural));
    }

    /** A phrasing that names a picture at all is strongly visual, even with no other signal. */
    @Test public void namingAPictureIsEnoughToBeStronglyVisual() {
        assertEquals(RichAnswerTrace.Intent.STRONG_VISUAL, intent("Mallard duck photos", ANSWER));
        assertEquals(RichAnswerTrace.Intent.STRONG_VISUAL,
                intent("I want pictures of a mallard duck", ANSWER));
    }

    /** A question a picture would not help is asked for no pictures at all. */
    @Test public void aNonVisualQuestionIsNotVisualAtAll() {
        String prompt = "Explain what a hash map is.";
        String answer = "A hash map stores keys and values with an average constant lookup.";
        assertEquals(RichAnswerTrace.Intent.NONE, intent(prompt, answer));
        assertFalse(RichAnswerRelevance.answerWantsImage(prompt, answer));
    }

    /**
     * Words that merely contain a picture word are not picture words.
     *
     * <p>The reason the count policy matches whole words rather than substrings: "photosynthesis"
     * contains "photos" and "imagery" contains "image", and a substring rule would read a biology
     * question as somebody asking to see two photographs.
     */
    @Test public void wordsThatMerelyContainPictureWordsAreNotRequests() {
        assertFalse(RichAnswerRelevance.requestsMultipleImages(
                "Explain photosynthesis in simple terms"));
        assertFalse(RichAnswerRelevance.requestsMultipleImages(
                "Write imagery-rich prose about the sea"));
    }

    // ---- what unlocks secondary discovery ---------------------------------------------------------

    /**
     * Only a request written in the plural unlocks the fallback, not everything that wants two.
     *
     * <p>A comparison earns a second picture because the question has two subjects. It is not the
     * user asking to see several pictures, so it must not send Orbit off to pages the answer never
     * cited.
     */
    @Test public void onlyExplicitPluralRequestsUnlockSecondaryDiscovery() {
        assertTrue(RichAnswerRelevance.requestsMultipleImages("Show me pics of a mallard duck"));
        assertTrue(RichAnswerRelevance.requestsMultipleImages(
                "Show me two pictures of a black widow"));
        assertTrue(RichAnswerRelevance.requestsMultipleImages(
                "Show me five pictures of a black widow"));
        assertFalse(RichAnswerRelevance.requestsMultipleImages("Compare a robin and a bluebird"));
        assertFalse(RichAnswerRelevance.requestsMultipleImages(
                "Show me the visual differences between a robin and a bluebird"));
        assertFalse(RichAnswerRelevance.requestsMultipleImages(
                "Show me one picture of a black widow"));
        assertFalse(RichAnswerRelevance.requestsMultipleImages("What does a mallard look like?"));
    }
}
