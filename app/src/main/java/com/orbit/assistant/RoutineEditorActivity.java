package com.orbit.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
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

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;

/** Create/edit UI for deterministic saved routines. */
public class RoutineEditorActivity extends Activity {
    public static final String EXTRA_ROUTINE_ID = "routine_id";
    /**
     * A generated but unsaved routine to prefill. No routine exists yet: the name and steps are
     * seeded, everything remains editable, and only Save writes anything to {@link RoutineStore}.
     */
    public static final String EXTRA_ROUTINE_DRAFT = "routine_draft";

    private final List<AssistantReply.Action> workingActions = new ArrayList<>();
    private LinearLayout stepsList;
    private EditText nameField;
    /** Proposed name from a generated draft; empty for a normal new routine. */
    private String draftName = "";
    /** Proposed automation from a generated draft; never scheduled by this screen. */
    private RoutineTriggerDraft draftTrigger;
    private Button addStepButton;
    /**
     * Which branch path a newly configured action belongs to, set by that path's Add action button
     * and cleared as soon as the action lands. -1 means the action is an ordinary step at the end.
     */
    private int pendingBranchCondition = -1;
    private int pendingBranchKind = RoutineBranch.BRANCH_NONE;
    private RoutineStore.Routine existing;
    private boolean dirty;
    private boolean initializing = true;
    private static final int REQ_CONDITION_LOCATION = 883;
    private EditText pendingConditionLatitude;
    private EditText pendingConditionLongitude;
    private Button pendingConditionLocationButton;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.syncTheme(this);
        String id = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_ROUTINE_ID);
        if (id != null && !id.trim().isEmpty()) {
            existing = RoutineStore.findById(this, id);
            if (existing == null) {
                Toast.makeText(this, "That routine is no longer available.", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            workingActions.addAll(RoutineStore.copyActions(existing.actions));
        } else {
            // Opening from a generated draft. Re-validated here rather than trusted, and left
            // entirely unsaved until the user presses Save like any other new routine.
            String payload = getIntent() == null ? null
                    : getIntent().getStringExtra(EXTRA_ROUTINE_DRAFT);
            RoutineDraft draft = RoutineDraft.fromPayload(this, payload);
            if (draft != null) {
                draftName = draft.name;
                workingActions.addAll(RoutineStore.copyActions(draft.actions));
                // Held only in memory. Nothing is scheduled unless the routine is saved and the
                // user then chooses to set the automation up.
                draftTrigger = draft.trigger;
            }
        }

        Window w = getWindow();
        w.setStatusBarColor(UiKit.BG);
        w.setNavigationBarColor(UiKit.BG);
        View content = buildContent();
        setContentView(content);
        UiKit.applyActivityInsets(this, content, true);
        initializing = false;
        dirty = false;
        refreshSteps();
    }

    @Override protected void onResume() {
        super.onResume();
        UiPresence.enter(this);
    }

    @Override protected void onPause() {
        UiPresence.leave(this);
        super.onPause();
    }

    @Override public void onBackPressed() {
        handleBack();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CONDITION_LOCATION && RoutineLocationTriggerScheduler.hasFineLocation(this)) {
            captureConditionLocation();
        }
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(UiKit.BG);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 20);
        page.setPadding(p, UiKit.dp(this, 26), p, UiKit.dp(this, 48));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = iconButton(R.drawable.ic_back, "Back");
        back.setOnClickListener(v -> handleBack());
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 48));
        backLp.rightMargin = UiKit.dp(this, 12);
        header.addView(back, backLp);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(UiKit.text(this, existing == null ? "New routine" : "Edit routine", 25, UiKit.TEXT, true));
        titles.addView(UiKit.text(this, "Saved Action Engine chain", 12, UiKit.MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(header);

        TextView intro = UiKit.text(this,
                "Steps run from top to bottom. An IF condition holds a THEN path and an OTHERWISE "
                        + "path; exactly one of them runs, then the routine carries on with the "
                        + "steps below it.",
                13, UiKit.MUTED, false);
        intro.setLineSpacing(0, 1.12f);
        LinearLayout.LayoutParams introLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        introLp.setMargins(UiKit.dp(this, 2), UiKit.dp(this, 16), UiKit.dp(this, 2), UiKit.dp(this, 14));
        page.addView(intro, introLp);

        page.addView(sectionTitle("NAME"));
        nameField = inputField("Routine name",
                existing != null ? existing.name : draftName, false);
        nameField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(RoutineStore.MAX_NAME_LENGTH)});
        nameField.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int before, int count) {
                if (!initializing) dirty = true;
            }
            public void afterTextChanged(Editable e) {}
        });
        page.addView(nameField, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 54)));

        page.addView(sectionTitle("STEPS"));
        stepsList = new LinearLayout(this);
        stepsList.setOrientation(LinearLayout.VERTICAL);
        page.addView(stepsList);

        addStepButton = secondaryButton("+  Add step");
        addStepButton.setOnClickListener(v -> {
            // This one always appends after everything, so any path the user started from and
            // then abandoned is forgotten here rather than capturing the next action.
            clearPendingBranch();
            showActionPicker(addStepButton);
        });
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48));
        addLp.setMargins(0, UiKit.dp(this, 2), 0, UiKit.dp(this, 18));
        page.addView(addStepButton, addLp);

        Button save = primaryButton(existing == null ? "Save routine" : "Save changes");
        save.setOnClickListener(v -> saveRoutine());
        page.addView(save, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 50)));

        TextView safety = UiKit.text(this,
                "Routine actions are stored only on this device. Enabled Extensions can add reviewed declarative actions; secure setup values stay outside Routine storage.",
                11, UiKit.MUTED, false);
        safety.setGravity(Gravity.CENTER);
        safety.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 14), UiKit.dp(this, 8), 0);
        page.addView(safety);

        return scroll;
    }

    private void refreshSteps() {
        if (stepsList == null) return;
        stepsList.removeAllViews();
        if (workingActions.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(UiKit.text(this, "No steps yet", 15, UiKit.TEXT, true));
            TextView note = UiKit.text(this,
                    "Add the actions Orbit should perform when this routine runs.",
                    12, UiKit.MUTED, false);
            note.setPadding(0, UiKit.dp(this, 5), 0, 0);
            empty.addView(note);
            stepsList.addView(empty, cardLp());
        } else {
            // Laid out as top-level units: an ordinary step, or a whole IF block with its two
            // paths drawn inside it. Nothing here asks the user how many steps a branch covers.
            List<RoutineBranch.Unit> units = RoutineBranch.units(workingActions);
            for (int position = 0; position < units.size(); position++) {
                RoutineBranch.Unit unit = units.get(position);
                stepsList.addView(unit.branch
                        ? branchCard(unit, position + 1)
                        : stepCard(unit.start, workingActions.get(unit.start), position + 1), cardLp());
            }
        }
        if (addStepButton != null) {
            boolean full = workingActions.size() >= RoutineActionCatalog.MAX_STEPS;
            addStepButton.setEnabled(!full);
            addStepButton.setAlpha(full ? 0.5f : 1f);
            addStepButton.setText(full ? "Maximum " + RoutineActionCatalog.MAX_STEPS + " steps" : "+  Add step");
        }
    }

    /**
     * One IF condition drawn as a single block: the condition, the THEN path, and the OTHERWISE
     * path. The paths are sections of the same card rather than separate steps, so which actions
     * belong to which outcome is visible without counting anything.
     */
    private View branchCard(RoutineBranch.Unit unit, int number) {
        int conditionIndex = unit.start;
        AssistantReply.Action condition = workingActions.get(conditionIndex);
        LinearLayout card = card();

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(numberBadge(number), numberLp());

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView tag = UiKit.text(this, "IF", 10, UiKit.accent(this), true);
        tag.setLetterSpacing(0.16f);
        tag.setPadding(0, 0, 0, UiKit.dp(this, 2));
        text.addView(tag);
        TextView title = UiKit.text(this, conditionTitle(condition), 14, UiKit.TEXT, true);
        title.setMaxLines(2);
        text.addView(title);
        header.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        ImageButton more = iconButton(R.drawable.ic_more, "Branch options");
        more.setOnClickListener(v -> showBranchMenu(more, conditionIndex));
        header.addView(more, new LinearLayout.LayoutParams(UiKit.dp(this, 42), UiKit.dp(this, 42)));
        card.addView(header);

        RoutineBranch.Span span = RoutineBranch.spanAt(workingActions, conditionIndex);
        card.addView(pathSection(conditionIndex, RoutineBranch.BRANCH_TRUE, "THEN",
                span == null ? 0 : span.trueStart, span == null ? 0 : span.trueEnd));
        card.addView(pathSection(conditionIndex, RoutineBranch.BRANCH_ELSE, "OTHERWISE",
                span == null ? 0 : span.elseStart, span == null ? 0 : span.elseEnd));

        // Where the branch ends, said in words rather than stored as a step. Only worth showing
        // when something actually follows it.
        if (unit.end < workingActions.size()) {
            TextView continues = UiKit.text(this,
                    "Either way, the routine continues below.", 11, UiKit.MUTED, false);
            LinearLayout.LayoutParams continuesLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            continuesLp.setMargins(0, UiKit.dp(this, 13), 0, 0);
            card.addView(continues, continuesLp);
        }
        return card;
    }

    /**
     * One labelled path inside a branch block, with its own actions and its own Add action.
     *
     * <p>A quiet accent rail down the left ties a path's rows together and tells the two paths
     * apart — THEN reads as the primary outcome, OTHERWISE as the quieter alternative — without
     * turning the card into a diagram.
     */
    private View pathSection(int conditionIndex, int kind, String label, int from, int to) {
        boolean otherwise = kind == RoutineBranch.BRANCH_ELSE;
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams wrapperLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wrapperLp.setMargins(UiKit.dp(this, 13), UiKit.dp(this, 13), 0, 0);
        wrapper.setLayoutParams(wrapperLp);

        View rail = new View(this);
        rail.setBackground(UiKit.rounded(
                UiKit.withAlpha(UiKit.accent(this), otherwise ? 60 : 120), 999, this));
        LinearLayout.LayoutParams railLp = new LinearLayout.LayoutParams(
                UiKit.dp(this, 2), ViewGroup.LayoutParams.MATCH_PARENT);
        railLp.rightMargin = UiKit.dp(this, 12);
        wrapper.addView(rail, railLp);

        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);

        TextView heading = UiKit.text(this, label, 10,
                otherwise ? UiKit.MUTED : UiKit.accent(this), true);
        heading.setLetterSpacing(0.16f);
        heading.setPadding(0, 0, 0, UiKit.dp(this, 5));
        section.addView(heading);

        if (to <= from) {
            // Not "Empty": the path is perfectly valid, it simply has no actions, which means a
            // false condition does nothing here and the routine carries straight on.
            TextView none = UiKit.text(this, "None", 13, UiKit.MUTED, false);
            none.setPadding(0, UiKit.dp(this, 2), 0, UiKit.dp(this, 4));
            section.addView(none);
        } else {
            for (int i = from; i < to; i++) {
                section.addView(pathRow(i, workingActions.get(i)));
            }
        }

        boolean canAdd = RoutineBranch.canAddTo(workingActions, conditionIndex, kind);
        Button add = secondaryButton("+  Add action");
        add.setTextSize(13);
        add.setEnabled(canAdd);
        add.setAlpha(canAdd ? 1f : 0.5f);
        add.setOnClickListener(v -> {
            if (!RoutineBranch.canAddTo(workingActions, conditionIndex, kind)) {
                Toast.makeText(this, addBlockedReason(conditionIndex, kind), Toast.LENGTH_SHORT).show();
                return;
            }
            pendingBranchCondition = conditionIndex;
            pendingBranchKind = kind;
            showActionPicker(add);
        });
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 38));
        addLp.setMargins(0, UiKit.dp(this, 5), 0, 0);
        section.addView(add, addLp);

        wrapper.addView(section, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return wrapper;
    }

    private String addBlockedReason(int conditionIndex, int kind) {
        if (workingActions.size() >= RoutineActionCatalog.MAX_STEPS) {
            return "Routine step limit reached.";
        }
        if (RoutineBranch.pathSize(workingActions, conditionIndex, kind)
                >= RoutineBranch.MAX_BRANCH_STEPS) {
            return "A path can hold up to " + RoutineBranch.MAX_BRANCH_STEPS + " actions.";
        }
        return "Add a THEN action first, so OTHERWISE has something to be an alternative to.";
    }

    /** One action inside a branch path. Compact, and never numbered: only one path ever runs. */
    private View pathRow(int index, AssistantReply.Action action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, UiKit.dp(this, 3), 0, UiKit.dp(this, 3));

        View dot = new View(this);
        dot.setBackground(UiKit.rounded(UiKit.withAlpha(UiKit.accent(this), 150), 999, this));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(UiKit.dp(this, 5), UiKit.dp(this, 5));
        dotLp.setMargins(0, 0, UiKit.dp(this, 10), 0);
        row.addView(dot, dotLp);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView title = UiKit.text(this, RoutineActionCatalog.title(action), 13, UiKit.TEXT, false);
        title.setMaxLines(2);
        text.addView(title);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        ImageButton more = iconButton(R.drawable.ic_more, "Action options");
        more.setOnClickListener(v -> showStepMenu(more, index));
        row.addView(more, new LinearLayout.LayoutParams(UiKit.dp(this, 36), UiKit.dp(this, 36)));
        return row;
    }

    /** The condition's own description, without the branch bookkeeping the editor now owns. */
    private String conditionTitle(AssistantReply.Action condition) {
        String title = RoutineActionCatalog.title(condition);
        int marker = title.indexOf(" · with ELSE");
        return marker < 0 ? title : title.substring(0, marker);
    }

    private TextView numberBadge(int number) {
        TextView badge = UiKit.text(this, String.valueOf(number), 13, UiKit.onAccent(this), true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(UiKit.rounded(UiKit.accent(this), 999, this));
        return badge;
    }

    private LinearLayout.LayoutParams numberLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                UiKit.dp(this, 30), UiKit.dp(this, 30));
        lp.rightMargin = UiKit.dp(this, 12);
        return lp;
    }

    private View stepCard(int index, AssistantReply.Action action, int number) {
        LinearLayout card = card();
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(numberBadge(number), numberLp());

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView title = UiKit.text(this, RoutineActionCatalog.title(action), 14, UiKit.TEXT, true);
        title.setMaxLines(1);
        text.addView(title);
        TextView summary = UiKit.text(this, RoutineActionCatalog.summary(action), 11, UiKit.MUTED, false);
        summary.setMaxLines(2);
        summary.setPadding(0, UiKit.dp(this, 3), 0, 0);
        text.addView(summary);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        ImageButton more = iconButton(R.drawable.ic_more, "Step options");
        more.setOnClickListener(v -> showStepMenu(more, index));
        row.addView(more, new LinearLayout.LayoutParams(UiKit.dp(this, 42), UiKit.dp(this, 42)));
        card.addView(row);
        return card;
    }

    /** Options for a whole IF block. Moving it carries both paths with it. */
    private void showBranchMenu(View anchor, int conditionIndex) {
        List<String> labels = new ArrayList<>();
        labels.add("Edit condition");
        if (RoutineBranch.canMove(workingActions, conditionIndex, -1)) labels.add("Move up");
        if (RoutineBranch.canMove(workingActions, conditionIndex, 1)) labels.add("Move down");
        labels.add("Remove branch");
        UiKit.showOrbitMenu(this, anchor, labels.toArray(new String[0]), -1, (choice, label) -> {
            if ("Edit condition".equals(label)) {
                configureAction(RoutineActionCatalog.IF_CONDITION, conditionIndex);
            } else if ("Move up".equals(label)) {
                if (RoutineBranch.move(workingActions, conditionIndex, -1)) markDirtyAndRefresh();
            } else if ("Move down".equals(label)) {
                if (RoutineBranch.move(workingActions, conditionIndex, 1)) markDirtyAndRefresh();
            } else if ("Remove branch".equals(label)) {
                confirmRemoveBranch(conditionIndex);
            }
        });
    }

    /**
     * Removing an IF takes both paths with it. Leaving them behind as ordinary steps would run a
     * THEN and its OTHERWISE one after the other, which is usually two opposite actions, so a
     * branch that actually holds actions asks first.
     *
     * <p>v0.7.5.1 built and styled that dialog but never called {@code show()}, which
     * {@link UiKit#styleOrbitDialog} does not do for you — it only prepares the window and
     * registers an on-show listener. The dialog was created and immediately discarded, so tapping
     * Remove branch on a populated branch did nothing at all. Package-visible so the tests can
     * drive this exact path rather than a copy of it.
     */
    void confirmRemoveBranch(int conditionIndex) {
        int actions = RoutineBranch.branchActionCount(workingActions, conditionIndex);
        if (actions == 0) {
            // Nothing to lose but the condition itself, so there is nothing worth asking about.
            if (RoutineBranch.removeBranch(workingActions, conditionIndex)) markDirtyAndRefresh();
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Remove branch?")
                .setMessage("This will remove the IF condition and all actions inside its THEN "
                        + "and OTHERWISE paths.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (d, which) -> {
                    if (RoutineBranch.removeBranch(workingActions, conditionIndex)) {
                        markDirtyAndRefresh();
                    }
                })
                .create();
        styleOrbitDialog(dialog, true, null);
        dialog.show();
    }

    /** The steps currently being edited. Package-visible so tests can drive the real editor. */
    List<AssistantReply.Action> editingSteps() {
        return workingActions;
    }

    private void showStepMenu(View anchor, int index) {
        List<String> labels = new ArrayList<>();
        if (RoutineActionCatalog.isConfigurable(workingActions.get(index).type)) labels.add("Edit");
        if (RoutineBranch.canMove(workingActions, index, -1)) labels.add("Move up");
        if (RoutineBranch.canMove(workingActions, index, 1)) labels.add("Move down");
        if (workingActions.size() < RoutineActionCatalog.MAX_STEPS) labels.add("Duplicate");
        labels.add("Remove");
        String[] menu = labels.toArray(new String[0]);
        UiKit.showOrbitMenu(this, anchor, menu, -1, (choice, label) -> {
            if ("Edit".equals(label)) {
                configureAction(workingActions.get(index).type, index);
            } else if ("Move up".equals(label)) {
                if (RoutineBranch.move(workingActions, index, -1)) markDirtyAndRefresh();
            } else if ("Move down".equals(label)) {
                if (RoutineBranch.move(workingActions, index, 1)) markDirtyAndRefresh();
            } else if ("Duplicate".equals(label)) {
                if (RoutineBranch.duplicateStep(workingActions, index)) markDirtyAndRefresh();
                else Toast.makeText(this, "There is no room to duplicate that action.",
                        Toast.LENGTH_SHORT).show();
            } else if ("Remove".equals(label)) {
                if (RoutineBranch.removeStep(workingActions, index)) markDirtyAndRefresh();
                else Toast.makeText(this,
                        "A THEN path needs at least one action. Remove the branch to delete it.",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showActionPicker(View anchor) {
        if (workingActions.size() >= RoutineActionCatalog.MAX_STEPS) return;
        // A path holds plain actions only. One clear IF is the limit in this release, so a
        // condition is simply not offered from inside a branch rather than being refused later.
        boolean insideBranch = pendingBranchKind != RoutineBranch.BRANCH_NONE;
        List<String> labels = new ArrayList<>();
        List<String> types = new ArrayList<>();
        for (int i = 0; i < RoutineActionCatalog.TYPES.length; i++) {
            if (insideBranch && RoutineActionCatalog.IF_CONDITION.equals(RoutineActionCatalog.TYPES[i])) {
                continue;
            }
            types.add(RoutineActionCatalog.TYPES[i]);
            labels.add(RoutineActionCatalog.LABELS[i]);
        }
        List<OrbitExtensionStore.ActionChoice> extensions = OrbitExtensionStore.enabledActions(this);
        int builtInCount = labels.size();
        if (!extensions.isEmpty()) labels.add("Extensions");
        UiKit.showOrbitMenu(this, anchor, labels.toArray(new String[0]), -1, (index, label) -> {
            if (index >= 0 && index < types.size()) {
                configureAction(types.get(index), -1);
            } else if (index == builtInCount) {
                anchor.post(() -> showExtensionActionPicker(anchor, -1));
            } else {
                clearPendingBranch();
            }
        });
    }

    private void showExtensionActionPicker(View anchor, int editIndex) {
        List<OrbitExtensionStore.ActionChoice> choices = OrbitExtensionStore.enabledActions(this);
        if (choices.isEmpty()) {
            Toast.makeText(this, "No enabled and configured extension actions are available.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[choices.size()];
        for (int i = 0; i < choices.size(); i++) {
            OrbitExtensionStore.ActionChoice choice = choices.get(i);
            labels[i] = choice.extension.name + " · " + choice.action.name;
        }
        UiKit.showOrbitMenu(this, anchor, labels, -1, (index, label) -> {
            if (index < 0 || index >= choices.size()) return;
            OrbitExtensionStore.ActionChoice choice = choices.get(index);
            if (choice.action.parameters.isEmpty()) {
                putAction(editIndex, RoutineActionCatalog.extensionAction(
                        choice.extension, choice.action));
            } else {
                showExtensionParameterDialog(choice.extension, choice.action,
                        new JSONObject(), editIndex);
            }
        });
    }

    private void configureAction(String type, int editIndex) {
        if (type == null) return;
        switch (type) {
            case RoutineActionCatalog.IF_CONDITION:
                showConditionDialog(editIndex);
                break;
            case RoutineActionCatalog.OPEN_APP:
                showOpenAppDialog(editIndex);
                break;
            case RoutineActionCatalog.SET_BRIGHTNESS:
                showPercentDialog(type, editIndex, "Set brightness", "Brightness percentage");
                break;
            case RoutineActionCatalog.SET_VOLUME:
                showPercentDialog(type, editIndex, "Set media volume", "Media volume percentage");
                break;
            case RoutineActionCatalog.SET_DND:
                showToggleDialog(type, editIndex, "Do Not Disturb", "Enabled", "Disabled");
                break;
            case RoutineActionCatalog.FLASHLIGHT:
                showToggleDialog(type, editIndex, "Flashlight", "On", "Off");
                break;
            case RoutineActionCatalog.SET_TIMER:
                showTimerDialog(editIndex);
                break;
            case RoutineActionCatalog.SET_ALARM:
                showAlarmDialog(editIndex);
                break;
            case RoutineActionCatalog.OPEN_SETTINGS:
            case RoutineActionCatalog.OPEN_INTERNET_PANEL:
            case RoutineActionCatalog.OPEN_BLUETOOTH_SETTINGS:
                putAction(editIndex, new AssistantReply.Action(type, new JSONObject(), false));
                break;
            case RoutineActionCatalog.EXTENSION_ACTION:
                showExistingExtensionActionDialog(editIndex);
                break;
            default:
                Toast.makeText(this, "That action is not available for routines yet.", Toast.LENGTH_SHORT).show();
        }
    }

    private void showExistingExtensionActionDialog(int editIndex) {
        AssistantReply.Action saved = actionAt(editIndex, RoutineActionCatalog.EXTENSION_ACTION);
        if (saved == null || saved.params == null) {
            showExtensionActionPicker(addStepButton, editIndex);
            return;
        }
        OrbitExtensionStore.Installed installed = OrbitExtensionStore.find(this,
                saved.params.optString("extensionId", ""));
        OrbitExtension.Action action = installed == null ? null : installed.extension.findAction(
                saved.params.optString("actionId", ""));
        if (action == null) {
            Toast.makeText(this, "Extension action unavailable", Toast.LENGTH_SHORT).show();
            return;
        }
        if (action.parameters.isEmpty()) {
            showExtensionActionPicker(addStepButton, editIndex);
            return;
        }
        showExtensionParameterDialog(installed.extension, action,
                saved.params.optJSONObject("actionParameters"), editIndex);
    }

    private void showExtensionParameterDialog(OrbitExtension extension,
            OrbitExtension.Action action, JSONObject current, int editIndex) {
        JSONObject old = current == null ? new JSONObject() : current;
        LinearLayout form = dialogForm();
        Map<String, EditText> textFields = new LinkedHashMap<>();
        Map<String, int[]> choiceIndexes = new LinkedHashMap<>();

        for (OrbitExtension.ActionParameter parameter : action.parameters) {
            String required = parameter.required ? " · Required" : " · Optional";
            form.addView(dialogLabel(parameter.label + required));
            if (!parameter.description.isEmpty()) {
                TextView description = UiKit.text(this, parameter.description,
                        11, UiKit.MUTED, false);
                description.setPadding(UiKit.dp(this, 2), 0, 0, UiKit.dp(this, 6));
                form.addView(description);
            }
            String existing = old.optString(parameter.id, parameter.defaultValue);
            if (OrbitExtension.PARAM_CHOICE.equals(parameter.type)) {
                String[] labels = new String[parameter.choices.size()];
                int selected = 0;
                for (int i = 0; i < parameter.choices.size(); i++) {
                    OrbitExtension.Choice choice = parameter.choices.get(i);
                    labels[i] = choice.label;
                    if (choice.value.equals(existing)) selected = i;
                }
                int[] index = {selected};
                choiceIndexes.put(parameter.id, index);
                form.addView(dialogSelector(labels, index), selectorLp());
            } else {
                EditText input = inputField(parameter.label, existing, false);
                input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(parameter.maxLength)});
                if (parameter.maxLength > 160) {
                    input.setSingleLine(false);
                    input.setMinLines(2);
                    input.setMaxLines(4);
                    input.setGravity(Gravity.TOP | Gravity.START);
                    input.setPadding(UiKit.dp(this, 15), UiKit.dp(this, 12),
                            UiKit.dp(this, 15), UiKit.dp(this, 10));
                }
                textFields.put(parameter.id, input);
                int height = parameter.maxLength > 160 ? UiKit.dp(this, 92) : UiKit.dp(this, 52);
                form.addView(input, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, height));
            }
        }

        showFormDialog((editIndex < 0 ? "Add " : "Edit ") + action.name, form,
                editIndex < 0 ? "Add" : "Save", () -> {
                    try {
                        JSONObject values = new JSONObject();
                        for (OrbitExtension.ActionParameter parameter : action.parameters) {
                            if (OrbitExtension.PARAM_CHOICE.equals(parameter.type)) {
                                int[] selected = choiceIndexes.get(parameter.id);
                                if (selected != null && !parameter.choices.isEmpty()) {
                                    int safe = Math.max(0, Math.min(
                                            parameter.choices.size() - 1, selected[0]));
                                    values.put(parameter.id, parameter.choices.get(safe).value);
                                }
                            } else {
                                EditText input = textFields.get(parameter.id);
                                String value = input == null ? "" : input.getText().toString();
                                if (!value.trim().isEmpty()) values.put(parameter.id, value);
                            }
                        }
                        JSONObject safe = OrbitExtensionV2.validateAndNormalizeParameters(
                                action, values, true);
                        putAction(editIndex, RoutineActionCatalog.extensionAction(
                                extension, action, safe));
                        return true;
                    } catch (IllegalArgumentException e) {
                        return formError(e.getMessage() == null
                                ? "Check the extension action values." : e.getMessage());
                    } catch (Exception e) {
                        return formError("Could not save that extension action.");
                    }
                });
    }

    private void showConditionDialog(int editIndex) {
        AssistantReply.Action old = actionAt(editIndex, RoutineActionCatalog.IF_CONDITION);
        JSONObject oldParams = old == null || old.params == null ? new JSONObject() : old.params;
        String oldMode = old == null ? RoutineConditionEvaluator.MODE_TIME : RoutineConditionEvaluator.mode(old);
        final int[] mode = {RoutineConditionEvaluator.MODE_LOCATION.equals(oldMode) ? 1 :
                RoutineConditionEvaluator.MODE_TIME_AND_LOCATION.equals(oldMode) ? 2 : 0};
        int startMinute = oldParams.optInt("startMinute", 18 * 60);
        int endMinute = oldParams.optInt("endMinute", 22 * 60);
        int startH24 = Math.max(0, Math.min(23, startMinute / 60));
        int endH24 = Math.max(0, Math.min(23, endMinute / 60));
        int startDisplay = startH24 % 12; if (startDisplay == 0) startDisplay = 12;
        int endDisplay = endH24 % 12; if (endDisplay == 0) endDisplay = 12;
        final int[] startAp = {startH24 >= 12 ? 1 : 0};
        final int[] endAp = {endH24 >= 12 ? 1 : 0};
        // The branch shape is carried straight through: editing what the condition checks must
        // never change which actions sit on its THEN and OTHERWISE paths.
        final int keptTrueSteps = old == null ? 1 : RoutineBranch.trueSteps(old);
        final int keptElseSteps = old == null ? 0 : RoutineBranch.elseSteps(old);
        float oldRadius = (float) oldParams.optDouble("radiusMeters", 200d);
        final float[] radii = {100f, 200f, 300f, 500f, 1000f, 2000f, 5000f};
        String[] radiusLabels = {"100 m", "200 m", "300 m", "500 m", "1 km", "2 km", "5 km"};
        int radiusIndex = 1;
        for (int i = 0; i < radii.length; i++) if (Math.abs(radii[i] - oldRadius) < 0.5f) { radiusIndex = i; break; }
        final int[] radius = {radiusIndex};

        LinearLayout form = dialogForm();
        form.addView(dialogLabel("Condition"));

        LinearLayout timeGroup = new LinearLayout(this);
        timeGroup.setOrientation(LinearLayout.VERTICAL);
        LinearLayout locationGroup = new LinearLayout(this);
        locationGroup.setOrientation(LinearLayout.VERTICAL);

        LinearLayout modeSelector = dialogSelector(new String[]{"Time", "Location", "Time + location"}, mode, () -> {
            timeGroup.setVisibility(mode[0] == 1 ? View.GONE : View.VISIBLE);
            locationGroup.setVisibility(mode[0] == 0 ? View.GONE : View.VISIBLE);
        });
        form.addView(modeSelector, selectorLp());

        timeGroup.addView(dialogLabel("Start time"));
        LinearLayout startRow = new LinearLayout(this);
        EditText startHour = numericField("Hour", String.valueOf(startDisplay));
        EditText startMin = numericField("Minute", String.format(Locale.US, "%02d", Math.max(0, Math.min(59, startMinute % 60))));
        startRow.addView(startHour, new LinearLayout.LayoutParams(0, UiKit.dp(this, 52), 1));
        LinearLayout.LayoutParams minLp = new LinearLayout.LayoutParams(0, UiKit.dp(this, 52), 1); minLp.leftMargin = UiKit.dp(this, 7);
        startRow.addView(startMin, minLp);
        timeGroup.addView(startRow);
        LinearLayout startApSelector = dialogSelector(new String[]{"AM", "PM"}, startAp);
        LinearLayout.LayoutParams startApLp = selectorLp(); startApLp.topMargin = UiKit.dp(this, 7);
        timeGroup.addView(startApSelector, startApLp);

        timeGroup.addView(dialogLabel("End time"));
        LinearLayout endRow = new LinearLayout(this);
        EditText endHour = numericField("Hour", String.valueOf(endDisplay));
        EditText endMin = numericField("Minute", String.format(Locale.US, "%02d", Math.max(0, Math.min(59, endMinute % 60))));
        endRow.addView(endHour, new LinearLayout.LayoutParams(0, UiKit.dp(this, 52), 1));
        LinearLayout.LayoutParams endMinLp = new LinearLayout.LayoutParams(0, UiKit.dp(this, 52), 1); endMinLp.leftMargin = UiKit.dp(this, 7);
        endRow.addView(endMin, endMinLp);
        timeGroup.addView(endRow);
        LinearLayout endApSelector = dialogSelector(new String[]{"AM", "PM"}, endAp);
        LinearLayout.LayoutParams endApLp = selectorLp(); endApLp.topMargin = UiKit.dp(this, 7);
        timeGroup.addView(endApSelector, endApLp);
        TextView overnight = UiKit.text(this, "Time windows can cross midnight. Using the same start and end time means all day.", 11, UiKit.MUTED, false);
        overnight.setPadding(0, UiKit.dp(this, 6), 0, 0);
        timeGroup.addView(overnight);
        form.addView(timeGroup);

        locationGroup.addView(dialogLabel("Saved place (optional)"));
        Button savedPlaceButton = secondaryButton("Choose saved place");
        locationGroup.addView(savedPlaceButton, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 44)));

        locationGroup.addView(dialogLabel("Location name"));
        EditText locationName = inputField("Home, Work, Gym…", oldParams.optString("locationName", ""), false);
        locationGroup.addView(locationName, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 52)));
        locationGroup.addView(dialogLabel("Coordinates"));
        LinearLayout coords = new LinearLayout(this);
        EditText latitude = decimalField("Latitude", old == null ? "" : coordinate(oldParams.optDouble("latitude", Double.NaN)));
        EditText longitude = decimalField("Longitude", old == null ? "" : coordinate(oldParams.optDouble("longitude", Double.NaN)));
        savedPlaceButton.setOnClickListener(v -> {
            List<SavedPlaceStore.Place> places = SavedPlaceStore.list(this);
            if (places.isEmpty()) {
                Toast.makeText(this, "No saved places yet. Add one first.", Toast.LENGTH_SHORT).show();
                startActivity(new Intent(this, SavedPlacesActivity.class));
                return;
            }
            String[] labels = new String[places.size() + 1];
            for (int i = 0; i < places.size(); i++) labels[i] = places.get(i).name;
            labels[places.size()] = "Manage saved places…";
            UiKit.showOrbitMenu(this, savedPlaceButton, labels, -1, (index, label) -> {
                if (index >= places.size()) {
                    startActivity(new Intent(this, SavedPlacesActivity.class));
                    return;
                }
                SavedPlaceStore.Place place = places.get(index);
                savedPlaceButton.setText(place.name);
                locationName.setText(place.name);
                latitude.setText(coordinate(place.latitude));
                longitude.setText(coordinate(place.longitude));
            });
        });
        coords.addView(latitude, new LinearLayout.LayoutParams(0, UiKit.dp(this, 52), 1));
        LinearLayout.LayoutParams lonLp = new LinearLayout.LayoutParams(0, UiKit.dp(this, 52), 1); lonLp.leftMargin = UiKit.dp(this, 7);
        coords.addView(longitude, lonLp);
        locationGroup.addView(coords);
        Button current = secondaryButton("Use my current location");
        current.setOnClickListener(v -> {
            savedPlaceButton.setText("Choose saved place");
            pendingConditionLatitude = latitude;
            pendingConditionLongitude = longitude;
            pendingConditionLocationButton = current;
            captureConditionLocation();
        });
        LinearLayout.LayoutParams currentLp = new LinearLayout.LayoutParams(-1, UiKit.dp(this, 44)); currentLp.topMargin = UiKit.dp(this, 8);
        locationGroup.addView(current, currentLp);
        locationGroup.addView(dialogLabel("Radius"));
        locationGroup.addView(dialogSelector(radiusLabels, radius), selectorLp());
        TextView locationHelp = UiKit.text(this, "Location conditions check the phone's current system location when this Routine reaches the IF step.", 11, UiKit.MUTED, false);
        locationHelp.setPadding(0, UiKit.dp(this, 6), 0, 0);
        locationGroup.addView(locationHelp);
        form.addView(locationGroup);

        // No step counts here any more. This dialog describes the condition; which actions belong
        // to THEN and which to OTHERWISE is shown and edited on the branch itself, and Orbit keeps
        // the stored counts in step with it.
        TextView behavior = UiKit.text(this,
                "Add the actions for THEN and OTHERWISE on the branch itself. Exactly one path "
                        + "runs, then Orbit continues with the rest of the Routine.",
                11, UiKit.MUTED, false);
        behavior.setLineSpacing(0, 1.1f);
        behavior.setPadding(0, UiKit.dp(this, 12), 0, 0);
        form.addView(behavior);

        timeGroup.setVisibility(mode[0] == 1 ? View.GONE : View.VISIBLE);
        locationGroup.setVisibility(mode[0] == 0 ? View.GONE : View.VISIBLE);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(form, new ScrollView.LayoutParams(-1, -2));
        showFormDialog(editIndex < 0 ? "Add IF condition" : "Edit IF condition", scroll,
                editIndex < 0 ? "Add" : "Save", () -> {
                    try {
                        JSONObject params = new JSONObject();
                        String selectedMode = mode[0] == 1 ? RoutineConditionEvaluator.MODE_LOCATION :
                                mode[0] == 2 ? RoutineConditionEvaluator.MODE_TIME_AND_LOCATION : RoutineConditionEvaluator.MODE_TIME;
                        params.put("mode", selectedMode).put("nextSteps", keptTrueSteps);
                        // Written only when an ELSE path actually holds actions, so a condition
                        // without one stays byte-identical to what every earlier release wrote.
                        if (keptElseSteps > 0) {
                            params.put(RoutineBranch.KEY_ELSE_STEPS, keptElseSteps);
                        }

                        if (mode[0] != 1) {
                            Integer sh = parseInt(startHour.getText().toString());
                            Integer sm = parseInt(startMin.getText().toString());
                            Integer eh = parseInt(endHour.getText().toString());
                            Integer em = parseInt(endMin.getText().toString());
                            if (sh == null || sh < 1 || sh > 12 || eh == null || eh < 1 || eh > 12 ||
                                    sm == null || sm < 0 || sm > 59 || em == null || em < 0 || em > 59) {
                                return formError("Use valid start and end times.");
                            }
                            int sh24 = sh % 12; if (startAp[0] == 1) sh24 += 12;
                            int eh24 = eh % 12; if (endAp[0] == 1) eh24 += 12;
                            params.put("startMinute", sh24 * 60 + sm).put("endMinute", eh24 * 60 + em);
                        }

                        if (mode[0] != 0) {
                            Double lat = parseDouble(latitude.getText().toString());
                            Double lon = parseDouble(longitude.getText().toString());
                            if (lat == null || lat < -90d || lat > 90d || lon == null || lon < -180d || lon > 180d) {
                                return formError("Enter valid latitude and longitude coordinates.");
                            }
                            params.put("locationName", locationName.getText().toString().trim())
                                    .put("latitude", lat).put("longitude", lon)
                                    .put("radiusMeters", radii[radius[0]]);
                        }
                        putAction(editIndex, new AssistantReply.Action(RoutineActionCatalog.IF_CONDITION, params, false));
                        pendingConditionLatitude = null;
                        pendingConditionLongitude = null;
                        pendingConditionLocationButton = null;
                        return true;
                    } catch (Exception e) {
                        return formError("Could not save that condition.");
                    }
                });
    }

    private void captureConditionLocation() {
        if (pendingConditionLatitude == null || pendingConditionLongitude == null) return;
        if (!RoutineLocationTriggerScheduler.hasFineLocation(this)) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION}, REQ_CONDITION_LOCATION);
            return;
        }
        if (!RoutineLocationTriggerScheduler.isLocationEnabled(this)) {
            Toast.makeText(this, "Turn on Android location first.", Toast.LENGTH_SHORT).show();
            RoutineLocationTriggerScheduler.openLocationServices(this);
            return;
        }
        Location cached = RoutineLocationTriggerScheduler.bestLastKnownLocation(this);
        if (cached != null && System.currentTimeMillis() - cached.getTime() <= 120_000L) {
            applyConditionLocation(cached);
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) return;
        String provider = null;
        try { if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) provider = LocationManager.NETWORK_PROVIDER; } catch (Exception ignored) {}
        try { if (provider == null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) provider = LocationManager.GPS_PROVIDER; } catch (Exception ignored) {}
        if (provider == null) {
            if (cached != null) applyConditionLocation(cached);
            else Toast.makeText(this, "Could not find an enabled location provider.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (pendingConditionLocationButton != null) {
            pendingConditionLocationButton.setEnabled(false);
            pendingConditionLocationButton.setText("Finding location…");
        }
        final String chosenProvider = provider;
        final Location fallback = cached;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(chosenProvider, null, getMainExecutor(), location -> {
                    if (location != null) applyConditionLocation(location);
                    else if (fallback != null) applyConditionLocation(fallback);
                    else conditionLocationFailed();
                });
            } else {
                lm.requestSingleUpdate(chosenProvider, new LocationListener() {
                    @Override public void onLocationChanged(Location location) { if (location != null) applyConditionLocation(location); else if (fallback != null) applyConditionLocation(fallback); else conditionLocationFailed(); }
                    @Override public void onProviderDisabled(String provider) { if (fallback != null) applyConditionLocation(fallback); else conditionLocationFailed(); }
                    @Override public void onProviderEnabled(String provider) {}
                    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
                }, Looper.getMainLooper());
            }
        } catch (SecurityException ignored) {
            conditionLocationFailed();
        } catch (Exception ignored) {
            if (cached != null) applyConditionLocation(cached); else conditionLocationFailed();
        }
    }

    private void applyConditionLocation(Location location) {
        runOnUiThread(() -> {
            if (pendingConditionLatitude != null) pendingConditionLatitude.setText(coordinate(location.getLatitude()));
            if (pendingConditionLongitude != null) pendingConditionLongitude.setText(coordinate(location.getLongitude()));
            if (pendingConditionLocationButton != null) {
                pendingConditionLocationButton.setEnabled(true);
                pendingConditionLocationButton.setText("Use my current location");
            }
        });
    }

    private void conditionLocationFailed() {
        runOnUiThread(() -> {
            if (pendingConditionLocationButton != null) {
                pendingConditionLocationButton.setEnabled(true);
                pendingConditionLocationButton.setText("Use my current location");
            }
            Toast.makeText(this, "Orbit could not get the current location.", Toast.LENGTH_SHORT).show();
        });
    }

    private String coordinate(double value) {
        if (Double.isNaN(value)) return "";
        return String.format(Locale.US, "%.6f", value);
    }

    private EditText decimalField(String hint, String value) {
        EditText e = inputField(hint, value, false);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        return e;
    }

    private Double parseDouble(String raw) {
        try { return Double.parseDouble(raw == null ? "" : raw.trim()); }
        catch (Exception ignored) { return null; }
    }

    private void showOpenAppDialog(int editIndex) {
        AssistantReply.Action existingAction = actionAt(editIndex, RoutineActionCatalog.OPEN_APP);
        String current = existingAction == null ? "" : existingAction.params.optString("app", "");
        LinearLayout form = dialogForm();
        form.addView(dialogLabel("App name"));
        EditText app = inputField("Spotify, YouTube, Overwatch…", current, false);
        form.addView(app, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 52)));
        TextView help = UiKit.text(this, "Orbit resolves the installed app when the routine runs.", 11, UiKit.MUTED, false);
        help.setPadding(0, UiKit.dp(this, 7), 0, 0);
        form.addView(help);
        showFormDialog(editIndex < 0 ? "Add Open app" : "Edit Open app", form,
                editIndex < 0 ? "Add" : "Save", () -> {
                    String value = app.getText().toString().trim();
                    if (value.isEmpty()) return formError("Enter an app name.");
                    try {
                        putAction(editIndex, new AssistantReply.Action(RoutineActionCatalog.OPEN_APP,
                                new JSONObject().put("app", value), false));
                        return true;
                    } catch (Exception e) { return formError("Could not save that app action."); }
                });
    }

    private void showPercentDialog(String type, int editIndex, String title, String label) {
        AssistantReply.Action old = actionAt(editIndex, type);
        int current = old == null ? (RoutineActionCatalog.SET_BRIGHTNESS.equals(type) ? 50 : 30)
                : old.params.optInt("percent", 50);
        LinearLayout form = dialogForm();
        form.addView(dialogLabel(label + " (0–100)"));
        EditText percent = numericField("0–100", String.valueOf(current));
        form.addView(percent, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 52)));
        showFormDialog((editIndex < 0 ? "Add " : "Edit ") + title, form,
                editIndex < 0 ? "Add" : "Save", () -> {
                    Integer value = parseInt(percent.getText().toString());
                    if (value == null || value < 0 || value > 100) return formError("Use a percentage from 0 to 100.");
                    try {
                        putAction(editIndex, new AssistantReply.Action(type,
                                new JSONObject().put("percent", value), false));
                        return true;
                    } catch (Exception e) { return formError("Could not save that value."); }
                });
    }

    private void showToggleDialog(String type, int editIndex, String title, String onLabel, String offLabel) {
        AssistantReply.Action old = actionAt(editIndex, type);
        boolean defaultValue = true;
        boolean current = old == null ? defaultValue :
                (RoutineActionCatalog.SET_DND.equals(type) ? old.params.optBoolean("enabled", true) : old.params.optBoolean("on", true));
        final int[] selected = {current ? 0 : 1};
        LinearLayout form = dialogForm();
        form.addView(dialogLabel("State"));
        LinearLayout selector = dialogSelector(new String[]{onLabel, offLabel}, selected);
        form.addView(selector, selectorLp());
        showFormDialog((editIndex < 0 ? "Add " : "Edit ") + title, form,
                editIndex < 0 ? "Add" : "Save", () -> {
                    try {
                        JSONObject params = new JSONObject();
                        if (RoutineActionCatalog.SET_DND.equals(type)) params.put("enabled", selected[0] == 0);
                        else params.put("on", selected[0] == 0);
                        putAction(editIndex, new AssistantReply.Action(type, params, false));
                        return true;
                    } catch (Exception e) { return formError("Could not save that state."); }
                });
    }

    private void showTimerDialog(int editIndex) {
        AssistantReply.Action old = actionAt(editIndex, RoutineActionCatalog.SET_TIMER);
        int seconds = old == null ? 300 : Math.max(1, old.params.optInt("seconds", 300));
        int unitIndex;
        int amount;
        if (seconds % 3600 == 0) { unitIndex = 2; amount = seconds / 3600; }
        else if (seconds % 60 == 0) { unitIndex = 1; amount = seconds / 60; }
        else { unitIndex = 0; amount = seconds; }
        final int[] unit = {unitIndex};

        LinearLayout form = dialogForm();
        form.addView(dialogLabel("Duration"));
        EditText amountField = numericField("Amount", String.valueOf(amount));
        form.addView(amountField, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 52)));
        LinearLayout unitSelector = dialogSelector(new String[]{"Seconds", "Minutes", "Hours"}, unit);
        LinearLayout.LayoutParams unitLp = selectorLp();
        unitLp.topMargin = UiKit.dp(this, 8);
        form.addView(unitSelector, unitLp);
        form.addView(dialogLabel("Optional label"));
        EditText label = inputField("Orbit timer", old == null ? "" : old.params.optString("label", ""), false);
        form.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 52)));

        showFormDialog(editIndex < 0 ? "Add Timer" : "Edit Timer", form,
                editIndex < 0 ? "Add" : "Save", () -> {
                    Integer value = parseInt(amountField.getText().toString());
                    if (value == null || value <= 0) return formError("Enter a duration greater than zero.");
                    long multiplier = unit[0] == 2 ? 3600L : unit[0] == 1 ? 60L : 1L;
                    long total = value * multiplier;
                    if (total > Integer.MAX_VALUE) return formError("That timer is too long.");
                    try {
                        String timerLabel = label.getText().toString().trim();
                        JSONObject p = new JSONObject().put("seconds", (int) total)
                                .put("label", timerLabel.isEmpty() ? "Orbit timer" : timerLabel);
                        putAction(editIndex, new AssistantReply.Action(RoutineActionCatalog.SET_TIMER, p, false));
                        return true;
                    } catch (Exception e) { return formError("Could not save that timer."); }
                });
    }

    private void showAlarmDialog(int editIndex) {
        AssistantReply.Action old = actionAt(editIndex, RoutineActionCatalog.SET_ALARM);
        int hour24 = old == null ? 8 : Math.max(0, Math.min(23, old.params.optInt("hour", 8)));
        int minute = old == null ? 0 : Math.max(0, Math.min(59, old.params.optInt("minute", 0)));
        int displayHour = hour24 % 12;
        if (displayHour == 0) displayHour = 12;
        final int[] amPm = {hour24 >= 12 ? 1 : 0};

        LinearLayout form = dialogForm();
        form.addView(dialogLabel("Hour (1–12)"));
        EditText hour = numericField("Hour", String.valueOf(displayHour));
        form.addView(hour, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 52)));
        form.addView(dialogLabel("Minute (0–59)"));
        EditText minuteField = numericField("Minute", String.valueOf(minute));
        form.addView(minuteField, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 52)));
        LinearLayout apSelector = dialogSelector(new String[]{"AM", "PM"}, amPm);
        LinearLayout.LayoutParams apLp = selectorLp();
        apLp.topMargin = UiKit.dp(this, 8);
        form.addView(apSelector, apLp);
        form.addView(dialogLabel("Optional label"));
        EditText label = inputField("Orbit alarm", old == null ? "" : old.params.optString("label", ""), false);
        form.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 52)));

        showFormDialog(editIndex < 0 ? "Add Alarm" : "Edit Alarm", form,
                editIndex < 0 ? "Add" : "Save", () -> {
                    Integer h = parseInt(hour.getText().toString());
                    Integer m = parseInt(minuteField.getText().toString());
                    if (h == null || h < 1 || h > 12) return formError("Use an hour from 1 to 12.");
                    if (m == null || m < 0 || m > 59) return formError("Use a minute from 0 to 59.");
                    int h24 = h % 12;
                    if (amPm[0] == 1) h24 += 12;
                    try {
                        String alarmLabel = label.getText().toString().trim();
                        JSONObject p = new JSONObject().put("hour", h24).put("minute", m)
                                .put("label", alarmLabel.isEmpty() ? "Orbit alarm" : alarmLabel);
                        putAction(editIndex, new AssistantReply.Action(RoutineActionCatalog.SET_ALARM, p, false));
                        return true;
                    } catch (Exception e) { return formError("Could not save that alarm."); }
                });
    }

    private AssistantReply.Action actionAt(int index, String expectedType) {
        if (index < 0 || index >= workingActions.size()) return null;
        AssistantReply.Action action = workingActions.get(index);
        return action != null && expectedType.equals(action.type) ? action : null;
    }

    private void putAction(int editIndex, AssistantReply.Action action) {
        int branchCondition = pendingBranchCondition;
        int branchKind = pendingBranchKind;
        clearPendingBranch();
        if (!RoutineActionCatalog.isValid(action)) {
            Toast.makeText(this, "That action is incomplete.", Toast.LENGTH_SHORT).show();
            return;
        }
        AssistantReply.Action copy = RoutineActionCatalog.copy(action);
        if (editIndex >= 0 && editIndex < workingActions.size()) {
            workingActions.set(editIndex, copy);
            // Editing a condition can change nothing about the branch shape, but a rewritten
            // params object would drop the counts the editor maintains, so they are restored.
            RoutineBranch.clampCounts(workingActions);
        } else if (branchCondition >= 0 && branchKind != RoutineBranch.BRANCH_NONE) {
            if (!RoutineBranch.addToPath(workingActions, branchCondition, branchKind, copy)) {
                Toast.makeText(this, addBlockedReason(branchCondition, branchKind),
                        Toast.LENGTH_SHORT).show();
                return;
            }
        } else if (!RoutineBranch.addStep(workingActions, copy)) {
            Toast.makeText(this, "Routine step limit reached.", Toast.LENGTH_SHORT).show();
            return;
        }
        markDirtyAndRefresh();
    }

    private void clearPendingBranch() {
        pendingBranchCondition = -1;
        pendingBranchKind = RoutineBranch.BRANCH_NONE;
    }

    private void markDirtyAndRefresh() {
        dirty = true;
        refreshSteps();
    }

    private void saveRoutine() {
        String name = RoutineStore.sanitizeName(nameField == null ? "" : nameField.getText().toString());
        if (name.isEmpty()) {
            Toast.makeText(this, "Give the routine a name.", Toast.LENGTH_SHORT).show();
            if (nameField != null) nameField.requestFocus();
            return;
        }
        if (workingActions.isEmpty()) {
            Toast.makeText(this, "Add at least one step before saving.", Toast.LENGTH_SHORT).show();
            return;
        }
        for (AssistantReply.Action action : workingActions) {
            if (!RoutineActionCatalog.isValid(action)) {
                Toast.makeText(this, "One of the routine steps is incomplete.", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        // Branch shape is checked before anything is written, and the message names the step, so a
        // half-defined ELSE is corrected here rather than becoming an unrunnable saved routine.
        String branchProblem = RoutineBranch.structureProblem(workingActions);
        if (!branchProblem.isEmpty()) {
            Toast.makeText(this, branchProblem, Toast.LENGTH_LONG).show();
            return;
        }
        // An IF with nothing on its THEN path decides nothing. This is checked here rather than in
        // RoutineBranch so that loading an older routine can never be affected by it.
        List<RoutineBranch.Unit> units = RoutineBranch.units(workingActions);
        for (int position = 0; position < units.size(); position++) {
            RoutineBranch.Unit unit = units.get(position);
            if (!unit.branch) continue;
            if (RoutineBranch.pathSize(workingActions, unit.start, RoutineBranch.BRANCH_TRUE) > 0) {
                continue;
            }
            Toast.makeText(this, "Add at least one THEN action to the IF condition in step "
                    + (position + 1) + ".", Toast.LENGTH_LONG).show();
            return;
        }
        String exceptId = existing == null ? null : existing.id;
        if (RoutineStore.nameExists(this, name, exceptId)) {
            Toast.makeText(this, "A routine named “" + name + "” already exists.", Toast.LENGTH_SHORT).show();
            return;
        }

        RoutineStore.Routine routine;
        if (existing == null) {
            routine = RoutineStore.create(name, workingActions);
        } else {
            // Rebuilds name/actions while carrying createdAt, run history, and pinned state.
            routine = existing.withNameAndActions(name, workingActions);
        }
        if (!RoutineStore.upsert(this, routine)) {
            Toast.makeText(this, "Orbit could not save this routine.", Toast.LENGTH_SHORT).show();
            return;
        }
        dirty = false;
        Toast.makeText(this, "Saved " + name, Toast.LENGTH_SHORT).show();
        // Only now, with a real saved routine, is a proposed trigger offered. It is still not
        // created here: the existing Automatic triggers screen stays the authority.
        if (draftTrigger != null) {
            String pendingRoutineId = routine.id;
            RoutineTriggerDraft proposed = draftTrigger;
            draftTrigger = null;
            offerProposedTrigger(pendingRoutineId, proposed);
            return;
        }
        finish();
    }

    /**
     * Asks whether to set up the automation the builder proposed. Declining leaves a perfectly
     * normal manual routine; accepting opens the existing trigger screen, which remains the only
     * thing that can actually schedule anything.
     */
    private void offerProposedTrigger(String routineId, RoutineTriggerDraft proposed) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Set up automation?")
                .setMessage("Orbit suggested running this routine automatically:\n\n"
                        + proposed.summary(this)
                        + "\n\nIt is not scheduled yet. Open Automatic triggers to set it up.")
                .setNegativeButton("Not now", (d, w) -> finish())
                .setPositiveButton("Automatic triggers", (d, w) -> {
                    startActivity(new Intent(this, RoutineTriggersActivity.class)
                            .putExtra(RoutineTriggersActivity.EXTRA_ROUTINE_ID, routineId));
                    finish();
                })
                .create();
        dialog.setOnCancelListener(d -> finish());
        UiKit.styleOrbitDialog(dialog, this, false);
        dialog.show();
    }

    private void handleBack() {
        if (!dirty || !hasActualChanges()) {
            dirty = false;
            finish();
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Discard routine changes?")
                .setMessage("Your unsaved changes will be lost.")
                .setNegativeButton("Keep editing", null)
                .setPositiveButton("Discard", (d, w) -> {
                    dirty = false;
                    finish();
                }).create();
        styleOrbitDialog(dialog, true, null);
        dialog.show();
    }

    private boolean hasActualChanges() {
        String currentName = RoutineStore.sanitizeName(nameField == null ? "" : nameField.getText().toString());
        if (existing == null) return !currentName.isEmpty() || !workingActions.isEmpty();
        if (!existing.name.equals(currentName)) return true;
        if (existing.actions.size() != workingActions.size()) return true;
        for (int i = 0; i < workingActions.size(); i++) {
            AssistantReply.Action a = existing.actions.get(i);
            AssistantReply.Action b = workingActions.get(i);
            if (a == null || b == null) {
                if (a != b) return true;
                continue;
            }
            if (!a.type.equals(b.type) || a.requiresConfirmation != b.requiresConfirmation) return true;
            String ap = a.params == null ? "{}" : a.params.toString();
            String bp = b.params == null ? "{}" : b.params.toString();
            if (!ap.equals(bp)) return true;
        }
        return false;
    }

    private interface SaveHandler { boolean save(); }

    private void showFormDialog(String title, View form, String positiveLabel, SaveHandler handler) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(positiveLabel, null)
                .create();
        styleOrbitDialog(dialog, false, () -> {
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positive != null) {
                positive.setOnClickListener(v -> {
                    if (handler != null && handler.save()) dialog.dismiss();
                });
            }
        });
        dialog.show();
    }

    private boolean formError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        return false;
    }

    private LinearLayout dialogForm() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 6), UiKit.dp(this, 20), UiKit.dp(this, 4));
        return form;
    }

    private TextView dialogLabel(String text) {
        TextView label = UiKit.text(this, text, 12, UiKit.MUTED, true);
        label.setPadding(UiKit.dp(this, 2), UiKit.dp(this, 8), 0, UiKit.dp(this, 6));
        return label;
    }

    private EditText inputField(String hint, String value, boolean numeric) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Color.rgb(115,120,135));
        e.setText(value == null ? "" : value);
        e.setTextColor(UiKit.TEXT);
        e.setTextSize(14);
        e.setSingleLine(true);
        e.setPadding(UiKit.dp(this, 15), 0, UiKit.dp(this, 15), 0);
        e.setBackground(UiKit.outlined(UiKit.SURFACE_2, Color.rgb(53,58,72), 15, this));
        e.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        return e;
    }

    private EditText numericField(String hint, String value) {
        return inputField(hint, value, true);
    }

    private LinearLayout dialogSelector(String[] labels, int[] selected, Runnable onChanged) {
        LinearLayout field = dialogSelector(labels, selected);
        field.setOnClickListener(v -> UiKit.showOrbitMenu(this, field, labels, selected[0], (index, label) -> {
            selected[0] = index;
            TextView value = field.getChildCount() > 0 && field.getChildAt(0) instanceof TextView
                    ? (TextView) field.getChildAt(0) : null;
            if (value != null) value.setText(label);
            if (onChanged != null) onChanged.run();
        }));
        return field;
    }

    private LinearLayout dialogSelector(String[] labels, int[] selected) {
        LinearLayout field = new LinearLayout(this);
        field.setOrientation(LinearLayout.HORIZONTAL);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPadding(UiKit.dp(this, 16), 0, UiKit.dp(this, 14), 0);
        field.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), 72), UiKit.accent(this), 16, this));
        int safe = Math.max(0, Math.min(labels.length - 1, selected[0]));
        selected[0] = safe;
        TextView value = UiKit.text(this, labels[safe], 14, UiKit.TEXT, false);
        field.addView(value, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView arrow = UiKit.text(this, "▾", 18, UiKit.MUTED, true);
        field.addView(arrow);
        field.setOnClickListener(v -> UiKit.showOrbitMenu(this, field, labels, selected[0], (index, label) -> {
            selected[0] = index;
            value.setText(label);
        }));
        UiKit.pressScale(field);
        return field;
    }

    private LinearLayout.LayoutParams selectorLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 52));
        lp.setMargins(0, 0, 0, UiKit.dp(this, 2));
        return lp;
    }

    private Integer parseInt(String raw) {
        try { return Integer.parseInt(raw == null ? "" : raw.trim()); }
        catch (Exception ignored) { return null; }
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(UiKit.dp(this, 15), UiKit.dp(this, 13), UiKit.dp(this, 12), UiKit.dp(this, 13));
        c.setBackground(UiKit.outlined(UiKit.SURFACE, UiKit.withAlpha(UiKit.accent(this), 40), 18, this));
        return c;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, UiKit.dp(this, 9));
        return lp;
    }

    private TextView sectionTitle(String text) {
        TextView t = UiKit.text(this, text, 12, UiKit.MUTED, true);
        t.setLetterSpacing(0.16f);
        t.setPadding(UiKit.dp(this, 5), UiKit.dp(this, 18), 0, UiKit.dp(this, 9));
        return t;
    }

    private ImageButton iconButton(int res, String description) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setImageTintList(ColorStateList.valueOf(UiKit.accent(this)));
        b.setBackground(UiKit.ripple(UiKit.SURFACE_2, UiKit.accent(this), 18, this));
        b.setContentDescription(description);
        b.setPadding(UiKit.dp(this, 11), UiKit.dp(this, 11), UiKit.dp(this, 11), UiKit.dp(this, 11));
        UiKit.pressScale(b);
        return b;
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
        b.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2, Color.rgb(53,58,72), UiKit.accent(this), 15, this));
        b.setMinHeight(0); b.setMinimumHeight(0); b.setStateListAnimator(null);
        UiKit.pressScale(b);
        return b;
    }

    private void styleOrbitDialog(AlertDialog dialog, boolean destructive, Runnable afterShown) {
        UiKit.styleOrbitDialog(dialog, this, destructive, afterShown);
    }

    private void tintDialogText(View view) {
        if (view == null) return;
        if (view instanceof TextView && !(view instanceof Button) && !(view instanceof EditText)) {
            ((TextView) view).setTextColor(UiKit.TEXT);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) tintDialogText(group.getChildAt(i));
        }
    }
}
