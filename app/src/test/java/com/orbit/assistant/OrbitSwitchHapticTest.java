package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.widget.LinearLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * One light tick per genuine user switch transition, and none otherwise.
 *
 * <p>The decision itself is a pure function, so it is tested directly rather than through
 * vibration hardware; the wiring is then checked against real toggles.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class OrbitSwitchHapticTest {
    private Activity activity;
    private Context context;

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        Prefs.get(activity).edit().clear().commit();
    }

    /** An OrbitSwitch that counts the haptic requests it makes, without needing real hardware. */
    private static final class CountingSwitch extends OrbitSwitch {
        int ticks;

        CountingSwitch(Context context) {
            super(context);
        }

        @Override public boolean performHapticFeedback(int feedbackConstant) {
            ticks++;
            return true;
        }
    }

    private static int ticksFrom(CountingSwitch control, Runnable action) {
        int before = control.ticks;
        action.run();
        return control.ticks - before;
    }

    // ---- the decision ----

    @Test public void anOrdinarySwitchTicksOnlyWhenHapticsAreOn() {
        assertTrue(OrbitSwitch.shouldTick(true, true));
        assertFalse(OrbitSwitch.shouldTick(false, false));
    }

    @Test public void theHapticsSwitchItselfTicksExactlyOnceEitherWay() {
        // Turning haptics off: on before, off after. The disabling gesture still confirms.
        assertTrue("turning haptics off gives one final tick",
                OrbitSwitch.shouldTick(true, false));
        // Turning haptics on: off before, on after. The enabling gesture confirms once.
        assertTrue("turning haptics on gives one tick", OrbitSwitch.shouldTick(false, true));
    }

    // ---- wiring ----

    @Test public void aUserToggleTicksWhenHapticsAreEnabled() {
        Prefs.get(activity).edit().putBoolean(Prefs.HAPTICS, true).commit();
        CountingSwitch control = new CountingSwitch(activity);
        assertEquals(1, ticksFrom(control, control::toggle));
    }

    @Test public void aUserToggleIsSilentWhenHapticsAreDisabled() {
        Prefs.get(activity).edit().putBoolean(Prefs.HAPTICS, false).commit();
        CountingSwitch control = new CountingSwitch(activity);
        assertEquals(0, ticksFrom(control, control::toggle));
    }

    @Test public void bindingInitialStateIsSilent() {
        Prefs.get(activity).edit().putBoolean(Prefs.HAPTICS, true).commit();
        CountingSwitch control = new CountingSwitch(activity);
        assertEquals("loading a screen must not buzz", 0,
                ticksFrom(control, () -> control.setChecked(true, false)));
    }

    @Test public void programmaticRefreshIsSilent() {
        Prefs.get(activity).edit().putBoolean(Prefs.HAPTICS, true).commit();
        CountingSwitch control = new CountingSwitch(activity);
        control.setChecked(true, false);
        assertEquals(0, ticksFrom(control, () -> {
            control.setChecked(false);
            control.setChecked(true);
            control.applyAccent(activity);
        }));
    }

    @Test public void anUnchangedValueIsSilent() {
        Prefs.get(activity).edit().putBoolean(Prefs.HAPTICS, true).commit();
        CountingSwitch control = new CountingSwitch(activity);
        control.setChecked(true, false);
        assertEquals(0, ticksFrom(control, () -> control.setChecked(true, false)));
    }

    @Test public void aDisabledSwitchIsSilentAndDoesNotChange() {
        Prefs.get(activity).edit().putBoolean(Prefs.HAPTICS, true).commit();
        CountingSwitch control = new CountingSwitch(activity);
        control.setEnabled(false);
        assertEquals(0, ticksFrom(control, control::toggle));
        assertFalse(control.isChecked());
    }

    @Test public void aRowTapProducesExactlyOneTransitionAndOneTick() {
        Prefs.get(activity).edit().putBoolean(Prefs.HAPTICS, true).commit();
        CountingSwitch control = new CountingSwitch(activity);
        AtomicInteger changes = new AtomicInteger();
        control.setOnCheckedChangeListener((view, checked) -> changes.incrementAndGet());
        LinearLayout row = UiKit.switchRow(activity, "Haptic feedback", null, control);

        int ticks = ticksFrom(control, row::performClick);
        assertEquals("one tap, one tick", 1, ticks);
        assertEquals("one tap, one state change", 1, changes.get());
        assertTrue(control.isChecked());
    }

    @Test public void tappingTheRowAndTheSwitchFeelTheSame() {
        // The row forwards to the same toggle, so neither route can add a second tick.
        Prefs.get(activity).edit().putBoolean(Prefs.HAPTICS, true).commit();
        CountingSwitch viaRow = new CountingSwitch(activity);
        LinearLayout row = UiKit.switchRow(activity, "Haptic feedback", null, viaRow);
        CountingSwitch viaSwitch = new CountingSwitch(activity);

        int rowTicks = ticksFrom(viaRow, row::performClick);
        int switchTicks = ticksFrom(viaSwitch, viaSwitch::performClick);
        assertEquals(1, rowTicks);
        assertEquals("however the tap arrives, it feels identical", rowTicks, switchTicks);
    }

    // ---- the global preference ----

    @Test public void turningHapticsOffGivesOneFinalConfirmation() {
        Prefs.get(activity).edit().putBoolean(Prefs.HAPTICS, true).commit();
        CountingSwitch control = new CountingSwitch(activity);
        control.setChecked(true, false);
        control.setOnCheckedChangeListener((view, checked) ->
                Prefs.get(activity).edit().putBoolean(Prefs.HAPTICS, checked).commit());

        assertEquals(1, ticksFrom(control, control::toggle));
        assertFalse(Prefs.haptics(activity));
    }

    @Test public void turningHapticsOnGivesOneConfirmation() {
        Prefs.get(activity).edit().putBoolean(Prefs.HAPTICS, false).commit();
        CountingSwitch control = new CountingSwitch(activity);
        control.setChecked(false, false);
        control.setOnCheckedChangeListener((view, checked) ->
                Prefs.get(activity).edit().putBoolean(Prefs.HAPTICS, checked).commit());

        assertEquals(1, ticksFrom(control, control::toggle));
        assertTrue(Prefs.haptics(activity));
    }

    @Test public void afterHapticsAreOffOtherSwitchesStaySilent() {
        Prefs.get(activity).edit().putBoolean(Prefs.HAPTICS, false).commit();
        CountingSwitch other = new CountingSwitch(activity);
        assertEquals(0, ticksFrom(other, other::toggle));
        assertEquals(0, ticksFrom(other, other::toggle));
    }

    // ---- accessibility is unaffected ----

    @Test public void accessibilityStateIsUnchangedByHaptics() {
        Prefs.get(activity).edit().putBoolean(Prefs.HAPTICS, false).commit();
        CountingSwitch control = new CountingSwitch(activity);
        control.toggle();

        android.view.accessibility.AccessibilityNodeInfo info =
                android.view.accessibility.AccessibilityNodeInfo.obtain();
        control.onInitializeAccessibilityNodeInfo(info);
        assertTrue(info.isCheckable());
        assertTrue("state must be announced regardless of vibration", info.isChecked());
    }
}
