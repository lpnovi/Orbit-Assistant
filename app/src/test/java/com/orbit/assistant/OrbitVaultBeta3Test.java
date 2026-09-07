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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

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
import java.util.List;

/**
 * Orbit Vault Beta 3: the Vault as somewhere Orbit's own surfaces can deliberately put things.
 *
 * <p>Beta 1 built the store and Beta 2 made a saved item useful. Beta 3's claim is a different one
 * and needs a different kind of proof: saving now appears in Documents, in Screen Selection, in the
 * text-selection doorway and on Orbit Deck, and every one of those is a new place where "nothing is
 * ever saved automatically" could quietly stop being true. So each new route is asserted twice -
 * once that an explicit act saves exactly what the user was looking at, and once that nothing
 * happens without that act, including when the Vault has been switched off.
 *
 * <p>The saved document page carries the most weight, because it is the first item type whose value
 * survives its source. A page kept from a PDF has to remain readable after the PDF is gone, which
 * means the text and the rendering are Orbit's own copies rather than a pointer, and it has to
 * degrade honestly when the rendering is lost rather than taking the rest of the Vault with it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class OrbitVaultBeta3Test {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        OrbitVaultStore.prefs(context).edit().clear().commit();
        Prefs.get(context).edit().clear().commit();
        DiagnosticStore.prefs(context).edit().clear().commit();
        ConversationStore.clear(context);
        SharedContentStore.clear();
        DeckLayoutStore.clearForTest(context);
        File media = OrbitVaultMedia.directory(context);
        File[] files = media.listFiles();
        if (files != null) for (File file : files) file.delete();
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

    private OrbitVaultItem savedPage() {
        return OrbitVaultStore.saveDocumentPage(context, "Health behavior theory", 6, 388,
                "Self-efficacy is the belief in one's capacity to act.", picture(), "");
    }

    // ---- Beta 2 polish: the two things a phone found ---------------------------------------------

    /**
     * The action area starts below the card above it, not against it.
     *
     * <p>Asserted as a real top margin on the first action row rather than by measuring pixels: a
     * screenshot test would pass or fail on font metrics, while the margin is the actual thing that
     * was missing when Open link and Ask Orbit appeared to clip into the metadata card.
     */
    @Test public void theprimaryActionRowIsSpacedAwayFromTheCardAboveIt() {
        assertTrue("the gap must be a deliberate one, not a hairline",
                OrbitVaultItemActivity.ACTIONS_TOP_GAP_DP >= 12);
        assertTrue("and not so large the actions float away from the item",
                OrbitVaultItemActivity.ACTIONS_TOP_GAP_DP <= 16);

        OrbitVaultItem item = OrbitVaultStore.saveLink(context, "Recipe",
                "https://example.com/r", OrbitVaultSource.CLIPBOARD);
        Activity screen = itemScreen(item);
        View open = findClickableWithDescription(screen.getWindow().getDecorView(), null,
                OrbitVaultItemActivity.ACTION_OPEN_LINK);
        assertNotNull("the link screen still offers Open link", open);

        View row = (View) open.getParent();
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) row.getLayoutParams();
        int expected = Math.round(OrbitVaultItemActivity.ACTIONS_TOP_GAP_DP
                * context.getResources().getDisplayMetrics().density);
        assertEquals("the primary action row carries its own top margin",
                expected, lp.topMargin);
    }

    /**
     * The note editor is a bounded box, not a field with a rule floating under it.
     *
     * <p>Two properties carry the fix. The field draws its own background, which is what removes
     * the platform underline that device testing read as a character meter, and it is bounded top
     * and bottom so it starts compact and scrolls inside itself rather than growing without limit.
     */
    @Test public void thenoteEditorUsesTheBoundedOrbitInputSurface() {
        EditText field = UiKit.input(context, "Why did you save this?", true);
        assertNotNull("the field draws its own surface rather than a platform underline",
                field.getBackground());
        assertNull("and is never tinted, because a tint is what colours that underline",
                field.getBackgroundTintList());
        assertEquals(UiKit.INPUT_MIN_LINES, field.getMinLines());
        assertEquals(UiKit.INPUT_MAX_LINES, field.getMaxLines());
        assertTrue("it starts compact", UiKit.INPUT_MIN_LINES <= 3);
        assertTrue("and stops before it can push a dialog's buttons off screen",
                UiKit.INPUT_MAX_LINES <= 10);
        assertTrue("its padding is its own, so text starts near the top of the box",
                field.getPaddingTop() > 0 && field.getPaddingLeft() > 0);

        String screen = source("OrbitVaultItemActivity");
        assertTrue("the note dialog builds its field through the shared surface",
                screen.contains("UiKit.input(this, \"Why did you save this?\", true)"));
        assertFalse("and never re-tints a platform underline",
                screen.contains("note.setBackgroundTintList"));
        assertFalse("no character counter belongs on a note field",
                screen.contains("setCounterEnabled") || screen.contains("MAX_NOTE_CHARS +"));
    }

    /** The note itself is untouched by the new field: it still round-trips and is still found. */
    @Test public void anoteStillPersistsAndIsStillSearchable() {
        OrbitVaultItem saved = OrbitVaultStore.saveText(context, "Clip", "Some text",
                OrbitVaultSource.QUICK_CAPTURE, "animation inspiration");
        assertEquals("animation inspiration", OrbitVaultStore.get(context, saved.id).note);
        assertEquals(1, OrbitVaultStore.search(context, "animation",
                OrbitVaultStore.Sort.NEWEST).size());
    }

    // ---- saved document pages ---------------------------------------------------------------------

    /** Saving a page produces a page, described completely, and not a note that mentions one. */
    @Test public void savingApageProducesADocumentPageItem() {
        OrbitVaultItem page = savedPage();
        assertNotNull(page);
        assertEquals(OrbitVaultItem.TYPE_DOCUMENT_PAGE, page.type);
        assertTrue(page.isDocumentPage());
        assertFalse("a saved page is not an ordinary note", page.isText());
        assertFalse("nor an ordinary picture", page.isImage());

        assertEquals("the document's own name is kept", "Health behavior theory",
                page.documentName);
        assertEquals("the page is kept where it was", 6, page.pageIndex);
        assertEquals("counting from one for a person", 7, page.pageNumber());
        assertEquals(388, page.pageCount);
        assertEquals("Page 7 of 388", page.pageLabel());
        assertEquals("Self-efficacy is the belief in one's capacity to act.", page.body);
        assertEquals("the source line says where it came from",
                OrbitVaultSource.documentPage(7), page.source);
        assertEquals("Document · Page 7", page.source);
        assertEquals("Document page", page.typeLabel());
        assertEquals("and it names itself without the user having to",
                "Health behavior theory · Page 7 of 388", page.displayTitle());
    }

    /** The rendering is Orbit's own private copy, inside the Vault's own directory. */
    @Test public void asavedPageOwnsItsRenderingPrivately() {
        OrbitVaultItem page = savedPage();
        assertFalse("a saved page keeps a rendering", page.mediaPath.isEmpty());
        assertTrue("which is a file the Vault genuinely owns",
                OrbitVaultMedia.owns(context, page.mediaPath));
        assertTrue(new File(page.mediaPath).isFile());
        assertTrue("and it is media the type is expected to have", page.ownsMedia());
        assertFalse("but not media it cannot exist without", page.requiresMedia());
    }

    /** No path to the original document is stored anywhere on the item. */
    @Test public void asavedPageKeepsNoRouteBackToTheOriginalDocument() {
        OrbitVaultItem page = savedPage();
        String stored = OrbitVaultStore.backupJson(context);
        assertFalse("no PDF path may be stored", stored.contains(".pdf"));
        assertFalse("nor a content URI", stored.contains("content://"));
        assertTrue("the only path on the item is the Vault's own rendering",
                OrbitVaultMedia.owns(context, page.mediaPath));
    }

    /** A page's text obeys the item body ceiling like everything else the Vault keeps. */
    @Test public void asavedPagesTextIsBounded() {
        StringBuilder huge = new StringBuilder();
        while (huge.length() < OrbitVaultItem.MAX_BODY_CHARS + 5000) huge.append("page text ");
        OrbitVaultItem page = OrbitVaultStore.saveDocumentPage(context, "Big book", 0, 2,
                huge.toString(), picture(), "");
        assertNotNull(page);
        assertTrue("the body is cut at the Vault's own ceiling",
                page.body.length() <= OrbitVaultItem.MAX_BODY_CHARS + 200);
        assertTrue("and says so rather than being silently truncated",
                page.body.contains("Orbit trimmed this saved item"));
    }

    /** A scanned page that rendered but yielded no text is still worth keeping. */
    @Test public void apageWithNoExtractableTextIsStillSaved() {
        OrbitVaultItem page = OrbitVaultStore.saveDocumentPage(context, "Scanned notes", 2, 9,
                "   ", picture(), "");
        assertNotNull("a figure or a scan is exactly the page somebody wants to keep", page);
        assertEquals("", page.body);
        assertFalse(page.mediaPath.isEmpty());
    }

    /** A page with neither text nor a rendering is nothing, and is refused rather than stored. */
    @Test public void apageWithNothingInItIsRefused() {
        assertNull(OrbitVaultStore.saveDocumentPage(context, "Empty", 0, 1, "", null, ""));
        assertEquals(0, OrbitVaultStore.count(context));
    }

    /** Deleting a saved page removes the rendering it owned, and only that. */
    @Test public void deletingApageRemovesOnlyItsOwnRendering() {
        OrbitVaultItem page = savedPage();
        OrbitVaultItem photo = OrbitVaultStore.saveImage(context, picture(), "Holiday",
                OrbitVaultSource.PHOTO);
        assertNotNull(photo);
        File pageFile = new File(page.mediaPath);
        File photoFile = new File(photo.mediaPath);
        assertTrue(pageFile.isFile() && photoFile.isFile());

        assertTrue(OrbitVaultStore.delete(context, page.id));
        assertFalse("the page's own rendering is gone", pageFile.exists());
        assertTrue("and nothing else in the Vault was touched", photoFile.isFile());
        assertEquals(1, OrbitVaultStore.count(context));
    }

    /**
     * A rendering that vanishes underneath a saved page costs the picture, never the page.
     *
     * <p>The whole Vault has to stay readable. A file deleted by the system, a partial restore, or
     * a backup that could not carry an image must not turn one row into an unopenable store.
     */
    @Test public void amissingRenderingDoesNotBreakThePageOrTheVault() {
        OrbitVaultItem page = savedPage();
        OrbitVaultStore.saveText(context, "Neighbour", "still here",
                OrbitVaultSource.QUICK_CAPTURE);
        assertTrue(new File(page.mediaPath).delete());

        List<OrbitVaultItem> all = OrbitVaultStore.list(context);
        assertEquals("both items still load", 2, all.size());
        OrbitVaultItem reloaded = OrbitVaultStore.get(context, page.id);
        assertNotNull("the page is still there", reloaded);
        assertEquals("with its text intact", page.body, reloaded.body);
        assertEquals("and its page number", 7, reloaded.pageNumber());
        assertFalse("only the file is gone", new File(reloaded.mediaPath).exists());
        assertTrue("the item is still storable without it",
                OrbitVaultItem.isStorable(reloaded));

        // And the screen that draws it opens rather than crashing.
        Activity screen = itemScreen(reloaded);
        String drawn = String.join("\n", textOf(screen.getWindow().getDecorView()));
        assertTrue("and still shows the page's text", drawn.contains("Self-efficacy"));
        assertTrue("and what it was", drawn.contains("Health behavior theory"));

        // A backup written now carries the page without its rendering rather than dropping it.
        assertTrue(OrbitVaultStore.backupJson(context).contains("Health behavior theory"));
    }

    /** A page item with no rendering at all is storable, because its text is the page. */
    @Test public void apageWithOnlyTextIsStorable() {
        OrbitVaultItem page = OrbitVaultStore.saveDocumentPage(context, "Text only", 1, 4,
                "Chapter two begins here.", null, "");
        assertNotNull(page);
        assertEquals("", page.mediaPath);
        assertEquals("Chapter two begins here.", OrbitVaultStore.get(context, page.id).body);
    }

    // ---- Ask Orbit about a saved page --------------------------------------------------------------

    /**
     * Ask Orbit stages the page and stops.
     *
     * <p>The same rule Beta 2 set for every saved item: a composer is armed and nothing is sent, no
     * conversation is written and no request is created. A page is a richer attachment than a note,
     * which is exactly why it is worth re-asserting that being richer did not make it automatic.
     */
    @Test public void askOrbitStagesASavedPageWithoutSendingAnything() {
        OrbitVaultItem page = savedPage();
        ComposerAttachment attachment = OrbitVaultAttachment.of(context, page);
        assertNotNull(attachment);
        assertEquals("it is an ordinary Vault attachment, on the ordinary path",
                OrbitVaultAttachment.KIND, attachment.kind);
        assertNotNull("a vision-capable provider can be given the page itself", attachment.image);
        assertEquals("the card says which page this is", "Page 7 of 388", attachment.detail);
        assertEquals(ComposerAttachment.CONTENT_FULL_TEXT, attachment.contentState);
        assertTrue(attachment.label.contains("Health behavior theory"));

        assertTrue("the page's own text travels", attachment.contextText.contains("Self-efficacy"));
        assertTrue("named as a page of a named document",
                attachment.contextText.contains("Health behavior theory"));
        assertTrue(attachment.contextText.contains("Page 7 of 388"));
        assertTrue("and framed as untrusted data rather than instructions",
                attachment.contextText.contains("untrusted data"));

        assertNull("nothing has been written to a conversation",
                ConversationStore.load(context, "unused"));
        assertEquals("and nothing has been requested", 0, ConversationStore.list(context).size());
    }

    /**
     * A provider with no vision is told the truth, never that a picture it never received explains
     * the page.
     */
    @Test public void apageAttachmentNeverClaimsAnImageTravelled() {
        OrbitVaultItem page = savedPage();
        String withImage = OrbitVaultAttachment.contextTextFor(page, true);
        String withoutImage = OrbitVaultAttachment.contextTextFor(page, false);
        for (String text : new String[]{withImage, withoutImage}) {
            assertTrue("the extracted text is the honest fallback",
                    text.contains("Self-efficacy"));
            assertFalse("and no claim is made about an attached image",
                    text.contains("image itself is attached"));
        }
    }

    /** A page with no extractable text says so instead of inviting the model to invent some. */
    @Test public void apageWithNoTextSaysSoRatherThanPretending() {
        OrbitVaultItem page = OrbitVaultStore.saveDocumentPage(context, "Scanned notes", 0, 3,
                "", picture(), "");
        String text = OrbitVaultAttachment.contextTextFor(page, true);
        assertTrue(text.contains("little or no extractable text"));
        assertTrue(text.contains("do not claim to have read text that was not provided"));
        ComposerAttachment attachment = OrbitVaultAttachment.of(context, page);
        assertEquals("and Orbit records what it really has",
                ComposerAttachment.CONTENT_VISUAL_ONLY, attachment.contentState);
    }

    /** The user's own note about a saved page stays labelled as theirs. */
    @Test public void anoteOnASavedPageIsSeparatelyLabelled() {
        OrbitVaultItem page = savedPage();
        assertTrue(OrbitVaultStore.updateNote(context, page.id, "For the literature review"));
        String text = OrbitVaultAttachment.contextTextFor(
                OrbitVaultStore.get(context, page.id), true);
        assertTrue(text.contains("The user's own note about why they saved it"));
        assertTrue(text.contains("For the literature review"));
    }

    /** Editing a saved page keeps everything about the page that makes it one. */
    @Test public void renamingASavedPageKeepsItsDocumentAndItsPageNumber() {
        OrbitVaultItem page = savedPage();
        assertTrue(OrbitVaultStore.updateTitle(context, page.id, "Self-efficacy"));
        OrbitVaultItem renamed = OrbitVaultStore.get(context, page.id);
        assertEquals("Self-efficacy", renamed.title);
        assertEquals("Health behavior theory", renamed.documentName);
        assertEquals(6, renamed.pageIndex);
        assertEquals(388, renamed.pageCount);
        assertEquals(page.mediaPath, renamed.mediaPath);
        assertFalse("a saved page is a record, so its text is not editable",
                renamed.bodyIsEditable());
    }

    // ---- Documents: the entry point ----------------------------------------------------------------

    /** The document viewer offers saving, and only when the Vault is on. */
    @Test public void thedocumentViewerOffersSavingOnlyWhileTheVaultIsOn() {
        String viewer = source("DocumentViewerActivity");
        assertTrue("it offers Save page to Vault", viewer.contains("savePageToVault"));
        assertTrue("through the Vault's own document-page store",
                viewer.contains("OrbitVaultStore.saveDocumentPage("));
        assertTrue("the control is hidden while the Vault is off",
                viewer.contains("Prefs.vaultEnabled(this) ? View.VISIBLE : View.GONE"));
        assertTrue("and the store is asked again before anything is written",
                viewer.contains("if (!Prefs.vaultEnabled(this)) {"));
        assertTrue("it uses the real Vault drawable", viewer.contains("R.drawable.ic_vault"));
        assertFalse("saving a page must never copy the whole document",
                viewer.contains("saveDocumentToVault"));
        assertTrue("and Ask Orbit is untouched by any of it",
                viewer.contains("SharedContentStore.stageDocumentPage(page)"));
    }

    // ---- Screen Selection ---------------------------------------------------------------------------

    /** An explicit save from the crop editor becomes an image item that says where it came from. */
    @Test public void screenSelectionSavesOnlyWhenAsked() {
        Activity editor = screenSelectionEditor();
        assertEquals("opening the editor saves nothing", 0, OrbitVaultStore.count(context));

        View save = findClickableWithDescription(editor.getWindow().getDecorView(),
                "Save this selection to your Vault", null);
        assertNotNull("the editor offers Save to Vault", save);
        save.performClick();

        List<OrbitVaultItem> saved = OrbitVaultStore.list(context);
        assertEquals(1, saved.size());
        assertTrue("a crop is a picture", saved.get(0).isImage());
        assertEquals(OrbitVaultSource.SCREEN_SELECTION, saved.get(0).source);
        assertTrue("copied into Orbit's own storage",
                OrbitVaultMedia.owns(context, saved.get(0).mediaPath));
        assertFalse("the editor stays open so the crop can still be used in a message",
                editor.isFinishing());
    }

    /** With the Vault off the control is not there, and the crop editor is otherwise unchanged. */
    @Test public void screenSelectionHidesSavingWhileTheVaultIsOff() {
        Prefs.get(context).edit().putBoolean(Prefs.VAULT_ENABLED, false).commit();
        Activity editor = screenSelectionEditor();
        View save = findClickableWithDescription(editor.getWindow().getDecorView(),
                "Save this selection to your Vault", null);
        assertTrue("the Vault control is gone", save == null || save.getVisibility() == View.GONE);

        String drawn = String.join("\n", textOf(editor.getWindow().getDecorView()));
        assertTrue("using the selection in a message still works", drawn.contains("Use selection"));
        assertTrue(drawn.contains("Use full screen"));
    }

    // ---- selected text ------------------------------------------------------------------------------

    /** Keeping a selection stores exactly the characters that were selected. */
    @Test public void aselectedParagraphIsSavedExactlyAsItWasSelected() {
        String selection = "  Line one\n  Line two with  double spaces  ";
        OrbitVaultItem saved = OrbitVaultStore.saveText(context, "", selection,
                OrbitVaultSource.SELECTED_TEXT);
        assertNotNull(saved);
        assertEquals("a selection is a note, not a link", OrbitVaultItem.TYPE_TEXT, saved.type);
        assertEquals(OrbitVaultSource.SELECTED_TEXT, saved.source);
        assertEquals("the words between the ends are untouched",
                selection.trim(), saved.body);
    }

    /** The doorway asks where a selection should go, and asks nothing when the Vault is off. */
    @Test public void theselectedTextDoorwayOffersTheVaultOnlyWhileItIsOn() {
        String doorway = source("ProcessTextToOrbitActivity");
        assertTrue("it offers both destinations",
                doorway.contains("setPositiveButton(ASK_ORBIT")
                        && doorway.contains("setNegativeButton(SAVE_TO_VAULT"));
        assertTrue("gated on the one preference",
                doorway.contains("if (Prefs.vaultEnabled(this)) {"));
        assertTrue("and with the Vault off the selection goes straight to a conversation",
                doorway.contains("openConversation(chat);"));
        assertTrue("saving records the canonical source",
                doorway.contains("OrbitVaultSource.SELECTED_TEXT"));
        assertFalse("Orbit never writes back over the user's own selection",
                doorway.contains("setResult("));
        assertFalse("and never sends anything itself",
                doorway.contains("OrbitRequestManager") || doorway.contains("AssistantClient"));
    }

    // ---- source vocabulary ---------------------------------------------------------------------------

    /**
     * Provenance comes from one closed list, and never from another app.
     *
     * <p>The share case is the one that matters. A receiver sees only what the sender chose to put
     * in the Intent, so "Shared from Chrome" would be Orbit writing something it cannot know
     * permanently into the user's own collection.
     */
    @Test public void everySaveRouteUsesTheCanonicalSourceVocabulary() {
        for (String source : new String[]{OrbitVaultSource.QUICK_CAPTURE, OrbitVaultSource.CLIPBOARD,
                OrbitVaultSource.PHOTO, OrbitVaultSource.SHARED, OrbitVaultSource.SELECTED_TEXT,
                OrbitVaultSource.SCREEN_SELECTION, OrbitVaultSource.ORBIT_REPLY,
                OrbitVaultSource.DOCUMENT, OrbitVaultSource.documentPage(12)}) {
            assertTrue(source + " must be canonical", OrbitVaultSource.isCanonical(source));
            assertTrue(source + " must fit the item field",
                    source.length() <= OrbitVaultItem.MAX_SOURCE_CHARS);
        }
        assertFalse(OrbitVaultSource.isCanonical("Shared from Candy Browser"));
        assertFalse(OrbitVaultSource.isCanonical("Document · Page many"));
        assertFalse(OrbitVaultSource.isCanonical(""));
        assertFalse(OrbitVaultSource.isCanonical(null));
        assertEquals("a page number Orbit could not work out degrades rather than lies",
                OrbitVaultSource.DOCUMENT, OrbitVaultSource.documentPage(0));

        assertEquals("a share stays a share", OrbitVaultSource.SHARED,
                ShareToOrbitActivity.SOURCE_LABEL);
        String share = source("ShareToOrbitActivity");
        assertFalse("and is never labelled with the sending app",
                share.contains("getApplicationLabel"));
        assertFalse("the share doorway does not even look the sender up",
                share.contains("getPackageManager") || share.contains("getCallingPackage"));
        assertFalse("nor trusts a name the sender supplied",
                share.contains("EXTRA_SUBJECT") || share.contains("EXTRA_TITLE"));
    }

    // ---- Orbit Deck -----------------------------------------------------------------------------------

    /** Both Deck destinations exist, resolve, and run through the canonical architecture. */
    @Test public void thedeckDestinationsResolveAndRun() {
        for (String type : new String[]{DeckTileRegistry.TYPE_VAULT,
                DeckTileRegistry.TYPE_QUICK_CAPTURE}) {
            DeckTileRegistry.Definition definition = DeckTileRegistry.definition(type);
            assertNotNull(type + " must be a registered tile", definition);
            assertTrue("a destination is placed once", definition.singleton);
            assertFalse("and needs nothing configured", definition.configurable);
            assertEquals(DeckTileRegistry.Category.ORBIT, definition.category);

            DeckTileResolver.Resolved resolved =
                    DeckTileResolver.resolve(context, DeckTile.of(type, DeckTile.Size.STANDARD));
            assertTrue(type + " must resolve while the Vault is on", resolved.usable());
            assertEquals(definition.title, resolved.title);
        }
        assertEquals("the Vault tile uses Orbit's real Vault drawable", R.drawable.ic_vault,
                DeckTileRegistry.definition(DeckTileRegistry.TYPE_VAULT).iconRes);

        String executor = source("DeckActionExecutor");
        assertTrue("Vault opens the Vault", executor.contains("new Intent(activity, OrbitVaultActivity.class)"));
        assertTrue("Quick Capture opens the Vault's own capture flow",
                executor.contains("OrbitVaultActivity.EXTRA_QUICK_CAPTURE"));
        assertFalse("Deck adds no second capture implementation",
                executor.contains("showCaptureMenu") || executor.contains("saveText("));
    }

    /**
     * With the Vault off the tiles stop working and say why, and the user's Deck is left alone.
     *
     * <p>Unavailable rather than unresolved on purpose: unresolved offers to remove the tile, and
     * offering to delete somebody's layout because they flipped a preference would be Orbit
     * suggesting they throw away something they will want back in a minute.
     */
    @Test public void adisabledVaultDisablesTheTilesWithoutTouchingTheDeck() {
        // An exact layout rather than added tiles, so "nothing was removed" is a comparison against
        // a known list instead of against Orbit's shipped defaults.
        List<DeckTile> mine = new ArrayList<>();
        mine.add(DeckTile.of(DeckTileRegistry.TYPE_VAULT, DeckTile.Size.STANDARD));
        mine.add(DeckTile.of(DeckTileRegistry.TYPE_QUICK_CAPTURE, DeckTile.Size.STANDARD));
        mine.add(DeckTile.of(DeckTileRegistry.TYPE_SETTINGS, DeckTile.Size.STANDARD));
        assertTrue(DeckLayoutStore.save(context, mine));
        int placed = DeckLayoutStore.layout(context).size();
        assertEquals(3, placed);

        Prefs.get(context).edit().putBoolean(Prefs.VAULT_ENABLED, false).commit();

        assertEquals("no tile is removed when the Vault is switched off",
                placed, DeckLayoutStore.layout(context).size());
        assertTrue(DeckLayoutStore.contains(context, DeckTileRegistry.TYPE_VAULT));
        assertTrue(DeckLayoutStore.contains(context, DeckTileRegistry.TYPE_QUICK_CAPTURE));

        for (String type : new String[]{DeckTileRegistry.TYPE_VAULT,
                DeckTileRegistry.TYPE_QUICK_CAPTURE}) {
            DeckTileResolver.Resolved off =
                    DeckTileResolver.resolve(context, DeckTile.of(type, DeckTile.Size.STANDARD));
            assertFalse(type + " cannot run while the Vault is off", off.usable());
            assertEquals("it must not offer to remove the user's tile",
                    DeckTile.Availability.UNAVAILABLE, off.availability);
            assertEquals(DeckTileResolver.VAULT_OFF, off.subtitle);
        }
        assertEquals("the Settings tile is entirely unaffected",
                DeckTile.Availability.AVAILABLE,
                DeckTileResolver.resolve(context,
                        DeckTile.of(DeckTileRegistry.TYPE_SETTINGS, DeckTile.Size.STANDARD)).availability);

        assertFalse("and nothing turned the Vault back on", Prefs.vaultEnabled(context));

        Prefs.get(context).edit().putBoolean(Prefs.VAULT_ENABLED, true).commit();
        assertTrue("switching it back on returns the tiles exactly as they were",
                DeckTileResolver.resolve(context,
                        DeckTile.of(DeckTileRegistry.TYPE_VAULT, DeckTile.Size.STANDARD)).usable());
    }

    /** A disabled Vault is not offered as something new to add, but nothing else changes. */
    @Test public void adisabledVaultIsNotOfferedOnTheAddSheet() {
        for (String type : new String[]{DeckTileRegistry.TYPE_VAULT,
                DeckTileRegistry.TYPE_QUICK_CAPTURE}) {
            assertTrue(DeckTileRegistry.isOfferable(context, DeckTileRegistry.definition(type)));
        }
        Prefs.get(context).edit().putBoolean(Prefs.VAULT_ENABLED, false).commit();
        for (String type : new String[]{DeckTileRegistry.TYPE_VAULT,
                DeckTileRegistry.TYPE_QUICK_CAPTURE}) {
            assertFalse(DeckTileRegistry.isOfferable(context, DeckTileRegistry.definition(type)));
        }
        assertTrue("every other tile is still offered", DeckTileRegistry.isOfferable(context,
                DeckTileRegistry.definition(DeckTileRegistry.TYPE_ROUTINES)));
        assertTrue(DeckTileRegistry.isOfferable(context,
                DeckTileRegistry.definition(DeckTileRegistry.TYPE_FLASHLIGHT)));
    }

    // ---- the Vault switch across the new surfaces --------------------------------------------------

    /** Every Beta 3 route refuses to write while the Vault is off, at the store rather than at a screen. */
    @Test public void everyNewSaveRouteRefusesWhileTheVaultIsOff() {
        Prefs.get(context).edit().putBoolean(Prefs.VAULT_ENABLED, false).commit();
        assertNull(OrbitVaultStore.saveDocumentPage(context, "Book", 1, 4, "text", picture(), ""));
        assertNull(OrbitVaultStore.saveImage(context, picture(), "",
                OrbitVaultSource.SCREEN_SELECTION));
        assertNull(OrbitVaultStore.saveText(context, "", "selected words",
                OrbitVaultSource.SELECTED_TEXT));
        assertEquals("and nothing at all was written", 0, OrbitVaultStore.count(context));
        assertEquals("not even a stray file", 0, mediaFileCount());
    }

    /** Switching it off keeps everything already saved, and switching it on returns all of it. */
    @Test public void thedisabledStateIsPreservedAndReversible() {
        OrbitVaultItem page = savedPage();
        OrbitVaultStore.saveText(context, "Kept", "words", OrbitVaultSource.SELECTED_TEXT);
        assertEquals(2, OrbitVaultStore.count(context));

        Prefs.get(context).edit().putBoolean(Prefs.VAULT_ENABLED, false).commit();
        assertEquals("nothing is deleted by the switch", 2, OrbitVaultStore.count(context));
        assertTrue("including the page's rendering", new File(page.mediaPath).isFile());

        Prefs.get(context).edit().putBoolean(Prefs.VAULT_ENABLED, true).commit();
        OrbitVaultItem back = OrbitVaultStore.get(context, page.id);
        assertNotNull(back);
        assertEquals(7, back.pageNumber());
        assertEquals("Health behavior theory", back.documentName);
    }

    // ---- compatibility and backup --------------------------------------------------------------------

    /** A Beta 1 document, with no note and no page fields, loads unchanged. */
    @Test public void abetaOneStoreStillLoads() {
        String legacy = "[{\"id\":\"a\",\"type\":\"text\",\"title\":\"Old note\",\"body\":\"kept\","
                + "\"source\":\"Quick Capture\",\"mediaPath\":\"\",\"createdAt\":1700000000000,"
                + "\"modifiedAt\":1700000000000}]";
        assertTrue(OrbitVaultStore.restoreBackupJson(context, legacy));
        List<OrbitVaultItem> all = OrbitVaultStore.list(context);
        assertEquals(1, all.size());
        assertEquals("Old note", all.get(0).title);
        assertFalse("a missing note reads as an empty one", all.get(0).hasNote());
        assertFalse("and a missing page is not a page", all.get(0).isDocumentPage());
        assertEquals("", all.get(0).documentName);
        assertEquals(0, all.get(0).pageIndex);
    }

    /** A Beta 2 document, with a note, loads with the note intact. */
    @Test public void abetaTwoStoreStillLoads() {
        String beta2 = "[{\"id\":\"b\",\"type\":\"link\",\"title\":\"\","
                + "\"body\":\"https://example.com/x\",\"source\":\"Shared to Orbit\","
                + "\"note\":\"read later\",\"mediaPath\":\"\",\"createdAt\":1700000000001,"
                + "\"modifiedAt\":1700000000001}]";
        assertTrue(OrbitVaultStore.restoreBackupJson(context, beta2));
        OrbitVaultItem item = OrbitVaultStore.list(context).get(0);
        assertEquals("read later", item.note);
        assertTrue(item.isLink());
    }

    /** A saved page survives being written to disk and read back. */
    @Test public void asavedPageRoundTripsThroughTheStore() {
        OrbitVaultItem page = savedPage();
        String stored = OrbitVaultStore.backupJson(context);
        assertTrue(stored.contains("\"documentName\":\"Health behavior theory\""));
        assertTrue(stored.contains("\"pageIndex\":6"));
        assertTrue(stored.contains("\"pageCount\":388"));

        assertTrue(OrbitVaultStore.restoreBackupJson(context, stored));
        OrbitVaultItem back = OrbitVaultStore.get(context, page.id);
        assertNotNull(back);
        assertEquals("Health behavior theory", back.documentName);
        assertEquals(6, back.pageIndex);
        assertEquals(388, back.pageCount);
        assertEquals(page.body, back.body);
    }

    /** Page fields are written only for pages, so an ordinary Vault is the document Beta 2 wrote. */
    @Test public void ordinaryItemsCarryNoPageFields() {
        OrbitVaultStore.saveText(context, "Plain", "text", OrbitVaultSource.QUICK_CAPTURE);
        String stored = OrbitVaultStore.backupJson(context);
        assertFalse(stored.contains("documentName"));
        assertFalse(stored.contains("pageIndex"));
        assertFalse(stored.contains("pageCount"));
    }

    /** A hand-edited store cannot give an ordinary note a page number to claim. */
    @Test public void pageFieldsAreIgnoredOnEveryOtherType() {
        String tampered = "[{\"id\":\"c\",\"type\":\"text\",\"title\":\"Note\",\"body\":\"hello\","
                + "\"source\":\"Quick Capture\",\"documentName\":\"Someone's book\","
                + "\"pageIndex\":42,\"pageCount\":99,\"mediaPath\":\"\","
                + "\"createdAt\":1700000000002,\"modifiedAt\":1700000000002}]";
        assertTrue(OrbitVaultStore.restoreBackupJson(context, tampered));
        OrbitVaultItem item = OrbitVaultStore.list(context).get(0);
        assertEquals("", item.documentName);
        assertEquals(0, item.pageIndex);
        assertEquals(0, item.pageCount);
        assertEquals("", item.pageLabel());
    }

    /** A page claiming an impossible page number is bounded rather than believed. */
    @Test public void animpossiblePageNumberIsBounded() {
        OrbitVaultItem page = OrbitVaultStore.saveDocumentPage(context, "Short", 900, 3,
                "text", null, "");
        assertNotNull(page);
        assertEquals("a page cannot be past the end of its own document", 2, page.pageIndex);
        assertEquals("Page 3 of 3", page.pageLabel());
    }

    /** A saved page is findable by its document's name and by its page number. */
    @Test public void asavedPageIsSearchableByDocumentAndPage() {
        savedPage();
        assertEquals(1, OrbitVaultStore.search(context, "health behavior",
                OrbitVaultStore.Sort.NEWEST).size());
        assertEquals(1, OrbitVaultStore.search(context, "page 7",
                OrbitVaultStore.Sort.NEWEST).size());
        assertEquals(0, OrbitVaultStore.search(context, "page 8",
                OrbitVaultStore.Sort.NEWEST).size());
    }

    // ---- privacy -------------------------------------------------------------------------------------

    /** Nothing about the new routes made the Vault automatic or gave it a request path. */
    @Test public void thenewRoutesStayExplicitAndInert() {
        for (String name : new String[]{"OrbitVaultSource", "OrbitVaultAttachment",
                "OrbitVaultStore", "OrbitVaultItem"}) {
            String text = source(name);
            for (String forbidden : new String[]{"AssistantClient", "ChatGptClient", "AutoRouter",
                    "OrbitRequestManager", "PendingRequestStore", "HttpURLConnection", "java.net.",
                    "MemoryStore", "RoutineStore", "DeckLayoutStore"}) {
                assertFalse(name + " must not reference " + forbidden, text.contains(forbidden));
            }
        }
        String selection = source("ScreenSelectionActivity");
        assertTrue("the crop editor saves only from a control",
                selection.contains("saveSelectionToVault"));
        assertFalse("never on its own from completing a selection",
                selection.contains("saveImage(this, renderedResult"));
        assertFalse("and never runs text recognition", selection.contains("TextRecognizer")
                || selection.contains("OCR"));
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private Activity itemScreen(OrbitVaultItem item) {
        Intent intent = new Intent(context, OrbitVaultItemActivity.class)
                .putExtra(OrbitVaultItemActivity.EXTRA_ITEM_ID, item.id);
        return Robolectric.buildActivity(OrbitVaultItemActivity.class, intent).setup().get();
    }

    private Activity screenSelectionEditor() {
        String path = ScreenSelectionStore.saveSource(context, picture());
        assertFalse("a source image is needed to open the editor", path.isEmpty());
        Intent intent = ScreenSelectionStore.editorIntent(context, path, "", "", "", null);
        intent.setClass(context, ScreenSelectionActivity.class);
        return Robolectric.buildActivity(ScreenSelectionActivity.class, intent).setup().get();
    }

    private int mediaFileCount() {
        File[] files = OrbitVaultMedia.directory(context).listFiles();
        return files == null ? 0 : files.length;
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

    /** Finds a clickable view by content description, or by the text on it when that is null. */
    private static View findClickableWithDescription(View view, String description, String text) {
        if (view.isClickable()) {
            if (description != null) {
                CharSequence actual = view.getContentDescription();
                if (actual != null && description.contentEquals(actual)) return view;
            }
            if (text != null && view instanceof TextView) {
                CharSequence actual = ((TextView) view).getText();
                if (actual != null && text.contentEquals(actual)) return view;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findClickableWithDescription(group.getChildAt(i), description, text);
                if (found != null) return found;
            }
        }
        return null;
    }
}
