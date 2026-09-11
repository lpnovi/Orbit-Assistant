package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
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

import java.util.ArrayList;
import java.util.List;

/**
 * The Orbit Pro Preview override, where it lives and where it must not.
 *
 * <p>Diagnostics is the hidden developer and support surface, reached by long-pressing the Settings
 * version footer, and that is the whole reason this control is there rather than in Settings. A
 * switch whose only job is to fake an entitlement has no business on a screen somebody browses.
 *
 * <p>The assertion that matters most is not that the selector works. It is that Diagnostics does
 * not know what makes a build eligible to have one. The moment this screen learns to check
 * {@code BuildConfig.DEBUG} for itself, there are two copies of a rule that must hold on every
 * Stable build Orbit ever ships, and only one of them is tested.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class DiagnosticsProPreviewTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    private static Activity screen() {
        return Robolectric.buildActivity(DiagnosticsActivity.class).setup().get();
    }

    private static List<TextView> texts(Activity activity) {
        List<TextView> found = new ArrayList<>();
        collect(activity.getWindow().getDecorView(), found);
        return found;
    }

    private static void collect(View view, List<TextView> into) {
        if (view instanceof TextView) into.add((TextView) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), into);
        }
    }

    private static String allText(Activity activity) {
        StringBuilder out = new StringBuilder();
        for (TextView text : texts(activity)) {
            out.append(text.getText() == null ? "" : text.getText()).append('\n');
        }
        return out.toString();
    }

    /** The clickable control whose label is exactly this word, which the two choices are. */
    private static TextView choice(Activity activity, String label) {
        for (TextView text : texts(activity)) {
            if (text.isClickable() && label.contentEquals(text.getText())) return text;
        }
        return null;
    }

    // ---- 15. visibility follows the build policy, and only the build policy -----------------------

    /**
     * The selector exists on this build, and exists exactly when the entitlement layer says so.
     *
     * <p>A unit test runs against the debug variant, so the eligible branch is the one that can be
     * exercised here. What the assertion pins is the equivalence rather than the constant: the
     * area is present if and only if {@link OrbitProEntitlement#previewAvailable()} is true.
     */
    @Test public void theSelectorIsShownExactlyWhenThePreviewIsAvailable() {
        boolean available = OrbitProEntitlement.previewAvailable();
        assertTrue("a debug unit-test build must be eligible", available);

        Activity activity = screen();
        assertEquals(available, allText(activity).contains("ORBIT PRO PREVIEW"));
        assertEquals(available, choice(activity, "Free") != null);
        assertEquals(available, choice(activity, "Pro Preview") != null);
    }

    /**
     * Diagnostics never re-derives eligibility, which is what keeps Stable safe.
     *
     * <p>On a Stable build {@link OrbitProEntitlement#previewAvailable()} is false, so the early
     * return in {@code addProPreview} means no caption, no selector, and no hidden way to reach
     * one. That behaviour is only guaranteed while this screen asks rather than decides.
     */
    @Test public void diagnosticsAsksTheEntitlementLayerRatherThanTheBuild() {
        String source = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/DiagnosticsActivity.java");
        assertTrue("the screen must ask the entitlement layer",
                source.contains("OrbitProEntitlement.previewAvailable()"));
        assertFalse("and must not check the build itself", source.contains("BuildConfig.DEBUG"));
        assertFalse("nor parse the version itself", source.contains("installedIsBeta"));
        assertTrue("the area is skipped entirely rather than disabled",
                source.contains("if (!OrbitProEntitlement.previewAvailable()) return;"));
    }

    // ---- the control itself ---------------------------------------------------------------------

    /** It opens on Free, with the effective entitlement stated beside it. */
    @Test public void itOpensOnFreeAndSaysWhatIsEffective() {
        Activity activity = screen();
        String text = allText(activity);
        assertTrue(text.contains("Selected: Free"));
        assertTrue(text.contains("Effective: Free (preview override)"));
        assertTrue("the Overview reports the same thing",
                text.contains("Orbit Pro: Free (preview override)"));
        assertTrue("and the area says what it is, so nobody mistakes it for a purchase",
                text.contains("Testing override for this build only")
                        && text.contains("nothing here is a purchase"));
    }

    /**
     * Choosing Pro Preview updates the screen in the same frame, with no restart.
     *
     * <p>Both places that report entitlement have to move together. A selector that said Pro
     * Preview while the Overview still said Free would be worse than no control at all, because
     * the whole purpose of the control is to be believed.
     */
    @Test public void choosingProPreviewUpdatesImmediately() {
        Activity activity = screen();
        TextView pro = choice(activity, "Pro Preview");
        assertNotNull(pro);
        pro.performClick();

        assertTrue("the selection is stored", Prefs.proPreviewSelected(activity));
        assertTrue("and entitlement follows at once", OrbitProEntitlement.hasPro(activity));

        String text = allText(activity);
        assertTrue(text.contains("Selected: Pro Preview"));
        assertTrue(text.contains("Effective: Pro Preview"));
        assertTrue("including the Overview above it", text.contains("Orbit Pro: Pro Preview"));
        assertTrue("and the chosen control reads as chosen",
                choice(activity, "Pro Preview").isSelected());
        assertFalse(choice(activity, "Free").isSelected());
    }

    /** And choosing Free again returns to the locked state, just as immediately. */
    @Test public void choosingFreeAgainReturnsToTheLockedState() {
        Prefs.setProPreview(context, Prefs.PRO_PREVIEW_PRO);
        Activity activity = screen();
        assertTrue(allText(activity).contains("Selected: Pro Preview"));

        TextView free = choice(activity, "Free");
        assertNotNull(free);
        free.performClick();

        assertFalse(OrbitProEntitlement.hasPro(activity));
        String text = allText(activity);
        assertTrue(text.contains("Selected: Free"));
        assertTrue(text.contains("Orbit Pro: Free (preview override)"));
        assertTrue(choice(activity, "Free").isSelected());
    }

    /** A selection made in a previous session is where the tester left it. */
    @Test public void aPreviousSelectionIsStillThereOnReopen() {
        Prefs.setProPreview(context, Prefs.PRO_PREVIEW_PRO);
        assertTrue(allText(screen()).contains("Selected: Pro Preview"));
        assertTrue(allText(screen()).contains("Effective: Pro Preview"));
    }

    /**
     * Diagnostics reports entitlement on every build, and never anything private about it.
     *
     * <p>One informational line. No token, no order, no account, and no badge: the screen gained a
     * fact, not a decoration.
     */
    @Test public void theOverviewLineIsInformationalAndSafeToCopy() {
        Activity activity = screen();
        DiagnosticsActivity diagnostics = (DiagnosticsActivity) activity;
        String full = diagnostics.fullReport();
        assertTrue("the copied report carries the effective state",
                full.contains("Orbit Pro: " + OrbitProEntitlement.status(activity)));

        // The entitlement line is one short phrase and must stay one short phrase. Anything a
        // provider knows privately has to stop at the boundary rather than reach the clipboard.
        String line = OrbitProEntitlement.status(activity);
        for (String secret : new String[]{"token", "order", "account", "purchase", "license"}) {
            assertFalse("the entitlement line must never expose " + secret,
                    line.toLowerCase().contains(secret));
        }
    }
}
