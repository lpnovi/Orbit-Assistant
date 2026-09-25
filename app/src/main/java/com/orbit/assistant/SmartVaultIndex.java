package com.orbit.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Smart Vault's in-memory search index, built from the Vault and its derived data.
 *
 * <p>Built off the main thread whenever the Vault or the derived database changes, then held as
 * one immutable snapshot, so ranking while the user types is pure computation over memory: a few
 * milliseconds for a full Vault. A screen that asks while no current snapshot exists gets ordinary
 * search immediately and a refresh when the snapshot is ready, so search is never blocked on it.
 */
final class SmartVaultIndex {

    static final class Snapshot {
        final long generation;
        final long dbChanges;
        final boolean meaning;
        final List<SmartVaultRanker.Doc> docs;
        final Map<String, SmartVaultRanker.Doc> byId;
        final Map<String, OrbitVaultItem> items;
        final Map<String, String> recognized;
        final Map<String, String> pages;

        Snapshot(long generation, long dbChanges, boolean meaning, List<SmartVaultRanker.Doc> docs,
                 Map<String, OrbitVaultItem> items, Map<String, String> recognized,
                 Map<String, String> pages) {
            this.generation = generation;
            this.dbChanges = dbChanges;
            this.meaning = meaning;
            this.docs = Collections.unmodifiableList(docs);
            Map<String, SmartVaultRanker.Doc> map = new HashMap<>();
            for (SmartVaultRanker.Doc d : docs) map.put(d.id, d);
            this.byId = map;
            this.items = items;
            this.recognized = recognized;
            this.pages = pages;
        }

        boolean hasVectors() {
            for (SmartVaultRanker.Doc d : docs) if (d.hasVectors()) return true;
            return false;
        }
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "orbit-smart-vault-index");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static volatile Snapshot snapshot;
    private static volatile boolean building;

    private SmartVaultIndex() {}

    static void invalidate() {
        snapshot = null;
    }

    /** The snapshot, only if it still describes the Vault and the derived data exactly. */
    static Snapshot current(Context c) {
        Snapshot s = snapshot;
        if (s == null) return null;
        if (s.generation != OrbitVaultStore.generation()) return null;
        if (s.dbChanges != SmartVaultDb.changes()) return null;
        if (s.meaning != meaningAvailable(c)) return null;
        return s;
    }

    private static boolean meaningAvailable(Context c) {
        return Prefs.smartVaultMeaning(c) && SmartVaultModel.isReady(c);
    }

    /** Builds a current snapshot on the index thread, then runs {@code ready} on the main thread. */
    static void warm(Context c, Runnable ready) {
        Context app = c.getApplicationContext();
        if (current(app) != null) {
            if (ready != null) ready.run();
            return;
        }
        if (building) return;
        building = true;
        EXEC.execute(() -> {
            try {
                build(app);
            } catch (Throwable ignored) {
                // A failed build leaves ordinary search in charge; nothing is lost.
            } finally {
                building = false;
            }
            if (ready != null) new Handler(Looper.getMainLooper()).post(ready);
        });
    }

    /** Builds and stores a snapshot on the calling thread. Never call it from the main thread. */
    static synchronized Snapshot build(Context c) {
        Snapshot existing = current(c);
        if (existing != null) return existing;
        long generation = OrbitVaultStore.generation();
        long dbChanges = SmartVaultDb.changes();
        boolean meaning = meaningAvailable(c);
        List<OrbitVaultItem> items = OrbitVaultStore.list(c);
        // Loaded here, on the index thread, so the first search by meaning never reads the
        // vocabulary on the main thread.
        if (meaning) SmartVaultModel.embedder(c);
        Map<String, Map<String, SmartVaultDb.Derived>> derived = new HashMap<>();
        Map<String, SmartVaultDb.Vectors> vectors = new HashMap<>();
        try {
            SmartVaultDb db = SmartVaultDb.get(c);
            derived = db.allDerived();
            if (meaning) vectors = db.allVectors();
        } catch (Exception ignored) {
            // No derived data is still an index: the item's own words and its topics.
        }
        List<SmartVaultRanker.Doc> docs = new ArrayList<>();
        Map<String, OrbitVaultItem> byId = new HashMap<>();
        Map<String, String> recognized = new HashMap<>();
        Map<String, String> pages = new HashMap<>();
        for (OrbitVaultItem item : items) {
            byId.put(item.id, item);
            String ocr = currentDerived(derived, item, SmartVaultDb.KIND_OCR);
            String page = currentDerived(derived, item, SmartVaultDb.KIND_PAGE);
            if (!ocr.isEmpty()) recognized.put(item.id, ocr);
            if (!page.isEmpty()) pages.put(item.id, page);
            List<String> passages = SmartVault.passagesFor(item, ocr, page);
            float[][] v = null;
            SmartVaultDb.Vectors stored = vectors.get(item.id);
            if (stored != null && stored.model.equals(SmartVaultModel.MODEL_ID)
                    && stored.basis.equals(SmartVault.vectorBasis(passages))
                    && stored.vectors.length == passages.size()) {
                v = stored.vectors;
            }
            String derivedText = (ocr + "\n" + page).trim();
            docs.add(new SmartVaultRanker.Doc(item.id, item.createdAt, item.displayTitle(),
                    item.searchHaystack(), derivedText, passages, v, item.allTopics()));
        }
        Snapshot built = new Snapshot(generation, dbChanges, meaning, docs, byId, recognized, pages);
        snapshot = built;
        return built;
    }

    /** Derived text of one kind, only if it was computed from what the item holds now. */
    static String currentDerived(Map<String, Map<String, SmartVaultDb.Derived>> all,
                                 OrbitVaultItem item, String kind) {
        Map<String, SmartVaultDb.Derived> rows = all.get(item.id);
        if (rows == null) return "";
        SmartVaultDb.Derived row = rows.get(kind);
        if (row == null) return "";
        return row.basis.equals(SmartVault.derivedBasis(item, kind)) ? row.text : "";
    }

    /** Ranked results for a query against one snapshot. Runs in milliseconds. */
    static List<SmartVaultRanker.Result> search(Context c, Snapshot s, String query) {
        if (s == null) return Collections.emptyList();
        float[] qv = null;
        if (s.meaning && s.hasVectors()) {
            SmartVaultEmbedder embedder = SmartVaultModel.embedder(c);
            if (embedder != null) qv = embedder.embed(query);
        }
        return SmartVaultRanker.rank(query, s.docs, qv);
    }

    /** Items like one item, from the local index alone. */
    static List<OrbitVaultItem> related(Snapshot s, String id, int limit) {
        List<OrbitVaultItem> out = new ArrayList<>();
        if (s == null) return out;
        for (String other : SmartVaultRanker.related(id, s.docs, limit)) {
            OrbitVaultItem item = s.items.get(other);
            if (item != null) out.add(item);
        }
        return out;
    }
}
