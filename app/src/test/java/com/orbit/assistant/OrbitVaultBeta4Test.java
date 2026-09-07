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
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Orbit Vault Beta 4: the collection, once it is big enough to need looking through.
 *
 * <p>Beta 3's claim was that saving belongs wherever the user already is, and it delivered enough
 * new routes that a flat newest-first list stops being the whole answer. Beta 4's claim is much
 * narrower and has to be proved narrowly: the Vault becomes easier to use because it holds more
 * things, and it does not become a filing system while doing it.
 *
 * <p>Three properties carry the weight here, and each one is a way this could have gone wrong.
 *
 * <p>The first is that filters are decided on <em>canonical values</em>. A filter matched against
 * the words a card happens to draw would work perfectly today and empty itself silently the first
 * time somebody reworded "Orbit answer"; a source filter matched against arbitrary strings would be
 * a durable feature steered by whatever another app claimed. Both are asserted directly.
 *
 * <p>The second is that filtering, searching and sorting <em>compose</em> rather than override. Four
 * separate rules would drift apart, and the way a user notices is a filter that appears to have
 * lost their things.
 *
 * <p>The third is that a pin costs nothing. It is one optional boolean, absent from every earlier
 * document, that never moves a timestamp, never touches media ownership, and never reaches a
 * provider. A pinned Vault must still be the same Vault.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class OrbitVaultBeta4Test {

    private Context context;
    private int nextUri;

    @Before public void setUp() {
        if (java.security.Security.getProvider("AndroidKeyStore") == null) {
            java.security.Security.addProvider(new FakeKeyStoreProvider());
        }
        context = RuntimeEnvironment.getApplication();
        OrbitVaultStore.prefs(context).edit().clear().commit();
        Prefs.get(context).edit().clear().commit();
        ConversationStore.clear(context);
        File media = OrbitVaultMedia.directory(context);
        File[] files = media.listFiles();
        if (files != null) for (File file : files) file.delete();
    }

    @org.junit.After public void tearDown() {
        java.security.Security.removeProvider("AndroidKeyStore");
    }

    /** The same empty stand-in {@link OrbitVaultBackupTest} documents; restore needs a provider. */
    private static final class FakeKeyStoreProvider extends java.security.Provider {
        FakeKeyStoreProvider() {
            super("AndroidKeyStore", 1.0, "Test-only empty AndroidKeyStore");
            put("KeyStore.AndroidKeyStore", OrbitVaultBackupTest.FakeAndroidKeyStore.class.getName());
        }
    }

    // ---- a collection worth filtering ------------------------------------------------------------

    private Bitmap picture() {
        Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.BLUE);
        return bitmap;
    }

    /** One of every kind, from a different door each time. */
    private void seed() {
        OrbitVaultStore.saveText(context, "Packing list", "Charger and passport",
                OrbitVaultSource.QUICK_CAPTURE);
        OrbitVaultStore.saveLink(context, "Peak sourdough", "https://example.com/peak",
                OrbitVaultSource.SHARED);
        OrbitVaultStore.saveImage(context, picture(), "Peak crop",
                OrbitVaultSource.SCREEN_SELECTION);
        OrbitVaultStore.saveOrbitReply(context, "Feed it twice a day.");
        OrbitVaultStore.saveDocumentPage(context, "Health behavior theory", 6, 388,
                "Self-efficacy is the belief in one's capacity to act.", picture(), "");
        OrbitVaultStore.saveText(context, "Selected paragraph", "A paragraph about peak demand",
                OrbitVaultSource.SELECTED_TEXT);
    }

    private List<OrbitVaultItem> browse(OrbitVaultFilter filter) {
        return OrbitVaultStore.browse(context, filter, OrbitVaultStore.Sort.NEWEST);
    }

    private static OrbitVaultFilter typed(OrbitVaultFilter.Type type) {
        return OrbitVaultFilter.NONE.withType(type);
    }

    // ---- type filters ----------------------------------------------------------------------------

    @Test public void allIsTheDefaultAndHidesNothing() {
        seed();
        assertEquals(OrbitVaultFilter.Type.ALL, OrbitVaultFilter.NONE.type);
        assertEquals(OrbitVaultFilter.Type.ALL, Prefs.vaultFilter(context).type);
        assertFalse(OrbitVaultFilter.NONE.isNarrowed());
        assertEquals(6, browse(OrbitVaultFilter.NONE).size());
    }

    @Test public void eachTypeFilterReturnsExactlyThatKind() {
        seed();
        for (OrbitVaultItem item : browse(typed(OrbitVaultFilter.Type.TEXT))) {
            assertTrue(item.isText());
        }
        assertEquals(2, browse(typed(OrbitVaultFilter.Type.TEXT)).size());

        assertEquals(1, browse(typed(OrbitVaultFilter.Type.LINKS)).size());
        assertTrue(browse(typed(OrbitVaultFilter.Type.LINKS)).get(0).isLink());

        assertEquals(1, browse(typed(OrbitVaultFilter.Type.IMAGES)).size());
        assertTrue(browse(typed(OrbitVaultFilter.Type.IMAGES)).get(0).isImage());

        assertEquals(1, browse(typed(OrbitVaultFilter.Type.DOCUMENTS)).size());
        assertTrue(browse(typed(OrbitVaultFilter.Type.DOCUMENTS)).get(0).isDocumentPage());

        assertEquals(1, browse(typed(OrbitVaultFilter.Type.ORBIT)).size());
        assertTrue(browse(typed(OrbitVaultFilter.Type.ORBIT)).get(0).isOrbitReply());
    }

    /**
     * The filter reads the stored type, not the drawn word.
     *
     * <p>A filter written against {@code typeLabel()} would pass every test above and break the
     * moment somebody reworded a label, which is exactly the kind of change nobody reruns the Vault
     * suite for. This asserts the dependency directly.
     */
    @Test public void typeFiltersAreDecidedOnCanonicalTypesRatherThanLabels() {
        String filter = source("OrbitVaultFilter");
        assertFalse("a type filter must never parse a display string",
                filter.contains("typeLabel"));
        assertTrue(filter.contains("isText()"));
        assertTrue(filter.contains("isLink()"));
        assertTrue(filter.contains("isImage()"));
        assertTrue(filter.contains("isDocumentPage()"));
        assertTrue(filter.contains("isOrbitReply()"));

        // And every stored type is reachable, so a type cannot become invisible by having no chip.
        seed();
        int reachable = 0;
        for (OrbitVaultFilter.Type type : OrbitVaultFilter.Type.values()) {
            if (type != OrbitVaultFilter.Type.ALL) reachable += browse(typed(type)).size();
        }
        assertEquals("every saved item is reachable through exactly one type filter",
                OrbitVaultStore.count(context), reachable);
    }

    // ---- source filters ---------------------------------------------------------------------------

    @Test public void sourceFiltersUseOrbitsOwnVocabulary() {
        seed();
        assertEquals(1, browse(
                OrbitVaultFilter.NONE.withSource(OrbitVaultSource.SCREEN_SELECTION)).size());
        assertEquals(1, browse(
                OrbitVaultFilter.NONE.withSource(OrbitVaultSource.SELECTED_TEXT)).size());
        assertEquals(1, browse(
                OrbitVaultFilter.NONE.withSource(OrbitVaultSource.QUICK_CAPTURE)).size());
        assertEquals(1, browse(OrbitVaultFilter.NONE.withSource(OrbitVaultSource.SHARED)).size());
        assertEquals(1, browse(
                OrbitVaultFilter.NONE.withSource(OrbitVaultSource.ORBIT_REPLY)).size());
    }

    /** A saved page carries its page number in its source, and still filters as a document. */
    @Test public void aSavedPageFiltersUnderDocumentWhateverPageItWas() {
        seed();
        OrbitVaultItem page = browse(typed(OrbitVaultFilter.Type.DOCUMENTS)).get(0);
        assertEquals(OrbitVaultSource.documentPage(7), page.source);
        assertEquals(OrbitVaultSource.DOCUMENT, OrbitVaultSource.family(page.source));
        assertEquals(1, browse(OrbitVaultFilter.NONE.withSource(OrbitVaultSource.DOCUMENT)).size());
    }

    /**
     * A source Orbit did not write is not a filter.
     *
     * <p>The whole reason the source vocabulary is closed is that a share target is handed whatever
     * the sender chose to claim. A restored backup may legitimately carry an older label, so such
     * an item keeps its place in the Vault - it simply cannot be turned into a filter.
     */
    @Test public void anArbitrarySourceCanNeverBecomeAFilter() {
        assertEquals("", OrbitVaultSource.family("Shared from Chrome"));
        assertEquals("", OrbitVaultSource.family("Document · Page seven"));
        assertEquals("", OrbitVaultSource.family(null));
        assertEquals("", OrbitVaultFilter.NONE.withSource("Shared from Chrome").source);
        assertFalse(OrbitVaultFilter.NONE.withSource("Shared from Chrome").hasTypeOrSource());
    }

    /** Only the sources this Vault actually holds are offered, in Orbit's own order. */
    @Test public void onlyPresentSourcesAreOffered() {
        seed();
        List<String> present = OrbitVaultStore.sourcesPresent(context);
        assertTrue(present.contains(OrbitVaultSource.QUICK_CAPTURE));
        assertTrue(present.contains(OrbitVaultSource.DOCUMENT));
        assertFalse("nothing was saved from the clipboard",
                present.contains(OrbitVaultSource.CLIPBOARD));
        assertFalse("nor from the gallery", present.contains(OrbitVaultSource.PHOTO));

        int previous = -1;
        for (String source : present) {
            int at = indexIn(OrbitVaultSource.FILTERABLE, source);
            assertTrue("sources are offered in Orbit's own order", at > previous);
            previous = at;
        }
    }

    private static int indexIn(String[] values, String value) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i;
        return -1;
    }

    // ---- filters, search and sort compose ----------------------------------------------------------

    @Test public void typeAndSourceCombine() {
        seed();
        OrbitVaultFilter both = typed(OrbitVaultFilter.Type.IMAGES)
                .withSource(OrbitVaultSource.SCREEN_SELECTION);
        assertEquals(1, browse(both).size());
        assertTrue(browse(both).get(0).isImage());

        // The same source with a type it cannot be is empty rather than ignored.
        assertEquals(0, browse(typed(OrbitVaultFilter.Type.LINKS)
                .withSource(OrbitVaultSource.SCREEN_SELECTION)).size());
    }

    @Test public void searchCombinesWithAType() {
        seed();
        assertEquals(3, browse(OrbitVaultFilter.NONE.withQuery("peak")).size());
        List<OrbitVaultItem> links = browse(typed(OrbitVaultFilter.Type.LINKS).withQuery("peak"));
        assertEquals(1, links.size());
        assertTrue(links.get(0).isLink());
        assertEquals(0, browse(typed(OrbitVaultFilter.Type.ORBIT).withQuery("peak")).size());
    }

    @Test public void searchCombinesWithASource() {
        seed();
        assertEquals(1, browse(OrbitVaultFilter.NONE
                .withSource(OrbitVaultSource.SELECTED_TEXT).withQuery("peak")).size());
        assertEquals(0, browse(OrbitVaultFilter.NONE
                .withSource(OrbitVaultSource.SHARED).withQuery("passport")).size());
    }

    @Test public void searchTypeAndSourceCombineAtOnce() {
        seed();
        OrbitVaultFilter all = typed(OrbitVaultFilter.Type.TEXT)
                .withSource(OrbitVaultSource.SELECTED_TEXT)
                .withQuery("peak");
        assertEquals(1, browse(all).size());
        assertEquals("A paragraph about peak demand", browse(all).get(0).body);

        assertEquals("and one wrong part is enough to exclude it", 0,
                browse(all.withSource(OrbitVaultSource.QUICK_CAPTURE)).size());
    }

    @Test public void sortStillAppliesInsideAFilter() {
        seed();
        OrbitVaultFilter text = typed(OrbitVaultFilter.Type.TEXT);
        List<OrbitVaultItem> newest =
                OrbitVaultStore.browse(context, text, OrbitVaultStore.Sort.NEWEST);
        List<OrbitVaultItem> oldest =
                OrbitVaultStore.browse(context, text, OrbitVaultStore.Sort.OLDEST);
        assertEquals(2, newest.size());
        assertEquals(2, oldest.size());
        assertEquals(newest.get(0).id, oldest.get(1).id);
        assertEquals(newest.get(1).id, oldest.get(0).id);
    }

    /** Local, and only local: no provider, no index, no network anywhere in the filter path. */
    @Test public void filteringNeverReachesAProvider() {
        for (String name : new String[]{"OrbitVaultFilter", "OrbitVaultStore", "OrbitVaultItem",
                "OrbitVaultSource"}) {
            String text = source(name);
            for (String forbidden : new String[]{"AssistantClient", "ChatGptClient", "AutoRouter",
                    // Asserted on API names rather than on English: the javadoc in these files
                    // legitimately promises there is no embedding and no provider, and a test that
                    // searched for those words would fail on the promise itself.
                    "OrbitRequestManager", "PendingRequestStore", "HttpURLConnection", "java.net.",
                    "MemoryStore", "TextRecognizer", "ExecutorService"}) {
                assertFalse(name + " must not reference " + forbidden, text.contains(forbidden));
            }
        }
    }

    // ---- the screen ---------------------------------------------------------------------------------

    private Activity vaultScreen() {
        return Robolectric.buildActivity(OrbitVaultActivity.class).setup().get();
    }

    @Test public void theChipRowOffersEveryTypeAndDefaultsToAll() {
        seed();
        List<String> drawn = textOf(vaultScreen().getWindow().getDecorView());
        for (OrbitVaultFilter.Type type : OrbitVaultFilter.Type.values()) {
            assertTrue("the chip row must offer " + type.label, drawn.contains(type.label));
        }
        View all = findClickableWithText(vaultScreen().getWindow().getDecorView(), "All");
        assertNotNull(all);
        assertTrue("All is the selected chip on a fresh Vault",
                String.valueOf(all.getContentDescription()).endsWith(", selected"));
    }

    @Test public void tappingATypeChipNarrowsTheListAndIsRemembered() {
        seed();
        Activity screen = vaultScreen();
        View links = findClickableWithText(screen.getWindow().getDecorView(), "Links");
        assertNotNull(links);
        links.performClick();

        assertEquals(OrbitVaultFilter.Type.LINKS, Prefs.vaultFilter(context).type);
        List<String> drawn = textOf(screen.getWindow().getDecorView());
        assertTrue("the one link is still shown", drawn.contains("Peak sourdough"));
        assertFalse("and the note is not", drawn.contains("Packing list"));
        assertTrue("the header says how much of the Vault is showing",
                joined(drawn).contains("1 of 6 saved items"));
    }

    /** The clear control appears only when something is hidden, and puts everything back. */
    @Test public void clearFiltersReturnsTheWholeVault() {
        seed();
        Activity screen = vaultScreen();
        assertNull("nothing is hidden yet, so nothing offers to unhide it",
                findClickableWithText(screen.getWindow().getDecorView(),
                        OrbitVaultActivity.CLEAR_FILTERS));

        findClickableWithText(screen.getWindow().getDecorView(), "Images").performClick();
        View clear = findClickableWithText(screen.getWindow().getDecorView(),
                OrbitVaultActivity.CLEAR_FILTERS);
        assertNotNull("a narrowed Vault must offer one way back", clear);
        clear.performClick();

        assertEquals(OrbitVaultFilter.Type.ALL, Prefs.vaultFilter(context).type);
        assertEquals("", Prefs.vaultFilter(context).source);
        assertTrue(textOf(screen.getWindow().getDecorView()).contains("Packing list"));
    }

    /** Search text is cleared by the same control, because the promise is the whole Vault. */
    @Test public void clearFiltersAlsoClearsTheSearchField() {
        seed();
        Activity screen = vaultScreen();
        EditText search = findSearchField(screen.getWindow().getDecorView());
        assertNotNull(search);
        search.setText("peak");
        findClickableWithText(screen.getWindow().getDecorView(), "Links").performClick();

        findClickableWithText(screen.getWindow().getDecorView(),
                OrbitVaultActivity.CLEAR_FILTERS).performClick();
        assertEquals("", search.getText().toString());
        assertTrue(textOf(screen.getWindow().getDecorView()).contains("Packing list"));
    }

    // ---- empty states -------------------------------------------------------------------------------

    @Test public void anEmptyVaultStillSaysItIsEmpty() {
        List<String> drawn = textOf(vaultScreen().getWindow().getDecorView());
        assertTrue(drawn.contains(OrbitVaultActivity.EMPTY_TITLE));
    }

    /** A Vault with things in it never claims to be empty, whatever the filter found. */
    @Test public void aFilterWithNoMatchesSaysSoWithoutClaimingTheVaultIsEmpty() {
        OrbitVaultStore.saveText(context, "Packing list", "Charger",
                OrbitVaultSource.QUICK_CAPTURE);
        Activity screen = vaultScreen();
        findClickableWithText(screen.getWindow().getDecorView(), "Images").performClick();

        String drawn = joined(textOf(screen.getWindow().getDecorView()));
        assertFalse("the Vault is not empty and must not say it is",
                drawn.contains(OrbitVaultActivity.EMPTY_TITLE));
        assertTrue(drawn.contains(OrbitVaultActivity.NO_RESULTS_TITLE));
        assertTrue("it must say it was the filter", drawn.contains(OrbitVaultActivity.NO_RESULTS_FILTER));
    }

    @Test public void aSearchWithNoMatchesSaysItWasTheSearch() {
        seed();
        Activity screen = vaultScreen();
        findSearchField(screen.getWindow().getDecorView()).setText("zzzznothing");

        String drawn = joined(textOf(screen.getWindow().getDecorView()));
        assertFalse(drawn.contains(OrbitVaultActivity.EMPTY_TITLE));
        assertTrue(drawn.contains(OrbitVaultActivity.NO_RESULTS_TITLE));
        assertTrue(drawn.contains(OrbitVaultActivity.NO_RESULTS_SEARCH));
    }

    @Test public void aSearchInsideAFilterSaysBothAreNarrowing() {
        seed();
        Activity screen = vaultScreen();
        findClickableWithText(screen.getWindow().getDecorView(), "Images").performClick();
        findSearchField(screen.getWindow().getDecorView()).setText("passport");

        String drawn = joined(textOf(screen.getWindow().getDecorView()));
        assertFalse(drawn.contains(OrbitVaultActivity.EMPTY_TITLE));
        assertTrue(drawn.contains(OrbitVaultActivity.NO_RESULTS_BOTH));
    }

    // ---- pinning ------------------------------------------------------------------------------------

    @Test public void everythingSavedBeforeBetaFourLoadsUnpinned() {
        String betaThree = "[{\"id\":\"a\",\"type\":\"link\",\"title\":\"Recipe\","
                + "\"body\":\"https://example.com/r\",\"source\":\"Shared to Orbit\","
                + "\"note\":\"try this\",\"mediaPath\":\"\","
                + "\"createdAt\":1600000000000,\"modifiedAt\":1600000000000},"
                + "{\"id\":\"b\",\"type\":\"document_page\",\"title\":\"\",\"body\":\"page text\","
                + "\"source\":\"Document · Page 7\",\"note\":\"\",\"mediaPath\":\"\","
                + "\"documentName\":\"Health behavior theory\",\"pageIndex\":6,\"pageCount\":388,"
                + "\"createdAt\":1600000000001,\"modifiedAt\":1600000000001}]";
        OrbitVaultStore.prefs(context).edit().putString("items_v1", betaThree).commit();

        assertEquals(2, OrbitVaultStore.count(context));
        for (OrbitVaultItem item : OrbitVaultStore.list(context)) {
            assertFalse(item.id + " must load unpinned", item.pinned);
        }
        // And nothing else about them moved.
        OrbitVaultItem link = OrbitVaultStore.get(context, "a");
        assertEquals("try this", link.note);
        assertEquals(1600000000000L, link.createdAt);
        OrbitVaultItem page = OrbitVaultStore.get(context, "b");
        assertEquals("Health behavior theory", page.documentName);
        assertEquals(7, page.pageNumber());
    }

    @Test public void pinningAndUnpinningPersist() {
        OrbitVaultItem item = OrbitVaultStore.saveText(context, "Packing list", "Charger",
                OrbitVaultSource.QUICK_CAPTURE);
        assertFalse(item.pinned);

        assertTrue(OrbitVaultStore.setPinned(context, item.id, true));
        assertTrue(OrbitVaultStore.get(context, item.id).pinned);
        assertEquals(1, OrbitVaultStore.pinnedCount(context));

        assertTrue(OrbitVaultStore.setPinned(context, item.id, false));
        assertFalse(OrbitVaultStore.get(context, item.id).pinned);
        assertEquals(0, OrbitVaultStore.pinnedCount(context));
    }

    /**
     * A pin is not an edit.
     *
     * <p>Stamping {@code modifiedAt} would reorder an Oldest-first Vault and make every pinned item
     * claim on its own screen to have been edited on the day it was pinned.
     */
    @Test public void pinningChangesNothingElseAboutAnItem() {
        OrbitVaultItem page = OrbitVaultStore.saveDocumentPage(context, "Health behavior theory",
                6, 388, "Self-efficacy.", picture(), "read again");
        assertNotNull(page);
        OrbitVaultStore.setPinned(context, page.id, true);

        OrbitVaultItem after = OrbitVaultStore.get(context, page.id);
        assertTrue(after.pinned);
        assertEquals(page.title, after.title);
        assertEquals(page.body, after.body);
        assertEquals(page.note, after.note);
        assertEquals(page.source, after.source);
        assertEquals(page.documentName, after.documentName);
        assertEquals(page.pageIndex, after.pageIndex);
        assertEquals(page.pageCount, after.pageCount);
        assertEquals(page.mediaPath, after.mediaPath);
        assertEquals(page.createdAt, after.createdAt);
        assertEquals("a pin must never move the edited time", page.modifiedAt, after.modifiedAt);
        assertEquals("and never claims an edit happened", "", after.modifiedLabel());
    }

    /** Pinning is a property of the item, so the picture it owns is untouched either way. */
    @Test public void pinningNeverTouchesMediaOwnership() {
        OrbitVaultItem image = OrbitVaultStore.saveImage(context, picture(), "Crop",
                OrbitVaultSource.SCREEN_SELECTION);
        assertNotNull(image);
        int before = mediaFileCount();

        OrbitVaultStore.setPinned(context, image.id, true);
        assertEquals("no file is copied", before, mediaFileCount());
        assertEquals("and none is moved", image.mediaPath,
                OrbitVaultStore.get(context, image.id).mediaPath);
        assertTrue(new File(image.mediaPath).exists());

        OrbitVaultStore.setPinned(context, image.id, false);
        assertEquals(before, mediaFileCount());
        assertTrue(new File(image.mediaPath).exists());

        // And deletion still removes exactly one item's own picture.
        OrbitVaultStore.setPinned(context, image.id, true);
        OrbitVaultStore.delete(context, image.id);
        assertFalse(new File(image.mediaPath).exists());
    }

    @Test public void changingSortDoesNotDisturbPinState() {
        seed();
        OrbitVaultItem link = browse(typed(OrbitVaultFilter.Type.LINKS)).get(0);
        OrbitVaultStore.setPinned(context, link.id, true);

        OrbitVaultStore.browse(context, OrbitVaultFilter.NONE, OrbitVaultStore.Sort.OLDEST);
        Prefs.setVaultSort(context, OrbitVaultStore.Sort.OLDEST);
        assertTrue(OrbitVaultStore.get(context, link.id).pinned);
        assertEquals(1, OrbitVaultStore.pinnedCount(context));
    }

    // ---- the pinned section --------------------------------------------------------------------------

    @Test public void pinnedItemsGetTheirOwnSectionAndAppearOnlyOnce() {
        seed();
        OrbitVaultItem link = browse(typed(OrbitVaultFilter.Type.LINKS)).get(0);
        OrbitVaultStore.setPinned(context, link.id, true);

        List<String> drawn = textOf(vaultScreen().getWindow().getDecorView());
        assertTrue(drawn.contains(OrbitVaultActivity.PINNED_HEADING));
        assertTrue(drawn.contains(OrbitVaultActivity.OTHERS_HEADING));

        int appearances = 0;
        for (String line : drawn) if ("Peak sourdough".equals(line)) appearances++;
        assertEquals("a pinned item is in exactly one of the two sections", 1, appearances);
    }

    @Test public void thereIsNoPinnedHeadingUntilSomethingIsPinned() {
        seed();
        List<String> drawn = textOf(vaultScreen().getWindow().getDecorView());
        assertFalse(drawn.contains(OrbitVaultActivity.PINNED_HEADING));
        assertFalse(drawn.contains(OrbitVaultActivity.OTHERS_HEADING));
    }

    /**
     * The pinned section is drawn from the filtered list, not from the whole Vault.
     *
     * <p>Otherwise pinning would quietly become a way to escape filtering, and somebody looking at
     * their saved documents would find a pinned link at the top of the page.
     */
    @Test public void pinnedItemsStillRespectTheActiveFilter() {
        seed();
        OrbitVaultItem link = browse(typed(OrbitVaultFilter.Type.LINKS)).get(0);
        OrbitVaultStore.setPinned(context, link.id, true);

        Activity screen = vaultScreen();
        findClickableWithText(screen.getWindow().getDecorView(), "Documents").performClick();
        List<String> drawn = textOf(screen.getWindow().getDecorView());
        assertFalse("a pinned link is not a document", drawn.contains("Peak sourdough"));
        assertFalse("so there is no pinned section here",
                drawn.contains(OrbitVaultActivity.PINNED_HEADING));
        assertTrue(joined(drawn).contains("Health behavior theory"));
    }

    /** Pinning is reachable from the list without opening the item. */
    @Test public void theListMenuOffersPinAndThenUnpin() {
        String menu = source("OrbitVaultActivity");
        assertTrue(menu.contains("ACTION_PIN"));
        assertTrue(menu.contains("ACTION_UNPIN"));
        assertTrue("it uses Orbit's own pin glyph", menu.contains("R.drawable.ic_pin"));
        assertTrue("through the store, never by rewriting the item",
                menu.contains("OrbitVaultStore.setPinned"));
        assertFalse("and there is no permanent pin button on every card",
                menu.contains("pinButton"));
    }

    /** And from the item screen, where it sits beside the item's name. */
    @Test public void theItemScreenOffersPinning() {
        OrbitVaultItem item = OrbitVaultStore.saveText(context, "Packing list", "Charger",
                OrbitVaultSource.QUICK_CAPTURE);
        Activity screen = itemScreen(item);
        View pin = findClickableWithDescription(screen.getWindow().getDecorView(),
                OrbitVaultItemActivity.ACTION_PIN + " this saved item");
        assertNotNull("an unpinned item offers to pin it", pin);
        pin.performClick();
        assertTrue(OrbitVaultStore.get(context, item.id).pinned);

        Activity reopened = itemScreen(OrbitVaultStore.get(context, item.id));
        assertNotNull("and a pinned one offers to unpin it",
                findClickableWithDescription(reopened.getWindow().getDecorView(),
                        OrbitVaultItemActivity.ACTION_UNPIN + " this saved item"));
    }

    // ---- state kept across opening an item ------------------------------------------------------------

    /**
     * Coming back from a saved item must not throw away the narrowing that found it.
     *
     * <p>Asserted through the preference the screen actually reads, rather than by driving an
     * Activity result: the filter is durable state, so returning to the Vault - or to the whole app
     * tomorrow - finds the same question still asked.
     */
    @Test public void theFilterSurvivesOpeningAnItemAndComingBack() {
        seed();
        Activity screen = vaultScreen();
        findClickableWithText(screen.getWindow().getDecorView(), "Documents").performClick();
        assertEquals(OrbitVaultFilter.Type.DOCUMENTS, Prefs.vaultFilter(context).type);

        OrbitVaultItem page = browse(typed(OrbitVaultFilter.Type.DOCUMENTS)).get(0);
        itemScreen(page);

        Activity returned = vaultScreen();
        assertEquals("the type filter is still in force",
                OrbitVaultFilter.Type.DOCUMENTS, Prefs.vaultFilter(context).type);
        List<String> drawn = textOf(returned.getWindow().getDecorView());
        assertFalse(drawn.contains("Packing list"));
    }

    @Test public void aWithdrawnSourceFilterDegradesToEverything() {
        Prefs.get(context).edit()
                .putString(Prefs.VAULT_SOURCE_FILTER, "Some future source")
                .putString(Prefs.VAULT_TYPE_FILTER, "sideways")
                .commit();
        OrbitVaultFilter filter = Prefs.vaultFilter(context);
        assertEquals(OrbitVaultFilter.Type.ALL, filter.type);
        assertEquals("", filter.source);
        assertFalse(filter.isNarrowed());
    }

    // ---- Backup & Restore -------------------------------------------------------------------------------

    @Test public void pinStateSurvivesABackupRoundTrip() throws Exception {
        OrbitVaultItem pinned = OrbitVaultStore.saveText(context, "Packing list", "Charger",
                OrbitVaultSource.QUICK_CAPTURE);
        OrbitVaultItem plain = OrbitVaultStore.saveLink(context, "Recipe",
                "https://example.com/r", OrbitVaultSource.SHARED);
        OrbitVaultStore.setPinned(context, pinned.id, true);

        String backup = exported();
        OrbitVaultStore.prefs(context).edit().clear().commit();
        assertEquals(0, OrbitVaultStore.count(context));

        OrbitBackupManager.restore(context, prepare(backup));

        assertEquals(2, OrbitVaultStore.count(context));
        assertTrue("the pin travelled", OrbitVaultStore.get(context, pinned.id).pinned);
        assertFalse("and an unpinned item stayed unpinned",
                OrbitVaultStore.get(context, plain.id).pinned);
    }

    /** A backup written before Beta 4 has no such key, and every item in it restores unpinned. */
    @Test public void anOlderBackupRestoresEverythingUnpinned() throws Exception {
        OrbitVaultStore.saveText(context, "Packing list", "Charger", OrbitVaultSource.QUICK_CAPTURE);
        String backup = exported().replace("\"pinned\":true", "\"pinned\":false");
        assertFalse("this backup carries no pin at all", backup.contains("\"pinned\":true"));

        OrbitVaultStore.prefs(context).edit().clear().commit();
        OrbitBackupManager.restore(context, prepare(backup));

        assertEquals(1, OrbitVaultStore.count(context));
        assertFalse(OrbitVaultStore.list(context).get(0).pinned);
    }

    /** An unpinned Vault writes exactly the document Beta 3 wrote. */
    @Test public void anUnpinnedVaultCarriesNoPinKeyOnDisk() {
        OrbitVaultStore.saveText(context, "Packing list", "Charger", OrbitVaultSource.QUICK_CAPTURE);
        assertFalse(OrbitVaultStore.prefs(context).getString("items_v1", "").contains("pinned"));

        OrbitVaultItem item = OrbitVaultStore.list(context).get(0);
        OrbitVaultStore.setPinned(context, item.id, true);
        assertTrue(OrbitVaultStore.prefs(context).getString("items_v1", "").contains("\"pinned\":true"));
    }

    // ---- Screen Selection ----------------------------------------------------------------------------------

    private Activity screenSelectionEditor() {
        String path = ScreenSelectionStore.saveSource(context, picture());
        assertFalse(path.isEmpty());
        Intent intent = ScreenSelectionStore.editorIntent(context, path, "", "", "", null);
        intent.setClass(context, ScreenSelectionActivity.class);
        return Robolectric.buildActivity(ScreenSelectionActivity.class, intent).setup().get();
    }

    private View vaultControl(Activity editor) {
        return findClickableWithDescription(editor.getWindow().getDecorView(),
                "Save this selection to your Vault");
    }

    /**
     * The Vault control no longer owns a band of the screen.
     *
     * <p>Two properties, and the second is the one that actually matters. It shares a parent with
     * Use selection, so it is on that row rather than above it; and its width is a bounded number
     * of pixels rather than the whole row, so the space Beta 3 spent on it has genuinely gone back
     * to the editor above.
     */
    @Test public void theVaultControlSharesTheActionRowRatherThanOwningOne() {
        Activity editor = screenSelectionEditor();
        View vault = vaultControl(editor);
        View use = findClickableWithDescription(editor.getWindow().getDecorView(),
                "Use screen selection");
        View full = findClickableWithDescription(editor.getWindow().getDecorView(),
                "Use full screen");
        assertNotNull(vault);
        assertNotNull(use);
        assertNotNull(full);
        assertTrue("the three actions are one row", vault.getParent() == use.getParent()
                && use.getParent() == full.getParent());

        ViewGroup.LayoutParams lp = vault.getLayoutParams();
        assertFalse("it must not be a full-width row of its own",
                lp.width == ViewGroup.LayoutParams.MATCH_PARENT);
        int expected = Math.round(ScreenSelectionActivity.VAULT_ACTION_WIDTH_DP
                * context.getResources().getDisplayMetrics().density);
        assertEquals("it is a compact fixed-width action", expected, lp.width);

        // And it takes no share of the remaining width, so the two actions beside it keep it all.
        assertEquals(0f, ((LinearLayout.LayoutParams) lp).weight, 0.001f);
        assertTrue("Use selection stays the strongest action",
                ((LinearLayout.LayoutParams) use.getLayoutParams()).weight
                        > ((LinearLayout.LayoutParams) full.getLayoutParams()).weight);
    }

    /** Small, but never so small that it stops saying where the picture is going. */
    @Test public void theCompactVaultControlStillNamesItsDestination() {
        Activity editor = screenSelectionEditor();
        View vault = vaultControl(editor);
        assertTrue(vault instanceof Button);
        assertEquals(ScreenSelectionActivity.SAVE_TO_VAULT, ((Button) vault).getText().toString());
        assertNotNull("with Orbit's own Vault glyph beside the word",
                ((Button) vault).getCompoundDrawablesRelative()[0]);
        assertTrue("and enough room to read it",
                ScreenSelectionActivity.VAULT_ACTION_WIDTH_DP >= 72);
        assertTrue("without taking a third of the row",
                ScreenSelectionActivity.VAULT_ACTION_WIDTH_DP <= 120);
    }

    /** The compact control still runs the one canonical save, unchanged from Beta 3. */
    @Test public void theCompactControlStillSavesTheSelection() {
        Activity editor = screenSelectionEditor();
        assertEquals(0, OrbitVaultStore.count(context));
        vaultControl(editor).performClick();

        List<OrbitVaultItem> saved = OrbitVaultStore.list(context);
        assertEquals(1, saved.size());
        assertTrue(saved.get(0).isImage());
        assertEquals(OrbitVaultSource.SCREEN_SELECTION, saved.get(0).source);
        assertTrue(OrbitVaultMedia.owns(context, saved.get(0).mediaPath));
        assertFalse("the editor stays open", editor.isFinishing());
    }

    /** With the Vault off the control is gone and the two remaining actions are untouched. */
    @Test public void theRowRebalancesWhenTheVaultIsOff() {
        Prefs.get(context).edit().putBoolean(Prefs.VAULT_ENABLED, false).commit();
        Activity editor = screenSelectionEditor();
        View vault = vaultControl(editor);
        assertTrue("the Vault control is gone",
                vault == null || vault.getVisibility() == View.GONE);

        View use = findClickableWithDescription(editor.getWindow().getDecorView(),
                "Use screen selection");
        View full = findClickableWithDescription(editor.getWindow().getDecorView(),
                "Use full screen");
        assertNotNull(use);
        assertNotNull(full);
        assertTrue("they are weighted, so they simply take the width back",
                ((LinearLayout.LayoutParams) use.getLayoutParams()).weight > 0f
                        && ((LinearLayout.LayoutParams) full.getLayoutParams()).weight > 0f);
        String drawn = joined(textOf(editor.getWindow().getDecorView()));
        assertTrue(drawn.contains("Use selection"));
        assertTrue(drawn.contains("Use full screen"));
        assertTrue("and the editor modes are unchanged",
                drawn.contains("Crop") && drawn.contains("Mark up"));
    }

    /** Crop and Mark up remain editor modes, and the Vault never joins them. */
    @Test public void theVaultActionIsNeverAnEditorMode() {
        String editor = source("ScreenSelectionActivity");
        int tools = editor.indexOf("cropButton = toolButton");
        int vault = editor.indexOf("vaultButton = compactVaultButton");
        assertTrue(tools > 0 && vault > tools);
        assertFalse("the Vault is not a tool", editor.contains("toolButton(SAVE_TO_VAULT"));
    }

    // ---- the Vault is still off when it is off ----------------------------------------------------------

    @Test public void aDisabledVaultShowsNoFilterRow() {
        seed();
        Prefs.get(context).edit().putBoolean(Prefs.VAULT_ENABLED, false).commit();
        Activity screen = vaultScreen();
        assertNull(findClickableWithText(screen.getWindow().getDecorView(), "Links"));
        assertTrue(joined(textOf(screen.getWindow().getDecorView()))
                .contains(OrbitVaultActivity.OFF_TITLE));
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private static String source(String simpleName) {
        return ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/" + simpleName + ".java");
    }

    private Activity itemScreen(OrbitVaultItem item) {
        Intent intent = new Intent(context, OrbitVaultItemActivity.class)
                .putExtra(OrbitVaultItemActivity.EXTRA_ITEM_ID, item.id);
        return Robolectric.buildActivity(OrbitVaultItemActivity.class, intent).setup().get();
    }

    private int mediaFileCount() {
        File[] files = OrbitVaultMedia.directory(context).listFiles();
        return files == null ? 0 : files.length;
    }

    private String exported() throws Exception {
        Uri uri = Uri.parse("content://test/backup-" + (nextUri++));
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        org.robolectric.Shadows.shadowOf(context.getContentResolver())
                .registerOutputStream(uri, captured);
        OrbitBackupManager.exportTo(context, uri);
        return captured.toString("UTF-8");
    }

    private OrbitBackupManager.PreparedRestore prepare(String backup) throws Exception {
        Uri uri = Uri.parse("content://test/restore-" + (nextUri++));
        org.robolectric.Shadows.shadowOf(context.getContentResolver())
                .registerInputStream(uri, new ByteArrayInputStream(
                        backup.getBytes(StandardCharsets.UTF_8)));
        return OrbitBackupManager.prepareRestore(context, uri);
    }

    private static String joined(List<String> lines) {
        return String.join("\n", lines);
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

    private static EditText findSearchField(View view) {
        if (view instanceof EditText) {
            CharSequence hint = ((EditText) view).getHint();
            if (hint != null && "Search your Vault".contentEquals(hint)) return (EditText) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText found = findSearchField(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View findClickableWithText(View view, String text) {
        if (view.isClickable() && view instanceof TextView) {
            CharSequence actual = ((TextView) view).getText();
            if (actual != null && text.contentEquals(actual)) return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findClickableWithText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View findClickableWithDescription(View view, String description) {
        if (view.isClickable()) {
            CharSequence actual = view.getContentDescription();
            if (actual != null && description.contentEquals(actual)) return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findClickableWithDescription(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }
}
