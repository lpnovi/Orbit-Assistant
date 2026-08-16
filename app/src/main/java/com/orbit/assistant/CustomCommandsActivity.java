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

/** Manager for deterministic phrases that run existing saved Routines. */
public final class CustomCommandsActivity extends Activity {
    private LinearLayout commandList;

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
        refresh();
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
        int p = UiKit.dp(this, 20);
        page.setPadding(p, UiKit.dp(this, 26), p, UiKit.dp(this, 48));
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
        titles.addView(UiKit.text(this, "Custom Commands", 24, UiKit.TEXT, true));
        titles.addView(UiKit.text(this, "Natural shortcuts for Routines", 13, UiKit.MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(header);

        TextView intro = UiKit.text(this,
                "Create exact phrases such as “bedtime” that run an existing saved Routine. Matching stays local and does not guess at similar wording.",
                14, UiKit.MUTED, false);
        intro.setLineSpacing(0, 1.12f);
        LinearLayout.LayoutParams introLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        introLp.setMargins(UiKit.dp(this, 2), UiKit.dp(this, 18), UiKit.dp(this, 2), UiKit.dp(this, 16));
        page.addView(intro, introLp);

        Button create = primaryButton("+  New command");
        create.setOnClickListener(v -> createCommand());
        page.addView(create, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 50)));

        TextView section = UiKit.text(this, "SAVED COMMANDS", 12, UiKit.MUTED, true);
        section.setLetterSpacing(0.16f);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sectionLp.setMargins(UiKit.dp(this, 5), UiKit.dp(this, 20), 0, UiKit.dp(this, 10));
        page.addView(section, sectionLp);

        commandList = new LinearLayout(this);
        commandList.setOrientation(LinearLayout.VERTICAL);
        page.addView(commandList);

        TextView note = UiKit.text(this,
                "You can also say “Orbit, …” or “please …”. Existing Orbit commands always keep priority.",
                12, UiKit.MUTED, false);
        note.setGravity(Gravity.CENTER);
        note.setPadding(UiKit.dp(this, 7), UiKit.dp(this, 8), UiKit.dp(this, 7), 0);
        page.addView(note);
        UiKit.applyTypography(page);
        return scroll;
    }

    private void createCommand() {
        if (CustomCommandStore.list(this).size() >= CustomCommandStore.MAX_COMMANDS) {
            Toast.makeText(this, "Custom Command limit reached.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (RoutineStore.list(this).isEmpty()) {
            Toast.makeText(this, "Create a Routine first.", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(this, CustomCommandEditorActivity.class));
    }

    private void refresh() {
        if (commandList == null) return;
        commandList.removeAllViews();
        List<CustomCommandStore.Command> commands = CustomCommandStore.list(this);
        if (commands.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(UiKit.text(this, "No Custom Commands yet", 16, UiKit.TEXT, true));
            TextView detail = UiKit.text(this,
                    "Create a short phrase and connect it to one of your saved Routines.",
                    13, UiKit.MUTED, false);
            detail.setPadding(0, UiKit.dp(this, 6), 0, 0);
            empty.addView(detail);
            commandList.addView(empty, cardLp());
            return;
        }
        for (CustomCommandStore.Command command : commands) {
            commandList.addView(commandCard(command), cardLp());
        }
    }

    private View commandCard(CustomCommandStore.Command command) {
        LinearLayout card = card();
        RoutineStore.Routine routine = RoutineStore.findById(this, command.routineId);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView phrase = UiKit.text(this, "“" + command.primaryPhrase + "”", 16, UiKit.TEXT, true);
        phrase.setMaxLines(2);
        labels.addView(phrase);
        String target = routine == null ? "Needs attention · Routine unavailable" : "Runs: " + routine.name;
        TextView runs = UiKit.text(this, target, 12,
                routine == null ? Color.rgb(239, 105, 105) : UiKit.MUTED, false);
        runs.setPadding(0, UiKit.dp(this, 4), 0, 0);
        labels.addView(runs);
        top.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        OrbitSwitch enabled = new OrbitSwitch(this);
        enabled.setChecked(command.enabled, false);
        enabled.setContentDescription(command.enabled ? "Disable command" : "Enable command");
        enabled.setOnCheckedChangeListener((button, checked) -> {
            CustomCommandStore.Command changed = command.withEnabled(checked);
            CustomCommandStore.Validation validation = CustomCommandStore.validate(this, changed, command.id);
            if (!validation.valid || !CustomCommandStore.upsert(this, changed)) {
                // A programmatic correction does not re-enter this listener.
                button.setChecked(command.enabled);
                Toast.makeText(this, validation.valid ? "Could not update this command." : validation.message,
                        Toast.LENGTH_LONG).show();
                refresh();
                return;
            }
            refresh();
        });
        top.addView(enabled);
        card.addView(top);

        String aliases = command.aliases.isEmpty() ? "No additional phrases" :
                command.aliases.size() + (command.aliases.size() == 1 ? " additional phrase" : " additional phrases");
        TextView aliasCount = UiKit.text(this, aliases, 12, UiKit.MUTED, false);
        aliasCount.setPadding(0, UiKit.dp(this, 9), 0, UiKit.dp(this, 10));
        card.addView(aliasCount);

        LinearLayout actions = new LinearLayout(this);
        Button edit = secondaryButton(routine == null ? "Reassign / Edit" : "Edit");
        edit.setOnClickListener(v -> startActivity(new Intent(this, CustomCommandEditorActivity.class)
                .putExtra(CustomCommandEditorActivity.EXTRA_COMMAND_ID, command.id)));
        actions.addView(edit, new LinearLayout.LayoutParams(0, UiKit.dp(this, 42), 1));
        Button delete = secondaryButton("Delete");
        delete.setTextColor(Color.rgb(239, 105, 105));
        delete.setOnClickListener(v -> confirmDelete(command));
        LinearLayout.LayoutParams deleteLp = new LinearLayout.LayoutParams(0, UiKit.dp(this, 42), 1);
        deleteLp.leftMargin = UiKit.dp(this, 9);
        actions.addView(delete, deleteLp);
        card.addView(actions);
        return card;
    }

    private void confirmDelete(CustomCommandStore.Command command) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Delete Custom Command?")
                .setMessage("This removes the phrase shortcut. The saved Routine is not deleted.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, which) -> {
                    if (CustomCommandStore.delete(this, command.id)) refresh();
                }).create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(UiKit.dp(this, 17), UiKit.dp(this, 15), UiKit.dp(this, 17), UiKit.dp(this, 15));
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
        button.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 10), UiKit.dp(this, 10), UiKit.dp(this, 10));
        UiKit.pressScale(button);
        return button;
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(UiKit.onAccent(this));
        button.setTextSize(14);
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
