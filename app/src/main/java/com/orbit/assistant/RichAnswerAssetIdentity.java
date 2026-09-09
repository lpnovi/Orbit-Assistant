package com.orbit.assistant;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether two addresses are two renditions of the same photograph, or two photographs.
 *
 * <p><b>The bug this exists to close.</b> On a real device, "Show me pics of a mallard duck" read a
 * Wikimedia file page whose candidate list was three addresses ending
 * {@code .../1920px-Mallard-Duck.jpg}, {@code .../960px-Mallard-Duck.jpg} and
 * {@code .../1280px-Mallard-Duck.jpg}. Those are one duck photographed once, offered at three
 * sizes. A resolver that counts addresses would happily present two of them side by side and call
 * that two pictures, which is worse than showing one: the user asked to see a mallard and got the
 * same mallard twice.
 *
 * <p><b>What identity means here.</b> The stable part of an address once the parts that only
 * describe a rendering are removed: the {@code /thumb/} indirection Wikimedia uses to serve a
 * resized copy, a {@code 960px-} prefix or a {@code -1024x768} suffix on a filename, a
 * {@code @2x} or {@code -scaled} marker, and the query parameters a CDN uses to ask for a width, a
 * height, a crop or a quality. Everything else is left exactly as it is.
 *
 * <p><b>Deliberately conservative.</b> Two addresses are called the same asset only when the
 * evidence is in the address itself; nothing here merges pictures because they share a host, a
 * directory or a subject. Missing a duplicate costs one repeated photograph in a two-image answer.
 * Inventing one costs a genuinely different photograph that the user never gets to see, and that is
 * the more expensive mistake, so the rules below cover the shapes the real web actually produces
 * and stop there.
 *
 * <p>Pure text. No network, no {@code Context}, no decoding, and nothing persisted. It is a naming
 * rule, not an image-analysis system.
 */
public final class RichAnswerAssetIdentity {

    /**
     * A Wikimedia thumbnail filename: the width prefix its scaler puts on a rendition.
     *
     * <p>{@code 1920px-Mallard-Duck.jpg}, and the {@code lossy-page1-} form the PDF and TIFF
     * renderers produce. Matched on the whole segment so an ordinary filename that merely contains
     * digits is never mistaken for one.
     */
    private static final Pattern RENDITION_SEGMENT =
            Pattern.compile("(?i)^(?:lossy-|lossless-)?(?:page\\d{1,4}-)?\\d{2,5}px-.+$");

    /** A leading {@code 960px-} on an ordinary filename. */
    private static final Pattern WIDTH_PREFIX = Pattern.compile("(?i)^\\d{2,5}px-");

    /** A trailing {@code -1024x768}, {@code -1024w}, {@code @2x} or {@code -scaled} on a stem. */
    private static final Pattern SIZE_SUFFIX =
            Pattern.compile("(?i)(?:[-_]\\d{2,5}x\\d{2,5}|[-_]\\d{2,5}w|@[23]x|[-_]scaled)$");

    /** Image extensions Orbit recognises when it has to decide whether a segment is a filename. */
    private static final String[] IMAGE_EXTENSIONS = {
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".heic", ".heif", ".avif", ".tif",
            ".tiff"};

    /**
     * Query parameters that ask for a rendering rather than name a picture.
     *
     * <p>Every one of these is a request for the same asset at a different size, crop, quality or
     * container. Anything not on this list is kept, because on plenty of sites the query string
     * <em>is</em> the identity and dropping it wholesale would merge unrelated images.
     */
    private static final String[] RENDERING_PARAMS = {
            "w", "width", "wid", "h", "height", "hei", "s", "size", "q", "quality", "dpr", "fit",
            "crop", "resize", "rect", "fm", "format", "auto", "sharp", "usm", "blur", "strip",
            "compress", "downsize", "scale", "zoom", "cropmode", "mode", "quality_auto", "ssl"};

    private RichAnswerAssetIdentity() {}

    /**
     * The identity of the picture at this address, or empty when there is not one.
     *
     * <p>Two addresses with the same non-empty identity are the same photograph as far as Rich
     * Answers is concerned. An address Orbit would not fetch at all has no identity, so a refused
     * URL never collides with anything.
     */
    public static String canonical(String url) {
        String normalized = RichAnswerUrlPolicy.normalizedForRequest(url);
        if (normalized.isEmpty()) return "";
        try {
            URI uri = new URI(normalized);
            String host = RichAnswerUrlPolicy.asciiHost(uri.getHost());
            if (host.isEmpty()) return "";
            if (host.startsWith("www.")) host = host.substring(4);
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
            return host + canonicalPath(path) + canonicalQuery(query);
        } catch (Exception ignored) {
            return "";
        }
    }

    /** Whether two addresses name the same photograph. Empty identities never match. */
    public static boolean sameAsset(String first, String second) {
        String a = canonical(first);
        if (a.isEmpty()) return false;
        return a.equals(canonical(second));
    }

    // ---- path ------------------------------------------------------------------------------------

    /** The path with the scaler's indirection and the filename's size markers removed. */
    static String canonicalPath(String path) {
        if (path == null || path.isEmpty()) return "/";
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/")) if (!segment.isEmpty()) segments.add(segment);
        if (segments.isEmpty()) return "/";

        collapseThumbnail(segments);

        int last = segments.size() - 1;
        segments.set(last, canonicalFilename(segments.get(last)));

        StringBuilder out = new StringBuilder();
        for (String segment : segments) out.append('/').append(segment);
        return out.length() == 0 ? "/" : out.toString();
    }

    /**
     * Removes a Wikimedia-style {@code /thumb/} rendition, leaving the original file's path.
     *
     * <p>{@code /wikipedia/commons/thumb/1/1e/Mallard-Duck.jpg/1920px-Mallard-Duck.jpg} becomes
     * {@code /wikipedia/commons/1/1e/Mallard-Duck.jpg}, which is exactly the address the same file
     * is served from at full size - so the thumbnail, the original and every other width collapse
     * onto one identity.
     *
     * <p>Both conditions are required. A {@code thumb} directory alone proves nothing, and a
     * rendition-looking last segment alone could be somebody's actual filename; together they are
     * the scaler's own URL shape and nothing else.
     */
    private static void collapseThumbnail(List<String> segments) {
        int thumb = -1;
        for (int i = 0; i < segments.size(); i++) {
            if ("thumb".equalsIgnoreCase(segments.get(i))) { thumb = i; break; }
        }
        if (thumb < 0 || segments.size() < thumb + 3) return;
        String last = segments.get(segments.size() - 1);
        String parent = segments.get(segments.size() - 2);
        if (!hasImageExtension(parent)) return;
        if (!RENDITION_SEGMENT.matcher(last).matches()) return;
        segments.remove(segments.size() - 1);
        segments.remove(thumb);
    }

    /** One filename with its width prefix, size suffix and density marker removed. */
    static String canonicalFilename(String segment) {
        if (segment == null || segment.isEmpty()) return "";
        String name = WIDTH_PREFIX.matcher(segment).replaceFirst("");
        int dot = lastImageDot(name);
        if (dot < 0) return name;
        String stem = name.substring(0, dot);
        String extension = name.substring(dot).toLowerCase(Locale.US);
        // Applied until it stops matching, because "photo-1024x768-scaled.jpg" carries two of them
        // and stripping only the outer one would leave two variants looking different.
        while (true) {
            Matcher matcher = SIZE_SUFFIX.matcher(stem);
            if (!matcher.find()) break;
            String shortened = matcher.replaceFirst("");
            if (shortened.equals(stem) || shortened.isEmpty()) break;
            stem = shortened;
        }
        return stem + extension;
    }

    private static int lastImageDot(String name) {
        String lower = name.toLowerCase(Locale.US);
        for (String extension : IMAGE_EXTENSIONS) {
            if (lower.endsWith(extension)) return name.length() - extension.length();
        }
        return -1;
    }

    /** Whether a path segment looks like the name of an image file. */
    static boolean hasImageExtension(String segment) {
        return lastImageDot(segment == null ? "" : segment) >= 0;
    }

    // ---- query -----------------------------------------------------------------------------------

    /** The query with rendering parameters dropped and the rest left in a stable order. */
    static String canonicalQuery(String query) {
        if (query == null || query.isEmpty()) return "";
        List<String> kept = new ArrayList<>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int equals = pair.indexOf('=');
            String key = (equals < 0 ? pair : pair.substring(0, equals)).toLowerCase(Locale.US);
            if (isRenderingParam(key)) continue;
            kept.add(pair);
        }
        if (kept.isEmpty()) return "";
        java.util.Collections.sort(kept);
        StringBuilder out = new StringBuilder("?");
        for (int i = 0; i < kept.size(); i++) {
            if (i > 0) out.append('&');
            out.append(kept.get(i));
        }
        return out.toString();
    }

    private static boolean isRenderingParam(String key) {
        for (String known : RENDERING_PARAMS) if (known.equals(key)) return true;
        return false;
    }
}
