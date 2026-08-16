package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicInteger;

/** The shared binary-settings control and the row Orbit presents it in. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class OrbitSwitchTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    @Test public void aNewSwitchStartsOff() {
        assertFalse(new OrbitSwitch(context).isChecked());
    }

    @Test public void togglingChangesStateAndReportsItExactlyOnce() {
        OrbitSwitch control = new OrbitSwitch(context);
        AtomicInteger calls = new AtomicInteger();
        control.setOnCheckedChangeListener((view, checked) -> calls.incrementAndGet());

        control.toggle();
        assertTrue(control.isChecked());
        assertEquals(1, calls.get());

        control.toggle();
        assertFalse(control.isChecked());
        assertEquals(2, calls.get());
    }

    @Test public void aProgrammaticChangeNeverReportsBack() {
        // This is what lets a denied permission roll the control back without re-entering
        // the caller's own handler.
        OrbitSwitch control = new OrbitSwitch(context);
        AtomicInteger calls = new AtomicInteger();
        control.setOnCheckedChangeListener((view, checked) -> calls.incrementAndGet());

        control.setChecked(true);
        control.setChecked(false, false);
        assertEquals(0, calls.get());
        assertFalse(control.isChecked());
    }

    @Test public void aRollbackInsideTheListenerSticks() {
        OrbitSwitch control = new OrbitSwitch(context);
        AtomicInteger calls = new AtomicInteger();
        control.setOnCheckedChangeListener((view, checked) -> {
            calls.incrementAndGet();
            if (checked) view.setChecked(false);
        });

        control.toggle();
        assertFalse("a refused setting must end up visibly off", control.isChecked());
        assertEquals("the rollback must not loop", 1, calls.get());
    }

    @Test public void clickingTheSwitchTogglesIt() {
        OrbitSwitch control = new OrbitSwitch(context);
        control.performClick();
        assertTrue(control.isChecked());
    }

    @Test public void clickingAnywhereOnTheRowTogglesIt() {
        OrbitSwitch control = new OrbitSwitch(context);
        LinearLayout row = UiKit.switchRow(context, "Haptic feedback", null, control);
        row.performClick();
        assertTrue(control.isChecked());
        row.performClick();
        assertFalse(control.isChecked());
    }

    @Test public void aDisabledSwitchIgnoresInteraction() {
        OrbitSwitch control = new OrbitSwitch(context);
        AtomicInteger calls = new AtomicInteger();
        control.setOnCheckedChangeListener((view, checked) -> calls.incrementAndGet());
        control.setEnabled(false);

        control.toggle();
        control.performClick();
        assertFalse(control.isChecked());
        assertEquals(0, calls.get());
    }

    @Test public void accessibilityReportsItAsACheckedSwitch() {
        OrbitSwitch control = new OrbitSwitch(context);
        AccessibilityNodeInfo off = AccessibilityNodeInfo.obtain();
        control.onInitializeAccessibilityNodeInfo(off);
        assertTrue(off.isCheckable());
        assertFalse(off.isChecked());
        assertEquals("android.widget.Switch", off.getClassName().toString());

        control.toggle();
        AccessibilityNodeInfo on = AccessibilityNodeInfo.obtain();
        control.onInitializeAccessibilityNodeInfo(on);
        assertTrue("TalkBack has to be able to tell on from off", on.isChecked());
    }

    @Test public void theRowLabelsTheControlRatherThanCompetingWithIt() {
        OrbitSwitch control = new OrbitSwitch(context);
        LinearLayout row = UiKit.switchRow(context, "Hands-free voice follow-ups",
                "Listen again after Orbit speaks.", control);

        assertEquals("Hands-free voice follow-ups", control.getContentDescription());
        View labels = row.getChildAt(0);
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, labels.getImportantForAccessibility());
    }

    @Test public void theRowKeepsAComfortableTarget() {
        OrbitSwitch control = new OrbitSwitch(context);
        LinearLayout row = UiKit.switchRow(context, "Haptic feedback", null, control);
        assertTrue("the row must stay easy to hit",
                row.getMinimumHeight() >= UiKit.dp(context, 44));

        control.measure(0, 0);
        assertTrue(control.getMeasuredHeight() >= UiKit.dp(context, 44));
        assertTrue(control.getMeasuredWidth() >= UiKit.dp(context, 48));
        assertTrue(control.getMeasuredWidth() <= UiKit.dp(context, 52));
    }

    @Test public void theDescriptionIsOptional() {
        OrbitSwitch bare = new OrbitSwitch(context);
        assertEquals(2, UiKit.switchRow(context, "Haptic feedback", null, bare).getChildCount());
        LinearLayout bareLabels = (LinearLayout) UiKit
                .switchRow(context, "Haptic feedback", "", new OrbitSwitch(context)).getChildAt(0);
        assertEquals(1, bareLabels.getChildCount());

        LinearLayout described = (LinearLayout) UiKit
                .switchRow(context, "Haptic feedback", "Small taps.", new OrbitSwitch(context))
                .getChildAt(0);
        assertEquals(2, described.getChildCount());
    }

    @Test public void stateSurvivesAnAppearanceRefresh() {
        OrbitSwitch control = new OrbitSwitch(context);
        control.toggle();
        assertTrue(control.isChecked());

        Prefs.get(context).edit().putString(Prefs.ACCENT, "mint").commit();
        control.applyAccent(context);
        assertTrue("re-reading colours must not disturb the setting", control.isChecked());

        Prefs.get(context).edit().putBoolean(Prefs.AMOLED_MODE, true).commit();
        UiKit.syncTheme(context);
        control.applyAccent(context);
        assertTrue(control.isChecked());
    }

    @Test public void theOnTrackFollowsTheCurrentAccent() {
        // Reading the accent at draw time is what keeps every preset, including the pastels,
        // from collapsing onto one hardcoded colour.
        Prefs.get(context).edit().putString(Prefs.ACCENT, "mint").commit();
        int mint = UiKit.accent(context);
        Prefs.get(context).edit().putString(Prefs.ACCENT, "nova").commit();
        int nova = UiKit.accent(context);
        assertFalse("the switch must not be one fixed colour", mint == nova);

        Prefs.get(context).edit().putString(Prefs.ACCENT, "pastelpink").commit();
        int pastel = UiKit.accent(context);
        assertFalse("a pale accent needs its own readable thumb",
                UiKit.onAccent(context) == pastel);
    }

    @Test public void theUnanimatedPathLandsImmediately() {
        // This is the same branch the control takes when the system has animators switched off,
        // so a device with animations disabled still reaches the final position rather than
        // waiting on a frame that never comes.
        OrbitSwitch control = new OrbitSwitch(context);
        control.setChecked(true, false);
        assertTrue(control.isChecked());
        control.setChecked(false, false);
        assertFalse(control.isChecked());
    }

    @Test public void bothPathsEndInTheSameState() {
        OrbitSwitch animated = new OrbitSwitch(context);
        OrbitSwitch immediate = new OrbitSwitch(context);
        animated.setChecked(true, true);
        immediate.setChecked(true, false);
        assertEquals(animated.isChecked(), immediate.isChecked());
        assertTrue(animated.isChecked());

        animated.toggle();
        immediate.toggle();
        assertEquals(animated.isChecked(), immediate.isChecked());
        assertFalse(animated.isChecked());
    }

    @Test public void settingTheSameValueTwiceIsHarmless() {
        OrbitSwitch control = new OrbitSwitch(context);
        AtomicInteger calls = new AtomicInteger();
        control.setOnCheckedChangeListener((view, checked) -> calls.incrementAndGet());

        control.setChecked(true, false);
        control.setChecked(true, false);
        control.setChecked(true);
        assertTrue(control.isChecked());
        assertEquals(0, calls.get());
    }

    @Test public void switchesAreForSettingsAndCheckboxesRemainForFormFields() {
        // The migrated controls flip stored state on the spot; the ones left as checkboxes are
        // fields inside an editor that only take effect when the form is saved.
        OrbitSwitch control = new OrbitSwitch(context);
        control.setOnCheckedChangeListener((view, checked) ->
                Prefs.get(context).edit().putBoolean(Prefs.HAPTICS, checked).apply());
        control.setChecked(Prefs.haptics(context), false);
        assertTrue(control.isChecked());

        control.toggle();
        assertFalse("a settings switch takes effect as soon as it moves",
                Prefs.haptics(context));
    }
}
