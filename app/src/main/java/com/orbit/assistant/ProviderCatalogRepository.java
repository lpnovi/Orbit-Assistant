package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Account-scoped provider catalog discovery with a bounded last-known-good offline cache. */
public final class ProviderCatalogRepository {
    private static final String FILE = "orbit_model_catalog_cache";
    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/models?limit=100";
    private static final String XAI_URL = "https://api.x.ai/v1/language-models";
    private static final int MAX_MODELS = 80;
    private static final long STALE_MS = 24L * 60L * 60L * 1000L;
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Set<String> REFRESHING = new HashSet<>();

    public interface Callback { void onFinished(boolean changed, String error); }

    private ProviderCatalogRepository() {}

    /** Loads last-known-good metadata without network access. Safe to call repeatedly. */
    public static void loadCached(Context c) {
        install(c, Prefs.PROVIDER_ANTHROPIC, readCache(c, Prefs.PROVIDER_ANTHROPIC));
        install(c, Prefs.PROVIDER_XAI, readCache(c, Prefs.PROVIDER_XAI));
    }

    public static void refreshAsync(Context c, String provider, Callback callback) {
        Context app = c.getApplicationContext();
        EXEC.execute(() -> {
            String error = "";
            boolean changed = false;
            try {
                List<AiModelSpec> models;
                if (Prefs.PROVIDER_ANTHROPIC.equals(provider)) {
                    String key = SecureStore.loadAnthropicKey(app);
                    if (key.isEmpty()) throw new CatalogException("Add an Anthropic API key first.");
                    models = parseAnthropic(get(ANTHROPIC_URL, "x-api-key", key, true));
                } else if (Prefs.PROVIDER_XAI.equals(provider)) {
                    String key = SecureStore.loadXaiKey(app);
                    if (key.isEmpty()) throw new CatalogException("Add an xAI API key first.");
                    models = parseXai(get(XAI_URL, "Authorization", "Bearer " + key, false));
                } else {
                    throw new CatalogException("This provider has no dynamic catalog.");
                }
                if (models.isEmpty()) throw new CatalogException("The provider returned no compatible chat models.");
                // A successful refresh may legitimately remove a model. Keep its last-known facts
                // as an unavailable historical entry so chats, Favorites, and Response Details
                // retain the exact model rather than silently changing to the new first item.
                retainMissing(models, readCache(app, provider));
                String before = prefs(app).getString(provider, "");
                String encoded = encode(models).toString();
                prefs(app).edit().putString(provider, encoded).putLong(provider + "_at",
                        System.currentTimeMillis()).apply();
                OrbitModelCatalog.installDynamic(provider, models);
                changed = !encoded.equals(before);
            } catch (CatalogException e) {
                error = e.getMessage();
            } catch (Exception e) {
                error = "Could not refresh models. Check the connection and try again.";
            }
            if (callback != null) callback.onFinished(changed, error);
        });
    }

    /** Refreshes configured account catalogs in the background at most once per day. */
    public static void refreshIfStale(Context c, String provider, Callback callback) {
        boolean configured = Prefs.PROVIDER_ANTHROPIC.equals(provider)
                ? SecureStore.hasAnthropicKey(c)
                : Prefs.PROVIDER_XAI.equals(provider) && SecureStore.hasXaiKey(c);
        if (!configured) return;
        long refreshedAt = prefs(c).getLong(provider + "_at", 0L);
        if (System.currentTimeMillis() - refreshedAt < STALE_MS) return;
        synchronized (REFRESHING) {
            if (!REFRESHING.add(provider)) return;
        }
        refreshAsync(c, provider, (changed, error) -> {
            synchronized (REFRESHING) { REFRESHING.remove(provider); }
            if (callback != null) callback.onFinished(changed, error);
        });
    }

    static List<AiModelSpec> parseAnthropic(String raw) throws Exception {
        JSONArray data = new JSONObject(raw).optJSONArray("data");
        List<AiModelSpec> out = new ArrayList<>();
        if (data == null) return out;
        for (int i = 0; i < data.length() && out.size() < MAX_MODELS; i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id", "").trim();
            if (!id.startsWith("claude-") || id.contains("embedding")) continue;
            String name = item.optString("display_name", id).trim();
            int context = nonNegative(item.optLong("max_input_tokens", 0L));
            JSONObject caps = item.optJSONObject("capabilities");
            boolean vision = supported(caps, "image_input");
            boolean pdf = supported(caps, "pdf_input");
            List<AiStrength> efforts = efforts(caps == null ? null : caps.optJSONObject("effort"));
            AiStrength def = defaultEffort(id, efforts);
            out.add(new AiModelSpec(id, name, family(name, "Claude"), Prefs.PROVIDER_ANTHROPIC,
                    "Available to this Anthropic API key", def, true, context,
                    vision, pdf, true, supported(caps, "tool_use"),
                    supported(caps, "web_search"), true, "dynamic_provider", "active",
                    efforts.toArray(new AiStrength[0])));
        }
        sort(out, Prefs.PROVIDER_ANTHROPIC);
        return out;
    }

    static List<AiModelSpec> parseXai(String raw) throws Exception {
        JSONArray data = new JSONObject(raw).optJSONArray("models");
        List<AiModelSpec> out = new ArrayList<>();
        if (data == null) return out;
        for (int i = 0; i < data.length() && out.size() < MAX_MODELS; i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id", "").trim();
            if (!id.toLowerCase(Locale.US).startsWith("grok-") || id.contains("image-generation")
                    || id.contains("imagine") || id.contains("embedding")) continue;
            JSONArray input = item.optJSONArray("input_modalities");
            JSONArray output = item.optJSONArray("output_modalities");
            if (!contains(input, "text") || !contains(output, "text")) continue;
            boolean vision = contains(input, "image");
            int context = nonNegative(item.optLong("context_length", 0L));
            JSONObject caps = item.optJSONObject("capabilities");
            List<AiStrength> efforts = new ArrayList<>();
            JSONArray values = caps == null ? null : caps.optJSONArray("reasoning_effort");
            if (values != null) for (int j = 0; j < values.length(); j++) {
                AiStrength strength = AiStrength.fromId(values.optString(j));
                if (strength != null && !efforts.contains(strength)) efforts.add(strength);
            }
            Collections.sort(efforts);
            AiStrength def = AiStrength.fromId(caps == null ? "" : caps.optString("default_reasoning_effort"));
            if (def == null && !efforts.isEmpty()) def = efforts.contains(AiStrength.HIGH)
                    ? AiStrength.HIGH : efforts.get(0);
            boolean tools = supported(caps, "tool_calling") || supported(caps, "tools");
            boolean web = supported(caps, "web_search") || supported(caps, "search");
            out.add(new AiModelSpec(id, displayXai(id), family(displayXai(id), "Grok"),
                    Prefs.PROVIDER_XAI, "Available to this xAI API key", def, true, context,
                    vision, false, true, tools, web, true, "dynamic_provider", "active",
                    efforts.toArray(new AiStrength[0])));
        }
        sort(out, Prefs.PROVIDER_XAI);
        return out;
    }

    private static String get(String endpoint, String authHeader, String authValue,
                              boolean anthropic) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty(authHeader, authValue);
            if (anthropic) conn.setRequestProperty("anthropic-version", "2023-06-01");
            int code = conn.getResponseCode();
            String body = read(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
            if (code == 401 || code == 403) throw new CatalogException("The saved API key was rejected.");
            if (code == 429) throw new CatalogException("The provider rate limit was reached. Try again later.");
            if (code < 200 || code >= 300) throw new CatalogException("The provider could not refresh its model list.");
            return body;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static JSONArray encode(List<AiModelSpec> models) throws Exception {
        JSONArray out = new JSONArray();
        for (AiModelSpec m : models) {
            JSONArray strengths = new JSONArray();
            for (AiStrength s : m.strengths) strengths.put(s.id);
            out.put(new JSONObject().put("id", m.id).put("name", m.displayName)
                    .put("family", m.familyLabel).put("description", m.descriptor)
                    .put("context", m.contextWindowTokens).put("vision", m.vision)
                    .put("nativeFiles", m.nativeFiles).put("tools", m.tools)
                    .put("web", m.webSearch).put("availability", m.availability)
                    .put("strengths", strengths)
                    .put("default", m.defaultStrength == null ? "" : m.defaultStrength.id));
        }
        return out;
    }

    private static List<AiModelSpec> readCache(Context c, String provider) {
        List<AiModelSpec> out = new ArrayList<>();
        try {
            JSONArray data = new JSONArray(prefs(c).getString(provider, "[]"));
            for (int i = 0; i < data.length() && out.size() < MAX_MODELS; i++) {
                JSONObject item = data.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "").trim();
                if (id.isEmpty()) continue;
                List<AiStrength> strengths = new ArrayList<>();
                JSONArray s = item.optJSONArray("strengths");
                if (s != null) for (int j = 0; j < s.length(); j++) {
                    AiStrength parsed = AiStrength.fromId(s.optString(j));
                    if (parsed != null && !strengths.contains(parsed)) strengths.add(parsed);
                }
                Collections.sort(strengths);
                AiStrength def = AiStrength.fromId(item.optString("default", ""));
                out.add(new AiModelSpec(id, item.optString("name", id),
                        item.optString("family", ""), provider,
                        item.optString("description", "Previously available"), def, true,
                        nonNegative(item.optLong("context", 0L)), item.optBoolean("vision"),
                        item.optBoolean("nativeFiles"), true, item.optBoolean("tools"),
                        item.optBoolean("web"), true, "dynamic_provider",
                        item.optString("availability", "active"),
                        strengths.toArray(new AiStrength[0])));
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static void install(Context c, String provider, List<AiModelSpec> models) {
        if (!models.isEmpty()) OrbitModelCatalog.installDynamic(provider, models);
    }

    private static void retainMissing(List<AiModelSpec> fresh, List<AiModelSpec> previous) {
        for (AiModelSpec old : previous) {
            boolean found = false;
            for (AiModelSpec current : fresh) if (current.id.equals(old.id)) { found = true; break; }
            if (found || fresh.size() >= MAX_MODELS) continue;
            fresh.add(new AiModelSpec(old.id, old.displayName, old.familyLabel, old.providerId,
                    "No longer reported by this provider", old.defaultStrength, true,
                    old.contextWindowTokens, old.vision, old.nativeFiles, old.extractedDocuments,
                    old.tools, old.webSearch, old.streaming, old.metadataSource, "unavailable",
                    old.strengths.toArray(new AiStrength[0])));
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static boolean supported(JSONObject caps, String name) {
        if (caps == null) return false;
        Object raw = caps.opt(name);
        if (raw instanceof Boolean) return (Boolean) raw;
        JSONObject value = raw instanceof JSONObject ? (JSONObject) raw : null;
        return value != null && value.optBoolean("supported", false);
    }

    private static List<AiStrength> efforts(JSONObject effort) {
        List<AiStrength> out = new ArrayList<>();
        if (effort == null || !effort.optBoolean("supported", false)) return out;
        for (AiStrength strength : new AiStrength[]{AiStrength.LOW, AiStrength.MEDIUM,
                AiStrength.HIGH, AiStrength.XHIGH, AiStrength.MAX}) {
            JSONObject value = effort.optJSONObject(strength.id);
            if (value != null && value.optBoolean("supported", false)) out.add(strength);
        }
        return out;
    }

    private static AiStrength defaultEffort(String id, List<AiStrength> values) {
        if (values.isEmpty()) return null;
        if (id.contains("opus") && values.contains(AiStrength.MEDIUM)) return AiStrength.MEDIUM;
        if (values.contains(AiStrength.HIGH)) return AiStrength.HIGH;
        return values.get(0);
    }

    private static boolean contains(JSONArray array, String value) {
        if (array == null) return false;
        for (int i = 0; i < array.length(); i++) if (value.equals(array.optString(i))) return true;
        return false;
    }

    private static String family(String name, String fallback) {
        String[] parts = name == null ? new String[0] : name.trim().split("\\s+");
        return parts.length >= 3 ? parts[0] + " " + parts[parts.length - 1] : fallback;
    }

    private static String displayXai(String id) {
        String[] parts = id.replace('-', ' ').trim().split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty() || "latest".equals(part)) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.length() == 0 ? id : out.toString();
    }

    private static void sort(List<AiModelSpec> models, String provider) {
        Comparator<AiModelSpec> comparator = Comparator
                .comparingInt((AiModelSpec m) -> priority(provider, m.id))
                .thenComparing(m -> m.displayName.toLowerCase(Locale.US));
        Collections.sort(models, comparator);
    }

    private static int priority(String provider, String id) {
        if (Prefs.PROVIDER_XAI.equals(provider) && OrbitModelCatalog.GROK_4_7.equals(id)) return 0;
        if (Prefs.PROVIDER_ANTHROPIC.equals(provider)) {
            if (OrbitModelCatalog.CLAUDE_OPUS_5_5.equals(id)) return 0;
            if (OrbitModelCatalog.CLAUDE_SONNET_5_5.equals(id)) return 1;
            if (OrbitModelCatalog.CLAUDE_FABLE_5_1.equals(id)) return 2;
            if (id.startsWith("claude-haiku-4-5")) return 3;
        }
        return 20;
    }

    private static int nonNegative(long value) {
        return value <= 0L || value > Integer.MAX_VALUE ? 0 : (int) value;
    }

    private static String read(InputStream input) throws Exception {
        if (input == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input,
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && out.length() < 2_000_000) out.append(line);
        }
        return out.toString();
    }

    private static final class CatalogException extends Exception {
        CatalogException(String message) { super(message); }
    }
}
