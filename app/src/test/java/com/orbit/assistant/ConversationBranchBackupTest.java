package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

/**
 * Branches, answer variants and kept context inside Backup &amp; Restore (0.8.3.0-beta.3).
 *
 * <p>A backup carries every branch of a chat, not just the visible one, with the same rule for
 * pictures that visible messages already follow: bytes travel, device paths do not. A backup from an
 * earlier Orbit, which has no branch data, restores exactly as before.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class ConversationBranchBackupTest {
    private Context context;
    private int nextUri;

    private static final class FakeKeyStoreProvider extends java.security.Provider {
        FakeKeyStoreProvider() {
            super("AndroidKeyStore", 1.0, "Test-only empty AndroidKeyStore");
            put("KeyStore.AndroidKeyStore", OrbitVaultBackupTest.FakeAndroidKeyStore.class.getName());
        }
    }

    @Before public void setUp() {
        if (java.security.Security.getProvider("AndroidKeyStore") == null) {
            java.security.Security.addProvider(new FakeKeyStoreProvider());
        }
        context = RuntimeEnvironment.getApplication();
        MemoryStore.clear(context);
        ConversationStore.clear(context);
    }

    @After public void tearDown() {
        java.security.Security.removeProvider("AndroidKeyStore");
    }

    private static byte[] jpeg() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, (byte) 0xFF, (byte) 0xD9};
    }

    private String branchedChatWithHiddenPhoto() throws Exception {
        File dir = new File(context.getFilesDir(), "orbit_attachments/history");
        dir.mkdirs();
        File photo = new File(dir, "hidden-branch.jpg");
        try (FileOutputStream out = new FileOutputStream(photo)) { out.write(jpeg()); }
        String id = ConversationStore.newId();
        ConversationStore.save(context, id, Arrays.asList(
                new AssistantClient.History("user", "What is this plant?", true,
                        photo.getAbsolutePath(), "image", "Photo", ""),
                new AssistantClient.History("assistant", "A fern")
                        .withReplyProvenance("r1", Collections.emptyList())));
        String parent = ConversationBranches.parentKey(ConversationStore.load(context, id).messages, 1);
        ConversationStore.commitAnswerVariant(context, id, 1, parent,
                new AssistantClient.History("assistant", "A bracken fern")
                        .withReplyProvenance("r2", Collections.emptyList()));
        String key = ConversationBranches.fingerprint(ConversationStore.load(context, id).messages.get(0));
        ConversationStore.branchFromUserMessage(context, id, 0, key,
                new AssistantClient.History("user", "Is it edible?"));
        ConversationStore.keep(context, id, KeptContext.create("file_text", "Notes.txt",
                "Field notes from the walk.", "/data/local/notes.txt", "", false));
        return id;
    }

    @Test public void everyBranchVariantAndKeptItemSurvivesARoundTrip() throws Exception {
        String id = branchedChatWithHiddenPhoto();
        String backup = exported();
        assertFalse("no device path leaves the phone, hidden branches included",
                backup.contains("hidden-branch.jpg") || backup.contains("/data/local/notes.txt"));

        ConversationStore.clear(context);
        restore(backup);
        ConversationStore.Conversation chat = ConversationStore.load(context, id);
        assertNotNull(chat);
        assertEquals("Is it edible?", chat.messages.get(0).content);
        ConversationBranches.Fork edit = chat.forkAt(0);
        assertNotNull("the edited branch came back", edit);
        ConversationBranches.Path original = edit.variants.get(0);
        assertEquals("What is this plant?", original.messages.get(0).content);
        assertEquals("the hidden branch's photo came back as a fresh file", 1,
                original.messages.get(0).attachmentPaths.size());
        assertTrue(new File(original.messages.get(0).attachmentPath).isFile());
        ConversationBranches.Fork variants = original.forks.get(0);
        assertEquals("both answer versions came back", 2, variants.count());

        assertEquals(1, chat.keptItems().size());
        assertEquals("Field notes from the walk.", chat.keptItems().get(0).text);
        assertEquals("", chat.keptItems().get(0).documentPath);

        ConversationStore.BranchResult back = ConversationStore.selectVariant(context, id, 0, 0);
        assertTrue(back.ok());
        assertEquals("A bracken fern", back.messages.get(1).content);
    }

    @Test public void aBackupFromBeforeBranchesRestoresUnchanged() throws Exception {
        ConversationStore.save(context, "plain", Arrays.asList(new AssistantClient.History("user", "Hi"),
                new AssistantClient.History("assistant", "Hello")));
        String backup = exported();
        JSONObject root = new JSONObject(backup);
        JSONObject conversation = root.getJSONObject("data").getJSONArray("conversations").getJSONObject(0);
        assertFalse(conversation.has("forks"));
        assertFalse(conversation.has("kept"));
        ConversationStore.clear(context);
        restore(backup);
        assertEquals(2, ConversationStore.load(context, "plain").messages.size());
    }

    @Test public void anImportedBranchCarryingADevicePathIsRefused() throws Exception {
        String id = branchedChatWithHiddenPhoto();
        JSONObject root = new JSONObject(exported());
        JSONObject conversation = root.getJSONObject("data").getJSONArray("conversations").getJSONObject(0);
        JSONObject hidden = conversation.getJSONArray("forks").getJSONObject(0)
                .getJSONArray("variants").getJSONObject(0).getJSONArray("messages").getJSONObject(0);
        hidden.put("attachmentPath", "/data/data/someone-else/secret.jpg");
        assertRefused(root.toString());
        assertNotNull(id);
    }

    @Test public void anImportedForkWithTooManyVariantsIsRefused() throws Exception {
        branchedChatWithHiddenPhoto();
        JSONObject root = new JSONObject(exported());
        JSONObject conversation = root.getJSONObject("data").getJSONArray("conversations").getJSONObject(0);
        JSONArray variants = conversation.getJSONArray("forks").getJSONObject(0).getJSONArray("variants");
        for (int i = 0; i < ConversationBranches.MAX_VARIANTS; i++) {
            variants.put(new JSONObject().put("messages", new JSONArray()
                    .put(new JSONObject().put("role", "user").put("content", "x" + i))));
        }
        assertRefused(root.toString());
    }

    @Test public void anImportedKeptItemWithAPathIsRefused() throws Exception {
        branchedChatWithHiddenPhoto();
        JSONObject root = new JSONObject(exported());
        JSONObject conversation = root.getJSONObject("data").getJSONArray("conversations").getJSONObject(0);
        conversation.getJSONArray("kept").getJSONObject(0).put("documentPath", "/sdcard/x.pdf");
        assertRefused(root.toString());
    }

    // ---- plumbing ---------------------------------------------------------------------------------

    private String exported() throws Exception {
        Uri uri = Uri.parse("content://test/backup-" + (nextUri++));
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        resolver().registerOutputStream(uri, captured);
        OrbitBackupManager.exportTo(context, uri);
        return captured.toString("UTF-8");
    }

    private void restore(String backup) throws Exception {
        OrbitBackupManager.restore(context, prepare(backup));
    }

    private OrbitBackupManager.PreparedRestore prepare(String backup) throws Exception {
        Uri uri = Uri.parse("content://test/restore-" + (nextUri++));
        resolver().registerInputStream(uri,
                new ByteArrayInputStream(backup.getBytes(StandardCharsets.UTF_8)));
        return OrbitBackupManager.prepareRestore(context, uri);
    }

    private void assertRefused(String backup) {
        try {
            prepare(backup);
            fail("damaged branch data must be refused before anything is written");
        } catch (Exception expected) {
            assertNotNull(expected);
        }
    }

    private ShadowContentResolver resolver() {
        return Shadows.shadowOf(context.getContentResolver());
    }
}
