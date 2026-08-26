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

    /** Hands the verified APK to Android's own installer. The user still confirms it. */
    public static void launchInstaller(Activity activity, File apk) throws Exception {
        File root = componentDirectory(activity).getCanonicalFile();
        File candidate = apk.getCanonicalFile();
        if (!candidate.getPath().startsWith(root.getPath() + File.separator) || !candidate.isFile()) {
            throw new InstallException("The verified component file is unavailable.");
        }
        Uri uri = FileProvider.getUriForFile(
                activity, activity.getPackageName() + ".fileprovider", candidate);
        Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(install);
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

    /** Removes downloaded component installers once they are no longer needed. */
    public static void cleanup(Context context) {
        File[] files = componentDirectory(context).listFiles();
        if (files == null) return;
        for (File file : files) safeDelete(file);
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
