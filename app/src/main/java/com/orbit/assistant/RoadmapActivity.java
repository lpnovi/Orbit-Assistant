package com.orbit.assistant;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Future-only product direction, kept separate from completed release history. */
public final class RoadmapActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.syncTheme(this);
        Window window = getWindow();
        window.setStatusBarColor(UiKit.BG);
        window.setNavigationBarColor(UiKit.BG);
        View content = buildContent();
        setContentView(content);
        UiKit.applyActivityInsets(this, content, true);
    }

    @Override protected void onResume() {
        super.onResume();
        UiPresence.enter(this);
    }

    @Override protected void onPause() {
        UiPresence.leave(this);
        super.onPause();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(UiKit.BG);
        scroll.setForceDarkAllowed(false);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int padding = UiKit.dp(this, 20);
        page.setPadding(padding, UiKit.dp(this, 30), padding, UiKit.dp(this, 48));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_back);
        back.setImageTintList(ColorStateList.valueOf(UiKit.accent(this)));
        back.setBackground(UiKit.ripple(UiKit.SURFACE_2, UiKit.accent(this), 18, this));
        back.setContentDescription("Back to About & updates");
        back.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 10),
                UiKit.dp(this, 10), UiKit.dp(this, 10));
        back.setOnClickListener(v -> finish());
        UiKit.pressScale(back);
        header.addView(back, new LinearLayout.LayoutParams(UiKit.dp(this, 44), UiKit.dp(this, 44)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(UiKit.dp(this, 13), 0, 0, 0);
        titles.addView(UiKit.text(this, "Roadmap", 24, UiKit.TEXT, true));
        titles.addView(UiKit.text(this, "What's next for Orbit", 13, UiKit.MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(header);

        TextView intro = UiKit.text(this,
                "Orbit's roadmap is a direction, not a promise of dates or release numbers. Shipped features stay in What's New; this page remains future-only.",
                13, UiKit.MUTED, false);
        intro.setLineSpacing(0, 1.14f);
        LinearLayout.LayoutParams introLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        introLp.setMargins(UiKit.dp(this, 3), UiKit.dp(this, 18), UiKit.dp(this, 3), 0);
        page.addView(intro, introLp);

        addGroup(page, "NEXT UP", new String[][]{
                {"Natural-language Routine creation", "Create editable Routines by describing the outcome in ordinary language."},
                {"Richer automation & branching", "More expressive chained plans, conditions and saved automation."}
        });
        addGroup(page, "PLANNED", new String[][]{
                {"Deeper Android actions", "Broader device controls through supported Android surfaces."},
                {"Stronger Custom Commands", "More capable personal phrases built on Orbit's existing safety model."},
                {"Additional AI providers", "More provider choices while preserving explicit account and privacy controls."},
                {"Conversational Voice improvements", "A more natural, responsive voice experience across chat and overlay."},
                {"Automation history & timeline", "Clear visibility into what ran, when it ran and what happened."},
                {"Richer quick access", "More flexible entry points for common assistant and automation flows."}
        });
        addGroup(page, "EXPLORING", new String[][]{
                {"Proactive screen intelligence", "Helpful context-aware assistance that remains transparent and controllable."},
                {"Image retrieval integrations", "Purpose-built image discovery with safe sources and clear attribution."},
                {"Local & on-device intelligence", "More useful private processing that can happen directly on the device."}
        });

        UiKit.applyTypography(page);
        return scroll;
    }

    private void addGroup(LinearLayout page, String name, String[][] items) {
        TextView section = UiKit.text(this, name, 12, UiKit.MUTED, true);
        section.setLetterSpacing(0.13f);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sectionLp.setMargins(UiKit.dp(this, 4), UiKit.dp(this, 24), 0, UiKit.dp(this, 9));
        page.addView(section, sectionLp);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(UiKit.dp(this, 17), UiKit.dp(this, 10),
                UiKit.dp(this, 17), UiKit.dp(this, 10));
        card.setBackground(UiKit.outlined(UiKit.SURFACE,
                UiKit.withAlpha(UiKit.accent(this), 38), 22, this));
        card.setElevation(UiKit.dp(this, 2));
        for (int i = 0; i < items.length; i++) {
            card.addView(item(items[i][0], items[i][1], i > 0));
        }
        page.addView(card);
    }

    private View item(String title, String description, boolean separated) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.TOP);
        row.setPadding(0, separated ? UiKit.dp(this, 10) : UiKit.dp(this, 6),
                0, UiKit.dp(this, 8));

        View dot = new View(this);
        dot.setBackground(UiKit.rounded(UiKit.accent(this), 99, this));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                UiKit.dp(this, 7), UiKit.dp(this, 7));
        dotLp.setMargins(0, UiKit.dp(this, 7), UiKit.dp(this, 11), 0);
        row.addView(dot, dotLp);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(UiKit.text(this, title, 15, UiKit.TEXT, true));
        TextView detail = UiKit.text(this, description, 12, UiKit.MUTED, false);
        detail.setLineSpacing(0, 1.12f);
        detail.setPadding(0, UiKit.dp(this, 3), 0, 0);
        copy.addView(detail);
        row.addView(copy, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }
}
