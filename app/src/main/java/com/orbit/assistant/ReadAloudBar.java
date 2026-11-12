package com.orbit.assistant;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Read Aloud's whole interface: one small pill that exists only while an answer is being read,
 * saying so and offering Pause/Resume and Stop. Gone, taking no room, the rest of the time.
 *
 * <p>Each surface adds one above its composer. It follows {@link ReadAloud} by itself, so a
 * surface never has to tell it anything.
 */
final class ReadAloudBar extends LinearLayout implements ReadAloud.Listener {
    static final String STOP_DESCRIPTION = "Stop reading aloud";
    private final TextView label;
    private final ImageButton pause;

    ReadAloudBar(Context c) {
        super(c);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(UiKit.dp(c, 12), 0, UiKit.dp(c, 2), 0);
        setBackground(UiKit.outlined(UiKit.SURFACE, UiKit.withAlpha(UiKit.MUTED, 44), 20, c));
        setElevation(UiKit.dp(c, 3));

        ImageView icon = new ImageView(c);
        icon.setImageResource(R.drawable.ic_read_aloud);
        icon.setImageTintList(ColorStateList.valueOf(UiKit.accent(c)));
        addView(icon, new LayoutParams(UiKit.dp(c, 18), UiKit.dp(c, 18)));

        label = UiKit.text(c, "", 13, UiKit.TEXT, false);
        label.setSingleLine(true);
        label.setPadding(UiKit.dp(c, 8), 0, UiKit.dp(c, 6), 0);
        label.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        addView(label);

        pause = button(c, R.drawable.ic_pause);
        pause.setOnClickListener(v -> {
            if (ReadAloud.state() == ReadAloud.PAUSED) ReadAloud.resume(); else ReadAloud.pause();
        });
        addView(pause);
        ImageButton stop = button(c, R.drawable.ic_stop);
        stop.setContentDescription(STOP_DESCRIPTION);
        stop.setOnClickListener(v -> ReadAloud.stop());
        addView(stop);

        setVisibility(GONE);
    }

    private static ImageButton button(Context c, int icon) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(UiKit.accent(c)));
        b.setBackground(UiKit.ripple(Color.TRANSPARENT, UiKit.accent(c), 20, c));
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = UiKit.dp(c, 11);
        b.setPadding(pad, pad, pad, pad);
        b.setLayoutParams(new LayoutParams(UiKit.dp(c, 44), UiKit.dp(c, 44)));
        UiKit.pressScale(b);
        return b;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ReadAloud.addListener(this);
        onReadAloudChanged();
    }

    @Override protected void onDetachedFromWindow() {
        ReadAloud.removeListener(this);
        super.onDetachedFromWindow();
    }

    @Override public void onReadAloudChanged() {
        int state = ReadAloud.state();
        if (state == ReadAloud.IDLE) {
            setVisibility(GONE);
            return;
        }
        boolean paused = state == ReadAloud.PAUSED;
        label.setText(paused ? "Paused" : state == ReadAloud.STARTING ? "Starting…" : "Reading aloud");
        pause.setImageResource(paused ? R.drawable.ic_play : R.drawable.ic_pause);
        pause.setContentDescription(paused ? "Resume reading aloud" : "Pause reading aloud");
        if (getVisibility() != VISIBLE) {
            setVisibility(VISIBLE);
            UiKit.enterContent(this);
        }
    }
}
