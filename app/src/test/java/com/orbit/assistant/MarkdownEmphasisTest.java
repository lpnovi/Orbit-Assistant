package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * Emphasis says what it was written to say, and nothing that was not written as emphasis becomes
 * emphasis.
 *
 * <p>The defect this file was written for was visible on a real device: {@code ***bold italic***}
 * came out as a bold phrase with a literal asterisk still sitting at each end, because the
 * {@code **} matcher got first refusal and consumed the middle four of the six delimiters. So the
 * headline property here is that combined emphasis is one construct — both styles over the same
 * characters, and no delimiter left on screen.
 *
 * <p>The second half of the file is the reason this is not simply a matter of deleting stray
 * asterisks. Prose legitimately contains "2 * 4 = 8", and code and configuration legitimately
 * contain {@code some_variable_name}. A parser aggressive enough to tidy the first case would
 * corrupt the second, so both are asserted alongside the fix.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class MarkdownEmphasisTest {

    private Context context;
    private int fill;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        UiKit.syncTheme(context);
        fill = UiKit.assistantBubbleFill(context, UiKit.SURFACE);
    }

    // ---- combined emphasis ----------------------------------------------------------------------

    @Test public void tripleAsterisksProduceBoldAndItalicOverTheSameWords() {
        Spanned rendered = inline("This is ***very important*** today.");
        assertEquals("This is very important today.", rendered.toString());
        assertFalse("no delimiter survives", rendered.toString().contains("*"));

        int from = rendered.toString().indexOf("very important");
        int to = from + "very important".length();
        assertTrue("the phrase is bold", styled(rendered, from, to, Typeface.BOLD));
        assertTrue("and italic", styled(rendered, from, to, Typeface.ITALIC));
        assertFalse("and the words either side of it are neither",
                styled(rendered, 0, from, Typeface.BOLD)
                        || styled(rendered, 0, from, Typeface.ITALIC));
    }

    @Test public void tripleUnderscoresBehaveTheSameWay() {
        Spanned rendered = inline("A ___bold italic___ phrase.");
        assertEquals("A bold italic phrase.", rendered.toString());
        assertFalse(rendered.toString().contains("_"));
        int from = rendered.toString().indexOf("bold italic");
        int to = from + "bold italic".length();
        assertTrue(styled(rendered, from, to, Typeface.BOLD));
        assertTrue(styled(rendered, from, to, Typeface.ITALIC));
    }

    /** Combined emphasis composes with the inline content around it rather than replacing it. */
    @Test public void combinedEmphasisSitsBesideOtherInlineMarkup() {
        Spanned rendered = inline("A ***bold italic*** phrase with `inline code` nearby.");
        assertEquals("A bold italic phrase with inline code nearby.", rendered.toString());
        int from = rendered.toString().indexOf("bold italic");
        assertTrue(styled(rendered, from, from + 11, Typeface.BOLD));
        assertTrue(styled(rendered, from, from + 11, Typeface.ITALIC));
    }

    // ---- every block that renders inline markup ---------------------------------------------------

    /**
     * The same phrase through every construct that renders inline Markdown.
     *
     * <p>Emphasis is applied by one shared inline renderer, so if it were handled specially
     * anywhere — a heading, a table cell, a task item — that construct would be the one that broke.
     */
    @Test public void combinedEmphasisWorksInEveryBlockThatRendersInlineMarkup() {
        String[] blocks = {
                "This is ***very important*** today.",
                "## A ***very important*** heading",
                "- A ***very important*** list item",
                "> A ***very important*** quote",
                "| Column |\n| --- |\n| A ***very important*** cell |",
                "- [x] A ***very important*** task",
        };
        for (String block : blocks) {
            String text = allText(OrbitRichResponseRenderer.render(context, block, fill, false));
            assertTrue("the words survive in: " + block, text.contains("very important"));
            assertFalse("no asterisk survives in: " + block, text.contains("*"));
            assertTrue("bold and italic are both applied in: " + block,
                    boldItalicSomewhere(OrbitRichResponseRenderer.render(context, block, fill, false)));
        }
    }

    // ---- ordinary emphasis still works ------------------------------------------------------------

    @Test public void ordinaryEmphasisIsUnchanged() {
        Spanned bold = inline("This is **bold** text.");
        assertEquals("This is bold text.", bold.toString());
        assertTrue(styled(bold, 8, 12, Typeface.BOLD));
        assertFalse("plain bold is not also italic", styled(bold, 8, 12, Typeface.ITALIC));

        Spanned italic = inline("This is *italic* text.");
        assertEquals("This is italic text.", italic.toString());
        assertTrue(styled(italic, 8, 14, Typeface.ITALIC));
        assertFalse("plain italic is not also bold", styled(italic, 8, 14, Typeface.BOLD));

        assertEquals("Call println here.", inline("Call `println` here.").toString());
        assertEquals("Struck out.", inline("~~Struck out.~~").toString());
    }

    // ---- literal characters ------------------------------------------------------------------------

    /**
     * Asterisks and underscores that were never markup are left exactly as written.
     *
     * <p>This is the constraint that rules out fixing the triple-emphasis bug by stripping stray
     * delimiters: arithmetic and identifiers would be silently rewritten to make formatting look
     * tidier, which is a worse bug than the one being fixed.
     */
    @Test public void charactersThatAreNotMarkupAreLeftAlone() {
        String[] literals = {
                "2 * 4 = 8",
                "5*3",
                "*",
                "a * b * c",
                "some_variable_name",
                "content_description_value",
                "call some_helper(value) then other_helper(value)",
        };
        for (String literal : literals) {
            assertEquals("literal text must survive: " + literal,
                    literal, inline(literal).toString());
        }
    }

    /** An opening delimiter whose partner never arrives loses nothing but formatting. */
    @Test public void unmatchedDelimitersKeepTheirCharactersInFinishedText() {
        assertEquals("This is *unfinished", inline("This is *unfinished").toString());
        assertEquals("This is ***unfinished", inline("This is ***unfinished").toString());
    }

    // ---- previews ----------------------------------------------------------------------------------

    /** The Chats list flattens the same markup, and must not leak the stray stars either. */
    @Test public void previewTextDropsCombinedEmphasisSyntax() {
        assertEquals("This is very important today.",
                OrbitMarkdown.toPreviewText("This is ***very important*** today.", 100));
        assertEquals("A bold italic phrase.",
                OrbitMarkdown.toPreviewText("A ___bold italic___ phrase.", 100));
        assertEquals("2 * 4 = 8", OrbitMarkdown.toPreviewText("2 * 4 = 8", 100));
        assertEquals("some_variable_name", OrbitMarkdown.toPreviewText("some_variable_name", 100));
    }

    /** Spoken replies say the words, never the delimiters. */
    @Test public void speechTextDropsCombinedEmphasisSyntax() {
        assertEquals("This is very important today.",
                OrbitMarkdown.toSpeechText("This is ***very important*** today."));
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private Spanned inline(String source) {
        return (Spanned) OrbitMarkdown.renderInline(context, source, UiKit.TEXT, fill);
    }

    /**
     * Whether the whole range carries a typeface style.
     *
     * <p>Read as a mask rather than as a span count, because bold-italic is deliberately two
     * ordinary {@link StyleSpan}s composing over one range rather than a special third style —
     * a {@code StyleSpan} ORs itself into the face the paint already has. The assertion is about
     * what the reader sees, so it must not care how many spans produced it.
     */
    private static boolean styled(Spanned text, int from, int to, int style) {
        if (to <= from) return false;
        int mask = 0;
        for (StyleSpan span : text.getSpans(from, to, StyleSpan.class)) {
            if (text.getSpanStart(span) <= from && text.getSpanEnd(span) >= to) {
                mask |= span.getStyle();
            }
        }
        return (mask & style) == style;
    }

    /** True when some run of some TextView in the tree is both bold and italic. */
    private static boolean boldItalicSomewhere(View root) {
        for (View view : descendants(root)) {
            if (!(view instanceof TextView)) continue;
            CharSequence text = ((TextView) view).getText();
            if (!(text instanceof Spanned)) continue;
            Spanned spanned = (Spanned) text;
            for (StyleSpan span : spanned.getSpans(0, spanned.length(), StyleSpan.class)) {
                int start = spanned.getSpanStart(span);
                int end = spanned.getSpanEnd(span);
                if (styled(spanned, start, end, Typeface.BOLD)
                        && styled(spanned, start, end, Typeface.ITALIC)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String allText(View root) {
        StringBuilder out = new StringBuilder();
        for (View view : descendants(root)) {
            if (!(view instanceof TextView)) continue;
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 0) out.append(text).append('\n');
        }
        return out.toString();
    }

    private static List<View> descendants(View root) {
        List<View> out = new ArrayList<>();
        out.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                out.addAll(descendants(group.getChildAt(i)));
            }
        }
        return out;
    }
}
