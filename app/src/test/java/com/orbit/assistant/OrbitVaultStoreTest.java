package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Orbit Vault's store: what it keeps, what it finds, and what it destroys.
 *
 * <p>Two groups of assertion carry the weight here. The first is durability, because a Vault that
 * loses something the user deliberately saved has failed at the only job it has - so a damaged
 * document must cost one row rather than the collection, and a saved answer must come back exactly
 * as it went in.
 *
 * <p>The second is deletion, because this is the one part of the feature that destroys files. A
 * delete has to remove the picture the item owned, must never reach a file the Vault does not own,
 * and must not touch anything when the item never had a picture at all.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class OrbitVaultStoreTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        OrbitVaultStore.prefs(context).edit().clear().commit();
        File media = OrbitVaultMedia.directory(context);
        File[] files = media.listFiles();
        if (files != null) for (File file : files) file.delete();
    }

    private Bitmap picture() {
        Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.RED);
        return bitmap;
    }

    // ---- what the store keeps ---------------------------------------------------------------------

    @Test public void atextItemIsSavedAndReadBack() {
        OrbitVaultItem saved = OrbitVaultStore.saveText(context, "Packing list",
                "Charger\nPassport\nHeadphones", "Quick Capture");
        assertNotNull(saved);
        assertEquals(OrbitVaultItem.TYPE_TEXT, saved.type);

        OrbitVaultItem read = OrbitVaultStore.get(context, saved.id);
        assertNotNull("a saved item must survive being read back", read);
        assertEquals("Packing list", read.title);
        assertEquals("Charger\nPassport\nHeadphones", read.body);
        assertEquals("Quick Capture", read.source);
        assertTrue(read.mediaPath.isEmpty());
    }

    /** One bare address is a link; a paragraph that merely contains one is a note. */
    @Test public void abareAddressIsSavedAsALink() {
        OrbitVaultItem link = OrbitVaultStore.saveText(context, "",
                "https://example.com/article?x=1", "Shared to Orbit");
        assertNotNull(link);
        assertEquals(OrbitVaultItem.TYPE_LINK, link.type);
        assertEquals("https://example.com/article?x=1", link.body);
        assertEquals("example.com", link.hostLabel());
    }

    @Test public void textAroundAnAddressStaysANote() {
        OrbitVaultItem note = OrbitVaultStore.saveText(context, "",
                "Read this later https://example.com/article", "Shared to Orbit");
        assertNotNull(note);
        assertEquals(OrbitVaultItem.TYPE_TEXT, note.type);
    }

    /** Only ordinary web addresses become links. Everything else stays inert text. */
    @Test public void onlyHttpAndHttpsAddressesAreLinks() {
        for (String hostile : new String[]{
                "javascript:alert(1)", "file:///data/data/com.orbit.assistant/x.xml",
                "content://com.example/secret", "intent://scan/#Intent;scheme=zxing;end",
                "orbit://run-routine", "ftp://example.com/x", "http://", "https://nohost",
                "market://details?id=com.example"}) {
            assertEquals(hostile + " must never be treated as a link",
                    "", OrbitVaultItem.singleLinkOrEmpty(hostile));
            OrbitVaultItem saved = OrbitVaultStore.saveText(context, "", hostile, "test");
            assertNotNull(saved);
            assertEquals(hostile + " must be kept as inert text",
                    OrbitVaultItem.TYPE_TEXT, saved.type);
        }
    }

    @Test public void animageItemKeepsAPrivateCopyOfThePicture() {
        OrbitVaultItem image = OrbitVaultStore.saveImage(context, picture(), "Receipt", "Photo");
        assertNotNull(image);
        assertEquals(OrbitVaultItem.TYPE_IMAGE, image.type);
        assertTrue("an image item must own a private file", OrbitVaultMedia.owns(context, image.mediaPath));
        assertTrue("and that file must exist", new File(image.mediaPath).isFile());
        assertTrue("and must hold the copied bytes", new File(image.mediaPath).length() > 0L);
    }

    @Test public void asavedOrbitReplyKeepsItsTypeAndText() {
        OrbitVaultItem reply = OrbitVaultStore.saveOrbitReply(context,
                "OLED is usually best for perfect blacks.");
        assertNotNull(reply);
        assertEquals(OrbitVaultItem.TYPE_ORBIT_REPLY, reply.type);
        assertEquals("OLED is usually best for perfect blacks.", reply.body);
        assertEquals("Orbit answer", reply.source);
    }

    @Test public void everySavedItemGetsItsOwnIdentity() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 25; i++) {
            OrbitVaultItem item = OrbitVaultStore.saveText(context, "", "note " + i, "test");
            assertNotNull(item);
            assertTrue("ids must be unique: " + item.id, ids.add(item.id));
        }
        assertEquals(25, OrbitVaultStore.count(context));
    }

    @Test public void timestampsSurviveTheRoundTrip() {
        OrbitVaultItem saved = OrbitVaultStore.saveText(context, "T", "body", "test");
        OrbitVaultItem read = OrbitVaultStore.get(context, saved.id);
        assertEquals(saved.createdAt, read.createdAt);
        assertEquals(saved.modifiedAt, read.modifiedAt);
        assertTrue(read.createdAt > 0L);
    }

    /**
     * A damaged store costs the damaged rows and nothing else.
     *
     * <p>Refusing to open would be the worst possible failure for a Vault: the user would be told
     * their saved material is gone when most of it is sitting there intact.
     */
    @Test public void adamagedDocumentLosesOnlyTheDamagedRows() {
        OrbitVaultStore.prefs(context).edit().putString("items_v1",
                "[{\"id\":\"a\",\"type\":\"text\",\"title\":\"Kept\",\"body\":\"real\","
                        + "\"createdAt\":1000,\"modifiedAt\":1000},"
                        + "{\"id\":\"\",\"type\":\"text\",\"body\":\"no id\",\"createdAt\":1},"
                        + "{\"type\":\"nonsense\",\"body\":\"unknown type\",\"createdAt\":2},"
                        + "{\"id\":\"c\",\"type\":\"image\",\"createdAt\":3},"
                        + "\"not an object\"]").commit();
        List<OrbitVaultItem> items = OrbitVaultStore.list(context);
        assertEquals("only the intact row survives", 1, items.size());
        assertEquals("Kept", items.get(0).title);
    }

    @Test public void adocumentThatIsNotJsonAtAllReadsAsAnEmptyVault() {
        OrbitVaultStore.prefs(context).edit().putString("items_v1", "{ this is not json").commit();
        assertTrue(OrbitVaultStore.list(context).isEmpty());
        assertEquals(0, OrbitVaultStore.count(context));
        assertNotNull("and the Vault still accepts new items",
                OrbitVaultStore.saveText(context, "", "after the damage", "test"));
    }

    // ---- search ----------------------------------------------------------------------------------

    @Test public void searchMatchesTitleBodyUrlAndSource() {
        OrbitVaultStore.saveText(context, "Camping checklist", "tent and stove", "Quick Capture");
        OrbitVaultStore.saveText(context, "", "https://kayak-guide.example.com/rapids", "Shared to Orbit");
        OrbitVaultStore.saveOrbitReply(context, "Sourdough needs a stiff starter.");

        assertEquals("title", 1, OrbitVaultStore.search(context, "checklist",
                OrbitVaultStore.Sort.NEWEST).size());
        assertEquals("body", 1, OrbitVaultStore.search(context, "stove",
                OrbitVaultStore.Sort.NEWEST).size());
        assertEquals("url", 1, OrbitVaultStore.search(context, "kayak-guide",
                OrbitVaultStore.Sort.NEWEST).size());
        assertEquals("source label", 1, OrbitVaultStore.search(context, "Shared to Orbit",
                OrbitVaultStore.Sort.NEWEST).size());
        assertEquals("saved answer body", 1, OrbitVaultStore.search(context, "sourdough",
                OrbitVaultStore.Sort.NEWEST).size());
    }

    @Test public void searchIgnoresCase() {
        OrbitVaultStore.saveText(context, "Barcelona", "Sagrada Familia tickets", "test");
        assertEquals(1, OrbitVaultStore.search(context, "BARCELONA",
                OrbitVaultStore.Sort.NEWEST).size());
        assertEquals(1, OrbitVaultStore.search(context, "sAgRaDa",
                OrbitVaultStore.Sort.NEWEST).size());
    }

    @Test public void searchWithNoMatchReturnsNothingRatherThanEverything() {
        OrbitVaultStore.saveText(context, "Barcelona", "tickets", "test");
        assertTrue(OrbitVaultStore.search(context, "helsinki",
                OrbitVaultStore.Sort.NEWEST).isEmpty());
    }

    @Test public void anemptyQueryIsNotASearch() {
        OrbitVaultStore.saveText(context, "one", "one", "test");
        OrbitVaultStore.saveText(context, "two", "two", "test");
        assertEquals(2, OrbitVaultStore.search(context, "   ",
                OrbitVaultStore.Sort.NEWEST).size());
    }

    // ---- sorting ---------------------------------------------------------------------------------

    private void seedThreeInOrder() {
        write(new OrbitVaultItem("old", OrbitVaultItem.TYPE_TEXT, "Oldest", "1", "t", "", 1000L, 1000L),
                new OrbitVaultItem("mid", OrbitVaultItem.TYPE_TEXT, "Middle", "2", "t", "", 2000L, 2000L),
                new OrbitVaultItem("new", OrbitVaultItem.TYPE_TEXT, "Newest", "3", "t", "", 3000L, 3000L));
    }

    @Test public void newestFirstIsTheDefaultOrder() {
        seedThreeInOrder();
        List<OrbitVaultItem> items = OrbitVaultStore.list(context);
        assertEquals("Newest", items.get(0).title);
        assertEquals("Middle", items.get(1).title);
        assertEquals("Oldest", items.get(2).title);
        assertEquals(OrbitVaultStore.Sort.NEWEST, Prefs.vaultSort(context));
    }

    @Test public void oldestFirstReversesIt() {
        seedThreeInOrder();
        List<OrbitVaultItem> items = OrbitVaultStore.list(context, OrbitVaultStore.Sort.OLDEST);
        assertEquals("Oldest", items.get(0).title);
        assertEquals("Newest", items.get(2).title);
    }

    @Test public void searchResultsObeyTheChosenOrder() {
        seedThreeInOrder();
        assertEquals("Oldest", OrbitVaultStore.search(context, "e",
                OrbitVaultStore.Sort.OLDEST).get(0).title);
        assertEquals("Newest", OrbitVaultStore.search(context, "e",
                OrbitVaultStore.Sort.NEWEST).get(0).title);
    }

    /** An unreadable stored order is newest first, never undefined. */
    @Test public void anUnknownStoredOrderFallsBackToNewestFirst() {
        Prefs.get(context).edit().putString(Prefs.VAULT_SORT, "sideways").apply();
        assertEquals(OrbitVaultStore.Sort.NEWEST, Prefs.vaultSort(context));
        Prefs.setVaultSort(context, OrbitVaultStore.Sort.OLDEST);
        assertEquals(OrbitVaultStore.Sort.OLDEST, Prefs.vaultSort(context));
    }

    // ---- editing ---------------------------------------------------------------------------------

    @Test public void anoteCanBeRewritten() {
        OrbitVaultItem note = OrbitVaultStore.saveText(context, "Draft", "first version", "test");
        assertTrue(OrbitVaultStore.updateText(context, note.id, "Final", "second version"));
        OrbitVaultItem read = OrbitVaultStore.get(context, note.id);
        assertEquals("Final", read.title);
        assertEquals("second version", read.body);
        assertEquals("its identity and save time do not change", note.createdAt, read.createdAt);
    }

    /**
     * A saved answer keeps its own words.
     *
     * <p>An editable one would look like a quotation of Orbit and not be one, which is exactly the
     * confusion between "saved answer" and "note" the two types exist to avoid.
     */
    @Test public void asavedAnswerBodyIsReadOnlyButItsTitleIsNot() {
        OrbitVaultItem reply = OrbitVaultStore.saveOrbitReply(context, "Orbit's exact words.");
        assertFalse(reply.bodyIsEditable());
        assertFalse(OrbitVaultStore.updateText(context, reply.id, "New", "rewritten"));
        assertEquals("Orbit's exact words.", OrbitVaultStore.get(context, reply.id).body);

        assertTrue(OrbitVaultStore.updateTitle(context, reply.id, "Screen advice"));
        assertEquals("Screen advice", OrbitVaultStore.get(context, reply.id).title);
        assertEquals("and still its exact words", "Orbit's exact words.",
                OrbitVaultStore.get(context, reply.id).body);
    }

    @Test public void alinkAddressIsReadOnlyToo() {
        OrbitVaultItem link = OrbitVaultStore.saveLink(context, "", "https://example.com/a", "test");
        assertFalse(OrbitVaultStore.updateText(context, link.id, "x", "https://elsewhere.example.com"));
        assertEquals("https://example.com/a", OrbitVaultStore.get(context, link.id).body);
    }

    @Test public void anemptyBodyIsNeverSaved() {
        assertNull(OrbitVaultStore.saveText(context, "Title only", "   ", "test"));
        OrbitVaultItem note = OrbitVaultStore.saveText(context, "Keep", "body", "test");
        assertFalse(OrbitVaultStore.updateText(context, note.id, "Keep", "  "));
        assertEquals("body", OrbitVaultStore.get(context, note.id).body);
    }

    // ---- deletion and media ownership --------------------------------------------------------------

    @Test public void deletingAnImageRemovesThePrivateFileItOwned() {
        OrbitVaultItem image = OrbitVaultStore.saveImage(context, picture(), "Receipt", "Photo");
        File file = new File(image.mediaPath);
        assertTrue(file.isFile());

        assertTrue(OrbitVaultStore.delete(context, image.id));
        assertFalse("the picture must go with the item", file.exists());
        assertNull(OrbitVaultStore.get(context, image.id));
    }

    @Test public void deletingANoteTouchesNoFileAtAll() {
        OrbitVaultItem image = OrbitVaultStore.saveImage(context, picture(), "Kept", "Photo");
        OrbitVaultItem note = OrbitVaultStore.saveText(context, "Note", "words", "test");

        assertTrue(OrbitVaultStore.delete(context, note.id));
        assertTrue("an unrelated picture must be untouched", new File(image.mediaPath).isFile());
        assertNotNull(OrbitVaultStore.get(context, image.id));
    }

    /** The deleter only ever reaches inside the Vault's own directory. */
    @Test public void afileTheVaultDoesNotOwnIsNeverDeleted() throws Exception {
        File outsider = new File(context.getFilesDir(), "orbit_attachments/history/keep-me.jpg");
        assertTrue(outsider.getParentFile().mkdirs() || outsider.getParentFile().isDirectory());
        assertTrue(outsider.createNewFile());

        assertFalse(OrbitVaultMedia.owns(context, outsider.getAbsolutePath()));
        assertFalse(OrbitVaultMedia.delete(context, outsider.getAbsolutePath()));
        assertTrue("a conversation attachment must survive a Vault delete", outsider.isFile());

        write(new OrbitVaultItem("hostile", OrbitVaultItem.TYPE_IMAGE, "Hostile", "", "t",
                outsider.getAbsolutePath(), 5000L, 5000L));
        assertTrue(OrbitVaultStore.delete(context, "hostile"));
        assertTrue("even when a stored row names it", outsider.isFile());
        outsider.delete();
    }

    @Test public void amissingPictureIsShownRatherThanFatal() {
        OrbitVaultItem image = OrbitVaultStore.saveImage(context, picture(), "Gone", "Photo");
        assertTrue(new File(image.mediaPath).delete());

        List<OrbitVaultItem> items = OrbitVaultStore.list(context);
        assertEquals("the item is still listed", 1, items.size());
        assertFalse("its picture is simply gone", new File(items.get(0).mediaPath).exists());
        assertTrue("and it can still be deleted", OrbitVaultStore.delete(context, image.id));
    }

    @Test public void prunedMediaOnlyRemovesWhatNothingRefersTo() {
        OrbitVaultItem kept = OrbitVaultStore.saveImage(context, picture(), "Kept", "Photo");
        String orphan = OrbitVaultMedia.save(context, picture());
        assertTrue(new File(orphan).isFile());

        OrbitVaultStore.pruneUnreferencedMedia(context);
        assertTrue("a referenced picture must survive", new File(kept.mediaPath).isFile());
        assertFalse("an unreferenced one must not", new File(orphan).exists());
    }

    // ---- saving an Orbit answer ----------------------------------------------------------------------

    /**
     * Two deliberate saves are two items.
     *
     * <p>Saving is an act, not a state. Quietly refusing the second would leave the user pressing a
     * control that appears to do nothing at all.
     */
    @Test public void savingTheSameReplyTwiceKeepsBothDeliberateCopies() {
        OrbitVaultItem first = OrbitVaultStore.saveOrbitReply(context, "The same answer.");
        OrbitVaultItem second = OrbitVaultStore.saveOrbitReply(context, "The same answer.");
        assertNotNull(first);
        assertNotNull(second);
        assertFalse(first.id.equals(second.id));
        assertEquals(2, OrbitVaultStore.count(context));
    }

    @Test public void anemptyReplyIsNotSaved() {
        assertNull(OrbitVaultStore.saveOrbitReply(context, "   "));
        assertNull(OrbitVaultStore.saveOrbitReply(context, null));
        assertEquals(0, OrbitVaultStore.count(context));
    }

    // ---- bounds --------------------------------------------------------------------------------------

    @Test public void anenormousBodyIsTrimmedWithANoticeRatherThanRefused() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < OrbitVaultItem.MAX_BODY_CHARS + 5000; i++) huge.append('x');
        OrbitVaultItem saved = OrbitVaultStore.saveText(context, "", huge.toString(), "test");
        assertNotNull(saved);
        assertTrue(saved.body.length() < huge.length());
        assertTrue(saved.body.contains("trimmed"));
    }

    @Test public void theCollectionIsBounded() {
        for (int i = 0; i < OrbitVaultStore.MAX_ITEMS + 20; i++) {
            OrbitVaultStore.saveText(context, "", "note " + i, "test");
        }
        assertEquals(OrbitVaultStore.MAX_ITEMS, OrbitVaultStore.count(context));
        assertEquals("the newest is kept", "note " + (OrbitVaultStore.MAX_ITEMS + 19),
                OrbitVaultStore.list(context).get(0).body);
    }

    // ---- titles derived on this device ------------------------------------------------------------

    @Test public void ausefulTitleIsWorkedOutLocallyWhenNoneIsGiven() {
        assertEquals("Sourdough starter",
                OrbitVaultItem.autoTitle(OrbitVaultItem.TYPE_TEXT, "Sourdough starter\nFeed daily."));
        assertEquals("markdown chrome is not a title", "Battery health",
                OrbitVaultItem.autoTitle(OrbitVaultItem.TYPE_ORBIT_REPLY, "## Battery health\n\nIt..."));
        assertEquals("example.com",
                OrbitVaultItem.autoTitle(OrbitVaultItem.TYPE_LINK, "https://www.example.com/a/b"));
        assertEquals("", OrbitVaultItem.autoTitle(OrbitVaultItem.TYPE_TEXT, "   "));
    }

    @Test public void anitemWithNoTitleStillHasSomethingToShow() {
        OrbitVaultItem image = OrbitVaultStore.saveImage(context, picture(), "", "Photo");
        assertEquals("Image", image.displayTitle());
        OrbitVaultItem note = OrbitVaultStore.saveText(context, "", "Just this line", "test");
        assertEquals("Just this line", note.displayTitle());
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** Writes exact rows, so a test can control timestamps the clock would otherwise decide. */
    private void write(OrbitVaultItem... items) {
        org.json.JSONArray array = new org.json.JSONArray();
        for (OrbitVaultItem item : items) {
            try { array.put(item.toJson()); } catch (Exception e) { throw new AssertionError(e); }
        }
        assertTrue(OrbitVaultStore.prefs(context).edit()
                .putString("items_v1", array.toString()).commit());
    }
}
