package com.orbit.assistant;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Smart Vault's on-device meaning model: a static sentence embedding, in plain Java.
 *
 * <p>Why this shape rather than a neural runtime. A static embedding model (Model2Vec's
 * {@code potion-base-8M}, MIT licensed) is one table of 29,528 token vectors of 256 floats each.
 * Turning text into a vector is tokenising it with an ordinary BERT WordPiece vocabulary, looking
 * each token's row up, averaging, and normalising. There is no inference runtime, no native
 * library, no GPU delegate and nothing to crash on an unusual chipset, so it adds nothing to the
 * APK and works on every device Orbit supports. The table is memory-mapped straight from the file,
 * so "loading" costs a vocabulary read and a mapping rather than 30 MB of heap.
 *
 * <p>This class knows nothing about Android, the Vault or the network. It is handed two files that
 * {@link SmartVaultModel} downloaded and verified, and it turns text into vectors.
 */
final class SmartVaultEmbedder {

    /** Longest run of tokens averaged per text, matching the reference implementation. */
    static final int MAX_TOKENS = 512;
    private static final int MAX_WORD_CHARS = 100;

    private final Map<String, Integer> vocab;
    private final int unkId;
    private final FloatBuffer table;
    private final int rows;
    final int dims;

    private SmartVaultEmbedder(Map<String, Integer> vocab, FloatBuffer table, int rows, int dims) {
        this.vocab = vocab;
        Integer unk = vocab.get("[UNK]");
        this.unkId = unk == null ? -1 : unk;
        this.table = table;
        this.rows = rows;
        this.dims = dims;
    }

    /**
     * Opens a model, or throws when the files are not a model this class can read.
     *
     * <p>Every structural fact is checked against the file rather than assumed: one F32 tensor,
     * exactly two dimensions, a row for every vocabulary entry, and data that really fits inside
     * the file. A truncated or substituted file is refused here rather than read out of bounds.
     */
    static SmartVaultEmbedder open(File vocabFile, File safetensors) throws Exception {
        Map<String, Integer> vocab = readVocab(vocabFile);
        try (RandomAccessFile raf = new RandomAccessFile(safetensors, "r");
             FileChannel channel = raf.getChannel()) {
            long size = channel.size();
            if (size < 16) throw new IllegalStateException("Model file is too small");
            ByteBuffer lenBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            channel.read(lenBuf, 0);
            lenBuf.flip();
            long headerLen = lenBuf.getLong();
            if (headerLen <= 0 || headerLen > 1_000_000 || 8 + headerLen > size) {
                throw new IllegalStateException("Model header is malformed");
            }
            ByteBuffer headerBuf = ByteBuffer.allocate((int) headerLen);
            channel.read(headerBuf, 8);
            JSONObject header = new JSONObject(
                    new String(headerBuf.array(), StandardCharsets.UTF_8).trim());
            JSONObject tensor = null;
            for (java.util.Iterator<String> it = header.keys(); it.hasNext(); ) {
                String key = it.next();
                if ("__metadata__".equals(key)) continue;
                if (tensor != null) throw new IllegalStateException("Model has several tensors");
                tensor = header.getJSONObject(key);
            }
            if (tensor == null || !"F32".equals(tensor.optString("dtype"))) {
                throw new IllegalStateException("Model tensor is not F32");
            }
            JSONArray shape = tensor.getJSONArray("shape");
            JSONArray offsets = tensor.getJSONArray("data_offsets");
            if (shape.length() != 2 || offsets.length() != 2) {
                throw new IllegalStateException("Model tensor has an unexpected shape");
            }
            int rows = shape.getInt(0);
            int dims = shape.getInt(1);
            long start = 8 + headerLen + offsets.getLong(0);
            long end = 8 + headerLen + offsets.getLong(1);
            if (rows != vocab.size() || dims <= 0 || dims > 4096
                    || end - start != (long) rows * dims * 4L || end > size) {
                throw new IllegalStateException("Model does not match its vocabulary");
            }
            MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, start, end - start);
            mapped.order(ByteOrder.LITTLE_ENDIAN);
            return new SmartVaultEmbedder(vocab, mapped.asFloatBuffer(), rows, dims);
        }
    }

    private static Map<String, Integer> readVocab(File file) throws Exception {
        Map<String, Integer> out = new HashMap<>(40_000);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            int id = 0;
            while ((line = reader.readLine()) != null) {
                // The line number is the row. A duplicate keeps its first row, as a WordPiece
                // vocabulary loaded by the reference tokenizer does.
                if (!out.containsKey(line)) out.put(line, id);
                id++;
            }
            if (id != out.size() && id - out.size() > 16) {
                throw new IllegalStateException("Vocabulary has too many duplicate lines");
            }
            if (id == 0) throw new IllegalStateException("Vocabulary is empty");
        }
        return out;
    }

    /**
     * The unit-length meaning vector for a text, or null when nothing in it is known.
     *
     * <p>Null rather than a zero vector, so a picture with no words and a text in an alphabet the
     * vocabulary does not cover are left out of meaning search instead of matching everything
     * equally badly.
     */
    float[] embed(String text) {
        List<Integer> ids = tokenIds(text);
        if (ids.isEmpty()) return null;
        float[] sum = new float[dims];
        int used = 0;
        for (int id : ids) {
            if (id < 0 || id >= rows) continue;
            int base = id * dims;
            for (int d = 0; d < dims; d++) sum[d] += table.get(base + d);
            used++;
        }
        if (used == 0) return null;
        return normalize(sum);
    }

    static float[] normalize(float[] v) {
        double norm = 0;
        for (float x : v) norm += (double) x * x;
        if (norm <= 0) return null;
        float inv = (float) (1.0 / Math.sqrt(norm));
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = v[i] * inv;
        return out;
    }

    static float dot(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0f;
        float s = 0f;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    /** WordPiece ids for a text, without special tokens and without unknown pieces. */
    List<Integer> tokenIds(String text) {
        List<Integer> out = new ArrayList<>();
        for (String word : basicTokens(text)) {
            wordPiece(word, out);
            if (out.size() >= MAX_TOKENS) break;
        }
        return out.size() > MAX_TOKENS ? out.subList(0, MAX_TOKENS) : out;
    }

    private void wordPiece(String word, List<Integer> out) {
        if (word.length() > MAX_WORD_CHARS) return; // [UNK], which is dropped
        int start = 0;
        List<Integer> pieces = new ArrayList<>();
        while (start < word.length()) {
            int end = word.length();
            Integer found = null;
            while (start < end) {
                String piece = word.substring(start, end);
                if (start > 0) piece = "##" + piece;
                Integer id = vocab.get(piece);
                if (id != null) { found = id; break; }
                end--;
            }
            if (found == null) return; // the whole word is [UNK], which is dropped
            pieces.add(found);
            start = end;
        }
        for (int id : pieces) if (id != unkId) out.add(id);
    }

    /**
     * BERT's normaliser and pre-tokeniser: clean, lower-case, strip accents, and split on
     * whitespace and punctuation, with CJK ideographs as words of their own.
     */
    static List<String> basicTokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        StringBuilder cleaned = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0 || cp == 0xFFFD || isControl(cp)) continue;
            if (isCjk(cp)) {
                cleaned.append(' ').appendCodePoint(cp).append(' ');
            } else if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                cleaned.append(' ');
            } else {
                cleaned.appendCodePoint(cp);
            }
        }
        String lowered = cleaned.toString().toLowerCase(Locale.ROOT);
        String stripped = Normalizer.normalize(lowered, Normalizer.Form.NFD);
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < stripped.length(); ) {
            int cp = stripped.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.getType(cp) == Character.NON_SPACING_MARK) continue;
            if (cp == ' ') {
                flush(word, out);
            } else if (isPunctuation(cp)) {
                flush(word, out);
                out.add(new String(Character.toChars(cp)));
            } else {
                word.appendCodePoint(cp);
            }
        }
        flush(word, out);
        return out;
    }

    private static void flush(StringBuilder word, List<String> out) {
        if (word.length() > 0) {
            out.add(word.toString());
            word.setLength(0);
        }
    }

    private static boolean isControl(int cp) {
        if (cp == '\t' || cp == '\n' || cp == '\r') return false;
        int type = Character.getType(cp);
        return type == Character.CONTROL || type == Character.FORMAT
                || type == Character.PRIVATE_USE || type == Character.SURROGATE
                || type == Character.UNASSIGNED;
    }

    private static boolean isPunctuation(int cp) {
        if ((cp >= 33 && cp <= 47) || (cp >= 58 && cp <= 64) || (cp >= 91 && cp <= 96)
                || (cp >= 123 && cp <= 126)) {
            return true;
        }
        switch (Character.getType(cp)) {
            case Character.CONNECTOR_PUNCTUATION:
            case Character.DASH_PUNCTUATION:
            case Character.START_PUNCTUATION:
            case Character.END_PUNCTUATION:
            case Character.INITIAL_QUOTE_PUNCTUATION:
            case Character.FINAL_QUOTE_PUNCTUATION:
            case Character.OTHER_PUNCTUATION:
                return true;
            default:
                return false;
        }
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x20000 && cp <= 0x2A6DF) || (cp >= 0x2A700 && cp <= 0x2B73F)
                || (cp >= 0x2B740 && cp <= 0x2B81F) || (cp >= 0x2B820 && cp <= 0x2CEAF)
                || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0x2F800 && cp <= 0x2FA1F);
    }
}
