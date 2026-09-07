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
 * history. Rename or drop a milestone in one place and the build says so.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class RoadmapSyncTest {

    /** Where the plan stops and the record of how Orbit got here begins. */
    private static final String HISTORY_HEADING = "# Development history";

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

    /** The plan reads in the order it happens, in both documents. */
    @Test public void theOrderIsTheSameInBothRoadmaps() {
        for (String text : new String[]{markdown(), inApp()}) {
            int current = text.indexOf(OrbitRoadmap.CURRENT);
            int next = text.indexOf(OrbitRoadmap.NEXT);
            int alongside = text.indexOf(OrbitRoadmap.ALONGSIDE);
            int after = text.indexOf(OrbitRoadmap.AFTER);
            assertTrue("Vault organization comes first", current >= 0);
            assertTrue("Rich Answers follows the Vault", next > current);
            assertTrue("Settings search is listed under Rich Answers, not above it",
                    alongside > next);
            assertTrue("and Smart Vault still follows both", after > alongside);
        }
    }

    /**
     * Settings search is planned work for 0.7.8.5, and only planned work.
     *
     * <p>It is on both roadmaps because the Settings page has genuinely grown past the point of
     * being browsable, and it is asserted to be <em>under</em> Rich Answers because it is the
     * smaller half of that release. The failure this prevents is the entry being read as shipped:
     * nothing in Orbit builds a Settings search field yet, so a changelog line or an in-app page
     * that implied one would be a promise the app cannot keep.
     */
    @Test public void settingsSearchIsPlannedForTheRichAnswersReleaseAndNotYetBuilt() {
        String file = markdown();
        int at = file.indexOf(OrbitRoadmap.ALONGSIDE);
        assertTrue(OrbitRoadmap.ALONGSIDE + " must be in ROADMAP.md", at >= 0);
        String section = file.substring(at, Math.min(file.length(), at + 1200));
        assertTrue("it belongs to the 0.7.8.5 release", file.substring(0, at).contains("0.7.8.5"));
        assertTrue("and finding a setting must not need a provider", section.contains("No AI"));
        assertTrue("choosing a result goes to the control itself",
                section.contains("straight to that control"));
        assertTrue("Rich Answers is still the primary work of that release",
                section.contains("remains the primary work"));

        assertFalse("Settings search has not shipped, so the changelog must not claim it",
                ComponentUninstallTest.readRepositoryFile("CHANGELOG.md")
                        .contains("- **v" + BuildConfig.VERSION_NAME + "**: " + "Settings search"));
        assertFalse("and no Settings screen may already offer it",
                ComponentUninstallTest.readRepositoryFile(
                        "app/src/main/java/com/orbit/assistant/SettingsActivity.java")
                        .contains("Search settings"));
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
        int at = file.indexOf(OrbitRoadmap.NEXT);
        assertTrue(at >= 0);
        String section = file.substring(at, Math.min(file.length(), at + 2600));
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
        int at = file.indexOf(OrbitRoadmap.AFTER);
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
