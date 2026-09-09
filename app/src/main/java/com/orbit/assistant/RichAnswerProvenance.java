package com.orbit.assistant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where Rich Answers is allowed to learn which pages an answer actually used.
 *
 * <p><b>The bug this exists to close.</b> On a real device Orbit would show a web answer with its
 * native "Open source · commons.wikimedia.org" control while Rich Answers diagnostics reported
 * "Sources received: 0". Both statements were true. The chip is drawn from an explicit
 * {@code Source: https://...} line the model is instructed to write at the end of a searched
 * answer, and Orbit's structured provenance is read from the hosted-search events themselves. When
 * the backend answered without ever emitting a {@code web_search_call} envelope Orbit could
 * recognise, the chip appeared and the structured list stayed empty, so image discovery stopped
 * before it started. Orbit visibly knew the source and could not use it.
 *
 * <p><b>Structured provenance stays primary.</b> Events reported by the search tool are a fact the
 * provider stated: these pages were consulted. Nothing here weakens that preference. The fallback
 * is consulted only when the structured list is empty, and it never merges into a non-empty one.
 *
 * <p><b>The fallback is deliberately narrow.</b> It reads exactly one thing:
 * {@link SourceLinkUtil#sourceUrl(String)}, the trailing {@code Source:} marker Orbit already
 * treats as hosted-search presentation metadata and already turns into a tappable control. It does
 * not use {@link SourceLinkUtil#firstUrl(String)}, which recognises ordinary Markdown links and
 * bare URLs in prose - those are things a model wrote in passing, not pages a search reported
 * consulting, and promoting them would turn "the model mentioned example.com" into "fetch
 * example.com and put a picture from it under this answer". The user's prompt and device-action
 * arguments are never read at all.
 *
 * <p><b>It changes availability, not trust.</b> A fallback URL passes exactly the same
 * {@link RichAnswerUrlPolicy} checks as a structured one, so loopback, private ranges, link-local
 * hosts, credentials in the URL and every non-http scheme are refused here as they were before.
 */
public final class RichAnswerProvenance {

    /** How Rich Answers came to know the page it is about to read. */
    public enum Route {
        /** Nothing usable: no structured sources and no explicit marker. */
        NONE,
        /** The hosted-search events reported the pages. Always preferred. */
        STRUCTURED_HOSTED_SEARCH,
        /** The structured list was empty and the answer carried Orbit's explicit Source marker. */
        EXPLICIT_SOURCE_MARKER
    }

    /** The pages discovery may read, and how they were obtained. */
    public static final class Resolved {
        public final Route route;
        public final List<String> urls;

        Resolved(Route route, List<String> urls) {
            this.route = route;
            this.urls = Collections.unmodifiableList(urls == null ? new ArrayList<>() : urls);
        }

        /** True when discovery has at least one page to look at. */
        public boolean hasSources() { return !urls.isEmpty(); }
    }

    private static final Resolved EMPTY = new Resolved(Route.NONE, new ArrayList<>());

    private RichAnswerProvenance() {}

    /**
     * The pages one reply may contribute to discovery.
     *
     * @param fallbackAllowed whether this answer qualifies for the explicit-marker fallback. The
     *     caller owns that decision because it is the caller that knows whether Rich Answers is on,
     *     whether the provider supports hosted web media, and whether the question was visual at
     *     all; a marker on an ordinary chat answer must not start a page fetch.
     */
    public static Resolved resolve(AssistantReply reply, boolean fallbackAllowed) {
        if (reply == null) return EMPTY;
        if (!reply.sourceUrls.isEmpty()) {
            // Already bounded, de-duplicated and policy-checked by AssistantReply itself.
            return new Resolved(Route.STRUCTURED_HOSTED_SEARCH, new ArrayList<>(reply.sourceUrls));
        }
        if (!fallbackAllowed) return EMPTY;
        String marker = SourceLinkUtil.sourceUrl(reply.text);
        if (marker.isEmpty()) return EMPTY;
        String url = marker.trim();
        if (!RichAnswerUrlPolicy.isOpenableWebUrl(url)) return EMPTY;
        List<String> single = new ArrayList<>();
        single.add(url);
        return new Resolved(Route.EXPLICIT_SOURCE_MARKER, single);
    }
}
