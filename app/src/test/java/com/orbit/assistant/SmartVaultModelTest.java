package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Smart Vault's items, suggestions and topics as stored data, and the on-device embedder against a
 * tiny model written in the real safetensors format.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35})
public class SmartVaultModelTest {

    @Rule public TemporaryFolder temp = new TemporaryFolder();

    // ---- the embedder ------------------------------------------------------------------------------

    private SmartVaultEmbedder tinyModel() throws Exception {
        String[] vocab = {"[PAD]", "[UNK]", "[CLS]", "[SEP]", "cat", "dog", "car", "##s", "run"};
        float[][] rows = {
                {0, 0}, {0, 0}, {0, 0}, {0, 0},
                {1, 0}, {0.9f, 0.1f}, {0, 1}, {0.1f, 0.1f}, {0.5f, 0.5f}};
        File v = temp.newFile("vocab.txt");
        try (FileOutputStream out = new FileOutputStream(v)) {
            out.write(String.join("\n", vocab).getBytes(StandardCharsets.UTF_8));
        }
        return SmartVaultEmbedder.open(v, writeSafetensors(rows, rows.length, 2, "F32"));
    }

    private File writeSafetensors(float[][] rows, int declaredRows, int dims, String dtype)
            throws Exception {
        int bytes = rows.length * dims * 4;
        String header = new JSONObject().put("embeddings", new JSONObject()
                .put("dtype", dtype)
                .put("shape", new JSONArray(Arrays.asList(declaredRows, dims)))
                .put("data_offsets", new JSONArray(Arrays.asList(0, bytes)))).toString();
        byte[] h = header.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(8 + h.length + bytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.putLong(h.length).put(h);
        for (float[] r : rows) for (float f : r) buf.putFloat(f);
        File file = temp.newFile();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(buf.array());
        }
        return file;
    }

    @Test public void similarWordsEmbedCloseAndDifferentOnesApart() throws Exception {
        SmartVaultEmbedder model = tinyModel();
        float[] cat = model.embed("Cat");
        float[] dog = model.embed("dog!");
        float[] car = model.embed("CAR");
        assertTrue(SmartVaultEmbedder.dot(cat, dog) > 0.9f);
        assertTrue(SmartVaultEmbedder.dot(cat, car) < 0.1f);
        assertEquals("vectors are unit length", 1.0, SmartVaultEmbedder.dot(cat, cat), 1e-4);
    }

    @Test public void wordPiecesAndAccentsFollowBert() throws Exception {
        SmartVaultEmbedder model = tinyModel();
        assertEquals("cats is cat + ##s", Arrays.asList(4, 7), model.tokenIds("cats"));
        assertEquals("accents are stripped", Arrays.asList(4), model.tokenIds("Cát"));
        assertEquals("unknown words are dropped", Arrays.asList(5), model.tokenIds("zebra dog"));
        assertNull("nothing known means no vector", model.embed("zebra quokka"));
        List<String> words = SmartVaultEmbedder.basicTokens("Hi, you're here.");
        assertEquals(Arrays.asList("hi", ",", "you", "'", "re", "here", "."), words);
    }

    @Test public void aModelThatDoesNotMatchItsVocabularyIsRefused() throws Exception {
        File v = temp.newFile("vocab2.txt");
        try (FileOutputStream out = new FileOutputStream(v)) {
            out.write("[PAD]\n[UNK]\ncat".getBytes(StandardCharsets.UTF_8));
        }
        float[][] rows = {{1, 0}, {0, 1}};
        try {
            SmartVaultEmbedder.open(v, writeSafetensors(rows, 2, 2, "F32"));
            fail("two rows for three words must be refused");
        } catch (IllegalStateException expected) {
        }
        try {
            SmartVaultEmbedder.open(v, writeSafetensors(new float[][]{{1, 0}, {0, 1}, {1, 1}},
                    3, 2, "F16"));
            fail("a tensor that is not F32 must be refused");
        } catch (IllegalStateException expected) {
        }
    }

    @Test public void theDownloadIsPinnedToOneRevisionAndChecksum() {
        assertEquals(64, SmartVaultModel.WEIGHTS.sha256.length());
        assertEquals(64, SmartVaultModel.VOCAB.sha256.length());
        assertEquals(29_528L * 256L * 4L + 88L, SmartVaultModel.WEIGHTS.size);
        assertTrue(SmartVaultModel.sizeLabel().endsWith("MB"));
    }

    // ---- stored items ------------------------------------------------------------------------------

    @Test public void anItemFromBeforeSmartVaultReadsAndWritesAsBefore() throws Exception {
        JSONObject old = new JSONObject()
                .put("id", "a").put("type", "text").put("title", "").put("body", "Buy milk")
                .put("source", "Quick Capture").put("note", "").put("mediaPath", "")
                .put("createdAt", 1000L).put("modifiedAt", 1000L);
        OrbitVaultItem item = OrbitVaultItem.fromJson(old);
        assertNotNull(item);
        assertEquals("", item.capturedText);
        assertTrue(item.topics.isEmpty());
        assertNull(item.suggestions);
        JSONObject written = item.toJson();
        assertFalse("nothing new is written for an item that uses nothing new",
                written.has("capturedText") || written.has("topics") || written.has("suggestions"));
        assertEquals(old.length(), written.length());
    }

    @Test public void smartVaultFieldsRoundTripAndStaySeparate() throws Exception {
        VaultSuggestions s = new VaultSuggestions("Miso salmon", "A quick dinner.",
                Arrays.asList("Recipes", "#fish"), Arrays.asList("cooking"), "basis", 5L, "ChatGPT");
        OrbitVaultItem item = new OrbitVaultItem("i", OrbitVaultItem.TYPE_IMAGE, "", "", "Screen",
                "", "/x.jpg", "", 0, 0, false, "", "Miso 200C", Arrays.asList("dinner"), s,
                1000L, 1000L);
        OrbitVaultItem back = OrbitVaultItem.fromJson(item.toJson());
        assertEquals("Miso 200C", back.capturedText);
        assertEquals(Arrays.asList("dinner"), back.topics);
        assertEquals(Arrays.asList("recipes", "fish"), back.suggestions.topics);
        assertEquals(Arrays.asList("cooking"), back.suggestions.rejected);
        assertEquals("the user's title field stays empty", "", back.title);
        assertEquals("the suggestion is only displayed", "Miso salmon", back.displayTitle());
        assertTrue(back.titleIsSuggested());
        assertEquals(Arrays.asList("dinner", "recipes", "fish"), back.allTopics());
        assertTrue("search reaches captured text", back.searchHaystack().contains("miso 200c"));
    }

    @Test public void aTitleTheUserChoseAlwaysWins() {
        VaultSuggestions s = new VaultSuggestions("Suggested", "", null, null, "", 1L, "");
        OrbitVaultItem item = new OrbitVaultItem("i", OrbitVaultItem.TYPE_TEXT, "Mine", "body",
                "", "", "", "", 0, 0, false, "", "", null, s, 1L, 1L);
        assertEquals("Mine", item.displayTitle());
        assertFalse(item.titleIsSuggested());
    }

    @Test public void theFingerprintIgnoresTitlePinTopicsAndSuggestions() {
        OrbitVaultItem a = new OrbitVaultItem("i", OrbitVaultItem.TYPE_TEXT, "One", "body",
                "", "note", "", 1L, 1L);
        OrbitVaultItem b = a.copyPinned(true).copySmart("", Arrays.asList("x"),
                new VaultSuggestions("t", "s", null, null, "", 1L, ""), 1L)
                .copyWith("Two", "body", "note", 5L);
        assertEquals(a.contentFingerprint(), b.contentFingerprint());
        OrbitVaultItem edited = a.copyWith("One", "body!", "note", 6L);
        assertFalse(a.contentFingerprint().equals(edited.contentFingerprint()));
    }

    @Test public void rejectedTopicsAreNeverSuggestedAgain() {
        VaultSuggestions s = new VaultSuggestions("", "", Arrays.asList("travel", "flights"),
                null, "", 1L, "");
        VaultSuggestions after = s.rejecting("travel");
        assertEquals(Arrays.asList("flights"), after.topics);
        VaultSuggestions regenerated = new VaultSuggestions("", "", Arrays.asList("travel", "trips"),
                after.rejected, "", 2L, "");
        assertEquals(Arrays.asList("trips"), regenerated.topics);
    }

    @Test public void topicsAreShortLowerCaseAndReuseTheVaultsSpelling() {
        assertEquals("recipes", VaultTopics.normalize("  #Recipes "));
        assertEquals("home office setup", VaultTopics.normalize("Home office setup ideas today"));
        assertEquals("", VaultTopics.normalize("!!!"));
        assertEquals("recipe", VaultTopics.reuse("Recipes", Arrays.asList("recipe", "travel")));
        assertEquals("city", VaultTopics.reuse("cities", Arrays.asList("city")));
        assertEquals("new topic", VaultTopics.reuse("New topic", Arrays.asList("travel")));
        assertEquals(VaultTopics.MAX_PER_ITEM, VaultTopics.clean(Arrays.asList(
                "a1", "a2", "a3", "a4", "a5", "a6", "a7")).size());
    }
}
