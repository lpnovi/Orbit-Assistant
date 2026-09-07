package com.orbit.assistant;

import java.util.Locale;

/**
 * Whether a picture would actually make this answer better, decided on this device with no AI.
 *
 * <p>The failure mode Rich Answers has to avoid is not "no image". It is an image on every web
 * answer: a publisher's generic hero graphic under a currency conversion, a stock photo of a laptop
 * under an explanation of hash maps, a social-sharing card that is mostly the site's own logo. Each
 * one costs the user height, bandwidth and attention and gives back nothing, and after three of
 * them the pictures stop being read at all. <b>No image is better than a bad image</b>, so the
 * default here is no.
 *
 * <p>Two gates, asked in order. The first is about the <em>question</em>: does what the user asked
 * have a visual answer - what does this bird look like, where is this place, which of these two
 * cars. The second is about the <em>candidate</em>: is this particular picture something worth
 * drawing, or is it a logo, an icon, a tracking pixel or a share card. Both are deterministic, both
 * are pure text, and neither costs a second request to a model - which is the point. Ranking images
 * with another AI call would double the cost of every web answer to decide something a list of
 * words decides well enough.
 *
 * <p>Wrong in the safe direction by construction. A visual question with no good candidate simply
 * gets the text answer it would have had anyway.
 */
public final class RichAnswerRelevance {

    /**
     * Words that mean the user is asking about how something looks, or about a thing that is
     * inherently seen rather than explained.
     *
     * <p>Identification, place, object and comparison. These are the cases where a photograph is
     * genuinely part of the answer instead of an illustration attached to one.
     */
    private static final String[] VISUAL_SUBJECTS = {
            "what does", "what do", "look like", "looks like", "picture of", "photo of",
            "image of", "show me", "what is this", "what's this", "identify", "identification",
            "which bird", "which plant", "which tree", "which flower", "which insect",
            "species", "breed", "bird", "flower", "plant", "tree", "mushroom", "insect",
            "spider", "snake", "butterfly", "fish", "animal", "dog", "cat",
            "landmark", "monument", "cathedral", "castle", "museum", "skyline",
            "where is", "what city", "what country", "travel", "destination", "hotel",
            "car", "vehicle", "motorcycle", "aircraft", "airplane", "train", "ship",
            "painting", "artwork", "sculpture", "artist", "architecture", "design of",
            "phone", "laptop", "watch", "camera", "console", "headphones", "sneaker",
            "anatomy", "diagram of", "map of", "flag of", "logo of", "uniform",
            "difference between", "compare", "versus", " vs ", "which one",
            "recipe", "dish", "cuisine", "outfit", "hairstyle", "tattoo", "plant care"};

    /**
     * Words that mean a picture would add nothing, whatever else the question contains.
     *
     * <p>Checked after the visual signal and allowed to overrule it, because "what does a closure
     * look like in JavaScript" is a code question wearing a visual question's words.
     */
    private static final String[] NON_VISUAL_SUBJECTS = {
            "define", "definition of", "meaning of", "what does it mean",
            "calculate", "convert", "how much is", "percent of", "square root",
            "javascript", "typescript", "python", "java ", "kotlin", "sql", "regex",
            "function", "variable", "compile", "algorithm", "big o", "runtime",
            "api", "endpoint", "database", "docker", "kubernetes", "git ",
            "write a", "draft", "rewrite", "rephrase", "summarize", "summary of",
            "translate", "spell", "grammar", "email to", "message to",
            "should i", "advice", "opinion", "explain the concept", "philosophy",
            "how do i feel", "motivate", "poem", "joke", "story about"};

    /** Domains whose declared preview image is nearly always branding rather than content. */
    private static final String[] BRANDED_PREVIEW_HOSTS = {
            "twitter.com", "x.com", "facebook.com", "instagram.com", "linkedin.com",
            "reddit.com", "tiktok.com", "pinterest.com", "youtube.com", "threads.net"};

    /** Filename and path fragments that name chrome rather than content. */
    private static final String[] CHROME_MARKERS = {
            "logo", "favicon", "sprite", "icon-", "-icon", "/icons/", "avatar", "placeholder",
            "default-", "-default", "share-image", "share_image", "og-default", "og_default",
            "twitter-card", "social-card", "social_card", "banner-ad", "/ads/", "/ad/",
            "pixel", "tracking", "beacon", "spacer", "blank", "1x1", "transparent"};

    /** Extensions Orbit will draw. Static raster first, and nothing that executes. */
    private static final String[] ALLOWED_EXTENSIONS = {
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".heic", ".heif", ".avif"};

    /** Extensions that are never a photograph of anything. */
    private static final String[] REFUSED_EXTENSIONS = {".svg", ".svgz", ".ico", ".pdf", ".xml"};

    private RichAnswerRelevance() {}

    /**
     * Whether a picture would materially improve the answer to this question.
     *
     * <p>Asked of the prompt and the answer together, because either alone gets it wrong. "What is
     * this?" says nothing on its own; an answer about a bird's plumage and range says a great deal.
     */
    public static boolean answerWantsImage(String prompt, String answerText) {
        String question = normalize(prompt);
        if (question.isEmpty()) return false;
        // A question about a picture the user already attached is answered by the picture they are
        // holding. Adding a stranger's photograph of a different bird would be actively confusing.
        String answer = normalize(answerText);
        if (containsAny(question, NON_VISUAL_SUBJECTS)) return false;
        if (containsAny(question, VISUAL_SUBJECTS)) return true;
        // The answer may reveal a visual subject the question did not name - "tell me about the
        // Hagia Sophia" is not phrased visually and is obviously a place.
        return containsAny(answer, VISUAL_SUBJECTS) && question.length() <= 160;
    }

    /**
     * How much this candidate is worth drawing, or a negative score for one that is not.
     *
     * <p>A score rather than a yes/no because several pages are usually cited and Orbit shows one
     * picture: the question is not "is this acceptable" but "is this the best of them". Belonging
     * to a page the answer actually used is worth more than anything else, because that is the
     * relationship the caption claims.
     *
     * @param cited whether {@code sourceUrl} is a page this answer genuinely cited.
     */
    public static int score(String imageUrl, String sourceUrl, String caption, boolean cited) {
        if (!RichAnswerUrlPolicy.isFetchableImageUrl(imageUrl)) return -1;
        String lower = imageUrl.toLowerCase(Locale.US);
        if (endsWithAny(lower, REFUSED_EXTENSIONS)) return -1;
        if (containsAny(lower, CHROME_MARKERS)) return -1;
        // A format this device cannot decode is not a candidate at all. Beta 1 scored an AVIF on
        // its path and its host like anything else, picked it because those looked good, and then
        // lost the picture entirely on an Android version where AVIF does not decode - while an
        // ordinary JPEG sat in the same page. Asked of the device rather than assumed.
        int tier = RichAnswerImageFormat.tierForUrl(imageUrl);
        if (tier == RichAnswerImageFormat.TIER_UNSUPPORTED) return -1;

        int score = cited ? 40 : 0;
        // A recognisable static raster extension is a strong signal that this is a photograph
        // rather than an interface asset; a URL with no extension at all is common for CDNs and is
        // neither rewarded nor punished.
        if (endsWithAny(pathOf(lower), ALLOWED_EXTENSIONS)) score += 10;
        // Two candidates that are otherwise equal are separated by how certain their formats are.
        // This is what puts a JPEG ahead of a HEIC without ever refusing the HEIC outright.
        if (tier == RichAnswerImageFormat.TIER_UNIVERSAL) score += 8;
        String host = RichAnswerImage.hostOf(sourceUrl);
        if (containsAny(host, BRANDED_PREVIEW_HOSTS)) score -= 25;
        String words = normalize(caption);
        if (!words.isEmpty()) score += Math.min(10, words.length() / 20);
        // A long descriptive path usually means an editorial asset; a two-character filename
        // usually means a sprite.
        if (pathOf(lower).length() > 24) score += 4;
        return score;
    }

    /**
     * Whether a decoded picture is big enough and shaped enough to be worth showing.
     *
     * <p>Checked after the download because it is the only honest place to check it: a declared
     * width is a claim and the decoded bounds are a fact. A 60x60 image is an icon; something four
     * hundred pixels wide and twenty tall is a banner.
     */
    public static boolean hasUsefulDimensions(int width, int height) {
        if (width < 200 || height < 150) return false;
        float ratio = width / (float) height;
        return ratio >= 0.3f && ratio <= 4.0f;
    }

    /**
     * How many pictures this answer may carry.
     *
     * <p>One by default. Two only where the question is explicitly a comparison, because that is
     * the one shape where a second picture answers something the first cannot.
     */
    public static int maxImagesFor(String prompt) {
        String question = normalize(prompt);
        boolean comparison = containsAny(question,
                "difference between", "compare", "versus", " vs ", "which one", "side by side");
        return comparison ? RichAnswerImage.MAX_PER_MESSAGE : 1;
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static String pathOf(String url) {
        int query = url.indexOf('?');
        String path = query >= 0 ? url.substring(0, query) : url;
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US).replaceAll("\\s+", " ").trim();
    }

    private static boolean containsAny(String value, String... needles) {
        if (value == null || value.isEmpty()) return false;
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private static boolean endsWithAny(String value, String... suffixes) {
        if (value == null) return false;
        for (String suffix : suffixes) if (value.endsWith(suffix)) return true;
        return false;
    }
}
