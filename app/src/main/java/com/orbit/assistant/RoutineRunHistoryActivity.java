package com.orbit.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/** Recent executions of saved routines, for understanding and troubleshooting automations. */
public class RoutineRunHistoryActivity extends Activity {
    private LinearLayout historyList;
    private Button clearButton;

    /** Interactive Back for this page. Its classification lives in OrbitNavigation. */
    private OrbitPredictiveBack navigation;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.syncTheme(this);
        Window w = getWindow();
        w.setStatusBarColor(UiKit.BG);
        w.setNavigationBarColor(UiKit.BG);
        View content = buildContent();
        setContentView(content);
        UiKit.applyActivityInsets(this, content, true);
        navigation = OrbitPredictiveBack.install(this);
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(UiKit.BG);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 20);
        page.setPadding(p, UiKit.dp(this, 26), p, UiKit.dp(this, 48));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = iconButton(R.drawable.ic_back, "Back");
        back.setOnClickListener(v -> navigation.performBack());
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 48));
        backLp.rightMargin = UiKit.dp(this, 12);
        header.addView(back, backLp);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(UiKit.text(this, "Run history", 25, UiKit.TEXT, true));
        titles.addView(UiKit.text(this, "Routines", 12, UiKit.MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(header);

        TextView intro = UiKit.text(this,
                "The most recent routine runs, whether they finished, and where a run stopped. "
                        + "Orbit keeps the last " + RoutineRunHistoryStore.MAX_ENTRIES
                        + " runs on this phone and records nothing from a routine's settings or extension credentials.",
                14, UiKit.MUTED, false);
        intro.setLineSpacing(0, 1.13f);
        LinearLayout.LayoutParams introLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        introLp.setMargins(UiKit.dp(this, 2), UiKit.dp(this, 16), UiKit.dp(this, 2), UiKit.dp(this, 4));
        page.addView(intro, introLp);

        page.addView(sectionTitle("RECENT RUNS"));

        historyList = new LinearLayout(this);
        historyList.setOrientation(LinearLayout.VERTICAL);
        page.addView(historyList);

        clearButton = secondaryButton("Clear run history");
        clearButton.setTextColor(UiKit.DANGER);
        clearButton.setOnClickListener(v -> confirmClear());
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 44));
        clearLp.setMargins(0, UiKit.dp(this, 14), 0, 0);
        page.addView(clearButton, clearLp);

        return scroll;
    }

    private void refresh() {
        if (historyList == null) return;
        historyList.removeAllViews();
        List<RoutineRunHistoryStore.Entry> entries = RoutineRunHistoryStore.list(this);
        if (entries.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(UiKit.text(this, "No runs yet", 16, UiKit.TEXT, true));
            TextView note = UiKit.text(this,
                    "Run a routine from the Routines screen, a widget, a Quick Settings tile, or an "
                            + "automatic trigger and it will appear here.",
                    13, UiKit.MUTED, false);
            note.setPadding(0, UiKit.dp(this, 6), 0, 0);
            empty.addView(note);
            historyList.addView(empty, cardLp());
        } else {
            for (RoutineRunHistoryStore.Entry entry : entries) {
                historyList.addView(historyCard(entry), cardLp());
            }
        }
        if (clearButton != null) clearButton.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private View historyCard(RoutineRunHistoryStore.Entry entry) {
        LinearLayout card = card();
        int tone = entry.success ? UiKit.SUCCESS : UiKit.DANGER;

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = UiKit.text(this, entry.routineName, 16, UiKit.TEXT, true);
        name.setMaxLines(1);
        top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView badge = UiKit.text(this, entry.success ? "Succeeded" : "Failed", 11, tone, true);
        badge.setPadding(UiKit.dp(this, 9), UiKit.dp(this, 4), UiKit.dp(this, 9), UiKit.dp(this, 4));
        badge.setBackground(UiKit.outlined(UiKit.SURFACE_2, UiKit.withAlpha(tone, 110), 11, this));
        badge.setContentDescription(entry.success ? "Run succeeded" : "Run failed");
        top.addView(badge);
        card.addView(top);

        TextView headline = UiKit.text(this, entry.headline(), 13, tone, false);
        headline.setPadding(0, UiKit.dp(this, 8), 0, 0);
        card.addView(headline);

        if (!entry.success && !entry.reason.isEmpty()) {
            TextView reason = UiKit.text(this, entry.reason, 12, UiKit.MUTED, false);
            reason.setLineSpacing(0, 1.1f);
            reason.setPadding(0, UiKit.dp(this, 5), 0, 0);
            card.addView(reason);
        }

        String when = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(new Date(entry.runAt));
        TextView meta = UiKit.text(this, when + " · " + entry.sourceLabel(), 11, UiKit.MUTED, false);
        meta.setPadding(0, UiKit.dp(this, 7), 0, 0);
        card.addView(meta);
        return card;
    }

    private void confirmClear() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Clear run history?")
                .setMessage("This removes the recorded run history only. Your routines, pinned routines, "
                        + "automatic triggers, and routine actions are not changed.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (d, w) -> {
                    if (RoutineRunHistoryStore.clear(this)) {
                        refresh();
                        Toast.makeText(this, "Run history cleared", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Could not clear run history.", Toast.LENGTH_SHORT).show();
                    }
                }).create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(UiKit.dp(this, 17), UiKit.dp(this, 15), UiKit.dp(this, 17), UiKit.dp(this, 15));
        c.setBackground(UiKit.outlined(UiKit.SURFACE, UiKit.withAlpha(UiKit.accent(this), 40), 20, this));
        c.setElevation(UiKit.dp(this, 2));
        return c;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, UiKit.dp(this, 10));
        return lp;
    }

    private TextView sectionTitle(String text) {
        TextView t = UiKit.text(this, text, 12, UiKit.MUTED, true);
        t.setLetterSpacing(0.16f);
        t.setPadding(UiKit.dp(this, 5), UiKit.dp(this, 12), 0, UiKit.dp(this, 10));
        return t;
    }

    private ImageButton iconButton(int res, String description) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setImageTintList(ColorStateList.valueOf(UiKit.accent(this)));
        b.setBackground(UiKit.ripple(UiKit.SURFACE_2, UiKit.accent(this), 18, this));
        b.setContentDescription(description);
        b.setPadding(UiKit.dp(this, 11), UiKit.dp(this, 11), UiKit.dp(this, 11), UiKit.dp(this, 11));
        UiKit.pressScale(b);
        return b;
    }

    private Button secondaryButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(UiKit.TEXT);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), 70), UiKit.accent(this), 15, this));
        b.setMinHeight(0); b.setMinimumHeight(0); b.setStateListAnimator(null);
        UiKit.pressScale(b);
        return b;
    }
}
