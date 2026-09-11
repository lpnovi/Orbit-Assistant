package com.orbit.assistant;

import android.content.Context;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * A short list of choices, all of them visible, one of them taken.
 *
 * <p>Orbit needed this for the background mode - Solid, Linear or Glow - and for where a glow sits.
 * Both are choices between three things a person is going to flick between while they look at a
 * preview, which is the case a dropdown is worst at: a {@code Spinner} hides two of the three
 * answers behind a tap, opens a list whose typography and motion belong to the platform rather than
 * to this design system, and costs two gestures for every comparison. A row of three pills costs one
 * gesture, shows the whole question, and looks like the rest of Orbit.
 *
 * <h2>Built once, selected in place</h2>
 *
 * <p>{@link #setOptions} constructs the segments and is the only thing that ever creates a view here.
 * {@link #setSelected} recolours the ones that already exist. That is the same rule the Theme Studio
 * sliders and previews follow, and it is here for the same reason: a control that is rebuilt in order
 * to show a different value loses its touch state, its accessibility focus, and any chance of being
 * the thing the user is currently pressing.
 *
 * <p>{@link #setSelected} never calls the listener, so synchronizing this to a draft cannot loop back
 * into an edit of that draft. A tap does call it, exactly once.
 *
 * <h2>State is in words, not only in colour</h2>
 *
 * <p>Each segment is announced as a button carrying its own label and whether it is selected, so the
 * choice is available to somebody who cannot see which pill is filled. The accent fill is how it
 * reads at a glance; it is never the only place the answer is written down.
 */
public final class OrbitSegmented extends LinearLayout {

    /** Between two pills. Tight enough to read as one control rather than three buttons. */
    private static final int GAP_DP = 6;
    private static final float RADIUS_DP = 13f;

    public interface OnSelectListener {
        void onSegmentSelected(OrbitSegmented view, int index);
    }

    private final List<TextView> segments = new ArrayList<>();
    private String[] labels = new String[0];
    private String title = "";
    private int selected = -1;
    private OnSelectListener listener;

    public OrbitSegmented(Context c) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
    }

    /** What this control is choosing, for the announcement each segment carries. */
    public void setTitle(String value) {
        title = value == null ? "" : value;
        describeAll();
    }

    /**
     * The choices. Called once, when the control is built.
     *
     * <p>Calling it again with the same labels is a no-op rather than a rebuild, so a caller that
     * cannot easily tell whether it has already built this control does not destroy it by asking.
     */
    public void setOptions(String[] values) {
        String[] next = values == null ? new String[0] : values;
        if (java.util.Arrays.equals(labels, next)) return;
        labels = next.clone();
        removeAllViews();
        segments.clear();
        Context c = getContext();
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView pill = UiKit.text(c, labels[i], 13, UiKit.TEXT, false);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(UiKit.dp(c, 10), UiKit.dp(c, 9), UiKit.dp(c, 10), UiKit.dp(c, 10));
            pill.setMinimumHeight(UiKit.dp(c, 42));
            pill.setSingleLine(true);
            UiKit.pressScale(pill);
            pill.setOnClickListener(v -> {
                UiKit.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY);
                choose(index);
            });
            LayoutParams lp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = UiKit.dp(c, GAP_DP);
            addView(pill, lp);
            segments.add(pill);
        }
        paint();
    }

    public void setOnSelectListener(OnSelectListener value) {
        listener = value;
    }

    /** The choice this control is showing. Assignment only: the listener is never called from here. */
    public void setSelected(int index) {
        if (index == selected) return;
        selected = index;
        paint();
    }

    public int selectedIndex() {
        return selected;
    }

    /** One segment, for the tests and for anything that needs to drive this from code. */
    public View segmentAt(int index) {
        return index < 0 || index >= segments.size() ? null : segments.get(index);
    }

    private void choose(int index) {
        if (index == selected) return;
        selected = index;
        paint();
        if (listener != null) listener.onSegmentSelected(this, index);
    }

    /**
     * The chosen pill carries the accent; the rest are quiet surfaces with a hairline.
     *
     * <p>Deliberately not a filled accent for every state and an outline for the rest. What is being
     * shown is which one of these is true, so the selected one is the only one that is allowed to be
     * loud, and the others have to stay obviously tappable rather than looking disabled.
     */
    private void paint() {
        Context c = getContext();
        int accent = UiKit.accent(c);
        for (int i = 0; i < segments.size(); i++) {
            TextView pill = segments.get(i);
            boolean on = i == selected;
            pill.setBackground(on
                    ? UiKit.ripple(accent, UiKit.onAccent(accent), RADIUS_DP, c)
                    : UiKit.rippleOutlined(UiKit.SURFACE_3, UiKit.withAlpha(accent, 76),
                            accent, RADIUS_DP, c));
            pill.setTextColor(on ? UiKit.onAccent(accent) : UiKit.TEXT);
            UiKit.setTextWeight(pill, on);
        }
        describeAll();
    }

    private void describeAll() {
        for (int i = 0; i < segments.size(); i++) {
            String label = i < labels.length ? labels[i] : "";
            String prefix = title.isEmpty() ? "" : title + ": ";
            segments.get(i).setContentDescription(
                    prefix + label + (i == selected ? ", selected" : ""));
        }
    }
}
