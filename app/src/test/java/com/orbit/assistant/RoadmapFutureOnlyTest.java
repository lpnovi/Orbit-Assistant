package com.orbit.assistant;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * The in-app Roadmap is future-only, and it keeps drifting in two different ways.
 *
 * <p>The first drift is the old one: the page offered natural-language Routine creation as upcoming
 * long after Create with Orbit shipped it, and several more entries went the same way. That is what
 * {@link #shippedFeaturesAreNotOfferedAsUpcoming} exists for, and the list only ever grows.
 *
 * <p>The second is worse and is what v0.7.8.4-beta.4 fixed. By the Vault line the page still opened
 * with 0.7.7-era priorities under "NEXT UP" while the whole project had moved to Orbit Vault, so
 * nothing on it was false and the page as a whole was still misleading: a reader came away with a
 * confident and wrong idea of what Orbit was working on. Stale-but-real work is therefore asserted
 * to be listed <em>below</em> the active plan rather than merely listed, and the active
 * milestone is asserted to be present in the page and in {@code ROADMAP.md} at once - see
 * {@link RoadmapSyncTest}. A milestone that ships leaves those constants and joins the list above.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class RoadmapFutureOnlyTest {

    /** Shipped work, in the wording the Roadmap used to describe it as upcoming. */
    private static final String[] ALREADY_SHIPPED = {
            "Natural-language Routine creation",
            "Automation history",
            "Richer quick access",
            // v0.7.5.0 shipped one dependable level of IF / ELSE branching.
            "Richer automation & branching",
            // v0.7.7.0 shipped the provider layer, provider management, and the first Orbit Local.
            "Choice of AI provider",
            "More AI providers & models",
            // v0.7.7.3 shipped the deterministic kitchen maths. Only the cooking session, its
            // hands-free vocabulary, and optional Orbit-owned timers remain unbuilt.
            "Kitchen conversions",
            "Cooking conversions",
            "Recipe scaling",
            "Smarter timer labels",
            // v0.7.7.4 shipped the Stable/Beta update channels themselves. The Beta channel may be
            // referred to as a way future work will be delivered, but never offered as upcoming.
            "Update channel",
            "Stable / Beta updates",
            "Choose your update channel",
            // v0.7.7.5 promoted Modular Orbit Local from Beta to Stable, so the component itself
            // is shipped work. Teaching it Orbit's device actions is what genuinely remains.
            "Modular Orbit Local",
            // v0.7.8.4 beta.1 to beta.3 shipped the Vault itself, saving from Orbit's own
            // surfaces, notes, Ask Orbit, Attach from Vault and the Deck destinations. Betas 4 to
            // 6 finished organization and polish, and the complete line is now Stable.
            //
            // "Save to Vault" is deliberately absent from this list: it is shipped, and it is also
            // a real part of the Rich Answers plan, where an inline web image has to reach the
            // Vault through the path that already exists rather than a second one.
            "Orbit Vault arrives",
            "Vault organization",
            "Quick Capture",
            "Attach from Vault",
            "Vault notes",
            "Theme Studio",
            // v0.7.8.5 shipped Rich Answers, Settings search and optional GPT-6 Astra, and
            // was promoted to Stable from the tested Beta 9. All three left OrbitRoadmap with
            // it, and none of them may be offered on a future-only page any more.
            "Rich Answers / Visual Web Results",
            "Settings search",
            "GPT-6 Astra",
    };

    private String roadmapText() {
        Activity activity = Robolectric.buildActivity(RoadmapActivity.class).setup().get();
        List<String> found = new ArrayList<>();
        collect(activity.getWindow().getDecorView(), found);
        return String.join("\n", found);
    }

    private static void collect(View view, List<String> into) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null) into.add(text.toString());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), into);
        }
    }

    @Test public void shippedFeaturesAreNotOfferedAsUpcoming() {
        String text = roadmapText();
        for (String shipped : ALREADY_SHIPPED) {
            assertFalse(shipped + " has already shipped and must not appear on the Roadmap",
                    text.contains(shipped));
        }
    }

    /** The page leads with what Orbit is building, then what follows it. */
    @Test public void thePageLeadsWithTheCurrentPlan() {
        String text = roadmapText();
        assertTrue("the page must say what is being built now", text.contains("NOW - 0.7.8.6"));
        assertTrue(text.contains("LATER"));
        assertTrue(text.contains("EXPLORING"));

        assertTrue("Smart Vault is the current line", text.contains(OrbitRoadmap.CURRENT));
    }

    /**
     * Order matters more than presence here. Every entry below was true before this release too;
     * what was wrong was that they sat above the actual plan.
     */
    @Test public void olderIdeasSitBelowTheActivePlan() {
        String text = roadmapText();
        int current = text.indexOf(OrbitRoadmap.CURRENT);
        assertTrue("the current work must be listed first", current >= 0);

        for (String older : new String[]{"Local device actions", "Calendar awareness",
                "More branch points & conditions", "Deeper Android actions", "Cook with Orbit",
                "OpenRouter chat", "Hybrid Auto", "Proactive screen intelligence"}) {
            int at = text.indexOf(older);
            assertTrue(older + " is still genuinely unfinished and must still be listed", at >= 0);
            assertTrue(older + " must not be presented above the active plan", at > current);
        }
    }

    /**
     * The remaining 0.7.7 direction is still promised, just no longer promised next.
     */
    @Test public void theProviderAndOnDeviceDirectionIsStillListed() {
        String text = roadmapText();
        assertTrue("finishing OpenRouter chat is still owed", text.contains("OpenRouter chat"));
        assertTrue("local tool calling is still owed", text.contains("Local device actions"));
        assertTrue("the withdrawn Edit & resend action must be promised back",
                text.contains("Edit & resend, reliably"));
        assertTrue(text.contains("Hybrid Auto"));
    }

    /**
     * The cooking direction is genuinely ahead of Orbit, and one part of it carries a promise the
     * page must keep making: Android's Clock app stays, and Orbit-managed timers are opt-in.
     */
    @Test public void theCookingDirectionIsListedAsFutureWork() {
        String text = roadmapText();
        assertTrue("the cooking session itself is unbuilt", text.contains("Cook with Orbit"));
        assertTrue("hands-free cooking voice is unbuilt", text.contains("Kitchen hands-free"));
        assertTrue("Orbit-owned timers are unbuilt", text.contains("Orbit-managed timers"));
        assertTrue("the Clock app must not be presented as going away",
                text.contains("Off by default") && text.contains("Clock app stays"));
    }

    /**
     * OpenRouter is not abandoned and not imminent. It needs a real account to validate against,
     * and until there is one the honest thing is to say so on the page.
     */
    @Test public void openRouterIsShownAsDeferredRatherThanNext() {
        String text = roadmapText();
        assertTrue("the deferred group must exist", text.contains("DEFERRED"));
        assertTrue("OpenRouter is still promised", text.contains("OpenRouter chat"));
        assertTrue("and the reason is stated", text.contains("account to test it with"));
        assertTrue("the existing secure groundwork is not being discarded",
                text.contains("secure setup already in Orbit stays"));
    }

    @Test public void thePageStillSaysItIsFutureOnly() {
        assertTrue(roadmapText().contains("future-only"));
    }
}
