package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Compact chat previews: Markdown gives up its syntax and keeps its words, while ordinary text
 * that merely contains the same characters is left alone.
 */
public final class MarkdownPreviewTest {
    private static final int MAX = 100;

    private static String preview(String source) {
        return OrbitMarkdown.toPreviewText(source, MAX);
    }

    @Test public void plainTextIsUnchanged() {
        assertEquals("Just a normal reply with nothing special.",
                preview("Just a normal reply with nothing special."));
    }

    @Test public void boldLosesItsMarkersAndKeepsItsWords() {
        assertEquals("Detroit Lions: regular season, 2016 through 2025",
                preview("**Detroit Lions:** regular season, 2016 through 2025"));
        assertEquals("Detroit Lions: regular season",
                preview("__Detroit Lions:__ regular season"));
    }

    @Test public void italicsLoseTheirMarkers() {
        assertEquals("This is probably the best option",
                preview("This is *probably* the best option"));
        assertEquals("This is probably the best option",
                preview("This is _probably_ the best option"));
    }

    @Test public void headingsBecomeReadableText() {
        assertEquals("OLED vs Mini LED", preview("## OLED vs Mini LED"));
    }

    @Test public void aHeadingFollowedByTextIsJoinedIntoOneLine() {
        assertEquals("OLED vs Mini LED · OLED is usually best for perfect blacks",
                preview("## OLED vs Mini LED\nOLED is usually best for perfect blacks"));
    }

    @Test public void blockquotesLoseTheirMarker() {
        assertEquals("Important result", preview("> Important result"));
    }

    @Test public void theReportedMixedCaseReadsCleanly() {
        String raw = "## OLED vs Mini LED\n> **Quick takeaway:** OLED is usually best for perfect blacks";
        assertEquals("OLED vs Mini LED · Quick takeaway: OLED is usually best for perfect blacks",
                preview(raw));
    }

    @Test public void bulletedListsBecomeOneCompactRun() {
        assertEquals("First option · Second option · Third option",
                preview("- First option\n- Second option\n- Third option"));
        assertEquals("First option · Second option",
                preview("* First option\n* Second option"));
    }

    @Test public void numberedListsBecomeOneCompactRun() {
        assertEquals("First · Second", preview("1. First\n2. Second"));
        assertEquals("First · Second", preview("1) First\n2) Second"));
    }

    @Test public void inlineCodeKeepsItsContent() {
        assertEquals("Set versionName in the build file",
                preview("Set `versionName` in the build file"));
    }

    @Test public void linksKeepTheirVisibleTextAndHideTheUrl() {
        String result = preview("Read the [official documentation](https://example.com)");
        assertEquals("Read the official documentation", result);
        assertFalse(result.contains("https://"));
    }

    @Test public void aLinkWithNoTextFallsBackToItsUrl() {
        // The URL is the only useful content in this case.
        assertEquals("https://example.com", preview("[](https://example.com)"));
    }

    @Test public void bareUrlsAreLeftAlone() {
        assertEquals("See https://example.com for details",
                preview("See https://example.com for details"));
    }

    @Test public void fencedCodeIsLeftOutButSurroundingProseSurvives() {
        String raw = "Here is the fix:\n```java\nint x = 2;\nSystem.out.println(x);\n```\nThat is all.";
        String result = preview(raw);
        assertEquals("Here is the fix: · That is all.", result);
        assertFalse("code must not be dumped into the card", result.contains("System.out"));
    }

    @Test public void aMessageThatIsOnlyCodeGetsAShortLabel() {
        assertEquals("Code snippet", preview("```java\nint x = 2;\n```"));
    }

    @Test public void severalParagraphsAreJoinedWithoutBlankGaps() {
        String result = preview("First paragraph.\n\n\nSecond paragraph.");
        assertEquals("First paragraph. · Second paragraph.", result);
        assertFalse(result.contains("\n"));
    }

    @Test public void longResponsesAreTruncatedAfterNormalization() {
        StringBuilder raw = new StringBuilder("**Heading:** ");
        for (int i = 0; i < 60; i++) raw.append("word ");
        String result = preview(raw.toString());

        assertTrue(result.length() <= MAX);
        assertTrue(result.endsWith("…"));
        // The budget is spent on words, not on the markers that were removed first.
        assertTrue(result.startsWith("Heading: word"));
    }

    @Test public void emojiSurvive() {
        assertEquals("All done 🎉 nice work", preview("**All done** 🎉 nice work"));
    }

    @Test public void ordinaryMathematicalAsterisksAreNotTreatedAsFormatting() {
        assertEquals("2 * 4 = 8", preview("2 * 4 = 8"));
        // Two asterisks in one line still must not be read as emphasis around a space.
        assertEquals("2 * 4 = 8 * 2", preview("2 * 4 = 8 * 2"));
    }

    @Test public void ordinaryHashtagsSurvive() {
        assertEquals("Trending #Orbit today", preview("Trending #Orbit today"));
        assertEquals("#hashtag at the start", preview("#hashtag at the start"));
    }

    @Test public void identifiersContainingUnderscoresSurvive() {
        assertEquals("Use some_var_name and another_value here",
                preview("Use some_var_name and another_value here"));
        assertEquals("MAX_RETRY_COUNT is the limit", preview("MAX_RETRY_COUNT is the limit"));
    }

    @Test public void emptyAndNearEmptyInputProduceNothing() {
        assertEquals("", preview(null));
        assertEquals("", preview(""));
        assertEquals("", preview("   \n\n  "));
    }

    @Test public void incompleteMarkdownFromAnInterruptedResponseIsSafe() {
        // A stream cut mid-emphasis or mid-fence must not corrupt the words already received.
        assertEquals("A partial **response that stopped",
                preview("A partial **response that stopped"));
        assertEquals("Some text", preview("Some text\n```java\nint x ="));
    }

    @Test public void horizontalRulesAreDropped() {
        assertEquals("Above · Below", preview("Above\n\n---\n\nBelow"));
    }

    @Test public void tableRowsBecomeReadableAndSeparatorsAreDropped() {
        String raw = "| Model | Score |\n|---|---|\n| OLED | 9 |";
        assertEquals("Model Score · OLED 9", preview(raw));
    }

    @Test public void boldInsideABulletIsFullyFlattened() {
        assertEquals("Quick takeaway: it depends",
                preview("- **Quick takeaway:** it depends"));
    }

    @Test public void nestedEmphasisAroundALinkIsFullyFlattened() {
        assertEquals("official documentation",
                preview("**[official documentation](https://example.com)**"));
    }

    @Test public void theOriginalMessageIsNeverModified() {
        String original = "## Heading\n> **Bold quote**\n- item";
        String copy = new String(original.toCharArray());
        preview(original);
        assertEquals("preview generation must not touch the stored message", copy, original);
    }

    @Test public void aZeroLimitReturnsTheFullNormalizedText() {
        assertEquals("Heading: value", OrbitMarkdown.toPreviewText("**Heading:** value", 0));
    }
}
