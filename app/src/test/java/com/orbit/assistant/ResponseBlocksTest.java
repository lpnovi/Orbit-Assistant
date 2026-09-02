package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * What a response is made of, and when Orbit is allowed to decide.
 *
 * <p>Progressive rendering lives or dies on this. A parser that commits too early reshapes a
 * paragraph into a table and back again as the text grows, which is far more distracting than the
 * raw Markdown it replaced; one that commits too late leaves the user reading {@code ## Heading}
 * and triple backticks for the whole answer, which is the bug being fixed. So every construct here
 * is asserted twice: that it is <em>not</em> recognised while the evidence is incomplete, and that
 * it <em>is</em> recognised the moment the evidence arrives.
 *
 * <p>These are plain JUnit tests. Deciding what the blocks are involves no Context, no View and no
 * measurement, which is exactly why that decision was worth separating out.
 */
public final class ResponseBlocksTest {

    // ---- paragraphs -------------------------------------------------------------------------------

    @Test public void ordinaryTextIsOneParagraph() {
        List<ResponseBlocks.Block> blocks = ResponseBlocks.parse("The answer is fairly simple.");
        assertEquals(1, blocks.size());
        assertEquals(ResponseBlocks.Kind.PARAGRAPH, blocks.get(0).kind);
        assertEquals("The answer is fairly simple.", blocks.get(0).source);
    }

    @Test public void ablankLineSeparatesParagraphs() {
        List<ResponseBlocks.Block> blocks = ResponseBlocks.parse("First one.\n\nSecond one.");
        assertEquals(2, blocks.size());
        assertEquals("First one.", blocks.get(0).source);
        assertEquals("Second one.", blocks.get(1).source);
    }

    /** While streaming, the block being written is open; everything above it is settled. */
    @Test public void onlyTheLastBlockIsIncompleteWhileStreaming() {
        List<ResponseBlocks.Block> blocks =
                ResponseBlocks.parse("## Title\n\nA paragraph still being writ", true);
        assertEquals(2, blocks.size());
        assertTrue("a heading with content after it is settled", blocks.get(0).complete);
        assertFalse("the paragraph is still arriving", blocks.get(1).complete);
    }

    @Test public void afinishedResponseHasNoIncompleteBlocks() {
        for (ResponseBlocks.Block block
                : ResponseBlocks.parse("## Title\n\nBody text\n\n- one\n- two", false)) {
            assertTrue(block.kind + " must be complete once the response is", block.complete);
        }
    }

    // ---- headings ----------------------------------------------------------------------------------

    /** A heading formats as soon as the line establishes it, without waiting for the answer. */
    @Test public void aheadingIsRecognisedAsSoonAsItIsEstablished() {
        List<ResponseBlocks.Block> blocks = ResponseBlocks.parse("## Things to try\n", true);
        assertEquals(1, blocks.size());
        assertEquals(ResponseBlocks.Kind.HEADING, blocks.get(0).kind);
    }

    @Test public void alonePoundSignIsNotYetAHeading() {
        // "#" with no space and no text is not a heading, and guessing that it will become one is
        // how a paragraph flickers into heading typography and back.
        assertEquals(ResponseBlocks.Kind.PARAGRAPH,
                ResponseBlocks.parse("#", true).get(0).kind);
        assertEquals(ResponseBlocks.Kind.PARAGRAPH,
                ResponseBlocks.parse("##Things", true).get(0).kind);
    }

    // ---- lists -------------------------------------------------------------------------------------

    @Test public void alistIsRecognisedFromItsFirstItem() {
        List<ResponseBlocks.Block> blocks = ResponseBlocks.parse("- first item", true);
        assertEquals(1, blocks.size());
        assertEquals(ResponseBlocks.Kind.LIST, blocks.get(0).kind);
    }

    @Test public void consecutiveItemsAreOneListBlock() {
        List<ResponseBlocks.Block> blocks = ResponseBlocks.parse("- one\n- two\n- three");
        assertEquals(1, blocks.size());
        assertEquals(ResponseBlocks.Kind.LIST, blocks.get(0).kind);
        assertEquals("- one\n- two\n- three", blocks.get(0).source);
    }

    @Test public void numberedListsAreListsToo() {
        assertEquals(ResponseBlocks.Kind.LIST,
                ResponseBlocks.parse("1. first\n2. second").get(0).kind);
    }

    /** A paragraph above a list stays a settled paragraph as the list grows beneath it. */
    @Test public void ablockAboveAGrowingListStaysSettled() {
        List<ResponseBlocks.Block> blocks = ResponseBlocks.parse("Intro line.\n\n- one\n- tw", true);
        assertEquals(2, blocks.size());
        assertTrue(blocks.get(0).complete);
        assertEquals("Intro line.", blocks.get(0).source);
        assertEquals(ResponseBlocks.Kind.LIST, blocks.get(1).kind);
    }

    // ---- quotes and rules ----------------------------------------------------------------------------

    @Test public void aquoteIsRecognisedOnceEstablished() {
        List<ResponseBlocks.Block> blocks = ResponseBlocks.parse("> something worth quoting\n\nAfter");
        assertEquals(2, blocks.size());
        assertEquals(ResponseBlocks.Kind.QUOTE, blocks.get(0).kind);
    }

    @Test public void aruleIsOnlyARuleWhenUnambiguous() {
        assertEquals(ResponseBlocks.Kind.RULE,
                ResponseBlocks.parse("Above\n\n---\n\nBelow").get(1).kind);
        // At the very end of a stream those dashes could still be growing into something else, so
        // the rule waits one fragment rather than flickering in.
        assertEquals(ResponseBlocks.Kind.PARAGRAPH,
                ResponseBlocks.parse("Above\n\n---", true).get(1).kind);
        assertEquals("and it settles as a rule once the answer finishes",
                ResponseBlocks.Kind.RULE, ResponseBlocks.parse("Above\n\n---", false).get(1).kind);
    }

    // ---- code ------------------------------------------------------------------------------------------

    /**
     * An open fence is a code block, not a paragraph of backticks.
     *
     * <p>Once the fence has been written Orbit knows what is coming, so the user should be looking
     * at a code surface rather than at the syntax that produced it.
     */
    @Test public void anOpenFenceIsAlreadyACodeBlock() {
        List<ResponseBlocks.Block> blocks = ResponseBlocks.parse(
                "```java\nSystem.out.println(\"hi\");", true);
        assertEquals(1, blocks.size());
        assertEquals(ResponseBlocks.Kind.CODE, blocks.get(0).kind);
        assertEquals("java", blocks.get(0).language);
        assertFalse("it is still being written", blocks.get(0).complete);
        assertEquals("System.out.println(\"hi\");", blocks.get(0).source);
        assertFalse("the fence itself is never shown once it is understood",
                blocks.get(0).source.contains("```"));
    }

    @Test public void aclosedFenceCompletesTheBlockWithoutChangingItsCode() {
        String code = "System.out.println(\"hi\");";
        ResponseBlocks.Block open = ResponseBlocks.parse("```java\n" + code, true).get(0);
        ResponseBlocks.Block closed = ResponseBlocks.parse("```java\n" + code + "\n```", true).get(0);
        assertEquals("closing a fence must not rewrite the code", open.source, closed.source);
        assertEquals(open.language, closed.language);
        assertFalse(open.complete);
        assertTrue(closed.complete);
    }

    @Test public void codeKeepsItsOwnBlankLinesAndIndentation() {
        ResponseBlocks.Block block = ResponseBlocks.parse(
                "```\nline one\n\n    indented\n```").get(0);
        assertEquals("line one\n\n    indented", block.source);
    }

    /** A fence with no language is still a code block. */
    @Test public void afenceWithoutALanguageIsStillCode() {
        ResponseBlocks.Block block = ResponseBlocks.parse("```\nplain\n```").get(0);
        assertEquals(ResponseBlocks.Kind.CODE, block.kind);
        assertEquals("", block.language);
    }

    // ---- tables -----------------------------------------------------------------------------------------

    /**
     * Pipes alone are not a table.
     *
     * <p>Ordinary prose contains them, and reshaping a sentence into a one-cell table the instant a
     * pipe arrives - then tearing it down when the next fragment proves otherwise - is exactly the
     * violent resizing progressive rendering has to avoid.
     */
    @Test public void apipeLineIsNotATableUntilTheDividerProvesIt() {
        assertEquals(ResponseBlocks.Kind.PARAGRAPH,
                ResponseBlocks.parse("| Name | Value |", true).get(0).kind);
        assertEquals(ResponseBlocks.Kind.PARAGRAPH,
                ResponseBlocks.parse("a | b | c", true).get(0).kind);
    }

    @Test public void thedividerRowPromotesItToATable() {
        List<ResponseBlocks.Block> blocks = ResponseBlocks.parse(
                "| Name | Value |\n| --- | --- |", true);
        assertEquals(1, blocks.size());
        assertEquals(ResponseBlocks.Kind.TABLE, blocks.get(0).kind);
    }

    @Test public void tableRowsAppendInOrder() {
        ResponseBlocks.Block block = ResponseBlocks.parse(
                "| A | B |\n| --- | --- |\n| 1 | 2 |\n| 3 | 4 |").get(0);
        assertEquals(ResponseBlocks.Kind.TABLE, block.kind);
        assertTrue(block.source.indexOf("| 1 | 2 |") < block.source.indexOf("| 3 | 4 |"));
    }

    /** A table promotes once and stays a table as rows arrive. */
    @Test public void atablePromotesOnlyOnce() {
        String[] stages = {
                "| A | B |\n| --- | --- |",
                "| A | B |\n| --- | --- |\n| 1 | 2 |",
                "| A | B |\n| --- | --- |\n| 1 | 2 |\n| 3 | 4 |"};
        for (String stage : stages) {
            List<ResponseBlocks.Block> blocks = ResponseBlocks.parse(stage, true);
            assertEquals("one table block at every stage", 1, blocks.size());
            assertEquals(ResponseBlocks.Kind.TABLE, blocks.get(0).kind);
        }
    }

    // ---- images ------------------------------------------------------------------------------------------

    /** A half-written image is text. Nothing can be fetched from a URL that is still arriving. */
    @Test public void anIncompleteImageIsNotAnImageBlock() {
        assertEquals(ResponseBlocks.Kind.PARAGRAPH,
                ResponseBlocks.parse("![a diagram](https://exa", true).get(0).kind);
        assertEquals(ResponseBlocks.Kind.PARAGRAPH,
                ResponseBlocks.parse("![a diagram]", true).get(0).kind);
    }

    @Test public void acompleteImageBecomesAnImageBlock() {
        assertEquals(ResponseBlocks.Kind.IMAGE,
                ResponseBlocks.parse("![a diagram](https://example.com/a.png)", true).get(0).kind);
    }

    // ---- unfinished inline markup -----------------------------------------------------------------------------

    /**
     * A delimiter whose partner has not arrived is withheld; the words are not.
     *
     * <p>This is the whole policy for unfinished inline syntax: never hide content, never guess at
     * formatting, and never leave the user reading the machinery.
     */
    @Test public void adanglingDelimiterIsWithheldButItsContentIsNot() {
        assertEquals("This is impor", ResponseBlocks.activeText("This is **impor"));
        assertEquals("This is ", ResponseBlocks.activeText("This is **"));
        assertEquals("code so far", ResponseBlocks.activeText("code so far`"));
        assertEquals("struck", ResponseBlocks.activeText("struck~~"));
    }

    /** A delimiter that closes a pair is real formatting and is never trimmed. */
    @Test public void acompletedDelimiterIsLeftAlone() {
        assertEquals("This is **important**", ResponseBlocks.activeText("This is **important**"));
        assertEquals("a `snippet`", ResponseBlocks.activeText("a `snippet`"));
        assertEquals("", ResponseBlocks.activeText(""));
        assertEquals("", ResponseBlocks.activeText(null));
    }

    /** Ordinary text is never touched. */
    @Test public void plainTextPassesThroughUnchanged() {
        assertEquals("2 * 4 = 8", ResponseBlocks.activeText("2 * 4 = 8"));
        assertEquals("The answer.", ResponseBlocks.activeText("The answer."));
    }

    // ---- signatures ---------------------------------------------------------------------------------------------

    /** Identical blocks share a signature, which is what lets the view leave them alone. */
    @Test public void identicalBlocksShareASignature() {
        String source = "## Title\n\nBody";
        assertEquals(ResponseBlocks.signatures(ResponseBlocks.parse(source)),
                ResponseBlocks.signatures(ResponseBlocks.parse(source)));
    }

    /** A growing answer keeps its earlier signatures, so earlier blocks are never rebuilt. */
    @Test public void agrowingAnswerKeepsItsEarlierSignatures() {
        List<String> first = ResponseBlocks.signatures(
                ResponseBlocks.parse("## Title\n\nSettled paragraph.\n\nNext one", true));
        List<String> later = ResponseBlocks.signatures(
                ResponseBlocks.parse("## Title\n\nSettled paragraph.\n\nNext one is longer", true));
        assertEquals("the heading is untouched", first.get(0), later.get(0));
        assertEquals("and so is the settled paragraph", first.get(1), later.get(1));
        assertFalse("only the block being written changes", first.get(2).equals(later.get(2)));
    }

    /**
     * Completion changes a block's identity only when it changes what the block shows.
     *
     * <p>The distinction matters more than it looks. A paragraph stops being the last block the
     * moment anything is written below it, and settling the response flips its {@code complete}
     * flag again — neither of which alters a single character on screen. Keying identity on the
     * flag would rebuild that paragraph twice for no visible reason, dropping a screen reader out
     * of it and losing any selection the user had made mid-answer.
     */
    @Test public void completionOnlyChangesBlocksWhoseAppearanceChanges() {
        List<String> streaming = ResponseBlocks.signatures(
                ResponseBlocks.parse("Settled.\n\nStill going", true));
        List<String> settled = ResponseBlocks.signatures(
                ResponseBlocks.parse("Settled.\n\nStill going", false));
        assertEquals("nothing about this text changes when the answer ends", streaming, settled);
    }

    /** A block whose dangling delimiter resolves does change, because its text does. */
    @Test public void completingADanglingDelimiterChangesTheBlock() {
        String open = ResponseBlocks.parse("This is **impor", true).get(0).signature();
        String closed = ResponseBlocks.parse("This is **important**", false).get(0).signature();
        assertFalse("the words and their formatting both changed", open.equals(closed));
    }

    /** Closing a fence changes the code block, because its Copy control becomes available. */
    @Test public void closingAFenceChangesTheCodeBlock() {
        String open = ResponseBlocks.parse("```java\nint x = 1;", true).get(0).signature();
        String closed = ResponseBlocks.parse("```java\nint x = 1;\n```", true).get(0).signature();
        assertFalse("completeness is part of a code block's identity", open.equals(closed));
    }

    /** Code is never delimiter-trimmed: a trailing backtick is part of the program. */
    @Test public void codeContentIsNeverTrimmed() {
        ResponseBlocks.Block block = ResponseBlocks.parse("```\nString s = \"a`\";", true).get(0);
        assertEquals(ResponseBlocks.Kind.CODE, block.kind);
        assertFalse(block.complete);
        assertEquals("String s = \"a`\";", block.displaySource());
    }

    // ---- robustness -------------------------------------------------------------------------------------------------

    @Test public void emptyAndNullSourcesProduceNoBlocks() {
        assertTrue(ResponseBlocks.parse(null, true).isEmpty());
        assertTrue(ResponseBlocks.parse("", true).isEmpty());
        assertTrue(ResponseBlocks.parse("   \n\n  ", true).isEmpty());
    }

    /** Every fragment of a growing answer parses without throwing. */
    @Test public void everyPrefixOfARichAnswerParsesSafely() {
        String answer = "## Heading\n\nSome **bold** text.\n\n- one\n- two\n\n> quoted\n\n"
                + "```java\nint x = 1;\n```\n\n| A | B |\n| --- | --- |\n| 1 | 2 |\n\n---\n\nEnd.";
        for (int i = 0; i <= answer.length(); i++) {
            String prefix = answer.substring(0, i);
            ResponseBlocks.parse(prefix, true);
            ResponseBlocks.parse(prefix, false);
        }
    }
}
