package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether Orbit's first open shows onboarding.
 *
 * <p>The failure this guards against was found on a real Google Play install: Chats applies the
 * theme before it asks whether this is a first run, applying the theme writes Orbit's default
 * appearance into preferences, and the legacy-install check then read those default values as
 * proof of an earlier install. A genuinely fresh Orbit went straight to Chats. The MainActivity
 * test below runs the real start-up order, so any later preference written by Orbit itself before
 * the decision fails here rather than on somebody's phone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class OnboardingFirstRunTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        ConversationStore.clear(context);
        OrbitRequestManager.resetForTest();
        OrbitRequestManager.setWorkCanceller(name -> {});
        TestWorkManager.ensureInitialized(context);
    }

    @Test public void aGenuinelyFreshInstallLaunchesOnboarding() {
        assertTrue(OnboardingState.shouldLaunchAutomatically(context));
        assertEquals(0, OnboardingState.completedVersion(context));
    }

    @Test public void orbitsOwnStartupPreferencesDoNotMakeAFreshInstallLookLegacy() {
        // Exactly what Chats does before deciding: apply the theme, which stamps the schema and
        // names the untouched appearance.
        UiKit.syncTheme(context);
        assertTrue("the theme step must have written something for this test to mean anything",
                Prefs.get(context).contains(Prefs.THEME_SCHEMA));

        assertTrue(OnboardingState.shouldLaunchAutomatically(context));
        assertEquals(0, OnboardingState.completedVersion(context));
    }

    @Test public void openingChatsOnAFreshInstallStartsOnboarding() {
        Robolectric.buildActivity(MainActivity.class).setup();
        ShadowLooper.idleMainLooper();

        Intent next = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
        assertNotNull("a fresh install must be sent to onboarding", next);
        assertEquals(new ComponentName(context, OnboardingActivity.class), next.getComponent());
        assertEquals(0, OnboardingState.completedVersion(context));
    }

    @Test public void aChosenAppearanceStillIdentifiesAnExistingInstall() {
        UiKit.syncTheme(context);
        Prefs.get(context).edit()
                .putString(Prefs.THEME_ID, Prefs.THEME_ID_CUSTOM)
                .putString(Prefs.THEME_NAME, "Your theme")
                .commit();

        assertFalse(OnboardingState.shouldLaunchAutomatically(context));
        assertEquals(OnboardingState.CURRENT_VERSION, OnboardingState.completedVersion(context));
    }

    @Test public void anExistingUsersSettingIdentifiesALegacyInstall() {
        Prefs.get(context).edit().putString(Prefs.ACCENT, "violet").commit();

        assertFalse(OnboardingState.shouldLaunchAutomatically(context));
        assertEquals(OnboardingState.CURRENT_VERSION, OnboardingState.completedVersion(context));
    }

    @Test public void existingChatsIdentifyALegacyInstall() {
        List<AssistantClient.History> history = new ArrayList<>();
        history.add(new AssistantClient.History("user", "hello"));
        ConversationStore.save(context, "legacy", history);

        assertFalse(OnboardingState.shouldLaunchAutomatically(context));
        assertEquals(OnboardingState.CURRENT_VERSION, OnboardingState.completedVersion(context));
    }

    @Test public void aRealPackageUpdateIdentifiesALegacyInstallEvenWithDefaults() {
        PackageInfo info = shadowOf(context.getPackageManager())
                .getInternalMutablePackageInfo(context.getPackageName());
        info.firstInstallTime = 1_000_000L;
        info.lastUpdateTime = 1_000_000L + 86_400_000L;
        UiKit.syncTheme(context);

        assertFalse(OnboardingState.shouldLaunchAutomatically(context));
        assertEquals(OnboardingState.CURRENT_VERSION, OnboardingState.completedVersion(context));
    }

    @Test public void completedOnboardingNeverLaunchesAgain() {
        OnboardingState.markCompleted(context);

        assertFalse(OnboardingState.shouldLaunchAutomatically(context));
        assertFalse(OnboardingState.shouldLaunchAutomatically(context));
    }

    @Test public void startedOnboardingResumesWhereItStopped() {
        assertTrue(OnboardingState.shouldLaunchAutomatically(context));
        OnboardingState.setCurrentStep(context, 3);
        // Choices made during onboarding are real settings; they must not end it early.
        Prefs.get(context).edit().putString(Prefs.ACCENT, "mint").commit();
        UiKit.syncTheme(context);

        assertTrue(OnboardingState.shouldLaunchAutomatically(context));
        assertEquals(3, OnboardingState.currentStep(context));
        assertEquals(0, OnboardingState.completedVersion(context));
    }

    @Test public void manualAssistantSetupStartsFromTheBeginningAndKeepsCompletion() {
        OnboardingState.markCompleted(context);

        Intent manual = OnboardingActivity.manualIntent(context);
        assertEquals(new ComponentName(context, OnboardingActivity.class), manual.getComponent());
        Robolectric.buildActivity(OnboardingActivity.class, manual).setup();

        assertEquals(0, OnboardingState.currentStep(context));
        assertEquals(OnboardingState.CURRENT_VERSION, OnboardingState.completedVersion(context));
        assertFalse(OnboardingState.shouldLaunchAutomatically(context));
    }
}
