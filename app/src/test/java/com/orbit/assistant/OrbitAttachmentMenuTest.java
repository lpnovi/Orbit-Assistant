package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * The attachment chooser must live in the host's own hierarchy and must never take input focus,
 * because that is what previously dropped the keyboard and, in the overlay, reshaped the sheet.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class OrbitAttachmentMenuTest {
    private static final String[] LABELS =
            {"Camera", "Gallery", "File", "Screen", "Clipboard"};

    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout host;
    private EditText composer;
    private View anchor;

    @Before public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).create();
        activity = controller.get();
        host = new FrameLayout(activity);
        composer = new EditText(activity);
        composer.setFocusable(true);
        composer.setFocusableInTouchMode(true);
        anchor = new View(activity);
        host.addView(composer);
        host.addView(anchor);
        activity.setContentView(host);
        // The hierarchy has to be attached and the window focused before an editor can hold
        // focus, which is the state this component's whole purpose depends on.
        controller.start().resume().visible();
        host.layout(0, 0, 1080, 1920);
    }

    @After public void tearDown() {
        controller.pause().stop().destroy();
    }

    private static View findMenuCard(ViewGroup host) {
        // The card is the last tagged child; the scrim is added first.
        View found = null;
        for (int i = 0; i < host.getChildCount(); i++) {
            View child = host.getChildAt(i);
            if ("orbit_attachment_menu".equals(child.getTag())) found = child;
        }
        return found;
    }

    private static int taggedChildCount(ViewGroup host) {
        int count = 0;
        for (int i = 0; i < host.getChildCount(); i++) {
            if ("orbit_attachment_menu".equals(host.getChildAt(i).getTag())) count++;
        }
        return count;
    }

    @Test public void nothingIsShowingUntilItIsOpened() {
        assertFalse(OrbitAttachmentMenu.isShowing(host));
    }

    @Test public void openingAddsTheMenuToTheHostsOwnHierarchy() {
        OrbitAttachmentMenu.show(host, anchor, LABELS, (i, l) -> {});

        assertTrue(OrbitAttachmentMenu.isShowing(host));
        assertNotNull("the chooser must be a child of the host, not a separate window",
                findMenuCard(host));
    }

    @Test public void theComposerKeepsInputFocusWhileTheMenuIsOpen() {
        composer.requestFocus();
        assertTrue("the editor must start focused for this test to mean anything",
                composer.hasFocus());

        OrbitAttachmentMenu.show(host, anchor, LABELS, (i, l) -> {});

        // Nothing in the chooser requests focus, so the editor that owns the keyboard keeps it.
        assertTrue("opening the chooser must not move input focus", composer.hasFocus());
        assertEquals("the focused view must be unchanged", composer, host.findFocus());
    }

    @Test public void noViewInTheMenuCanTakeFocus() {
        OrbitAttachmentMenu.show(host, anchor, LABELS, (i, l) -> {});
        for (int i = 0; i < host.getChildCount(); i++) {
            View child = host.getChildAt(i);
            if (!"orbit_attachment_menu".equals(child.getTag())) continue;
            assertNoFocusable(child);
        }
    }

    private static void assertNoFocusable(View view) {
        assertFalse("a focusable menu view would displace the composer and drop the keyboard",
                view.isFocusable());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) assertNoFocusable(group.getChildAt(i));
        }
    }

    @Test public void theComposerStillHasFocusAfterDismissal() {
        composer.requestFocus();
        OrbitAttachmentMenu.show(host, anchor, LABELS, (i, l) -> {});
        OrbitAttachmentMenu.dismiss(host);

        assertFalse(OrbitAttachmentMenu.isShowing(host));
        assertTrue("typing must continue without another tap", composer.hasFocus());
    }

    @Test public void onlyOneMenuIsEverOpen() {
        OrbitAttachmentMenu.show(host, anchor, LABELS, (i, l) -> {});
        OrbitAttachmentMenu.show(host, anchor, LABELS, (i, l) -> {});
        OrbitAttachmentMenu.show(host, anchor, LABELS, (i, l) -> {});

        // One scrim and one card, no matter how often the button is pressed.
        assertEquals(2, taggedChildCount(host));
    }

    @Test public void dismissReportsWhetherItActuallyClosedSomething() {
        // Back navigation relies on this to decide whether to consume the press.
        assertFalse(OrbitAttachmentMenu.dismiss(host));

        OrbitAttachmentMenu.show(host, anchor, LABELS, (i, l) -> {});
        assertTrue(OrbitAttachmentMenu.dismiss(host));
        assertFalse(OrbitAttachmentMenu.dismiss(host));
    }

    @Test public void dismissingLeavesTheHostAsItWas() {
        int before = host.getChildCount();
        OrbitAttachmentMenu.show(host, anchor, LABELS, (i, l) -> {});
        OrbitAttachmentMenu.dismiss(host);

        assertEquals("the chooser must not leave anything behind", before, host.getChildCount());
    }

    @Test public void choosingAnOptionDispatchesItAndClosesTheMenu() {
        final int[] chosen = {-1};
        final String[] chosenLabel = {null};
        OrbitAttachmentMenu.show(host, anchor, LABELS, (i, l) -> {
            chosen[0] = i;
            chosenLabel[0] = l;
        });

        ViewGroup card = (ViewGroup) findMenuCard(host);
        assertNotNull(card);
        card.getChildAt(3).performClick();

        assertEquals(3, chosen[0]);
        assertEquals("Screen", chosenLabel[0]);
        assertFalse("selecting an option closes the chooser", OrbitAttachmentMenu.isShowing(host));
    }

    @Test public void everyOptionIsPresentInOrder() {
        OrbitAttachmentMenu.show(host, anchor, LABELS, (i, l) -> {});
        ViewGroup card = (ViewGroup) findMenuCard(host);
        assertNotNull(card);
        assertEquals(LABELS.length, card.getChildCount());

        for (int i = 0; i < LABELS.length; i++) {
            final int[] chosen = {-1};
            OrbitAttachmentMenu.show(host, anchor, LABELS, (index, label) -> chosen[0] = index);
            ViewGroup reopened = (ViewGroup) findMenuCard(host);
            reopened.getChildAt(i).performClick();
            assertEquals("option " + LABELS[i] + " dispatched the wrong index", i, chosen[0]);
        }
    }

    @Test public void theScreenSubmenuUsesTheSameComponent() {
        String[] options = {"Use full screen", "Select or mark area"};
        final int[] chosen = {-1};
        OrbitAttachmentMenu.show(host, anchor, options, (i, l) -> chosen[0] = i);

        ViewGroup card = (ViewGroup) findMenuCard(host);
        assertNotNull(card);
        assertEquals(2, card.getChildCount());
        card.getChildAt(1).performClick();
        assertEquals(1, chosen[0]);
    }

    @Test public void tappingOutsideClosesTheMenu() {
        OrbitAttachmentMenu.show(host, anchor, LABELS, (i, l) -> {});
        // The scrim is the first tagged child.
        for (int i = 0; i < host.getChildCount(); i++) {
            View child = host.getChildAt(i);
            if ("orbit_attachment_menu".equals(child.getTag())) {
                child.performClick();
                break;
            }
        }
        assertFalse(OrbitAttachmentMenu.isShowing(host));
    }

    @Test public void badInputIsIgnoredRatherThanCrashing() {
        OrbitAttachmentMenu.show(null, anchor, LABELS, (i, l) -> {});
        OrbitAttachmentMenu.show(host, null, LABELS, (i, l) -> {});
        OrbitAttachmentMenu.show(host, anchor, null, (i, l) -> {});
        OrbitAttachmentMenu.show(host, anchor, new String[0], (i, l) -> {});
        assertFalse(OrbitAttachmentMenu.isShowing(host));

        // A missing callback must still open and close cleanly.
        OrbitAttachmentMenu.show(host, anchor, LABELS, null);
        assertTrue(OrbitAttachmentMenu.isShowing(host));
        ViewGroup card = (ViewGroup) findMenuCard(host);
        card.getChildAt(0).performClick();
        assertFalse(OrbitAttachmentMenu.isShowing(host));
    }

    @Test public void isShowingHandlesAMissingHost() {
        assertFalse(OrbitAttachmentMenu.isShowing(null));
        assertFalse(OrbitAttachmentMenu.dismiss(null));
    }

    /** Stands in for the overlay's sheet, which carries a real elevation inside the session root. */
    private View addElevatedContent(float elevationDp) {
        View content = new View(activity);
        content.setElevation(UiKit.dp(activity, elevationDp));
        host.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return content;
    }

    private View menuScrim() {
        for (int i = 0; i < host.getChildCount(); i++) {
            View child = host.getChildAt(i);
            if ("orbit_attachment_menu".equals(child.getTag())) return child;
        }
        return null;
    }

    @Test public void theChooserRisesAboveAnElevatedSheet() {
        // The overlay sheet sits at 16dp. A fixed 12dp card drew behind it and could not be seen
        // or tapped, which is exactly what happened on the device.
        View sheet = addElevatedContent(16f);

        OrbitAttachmentMenu.show(host, anchor, LABELS, (i, l) -> {});

        View card = findMenuCard(host);
        View scrim = menuScrim();
        assertNotNull(card);
        assertNotNull(scrim);
        assertTrue("the chooser must draw above the sheet, not behind it",
                card.getZ() > sheet.getZ());
        assertTrue("the outside-tap layer must also clear the sheet",
                scrim.getZ() > sheet.getZ());
    }

    @Test public void theCardAlwaysSitsAboveItsOwnScrim() {
        addElevatedContent(16f);
        OrbitAttachmentMenu.show(host, anchor, LABELS, (i, l) -> {});

        assertTrue("a scrim above the card would swallow every option tap",
                findMenuCard(host).getZ() > menuScrim().getZ());
    }

    @Test public void theChooserClearsWhateverElevationTheHostHappensToUse() {
        // Derived from the hierarchy, so a later layout change cannot overtake it.
        for (float elevation : new float[]{0f, 4f, 16f, 32f, 64f}) {
            OrbitAttachmentMenu.dismiss(host);
            host.removeAllViews();
            host.addView(composer);
            host.addView(anchor);
            View content = addElevatedContent(elevation);

            OrbitAttachmentMenu.show(host, anchor, LABELS, (i, l) -> {});
            assertTrue("failed to clear content at " + elevation + "dp",
                    findMenuCard(host).getZ() > content.getZ());
        }
    }

    @Test public void theCardStaysInsideTheHostBounds() {
        host.layout(0, 0, 1080, 1920);
        OrbitAttachmentMenu.show(host, anchor, LABELS, (i, l) -> {});

        FrameLayout.LayoutParams lp =
                (FrameLayout.LayoutParams) findMenuCard(host).getLayoutParams();
        assertTrue("negative margins would push the card off screen", lp.leftMargin >= 0);
        assertTrue(lp.bottomMargin >= 0);
        assertTrue("the card must not be pushed past the top of the host",
                lp.bottomMargin < host.getHeight());
    }

    @Test public void anAnchorNearTheTopStillLeavesTheCardOnScreen() {
        host.layout(0, 0, 1080, 1920);
        // An anchor at the very top would otherwise produce a bottom margin taller than the host.
        anchor.layout(0, 0, 100, 100);
        OrbitAttachmentMenu.show(host, anchor, LABELS, (i, l) -> {});

        FrameLayout.LayoutParams lp =
                (FrameLayout.LayoutParams) findMenuCard(host).getLayoutParams();
        assertTrue(lp.bottomMargin < host.getHeight());
    }

    @Test public void openingTheChooserDoesNotImmediatelyCloseIt() {
        // The scrim is added during the button's click, which happens on the release of a gesture
        // the button already owns, so it cannot receive that same touch.
        OrbitAttachmentMenu.show(host, anchor, LABELS, (i, l) -> {});
        assertTrue("the chooser must survive the interaction that opened it",
                OrbitAttachmentMenu.isShowing(host));
    }
}
