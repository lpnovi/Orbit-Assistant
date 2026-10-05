package com.orbit.assistant;

import android.animation.LayoutTransition;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Settings' picture of the Side-button overlay: the selected {@link OverlayStyle}, with the quick
 * controls {@link OverlayControls} places, at a size that fits a Settings card.
 *
 * <p>Drawing only. It reads the same style numbers and the same control arrangement the overlay
 * does, so the two cannot disagree about where a control lives, and it owns no conversation, no
 * request and no session. A style change crossfades into a newly drawn sheet while the frame
 * eases to its new height; a control change only flips visibilities, and a LayoutTransition lets
 * the neighbours slide into the space.
 */
final class OverlayPreviewView extends FrameLayout {
    /** The preview's scale against the real overlay. */
    private static final float S = 0.74f;
    static final String TAG_MODEL = "model";
    static final String TAG_HISTORY = "history";
    static final String TAG_NEW_CHAT = "new_chat";
    static final String TAG_MORE = "more";
    static final String TAG_SCREEN = "screen";
    static final String TAG_ATTACH = "attach";
    static final String TAG_VOICE = "voice";

    private OverlayStyle style;
    private View sheet;
    private ValueAnimator heightAnimator;

    OverlayPreviewView(Context c) {
        super(c);
        setPadding(px(14), px(26), px(14), 0);
        setBackground(UiKit.rounded(UiKit.blend(UiKit.SURFACE_2, Color.BLACK, 0.35f), 20, c));
        setClipToOutline(true);
        // One description for the whole picture; its pieces are drawings, not controls.
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    OverlayStyle style() { return style; }

    /** Shows {@code next}, crossfading from whatever was shown before. */
    void show(OverlayStyle next, OverlayControls controls) {
        if (next == style && sheet != null) {
            apply(controls);
            return;
        }
        Context c = getContext();
        View outgoing = sheet;
        style = next;
        sheet = buildSheet(c, next);
        apply(controls);
        setContentDescription("Preview of the " + next.label + " overlay");
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        int margin = px(next.marginDp);
        lp.setMargins(margin, 0, margin, margin);
        addView(sheet, lp);
        if (outgoing == null || !UiKit.animationsEnabled() || getHeight() == 0) {
            if (outgoing != null) removeView(outgoing);
            return;
        }
        // Old and new share the frame for one short beat: the old fades as the new rises in, and
        // the frame eases from the old height to the new one rather than jumping.
        int from = getHeight();
        measure(MeasureSpec.makeMeasureSpec(getWidth(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int to = getPaddingTop() + sheet.getMeasuredHeight() + margin;
        outgoing.animate().alpha(0f).setDuration(UiKit.MOTION_FAST)
                .withEndAction(() -> removeView(outgoing)).start();
        sheet.setAlpha(0f);
        sheet.setTranslationY(px(8));
        sheet.animate().alpha(1f).translationY(0f).setStartDelay(UiKit.MOTION_FAST / 2)
                .setDuration(UiKit.MOTION_STANDARD).setInterpolator(UiKit.motionEasing()).start();
        if (heightAnimator != null) heightAnimator.cancel();
        ViewGroup.LayoutParams own = getLayoutParams();
        if (own == null || from == to) return;
        heightAnimator = ValueAnimator.ofInt(from, to);
        heightAnimator.setDuration(UiKit.MOTION_ENTER);
        heightAnimator.setInterpolator(UiKit.motionEasing());
        heightAnimator.addUpdateListener(a -> {
            own.height = (int) a.getAnimatedValue();
            requestLayout();
        });
        heightAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                own.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                requestLayout();
            }
        });
        heightAnimator.start();
    }

    /** Shows each quick control where the overlay would, and More only when it holds something. */
    void apply(OverlayControls controls) {
        if (sheet == null || controls == null) return;
        visible(TAG_MODEL, controls.model);
        visible(TAG_HISTORY, controls.history);
        visible(TAG_NEW_CHAT, controls.newChat);
        visible(TAG_MORE, controls.hasOverflow());
        // The overlay hides the two buttons and keeps their row, so Mic and Send stay put.
        View screen = sheet.findViewWithTag(TAG_SCREEN);
        if (screen instanceof ViewGroup) {
            ViewGroup buttons = (ViewGroup) screen;
            for (int i = 0; i < buttons.getChildCount(); i++) {
                buttons.getChildAt(i).setVisibility(controls.screen ? VISIBLE : GONE);
            }
        }
        visible(TAG_ATTACH, controls.attach);
        visible(TAG_VOICE, controls.voice);
    }

    private void visible(String tag, boolean shown) {
        View v = sheet.findViewWithTag(tag);
        if (v != null) v.setVisibility(shown ? VISIBLE : GONE);
    }

    private View buildSheet(Context c, OverlayStyle s) {
        int accent = UiKit.accent(c);
        int soft = s.cute ? CutieTouches.pastel(c) : Color.rgb(74, 79, 92);
        LinearLayout sheet = new LinearLayout(c);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(px(s.sidePaddingDp), px(6), px(s.sidePaddingDp), px(s.sidePaddingDp));
        GradientDrawable bg = UiKit.gradientSheet(c, s.cornerDp * S);
        bg.setAlpha(s.sheetAlpha);
        sheet.setBackground(bg);
        sheet.setElevation(px(10));

        View handle = new View(c);
        handle.setBackground(UiKit.rounded(soft, 2, c));
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(px(42), px(4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = px(8);
        sheet.addView(handle, handleLp);

        LinearLayout header = row(c);
        View mark = UiKit.orbitMark(c, s.markDp * S);
        int markPx = px(s.markDp + (s.cute ? 10 : 4));
        if (s.cute) mark = CutieTouches.dressMark(c, mark, px(s.markDp + 4));
        LinearLayout.LayoutParams markLp = new LinearLayout.LayoutParams(markPx, markPx);
        markLp.rightMargin = px(8);
        header.addView(mark, markLp);
        LinearLayout titles = new LinearLayout(c);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(UiKit.text(c, UiKit.appTitle(c), s.titleSp * S, UiKit.TEXT, true));
        titles.addView(UiKit.text(c, "Ready", 11 * S, UiKit.MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView chip = UiKit.text(c, "Auto  ▾", 10.5f * S, accent, true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(px(10), 0, px(10), 0);
        chip.setBackground(UiKit.outlined(UiKit.blend(accent, UiKit.SURFACE_2, 0.08f),
                UiKit.withAlpha(accent, 92), s.controlRadiusDp * S, c));
        chip.setTag(TAG_MODEL);
        header.addView(chip, spaced(ViewGroup.LayoutParams.WRAP_CONTENT, px(32)));
        int icon = px(s.iconDp);
        header.addView(icon(c, R.drawable.ic_history, TAG_HISTORY), spaced(icon, icon));
        header.addView(icon(c, R.drawable.ic_add, TAG_NEW_CHAT), spaced(icon, icon));
        header.addView(icon(c, R.drawable.ic_more, TAG_MORE), spaced(icon, icon));
        header.addView(icon(c, R.drawable.ic_close, null), new LinearLayout.LayoutParams(icon, icon));
        sheet.addView(header);

        LinearLayout screen = row(c);
        screen.setTag(TAG_SCREEN);
        screen.addView(button(c, "Use screen", accent, s), spaced(ViewGroup.LayoutParams.WRAP_CONTENT, px(32)));
        screen.addView(button(c, "Select area", accent, s),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(32)));
        if (!s.integratedComposer) {
            // Classic's own screen bar, under the header.
            LinearLayout bar = row(c);
            bar.setPadding(px(10), px(6), px(8), px(6));
            bar.setMinimumHeight(px(52));
            bar.setBackground(UiKit.rounded(UiKit.SURFACE, 14 * S, c));
            bar.addView(UiKit.text(c, "Current screen available", 12 * S, UiKit.MUTED, false),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            bar.addView(screen);
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            barLp.setMargins(0, px(10), 0, px(7));
            sheet.addView(bar, barLp);
        }

        // A short exchange so the conversation's place reads; a fixed conversation keeps a
        // taller stage, as Classic's does.
        LinearLayout talk = new LinearLayout(c);
        talk.setOrientation(LinearLayout.VERTICAL);
        talk.setPadding(0, px(8), 0, px(8));
        TextView asked = UiKit.text(c, "What's on my screen?", 12 * S, UiKit.onAccent(c), false);
        asked.setPadding(px(10), px(6), px(10), px(6));
        asked.setBackground(UiKit.rounded(accent, 14 * S, c));
        LinearLayout.LayoutParams askedLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        askedLp.gravity = Gravity.END;
        talk.addView(asked, askedLp);
        for (float width : new float[]{0.86f, 0.58f}) {
            View line = new View(c);
            line.setBackground(UiKit.rounded(UiKit.withAlpha(UiKit.TEXT, 46), 4, c));
            LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(0, px(7));
            lineLp.topMargin = px(8);
            LinearLayout lineRow = new LinearLayout(c);
            lineRow.setWeightSum(1f);
            lineLp.weight = width;
            lineRow.addView(line, lineLp);
            talk.addView(lineRow);
        }
        if (!s.fitsContent()) talk.setMinimumHeight(px(s.conversationDp / 2));
        sheet.addView(talk);

        LinearLayout composer = new LinearLayout(c);
        composer.setOrientation(s.integratedComposer ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setPadding(px(6), px(s.integratedComposer ? 8 : 5), px(6), px(s.integratedComposer ? 6 : 5));
        composer.setBackground(UiKit.outlined(UiKit.SURFACE,
                s.cute ? UiKit.withAlpha(soft, 90) : Color.rgb(48, 53, 67), s.composerRadiusDp * S, c));
        TextView hint = UiKit.text(c, s.hint(), 15 * S, Color.rgb(117, 123, 139), false);
        hint.setSingleLine(true);
        hint.setPadding(px(10), px(6), px(8), px(6));
        int button = px(s.integratedComposer ? 40 : 44);
        View attach = round(c, R.drawable.ic_add, UiKit.SURFACE_2, UiKit.accent(c), button, TAG_ATTACH);
        View mic = round(c, R.drawable.ic_mic, Color.TRANSPARENT, accent, button, TAG_VOICE);
        View send = round(c, R.drawable.ic_send, accent, UiKit.onAccent(c), button, null);
        if (s.integratedComposer) {
            LinearLayout status = row(c);
            status.setPadding(px(10), 0, px(4), 0);
            status.addView(UiKit.text(c, "Current screen available", 11 * S, UiKit.MUTED, false));
            composer.addView(status);
            composer.addView(hint);
            LinearLayout controls = row(c);
            controls.addView(attach, new LinearLayout.LayoutParams(button, button));
            LinearLayout.LayoutParams screenLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            screenLp.setMargins(px(6), 0, px(4), 0);
            controls.addView(screen, screenLp);
            controls.addView(mic, new LinearLayout.LayoutParams(button, button));
            LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(button, button);
            sendLp.leftMargin = px(4);
            controls.addView(send, sendLp);
            composer.addView(controls, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            composer.setLayoutTransition(transition());
            composer.addView(attach, new LinearLayout.LayoutParams(button, button));
            composer.addView(hint, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            composer.addView(mic, new LinearLayout.LayoutParams(button, button));
            LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(button, button);
            sendLp.leftMargin = px(5);
            composer.addView(send, sendLp);
        }
        sheet.addView(composer);
        UiKit.applyTypography(sheet);
        return sheet;
    }

    private LinearLayout row(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutTransition(transition());
        return row;
    }

    /** Hidden controls fade, and their neighbours slide into the space rather than jumping. */
    private static LayoutTransition transition() {
        LayoutTransition t = new LayoutTransition();
        t.enableTransitionType(LayoutTransition.CHANGING);
        t.setDuration(UiKit.MOTION_STANDARD);
        t.setInterpolator(LayoutTransition.CHANGING, UiKit.motionEasing());
        return t;
    }

    private LinearLayout.LayoutParams spaced(int w, int h) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.rightMargin = px(6);
        return lp;
    }

    private View icon(Context c, int res, String tag) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setImageTintList(ColorStateList.valueOf(UiKit.MUTED));
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        v.setPadding(px(8), px(8), px(8), px(8));
        v.setTag(tag);
        return v;
    }

    private View round(Context c, int res, int fill, int tint, int size, String tag) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setImageTintList(ColorStateList.valueOf(tint));
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        v.setPadding(size / 4, size / 4, size / 4, size / 4);
        if (fill != Color.TRANSPARENT) {
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(fill);
            v.setBackground(dot);
        }
        v.setTag(tag);
        return v;
    }

    private TextView button(Context c, String label, int accent, OverlayStyle s) {
        TextView b = UiKit.text(c, label, 11 * S, accent, true);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setPadding(px(10), 0, px(10), 0);
        b.setBackground(UiKit.outlined(UiKit.SURFACE_2, UiKit.withAlpha(accent, 110),
                s.controlRadiusDp * S, c));
        return b;
    }

    private int px(float overlayDp) {
        return UiKit.dp(getContext(), overlayDp * S);
    }
}
