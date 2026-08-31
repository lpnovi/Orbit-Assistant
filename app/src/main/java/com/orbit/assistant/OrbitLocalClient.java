package com.orbit.assistant;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.orbit.assistant.local.IOrbitLocalCallback;
import com.orbit.assistant.local.IOrbitLocalService;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Orbit's side of the Orbit Local service connection.
 *
 * <p>Everything that crosses the process boundary goes through here, so the rest of Orbit — the
 * provider, the management screen — never handles a Binder, a service connection, or a death
 * recipient. What they get instead is an ordinary call that either works or fails with a plain
 * Orbit Local message.
 *
 * <p>Three rules shape it. The component is verified before every bind, not once at startup, so a
 * package uninstalled or replaced underneath Orbit cannot keep being used. The protocol version is
 * confirmed before any real call, so Orbit never runs inference through an interface it does not
 * understand. And a dead service is a clear local failure: it is never quietly turned into a cloud
 * request.
 */
public final class OrbitLocalClient {
    private static final String TAG = "OrbitLocalClient";
    private static final long BIND_TIMEOUT_MS = 8000L;

    /** Why a local call could not be made, in words a person can act on. */
    public static final String NOT_INSTALLED =
            "Orbit Local's component is not installed. Open Settings > AI & account > AI Providers > Orbit Local to set it up.";
    public static final String UPDATE_REQUIRED =
            "The Orbit Local component needs updating for this version of Orbit. Open Settings > AI & account > AI Providers > Orbit Local.";
    public static final String UNTRUSTED =
            "The installed Orbit Local component was not published by Orbit and will not be used. Remove it and install Orbit Local again.";
    public static final String UNAVAILABLE =
            "Orbit Local could not start on this device.";

    private static final Object LOCK = new Object();
    private static IOrbitLocalService service;
    private static ServiceConnection connection;

    private OrbitLocalClient() {}

    /** Streamed generation output, already translated out of Binder terms. */
    public interface StreamCallback {
        void onPartial(String cumulativeText);
        void onDone(String fullText);
        void onError(String message);
    }

    /** The reason the component cannot be used right now, or "" when it can. */
    public static String unavailableReason(Context context) {
        switch (OrbitLocalComponent.state(context)) {
            case INSTALLED: return "";
            case UPDATE_REQUIRED: return UPDATE_REQUIRED;
            case UNTRUSTED: return UNTRUSTED;
            default: return NOT_INSTALLED;
        }
    }

    /**
     * Binds if needed and returns the service, or null.
     *
     * <p>Blocking, and never to be called from the main thread. Callers on Orbit's UI thread use
     * {@link #statusAsync} or the provider's background path instead.
     */
    private static IOrbitLocalService connect(Context context) {
        Context app = context.getApplicationContext();
        if (!OrbitLocalComponent.isUsable(app)) {
            disconnect(app);
            return null;
        }
        synchronized (LOCK) {
            if (service != null && service.asBinder().isBinderAlive()) return service;
            service = null;
        }

        CountDownLatch latch = new CountDownLatch(1);
        ServiceConnection pending = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                synchronized (LOCK) {
                    service = IOrbitLocalService.Stub.asInterface(binder);
                }
                latch.countDown();
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                // The component's process died. Drop the stale proxy so the next request rebinds
                // rather than failing forever against a corpse.
                synchronized (LOCK) { service = null; }
            }

            @Override public void onBindingDied(ComponentName name) {
                synchronized (LOCK) { service = null; }
                latch.countDown();
            }

            @Override public void onNullBinding(ComponentName name) {
                latch.countDown();
            }
        };

        Intent intent = new Intent(OrbitLocalComponent.BIND_ACTION)
                .setPackage(OrbitLocalComponent.PACKAGE);
        boolean bound;
        try {
            bound = app.bindService(intent, pending, Context.BIND_AUTO_CREATE);
        } catch (Throwable t) {
            Log.w(TAG, "bind refused: " + t.getClass().getSimpleName());
            bound = false;
        }
        if (!bound) {
            try { app.unbindService(pending); } catch (Throwable ignored) {}
            return null;
        }
        synchronized (LOCK) {
            if (connection != null) {
                try { app.unbindService(connection); } catch (Throwable ignored) {}
            }
            connection = pending;
        }
        try {
            if (!latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

        IOrbitLocalService bound_service;
        synchronized (LOCK) { bound_service = service; }
        if (bound_service == null) return null;
        // Never speak to an interface Orbit does not understand.
        try {
            if (bound_service.protocolVersion() != OrbitLocalComponent.PROTOCOL_VERSION) {
                Log.w(TAG, "component protocol mismatch");
                return null;
            }
        } catch (Throwable t) {
            synchronized (LOCK) { service = null; }
            return null;
        }
        return bound_service;
    }

    /** Releases the connection, e.g. after the component is uninstalled. */
    public static void disconnect(Context context) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            if (connection != null) {
                try { app.unbindService(connection); } catch (Throwable ignored) {}
            }
            connection = null;
            service = null;
        }
    }

    // ---- status ---------------------------------------------------------------------------------

    /** The component's current state, or null when it cannot be reached. Blocking. */
    public static OrbitLocalStatus status(Context context) {
        IOrbitLocalService bound = connect(context);
        if (bound == null) return null;
        try {
            Bundle bundle = bound.status();
            return bundle == null ? null : OrbitLocalStatus.from(bundle);
        } catch (Throwable t) {
            synchronized (LOCK) { service = null; }
            return null;
        }
    }

    public interface StatusCallback {
        void onStatus(OrbitLocalStatus status);
    }

    /**
     * One thread for every status read Orbit will ever make.
     *
     * <p>The Orbit Local screen polls while it is open, so status is the one call here that
     * repeats. A single-threaded executor makes "no overlapping status readers" a property of the
     * client rather than a rule each caller has to remember, and bounds the cost of a screen left
     * open during a long download to exactly one thread.
     */
    private static final java.util.concurrent.ExecutorService STATUS_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "orbit-local-status");
                thread.setDaemon(true);
                return thread;
            });

    /** {@link #status} off the main thread, for UI that must not block. */
    public static void statusAsync(Context context, StatusCallback callback) {
        Context app = context.getApplicationContext();
        STATUS_EXECUTOR.execute(() -> callback.onStatus(status(app)));
    }

    // ---- model lifecycle -------------------------------------------------------------------------

    public static void startModelDownload(Context context) {
        run(context, s -> s.startModelDownload());
    }

    public static void pauseModelDownload(Context context) {
        run(context, s -> s.pauseModelDownload());
    }

    public static void cancelModelDownload(Context context) {
        run(context, s -> s.cancelModelDownload());
    }

    public static void deleteModel(Context context) {
        run(context, s -> s.deleteModel());
    }

    public static void unloadEngine(Context context) {
        run(context, s -> s.unloadEngine());
    }

    public static void cancelGeneration(Context context) {
        run(context, s -> s.cancelGeneration());
    }

    /**
     * Hands an existing Orbit-side model file to the component through a read-only descriptor.
     *
     * <p>The file itself never crosses Binder — only a descriptor does. Returns whether the
     * component accepted the transfer, not whether it finished; completion is observed through
     * {@link #status}, and Orbit deletes nothing until it sees READY.
     */
    public static boolean startModelImport(Context context, File source, long expectedBytes) {
        IOrbitLocalService bound = connect(context);
        if (bound == null || source == null || !source.isFile()) return false;
        ParcelFileDescriptor descriptor = null;
        try {
            descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY);
            return bound.startModelImport(descriptor, expectedBytes);
        } catch (Throwable t) {
            Log.w(TAG, "model import could not start: " + t.getClass().getSimpleName());
            return false;
        } finally {
            // Binder duplicates the descriptor for the other process; this end is done with it.
            if (descriptor != null) {
                try { descriptor.close(); } catch (Throwable ignored) {}
            }
        }
    }

    public static void abortModelImport(Context context) {
        run(context, s -> s.abortModelImport());
    }

    // ---- the action model's own lifecycle -----------------------------------------------------

    public static void startActionModelDownload(Context context) {
        run(context, s -> s.startActionModelDownload());
    }

    public static void pauseActionModelDownload(Context context) {
        run(context, s -> s.pauseActionModelDownload());
    }

    public static void cancelActionModelDownload(Context context) {
        run(context, s -> s.cancelActionModelDownload());
    }

    public static void deleteActionModel(Context context) {
        run(context, s -> s.deleteActionModel());
    }

    public static void cancelActionGeneration(Context context) {
        run(context, s -> s.cancelActionGeneration());
    }

    // ---- generation ------------------------------------------------------------------------------

    /**
     * Runs one generation in the component.
     *
     * <p>Every failure path ends in {@code onError} with an Orbit Local message. None of them
     * reroutes the prompt anywhere else: a request the user aimed at on-device AI is never
     * quietly handed to a cloud provider.
     */
    public static void generate(Context context, String prompt, StreamCallback callback) {
        generate(context, prompt, false, callback);
    }

    /**
     * Runs one action-model generation.
     *
     * <p>The same transport as chat, aimed at the other model. Orbit builds the prompt, the
     * component runs the small model, and Orbit validates whatever comes back before any of it can
     * mean anything. Nothing about action handling lives on the component's side of this call.
     */
    public static void generateAction(Context context, String prompt, StreamCallback callback) {
        generate(context, prompt, true, callback);
    }

    private static void generate(Context context, String prompt, boolean actionModel,
                                 StreamCallback callback) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            String reason = unavailableReason(app);
            if (!reason.isEmpty()) {
                callback.onError(reason);
                return;
            }
            IOrbitLocalService bound = connect(app);
            if (bound == null) {
                callback.onError(UNAVAILABLE);
                return;
            }
            try {
                IOrbitLocalCallback stub = new IOrbitLocalCallback.Stub() {
                    @Override public void onPartial(String cumulativeText) {
                        callback.onPartial(cumulativeText == null ? "" : cumulativeText);
                    }

                    @Override public void onDone(String fullText) {
                        callback.onDone(fullText == null ? "" : fullText);
                    }

                    @Override public void onError(String message) {
                        callback.onError("Orbit Local could not answer: "
                                + (message == null ? "unknown error" : message));
                    }
                };
                if (actionModel) bound.generateAction(prompt, stub);
                else bound.generate(prompt, stub);
            } catch (Throwable t) {
                // Includes the component's process dying mid-call. Drop the proxy so the next
                // attempt rebinds cleanly, and report it as what it is.
                synchronized (LOCK) { service = null; }
                callback.onError("Orbit Local stopped unexpectedly. Try again, or check the Orbit Local screen.");
            }
        }, "orbit-local-generate").start();
    }

    // ---- plumbing --------------------------------------------------------------------------------

    private interface ServiceCall {
        void run(IOrbitLocalService service) throws Exception;
    }

    /** Fire-and-forget management call, off the main thread, never throwing to the caller. */
    private static void run(Context context, ServiceCall call) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            IOrbitLocalService bound = connect(app);
            if (bound == null) return;
            try {
                call.run(bound);
            } catch (Throwable t) {
                synchronized (LOCK) { service = null; }
                Log.w(TAG, "component call failed: " + t.getClass().getSimpleName());
            }
        }, "orbit-local-call").start();
    }
}
