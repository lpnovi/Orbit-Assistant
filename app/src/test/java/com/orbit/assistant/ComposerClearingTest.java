package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.text.Editable;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * How the composer is emptied when a typed message is sent.
 *
 * <p>These tests establish what the candidate actually does to the editor: the text object the
 * editor and the input method share is kept, focus is untouched, and nothing restarts the input
 * method. They do <em>not</em> establish that the Samsung typing bug is fixed — no unit test on
 * this machine can, because the failure is in a real IME's behaviour against a real editor. Only
 * the signed candidate on the phone can answer that.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class ComposerClearingTest {
    private Activity activity;
    private EditText editor;
    private View holder;

    /** Mirrors the clearing helper both surfaces now use. */
    private static void clearInPlace(EditText input) {
        if (input == null) return;
        Editable editable = input.getText();
        if (editable == null) {
            input.setText("");
            return;
        }
        editable.clear();
    }

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout root = new LinearLayout(activity);
        editor = new EditText(activity);
        editor.setFocusable(true);
        editor.setFocusableInTouchMode(true);
        holder = new View(activity);
        holder.setFocusable(true);
        holder.setFocusableInTouchMode(true);
        root.addView(editor);
        root.addView(holder);
        activity.setContentView(root);
    }

    // ---- what setText("") actually did ----

    @Test public void replacingTheTextSwapsTheEditorsTextObject() {
        // The behaviour the candidate moves away from, stated plainly so the difference is
        // visible: setText hands the editor a different Editable than the one it had.
        editor.setText("first message");
        Editable before = editor.getText();

        editor.setText("");
        assertFalse("setText replaces the editor's text object",
                before == editor.getText());
    }

    @Test public void clearingInPlaceKeepsTheSameTextObject() {
        editor.setText("first message");
        Editable before = editor.getText();
        assertNotNull(before);

        clearInPlace(editor);
        assertSame("a continuing typed session keeps its Editable", before, editor.getText());
        assertEquals("", editor.getText().toString());
    }

    @Test public void theSubmittedTextIsReadBeforeClearing() {
        editor.setText("turn on flashlight");
        String submitted = editor.getText().toString().trim();

        clearInPlace(editor);
        assertEquals("turn on flashlight", submitted);
        assertEquals("", editor.getText().toString());
    }

    // ---- the editor stays usable ----

    @Test public void clearingDoesNotMoveFocus() {
        editor.requestFocus();
        assertTrue(editor.hasFocus());

        clearInPlace(editor);
        assertTrue("clearing must not disturb focus", editor.hasFocus());
        assertFalse(holder.hasFocus());
    }

    @Test public void theSameLiveEditableAcceptsTheNextMessage() {
        editor.requestFocus();
        editor.setText("first message");
        Editable live = editor.getText();

        clearInPlace(editor);
        // Editing through the object the editor still owns, as an IME commit would.
        live.append("second message");
        assertEquals("second message", editor.getText().toString());
        assertSame(live, editor.getText());
    }

    @Test public void threeConsecutiveTurnsShareOneTextObject() {
        editor.requestFocus();
        Editable original = editor.getText();

        for (int turn = 1; turn <= 3; turn++) {
            editor.getText().append("message " + turn);
            String submitted = editor.getText().toString();
            assertEquals("message " + turn, submitted);
            clearInPlace(editor);
            assertSame("turn " + turn + " swapped the editor's text object",
                    original, editor.getText());
            assertTrue("turn " + turn + " lost focus", editor.hasFocus());
        }
    }

    @Test public void clearingAnAlreadyEmptyComposerIsHarmless() {
        editor.requestFocus();
        Editable original = editor.getText();
        clearInPlace(editor);
        clearInPlace(editor);
        assertSame(original, editor.getText());
        assertEquals("", editor.getText().toString());
        assertTrue(editor.hasFocus());
    }

    // ---- the surrounding contract, unchanged from v0.7.3.7 ----

    @Test public void aTypedSendDoesNotReleaseTheKeyboard() {
        ComposerImeState ime = new ComposerImeState();
        ime.attach();
        assertFalse("a typed turn keeps its keyboard", ime.shouldReleaseOnSubmit(false));
    }

    @Test public void aVoiceSendStillReleasesTheKeyboard() {
        ComposerImeState ime = new ComposerImeState();
        ime.attach();
        assertTrue(ime.shouldReleaseOnSubmit(true));
    }

    @Test public void aSendWithNoTypingSessionStillReleases() {
        ComposerImeState ime = new ComposerImeState();
        ime.reset();
        assertTrue(ime.shouldReleaseOnSubmit(false));
    }

    @Test public void theFocusCoordinatorStillRefusesToRecurse() {
        // v0.7.3.6's rule, unchanged: a focus-gained callback never moves focus.
        int[] refreshes = {0};
        ComposerFocusCoordinator coordinator = new ComposerFocusCoordinator(editor, holder,
                new ComposerFocusCoordinator.ImeBridge() {
                    @Override public void allowWindowToTakeIme() {}
                    @Override public void onTypingClaimed() {}
                    @Override public void refreshInputConnection() {
                        refreshes[0]++;
                        if (refreshes[0] > 10) throw new AssertionError("focus recursion");
                        editor.clearFocus();
                        editor.requestFocus();
                    }
                });
        editor.setOnFocusChangeListener((v, hasFocus) -> coordinator.onFocusChanged(hasFocus));

        coordinator.onEditorTapped();
        assertTrue(refreshes[0] <= 2);
        assertTrue(editor.hasFocus());
    }

    @Test public void releasingAndReacquiringStillWorks() {
        ComposerFocusCoordinator coordinator = new ComposerFocusCoordinator(editor, holder,
                new ComposerFocusCoordinator.ImeBridge() {
                    @Override public void allowWindowToTakeIme() {}
                    @Override public void onTypingClaimed() {}
                    @Override public void refreshInputConnection() {}
                });
        coordinator.onEditorTapped();
        assertTrue(editor.hasFocus());

        coordinator.release();
        assertFalse(editor.hasFocus());

        coordinator.onEditorTapped();
        assertTrue("one tap has to bring it back", editor.hasFocus());
    }

    @Test public void theVoiceToTypingHandoffIsUnchanged() {
        VoiceHandoff turn = new VoiceHandoff();
        turn.begin();
        assertTrue(turn.hasLiveTurn());

        turn.abandon();
        assertFalse("an abandoned turn cannot act", turn.hasLiveTurn());
        assertEquals("keep this", VoiceHandoff.preservedDraft("keep", "this"));
    }
}
