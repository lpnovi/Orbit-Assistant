package com.orbit.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Manager and manual runner for saved Orbit action routines. */
public class RoutinesActivity extends Activity {
    public static final String EXTRA_AUTORUN_ROUTINE_ID = "autorun_routine_id";
    public static final String EXTRA_AUTORUN_START_INDEX = "autorun_start_index";
    public static final String EXTRA_AUTORUN_TRIGGER_ID = "autorun_trigger_id";
    public static final String EXTRA_AUTORUN_SCHEDULED = "autorun_scheduled";
    private boolean handledAutoRunIntent;
    private ScrollView pageScroll;
    private LinearLayout pageContent;
    private LinearLayout routinesList;
    private LinearLayout runPanel;
    private TextView runTitle;
    private TextView runSubtitle;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.syncTheme(this);
        Window w = getWindow();
        w.setStatusBarColor(UiKit.BG);
        w.setNavigationBarColor(UiKit.BG);
        View content = buildContent();
        setContentView(content);
        UiKit.applyActivityInsets(this, content, true);
    }

    @Override protected void onResume() {
        super.onResume();
        UiPresence.enter(this);
        RoutineTriggerScheduler.rescheduleAll(this);
        refresh();
        handleAutoRunIntent();
    }

    @Override protected void onPause() {
        UiPresence.leave(this);
        super.onPause();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handledAutoRunIntent = false;
        handleAutoRunIntent();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        pageScroll = scroll;
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(UiKit.BG);

        LinearLayout page = new LinearLayout(this);
        pageContent = page;
        page.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 20);
        page.setPadding(p, UiKit.dp(this, 26), p, UiKit.dp(this, 48));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = iconButton(R.drawable.ic_back, "Back");
        back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 48));
        backLp.rightMargin = UiKit.dp(this, 12);
        header.addView(back, backLp);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(UiKit.text(this, "Routines", 26, UiKit.TEXT, true));
        titles.addView(UiKit.text(this, "Automation", 12, UiKit.MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(header);

        TextView intro = UiKit.text(this,
                "Save reliable device-action chains once, run them here or ask Orbit by name, and add automatic schedules when you want them. Routines use the same Action Engine as normal Orbit commands.",
                14, UiKit.MUTED, false);
        intro.setLineSpacing(0, 1.13f);
        LinearLayout.LayoutParams introLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        introLp.setMargins(UiKit.dp(this, 2), UiKit.dp(this, 16), UiKit.dp(this, 2), UiKit.dp(this, 16));
        page.addView(intro, introLp);

        Button create = primaryButton("+  New routine");
        create.setOnClickListener(v -> {
            if (RoutineStore.list(this).size() >= RoutineStore.MAX_ROUTINES) {
                Toast.makeText(this, "Orbit supports up to " + RoutineStore.MAX_ROUTINES + " routines.", Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(new Intent(this, RoutineEditorActivity.class));
        });
        page.addView(create, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 50)));

        Button templates = secondaryButton("Templates");
        templates.setOnClickListener(v ->
                startActivity(new Intent(this, RoutineTemplatesActivity.class)));
        LinearLayout.LayoutParams templatesLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 44));
        templatesLp.setMargins(0, UiKit.dp(this, 10), 0, 0);
        page.addView(templates, templatesLp);

        Button customCommands = secondaryButton("Custom Commands");
        customCommands.setOnClickListener(v ->
                startActivity(new Intent(this, CustomCommandsActivity.class)));
        LinearLayout.LayoutParams customLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 44));
        customLp.setMargins(0, UiKit.dp(this, 8), 0, 0);
        page.addView(customCommands, customLp);

        Button runHistory = secondaryButton("Run history");
        runHistory.setOnClickListener(v ->
                startActivity(new Intent(this, RoutineRunHistoryActivity.class)));
        LinearLayout.LayoutParams historyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 44));
        historyLp.setMargins(0, UiKit.dp(this, 8), 0, 0);
        page.addView(runHistory, historyLp);

        runPanel = card();
        runPanel.setVisibility(View.GONE);
        runTitle = UiKit.text(this, "Running routine", 15, UiKit.TEXT, true);
        runPanel.addView(runTitle);
        runSubtitle = UiKit.text(this, "", 12, UiKit.MUTED, false);
        runSubtitle.setPadding(0, UiKit.dp(this, 5), 0, 0);
        runPanel.addView(runSubtitle);
        LinearLayout.LayoutParams runLp = cardLp();
        runLp.setMargins(0, UiKit.dp(this, 14), 0, 0);
        page.addView(runPanel, runLp);

        TextView saved = sectionTitle("SAVED ROUTINES");
        LinearLayout.LayoutParams savedLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        savedLp.setMargins(0, UiKit.dp(this, 14), 0, 0);
        page.addView(saved, savedLp);

        routinesList = new LinearLayout(this);
        routinesList.setOrientation(LinearLayout.VERTICAL);
        page.addView(routinesList);

        TextView hint = UiKit.text(this,
                "Tip: run a routine by name, or open Automatic triggers on its card to schedule it.",
                12, UiKit.MUTED, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 12), UiKit.dp(this, 8), 0);
        page.addView(hint);

        return scroll;
    }

    /**
     * Applies a change that rebuilds the routine cards or resizes the run panel above them, then
     * restores the scroll offset once layout settles. Rebuilding briefly collapses the list, which
     * otherwise makes ScrollView clamp the offset to the header, and showing or growing the run
     * panel shifts every card down. Re-anchoring on the list keeps the visible routines in place.
     */
    private void preserveListPosition(Runnable change) {
        if (change == null) return;
        if (pageScroll == null || pageContent == null || routinesList == null) {
            change.run();
            return;
        }
        final ScrollView target = pageScroll;
        final int previousScroll = target.getScrollY();
        final int previousAnchor = routinesList.getTop();
        change.run();
        if (previousScroll <= 0) return;
        ViewTreeObserver.OnGlobalLayoutListener listener = new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                ViewTreeObserver observer = target.getViewTreeObserver();
                if (observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
                int maximum = Math.max(0, pageContent.getHeight() - target.getHeight());
                int desired = previousScroll + (routinesList.getTop() - previousAnchor);
                target.scrollTo(0, Math.max(0, Math.min(desired, maximum)));
            }
        };
        target.getViewTreeObserver().addOnGlobalLayoutListener(listener);
    }

    private void refresh() {
        if (routinesList == null) return;
        preserveListPosition(this::rebuildRoutinesList);
    }

    private void rebuildRoutinesList() {
        routinesList.removeAllViews();
        List<RoutineStore.Routine> routines = RoutineStore.list(this);
        if (routines.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(UiKit.text(this, "No routines yet", 16, UiKit.TEXT, true));
            TextView note = UiKit.text(this,
                    "Create a routine for repeated setups such as Bedtime, Gaming, or Driving.",
                    13, UiKit.MUTED, false);
            note.setPadding(0, UiKit.dp(this, 6), 0, 0);
            empty.addView(note);
            routinesList.addView(empty, cardLp());
            return;
        }
        for (RoutineStore.Routine routine : routines) routinesList.addView(routineCard(routine), cardLp());
    }

    private View routineCard(RoutineStore.Routine routine) {
        LinearLayout card = card();

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView name = UiKit.text(this, routine.name, 16, UiKit.TEXT, true);
        name.setMaxLines(1);
        text.addView(name);
        String stepText = routine.actions.size() == 1 ? "1 step" : routine.actions.size() + " steps";
        TextView steps = UiKit.text(this, stepText, 12, UiKit.MUTED, false);
        steps.setPadding(0, UiKit.dp(this, 4), 0, 0);
        text.addView(steps);
        top.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        // Status only, and only on pinned cards, so an unpinned routine card looks
        // exactly as it did before. Pinning itself lives in the existing options menu.
        if (routine.pinned) {
            ImageView pinned = new ImageView(this);
            pinned.setImageResource(R.drawable.ic_pin);
            pinned.setScaleType(ImageView.ScaleType.FIT_CENTER);
            pinned.setColorFilter(UiKit.accent(this));
            pinned.setContentDescription("Pinned routine");
            LinearLayout.LayoutParams pinnedLp = new LinearLayout.LayoutParams(
                    UiKit.dp(this, 18), UiKit.dp(this, 18));
            pinnedLp.rightMargin = UiKit.dp(this, 6);
            top.addView(pinned, pinnedLp);
        }

        ImageButton more = iconButton(R.drawable.ic_more, "Routine options");
        more.setOnClickListener(v -> showRoutineMenu(more, routine));
        top.addView(more, new LinearLayout.LayoutParams(UiKit.dp(this, 44), UiKit.dp(this, 44)));
        card.addView(top);

        TextView preview = UiKit.text(this, routinePreview(routine), 12, UiKit.MUTED, false);
        preview.setMaxLines(2);
        preview.setPadding(0, UiKit.dp(this, 9), 0, UiKit.dp(this, 9));
        card.addView(preview);

        Button triggers = triggerSummaryButton(routine);
        triggers.setOnClickListener(v -> openTriggers(routine.id));
        LinearLayout.LayoutParams triggerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 38));
        triggerLp.bottomMargin = UiKit.dp(this, 10);
        card.addView(triggers, triggerLp);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button run = primaryButton("Run");
        run.setOnClickListener(v -> runRoutine(routine));
        actions.addView(run, new LinearLayout.LayoutParams(0, UiKit.dp(this, 44), 1));

        Button edit = secondaryButton("Edit");
        edit.setOnClickListener(v -> openEditor(routine.id));
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(0, UiKit.dp(this, 44), 1);
        editLp.leftMargin = UiKit.dp(this, 9);
        actions.addView(edit, editLp);
        card.addView(actions);
        return card;
    }

    private void showRoutineMenu(View anchor, RoutineStore.Routine routine) {
        String[] labels = {routine.pinned ? "Unpin" : "Pin", "Edit", "Automatic triggers",
                "Duplicate", "Delete"};
        UiKit.showOrbitMenuWithDialogHandoff(this, anchor, labels, 4, (index, label) -> {
            if (index == 0) {
                togglePinned(routine);
            } else if (index == 1) {
                openEditor(routine.id);
            } else if (index == 2) {
                openTriggers(routine.id);
            } else if (index == 3) {
                duplicateRoutine(routine);
            } else {
                confirmDelete(routine);
            }
        });
    }

    private void togglePinned(RoutineStore.Routine routine) {
        boolean pin = !routine.pinned;
        if (!RoutineStore.setPinned(this, routine.id, pin)) {
            Toast.makeText(this, "Could not update this routine.", Toast.LENGTH_SHORT).show();
            return;
        }
        refresh();
        Toast.makeText(this, (pin ? "Pinned " : "Unpinned ") + routine.name, Toast.LENGTH_SHORT).show();
    }

    private void duplicateRoutine(RoutineStore.Routine source) {
        if (RoutineStore.list(this).size() >= RoutineStore.MAX_ROUTINES) {
            Toast.makeText(this, "Routine limit reached.", Toast.LENGTH_SHORT).show();
            return;
        }
        String name = uniqueCopyName(source.name);
        RoutineStore.Routine copy = RoutineStore.create(name, source.actions);
        if (RoutineStore.upsert(this, copy)) {
            refresh();
            Toast.makeText(this, "Duplicated " + source.name, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Could not duplicate routine.", Toast.LENGTH_SHORT).show();
        }
    }

    private String uniqueCopyName(String source) {
        String base = RoutineStore.sanitizeName(source + " Copy");
        if (!RoutineStore.nameExists(this, base, null)) return base;
        for (int i = 2; i < 100; i++) {
            String suffix = " (" + i + ")";
            int maxBase = Math.max(1, RoutineStore.MAX_NAME_LENGTH - suffix.length());
            String root = source.length() > maxBase ? source.substring(0, maxBase).trim() : source;
            String candidate = RoutineStore.sanitizeName(root + suffix);
            if (!RoutineStore.nameExists(this, candidate, null)) return candidate;
        }
        return "Routine " + System.currentTimeMillis() % 10000;
    }

    private void confirmDelete(RoutineStore.Routine routine) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Delete " + routine.name + "?")
                .setMessage("This removes the saved routine from Orbit. It does not change any current device settings.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    if (RoutineStore.delete(this, routine.id)) refresh();
                }).create();
        styleOrbitDialog(dialog, true);
        dialog.show();
    }

    private void runRoutine(RoutineStore.Routine routine) {
        runRoutineFromIndex(routine, 0, false, "");
    }

    private void runRoutineFromIndex(RoutineStore.Routine routine, int startIndex, boolean scheduledContinuation,
                                     String scheduledTriggerId) {
        if (routine == null || routine.actions.isEmpty()) return;
        int safeStart = Math.max(0, Math.min(startIndex, routine.actions.size() - 1));
        RoutineStore.markRun(this, routine.id);
        int remaining = routine.actions.size() - safeStart;
        preserveListPosition(() -> {
            runPanel.setVisibility(View.VISIBLE);
            runTitle.setText((scheduledContinuation ? "Finishing " : "Running ") + routine.name);
            runSubtitle.setText("Starting " + remaining + (remaining == 1 ? " remaining step…" : " remaining steps…"));
            while (runPanel.getChildCount() > 2) runPanel.removeViewAt(2);
        });

        List<AssistantReply.Action> actions = RoutineStore.copyActions(routine.actions.subList(safeStart, routine.actions.size()));
        final int offset = safeStart;
        final int originalTotal = routine.actions.size();
        OrbitActionEngine.execute(this, actions,
                (action, onAllow, onCancel) -> showActionConfirmation(action, onAllow, onCancel),
                new OrbitActionEngine.Listener() {
                    private String lastFailure = "";
                    private int retryFromIndex = offset;

                    @Override public void onStep(AssistantReply.Action action, DeviceActionExecutor.Result result, int index, int total) {
                        int shownIndex = offset + index;
                        addRunResult(action, result, shownIndex, originalTotal);
                        if (result != null && !result.success) {
                            retryFromIndex = shownIndex;
                            if (result.message != null) lastFailure = result.message.trim();
                        }
                        runSubtitle.setText("Step " + (shownIndex + 1) + " of " + originalTotal);
                    }

                    @Override public void onFinished(boolean completedAllSteps, int completedSteps, int totalSteps) {
                        int shownCompleted = offset + completedSteps;
                        RoutineRunHistoryStore.record(RoutinesActivity.this, routine.id, routine.name,
                                scheduledContinuation ? RoutineRunHistoryStore.SOURCE_TRIGGER
                                        : RoutineRunHistoryStore.SOURCE_MANUAL,
                                completedAllSteps, shownCompleted, originalTotal,
                                completedAllSteps ? -1 : retryFromIndex,
                                completedAllSteps ? null : actionAt(routine, retryFromIndex),
                                lastFailure);
                        if (completedAllSteps) {
                            runSubtitle.setText("Completed " + originalTotal + (originalTotal == 1 ? " step" : " steps"));
                            updateScheduledContinuationStatus(scheduledContinuation, scheduledTriggerId,
                                    "Completed after tap");
                        } else {
                            String continuationResult = lastFailure == null || lastFailure.isEmpty()
                                    ? "Continuation stopped at step " + shownCompleted
                                    : "Continuation stopped · " + lastFailure;
                            updateScheduledContinuationStatus(scheduledContinuation, scheduledTriggerId, continuationResult);
                            runSubtitle.setText("Stopped after step " + shownCompleted + " of " + originalTotal);
                            Button retry = secondaryButton("Retry from failed step");
                            final int retryStart = Math.max(0, Math.min(retryFromIndex, originalTotal - 1));
                            retry.setOnClickListener(v -> runRoutineFromIndex(routine, retryStart, scheduledContinuation, scheduledTriggerId));
                            LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(RoutinesActivity.this, 42));
                            retryLp.setMargins(0, UiKit.dp(RoutinesActivity.this, 10), 0, 0);
                            preserveListPosition(() -> runPanel.addView(retry, retryLp));
                        }
                    }
                });
    }

    private static AssistantReply.Action actionAt(RoutineStore.Routine routine, int index) {
        if (routine == null || index < 0 || index >= routine.actions.size()) return null;
        return routine.actions.get(index);
    }

    private void addRunResult(AssistantReply.Action action, DeviceActionExecutor.Result result, int index, int total) {
        LinearLayout row = new LinearLayout(this);
        renderRunResult(row, action, result, index, total);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, UiKit.dp(this, 8), 0, 0);
        preserveListPosition(() -> runPanel.addView(row, lp));
    }

    private void renderRunResult(LinearLayout row, AssistantReply.Action action,
                                 DeviceActionExecutor.Result result, int index, int total) {
        row.removeAllViews();
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 9), UiKit.dp(this, 12), UiKit.dp(this, 9));

        boolean success = result != null && result.success;
        boolean reversibleOff = success && ReversibleActionHelper.isOffState(action);
        int red = Color.rgb(239, 105, 105);
        int tone = (success && !reversibleOff) ? UiKit.SUCCESS : red;
        row.setBackground(UiKit.outlined(UiKit.SURFACE_2, UiKit.withAlpha(tone, 110), 15, this));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        String prefix = (success && !reversibleOff) ? "✓ " : (success ? "○ " : "! ");
        TextView title = UiKit.text(this, prefix + RoutineActionCatalog.title(action), 13, tone, true);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        if (success && ReversibleActionHelper.canTurnOff(action)) {
            Button off = runCardControlButton("Turn off", red);
            off.setOnClickListener(v -> {
                AssistantReply.Action offAction = ReversibleActionHelper.turnOffAction(action);
                if (offAction == null) return;
                DeviceActionExecutor.Result offResult = DeviceActionExecutor.executeDetailed(this, offAction);
                AssistantReply.Action shownAction = offResult.success ? offAction : action;
                renderRunResult(row, shownAction, offResult, index, total);
            });
            LinearLayout.LayoutParams offLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 30));
            offLp.setMargins(UiKit.dp(this, 10), 0, 0, 0);
            titleRow.addView(off, offLp);
        }
        row.addView(titleRow);

        String message = result == null ? "No result" : result.message;
        TextView detail = UiKit.text(this, "Step " + (index + 1) + " of " + total + " · " + message, 11, UiKit.MUTED, false);
        detail.setPadding(0, UiKit.dp(this, 3), 0, 0);
        row.addView(detail);

        if (result != null && DeviceActionExecutor.STATUS_PERMISSION.equals(result.status)) {
            Button access = permissionButtonFor(action);
            if (access != null) {
                LinearLayout.LayoutParams accessLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 38));
                accessLp.setMargins(0, UiKit.dp(this, 8), 0, 0);
                row.addView(access, accessLp);
            }
        }
    }

    private Button runCardControlButton(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(10.5f);
        b.setTextColor(color);
        b.setSingleLine(true);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setIncludeFontPadding(false);
        b.setStateListAnimator(null);
        b.setPadding(UiKit.dp(this, 10), 0, UiKit.dp(this, 10), 0);
        b.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                UiKit.withAlpha(color, 82), color, 12, this));
        UiKit.pressScale(b);
        return b;
    }


    private Button permissionButtonFor(AssistantReply.Action action) {
        if (action == null || action.type == null) return null;
        Button access = secondaryButton("Grant access");
        if (RoutineActionCatalog.SET_BRIGHTNESS.equals(action.type)) {
            access.setOnClickListener(v -> OrbitPermissionHelper.openWriteSettings(this));
            return access;
        }
        if (RoutineActionCatalog.SET_DND.equals(action.type)) {
            access.setOnClickListener(v -> OrbitPermissionHelper.openDndAccess(this));
            return access;
        }
        if (RoutineActionCatalog.FLASHLIGHT.equals(action.type)) {
            access.setOnClickListener(v -> requestPermissions(new String[]{Manifest.permission.CAMERA}, 802));
            return access;
        }
        return null;
    }

    private void showActionConfirmation(AssistantReply.Action action, Runnable onAllow, Runnable onCancel) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Let Orbit do this?")
                .setMessage(action == null ? "Device action" : RoutineActionCatalog.title(action))
                .setNegativeButton("Cancel", (d, w) -> onCancel.run())
                .setPositiveButton("Continue", (d, w) -> onAllow.run())
                .create();
        styleOrbitDialog(dialog, false);
        dialog.show();
    }

    private void openEditor(String id) {
        startActivity(new Intent(this, RoutineEditorActivity.class)
                .putExtra(RoutineEditorActivity.EXTRA_ROUTINE_ID, id));
    }

    private void openTriggers(String routineId) {
        startActivity(new Intent(this, RoutineTriggersActivity.class)
                .putExtra(RoutineTriggersActivity.EXTRA_ROUTINE_ID, routineId));
    }

    private Button triggerSummaryButton(RoutineStore.Routine routine) {
        List<RoutineTriggerStore.Trigger> triggers = RoutineTriggerStore.listForRoutine(this, routine.id);
        int enabled = 0;
        int enabledLocations = 0;
        long next = 0L;
        for (RoutineTriggerStore.Trigger t : triggers) {
            if (!t.enabled) continue;
            enabled++;
            if (RoutineTriggerStore.TYPE_LOCATION.equals(t.type)) enabledLocations++;
            if (t.nextRunAt > 0 && (next == 0L || t.nextRunAt < next)) next = t.nextRunAt;
        }
        String text;
        if (triggers.isEmpty()) text = "Automatic triggers · None";
        else if (enabled == 0) text = "Automatic triggers · Off";
        else {
            String detail = next > 0 && enabledLocations > 0 ? " · time + location"
                    : next > 0 ? " · scheduled"
                    : enabledLocations > 0 ? " · location" : "";
            text = "Automatic triggers · " + enabled + " on" + detail;
        }
        Button b = secondaryButton(text);
        b.setTextSize(12);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        b.setPadding(UiKit.dp(this, 12), 0, UiKit.dp(this, 12), 0);
        return b;
    }

    private void handleAutoRunIntent() {
        if (handledAutoRunIntent || getIntent() == null) return;
        String id = getIntent().getStringExtra(EXTRA_AUTORUN_ROUTINE_ID);
        if (id == null || id.trim().isEmpty()) return;
        handledAutoRunIntent = true;
        int start = getIntent().getIntExtra(EXTRA_AUTORUN_START_INDEX, 0);
        String triggerId = getIntent().getStringExtra(EXTRA_AUTORUN_TRIGGER_ID);
        boolean scheduled = getIntent().getBooleanExtra(EXTRA_AUTORUN_SCHEDULED, true);
        RoutineStore.Routine target = RoutineStore.findById(this, id);
        if (target != null) runRoutineFromIndex(target, start, scheduled,
                triggerId == null ? "" : triggerId);
        getIntent().removeExtra(EXTRA_AUTORUN_ROUTINE_ID);
        getIntent().removeExtra(EXTRA_AUTORUN_START_INDEX);
        getIntent().removeExtra(EXTRA_AUTORUN_TRIGGER_ID);
        getIntent().removeExtra(EXTRA_AUTORUN_SCHEDULED);
    }

    private void updateScheduledContinuationStatus(boolean scheduledContinuation, String triggerId, String result) {
        if (!scheduledContinuation || triggerId == null || triggerId.trim().isEmpty()) return;
        RoutineTriggerStore.Trigger trigger = RoutineTriggerStore.findById(this, triggerId);
        if (trigger == null) return;
        RoutineTriggerStore.updateRunState(this, trigger.id, trigger.lastRunAt, trigger.nextRunAt,
                result == null ? "" : result, trigger.enabled);
    }

    private String routinePreview(RoutineStore.Routine routine) {
        if (routine == null || routine.actions.isEmpty()) return "No steps";
        StringBuilder out = new StringBuilder();
        int max = Math.min(3, routine.actions.size());
        for (int i = 0; i < max; i++) {
            if (i > 0) out.append("  →  ");
            out.append(RoutineActionCatalog.title(routine.actions.get(i)));
        }
        if (routine.actions.size() > max) out.append("  →  +").append(routine.actions.size() - max).append(" more");
        return out.toString();
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(UiKit.dp(this, 17), UiKit.dp(this, 15), UiKit.dp(this, 17), UiKit.dp(this, 15));
        c.setBackground(UiKit.outlined(UiKit.SURFACE, UiKit.withAlpha(UiKit.accent(this), 40), 20, this));
        c.setElevation(UiKit.dp(this, 2));
        return c;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, UiKit.dp(this, 10));
        return lp;
    }

    private TextView sectionTitle(String text) {
        TextView t = UiKit.text(this, text, 12, UiKit.MUTED, true);
        t.setLetterSpacing(0.16f);
        t.setPadding(UiKit.dp(this, 5), UiKit.dp(this, 12), 0, UiKit.dp(this, 10));
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

    private void styleOrbitDialog(AlertDialog dialog, boolean destructive) {
        UiKit.styleOrbitDialog(dialog, this, destructive);
    }

    private void tintDialogText(View view) {
        if (view == null) return;
        if (view instanceof TextView && !(view instanceof Button)) ((TextView) view).setTextColor(UiKit.TEXT);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) tintDialogText(group.getChildAt(i));
        }
    }
}
