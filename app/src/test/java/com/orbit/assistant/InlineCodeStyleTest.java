package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
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

    // ---- punctuation ----------------------------------------------------------------------------

    /** The pill a rendered sentence actually produced, so these exercise the real wiring. */
    private InlineCodeSpan pillIn(String markdown) {
        InlineCodeSpan[] pills = spans(render(markdown, UiKit.SURFACE), InlineCodeSpan.class);
        assertEquals("expected exactly one pill in: " + markdown, 1, pills.length);
        return pills[0];
    }

    private static Paint prosePaint() {
        Paint paint = new Paint();
        paint.setTextSize(40f);
        return paint;
    }

    private int measured(InlineCodeSpan pill, String text) {
        return pill.getSize(prosePaint(), text, 0, text.length(), null);
    }

    /**
     * The reported case. A pill measures and draws itself, so its trailing padding is what decides
     * where the next character starts. With one padding value on both sides, the full stop after
     * {@code `requestId`} began a word-space away from the last letter, and the sentence read as
     * "requestId ." on the device.
     */
    @Test public void punctuationAfterACodeTermSitsAgainstIt() {
        for (String tail : new String[]{".", ",", ":", ";", "!", "?", ")", "]", "}",
                "’", "”", "%", "/", "-"}) {
            InlineCodeSpan attached = pillIn("Keyed by `requestId`" + tail);
            assertTrue("a '" + tail + "' must not be held off by a word-space of padding",
                    attached.trailPadding() < attached.leadPadding());
        }
    }

    /** Brackets and quotes that open onto a code term close up on the other side. */
    @Test public void punctuationBeforeACodeTermSitsAgainstItToo() {
        for (String head : new String[]{"(", "[", "{", "‘", "“", "/", "-"}) {
            InlineCodeSpan attached = pillIn("Keyed by " + head + "`requestId`");
            assertTrue("a '" + head + "' must not be held off by a word-space of padding",
                    attached.leadPadding() < attached.trailPadding());
        }
        InlineCodeSpan both = pillIn("Keyed by (`requestId`).");
        assertEquals("a term wrapped in brackets tightens on both sides",
                both.leadPadding(), both.trailPadding(), 0.001f);
    }

    /**
     * The other half of the fix, and the reason this is not "remove the horizontal padding". A
     * pill in ordinary prose still needs its full breathing room, or the fill crowds the glyphs.
     */
    @Test public void aCodeTermInProseKeepsItsFullBreathingRoom() {
        InlineCodeSpan prose = pillIn("The `READY` state is next.");
        InlineCodeSpan punctuated = pillIn("The next state is `READY`.");
        assertEquals("prose on both sides is the unmodified case",
                prose.leadPadding(), prose.trailPadding(), 0.001f);
        assertTrue("tightening must be a reduction from the prose value, not the prose value",
                punctuated.trailPadding() < prose.trailPadding());
        assertEquals("the side that still meets prose is untouched",
                prose.leadPadding(), punctuated.leadPadding(), 0.001f);
    }

    /** Tightened, never removed: a zero-padding pill would print its fill over the glyphs. */
    @Test public void noSideOfThePillEverLosesItsPaddingEntirely() {
        for (String markdown : new String[]{
                "See `READY`.", "See (`READY`)!", "`READY`,", "-`READY`-",
                "See `READY` next", "`READY`"}) {
            InlineCodeSpan pill = pillIn(markdown);
            assertTrue(markdown + ": leading padding must survive", pill.leadPadding() >= 1f);
            assertTrue(markdown + ": trailing padding must survive", pill.trailPadding() >= 1f);
        }
    }

    /** A run that starts or ends the line has no neighbour, and keeps the prose value. */
    @Test public void aRunAtTheEdgeOfALineKeepsTheProseValue() {
        InlineCodeSpan alone = pillIn("`READY`");
        InlineCodeSpan prose = pillIn("The `READY` state is next.");
        assertEquals(prose.leadPadding(), alone.leadPadding(), 0.001f);
        assertEquals(prose.trailPadding(), alone.trailPadding(), 0.001f);
    }

    /** Letters, digits, spaces and nothing at all are prose, and keep the prose value. */
    @Test public void onlyPunctuationCountsAsAttached() {
        char[] ordinary = {'a', 'Z', '0', ' ', InlineCodeSpan.NOTHING};
        for (char c : ordinary) {
            assertFalse(c + " is not sentence punctuation", InlineCodeSpan.closesAgainstCode(c));
            assertFalse(c + " does not open onto a term", InlineCodeSpan.opensAgainstCode(c));
        }
        assertTrue(InlineCodeSpan.closesAgainstCode('.'));
        assertTrue(InlineCodeSpan.opensAgainstCode('('));
        assertFalse("an opening bracket does not close against the term before it",
                InlineCodeSpan.closesAgainstCode('('));
        assertFalse("a full stop does not open onto the term after it",
                InlineCodeSpan.opensAgainstCode('.'));
    }

    // ---- measurement -----------------------------------------------------------------------------

    /**
     * The tightening has to reach the measured width, because that width is the whole mechanism:
     * the layout places the next character at the pill's trailing edge and nowhere else.
     */
    @Test public void theTighteningIsVisibleInTheMeasuredWidth() {
        InlineCodeSpan prose = pillIn("The `READY` state is next.");
        InlineCodeSpan punctuated = pillIn("The next state is `READY`.");
        assertTrue("a pill followed by a full stop must measure narrower",
                measured(punctuated, "READY") < measured(prose, "READY"));
    }

    /**
     * Measurement and drawing must agree exactly. They now share one width, so the fill's trailing
     * edge and the next character's origin cannot end up a sub-pixel apart.
     */
    @Test public void theMeasuredWidthIsStableAndPadded() {
        InlineCodeSpan pill = pillIn("Keyed by `requestId`.");
        int first = measured(pill, "requestId");
        assertEquals("the width must be stable across calls", first, measured(pill, "requestId"));
        assertTrue("padding is part of the measured width",
                first >= Math.round(pill.leadPadding() + pill.trailPadding()));
    }

    /**
     * A paragraph containing inline code sits on exactly the same line rhythm as one without it.
     * The pill is inset within the line box rather than pushing it open, so it must leave the font
     * metrics it is handed completely alone.
     */
    @Test public void aPillNeverChangesTheLineHeight() {
        InlineCodeSpan pill = pillIn("Keyed by `requestId`.");
        Paint.FontMetricsInt metrics = new Paint.FontMetricsInt();
        metrics.ascent = -30;
        metrics.descent = 8;
        metrics.top = -34;
        metrics.bottom = 10;
        metrics.leading = 2;
        pill.getSize(prosePaint(), "requestId", 0, 9, metrics);
        assertEquals("line height must be untouched", -30, metrics.ascent);
        assertEquals(8, metrics.descent);
        assertEquals(-34, metrics.top);
        assertEquals(10, metrics.bottom);
        assertEquals(2, metrics.leading);
    }

    /** Punctuation spacing is a property of the run, not of the selected font. */
    @Test public void theTighteningHoldsForEveryOrbitFont() {
        for (String font : new String[]{"orbit_default", "times_new_roman", "monospace",
                "condensed", "light"}) {
            Prefs.get(context).edit().putString(Prefs.APP_FONT, font).commit();
            InlineCodeSpan punctuated = pillIn("The next state is `READY`.");
            InlineCodeSpan prose = pillIn("The `READY` state is next.");
            assertTrue(font + ": a full stop must still sit against the term",
                    punctuated.trailPadding() < prose.trailPadding());
            assertEquals(font + ": the prose side is unaffected",
                    prose.leadPadding(), punctuated.leadPadding(), 0.001f);
        }
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
