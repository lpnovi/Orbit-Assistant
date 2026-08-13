package com.orbit.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
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

/** Built-in Routine starting points. Using one creates an ordinary editable Routine. */
public final class RoutineTemplatesActivity extends Activity {
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
        page.setPadding(padding, UiKit.dp(this, 26), padding, UiKit.dp(this, 48));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = iconButton();
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(UiKit.dp(this, 44), UiKit.dp(this, 44)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(UiKit.dp(this, 13), 0, 0, 0);
        titles.addView(UiKit.text(this, "Routine Templates", 24, UiKit.TEXT, true));
        titles.addView(UiKit.text(this, "Start with a useful setup, then make it yours.",
                12, UiKit.MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(header);

        TextView explanation = UiKit.text(this,
                "Templates use only actions Orbit already supports. Choosing one creates a normal saved Routine that you can rename, reorder, expand with IF conditions, and schedule like any other Routine.",
                13, UiKit.MUTED, false);
        explanation.setLineSpacing(0, 1.12f);
        LinearLayout.LayoutParams explanationLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        explanationLp.setMargins(UiKit.dp(this, 2), UiKit.dp(this, 18), UiKit.dp(this, 2), UiKit.dp(this, 17));
        page.addView(explanation, explanationLp);

        for (RoutineTemplateCatalog.Template template : RoutineTemplateCatalog.list()) {
            page.addView(templateCard(template), cardLp());
        }

        TextView footer = UiKit.text(this,
                "Templates are built into Orbit and do not run until you create and run the resulting Routine.",
                11, UiKit.MUTED, false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 7), UiKit.dp(this, 8), 0);
        page.addView(footer);
        UiKit.applyTypography(page);
        return scroll;
    }

    private View templateCard(RoutineTemplateCatalog.Template template) {
        LinearLayout card = card();

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = UiKit.text(this, template.displayName, 17, UiKit.TEXT, true);
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView category = UiKit.text(this, template.category, 10, UiKit.accent(this), true);
        category.setLetterSpacing(0.08f);
        category.setPadding(UiKit.dp(this, 9), UiKit.dp(this, 4),
                UiKit.dp(this, 9), UiKit.dp(this, 4));
        category.setBackground(UiKit.rounded(
                UiKit.withAlpha(UiKit.accent(this), 32), 99, this));
        titleRow.addView(category);
        card.addView(titleRow);

        TextView description = UiKit.text(this, template.description, 13, UiKit.MUTED, false);
        description.setLineSpacing(0, 1.1f);
        description.setPadding(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 9));
        card.addView(description);

        TextView summary = UiKit.text(this, RoutineTemplateCatalog.actionSummary(template),
                12, UiKit.TEXT, false);
        summary.setMaxLines(2);
        summary.setPadding(0, 0, 0, UiKit.dp(this, 12));
        card.addView(summary);

        LinearLayout actions = new LinearLayout(this);
        Button preview = secondaryButton("Preview");
        preview.setOnClickListener(v -> showPreview(template));
        actions.addView(preview, new LinearLayout.LayoutParams(0, UiKit.dp(this, 42), 1));
        Button use = primaryButton("Use template");
        use.setOnClickListener(v -> useTemplate(template));
        LinearLayout.LayoutParams useLp = new LinearLayout.LayoutParams(0, UiKit.dp(this, 42), 1);
        useLp.leftMargin = UiKit.dp(this, 9);
        actions.addView(use, useLp);
        card.addView(actions);
        return card;
    }

    private void showPreview(RoutineTemplateCatalog.Template template) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int horizontal = UiKit.dp(this, 4);
        content.setPadding(horizontal, 0, horizontal, UiKit.dp(this, 4));

        TextView creates = UiKit.text(this,
                "Creates a normal editable Routine named “" + template.suggestedRoutineName + "”.",
                13, UiKit.MUTED, false);
        creates.setLineSpacing(0, 1.1f);
        creates.setPadding(0, 0, 0, UiKit.dp(this, 12));
        content.addView(creates);

        content.addView(dialogLabel("STEPS"));
        for (int i = 0; i < template.actions.size(); i++) {
            AssistantReply.Action action = template.actions.get(i);
            TextView step = UiKit.text(this,
                    (i + 1) + ".  " + RoutineActionCatalog.title(action),
                    13, UiKit.TEXT, false);
            step.setPadding(0, UiKit.dp(this, 5), 0, 0);
            content.addView(step);
        }

        List<String> capabilities = RoutineTemplateCatalog.capabilityNotes(this, template);
        if (!capabilities.isEmpty()) {
            TextView capabilityLabel = dialogLabel("CAPABILITIES");
            capabilityLabel.setPadding(0, UiKit.dp(this, 16), 0, UiKit.dp(this, 2));
            content.addView(capabilityLabel);
            for (String note : capabilities) {
                TextView row = UiKit.text(this, "• " + note, 12, UiKit.MUTED, false);
                row.setPadding(0, UiKit.dp(this, 4), 0, 0);
                content.addView(row);
            }
        }

        if (!template.customizationNote.isEmpty()) {
            TextView note = UiKit.text(this, template.customizationNote, 12, UiKit.MUTED, false);
            note.setLineSpacing(0, 1.08f);
            note.setPadding(0, UiKit.dp(this, 15), 0, 0);
            content.addView(note);
        }
        if (!template.recommendedCommandPhrase.isEmpty()) {
            TextView shortcut = UiKit.text(this,
                    "Suggested Custom Command: “" + template.recommendedCommandPhrase + "”",
                    12, UiKit.accent(this), true);
            shortcut.setPadding(0, UiKit.dp(this, 13), 0, 0);
            content.addView(shortcut);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setPadding(UiKit.dp(this, 18), 0, UiKit.dp(this, 18), 0);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(template.displayName)
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Use template", (d, which) -> useTemplate(template))
                .create();
        UiKit.styleOrbitDialog(dialog, this, false);
        dialog.show();
    }

    private void useTemplate(RoutineTemplateCatalog.Template template) {
        if (RoutineStore.list(this).size() >= RoutineStore.MAX_ROUTINES) {
            Toast.makeText(this, "Routine limit reached.", Toast.LENGTH_SHORT).show();
            return;
        }
        String name = RoutineTemplateCatalog.uniqueRoutineName(this, template.suggestedRoutineName);
        RoutineStore.Routine routine = RoutineStore.create(name, template.actions);
        if (!RoutineStore.upsert(this, routine)) {
            Toast.makeText(this, "Orbit could not create this Routine.", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(this, RoutineEditorActivity.class)
                .putExtra(RoutineEditorActivity.EXTRA_ROUTINE_ID, routine.id));
    }

    private TextView dialogLabel(String text) {
        TextView label = UiKit.text(this, text, 11, UiKit.MUTED, true);
        label.setLetterSpacing(0.12f);
        return label;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(UiKit.dp(this, 17), UiKit.dp(this, 15),
                UiKit.dp(this, 17), UiKit.dp(this, 15));
        card.setBackground(UiKit.outlined(UiKit.SURFACE,
                UiKit.withAlpha(UiKit.accent(this), 40), 20, this));
        card.setElevation(UiKit.dp(this, 2));
        return card;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, UiKit.dp(this, 10));
        return lp;
    }

    private ImageButton iconButton() {
        ImageButton button = new ImageButton(this);
        button.setImageResource(R.drawable.ic_back);
        button.setImageTintList(ColorStateList.valueOf(UiKit.accent(this)));
        button.setBackground(UiKit.ripple(UiKit.SURFACE_2, UiKit.accent(this), 18, this));
        button.setContentDescription("Back");
        button.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 10),
                UiKit.dp(this, 10), UiKit.dp(this, 10));
        UiKit.pressScale(button);
        return button;
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(UiKit.onAccent(this));
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setBackground(UiKit.ripple(UiKit.accent(this), UiKit.onAccent(this), 15, this));
        button.setMinHeight(0); button.setMinimumHeight(0); button.setStateListAnimator(null);
        UiKit.pressScale(button);
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(UiKit.TEXT);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                Color.rgb(53, 58, 72), UiKit.accent(this), 15, this));
        button.setMinHeight(0); button.setMinimumHeight(0); button.setStateListAnimator(null);
        UiKit.pressScale(button);
        return button;
    }
}
