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
import android.util.Base64;

import org.json.JSONObject;
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
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ChatGPT browser sign-in: PKCE, state, the loopback receiver's lifecycle, and how its outcomes
 * reach the shared credential storage. The token exchange itself is replaced by a fake that calls
 * the real {@link ChatGptAuth#storeSignIn}, so storage runs for real against an in-memory Keystore.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class ChatGptBrowserAuthTest {
    private Context context;
    private ChatGptBrowserAuth.Exchanger previous;

    /** A token whose payload names an account, as OpenAI's do. */
    private static String jwt(String accountId, String email, long exp) throws Exception {
        JSONObject auth = new JSONObject().put("chatgpt_account_id", accountId)
                .put("chatgpt_plan_type", "plus");
        JSONObject claims = new JSONObject().put("email", email).put("exp", exp)
                .put("https://api.openai.com/auth", auth);
        String payload = Base64.encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        return "e30." + payload + ".sig";
    }

    private final AtomicReference<String> exchangedCode = new AtomicReference<>();
    private final AtomicReference<String> exchangedVerifier = new AtomicReference<>();
    private final AtomicReference<String> exchangedRedirect = new AtomicReference<>();

    @Before public void setUp() {
        TestKeystore.install();
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        SecureStore.clearChatGpt(context);
        previous = ChatGptBrowserAuth.installExchangerForTest((c, code, verifier, redirect) -> {
            exchangedCode.set(code);
            exchangedVerifier.set(verifier);
            exchangedRedirect.set(redirect);
            try {
                long exp = System.currentTimeMillis() / 1000L + 3600L;
                return ChatGptAuth.storeSignIn(c, jwt("acct-123", "user@example.com", exp),
                        jwt("acct-123", "user@example.com", exp), "refresh-1");
            } catch (Exception e) {
                return ChatGptAuth.BrowserExchange.error("fake failed");
            }
        });
    }

    @After public void tearDown() {
        ChatGptBrowserAuth.cancel();
        ChatGptBrowserAuth.installExchangerForTest(previous);
        ShadowLooper.idleMainLooper();
        TestKeystore.uninstall();
    }

    /** Records how an attempt ended. */
    private static final class Recorder implements ChatGptBrowserAuth.Listener {
        final AtomicInteger signedIn = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final AtomicInteger cancelled = new AtomicInteger();
        final AtomicReference<String> message = new AtomicReference<>();
        ChatGptAuth.AccountInfo account;
        boolean offeredCode;

        @Override public void onSignedIn(ChatGptAuth.AccountInfo a) { account = a; signedIn.incrementAndGet(); }
        @Override public void onFailed(String m, boolean offer) {
            message.set(m);
            offeredCode = offer;
            failed.incrementAndGet();
        }
        @Override public void onCancelled() { cancelled.incrementAndGet(); }
        int outcomes() { return signedIn.get() + failed.get() + cancelled.get(); }
    }

    private static String stateOf(ChatGptBrowserAuth.Started started) {
        return Uri.parse(started.authorizeUrl).getQueryParameter("state");
    }

    // ---- PKCE and state ------------------------------------------------------------------------

    @Test public void pkceMatchesTheRfc7636Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                ChatGptBrowserAuth.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    @Test public void verifiersAreLongRandomAndUrlSafe() {
        Set<String> seen = new HashSet<>();
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 50; i++) {
            ChatGptBrowserAuth.Pkce pkce = ChatGptBrowserAuth.newPkce(random);
            assertEquals(86, pkce.verifier.length());
            assertTrue(pkce.verifier.matches("[A-Za-z0-9_-]+"));
            assertEquals(ChatGptBrowserAuth.challengeFor(pkce.verifier), pkce.challenge);
            assertTrue("never repeats", seen.add(pkce.verifier));
        }
    }

    @Test public void stateIsRandomPerAttempt() {
        Set<String> seen = new HashSet<>();
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 50; i++) {
            String state = ChatGptBrowserAuth.newState(random);
            assertTrue(state.length() >= 43);
            assertTrue(seen.add(state));
        }
    }

    @Test public void theAuthorizeUrlCarriesExactlyWhatOAuthNeeds() {
        String url = ChatGptBrowserAuth.authorizeUrl("http://127.0.0.1:1455/auth/callback",
                "CHALLENGE", "STATE");
        Uri uri = Uri.parse(url);
        assertEquals("https", uri.getScheme());
        assertEquals("auth.openai.com", uri.getHost());
        assertEquals("/oauth/authorize", uri.getPath());
        assertEquals("code", uri.getQueryParameter("response_type"));
        assertEquals(ChatGptAuth.CLIENT_ID, uri.getQueryParameter("client_id"));
        assertEquals("http://127.0.0.1:1455/auth/callback", uri.getQueryParameter("redirect_uri"));
        assertEquals("openid profile email offline_access", uri.getQueryParameter("scope"));
        assertEquals("CHALLENGE", uri.getQueryParameter("code_challenge"));
        assertEquals("S256", uri.getQueryParameter("code_challenge_method"));
        assertEquals("STATE", uri.getQueryParameter("state"));
        assertNull("the verifier never leaves the phone", uri.getQueryParameter("code_verifier"));
    }

    // ---- callback parsing ----------------------------------------------------------------------

    @Test public void aMatchingCallbackYieldsTheCode() {
        ChatGptBrowserAuth.Callback cb = ChatGptBrowserAuth.parseCallback(
                "/auth/callback?code=abc&state=S1", "S1");
        assertEquals(ChatGptBrowserAuth.Outcome.CODE, cb.outcome);
        assertEquals("abc", cb.code);
    }

    @Test public void aWrongOrMissingStateIsRejected() {
        assertEquals(ChatGptBrowserAuth.Outcome.STATE_MISMATCH,
                ChatGptBrowserAuth.parseCallback("/auth/callback?code=abc&state=OLD", "S1").outcome);
        assertEquals(ChatGptBrowserAuth.Outcome.STATE_MISMATCH,
                ChatGptBrowserAuth.parseCallback("/auth/callback?code=abc", "S1").outcome);
        assertEquals("an error with the wrong state is not believed",
                ChatGptBrowserAuth.Outcome.STATE_MISMATCH,
                ChatGptBrowserAuth.parseCallback("/auth/callback?error=access_denied&state=X", "S1").outcome);
    }

    @Test public void aMissingCodeIsRejected() {
        assertEquals(ChatGptBrowserAuth.Outcome.MISSING_CODE,
                ChatGptBrowserAuth.parseCallback("/auth/callback?state=S1", "S1").outcome);
    }

    @Test public void anOAuthErrorIsReported() {
        ChatGptBrowserAuth.Callback cb = ChatGptBrowserAuth.parseCallback(
                "/auth/callback?error=access_denied&error_description=User%20cancelled&state=S1", "S1");
        assertEquals(ChatGptBrowserAuth.Outcome.OAUTH_ERROR, cb.outcome);
        assertEquals("User cancelled", cb.error);
    }

    @Test public void otherPathsAreNotCallbacks() {
        assertEquals(ChatGptBrowserAuth.Outcome.NOT_CALLBACK,
                ChatGptBrowserAuth.parseCallback("/favicon.ico", "S1").outcome);
    }

    // ---- attempts ------------------------------------------------------------------------------

    @Test public void aSuccessfulCallbackStoresCredentialsAndClosesTheReceiver() throws Exception {
        Recorder recorder = new Recorder();
        ChatGptBrowserAuth.Started started = ChatGptBrowserAuth.start(context, recorder);
        assertTrue(started.ok());
        ChatGptBrowserAuth.Attempt attempt = ChatGptBrowserAuth.activeForTest();
        assertNotNull(attempt);
        assertTrue(attempt.server.isBound());

        ChatGptBrowserAuth.Response response = ChatGptBrowserAuth.respond(attempt,
                "/auth/callback?code=the-code&state=" + Uri.encode(stateOf(started)));
        ShadowLooper.idleMainLooper();

        assertEquals(200, response.status);
        assertTrue(response.body.contains("Return to Orbit"));
        assertTrue(response.body.contains(ChatGptBrowserAuth.RETURN_URI));
        assertEquals(1, recorder.signedIn.get());
        assertEquals("acct-123", recorder.account.accountId);
        assertEquals("the-code", exchangedCode.get());
        assertEquals(attempt.pkce.verifier, exchangedVerifier.get());
        assertEquals(attempt.redirectUri, exchangedRedirect.get());
        assertTrue("the receiver closes once its job is done", attempt.server.isClosed());
        assertFalse(ChatGptBrowserAuth.inProgress());

        SecureStore.ChatGptTokens stored = SecureStore.loadChatGptTokens(context);
        assertNotNull(stored);
        assertEquals("refresh-1", stored.refreshToken);
        assertEquals("acct-123", stored.accountId);
        assertTrue(ChatGptAuth.isSignedIn(context));
        assertEquals(Prefs.PROVIDER_CHATGPT, Prefs.provider(context));
    }

    @Test public void aStaleCallbackIsRefusedAndTheRealOneStillWorks() {
        Recorder recorder = new Recorder();
        ChatGptBrowserAuth.Started started = ChatGptBrowserAuth.start(context, recorder);
        ChatGptBrowserAuth.Attempt attempt = ChatGptBrowserAuth.activeForTest();
        ChatGptBrowserAuth.Response stale = ChatGptBrowserAuth.respond(attempt,
                "/auth/callback?code=evil&state=someone-elses");
        ShadowLooper.idleMainLooper();
        assertEquals(400, stale.status);
        assertEquals("a stray request cannot end the sign-in", 0, recorder.outcomes());
        assertTrue(ChatGptBrowserAuth.inProgress());
        assertNull("and its code is never exchanged", exchangedCode.get());

        ChatGptBrowserAuth.respond(attempt, "/auth/callback?code=real&state=" + Uri.encode(stateOf(started)));
        ShadowLooper.idleMainLooper();
        assertEquals(1, recorder.signedIn.get());
        assertEquals("real", exchangedCode.get());
    }

    @Test public void anOAuthErrorEndsTheAttemptAndOffersCodeSignIn() {
        Recorder recorder = new Recorder();
        ChatGptBrowserAuth.Started started = ChatGptBrowserAuth.start(context, recorder);
        ChatGptBrowserAuth.Attempt attempt = ChatGptBrowserAuth.activeForTest();
        ChatGptBrowserAuth.respond(attempt, "/auth/callback?error=access_denied&state="
                + Uri.encode(stateOf(started)));
        ShadowLooper.idleMainLooper();
        assertEquals(1, recorder.failed.get());
        assertTrue(recorder.offeredCode);
        assertTrue(recorder.message.get().contains("access_denied"));
        assertTrue(attempt.server.isClosed());
        assertFalse(ChatGptAuth.isSignedIn(context));
    }

    @Test public void aMissingCodeEndsTheAttemptHonestly() {
        Recorder recorder = new Recorder();
        ChatGptBrowserAuth.Started started = ChatGptBrowserAuth.start(context, recorder);
        ChatGptBrowserAuth.Attempt attempt = ChatGptBrowserAuth.activeForTest();
        ChatGptBrowserAuth.respond(attempt, "/auth/callback?state=" + Uri.encode(stateOf(started)));
        ShadowLooper.idleMainLooper();
        assertEquals(1, recorder.failed.get());
        assertTrue(recorder.message.get().contains("without a sign-in code"));
        assertTrue(attempt.server.isClosed());
    }

    @Test public void aFailedExchangeIsReportedAndStoresNothing() {
        ChatGptBrowserAuth.installExchangerForTest((c, code, v, r) ->
                ChatGptAuth.BrowserExchange.error("OpenAI could not finish the sign-in (HTTP 400): invalid_grant"));
        Recorder recorder = new Recorder();
        ChatGptBrowserAuth.Started started = ChatGptBrowserAuth.start(context, recorder);
        ChatGptBrowserAuth.Attempt attempt = ChatGptBrowserAuth.activeForTest();
        ChatGptBrowserAuth.respond(attempt, "/auth/callback?code=c&state=" + Uri.encode(stateOf(started)));
        ShadowLooper.idleMainLooper();
        assertEquals(1, recorder.failed.get());
        assertTrue(recorder.message.get().contains("invalid_grant"));
        assertTrue(recorder.message.get().contains("code sign-in"));
        assertFalse(ChatGptAuth.isSignedIn(context));
        assertTrue(attempt.server.isClosed());
    }

    @Test public void aNewerAttemptSupersedesTheOldOneAndItsState() {
        Recorder first = new Recorder();
        ChatGptBrowserAuth.Started one = ChatGptBrowserAuth.start(context, first);
        ChatGptBrowserAuth.Attempt oldAttempt = ChatGptBrowserAuth.activeForTest();
        Recorder second = new Recorder();
        ChatGptBrowserAuth.Started two = ChatGptBrowserAuth.start(context, second);
        ShadowLooper.idleMainLooper();
        assertEquals(1, first.cancelled.get());
        assertTrue("the old receiver is closed", oldAttempt.server.isClosed());
        assertNotEquals(stateOf(one), stateOf(two));

        ChatGptBrowserAuth.Attempt current = ChatGptBrowserAuth.activeForTest();
        ChatGptBrowserAuth.Response replay = ChatGptBrowserAuth.respond(current,
                "/auth/callback?code=c&state=" + Uri.encode(stateOf(one)));
        assertEquals("the old attempt's state is now stale", 400, replay.status);
        assertEquals(0, second.outcomes());
    }

    @Test public void cancelAndSignOutCloseTheReceiver() {
        Recorder recorder = new Recorder();
        ChatGptBrowserAuth.start(context, recorder);
        ChatGptBrowserAuth.Attempt attempt = ChatGptBrowserAuth.activeForTest();
        ChatGptAuth.logout(context);
        ShadowLooper.idleMainLooper();
        assertTrue(attempt.server.isClosed());
        assertEquals(1, recorder.cancelled.get());
        assertFalse(ChatGptBrowserAuth.inProgress());
        ChatGptBrowserAuth.cancel();
        assertEquals("ending twice reports once", 1, recorder.outcomes());
    }

    @Test public void aBrowserThatNeverReturnsTimesOutAndCloses() throws Exception {
        Recorder recorder = new Recorder();
        ChatGptBrowserAuth.start(context, recorder, 300L);
        ChatGptBrowserAuth.Attempt attempt = ChatGptBrowserAuth.activeForTest();
        long until = System.currentTimeMillis() + 5000L;
        while (!attempt.server.isClosed() && System.currentTimeMillis() < until) Thread.sleep(50);
        ShadowLooper.idleMainLooper();
        assertTrue(attempt.server.isClosed());
        assertEquals(1, recorder.failed.get());
        assertTrue(recorder.message.get().contains("timed out"));
        assertTrue(recorder.offeredCode);
    }

    @Test public void theRealReceiverAnswersOnLoopbackAndThenStopsListening() throws Exception {
        Recorder recorder = new Recorder();
        ChatGptBrowserAuth.Started started = ChatGptBrowserAuth.start(context, recorder);
        ChatGptBrowserAuth.Attempt attempt = ChatGptBrowserAuth.activeForTest();
        int port = attempt.server.getLocalPort();
        assertTrue(port == ChatGptBrowserAuth.PORT || port == ChatGptBrowserAuth.FALLBACK_PORT);
        assertEquals("127.0.0.1", attempt.server.getInetAddress().getHostAddress());

        String status;
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(("GET /auth/callback?code=live&state=" + Uri.encode(stateOf(started))
                    + " HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                    StandardCharsets.UTF_8));
            status = in.readLine();
        }
        ShadowLooper.idleMainLooper();
        assertEquals("HTTP/1.1 200 OK", status);
        assertEquals(1, recorder.signedIn.get());
        try (Socket again = new Socket("127.0.0.1", port)) {
            fail("nothing may still be listening after sign-in ends");
        } catch (java.io.IOException expected) {
            // Connection refused: the receiver is gone.
        }
    }

    @Test public void anOutcomeWhileNoScreenIsListeningIsDeliveredOnReturn() {
        Recorder away = new Recorder();
        ChatGptBrowserAuth.Started started = ChatGptBrowserAuth.start(context, away);
        ChatGptBrowserAuth.Attempt attempt = ChatGptBrowserAuth.activeForTest();
        ChatGptBrowserAuth.detach(away);
        ChatGptBrowserAuth.respond(attempt, "/auth/callback?code=c&state=" + Uri.encode(stateOf(started)));
        ShadowLooper.idleMainLooper();
        assertEquals(0, away.outcomes());
        Recorder back = new Recorder();
        ChatGptBrowserAuth.attach(back);
        ShadowLooper.idleMainLooper();
        assertEquals(1, back.signedIn.get());
    }

    // ---- the code fallback and existing sessions ------------------------------------------------

    @Test public void startingBrowserSignInEndsAPendingCodeSignIn() {
        assertTrue(SecureStore.savePendingChatGptLogin(context, "dev-1", "ABCD-1234", 5L,
                System.currentTimeMillis() + 60000L));
        ChatGptBrowserAuth.start(context, new Recorder());
        assertNull("only one sign-in method waits at a time",
                SecureStore.loadPendingChatGptLogin(context));
    }

    @Test public void theCodeFallbackRemainsFullyWired() {
        String settings = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/SettingsActivity.java");
        assertTrue(settings.contains("Having trouble? Use code sign-in"));
        assertTrue(settings.contains("ChatGptAuth.requestDeviceCode("));
        assertTrue(settings.contains("ChatGptAuth.completeDeviceCode("));
        assertTrue("a pending code sign-in still resumes",
                settings.contains("ChatGptAuth.resumePendingDeviceCode("));
        String onboarding = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OnboardingActivity.java");
        assertTrue(onboarding.contains("Having trouble? Use code sign-in"));
        String auth = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/ChatGptAuth.java");
        assertTrue(auth.contains("DEVICE_USER_CODE_URL"));
        assertTrue(auth.contains("DEVICE_TOKEN_URL"));
        assertTrue("choosing code sign-in ends a browser attempt",
                auth.contains("ChatGptBrowserAuth.cancel();"));
    }

    @Test public void failuresNeverSwitchMethodsSilently() {
        String browser = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/ChatGptBrowserAuth.java");
        assertFalse(browser.contains("requestDeviceCode"));
        assertFalse(browser.contains("completeDeviceCode"));
    }

    @Test public void anExistingSessionSurvivesTheUpdate() throws Exception {
        long exp = System.currentTimeMillis() / 1000L + 3600L;
        assertTrue(SecureStore.saveChatGptTokens(context, jwt("acct-9", "a@b.c", exp),
                jwt("acct-9", "a@b.c", exp), "refresh-old", "acct-9"));
        // Everything this release adds runs on an upgraded install...
        AiSelections.globalDefault(context);
        AiSelections.ensureMigrated(context);
        ChatGptBrowserAuth.cancel();
        // ...and the stored session is untouched and still usable.
        SecureStore.ChatGptTokens t = SecureStore.loadChatGptTokens(context);
        assertNotNull(t);
        assertEquals("refresh-old", t.refreshToken);
        assertEquals("acct-9", ChatGptAuth.getAccountInfo(context).accountId);
        AtomicReference<SecureStore.ChatGptTokens> valid = new AtomicReference<>();
        ChatGptAuth.getValidTokens(context, null, new ChatGptAuth.TokenCallback() {
            @Override public void onSuccess(SecureStore.ChatGptTokens tokens) { valid.set(tokens); }
            @Override public void onError(String message) { fail(message); }
        });
        assertEquals("a valid token is used as it is, with no refresh", "refresh-old",
                valid.get().refreshToken);
    }

    @Test public void refreshTokensRotateAndOmittedFieldsAreKept() throws Exception {
        long exp = System.currentTimeMillis() / 1000L + 3600L;
        SecureStore.saveChatGptTokens(context, jwt("acct-1", "a@b.c", exp), "old-access",
                "old-refresh", "acct-1");
        SecureStore.ChatGptTokens old = SecureStore.loadChatGptTokens(context);
        String[] rotated = ChatGptAuth.refreshed(old, new JSONObject()
                .put("access_token", "new-access").put("refresh_token", "new-refresh"));
        assertEquals("new-access", rotated[1]);
        assertEquals("a rotated refresh token replaces the old one", "new-refresh", rotated[2]);
        assertEquals("acct-1", rotated[3]);
        String[] kept = ChatGptAuth.refreshed(old, new JSONObject().put("access_token", "x"));
        assertEquals("old-refresh", kept[2]);
        assertNull(ChatGptAuth.refreshed(old, new JSONObject().put("access_token", "")
                .put("refresh_token", "")));
        // And the rotated pair is what storage holds afterwards.
        assertTrue(SecureStore.saveChatGptTokens(context, rotated[0], rotated[1], rotated[2], rotated[3]));
        assertEquals("new-refresh", SecureStore.loadChatGptTokens(context).refreshToken);
    }

    @Test public void signingOutClearsCredentialsAndPendingSignIns() throws Exception {
        long exp = System.currentTimeMillis() / 1000L + 3600L;
        SecureStore.saveChatGptTokens(context, jwt("a", "e", exp), jwt("a", "e", exp), "r", "a");
        SecureStore.savePendingChatGptLogin(context, "dev", "CODE", 5L, System.currentTimeMillis() + 60000L);
        ChatGptBrowserAuth.start(context, new Recorder());
        ChatGptAuth.logout(context);
        assertNull(SecureStore.loadChatGptTokens(context));
        assertNull(SecureStore.loadPendingChatGptLogin(context));
        assertFalse(ChatGptBrowserAuth.inProgress());
    }

    @Test public void nothingSecretIsLogged() {
        for (String file : new String[]{"ChatGptBrowserAuth.java", "ChatGptAuth.java"}) {
            String source = ComponentUninstallTest.readRepositoryFile(
                    "app/src/main/java/com/orbit/assistant/" + file);
            for (String line : source.split("\n")) {
                if (!line.contains("Log.") && !line.contains("logStage(") && !line.contains("logFailure(")) continue;
                for (String secret : new String[]{"verifier", "accessToken", "refreshToken",
                        "access_token", "callback.code", "state)", "authorizeUrl"}) {
                    assertFalse(file + " logs " + secret + ": " + line.trim(), line.contains(secret));
                }
            }
        }
    }

    @Test public void theReturnDoorwayIsBrowsableAndCarriesNoData() {
        String manifest = ComponentUninstallTest.readRepositoryFile("app/src/main/AndroidManifest.xml");
        int at = manifest.indexOf("android:name=\".ChatGptSignInReturnActivity\"");
        assertTrue(at > 0);
        String block = manifest.substring(at, manifest.indexOf("</activity>", at));
        assertTrue(block.contains("android.intent.category.BROWSABLE"));
        assertTrue(block.contains("android:scheme=\"orbit-assistant\""));
        assertTrue(block.contains("android:host=\"chatgpt-signin\""));
        String activity = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/ChatGptSignInReturnActivity.java");
        assertFalse("it reads nothing from the link", activity.contains("getData()"));
        assertFalse(activity.contains("getQueryParameter"));
    }
}
