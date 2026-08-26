package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Public GitHub stable-release notes with a private, last-known-good offline cache. */
public final class ReleaseNotesRepository {
    private static final String REPO = "https://api.github.com/repos/lpnovi/Orbit-Assistant";
    private static final String API_LATEST = REPO + "/releases/latest";
    private static final String API_TAGS = REPO + "/tags?per_page=15";
    private static final String API_BY_TAG = REPO + "/releases/tags/";
    /** Bounded so this stays a small lookup rather than a crawl of the repository. */
    private static final int MAX_CANDIDATE_TAGS = 7;
    private static final String FILE = "orbit_release_notes_cache";
    private static final String KEY_RELEASES = "stable_releases_v1";
    private static final String KEY_FETCHED_AT = "fetched_at";
    private static final int MAX_RELEASES = 5;
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private static final int MAX_BODY_LENGTH = 40_000;
    private static final Pattern TAG = Pattern.compile("^v(\\d+\\.\\d+\\.\\d+\\.\\d+)$");
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    public static final class ReleaseNote {
        public final String tag;
        public final String versionName;
        public final String title;
        public final String body;
        public final String publishedAt;

        ReleaseNote(String tag, String versionName, String title, String body, String publishedAt) {
            this.tag = tag;
            this.versionName = versionName;
            this.title = title == null || title.trim().isEmpty()
                    ? "Orbit Assistant " + tag : title.trim();
            this.body = body == null ? "" : body.trim();
            this.publishedAt = publishedAt == null ? "" : publishedAt.trim();
        }
    }

    public interface Callback {
        void onLoaded(List<ReleaseNote> releases, boolean fromCache);
        void onError(String message);
    }

    private ReleaseNotesRepository() {}

    public static void load(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                List<ReleaseNote> fresh = fetch();
                if (fresh.isEmpty()) throw new IllegalStateException("No stable Orbit releases were returned.");
                save(app, fresh);
                callback.onLoaded(fresh, false);
            } catch (Exception ignored) {
                List<ReleaseNote> cached = loadCached(app);
                if (!cached.isEmpty()) callback.onLoaded(cached, true);
                else callback.onError("Orbit could not load release notes. Check your connection and try again.");
            }
        });
    }

    static boolean isNewerThanCurrent(String versionName) {
        int[] candidate = parseVersion(versionName);
        int[] current = parseVersion(BuildConfig.VERSION_NAME);
        if (candidate == null || current == null) return false;
        for (int i = 0; i < 4; i++) {
            if (candidate[i] != current[i]) return candidate[i] > current[i];
        }
        return false;
    }

    /**
     * Builds the list from the authoritative latest release plus recent tags, then orders it here.
     *
     * <p>The collection endpoint cannot be trusted for ordering: it returns Orbit's tags
     * lexicographically, so v0.7.2.15 sorts below v0.7.2.2 and taking the first few entries pinned
     * this screen at v0.7.2.9 indefinitely. Each candidate tag is confirmed to be a genuine
     * published Release, and Orbit sorts the result by version rather than trusting array order.
     */
    private static List<ReleaseNote> fetch() throws Exception {
        List<String> candidates = new ArrayList<>();
        Set<String> seenTags = new HashSet<>();

        String latest = latestReleaseTag();
        if (latest != null && seenTags.add(latest)) candidates.add(latest);
        for (String tag : recentTags()) {
            if (candidates.size() >= MAX_CANDIDATE_TAGS) break;
            if (seenTags.add(tag)) candidates.add(tag);
        }
        // Newest first, so a bounded lookup always covers the most recent releases.
        candidates.sort((a, b) -> compareTags(b, a));

        List<ReleaseNote> found = new ArrayList<>();
        for (String tag : candidates) {
            if (found.size() >= MAX_RELEASES) break;
            ReleaseNote note = releaseForTag(tag);
            // One unavailable candidate must not discard the others that did resolve.
            if (note != null) found.add(note);
        }
        found.sort((a, b) -> compareVersions(b.versionName, a.versionName));
        return found;
    }

    private static String latestReleaseTag() {
        try {
            JSONObject object = new JSONObject(getJson(API_LATEST));
            if (object.optBoolean("draft", true) || object.optBoolean("prerelease", true)) return null;
            String tag = object.optString("tag_name", "").trim();
            return TAG.matcher(tag).matches() ? tag : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<String> recentTags() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(getJson(API_TAGS));
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                String tag = object.optString("name", "").trim();
                if (TAG.matcher(tag).matches()) out.add(tag);
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** A published, non-draft, non-prerelease Release for this tag, or null. */
    private static ReleaseNote releaseForTag(String tag) {
        try {
            JSONObject object = new JSONObject(getJson(API_BY_TAG + tag));
            return parseRelease(object);
        } catch (Exception ignored) {
            return null;
        }
    }

    static ReleaseNote parseRelease(JSONObject object) {
        if (object == null) return null;
        if (object.optBoolean("draft", true) || object.optBoolean("prerelease", true)) return null;
        String tag = object.optString("tag_name", "").trim();
        Matcher matcher = TAG.matcher(tag);
        if (!matcher.matches()) return null;
        String body = object.optString("body", "");
        if (body.length() > MAX_BODY_LENGTH) body = body.substring(0, MAX_BODY_LENGTH);
        // Title, date, and notes all come from the real Release rather than from a tag.
        return new ReleaseNote(tag, matcher.group(1), object.optString("name", ""), body,
                object.optString("published_at", ""));
    }

    private static String getJson(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(15_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "Orbit-Assistant/" + BuildConfig.VERSION_NAME);
        try {
            if (connection.getResponseCode() != 200)
                throw new IllegalStateException("GitHub returned HTTP " + connection.getResponseCode());
            try (InputStream input = connection.getInputStream()) {
                return new String(readLimited(input, MAX_RESPONSE_BYTES), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static int compareTags(String a, String b) {
        return compareVersions(versionOfTag(a), versionOfTag(b));
    }

    private static String versionOfTag(String tag) {
        Matcher matcher = TAG.matcher(tag == null ? "" : tag);
        return matcher.matches() ? matcher.group(1) : "";
    }

    /** Four-part numeric comparison, so 0.7.2.10 correctly ranks above 0.7.2.9. */
    static int compareVersions(String left, String right) {
        int[] a = parseVersion(left);
        int[] b = parseVersion(right);
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        for (int i = 0; i < 4; i++) {
            if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
        }
        return 0;
    }

    private static void save(Context context, List<ReleaseNote> releases) {
        JSONArray array = new JSONArray();
        try {
            for (ReleaseNote note : releases) {
                array.put(new JSONObject()
                        .put("tag", note.tag)
                        .put("versionName", note.versionName)
                        .put("title", note.title)
                        .put("body", note.body)
                        .put("publishedAt", note.publishedAt));
            }
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                    .putString(KEY_RELEASES, array.toString())
                    .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
                    .commit();
        } catch (Exception ignored) {}
    }

    private static List<ReleaseNote> loadCached(Context context) {
        List<ReleaseNote> out = new ArrayList<>();
        SharedPreferences prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_RELEASES, "[]"));
            for (int i = 0; i < array.length() && out.size() < MAX_RELEASES; i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                String tag = object.optString("tag", "").trim();
                Matcher matcher = TAG.matcher(tag);
                if (!matcher.matches()) continue;
                out.add(new ReleaseNote(tag, matcher.group(1), object.optString("title", ""),
                        object.optString("body", ""), object.optString("publishedAt", "")));
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static int[] parseVersion(String version) {
        if (version == null) return null;
        // What's New lists Stable releases, but Orbit itself may be a Beta build. Comparing against
        // the Beta's base version is what makes "0.7.7.5 Beta 2" correctly regard the finished
        // 0.7.7.5 notes as not newer, instead of failing to parse and hiding the whole screen.
        String value = OrbitVersion.isBeta(version.trim())
                ? OrbitVersion.baseVersion(version.trim()) : version.trim();
        Matcher matcher = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)$").matcher(value);
        if (!matcher.matches()) return null;
        try {
            return new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(4))};
        } catch (Exception ignored) { return null; }
    }

    private static byte[] readLimited(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw new IllegalStateException("Release notes response is too large.");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
