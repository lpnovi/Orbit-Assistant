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

        // Future-only, and audited against release history each time it changes. Three entries left
        // in v0.7.4.2 because the work they described had already shipped: natural-language Routine
        // creation as Create with Orbit in the 0.7.3 series, automation history as Routine Run
        // history, and quick access as widgets, tiles and Custom Commands. A fourth was rewritten in
        // v0.7.5.0, which shipped IF / ELSE branching, so what is listed now is the branching work
        // that genuinely remains. A fifth left in v0.7.6.0: conversation-aware voice follow-ups
        // shipped as Smart follow-ups. Shipped work belongs in What's New, never here.
        //
        // Two more entries left in v0.7.7.0, which shipped provider choice and the first Orbit
        // Local release: "Choice of AI provider" and the old planned "Orbit Local" wording are now
        // What's New material. What remains here is the genuinely unfinished part of that line —
        // finishing OpenRouter chat, teaching the local model Orbit's device actions, and the
        // temporarily withdrawn Edit & resend action, which returns once resending is dependable.
        //
        // The cooking entries added in v0.7.7.3 are future-only by construction. That release
        // shipped the deterministic kitchen maths - conversions, fractions, scaling, better timer
        // labels - and none of it is described here. What is listed is the part that does not
        // exist yet: the cooking session itself, its hands-free vocabulary, and the optional
        // Orbit-owned timers that Android's Clock app is deliberately still the default for.
        addGroup(page, "NEXT UP", new String[][]{
                {"Modular Orbit Local", "A more flexible on-device setup, and the first Orbit feature you'll be able to try early on the Beta channel."},
                {"Local device actions", "Orbit Local asking the same trusted Orbit actions the cloud providers use, starting with simple reversible controls."},
                {"More branch points & conditions", "Several decision points in one Routine, and conditions beyond time and place."},
                {"Cook with Orbit", "A cooking session you start on purpose and end when you're done, following a recipe with you step by step."}
        });
        addGroup(page, "PLANNED", new String[][]{
                {"Kitchen hands-free", "Short spoken commands while cooking, on the Voice you already use: next, back, repeat, how much."},
                {"Recipe intelligence", "Reading a whole recipe, scaling all of it at once, suggesting substitutions and a sensible order of work."},
                {"More local models", "A choice of on-device models sized to different phones and needs."},
                {"Edit & resend, reliably", "The message action returns once editing and resending an earlier message is dependable."},
                {"Deeper Android actions", "Broader device controls through supported Android surfaces."},
                {"Stronger Custom Commands", "Personal phrases that accept variation and detail, beyond today's exact wording."},
                {"Orbit-managed timers", "An optional alternative to your Clock app for several named timers at once. Off by default; your Clock app stays."}
        });
        addGroup(page, "DEFERRED", new String[][]{
                {"OpenRouter chat", "On hold until there's an account to test it with properly. The secure setup already in Orbit stays exactly as it is."}
        });
        addGroup(page, "EXPLORING", new String[][]{
                {"Hybrid Auto", "Orbit choosing on-device or cloud by itself, from the task, what is available, and your preference."},
                {"Proactive screen intelligence", "Helpful context-aware assistance that remains transparent and controllable."},
                {"Image retrieval integrations", "Purpose-built image discovery with safe sources and clear attribution."},
                {"Hands-busy help beyond cooking", "The same guided sessions for repairs, cleaning, assembly, and anything else done with full hands."},
                {"Food safety guidance", "Careful answers on cooking temperatures, storage and reheating, written to be trustworthy rather than alarming."}
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
