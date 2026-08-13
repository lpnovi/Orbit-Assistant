package com.orbit.assistant;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Executes only validated declarative URL and HTTPS capabilities from Extensions v1/v2. */
public final class OrbitExtensionActionExecutor {
    public interface Completion { void finish(DeviceActionExecutor.Result result); }

    private static final ExecutorService NETWORK = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private OrbitExtensionActionExecutor() {}

    public static void execute(Context context, AssistantReply.Action routineAction,
                               Completion completion) {
        if (context == null || routineAction == null || routineAction.params == null) {
            finish(completion, unavailable());
            return;
        }
        String extensionId = routineAction.params.optString("extensionId", "");
        String actionId = routineAction.params.optString("actionId", "");
        OrbitExtensionStore.Installed installed = OrbitExtensionStore.find(context, extensionId);
        OrbitExtension.Action action = installed == null || !installed.enabled
                ? null : installed.extension.findAction(actionId);
        if (action == null) {
            finish(completion, unavailable());
            return;
        }
        if (!OrbitExtensionStore.isConfigured(context, installed)) {
            finish(completion, DeviceActionExecutor.Result.unavailable(
                    installed.extension.name + " needs setup"));
            return;
        }

        if (OrbitExtension.TYPE_OPEN_URL.equals(action.type)) {
            NETWORK.execute(() -> openReviewedUrl(context, action, completion));
            return;
        }

        if (!OrbitExtension.TYPE_HTTPS_REQUEST.equals(action.type)) {
            finish(completion, unavailable());
            return;
        }
        Context app = context.getApplicationContext();
        NETWORK.execute(() -> finish(completion,
                executeHttps(app, installed, action, routineAction)));
    }

    public static boolean isHeadlessHttps(Context context, AssistantReply.Action routineAction) {
        if (context == null || routineAction == null || routineAction.params == null) return false;
        OrbitExtension.Action action = OrbitExtensionStore.resolveEnabledAction(context,
                routineAction.params.optString("extensionId", ""),
                routineAction.params.optString("actionId", ""));
        return action != null && OrbitExtension.TYPE_HTTPS_REQUEST.equals(action.type);
    }

    private static DeviceActionExecutor.Result executeHttps(Context context,
            OrbitExtensionStore.Installed installed, OrbitExtension.Action action,
            AssistantReply.Action routineAction) {
        HttpURLConnection connection = null;
        try {
            Map<String, String> configuration = installed.extension.schemaVersion ==
                    OrbitExtension.SCHEMA_VERSION_V2
                    ? OrbitExtensionStore.resolvedConfiguration(context, installed)
                    : Collections.emptyMap();
            if (configuration == null) return DeviceActionExecutor.Result.unavailable(
                    installed.extension.name + " needs setup");
            JSONObject rawParameters = routineAction.params.optJSONObject("actionParameters");
            JSONObject safeParameters = installed.extension.schemaVersion ==
                    OrbitExtension.SCHEMA_VERSION_V2
                    ? OrbitExtensionV2.validateAndNormalizeParameters(
                            action, rawParameters, true) : new JSONObject();
            Map<String, String> parameters = jsonStrings(safeParameters);
            String finalUrl = installed.extension.schemaVersion == OrbitExtension.SCHEMA_VERSION_V2
                    ? OrbitExtensionV2.renderString(action.url, configuration, parameters)
                    : action.url;

            // Validate the fully rendered URL and resolve its host immediately before connecting.
            if (finalUrl.length() > 2048)
                return DeviceActionExecutor.Result.failed(
                        "Extension endpoint exceeded Orbit's safe size limit.");
            OrbitExtension.validatePublicUrl(finalUrl, false);
            OrbitExtension.validateResolvedPublicHost(finalUrl);

            JSONObject renderedBody = action.body == null ? new JSONObject()
                    : installed.extension.schemaVersion == OrbitExtension.SCHEMA_VERSION_V2
                    ? OrbitExtensionV2.renderBody(action.body, configuration, parameters)
                    : action.body;
            byte[] body = renderedBody.toString().getBytes(StandardCharsets.UTF_8);
            if (body.length > OrbitExtension.MAX_POST_BODY_BYTES)
                return DeviceActionExecutor.Result.failed(
                        "Extension request exceeded Orbit's safe size limit.");

            Map<String, String> renderedHeaders = new LinkedHashMap<>();
            for (OrbitExtension.RequestHeader header : action.headers) {
                String value = OrbitExtensionV2.renderString(
                        header.valueTemplate, configuration, parameters);
                if (value.length() > 1024 || unsafeHeaderValue(value))
                    return DeviceActionExecutor.Result.failed("Extension request headers are invalid.");
                renderedHeaders.put(header.name, value);
            }

            connection = (HttpURLConnection) new URL(finalUrl).openConnection();
            // Split the declared total budget between connection and response read.
            int timeoutMs = Math.max(1000, action.timeoutSeconds * 500);
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestMethod(action.method);
            connection.setRequestProperty("Accept", "application/json, text/plain;q=0.8, */*;q=0.2");
            connection.setRequestProperty("User-Agent", "Orbit-Assistant/" + BuildConfig.VERSION_NAME);
            for (Map.Entry<String, String> header : renderedHeaders.entrySet())
                connection.setRequestProperty(header.getKey(), header.getValue());
            if ("POST".equals(action.method)) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                if (!containsHeader(renderedHeaders, "Content-Type"))
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }
            }
            int status = connection.getResponseCode();
            InputStream input = status >= 200 && status < 400
                    ? connection.getInputStream() : connection.getErrorStream();
            if (input != null) readBounded(input, OrbitExtension.MAX_RESPONSE_BYTES);
            if (status >= 200 && status < 300) {
                return DeviceActionExecutor.Result.success(
                        "Extension request succeeded (HTTP " + status + ")");
            }
            if (status >= 300 && status < 400) {
                return DeviceActionExecutor.Result.failed(
                        "Extension request stopped at an unreviewed redirect (HTTP " + status + ")");
            }
            return DeviceActionExecutor.Result.failed(
                    "Extension request failed (HTTP " + status + ")");
        } catch (ResponseTooLargeException ignored) {
            return DeviceActionExecutor.Result.failed("Extension response exceeded Orbit's safe size limit.");
        } catch (IllegalArgumentException ignored) {
            return DeviceActionExecutor.Result.failed("Extension endpoint is not a public HTTPS address.");
        } catch (Exception ignored) {
            return DeviceActionExecutor.Result.failed("Extension request could not be completed.");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static Map<String, String> jsonStrings(JSONObject object) {
        Map<String, String> out = new LinkedHashMap<>();
        if (object == null) return out;
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            out.put(key, object.optString(key, ""));
        }
        return out;
    }

    private static boolean containsHeader(Map<String, String> headers, String wanted) {
        for (String name : headers.keySet()) if (wanted.equalsIgnoreCase(name)) return true;
        return false;
    }

    private static boolean unsafeHeaderValue(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) return true;
        }
        return false;
    }

    private static void readBounded(InputStream input, int maxBytes) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) throw new ResponseTooLargeException();
                output.write(buffer, 0, read);
            }
        }
    }

    private static void openReviewedUrl(Context context, OrbitExtension.Action action,
                                        Completion completion) {
        try {
            OrbitExtension.validateResolvedPublicHost(action.url);
            MAIN.post(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(action.url))
                            .addCategory(Intent.CATEGORY_BROWSABLE);
                    if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (intent.resolveActivity(context.getPackageManager()) == null) {
                        if (completion != null) completion.finish(DeviceActionExecutor.Result.unavailable(
                                "No browser is available for this extension action."));
                        return;
                    }
                    context.startActivity(intent);
                    if (completion != null) completion.finish(DeviceActionExecutor.Result.success(
                            "Extension opened the reviewed URL"));
                } catch (Exception ignored) {
                    if (completion != null) completion.finish(DeviceActionExecutor.Result.failed(
                            "Orbit could not open the extension URL."));
                }
            });
        } catch (Exception ignored) {
            finish(completion, DeviceActionExecutor.Result.failed(
                    "Extension URL is not a public web address."));
        }
    }

    private static DeviceActionExecutor.Result unavailable() {
        return DeviceActionExecutor.Result.unavailable("Extension action unavailable");
    }

    private static void finish(Completion completion, DeviceActionExecutor.Result result) {
        if (completion != null) MAIN.post(() -> completion.finish(result));
    }

    private static final class ResponseTooLargeException extends Exception {}
}
