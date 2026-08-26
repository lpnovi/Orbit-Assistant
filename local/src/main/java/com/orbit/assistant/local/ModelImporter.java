package com.orbit.assistant.local;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Adopts a model that Orbit already downloaded, instead of downloading 1.6 GB again.
 *
 * <p>Runs on a plain background thread rather than through WorkManager, deliberately: the source
 * is a live file descriptor handed across Binder, and a descriptor cannot outlive the process that
 * received it. Making this survive process death would mean pretending to hold something that is
 * already gone.
 *
 * <p>That is safe because the source is never at risk. Orbit keeps its own copy until this
 * component reports READY, so an interrupted import costs only the partial destination file —
 * which {@link ComponentModelStore#state} sweeps on the next read. The copy is verified against
 * the pinned size and SHA-256 before it is promoted, so a truncated or corrupted transfer can
 * never be promoted to a working model.
 */
final class ModelImporter {
    private static final String TAG = "OrbitLocalImport";
    private static final int BUFFER_BYTES = 1024 * 1024;

    private static final Object LOCK = new Object();
    private static Thread running;
    private static AtomicBoolean cancelled = new AtomicBoolean(false);

    private ModelImporter() {}

    static boolean isRunning(Context c) {
        synchronized (LOCK) {
            return running != null && running.isAlive();
        }
    }

    /**
     * Starts the copy. Returns false when the component cannot honestly accept it, so Orbit can
     * offer the replace-and-redownload path instead of starting something doomed to fail.
     */
    static boolean start(Context context, ParcelFileDescriptor source, long expectedBytes) {
        Context app = context.getApplicationContext();
        if (source == null) return false;
        if (expectedBytes != ComponentModelStore.MODEL_SIZE_BYTES) {
            closeQuietly(source);
            return false;
        }
        synchronized (LOCK) {
            if (running != null && running.isAlive()) {
                closeQuietly(source);
                return false;
            }
            if (ComponentModelStore.isReady(app)) {
                closeQuietly(source);
                return false;
            }
            if (!ComponentModelStore.enoughStorageToImport(app)) {
                closeQuietly(source);
                ComponentModelStore.setState(app, ComponentModelStore.State.ERROR,
                        "Not enough free storage to move the existing model.");
                return false;
            }
            cancelled = new AtomicBoolean(false);
            final AtomicBoolean stop = cancelled;
            ComponentModelStore.setState(app, ComponentModelStore.State.IMPORTING, "");
            running = new Thread(() -> copy(app, source, stop), "orbit-local-import");
            running.setPriority(Thread.NORM_PRIORITY - 1);
            running.start();
            return true;
        }
    }

    static void cancel(Context c) {
        synchronized (LOCK) {
            if (cancelled != null) cancelled.set(true);
        }
    }

    private static void copy(Context app, ParcelFileDescriptor source, AtomicBoolean stop) {
        File destination = ComponentModelStore.importFile(app);
        //noinspection ResultOfMethodCallIgnored
        destination.delete();
        boolean promoted = false;
        try (ParcelFileDescriptor pfd = source;
             InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
             FileOutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[BUFFER_BYTES];
            long written = 0L;
            int read;
            while ((read = in.read(buffer)) > 0) {
                if (stop.get()) {
                    ComponentModelStore.setState(app, ComponentModelStore.State.NOT_INSTALLED, "");
                    return;
                }
                out.write(buffer, 0, read);
                written += read;
                if (written > ComponentModelStore.MODEL_SIZE_BYTES) {
                    ComponentModelStore.setState(app, ComponentModelStore.State.ERROR,
                            "The existing model was larger than expected and was not moved.");
                    return;
                }
            }
            out.getFD().sync();
            if (written != ComponentModelStore.MODEL_SIZE_BYTES) {
                ComponentModelStore.setState(app, ComponentModelStore.State.ERROR,
                        "The existing model could not be moved completely. Nothing was removed.");
                return;
            }
            ComponentModelStore.setState(app, ComponentModelStore.State.VALIDATING, "");
            // Verification decides everything. Only a byte-perfect copy becomes the model, which
            // is precisely what lets Orbit delete its own copy afterwards.
            promoted = ComponentModelStore.validateAndPromote(app, destination);
        } catch (Throwable t) {
            Log.w(TAG, "model import failed: " + t.getClass().getSimpleName());
            ComponentModelStore.setState(app, ComponentModelStore.State.ERROR,
                    "The existing model could not be moved. Nothing was removed; you can try again.");
        } finally {
            if (!promoted) {
                //noinspection ResultOfMethodCallIgnored
                destination.delete();
            }
            synchronized (LOCK) { running = null; }
        }
    }

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        try { pfd.close(); } catch (Throwable ignored) {}
    }
}
