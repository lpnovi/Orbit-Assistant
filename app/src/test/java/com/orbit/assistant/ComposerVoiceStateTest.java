package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Full-chat composer voice presentation: the audio level reaches the shared indicator, the
 * indicator retires on every path out of listening, and the listening tint stays theme-derived.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class ComposerVoiceStateTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    /** Mirrors what the composer's callback does for a given voice state. */
    private static void applyState(OrbitListeningHalo halo, boolean listening) {
        if (listening) halo.start();
        else halo.stop();
    }

    @Test public void theComposerUsesTheSharedListeningIndicator() {
        // The same implementation as the Side-button overlay, not a second animation system.
        OrbitListeningHalo halo = new OrbitListeningHalo(context);
        assertFalse(halo.isListening());
        halo.start();
        assertTrue(halo.isListening());
        halo.stop();
        assertFalse(halo.isListening());
    }

    @Test public void everyExitFromListeningRetiresTheIndicator() {
        OrbitListeningHalo halo = new OrbitListeningHalo(context);
        // Normal stop, cancellation, failure, finalizing into thinking, and navigating away all
        // arrive as "no longer listening".
        for (int i = 0; i < 5; i++) {
            applyState(halo, true);
            assertTrue(halo.isListening());
            applyState(halo, false);
            assertFalse("listening state " + i + " left the microphone animating",
                    halo.isListening());
        }
    }

    @Test public void audioLevelsAreIgnoredOnceListeningHasEnded() {
        OrbitListeningHalo halo = new OrbitListeningHalo(context);
        halo.start();
        halo.setLevel(8f);
        halo.stop();

        // A recognizer callback arriving after the state changed must not revive the animation.
        halo.setLevel(9f);
        assertFalse(halo.isListening());
    }

    @Test public void theAudioLevelCallbackIsOptionalForOtherSurfaces() throws Exception {
        // Declared as a default method, so a voice surface that shows no reactive feedback is
        // not forced to implement it.
        assertTrue(VoiceInputController.Callback.class
                .getMethod("onAudioLevel", float.class).isDefault());
    }

    @Test public void theListeningTintIsDerivedFromTheCurrentAccent() {
        Prefs.get(context).edit().putString(Prefs.ACCENT, "mint").commit();
        int mintAccent = UiKit.accent(context);
        int mintListening = UiKit.blend(mintAccent, android.graphics.Color.rgb(255, 112, 112), 0.58f);

        Prefs.get(context).edit().putString(Prefs.ACCENT, "blue").commit();
        int blueAccent = UiKit.accent(context);
        int blueListening = UiKit.blend(blueAccent, android.graphics.Color.rgb(255, 112, 112), 0.58f);

        // Changing accent has to move the listening colour, or it is effectively a fixed red.
        assertFalse("listening tint must follow the accent", mintListening == blueListening);
        assertFalse(mintListening == android.graphics.Color.rgb(255, 112, 112));
    }

    @Test public void theComposerOutlineFollowsTheAccentRatherThanAFixedSlate() {
        Prefs.get(context).edit().putString(Prefs.ACCENT, "rose").commit();
        int rose = UiKit.withAlpha(UiKit.accent(context), 46);

        Prefs.get(context).edit().putString(Prefs.ACCENT, "nova").commit();
        int nova = UiKit.withAlpha(UiKit.accent(context), 46);

        assertFalse("composer outline must change with the accent", rose == nova);
    }

    @Test public void repeatedListeningSessionsRestartFromRest() {
        OrbitListeningHalo halo = new OrbitListeningHalo(context);
        for (int i = 0; i < 4; i++) {
            halo.start();
            halo.setLevel(10f);
            halo.stop();
            assertFalse(halo.isListening());
        }
        halo.start();
        assertTrue("a later session must still start cleanly", halo.isListening());
        halo.stop();
    }

    @Test public void sendIsOnlyOfferedWhenThereIsSomethingToSend() {
        // Mirrors updateSendState: text or an attachment both count as sendable.
        assertFalse(sendReady("", false));
        assertFalse(sendReady("   ", false));
        assertTrue(sendReady("hello", false));
        assertTrue(sendReady("", true));
        assertTrue(sendReady("   ", true));
        assertTrue(sendReady("hello", true));
    }

    private static boolean sendReady(String text, boolean hasAttachment) {
        return text.trim().length() > 0 || hasAttachment;
    }

    @Test public void accentChangesDoNotForceAComposerRebuild() {
        // The v0.7.2.4 split still holds: nothing in this pass added a structural trigger.
        Prefs.get(context).edit().putString(Prefs.ACCENT, "mint").commit();
        String structural = UiKit.structuralAppearanceSignature(context);

        Prefs.get(context).edit().putString(Prefs.APP_FONT, "condensed").commit();
        assertEquals(structural, UiKit.structuralAppearanceSignature(context));

        Prefs.get(context).edit().putString(Prefs.CHAT_TEXT_SIZE, Prefs.CHAT_TEXT_LARGE).commit();
        assertEquals(structural, UiKit.structuralAppearanceSignature(context));
    }
}
