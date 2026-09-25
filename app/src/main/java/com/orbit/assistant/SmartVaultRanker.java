package com.orbit.assistant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Smart Vault's hybrid ranking: literal matches first, then keywords, then meaning.
 *
 * <p>Pure Java over plain documents, so the ordering rules are tested directly rather than through
 * a screen. Three signals are combined and the first always dominates:
 *
 * <ol>
 *   <li><b>Literal.</b> Exactly what the Vault's ordinary search matches - the whole query as a
 *   substring of an item's own fields - is always included and always ranked above anything found
 *   another way, with an exact or partial title match on top. Smart Vault can only ever add
 *   results to ordinary search; it cannot lose one.</li>
 *   <li><b>Keywords.</b> BM25 over every word the index holds for an item, including text
 *   recognised in pictures and read from saved pages, with a plural fold and prefix matching on
 *   the word still being typed.</li>
 *   <li><b>Meaning.</b> The best cosine similarity between the query's vector and any passage of
 *   the item, from the on-device model, when it is downloaded. A meaning-only result must clear an
 *   absolute floor and sit near the best meaning score, so a query never drags in a long tail of
 *   vaguely related items.</li>
 * </ol>
 */
final class SmartVaultRanker {

    /** Why a result is in the list, which is what its card says under the preview. */
    enum Reason { TITLE, LITERAL, RECOGNIZED, KEYWORD, MEANING }

    /**
     * The meaning floor. Calibrated against {@code potion-base-8M} on real saved-item text:
     * short unrelated pairs mostly sit below 0.25, while "fish dinner" against a salmon
     * recipe scores 0.32, "wifi password" against a router note 0.52 and "baking dessert" against
     * a cookie recipe 0.58.
     */
    static final float MEANING_FLOOR = 0.27f;
    /** A meaning-only result must also reach this share of the best meaning score. */
    static final float MEANING_RELATIVE = 0.8f;
    /** How close two items must be to be called related. */
    static final float RELATED_FLOOR = 0.33f;

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    static final class Doc {
        final String id;
        final long createdAt;
        final String titleLower;
        /** The item's own fields, lower-cased: exactly what ordinary search looks through. */
        final String haystackLower;
        /** Text Orbit derived - recognised in a picture, read from a page - lower-cased. */
        final String derivedLower;
        final String derivedOriginal;
        final Map<String, Integer> counts;
        final Set<String> titleTerms;
        final int length;
        final List<String> passages;
        /** One unit vector per passage, or null when meaning search has nothing for this item. */
        final float[][] vectors;
        final float[] centroid;
        final List<String> topics;

        Doc(String id, long createdAt, String title, String haystack, String derived,
            List<String> passages, float[][] vectors, List<String> topics) {
            this.id = id;
            this.createdAt = createdAt;
            this.titleLower = title == null ? "" : title.toLowerCase(Locale.ROOT);
            this.haystackLower = haystack == null ? "" : haystack.toLowerCase(Locale.ROOT);
            this.derivedOriginal = derived == null ? "" : derived;
            this.derivedLower = derivedOriginal.toLowerCase(Locale.ROOT);
            List<String> terms = SmartVaultText.terms(this.haystackLower + "\n" + derivedLower);
            this.counts = SmartVaultText.counts(terms);
            this.length = Math.max(1, terms.size());
            this.titleTerms = new HashSet<>(SmartVaultText.terms(this.titleLower));
            this.passages = passages == null ? Collections.emptyList() : passages;
            this.vectors = vectors;
            this.centroid = centroidOf(vectors);
            this.topics = topics == null ? Collections.emptyList() : topics;
        }

        boolean hasVectors() { return vectors != null && vectors.length > 0; }
    }

    static final class Result {
        final String id;
        final double score;
        final Reason reason;
        /** A short excerpt explaining the match, or empty when the card already shows why. */
        final String excerpt;
        final float meaning;
        final int bestPassage;

        Result(String id, double score, Reason reason, String excerpt, float meaning,
               int bestPassage) {
            this.id = id;
            this.score = score;
            this.reason = reason;
            this.excerpt = excerpt == null ? "" : excerpt;
            this.meaning = meaning;
            this.bestPassage = bestPassage;
        }
    }

    private SmartVaultRanker() {}

    /**
     * Ranks every document against a query, best first. Documents that match in no way are left
     * out. {@code queryVector} may be null, which simply turns meaning search off.
     */
    static List<Result> rank(String query, List<Doc> docs, float[] queryVector) {
        List<Result> out = new ArrayList<>();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty() || docs == null || docs.isEmpty()) return out;

        List<String> qTerms = unique(SmartVaultText.terms(q));
        // The last word is still being typed unless the query ends in a space, so it also matches
        // any indexed word it begins.
        String prefix = "";
        if (!qTerms.isEmpty() && !Character.isWhitespace(query.charAt(query.length() - 1))) {
            String last = qTerms.get(qTerms.size() - 1);
            if (last.length() >= 3) prefix = last;
        }

        double avgLen = 0;
        for (Doc d : docs) avgLen += d.length;
        avgLen = Math.max(1, avgLen / docs.size());
        Map<String, Double> idf = new HashMap<>();
        for (String t : qTerms) {
            int df = 0;
            boolean isPrefix = t.equals(prefix);
            for (Doc d : docs) if (tf(d, t, isPrefix) > 0) df++;
            idf.put(t, Math.log(1 + (docs.size() - df + 0.5) / (df + 0.5)));
        }

        float[] meaning = new float[docs.size()];
        int[] bestPassage = new int[docs.size()];
        float bestMeaning = 0f;
        for (int i = 0; i < docs.size(); i++) {
            bestPassage[i] = -1;
            Doc d = docs.get(i);
            if (queryVector == null || !d.hasVectors()) continue;
            float best = -1f;
            for (int p = 0; p < d.vectors.length; p++) {
                float s = SmartVaultEmbedder.dot(queryVector, d.vectors[p]);
                if (s > best) {
                    best = s;
                    bestPassage[i] = p;
                }
            }
            meaning[i] = Math.max(0f, best);
            bestMeaning = Math.max(bestMeaning, meaning[i]);
        }
        float meaningGate = Math.max(MEANING_FLOOR, bestMeaning * MEANING_RELATIVE);

        for (int i = 0; i < docs.size(); i++) {
            Doc d = docs.get(i);
            boolean literal = d.haystackLower.contains(q);
            boolean literalDerived = !literal && d.derivedLower.contains(q);
            double bm25 = 0;
            int matched = 0;
            int titleHits = 0;
            List<String> hitTerms = new ArrayList<>();
            for (String t : qTerms) {
                boolean isPrefix = t.equals(prefix);
                double f = tf(d, t, isPrefix);
                if (f <= 0) continue;
                matched++;
                hitTerms.add(t);
                bm25 += idf.get(t) * f * (K1 + 1) / (f + K1 * (1 - B + B * d.length / avgLen));
                if (d.titleTerms.contains(t) || (isPrefix && startsAny(d.titleTerms, t))) {
                    titleHits++;
                }
            }
            double coverage = qTerms.isEmpty() ? 0 : (double) matched / qTerms.size();
            boolean keyword = coverage >= 0.5 && bm25 > 0;
            boolean meaningful = meaning[i] >= meaningGate;
            if (!literal && !literalDerived && !keyword && !meaningful) continue;

            double score = 0;
            Reason reason;
            if (literal) {
                score += 1000;
                if (d.titleLower.equals(q)) score += 400;
                else if (d.titleLower.startsWith(q)) score += 300;
                else if (d.titleLower.contains(q)) score += 200;
                reason = d.titleLower.contains(q) ? Reason.TITLE : Reason.LITERAL;
            } else if (literalDerived) {
                score += 500;
                reason = Reason.RECOGNIZED;
            } else if (keyword) {
                reason = keywordInDerivedOnly(d, hitTerms) ? Reason.RECOGNIZED : Reason.KEYWORD;
            } else {
                reason = Reason.MEANING;
            }
            score += 40 * bm25 * coverage + 25 * titleHits;
            if (meaning[i] > MEANING_FLOOR) score += 300 * (meaning[i] - MEANING_FLOOR);

            String excerpt = "";
            if (reason == Reason.RECOGNIZED) {
                List<String> needles = new ArrayList<>();
                needles.add(q);
                needles.addAll(hitTerms);
                excerpt = SmartVaultText.excerpt(d.derivedOriginal, needles, 120);
            } else if (reason == Reason.MEANING && bestPassage[i] >= 0
                    && bestPassage[i] < d.passages.size()) {
                excerpt = clip(d.passages.get(bestPassage[i]), 120);
            }
            out.add(new Result(d.id, score, reason, excerpt, meaning[i], bestPassage[i]));
        }

        Map<String, Long> created = new HashMap<>();
        for (Doc d : docs) created.put(d.id, d.createdAt);
        out.sort((a, b) -> {
            int byScore = Double.compare(b.score, a.score);
            if (byScore != 0) return byScore;
            int byTime = Long.compare(created.get(b.id), created.get(a.id));
            return byTime != 0 ? byTime : a.id.compareTo(b.id);
        });
        return out;
    }

    /**
     * The items most like one item, best first, excluding itself.
     *
     * <p>Meaning when both items have vectors; otherwise keyword overlap weighted by how rare each
     * shared word is. A shared topic adds a little either way. Nothing here asks a provider.
     */
    static List<String> related(String id, List<Doc> docs, int limit) {
        List<String> out = new ArrayList<>();
        Doc self = null;
        for (Doc d : docs) if (d.id.equals(id)) self = d;
        if (self == null) return out;
        Map<String, Double> idf = idfTable(docs);
        List<double[]> scored = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (Doc d : docs) {
            if (d.id.equals(id)) continue;
            double score;
            double floor;
            if (self.centroid != null && d.centroid != null) {
                score = SmartVaultEmbedder.dot(self.centroid, d.centroid);
                floor = RELATED_FLOOR;
            } else {
                score = keywordSimilarity(self, d, idf);
                floor = 0.18;
            }
            for (String topic : self.topics) if (d.topics.contains(topic)) score += 0.06;
            if (score < floor) continue;
            scored.add(new double[]{score, ids.size()});
            ids.add(d.id);
        }
        scored.sort((a, b) -> Double.compare(b[0], a[0]));
        for (double[] s : scored) {
            out.add(ids.get((int) s[1]));
            if (out.size() >= limit) break;
        }
        return out;
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static double tf(Doc d, String term, boolean prefix) {
        Integer exact = d.counts.get(term);
        double f = exact == null ? 0 : exact;
        if (prefix) {
            for (Map.Entry<String, Integer> e : d.counts.entrySet()) {
                String key = e.getKey();
                if (key.length() > term.length() && key.startsWith(term)) f += 0.8 * e.getValue();
            }
        }
        return f;
    }

    private static boolean startsAny(Set<String> terms, String prefix) {
        for (String t : terms) if (t.startsWith(prefix)) return true;
        return false;
    }

    private static boolean keywordInDerivedOnly(Doc d, List<String> hits) {
        if (d.derivedLower.isEmpty()) return false;
        Set<String> own = new HashSet<>(SmartVaultText.terms(d.haystackLower));
        for (String t : hits) {
            boolean inOwn = own.contains(t);
            if (!inOwn) for (String o : own) if (o.startsWith(t)) { inOwn = true; break; }
            if (inOwn) return false;
        }
        return true;
    }

    private static Map<String, Double> idfTable(List<Doc> docs) {
        Map<String, Integer> df = new HashMap<>();
        for (Doc d : docs) for (String t : d.counts.keySet()) df.merge(t, 1, Integer::sum);
        Map<String, Double> out = new HashMap<>();
        for (Map.Entry<String, Integer> e : df.entrySet()) {
            out.put(e.getKey(), Math.log(1.0 + docs.size() / (double) e.getValue()));
        }
        return out;
    }

    /** Cosine similarity of two items' tf-idf word weights. */
    static double keywordSimilarity(Doc a, Doc b, Map<String, Double> idf) {
        double dot = 0, na = 0, nb = 0;
        for (Map.Entry<String, Integer> e : a.counts.entrySet()) {
            double w = e.getValue() * idf.getOrDefault(e.getKey(), 0.0);
            na += w * w;
            Integer other = b.counts.get(e.getKey());
            if (other != null) dot += w * other * idf.getOrDefault(e.getKey(), 0.0);
        }
        for (Map.Entry<String, Integer> e : b.counts.entrySet()) {
            double w = e.getValue() * idf.getOrDefault(e.getKey(), 0.0);
            nb += w * w;
        }
        if (na <= 0 || nb <= 0) return 0;
        return dot / Math.sqrt(na * nb);
    }

    static float[] centroidOf(float[][] vectors) {
        if (vectors == null || vectors.length == 0) return null;
        float[] sum = new float[vectors[0].length];
        for (float[] v : vectors) {
            if (v == null || v.length != sum.length) continue;
            for (int i = 0; i < sum.length; i++) sum[i] += v[i];
        }
        return SmartVaultEmbedder.normalize(sum);
    }

    private static List<String> unique(List<String> terms) {
        List<String> out = new ArrayList<>();
        for (String t : terms) if (!out.contains(t)) out.add(t);
        return out;
    }

    private static String clip(String text, int max) {
        if (text == null) return "";
        String flat = text.replaceAll("\\s+", " ").trim();
        if (flat.length() <= max) return flat;
        String cut = flat.substring(0, max);
        int space = cut.lastIndexOf(' ');
        if (space > max / 2) cut = cut.substring(0, space);
        return cut.trim() + "…";
    }
}
