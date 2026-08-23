package com.orbit.assistant;

import android.app.Activity;
import android.app.AlertDialog;
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

/**
 * AI Providers: choose and manage the backends Orbit can think with.
 *
 * <p>One card per provider, in recommendation order. Each card answers the questions a normal
 * user actually has — is this the active one, is it ready, what can it do, what can it not do —
 * in product language, with a single Manage action for anything deeper. Selection is explicit;
 * Orbit never switches providers by itself.
 */
public final class AiProvidersActivity extends Activity {
    private LinearLayout cardsContainer;
    private String appearanceSignature;

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
        back.setOnClickListener(v -> finish());
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
                "Orbit works the same whichever provider answers. Pick one as active; the others stay set up and ready to switch to.",
                13, UiKit.MUTED, false);
        intro.setLineSpacing(0, 1.14f);
        LinearLayout.LayoutParams introLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        introLp.setMargins(UiKit.dp(this, 3), UiKit.dp(this, 18), UiKit.dp(this, 3), UiKit.dp(this, 4));
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
        lp.setMargins(0, UiKit.dp(this, 10), 0, 0);
        return lp;
    }

    private View providerCard(AiProvider provider, boolean active) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(UiKit.dp(this, 17), UiKit.dp(this, 15), UiKit.dp(this, 17), UiKit.dp(this, 15));
        int stroke = active ? UiKit.accent(this) : UiKit.withAlpha(UiKit.accent(this), 38);
        card.setBackground(UiKit.outlined(UiKit.SURFACE, stroke, 22, this));
        card.setElevation(UiKit.dp(this, 2));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(UiKit.text(this, provider.displayName(), 17, UiKit.TEXT, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (active) {
            TextView pill = UiKit.text(this, "Active", 11, UiKit.onAccent(this), true);
            pill.setBackground(UiKit.rounded(UiKit.accent(this), 99, this));
            pill.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 4), UiKit.dp(this, 10), UiKit.dp(this, 4));
            titleRow.addView(pill);
        } else if (provider.status(this) == AiProvider.Status.COMING_SOON) {
            TextView pill = UiKit.text(this, "Experimental", 11, UiKit.MUTED, true);
            pill.setBackground(UiKit.outlined(UiKit.SURFACE_2, Color.rgb(53, 58, 72), 99, this));
            pill.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 4), UiKit.dp(this, 10), UiKit.dp(this, 4));
            titleRow.addView(pill);
        }
        card.addView(titleRow);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, UiKit.dp(this, 6), 0, 0);
        View dot = new View(this);
        dot.setBackground(UiKit.rounded(statusColor(provider), 99, this));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                UiKit.dp(this, 8), UiKit.dp(this, 8));
        dotLp.rightMargin = UiKit.dp(this, 7);
        statusRow.addView(dot, dotLp);
        statusRow.addView(UiKit.text(this, provider.statusDetail(this), 13, UiKit.MUTED, false));
        card.addView(statusRow);

        TextView description = UiKit.text(this, provider.description(), 13, UiKit.MUTED, false);
        description.setLineSpacing(0, 1.13f);
        description.setPadding(0, UiKit.dp(this, 8), 0, 0);
        card.addView(description);

        TextView caps = UiKit.text(this, capabilitySummary(provider.capabilities()), 12,
                UiKit.withAlpha(UiKit.TEXT, 195), false);
        caps.setPadding(0, UiKit.dp(this, 7), 0, 0);
        card.addView(caps);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, UiKit.dp(this, 12), 0, 0);

        Button manage = secondaryButton(manageLabel(provider));
        manage.setOnClickListener(v -> manage(provider));
        actions.addView(manage, actionLp(false));

        if (!active && provider.selectable(this)) {
            Button use = primaryButton("Use this provider");
            use.setOnClickListener(v -> {
                if (AiProviders.select(this, provider.id())) {
                    Toast.makeText(this, provider.displayName() + " is now Orbit's active provider",
                            Toast.LENGTH_SHORT).show();
                    refreshCards();
                }
            });
            actions.addView(use, actionLp(true));
        }
        card.addView(actions);
        return card;
    }

    private LinearLayout.LayoutParams actionLp(boolean spaced) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (spaced) lp.leftMargin = UiKit.dp(this, 9);
        return lp;
    }

    private int statusColor(AiProvider provider) {
        switch (provider.status(this)) {
            case READY: return UiKit.SUCCESS;
            case COMING_SOON: return UiKit.MUTED;
            case UNSUPPORTED: return UiKit.DANGER;
            default: return Color.rgb(240, 193, 100);
        }
    }

    private String manageLabel(AiProvider provider) {
        if (Prefs.PROVIDER_LOCAL.equals(provider.id())) {
            return LocalModelStore.isReady(this) ? "Manage" : "Set up";
        }
        if (provider.status(this) == AiProvider.Status.NEEDS_SETUP) return "Set up";
        return "Manage";
    }

    /** Plain-language strengths and honest gaps, straight from the capability metadata. */
    static String capabilitySummary(AiCapabilities caps) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (caps.streaming) parts.add("Live replies");
        if (caps.deviceActions) parts.add("Device actions");
        if (caps.images) parts.add("Screens & images");
        if (caps.hostedWebSearch) parts.add("Web search");
        if (caps.offline) parts.add("Works offline");
        if (!caps.needsCredentials) parts.add("No account");
        StringBuilder line = new StringBuilder(String.join(" · ", parts));
        if (!caps.deviceActions) {
            line.append(line.length() > 0 ? "\n" : "").append("Can't run device actions yet");
            if (!caps.images) line.append(" or read screens and images");
        }
        return line.toString();
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
        b.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2, Color.rgb(53, 58, 72), UiKit.accent(this), 15, this));
        b.setMinHeight(0); b.setMinimumHeight(0); b.setStateListAnimator(null);
        UiKit.pressScale(b);
        return b;
    }
}
