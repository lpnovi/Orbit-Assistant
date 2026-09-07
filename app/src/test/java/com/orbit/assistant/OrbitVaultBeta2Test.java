package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.work.Configuration;
import androidx.work.ListenableWorker;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerFactory;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orbit Vault Beta 2: notes, the switch, the erase, and the two deliberate ways a saved item can
 * reach a model.
 *
 * <p>Beta 1's promise was that nothing in the Vault ever talks to a provider. Beta 2 opens exactly
 * two doors in that wall - Ask Orbit and Attach from Vault - and the value of this file is proving
 * that both of them are doors the user has to walk through. Staging an item must arm a composer and
 * nothing else: no request, no upload, no conversation written, no provider chosen. If that ever
 * stops being true, the feature has quietly become the automatic Vault the whole design refuses.
 *
 * <p>The other half is the switch. "Turning it off does not delete anything" is the sort of claim
 * that is easy to write in release notes and easy to break in code, so it is asserted directly:
 * every item survives, every save route refuses, and turning it back on returns all of it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class OrbitVaultBeta2Test {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        OrbitVaultStore.prefs(context).edit().clear().commit();
        Prefs.get(context).edit().clear().commit();
        ConversationStore.clear(context);
        MemoryStore.clear(context);
        File media = OrbitVaultMedia.directory(context);
        File[] files = media.listFiles();
        if (files != null) for (File file : files) file.delete();
        initWorkManager();
    }

    private static String source(String simpleName) {
        return ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/" + simpleName + ".java");
    }

    private Bitmap picture() {
        Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.BLUE);
        return bitmap;
    }

    // ---- notes -----------------------------------------------------------------------------------

    /**
     * A note is written, read back, and changes nothing else about the item.
     *
     * <p>The second half is the part worth asserting. A note that quietly rewrote the body, the
     * title or the type would have turned the saved thing into something the user did not save.
     */
    @Test public void anoteIsAddedEditedAndRemovedWithoutTouchingWhatWasSaved() {
        OrbitVaultItem saved = OrbitVaultStore.saveLink(context, "Clip",
                "https://example.com/clip", "Clipboard");
        assertNotNull(saved);
        assertFalse("a freshly saved item has no note of its own", saved.hasNote());

        assertTrue(OrbitVaultStore.updateNote(context, saved.id,
                "Look at this later for the animation idea."));
        OrbitVaultItem withNote = OrbitVaultStore.get(context, saved.id);
        assertEquals("Look at this later for the animation idea.", withNote.note);
        assertEquals("the saved address is untouched", saved.body, withNote.body);
        assertEquals("and so is the title", saved.title, withNote.title);
        assertEquals("and the type", saved.type, withNote.type);
        assertEquals("and the identity", saved.id, withNote.id);
        assertEquals("and when it was saved", saved.createdAt, withNote.createdAt);

        assertTrue(OrbitVaultStore.updateNote(context, saved.id, "Different reason entirely."));
        assertEquals("Different reason entirely.", OrbitVaultStore.get(context, saved.id).note);

        assertTrue("clearing a note is a save, not a failure",
                OrbitVaultStore.updateNote(context, saved.id, "   "));
        OrbitVaultItem cleared = OrbitVaultStore.get(context, saved.id);
        assertFalse(cleared.hasNote());
        assertEquals("", cleared.note);
        assertEquals("removing the note must never remove the item",
                1, OrbitVaultStore.count(context));
        assertEquals(saved.body, cleared.body);
    }

    /** A note survives being written to disk and read back by a different call. */
    @Test public void anotePersists() {
        OrbitVaultItem saved = OrbitVaultStore.saveText(context, "Recipe", "Sourdough", "test",
                "For Sunday");
        assertEquals("For Sunday", saved.note);
        assertEquals("For Sunday", OrbitVaultStore.list(context).get(0).note);
    }

    /** A note the user wrote is searchable, which is usually the only reason they can find it. */
    @Test public void anoteIsSearchable() {
        OrbitVaultItem saved = OrbitVaultStore.saveLink(context, "",
                "https://example.com/aWK2mPfx", "Clipboard");
        assertTrue(OrbitVaultStore.updateNote(context, saved.id, "animation inspiration"));

        List<OrbitVaultItem> found = OrbitVaultStore.search(context, "animation",
                OrbitVaultStore.Sort.NEWEST);
        assertEquals("the note is the only place that word appears", 1, found.size());
        assertEquals(saved.id, found.get(0).id);
        assertTrue("and it is case-insensitive like every other field", OrbitVaultStore
                .search(context, "ANIMATION", OrbitVaultStore.Sort.NEWEST).size() == 1);
        assertEquals("something that matches nothing still matches nothing",
                0, OrbitVaultStore.search(context, "sourdough",
                        OrbitVaultStore.Sort.NEWEST).size());
    }

    @Test public void anoteIsBounded() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < OrbitVaultItem.MAX_NOTE_CHARS + 500; i++) huge.append('n');
        OrbitVaultItem saved = OrbitVaultStore.saveText(context, "t", "body", "test",
                huge.toString());
        assertEquals(OrbitVaultItem.MAX_NOTE_CHARS, saved.note.length());
    }

    /** Notes are their own field on disk, never smuggled into a field that already means something. */
    @Test public void anoteIsItsOwnStoredField() throws Exception {
        OrbitVaultStore.saveText(context, "Title", "Body", "test", "My reason");
        JSONArray stored = new JSONArray(
                OrbitVaultStore.prefs(context).getString("items_v1", "[]"));
        assertEquals("My reason", stored.getJSONObject(0).getString("note"));
        assertEquals("Body", stored.getJSONObject(0).getString("body"));
        assertEquals("Title", stored.getJSONObject(0).getString("title"));
    }

    // ---- Beta 1 compatibility ---------------------------------------------------------------------

    /**
     * Everything Beta 1 wrote still loads, unchanged, and simply has no note.
     *
     * <p>Asserted against a document in exactly Beta 1's shape - no {@code note} key at all - which
     * is what is actually on every existing tester's phone. A migration step here would be a bug:
     * a missing note already means the user never wrote one.
     */
    @Test public void abeta1StoreLoadsWithEmptyNotes() {
        String beta1 = "[{\"id\":\"b1-text\",\"type\":\"text\",\"title\":\"Packing list\","
                + "\"body\":\"Charger\",\"source\":\"Quick Capture\",\"mediaPath\":\"\","
                + "\"createdAt\":1000,\"modifiedAt\":1000},"
                + "{\"id\":\"b1-link\",\"type\":\"link\",\"title\":\"\","
                + "\"body\":\"https://example.com/a\",\"source\":\"Shared to Orbit\","
                + "\"mediaPath\":\"\",\"createdAt\":2000,\"modifiedAt\":2000}]";
        assertTrue(OrbitVaultStore.prefs(context).edit().putString("items_v1", beta1).commit());

        List<OrbitVaultItem> items = OrbitVaultStore.list(context, OrbitVaultStore.Sort.OLDEST);
        assertEquals(2, items.size());
        assertEquals("b1-text", items.get(0).id);
        assertEquals("Packing list", items.get(0).title);
        assertEquals("Charger", items.get(0).body);
        assertEquals("Quick Capture", items.get(0).source);
        assertEquals(1000L, items.get(0).createdAt);
        assertEquals("a missing note is an empty note", "", items.get(0).note);
        assertFalse(items.get(0).hasNote());
        assertEquals(OrbitVaultItem.TYPE_LINK, items.get(1).type);
        assertEquals("https://example.com/a", items.get(1).body);
        assertEquals("", items.get(1).note);

        // And a note can be added to one afterwards without disturbing the other.
        assertTrue(OrbitVaultStore.updateNote(context, "b1-text", "Written in Beta 2"));
        assertEquals("Written in Beta 2", OrbitVaultStore.get(context, "b1-text").note);
        assertEquals("", OrbitVaultStore.get(context, "b1-link").note);
        assertEquals("Charger", OrbitVaultStore.get(context, "b1-text").body);
    }

    /** A Beta 1 image item keeps its picture, and the picture still resolves. */
    @Test public void abeta1ImageItemRemainsValid() {
        OrbitVaultItem image = OrbitVaultStore.saveImage(context, picture(), "Old photo", "Photo");
        assertNotNull(image);
        // Rewritten exactly as Beta 1 would have stored it: the same row, with no note key.
        OrbitVaultStore.prefs(context).edit().putString("items_v1",
                "[{\"id\":\"" + image.id + "\",\"type\":\"image\",\"title\":\"Old photo\","
                        + "\"body\":\"\",\"source\":\"Photo\",\"mediaPath\":\""
                        + image.mediaPath.replace("\\", "\\\\") + "\",\"createdAt\":1,"
                        + "\"modifiedAt\":1}]").commit();

        OrbitVaultItem loaded = OrbitVaultStore.get(context, image.id);
        assertNotNull(loaded);
        assertTrue(loaded.isImage());
        assertEquals("", loaded.note);
        assertTrue("its picture is still where it was", new File(loaded.mediaPath).isFile());
        assertNotNull(OrbitVaultMedia.load(loaded.mediaPath));
    }

    // ---- the Use Orbit Vault preference -----------------------------------------------------------

    @Test public void theVaultIsOnByDefault() {
        assertTrue("an existing user who never set this must keep their Vault",
                Prefs.vaultEnabled(context));
        assertTrue(OrbitVaultStore.enabled(context));
    }

    /**
     * Off stops every save route, and destroys nothing.
     *
     * <p>The refusal is asserted at the store rather than at a screen, because that is where it
     * has to hold: hiding a control is drawing, and a route somebody adds later would walk straight
     * past a check that only lived in a layout.
     */
    @Test public void turningTheVaultOffBlocksNewSavesAndDeletesNothing() {
        OrbitVaultStore.saveText(context, "Kept", "Something I saved", "Quick Capture", "my note");
        OrbitVaultItem image = OrbitVaultStore.saveImage(context, picture(), "Photo", "Photo");
        assertEquals(2, OrbitVaultStore.count(context));

        Prefs.get(context).edit().putBoolean(Prefs.VAULT_ENABLED, false).commit();
        assertFalse(OrbitVaultStore.enabled(context));

        assertNull("no text may be written while the Vault is off",
                OrbitVaultStore.saveText(context, "New", "New thing", "Quick Capture"));
        assertNull("no link either",
                OrbitVaultStore.saveLink(context, "", "https://example.com/b", "Clipboard"));
        assertNull("no picture either",
                OrbitVaultStore.saveImage(context, picture(), "", "Photo"));
        assertNull("and no saved answer either",
                OrbitVaultStore.saveOrbitReply(context, "Orbit said something."));

        assertEquals("nothing already saved is touched", 2, OrbitVaultStore.count(context));
        assertTrue("and no picture file is removed", new File(image.mediaPath).isFile());
    }

    /** Turning it back on returns every item, with its note, and needs no reimport. */
    @Test public void turningTheVaultBackOnRestoresEverything() {
        OrbitVaultItem saved = OrbitVaultStore.saveText(context, "Kept", "Body", "Quick Capture",
                "why I kept it");
        Prefs.get(context).edit().putBoolean(Prefs.VAULT_ENABLED, false).commit();
        Prefs.get(context).edit().putBoolean(Prefs.VAULT_ENABLED, true).commit();

        assertTrue(OrbitVaultStore.enabled(context));
        OrbitVaultItem back = OrbitVaultStore.get(context, saved.id);
        assertNotNull(back);
        assertEquals("Body", back.body);
        assertEquals("why I kept it", back.note);
        assertNotNull("and saving works again",
                OrbitVaultStore.saveText(context, "New", "New thing", "Quick Capture"));
    }

    /** Save to Vault leaves the message menu while the Vault is off, rather than being greyed out. */
    @Test public void saveToVaultLeavesTheMessageMenuWhileTheVaultIsOff() {
        assertTrue(Arrays.asList(MessageActions.assistantLabels(true, true))
                .contains(MessageActions.SAVE_TO_VAULT_MENU_LABEL));
        assertEquals(3, MessageActions.assistantIcons(true, true).length);

        String[] off = MessageActions.assistantLabels(true, false);
        assertFalse(Arrays.asList(off).contains(MessageActions.SAVE_TO_VAULT_MENU_LABEL));
        assertTrue("Copy is untouched", Arrays.asList(off).contains(MessageActions.COPY_MENU_LABEL));
        assertTrue("and so is Regenerate",
                Arrays.asList(off).contains(MessageActions.REGENERATE_MENU_LABEL));
        assertEquals("labels and icons must stay the same length",
                off.length, MessageActions.assistantIcons(true, false).length);
        assertEquals(1, MessageActions.assistantLabels(false, false).length);
        assertEquals(1, MessageActions.assistantIcons(false, false).length);
        assertTrue("the menu reads the preference rather than assuming it",
                source("MessageActions").contains("Prefs.vaultEnabled(bubble.getContext())"));
    }

    /** The composer's Vault entry is absent while the Vault is off. */
    @Test public void attachFromVaultIsUnavailableWhileTheVaultIsOff() {
        assertTrue(Arrays.asList(ChatActivity.attachmentMenuLabels(true)).contains("Vault"));
        assertFalse(Arrays.asList(ChatActivity.attachmentMenuLabels(false)).contains("Vault"));
        assertEquals("every other way to attach is untouched",
                5, ChatActivity.attachmentMenuLabels(false).length);
        assertTrue(Arrays.asList(ChatActivity.attachmentMenuLabels(false)).contains("Gallery"));
    }

    /** The Chats header control disappears with the feature, and Share to Orbit does not. */
    @Test public void everyVaultEntryPointReadsThePreference() {
        assertTrue("the Chats header control is conditional",
                source("MainActivity").contains("if (Prefs.vaultEnabled(this))"));
        assertTrue("and Chats rebuilds when the preference changes",
                source("MainActivity").contains("\"|vault=\" + Prefs.vaultEnabled(this)"));
        assertTrue("the share destination choice is conditional",
                source("ShareToOrbitActivity")
                        .contains("Prefs.vaultEnabled(this) && vaultCanHold("));
        assertTrue("and Ask Orbit through a share still works either way",
                source("ShareToOrbitActivity").contains("openConversation(chat)"));
        assertFalse("the share receiver itself is never disabled",
                source("ShareToOrbitActivity").contains("setComponentEnabledSetting"));
    }

    // ---- Delete Vault data -------------------------------------------------------------------------

    /**
     * Erasing the Vault reaches exactly the Vault.
     *
     * <p>A control called "Delete Vault data" sitting a few rows from Memory and Backup is exactly
     * the one somebody presses while wondering how far it goes, so how far it goes is asserted:
     * items and their pictures, and nothing else that Orbit stores.
     */
    @Test public void deletingAllVaultDataClearsItemsAndMediaAndNothingElse() throws Exception {
        OrbitVaultStore.saveText(context, "Note", "Something", "Quick Capture", "a note");
        OrbitVaultItem image = OrbitVaultStore.saveImage(context, picture(), "Photo", "Photo");
        assertNotNull(image);
        MemoryStore.add(context, MemoryStore.inferCategory("I prefer window seats"),
                "The user prefers window seats");
        List<AssistantClient.History> chat = new ArrayList<>();
        chat.add(new AssistantClient.History("user", "Hello"));
        ConversationStore.save(context, "vault-delete-test", chat);

        File outsider = new File(context.getFilesDir(), "orbit_attachments/keep.jpg");
        assertTrue(outsider.getParentFile().mkdirs() || outsider.getParentFile().isDirectory());
        assertTrue(outsider.createNewFile());

        assertEquals(2, OrbitVaultStore.deleteAllData(context));

        assertEquals("every saved item is gone", 0, OrbitVaultStore.count(context));
        assertTrue("and so is every Vault picture", !new File(image.mediaPath).exists());
        assertFalse("Orbit Memory is untouched", MemoryStore.list(context).isEmpty());
        assertEquals("chats are untouched", 1,
                ConversationStore.load(context, "vault-delete-test").messages.size());
        assertTrue("and a file the Vault does not own is untouched", outsider.isFile());
        outsider.delete();

        assertEquals("erasing an already-empty Vault is a no-op",
                0, OrbitVaultStore.deleteAllData(context));
    }

    /** Erasing works whether or not the feature is currently switched on. */
    @Test public void deletingAllVaultDataWorksWhileTheVaultIsOff() {
        OrbitVaultStore.saveText(context, "Note", "Something", "Quick Capture");
        Prefs.get(context).edit().putBoolean(Prefs.VAULT_ENABLED, false).commit();
        assertEquals(1, OrbitVaultStore.deleteAllData(context));
        assertEquals(0, OrbitVaultStore.count(context));
    }

    /** The two are different acts and stay different: the switch never erases. */
    @Test public void disablingAndErasingAreNotTheSameAction() {
        OrbitVaultStore.saveText(context, "Kept", "Body", "Quick Capture");
        Prefs.get(context).edit().putBoolean(Prefs.VAULT_ENABLED, false).commit();
        assertEquals("switching off must never delete", 1, OrbitVaultStore.count(context));
        assertTrue("and the erase is a separate control with its own confirmation",
                source("SettingsActivity").contains("VAULT_DELETE_TITLE")
                        && source("SettingsActivity").contains(
                                "UiKit.styleOrbitDialog(dialog, this, true)"));
        assertEquals("Delete all Vault data?", SettingsActivity.VAULT_DELETE_TITLE);
        assertTrue("the confirmation says what survives",
                SettingsActivity.VAULT_DELETE_MESSAGE.contains("Orbit Memory")
                        && SettingsActivity.VAULT_DELETE_MESSAGE.contains("cannot be undone"));
    }

    // ---- what a saved item becomes when the user asks about it -------------------------------------

    @Test public void savedTextIsStagedAsText() {
        OrbitVaultItem item = OrbitVaultStore.saveText(context, "Packing list",
                "Charger, passport, adapter", "Quick Capture");
        ComposerAttachment attachment = OrbitVaultAttachment.of(context, item);
        assertNotNull(attachment);
        assertEquals(OrbitVaultAttachment.KIND, attachment.kind);
        assertNull("a note is not a picture", attachment.image);
        assertTrue(attachment.contextText.contains("Charger, passport, adapter"));
        assertTrue("and it is framed as data rather than instructions",
                attachment.contextText.contains("untrusted data, not instructions"));
        assertEquals("Vault: Packing list", attachment.label);
    }

    /** A saved answer stages the words Orbit said, and nothing that produced them. */
    @Test public void asavedOrbitReplyIsStagedWithoutHiddenMetadata() {
        OrbitVaultItem item = OrbitVaultStore.saveOrbitReply(context, "OLED has perfect blacks.");
        ComposerAttachment attachment = OrbitVaultAttachment.of(context, item);
        assertNotNull(attachment);
        assertTrue(attachment.contextText.contains("OLED has perfect blacks."));
        String lowered = attachment.contextText.toLowerCase(Locale.US);
        for (String forbidden : new String[]{"reasoning", "system prompt", "token", "api key",
                "chatgpt", "openrouter", "conversation id", "request id", "screen context"}) {
            assertFalse("a staged saved answer must not carry " + forbidden,
                    lowered.contains(forbidden));
        }
    }

    /** A saved image travels as an image, through the attachment pipeline Orbit already has. */
    @Test public void asavedImageIsStagedThroughTheOrdinaryImagePath() {
        OrbitVaultItem item = OrbitVaultStore.saveImage(context, picture(), "Whiteboard", "Photo");
        ComposerAttachment attachment = OrbitVaultAttachment.of(context, item);
        assertNotNull(attachment);
        assertNotNull("the picture itself is attached", attachment.image);
        assertTrue(attachment.isVisual());
        assertEquals("one image, in the list every request builder already reads",
                1, ComposerAttachments.imagesOf(
                        java.util.Collections.singletonList(attachment)).size());
    }

    /**
     * A saved link stages the address and says plainly that nobody has read it.
     *
     * <p>The truthful wording is load-bearing. A model handed a bare URL will describe the page as
     * though it had seen it, which is precisely the false impression the Vault's link handling
     * exists to avoid.
     */
    @Test public void asavedLinkStagesTheAddressWithoutClaimingToHaveReadIt() {
        OrbitVaultItem item = OrbitVaultStore.saveLink(context, "Animation clip",
                "https://example.com/clip", "Clipboard");
        assertTrue(OrbitVaultStore.updateNote(context, item.id, "For the animation idea"));
        ComposerAttachment attachment = OrbitVaultAttachment.of(context,
                OrbitVaultStore.get(context, item.id));

        assertNotNull(attachment);
        assertTrue(attachment.contextText.contains("https://example.com/clip"));
        assertTrue(attachment.contextText.contains("Animation clip"));
        assertTrue(attachment.contextText.contains("For the animation idea"));
        assertTrue("it must state that the page was not fetched",
                attachment.contextText.contains("has not opened or read this address"));
        assertNull("and no page contents can exist, because none were retrieved",
                attachment.image);
    }

    /** The note is labelled as the user's, never merged into the saved content. */
    @Test public void thenoteIsStagedAsSeparateLabelledContext() {
        OrbitVaultItem item = OrbitVaultStore.saveText(context, "Clip", "Some saved text",
                "Quick Capture", "Look at this later");
        String staged = OrbitVaultAttachment.contextTextFor(item, false);
        assertTrue(staged.contains("Some saved text"));
        assertTrue(staged.contains("The user's own note about why they saved it:"));
        assertTrue(staged.indexOf("Some saved text") < staged.indexOf("Look at this later"));
        assertFalse("an item with no note says nothing about one",
                OrbitVaultAttachment.contextTextFor(
                        OrbitVaultStore.saveText(context, "b", "plain", "test"), false)
                        .contains("own note"));
    }

    /** Only the chosen item goes anywhere near a composer. */
    @Test public void onlyTheSelectedItemIsStaged() {
        OrbitVaultItem chosen = OrbitVaultStore.saveText(context, "Chosen", "the chosen body",
                "test", "chosen note");
        OrbitVaultStore.saveText(context, "Neighbour", "a different body", "test", "other note");
        OrbitVaultStore.saveText(context, "Another", "another body", "test", "another note");

        String staged = OrbitVaultAttachment.of(context, chosen).contextText;
        assertTrue(staged.contains("the chosen body"));
        for (String other : new String[]{"a different body", "another body", "other note",
                "another note", "Neighbour", "Another"}) {
            assertFalse("nothing but the selected item may be staged: " + other,
                    staged.contains(other));
        }
    }

    // ---- Ask Orbit, end to end ---------------------------------------------------------------------

    /**
     * Ask Orbit opens a conversation holding the item, and sends nothing.
     *
     * <p>This is the assertion the whole feature rests on. The composer is armed, the tray has one
     * attachment, and no request exists anywhere: no conversation was written, no pending request
     * was created, and the user still has to type something and press Send.
     */
    @Test public void askOrbitStagesTheItemAndSendsNothing() {
        OrbitVaultItem item = OrbitVaultStore.saveText(context, "Packing list",
                "Charger and adapter", "Quick Capture", "for the trip");
        String conversationId = "vault-ask-test";
        Intent intent = new Intent(context, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, conversationId)
                .putExtra(ChatActivity.EXTRA_VAULT_ITEM_ID, item.id);

        ChatActivity chat = Robolectric.buildActivity(ChatActivity.class, intent).setup().get();

        List<ComposerAttachment> staged = chat.pendingAttachments();
        assertEquals("exactly the one item the user chose", 1, staged.size());
        assertEquals(OrbitVaultAttachment.KIND, staged.get(0).kind);
        assertTrue(staged.get(0).contextText.contains("Charger and adapter"));
        assertTrue("with the user's own note beside it",
                staged.get(0).contextText.contains("for the trip"));

        assertNull("nothing may be written to the conversation",
                ConversationStore.load(context, conversationId));
        assertNull("and no request may exist", PendingRequestStore.load(context, conversationId));
    }

    /** A configuration change cannot attach the same saved item twice. */
    @Test public void askOrbitStagesTheItemOnlyOnce() {
        OrbitVaultItem item = OrbitVaultStore.saveText(context, "Once", "Only once", "test");
        Intent intent = new Intent(context, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, "vault-once-test")
                .putExtra(ChatActivity.EXTRA_VAULT_ITEM_ID, item.id);

        org.robolectric.android.controller.ActivityController<ChatActivity> controller =
                Robolectric.buildActivity(ChatActivity.class, intent).setup();
        controller.pause().resume();
        assertEquals(1, controller.get().pendingAttachments().size());
    }

    /** The item screen builds the Intent and stops: no request machinery is reachable from it. */
    @Test public void theItemScreenStagesRatherThanSends() {
        String screen = source("OrbitVaultItemActivity");
        assertTrue("Ask Orbit hands the id to the ordinary chat screen",
                screen.contains("ChatActivity.EXTRA_VAULT_ITEM_ID"));
        for (String forbidden : new String[]{"AssistantClient", "AiProviders", "AutoRouter",
                "OrbitRequestManager", "PendingRequestStore", "OrbitRequestWorker",
                "HttpURLConnection", "java.net."}) {
            assertFalse("the item screen must not reference " + forbidden,
                    screen.contains(forbidden));
        }
        for (String name : new String[]{"OrbitVaultAttachment", "OrbitVaultPickerActivity"}) {
            String text = source(name);
            for (String forbidden : new String[]{"AssistantClient", "AiProviders", "ChatGptClient",
                    "AutoRouter", "OrbitRequestManager", "PendingRequestStore",
                    "HttpURLConnection", "java.net.", "MemoryStore"}) {
                assertFalse(name + " must not reference " + forbidden, text.contains(forbidden));
            }
        }
    }

    // ---- Attach from Vault --------------------------------------------------------------------------

    @Test public void thepickerListsSavedItemsAndReturnsOne() {
        OrbitVaultStore.saveText(context, "Packing list", "Charger", "Quick Capture", "trip note");
        OrbitVaultStore.saveLink(context, "Recipe", "https://example.com/r", "Clipboard");

        Activity picker = Robolectric.buildActivity(OrbitVaultPickerActivity.class).setup().get();
        String drawn = String.join("\n", textOf(picker.getWindow().getDecorView()));
        assertTrue(drawn.contains("Packing list"));
        assertTrue(drawn.contains("Recipe"));
        assertTrue("a saved item with a note says so", drawn.contains("Has a note"));

        View row = findClickableWithDescription(picker.getWindow().getDecorView(),
                "Link: Recipe, not selected");
        assertNotNull("each saved item must be selectable", row);
        // Marking is not attaching: the picker stays open until Attach is pressed.
        row.performClick();
        assertFalse("marking an item must not close the picker", picker.isFinishing());
        View attach = findClickableWithDescription(picker.getWindow().getDecorView(),
                "Attach 1 saved item");
        assertNotNull("marking one item arms the Attach control", attach);
        attach.performClick();
        assertTrue(picker.isFinishing());
        Intent result = Shadows.shadowOf(picker).getResultIntent();
        assertNotNull(result);
        String[] picked = result.getStringArrayExtra(OrbitVaultPickerActivity.EXTRA_PICKED_IDS);
        assertNotNull(picked);
        assertEquals(1, picked.length);
        assertEquals(OrbitVaultStore.search(context, "Recipe", OrbitVaultStore.Sort.NEWEST)
                        .get(0).id,
                picked[0]);
    }

    @Test public void anemptyPickerExplainsItself() {
        Activity picker = Robolectric.buildActivity(OrbitVaultPickerActivity.class).setup().get();
        List<String> drawn = textOf(picker.getWindow().getDecorView());
        assertTrue(drawn.contains(OrbitVaultPickerActivity.EMPTY_TITLE));
        assertTrue(drawn.contains(OrbitVaultPickerActivity.EMPTY_BODY));
    }

    /**
     * A Vault attachment is an ordinary attachment, subject to the ordinary limit.
     *
     * <p>The Vault gets no allowance of its own. Ten is Orbit's one number for a message, read by
     * Gallery, by Share to Orbit and by both surfaces, and a saved item counts against it exactly
     * as a photo does.
     */
    @Test public void avaultAttachmentObeysTheOrdinaryLimitAndCanBeRemoved() {
        ComposerAttachments composer = new ComposerAttachments();
        for (int i = 0; i < ComposerAttachments.MAX_PER_TURN; i++) {
            composer.add(new ComposerAttachment("gallery", "Photo " + i, "", null));
        }
        assertTrue(composer.isFull());

        OrbitVaultItem item = OrbitVaultStore.saveText(context, "Saved", "Body", "test");
        ComposerAttachments.AddResult refused =
                composer.add(OrbitVaultAttachment.of(context, item));
        assertTrue("the Vault may not exceed the per-message limit", refused.hitLimit());
        assertEquals(ComposerAttachments.MAX_PER_TURN, composer.size());

        ComposerAttachments fresh = new ComposerAttachments();
        ComposerAttachment attachment = OrbitVaultAttachment.of(context, item);
        assertFalse(fresh.add(attachment).hitLimit());
        assertEquals(1, fresh.size());
        assertTrue("and it is removed the way every attachment is",
                fresh.remove(attachment.id));
        assertTrue(fresh.isEmpty());
    }

    /** Choosing a saved item through the composer arms it and sends nothing. */
    @Test public void attachingFromTheVaultSendsNothing() {
        OrbitVaultItem item = OrbitVaultStore.saveText(context, "Saved", "Attach me", "test");
        String conversationId = "vault-attach-test";
        Intent intent = new Intent(context, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, conversationId);
        ChatActivity chat = Robolectric.buildActivity(ChatActivity.class, intent).setup().get();

        chat.addComposerAttachmentForTest(OrbitVaultAttachment.of(context, item));

        assertEquals(1, chat.pendingAttachments().size());
        assertNull("no message may be written before Send",
                ConversationStore.load(context, conversationId));
        assertNull(PendingRequestStore.load(context, conversationId));
    }

    /** There is one attachment pipeline, and the Vault uses it rather than a second one. */
    @Test public void theVaultBuildsNoParallelRequestPipeline() {
        String bridge = source("OrbitVaultAttachment");
        assertTrue("it produces an ordinary ComposerAttachment",
                bridge.contains("return new ComposerAttachment("));
        String chat = source("ChatActivity");
        assertTrue("the composer appends them to the one collection it already owns",
                chat.contains("composerAttachments.addAll(staged)"));
        assertFalse("and no Vault-only collection exists",
                chat.contains("vaultAttachments"));
    }

    // ---- link safety --------------------------------------------------------------------------------

    /**
     * Only an ordinary web address is ever openable, whatever a stored row claims.
     *
     * <p>Re-validated at the tap rather than trusted from disk, because a stored address can have
     * arrived from a share, a paste, or an imported backup - none of which Orbit wrote.
     */
    @Test public void onlyHttpAndHttpsAddressesCanBeOpened() {
        for (String allowed : new String[]{"http://example.com/a", "https://example.com/a",
                "https://www.example.com/a?b=c", "HTTPS://Example.com/A"}) {
            assertEquals(allowed, OrbitVaultItem.singleLinkOrEmpty(allowed));
        }
        for (String refused : new String[]{
                "javascript:alert(1)",
                "file:///data/data/com.orbit.assistant/files/secret.txt",
                "intent://scan/#Intent;scheme=zxing;end",
                "content://media/external/images/1",
                "orbit://do-something",
                "data:text/html,<script>alert(1)</script>",
                "ftp://example.com/a",
                "https://",
                "http://nohost",
                "not a url at all",
                "https://example.com/a https://example.com/b",
                ""}) {
            assertEquals("must never be openable: " + refused,
                    "", OrbitVaultItem.singleLinkOrEmpty(refused));
        }
        assertTrue("and the item screen re-checks before handing anything to Android",
                source("OrbitVaultItemActivity")
                        .contains("OrbitVaultItem.singleLinkOrEmpty(item.body)"));
    }

    /** A hostile address stored as a link cannot be opened from the item screen. */
    @Test public void ahostileStoredAddressIsRefusedAtTheTap() {
        // Written directly, because saveLink would have refused it on the way in. This is the
        // restored-backup case: a row that already exists and claims to be a link.
        assertTrue(OrbitVaultStore.prefs(context).edit().putString("items_v1",
                "[{\"id\":\"bad\",\"type\":\"link\",\"title\":\"Bad\","
                        + "\"body\":\"javascript:alert(1)\",\"source\":\"t\",\"mediaPath\":\"\","
                        + "\"createdAt\":1,\"modifiedAt\":1}]").commit());

        Activity screen = Robolectric.buildActivity(OrbitVaultItemActivity.class,
                new Intent(context, OrbitVaultItemActivity.class)
                        .putExtra(OrbitVaultItemActivity.EXTRA_ITEM_ID, "bad")).setup().get();
        View open = findClickableWithDescription(screen.getWindow().getDecorView(),
                OrbitVaultItemActivity.ACTION_OPEN_LINK);
        if (open != null) open.performClick();
        assertNull("no Intent may leave Orbit for a scheme it does not open",
                Shadows.shadowOf(screen).getNextStartedActivity());
    }

    // ---- the item screen's action surface -----------------------------------------------------------

    /**
     * A saved link offers Open link and Ask Orbit at the top, the utilities compactly below, and
     * Delete on its own.
     *
     * <p>Asserted as a set of labels rather than as pixels, so it stays a statement about what the
     * screen offers and does not become a brittle screenshot of how it currently looks.
     */
    @Test public void asavedLinkOffersOpenLinkAndAskOrbitAndASeparateDelete() {
        OrbitVaultItem link = OrbitVaultStore.saveLink(context, "Recipe",
                "https://example.com/recipe", "Clipboard");
        Activity screen = openItem(link.id);
        List<String> drawn = textOf(screen.getWindow().getDecorView());

        assertTrue(drawn.contains(OrbitVaultItemActivity.ACTION_OPEN_LINK));
        assertTrue(drawn.contains(OrbitVaultItemActivity.ACTION_ASK));
        assertTrue(drawn.contains(OrbitVaultItemActivity.ACTION_COPY));
        assertTrue(drawn.contains(OrbitVaultItemActivity.ACTION_SHARE));
        assertTrue(drawn.contains(OrbitVaultItemActivity.ACTION_RENAME));
        assertTrue(drawn.contains(OrbitVaultItemActivity.ACTION_DELETE));
        assertTrue("the truthful line about the address stays",
                String.join("\n", drawn).contains("has not opened or read this address"));

        assertTrue("Open link is the one filled control on a saved address",
                source("OrbitVaultItemActivity")
                        .contains("filledAction(ACTION_OPEN_LINK"));
        assertTrue("the utilities are compact rather than full-width buttons",
                source("OrbitVaultItemActivity").contains("compactAction(ACTION_COPY"));
        assertTrue("and Delete keeps Orbit's destructive treatment",
                source("OrbitVaultItemActivity").contains("delete.setTextColor(UiKit.DANGER)"));
    }

    /** Something that is not an address makes Ask Orbit the primary action and offers no Open link. */
    @Test public void asavedNoteMakesAskOrbitThePrimaryAction() {
        OrbitVaultItem note = OrbitVaultStore.saveText(context, "Packing list", "Charger",
                "Quick Capture");
        List<String> drawn = textOf(openItem(note.id).getWindow().getDecorView());
        assertTrue(drawn.contains(OrbitVaultItemActivity.ACTION_ASK));
        assertFalse("there is no address to open", drawn.contains(
                OrbitVaultItemActivity.ACTION_OPEN_LINK));
        assertTrue(drawn.contains(OrbitVaultItemActivity.ACTION_EDIT));
    }

    /** The note section says what it is, in both of its states. */
    @Test public void theitemScreenShowsTheNoteSection() {
        OrbitVaultItem item = OrbitVaultStore.saveText(context, "Clip", "Body", "test");
        List<String> empty = textOf(openItem(item.id).getWindow().getDecorView());
        assertTrue(empty.contains(OrbitVaultItemActivity.NOTE_HEADING));
        assertTrue("an item with no note invites one",
                empty.contains(OrbitVaultItemActivity.NOTE_EMPTY));

        assertTrue(OrbitVaultStore.updateNote(context, item.id, "Because of the animation."));
        List<String> written = textOf(openItem(item.id).getWindow().getDecorView());
        assertTrue(written.contains(OrbitVaultItemActivity.NOTE_HEADING));
        assertTrue(written.contains("Because of the animation."));
        assertFalse(written.contains(OrbitVaultItemActivity.NOTE_EMPTY));
    }

    // ---- privacy -------------------------------------------------------------------------------------

    /**
     * Looking at the Vault, searching it, and writing a note are all silent.
     *
     * <p>Zero provider calls was Beta 1's claim and Beta 2 does not weaken it: the two new doors
     * are opened by the user, and everything that is not those two doors still contacts nothing.
     */
    @Test public void browsingSearchingAndNotingContactNothing() {
        OrbitVaultStore.saveText(context, "Packing list", "Charger", "Quick Capture");
        OrbitVaultItem item = OrbitVaultStore.list(context).get(0);

        Activity vault = Robolectric.buildActivity(OrbitVaultActivity.class).setup().get();
        assertNull(Shadows.shadowOf(vault).getNextStartedActivity());
        OrbitVaultStore.search(context, "charger", OrbitVaultStore.Sort.NEWEST);
        OrbitVaultStore.updateNote(context, item.id, "a note written offline");
        Prefs.get(context).edit().putBoolean(Prefs.VAULT_ENABLED, false).commit();

        Activity picker = Robolectric.buildActivity(OrbitVaultPickerActivity.class).setup().get();
        assertNull("opening the picker sends nothing either",
                Shadows.shadowOf(picker).getNextStartedActivity());
        assertTrue("and none of it teaches Orbit anything",
                MemoryStore.list(context).isEmpty());
    }

    /** The Vault gains no new exported door, and the picker is not one. */
    @Test public void thepickerIsNotExported() {
        String manifest = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/AndroidManifest.xml");
        int declaration = manifest.indexOf("android:name=\".OrbitVaultPickerActivity\"");
        assertTrue("the picker must be declared", declaration > 0);
        String block = manifest.substring(declaration, manifest.indexOf("/>", declaration));
        assertTrue(block.contains("android:exported=\"false\""));
        assertFalse(block.contains("intent-filter"));
        assertEquals("Attach from Vault",
                OrbitNavigation.labelFor(OrbitVaultPickerActivity.class));
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private Activity openItem(String id) {
        return Robolectric.buildActivity(OrbitVaultItemActivity.class,
                new Intent(context, OrbitVaultItemActivity.class)
                        .putExtra(OrbitVaultItemActivity.EXTRA_ITEM_ID, id)).setup().get();
    }

    private static List<String> textOf(View view) {
        List<String> out = new ArrayList<>();
        collect(view, out);
        return out;
    }

    private static void collect(View view, List<String> out) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 0) out.add(text.toString());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out);
        }
    }

    private static View findClickableWithDescription(View view, String description) {
        CharSequence actual = view.getContentDescription();
        if (view.isClickable() && actual != null && description.contentEquals(actual)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findClickableWithDescription(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * Lets ChatActivity start under Robolectric without a real background queue.
     *
     * <p>Nothing under test here goes near it: these assertions are about a composer that has not
     * sent anything. It exists so that opening the screen at all does not fail on WorkManager.
     */
    private void initWorkManager() {
        try {
            WorkManager.getInstance(context);
            return;
        } catch (IllegalStateException notInitialized) {
            // Falls through to initialize it for this test application.
        }
        ExecutorService background = Executors.newSingleThreadExecutor();
        WorkManager.initialize(context, new Configuration.Builder()
                .setExecutor(background)
                .setTaskExecutor(background)
                .setWorkerFactory(new WorkerFactory() {
                    @Override public ListenableWorker createWorker(
                            Context appContext, String workerClassName, WorkerParameters params) {
                        return new Worker(appContext, params) {
                            @Override public Result doWork() { return Result.success(); }
                        };
                    }
                })
                .build());
    }
}
