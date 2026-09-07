package com.orbit.assistant;

import android.content.Context;
import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Finds the picture that belongs to an answer, after the answer has already been delivered.
 *
 * <p><b>Text is never made to wait.</b> That is the whole shape of this class. Discovery starts
 * once a request has won its completion claim and the answer is already written, streamed and on
 * screen; it then runs on its own thread, and whatever it finds is attached afterwards. An answer
 * whose picture takes four seconds is an answer the user read four seconds ago, and an answer whose
 * picture never resolves at all is simply an answer. Nothing here can slow a response down, fail
 * one, or change a word of one.
 *
 * <p><b>Ownership is checked, not assumed.</b> A discovery is started only inside the completion
 * gate, so a stopped or superseded request never begins one. When it finishes, the result is
 * attached by matching the answer's exact text through
 * {@link ConversationStore#attachRichImages}, which is what keeps a late result from a request the
 * user has moved past away from whatever answer is on screen now. A cancelled request is checked
 * again at delivery, because a Stop can land while the fetch is in flight.
 *
 * <p><b>Nothing is sent outward.</b> The only things that leave this device are ordinary bounded
 * GETs to pages the answer already cited and to the pictures those pages declare. No conversation
 * text, no prompt, no Vault content, no identifiers and no cookies travel with them; the question
 * that decided whether to look at all was answered locally by {@link RichAnswerRelevance}.
 */
public final class RichAnswerCoordinator {

    /**
     * How many cited pages one answer's discovery will read metadata from.
     *
     * <p>Small on purpose. Reading six pages to choose one picture would be six requests the user
     * did not ask for, and the pages a search cites are ordered by relevance already, so the first
     * few are where a useful picture is.
     */
    static final int MAX_PAGES_EXAMINED = 3;

    /**
     * One thread. Rich media is the least important work Orbit does, and it is the work most
     * likely to sit blocked on somebody else's slow web server, so it gets exactly enough capacity
     * to make progress and none to compete with anything that matters.
     */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "orbit-rich-answer");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    /** Told when an answer already on screen gains a picture. Display only. */
    public interface Listener {
        void onRichImagesAttached(String conversationId);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private RichAnswerCoordinator() {}

    public static void addListener(Listener listener) {
        if (listener != null && !LISTENERS.contains(listener)) LISTENERS.add(listener);
    }

    public static void removeListener(Listener listener) {
        if (listener != null) LISTENERS.remove(listener);
    }

    /**
     * Starts looking for a picture for one completed answer. Returns immediately.
     *
     * <p>Call this from inside a completion gate, after the answer has been persisted. It declines
     * quietly whenever there is nothing to do - no cited pages, a provider without hosted search, a
     * question a picture would not help, or a user who has turned rich answers off - so the caller
     * never has to know any of those rules.
     */
    public static void discover(Context context, String conversationId, String requestId,
                                String prompt, AssistantReply reply) {
        if (context == null || reply == null) return;
        if (conversationId == null || conversationId.trim().isEmpty()) return;
        String answer = reply.text == null ? "" : reply.text.trim();
        if (answer.isEmpty() || reply.sourceUrls.isEmpty()) return;
        if (!enabled(context)) return;
        if (!AiProviders.active(context).capabilities().richWebMedia) return;
        if (!RichAnswerRelevance.answerWantsImage(prompt, answer)) return;

        Context app = context.getApplicationContext();
        String chat = conversationId.trim();
        String owner = requestId == null ? "" : requestId.trim();
        List<String> pages = new ArrayList<>(reply.sourceUrls);
        int wanted = RichAnswerRelevance.maxImagesFor(prompt);
        int anchor = RichAnswerPlacement.placementFor(answer);

        EXECUTOR.execute(() -> {
            try {
                List<RichAnswerImage> found = resolve(app, pages, wanted, anchor);
                if (found.isEmpty()) return;
                // Asked again here rather than only at the start: a Stop can land while a page
                // and an image are being fetched, and a stopped request must not decorate the
                // partial answer it left behind.
                if (!owner.isEmpty() && OrbitRequestManager.isCancelled(app, owner)) return;
                if (!ConversationStore.attachRichImages(app, chat, answer, found)) return;
                for (Listener listener : LISTENERS) {
                    try { listener.onRichImagesAttached(chat); } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {
                // A failed lookup is never a failed answer. The text stands exactly as it was.
            }
        });
    }

    /**
     * Whether the user has left sourced pictures on.
     *
     * <p>Defaults to on, and reads the preference at discovery time rather than caching it, so
     * turning it off stops the next answer from looking rather than the one after that.
     */
    public static boolean enabled(Context context) {
        return context != null && Prefs.richAnswers(context);
    }

    /**
     * The best pictures the cited pages declare, in the order they should be drawn.
     *
     * <p>Blocking. Each page contributes at most one picture, so two images always means two
     * different sources rather than two crops of the same hero graphic. A page that declares
     * nothing, refuses the fetch, or turns out to be a PDF simply contributes nothing.
     */
    static List<RichAnswerImage> resolve(Context context, List<String> pages, int wanted, int anchor) {
        List<RichAnswerImage> found = new ArrayList<>();
        if (pages == null || pages.isEmpty()) return found;
        int limit = Math.max(1, Math.min(wanted, RichAnswerImage.MAX_PER_MESSAGE));
        Set<String> seenImages = new LinkedHashSet<>();
        int examined = 0;
        for (String page : pages) {
            if (found.size() >= limit || examined >= MAX_PAGES_EXAMINED) break;
            if (!RichAnswerUrlPolicy.isFetchablePageUrl(page)) continue;
            examined++;
            RichAnswerPageMetadata.Preview preview = RichAnswerPageFetcher.fetchPreview(page);
            if (!preview.hasImage()) continue;

            String best = "";
            int bestScore = Integer.MIN_VALUE;
            for (String candidate : preview.imageUrls) {
                if (seenImages.contains(candidate)) continue;
                int score = RichAnswerRelevance.score(candidate, page, preview.description, true);
                if (score > bestScore) { bestScore = score; best = candidate; }
            }
            if (best.isEmpty() || bestScore < 0) continue;

            // Fetched before it is committed to, because a declared preview image can be a
            // 40-pixel logo, a placeholder, or something that is not an image at all - and the
            // decoded bounds are the only honest answer to which of those it is.
            Bitmap bitmap = RemoteImageLoader.fetchForRichAnswer(context, best);
            if (bitmap == null) continue;
            seenImages.add(best);

            String caption = captionFor(preview);
            RichAnswerImage image = RichAnswerImage.webSource(best, page, caption, caption, anchor);
            if (image.isUsable()) found.add(image);
        }
        return found;
    }

    /**
     * The line drawn under a picture: the page's own words for it, trimmed to one clause.
     *
     * <p>Never Orbit's words and never the model's. A caption that Orbit wrote would be Orbit
     * asserting something about a photograph it has not looked at; the page's own alt text or title
     * is a claim its author made, which is exactly what an attribution should carry.
     */
    static String captionFor(RichAnswerPageMetadata.Preview preview) {
        if (preview == null) return "";
        String candidate = preview.description.isEmpty() ? preview.title : preview.description;
        String text = candidate == null ? "" : candidate.replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) return "";
        // A publisher's title often ends with its own name after a separator, which is branding
        // rather than description and reads badly beside the domain Orbit already shows. The
        // minimum length is what stops "A | Some Very Long Publisher" collapsing to one letter:
        // below it, the part before the separator is too short to have been the real title.
        for (String separator : new String[]{" | ", " - ", " · ", " — "}) {
            int at = text.lastIndexOf(separator);
            if (at >= 8) { text = text.substring(0, at).trim(); break; }
        }
        if (text.length() <= 110) return text;
        String clipped = text.substring(0, 110);
        int space = clipped.lastIndexOf(' ');
        if (space > 60) clipped = clipped.substring(0, space);
        return clipped.trim();
    }
}
