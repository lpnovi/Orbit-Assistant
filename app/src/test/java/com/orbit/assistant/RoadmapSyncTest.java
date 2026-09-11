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
 * Orbit keeps two roadmaps, and by v0.7.8.4 they had drifted far enough to mislead.
 *
 * <p>`ROADMAP.md` is where the plan is argued out and where the development history lives.
 * {@link RoadmapActivity} is what somebody opens in About &amp; updates. Nothing kept them in step,
 * so the in-app page went on presenting 0.7.7-era priorities as next while the repository had moved
 * on through Orbit Deck, Theme Studio and three Vault Betas. Neither document was individually
 * false; together they gave two different answers to "what is Orbit working on?".
 *
 * <p>This is the smallest guard that stops it happening again, and it is deliberately small. There
 * is no Markdown parser, no schema, no generated file, and nothing that runs on a user's phone.
 * {@link OrbitRoadmap} holds the names of the active milestones, the in-app page draws its headings
 * from those constants, and this test asserts the same names appear in `ROADMAP.md` above its
 * history. Rename or drop a milestone in one place and the build says so. A milestone that ships
 * leaves those constants and is checked here as recorded history instead.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class RoadmapSyncTest {

    /** Where the plan stops and the record of how Orbit got here begins. */
    private static final String HISTORY_HEADING = "# Development history";

    /**
     * Two names this file still has to check that are no longer active milestones.
     *
     * <p>Both shipped in {@code 0.7.8.5}. They left {@link OrbitRoadmap} at Stable promotion,
     * because the in-app page is future-only and a constant for either would have kept offering a
     * user something they already have. What they must still do is stay correctly recorded in
     * `ROADMAP.md`, so the commitments made about them are named here instead.
     */
    private static final String RICH_ANSWERS = "Rich Answers / Visual Web Results";
    private static final String SETTINGS_SEARCH = "Settings search";

    private static String markdown() {
        return ComponentUninstallTest.readRepositoryFile("ROADMAP.md");
    }

    private static String inApp() {
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

    /** The one assertion this whole file exists for. */
    @Test public void everyActiveMilestoneAppearsInBothRoadmaps() {
        String file = markdown();
        String page = inApp();
        for (String milestone : OrbitRoadmap.MILESTONES) {
            assertTrue(milestone + " is an active milestone and must be in ROADMAP.md",
                    file.contains(milestone));
            assertTrue(milestone + " is an active milestone and must be in the in-app Roadmap",
                    page.contains(milestone));
        }
    }

    /** And in the plan at the top, not buried somewhere in the history below it. */
    @Test public void theMilestonesAreInThePlanRatherThanTheHistory() {
        String file = markdown();
        int history = file.indexOf(HISTORY_HEADING);
        assertTrue("ROADMAP.md must separate its plan from its history", history > 0);
        for (String milestone : OrbitRoadmap.MILESTONES) {
            assertTrue(milestone + " must appear above the development history",
                    file.indexOf(milestone) < history);
        }
    }

    /**
     * The plan reads in the order it happens, in both documents.
     *
     * <p>At Stable promotion the order question changes shape. There is one active milestone left,
     * so what has to hold is that the finished line is recorded as finished above it and has left
     * the future-only in-app page entirely.
     */
    @Test public void theShippedLineIsRecordedAboveTheActivePlan() {
        String file = markdown();
        int shipped = file.indexOf(RICH_ANSWERS);
        int current = file.indexOf(OrbitRoadmap.CURRENT);
        assertTrue("Rich Answers must still be recorded", shipped >= 0);
        assertTrue("Smart Vault is the active line", current > shipped);
        assertTrue("Rich Answers is recorded as Stable, not as current work",
                file.indexOf("### `0.7.8.5` Stable") >= 0);
        assertTrue("and the Stable entry comes before the active plan",
                file.indexOf("### `0.7.8.5` Stable") < current);

        String page = inApp();
        assertTrue("the in-app page leads with the active line",
                page.indexOf(OrbitRoadmap.CURRENT) >= 0);
        assertFalse("and a shipped line is never offered there", page.contains(RICH_ANSWERS));
    }

    /**
     * Settings search belongs to the 0.7.8.5 release, and now genuinely exists.
     *
     * <p>The inverse of what this asserted before Beta 1. While it was planned work the failure
     * worth preventing was the roadmap implying a field the app did not have; now that the field
     * exists, the failure worth preventing is the opposite one - a roadmap that still reads as a
     * promise about something already shipped. Both directions are the same rule: the two roadmaps
     * and the app have to agree about what is true today.
     *
     * <p>The claims themselves are still pinned, because they are the ones somebody would water
     * down first: local and provider-free, and landing on the control rather than its section.
     */
    @Test public void settingsSearchShippedWithTheRichAnswersRelease() {
        String file = markdown();
        // Anchored on the heading rather than on the first mention: Beta 1's shipped list names
        // Settings search before the section that describes it, and reading the section means
        // starting where the section starts.
        int at = file.indexOf("In the same release: " + SETTINGS_SEARCH);
        assertTrue(SETTINGS_SEARCH + " must have its own section in ROADMAP.md", at >= 0);
        String section = file.substring(at, Math.min(file.length(), at + 1600));
        assertTrue("it belongs to the 0.7.8.5 release", file.substring(0, at).contains("0.7.8.5"));
        assertTrue("and finding a setting must not need a provider", section.contains("No AI"));
        assertTrue("choosing a result goes to the control itself",
                section.contains("straight to that control"));
        assertTrue("Rich Answers is still the primary work of that release",
                file.contains("remains the primary work"));

        String settings = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/SettingsActivity.java");
        assertTrue("the Settings screen must actually offer the search field",
                settings.contains("Search settings"));
        assertTrue("and it must be driven by the structured index rather than by scraped views",
                settings.contains("SettingsSearchIndex.search"));
        assertFalse("searching for a setting must never reach a provider",
                settings.contains("AssistantClient.send"));
    }

    /**
     * The two commitments that make Rich Answers reuse Orbit rather than duplicate it.
     *
     * <p>Written down where they can be checked, because they are the part that will be quietly
     * dropped first: it is much easier to build a second image viewer and a second saving path than
     * to reach the ones that already exist.
     */
    @Test public void richAnswersIsRecordedAsReusingOrbitsOwnImageViewerAndVault() {
        String file = markdown();
        int at = file.indexOf(RICH_ANSWERS);
        assertTrue(at >= 0);
        // Bounded by the next release heading rather than by a character count. The section grows
        // as each Beta records what it shipped, and a fixed window quietly stops covering the
        // commitments it was written to protect.
        int end = file.indexOf("### `0.7.8.4` Stable", at);
        String section = file.substring(at, end > at ? end : file.length());
        assertTrue("inline images must open in the image viewer Orbit already has",
                section.contains("image viewer"));
        assertTrue("and reach the Vault through Save to Vault",
                section.contains("Save to Vault"));
        assertTrue("with sourcing that is structured rather than trusted Markdown",
                section.contains("Markdown"));
        assertTrue("there must not be a second image ecosystem",
                section.contains("second image ecosystem"));
    }

    /** Smart Vault stays opt-in, and the roadmap has to keep saying so. */
    @Test public void smartVaultIsRecordedAsOptIn() {
        String file = markdown();
        int at = file.indexOf("### `0.7.8.6` - " + OrbitRoadmap.CURRENT);
        assertTrue(at >= 0);
        String section = file.substring(at, Math.min(file.length(), at + 1400));
        assertTrue(section.contains("opt-in"));
        assertTrue("and must never silently upload a Vault", section.contains("silently"));
    }

    /**
     * Neither roadmap may present this release's own work as something still to come.
     *
     * <p>The Vault itself, saving from Orbit's surfaces, and Beta 4's filters and pinning are all
     * shipped by the time this runs. What is future is Rich Answers and Smart Vault.
     */
    @Test public void shippedVaultWorkIsNotOfferedAsFutureInTheApp() {
        String page = inApp();
        for (String shipped : new String[]{"Quick Capture", "Attach from Vault",
                "Save page to Vault", "Vault and Quick Capture as Orbit Deck destinations"}) {
            assertTrue(shipped + " has shipped and must not be listed as upcoming",
                    !page.contains(shipped));
        }
    }
}
