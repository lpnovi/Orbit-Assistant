package com.orbit.assistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The optional private HTTPS API-relay fallback behind the provider contract.
 *
 * <p>The request and planning bodies are the ones {@code AssistantClient} sent before the
 * provider layer existed, moved here unchanged so an already-configured relay keeps behaving
 * identically. The relay applies its own response format server-side, which is why planning
 * hands the body back whole rather than parsing it.
 */
final class RelayProvider implements AiProvider {
    private static final ExecutorService EXEC = Executors.newCachedThreadPool();

    static final String NOT_CONFIGURED_ERROR =
            "API-relay mode is selected, but no relay is configured. Add your HTTPS relay URL or switch Provider to ChatGPT account.";
    static final String HTTPS_ERROR = "For security, Orbit only connects to HTTPS relay URLs.";

    private static final AiCapabilities CAPABILITIES = AiCapabilities.builder()
            .streaming(false)
            .deviceActions(true)
            .images(true)
            .offline(false)
            .needsCredentials(true)
            .reasoningLevels(true)
            .hostedWebSearch(false)
            .routinePlanning(true)
            .build();

    @Override public String id() { return Prefs.PROVIDER_RELAY; }

    @Override public String displayName() { return "API relay"; }

    @Override public String description() {
        return "Advanced fallback: a private HTTPS relay you control. Your API key never enters Orbit.";
    }

    @Override public AiCapabilities capabilities() { return CAPABILITIES; }

    @Override public Status status(Context context) {
        return Prefs.relayConfigured(context) ? Status.READY : Status.NEEDS_SETUP;
    }

    @Override public String statusDetail(Context context) {
        return Prefs.relayConfigured(context) ? "Relay configured" : "Relay URL required";
    }

    @Override public boolean selectable(Context context) { return true; }

    @Override public void send(Context context, AiRequest request,
                               AssistantClient.Callback cb) {
        String backend = Prefs.backendUrl(context);
        if (backend.isEmpty()) { cb.onError(NOT_CONFIGURED_ERROR); return; }
        if (!backend.startsWith("https://")) { cb.onError(HTTPS_ERROR); return; }

        EXEC.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(backend.endsWith("/") ? backend + "assistant" : backend + "/assistant");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(90000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Accept", "application/json");
                String token = Prefs.token(context);
                if (!token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);

                JSONObject payload = new JSONObject();
                payload.put("prompt", request.prompt);
                payload.put("model", Prefs.effectiveModelForMode(context, request.intelligenceMode, request.prompt));
                payload.put("reasoning", Prefs.effectiveReasoningForMode(context, request.intelligenceMode, request.prompt));
                payload.put("clientTime", OffsetDateTime.now().toString());
                payload.put("timezone", TimeZone.getDefault().getID());
                payload.put("locale", java.util.Locale.getDefault().toLanguageTag());
                payload.put("leloMode", Prefs.leloMode(context));
                payload.put("memoryContext", request.memoryContext);
                payload.put("trustedTaskContext", request.trustedTaskContext);
                payload.put("notificationContext", safe(request.notificationContext, 24000));
                payload.put("screenText", (Prefs.screenContext(context) || request.explicitAttachment)
                        ? safe(request.screenText, request.explicitAttachment ? 105000 : 18000) : "");
                if ((Prefs.screenshot(context) || request.explicitAttachment) && request.screenshot != null) {
                    payload.put("screenshotBase64", bitmapToBase64(request.screenshot));
                }
                JSONArray h = new JSONArray();
                java.util.List<AssistantClient.History> history = request.history;
                if (history != null) {
                    int end = history.size();
                    if (end > 0) {
                        AssistantClient.History last = history.get(end - 1);
                        if (last != null && "user".equalsIgnoreCase(last.role) && request.prompt != null
                                && request.prompt.trim().equals(last.content == null ? "" : last.content.trim())) end--;
                    }
                    int start = Math.max(0, end - 10);
                    for (int i = start; i < end; i++) {
                        JSONObject m = new JSONObject();
                        m.put("role", history.get(i).role);
                        m.put("content", safe(history.get(i).content, 6000));
                        h.put(m);
                    }
                }
                payload.put("history", h);
                payload.put("capabilities", new JSONArray(new String[]{
                        "OPEN_APP", "OPEN_SETTINGS", "SET_ALARM", "SET_TIMER", "SET_REMINDER", "CREATE_EVENT",
                        "NAVIGATE", "DIAL", "DIAL_CONTACT", "SMS", "SMS_CONTACT", "WEB_SEARCH", "OPEN_URL", "SHARE", "COPY", "FLASHLIGHT",
                        "SET_VOLUME", "SET_BRIGHTNESS", "SET_DND", "OPEN_INTERNET_PANEL", "OPEN_BLUETOOTH_SETTINGS"
                }));

                byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = conn.getOutputStream()) { out.write(bytes); }

                int code = conn.getResponseCode();
                InputStream input = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
                String body = readAll(input);
                if (code < 200 || code >= 300) {
                    cb.onError("Relay error " + code + (body.isEmpty() ? "" : ": " + safe(body, 700)));
                    return;
                }
                cb.onSuccess(AssistantReply.fromJson(new JSONObject(body)));
            } catch (Exception e) {
                cb.onError("Could not reach the Orbit relay: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    @Override public void plan(Context context, String planningPrompt, String intelligenceMode,
                               AssistantClient.PlanCallback cb) {
        String backend = Prefs.backendUrl(context);
        if (backend.isEmpty()) { cb.onError(NOT_CONFIGURED_ERROR); return; }
        if (!backend.startsWith("https://")) { cb.onError(HTTPS_ERROR); return; }
        EXEC.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(backend.endsWith("/") ? backend + "assistant" : backend + "/assistant");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(90000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Accept", "application/json");
                String token = Prefs.token(context);
                if (!token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);

                // Planning carries the description and safe action metadata only: no history,
                // screen text, screenshot, notifications, or memory context.
                JSONObject payload = new JSONObject();
                payload.put("prompt", planningPrompt);
                payload.put("model", Prefs.effectiveModelForMode(context, intelligenceMode, planningPrompt));
                payload.put("reasoning", Prefs.effectiveReasoningForMode(context, intelligenceMode, planningPrompt));
                payload.put("clientTime", OffsetDateTime.now().toString());
                payload.put("timezone", TimeZone.getDefault().getID());
                payload.put("locale", java.util.Locale.getDefault().toLanguageTag());
                payload.put("history", new JSONArray());
                payload.put("screenText", "");
                payload.put("notificationContext", "");
                payload.put("memoryContext", "");
                payload.put("trustedTaskContext", "");

                byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = conn.getOutputStream()) { out.write(bytes); }

                int code = conn.getResponseCode();
                InputStream input = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
                String body = readAll(input);
                if (code < 200 || code >= 300) {
                    cb.onError("Relay error " + code + (body.isEmpty() ? "" : ": " + safe(body, 700)));
                    return;
                }
                cb.onText(body, "Relay · " + Prefs.modeLabel(intelligenceMode));
            } catch (Exception e) {
                cb.onError("Could not reach the Orbit relay: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private static String bitmapToBase64(Bitmap source) {
        Bitmap bmp = source;
        int max = 1280;
        if (source.getWidth() > max || source.getHeight() > max) {
            float scale = Math.min(max / (float) source.getWidth(), max / (float) source.getHeight());
            bmp = Bitmap.createScaledBitmap(source, Math.max(1, Math.round(source.getWidth() * scale)), Math.max(1, Math.round(source.getHeight() * scale)), true);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 78, out);
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) b.append(line);
        }
        return b.toString();
    }

    private static String safe(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
