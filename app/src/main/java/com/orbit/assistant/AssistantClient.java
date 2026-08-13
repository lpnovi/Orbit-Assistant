package com.orbit.assistant;

import android.content.Context;
import android.util.Base64;
import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.TimeZone;
import java.time.OffsetDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AssistantClient {
    private static final ExecutorService EXEC = Executors.newCachedThreadPool();

    public interface Callback {
        default void onDelta(String text) {}
        void onSuccess(AssistantReply reply);
        void onError(String message);
    }

    public static final class History {
        public final String role;
        public final String content;
        /** Kept for backward compatibility; true now means any attachment exists. */
        public final boolean screenAttached;
        public final String attachmentPath;
        public final String attachmentKind;
        public final String attachmentLabel;
        /** Local request context needed for regeneration of text/PDF/screen attachments. */
        public final String attachmentText;
        /** Snapshot of memories supplied to the AI for this assistant turn. */
        public final String memoryUsage;
        /** Optional user-approved memory suggestion attached to this assistant turn. */
        public final String memorySuggestionText;
        public final String memorySuggestionCategory;

        public History(String role, String content) {
            this(role, content, false, "", "", "", "", "", "", "");
        }

        public History(String role, String content, boolean screenAttached) {
            this(role, content, screenAttached, "", screenAttached ? "screen" : "",
                    screenAttached ? "Screen attached" : "", "", "", "", "");
        }

        public History(String role, String content, boolean screenAttached, String attachmentPath) {
            this(role, content, screenAttached, attachmentPath,
                    screenAttached ? "screen" : "",
                    screenAttached ? "Screen attached" : "", "", "", "", "");
        }

        public History(String role, String content, boolean attached, String attachmentPath,
                       String attachmentKind, String attachmentLabel, String attachmentText) {
            this(role, content, attached, attachmentPath, attachmentKind, attachmentLabel,
                    attachmentText, "", "", "");
        }

        public History(String role, String content, boolean attached, String attachmentPath,
                       String attachmentKind, String attachmentLabel, String attachmentText,
                       String memoryUsage, String memorySuggestionText,
                       String memorySuggestionCategory) {
            this.role = role;
            this.content = content;
            this.screenAttached = attached;
            this.attachmentPath = attachmentPath == null ? "" : attachmentPath;
            this.attachmentKind = attachmentKind == null ? "" : attachmentKind;
            this.attachmentLabel = attachmentLabel == null ? "" : attachmentLabel;
            this.attachmentText = attachmentText == null ? "" : attachmentText;
            this.memoryUsage = memoryUsage == null ? "" : memoryUsage.trim();
            this.memorySuggestionText = memorySuggestionText == null ? "" : memorySuggestionText.trim();
            this.memorySuggestionCategory = memorySuggestionCategory == null ? "" : memorySuggestionCategory.trim();
        }
    }

    private AssistantClient() {}

    public static void send(Context context, String prompt, String screenText, Bitmap screenshot, List<History> history, Callback cb) {
        send(context, prompt, screenText, screenshot, history, Prefs.intelligenceMode(context), cb);
    }

    public static void send(Context context, String prompt, String screenText, Bitmap screenshot, List<History> history,
                            String intelligenceMode, Callback cb) {
        send(context, prompt, screenText, screenshot, history, intelligenceMode, false, cb);
    }

    public static void send(Context context, String prompt, String screenText, Bitmap screenshot, List<History> history,
                            String intelligenceMode, boolean explicitAttachment, Callback cb) {
        send(context, prompt, screenText, screenshot, history, intelligenceMode,
                explicitAttachment, "", cb);
    }

    public static void send(Context context, String prompt, String screenText, Bitmap screenshot, List<History> history,
                            String intelligenceMode, boolean explicitAttachment,
                            String trustedTaskContext, Callback cb) {
        // Explicit Orbit Memory commands stay local, inspectable, and instant.
        AssistantReply memory = MemoryCommandRouter.tryHandle(context, prompt, history);
        if (memory != null) {
            cb.onSuccess(memory);
            return;
        }

        // Saved routines are local, deterministic Action Engine plans. Resolve them
        // before generic device commands or the network-backed assistant.
        AssistantReply routine = RoutineCommandRouter.tryHandle(context, prompt);
        if (routine != null) {
            cb.onSuccess(routine);
            return;
        }

        // Obvious phone commands stay instant and offline regardless of AI provider.
        AssistantReply local = LocalCommandRouter.tryHandle(context, prompt);
        if (local != null) {
            cb.onSuccess(local);
            return;
        }

        NotificationQueryHelper.Prepared notification =
                NotificationQueryHelper.prepare(context, prompt);
        if (notification.recognized && notification.localReply != null) {
            cb.onSuccess(notification.localReply);
            return;
        }
        String notificationContext = notification.context;

        // User-defined phrases are exact local aliases for saved Routines. Core
        // memory, explicit Routine syntax, device commands, and local notification
        // queries intentionally retain priority.
        AssistantReply customCommand = CustomCommandRouter.tryHandle(context, prompt);
        if (customCommand != null) {
            cb.onSuccess(customCommand);
            return;
        }

        final MemoryStore.Selection memorySelection =
                MemoryStore.select(context, prompt, screenText, history);
        final MemoryStore.Suggestion memorySuggestion =
                MemoryStore.suggest(context, prompt);
        final Callback responseCallback = decorateMemoryMetadata(
                cb, memorySelection, memorySuggestion);

        String requestMode = Prefs.normalizeMode(intelligenceMode);
        if (Prefs.MODE_AUTO.equals(requestMode)) {
            AutoRouter.Decision auto = AutoRouter.route(context, prompt, screenText, screenshot,
                    history, explicitAttachment, notificationContext);
            requestMode = auto.mode;
            DiagnosticStore.recordAutoRouting(context, requestMode, auto.confidence, auto.reason,
                    Prefs.effectiveModelForMode(context, requestMode, prompt),
                    Prefs.effectiveReasoningForMode(context, requestMode, prompt));
        }

        String provider = Prefs.provider(context);
        if (Prefs.PROVIDER_CHATGPT.equals(provider)) {
            if (!ChatGptAuth.isSignedIn(context)) {
                responseCallback.onError("Sign in with ChatGPT in Orbit settings first. No API key is required for ChatGPT-account mode.");
                return;
            }
            ChatGptClient.send(context, prompt, screenText, screenshot, history,
                    requestMode, explicitAttachment, notificationContext,
                    memorySelection.promptContext, trustedTaskContext, responseCallback);
            return;
        }

        sendViaRelay(context, prompt, screenText, screenshot, history, requestMode,
                explicitAttachment, notificationContext, memorySelection.promptContext,
                trustedTaskContext, responseCallback);
    }

    private static Callback decorateMemoryMetadata(Callback downstream,
                                                   MemoryStore.Selection selection,
                                                   MemoryStore.Suggestion suggestion) {
        final String usage = selection == null ? "" : selection.usageText;
        final String suggestedText = suggestion == null ? "" : suggestion.text;
        final String suggestedCategory = suggestion == null ? "" : suggestion.category;

        return new Callback() {
            @Override public void onDelta(String text) {
                downstream.onDelta(text);
            }

            @Override public void onSuccess(AssistantReply reply) {
                if (reply == null) {
                    downstream.onSuccess(new AssistantReply("", new java.util.ArrayList<>(),
                            usage, suggestedText, suggestedCategory));
                    return;
                }
                downstream.onSuccess(new AssistantReply(reply.text, reply.actions,
                        usage, suggestedText, suggestedCategory));
            }

            @Override public void onError(String message) {
                downstream.onError(message);
            }
        };
    }

    private static void sendViaRelay(Context context, String prompt, String screenText, Bitmap screenshot,
                                     List<History> history, String intelligenceMode,
                                     boolean explicitAttachment, String notificationContext,
                                     String memoryContext, String trustedTaskContext, Callback cb) {
        String backend = Prefs.backendUrl(context);
        if (backend.isEmpty()) {
            cb.onError("API-relay mode is selected, but no relay is configured. Add your HTTPS relay URL or switch Provider to ChatGPT account.");
            return;
        }
        if (!backend.startsWith("https://")) {
            cb.onError("For security, Orbit only connects to HTTPS relay URLs.");
            return;
        }

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
                payload.put("prompt", prompt);
                payload.put("model", Prefs.effectiveModelForMode(context, intelligenceMode, prompt));
                payload.put("reasoning", Prefs.effectiveReasoningForMode(context, intelligenceMode, prompt));
                payload.put("clientTime", OffsetDateTime.now().toString());
                payload.put("timezone", TimeZone.getDefault().getID());
                payload.put("locale", java.util.Locale.getDefault().toLanguageTag());
                payload.put("leloMode", Prefs.leloMode(context));
                payload.put("memoryContext", memoryContext == null ? "" : memoryContext);
                payload.put("trustedTaskContext", trustedTaskContext == null ? "" : trustedTaskContext);
                payload.put("notificationContext", safe(notificationContext, 24000));
                payload.put("screenText", (Prefs.screenContext(context) || explicitAttachment)
                        ? safe(screenText, explicitAttachment ? 105000 : 18000) : "");
                if ((Prefs.screenshot(context) || explicitAttachment) && screenshot != null) payload.put("screenshotBase64", bitmapToBase64(screenshot));
                JSONArray h = new JSONArray();
                if (history != null) {
                    int end = history.size();
                    if (end > 0) {
                        History last = history.get(end - 1);
                        if (last != null && "user".equalsIgnoreCase(last.role) && prompt != null && prompt.trim().equals(last.content == null ? "" : last.content.trim())) end--;
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
