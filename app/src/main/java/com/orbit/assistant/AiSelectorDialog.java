package com.orbit.assistant;

import android.app.AlertDialog;
import android.content.Context;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Orbit's one picker for provider, model and strength.
 *
 * <p>Used by the chat header, Settings' defaults and Retry with, so the three can never disagree
 * about what is legal: every choice goes through {@link AiSelections}, and the picker only shows
 * what that layer says the selected model accepts. There is no disabled "None" on GPT-6.1 Sol; it
 * simply is not offered, and moving from Luna at None to Sol lands visibly on Low.
 *
 * <p>The hierarchy is built once. Choosing a model or a strength updates selected states, chip
 * visibility and the confirm label in place; only a provider change, which swaps the whole model
 * list, rebuilds that one list.
 */
final class AiSelectorDialog {

    interface OnChosen {
        void onChosen(AiSelection selection);
    }

    /** Produces the confirm button's text for the selection currently on screen. */
    interface ConfirmLabel {
        String labelFor(AiSelection selection);
    }

    private final Context context;
    private final List<AiProvider> providers;
    private AiSelection selection;

    private LinearLayout providerRow;
    private final List<Button> providerChips = new ArrayList<>();
    private LinearLayout modelList;
    private final List<View> modelRows = new ArrayList<>();
    private final List<AiModelSpec> modelSpecs = new ArrayList<>();
    private TextView strengthHeading;
    private LinearLayout strengthRow;
    private TextView noStrengthNote;
    private final Map<AiStrength, Button> strengthChips = new EnumMap<>(AiStrength.class);
    private AlertDialog dialog;

    private AiSelectorDialog(Context context, AiSelection initial) {
        this.context = context;
        this.selection = AiSelections.resolve(initial);
        this.providers = AiSelections.pickableProviders(context);
    }

    /** Shows the picker. {@code chosen} runs once, with a validated selection, on confirm. */
    static AlertDialog show(Context context, String title, AiSelection initial,
                            ConfirmLabel confirmLabel, OnChosen chosen) {
        AiSelectorDialog picker = new AiSelectorDialog(context, initial);
        return picker.open(title, confirmLabel, chosen);
    }

    /** The view hierarchy alone, for tests that check what is offered. */
    static AiSelectorDialog forTest(Context context, AiSelection initial) {
        AiSelectorDialog picker = new AiSelectorDialog(context, initial);
        picker.build();
        return picker;
    }

    AiSelection current() { return selection; }

    private AlertDialog open(String title, ConfirmLabel confirmLabel, OnChosen chosen) {
        View content = build();
        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(title)
                .setView(content)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(confirmLabel.labelFor(selection),
                        (d, w) -> chosen.onChosen(AiSelections.resolve(selection)));
        AlertDialog shown = builder.create();
        dialog = shown;
        UiKit.styleOrbitDialog(shown, context, false);
        onChange = () -> {
            Button positive = shown.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positive != null) positive.setText(confirmLabel.labelFor(selection));
        };
        shown.show();
        return shown;
    }

    private Runnable onChange;

    // ---- building ------------------------------------------------------------------------------

    View build() {
        ScrollView scroll = new ScrollView(context);
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dp(context, 20);
        body.setPadding(pad, UiKit.dp(context, 4), pad, UiKit.dp(context, 8));
        scroll.addView(body);

        // Provider chips only when there is a real choice to make. A single-provider phone gets no
        // control that can only ever show one option.
        if (providers.size() > 1) {
            body.addView(heading("Provider"));
            providerRow = new LinearLayout(context);
            providerRow.setOrientation(LinearLayout.HORIZONTAL);
            HorizontalScrollView providerScroll = new HorizontalScrollView(context);
            providerScroll.setHorizontalScrollBarEnabled(false);
            providerScroll.addView(providerRow);
            for (AiProvider provider : providers) {
                Button chip = chip(provider.displayName());
                chip.setOnClickListener(v -> chooseProvider(provider.id(), v));
                chip.setTag(provider.id());
                providerChips.add(chip);
                providerRow.addView(chip, chipLp());
            }
            body.addView(providerScroll);
        }

        body.addView(heading("Model"));
        modelList = new LinearLayout(context);
        modelList.setOrientation(LinearLayout.VERTICAL);
        body.addView(modelList);
        buildModelRows();

        strengthHeading = heading("Strength");
        body.addView(strengthHeading);
        strengthRow = new LinearLayout(context);
        strengthRow.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView strengthScroll = new HorizontalScrollView(context);
        strengthScroll.setHorizontalScrollBarEnabled(false);
        strengthScroll.addView(strengthRow);
        for (AiStrength strength : AiStrength.values()) {
            Button chip = chip(strength.label);
            chip.setOnClickListener(v -> chooseStrength(strength, v));
            strengthChips.put(strength, chip);
            strengthRow.addView(chip, chipLp());
        }
        body.addView(strengthScroll);
        noStrengthNote = UiKit.text(context,
                "This model has no strength setting.", 13, UiKit.MUTED, false);
        noStrengthNote.setPadding(0, UiKit.dp(context, 2), 0, 0);
        body.addView(noStrengthNote);

        refresh();
        return scroll;
    }

    private void buildModelRows() {
        modelList.removeAllViews();
        modelRows.clear();
        modelSpecs.clear();
        for (AiModelSpec spec : OrbitModelCatalog.modelsFor(selection.provider)) {
            View row = modelRow(spec);
            modelRows.add(row);
            modelSpecs.add(spec);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, UiKit.dp(context, 3), 0, UiKit.dp(context, 3));
            modelList.addView(row, lp);
        }
    }

    private View modelRow(AiModelSpec spec) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(UiKit.dp(context, 52));
        row.setPadding(UiKit.dp(context, 14), UiKit.dp(context, 8), UiKit.dp(context, 12),
                UiKit.dp(context, 8));
        row.setClickable(true);
        row.setFocusable(true);

        LinearLayout words = new LinearLayout(context);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView name = UiKit.text(context, spec.displayName, 15, UiKit.TEXT, true);
        words.addView(name);
        boolean unavailable = ModelAvailability.knownUnavailable(context, spec.providerId, spec.id);
        String note = unavailable ? "Not available on this account right now" : spec.descriptor;
        if (!note.isEmpty()) {
            TextView descriptor = UiKit.text(context, note, 12,
                    unavailable ? UiKit.DANGER : UiKit.MUTED, false);
            words.addView(descriptor);
        }
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView check = UiKit.text(context, "✓", 17, UiKit.accent(context), true);
        check.setTag("check");
        check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(check);

        row.setOnClickListener(v -> chooseModel(spec.id, v));
        UiKit.pressScale(row);
        return row;
    }

    private TextView heading(String text) {
        TextView t = UiKit.text(context, text, 12, UiKit.MUTED, true);
        t.setAllCaps(true);
        t.setLetterSpacing(0.06f);
        t.setPadding(0, UiKit.dp(context, 14), 0, UiKit.dp(context, 6));
        return t;
    }

    private Button chip(String label) {
        Button b = new Button(context);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setSingleLine(true);
        b.setMinHeight(UiKit.dp(context, 44));
        b.setMinimumHeight(UiKit.dp(context, 44));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setStateListAnimator(null);
        b.setPadding(UiKit.dp(context, 14), 0, UiKit.dp(context, 14), 0);
        UiKit.pressScale(b);
        return b;
    }

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(context, 44));
        lp.setMargins(0, 0, UiKit.dp(context, 8), 0);
        return lp;
    }

    // ---- choosing ------------------------------------------------------------------------------

    private void chooseProvider(String provider, View source) {
        if (provider.equals(selection.provider)) return;
        selection = AiSelections.withProvider(selection, provider);
        buildModelRows();
        tick(source);
        refresh();
    }

    void chooseModel(String model, View source) {
        selection = AiSelections.withModel(selection, model);
        tick(source);
        refresh();
    }

    void chooseStrength(AiStrength strength, View source) {
        selection = AiSelections.withStrength(selection, strength);
        tick(source);
        refresh();
    }

    private void tick(View source) {
        if (source != null && Prefs.haptics(context)) {
            UiKit.haptic(source, HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    /** Applies the current selection to the existing views. Builds nothing. */
    private void refresh() {
        int accent = UiKit.accent(context);
        for (Button chip : providerChips) {
            styleChip(chip, chip.getTag().equals(selection.provider), accent,
                    "Provider " + chip.getText());
        }
        for (int i = 0; i < modelRows.size(); i++) {
            View row = modelRows.get(i);
            AiModelSpec spec = modelSpecs.get(i);
            boolean selected = spec.id.equals(selection.model);
            row.setBackground(selected
                    ? UiKit.rippleOutlined(UiKit.withAlpha(accent, 38), UiKit.withAlpha(accent, 170),
                            accent, 14, context)
                    : UiKit.rippleOutlined(UiKit.SURFACE_2, UiKit.withAlpha(accent, 40),
                            accent, 14, context));
            View check = row.findViewWithTag("check");
            if (check != null) check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
            row.setContentDescription(spec.displayName
                    + (spec.descriptor.isEmpty() ? "" : ". " + spec.descriptor)
                    + (selected ? ". Selected" : ""));
            row.setSelected(selected);
        }
        List<AiStrength> legal = AiSelections.strengthsFor(selection);
        for (Map.Entry<AiStrength, Button> e : strengthChips.entrySet()) {
            boolean offered = legal.contains(e.getKey());
            e.getValue().setVisibility(offered ? View.VISIBLE : View.GONE);
            styleChip(e.getValue(), e.getKey() == selection.strength, accent,
                    "Strength " + e.getKey().label);
        }
        boolean hasStrengths = !legal.isEmpty();
        strengthHeading.setVisibility(hasStrengths ? View.VISIBLE : View.GONE);
        strengthRow.setVisibility(hasStrengths ? View.VISIBLE : View.GONE);
        noStrengthNote.setVisibility(hasStrengths ? View.GONE : View.VISIBLE);
        if (onChange != null) onChange.run();
    }

    private void styleChip(Button chip, boolean selected, int accent, String description) {
        chip.setSelected(selected);
        chip.setTextColor(selected ? UiKit.onAccent(accent) : UiKit.TEXT);
        UiKit.setTextWeight(chip, selected);
        chip.setBackground(selected
                ? UiKit.ripple(accent, UiKit.onAccent(accent), 16, context)
                : UiKit.rippleOutlined(UiKit.SURFACE_2, UiKit.withAlpha(accent, 60), accent, 16,
                        context));
        chip.setContentDescription(description + (selected ? ", selected" : ""));
    }

    // ---- for tests -----------------------------------------------------------------------------

    /** Strength chips currently offered, in order. */
    List<AiStrength> offeredStrengths() {
        List<AiStrength> out = new ArrayList<>();
        for (Map.Entry<AiStrength, Button> e : strengthChips.entrySet()) {
            if (e.getValue().getVisibility() == View.VISIBLE) out.add(e.getKey());
        }
        return out;
    }

    /** Model ids currently listed, in order. */
    List<String> offeredModels() {
        List<String> out = new ArrayList<>();
        for (AiModelSpec spec : modelSpecs) out.add(spec.id);
        return out;
    }

    /** Model list rows; identity is stable across model and strength changes. */
    List<View> modelRowsForTest() { return modelRows; }
}
