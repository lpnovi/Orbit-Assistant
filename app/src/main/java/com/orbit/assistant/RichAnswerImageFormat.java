package com.orbit.assistant;

import android.os.Build;

import java.util.Locale;

/**
 * Which picture formats this phone can actually turn into a bitmap, and how sure Orbit is.
 *
 * <p>Beta 1 treated "the URL ends in an image extension" and "the server said {@code image/}" as
 * the whole question. Neither is the question. {@code minSdk} is 29 and AVIF decoding arrives in
 * Android 12, so an {@code .avif} chosen on a Galaxy running Android 10 is a candidate that was
 * always going to fail - and because Beta 1 committed to one candidate per page, choosing it lost
 * the picture even when the same page also declared a perfectly ordinary JPEG.
 *
 * <p>So format knowledge lives in one place and answers two different callers. The loader asks
 * whether a declared content type is worth reading at all; candidate selection asks which of
 * several declared images is most likely to decode <em>on this device</em>. Keeping those in one
 * class is what stops the two drifting into disagreeing about WebP.
 *
 * <p><b>None of this is a substitute for actually decoding.</b> A server can send anything under
 * any content type, so every one of these answers is a ranking hint, and the bytes still have to
 * survive {@code BitmapFactory} before anything is drawn. What this prevents is spending a fetch on
 * a format that cannot possibly work while a usable alternative was sitting in the same page.
 */
public final class RichAnswerImageFormat {

    /** Decodes on every Android version Orbit supports. */
    public static final int TIER_UNIVERSAL = 2;
    /** Decodes on this device, but not on every device Orbit runs on. */
    public static final int TIER_CONDITIONAL = 1;
    /** Cannot be decoded here at all. */
    public static final int TIER_UNSUPPORTED = 0;

    private RichAnswerImageFormat() {}

    /**
     * Whether this device can decode HEIF/HEIC.
     *
     * <p>{@code BitmapFactory} gained HEIF support in Android 10, which is Orbit's floor, but
     * whether a given device carries the decoder is a hardware question. Treated as conditional
     * rather than universal so an ordinary JPEG always wins a tie.
     */
    public static boolean supportsHeif() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    /** Whether this device can decode AVIF. Android 12 and later, and not before. */
    public static boolean supportsAvif() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    /**
     * How confidently this device can decode the format an address implies.
     *
     * <p>Read from the file extension, which is a hint and not a promise - a CDN path with no
     * extension at all is extremely common and is deliberately treated as conditional rather than
     * refused, because most of those are ordinary JPEGs.
     */
    public static int tierForUrl(String url) {
        String extension = extensionOf(url);
        if (extension.isEmpty()) return TIER_CONDITIONAL;
        return tierForExtension(extension);
    }

    /** The same question asked of a bare extension, with or without its dot. */
    public static int tierForExtension(String value) {
        String extension = value == null ? "" : value.trim().toLowerCase(Locale.US);
        if (extension.startsWith(".")) extension = extension.substring(1);
        switch (extension) {
            case "jpg":
            case "jpeg":
            case "png":
            case "webp":
                return TIER_UNIVERSAL;
            // Animated, and universally decodable as a still first frame, which is all Orbit draws.
            case "gif":
            case "bmp":
                return TIER_UNIVERSAL;
            case "heic":
            case "heif":
                return supportsHeif() ? TIER_CONDITIONAL : TIER_UNSUPPORTED;
            case "avif":
                return supportsAvif() ? TIER_CONDITIONAL : TIER_UNSUPPORTED;
            // Not a raster picture, whatever the server calls it. SVG in particular is a document
            // with a scripting model, and Orbit has no safe static renderer for one.
            case "svg":
            case "svgz":
            case "ico":
            case "pdf":
            case "xml":
                return TIER_UNSUPPORTED;
            default:
                return TIER_CONDITIONAL;
        }
    }

    /**
     * Whether a response's declared content type is worth reading as a picture.
     *
     * <p>Deliberately wider than Beta 1's {@code startsWith("image/")} in one direction and
     * narrower in another. Wider, because a content type legitimately carries parameters
     * ({@code image/jpeg; charset=binary}) and because some CDNs serve perfectly ordinary JPEGs as
     * {@code application/octet-stream}; narrower, because {@code image/svg+xml} is an XML document
     * that Orbit will not attempt and {@code text/html} is a web page rather than a picture.
     *
     * <p>Accepting a type is never a decision to draw anything. It is a decision to spend bounded
     * bytes finding out, and {@code BitmapFactory} still has the last word.
     */
    public static boolean isDecodableContentType(String contentType) {
        String type = baseType(contentType);
        if (type.isEmpty()) {
            // No declared type at all. Common enough on small static hosts to be worth reading,
            // because the bytes themselves settle it a moment later.
            return true;
        }
        if (type.equals("application/octet-stream") || type.equals("binary/octet-stream")) {
            return true;
        }
        if (!type.startsWith("image/")) return false;
        String subtype = type.substring("image/".length());
        if (subtype.startsWith("svg")) return false;
        if (subtype.equals("x-icon") || subtype.equals("vnd.microsoft.icon")) return false;
        if (subtype.equals("heic") || subtype.equals("heif")) return supportsHeif();
        if (subtype.equals("avif")) return supportsAvif();
        return true;
    }

    /** Whether a response is a web page rather than a picture. */
    public static boolean isHtmlContentType(String contentType) {
        String type = baseType(contentType);
        return type.equals("text/html") || type.startsWith("application/xhtml");
    }

    /**
     * The {@code Accept} header Orbit sends for a picture.
     *
     * <p>Built from what this device can genuinely decode, so a server doing content negotiation is
     * told the truth rather than being offered a format Orbit would then fail to draw. The trailing
     * low-quality wildcard is what keeps hosts that ignore negotiation entirely from refusing.
     */
    public static String acceptHeader() {
        StringBuilder accept = new StringBuilder();
        if (supportsAvif()) accept.append("image/avif,");
        if (supportsHeif()) accept.append("image/heic,image/heif,");
        accept.append("image/webp,image/apng,image/jpeg,image/png,image/gif,image/*;q=0.8,*/*;q=0.5");
        return accept.toString();
    }

    /** The lower-cased media type with its parameters removed, or empty. */
    static String baseType(String contentType) {
        if (contentType == null) return "";
        String value = contentType.trim().toLowerCase(Locale.US);
        int semicolon = value.indexOf(';');
        if (semicolon >= 0) value = value.substring(0, semicolon);
        return value.trim();
    }

    /** The file extension an address implies, without its dot, or empty. */
    static String extensionOf(String url) {
        if (url == null) return "";
        String value = url;
        int hash = value.indexOf('#');
        if (hash >= 0) value = value.substring(0, hash);
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        int slash = value.lastIndexOf('/');
        String name = slash >= 0 ? value.substring(slash + 1) : value;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        String extension = name.substring(dot + 1).toLowerCase(Locale.US);
        // A dot in a filename is not always an extension. Anything longer than this is a version
        // number, a date, or part of the name itself.
        return extension.length() <= 5 ? extension : "";
    }
}
