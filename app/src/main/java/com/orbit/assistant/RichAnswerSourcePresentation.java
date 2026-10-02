package com.orbit.assistant;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Presentation-only de-duplication for source controls; stored citations remain untouched.
 *
 * <p>An answer can name the same page three times: an inline citation link in its text, a tappable
 * attribution under a picture, and its trailing {@code Source:} marker. Only the last one used to
 * become a standalone "Open source" pill, and when the first two already open that page the pill
 * was a duplicate floating below the answer's own actions. This decides, for one specific page,
 * whether something visible and openable already represents it.
 *
 * <p>Pages are compared as pages, not as domains: two different articles on the same site are two
 * sources, and each keeps its own control. Comparison ignores what does not change the page - the
 * scheme, a leading {@code www.}, a fragment, a trailing slash and tracking parameters such as
 * {@code utm_source}.
 */
final class RichAnswerSourcePresentation {
    private RichAnswerSourcePresentation() {}

    private static final Pattern LINK = Pattern.compile(
            "\\]\\((https?://[^\\s)]+)\\)|https?://[^\\s<>()\\]]+", Pattern.CASE_INSENSITIVE);

    static boolean isAlreadyAttributed(String sourceUrl, List<RichAnswerImage> images) {
        String wanted = pageKey(sourceUrl);
        if (wanted.isEmpty() || images == null) return false;
        for (RichAnswerImage image : images) {
            if (image == null || !image.isUsable() || !image.isWebSource()) continue;
            if (wanted.equals(pageKey(image.sourceUrl))) return true;
        }
        return false;
    }

    /** Whether the visible answer text already links to this exact page. */
    static boolean isCitedInline(String sourceUrl, String displayText) {
        String wanted = pageKey(sourceUrl);
        if (wanted.isEmpty() || displayText == null || displayText.isEmpty()) return false;
        Matcher m = LINK.matcher(displayText);
        while (m.find()) {
            String url = m.group(1) != null ? m.group(1) : m.group();
            if (wanted.equals(pageKey(url))) return true;
        }
        return false;
    }

    /**
     * Whether this answer needs its own standalone source control: only when the page it names has
     * no other visible, openable representation in the answer.
     */
    static boolean needsStandaloneSource(String sourceUrl, String displayText,
                                         List<RichAnswerImage> images) {
        if (pageKey(sourceUrl).isEmpty()) return false;
        return !isAlreadyAttributed(sourceUrl, images) && !isCitedInline(sourceUrl, displayText);
    }

    /** One page's identity for comparison, or "" for anything that is not a web page. */
    static String pageKey(String url) {
        if (url == null) return "";
        String s = url.trim();
        while (!s.isEmpty() && ".,;:!?)]}'\"".indexOf(s.charAt(s.length() - 1)) >= 0) {
            s = s.substring(0, s.length() - 1);
        }
        String lower = s.toLowerCase(Locale.US);
        if (lower.startsWith("https://")) s = s.substring(8);
        else if (lower.startsWith("http://")) s = s.substring(7);
        else return "";
        int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        String query = "";
        int q = s.indexOf('?');
        if (q >= 0) {
            query = s.substring(q + 1);
            s = s.substring(0, q);
        }
        int slash = s.indexOf('/');
        String host = (slash < 0 ? s : s.substring(0, slash)).toLowerCase(Locale.US);
        String path = slash < 0 ? "" : s.substring(slash);
        if (host.startsWith("www.")) host = host.substring(4);
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        StringBuilder kept = new StringBuilder();
        if (!query.isEmpty()) {
            for (String part : query.split("&")) {
                String name = part.contains("=") ? part.substring(0, part.indexOf('=')) : part;
                String lowerName = name.toLowerCase(Locale.US);
                if (lowerName.startsWith("utm_") || lowerName.equals("ref") || lowerName.equals("fbclid")
                        || lowerName.equals("gclid") || part.isEmpty()) continue;
                if (kept.length() > 0) kept.append('&');
                kept.append(part);
            }
        }
        if (host.isEmpty()) return "";
        return host + path + (kept.length() == 0 ? "" : "?" + kept);
    }
}
