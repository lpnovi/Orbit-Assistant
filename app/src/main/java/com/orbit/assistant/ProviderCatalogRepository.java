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

/**
 * Account-scoped provider catalog discovery with a bounded last-known-good offline cache.
 *
 * <p><b>OpenRouter (0.8.3.0-beta.6+).</b> OpenRouter's catalog is hundreds of models, so it is
 * filtered to chat models (text in, text out), drops OpenRouter's own routers except OpenRouter
 * Auto (Fusion, Free and the rest stay out), drops batch-only variants, and is cached as a file in
 * the no-backup directory rather than in preferences. Capabilities come only from OpenRouter's
 * structured fields: images from {@code architecture.input_modalities}, the window from
 * {@code context_length}, and strengths only from {@code reasoning.supported_efforts} on a model
 * whose {@code supported_parameters} include {@code reasoning}. Nothing is inferred from a name.
 * The account-filtered {@code /models/user} list is preferred; where a key may not read it, the
 * public {@code /models} list is used and availability is learned at request time instead.
 */
public final class ProviderCatalogRepository {
    private static final String FILE = "orbit_model_catalog_cache";
    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/models?limit=100";
    private static final String XAI_URL = "https://api.x.ai/v1/language-models";
    static final String OPENROUTER_USER_MODELS_URL = "https://openrouter.ai/api/v1/models/user?limit=1000";
    static final String OPENROUTER_MODELS_URL = "https://openrouter.ai/api/v1/models";
    static final String OPENROUTER_KEY_URL = "https://openrouter.ai/api/v1/key";
    private static final String OPENROUTER_FILE = "openrouter_catalog_v1.json";
    private static final int MAX_MODELS = 80;
    /** Comfortably above OpenRouter's current chat catalog; anything beyond is dropped, not paged. */
    static final int MAX_OPENROUTER_MODELS = 800;
    private static final long STALE_MS = 24L * 60L * 60L * 1000L;
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Set<String> REFRESHING = new HashSet<>();
    /** The OpenRouter cache file's stamp when it was last parsed, so repeat loads cost nothing. */
    private static long openRouterLoadedStamp = -1L;

    public interface Callback { void onFinished(boolean changed, String error); }

    private ProviderCatalogRepository() {}

    /** Loads last-known-good metadata without network access. Safe to call repeatedly. */
    public static void loadCached(Context c) {
        install(c, Prefs.PROVIDER_ANTHROPIC, readCache(c, Prefs.PROVIDER_ANTHROPIC));
        install(c, Prefs.PROVIDER_XAI, readCache(c, Prefs.PROVIDER_XAI));
        loadOpenRouterCache(c);
    }

    // ---- OpenRouter ------------------------------------------------------------------------------

    private static java.io.File openRouterFile(Context c) {
        return new java.io.File(c.getNoBackupFilesDir(), OPENROUTER_FILE);
    }

    /** Parses the cached OpenRouter catalog only when the file changed since the last parse. */
    private static synchronized void loadOpenRouterCache(Context c) {
        if (c == null) return;
        java.io.File file = openRouterFile(c);
        long stamp = file.exists() ? file.lastModified() ^ (file.length() << 20) : 0L;
        // Skipped only while the parsed list is still the one installed.
        if (stamp == openRouterLoadedStamp
                && (stamp == 0L || OrbitModelCatalog.hasDynamic(Prefs.PROVIDER_OPENROUTER))) return;
        openRouterLoadedStamp = stamp;
        if (stamp == 0L) return;
        try {
            JSONObject root = new JSONObject(readFile(file));
            install(c, Prefs.PROVIDER_OPENROUTER, decodeOpenRouter(root.optJSONArray("models")));
        } catch (Exception ignored) {}
    }

    private static synchronized void writeOpenRouterCache(Context c, List<AiModelSpec> models)
            throws Exception {
        JSONObject root = new JSONObject().put("savedAt", System.currentTimeMillis())
                .put("models", encode(models));
        java.io.File file = openRouterFile(c);
        java.io.File temp = new java.io.File(file.getParentFile(), OPENROUTER_FILE + ".tmp");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(temp)) {
            out.write(root.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!temp.renameTo(file)) {
            file.delete();
            if (!temp.renameTo(file)) throw new java.io.IOException("cache rename failed");
        }
        openRouterLoadedStamp = file.lastModified() ^ (file.length() << 20);
    }

    private static List<AiModelSpec> readOpenRouterCacheFile(Context c) {
        try {
            java.io.File file = openRouterFile(c);
            if (!file.exists()) return new ArrayList<>();
            return decodeOpenRouter(new JSONObject(readFile(file)).optJSONArray("models"));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static long openRouterSavedAt(Context c) {
        java.io.File file = openRouterFile(c);
        return file.exists() ? file.lastModified() : 0L;
    }

    private static String readFile(java.io.File file) throws Exception {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            return read(in);
        }
    }

    /** OpenRouter's catalog for this key: the account-filtered list when the key may read it. */
    private static List<AiModelSpec> fetchOpenRouter(String key) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(OPENROUTER_USER_MODELS_URL).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + key);
            int code = conn.getResponseCode();
            if (code == 401) throw new CatalogException(OPENROUTER_REJECTED);
            if (code >= 200 && code < 300) {
                List<AiModelSpec> models = parseOpenRouter(read(conn.getInputStream()));
                if (!models.isEmpty()) return models;
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
        // Not readable with this key, or empty: the public catalog, checked again at send time.
        return parseOpenRouter(get(OPENROUTER_MODELS_URL, "Accept", "application/json", false));
    }

    static final String OPENROUTER_REJECTED =
            "OpenRouter rejected the saved connection. It may have been revoked. Reconnect OpenRouter.";

    /**
     * Checks a saved key with {@code GET /api/v1/key}. Empty when it works; otherwise one short
     * sentence. Says nothing about credit balances: Orbit never shows or interprets them.
     */
    static String checkOpenRouterKey(String key) {
        if (key == null || key.trim().isEmpty()) return "OpenRouter is not connected.";
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(OPENROUTER_KEY_URL).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + key.trim());
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) return "";
            if (code == 401 || code == 403) return OPENROUTER_REJECTED;
            if (code == 429) return "OpenRouter's rate limit was reached. Try again later.";
            return "OpenRouter could not check the connection right now. Try again shortly.";
        } catch (Exception e) {
            return "Could not reach OpenRouter. Check the connection and try again.";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Checks the saved OpenRouter connection off the main thread. */
    public static void checkOpenRouterAsync(Context c, Callback callback) {
        Context app = c.getApplicationContext();
        EXEC.execute(() -> {
            String error = checkOpenRouterKey(SecureStore.loadOpenRouterKey(app));
            if (callback != null) callback.onFinished(false, error);
        });
    }

    /** OpenRouter's own routers. Only OpenRouter Auto is offered, as an explicit model. */
    private static final String OPENROUTER_NAMESPACE = "openrouter/";

    /**
     * The chat models in one OpenRouter catalog response, in picker order. Pure: tests feed it the
     * documented model object shape.
     */
    static List<AiModelSpec> parseOpenRouter(String raw) throws Exception {
        JSONArray data = new JSONObject(raw).optJSONArray("data");
        List<AiModelSpec> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (data == null) return out;
        long now = System.currentTimeMillis();
        for (int i = 0; i < data.length() && out.size() < MAX_OPENROUTER_MODELS; i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id", "").trim();
            if (id.isEmpty() || id.indexOf('/') <= 0 || id.contains("|") || !seen.add(id)) continue;
            boolean router = OrbitModelCatalog.OPENROUTER_AUTO.equals(id);
            if (id.startsWith(OPENROUTER_NAMESPACE) && !router) continue;
            if (id.endsWith(":batch")) continue;
            JSONObject arch = item.optJSONObject("architecture");
            JSONArray input = arch == null ? null : arch.optJSONArray("input_modalities");
            JSONArray output = arch == null ? null : arch.optJSONArray("output_modalities");
            if (!contains(input, "text") || !contains(output, "text")) continue;
            // Orbit shows text answers. A model that also answers with images, audio or the like is
            // not a chat model here; OpenRouter Auto is the one exception, by design text-first.
            if (!router && (output == null || output.length() != 1)) continue;
            String author = id.substring(0, id.indexOf('/'));
            String name = openRouterName(item.optString("name", id), id);
            String family = openRouterFamily(item.optString("name", ""), author);
            boolean vision = contains(input, "image");
            int context = router ? 0 : nonNegative(item.optLong("context_length", 0L));
            List<AiStrength> efforts = new ArrayList<>();
            AiStrength def = null;
            JSONObject reasoning = item.optJSONObject("reasoning");
            if (!router && contains(item.optJSONArray("supported_parameters"), "reasoning")
                    && reasoning != null) {
                JSONArray values = reasoning.optJSONArray("supported_efforts");
                if (values != null) for (int j = 0; j < values.length(); j++) {
                    AiStrength strength = AiStrength.fromId(values.optString(j));
                    if (strength != null && !efforts.contains(strength)) efforts.add(strength);
                }
                Collections.sort(efforts);
                def = AiStrength.fromId(reasoning.optString("default_effort", ""));
                if (def != null && !efforts.contains(def)) def = null;
                if (def == null && !efforts.isEmpty()) def = efforts.contains(AiStrength.MEDIUM)
                        ? AiStrength.MEDIUM : efforts.get(0);
            }
            String availability = "active";
            String descriptor = router ? "OpenRouter picks a model for each request"
                    : family + " via OpenRouter";
            String expires = item.optString("expiration_date", "");
            if (!item.isNull("expiration_date") && expires.length() >= 10) {
                try {
                    long at = java.time.LocalDate.parse(expires.substring(0, 10))
                            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
                    availability = at <= now ? "unavailable" : "deprecated";
                    descriptor = family + " via OpenRouter · retiring " + expires.substring(0, 10);
                } catch (Exception ignored) {}
            }
            out.add(new AiModelSpec(id, router ? "OpenRouter Auto" : name,
                    router ? "OpenRouter" : family, Prefs.PROVIDER_OPENROUTER, descriptor, def, true,
                    context, vision, false, true, false, false, true, "dynamic_provider",
                    availability, efforts.toArray(new AiStrength[0])));
        }
        sortOpenRouter(out);
        return out;
    }

    /** "Anthropic: Claude Sonnet 5.5" is "Claude Sonnet 5.5"; a name without an author stays. */
    static String openRouterName(String name, String id) {
        String value = name == null ? "" : name.trim();
        int colon = value.indexOf(": ");
        if (colon > 0 && colon < 40) value = value.substring(colon + 2).trim();
        if (value.isEmpty()) value = id;
        return value.length() > 80 ? value.substring(0, 79).trim() + "…" : value;
    }

    /** "Anthropic" from "Anthropic: Claude Sonnet 5.5", else the slug's author. */
    static String openRouterFamily(String name, String author) {
        String value = name == null ? "" : name.trim();
        int colon = value.indexOf(": ");
        if (colon > 0 && colon < 40) return value.substring(0, colon).trim();
        return author == null || author.isEmpty() ? "OpenRouter" : author;
    }

    /**
     * Orbit Auto's curated routes, then OpenRouter Auto, then every other model by name. The first
     * entry is what choosing OpenRouter starts on, so it is a known, inexpensive model rather than a
     * router whose downstream model and cost vary per request.
     */
    private static void sortOpenRouter(List<AiModelSpec> models) {
        Collections.sort(models, Comparator
                .comparingInt((AiModelSpec m) -> openRouterPriority(m.id))
                .thenComparing(m -> m.displayName.toLowerCase(Locale.US))
                .thenComparing(m -> m.id));
    }

    private static int openRouterPriority(String id) {
        if (OrbitModelCatalog.OR_GPT_6_LUNA.equals(id)) return 0;
        if (OrbitModelCatalog.OR_GPT_6_1_SOL.equals(id)) return 1;
        if (OrbitModelCatalog.OR_CLAUDE_SONNET_5_5.equals(id)) return 2;
        if (OrbitModelCatalog.OR_CLAUDE_OPUS_5_5.equals(id)) return 3;
        if (OrbitModelCatalog.OPENROUTER_AUTO.equals(id)) return 4;
        return 20;
    }

    private static List<AiModelSpec> decodeOpenRouter(JSONArray data) {
        List<AiModelSpec> out = new ArrayList<>();
        if (data == null) return out;
        for (int i = 0; i < data.length() && out.size() < MAX_OPENROUTER_MODELS; i++) {
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
            out.add(new AiModelSpec(id, item.optString("name", id), item.optString("family", ""),
                    Prefs.PROVIDER_OPENROUTER, item.optString("description", ""),
                    AiStrength.fromId(item.optString("default", "")), true,
                    nonNegative(item.optLong("context", 0L)), item.optBoolean("vision"), false,
                    true, false, false, true, "dynamic_provider",
                    item.optString("availability", "active"),
                    strengths.toArray(new AiStrength[0])));
        }
        return out;
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
                } else if (Prefs.PROVIDER_OPENROUTER.equals(provider)) {
                    String key = SecureStore.loadOpenRouterKey(app);
                    if (key.isEmpty()) throw new CatalogException("Connect OpenRouter first.");
                    models = fetchOpenRouter(key);
                    if (models.isEmpty()) throw new CatalogException("The provider returned no compatible chat models.");
                    // Same rule as below: a model that disappeared stays as a historical entry.
                    retainMissing(models, readOpenRouterCacheFile(app), MAX_OPENROUTER_MODELS);
                    List<AiModelSpec> before = readOpenRouterCacheFile(app);
                    writeOpenRouterCache(app, models);
                    OrbitModelCatalog.installDynamic(provider, models);
                    changed = !encode(models).toString().equals(encode(before).toString());
                    if (callback != null) callback.onFinished(changed, "");
                    return;
                } else {
                    throw new CatalogException("This provider has no dynamic catalog.");
                }
                if (models.isEmpty()) throw new CatalogException("The provider returned no compatible chat models.");
                // A successful refresh may legitimately remove a model. Keep its last-known facts
                // as an unavailable historical entry so chats, Favorites, and Response Details
                // retain the exact model rather than silently changing to the new first item.
                retainMissing(models, readCache(app, provider), MAX_MODELS);
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
                : Prefs.PROVIDER_XAI.equals(provider) ? SecureStore.hasXaiKey(c)
                : Prefs.PROVIDER_OPENROUTER.equals(provider) && SecureStore.hasOpenRouterKey(c);
        if (!configured) return;
        long refreshedAt = Prefs.PROVIDER_OPENROUTER.equals(provider)
                ? openRouterSavedAt(c) : prefs(c).getLong(provider + "_at", 0L);
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

    private static void retainMissing(List<AiModelSpec> fresh, List<AiModelSpec> previous, int max) {
        Set<String> present = new HashSet<>();
        for (AiModelSpec current : fresh) present.add(current.id);
        for (AiModelSpec old : previous) {
            if (present.contains(old.id) || fresh.size() >= max) continue;
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
