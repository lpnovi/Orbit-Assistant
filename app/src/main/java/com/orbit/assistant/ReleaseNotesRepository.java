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
    private static final String API =
            "https://api.github.com/repos/lpnovi/Orbit-Assistant/releases?per_page=10";
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

    private static List<ReleaseNote> fetch() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(API).openConnection();
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(15_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "Orbit-Assistant/" + BuildConfig.VERSION_NAME);
        try {
            if (connection.getResponseCode() != 200)
                throw new IllegalStateException("GitHub returned HTTP " + connection.getResponseCode());
            byte[] bytes;
            try (InputStream input = connection.getInputStream()) {
                bytes = readLimited(input, MAX_RESPONSE_BYTES);
            }
            return parse(new JSONArray(new String(bytes, StandardCharsets.UTF_8)));
        } finally {
            connection.disconnect();
        }
    }

    private static List<ReleaseNote> parse(JSONArray array) {
        List<ReleaseNote> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < array.length() && out.size() < MAX_RELEASES; i++) {
            JSONObject object = array.optJSONObject(i);
            if (object == null || object.optBoolean("draft", true) ||
                    object.optBoolean("prerelease", true)) continue;
            String tag = object.optString("tag_name", "").trim();
            Matcher matcher = TAG.matcher(tag);
            if (!matcher.matches() || !seen.add(tag)) continue;
            String body = object.optString("body", "");
            if (body.length() > MAX_BODY_LENGTH) body = body.substring(0, MAX_BODY_LENGTH);
            out.add(new ReleaseNote(tag, matcher.group(1), object.optString("name", ""), body,
                    object.optString("published_at", "")));
        }
        return out;
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
        Matcher matcher = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)$").matcher(version.trim());
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
