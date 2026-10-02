package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.net.Uri;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sign in with OpenRouter (0.8.3.0-beta.6): S256 PKCE, the per-attempt state carried in the
 * callback path, the loopback receiver's lifecycle, the code exchange's documented answers, and
 * where the resulting key ends up. The exchange is replaced by a fake; storage runs for real
 * against an in-memory Keystore. No real OpenRouter account is used.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class OpenRouterAuthTest {
    private static final String FAKE_KEY = "sk-or-v1-test-oauth-key";

    private Context context;
    private OpenRouterAuth.Exchanger previous;
    private final AtomicReference<String> exchangedCode = new AtomicReference<>();
    private final AtomicReference<String> exchangedVerifier = new AtomicReference<>();
    private final AtomicInteger exchanges = new AtomicInteger();
    private volatile OpenRouterAuth.Exchange nextExchange = OpenRouterAuth.Exchange.success(FAKE_KEY);

    @Before public void setUp() {
        TestKeystore.install();
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        previous = OpenRouterAuth.installExchangerForTest((code, verifier) -> {
            exchanges.incrementAndGet();
            exchangedCode.set(code);
            exchangedVerifier.set(verifier);
            return nextExchange;
        });
    }

    @After public void tearDown() {
        OpenRouterAuth.cancel();
        OpenRouterAuth.installExchangerForTest(previous);
        ShadowLooper.idleMainLooper();
        TestKeystore.uninstall();
    }

    private static final class Recorder implements OpenRouterAuth.Listener {
        final AtomicInteger connected = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final AtomicInteger cancelled = new AtomicInteger();
        final AtomicReference<String> message = new AtomicReference<>();

        @Override public void onConnected() { connected.incrementAndGet(); }
        @Override public void onFailed(String m) { message.set(m); failed.incrementAndGet(); }
        @Override public void onCancelled() { cancelled.incrementAndGet(); }
        int outcomes() { return connected.get() + failed.get() + cancelled.get(); }
    }

    private static Uri callbackOf(OpenRouterAuth.Started started) {
        return Uri.parse(Uri.parse(started.authorizeUrl).getQueryParameter("callback_url"));
    }

    private static String stateOf(OpenRouterAuth.Started started) {
        return callbackOf(started).getLastPathSegment();
    }

    private static String callbackPath(OpenRouterAuth.Started started) {
        return callbackOf(started).getPath();
    }

    // ---- PKCE, state and the authorize page ------------------------------------------------------

    @Test public void theChallengeIsS256OfTheVerifier() {
        // RFC 7636 appendix B.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                ChatGptBrowserAuth.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        OpenRouterAuth.Started started = OpenRouterAuth.start(context, new Recorder());
        OpenRouterAuth.Attempt attempt = OpenRouterAuth.activeForTest();
        Uri page = Uri.parse(started.authorizeUrl);
        assertEquals(ChatGptBrowserAuth.challengeFor(attempt.pkce.verifier),
                page.getQueryParameter("code_challenge"));
        assertEquals("S256", page.getQueryParameter("code_challenge_method"));
    }

    @Test public void everyAttemptHasAFreshHighEntropyVerifierAndState() {
        Set<String> verifiers = new HashSet<>();
        Set<String> states = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            OpenRouterAuth.start(context, new Recorder());
            OpenRouterAuth.Attempt attempt = OpenRouterAuth.activeForTest();
            assertEquals("86-character RFC 7636 verifier", 86, attempt.pkce.verifier.length());
            assertTrue(attempt.pkce.verifier.matches("[A-Za-z0-9_-]+"));
            assertTrue("256-bit state", attempt.state.length() >= 43);
            assertTrue(attempt.state.matches("[A-Za-z0-9_-]+"));
            verifiers.add(attempt.pkce.verifier);
            states.add(attempt.state);
        }
        assertEquals(20, verifiers.size());
        assertEquals(20, states.size());
    }

    @Test public void theAuthorizePageIsOpenRoutersWithALoopbackCallback() {
        OpenRouterAuth.Started started = OpenRouterAuth.start(context, new Recorder());
        assertTrue(started.ok());
        Uri page = Uri.parse(started.authorizeUrl);
        assertEquals("https", page.getScheme());
        assertEquals("openrouter.ai", page.getHost());
        assertEquals("/auth", page.getPath());
        assertEquals(OpenRouterAuth.KEY_LABEL, page.getQueryParameter("key_label"));
        Uri callback = callbackOf(started);
        assertEquals("a documented callback form: http://localhost:<port>", "http", callback.getScheme());
        assertEquals("localhost", callback.getHost());
        OpenRouterAuth.Attempt attempt = OpenRouterAuth.activeForTest();
        assertEquals(attempt.server.getLocalPort(), callback.getPort());
        assertTrue("an ephemeral port, never a fixed one", callback.getPort() > 1024);
        assertEquals(OpenRouterAuth.CALLBACK_PREFIX + attempt.state, callback.getPath());
        assertFalse("nothing secret is in the page address",
                started.authorizeUrl.contains(attempt.pkce.verifier));
    }

    @Test public void theReceiverListensOnLoopbackOnly() {
        OpenRouterAuth.start(context, new Recorder());
        OpenRouterAuth.Attempt attempt = OpenRouterAuth.activeForTest();
        assertTrue(attempt.server.getInetAddress().isLoopbackAddress());
        assertEquals("127.0.0.1", attempt.server.getInetAddress().getHostAddress());
        if (attempt.server6 != null) {
            assertTrue(attempt.server6.getInetAddress().isLoopbackAddress());
            assertEquals(attempt.server.getLocalPort(), attempt.server6.getLocalPort());
        }
    }

    // ---- callback parsing ------------------------------------------------------------------------

    @Test public void onlyTheExactStatePathWithACodeIsAccepted() {
        String state = "abcDEF123_-";
        OpenRouterAuth.Callback ok = OpenRouterAuth.parseCallback(
                OpenRouterAuth.CALLBACK_PREFIX + state + "?code=the-code", state);
        assertEquals(OpenRouterAuth.Outcome.CODE, ok.outcome);
        assertEquals("the-code", ok.code);

        assertEquals(OpenRouterAuth.Outcome.STATE_MISMATCH, OpenRouterAuth.parseCallback(
                OpenRouterAuth.CALLBACK_PREFIX + "other?code=c", state).outcome);
        assertEquals(OpenRouterAuth.Outcome.STATE_MISMATCH, OpenRouterAuth.parseCallback(
                OpenRouterAuth.CALLBACK_PREFIX + state + "x?code=c", state).outcome);
        assertEquals(OpenRouterAuth.Outcome.STATE_MISMATCH, OpenRouterAuth.parseCallback(
                OpenRouterAuth.CALLBACK_PREFIX + "?code=c", state).outcome);
        assertEquals(OpenRouterAuth.Outcome.STATE_MISMATCH, OpenRouterAuth.parseCallback(
                OpenRouterAuth.CALLBACK_PREFIX + state + "?code=c", null).outcome);
        assertEquals(OpenRouterAuth.Outcome.MISSING_CODE, OpenRouterAuth.parseCallback(
                OpenRouterAuth.CALLBACK_PREFIX + state, state).outcome);
        assertEquals(OpenRouterAuth.Outcome.MISSING_CODE, OpenRouterAuth.parseCallback(
                OpenRouterAuth.CALLBACK_PREFIX + state + "?code=", state).outcome);
        assertEquals(OpenRouterAuth.Outcome.DENIED, OpenRouterAuth.parseCallback(
                OpenRouterAuth.CALLBACK_PREFIX + state + "?error=access_denied", state).outcome);
        assertEquals(OpenRouterAuth.Outcome.NOT_CALLBACK,
                OpenRouterAuth.parseCallback("/favicon.ico", state).outcome);
        assertEquals(OpenRouterAuth.Outcome.NOT_CALLBACK,
                OpenRouterAuth.parseCallback("/callback?code=c", state).outcome);
    }

    // ---- outcomes --------------------------------------------------------------------------------

    @Test public void aGoodCallbackExchangesOnceStoresTheKeyAndCloses() {
        Recorder recorder = new Recorder();
        OpenRouterAuth.Started started = OpenRouterAuth.start(context, recorder);
        OpenRouterAuth.Attempt attempt = OpenRouterAuth.activeForTest();
        OpenRouterAuth.Response response = OpenRouterAuth.respond(attempt,
                callbackPath(started) + "?code=one-time");
        ShadowLooper.idleMainLooper();

        assertEquals(200, response.status);
        assertEquals(1, recorder.connected.get());
        assertEquals(1, exchanges.get());
        assertEquals("one-time", exchangedCode.get());
        assertEquals("the verifier that matches the challenge", attempt.pkce.verifier,
                exchangedVerifier.get());
        assertTrue(attempt.server.isClosed());
        assertFalse(OpenRouterAuth.inProgress());
        assertEquals(FAKE_KEY, SecureStore.loadOpenRouterKey(context));
        assertEquals(SecureStore.OPENROUTER_SOURCE_OAUTH, SecureStore.openRouterKeySource(context));
        assertEquals("Connected with OpenRouter",
                AiProviders.byId(Prefs.PROVIDER_OPENROUTER).statusDetail(context));
        assertFalse("the browser page never shows the key", response.body.contains(FAKE_KEY));
        assertFalse("connecting never enables Auto",
                AutoPermissions.allows(context, Prefs.PROVIDER_OPENROUTER));

        // The code is used once: a replay to the finished attempt is refused and never exchanged.
        OpenRouterAuth.Response replay = OpenRouterAuth.respond(attempt,
                callbackPath(started) + "?code=one-time");
        ShadowLooper.idleMainLooper();
        assertEquals(400, replay.status);
        assertEquals(1, exchanges.get());
        assertEquals(1, recorder.outcomes());
    }

    @Test public void aStaleOrForgedCallbackIsRefusedAndTheRealOneStillWorks() {
        Recorder recorder = new Recorder();
        OpenRouterAuth.Started started = OpenRouterAuth.start(context, recorder);
        OpenRouterAuth.Attempt attempt = OpenRouterAuth.activeForTest();
        OpenRouterAuth.Response stale = OpenRouterAuth.respond(attempt,
                OpenRouterAuth.CALLBACK_PREFIX + "an-old-state?code=stolen");
        ShadowLooper.idleMainLooper();
        assertEquals(400, stale.status);
        assertEquals(0, recorder.outcomes());
        assertEquals("a forged callback is never exchanged", 0, exchanges.get());
        assertTrue(OpenRouterAuth.inProgress());
        assertFalse(SecureStore.hasOpenRouterKey(context));

        OpenRouterAuth.respond(attempt, callbackPath(started) + "?code=real");
        ShadowLooper.idleMainLooper();
        assertEquals(1, recorder.connected.get());
        assertEquals("real", exchangedCode.get());
    }

    @Test public void aMissingCodeEndsTheAttemptAndStoresNothing() {
        Recorder recorder = new Recorder();
        OpenRouterAuth.Started started = OpenRouterAuth.start(context, recorder);
        OpenRouterAuth.respond(OpenRouterAuth.activeForTest(), callbackPath(started));
        ShadowLooper.idleMainLooper();
        assertEquals(1, recorder.failed.get());
        assertTrue(recorder.message.get().contains("without an authorization code"));
        assertEquals(0, exchanges.get());
        assertFalse(SecureStore.hasOpenRouterKey(context));
        assertFalse(OpenRouterAuth.inProgress());
    }

    @Test public void aDeniedAuthorizationIsReported() {
        Recorder recorder = new Recorder();
        OpenRouterAuth.Started started = OpenRouterAuth.start(context, recorder);
        OpenRouterAuth.respond(OpenRouterAuth.activeForTest(),
                callbackPath(started) + "?error=access_denied");
        ShadowLooper.idleMainLooper();
        assertEquals(1, recorder.failed.get());
        assertTrue(recorder.message.get().contains("use an API key instead"));
        assertFalse(SecureStore.hasOpenRouterKey(context));
    }

    @Test public void aFailedExchangeIsReportedAndStoresNothing() {
        nextExchange = OpenRouterAuth.Exchange.failure("OpenRouter refused the sign-in code.");
        Recorder recorder = new Recorder();
        OpenRouterAuth.Started started = OpenRouterAuth.start(context, recorder);
        OpenRouterAuth.respond(OpenRouterAuth.activeForTest(), callbackPath(started) + "?code=x");
        ShadowLooper.idleMainLooper();
        assertEquals(1, recorder.failed.get());
        assertEquals("OpenRouter refused the sign-in code.", recorder.message.get());
        assertFalse(SecureStore.hasOpenRouterKey(context));
    }

    @Test public void cancellingClosesTheReceiverAndReportsOnce() {
        Recorder recorder = new Recorder();
        OpenRouterAuth.start(context, recorder);
        OpenRouterAuth.Attempt attempt = OpenRouterAuth.activeForTest();
        OpenRouterAuth.cancel();
        ShadowLooper.idleMainLooper();
        assertTrue(attempt.server.isClosed());
        if (attempt.server6 != null) assertTrue(attempt.server6.isClosed());
        assertEquals(1, recorder.cancelled.get());
        OpenRouterAuth.cancel();
        assertEquals(1, recorder.outcomes());
        assertNull(OpenRouterAuth.activeForTest());
    }

    @Test public void aNewAttemptReplacesTheOldOneAndItsState() {
        Recorder first = new Recorder();
        OpenRouterAuth.Started old = OpenRouterAuth.start(context, first);
        OpenRouterAuth.Attempt oldAttempt = OpenRouterAuth.activeForTest();
        Recorder second = new Recorder();
        OpenRouterAuth.Started fresh = OpenRouterAuth.start(context, second);
        ShadowLooper.idleMainLooper();
        assertEquals(1, first.cancelled.get());
        assertTrue(oldAttempt.server.isClosed());
        assertNotEquals(stateOf(old), stateOf(fresh));
        // The old tab's callback carries the old state: refused by the new attempt.
        OpenRouterAuth.Response response = OpenRouterAuth.respond(OpenRouterAuth.activeForTest(),
                callbackPath(old) + "?code=old");
        assertEquals(400, response.status);
        assertEquals(0, exchanges.get());
    }

    @Test public void anExpiredAttemptTimesOutAndCloses() throws Exception {
        Recorder recorder = new Recorder();
        OpenRouterAuth.start(context, recorder, 300L);
        OpenRouterAuth.Attempt attempt = OpenRouterAuth.activeForTest();
        long until = System.currentTimeMillis() + 5000L;
        while (!attempt.server.isClosed() && System.currentTimeMillis() < until) Thread.sleep(50);
        ShadowLooper.idleMainLooper();
        assertTrue(attempt.server.isClosed());
        assertEquals(1, recorder.failed.get());
        assertTrue(recorder.message.get().contains("timed out"));
        assertFalse(OpenRouterAuth.inProgress());
    }

    @Test public void theRealReceiverAnswersOnLoopbackThenStopsListening() throws Exception {
        Recorder recorder = new Recorder();
        OpenRouterAuth.Started started = OpenRouterAuth.start(context, recorder);
        OpenRouterAuth.Attempt attempt = OpenRouterAuth.activeForTest();
        int port = attempt.server.getLocalPort();
        String status;
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + callbackPath(started) + "?code=live HTTP/1.1\r\nHost: localhost\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                    StandardCharsets.UTF_8));
            status = in.readLine();
        }
        ShadowLooper.idleMainLooper();
        assertEquals("HTTP/1.1 200 OK", status);
        assertEquals(1, recorder.connected.get());
        assertEquals(FAKE_KEY, SecureStore.loadOpenRouterKey(context));
        try (Socket again = new Socket("127.0.0.1", port)) {
            fail("nothing may still be listening after sign-in ends");
        } catch (java.io.IOException expected) {
            // Connection refused: the receiver is gone.
        }
    }

    @Test public void anOutcomeWhileTheScreenIsAwayIsDeliveredOnReturn() {
        Recorder away = new Recorder();
        OpenRouterAuth.Started started = OpenRouterAuth.start(context, away);
        OpenRouterAuth.Attempt attempt = OpenRouterAuth.activeForTest();
        OpenRouterAuth.detach(away);
        OpenRouterAuth.respond(attempt, callbackPath(started) + "?code=c");
        ShadowLooper.idleMainLooper();
        assertEquals(0, away.outcomes());
        Recorder back = new Recorder();
        OpenRouterAuth.attach(back);
        ShadowLooper.idleMainLooper();
        assertEquals(1, back.connected.get());
    }

    // ---- the exchange's documented answers ---------------------------------------------------------

    @Test public void theExchangeReadsOnlyTheDocumentedKeyField() {
        OpenRouterAuth.Exchange ok = OpenRouterAuth.interpretExchange(200, "{\"key\":\"sk-or-v1-abc\"}");
        assertTrue(ok.ok());
        assertEquals("sk-or-v1-abc", ok.key);
        assertFalse(OpenRouterAuth.interpretExchange(200, "{}").ok());
        assertFalse(OpenRouterAuth.interpretExchange(200, "not json").ok());
        OpenRouterAuth.Exchange expired = OpenRouterAuth.interpretExchange(403,
                "{\"error\":{\"message\":\"Authorization code expired\"}}");
        assertFalse(expired.ok());
        assertTrue(expired.message.contains("expired"));
        assertFalse("the server body is never echoed", expired.message.contains("{"));
        assertTrue(OpenRouterAuth.interpretExchange(400, "Invalid code_challenge_method")
                .message.contains("rejected"));
        assertTrue(OpenRouterAuth.interpretExchange(429, "").message.contains("rate limit"));
        assertFalse(OpenRouterAuth.interpretExchange(500, "boom").ok());
    }

    @Test public void theExchangeIsAPostToTheDocumentedEndpointWithS256() {
        assertEquals("https://openrouter.ai/api/v1/auth/keys", OpenRouterAuth.EXCHANGE_URL);
        String source = source("OpenRouterAuth.java");
        assertTrue(source.contains(".put(\"code_verifier\", verifier)"));
        assertTrue(source.contains(".put(\"code_challenge_method\", \"S256\")"));
        assertTrue(source.contains("setRequestMethod(\"POST\")"));
        assertTrue(source.contains("setInstanceFollowRedirects(false)"));
        assertFalse("plain PKCE is never used", source.contains("\"plain\""));
    }

    // ---- secrets ---------------------------------------------------------------------------------

    @Test public void noSignInSecretIsLoggedStoredOutsideTheKeystoreOrBackedUp() throws Exception {
        String source = source("OpenRouterAuth.java");
        assertFalse(source.contains("Log."));
        assertFalse(source.contains("printStackTrace"));
        assertFalse("the verifier never touches disk", source.contains("SharedPreferences"));
        assertFalse(source.contains("putString("));

        Recorder recorder = new Recorder();
        OpenRouterAuth.Started started = OpenRouterAuth.start(context, recorder);
        OpenRouterAuth.Attempt attempt = OpenRouterAuth.activeForTest();
        OpenRouterAuth.respond(attempt, callbackPath(started) + "?code=secret-code");
        ShadowLooper.idleMainLooper();
        String backup = Prefs.backupSnapshot(context).toString();
        String allPrefs = Prefs.get(context).getAll().toString();
        for (String secret : new String[]{FAKE_KEY, "secret-code", attempt.pkce.verifier, attempt.state}) {
            assertFalse("backup holds " + secret, backup.contains(secret));
            assertFalse("plain preferences hold " + secret, allPrefs.contains(secret));
        }
        assertFalse(backup.contains("openrouter_key_source"));
        assertNotNull(SecureStore.loadOpenRouterKey(context));
    }

    private static String source(String file) {
        return ComponentUninstallTest.readRepositoryFile("app/src/main/java/com/orbit/assistant/" + file);
    }
}
