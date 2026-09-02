package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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
 * An answer that looks like an Orbit answer while it is still being written.
 *
 * <p>Before this, streaming and finished were two different presentations: a raw {@code TextView}
 * full of {@code ## Heading} and triple backticks, thrown away at completion and replaced by a rich
 * tree. Every response therefore ended with a visible jump, and the user spent the whole generation
 * looking at the worse of the two.
 *
 * <p>Two properties carry this file. The first is <b>parity</b>: streaming a response fragment by
 * fragment must end up saying exactly what rendering it in one pass says, because the two paths now
 * share one parser and one block builder and any divergence means they have drifted apart again.
 * The second is <b>stability</b>: a block that has not changed must not be rebuilt, since rebuilding
 * is what loses a code block's Copy control, a link's clickability, a table's scroll offset and a
 * screen reader's focus while the rest of the answer is still arriving.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class ProgressiveResponseTest {

    private Context context;
    private int fill;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        UiKit.syncTheme(context);
        fill = UiKit.assistantBubbleFill(context, UiKit.SURFACE);
    }

    private ProgressiveResponseView view() {
        return new ProgressiveResponseView(context, fill, false);
    }

    /** Streams a response through the given fragments and returns the finished view. */
    private ProgressiveResponseView stream(String... fragments) {
        ProgressiveResponseView view = view();
        for (int i = 0; i < fragments.length; i++) {
            view.update(fragments[i], i < fragments.length - 1);
        }
        return view;
    }

    /** Every character of a response, offered one fragment at a time. */
    private ProgressiveResponseView streamCharacterByCharacter(String answer) {
        ProgressiveResponseView view = view();
        for (int i = 1; i <= answer.length(); i++) view.update(answer.substring(0, i), true);
        view.update(answer, false);
        return view;
    }

    // ---- inline markup -------------------------------------------------------------------------------

    /**
     * The headline case: bold formats as soon as its closing delimiter arrives.
     *
     * <p>And crucially, the half-written form in the middle never shows the user the asterisks that
     * are still deciding what to be.
     */
    @Test public void boldFormatsOnceItIsComplete() {
        ProgressiveResponseView view = view();

        view.update("This is **impor", true);
        String midway = allText(view);
        assertTrue("the words keep arriving", midway.contains("impor"));
        assertFalse("but the unfinished delimiter is not shown", midway.contains("**"));

        view.update("This is **important** text.", false);
        String finished = allText(view);
        assertEquals("This is important text.", finished.trim());
        assertFalse("no raw syntax survives", finished.contains("**"));
        assertTrue("and the word is actually bold", hasStyleSpan(view));
    }

    @Test public void italicsAndInlineCodeFormatWhenComplete() {
        assertEquals("An italic word.", allText(stream("An *italic* word.")).trim());
        assertEquals("Call println here.", allText(stream("Call `println` here.")).trim());
    }

    @Test public void acompletedLinkKeepsItsTextAndDropsItsSyntax() {
        String text = allText(stream("See [the docs](https://example.com) for more.")).trim();
        assertEquals("See the docs for more.", text);
        assertFalse(text.contains("]("));
        assertFalse(text.contains("https://"));
    }

    /** An incomplete link stays plain, stable text. Nothing is opened and nothing is guessed. */
    @Test public void anIncompleteLinkStaysStable() {
        ProgressiveResponseView view = view();
        view.update("See [the docs](https://exa", true);
        assertTrue(allText(view).contains("the docs"));
    }

    /**
     * Completing an inline run does not rebuild the response container.
     *
     * <p>The bubble the user is reading stays the same object; only the paragraph inside it is
     * redrawn.
     */
    @Test public void completingInlineMarkupDoesNotReplaceTheWholeResponse() {
        ProgressiveResponseView view = view();
        view.update("Intro paragraph.\n\nThis is **impor", true);
        View settledFirstBlock = view.getChildAt(0);

        view.update("Intro paragraph.\n\nThis is **important**.", false);
        assertSame("a settled block must not be rebuilt by later text",
                settledFirstBlock, view.getChildAt(0));
    }

    // ---- headings, lists, quotes ------------------------------------------------------------------------

    /** A heading formats while generation is still running, not after it. */
    @Test public void aheadingFormatsWhileTheAnswerIsStillArriving() {
        ProgressiveResponseView view = view();
        view.update("## Things to try\n", true);
        String text = allText(view);
        assertTrue(text.contains("Things to try"));
        assertFalse("the hashes are gone as soon as Orbit understands them", text.contains("##"));
    }

    @Test public void listItemsAppearAsTheyArrive() {
        ProgressiveResponseView view = view();
        view.update("## Things to try\n\n- First", true);
        assertTrue(allText(view).contains("First"));

        view.update("## Things to try\n\n- First\n- Second", true);
        String text = allText(view);
        assertTrue(text.contains("First"));
        assertTrue(text.contains("Second"));
        assertFalse("no raw list syntax survives recognition", text.contains("- First"));
    }

    @Test public void aquoteFormatsOnceEstablished() {
        String text = allText(stream("Intro.\n\n> something quoted\n\nAfter.")).trim();
        assertTrue(text.contains("something quoted"));
        assertFalse(text.contains("> something"));
    }

    /** Blocks already on screen are untouched as later ones arrive. */
    @Test public void earlierBlocksAreNotRebuiltAsLaterOnesArrive() {
        ProgressiveResponseView view = view();
        view.update("## Things to try\n\nAn intro paragraph.", true);
        assertTrue(view.getChildCount() >= 2);
        View heading = view.getChildAt(0);
        View paragraph = view.getChildAt(1);

        view.update("## Things to try\n\nAn intro paragraph.\n\n- First\n- Second", true);
        assertSame("the heading is the same view", heading, view.getChildAt(0));
        assertSame("and so is the paragraph", paragraph, view.getChildAt(1));
        assertTrue("with the list appended after them", view.getChildCount() >= 3);
    }

    // ---- code -----------------------------------------------------------------------------------------------

    /** An open fence is already a code block, not a paragraph of backticks. */
    @Test public void anOpenFenceIsDrawnAsACodeBlock() {
        ProgressiveResponseView view = view();
        view.update("```java\nSystem.out.println(", true);
        String text = allText(view);
        assertTrue("the code is on screen", text.contains("System.out.println("));
        assertFalse("the fence is not", text.contains("```"));
        assertTrue("and it is labelled", text.contains("java"));
    }

    /** Code fills in progressively inside the block that is already there. */
    @Test public void codeStreamsInsideTheBlockThatIsAlreadyThere() {
        ProgressiveResponseView view = view();
        view.update("```java\nSystem", true);
        int blocksWithOpenFence = view.getChildCount();
        view.update("```java\nSystem.out.println(\"hello\");", true);
        assertEquals("the code block is filled, not re-added",
                blocksWithOpenFence, view.getChildCount());
        assertTrue(allText(view).contains("System.out.println(\"hello\");"));
    }

    /** Closing the fence completes the block without recreating its controls. */
    @Test public void closingTheFenceLeavesExactlyOneCopyControl() {
        ProgressiveResponseView view = stream(
                "```java\nSystem",
                "```java\nSystem.out.println(\"hello\");",
                "```java\nSystem.out.println(\"hello\");\n```");
        assertEquals("exactly one Copy control on a finished code block",
                1, copyControls(view).size());
        assertTrue("and it is available now the code is complete",
                copyControls(view).get(0).isEnabled());
        assertTrue(allText(view).contains("System.out.println(\"hello\");"));
        assertFalse(allText(view).contains("```"));
    }

    /** While the block is open, Copy is held back rather than offering half a snippet. */
    @Test public void copyWaitsUntilTheCodeIsComplete() {
        ProgressiveResponseView view = view();
        view.update("```java\nint x =", true);
        List<Button> controls = copyControls(view);
        assertEquals("the control exists, so the header does not change shape", 1, controls.size());
        assertFalse("but it does not offer a half-written snippet", controls.get(0).isEnabled());
    }

    /** A response stopped inside an open fence keeps its code block and stays readable. */
    @Test public void astoppedResponseInsideAFenceKeepsItsCodeBlock() {
        ProgressiveResponseView view = view();
        view.update("Here is the code:\n\n```java\nint x = 1;", true);
        // Stopping settles exactly what arrived, with no closing fence ever coming.
        view.settle("Here is the code:\n\n```java\nint x = 1;");
        String text = allText(view);
        assertTrue(text.contains("int x = 1;"));
        assertFalse("backticks must not reappear when a response is stopped", text.contains("```"));
    }

    // ---- tables -----------------------------------------------------------------------------------------------

    /** A pipe line stays ordinary text until the divider proves it is a table. */
    @Test public void apipeLineIsNotATableUntilTheDividerArrives() {
        ProgressiveResponseView view = view();
        view.update("| Name | Value |", true);
        assertEquals("still one ordinary block", 1, view.getChildCount());
        assertEquals(0, tables(view).size());

        view.update("| Name | Value |\n| --- | --- |", true);
        assertEquals("now it is a table", 1, tables(view).size());
    }

    /** Rows append in order, and the table is promoted only once. */
    @Test public void tableRowsAppendInOrderWithoutDuplicating() {
        ProgressiveResponseView view = stream(
                "| A | B |\n| --- | --- |",
                "| A | B |\n| --- | --- |\n| 1 | 2 |",
                "| A | B |\n| --- | --- |\n| 1 | 2 |\n| 3 | 4 |");
        assertEquals("one table, promoted once", 1, tables(view).size());
        String text = allText(view);
        assertTrue(text.indexOf("1") < text.indexOf("3"));
        assertFalse("the divider row is structure and is never drawn", text.contains("---"));
        assertEquals("no duplicated rows", 1, countOccurrences(text, "3"));
    }

    // ---- combined emphasis while streaming -----------------------------------------------------------------------

    /**
     * Bold-italic settles once, rather than flickering through its own delimiters.
     *
     * <p>The device showed the finished form of this bug — a stray asterisk left at each end — but
     * the streaming form matters just as much: while the six delimiters are arriving one at a time
     * the reader must never watch stars appear and disappear around a phrase they are trying to
     * read. The existing dangling-delimiter policy covers it, once it knows that {@code ***} is a
     * delimiter in its own right.
     */
    @Test public void combinedEmphasisSettlesCleanlyAsItArrives() {
        ProgressiveResponseView view = view();

        view.update("This is ***bo", true);
        String early = allText(view);
        assertTrue("the words keep arriving", early.contains("bo"));
        assertFalse("with no delimiter noise while it is still being decided", early.contains("*"));

        view.update("This is ***bold ita", true);
        assertFalse(allText(view).contains("*"));
        assertTrue(allText(view).contains("bold ita"));

        view.update("This is ***bold italic*** text.", false);
        String finished = allText(view).trim();
        assertEquals("This is bold italic text.", finished);
        assertFalse("no raw syntax survives", finished.contains("*"));
        assertTrue("and the phrase is genuinely bold and italic", boldItalicSomewhere(view));
    }

    /** A half-written triple never loses characters, even if the answer stops there. */
    @Test public void anUnfinishedTripleKeepsItsWords() {
        ProgressiveResponseView view = view();
        view.update("This is ***unfinished", true);
        assertTrue(allText(view).contains("unfinished"));
        view.settle("This is ***unfinished");
        assertTrue("a stopped answer keeps every character it was given",
                allText(view).contains("***unfinished"));
    }

    /** Completing a triple redraws its own paragraph and nothing else. */
    @Test public void completingCombinedEmphasisLeavesEarlierBlocksAlone() {
        ProgressiveResponseView view = view();
        view.update("Intro paragraph.\n\nThis is ***very", true);
        View intro = view.getChildAt(0);
        view.update("Intro paragraph.\n\nThis is ***very important*** now.", false);
        assertSame("a settled block must not be rebuilt by later text", intro, view.getChildAt(0));
    }

    // ---- task lists while streaming --------------------------------------------------------------------------

    /**
     * A task item is recognised as soon as its box is written, and does not change its mind.
     *
     * <p>The oscillation this rules out is bullet, then a literal "[x]", then a checkbox: three
     * presentations of one line while the reader watches. Once {@code - [x]} exists there is
     * nothing left to decide, so the box appears then and stays.
     */
    @Test public void aTaskItemIsRecognisedAsSoonAsItsBoxIsWritten() {
        ProgressiveResponseView view = view();

        view.update("- [x", true);
        assertFalse("a half-written box is not a box yet", hasTaskBox(view));
        assertTrue("but nothing is lost while it waits", allText(view).contains("x"));

        view.update("- [x]", true);
        assertTrue("the box appears the moment it is complete", hasTaskBox(view));
        assertEquals(1, taskBoxes(view).size());

        view.update("- [x] Headings rendered", true);
        assertEquals("and the text fills in behind the same box", 1, taskBoxes(view).size());
        assertTrue(taskBoxes(view).get(0).isChecked());
        assertTrue(allText(view).contains("Headings rendered"));
        assertFalse(allText(view).contains("[x]"));
    }

    /** Later items arrive without disturbing the ones already ticked off above them. */
    @Test public void earlierTasksStayStableAsLaterOnesArrive() {
        ProgressiveResponseView view = stream(
                "- [x] Done",
                "- [x] Done\n- [ ] Not",
                "- [x] Done\n- [ ] Not done yet");
        List<TaskBoxSpan> boxes = taskBoxes(view);
        assertEquals("exactly one box per item, never one per update", 2, boxes.size());
        assertTrue(boxes.get(0).isChecked());
        assertFalse(boxes.get(1).isChecked());
        String text = allText(view);
        assertTrue(text.contains("Done"));
        assertTrue(text.contains("Not done yet"));
        assertFalse(text.contains("[ ]"));
    }

    /** Streaming a task list character by character lands exactly where a direct render does. */
    @Test public void aStreamedTaskListMatchesADirectRender() {
        String answer = "## Progress\n\n- [x] Headings rendered\n- [ ] Run device test\n"
                + "- [x] ***Bold italic*** item";
        assertEquals(normalise(allText(OrbitRichResponseRenderer.render(context, answer, fill, false))),
                normalise(allText(streamCharacterByCharacter(answer))));
    }

    // ---- table geometry while streaming ----------------------------------------------------------------------

    /**
     * The equal-height rule holds on a table that is still growing.
     *
     * <p>Fixing only the completed renderer would leave a streaming table fragmented for the whole
     * time the user is actually watching it. It holds here for free because both paths build their
     * table with the same block builder, and that is the property worth pinning.
     */
    @Test public void everyRowOfAStreamingTableKeepsItsCellsTogether() {
        ProgressiveResponseView view = view();
        String[] fragments = {
                "| A | B | C |\n| --- | --- | --- |",
                "| A | B | C |\n| --- | --- | --- |\n| 1 | 2 | 3 |",
                "| A | B | C |\n| --- | --- | --- |\n| 1 | 2 | 3 |\n| 4 | 5 | 6 |",
        };
        for (int i = 0; i < fragments.length; i++) {
            view.update(fragments[i], true);
            assertEquals("one table, promoted once", 1, tables(view).size());
            for (android.widget.TableRow row : tableRows(view)) {
                for (int cell = 0; cell < row.getChildCount(); cell++) {
                    assertEquals("a streaming table's cells must fill their row too",
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            row.getChildAt(cell).getLayoutParams().height);
                }
            }
        }
        view.settle(fragments[fragments.length - 1]);
        assertEquals("the finished table has its header and both rows", 3, tableRows(view).size());
        assertEquals("still exactly one horizontal scroller", 1, tables(view).size());
    }

    // ---- final parity ----------------------------------------------------------------------------------------

    /**
     * Streaming a response then settling it says exactly what rendering it once says.
     *
     * <p>The property that proves the two presentations really are one. Internal view identity is
     * allowed to differ; the words and their formatting are not.
     */
    @Test public void aStreamedResponseEndsUpSayingWhatADirectRenderSays() {
        String[] responses = {
                "A plain paragraph answer.",
                "# Big heading\n\nBody text under it.",
                "## Things to try\n\n- First item\n- Second item\n- Third item",
                "1. Step one\n2. Step two",
                "> A quoted line worth keeping.\n\nAnd a reply to it.",
                "Here:\n\n```java\nint x = 1;\nSystem.out.println(x);\n```\n\nDone.",
                "| Name | Value |\n| --- | --- |\n| alpha | 1 |\n| beta | 2 |",
                "See [the docs](https://example.com) and **note** this.",
                "Above the line.\n\n---\n\nBelow the line.",
                "## Mixed\n\nIntro **bold** text.\n\n- one\n- two\n\n```py\nx = 1\n```\n\n> quoted\n\nEnd.",
        };
        for (String response : responses) {
            String direct = allText(
                    OrbitRichResponseRenderer.render(context, response, fill, false));
            String streamed = allText(streamCharacterByCharacter(response));
            assertEquals("streamed and direct renders must agree for: " + response,
                    normalise(direct), normalise(streamed));
        }
    }

    /** Settling on a canonical reply that differs slightly still lands on the canonical text. */
    @Test public void thecanonicalReplyWinsAtCompletion() {
        ProgressiveResponseView view = view();
        view.update("The answer is 41", true);
        view.settle("The answer is 42.");
        assertEquals("The answer is 42.", allText(view).trim());
    }

    // ---- width -------------------------------------------------------------------------------------------------

    /** Width is earned once and kept, so the bubble never breathes while it is being read. */
    @Test public void widthIsMonotonicAcrossOneAnswer() {
        ProgressiveResponseView view = view();
        view.update("Short", true);
        assertFalse(view.prefersWide());
        view.update("Short\n\n```java\nint x = 1;\n```", true);
        assertTrue("a code block earns the room", view.prefersWide());
        view.settle("Short");
        assertTrue("and it is not taken away again mid-answer", view.prefersWide());
    }

    // ---- lifecycle and performance -------------------------------------------------------------------------------

    /** A finished answer holds no stream state. */
    @Test public void settlingReleasesStreamState() {
        ProgressiveResponseView view = view();
        view.onDelta("partial");
        assertTrue(view.hasStreamState());
        view.settle("partial and complete");
        assertFalse("a completed turn must not keep a scheduler alive", view.hasStreamState());
    }

    @Test public void cancellingReleasesStreamState() {
        ProgressiveResponseView view = view();
        view.onDelta("partial");
        view.cancelPendingRenders();
        assertFalse(view.hasStreamState());
    }

    /** Views do not accumulate as an answer grows: one block in, one block on screen. */
    @Test public void viewsDoNotAccumulateAsTheAnswerGrows() {
        ProgressiveResponseView view = view();
        String answer = "## Heading\n\nParagraph text.\n\n- one\n- two";
        for (int i = 1; i <= answer.length(); i++) view.update(answer.substring(0, i), true);
        view.update(answer, false);
        assertEquals("three blocks, however many fragments arrived",
                ResponseBlocks.parse(answer).size(), view.getChildCount());
    }

    /** Redrawing identical text costs nothing at all. */
    @Test public void anIdenticalUpdateIsANoOp() {
        ProgressiveResponseView view = view();
        view.update("## Heading\n\nBody", true);
        View heading = view.getChildAt(0);
        int children = view.getChildCount();
        view.update("## Heading\n\nBody", true);
        assertEquals(children, view.getChildCount());
        assertSame(heading, view.getChildAt(0));
    }

    /** A completed answer has exactly one Copy per code block, never one per update. */
    @Test public void repeatedUpdatesNeverDuplicateCodeControls() {
        String answer = "```java\nint x = 1;\nint y = 2;\n```";
        ProgressiveResponseView view = streamCharacterByCharacter(answer);
        assertEquals(1, copyControls(view).size());
    }

    // ---- reduced motion -------------------------------------------------------------------------------------------

    /**
     * Reduced motion removes decoration, never content.
     *
     * <p>The rule that matters is that progressive rendering is not an animation: with animators
     * off, the answer still formats as it arrives and every block is fully visible.
     */
    @Test public void reducedMotionKeepsEveryBlockVisible() throws Exception {
        setAnimationScale(0f);
        try {
            assertFalse("the test needs animators genuinely off", UiKit.animationsEnabled());
            ProgressiveResponseView view = stream(
                    "## Heading", "## Heading\n\nBody text.", "## Heading\n\nBody text.\n\n- one");
            assertTrue(view.getChildCount() >= 3);
            for (int i = 0; i < view.getChildCount(); i++) {
                View child = view.getChildAt(i);
                assertEquals("no block may be left mid-fade with animations off",
                        1f, child.getAlpha(), 0.001f);
                assertEquals("and none may be left offset",
                        0f, child.getTranslationY(), 0.001f);
                assertEquals(View.VISIBLE, child.getVisibility());
            }
            assertTrue("reduced motion must not mean reduced content",
                    allText(view).contains("Body text."));
        } finally {
            setAnimationScale(1f);
        }
    }

    /** Reduced motion is not "disable streaming": the answer still formats as it arrives. */
    @Test public void reducedMotionStillFormatsProgressively() throws Exception {
        setAnimationScale(0f);
        try {
            ProgressiveResponseView view = view();
            view.update("## Heading\n\n```java\nint x = 1;", true);
            String text = allText(view);
            assertFalse("headings still format", text.contains("##"));
            assertFalse("code blocks still form", text.contains("```"));
            assertTrue(text.contains("int x = 1;"));
        } finally {
            setAnimationScale(1f);
        }
    }

    /** Robolectric's own animator scale, matching how the rest of the suite disables motion. */
    private static void setAnimationScale(float scale) throws Exception {
        java.lang.reflect.Method setScale = org.robolectric.shadows.ShadowValueAnimator.class
                .getDeclaredMethod("setDurationScale", float.class);
        setScale.setAccessible(true);
        setScale.invoke(null, scale);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /** Every piece of text a view tree displays, in order. */
    private static String allText(View root) {
        StringBuilder out = new StringBuilder();
        collectText(root, out);
        return out.toString();
    }

    private static void collectText(View view, StringBuilder out) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 0) {
                if (out.length() > 0) out.append('\n');
                out.append(text);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectText(group.getChildAt(i), out);
        }
    }

    /** Whitespace-insensitive, so parity is about words and formatting rather than layout. */
    private static String normalise(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private static boolean hasStyleSpan(View root) {
        for (View view : descendants(root)) {
            if (!(view instanceof TextView)) continue;
            CharSequence text = ((TextView) view).getText();
            if (!(text instanceof android.text.Spanned)) continue;
            android.text.style.StyleSpan[] spans = ((android.text.Spanned) text)
                    .getSpans(0, text.length(), android.text.style.StyleSpan.class);
            if (spans != null && spans.length > 0) return true;
        }
        return false;
    }

    private static List<Button> copyControls(View root) {
        List<Button> found = new ArrayList<>();
        for (View view : descendants(root)) {
            if (view instanceof Button && "Copy".contentEquals(((Button) view).getText())) {
                found.add((Button) view);
            }
        }
        return found;
    }

    /** True when some run of some TextView in the tree is both bold and italic. */
    private static boolean boldItalicSomewhere(View root) {
        for (View view : descendants(root)) {
            if (!(view instanceof TextView)) continue;
            CharSequence text = ((TextView) view).getText();
            if (!(text instanceof android.text.Spanned)) continue;
            android.text.Spanned spanned = (android.text.Spanned) text;
            for (android.text.style.StyleSpan span :
                    spanned.getSpans(0, spanned.length(), android.text.style.StyleSpan.class)) {
                int mask = 0;
                int start = spanned.getSpanStart(span);
                int end = spanned.getSpanEnd(span);
                for (android.text.style.StyleSpan other :
                        spanned.getSpans(start, end, android.text.style.StyleSpan.class)) {
                    if (spanned.getSpanStart(other) <= start && spanned.getSpanEnd(other) >= end) {
                        mask |= other.getStyle();
                    }
                }
                if ((mask & android.graphics.Typeface.BOLD) != 0
                        && (mask & android.graphics.Typeface.ITALIC) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasTaskBox(View root) { return !taskBoxes(root).isEmpty(); }

    /** Every task checkbox drawn in the tree, in order. */
    private static List<TaskBoxSpan> taskBoxes(View root) {
        List<TaskBoxSpan> found = new ArrayList<>();
        for (View view : descendants(root)) {
            if (!(view instanceof TextView)) continue;
            CharSequence text = ((TextView) view).getText();
            if (!(text instanceof android.text.Spanned)) continue;
            android.text.Spanned spanned = (android.text.Spanned) text;
            for (TaskBoxSpan span : spanned.getSpans(0, spanned.length(), TaskBoxSpan.class)) {
                found.add(span);
            }
        }
        return found;
    }

    private static List<android.widget.TableRow> tableRows(View root) {
        List<android.widget.TableRow> found = new ArrayList<>();
        for (View view : descendants(root)) {
            if (view instanceof android.widget.TableRow) found.add((android.widget.TableRow) view);
        }
        return found;
    }

    private static List<View> tables(View root) {
        List<View> found = new ArrayList<>();
        for (View view : descendants(root)) {
            if (view instanceof android.widget.TableLayout) found.add(view);
        }
        return found;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
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

    static {
        assertNotNull("keeps the helper set honest", ProgressiveResponseView.class);
    }
}
