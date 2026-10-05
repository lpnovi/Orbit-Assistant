package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

/** About & updates links to Orbit's repository, in the browser, without any way to crash. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public class AboutLinksTest {
    private static final String REPO = "https://github.com/lpnovi/Orbit-Assistant";

    @org.junit.Before public void setUp() {
        TestWorkManager.ensureInitialized(RuntimeEnvironment.getApplication());
    }

    @Test public void gitHubIsOnTheLinksCardAndPointsAtTheRepository() {
        View page = page();
        View row = page.findViewWithTag(REPO);
        assertNotNull("GitHub row", row);
        assertTrue(row.isClickable());
        assertTrue(row.getContentDescription().toString().startsWith("GitHub"));
        assertNotNull(text(page, "LINKS"));
        assertEquals(REPO, UpdateActivity.LINKS[0].uri);
    }

    @Test public void tappingOpensTheRepositoryInTheBrowser() {
        UpdateActivity activity = Robolectric.buildActivity(UpdateActivity.class).setup().get();
        activity.getWindow().getDecorView().findViewWithTag(REPO).performClick();
        Intent opened = shadowOf(activity).getNextStartedActivity();
        assertNotNull(opened);
        assertEquals(Intent.ACTION_VIEW, opened.getAction());
        assertEquals(REPO, opened.getDataString());
        assertTrue(opened.hasCategory(Intent.CATEGORY_BROWSABLE));
    }

    @Test public void noBrowserMeansAMessageNotACrash() {
        android.content.ContextWrapper noBrowser =
                new android.content.ContextWrapper(RuntimeEnvironment.getApplication()) {
                    @Override public void startActivity(Intent intent) {
                        throw new android.content.ActivityNotFoundException();
                    }
                };
        assertFalse(UpdateActivity.openExternal(noBrowser, REPO));
        assertEquals("No app on this phone can open that link", ShadowToast.getTextOfLatestToast());
    }

    @Test public void everyEntryBecomesOneRowFromTheSameBuilder() {
        // The card is drawn from LINKS, so adding a link is adding an entry: one row each.
        View page = page();
        ViewGroup card = (ViewGroup) page.findViewWithTag(REPO).getParent();
        assertEquals(UpdateActivity.LINKS.length, card.getChildCount());
        for (UpdateActivity.AboutLink link : UpdateActivity.LINKS) {
            assertTrue(link.uri, link.uri.startsWith("https://"));
            assertNotNull(page.findViewWithTag(link.uri));
        }
    }

    private static View page() {
        return Robolectric.buildActivity(UpdateActivity.class).setup().get()
                .getWindow().getDecorView();
    }

    private static TextView text(View view, String s) {
        if (view instanceof TextView && s.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView found = text(g.getChildAt(i), s);
                if (found != null) return found;
            }
        }
        return null;
    }
}
