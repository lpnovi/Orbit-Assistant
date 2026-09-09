package com.orbit.assistant;

import java.util.ArrayList;
import java.util.List;
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
            "how do i feel", "motivate", "poem", "joke", "story about",
            // Added in Beta 3 so the control cases decide here rather than falling through to the
            // answer-text guess below. A hash map and a state's population are not things a
            // photograph answers, however the question happens to be phrased.
            "hash map", "hashmap", "data structure", "linked list", "binary tree", "pointer",
            "syntax", "code example", "population", "gdp", "interest rate", "stock price",
            "how many people", "census"};

    /**
     * Words that mean the user is asking for a picture rather than merely asking about something
     * that has one.
     *
     * <p>This is the line between "a photograph would help" and "a photograph is the answer", and
     * it is what buys a larger page budget and a deeper candidate search. "Show me pictures of a
     * black widow" is unanswerable without an image; "how do I care for a fiddle leaf fig" is a
     * perfectly good text answer that a picture improves.
     */
    private static final String[] STRONG_VISUAL_SUBJECTS = {
            "picture of", "pictures of", "photo of", "photos of", "photograph of", "image of",
            "images of", "show me", "show a", "show the", "what does", "what do",
            "look like", "looks like", "looked like", "appearance of", "what it looks",
            "identify", "identification", "how to tell", "how do i tell", "how can i tell",
            "tell apart", "diagram of", "map of", "flag of", "picture", "photo"};

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
        // Asked as whole words before the phrase list, so "mallard duck photos" and "I want
        // pictures of a mallard duck" - neither of which contains any of the phrases below - are
        // recognised as somebody asking to see something, while "photosynthesis" is not.
        if (mentionsImageWord(words(question))) return true;
        if (containsAny(question, VISUAL_SUBJECTS)) return true;
        // The answer may reveal a visual subject the question did not name - "tell me about the
        // Hagia Sophia" is not phrased visually and is obviously a place.
        return containsAny(answer, VISUAL_SUBJECTS) && question.length() <= 160;
    }

    /**
     * How visual this question is, on a three-step scale.
     *
     * <p>The scale exists because Beta 2 had only two answers - look, or do not look - and so had
     * to pick one page budget for both "what does a northern black widow look like", where the
     * picture <em>is</em> the answer, and "what should I plant in June", where it is decoration.
     * Trying five pages for the second would be five requests nobody asked for; trying three for
     * the first is what lost the picture. Strong visual means <b>try harder</b>, and nothing else:
     * a larger page budget and a deeper candidate list, with every safety bound unchanged.
     */
    public static RichAnswerTrace.Intent intentFor(String prompt, String answerText) {
        if (!answerWantsImage(prompt, answerText)) return RichAnswerTrace.Intent.NONE;
        String question = normalize(prompt);
        // Naming a picture at all is the strongest form this question takes: "mallard duck photos"
        // is a request to see a mallard, however few of the phrases below it happens to contain.
        if (mentionsImageWord(words(question))) return RichAnswerTrace.Intent.STRONG_VISUAL;
        return containsAny(question, STRONG_VISUAL_SUBJECTS)
                ? RichAnswerTrace.Intent.STRONG_VISUAL
                : RichAnswerTrace.Intent.VISUAL;
    }

    /** A score, and - when there is not one - the reason, in the words Diagnostics prints. */
    public static final class Judgement {
        public final int score;
        public final RichAnswerTrace.Reason reason;

        Judgement(int score, RichAnswerTrace.Reason reason) {
            this.score = score;
            this.reason = reason;
        }

        /** Whether this candidate is worth spending a download on. */
        public boolean acceptable() { return reason == RichAnswerTrace.Reason.NONE; }

        static Judgement no(RichAnswerTrace.Reason reason) { return new Judgement(-1, reason); }
    }

    /**
     * Whether one article or preview candidate is worth downloading, and how badly.
     *
     * <p>Everything {@link #score} knew, plus the three things it could not: where the image sits
     * in the document, what the page wrote next to it, and whether any of that has anything to do
     * with what was asked. That last one is the whole point. On a university publication about a
     * spider, the institution's logo and the photograph of the spider are separated by almost
     * nothing in a URL-only score and by a mile once the caption is read against the question.
     *
     * @param subjectTokens the local, transient subject words from {@link RichAnswerSubject}. Never
     *                      persisted and never recorded in diagnostics.
     */
    public static Judgement judge(RichAnswerArticleImages.Candidate candidate, String pageUrl,
                                  String pageTitle, java.util.List<String> subjectTokens,
                                  boolean cited) {
        if (candidate == null || candidate.url.isEmpty()) {
            return Judgement.no(RichAnswerTrace.Reason.UNSAFE_URL);
        }
        if (!RichAnswerUrlPolicy.isFetchableImageUrl(candidate.url)) {
            return Judgement.no(RichAnswerTrace.Reason.UNSAFE_URL);
        }
        String lower = candidate.url.toLowerCase(Locale.US);
        if (endsWithAny(lower, REFUSED_EXTENSIONS)) {
            return Judgement.no(RichAnswerTrace.Reason.UNSUPPORTED_FORMAT);
        }
        if (RichAnswerImageFormat.tierForUrl(candidate.url)
                == RichAnswerImageFormat.TIER_UNSUPPORTED) {
            return Judgement.no(RichAnswerTrace.Reason.UNSUPPORTED_FORMAT);
        }

        String described = normalize(candidate.describedBy());
        // Chrome is refused on what the page called it as well as on where it put it. A file named
        // "hero-2.jpg" gives nothing away; an alt attribute reading "Virginia Tech logo" does.
        if (containsAny(lower, CHROME_MARKERS) || containsAny(described, ALT_CHROME_MARKERS)) {
            return Judgement.no(RichAnswerTrace.Reason.LOGO_OR_CHROME);
        }
        // A size the page itself declares as tiny is an icon, and there is no point paying for the
        // download to discover that. A declared size is only ever believed in this direction.
        if (candidate.declaredWidth > 0 && candidate.declaredHeight > 0) {
            RichAnswerTrace.Reason declared =
                    judgeDimensions(candidate.declaredWidth, candidate.declaredHeight);
            if (declared != RichAnswerTrace.Reason.ACCEPTED) return Judgement.no(declared);
        }

        int score = cited ? 40 : 0;
        switch (candidate.origin) {
            case OG_IMAGE: score += 14; break;
            case TWITTER_IMAGE: score += 10; break;
            case IMAGE_SRC: score += 6; break;
            // An image the article actually contains is worth as much as the page's own share card
            // and often a great deal more, which is the correction Beta 3 exists to make.
            case ARTICLE_IMG:
            case PICTURE_SOURCE:
            case SRCSET: score += 9; break;
            case LAZY_IMAGE: score += 7; break;
            default: break;
        }
        if (candidate.structure == RichAnswerArticleImages.STRUCTURE_CONTENT) score += 16;
        if (candidate.structure == RichAnswerArticleImages.STRUCTURE_CHROME) score -= 30;

        // The strongest signal there is, and the one Beta 2 had no way to consult.
        score += RichAnswerSubject.matchScore(subjectTokens, candidate.describedBy());
        if (RichAnswerSubject.matchesAny(subjectTokens, pageTitle)) score += 6;

        if (endsWithAny(pathOf(lower), ALLOWED_EXTENSIONS)) score += 10;
        if (RichAnswerImageFormat.tierForUrl(candidate.url) == RichAnswerImageFormat.TIER_UNIVERSAL) {
            score += 8;
        }
        if (containsAny(RichAnswerImage.hostOf(pageUrl), BRANDED_PREVIEW_HOSTS)) score -= 25;
        if (!described.isEmpty()) score += Math.min(10, described.length() / 20);
        if (pathOf(lower).length() > 24) score += 4;
        // A declared size that is genuinely large is a page telling you this is the photograph
        // rather than the thumbnail, and it costs nothing to believe in this direction.
        if (candidate.declaredWidth >= 600) score += 8;
        else if (candidate.declaredWidth >= 320) score += 4;
        return new Judgement(score, RichAnswerTrace.Reason.NONE);
    }

    /**
     * Words that name chrome when they appear in what a page called an image.
     *
     * <p>Separate from {@link #CHROME_MARKERS}, which matches URL fragments. "icon" in a path is a
     * strong signal; "icon" in a sentence of alt text is often part of a phrase like "iconic
     * skyline", so this list is the narrower one that survives being read as English.
     */
    private static final String[] ALT_CHROME_MARKERS = {
            "logo", "favicon", "avatar", "site header", "site footer", "navigation",
            "advertisement", "sponsored", "share on", "follow us", "subscribe",
            "placeholder", "loading", "tracking pixel", "spacer"};

    /** The narrowest a picture may be and still be a picture. */
    static final int MIN_WIDTH = 200;
    /** The shortest a picture may be and still be a picture. */
    static final int MIN_HEIGHT = 150;

    /**
     * Why a decoded picture was refused, or {@link RichAnswerTrace.Reason#ACCEPTED}.
     *
     * <p>The same rule as {@link #hasUsefulDimensions}, asked in a form that says which half
     * failed. "Too small" and "wrong shape" are different bugs on a real device - the first is a
     * page whose photographs are thumbnails, the second is a page whose hero is a letterbox banner
     * - and collapsing them to one boolean is exactly what left Beta 2 unexplainable.
     */
    public static RichAnswerTrace.Reason judgeDimensions(int width, int height) {
        if (width <= 0 || height <= 0) return RichAnswerTrace.Reason.TOO_SMALL;
        // Shape is asked first, because it is the more specific answer where both apply. A
        // 1600x120 image fails the height rule and the ratio rule at once, and calling that "too
        // small" would send somebody looking for a bigger version of a page banner.
        float ratio = width / (float) height;
        if (ratio < 0.3f || ratio > 4.0f) return RichAnswerTrace.Reason.BAD_ASPECT_RATIO;
        if (width < MIN_WIDTH || height < MIN_HEIGHT) return RichAnswerTrace.Reason.TOO_SMALL;
        return RichAnswerTrace.Reason.ACCEPTED;
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
        return judgeDimensions(width, height) == RichAnswerTrace.Reason.ACCEPTED;
    }

    /**
     * How many pictures this answer may carry.
     *
     * <p><b>Two questions, not one.</b> {@link #intentFor} decides whether Rich Answers runs at
     * all; this decides how many pictures it should try to bring back, and Beta 5 conflated them
     * badly enough to be the release's headline bug. "Show me pics of a mallard duck" was strongly
     * visual, found its source, found seventeen article candidates - and asked for exactly one
     * picture, because the only thing that had ever earned a second was a comparison. The user
     * wrote a plural noun and got a singular answer.
     *
     * <p>Four rules, in order, and all of them local, deterministic and free:
     * <ol>
     *   <li>A number written next to a picture word wins, clamped into what Orbit will ever show -
     *       "five pictures" is two, "one photo" is one.</li>
     *   <li>A plural picture word means two. This is the whole fix: pictures, pics, photos,
     *       images, photographs.</li>
     *   <li>A comparison means two, unchanged from Beta 5.</li>
     *   <li>Everything else means one.</li>
     * </ol>
     *
     * <p>{@link RichAnswerImage#MAX_PER_MESSAGE} is the ceiling and Beta 6 does not raise it. Two
     * pictures is a deliberate design limit rather than a budget, and a request for ten is a
     * request Orbit answers well with two.
     */
    public static int maxImagesFor(String prompt) {
        String question = normalize(prompt);
        if (question.isEmpty()) return 1;
        List<String> words = words(question);
        int explicit = explicitImageCount(words);
        if (explicit > 0) return clampImages(explicit);
        if (hasPluralImageWord(words)) return RichAnswerImage.MAX_PER_MESSAGE;
        if (isComparison(question)) return RichAnswerImage.MAX_PER_MESSAGE;
        return 1;
    }

    /**
     * Whether the user asked, in words, for more than one picture.
     *
     * <p>Narrower than {@code maxImagesFor(prompt) > 1} on purpose, and the difference is what
     * gates secondary image discovery. A comparison earns a second picture because the question
     * has two subjects, not because the user asked to see several pictures, so it must not unlock
     * a fallback that goes looking at pages the answer never cited.
     */
    public static boolean requestsMultipleImages(String prompt) {
        String question = normalize(prompt);
        if (question.isEmpty()) return false;
        List<String> words = words(question);
        int explicit = explicitImageCount(words);
        if (explicit > 0) return explicit > 1;
        return hasPluralImageWord(words);
    }

    /** Whether the question is a comparison, which is answered by two pictures rather than one. */
    static boolean isComparison(String question) {
        return containsAny(question, "difference between", "differences between", "compare",
                "comparison", "versus", " vs ", "which one", "side by side", "side-by-side");
    }

    /** Whatever Orbit is willing to draw, whatever number was asked for. */
    private static int clampImages(int requested) {
        return Math.max(1, Math.min(requested, RichAnswerImage.MAX_PER_MESSAGE));
    }

    /**
     * Singular words for a picture, matched as whole words.
     *
     * <p>Whole words rather than substrings, which is not fussiness: "photosynthesis" contains
     * "photos" and "imagery" contains "image", and a substring rule would read both of those as
     * somebody asking to see something.
     */
    private static final String[] SINGULAR_IMAGE_WORDS = {
            "picture", "pic", "photo", "photograph", "image", "snapshot", "shot"};

    /** The plural of each, which is the signal that two pictures were asked for. */
    private static final String[] PLURAL_IMAGE_WORDS = {
            "pictures", "pics", "photos", "photographs", "images", "snapshots", "shots"};

    /** Numbers a person writes in front of a picture word. */
    private static final String[] NUMBER_WORDS = {
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten"};

    /** Whether any picture word at all appears, in either number. */
    static boolean mentionsImageWord(List<String> words) {
        return hasAny(words, SINGULAR_IMAGE_WORDS) || hasAny(words, PLURAL_IMAGE_WORDS);
    }

    /** Whether a plural picture word appears. */
    static boolean hasPluralImageWord(List<String> words) {
        return hasAny(words, PLURAL_IMAGE_WORDS);
    }

    /**
     * The number the user wrote next to a picture word, or 0 when they did not write one.
     *
     * <p>"Next to" is a two-word window, so "two pictures" and "three good photos" both count and
     * a number sitting somewhere else in the sentence does not. Digits and written numbers are
     * treated the same, because "show me 3 photos" and "show me three photos" are the same request.
     */
    static int explicitImageCount(List<String> words) {
        if (words == null) return 0;
        for (int i = 0; i < words.size(); i++) {
            int value = numberValue(words.get(i));
            if (value < 0) continue;
            for (int j = i + 1; j <= i + 2 && j < words.size(); j++) {
                String word = words.get(j);
                if (matches(word, SINGULAR_IMAGE_WORDS) || matches(word, PLURAL_IMAGE_WORDS)) {
                    return value;
                }
            }
        }
        return 0;
    }

    /** The value of a number word or a small run of digits, or -1 when this is not a number. */
    private static int numberValue(String word) {
        if (word == null || word.isEmpty()) return -1;
        for (int i = 0; i < NUMBER_WORDS.length; i++) if (NUMBER_WORDS[i].equals(word)) return i;
        if (word.length() > 3) return -1;
        for (int i = 0; i < word.length(); i++) {
            if (!Character.isDigit(word.charAt(i))) return -1;
        }
        try {
            return Integer.parseInt(word);
        } catch (Exception ignored) {
            return -1;
        }
    }

    /** A normalized question split into whole words, so nothing matches inside another word. */
    static List<String> words(String question) {
        List<String> words = new ArrayList<>();
        if (question == null || question.isEmpty()) return words;
        for (String word : question.split("[^\\p{L}\\p{N}]+")) {
            if (!word.isEmpty()) words.add(word);
        }
        return words;
    }

    private static boolean hasAny(List<String> words, String[] needles) {
        if (words == null) return false;
        for (String word : words) if (matches(word, needles)) return true;
        return false;
    }

    private static boolean matches(String word, String[] needles) {
        for (String needle : needles) if (needle.equals(word)) return true;
        return false;
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
