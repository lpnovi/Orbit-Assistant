package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Smart Routing 1.1 (0.8.3.0-beta.6, policy 2): the GPT-5.6 candidates and the states in which
 * each one really is chosen, OpenRouter as an opt-in metered provider with a curated set of exact
 * routes, and the line between Orbit Auto and OpenRouter Auto.
 *
 * <p>Reachability is proven with the phone's real state where possible (signed in to ChatGPT, a
 * model the account refused, a Favorite) through {@link SmartRouter#route(Context, SmartRouter.Request)},
 * never by handing the router metadata no real catalog would produce.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class SmartRouting11Test {
    private Context context;

    private static final String NORMAL = "Explain how tides work";
    private static final String HARD = "Prove that the square root of two is irrational, step by "
            + "step, and explain why the proof works.";

    @Before public void setUp() {
        TestKeystore.install();
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        context.getSharedPreferences("orbit_model_availability", Context.MODE_PRIVATE).edit().clear().commit();
        SecureStore.clearChatGpt(context);
        OrbitModelCatalog.clearDynamicForTest();
    }

    @After public void tearDown() {
        OrbitModelCatalog.clearDynamicForTest();
        TestKeystore.uninstall();
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static String jwt(long exp) throws Exception {
        JSONObject auth = new JSONObject().put("chatgpt_account_id", "acct-test")
                .put("chatgpt_plan_type", "plus");
        JSONObject claims = new JSONObject().put("email", "user@example.com").put("exp", exp)
                .put("https://api.openai.com/auth", auth);
        String payload = Base64.encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        return "e30." + payload + ".sig";
    }

    private void signInChatGpt() throws Exception {
        long exp = System.currentTimeMillis() / 1000L + 3600L;
        ChatGptAuth.storeSignIn(context, jwt(exp), jwt(exp), "refresh-test");
        assertTrue(ChatGptAuth.isSignedIn(context));
    }

    private SmartRouter.Route route(String prompt) {
        return SmartRouter.route(context, new SmartRouter.Request(prompt, 0, 0, 2_000, 200));
    }

    private static void assertChose(SmartRouter.Route route, String provider, String model,
                                    AiStrength strength) {
        assertTrue("routed: " + route.error, route.ok());
        assertEquals(provider + "/" + model, route.selection.provider + "/" + route.selection.model);
        assertEquals(strength, route.selection.strength);
        assertEquals(2, route.policy);
    }

    private Map<String, Object> autoPrefs() {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<String, ?> e : Prefs.get(context).getAll().entrySet()) {
            if (e.getKey().startsWith("auto_use_")) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    // ---- policy version ---------------------------------------------------------------------------

    @Test public void newRoutesArePolicyTwoAndOldRecordsKeepTheirNumber() throws Exception {
        assertEquals(2, SmartRouter.POLICY_VERSION);
        signInChatGpt();
        assertEquals(2, route(NORMAL).policy);

        AiSelection luna = AiSelection.of(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.LUNA, AiStrength.MEDIUM);
        JSONObject beta5 = new JSONObject().put("requested", "auto").put("policy", 1)
                .put("reason", "Normal conversation");
        assertEquals("a Beta 5 record is never reinterpreted", 1,
                SmartRouter.Route.fromJson(beta5, luna).policy);
        JSONObject unnumbered = new JSONObject().put("requested", "auto").put("reason", "x");
        assertEquals("a record without a number predates policy 2", 1,
                SmartRouter.Route.fromJson(unnumbered, luna).policy);

        ResponseDetails old = ResponseDetails.fromJson(new JSONObject().put("provider", "chatgpt")
                .put("model", OrbitModelCatalog.LUNA).put("requested", "auto").put("routerPolicy", 1));
        assertEquals(1, old.routerPolicy);
    }

    // ---- ordinary requests are unchanged ----------------------------------------------------------

    @Test public void anOrdinaryAccountStillRoutesToGpt6() throws Exception {
        signInChatGpt();
        assertChose(route(NORMAL), Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.LUNA, AiStrength.MEDIUM);
        assertChose(route(HARD), Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.SOL, AiStrength.HIGH);
        assertTrue(SmartRouter.isCandidate(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.LUNA));
        assertTrue(SmartRouter.isCandidate(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.SOL));
    }

    // ---- GPT-5.6 reachability --------------------------------------------------------------------

    @Test public void everyGpt56ModelIsACandidate() {
        for (String model : new String[]{OrbitModelCatalog.GPT_5_6_LUNA, OrbitModelCatalog.GPT_5_6_TERRA,
                OrbitModelCatalog.GPT_5_6_SOL}) {
            assertTrue(model, SmartRouter.isCandidate(Prefs.PROVIDER_CHATGPT, model));
        }
    }

    @Test public void gpt56LunaAnswersWhenThisAccountCannotReachGpt6Luna() throws Exception {
        signInChatGpt();
        ModelAvailability.markUnavailable(context, Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.LUNA);
        assertChose(route(NORMAL), Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.GPT_5_6_LUNA, AiStrength.MEDIUM);
        ModelAvailability.markAvailable(context, Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.LUNA);
        assertChose(route(NORMAL), Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.LUNA, AiStrength.MEDIUM);
    }

    @Test public void gpt56LunaIsUsedWhenItIsTheFavoriteOfTwoEqualModels() throws Exception {
        signInChatGpt();
        ModelLibraryStore.setFavorite(context, Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.GPT_5_6_LUNA, true);
        assertChose(route(NORMAL), Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.GPT_5_6_LUNA, AiStrength.MEDIUM);
        assertChose("a Favorite never changes the tier a harder request needs", route(HARD),
                Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.SOL, AiStrength.HIGH);
    }

    private static void assertChose(String why, SmartRouter.Route route, String provider,
                                    String model, AiStrength strength) {
        assertTrue(why + ": " + route.error, route.ok());
        assertEquals(why, provider + "/" + model, route.selection.provider + "/" + route.selection.model);
        assertEquals(why, strength, route.selection.strength);
    }

    @Test public void gpt56SolTakesComplexWorkWhenGpt61SolIsUnreachable() throws Exception {
        signInChatGpt();
        ModelAvailability.markUnavailable(context, Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.SOL);
        assertChose(route(HARD), Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.GPT_5_6_SOL, AiStrength.HIGH);
        ModelAvailability.markAvailable(context, Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.SOL);
        ModelLibraryStore.setFavorite(context, Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.GPT_5_6_SOL, true);
        assertChose(route(HARD), Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.GPT_5_6_SOL, AiStrength.HIGH);
        assertChose("Sol tier is never used for ordinary chat", route(NORMAL),
                Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.LUNA, AiStrength.MEDIUM);
    }

    @Test public void gpt56TerraTakesComplexWorkWhenNeitherSolIsReachable() throws Exception {
        signInChatGpt();
        ModelAvailability.markUnavailable(context, Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.SOL);
        ModelAvailability.markUnavailable(context, Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.GPT_5_6_SOL);
        assertChose("the balanced tier is the closest fit", route(HARD),
                Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.GPT_5_6_TERRA, AiStrength.HIGH);
    }

    @Test public void gpt56TerraIsUsedForConversationWhenItIsTheFavorite() throws Exception {
        signInChatGpt();
        ModelLibraryStore.setFavorite(context, Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.GPT_5_6_TERRA, true);
        assertChose(route(NORMAL), Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.GPT_5_6_TERRA, AiStrength.MEDIUM);
        assertChose("but complex work still goes to the Sol tier", route(HARD),
                Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.SOL, AiStrength.HIGH);
        assertChose("and a trivial request is not Terra's", route("hi"),
                Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.LUNA, AiStrength.LOW);
    }

    // ---- OpenRouter cost safety -------------------------------------------------------------------

    @Test public void openRouterIsOffForAutoByDefaultHoweverItWasConnected() throws Exception {
        assertFalse(AutoPermissions.allows(context, Prefs.PROVIDER_OPENROUTER));
        for (String source : new String[]{SecureStore.OPENROUTER_SOURCE_OAUTH,
                SecureStore.OPENROUTER_SOURCE_MANUAL}) {
            assertTrue(SecureStore.saveOpenRouterKey(context, "sk-or-v1-" + source, source));
            assertFalse(source + " connection never enables Auto",
                    AutoPermissions.allows(context, Prefs.PROVIDER_OPENROUTER));
            for (String prompt : new String[]{"hi", NORMAL, HARD}) {
                SmartRouter.Route r = route(prompt);
                assertFalse("connected but off: never routed",
                        r.ok() && Prefs.PROVIDER_OPENROUTER.equals(r.selection.provider));
            }
            SmartRouter.Route r = route(NORMAL);
            assertFalse(r.ok());
            assertTrue(r.error, r.error.contains("OpenRouter is not enabled for Auto"));
        }
    }

    @Test public void enabledOpenRouterRoutesOnlyToCuratedExactModels() throws Exception {
        assertTrue(SecureStore.saveOpenRouterKey(context, "sk-or-v1-enabled",
                SecureStore.OPENROUTER_SOURCE_OAUTH));
        AutoPermissions.set(context, Prefs.PROVIDER_OPENROUTER, true);
        assertChose(route(NORMAL), Prefs.PROVIDER_OPENROUTER, OrbitModelCatalog.OR_GPT_6_LUNA, AiStrength.MEDIUM);
        assertChose(route(HARD), Prefs.PROVIDER_OPENROUTER, OrbitModelCatalog.OR_GPT_6_1_SOL, AiStrength.HIGH);
        SmartRouter.Route image = SmartRouter.route(context, new SmartRouter.Request("What is this?", 1, 0, 3_000, 1_000));
        assertTrue(image.ok());
        assertEquals(Prefs.PROVIDER_OPENROUTER, image.selection.provider);
    }

    @Test public void aDynamicOpenRouterModelIsNeverAnAutoCandidate() throws Exception {
        JSONObject arch = new JSONObject().put("input_modalities", new JSONArray().put("text").put("image"))
                .put("output_modalities", new JSONArray().put("text"));
        JSONArray data = new JSONArray();
        for (String id : new String[]{"maker/super-model", "openrouter/auto"}) {
            data.put(new JSONObject().put("id", id).put("name", "Maker: Super").put("context_length", 2_000_000)
                    .put("architecture", arch).put("supported_parameters", new JSONArray().put("reasoning"))
                    .put("reasoning", new JSONObject().put("supported_efforts",
                            new JSONArray().put("low").put("high"))));
        }
        OrbitModelCatalog.installDynamic(Prefs.PROVIDER_OPENROUTER, ProviderCatalogRepository.parseOpenRouter(
                new JSONObject().put("data", data).toString()));
        assertTrue(SecureStore.saveOpenRouterKey(context, "sk-or-v1-x", SecureStore.OPENROUTER_SOURCE_MANUAL));
        AutoPermissions.set(context, Prefs.PROVIDER_OPENROUTER, true);
        assertFalse(SmartRouter.isCandidate(Prefs.PROVIDER_OPENROUTER, "maker/super-model"));
        for (SmartRouter.Option o : SmartRouter.options(context)) {
            assertNotEquals("maker/super-model", o.candidate.model);
        }
        // The live catalog lists neither curated slug now, so nothing OpenRouter offers is usable.
        SmartRouter.Route r = route(NORMAL);
        assertFalse(r.ok());
    }

    @Test public void orbitAutoNeverResolvesToOpenRouterAuto() throws Exception {
        assertTrue(SecureStore.saveOpenRouterKey(context, "sk-or-v1-x", SecureStore.OPENROUTER_SOURCE_OAUTH));
        AutoPermissions.set(context, Prefs.PROVIDER_OPENROUTER, true);
        assertFalse(SmartRouter.isCandidate(Prefs.PROVIDER_OPENROUTER, OrbitModelCatalog.OPENROUTER_AUTO));
        for (String prompt : new String[]{"hi", NORMAL, HARD}) {
            for (int images : new int[]{0, 2}) {
                for (int tokens : new int[]{2_000, 300_000, 950_000}) {
                    SmartRouter.Route r = SmartRouter.route(context,
                            new SmartRouter.Request(prompt, images, 0, tokens, tokens));
                    if (r.ok()) assertNotEquals(OrbitModelCatalog.OPENROUTER_AUTO, r.selection.model);
                }
            }
        }
        // Even an OpenRouter Auto option offered to the pure policy is ignored.
        AiModelSpec router = OrbitModelCatalog.spec(Prefs.PROVIDER_OPENROUTER, OrbitModelCatalog.OPENROUTER_AUTO);
        SmartRouter.Option forged = new SmartRouter.Option(new SmartRouter.Candidate(
                Prefs.PROVIDER_OPENROUTER, OrbitModelCatalog.OPENROUTER_AUTO, 0, 0, 0, 0),
                router, true, true, false, true, true, false, false);
        SmartRouter.Route r = SmartRouter.route(new SmartRouter.Request(NORMAL, 0, 0, 2_000, 200),
                Collections.singletonList(forged));
        assertFalse(r.ok());
    }

    @Test public void explicitOpenRouterAutoStaysAnExactSelection() {
        AiSelection router = AiSelection.of(Prefs.PROVIDER_OPENROUTER, OrbitModelCatalog.OPENROUTER_AUTO, null);
        AiSelection resolved = AiSelections.resolve(router);
        assertFalse("OpenRouter Auto is not Orbit Auto", resolved.isAuto());
        assertEquals(router, resolved);
        assertEquals("OpenRouter Auto", resolved.label());
    }

    @Test public void theAccountRouteIsPreferredOverOpenRouterWhenEquallySuited() throws Exception {
        signInChatGpt();
        assertTrue(SecureStore.saveOpenRouterKey(context, "sk-or-v1-x", SecureStore.OPENROUTER_SOURCE_OAUTH));
        AutoPermissions.set(context, Prefs.PROVIDER_OPENROUTER, true);
        ModelLibraryStore.setFavorite(context, Prefs.PROVIDER_OPENROUTER, OrbitModelCatalog.OR_GPT_6_LUNA, true);
        assertChose("no OpenRouter credits for what ChatGPT already covers, even for a Favorite",
                route(NORMAL), Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.LUNA, AiStrength.MEDIUM);
        assertChose(route(HARD), Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.SOL, AiStrength.HIGH);
    }

    @Test public void aDirectPaidRouteIsPreferredOverTheSameTierThroughOpenRouter() throws Exception {
        assertTrue(SecureStore.saveAnthropicKey(context, "sk-ant-test"));
        AutoPermissions.set(context, Prefs.PROVIDER_ANTHROPIC, true);
        assertTrue(SecureStore.saveOpenRouterKey(context, "sk-or-v1-x", SecureStore.OPENROUTER_SOURCE_OAUTH));
        AutoPermissions.set(context, Prefs.PROVIDER_OPENROUTER, true);
        SmartRouter.Route r = route(NORMAL);
        assertTrue(r.ok());
        assertEquals(Prefs.PROVIDER_ANTHROPIC, r.selection.provider);
        assertEquals(OrbitModelCatalog.CLAUDE_SONNET_5_5, r.selection.model);
    }

    @Test public void routingNeverChangesAPaidPermission() throws Exception {
        signInChatGpt();
        assertTrue(SecureStore.saveOpenRouterKey(context, "sk-or-v1-x", SecureStore.OPENROUTER_SOURCE_OAUTH));
        Map<String, Object> before = autoPrefs();
        for (String prompt : new String[]{"hi", NORMAL, HARD}) route(prompt);
        assertEquals(before, autoPrefs());
        assertFalse(AutoPermissions.allows(context, Prefs.PROVIDER_OPENROUTER));
        assertTrue(AutoPermissions.metered(Prefs.PROVIDER_OPENROUTER));
        assertTrue("the Auto sheet lists OpenRouter", AutoPermissions.PROVIDERS.contains(Prefs.PROVIDER_OPENROUTER));
        assertTrue(AutoSheets.describe(context, Prefs.PROVIDER_OPENROUTER, false)
                .contains(AutoSheets.METERED_NOTE));
        assertFalse(Prefs.backupSnapshot(context).toString().contains(Prefs.AUTO_USE_OPENROUTER));
    }

    @Test public void anExplicitSelectionIsStillNeverRouted() {
        for (AiSelection explicit : Arrays.asList(
                AiSelection.of(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.GPT_5_6_TERRA, AiStrength.LOW),
                AiSelection.of(Prefs.PROVIDER_OPENROUTER, OrbitModelCatalog.OR_CLAUDE_OPUS_5_5, AiStrength.MAX),
                AiSelection.of(Prefs.PROVIDER_OPENROUTER, OrbitModelCatalog.OPENROUTER_AUTO, null))) {
            assertEquals(explicit, AiSelections.resolve(explicit));
            assertFalse(AiSelections.resolve(explicit).isAuto());
        }
    }

    @Test public void theSourceStatesThePolicyChangesWhereTheyLive() {
        String router = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/SmartRouter.java");
        assertTrue(router.contains("POLICY_VERSION = 2"));
        assertTrue(router.contains("viaOpenRouter()"));
        List<String> curated = new ArrayList<>();
        for (SmartRouter.Candidate c : SmartRouter.CANDIDATES) {
            if (Prefs.PROVIDER_OPENROUTER.equals(c.provider)) curated.add(c.model);
        }
        assertEquals(Arrays.asList(OrbitModelCatalog.OR_GPT_6_LUNA, OrbitModelCatalog.OR_GPT_6_1_SOL,
                OrbitModelCatalog.OR_CLAUDE_SONNET_5_5, OrbitModelCatalog.OR_CLAUDE_OPUS_5_5), curated);
    }
}
