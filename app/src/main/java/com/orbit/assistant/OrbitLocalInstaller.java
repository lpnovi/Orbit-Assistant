package com.orbit.assistant;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Downloads and installs the optional Orbit Local component.
 *
 * <p>Deliberately built on the same ideas as Orbit's own updater rather than as a second,
 * looser sideload path: the asset comes only from the official GitHub Release for the running
 * Orbit version, its URL is constructed rather than taken from anywhere, and it is verified
 * completely before Android's package installer is ever invoked. Beta builds get exactly this
 * treatment too — a Beta is newer code, not less trusted code.
 *
 * <p>Fail-closed throughout. A checksum, package, version, signer count, or certificate that does
 * not match deletes the download and reports it; nothing is ever installed "best effort".
 */
public final class OrbitLocalInstaller {
    private static final String TAG = "OrbitLocalInstaller";
    private static final String REPOSITORY = "lpnovi/Orbit-Assistant";
    private static final String RELEASE_DOWNLOAD_BASE =
            "https://github.com/" + REPOSITORY + "/releases/download/";
    /** Orbit's permanent release certificate. The component must carry the same one. */
    private static final String CERTIFICATE_SHA256 = OrbitLocalComponent.CERTIFICATE_SHA256;
    private static final long MAX_APK_BYTES = 200L * 1024L * 1024L;
    private static final int MAX_JSON_BYTES = 256 * 1024;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private OrbitLocalInstaller() {}

    public interface Callback {
        void onProgress(int percent);
        void onVerifying();
        void onReady(File apk);
        void onError(String message);
    }

    /**
     * The component asset that belongs to this Orbit build.
     *
     * <p>Always the running version's own component, never "the latest one". A main app and a
     * component from different releases is a combination nobody has tested, and Orbit refuses to
     * assemble one by accident.
     */
    public static String assetName() {
        return "Orbit-Local-v" + BuildConfig.VERSION_NAME + ".apk";
    }

    public static String releaseTag() {
        return "v" + BuildConfig.VERSION_NAME;
    }

    /** Approximate download size for the setup sheet, or 0 when it is not known yet. */
    public static long knownComponentBytes(Context context) {
        long installed = OrbitLocalComponent.installedApkBytes(context);
        if (installed > 0L) return installed;
        return Prefs.get(context).getLong("orbit_local_component_bytes", 0L);
    }

    static void rememberComponentBytes(Context context, long bytes) {
        if (bytes > 0L) {
            Prefs.get(context).edit().putLong("orbit_local_component_bytes", bytes).apply();
        }
    }

    /** Downloads and fully verifies the component APK for this Orbit release. */
    public static void downloadAsync(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            File output = null;
            File part = null;
            HttpURLConnection connection = null;
            try {
                JSONObject manifest = readJson(
                        downloadUrl(releaseTag(), "orbit-update.json"), MAX_JSON_BYTES);
                Expected expected = Expected.from(manifest);

                File directory = componentDirectory(app);
                // Nothing already in this directory can help a fresh download, and an old Beta's
                // component APK is 35 MB of pure waste from the moment this build exists. Clear
                // the whole directory before writing into it rather than layering another file on
                // top of whatever previous attempts left behind.
                cleanup(app);
                part = new File(directory, expected.assetName + ".part");
                output = new File(directory, expected.assetName);
                safeDelete(part);
                safeDelete(output);

                connection = open(downloadUrl(releaseTag(), expected.assetName));
                int status = connection.getResponseCode();
                requireHttpsFinalUrl(connection);
                if (status < 200 || status >= 300) {
                    throw new InstallException("The Orbit Local download failed (HTTP " + status + ").");
                }
                long contentLength = connection.getContentLengthLong();
                if (contentLength > MAX_APK_BYTES) {
                    throw new InstallException("The Orbit Local component is unexpectedly large.");
                }

                long total = 0L;
                int lastPercent = -1;
                byte[] buffer = new byte[32 * 1024];
                try (InputStream in = new BufferedInputStream(connection.getInputStream());
                     FileOutputStream out = new FileOutputStream(part)) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        total += read;
                        if (total > MAX_APK_BYTES) {
                            throw new InstallException("The Orbit Local download exceeded its safe size limit.");
                        }
                        out.write(buffer, 0, read);
                        long denominator = expected.sizeBytes > 0L ? expected.sizeBytes : contentLength;
                        int percent = denominator > 0L
                                ? (int) Math.min(100L, total * 100L / denominator) : -1;
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            callback.onProgress(percent);
                        }
                    }
                    out.getFD().sync();
                }
                if (!part.renameTo(output)) {
                    throw new InstallException("Orbit could not finish preparing the component.");
                }
                callback.onVerifying();
                verify(app, output, expected);
                rememberComponentBytes(app, output.length());
                callback.onReady(output);
            } catch (Exception e) {
                safeDelete(part);
                safeDelete(output);
                Log.w(TAG, "component install failed: " + e.getClass().getSimpleName());
                callback.onError(e instanceof InstallException && e.getMessage() != null
                        ? e.getMessage()
                        : "Orbit could not download the Orbit Local component.");
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    /** What the release manifest says this release's component must be. */
    static final class Expected {
        final String packageName;
        final String versionName;
        final long versionCode;
        final String assetName;
        final String sha256;
        final String certificateSha256;
        final int protocol;
        final long sizeBytes;

        private Expected(String packageName, String versionName, long versionCode, String assetName,
                         String sha256, String certificateSha256, int protocol, long sizeBytes) {
            this.packageName = packageName;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.assetName = assetName;
            this.sha256 = sha256;
            this.certificateSha256 = certificateSha256;
            this.protocol = protocol;
            this.sizeBytes = sizeBytes;
        }

        /**
         * Reads the additive component block from the release manifest.
         *
         * <p>The manifest stays schema 1 so an older Orbit keeps updating normally: the component
         * block is a field older readers simply never look at. A manifest without it is a release
         * that has no component, which is a clear error here rather than a silent no-op.
         */
        static Expected from(JSONObject manifest) throws Exception {
            JSONObject component = manifest == null ? null : manifest.optJSONObject("component");
            if (component == null) {
                throw new InstallException("This Orbit release does not publish an Orbit Local component.");
            }
            Expected expected = new Expected(
                    component.optString("packageName", "").trim(),
                    component.optString("versionName", "").trim(),
                    component.optLong("versionCode", -1L),
                    component.optString("apkAssetName", "").trim(),
                    component.optString("apkSha256", "").trim(),
                    component.optString("certificateSha256", "").trim(),
                    component.optInt("protocol", -1),
                    component.optLong("apkSize", -1L));

            if (!OrbitLocalComponent.PACKAGE.equals(expected.packageName)) {
                throw new InstallException("The Orbit Local component metadata names the wrong package.");
            }
            if (!assetName().equals(expected.assetName)) {
                throw new InstallException("The Orbit Local component does not match this Orbit version.");
            }
            // Both halves of the version, not just the name: the component and the main app are
            // released in lockstep, and a mismatched versionCode is a mismatched build.
            if (!BuildConfig.VERSION_NAME.equals(expected.versionName)
                    || expected.versionCode != BuildConfig.VERSION_CODE) {
                throw new InstallException("The Orbit Local component does not match this Orbit version.");
            }
            if (expected.protocol != OrbitLocalComponent.PROTOCOL_VERSION) {
                throw new InstallException("The Orbit Local component uses an incompatible interface.");
            }
            if (!expected.sha256.matches("^[0-9a-fA-F]{64}$")) {
                throw new InstallException("The Orbit Local component checksum is malformed.");
            }
            if (!normalize(expected.certificateSha256).equals(normalize(CERTIFICATE_SHA256))) {
                throw new InstallException("The Orbit Local component certificate metadata is invalid.");
            }
            return expected;
        }
    }

    /**
     * Everything that must be true before Android's installer is invoked.
     *
     * <p>The same standard the main APK is held to: checksum, package, version, exactly one
     * signer, and Orbit's permanent certificate.
     */
    static void verify(Context context, File apk, Expected expected) throws Exception {
        File root = componentDirectory(context).getCanonicalFile();
        File candidate = apk.getCanonicalFile();
        if (!candidate.getPath().startsWith(root.getPath() + File.separator) || !candidate.isFile()) {
            throw new InstallException("The downloaded component file is unavailable.");
        }
        if (!normalize(sha256(candidate)).equals(normalize(expected.sha256))) {
            throw new InstallException("The downloaded component checksum did not match the official release.");
        }
        PackageInfo info = context.getPackageManager().getPackageArchiveInfo(
                candidate.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (info == null || info.signingInfo == null) {
            throw new InstallException("Android could not inspect the downloaded component.");
        }
        if (!OrbitLocalComponent.PACKAGE.equals(info.packageName)) {
            throw new InstallException("The downloaded component has the wrong package name.");
        }
        if (!expected.versionName.equals(info.versionName)
                || info.getLongVersionCode() != expected.versionCode) {
            throw new InstallException("The downloaded component version does not match the release manifest.");
        }
        if (info.signingInfo.hasMultipleSigners()) {
            throw new InstallException("The downloaded component has an unexpected signer set.");
        }
        Signature[] signers = info.signingInfo.getApkContentsSigners();
        if (signers == null || signers.length != 1) {
            throw new InstallException("The downloaded component signer could not be verified.");
        }
        String certificate = hex(MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray()));
        if (!normalize(certificate).equals(normalize(CERTIFICATE_SHA256))) {
            throw new InstallException("The downloaded component is not signed by Orbit's permanent certificate.");
        }
    }

    /** The MIME type Android's package installer registers for. */
    static final String APK_MIME_TYPE = "application/vnd.android.package-archive";
    /** The FileProvider authority declared in Orbit's manifest as ${applicationId}.fileprovider. */
    static String fileProviderAuthority(Context context) {
        return context.getPackageName() + ".fileprovider";
    }

    /**
     * The steps of the handoff, so a failure says which one broke.
     *
     * <p>In v0.7.7.5-beta.1 every one of these collapsed into a single "Android could not open the
     * package installer", which was true of only the last of them. The APK had in fact downloaded
     * and verified perfectly; {@code FILEPROVIDER_URI} was what failed, because the component's
     * cache directory was missing from {@code file_paths.xml}. Naming the stage is what turns a
     * Beta report into a diagnosis.
     */
    enum InstallStage {
        APK_MISSING("The verified component file is no longer available. Download it again."),
        OUTSIDE_COMPONENT_DIRECTORY("The verified component file is unavailable."),
        FILEPROVIDER_URI("Orbit could not share the component with Android's installer."),
        NO_INSTALLER("This device has no package installer available."),
        START_ACTIVITY("Android could not open the package installer.");

        final String message;

        InstallStage(String message) {
            this.message = message;
        }
    }

    /**
     * Hands the verified APK to Android's own installer. The user still confirms it.
     *
     * <p>Structurally identical to {@link OrbitUpdater#launchPackageInstaller} — the same
     * ACTION_VIEW, the same APK MIME type, the same read-permission grant, the same FileProvider
     * authority. That path has installed Orbit's own updates for many releases, so this one
     * deliberately matches it rather than inventing a second mechanism.
     */
    public static void launchInstaller(Activity activity, File apk) throws Exception {
        InstallStage stage = InstallStage.APK_MISSING;
        try {
            if (apk == null || !apk.isFile()) throw new InstallException(stage.message);

            stage = InstallStage.OUTSIDE_COMPONENT_DIRECTORY;
            File root = componentDirectory(activity).getCanonicalFile();
            File candidate = apk.getCanonicalFile();
            // Unchanged from Beta 1: only a file inside Orbit's own component cache may ever be
            // offered for installation, whatever path the caller passed in.
            if (!candidate.getPath().startsWith(root.getPath() + File.separator)
                    || !candidate.isFile()) {
                throw new InstallException(stage.message);
            }

            stage = InstallStage.FILEPROVIDER_URI;
            Uri uri = FileProvider.getUriForFile(
                    activity, fileProviderAuthority(activity), candidate);

            Intent install = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME_TYPE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            stage = InstallStage.NO_INSTALLER;
            if (install.resolveActivity(activity.getPackageManager()) == null) {
                throw new InstallException(stage.message);
            }

            stage = InstallStage.START_ACTIVITY;
            activity.startActivity(install);
            log(activity, InstallStage.START_ACTIVITY, null);
        } catch (Throwable t) {
            log(activity, stage, t);
            throw t instanceof InstallException ? (InstallException) t
                    : new InstallException(stage.message);
        }
    }

    /**
     * Records which stage the handoff reached, and what threw.
     *
     * <p>The exception class and stage go to logcat and to Orbit's Diagnostics page, where a Beta
     * tester can read them. The user-facing message stays the short sentence on the stage, and no
     * filesystem path ever reaches the UI.
     */
    private static void log(Context context, InstallStage stage, Throwable failure) {
        String detail = "stage=" + stage.name()
                + (failure == null ? " result=ok" : " cause=" + failure.getClass().getSimpleName());
        Log.i(TAG, "component_installer " + detail);
        if (failure != null) {
            try {
                DiagnosticStore.recordError(context.getApplicationContext(),
                        "Orbit Local component installer: " + detail);
            } catch (Throwable ignored) {
                // Diagnostics must never be the reason an install fails.
            }
        }
    }

    /** Asks Android to uninstall the component. Android owns the confirmation, and the outcome. */
    public static void requestUninstall(Activity activity) {
        try {
            Intent uninstall = new Intent(Intent.ACTION_DELETE,
                    Uri.parse("package:" + OrbitLocalComponent.PACKAGE));
            activity.startActivity(uninstall);
        } catch (Throwable t) {
            Log.w(TAG, "uninstall request failed: " + t.getClass().getSimpleName());
        }
    }

    // ---- the installer cache ----------------------------------------------------------------------

    /**
     * How long an installer nobody used is kept before it counts as abandoned.
     *
     * <p>The same 48 hours the main updater applies to its own downloads, and for the same reason:
     * a cancelled install should not cost the user another 35 MB on the next attempt, but it also
     * should not sit in the cache forever.
     */
    private static final long ABANDONED_INSTALLER_MS = 48L * 60L * 60L * 1000L;

    /**
     * Removes every downloaded component installer. Used when Orbit Local is being removed outright.
     *
     * <p>Only ever touches {@code cache/orbit-local/}. The installed package belongs to Android and
     * the model belongs to the component; neither is reachable from here.
     */
    public static void cleanup(Context context) {
        File[] files = componentDirectory(context).listFiles();
        if (files == null) return;
        for (File file : files) safeDelete(file);
    }

    /**
     * Clears the installer cache once the component is confirmed installed.
     *
     * <p>Called only after {@link OrbitLocalComponent} has read the real package back from the
     * package manager, never because Orbit asked Android to install something. At that point the
     * downloaded APK is a pure duplicate of a package Android is already storing, so keeping it
     * would mean charging the user twice for the same 35 MB.
     */
    public static void cleanupAfterInstall(Context context) {
        cleanup(context);
    }

    /**
     * Drops component installers that are no longer worth keeping.
     *
     * <p>{@code cache/orbit-local/} holds temporary handoff files, not a history of every Beta.
     * The policy is deliberately narrow and deterministic:
     *
     * <ul>
     *   <li>{@code keep} is never touched — that is the file being handed to Android right now.
     *   <li>An APK named for any other Orbit version is obsolete the moment this build runs: Orbit
     *       only ever installs its own release's component. Removed.
     *   <li>A {@code .part} file is an interrupted download that will be started again from zero.
     *       Removed.
     *   <li>This version's own verified installer is kept, so Retry after a cancelled install costs
     *       nothing, until it passes {@link #ABANDONED_INSTALLER_MS} unused.
     * </ul>
     */
    public static void prune(Context context, File keep) {
        File[] files = componentDirectory(context).listFiles();
        if (files == null) return;
        String current = assetName();
        long now = System.currentTimeMillis();
        for (File file : files) {
            if (file == null || !file.isFile()) continue;
            if (keep != null && sameFile(file, keep)) continue;
            if (!current.equals(file.getName())) {
                // An old Beta's component, or a half-finished download of any version.
                safeDelete(file);
                continue;
            }
            if (now - file.lastModified() > ABANDONED_INSTALLER_MS) safeDelete(file);
        }
    }

    private static boolean sameFile(File a, File b) {
        try {
            return a.getCanonicalPath().equals(b.getCanonicalPath());
        } catch (Exception e) {
            return a.getAbsolutePath().equals(b.getAbsolutePath());
        }
    }

    static File componentDirectory(Context context) {
        File directory = new File(context.getCacheDir(), "orbit-local");
        if (!directory.exists() && !directory.mkdirs()) {
            Log.w(TAG, "Component storage could not be prepared");
        }
        return directory;
    }

    // ---- plumbing ---------------------------------------------------------------------------------

    private static String downloadUrl(String tag, String assetName) throws Exception {
        if (!OrbitVersion.isValidTag(tag) || !assetName.matches("^[A-Za-z0-9._-]+$")) {
            throw new InstallException("The Orbit Local asset reference is malformed.");
        }
        return RELEASE_DOWNLOAD_BASE + tag + "/" + assetName;
    }

    private static JSONObject readJson(String url, int limit) throws Exception {
        HttpURLConnection connection = open(url);
        try {
            int status = connection.getResponseCode();
            requireHttpsFinalUrl(connection);
            if (status < 200 || status >= 300) {
                throw new InstallException("The Orbit Local release information could not be read (HTTP "
                        + status + ").");
            }
            try (InputStream in = connection.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > limit) throw new InstallException("The release information is too large.");
                    out.write(buffer, 0, read);
                }
                return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String value) throws Exception {
        URL url = new URL(value);
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new InstallException("Orbit Local downloads require HTTPS.");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(45000);
        connection.setRequestProperty("Accept", "application/octet-stream, application/json");
        connection.setRequestProperty("User-Agent",
                "OrbitAssistant/" + BuildConfig.VERSION_NAME + " (Android component installer)");
        return connection;
    }

    private static void requireHttpsFinalUrl(HttpURLConnection connection) throws Exception {
        if (connection == null || connection.getURL() == null
                || !"https".equalsIgnoreCase(connection.getURL().getProtocol())) {
            throw new InstallException("The download redirected to an insecure URL.");
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(Locale.US, "%02X", value & 0xff));
        return out.toString();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace(":", "").replaceAll("\\s+", "").toUpperCase(Locale.US);
    }

    private static void safeDelete(File file) {
        if (file != null && file.exists() && !file.delete()) {
            Log.w(TAG, "Could not delete a component temporary file");
        }
    }

    static class InstallException extends Exception {
        InstallException(String message) { super(message); }
    }
}
