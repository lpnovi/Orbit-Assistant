package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONObject;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Suggested, which is allowed to say nothing and usually should.
 *
 * <p>The failure this guards against is not a missing suggestion, it is a confident wrong one: a
 * shortcut to an app that is gone, an offer built from context two days old, or — the specific trap
 * of putting this in the full app — Orbit classifying <em>itself</em> as the foreground app, because
 * opening Deck is exactly what makes Orbit foreground.
 *
 * <p>A fixed clock is passed in rather than read, so freshness is asserted rather than waited for.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class DeckSuggestionEngineTest {

    private static final long NOW = 1_800_000_000_000L;

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        DeckLayoutStore.clearForTest(context);
        DiagnosticStore.prefs(context).edit().clear().commit();
    }

    private List<DeckSuggestionEngine.Suggestion> suggest(DeckTileResolver.LiveState live,
                                                          List<DeckTile> deck) {
        return DeckSuggestionEngine.suggestions(context, live, deck,
                DeckSuggestionEngine.MAX_PHONE, NOW);
    }

    private static DeckTileResolver.LiveState playing(String app) {
        return new DeckTileResolver.LiveState(null, true, app);
    }

    private static DeckTileResolver.LiveState nothing() {
        return DeckTileResolver.LiveState.unknown();
    }

    /** Records a foreground app the way the overlay's screen capture does. */
    private void recordForeground(String packageName, long at) {
        DiagnosticStore.prefs(context).edit()
                .putString("foreground_package", packageName)
                .putLong("screen_updated", at)
                .commit();
    }

    /** A minimal valid Routine step, since RoutineStore refuses to save an actionless Routine. */
    private static List<AssistantReply.Action> oneAction() {
        List<AssistantReply.Action> actions = new ArrayList<>();
        try {
            actions.add(new AssistantReply.Action(RoutineActionCatalog.FLASHLIGHT,
                    new JSONObject().put("on", true), false));
        } catch (Exception ignored) {}
        return actions;
    }

    private static List<String> idsOf(List<DeckSuggestionEngine.Suggestion> suggestions) {
        List<String> out = new ArrayList<>();
        for (DeckSuggestionEngine.Suggestion suggestion : suggestions) out.add(suggestion.id);
        return out;
    }

    // ---- nothing to say ---------------------------------------------------------------------------

    @Test public void withNoContextThereAreNoSuggestions() {
        assertTrue(suggest(nothing(), Collections.emptyList()).isEmpty());
    }

    @Test public void staleContextProducesNoSuggestions() {
        recordForeground("com.example.reader",
                NOW - DeckSuggestionEngine.CONTEXT_FRESHNESS_MS - 1);
        assertTrue(suggest(nothing(), Collections.emptyList()).isEmpty());
    }

    /**
     * Orbit is the foreground app whenever Deck is open, so its own package must be ignored.
     *
     * <p>Without this the most reliably "fresh" context Deck could ever read would be itself, and
     * every Deck would suggest opening Orbit.
     */
    @Test public void orbitItselfNeverBecomesASuggestion() {
        recordForeground(context.getPackageName(), NOW - 1000L);
        List<DeckSuggestionEngine.Suggestion> suggestions = suggest(nothing(), Collections.emptyList());
        assertTrue("Orbit must not suggest opening Orbit", suggestions.isEmpty());
    }

    /** A fresh record for an app that is not installed cannot become a shortcut to nothing. */
    @Test public void anUninstallableRecentAppIsNotSuggested() {
        recordForeground("com.example.definitely.not.installed", NOW - 1000L);
        assertTrue(suggest(nothing(), Collections.emptyList()).isEmpty());
    }

    @Test public void aPausedMediaSessionIsNotSuggested() {
        DeckTileResolver.LiveState paused = new DeckTileResolver.LiveState(null, false, "Music");
        assertTrue("only something actually playing is worth offering",
                suggest(paused, Collections.emptyList()).isEmpty());
    }

    @Test public void turningSmartSuggestionsOffProducesNone() {
        Prefs.get(context).edit().putBoolean(Prefs.DECK_SUGGESTIONS, false).commit();
        assertTrue(suggest(playing("Music"), Collections.emptyList()).isEmpty());
    }

    // ---- what it does offer -----------------------------------------------------------------------

    @Test public void musicPlayingOffersPause() {
        List<DeckSuggestionEngine.Suggestion> suggestions =
                suggest(playing("Music"), Collections.emptyList());
        assertEquals(1, suggestions.size());
        assertEquals("media", suggestions.get(0).id);
        assertEquals(DeckTileRegistry.TYPE_MEDIA, suggestions.get(0).action.type);
        assertTrue("and it says where", suggestions.get(0).subtitle.contains("Music"));
    }

    @Test public void aRecentlyRunRoutineIsOffered() {
        RoutineStore.Routine routine = RoutineStore.create("Goodnight", oneAction());
        RoutineStore.upsert(context, routine);
        RoutineStore.markRun(context, routine.id);

        List<DeckSuggestionEngine.Suggestion> suggestions =
                DeckSuggestionEngine.suggestions(context, nothing(), Collections.emptyList(),
                        DeckSuggestionEngine.MAX_PHONE, System.currentTimeMillis());
        assertTrue(idsOf(suggestions).contains("recent_routine"));
        for (DeckSuggestionEngine.Suggestion suggestion : suggestions) {
            if (!"recent_routine".equals(suggestion.id)) continue;
            assertEquals("Goodnight", suggestion.title);
            assertEquals(DeckTileRegistry.TYPE_ROUTINE, suggestion.action.type);
            assertEquals(routine.id, suggestion.action.config(DeckTile.CONFIG_ROUTINE_ID));
        }
    }

    @Test public void aRoutineRunLongAgoIsNotOffered() {
        RoutineStore.Routine routine = RoutineStore.create("Old", oneAction());
        RoutineStore.upsert(context, routine);
        RoutineStore.markRun(context, routine.id);

        List<DeckSuggestionEngine.Suggestion> suggestions =
                DeckSuggestionEngine.suggestions(context, nothing(), Collections.emptyList(),
                        DeckSuggestionEngine.MAX_PHONE,
                        System.currentTimeMillis() + DeckSuggestionEngine.ROUTINE_RECENCY_MS + 1000L);
        assertFalse(idsOf(suggestions).contains("recent_routine"));
    }

    // ---- it never competes with My Deck -----------------------------------------------------------

    /** Suggested is a hint about what is missing, not a second copy of what is already there. */
    @Test public void aSuggestionIsSuppressedWhenItsTileIsAlreadyOnTheDeck() {
        List<DeckTile> deck = new ArrayList<>();
        deck.add(DeckTile.of(DeckTileRegistry.TYPE_MEDIA, DeckTile.Size.STANDARD));
        assertTrue("the Media tile is already there, so do not suggest it",
                suggest(playing("Music"), deck).isEmpty());
    }

    @Test public void aRoutineAlreadyOnTheDeckIsNotSuggested() {
        RoutineStore.Routine routine = RoutineStore.create("Goodnight", oneAction());
        RoutineStore.upsert(context, routine);
        RoutineStore.markRun(context, routine.id);

        List<DeckTile> deck = new ArrayList<>();
        deck.add(DeckTile.of(DeckTileRegistry.TYPE_ROUTINE, DeckTile.Size.STANDARD)
                .withConfig(DeckTile.CONFIG_ROUTINE_ID, routine.id));

        List<DeckSuggestionEngine.Suggestion> suggestions =
                DeckSuggestionEngine.suggestions(context, nothing(), deck,
                        DeckSuggestionEngine.MAX_PHONE, System.currentTimeMillis());
        assertFalse(idsOf(suggestions).contains("recent_routine"));
    }

    /**
     * Computing suggestions must not touch the user's layout.
     *
     * <p>The permanent Deck is the user's, so this is asserted directly rather than left to the
     * absence of a call: whatever Suggested decides, the stored layout is byte-identical afterwards.
     */
    @Test public void buildingSuggestionsNeverModifiesTheDeck() {
        RoutineStore.Routine routine = RoutineStore.create("Goodnight", oneAction());
        RoutineStore.upsert(context, routine);
        RoutineStore.markRun(context, routine.id);
        DeckLayoutStore.reset(context);

        String before = DiagnosticStore.prefs(context) == null ? "" : storedLayout();
        DeckSuggestionEngine.suggestions(context, playing("Music"),
                DeckLayoutStore.layout(context), DeckSuggestionEngine.MAX_PHONE,
                System.currentTimeMillis());
        assertEquals("Suggested must never rearrange or add to My Deck", before, storedLayout());
    }

    private String storedLayout() {
        return context.getSharedPreferences("orbit_deck", Context.MODE_PRIVATE)
                .getString("deck_layout", "");
    }

    // ---- caps -------------------------------------------------------------------------------------

    @Test public void aPhoneShowsAtMostTwo() {
        RoutineStore.Routine routine = RoutineStore.create("Goodnight", oneAction());
        RoutineStore.upsert(context, routine);
        RoutineStore.markRun(context, routine.id);
        recordForeground(context.getPackageName(), System.currentTimeMillis());

        List<DeckSuggestionEngine.Suggestion> suggestions =
                DeckSuggestionEngine.suggestions(context, playing("Music"),
                        Collections.emptyList(), DeckSuggestionEngine.MAX_PHONE,
                        System.currentTimeMillis());
        assertTrue(suggestions.size() <= DeckSuggestionEngine.MAX_PHONE);
    }

    @Test public void theCapFollowsTheColumnCount() {
        assertEquals(DeckSuggestionEngine.MAX_PHONE, DeckSuggestionEngine.maxFor(2));
        assertEquals(DeckSuggestionEngine.MAX_WIDE, DeckSuggestionEngine.maxFor(3));
        assertEquals(DeckSuggestionEngine.MAX_WIDE, DeckSuggestionEngine.maxFor(4));
    }

    @Test public void aZeroCapProducesNothing() {
        assertTrue(DeckSuggestionEngine.suggestions(context, playing("Music"),
                Collections.emptyList(), 0, NOW).isEmpty());
    }

    // ---- the freshness window ---------------------------------------------------------------------

    /** Conservative by construction: a quarter of an hour, not a day. */
    @Test public void theFreshnessWindowIsConservative() {
        assertEquals(15L * 60L * 1000L, DeckSuggestionEngine.CONTEXT_FRESHNESS_MS);
        assertTrue("and the routine window is a day at most",
                DeckSuggestionEngine.ROUTINE_RECENCY_MS <= 24L * 60L * 60L * 1000L);
    }
}
