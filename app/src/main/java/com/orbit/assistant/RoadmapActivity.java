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
    /** Interactive Back for this page. Its classification lives in OrbitNavigation. */
    private OrbitPredictiveBack navigation;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.syncTheme(this);
        Window window = getWindow();
        window.setStatusBarColor(UiKit.BG);
        window.setNavigationBarColor(UiKit.BG);
        View content = buildContent();
        setContentView(content);
        UiKit.applyActivityInsets(this, content, true);
        navigation = OrbitPredictiveBack.install(this);
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
        OrbitBackground.applyPage(scroll);
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
        back.setOnClickListener(v -> navigation.performBack());
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
                "Orbit's roadmap is a direction, not a promise of dates or release numbers. It starts with what is being built now. Shipped features stay in What's New; this page remains future-only.",
                13, UiKit.MUTED, false);
        intro.setLineSpacing(0, 1.14f);
        LinearLayout.LayoutParams introLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        introLp.setMargins(UiKit.dp(this, 3), UiKit.dp(this, 18), UiKit.dp(this, 3), 0);
        page.addView(intro, introLp);

        // Future-only, and audited against release history each time it changes. Everything Orbit
        // has actually released belongs to What's New and to CHANGELOG.md, never here.
        //
        // Rebuilt in v0.7.8.4-beta.4. The page had drifted badly: it still opened with 0.7.7-era
        // priorities under NEXT UP while the whole project had moved on to Orbit Vault, so a user
        // reading it came away with a confident and wrong idea of what Orbit was working on. The
        // shape is now the same shape ROADMAP.md uses - what is being built and what is genuinely
        // further out - and the active milestone name is read from OrbitRoadmap so the two
        // documents cannot silently disagree about it again. Rich Answers, Settings search and
        // Astra left this page when v0.7.8.5 reached Stable: a shipped feature belongs to What's
        // New, never here.
        //
        // v0.8.0.0-beta.7 completed the planned Deck Pro expansion, so Smart Vault returns to NOW.
        // It remains a Free feature. The shipped Orbit Pro work belongs in What's New and the
        // repository history rather than on this future-only page.
        //
        // Older entries were not deleted. Local device actions, branching conditions, the cooking
        // session, Orbit-managed timers, OpenRouter chat and the rest are all real unfinished work
        // and are still listed; they have simply stopped being presented as the next thing to
        // happen, which they had not been for several releases.
        addGroup(page, "NOW - 0.8", new String[][]{
                {OrbitRoadmap.CURRENT, "Making saved things easier to find and understand, with "
                        + "optional semantic search, text read from images you saved, and richer "
                        + "retrieval of saved pages. Always explicit, never automatic, and always Free."}
        });
        addGroup(page, "LATER", new String[][]{
                {"Vault Pro organization", "Optional advanced organization built on top of the Free Vault."},
                {"Backup and export Pro", "Additional power-user backup and export controls without removing the Free backup path."},
                {"Play distribution and Billing", "A real entitlement provider only when Orbit is ready for store distribution."},
                {"Local device actions", "Growing what Orbit Local can act on by itself, beyond the first safe set of controls it understands today."},
                {"Calendar awareness", "Orbit reading your day back to you, and changing or removing events it added for you."},
                {"More branch points & conditions", "Several decision points in one Routine, and conditions beyond time and place."},
                {"Stronger Custom Commands", "Personal phrases that accept variation and detail, beyond today's exact wording."},
                {"Deeper Android actions", "Broader device controls through supported Android surfaces."},
                {"More local models", "A choice of on-device models sized to different phones and needs."},
                {"Edit & resend, reliably", "The message action returns once editing and resending an earlier message is dependable."},
                {"Cook with Orbit", "A cooking session you start on purpose and end when you're done, following a recipe with you step by step."},
                {"Kitchen hands-free", "Short spoken commands while cooking, on the Voice you already use: next, back, repeat, how much."},
                {"Recipe intelligence", "Reading a whole recipe, scaling all of it at once, suggesting substitutions and a sensible order of work."},
                {"Orbit-managed timers", "An optional alternative to your Clock app for several named timers at once. Off by default; your Clock app stays."}
        });
        addGroup(page, "DEFERRED", new String[][]{
                {"OpenRouter chat", "On hold until there's an account to test it with properly. The secure setup already in Orbit stays exactly as it is."}
        });
        addGroup(page, "EXPLORING", new String[][]{
                {"Hybrid Auto", "Orbit choosing on-device or cloud by itself, from the task, what is available, and your preference."},
                {"Proactive screen intelligence", "Helpful context-aware assistance that remains transparent and controllable."},
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
