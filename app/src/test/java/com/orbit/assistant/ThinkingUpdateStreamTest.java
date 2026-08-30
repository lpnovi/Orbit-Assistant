package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Turning a token stream into a status line a person can actually read.
 *
 * <p>The behaviour under test is a contract, not a decoration: a reasoning summary arrives a few
 * characters at a time, and rendering that directly would rewrite the line dozens of times a
 * second and fire an accessibility announcement for each rewrite. These assert what is emitted and
 * when, in terms of the stream's own documented rules, and drive the clock explicitly rather than
 * asserting any real-time duration.
 */
public final class ThinkingUpdateStreamTest {

    /** Feeds a whole summary paragraph one small delta at a time, with a clock the test owns. */
    private static List<String> emissions(ThinkingUpdateStream stream, String text,
                                          int chunk, long startMs, long msPerChunk) {
        List<String> out = new ArrayList<>();
        long now = startMs;
        for (int i = 0; i < text.length(); i += chunk) {
            String delta = text.substring(i, Math.min(text.length(), i + chunk));
            String emitted = stream.accept(delta, now);
            if (!emitted.isEmpty()) out.add(emitted);
            now += msPerChunk;
        }
        return out;
    }

    // ---- coalescing --------------------------------------------------------------------------

    /** The whole point: many deltas, very few visible changes. */
    @Test public void aFastTokenStreamProducesFarFewerUpdatesThanTokens() {
        ThinkingUpdateStream stream = new ThinkingUpdateStream();
        String paragraph = "Comparing the possible approaches to the problem and weighing "
                + "which one actually fits the question that was asked.";
        // Three characters every 20ms is roughly how fast a real summary streams.
        List<String> emitted = emissions(stream, paragraph, 3, 1_000L, 20L);
        int deltas = (paragraph.length() + 2) / 3;
        assertTrue("nothing was shown at all", emitted.size() >= 1);
        assertTrue("one update per token is exactly the flicker this exists to prevent: "
                        + emitted.size() + " updates for " + deltas + " deltas",
                emitted.size() <= deltas / 5);
    }

    @Test public void updatesAreNeverCloserTogetherThanTheMinimumInterval() {
        ThinkingUpdateStream stream = new ThinkingUpdateStream();
        // Every delta ends a sentence, so only the interval can hold anything back.
        long now = 1_000L;
        List<Long> emittedAt = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String emitted = stream.accept("Considering option " + i + ". ", now);
            if (!emitted.isEmpty()) emittedAt.add(now);
            now += 50L;
        }
        for (int i = 1; i < emittedAt.size(); i++) {
            assertTrue("two updates landed " + (emittedAt.get(i) - emittedAt.get(i - 1)) + "ms apart",
                    emittedAt.get(i) - emittedAt.get(i - 1) >= ThinkingUpdateStream.MIN_INTERVAL_MS);
        }
    }

    /** The first thing Orbit has to say is not made to wait; only the ones after it are spaced. */
    @Test public void theFirstUpdateIsImmediate() {
        ThinkingUpdateStream stream = new ThinkingUpdateStream();
        assertEquals("Comparing the possible approaches.",
                stream.accept("Comparing the possible approaches.", 0L));
    }

    // ---- coherence ---------------------------------------------------------------------------

    /** A fragment of a word is not a status. */
    @Test public void aShortFragmentIsNotShownUntilItMeansSomething() {
        ThinkingUpdateStream stream = new ThinkingUpdateStream();
        assertEquals("", stream.accept("Comp", 1_000L));
        assertEquals("", stream.accept("aring", 1_020L));
        assertFalse(stream.accept(" the approaches now", 1_040L).isEmpty());
    }

    /**
     * A short phrase is held back while it might still be a fragment, and shown once the paragraph
     * ends and proves it complete.
     */
    @Test public void aShortButCompletePhraseIsShownWhenTheParagraphEnds() {
        ThinkingUpdateStream stream = new ThinkingUpdateStream();
        stream.beginPart();
        assertEquals("too short to mean anything on its own yet", "", stream.accept("Deciding", 1_000L));
        assertEquals("Deciding", stream.finishPart(1_000L));
    }

    /**
     * Reasoning summaries usually open with a short heading. Freezing it once the paragraph moves
     * past turns a growing paragraph into one stable phrase rather than a caption that keeps
     * rewriting itself under the reader.
     */
    @Test public void theOpeningLineIsFrozenOnceTheParagraphMovesPastIt() {
        ThinkingUpdateStream stream = new ThinkingUpdateStream();
        stream.beginPart();
        assertEquals("Comparing the approaches", stream.accept("Comparing the approaches\n", 1_000L));
        // Everything afterwards resolves to the same frozen line, so however much prose follows,
        // the status the user is reading does not move again for this paragraph.
        assertEquals("", stream.accept("Now I will weigh each", 2_000L));
        assertEquals("", stream.accept(" one against the others in detail", 3_000L));
        assertEquals("", stream.finishPart(4_000L));
    }

    /** A paragraph that opens with a blank line has not started yet. */
    @Test public void aBlankOpeningLineDoesNotFreezeAnEmptyHeading() {
        ThinkingUpdateStream stream = new ThinkingUpdateStream();
        stream.beginPart();
        assertEquals("", stream.accept("\n", 1_000L));
        assertEquals("Checking the failure modes",
                stream.accept("Checking the failure modes\n", 2_000L));
    }

    // ---- no repetition -----------------------------------------------------------------------

    @Test public void thesameTextIsNeverEmittedTwiceInARow() {
        ThinkingUpdateStream stream = new ThinkingUpdateStream();
        stream.beginPart();
        assertEquals("Checking the failure modes.", stream.accept("Checking the failure modes.", 1_000L));
        assertEquals("", stream.finishPart(9_000L));
    }

    /** A new paragraph starts clean: the previous one cannot bleed into it. */
    @Test public void anewPartDoesNotInheritThePreviousParagraphsText() {
        ThinkingUpdateStream stream = new ThinkingUpdateStream();
        stream.beginPart();
        stream.accept("Comparing the approaches.", 1_000L);
        stream.beginPart();
        assertEquals("Forming a recommendation.", stream.accept("Forming a recommendation.", 2_000L));
    }

    // ---- bounded -----------------------------------------------------------------------------

    /** A provider that never stops talking must not grow Orbit's memory. */
    @Test public void theBufferAndTheEmittedTextBothStayBounded() {
        ThinkingUpdateStream stream = new ThinkingUpdateStream();
        stream.beginPart();
        String last = "";
        long now = 0L;
        for (int i = 0; i < 500; i++) {
            String emitted = stream.accept("more reasoning text ", now);
            if (!emitted.isEmpty()) last = emitted;
            now += 1_000L;
        }
        assertTrue("emitted text must stay within the display bound",
                last.length() <= ThinkingUpdate.MAX_TEXT_CHARS + 1);
    }
}
