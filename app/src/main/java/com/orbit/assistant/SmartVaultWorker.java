package com.orbit.assistant;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Smart Vault's background work: local indexing, and AI suggestions when they were asked for.
 *
 * <p><b>Safe to repeat.</b> Every step compares what it would produce with what is already stored
 * for the same basis and skips work that is current, so a run after process death, a retry, or
 * two runs back to back simply find nothing left to do.
 *
 * <p><b>Unable to overwrite the user.</b> Results are written only after re-checking, under the
 * Vault's own lock, that the item still exists and still holds what the work started from. A
 * deleted item stays deleted; an item edited meanwhile keeps the edit and is simply looked at
 * again on the next run. AI suggestions go through
 * {@link OrbitVaultStore#applySuggestions}, which never touches a field the user owns.
 */
public final class SmartVaultWorker extends Worker {

    static final String KEY_SUGGEST = "suggest";
    /** Failed local attempts on one basis before it is left alone. */
    static final int MAX_LOCAL_ATTEMPTS = 3;
    /** Suggestion requests one run makes before handing over to the next run. */
    static final int SUGGESTIONS_PER_RUN = 12;
    static final long PROVIDER_TIMEOUT_SECONDS = 150;

    public SmartVaultWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull @Override public Result doWork() {
        Context c = getApplicationContext();
        if (!Prefs.smartVaultEnabled(c)) return Result.success();
        try {
            if (getInputData().getBoolean(KEY_SUGGEST, false)) return suggest(c);
            indexLocally(c, this::isStopped);
            return Result.success();
        } catch (Throwable t) {
            return getRunAttemptCount() < 2 ? Result.retry() : Result.failure();
        }
    }

    interface Stop {
        boolean now();
    }

    // ---- local indexing ---------------------------------------------------------------------------

    /**
     * One pass over the Vault. Package-private and synchronous so tests drive it directly.
     */
    static void indexLocally(Context c, Stop stop) {
        SmartVaultDb db = SmartVaultDb.get(c);
        List<OrbitVaultItem> items = OrbitVaultStore.list(c);
        Set<String> ids = new HashSet<>();
        for (OrbitVaultItem item : items) ids.add(item.id);
        db.forgetAllExcept(ids);

        boolean ocr = Prefs.smartVaultOcr(c);
        boolean readLinks = Prefs.smartVaultReadLinks(c);
        SmartVaultEmbedder embedder = Prefs.smartVaultMeaning(c) ? SmartVaultModel.embedder(c) : null;
        Map<String, Map<String, SmartVaultDb.Derived>> derived = db.allDerived();
        Map<String, SmartVaultDb.Vectors> vectors = embedder == null ? null : db.allVectors();
        boolean recognitionUnavailable = false;

        for (OrbitVaultItem item : items) {
            if (stop != null && stop.now()) return;
            if (!Prefs.smartVaultEnabled(c)) return;

            // 1. Text in a picture.
            if (ocr && !recognitionUnavailable && SmartVault.wantsRecognition(item)) {
                String basis = SmartVault.derivedBasis(item, SmartVaultDb.KIND_OCR);
                if (!isCurrent(derived, item.id, SmartVaultDb.KIND_OCR, basis)
                        && db.attempts(item.id, SmartVaultDb.KIND_OCR, basis) < MAX_LOCAL_ATTEMPTS) {
                    try {
                        Bitmap picture = OrbitVaultMedia.load(item.mediaPath);
                        String text = picture == null ? "" : SmartVaultOcr.recognize(c, picture);
                        if (picture != null && !picture.isRecycled()) picture.recycle();
                        if (picture == null) {
                            db.recordAttempt(item.id, SmartVaultDb.KIND_OCR, basis);
                        } else {
                            writeDerivedIfCurrent(c, db, item.id, SmartVaultDb.KIND_OCR, basis, text);
                        }
                    } catch (SmartVaultOcr.Unavailable e) {
                        // Play services missing or still preparing: stop asking this run, and
                        // leave every picture to try again later.
                        recognitionUnavailable = true;
                    } catch (Throwable t) {
                        db.recordAttempt(item.id, SmartVaultDb.KIND_OCR, basis);
                    }
                }
            }

            // 2. The page behind a saved link.
            if (readLinks && item.isLink()) {
                String basis = SmartVault.derivedBasis(item, SmartVaultDb.KIND_PAGE);
                if (!isCurrent(derived, item.id, SmartVaultDb.KIND_PAGE, basis)
                        && db.attempts(item.id, SmartVaultDb.KIND_PAGE, basis) < 2) {
                    SmartVaultPageReader.Page page = SmartVaultPageReader.read(item.body);
                    if (page != null && page.ok) {
                        String text = (page.title + "\n" + page.text).trim();
                        writeDerivedIfCurrent(c, db, item.id, SmartVaultDb.KIND_PAGE, basis, text);
                        offerPageTitle(c, item.id, page.title);
                    } else {
                        db.recordAttempt(item.id, SmartVaultDb.KIND_PAGE, basis);
                    }
                }
            }
        }

        // 3. Meaning vectors, after recognition and page text exist to be embedded.
        if (embedder == null) return;
        derived = db.allDerived();
        for (OrbitVaultItem stale : items) {
            if (stop != null && stop.now()) return;
            OrbitVaultItem item = OrbitVaultStore.get(c, stale.id);
            if (item == null) continue;
            String ocrText = SmartVaultIndex.currentDerived(derived, item, SmartVaultDb.KIND_OCR);
            String pageText = SmartVaultIndex.currentDerived(derived, item, SmartVaultDb.KIND_PAGE);
            List<String> passages = SmartVault.passagesFor(item, ocrText, pageText);
            String basis = SmartVault.vectorBasis(passages);
            SmartVaultDb.Vectors existing = vectors.get(item.id);
            if (existing != null && existing.basis.equals(basis)
                    && existing.model.equals(SmartVaultModel.MODEL_ID)) {
                continue;
            }
            float[][] out = new float[passages.size()][];
            boolean any = false;
            for (int i = 0; i < passages.size(); i++) {
                out[i] = embedder.embed(passages.get(i));
                if (out[i] == null) out[i] = new float[embedder.dims];
                else any = true;
            }
            synchronized (OrbitVaultStore.class) {
                OrbitVaultItem now = OrbitVaultStore.get(c, item.id);
                if (now == null) continue;
                // Only if the item still produces exactly these passages.
                List<String> check = SmartVault.passagesFor(now, ocrText, pageText);
                if (!SmartVault.vectorBasis(check).equals(basis)) continue;
                db.putVectors(item.id, basis, SmartVaultModel.MODEL_ID, any ? out : null);
            }
        }
    }

    private static boolean isCurrent(Map<String, Map<String, SmartVaultDb.Derived>> derived,
                                     String id, String kind, String basis) {
        Map<String, SmartVaultDb.Derived> rows = derived.get(id);
        if (rows == null) return false;
        SmartVaultDb.Derived row = rows.get(kind);
        return row != null && row.basis.equals(basis);
    }

    /**
     * Stores derived text only if the item still exists and still has the same basis. Holding
     * the Vault's lock makes the check and the write one step, so a delete cannot slip between.
     */
    static boolean writeDerivedIfCurrent(Context c, SmartVaultDb db, String id, String kind,
                                         String basis, String text) {
        synchronized (OrbitVaultStore.class) {
            OrbitVaultItem now = OrbitVaultStore.get(c, id);
            if (now == null || !SmartVault.derivedBasis(now, kind).equals(basis)) return false;
            db.putDerived(id, kind, basis, text);
            return true;
        }
    }

    /**
     * A saved link with no title of its own takes the page's title as a suggestion - never as
     * the user's title - unless Orbit already suggested one.
     */
    private static void offerPageTitle(Context c, String id, String pageTitle) {
        if (pageTitle == null || pageTitle.trim().isEmpty()) return;
        OrbitVaultItem item = OrbitVaultStore.get(c, id);
        if (item == null || !item.title.isEmpty()) return;
        VaultSuggestions existing = item.suggestions;
        if (existing != null && !existing.title.isEmpty()) return;
        VaultSuggestions next = new VaultSuggestions(pageTitle,
                existing == null ? "" : existing.summary,
                existing == null ? null : existing.topics,
                existing == null ? null : existing.rejected,
                item.contentFingerprint(),
                existing == null ? System.currentTimeMillis() : existing.generatedAt,
                existing == null ? "Web page" : existing.provider);
        OrbitVaultStore.applySuggestions(c, id, item.contentFingerprint(), next);
    }

    // ---- AI suggestions ---------------------------------------------------------------------------

    private Result suggest(Context c) {
        Outcome outcome = suggestQueued(c, this::isStopped, SUGGESTIONS_PER_RUN);
        if (outcome == Outcome.RETRY) return Result.retry();
        if (outcome == Outcome.MORE) SmartVault.scheduleSuggestions(c);
        return Result.success();
    }

    enum Outcome { DONE, MORE, RETRY }

    /** Works through queued requests. Package-private so tests drive it with a fake provider. */
    static Outcome suggestQueued(Context c, Stop stop, int limit) {
        SmartVaultDb db = SmartVaultDb.get(c);
        AiProvider provider = AiProviders.active(c);
        List<SmartVaultDb.Job> jobs = db.queuedJobs(limit);
        if (jobs.isEmpty()) return Outcome.DONE;
        if (!provider.supportsCompletion(c)) {
            // Reported once per item and then left alone, so nothing retries against a provider
            // that cannot answer and the user sees why on the item.
            String why = provider.status(c) == AiProvider.Status.NEEDS_SETUP
                    ? "Sign in to " + provider.displayName() + " to get suggestions."
                    : AiProvider.COMPLETION_UNSUPPORTED;
            for (SmartVaultDb.Job job : db.queuedJobs(10_000)) {
                db.putJob(job.itemId, SmartVaultDb.STATE_FAILED, job.basis, job.attempts, why);
            }
            return Outcome.DONE;
        }
        Set<String> existingTopics = OrbitVaultStore.topicsInUse(c).keySet();
        Map<String, Map<String, SmartVaultDb.Derived>> derived = db.allDerived();
        for (SmartVaultDb.Job job : jobs) {
            if (stop != null && stop.now()) return Outcome.MORE;
            if (!Prefs.smartVaultEnabled(c)) return Outcome.DONE;
            OrbitVaultItem item = OrbitVaultStore.get(c, job.itemId);
            if (item == null) {
                db.forget(job.itemId);
                continue;
            }
            String fingerprint = item.contentFingerprint();
            String ocr = SmartVaultIndex.currentDerived(derived, item, SmartVaultDb.KIND_OCR);
            String page = SmartVaultIndex.currentDerived(derived, item, SmartVaultDb.KIND_PAGE);
            if (!SmartVaultEnrichment.hasMaterial(item, ocr, page)) {
                db.putJob(item.id, SmartVaultDb.STATE_DONE, fingerprint, job.attempts,
                        "There is no text here for Orbit to describe yet.");
                continue;
            }
            String prompt = SmartVaultEnrichment.prompt(item, ocr, page, existingTopics);
            final String[] text = {null};
            final String[] error = {null};
            final String[] label = {""};
            CountDownLatch done = new CountDownLatch(1);
            provider.complete(c, SmartVaultEnrichment.INSTRUCTIONS, prompt,
                    new AssistantClient.PlanCallback() {
                        @Override public void onText(String raw, String providerLabel) {
                            text[0] = raw;
                            label[0] = providerLabel == null ? "" : providerLabel;
                            done.countDown();
                        }

                        @Override public void onError(String message) {
                            error[0] = message == null ? "The request failed." : message;
                            done.countDown();
                        }
                    });
            try {
                if (!done.await(PROVIDER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    error[0] = "The AI provider took too long to answer.";
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Outcome.RETRY;
            }
            int attempts = job.attempts + 1;
            if (error[0] != null) {
                if (looksTemporary(error[0]) && attempts < 3) {
                    db.putJob(item.id, SmartVaultDb.STATE_QUEUED, fingerprint, attempts, "");
                    return Outcome.RETRY;
                }
                db.putJob(item.id, SmartVaultDb.STATE_FAILED, fingerprint, attempts,
                        "Suggestions failed: " + shorten(error[0]));
                continue;
            }
            String providerName = label[0].contains("·")
                    ? label[0].substring(0, label[0].indexOf('·')).trim() : label[0];
            VaultSuggestions parsed = SmartVaultEnrichment.parse(text[0], existingTopics,
                    fingerprint, providerName.isEmpty() ? provider.displayName() : providerName,
                    System.currentTimeMillis());
            if (parsed == null) {
                db.putJob(item.id, attempts < 2 ? SmartVaultDb.STATE_QUEUED : SmartVaultDb.STATE_FAILED,
                        fingerprint, attempts, attempts < 2 ? ""
                                : "The AI provider's answer could not be used. Try again later.");
                continue;
            }
            OrbitVaultStore.Apply applied = OrbitVaultStore.applySuggestions(c, item.id,
                    fingerprint, parsed);
            if (applied == OrbitVaultStore.Apply.GONE) {
                db.forget(item.id);
            } else if (applied == OrbitVaultStore.Apply.CHANGED) {
                // The item was edited while the request ran. The edit wins; the next run looks at
                // the new version, a bounded number of times.
                db.putJob(item.id, attempts < 3 ? SmartVaultDb.STATE_QUEUED : SmartVaultDb.STATE_FAILED,
                        "", attempts, attempts < 3 ? ""
                                : "The item kept changing, so Orbit stopped suggesting details.");
            } else {
                db.putJob(item.id, SmartVaultDb.STATE_DONE, fingerprint, attempts, "");
            }
            try { Thread.sleep(600); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Outcome.MORE;
            }
        }
        return db.queuedCount() > 0 ? Outcome.MORE : Outcome.DONE;
    }

    static boolean looksTemporary(String message) {
        String m = message.toLowerCase(java.util.Locale.ROOT);
        return m.contains("timeout") || m.contains("timed out") || m.contains("unable to resolve")
                || m.contains("network") || m.contains("connection") || m.contains("429")
                || m.contains("503") || m.contains("502") || m.contains("took too long");
    }

    private static String shorten(String message) {
        String m = message.replaceAll("\\s+", " ").trim();
        return m.length() <= 140 ? m : m.substring(0, 140) + "…";
    }
}
