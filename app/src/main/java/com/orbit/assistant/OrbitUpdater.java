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
    /**
     * How many recent releases the Beta channel looks at.
     *
     * <p>Stable keeps GitHub's own {@code /releases/latest}, which already means "newest published
     * non-prerelease" and needs no scanning. Beta cannot use it, because that endpoint is defined
     * to skip prereleases, so it reads a bounded recent page instead — never the full history.
     */
    private static final int BETA_SCAN_PAGE_SIZE = 15;
    /**
     * How many of those releases may cost a manifest download.
     *
     * <p>The shortlist is ordered newest-version-first before any manifest is fetched, so the
     * answer is almost always the first entry. This cap keeps a repository full of old releases
     * from turning one update check into a dozen requests.
     */
    private static final int MAX_MANIFEST_FETCHES = 4;
    private static final String RELEASES_LIST_API =
            "https://api.github.com/repos/" + REPOSITORY + "/releases?per_page=" + BETA_SCAN_PAGE_SIZE;
    private static final String RELEASE_DOWNLOAD_BASE =
            "https://github.com/" + REPOSITORY + "/releases/download/";
    private static final String UPDATE_MANIFEST_NAME = "orbit-update.json";
    private static final String PACKAGE_NAME = "com.orbit.assistant";
    private static final String CERTIFICATE_SHA256 =
            "7D:AD:61:93:85:DF:F1:1E:C7:31:AA:55:5F:2B:44:8A:94:3C:73:91:81:3D:1A:94:DF:1C:B4:23:2E:CD:41:E3";
    private static final int SCHEMA = 1;
    private static final int MAX_JSON_BYTES = 256 * 1024;
    /**
     * A page of full release objects is far larger than a single one, so it gets its own ceiling.
     * Still a hard bound — the response is read into memory and must never be unlimited.
     */
    private static final int MAX_RELEASE_LIST_BYTES = 1024 * 1024;
    private static final long MAX_APK_BYTES = 500L * 1024L * 1024L;
    private static final long ABANDONED_FILE_MS = 48L * 60L * 60L * 1000L;
    private static final long FOREGROUND_CHECK_SPACING_MS = 5L * 60L * 60L * 1000L;
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();

    private static final String PREF_CACHED_RELEASE = "orbit_update_cached_release";
    private static final String PREF_LAST_CHECK_MS = "orbit_update_last_check_ms";
    private static final String PREF_LAST_FOREGROUND_CHECK_MS =
            "orbit_update_last_foreground_check_ms";
    private static final String PREF_NOTIFIED_CODE = "orbit_update_notified_code";
    private static final String PREF_PENDING_INSTALL_CODE = "orbit_update_pending_install_code";
    /** Version an Orbit-verified update reached, awaiting one acknowledgement in the main app. */
    private static final String PREF_POST_UPDATE_VERSION = "orbit_post_update_version";
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

        /** Whether this release is a Beta prerelease, read from its own version. */
        public boolean isBeta() {
            return OrbitVersion.isBeta(versionName);
        }

        /** "0.7.7.5" or "0.7.7.5 Beta 1". */
        public String displayName() {
            return OrbitVersion.displayName(versionName);
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
        return checkNow(context, Prefs.updateChannel(context));
    }

    /**
     * One update check, in one channel.
     *
     * <p>Stable is unchanged from every Orbit before this one: GitHub's own {@code
     * /releases/latest}, which by definition returns the newest published non-prerelease. Beta adds
     * a bounded scan of recent releases so it can see prereleases too, and then picks whichever
     * eligible build carries the greatest {@code versionCode} — which is frequently a finished
     * Stable release rather than another Beta.
     */
    static CheckResult checkNow(Context context, String channel) throws Exception {
        cleanupAbandonedDownloads(context, null);
        String resolved = Prefs.normalizeChannel(channel);
        Release parsed = Prefs.CHANNEL_BETA.equals(resolved)
                ? findBestBetaChannelRelease()
                : findLatestStableRelease();

        Prefs.get(context).edit().putLong(PREF_LAST_CHECK_MS, System.currentTimeMillis()).apply();
        if (parsed != null && parsed.versionCode > BuildConfig.VERSION_CODE) {
            Prefs.get(context).edit()
                    .putString(PREF_CACHED_RELEASE,
                            parsed.toJson().put("channel", resolved).toString())
                    .apply();
            log("check_complete", "update_available_code_" + parsed.versionCode
                    + "_channel_" + resolved);
            return new CheckResult(true, parsed);
        }
        Prefs.get(context).edit().remove(PREF_CACHED_RELEASE).apply();
        log("check_complete", "up_to_date_channel_" + resolved);
        return new CheckResult(false, parsed);
    }

    /** The Stable path, byte for byte the behaviour Orbit has always had. */
    private static Release findLatestStableRelease() throws Exception {
        log("check_started", "latest_stable_release");
        JSONObject releaseJson = readJson(LATEST_RELEASE_API, MAX_JSON_BYTES);
        if (releaseJson.optBoolean("draft", true) || releaseJson.optBoolean("prerelease", true)) {
            throw new UpdateException("The latest GitHub release is not a stable published release.");
        }
        String tag = releaseJson.optString("tag_name", "").trim();
        if (!OrbitVersion.isStableTag(tag)) {
            throw new UpdateException("The latest Orbit release tag is malformed.");
        }
        if (releaseJson.optJSONArray("assets") == null) {
            throw new UpdateException("The latest Orbit release has no assets.");
        }
        JSONObject manifest = fetchManifest(releaseJson, tag);
        return evaluateCandidate(releaseJson, manifest, Prefs.CHANNEL_STABLE);
    }

    /**
     * The Beta path: the highest-versionCode eligible release among a bounded recent page.
     *
     * <p>Each candidate is validated exactly as strictly as a Stable one. A malformed, unofficial,
     * or mislabelled release is skipped rather than accepted, and if nothing validates this returns
     * null so the caller can say Orbit is already on the newest build it can see.
     */
    private static Release findBestBetaChannelRelease() throws Exception {
        log("check_started", "beta_channel_release_scan");
        JSONArray releases = readJsonArray(RELEASES_LIST_API, MAX_RELEASE_LIST_BYTES);
        java.util.List<JSONObject> shortlist = shortlist(releases, Prefs.CHANNEL_BETA);

        java.util.List<Release> valid = new java.util.ArrayList<>();
        int fetches = 0;
        for (JSONObject release : shortlist) {
            if (fetches >= MAX_MANIFEST_FETCHES) break;
            fetches++;
            String tag = release.optString("tag_name", "").trim();
            try {
                JSONObject manifest = fetchManifest(release, tag);
                valid.add(evaluateCandidate(release, manifest, Prefs.CHANNEL_BETA));
            } catch (Exception e) {
                // A malformed or unofficial release is skipped, never accepted best-effort.
                log("candidate_rejected", diagnostic(e));
            }
        }
        return bestByVersionCode(valid);
    }

    /**
     * The releases worth spending a manifest download on, highest version first.
     *
     * <p>The ordering is only a search heuristic so the bounded budget is spent well. What is
     * actually installed is still decided by {@code versionCode} in {@link #bestByVersionCode}.
     */
    static java.util.List<JSONObject> shortlist(JSONArray releases, String channel) {
        java.util.List<JSONObject> out = new java.util.ArrayList<>();
        if (releases == null) return out;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.optJSONObject(i);
            if (structurallyEligible(release, channel)) out.add(release);
        }
        java.util.Collections.sort(out, (a, b) -> OrbitVersion.compareVersions(
                OrbitVersion.versionFromTag(b.optString("tag_name", "").trim()),
                OrbitVersion.versionFromTag(a.optString("tag_name", "").trim())));
        return out;
    }

    /**
     * The winner among validated candidates: the greatest {@code versionCode}, and nothing else.
     *
     * <p>Version <em>names</em> never decide this. A Beta user offered both {@code 0.7.7.5} and
     * {@code 0.7.7.6-beta.1} takes whichever Android would actually treat as newer.
     */
    static Release bestByVersionCode(java.util.List<Release> candidates) {
        Release best = null;
        if (candidates == null) return null;
        for (Release candidate : candidates) {
            if (candidate == null) continue;
            if (best == null || candidate.versionCode > best.versionCode) best = candidate;
        }
        return best;
    }

    /**
     * The checks that need no network, so an obviously ineligible release costs nothing.
     *
     * <p>The important one is agreement: a {@code -beta.N} tag must be published as a GitHub
     * prerelease, and a plain version tag must not be. A Beta tag released as a normal Stable
     * release, or a Stable tag flagged as a prerelease, is a mislabelled release and Orbit refuses
     * it in both channels rather than guessing which half was intended.
     */
    static boolean structurallyEligible(JSONObject release, String channel) {
        if (release == null) return false;
        if (release.optBoolean("draft", true)) return false;
        String tag = release.optString("tag_name", "").trim();
        if (!OrbitVersion.isValidTag(tag)) return false;
        boolean prerelease = release.optBoolean("prerelease", true);
        if (OrbitVersion.isBetaTag(tag) != prerelease) return false;
        // Stable never accepts a prerelease, whatever its tag claims.
        if (!Prefs.CHANNEL_BETA.equals(Prefs.normalizeChannel(channel)) && prerelease) return false;
        return release.optJSONArray("assets") != null;
    }

    /**
     * Full validation of one release against its manifest. Throws for anything that does not match.
     *
     * <p>Separated from the network so the rules can be exercised exactly, and so both channels are
     * provably running the same ones.
     */
    static Release evaluateCandidate(JSONObject release, JSONObject manifest, String channel)
            throws Exception {
        if (!structurallyEligible(release, channel)) {
            throw new UpdateException("The Orbit release is not an eligible published release.");
        }
        String tag = release.optString("tag_name", "").trim();
        JSONArray assets = release.optJSONArray("assets");
        JSONObject manifestAsset = uniqueAsset(assets, UPDATE_MANIFEST_NAME);
        requireOfficialAssetUrl(manifestAsset, tag, UPDATE_MANIFEST_NAME);
        return validateManifestAndAssets(tag, release.optString("body", ""), manifest, assets);
    }

    private static JSONObject fetchManifest(JSONObject release, String tag) throws Exception {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) throw new UpdateException("The Orbit release has no assets.");
        JSONObject manifestAsset = uniqueAsset(assets, UPDATE_MANIFEST_NAME);
        requireOfficialAssetUrl(manifestAsset, tag, UPDATE_MANIFEST_NAME);
        return readJson(downloadUrl(tag, UPDATE_MANIFEST_NAME), MAX_JSON_BYTES);
    }

    /**
     * Forgets everything the previous channel decided, without disturbing an install in flight.
     *
     * <p>A cached Beta candidate must not survive a switch to Stable, and Stable's
     * already-notified bookkeeping must not silence the first Beta notification. Both are update
     * <em>discovery</em> state, so both are dropped. The pending-installer record is deliberately
     * left alone: if a verified APK is already on its way to Android's installer, changing a
     * preference must not orphan it.
     */
    public static void onChannelChanged(Context context) {
        if (context == null) return;
        Prefs.get(context).edit()
                .remove(PREF_CACHED_RELEASE)
                .remove(PREF_NOTIFIED_CODE)
                .remove(PREF_LAST_CHECK_MS)
                .remove(PREF_LAST_FOREGROUND_CHECK_MS)
                .commit();
        OrbitUpdateNotifier.cancel(context);
        log("channel_changed", Prefs.updateChannel(context));
    }

    public static Release loadCachedAvailable(Context context) {
        String raw = Prefs.get(context).getString(PREF_CACHED_RELEASE, "");
        if (raw.isEmpty()) return null;
        boolean beta = Prefs.betaChannel(context);
        try {
            JSONObject o = new JSONObject(raw);
            // A candidate found in the other channel is not evidence about this one.
            if (!Prefs.updateChannel(context)
                    .equals(Prefs.normalizeChannel(o.optString("channel", Prefs.CHANNEL_STABLE)))) {
                Prefs.get(context).edit().remove(PREF_CACHED_RELEASE).apply();
                return null;
            }
            Release release = new Release(
                    o.getString("tag"), o.getString("versionName"), o.getLong("versionCode"),
                    o.getString("apkAssetName"), o.getString("apkSha256"),
                    o.getString("certificateSha256"), o.optLong("apkSize", -1L),
                    o.optString("releaseNotes", ""));
            validateReleaseFields(release);
            // Belt and braces: Stable can never surface a Beta build, whatever the cache says.
            if (release.isBeta() && !beta) {
                Prefs.get(context).edit().remove(PREF_CACHED_RELEASE).apply();
                return null;
            }
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
            // Reaching the version an Orbit-verified install was aiming for is the authoritative
            // proof that update actually happened. Recorded before the pending state is cleared so
            // the companion app can acknowledge it once; a cancelled or failed install never gets
            // here, and neither does a fresh install.
            Prefs.get(context).edit()
                    .putString(PREF_POST_UPDATE_VERSION, BuildConfig.VERSION_NAME)
                    .commit();
            safeDelete(installer);
            safeDelete(new File(directory, fileName + ".part"));
            clearPendingInstall(context);
            log("installed_update_cleanup", "target_code_" + targetCode);
        }
        cleanupAbandonedDownloads(context, null);
        if (!installer.exists()) clearPendingInstall(context);
    }

    /**
     * Version installed by a verified Orbit update that has not been acknowledged yet, or "".
     * Only the companion app consumes this; the Side-button overlay leaves it pending.
     */
    public static String pendingPostUpdateVersion(Context context) {
        if (context == null) return "";
        return Prefs.get(context).getString(PREF_POST_UPDATE_VERSION, "").trim();
    }

    /** Acknowledges the update once, so the prompt cannot appear again for that version. */
    public static void clearPostUpdateVersion(Context context) {
        if (context == null) return;
        Prefs.get(context).edit().remove(PREF_POST_UPDATE_VERSION).commit();
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
        // Stable and Beta are both accepted here, and both must be exactly well-formed. A tag such
        // as v0.7.7.5-beta.0 or v0.7.7.5-test is not an Orbit version at all, so it never reaches
        // the point of having a download URL built for it.
        if (release == null || !OrbitVersion.isValid(release.versionName) ||
                !release.tag.equals(OrbitVersion.tagFor(release.versionName)) ||
                release.versionCode <= 0L) {
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
        return new JSONObject(readJsonText(url, limit));
    }

    /** The releases collection endpoint answers with an array rather than an object. */
    private static JSONArray readJsonArray(String url, int limit) throws Exception {
        return new JSONArray(readJsonText(url, limit));
    }

    private static String readJsonText(String url, int limit) throws Exception {
        HttpURLConnection connection = open(url, "application/vnd.github+json, application/json");
        try {
            int status = connection.getResponseCode();
            requireHttpsFinalUrl(connection);
            if (status < 200 || status >= 300) {
                throw new UpdateException("The update service returned HTTP " + status + ".");
            }
            byte[] bytes = readLimited(connection.getInputStream(), limit);
            return new String(bytes, StandardCharsets.UTF_8);
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
        if (!OrbitVersion.isValidTag(tag) || !assetName.matches("^[A-Za-z0-9._-]+$")) {
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
