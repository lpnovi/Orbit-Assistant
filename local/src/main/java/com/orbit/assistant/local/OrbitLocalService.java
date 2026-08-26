package com.orbit.assistant.local;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * The only way into the Orbit Local component.
 *
 * <p>Exported, because Orbit is a separate package and has to reach it — but guarded twice. The
 * manifest requires a signature-level permission, which Android grants only to a package signed
 * with Orbit's permanent release certificate; and every single transaction independently
 * re-verifies the calling UID's package name and signing certificate here. The permission alone
 * would be adequate on a healthy device; the explicit check is what makes that assumption
 * something the component proves rather than trusts.
 */
public final class OrbitLocalService extends Service {
    private static final String TAG = "OrbitLocalService";

    /** The IPC contract version. Bumped only when the interface's meaning changes. */
    public static final int PROTOCOL_VERSION = 1;

    /** The only package allowed to bind, whatever the permission system reports. */
    private static final String ORBIT_PACKAGE = "com.orbit.assistant";
    /** Orbit's permanent release certificate. The same pin the main app carries. */
    private static final String ORBIT_CERTIFICATE_SHA256 =
            "7DAD619385DFF11EC731AA555F2B448A943C7391813D1A94DF1CB4232ECD41E3";

    // ---- status Bundle keys, mirrored by Orbit's OrbitLocalStatus ------------------------------

    public static final String KEY_PROTOCOL = "protocol";
    public static final String KEY_COMPONENT_VERSION_NAME = "componentVersionName";
    public static final String KEY_COMPONENT_VERSION_CODE = "componentVersionCode";
    public static final String KEY_MODEL_STATE = "modelState";
    public static final String KEY_MODEL_ID = "modelId";
    public static final String KEY_MODEL_DISPLAY_NAME = "modelDisplayName";
    public static final String KEY_MODEL_BYTES = "modelBytes";
    public static final String KEY_MODEL_TOTAL_BYTES = "modelTotalBytes";
    public static final String KEY_MODEL_SIZE_BYTES = "modelSizeBytes";
    public static final String KEY_MODEL_ERROR = "modelError";
    public static final String KEY_FREE_BYTES = "freeBytes";

    @Override public IBinder onBind(Intent intent) {
        return binder;
    }

    private final IOrbitLocalService.Stub binder = new IOrbitLocalService.Stub() {

        @Override public int protocolVersion() {
            requireOrbitCaller();
            return PROTOCOL_VERSION;
        }

        @Override public Bundle status() {
            requireOrbitCaller();
            Context c = getApplicationContext();
            Bundle out = new Bundle();
            out.putInt(KEY_PROTOCOL, PROTOCOL_VERSION);
            out.putString(KEY_COMPONENT_VERSION_NAME, BuildConfig.VERSION_NAME);
            out.putLong(KEY_COMPONENT_VERSION_CODE, BuildConfig.VERSION_CODE);
            out.putString(KEY_MODEL_STATE, ComponentModelStore.state(c).name());
            out.putString(KEY_MODEL_ID, ComponentModelStore.MODEL_ID);
            out.putString(KEY_MODEL_DISPLAY_NAME, ComponentModelStore.MODEL_DISPLAY_NAME);
            out.putLong(KEY_MODEL_BYTES, ComponentModelStore.downloadedBytes(c));
            out.putLong(KEY_MODEL_TOTAL_BYTES, ComponentModelStore.totalModelBytes(c));
            out.putLong(KEY_MODEL_SIZE_BYTES, ComponentModelStore.MODEL_SIZE_BYTES);
            out.putString(KEY_MODEL_ERROR, ComponentModelStore.errorMessage(c));
            out.putLong(KEY_FREE_BYTES, ComponentModelStore.freeStorageBytes(c));
            return out;
        }

        @Override public void startModelDownload() {
            requireOrbitCaller();
            ComponentDownloadWorker.start(getApplicationContext());
        }

        @Override public void pauseModelDownload() {
            requireOrbitCaller();
            Context c = getApplicationContext();
            ComponentDownloadWorker.cancel(c);
            ComponentModelStore.setState(c, ComponentModelStore.State.PAUSED, "");
        }

        @Override public void cancelModelDownload() {
            requireOrbitCaller();
            ComponentDownloadWorker.cancelAndDiscard(getApplicationContext());
        }

        @Override public void deleteModel() {
            requireOrbitCaller();
            ComponentModelStore.delete(getApplicationContext());
        }

        @Override public boolean startModelImport(ParcelFileDescriptor source, long expectedBytes) {
            requireOrbitCaller();
            return ModelImporter.start(getApplicationContext(), source, expectedBytes);
        }

        @Override public void abortModelImport() {
            requireOrbitCaller();
            ModelImporter.cancel(getApplicationContext());
        }

        @Override public void generate(String prompt, IOrbitLocalCallback callback) {
            requireOrbitCaller();
            if (callback == null) return;
            Context c = getApplicationContext();
            if (!ComponentModelStore.isReady(c)) {
                safeError(callback, "the local model is not installed");
                return;
            }
            if (prompt == null || prompt.trim().isEmpty()) {
                safeError(callback, "the request was empty");
                return;
            }
            LocalLlmEngine.generate(c, prompt, new LocalLlmEngine.StreamCallback() {
                @Override public void onPartial(String cumulativeText) {
                    try { callback.onPartial(cumulativeText); } catch (RemoteException ignored) {
                        // Orbit went away mid-generation. Stop rather than keep burning the CPU.
                        LocalLlmEngine.requestCancel();
                    }
                }

                @Override public void onDone(String fullText) {
                    try { callback.onDone(fullText); } catch (RemoteException ignored) {}
                }

                @Override public void onError(String message) {
                    safeError(callback, message);
                }
            });
        }

        @Override public void cancelGeneration() {
            requireOrbitCaller();
            LocalLlmEngine.requestCancel();
        }

        @Override public void unloadEngine() {
            requireOrbitCaller();
            LocalLlmEngine.unload();
        }
    };

    private static void safeError(IOrbitLocalCallback callback, String message) {
        try { callback.onError(message == null ? "unknown error" : message); }
        catch (RemoteException ignored) {}
    }

    /**
     * Confirms the caller really is Orbit, on every transaction.
     *
     * <p>Throws {@link SecurityException}, which Binder delivers to the caller rather than
     * crashing the component. Anything that is not the expected package signed by Orbit's
     * permanent certificate is refused outright — there is no partial trust here.
     */
    private void requireOrbitCaller() {
        int uid = Binder.getCallingUid();
        if (uid == android.os.Process.myUid()) return;
        if (!isOrbit(getApplicationContext(), uid)) {
            Log.w(TAG, "rejected a bind attempt from an unauthorized caller");
            throw new SecurityException("Only Orbit may use the Orbit Local component.");
        }
    }

    /** True when every package sharing this UID is Orbit, signed by Orbit's certificate. */
    static boolean isOrbit(Context context, int uid) {
        PackageManager pm = context.getPackageManager();
        String[] packages = pm.getPackagesForUid(uid);
        if (packages == null || packages.length == 0) return false;
        boolean sawOrbit = false;
        for (String packageName : packages) {
            if (!ORBIT_PACKAGE.equals(packageName)) return false;
            if (!hasOrbitCertificate(pm, packageName)) return false;
            sawOrbit = true;
        }
        return sawOrbit;
    }

    private static boolean hasOrbitCertificate(PackageManager pm, String packageName) {
        try {
            SigningInfo info = pm.getPackageInfo(packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES).signingInfo;
            if (info == null || info.hasMultipleSigners()) return false;
            Signature[] signers = info.getApkContentsSigners();
            if (signers == null || signers.length != 1) return false;
            return ORBIT_CERTIFICATE_SHA256.equalsIgnoreCase(sha256(signers[0].toByteArray()));
        } catch (Throwable t) {
            return false;
        }
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format(Locale.US, "%02X", b & 0xff));
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
