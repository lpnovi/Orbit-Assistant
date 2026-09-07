package com.orbit.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.ArrayList;

/**
 * Ask Orbit, from the text-selection menu of any Android app.
 *
 * <p>Select a paragraph in a browser, a message, a document or a note, tap Ask Orbit, and the
 * selection arrives in a new Orbit composer, unsent, with the keyboard up and the cursor after it.
 * The user writes the question. That last part is the feature: Orbit deliberately does not decide
 * that a selected paragraph means "summarise this", because half the time it means "is this
 * actually true", and guessing wrong wastes a request and answers a question nobody asked.
 *
 * <p>A second narrow door beside {@link ShareToOrbitActivity} rather than a second architecture.
 * Both are exported, both read content from an app Orbit does not control, and both do exactly the
 * same five things with it: validate the Intent, bound what it carries, stage that under a private
 * one-shot token, open a real conversation with a Chats stack behind it, and finish. What differs
 * is only which Android contract delivered the text, which is recorded as source metadata and
 * changes nothing about how the text is treated. Chats and the conversation screen still never
 * parse an external Intent themselves.
 *
 * <p><b>This is Ask Orbit, not Replace with Orbit.</b> {@code ACTION_PROCESS_TEXT} lets a handler
 * return replacement text that the source app writes back over the user's selection, and Orbit
 * never does: no result is set, so the platform is told nothing was produced and the text in the
 * other app is left exactly as the user wrote it. {@code EXTRA_PROCESS_TEXT_READONLY} is read and
 * recorded, but it cannot change that, because Orbit does not write back either way.
 *
 * <p>And nothing is sent. The selection is untrusted data placed in a composer: it is not a
 * command, not a package name, not a path, not an Orbit action, and not an instruction. It only
 * becomes a question to a model if the user presses Send, at which point it travels the ordinary
 * path inside Orbit's untrusted-attachment framing like anything else they shared.
 *
 * <p><b>Two destinations from v0.7.8.4 Beta 3</b>, in exactly the shape Share to Orbit already
 * settled on. A selected paragraph now has two honest meanings - "ask Orbit about this" and "keep
 * this" - and Orbit has no way to tell which one the user had in mind. Adding a second entry to
 * Android's own selection menu was the alternative and it is worse: it would put two Orbit items in
 * every app's text menu, and the platform gives a handler no say in how those are ordered or
 * grouped. One Orbit-owned question, drawn by Orbit, with cancel as a real third answer, keeps the
 * selection menu at one entry and lets the user see both destinations at once.
 *
 * <p>With the Vault switched off there is no question to ask and no dialog at all: the selection
 * goes straight to a conversation exactly as it did before the Vault existed. Ask Orbit from a text
 * selection must keep working whatever else the user has turned off.
 */
public final class ProcessTextToOrbitActivity extends Activity {

    /** The two destinations offered for a selection, in the words Share to Orbit already uses. */
    static final String ASK_ORBIT = "Ask Orbit";
    static final String SAVE_TO_VAULT = "Save to Vault";

    /** True while Orbit's own choice of destination is on screen and this bridge must stay open. */
    private boolean awaitingChoice;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        // A configuration change must not stage the same selection twice: the first pass already
        // handed it to a conversation and this Activity has nothing left to do.
        if (state != null) { finish(); return; }
        try {
            handleSelection(getIntent());
        } catch (Exception failure) {
            // Malformed input costs the selection, never the process and never a partial import.
            DiagnosticStore.recordExternalText(this, SharedContentStore.SOURCE_PROCESS_TEXT,
                    0, "rejected");
            Toast.makeText(this, "Orbit could not read that selection", Toast.LENGTH_SHORT).show();
        }
        // Finished without a result, so the source app replaces nothing. This is the whole
        // read-only contract, and it is the default rather than a branch on purpose: there is no
        // path through this Activity that can set a replacement. The one thing that may hold this
        // open is Orbit's own question about where the selection should go, and every answer to it
        // finishes here too - still without ever setting a result.
        if (!awaitingChoice) finish();
    }

    private void handleSelection(Intent intent) {
        if (intent == null || !Intent.ACTION_PROCESS_TEXT.equals(intent.getAction())) {
            reject();
            return;
        }
        if (!isSupportedType(intent.getType())) { reject(); return; }

        String text = readSelection(intent);
        if (text.isEmpty()) { reject(); return; }

        // Read so Diagnostics can report what Android said, and used for nothing else. Orbit does
        // not offer to edit the source text whether or not it claims to be editable.
        boolean readOnly = readOnlyFlag(intent);

        String token = SharedContentStore.stage(SharedContentStore.SOURCE_PROCESS_TEXT,
                text, new ArrayList<>(), "text", 0);
        DiagnosticStore.recordExternalText(this, SharedContentStore.SOURCE_PROCESS_TEXT,
                text.length(), readOnly ? "staged-readonly" : "staged");

        // Always a new conversation, exactly as a share is. An unrelated draft the user has open
        // elsewhere is theirs and is not somewhere a selection from another app may land.
        Intent chat = new Intent(this, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, ConversationStore.newId())
                .putExtra(ChatActivity.EXTRA_SHARE_TOKEN, token)
                .putExtra(ChatActivity.EXTRA_FOCUS_COMPOSER, true);

        // Where this selection should go. Asking Orbit about it is still what happens unless the
        // user says otherwise, and it is still what happens with no question at all when the Vault
        // is off.
        if (Prefs.vaultEnabled(this)) {
            askDestination(text, token, chat);
            return;
        }
        openConversation(chat);
    }

    /**
     * Asks where the selection should land, and does nothing at all until the answer arrives.
     *
     * <p>Cancelling is a real third answer. The staged token is simply never spent, expires on its
     * own, and no conversation is created and no item is saved - which is what "I tapped the wrong
     * thing" should cost somebody in the middle of reading.
     */
    private void askDestination(String text, String token, Intent chat) {
        awaitingChoice = true;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Selected text")
                .setMessage("Ask Orbit about this, or keep it in your Vault. "
                        + "Nothing is sent to Orbit's AI until you send it.")
                .setNegativeButton(SAVE_TO_VAULT, (d, which) -> {
                    saveSelectionToVault(text, token);
                    finish();
                })
                .setPositiveButton(ASK_ORBIT, (d, which) -> {
                    openConversation(chat);
                    finish();
                })
                .setOnCancelListener(d -> finish())
                .create();
        UiKit.styleOrbitDialog(dialog, this, false);
        dialog.setCanceledOnTouchOutside(false);
        Window window = dialog.getWindow();
        if (window != null) {
            // The bridge itself draws nothing, so the dialog brings its own scrim rather than
            // floating over whichever app the text was selected in with no separation at all.
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.55f);
        }
        dialog.show();
    }

    /**
     * Keeps the exact selection as one Vault item, locally.
     *
     * <p>The exact characters the user selected: no normalising, no unwrapping, no rewriting. They
     * pointed at something specific, and an item holding a tidied version of it would be a
     * different quotation from the one they kept.
     *
     * <p>The staged token is spent here even though no composer ever sees it, so a selection that
     * went to the Vault cannot also be replayed into a conversation. Nothing is sent anywhere.
     */
    private void saveSelectionToVault(String text, String token) {
        SharedContentStore.consume(token);
        boolean saved = OrbitVaultStore.saveText(this, "", text,
                OrbitVaultSource.SELECTED_TEXT) != null;
        DiagnosticStore.recordExternalText(this, SharedContentStore.SOURCE_PROCESS_TEXT,
                text.length(), saved ? "saved-to-vault" : "vault-save-failed");
        Toast.makeText(this, saved ? "Saved to Vault" : "Orbit could not save that selection",
                Toast.LENGTH_SHORT).show();
    }

    private void openConversation(Intent chat) {
        // A real stack, so Back from the conversation goes to Chats rather than straight back to
        // the app the text was selected in. CLEAR_TOP with SINGLE_TOP is what stops a second copy
        // of Chats being created behind every selection.
        startActivities(OrbitNavigation.stackFor(this, chat));
    }

    /**
     * The types this doorway accepts, checked here as well as in the manifest.
     *
     * <p>The intent filter decides which menus Orbit appears in, and that is all it decides: this
     * Activity is exported, so any installed app can name the component directly and skip the
     * filter entirely. The gate therefore lives in the code too.
     *
     * <p>An absent type is accepted because Android's own selection menu is not required to set
     * one, and refusing that would break the feature on the platform it is built for. Anything
     * positively declared as something other than text is refused: Orbit reads a selection of
     * words here and nothing else.
     */
    static boolean isSupportedType(String type) {
        if (type == null || type.trim().isEmpty()) return true;
        return type.trim().toLowerCase(java.util.Locale.US).startsWith("text/");
    }

    /**
     * The selected text, taken as data and bounded.
     *
     * <p>Preserved exactly otherwise. A selection is the user pointing at something specific, and
     * a viewer that silently normalised its whitespace or unwrapped its lines would be answering
     * about text they did not select. Nothing here parses it, resolves it, fetches it, or decides
     * what it means.
     */
    static String readSelection(Intent intent) {
        CharSequence value;
        try {
            value = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        } catch (Exception ignored) {
            return "";
        }
        return SharedContentStore.bound(value == null ? "" : value.toString());
    }

    /** What the source app claimed about editability. Recorded, never acted on. */
    static boolean readOnlyFlag(Intent intent) {
        try {
            return intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void reject() {
        DiagnosticStore.recordExternalText(this, SharedContentStore.SOURCE_PROCESS_TEXT,
                0, "rejected");
        Toast.makeText(this, "Select some text to ask Orbit about", Toast.LENGTH_SHORT).show();
    }
}
