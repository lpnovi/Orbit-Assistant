package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPackageManager;

import java.util.ArrayList;
import java.util.List;

/**
 * What a Deck tile actually does when it is tapped.
 *
 * <p>Two things carry the weight here. The first is that a Prompt tile <em>prepares</em> a request
 * and never sends one: the whole feature would be a liability if a tile could spend the user's AI
 * usage from a grid. The second is that Deck delegates rather than reimplements — a Routine tile
 * must reach the same runner, with the same confirmation behaviour, as every other way of running a
 * Routine, because a second execution path is a second thing that can disagree about safety.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class DeckActionTest {

    private Context context;
    private ActivityController<DeckActivity> controller;
    private DeckActivity deck;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        DeckLayoutStore.clearForTest(context);
        ConversationStore.clear(context);
        OrbitRequestManager.resetForTest();
        OrbitRequestManager.setWorkCanceller(name -> {});
        TestWorkManager.ensureInitialized(context);
        controller = Robolectric.buildActivity(DeckActivity.class).setup();
        deck = controller.get();
    }

    @After public void tearDown() {
        if (controller != null) controller.pause().stop().destroy();
    }

    private Intent nextIntent() {
        return shadowOf(deck).getNextStartedActivity();
    }

    private void run(DeckTile tile) {
        DeckActionExecutor.execute(deck, tile, outcome -> {});
    }

    // ---- prompt tiles: prepared, never sent --------------------------------------------------------

    @Test public void aPromptTileOpensAChatWithTheTextWaiting() {
        DeckTile tile = DeckTile.of(DeckTileRegistry.TYPE_PROMPT, DeckTile.Size.STANDARD)
                .withConfig(DeckTile.CONFIG_PROMPT, "Explain this clearly and concisely:")
                .withConfig(DeckTile.CONFIG_TITLE, "Explain");
        run(tile);

        Intent started = nextIntent();
        assertNotNull("a prompt tile must open a conversation", started);
        assertEquals(ChatActivity.class.getName(), started.getComponent().getClassName());
        assertEquals("Explain this clearly and concisely:",
                started.getStringExtra(ChatActivity.EXTRA_INITIAL_DRAFT));
        assertTrue("and put the cursor in the composer",
                started.getBooleanExtra(ChatActivity.EXTRA_FOCUS_COMPOSER, false));
    }

    /**
     * The tile hands the text to the composer and stops.
     *
     * <p>Asserted from the request layer rather than from the intent: whatever else happens, no
     * request may have been created and no message may exist in the conversation. The user presses
     * send.
     */
    @Test public void aPromptTileNeverSendsAnything() {
        DeckTile tile = DeckTile.of(DeckTileRegistry.TYPE_PROMPT, DeckTile.Size.STANDARD)
                .withConfig(DeckTile.CONFIG_PROMPT, "Plan my day");
        run(tile);

        Intent started = nextIntent();
        String conversationId = started.getStringExtra(ChatActivity.EXTRA_CONVERSATION_ID);
        assertNotNull(conversationId);
        ConversationStore.Conversation opened = ConversationStore.load(context, conversationId);
        assertTrue("no message may exist yet",
                opened == null || opened.messages.isEmpty());
        assertTrue("and no request may have been started",
                PendingRequestStore.active(context).isEmpty());
    }

    /** Nothing is sent merely by drawing the Deck, either. */
    @Test public void openingDeckSendsNothing() {
        assertTrue(PendingRequestStore.active(context).isEmpty());
        assertNull("opening Deck must navigate nowhere on its own", nextIntent());
        for (ConversationStore.Conversation chat : ConversationStore.list(context)) {
            assertTrue(chat.messages.isEmpty());
        }
    }

    @Test public void aPromptTileWithNoTextAsksToBeSetUpRatherThanRunning() {
        DeckTile tile = DeckTile.of(DeckTileRegistry.TYPE_PROMPT, DeckTile.Size.STANDARD);
        final boolean[] needsConfiguration = {false};
        DeckActionExecutor.execute(deck, tile, outcome -> needsConfiguration[0] = outcome.needsConfiguration);

        assertTrue(needsConfiguration[0]);
        assertNull("and it must not open anything", nextIntent());
    }

    /** A prompt is the user's own text and never reaches Diagnostics. */
    @Test public void promptTextIsNeverRecordedInDiagnostics() {
        DeckTile tile = DeckTile.of(DeckTileRegistry.TYPE_PROMPT, DeckTile.Size.STANDARD)
                .withConfig(DeckTile.CONFIG_PROMPT, "a distinctive private prompt string");
        DeckLayoutStore.add(context, tile);
        run(tile);

        assertEquals("only the tile type is recorded",
                DeckTileRegistry.TYPE_PROMPT, DiagnosticStore.lastDeckAction(context));
        String everything = DiagnosticStore.prefs(context).getAll().toString();
        assertFalse("the prompt itself must not be in the diagnostics store",
                everything.contains("a distinctive private prompt string"));
    }

    // ---- new chat ----------------------------------------------------------------------------------

    @Test public void theNewChatTileOpensAFreshConversation() {
        run(DeckTile.of(DeckTileRegistry.TYPE_NEW_CHAT, DeckTile.Size.WIDE));

        Intent started = nextIntent();
        assertEquals(ChatActivity.class.getName(), started.getComponent().getClassName());
        assertFalse(started.getStringExtra(ChatActivity.EXTRA_CONVERSATION_ID).isEmpty());
        assertNull("and carries no draft", started.getStringExtra(ChatActivity.EXTRA_INITIAL_DRAFT));
    }

    // ---- destinations ------------------------------------------------------------------------------

    @Test public void destinationTilesOpenTheirRealScreens() {
        String[][] expected = {
                {DeckTileRegistry.TYPE_ROUTINES, RoutinesActivity.class.getName()},
                {DeckTileRegistry.TYPE_REMINDERS, RemindersActivity.class.getName()},
                {DeckTileRegistry.TYPE_MEMORIES, MemoryActivity.class.getName()},
                {DeckTileRegistry.TYPE_CAPABILITIES, CapabilitiesActivity.class.getName()},
                {DeckTileRegistry.TYPE_EXTENSIONS, ExtensionsActivity.class.getName()},
                {DeckTileRegistry.TYPE_SETTINGS, SettingsActivity.class.getName()},
        };
        for (String[] pair : expected) {
            run(DeckTile.of(pair[0], DeckTile.Size.STANDARD));
            Intent started = nextIntent();
            assertNotNull(pair[0] + " must open something", started);
            assertEquals(pair[0], pair[1], started.getComponent().getClassName());
        }
    }

    // ---- routine tiles -----------------------------------------------------------------------------

    @Test public void aRoutineTileResolvesItsSavedRoutine() {
        RoutineStore.Routine routine = RoutineStore.create("Goodnight", oneAction());
        RoutineStore.upsert(context, routine);

        DeckTile tile = DeckTile.of(DeckTileRegistry.TYPE_ROUTINE, DeckTile.Size.WIDE)
                .withConfig(DeckTile.CONFIG_ROUTINE_ID, routine.id);
        DeckTileResolver.Resolved resolved = DeckTileResolver.resolve(context, tile);

        assertEquals(DeckTile.Availability.AVAILABLE, resolved.availability);
        assertEquals("the Routine's own name is the tile's name", "Goodnight", resolved.title);
        assertTrue(resolved.subtitle.contains("Routine"));
    }

    @Test public void aDeletedRoutineBecomesUnresolvedRatherThanDisappearing() {
        RoutineStore.Routine routine = RoutineStore.create("Goodnight", oneAction());
        RoutineStore.upsert(context, routine);
        DeckTile tile = DeckTile.of(DeckTileRegistry.TYPE_ROUTINE, DeckTile.Size.STANDARD)
                .withConfig(DeckTile.CONFIG_ROUTINE_ID, routine.id);
        DeckLayoutStore.add(context, tile);

        RoutineStore.delete(context, routine.id);

        assertEquals("the tile stays on the Deck", 1, countType(DeckTileRegistry.TYPE_ROUTINE));
        DeckTileResolver.Resolved resolved = DeckTileResolver.resolve(context, tile);
        assertEquals(DeckTile.Availability.UNRESOLVED, resolved.availability);
        assertTrue(resolved.subtitle.toLowerCase().contains("deleted"));
    }

    @Test public void anUnresolvedRoutineTileOffersRepairInsteadOfRunning() {
        DeckTile tile = DeckTile.of(DeckTileRegistry.TYPE_ROUTINE, DeckTile.Size.STANDARD)
                .withConfig(DeckTile.CONFIG_ROUTINE_ID, "no-such-routine");
        final boolean[] needsConfiguration = {false};
        DeckActionExecutor.execute(deck, tile, outcome -> needsConfiguration[0] = outcome.needsConfiguration);

        assertTrue(needsConfiguration[0]);
        assertNull("nothing may be executed", nextIntent());
    }

    /**
     * A Routine step that requires confirmation still reaches the Routine runner.
     *
     * <p>This is the safety assertion. The shared runner hands a confirmation-carrying Routine to
     * {@code RoutinesActivity} rather than executing it headlessly, and a Deck tile must land in
     * exactly that behaviour rather than quietly running the step because it came from a grid.
     */
    @Test public void aRoutineNeedingConfirmationIsHandedToTheRoutineRunner() throws Exception {
        List<AssistantReply.Action> actions = new ArrayList<>();
        actions.add(new AssistantReply.Action("SET_DND", new JSONObject().put("enabled", true), true));
        RoutineStore.Routine routine = RoutineStore.create("Bedtime", actions);
        RoutineStore.upsert(context, routine);

        run(DeckTile.of(DeckTileRegistry.TYPE_ROUTINE, DeckTile.Size.STANDARD)
                .withConfig(DeckTile.CONFIG_ROUTINE_ID, routine.id));

        Intent started = nextIntent();
        assertNotNull("confirmation must not be skipped", started);
        assertEquals(RoutinesActivity.class.getName(), started.getComponent().getClassName());
        assertEquals(routine.id,
                started.getStringExtra(RoutinesActivity.EXTRA_AUTORUN_ROUTINE_ID));
    }

    /**
     * Deck owns no execution engine of its own.
     *
     * <p>Asserted against the source, because the point is structural: the moment Deck calls the
     * Action Engine directly it has a second execution path that can drift away from confirmations,
     * permission hand-off and run history. Everything must go through the shared runner.
     */
    @Test public void deckHasNoActionEngineOfItsOwn() {
        String executor = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/DeckActionExecutor.java");

        assertTrue("Routines must go through the shared runner",
                executor.contains("OrbitWidgetExecutor.runRoutine"));
        assertTrue("and the flashlight through the shared torch path",
                executor.contains("OrbitWidgetExecutor.toggleFlashlight"));
        assertFalse("Deck must never drive the Action Engine itself",
                executor.contains("OrbitActionEngine."));
        assertFalse("nor execute a device action directly",
                executor.contains("DeviceActionExecutor.execute"));

        String view = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/DeckTileView.java");
        assertFalse("the tile view must hold no business logic",
                view.contains("OrbitWidgetExecutor") || view.contains("RoutineStore")
                        || view.contains("startActivity"));
    }

    /** Suggested is computed locally, so nothing in it may reach a provider. */
    @Test public void suggestionsNeverReachAProvider() {
        String engine = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/DeckSuggestionEngine.java");
        for (String forbidden : new String[]{
                "AssistantClient", "AiProviders", "ChatGptClient", "OrbitRequestManager",
                "HttpURLConnection", "URL("}) {
            assertFalse("Suggested must not use " + forbidden, engine.contains(forbidden));
        }
    }

    // ---- app tiles ---------------------------------------------------------------------------------

    @Test public void anAppTileLaunchesThroughTheLaunchersOwnIntent() {
        installLaunchableApp("com.example.player", "Player");

        DeckTile tile = DeckTile.of(DeckTileRegistry.TYPE_APP, DeckTile.Size.STANDARD)
                .withConfig(DeckTile.CONFIG_PACKAGE, "com.example.player");
        DeckTileResolver.Resolved resolved = DeckTileResolver.resolve(context, tile);
        assertEquals(DeckTile.Availability.AVAILABLE, resolved.availability);

        run(tile);
        Intent started = nextIntent();
        assertNotNull(started);
        assertEquals("com.example.player", started.getPackage() == null
                ? started.getComponent().getPackageName() : started.getPackage());
    }

    /**
     * A stored package name can never become a hand-assembled intent.
     *
     * <p>Deck asks the PackageManager for the app's own launch intent and launches that or nothing.
     * A package that is not launchable therefore produces a repair prompt rather than an intent
     * built out of a string, so a malformed or hostile stored value has nothing to act on.
     */
    @Test public void aNonLaunchablePackageCannotProduceAnIntent() {
        for (String hostile : new String[]{
                "", "   ", "not a package", "com.example.missing",
                "com.example.app/../../evil", "intent:#Intent;action=x;end"}) {
            DeckTile tile = DeckTile.of(DeckTileRegistry.TYPE_APP, DeckTile.Size.STANDARD)
                    .withConfig(DeckTile.CONFIG_PACKAGE, hostile);
            assertNull(hostile + " must not resolve to an intent",
                    DeckTileResolver.launchIntent(context, hostile));

            final boolean[] needsConfiguration = {false};
            DeckActionExecutor.execute(deck, tile,
                    outcome -> needsConfiguration[0] = outcome.needsConfiguration);
            assertTrue(hostile + " must ask to be set up", needsConfiguration[0]);
            assertNull(hostile + " must launch nothing", nextIntent());
        }
    }

    @Test public void anUninstalledAppBecomesUnresolvedAndRecoversOnReinstall() {
        installLaunchableApp("com.example.player", "Player");
        DeckTile tile = DeckTile.of(DeckTileRegistry.TYPE_APP, DeckTile.Size.STANDARD)
                .withConfig(DeckTile.CONFIG_PACKAGE, "com.example.player");
        DeckLayoutStore.add(context, tile);
        assertEquals(DeckTile.Availability.AVAILABLE,
                DeckTileResolver.resolve(context, tile).availability);

        shadowOf(context.getPackageManager()).removePackage("com.example.player");
        assertEquals(DeckTile.Availability.UNRESOLVED,
                DeckTileResolver.resolve(context, tile).availability);
        assertEquals("the tile is kept, not deleted", 1, countType(DeckTileRegistry.TYPE_APP));

        // The package name is the stored identity, so reinstalling is all it takes.
        installLaunchableApp("com.example.player", "Player");
        assertEquals(DeckTile.Availability.AVAILABLE,
                DeckTileResolver.resolve(context, tile).availability);
    }

    // ---- helpers -----------------------------------------------------------------------------------

    /** A minimal valid Routine step, since RoutineStore refuses to save an actionless Routine. */
    private static List<AssistantReply.Action> oneAction() {
        List<AssistantReply.Action> actions = new ArrayList<>();
        try {
            actions.add(new AssistantReply.Action(RoutineActionCatalog.FLASHLIGHT,
                    new JSONObject().put("on", true), false));
        } catch (Exception ignored) {}
        return actions;
    }

    private int countType(String type) {
        int count = 0;
        for (DeckTile tile : DeckLayoutStore.layout(context)) {
            if (type.equals(tile.type)) count++;
        }
        return count;
    }

    /** Installs a package with a launcher entry, the way a real installed app appears. */
    private void installLaunchableApp(String packageName, String label) {
        ShadowPackageManager shadow = shadowOf(context.getPackageManager());
        PackageInfo info = new PackageInfo();
        info.packageName = packageName;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = packageName;
        info.applicationInfo.name = label;
        info.applicationInfo.nonLocalizedLabel = label;
        shadow.installPackage(info);

        Intent launch = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(packageName);
        ActivityInfo activity = new ActivityInfo();
        activity.packageName = packageName;
        activity.name = packageName + ".Main";
        activity.applicationInfo = info.applicationInfo;
        shadow.addActivityIfNotPresent(new android.content.ComponentName(packageName, activity.name));
        shadow.addIntentFilterForActivity(
                new android.content.ComponentName(packageName, activity.name),
                new android.content.IntentFilter(Intent.ACTION_MAIN) {{
                    addCategory(Intent.CATEGORY_LAUNCHER);
                }});
    }
}
