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
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Orbit Vault inside Backup &amp; Restore.
 *
 * <p>Three promises are asserted here, and they matter in this order. A backup written before the
 * Vault existed must still restore, or shipping this would strand every backup anybody already
 * holds. A Vault must come back with its identities and its pictures intact, or backing it up was
 * theatre. And a damaged Vault section must be refused before anything is written, rather than
 * taking the rest of somebody's Orbit down with it.
 *
 * <p>The picture rule is the same one conversation attachments already follow: bytes travel, paths
 * do not. A path inside a backup is a path into another device, so one arriving in an imported file
 * is a refusal rather than something to be helpful about.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class OrbitVaultBackupTest {

    private Context context;
    private int nextUri;

    /**
     * A stand-in for Android's hardware-backed keystore, which Robolectric does not provide.
     *
     * <p>A committed restore clears extension credentials as its last step, and refuses to report
     * success if it cannot - which is correct on a phone and means the whole restore path is
     * unreachable in a JVM without an {@code AndroidKeyStore} provider. This supplies an empty one
     * so the restore can be tested at all. It holds nothing and is removed again afterwards; every
     * assertion in this file is about the Vault, never about credential storage.
     */
    public static final class FakeAndroidKeyStore extends java.security.KeyStoreSpi {
        @Override public java.security.Key engineGetKey(String alias, char[] password) { return null; }
        @Override public java.security.cert.Certificate[] engineGetCertificateChain(String alias) { return null; }
        @Override public java.security.cert.Certificate engineGetCertificate(String alias) { return null; }
        @Override public java.util.Date engineGetCreationDate(String alias) { return null; }
        @Override public void engineSetKeyEntry(String a, java.security.Key k, char[] p,
                                                java.security.cert.Certificate[] c) {}
        @Override public void engineSetKeyEntry(String a, byte[] k, java.security.cert.Certificate[] c) {}
        @Override public void engineSetCertificateEntry(String a, java.security.cert.Certificate c) {}
        @Override public void engineDeleteEntry(String alias) {}
        @Override public java.util.Enumeration<String> engineAliases() {
            return java.util.Collections.emptyEnumeration();
        }
        @Override public boolean engineContainsAlias(String alias) { return false; }
        @Override public int engineSize() { return 0; }
        @Override public boolean engineIsKeyEntry(String alias) { return false; }
        @Override public boolean engineIsCertificateEntry(String alias) { return false; }
        @Override public String engineGetCertificateAlias(java.security.cert.Certificate cert) { return null; }
        @Override public void engineStore(java.io.OutputStream stream, char[] password) {}
        @Override public void engineLoad(java.io.InputStream stream, char[] password) {}
    }

    private static final class FakeKeyStoreProvider extends java.security.Provider {
        FakeKeyStoreProvider() {
            super("AndroidKeyStore", 1.0, "Test-only empty AndroidKeyStore");
            put("KeyStore.AndroidKeyStore", FakeAndroidKeyStore.class.getName());
        }
    }

    @Before public void setUp() {
        if (java.security.Security.getProvider("AndroidKeyStore") == null) {
            java.security.Security.addProvider(new FakeKeyStoreProvider());
        }
        context = RuntimeEnvironment.getApplication();
        OrbitVaultStore.prefs(context).edit().clear().commit();
        MemoryStore.clear(context);
        ConversationStore.clear(context);
        File media = OrbitVaultMedia.directory(context);
        File[] files = media.listFiles();
        if (files != null) for (File file : files) file.delete();
    }

    @org.junit.After public void tearDown() {
        java.security.Security.removeProvider("AndroidKeyStore");
    }

    // ---- what a backup carries -----------------------------------------------------------------

    @Test public void vaultTextAndMetadataSurviveARoundTrip() throws Exception {
        OrbitVaultItem note = OrbitVaultStore.saveText(context, "Packing list",
                "Charger\nPassport", "Quick Capture");
        OrbitVaultItem link = OrbitVaultStore.saveLink(context, "Recipe",
                "https://example.com/sourdough", "Shared to Orbit");
        OrbitVaultItem reply = OrbitVaultStore.saveOrbitReply(context, "Feed it twice a day.");

        String backup = exported();
        OrbitVaultStore.prefs(context).edit().clear().commit();
        assertEquals(0, OrbitVaultStore.count(context));

        restore(backup);

        assertEquals(3, OrbitVaultStore.count(context));
        OrbitVaultItem restoredNote = OrbitVaultStore.get(context, note.id);
        assertNotNull("identities must survive, so an item stays the same item", restoredNote);
        assertEquals("Packing list", restoredNote.title);
        assertEquals("Charger\nPassport", restoredNote.body);
        assertEquals("Quick Capture", restoredNote.source);
        assertEquals(note.createdAt, restoredNote.createdAt);

        assertEquals(OrbitVaultItem.TYPE_LINK, OrbitVaultStore.get(context, link.id).type);
        assertEquals("https://example.com/sourdough", OrbitVaultStore.get(context, link.id).body);
        assertEquals(OrbitVaultItem.TYPE_ORBIT_REPLY, OrbitVaultStore.get(context, reply.id).type);
    }

    /**
     * A note the user wrote travels with the item it belongs to, and stays its own field.
     *
     * <p>A backup that carried the saved content but dropped the reason for keeping it would lose
     * exactly the half the user wrote themselves, which is the half they cannot reconstruct.
     */
    @Test public void anoteSurvivesARoundTripAsItsOwnField() throws Exception {
        OrbitVaultItem noted = OrbitVaultStore.saveLink(context, "Clip",
                "https://example.com/clip", "Clipboard", "Look at this for the animation idea");
        OrbitVaultItem plain = OrbitVaultStore.saveText(context, "Plain", "No note here", "test");

        String backup = exported();
        JSONObject data = new JSONObject(backup).getJSONObject("data");
        JSONArray items = data.getJSONArray("vault");
        boolean carried = false;
        for (int i = 0; i < items.length(); i++) {
            if (noted.id.equals(items.getJSONObject(i).optString("id"))) {
                assertEquals("Look at this for the animation idea",
                        items.getJSONObject(i).getString("note"));
                assertEquals("and the saved address is untouched by it",
                        "https://example.com/clip", items.getJSONObject(i).getString("body"));
                carried = true;
            }
        }
        assertTrue("the noted item must appear in the backup", carried);

        OrbitVaultStore.prefs(context).edit().clear().commit();
        restore(backup);

        assertEquals("Look at this for the animation idea",
                OrbitVaultStore.get(context, noted.id).note);
        assertEquals("https://example.com/clip", OrbitVaultStore.get(context, noted.id).body);
        assertEquals("an item that never had a note comes back without one",
                "", OrbitVaultStore.get(context, plain.id).note);
    }

    /**
     * A Beta 1 backup, written before notes existed, restores with empty notes.
     *
     * <p>Every backup a Beta 1 tester is holding is one of these. Removing the key entirely is
     * what those files genuinely look like, and the result must be an ordinary Vault rather than
     * an unsupported file.
     */
    @Test public void abeta1BackupWithoutNotesRestoresNormally() throws Exception {
        OrbitVaultItem item = OrbitVaultStore.saveText(context, "Packing list", "Charger",
                "Quick Capture", "written in Beta 2");

        JSONObject root = new JSONObject(exported());
        JSONArray items = root.getJSONObject("data").getJSONArray("vault");
        for (int i = 0; i < items.length(); i++) items.getJSONObject(i).remove("note");

        OrbitVaultStore.prefs(context).edit().clear().commit();
        restore(root.toString());

        OrbitVaultItem restored = OrbitVaultStore.get(context, item.id);
        assertNotNull("a backup with no note key must still restore", restored);
        assertEquals("Charger", restored.body);
        assertEquals("Packing list", restored.title);
        assertEquals("", restored.note);
    }

    /** An oversized note in an imported file is refused like any other field out of bounds. */
    @Test public void anoversizedNoteInAnImportedBackupIsRefused() throws Exception {
        OrbitVaultStore.saveText(context, "Packing list", "Charger", "Quick Capture", "fine");
        JSONObject root = new JSONObject(exported());
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < OrbitVaultItem.MAX_NOTE_CHARS + 10; i++) huge.append('n');
        root.getJSONObject("data").getJSONArray("vault").getJSONObject(0)
                .put("note", huge.toString());

        OrbitVaultStore.prefs(context).edit().clear().commit();
        try {
            restore(root.toString());
            fail("an out-of-bounds note must be refused before anything is written");
        } catch (Exception expected) {
            assertEquals("and nothing is written when it is", 0, OrbitVaultStore.count(context));
        }
    }

    /** Bytes travel; the path this device used does not leave it. */
    @Test public void avaultPictureTravelsAsBytesAndNeverAsAPath() throws Exception {
        OrbitVaultItem image = seedImage("Receipt");
        String originalPath = image.mediaPath;
        assertTrue(new File(originalPath).isFile());

        String backup = exported();
        JSONObject data = new JSONObject(backup).getJSONObject("data");
        assertEquals(1, data.getJSONArray("vaultMedia").length());
        assertEquals("image/jpeg",
                data.getJSONArray("vaultMedia").getJSONObject(0).getString("mimeType"));
        assertFalse("no device-local path may leave this phone",
                backup.contains(originalPath));
        assertEquals("", data.getJSONArray("vault").getJSONObject(0).optString("mediaPath", ""));

        OrbitVaultStore.prefs(context).edit().clear().commit();
        assertTrue(new File(originalPath).delete());

        restore(backup);

        OrbitVaultItem restored = OrbitVaultStore.get(context, image.id);
        assertNotNull(restored);
        assertTrue("the picture must be written to a fresh private file on this device",
                OrbitVaultMedia.owns(context, restored.mediaPath));
        assertTrue(new File(restored.mediaPath).isFile());
        assertFalse("and never at the path the backup came from",
                restored.mediaPath.equals(originalPath));
    }

    /**
     * A backup written before Orbit had a Vault restores exactly as it always did.
     *
     * <p>The single most important case in this file: every backup anybody is currently holding is
     * one of these, and a new key that turned them all into "unsupported" files would be the worst
     * possible way to ship a new feature.
     */
    @Test public void apreVaultBackupStillRestores() throws Exception {
        MemoryStore.add(context, MemoryStore.CATEGORY_PREFERENCE, "Prefers metric units");
        OrbitVaultStore.saveText(context, "Local", "not in the backup", "test");

        JSONObject root = new JSONObject(exported());
        JSONObject data = root.getJSONObject("data");
        data.remove("vault");
        data.remove("vaultMedia");
        assertFalse(data.has("vault"));

        restore(root.toString());

        assertEquals("a pre-Vault backup restores an empty Vault, not a failure",
                0, OrbitVaultStore.count(context));
        assertEquals("and everything it did carry is restored",
                1, MemoryStore.list(context).size());
    }

    // ---- what a backup refuses -----------------------------------------------------------------

    @Test public void adevicePathInsideAnImportedVaultIsRefused() throws Exception {
        seedImage("Receipt");
        JSONObject root = new JSONObject(exported());
        root.getJSONObject("data").getJSONArray("vault").getJSONObject(0)
                .put("mediaPath", "/data/data/com.orbit.assistant/files/somebody-elses.jpg");
        assertRefused(root.toString());
    }

    @Test public void avaultImageWithNoBytesInTheBackupIsRefused() throws Exception {
        seedImage("Receipt");
        JSONObject root = new JSONObject(exported());
        root.getJSONObject("data").put("vaultMedia", new JSONArray());
        assertRefused(root.toString());
    }

    @Test public void picturesThatNoItemRefersToAreRefused() throws Exception {
        seedImage("Receipt");
        JSONObject root = new JSONObject(exported());
        JSONArray media = root.getJSONObject("data").getJSONArray("vaultMedia");
        media.put(new JSONObject(media.getJSONObject(0).toString()).put("id", "unreferenced"));
        assertRefused(root.toString());
    }

    @Test public void payloadThatIsNotAJpegIsRefused() throws Exception {
        seedImage("Receipt");
        JSONObject root = new JSONObject(exported());
        JSONObject record = root.getJSONObject("data").getJSONArray("vaultMedia").getJSONObject(0);
        byte[] notAnImage = "<html>not a picture</html>".getBytes(StandardCharsets.UTF_8);
        record.put("data", android.util.Base64.encodeToString(notAnImage, android.util.Base64.NO_WRAP));
        record.put("size", notAnImage.length);
        assertRefused(root.toString());
    }

    @Test public void anunknownVaultTypeIsRefused() throws Exception {
        OrbitVaultStore.saveText(context, "Note", "body", "test");
        JSONObject root = new JSONObject(exported());
        root.getJSONObject("data").getJSONArray("vault").getJSONObject(0).put("type", "executable");
        assertRefused(root.toString());
    }

    /**
     * A refusal costs nothing. The user still has everything they had a moment earlier.
     *
     * <p>Refusal happens in {@code prepareRestore}, before a single store is written, so a damaged
     * Vault section cannot take conversations, Memory or the existing Vault down with it.
     */
    @Test public void arefusalLeavesEveryOtherStoreUntouched() throws Exception {
        seedImage("Receipt");
        MemoryStore.add(context, MemoryStore.CATEGORY_PREFERENCE, "Prefers metric units");
        JSONObject root = new JSONObject(exported());
        root.getJSONObject("data").getJSONArray("vault").getJSONObject(0).put("type", "executable");

        int vaultBefore = OrbitVaultStore.count(context);
        int memoriesBefore = MemoryStore.list(context).size();
        assertRefused(root.toString());

        assertEquals(vaultBefore, OrbitVaultStore.count(context));
        assertEquals(memoriesBefore, MemoryStore.list(context).size());
        List<OrbitVaultItem> items = OrbitVaultStore.list(context);
        assertTrue("and the local pictures are still on disk",
                new File(items.get(0).mediaPath).isFile());
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /**
     * A Vault image item written directly, with real JPEG bytes.
     *
     * <p>Robolectric's {@code Bitmap.compress} does not produce a real JPEG, and the backup checks
     * the magic bytes of everything it carries - correctly - so the picture is written as bytes
     * rather than encoded from a fake bitmap.
     */
    private OrbitVaultItem seedImage(String title) throws Exception {
        byte[] jpeg = new byte[512];
        jpeg[0] = (byte) 0xff;
        jpeg[1] = (byte) 0xd8;
        jpeg[2] = (byte) 0xff;
        for (int i = 3; i < jpeg.length; i++) jpeg[i] = (byte) (i % 251);
        String path = OrbitVaultMedia.writeBytes(context, jpeg);
        assertFalse("the test picture must be written", path.isEmpty());

        OrbitVaultItem item = new OrbitVaultItem("image-" + title, OrbitVaultItem.TYPE_IMAGE,
                title, "", "Photo", path, 4000L, 4000L);
        JSONArray stored = new JSONArray(OrbitVaultStore.backupJson(context));
        stored.put(item.toJson());
        assertTrue(OrbitVaultStore.prefs(context).edit()
                .putString("items_v1", stored.toString()).commit());
        return item;
    }

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
            fail("a damaged Vault section must be refused before anything is written");
        } catch (Exception expected) {
            assertNotNull(expected);
        }
    }

    private ShadowContentResolver resolver() {
        return Shadows.shadowOf(context.getContentResolver());
    }
}
