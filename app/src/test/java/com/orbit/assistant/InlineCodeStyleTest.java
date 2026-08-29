package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Color;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * How an inline `term` looks inside a reply.
 *
 * <p>A long architecture answer on the device came back full of inline code — {@code READY},
 * {@code RUNNING}, {@code requestId} — and each one was drawn as a square grey block the full
 * height of the line. The semantics were right; the treatment was a flat
 * {@code BackgroundColorSpan} in one hard-coded slate, which paints the whole line box and takes
 * no notice of the bubble it is sitting on.
 *
 * <p>Two properties matter and are tested here. The pill must be visible against every bubble
 * Orbit can produce — classic, accent, pastel, AMOLED, and any colour the user picks — without any
 * of them being enumerated. And it must not dominate: a sentence with four code terms in it still
 * has to read as a sentence.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class InlineCodeStyleTest {

    /** Every fill an assistant bubble can actually have, plus the extremes. */
    private static final int[] SURFACES = {
            UiKit.SURFACE,                  // classic assistant bubble
            UiKit.SURFACE_2,
            Color.BLACK,                    // AMOLED
            Color.WHITE,
            UiKit.DEFAULT_ACCENT,
            UiKit.BLURPLE,
            UiKit.PASTEL_PINK,
            UiKit.PASTEL_BLUE,
            Color.rgb(87, 214, 146),        // mint
            Color.rgb(120, 120, 120),       // an awkward mid grey
    };

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        UiKit.syncTheme(context);
    }

    private Spanned render(String markdown, int surface) {
        return (Spanned) OrbitMarkdown.renderInline(context, markdown, UiKit.onBubble(surface),
                surface);
    }

    private <T> T[] spans(Spanned text, Class<T> type) {
        return text.getSpans(0, text.length(), type);
    }

    // ---- the pill -------------------------------------------------------------------------------

    @Test public void shortInlineCodeBecomesARoundedPill() {
        Spanned rendered = render("The request moves to `READY` next.", UiKit.SURFACE);
        InlineCodeSpan[] pills = spans(rendered, InlineCodeSpan.class);
        assertEquals(1, pills.length);
        assertEquals("the backticks are formatting, not content",
                "The request moves to READY next.", rendered.toString());
        assertEquals(21, rendered.getSpanStart(pills[0]));
        assertEquals(26, rendered.getSpanEnd(pills[0]));
    }

    @Test public void severalCodeTermsInOneSentenceEachGetTheirOwnPill() {
        Spanned rendered = render("It goes `READY` then `RUNNING` then `SUCCEEDED`, keyed by "
                + "`requestId`.", UiKit.SURFACE);
        assertEquals(4, spans(rendered, InlineCodeSpan.class).length);
        assertFalse("the old flat rectangle must be gone",
                spans(rendered, BackgroundColorSpan.class).length > 0);
    }

    @Test public void inlineCodeIsSetInAMonospaceFace() {
        Spanned rendered = render("Keyed by `requestId`.", UiKit.SURFACE);
        assertEquals("the pill draws its own monospace text",
                1, spans(rendered, InlineCodeSpan.class).length);
    }

    /**
     * A replacement span cannot wrap, so a run long enough to need wrapping keeps the flat
     * treatment rather than being pushed onto its own line and overflowing the bubble.
     */
    @Test public void longRunsKeepTheWrappableTreatment() {
        String longRun = "com.orbit.assistant.OrbitRequestManager.completeIfNotCancelled";
        assertTrue(longRun.length() > InlineCodeSpan.MAX_PILL_CHARS);
        Spanned rendered = render("Call `" + longRun + "` for that.", UiKit.SURFACE);
        assertEquals(0, spans(rendered, InlineCodeSpan.class).length);
        assertEquals(1, spans(rendered, BackgroundColorSpan.class).length);
        assertEquals(1, spans(rendered, TypefaceSpan.class).length);
    }

    @Test public void theWrappableFallbackIsAlsoDerivedFromTheSurface() {
        String longRun = "a rather long inline code run that has to be allowed to wrap";
        for (int surface : SURFACES) {
            Spanned rendered = render("See `" + longRun + "` here.", surface);
            BackgroundColorSpan[] fills = spans(rendered, BackgroundColorSpan.class);
            ForegroundColorSpan[] inks = spans(rendered, ForegroundColorSpan.class);
            assertEquals(1, fills.length);
            assertEquals(1, inks.length);
            assertEquals("both treatments share one derivation",
                    UiKit.inlineCodeTint(surface), fills[0].getBackgroundColor());
            assertEquals(UiKit.inlineCodeInk(surface), inks[0].getForegroundColor());
        }
    }

    // ---- the tint --------------------------------------------------------------------------------

    @Test public void theTintIsAlwaysDistinguishableFromItsBubble() {
        for (int surface : SURFACES) {
            int tint = UiKit.inlineCodeTint(surface);
            assertNotEquals("a pill the colour of its bubble is not a pill", surface, tint);
            double separation = UiKit.contrastRatio(tint, surface);
            assertTrue("code must be visible on " + Integer.toHexString(surface)
                    + " (separation " + separation + ")", separation >= 1.12d);
        }
    }

    @Test public void theTintNeverDominatesTheSentence() {
        for (int surface : SURFACES) {
            double separation = UiKit.contrastRatio(UiKit.inlineCodeTint(surface), surface);
            assertTrue("inline code must not shout over the prose on "
                    + Integer.toHexString(surface) + " (separation " + separation + ")",
                    separation <= 2.2d);
        }
    }

    @Test public void codeTextStaysReadableOnItsOwnPill() {
        for (int surface : SURFACES) {
            double ratio = UiKit.contrastRatio(UiKit.inlineCodeInk(surface),
                    UiKit.inlineCodeTint(surface));
            assertTrue("code has to be legible on " + Integer.toHexString(surface)
                    + " (ratio " + ratio + ")", ratio >= 4.5d);
        }
    }

    @Test public void darkBubblesLightenAndBrightBubblesDarken() {
        assertTrue("a dark bubble lifts its code pill toward the light",
                luminance(UiKit.inlineCodeTint(UiKit.SURFACE)) > luminance(UiKit.SURFACE));
        assertTrue("AMOLED true black lifts too, rather than staying invisible",
                luminance(UiKit.inlineCodeTint(Color.BLACK)) > 0d);
        assertTrue("a pastel bubble sinks its code pill instead",
                luminance(UiKit.inlineCodeTint(UiKit.PASTEL_PINK)) < luminance(UiKit.PASTEL_PINK));
        assertTrue(luminance(UiKit.inlineCodeTint(Color.WHITE)) < luminance(Color.WHITE));
    }

    // ---- nothing else changed ---------------------------------------------------------------------

    @Test public void linksBoldItalicsAndStrikethroughStillWork() {
        Spanned rendered = render("See **this** and *that* and [docs](https://example.com) "
                + "with `code`.", UiKit.SURFACE);
        assertEquals(1, spans(rendered, URLSpan.class).length);
        assertEquals(2, spans(rendered, StyleSpan.class).length);
        assertEquals(1, spans(rendered, InlineCodeSpan.class).length);
        assertTrue(rendered.toString().contains("See this and that and docs with code."));
    }

    @Test public void fencedCodeBlocksKeepTheirFlatTreatment() {
        Spanned rendered = (Spanned) OrbitMarkdown.render(context,
                "Here:\n```\nint x = 1;\nint y = 2;\n```\nDone.", UiKit.SURFACE);
        assertEquals("a fenced block is a block, not a row of pills",
                0, spans(rendered, InlineCodeSpan.class).length);
        assertTrue(spans(rendered, BackgroundColorSpan.class).length >= 1);
        assertTrue(rendered.toString().contains("int x = 1;"));
    }

    @Test public void bulletsAndHeadingsStillRenderTheirInlineCode() {
        Spanned rendered = (Spanned) OrbitMarkdown.render(context,
                "# The `READY` state\n- moves to `RUNNING`\n1. then `DONE`", UiKit.SURFACE);
        assertEquals(3, spans(rendered, InlineCodeSpan.class).length);
        assertTrue(rendered.toString().contains("•"));
    }

    @Test public void emptyAndOddInputIsSurvivable() {
        assertEquals(0, OrbitMarkdown.renderInline(context, "", Color.WHITE, UiKit.SURFACE).length());
        assertEquals("``", OrbitMarkdown.renderInline(context, "``", Color.WHITE,
                UiKit.SURFACE).toString());
        assertTrue(InlineCodeSpan.fits("READY"));
        assertFalse(InlineCodeSpan.fits(""));
        assertFalse(InlineCodeSpan.fits(null));
    }

    private static double luminance(int color) {
        return (0.2126 * Color.red(color) + 0.7152 * Color.green(color)
                + 0.0722 * Color.blue(color)) / 255.0;
    }
}
