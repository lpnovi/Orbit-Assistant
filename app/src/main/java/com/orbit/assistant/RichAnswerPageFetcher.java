package com.orbit.assistant;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Reads a cited web page, once, and reports both the pictures on it and exactly what happened.
 *
 * <p>Deliberately the smallest possible web client. One {@code GET}, a bounded number of redirects
 * with every hop revalidated, at most {@link #MAX_BYTES} of document, and every byte thrown away
 * afterwards. It sends no cookies, no authentication, no referrer and nothing identifying; it
 * executes no JavaScript, because it has no engine to execute one with; it loads no CSS, no
 * scripts and no iframes; and it never issues a {@code POST}.
 *
 * <p><b>What Beta 3 changed, and why.</b> Beta 1 and Beta 2 stopped reading at {@code </head>},
 * because the only question being asked was what the page declared as its own preview image. That
 * made the fetch beautifully cheap and made the feature fail on exactly the sources a careful
 * factual answer cites: university extension publications, government fact sheets, museum entries
 * and field guides declare no Open Graph image at all, and every photograph they carry is in the
 * body Orbit had decided not to read. So the read now continues into the article, up to a ceiling,
 * and the parse asks both questions of the same bytes.
 *
 * <p><b>It is not a browser and must never become one.</b> The ceiling is the design rather than a
 * safety net around an unbounded fetch: the read stops at {@link #MAX_BYTES} whether or not the
 * document has ended, and a page that has not shown a usable picture by then does not get another
 * request.
 *
 * <p>Everything it returns is untrusted. A title is a caption candidate, an image URL is a fetch
 * candidate that {@link RichAnswerUrlPolicy} still has to approve, and nothing in a page's markup -
 * alt text and captions very much included - is ever read as an instruction to Orbit.
 */
final class RichAnswerPageFetcher {

    /**
     * How much of a page is read.
     *
     * <p>512 KB of decoded markup. The number is chosen from what real article templates look like
     * rather than from a round figure: a heavyweight news or university page runs 150-350 KB of
     * HTML with its inline scripts, and the article's own photographs sit inside the first
     * two-thirds of that, so 512 KB reaches the pictures on essentially every page that has any
     * while still refusing to stream an unbounded document. It is also small enough that one
     * background page read stays comfortably under a megabyte on a cellular connection, which
     * matters because this work is the least important thing Orbit does.
     *
     * <p>Raising it further would buy almost nothing: a page that has not shown a photograph in
     * half a megabyte of markup is a page whose photographs are built by JavaScript, and no
     * increase in the ceiling reaches those.
     */
    static final int MAX_BYTES = 512 * 1024;

    /**
     * How much is read when only the head is wanted.
     *
     * <p>Kept as a separate, much smaller ceiling for the one caller that genuinely only needs
     * declarations - the Markdown loader resolving a page address into the picture it advertises.
     * That path should not pay for an article body it will not look at.
     */
    static final int MAX_HEAD_BYTES = 96 * 1024;

    static final int CONNECT_TIMEOUT_MS = 6000;
    static final int READ_TIMEOUT_MS = 8000;

    /** Content types a page's markup can arrive as. Anything else is not a page. */
    private static final String[] HTML_TYPES = {"text/html", "application/xhtml"};

    /**
     * How Orbit identifies itself, truthfully, and identically to the image loader.
     *
     * <p>Not a browser and never claiming to be one. It names the app, its version and a contact
     * address, which is what large public hosts ask of an automated client and what stops an
     * anonymous request being refused before it is even considered.
     */
    static final String USER_AGENT =
            "OrbitAssistant/" + BuildConfig.VERSION_NAME
                    + " (Android; +https://github.com/lpnovi/Orbit-Assistant)";

    /**
     * Everything one page read produced, including why it produced nothing.
     *
     * <p>The failure detail is the reason this type exists. Beta 2 returned an empty preview for a
     * refused address, a 403, a PDF, a redirect loop and a page that simply declared no image - five
     * genuinely different problems flattened into one silent outcome, which is how two releases
     * shipped without anybody being able to say what had gone wrong on the phone.
     */
    static final class PageResult {
        /** What the page declared about itself. Never null. */
        final RichAnswerPageMetadata.Preview preview;
        /** The pictures actually in the document, in document order. Never null. */
        final List<RichAnswerArticleImages.Candidate> articleImages;
        /** The address the markup finally came from, after redirects. */
        final String finalUrl;
        /** Whether markup was read at all. */
        final boolean fetched;
        final int status;
        final String contentType;
        final int bytesRead;
        final int redirects;
        /** Why there is no markup, or {@link RichAnswerTrace.Reason#NONE}. */
        final RichAnswerTrace.Reason reason;

        PageResult(RichAnswerPageMetadata.Preview preview,
                   List<RichAnswerArticleImages.Candidate> articleImages, String finalUrl,
                   boolean fetched, int status, String contentType, int bytesRead, int redirects,
                   RichAnswerTrace.Reason reason) {
            this.preview = preview == null ? RichAnswerPageMetadata.empty() : preview;
            this.articleImages = articleImages == null
                    ? Collections.emptyList() : Collections.unmodifiableList(articleImages);
            this.finalUrl = finalUrl == null ? "" : finalUrl;
            this.fetched = fetched;
            this.status = status;
            this.contentType = contentType == null ? "" : contentType;
            this.bytesRead = bytesRead;
            this.redirects = redirects;
            this.reason = reason == null ? RichAnswerTrace.Reason.NONE : reason;
        }

        static PageResult failed(RichAnswerTrace.Reason reason, String url, int status,
                                 String contentType, int redirects) {
            return new PageResult(null, null, url, false, status, contentType, 0, redirects, reason);
        }
    }

    private RichAnswerPageFetcher() {}

    /**
     * Where a cited page's markup comes from.
     *
     * <p>A seam with exactly one production implementation, installed permanently and never
     * replaced by anything in the app. It exists because the questions worth testing about
     * discovery are which pages get read, in what order, and how many - a budget question - and
     * none of that should need a socket, a real host, or a network at all to exercise. It mirrors
     * the transport seam {@link RemoteImageLoader} already uses for exactly the same reason.
     */
    interface PageSource {
        PageResult fetch(String pageUrl);
    }

    /** The real network. The only implementation the app ever installs. */
    private static final PageSource NETWORK = RichAnswerPageFetcher::fetchOverNetwork;

    private static volatile PageSource source = NETWORK;

    /** Installs a page source for one test, and returns the one it replaced. Tests only. */
    static PageSource installSourceForTest(PageSource replacement) {
        PageSource previous = source;
        source = replacement == null ? NETWORK : replacement;
        return previous;
    }

    /**
     * One cited page, read once and parsed for both kinds of picture.
     *
     * <p>Blocking. Background threads only. Never throws: a refused address, a timeout, a redirect
     * loop, a PDF where a page was expected and markup carrying no pictures are all ordinary
     * outcomes, and each one comes back saying which it was.
     */
    static PageResult fetchPage(String pageUrl) {
        return source.fetch(pageUrl);
    }

    private static PageResult fetchOverNetwork(String pageUrl) {
        String current = pageUrl == null ? "" : pageUrl.trim();
        if (!RichAnswerUrlPolicy.isFetchablePageUrl(current)) {
            return PageResult.failed(RichAnswerTrace.Reason.UNSAFE_URL, current, 0, "", 0);
        }
        for (int redirect = 0; redirect <= RichAnswerUrlPolicy.MAX_REDIRECTS; redirect++) {
            // Every hop, not just the first. What a name resolved to a moment ago is not what the
            // next hop resolves to, and one check at the top is what an SSRF chain relies on.
            if (!RichAnswerUrlPolicy.resolvesToPublicHost(current)) {
                return PageResult.failed(RichAnswerTrace.Reason.PRIVATE_HOST, current, 0, "", redirect);
            }
            HttpURLConnection connection = null;
            try {
                // Encoded exactly as the image loader encodes an address, so a page whose path
                // carries an accent is reached rather than refused with a raw-byte HTTP 400. The
                // two fetchers do different jobs with different bounds, but what goes on the wire
                // and what the policy judges must never be two different strings.
                String request = RichAnswerUrlPolicy.normalizedForRequest(current);
                if (request.isEmpty()) {
                    return PageResult.failed(RichAnswerTrace.Reason.UNSAFE_URL, current, 0, "", redirect);
                }
                connection = (HttpURLConnection) URI.create(request).toURL().openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml");
                connection.setRequestProperty("Accept-Language", "en");
                // The same truthful identification the image loader sends. Several large public
                // hosts refuse an anonymous or unrecognised client outright, and being one
                // recognisable, contactable agent everywhere is both politer and more reliable
                // than being two.
                connection.setRequestProperty("User-Agent", USER_AGENT);
                // Explicitly emptied rather than merely not set. A JVM-wide CookieHandler would
                // otherwise attach whatever this device happens to hold for the host, and a page
                // read must carry nothing identifying. Verified against real public hosts as part
                // of the Beta 2 diagnosis: it is not what any of them were refusing.
                connection.setRequestProperty("Cookie", "");
                connection.setUseCaches(false);
                connection.setDoInput(true);
                connection.setDoOutput(false);

                int status = connection.getResponseCode();
                String type = connection.getContentType();
                if (status >= 300 && status < 400) {
                    String next = RichAnswerUrlPolicy.redirectTarget(
                            current, connection.getHeaderField("Location"));
                    if (next.isEmpty()) {
                        return PageResult.failed(
                                RichAnswerTrace.Reason.REDIRECT_REJECTED, current, status, type, redirect);
                    }
                    current = next;
                    continue;
                }
                if (status < 200 || status >= 300) {
                    return PageResult.failed(
                            RichAnswerTrace.Reason.HTTP_ERROR, current, status, type, redirect);
                }
                if (!looksLikeHtml(type)) {
                    return PageResult.failed(
                            RichAnswerTrace.Reason.NOT_HTML, current, status, type, redirect);
                }
                String html = readBounded(connection.getInputStream(), MAX_BYTES);
                return parse(html, current, status, type, redirect);
            } catch (java.net.SocketTimeoutException e) {
                return PageResult.failed(RichAnswerTrace.Reason.TIMEOUT, current, 0, "", redirect);
            } catch (Exception ignored) {
                return PageResult.failed(RichAnswerTrace.Reason.NETWORK, current, 0, "", redirect);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        return PageResult.failed(RichAnswerTrace.Reason.REDIRECT_REJECTED, current, 0, "",
                RichAnswerUrlPolicy.MAX_REDIRECTS);
    }

    /**
     * Both kinds of picture read out of one document's bytes.
     *
     * <p>Split out from the fetch so the whole decision - what a page yields - can be tested
     * against real markup with no socket anywhere near it.
     */
    static PageResult parse(String html, String finalUrl, int status, String contentType,
                            int redirects) {
        RichAnswerPageMetadata.Preview preview = RichAnswerPageMetadata.parse(html, finalUrl);
        List<RichAnswerArticleImages.Candidate> article =
                RichAnswerArticleImages.parse(html, finalUrl);
        RichAnswerTrace.Reason reason = preview.hasImage() || !article.isEmpty()
                ? RichAnswerTrace.Reason.NONE : RichAnswerTrace.Reason.NO_CANDIDATES;
        return new PageResult(preview, article, finalUrl, true, status, contentType,
                html == null ? 0 : html.length(), redirects, reason);
    }

    /** Whether a declared content type is markup Orbit can read pictures out of. */
    static boolean looksLikeHtml(String contentType) {
        if (contentType == null) return false;
        String lower = contentType.toLowerCase(Locale.US);
        for (String type : HTML_TYPES) if (lower.startsWith(type)) return true;
        return false;
    }

    /**
     * At most {@code max} characters of a document.
     *
     * <p>No early stop any more. Beta 2 broke out of this loop the moment {@code </head>} appeared,
     * which is precisely why it never saw an article photograph: the interesting part of the
     * document begins one character after the point it stopped reading. The ceiling is now the only
     * thing that ends the read, and it is checked after every buffer so a chunked response cannot
     * stream past it.
     */
    static String readBounded(InputStream input, int max) throws Exception {
        StringBuilder out = new StringBuilder(Math.min(max, 64 * 1024));
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                out.append(buffer, 0, read);
                if (out.length() >= max) break;
            }
        }
        return out.length() > max ? out.substring(0, max) : out.toString();
    }
}
