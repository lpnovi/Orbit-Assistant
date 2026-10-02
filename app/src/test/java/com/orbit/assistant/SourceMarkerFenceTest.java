package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.8.3.0-beta.6: a hosted-search answer whose final {@code Source: https://...} marker the model
 * wrapped in a plain-text code fence shows Orbit's native source link, not a copyable "text" code
 * block. The rule is deliberately narrow, so every real code block keeps its Copy button.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class SourceMarkerFenceTest {
    private static final String URL = "https://www.bmj.com/content/345/bmj.e5661";
    private static final String BODY = "Coffee is not linked to higher mortality in this cohort.";

    private static String fenced(String label, String inside) {
        return BODY + "\n\n```" + label + "\n" + inside + "\n```";
    }

    // ---- what is normalized --------------------------------------------------------------------------

    @Test public void aRawTrailingSourceLineKeepsItsExistingBehavior() {
        String raw = BODY + "\n\nSource: " + URL;
        assertEquals(URL, SourceLinkUtil.sourceUrl(raw));
        assertEquals(BODY, SourceLinkUtil.displayText(raw));
        assertEquals(BODY + "\n\nSource: " + URL, SourceLinkUtil.copyText(raw));
        assertEquals("bmj.com", SourceLinkUtil.sourceLabel(raw));
    }

    @Test public void aFinalFenceHoldingOnlyTheMarkerBecomesSourceMetadata() {
        for (String label : new String[]{"", "text", "txt", "plaintext", "TEXT", "plain"}) {
            String raw = fenced(label, "Source: " + URL);
            assertEquals(label, URL, SourceLinkUtil.sourceUrl(raw));
            assertEquals(label, BODY, SourceLinkUtil.displayText(raw));
            assertFalse(label, SourceLinkUtil.displayText(raw).contains("```"));
            assertEquals(label, BODY + "\n\nSource: " + URL, SourceLinkUtil.copyText(raw));
            assertEquals("bmj.com", SourceLinkUtil.sourceLabel(raw));
        }
    }

    @Test public void tildeFencesTrailingSpaceAndReadMoreMarkersAreTheSameCase() {
        assertEquals(BODY, SourceLinkUtil.displayText(BODY + "\n\n~~~text\nSource: " + URL + "\n~~~\n"));
        assertEquals(BODY, SourceLinkUtil.displayText(BODY + "\n```text  \n  Source:  " + URL + "  \n```  \n\n"));
        assertEquals(URL, SourceLinkUtil.sourceUrl(fenced("text", "Read more: " + URL)));
    }

    @Test public void anAnswerThatIsOnlyTheFencedMarkerKeepsTheLink() {
        String raw = "```text\nSource: " + URL + "\n```";
        assertEquals(URL, SourceLinkUtil.sourceUrl(raw));
        assertEquals("", SourceLinkUtil.displayText(raw));
        assertEquals(URL, SourceLinkUtil.copyText(raw));
    }

    // ---- what is left alone ------------------------------------------------------------------------

    @Test public void aFenceWithAnythingElseInsideStaysCode() {
        String raw = fenced("text", "Source: https://example.com\necho hello");
        assertEquals(raw.trim(), SourceLinkUtil.displayText(raw));
        assertEquals("a Source line inside real code is not Orbit's marker", "",
                SourceLinkUtil.sourceUrl(raw));
    }

    @Test public void codeLanguagesStayCodeEvenWithOnlyASourceLine() {
        for (String label : new String[]{"python", "json", "powershell", "bash", "kotlin", "yaml"}) {
            String raw = fenced(label, "Source: " + URL);
            assertEquals(label, raw.trim(), SourceLinkUtil.displayText(raw));
            assertEquals(label, "", SourceLinkUtil.sourceUrl(raw));
        }
    }

    @Test public void sourceStringsInsideCodeStayCode() {
        String python = fenced("python", "print(\"Source: https://example.com\")");
        assertEquals(python.trim(), SourceLinkUtil.displayText(python));
        assertEquals("", SourceLinkUtil.sourceUrl(python));
        String json = fenced("json", "{\"source\": \"Source: https://example.com\"}");
        assertEquals(json.trim(), SourceLinkUtil.displayText(json));
    }

    @Test public void aFencedMarkerThatIsNotTheEndIsLeftAlone() {
        String raw = BODY + "\n\n```text\nSource: " + URL + "\n```\n\nOne more thought after it.";
        assertEquals(raw.trim(), SourceLinkUtil.displayText(raw));
    }

    @Test public void aFenceThatClosesAnEarlierBlockIsNotMistakenForTheMarker() {
        // The "opening" fence before the marker is really the end of an unclosed earlier block.
        String raw = "```text\nfirst\n```\n```\nSource: " + URL + "\n```";
        assertEquals("balanced earlier block: the trailing one is still normalized",
                "```text\nfirst\n```\n\nSource: " + URL,
                SourceLinkUtil.normalizeFencedSourceMarker(raw));
        String unbalanced = "```text\nfirst\n\n```\nSource: " + URL + "\n```";
        assertEquals(unbalanced, SourceLinkUtil.normalizeFencedSourceMarker(unbalanced));
    }

    @Test public void anInvalidUrlIsNeverPromotedToASource() {
        String raw = fenced("text", "Source: https://localhost");
        assertEquals(raw, SourceLinkUtil.normalizeFencedSourceMarker(raw));
    }

    @Test public void inlineLinksInProseAreUnchanged() {
        String raw = "See [the study](https://example.com/study) for details, and https://example.org too.";
        assertEquals(raw, SourceLinkUtil.displayText(raw));
        assertEquals("", SourceLinkUtil.sourceUrl(raw));
        assertEquals("https://example.com/study", SourceLinkUtil.firstUrl(raw));
    }

    @Test public void richAnswerProvenanceSeesTheNormalizedMarkerOnce() {
        String raw = fenced("text", "Source: " + URL);
        AssistantReply reply = new AssistantReply(raw);
        RichAnswerProvenance.Resolved resolved = RichAnswerProvenance.resolve(reply, true);
        assertEquals(1, resolved.urls.size());
        assertEquals(URL, resolved.urls.get(0));
    }

    // ---- what the user sees ------------------------------------------------------------------------

    @Test public void theRenderedAnswerHasNoCodeBlockForAFencedMarker() {
        Context context = RuntimeEnvironment.getApplication();
        View view = OrbitRichResponseRenderer.render(context,
                SourceLinkUtil.displayText(fenced("text", "Source: " + URL)), 0xFF202020, false);
        assertEquals(0, copyButtons(view));
        assertFalse(allText(view).contains("Source: "));
        assertTrue(allText(view).contains("Coffee"));
    }

    @Test public void realCodeBlocksStillRenderWithCopy() {
        Context context = RuntimeEnvironment.getApplication();
        for (String raw : new String[]{
                fenced("powershell", "Get-ChildItem C:\\"),
                fenced("json", "{\"a\": 1}"),
                fenced("python", "print(\"Source: https://example.com\")"),
                fenced("text", "Source: https://example.com\necho hello")}) {
            View view = OrbitRichResponseRenderer.render(context, SourceLinkUtil.displayText(raw),
                    0xFF202020, false);
            assertEquals(raw, 1, copyButtons(view));
        }
    }

    private static int copyButtons(View view) {
        int n = "Copy code block".contentEquals(String.valueOf(view.getContentDescription())) ? 1 : 0;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) n += copyButtons(group.getChildAt(i));
        }
        return n;
    }

    private static String allText(View view) {
        List<String> out = new ArrayList<>();
        collect(view, out);
        return String.join("\n", out);
    }

    private static void collect(View view, List<String> out) {
        if (view instanceof TextView) out.add(((TextView) view).getText().toString());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out);
        }
    }
}
