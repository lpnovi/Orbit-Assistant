package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * The on-device meaning model Smart Vault downloads when the user asks for search by meaning.
 *
 * <p><b>What it is.</b> Model2Vec {@code potion-base-8M} (MIT licence, by Minish Lab), a static
 * sentence-embedding table of 29,528 x 256 floats plus its WordPiece vocabulary: two plain data
 * files, about 30 MB together, read by {@link SmartVaultEmbedder} in pure Java. It is not code and
 * nothing in it executes.
 *
 * <p><b>Why a download.</b> Most people never turn search by meaning on, so shipping 30 MB inside
 * every Orbit install would be the wrong trade. The files are fetched only after the user turns
 * the feature on, from Hugging Face at one pinned revision, and each is checked against a SHA-256
 * compiled into Orbit before it is used. A file that does not match is deleted, never loaded.
 *
 * <p><b>Measured, not assumed.</b> On the development machine the table maps in about 25 ms and one
 * passage embeds in about 45 microseconds, so a 300 item Vault is indexed in well under a second
 * once the model is present. It adds nothing to the APK and needs no native library, GPU or
 * particular chipset.
 */
final class SmartVaultModel {

    static final String MODEL_ID = "potion-base-8M@bf8b056";
    static final String LICENSE = "MIT";
    static final String AUTHOR = "Minish Lab (Model2Vec)";
    private static final String REVISION = "bf8b056651a2c21b8d2565580b8569da283cab23";
    private static final String BASE = "https://huggingface.co/minishlab/potion-base-8M/resolve/"
            + REVISION + "/";

    static final class Asset {
        final String name;
        final long size;
        final String sha256;

        Asset(String name, long size, String sha256) {
            this.name = name;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    static final Asset VOCAB = new Asset("vocab.txt", 219_690L,
            "1394523a67ddd404a825428018c0582a6998bcfa044ecbcbf1f4d71adb94c61c");
    static final Asset WEIGHTS = new Asset("model.safetensors", 30_236_760L,
            "f65d0f325faadc1e121c319e2faa41170d3fa07d8c89abd48ca5358d9a223de2");
    static final long TOTAL_BYTES = VOCAB.size + WEIGHTS.size;

    static final String UNIQUE_WORK = "orbit-smart-vault-model";
    private static final String PREFS = "orbit_smart_vault_model";
    private static final String KEY_DONE = "done_bytes";
    private static final String KEY_ERROR = "error";
    private static final String KEY_RUNNING = "running";
    private static final String VERIFIED_MARKER = ".verified";

    enum State { MISSING, DOWNLOADING, READY, FAILED }

    private static SmartVaultEmbedder embedder;

    private SmartVaultModel() {}

    static File dir(Context c) {
        return new File(new File(c.getFilesDir(), "smart_vault"), "model-" + REVISION.substring(0, 7));
    }

    /**
     * Ready only when both files are present at their exact sizes and were verified by hash when
     * they arrived. The hash is not recomputed on every launch: the files live in private storage
     * nothing else can write to, and the sizes still catch truncation.
     */
    static boolean isReady(Context c) {
        File d = dir(c);
        File vocab = new File(d, VOCAB.name);
        File weights = new File(d, WEIGHTS.name);
        return new File(d, VERIFIED_MARKER).exists()
                && vocab.length() == VOCAB.size && weights.length() == WEIGHTS.size;
    }

    static State state(Context c) {
        if (isReady(c)) return State.READY;
        SharedPreferences p = prefs(c);
        if (p.getBoolean(KEY_RUNNING, false)) return State.DOWNLOADING;
        if (!p.getString(KEY_ERROR, "").isEmpty()) return State.FAILED;
        return State.MISSING;
    }

    static String error(Context c) {
        return prefs(c).getString(KEY_ERROR, "");
    }

    /** Progress from 0 to 100 while a download runs. */
    static int percent(Context c) {
        long done = prefs(c).getLong(KEY_DONE, 0L);
        return (int) Math.max(0, Math.min(100, done * 100 / TOTAL_BYTES));
    }

    static String sizeLabel() {
        return String.format(Locale.US, "%.0f MB", TOTAL_BYTES / 1_000_000.0);
    }

    /** Asks for the download. Runs once there is a connection, and survives the app closing. */
    static void requestDownload(Context c) {
        if (isReady(c)) return;
        prefs(c).edit().putBoolean(KEY_RUNNING, true).putString(KEY_ERROR, "")
                .putLong(KEY_DONE, 0L).apply();
        try {
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build();
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(DownloadWorker.class)
                    .setConstraints(constraints)
                    .build();
            WorkManager.getInstance(c.getApplicationContext())
                    .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request);
        } catch (Exception e) {
            prefs(c).edit().putBoolean(KEY_RUNNING, false)
                    .putString(KEY_ERROR, "Orbit could not schedule the download.").apply();
        }
    }

    /** Removes the model and every vector made with it. Search falls back to keywords. */
    static synchronized void delete(Context c) {
        try {
            WorkManager.getInstance(c.getApplicationContext()).cancelUniqueWork(UNIQUE_WORK);
        } catch (Exception ignored) {
        }
        embedder = null;
        File d = dir(c);
        File[] files = d.listFiles();
        if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
        //noinspection ResultOfMethodCallIgnored
        d.delete();
        prefs(c).edit().clear().apply();
        try { SmartVaultDb.get(c).clearVectors(); } catch (Exception ignored) { }
        SmartVaultIndex.invalidate();
    }

    /** The loaded model, or null when it is not downloaded or cannot be read. */
    static synchronized SmartVaultEmbedder embedder(Context c) {
        if (embedder != null) return embedder;
        if (!isReady(c)) return null;
        try {
            File d = dir(c);
            embedder = SmartVaultEmbedder.open(new File(d, VOCAB.name), new File(d, WEIGHTS.name));
        } catch (Throwable t) {
            embedder = null;
        }
        return embedder;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- the download ---------------------------------------------------------------------------

    /**
     * Fetches both files into temporary names, verifies each against its pinned hash, and only
     * then moves them into place and writes the verified marker. An interrupted or tampered
     * download leaves nothing that {@link #isReady} would accept.
     */
    static String download(Context c, ProgressListener listener) {
        File d = dir(c);
        if (!d.isDirectory() && !d.mkdirs()) return "Orbit could not create the model folder.";
        long done = 0L;
        for (Asset asset : new Asset[]{VOCAB, WEIGHTS}) {
            File target = new File(d, asset.name);
            if (target.length() == asset.size && sha256(target).equalsIgnoreCase(asset.sha256)) {
                done += asset.size;
                continue;
            }
            File part = new File(d, asset.name + ".part");
            String error = fetch(BASE + asset.name, part, asset.size, done, listener);
            if (!error.isEmpty()) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                return error;
            }
            if (!sha256(part).equalsIgnoreCase(asset.sha256)) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                return "The downloaded model did not match Orbit's pinned checksum, so it was "
                        + "discarded.";
            }
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            if (!part.renameTo(target)) return "Orbit could not store the model.";
            done += asset.size;
        }
        try {
            if (!new File(d, VERIFIED_MARKER).createNewFile() && !new File(d, VERIFIED_MARKER).exists()) {
                return "Orbit could not store the model.";
            }
        } catch (Exception e) {
            return "Orbit could not store the model.";
        }
        return "";
    }

    interface ProgressListener {
        void onProgress(long doneBytes);
    }

    private static String fetch(String address, File part, long expected, long before,
                                ProgressListener listener) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(60_000);
            connection.setRequestProperty("User-Agent",
                    "OrbitAssistant/" + BuildConfig.VERSION_NAME + " (Android; Smart Vault)");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                return "The model server answered " + status + ". Try again later.";
            }
            // A redirect may only ever land on another https address.
            if (!"https".equalsIgnoreCase(connection.getURL().getProtocol())) {
                return "The model download was redirected somewhere unsafe, so Orbit stopped.";
            }
            long written = 0L;
            try (InputStream in = connection.getInputStream();
                 OutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                long lastReport = 0L;
                while ((read = in.read(buffer)) != -1) {
                    written += read;
                    if (written > expected) return "The model download was larger than expected.";
                    out.write(buffer, 0, read);
                    if (listener != null && written - lastReport > 512 * 1024) {
                        lastReport = written;
                        listener.onProgress(before + written);
                    }
                }
            }
            if (written != expected) return "The model download was incomplete. Try again.";
            return "";
        } catch (java.net.UnknownHostException e) {
            return "No connection to the model server. Orbit will try again when you are online.";
        } catch (Exception e) {
            return "The model download failed. Try again later.";
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static String sha256(File file) {
        if (file == null || !file.isFile()) return "";
        try (InputStream in = new java.io.FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            StringBuilder out = new StringBuilder();
            for (byte b : digest.digest()) out.append(String.format(Locale.US, "%02x", b));
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** The WorkManager job that runs {@link #download}. */
    public static final class DownloadWorker extends Worker {
        public DownloadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
            super(context, params);
        }

        @NonNull @Override public Result doWork() {
            Context c = getApplicationContext();
            if (!Prefs.smartVaultMeaning(c)) {
                prefs(c).edit().putBoolean(KEY_RUNNING, false).apply();
                return Result.success();
            }
            String error = download(c, done -> prefs(c).edit().putLong(KEY_DONE, done).apply());
            if (error.isEmpty()) {
                prefs(c).edit().putBoolean(KEY_RUNNING, false).putString(KEY_ERROR, "")
                        .putLong(KEY_DONE, TOTAL_BYTES).apply();
                SmartVault.scheduleIndexing(c);
                return Result.success();
            }
            if (getRunAttemptCount() < 3 && error.startsWith("No connection")) {
                return Result.retry();
            }
            prefs(c).edit().putBoolean(KEY_RUNNING, false).putString(KEY_ERROR, error).apply();
            return Result.failure();
        }
    }
}
