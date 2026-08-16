package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * "Start listening when overlay opens": the preference itself, and the overlay lifecycle rules
 * that decide whether a given show may begin the first voice turn.
 *
 * <p>The lifecycle cases drive a small stand-in for {@code OrbitSession}'s guard transitions
 * rather than a live {@code VoiceInteractionSession}, so the rules can be checked without a real
 * {@code SpeechRecognizer}. The stand-in mirrors the real session exactly: the guard is cleared
 * when a new invocation is prepared and when the sheet hides, and set when a show decides to
 * start.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class OverlayAutoListenTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    /**
     * Mirrors OrbitSession's per-invocation guard: onPrepareShow clears it for a genuinely new
     * invocation, onShow consults OverlayAutoListen and sets it, onHide clears it again.
     */
    private static final class SessionLifecycle {
        private boolean guard = false;
        int listeningStarts = 0;

        /** A fresh external invocation being prepared (side button, assistant gesture). */
        void prepareFreshShow() { guard = false; }

        /** Orbit resuming its own session; onPrepareShow returns before touching the guard. */
        void prepareInternalResume() { }

        void show(boolean enabled, boolean internalResume) {
            boolean start = OverlayAutoListen.shouldStartForShow(enabled, internalResume, guard);
            if (start) {
                guard = true;
                listeningStarts++;
            }
        }

        void hide() { guard = false; }
    }

    // --- Preference ---------------------------------------------------------------------

    @Test public void theSettingDefaultsOff() {
        assertFalse(Prefs.autoListenOnOpen(context));
    }

    @Test public void theSettingPersistsBothWays() {
        Prefs.get(context).edit().putBoolean(Prefs.AUTO_LISTEN_ON_OPEN, true).commit();
        assertTrue(Prefs.autoListenOnOpen(context));
        Prefs.get(context).edit().putBoolean(Prefs.AUTO_LISTEN_ON_OPEN, false).commit();
        assertFalse(Prefs.autoListenOnOpen(context));
    }

    @Test public void theSettingDoesNotReuseTheHandsFreeKey() {
        // Hands-free follow-ups must keep their own storage and their own behaviour.
        assertFalse(Prefs.AUTO_LISTEN_ON_OPEN.equals(Prefs.AUTO_LISTEN));
        Prefs.get(context).edit().putBoolean(Prefs.AUTO_LISTEN_ON_OPEN, true).commit();
        assertFalse(Prefs.autoListen(context));
    }

    // --- Backup & Restore ---------------------------------------------------------------

    @Test public void theSettingSurvivesBackupAndRestore() throws Exception {
        Prefs.get(context).edit().putBoolean(Prefs.AUTO_LISTEN_ON_OPEN, true).commit();
        JSONObject snapshot = Prefs.backupSnapshot(context);
        assertTrue(snapshot.getBoolean(Prefs.AUTO_LISTEN_ON_OPEN));

        Prefs.get(context).edit().clear().commit();
        assertFalse(Prefs.autoListenOnOpen(context));

        assertTrue(Prefs.restoreBackupSnapshot(context, snapshot));
        assertTrue(Prefs.autoListenOnOpen(context));
    }

    @Test public void olderBackupsWithoutTheNewKeyStillRestore() throws Exception {
        // A backup written before v0.7.3.3 simply has no entry for the new key.
        Prefs.get(context).edit()
                .putBoolean(Prefs.AUTO_LISTEN, true)
                .putBoolean(Prefs.SPEAK, false)
                .commit();
        JSONObject legacy = Prefs.backupSnapshot(context);
        assertFalse(legacy.has(Prefs.AUTO_LISTEN_ON_OPEN));

        Prefs.get(context).edit().clear().commit();
        assertTrue(Prefs.validBackupSnapshot(legacy));
        assertTrue(Prefs.restoreBackupSnapshot(context, legacy));

        assertTrue(Prefs.autoListen(context));
        assertFalse(Prefs.speak(context));
        // Absent from the backup means the new setting lands on its safe default.
        assertFalse(Prefs.autoListenOnOpen(context));
    }

    // --- Overlay lifecycle --------------------------------------------------------------

    @Test public void aFreshOverlayDoesNotListenWhenTheSettingIsOff() {
        SessionLifecycle session = new SessionLifecycle();
        session.prepareFreshShow();
        session.show(false, false);
        assertEquals(0, session.listeningStarts);
    }

    @Test public void aFreshOverlayListensExactlyOnceWhenTheSettingIsOn() {
        SessionLifecycle session = new SessionLifecycle();
        session.prepareFreshShow();
        session.show(true, false);
        assertEquals(1, session.listeningStarts);
    }

    @Test public void returningFromScreenSelectionDoesNotStartListening() {
        SessionLifecycle session = new SessionLifecycle();
        session.prepareFreshShow();
        session.show(true, false);
        assertEquals(1, session.listeningStarts);

        // Screen Selection hides this same session and returns through the internal path.
        session.hide();
        session.prepareInternalResume();
        session.show(true, true);
        assertEquals("Returning from Screen Selection is not a new invocation",
                1, session.listeningStarts);
    }

    @Test public void returningFromScreenSelectionWhileNotListeningStaysSilent() {
        // The user never started a voice turn before Screen Selection, so coming back must not
        // suddenly begin one merely because the preference is enabled.
        SessionLifecycle session = new SessionLifecycle();
        session.hide();
        session.prepareInternalResume();
        session.show(true, true);
        assertEquals(0, session.listeningStarts);
    }

    @Test public void returningFromTheAttachmentPickerDoesNotStartListening() {
        // The Gallery/file picker resumes through the same internal-resume path.
        SessionLifecycle session = new SessionLifecycle();
        session.prepareFreshShow();
        session.show(true, false);
        session.hide();
        session.prepareInternalResume();
        session.show(true, true);
        assertEquals(1, session.listeningStarts);
    }

    @Test public void aRepeatedCallbackForTheSameVisibleSheetDoesNotStartTwice() {
        SessionLifecycle session = new SessionLifecycle();
        session.prepareFreshShow();
        session.show(true, false);
        // Re-theming, configuration rebuilds, or another callback for the same visible sheet.
        session.show(true, false);
        session.show(true, false);
        assertEquals(1, session.listeningStarts);
    }

    @Test public void eachGenuinelyNewInvocationListensAgain() {
        SessionLifecycle session = new SessionLifecycle();
        session.prepareFreshShow();
        session.show(true, false);
        session.hide();
        session.prepareFreshShow();
        session.show(true, false);
        assertEquals(2, session.listeningStarts);
    }

    // --- Independence from hands-free follow-ups ----------------------------------------

    /** The decision Orbit makes after speaking, in both the overlay and full chat. */
    private boolean followUpAfterSpeaking() { return Prefs.autoListen(context); }

    @Test public void startOnOpenAloneDoesNotEnableFollowUps() {
        Prefs.get(context).edit().putBoolean(Prefs.AUTO_LISTEN_ON_OPEN, true).commit();
        SessionLifecycle session = new SessionLifecycle();
        session.prepareFreshShow();
        session.show(Prefs.autoListenOnOpen(context), false);

        assertEquals(1, session.listeningStarts);
        assertFalse("Orbit must not reopen the mic after replying", followUpAfterSpeaking());
    }

    @Test public void followUpsAloneDoNotStartTheOverlayListening() {
        Prefs.get(context).edit().putBoolean(Prefs.AUTO_LISTEN, true).commit();
        SessionLifecycle session = new SessionLifecycle();
        session.prepareFreshShow();
        session.show(Prefs.autoListenOnOpen(context), false);

        assertEquals("The overlay opens normally", 0, session.listeningStarts);
        assertTrue(followUpAfterSpeaking());
    }

    @Test public void bothEnabledGiveAFirstTurnAndAFollowUpWithoutDuplicates() {
        Prefs.get(context).edit()
                .putBoolean(Prefs.AUTO_LISTEN_ON_OPEN, true)
                .putBoolean(Prefs.AUTO_LISTEN, true)
                .commit();
        SessionLifecycle session = new SessionLifecycle();
        session.prepareFreshShow();
        session.show(Prefs.autoListenOnOpen(context), false);
        // The follow-up turn runs through the TTS completion path, not through another show.
        session.show(Prefs.autoListenOnOpen(context), false);

        assertEquals("The opening turn starts once", 1, session.listeningStarts);
        assertTrue(followUpAfterSpeaking());
    }

    // --- Full chat -----------------------------------------------------------------------

    @Test public void fullChatVoiceBehaviourIsUnchanged() {
        // Full chat starts a voice turn only when the user asks for one, and only continues
        // hands-free through AUTO_LISTEN. The overlay-only setting must not appear in that
        // decision, so turning it on changes nothing full chat reads.
        Prefs.get(context).edit().putBoolean(Prefs.AUTO_LISTEN_ON_OPEN, true).commit();
        assertFalse(followUpAfterSpeaking());

        Prefs.get(context).edit().putBoolean(Prefs.AUTO_LISTEN, true).commit();
        assertTrue(followUpAfterSpeaking());
    }

    // --- Microphone permission -----------------------------------------------------------

    @Test public void aMissingMicrophonePermissionDoesNotLoopOrCrash() {
        // Robolectric grants nothing by default, matching a user who never allowed the mic.
        assertEquals(PackageManager.PERMISSION_DENIED,
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO));

        Prefs.get(context).edit().putBoolean(Prefs.AUTO_LISTEN_ON_OPEN, true).commit();
        SessionLifecycle session = new SessionLifecycle();
        session.prepareFreshShow();
        session.show(true, false);
        session.show(true, false);
        session.show(true, false);

        // One request per invocation regardless of the permission answer: Orbit hands off to the
        // microphone button's existing permission handling instead of retrying in a loop.
        assertEquals(1, session.listeningStarts);
    }

    // --- Keyboard-aware invocation -------------------------------------------------------

    @Test public void keyboardBehaviourIsUnchangedWhenStartOnOpenIsOff() {
        // Exactly Orbit's existing rule: focus the composer only when neither hands-free
        // follow-ups nor keyboard-aware invocation is on.
        assertTrue(OverlayAutoListen.shouldFocusComposer(false, false, false));
        assertFalse(OverlayAutoListen.shouldFocusComposer(false, true, false));
        assertFalse(OverlayAutoListen.shouldFocusComposer(false, false, true));
        assertFalse(OverlayAutoListen.shouldFocusComposer(false, true, true));
    }

    @Test public void voiceTakesPriorityOverTheComposerWhenStartingOnOpen() {
        // No keyboard is summoned behind the listening UI, whatever the other settings say.
        assertFalse(OverlayAutoListen.shouldFocusComposer(true, false, false));
        assertFalse(OverlayAutoListen.shouldFocusComposer(true, true, false));
        assertFalse(OverlayAutoListen.shouldFocusComposer(true, false, true));
        assertFalse(OverlayAutoListen.shouldFocusComposer(true, true, true));
    }
}
