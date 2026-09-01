package com.orbit.assistant;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where Android delivers a Share to Orbit, and the only exported surface that reads one.
 *
 * <p>It exists so that untrusted external input has exactly one narrow door. Chats and the
 * conversation screen are Orbit's own screens with Orbit's own state; letting either of them parse
 * an Intent any installed app can construct would put hostile input next to everything they hold.
 * This Activity draws nothing, keeps nothing, and does five things: validate the Intent, collect
 * what it legitimately carries, stage that under a private token, open the real conversation with
 * a proper task stack behind it, and finish.
 *
 * <p><b>Nothing here is ever executed or interpreted.</b> Shared text is text: it is not a command,
 * not a package name, not a component name, not a file path, not an Orbit action, and not an
 * instruction. It is placed in the composer, unsent, and only becomes a question to a model if the
 * user themselves presses Send - at which point it travels the ordinary path and lands inside
 * Orbit's untrusted-attachment framing like any other content the user shared.
 *
 * <p>And nothing is ever sent automatically. A share opens a composer holding the shared material
 * and waits. Orbit does not prepend "Summarize this", does not pick a prompt, and does not call a
 * model: the user decides what to ask, or closes it and nothing happened.
 */
public final class ShareToOrbitActivity extends Activity {

    /** MIME types Orbit will accept from a share, matching what AttachmentLoader can really read. */
    private static boolean isSupportedStreamType(String type) {
        if (type == null) return false;
        String lower = type.toLowerCase(Locale.US).trim();
        if (lower.isEmpty()) return false;
        // Wildcards appear when a sender shares a mixed set. They are accepted at this gate and
        // resolved per item by AttachmentLoader, which reads each URI's real type.
        if (lower.equals("*/*")) return true;
        return lower.startsWith("image/")
                || lower.startsWith("text/")
                || lower.equals("application/pdf")
                || lower.equals("application/json")
                || lower.equals("application/xml");
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        // A configuration change must not re-stage the same share: the first pass already handed
        // it to a conversation, and this Activity has nothing left to do.
        if (state != null) { finish(); return; }
        try {
            handleShare(getIntent());
        } catch (Exception failure) {
            // A malformed share is a rejected share, never a crash and never a partial import.
            DiagnosticStore.recordShareToOrbit(this, "none", "rejected", 0);
            Toast.makeText(this, "Orbit could not read what was shared", Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    private void handleShare(Intent intent) {
        if (intent == null) { reject(); return; }
        String action = intent.getAction();
        boolean single = Intent.ACTION_SEND.equals(action);
        boolean multiple = Intent.ACTION_SEND_MULTIPLE.equals(action);
        if (!single && !multiple) { reject(); return; }

        String declaredType = intent.getType();
        String text = readSharedText(intent);
        List<Uri> uris = isSupportedStreamType(declaredType)
                ? AttachmentUriCollector.fromShare(intent)
                : new ArrayList<>();

        if (text.isEmpty() && uris.isEmpty()) { reject(); return; }

        int offered = uris.size();
        // Orbit's one per-message limit, the same number Gallery obeys. Anything past it is
        // dropped here rather than being carried into a composer that cannot hold it, and the user
        // is told how many arrived.
        if (uris.size() > ComposerAttachments.MAX_PER_TURN) {
            uris = new ArrayList<>(uris.subList(0, ComposerAttachments.MAX_PER_TURN));
        }

        String shape = shapeOf(text, offered);
        String token = SharedContentStore.stage(text, uris, shape, offered);
        DiagnosticStore.recordShareToOrbit(this, shape, "staged", uris.size());

        String conversationId = ConversationStore.newId();
        Intent chat = new Intent(this, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, conversationId)
                .putExtra(ChatActivity.EXTRA_SHARE_TOKEN, token)
                .putExtra(ChatActivity.EXTRA_FOCUS_COMPOSER, true);

        // The read permission a share carries belongs to this Activity and can end when it does.
        // Re-granting the exact URIs onto the outgoing Intent, as ClipData, is the platform's own
        // way to hand a grant forward, so the composer can still read the photos after this bridge
        // has finished. Nothing larger than a URI crosses the Binder here.
        if (!uris.isEmpty()) {
            ClipData clip = ClipData.newRawUri("orbit_shared", uris.get(0));
            for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
            chat.setClipData(clip);
            chat.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }

        // A real stack, so Back from the shared conversation goes to Chats rather than straight
        // back to whichever app did the sharing. CLEAR_TOP with SINGLE_TOP on Chats is what stops
        // a second copy of it being created behind every share.
        startActivities(OrbitNavigation.stackFor(this, chat));
    }

    /**
     * The shared text, bounded and taken as data.
     *
     * <p>A URL shared as text is text and is preserved exactly, character for character, because
     * the user may well want to ask about that precise link. Nothing here parses it, resolves it,
     * fetches it, or decides what it means.
     */
    private String readSharedText(Intent intent) {
        CharSequence value;
        try {
            value = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        } catch (Exception ignored) {
            return "";
        }
        if (value == null) return "";
        String text = value.toString().trim();
        if (text.isEmpty()) return "";
        // The same ceiling clipboard text obeys, so one path cannot be used to push more into a
        // composer than the other allows.
        if (text.length() > 36000) {
            text = text.substring(0, 36000)
                    + "\n\n[Orbit truncated the shared text after 36,000 characters.]";
        }
        return text;
    }

    /** Orbit's own shape word for Diagnostics. Never the content, never the sender. */
    static String shapeOf(String text, int streamCount) {
        boolean hasText = text != null && !text.trim().isEmpty();
        if (streamCount <= 0) return hasText ? "text" : "none";
        if (hasText) return "mixed";
        return streamCount == 1 ? "item" : "items";
    }

    private void reject() {
        DiagnosticStore.recordShareToOrbit(this, "none", "rejected", 0);
        Toast.makeText(this, "Orbit can accept shared text, images, PDFs and text files",
                Toast.LENGTH_SHORT).show();
    }
}
