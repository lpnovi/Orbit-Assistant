package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * Floating glass controls line up with the rest of their page at every width.
 *
 * <p>Found on a real tablet: Chats' New chat and the list spanned the page while Search stopped at
 * a shared 520dp cap, about half a landscape screen, and the Vault's Search and selectors did the
 * same under a full-width Save to Vault. Each test lays the page out at the configured screen size,
 * so a phone and a tablet in both orientations are checked on real geometry: the floating controls
 * start and end exactly where the primary button and the list do.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class FloatingControlWidthTest {

    /** The cap this replaced, kept only to prove a tablet is now wider than it. */
    private static final int OLD_CAP_DP = 520;

    private Context context;
    private ActivityController<MainActivity> chats;
    private ActivityController<OrbitVaultActivity> vault;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        TestWorkManager.ensureInitialized(context);
        Prefs.get(context).edit().clear().commit();
        OrbitThemeStore.clearForTests(context);
        OrbitVaultStore.prefs(context).edit().clear().commit();
        ConversationStore.clear(context);
        OnboardingState.markCompleted(context);
        UiKit.syncTheme(context);
    }

    @After public void tearDown() {
        if (chats != null) chats.pause().stop().destroy();
        if (vault != null) vault.pause().stop().destroy();
    }

    // ---- Chats -------------------------------------------------------------------------------------

    @Test @Config(qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void onAPhoneChatsSearchMatchesNewChatAndTheList() {
        View search = chatsSearchAligned();
        assertEquals("a phone keeps its full-width Search",
                pageContentWidth(search), search.getWidth());
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    public void onATabletInLandscapeChatsSearchMatchesNewChatAndTheList() {
        assertTrue(chatsSearchAligned().getWidth() > UiKit.dp(context, OLD_CAP_DP));
    }

    @Test @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    public void onATabletInPortraitChatsSearchMatchesNewChatAndTheList() {
        assertTrue(chatsSearchAligned().getWidth() > UiKit.dp(context, OLD_CAP_DP));
    }

    // ---- Vault -------------------------------------------------------------------------------------

    @Test @Config(qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void onAPhoneVaultControlsMatchSaveAndTheList() {
        View search = vaultControlsAligned();
        assertEquals(pageContentWidth(search), search.getWidth());
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    public void onATabletInLandscapeVaultControlsMatchSaveAndTheList() {
        assertTrue(vaultControlsAligned().getWidth() > UiKit.dp(context, OLD_CAP_DP));
    }

    @Test @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    public void onATabletInPortraitVaultControlsMatchSaveAndTheList() {
        assertTrue(vaultControlsAligned().getWidth() > UiKit.dp(context, OLD_CAP_DP));
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private View chatsSearchAligned() {
        String id = ConversationStore.newId();
        List<AssistantClient.History> history = new ArrayList<>();
        history.add(new AssistantClient.History("user", "Charger and passport"));
        ConversationStore.save(context, id, history);
        chats = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = chats.get();
        layout(activity);

        View search = activity.searchSurfaceForTest();
        ViewGroup page = (ViewGroup) search.getParent();
        View list = activity.chromeForTest().host();
        assertSame(page, list.getParent());
        assertSameColumn(buttonNamed(page, "New chat"), search, list);
        return search;
    }

    private View vaultControlsAligned() {
        OrbitVaultStore.saveText(context, "Packing list", "Charger and passport",
                OrbitVaultSource.QUICK_CAPTURE);
        vault = Robolectric.buildActivity(OrbitVaultActivity.class).setup();
        OrbitVaultActivity activity = vault.get();
        layout(activity);

        View search = activity.searchSurfaceForTest();
        ViewGroup page = (ViewGroup) search.getParent();
        View filters = page.getChildAt(page.indexOfChild(search) + 1);
        View list = activity.chromeForTest().host();
        assertSame(page, list.getParent());
        View save = buttonNamed(page, "Save to Vault");
        assertSameColumn(save, search, list);
        assertSameColumn(save, filters, list);
        return search;
    }

    private static void assertSameColumn(View primary, View control, View list) {
        assertTrue("the page must have been laid out", primary.getWidth() > 0);
        assertEquals("the control starts where the primary button starts",
                primary.getLeft(), control.getLeft());
        assertEquals("and ends where it ends", primary.getRight(), control.getRight());
        assertEquals("the list shares the same column", list.getLeft(), control.getLeft());
        assertEquals(list.getRight(), control.getRight());
    }

    /** The width inside the page's own padding, which is what "the content column" means. */
    private static int pageContentWidth(View child) {
        View page = (View) child.getParent();
        return page.getWidth() - page.getPaddingLeft() - page.getPaddingRight();
    }

    private static View buttonNamed(ViewGroup page, String label) {
        for (int i = 0; i < page.getChildCount(); i++) {
            View child = page.getChildAt(i);
            if (child instanceof Button && ((Button) child).getText().toString().contains(label)) {
                return child;
            }
        }
        assertNotNull("the page has a " + label + " button", null);
        return null;
    }

    private static void layout(Activity activity) {
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        View root = activity.getWindow().getDecorView();
        root.measure(
                View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, metrics.widthPixels, metrics.heightPixels);
    }
}
