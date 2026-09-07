package com.orbit.assistant;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The preview image a web page declares about itself, read out of its own head and nothing else.
 *
 * <p>Orbit does not go looking for pictures on a page. It reads the one the page's author already
 * chose to represent it - {@code og:image}, {@code twitter:image}, or a link-relation image - which
 * is the only image on a page that comes with a claim attached: <em>this</em> is what this page is
 * about. Scraping {@code <img>} tags would find navigation chrome, tracking pixels, advertising and
 * the author's avatar, and Orbit would have no way to tell which was which.
 *
 * <p>Pure text work, deliberately. No network, no {@code Context}, no HTML engine and no
 * JavaScript: the fetch that produced these bytes is somewhere else, and what a page declares is a
 * question that can be answered - and tested - with a string. Nothing here executes anything, and
 * the parser stops at {@code </head>} so a megabyte of body never has to be considered.
 *
 * <p>Everything that comes out is untrusted display data. A title is a caption candidate and never
 * an instruction; an image URL is a candidate and is put through {@link RichAnswerUrlPolicy} before
 * anything is fetched.
 */
public final class RichAnswerPageMetadata {

    /** The most HTML worth reading to find a head. Beyond this a page is not declaring anything. */
    public static final int MAX_HTML_CHARS = 262144;
    /** How many distinct image candidates one page may contribute. */
    public static final int MAX_CANDIDATES = 4;

    private static final Pattern META = Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK = Pattern.compile("<link\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE = Pattern.compile("<title\\b[^>]*>(.*?)</title>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))");

    /**
     * Meta names Orbit accepts as "this is the picture for this page", in preference order.
     *
     * <p>Closed on purpose. A page can declare any number of images under any number of names, and
     * the two below are the ones that actually mean the whole page rather than one article inside
     * it, one product variant, or a favicon at some size.
     */
    private static final String[] IMAGE_KEYS = {
            "og:image", "og:image:url", "og:image:secure_url", "twitter:image", "twitter:image:src"};

    /** Meta names carrying a human description of the page, used only as a caption fallback. */
    private static final String[] DESCRIPTION_KEYS = {
            "og:image:alt", "twitter:image:alt", "og:title", "twitter:title", "og:description"};

    /** What one page declared about itself. */
    public static final class Preview {
        /** Absolute, de-duplicated image candidates, best first. Never empty when usable. */
        public final List<String> imageUrls;
        /** The page's own words for itself, or empty. A caption candidate, never a fact. */
        public final String description;
        /** The page's title, or empty. */
        public final String title;

        Preview(List<String> imageUrls, String description, String title) {
            this.imageUrls = imageUrls == null
                    ? java.util.Collections.emptyList()
                    : java.util.Collections.unmodifiableList(new ArrayList<>(imageUrls));
            this.description = description == null ? "" : description;
            this.title = title == null ? "" : title;
        }

        public boolean hasImage() { return !imageUrls.isEmpty(); }

        /** The best candidate, or empty. */
        public String bestImage() { return imageUrls.isEmpty() ? "" : imageUrls.get(0); }
    }

    private RichAnswerPageMetadata() {}

    /** A page that declared nothing Orbit can use. */
    public static Preview empty() {
        return new Preview(new ArrayList<>(), "", "");
    }

    /**
     * What this page declares, with every relative image address resolved against the page itself.
     *
     * @param pageUrl the address the HTML was actually fetched from, after any redirects. Relative
     *                image URLs are resolved against it, so {@code /img/hero.jpg} becomes a real
     *                address rather than being dropped or guessed at.
     */
    public static Preview parse(String html, String pageUrl) {
        if (html == null || html.trim().isEmpty()) return empty();
        String source = html.length() > MAX_HTML_CHARS ? html.substring(0, MAX_HTML_CHARS) : html;
        String head = headOf(source);

        // Ordered by the preference list rather than by where they appear, so a page carrying both
        // og:image and twitter:image yields the one that describes the page rather than the one
        // that happened to be written first.
        Set<String> ordered = new LinkedHashSet<>();
        List<String[]> metas = attributesOf(META.matcher(head));
        for (String key : IMAGE_KEYS) {
            for (String[] meta : metas) {
                if (!key.equalsIgnoreCase(meta[0])) continue;
                String absolute = absolute(meta[1], pageUrl);
                if (!absolute.isEmpty()) ordered.add(absolute);
            }
        }
        for (String[] link : attributesOf(LINK.matcher(head))) {
            // rel="image_src" is the older declaration of the same idea and still appears.
            if (!"image_src".equalsIgnoreCase(link[0])) continue;
            String absolute = absolute(link[1], pageUrl);
            if (!absolute.isEmpty()) ordered.add(absolute);
        }

        List<String> candidates = new ArrayList<>();
        for (String candidate : ordered) {
            if (candidates.size() >= MAX_CANDIDATES) break;
            candidates.add(candidate);
        }

        String description = "";
        for (String key : DESCRIPTION_KEYS) {
            for (String[] meta : metas) {
                if (!key.equalsIgnoreCase(meta[0])) continue;
                String value = decode(meta[1]);
                if (!value.isEmpty()) { description = value; break; }
            }
            if (!description.isEmpty()) break;
        }

        String title = "";
        Matcher titleMatch = TITLE.matcher(head);
        if (titleMatch.find()) title = decode(titleMatch.group(1));

        return new Preview(candidates, description, title);
    }

    /**
     * The head of a document, or the whole of what was read when there is no closing tag.
     *
     * <p>A truncated fetch is the normal case rather than the exception: Orbit deliberately stops
     * reading after a bounded number of bytes, so the {@code </head>} may simply not have arrived.
     * Parsing what did arrive is the right answer - the declarations Orbit wants are at the top of
     * a document, which is exactly why the fetch is bounded in the first place.
     */
    static String headOf(String html) {
        String lower = html.toLowerCase(Locale.US);
        int end = lower.indexOf("</head");
        if (end < 0) end = lower.indexOf("<body");
        return end > 0 ? html.substring(0, end) : html;
    }

    /**
     * The (key, content) pairs of a run of tags, where key is {@code name}/{@code property}/
     * {@code rel} and content is {@code content}/{@code href}.
     */
    private static List<String[]> attributesOf(Matcher tags) {
        List<String[]> out = new ArrayList<>();
        while (tags.find()) {
            String tag = tags.group();
            String key = "";
            String value = "";
            Matcher attribute = ATTRIBUTE.matcher(tag);
            while (attribute.find()) {
                String name = attribute.group(1).toLowerCase(Locale.US);
                String raw = attribute.group(3) != null ? attribute.group(3)
                        : attribute.group(4) != null ? attribute.group(4) : attribute.group(5);
                if (raw == null) continue;
                switch (name) {
                    case "property":
                    case "name":
                    case "rel":
                        if (key.isEmpty()) key = raw.trim();
                        break;
                    case "content":
                    case "href":
                        if (value.isEmpty()) value = raw.trim();
                        break;
                    default:
                        break;
                }
            }
            if (!key.isEmpty() && !value.isEmpty()) out.add(new String[]{key, value});
        }
        return out;
    }

    /**
     * A declared image address made absolute and checked, or empty.
     *
     * <p>The check happens here rather than at the fetch so a page cannot get a {@code javascript:}
     * or {@code file:} candidate as far as the download queue in the first place. A protocol-
     * relative {@code //host/img.png} resolves against the page, which is what a browser does.
     */
    static String absolute(String raw, String pageUrl) {
        String value = decode(raw);
        if (value.isEmpty()) return "";
        String resolved = value;
        try {
            if (pageUrl != null && !pageUrl.trim().isEmpty()) {
                resolved = URI.create(pageUrl.trim()).resolve(value).toString();
            }
        } catch (Exception ignored) {
            resolved = value;
        }
        return RichAnswerUrlPolicy.isFetchableImageUrl(resolved) ? resolved : "";
    }

    /**
     * HTML entity decoding, limited to what actually turns up in a meta tag.
     *
     * <p>Deliberately small. This text is drawn as a caption and is never interpreted, so the job
     * is to stop the user reading {@code &amp;amp;} rather than to implement HTML.
     */
    static String decode(String raw) {
        if (raw == null) return "";
        String text = raw.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ");
        return text.replaceAll("\\s+", " ").trim();
    }
}
