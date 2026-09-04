package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Color;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Links are a derived theme token, and they follow the theme.
 *
 * <p>Two separate faults were reported from the device, and they had different causes. The first
 * was that the Theme Studio preview's sample link did not move when the accent moved: the preview
 * asked {@code UiKit} for the accent Orbit was <em>currently</em> using, which is exactly the value
 * a draft has not been applied with, so the one colour on that screen that should have been proving
 * the theme was the one colour ignoring it. The second was that a real link barely looked like the
 * accent at all: when the accent could not read on its surface, the old rule mixed it toward
 * Orbit's near-white ink, which desaturates a colour and pulls its hue toward grey at once. Nova
 * failed that test at 2.3 to 1 against a card and came back as a pale mauve.
 *
 * <p>So the derivation moved into {@link OrbitContrast#readableAccentOn}, which walks in HSV with
 * the hue pinned, and the token moved into {@link OrbitThemeTokens} so the preview and the renderer
 * read the same field.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class ThemedLinkColorTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        UiKit.syncTheme(context);
    }

    private static float hueOf(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        return hsv[0];
    }

    private static OrbitTheme themeWithAccent(String accent) {
        return OrbitTheme.orbitDefault().withAccent(accent);
    }

    // ---- the preview and the renderer are the same derivation ----------------------------------

    /**
     * The preview's link and a real reply's link come from one function given one accent.
     *
     * <p>{@link ThemePreviewView} draws {@code tokens.link}; the renderer calls
     * {@code UiKit.linkColorOn} with the reply surface. This asserts those agree for a theme that
     * is actually applied, which is the only case where they can be compared directly and the only
     * case where a disagreement would be visible.
     */
    @Test public void thePreviewAndTheRealRendererDeriveTheSameLink() {
        for (String accent : new String[]{"nova", "mint", "rose", "blurple", "pastel_pink",
                OrbitTheme.DYNAMIC, "#123456", "#F2E9A0"}) {
            OrbitTheme theme = themeWithAccent(accent);
            OrbitThemeStore.applyActive(context, theme);
            OrbitThemeTokens tokens = OrbitThemeTokens.resolve(context, theme);
            assertEquals(accent + ": preview and renderer must agree",
                    UiKit.linkColorOn(context, tokens.assistantBubble), tokens.link);
        }
    }

    /**
     * The preview resolves the draft, not what is applied.
     *
     * <p>This is the reported bug stated as an assertion: with Nova applied and Mint in the draft,
     * the preview's link must be Mint's.
     */
    @Test public void aDraftLinkIgnoresTheAppliedAccent() {
        OrbitThemeStore.applyActive(context, themeWithAccent("nova"));
        OrbitThemeTokens draft = OrbitThemeTokens.resolve(context, themeWithAccent("mint"));
        OrbitThemeTokens applied = OrbitThemeTokens.resolve(context, themeWithAccent("nova"));
        assertFalse("the draft's link must not follow the applied accent",
                draft.link == applied.link);
        assertEquals("it must follow the draft's own accent",
                UiKit.linkColorFor(draft.accent, draft.assistantBubble), draft.link);
    }

    /** Every accent produces a visibly different link on the same surface. */
    @Test public void changingTheAccentChangesTheLink() {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (String accent : new String[]{"blurple", "violet", "blue", "mint", "rose", "nova",
                "pastel_pink", "pastel_blue"}) {
            OrbitThemeTokens tokens = OrbitThemeTokens.resolve(context, themeWithAccent(accent));
            assertTrue(accent + " produced a link another accent already produced",
                    seen.add(tokens.link));
        }
    }

    // ---- hue is preserved -----------------------------------------------------------------------

    /**
     * The correction moves a colour along its own hue and nowhere else.
     *
     * <p>Nova is the case that made this necessary: it cannot reach 3 to 1 against any dark ground,
     * so it is always corrected, and if the correction mixed toward grey the link would stop being
     * Nova on every dark theme Orbit has.
     */
    @Test public void aCorrectedLinkKeepsTheAccentsHue() {
        int[] grounds = {Color.BLACK, Color.rgb(22, 25, 33), Color.rgb(10, 12, 17),
                Color.rgb(27, 21, 49), Color.WHITE, Color.rgb(238, 238, 245)};
        for (String accent : new String[]{"nova", "blurple", "violet", "mint", "rose",
                "pastel_pink", "pastel_blue"}) {
            int value = OrbitPalette.colorFor(accent, 0);
            for (int ground : grounds) {
                int link = UiKit.linkColorFor(value, ground);
                assertEquals(accent + " drifted off its hue on #" + Integer.toHexString(ground),
                        hueOf(value), hueOf(link), 2f);
            }
        }
    }

    /** Nova on a card is the exact reported pairing: corrected, readable, and still Nova. */
    @Test public void novaOnACardIsLiftedRatherThanWashedOut() {
        int nova = OrbitPalette.colorFor(OrbitPalette.NOVA, 0);
        int card = Color.rgb(22, 25, 33);
        assertTrue("the premise: raw Nova does not read on a card",
                OrbitContrast.contrastRatio(nova, card) < OrbitContrast.LARGE_TEXT_MIN);

        int link = UiKit.linkColorFor(nova, card);
        assertTrue("the corrected link must read",
                OrbitContrast.contrastRatio(link, card) >= OrbitContrast.LARGE_TEXT_MIN);
        assertEquals("and must still be Nova", hueOf(nova), hueOf(link), 2f);

        float[] hsv = new float[3];
        Color.colorToHSV(link, hsv);
        assertTrue("a link that has lost its saturation has lost the theme", hsv[1] >= 0.35f);
    }

    // ---- readability ----------------------------------------------------------------------------

    /** An accent that already reads is used exactly, never adjusted for the sake of it. */
    @Test public void aReadableAccentIsUsedUnchanged() {
        int card = Color.rgb(22, 25, 33);
        for (String accent : new String[]{"mint", "blue", "rose", "pastel_pink", "pastel_blue"}) {
            int value = OrbitPalette.colorFor(accent, 0);
            if (OrbitContrast.contrastRatio(value, card) < OrbitContrast.LARGE_TEXT_MIN) continue;
            assertEquals(accent + " was altered when it did not need to be",
                    value, UiKit.linkColorFor(value, card));
        }
    }

    /**
     * Every accent on every surface a theme can produce clears the threshold.
     *
     * <p>Including the pathological ones: an accent identical to the bubble it sits on, an almost
     * white accent on white, and an almost black one on black.
     */
    @Test public void everyAccentAndSurfaceCombinationStaysReadable() {
        int[] grounds = {Color.BLACK, Color.WHITE, Color.rgb(22, 25, 33), Color.rgb(10, 12, 17),
                Color.rgb(238, 238, 245), Color.rgb(60, 32, 120), Color.rgb(205, 195, 245),
                Color.rgb(127, 127, 127), Color.rgb(8, 8, 10), Color.rgb(250, 250, 250)};
        int[] accents = {OrbitPalette.colorFor("nova", 0), OrbitPalette.colorFor("mint", 0),
                OrbitPalette.colorFor("pastel_pink", 0), Color.rgb(255, 255, 0),
                Color.rgb(1, 1, 1), Color.rgb(254, 254, 254), Color.rgb(127, 127, 127)};
        for (int accent : accents) {
            for (int ground : grounds) {
                int link = UiKit.linkColorFor(accent, ground);
                double ratio = OrbitContrast.contrastRatio(link, ground);
                assertTrue("#" + Integer.toHexString(accent) + " on #"
                                + Integer.toHexString(ground) + " gave " + ratio,
                        ratio >= OrbitContrast.LARGE_TEXT_MIN);
            }
            // And against itself, which is the accent-coloured-bubble case.
            assertTrue(OrbitContrast.contrastRatio(UiKit.linkColorFor(accent, accent), accent)
                    >= OrbitContrast.LARGE_TEXT_MIN);
        }
    }

    /** An extremely bright accent darkens on a light theme; an extremely dark one brightens. */
    @Test public void theCorrectionGoesTheDirectionTheGroundNeeds() {
        int nearlyWhite = Color.rgb(252, 250, 245);
        int light = UiKit.linkColorFor(nearlyWhite, Color.WHITE);
        assertTrue("a near-white accent on white must darken",
                OrbitContrast.relativeLuminance(light)
                        < OrbitContrast.relativeLuminance(nearlyWhite));

        int nearlyBlack = Color.rgb(6, 4, 12);
        int dark = UiKit.linkColorFor(nearlyBlack, Color.BLACK);
        assertTrue("a near-black accent on black must brighten",
                OrbitContrast.relativeLuminance(dark)
                        > OrbitContrast.relativeLuminance(nearlyBlack));
    }

    /** AMOLED is the surface most likely to be looked at in the dark, and it has to hold up. */
    @Test public void linksAreReadableOnTrueBlack() {
        for (OrbitTheme preset : OrbitTheme.builtIns()) {
            OrbitTheme amoled = preset.withAmoled(true);
            OrbitThemeTokens tokens = OrbitThemeTokens.resolve(context, amoled);
            assertEquals(Color.BLACK, tokens.background);
            assertTrue(preset.name + " AMOLED has an unreadable link",
                    OrbitContrast.contrastRatio(tokens.link, tokens.assistantBubble)
                            >= OrbitContrast.LARGE_TEXT_MIN);
            assertTrue(preset.name + " AMOLED has an unreadable link on the page",
                    OrbitContrast.contrastRatio(UiKit.linkColorFor(tokens.accent, Color.BLACK),
                            Color.BLACK) >= OrbitContrast.LARGE_TEXT_MIN);
        }
    }

    /** Custom themes at both ends of the range, typed by hand rather than chosen from a list. */
    @Test public void customLightAndDarkThemesBothGetReadableLinks() {
        OrbitTheme lightTheme = OrbitTheme.custom("Daylight", "#FFE45C", OrbitTheme.CLASSIC,
                "#FFFFFF", "#FAFAFA", "#FFFFFF", false);
        OrbitTheme darkTheme = OrbitTheme.custom("Pitch", "#101018", OrbitTheme.CLASSIC,
                "#050508", "#080810", "#000000", false);
        for (OrbitTheme theme : new OrbitTheme[]{lightTheme, darkTheme}) {
            OrbitThemeTokens tokens = OrbitThemeTokens.resolve(context, theme);
            assertTrue(theme.name + " has an unreadable link",
                    OrbitContrast.contrastRatio(tokens.link, tokens.assistantBubble)
                            >= OrbitContrast.LARGE_TEXT_MIN);
        }
    }

    // ---- the derivation itself --------------------------------------------------------------------

    @Test public void theSameInputsAlwaysGiveTheSameLink() {
        int accent = OrbitPalette.colorFor("nova", 0);
        int ground = Color.rgb(22, 25, 33);
        int first = UiKit.linkColorFor(accent, ground);
        for (int i = 0; i < 5; i++) assertEquals(first, UiKit.linkColorFor(accent, ground));
    }

    @Test public void aLinkIsAlwaysOpaque() {
        assertEquals(255, Color.alpha(UiKit.linkColorFor(
                OrbitPalette.colorFor("nova", 0), Color.BLACK)));
    }

    /** There is no separate link preference to get out of step with the accent. */
    @Test public void thereIsNoStoredLinkColour() {
        OrbitTheme theme = OrbitTheme.custom("Mine", "nova", OrbitTheme.CLASSIC,
                OrbitTheme.CLASSIC, OrbitTheme.CLASSIC, OrbitTheme.CLASSIC, false);
        try {
            String json = theme.toJson().toString();
            assertFalse("a link colour must never be stored", json.contains("link"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
