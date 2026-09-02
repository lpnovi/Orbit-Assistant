package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.text.style.URLSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
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
 * Markdown task lists are shown as ticked and empty boxes, and are shown rather than operated.
 *
 * <p>Before this, {@code - [x] Done} reached a real device with its syntax intact — the list
 * parser removed the bullet and printed "[x] Done" — so an answer that had marked its own work
 * finished looked unfinished.
 *
 * <p>Two properties carry this file. The first is <b>recognition</b>: the checkbox is read only
 * where it was written as a list item, so ordinary prose containing "[x]" keeps its brackets. The
 * second is <b>read-only</b>: these boxes present what the assistant wrote, and a box that could
 * be tapped would promise to edit a stored reply. There is deliberately no control in the tree to
 * tap.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class MarkdownTaskListTest {

    private Context context;
    private int fill;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        UiKit.syncTheme(context);
        fill = UiKit.assistantBubbleFill(context, UiKit.SURFACE);
    }

    // ---- recognition ------------------------------------------------------------------------------

    @Test public void checkedAndUncheckedItemsAreRecognised() {
        View rendered = render("- [x] Done\n- [X] Also done\n- [ ] Not done");
        List<TaskBoxSpan> boxes = boxes(rendered);
        assertEquals("one box per item", 3, boxes.size());
        assertTrue("lowercase x is checked", boxes.get(0).isChecked());
        assertTrue("uppercase X is checked too", boxes.get(1).isChecked());
        assertFalse("a space is unchecked", boxes.get(2).isChecked());

        String text = allText(rendered);
        assertTrue(text.contains("Done"));
        assertTrue(text.contains("Not done"));
        assertFalse("no literal task syntax survives", text.contains("[x]"));
        assertFalse(text.contains("[X]"));
        assertFalse(text.contains("[ ]"));
        assertFalse("and no bullet is drawn beside the box", text.contains("•"));
    }

    /** The other bullet markers the list parser already accepts carry a checkbox too. */
    @Test public void starAndPlusMarkersAlsoCarryTasks() {
        assertEquals(1, boxes(render("* [x] Star marker")).size());
        assertEquals(1, boxes(render("+ [ ] Plus marker")).size());
    }

    /** A numbered item is a step, not a task, and keeps its number. */
    @Test public void numberedItemsAreNotPromotedIntoTasks() {
        View rendered = render("1. [x] Step one");
        assertEquals("no checkbox on a numbered item", 0, boxes(rendered).size());
        assertTrue(allText(rendered).contains("1."));
    }

    // ---- false positives --------------------------------------------------------------------------

    /**
     * Prose that happens to contain the characters is prose.
     *
     * <p>Only a line already written as a list item can become a task, so an assistant explaining
     * bracket notation keeps its brackets rather than sprouting a checkbox mid-sentence.
     */
    @Test public void bracketsInOrdinaryTextStayOrdinaryText() {
        String[] prose = {
                "The token [x] means something.",
                "[ ]",
                "hello [x] world",
                "A sentence about [ ] and [x] notation.",
        };
        for (String line : prose) {
            View rendered = render(line);
            assertEquals("no checkbox may appear in: " + line, 0, boxes(rendered).size());
            assertTrue("the brackets survive in: " + line,
                    allText(rendered).contains(line.contains("[x]") ? "[x]" : "[ ]"));
        }
    }

    /** The parser is the single source of this decision, and it only answers for list items. */
    @Test public void theParserOnlyRecognisesTasksBehindABulletMarker() {
        assertNotNull(ResponseBlocks.task("-", "[x] Done"));
        assertNotNull(ResponseBlocks.task("*", "[ ] Pending"));
        assertNull("a numbered marker never carries a task",
                ResponseBlocks.task("1.", "[x] Done"));
        assertNull("nor does prose that merely contains brackets",
                ResponseBlocks.task("-", "The token [x] means something."));
        assertNull("a box needs a state character", ResponseBlocks.task("-", "[] Done"));
        assertEquals("Done", ResponseBlocks.task("-", "[x] Done").text);
        assertTrue(ResponseBlocks.task("-", "[x] Done").checked);
        assertFalse(ResponseBlocks.task("-", "[ ] Done").checked);
    }

    // ---- read-only --------------------------------------------------------------------------------

    /**
     * Nothing in a task list can be operated.
     *
     * <p>Asserted as the absence of a control rather than as a disabled one: a stock
     * {@code CheckBox} would arrive focusable and announced as a switch even with its listener
     * removed, so the box is drawn by a span, which has nothing to press.
     */
    @Test public void taskBoxesAreNotControls() {
        View rendered = render("- [x] Done\n- [ ] Not done");
        for (View view : descendants(rendered)) {
            assertFalse("a task list must contain no toggle control",
                    view instanceof CompoundButton);
        }
    }

    /** Tapping a task item changes nothing about the answer. */
    @Test public void tappingATaskDoesNotToggleIt() {
        View rendered = render("- [ ] Not done");
        TextView item = taskViews(rendered).get(0);
        CharSequence before = item.getText();
        boolean checkedBefore = boxes(rendered).get(0).isChecked();

        item.performClick();
        item.performLongClick();

        assertSame("the item's text is not rebuilt by a tap", before, item.getText());
        assertEquals("and its state is unchanged",
                checkedBefore, boxes(rendered).get(0).isChecked());
        assertFalse(boxes(rendered).get(0).isChecked());
    }

    // ---- accessibility ----------------------------------------------------------------------------

    /**
     * A screen reader hears the state and the words, never the syntax.
     *
     * <p>The box has no text of its own — it is drawn over a placeholder — so without this the
     * announcement would either lose the state entirely or read out the bracket characters.
     */
    @Test public void stateIsAnnouncedInWords() {
        View rendered = render("- [x] Headings rendered\n- [ ] Run device test");
        List<TextView> items = taskViews(rendered);
        assertEquals(2, items.size());
        assertEquals("Checked, Headings rendered",
                String.valueOf(items.get(0).getContentDescription()));
        assertEquals("Unchecked, Run device test",
                String.valueOf(items.get(1).getContentDescription()));
    }

    /** Spoken replies say the state too, rather than reading the brackets aloud. */
    @Test public void speechSaysTheStateRatherThanTheBrackets() {
        String spoken = OrbitMarkdown.toSpeechText("- [x] Done\n- [ ] Pending");
        assertFalse(spoken.contains("["));
        assertFalse(spoken.contains("]"));
        assertTrue(spoken.contains("Done"));
        assertTrue(spoken.contains("Pending"));
    }

    /** The Chats list flattens a task item to its words, without the syntax. */
    @Test public void previewsDropTheTaskSyntax() {
        assertEquals("Done · Pending",
                OrbitMarkdown.toPreviewText("- [x] Done\n- [ ] Pending", 100));
    }

    // ---- inline Markdown inside a task ------------------------------------------------------------

    /**
     * A task's text is ordinary rich text.
     *
     * <p>The box is a prefix, not a different renderer, so everything that works in a bullet works
     * behind a checkbox — including the combined emphasis fixed alongside it.
     */
    @Test public void inlineMarkdownSurvivesTaskParsing() {
        View rendered = render("- [x] **Bold item**\n"
                + "- [ ] *Italic item*\n"
                + "- [x] ***Bold italic item***\n"
                + "- [ ] Use `inline code`\n"
                + "- [x] See [the docs](https://example.com)");
        String text = allText(rendered);
        assertFalse("no emphasis syntax survives", text.contains("*"));
        assertFalse("no link syntax survives", text.contains("]("));
        assertTrue(text.contains("Bold item"));
        assertTrue(text.contains("inline code"));
        assertTrue(text.contains("the docs"));

        List<TextView> items = taskViews(rendered);
        assertEquals(5, items.size());
        assertTrue("bold works behind a box", styledSomewhere(items.get(0), Typeface.BOLD));
        assertTrue("italic works behind a box", styledSomewhere(items.get(1), Typeface.ITALIC));
        assertTrue("and so does both at once",
                styledSomewhere(items.get(2), Typeface.BOLD | Typeface.ITALIC));

        Spanned link = (Spanned) items.get(4).getText();
        assertEquals("a link inside a task is still a link",
                1, link.getSpans(0, link.length(), URLSpan.class).length);
        assertTrue("and is still clickable", items.get(4).getLinksClickable());
    }

    // ---- ordinary lists are untouched ---------------------------------------------------------------

    /** The task syntax extends the list parser; it does not replace it. */
    @Test public void ordinaryListsAreUnchanged() {
        View bullets = render("- First\n- Second **bold**\n- [See docs](https://example.com)");
        assertEquals("no checkbox on an ordinary bullet", 0, boxes(bullets).size());
        String text = allText(bullets);
        assertTrue("bullets still get their bullet", text.contains("•"));
        assertTrue(text.contains("First"));
        assertTrue(text.contains("Second bold"));
        assertTrue(text.contains("See docs"));

        View numbered = render("1. Step one\n2. Step two");
        assertEquals(0, boxes(numbered).size());
        assertTrue(allText(numbered).contains("1."));
        assertTrue(allText(numbered).contains("2."));
    }

    /** Indentation is the list's, not the checkbox's: a task row indents like a bullet row. */
    @Test public void tasksIndentExactlyAsBulletsDo() {
        List<TextView> tasks = taskViews(render("- [x] Top\n  - [ ] Nested"));
        List<TextView> items = listItems(render("- Top\n  - Nested"));
        assertEquals(2, tasks.size());
        assertEquals(2, items.size());
        assertEquals("a top-level task starts where a top-level bullet does",
                items.get(0).getPaddingLeft(), tasks.get(0).getPaddingLeft());
        assertEquals("and a nested one where a nested bullet does",
                items.get(1).getPaddingLeft(), tasks.get(1).getPaddingLeft());
        assertTrue("nesting still indents", tasks.get(1).getPaddingLeft() > tasks.get(0).getPaddingLeft());
    }

    /** The box and the words are one view, so they cannot end up on separate rows. */
    @Test public void aTaskIsOneRowNotTwo() {
        View rendered = render("- [x] Done");
        List<TextView> items = listItems(rendered);
        assertEquals("one view for the whole task", 1, items.size());
        Spanned text = (Spanned) items.get(0).getText();
        assertEquals("with its box inside that view's own text",
                1, text.getSpans(0, text.length(), TaskBoxSpan.class).length);
        assertTrue(text.toString().contains("Done"));
    }

    /**
     * The Side-button overlay gets the same rendering full chat does.
     *
     * <p>The overlay is not a second renderer — it calls the same entry point with {@code compact}
     * set — so the only thing worth pinning is that the compact shape does not lose the box, the
     * state, or the formatting inside the item.
     */
    @Test public void theOverlayRendersTasksTheSameWay() {
        String source = "- [x] ***Done properly***\n- [ ] Not done";
        View overlay = OrbitRichResponseRenderer.render(context, source, fill, true);
        View chat = OrbitRichResponseRenderer.render(context, source, fill, false);

        assertEquals("both surfaces draw the same boxes", boxes(chat).size(), boxes(overlay).size());
        assertEquals(2, boxes(overlay).size());
        assertTrue(boxes(overlay).get(0).isChecked());
        assertFalse(boxes(overlay).get(1).isChecked());
        assertEquals("and say the same words", allText(chat), allText(overlay));
        assertFalse(allText(overlay).contains("["));
        assertTrue("with the same emphasis inside the item",
                styledSomewhere(taskViews(overlay).get(0), Typeface.BOLD | Typeface.ITALIC));
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private View render(String source) {
        return OrbitRichResponseRenderer.render(context, source, fill, false);
    }

    private static List<TaskBoxSpan> boxes(View root) {
        List<TaskBoxSpan> found = new ArrayList<>();
        for (TextView view : listItems(root)) {
            CharSequence text = view.getText();
            if (!(text instanceof Spanned)) continue;
            Spanned spanned = (Spanned) text;
            for (TaskBoxSpan span : spanned.getSpans(0, spanned.length(), TaskBoxSpan.class)) {
                found.add(span);
            }
        }
        return found;
    }

    /** The TextViews that carry a checkbox, in order. */
    private static List<TextView> taskViews(View root) {
        List<TextView> found = new ArrayList<>();
        for (TextView view : listItems(root)) {
            CharSequence text = view.getText();
            if (!(text instanceof Spanned)) continue;
            Spanned spanned = (Spanned) text;
            if (spanned.getSpans(0, spanned.length(), TaskBoxSpan.class).length > 0) found.add(view);
        }
        return found;
    }

    private static List<TextView> listItems(View root) {
        List<TextView> found = new ArrayList<>();
        for (View view : descendants(root)) {
            if (view instanceof TextView) found.add((TextView) view);
        }
        return found;
    }

    private static boolean styledSomewhere(TextView view, int style) {
        CharSequence text = view.getText();
        if (!(text instanceof Spanned)) return false;
        Spanned spanned = (Spanned) text;
        for (StyleSpan span : spanned.getSpans(0, spanned.length(), StyleSpan.class)) {
            int mask = 0;
            int start = spanned.getSpanStart(span);
            int end = spanned.getSpanEnd(span);
            for (StyleSpan other : spanned.getSpans(start, end, StyleSpan.class)) {
                if (spanned.getSpanStart(other) <= start && spanned.getSpanEnd(other) >= end) {
                    mask |= other.getStyle();
                }
            }
            if ((mask & style) == style) return true;
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
