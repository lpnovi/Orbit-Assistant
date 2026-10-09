package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Base64;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ChatGPT session renewal and recovery (0.8.4.1): one renewal at a time, stale sessions renewed
 * before use, a failure for now never signs anyone out, refused credentials end the session once
 * and are never retried, and a session replaced meanwhile is never brought back.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class ChatGptSessionRecoveryTest {
    private Context context;
    private ChatGptAuth.Refresher previous;
    private final AtomicInteger refreshCalls = new AtomicInteger();
    private volatile ChatGptAuth.Refresher behaviour;

    private static String jwt(String accountId, long exp) throws Exception {
        JSONObject auth = new JSONObject().put("chatgpt_account_id", accountId);
        JSONObject claims = new JSONObject().put("email", "a@b.c").put("exp", exp)
                .put("https://api.openai.com/auth", auth);
        return "e30." + Base64.encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING) + ".sig";
    }

    private static long inSeconds(long s) { return System.currentTimeMillis() / 1000L + s; }

    private static ChatGptAuth.TokenResponse renewed(String access, String refresh) throws Exception {
        return new ChatGptAuth.TokenResponse(200, new JSONObject().put("access_token", access)
                .put("refresh_token", refresh).toString());
    }

    @Before public void setUp() throws Exception {
        TestKeystore.install();
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        SecureStore.clearChatGpt(context);
        behaviour = form -> renewed(jwt("acct", inSeconds(3600)), "refresh-new");
        previous = ChatGptAuth.installRefresherForTest(form -> {
            refreshCalls.incrementAndGet();
            return behaviour.post(form);
        });
        drainDueFlag();
    }

    @After public void tearDown() {
        ChatGptAuth.installRefresherForTest(previous);
    }

    /** A test that leaves the "renew next time" flag set must not leak it into the next one. */
    private void drainDueFlag() throws Exception {
        assertTrue(SecureStore.saveChatGptTokens(context, "", jwt("acct", inSeconds(3600)), "drain", "acct"));
        get(null);
        SecureStore.clearChatGpt(context);
        refreshCalls.set(0);
    }

    private void signIn(long exp, String refresh) throws Exception {
        assertTrue(SecureStore.saveChatGptTokens(context, "", jwt("acct", exp), refresh, "acct"));
    }

    private static final class Outcome {
        SecureStore.ChatGptTokens tokens;
        String error;
    }

    private Outcome get(String rejected) throws Exception {
        Outcome out = new Outcome();
        CountDownLatch done = new CountDownLatch(1);
        ChatGptAuth.getValidTokens(context, rejected, new ChatGptAuth.TokenCallback() {
            @Override public void onSuccess(SecureStore.ChatGptTokens tokens) { out.tokens = tokens; done.countDown(); }
            @Override public void onError(String message) { out.error = message; done.countDown(); }
        });
        assertTrue("token callback never arrived", done.await(5, TimeUnit.SECONDS));
        return out;
    }

    @Test public void aValidSessionIsUsedAsItIs() throws Exception {
        signIn(inSeconds(3600), "refresh-old");
        Outcome out = get(null);
        assertEquals("refresh-old", out.tokens.refreshToken);
        assertEquals(0, refreshCalls.get());
    }

    @Test public void anExpiringSessionIsRenewedAndTheRotatedTokenStored() throws Exception {
        signIn(inSeconds(30), "refresh-old");
        Outcome out = get(null);
        assertEquals("refresh-new", out.tokens.refreshToken);
        assertEquals("refresh-new", SecureStore.loadChatGptTokens(context).refreshToken);
        assertEquals(1, refreshCalls.get());
    }

    @Test public void anOldSessionIsRenewedEvenBeforeItExpires() throws Exception {
        long now = System.currentTimeMillis();
        SecureStore.ChatGptTokens fresh = new SecureStore.ChatGptTokens("", "a", "r", "x", now - 1000L);
        SecureStore.ChatGptTokens old = new SecureStore.ChatGptTokens("", "a", "r", "x",
                now - ChatGptAuth.MAX_SESSION_AGE_MS);
        SecureStore.ChatGptTokens legacy = new SecureStore.ChatGptTokens("", "a", "r", "x", 0L);
        assertFalse(ChatGptAuth.sessionAged(fresh, now));
        assertTrue(ChatGptAuth.sessionAged(old, now));
        assertTrue("a session saved before renewal times were kept is renewed once",
                ChatGptAuth.sessionAged(legacy, now));
        // And renewing stamps the new session, so it is not renewed again.
        signIn(inSeconds(30), "refresh-old");
        get(null);
        assertFalse(ChatGptAuth.sessionAged(SecureStore.loadChatGptTokens(context), System.currentTimeMillis()));
    }

    @Test public void aTemporaryFailureKeepsAWorkingSessionInUse() throws Exception {
        signIn(inSeconds(3600), "refresh-old");
        ChatGptAuth.markRefreshDue();
        behaviour = form -> new ChatGptAuth.TokenResponse(503, "{\"error\":\"unavailable\"}");
        Outcome out = get(null);
        assertNull(out.error);
        assertEquals("refresh-old", out.tokens.refreshToken);
        assertNotNull("never signed out for a server problem", SecureStore.loadChatGptTokens(context));
    }

    @Test public void aTemporaryFailureOnAnExpiredSessionReportsAProblemNotASignOut() throws Exception {
        signIn(inSeconds(30), "refresh-old");
        behaviour = form -> { throw new java.io.IOException("timeout"); };
        Outcome out = get(null);
        assertNull(out.tokens);
        assertFalse(out.error.contains("Sign in with ChatGPT"));
        assertNotNull(SecureStore.loadChatGptTokens(context));
        assertFalse(ChatGptAuth.needsSignIn(context, out.error));
    }

    @Test public void refusedCredentialsEndTheSessionOnceAndAreNeverRetried() throws Exception {
        signIn(inSeconds(30), "refresh-old");
        behaviour = form -> new ChatGptAuth.TokenResponse(400,
                "{\"error\":\"invalid_grant\",\"error_description\":\"refresh_token_reused\"}");
        Outcome out = get(null);
        assertEquals(ChatGptAuth.SESSION_ENDED, out.error);
        assertNull(SecureStore.loadChatGptTokens(context));
        assertTrue("the failure offers Sign in again", ChatGptAuth.needsSignIn(context, out.error));
        Outcome again = get(null);
        assertNotNull(again.error);
        assertEquals("a refused session is not sent to OpenAI again", 1, refreshCalls.get());
        // Signing in again clears the prompt.
        signIn(inSeconds(3600), "refresh-2");
        assertFalse(ChatGptAuth.needsSignIn(context, out.error));
    }

    @Test public void concurrentRequestsShareOneRenewal() throws Exception {
        signIn(inSeconds(30), "refresh-old");
        CountDownLatch release = new CountDownLatch(1);
        behaviour = form -> {
            release.await(5, TimeUnit.SECONDS);
            return renewed(jwt("acct", inSeconds(3600)), "refresh-new");
        };
        int n = 4;
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger ok = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            new Thread(() -> ChatGptAuth.getValidTokens(context, null, new ChatGptAuth.TokenCallback() {
                @Override public void onSuccess(SecureStore.ChatGptTokens t) {
                    if ("refresh-new".equals(t.refreshToken)) ok.incrementAndGet();
                    done.countDown();
                }
                @Override public void onError(String message) { done.countDown(); }
            })).start();
        }
        Thread.sleep(200);
        release.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(n, ok.get());
        assertEquals("a rotating refresh token is used exactly once", 1, refreshCalls.get());
    }

    @Test public void aRejectionAlreadyRenewedByAnotherRequestDoesNotRenewTwice() throws Exception {
        signIn(inSeconds(3600), "refresh-current");
        Outcome out = get("an-older-access-token");
        assertEquals("refresh-current", out.tokens.refreshToken);
        assertEquals(0, refreshCalls.get());
        // The token actually in use being rejected does renew.
        Outcome renewed = get(SecureStore.loadChatGptTokens(context).accessToken);
        assertEquals("refresh-new", renewed.tokens.refreshToken);
        assertEquals(1, refreshCalls.get());
    }

    @Test public void anUnexplainedFailureRenewsBeforeTheNextRequestOnly() throws Exception {
        signIn(inSeconds(3600), "refresh-old");
        ChatGptAuth.markRefreshDue();
        assertEquals("refresh-new", get(null).tokens.refreshToken);
        get(null);
        assertEquals(1, refreshCalls.get());
    }

    @Test public void signingOutDuringARenewalIsNeverUndone() throws Exception {
        signIn(inSeconds(30), "refresh-old");
        behaviour = form -> {
            ChatGptAuth.logout(context);
            return renewed(jwt("acct", inSeconds(3600)), "refresh-new");
        };
        Outcome out = get(null);
        assertNull(out.tokens);
        assertNull(SecureStore.loadChatGptTokens(context));
    }

    @Test public void onlyARefusalCountsAsInvalidCredentials() {
        assertTrue(ChatGptAuth.refreshRefused(401, ""));
        assertTrue(ChatGptAuth.refreshRefused(400, "{\"error\":\"invalid_grant\"}"));
        assertTrue(ChatGptAuth.refreshRefused(400, "{\"error\":{\"code\":\"refresh_token_expired\"}}"));
        assertFalse(ChatGptAuth.refreshRefused(400, "{\"error\":\"invalid_request\"}"));
        assertFalse(ChatGptAuth.refreshRefused(429, "invalid_grant"));
        assertFalse(ChatGptAuth.refreshRefused(500, ""));
        assertFalse(ChatGptAuth.refreshRefused(503, null));
    }

    @Test public void everyChatGptRequestPathSharesTheRecovery() {
        String client = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/ChatGptClient.java");
        // Chat (text and pictures alike), Routine planning and completions all start here, and an
        // Auto turn reaches the same send() with its frozen route, so one recovery covers them all.
        assertEquals(4, count(client, "ChatGptAuth.getValidTokens("));
        assertEquals("a repeat 401 ends the session instead of looping", 2,
                count(client, "ChatGptAuth.sessionRejected(context, tokens)"));
        assertEquals(4, count(client, "ChatGptAuth.markRefreshDue()"));
        assertFalse(client.contains("getValidTokens(context, true"));
    }

    @Test public void credentialsNeverReachTheVisibleError() throws Exception {
        signIn(inSeconds(30), "refresh-secret-value");
        behaviour = form -> new ChatGptAuth.TokenResponse(502, "bad gateway");
        Outcome out = get(null);
        assertFalse(out.error.contains("refresh-secret-value"));
        assertFalse(out.error.contains(SecureStore.loadChatGptTokens(context).accessToken));
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) n++;
        return n;
    }
}
