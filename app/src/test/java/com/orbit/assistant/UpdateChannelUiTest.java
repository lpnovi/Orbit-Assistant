package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * Joining Beta is a decision, and About &amp; updates has to treat it like one.
 *
 * <p>Cancelling must change nothing at all, accepting must be what enrols the device, leaving Beta
 * must be frictionless, and coming back to Beta later must ask again — because that is a fresh
 * acceptance of the same risk, not a repeat of a question already answered.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class UpdateChannelUiTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        ShadowAlertDialog.reset();
        // About & updates schedules the periodic update check when it opens.
        TestWorkManager.ensureInitialized(context);
    }

    private Activity open() {
        return Robolectric.buildActivity(UpdateActivity.class).setup().get();
    }

    // ---- the row itself ---------------------------------------------------------------------------

    @Test public void aboutAndUpdatesShowsTheChannelAndItsCurrentValue() {
        Activity activity = open();
        String text = allText(activity.getWindow().getDecorView());
        assertTrue("the row must be discoverable", text.contains("Update channel"));
        assertTrue("the selected value must be visible", text.contains("Stable"));
        assertTrue("Update notifications stays its own separate preference",
                text.contains("Update notifications"));
    }

    /** The channel and the installed build are different things and are shown separately. */
    @Test public void aStableBuildShowsNoBetaBranding() {
        Activity activity = open();
        String text = allText(activity.getWindow().getDecorView());
        assertFalse("v0.7.7.4 is a Stable build", text.contains("BETA BUILD"));
        assertTrue(text.contains("Current version: " + BuildConfig.VERSION_NAME));
    }

    // ---- the Beta confirmation --------------------------------------------------------------------

    @Test public void choosingBetaAsksBeforeChangingAnything() {
        Activity activity = open();
        openSelector(activity);
        chooseOption("Beta");

        AlertDialog confirmation = latestDialog();
        assertNotNull("choosing Beta must ask first", confirmation);
        String text = allText(confirmation.getWindow().getDecorView());
        assertTrue(text.contains("Join the Beta channel?"));
        assertTrue("the risk must be stated plainly",
                text.contains("bugs, crashes, regressions"));
        assertTrue("Beta must not be implied to be less trusted",
                text.contains("officially signed and verified by Orbit"));
        assertTrue("leaving must be presented as easy and safe",
                text.contains("never automatically downgrade"));
        assertFalse("nothing changes until the user accepts", Prefs.betaChannel(context));
    }

    @Test public void cancellingTheWarningLeavesTheDeviceOnStable() {
        Activity activity = open();
        openSelector(activity);
        chooseOption("Beta");

        AlertDialog confirmation = latestDialog();
        confirmation.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        idle();

        assertEquals(Prefs.CHANNEL_STABLE, Prefs.updateChannel(context));
        assertFalse(Prefs.betaChannel(context));
    }

    @Test public void acceptingTheWarningEnrolsTheDevice() {
        Activity activity = open();
        openSelector(activity);
        chooseOption("Beta");

        latestDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        idle();

        assertTrue(Prefs.betaChannel(context));
        assertEquals(Prefs.CHANNEL_BETA, Prefs.updateChannel(context));
    }

    // ---- leaving, and coming back -----------------------------------------------------------------

    /** Going back to Stable is the safe direction, so it happens immediately and asks nothing. */
    @Test public void returningToStableAsksNothing() {
        Prefs.setUpdateChannel(context, Prefs.CHANNEL_BETA);
        Activity activity = open();
        openSelector(activity);
        chooseOption("Stable");

        assertEquals(Prefs.CHANNEL_STABLE, Prefs.updateChannel(context));
        AlertDialog remaining = latestDialog();
        if (remaining != null && remaining.isShowing()) {
            assertFalse("leaving Beta must not raise the join warning",
                    allText(remaining.getWindow().getDecorView()).contains("Join the Beta channel?"));
        }
    }

    /** Choosing Beta while already enrolled is a no-op, not a repeat of the warning. */
    @Test public void anAlreadyEnrolledDeviceIsNotAskedAgain() {
        Prefs.setUpdateChannel(context, Prefs.CHANNEL_BETA);
        Activity activity = open();
        openSelector(activity);
        chooseOption("Beta");

        AlertDialog remaining = latestDialog();
        boolean warningShown = remaining != null && remaining.isShowing()
                && allText(remaining.getWindow().getDecorView()).contains("Join the Beta channel?");
        assertFalse("an enrolled device must not be re-asked", warningShown);
        assertTrue(Prefs.betaChannel(context));
    }

    /** Leaving and rejoining is a fresh decision, so the warning comes back. */
    @Test public void rejoiningBetaRequiresConfirmationAgain() {
        Prefs.setUpdateChannel(context, Prefs.CHANNEL_BETA);
        Prefs.setUpdateChannel(context, Prefs.CHANNEL_STABLE);
        assertFalse(Prefs.betaChannel(context));

        Activity activity = open();
        openSelector(activity);
        chooseOption("Beta");

        AlertDialog confirmation = latestDialog();
        assertNotNull(confirmation);
        assertTrue(allText(confirmation.getWindow().getDecorView())
                .contains("Join the Beta channel?"));
        assertFalse("still nothing changed until it is accepted", Prefs.betaChannel(context));

        confirmation.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        idle();
        assertTrue(Prefs.betaChannel(context));
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private void openSelector(Activity activity) {
        View row = findRowLabelled(activity.getWindow().getDecorView(), "Update channel");
        assertNotNull("the Update channel row must exist", row);
        row.performClick();
        idle();
        assertNotNull("tapping the row must open the Orbit selector", latestDialog());
    }

    private void chooseOption(String name) {
        AlertDialog selector = latestDialog();
        assertNotNull(selector);
        View option = findRowLabelled(selector.getWindow().getDecorView(), name);
        assertNotNull("the " + name + " option must be offered", option);
        option.performClick();
        idle();
    }

    /**
     * Robolectric does not run posted work by itself, and a dialog button dismisses through the
     * main looper before its listener runs. Without this the preference is read too early.
     */
    private static void idle() {
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static AlertDialog latestDialog() {
        return ShadowAlertDialog.getLatestAlertDialog();
    }

    /** The nearest clickable ancestor of a TextView whose text is exactly {@code label}. */
    private static View findRowLabelled(View root, String label) {
        List<TextView> matches = new ArrayList<>();
        collectTextViews(root, label, matches);
        for (TextView match : matches) {
            View view = match;
            while (view != null) {
                if (view.isClickable()) return view;
                view = view.getParent() instanceof View ? (View) view.getParent() : null;
            }
        }
        return null;
    }

    private static void collectTextViews(View view, String label, List<TextView> into) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && label.contentEquals(text)) into.add((TextView) view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectTextViews(group.getChildAt(i), label, into);
            }
        }
    }

    private static String allText(View root) {
        StringBuilder out = new StringBuilder();
        appendText(root, out);
        return out.toString();
    }

    private static void appendText(View view, StringBuilder out) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null) out.append(text).append('\n');
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) appendText(group.getChildAt(i), out);
        }
    }
}
