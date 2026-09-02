package com.orbit.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * One assistant answer, drawn as Orbit draws answers, while it is still being written.
 *
 * <p>This replaces the raw {@code TextView} both surfaces used to show a streaming response in. The
 * problem with that view was not that it was ugly — it was that it was a <em>different thing</em>
 * from the finished answer. The user watched literal {@code ## Heading}, {@code - item} and triple
 * backticks scroll past, and then at completion the whole view was thrown away and a rich tree
 * appeared in its place, so every answer ended with a visible jump between two presentations.
 *
 * <p>Here there is only one presentation, and it grows. The response is cut into blocks by
 * {@link ResponseBlocks} and each block is built by the same {@link OrbitRichResponseRenderer} the
 * finished answer uses, so "streaming" and "final" are the same views at different moments rather
 * than two renderers that have to be kept looking alike.
 *
 * <p>The part that makes this cheap enough to do on every update is the diff. A block whose source
 * has not changed is not rebuilt, not re-laid-out and not touched at all — which is what keeps a
 * code block's Copy control, a link's clickability, a table's horizontal scroll offset and a screen
 * reader's focus alive while the paragraph below them is still arriving. In practice a growing
 * answer only ever rebuilds its last block, and appends a new one when the model starts a new
 * construct.
 */
public final class ProgressiveResponseView extends LinearLayout {

    private final int bubbleFill;
    private final boolean compact;
    /** The signature of each block currently drawn, in order. Never a copy of the answer. */
    private final List<String> drawn = new ArrayList<>();
    private StreamRenderScheduler scheduler;
    /**
     * Whether this answer has already earned the wide layout.
     *
     * <p>One-way on purpose. Width is decided from the text, and the text grows, so a response that
     * re-decided every update would narrow and widen repeatedly as a table or a code fence arrived
     * — the bubble visibly breathing while the user tries to read it. Once an answer has shown it
     * needs the room it keeps it for the rest of the turn.
     */
    private boolean wide;
    /** The last source actually drawn, so a repeated identical update costs nothing. */
    private String lastSource = "";
    private boolean lastStreaming;

    public ProgressiveResponseView(Context context, int bubbleFill, boolean compact) {
        super(context);
        this.bubbleFill = bubbleFill;
        this.compact = compact;
        OrbitRichResponseRenderer.applyBubbleChrome(this, bubbleFill, compact);
        UiKit.watchTypography(this);
    }

    /** True once the answer has grown enough to deserve the full bubble width. */
    public boolean prefersWide() { return wide; }

    /**
     * Draws the answer as it currently stands.
     *
     * @param streaming true while more is expected, which lets the final block stay open rather
     *                  than being forced into a shape the text has not earned yet.
     */
    public void update(String source, boolean streaming) {
        String text = source == null ? "" : source.replace("\r", "");
        // A provider can repeat the same cumulative text, and a flush can land on text already
        // drawn. Neither is a reason to touch the view tree.
        if (text.equals(lastSource) && streaming == lastStreaming && getChildCount() > 0) return;
        lastSource = text;
        lastStreaming = streaming;
        if (!wide && OrbitRichResponseRenderer.prefersWideLayout(text)) wide = true;

        List<ResponseBlocks.Block> blocks;
        try {
            blocks = ResponseBlocks.parse(text, streaming);
        } catch (Throwable failure) {
            // A response is never worth losing to a parser. Falling back to the text itself keeps
            // every word on screen, which is the only guarantee that actually matters here.
            blocks = new ArrayList<>();
        }
        if (blocks.isEmpty() && !text.trim().isEmpty()) {
            blocks = new ArrayList<>();
            blocks.add(ResponseBlocks.paragraph(text, !streaming));
        }
        applyBlocks(blocks);
    }

    /**
     * The blocks, reconciled against what is already on screen.
     *
     * <p>Walks the two lists together: identical blocks are left completely alone, a block whose
     * source has changed is rebuilt in place, and blocks past the end are appended. Only genuinely
     * new blocks animate — a paragraph gaining a word is not an arrival.
     */
    private void applyBlocks(List<ResponseBlocks.Block> blocks) {
        int images = 0;
        for (int i = 0; i < blocks.size(); i++) {
            ResponseBlocks.Block block = blocks.get(i);
            boolean asImage = block.kind == ResponseBlocks.Kind.IMAGE
                    && images < OrbitRichResponseRenderer.maxImages();
            if (block.kind == ResponseBlocks.Kind.IMAGE) images++;

            String signature = block.signature();
            if (i < drawn.size() && signature.equals(drawn.get(i))) continue;

            View view = OrbitRichResponseRenderer.buildBlock(
                    getContext(), block, bubbleFill, compact, asImage);
            LayoutParams lp = OrbitRichResponseRenderer.blockLayout(getContext(),
                    OrbitRichResponseRenderer.spacingFor(block, asImage),
                    OrbitRichResponseRenderer.topSpacingFor(block));
            if (i < drawn.size()) {
                // Replaced in place. No entrance animation: this block was already on screen and
                // re-fading it every time a word lands inside it is exactly the flicker that makes
                // progressive rendering feel worse than plain text.
                removeViewAt(i);
                addView(view, i, lp);
                drawn.set(i, signature);
            } else {
                addView(view, i, lp);
                drawn.add(signature);
                UiKit.enterBlock(view);
            }
        }
        // The answer got structurally shorter, which happens when streamed text resolves into
        // fewer blocks than it briefly looked like. Drop the tail rather than leaving orphans.
        while (drawn.size() > blocks.size()) {
            int last = drawn.size() - 1;
            if (last < getChildCount()) removeViewAt(last);
            drawn.remove(last);
        }
        trimTrailingSpacing();
    }

    /**
     * Block spacing separates blocks and must not hang below the last one.
     *
     * <p>The same rule the completed renderer applies, for the same reason: the bubble already has
     * symmetric vertical padding, so a trailing block margin gives it a visible chin. Reapplied on
     * every update because which block is last keeps changing while an answer grows.
     */
    private void trimTrailingSpacing() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            if (lp == null) continue;
            boolean last = i == getChildCount() - 1;
            int wanted = last ? 0 : lp.bottomMargin;
            if (last && lp.bottomMargin != 0) {
                lp.bottomMargin = 0;
                child.setLayoutParams(lp);
            } else if (!last && wanted != lp.bottomMargin) {
                child.setLayoutParams(lp);
            }
        }
    }

    // ---- streaming ------------------------------------------------------------------------------

    /**
     * Accepts one cumulative delta, coalesced.
     *
     * <p>The scheduler is created on first use and released at {@link #settle}, so a bubble that
     * never streams never allocates one and a finished turn holds nothing.
     */
    public void onDelta(String cumulativeText) {
        scheduler().offer(cumulativeText);
    }

    /**
     * Finishes the answer on the canonical text and releases the stream state.
     *
     * <p>The canonical reply wins over whatever streamed, because a provider can normalise its own
     * output and the stored conversation must match what is on screen. It rarely differs by much,
     * and because the diff only rebuilds blocks that actually changed, reconciling costs a redraw
     * of the last block or two rather than a flash of the whole answer.
     */
    public void settle(String canonicalText) {
        if (scheduler != null) {
            scheduler.finish();
            scheduler = null;
        }
        update(canonicalText, false);
    }

    /** Drops any pending render without drawing. For a surface going away. */
    public void cancelPendingRenders() {
        if (scheduler == null) return;
        scheduler.finish();
        scheduler = null;
    }

    /** True while this bubble still holds stream state. Asserted by tests after completion. */
    public boolean hasStreamState() { return scheduler != null; }

    private StreamRenderScheduler scheduler() {
        if (scheduler == null) {
            Handler handler = new Handler(Looper.getMainLooper());
            scheduler = new StreamRenderScheduler(
                    SystemClock::uptimeMillis,
                    new StreamRenderScheduler.Poster() {
                        @Override public void postDelayed(Runnable action, long delayMs) {
                            handler.postDelayed(action, delayMs);
                        }
                        @Override public void cancel(Runnable action) {
                            handler.removeCallbacks(action);
                        }
                    },
                    text -> update(text, true));
        }
        return scheduler;
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // A bubble taken off screen must not keep a render callback alive against a dead surface.
        cancelPendingRenders();
    }

    /** For tests: the block signatures currently drawn, in order. */
    List<String> drawnSignatures() { return new ArrayList<>(drawn); }
}
