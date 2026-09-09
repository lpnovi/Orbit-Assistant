package com.orbit.assistant;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Why the last few Rich Answers attempts did or did not produce a picture.
 *
 * <p><b>The problem this solves.</b> Two betas in a row ended the same way: a visual question, a
 * hosted web search, a cited page, and no image - with nothing anywhere on the device that could
 * say which of a dozen stages had failed. Was the page refused? Did it return a PDF? Were there no
 * candidates, or were there twelve that all scored badly? Did an image download fine and then fail
 * to attach to the message? Every one of those has a different fix and they were indistinguishable
 * from the outside, so each release shipped a guess. This is the end of that: after Beta 3 a
 * failure is either fixed or explainable, and never again "no image, no explanation".
 *
 * <p><b>What it records is stages, not content.</b> This buffer is designed to be copied out of
 * Diagnostics and pasted into a chat window with a stranger, so it must be safe to read. It holds
 * hosts, sanitized paths, HTTP statuses, MIME types, byte counts, scores, decoded dimensions and
 * enum-named outcomes. It holds no prompt, no answer, no reasoning, no screen contents, no
 * attachment, no Vault content, no cookie, no header, no token, and no query string or fragment -
 * a URL is reduced to {@code host/path} with a bounded path before it is written, because a query
 * string is where a page puts what somebody searched for.
 *
 * <p><b>Small and bounded.</b> {@link #MAX_ATTEMPTS} attempts, each with a bounded number of pages
 * and each page a bounded number of candidates. It persists to the same private diagnostics
 * preferences file as the other counters, because the realistic workflow is that the user
 * reproduces a failure, leaves the app, and opens Diagnostics afterwards - which a memory-only
 * buffer would not survive. It is cleared with the rest of the local data.
 */
public final class RichAnswerTrace {

    /**
     * How many attempts are kept.
     *
     * <p>Five. Enough that a user who reproduces a failure two or three times still has the first
     * one, and small enough that the whole buffer stays a few kilobytes and stays readable when it
     * is pasted somewhere.
     */
    public static final int MAX_ATTEMPTS = 5;

    /** Pages recorded per attempt. Above the largest page budget, so nothing is ever truncated. */
    public static final int MAX_PAGES_PER_ATTEMPT = 6;

    /** Candidates recorded per page. Above the fetch budget, so every attempt made is visible. */
    public static final int MAX_CANDIDATES_PER_PAGE = 10;

    /** The longest sanitized path written down. */
    static final int MAX_PATH_CHARS = 60;

    private static final String FILE = "orbit_diagnostics";
    private static final String KEY = "rich_answer_trace";

    /** How visual Orbit judged the question to be. */
    public enum Intent { NONE, VISUAL, STRONG_VISUAL }

    /**
     * Which route supplied the pages this attempt read.
     *
     * <p>Recorded separately from the outcome because the two answer different questions. The
     * outcome says how the attempt ended; this says where it got its sources, which is the fact
     * Beta 4 could not report and the reason a device showing an "Open source" chip alongside
     * "Sources received: 0" was so hard to explain. It mirrors
     * {@link RichAnswerProvenance.Route} exactly.
     */
    public enum Provenance { NONE, STRUCTURED_HOSTED_SEARCH, EXPLICIT_SOURCE_MARKER }

    /** How one whole attempt ended. */
    public enum Outcome {
        /** A picture was found and is on the message. */
        IMAGE_FOUND_AND_ATTACHED,
        /**
         * A picture was found and downloaded, and then had nowhere to go.
         *
         * <p>Kept distinct because it means the discovery pipeline worked perfectly and the bug is
         * somewhere else entirely - the answer text no longer matched, or the chat had moved on.
         * Reading this as "image fetch failed" would send the next fix to the wrong place.
         */
        IMAGE_FOUND_BUT_MESSAGE_NOT_FOUND,
        /** A picture was found, and the user had already stopped the request. Correct behaviour. */
        IMAGE_FOUND_BUT_REQUEST_CANCELLED,
        /** Every page was read and nothing on any of them was usable. */
        NO_USABLE_IMAGE,
        /** Orbit never looked: rich answers off, no hosted search, no sources, or not a visual question. */
        NOT_ELIGIBLE,
        DISABLED,
        PROVIDER_UNSUPPORTED,
        NOT_VISUAL,
        NO_SOURCE_URLS_RECEIVED,
        /** Recorded but never finished, which on a healthy device should not appear. */
        INCOMPLETE
    }

    /** Why one candidate, page or attempt did not produce a picture. */
    public enum Reason {
        NONE,
        UNSAFE_URL,
        PRIVATE_HOST,
        DNS_REJECTED,
        REDIRECT_REJECTED,
        HTTP_ERROR,
        NOT_IMAGE,
        NOT_HTML,
        TOO_LARGE,
        DECODE_FAILED,
        UNSUPPORTED_FORMAT,
        TOO_SMALL,
        BAD_ASPECT_RATIO,
        LOW_SCORE,
        DUPLICATE,
        /**
         * The same photograph as one already accepted, proven from the decoded picture.
         *
         * <p>Deliberately not {@link #DUPLICATE}, which means two addresses were shown to name one
         * asset. This one means two unrelated addresses turned out to be one photograph, which is
         * the failure the device produced and the reason Beta 7 exists. Telling them apart in a
         * report is what says whether the cheap layer or the real one caught it.
         */
        VISUAL_DUPLICATE,
        LOGO_OR_CHROME,
        /**
         * Nothing the page said about this image tied it to what was asked about.
         *
         * <p>Deliberately not {@link #LOGO_OR_CHROME}, which means the image named itself as
         * furniture. This one means the page never said anything about it at all, and the only
         * thing connecting it to the subject was the document it happened to sit in.
         */
        INSUFFICIENT_SUBJECT_RELEVANCE,
        /**
         * The picture downloaded and turned out to be a graphic rather than a photograph.
         *
         * <p>The blue cube. Recorded separately from {@link #TOO_SMALL} and {@link #LOGO_OR_CHROME}
         * because it was neither: large enough, not named as chrome, and still not a photograph of
         * anything.
         */
        GRAPHIC_OR_PLACEHOLDER,
        /**
         * Good enough to lead an answer, not good enough to be the second picture in one.
         *
         * <p>A request for two pictures is a request for up to two useful pictures. When a picture
         * is already in hand, a candidate with no evidence of its own is refused and the answer
         * shows one - which is the correct outcome and used to look identical to a failure.
         */
        SECOND_SLOT_LOW_RELEVANCE,
        NO_CANDIDATES,
        PAGE_FETCH_FAILED,
        TIMEOUT,
        NETWORK,
        ACCEPTED
    }

    // ---- recorded shapes -------------------------------------------------------------------------

    /** One image address Orbit considered, and what became of it. */
    public static final class CandidateRecord {
        public RichAnswerArticleImages.Origin origin = RichAnswerArticleImages.Origin.ARTICLE_IMG;
        public String host = "";
        public String path = "";
        public int score;
        public int declaredWidth;
        public int declaredHeight;
        /** True once a download was actually started for this candidate. */
        public boolean fetched;
        public int httpStatus;
        public String mime = "";
        public int bytesRead;
        public int redirects;
        public int decodedWidth;
        public int decodedHeight;
        /**
         * A short label for what the picture actually looked like, or empty.
         *
         * <p>Four hex characters of a perceptual hash, never a bitmap and never anything that could
         * be turned back into one. It is here because two rows reading {@code 7A31…} are the whole
         * explanation for an answer that showed one picture when two were asked for, and Beta 6's
         * report could not say that at all. A picture too flat to fingerprint has no token.
         */
        public String visualId = "";
        /**
         * How well this image's <em>own</em> words and filename matched the subject.
         *
         * <p>Separate from {@link #score}, and that separation is the finding Beta 9 makes
         * legible. A candidate can rank well on structure, size and the page it belongs to while
         * having nothing candidate-specific about it at all, which is exactly how a page graphic
         * became the second picture in a Mallard answer. A row reading {@code subject 0} beside a
         * healthy score is that failure, written down. The words themselves are never recorded.
         */
        public int subjectScore;
        /** Where the page put this image: 1 content, 0 neutral, -1 chrome. */
        public int structure;
        /** How firmly the page tied this image to the subject, as a name rather than a number. */
        public RichAnswerCandidateQuality.Standing standing =
                RichAnswerCandidateQuality.Standing.PROVISIONAL;
        public Reason reason = Reason.NONE;

        /** One compact human line, e.g. {@code ARTICLE_IMG jpg · 800x600 · accepted}. */
        public String describe() {
            StringBuilder b = new StringBuilder(origin.name());
            if (!path.isEmpty()) b.append(' ').append(path);
            b.append(" · score ").append(score);
            b.append(" · subject ").append(subjectScore);
            b.append(" · ").append(structureName());
            b.append(" · ").append(standing.name().toLowerCase(Locale.US));
            if (declaredWidth > 0 && declaredHeight > 0) {
                b.append(" · declared ").append(declaredWidth).append('x').append(declaredHeight);
            }
            if (!fetched) {
                b.append(" · not fetched · ").append(reason.name());
                return b.toString();
            }
            if (httpStatus > 0) b.append(" · HTTP ").append(httpStatus);
            if (!mime.isEmpty()) b.append(" · ").append(mime);
            if (bytesRead > 0) b.append(" · ").append(bytesRead / 1024).append(" KB");
            if (redirects > 0) b.append(" · ").append(redirects).append(" redirects");
            if (decodedWidth > 0 && decodedHeight > 0) {
                b.append(" · ").append(decodedWidth).append('x').append(decodedHeight);
            }
            if (!visualId.isEmpty()) b.append(" · Visual ID ").append(visualId);
            b.append(" · ").append(reason == Reason.ACCEPTED ? "accepted" : reason.name());
            return b.toString();
        }

        /** Where the page put this image, in a word. */
        private String structureName() {
            if (structure == RichAnswerArticleImages.STRUCTURE_CONTENT) return "content";
            if (structure == RichAnswerArticleImages.STRUCTURE_CHROME) return "chrome";
            return "neutral";
        }
    }

    /** One cited page Orbit read, and what it contained. */
    public static final class PageRecord {
        public String host = "";
        public String path = "";
        /**
         * Whether this page came from a secondary image-discovery hint rather than from provenance.
         *
         * <p>Recorded because the two are different claims and a report that could not tell them
         * apart would be misleading in exactly the direction that matters: a hint page is where a
         * picture was found, never a page the answer cited for anything it said.
         */
        public boolean discoveryHint;
        public boolean fetchAllowed = true;
        public int httpStatus;
        public String contentType = "";
        public int bytesRead;
        public int redirects;
        public int previewCandidates;
        public int articleCandidates;
        public Reason reason = Reason.NONE;
        public final List<CandidateRecord> candidates = new ArrayList<>();

        /** Adds a candidate record, bounded. Returns it, or a scratch record past the bound. */
        public CandidateRecord candidate() {
            CandidateRecord record = new CandidateRecord();
            if (candidates.size() < MAX_CANDIDATES_PER_PAGE) candidates.add(record);
            return record;
        }
    }

    /** One whole discovery attempt for one answer. */
    public static final class Attempt {
        public long timestamp = System.currentTimeMillis();
        public boolean enabled;
        public boolean providerEligible;
        public Intent intent = Intent.NONE;
        public Provenance provenance = Provenance.NONE;
        public int requestedImages;
        /**
         * How many pictures actually ended up on the message.
         *
         * <p>Beta 5 reported only what was asked for, so "Images requested: 1" was the whole story
         * and a plural request that came back with one picture looked identical to a singular
         * request that worked perfectly. Requested and attached are printed together now, and the
         * gap between them is the finding.
         */
        public int attachedImages;
        /**
         * How many downloaded pictures were refused for being a photograph the answer already had.
         *
         * <p>The number that makes Beta 7's behaviour legible. "Requested 2, fetched 4, visual
         * duplicates 2, attached 2" is a search that worked; "requested 2, visual duplicates 3,
         * attached 1" is a subject the web only has one photograph of, which is a correct answer
         * and used to be indistinguishable from a broken one.
         */
        public int visualDuplicates;
        public int sourcesReceived;
        public int pagesAttempted;
        public int pageBudget;
        /** Image-oriented links in the answer that were collected as a possible second picture. */
        public int discoveryHintsConsidered;
        /** How many of those were actually read. */
        public int discoveryHintsUsed;
        public Outcome outcome = Outcome.INCOMPLETE;
        public final List<PageRecord> pages = new ArrayList<>();

        /** Adds a page record, bounded. Returns it, or a scratch record past the bound. */
        public PageRecord page() {
            PageRecord record = new PageRecord();
            if (pages.size() < MAX_PAGES_PER_ATTEMPT) pages.add(record);
            return record;
        }

        public int candidatesConsidered() {
            int total = 0;
            for (PageRecord page : pages) total += page.candidates.size();
            return total;
        }

        public int imagesFetched() {
            int total = 0;
            for (PageRecord page : pages) {
                for (CandidateRecord candidate : page.candidates) if (candidate.fetched) total++;
            }
            return total;
        }

        /** How many candidates were refused for one particular reason. */
        public int rejections(Reason reason) {
            int total = 0;
            for (PageRecord page : pages) {
                for (CandidateRecord candidate : page.candidates) {
                    if (candidate.reason == reason) total++;
                }
            }
            return total;
        }
    }

    private RichAnswerTrace() {}

    // ---- writing ---------------------------------------------------------------------------------

    /**
     * Stores one finished attempt, evicting the oldest.
     *
     * <p>Called from the discovery thread once, at the end. Never throws: a diagnostics buffer that
     * can break a feature is worse than no diagnostics buffer.
     */
    public static void record(Context context, Attempt attempt) {
        if (context == null || attempt == null) return;
        try {
            List<Attempt> attempts = attempts(context);
            attempts.add(attempt);
            while (attempts.size() > MAX_ATTEMPTS) attempts.remove(0);
            JSONArray array = new JSONArray();
            for (Attempt each : attempts) array.put(toJson(each));
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                    .putString(KEY, array.toString())
                    .apply();
        } catch (Exception ignored) {}
    }

    /** Empties the buffer. Used by the local-data reset and by tests. */
    public static void clear(Context context) {
        if (context == null) return;
        try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY).apply();
        } catch (Exception ignored) {}
    }

    /** Every stored attempt, oldest first. Never null. */
    public static List<Attempt> attempts(Context context) {
        List<Attempt> out = new ArrayList<>();
        if (context == null) return out;
        try {
            String stored = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .getString(KEY, "");
            if (stored == null || stored.trim().isEmpty()) return out;
            JSONArray array = new JSONArray(stored);
            for (int i = 0; i < array.length() && out.size() < MAX_ATTEMPTS; i++) {
                JSONObject object = array.optJSONObject(i);
                if (object != null) out.add(fromJson(object));
            }
        } catch (Exception ignored) {
            // Corrupt or from an older shape: an unreadable buffer is an empty one, never a crash.
        }
        return out;
    }

    /** The most recent attempt, or null. */
    public static Attempt last(Context context) {
        List<Attempt> attempts = attempts(context);
        return attempts.isEmpty() ? null : attempts.get(attempts.size() - 1);
    }

    // ---- sanitizing ------------------------------------------------------------------------------

    /**
     * A URL reduced to what is safe to write down: host and a bounded path, nothing else.
     *
     * <p>{@code https://example.com/article?id=123#part} becomes {@code example.com/article}. The
     * query and the fragment are dropped rather than shortened, because a query string is precisely
     * where a page carries what somebody typed, and a fragment is where it carries where they were
     * reading. Any userinfo is dropped with them.
     */
    public static String sanitize(String url) {
        if (url == null) return "";
        String text = url.trim();
        if (text.isEmpty()) return "";
        try {
            java.net.URI uri = java.net.URI.create(text);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.US);
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            if (host.isEmpty()) return "";
            return host + boundedPath(path);
        } catch (Exception ignored) {
            return "";
        }
    }

    /** Just the host of a URL, lowercased, or empty. */
    public static String hostOf(String url) {
        String sanitized = sanitize(url);
        int slash = sanitized.indexOf('/');
        return slash < 0 ? sanitized : sanitized.substring(0, slash);
    }

    /** Just the bounded path of a URL, or empty. */
    public static String pathOf(String url) {
        String sanitized = sanitize(url);
        int slash = sanitized.indexOf('/');
        return slash < 0 ? "" : sanitized.substring(slash);
    }

    /**
     * A path shortened from the middle, so both what kind of page it is and what it is called
     * survive.
     *
     * <p>Bounded because a path is not automatically safe either: some sites put a search term or a
     * title in it. Sixty characters is enough to recognise a page and short enough that a long
     * generated path cannot smuggle a paragraph into the report.
     */
    static String boundedPath(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) return "/";
        String clean = path.replaceAll("\\s+", "");
        if (clean.length() <= MAX_PATH_CHARS) return clean;
        return clean.substring(0, MAX_PATH_CHARS - 8) + "…" + clean.substring(clean.length() - 6);
    }

    // ---- reporting -------------------------------------------------------------------------------

    /** The one line the Diagnostics summary carries, or empty when nothing has been attempted. */
    public static String summaryLine(Context context) {
        Attempt last = last(context);
        if (last == null) return "";
        return String.format(Locale.US, "Rich Answers: last attempt %s · %d pages · %d candidates",
                readable(last.outcome), last.pagesAttempted, last.candidatesConsidered());
    }

    /** The readable block the Rich Answers section shows on screen. */
    public static String body(Context context) {
        List<Attempt> attempts = attempts(context);
        if (attempts.isEmpty()) {
            return "No Rich Answers attempt recorded on this device yet.\n\n"
                    + "Rich Answers records sourced answers and visual requests, even when sources are missing. Ask a "
                    + "visual question with web search on, then come back here.";
        }
        StringBuilder b = new StringBuilder();
        for (int i = attempts.size() - 1; i >= 0; i--) {
            if (b.length() > 0) b.append("\n\n");
            b.append(describe(attempts.get(i), attempts.size() - i));
        }
        return b.toString();
    }

    /** The verbose pasteable report behind "Copy Rich Answers diagnostics". */
    public static String report(Context context) {
        StringBuilder b = new StringBuilder("Orbit Rich Answers diagnostics\n");
        b.append("Orbit ").append(BuildConfig.VERSION_NAME).append('\n');
        b.append("Android ").append(android.os.Build.VERSION.SDK_INT).append('\n');
        b.append("Attempts kept: ").append(MAX_ATTEMPTS).append('\n');
        b.append("No prompt, answer or page text is recorded.\n\n");
        b.append(body(context));
        return b.toString();
    }

    /** One attempt written out. {@code ordinal} is 1 for the most recent. */
    static String describe(Attempt attempt, int ordinal) {
        StringBuilder b = new StringBuilder();
        b.append(ordinal == 1 ? "Last attempt" : ("Attempt −" + (ordinal - 1)));
        b.append(": ").append(ago(attempt.timestamp)).append('\n');
        b.append("Intent: ").append(readable(attempt.intent)).append('\n');
        b.append("Rich Answers enabled: ").append(attempt.enabled ? "yes" : "no").append('\n');
        b.append("Provider eligible: ").append(attempt.providerEligible ? "yes" : "no").append('\n');
        b.append("Images requested: ").append(attempt.requestedImages).append('\n');
        b.append("Images attached: ").append(attempt.attachedImages).append('\n');
        b.append("Source provenance: ").append(readable(attempt.provenance)).append('\n');
        b.append("Sources received: ").append(attempt.sourcesReceived).append('\n');
        b.append("Page budget: ").append(attempt.pageBudget).append('\n');
        b.append("Pages attempted: ").append(attempt.pagesAttempted).append('\n');
        if (attempt.discoveryHintsConsidered > 0 || attempt.discoveryHintsUsed > 0) {
            b.append("Discovery hints considered: ")
                    .append(attempt.discoveryHintsConsidered).append('\n');
            b.append("Discovery hints used: ").append(attempt.discoveryHintsUsed).append('\n');
        }
        b.append("Candidates considered: ").append(attempt.candidatesConsidered()).append('\n');
        b.append("Images fetched: ").append(attempt.imagesFetched()).append('\n');
        b.append("Visual duplicates rejected: ").append(attempt.visualDuplicates).append('\n');
        // Beta 9's two new refusals, counted here so a plural request answered with one picture
        // says which of the three completely different reasons it was.
        b.append("Irrelevant candidates rejected: ")
                .append(attempt.rejections(Reason.INSUFFICIENT_SUBJECT_RELEVANCE)
                        + attempt.rejections(Reason.SECOND_SLOT_LOW_RELEVANCE)).append('\n');
        b.append("Graphics and placeholders rejected: ")
                .append(attempt.rejections(Reason.GRAPHIC_OR_PLACEHOLDER)).append('\n');
        b.append("Result: ").append(readable(attempt.outcome));
        // The one line that explains a plural request answered with a single picture. Deliberately
        // here and nowhere else: showing one good photograph is the right outcome, so the shortfall
        // belongs in a report somebody opened on purpose rather than in the chat.
        if (attempt.requestedImages > 1 && attempt.attachedImages == 1) {
            // Four shapes of the same shortfall, and they mean completely different things. Two of
            // them are Orbit working correctly: the web only had one photograph of this subject, or
            // the only other thing on offer was a graphic. Showing one good picture is the right
            // answer in both, and a report that could not say which is which is what sent a blue
            // cube to a physical device.
            b.append("\nSecond image unavailable");
            if (attempt.rejections(Reason.GRAPHIC_OR_PLACEHOLDER) > 0) {
                b.append(": the remaining candidates were graphics rather than photographs");
            } else if (attempt.rejections(Reason.SECOND_SLOT_LOW_RELEVANCE) > 0) {
                b.append(": no other image on the pages was described as the subject");
            } else if (attempt.visualDuplicates > 0) {
                b.append(": every other candidate was the same photograph");
            }
        }
        for (PageRecord page : attempt.pages) {
            b.append("\n\n").append(page.host.isEmpty() ? "(unknown host)" : page.host);
            if (!page.path.isEmpty()) b.append(page.path);
            if (page.discoveryHint) b.append(" (discovery hint)");
            b.append('\n');
            if (!page.fetchAllowed) {
                b.append("Page fetch: blocked · ").append(page.reason.name());
            } else if (page.httpStatus > 0 || !page.contentType.isEmpty()) {
                b.append("Page fetch: HTTP ").append(page.httpStatus);
                if (!page.contentType.isEmpty()) b.append(" · ").append(page.contentType);
                b.append(" · ").append(page.bytesRead / 1024).append(" KB");
                if (page.redirects > 0) b.append(" · ").append(page.redirects).append(" redirects");
                if (page.reason != Reason.NONE && page.reason != Reason.ACCEPTED) {
                    b.append(" · ").append(page.reason.name());
                }
            } else {
                b.append("Page fetch: failed · ").append(page.reason.name());
            }
            b.append("\nPreview candidates: ").append(page.previewCandidates);
            b.append("\nArticle candidates: ").append(page.articleCandidates);
            int n = 1;
            for (CandidateRecord candidate : page.candidates) {
                b.append("\nCandidate ").append(n++).append(": ").append(candidate.describe());
            }
            if (page.candidates.isEmpty()) b.append("\nNo candidate was attempted.");
        }
        return b.toString();
    }

    static String readable(Outcome outcome) {
        switch (outcome) {
            case IMAGE_FOUND_AND_ATTACHED: return "Image attached";
            case IMAGE_FOUND_BUT_MESSAGE_NOT_FOUND: return "Image found, message not found";
            case IMAGE_FOUND_BUT_REQUEST_CANCELLED: return "Image found, request cancelled";
            case NO_USABLE_IMAGE: return "No usable image";
            case NOT_ELIGIBLE: return "Not eligible";
            case DISABLED: return "Disabled";
            case PROVIDER_UNSUPPORTED: return "Provider unsupported";
            case NOT_VISUAL: return "Not visual";
            case NO_SOURCE_URLS_RECEIVED: return "No source URLs received";
            default: return "Incomplete";
        }
    }

    static String readable(Provenance provenance) {
        switch (provenance) {
            case STRUCTURED_HOSTED_SEARCH: return "Structured hosted search";
            case EXPLICIT_SOURCE_MARKER: return "Explicit source marker fallback";
            default: return "None";
        }
    }

    static String readable(Intent intent) {
        switch (intent) {
            case STRONG_VISUAL: return "Strong visual";
            case VISUAL: return "Visual";
            default: return "Not visual";
        }
    }

    /** A coarse relative time. Coarse on purpose: an exact clock reading is not needed to debug. */
    static String ago(long timestamp) {
        long delta = System.currentTimeMillis() - timestamp;
        if (timestamp <= 0 || delta < 0) return "just now";
        long minutes = delta / 60000L;
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + " min ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + (hours == 1 ? " hour ago" : " hours ago");
        long days = hours / 24;
        return days + (days == 1 ? " day ago" : " days ago");
    }

    // ---- storage shape ---------------------------------------------------------------------------

    private static JSONObject toJson(Attempt attempt) throws Exception {
        JSONObject o = new JSONObject();
        o.put("t", attempt.timestamp);
        o.put("on", attempt.enabled);
        o.put("prov", attempt.providerEligible);
        o.put("intent", attempt.intent.name());
        o.put("prov_route", attempt.provenance.name());
        o.put("want", attempt.requestedImages);
        o.put("got", attempt.attachedImages);
        o.put("vdup", attempt.visualDuplicates);
        o.put("src", attempt.sourcesReceived);
        o.put("tried", attempt.pagesAttempted);
        o.put("budget", attempt.pageBudget);
        o.put("hint_n", attempt.discoveryHintsConsidered);
        o.put("hint_u", attempt.discoveryHintsUsed);
        o.put("out", attempt.outcome.name());
        JSONArray pages = new JSONArray();
        for (PageRecord page : attempt.pages) {
            JSONObject p = new JSONObject();
            p.put("h", page.host);
            p.put("hint", page.discoveryHint);
            p.put("p", page.path);
            p.put("ok", page.fetchAllowed);
            p.put("s", page.httpStatus);
            p.put("ct", page.contentType);
            p.put("b", page.bytesRead);
            p.put("r", page.redirects);
            p.put("pc", page.previewCandidates);
            p.put("ac", page.articleCandidates);
            p.put("why", page.reason.name());
            JSONArray candidates = new JSONArray();
            for (CandidateRecord candidate : page.candidates) {
                JSONObject c = new JSONObject();
                c.put("o", candidate.origin.name());
                c.put("h", candidate.host);
                c.put("p", candidate.path);
                c.put("sc", candidate.score);
                c.put("dw", candidate.declaredWidth);
                c.put("dh", candidate.declaredHeight);
                c.put("f", candidate.fetched);
                c.put("s", candidate.httpStatus);
                c.put("m", candidate.mime);
                c.put("b", candidate.bytesRead);
                c.put("r", candidate.redirects);
                c.put("w", candidate.decodedWidth);
                c.put("ht", candidate.decodedHeight);
                c.put("vid", candidate.visualId);
                c.put("ss", candidate.subjectScore);
                c.put("st", candidate.structure);
                c.put("sd", candidate.standing.name());
                c.put("why", candidate.reason.name());
                candidates.put(c);
            }
            p.put("c", candidates);
            pages.put(p);
        }
        o.put("pages", pages);
        return o;
    }

    private static Attempt fromJson(JSONObject o) {
        Attempt attempt = new Attempt();
        attempt.timestamp = o.optLong("t", 0L);
        attempt.enabled = o.optBoolean("on", false);
        attempt.providerEligible = o.optBoolean("prov", false);
        attempt.intent = intent(o.optString("intent", "NONE"));
        attempt.provenance = provenance(o.optString("prov_route", "NONE"));
        attempt.requestedImages = o.optInt("want", 0);
        attempt.attachedImages = o.optInt("got", 0);
        attempt.visualDuplicates = o.optInt("vdup", 0);
        attempt.sourcesReceived = o.optInt("src", 0);
        attempt.pagesAttempted = o.optInt("tried", 0);
        attempt.pageBudget = o.optInt("budget", 0);
        attempt.discoveryHintsConsidered = o.optInt("hint_n", 0);
        attempt.discoveryHintsUsed = o.optInt("hint_u", 0);
        attempt.outcome = outcome(o.optString("out", "INCOMPLETE"));
        JSONArray pages = o.optJSONArray("pages");
        if (pages == null) return attempt;
        for (int i = 0; i < pages.length() && i < MAX_PAGES_PER_ATTEMPT; i++) {
            JSONObject p = pages.optJSONObject(i);
            if (p == null) continue;
            PageRecord page = attempt.page();
            page.host = p.optString("h", "");
            page.discoveryHint = p.optBoolean("hint", false);
            page.path = p.optString("p", "");
            page.fetchAllowed = p.optBoolean("ok", true);
            page.httpStatus = p.optInt("s", 0);
            page.contentType = p.optString("ct", "");
            page.bytesRead = p.optInt("b", 0);
            page.redirects = p.optInt("r", 0);
            page.previewCandidates = p.optInt("pc", 0);
            page.articleCandidates = p.optInt("ac", 0);
            page.reason = reason(p.optString("why", "NONE"));
            JSONArray candidates = p.optJSONArray("c");
            if (candidates == null) continue;
            for (int j = 0; j < candidates.length() && j < MAX_CANDIDATES_PER_PAGE; j++) {
                JSONObject c = candidates.optJSONObject(j);
                if (c == null) continue;
                CandidateRecord candidate = page.candidate();
                candidate.origin = origin(c.optString("o", "ARTICLE_IMG"));
                candidate.host = c.optString("h", "");
                candidate.path = c.optString("p", "");
                candidate.score = c.optInt("sc", 0);
                candidate.declaredWidth = c.optInt("dw", 0);
                candidate.declaredHeight = c.optInt("dh", 0);
                candidate.fetched = c.optBoolean("f", false);
                candidate.httpStatus = c.optInt("s", 0);
                candidate.mime = c.optString("m", "");
                candidate.bytesRead = c.optInt("b", 0);
                candidate.redirects = c.optInt("r", 0);
                candidate.decodedWidth = c.optInt("w", 0);
                candidate.decodedHeight = c.optInt("ht", 0);
                candidate.visualId = c.optString("vid", "");
                candidate.subjectScore = c.optInt("ss", 0);
                candidate.structure = c.optInt("st", 0);
                candidate.standing = standing(c.optString("sd", "PROVISIONAL"));
                candidate.reason = reason(c.optString("why", "NONE"));
            }
        }
        return attempt;
    }

    private static Intent intent(String name) {
        try { return Intent.valueOf(name); } catch (Exception ignored) { return Intent.NONE; }
    }

    private static Provenance provenance(String name) {
        try { return Provenance.valueOf(name); } catch (Exception ignored) { return Provenance.NONE; }
    }

    private static Outcome outcome(String name) {
        try { return Outcome.valueOf(name); } catch (Exception ignored) { return Outcome.INCOMPLETE; }
    }

    private static Reason reason(String name) {
        try { return Reason.valueOf(name); } catch (Exception ignored) { return Reason.NONE; }
    }

    private static RichAnswerCandidateQuality.Standing standing(String name) {
        try {
            return RichAnswerCandidateQuality.Standing.valueOf(name);
        } catch (Exception ignored) {
            return RichAnswerCandidateQuality.Standing.PROVISIONAL;
        }
    }

    private static RichAnswerArticleImages.Origin origin(String name) {
        try {
            return RichAnswerArticleImages.Origin.valueOf(name);
        } catch (Exception ignored) {
            return RichAnswerArticleImages.Origin.ARTICLE_IMG;
        }
    }
}
