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
 * The in-app Roadmap is future-only, and it drifted: it still offered natural-language Routine
 * creation as upcoming long after Create with Orbit shipped it in the 0.7.3 series. This keeps the
 * page honest by naming the features Orbit has actually released.
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

    @Test public void genuinelyUnfinishedWorkIsStillListed() {
        String text = roadmapText();
        assertTrue(text.contains("NEXT UP"));
        assertTrue(text.contains("PLANNED"));
        assertTrue(text.contains("EXPLORING"));
        assertTrue("more than one branch point is genuinely unfinished",
                text.contains("More branch points & conditions"));
        assertTrue(text.contains("Deeper Android actions"));
        assertTrue(text.contains("Proactive screen intelligence"));
    }

    /** The remaining 0.7.7 direction must be discoverable in the app, not only in git. */
    @Test public void theProviderAndOnDeviceDirectionIsListed() {
        String text = roadmapText();
        assertTrue("finishing OpenRouter chat is the next provider work",
                text.contains("OpenRouter chat"));
        assertTrue("local tool calling is the next Orbit Local work",
                text.contains("Local device actions"));
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

    @Test public void thePageStillSaysItIsFutureOnly() {
        assertTrue(roadmapText().contains("future-only"));
    }
}
