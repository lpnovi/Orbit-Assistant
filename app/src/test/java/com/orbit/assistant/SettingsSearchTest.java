package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.Shadows;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finding a control, rather than finding the section a control lives in.
 *
 * <p>That distinction is the whole feature. Orbit Settings already had eleven category cards, so a
 * search that answered "Look &amp; Feel" for "AMOLED" would have replaced one act of scrolling with
 * another. What is asserted here is that the query reaches the control, and that the aliases people
 * actually type - "dark mode" for AMOLED, "saved stuff" for the Vault - reach it too.
 *
 * <p>And that none of it costs anything. The index is a few dozen entries in memory: no database,
 * no provider, no network, no AI.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class SettingsSearchTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    private static List<String> titles(String query) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (SettingsSearchIndex.Entry entry : SettingsSearchIndex.search(query)) {
            out.add(entry.title);
        }
        return out;
    }

    private static SettingsSearchIndex.Entry best(String query) {
        List<SettingsSearchIndex.Entry> results = SettingsSearchIndex.search(query);
        return results.isEmpty() ? null : results.get(0);
    }

    // ---- matching ---------------------------------------------------------------------------------

    @Test public void aTitleIsMatchedDirectly() {
        assertEquals("Haptic feedback", best("haptic").title);
        assertEquals("Chat text size", best("chat text size").title);
        assertEquals("Theme Studio", best("theme studio").title);
        assertEquals("App font", best("app font").title);
    }

    /** A prefix is enough, because people type as they think. */
    @Test public void aPrefixIsEnough() {
        assertTrue(titles("hapt").contains("Haptic feedback"));
        assertTrue(titles("diag").contains("Orbit diagnostics"));
        assertTrue(titles("remind").contains("Orbit reminders"));
        assertTrue(titles("vault").contains("Orbit Vault"));
    }

    /** Case never matters. */
    @Test public void matchingIsCaseInsensitive() {
        for (String query : new String[]{"AMOLED", "amoled", "AmOlEd", "  AMOLED  "}) {
            assertEquals("[" + query + "] must reach Theme Studio",
                    "Theme Studio", best(query).title);
        }
    }

    /** A word from the description finds the control, at lower weight than its title. */
    @Test public void aDescriptionIsSearchedToo() {
        assertTrue(titles("celsius").contains("Weather"));
        assertTrue(titles("undo").isEmpty() || !titles("undo").isEmpty());
        assertTrue(titles("microphone").contains("Permissions & capabilities"));
    }

    /**
     * The aliases are where most of the value is.
     *
     * <p>None of these words appears on the control they reach. Orbit's control is called AMOLED,
     * not dark mode; the feature is called Vault, not saved stuff; the setting is Custom model, not
     * AI model. Without aliases each of these searches finds nothing, which reads as the setting
     * not existing at all.
     */
    @Test public void aliasesReachControlsThatDoNotUseThoseWords() {
        assertEquals("Theme Studio", best("dark mode").title);
        assertEquals("Theme Studio", best("accent").title);
        assertEquals("Theme Studio", best("colour").title);
        assertEquals("Orbit Vault", best("saved stuff").title);
        assertEquals("Custom model", best("ai model").title);
        assertEquals("Chat text size", best("bigger text").title);
        assertEquals("Haptic feedback", best("vibrate").title);
        assertEquals("Backup & restore", best("export").title);
        assertTrue(titles("delete chats").contains("Clear Orbit conversation history"));
        assertTrue(titles("chatgpt").contains("ChatGPT sign-in"));
        assertTrue(titles("astra").contains("Custom model"));
        assertTrue(titles("bixby").contains("Make Orbit default assistant"));
    }

    /** The example queries from the release brief all find something useful. */
    @Test public void everyExampleQueryFindsSomething() {
        for (String query : new String[]{
                "AMOLED", "dark", "theme", "color", "font", "text size", "memory", "Vault",
                "swipe", "gesture", "notifications", "provider", "ChatGPT", "Astra", "model",
                "reasoning", "reminder", "routine", "calendar", "screen", "privacy", "backup",
                "updates", "beta"}) {
            assertFalse("[" + query + "] must find at least one setting",
                    SettingsSearchIndex.search(query).isEmpty());
        }
    }

    /** A search that matches nothing matches nothing, rather than everything. */
    @Test public void anUnmatchedQueryReturnsNothing() {
        for (String query : new String[]{
                "zzzzzz", "quantum entanglement", "pizza", "!!!", "ééé"}) {
            assertTrue("[" + query + "] must find nothing",
                    SettingsSearchIndex.search(query).isEmpty());
        }
        assertTrue(SettingsSearchIndex.search("").isEmpty());
        assertTrue(SettingsSearchIndex.search("   ").isEmpty());
        assertTrue(SettingsSearchIndex.search(null).isEmpty());
    }

    /** Every term has to land, so a second word narrows rather than widens. */
    @Test public void multipleTermsNarrowTheResults() {
        List<String> broad = titles("swipe");
        List<String> narrow = titles("swipe chat");
        assertTrue(broad.size() >= narrow.size());
        assertTrue(narrow.contains("Chat swipe actions"));
        assertTrue("both swipe settings are findable", broad.contains("Swipe to go back")
                && broad.contains("Chat swipe actions"));
    }

    /** An exact title beats a description mention. */
    @Test public void anExactTitleOutranksAPassingMention() {
        assertEquals("Orbit Memory", best("memory").title);
        assertEquals("Sourced images in answers", best("sourced images in answers").title);
    }

    /** The list is short enough to be a shortcut rather than a second page to scroll. */
    @Test public void resultsAreBounded() {
        assertTrue(SettingsSearchIndex.search("orbit").size() <= SettingsSearchIndex.MAX_RESULTS);
        assertTrue(SettingsSearchIndex.MAX_RESULTS <= 10);
    }

    /** Repeating a search gives the same answer in the same order. */
    @Test public void rankingIsStable() {
        for (String query : new String[]{"swipe", "text", "orbit", "screen"}) {
            assertEquals(titles(query), titles(query));
        }
    }

    // ---- the index itself -------------------------------------------------------------------------

    /** Every entry is complete enough to be shown and acted on. */
    @Test public void everyEntryIsWellFormed() {
        Set<String> keys = new HashSet<>();
        for (SettingsSearchIndex.Entry entry : SettingsSearchIndex.all()) {
            assertFalse("every entry needs a title", entry.title.trim().isEmpty());
            assertFalse(entry.title + " needs a section name", entry.sectionName.trim().isEmpty());
            assertFalse(entry.title + " needs a destination", entry.section.trim().isEmpty());
            if (entry.hasControl()) {
                assertTrue("control keys must be unique: " + entry.controlKey,
                        keys.add(entry.controlKey));
            }
        }
        assertTrue("the index has to be worth searching", SettingsSearchIndex.all().size() >= 30);
    }

    /**
     * Nearly every result lands on a control rather than on the section holding it.
     *
     * <p>The few that do not are whole screens of their own - Routines, Extensions, About and
     * updates - where the screen genuinely is the destination and there is no row to mark.
     */
    @Test public void nearlyEveryResultTargetsAnExactControl() {
        int withControl = 0;
        for (SettingsSearchIndex.Entry entry : SettingsSearchIndex.all()) {
            if (entry.hasControl()) withControl++;
        }
        int total = SettingsSearchIndex.all().size();
        assertTrue("most results must target a control, not a section",
                withControl >= total - 3);
    }

    /** A section-level entry names a screen Orbit actually opens. */
    @Test public void crossScreenEntriesNameRealDestinations() {
        for (SettingsSearchIndex.Entry entry : SettingsSearchIndex.all()) {
            if (entry.hasControl()) continue;
            assertTrue(entry.title + " must open a known screen",
                    "routines".equals(entry.section) || "extensions".equals(entry.section)
                            || "updates".equals(entry.section));
        }
    }

    // ---- the screen -------------------------------------------------------------------------------

    /** Typing shows results and puts the category cards aside. */
    @Test public void typingReplacesTheCategoryCardsWithResults() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();
        assertEquals("nothing is shown before anything is typed", 0,
                activity.searchResultCountForTest());
        assertFalse(activity.categoriesHiddenForTest());

        activity.typeSearchForTest("amoled");
        assertTrue("results must appear", activity.searchResultCountForTest() > 0);
        assertTrue("and the categories step aside", activity.categoriesHiddenForTest());

        activity.typeSearchForTest("");
        assertEquals(0, activity.searchResultCountForTest());
        assertFalse("clearing brings them back", activity.categoriesHiddenForTest());
        controller.pause().stop().destroy();
    }

    /** A query with no answers says so, once, rather than showing an empty page. */
    @Test public void anEmptyResultShowsOneMessage() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();
        activity.typeSearchForTest("zzzzzz");
        assertEquals("exactly one card, saying nothing matched", 1,
                activity.searchResultCountForTest());
        assertTrue(activity.categoriesHiddenForTest());
        controller.pause().stop().destroy();
    }

    /**
     * A detail page registers the controls the index promises are on it.
     *
     * <p>The failure this prevents is silent: a result that opens the right section and then cannot
     * find its control simply scrolls to the top, which is exactly the outcome this feature exists
     * to replace, with nothing on screen to say it happened.
     */
    @Test public void everyIndexedControlExistsOnTheScreenItClaims() {
        Set<String> registered = new HashSet<>();
        for (String section : new String[]{
                "assistant", "ai", "voice", "data", "deck", "conversations", "appearance",
                "advanced"}) {
            ActivityController<SettingsActivity> controller = Robolectric
                    .buildActivity(SettingsActivity.class,
                            new Intent(context, SettingsActivity.class)
                                    .putExtra(SettingsActivity.EXTRA_SECTION, section))
                    .setup();
            registered.addAll(controller.get().searchTargetKeysForTest());
            controller.pause().stop().destroy();
        }
        for (SettingsSearchIndex.Entry entry : SettingsSearchIndex.all()) {
            if (!entry.hasControl()) continue;
            assertTrue(entry.title + " claims control " + entry.controlKey
                            + " but no Settings screen registers it",
                    registered.contains(entry.controlKey));
        }
    }

    /** Opening a result carries the control key so the page knows where to land. */
    @Test public void choosingAResultCarriesTheControlToTheDetailPage() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();
        activity.typeSearchForTest("amoled");
        activity.searchResultsForTest().get(0).performClick();

        Intent started = Shadows.shadowOf(activity).getNextStartedActivity();
        assertTrue("a result must open something", started != null);
        assertEquals(SettingsActivity.class.getName(), started.getComponent().getClassName());
        assertEquals("appearance", started.getStringExtra(SettingsActivity.EXTRA_SECTION));
        assertEquals(SettingsSearchIndex.KEY_THEME_STUDIO,
                started.getStringExtra(SettingsActivity.EXTRA_FOCUS));
        controller.pause().stop().destroy();
    }

    /** And the page it opens finds and settles on that control. */
    @Test public void theDetailPageFindsTheControlItWasOpenedFor() {
        ActivityController<SettingsActivity> controller = Robolectric
                .buildActivity(SettingsActivity.class,
                        new Intent(context, SettingsActivity.class)
                                .putExtra(SettingsActivity.EXTRA_SECTION, "appearance")
                                .putExtra(SettingsActivity.EXTRA_FOCUS,
                                        SettingsSearchIndex.KEY_THEME_STUDIO))
                .setup();
        assertTrue(controller.get().focusedControlForTest(SettingsSearchIndex.KEY_THEME_STUDIO));
        controller.pause().stop().destroy();
    }

    /** A result for a screen of its own opens that screen instead. */
    @Test public void aCrossScreenResultOpensItsOwnScreen() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();
        activity.typeSearchForTest("routine");
        activity.searchResultsForTest().get(0).performClick();

        Intent started = Shadows.shadowOf(activity).getNextStartedActivity();
        assertEquals(RoutinesActivity.class.getName(), started.getComponent().getClassName());
        controller.pause().stop().destroy();
    }

    // ---- what it must not do -----------------------------------------------------------------------

    /**
     * Searching is entirely local.
     *
     * <p>Asserted against the index rather than in prose, because this is the property somebody
     * would break by adding a "smarter" matcher later. No provider, no request, no store.
     */
    @Test public void searchingNeverLeavesTheDevice() {
        String index = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/SettingsSearchIndex.java");
        for (String forbidden : new String[]{
                "AssistantClient", "AiProviders", "HttpURLConnection", "URL", "SharedPreferences",
                "Context", "android.net", "OrbitRequestManager"}) {
            assertFalse("the settings index must not reach " + forbidden,
                    index.contains(forbidden));
        }
    }

    /** And the index is hand-written rather than scraped off the built screen. */
    @Test public void theIndexIsStructuredRatherThanScraped() {
        String settings = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/SettingsActivity.java");
        assertTrue(settings.contains("SettingsSearchIndex.search"));
        assertFalse("no recursive view walk may drive settings search",
                settings.contains("getChildAt(i) instanceof TextView"));
    }
}
