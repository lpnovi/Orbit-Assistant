package com.orbit.assistant;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** Public GitHub Release update discovery, download, and fail-closed APK verification. */
public final class OrbitUpdater {
    private static final String TAG = "OrbitUpdater";
    private static final String REPOSITORY = "lpnovi/Orbit-Assistant";
    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/" + REPOSITORY + "/releases/latest";
    private static final String RELEASE_DOWNLOAD_BASE =
            "https://github.com/" + REPOSITORY + "/releases/download/";
    private static final String UPDATE_MANIFEST_NAME = "orbit-update.json";
    private static final String PACKAGE_NAME = "com.orbit.assistant";
    private static final String CERTIFICATE_SHA256 =
            "7D:AD:61:93:85:DF:F1:1E:C7:31:AA:55:5F:2B:44:8A:94:3C:73:91:81:3D:1A:94:DF:1C:B4:23:2E:CD:41:E3";
    private static final int SCHEMA = 1;
    private static final int MAX_JSON_BYTES = 256 * 1024;
    private static final long MAX_APK_BYTES = 500L * 1024L * 1024L;
    private static final long ABANDONED_FILE_MS = 48L * 60L * 60L * 1000L;
    private static final long FOREGROUND_CHECK_SPACING_MS = 5L * 60L * 60L * 1000L;
    private static final Pattern VERSION_PATTERN = Pattern.compile("^[0-9]+(?:\\.[0-9]+)+$");
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();

    private static final String PREF_CACHED_RELEASE = "orbit_update_cached_release";
    private static final String PREF_LAST_CHECK_MS = "orbit_update_last_check_ms";
    private static final String PREF_LAST_FOREGROUND_CHECK_MS =
            "orbit_update_last_foreground_check_ms";
    private static final String PREF_NOTIFIED_CODE = "orbit_update_notified_code";
    private static final String PREF_PENDING_INSTALL_CODE = "orbit_update_pending_install_code";
    private static final String PREF_PENDING_INSTALL_FILE = "orbit_update_pending_install_file";

    private OrbitUpdater() {}

    public interface CheckCallback {
        void onResult(CheckResult result);
        void onError(String message);
    }

    public interface DownloadCallback {
        void onProgress(int percent);
        void onVerifying();
        void onReady(File apk);
        void onError(String message, boolean verificationFailure);
        void onCancelled();
    }

    public interface VerifyCallback {
        void onVerified();
        void onError(String message);
    }

    public static final class DownloadHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        public void cancel() { cancelled.set(true); }
        boolean isCancelled() { return cancelled.get(); }
    }

    public static final class CheckResult {
        public final boolean updateAvailable;
        public final Release release;

        CheckResult(boolean updateAvailable, Release release) {
            this.updateAvailable = updateAvailable;
            this.release = release;
        }
    }

    public static final class Release {
        public final String tag;
        public final String versionName;
        public final long versionCode;
        public final String apkAssetName;
        public final String apkSha256;
        public final String certificateSha256;
        public final long apkSize;
        public final String releaseNotes;

        Release(String tag, String versionName, long versionCode, String apkAssetName,
                String apkSha256, String certificateSha256, long apkSize, String releaseNotes) {
            this.tag = tag;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.apkAssetName = apkAssetName;
            this.apkSha256 = apkSha256;
            this.certificateSha256 = certificateSha256;
            this.apkSize = apkSize;
            this.releaseNotes = releaseNotes == null ? "" : releaseNotes;
        }

        JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("tag", tag)
                    .put("versionName", versionName)
                    .put("versionCode", versionCode)
                    .put("apkAssetName", apkAssetName)
                    .put("apkSha256", apkSha256)
                    .put("certificateSha256", certificateSha256)
                    .put("apkSize", apkSize)
                    .put("releaseNotes", releaseNotes);
        }
    }

    public static void checkAsync(Context context, CheckCallback callback) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try { callback.onResult(checkNow(app)); }
            catch (Exception e) {
                log("check_failed", diagnostic(e));
                callback.onError(userMessage(e, "Orbit could not check for updates."));
            }
        });
    }

    public static CheckResult checkNow(Context context) throws Exception {
        cleanupAbandonedDownloads(context, null);
        log("check_started", "latest_stable_release");
        JSONObject releaseJson = readJson(LATEST_RELEASE_API, MAX_JSON_BYTES);
        if (releaseJson.optBoolean("draft", true) || releaseJson.optBoolean("prerelease", true)) {
            throw new UpdateException("The latest GitHub release is not a stable published release.");
        }
        String tag = releaseJson.optString("tag_name", "").trim();
        if (!tag.matches("^v[0-9]+(?:\\.[0-9]+)+$")) {
            throw new UpdateException("The latest Orbit release tag is malformed.");
        }
        JSONArray assets = releaseJson.optJSONArray("assets");
        if (assets == null) throw new UpdateException("The latest Orbit release has no assets.");

        JSONObject manifestAsset = uniqueAsset(assets, UPDATE_MANIFEST_NAME);
        requireOfficialAssetUrl(manifestAsset, tag, UPDATE_MANIFEST_NAME);
        JSONObject manifest = readJson(downloadUrl(tag, UPDATE_MANIFEST_NAME), MAX_JSON_BYTES);
        Release parsed = validateManifestAndAssets(
                tag, releaseJson.optString("body", ""), manifest, assets);

        Prefs.get(context).edit().putLong(PREF_LAST_CHECK_MS, System.currentTimeMillis()).apply();
        if (parsed.versionCode > BuildConfig.VERSION_CODE) {
            Prefs.get(context).edit().putString(PREF_CACHED_RELEASE, parsed.toJson().toString()).apply();
            log("check_complete", "update_available_code_" + parsed.versionCode);
            return new CheckResult(true, parsed);
        }
        Prefs.get(context).edit().remove(PREF_CACHED_RELEASE).apply();
        log("check_complete", "up_to_date");
        return new CheckResult(false, parsed);
    }

    public static Release loadCachedAvailable(Context context) {
        String raw = Prefs.get(context).getString(PREF_CACHED_RELEASE, "");
        if (raw.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(raw);
            Release release = new Release(
                    o.getString("tag"), o.getString("versionName"), o.getLong("versionCode"),
                    o.getString("apkAssetName"), o.getString("apkSha256"),
                    o.getString("certificateSha256"), o.optLong("apkSize", -1L),
                    o.optString("releaseNotes", ""));
            validateReleaseFields(release);
            return release.versionCode > BuildConfig.VERSION_CODE ? release : null;
        } catch (Exception e) {
            Prefs.get(context).edit().remove(PREF_CACHED_RELEASE).apply();
            return null;
        }
    }

    public static long lastCheckMs(Context context) {
        return Prefs.get(context).getLong(PREF_LAST_CHECK_MS, 0L);
    }

    /** Atomically claims the lightweight five-hour companion-app launch check. */
    public static boolean claimForegroundCheck(Context context) {
        long now = System.currentTimeMillis();
        long last = Prefs.get(context).getLong(PREF_LAST_FOREGROUND_CHECK_MS, 0L);
        if (last > 0L && now >= last && now - last < FOREGROUND_CHECK_SPACING_MS) {
            return false;
        }
        return Prefs.get(context).edit()
                .putLong(PREF_LAST_FOREGROUND_CHECK_MS, now)
                .commit();
    }

    public static boolean wasNotified(Context context, long versionCode) {
        return Prefs.get(context).getLong(PREF_NOTIFIED_CODE, 0L) == versionCode;
    }

    public static void markNotified(Context context, long versionCode) {
        Prefs.get(context).edit().putLong(PREF_NOTIFIED_CODE, versionCode).apply();
    }

    public static DownloadHandle downloadAsync(Context context, Release release,
                                               DownloadCallback callback) {
        Context app = context.getApplicationContext();
        DownloadHandle handle = new DownloadHandle();
        EXECUTOR.execute(() -> download(app, release, handle, callback));
        return handle;
    }

    public static void verifyAsync(Context context, File apk, Release release,
                                   VerifyCallback callback) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                verifyDownloadedApk(app, apk, release);
                callback.onVerified();
            } catch (Exception e) {
                safeDelete(apk);
                log("verification_failed", diagnostic(e));
                callback.onError(userMessage(e, "The downloaded update could not be verified."));
            }
        });
    }

    public static void verifyDownloadedApk(Context context, File apk, Release release) throws Exception {
        validateReleaseFields(release);
        File root = updateDirectory(context).getCanonicalFile();
        File candidate = apk.getCanonicalFile();
        if (!candidate.getPath().startsWith(root.getPath() + File.separator) || !candidate.isFile()) {
            throw new VerificationException("The downloaded update file is unavailable.");
        }
        if (!normalizeSha256(sha256(candidate)).equals(normalizeSha256(release.apkSha256))) {
            throw new VerificationException("The downloaded APK checksum did not match the official release.");
        }

        PackageManager pm = context.getPackageManager();
        PackageInfo info = pm.getPackageArchiveInfo(
                candidate.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (info == null || info.signingInfo == null) {
            throw new VerificationException("Android could not inspect the downloaded APK.");
        }
        if (!PACKAGE_NAME.equals(info.packageName)) {
            throw new VerificationException("The downloaded APK has the wrong package name.");
        }
        if (info.getLongVersionCode() != release.versionCode ||
                !release.versionName.equals(info.versionName)) {
            throw new VerificationException("The downloaded APK version does not match the release manifest.");
        }
        if (info.getLongVersionCode() <= BuildConfig.VERSION_CODE) {
            throw new VerificationException("The downloaded APK is not newer than this Orbit installation.");
        }
        if (info.signingInfo.hasMultipleSigners()) {
            throw new VerificationException("The downloaded APK has an unexpected signer set.");
        }
        Signature[] signers = info.signingInfo.getApkContentsSigners();
        if (signers == null || signers.length != 1) {
            throw new VerificationException("The downloaded APK signer could not be verified.");
        }
        String certificate = hex(MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray()));
        if (!normalizeSha256(certificate).equals(normalizeSha256(CERTIFICATE_SHA256)) ||
                !normalizeSha256(release.certificateSha256).equals(normalizeSha256(CERTIFICATE_SHA256))) {
            throw new VerificationException("The downloaded APK is not signed by Orbit's permanent certificate.");
        }
        log("verification_complete", "package_version_certificate_valid");
    }

    public static boolean canRequestPackageInstalls(Context context) {
        return context.getPackageManager().canRequestPackageInstalls();
    }

    public static void openUnknownSourcesSettings(Activity activity) {
        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.getPackageName()));
        activity.startActivity(intent);
    }

    public static void launchPackageInstaller(Activity activity, File apk, Release release) throws Exception {
        validateReleaseFields(release);
        File root = updateDirectory(activity).getCanonicalFile();
        File candidate = apk.getCanonicalFile();
        if (!candidate.getPath().startsWith(root.getPath() + File.separator) || !candidate.isFile() ||
                !candidate.getName().equals(release.apkAssetName)) {
            throw new VerificationException("The verified update file is unavailable.");
        }
        Uri uri = FileProvider.getUriForFile(
                activity, activity.getPackageName() + ".fileprovider", candidate);
        Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        boolean saved = Prefs.get(activity).edit()
                .putLong(PREF_PENDING_INSTALL_CODE, release.versionCode)
                .putString(PREF_PENDING_INSTALL_FILE, candidate.getName())
                .commit();
        if (!saved) throw new UpdateException("Orbit could not prepare the update installer.");
        try {
            activity.startActivity(install);
        } catch (Exception e) {
            clearPendingInstall(activity);
            throw e;
        }
        log("installer_opened", "android_package_installer");
    }

    /** Cleans a verified installer only after Android reports Orbit at the target version or newer. */
    public static void reconcilePendingInstall(Context context) {
        long targetCode = Prefs.get(context).getLong(PREF_PENDING_INSTALL_CODE, 0L);
        String fileName = Prefs.get(context).getString(PREF_PENDING_INSTALL_FILE, "");
        if (targetCode <= 0L || !fileName.matches("^[A-Za-z0-9._-]+$") || fileName.endsWith(".part")) {
            clearPendingInstall(context);
            cleanupAbandonedDownloads(context, null);
            return;
        }
        File directory = updateDirectory(context);
        File installer = new File(directory, fileName);
        if (BuildConfig.VERSION_CODE >= targetCode) {
            safeDelete(installer);
            safeDelete(new File(directory, fileName + ".part"));
            clearPendingInstall(context);
            log("installed_update_cleanup", "target_code_" + targetCode);
        }
        cleanupAbandonedDownloads(context, null);
        if (!installer.exists()) clearPendingInstall(context);
    }

    private static void clearPendingInstall(Context context) {
        Prefs.get(context).edit()
                .remove(PREF_PENDING_INSTALL_CODE)
                .remove(PREF_PENDING_INSTALL_FILE)
                .apply();
    }

    public static void cleanupAbandonedDownloads(Context context, File keep) {
        File directory = updateDirectory(context);
        File[] files = directory.listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File file : files) {
            if (keep != null && file.equals(keep)) continue;
            if (file.getName().endsWith(".part") || now - file.lastModified() > ABANDONED_FILE_MS) {
                safeDelete(file);
            }
        }
    }

    private static void download(Context context, Release release, DownloadHandle handle,
                                 DownloadCallback callback) {
        File part = null;
        File output = null;
        HttpURLConnection connection = null;
        try {
            validateReleaseFields(release);
            cleanupAbandonedDownloads(context, null);
            File directory = updateDirectory(context);
            part = new File(directory, release.apkAssetName + ".part");
            output = new File(directory, release.apkAssetName);
            safeDelete(part);
            safeDelete(output);

            connection = open(downloadUrl(release.tag, release.apkAssetName), "application/vnd.android.package-archive");
            int status = connection.getResponseCode();
            requireHttpsFinalUrl(connection);
            if (status < 200 || status >= 300) {
                throw new UpdateException("The official APK download failed (HTTP " + status + ").");
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_APK_BYTES || release.apkSize > MAX_APK_BYTES) {
                throw new UpdateException("The official APK is unexpectedly large.");
            }
            if (release.apkSize > 0L && contentLength > 0L && release.apkSize != contentLength) {
                throw new VerificationException("The APK download size did not match the release metadata.");
            }

            long total = 0L;
            int lastProgress = -1;
            byte[] buffer = new byte[32 * 1024];
            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream out = new FileOutputStream(part)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (handle.isCancelled()) throw new CancelledException();
                    total += read;
                    if (total > MAX_APK_BYTES) throw new UpdateException("The APK download exceeded the safe size limit.");
                    out.write(buffer, 0, read);
                    long expected = release.apkSize > 0L ? release.apkSize : contentLength;
                    int progress = expected > 0L ? (int) Math.min(100L, total * 100L / expected) : -1;
                    if (progress != lastProgress) {
                        lastProgress = progress;
                        callback.onProgress(progress);
                    }
                }
                out.getFD().sync();
            }
            if (release.apkSize > 0L && total != release.apkSize) {
                throw new VerificationException("The APK download was incomplete.");
            }
            if (!part.renameTo(output)) throw new UpdateException("Orbit could not finalize the APK download.");
            callback.onVerifying();
            verifyDownloadedApk(context, output, release);
            callback.onReady(output);
        } catch (CancelledException e) {
            safeDelete(part);
            safeDelete(output);
            log("download_cancelled", "user_cancelled");
            callback.onCancelled();
        } catch (Exception e) {
            safeDelete(part);
            safeDelete(output);
            boolean verification = e instanceof VerificationException;
            log(verification ? "verification_failed" : "download_failed", diagnostic(e));
            callback.onError(userMessage(e, verification
                    ? "The downloaded update failed verification."
                    : "Orbit could not download the update."), verification);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static Release validateManifestAndAssets(String tag, String notes, JSONObject manifest,
                                                     JSONArray assets) throws Exception {
        if (manifest.optInt("schema", -1) != SCHEMA) {
            throw new UpdateException("The Orbit update manifest schema is unsupported.");
        }
        String packageName = manifest.optString("packageName", "");
        String versionName = manifest.optString("versionName", "").trim();
        long versionCode = manifest.optLong("versionCode", -1L);
        String apkAssetName = manifest.optString("apkAssetName", "").trim();
        String apkSha256 = manifest.optString("apkSha256", "").trim();
        String certificate = manifest.optString("certificateSha256", "").trim();
        Release release = new Release(tag, versionName, versionCode, apkAssetName,
                apkSha256, certificate, -1L, compactNotes(notes));
        if (!PACKAGE_NAME.equals(packageName)) {
            throw new UpdateException("The Orbit update manifest has the wrong package name.");
        }
        validateReleaseFields(release);
        JSONObject apkAsset = uniqueAsset(assets, apkAssetName);
        requireOfficialAssetUrl(apkAsset, tag, apkAssetName);
        long size = apkAsset.optLong("size", -1L);
        if (size <= 0L || size > MAX_APK_BYTES) {
            throw new UpdateException("The Orbit release APK size is invalid.");
        }
        return new Release(tag, versionName, versionCode, apkAssetName,
                apkSha256, certificate, size, compactNotes(notes));
    }

    private static void validateReleaseFields(Release release) throws Exception {
        if (release == null || !VERSION_PATTERN.matcher(release.versionName).matches() ||
                !release.tag.equals("v" + release.versionName) || release.versionCode <= 0L) {
            throw new UpdateException("The Orbit release version metadata is malformed.");
        }
        String expectedAsset = "Orbit-Assistant-v" + release.versionName + ".apk";
        if (!expectedAsset.equals(release.apkAssetName)) {
            throw new UpdateException("The Orbit release APK name is inconsistent.");
        }
        if (!SHA256_PATTERN.matcher(release.apkSha256).matches()) {
            throw new UpdateException("The Orbit release APK checksum is malformed.");
        }
        if (!normalizeSha256(release.certificateSha256)
                .equals(normalizeSha256(CERTIFICATE_SHA256))) {
            throw new UpdateException("The Orbit release certificate metadata is invalid.");
        }
    }

    private static JSONObject uniqueAsset(JSONArray assets, String name) throws Exception {
        JSONObject found = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset != null && name.equals(asset.optString("name", ""))) {
                if (found != null) throw new UpdateException("The Orbit release contains duplicate assets.");
                found = asset;
            }
        }
        if (found == null) throw new UpdateException("The Orbit release is missing " + name + ".");
        return found;
    }

    private static void requireOfficialAssetUrl(JSONObject asset, String tag, String name) throws Exception {
        if (!downloadUrl(tag, name).equals(asset.optString("browser_download_url", ""))) {
            throw new UpdateException("The Orbit release contains an unexpected asset URL.");
        }
    }

    private static JSONObject readJson(String url, int limit) throws Exception {
        HttpURLConnection connection = open(url, "application/vnd.github+json, application/json");
        try {
            int status = connection.getResponseCode();
            requireHttpsFinalUrl(connection);
            if (status < 200 || status >= 300) {
                throw new UpdateException("The update service returned HTTP " + status + ".");
            }
            byte[] bytes = readLimited(connection.getInputStream(), limit);
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String value, String accept) throws Exception {
        URL url = new URL(value);
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new UpdateException("Orbit updates require HTTPS.");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(45000);
        connection.setRequestProperty("Accept", accept);
        connection.setRequestProperty("User-Agent", "OrbitAssistant/" + BuildConfig.VERSION_NAME + " (Android updater)");
        return connection;
    }

    private static void requireHttpsFinalUrl(HttpURLConnection connection) throws Exception {
        if (connection == null || connection.getURL() == null ||
                !"https".equalsIgnoreCase(connection.getURL().getProtocol())) {
            throw new UpdateException("The update service redirected to an insecure URL.");
        }
    }

    private static String downloadUrl(String tag, String assetName) throws Exception {
        if (!tag.matches("^v[0-9]+(?:\\.[0-9]+)+$") ||
                !assetName.matches("^[A-Za-z0-9._-]+$")) {
            throw new UpdateException("The Orbit release asset reference is malformed.");
        }
        return RELEASE_DOWNLOAD_BASE + tag + "/" + assetName;
    }

    private static File updateDirectory(Context context) {
        File directory = new File(context.getCacheDir(), "updates");
        if (!directory.exists() && !directory.mkdirs()) {
            Log.w(TAG, "Update storage could not be prepared");
        }
        return directory;
    }

    private static byte[] readLimited(InputStream input, int limit) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > limit) throw new UpdateException("The update service response is too large.");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
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

    private static String normalizeSha256(String value) {
        return value == null ? "" : value.replace(":", "").replaceAll("\\s+", "")
                .toUpperCase(Locale.US);
    }

    private static String compactNotes(String notes) {
        String value = notes == null ? "" : notes.trim();
        return value.length() <= 6000 ? value : value.substring(0, 6000).trim() + "…";
    }

    private static String userMessage(Exception e, String fallback) {
        if (e instanceof UpdateException && e.getMessage() != null && !e.getMessage().isEmpty()) {
            return e.getMessage();
        }
        return fallback;
    }

    private static String diagnostic(Exception e) {
        if (e instanceof UpdateException && e.getMessage() != null) {
            return e.getClass().getSimpleName() + ":" + e.getMessage().replaceAll("[^A-Za-z0-9 _.-]", "");
        }
        return e == null ? "unknown" : e.getClass().getSimpleName();
    }

    private static void log(String stage, String detail) {
        Log.i(TAG, "stage=" + stage + " detail=" + detail);
    }

    private static void safeDelete(File file) {
        if (file != null && file.exists() && !file.delete()) {
            Log.w(TAG, "Could not delete an updater temporary file");
        }
    }

    private static class UpdateException extends Exception {
        UpdateException(String message) { super(message); }
    }

    private static final class VerificationException extends UpdateException {
        VerificationException(String message) { super(message); }
    }

    private static final class CancelledException extends Exception {}
}
