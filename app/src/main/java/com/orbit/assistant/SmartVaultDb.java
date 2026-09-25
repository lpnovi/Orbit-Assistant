package com.orbit.assistant;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Smart Vault's derived data: everything it worked out about saved items, and nothing the user
 * saved.
 *
 * <p>This database is disposable by design. The Vault's own document ({@link OrbitVaultStore}) is
 * the only authority; every row here - text recognised in a picture, text read from a saved page,
 * meaning vectors, and the state of background work - can be deleted at any moment and rebuilt
 * from that document. That is why it is not in backups, why "Delete Smart Vault data" can simply
 * drop it, and why a schema change here never needs a migration: the upgrade path is to start
 * again.
 *
 * <p>Every row carries the {@code basis} it was computed from (a picture's path, a page's address,
 * a text fingerprint), so a row describing an older version of an item is recognisably stale
 * rather than silently wrong.
 */
final class SmartVaultDb extends SQLiteOpenHelper {

    static final String NAME = "smart_vault_index.db";
    private static final int VERSION = 1;

    /** Text recognised in a saved picture. Basis: the picture's path. */
    static final String KIND_OCR = "ocr";
    /** Readable text from the web page behind a saved link. Basis: the address. */
    static final String KIND_PAGE = "page";

    /** Background job kinds and their states. */
    static final String JOB_ENRICH = "enrich";
    static final String STATE_QUEUED = "queued";
    static final String STATE_FAILED = "failed";
    static final String STATE_DONE = "done";

    private static SmartVaultDb instance;
    /** Increases on every write, so the in-memory index knows when to rebuild. */
    private static volatile long changes = 1L;

    static synchronized SmartVaultDb get(Context c) {
        if (instance == null) instance = new SmartVaultDb(c.getApplicationContext());
        return instance;
    }

    /** Forgets the open instance. Tests use it after deleting the file. */
    static synchronized void resetForTest() {
        if (instance != null) instance.close();
        instance = null;
        changes++;
    }

    static long changes() { return changes; }

    private SmartVaultDb(Context c) {
        super(c, NAME, null, VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE derived (item_id TEXT NOT NULL, kind TEXT NOT NULL, "
                + "basis TEXT NOT NULL, text TEXT NOT NULL, updated_at INTEGER NOT NULL, "
                + "PRIMARY KEY (item_id, kind))");
        db.execSQL("CREATE TABLE vectors (item_id TEXT NOT NULL PRIMARY KEY, basis TEXT NOT NULL, "
                + "model TEXT NOT NULL, dims INTEGER NOT NULL, data BLOB NOT NULL)");
        db.execSQL("CREATE TABLE jobs (item_id TEXT NOT NULL, kind TEXT NOT NULL, "
                + "state TEXT NOT NULL, basis TEXT NOT NULL, attempts INTEGER NOT NULL, "
                + "message TEXT NOT NULL, updated_at INTEGER NOT NULL, "
                + "PRIMARY KEY (item_id, kind))");
        db.execSQL("CREATE TABLE attempts (item_id TEXT NOT NULL, kind TEXT NOT NULL, "
                + "basis TEXT NOT NULL, count INTEGER NOT NULL, PRIMARY KEY (item_id, kind))");
    }

    /** Derived data is rebuildable, so an upgrade starts again rather than migrating. */
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        dropAll(db);
        onCreate(db);
    }

    @Override public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        dropAll(db);
        onCreate(db);
    }

    private static void dropAll(SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS derived");
        db.execSQL("DROP TABLE IF EXISTS vectors");
        db.execSQL("DROP TABLE IF EXISTS jobs");
        db.execSQL("DROP TABLE IF EXISTS attempts");
    }

    // ---- derived text ---------------------------------------------------------------------------

    static final class Derived {
        final String basis;
        final String text;

        Derived(String basis, String text) {
            this.basis = basis;
            this.text = text;
        }
    }

    /** Every derived text, by item then kind. */
    synchronized Map<String, Map<String, Derived>> allDerived() {
        Map<String, Map<String, Derived>> out = new HashMap<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT item_id, kind, basis, text FROM derived", null)) {
            while (cursor.moveToNext()) {
                out.computeIfAbsent(cursor.getString(0), k -> new HashMap<>())
                        .put(cursor.getString(1), new Derived(cursor.getString(2),
                                cursor.getString(3)));
            }
        }
        return out;
    }

    synchronized Derived derived(String itemId, String kind) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT basis, text FROM derived WHERE item_id = ? AND kind = ?",
                new String[]{itemId, kind})) {
            return cursor.moveToFirst() ? new Derived(cursor.getString(0), cursor.getString(1))
                    : null;
        }
    }

    synchronized void putDerived(String itemId, String kind, String basis, String text) {
        ContentValues values = new ContentValues();
        values.put("item_id", itemId);
        values.put("kind", kind);
        values.put("basis", basis == null ? "" : basis);
        values.put("text", text == null ? "" : text);
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("derived", null, values,
                SQLiteDatabase.CONFLICT_REPLACE);
        changes++;
    }

    // ---- vectors ------------------------------------------------------------------------------

    static final class Vectors {
        final String basis;
        final String model;
        final float[][] vectors;

        Vectors(String basis, String model, float[][] vectors) {
            this.basis = basis;
            this.model = model;
            this.vectors = vectors;
        }
    }

    synchronized Map<String, Vectors> allVectors() {
        Map<String, Vectors> out = new HashMap<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT item_id, basis, model, dims, data FROM vectors", null)) {
            while (cursor.moveToNext()) {
                float[][] decoded = decode(cursor.getBlob(4), cursor.getInt(3));
                if (decoded == null) continue;
                out.put(cursor.getString(0), new Vectors(cursor.getString(1),
                        cursor.getString(2), decoded));
            }
        }
        return out;
    }

    synchronized void putVectors(String itemId, String basis, String model, float[][] vectors) {
        if (vectors == null || vectors.length == 0) {
            getWritableDatabase().delete("vectors", "item_id = ?", new String[]{itemId});
            changes++;
            return;
        }
        int dims = vectors[0].length;
        ByteBuffer buffer = ByteBuffer.allocate(vectors.length * dims * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float[] v : vectors) for (int i = 0; i < dims; i++) buffer.putFloat(v[i]);
        ContentValues values = new ContentValues();
        values.put("item_id", itemId);
        values.put("basis", basis);
        values.put("model", model);
        values.put("dims", dims);
        values.put("data", buffer.array());
        getWritableDatabase().insertWithOnConflict("vectors", null, values,
                SQLiteDatabase.CONFLICT_REPLACE);
        changes++;
    }

    static float[][] decode(byte[] data, int dims) {
        if (data == null || dims <= 0 || data.length % (dims * 4) != 0) return null;
        int rows = data.length / (dims * 4);
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        float[][] out = new float[rows][dims];
        for (int r = 0; r < rows; r++) for (int i = 0; i < dims; i++) out[r][i] = buffer.getFloat();
        return out;
    }

    synchronized int vectorCount() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM vectors", null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    /** How many items have derived text of one kind that actually contains words. */
    synchronized int derivedCount(String kind) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM derived WHERE kind = ? AND length(text) > 0",
                new String[]{kind})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    synchronized void clearVectors() {
        getWritableDatabase().delete("vectors", null, null);
        changes++;
    }

    // ---- failed attempts at local work --------------------------------------------------------

    /** How often local work on this basis has failed, so a bad picture is not retried forever. */
    synchronized int attempts(String itemId, String kind, String basis) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT basis, count FROM attempts WHERE item_id = ? AND kind = ?",
                new String[]{itemId, kind})) {
            if (!cursor.moveToFirst()) return 0;
            return basis.equals(cursor.getString(0)) ? cursor.getInt(1) : 0;
        }
    }

    synchronized void recordAttempt(String itemId, String kind, String basis) {
        int next = attempts(itemId, kind, basis) + 1;
        ContentValues values = new ContentValues();
        values.put("item_id", itemId);
        values.put("kind", kind);
        values.put("basis", basis);
        values.put("count", next);
        getWritableDatabase().insertWithOnConflict("attempts", null, values,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    // ---- AI suggestion jobs -------------------------------------------------------------------

    static final class Job {
        final String itemId;
        final String state;
        final String basis;
        final int attempts;
        final String message;

        Job(String itemId, String state, String basis, int attempts, String message) {
            this.itemId = itemId;
            this.state = state;
            this.basis = basis;
            this.attempts = attempts;
            this.message = message;
        }
    }

    synchronized void putJob(String itemId, String state, String basis, int attempts,
                             String message) {
        ContentValues values = new ContentValues();
        values.put("item_id", itemId);
        values.put("kind", JOB_ENRICH);
        values.put("state", state);
        values.put("basis", basis == null ? "" : basis);
        values.put("attempts", attempts);
        values.put("message", message == null ? "" : message);
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("jobs", null, values,
                SQLiteDatabase.CONFLICT_REPLACE);
        changes++;
    }

    synchronized Job job(String itemId) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT state, basis, attempts, message FROM jobs WHERE item_id = ? AND kind = ?",
                new String[]{itemId, JOB_ENRICH})) {
            return cursor.moveToFirst() ? new Job(itemId, cursor.getString(0),
                    cursor.getString(1), cursor.getInt(2), cursor.getString(3)) : null;
        }
    }

    /** Queued suggestion jobs, oldest request first. */
    synchronized List<Job> queuedJobs(int limit) {
        List<Job> out = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT item_id, state, basis, attempts, message FROM jobs WHERE kind = ? "
                        + "AND state = ? ORDER BY updated_at ASC LIMIT " + Math.max(1, limit),
                new String[]{JOB_ENRICH, STATE_QUEUED})) {
            while (cursor.moveToNext()) {
                out.add(new Job(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                        cursor.getInt(3), cursor.getString(4)));
            }
        }
        return out;
    }

    synchronized int queuedCount() {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM jobs WHERE kind = ? AND state = ?",
                new String[]{JOB_ENRICH, STATE_QUEUED})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    /** Stops every queued suggestion request. Nothing already written is touched. */
    synchronized int cancelQueued() {
        int n = getWritableDatabase().delete("jobs", "kind = ? AND state = ?",
                new String[]{JOB_ENRICH, STATE_QUEUED});
        changes++;
        return n;
    }

    // ---- housekeeping -------------------------------------------------------------------------

    /** Removes everything derived about one item. */
    synchronized void forget(String itemId) {
        SQLiteDatabase db = getWritableDatabase();
        String[] args = {itemId};
        db.delete("derived", "item_id = ?", args);
        db.delete("vectors", "item_id = ?", args);
        db.delete("jobs", "item_id = ?", args);
        db.delete("attempts", "item_id = ?", args);
        changes++;
    }

    /** Removes rows for items the Vault no longer has. */
    synchronized int forgetAllExcept(Set<String> keep) {
        int removed = 0;
        for (String table : new String[]{"derived", "vectors", "jobs", "attempts"}) {
            List<String> ids = new ArrayList<>();
            try (Cursor cursor = getReadableDatabase().rawQuery(
                    "SELECT DISTINCT item_id FROM " + table, null)) {
                while (cursor.moveToNext()) {
                    String id = cursor.getString(0);
                    if (!keep.contains(id)) ids.add(id);
                }
            }
            for (String id : ids) {
                removed += getWritableDatabase().delete(table, "item_id = ?", new String[]{id});
            }
        }
        if (removed > 0) changes++;
        return removed;
    }

    /** Empties every table. The database file stays, ready to be rebuilt. */
    synchronized void clearAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("derived", null, null);
        db.delete("vectors", null, null);
        db.delete("jobs", null, null);
        db.delete("attempts", null, null);
        changes++;
    }
}
