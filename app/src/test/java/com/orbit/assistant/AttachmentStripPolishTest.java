package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowValueAnimator;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What the attachment strip says, and where it is looking, after a redraw.
 *
 * <p>Both of these were reported from a real Galaxy S25 Ultra rather than found in code. Attaching
 * photos filled the strip with forty-character screenshot filenames, so two cards used the space
 * five could have; and every redraw ended by slamming the viewport to the far right, so attaching
 * a first batch of three photos showed Photo 3 with Photo 1 already off-screen. Neither was a
 * crash and both made the feature feel wrong to use, which is the kind of thing that only shows up
 * on a phone.
 *
 * <p>The scroll half is tested twice on purpose: once as the rule, which is a pure function of
 * what was on screen before and what is on screen now, and once through a laid-out strip, because
 * "the rule is right" and "the viewport ended up there" are different claims and it was the second
 * one that failed on the device.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class AttachmentStripPolishTest {

    /** Wide enough to hold about three cards, so there is genuinely somewhere to scroll. */
    private static final int VIEWPORT = 400;

    private Context context;
    private ActivityController<Activity> controller;
    private FrameLayout host;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        // A strip that is not attached to a window queues its posted scroll instead of running
        // it, so a test on a detached view would pass without the code under test ever executing.
        controller = Robolectric.buildActivity(Activity.class).setup();
        host = new FrameLayout(controller.get());
        controller.get().setContentView(host);
    }

    @After public void tearDown() {
        if (controller != null) controller.pause().stop().destroy();
    }

    // ---- what a card is called -------------------------------------------------------------------

    /**
     * Photos are captioned by position, not by the name a camera roll gave them.
     *
     * <p>{@code Screenshot_20260830_211455_Orbit Assistant.jpg} tells the user nothing the
     * thumbnail beside it does not already show, and costs the width of two more attachments.
     */
    @Test public void photosAreCaptionedByPosition() {
        List<ComposerAttachment> photos = images(
                "Screenshot_20260830_211455_Orbit Assistant.jpg",
                "Screenshot_20260830_211502_Orbit Assistant.jpg",
                "IMG_20260830_2115.jpg");

        assertEquals("Photo 1", AttachmentLabels.displayLabel(photos.get(0), 1));
        assertEquals("Photo 2", AttachmentLabels.displayLabel(photos.get(1), 2));
        assertEquals("Photo 3", AttachmentLabels.displayLabel(photos.get(2), 3));
    }

    /** The caption is a caption. Nothing underneath it was rewritten. */
    @Test public void theUnderlyingLabelIsNeverMutated() {
        ComposerAttachment photo = image("Screenshot_20260830_211455_Orbit Assistant.jpg");
        AttachmentLabels.displayLabel(photo, 1);
        assertEquals("history, the model, and diagnostics still need the real name",
                "Screenshot_20260830_211455_Orbit Assistant.jpg", photo.label);
    }

    /**
     * A document's filename is the information, so it stays.
     *
     * <p>Replacing {@code syllabus.pdf} with "File 1" would remove the only thing distinguishing
     * it from {@code timetable.pdf}, and unlike a photo there is no preview carrying that meaning.
     */
    @Test public void documentsKeepTheirRealFilenames() {
        assertEquals("syllabus.pdf",
                AttachmentLabels.displayLabel(document("pdf", "syllabus.pdf"), 1));
        assertEquals("notes.txt",
                AttachmentLabels.displayLabel(document("file_text", "notes.txt"), 2));
    }

    /** A screen capture already has Orbit's own short caption, so it is left alone. */
    @Test public void screenCapturesKeepOrbitsOwnCaption() {
        ComposerAttachment capture = new ComposerAttachment("screen", "Screen · Gmail", "", bitmap());
        assertEquals("Screen · Gmail", AttachmentLabels.displayLabel(capture, 1));
    }

    // ---- what a screen reader hears ---------------------------------------------------------------

    /** Shortening the caption must not shorten what TalkBack is told. */
    @Test public void aphotoIsAnnouncedByKindAndPosition() {
        List<ComposerAttachment> four = images("a.jpg", "b.jpg", "c.jpg", "d.jpg");
        assertEquals("Photo attachment 2 of 4",
                AttachmentLabels.cardDescription(four.get(1), 2, 4));
        assertEquals("Remove photo attachment 2",
                AttachmentLabels.removeDescription(four.get(1), 2, 4));
    }

    /** A lone photo is not read out as "1 of 1". */
    @Test public void asinglePhotoIsAnnouncedWithoutAPosition() {
        ComposerAttachment only = image("holiday.jpg");
        assertEquals("Photo attachment", AttachmentLabels.cardDescription(only, 1, 1));
        assertEquals("Remove photo attachment", AttachmentLabels.removeDescription(only, 1, 1));
    }

    /** A document is announced with the name, because for a document the name is the point. */
    @Test public void adocumentIsAnnouncedWithItsFilename() {
        ComposerAttachment pdf = document("pdf", "syllabus.pdf");
        assertEquals("PDF attachment 1 of 2, syllabus.pdf",
                AttachmentLabels.cardDescription(pdf, 1, 2));
        assertEquals("Remove PDF attachment 1, syllabus.pdf",
                AttachmentLabels.removeDescription(pdf, 1, 2));
    }

    /** The card and its remove control agree about which attachment they are talking about. */
    @Test public void thedrawnStripCarriesThoseDescriptions() {
        AttachmentStripView strip = strip(false);
        List<ComposerAttachment> mixed = new ArrayList<>(images("one.jpg", "two.jpg"));
        mixed.add(document("pdf", "syllabus.pdf"));
        bind(strip, mixed);

        assertEquals("Photo attachment 1 of 3", description(strip, 0));
        assertEquals("Photo attachment 2 of 3", description(strip, 1));
        assertEquals("PDF attachment 3 of 3, syllabus.pdf", description(strip, 2));
        assertEquals("Remove photo attachment 2", removeDescription(strip, 1));
        assertEquals("Remove PDF attachment 3, syllabus.pdf", removeDescription(strip, 2));
    }

    // ---- the rule about where the strip looks --------------------------------------------------------

    /** The first batch is read from the beginning, which is where Photo 1 is. */
    @Test public void afirstBatchPlansToStayAtTheStart() {
        AttachmentStripView.ScrollPlan plan =
                AttachmentStripView.planScroll(Collections.emptyList(), Arrays.asList("A", "B", "C"));
        assertEquals(AttachmentStripView.ScrollPlan.START, plan.mode);
    }

    /** Appending reveals the first item that was not there before, not the absolute last card. */
    @Test public void anAppendedBatchPlansToRevealTheFirstNewItem() {
        AttachmentStripView.ScrollPlan plan = AttachmentStripView.planScroll(
                Arrays.asList("A", "B", "C"), Arrays.asList("A", "B", "C", "D", "E"));
        assertEquals(AttachmentStripView.ScrollPlan.REVEAL, plan.mode);
        assertEquals("D is what the user just added; E is beside it", 3, plan.revealIndex);
    }

    /** A removal leaves the user reading where they were reading. */
    @Test public void aremovalPlansToKeepThePosition() {
        AttachmentStripView.ScrollPlan plan = AttachmentStripView.planScroll(
                Arrays.asList("A", "B", "C", "D"), Arrays.asList("A", "B", "D"));
        assertEquals(AttachmentStripView.ScrollPlan.KEEP, plan.mode);
    }

    /** A retheme, a font change, or a lifecycle rebuild is not a reason to move anything. */
    @Test public void anIdenticalRebindPlansToKeepThePosition() {
        AttachmentStripView.ScrollPlan plan = AttachmentStripView.planScroll(
                Arrays.asList("A", "B", "C"), Arrays.asList("A", "B", "C"));
        assertEquals(AttachmentStripView.ScrollPlan.KEEP, plan.mode);
    }

    /** Growth that is not an append is not an append, and is not guessed at. */
    @Test public void areorderIsNotTreatedAsAnAppend() {
        AttachmentStripView.ScrollPlan plan = AttachmentStripView.planScroll(
                Arrays.asList("A", "B"), Arrays.asList("B", "A", "C"));
        assertEquals(AttachmentStripView.ScrollPlan.KEEP, plan.mode);
    }

    // ---- and where it actually ends up ----------------------------------------------------------------

    /**
     * The reported bug, as the device showed it.
     *
     * <p>Empty strip, attach three photos, and the strip must be showing Photo 1. Before this it
     * posted {@code fullScroll(FOCUS_RIGHT)} unconditionally and landed on Photo 3.
     */
    @Test public void thefirstBatchLeavesTheStripAtTheStart() {
        AttachmentStripView strip = strip(false);
        bind(strip, images("one.jpg", "two.jpg", "three.jpg", "four.jpg", "five.jpg"));

        assertEquals("a first batch must not jump to the end", 0, strip.getScrollX());
        assertTrue("there is genuinely somewhere to have jumped to", maxScroll(strip) > 0);
    }

    /**
     * The spec case: three photos, then two more.
     *
     * <p>What has to be true is that both of the photos the user just added are on screen. Photo 4
     * is the one they are shown and Photo 5 sits beside it - which is the difference between this
     * and the old behaviour, where the strip went to the absolute end and Photo 4 was pushed off
     * the left edge.
     */
    @Test public void anAppendedBatchShowsBothNewlyAddedPhotos() throws Exception {
        disableAnimations();
        AttachmentStripView strip = strip(false);
        List<ComposerAttachment> first = images("one.jpg", "two.jpg", "three.jpg");
        bind(strip, first);
        assertEquals(0, strip.getScrollX());

        List<ComposerAttachment> appended = new ArrayList<>(first);
        appended.addAll(images("four.jpg", "five.jpg"));
        bind(strip, appended);

        assertTrue("the strip must have moved to show the new photos", strip.getScrollX() > 0);
        assertTrue("Photo 4 is the one the user is shown", isFullyVisible(strip, 3));
        assertTrue("and Photo 5 is beside it", isFullyVisible(strip, 4));
    }

    /**
     * With enough content to scroll past, the new item lands at the leading edge rather than last.
     *
     * <p>The short case above cannot distinguish "revealed Photo 4" from "ran out of strip", so
     * this one gives the strip somewhere further to go and pins the actual destination.
     */
    @Test public void anAppendedBatchLandsOnTheFirstNewCardAndNotTheLast() throws Exception {
        disableAnimations();
        AttachmentStripView strip = strip(false);
        List<ComposerAttachment> first = images("1.jpg", "2.jpg", "3.jpg");
        bind(strip, first);

        List<ComposerAttachment> appended = new ArrayList<>(first);
        appended.addAll(images("4.jpg", "5.jpg", "6.jpg", "7.jpg", "8.jpg", "9.jpg"));
        bind(strip, appended);

        int fourthLeft = card(strip, 3).getLeft();
        assertTrue("the first newly added card sits at the leading edge",
                Math.abs(strip.getScrollX() - fourthLeft) <= UiKit.dp(context, 8));
        assertNotEquals("and not at the absolute end, which would hide Photo 4",
                maxScroll(strip), strip.getScrollX());
    }

    /** Removing one card does not throw the user to either end of the strip. */
    @Test public void aremovalKeepsTheUserRoughlyWhereTheyWere() {
        AttachmentStripView strip = strip(false);
        List<ComposerAttachment> six = images("1.jpg", "2.jpg", "3.jpg", "4.jpg", "5.jpg", "6.jpg");
        bind(strip, six);

        int middle = maxScroll(strip) / 2;
        strip.scrollTo(middle, 0);
        assertEquals(middle, strip.getScrollX());

        List<ComposerAttachment> afterRemoval = new ArrayList<>(six);
        afterRemoval.remove(2);
        bind(strip, afterRemoval);

        assertEquals("the position is preserved, not reset and not sent to the end",
                middle, strip.getScrollX());
    }

    /** If the strip got shorter than where the user was, the position is clamped, not lost. */
    @Test public void aremovalPastTheEndIsClampedRatherThanLeftInEmptySpace() {
        AttachmentStripView strip = strip(false);
        List<ComposerAttachment> six = images("1.jpg", "2.jpg", "3.jpg", "4.jpg", "5.jpg", "6.jpg");
        bind(strip, six);
        strip.scrollTo(maxScroll(strip), 0);

        bind(strip, images("1.jpg", "2.jpg", "3.jpg"));

        assertTrue("never past the new content", strip.getScrollX() <= maxScroll(strip));
        assertEquals(maxScroll(strip), strip.getScrollX());
    }

    /** A redraw that changes nothing must not move anything. */
    @Test public void arethemeStyleRebindDoesNotMoveTheStrip() {
        AttachmentStripView strip = strip(false);
        List<ComposerAttachment> six = images("1.jpg", "2.jpg", "3.jpg", "4.jpg", "5.jpg", "6.jpg");
        bind(strip, six);

        int where = maxScroll(strip) / 3;
        strip.scrollTo(where, 0);
        bind(strip, six);

        assertEquals("a theme, font, or lifecycle rebuild is not a scroll gesture",
                where, strip.getScrollX());
    }

    /** Clearing the composer and starting again begins at the beginning. */
    @Test public void clearingAndAttachingAgainStartsAtTheBeginning() {
        AttachmentStripView strip = strip(false);
        bind(strip, images("1.jpg", "2.jpg", "3.jpg", "4.jpg", "5.jpg"));
        strip.scrollTo(maxScroll(strip), 0);

        bind(strip, Collections.emptyList());
        assertEquals(View.GONE, strip.getVisibility());

        bind(strip, images("a.jpg", "b.jpg", "c.jpg", "d.jpg", "e.jpg"));
        assertEquals(View.VISIBLE, strip.getVisibility());
        assertEquals(0, strip.getScrollX());
    }

    /** The overlay's tighter strip follows exactly the same rules. */
    @Test public void theOverlayStripBehavesIdentically() {
        AttachmentStripView overlay = strip(true);
        bind(overlay, images("1.jpg", "2.jpg", "3.jpg", "4.jpg", "5.jpg"));
        assertEquals(0, overlay.getScrollX());
        assertEquals("Photo attachment 1 of 5", description(overlay, 0));
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private AttachmentStripView strip(boolean compact) {
        AttachmentStripView strip = new AttachmentStripView(controller.get(), compact);
        host.addView(strip);
        return strip;
    }

    /** Binds, lays the strip out, then runs the posted scroll exactly as the real strip does. */
    private void bind(AttachmentStripView strip, List<ComposerAttachment> attachments) {
        strip.bind(attachments);
        layout(strip);
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
        layout(strip);
    }

    private void layout(AttachmentStripView strip) {
        strip.measure(View.MeasureSpec.makeMeasureSpec(VIEWPORT, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(UiKit.dp(context, 64), View.MeasureSpec.EXACTLY));
        strip.layout(0, 0, VIEWPORT, UiKit.dp(context, 64));
    }

    private static LinearLayout row(AttachmentStripView strip) {
        return (LinearLayout) strip.getChildAt(0);
    }

    private static View card(AttachmentStripView strip, int index) {
        return row(strip).getChildAt(index);
    }

    /** True when the whole card is inside the viewport at the strip's current scroll position. */
    private static boolean isFullyVisible(AttachmentStripView strip, int index) {
        View card = card(strip, index);
        return card.getLeft() >= strip.getScrollX()
                && card.getRight() <= strip.getScrollX() + strip.getWidth();
    }

    private static int maxScroll(AttachmentStripView strip) {
        return Math.max(0, row(strip).getWidth() - strip.getWidth());
    }

    private static String description(AttachmentStripView strip, int index) {
        CharSequence value = card(strip, index).getContentDescription();
        return value == null ? "" : value.toString();
    }

    private static String removeDescription(AttachmentStripView strip, int index) {
        LinearLayout card = (LinearLayout) card(strip, index);
        CharSequence value = card.getChildAt(card.getChildCount() - 1).getContentDescription();
        return value == null ? "" : value.toString();
    }

    private static void disableAnimations() throws Exception {
        Method setScale =
                ShadowValueAnimator.class.getDeclaredMethod("setDurationScale", float.class);
        setScale.setAccessible(true);
        setScale.invoke(null, 0f);
        assertFalse(ValueAnimator.areAnimatorsEnabled());
    }

    private static Bitmap bitmap() {
        return Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
    }

    private static ComposerAttachment image(String label) {
        return new ComposerAttachment("image", label, "", bitmap());
    }

    private static List<ComposerAttachment> images(String... labels) {
        List<ComposerAttachment> out = new ArrayList<>();
        for (String label : labels) out.add(image(label));
        return out;
    }

    private static ComposerAttachment document(String kind, String label) {
        return new ComposerAttachment(kind, label, "contents", null);
    }
}
