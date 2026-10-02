package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Smart Routing's policy, from fixed fixtures (0.8.3.0-beta.5).
 *
 * <p>Every case here is the pure routing decision: a request described by counts and signals, and
 * a fixed set of candidates with known permission and readiness. No provider is called, nothing is
 * paid for, and the same fixture must always give the same route.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class SmartRouterPolicyTest {

    @After public void tearDown() {
        OrbitModelCatalog.clearDynamicForTest();
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private static SmartRouter.Candidate candidate(String provider, String model) {
        for (SmartRouter.Candidate c : SmartRouter.CANDIDATES) {
            if (c.provider.equals(provider) && c.model.equals(model)) return c;
        }
        throw new AssertionError("not a candidate: " + provider + "/" + model);
    }

    private static SmartRouter.Option option(String provider, String model, boolean permitted,
                                             boolean ready) {
        return option(provider, model, permitted, ready, false, false);
    }

    private static SmartRouter.Option option(String provider, String model, boolean permitted,
                                             boolean ready, boolean unavailable, boolean favorite) {
        AiCapabilities caps = AiProviders.byId(provider).capabilities();
        return new SmartRouter.Option(candidate(provider, model),
                OrbitModelCatalog.spec(provider, model), permitted, ready, unavailable, favorite,
                caps.images, caps.deviceActions, caps.hostedWebSearch);
    }

    /**
     * The phone as most people will have it: ChatGPT signed in, Orbit Local ready, and Anthropic and
     * xAI connected but not enabled for Auto.
     */
    private static List<SmartRouter.Option> defaults(boolean localReady) {
        return new ArrayList<>(Arrays.asList(
                option(Prefs.PROVIDER_LOCAL, OrbitModelCatalog.ORBIT_LOCAL, true, localReady),
                option(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.LUNA, true, true),
                option(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.SOL, true, true),
                option(Prefs.PROVIDER_ANTHROPIC, OrbitModelCatalog.CLAUDE_HAIKU_4_5, false, true),
                option(Prefs.PROVIDER_ANTHROPIC, OrbitModelCatalog.CLAUDE_SONNET_5_5, false, true),
                option(Prefs.PROVIDER_ANTHROPIC, OrbitModelCatalog.CLAUDE_OPUS_5_5, false, true),
                option(Prefs.PROVIDER_XAI, OrbitModelCatalog.GROK_4_7, false, true)));
    }

    /** Only the metered providers, both enabled: ChatGPT and Local are off. */
    private static List<SmartRouter.Option> meteredOnly() {
        return new ArrayList<>(Arrays.asList(
                option(Prefs.PROVIDER_LOCAL, OrbitModelCatalog.ORBIT_LOCAL, false, true),
                option(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.LUNA, false, true),
                option(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.SOL, false, true),
                option(Prefs.PROVIDER_ANTHROPIC, OrbitModelCatalog.CLAUDE_HAIKU_4_5, true, true),
                option(Prefs.PROVIDER_ANTHROPIC, OrbitModelCatalog.CLAUDE_SONNET_5_5, true, true),
                option(Prefs.PROVIDER_ANTHROPIC, OrbitModelCatalog.CLAUDE_OPUS_5_5, true, true),
                option(Prefs.PROVIDER_XAI, OrbitModelCatalog.GROK_4_7, true, true)));
    }

    private static SmartRouter.Request text(String prompt) {
        return new SmartRouter.Request(prompt, 0, 0, 2_000, 200);
    }

    private static SmartRouter.Request sized(String prompt, int tokens) {
        return new SmartRouter.Request(prompt, 0, 0, tokens, tokens);
    }

    private static void assertRoute(SmartRouter.Route route, String provider, String model,
                                    AiStrength strength) {
        assertTrue("routed: " + route.error, route.ok());
        assertEquals(provider, route.selection.provider);
        assertEquals(model, route.selection.model);
        assertEquals(strength, route.selection.strength);
        assertEquals(SmartRouter.POLICY_VERSION, route.policy);
    }

    private static final String HARD = "Prove that the square root of two is irrational, step by "
            + "step, and explain why the proof works.";
    private static final String VERY_HARD = "Debug and optimize this algorithm, then analyze the "
            + "trade-offs of the architecture step by step:\n```java\npublic int f(int n) { "
            + "return n < 2 ? n : f(n - 1) + f(n - 2); }\n```\nWhy is it slow, and how should the "
            + "design change for millions of calls per second with strict latency targets?";

    // ---- demand ----------------------------------------------------------------------------------

    @Test public void demandIsReadFromCountsAndAFewPlainSignals() {
        assertEquals(SmartRouter.Demand.SIMPLE, SmartRouter.demand("hi there", 0, 0, 0));
        assertEquals(SmartRouter.Demand.SIMPLE, SmartRouter.demand("Thanks, that helps!", 0, 0, 0));
        assertEquals("an explanation is ordinary conversation, never a tiny request",
                SmartRouter.Demand.NORMAL, SmartRouter.demand("Explain quantum computing", 0, 0, 0));
        assertEquals("an image is never a simple request", SmartRouter.Demand.NORMAL,
                SmartRouter.demand("What is this?", 1, 0, 0));
        assertEquals(SmartRouter.Demand.NORMAL, SmartRouter.demand(
                "Can you help me write a short, friendly note to my neighbour about their dog?",
                0, 0, 0));
        assertEquals(SmartRouter.Demand.COMPLEX, SmartRouter.demand(HARD, 0, 0, 0));
        assertEquals(SmartRouter.Demand.VERY_COMPLEX, SmartRouter.demand(VERY_HARD, 0, 0, 0));
        assertEquals("a long document is more than ordinary conversation",
                SmartRouter.Demand.COMPLEX, SmartRouter.demand("Summarize this", 0, 60_000, 0));
    }

    // ---- simple and Local ------------------------------------------------------------------------

    @Test public void aTrivialRequestUsesOrbitLocalWhenItIsReady() {
        SmartRouter.Route route = SmartRouter.route(text("hi there"), defaults(true));
        assertRoute(route, Prefs.PROVIDER_LOCAL, OrbitModelCatalog.ORBIT_LOCAL, null);
        assertEquals(SmartRouter.LOCAL, route.reason);
    }

    @Test public void withoutLocalATrivialRequestUsesTheLightModelAtLow() {
        SmartRouter.Route route = SmartRouter.route(text("hi there"), defaults(false));
        assertRoute(route, Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.LUNA, AiStrength.LOW);
        assertEquals(SmartRouter.SIMPLE, route.reason);
    }

    @Test public void localIsNeverOfferedMoreThanItsWindowHoldsWhole() {
        // A short follow-up in a long chat: Local would have to drop most of the conversation.
        SmartRouter.Route route = SmartRouter.route(
                new SmartRouter.Request("and the second one?", 0, 0, 9_000, 6_000), defaults(true));
        assertNotEquals(Prefs.PROVIDER_LOCAL, route.selection.provider);
        assertRoute(route, Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.LUNA, AiStrength.LOW);
    }

    @Test public void localIsNotUsedForOrdinaryOrHarderRequests() {
        assertNotEquals(Prefs.PROVIDER_LOCAL,
                SmartRouter.route(text("Explain quantum computing"), defaults(true)).selection.provider);
        assertNotEquals(Prefs.PROVIDER_LOCAL,
                SmartRouter.route(text(HARD), defaults(true)).selection.provider);
    }

    @Test public void localIsNotUsedForCurrentInformation() {
        SmartRouter.Route route = SmartRouter.route(text("latest news"), defaults(true));
        assertEquals(Prefs.PROVIDER_CHATGPT, route.selection.provider);
        assertEquals("Current information + simple request", route.reason);
    }

    @Test public void localNeverReceivesAnImage() {
        List<SmartRouter.Option> localOnly = new ArrayList<>(Arrays.asList(
                option(Prefs.PROVIDER_LOCAL, OrbitModelCatalog.ORBIT_LOCAL, true, true)));
        SmartRouter.Route route = SmartRouter.route(
                new SmartRouter.Request("hi", 1, 0, 1_500, 1_200), localOnly);
        assertFalse(route.ok());
        assertTrue(route.error.startsWith("No Auto-enabled model can handle this image."));
    }

    // ---- ordinary and harder requests --------------------------------------------------------------

    @Test public void normalConversationUsesTheGeneralModelAtMedium() {
        SmartRouter.Route route = SmartRouter.route(text("Explain quantum computing"), defaults(true));
        assertRoute(route, Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.LUNA, AiStrength.MEDIUM);
        assertEquals(SmartRouter.NORMAL, route.reason);
    }

    @Test public void complexReasoningUsesTheStrongModelAtHigh() {
        SmartRouter.Route route = SmartRouter.route(text(HARD), defaults(true));
        assertRoute(route, Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.SOL, AiStrength.HIGH);
        assertEquals(SmartRouter.COMPLEX, route.reason);
    }

    @Test public void veryDifficultWorkUsesExtraHighButNeverMax() {
        SmartRouter.Route route = SmartRouter.route(text(VERY_HARD), defaults(true));
        assertRoute(route, Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.SOL, AiStrength.XHIGH);
        for (SmartRouter.Candidate c : SmartRouter.CANDIDATES) {
            AiModelSpec spec = OrbitModelCatalog.spec(c.provider, c.model);
            for (SmartRouter.Demand d : SmartRouter.Demand.values()) {
                AiStrength s = SmartRouter.strengthFor(spec, d);
                assertNotEquals("Auto never asks for Max", AiStrength.MAX, s);
                if (spec.hasStrengths()) assertTrue(c.model + " " + d, spec.supports(s));
                else assertNull(s);
            }
        }
    }

    // ---- images ----------------------------------------------------------------------------------

    @Test public void anImageGoesToAVisionModel() {
        SmartRouter.Route route = SmartRouter.route(
                new SmartRouter.Request("What is in this picture?", 1, 0, 3_000, 1_500), defaults(true));
        assertRoute(route, Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.LUNA, AiStrength.MEDIUM);
        assertEquals("Image input required + normal conversation", route.reason);
    }

    @Test public void aConnectedButDisabledProviderIsNamedAndNotUsed() {
        // Only Orbit Local may be used, and it cannot read images. Anthropic could, but is off.
        List<SmartRouter.Option> options = new ArrayList<>(Arrays.asList(
                option(Prefs.PROVIDER_LOCAL, OrbitModelCatalog.ORBIT_LOCAL, true, true),
                option(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.LUNA, false, false),
                option(Prefs.PROVIDER_ANTHROPIC, OrbitModelCatalog.CLAUDE_SONNET_5_5, false, true)));
        SmartRouter.Route route = SmartRouter.route(
                new SmartRouter.Request("What is this?", 1, 0, 3_000, 1_500), options);
        assertFalse(route.ok());
        assertNull(route.selection);
        assertTrue(route.error, route.error.startsWith(
                "Anthropic supports this request, but Anthropic is not enabled for Auto."));
        assertTrue(route.error.contains("Settings > Intelligence > Auto"));
    }

    // ---- context ---------------------------------------------------------------------------------

    @Test public void aModelTooSmallForTheRequestIsExcludedAndALargerOneUsed() {
        SmartRouter.Route route = SmartRouter.route(
                sized("Summarize what we discussed", 300_000), meteredOnly());
        assertTrue(route.ok());
        assertNotEquals("Haiku's 200K window cannot hold it", OrbitModelCatalog.CLAUDE_HAIKU_4_5,
                route.selection.model);
        assertTrue(route.reason.startsWith(SmartRouter.LARGE_CONTEXT));
    }

    @Test public void contextIsNeverTrimmedToForceASmallerModel() {
        // Only Haiku is enabled; Grok could hold the request but is not enabled. Auto refuses and says
        // so rather than cutting the conversation down to Haiku's size.
        List<SmartRouter.Option> options = new ArrayList<>(Arrays.asList(
                option(Prefs.PROVIDER_ANTHROPIC, OrbitModelCatalog.CLAUDE_HAIKU_4_5, true, true),
                option(Prefs.PROVIDER_XAI, OrbitModelCatalog.GROK_4_7, false, true)));
        SmartRouter.Route route = SmartRouter.route(sized("Go on", 300_000), options);
        assertFalse(route.ok());
        assertTrue(route.error, route.error.startsWith(
                "xAI supports this request, but xAI is not enabled for Auto."));

        options.set(1, option(Prefs.PROVIDER_XAI, OrbitModelCatalog.GROK_4_7, false, false));
        route = SmartRouter.route(sized("Go on", 300_000), options);
        assertTrue(route.error, route.error.startsWith(
                "No Auto-enabled model can fit this much context."));
    }

    @Test public void contextFitLeavesRoomForTheAnswer() {
        assertTrue(SmartRouter.fits(100_000, 200_000));
        assertFalse("a request that would fill the window leaves no room to answer",
                SmartRouter.fits(195_000, 200_000));
        assertFalse("an unknown window never fits", SmartRouter.fits(10, 0));
    }

    @Test public void aModelWithoutAKnownWindowIsNeverAnAutoCandidate() {
        AiModelSpec noWindow = new AiModelSpec(OrbitModelCatalog.CLAUDE_SONNET_5_5,
                "Claude Sonnet 5.5", "Claude 5", Prefs.PROVIDER_ANTHROPIC, "", AiStrength.HIGH,
                true, 0, true, true, true, true, false, true, "dynamic_provider", "active",
                AiStrength.LOW, AiStrength.MEDIUM, AiStrength.HIGH);
        OrbitModelCatalog.installDynamic(Prefs.PROVIDER_ANTHROPIC, Collections.singletonList(noWindow));
        List<SmartRouter.Option> options = new ArrayList<>(Collections.singletonList(
                option(Prefs.PROVIDER_ANTHROPIC, OrbitModelCatalog.CLAUDE_SONNET_5_5, true, true)));
        assertFalse(SmartRouter.route(text("Explain tides"), options).ok());
    }

    @Test public void aModelTheCatalogNoLongerOffersIsExcluded() {
        AiModelSpec other = new AiModelSpec("claude-other", "Claude Other", "Claude",
                Prefs.PROVIDER_ANTHROPIC, "", AiStrength.HIGH, true, 1_000_000, true, true, true,
                true, false, true, "dynamic_provider", "active",
                AiStrength.LOW, AiStrength.MEDIUM, AiStrength.HIGH);
        OrbitModelCatalog.installDynamic(Prefs.PROVIDER_ANTHROPIC, Collections.singletonList(other));
        SmartRouter.Route route = SmartRouter.route(text("Explain tides"), meteredOnly());
        assertTrue(route.ok());
        assertEquals("only Grok remains a curated, offered candidate", OrbitModelCatalog.GROK_4_7,
                route.selection.model);
        assertFalse("an account-discovered model is never a candidate by appearing",
                SmartRouter.isCandidate(Prefs.PROVIDER_ANTHROPIC, "claude-other"));
    }

    // ---- availability ----------------------------------------------------------------------------

    @Test public void anUnavailableProviderIsSkippedCleanly() {
        List<SmartRouter.Option> options = defaults(false);
        options.set(1, option(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.LUNA, true, false));
        options.set(2, option(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.SOL, true, false));
        SmartRouter.Route route = SmartRouter.route(text("Explain tides"), options);
        assertFalse("Anthropic and xAI stay off even when ChatGPT is signed out", route.ok());
        assertTrue(route.error.startsWith("Anthropic supports this request"));
    }

    @Test public void nothingEnabledAndReadyIsReportedPlainly() {
        List<SmartRouter.Option> options = new ArrayList<>(Arrays.asList(
                option(Prefs.PROVIDER_LOCAL, OrbitModelCatalog.ORBIT_LOCAL, true, false),
                option(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.LUNA, true, false)));
        SmartRouter.Route route = SmartRouter.route(text("Explain tides"), options);
        assertFalse(route.ok());
        assertTrue(route.error.startsWith("No Auto-enabled provider is currently available."));
    }

    @Test public void anUnavailableModelIsSkippedForTheNextSuitableOne() {
        List<SmartRouter.Option> options = defaults(false);
        options.set(1, option(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.LUNA, true, true, true, false));
        SmartRouter.Route route = SmartRouter.route(text("Explain tides"), options);
        assertRoute(route, Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.SOL, AiStrength.MEDIUM);
        assertEquals("Normal conversation + preferred eligible model", route.reason);
    }

    // ---- preferences and ties --------------------------------------------------------------------

    @Test public void aPaidProviderIsNotPreferredJustForBeingThere() {
        List<SmartRouter.Option> all = meteredOnly();
        all.set(1, option(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.LUNA, true, true));
        all.set(2, option(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.SOL, true, true));
        // Claude Sonnet is a Favorite; it still does not outrank the user's own ChatGPT account.
        all.set(4, option(Prefs.PROVIDER_ANTHROPIC, OrbitModelCatalog.CLAUDE_SONNET_5_5, true, true,
                false, true));
        assertEquals(Prefs.PROVIDER_CHATGPT,
                SmartRouter.route(text("Explain tides"), all).selection.provider);
        assertEquals(Prefs.PROVIDER_CHATGPT, SmartRouter.route(text(HARD), all).selection.provider);
    }

    @Test public void aFavoriteBreaksOnlyAnExactTie() {
        List<SmartRouter.Option> options = meteredOnly();
        assertEquals("fixed order without a Favorite", OrbitModelCatalog.CLAUDE_SONNET_5_5,
                SmartRouter.route(text("Explain tides"), options).selection.model);
        options.set(6, option(Prefs.PROVIDER_XAI, OrbitModelCatalog.GROK_4_7, true, true, false, true));
        assertEquals("an equally suitable Favorite wins the tie", OrbitModelCatalog.GROK_4_7,
                SmartRouter.route(text("Explain tides"), options).selection.model);
        assertEquals("a Favorite never overrides context", OrbitModelCatalog.CLAUDE_SONNET_5_5,
                SmartRouter.route(sized("Explain tides", 600_000), options).selection.model);
    }

    @Test public void aRouteThatCanRunPhoneActionsIsPreferredWhenOneExists() {
        String action = "turn on the flashlight";
        assertTrue(new SmartRouter.Request(action, 0, 0, 0, 0).deviceAction);
        List<SmartRouter.Option> both = meteredOnly();
        both.set(1, option(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.LUNA, true, true));
        SmartRouter.Route route = SmartRouter.route(text(action), both);
        assertEquals(Prefs.PROVIDER_CHATGPT, route.selection.provider);
        assertTrue(route.reason.startsWith(SmartRouter.DEVICE_ACTION));
        assertTrue("without one, Auto still answers with what is allowed",
                SmartRouter.route(text(action), meteredOnly()).ok());
    }

    // ---- stability -------------------------------------------------------------------------------

    @Test public void identicalConditionsAlwaysRouteTheSameWay() {
        String[] prompts = {"hi", "Explain tides", HARD, VERY_HARD, "latest news"};
        for (String prompt : prompts) {
            SmartRouter.Route first = SmartRouter.route(text(prompt), meteredOnly());
            for (int i = 0; i < 25; i++) {
                List<SmartRouter.Option> shuffled = meteredOnly();
                Collections.shuffle(shuffled, new java.util.Random(i));
                SmartRouter.Route again = SmartRouter.route(text(prompt), shuffled);
                assertEquals(prompt, first.selection, again.selection);
                assertEquals(prompt, first.reason, again.reason);
            }
        }
    }

    @Test public void reasonsAreShortFactualCategories() {
        Set<String> allowed = new HashSet<>(Arrays.asList(SmartRouter.SIMPLE, SmartRouter.NORMAL,
                SmartRouter.COMPLEX, SmartRouter.IMAGE, SmartRouter.LARGE_CONTEXT, SmartRouter.LOCAL,
                SmartRouter.DEVICE_ACTION, SmartRouter.CURRENT, SmartRouter.PREFERRED));
        List<SmartRouter.Route> routes = new ArrayList<>();
        for (List<SmartRouter.Option> options : Arrays.asList(defaults(true), defaults(false),
                meteredOnly())) {
            for (SmartRouter.Request r : Arrays.asList(text("hi"), text("Explain tides"), text(HARD),
                    text(VERY_HARD), text("latest news"), sized("Go on", 300_000),
                    new SmartRouter.Request("What is this?", 2, 0, 4_000, 3_000))) {
                routes.add(SmartRouter.route(r, options));
            }
        }
        for (SmartRouter.Route route : routes) {
            if (!route.ok()) continue;
            for (String part : route.reason.split(" \\+ ")) {
                String normalized = Character.toUpperCase(part.charAt(0)) + part.substring(1);
                assertTrue(route.reason, allowed.contains(normalized));
            }
            assertFalse(route.reason.matches(".*\\d.*"));
            assertTrue(route.reason.split(" \\+ ").length <= 2);
        }
    }

    // ---- the curated set -------------------------------------------------------------------------

    @Test public void theCandidateSetIsSmallCuratedAndCurrent() {
        // Policy 2 (0.8.3.0-beta.6): Beta 5's seven, the three GPT-5.6 models, and four exact
        // OpenRouter routes. Never OpenRouter Auto, and never anything merely listed by a catalog.
        assertEquals(14, SmartRouter.CANDIDATES.size());
        assertFalse(SmartRouter.isCandidate(Prefs.PROVIDER_OPENROUTER, OrbitModelCatalog.OPENROUTER_AUTO));
        for (SmartRouter.Candidate c : SmartRouter.CANDIDATES) {
            AiModelSpec spec = OrbitModelCatalog.spec(c.provider, c.model);
            assertNotNull(c.model + " must be in the trusted catalog", spec);
            assertEquals("active", spec.availability);
            assertTrue(c.model, Prefs.PROVIDER_LOCAL.equals(c.provider) || spec.contextWindowTokens > 0);
        }
        assertFalse("access varies by account", SmartRouter.isCandidate(Prefs.PROVIDER_CHATGPT,
                OrbitModelCatalog.ASTRA));
        assertFalse("the relay spends an operator's key", SmartRouter.isCandidate(Prefs.PROVIDER_RELAY,
                OrbitModelCatalog.LUNA));
        assertFalse(SmartRouter.isCandidate(Prefs.PROVIDER_ANTHROPIC, OrbitModelCatalog.CLAUDE_FABLE_5_1));
    }
}
