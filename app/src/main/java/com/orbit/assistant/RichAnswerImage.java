package com.orbit.assistant;

import android.net.Uri;

import org.json.JSONObject;

import java.util.Locale;
import java.util.UUID;

/**
 * One picture Orbit is willing to show inside an answer, and everything it knows about it.
 *
 * <p>This exists because Orbit already had a way to put a picture in a response and it was the
 * wrong one. A Markdown {@code ![](https://…)} written by a model is a URL and nothing else: no
 * page it belongs to, no domain to attribute it to, no caption, no way to tell a diagram from a
 * tracking pixel, and no answer at all to "where did this come from". A response image has to be
 * able to say who it belongs to before Orbit will put it under an answer and offer to save it, so
 * a rich image is a structured record rather than a string.
 *
 * <p><b>Provenance is the field that matters.</b> {@link #WEB_SOURCE} means Orbit found this
 * picture on a page the answer actually cited, and {@link #SOURCE_URL} points at that page. Nothing
 * else may claim it. {@link #GENERATED} is reserved for a provider that genuinely returns an image
 * it produced, and a generated image must never carry a source page, because it does not have one.
 * The distinction is enforced in the constructor rather than left to each call site, so a future
 * generated-image path cannot accidentally present a made-up picture as a sourced one.
 *
 * <p>Never trusted, always bounded. Every string is clipped, the URLs are validated by
 * {@link RichAnswerUrlPolicy} on the way in and again immediately before anything is fetched or
 * opened, and the caption and alt text are display data - they are drawn, and they are never read
 * as an instruction, an action, or a link.
 */
public final class RichAnswerImage {

    // ---- provenance (storage identity: never rename) -------------------------------------------

    /** Found on a page the answer cited. {@link #sourceUrl} is that page. */
    public static final String WEB_SOURCE = "web_source";
    /**
     * Produced by the model itself.
     *
     * <p>Architecturally supported and deliberately unused in v0.7.8.5 Beta 1. Orbit's current
     * provider path does not expose a trustworthy generated-image output, and inventing one would
     * mean showing the user a picture whose origin Orbit cannot describe. When a provider does,
     * this is what it will carry - clearly labelled generated, with no source page at all.
     */
    public static final String GENERATED = "generated";

    // ---- bounds --------------------------------------------------------------------------------

    public static final int MAX_URL_CHARS = 2000;
    public static final int MAX_CAPTION_CHARS = 220;
    public static final int MAX_ALT_CHARS = 220;
    /** How many of these one answer may ever hold, whatever a provider or a store claims. */
    public static final int MAX_PER_MESSAGE = 2;

    public final String id;
    /** The picture itself. Empty is impossible: an image with no address is not one. */
    public final String imageUrl;
    /** The page this picture belongs to, for {@link #WEB_SOURCE}. Empty for a generated image. */
    public final String sourceUrl;
    /** The host of {@link #sourceUrl}, lower-cased and without {@code www.}, or empty. */
    public final String sourceDomain;
    /** A short line shown under the picture, or empty. Display data only. */
    public final String caption;
    /** What a screen reader is told, or empty. Display data only. */
    public final String altText;
    /** {@link #WEB_SOURCE} or {@link #GENERATED}. */
    public final String provenance;
    /**
     * Which block of the answer this picture is drawn after, counting content blocks from zero.
     *
     * <p>A hint rather than a coordinate. The renderer clamps it to the blocks that actually exist,
     * so an answer that was re-clipped, edited, or restored from a backup still draws its picture
     * somewhere sensible instead of losing it or throwing.
     */
    public final int blockIndex;

    public RichAnswerImage(String id, String imageUrl, String sourceUrl, String caption,
                           String altText, String provenance, int blockIndex) {
        this.id = id == null || id.trim().isEmpty() ? UUID.randomUUID().toString() : id.trim();
        this.imageUrl = bound(imageUrl, MAX_URL_CHARS);
        this.provenance = GENERATED.equals(provenance) ? GENERATED : WEB_SOURCE;
        // A generated picture has no page it came from, so it is never allowed to carry one. This
        // is the rule that keeps "Open source" from ever opening a link Orbit invented.
        String page = GENERATED.equals(this.provenance) ? "" : bound(sourceUrl, MAX_URL_CHARS);
        this.sourceUrl = page;
        this.sourceDomain = hostOf(page);
        this.caption = collapse(bound(caption, MAX_CAPTION_CHARS));
        this.altText = collapse(bound(altText, MAX_ALT_CHARS));
        this.blockIndex = Math.max(0, blockIndex);
    }

    /** A sourced web image: the only kind Beta 1 produces. */
    public static RichAnswerImage webSource(String imageUrl, String sourceUrl, String caption,
                                            String altText, int blockIndex) {
        return new RichAnswerImage("", imageUrl, sourceUrl, caption, altText, WEB_SOURCE, blockIndex);
    }

    /** The same picture, drawn after a different block. Everything else is carried over. */
    public RichAnswerImage atBlock(int index) {
        return new RichAnswerImage(id, imageUrl, sourceUrl, caption, altText, provenance, index);
    }

    public boolean isWebSource() { return WEB_SOURCE.equals(provenance); }

    public boolean isGenerated() { return GENERATED.equals(provenance); }

    /**
     * Whether this record is worth drawing at all.
     *
     * <p>Checked on the way in and on the way out, so a hand-edited store, a truncated write, or a
     * restored backup loses the damaged entry and keeps the answer rather than breaking the chat.
     * A sourced image without a real page is not a sourced image, and refusing it here is what
     * stops one being drawn with nothing to attribute it to.
     */
    public boolean isUsable() {
        if (!RichAnswerUrlPolicy.isFetchableImageUrl(imageUrl)) return false;
        if (isGenerated()) return sourceUrl.isEmpty();
        return RichAnswerUrlPolicy.isOpenableWebUrl(sourceUrl) && !sourceDomain.isEmpty();
    }

    /** The line drawn under the picture: the caption, the domain, or both. */
    public String attributionLine() {
        if (isGenerated()) return "Generated image";
        if (sourceDomain.isEmpty()) return caption;
        return caption.isEmpty() ? sourceDomain : caption + " · " + sourceDomain;
    }

    /** What a screen reader is told about the picture itself. */
    public String contentDescription() {
        if (!altText.isEmpty()) return altText;
        if (!caption.isEmpty()) return caption;
        return isGenerated() ? "Generated image" : "Image from " + sourceDomain;
    }

    /** A reasonable Vault title, derived locally with no request to anything. */
    public String vaultTitle() {
        if (!caption.isEmpty()) return caption;
        if (!altText.isEmpty()) return altText;
        return sourceDomain.isEmpty() ? "Saved image" : "Image from " + sourceDomain;
    }

    // ---- storage ---------------------------------------------------------------------------------

    JSONObject toJson() throws Exception {
        JSONObject out = new JSONObject()
                .put("id", id)
                .put("imageUrl", imageUrl)
                .put("provenance", provenance)
                .put("blockIndex", blockIndex);
        // Written only when they hold something, so the record stays small and an older build
        // reading the same document finds nothing it has to understand.
        if (!sourceUrl.isEmpty()) out.put("sourceUrl", sourceUrl);
        if (!caption.isEmpty()) out.put("caption", caption);
        if (!altText.isEmpty()) out.put("altText", altText);
        return out;
    }

    static RichAnswerImage fromJson(JSONObject o) {
        if (o == null) return null;
        RichAnswerImage image = new RichAnswerImage(
                o.optString("id", ""),
                o.optString("imageUrl", ""),
                o.optString("sourceUrl", ""),
                o.optString("caption", ""),
                o.optString("altText", ""),
                o.optString("provenance", WEB_SOURCE),
                o.optInt("blockIndex", 0));
        return image.isUsable() ? image : null;
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** The lower-cased host of an http/https address, without a leading {@code www.}. */
    static String hostOf(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        try {
            Uri parsed = Uri.parse(value.trim());
            String scheme = parsed.getScheme();
            String host = parsed.getHost();
            if (scheme == null || host == null) return "";
            String lowered = scheme.toLowerCase(Locale.US);
            if (!"http".equals(lowered) && !"https".equals(lowered)) return "";
            String lower = host.trim().toLowerCase(Locale.US);
            if (lower.isEmpty() || lower.indexOf('.') < 0) return "";
            return lower.startsWith("www.") ? lower.substring(4) : lower;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String bound(String value, int max) {
        String text = value == null ? "" : value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String collapse(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    @Override public String toString() {
        // Deliberately without the URLs: this type reaches log-shaped contexts, and where somebody
        // was reading is not something Orbit writes down.
        return "RichAnswerImage{" + provenance + "@" + blockIndex + "}";
    }
}
