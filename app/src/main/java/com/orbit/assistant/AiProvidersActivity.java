package com.orbit.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * AI Providers: choose and manage the backends Orbit can think with.
 *
 * <p>Each card is built for scanning, top to bottom: name, a status dot that always agrees with
 * the actions below it, one short sentence, capability chips, and only the actions the current
 * state genuinely supports. A provider that cannot answer right now never shows an enabled
 * "Use this provider"; its single action is the step that would make it usable. Selection is
 * explicit; Orbit never switches providers by itself.
 */
public final class AiProvidersActivity extends Activity {
    static final String ACTION_USE = "Use this provider";
    static final String ACTION_MANAGE = "Manage";
    static final String ACTION_SET_UP = "Set up";
    static final String ACTION_DETAILS = "Details";

    private LinearLayout cardsContainer;
    private String appearanceSignature;

    /** Interactive Back for this page. Its classification lives in OrbitNavigation. */
    private OrbitPredictiveBack navigation;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.syncTheme(this);
        appearanceSignature = UiKit.appearanceSignature(this);
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
        if (!UiKit.appearanceSignature(this).equals(appearanceSignature)) {
            recreate();
            return;
        }
        refreshCards();
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
        back.setContentDescription("Back");
        back.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 10),
                UiKit.dp(this, 10), UiKit.dp(this, 10));
        back.setOnClickListener(v -> navigation.performBack());
        UiKit.pressScale(back);
        header.addView(back, new LinearLayout.LayoutParams(UiKit.dp(this, 44), UiKit.dp(this, 44)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(UiKit.dp(this, 13), 0, 0, 0);
        titles.addView(UiKit.text(this, "AI Providers", 24, UiKit.TEXT, true));
        titles.addView(UiKit.text(this, "Choose what Orbit thinks with", 13, UiKit.MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(header);

        TextView intro = UiKit.text(this,
                "Choose how Orbit thinks. Features and capabilities vary by provider.",
                13, UiKit.MUTED, false);
        intro.setLineSpacing(0, 1.14f);
        LinearLayout.LayoutParams introLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        introLp.setMargins(UiKit.dp(this, 3), UiKit.dp(this, 18), UiKit.dp(this, 3), UiKit.dp(this, 2));
        page.addView(intro, introLp);

        cardsContainer = new LinearLayout(this);
        cardsContainer.setOrientation(LinearLayout.VERTICAL);
        page.addView(cardsContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        refreshCards();

        UiKit.applyTypography(page);
        return scroll;
    }

    private void refreshCards() {
        if (cardsContainer == null) return;
        cardsContainer.removeAllViews();
        String activeId = AiProviders.active(this).id();
        for (AiProvider provider : AiProviders.all()) {
            cardsContainer.addView(providerCard(provider, provider.id().equals(activeId)),
                    cardLp());
        }
        UiKit.applyTypography(cardsContainer);
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, UiKit.dp(this, 11), 0, 0);
        return lp;
    }

    private View providerCard(AiProvider provider, boolean active) {
        AiProvider.Status status = provider.status(this);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(UiKit.dp(this, 17), UiKit.dp(this, 15), UiKit.dp(this, 17), UiKit.dp(this, 15));
        // The active provider reads at a glance: accent edge, a faint accent-warmed surface, and
        // the Active pill. Everything else keeps the quiet standard outline.
        int fill = active ? UiKit.blend(UiKit.SURFACE, UiKit.accent(this), 0.93f) : UiKit.SURFACE;
        int stroke = active ? UiKit.accent(this) : UiKit.withAlpha(UiKit.accent(this), 38);
        card.setBackground(UiKit.outlined(fill, stroke, 22, this));
        card.setElevation(UiKit.dp(this, 2));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(UiKit.text(this, provider.displayName(), 17, UiKit.TEXT, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (active) {
            titleRow.addView(pill("Active", UiKit.onAccent(this),
                    UiKit.rounded(UiKit.accent(this), 99, this)));
        } else if (status == AiProvider.Status.COMING_SOON) {
            titleRow.addView(pill("Experimental", UiKit.MUTED,
                    UiKit.outlined(UiKit.SURFACE_2, Color.rgb(53, 58, 72), 99, this)));
        }
        card.addView(titleRow);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, UiKit.dp(this, 7), 0, 0);
        View dot = new View(this);
        dot.setBackground(UiKit.rounded(statusColor(status), 99, this));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                UiKit.dp(this, 8), UiKit.dp(this, 8));
        dotLp.rightMargin = UiKit.dp(this, 7);
        statusRow.addView(dot, dotLp);
        statusRow.addView(UiKit.text(this, provider.statusDetail(this), 13, UiKit.TEXT, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(statusRow);

        TextView description = UiKit.text(this, provider.description(), 12.5f, UiKit.MUTED, false);
        description.setLineSpacing(0, 1.15f);
        description.setPadding(0, UiKit.dp(this, 8), 0, 0);
        card.addView(description);

        ChipFlow chips = new ChipFlow(this, UiKit.dp(this, 6), UiKit.dp(this, 6));
        for (String label : capabilityChips(provider.capabilities())) {
            chips.addView(chip(label));
        }
        LinearLayout.LayoutParams chipsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipsLp.topMargin = UiKit.dp(this, 10);
        card.addView(chips, chipsLp);

        String limitation = capabilityLimitation(provider.capabilities());
        if (!limitation.isEmpty()) {
            TextView limits = UiKit.text(this, limitation, 11.5f,
                    UiKit.withAlpha(UiKit.MUTED, 210), false);
            limits.setPadding(0, UiKit.dp(this, 7), 0, 0);
            card.addView(limits);
        }

        addActions(card, provider, status, active);
        return card;
    }

    /**
     * Which actions a card offers, purely from state. READY earns "Use this provider" (plus
     * Manage); anything not ready offers only the step that would make it usable, so status and
     * actions can never contradict each other.
     */
    static List<String> actionLabels(AiProvider.Status status, boolean active) {
        List<String> out = new ArrayList<>();
        switch (status) {
            case READY:
                if (!active) out.add(ACTION_USE);
                out.add(ACTION_MANAGE);
                break;
            case NEEDS_SETUP:
            case NOT_INSTALLED:
            case COMING_SOON:
                out.add(ACTION_SET_UP);
                break;
            default: // UNSUPPORTED: nothing to enable, but the explanation stays reachable.
                out.add(ACTION_DETAILS);
        }
        return out;
    }

    private void addActions(LinearLayout card, AiProvider provider, AiProvider.Status status,
                            boolean active) {
        List<String> labels = actionLabels(status, active);
        boolean first = true;
        for (String label : labels) {
            boolean primary = ACTION_USE.equals(label)
                    || (ACTION_SET_UP.equals(label) && status != AiProvider.Status.COMING_SOON);
            View action;
            LinearLayout.LayoutParams lp;
            if (primary) {
                // The one state-progressing action is full width, in Orbit's stacked settings
                // button language; nothing is ever squeezed beside it.
                Button b = primaryButton(label);
                b.setOnClickListener(v -> runAction(provider, label));
                action = b;
                lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 46));
            } else {
                // Secondary actions stay compact and right-aligned so cards without a primary
                // action do not gain a wall of full-width buttons.
                Button b = secondaryButton(label);
                b.setOnClickListener(v -> runAction(provider, label));
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.END);
                row.addView(b, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 40)));
                action = row;
                lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            lp.topMargin = UiKit.dp(this, first ? 13 : 8);
            first = false;
            card.addView(action, lp);
        }
    }

    private void runAction(AiProvider provider, String label) {
        if (ACTION_USE.equals(label)) {
            if (AiProviders.select(this, provider.id())) {
                Toast.makeText(this, provider.displayName() + " is now Orbit's active provider",
                        Toast.LENGTH_SHORT).show();
                refreshCards();
            }
            return;
        }
        manage(provider);
    }

    private TextView pill(String text, int textColor, android.graphics.drawable.Drawable background) {
        TextView pill = UiKit.text(this, text, 11, textColor, true);
        pill.setBackground(background);
        pill.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 4), UiKit.dp(this, 10), UiKit.dp(this, 4));
        return pill;
    }

    private TextView chip(String label) {
        TextView chip = UiKit.text(this, label, 11, UiKit.withAlpha(UiKit.TEXT, 205), false);
        chip.setBackground(UiKit.outlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), 46), 99, this));
        chip.setPadding(UiKit.dp(this, 9), UiKit.dp(this, 4), UiKit.dp(this, 9), UiKit.dp(this, 4));
        chip.setSingleLine(true);
        return chip;
    }

    private int statusColor(AiProvider.Status status) {
        switch (status) {
            case READY: return UiKit.SUCCESS;
            case COMING_SOON: return UiKit.MUTED;
            case UNSUPPORTED: return UiKit.DANGER;
            default: return Color.rgb(240, 193, 100);
        }
    }

    /** Compact positive capabilities, in scanning order. */
    static List<String> capabilityChips(AiCapabilities caps) {
        List<String> chips = new ArrayList<>();
        if (caps.streaming) chips.add("Live replies");
        if (caps.deviceActions) chips.add("Device actions");
        if (caps.images) chips.add("Screens & images");
        if (caps.hostedWebSearch) chips.add("Web search");
        if (caps.offline) chips.add("Works offline");
        if (!caps.needsCredentials) chips.add("No account");
        return chips;
    }

    /** One honest sentence about what the provider cannot do yet, or empty. */
    static String capabilityLimitation(AiCapabilities caps) {
        if (caps.deviceActions) return "";
        return caps.images
                ? "Can't run device actions yet"
                : "Can't run device actions or read screens and images yet";
    }

    private void manage(AiProvider provider) {
        String id = provider.id();
        if (Prefs.PROVIDER_LOCAL.equals(id)) {
            startActivity(new Intent(this, LocalAiActivity.class));
            return;
        }
        if (Prefs.PROVIDER_OPENROUTER.equals(id)) {
            showOpenRouterSetup();
            return;
        }
        // ChatGPT sign-in and relay configuration keep living in Settings > AI & account, which
        // already owns those flows.
        Intent intent = new Intent(this, SettingsActivity.class);
        intent.putExtra(SettingsActivity.EXTRA_SECTION, SettingsActivity.SECTION_AI);
        startActivity(intent);
    }

    private void showOpenRouterSetup() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dp(this, 22);
        wrap.setPadding(pad, UiKit.dp(this, 8), pad, 0);
        TextView note = UiKit.text(this,
                "OpenRouter chat arrives in an upcoming Orbit update. You can already save your API key; it is stored encrypted on this device, never backed up, and never shown again.",
                13, UiKit.MUTED, false);
        note.setLineSpacing(0, 1.14f);
        wrap.addView(note);

        EditText input = new EditText(this);
        input.setHint(SecureStore.hasOpenRouterKey(this) ? "Key saved · enter a new key to replace it" : "sk-or-…");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setTextColor(UiKit.TEXT);
        input.setHintTextColor(UiKit.MUTED);
        input.setBackgroundTintList(ColorStateList.valueOf(UiKit.accent(this)));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputLp.topMargin = UiKit.dp(this, 10);
        wrap.addView(input, inputLp);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("OpenRouter setup")
                .setView(wrap)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save key", (d, w) -> {
                    String value = input.getText().toString().trim();
                    if (value.isEmpty()) return;
                    if (SecureStore.saveOpenRouterKey(this, value)) {
                        Toast.makeText(this, "OpenRouter key saved securely", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Could not store the key securely, so it was not saved",
                                Toast.LENGTH_LONG).show();
                    }
                    refreshCards();
                });
        if (SecureStore.hasOpenRouterKey(this)) {
            builder.setNeutralButton("Remove key", (d, w) -> {
                SecureStore.clearOpenRouterKey(this);
                Toast.makeText(this, "OpenRouter key removed", Toast.LENGTH_SHORT).show();
                refreshCards();
            });
        }
        AlertDialog dialog = builder.create();
        UiKit.styleOrbitDialog(dialog, this, false);
        dialog.show();
    }

    private Button primaryButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(UiKit.onAccent(this));
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setBackground(UiKit.ripple(UiKit.accent(this), UiKit.onAccent(this), 15, this));
        b.setMinHeight(0); b.setMinimumHeight(0); b.setStateListAnimator(null);
        UiKit.pressScale(b);
        return b;
    }

    private Button secondaryButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(UiKit.TEXT);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setPadding(UiKit.dp(this, 18), 0, UiKit.dp(this, 18), 0);
        b.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2, Color.rgb(53, 58, 72), UiKit.accent(this), 15, this));
        b.setMinHeight(0); b.setMinimumHeight(0); b.setStateListAnimator(null);
        UiKit.pressScale(b);
        return b;
    }

    /**
     * A minimal wrapping row for capability chips: children flow left to right and wrap to new
     * lines when the card runs out of width, so no chip is ever clipped or squeezed at any
     * screen width or font scale.
     */
    private static final class ChipFlow extends ViewGroup {
        private final int hGap;
        private final int vGap;

        ChipFlow(Context context, int hGapPx, int vGapPx) {
            super(context);
            this.hGap = hGapPx;
            this.vGap = vGapPx;
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int x = 0, y = 0, rowHeight = 0;
            boolean any = false;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                any = true;
                child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                int cw = child.getMeasuredWidth();
                int ch = child.getMeasuredHeight();
                if (x > 0 && x + cw > width) {
                    x = 0;
                    y += rowHeight + vGap;
                    rowHeight = 0;
                }
                x += cw + hGap;
                rowHeight = Math.max(rowHeight, ch);
            }
            setMeasuredDimension(width, any ? y + rowHeight : 0);
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = r - l;
            int x = 0, y = 0, rowHeight = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                int cw = child.getMeasuredWidth();
                int ch = child.getMeasuredHeight();
                if (x > 0 && x + cw > width) {
                    x = 0;
                    y += rowHeight + vGap;
                    rowHeight = 0;
                }
                child.layout(x, y, x + cw, y + ch);
                x += cw + hGap;
                rowHeight = Math.max(rowHeight, ch);
            }
        }
    }
}
