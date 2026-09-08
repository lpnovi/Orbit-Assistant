package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.Locale;

/**
 * The buffer that has to explain a failure the next time one happens on real hardware.
 *
 * <p>Two things are being tested and they pull against each other. It must carry enough technical
 * state that somebody reading it does not have to guess which stage broke - the point of Beta 3 -
 * and it must carry nothing that could embarrass the person who pastes it into a chat window. So
 * every assertion here is either "this detail survived" or "this content never went in".
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class RichAnswerTraceTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        RichAnswerTrace.clear(context);
    }

    private RichAnswerTrace.Attempt attempt(RichAnswerTrace.Outcome outcome) {
        RichAnswerTrace.Attempt attempt = new RichAnswerTrace.Attempt();
        attempt.enabled = true;
        attempt.providerEligible = true;
        attempt.intent = RichAnswerTrace.Intent.STRONG_VISUAL;
        attempt.requestedImages = 1;
        attempt.sourcesReceived = 3;
        attempt.pagesAttempted = 2;
        attempt.pageBudget = RichAnswerCoordinator.MAX_PAGES_EXAMINED_STRONG;
        attempt.outcome = outcome;
        return attempt;
    }

    private RichAnswerTrace.PageRecord page(RichAnswerTrace.Attempt attempt, String host) {
        RichAnswerTrace.PageRecord page = attempt.page();
        page.host = host;
        page.path = "/content/spiders";
        page.httpStatus = 200;
        page.contentType = "text/html";
        page.bytesRead = 148 * 1024;
        page.previewCandidates = 0;
        page.articleCandidates = 5;
        return page;
    }

    // ---- retention -------------------------------------------------------------------------------

    @Test public void anAttemptIsStoredAndReadBack() {
        RichAnswerTrace.record(context, attempt(RichAnswerTrace.Outcome.NO_USABLE_IMAGE));
        List<RichAnswerTrace.Attempt> stored = RichAnswerTrace.attempts(context);
        assertEquals(1, stored.size());
        assertEquals(RichAnswerTrace.Outcome.NO_USABLE_IMAGE, stored.get(0).outcome);
        assertEquals(RichAnswerTrace.Intent.STRONG_VISUAL, stored.get(0).intent);
        assertEquals(3, stored.get(0).sourcesReceived);
    }

    @Test public void theBufferIsBoundedAndTheOldestIsEvicted() {
        for (int i = 0; i < RichAnswerTrace.MAX_ATTEMPTS + 3; i++) {
            RichAnswerTrace.Attempt attempt = attempt(RichAnswerTrace.Outcome.NO_USABLE_IMAGE);
            attempt.requestedImages = i;
            RichAnswerTrace.record(context, attempt);
        }
        List<RichAnswerTrace.Attempt> stored = RichAnswerTrace.attempts(context);
        assertEquals(RichAnswerTrace.MAX_ATTEMPTS, stored.size());
        assertEquals("the oldest is gone", 3, stored.get(0).requestedImages);
        assertEquals("and the newest is kept",
                RichAnswerTrace.MAX_ATTEMPTS + 2, stored.get(stored.size() - 1).requestedImages);
    }

    /**
     * The workflow this exists for: reproduce a failure, leave, come back and open Diagnostics.
     *
     * <p>Reading through a fresh accessor is how a new process sees it, so this is the same
     * question as "does it survive the app being killed".
     */
    @Test public void theBufferSurvivesBeingReadByAFreshProcess() {
        RichAnswerTrace.Attempt attempt = attempt(RichAnswerTrace.Outcome.NO_USABLE_IMAGE);
        RichAnswerTrace.PageRecord page = page(attempt, "pubs.example.edu");
        RichAnswerTrace.CandidateRecord candidate = page.candidate();
        candidate.origin = RichAnswerArticleImages.Origin.ARTICLE_IMG;
        candidate.host = "cdn.example.edu";
        candidate.path = "/photos/widow.jpg";
        candidate.fetched = true;
        candidate.httpStatus = 403;
        candidate.reason = RichAnswerTrace.Reason.HTTP_ERROR;
        RichAnswerTrace.record(context, attempt);

        RichAnswerTrace.Attempt read = RichAnswerTrace.last(context);
        assertNotNull(read);
        assertEquals(1, read.pages.size());
        assertEquals("pubs.example.edu", read.pages.get(0).host);
        assertEquals(5, read.pages.get(0).articleCandidates);
        assertEquals(1, read.pages.get(0).candidates.size());
        assertEquals(403, read.pages.get(0).candidates.get(0).httpStatus);
        assertEquals(RichAnswerTrace.Reason.HTTP_ERROR,
                read.pages.get(0).candidates.get(0).reason);
        assertEquals(RichAnswerArticleImages.Origin.ARTICLE_IMG,
                read.pages.get(0).candidates.get(0).origin);
    }

    @Test public void pagesAndCandidatesAreBoundedPerAttempt() {
        RichAnswerTrace.Attempt attempt = attempt(RichAnswerTrace.Outcome.NO_USABLE_IMAGE);
        for (int i = 0; i < 40; i++) {
            RichAnswerTrace.PageRecord page = attempt.page();
            for (int j = 0; j < 40; j++) page.candidate();
        }
        assertTrue(attempt.pages.size() <= RichAnswerTrace.MAX_PAGES_PER_ATTEMPT);
        for (RichAnswerTrace.PageRecord page : attempt.pages) {
            assertTrue(page.candidates.size() <= RichAnswerTrace.MAX_CANDIDATES_PER_PAGE);
        }
    }

    @Test public void corruptStorageReadsAsEmptyRatherThanCrashing() {
        context.getSharedPreferences("orbit_diagnostics", Context.MODE_PRIVATE).edit()
                .putString("rich_answer_trace", "{not json at all").apply();
        assertTrue(RichAnswerTrace.attempts(context).isEmpty());
        assertNull(RichAnswerTrace.last(context));
        assertTrue(RichAnswerTrace.report(context).contains("No Rich Answers attempt recorded"));
    }

    @Test public void clearEmptiesTheBuffer() {
        RichAnswerTrace.record(context, attempt(RichAnswerTrace.Outcome.IMAGE_FOUND_AND_ATTACHED));
        RichAnswerTrace.clear(context);
        assertTrue(RichAnswerTrace.attempts(context).isEmpty());
    }

    // ---- privacy ---------------------------------------------------------------------------------

    @Test public void aQueryStringAndFragmentAreDroppedFromASanitizedAddress() {
        assertEquals("example.com/article",
                RichAnswerTrace.sanitize("https://example.com/article?id=123#part"));
        assertEquals("example.com/", RichAnswerTrace.sanitize("https://example.com"));
        assertEquals("example.com", RichAnswerTrace.hostOf("https://example.com/a/b?q=secret"));
        assertEquals("/a/b", RichAnswerTrace.pathOf("https://example.com/a/b?q=secret"));
    }

    @Test public void credentialsInAnAddressAreNeverWrittenDown() {
        String sanitized = RichAnswerTrace.sanitize("https://user:hunter2@example.com/a");
        assertFalse(sanitized.contains("hunter2"));
        assertFalse(sanitized.contains("user"));
        assertTrue(sanitized.startsWith("example.com"));
    }

    @Test public void aLongPathIsBoundedFromTheMiddle() {
        StringBuilder path = new StringBuilder("/search/");
        for (int i = 0; i < 40; i++) path.append("some-long-private-looking-segment/");
        String bounded = RichAnswerTrace.boundedPath(path.toString());
        assertTrue(bounded.length() <= RichAnswerTrace.MAX_PATH_CHARS);
        assertTrue("the beginning still identifies the kind of page", bounded.startsWith("/search/"));
    }

    @Test public void aMalformedAddressSanitizesToNothingRatherThanToItself() {
        assertEquals("", RichAnswerTrace.sanitize("not a url at all"));
        assertEquals("", RichAnswerTrace.sanitize(null));
        assertEquals("", RichAnswerTrace.hostOf(""));
    }

    /** The whole report, checked against the things it must never be able to contain. */
    @Test public void theReportCarriesNoPromptAnswerOrPageText() {
        RichAnswerTrace.Attempt attempt = attempt(RichAnswerTrace.Outcome.NO_USABLE_IMAGE);
        RichAnswerTrace.PageRecord page = page(attempt, "pubs.example.edu");
        RichAnswerTrace.CandidateRecord candidate = page.candidate();
        candidate.host = "cdn.example.edu";
        candidate.path = "/photos/widow.jpg";
        candidate.reason = RichAnswerTrace.Reason.LOW_SCORE;
        RichAnswerTrace.record(context, attempt);

        String report = RichAnswerTrace.report(context);
        for (String forbidden : new String[]{
                "northern", "black widow", "Search the web", "hourglass", "?", "#",
                "Latrodectus", "The northern black widow is"}) {
            assertFalse("a copied report must never carry " + forbidden,
                    report.toLowerCase(Locale.US).contains(forbidden.toLowerCase(Locale.US)));
        }
        assertTrue(report.contains("No prompt, answer or page text is recorded."));
    }

    /** The stored form is checked too, not only what is printed from it. */
    @Test public void nothingUserWrittenIsStoredEvenBeforeItIsPrinted() {
        RichAnswerTrace.Attempt attempt = attempt(RichAnswerTrace.Outcome.NO_USABLE_IMAGE);
        page(attempt, "pubs.example.edu");
        RichAnswerTrace.record(context, attempt);
        String raw = context.getSharedPreferences("orbit_diagnostics", Context.MODE_PRIVATE)
                .getString("rich_answer_trace", "");
        assertFalse(raw.contains("?"));
        assertFalse(raw.contains("#"));
        assertTrue("only hosts, paths, statuses and enum names", raw.contains("pubs.example.edu"));
    }

    // ---- reporting -------------------------------------------------------------------------------

    @Test public void theEmptyStateIsTruthful() {
        String body = RichAnswerTrace.body(context);
        assertTrue(body.contains("No Rich Answers attempt recorded on this device yet."));
        assertEquals("", RichAnswerTrace.summaryLine(context));
    }

    @Test public void theSummaryLineIsOneReadableLine() {
        RichAnswerTrace.record(context, attempt(RichAnswerTrace.Outcome.NO_USABLE_IMAGE));
        String line = RichAnswerTrace.summaryLine(context);
        assertTrue(line.startsWith("Rich Answers: last attempt"));
        assertFalse("a summary line must not wrap into a report", line.contains("\n"));
    }

    @Test public void theBodyExplainsWhatHappenedAtEachStage() {
        RichAnswerTrace.Attempt attempt = attempt(RichAnswerTrace.Outcome.NO_USABLE_IMAGE);
        RichAnswerTrace.PageRecord page = page(attempt, "pubs.example.edu");
        RichAnswerTrace.CandidateRecord blocked = page.candidate();
        blocked.origin = RichAnswerArticleImages.Origin.ARTICLE_IMG;
        blocked.path = "/photos/a.jpg";
        blocked.score = 71;
        blocked.fetched = true;
        blocked.httpStatus = 403;
        blocked.reason = RichAnswerTrace.Reason.HTTP_ERROR;
        RichAnswerTrace.CandidateRecord small = page.candidate();
        small.origin = RichAnswerArticleImages.Origin.SRCSET;
        small.path = "/photos/b.jpg";
        small.fetched = true;
        small.mime = "image/jpeg";
        small.decodedWidth = 60;
        small.decodedHeight = 60;
        small.reason = RichAnswerTrace.Reason.TOO_SMALL;
        RichAnswerTrace.record(context, attempt);

        String body = RichAnswerTrace.body(context);
        assertTrue(body.contains("Intent: Strong visual"));
        assertTrue(body.contains("Provider eligible: yes"));
        assertTrue(body.contains("Sources received: 3"));
        assertTrue(body.contains("Pages attempted: 2"));
        assertTrue(body.contains("Article candidates: 5"));
        assertTrue(body.contains("Preview candidates: 0"));
        assertTrue(body.contains("Result: No usable image"));
        assertTrue(body.contains("pubs.example.edu"));
        assertTrue(body.contains("HTTP 200"));
        assertTrue(body.contains("HTTP 403"));
        assertTrue(body.contains("60x60"));
        assertTrue(body.contains("TOO_SMALL"));
        assertTrue(body.contains("ARTICLE_IMG"));
    }

    /** Every failure reason has to render as something, or a trace lies by omission. */
    @Test public void everyFailureReasonRendersReadably() {
        for (RichAnswerTrace.Reason reason : RichAnswerTrace.Reason.values()) {
            RichAnswerTrace.Attempt attempt = attempt(RichAnswerTrace.Outcome.NO_USABLE_IMAGE);
            RichAnswerTrace.PageRecord page = page(attempt, "example.org");
            RichAnswerTrace.CandidateRecord candidate = page.candidate();
            candidate.fetched = true;
            candidate.reason = reason;
            String described = candidate.describe();
            assertNotNull(described);
            assertTrue(reason + " must appear in its own line",
                    described.contains(reason == RichAnswerTrace.Reason.ACCEPTED
                            ? "accepted" : reason.name()));
        }
    }

    /** And every outcome, including the two that mean the download worked and the attach did not. */
    @Test public void everyOutcomeRendersReadably() {
        for (RichAnswerTrace.Outcome outcome : RichAnswerTrace.Outcome.values()) {
            String readable = RichAnswerTrace.readable(outcome);
            assertNotNull(readable);
            assertFalse(outcome + " needs words rather than an enum name", readable.isEmpty());
            assertFalse(readable.contains("_"));
        }
        assertEquals("Image found, message not found",
                RichAnswerTrace.readable(RichAnswerTrace.Outcome.IMAGE_FOUND_BUT_MESSAGE_NOT_FOUND));
        assertEquals("Image found, request cancelled",
                RichAnswerTrace.readable(RichAnswerTrace.Outcome.IMAGE_FOUND_BUT_REQUEST_CANCELLED));
    }

    @Test public void aPageThatWasNeverFetchedSaysSo() {
        RichAnswerTrace.Attempt attempt = attempt(RichAnswerTrace.Outcome.NO_USABLE_IMAGE);
        RichAnswerTrace.PageRecord page = attempt.page();
        page.host = "example.org";
        page.fetchAllowed = false;
        page.reason = RichAnswerTrace.Reason.PRIVATE_HOST;
        RichAnswerTrace.record(context, attempt);
        String body = RichAnswerTrace.body(context);
        assertTrue(body.contains("Page fetch: blocked · PRIVATE_HOST"));
        assertTrue(body.contains("No candidate was attempted."));
    }

    @Test public void theMostRecentAttemptIsShownFirst() {
        RichAnswerTrace.Attempt older = attempt(RichAnswerTrace.Outcome.NO_USABLE_IMAGE);
        RichAnswerTrace.record(context, older);
        RichAnswerTrace.Attempt newer = attempt(RichAnswerTrace.Outcome.IMAGE_FOUND_AND_ATTACHED);
        RichAnswerTrace.record(context, newer);
        String body = RichAnswerTrace.body(context);
        assertTrue(body.indexOf("Last attempt") < body.indexOf("Attempt −1"));
        assertTrue(body.indexOf("Image attached") < body.indexOf("No usable image"));
    }

    @Test public void theReportNamesTheVersionItCameFrom() {
        RichAnswerTrace.record(context, attempt(RichAnswerTrace.Outcome.NO_USABLE_IMAGE));
        String report = RichAnswerTrace.report(context);
        assertTrue(report.startsWith("Orbit Rich Answers diagnostics"));
        assertTrue(report.contains(BuildConfig.VERSION_NAME));
    }
}
