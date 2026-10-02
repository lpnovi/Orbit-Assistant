package com.orbit.assistant;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * ChatGPT sign-in through the normal browser: OAuth 2.0 authorization code with PKCE (S256) and a
 * loopback callback, the same public client and callback shape OpenAI's own Codex CLI uses.
 *
 * <p>The flow, once per tap of Sign in with ChatGPT:
 * <ol>
 *   <li>A fresh code verifier and state, both from {@link SecureRandom}.</li>
 *   <li>A receiver on {@code 127.0.0.1:1455} (1457 if that port is busy), bound to loopback only,
 *       so nothing off the phone can reach it.</li>
 *   <li>The system browser opens OpenAI's authorize page; the user signs in normally.</li>
 *   <li>OpenAI redirects the browser to {@code /auth/callback}. The state must match exactly; a
 *       stale or forged callback is refused and the attempt keeps waiting for the real one.</li>
 *   <li>The code is exchanged and stored through {@link ChatGptAuth#storeSignIn}: the same Android
 *       Keystore storage, refresh and sign-out the code sign-in has always used.</li>
 *   <li>The browser page offers a Return to Orbit button.</li>
 * </ol>
 *
 * <p>The receiver exists only while an attempt does. It closes on success, on any failure, on
 * cancel, on sign-out, when a newer attempt starts, and after {@link #TIMEOUT_MS}. If anything
 * fails, Orbit says what happened and offers code sign-in; it never switches methods on its own.
 *
 * <p>The verifier lives only in memory. If Android ends Orbit's process while the browser is open,
 * the receiver goes with it and the browser's redirect cannot connect; the user starts again. That
 * is deliberate: persisting a verifier to resume across process death would keep a sign-in secret
 * on disk for something a second tap recovers.
 */
public final class ChatGptBrowserAuth {
    static final String AUTHORIZE_URL = ChatGptAuth.ISSUER + "/oauth/authorize";
    static final String CALLBACK_PATH = "/auth/callback";
    static final int PORT = 1455;
    static final int FALLBACK_PORT = 1457;
    static final String SCOPE = "openid profile email offline_access";
    /** Codex's public client is used with Codex's originator, as the authorize page expects. */
    static final String ORIGINATOR = "codex_cli_rs";
    /** How long Orbit waits for the browser to come back before ending the attempt. */
    static final long TIMEOUT_MS = 10L * 60L * 1000L;
    /** Where the success page's button returns the user. Handled by {@link ChatGptSignInReturnActivity}. */
    static final String RETURN_URI = "orbit-assistant://chatgpt-signin";

    /** What the UI hears. Delivered on the main thread, exactly once per attempt. */
    public interface Listener {
        void onSignedIn(ChatGptAuth.AccountInfo account);
        /** {@code offerCodeSignIn} is always true for a real failure: the fallback is the remedy. */
        void onFailed(String message, boolean offerCodeSignIn);
        /** The attempt ended because the user or a newer attempt replaced it. Nothing to show. */
        void onCancelled();
    }

    /** Exchanges a code for credentials. Replaced in tests; never a different method in production. */
    interface Exchanger {
        ChatGptAuth.BrowserExchange exchange(Context context, String code, String verifier,
                                             String redirectUri);
    }

    private static final Object LOCK = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static Attempt active;
    private static Exchanger exchanger = ChatGptAuth::exchangeBrowserCode;
    /** A finished attempt's outcome, kept until a screen is attached to hear it. */
    private static Delivery undelivered;

    private ChatGptBrowserAuth() {}

    // ---- PKCE and state ------------------------------------------------------------------------

    static final class Pkce {
        final String verifier;
        final String challenge;

        Pkce(String verifier) {
            this.verifier = verifier;
            this.challenge = challengeFor(verifier);
        }
    }

    /** 64 random bytes, base64url without padding: an 86-character RFC 7636 verifier. */
    static Pkce newPkce(SecureRandom random) {
        byte[] bytes = new byte[64];
        random.nextBytes(bytes);
        return new Pkce(base64Url(bytes));
    }

    /** BASE64URL(SHA-256(ASCII(verifier))), the S256 challenge. */
    static String challengeFor(String verifier) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return base64Url(sha.digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 32 random bytes, base64url: unguessable, and different for every attempt. */
    static String newState(SecureRandom random) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return base64Url(bytes);
    }

    static String base64Url(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    static String redirectUri(int port) {
        return "http://127.0.0.1:" + port + CALLBACK_PATH;
    }

    static String authorizeUrl(String redirectUri, String challenge, String state) {
        return AUTHORIZE_URL
                + "?response_type=code"
                + "&client_id=" + enc(ChatGptAuth.CLIENT_ID)
                + "&redirect_uri=" + enc(redirectUri)
                + "&scope=" + enc(SCOPE)
                + "&code_challenge=" + enc(challenge)
                + "&code_challenge_method=S256"
                + "&id_token_add_organizations=true"
                + "&codex_cli_simplified_flow=true"
                + "&state=" + enc(state)
                + "&originator=" + enc(ORIGINATOR);
    }

    private static String enc(String value) {
        try { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
        catch (Exception e) { return ""; }
    }

    // ---- callback parsing ----------------------------------------------------------------------

    enum Outcome {
        /** Valid callback carrying a code. */
        CODE,
        /** OpenAI reported an error (the user declined, the account cannot sign in, ...). */
        OAUTH_ERROR,
        /** The state did not match this attempt: stale tab, earlier attempt, or forgery. */
        STATE_MISMATCH,
        /** State matched but there was no code. */
        MISSING_CODE,
        /** Anything that is not the callback path, such as a favicon request. */
        NOT_CALLBACK
    }

    static final class Callback {
        final Outcome outcome;
        final String code;
        final String error;

        Callback(Outcome outcome, String code, String error) {
            this.outcome = outcome;
            this.code = code == null ? "" : code;
            this.error = error == null ? "" : error;
        }
    }

    /**
     * Reads one request target such as {@code /auth/callback?code=...&state=...}. The state is
     * compared in constant time and must match exactly; an error is only believed once the state
     * shows it belongs to this attempt.
     */
    static Callback parseCallback(String target, String expectedState) {
        Uri uri;
        try { uri = Uri.parse("http://127.0.0.1" + (target == null ? "/" : target)); }
        catch (Exception e) { return new Callback(Outcome.NOT_CALLBACK, "", ""); }
        if (!CALLBACK_PATH.equals(uri.getPath())) return new Callback(Outcome.NOT_CALLBACK, "", "");
        String state = uri.getQueryParameter("state");
        if (state == null || expectedState == null || !MessageDigest.isEqual(
                state.getBytes(StandardCharsets.UTF_8), expectedState.getBytes(StandardCharsets.UTF_8))) {
            return new Callback(Outcome.STATE_MISMATCH, "", "");
        }
        String error = uri.getQueryParameter("error");
        if (error != null && !error.trim().isEmpty()) {
            String description = uri.getQueryParameter("error_description");
            return new Callback(Outcome.OAUTH_ERROR, "",
                    description == null || description.trim().isEmpty() ? error : description);
        }
        String code = uri.getQueryParameter("code");
        if (code == null || code.trim().isEmpty()) return new Callback(Outcome.MISSING_CODE, "", "");
        return new Callback(Outcome.CODE, code, "");
    }

    // ---- attempts ------------------------------------------------------------------------------

    /** One sign-in. Owns its receiver, verifier and state, and ends exactly once. */
    static final class Attempt {
        final Context app;
        final Pkce pkce;
        final String state;
        final ServerSocket server;
        final String redirectUri;
        final String authorizeUrl;
        final long deadline;
        Listener listener;
        boolean ended;

        Attempt(Context app, Pkce pkce, String state, ServerSocket server, long deadline) {
            this.app = app;
            this.pkce = pkce;
            this.state = state;
            this.server = server;
            this.redirectUri = redirectUri(server.getLocalPort());
            this.authorizeUrl = authorizeUrl(redirectUri, pkce.challenge, state);
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
     * Starts a fresh attempt and returns the authorize URL for the caller to open in the system
     * browser. Any earlier browser attempt and any pending code sign-in end first, so exactly one
     * sign-in is ever waiting.
     */
    public static Started start(Context context, Listener listener) {
        return start(context, listener, TIMEOUT_MS);
    }

    /** As above with a chosen timeout; tests use a short one to prove the receiver closes. */
    static Started start(Context context, Listener listener, long timeoutMs) {
        Context app = context.getApplicationContext();
        cancel();
        ChatGptAuth.cancelPendingDeviceCode(app);
        ServerSocket server = bind();
        if (server == null) {
            ChatGptAuth.logFailure("browser_receiver_failed", "ports_busy");
            return new Started(null, "Orbit could not open its local sign-in receiver on this "
                    + "phone, so the browser sign-in cannot finish. Use code sign-in instead.");
        }
        Attempt attempt = new Attempt(app, newPkce(RANDOM), newState(RANDOM), server,
                System.currentTimeMillis() + timeoutMs);
        attempt.listener = listener;
        synchronized (LOCK) {
            active = attempt;
            undelivered = null;
        }
        Thread thread = new Thread(() -> serve(attempt), "orbit-chatgpt-signin");
        thread.setDaemon(true);
        thread.start();
        ChatGptAuth.logStage("browser_attempt_started");
        return new Started(attempt.authorizeUrl, null);
    }

    /** Loopback only, on the registered ports. Null when neither can be bound. */
    private static ServerSocket bind() {
        for (int port : new int[]{PORT, FALLBACK_PORT}) {
            ServerSocket socket = null;
            try {
                socket = new ServerSocket();
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 4);
                return socket;
            } catch (IOException e) {
                closeQuietly(socket);
            }
        }
        return null;
    }

    /** True while an attempt is waiting for the browser. */
    public static boolean inProgress() {
        synchronized (LOCK) { return active != null && !active.ended; }
    }

    /**
     * Points the active attempt's outcome at a new screen, for example after the activity that
     * started it was recreated. An outcome that arrived while no screen was listening is delivered
     * now.
     */
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

    /** Ends any attempt now and closes its receiver. Safe to call at any time. */
    public static void cancel() {
        Attempt attempt;
        synchronized (LOCK) { attempt = active; }
        if (attempt != null) finish(attempt, l -> l.onCancelled(), "cancelled");
    }

    // ---- serving -------------------------------------------------------------------------------

    private static void serve(Attempt attempt) {
        try {
            while (!isEnded(attempt)) {
                long remaining = attempt.deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    finish(attempt, l -> l.onFailed("The ChatGPT sign-in timed out before the "
                            + "browser came back to Orbit. Try again, or use code sign-in.", true),
                            "timeout");
                    return;
                }
                attempt.server.setSoTimeout((int) Math.min(remaining, 2000L));
                Socket socket;
                try {
                    socket = attempt.server.accept();
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
            // The receiver was closed by cancel/finish, or failed. Either way the attempt ends.
            if (!isEnded(attempt)) {
                finish(attempt, l -> l.onFailed("Orbit's local sign-in receiver stopped before "
                        + "the browser came back. Try again, or use code sign-in.", true),
                        "receiver_failed");
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
        Response response = respond(attempt, target);
        writeResponse(socket.getOutputStream(), response);
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

    /**
     * Decides what one request to the receiver means for the attempt, and what the browser sees.
     * Split from the socket so tests can drive every branch directly.
     */
    static Response respond(Attempt attempt, String target) {
        Callback callback = parseCallback(target, attempt.state);
        switch (callback.outcome) {
            case NOT_CALLBACK:
                return new Response(404, page("Not found", "This address is only used to finish "
                        + "signing in to Orbit.", false));
            case STATE_MISMATCH:
                // Refused without ending the attempt: an old tab or a stray request must not be
                // able to cancel the sign-in the user is actually completing.
                ChatGptAuth.logFailure("browser_callback_rejected", "state_mismatch");
                return new Response(400, page("This sign-in page is out of date",
                        "Return to Orbit and tap Sign in with ChatGPT again.", true));
            case OAUTH_ERROR:
                String reason = callback.error;
                finish(attempt, l -> l.onFailed("ChatGPT sign-in did not complete: "
                        + reason + ". You can try again, or use code sign-in.", true), "oauth_error");
                return new Response(200, page("Sign-in was not completed",
                        "Return to Orbit to try again.", true));
            case MISSING_CODE:
                finish(attempt, l -> l.onFailed("OpenAI returned to Orbit without a sign-in "
                        + "code. Try again, or use code sign-in.", true), "missing_code");
                return new Response(400, page("Sign-in was not completed",
                        "Return to Orbit to try again.", true));
            case CODE:
            default:
                // The receiver's job is done; nothing else may arrive on it.
                markEnded(attempt);
                closeQuietly(attempt.server);
                ChatGptAuth.BrowserExchange result = exchanger.exchange(attempt.app,
                        callback.code, attempt.pkce.verifier, attempt.redirectUri);
                if (result.ok()) {
                    deliver(attempt, l -> l.onSignedIn(result.account), "signed_in");
                    return new Response(200, page("You're signed in to Orbit",
                            "Your ChatGPT account is connected. You can return to Orbit.", true));
                }
                deliver(attempt, l -> l.onFailed(result.message
                        + " You can try again, or use code sign-in.", true), "exchange_failed");
                return new Response(200, page("Orbit could not finish signing in",
                        "Return to Orbit for details.", true));
        }
    }

    private static String page(String title, String message, boolean returnButton) {
        String button = returnButton
                ? "<p><a class=\"b\" href=\"" + RETURN_URI + "\">Return to Orbit</a></p>" : "";
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

    private static void markEnded(Attempt attempt) {
        synchronized (LOCK) { attempt.ended = true; }
    }

    /** Ends an attempt that has not ended yet: closes its receiver, then tells the screen once. */
    private static void finish(Attempt attempt, Delivery delivery, String diagnostic) {
        synchronized (LOCK) {
            if (attempt.ended) return;
            attempt.ended = true;
        }
        closeQuietly(attempt.server);
        deliver(attempt, delivery, diagnostic);
    }

    /** Tells whichever screen is attached, on the main thread; keeps it if nobody is. */
    private static void deliver(Attempt attempt, Delivery delivery, String diagnostic) {
        Listener listener;
        synchronized (LOCK) {
            if (active == attempt) active = null;
            listener = attempt.listener;
            attempt.listener = null;
        }
        ChatGptAuth.logStage("browser_attempt_" + diagnostic);
        if (listener == null) {
            // Nobody is listening (the screen paused or was recreated). A real outcome waits for
            // the next attach; a cancellation has nothing to say and is simply dropped.
            if (!"cancelled".equals(diagnostic)) {
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

    /** Replaces the token exchange. Returns the previous one so a test can restore it. */
    static Exchanger installExchangerForTest(Exchanger replacement) {
        Exchanger previous = exchanger;
        exchanger = replacement == null ? ChatGptAuth::exchangeBrowserCode : replacement;
        return previous;
    }

    /** The live attempt, or null. */
    static Attempt activeForTest() {
        synchronized (LOCK) { return active; }
    }
}
