package com.orbit.assistant;

import android.content.Context;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
    private static final ExecutorService EXEC = Executors.newCachedThreadPool();

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

        DeviceCode(String verificationUrl, String userCode, String deviceAuthId, long intervalSeconds) {
            this.verificationUrl = verificationUrl;
            this.userCode = userCode;
            this.deviceAuthId = deviceAuthId;
            this.intervalSeconds = Math.max(1L, intervalSeconds);
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

    public static void requestDeviceCode(StartCallback cb) {
        EXEC.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = jsonConnection(DEVICE_USER_CODE_URL, "POST", 15000, 20000);
                JSONObject req = new JSONObject().put("client_id", CLIENT_ID);
                write(conn, req.toString(), "application/json");
                int code = conn.getResponseCode();
                String body = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
                if (code < 200 || code >= 300) {
                    cb.onError(deviceAuthError("Could not start ChatGPT sign-in", code, body));
                    return;
                }
                JSONObject o = new JSONObject(body);
                String deviceAuthId = o.optString("device_auth_id", "");
                String userCode = o.optString("user_code", o.optString("usercode", ""));
                long interval = parseLong(o.opt("interval"), 5L);
                if (deviceAuthId.isEmpty() || userCode.isEmpty()) {
                    cb.onError("OpenAI returned an incomplete device sign-in response.");
                    return;
                }
                cb.onSuccess(new DeviceCode(VERIFICATION_URL, userCode, deviceAuthId, interval));
            } catch (Exception e) {
                cb.onError("Could not start ChatGPT sign-in: " + cleanMessage(e));
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    /** Polls for up to 15 minutes, then exchanges the one-time authorization code for OAuth tokens. */
    public static void completeDeviceCode(Context context, DeviceCode dc, LoginCallback cb) {
        EXEC.execute(() -> {
            long deadline = System.currentTimeMillis() + 15L * 60L * 1000L;
            while (System.currentTimeMillis() < deadline) {
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
                            cb.onError("OpenAI approved the sign-in but did not return the token exchange fields Orbit needs.");
                            return;
                        }
                        exchangeAuthorizationCode(context, authCode, verifier, cb);
                        return;
                    }
                    // This matches Codex's public device-code implementation: 403/404 mean keep polling.
                    if (code != 403 && code != 404) {
                        cb.onError(deviceAuthError("ChatGPT sign-in failed", code, body));
                        return;
                    }
                } catch (Exception e) {
                    cb.onError("ChatGPT sign-in failed: " + cleanMessage(e));
                    return;
                } finally {
                    if (conn != null) conn.disconnect();
                }
                try {
                    Thread.sleep(Math.min(15000L, Math.max(1000L, dc.intervalSeconds * 1000L)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cb.onError("ChatGPT sign-in was cancelled.");
                    return;
                }
            }
            cb.onError("The ChatGPT sign-in code expired. Start sign-in again to get a new code.");
        });
    }

    private static void exchangeAuthorizationCode(Context context, String authCode, String verifier, LoginCallback cb) {
        HttpURLConnection conn = null;
        try {
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
                cb.onError(oauthError("OpenAI token exchange failed", code, body));
                return;
            }
            JSONObject o = new JSONObject(body);
            String id = o.optString("id_token", "");
            String access = o.optString("access_token", "");
            String refresh = o.optString("refresh_token", "");
            if (access.isEmpty() || refresh.isEmpty()) {
                cb.onError("OpenAI completed sign-in but did not return reusable OAuth credentials.");
                return;
            }
            String accountId = firstNonEmpty(extractAccountId(id), extractAccountId(access));
            if (!SecureStore.saveChatGptTokens(context, id, access, refresh, accountId)) {
                cb.onError("ChatGPT sign-in worked, but Orbit could not save the credentials in Android Keystore.");
                return;
            }
            Prefs.get(context).edit().putString(Prefs.PROVIDER, Prefs.PROVIDER_CHATGPT).apply();
            cb.onSuccess(accountInfo(id, access, accountId));
        } catch (Exception e) {
            cb.onError("OpenAI token exchange failed: " + cleanMessage(e));
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
        SecureStore.clearChatGpt(context);
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

    private static String trim(String s, int n) { return s.length() <= n ? s : s.substring(0, n); }
    private static boolean empty(String s) { return s == null || s.isEmpty(); }
    private static String firstNonEmpty(String... xs) {
        if (xs != null) for (String x : xs) if (x != null && !x.isEmpty()) return x;
        return "";
    }
}
