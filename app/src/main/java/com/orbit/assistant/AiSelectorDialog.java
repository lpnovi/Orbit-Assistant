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
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Orbit's one picker for provider, model and strength.
 *
 * <p>Used by the chat header, Settings' defaults and Retry with, so the three can never disagree
 * about what is legal: every choice goes through {@link AiSelections}, and the picker only shows
 * what that layer says the selected model accepts. There is no disabled "None" on GPT-6.1 Sol; it
 * simply is not offered, and moving from Luna at None to Sol lands visibly on Low.
 *
 * <p>Auto (0.8.3.0-beta.5+) is the first row: one Orbit-level choice rather than a model of any
 * provider, so choosing it clears the provider, model and strength selection, and its only option
 * is the line leading to which providers Auto may use.
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
    private final List<TextView> modelSections = new ArrayList<>();
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
                .setPositiveButton(confirmLabel.labelFor(selection), (d, w) -> {
                    AiSelection confirmed = AiSelections.resolve(selection);
                    // The first time Auto is chosen anywhere, one short sheet says what it does.
                    if (confirmed.isAuto()) AutoSheets.introThen(context, () -> chosen.onChosen(confirmed));
                    else chosen.onChosen(confirmed);
                });
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

    /**
     * The provider whose models are listed. Auto belongs to no provider, so while it is selected the
     * list shows the explicit default's provider, ready for leaving Auto with one tap.
     */
    private String browseProvider() {
        return selection.isAuto() ? AiSelections.globalDefault(context).provider : selection.provider;
    }

    private void buildModelRows() {
        modelList.removeAllViews();
        modelRows.clear();
        modelSpecs.clear();
        modelSections.clear();
        // Auto first: one Orbit-level choice, not a model of any provider.
        autoRow = autoRow();
        LinearLayout.LayoutParams autoLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        autoLp.setMargins(0, UiKit.dp(context, 3), 0, UiKit.dp(context, 3));
        modelList.addView(autoRow, autoLp);
        autoProviders = UiKit.text(context, autoProvidersText(), 12, UiKit.accent(context), false);
        autoProviders.setPadding(UiKit.dp(context, 14), UiKit.dp(context, 2), UiKit.dp(context, 14),
                UiKit.dp(context, 6));
        autoProviders.setMinHeight(UiKit.dp(context, 32));
        autoProviders.setGravity(Gravity.CENTER_VERTICAL);
        autoProviders.setContentDescription("Choose which providers Auto can use");
        autoProviders.setOnClickListener(v -> AutoSheets.showSettings(context, () -> {
            if (autoProviders != null) autoProviders.setText(autoProvidersText());
        }));
        modelList.addView(autoProviders);

        Set<String> added = new LinkedHashSet<>();
        addGroup("Favorites", ModelLibraryStore.favorites(context), added, Integer.MAX_VALUE);
        addGroup("Recent", ModelLibraryStore.recents(context), added, 4);

        String provider = browseProvider();
        List<AiSelection> common = new ArrayList<>();
        int shown = 0;
        for (AiModelSpec spec : OrbitModelCatalog.modelsFor(provider)) {
            if (shown < 6 || spec.id.equals(selection.model)) {
                common.add(AiSelection.of(spec.providerId, spec.id, spec.defaultStrength));
                shown++;
            }
        }
        if (Prefs.PROVIDER_CHATGPT.equals(provider)) {
            addFamilyGroups(common, added);
        } else {
            addGroup(AiProviders.byId(provider).displayName(), common, added,
                    Integer.MAX_VALUE);
        }

        Button browse = chip("Browse models ›");
        browse.setContentDescription("Browse all models");
        browse.setOnClickListener(v -> ModelLibraryDialog.show(context, selection, chosen -> {
            selection = chosen;
            buildModelRows();
            refresh();
        }));
        LinearLayout.LayoutParams browseLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(context, 46));
        browseLp.setMargins(0, UiKit.dp(context, 9), 0, UiKit.dp(context, 2));
        modelList.addView(browse, browseLp);
    }

    /** Preserve the concise GPT family headings while Favorites and Recents may span providers. */
    private void addFamilyGroups(List<AiSelection> selections, Set<String> added) {
        List<String> families = new ArrayList<>();
        for (AiSelection item : selections) {
            AiModelSpec spec = OrbitModelCatalog.spec(item.provider, item.model);
            if (spec != null && !families.contains(spec.familyLabel)) families.add(spec.familyLabel);
        }
        for (String family : families) {
            List<AiSelection> members = new ArrayList<>();
            for (AiSelection item : selections) {
                AiModelSpec spec = OrbitModelCatalog.spec(item.provider, item.model);
                if (spec != null && family.equals(spec.familyLabel)) members.add(item);
            }
            addGroup(family, members, added, Integer.MAX_VALUE);
        }
    }

    private void addGroup(String label, List<AiSelection> selections, Set<String> added, int max) {
        List<AiModelSpec> specs = new ArrayList<>();
        for (AiSelection item : selections) {
            if (item == null || specs.size() >= max) break;
            AiModelSpec spec = OrbitModelCatalog.spec(item.provider, item.model);
            if (spec == null && OrbitModelCatalog.isDynamicProvider(item.provider)) {
                spec = OrbitModelCatalog.unavailableReference(item.provider, item.model);
            }
            String key = item.provider + "/" + item.model;
            if (spec != null && added.add(key)) specs.add(spec);
        }
        if (specs.isEmpty()) return;
        TextView section = heading(label);
        section.setTextSize(11);
        section.setPadding(UiKit.dp(context, 2), UiKit.dp(context, 10), 0, UiKit.dp(context, 2));
        modelList.addView(section);
        modelSections.add(section);
        for (AiModelSpec spec : specs) addModelRow(spec);
    }

    private void addModelRow(AiModelSpec spec) {
        View row = modelRow(spec);
        modelRows.add(row);
        modelSpecs.add(spec);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, UiKit.dp(context, 3), 0, UiKit.dp(context, 3));
        modelList.addView(row, lp);
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

        if (spec.selectable()) row.setOnClickListener(v -> chooseSpec(spec, v));
        row.setOnLongClickListener(v -> {
            boolean next = !ModelLibraryStore.isFavorite(context, spec.providerId, spec.id);
            ModelLibraryStore.setFavorite(context, spec.providerId, spec.id, next);
            buildModelRows();
            refresh();
            return true;
        });
        UiKit.pressScale(row);
        return row;
    }

    private View autoRow;
    private TextView autoProviders;

    /** "Auto can use ChatGPT, Orbit Local ›": the one line that leads to Auto's settings. */
    private String autoProvidersText() {
        return "Auto can use " + AutoPermissions.summary(context) + " ›";
    }

    /** Auto's row: same shape as a model row, never a Favorite, with no strength of its own. */
    private View autoRow() {
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
        words.addView(UiKit.text(context, AiSelection.AUTO_LABEL + "  " + AUTO_MARK, 15,
                UiKit.TEXT, true));
        words.addView(UiKit.text(context, AiSelection.AUTO_DESCRIPTION, 12, UiKit.MUTED, false));
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView check = UiKit.text(context, "✓", 17, UiKit.accent(context), true);
        check.setTag("check");
        check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(check);
        row.setOnClickListener(v -> chooseAuto(v));
        UiKit.pressScale(row);
        return row;
    }

    /** Auto's restrained mark, shared with the chat header. */
    static final String AUTO_MARK = "✦";

    private void chooseAuto(View source) {
        if (selection.isAuto()) return;
        selection = AiSelection.AUTO;
        tick(source);
        buildModelRows();
        refresh();
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
        if (!selection.isAuto() && provider.equals(selection.provider)) return;
        selection = AiSelections.withProvider(context, selection, provider);
        buildModelRows();
        tick(source);
        refresh();
    }

    void chooseModel(String model, View source) {
        selection = AiSelections.withModel(selection, model);
        tick(source);
        refresh();
    }

    private void chooseSpec(AiModelSpec spec, View source) {
        AiStrength strength = selection.strength;
        if (selection.isAuto()) {
            // Leaving Auto: the strength last used with this model, when there is one.
            AiSelection remembered = ModelLibraryStore.providerDefault(context, spec.providerId);
            strength = remembered != null && remembered.model.equals(spec.id)
                    ? remembered.strength : spec.defaultStrength;
        }
        selection = AiSelections.resolve(AiSelection.of(spec.providerId, spec.id,
                spec.resolveStrength(strength)));
        tick(source);
        buildModelRows();
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
        boolean auto = selection.isAuto();
        for (Button chip : providerChips) {
            styleChip(chip, !auto && chip.getTag().equals(selection.provider), accent,
                    "Provider " + chip.getText());
        }
        if (autoRow != null) {
            autoRow.setBackground(auto
                    ? UiKit.rippleOutlined(UiKit.withAlpha(accent, 38), UiKit.withAlpha(accent, 170),
                            accent, 14, context)
                    : UiKit.rippleOutlined(UiKit.SURFACE_2, UiKit.withAlpha(accent, 40),
                            accent, 14, context));
            View check = autoRow.findViewWithTag("check");
            if (check != null) check.setVisibility(auto ? View.VISIBLE : View.INVISIBLE);
            autoRow.setContentDescription(AiSelection.AUTO_LABEL + ". "
                    + AiSelection.AUTO_DESCRIPTION + (auto ? ". Selected" : ""));
            autoRow.setSelected(auto);
        }
        if (autoProviders != null) autoProviders.setVisibility(auto ? View.VISIBLE : View.GONE);
        for (int i = 0; i < modelRows.size(); i++) {
            View row = modelRows.get(i);
            AiModelSpec spec = modelSpecs.get(i);
            boolean selected = !auto && spec.providerId.equals(selection.provider)
                    && spec.id.equals(selection.model);
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
        // Auto chooses the strength per request, so it has neither chips nor a "no strength" note.
        noStrengthNote.setVisibility(hasStrengths || auto ? View.GONE : View.VISIBLE);
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

    /** Auto's row, and the line leading to its settings. */
    View autoRowForTest() { return autoRow; }
    TextView autoProvidersForTest() { return autoProviders; }

    /** Taps Auto, as the user would. */
    void chooseAutoForTest() { chooseAuto(null); }

    /** Taps a listed model row, as the user would. */
    void chooseSpecForTest(AiModelSpec spec) { chooseSpec(spec, null); }

    /** Whether the "no strength setting" note is showing. */
    boolean noStrengthNoteShownForTest() { return noStrengthNote.getVisibility() == View.VISIBLE; }

    /** Model list rows; identity is stable across model and strength changes. */
    List<View> modelRowsForTest() { return modelRows; }

    List<String> modelSectionsForTest() {
        List<String> labels = new ArrayList<>();
        for (TextView section : modelSections) labels.add(section.getText().toString());
        return labels;
    }
}
