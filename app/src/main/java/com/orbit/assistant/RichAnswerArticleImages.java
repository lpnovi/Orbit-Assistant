package com.orbit.assistant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The photographs that are actually inside an article, as opposed to the one the page advertises.
 *
 * <p><b>Why this exists.</b> Beta 1 and Beta 2 asked every cited page a single question - what does
 * your {@code <head>} declare as your preview image - and treated "nothing" as "this page has no
 * picture". On the real web that is wrong often enough to be the whole bug. A university extension
 * publication, a government fact sheet, a field guide, a museum catalogue entry: these are exactly
 * the trustworthy sources a factual answer cites, they are exactly the pages that carry the
 * photograph the user wanted, and they are written on templates that predate Open Graph and declare
 * no preview image at all. Orbit was reading the one part of those pages that had nothing in it.
 *
 * <p>So this reads the other part. Given the bounded HTML that was already fetched, it finds the
 * {@code <img>}, {@code <picture>} and lazy-loaded images in the document, resolves their addresses
 * against the page, and reports where each one sits in the document's structure and what text the
 * page put next to it.
 *
 * <p><b>It is a parser, not a browser.</b> Pure text in, value objects out. No network, no
 * {@code Context}, no DOM, no JavaScript, no CSS, no iframes - nothing here executes anything, and
 * the class has no way to. Scripts, styles and comments are blanked out before the scan precisely so
 * an address written inside a script is never mistaken for a picture on the page.
 *
 * <p><b>Everything it returns is untrusted display data.</b> Alt text and captions are ranking
 * signals and caption candidates. They are never read as instructions to Orbit, and an address is
 * only ever a candidate that {@link RichAnswerUrlPolicy} still has to approve before a byte is
 * fetched.
 */
public final class RichAnswerArticleImages {

    /**
     * How many candidates one page may contribute to ranking.
     *
     * <p>A long article can carry fifty images. Ranking is cheap and downloading is not, so the
     * parse is bounded here and the download budget is bounded separately and much lower: this
     * number is "how many did we get to look at", not "how many will be fetched".
     */
    public static final int MAX_CANDIDATES = 24;

    /**
     * The widest {@code srcset} entry worth preferring.
     *
     * <p>Publishers routinely offer a 3840-wide original beside a perfectly good 1200. Orbit draws
     * into a phone-width card and samples anything larger down anyway, so choosing the biggest
     * asset on offer would be paying megabytes of somebody's bandwidth for pixels that are thrown
     * away. The largest entry at or below this wins; if every entry is above it, the smallest of
     * those is taken rather than giving up on the image.
     */
    public static final int MAX_SRCSET_WIDTH = 2400;

    /** Where a candidate came from. Recorded for diagnostics and used in ranking. */
    public enum Origin {
        /** A structured image handed back by the hosted search backend itself. */
        HOSTED_SEARCH,
        /** {@code <meta property="og:image">}. */
        OG_IMAGE,
        /** {@code <meta name="twitter:image">}. */
        TWITTER_IMAGE,
        /** {@code <link rel="image_src">}. */
        IMAGE_SRC,
        /** An ordinary {@code <img src>} in the document. */
        ARTICLE_IMG,
        /** A {@code <source>} inside a {@code <picture>}. */
        PICTURE_SOURCE,
        /** Chosen out of a {@code srcset} on an {@code <img>}. */
        SRCSET,
        /** A {@code data-src}-style address a lazy loader would have swapped in. */
        LAZY_IMAGE;

        /** Whether this origin is something the page declared about itself rather than content. */
        public boolean isPreview() {
            return this == OG_IMAGE || this == TWITTER_IMAGE || this == IMAGE_SRC;
        }
    }

    /** Structural placement, as a single signed signal rather than a tree. */
    public static final int STRUCTURE_NEUTRAL = 0;
    /** Inside {@code <article>}, {@code <main>} or {@code <figure>}. */
    public static final int STRUCTURE_CONTENT = 1;
    /** Inside {@code <header>}, {@code <nav>}, {@code <footer>} or {@code <aside>}. */
    public static final int STRUCTURE_CHROME = -1;

    /** One image address a page offers, with the context the page put around it. */
    public static final class Candidate {
        /** Absolute, policy-approved address. Never empty on a candidate that was emitted. */
        public final String url;
        public final Origin origin;
        /** {@code alt}, or {@code title} when there is no alt. Untrusted text. */
        public final String alt;
        /** The enclosing {@code <figcaption>}, or empty. Untrusted text. */
        public final String caption;
        /** Declared width, or 0 when the page did not say. A claim, never a fact. */
        public final int declaredWidth;
        /** Declared height, or 0. */
        public final int declaredHeight;
        /** One of {@link #STRUCTURE_CONTENT}, {@link #STRUCTURE_NEUTRAL}, {@link #STRUCTURE_CHROME}. */
        public final int structure;

        public Candidate(String url, Origin origin, String alt, String caption,
                         int declaredWidth, int declaredHeight, int structure) {
            this.url = url == null ? "" : url;
            this.origin = origin == null ? Origin.ARTICLE_IMG : origin;
            this.alt = alt == null ? "" : alt;
            this.caption = caption == null ? "" : caption;
            this.declaredWidth = Math.max(0, declaredWidth);
            this.declaredHeight = Math.max(0, declaredHeight);
            this.structure = structure;
        }

        /** Every piece of text the page attached to this image, for ranking and captioning. */
        public String describedBy() {
            if (caption.isEmpty()) return alt;
            if (alt.isEmpty()) return caption;
            return caption + " " + alt;
        }
    }

    private static final Pattern IMG = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SOURCE =
            Pattern.compile("<source\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern FIGCAPTION = Pattern.compile(
            "<figcaption\\b[^>]*>(.*?)</figcaption>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAGS = Pattern.compile("<[^>]*>");
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))");

    /**
     * Address-bearing attributes, in the order they are trusted.
     *
     * <p>{@code src} first because it is the real one. The {@code data-} names after it are what
     * every lazy-loading library on the web puts the true address in while {@code src} holds a
     * grey placeholder - so a page that looks image-free to a naive reader is often a page whose
     * pictures are one attribute along.
     */
    private static final String[] URL_ATTRIBUTES = {
            "src", "data-src", "data-lazy-src", "data-original", "data-original-src",
            "data-hi-res-src", "data-full-src", "data-image", "data-lazy"};

    /** {@code srcset}-shaped attributes, same idea. */
    private static final String[] SRCSET_ATTRIBUTES = {"srcset", "data-srcset", "data-lazy-srcset"};

    /** Containers whose contents are the page's actual subject matter. */
    private static final String[] CONTENT_TAGS = {"article", "main", "figure"};

    /** Containers whose contents are the furniture around the subject matter. */
    private static final String[] CHROME_TAGS = {"header", "nav", "footer", "aside"};

    private RichAnswerArticleImages() {}

    /**
     * Every usable image address in this document, in document order.
     *
     * <p>Never throws and never returns null. Malformed markup - an unterminated attribute, a tag
     * that never closes, an entity that is not one - produces fewer candidates rather than an
     * error, because the alternative to a lenient parser here is no picture at all.
     *
     * @param pageUrl the address the HTML was fetched from after redirects. Relative and
     *                protocol-relative addresses resolve against it.
     */
    public static List<Candidate> parse(String html, String pageUrl) {
        List<Candidate> out = new ArrayList<>();
        if (html == null || html.isEmpty()) return out;

        // Blanked rather than removed, so every offset below still points at the same character it
        // pointed at in the original document and the structural spans stay honest.
        String source = blankOutNonMarkup(html);

        int[][] contentSpans = spansOf(source, CONTENT_TAGS, false);
        int[][] chromeSpans = spansOf(source, CHROME_TAGS, true);
        int[][] figureSpans = spansOf(source, new String[]{"figure"}, false);
        String[] figureCaptions = captionsOf(source, figureSpans);

        // Keyed by address so the same picture offered as src, as a srcset entry and as a
        // data-src placeholder-replacement counts once - and keeps the first, best-described
        // occurrence rather than the last.
        Map<String, Candidate> unique = new LinkedHashMap<>();

        collect(IMG.matcher(source), source, pageUrl, false,
                contentSpans, chromeSpans, figureSpans, figureCaptions, unique);
        collect(SOURCE.matcher(source), source, pageUrl, true,
                contentSpans, chromeSpans, figureSpans, figureCaptions, unique);

        for (Candidate candidate : unique.values()) {
            if (out.size() >= MAX_CANDIDATES) break;
            out.add(candidate);
        }
        return out;
    }

    // ---- scanning --------------------------------------------------------------------------------

    private static void collect(Matcher tags, String source, String pageUrl, boolean isPictureSource,
                                int[][] contentSpans, int[][] chromeSpans,
                                int[][] figureSpans, String[] figureCaptions,
                                Map<String, Candidate> unique) {
        while (tags.find()) {
            if (unique.size() >= MAX_CANDIDATES) return;
            int at = tags.start();
            Map<String, String> attributes = attributesOf(tags.group());
            if (attributes.isEmpty()) continue;

            String alt = firstOf(attributes, "alt", "title", "data-alt", "aria-label");
            int width = number(attributes.get("width"));
            int height = number(attributes.get("height"));
            String caption = captionAt(at, figureSpans, figureCaptions);
            int structure = structureAt(at, contentSpans, chromeSpans, figureSpans);

            // srcset first: when a page offers both, the set is the one with real choices in it and
            // the bare src is usually the smallest fallback.
            for (String name : SRCSET_ATTRIBUTES) {
                String raw = attributes.get(name);
                if (raw == null || raw.trim().isEmpty()) continue;
                Chosen chosen = bestFromSrcset(raw, pageUrl);
                if (chosen == null) continue;
                Origin origin = isPictureSource ? Origin.PICTURE_SOURCE
                        : name.startsWith("data-") ? Origin.LAZY_IMAGE : Origin.SRCSET;
                add(unique, new Candidate(chosen.url, origin, alt, caption,
                        chosen.width > 0 ? chosen.width : width, height, structure));
                break;
            }

            for (String name : URL_ATTRIBUTES) {
                String raw = attributes.get(name);
                if (raw == null || raw.trim().isEmpty()) continue;
                String absolute = RichAnswerPageMetadata.absolute(raw, pageUrl);
                if (absolute.isEmpty()) continue;
                Origin origin = isPictureSource ? Origin.PICTURE_SOURCE
                        : "src".equals(name) ? Origin.ARTICLE_IMG : Origin.LAZY_IMAGE;
                add(unique, new Candidate(absolute, origin, alt, caption, width, height, structure));
            }
        }
    }

    /** Keeps the first description of an address rather than letting a later bare copy erase it. */
    private static void add(Map<String, Candidate> unique, Candidate candidate) {
        if (candidate.url.isEmpty() || unique.size() >= MAX_CANDIDATES) return;
        Candidate existing = unique.get(candidate.url);
        if (existing == null) {
            unique.put(candidate.url, candidate);
            return;
        }
        // The same picture met twice: keep whichever occurrence the page described, and prefer a
        // content placement over a chrome one, since a logo in the footer is often also the hero.
        if (existing.describedBy().isEmpty() && !candidate.describedBy().isEmpty()) {
            unique.put(candidate.url, candidate);
        } else if (existing.structure < candidate.structure) {
            unique.put(candidate.url, candidate);
        }
    }

    // ---- srcset ----------------------------------------------------------------------------------

    /** One entry chosen out of a {@code srcset}, with the width the page claimed for it. */
    static final class Chosen {
        final String url;
        final int width;
        Chosen(String url, int width) { this.url = url; this.width = width; }
    }

    /**
     * The most useful entry in a {@code srcset}, resolved and policy-checked, or null.
     *
     * <p>"Most useful" is the largest entry that is still a sensible size for a phone card. Taking
     * the first entry - which is what a naive split does - reliably picks the 320-wide thumbnail a
     * responsive page lists first, and that is exactly the picture Orbit then rejects for being too
     * small. Taking the last picks a 4K original. So: the widest at or below
     * {@link #MAX_SRCSET_WIDTH}, and if every entry is bigger than that, the smallest of them.
     */
    static Chosen bestFromSrcset(String srcset, String pageUrl) {
        List<String[]> entries = srcsetEntries(srcset);
        if (entries.isEmpty()) return null;

        String bestUnderCap = "";
        int bestUnderCapWidth = -1;
        String smallestOverCap = "";
        int smallestOverCapWidth = Integer.MAX_VALUE;
        String bestDensity = "";
        int bestDensityScore = -1;
        String first = "";

        for (String[] entry : entries) {
            String absolute = RichAnswerPageMetadata.absolute(entry[0], pageUrl);
            if (absolute.isEmpty()) continue;
            if (first.isEmpty()) first = absolute;
            String descriptor = entry[1].toLowerCase(Locale.US);
            if (descriptor.endsWith("w")) {
                int width = number(descriptor.substring(0, descriptor.length() - 1));
                if (width <= 0) continue;
                if (width <= MAX_SRCSET_WIDTH) {
                    if (width > bestUnderCapWidth) {
                        bestUnderCapWidth = width;
                        bestUnderCap = absolute;
                    }
                } else if (width < smallestOverCapWidth) {
                    smallestOverCapWidth = width;
                    smallestOverCap = absolute;
                }
            } else if (descriptor.endsWith("x")) {
                // Density descriptors carry no pixel count, so they are only ever compared with
                // each other and never override a real width.
                int density = (int) (parseDensity(descriptor) * 1000);
                if (density > bestDensityScore) {
                    bestDensityScore = density;
                    bestDensity = absolute;
                }
            }
        }

        if (!bestUnderCap.isEmpty()) return new Chosen(bestUnderCap, bestUnderCapWidth);
        if (!smallestOverCap.isEmpty()) return new Chosen(smallestOverCap, smallestOverCapWidth);
        if (!bestDensity.isEmpty()) return new Chosen(bestDensity, 0);
        return first.isEmpty() ? null : new Chosen(first, 0);
    }

    /**
     * A {@code srcset} split into (address, descriptor) pairs.
     *
     * <p>Hand-written rather than a split on commas, because a comma is legal inside an address -
     * image CDNs put whole transform lists in the path - and splitting on it truncates every
     * Cloudinary URL on the web. An address cannot contain a space, so the space is what actually
     * terminates it, and the comma is only read once the descriptor is done.
     */
    static List<String[]> srcsetEntries(String srcset) {
        List<String[]> out = new ArrayList<>();
        if (srcset == null) return out;
        String text = RichAnswerPageMetadata.decode(srcset);
        int i = 0;
        int length = text.length();
        while (i < length && out.size() < 32) {
            while (i < length && (Character.isWhitespace(text.charAt(i)) || text.charAt(i) == ',')) i++;
            if (i >= length) break;
            int start = i;
            while (i < length && !Character.isWhitespace(text.charAt(i))) i++;
            String url = text.substring(start, i);
            // A trailing comma means this entry carried no descriptor and the next one starts here.
            while (url.endsWith(",")) url = url.substring(0, url.length() - 1);
            if (url.isEmpty()) continue;
            String descriptor = "";
            if (i < length) {
                while (i < length && Character.isWhitespace(text.charAt(i))) i++;
                int descriptorStart = i;
                while (i < length && text.charAt(i) != ',') i++;
                descriptor = text.substring(descriptorStart, i).trim();
                if (i < length) i++;
            }
            out.add(new String[]{url, descriptor});
        }
        return out;
    }

    private static float parseDensity(String descriptor) {
        try {
            return Float.parseFloat(descriptor.substring(0, descriptor.length() - 1));
        } catch (Exception ignored) {
            return 0f;
        }
    }

    // ---- structure -------------------------------------------------------------------------------

    /**
     * The structural verdict for an image at this offset.
     *
     * <p>Content beats chrome when both apply, because a {@code <figure>} inside an {@code <aside>}
     * is still a figure - somebody deliberately captioned that picture - whereas the aside is a
     * statement about layout. A picture in neither is neutral, which is the common case on a page
     * whose markup predates the sectioning elements entirely.
     */
    static int structureAt(int offset, int[][] contentSpans, int[][] chromeSpans,
                           int[][] figureSpans) {
        if (within(offset, figureSpans) || within(offset, contentSpans)) return STRUCTURE_CONTENT;
        if (within(offset, chromeSpans)) return STRUCTURE_CHROME;
        return STRUCTURE_NEUTRAL;
    }

    private static boolean within(int offset, int[][] spans) {
        if (spans == null) return false;
        for (int[] span : spans) if (offset >= span[0] && offset < span[1]) return true;
        return false;
    }

    private static String captionAt(int offset, int[][] figureSpans, String[] captions) {
        if (figureSpans == null || captions == null) return "";
        for (int i = 0; i < figureSpans.length && i < captions.length; i++) {
            if (offset >= figureSpans[i][0] && offset < figureSpans[i][1]) return captions[i];
        }
        return "";
    }

    /**
     * The outermost spans of a set of container tags.
     *
     * <p>Depth-counted per tag name, so a nested {@code <figure>} inside a {@code <figure>} yields
     * one span rather than two overlapping ones.
     *
     * @param dropUnclosed what to do with an opening tag that never closes. For chrome tags this is
     *                     true and the span is discarded: a single unterminated {@code <nav>} near
     *                     the top of a sloppy page would otherwise mark the entire rest of the
     *                     document as navigation and reject every photograph on it. For content
     *                     tags it is false and the span runs to the end, which is both the harmless
     *                     direction and usually what the author meant.
     */
    static int[][] spansOf(String html, String[] tagNames, boolean dropUnclosed) {
        List<int[]> spans = new ArrayList<>();
        for (String tag : tagNames) {
            Matcher matcher = Pattern.compile("<(/?)" + tag + "\\b[^>]*>", Pattern.CASE_INSENSITIVE)
                    .matcher(html);
            int depth = 0;
            int start = -1;
            while (matcher.find()) {
                boolean closing = "/".equals(matcher.group(1));
                if (!closing) {
                    if (depth == 0) start = matcher.start();
                    depth++;
                } else if (depth > 0) {
                    depth--;
                    if (depth == 0 && start >= 0) {
                        spans.add(new int[]{start, matcher.end()});
                        start = -1;
                    }
                }
            }
            if (depth > 0 && start >= 0 && !dropUnclosed) {
                spans.add(new int[]{start, html.length()});
            }
        }
        return spans.toArray(new int[0][]);
    }

    /** The caption text of each figure span, in the same order. */
    private static String[] captionsOf(String html, int[][] figureSpans) {
        String[] out = new String[figureSpans.length];
        for (int i = 0; i < figureSpans.length; i++) {
            out[i] = "";
            String figure = html.substring(figureSpans[i][0],
                    Math.min(html.length(), figureSpans[i][1]));
            Matcher caption = FIGCAPTION.matcher(figure);
            if (caption.find()) out[i] = text(caption.group(1));
        }
        return out;
    }

    // ---- text ------------------------------------------------------------------------------------

    /** Markup reduced to the words inside it, entity-decoded and collapsed. Never interpreted. */
    static String text(String markup) {
        if (markup == null) return "";
        String stripped = TAGS.matcher(markup).replaceAll(" ");
        String decoded = RichAnswerPageMetadata.decode(stripped);
        return decoded.length() > 300 ? decoded.substring(0, 300).trim() : decoded;
    }

    /**
     * Script, style and comment content replaced by spaces of the same length.
     *
     * <p>Length-preserving on purpose: every offset computed afterwards still refers to the same
     * place in the real document, so the structural spans do not have to be recomputed against a
     * shortened copy. The point is that an image address sitting in a JSON blob inside a
     * {@code <script>} is not a picture on this page and must never be scanned as one.
     */
    static String blankOutNonMarkup(String html) {
        StringBuilder out = new StringBuilder(html);
        // Comments first, and the lowercased copy is rebuilt between passes. Otherwise a commented
        // out {@code <script} - which has no closing tag because the whole thing is a comment -
        // would send the script pass looking for a terminator to the end of the document and blank
        // every real picture below it.
        blank(out, out.toString().toLowerCase(Locale.US), "<!--", "-->");
        blank(out, out.toString().toLowerCase(Locale.US), "<script", "</script");
        blank(out, out.toString().toLowerCase(Locale.US), "<style", "</style");
        return out.toString();
    }

    /**
     * Blanks every {@code open}..{@code close} region, in one forward pass.
     *
     * <p>Written with {@code indexOf} rather than a lazy regex on purpose. {@code <script\b[^>]*>
     * .*?</script>} is quadratic on a document with many unterminated {@code <script} tokens - the
     * engine restarts a full scan to the end of the document at every one of them - and a page
     * Orbit reads is a page a stranger wrote. This is linear in the length of the document no
     * matter what is in it.
     *
     * <p>An unterminated region blanks to the end of what was read, which is the safe direction:
     * markup inside a script that never closes is still not markup.
     */
    private static void blank(StringBuilder text, String lower, String open, String close) {
        int from = 0;
        while (true) {
            int start = lower.indexOf(open, from);
            if (start < 0) return;
            int end = lower.indexOf(close, start + open.length());
            int stop = end < 0 ? text.length() : Math.min(text.length(), end + close.length());
            for (int i = start; i < stop; i++) {
                if (text.charAt(i) != '\n') text.setCharAt(i, ' ');
            }
            if (end < 0) return;
            from = stop;
        }
    }

    /** The (lowercased) attributes of one tag. Malformed pairs are simply not there. */
    static Map<String, String> attributesOf(String tag) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher attribute = ATTRIBUTE.matcher(tag);
        while (attribute.find()) {
            String name = attribute.group(1).toLowerCase(Locale.US);
            String raw = attribute.group(3) != null ? attribute.group(3)
                    : attribute.group(4) != null ? attribute.group(4) : attribute.group(5);
            if (raw == null || out.containsKey(name)) continue;
            out.put(name, raw.trim());
        }
        return out;
    }

    private static String firstOf(Map<String, String> attributes, String... names) {
        for (String name : names) {
            String value = attributes.get(name);
            if (value == null) continue;
            String decoded = RichAnswerPageMetadata.decode(value);
            if (!decoded.isEmpty()) return decoded.length() > 220 ? decoded.substring(0, 220) : decoded;
        }
        return "";
    }

    /** A declared dimension, or 0. A page writing {@code width="100%"} has declared nothing. */
    static int number(String value) {
        if (value == null) return 0;
        String digits = value.trim();
        int end = 0;
        while (end < digits.length() && Character.isDigit(digits.charAt(end))) end++;
        if (end == 0) return 0;
        // A trailing unit is fine ("640px"); a trailing percent is a share of something unknown.
        if (end < digits.length() && digits.charAt(end) == '%') return 0;
        try {
            long parsed = Long.parseLong(digits.substring(0, end));
            return parsed > 100000L ? 0 : (int) parsed;
        } catch (Exception ignored) {
            return 0;
        }
    }
}
