package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * A one-line assistant answer has to sit in the middle of its bubble.
 *
 * <p>On the device, the reply {@code 13.5} had visibly more empty space below the number than
 * above it — a small "chin" — while the user message directly above it looked balanced. The two
 * were never using different fonts, different metrics, or different {@code includeFontPadding}:
 * the user bubble is a plain TextView with symmetric padding, and the assistant bubble is a column
 * of blocks in which <em>every</em> block, including the last, carried a bottom margin. That
 * trailing margin sat underneath the text on top of the bubble's own symmetric padding.
 *
 * <p>These tests assert the structural invariant — space above the content equals space below it —
 * rather than any particular dp value, so the design is free to change and the balance is not.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class AssistantBubbleBalanceTest {

    /** Ascenders, descenders, digits, punctuation, and one that has none of them. */
    private static final String[] SHORT_ANSWERS = {
            "13.5", "Yes", "READY", "gyp", "Hello!", "42%", "Jgq,", "—", "no"
    };

    /** Orbit Default, the serif face the device is set to, and one substantially different. */
    private static final String[] FONTS = {
            "orbit_default", "times_new_roman", "monospace", "condensed", "light"
    };

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        UiKit.syncTheme(context);
    }

    private LinearLayout bubble(String text, boolean compact) {
        return (LinearLayout) OrbitRichResponseRenderer.render(context, text,
                UiKit.assistantBubbleFill(context, UiKit.SURFACE), compact);
    }

    /** Whatever the last visible block adds below itself, on top of the bubble's own padding. */
    private int trailingGap(LinearLayout bubble) {
        for (int i = bubble.getChildCount() - 1; i >= 0; i--) {
            View child = bubble.getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            return lp instanceof LinearLayout.LayoutParams
                    ? ((LinearLayout.LayoutParams) lp).bottomMargin : 0;
        }
        return 0;
    }

    private int leadingGap(LinearLayout bubble) {
        for (int i = 0; i < bubble.getChildCount(); i++) {
            View child = bubble.getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            return lp instanceof LinearLayout.LayoutParams
                    ? ((LinearLayout.LayoutParams) lp).topMargin : 0;
        }
        return 0;
    }

    private void assertBalanced(String message, LinearLayout bubble) {
        assertEquals(message + ": bubble padding must be symmetric",
                bubble.getPaddingTop(), bubble.getPaddingBottom());
        assertEquals(message + ": no space may hang off the bottom of the last block",
                leadingGap(bubble), trailingGap(bubble));
        assertEquals(message + ": space above the text must equal space below it",
                bubble.getPaddingTop() + leadingGap(bubble),
                bubble.getPaddingBottom() + trailingGap(bubble));
    }

    // ---- the reported case -----------------------------------------------------------------------

    @Test public void theShortAnswerFromTheDeviceHasNoChin() {
        assertBalanced("13.5 in full chat", bubble("13.5", false));
        assertBalanced("13.5 in the overlay", bubble("13.5", true));
        assertEquals("block spacing separates blocks; there is only one here",
                0, trailingGap(bubble("13.5", false)));
    }

    @Test public void everyShortAnswerShapeIsBalanced() {
        for (String answer : SHORT_ANSWERS) {
            assertBalanced("full chat: " + answer, bubble(answer, false));
            assertBalanced("overlay: " + answer, bubble(answer, true));
        }
    }

    @Test public void balanceHoldsForEveryOrbitFont() {
        for (String font : FONTS) {
            Prefs.get(context).edit().putString(Prefs.APP_FONT, font).commit();
            for (String answer : SHORT_ANSWERS) {
                assertBalanced(font + " / " + answer, bubble(answer, false));
            }
        }
    }

    @Test public void balanceHoldsAtEveryChatTextSize() {
        String[] sizes = {Prefs.CHAT_TEXT_SMALL, Prefs.CHAT_TEXT_LARGE, Prefs.CHAT_TEXT_EXTRA_LARGE};
        for (String size : sizes) {
            Prefs.get(context).edit().putString(Prefs.CHAT_TEXT_SIZE, size).commit();
            assertBalanced("13.5 at " + size, bubble("13.5", false));
        }
    }

    /**
     * The endings the chin could come back on.
     *
     * <p>The trailing gap is a property of the last block, so what that block <em>is</em> matters.
     * Beta 4 changed how an inline code pill measures itself, which is a last-block concern, so
     * every shape an answer can end on is covered here rather than only the short one that was
     * reported.
     */
    @Test public void everyEndingShapeIsBalanced() {
        String[] endings = {
                "Yes",
                "The request moves to READY and then finishes normally.",
                "Steps:\n\n- first\n- second\n- third",
                "It is keyed by `requestId`.",
                "`READY`",
                "Here:\n\n```\nint x = 1;\n```",
                "Done.",
                "Are you sure?",
                "First paragraph here.\n\nAnd a second one that ends in `code`.",
        };
        for (String ending : endings) {
            assertBalanced("full chat: " + ending, bubble(ending, false));
            assertBalanced("overlay: " + ending, bubble(ending, true));
        }
    }

    /** Inline code is a span inside a block, so it must not add a block of trailing space. */
    @Test public void anAnswerEndingInInlineCodeHasNoChin() {
        assertEquals("a one-block answer has nothing to separate itself from",
                0, trailingGap(bubble("It is keyed by `requestId`.", false)));
        assertEquals(0, trailingGap(bubble("`READY`", false)));
        assertBalanced("inline code at the very end", bubble("It ends on `READY`", false));
    }

    // ---- longer answers must not be squeezed to achieve it ----------------------------------------

    @Test public void multiBlockAnswersKeepTheirInternalSpacing() {
        LinearLayout bubble = bubble("First paragraph here.\n\nSecond paragraph here.\n\n"
                + "Third paragraph here.", false);
        assertTrue("a multi-paragraph answer should still be several blocks",
                bubble.getChildCount() >= 3);
        int spaced = 0;
        for (int i = 0; i < bubble.getChildCount() - 1; i++) {
            ViewGroup.LayoutParams lp = bubble.getChildAt(i).getLayoutParams();
            if (lp instanceof LinearLayout.LayoutParams
                    && ((LinearLayout.LayoutParams) lp).bottomMargin > 0) spaced++;
        }
        assertTrue("paragraphs must still be separated from each other", spaced >= 2);
        assertBalanced("multi-paragraph answer", bubble);
    }

    @Test public void listsAndHeadingsAreBalancedToo() {
        assertBalanced("heading and list",
                bubble("# Summary\n\n- one\n- two\n- three", false));
        assertBalanced("answer ending in a list", bubble("Steps:\n\n1. first\n2. second", false));
        assertBalanced("answer ending in a fenced block",
                bubble("Here:\n\n```\nint x = 1;\n```", false));
    }

    // ---- one shared line rhythm --------------------------------------------------------------------

    /**
     * The user and assistant paths had drifted to 1.08, 1.12, and 1.13 in three different files,
     * so a message and its reply were set on subtly different rhythms for no chosen reason.
     */
    @Test public void userAndAssistantTextShareOneLineRhythm() {
        TextView shared = UiKit.text(context, "example", 15, UiKit.TEXT, false);
        UiKit.applyBubbleTextMetrics(shared);
        assertEquals(UiKit.CHAT_LINE_SPACING, shared.getLineSpacingMultiplier(), 0.0001f);
        assertEquals("line spacing adds no fixed extra of its own",
                0f, shared.getLineSpacingExtra(), 0.0001f);
    }

    /**
     * The font's own reserve above its ascent and below its descent is what keeps tall accents and
     * deep descenders from being clipped, and Orbit's serif option needs more of it than the
     * default does. Balance was fixed in the layout, so this must stay switched on.
     */
    @Test public void theFontsOwnVerticalReserveIsLeftAlone() {
        for (String font : FONTS) {
            Prefs.get(context).edit().putString(Prefs.APP_FONT, font).commit();
            LinearLayout bubble = bubble("Jgq Ẫ 13.5", false);
            for (int i = 0; i < bubble.getChildCount(); i++) {
                View child = bubble.getChildAt(i);
                if (child instanceof TextView) {
                    assertTrue("glyphs must not be clipped on " + font,
                            ((TextView) child).getIncludeFontPadding());
                }
            }
        }
    }
}
