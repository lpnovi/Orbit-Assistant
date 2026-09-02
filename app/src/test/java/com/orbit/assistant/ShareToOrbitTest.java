package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Parcelable;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Share to Orbit: what arrives, what is refused, and what is emphatically not done with it.
 *
 * <p>This is Orbit's only exported surface that reads content from another app, so half of these
 * cases are about hostile or malformed input costing a rejection rather than a crash or a partial
 * import. The other half is about the promise the feature makes: shared material is staged in a
 * composer and <em>waits</em>. Nothing is sent, no prompt is invented, and nothing shared is ever
 * treated as an instruction to Orbit.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class ShareToOrbitTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        DiagnosticStore.prefs(context).edit().clear().commit();
        ConversationStore.clear(context);
        SharedContentStore.clear();
        drain();
    }

    @After public void tearDown() {
        SharedContentStore.clear();
    }

    // ---- text ---------------------------------------------------------------------------------------

    /** Shared text lands in the composer, unsent, exactly as it was written. */
    @Test public void sharedTextIsStagedForTheComposer() {
        SharedContentStore.Staged staged = share(sendText("Look at this paragraph."));
        assertNotNull(staged);
        assertEquals("Look at this paragraph.", staged.text);
        assertTrue(staged.uris.isEmpty());
        assertEquals("text", staged.shape);
    }

    /** A URL is text and is preserved character for character. */
    @Test public void asharedUrlIsPreservedExactly() {
        String url = "https://example.com/a/b?c=1&d=2#frag";
        assertEquals(url, share(sendText(url)).text);
    }

    /**
     * Orbit does not decide what the user wanted to ask.
     *
     * <p>No "Summarize this", no "What is this", no instruction of any kind is prepended: the
     * composer holds what was shared and the user writes the question.
     */
    @Test public void nothingIsPrependedToSharedText() {
        String staged = share(sendText("https://example.com/article")).text;
        assertEquals("https://example.com/article", staged);
        assertFalse(staged.toLowerCase(java.util.Locale.US).contains("summarize"));
    }

    /** Shared text obeys the same ceiling clipboard text does. */
    @Test public void anEnormousSharedTextIsTruncatedRatherThanRefused() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 40000; i++) huge.append('x');
        SharedContentStore.Staged staged = share(sendText(huge.toString()));
        assertNotNull(staged);
        assertTrue(staged.text.length() < huge.length());
        assertTrue(staged.text.contains("truncated"));
    }

    // ---- images -------------------------------------------------------------------------------------

    /** One shared image becomes one attachment. */
    @Test public void oneSharedImageIsOneAttachment() {
        Intent intent = new Intent(Intent.ACTION_SEND).setType("image/jpeg");
        intent.putExtra(Intent.EXTRA_STREAM, (Parcelable) uri("photo"));

        SharedContentStore.Staged staged = share(intent);
        assertNotNull(staged);
        assertEquals(java.util.Collections.singletonList(uri("photo")), staged.uris);
        assertEquals("item", staged.shape);
    }

    /**
     * The real-device case: four photos selected in Samsung Gallery and shared to Orbit.
     *
     * <p>All four, in the order the sending app listed them, staged in one unsent composer.
     */
    @Test public void fourSharedImagesAllArriveInOrder() {
        SharedContentStore.Staged staged = share(sendMultiple(
                uri("a"), uri("b"), uri("c"), uri("d")));
        assertNotNull(staged);
        assertEquals(Arrays.asList(uri("a"), uri("b"), uri("c"), uri("d")), staged.uris);
        assertEquals("items", staged.shape);
    }

    /**
     * A sender may populate EXTRA_STREAM and ClipData with overlapping sets.
     *
     * <p>Reading both without deduplicating attaches the same photo twice; reading one loses
     * photos. Both fields are read, and the result is each photo once.
     */
    @Test public void thesameUriInTwoFieldsBecomesOneAttachment() {
        Intent intent = sendMultiple(uri("a"), uri("b"));
        intent.setClipData(clip(uri("a"), uri("c")));

        assertEquals(Arrays.asList(uri("a"), uri("b"), uri("c")), share(intent).uris);
    }

    /** Text and images together is a legitimate share and both halves survive. */
    @Test public void amixedShareKeepsBothHalves() {
        Intent intent = sendMultiple(uri("a"), uri("b"));
        intent.putExtra(Intent.EXTRA_TEXT, "Which of these is sharper?");

        SharedContentStore.Staged staged = share(intent);
        assertEquals("Which of these is sharper?", staged.text);
        assertEquals(2, staged.uris.size());
        assertEquals("mixed", staged.shape);
    }

    /** The one central limit governs Share exactly as it governs Gallery. */
    @Test public void tooManySharedItemsAreCappedAndCounted() {
        List<Uri> fifteen = new ArrayList<>();
        for (int i = 0; i < 15; i++) fifteen.add(uri("photo" + i));

        SharedContentStore.Staged staged = share(sendMultiple(fifteen.toArray(new Uri[0])));
        assertEquals(ComposerAttachments.MAX_PER_TURN, staged.uris.size());
        assertEquals("the user is told how many were offered", 15, staged.offered);
        assertEquals(uri("photo0"), staged.uris.get(0));
    }

    // ---- refusal ------------------------------------------------------------------------------------

    /** An action Orbit does not handle is refused rather than half-interpreted. */
    @Test public void anUnsupportedActionIsRefused() {
        assertNull(share(new Intent(Intent.ACTION_VIEW).setType("image/jpeg")));
        assertNull(share(new Intent(Intent.ACTION_MAIN)));
    }

    /** A MIME type AttachmentLoader cannot read carries no streams into the composer. */
    @Test public void anUnsupportedMimeTypeCarriesNoStreams() {
        Intent intent = new Intent(Intent.ACTION_SEND).setType("application/vnd.example.binary");
        intent.putExtra(Intent.EXTRA_STREAM, (Parcelable) uri("blob"));
        assertNull("nothing readable and no text means nothing to stage", share(intent));
    }

    /** A share with a type but nothing in it is refused, not staged as an empty message. */
    @Test public void anEmptyShareIsRefused() {
        assertNull(share(new Intent(Intent.ACTION_SEND).setType("text/plain")));
        Intent blank = new Intent(Intent.ACTION_SEND).setType("text/plain");
        blank.putExtra(Intent.EXTRA_TEXT, "    ");
        assertNull(share(blank));
    }

    /** A malformed stream extra costs the item, never the process. */
    @Test public void amalformedStreamExtraIsSurvived() {
        Intent intent = new Intent(Intent.ACTION_SEND).setType("image/jpeg");
        // Not a Uri at all: an app may put anything under this key.
        intent.putExtra(Intent.EXTRA_STREAM, "not-a-uri");
        intent.putExtra(Intent.EXTRA_TEXT, "still fine");

        SharedContentStore.Staged staged = share(intent);
        assertNotNull("the text half still arrives", staged);
        assertEquals("still fine", staged.text);
        assertTrue(staged.uris.isEmpty());
    }

    /** A list of the wrong things yields the URIs and ignores the rest. */
    @Test public void amixedParcelableListKeepsOnlyTheUris() {
        Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*");
        ArrayList<Parcelable> items = new ArrayList<>();
        items.add(uri("good"));
        items.add(new Intent("com.example.NOT_A_URI"));
        items.add(uri("also-good"));
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, items);

        assertEquals(Arrays.asList(uri("good"), uri("also-good")), share(intent).uris);
    }

    /**
     * A shared file path is refused.
     *
     * <p>A {@code file://} URI is not something the sender proved it may read, and honouring one
     * would let any installed app aim Orbit's own reader at an arbitrary path.
     */
    @Test public void asharedFilePathIsRefused() {
        Intent intent = new Intent(Intent.ACTION_SEND).setType("image/jpeg");
        intent.putExtra(Intent.EXTRA_STREAM,
                (Parcelable) Uri.parse("file:///data/data/com.orbit.assistant/shared_prefs/x.xml"));
        assertNull(share(intent));
    }

    // ---- what happens next ---------------------------------------------------------------------------

    /**
     * A share opens the full conversation with a real Chats stack behind it.
     *
     * <p>Not the Side-button overlay, and not a conversation whose Back returns to whichever app
     * did the sharing: Back goes to Chats, because that is where the user now is.
     */
    @Test public void ashareOpensTheConversationWithAChatsStackBehindIt() {
        runShare(sendText("hello"));
        List<Intent> started = allStarted();
        assertEquals("one stack, started at once", 2, started.size());
        assertEquals(MainActivity.class.getName(),
                started.get(0).getComponent().getClassName());
        assertEquals(ChatActivity.class.getName(),
                started.get(1).getComponent().getClassName());
        assertTrue("Chats is reused rather than duplicated behind every share",
                (started.get(0).getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0
                        && (started.get(0).getFlags() & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0);
    }

    /** A share never lands in an unrelated existing conversation. */
    @Test public void ashareOpensItsOwnNewConversation() {
        ConversationStore.save(context, "c-existing", new ArrayList<>(
                java.util.Collections.singletonList(
                        new AssistantClient.History("user", "unrelated work in progress"))));

        runShare(sendText("shared material"));
        String conversationId = allStarted().get(1)
                .getStringExtra(ChatActivity.EXTRA_CONVERSATION_ID);
        assertNotNull(conversationId);
        assertFalse("shared content must not be injected into an unrelated chat",
                "c-existing".equals(conversationId));
    }

    /** The read grant is handed forward, so the composer can still open the photos. */
    @Test public void thereadGrantTravelsWithTheConversationIntent() {
        runShare(sendMultiple(uri("a"), uri("b")));
        Intent chat = allStarted().get(1);
        assertTrue((chat.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        assertNotNull(chat.getClipData());
        assertEquals(2, chat.getClipData().getItemCount());
    }

    /** Nothing larger than a URI crosses the Binder: no bitmaps, no bytes, in an extra. */
    @Test public void nothingLargeIsPutInTheIntent() {
        runShare(sendMultiple(uri("a"), uri("b"), uri("c")));
        Intent chat = allStarted().get(1);
        String token = chat.getStringExtra(ChatActivity.EXTRA_SHARE_TOKEN);
        assertNotNull("the content travels as a small private token", token);
        assertTrue(token.startsWith("share-"));
    }

    /** Nothing is sent. A share stages a composer and stops. */
    @Test public void ashareNeverSubmitsAnything() {
        runShare(sendText("please do not send this"));
        String conversationId = allStarted().get(1)
                .getStringExtra(ChatActivity.EXTRA_CONVERSATION_ID);

        assertNull("no conversation may be written by a share alone",
                ConversationStore.load(context, conversationId));
        assertFalse(PendingRequestStore.hasActiveForConversation(context, conversationId));
    }

    // ---- the staging token ------------------------------------------------------------------------------

    /** A token is spent as it is read, so a recreated screen cannot apply the same share twice. */
    @Test public void atokenIsOneShot() {
        String token = SharedContentStore.stage("hello", new ArrayList<>(), "text", 0);
        assertNotNull(SharedContentStore.consume(token));
        assertNull("a share must never be replayed", SharedContentStore.consume(token));
    }

    @Test public void anUnknownTokenResolvesToNothing() {
        assertNull(SharedContentStore.consume(null));
        assertNull(SharedContentStore.consume(""));
        assertNull(SharedContentStore.consume("share-never-issued"));
    }

    /** Abandoned shares do not pile up: the store is bounded. */
    @Test public void abandonedSharesAreBounded() {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tokens.add(SharedContentStore.stage("text " + i, new ArrayList<>(), "text", 0));
        }
        int alive = 0;
        for (String token : tokens) if (SharedContentStore.consume(token) != null) alive++;
        assertTrue("staged shares must not accumulate without limit", alive <= 5);
    }

    // ---- diagnostics -------------------------------------------------------------------------------------

    /** Diagnostics records a shape word and never the shared content. */
    @Test public void diagnosticsRecordsTheShapeAndNotTheContent() {
        runShare(sendText("https://secret.example.com/private-token-abc"));
        for (Object value : DiagnosticStore.prefs(context).getAll().values()) {
            if (!(value instanceof String)) continue;
            String stored = (String) value;
            assertFalse("shared text must never reach Diagnostics", stored.contains("secret"));
            assertFalse(stored.contains("private-token-abc"));
        }
        assertEquals("text", DiagnosticStore.prefs(context).getString("share_shape", ""));
    }

    /** The shape vocabulary is Orbit's own closed set. */
    @Test public void theshapeVocabularyIsClosed() {
        assertEquals("none", ShareToOrbitActivity.shapeOf("", 0));
        assertEquals("text", ShareToOrbitActivity.shapeOf("hello", 0));
        assertEquals("item", ShareToOrbitActivity.shapeOf("", 1));
        assertEquals("items", ShareToOrbitActivity.shapeOf("", 4));
        assertEquals("mixed", ShareToOrbitActivity.shapeOf("hello", 2));
    }

    // ---- the exported surface ------------------------------------------------------------------------------

    /**
     * Adding a share target adds an exported component, and that is the whole list.
     *
     * <p>Exported means any installed app can reach it, so the set is asserted rather than
     * reviewed: MainActivity (the launcher and assistant entry), the widget configuration screen
     * the launcher calls, this share doorway, the selected-text doorway v0.7.8.1 Beta 1 added
     * beside it, and the four services Android itself binds behind system permissions. A future
     * component that quietly becomes exported fails here.
     */
    @Test public void theShareTargetIsTheOnlyNewExportedComponent() {
        String manifest = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/AndroidManifest.xml");
        List<String> exported = new ArrayList<>();
        for (String block : manifest.split("<(activity|service|receiver|provider)")) {
            if (!block.contains("android:exported=\"true\"")) continue;
            int at = block.indexOf("android:name=\".");
            if (at < 0) continue;
            String name = block.substring(at + "android:name=\".".length());
            exported.add(name.substring(0, name.indexOf('"')));
        }
        java.util.Collections.sort(exported);
        assertEquals(Arrays.asList(
                "AskOrbitTileService",
                "MainActivity",
                "OrbitNotificationListenerService",
                "OrbitRoutineTileService",
                "OrbitSessionService",
                "OrbitVoiceInteractionService",
                "OrbitWidgetConfigureActivity",
                "ProcessTextToOrbitActivity",
                "ShareToOrbitActivity"), exported);
    }

    /**
     * The share filters promise only what AttachmentLoader can actually read.
     *
     * <p>No wildcard-everything filter: appearing in every share sheet and then rejecting most of
     * what arrives would be worse than not appearing at all.
     */
    @Test public void theShareFiltersClaimOnlySupportedTypes() {
        String manifest = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/AndroidManifest.xml");
        int start = manifest.indexOf("ShareToOrbitActivity");
        String block = manifest.substring(start, manifest.indexOf("</activity>", start));

        assertTrue(block.contains("android.intent.action.SEND"));
        assertTrue(block.contains("android.intent.action.SEND_MULTIPLE"));
        assertTrue(block.contains("android:mimeType=\"image/*\""));
        assertTrue(block.contains("android:mimeType=\"application/pdf\""));
        assertTrue(block.contains("android:mimeType=\"text/*\""));
        assertFalse("Orbit must not claim it can read every file type",
                block.contains("android:mimeType=\"*/*\""));
        assertTrue("a consumed share must not become a Recents card",
                block.contains("android:excludeFromRecents=\"true\""));
        assertTrue(block.contains("android:noHistory=\"true\""));
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    /** Runs the share bridge and returns what it staged, or null when it refused. */
    private SharedContentStore.Staged share(Intent intent) {
        runShare(intent);
        List<Intent> started = allStarted();
        if (started.isEmpty()) return null;
        String token = started.get(started.size() - 1)
                .getStringExtra(ChatActivity.EXTRA_SHARE_TOKEN);
        return token == null ? null : SharedContentStore.consume(token);
    }

    /** Every Intent the last share bridge started, in order. */
    private final List<Intent> lastStarted = new ArrayList<>();

    private void runShare(Intent intent) {
        drain();
        lastStarted.clear();
        intent.setComponent(new android.content.ComponentName(context, ShareToOrbitActivity.class));
        ActivityController<ShareToOrbitActivity> controller =
                Robolectric.buildActivity(ShareToOrbitActivity.class, intent).create();
        // The bridge starts its stack from the Activity, so the Activity's shadow is what records
        // it; the application shadow would report nothing at all.
        org.robolectric.shadows.ShadowActivity shadow = Shadows.shadowOf(controller.get());
        Intent next;
        while ((next = shadow.getNextStartedActivity()) != null) lastStarted.add(next);
        // The shadow hands them back newest-first; these assertions are about the stack Orbit
        // built, so they are put back into the order startActivities was given them in.
        java.util.Collections.reverse(lastStarted);
        controller.destroy();
    }

    private static Intent sendText(String text) {
        return new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text);
    }

    private static Intent sendMultiple(Uri... uris) {
        Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*");
        ArrayList<Parcelable> items = new ArrayList<>(Arrays.asList(uris));
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, items);
        return intent;
    }

    private static ClipData clip(Uri... uris) {
        ClipData clip = ClipData.newRawUri("test", uris[0]);
        for (int i = 1; i < uris.length; i++) clip.addItem(new ClipData.Item(uris[i]));
        return clip;
    }

    private static Uri uri(String id) {
        return Uri.parse("content://com.example.gallery/media/" + id);
    }

    private ShadowApplication shadowApp() {
        return Shadows.shadowOf((Application) RuntimeEnvironment.getApplication());
    }

    private void drain() {
        while (shadowApp().getNextStartedActivity() != null) { /* drained */ }
    }

    private List<Intent> allStarted() {
        return lastStarted;
    }
}
