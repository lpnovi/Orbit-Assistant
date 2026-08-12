package com.orbit.assistant;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Experimental ChatGPT/Codex account authentication.
 *
 * This follows OpenAI Codex's public device-code OAuth protocol. It intentionally
 * stores OAuth credentials only through SecureStore (Android Keystore).
 */
public final class ChatGptAuth {
    public static final String ISSUER = "https://auth.openai.com";
    public static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    public static final String VERIFICATION_URL = ISSUER + "/codex/device";
    private static final String DEVICE_USER_CODE_URL = ISSUER + "/api/accounts/deviceauth/usercode";
    private static final String DEVICE_TOKEN_URL = ISSUER + "/api/accounts/deviceauth/token";
    private static final String OAUTH_TOKEN_URL = ISSUER + "/oauth/token";
    private static final String REDIRECT_URI = ISSUER + "/deviceauth/callback";
    private static final String LOG_TAG = "OrbitChatGptAuth";
    private static final long LOGIN_TIMEOUT_MS = 15L * 60L * 1000L;
    private static final ExecutorService EXEC = Executors.newCachedThreadPool();
    private static final Object LOGIN_LOCK = new Object();
    private static final List<WeakReference<LoginCallback>> LOGIN_CALLBACKS = new ArrayList<>();
    private static String activeDeviceAuthId = "";

    private ChatGptAuth() {}

    public interface StartCallback {
        void onSuccess(DeviceCode code);
        void onError(String message);
    }

    public interface LoginCallback {
        void onSuccess(AccountInfo account);
        void onError(String message);
    }

    public interface TokenCallback {
        void onSuccess(SecureStore.ChatGptTokens tokens);
        void onError(String message);
    }

    public static final class DeviceCode {
        public final String verificationUrl;
        public final String userCode;
        final String deviceAuthId;
        final long intervalSeconds;
        final long expiresAtMs;

        DeviceCode(String verificationUrl, String userCode, String deviceAuthId, long intervalSeconds,
                   long expiresAtMs) {
            this.verificationUrl = verificationUrl;
            this.userCode = userCode;
            this.deviceAuthId = deviceAuthId;
            this.intervalSeconds = Math.max(1L, intervalSeconds);
            this.expiresAtMs = expiresAtMs;
        }
    }

    public static final class AccountInfo {
        public final String accountId;
        public final String email;
        public final String plan;

        AccountInfo(String accountId, String email, String plan) {
            this.accountId = accountId == null ? "" : accountId;
            this.email = email == null ? "" : email;
            this.plan = plan == null ? "" : plan;
        }
    }

    public static void requestDeviceCode(Context context, StartCallback cb) {
        Context app = context.getApplicationContext();
        EXEC.execute(() -> {
            HttpURLConnection conn = null;
            try {
                logStage("device_code_request_started");
                conn = jsonConnection(DEVICE_USER_CODE_URL, "POST", 15000, 20000);
                JSONObject req = new JSONObject().put("client_id", CLIENT_ID);
                write(conn, req.toString(), "application/json");
                int code = conn.getResponseCode();
                String body = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
                if (code < 200 || code >= 300) {
                    logFailure("device_code_request_failed", "http_" + code);
                    cb.onError(deviceAuthError("Could not start ChatGPT sign-in", code, body));
                    return;
                }
                JSONObject o = new JSONObject(body);
                String deviceAuthId = o.optString("device_auth_id", "");
                String userCode = o.optString("user_code", o.optString("usercode", ""));
                long interval = parseLong(o.opt("interval"), 5L);
                if (deviceAuthId.isEmpty() || userCode.isEmpty()) {
                    logFailure("device_code_request_failed", "incomplete_response");
                    cb.onError("OpenAI returned an incomplete device sign-in response.");
                    return;
                }
                long expiresAtMs = System.currentTimeMillis() + LOGIN_TIMEOUT_MS;
                if (!persistPendingAttempt(
                        app, deviceAuthId, userCode, interval, expiresAtMs)) {
                    logFailure("device_code_request_failed", "pending_state_persistence");
                    cb.onError("Orbit could not securely save the pending ChatGPT sign-in. Try again.");
                    return;
                }
                logStage("device_code_received_and_persisted");
                cb.onSuccess(new DeviceCode(
                        VERIFICATION_URL, userCode, deviceAuthId, interval, expiresAtMs));
            } catch (Exception e) {
                logFailure("device_code_request_failed", exceptionKind(e));
                cb.onError("Could not start ChatGPT sign-in: " + cleanMessage(e));
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    /** Polls for up to 15 minutes, then exchanges the one-time authorization code for OAuth tokens. */
    public static void completeDeviceCode(Context context, DeviceCode dc, LoginCallback cb) {
        Context app = context.getApplicationContext();
        if (!registerAttempt(dc, cb)) return;
        EXEC.execute(() -> {
            logStage("poll_started_or_resumed");
            long deadline = dc.expiresAtMs;
            while (System.currentTimeMillis() < deadline) {
                if (!isActiveAttempt(dc.deviceAuthId)) return;
                HttpURLConnection conn = null;
                try {
                    conn = jsonConnection(DEVICE_TOKEN_URL, "POST", 15000, 20000);
                    JSONObject req = new JSONObject()
                            .put("device_auth_id", dc.deviceAuthId)
                            .put("user_code", dc.userCode);
                    write(conn, req.toString(), "application/json");
                    int code = conn.getResponseCode();
                    String body = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
                    if (code >= 200 && code < 300) {
                        JSONObject result = new JSONObject(body);
                        String authCode = result.optString("authorization_code", "");
                        String verifier = result.optString("code_verifier", "");
                        if (authCode.isEmpty() || verifier.isEmpty()) {
                            finishError(app, dc.deviceAuthId, "poll_response_incomplete",
                                    "OpenAI approved the sign-in but did not return the token exchange fields Orbit needs.");
                            return;
                        }
                        logStage("browser_authorization_confirmed");
                        while (System.currentTimeMillis() < deadline && isActiveAttempt(dc.deviceAuthId)) {
                            ExchangeResult exchangeResult = exchangeAuthorizationCode(
                                    app, dc.deviceAuthId, authCode, verifier);
                            if (exchangeResult.account != null) {
                                finishSuccess(app, dc.deviceAuthId, exchangeResult.account);
                                return;
                            }
                            if (!exchangeResult.retryable) {
                                finishError(app, dc.deviceAuthId, "token_exchange_failed",
                                        exchangeResult.message);
                                return;
                            }
                            logFailure("token_exchange_retry", exchangeResult.diagnostic);
                            if (!sleepFor(dc.intervalSeconds, deadline)) break;
                        }
                        break;
                    }
                    // This matches Codex's public device-code implementation: 403/404 mean keep polling.
                    if (code != 403 && code != 404 && code != 429 && (code < 500 || code >= 600)) {
                        finishError(app, dc.deviceAuthId, "poll_rejected_http_" + code,
                                deviceAuthError("ChatGPT sign-in failed", code, body));
                        return;
                    }
                    if (code == 429 || code >= 500) {
                        logFailure("poll_retry", "http_" + code);
                    }
                } catch (Exception e) {
                    // Mobile connectivity can briefly change while the browser owns the foreground.
                    // Keep the same pending device authorization alive until its real expiry.
                    logFailure("poll_retry", exceptionKind(e));
                } finally {
                    if (conn != null) conn.disconnect();
                }
                if (!sleepFor(dc.intervalSeconds, deadline)) break;
            }
            finishError(app, dc.deviceAuthId, "device_code_expired",
                    "The ChatGPT sign-in code expired. Start sign-in again to get a new code.");
        });
    }

    public static boolean resumePendingDeviceCode(Context context, LoginCallback cb) {
        if (isSignedIn(context)) {
            SecureStore.clearPendingChatGptLogin(context);
            return false;
        }
        SecureStore.PendingChatGptLogin pending = SecureStore.loadPendingChatGptLogin(context);
        if (pending == null) return false;
        logStage("pending_login_restored");
        completeDeviceCode(context, new DeviceCode(VERIFICATION_URL, pending.userCode,
                pending.deviceAuthId, pending.intervalSeconds, pending.expiresAtMs), cb);
        return true;
    }

    private static ExchangeResult exchangeAuthorizationCode(Context context, String deviceAuthId,
                                                              String authCode, String verifier) {
        HttpURLConnection conn = null;
        try {
            logStage("token_exchange_started");
            String form = formPair("grant_type", "authorization_code") +
                    "&" + formPair("code", authCode) +
                    "&" + formPair("redirect_uri", REDIRECT_URI) +
                    "&" + formPair("client_id", CLIENT_ID) +
                    "&" + formPair("code_verifier", verifier);
            conn = formConnection(OAUTH_TOKEN_URL, 15000, 30000);
            write(conn, form, "application/x-www-form-urlencoded");
            int code = conn.getResponseCode();
            String body = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
            if (code < 200 || code >= 300) {
                return ExchangeResult.error(oauthError("OpenAI token exchange failed", code, body),
                        code == 429 || code >= 500, "http_" + code);
            }
            JSONObject o = new JSONObject(body);
            String id = o.optString("id_token", "");
            String access = o.optString("access_token", "");
            String refresh = o.optString("refresh_token", "");
            if (access.isEmpty() || refresh.isEmpty()) {
                return ExchangeResult.error(
                        "OpenAI completed sign-in but did not return reusable OAuth credentials.",
                        false, "incomplete_credentials");
            }
            String accountId = firstNonEmpty(extractAccountId(id), extractAccountId(access));
            synchronized (LOGIN_LOCK) {
                if (!deviceAuthId.equals(activeDeviceAuthId)) {
                    return ExchangeResult.error("", false, "superseded_attempt");
                }
                if (!SecureStore.saveChatGptTokens(context, id, access, refresh, accountId)) {
                    return ExchangeResult.error(
                            "ChatGPT sign-in worked, but Orbit could not save the credentials in Android Keystore.",
                            false, "credential_persistence");
                }
                if (SecureStore.loadChatGptTokens(context) == null) {
                    return ExchangeResult.error(
                            "ChatGPT sign-in worked, but Orbit could not verify the saved credentials.",
                            false, "credential_reload");
                }
                Prefs.get(context).edit().putString(Prefs.PROVIDER, Prefs.PROVIDER_CHATGPT).apply();
            }
            logStage("credentials_persisted_and_verified");
            return ExchangeResult.success(accountInfo(id, access, accountId));
        } catch (Exception e) {
            return ExchangeResult.error("OpenAI token exchange failed: " + cleanMessage(e),
                    true, exceptionKind(e));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static boolean isSignedIn(Context context) {
        return SecureStore.loadChatGptTokens(context) != null;
    }

    public static AccountInfo getAccountInfo(Context context) {
        SecureStore.ChatGptTokens t = SecureStore.loadChatGptTokens(context);
        if (t == null) return null;
        return accountInfo(t.idToken, t.accessToken, t.accountId);
    }

    public static void logout(Context context) {
        synchronized (LOGIN_LOCK) {
            SecureStore.clearPendingChatGptLogin(context);
            SecureStore.clearChatGpt(context);
            activeDeviceAuthId = "";
            LOGIN_CALLBACKS.clear();
        }
    }

    /** Returns a valid access token, refreshing through OpenAI when it is near expiry. */
    public static void getValidTokens(Context context, boolean forceRefresh, TokenCallback cb) {
        SecureStore.ChatGptTokens t = SecureStore.loadChatGptTokens(context);
        if (t == null) {
            cb.onError("Sign in with ChatGPT in Orbit settings first.");
            return;
        }
        long exp = jwtLong(t.accessToken, "exp");
        long now = System.currentTimeMillis() / 1000L;
        if (!forceRefresh && (exp == 0L || exp > now + 120L)) {
            cb.onSuccess(t);
            return;
        }
        EXEC.execute(() -> refresh(context, t, cb));
    }

    private static void refresh(Context context, SecureStore.ChatGptTokens old, TokenCallback cb) {
        HttpURLConnection conn = null;
        try {
            String form = formPair("grant_type", "refresh_token") +
                    "&" + formPair("refresh_token", old.refreshToken) +
                    "&" + formPair("client_id", CLIENT_ID);
            conn = formConnection(OAUTH_TOKEN_URL, 15000, 30000);
            write(conn, form, "application/x-www-form-urlencoded");
            int code = conn.getResponseCode();
            String body = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
            if (code < 200 || code >= 300) {
                cb.onError(oauthError("ChatGPT session refresh failed. Sign out and sign in again if this persists", code, body));
                return;
            }
            JSONObject o = new JSONObject(body);
            String access = o.optString("access_token", old.accessToken);
            String refresh = o.optString("refresh_token", old.refreshToken); // Refresh tokens may rotate; always persist the newest one.
            String id = o.optString("id_token", old.idToken);
            String accountId = firstNonEmpty(extractAccountId(id), extractAccountId(access), old.accountId);
            if (access.isEmpty() || refresh.isEmpty()) {
                cb.onError("OpenAI returned an incomplete token refresh response.");
                return;
            }
            if (!SecureStore.saveChatGptTokens(context, id, access, refresh, accountId)) {
                cb.onError("Orbit could not securely save the refreshed ChatGPT session.");
                return;
            }
            SecureStore.ChatGptTokens updated = SecureStore.loadChatGptTokens(context);
            if (updated == null) cb.onError("Orbit could not reload the refreshed ChatGPT session.");
            else cb.onSuccess(updated);
        } catch (Exception e) {
            cb.onError("ChatGPT session refresh failed: " + cleanMessage(e));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static AccountInfo accountInfo(String idToken, String accessToken, String fallbackId) {
        JSONObject claims = jwtClaims(!empty(idToken) ? idToken : accessToken);
        String id = firstNonEmpty(extractAccountId(idToken), extractAccountId(accessToken), fallbackId);
        String email = claims == null ? "" : claims.optString("email", "");
        String plan = extractStringClaim(claims, "chatgpt_plan_type");
        return new AccountInfo(id, email, plan);
    }

    private static String extractAccountId(String jwt) {
        JSONObject claims = jwtClaims(jwt);
        if (claims == null) return "";
        String root = claims.optString("chatgpt_account_id", "");
        if (!root.isEmpty()) return root;
        Object auth = claims.opt("https://api.openai.com/auth");
        if (auth instanceof JSONObject) {
            String nested = ((JSONObject) auth).optString("chatgpt_account_id", "");
            if (!nested.isEmpty()) return nested;
        }
        String dotted = claims.optString("https://api.openai.com/auth.chatgpt_account_id", "");
        if (!dotted.isEmpty()) return dotted;
        return "";
    }

    private static String extractStringClaim(JSONObject claims, String key) {
        if (claims == null) return "";
        String root = claims.optString(key, "");
        if (!root.isEmpty()) return root;
        Object auth = claims.opt("https://api.openai.com/auth");
        if (auth instanceof JSONObject) return ((JSONObject) auth).optString(key, "");
        return claims.optString("https://api.openai.com/auth." + key, "");
    }

    private static JSONObject jwtClaims(String jwt) {
        if (jwt == null || jwt.isEmpty()) return null;
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return null;
            byte[] decoded = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            return new JSONObject(new String(decoded, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static long jwtLong(String jwt, String key) {
        JSONObject claims = jwtClaims(jwt);
        return claims == null ? 0L : claims.optLong(key, 0L);
    }

    private static HttpURLConnection jsonConnection(String url, String method, int connectMs, int readMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        c.setDoOutput(true);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("User-Agent", "OrbitAssistant/" + BuildConfig.VERSION_NAME + " (Android)");
        return c;
    }

    private static HttpURLConnection formConnection(String url, int connectMs, int readMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        c.setDoOutput(true);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setRequestProperty("User-Agent", "OrbitAssistant/" + BuildConfig.VERSION_NAME + " (Android)");
        return c;
    }

    private static void write(HttpURLConnection c, String body, String contentType) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        c.setRequestProperty("Content-Type", contentType);
        c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
    }

    static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) b.append(line).append('\n');
        }
        return b.toString().trim();
    }

    private static String formPair(String key, String value) throws Exception {
        return URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value == null ? "" : value, "UTF-8");
    }

    private static long parseLong(Object value, long def) {
        if (value == null) return def;
        try { return Long.parseLong(String.valueOf(value).trim()); }
        catch (Exception ignored) { return def; }
    }

    private static String deviceAuthError(String prefix, int code, String body) {
        if (code == 404) return prefix + ": device-code login may be disabled for this ChatGPT account or workspace.";
        return prefix + " (HTTP " + code + ")" + conciseBody(body);
    }

    private static String oauthError(String prefix, int code, String body) {
        return prefix + " (HTTP " + code + ")" + conciseBody(body);
    }

    private static String conciseBody(String body) {
        if (body == null || body.isEmpty()) return ".";
        try {
            JSONObject o = new JSONObject(body);
            String d = o.optString("error_description", "");
            if (!d.isEmpty()) return ": " + trim(d, 300);
            Object err = o.opt("error");
            if (err instanceof String) return ": " + trim((String) err, 300);
            if (err instanceof JSONObject) {
                String m = ((JSONObject) err).optString("message", "");
                if (!m.isEmpty()) return ": " + trim(m, 300);
            }
        } catch (Exception ignored) {}
        return ": " + trim(body.replace('\n', ' '), 300);
    }

    private static String cleanMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static boolean persistPendingAttempt(Context context, String deviceAuthId,
                                                 String userCode, long intervalSeconds,
                                                 long expiresAtMs) {
        synchronized (LOGIN_LOCK) {
            if (!SecureStore.savePendingChatGptLogin(
                    context, deviceAuthId, userCode, intervalSeconds, expiresAtMs)) {
                return false;
            }
            SecureStore.PendingChatGptLogin saved = SecureStore.loadPendingChatGptLogin(context);
            if (saved == null || !deviceAuthId.equals(saved.deviceAuthId)) {
                SecureStore.clearPendingChatGptLogin(context);
                return false;
            }
            if (!activeDeviceAuthId.isEmpty() && !deviceAuthId.equals(activeDeviceAuthId)) {
                activeDeviceAuthId = "";
                LOGIN_CALLBACKS.clear();
            }
            return true;
        }
    }

    private static boolean registerAttempt(DeviceCode dc, LoginCallback cb) {
        synchronized (LOGIN_LOCK) {
            for (int i = LOGIN_CALLBACKS.size() - 1; i >= 0; i--) {
                LoginCallback existing = LOGIN_CALLBACKS.get(i).get();
                if (existing == null) LOGIN_CALLBACKS.remove(i);
                else if (existing == cb) cb = null;
            }
            if (cb != null) LOGIN_CALLBACKS.add(new WeakReference<>(cb));
            if (dc.deviceAuthId.equals(activeDeviceAuthId)) return false;
            activeDeviceAuthId = dc.deviceAuthId;
            return true;
        }
    }

    private static boolean isActiveAttempt(String deviceAuthId) {
        synchronized (LOGIN_LOCK) {
            return deviceAuthId != null && deviceAuthId.equals(activeDeviceAuthId);
        }
    }

    private static void finishSuccess(Context context, String deviceAuthId, AccountInfo account) {
        List<LoginCallback> callbacks = takeCallbacks(context, deviceAuthId);
        if (callbacks == null) return;
        logStage("login_complete");
        for (LoginCallback callback : callbacks) {
            try { callback.onSuccess(account); }
            catch (RuntimeException e) { logFailure("ui_callback_failed", exceptionKind(e)); }
        }
    }

    private static void finishError(Context context, String deviceAuthId, String diagnostic,
                                    String message) {
        List<LoginCallback> callbacks = takeCallbacks(context, deviceAuthId);
        if (callbacks == null) return;
        logFailure("login_failed", diagnostic);
        for (LoginCallback callback : callbacks) {
            try { callback.onError(message); }
            catch (RuntimeException e) { logFailure("ui_callback_failed", exceptionKind(e)); }
        }
    }

    private static List<LoginCallback> takeCallbacks(Context context, String deviceAuthId) {
        synchronized (LOGIN_LOCK) {
            if (!deviceAuthId.equals(activeDeviceAuthId)) return null;
            SecureStore.clearPendingChatGptLogin(context);
            activeDeviceAuthId = "";
            List<LoginCallback> callbacks = new ArrayList<>();
            for (WeakReference<LoginCallback> ref : LOGIN_CALLBACKS) {
                LoginCallback callback = ref.get();
                if (callback != null) callbacks.add(callback);
            }
            LOGIN_CALLBACKS.clear();
            return callbacks;
        }
    }

    private static boolean sleepFor(long intervalSeconds, long deadlineMs) {
        long remaining = deadlineMs - System.currentTimeMillis();
        if (remaining <= 0L) return false;
        try {
            Thread.sleep(Math.min(remaining,
                    Math.min(15000L, Math.max(1000L, intervalSeconds * 1000L))));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void logStage(String stage) {
        Log.i(LOG_TAG, "Device sign-in stage=" + stage);
    }

    private static void logFailure(String stage, String diagnostic) {
        Log.w(LOG_TAG, "Device sign-in stage=" + stage + " diagnostic=" + diagnostic);
    }

    private static String exceptionKind(Exception e) {
        return e == null ? "unknown" : e.getClass().getSimpleName();
    }

    private static final class ExchangeResult {
        final AccountInfo account;
        final String message;
        final boolean retryable;
        final String diagnostic;

        private ExchangeResult(AccountInfo account, String message, boolean retryable,
                               String diagnostic) {
            this.account = account;
            this.message = message;
            this.retryable = retryable;
            this.diagnostic = diagnostic;
        }

        static ExchangeResult success(AccountInfo account) {
            return new ExchangeResult(account, "", false, "success");
        }

        static ExchangeResult error(String message, boolean retryable, String diagnostic) {
            return new ExchangeResult(null, message, retryable, diagnostic);
        }
    }

    private static String trim(String s, int n) { return s.length() <= n ? s : s.substring(0, n); }
    private static boolean empty(String s) { return s == null || s.isEmpty(); }
    private static String firstNonEmpty(String... xs) {
        if (xs != null) for (String x : xs) if (x != null && !x.isEmpty()) return x;
        return "";
    }
}
