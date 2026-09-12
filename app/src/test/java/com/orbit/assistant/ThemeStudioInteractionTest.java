package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
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

import java.util.ArrayList;
import java.util.List;

/**
 * What physical testing of v0.8.0.0-beta.2 found, turned into assertions.
 *
 * <p>Beta 2's premium controls worked and were unusable, which is a combination unit tests are bad
 * at catching and a person notices in about four seconds. Every fault came from one decision: the
 * screen rebuilt its controls whenever the draft changed, and a slider changes the draft
 * continuously. Destroying and recreating five sliders sixty times a second made unrelated thumbs
 * move, made the thumb being dragged snap on release, reloaded the theme library from disk under
 * the user's finger, and fed the typography watcher a fresh set of labels every frame.
 *
 * <p>So the tests here are mostly about <em>identity and persistence of view objects</em> rather
 * than about values. The interesting assertion is usually "this is the same object it was before",
 * because that is the property that was actually lost. Where a value is at stake, the drag is
 * driven with real {@link MotionEvent}s rather than by calling a listener, since the reported
 * problems lived in the gesture contract rather than in the arithmetic.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class ThemeStudioInteractionTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        OrbitThemeStore.clearForTests(context);
        UiPresence.clearForTests();
        OrbitThemeStore.applyActive(context, OrbitTheme.orbitDefault());
    }

    private ActivityController<ThemeStudioActivity> openPro() {
        Prefs.setProPreview(context, Prefs.PRO_PREVIEW_PRO);
        assertTrue(OrbitProEntitlement.hasPro(context));
        return Robolectric.buildActivity(ThemeStudioActivity.class).setup();
    }

    private ActivityController<ThemeStudioActivity> openFree() {
        Prefs.setProPreview(context, Prefs.PRO_PREVIEW_FREE);
        assertFalse(OrbitProEntitlement.hasPro(context));
        return Robolectric.buildActivity(ThemeStudioActivity.class).setup();
    }

    // ---- 1 and 2. where Orbit Pro sits ------------------------------------------------------------

    /**
     * 1. Orbit Pro comes after everything Theme Studio already had.
     *
     * <p>Beta 2 put it between Colors and Presets. On a phone that meant a free user scrolling to
     * the gallery had to scroll past a large locked panel describing things they could not use,
     * which is the single change that made the screen feel taken over. Asserted by the order the
     * card titles actually appear in the built hierarchy, so a future edit that moves a card
     * fails here rather than on a device.
     */
    @Test public void phoneOrderIsPreviewThenColorsThenPresetsThenPro() {
        for (boolean pro : new boolean[]{false, true}) {
            ActivityController<ThemeStudioActivity> controller = pro ? openPro() : openFree();
            List<String> order = sectionOrder(controller.get());
            assertEquals("with Pro " + pro + ", sections must read " + order,
                    java.util.Arrays.asList("Colors", "Presets", "Orbit Pro"), order);
            assertTrue("the preview comes before all of them",
                    previewIndex(controller.get()) >= 0);
            controller.pause().stop().destroy();
        }
    }

    /** 2. And the same order in the tablet's right-hand pane. */
    @Test public void tabletRightPaneOrderIsColorsThenPresetsThenPro() {
        RuntimeEnvironment.setQualifiers("w1024dp-h768dp");
        for (boolean pro : new boolean[]{false, true}) {
            ActivityController<ThemeStudioActivity> controller = pro ? openPro() : openFree();
            assertEquals("two-pane order must match the phone",
                    java.util.Arrays.asList("Colors", "Presets", "Orbit Pro"),
                    sectionOrder(controller.get()));
            controller.pause().stop().destroy();
        }
        RuntimeEnvironment.setQualifiers("w411dp-h891dp");
    }

    // ---- 3 to 6. the Free state -------------------------------------------------------------------

    /**
     * 3 and 4. The locked treatment is a teaser, not a panel.
     *
     * <p>Measured rather than described. Beta 2's locked state was a bordered box inside the card
     * carrying a heading, a two-line feature inventory and an explanation; the replacement is one
     * line saying what Pro adds and one saying why it is off. The assertion is on the amount of
     * text, because that is what the complaint was about.
     */
    @Test public void freeLockedProTreatmentIsCompact() {
        ActivityController<ThemeStudioActivity> controller = openFree();
        ThemeStudioActivity activity = controller.get();

        View proBody = (View) field(activity, "proBody");
        assertNotNull(proBody);
        List<String> lines = textsIn(proBody);
        assertTrue("the locked state must be a short teaser, found " + lines,
                lines.size() <= 3);

        String joined = String.join(" ", lines);
        assertFalse("the old nested panel heading must be gone",
                joined.contains("Included with Orbit Pro"));
        assertFalse("and its feature inventory with it",
                joined.contains("Messages: bubble roundness"));
        assertTrue("it still says what Pro adds, including the two Beta 4 tools",
                joined.contains("Advanced message styling, Liquid Glass and advanced backgrounds"));

        // No purchase, at any size.
        for (String selling : new String[]{"Upgrade", "Buy", "Subscribe", "$", "price", "checkout"}) {
            assertFalse("a locked state must never read like a store: " + selling,
                    joined.toLowerCase(java.util.Locale.US)
                            .contains(selling.toLowerCase(java.util.Locale.US)));
        }

        // And no disabled slider graveyard.
        assertTrue("Free must not build premium controls at all",
                findAll(proBody, OrbitSlider.class).isEmpty());
        controller.pause().stop().destroy();
    }

    /** 5 and 6. Free keeps the whole of the editor it already had. */
    @Test public void freeColorsAndPresetsRemainFullyUsable() {
        ActivityController<ThemeStudioActivity> controller = openFree();
        ThemeStudioActivity activity = controller.get();

        List<String> shown = allTexts(activity);
        for (String row : new String[]{"Accent", "Your messages", "Orbit's replies", "Cards",
                "Background", "True black AMOLED background"}) {
            assertTrue(row + " must still be offered on Free", shown.contains(row));
        }
        for (OrbitTheme preset : OrbitTheme.freeBuiltIns()) {
            assertTrue(preset.name + " must still be in the gallery", shown.contains(preset.name));
        }

        // And a free preset still loads into the draft on a Free device.
        invoke(activity, "selectPreset", new Class<?>[]{OrbitTheme.class},
                OrbitTheme.builtIn(OrbitTheme.ID_NEBULA));
        assertEquals(OrbitTheme.ID_NEBULA, draft(activity).id);
        controller.pause().stop().destroy();
    }

    // ---- 7 to 10. the two local previews ------------------------------------------------------------

    /** 7, 8, 9 and 10. Each tool leads with a sample, and neither sample has its own styling math. */
    @Test public void proStateHasLocalMessageAndGlassPreviews() {
        ActivityController<ThemeStudioActivity> controller = openPro();
        ThemeStudioActivity activity = controller.get();

        MessageStylePreview message = (MessageStylePreview) field(activity, "messagePreview");
        GlassStylePreview glass = (GlassStylePreview) field(activity, "glassPreview");
        assertNotNull("Message style must have its own live sample", message);
        assertNotNull("Floating glass must have its own live sample", glass);

        // Each sits above the controls it explains rather than at the top of the page.
        View proBody = (View) field(activity, "proBody");
        assertTrue(indexOfDescendant(proBody, message) < indexOfFirst(proBody, OrbitSlider.class));

        // 9 and 10: the shared implementations, not local imitations of them.
        String messageSource = ThemeStudioProTest.readSourceFile("MessageStylePreview.java");
        assertTrue("the message sample must draw bubbles the way conversations do",
                messageSource.contains("UiKit.bubbleSurface("));
        assertFalse("and must not carry its own corner radius",
                messageSource.contains("setCornerRadius") || messageSource.contains("setStroke"));

        String glassSource = ThemeStudioProTest.readSourceFile("GlassStylePreview.java");
        assertTrue("the glass sample must be drawn by the shared floating-surface resolver",
                glassSource.contains("OrbitFloatingSurface.surfaceDrawable("));
        assertFalse("and must not hand-roll a translucent rectangle",
                glassSource.contains("GradientDrawable"));
        controller.pause().stop().destroy();
    }

    // ---- 11 to 13. a drag does not rebuild controls ----------------------------------------------------

    /**
     * 11 and 12. The five sliders are the same five objects before, during and after a drag.
     *
     * <p>This is the assertion the whole release turns on. Beta 2 replaced all five on every
     * settled change, so the four the user was not touching were reconstructed at whatever value
     * the draft then held, and the one they were touching was replaced underneath the finger.
     */
    @Test public void slidersAreNotRecreatedByADragOrItsSettle() {
        ActivityController<ThemeStudioActivity> controller = openPro();
        ThemeStudioActivity activity = controller.get();

        OrbitSlider radius = slider(activity, "bubbleRadiusSlider");
        OrbitSlider outline = slider(activity, "bubbleOutlineSlider");
        OrbitSlider opacity = slider(activity, "glassOpacitySlider");
        OrbitSlider tint = slider(activity, "glassTintSlider");
        OrbitSlider edge = slider(activity, "glassEdgeSlider");
        MessageStylePreview message = (MessageStylePreview) field(activity, "messagePreview");
        GlassStylePreview glass = (GlassStylePreview) field(activity, "glassPreview");
        ThemePreviewView preview = (ThemePreviewView) field(activity, "preview");

        drag(radius, 0.1f, 0.9f);

        assertSame("the dragged slider must survive its own gesture",
                radius, slider(activity, "bubbleRadiusSlider"));
        assertSame(outline, slider(activity, "bubbleOutlineSlider"));
        assertSame(opacity, slider(activity, "glassOpacitySlider"));
        assertSame(tint, slider(activity, "glassTintSlider"));
        assertSame(edge, slider(activity, "glassEdgeSlider"));
        assertSame("the samples must not be replaced either",
                message, field(activity, "messagePreview"));
        assertSame(glass, field(activity, "glassPreview"));
        assertSame(preview, field(activity, "preview"));
        controller.pause().stop().destroy();
    }

    /** 13. And moving one slider does not move any other. */
    @Test public void adjustingOneSliderLeavesTheOthersWhereTheyWere() {
        ActivityController<ThemeStudioActivity> controller = openPro();
        ThemeStudioActivity activity = controller.get();

        int outlineBefore = slider(activity, "bubbleOutlineSlider").getValue();
        int opacityBefore = slider(activity, "glassOpacitySlider").getValue();
        int tintBefore = slider(activity, "glassTintSlider").getValue();
        int edgeBefore = slider(activity, "glassEdgeSlider").getValue();

        drag(slider(activity, "bubbleRadiusSlider"), 0.05f, 0.95f);

        assertEquals(outlineBefore, slider(activity, "bubbleOutlineSlider").getValue());
        assertEquals(opacityBefore, slider(activity, "glassOpacitySlider").getValue());
        assertEquals(tintBefore, slider(activity, "glassTintSlider").getValue());
        assertEquals(edgeBefore, slider(activity, "glassEdgeSlider").getValue());

        // 25: and the draft's other premium fields are untouched.
        OrbitProStyle pro = draft(activity).pro;
        assertEquals(outlineBefore, pro.bubbleOutline);
        assertEquals(opacityBefore, pro.glassOpacity);
        assertEquals(tintBefore, pro.glassTint);
        assertEquals(edgeBefore, pro.glassEdge);
        controller.pause().stop().destroy();
    }

    // ---- 14 to 16. the gesture contract -------------------------------------------------------------------

    /**
     * 14. Lifting the finger settles the value the drag reached, and does not sample again.
     *
     * <p>Reproduced exactly as it happens: ACTION_UP carries its own coordinate, and a finger
     * rolling off the glass routinely lands it several pixels from the last ACTION_MOVE. Beta 2
     * recomputed from it, so the thumb moved once more after the user had stopped.
     */
    @Test public void liftingTheFingerDoesNotResampleIntoADifferentValue() {
        OrbitSlider slider = measuredSlider(0, 100, 50);
        List<Integer> settled = new ArrayList<>();
        slider.setOnValueChangeListener((view, value, isSettled) -> {
            if (isSettled) settled.add(value);
        });

        float width = slider.getWidth();
        dispatch(slider, MotionEvent.ACTION_DOWN, width * 0.5f);
        dispatch(slider, MotionEvent.ACTION_MOVE, width * 0.75f);
        int afterMove = slider.getValue();

        // The lift lands a long way from the last move, which is the pathological version of what
        // a real finger does. The value must not follow it.
        dispatch(slider, MotionEvent.ACTION_UP, width * 0.20f);

        assertEquals("the value must stay where the drag left it", afterMove, slider.getValue());
        assertEquals("and settle exactly once", 1, settled.size());
        assertEquals(afterMove, (int) settled.get(0));
    }

    /** 15. A cancelled gesture keeps the value it had reached rather than inventing a new one. */
    @Test public void cancellingAGestureDoesNotJumpToANewValue() {
        OrbitSlider slider = measuredSlider(0, 100, 50);
        float width = slider.getWidth();
        dispatch(slider, MotionEvent.ACTION_DOWN, width * 0.5f);
        dispatch(slider, MotionEvent.ACTION_MOVE, width * 0.6f);
        int afterMove = slider.getValue();
        dispatch(slider, MotionEvent.ACTION_CANCEL, 0f);
        assertEquals(afterMove, slider.getValue());
    }

    /** 16. The programmatic path stays silent, which is what makes synchronization safe. */
    @Test public void setValueNeverNotifiesTheListener() {
        OrbitSlider slider = measuredSlider(0, 100, 50);
        List<Integer> heard = new ArrayList<>();
        slider.setOnValueChangeListener((view, value, settled) -> heard.add(value));
        slider.setValue(10);
        slider.setValue(90);
        slider.setValue(90);
        assertEquals(90, slider.getValue());
        assertTrue("setValue must never call back, found " + heard, heard.isEmpty());
    }

    // ---- 17 to 23. preset selection is draft-only ------------------------------------------------------------

    /**
     * 17, 18, 19 and 20. Selecting a preset synchronizes the controls once, silently.
     *
     * <p>Run over a premium preset and an ordinary one together, because the reported symptom was
     * that the two behaved differently. They do not: a premium preset is simply the first kind that
     * carries values these controls display, so it is the first one where the synchronization was
     * visible at all.
     */
    @Test public void selectingAnyPresetSynchronizesSlidersOnceWithoutCallbacks() {
        ActivityController<ThemeStudioActivity> controller = openPro();
        ThemeStudioActivity activity = controller.get();

        for (String id : new String[]{OrbitTheme.ID_SIGNAL_VIOLET, OrbitTheme.ID_NEBULA_GLASS,
                OrbitTheme.ID_NEBULA, OrbitTheme.ID_DEFAULT}) {
            OrbitTheme preset = OrbitTheme.builtIn(id);
            List<Integer> callbacks = new ArrayList<>();
            OrbitSlider radius = slider(activity, "bubbleRadiusSlider");
            radius.setOnValueChangeListener((view, value, settled) -> callbacks.add(value));

            invoke(activity, "selectPreset", new Class<?>[]{OrbitTheme.class}, preset);

            assertTrue(preset.name + " must not fire edit callbacks while synchronizing, found "
                    + callbacks, callbacks.isEmpty());
            assertEquals(preset.name + " must show its own bubble roundness",
                    preset.pro.bubbleRadiusDp, radius.getValue());
            assertEquals(preset.pro.bubbleOutline,
                    slider(activity, "bubbleOutlineSlider").getValue());
            assertEquals(preset.pro.glassOpacity,
                    slider(activity, "glassOpacitySlider").getValue());
            assertEquals(preset.pro.glassTint, slider(activity, "glassTintSlider").getValue());
            assertEquals(preset.pro.glassEdge, slider(activity, "glassEdgeSlider").getValue());
        }
        controller.pause().stop().destroy();
    }

    /**
     * 18, 19, 20, 21, 22 and 23. Selecting a preset changes the draft and nothing else.
     *
     * <p>The mental model the whole screen rests on, asserted against the app's own state rather
     * than against the screen's. Orbit goes on drawing what it was drawing, no appearance broadcast
     * goes out, and only Apply moves the applied theme.
     */
    @Test public void selectingAPresetIsPreviewOnlyUntilApply() {
        ActivityController<ThemeStudioActivity> controller = openPro();
        ThemeStudioActivity activity = controller.get();

        OrbitTheme appliedBefore = OrbitThemeStore.active(context);
        OrbitProStyle liveBefore = OrbitProStyle.live(context);
        int accentBefore = UiKit.accent(context);

        for (String id : new String[]{OrbitTheme.ID_SIGNAL_VIOLET, OrbitTheme.ID_NEBULA_GLASS,
                OrbitTheme.ID_TIDE}) {
            OrbitTheme preset = OrbitTheme.builtIn(id);
            invoke(activity, "selectPreset", new Class<?>[]{OrbitTheme.class}, preset);

            assertEquals(preset.name + " must only reach the draft", preset.id, draft(activity).id);
            assertTrue("the applied theme must not move",
                    OrbitThemeStore.active(context).sameColours(appliedBefore));
            assertTrue("nor the styling Orbit actually draws with",
                    OrbitProStyle.live(context).same(liveBefore));
            assertEquals("nor the live canvas", accentBefore, UiKit.accent(context));
        }

        // Only Apply commits, and then it commits exactly what was in the preview.
        invoke(activity, "selectPreset", new Class<?>[]{OrbitTheme.class},
                OrbitTheme.builtIn(OrbitTheme.ID_NEBULA_GLASS));
        invoke(activity, "applyDraft");
        assertEquals(OrbitTheme.ID_NEBULA_GLASS, OrbitThemeStore.active(context).id);
        assertTrue(OrbitProStyle.live(context)
                .same(OrbitTheme.builtIn(OrbitTheme.ID_NEBULA_GLASS).pro));
        controller.pause().stop().destroy();
    }

    /** 22. And selection never broadcasts an appearance change to the rest of the app. */
    @Test public void presetSelectionDoesNotBroadcastAnAppearanceChange() {
        String source = ThemeStudioProTest.readSourceFile("ThemeStudioActivity.java");
        int at = source.indexOf("private void selectPreset(");
        assertTrue(at > 0);
        String body = source.substring(at, source.indexOf("\n    }", at));
        assertFalse("selecting a preset must not notify the app",
                body.contains("notifyAppearanceChanged"));
        assertFalse("and must not write the active theme", body.contains("applyActive"));
    }

    // ---- 24 to 26. editing after selecting ------------------------------------------------------------------

    /**
     * 24, 25 and 26. The value the user chose survives the settle that follows it.
     *
     * <p>This is the Beta 2 bug with the sharpest edge, and it was not in the slider at all.
     * Settling re-derives which preset the draft is, and {@code canonicalIdentity} rebuilt the
     * theme through a constructor overload that defaults the premium block - so releasing the thumb
     * reset all five premium values to Orbit's own. The thumb appeared to snap back because the
     * draft behind it really had.
     */
    @Test public void aSliderEditAfterSelectingAPresetKeepsItsValue() {
        ActivityController<ThemeStudioActivity> controller = openPro();
        ThemeStudioActivity activity = controller.get();

        OrbitTheme violet = OrbitTheme.builtIn(OrbitTheme.ID_SIGNAL_VIOLET);
        invoke(activity, "selectPreset", new Class<?>[]{OrbitTheme.class}, violet);

        OrbitSlider opacity = slider(activity, "glassOpacitySlider");
        measure(opacity);
        drag(opacity, 0.5f, 0.95f);
        int chosen = opacity.getValue();

        assertTrue("the drag must have moved it", chosen != violet.pro.glassOpacity);
        assertEquals("and the draft must hold exactly what was chosen",
                chosen, draft(activity).pro.glassOpacity);
        assertEquals("the control must agree with the draft",
                chosen, slider(activity, "glassOpacitySlider").getValue());

        // 25 and 26: the identity may stop being Signal Violet, and that must cost nothing else.
        assertEquals("Signal Violet's other premium values must survive",
                violet.pro.bubbleRadiusDp, draft(activity).pro.bubbleRadiusDp);
        assertEquals(violet.pro.bubbleOutline, draft(activity).pro.bubbleOutline);
        assertEquals(violet.pro.glassTint, draft(activity).pro.glassTint);
        assertEquals(violet.pro.glassEdge, draft(activity).pro.glassEdge);
        assertEquals("and its colours too", violet.accent, draft(activity).accent);
        controller.pause().stop().destroy();
    }

    /** The same rule at the model level, where the reset actually happened. */
    @Test public void canonicalIdentityNeverDiscardsPremiumStyling() {
        Prefs.setProPreview(context, Prefs.PRO_PREVIEW_PRO);
        OrbitProStyle styled = OrbitProStyle.of(11, OrbitProStyle.OUTLINE_DEFINED, 180, 150, 140);

        // A built-in wearing premium styling it does not itself have: the exact shape of "the user
        // selected a preset and then moved a slider".
        OrbitTheme edited = OrbitTheme.builtIn(OrbitTheme.ID_NEBULA).withPro(styled);
        OrbitTheme canonical = OrbitThemeStore.canonicalIdentity(context, edited);
        assertTrue("re-labelling must carry the premium styling through",
                canonical.pro.same(styled));
        assertFalse("while the theme stops claiming to be the preset", canonical.builtIn);

        // And the other fallback, for a theme that already belonged to the user.
        OrbitTheme owned = OrbitTheme.custom("Mine", "violet", OrbitTheme.CLASSIC,
                OrbitTheme.CLASSIC, OrbitTheme.CLASSIC, OrbitTheme.CLASSIC, false, styled);
        assertTrue(OrbitThemeStore.canonicalIdentity(context, owned).pro.same(styled));
    }

    // ---- 27 to 29. the previews hold still ----------------------------------------------------------------------

    /**
     * 27, 28 and 29. None of the three previews rebuilds itself to show a new value.
     *
     * <p>Identity of the child views is the test, because that is exactly what was lost. The main
     * preview called {@code removeAllViews} on every render, which is both wasteful and the likeliest
     * source of the weight flicker: a fresh TextView on every frame is a fresh TextView for the
     * typography watcher to meet.
     */
    @Test public void everyPreviewKeepsItsChildViewsAcrossUpdates() {
        ActivityController<ThemeStudioActivity> controller = openPro();
        ThemeStudioActivity activity = controller.get();

        ThemePreviewView preview = (ThemePreviewView) field(activity, "preview");
        MessageStylePreview message = (MessageStylePreview) field(activity, "messagePreview");
        GlassStylePreview glass = (GlassStylePreview) field(activity, "glassPreview");
        BackgroundStylePreview background =
                (BackgroundStylePreview) field(activity, "backgroundPreview");

        List<View> previewBefore = descendants(preview);
        List<View> messageBefore = descendants(message);
        List<View> glassBefore = descendants(glass);
        List<View> backgroundBefore = descendants(background);
        assertTrue("the previews must actually contain something", previewBefore.size() > 5);
        assertTrue("including the background sample", backgroundBefore.size() > 3);

        drag(slider(activity, "bubbleRadiusSlider"), 0.1f, 0.9f);
        drag(slider(activity, "glassOpacitySlider"), 0.9f, 0.2f);
        drag(slider(activity, "glowStrengthSlider"), 0.1f, 0.8f);
        drag(slider(activity, "glowSizeSlider"), 0.8f, 0.3f);

        assertSameViews("the main preview", previewBefore, descendants(preview));
        assertSameViews("the message sample", messageBefore, descendants(message));
        assertSameViews("the glass sample", glassBefore, descendants(glass));
        assertSameViews("the background sample", backgroundBefore, descendants(background));
        controller.pause().stop().destroy();
    }

    /** And the source says so, so a future rewrite cannot quietly go back to rebuilding. */
    @Test public void previewsUpdateInPlaceRatherThanRebuilding() {
        for (String file : new String[]{"ThemePreviewView.java", "MessageStylePreview.java",
                "GlassStylePreview.java", "BackgroundStylePreview.java"}) {
            String source = ThemeStudioProTest.readSourceFile(file);
            int at = source.indexOf("public void render(");
            assertTrue(file + " must have a render method", at > 0);
            String body = source.substring(at, Math.min(source.length(), at + 3000));
            // A bare self-clear is the thing being forbidden. ThemePreviewView legitimately calls
            // markHost.removeAllViews() to swap the one child it cannot recolour in place, the
            // brand mark, and only when the accent actually changes - so the receiver matters and
            // banning the method name outright would ban the documented exception with it.
            assertFalse(file + "'s render must not clear its own hierarchy",
                    body.contains("\n        removeAllViews()"));
        }
    }

    // ---- the Background tool, on the same terms as everything above it -------------------------------

    /**
     * The third tool exists, in order, with its own sample above its own controls.
     *
     * <p>Order asserted rather than presence alone. Message style, Liquid Glass and then Background is
     * increasing scope - a bubble, the controls over the page, the page - and the newest tool going
     * anywhere but last would move two a person already knows.
     */
    @Test public void proStateHasAThirdToolForTheBackground() {
        ActivityController<ThemeStudioActivity> controller = openPro();
        ThemeStudioActivity activity = controller.get();
        View root = activity.getWindow().getDecorView();

        BackgroundStylePreview sample =
                (BackgroundStylePreview) field(activity, "backgroundPreview");
        assertNotNull("Background must have its own live sample", sample);
        assertNotNull(field(activity, "backgroundModeSegment"));
        assertNotNull(field(activity, "glowStrengthSlider"));
        assertNotNull(field(activity, "glowSizeSlider"));
        assertNotNull(field(activity, "glowPositionSegment"));

        // Read inside the Orbit Pro card only. "Background" is also the name of the free background
        // colour row in the Colors card at the top of the page, which is exactly where it should be
        // and exactly what a scan of the whole screen would trip over.
        List<String> titles = new ArrayList<>();
        for (String text : textsIn((View) field(activity, "proBody"))) {
            if (text.equals("Message style") || text.equals("Glass")
                    || text.equals("Background")) {
                if (!titles.contains(text)) titles.add(text);
            }
        }
        assertEquals("the three tools must appear in order of increasing scope",
                List.of("Message style", "Glass", "Background"), titles);

        // And the sample sits above the controls that change it, not at the top of the page.
        int sampleAt = indexOfDescendant(root, sample);
        int segmentAt = indexOfDescendant(root, (View) field(activity, "backgroundModeSegment"));
        assertTrue("the background sample must lead its own controls", sampleAt < segmentAt);
        controller.pause().stop().destroy();
    }

    /**
     * Only the controls that apply to the chosen mode are on screen, and none are ever rebuilt.
     *
     * <p>Visibility rather than construction, which is the Beta 3 rule applied to a new kind of
     * change. Switching Linear to Glow and back has to return to the same direction row holding the
     * same value, so the assertion is on object identity across the round trip rather than on what is
     * visible at the end of it.
     */
    @Test public void changingBackgroundModeShowsAndHidesRatherThanRebuilds() {
        ActivityController<ThemeStudioActivity> controller = openPro();
        ThemeStudioActivity activity = controller.get();

        View linear = (View) field(activity, "linearControls");
        View glow = (View) field(activity, "glowControls");
        OrbitSlider strength = (OrbitSlider) field(activity, "glowStrengthSlider");
        View direction = (View) field(activity, "directionRow");
        List<View> linearBefore = descendants(linear);
        List<View> glowBefore = descendants(glow);

        assertEquals("Solid shows no effect controls at all", View.GONE, linear.getVisibility());
        assertEquals(View.GONE, glow.getVisibility());

        chooseBackgroundMode(activity, OrbitProStyle.BACKGROUND_LINEAR);
        assertEquals(View.VISIBLE, linear.getVisibility());
        assertEquals(View.GONE, glow.getVisibility());

        chooseBackgroundMode(activity, OrbitProStyle.BACKGROUND_GLOW);
        assertEquals(View.GONE, linear.getVisibility());
        assertEquals(View.VISIBLE, glow.getVisibility());

        chooseBackgroundMode(activity, OrbitProStyle.BACKGROUND_SOLID);
        assertEquals(View.GONE, linear.getVisibility());
        assertEquals(View.GONE, glow.getVisibility());

        assertSame("the glow sliders must be the same objects afterwards",
                strength, field(activity, "glowStrengthSlider"));
        assertSame("and so must the direction row", direction, field(activity, "directionRow"));
        assertSameViews("the linear controls", linearBefore, descendants(linear));
        assertSameViews("the glow controls", glowBefore, descendants(glow));
        controller.pause().stop().destroy();
    }

    /**
     * Every background adjustment is a draft edit. Only Apply changes Orbit.
     *
     * <p>The same contract the preset fix established, extended to the six values this release added.
     * Each is changed in turn and the applied theme, the live premium styling and the live canvas are
     * all asserted not to move - then Apply is pressed once and asserted to commit exactly what the
     * preview was showing.
     */
    @Test public void backgroundEditsAreDraftOnlyUntilApply() {
        ActivityController<ThemeStudioActivity> controller = openPro();
        ThemeStudioActivity activity = controller.get();

        OrbitTheme appliedBefore = OrbitThemeStore.active(context);
        OrbitProStyle liveBefore = OrbitProStyle.live(context);
        int backgroundBefore = UiKit.BG;

        chooseBackgroundMode(activity, OrbitProStyle.BACKGROUND_GLOW);
        editPro(activity, draft(activity).pro.withBackgroundEffectColor("#5B3FCF"));
        editPro(activity, draft(activity).pro.withGlowPosition(OrbitProStyle.GLOW_BOTTOM));
        drag(slider(activity, "glowStrengthSlider"), 0.1f, 0.9f);
        drag(slider(activity, "glowSizeSlider"), 0.9f, 0.2f);
        editPro(activity, draft(activity).pro
                .withBackgroundMode(OrbitProStyle.BACKGROUND_LINEAR)
                .withGradientDirection(OrbitProStyle.DIRECTION_BR_TL));

        assertTrue("the draft must have moved", draft(activity).pro.hasBackgroundEffect());
        assertTrue("but the applied theme must not",
                OrbitThemeStore.active(context).sameColours(appliedBefore));
        assertTrue("nor the styling Orbit actually draws with",
                OrbitProStyle.live(context).same(liveBefore));
        assertEquals("nor the live page colour", backgroundBefore, UiKit.BG);
        assertFalse("and no page anywhere in Orbit may be drawing an effect yet",
                OrbitBackground.effectDraws(context));

        OrbitProStyle intended = draft(activity).pro;
        invoke(activity, "applyDraft");
        assertTrue("Apply commits exactly what the preview showed",
                OrbitProStyle.live(context).same(intended));
        assertTrue(OrbitBackground.effectDraws(context));
        controller.pause().stop().destroy();
    }

    /** A segmented control reports a tap once, and reports a synchronization never. */
    @Test public void segmentedSelectionNeverNotifiesWhenItIsSynchronized() {
        OrbitSegmented segmented = new OrbitSegmented(context);
        segmented.setOptions(new String[]{"Solid", "Linear", "Glow"});
        List<Integer> reported = new ArrayList<>();
        segmented.setOnSelectListener((view, index) -> reported.add(index));

        segmented.setSelected(2);
        segmented.setSelected(0);
        segmented.setSelected(0);
        assertTrue("setSelected must never call the listener", reported.isEmpty());
        assertEquals(0, segmented.selectedIndex());

        segmented.segmentAt(1).performClick();
        assertEquals("a tap reports exactly once", List.of(1), reported);
        assertEquals(1, segmented.selectedIndex());
        segmented.segmentAt(1).performClick();
        assertEquals("and tapping the current choice reports nothing", List.of(1), reported);

        // Rebuilding with the same options is a no-op, so a caller cannot destroy it by asking.
        View pill = segmented.segmentAt(1);
        segmented.setOptions(new String[]{"Solid", "Linear", "Glow"});
        assertSame("the segments must survive a repeated setOptions", pill, segmented.segmentAt(1));
    }

    /**
     * Selecting a background preset synchronizes every new control once, without callbacks.
     *
     * <p>Aurora and Nova Ultra are the first presets that move the background controls, so they are
     * the first ones where a synchronization reporting itself as a user edit would be visible - and
     * the Beta 3 fix this extends is exactly that.
     */
    @Test public void selectingABackgroundPresetSynchronizesTheNewControlsOnce() {
        ActivityController<ThemeStudioActivity> controller = openPro();
        ThemeStudioActivity activity = controller.get();

        OrbitSegmented mode = (OrbitSegmented) field(activity, "backgroundModeSegment");
        OrbitSegmented position = (OrbitSegmented) field(activity, "glowPositionSegment");
        OrbitSlider strength = (OrbitSlider) field(activity, "glowStrengthSlider");
        OrbitSlider size = (OrbitSlider) field(activity, "glowSizeSlider");

        for (String id : new String[]{OrbitTheme.ID_AURORA, OrbitTheme.ID_NOVA_ULTRA}) {
            OrbitTheme preset = OrbitTheme.builtIn(id);
            invoke(activity, "selectPreset", new Class<?>[]{OrbitTheme.class}, preset);

            assertEquals(preset.name + " must reach the draft only", preset.id, draft(activity).id);
            assertSame("no control may be replaced", mode, field(activity, "backgroundModeSegment"));
            assertSame(position, field(activity, "glowPositionSegment"));
            assertSame(strength, field(activity, "glowStrengthSlider"));
            assertSame(size, field(activity, "glowSizeSlider"));

            assertEquals("and every one must be showing the preset's own value",
                    preset.pro.backgroundMode, mode.selectedIndex());
            assertEquals(preset.pro.glowPosition, position.selectedIndex());
            assertEquals(preset.pro.glowStrength, strength.getValue());
            assertEquals(preset.pro.glowSize, size.getValue());
        }
        controller.pause().stop().destroy();
    }

    /** Dragging a glow slider does not move the reading position, exactly as the others do not. */
    @Test public void scrollPositionIsUnchangedByABackgroundSliderDrag() {
        ActivityController<ThemeStudioActivity> controller = openPro();
        ThemeStudioActivity activity = controller.get();
        android.widget.ScrollView scroll =
                (android.widget.ScrollView) field(activity, "contentScroll");
        assertNotNull(scroll);
        scroll.scrollTo(0, 420);
        int before = scroll.getScrollY();

        chooseBackgroundMode(activity, OrbitProStyle.BACKGROUND_GLOW);
        drag(slider(activity, "glowStrengthSlider"), 0.2f, 0.85f);
        drag(slider(activity, "glowSizeSlider"), 0.85f, 0.25f);

        assertEquals("a background drag must not move the page", before, scroll.getScrollY());
        controller.pause().stop().destroy();
    }

    /** AMOLED explains itself rather than silently swallowing a configured effect. */
    @Test public void amoledSaysWhyTheBackgroundEffectIsNotShowing() {
        ActivityController<ThemeStudioActivity> controller = openPro();
        ThemeStudioActivity activity = controller.get();
        View note = (View) field(activity, "amoledBackgroundNote");
        assertNotNull(note);
        assertEquals("nothing to explain with no effect configured",
                View.GONE, note.getVisibility());

        chooseBackgroundMode(activity, OrbitProStyle.BACKGROUND_GLOW);
        assertEquals("still nothing, because AMOLED is off", View.GONE, note.getVisibility());

        invoke(activity, "edit", new Class<?>[]{OrbitTheme.class},
                draft(activity).withAmoled(true));
        assertEquals("now it has to say so", View.VISIBLE, note.getVisibility());
        assertTrue("and say how to see the effect again",
                textsIn(note).toString().contains("Turn AMOLED off"));
        assertTrue("while the configuration really is kept",
                draft(activity).pro.backgroundMode == OrbitProStyle.BACKGROUND_GLOW);

        invoke(activity, "edit", new Class<?>[]{OrbitTheme.class},
                draft(activity).withAmoled(false));
        assertEquals(View.GONE, note.getVisibility());
        assertEquals("and the glow returns", OrbitProStyle.BACKGROUND_GLOW,
                draft(activity).pro.backgroundMode);
        controller.pause().stop().destroy();
    }

    // ---- the free material selector ----------------------------------------------------------------

    /**
     * The material selector is in the Colors card, is free, and does not disturb the section order.
     *
     * <p>Where it sits is the product decision this test protects. It is a free theme setting, so it
     * belongs with the free theme settings; and it is one row, so giving it a card of its own between
     * Presets and Orbit Pro would have put a third major heading on the screen for a single choice and
     * undone the ordering v0.8.0.0-beta.3 settled on the device.
     */
    @Test public void theMaterialSelectorIsFreeAndLivesWithTheColours() {
        ActivityController<ThemeStudioActivity> controller = openFree();
        ThemeStudioActivity activity = controller.get();

        OrbitSegmented selector = (OrbitSegmented) field(activity, "materialSegment");
        assertNotNull("a Free device must get the material selector", selector);
        assertEquals("with all three materials", 3, OrbitTheme.materials().length);
        assertEquals(OrbitTheme.materialIndex(draft(activity).material), selector.selectedIndex());

        // Inside the Colors card, above Presets, and with no fourth card added.
        View root = activity.getWindow().getDecorView();
        assertTrue("the selector must come before the Presets heading",
                indexOfDescendant(root, selector) < indexOfDescendant(root,
                        findText(root, "Presets")));
        assertEquals("and the section order must be untouched",
                List.of("Colors", "Presets", "Orbit Pro"), sectionOrder(activity));

        // And no premium controls came with it.
        assertTrue("Free must still build no premium sliders",
                findAll((View) field(activity, "proBody"), OrbitSlider.class).isEmpty());
        controller.pause().stop().destroy();
    }

    /** Choosing a material is a draft edit like any other, and only Apply commits it. */
    @Test public void choosingAMaterialIsDraftOnlyUntilApply() {
        ActivityController<ThemeStudioActivity> controller = openFree();
        ThemeStudioActivity activity = controller.get();

        OrbitSegmented selector = (OrbitSegmented) field(activity, "materialSegment");
        String appliedBefore = OrbitThemeStore.activeMaterial(context);

        int solid = OrbitTheme.materialIndex(OrbitTheme.MATERIAL_SOLID);
        selector.segmentAt(solid).performClick();
        assertEquals("the draft must have moved",
                OrbitTheme.MATERIAL_SOLID, draft(activity).material);
        assertEquals("but Orbit must still be drawing what it was",
                appliedBefore, OrbitThemeStore.activeMaterial(context));

        invoke(activity, "applyDraft");
        assertEquals("Apply commits it", OrbitTheme.MATERIAL_SOLID,
                OrbitThemeStore.activeMaterial(context));
        controller.pause().stop().destroy();
    }

    /**
     * Solid hides the three glass sliders without destroying them, and says why.
     *
     * <p>The Beta 3 rule applied to a free choice. Switching to Solid and back has to return the same
     * three sliders holding the same three values, because the alternative is a person losing tuning
     * they spent time on by trying a material out.
     */
    @Test public void solidHidesTheGlassSlidersWithoutLosingThem() {
        ActivityController<ThemeStudioActivity> controller = openPro();
        ThemeStudioActivity activity = controller.get();

        OrbitSlider opacity = (OrbitSlider) field(activity, "glassOpacitySlider");
        OrbitSlider tint = (OrbitSlider) field(activity, "glassTintSlider");
        OrbitSlider edge = (OrbitSlider) field(activity, "glassEdgeSlider");
        View glassControls = (View) field(activity, "glassControls");
        View note = (View) field(activity, "noGlassNote");
        assertNotNull(glassControls);
        assertNotNull(note);

        drag(slider(activity, "glassTintSlider"), 0.1f, 0.8f);
        int tunedTint = tint.getValue();
        assertEquals(View.VISIBLE, glassControls.getVisibility());
        assertEquals(View.GONE, note.getVisibility());

        chooseMaterial(activity, OrbitTheme.MATERIAL_SOLID);
        assertEquals("Solid hides them", View.GONE, glassControls.getVisibility());
        assertEquals("and says so", View.VISIBLE, note.getVisibility());
        assertTrue("in words that promise the values are kept",
                textsIn(note).toString().contains("kept"));

        chooseMaterial(activity, OrbitTheme.MATERIAL_FROSTED);
        assertEquals("Frosted brings them back", View.VISIBLE, glassControls.getVisibility());
        assertEquals(View.GONE, note.getVisibility());
        assertSame("as the same objects", opacity, field(activity, "glassOpacitySlider"));
        assertSame(tint, field(activity, "glassTintSlider"));
        assertSame(edge, field(activity, "glassEdgeSlider"));
        assertEquals("holding the same value", tunedTint, tint.getValue());
        assertEquals("and the draft still carries it", tunedTint, draft(activity).pro.glassTint);
        controller.pause().stop().destroy();
    }

    /** The gradient strength slider behaves like every other Beta 3 slider. */
    @Test public void theGradientStrengthSliderKeepsEveryBetaThreeGuarantee() {
        ActivityController<ThemeStudioActivity> controller = openPro();
        ThemeStudioActivity activity = controller.get();

        chooseBackgroundMode(activity, OrbitProStyle.BACKGROUND_LINEAR);
        OrbitSlider strength = (OrbitSlider) field(activity, "gradientStrengthSlider");
        assertNotNull("Linear must offer a strength control", strength);
        assertEquals("starting at the Beta 4 gradient",
                OrbitProStyle.GRADIENT_STRENGTH_DEFAULT, strength.getValue());

        OrbitSlider direction = (OrbitSlider) field(activity, "glowStrengthSlider");
        int unrelatedBefore = direction.getValue();

        drag(slider(activity, "gradientStrengthSlider"), 0.9f, 0.25f);
        int afterMove = strength.getValue();
        assertTrue("the drag must have moved it",
                afterMove < OrbitProStyle.GRADIENT_STRENGTH_DEFAULT);
        assertEquals("and lifting the finger must not resample it",
                afterMove, draft(activity).pro.gradientStrength);
        assertSame("the slider must not be recreated", strength,
                field(activity, "gradientStrengthSlider"));
        assertEquals("and no unrelated slider may move", unrelatedBefore, direction.getValue());

        // Programmatic synchronization never reports itself as a user edit.
        List<Integer> reported = new ArrayList<>();
        strength.setOnValueChangeListener((view, value, settled) -> reported.add(value));
        strength.setValue(OrbitProStyle.GRADIENT_STRENGTH_MIN);
        strength.setValue(OrbitProStyle.GRADIENT_STRENGTH_MAX);
        assertTrue("setValue must never notify", reported.isEmpty());
        controller.pause().stop().destroy();
    }

    /** Selecting a preset moves the free material selector too, once and without callbacks. */
    @Test public void selectingAPresetSynchronizesTheMaterialSelector() {
        ActivityController<ThemeStudioActivity> controller = openPro();
        ThemeStudioActivity activity = controller.get();
        OrbitSegmented selector = (OrbitSegmented) field(activity, "materialSegment");

        for (String id : new String[]{OrbitTheme.ID_AURORA, OrbitTheme.ID_NEBULA_GLASS,
                OrbitTheme.ID_SIGNAL_VIOLET}) {
            OrbitTheme preset = OrbitTheme.builtIn(id);
            invoke(activity, "selectPreset", new Class<?>[]{OrbitTheme.class}, preset);
            assertSame("the selector must not be replaced", selector,
                    field(activity, "materialSegment"));
            assertEquals(preset.name + " must move the selector to its own material",
                    OrbitTheme.materialIndex(preset.material), selector.selectedIndex());
            assertEquals(preset.material, draft(activity).material);
        }
        controller.pause().stop().destroy();
    }

    private void chooseMaterial(ThemeStudioActivity activity, String material) {
        invoke(activity, "edit", new Class<?>[]{OrbitTheme.class},
                draft(activity).withMaterial(material));
    }

    /** Selects a background mode the way the segmented control does, through the draft edit path. */
    private void chooseBackgroundMode(ThemeStudioActivity activity, int mode) {
        editPro(activity, draft(activity).pro.withBackgroundMode(mode));
    }

    private void editPro(ThemeStudioActivity activity, OrbitProStyle next) {
        invoke(activity, "editPro", new Class<?>[]{OrbitProStyle.class, boolean.class},
                next, true);
    }

    // ---- 30 to 32. typography ----------------------------------------------------------------------------------

    /**
     * 30 and 31. Intended weight is deterministic across everything that redraws a label.
     *
     * <p>The device symptom was text changing weight while a slider moved. The cause was that every
     * re-application of typography inferred the intended weight by reading the realized
     * {@link Typeface} back, and {@code Typeface.create} reports the style of the font it matched
     * rather than the one that was asked for. A family with no true bold therefore reported NORMAL
     * for a bold request, and the next pass made it normal for real.
     *
     * <p>The assertion is the round trip that was broken: put a label through repeated typography
     * passes and its weight must not move. "Looking good" is checked by name because it is the one
     * the user watched it happen to.
     */
    @Test public void intendedFontWeightSurvivesRepeatedTypographyPasses() {
        TextView bold = UiKit.text(context, "Looking good", 13, UiKit.TEXT, true);
        TextView normal = UiKit.text(context, "Bubble roundness", 13.5f, UiKit.TEXT, false);

        boolean boldWanted = bold.getTypeface() != null && bold.getTypeface().isBold();
        for (int pass = 0; pass < 5; pass++) {
            UiKit.applyTypography(bold);
            UiKit.applyTypography(normal);
            assertEquals("a bold label must stay bold on pass " + pass,
                    boldWanted, bold.getTypeface() != null && bold.getTypeface().isBold());
            assertFalse("a normal label must never become bold on pass " + pass,
                    normal.getTypeface() != null && normal.getTypeface().isBold());
        }
    }

    /** 30. And the same, driven through a real drag of the real screen. */
    @Test public void theMainPreviewHeadingKeepsItsWeightThroughADrag() {
        ActivityController<ThemeStudioActivity> controller = openPro();
        ThemeStudioActivity activity = controller.get();
        ThemePreviewView preview = (ThemePreviewView) field(activity, "preview");

        TextView heading = findText(preview, "Looking good");
        assertNotNull("the preview must contain the heading", heading);
        Typeface before = heading.getTypeface();

        drag(slider(activity, "bubbleRadiusSlider"), 0.1f, 0.9f);
        UiKit.applyTypography(preview);

        assertSame("the heading must be the same view after a drag",
                heading, findText(preview, "Looking good"));
        assertEquals("and must not have changed weight",
                before != null && before.isBold(),
                heading.getTypeface() != null && heading.getTypeface().isBold());
        controller.pause().stop().destroy();
    }

    /**
     * 31. Every premium control title is deliberately normal weight, and stays that way.
     *
     * <p>Beta 2 made each one bold at nearly heading size, which is most of why five settings read
     * as a wall. They are normal now; the accent value word beside them carries the emphasis.
     */
    @Test public void proControlTitlesAreNormalWeightAndStayThere() {
        ActivityController<ThemeStudioActivity> controller = openPro();
        ThemeStudioActivity activity = controller.get();
        View proBody = (View) field(activity, "proBody");

        for (String title : new String[]{"Bubble roundness", "Bubble outline", "Glass opacity",
                "Glass tint", "Glass edge"}) {
            TextView label = findText(proBody, title);
            assertNotNull(title + " must be on screen", label);
            assertFalse(title + " must not be bold",
                    label.getTypeface() != null && label.getTypeface().isBold());
        }

        drag(slider(activity, "glassTintSlider"), 0.2f, 0.8f);
        UiKit.applyTypography(proBody);

        for (String title : new String[]{"Bubble roundness", "Glass tint"}) {
            TextView label = findText(proBody, title);
            assertNotNull(label);
            assertFalse(title + " must still not be bold after a drag",
                    label.getTypeface() != null && label.getTypeface().isBold());
        }
        controller.pause().stop().destroy();
    }

    /** 32. Re-weighting goes through UiKit so the intent is recorded rather than overwritten. */
    @Test public void deliberateReWeightingIsRecordedAsTheNewIntent() {
        TextView label = UiKit.text(context, "Nebula", 13, UiKit.TEXT, false);
        UiKit.setTextWeight(label, true);
        boolean boldWanted = label.getTypeface() != null && label.getTypeface().isBold();

        for (int pass = 0; pass < 3; pass++) UiKit.applyTypography(label);
        assertEquals("a deliberately bolded label must stay bold",
                boldWanted, label.getTypeface() != null && label.getTypeface().isBold());

        UiKit.setTextWeight(label, false);
        for (int pass = 0; pass < 3; pass++) UiKit.applyTypography(label);
        assertFalse("and must go back to normal when told to",
                label.getTypeface() != null && label.getTypeface().isBold());
    }

    // ---- 33. the page does not move ---------------------------------------------------------------------------

    /** 33. A drag leaves the user where they were editing. */
    @Test public void scrollPositionIsUnchangedByASliderDrag() {
        ActivityController<ThemeStudioActivity> controller = openPro();
        ThemeStudioActivity activity = controller.get();

        android.widget.ScrollView scroll =
                (android.widget.ScrollView) field(activity, "contentScroll");
        assertNotNull(scroll);
        scroll.setScrollY(420);

        drag(slider(activity, "glassEdgeSlider"), 0.2f, 0.85f);

        assertEquals("the page must not scroll because a slider moved", 420, scroll.getScrollY());
        controller.pause().stop().destroy();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /**
     * The order Theme Studio's card headings appear in, top to bottom.
     *
     * <p>Matched on the text the headings actually carry. {@code cardTitle} draws them in caps
     * through {@code setAllCaps}, which is a display transformation and leaves {@code getText}
     * returning the original words - so "Colors" is what is here, while the all-caps "ORBIT PRO"
     * string belongs to the small Pro chip and is deliberately not one of these.
     */
    private List<String> sectionOrder(ThemeStudioActivity activity) {
        List<String> out = new ArrayList<>();
        for (String text : allTexts(activity)) {
            if (text.equals("Colors") || text.equals("Presets") || text.equals("Orbit Pro")) {
                if (!out.contains(text)) out.add(text);
            }
        }
        return out;
    }

    private int previewIndex(ThemeStudioActivity activity) {
        List<View> all = descendants(activity.getWindow().getDecorView());
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i) instanceof ThemePreviewView) return i;
        }
        return -1;
    }

    /**
     * A slider that has been through measure and layout, so it has a width to compute against.
     *
     * <p>Robolectric does not lay a detached view out, and a slider with zero width returns its
     * current value for every coordinate, which would make every gesture assertion here vacuous.
     */
    private OrbitSlider measuredSlider(int min, int max, int start) {
        OrbitSlider slider = new OrbitSlider(context);
        slider.setRange(min, max, start);
        measure(slider);
        return slider;
    }

    private void measure(OrbitSlider slider) {
        slider.measure(
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY));
        slider.layout(0, 0, 600, 120);
        assertTrue("the slider must have a width to drag across", slider.getWidth() > 0);
    }

    /** Down, a few moves, then up at the end position. The gesture a finger actually makes. */
    private void drag(OrbitSlider slider, float fromFraction, float toFraction) {
        if (slider.getWidth() <= 0) measure(slider);
        float width = slider.getWidth();
        dispatch(slider, MotionEvent.ACTION_DOWN, width * fromFraction);
        for (int step = 1; step <= 4; step++) {
            float at = fromFraction + ((toFraction - fromFraction) * step / 4f);
            dispatch(slider, MotionEvent.ACTION_MOVE, width * at);
        }
        dispatch(slider, MotionEvent.ACTION_UP, width * toFraction);
    }

    private void dispatch(OrbitSlider slider, int action, float x) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, slider.getHeight() / 2f, 0);
        slider.onTouchEvent(event);
        event.recycle();
    }

    private OrbitSlider slider(ThemeStudioActivity activity, String name) {
        OrbitSlider slider = (OrbitSlider) field(activity, name);
        assertNotNull(name + " must exist in the Pro state", slider);
        if (slider.getWidth() <= 0) measure(slider);
        return slider;
    }

    private void assertSameViews(String what, List<View> before, List<View> after) {
        assertEquals(what + " must keep the same number of views", before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertSame(what + " must keep view " + i, before.get(i), after.get(i));
        }
    }

    private List<View> descendants(View root) {
        List<View> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private void collect(View view, List<View> into) {
        if (view == null) return;
        into.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), into);
        }
    }

    private <T> List<T> findAll(View root, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (View view : descendants(root)) {
            if (type.isInstance(view)) out.add(type.cast(view));
        }
        return out;
    }

    private int indexOfDescendant(View root, View target) {
        List<View> all = descendants(root);
        for (int i = 0; i < all.size(); i++) if (all.get(i) == target) return i;
        return -1;
    }

    private int indexOfFirst(View root, Class<?> type) {
        List<View> all = descendants(root);
        for (int i = 0; i < all.size(); i++) if (type.isInstance(all.get(i))) return i;
        return Integer.MAX_VALUE;
    }

    private List<String> textsIn(View root) {
        List<String> out = new ArrayList<>();
        for (View view : descendants(root)) {
            if (view instanceof TextView) {
                CharSequence text = ((TextView) view).getText();
                if (text != null && text.length() > 0) out.add(text.toString());
            }
        }
        return out;
    }

    private List<String> allTexts(ThemeStudioActivity activity) {
        return textsIn(activity.getWindow().getDecorView());
    }

    private TextView findText(View root, String exact) {
        for (View view : descendants(root)) {
            if (view instanceof TextView
                    && exact.contentEquals(((TextView) view).getText())) {
                return (TextView) view;
            }
        }
        return null;
    }

    private OrbitTheme draft(ThemeStudioActivity activity) {
        return (OrbitTheme) field(activity, "draft");
    }

    private void invoke(Activity activity, String name, Class<?>[] types, Object... args) {
        try {
            java.lang.reflect.Method method =
                    ThemeStudioActivity.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(activity, args);
        } catch (Exception e) {
            throw new AssertionError("could not invoke " + name, e);
        }
    }

    private void invoke(Activity activity, String name) {
        invoke(activity, name, new Class<?>[0]);
    }

    private Object field(Activity activity, String name) {
        try {
            java.lang.reflect.Field field = ThemeStudioActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(activity);
        } catch (Exception e) {
            throw new AssertionError("could not read " + name, e);
        }
    }
}
