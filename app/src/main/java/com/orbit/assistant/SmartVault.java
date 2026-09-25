package com.orbit.assistant;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Smart Vault: search by meaning, recognised text, suggestions and Ask Vault, built on top of the
 * ordinary Vault rather than instead of it.
 *
 * <p>This class is the one place the rest of Orbit talks to. It is told when items are saved,
 * changed and removed, and it decides - from the user's own switches - what background work that
 * implies. It never blocks a save and never changes what a save stores.
 *
 * <p><b>Privacy boundaries, each its own switch and each off until the user turns it on:</b>
 * <ul>
 *   <li>Local indexing and search (Smart Vault itself): nothing leaves the device.</li>
 *   <li>Text recognition in pictures: on the device through Google ML Kit.</li>
 *   <li>Search by meaning: a one-time download of the model; the Vault never leaves the device.</li>
 *   <li>Reading saved links: contacts the website behind a link the user saved.</li>
 *   <li>AI suggestions for new items: sends a new item to the active AI provider.</li>
 * </ul>
 * Suggestions for items saved before the switch was on happen only when the user asks, item by
 * item or as one counted batch they confirm.
 */
final class SmartVault {

    static final String INDEX_WORK = "orbit-smart-vault-index";
    static final String ENRICH_WORK = "orbit-smart-vault-suggest";
    /** How many existing items one "Suggest for existing items" confirmation may queue. */
    static final int MAX_BATCH = 100;

    private static final ExecutorService HOUSEKEEPING = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "orbit-smart-vault-housekeeping");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private SmartVault() {}

    // ---- what the store tells us ------------------------------------------------------------------

    static void onSaved(Context c, OrbitVaultItem item) {
        if (c == null || item == null || !Prefs.smartVaultEnabled(c)) return;
        scheduleIndexing(c);
        if (Prefs.smartVaultAiForNewItems(c)) queueSuggestions(c, java.util.Collections.singletonList(item.id));
    }

    static void onChanged(Context c, OrbitVaultItem item) {
        SmartVaultIndex.invalidate();
        if (c == null || item == null || !Prefs.smartVaultEnabled(c)) return;
        scheduleIndexing(c);
    }

    static void onDeleted(Context c, String id) {
        SmartVaultIndex.invalidate();
        if (c == null || id == null || !databaseExists(c)) return;
        Context app = c.getApplicationContext();
        HOUSEKEEPING.execute(() -> {
            try { SmartVaultDb.get(app).forget(id); } catch (Exception ignored) { }
        });
    }

    static void onVaultCleared(Context c) {
        SmartVaultIndex.invalidate();
        if (c == null || !databaseExists(c)) return;
        try { SmartVaultDb.get(c).clearAll(); } catch (Exception ignored) { }
    }

    /** After a backup restore: derived data describes the old Vault, so it is swept and rebuilt. */
    static void onRestored(Context c) {
        SmartVaultIndex.invalidate();
        if (c == null) return;
        if (Prefs.smartVaultEnabled(c)) scheduleIndexing(c);
    }

    static boolean databaseExists(Context c) {
        try {
            return c.getDatabasePath(SmartVaultDb.NAME).exists();
        } catch (Exception e) {
            return false;
        }
    }

    // ---- turning it on and off ------------------------------------------------------------------

    /** Turns Smart Vault on with the choices the user made on the setup screen. */
    static void enable(Context c, boolean ocr, boolean meaning, boolean readLinks,
                       boolean aiForNew) {
        Prefs.get(c).edit()
                .putBoolean(Prefs.SMART_VAULT_ENABLED, true)
                .putBoolean(Prefs.SMART_VAULT_OCR, ocr)
                .putBoolean(Prefs.SMART_VAULT_MEANING, meaning)
                .putBoolean(Prefs.SMART_VAULT_READ_LINKS, readLinks)
                .putBoolean(Prefs.SMART_VAULT_AI_NEW, aiForNew)
                .putBoolean(Prefs.SMART_VAULT_INTRO_DISMISSED, true)
                .commit();
        SmartVaultIndex.invalidate();
        if (ocr) SmartVaultOcr.prepare(c);
        if (meaning) SmartVaultModel.requestDownload(c);
        scheduleIndexing(c);
    }

    /**
     * Turns Smart Vault off. Background work stops; the index is kept so turning it back on is
     * instant, and "Delete Smart Vault data" is the separate control that removes it.
     */
    static void disable(Context c) {
        Prefs.get(c).edit().putBoolean(Prefs.SMART_VAULT_ENABLED, false).commit();
        SmartVaultIndex.invalidate();
        cancelWork(c);
        if (databaseExists(c)) {
            try { SmartVaultDb.get(c).cancelQueued(); } catch (Exception ignored) { }
        }
    }

    static void setOption(Context c, String key, boolean value) {
        Prefs.get(c).edit().putBoolean(key, value).commit();
        SmartVaultIndex.invalidate();
        if (!Prefs.smartVaultEnabled(c)) return;
        if (Prefs.SMART_VAULT_MEANING.equals(key) && value) SmartVaultModel.requestDownload(c);
        if (Prefs.SMART_VAULT_OCR.equals(key) && value) SmartVaultOcr.prepare(c);
        scheduleIndexing(c);
    }

    /**
     * Removes everything Smart Vault derived or suggested, and nothing the user saved or wrote.
     * Items, notes, the user's own titles and kept topics stay. The model is removed separately.
     */
    static int deleteData(Context c) {
        cancelWork(c);
        if (databaseExists(c)) {
            try { SmartVaultDb.get(c).clearAll(); } catch (Exception ignored) { }
        }
        SmartVaultIndex.invalidate();
        int cleared = OrbitVaultStore.clearAllSuggestions(c);
        if (Prefs.smartVaultEnabled(c)) scheduleIndexing(c);
        return cleared;
    }

    private static void cancelWork(Context c) {
        try {
            WorkManager wm = WorkManager.getInstance(c.getApplicationContext());
            wm.cancelUniqueWork(INDEX_WORK);
            wm.cancelUniqueWork(ENRICH_WORK);
        } catch (Exception ignored) {
        }
    }

    // ---- background work --------------------------------------------------------------------------

    /** Local indexing: recognition, page reading and meaning vectors. Waits out a low battery. */
    static void scheduleIndexing(Context c) {
        if (c == null || !Prefs.smartVaultEnabled(c)) return;
        SmartVaultIndex.invalidate();
        try {
            Constraints constraints = new Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build();
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(SmartVaultWorker.class)
                    .setConstraints(constraints)
                    .addTag(INDEX_WORK)
                    .build();
            WorkManager.getInstance(c.getApplicationContext())
                    .enqueueUniqueWork(INDEX_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request);
        } catch (Exception ignored) {
            // WorkManager unavailable: search still works on the Vault's own words.
        }
    }

    /**
     * Queues AI suggestions for exactly these items. Nothing is sent until the suggestion job runs,
     * and it only runs with a connection and a battery that is not low.
     */
    static int queueSuggestions(Context c, List<String> ids) {
        if (c == null || ids == null || ids.isEmpty() || !Prefs.smartVaultEnabled(c)) return 0;
        int queued = 0;
        try {
            SmartVaultDb db = SmartVaultDb.get(c);
            for (String id : ids) {
                OrbitVaultItem item = OrbitVaultStore.get(c, id);
                if (item == null) continue;
                db.putJob(id, SmartVaultDb.STATE_QUEUED, item.contentFingerprint(), 0, "");
                queued++;
            }
        } catch (Exception e) {
            return 0;
        }
        if (queued > 0) scheduleSuggestions(c);
        return queued;
    }

    static void scheduleSuggestions(Context c) {
        try {
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build();
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(SmartVaultWorker.class)
                    .setConstraints(constraints)
                    .setInputData(new androidx.work.Data.Builder()
                            .putBoolean(SmartVaultWorker.KEY_SUGGEST, true).build())
                    .addTag(ENRICH_WORK)
                    .build();
            WorkManager.getInstance(c.getApplicationContext())
                    .enqueueUniqueWork(ENRICH_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request);
        } catch (Exception ignored) {
        }
    }

    /** Existing items with no current suggestions, newest first, for the batch confirmation. */
    static List<String> itemsWithoutSuggestions(Context c) {
        List<String> out = new ArrayList<>();
        for (OrbitVaultItem item : OrbitVaultStore.list(c)) {
            if (item.suggestions != null && item.suggestions.hasContent()
                    && item.suggestionsAreCurrent()) {
                continue;
            }
            out.add(item.id);
        }
        return out;
    }

    /** The state of one item's suggestion request, in words, or empty when there is none. */
    static String suggestionStatus(Context c, String id) {
        if (!databaseExists(c)) return "";
        try {
            SmartVaultDb.Job job = SmartVaultDb.get(c).job(id);
            if (job == null) return "";
            if (SmartVaultDb.STATE_QUEUED.equals(job.state)) {
                return "Waiting to suggest details. Needs a connection.";
            }
            if (SmartVaultDb.STATE_FAILED.equals(job.state)) return job.message;
            if (SmartVaultDb.STATE_DONE.equals(job.state) && !job.message.isEmpty()) {
                return job.message;
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    // ---- the AI provider, as the Vault screens need to describe it ----------------------------

    /**
     * Why suggestions cannot be requested right now, or empty when they can. The Vault's own
     * screens ask this rather than reaching provider machinery themselves.
     */
    static String suggestionBlocker(Context c) {
        AiProvider provider = AiProviders.active(c);
        if (provider.supportsCompletion(c)) return "";
        return provider.status(c) == AiProvider.Status.NEEDS_SETUP
                ? "Sign in to " + provider.displayName() + " first"
                : AiProvider.COMPLETION_UNSUPPORTED;
    }

    /** The active provider's name, for sentences that say where something would go. */
    static String providerName(Context c) {
        return AiProviders.active(c).displayName();
    }

    /** Whether the active provider can answer a chat right now. */
    static boolean providerReady(Context c) {
        return AiProviders.active(c).status(c) == AiProvider.Status.READY;
    }

    // ---- what the index is built from --------------------------------------------------------------

    /**
     * The passages one item is embedded as, in a fixed order: first its "card" - title, note,
     * summary and topics - then its content and derived text in windows. Deterministic, so a
     * stored vector is matched back to its words without storing them twice.
     */
    static List<String> passagesFor(OrbitVaultItem item, String recognized, String page) {
        List<String> out = new ArrayList<>();
        StringBuilder card = new StringBuilder(item.displayTitle());
        if (item.hasNote()) card.append(". ").append(item.note);
        String summary = item.suggestedSummary();
        if (!summary.isEmpty()) card.append(". ").append(summary);
        List<String> topics = item.allTopics();
        if (!topics.isEmpty()) card.append(". ").append(String.join(", ", topics));
        if (item.isDocumentPage() && !item.documentName.isEmpty()) {
            card.append(". ").append(item.documentName);
        }
        out.add(card.toString());
        StringBuilder content = new StringBuilder();
        if (!item.isLink()) content.append(item.body);
        append(content, item.capturedText);
        append(content, recognized);
        append(content, page);
        for (String p : SmartVaultText.passages(content.toString())) {
            if (out.size() > SmartVaultText.MAX_PASSAGES) break;
            out.add(p);
        }
        return out;
    }

    private static void append(StringBuilder out, String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (out.length() > 0) out.append('\n');
        out.append(text.trim());
    }

    /** What a stored vector set must have been computed from to still be current. */
    static String vectorBasis(List<String> passages) {
        return hash(SmartVaultModel.MODEL_ID + "\u0001" + String.join("\u0002", passages));
    }

    /** What a derived text of one kind must have been computed from to still be current. */
    static String derivedBasis(OrbitVaultItem item, String kind) {
        if (SmartVaultDb.KIND_OCR.equals(kind)) return item.mediaPath;
        if (SmartVaultDb.KIND_PAGE.equals(kind)) return item.isLink() ? item.body : "";
        return "";
    }

    /** Whether a picture is worth reading text from. */
    static boolean wantsRecognition(OrbitVaultItem item) {
        if (item == null || item.mediaPath.isEmpty()) return false;
        if (item.isImage()) return true;
        // A saved page whose text layer was empty is a scan: recognition is its only text.
        return item.isDocumentPage() && item.body.trim().length() < 20;
    }

    static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 16; i++) out.append(String.format(Locale.US, "%02x", bytes[i]));
            return out.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
