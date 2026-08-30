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
 * Turning a feature on by default without overruling anybody who already said no.
 *
 * <p>Beta 1 shipped Thinking updates off, because nothing was proven on real hardware yet. Beta 2
 * turns them on, and the whole risk of that change is in one distinction: "this user has never had
 * an opinion" must behave differently from "this user turned it off". The preference system
 * already draws that line, so the correct implementation writes nothing and changes only the
 * fallback — and these pin that, including the case that would be a real bug, which is an update
 * quietly re-enabling something a person deliberately switched off.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class ThinkingUpdateDefaultTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    // ---- the three states a preference can be in --------------------------------------------------

    /** Someone upgrading from v0.7.7.7, or a fresh install: no stored value at all. */
    @Test public void anAbsentPreferenceMeansEnabled() {
        assertFalse("the test must start with no stored value",
                Prefs.get(context).contains(Prefs.THINKING_UPDATES));
        assertTrue(Prefs.thinkingUpdates(context));
    }

    @Test public void anExplicitTrueIsEnabled() {
        Prefs.get(context).edit().putBoolean(Prefs.THINKING_UPDATES, true).commit();
        assertTrue(Prefs.thinkingUpdates(context));
    }

    /** The case the change exists to not break. */
    @Test public void anExplicitFalseStaysDisabled() {
        Prefs.get(context).edit().putBoolean(Prefs.THINKING_UPDATES, false).commit();
        assertFalse("an explicit opt-out must survive the new default",
                Prefs.thinkingUpdates(context));
    }

    // ---- reading the preference must never create one ---------------------------------------------

    /**
     * Nothing may turn an absent preference into a stored one. If reading it wrote a value, the
     * distinction above would survive exactly one app launch and then be gone forever.
     */
    @Test public void readingTheDefaultDoesNotStoreAnything() {
        assertTrue(Prefs.thinkingUpdates(context));
        for (int i = 0; i < 5; i++) Prefs.thinkingUpdates(context);
        assertFalse("reading must not write a preference the user never set",
                Prefs.get(context).contains(Prefs.THINKING_UPDATES));
    }

    /** And an opt-out must survive however many times the app reads it. */
    @Test public void anOptOutSurvivesRepeatedReadsAndRestarts() {
        Prefs.get(context).edit().putBoolean(Prefs.THINKING_UPDATES, false).commit();
        for (int i = 0; i < 5; i++) assertFalse(Prefs.thinkingUpdates(context));
        // A restart is the same preference file being read again, which is what this represents.
        assertFalse(Prefs.get(context).getBoolean(Prefs.THINKING_UPDATES, true));
    }

    // ---- the switch and the default agree ----------------------------------------------------------

    /**
     * Settings must show the same state the pipeline uses. These were two separate literals in
     * Beta 1, which is exactly how a switch comes to read "off" for a feature that is running.
     */
    @Test public void theSettingsSwitchReadsTheSameDefaultAsThePipeline() {
        assertEquals("the switch default must be the pipeline default",
                Prefs.THINKING_UPDATES_DEFAULT, Prefs.thinkingUpdates(context));
        assertTrue("Beta 2 ships this on", Prefs.THINKING_UPDATES_DEFAULT);
    }

    /** Turning it off in Settings really does turn it off for the next request. */
    @Test public void turningTheSwitchOffDisablesItForSubsequentRequests() {
        assertTrue(Prefs.thinkingUpdates(context));
        Prefs.get(context).edit().putBoolean(Prefs.THINKING_UPDATES, false).commit();
        assertFalse(Prefs.thinkingUpdates(context));
        Prefs.get(context).edit().putBoolean(Prefs.THINKING_UPDATES, true).commit();
        assertTrue(Prefs.thinkingUpdates(context));
    }

    // ---- backup and restore ------------------------------------------------------------------------

    /** An opt-out is part of who the user is, so it travels with a backup. */
    @Test public void anOptOutSurvivesABackupAndRestore() throws Exception {
        Prefs.get(context).edit().putBoolean(Prefs.THINKING_UPDATES, false).commit();
        org.json.JSONObject snapshot = Prefs.backupSnapshot(context);
        assertTrue(snapshot.has(Prefs.THINKING_UPDATES));

        Prefs.get(context).edit().clear().commit();
        assertTrue("cleared preferences fall back to the new default",
                Prefs.thinkingUpdates(context));

        assertTrue(Prefs.restoreBackupSnapshot(context, snapshot));
        assertFalse("restoring must bring the opt-out back with it",
                Prefs.thinkingUpdates(context));
    }

    /** Someone who never chose is not given a choice by the backup either. */
    @Test public void anAbsentPreferenceIsNotInventedByABackup() throws Exception {
        org.json.JSONObject snapshot = Prefs.backupSnapshot(context);
        assertFalse("a preference nobody set must not be written into a backup",
                snapshot.has(Prefs.THINKING_UPDATES));
        assertTrue(Prefs.restoreBackupSnapshot(context, snapshot));
        assertTrue(Prefs.thinkingUpdates(context));
        assertFalse(Prefs.get(context).contains(Prefs.THINKING_UPDATES));
    }
}
