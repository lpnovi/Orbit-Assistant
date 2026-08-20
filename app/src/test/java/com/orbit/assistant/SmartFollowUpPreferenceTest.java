package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The Smart follow-ups preference and its place under Hands-free voice follow-ups.
 *
 * <p>Smart follow-ups is subordinate: it shapes what the master switch does, and means nothing on
 * its own. It is also an ordinary voice preference, so it travels in Backup &amp; Restore with the
 * rest of them, and a backup written before it existed has to land on the current default rather
 * than on whatever {@code getBoolean} would have guessed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class SmartFollowUpPreferenceTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().apply();
    }

    @Test public void smartFollowUpsIsOnByDefault() {
        assertTrue("a fresh install should get the conversational behaviour",
                Prefs.smartFollowUps(context));
    }

    @Test public void theExistingVoicePreferenceDefaultsAreUntouched() {
        assertFalse("Hands-free voice follow-ups still defaults off",
                Prefs.autoListen(context));
        assertFalse("Start listening when overlay opens still defaults off",
                Prefs.autoListenOnOpen(context));
    }

    @Test public void smartFollowUpsPersists() {
        Prefs.get(context).edit().putBoolean(Prefs.SMART_FOLLOW_UPS, false).apply();
        assertFalse(Prefs.smartFollowUps(context));
        Prefs.get(context).edit().putBoolean(Prefs.SMART_FOLLOW_UPS, true).apply();
        assertTrue(Prefs.smartFollowUps(context));
    }

    @Test public void smartFollowUpsIsCarriedByBackupAndRestore() throws Exception {
        Prefs.get(context).edit()
                .putBoolean(Prefs.AUTO_LISTEN, true)
                .putBoolean(Prefs.SMART_FOLLOW_UPS, false)
                .apply();

        JSONObject snapshot = Prefs.backupSnapshot(context);
        assertTrue("the key has to be in the backup at all",
                snapshot.has(Prefs.SMART_FOLLOW_UPS));
        assertFalse(snapshot.getBoolean(Prefs.SMART_FOLLOW_UPS));

        Prefs.get(context).edit().clear().apply();
        assertTrue("cleared preferences return to the default", Prefs.smartFollowUps(context));

        assertTrue(Prefs.restoreBackupSnapshot(context, snapshot));
        assertFalse("restore has to bring the stored choice back",
                Prefs.smartFollowUps(context));
        assertTrue(Prefs.autoListen(context));
    }

    @Test public void aBackupWrittenBeforeThisVersionRestoresOntoTheDefault() throws Exception {
        // Exactly what a pre-0.7.6.0 backup looks like: the other voice preferences, no Smart key.
        JSONObject legacy = new JSONObject();
        legacy.put(Prefs.AUTO_LISTEN, true);
        legacy.put(Prefs.AUTO_LISTEN_ON_OPEN, true);
        legacy.put(Prefs.SPEAK, true);

        assertTrue("a backup without the new key is still valid",
                Prefs.validBackupSnapshot(legacy));

        Prefs.get(context).edit().putBoolean(Prefs.SMART_FOLLOW_UPS, false).apply();
        assertTrue(Prefs.restoreBackupSnapshot(context, legacy));

        assertTrue("a missing key must land on the current default, not a stale value",
                Prefs.smartFollowUps(context));
        assertTrue(Prefs.autoListen(context));
        assertTrue(Prefs.autoListenOnOpen(context));
    }

    // ---- the hierarchy ----------------------------------------------------------------------

    @Test public void theMasterSwitchWinsWhateverSmartIsSetTo() {
        Prefs.get(context).edit().putBoolean(Prefs.AUTO_LISTEN, false).apply();
        for (boolean smart : new boolean[]{true, false}) {
            Prefs.get(context).edit().putBoolean(Prefs.SMART_FOLLOW_UPS, smart).apply();
            VoiceFollowUpPolicy.Decision decision = VoiceFollowUpPolicy.decide(
                    Prefs.autoListen(context), Prefs.smartFollowUps(context),
                    true, true, "Which one did you mean?");
            assertEquals(VoiceFollowUpPolicy.Decision.MASTER_OFF, decision);
            assertFalse(decision.reopensMicrophone());
        }
    }

    @Test public void turningTheMasterOffPreservesTheStoredSmartValue() {
        Prefs.get(context).edit()
                .putBoolean(Prefs.SMART_FOLLOW_UPS, false)
                .putBoolean(Prefs.AUTO_LISTEN, false)
                .apply();
        assertFalse("the subordinate choice survives the master being switched off",
                Prefs.smartFollowUps(context));

        Prefs.get(context).edit().putBoolean(Prefs.AUTO_LISTEN, true).apply();
        assertFalse(Prefs.smartFollowUps(context));
        assertEquals(VoiceFollowUpPolicy.Decision.SMART_OFF_LEGACY,
                VoiceFollowUpPolicy.decide(Prefs.autoListen(context),
                        Prefs.smartFollowUps(context), true, true, "All done."));
    }

    @Test public void startListeningOnOpenIsIndependentOfSmartFollowUps() {
        Prefs.get(context).edit()
                .putBoolean(Prefs.AUTO_LISTEN_ON_OPEN, true)
                .putBoolean(Prefs.AUTO_LISTEN, false)
                .putBoolean(Prefs.SMART_FOLLOW_UPS, false)
                .apply();
        // The overlay's opening turn asks OverlayAutoListen, which knows nothing about follow-ups.
        assertTrue(OverlayAutoListen.shouldStartForShow(
                Prefs.autoListenOnOpen(context), false, false));

        Prefs.get(context).edit().putBoolean(Prefs.SMART_FOLLOW_UPS, true).apply();
        assertTrue("Smart follow-ups must not reach the opening turn",
                OverlayAutoListen.shouldStartForShow(
                        Prefs.autoListenOnOpen(context), false, false));
    }
}
