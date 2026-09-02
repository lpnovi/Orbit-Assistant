package com.orbit.assistant;

/**
 * How often a streaming answer is allowed to redraw itself.
 *
 * <p>A provider emits deltas far faster than a screen can usefully change: a long answer arrives as
 * hundreds of fragments, sometimes several in the same frame. Rendering each one would mean
 * re-parsing the whole response and rebuilding views hundreds of times for an answer the user reads
 * once — layout thrash, garbage, and a conversation that visibly stutters while it scrolls.
 *
 * <p>So text is accumulated the instant it arrives and the <em>presentation</em> is rate-limited.
 * The first fragment renders immediately, because a visible delay at the start of an answer is the
 * one thing that would read as Orbit being slow; after that a render happens at most once per
 * {@link #INTERVAL_MS}. Anything that arrives inside a window is not dropped — it is held and
 * rendered by a single trailing pass, so the newest text always wins and nothing is lost.
 *
 * <p>The scheduling is expressed against an injected clock and poster rather than against
 * {@code SystemClock} and a {@code Handler}. That is what makes the coalescing rule testable as a
 * rule: a test can push a thousand fragments through a virtual clock and assert how many renders
 * actually happened, with no sleeping and nothing flaky about it.
 */
public final class StreamRenderScheduler {

    /**
     * The gap between presentation passes while content is actively arriving.
     *
     * <p>50ms is twenty updates a second: comfortably faster than anyone reads, comfortably slower
     * than a provider emits, and close enough to a frame boundary that the text appears to flow
     * rather than to tick. Lower than this buys nothing a reader can perceive and costs a parse;
     * much higher starts to feel like typing lag.
     */
    public static final long INTERVAL_MS = 50L;

    /** Where the scheduler gets the time from. */
    public interface Clock { long now(); }

    /** How the scheduler asks for a deferred render. */
    public interface Poster {
        void postDelayed(Runnable action, long delayMs);
        void cancel(Runnable action);
    }

    /** What actually redraws, given the newest complete text. */
    public interface Renderer { void render(String text); }

    private final Clock clock;
    private final Poster poster;
    private final Renderer renderer;
    private final long intervalMs;

    private String pending;
    private boolean hasPending;
    private long lastRenderAt = Long.MIN_VALUE;
    private boolean scheduled;
    private boolean finished;

    private final Runnable flushPending = this::renderPending;

    public StreamRenderScheduler(Clock clock, Poster poster, Renderer renderer) {
        this(clock, poster, renderer, INTERVAL_MS);
    }

    public StreamRenderScheduler(Clock clock, Poster poster, Renderer renderer, long intervalMs) {
        this.clock = clock;
        this.poster = poster;
        this.renderer = renderer;
        this.intervalMs = Math.max(0L, intervalMs);
    }

    /**
     * Accepts the newest complete text of the answer so far.
     *
     * <p>Cumulative, matching what {@link OrbitRequestManager} dispatches: each delta carries the
     * whole partial answer, so the newest simply replaces the last. That is also why holding one
     * pending string is enough and no queue is needed — there is nothing to queue, only a most
     * recent value, and an unbounded backlog is therefore impossible by construction.
     */
    public void offer(String text) {
        if (finished) return;
        pending = text;
        hasPending = true;
        long now = clock.now();
        if (lastRenderAt == Long.MIN_VALUE || now - lastRenderAt >= intervalMs) {
            renderPending();
            return;
        }
        if (scheduled) return;
        scheduled = true;
        poster.postDelayed(flushPending, Math.max(1L, intervalMs - (now - lastRenderAt)));
    }

    /**
     * Renders whatever is still held, immediately.
     *
     * <p>Called when the answer completes or is stopped. Without it the last fragment of every
     * response would be left sitting in the pending slot for up to one interval, which is exactly
     * the moment the user is looking hardest at the text.
     */
    public void flush() {
        if (finished) return;
        cancelScheduled();
        if (hasPending) renderPending();
    }

    /**
     * Ends the stream and releases everything it was holding.
     *
     * <p>After this the scheduler accepts nothing and has no callback outstanding, so a delta from
     * a request that has already finished cannot reach a screen, and a destroyed Activity cannot be
     * kept alive by a pending render.
     */
    public void finish() {
        finished = true;
        cancelScheduled();
        pending = null;
        hasPending = false;
    }

    /** True once {@link #finish()} has run. */
    public boolean isFinished() { return finished; }

    /** True while a deferred render is outstanding. Asserted by tests after completion. */
    public boolean hasScheduledWork() { return scheduled; }

    /** True while text has arrived that has not yet been drawn. */
    public boolean hasPendingText() { return hasPending; }

    private void cancelScheduled() {
        if (!scheduled) return;
        scheduled = false;
        poster.cancel(flushPending);
    }

    private void renderPending() {
        scheduled = false;
        if (finished || !hasPending) return;
        String text = pending;
        hasPending = false;
        pending = null;
        lastRenderAt = clock.now();
        renderer.render(text == null ? "" : text);
    }
}
