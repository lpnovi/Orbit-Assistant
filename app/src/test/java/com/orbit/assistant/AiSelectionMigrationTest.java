package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * The one-time move from intelligence modes to explicit selections, and per-chat selections.
 *
 * <p>Every legacy mode is mapped deterministically, a valid Custom choice is preserved, corrupt
 * values fall back safely, the migration runs once, and nothing it does can later overwrite a
 * choice the user makes in the new controls.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class AiSelectionMigrationTest {
    private Context context;

    private static final AiSelection LUNA_MEDIUM =
            AiSelection.of(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.LUNA, AiStrength.MEDIUM);
    private static final AiSelection LUNA_LOW =
            AiSelection.of(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.LUNA, AiStrength.LOW);
    private static final AiSelection SOL_HIGH =
            AiSelection.of(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.SOL, AiStrength.HIGH);
    private static final AiSelection ASTRA_MAX =
            AiSelection.of(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.ASTRA, AiStrength.MAX);

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        ConversationStore.clear(context);
    }

    private void legacy(String mode, String model, String reasoning) {
        SharedPreferences.Editor e = Prefs.get(context).edit();
        if (mode != null) e.putString(Prefs.INTELLIGENCE_MODE, mode);
        if (model != null) e.putString(Prefs.MODEL, model);
        if (reasoning != null) e.putString(Prefs.REASONING, reasoning);
        e.commit();
    }

    // ---- mapping -------------------------------------------------------------------------------

    @Test public void everyLegacyModeMapsDeterministically() {
        assertEquals(LUNA_MEDIUM, AiSelections.fromLegacy("chatgpt", "auto", "", ""));
        assertEquals(LUNA_LOW, AiSelections.fromLegacy("chatgpt", "fast", "", ""));
        assertEquals(LUNA_MEDIUM, AiSelections.fromLegacy("chatgpt", "balanced", "", ""));
        assertEquals(SOL_HIGH, AiSelections.fromLegacy("chatgpt", "deep", "", ""));
        assertEquals("the same input always gives the same answer",
                AiSelections.fromLegacy("chatgpt", "deep", "", ""),
                AiSelections.fromLegacy("chatgpt", "deep", "", ""));
    }

    @Test public void aValidCustomChoiceIsPreserved() {
        assertEquals(ASTRA_MAX, AiSelections.fromLegacy("chatgpt", "custom", "gpt-6-astra", "max"));
        assertEquals("a restored current id stays exact and keeps its effort",
                AiSelection.of(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.GPT_5_6_SOL,
                        AiStrength.XHIGH),
                AiSelections.fromLegacy("chatgpt", "custom", "gpt-5.6-sol", "xhigh"));
        assertEquals(AiSelection.of(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.GPT_5_6_TERRA,
                        AiStrength.NONE),
                AiSelections.fromLegacy("chatgpt", "custom", "gpt-5.6-terra", "none"));
    }

    @Test public void customNoneOnAModelWithoutNoneBecomesLow() {
        assertEquals(AiSelection.of(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.ASTRA, AiStrength.LOW),
                AiSelections.fromLegacy("chatgpt", "custom", "gpt-6-astra", "none"));
    }

    @Test public void corruptOrUnknownValuesFallBackSafely() {
        assertEquals(LUNA_MEDIUM, AiSelections.fromLegacy("chatgpt", "turbo", "", ""));
        assertEquals(LUNA_MEDIUM, AiSelections.fromLegacy("chatgpt", "", "", ""));
        assertEquals(LUNA_MEDIUM, AiSelections.fromLegacy("chatgpt", null, null, null));
        assertEquals(LUNA_MEDIUM, AiSelections.fromLegacy("not-a-provider", "auto", "", ""));
        AiSelection junkCustom = AiSelections.fromLegacy("chatgpt", "custom", "gpt-9", "ludicrous");
        assertEquals(OrbitModelCatalog.LUNA, junkCustom.model);
        assertEquals(AiStrength.LOW, junkCustom.strength);
        assertTrue(AiSelections.isValid(junkCustom));
    }

    @Test public void orbitLocalStaysLocalWithNoStrength() {
        AiSelection local = AiSelections.fromLegacy("local", "deep", "", "");
        assertEquals(Prefs.PROVIDER_LOCAL, local.provider);
        assertEquals(OrbitModelCatalog.ORBIT_LOCAL, local.model);
        assertNull(local.strength);
    }

    @Test public void relayKeepsItsProviderAndNeverGetsAstra() {
        AiSelection relay = AiSelections.fromLegacy("relay", "custom", "gpt-6-astra", "high");
        assertEquals(Prefs.PROVIDER_RELAY, relay.provider);
        assertEquals("Astra is not offered on the relay", OrbitModelCatalog.LUNA, relay.model);
    }

    // ---- once, and never over a new choice -----------------------------------------------------

    @Test public void aFreshInstallGetsTheBroadlyAvailableDefault() {
        assertEquals(LUNA_MEDIUM, AiSelections.globalDefault(context));
    }

    @Test public void anUpgradeMigratesTheStoredModeOnce() {
        legacy("deep", null, null);
        assertEquals(SOL_HIGH, AiSelections.globalDefault(context));
        assertEquals(OrbitModelCatalog.SOL, Prefs.get(context).getString(Prefs.AI_DEFAULT_MODEL, ""));
        // A later legacy write (say, a downgrade and upgrade) does not re-run the migration.
        legacy("fast", null, null);
        assertEquals(SOL_HIGH, AiSelections.globalDefault(context));
    }

    @Test public void aNewExplicitChoiceIsNeverOverwritten() {
        legacy("custom", "gpt-6-astra", "max");
        assertEquals(ASTRA_MAX, AiSelections.globalDefault(context));
        AiSelections.setGlobalDefault(context, LUNA_LOW);
        AiSelections.ensureMigrated(context);
        AiSelections.ensureMigrated(context);
        assertEquals(LUNA_LOW, AiSelections.globalDefault(context));
    }

    @Test public void legacyKeysAreLeftInPlaceForDowngrades() {
        legacy("deep", "gpt-5.6-sol", "high");
        AiSelections.globalDefault(context);
        SharedPreferences p = Prefs.get(context);
        assertEquals("deep", p.getString(Prefs.INTELLIGENCE_MODE, ""));
        assertEquals("gpt-5.6-sol", p.getString(Prefs.MODEL, ""));
        assertEquals("high", p.getString(Prefs.REASONING, ""));
    }

    // ---- chats ---------------------------------------------------------------------------------

    /** Writes a chat the way 0.8.2.0 did: an intelligenceMode field and no aiSelection. */
    private void legacyChat(String id, String mode) throws Exception {
        SharedPreferences store = context.getSharedPreferences("orbit_conversations", Context.MODE_PRIVATE);
        JSONArray all = new JSONArray(store.getString("items_v1", "[]"));
        JSONObject chat = new JSONObject().put("id", id).put("title", "Old " + id)
                .put("updatedAt", 1L).put("intelligenceMode", mode)
                .put("messages", new JSONArray().put(new JSONObject()
                        .put("role", "user").put("content", "hello " + id)));
        all.put(chat);
        store.edit().putString("items_v1", all.toString()).commit();
    }

    @Test public void existingChatsKeepTheirModesMeaningAndStayReadable() throws Exception {
        legacyChat("a", "deep");
        legacyChat("b", "");
        legacyChat("c", "fast");
        legacy("custom", "gpt-6-astra", "max");
        ConversationStore.Conversation before = ConversationStore.load(context, "a");
        assertNotNull("a 0.8.2.0 chat must read back before migration", before);
        assertEquals("deep", before.intelligenceMode);

        assertEquals(SOL_HIGH, AiSelections.forConversation(context, "a"));
        assertEquals("a chat that followed the default is pinned to the migrated default",
                ASTRA_MAX, AiSelections.forConversation(context, "b"));
        assertEquals(LUNA_LOW, AiSelections.forConversation(context, "c"));
        assertEquals("its legacy field is written back unchanged",
                "deep", ConversationStore.load(context, "a").intelligenceMode);
        assertEquals("hello a", ConversationStore.load(context, "a").messages.get(0).content);

        // Changing the default afterwards changes no existing chat.
        AiSelections.setGlobalDefault(context, LUNA_LOW);
        assertEquals(ASTRA_MAX, AiSelections.forConversation(context, "b"));
    }

    @Test public void chatMigrationRunsOnceAndRespectsNewChoices() throws Exception {
        legacyChat("a", "deep");
        AiSelections.ensureMigrated(context);
        AiSelections.setForConversation(context, "a", LUNA_LOW);
        AiSelections.ensureMigrated(context);
        assertEquals(LUNA_LOW, AiSelections.forConversation(context, "a"));
    }

    @Test public void restoringAnOldBackupMigratesWhatItRestored() throws Exception {
        AiSelections.setGlobalDefault(context, LUNA_LOW);
        JSONObject oldBackup = new JSONObject().put(Prefs.INTELLIGENCE_MODE, "deep");
        assertTrue(Prefs.restoreBackupSnapshot(context, oldBackup));
        assertEquals("an old backup's mode means what it meant", SOL_HIGH,
                AiSelections.globalDefault(context));

        JSONObject newBackup = Prefs.backupSnapshot(context);
        assertEquals(OrbitModelCatalog.SOL, newBackup.getString(Prefs.AI_DEFAULT_MODEL));
        assertEquals("high", newBackup.getString(Prefs.AI_DEFAULT_STRENGTH));
        assertFalse("the provider and credentials never enter a backup",
                newBackup.has(Prefs.PROVIDER));
    }

    @Test public void aRequestQueuedBeforeTheUpdateKeepsItsMeaning() throws Exception {
        SharedPreferences pending = context.getSharedPreferences("orbit_pending_requests", Context.MODE_PRIVATE);
        JSONObject record = new JSONObject().put("id", "r1").put("conversationId", "c")
                .put("prompt", "p").put("status", PendingRequestStore.QUEUED)
                .put("intelligenceMode", "deep");
        pending.edit().putString("items_v1", new JSONArray().put(record).toString()).commit();
        PendingRequestStore.Item item = PendingRequestStore.load(context, "r1");
        assertNotNull(item);
        assertEquals(SOL_HIGH, item.selection);
    }

    // ---- per chat ------------------------------------------------------------------------------

    private void chat(String id) {
        List<AssistantClient.History> history = new ArrayList<>();
        history.add(new AssistantClient.History("user", "question " + id));
        ConversationStore.save(context, id, history);
    }

    @Test public void aNewChatInheritsTheDefaultItStartedUnder() {
        AiSelections.setGlobalDefault(context, SOL_HIGH);
        chat("new");
        assertEquals(SOL_HIGH, ConversationStore.selectionFor(context, "new"));
        AiSelections.setGlobalDefault(context, LUNA_LOW);
        assertEquals("the default changes future chats only", SOL_HIGH,
                AiSelections.forConversation(context, "new"));
        chat("later");
        assertEquals(LUNA_LOW, AiSelections.forConversation(context, "later"));
    }

    @Test public void twoChatsHoldDifferentModelsIndependently() {
        chat("a");
        chat("b");
        AiSelections.setForConversation(context, "a", ASTRA_MAX);
        AiSelections.setForConversation(context, "b", LUNA_LOW);
        assertEquals(ASTRA_MAX, AiSelections.forConversation(context, "a"));
        assertEquals(LUNA_LOW, AiSelections.forConversation(context, "b"));
        AiSelections.setForConversation(context, "a", SOL_HIGH);
        assertEquals("changing A leaves B alone", LUNA_LOW, AiSelections.forConversation(context, "b"));
        assertEquals("and leaves the default alone", LUNA_MEDIUM, AiSelections.globalDefault(context));
    }

    @Test public void aChatsSelectionSurvivesEveryRewriteOfItsRecord() {
        chat("a");
        AiSelections.setForConversation(context, "a", ASTRA_MAX);
        ConversationStore.rename(context, "a", "Renamed");
        ConversationStore.setPinned(context, "a", true);
        List<AssistantClient.History> more = new ArrayList<>(ConversationStore.load(context, "a").messages);
        more.add(new AssistantClient.History("assistant", "answer"));
        ConversationStore.save(context, "a", more);
        ConversationStore.removeLastAssistantTurn(context, "a");
        assertEquals(ASTRA_MAX, ConversationStore.selectionFor(context, "a"));
    }

    @Test public void selectionsAreStoredValidatedAndReadBackAfterReload() {
        chat("a");
        AiSelections.setForConversation(context, "a",
                AiSelection.of(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.SOL, AiStrength.NONE));
        // A fresh read of the store is what a recreated process sees.
        ConversationStore.Conversation reread = ConversationStore.load(context, "a");
        assertEquals(AiSelection.of(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.SOL, AiStrength.LOW),
                reread.aiSelection);
    }

    @Test public void encodingRoundTripsAndRejectsJunk() {
        assertEquals(ASTRA_MAX, AiSelection.decode(ASTRA_MAX.encode()));
        AiSelection local = AiSelections.resolve(AiSelection.of("local", "orbit-local", null));
        assertEquals(local, AiSelection.decode(local.encode()));
        assertNull(AiSelection.decode(""));
        assertNull(AiSelection.decode("garbage"));
        assertNull(AiSelection.decode("2|chatgpt|gpt-6-luna|low"));
        assertNull(AiSelection.decode("1||gpt-6-luna|low"));
    }

    @Test public void switchingModelResolvesTheStrengthVisibly() {
        AiSelection lunaNone = AiSelection.of(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.LUNA, AiStrength.NONE);
        AiSelection sol = AiSelections.withModel(lunaNone, OrbitModelCatalog.SOL);
        assertEquals(AiStrength.LOW, sol.strength);
        AiSelection back = AiSelections.withModel(sol, OrbitModelCatalog.LUNA);
        assertEquals("a legal strength is kept", AiStrength.LOW, back.strength);
        AiSelection local = AiSelections.withProvider(sol, Prefs.PROVIDER_LOCAL);
        assertNull(local.strength);
    }
}
