package com.orbit.assistant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Whether one particular image on a page belongs in this particular answer.
 *
 * <p><b>The distinction Beta 9 exists to draw.</b> A page titled "Mallard photos and videos" is
 * obviously about Mallards. That says nothing whatsoever about the twelfth {@code <img>} on it,
 * which may be a donation banner, a theme asset or a small blue cube. Every earlier Beta ranked
 * candidates and then took them in order, so the second image slot was filled by "the next thing
 * that is not the first image" - and on a real device that is exactly what it filled it with. The
 * page was relevant, the candidate was not, and nothing in the pipeline could tell the two apart.
 *
 * <p><b>So page relevance and image relevance are separated here.</b> The page title still helps
 * rank a page. It is never, on its own, evidence about an individual image. What counts as evidence
 * about an image is what the page wrote next to <em>that image</em>: its alt text, its title, the
 * figure caption around it, the words in its own filename, and whether it sits inside the article's
 * content or in the furniture around it.
 *
 * <p><b>The second slot has to be earned.</b> Once a good picture is already in hand, the bar for
 * the next one rises, because the alternative to a weak second picture is not an empty answer - it
 * is a good answer with one picture in it. A request for two pictures is a request for up to two
 * useful pictures, and one excellent photograph beats one excellent photograph plus a cube every
 * time.
 *
 * <p>Pure text and a couple of integers. No network, no {@code Context}, no model. The subject
 * words are the transient local ones from {@link RichAnswerSubject}: never persisted, never sent,
 * and never written into {@link RichAnswerTrace}.
 */
public final class RichAnswerCandidateQuality {

    /**
     * The shortest description that counts as the page having said something about an image.
     *
     * <p>Twelve characters. {@code alt="photo"} and {@code alt="image"} are markup filler; a
     * sentence is a claim about what is in the picture.
     */
    static final int MIN_DESCRIPTION_CHARS = 12;

    /** The narrowest decoded picture that can confirm a second slot. */
    static final int MIN_CONFIRMING_WIDTH = 360;

    /** The shortest decoded picture that can confirm a second slot. */
    static final int MIN_CONFIRMING_HEIGHT = 270;

    private RichAnswerCandidateQuality() {}

    /**
     * What this answer is actually looking for: the subject, and whether it wants photographs.
     *
     * <p>Derived once per discovery attempt from the prompt and carried down the resolver, so every
     * candidate on every page is judged against the same question rather than against whatever the
     * page happened to say about itself.
     */
    public static final class Demand {

        /** Nothing known. Every candidate is provisional and no gate can be strict. */
        public static final Demand NONE = new Demand(Collections.<String>emptyList(), false);

        /** The transient local subject words. Never persisted, never recorded, never sent. */
        public final List<String> subject;

        /**
         * Whether the user asked to see photographs rather than pictures in general.
         *
         * <p>The flag that keeps the decoded-bitmap graphic check off diagrams, maps, flags, logos,
         * illustrations, screenshots and artwork, all of which are legitimate answers that are not
         * photographs and must never be refused for failing to look like one.
         */
        public final boolean photographic;

        private Demand(List<String> subject, boolean photographic) {
            this.subject = subject == null
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(subject));
            this.photographic = photographic;
        }

        /** What one prompt is asking for. */
        public static Demand of(String prompt) {
            return new Demand(RichAnswerSubject.tokensOf(prompt),
                    RichAnswerRelevance.wantsPhotographs(prompt));
        }

        /** The same thing, assembled directly. Used by tests and by callers that already split it. */
        public static Demand of(List<String> subject, boolean photographic) {
            return new Demand(subject, photographic);
        }

        /** Whether there is a subject to judge a candidate against at all. */
        public boolean hasSubject() { return !subject.isEmpty(); }
    }

    /**
     * How firmly one candidate is tied to the subject, by what the page said about it.
     *
     * <p>Three steps rather than a score, because the three are used differently. Only
     * {@link #QUALIFIED} may take a second slot on its own; {@link #PROVISIONAL} has to be
     * confirmed by the decoded picture; {@link #UNQUALIFIED} is refused before a byte is spent.
     */
    public enum Standing {
        /** The page named the subject in this image's own description or filename. */
        QUALIFIED,
        /** Article content or a declared page image, but nothing candidate-specific about it. */
        PROVISIONAL,
        /** Page furniture, or an undescribed asset the page never tied to anything. */
        UNQUALIFIED
    }

    /** Everything known about one candidate before it is downloaded. */
    public static final class Evidence {

        /** What an absent candidate produces. Qualifies for nothing. */
        public static final Evidence NONE = new Evidence(
                Standing.UNQUALIFIED, 0, false, false, false, false, false, false);

        public final Standing standing;
        /** The subject match on this image's own words and filename. Never on the page's title. */
        public final int subjectScore;
        /** A subject word in the alt text, title or figure caption. */
        public final boolean describedMatch;
        /** A subject word in the image's own filename or path. */
        public final boolean filenameMatch;
        /** Inside {@code <article>}, {@code <main>} or {@code <figure>}. */
        public final boolean inContent;
        /** Inside {@code <header>}, {@code <nav>}, {@code <footer>} or {@code <aside>}. */
        public final boolean chrome;
        /** The page wrote a real sentence about this image rather than markup filler. */
        public final boolean described;
        /** The page declared this as its own representative image. */
        public final boolean pageDeclared;

        Evidence(Standing standing, int subjectScore, boolean describedMatch, boolean filenameMatch,
                 boolean inContent, boolean chrome, boolean described, boolean pageDeclared) {
            this.standing = standing;
            this.subjectScore = subjectScore;
            this.describedMatch = describedMatch;
            this.filenameMatch = filenameMatch;
            this.inContent = inContent;
            this.chrome = chrome;
            this.described = described;
            this.pageDeclared = pageDeclared;
        }
    }

    /**
     * What this page said about this image, and nothing about what it said about itself.
     *
     * <p>The page title is deliberately not an input. That is the whole correction: a Wikimedia
     * file page called {@code File:Anas platyrhynchos-male-in water.jpg} is about a Mallard, and
     * the theme graphic three hundred lines further down the same document is not.
     */
    public static Evidence evaluate(RichAnswerArticleImages.Candidate candidate, Demand demand) {
        if (candidate == null || candidate.url.isEmpty()) return Evidence.NONE;
        Demand wanted = demand == null ? Demand.NONE : demand;

        String described = candidate.describedBy();
        String filename = readablePath(candidate.url);
        boolean describedMatch = wanted.hasSubject()
                && RichAnswerSubject.matchesAny(wanted.subject, described);
        boolean filenameMatch = wanted.hasSubject()
                && RichAnswerSubject.matchesAny(wanted.subject, filename);
        int subjectScore = Math.max(
                RichAnswerSubject.matchScore(wanted.subject, described),
                RichAnswerSubject.matchScore(wanted.subject, filename));

        boolean inContent = candidate.structure == RichAnswerArticleImages.STRUCTURE_CONTENT;
        boolean chrome = candidate.structure == RichAnswerArticleImages.STRUCTURE_CHROME;
        boolean hasDescription = described.trim().length() >= MIN_DESCRIPTION_CHARS;
        boolean pageDeclared = candidate.origin.isPreview()
                || candidate.origin == RichAnswerArticleImages.Origin.HOSTED_SEARCH;

        Standing standing;
        if (chrome) {
            // Furniture stays furniture even when somebody wrote the subject into its alt text.
            standing = Standing.UNQUALIFIED;
        } else if (describedMatch || filenameMatch) {
            standing = Standing.QUALIFIED;
        } else if (inContent || pageDeclared) {
            // The page put this in its article or declared it as its own picture. That is real
            // structural evidence and it is not evidence about the subject, so it is provisional:
            // good enough to lead an answer, not good enough to fill a second slot unaided.
            standing = Standing.PROVISIONAL;
        } else if (!wanted.hasSubject() && hasDescription) {
            standing = Standing.PROVISIONAL;
        } else {
            standing = Standing.UNQUALIFIED;
        }
        return new Evidence(standing, subjectScore, describedMatch, filenameMatch, inContent,
                chrome, hasDescription, pageDeclared);
    }

    /**
     * Why this candidate may not have this slot, or {@link RichAnswerTrace.Reason#NONE}.
     *
     * <p>Asked before the download, so a candidate with nothing to say for itself never costs a
     * request. {@code slot} counts the pictures this answer has already accepted, so slot zero is
     * the picture that carries the answer and every slot after it is a bonus that has to be worth
     * the space it takes.
     */
    public static RichAnswerTrace.Reason judgeSlot(Evidence evidence, int slot) {
        Evidence found = evidence == null ? Evidence.NONE : evidence;
        if (found.standing != Standing.UNQUALIFIED) return RichAnswerTrace.Reason.NONE;
        return slot <= 0
                ? RichAnswerTrace.Reason.INSUFFICIENT_SUBJECT_RELEVANCE
                : RichAnswerTrace.Reason.SECOND_SLOT_LOW_RELEVANCE;
    }

    /**
     * Whether the decoded picture makes good on what the markup promised.
     *
     * <p>The last question asked before a picture is committed to, and the one that separates
     * "different from image one" from "worth showing beside image one".
     *
     * <ul>
     *   <li>A {@link Standing#QUALIFIED} candidate has already earned its place: the page named the
     *       subject next to this exact image.</li>
     *   <li>At slot zero, a {@link Standing#PROVISIONAL} candidate is taken. An article's main
     *       photograph often carries no alt text at all, and refusing it would mean answering a
     *       picture question with no picture.</li>
     *   <li>At every later slot, a provisional candidate must be article content the page described,
     *       or the page's own declared image; it must be a real photograph when photographs were
     *       what was asked for; and it must be the size of a photograph rather than of a badge.</li>
     * </ul>
     */
    public static boolean confirms(Evidence evidence, Demand demand, int slot, int width,
                                   int height, boolean photographLike) {
        Evidence found = evidence == null ? Evidence.NONE : evidence;
        Demand wanted = demand == null ? Demand.NONE : demand;
        if (found.standing == Standing.UNQUALIFIED) return false;
        if (wanted.photographic && !photographLike) return false;
        if (found.standing == Standing.QUALIFIED) return true;
        if (slot <= 0) return true;
        if (!(found.inContent && found.described) && !found.pageDeclared) return false;
        return width >= MIN_CONFIRMING_WIDTH && height >= MIN_CONFIRMING_HEIGHT;
    }

    /**
     * The words in an address, as words.
     *
     * <p>Separators, percent-encoding and extensions all become spaces, so
     * {@code /commons/Mallard_duck-drake.jpg} reads as {@code commons mallard duck drake jpg} and a
     * whole-word subject match can find "mallard" in it without also finding it inside a hash.
     */
    static String readablePath(String url) {
        if (url == null || url.isEmpty()) return "";
        String text = url.toLowerCase(Locale.US);
        int query = text.indexOf('?');
        if (query >= 0) text = text.substring(0, query);
        int fragment = text.indexOf('#');
        if (fragment >= 0) text = text.substring(0, fragment);
        int scheme = text.indexOf("://");
        if (scheme >= 0) text = text.substring(scheme + 3);
        int slash = text.indexOf('/');
        // The host is dropped: a subject word in a domain name says nothing about one image on it.
        text = slash >= 0 ? text.substring(slash) : "";
        return text.replaceAll("%[0-9a-f]{2}", " ").replaceAll("[^a-z0-9]+", " ").trim();
    }
}
