package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Look &amp; Feel has one color destination, and everything else it offers is not a color.
 *
 * <p>Beta 1 left Orbit with two apparently authoritative theming systems on one screen: Theme
 * Studio at the top, and immediately below it the accent menu, the AMOLED switch and the two bubble
 * menus that predate it. They wrote the same preferences, so nothing could go out of sync, and that
 * was never the problem. The problem was that the screen asked a question it could not answer, and
 * on the device the two cards sat close enough together to read as one surface with two minds.
 *
 * <p>So this file asserts an absence as much as a presence: Theme Studio is reachable and describes
 * the current theme, and the colour editors that used to sit under it are gone rather than hidden.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class LookAndFeelConsolidationTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        OrbitThemeStore.clearForTests(context);
        TestWorkManager.ensureInitialized(context);
    }

    private ActivityController<SettingsActivity> lookAndFeel() {
        Intent intent = new Intent(context, SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_SECTION, SettingsActivity.SECTION_APPEARANCE);
        return Robolectric.buildActivity(SettingsActivity.class, intent).setup();
    }

    private static List<View> descendants(View root) {
        List<View> out = new ArrayList<>();
        out.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                out.addAll(descendants(group.getChildAt(i)));
            }
        }
        return out;
    }

    /** Every visible word on the page, including content descriptions. */
    private static String visibleText(Activity activity) {
        StringBuilder out = new StringBuilder();
        for (View view : descendants(activity.getWindow().getDecorView())) {
            if (view.getVisibility() != View.VISIBLE) continue;
            if (view instanceof TextView) {
                out.append(((TextView) view).getText()).append('\n');
            }
            CharSequence description = view.getContentDescription();
            if (description != null) out.append(description).append('\n');
        }
        return out.toString();
    }

    // ---- one destination -------------------------------------------------------------------------

    @Test public void themeStudioIsOfferedAndDescribesTheCurrentTheme() {
        ActivityController<SettingsActivity> controller = lookAndFeel();
        String text = visibleText(controller.get());
        assertTrue("Theme Studio must be reachable from Look & Feel",
                text.contains("Theme Studio"));
        assertTrue("and it must say what the theme currently is",
                text.contains(OrbitThemeStore.active(context).name));
        controller.pause().stop().destroy();
    }

    /** The summary names the accent by its Orbit name, not by a hex value. */
    @Test public void theSummaryNamesTheAccentTheWayOrbitDoes() {
        OrbitThemeStore.applyActive(context,
                OrbitTheme.custom("Midnight", "nova", OrbitTheme.CLASSIC,
                        OrbitTheme.CLASSIC, OrbitTheme.CLASSIC, OrbitTheme.CLASSIC, true));
        ActivityController<SettingsActivity> controller = lookAndFeel();
        String text = visibleText(controller.get());
        assertTrue("the theme's own name", text.contains("Midnight"));
        assertTrue("its accent, by name", text.contains("Nova"));
        assertTrue("and that it is true black", text.contains("AMOLED"));
        assertFalse("never a raw hex value", text.contains("#4C00FF"));
        controller.pause().stop().destroy();
    }

    /**
     * The colour editors Theme Studio replaced are not on this screen any more.
     *
     * <p>Asserted through the labels a person actually saw, because that is what made the screen
     * ambiguous. A stray "Accent" inside a longer sentence is fine; a control labelled "Accent"
     * sitting under Theme Studio is the thing that had to go.
     */
    @Test public void theDuplicateColourControlsAreGone() {
        ActivityController<SettingsActivity> controller = lookAndFeel();
        Activity activity = controller.get();
        List<String> labels = new ArrayList<>();
        for (View view : descendants(activity.getWindow().getDecorView())) {
            if (view.getVisibility() != View.VISIBLE || !(view instanceof TextView)) continue;
            labels.add(((TextView) view).getText().toString().trim());
        }
        for (String gone : new String[]{"Accent", "Conversation colors", "Your bubbles",
                "Orbit bubbles", "Use true black AMOLED backgrounds"}) {
            assertFalse("Look & Feel still offers a second editor for " + gone,
                    labels.contains(gone));
        }
        controller.pause().stop().destroy();
    }

    // ---- what stays ------------------------------------------------------------------------------

    /**
     * Typography and feedback are not colours and stay where they are.
     *
     * <p>The point of the consolidation was one destination for theme colours, not moving every
     * preference into Theme Studio. Applying a saved theme must never change the font or turn the
     * haptics off.
     */
    @Test public void fontChatSizeAndHapticsAllRemain() {
        ActivityController<SettingsActivity> controller = lookAndFeel();
        String text = visibleText(controller.get());
        assertTrue(text.contains("App font"));
        assertTrue(text.contains("Chat text size"));
        assertTrue(text.contains("Haptic feedback"));
        assertTrue(text.contains("Page transitions"));
        controller.pause().stop().destroy();
    }

    /** A theme carries colours and nothing else, so applying one cannot move an unrelated setting. */
    @Test public void applyingAThemeLeavesEveryNonColourPreferenceAlone() {
        Prefs.get(context).edit()
                .putString(Prefs.APP_FONT, "condensed")
                .putBoolean(Prefs.HAPTICS, false)
                .putString(Prefs.CHAT_TEXT_SIZE, Prefs.CHAT_TEXT_LARGE)
                .putString(Prefs.PAGE_TRANSITION, Prefs.PAGE_TRANSITION_NONE)
                .commit();

        OrbitThemeStore.applyActive(context, OrbitTheme.builtIn(OrbitTheme.ID_NOVA_AMOLED));

        assertEquals("condensed", Prefs.appFont(context));
        assertFalse(Prefs.haptics(context));
        assertEquals(Prefs.CHAT_TEXT_LARGE, Prefs.get(context)
                .getString(Prefs.CHAT_TEXT_SIZE, Prefs.CHAT_TEXT_DEFAULT));
        assertEquals(Prefs.PAGE_TRANSITION_NONE, Prefs.pageTransition(context));
    }

    // ---- the page still hangs together -----------------------------------------------------------

    /**
     * Every major card on Look &amp; Feel is introduced by a section heading.
     *
     * <p>On the device the Theme Studio card and the card under it very nearly touched, because the
     * second one was added straight after the first with no heading and no margin between them.
     * Orbit's own spacing lives on the section heading, so the fix is structural rather than a
     * one-off gap: a heading between two cards produces the same separation every other pair on the
     * page already has.
     */
    @Test public void everyCardOnThePageIsIntroducedByASectionHeading() {
        ActivityController<SettingsActivity> controller = lookAndFeel();
        ViewGroup page = settingsPage(controller.get());
        assertNotNull(page);

        boolean headingSinceLastCard = false;
        int cards = 0;
        for (int i = 0; i < page.getChildCount(); i++) {
            View child = page.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            Object tag = child.getTag();
            if (!(tag instanceof String)) continue;
            String value = (String) tag;
            if (value.startsWith("orbit_settings_section:")) {
                headingSinceLastCard = true;
            } else if (value.startsWith("orbit_card")) {
                cards++;
                assertTrue("card " + cards + " follows another card with nothing between them",
                        headingSinceLastCard);
                headingSinceLastCard = false;
            }
        }
        assertTrue("Look & Feel should have several cards", cards >= 3);
        controller.pause().stop().destroy();
    }

    /** The scrolling column Settings builds its cards into. */
    private static ViewGroup settingsPage(Activity activity) {
        ViewGroup best = null;
        int mostTagged = 0;
        for (View view : descendants(activity.getWindow().getDecorView())) {
            if (!(view instanceof ViewGroup)) continue;
            ViewGroup group = (ViewGroup) view;
            int tagged = 0;
            for (int i = 0; i < group.getChildCount(); i++) {
                Object tag = group.getChildAt(i).getTag();
                if (!(tag instanceof String)) continue;
                String value = (String) tag;
                if (value.startsWith("orbit_card") || value.startsWith("orbit_settings_section:")) tagged++;
            }
            if (tagged > mostTagged) { mostTagged = tagged; best = group; }
        }
        return best;
    }

    // ---- copy -------------------------------------------------------------------------------------

    /**
     * The surfaces this feature owns are written in American English and use no em dash.
     *
     * <p>A source scan rather than a rendered one, because a string only reaches the screen in the
     * state it is written in, and half of these live in menus and dialogs that no Robolectric pass
     * opens. Only string literals are read: Javadoc and comments are prose about the code and are
     * left alone, which is also what keeps this from becoming a scan of unrelated files.
     */
    @Test public void everyUserFacingStringOnTheseSurfacesIsAmericanEnglish() {
        for (String file : new String[]{
                "ThemeStudioActivity.java", "ThemePreviewView.java", "OrbitColorPicker.java",
                "OrbitColorName.java", "OrbitTheme.java", "OrbitPalette.java"}) {
            for (String literal : stringLiterals(source(file))) {
                assertFalse(file + " uses British spelling: " + literal,
                        literal.toLowerCase(Locale.US).contains("colour"));
                assertFalse(file + " uses an em dash: " + literal, literal.contains("—"));
            }
        }
    }

    /** The same rule for the Look &amp; Feel copy inside Settings. */
    @Test public void theLookAndFeelCopyIsAmericanEnglishAndFreeOfEmDashes() {
        for (String literal : stringLiterals(source("SettingsActivity.java"))) {
            assertFalse("Settings uses British spelling: " + literal,
                    literal.toLowerCase(Locale.US).contains("colour"));
        }
        String source = source("SettingsActivity.java");
        int start = source.indexOf("page.addView(sectionTitle(\"LOOK & FEEL\"");
        int end = source.indexOf("page.addView(sectionTitle(\"GESTURES\"");
        assertTrue("the Look & Feel body must be findable", start > 0 && end > start);
        for (String literal : stringLiterals(source.substring(start, end))) {
            assertFalse("Look & Feel uses an em dash: " + literal, literal.contains("—"));
        }
    }

    /** Nothing in this release's Theme Studio copy is an emoji or other decoration. */
    @Test public void thePresetNoteIsPlainText() {
        assertEquals("Creator's favorite", OrbitTheme.CREATOR_FAVORITE);
        assertFalse(OrbitTheme.CREATOR_FAVORITE.contains("—"));
    }

    // ---- reading the source ------------------------------------------------------------------------

    private static String source(String name) {
        File file = new File("src/main/java/com/orbit/assistant/" + name);
        if (!file.exists()) file = new File("app/src/main/java/com/orbit/assistant/" + name);
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read " + name, e);
        }
    }

    /**
     * Every double-quoted literal in a Java source file.
     *
     * <p>Deliberately simple, and it skips line and block comments, because
     * the prose explaining a decision is allowed to use whatever punctuation reads best. Only what
     * can reach a screen is checked.
     */
    private static List<String> stringLiterals(String source) {
        List<String> out = new ArrayList<>();
        boolean inLine = false;
        boolean inBlock = false;
        boolean inString = false;
        boolean inChar = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (inLine) {
                if (ch == '\n') inLine = false;
                continue;
            }
            if (inBlock) {
                if (ch == '*' && next == '/') { inBlock = false; i++; }
                continue;
            }
            if (inChar) {
                if (ch == '\\') { i++; continue; }
                if (ch == '\'') inChar = false;
                continue;
            }
            if (inString) {
                if (ch == '\\') { i++; continue; }
                if (ch == '"') {
                    inString = false;
                    out.add(current.toString());
                    current.setLength(0);
                    continue;
                }
                current.append(ch);
                continue;
            }
            if (ch == '/' && next == '/') { inLine = true; i++; continue; }
            if (ch == '/' && next == '*') { inBlock = true; i++; continue; }
            if (ch == '\'') { inChar = true; continue; }
            if (ch == '"') { inString = true; continue; }
        }
        return out;
    }
}
