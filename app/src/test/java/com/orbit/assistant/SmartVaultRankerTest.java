package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Smart Vault's ranking rules, over plain documents: literal matches always first and never lost,
 * keywords from derived text, meaning with a floor, and related items from the local index alone.
 */
public class SmartVaultRankerTest {

    private static SmartVaultRanker.Doc doc(String id, long created, String title, String own,
                                            String derived, float[]... vectors) {
        return new SmartVaultRanker.Doc(id, created, title, title + "\n" + own, derived,
                Collections.singletonList(title + " " + own),
                vectors.length == 0 ? null : vectors, Collections.emptyList());
    }

    private static List<String> ids(List<SmartVaultRanker.Result> results) {
        List<String> out = new ArrayList<>();
        for (SmartVaultRanker.Result r : results) out.add(r.id);
        return out;
    }

    private static float[] unit(float... v) {
        return SmartVaultEmbedder.normalize(v);
    }

    @Test public void everyLiteralMatchIsIncludedAndRanksAboveEverythingElse() {
        List<SmartVaultRanker.Doc> docs = Arrays.asList(
                doc("meaning", 3, "Evening meal ideas", "fish and rice", "", unit(1, 0, 0)),
                doc("literal", 1, "Groceries", "buy salmon fillets", "", unit(0, 1, 0)),
                doc("title", 2, "Salmon recipe", "bake it", "", unit(0, 0, 1)));
        List<SmartVaultRanker.Result> ranked =
                SmartVaultRanker.rank("salmon", docs, unit(1, 0, 0));
        assertEquals("the title match first, then the body match, then meaning",
                Arrays.asList("title", "literal", "meaning"), ids(ranked));
        assertEquals(SmartVaultRanker.Reason.TITLE, ranked.get(0).reason);
        assertEquals(SmartVaultRanker.Reason.LITERAL, ranked.get(1).reason);
        assertEquals(SmartVaultRanker.Reason.MEANING, ranked.get(2).reason);
    }

    @Test public void anExactTitleOutranksAPartialOne() {
        List<SmartVaultRanker.Doc> docs = Arrays.asList(
                doc("partial", 2, "Tax return notes 2025", "", ""),
                doc("exact", 1, "Tax return", "", ""));
        assertEquals("exact", SmartVaultRanker.rank("tax return", docs, null).get(0).id);
    }

    @Test public void textOrbitReadIsSearchableAndSaysSo() {
        List<SmartVaultRanker.Doc> docs = Arrays.asList(
                doc("shot", 1, "Screenshot", "", "Network HomeNet password blue-otter-4417"),
                doc("other", 2, "Notes", "nothing here", ""));
        List<SmartVaultRanker.Result> ranked = SmartVaultRanker.rank("blue-otter", docs, null);
        assertEquals(Collections.singletonList("shot"), ids(ranked));
        assertEquals(SmartVaultRanker.Reason.RECOGNIZED, ranked.get(0).reason);
        assertTrue("the card can show where it was found",
                ranked.get(0).excerpt.contains("blue-otter"));
    }

    @Test public void keywordsFoldPluralsAndMatchTheWordStillBeingTyped() {
        List<SmartVaultRanker.Doc> docs = Arrays.asList(
                doc("recipes", 1, "Weekend recipes", "", ""),
                doc("unrelated", 2, "Car insurance", "", ""));
        assertEquals(Collections.singletonList("recipes"),
                ids(SmartVaultRanker.rank("recipe", docs, null)));
        assertEquals("a half-typed word still finds it", Collections.singletonList("recipes"),
                ids(SmartVaultRanker.rank("week", docs, null)));
    }

    @Test public void wordsInAnyOrderStillMatch() {
        List<SmartVaultRanker.Doc> docs = Arrays.asList(
                doc("flight", 1, "Trip", "BA 117 London to New York, gate B32", ""),
                doc("dentist", 2, "Dentist", "Tuesday 3pm", ""));
        List<String> found = ids(SmartVaultRanker.rank("new york gate ", docs, null));
        assertEquals(Collections.singletonList("flight"), found);
    }

    @Test public void meaningNeedsToClearTheFloorAndStayNearTheBest() {
        List<SmartVaultRanker.Doc> docs = Arrays.asList(
                doc("close", 1, "A", "", "", unit(1, 0.1f, 0)),
                doc("weak", 2, "B", "", "", unit(1, 3, 0)),
                doc("far", 3, "C", "", "", unit(0, 0, 1)));
        List<String> found = ids(SmartVaultRanker.rank("zzz", docs, unit(1, 0, 0)));
        assertTrue(found.contains("close"));
        assertFalse("well below the best meaning score", found.contains("weak"));
        assertFalse("unrelated", found.contains("far"));
    }

    @Test public void withoutAQueryVectorNothingIsFoundByMeaning() {
        List<SmartVaultRanker.Doc> docs = Collections.singletonList(
                doc("a", 1, "Alpha", "", "", unit(1, 0, 0)));
        assertTrue(SmartVaultRanker.rank("zzz", docs, null).isEmpty());
    }

    @Test public void anEmptyQueryRanksNothing() {
        List<SmartVaultRanker.Doc> docs = Collections.singletonList(doc("a", 1, "Alpha", "", ""));
        assertTrue(SmartVaultRanker.rank("   ", docs, null).isEmpty());
    }

    @Test public void relatedItemsComeFromTheIndexAndExcludeTheItemItself() {
        List<SmartVaultRanker.Doc> docs = Arrays.asList(
                doc("salmon", 1, "Salmon", "", "", unit(1, 0.2f, 0)),
                doc("cookies", 2, "Cookies", "", "", unit(1, 0.4f, 0)),
                doc("taxes", 3, "Taxes", "", "", unit(0, 0, 1)));
        List<String> related = SmartVaultRanker.related("salmon", docs, 4);
        assertEquals(Collections.singletonList("cookies"), related);
    }

    @Test public void relatedFallsBackToSharedRareWordsWithoutTheModel() {
        List<SmartVaultRanker.Doc> docs = Arrays.asList(
                doc("a", 1, "Sourdough starter", "feed the sourdough starter flour water", ""),
                doc("b", 2, "Bread", "sourdough loaf with starter", ""),
                doc("c", 3, "Car", "tyre pressure and oil change", ""));
        List<String> related = SmartVaultRanker.related("a", docs, 4);
        assertEquals(Collections.singletonList("b"), related);
    }
}
