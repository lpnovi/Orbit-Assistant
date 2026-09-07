package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Orbit's floating chrome, and the boundary it exists to stop drawing.
 *
 * <p>Chats and the Vault both ended with a band of controls and then a list, and every attempt to
 * soften the join between them treated it as a spacing problem. It was not. The join was a drawn
 * boundary - an accent rule on Chats, a bare edge on the Vault - and content stopped dead at it.
 * v0.7.8.4-beta.6 removes the boundary rather than softening it: the controls become translucent
 * surfaces lying over the page, and a scrim laid over the <em>top of the list</em> takes content
 * out of sight before it is clipped.
 *
 * <p>Four things could quietly undo that, and each has assertions here. The divider could come back
 * in some other form, so the absence of anything between Search and the list is asserted
 * structurally rather than by looking for the old code. The two screens could grow their own
 * versions of the effect, so both are asserted to be drawing the same shared primitive with the
 * same numbers. The glass could get tied to one Orbit colour scheme, so its colours are asserted to
 * move with Theme Studio and to survive AMOLED. And it could quietly become expensive, so the
 * absence of per-frame capture, of a blur dependency and of an API-level branch is asserted
 * directly.
 *
 * <p>Nothing here asserts pixels. Whether the result is beautiful is a question for a real S25
 * Ultra; what a machine can settle is that the boundary is gone, that the treatment is shared, that
 * it reads in every theme, and that the Beta 5 behaviour underneath it is untouched.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class OrbitGlassChromeTest {

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
        UiKit.syncTheme(context);
        Settings.Global.putFloat(context.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
    }

    @After public void tearDown() {
        if (chats != null) chats.pause().stop().destroy();
        if (vault != null) vault.pause().stop().destroy();
        Settings.Global.putFloat(context.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
    }

    // ---- the two screens ---------------------------------------------------------------------------

    private void chat(String title, String body) {
        String id = ConversationStore.newId();
        List<AssistantClient.History> history = new ArrayList<>();
        history.add(new AssistantClient.History("user", body));
        ConversationStore.save(context, id, history);
        ConversationStore.rename(context, id, title);
    }

    private MainActivity chatsScreen() {
        chat("Packing list", "Charger and passport");
        chat("Sourdough", "Starter and flour");
        chats = Robolectric.buildActivity(MainActivity.class).setup();
        return chats.get();
    }

    /** The page, measured and laid out at a real phone size, so scrolling has somewhere to go. */
    private static void layout(Activity activity) {
        View root = activity.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 2400);
    }

    private OrbitVaultActivity vaultScreen() {
        OrbitVaultStore.saveText(context, "Packing list", "Charger and passport",
                OrbitVaultSource.QUICK_CAPTURE);
        OrbitVaultStore.saveLink(context, "Peak sourdough", "https://example.com/peak",
                OrbitVaultSource.SHARED);
        vault = Robolectric.buildActivity(OrbitVaultActivity.class).setup();
        return vault.get();
    }

    private static String source(String name) {
        return readRepositoryFile("app/src/main/java/com/orbit/assistant/" + name + ".java");
    }

    static String readRepositoryFile(String relative) {
        Path start = Paths.get("").toAbsolutePath();
        for (Path directory = start; directory != null; directory = directory.getParent()) {
            Path candidate = directory.resolve(relative);
            if (!Files.isRegularFile(candidate)) continue;
            try {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new AssertionError("could not read " + relative, e);
            }
        }
        throw new AssertionError(relative + " was not found above " + start);
    }

    /** One Java file with its comments taken out, so a source scan reads code and not prose. */
    private static String code(String java) {
        return java.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    private static List<String> textOf(View view) {
        List<String> found = new ArrayList<>();
        collect(view, found);
        return found;
    }

    private static void collect(View view, List<String> into) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null) into.add(text.toString());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), into);
        }
    }

    private static <T extends View> T firstOfType(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = firstOfType(group.getChildAt(i), type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static List<OrbitSwipeRow> swipeRows(View view) {
        List<OrbitSwipeRow> found = new ArrayList<>();
        collectRows(view, found);
        return found;
    }

    private static void collectRows(View view, List<OrbitSwipeRow> into) {
        if (view instanceof OrbitSwipeRow) into.add((OrbitSwipeRow) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectRows(group.getChildAt(i), into);
        }
    }

    // ---- one shared primitive ----------------------------------------------------------------------

    /**
     * Both screens draw the same chrome, from the same place, with the same numbers.
     *
     * <p>This is the assertion the whole release rests on. Two hand-built effects would look
     * identical on the day they shipped and would drift the first time either screen was touched.
     */
    @Test public void chatsAndTheVaultUseTheSameScrimAtTheSameDepth() {
        View chatsScrim = chatsScreen().chromeForTest().scrim();
        View vaultScrim = vaultScreen().chromeForTest().scrim();
        assertNotNull(chatsScrim);
        assertNotNull(vaultScrim);

        int depth = UiKit.dp(context, OrbitGlass.SCRIM_DEPTH_DP);
        assertEquals("Chats' scrim is exactly the shared depth",
                depth, chatsScrim.getLayoutParams().height);
        assertEquals("and so is the Vault's",
                depth, vaultScrim.getLayoutParams().height);
        assertTrue("both are the shared gradient rather than a local drawable",
                chatsScrim.getBackground() instanceof GradientDrawable);
        assertTrue(vaultScrim.getBackground() instanceof GradientDrawable);
    }

    /**
     * The scrim is laid <em>over</em> the list, not fitted above it.
     *
     * <p>A sibling above the list would be another band taking height off the viewport, which is
     * exactly the shape of the problem being fixed. Over it, content passes underneath and fades.
     */
    @Test public void theScrimIsLaidOverTheListRatherThanStackedAboveIt() {
        for (View scrim : new View[]{
                chatsScreen().chromeForTest().scrim(),
                vaultScreen().chromeForTest().scrim()}) {
            ViewGroup host = (ViewGroup) scrim.getParent();
            assertTrue("the scrim must share a frame with the list", host instanceof FrameLayout);
            ScrollView list = firstOfType(host, ScrollView.class);
            assertNotNull("the list is in that frame", list);
            assertTrue("and the scrim is drawn after it, so it lies on top",
                    host.indexOfChild(scrim) > host.indexOfChild(list));
            assertEquals("the frame is the whole remaining page",
                    ViewGroup.LayoutParams.MATCH_PARENT, list.getLayoutParams().height);
        }
    }

    /** The scrim is decoration, so it is out of the accessibility tree and takes no touches. */
    @Test public void theScrimIsInertToTouchAndToTalkBack() {
        for (View scrim : new View[]{
                chatsScreen().chromeForTest().scrim(),
                vaultScreen().chromeForTest().scrim()}) {
            assertFalse("a haze must never swallow a scroll", scrim.isClickable());
            assertFalse(scrim.isFocusable());
            assertEquals("and must never be announced",
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO, scrim.getImportantForAccessibility());
        }
    }

    /** Both screens float their search on the same glass, at the same corner and the same depth. */
    @Test public void bothSearchSurfacesAreTheSameFloatingGlass() {
        View chatsSearch = chatsScreen().searchSurfaceForTest();
        View vaultSearch = vaultScreen().searchSurfaceForTest();
        assertNotNull(chatsSearch);
        assertNotNull(vaultSearch);
        assertTrue(chatsSearch.getBackground() instanceof GradientDrawable);
        assertTrue(vaultSearch.getBackground() instanceof GradientDrawable);
        float resting = UiKit.dp(context, OrbitGlass.RESTING_ELEVATION_DP);
        assertEquals("Search sits above the page", resting, chatsSearch.getElevation(), 0.01f);
        assertEquals(resting, vaultSearch.getElevation(), 0.01f);
    }

    /** And both cap their floating controls at the same width, so a tablet gets one cluster. */
    @Test public void everyFloatingControlSharesOneMaximumWidth() {
        assertEquals(OrbitGlass.MAX_CONTROL_WIDTH_DP, OrbitVaultActivity.FILTERS_MAX_WIDTH_DP);
        assertFalse("the Vault must not keep its own cap arithmetic",
                source("OrbitVaultActivity").contains("filterBarWidth"));
        for (View search : new View[]{
                chatsScreen().searchSurfaceForTest(),
                vaultScreen().searchSurfaceForTest()}) {
            assertEquals("on a phone a floating control is simply the page width",
                    OrbitGlass.controlWidth(context), search.getLayoutParams().width);
        }
    }

    // ---- no divider, and nothing that could become one ----------------------------------------------

    /**
     * Nothing is drawn between Search and the list on either screen.
     *
     * <p>Asserted on the page's own structure rather than by grepping for the old divider: any
     * replacement rule, hairline or spacer band would have to be a view here, and there is not one.
     */
    @Test public void thereIsNothingBetweenSearchAndTheFeed() {
        MainActivity screen = chatsScreen();
        View search = screen.searchSurfaceForTest();
        ViewGroup page = (ViewGroup) search.getParent();
        View chromeHost = screen.chromeForTest().host();
        assertSame("the list frame is the page's own child", page, chromeHost.getParent());
        assertEquals("Search is followed immediately by the list",
                page.indexOfChild(search) + 1, page.indexOfChild(chromeHost));

        OrbitVaultActivity saved = vaultScreen();
        ViewGroup vaultPage = (ViewGroup) saved.searchSurfaceForTest().getParent();
        View vaultHost = saved.chromeForTest().host();
        assertEquals("and in the Vault the selector row is the last thing before it",
                vaultPage.getChildCount() - 1, vaultPage.indexOfChild(vaultHost));
    }

    /** The accent rule Chats used to draw is gone from the source, not merely hidden. */
    @Test public void theChatsDividerIsGone() {
        String page = source("MainActivity");
        assertFalse("no divider view", page.contains("dividerCore"));
        assertFalse(page.contains("dividerGlow"));
        assertFalse(page.contains("dividerAccent"));
    }

    /**
     * The transition is spacing plus scrim, and it is neither a dead zone nor a cutoff.
     *
     * <p>The first heading comes to rest exactly where the scrim finishes: any less and it would be
     * sitting inside the haze, any more and there would be an empty band under the controls.
     */
    @Test public void theFirstHeadingRestsExactlyWhereTheScrimEnds() {
        MainActivity screen = chatsScreen();
        View content = screen.listContentForTest();
        TextView heading = null;
        for (int i = 0; i < ((ViewGroup) content).getChildCount(); i++) {
            View child = ((ViewGroup) content).getChildAt(i);
            TextView candidate = firstOfType(child, TextView.class);
            if (candidate != null && "RECENT CHATS".contentEquals(candidate.getText())) {
                heading = candidate;
                break;
            }
        }
        assertNotNull("Chats still heads its feed", heading);
        int toHeadingText = content.getPaddingTop() + heading.getPaddingTop();
        assertEquals("the feed's first words begin where the scrim has finished",
                UiKit.dp(context, OrbitGlass.SCRIM_DEPTH_DP), toHeadingText);
    }

    // ---- theme, AMOLED and Theme Studio ------------------------------------------------------------

    private void applyTheme(String presetId) {
        OrbitTheme theme = OrbitTheme.builtIn(presetId);
        assertNotNull(presetId + " must be a preset Orbit ships", theme);
        OrbitThemeStore.applyActive(context, theme);
        UiKit.syncTheme(context);
    }

    private static float hueOf(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        return hsv[0];
    }

    /** The glass takes its colour from the theme in force, not from one Orbit colour scheme. */
    @Test public void theGlassIsDerivedFromTheActiveThemeRatherThanFromNova() {
        applyTheme(OrbitTheme.ID_NOVA_AMOLED);
        int novaBorder = OrbitGlass.borderColor(context);
        int novaHaze = OrbitGlass.hazeColor(context);
        int novaFill = OrbitGlass.effectiveFill(context);

        applyTheme(OrbitTheme.ID_EMBER);
        int emberBorder = OrbitGlass.borderColor(context);
        int emberHaze = OrbitGlass.hazeColor(context);
        int emberFill = OrbitGlass.effectiveFill(context);

        assertTrue("a warm theme must not be given a violet hairline",
                Math.abs(hueOf(novaBorder) - hueOf(emberBorder)) > 40f);
        assertTrue("nor a violet haze", Math.abs(hueOf(novaHaze) - hueOf(emberHaze)) > 40f);
        assertTrue("nor violet glass", novaFill != emberFill);

        assertFalse("no Orbit colour may be written into the glass itself",
                code(source("OrbitGlass")).contains("Color.rgb("));
        assertFalse(code(source("OrbitGlass")).contains("Color.parseColor"));
        assertFalse("and no hex literal either", code(source("OrbitGlass")).contains("0xFF"));
    }

    /** True black stays true black at the boundary, which is what makes the seam invisible. */
    @Test public void theScrimBeginsAsThePageItSitsOn() {
        for (String preset : new String[]{
                OrbitTheme.ID_DEFAULT, OrbitTheme.ID_NOVA_AMOLED, OrbitTheme.ID_EMBER}) {
            applyTheme(preset);
            GradientDrawable scrim = OrbitGlass.scrimDrawable(context);
            int[] colors = scrim.getColors();
            assertNotNull(colors);
            assertTrue("the scrim needs room to dissolve", colors.length >= 3);
            assertEquals(preset + ": the scrim starts as the page's own background",
                    UiKit.BG | 0xFF000000, colors[0] | 0xFF000000);
            assertEquals(preset + ": opaque where the list is clipped, so nothing is sliced",
                    255, Color.alpha(colors[0]));
            assertEquals(preset + ": and completely gone by the end, so there is no bottom edge",
                    0, Color.alpha(colors[colors.length - 1]));
            for (int i = 1; i < colors.length; i++) {
                assertTrue(preset + ": the scrim only ever fades",
                        Color.alpha(colors[i]) < Color.alpha(colors[i - 1]));
            }
        }
    }

    /** AMOLED keeps its true-black page, and the glass still has an edge to be seen by. */
    @Test public void amoledKeepsTrueBlackAndTheGlassStillReads() {
        applyTheme(OrbitTheme.ID_NOVA_AMOLED);
        assertEquals("AMOLED means a true black page", Color.BLACK, UiKit.BG | 0xFF000000);
        assertEquals("and the scrim honours it", Color.BLACK,
                OrbitGlass.scrimDrawable(context).getColors()[0] | 0xFF000000);
        assertTrue("the hairline is what carries the edge where a shadow cannot",
                Color.alpha(OrbitGlass.borderColor(context)) > 0);
        assertTrue("and the glass is not simply the page",
                OrbitGlass.effectiveFill(context) != UiKit.BG);
    }

    /**
     * Nothing on the glass is harder to read than it was on the surface it replaced.
     *
     * <p>Translucency is exactly the way a design like this quietly loses contrast, so the check is
     * on the colour that is actually composited on screen rather than on the fill as authored.
     */
    @Test public void textOnTheGlassStillReads() {
        for (OrbitTheme preset : OrbitTheme.builtIns()) {
            applyTheme(preset.id);
            int glass = OrbitGlass.effectiveFill(context);
            double onGlass = OrbitContrast.contrastRatio(UiKit.TEXT, glass);
            assertTrue(preset.name + ": text on a floating control must meet AA (" + onGlass + ")",
                    onGlass >= OrbitContrast.BODY_TEXT_MIN);

            // Whatever ink reads on an Orbit card has to be the ink that reads on the glass. A
            // translucent surface that drifted far enough to want the other one would need its own
            // text colour, and Orbit does not have one to give it.
            assertEquals(preset.name + ": the glass takes the same ink as a card",
                    OrbitContrast.prefersDarkInk(UiKit.SURFACE),
                    OrbitContrast.prefersDarkInk(glass));

            double hint = OrbitContrast.contrastRatio(UiKit.MUTED, glass);
            assertTrue(preset.name + ": the search hint must still read (" + hint + ")",
                    hint >= OrbitContrast.LARGE_TEXT_MIN);
        }
    }

    /** A theme change rebuilds the chrome rather than leaving the old colours on screen. */
    @Test public void aThemeChangeIsCarriedIntoTheChrome() {
        applyTheme(OrbitTheme.ID_DEFAULT);
        MainActivity screen = chatsScreen();
        View firstScrim = screen.chromeForTest().scrim();
        assertNotNull(firstScrim);

        applyTheme(OrbitTheme.ID_EMBER);
        chats.pause().resume();
        View rebuilt = screen.chromeForTest().scrim();
        assertNotNull("Chats rebuilds itself for a new theme", rebuilt);
        assertEquals("and the new scrim is drawn from the new page colour",
                UiKit.BG | 0xFF000000,
                ((GradientDrawable) rebuilt.getBackground()).getColors()[0] | 0xFF000000);
    }

    // ---- depth, motion and cost --------------------------------------------------------------------

    /** The chrome settles into two states rather than tracking the scroll position. */
    @Test public void theChromeStrengthensOnceWhenContentGoesUnderneathIt() {
        MainActivity screen = chatsScreen();
        OrbitGlass.Chrome chrome = screen.chromeForTest();
        View scrim = chrome.scrim();
        assertFalse("nothing is underneath it at the top", chrome.raised());
        float resting = scrim.getAlpha();

        chrome.setRaised(true);
        assertTrue(chrome.raised());
        assertTrue("the scrim is stronger with content behind it", scrim.getAlpha() > resting);
        assertEquals("and reaches full strength immediately, before anything can peek",
                1f, scrim.getAlpha(), 0.001f);

        chrome.setRaised(true);
        assertEquals("asking twice changes nothing", 1f, scrim.getAlpha(), 0.001f);
    }

    /**
     * Scrolling the list is what raises it, through one deterministic threshold.
     *
     * <p>Driven through the real {@code ScrollView}, so what is asserted is that the chrome is
     * genuinely wired to the list rather than that a method exists to raise it by hand.
     */
    @Test public void theListItselfRaisesTheChrome() {
        for (int i = 0; i < 30; i++) chat("Chat " + i, "Something worth scrolling past");
        chats = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity screen = chats.get();
        layout(screen);

        ScrollView list = screen.listViewportForTest();
        OrbitGlass.Chrome chrome = screen.chromeForTest();
        assertFalse("nothing is under the chrome at the top", chrome.raised());

        list.scrollTo(0, 600);
        assertTrue("the list must actually have moved", list.getScrollY() > 0);
        assertTrue("content under the chrome raises it", chrome.raised());

        list.scrollTo(0, 0);
        assertEquals(0, list.getScrollY());
        assertFalse("and returning to the top settles it back", chrome.raised());
    }

    /** With animations off the chrome still changes state; it simply does not animate to it. */
    @Test public void turningAnimationsOffKeepsTheChromeCorrect() {
        Settings.Global.putFloat(context.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 0f);
        assertFalse(UiKit.animationsEnabled());

        MainActivity screen = chatsScreen();
        OrbitGlass.Chrome chrome = screen.chromeForTest();
        View scrim = chrome.scrim();
        chrome.setRaised(true);
        assertEquals(1f, scrim.getAlpha(), 0.001f);
        chrome.setRaised(false);
        assertFalse(chrome.raised());
        assertTrue("the resting state is reached at once rather than faded into",
                scrim.getAlpha() < 1f);
    }

    /**
     * The effect is two gradient drawables and nothing else.
     *
     * <p>Every expensive way to fake glass is asserted absent by name, because each of them would
     * pass a visual review and only show up as dropped frames on a real phone.
     */
    @Test public void nothingIsCapturedBlurredOrAllocatedWhileScrolling() {
        // Comments stripped: the file explains at length why each of these is not used, and a
        // scan that read the explanation as a use would forbid Orbit from writing the reasoning
        // down.
        String glass = code(source("OrbitGlass"));
        for (String expensive : new String[]{
                "Bitmap", "RenderScript", "ScriptIntrinsicBlur", "RenderEffect",
                "setRenderEffect", "drawingCache", "PixelCopy", "Canvas", "saveLayer",
                "LAYER_TYPE_SOFTWARE", "setBackgroundBlurRadius"}) {
            assertFalse("floating chrome must not reach for " + expensive,
                    glass.contains(expensive));
        }
        assertFalse("and must not fade a whole list every frame either",
                glass.contains("FadingEdge"));
    }

    /** No third-party blur or glass dependency was added to reach this. */
    @Test public void noBlurLibraryWasAdded() {
        String gradle = readRepositoryFile("app/build.gradle").toLowerCase(java.util.Locale.US);
        for (String library : new String[]{"blur", "glass", "haze", "frosted", "renderscript"}) {
            assertFalse("Orbit must not take a dependency for this: " + library,
                    gradle.contains(library));
        }
    }

    /**
     * One treatment on every supported Android version.
     *
     * <p>This test runs on API 29 and API 35, and asserts the same drawables on both. There is no
     * fallback path because there is nothing to fall back from: Android has no backdrop blur a
     * View can use at any of Orbit's supported levels, so the glass is drawn the same way
     * everywhere and there is no second path to rot.
     */
    @Test public void everySupportedAndroidVersionGetsTheSameGlass() {
        assertTrue(OrbitGlass.surfaceDrawable(context) instanceof GradientDrawable);
        assertTrue(OrbitGlass.interactive(context) instanceof RippleDrawable);
        assertTrue(OrbitGlass.scrimDrawable(context) instanceof GradientDrawable);
        assertFalse("no API-level branch to keep working",
                code(source("OrbitGlass")).contains("SDK_INT"));
        assertFalse(code(source("OrbitGlass")).contains("VERSION_CODES"));
    }

    // ---- Chats still works -------------------------------------------------------------------------

    @Test public void chatSearchStillFiltersTheList() {
        MainActivity screen = chatsScreen();
        EditText search = firstOfType(screen.getWindow().getDecorView(), EditText.class);
        assertNotNull(search);
        assertTrue(textOf(screen.getWindow().getDecorView()).contains("Sourdough"));

        search.setText("packing");
        List<String> drawn = textOf(screen.getWindow().getDecorView());
        assertTrue("a matching chat stays", drawn.contains("Packing list"));
        assertFalse("and one that does not match goes", drawn.contains("Sourdough"));
    }

    @Test public void chatsKeepsItsHeadingAndItsSwipeGestures() {
        MainActivity screen = chatsScreen();
        assertTrue(textOf(screen.getWindow().getDecorView()).contains("RECENT CHATS"));
        List<OrbitSwipeRow> rows = swipeRows(screen.getWindow().getDecorView());
        assertEquals("both chats are still swipeable rows", 2, rows.size());
        for (OrbitSwipeRow row : rows) assertTrue(row.swipeEnabled());
    }

    // ---- the Vault still works ---------------------------------------------------------------------

    @Test public void theVaultKeepsBothSelectorsAndTheirFiltering() {
        OrbitVaultActivity screen = vaultScreen();
        View decor = screen.getWindow().getDecorView();
        List<String> drawn = textOf(decor);
        assertTrue("Type rests at All items", drawn.contains(OrbitVaultFilter.Type.ALL.label));
        assertTrue("and Saved from at Any source", drawn.contains(OrbitVaultActivity.SOURCE_ANY));
        assertTrue("the feed says where it begins",
                drawn.contains(OrbitVaultActivity.SAVED_HEADING));
        assertEquals("both saved items are swipeable rows", 2, swipeRows(decor).size());

        Prefs.setVaultFilter(context, OrbitVaultFilter.NONE
                .withType(OrbitVaultFilter.Type.LINKS));
        vault.pause().resume();
        List<String> filtered = textOf(screen.getWindow().getDecorView());
        assertTrue("filtering still keeps what matches", filtered.contains("Peak sourdough"));
        assertFalse("and still removes what does not", filtered.contains("Packing list"));
    }

    /** A resting selector is glass; a chosen one keeps the accent fill that says it is filtering. */
    @Test public void theSelectorsAreGlassAtRestAndAccentWhenTheyAreNarrowing() {
        OrbitVaultActivity screen = vaultScreen();
        View type = findByDescriptionPrefix(screen.getWindow().getDecorView(),
                OrbitVaultActivity.TYPE_QUESTION + ": ");
        assertNotNull(type);
        assertTrue("a resting selector is Orbit glass",
                type.getBackground() instanceof RippleDrawable);
        Drawable resting = ((RippleDrawable) type.getBackground()).getDrawable(0);
        assertTrue(resting instanceof GradientDrawable);
        assertNotNull("its translucency is a gradient, not a flat fill",
                ((GradientDrawable) resting).getColors());

        Prefs.setVaultFilter(context, OrbitVaultFilter.NONE
                .withType(OrbitVaultFilter.Type.LINKS));
        vault.pause().resume();
        View chosen = findByDescriptionPrefix(screen.getWindow().getDecorView(),
                OrbitVaultActivity.TYPE_QUESTION + ": ");
        assertNotNull(chosen);
        assertTrue("and the chosen state is still stated in words, never by depth alone",
                String.valueOf(chosen.getContentDescription()).contains("Currently filtering"));
    }

    /**
     * The Saved-from menu offers the doors the current type actually came through.
     *
     * <p>A source row that can only empty the screen is not a choice. What is asserted here is that
     * only the offer narrows: an item's stored source is untouched, and the filter still matches on
     * it exactly as it did in Beta 5.
     */
    @Test public void savedFromOnlyOffersSourcesTheCurrentTypeCameThrough() {
        OrbitVaultStore.saveText(context, "Packing list", "Charger",
                OrbitVaultSource.QUICK_CAPTURE);
        OrbitVaultStore.saveLink(context, "Peak sourdough", "https://example.com/peak",
                OrbitVaultSource.SHARED);

        List<String> all = OrbitVaultStore.sourcesPresent(context);
        assertTrue(all.contains(OrbitVaultSource.QUICK_CAPTURE));
        assertTrue(all.contains(OrbitVaultSource.SHARED));

        List<String> links = OrbitVaultStore.sourcesPresent(context, OrbitVaultFilter.Type.LINKS);
        assertEquals("only the door the saved link came through", 1, links.size());
        assertEquals(OrbitVaultSource.SHARED, links.get(0));

        List<String> text = OrbitVaultStore.sourcesPresent(context, OrbitVaultFilter.Type.TEXT);
        assertEquals(1, text.size());
        assertEquals(OrbitVaultSource.QUICK_CAPTURE, text.get(0));

        assertTrue("nothing about matching changed",
                OrbitVaultFilter.NONE.withSource(OrbitVaultSource.SHARED)
                        .matches(OrbitVaultStore.browse(context,
                                OrbitVaultFilter.NONE.withType(OrbitVaultFilter.Type.LINKS),
                                OrbitVaultStore.Sort.NEWEST).get(0)));
        assertTrue("a source with nothing of this type left offers nothing",
                OrbitVaultStore.sourcesPresent(context, OrbitVaultFilter.Type.IMAGES).isEmpty());
    }

    /** With the Vault switched off there is no chrome, so there is no haze over the notice. */
    @Test public void aTurnedOffVaultShowsNoFloatingChrome() {
        Prefs.get(context).edit().putBoolean(Prefs.VAULT_ENABLED, false).commit();
        OrbitVaultActivity screen = vaultScreen();
        assertEquals("no scrim over a page with no controls above it",
                View.GONE, screen.chromeForTest().scrim().getVisibility());
        assertEquals(View.GONE, screen.searchSurfaceForTest().getVisibility());
    }

    private static View findByDescriptionPrefix(View view, String prefix) {
        CharSequence description = view.getContentDescription();
        if (description != null && description.toString().startsWith(prefix)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findByDescriptionPrefix(group.getChildAt(i), prefix);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Large text does not break the cluster: nothing about it is a fixed pixel measurement. */
    @Test public void theGlassDoesNotDependOnTheTextSize() {
        assertFalse("no floating control may be sized in raw pixels",
                code(source("OrbitGlass")).contains("getWidth()"));
        assertNull("and none of it measures a view to decide what to draw",
                firstOfType(OrbitGlass.scrim(context), ScrollView.class));

        MainActivity screen = chatsScreen();
        LinearLayout searchBox = (LinearLayout) screen.searchSurfaceForTest();
        EditText search = firstOfType(searchBox, EditText.class);
        assertNotNull(search);
        assertTrue("the search field keeps a comfortable touch target",
                searchBox.getLayoutParams().height >= UiKit.dp(context, 48));
    }
}
