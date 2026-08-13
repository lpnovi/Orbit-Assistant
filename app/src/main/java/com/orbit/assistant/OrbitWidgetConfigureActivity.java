package com.orbit.assistant;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
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

import java.util.List;

/** Orbit-styled configuration for Run Routine and Quick Actions widgets. */
public final class OrbitWidgetConfigureActivity extends Activity {
    private static final String[] ACTION_TYPES = {
            OrbitWidgets.TYPE_ASK, OrbitWidgets.TYPE_ROUTINE, OrbitWidgets.TYPE_FLASHLIGHT,
            OrbitWidgets.TYPE_REMINDER, OrbitWidgets.TYPE_NONE
    };
    private static final String[] ACTION_LABELS = {
            "Ask Orbit", "Run a Routine", "Flashlight", "Create reminder", "Empty"
    };

    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private boolean runRoutineWidget;
    private String runRoutineId = "";
    private final String[] quickTypes = new String[4];
    private final String[] quickRoutineIds = new String[4];

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);
        UiKit.syncTheme(this);
        widgetId = getIntent() == null ? AppWidgetManager.INVALID_APPWIDGET_ID
                : getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
        if (!OrbitWidgets.isOwnedConfiguration(this, widgetId)) {
            Toast.makeText(this, "This Orbit widget is no longer available.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        runRoutineWidget = OrbitWidgets.isRunRoutineWidget(this, widgetId);
        loadState();
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

    private void loadState() {
        if (runRoutineWidget) {
            runRoutineId = OrbitWidgets.runRoutineId(this, widgetId);
            return;
        }
        for (int slot = 0; slot < 4; slot++) {
            quickTypes[slot] = OrbitWidgets.quickType(this, widgetId, slot);
            quickRoutineIds[slot] = OrbitWidgets.quickRoutineId(this, widgetId, slot);
        }
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(UiKit.BG);
        scroll.setForceDarkAllowed(false);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int padding = UiKit.dp(this, 20);
        page.setPadding(padding, UiKit.dp(this, 28), padding, UiKit.dp(this, 38));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton close = iconButton(R.drawable.ic_close, "Cancel widget setup");
        close.setOnClickListener(v -> finish());
        header.addView(close, new LinearLayout.LayoutParams(UiKit.dp(this, 46), UiKit.dp(this, 46)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(UiKit.dp(this, 13), 0, 0, 0);
        titles.addView(UiKit.text(this, runRoutineWidget ? "Run Routine widget" : "Quick Actions widget",
                23, UiKit.TEXT, true));
        titles.addView(UiKit.text(this, "Home-screen setup", 12, UiKit.MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(header);

        TextView intro = UiKit.text(this, runRoutineWidget
                        ? "Choose the saved Routine this widget will open and run through Orbit's normal Action Engine."
                        : "Choose up to four concise actions. Compact widget sizes show the first two slots.",
                13, UiKit.MUTED, false);
        intro.setLineSpacing(0, 1.14f);
        LinearLayout.LayoutParams introLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        introLp.setMargins(UiKit.dp(this, 2), UiKit.dp(this, 18), UiKit.dp(this, 2), UiKit.dp(this, 14));
        page.addView(intro, introLp);

        if (runRoutineWidget) buildRoutineConfiguration(page);
        else buildQuickConfiguration(page);

        Button save = primaryButton("Save widget");
        save.setOnClickListener(v -> save());
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 50));
        saveLp.setMargins(0, UiKit.dp(this, 18), 0, 0);
        page.addView(save, saveLp);

        Button cancel = secondaryButton("Cancel");
        cancel.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 46));
        cancelLp.setMargins(0, UiKit.dp(this, 9), 0, 0);
        page.addView(cancel, cancelLp);
        UiKit.applyTypography(page);
        return scroll;
    }

    private void buildRoutineConfiguration(LinearLayout page) {
        LinearLayout card = card();
        card.addView(UiKit.text(this, "ROUTINE", 12, UiKit.MUTED, true));
        Button chooser = secondaryButton(routineLabel(runRoutineId));
        chooser.setOnClickListener(v -> showRoutineChooser(chooser, selected -> {
            runRoutineId = selected;
            chooser.setText(routineLabel(selected));
        }));
        LinearLayout.LayoutParams chooserLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48));
        chooserLp.setMargins(0, UiKit.dp(this, 10), 0, 0);
        card.addView(chooser, chooserLp);
        addNoRoutinesHelp(card);
        page.addView(card);
    }

    private void buildQuickConfiguration(LinearLayout page) {
        for (int slot = 0; slot < 4; slot++) {
            final int index = slot;
            LinearLayout card = card();
            TextView title = UiKit.text(this, "SLOT " + (slot + 1), 12, UiKit.MUTED, true);
            card.addView(title);

            Button action = secondaryButton(actionLabel(quickTypes[slot]));
            LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 46));
            actionLp.setMargins(0, UiKit.dp(this, 9), 0, 0);
            card.addView(action, actionLp);

            Button routine = secondaryButton(routineLabel(quickRoutineIds[slot]));
            LinearLayout.LayoutParams routineLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 44));
            routineLp.setMargins(0, UiKit.dp(this, 8), 0, 0);
            card.addView(routine, routineLp);
            routine.setVisibility(OrbitWidgets.TYPE_ROUTINE.equals(quickTypes[slot])
                    ? View.VISIBLE : View.GONE);

            action.setOnClickListener(v -> UiKit.showOrbitMenu(this, action, ACTION_LABELS,
                    actionIndex(quickTypes[index]), (position, label) -> {
                        quickTypes[index] = ACTION_TYPES[position];
                        action.setText(ACTION_LABELS[position]);
                        routine.setVisibility(OrbitWidgets.TYPE_ROUTINE.equals(quickTypes[index])
                                ? View.VISIBLE : View.GONE);
                        if (OrbitWidgets.TYPE_ROUTINE.equals(quickTypes[index]) &&
                                RoutineStore.findById(this, quickRoutineIds[index]) == null) {
                            showRoutineChooser(routine, selected -> {
                                quickRoutineIds[index] = selected;
                                routine.setText(routineLabel(selected));
                            });
                        }
                    }));
            routine.setOnClickListener(v -> showRoutineChooser(routine, selected -> {
                quickRoutineIds[index] = selected;
                routine.setText(routineLabel(selected));
            }));
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.setMargins(0, 0, 0, UiKit.dp(this, 10));
            page.addView(card, cardLp);
        }
    }

    private void showRoutineChooser(View anchor, RoutineChoice callback) {
        List<RoutineStore.Routine> routines = RoutineStore.list(this);
        if (routines.isEmpty()) {
            Toast.makeText(this, "Create a saved Routine first.", Toast.LENGTH_LONG).show();
            return;
        }
        String[] labels = new String[routines.size()];
        for (int i = 0; i < routines.size(); i++) labels[i] = routines.get(i).name;
        UiKit.showOrbitMenu(this, anchor, labels, -1,
                (position, label) -> callback.onChoice(routines.get(position).id));
    }

    private void addNoRoutinesHelp(LinearLayout card) {
        if (!RoutineStore.list(this).isEmpty()) return;
        TextView help = UiKit.text(this,
                "No saved Routines are available yet. Create one, then return here to choose it.",
                12, UiKit.MUTED, false);
        help.setPadding(0, UiKit.dp(this, 10), 0, UiKit.dp(this, 8));
        card.addView(help);
        Button open = secondaryButton("Open Routines");
        open.setOnClickListener(v -> startActivity(new Intent(this, RoutinesActivity.class)));
        card.addView(open, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 44)));
    }

    private void save() {
        if (runRoutineWidget) {
            if (RoutineStore.findById(this, runRoutineId) == null) {
                Toast.makeText(this, "Choose an available Routine.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!OrbitWidgets.saveRunRoutine(this, widgetId, runRoutineId)) {
                Toast.makeText(this, "Orbit could not save this widget.", Toast.LENGTH_SHORT).show();
                return;
            }
            OrbitWidgets.updateRunRoutine(this, AppWidgetManager.getInstance(this), widgetId);
        } else {
            if (OrbitWidgets.TYPE_NONE.equals(quickTypes[0]) ||
                    OrbitWidgets.TYPE_NONE.equals(quickTypes[1])) {
                Toast.makeText(this, "Choose actions for the first two slots.",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            for (int slot = 0; slot < 4; slot++) {
                if (OrbitWidgets.TYPE_ROUTINE.equals(quickTypes[slot]) &&
                        RoutineStore.findById(this, quickRoutineIds[slot]) == null) {
                    Toast.makeText(this, "Choose a Routine for slot " + (slot + 1) + ".",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            if (!OrbitWidgets.saveQuickSlots(this, widgetId, quickTypes, quickRoutineIds)) {
                Toast.makeText(this, "Orbit could not save this widget.", Toast.LENGTH_SHORT).show();
                return;
            }
            OrbitWidgets.updateQuickActions(this, AppWidgetManager.getInstance(this), widgetId);
        }
        Intent result = new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        setResult(RESULT_OK, result);
        finish();
    }

    private String routineLabel(String id) {
        RoutineStore.Routine routine = RoutineStore.findById(this, id);
        return routine == null ? "Choose Routine  ▾" : routine.name + "  ▾";
    }

    private static int actionIndex(String type) {
        for (int i = 0; i < ACTION_TYPES.length; i++) if (ACTION_TYPES[i].equals(type)) return i;
        return ACTION_TYPES.length - 1;
    }

    private static String actionLabel(String type) {
        return ACTION_LABELS[actionIndex(type)] + "  ▾";
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(UiKit.dp(this, 17), UiKit.dp(this, 15),
                UiKit.dp(this, 17), UiKit.dp(this, 15));
        card.setBackground(UiKit.outlined(UiKit.SURFACE,
                UiKit.withAlpha(UiKit.accent(this), 42), 20, this));
        card.setElevation(UiKit.dp(this, 2));
        return card;
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(UiKit.onAccent(this));
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setStateListAnimator(null);
        button.setBackground(UiKit.ripple(UiKit.accent(this), UiKit.onAccent(this), 16, this));
        UiKit.pressScale(button);
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(UiKit.TEXT);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setStateListAnimator(null);
        button.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), 86), UiKit.accent(this), 15, this));
        UiKit.pressScale(button);
        return button;
    }

    private ImageButton iconButton(int icon, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setImageTintList(ColorStateList.valueOf(UiKit.accent(this)));
        button.setContentDescription(description);
        button.setPadding(UiKit.dp(this, 11), UiKit.dp(this, 11),
                UiKit.dp(this, 11), UiKit.dp(this, 11));
        button.setBackground(UiKit.ripple(UiKit.SURFACE_2, UiKit.accent(this), 18, this));
        UiKit.pressScale(button);
        return button;
    }

    private interface RoutineChoice { void onChoice(String routineId); }
}
