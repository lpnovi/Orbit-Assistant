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
 * Which picture on a page is the right one, decided against what the user actually asked.
 *
 * <p>This is the half of Beta 3 that stops the article parser from making things worse. Reading a
 * page's body finds the photograph, and it also finds the institution's logo, the author's
 * portrait, the newsletter promo and the share buttons - so a parser without a ranking that knows
 * the subject would trade "no picture" for "a picture of the wrong thing", which is worse.
 *
 * <p>The subject words never leave the device and are never written down. They exist for the
 * length of one discovery attempt, and these tests are the only place they are visible at all.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class RichAnswerRankingTest {

    private static final String PAGE = "https://pubs.example.edu/content/spiders/widow.html";
    private static final String TITLE = "Widow spiders in the home landscape";

    private static final List<String> WIDOW_TOKENS =
            RichAnswerSubject.tokensOf("Search the web and describe what a Northern black widow "
                    + "looks like, including its important identifying features.");

    private RichAnswerArticleImages.Candidate candidate(String url, String alt, String caption,
                                                        int structure) {
        return new RichAnswerArticleImages.Candidate(url,
                RichAnswerArticleImages.Origin.ARTICLE_IMG, alt, caption, 0, 0, structure);
    }

    private int score(RichAnswerArticleImages.Candidate candidate) {
        return RichAnswerRelevance.judge(candidate, PAGE, TITLE, WIDOW_TOKENS, true).score;
    }

    private RichAnswerTrace.Reason reason(RichAnswerArticleImages.Candidate candidate) {
        return RichAnswerRelevance.judge(candidate, PAGE, TITLE, WIDOW_TOKENS, true).reason;
    }

    // ---- subject tokens --------------------------------------------------------------------------

    @Test public void theSubjectIsWhatIsLeftAfterTheAsking() {
        assertEquals(Arrays.asList("northern", "black", "widow"), WIDOW_TOKENS);
    }

    /** The trap: a prompt that says "photo" must not make "photo" a subject word. */
    @Test public void theVocabularyOfThisFeatureIsNeverASubjectWord() {
        List<String> tokens = RichAnswerSubject.tokensOf(
                "Search the web and answer inside Orbit. Show me how to identify a Northern black "
                        + "widow spider. Include one useful sourced photo inline in your response.");
        for (String forbidden : new String[]{
                "photo", "image", "inline", "sourced", "search", "show", "include", "useful",
                "identify", "response", "orbit", "answer", "web"}) {
            assertFalse(forbidden + " is how the question was asked, not what it was about",
                    tokens.contains(forbidden));
        }
        assertTrue(tokens.contains("widow"));
        assertTrue(tokens.contains("spider"));
    }

    @Test public void tokensAreBoundedAndDeduplicated() {
        List<String> tokens = RichAnswerSubject.tokensOf(
                "robin robin robin cardinal finch sparrow starling wren thrush warbler nuthatch");
        assertTrue(tokens.size() <= RichAnswerSubject.MAX_TOKENS);
        assertEquals(1, Collections.frequency(tokens, "robin"));
    }

    @Test public void anEmptyPromptYieldsNoTokens() {
        assertTrue(RichAnswerSubject.tokensOf("").isEmpty());
        assertTrue(RichAnswerSubject.tokensOf(null).isEmpty());
        assertEquals(0, RichAnswerSubject.matchScore(null, "anything"));
        assertEquals(0, RichAnswerSubject.matchScore(WIDOW_TOKENS, ""));
    }

    /** A word inside another word is not the word. */
    @Test public void matchingIsByWordRatherThanBySubstring() {
        assertTrue(RichAnswerSubject.containsWord("a female black widow", "widow"));
        assertTrue("a plural is the same subject",
                RichAnswerSubject.containsWord("black widows on a web", "widow"));
        assertFalse(RichAnswerSubject.containsWord("the widowmaker ridge", "widow"));
        assertFalse(RichAnswerSubject.containsWord("bartholomew", "art"));
    }

    @Test public void pluralsInThePromptMatchSingularsInACaption() {
        List<String> tokens = RichAnswerSubject.tokensOf("show me pictures of black widow spiders");
        assertTrue(tokens.contains("spider"));
        assertTrue(RichAnswerSubject.matchScore(tokens, "A black widow spider") > 0);
    }

    // ---- ranking ---------------------------------------------------------------------------------

    @Test public void altTextNamingTheSubjectBeatsUnrelatedArtwork() {
        int subject = score(candidate("https://cdn.example.edu/img/a1b2c3.jpg",
                "female northern black widow", "", RichAnswerArticleImages.STRUCTURE_CONTENT));
        int unrelated = score(candidate("https://cdn.example.edu/img/d4e5f6.jpg",
                "campus in autumn", "", RichAnswerArticleImages.STRUCTURE_CONTENT));
        assertTrue("the subject must win by a clear margin", subject > unrelated + 20);
    }

    @Test public void aCaptionNamingTheSubjectBeatsOneThatDoesNot() {
        int subject = score(candidate("https://cdn.example.edu/img/one.jpg", "",
                "Female northern black widow showing the hourglass",
                RichAnswerArticleImages.STRUCTURE_CONTENT));
        int generic = score(candidate("https://cdn.example.edu/img/two.jpg", "",
                "Figure 4", RichAnswerArticleImages.STRUCTURE_CONTENT));
        assertTrue(subject > generic + 20);
    }

    @Test public void aLogoIsRefusedOutright() {
        assertEquals(RichAnswerTrace.Reason.LOGO_OR_CHROME,
                reason(candidate("https://pubs.example.edu/img/logo.png", "",
                        "", RichAnswerArticleImages.STRUCTURE_CONTENT)));
        assertEquals("even when only the alt text says so",
                RichAnswerTrace.Reason.LOGO_OR_CHROME,
                reason(candidate("https://pubs.example.edu/img/a7f3.png",
                        "Example University logo", "", RichAnswerArticleImages.STRUCTURE_CONTENT)));
    }

    @Test public void anAvatarAndAFaviconAreRefused() {
        for (String url : new String[]{
                "https://pubs.example.edu/img/avatar/jane.jpg",
                "https://pubs.example.edu/favicon.png",
                "https://pubs.example.edu/img/icon-search.png",
                "https://pubs.example.edu/img/tracking/pixel.gif",
                "https://pubs.example.edu/img/spacer.gif"}) {
            assertEquals(url + " is furniture", RichAnswerTrace.Reason.LOGO_OR_CHROME,
                    reason(candidate(url, "", "", RichAnswerArticleImages.STRUCTURE_CONTENT)));
        }
        assertEquals(RichAnswerTrace.Reason.LOGO_OR_CHROME,
                reason(candidate("https://pubs.example.edu/img/x9.jpg",
                        "Author avatar", "", RichAnswerArticleImages.STRUCTURE_CONTENT)));
    }

    /** A CDN address that gives nothing away can still win, on what the page said about it. */
    @Test public void anOpaqueCdnAddressCanStillWin() {
        RichAnswerArticleImages.Candidate opaque = candidate(
                "https://images.example.net/v2/9f8a7b6c5d4e",
                "Northern black widow underside", "",
                RichAnswerArticleImages.STRUCTURE_CONTENT);
        RichAnswerArticleImages.Candidate descriptive = candidate(
                "https://pubs.example.edu/img/campus-quad-in-spring.jpg", "", "",
                RichAnswerArticleImages.STRUCTURE_CONTENT);
        assertEquals(RichAnswerTrace.Reason.NONE, reason(opaque));
        assertTrue("the words beat the filename", score(opaque) > score(descriptive));
    }

    /** The correction Beta 3 exists to make, stated as an ordering. */
    @Test public void aRelevantArticlePhotographBeatsAGenericPreviewCard() {
        RichAnswerArticleImages.Candidate hero = new RichAnswerArticleImages.Candidate(
                "https://pubs.example.edu/img/social-share.jpg",
                RichAnswerArticleImages.Origin.OG_IMAGE,
                "Example University Extension", "", 0, 0,
                RichAnswerArticleImages.STRUCTURE_NEUTRAL);
        RichAnswerArticleImages.Candidate photograph = candidate(
                "https://pubs.example.edu/img/nbw-female.jpg",
                "Female northern black widow",
                "Northern black widow, ventral view",
                RichAnswerArticleImages.STRUCTURE_CONTENT);
        assertTrue(score(photograph) > score(hero));
    }

    /** But a preview image still wins when nothing in the article is about the subject. */
    @Test public void aPreviewCardStillWinsWhenTheArticleOffersNothingRelevant() {
        RichAnswerArticleImages.Candidate preview = new RichAnswerArticleImages.Candidate(
                "https://pubs.example.edu/img/widow-hero.jpg",
                RichAnswerArticleImages.Origin.OG_IMAGE, "", "", 0, 0,
                RichAnswerArticleImages.STRUCTURE_NEUTRAL);
        RichAnswerArticleImages.Candidate unrelated = candidate(
                "https://pubs.example.edu/img/newsletter-promo.jpg", "Sign up for our newsletter",
                "", RichAnswerArticleImages.STRUCTURE_NEUTRAL);
        assertTrue(score(preview) > score(unrelated));
    }

    @Test public void beingInsideTheArticleBeatsBeingInTheFurniture() {
        String url = "https://pubs.example.edu/img/nbw.jpg";
        assertTrue(score(candidate(url, "black widow", "", RichAnswerArticleImages.STRUCTURE_CONTENT))
                > score(candidate(url, "black widow", "", RichAnswerArticleImages.STRUCTURE_CHROME)));
    }

    @Test public void aUsefulDeclaredSizeIsRewardedAndATinyOneIsRefused() {
        RichAnswerArticleImages.Candidate large = new RichAnswerArticleImages.Candidate(
                "https://pubs.example.edu/img/nbw.jpg",
                RichAnswerArticleImages.Origin.ARTICLE_IMG, "black widow", "", 1200, 800,
                RichAnswerArticleImages.STRUCTURE_CONTENT);
        RichAnswerArticleImages.Candidate unsized = candidate(
                "https://pubs.example.edu/img/nbw.jpg", "black widow", "",
                RichAnswerArticleImages.STRUCTURE_CONTENT);
        assertTrue(score(large) > score(unsized));

        RichAnswerArticleImages.Candidate tiny = new RichAnswerArticleImages.Candidate(
                "https://pubs.example.edu/img/nbw.jpg",
                RichAnswerArticleImages.Origin.ARTICLE_IMG, "black widow", "", 32, 32,
                RichAnswerArticleImages.STRUCTURE_CONTENT);
        assertEquals("a declared icon costs no download to refuse",
                RichAnswerTrace.Reason.TOO_SMALL, reason(tiny));

        RichAnswerArticleImages.Candidate banner = new RichAnswerArticleImages.Candidate(
                "https://pubs.example.edu/img/nbw.jpg",
                RichAnswerArticleImages.Origin.ARTICLE_IMG, "black widow", "", 1600, 120,
                RichAnswerArticleImages.STRUCTURE_CONTENT);
        assertEquals(RichAnswerTrace.Reason.BAD_ASPECT_RATIO, reason(banner));
    }

    @Test public void unsafeAndUndrawableAddressesAreRefusedWithTheirOwnReasons() {
        assertEquals(RichAnswerTrace.Reason.UNSAFE_URL,
                reason(candidate("http://insecure.example.edu/a.jpg", "black widow", "", 0)));
        assertEquals(RichAnswerTrace.Reason.UNSAFE_URL,
                reason(candidate("javascript:alert(1)", "black widow", "", 0)));
        assertEquals(RichAnswerTrace.Reason.UNSUPPORTED_FORMAT,
                reason(candidate("https://pubs.example.edu/img/diagram.svg", "black widow", "", 0)));
    }

    /** Decoded size, asked in a form that says which half of the rule failed. */
    @Test public void decodedDimensionsAreJudgedWithAReason() {
        assertEquals(RichAnswerTrace.Reason.ACCEPTED, RichAnswerRelevance.judgeDimensions(1200, 800));
        assertEquals(RichAnswerTrace.Reason.TOO_SMALL, RichAnswerRelevance.judgeDimensions(48, 48));
        assertEquals(RichAnswerTrace.Reason.TOO_SMALL, RichAnswerRelevance.judgeDimensions(180, 120));
        assertEquals(RichAnswerTrace.Reason.BAD_ASPECT_RATIO,
                RichAnswerRelevance.judgeDimensions(1600, 120));
        assertEquals(RichAnswerTrace.Reason.BAD_ASPECT_RATIO,
                RichAnswerRelevance.judgeDimensions(200, 1600));
        assertTrue(RichAnswerRelevance.hasUsefulDimensions(1200, 800));
        assertFalse(RichAnswerRelevance.hasUsefulDimensions(48, 48));
    }

    // ---- intent ----------------------------------------------------------------------------------

    /** The three physical acceptance cases, at the gate that decides whether Orbit looks at all. */
    @Test public void allThreeAcceptanceQuestionsAreStronglyVisual() {
        String[] prompts = {
                "Show me pictures of a black widow",
                "Search the web and answer inside Orbit. Show me how to identify a Northern black "
                        + "widow spider. Include one useful sourced photo inline in your response, "
                        + "preferably from Wikimedia Commons, a university, or another trustworthy "
                        + "source. Put the image near the identification explanation, include its "
                        + "caption/source, and do not open a browser or just give me an image link.",
                "Search the web and describe what a Northern black widow looks like, including its "
                        + "important identifying features."};
        for (String prompt : prompts) {
            assertEquals(prompt.substring(0, Math.min(40, prompt.length())),
                    RichAnswerTrace.Intent.STRONG_VISUAL,
                    RichAnswerRelevance.intentFor(prompt,
                            "The northern black widow is a shiny black spider with red markings."));
        }
    }

    @Test public void theControlVisualQuestionsAreStronglyVisualToo() {
        assertEquals(RichAnswerTrace.Intent.STRONG_VISUAL,
                RichAnswerRelevance.intentFor(
                        "What does a European robin look like? Search the web and describe its "
                                + "appearance.",
                        "A small bird with an orange-red breast."));
        assertEquals(RichAnswerTrace.Intent.STRONG_VISUAL,
                RichAnswerRelevance.intentFor("Show me what the Eiffel Tower looks like.",
                        "An iron lattice tower in Paris."));
    }

    @Test public void theControlNonVisualQuestionsAskForNoPicture() {
        assertEquals(RichAnswerTrace.Intent.NONE,
                RichAnswerRelevance.intentFor("What is the population of Michigan?",
                        "Michigan has about 10 million residents as of the most recent estimate."));
        assertEquals(RichAnswerTrace.Intent.NONE,
                RichAnswerRelevance.intentFor("Explain what a hash map is.",
                        "A hash map stores key-value pairs and looks them up in constant time."));
    }

    /** A picture would help without being the answer, which is the middle of the scale. */
    @Test public void anOrdinaryVisualQuestionIsNotStrong() {
        assertEquals(RichAnswerTrace.Intent.VISUAL,
                RichAnswerRelevance.intentFor("best hiking destination in the Dolomites",
                        "A well-known travel destination with dramatic limestone peaks."));
    }

    @Test public void intentIsNoneWhenTheQuestionIsNotVisualAtAll() {
        assertEquals(RichAnswerTrace.Intent.NONE, RichAnswerRelevance.intentFor("", "anything"));
        assertEquals(RichAnswerTrace.Intent.NONE, RichAnswerRelevance.intentFor(null, "anything"));
        assertEquals(RichAnswerTrace.Intent.NONE,
                RichAnswerRelevance.intentFor("summarize this article", "A summary."));
    }
}
