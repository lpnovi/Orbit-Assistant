package com.orbit.assistant;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A place a second picture might be, when the answer's own sources could only supply one.
 *
 * <p><b>Why this is a separate concept and not more provenance.</b> Beta 5's fix works by falling
 * back to the explicit {@code Source:} marker at the end of an answer, and on a real device that
 * marker is a single line, so an explicit plural request like "show me pics of a mallard duck"
 * routinely has exactly one page to read. When that page carries one usable photograph, Orbit is
 * short of what the user asked for and has nowhere else it is allowed to look. This is that
 * somewhere else, and it is deliberately built as its own type rather than as extra entries in
 * {@link AssistantReply#sourceUrls}.
 *
 * <p><b>A hint is not a citation.</b> {@code sourceUrls} means "the search reported consulting this
 * page", and Orbit's "Open source" control makes that claim to the user about the answer's facts. A
 * hint means only "a picture may be here". It never merges into the source list, never becomes a
 * citation, never appears as provenance for anything the answer said, and is used for one thing:
 * finding a photograph. Where a hint does produce a picture, that picture's own source page is the
 * page the picture actually came from, which is a true statement about the picture and nothing more.
 *
 * <p><b>Narrow by construction.</b> Only Markdown links and Markdown images in the assistant's own
 * answer are read. A bare URL sitting in prose is not a hint, because a model mentioning a domain
 * in passing is not a model pointing at a gallery. The user's prompt is never read here, and
 * neither are device-action arguments. At most {@link #MAX_HINTS} survive, every one of them still
 * has to pass {@link RichAnswerUrlPolicy} exactly as a cited page does, and the caller only asks
 * for them at all when the request was explicitly for several pictures and the trusted route has
 * already been tried and come up short.
 */
public final class RichAnswerDiscoveryHint {

    /**
     * How many hints are ever collected.
     *
     * <p>Four. This is a fallback for a picture, not a crawl: the page budget that governs cited
     * sources still applies to whatever comes out of here, and a small ceiling is what keeps a
     * chatty answer full of links from turning into a handful of extra requests.
     */
    public static final int MAX_HINTS = 4;

    /** What kind of address a hint is, which decides how it is followed. */
    public enum Kind {
        /** An ordinary web page that may contain photographs. Read as HTML, then ranked. */
        PAGE,
        /** The picture itself. Fetched, MIME-checked and decode-bounded like any other. */
        IMAGE
    }

    /** The address. Always policy-approved for its kind by the time it exists. */
    public final String url;
    public final Kind kind;

    RichAnswerDiscoveryHint(String url, Kind kind) {
        this.url = url == null ? "" : url;
        this.kind = kind == null ? Kind.PAGE : kind;
    }

    public boolean isImage() { return kind == Kind.IMAGE; }

    // ---- extraction ------------------------------------------------------------------------------

    private static final Pattern MARKDOWN_IMAGE =
            Pattern.compile("!\\[([^\\]]{0,200})\\]\\((https?://[^\\s)]{1,2000})\\)");
    private static final Pattern MARKDOWN_LINK =
            Pattern.compile("(?<!!)\\[([^\\]]{0,200})\\]\\((https?://[^\\s)]{1,2000})\\)");

    /** Extensions that make an address the picture rather than a page about one. */
    private static final String[] IMAGE_EXTENSIONS = {
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".heic", ".heif", ".avif"};

    /**
     * Words that mean a link is about pictures rather than about a topic.
     *
     * <p>Matched in the link's label and in its path, both of which are things the model wrote or
     * copied rather than things Orbit inferred. A gallery, a photo page, a Commons file page: these
     * are where a second photograph of a mallard actually lives.
     */
    private static final String[] IMAGE_SIGNALS = {
            "photo", "photos", "photograph", "photographs", "picture", "pictures",
            "image", "images", "gallery", "galleries", "album",
            ".jpg", ".jpeg", ".png", ".webp", "/wiki/file:", "/file:"};

    /**
     * The image-oriented links one answer offers, bounded, de-duplicated and policy-checked.
     *
     * @param answerText    the assistant's own answer. Never the prompt, and never an action's
     *                      arguments.
     * @param subjectTokens the local subject words from {@link RichAnswerSubject}, used to let a
     *                      link whose label names the subject count even when it says nothing about
     *                      pictures. Never persisted and never recorded in diagnostics.
     * @param alreadyTried  pages the trusted route has already read, so a hint never re-fetches one.
     */
    public static List<RichAnswerDiscoveryHint> from(String answerText, List<String> subjectTokens,
                                                     Collection<String> alreadyTried) {
        List<RichAnswerDiscoveryHint> hints = new ArrayList<>();
        if (answerText == null || answerText.trim().isEmpty()) return hints;

        Set<String> seen = new LinkedHashSet<>();
        if (alreadyTried != null) {
            for (String url : alreadyTried) {
                String identity = identityOf(url);
                if (!identity.isEmpty()) seen.add(identity);
            }
        }

        collect(MARKDOWN_IMAGE.matcher(answerText), true, subjectTokens, seen, hints);
        collect(MARKDOWN_LINK.matcher(answerText), false, subjectTokens, seen, hints);
        return hints;
    }

    private static void collect(Matcher matcher, boolean writtenAsImage, List<String> subjectTokens,
                                Set<String> seen, List<RichAnswerDiscoveryHint> hints) {
        while (matcher.find() && hints.size() < MAX_HINTS) {
            String label = matcher.group(1) == null ? "" : matcher.group(1);
            String url = matcher.group(2) == null ? "" : matcher.group(2).trim();
            RichAnswerDiscoveryHint hint = hintFor(label, url, writtenAsImage, subjectTokens);
            if (hint == null) continue;
            String identity = identityOf(hint.url);
            if (identity.isEmpty() || !seen.add(identity)) continue;
            hints.add(hint);
        }
    }

    /** One link judged, or null when it is not something Orbit will go looking at. */
    static RichAnswerDiscoveryHint hintFor(String label, String url, boolean writtenAsImage,
                                           List<String> subjectTokens) {
        if (url == null || url.trim().isEmpty()) return null;
        String trimmed = url.trim();
        boolean looksLikeImage = writtenAsImage || endsWithImageExtension(trimmed);
        if (looksLikeImage) {
            // An address written as a picture is only ever followed as a picture, so a model that
            // writes an ordinary page inside image syntax gets it fetched, MIME-checked and refused
            // rather than parsed as markup.
            return RichAnswerUrlPolicy.isFetchableImageUrl(trimmed)
                    ? new RichAnswerDiscoveryHint(trimmed, Kind.IMAGE) : null;
        }
        if (!RichAnswerUrlPolicy.isFetchablePageUrl(trimmed)) return null;
        if (hasImageSignal(label) || hasImageSignal(pathOf(trimmed))) {
            return new RichAnswerDiscoveryHint(trimmed, Kind.PAGE);
        }
        // A link whose label names what the user actually asked about is worth reading even when
        // nothing in it says "photo". Strongly, not loosely: one shared word out of six is a
        // coincidence, and this is the one rule here that is not purely about picture vocabulary.
        return namesSubject(label, subjectTokens)
                ? new RichAnswerDiscoveryHint(trimmed, Kind.PAGE) : null;
    }

    /** Whether a label matches enough of the subject to be worth following on its own. */
    static boolean namesSubject(String label, List<String> subjectTokens) {
        if (label == null || label.trim().isEmpty()) return false;
        if (subjectTokens == null || subjectTokens.isEmpty()) return false;
        int needed = Math.min(subjectTokens.size(), 2) * RichAnswerSubject.PER_TOKEN;
        return RichAnswerSubject.matchScore(subjectTokens, label) >= needed;
    }

    private static boolean hasImageSignal(String value) {
        if (value == null || value.isEmpty()) return false;
        String lower = value.toLowerCase(Locale.US);
        for (String signal : IMAGE_SIGNALS) if (lower.contains(signal)) return true;
        return false;
    }

    private static boolean endsWithImageExtension(String url) {
        String path = pathOf(url).toLowerCase(Locale.US);
        for (String extension : IMAGE_EXTENSIONS) if (path.endsWith(extension)) return true;
        return false;
    }

    /** The path of an address, without its query or fragment. Empty when it will not parse. */
    static String pathOf(String url) {
        try {
            String normalized = RichAnswerUrlPolicy.normalizedForRequest(url);
            if (normalized.isEmpty()) return "";
            String path = new java.net.URI(normalized).getRawPath();
            return path == null ? "" : path;
        } catch (Exception ignored) {
            return "";
        }
    }

    /** How two addresses are told apart for de-duplication, shared with the asset rules. */
    private static String identityOf(String url) {
        String canonical = RichAnswerAssetIdentity.canonical(url);
        if (!canonical.isEmpty()) return canonical;
        return RichAnswerUrlPolicy.normalizedForRequest(url);
    }
}
