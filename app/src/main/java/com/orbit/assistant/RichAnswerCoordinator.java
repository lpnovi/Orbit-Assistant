package com.orbit.assistant;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Finds the picture that belongs to an answer, after the answer has already been delivered.
 *
 * <p><b>Text is never made to wait.</b> That is the whole shape of this class. Discovery starts
 * once a request has won its completion claim and the answer is already written, streamed and on
 * screen; it then runs on its own thread, and whatever it finds is attached afterwards. An answer
 * whose picture takes four seconds is an answer the user read four seconds ago, and an answer whose
 * picture never resolves at all is simply an answer. Nothing here can slow a response down, fail
 * one, or change a word of one.
 *
 * <p><b>Ownership is checked, not assumed.</b> A discovery is started only inside the completion
 * gate, so a stopped or superseded request never begins one. When it finishes, the result is
 * attached by matching the answer's exact text through
 * {@link ConversationStore#attachRichImages}, which is what keeps a late result from a request the
 * user has moved past away from whatever answer is on screen now. A cancelled request is checked
 * again at delivery, because a Stop can land while the fetch is in flight.
 *
 * <p><b>Nothing is sent outward.</b> The only things that leave this device are ordinary bounded
 * GETs to pages the answer already cited and to the pictures those pages carry. No conversation
 * text, no prompt, no Vault content, no identifiers and no cookies travel with them; the questions
 * of whether to look at all and which picture is best are answered locally by
 * {@link RichAnswerRelevance} and {@link RichAnswerSubject}.
 *
 * <p><b>What Beta 3 changed.</b> Beta 1 and Beta 2 asked each cited page for its declared preview
 * image and moved on to the next page when there was not one - so a university publication with
 * five photographs of the animal in question contributed nothing, because its template declares no
 * {@code og:image}. That single {@code continue} was the bug behind two failed acceptance runs. A
 * page now yields both what it declares and what it actually contains, the two are ranked together
 * against what the user asked about, and every stage of it is written to {@link RichAnswerTrace} so
 * a failure on real hardware can be explained instead of guessed at.
 */
public final class RichAnswerCoordinator {

    /**
     * How many cited pages an ordinary visual answer will read.
     *
     * <p>Small on purpose. Reading six pages to decorate an answer would be six requests the user
     * did not ask for, and the pages a search cites are ordered by relevance already.
     */
    static final int MAX_PAGES_EXAMINED = 3;

    /**
     * How many cited pages a strongly visual answer will read.
     *
     * <p>Five, and only for questions where the picture <em>is</em> the answer - "show me pictures
     * of", "what does it look like", "how do I identify". Those are the questions where coming back
     * with text only is a failure rather than a restraint, so they are allowed to keep looking
     * after the first three sources disappoint. Every other bound is unchanged: strong visual
     * intent means try harder, not crawl the web.
     */
    static final int MAX_PAGES_EXAMINED_STRONG = 5;

    /**
     * How many candidates from one page are ranked.
     *
     * <p>Ranking is arithmetic over strings and costs nothing, so this is generous. It is not a
     * download budget and must not be read as one.
     */
    static final int MAX_RANKED_CANDIDATES_PER_PAGE = 8;

    /**
     * How many candidates from one page are actually downloaded.
     *
     * <p>Four. Beta 2 allowed three and only ever had preview images to spend them on; a page with
     * a real article now offers many more, and downloading all of them would turn one polite read
     * into a small crawl. Four is enough that a page's best photograph being a 403 or an
     * undecodable format does not lose the picture, and it stops there.
     */
    static final int MAX_FETCHED_CANDIDATES_PER_PAGE = 4;

    /**
     * One thread. Rich media is the least important work Orbit does, and it is the work most
     * likely to sit blocked on somebody else's slow web server, so it gets exactly enough capacity
     * to make progress and none to compete with anything that matters.
     */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "orbit-rich-answer");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    /** Told when an answer already on screen gains a picture. Display only. */
    public interface Listener {
        void onRichImagesAttached(String conversationId);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private RichAnswerCoordinator() {}

    public static void addListener(Listener listener) {
        if (listener != null && !LISTENERS.contains(listener)) LISTENERS.add(listener);
    }

    public static void removeListener(Listener listener) {
        if (listener != null) LISTENERS.remove(listener);
    }

    /**
     * Starts looking for a picture for one completed answer. Returns immediately.
     *
     * <p>Call this from inside a completion gate, after the answer has been persisted. It declines
     * quietly whenever there is nothing to do - no cited pages, a provider without hosted search, a
     * question a picture would not help, or a user who has turned rich answers off - so the caller
     * never has to know any of those rules.
     *
     * <p>A declined attempt is written to the trace only when the answer actually cited sources.
     * Recording every ordinary chat message would fill a five-slot buffer with answers nobody
     * expected a picture from, and push out the one failure the user is trying to report.
     */
    public static void discover(Context context, String conversationId, String requestId,
                                String prompt, AssistantReply reply) {
        if (context == null || reply == null) return;
        if (conversationId == null || conversationId.trim().isEmpty()) return;
        String answer = reply.text == null ? "" : reply.text.trim();
        if (answer.isEmpty()) return;

        Context app = context.getApplicationContext();
        RichAnswerTrace.Attempt trace = new RichAnswerTrace.Attempt();
        trace.sourcesReceived = reply.sourceUrls.size();
        trace.enabled = enabled(app);
        trace.providerEligible = AiProviders.active(app).capabilities().richWebMedia;
        trace.intent = RichAnswerRelevance.intentFor(prompt, answer);

        if (reply.sourceUrls.isEmpty()) return;
        if (!trace.enabled || !trace.providerEligible
                || trace.intent == RichAnswerTrace.Intent.NONE) {
            trace.outcome = RichAnswerTrace.Outcome.NOT_ELIGIBLE;
            RichAnswerTrace.record(app, trace);
            return;
        }

        String chat = conversationId.trim();
        String owner = requestId == null ? "" : requestId.trim();
        List<String> pages = new ArrayList<>(reply.sourceUrls);
        boolean strong = trace.intent == RichAnswerTrace.Intent.STRONG_VISUAL;
        int wanted = RichAnswerRelevance.maxImagesFor(prompt);
        int anchor = RichAnswerPlacement.placementFor(answer);
        trace.requestedImages = wanted;
        trace.pageBudget = strong ? MAX_PAGES_EXAMINED_STRONG : MAX_PAGES_EXAMINED;
        // Derived here, on this thread, and handed straight to ranking. Never stored, never sent,
        // and deliberately never written into the trace.
        List<String> subject = RichAnswerSubject.tokensOf(prompt);

        EXECUTOR.execute(() -> {
            try {
                List<RichAnswerImage> found = resolve(app, pages, wanted, anchor, subject, trace);
                if (found.isEmpty()) {
                    trace.outcome = RichAnswerTrace.Outcome.NO_USABLE_IMAGE;
                    return;
                }
                // Asked again here rather than only at the start: a Stop can land while a page
                // and an image are being fetched, and a stopped request must not decorate the
                // partial answer it left behind.
                if (!owner.isEmpty() && OrbitRequestManager.isCancelled(app, owner)) {
                    trace.outcome = RichAnswerTrace.Outcome.IMAGE_FOUND_BUT_REQUEST_CANCELLED;
                    return;
                }
                if (!ConversationStore.attachRichImages(app, chat, answer, found)) {
                    // The picture is fine and the message it belonged to is not there any more.
                    // Recorded as its own outcome because it is a completely different bug from a
                    // failed download, and Beta 2 could not tell them apart.
                    trace.outcome = RichAnswerTrace.Outcome.IMAGE_FOUND_BUT_MESSAGE_NOT_FOUND;
                    return;
                }
                trace.outcome = RichAnswerTrace.Outcome.IMAGE_FOUND_AND_ATTACHED;
                for (Listener listener : LISTENERS) {
                    try { listener.onRichImagesAttached(chat); } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {
                // A failed lookup is never a failed answer. The text stands exactly as it was.
            } finally {
                RichAnswerTrace.record(app, trace);
            }
        });
    }

    /**
     * Whether the user has left sourced pictures on.
     *
     * <p>Defaults to on, and reads the preference at discovery time rather than caching it, so
     * turning it off stops the next answer from looking rather than the one after that.
     */
    public static boolean enabled(Context context) {
        return context != null && Prefs.richAnswers(context);
    }

    /**
     * The best pictures the cited pages offer, in the order they should be drawn.
     *
     * <p>Blocking. Each page contributes at most one picture, so two images always means two
     * different sources rather than two crops of the same hero graphic. A page that refuses the
     * fetch, turns out to be a PDF, or carries nothing usable simply contributes nothing - and says
     * which of those it was in {@code trace}.
     */
    static List<RichAnswerImage> resolve(Context context, List<String> pages, int wanted, int anchor,
                                         List<String> subject, RichAnswerTrace.Attempt trace) {
        List<RichAnswerImage> found = new ArrayList<>();
        if (pages == null || pages.isEmpty()) return found;
        int limit = Math.max(1, Math.min(wanted, RichAnswerImage.MAX_PER_MESSAGE));
        int budget = trace != null && trace.pageBudget > 0 ? trace.pageBudget : MAX_PAGES_EXAMINED;
        Set<String> seenImages = new LinkedHashSet<>();
        int examined = 0;

        for (String page : pages) {
            if (found.size() >= limit || examined >= budget) break;
            RichAnswerTrace.PageRecord record = trace == null ? new RichAnswerTrace.PageRecord()
                    : trace.page();
            record.host = RichAnswerTrace.hostOf(page);
            record.path = RichAnswerTrace.pathOf(page);
            if (!RichAnswerUrlPolicy.isFetchablePageUrl(page)) {
                // Not counted against the budget: refusing an address costs no request, so it must
                // not consume one of the chances a later, usable source was going to get.
                record.fetchAllowed = false;
                record.reason = RichAnswerTrace.Reason.UNSAFE_URL;
                continue;
            }
            examined++;
            if (trace != null) trace.pagesAttempted = examined;

            RichAnswerPageFetcher.PageResult result = RichAnswerPageFetcher.fetchPage(page);
            record.host = RichAnswerTrace.hostOf(result.finalUrl.isEmpty() ? page : result.finalUrl);
            record.path = RichAnswerTrace.pathOf(result.finalUrl.isEmpty() ? page : result.finalUrl);
            record.fetchAllowed = result.reason != RichAnswerTrace.Reason.UNSAFE_URL
                    && result.reason != RichAnswerTrace.Reason.PRIVATE_HOST;
            record.httpStatus = result.status;
            record.contentType = RichAnswerImageFormat.baseType(result.contentType);
            record.bytesRead = result.bytesRead;
            record.redirects = result.redirects;
            record.previewCandidates = result.preview.imageUrls.size();
            record.articleCandidates = result.articleImages.size();
            record.reason = result.reason;
            if (!result.fetched) continue;

            String pageUrl = result.finalUrl.isEmpty() ? page : result.finalUrl;
            RichAnswerImage image = bestFrom(context, result, pageUrl, anchor, subject,
                    seenImages, record);
            if (image != null) found.add(image);
        }
        return found;
    }

    /**
     * The first candidate from one page that genuinely fetches, decodes and is worth drawing.
     *
     * <p>Preview images and article images are ranked in one list rather than in two passes. That
     * ordering is the fix: a page's share card is a candidate like any other and wins on its origin
     * bonus when nothing better exists, but it no longer gets to be the only thing considered, and
     * a photograph inside the article that actually matches the question outranks it.
     *
     * <p>Strictly bounded. {@link #MAX_RANKED_CANDIDATES_PER_PAGE} ranked,
     * {@link #MAX_FETCHED_CANDIDATES_PER_PAGE} downloaded, each one going through exactly the same
     * URL policy, redirect limit, byte ceiling and decode bound as the first, and no candidate that
     * has already failed is ever retried.
     */
    static RichAnswerImage bestFrom(Context context, RichAnswerPageFetcher.PageResult result,
                                    String pageUrl, int anchor, List<String> subject,
                                    Set<String> seenImages, RichAnswerTrace.PageRecord record) {
        List<Ranked> ranked = rank(result, pageUrl, subject, seenImages, record);
        int fetched = 0;
        for (Ranked candidate : ranked) {
            if (fetched >= MAX_FETCHED_CANDIDATES_PER_PAGE) break;
            fetched++;
            RichAnswerTrace.CandidateRecord entry = candidate.record;
            entry.fetched = true;

            // Fetched before it is committed to, because a declared image can be a 40-pixel logo, a
            // placeholder, or something that is not an image at all - and the decoded bounds are the
            // only honest answer to which of those it is.
            RemoteImageLoader.Result fetch =
                    RemoteImageLoader.fetchForRichAnswer(context, candidate.candidate.url);
            entry.httpStatus = fetch.status;
            entry.mime = fetch.contentType;
            entry.bytesRead = fetch.bytesRead;
            entry.redirects = fetch.redirects;
            entry.decodedWidth = fetch.decodedWidth;
            entry.decodedHeight = fetch.decodedHeight;
            if (!fetch.loaded()) {
                entry.reason = reasonFor(fetch.failure);
                continue;
            }
            // The picture arrived and turned out to be an icon or a letterbox banner. A judgement
            // about content, made here with the other content rules, and recorded as the shape it
            // actually was rather than as a transport failure.
            RichAnswerTrace.Reason dimensions =
                    RichAnswerRelevance.judgeDimensions(fetch.decodedWidth, fetch.decodedHeight);
            if (dimensions != RichAnswerTrace.Reason.ACCEPTED) {
                entry.reason = dimensions;
                continue;
            }

            seenImages.add(candidate.candidate.url);
            String caption = captionFor(candidate.candidate, result.preview);
            RichAnswerImage image = RichAnswerImage.webSource(
                    candidate.candidate.url, pageUrl, caption, caption, anchor);
            if (!image.isUsable()) {
                entry.reason = RichAnswerTrace.Reason.UNSAFE_URL;
                continue;
            }
            entry.reason = RichAnswerTrace.Reason.ACCEPTED;
            return image;
        }
        return null;
    }

    /** One candidate with its score and its diagnostic row. */
    static final class Ranked {
        final RichAnswerArticleImages.Candidate candidate;
        final int score;
        final int order;
        /** Why this one was refused, or {@link RichAnswerTrace.Reason#NONE} while it is still in. */
        RichAnswerTrace.Reason reason = RichAnswerTrace.Reason.NONE;
        RichAnswerTrace.CandidateRecord record;

        Ranked(RichAnswerArticleImages.Candidate candidate, int score, int order) {
            this.candidate = candidate;
            this.score = score;
            this.order = order;
        }
    }

    /**
     * The candidates of one page worth trying, best first, with every refusal written down.
     *
     * <p>The rejected ones are recorded as well as the accepted ones, and that is most of the value
     * of this method on a real device: "eleven candidates, all rejected as chrome" and "no
     * candidates at all" look identical from the outside and mean completely different things.
     */
    static List<Ranked> rank(RichAnswerPageFetcher.PageResult result, String pageUrl,
                             List<String> subject, Set<String> seenImages,
                             RichAnswerTrace.PageRecord record) {
        List<RichAnswerArticleImages.Candidate> all = new ArrayList<>(result.preview.candidates);
        all.addAll(result.articleImages);

        List<Ranked> scored = new ArrayList<>();
        List<Ranked> rejected = new ArrayList<>();
        Set<String> seenHere = new LinkedHashSet<>();
        int order = 0;
        for (RichAnswerArticleImages.Candidate candidate : all) {
            int position = order++;
            if (candidate == null || candidate.url.isEmpty()) continue;
            if (!seenHere.add(candidate.url)) continue;
            if (seenImages != null && seenImages.contains(candidate.url)) {
                rejected.add(reject(candidate, position, RichAnswerTrace.Reason.DUPLICATE));
                continue;
            }
            RichAnswerRelevance.Judgement judgement = RichAnswerRelevance.judge(
                    candidate, pageUrl, result.preview.title, subject, true);
            if (!judgement.acceptable()) {
                rejected.add(reject(candidate, position, judgement.reason));
                continue;
            }
            scored.add(new Ranked(candidate, judgement.score, position));
        }

        // Descending by score, and by document order when they tie, so a page's own first choice
        // wins a tie and the ordering is stable between runs.
        Collections.sort(scored, (a, b) ->
                a.score != b.score ? Integer.compare(b.score, a.score) : Integer.compare(a.order, b.order));

        List<Ranked> out = new ArrayList<>();
        for (Ranked candidate : scored) {
            if (out.size() >= MAX_RANKED_CANDIDATES_PER_PAGE) {
                rejected.add(reject(candidate.candidate, candidate.order, RichAnswerTrace.Reason.LOW_SCORE));
                continue;
            }
            out.add(candidate);
        }
        if (record != null) {
            for (Ranked candidate : out) candidate.record = describe(record, candidate.candidate,
                    candidate.score, RichAnswerTrace.Reason.NONE);
            for (Ranked candidate : rejected) describe(record, candidate.candidate,
                    candidate.score, candidate.reason);
        } else {
            for (Ranked candidate : out) candidate.record = new RichAnswerTrace.CandidateRecord();
        }
        return out;
    }

    private static Ranked reject(RichAnswerArticleImages.Candidate candidate, int order,
                                 RichAnswerTrace.Reason reason) {
        Ranked ranked = new Ranked(candidate, -1, order);
        ranked.reason = reason;
        return ranked;
    }

    private static RichAnswerTrace.CandidateRecord describe(RichAnswerTrace.PageRecord page,
                                                            RichAnswerArticleImages.Candidate candidate,
                                                            int score,
                                                            RichAnswerTrace.Reason reason) {
        RichAnswerTrace.CandidateRecord entry = page.candidate();
        entry.origin = candidate.origin;
        entry.host = RichAnswerTrace.hostOf(candidate.url);
        entry.path = RichAnswerTrace.pathOf(candidate.url);
        entry.score = score;
        entry.declaredWidth = candidate.declaredWidth;
        entry.declaredHeight = candidate.declaredHeight;
        entry.reason = reason;
        return entry;
    }

    /** The transport's own category, translated into the trace's vocabulary. */
    static RichAnswerTrace.Reason reasonFor(RemoteImageLoader.Failure failure) {
        if (failure == null) return RichAnswerTrace.Reason.NETWORK;
        switch (failure) {
            case BLOCKED: return RichAnswerTrace.Reason.UNSAFE_URL;
            case REDIRECT_BLOCKED:
            case TOO_MANY_REDIRECTS: return RichAnswerTrace.Reason.REDIRECT_REJECTED;
            case HTTP_ERROR: return RichAnswerTrace.Reason.HTTP_ERROR;
            case NOT_AN_IMAGE: return RichAnswerTrace.Reason.NOT_IMAGE;
            case UNSUPPORTED_FORMAT: return RichAnswerTrace.Reason.UNSUPPORTED_FORMAT;
            case TOO_LARGE: return RichAnswerTrace.Reason.TOO_LARGE;
            case DECODE_FAILED: return RichAnswerTrace.Reason.DECODE_FAILED;
            case TIMEOUT: return RichAnswerTrace.Reason.TIMEOUT;
            default: return RichAnswerTrace.Reason.NETWORK;
        }
    }

    /**
     * The line drawn under a picture: the page's own words for it, trimmed to one clause.
     *
     * <p>Never Orbit's words and never the model's. A caption that Orbit wrote would be Orbit
     * asserting something about a photograph it has not looked at; the page's own figure caption,
     * alt text or title is a claim its author made, which is exactly what an attribution should
     * carry - and it is drawn as text and never interpreted as anything else.
     *
     * <p>The image's own caption is preferred over the page's, because a figcaption describes the
     * photograph while a page description describes the article. Beta 2 only had the second.
     */
    static String captionFor(RichAnswerArticleImages.Candidate candidate,
                             RichAnswerPageMetadata.Preview preview) {
        String own = candidate == null ? "" : candidate.describedBy();
        if (!own.trim().isEmpty()) return trimCaption(own);
        return captionFor(preview);
    }

    /** The page's own words for itself, when the image carried none. */
    static String captionFor(RichAnswerPageMetadata.Preview preview) {
        if (preview == null) return "";
        return trimCaption(preview.description.isEmpty() ? preview.title : preview.description);
    }

    static String trimCaption(String candidate) {
        String text = candidate == null ? "" : candidate.replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) return "";
        // A publisher's title often ends with its own name after a separator, which is branding
        // rather than description and reads badly beside the domain Orbit already shows. The
        // minimum length is what stops "A | Some Very Long Publisher" collapsing to one letter:
        // below it, the part before the separator is too short to have been the real title.
        for (String separator : new String[]{" | ", " - ", " · ", " — "}) {
            int at = text.lastIndexOf(separator);
            if (at >= 8) { text = text.substring(0, at).trim(); break; }
        }
        if (text.length() <= 110) return text;
        String clipped = text.substring(0, 110);
        int space = clipped.lastIndexOf(' ');
        if (space > 60) clipped = clipped.substring(0, space);
        return clipped.trim();
    }
}
