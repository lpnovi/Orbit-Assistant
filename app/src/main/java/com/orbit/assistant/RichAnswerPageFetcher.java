package com.orbit.assistant;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Reads just enough of a cited web page to find the preview image it declares about itself.
 *
 * <p>Deliberately the smallest possible web client. It performs one {@code GET}, follows a bounded
 * number of redirects while revalidating each one, reads at most {@link #MAX_BYTES} bytes, stops as
 * soon as the document's head has been seen, and throws every byte away afterwards. It sends no
 * cookies, no authentication, no referrer and nothing identifying; it executes no JavaScript,
 * because it has no engine to execute one with; and it never issues a {@code POST}.
 *
 * <p><b>It is not a browser and must never become one.</b> The value of a metadata read is that it
 * is cheap and bounded: a page's {@code og:image} is in the first few kilobytes of the document, so
 * downloading a two megabyte article to find it would be paying an enormous cost for information
 * that arrived in the first packet. The byte ceiling here is not a safety net around a full fetch,
 * it is the design.
 *
 * <p>Everything it returns is untrusted. A title is a caption candidate, an image URL is a fetch
 * candidate that {@link RichAnswerUrlPolicy} still has to approve, and nothing in a page's markup
 * is ever read as an instruction to Orbit.
 */
final class RichAnswerPageFetcher {

    /** How much of a page is worth reading to find its head. */
    static final int MAX_BYTES = 96 * 1024;
    static final int CONNECT_TIMEOUT_MS = 6000;
    static final int READ_TIMEOUT_MS = 8000;

    /** Content types a page's markup can arrive as. Anything else is not a page. */
    private static final String[] HTML_TYPES = {"text/html", "application/xhtml"};

    private RichAnswerPageFetcher() {}

    /**
     * The preview one cited page declares, or an empty preview.
     *
     * <p>Blocking. Background threads only. Never throws: every failure - a refused address, a
     * timeout, a redirect loop, a PDF where a page was expected, markup that declares nothing - is
     * the same clean outcome, which is that this answer does not get a picture.
     */
    static RichAnswerPageMetadata.Preview fetchPreview(String pageUrl) {
        String current = pageUrl == null ? "" : pageUrl.trim();
        if (!RichAnswerUrlPolicy.isFetchablePageUrl(current)) return RichAnswerPageMetadata.empty();
        for (int redirect = 0; redirect <= RichAnswerUrlPolicy.MAX_REDIRECTS; redirect++) {
            // Every hop, not just the first. What a name resolved to a moment ago is not what the
            // next hop resolves to, and one check at the top is what an SSRF chain relies on.
            if (!RichAnswerUrlPolicy.resolvesToPublicHost(current)) {
                return RichAnswerPageMetadata.empty();
            }
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) URI.create(current).toURL().openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml");
                connection.setRequestProperty("Accept-Language", "en");
                connection.setRequestProperty("User-Agent", "Orbit-Assistant-Preview/1.0");
                // Explicitly emptied rather than merely not set: a platform cookie handler can
                // otherwise attach whatever this device happens to hold for the host.
                connection.setRequestProperty("Cookie", "");
                connection.setUseCaches(false);
                connection.setDoInput(true);
                connection.setDoOutput(false);

                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String next = RichAnswerUrlPolicy.redirectTarget(
                            current, connection.getHeaderField("Location"));
                    if (next.isEmpty()) return RichAnswerPageMetadata.empty();
                    current = next;
                    continue;
                }
                if (status < 200 || status >= 300) return RichAnswerPageMetadata.empty();
                if (!looksLikeHtml(connection.getContentType())) {
                    return RichAnswerPageMetadata.empty();
                }
                String html = readBounded(connection.getInputStream());
                return RichAnswerPageMetadata.parse(html, current);
            } catch (Exception ignored) {
                return RichAnswerPageMetadata.empty();
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        return RichAnswerPageMetadata.empty();
    }

    /** Whether a declared content type is markup Orbit can read declarations out of. */
    static boolean looksLikeHtml(String contentType) {
        if (contentType == null) return false;
        String lower = contentType.toLowerCase(Locale.US);
        for (String type : HTML_TYPES) if (lower.startsWith(type)) return true;
        return false;
    }

    /**
     * At most {@link #MAX_BYTES} of a document, stopping early once the head has closed.
     *
     * <p>The early stop matters as much as the ceiling: on a well-formed page the loop ends after a
     * few kilobytes, which is the difference between a fetch the user never notices and one that
     * holds a connection open reading an article Orbit is going to discard.
     */
    static String readBounded(InputStream input) throws Exception {
        StringBuilder out = new StringBuilder(8192);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                out.append(buffer, 0, read);
                if (out.length() >= MAX_BYTES) break;
                if (out.indexOf("</head") >= 0 || out.indexOf("</HEAD") >= 0) break;
            }
        }
        return out.length() > MAX_BYTES ? out.substring(0, MAX_BYTES) : out.toString();
    }
}
