package com.orbit.assistant;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Official API-key transports for Anthropic Messages and xAI Chat Completions. */
final class ApiKeyProviderClient {
    private static final String ANTHROPIC_MESSAGES = "https://api.anthropic.com/v1/messages";
    private static final String XAI_CHAT = "https://api.x.ai/v1/chat/completions";
    private static final ExecutorService EXEC = Executors.newCachedThreadPool();

    private ApiKeyProviderClient() {}

    static void send(Context c, String provider, String key, AiRequest request,
                     AssistantClient.Callback callback) {
        EXEC.execute(() -> doSend(c, provider, key, request, callback));
    }

    static void complete(Context c, String provider, String key, AiSelection selection,
                         String instructions, String prompt, AssistantClient.PlanCallback callback) {
        EXEC.execute(() -> {
            HttpURLConnection conn = null;
            try {
                JSONObject body = ProviderRequestMapper.simple(provider, selection, instructions, prompt);
                conn = open(provider, key, false);
                write(conn, body);
                int code = conn.getResponseCode();
                String raw = read(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
                if (code < 200 || code >= 300) {
                    callback.onError(error(provider, code, raw, selection.model));
                    return;
                }
                String text = Prefs.PROVIDER_ANTHROPIC.equals(provider)
                        ? anthropicText(new JSONObject(raw)) : xaiText(new JSONObject(raw));
                if (text.trim().isEmpty()) callback.onError(name(provider) + " returned no text.");
                else callback.onText(text, name(provider) + " · " + selection.modelName());
            } catch (Exception e) {
                callback.onError("Could not reach " + name(provider) + ". Check the connection and try again.");
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private static void doSend(Context c, String provider, String key, AiRequest request,
                               AssistantClient.Callback callback) {
        HttpURLConnection conn = null;
        try {
            AiModelSpec spec = OrbitModelCatalog.spec(provider, request.selection.model);
            if (spec == null || !spec.selectable()) {
                callback.onError(OrbitModelCatalog.unavailableMessage(request.selection.model));
                return;
            }
            JSONObject body = Prefs.PROVIDER_ANTHROPIC.equals(provider)
                    ? ProviderRequestMapper.anthropic(c, request, true)
                    : ProviderRequestMapper.xai(c, request, true);
            conn = open(provider, key, true);
            write(conn, body);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String raw = read(conn.getErrorStream());
                String friendly = error(provider, code, raw, request.selection.model);
                if (code == 404 || OrbitModelCatalog.looksUnavailable(request.selection.model, raw)) {
                    ModelAvailability.markUnavailable(c, provider, request.selection.model);
                }
                callback.onError(friendly);
                return;
            }
            ModelAvailability.markAvailable(c, provider, request.selection.model);
            if (request.thinkingUpdates) callback.onThinking(ThinkingUpdate.modelReasoning(request.selection.model));
            String text = Prefs.PROVIDER_ANTHROPIC.equals(provider)
                    ? readAnthropicStream(conn.getInputStream(), callback)
                    : readXaiStream(conn.getInputStream(), callback);
            if (text.trim().isEmpty()) {
                callback.onError(name(provider) + " connected but returned no assistant text. Try again.");
                return;
            }
            callback.onSuccess(new AssistantReply(text.replace("—", "-"), new ArrayList<>())
                    .withDetails(ResponseDetails.sentWith(request.selection)));
        } catch (Exception e) {
            callback.onError("Could not reach " + name(provider) + ". Check the connection and try again.");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static HttpURLConnection open(String provider, String key, boolean stream) throws Exception {
        boolean anthropic = Prefs.PROVIDER_ANTHROPIC.equals(provider);
        HttpURLConnection conn = (HttpURLConnection) new URL(anthropic ? ANTHROPIC_MESSAGES : XAI_CHAT)
                .openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(240_000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", stream ? "text/event-stream" : "application/json");
        if (anthropic) {
            conn.setRequestProperty("x-api-key", key);
            conn.setRequestProperty("anthropic-version", "2023-06-01");
        } else {
            conn.setRequestProperty("Authorization", "Bearer " + key);
        }
        return conn;
    }

    private static void write(HttpURLConnection conn, JSONObject body) throws Exception {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = conn.getOutputStream()) { out.write(bytes); }
    }

    static String readAnthropicStream(InputStream input, AssistantClient.Callback callback) throws Exception {
        StringBuilder full = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty()) continue;
                JSONObject event;
                try { event = new JSONObject(data); } catch (Exception ignored) { continue; }
                if ("error".equals(event.optString("type"))) {
                    JSONObject error = event.optJSONObject("error");
                    throw new IllegalStateException(error == null ? "stream error" : error.optString("type"));
                }
                JSONObject delta = event.optJSONObject("delta");
                if (delta != null && "text_delta".equals(delta.optString("type"))) {
                    full.append(delta.optString("text", ""));
                    callback.onDelta(full.toString().replace("—", "-"));
                }
            }
        }
        return full.toString();
    }

    static String readXaiStream(InputStream input, AssistantClient.Callback callback) throws Exception {
        StringBuilder full = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) continue;
                JSONObject event;
                try { event = new JSONObject(data); } catch (Exception ignored) { continue; }
                JSONArray choices = event.optJSONArray("choices");
                JSONObject choice = choices == null ? null : choices.optJSONObject(0);
                JSONObject delta = choice == null ? null : choice.optJSONObject("delta");
                if (delta != null && delta.has("content")) {
                    full.append(delta.optString("content", ""));
                    callback.onDelta(full.toString().replace("—", "-"));
                }
            }
        }
        return full.toString();
    }

    static String error(String provider, int code, String raw, String model) {
        String lower = raw == null ? "" : raw.toLowerCase(java.util.Locale.US);
        if (code == 401 || code == 403) return "The saved " + name(provider)
                + " API key is invalid, revoked, or lacks access. Update it in AI Providers.";
        if (code == 404 || OrbitModelCatalog.looksUnavailable(model, raw))
            return OrbitModelCatalog.unavailableMessage(model);
        if (code == 413 || lower.contains("context") && (lower.contains("long") || lower.contains("limit")))
            return "This request is too large for " + OrbitModelCatalog.displayName(model) + ". Remove some context or continue in a new chat.";
        if (code == 429) return name(provider) + " rate limit reached. Wait a moment and try again.";
        if (code == 529 || lower.contains("overloaded")) return name(provider) + " is temporarily overloaded. Try again shortly.";
        if (code >= 500) return name(provider) + " had a server problem. Try again shortly.";
        if (lower.contains("image") || lower.contains("unsupported"))
            return "The selected model does not support something attached to this request.";
        return name(provider) + " rejected this request. Check the selected model and try again.";
    }

    private static String anthropicText(JSONObject root) {
        JSONArray content = root.optJSONArray("content");
        StringBuilder out = new StringBuilder();
        if (content != null) for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.optJSONObject(i);
            if (block != null && "text".equals(block.optString("type"))) out.append(block.optString("text"));
        }
        return out.toString();
    }

    private static String xaiText(JSONObject root) {
        JSONArray choices = root.optJSONArray("choices");
        JSONObject choice = choices == null ? null : choices.optJSONObject(0);
        JSONObject message = choice == null ? null : choice.optJSONObject("message");
        return message == null ? "" : message.optString("content", "");
    }

    private static String read(InputStream input) throws Exception {
        if (input == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && out.length() < 200_000) out.append(line);
        }
        return out.toString();
    }

    static String name(String provider) {
        return Prefs.PROVIDER_ANTHROPIC.equals(provider) ? "Anthropic" : "xAI";
    }
}
