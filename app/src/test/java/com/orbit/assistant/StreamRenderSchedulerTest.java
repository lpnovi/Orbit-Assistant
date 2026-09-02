package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How often a streaming answer is allowed to redraw, asserted as a rule rather than as a stopwatch.
 *
 * <p>The scheduler exists because a provider emits far more deltas than a screen can usefully
 * show: a long answer arrives as hundreds of fragments, and re-parsing and rebuilding the response
 * for each one is layout thrash the user sees as stutter. But rate-limiting is also the easiest
 * place to lose text — drop the trailing fragment and the last words of an answer never appear.
 *
 * <p>Both halves are checked here against a virtual clock and a virtual poster, so the tests are
 * exact and never flaky: no sleeping, no wall-clock tolerance, and a thousand fragments cost
 * nothing to push through.
 */
public final class StreamRenderSchedulerTest {

    /** A clock the test moves by hand. */
    private static final class FakeClock implements StreamRenderScheduler.Clock {
        long now;
        @Override public long now() { return now; }
    }

    /**
     * A poster that holds callbacks until they are actually due.
     *
     * <p>The delay is honoured rather than ignored, which matters: a harness that ran every
     * callback the moment it was posted would render on every fragment and then report that the
     * scheduler coalesces nothing. What is being tested is the pacing, so the fake has to model a
     * real {@code Handler} closely enough for the pacing to exist.
     */
    private final class FakePoster implements StreamRenderScheduler.Poster {
        final Map<Runnable, Long> pending = new LinkedHashMap<>();
        int posts;

        @Override public void postDelayed(Runnable action, long delayMs) {
            pending.put(action, clock.now + delayMs);
            posts++;
        }

        @Override public void cancel(Runnable action) { pending.remove(action); }

        /** Runs everything whose delay has elapsed, the way a Handler eventually would. */
        void run() {
            List<Runnable> due = new ArrayList<>();
            for (Map.Entry<Runnable, Long> entry : pending.entrySet()) {
                if (entry.getValue() <= clock.now) due.add(entry.getKey());
            }
            for (Runnable action : due) pending.remove(action);
            for (Runnable action : due) action.run();
        }

        /** Runs everything outstanding regardless of when it was due. */
        void runAll() {
            List<Runnable> due = new ArrayList<>(pending.keySet());
            pending.clear();
            for (Runnable action : due) action.run();
        }
    }

    private final FakeClock clock = new FakeClock();
    private final FakePoster poster = new FakePoster();
    private final List<String> rendered = new ArrayList<>();

    private StreamRenderScheduler scheduler() {
        return new StreamRenderScheduler(clock, poster, rendered::add);
    }

    // ---- responsiveness ----------------------------------------------------------------------------

    /** The first fragment draws at once. A visible pause at the start would read as Orbit lagging. */
    @Test public void thefirstFragmentRendersImmediately() {
        StreamRenderScheduler scheduler = scheduler();
        scheduler.offer("Hel");
        assertEquals(1, rendered.size());
        assertEquals("Hel", rendered.get(0));
    }

    /** Fragments spaced beyond the interval each draw immediately. */
    @Test public void fragmentsBeyondTheIntervalRenderImmediately() {
        StreamRenderScheduler scheduler = scheduler();
        scheduler.offer("one");
        clock.now += StreamRenderScheduler.INTERVAL_MS;
        scheduler.offer("one two");
        clock.now += StreamRenderScheduler.INTERVAL_MS;
        scheduler.offer("one two three");
        assertEquals(3, rendered.size());
        assertEquals("one two three", rendered.get(2));
    }

    // ---- coalescing ---------------------------------------------------------------------------------

    /**
     * The headline property: many fragments, far fewer renders.
     *
     * <p>A thousand deltas inside one interval must not become a thousand parses. They become one
     * immediate draw and one trailing draw carrying the newest text.
     */
    @Test public void amessOfFragmentsInsideOneIntervalCollapsesToTwoRenders() {
        StreamRenderScheduler scheduler = scheduler();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            text.append('x');
            scheduler.offer(text.toString());
        }
        assertEquals("only the first fragment drew immediately", 1, rendered.size());
        poster.runAll();
        assertEquals("and one trailing pass carried the rest", 2, rendered.size());
        assertEquals("which held the newest text", text.toString(), rendered.get(1));
    }

    /** Expensive passes stay far below the delta count across a realistic stream. */
    @Test public void renderCountStaysFarBelowDeltaCount() {
        StreamRenderScheduler scheduler = scheduler();
        StringBuilder text = new StringBuilder();
        // 600 fragments arriving roughly 5ms apart: about three seconds of a long answer.
        for (int i = 0; i < 600; i++) {
            text.append("word ");
            scheduler.offer(text.toString());
            clock.now += 5;
            poster.run();
        }
        scheduler.flush();
        assertTrue("600 deltas must not mean 600 presentation passes, was " + rendered.size(),
                rendered.size() < 120);
        assertEquals("and the newest text always wins",
                text.toString(), rendered.get(rendered.size() - 1));
    }

    /** Only one callback is ever outstanding, so no backlog can build up. */
    @Test public void onlyOneDeferredRenderIsEverOutstanding() {
        StreamRenderScheduler scheduler = scheduler();
        scheduler.offer("a");
        for (int i = 0; i < 50; i++) scheduler.offer("a" + i);
        assertEquals("one deferred pass, however many fragments arrived", 1, poster.pending.size());
        assertEquals(1, poster.posts);
    }

    /** Nothing is lost: the text held back is exactly the newest offered. */
    @Test public void thenewestTextAlwaysWins() {
        StreamRenderScheduler scheduler = scheduler();
        scheduler.offer("first");
        scheduler.offer("first second");
        scheduler.offer("first second third");
        poster.runAll();
        assertEquals("first second third", rendered.get(rendered.size() - 1));
    }

    // ---- completion ------------------------------------------------------------------------------------

    /** Completion draws whatever is still held, rather than leaving the last words waiting. */
    @Test public void flushDrawsThePendingTailImmediately() {
        StreamRenderScheduler scheduler = scheduler();
        scheduler.offer("start");
        scheduler.offer("start and the rest of it");
        assertEquals(1, rendered.size());
        scheduler.flush();
        assertEquals(2, rendered.size());
        assertEquals("start and the rest of it", rendered.get(1));
    }

    /** A flush with nothing held draws nothing. */
    @Test public void flushWithNothingPendingDrawsNothing() {
        StreamRenderScheduler scheduler = scheduler();
        scheduler.offer("only");
        scheduler.flush();
        assertEquals(1, rendered.size());
    }

    /** After finishing there is no callback left and no text held. */
    @Test public void finishingReleasesEverything() {
        StreamRenderScheduler scheduler = scheduler();
        scheduler.offer("a");
        scheduler.offer("ab");
        assertTrue(scheduler.hasScheduledWork());
        scheduler.finish();
        assertTrue(scheduler.isFinished());
        assertFalse("no pending render may outlive the stream", scheduler.hasScheduledWork());
        assertFalse(scheduler.hasPendingText());
        assertTrue("and the poster is left holding nothing", poster.pending.isEmpty());
    }

    /** A finished scheduler is inert: late deltas cannot draw anything. */
    @Test public void afinishedSchedulerIgnoresLateDeltas() {
        StreamRenderScheduler scheduler = scheduler();
        scheduler.offer("live");
        scheduler.finish();
        int before = rendered.size();
        scheduler.offer("late");
        scheduler.flush();
        poster.runAll();
        assertEquals("a stopped or completed request may not reach the screen",
                before, rendered.size());
    }

    /** A deferred pass that lands after finishing draws nothing. */
    @Test public void adeferredPassAfterFinishingIsInert() {
        StreamRenderScheduler scheduler = scheduler();
        scheduler.offer("a");
        scheduler.offer("ab");
        // The stream ends while a trailing pass is still outstanding; the poster is not cleared by
        // the world, only by the scheduler, so this proves the callback itself is guarded.
        List<Runnable> outstanding = new ArrayList<>(poster.pending.keySet());
        scheduler.finish();
        int before = rendered.size();
        for (Runnable action : outstanding) action.run();
        assertEquals(before, rendered.size());
    }
}
