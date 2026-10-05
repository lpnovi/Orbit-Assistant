package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.PopupWindow;
import android.widget.TextView;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Quick controls only move a control; the live preview shows the overlay those choices make. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public class OverlayCustomizationTest {
    private static final String[] KEYS = {Prefs.OVERLAY_SHOW_MODEL, Prefs.OVERLAY_SHOW_HISTORY,
            Prefs.OVERLAY_SHOW_NEW_CHAT, Prefs.OVERLAY_SHOW_SCREEN, Prefs.OVERLAY_SHOW_ATTACH,
            Prefs.OVERLAY_SHOW_VOICE};
    private static final OverlayStyle[] ALL = {OverlayStyle.MODERN, OverlayStyle.FLOAT,
            OverlayStyle.CLASSIC, OverlayStyle.CUTIE};

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    // ---- The arrangement ----

    @Test public void theDefaultsAreTheOverlayAsItWas() {
        OverlayControls modern = OverlayControls.of(context, OverlayStyle.MODERN);
        assertTrue(modern.model && modern.history && modern.newChat && modern.screen
                && modern.attach && modern.voice);
        assertFalse("no More menu by default", modern.hasOverflow());
        assertFalse(OverlayControls.of(context, OverlayStyle.CLASSIC).hasOverflow());
        // Float's More has always held exactly these two.
        assertEquals(Arrays.asList(OverlayControls.HISTORY, OverlayControls.NEW_CHAT),
                OverlayControls.of(context, OverlayStyle.FLOAT).overflow);
    }

    @Test public void everyCombinationKeepsEveryCapabilityReachable() {
        for (OverlayStyle style : ALL) {
            for (int mask = 0; mask < 64; mask++) {
                boolean[] on = new boolean[6];
                for (int i = 0; i < 6; i++) on[i] = (mask & (1 << i)) != 0;
                OverlayControls c = OverlayControls.resolve(style, on[0], on[1], on[2], on[3], on[4], on[5]);
                String where = style.id + " mask " + mask;
                assertTrue(where, c.model || c.overflow.contains(OverlayControls.MODEL));
                assertTrue(where, c.history || c.overflow.contains(OverlayControls.HISTORY));
                assertTrue(where, c.newChat || c.overflow.contains(OverlayControls.NEW_CHAT));
                assertTrue(where, c.attach || c.overflow.contains(OverlayControls.ATTACH));
                assertTrue(where, c.voice || c.overflow.contains(OverlayControls.VOICE));
                // Screen lives in Attach's Screen entry when its own buttons are hidden.
                assertTrue(where, c.screen || c.attach || c.overflow.contains(OverlayControls.ATTACH));
                // More never lists something already on the sheet.
                assertEquals(where, c.hasOverflow(), !c.overflow.isEmpty());
                if (c.model) assertFalse(where, c.overflow.contains(OverlayControls.MODEL));
                if (c.attach) assertFalse(where, c.overflow.contains(OverlayControls.ATTACH));
            }
        }
    }

    @Test public void choicesPersistAndTravelWithABackup() throws Exception {
        Prefs.get(context).edit().putBoolean(Prefs.OVERLAY_SHOW_HISTORY, false)
                .putBoolean(Prefs.OVERLAY_SHOW_VOICE, false).commit();
        JSONObject backup = Prefs.backupSnapshot(context);
        Prefs.get(context).edit().clear().commit();
        assertTrue(OverlayControls.of(context, OverlayStyle.MODERN).history);
        assertTrue(Prefs.restoreBackupSnapshot(context, backup));
        OverlayControls restored = OverlayControls.of(context, OverlayStyle.MODERN);
        assertFalse(restored.history);
        assertFalse(restored.voice);
        assertTrue(restored.model);
    }

    // ---- The real overlay ----

    @Test public void aHiddenControlLeavesItsPlaceAndWaitsInMore() {
        hide(Prefs.OVERLAY_SHOW_HISTORY, Prefs.OVERLAY_SHOW_NEW_CHAT);
        View root = overlay(Prefs.OVERLAY_STYLE_MODERN);
        assertNull(find(root, "Recent chats"));
        assertNull(find(root, "New chat"));
        View more = find(root, "More options");
        assertNotNull(more);
        more.performClick();
        ((View) textView(latestPopup(), "Recent chats").getParent()).performClick();
        ShadowLooper.idleMainLooper();
        assertNotNull("History still opens", textView(root, "No saved chats yet."));
    }

    @Test public void hiddenModelControlsStayReachableFromMore() {
        hide(Prefs.OVERLAY_SHOW_MODEL);
        View root = overlay(Prefs.OVERLAY_STYLE_CLASSIC);
        View more = find(root, "More options");
        more.performClick();
        ((View) textView(latestPopup(), OverlayControls.MODEL).getParent()).performClick();
        ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS);
        View menu = latestPopup();
        assertNotNull("the AI menu opened", startsWith(menu, AiSelection.AUTO_LABEL));
    }

    @Test public void hiddenAttachAndScreenStayReachableThroughAttach() {
        hide(Prefs.OVERLAY_SHOW_ATTACH, Prefs.OVERLAY_SHOW_SCREEN);
        View root = overlay(Prefs.OVERLAY_STYLE_FLOAT);
        assertEquals(View.GONE, find(root, "Attach to message").getVisibility());
        assertEquals(View.GONE, find(root, "Attach current screen").getVisibility());
        assertEquals(View.GONE, find(root, "Select or mark part of the current screen").getVisibility());
        View more = find(root, "More options");
        more.performClick();
        ((View) textView(latestPopup(), OverlayControls.ATTACH).getParent()).performClick();
        ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertNotNull("Attach opens with its Screen entry", textView(root, "Screen"));
    }

    @Test public void aHiddenMicIsStillOfferedInMore() {
        hide(Prefs.OVERLAY_SHOW_VOICE);
        View root = overlay(Prefs.OVERLAY_STYLE_MODERN);
        assertEquals(View.GONE, find(root, "Voice input").getVisibility());
        find(root, "More options").performClick();
        assertNotNull(textView(latestPopup(), OverlayControls.VOICE));
    }

    @Test public void nothingCanBeHiddenIntoABrokenOverlay() {
        hide(KEYS);
        for (OverlayStyle style : ALL) {
            Prefs.get(context).edit().putBoolean(Prefs.LELO_MODE, style.cute).commit();
            View root = overlay(style.id);
            assertNotNull(style.id, input(root));
            assertEquals(style.id, View.VISIBLE, find(root, "Send message").getVisibility());
            assertEquals(style.id, View.VISIBLE, find(root, "Close Orbit").getVisibility());
            assertEquals(style.id, View.VISIBLE, find(root, "More options").getVisibility());
            find(root, "More options").performClick();
            View menu = latestPopup();
            for (String item : Arrays.asList(OverlayControls.MODEL, OverlayControls.HISTORY,
                    OverlayControls.NEW_CHAT, OverlayControls.ATTACH, OverlayControls.VOICE)) {
                assertNotNull(style.id + " " + item, textView(menu, item));
            }
        }
    }

    @Test public void classicKeepsItsLookWithEveryControlShown() {
        View root = overlay(Prefs.OVERLAY_STYLE_CLASSIC);
        assertNull(find(root, "More options"));
        for (String control : new String[]{"Recent chats", "New chat", "Attach to message",
                "Voice input", "Attach current screen"}) {
            assertEquals(control, View.VISIBLE, find(root, control).getVisibility());
        }
    }

    // ---- The preview ----

    @Test public void thePreviewDrawsEveryStyleItIsGiven() {
        OverlayPreviewView preview = new OverlayPreviewView(context);
        for (OverlayStyle style : ALL) {
            preview.show(style, OverlayControls.of(context, style));
            assertSame(style, preview.style());
            assertTrue(preview.getContentDescription().toString().contains(style.label));
            assertNotNull(preview.findViewWithTag(OverlayPreviewView.TAG_VOICE));
        }
        preview.show(OverlayStyle.CUTIE, OverlayControls.of(context, OverlayStyle.CUTIE));
        assertNotNull("the secret style keeps its heart", textView(preview, "♥"));
    }

    @Test public void thePreviewFollowsTheQuickControls() {
        OverlayPreviewView preview = new OverlayPreviewView(context);
        preview.show(OverlayStyle.MODERN, OverlayControls.of(context, OverlayStyle.MODERN));
        assertEquals(View.VISIBLE, preview.findViewWithTag(OverlayPreviewView.TAG_HISTORY).getVisibility());
        assertEquals(View.GONE, preview.findViewWithTag(OverlayPreviewView.TAG_MORE).getVisibility());

        hide(Prefs.OVERLAY_SHOW_HISTORY, Prefs.OVERLAY_SHOW_VOICE);
        preview.show(OverlayStyle.MODERN, OverlayControls.of(context, OverlayStyle.MODERN));
        assertEquals(View.GONE, preview.findViewWithTag(OverlayPreviewView.TAG_HISTORY).getVisibility());
        assertEquals(View.GONE, preview.findViewWithTag(OverlayPreviewView.TAG_VOICE).getVisibility());
        assertEquals(View.VISIBLE, preview.findViewWithTag(OverlayPreviewView.TAG_MORE).getVisibility());

        // Float keeps history in More whatever the switch says, as the overlay does.
        Prefs.get(context).edit().clear().commit();
        preview.show(OverlayStyle.FLOAT, OverlayControls.of(context, OverlayStyle.FLOAT));
        assertEquals(View.GONE, preview.findViewWithTag(OverlayPreviewView.TAG_HISTORY).getVisibility());
        assertEquals(View.VISIBLE, preview.findViewWithTag(OverlayPreviewView.TAG_MORE).getVisibility());
    }

    @Test public void thePreviewIsOnlyAPicture() {
        String source = OrbitGlassChromeTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OverlayPreviewView.java");
        for (String forbidden : new String[]{"OrbitSession", "VoiceInteraction", "AssistantClient",
                "OrbitRequestManager", "ConversationStore", "SpeechRecognizer", "setOnClickListener"}) {
            assertFalse(forbidden, source.contains(forbidden));
        }
    }

    @Test public void settingsSwitchesUpdateThePreviewAndPersist() {
        SettingsActivity activity = settings();
        OverlayPreviewView preview = preview(activity);
        assertSame(OverlayStyle.MODERN, preview.style());
        View history = preview.findViewWithTag(OverlayPreviewView.TAG_HISTORY);
        assertEquals(View.VISIBLE, history.getVisibility());

        quickSwitch(activity, "Recent chats").toggle();
        assertFalse(Prefs.get(context).getBoolean(Prefs.OVERLAY_SHOW_HISTORY, true));
        assertEquals(View.GONE, preview(activity).findViewWithTag(OverlayPreviewView.TAG_HISTORY).getVisibility());
        assertEquals(View.VISIBLE, preview(activity).findViewWithTag(OverlayPreviewView.TAG_MORE).getVisibility());
    }

    @Test public void theStyleListAndPreviewFollowLeloMode() {
        Prefs.get(context).edit().putBoolean(Prefs.LELO_MODE, true).commit();
        Prefs.setOverlayStyle(context, Prefs.OVERLAY_STYLE_CUTIE);
        SettingsActivity activity = settings();
        assertSame(OverlayStyle.CUTIE, preview(activity).style());

        // Seven taps on the footer turn Lelo mode off; the page rebuilds in place.
        TextView footer = startsWith(activity.getWindow().getDecorView(), "Orbit " + BuildConfig.VERSION_NAME);
        for (int i = 0; i < 7; i++) footer.performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(Prefs.leloMode(context));
        assertSame("safe public fallback", OverlayStyle.MODERN, preview(activity).style());
        assertFalse(OverlayStyle.choices(context).contains(OverlayStyle.CUTIE));

        footer = startsWith(activity.getWindow().getDecorView(), "Orbit " + BuildConfig.VERSION_NAME);
        for (int i = 0; i < 7; i++) footer.performClick();
        ShadowLooper.idleMainLooper();
        assertSame("the stored choice comes back", OverlayStyle.CUTIE, preview(activity).style());
    }

    @Test public void searchFindsTheOverlayCustomization() {
        for (String query : new String[]{"overlay", "overlay style", "float", "modern", "classic",
                "quick controls", "overlay buttons", "overlay preview"}) {
            assertFalse(query, SettingsSearchIndex.search(query).isEmpty());
        }
        assertEquals("Quick controls", SettingsSearchIndex.search("quick controls").get(0).title);
        assertEquals("Overlay preview", SettingsSearchIndex.search("overlay preview").get(0).title);
        for (String secret : new String[]{"cutie", "patootie", "lelo"}) {
            assertTrue(secret, SettingsSearchIndex.search(secret).isEmpty());
        }
    }

    // ---- Helpers ----

    private void hide(String... keys) {
        android.content.SharedPreferences.Editor e = Prefs.get(context).edit();
        for (String key : keys) e.putBoolean(key, false);
        e.commit();
    }

    private View overlay(String style) {
        Prefs.setOverlayStyle(context, style);
        OrbitSessionService service = Robolectric.buildService(OrbitSessionService.class).create().get();
        OrbitSession session = (OrbitSession) service.onNewSession(null);
        return session.onCreateContentView();
    }

    private SettingsActivity settings() {
        TestWorkManager.ensureInitialized(context);
        Intent intent = new Intent(context, SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_SECTION, SettingsActivity.SECTION_APPEARANCE);
        return Robolectric.buildActivity(SettingsActivity.class, intent).setup().get();
    }

    private static OverlayPreviewView preview(SettingsActivity activity) {
        List<View> out = new ArrayList<>();
        collect(activity.getWindow().getDecorView(), OverlayPreviewView.class, out);
        assertEquals("one preview", 1, out.size());
        return (OverlayPreviewView) out.get(0);
    }

    private static OrbitSwitch quickSwitch(SettingsActivity activity, String label) {
        ViewGroup quick = (ViewGroup) textView(activity.getWindow().getDecorView(), "Quick controls").getParent();
        ViewGroup row = (ViewGroup) textView(quick, label).getParent();
        while (row != null) {
            List<View> switches = new ArrayList<>();
            collect(row, OrbitSwitch.class, switches);
            if (!switches.isEmpty()) return (OrbitSwitch) switches.get(0);
            row = (ViewGroup) row.getParent();
        }
        throw new AssertionError("no switch for " + label);
    }

    private static void collect(View view, Class<?> type, List<View> out) {
        if (type.isInstance(view)) out.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), type, out);
        }
    }

    private static View latestPopup() {
        PopupWindow popup = shadowOf(RuntimeEnvironment.getApplication()).getLatestPopupWindow();
        assertNotNull("menu opened", popup);
        return popup.getContentView();
    }

    private static EditText input(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                EditText found = input(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static TextView textView(View view, String text) {
        if (view instanceof TextView && !(view instanceof EditText)
                && text.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView found = textView(g.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static TextView startsWith(View view, String prefix) {
        if (view instanceof TextView && !(view instanceof EditText)
                && ((TextView) view).getText().toString().startsWith(prefix)) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView found = startsWith(g.getChildAt(i), prefix);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View find(View view, String description) {
        if (view.getContentDescription() != null
                && description.contentEquals(view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = find(g.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }
}
