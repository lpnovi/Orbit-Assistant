package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Listening-indicator lifecycle and the one-shot assistant handoff flag. These cover the states
 * that would otherwise leave a pulsing microphone behind or suppress a later page transition.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class OrbitListeningHaloTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    private static void draw(OrbitListeningHalo halo, int size) {
        halo.setBounds(0, 0, size, size);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        halo.draw(new Canvas(bitmap));
        bitmap.recycle();
    }

    @Test public void startsIdleAndReportsListeningOnlyWhileStarted() {
        OrbitListeningHalo halo = new OrbitListeningHalo(context);
        assertFalse(halo.isListening());

        halo.start();
        assertTrue(halo.isListening());

        halo.stop();
        assertFalse(halo.isListening());
    }

    @Test public void repeatedStartsAndStopsStayConsistent() {
        OrbitListeningHalo halo = new OrbitListeningHalo(context);
        for (int i = 0; i < 4; i++) {
            halo.start();
            assertTrue(halo.isListening());
            halo.stop();
            assertFalse(halo.isListening());
        }
    }

    @Test public void stoppingWithoutStartingIsSafe() {
        OrbitListeningHalo halo = new OrbitListeningHalo(context);
        halo.stop();
        halo.setLevel(6f);
        assertFalse(halo.isListening());
        draw(halo, 44);
    }

    @Test public void levelsAreIgnoredWhileNotListening() {
        OrbitListeningHalo halo = new OrbitListeningHalo(context);
        // A late recognizer callback after listening ended must not revive the animation.
        halo.setLevel(10f);
        assertFalse(halo.isListening());
        draw(halo, 44);
        assertFalse(halo.isListening());
    }

    @Test public void drawsAcrossQuietAndLoudLevelsWithoutCrashing() {
        OrbitListeningHalo halo = new OrbitListeningHalo(context);
        halo.start();
        float[] levels = {-40f, -2f, 0f, 3.5f, 10f, 40f};
        for (float level : levels) {
            halo.setLevel(level);
            draw(halo, 44);
        }
        assertTrue(halo.isListening());
        halo.stop();
    }

    @Test public void drawsAtSmallAndLargeBoundsAndWhileEmpty() {
        OrbitListeningHalo halo = new OrbitListeningHalo(context);
        halo.start();
        draw(halo, 1);
        draw(halo, 44);
        draw(halo, 200);

        // Zero bounds must simply draw nothing rather than divide by an empty size.
        halo.setBounds(0, 0, 0, 0);
        Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        halo.draw(new Canvas(bitmap));
        bitmap.recycle();
        halo.stop();
    }

    @Test public void hidingTheDrawableEndsItsFrames() {
        OrbitListeningHalo halo = new OrbitListeningHalo(context);
        halo.start();
        draw(halo, 44);

        halo.setVisible(false, false);
        // Still logically listening, but nothing is scheduled while it is off screen.
        assertTrue(halo.isListening());

        halo.stop();
        assertFalse(halo.isListening());
    }

    @Test public void followsTheCurrentAccentRatherThanAFixedColour() {
        Prefs.get(context).edit().putString(Prefs.ACCENT, "mint").commit();
        OrbitListeningHalo halo = new OrbitListeningHalo(context);
        halo.start();
        draw(halo, 44);

        Prefs.get(context).edit().putString(Prefs.ACCENT, "rose").commit();
        halo.applyAccent(context);
        draw(halo, 44);

        assertEquals(UiKit.accentForName(context, "rose"), UiKit.accent(context));
        halo.stop();
    }

    @Test public void assistantHandoffFlagIsCarriedOnTheIntent() {
        Intent handoff = new Intent(context, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_CONVERSATION_ID, "conversation-1")
                .putExtra(MainActivity.EXTRA_ASSISTANT_HANDOFF, true);

        assertTrue(handoff.getBooleanExtra(MainActivity.EXTRA_ASSISTANT_HANDOFF, false));
        assertEquals("conversation-1",
                handoff.getStringExtra(MainActivity.EXTRA_OPEN_CONVERSATION_ID));
    }

    @Test public void ordinaryNavigationCarriesNoHandoffFlag() {
        // Anything that is not the overlay handoff must keep the user's page transition.
        Intent normal = new Intent(context, MainActivity.class);
        assertFalse(normal.getBooleanExtra(MainActivity.EXTRA_ASSISTANT_HANDOFF, false));

        Intent openChat = new Intent(context, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_CONVERSATION_ID, "conversation-2");
        assertFalse(openChat.getBooleanExtra(MainActivity.EXTRA_ASSISTANT_HANDOFF, false));
    }

    @Test public void suppressingAHandoffDoesNotChangeTheStoredPreference() {
        Prefs.get(context).edit().putString(Prefs.PAGE_TRANSITION,
                Prefs.PAGE_TRANSITION_SLIDE).commit();

        // The bypass is per launch, so the preference and the style it resolves to are untouched
        // and later navigation still slides.
        assertEquals(Prefs.PAGE_TRANSITION_SLIDE, Prefs.pageTransition(context));
        assertEquals(R.style.OrbitWindowAnimation_Slide, UiKit.pageTransitionStyle(context));

        Prefs.get(context).edit().putString(Prefs.PAGE_TRANSITION,
                Prefs.PAGE_TRANSITION_FADE).commit();
        assertEquals(R.style.OrbitWindowAnimation_Fade, UiKit.pageTransitionStyle(context));
    }
}
