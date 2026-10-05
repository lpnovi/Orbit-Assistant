package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** 0.8.4.0: people updating from 0.8.3 Stable keep Classic; everyone else keeps what they had. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public class OverlayStableUpgradeTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    @Test public void aFreshInstallGetsModern() {
        Prefs.keepClassicForStableUpgrade(context, false);
        assertSame(OverlayStyle.MODERN, OverlayStyle.current(context));
        assertFalse("nothing chosen on their behalf", Prefs.get(context).contains(Prefs.OVERLAY_STYLE));
    }

    @Test public void anUpdateFromStableKeepsClassic() {
        // 0.8.3.0 left real settings behind, but never anything about overlay styles.
        Prefs.get(context).edit().putBoolean(Prefs.HAPTICS, false).commit();
        Prefs.keepClassicForStableUpgrade(context, true);
        assertSame(OverlayStyle.CLASSIC, OverlayStyle.current(context));
        assertEquals("stored as a real choice", Prefs.OVERLAY_STYLE_CLASSIC,
                Prefs.get(context).getString(Prefs.OVERLAY_STYLE, null));
        assertFalse("nothing else touched", Prefs.get(context).getBoolean(Prefs.HAPTICS, true));
    }

    @Test public void itRunsOnceAndNeverUndoesALaterChoice() {
        Prefs.keepClassicForStableUpgrade(context, true);
        for (String chosen : new String[]{Prefs.OVERLAY_STYLE_MODERN, Prefs.OVERLAY_STYLE_FLOAT}) {
            Prefs.setOverlayStyle(context, chosen);
            Prefs.keepClassicForStableUpgrade(context, true);
            Prefs.keepClassicForStableUpgrade(context, true);
            assertEquals(chosen, Prefs.overlayStyle(context));
        }
        // Even a later update finding no stored value cannot re-run it.
        Prefs.get(context).edit().remove(Prefs.OVERLAY_STYLE).commit();
        Prefs.keepClassicForStableUpgrade(context, true);
        assertSame(OverlayStyle.MODERN, OverlayStyle.current(context));
    }

    @Test public void explicitChoicesAreNeverOverwritten() {
        for (String explicit : new String[]{Prefs.OVERLAY_STYLE_CLASSIC, Prefs.OVERLAY_STYLE_MODERN,
                Prefs.OVERLAY_STYLE_FLOAT}) {
            Prefs.get(context).edit().clear().commit();
            Prefs.setOverlayStyle(context, explicit);
            Prefs.keepClassicForStableUpgrade(context, true);
            assertEquals(explicit, Prefs.overlayStyle(context));
        }
    }

    @Test public void betaUsersOnTheDefaultAreNotSentBackToClassic() {
        // Any Beta from Beta 2 on resolved the style once, which leaves the migration flag behind,
        // even for someone who never opened the selector and so sat on the Modern default.
        assertEquals(Prefs.OVERLAY_STYLE_MODERN, Prefs.overlayStyle(context));
        assertFalse(Prefs.get(context).contains(Prefs.OVERLAY_STYLE));
        Prefs.keepClassicForStableUpgrade(context, true);
        assertSame(OverlayStyle.MODERN, OverlayStyle.current(context));
    }

    @Test public void beta1ChoicesStillMeanWhatTheyMeant() {
        // Beta 1's "float" is today's Modern; the stored value is left for that migration.
        Prefs.get(context).edit().putString(Prefs.OVERLAY_STYLE, "float").commit();
        Prefs.keepClassicForStableUpgrade(context, true);
        assertEquals(Prefs.OVERLAY_STYLE_MODERN, Prefs.overlayStyle(context));
    }

    @Test public void corruptValuesStillFallBackSafely() {
        Prefs.setOverlayStyle(context, "not-a-style");
        Prefs.keepClassicForStableUpgrade(context, true);
        assertSame(OverlayStyle.MODERN, OverlayStyle.current(context));
    }

    @Test public void theRuleLeavesTheHiddenStyleGatingAlone() {
        Prefs.get(context).edit().putBoolean(Prefs.LELO_MODE, true).commit();
        Prefs.setOverlayStyle(context, Prefs.OVERLAY_STYLE_CUTIE);
        Prefs.keepClassicForStableUpgrade(context, true);
        assertSame(OverlayStyle.CUTIE, OverlayStyle.current(context));
        Prefs.get(context).edit().putBoolean(Prefs.LELO_MODE, false).commit();
        assertSame(OverlayStyle.MODERN, OverlayStyle.current(context));
    }

    @Test public void theInstallMarkerNeverTravelsInABackup() throws Exception {
        Prefs.keepClassicForStableUpgrade(context, true);
        JSONObject backup = Prefs.backupSnapshot(context);
        assertFalse(backup.toString().contains(Prefs.OVERLAY_STYLE_STABLE_UPGRADE));
        assertTrue("the chosen style itself does", backup.toString().contains(Prefs.OVERLAY_STYLE));
    }
}
