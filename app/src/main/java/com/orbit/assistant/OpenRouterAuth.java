package com.orbit.assistant;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Sign in with OpenRouter (0.8.3.0-beta.6+): OpenRouter's documented OAuth PKCE flow, which ends
 * with a user-controlled OpenRouter API key that Orbit stores like a key typed by hand.
 *
 * <p>Checked against OpenRouter's OAuth PKCE guide on 2026-10-02: the browser opens
 * {@code https://openrouter.ai/auth} with {@code callback_url}, {@code code_challenge} and
 * {@code code_challenge_method=S256}; OpenRouter redirects to the callback with {@code ?code=};
 * the code is exchanged once at {@code POST https://openrouter.ai/api/v1/auth/keys} with the
 * verifier, and expires after ten minutes. {@code http://localhost:<port>} on any port is a
 * documented callback form; custom URI schemes are not, so Orbit does not use one.
 *
 * <p>The flow, once per tap of Sign in with OpenRouter:
 * <ol>
 *   <li>A fresh 86-character verifier and its S256 challenge, and a fresh 256-bit state, all from
 *       {@link SecureRandom} (the same primitives as {@link ChatGptBrowserAuth}).</li>
 *   <li>A receiver on an ephemeral port chosen by Android, bound to loopback only ({@code
 *       127.0.0.1}, and {@code ::1} on the same port when the phone allows it, because a browser
 *       may resolve {@code localhost} to either). Nothing off the phone can reach it.</li>
 *   <li>OpenRouter documents no {@code state} parameter, so the state travels in the callback
 *       <em>path</em>: {@code http://localhost:<port>/orbit/openrouter/<state>}. A request to any
 *       other path, including a stale tab from an earlier attempt, is refused without ending the
 *       attempt.</li>
 *   <li>The first callback with the right state and a code ends the receiver, then exchanges the
 *       code exactly once. The key goes straight into {@link SecureStore}; it is never logged,
 *       shown, copied, backed up, or put in diagnostics or a chat.</li>
 * </ol>
 *
 * <p>The verifier and state live only in memory. If Android ends Orbit's process while the browser
 * is open, the receiver ends with it and the redirect cannot connect; the user taps again. Keeping
 * a sign-in secret on disk to survive that would buy nothing, because the receiver cannot survive
 * it either. Recreating the screen is fine: the attempt is held here, and {@link #attach} hands its
 * outcome to the new screen.
 */
public final class OpenRouterAuth {
    static final String AUTHORIZE_URL = "https://openrouter.ai/auth";
    static final String EXCHANGE_URL = "https://openrouter.ai/api/v1/auth/keys";
    /** The callback path before the state. */
    static final String CALLBACK_PREFIX = "/orbit/openrouter/";
    /** Prefills the key's label on OpenRouter's consent page, so the user can find it later. */
    static final String KEY_LABEL = "Orbit Assistant";
    /** OpenRouter's authorization codes expire after ten minutes, so Orbit waits no longer. */
    static final long TIMEOUT_MS = 10L * 60L * 1000L;

    /** What the screen hears. Delivered on the main thread, exactly once per attempt. */
    public interface Listener {
        void onConnected();
        void onFailed(String message);
        /** The attempt ended because the user or a newer attempt replaced it. Nothing to show. */
        void onCancelled();
    }

    /** The outcome of exchanging one code. */
    static final class Exchange {
        final String key;
        final String message;

        private Exchange(String key, String message) {
            this.key = key == null ? "" : key;
            this.message = message == null ? "" : message;
        }

        static Exchange success(String key) { return new Exchange(key, ""); }
        static Exchange failure(String message) { return new Exchange("", message); }

        boolean ok() { return !key.isEmpty(); }
    }

    /** Exchanges a code for a key. Replaced in tests; never a different endpoint in production. */
    interface Exchanger {
        Exchange exchange(String code, String verifier);
    }

    private static final Object LOCK = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static Attempt active;
    private static Exchanger exchanger = OpenRouterAuth::exchangeCode;
    private static Delivery undelivered;

    private OpenRouterAuth() {}

    // ---- URLs ----------------------------------------------------------------------------------

    static String callbackUrl(int port, String state) {
        return "http://localhost:" + port + CALLBACK_PREFIX + state;
    }

    static String authorizeUrl(String callbackUrl, String challenge) {
        return AUTHORIZE_URL
                + "?callback_url=" + enc(callbackUrl)
                + "&code_challenge=" + enc(challenge)
                + "&code_challenge_method=S256"
                + "&key_label=" + enc(KEY_LABEL);
    }

    private static String enc(String value) {
        try { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
        catch (Exception e) { return ""; }
    }

    // ---- callback parsing ----------------------------------------------------------------------

    enum Outcome {
        /** The right state and a code. */
        CODE,
        /** The right state, and OpenRouter reported that authorization did not happen. */
        DENIED,
        /** A callback path whose state is not this attempt's: stale tab, earlier attempt, forgery. */
        STATE_MISMATCH,
        /** The right state but no code. */
        MISSING_CODE,
        /** Anything that is not a callback at all, such as a favicon request. */
        NOT_CALLBACK
    }

    static final class Callback {
        final Outcome outcome;
        final String code;

        Callback(Outcome outcome, String code) {
            this.outcome = outcome;
            this.code = code == null ? "" : code;
        }
    }

    /**
     * Reads one request target such as {@code /orbit/openrouter/<state>?code=...}. The state is
     * compared in constant time and must match exactly; nothing about the request is believed
     * until it has.
     */
    static Callback parseCallback(String target, String expectedState) {
        Uri uri;
        try { uri = Uri.parse("http://localhost" + (target == null ? "/" : target)); }
        catch (Exception e) { return new Callback(Outcome.NOT_CALLBACK, ""); }
        String path = uri.getPath();
        if (path == null || !path.startsWith(CALLBACK_PREFIX)) {
            return new Callback(Outcome.NOT_CALLBACK, "");
        }
        String state = path.substring(CALLBACK_PREFIX.length());
        if (expectedState == null || expectedState.isEmpty() || !MessageDigest.isEqual(
                state.getBytes(StandardCharsets.UTF_8),
                expectedState.getBytes(StandardCharsets.UTF_8))) {
            return new Callback(Outcome.STATE_MISMATCH, "");
        }
        String error = uri.getQueryParameter("error");
        if (error != null && !error.trim().isEmpty()) return new Callback(Outcome.DENIED, "");
        String code = uri.getQueryParameter("code");
        if (code == null || code.trim().isEmpty()) return new Callback(Outcome.MISSING_CODE, "");
        return new Callback(Outcome.CODE, code.trim());
    }

    // ---- attempts ------------------------------------------------------------------------------

    /** One sign-in. Owns its receivers, verifier and state, and ends exactly once. */
    static final class Attempt {
        final Context app;
        final ChatGptBrowserAuth.Pkce pkce;
        final String state;
        final ServerSocket server;
        /** The same port on ::1, or null when the phone has no IPv6 loopback. */
        final ServerSocket server6;
        final String callbackUrl;
        final String authorizeUrl;
        final long deadline;
        Listener listener;
        boolean ended;

        Attempt(Context app, ChatGptBrowserAuth.Pkce pkce, String state, ServerSocket server,
                ServerSocket server6, long deadline) {
            this.app = app;
            this.pkce = pkce;
            this.state = state;
            this.server = server;
            this.server6 = server6;
            this.callbackUrl = callbackUrl(server.getLocalPort(), state);
            this.authorizeUrl = authorizeUrl(callbackUrl, pkce.challenge);
            this.deadline = deadline;
        }
    }

    /** What {@link #start} hands back: the page to open, or why there is none. */
    public static final class Started {
        public final String authorizeUrl;
        public final String error;

        Started(String authorizeUrl, String error) {
            this.authorizeUrl = authorizeUrl;
            this.error = error;
        }

        public boolean ok() { return authorizeUrl != null; }
    }

    /**
     * Starts a fresh attempt and returns the page for the caller to open in the system browser.
     * Any earlier attempt ends first, so exactly one sign-in is ever waiting.
     */
    public static Started start(Context context, Listener listener) {
        return start(context, listener, TIMEOUT_MS);
    }

    /** As above with a chosen timeout; tests use a short one to prove the receiver closes. */
    static Started start(Context context, Listener listener, long timeoutMs) {
        Context app = context.getApplicationContext();
        cancel();
        ServerSocket server = bindLoopback();
        if (server == null) {
            return new Started(null, "Orbit could not open its local sign-in receiver on this "
                    + "phone, so the browser sign-in cannot finish. Use an API key instead.");
        }
        ServerSocket server6 = bindIpv6Loopback(server.getLocalPort());
        Attempt attempt = new Attempt(app, ChatGptBrowserAuth.newPkce(RANDOM),
                ChatGptBrowserAuth.newState(RANDOM), server, server6,
                System.currentTimeMillis() + timeoutMs);
        attempt.listener = listener;
        synchronized (LOCK) {
            active = attempt;
            undelivered = null;
        }
        startServing(attempt, attempt.server, "orbit-openrouter-signin");
        if (server6 != null) startServing(attempt, server6, "orbit-openrouter-signin-6");
        return new Started(attempt.authorizeUrl, null);
    }

    private static void startServing(Attempt attempt, ServerSocket socket, String name) {
        Thread thread = new Thread(() -> serve(attempt, socket), name);
        thread.setDaemon(true);
        thread.start();
    }

    /** IPv4 loopback on a port Android picks: unpredictable, and never a LAN address. */
    private static ServerSocket bindLoopback() {
        ServerSocket socket = null;
        try {
            socket = new ServerSocket();
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4);
            return socket;
        } catch (IOException e) {
            closeQuietly(socket);
            return null;
        }
    }

    /** IPv6 loopback on the same port, best effort; the IPv4 receiver is enough on its own. */
    private static ServerSocket bindIpv6Loopback(int port) {
        ServerSocket socket = null;
        try {
            socket = new ServerSocket();
            socket.bind(new InetSocketAddress(InetAddress.getByName("::1"), port), 4);
            return socket;
        } catch (Exception e) {
            closeQuietly(socket);
            return null;
        }
    }

    /** True while an attempt is waiting for the browser. */
    public static boolean inProgress() {
        synchronized (LOCK) { return active != null && !active.ended; }
    }

    /** Points the active attempt's outcome at a new screen; delivers one that arrived meanwhile. */
    public static void attach(Listener listener) {
        Delivery pending;
        synchronized (LOCK) {
            if (active != null && !active.ended) active.listener = listener;
            pending = undelivered;
            if (listener != null) undelivered = null;
        }
        if (pending != null && listener != null) pending.to(listener);
    }

    /** Stops listening without ending the attempt, e.g. when the screen pauses. */
    public static void detach(Listener listener) {
        synchronized (LOCK) {
            if (active != null && active.listener == listener) active.listener = null;
        }
    }

    /** Ends any attempt now and closes its receivers. Safe to call at any time. */
    public static void cancel() {
        Attempt attempt;
        synchronized (LOCK) { attempt = active; }
        if (attempt != null) finish(attempt, Listener::onCancelled, true);
    }

    // ---- serving -------------------------------------------------------------------------------

    private static void serve(Attempt attempt, ServerSocket server) {
        try {
            while (!isEnded(attempt)) {
                long remaining = attempt.deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    finish(attempt, l -> l.onFailed("The OpenRouter sign-in timed out before the "
                            + "browser came back to Orbit. Try again, or use an API key instead."),
                            false);
                    return;
                }
                server.setSoTimeout((int) Math.min(remaining, 2000L));
                Socket socket;
                try {
                    socket = server.accept();
                } catch (SocketTimeoutException timeout) {
                    continue;
                }
                try {
                    handle(attempt, socket);
                } finally {
                    closeQuietly(socket);
                }
            }
        } catch (IOException e) {
            // Closed by cancel/finish, or failed. Only the main receiver failing ends the attempt.
            if (!isEnded(attempt) && server == attempt.server) {
                finish(attempt, l -> l.onFailed("Orbit's local sign-in receiver stopped before "
                        + "the browser came back. Try again, or use an API key instead."), false);
            }
        }
    }

    private static void handle(Attempt attempt, Socket socket) throws IOException {
        socket.setSoTimeout(5000);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                StandardCharsets.US_ASCII));
        String requestLine = in.readLine();
        String target = "/";
        if (requestLine != null) {
            String[] parts = requestLine.split(" ");
            if (parts.length >= 2) target = parts[1];
        }
        writeResponse(socket.getOutputStream(), respond(attempt, target));
    }

    /** One HTTP response: status and page. */
    static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    /** What one request to the receiver means for the attempt, and what the browser sees. */
    static Response respond(Attempt attempt, String target) {
        Callback callback = parseCallback(target, attempt.state);
        switch (callback.outcome) {
            case NOT_CALLBACK:
                return new Response(404, page("Not found",
                        "This address is only used to finish connecting OpenRouter to Orbit.", false));
            case STATE_MISMATCH:
                // Refused without ending the attempt: an old tab or a stray request must not be
                // able to cancel the sign-in the user is actually completing.
                return new Response(400, page("This sign-in page is out of date",
                        "Return to Orbit and tap Sign in with OpenRouter again.", true));
            case DENIED:
                finish(attempt, l -> l.onFailed("OpenRouter did not authorize Orbit. You can try "
                        + "again, or use an API key instead."), false);
                return new Response(200, page("OpenRouter was not connected",
                        "Return to Orbit to try again.", true));
            case MISSING_CODE:
                finish(attempt, l -> l.onFailed("OpenRouter returned to Orbit without an "
                        + "authorization code. Try again, or use an API key instead."), false);
                return new Response(400, page("OpenRouter was not connected",
                        "Return to Orbit to try again.", true));
            case CODE:
            default:
                // The receivers' job is done; nothing else may arrive. The code is used once: the
                // attempt is claimed atomically, so a replay, or the same callback reaching both
                // loopback receivers, can never exchange a second time.
                if (!claim(attempt)) {
                    return new Response(400, page("This sign-in already finished",
                            "Return to Orbit.", true));
                }
                closeQuietly(attempt.server);
                closeQuietly(attempt.server6);
                Exchange result = exchanger.exchange(callback.code, attempt.pkce.verifier);
                if (result.ok() && SecureStore.saveOpenRouterKey(attempt.app, result.key,
                        SecureStore.OPENROUTER_SOURCE_OAUTH)) {
                    deliver(attempt, Listener::onConnected, false);
                    return new Response(200, page("OpenRouter is connected",
                            "Your OpenRouter account is connected to Orbit. You can return to Orbit.",
                            true));
                }
                String message = result.ok()
                        ? "Orbit could not store the OpenRouter key securely on this phone, so it was not saved."
                        : result.message;
                deliver(attempt, l -> l.onFailed(message), false);
                return new Response(200, page("Orbit could not finish connecting",
                        "Return to Orbit for details.", true));
        }
    }

    // ---- the code exchange -----------------------------------------------------------------------

    /**
     * {@code POST /api/v1/auth/keys} with the code, the verifier and S256. Over HTTPS only, with no
     * redirects followed. Nothing from the request or the response is logged; failures become a
     * short sentence without the server's raw body.
     */
    static Exchange exchangeCode(String code, String verifier) {
        HttpURLConnection conn = null;
        try {
            JSONObject body = new JSONObject().put("code", code).put("code_verifier", verifier)
                    .put("code_challenge_method", "S256");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            conn = (HttpURLConnection) new URL(EXCHANGE_URL).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = conn.getOutputStream()) { out.write(bytes); }
            int status = conn.getResponseCode();
            String raw = read(status >= 200 && status < 300
                    ? conn.getInputStream() : conn.getErrorStream());
            return interpretExchange(status, raw);
        } catch (Exception e) {
            return Exchange.failure("Orbit could not reach OpenRouter to finish connecting. "
                    + "Check the connection and try again.");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** The exchange's answer, as OpenRouter documents it. Split out so tests need no network. */
    static Exchange interpretExchange(int status, String raw) {
        if (status >= 200 && status < 300) {
            String key = "";
            try { key = new JSONObject(raw == null ? "" : raw).optString("key", "").trim(); }
            catch (Exception ignored) {}
            return key.isEmpty()
                    ? Exchange.failure("OpenRouter finished signing in but returned no key. Try again.")
                    : Exchange.success(key);
        }
        if (status == 403) {
            return Exchange.failure("OpenRouter refused the sign-in code. It may have expired or "
                    + "already been used. Try signing in again.");
        }
        if (status == 400) {
            return Exchange.failure("OpenRouter rejected the sign-in request. Try signing in again.");
        }
        if (status == 429) {
            return Exchange.failure("OpenRouter is rate limiting sign-ins. Wait a moment and try again.");
        }
        return Exchange.failure("OpenRouter could not finish connecting right now. Try again shortly.");
    }

    private static String read(InputStream input) throws IOException {
        if (input == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input,
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && out.length() < 16_000) out.append(line);
        }
        return out.toString();
    }

    // ---- the browser page ------------------------------------------------------------------------

    private static String page(String title, String message, boolean returnButton) {
        String button = returnButton ? "<p><a class=\"b\" href=\""
                + ChatGptBrowserAuth.RETURN_URI + "\">Return to Orbit</a></p>" : "";
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Orbit</title><style>body{font-family:sans-serif;background:#111;color:#eee;"
                + "display:flex;min-height:90vh;align-items:center;justify-content:center;margin:0;padding:24px}"
                + "main{max-width:420px;text-align:center}h1{font-size:22px}p{color:#bbb;line-height:1.5}"
                + "a.b{display:inline-block;margin-top:12px;padding:14px 22px;border-radius:14px;"
                + "background:#8b7cff;color:#111;text-decoration:none;font-weight:600}</style></head>"
                + "<body><main><h1>" + title + "</h1><p>" + message + "</p>" + button
                + "</main></body></html>";
    }

    private static void writeResponse(OutputStream out, Response response) throws IOException {
        byte[] body = response.body.getBytes(StandardCharsets.UTF_8);
        String reason = response.status == 200 ? "OK" : response.status == 404 ? "Not Found" : "Bad Request";
        String head = "HTTP/1.1 " + response.status + " " + reason + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Referrer-Policy: no-referrer\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    // ---- ending --------------------------------------------------------------------------------

    private interface Delivery {
        void to(Listener listener);
    }

    private static boolean isEnded(Attempt attempt) {
        synchronized (LOCK) { return attempt.ended; }
    }

    /** Ends the attempt and returns true, or returns false when it had already ended. */
    private static boolean claim(Attempt attempt) {
        synchronized (LOCK) {
            if (attempt.ended) return false;
            attempt.ended = true;
            return true;
        }
    }

    /** Ends an attempt that has not ended yet: closes its receivers, then tells the screen once. */
    private static void finish(Attempt attempt, Delivery delivery, boolean cancelled) {
        synchronized (LOCK) {
            if (attempt.ended) return;
            attempt.ended = true;
        }
        closeQuietly(attempt.server);
        closeQuietly(attempt.server6);
        deliver(attempt, delivery, cancelled);
    }

    /** Tells whichever screen is attached, on the main thread; keeps it if nobody is. */
    private static void deliver(Attempt attempt, Delivery delivery, boolean cancelled) {
        Listener listener;
        synchronized (LOCK) {
            if (active == attempt) active = null;
            listener = attempt.listener;
            attempt.listener = null;
        }
        if (listener == null) {
            if (!cancelled) {
                synchronized (LOCK) { undelivered = delivery; }
            }
            return;
        }
        Runnable run = () -> delivery.to(listener);
        if (Looper.myLooper() == Looper.getMainLooper()) run.run();
        else new Handler(Looper.getMainLooper()).post(run);
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try { closeable.close(); } catch (Exception ignored) {}
    }

    // ---- for tests -----------------------------------------------------------------------------

    static Exchanger installExchangerForTest(Exchanger replacement) {
        Exchanger previous = exchanger;
        exchanger = replacement == null ? OpenRouterAuth::exchangeCode : replacement;
        return previous;
    }

    static Attempt activeForTest() {
        synchronized (LOCK) { return active; }
    }
}
