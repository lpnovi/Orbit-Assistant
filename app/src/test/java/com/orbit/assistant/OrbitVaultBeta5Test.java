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
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
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
 * Orbit Vault Beta 5: the same organization model, finally operable.
 *
 * <p>Beta 4 shipped filters that were correct and a screen that was not. Everything asserted here
 * is a defect real-device testing found rather than a new capability, so the suite is shaped around
 * the four ways the fix could go wrong.
 *
 * <p>The first is that the horizontal chip strip could survive in some form. A "compact" filter bar
 * that still needs a sideways drag to reach Source has not fixed anything, so the absence of a
 * scroller is asserted directly rather than inferred from the presence of the selectors.
 *
 * <p>The second is independence. Turning a type filter off used to mean reaching for Clear filters,
 * which also discarded the source and the typed search. Every combination of "change one, keep the
 * others" is asserted, because a single {@code withX} written against the wrong constructor
 * argument would silently reintroduce exactly that.
 *
 * <p>The third is that clearer wording could become a migration. "Orbit Documents" and "Orbit
 * answers" are labels a screen draws; the stored source string, the stored type id and the
 * "Document · Page 7" provenance line under a saved page must all be untouched, and a Beta 1 to
 * Beta 4 store must load without noticing.
 *
 * <p>The fourth is deletion. A Vault item can own a private picture, so an Undo that rebuilt the
 * item from a snapshot would have to re-adopt that file and could lose a field. The property that
 * makes this safe is that nothing is removed at all while the offer stands, which is asserted on
 * the file system rather than on the store alone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class OrbitVaultBeta5Test {

    private Context context;
    private int nextUri;
    private ActivityController<OrbitVaultActivity> vault;

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

    private Activity vaultScreen() {
        vault = Robolectric.buildActivity(OrbitVaultActivity.class).setup();
        return vault.get();
    }

    private View typeSelector(Activity screen) {
        return findByDescriptionPrefix(screen.getWindow().getDecorView(),
                OrbitVaultActivity.TYPE_QUESTION + ": ");
    }

    private View sourceSelector(Activity screen) {
        return findByDescriptionPrefix(screen.getWindow().getDecorView(),
                OrbitVaultActivity.SOURCE_QUESTION + ": ");
    }

    // ---- the strip is gone -----------------------------------------------------------------------

    /**
     * No part of the Vault's filtering requires a horizontal drag.
     *
     * <p>Asserted on the whole page rather than on the filter area, because the failure this
     * prevents is somebody putting the selectors in a scroller "just in case a label is long". A
     * long label is what {@code ellipsize} is for.
     */
    @Test public void theVaultHasNoHorizontalFilterScroller() {
        seed();
        assertNull("filtering must never need a sideways drag",
                findHorizontalScroller(vaultScreen().getWindow().getDecorView()));
        assertFalse("and the screen must not build one",
                source("OrbitVaultActivity").contains("HorizontalScrollView"));
    }

    /** Both questions are on screen at once, on a fresh Vault, before anything is chosen. */
    @Test public void bothSelectorsAreVisibleWithoutScrollingAndShowTheirDefaults() {
        seed();
        Activity screen = vaultScreen();
        View type = typeSelector(screen);
        View from = sourceSelector(screen);
        assertNotNull("the Type selector must be immediately visible", type);
        assertNotNull("and so must the Saved from selector", from);
        assertTrue("they are one row", type.getParent() == from.getParent());

        List<String> drawn = textOf(screen.getWindow().getDecorView());
        assertTrue("Type rests at All items", drawn.contains(OrbitVaultFilter.Type.ALL.label));
        assertEquals("All items", OrbitVaultFilter.Type.ALL.label);
        assertTrue("and Saved from rests at Any source",
                drawn.contains(OrbitVaultActivity.SOURCE_ANY));
    }

    /**
     * Neither selector is sized by its words.
     *
     * <p>Equal weight is what keeps the row the same shape whichever answers are showing, and it is
     * what stops "Screen selection" pushing its neighbour off a phone. A long value shortens itself
     * instead.
     */
    @Test public void theSelectorsShareTheRowEquallyAndEllipsizeLongValues() {
        seed();
        Prefs.setVaultFilter(context, OrbitVaultFilter.NONE
                .withSource(OrbitVaultSource.SCREEN_SELECTION));
        Activity screen = vaultScreen();
        LinearLayout.LayoutParams typeLp =
                (LinearLayout.LayoutParams) typeSelector(screen).getLayoutParams();
        LinearLayout.LayoutParams fromLp =
                (LinearLayout.LayoutParams) sourceSelector(screen).getLayoutParams();
        assertEquals(0, typeLp.width);
        assertEquals(0, fromLp.width);
        assertEquals(typeLp.weight, fromLp.weight, 0.001f);
        assertEquals(typeLp.height, fromLp.height);

        TextView value = firstTextView(sourceSelector(screen));
        assertNotNull(value);
        assertNotNull("a long source label must shorten rather than wrap or push",
                value.getEllipsize());
    }

    /** Six type choices, in the words the selector shows, and nothing else in the popup. */
    @Test public void theTypeSelectorOffersEveryTypeUnderItsClearerName() {
        assertEquals(6, OrbitVaultFilter.Type.values().length);
        assertEquals("All items", OrbitVaultFilter.Type.ALL.label);
        assertEquals("Orbit answers", OrbitVaultFilter.Type.ORBIT.label);
        assertEquals("Documents", OrbitVaultFilter.Type.DOCUMENTS.label);
        // Renamed for reading, never for storage.
        assertEquals("all", OrbitVaultFilter.Type.ALL.id);
        assertEquals("orbit", OrbitVaultFilter.Type.ORBIT.id);
        assertEquals("documents", OrbitVaultFilter.Type.DOCUMENTS.id);
    }

    // ---- independent reset -----------------------------------------------------------------------

    /** Choosing a type leaves the source and the words exactly where they were. */
    @Test public void changingTypePreservesSourceAndSearch() {
        OrbitVaultFilter start = new OrbitVaultFilter(OrbitVaultFilter.Type.IMAGES,
                OrbitVaultSource.SCREEN_SELECTION, "peak");
        OrbitVaultFilter after = start.withType(OrbitVaultFilter.Type.LINKS);
        assertEquals(OrbitVaultFilter.Type.LINKS, after.type);
        assertEquals(OrbitVaultSource.SCREEN_SELECTION, after.source);
        assertEquals("peak", after.query);
    }

    /** And returning it to All items is the same operation, not a reset of everything. */
    @Test public void resettingTypePreservesSourceAndSearch() {
        OrbitVaultFilter start = new OrbitVaultFilter(OrbitVaultFilter.Type.IMAGES,
                OrbitVaultSource.SCREEN_SELECTION, "peak");
        OrbitVaultFilter after = start.withType(OrbitVaultFilter.Type.ALL);
        assertEquals(OrbitVaultFilter.Type.ALL, after.type);
        assertEquals("the source survives turning the type off",
                OrbitVaultSource.SCREEN_SELECTION, after.source);
        assertEquals("and so do the words", "peak", after.query);
    }

    @Test public void changingSourcePreservesTypeAndSearch() {
        OrbitVaultFilter start = new OrbitVaultFilter(OrbitVaultFilter.Type.IMAGES,
                OrbitVaultSource.SCREEN_SELECTION, "peak");
        OrbitVaultFilter after = start.withSource(OrbitVaultSource.PHOTO);
        assertEquals(OrbitVaultFilter.Type.IMAGES, after.type);
        assertEquals(OrbitVaultSource.PHOTO, after.source);
        assertEquals("peak", after.query);
    }

    @Test public void resettingSourcePreservesTypeAndSearch() {
        OrbitVaultFilter start = new OrbitVaultFilter(OrbitVaultFilter.Type.IMAGES,
                OrbitVaultSource.SCREEN_SELECTION, "peak");
        OrbitVaultFilter after = start.withSource("");
        assertEquals(OrbitVaultFilter.Type.IMAGES, after.type);
        assertEquals("", after.source);
        assertEquals("peak", after.query);
    }

    /**
     * The same independence, through the screen.
     *
     * <p>The popup itself cannot be driven under Robolectric, so this exercises the state the popup
     * writes: the screen is asked to apply a type while a source is in force, and the source has to
     * still be in force afterwards with the search field untouched.
     */
    @Test public void theScreenKeepsSourceAndSearchWhenTheTypeIsReset() {
        seed();
        Prefs.setVaultFilter(context, new OrbitVaultFilter(OrbitVaultFilter.Type.IMAGES,
                OrbitVaultSource.SCREEN_SELECTION, ""));
        Activity screen = vaultScreen();
        EditText search = findSearchField(screen.getWindow().getDecorView());
        assertNotNull(search);
        search.setText("peak");

        Prefs.setVaultFilter(context, Prefs.vaultFilter(context)
                .withType(OrbitVaultFilter.Type.ALL));
        vault.pause().resume();

        assertEquals(OrbitVaultFilter.Type.ALL, Prefs.vaultFilter(context).type);
        assertEquals("the source is untouched",
                OrbitVaultSource.SCREEN_SELECTION, Prefs.vaultFilter(context).source);
        assertEquals("and the typed words are still there", "peak", search.getText().toString());
    }

    /**
     * Clear filters is now secondary and now honest.
     *
     * <p>It appears only while both selectors are narrowing, because with one in force that
     * selector already offers the way back; and it resets the two filters without deleting the
     * words the user typed, which Beta 4's version did.
     */
    @Test public void clearFiltersAppearsOnlyForTwoConstraintsAndSparesTheSearchField() {
        seed();
        assertNull("one filter needs no global clear",
                findByDescriptionPrefix(vaultScreen().getWindow().getDecorView(),
                        OrbitVaultActivity.CLEAR_FILTERS));

        Prefs.setVaultFilter(context, new OrbitVaultFilter(OrbitVaultFilter.Type.IMAGES,
                OrbitVaultSource.SCREEN_SELECTION, ""));
        Activity screen = vaultScreen();
        EditText search = findSearchField(screen.getWindow().getDecorView());
        search.setText("peak");
        View clear = findByDescriptionPrefix(screen.getWindow().getDecorView(),
                OrbitVaultActivity.CLEAR_FILTERS);
        assertNotNull("two filters together may offer one way back", clear);

        clear.performClick();
        assertEquals(OrbitVaultFilter.Type.ALL, Prefs.vaultFilter(context).type);
        assertEquals("", Prefs.vaultFilter(context).source);
        assertEquals("clearing filters must not erase the search", "peak",
                search.getText().toString());
    }

    /** A single type filter offers no global clear, because its own selector is the way back. */
    @Test public void oneTypeFilterOffersNoGlobalClear() {
        seed();
        Prefs.setVaultFilter(context, OrbitVaultFilter.NONE
                .withType(OrbitVaultFilter.Type.IMAGES));
        assertNull(findByDescriptionPrefix(vaultScreen().getWindow().getDecorView(),
                OrbitVaultActivity.CLEAR_FILTERS));
    }

    // ---- display labels --------------------------------------------------------------------------

    /** "Document" the stored word, "Orbit Documents" the shown one, and no migration between them. */
    @Test public void theDocumentSourceIsShownUnderAClearerNameWithoutBeingRenamed() {
        assertEquals(OrbitVaultSource.DOCUMENT_DISPLAY,
                OrbitVaultSource.displayLabel(OrbitVaultSource.DOCUMENT));
        assertEquals("Orbit Documents", OrbitVaultSource.DOCUMENT_DISPLAY);
        assertEquals("a shaped page source shows the same clearer label",
                OrbitVaultSource.DOCUMENT_DISPLAY,
                OrbitVaultSource.displayLabel("Document · Page 7"));
        assertEquals("but the canonical value is unchanged", "Document", OrbitVaultSource.DOCUMENT);

        OrbitVaultItem page = OrbitVaultStore.saveDocumentPage(context, "Health behavior theory",
                6, 388, "Self-efficacy.", picture(), "");
        assertNotNull(page);
        assertEquals("the provenance line under a saved page is untouched",
                "Document · Page 7", page.source);
        assertTrue("and that is what reaches the disk",
                OrbitVaultStore.prefs(context).getString("items_v1", "")
                        .contains("Document \\u00b7 Page 7")
                        || OrbitVaultStore.prefs(context).getString("items_v1", "")
                        .contains("Document · Page 7"));
    }

    /** Every other source shows exactly the word it is stored as. */
    @Test public void everyOtherSourceLabelIsItsOwnCanonicalWord() {
        for (String canonical : OrbitVaultSource.FILTERABLE) {
            String shown = OrbitVaultSource.displayLabel(canonical);
            assertFalse(canonical + " must have a label", shown.isEmpty());
            if (OrbitVaultSource.DOCUMENT.equals(canonical)) continue;
            assertEquals(canonical, shown);
        }
        assertEquals("a source Orbit did not write has no label at all",
                "", OrbitVaultSource.displayLabel("Shared from Chrome"));
    }

    /** A saved page is a Document by type and an Orbit Documents item by source. */
    @Test public void aSavedPageIsFilterableAsBothATypeAndASource() {
        seed();
        OrbitVaultFilter both = new OrbitVaultFilter(OrbitVaultFilter.Type.DOCUMENTS,
                OrbitVaultSource.DOCUMENT, "");
        List<OrbitVaultItem> found = OrbitVaultStore.browse(context, both,
                OrbitVaultStore.Sort.NEWEST);
        assertEquals(1, found.size());
        assertTrue(found.get(0).isDocumentPage());
        assertEquals("Documents · Orbit Documents", both.describe());
    }

    // ---- swipe -----------------------------------------------------------------------------------

    /** Vault cards are wrapped in the row Chats already uses, not in a second gesture detector. */
    @Test public void vaultCardsUseTheSharedSwipeRow() {
        String screen = source("OrbitVaultActivity");
        assertTrue(screen.contains("new OrbitSwipeRow(this,"));
        assertTrue(screen.contains("OrbitSwipeRow.ACTION_DELETE"));
        assertTrue(screen.contains("OrbitSwipeRow.ACTION_PIN"));
        for (String detector : new String[]{"GestureDetector", "onInterceptTouchEvent",
                "VelocityTracker", "getScaledTouchSlop"}) {
            assertFalse("the Vault must not write its own gesture: " + detector,
                    screen.contains(detector));
        }
    }

    /** Left is Delete and right is Pin, on a real card, in the real list. */
    @Test public void everyVaultCardIsArmedForDeleteAndPin() {
        seed();
        List<OrbitSwipeRow> rows = swipeRows(vaultScreen().getWindow().getDecorView());
        assertEquals("every saved item is swipeable", 6, rows.size());
        for (OrbitSwipeRow row : rows) {
            assertTrue(row.swipeEnabled());
            assertNotNull("the card itself is still the card", row.card());
        }
    }

    /**
     * The gesture is a shortcut, never the only route.
     *
     * <p>Both directions are exposed as accessibility actions on the card, named for what this list
     * holds rather than for chats, and the hold menu still offers Pin, Unpin and Delete without any
     * gesture at all.
     */
    @Test public void swipeActionsHaveNonGestureEquivalents() {
        seed();
        List<OrbitSwipeRow> rows = swipeRows(vaultScreen().getWindow().getDecorView());
        assertFalse(rows.isEmpty());
        List<CharSequence> named = accessibilityActionLabels(rows.get(0).card());
        assertTrue("pinning is reachable without a gesture",
                named.contains("Pin " + OrbitVaultActivity.SWIPE_SUBJECT));
        assertTrue("and so is deletion",
                named.contains("Delete " + OrbitVaultActivity.SWIPE_SUBJECT));

        String screen = source("OrbitVaultActivity");
        assertTrue("the hold menu still offers both", screen.contains("showItemMenu"));
        assertTrue(screen.contains("ACTION_PIN"));
        assertTrue(screen.contains("confirmDelete"));
    }

    /** A pinned card's spoken action says Unpin, because that is what it would do. */
    @Test public void aPinnedCardOffersUnpinRatherThanPin() {
        OrbitVaultItem item = OrbitVaultStore.saveText(context, "Packing list", "Charger",
                OrbitVaultSource.QUICK_CAPTURE);
        OrbitVaultStore.setPinned(context, item.id, true);
        List<OrbitSwipeRow> rows = swipeRows(vaultScreen().getWindow().getDecorView());
        assertEquals(1, rows.size());
        List<CharSequence> named = accessibilityActionLabels(rows.get(0).card());
        assertTrue(named.contains("Unpin " + OrbitVaultActivity.SWIPE_SUBJECT));
        assertFalse(named.contains("Pin " + OrbitVaultActivity.SWIPE_SUBJECT));
    }

    /**
     * Chats keeps the exact words it always had.
     *
     * <p>The subject is the only thing the Vault varies, and it defaults to "chat", so generalising
     * this component could not have changed what TalkBack reads out on the Chats list.
     */
    @Test public void chatSwipeLabelsAreUnchanged() {
        TestWorkManager.ensureInitialized(context);
        String id = ConversationStore.newId();
        List<AssistantClient.History> history = new ArrayList<>();
        history.add(new AssistantClient.History("user", "How long does a levain take?"));
        ConversationStore.save(context, id, history);
        ConversationStore.rename(context, id, "Sourdough");
        Activity chats = Robolectric.buildActivity(MainActivity.class).setup().get();
        List<OrbitSwipeRow> rows = swipeRows(chats.getWindow().getDecorView());
        assertFalse("Chats must still wrap its cards", rows.isEmpty());
        List<CharSequence> named = accessibilityActionLabels(rows.get(0).card());
        assertTrue(named.contains("Pin chat"));
        assertTrue(named.contains("Delete chat"));
    }

    /** Pinning through the gesture moves the item into the pinned section, exactly once. */
    @Test public void aSwipedPinMovesTheItemIntoThePinnedSectionWithoutDuplicatingIt() {
        seed();
        Activity screen = vaultScreen();
        OrbitSwipeRow row = rowForTitle(screen, "Peak sourdough");
        assertNotNull(row);
        row.card().performAccessibilityAction(R.id.orbit_action_pin, null);

        assertTrue(OrbitVaultStore.get(context, itemIdOf("Peak sourdough")).pinned);
        List<String> drawn = textOf(screen.getWindow().getDecorView());
        assertTrue(drawn.contains(OrbitVaultActivity.PINNED_HEADING));
        assertTrue(drawn.contains(OrbitVaultActivity.OTHERS_HEADING));
        int appearances = 0;
        for (String line : drawn) if ("Peak sourdough".equals(line)) appearances++;
        assertEquals("the item is in exactly one section", 1, appearances);
    }

    /** And doing it again to a pinned card unpins it. */
    @Test public void aSwipedPinOnAPinnedItemUnpinsIt() {
        seed();
        OrbitVaultStore.setPinned(context, itemIdOf("Peak sourdough"), true);
        Activity screen = vaultScreen();
        OrbitSwipeRow row = rowForTitle(screen, "Peak sourdough");
        assertNotNull(row);
        row.card().performAccessibilityAction(R.id.orbit_action_pin, null);

        assertFalse(OrbitVaultStore.get(context, itemIdOf("Peak sourdough")).pinned);
        assertFalse(textOf(screen.getWindow().getDecorView())
                .contains(OrbitVaultActivity.PINNED_HEADING));
    }

    /** A pinned item still obeys the filter, so pinning is never a way out of one. */
    @Test public void aPinnedItemStillObeysBothSelectors() {
        seed();
        OrbitVaultStore.setPinned(context, itemIdOf("Peak sourdough"), true);
        Prefs.setVaultFilter(context, new OrbitVaultFilter(OrbitVaultFilter.Type.DOCUMENTS,
                OrbitVaultSource.DOCUMENT, ""));
        List<String> drawn = textOf(vaultScreen().getWindow().getDecorView());
        assertFalse("a pinned link is not a saved page", drawn.contains("Peak sourdough"));
        assertFalse(drawn.contains(OrbitVaultActivity.PINNED_HEADING));
        assertTrue(joined(drawn).contains("Health behavior theory"));
    }

    // ---- delete and undo -------------------------------------------------------------------------

    /** A swiped-away text item leaves the list, and comes back untouched. */
    @Test public void aSwipedTextItemCanBeUndone() {
        OrbitVaultItem item = OrbitVaultStore.saveText(context, "Packing list", "Charger",
                OrbitVaultSource.QUICK_CAPTURE, "before the flight");
        Activity screen = vaultScreen();
        swipeDelete(screen, "Packing list");

        assertFalse("it is out of the list", textOf(screen.getWindow().getDecorView())
                .contains("Packing list"));
        assertNotNull("but nothing has been deleted yet",
                OrbitVaultStore.get(context, item.id));
        undo(screen);

        OrbitVaultItem after = OrbitVaultStore.get(context, item.id);
        assertNotNull(after);
        assertEquals(item.id, after.id);
        assertEquals("before the flight", after.note);
        assertEquals(item.createdAt, after.createdAt);
        assertEquals(item.modifiedAt, after.modifiedAt);
        assertTrue("and it is back on screen",
                textOf(screen.getWindow().getDecorView()).contains("Packing list"));
    }

    /** A link restores as a link, with its address intact. */
    @Test public void aSwipedLinkCanBeUndone() {
        OrbitVaultItem link = OrbitVaultStore.saveLink(context, "Peak sourdough",
                "https://example.com/peak", OrbitVaultSource.SHARED);
        Activity screen = vaultScreen();
        swipeDelete(screen, "Peak sourdough");
        undo(screen);

        OrbitVaultItem after = OrbitVaultStore.get(context, link.id);
        assertNotNull(after);
        assertTrue(after.isLink());
        assertEquals("https://example.com/peak", after.body);
    }

    /**
     * An image restores with its picture, because the picture was never removed.
     *
     * <p>The file is asserted directly at every step. A scheme that deleted the row first and
     * copied the bytes aside would pass a store-only assertion and fail this one.
     */
    @Test public void aSwipedImageRestoresWithItsMediaStillOwned() {
        OrbitVaultItem image = OrbitVaultStore.saveImage(context, picture(), "Peak crop",
                OrbitVaultSource.SCREEN_SELECTION);
        assertNotNull(image);
        assertTrue(new File(image.mediaPath).exists());
        int before = mediaFileCount();

        Activity screen = vaultScreen();
        swipeDelete(screen, "Peak crop");
        assertTrue("the picture must survive the undo window",
                new File(image.mediaPath).exists());
        assertEquals("and must not be copied to survive it", before, mediaFileCount());

        undo(screen);
        OrbitVaultItem after = OrbitVaultStore.get(context, image.id);
        assertNotNull(after);
        assertEquals("the same file, not a second copy", image.mediaPath, after.mediaPath);
        assertTrue(new File(after.mediaPath).exists());
        assertEquals(before, mediaFileCount());
    }

    /** A saved document page restores with its rendering, its document and its page number. */
    @Test public void aSwipedDocumentPageRestoresCompletely() {
        OrbitVaultItem page = OrbitVaultStore.saveDocumentPage(context, "Health behavior theory",
                6, 388, "Self-efficacy is the belief in one's capacity to act.", picture(),
                "read again");
        assertNotNull(page);
        OrbitVaultStore.setPinned(context, page.id, true);

        Activity screen = vaultScreen();
        swipeDelete(screen, page.displayTitle());
        assertTrue(new File(page.mediaPath).exists());
        undo(screen);

        OrbitVaultItem after = OrbitVaultStore.get(context, page.id);
        assertNotNull(after);
        assertTrue("the pin came back with it", after.pinned);
        assertEquals("read again", after.note);
        assertEquals("Health behavior theory", after.documentName);
        assertEquals(6, after.pageIndex);
        assertEquals(388, after.pageCount);
        assertEquals("Document · Page 7", after.source);
        assertEquals(page.mediaPath, after.mediaPath);
        assertEquals(page.createdAt, after.createdAt);
        assertEquals(page.modifiedAt, after.modifiedAt);
        assertTrue(new File(after.mediaPath).exists());
    }

    /**
     * An offer that expires is carried out, and reaches exactly one item's own picture.
     *
     * <p>The window is closed here by leaving the screen, which is one of the three things that
     * finish it; the timer is the same call on a delay.
     */
    @Test public void afinishedDeletionRemovesTheItemAndOnlyItsOwnMedia() {
        OrbitVaultItem doomed = OrbitVaultStore.saveImage(context, picture(), "Peak crop",
                OrbitVaultSource.SCREEN_SELECTION);
        OrbitVaultItem kept = OrbitVaultStore.saveImage(context, picture(), "Another crop",
                OrbitVaultSource.PHOTO);
        assertNotNull(doomed);
        assertNotNull(kept);

        Activity screen = vaultScreen();
        swipeDelete(screen, "Peak crop");
        vault.pause();

        assertNull("the item is gone once the offer ends",
                OrbitVaultStore.get(context, doomed.id));
        assertFalse("and so is the picture it owned", new File(doomed.mediaPath).exists());
        assertNotNull("nothing else was touched", OrbitVaultStore.get(context, kept.id));
        assertTrue(new File(kept.mediaPath).exists());
    }

    /**
     * A second deletion honours the first rather than discarding it.
     *
     * <p>The offer always belongs to the newest action, and the older one is carried out rather
     * than quietly forgotten, so an item can never escape a deletion the user meant.
     */
    @Test public void asecondDeletionCommitsTheFirst() {
        OrbitVaultItem first = OrbitVaultStore.saveText(context, "Packing list", "Charger",
                OrbitVaultSource.QUICK_CAPTURE);
        OrbitVaultItem second = OrbitVaultStore.saveText(context, "Selected paragraph",
                "A paragraph", OrbitVaultSource.SELECTED_TEXT);

        Activity screen = vaultScreen();
        swipeDelete(screen, "Packing list");
        swipeDelete(screen, "Selected paragraph");

        assertNull("the first deletion was carried out", OrbitVaultStore.get(context, first.id));
        assertNotNull("and the second is still undoable",
                OrbitVaultStore.get(context, second.id));
        undo(screen);
        assertNotNull(OrbitVaultStore.get(context, second.id));
    }

    /** The count in the header does not go on claiming an item the user has watched leave. */
    @Test public void thePendingItemIsOutOfTheHeaderCountToo() {
        seed();
        Activity screen = vaultScreen();
        swipeDelete(screen, "Packing list");
        assertTrue(joined(textOf(screen.getWindow().getDecorView()))
                .contains("5 saved items"));
    }

    // ---- the item screen -------------------------------------------------------------------------

    /** The header carries Back, the title and the type, and nothing else. */
    @Test public void theItemHeaderNoLongerHoldsAPinButton() {
        OrbitVaultItem page = OrbitVaultStore.saveDocumentPage(context,
                "Health behavior theory and research 3rd edition", 6, 388,
                "Self-efficacy.", picture(), "");
        assertNotNull(page);
        Activity screen = itemScreen(page);

        View back = findClickableWithDescription(screen.getWindow().getDecorView(),
                "Back to Vault");
        assertNotNull(back);
        ViewGroup header = (ViewGroup) back.getParent();
        assertEquals("Back, the titles, and nothing to their right", 2, header.getChildCount());

        // The title column takes every pixel the removed control was holding.
        LinearLayout.LayoutParams titleLp =
                (LinearLayout.LayoutParams) header.getChildAt(1).getLayoutParams();
        assertEquals(0, titleLp.width);
        assertTrue(titleLp.weight > 0f);
    }

    /** Pin is still one tap away, now beside the other utilities. */
    @Test public void pinMovedIntoTheUtilityActions() {
        OrbitVaultItem item = OrbitVaultStore.saveText(context, "Packing list", "Charger",
                OrbitVaultSource.QUICK_CAPTURE);
        Activity screen = itemScreen(item);
        View pin = findClickableWithDescription(screen.getWindow().getDecorView(),
                OrbitVaultItemActivity.ACTION_PIN + " this saved item");
        assertNotNull(pin);
        assertTrue("it is a utility cell, not a header button",
                textOf(pin).contains(OrbitVaultItemActivity.ACTION_PIN));

        pin.performClick();
        assertTrue(OrbitVaultStore.get(context, item.id).pinned);
        Activity reopened = itemScreen(OrbitVaultStore.get(context, item.id));
        assertNotNull("and it offers Unpin afterwards",
                findClickableWithDescription(reopened.getWindow().getDecorView(),
                        OrbitVaultItemActivity.ACTION_UNPIN + " this saved item"));
    }

    /** Four utilities take two rows on a phone and one on a tablet; two always take one. */
    @Test public void theUtilityRowsAreResponsive() {
        assertFalse("four across a phone is cramped",
                OrbitVaultItemActivity.fitsOneUtilityRow(412, 4));
        assertTrue("a Tab S9 Plus has the width",
                OrbitVaultItemActivity.fitsOneUtilityRow(800, 4));
        assertTrue("two always fit", OrbitVaultItemActivity.fitsOneUtilityRow(412, 2));
        assertTrue(OrbitVaultItemActivity.UTILITY_ROW_MIN_WIDTH_DP > 412);
    }

    /** An item with fewer actions leaves no holes: its row is simply shorter. */
    @Test public void anItemWithTwoUtilitiesGetsOneFullRow() {
        OrbitVaultItem image = OrbitVaultStore.saveImage(context, picture(), "Peak crop",
                OrbitVaultSource.SCREEN_SELECTION);
        assertNotNull(image);
        Activity screen = itemScreen(image);
        View rename = findClickableWithDescription(screen.getWindow().getDecorView(),
                OrbitVaultItemActivity.ACTION_RENAME);
        View pin = findClickableWithDescription(screen.getWindow().getDecorView(),
                OrbitVaultItemActivity.ACTION_PIN + " this saved item");
        assertNotNull(rename);
        assertNotNull(pin);
        assertTrue("both on one row", rename.getParent() == pin.getParent());
        assertEquals("with no empty cell beside them", 2,
                ((ViewGroup) rename.getParent()).getChildCount());
    }

    // ---- nothing else moved ----------------------------------------------------------------------

    /** Beta 1 through Beta 4 stores still load, with every field where it was. */
    @Test public void everyEarlierVaultDocumentStillLoads() {
        String stored = "[{\"id\":\"a\",\"type\":\"text\",\"title\":\"Packing list\","
                + "\"body\":\"Charger\",\"source\":\"Quick Capture\",\"note\":\"\","
                + "\"mediaPath\":\"\",\"createdAt\":1600000000000,\"modifiedAt\":1600000000000},"
                + "{\"id\":\"b\",\"type\":\"link\",\"title\":\"Recipe\","
                + "\"body\":\"https://example.com/r\",\"source\":\"Shared to Orbit\","
                + "\"note\":\"try this\",\"mediaPath\":\"\","
                + "\"createdAt\":1600000000001,\"modifiedAt\":1600000000001},"
                + "{\"id\":\"c\",\"type\":\"document_page\",\"title\":\"\",\"body\":\"page text\","
                + "\"source\":\"Document · Page 7\",\"note\":\"\",\"mediaPath\":\"\","
                + "\"documentName\":\"Health behavior theory\",\"pageIndex\":6,\"pageCount\":388,"
                + "\"createdAt\":1600000000002,\"modifiedAt\":1600000000002},"
                + "{\"id\":\"d\",\"type\":\"orbit_reply\",\"title\":\"\","
                + "\"body\":\"Feed it twice a day.\",\"source\":\"Orbit answer\",\"note\":\"\","
                + "\"mediaPath\":\"\",\"pinned\":true,"
                + "\"createdAt\":1600000000003,\"modifiedAt\":1600000000003}]";
        OrbitVaultStore.prefs(context).edit().putString("items_v1", stored).commit();

        assertEquals(4, OrbitVaultStore.count(context));
        assertEquals("try this", OrbitVaultStore.get(context, "b").note);
        OrbitVaultItem page = OrbitVaultStore.get(context, "c");
        assertEquals("Document · Page 7", page.source);
        assertEquals(7, page.pageNumber());
        assertTrue("a Beta 4 pin still loads", OrbitVaultStore.get(context, "d").pinned);

        // And every one of them is still reachable through both selectors.
        assertEquals(1, OrbitVaultStore.browse(context,
                new OrbitVaultFilter(OrbitVaultFilter.Type.DOCUMENTS, OrbitVaultSource.DOCUMENT, ""),
                OrbitVaultStore.Sort.NEWEST).size());
        assertEquals(1, OrbitVaultStore.browse(context,
                new OrbitVaultFilter(OrbitVaultFilter.Type.ORBIT, OrbitVaultSource.ORBIT_REPLY, ""),
                OrbitVaultStore.Sort.NEWEST).size());
    }

    /** A backup written by this release still restores every field, including the pin. */
    @Test public void backupsStillRoundTrip() throws Exception {
        OrbitVaultItem page = OrbitVaultStore.saveDocumentPage(context, "Health behavior theory",
                6, 388, "Self-efficacy.", picture(), "read again");
        assertNotNull(page);
        OrbitVaultStore.setPinned(context, page.id, true);

        String backup = exported();
        OrbitVaultStore.prefs(context).edit().clear().commit();
        OrbitBackupManager.restore(context, prepare(backup));

        OrbitVaultItem after = OrbitVaultStore.get(context, page.id);
        assertNotNull(after);
        assertTrue(after.pinned);
        assertEquals("read again", after.note);
        assertEquals("Document · Page 7", after.source);
        assertEquals(388, after.pageCount);
    }

    /** Filtering, searching, pinning, swiping and deleting stay local. */
    @Test public void noneOfThisReachesAProvider() {
        for (String name : new String[]{"OrbitVaultActivity", "OrbitVaultItemActivity",
                "OrbitVaultFilter", "OrbitVaultSource", "OrbitSwipeRow"}) {
            String text = source(name);
            for (String forbidden : new String[]{"AssistantClient", "ChatGptClient", "AutoRouter",
                    "HttpURLConnection", "java.net.", "TextRecognizer", "OrbitRequestManager"}) {
                assertFalse(name + " must not reference " + forbidden, text.contains(forbidden));
            }
        }
    }

    /** The chat swipe preference still means chats, and nothing else. */
    @Test public void theChatSwipePreferenceDoesNotReachTheVault() {
        assertFalse("Vault swipes are native to the Vault",
                source("OrbitVaultActivity").contains("chatSwipeActions"));
        assertTrue("and Chats still reads it",
                source("MainActivity").contains("Prefs.chatSwipeActions(this)"));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static String source(String simpleName) {
        return ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/" + simpleName + ".java");
    }

    private Activity itemScreen(OrbitVaultItem item) {
        Intent intent = new Intent(context, OrbitVaultItemActivity.class)
                .putExtra(OrbitVaultItemActivity.EXTRA_ITEM_ID, item.id);
        return Robolectric.buildActivity(OrbitVaultItemActivity.class, intent).setup().get();
    }

    private String itemIdOf(String title) {
        for (OrbitVaultItem item : OrbitVaultStore.list(context)) {
            if (title.equals(item.displayTitle())) return item.id;
        }
        return "";
    }

    private OrbitSwipeRow rowForTitle(Activity screen, String title) {
        for (OrbitSwipeRow row : swipeRows(screen.getWindow().getDecorView())) {
            if (textOf(row.card()).contains(title)) return row;
        }
        return null;
    }

    private void swipeDelete(Activity screen, String title) {
        OrbitSwipeRow row = rowForTitle(screen, title);
        assertNotNull("no card is showing " + title, row);
        row.card().performAccessibilityAction(R.id.orbit_action_delete, null);
    }

    private void undo(Activity screen) {
        View undo = findClickableWithDescription(screen.getWindow().getDecorView(),
                "Undo deleting this saved item");
        assertNotNull("the Undo offer must be on screen", undo);
        undo.performClick();
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

    private static List<CharSequence> accessibilityActionLabels(View card) {
        android.view.accessibility.AccessibilityNodeInfo info =
                android.view.accessibility.AccessibilityNodeInfo.obtain();
        card.onInitializeAccessibilityNodeInfo(info);
        List<CharSequence> out = new ArrayList<>();
        for (android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction action
                : info.getActionList()) {
            if (action.getLabel() != null) out.add(action.getLabel());
        }
        return out;
    }

    private static List<OrbitSwipeRow> swipeRows(View view) {
        List<OrbitSwipeRow> out = new ArrayList<>();
        collectRows(view, out);
        return out;
    }

    private static void collectRows(View view, List<OrbitSwipeRow> out) {
        if (view instanceof OrbitSwipeRow) out.add((OrbitSwipeRow) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectRows(group.getChildAt(i), out);
        }
    }

    private static View findHorizontalScroller(View view) {
        if (view instanceof HorizontalScrollView) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findHorizontalScroller(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
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

    private static TextView firstTextView(View view) {
        if (view instanceof TextView) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = firstTextView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
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

    private static View findByDescriptionPrefix(View view, String prefix) {
        if (view.isClickable()) {
            CharSequence actual = view.getContentDescription();
            if (actual != null && actual.toString().startsWith(prefix)) return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findByDescriptionPrefix(group.getChildAt(i), prefix);
                if (found != null) return found;
            }
        }
        return null;
    }
}
