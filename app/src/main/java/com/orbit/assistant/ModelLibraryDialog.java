package com.orbit.assistant;

import android.app.AlertDialog;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Searchable, capability-aware Model Library kept behind the quick picker. */
final class ModelLibraryDialog {
    interface OnChosen { void onChosen(AiSelection selection); }

    /**
     * Most rows built at once. OpenRouter alone lists hundreds of models; building a row for each
     * on every keystroke would stall the dialog, so the rest are reached by searching.
     */
    static final int MAX_ROWS = 60;

    private final Context context;
    private final OnChosen chosen;
    private final LinearLayout results;
    private final EditText search;
    private String providerFilter = "";
    private boolean favoritesOnly;
    private boolean visionOnly;
    private boolean reasoningOnly;
    private AlertDialog dialog;

    static void show(Context context, AiSelection initial, OnChosen chosen) {
        new ModelLibraryDialog(context, chosen).open(initial);
    }

    private ModelLibraryDialog(Context context, OnChosen chosen) {
        this.context = context;
        this.chosen = chosen;
        this.results = new LinearLayout(context);
        this.search = new EditText(context);
    }

    private void open(AiSelection initial) {
        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dp(context, 18);
        page.setPadding(pad, UiKit.dp(context, 4), pad, UiKit.dp(context, 8));

        search.setHint("Search models, makers or IDs");
        search.setSingleLine(true);
        search.setTextColor(UiKit.TEXT);
        search.setHintTextColor(UiKit.MUTED);
        page.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout filters = new LinearLayout(context);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        Button all = filter("All");
        all.setOnClickListener(v -> {
            providerFilter = "";
            favoritesOnly = false;
            visionOnly = false;
            reasoningOnly = false;
            rebuild();
        });
        filters.addView(all);
        Button favorites = filter("Favorites");
        favorites.setOnClickListener(v -> { favoritesOnly = true; rebuild(); });
        filters.addView(favorites);
        // Two capability filters, each a toggle; they combine with the provider filter.
        Button vision = filter("Vision");
        vision.setOnClickListener(v -> { visionOnly = !visionOnly; rebuild(); });
        filters.addView(vision);
        Button reasoning = filter("Reasoning");
        reasoning.setOnClickListener(v -> { reasoningOnly = !reasoningOnly; rebuild(); });
        filters.addView(reasoning);
        for (AiProvider provider : AiSelections.pickableProviders(context)) {
            Button button = filter(provider.displayName());
            button.setOnClickListener(v -> {
                providerFilter = provider.id();
                favoritesOnly = false;
                rebuild();
            });
            filters.addView(button);
        }
        HorizontalScrollView filterScroll = new HorizontalScrollView(context);
        filterScroll.setHorizontalScrollBarEnabled(false);
        filterScroll.addView(filters);
        page.addView(filterScroll);

        results.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(results);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(context, 430));
        page.addView(scroll, scrollLp);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { rebuild(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        // Opened from Auto, which belongs to no provider, the library shows every provider.
        if (initial != null && !initial.isAuto()) providerFilter = initial.provider;
        rebuild();
        AlertDialog.Builder builder = new AlertDialog.Builder(context).setTitle("Browse Models")
                .setView(page).setNegativeButton("Close", null);
        AlertDialog builtDialog = builder.create();
        dialog = builtDialog;
        UiKit.styleOrbitDialog(builtDialog, context, false);
        builtDialog.show();
    }

    private void rebuild() {
        results.removeAllViews();
        String query = search.getText().toString().trim().toLowerCase(Locale.US);
        java.util.Set<String> favoriteKeys = new java.util.HashSet<>();
        for (AiSelection favorite : ModelLibraryStore.favorites(context)) {
            favoriteKeys.add(favorite.provider + "/" + favorite.model);
        }
        int count = 0;
        int shown = 0;
        for (AiProvider provider : AiSelections.pickableProviders(context)) {
            if (!providerFilter.isEmpty() && !providerFilter.equals(provider.id())) continue;
            for (AiModelSpec spec : modelsForBrowse(provider.id())) {
                if (favoritesOnly && !favoriteKeys.contains(provider.id() + "/" + spec.id)) continue;
                if (!passesCapabilities(spec, visionOnly, reasoningOnly)) continue;
                if (!matches(spec, provider.displayName(), query)) continue;
                count++;
                // Count everything, build only the first rows: the list stays quick at any size.
                if (shown < MAX_ROWS) {
                    results.addView(row(provider, spec));
                    shown++;
                }
            }
        }
        if (count == 0) {
            TextView empty = UiKit.text(context, favoritesOnly
                    ? "No favorite models match." : "No models match.", 14, UiKit.MUTED, false);
            empty.setPadding(0, UiKit.dp(context, 24), 0, UiKit.dp(context, 24));
            empty.setGravity(Gravity.CENTER);
            results.addView(empty);
        } else if (count > shown) {
            TextView more = UiKit.text(context, moreLabel(shown, count), 12.5f, UiKit.MUTED, false);
            more.setPadding(0, UiKit.dp(context, 12), 0, UiKit.dp(context, 12));
            more.setGravity(Gravity.CENTER);
            results.addView(more);
        }
        UiKit.applyTypography(results);
    }

    /** "Showing 60 of 412 models. Search to find more." */
    static String moreLabel(int shown, int total) {
        return "Showing " + shown + " of " + total + " models. Search to find more.";
    }

    /** The Vision and Reasoning filters: only what the catalog states, never a guess. */
    static boolean passesCapabilities(AiModelSpec spec, boolean visionOnly, boolean reasoningOnly) {
        if (visionOnly && !spec.vision) return false;
        return !reasoningOnly || spec.hasStrengths();
    }

    private List<AiModelSpec> modelsForBrowse(String provider) {
        List<AiModelSpec> out = new ArrayList<>(OrbitModelCatalog.modelsFor(provider));
        for (AiSelection favorite : ModelLibraryStore.favorites(context)) {
            if (!provider.equals(favorite.provider)) continue;
            boolean found = false;
            for (AiModelSpec spec : out) if (spec.id.equals(favorite.model)) { found = true; break; }
            if (!found) out.add(OrbitModelCatalog.unavailableReference(provider, favorite.model));
        }
        return out;
    }

    static boolean matches(AiModelSpec spec, String providerName, String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.US);
        if (query.isEmpty()) return true;
        // Name, family or maker ("Anthropic"), route ("OpenRouter") and the exact id or slug
        // ("anthropic/claude-sonnet-5.5"), so a model can be found however the user knows it.
        String haystack = (spec.displayName + " " + spec.familyLabel + " "
                + (providerName == null ? "" : providerName) + " " + spec.id).toLowerCase(Locale.US);
        for (String word : query.split("\\s+")) {
            if (!word.isEmpty() && !haystack.contains(word)) return false;
        }
        return true;
    }

    private View row(AiProvider provider, AiModelSpec spec) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(UiKit.dp(context, 12), UiKit.dp(context, 9), UiKit.dp(context, 6),
                UiKit.dp(context, 9));
        row.setMinimumHeight(UiKit.dp(context, 64));
        row.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(context), 45), UiKit.accent(context), 14, context));

        LinearLayout words = new LinearLayout(context);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(UiKit.text(context, spec.displayName, 15, UiKit.TEXT, true));
        boolean openRouterAuto = OrbitModelCatalog.OPENROUTER_AUTO.equals(spec.id);
        String contextLabel = openRouterAuto ? "Context varies by model"
                : spec.contextWindowTokens <= 0 ? "Context unknown"
                : "Context " + compact(spec.contextWindowTokens);
        // The route comes first, so the same model direct and through OpenRouter never look alike.
        String route = Prefs.PROVIDER_OPENROUTER.equals(provider.id()) && !openRouterAuto
                && !spec.familyLabel.isEmpty()
                ? provider.displayName() + " · " + spec.familyLabel : provider.displayName();
        String detail = route + " · " + contextLabel
                + " · Vision " + (spec.vision ? "Yes" : "Not listed")
                + (spec.nativeFiles ? " · Provider file input" : "")
                + (spec.extractedDocuments ? " · Orbit text extraction" : "")
                + (spec.tools ? " · Tools" : "")
                + (spec.webSearch ? " · Web search" : "")
                + (spec.hasStrengths() ? " · Reasoning " + strengthRange(spec) : " · No strength control")
                + (spec.selectable() ? "" : " · Unavailable");
        words.addView(UiKit.text(context, detail, 11.5f,
                spec.selectable() ? UiKit.MUTED : UiKit.DANGER, false));
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button star = filter(ModelLibraryStore.isFavorite(context, provider.id(), spec.id) ? "★" : "☆");
        star.setContentDescription((ModelLibraryStore.isFavorite(context, provider.id(), spec.id)
                ? "Remove from Favorites: " : "Add to Favorites: ") + spec.displayName);
        star.setOnClickListener(v -> {
            boolean next = !ModelLibraryStore.isFavorite(context, provider.id(), spec.id);
            ModelLibraryStore.setFavorite(context, provider.id(), spec.id, next);
            rebuild();
        });
        row.addView(star);
        if (spec.selectable()) row.setOnClickListener(v -> {
            AiSelection selected = AiSelection.of(provider.id(), spec.id, spec.defaultStrength);
            chosen.onChosen(AiSelections.resolve(selected));
            if (dialog != null) dialog.dismiss();
        });
        row.setOnLongClickListener(v -> {
            boolean next = !ModelLibraryStore.isFavorite(context, provider.id(), spec.id);
            ModelLibraryStore.setFavorite(context, provider.id(), spec.id, next);
            rebuild();
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, UiKit.dp(context, 4), 0, UiKit.dp(context, 4));
        row.setLayoutParams(lp);
        return row;
    }

    private Button filter(String label) {
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setSingleLine(true);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(UiKit.dp(context, 42));
        button.setMinimumHeight(UiKit.dp(context, 42));
        button.setPadding(UiKit.dp(context, 13), 0, UiKit.dp(context, 13), 0);
        button.setTextColor(UiKit.TEXT);
        button.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(context), 55), UiKit.accent(context), 15, context));
        return button;
    }

    private static String compact(int tokens) {
        if (tokens >= 1_000_000) return (tokens % 1_000_000 == 0
                ? String.valueOf(tokens / 1_000_000) : String.format(Locale.US, "%.2f", tokens / 1_000_000.0)) + "M";
        return Math.round(tokens / 1000f) + "K";
    }

    private static String strengthRange(AiModelSpec spec) {
        if (spec.strengths.isEmpty()) return "None";
        if (spec.strengths.size() == 1) return spec.strengths.get(0).label;
        return spec.strengths.get(0).label + "-" + spec.strengths.get(spec.strengths.size() - 1).label;
    }
}
