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
 * attached by matching the completed request id and answer through
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
 *
 * <p><b>What Beta 5 changed.</b> None of the above ever ran on the device, because it was never
 * given a source. A searched answer would show Orbit's own "Open source · commons.wikimedia.org"
 * control while this class recorded "Sources received: 0", which is not a contradiction: the chip
 * is drawn from the explicit {@code Source:} line at the end of the answer, and discovery only
 * accepted pages reported by the hosted-search events. When the backend produced no envelope Orbit
 * recognised, the two disagreed and the picture was never looked for. {@link RichAnswerProvenance}
 * now resolves both, structured provenance first, and discovery starts whenever Orbit legitimately
 * knows a source at all. Which route it used is written into the trace.
 *
 * <p><b>What Beta 6 changed.</b> Beta 5's fix worked, and then answered "show me pics of a mallard
 * duck" with one picture. Two separate rules were doing it: the count policy asked for a second
 * image only for comparisons, and the resolver took at most one picture from each page while the
 * recovered-source route routinely supplies exactly one page. Both are gone.
 * {@link RichAnswerRelevance#maxImagesFor} now reads plural picture words and written numbers, a
 * page may contribute two pictures when two are wanted, {@link RichAnswerAssetIdentity} makes sure
 * those are two photographs rather than one photograph at two widths, and an explicitly plural
 * request that still comes up short may follow a small number of image-oriented links out of the
 * answer itself - as {@link RichAnswerDiscoveryHint}s, which are where a picture is and never a
 * citation for anything the answer said.
 *
 * <p><b>What Beta 7 changed.</b> Beta 6 could tell one photograph from another by reading their
 * addresses, and the device found the case where that is not enough: a photograph rehosted on an
 * unrelated CDN under an unrelated name, shown beside the original as though there were two of
 * them. There are now two identity layers rather than one. {@link RichAnswerAssetIdentity} still
 * goes first and still refuses the obvious renditions for free; and once a candidate has actually
 * downloaded and decoded, {@link RichAnswerVisualIdentity} fingerprints the picture and compares it
 * with every picture this answer has already accepted, from any page and any host. Two image slots
 * now mean two photographs. When Orbit cannot find a second photograph it shows one, which is the
 * right answer and is written into the trace rather than papered over with a repeat.
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
     * <p>A declined attempt is written when the answer cited sources or the request is visual.
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
        trace.enabled = enabled(app);
        trace.providerEligible = AiProviders.active(app).capabilities().richWebMedia;
        trace.intent = RichAnswerRelevance.intentFor(prompt, answer);
        boolean strong = trace.intent == RichAnswerTrace.Intent.STRONG_VISUAL;

        // Filled in before provenance is resolved, and that ordering is the point. Beta 4 computed
        // these after the zero-source return, so a failed strongly visual attempt reported "Images
        // requested: 0, Page budget: 0" - three fields that had never been evaluated reading like
        // three findings. What Orbit would have done is knowable without a single source, so it is
        // recorded without one.
        int wanted = 0;
        if (trace.intent != RichAnswerTrace.Intent.NONE) {
            wanted = RichAnswerRelevance.maxImagesFor(prompt);
            trace.requestedImages = wanted;
            trace.pageBudget = strong ? MAX_PAGES_EXAMINED_STRONG : MAX_PAGES_EXAMINED;
        }

        // The fallback is offered only to an answer that would genuinely have gone on to discovery.
        // A trailing Source marker under an ordinary chat answer, on a provider without hosted web
        // media, or with Rich Answers switched off, must not start a page fetch.
        boolean fallbackAllowed = trace.enabled && trace.providerEligible
                && trace.intent != RichAnswerTrace.Intent.NONE;
        RichAnswerProvenance.Resolved provenance =
                RichAnswerProvenance.resolve(reply, fallbackAllowed);
        trace.provenance = route(provenance.route);
        trace.sourcesReceived = provenance.urls.size();

        if (!provenance.hasSources() && trace.intent == RichAnswerTrace.Intent.NONE) return;
        if (!trace.enabled) trace.outcome = RichAnswerTrace.Outcome.DISABLED;
        else if (!trace.providerEligible) trace.outcome = RichAnswerTrace.Outcome.PROVIDER_UNSUPPORTED;
        else if (trace.intent == RichAnswerTrace.Intent.NONE) trace.outcome = RichAnswerTrace.Outcome.NOT_VISUAL;
        else if (!provenance.hasSources()) trace.outcome = RichAnswerTrace.Outcome.NO_SOURCE_URLS_RECEIVED;
        if (trace.outcome != RichAnswerTrace.Outcome.INCOMPLETE) {
            RichAnswerTrace.record(app, trace);
            return;
        }

        String chat = conversationId.trim();
        String owner = requestId == null ? "" : requestId.trim();
        List<String> pages = new ArrayList<>(provenance.urls);
        int anchor = RichAnswerPlacement.placementFor(answer);
        // Written back onto the stored message only when the fallback supplied it, so reopening the
        // chat still knows which page the picture came from. An answer that already carries
        // structured provenance is left exactly as it is.
        List<String> recovered = provenance.route == RichAnswerProvenance.Route.EXPLICIT_SOURCE_MARKER
                ? pages : new ArrayList<>();
        int images = wanted;
        // Derived here, on this thread, and handed straight to ranking. Never stored, never sent,
        // and deliberately never written into the trace. Beta 9 carries the kind of picture that
        // was asked for alongside the subject, because "show me pics of a mallard duck" and "show
        // me the flag of Ireland" want opposite things from a decoded bitmap.
        RichAnswerCandidateQuality.Demand demand = RichAnswerCandidateQuality.Demand.of(prompt);
        List<String> subject = demand.subject;

        // The secondary path, gated on every condition at once and collected up front so the
        // executor never reads the answer again. Strong visual intent is not enough on its own:
        // the user has to have asked, in words, for more than one picture, because a comparison
        // that wants two subjects must not send Orbit off to pages the answer never cited.
        List<RichAnswerDiscoveryHint> hints = Collections.emptyList();
        if (images > 1 && strong && RichAnswerRelevance.requestsMultipleImages(prompt)) {
            hints = RichAnswerDiscoveryHint.from(answer, subject, pages);
            trace.discoveryHintsConsidered = hints.size();
        }
        List<RichAnswerDiscoveryHint> discoveryHints = hints;

        EXECUTOR.execute(() -> {
            try {
                List<RichAnswerImage> found = resolve(app, pages, discoveryHints, images, anchor,
                        demand, trace);
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
                if (!ConversationStore.attachRichImages(app, chat, owner, answer, found, recovered)) {
                    // The picture is fine and the message it belonged to is not there any more.
                    // Recorded as its own outcome because it is a completely different bug from a
                    // failed download, and Beta 2 could not tell them apart.
                    trace.outcome = RichAnswerTrace.Outcome.IMAGE_FOUND_BUT_MESSAGE_NOT_FOUND;
                    return;
                }
                trace.outcome = RichAnswerTrace.Outcome.IMAGE_FOUND_AND_ATTACHED;
                trace.attachedImages = found.size();
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

    /** The resolver's route in the trace's own vocabulary. One enum per layer, mapped once. */
    static RichAnswerTrace.Provenance route(RichAnswerProvenance.Route route) {
        if (route == RichAnswerProvenance.Route.STRUCTURED_HOSTED_SEARCH) {
            return RichAnswerTrace.Provenance.STRUCTURED_HOSTED_SEARCH;
        }
        if (route == RichAnswerProvenance.Route.EXPLICIT_SOURCE_MARKER) {
            return RichAnswerTrace.Provenance.EXPLICIT_SOURCE_MARKER;
        }
        return RichAnswerTrace.Provenance.NONE;
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
     * <p>Blocking. Kept for the single-picture path and for callers that have no fallback to offer.
     */
    static List<RichAnswerImage> resolve(Context context, List<String> pages, int wanted, int anchor,
                                         RichAnswerCandidateQuality.Demand demand,
                                         RichAnswerTrace.Attempt trace) {
        return resolve(context, pages, Collections.emptyList(), wanted, anchor, demand, trace);
    }

    /**
     * The best pictures the cited pages offer, with a bounded fallback when they come up short.
     *
     * <p><b>What Beta 6 changed.</b> Every page used to contribute at most one picture, on the
     * reasoning that two pictures meant a comparison and a comparison wants two sources. That was
     * right for comparisons and wrong for the request that actually broke: "show me pics of a
     * mallard duck" reaches this method with one recovered source page and seventeen article
     * candidates on it, and a one-per-page rule guarantees it comes back with a single photograph
     * however many the page carries. A page may now contribute a second picture, and the thing that
     * stops that becoming the same duck twice is {@link RichAnswerAssetIdentity} rather than an
     * arbitrary limit.
     *
     * <p><b>Restraint is unchanged.</b> The page budget is the same and is shared with the
     * fallback, the per-page download budget is the same and is not doubled because two pictures
     * were asked for, and every fetch goes through the same policy, redirect limit, byte ceiling
     * and decode bound it did before. Wanting two pictures buys ordering, not bandwidth.
     *
     * @param hints image-oriented links from the answer itself, tried only after the trusted pages
     *              and only when the caller established that the user explicitly asked for several
     *              pictures. Never citations, and never treated as any.
     */
    static List<RichAnswerImage> resolve(Context context, List<String> pages,
                                         List<RichAnswerDiscoveryHint> hints, int wanted, int anchor,
                                         RichAnswerCandidateQuality.Demand demand,
                                         RichAnswerTrace.Attempt trace) {
        List<RichAnswerImage> found = new ArrayList<>();
        int limit = Math.max(1, Math.min(wanted, RichAnswerImage.MAX_PER_MESSAGE));
        int budget = trace != null && trace.pageBudget > 0 ? trace.pageBudget : MAX_PAGES_EXAMINED;
        Set<String> seenAssets = new LinkedHashSet<>();
        // One set for the whole attempt, which is what makes the second layer work across pages and
        // across hosts rather than only inside the page that happened to be read first.
        Visuals visuals = new Visuals(trace);
        int[] examined = {0};

        if (pages != null) {
            for (String page : pages) {
                if (found.size() >= limit || examined[0] >= budget) break;
                readPage(context, page, false, found, limit, examined, anchor, demand,
                        seenAssets, visuals, trace);
            }
        }
        // The first picture is never thrown away because the page could not supply a second, so
        // "one from source A, one from a hint" and "both from source A" are equally valid answers.
        if (found.size() < limit && hints != null && !hints.isEmpty()) {
            for (RichAnswerDiscoveryHint hint : hints) {
                if (found.size() >= limit || examined[0] >= budget) break;
                if (trace != null) trace.discoveryHintsUsed++;
                if (hint.isImage()) {
                    readDirectImage(context, hint.url, found, examined, anchor, demand, seenAssets,
                            visuals, trace);
                } else {
                    readPage(context, hint.url, true, found, limit, examined, anchor, demand,
                            seenAssets, visuals, trace);
                }
            }
        }
        return found;
    }

    /**
     * The pictures this answer has already accepted, as pictures rather than as addresses.
     *
     * <p><b>The second of the two identity layers, and the one Beta 7 adds.</b>
     * {@link RichAnswerAssetIdentity} reads a URL and is free, so it goes first and refuses the
     * obvious renditions before a byte is spent. It cannot go any further than that: two addresses
     * that share no structure can still be one photograph, and the device proved it by showing the
     * same Mallard twice from two unrelated CDN paths. Once a candidate has downloaded and decoded,
     * {@link RichAnswerVisualIdentity} asks the only question that settles it.
     *
     * <p>Owned by one {@link #resolve} call and never shared between answers, so the comparison is
     * always "is this the same as something <em>this answer</em> is already showing".
     */
    static final class Visuals {
        private final List<RichAnswerVisualIdentity.Fingerprint> accepted = new ArrayList<>();
        private final RichAnswerTrace.Attempt trace;

        Visuals(RichAnswerTrace.Attempt trace) { this.trace = trace; }

        /** Whether this picture is one the answer already carries. Counts the refusal. */
        boolean isDuplicate(RichAnswerVisualIdentity.Fingerprint print) {
            for (RichAnswerVisualIdentity.Fingerprint each : accepted) {
                if (RichAnswerVisualIdentity.sameImage(each, print)) {
                    if (trace != null) trace.visualDuplicates++;
                    return true;
                }
            }
            return false;
        }

        /** Remembers a picture that is going on the answer. */
        void accept(RichAnswerVisualIdentity.Fingerprint print) {
            if (print != null && print.isStrong()) accepted.add(print);
        }
    }

    /** One page read, ranked and mined for as many distinct pictures as are still wanted. */
    private static void readPage(Context context, String page, boolean hint,
                                 List<RichAnswerImage> found, int limit, int[] examined, int anchor,
                                 RichAnswerCandidateQuality.Demand demand, Set<String> seenAssets,
                                 Visuals visuals, RichAnswerTrace.Attempt trace) {
        RichAnswerTrace.PageRecord record = trace == null ? new RichAnswerTrace.PageRecord()
                : trace.page();
        record.discoveryHint = hint;
        record.host = RichAnswerTrace.hostOf(page);
        record.path = RichAnswerTrace.pathOf(page);
        if (!RichAnswerUrlPolicy.isFetchablePageUrl(page)) {
            // Not counted against the budget: refusing an address costs no request, so it must
            // not consume one of the chances a later, usable source was going to get.
            record.fetchAllowed = false;
            record.reason = RichAnswerTrace.Reason.UNSAFE_URL;
            return;
        }
        examined[0]++;
        if (trace != null) trace.pagesAttempted = examined[0];

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
        // A 403 or a 404 on a cited page is ordinary public-web messiness rather than the end of
        // the search: the loop above moves on to the next source, and then to the hints.
        if (!result.fetched) return;

        String pageUrl = result.finalUrl.isEmpty() ? page : result.finalUrl;
        // The count already accepted is passed down, not just the count still wanted, because the
        // bar a candidate has to clear depends on whether the answer already has a picture.
        found.addAll(bestFromPage(context, result, pageUrl, anchor, demand, seenAssets, visuals,
                record, limit - found.size(), found.size()));
    }

    /**
     * A hint that was written as the picture itself, fetched and judged as one.
     *
     * <p>No markup is read, because there is none to read: this is an address ending in an image,
     * so it goes straight through the same transport, MIME check, decode bound and dimension rules
     * every other candidate does. Its source is the address it came from, which is a true statement
     * about where the picture is and makes no claim about what the answer cited.
     */
    private static void readDirectImage(Context context, String url, List<RichAnswerImage> found,
                                        int[] examined, int anchor,
                                        RichAnswerCandidateQuality.Demand demand,
                                        Set<String> seenAssets, Visuals visuals,
                                        RichAnswerTrace.Attempt trace) {
        RichAnswerTrace.PageRecord record = trace == null ? new RichAnswerTrace.PageRecord()
                : trace.page();
        record.discoveryHint = true;
        record.host = RichAnswerTrace.hostOf(url);
        record.path = RichAnswerTrace.pathOf(url);
        RichAnswerTrace.CandidateRecord entry = record.candidate();
        entry.origin = RichAnswerArticleImages.Origin.ARTICLE_IMG;
        entry.host = record.host;
        entry.path = record.path;
        if (!RichAnswerUrlPolicy.isFetchableImageUrl(url)) {
            record.fetchAllowed = false;
            record.reason = RichAnswerTrace.Reason.UNSAFE_URL;
            entry.reason = RichAnswerTrace.Reason.UNSAFE_URL;
            return;
        }
        String asset = assetKey(url);
        if (seenAssets.contains(asset)) {
            entry.reason = RichAnswerTrace.Reason.DUPLICATE;
            return;
        }
        examined[0]++;
        if (trace != null) trace.pagesAttempted = examined[0];

        entry.fetched = true;
        RemoteImageLoader.Result fetch = RemoteImageLoader.fetchForRichAnswer(context, url);
        entry.httpStatus = fetch.status;
        entry.mime = fetch.contentType;
        entry.bytesRead = fetch.bytesRead;
        entry.redirects = fetch.redirects;
        entry.decodedWidth = fetch.decodedWidth;
        entry.decodedHeight = fetch.decodedHeight;
        record.httpStatus = fetch.status;
        if (!fetch.loaded()) {
            entry.reason = reasonFor(fetch.failure);
            record.reason = entry.reason;
            return;
        }
        RichAnswerTrace.Reason dimensions =
                RichAnswerRelevance.judgeDimensions(fetch.decodedWidth, fetch.decodedHeight);
        if (dimensions != RichAnswerTrace.Reason.ACCEPTED) {
            entry.reason = dimensions;
            record.reason = dimensions;
            return;
        }
        // A hint carries no markup, so there is nothing candidate-specific to read: its label
        // already had to name the subject before it became a hint at all. What can still be asked
        // of it is the picture itself, and a request for photographs does ask.
        if (demand != null && demand.photographic
                && !RichAnswerVisualQuality.isPhotographLike(fetch.bitmap)) {
            entry.reason = RichAnswerTrace.Reason.GRAPHIC_OR_PLACEHOLDER;
            record.reason = RichAnswerTrace.Reason.GRAPHIC_OR_PLACEHOLDER;
            return;
        }
        // A hint is the path most likely to arrive at a copy of a picture the pages already
        // supplied, because it is the same photograph rehosted somewhere else. It gets the same
        // check as everything else, from the same set.
        RichAnswerVisualIdentity.Fingerprint print =
                RichAnswerVisualIdentity.fingerprint(fetch.bitmap);
        entry.visualId = print.token();
        if (visuals.isDuplicate(print)) {
            entry.reason = RichAnswerTrace.Reason.VISUAL_DUPLICATE;
            record.reason = RichAnswerTrace.Reason.VISUAL_DUPLICATE;
            return;
        }
        RichAnswerImage image = RichAnswerImage.webSource(url, url, "", "", anchor);
        if (!image.isUsable()) {
            entry.reason = RichAnswerTrace.Reason.UNSAFE_URL;
            return;
        }
        seenAssets.add(asset);
        visuals.accept(print);
        entry.reason = RichAnswerTrace.Reason.ACCEPTED;
        record.reason = RichAnswerTrace.Reason.ACCEPTED;
        found.add(image);
    }

    /**
     * The single best picture one page offers, or null.
     *
     * <p>{@link #bestFromPage} asked for one, which is what every page did before Beta 6 and what
     * every singular request still does.
     */
    static RichAnswerImage bestFrom(Context context, RichAnswerPageFetcher.PageResult result,
                                    String pageUrl, int anchor,
                                    RichAnswerCandidateQuality.Demand demand,
                                    Set<String> seenAssets, RichAnswerTrace.PageRecord record) {
        List<RichAnswerImage> found = bestFromPage(context, result, pageUrl, anchor, demand,
                seenAssets, new Visuals(null), record, 1, 0);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * The candidates from one page that genuinely fetch, decode and are worth drawing.
     *
     * <p>Preview images and article images are ranked in one list rather than in two passes. That
     * ordering is the fix Beta 3 made: a page's share card is a candidate like any other and wins
     * on its origin bonus when nothing better exists, but it no longer gets to be the only thing
     * considered, and a photograph inside the article that actually matches the question outranks
     * it.
     *
     * <p><b>Distinct, not merely different.</b> Once a picture is accepted, every later candidate
     * that resolves to the same underlying asset is refused before a byte is spent on it. That is
     * what stops the real Mallard page - one photograph offered at 1920, 1280 and 960 - from being
     * presented as two pictures of a duck. A candidate that <em>failed</em> never blocks anything,
     * so a 403 on the largest rendition still leaves the smaller one available to save the picture.
     *
     * <p><b>And then the picture itself.</b> Beta 6 stopped there, and the device found the case
     * that leaves behind: two addresses with nothing in common that turn out to be one photograph.
     * A candidate that has downloaded and decoded is fingerprinted by
     * {@link RichAnswerVisualIdentity} and compared against everything this answer has already
     * accepted, from any page and any host. A visual duplicate is refused with its own reason and
     * the search carries on looking for a genuinely different picture; if there is not one, the
     * answer shows one picture. Two image slots mean two photographs or they mean one.
     *
     * <p><b>And then whether it belongs here at all.</b> Beta 8 stopped at "distinct", and the
     * device produced the consequence: a real Mallard photograph beside a large blank card carrying
     * a small blue cube, captioned with the Mallard page's title. Every check above passed on the
     * cube. It was a different picture, it was big enough, it decoded, and it had nothing to do
     * with the question - the only thing tying it to a duck was the document it sat in.
     * {@link RichAnswerCandidateQuality} asks what the page said about <em>this image</em> rather
     * than about itself, and {@link RichAnswerVisualQuality} asks whether the decoded bitmap is a
     * photograph when a photograph is what was wanted. A candidate with nothing of its own is
     * refused before it costs a request, and the second slot is refused outright rather than filled
     * with whatever came next. <b>One good photograph is a complete answer to a request for two.</b>
     *
     * <p>Strictly bounded, and by the same numbers as before: {@link #MAX_RANKED_CANDIDATES_PER_PAGE}
     * ranked and {@link #MAX_FETCHED_CANDIDATES_PER_PAGE} downloaded, whether one picture is wanted
     * or two.
     *
     * @param accepted how many pictures this answer already carries, from this page or any earlier
     *                 one. It is the slot number the next acceptance would take, and the bar rises
     *                 with it.
     */
    static List<RichAnswerImage> bestFromPage(Context context, RichAnswerPageFetcher.PageResult result,
                                              String pageUrl, int anchor,
                                              RichAnswerCandidateQuality.Demand demand,
                                              Set<String> seenAssets, Visuals visuals,
                                              RichAnswerTrace.PageRecord record, int wanted,
                                              int accepted) {
        List<RichAnswerImage> out = new ArrayList<>();
        int remaining = Math.max(0, wanted);
        if (remaining == 0) return out;
        RichAnswerCandidateQuality.Demand wants =
                demand == null ? RichAnswerCandidateQuality.Demand.NONE : demand;
        List<Ranked> ranked = rank(result, pageUrl, wants, seenAssets, record);
        int fetched = 0;
        for (Ranked candidate : ranked) {
            if (out.size() >= remaining) break;
            if (fetched >= MAX_FETCHED_CANDIDATES_PER_PAGE) break;
            RichAnswerTrace.CandidateRecord entry = candidate.record;
            String asset = assetKey(candidate.candidate.url);
            // Checked here as well as in ranking, because the set grows as this loop accepts
            // pictures: a resize variant of the photograph just accepted is a duplicate that the
            // ranking pass, run before any of them, could not have known about.
            if (seenAssets.contains(asset)) {
                entry.reason = RichAnswerTrace.Reason.DUPLICATE;
                continue;
            }
            // Which slot this candidate is competing for, counted across the whole answer. Asked
            // before the download, so a candidate the page never tied to anything never costs a
            // request - and refused with a reason that says which slot it failed.
            int slot = accepted + out.size();
            RichAnswerTrace.Reason relevance =
                    RichAnswerCandidateQuality.judgeSlot(candidate.evidence, slot);
            if (relevance != RichAnswerTrace.Reason.NONE) {
                entry.reason = relevance;
                continue;
            }
            fetched++;
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

            // Is this a photograph, or is it graphic design. Only asked when photographs are what
            // the question wanted, so a diagram, a map, a flag or a logo is never refused for
            // failing to look like a photograph of something.
            boolean photographLike = RichAnswerVisualQuality.isPhotographLike(fetch.bitmap);
            if (wants.photographic && !photographLike) {
                entry.reason = RichAnswerTrace.Reason.GRAPHIC_OR_PLACEHOLDER;
                continue;
            }

            // The second identity layer. Everything above this line is a statement about an
            // address; this is the only statement about the photograph.
            RichAnswerVisualIdentity.Fingerprint print =
                    RichAnswerVisualIdentity.fingerprint(fetch.bitmap);
            entry.visualId = print.token();
            if (visuals.isDuplicate(print)) {
                entry.reason = RichAnswerTrace.Reason.VISUAL_DUPLICATE;
                continue;
            }

            // The last question, and the one the second slot turns on. A candidate the page named
            // as the subject is already in; one that is only structurally promising has to be
            // article content the page described, a real photograph, and the size of a photograph
            // before it may sit beside a picture the answer already has.
            if (!RichAnswerCandidateQuality.confirms(candidate.evidence, wants, slot,
                    fetch.decodedWidth, fetch.decodedHeight, photographLike)) {
                entry.reason = slot <= 0
                        ? RichAnswerTrace.Reason.INSUFFICIENT_SUBJECT_RELEVANCE
                        : RichAnswerTrace.Reason.SECOND_SLOT_LOW_RELEVANCE;
                continue;
            }

            String caption = captionFor(candidate.candidate, result.preview);
            RichAnswerImage image = RichAnswerImage.webSource(
                    candidate.candidate.url, pageUrl, caption, caption, anchor);
            if (!image.isUsable()) {
                entry.reason = RichAnswerTrace.Reason.UNSAFE_URL;
                continue;
            }
            seenAssets.add(asset);
            visuals.accept(print);
            entry.reason = RichAnswerTrace.Reason.ACCEPTED;
            out.add(image);
        }
        return out;
    }

    /**
     * How one address is told apart from another. The cheap first layer, and only the first.
     *
     * <p>The canonical asset where the address yields one, and the address itself where it does
     * not, so an unparseable or unusual URL is compared with something rather than colliding with
     * every other empty key. It answers before a request is made, which is exactly what it is for
     * and exactly what limits it: whether two <em>different-looking</em> addresses are one
     * photograph is a question only {@link RichAnswerVisualIdentity} can answer, and it is asked
     * after the download rather than instead of this.
     */
    static String assetKey(String url) {
        String canonical = RichAnswerAssetIdentity.canonical(url);
        return canonical.isEmpty() ? (url == null ? "" : url) : canonical;
    }

    /** One candidate with its score and its diagnostic row. */
    static final class Ranked {
        final RichAnswerArticleImages.Candidate candidate;
        final int score;
        final int order;
        /** Why this one was refused, or {@link RichAnswerTrace.Reason#NONE} while it is still in. */
        RichAnswerTrace.Reason reason = RichAnswerTrace.Reason.NONE;
        RichAnswerTrace.CandidateRecord record;
        /** What the page said about this image in particular, worked out once during ranking. */
        RichAnswerCandidateQuality.Evidence evidence = RichAnswerCandidateQuality.Evidence.NONE;

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
                             RichAnswerCandidateQuality.Demand demand, Set<String> seenAssets,
                             RichAnswerTrace.PageRecord record) {
        RichAnswerCandidateQuality.Demand wants =
                demand == null ? RichAnswerCandidateQuality.Demand.NONE : demand;
        List<String> subject = wants.subject;
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
            // Renditions of the same photograph stay in the list rather than being collapsed here,
            // deliberately. A 1920 that 403s and a 960 that loads are two chances at one picture,
            // and dropping the second at ranking time would throw the spare away before it was
            // needed. Whether they may both be *shown* is decided at acceptance instead.
            if (seenAssets != null && seenAssets.contains(assetKey(candidate.url))) {
                rejected.add(reject(candidate, position, RichAnswerTrace.Reason.DUPLICATE,
                        RichAnswerCandidateQuality.evaluate(candidate, wants)));
                continue;
            }
            RichAnswerRelevance.Judgement judgement = RichAnswerRelevance.judge(
                    candidate, pageUrl, result.preview.title, subject, true);
            RichAnswerCandidateQuality.Evidence evidence =
                    RichAnswerCandidateQuality.evaluate(candidate, wants);
            if (!judgement.acceptable()) {
                rejected.add(reject(candidate, position, judgement.reason, evidence));
                continue;
            }
            Ranked entry = new Ranked(candidate, judgement.score, position);
            entry.evidence = evidence;
            scored.add(entry);
        }

        // Descending by score, and by document order when they tie, so a page's own first choice
        // wins a tie and the ordering is stable between runs.
        Collections.sort(scored, (a, b) ->
                a.score != b.score ? Integer.compare(b.score, a.score) : Integer.compare(a.order, b.order));

        List<Ranked> out = new ArrayList<>();
        for (Ranked candidate : scored) {
            if (out.size() >= MAX_RANKED_CANDIDATES_PER_PAGE) {
                rejected.add(reject(candidate.candidate, candidate.order,
                        RichAnswerTrace.Reason.LOW_SCORE, candidate.evidence));
                continue;
            }
            out.add(candidate);
        }
        if (record != null) {
            for (Ranked candidate : out) candidate.record = describe(record, candidate.candidate,
                    candidate.score, RichAnswerTrace.Reason.NONE, candidate.evidence);
            for (Ranked candidate : rejected) describe(record, candidate.candidate,
                    candidate.score, candidate.reason, candidate.evidence);
        } else {
            for (Ranked candidate : out) candidate.record = new RichAnswerTrace.CandidateRecord();
        }
        return out;
    }

    private static Ranked reject(RichAnswerArticleImages.Candidate candidate, int order,
                                 RichAnswerTrace.Reason reason,
                                 RichAnswerCandidateQuality.Evidence evidence) {
        Ranked ranked = new Ranked(candidate, -1, order);
        ranked.reason = reason;
        ranked.evidence = evidence == null ? RichAnswerCandidateQuality.Evidence.NONE : evidence;
        return ranked;
    }

    private static RichAnswerTrace.CandidateRecord describe(RichAnswerTrace.PageRecord page,
                                                            RichAnswerArticleImages.Candidate candidate,
                                                            int score,
                                                            RichAnswerTrace.Reason reason,
                                                            RichAnswerCandidateQuality.Evidence evidence) {
        RichAnswerTrace.CandidateRecord entry = page.candidate();
        entry.origin = candidate.origin;
        entry.host = RichAnswerTrace.hostOf(candidate.url);
        entry.path = RichAnswerTrace.pathOf(candidate.url);
        entry.score = score;
        entry.declaredWidth = candidate.declaredWidth;
        entry.declaredHeight = candidate.declaredHeight;
        entry.structure = candidate.structure;
        // The candidate's own subject match, recorded beside the overall score precisely because
        // the two disagreeing is the finding. No subject word is ever written down, only how many
        // of them this image's own words matched.
        if (evidence != null) {
            entry.subjectScore = evidence.subjectScore;
            entry.standing = evidence.standing;
        }
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
     *
     * <p><b>And the page's words are not borrowed by every image on it.</b> That fallback is honest
     * for a page's declared preview image, which the page is asserting represents it. It is a lie
     * for an arbitrary image found inside the document, and the device printed the lie: a small blue
     * cube captioned {@code File:Anas platyrhynchos-male-in water.jpg}, which reads as a promise
     * that the reader is looking at a Mallard. An article image with nothing of its own now carries
     * nothing of its own, and {@link RichAnswerImage#attributionLine()} shows the source domain by
     * itself - true, useful, and never a claim about what is in the picture.
     */
    static String captionFor(RichAnswerArticleImages.Candidate candidate,
                             RichAnswerPageMetadata.Preview preview) {
        String own = candidate == null ? "" : candidate.describedBy();
        if (!own.trim().isEmpty()) return trimCaption(own);
        if (candidate != null && !candidate.origin.isPreview()
                && candidate.origin != RichAnswerArticleImages.Origin.HOSTED_SEARCH) {
            return "";
        }
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
