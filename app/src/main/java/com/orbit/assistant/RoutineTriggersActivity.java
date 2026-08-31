package com.orbit.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Lists and manages automatic time and location triggers for one saved routine. */
public class RoutineTriggersActivity extends Activity {
    public static final String EXTRA_ROUTINE_ID = "routine_id";
    private static final int REQ_TRIGGER_ALERTS = 863;
    private static final int REQ_FINE_LOCATION = 865;
    private static final int REQ_BACKGROUND_LOCATION = 866;

    private String routineId;
    private RoutineStore.Routine routine;
    private LinearLayout timeTriggerList;
    private LinearLayout locationTriggerList;
    private LinearLayout precisionCard;
    private LinearLayout locationAccessCard;

    /** Interactive Back for this page. Its classification lives in OrbitNavigation. */
    private OrbitPredictiveBack navigation;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        UiKit.syncTheme(this);
        routineId = getIntent().getStringExtra(EXTRA_ROUTINE_ID);
        routine = RoutineStore.findById(this, routineId);
        if (routine == null) { finish(); return; }
        Window w = getWindow();
        w.setStatusBarColor(UiKit.BG);
        w.setNavigationBarColor(UiKit.BG);
        View content = buildContent();
        setContentView(content);
        UiKit.applyActivityInsets(this, content, true);
        navigation = OrbitPredictiveBack.install(this);
    }

    @Override protected void onResume() {
        super.onResume();
        UiPresence.enter(this);
        routine = RoutineStore.findById(this, routineId);
        if (routine == null) { finish(); return; }
        RoutineTriggerScheduler.rescheduleAll(this);
        refresh();
    }

    @Override protected void onPause() { UiPresence.leave(this); super.onPause(); }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_TRIGGER_ALERTS) refreshPrecisionCard();
        if (requestCode == REQ_FINE_LOCATION || requestCode == REQ_BACKGROUND_LOCATION) {
            RoutineTriggerScheduler.rescheduleAll(this);
            refreshLocationAccessCard();
            refresh();
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
        scroll.addView(page, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = iconButton(R.drawable.ic_back, "Back");
        back.setOnClickListener(v -> navigation.performBack());
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 48));
        backLp.rightMargin = UiKit.dp(this, 12);
        header.addView(back, backLp);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(UiKit.text(this, "Automatic triggers", 25, UiKit.TEXT, true));
        titles.addView(UiKit.text(this, routine.name, 12, UiKit.MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(header);

        TextView intro = UiKit.text(this,
                "Run this routine automatically on a schedule or when your phone arrives at or leaves a saved area. Every trigger keeps the routine's existing step order and uses Orbit's shared Action Engine.",
                14, UiKit.MUTED, false);
        intro.setLineSpacing(0, 1.13f);
        LinearLayout.LayoutParams introLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        introLp.setMargins(UiKit.dp(this, 2), UiKit.dp(this, 16), UiKit.dp(this, 2), UiKit.dp(this, 14));
        page.addView(intro, introLp);

        precisionCard = card();
        page.addView(precisionCard, cardLp());

        locationAccessCard = card();
        page.addView(locationAccessCard, cardLp());

        Button addTime = primaryButton("+  New time trigger");
        addTime.setOnClickListener(v -> openNewTimeTrigger());
        page.addView(addTime, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 50)));

        Button addLocation = secondaryButton("+  New location trigger");
        addLocation.setOnClickListener(v -> openNewLocationTrigger());
        LinearLayout.LayoutParams locationAddLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 50));
        locationAddLp.topMargin = UiKit.dp(this, 9);
        page.addView(addLocation, locationAddLp);

        page.addView(sectionTitle("TIME TRIGGERS"));
        timeTriggerList = new LinearLayout(this);
        timeTriggerList.setOrientation(LinearLayout.VERTICAL);
        page.addView(timeTriggerList);

        page.addView(sectionTitle("LOCATION TRIGGERS"));
        locationTriggerList = new LinearLayout(this);
        locationTriggerList.setOrientation(LinearLayout.VERTICAL);
        page.addView(locationTriggerList);
        return scroll;
    }

    private void openNewTimeTrigger() {
        if (!canAddTrigger()) return;
        startActivity(new Intent(this, TimeTriggerEditorActivity.class)
                .putExtra(TimeTriggerEditorActivity.EXTRA_ROUTINE_ID, routineId));
    }

    private void openNewLocationTrigger() {
        if (!canAddTrigger()) return;
        startActivity(new Intent(this, LocationTriggerEditorActivity.class)
                .putExtra(LocationTriggerEditorActivity.EXTRA_ROUTINE_ID, routineId));
    }

    private boolean canAddTrigger() {
        if (RoutineTriggerStore.listForRoutine(this, routineId).size() >= RoutineTriggerStore.MAX_TRIGGERS_PER_ROUTINE) {
            Toast.makeText(this, "This routine already has the maximum number of triggers.", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private void refresh() {
        refreshPrecisionCard();
        refreshLocationAccessCard();
        if (timeTriggerList == null || locationTriggerList == null) return;
        timeTriggerList.removeAllViews();
        locationTriggerList.removeAllViews();
        List<RoutineTriggerStore.Trigger> times = new ArrayList<>();
        List<RoutineTriggerStore.Trigger> locations = new ArrayList<>();
        for (RoutineTriggerStore.Trigger trigger : RoutineTriggerStore.listForRoutine(this, routineId)) {
            if (RoutineTriggerStore.TYPE_LOCATION.equals(trigger.type)) locations.add(trigger);
            else times.add(trigger);
        }
        renderTriggerGroup(timeTriggerList, times, false);
        renderTriggerGroup(locationTriggerList, locations, true);
    }

    private void renderTriggerGroup(LinearLayout list, List<RoutineTriggerStore.Trigger> triggers, boolean location) {
        if (triggers.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(UiKit.text(this, location ? "No location triggers" : "No time triggers", 16, UiKit.TEXT, true));
            TextView note = UiKit.text(this, location
                            ? "Add an arrive or leave trigger for places such as Home, Work, or the gym."
                            : "Add a schedule such as daily, weekdays, weekly, biweekly/fortnightly, or a custom interval.",
                    13, UiKit.MUTED, false);
            note.setPadding(0, UiKit.dp(this, 6), 0, 0);
            empty.addView(note);
            list.addView(empty, cardLp());
            return;
        }
        for (RoutineTriggerStore.Trigger trigger : triggers) list.addView(triggerCard(trigger), cardLp());
    }

    private void refreshPrecisionCard() {
        if (precisionCard == null) return;
        precisionCard.removeAllViews();
        boolean exact = RoutineTriggerScheduler.canScheduleExact(this);
        precisionCard.addView(UiKit.text(this, exact ? "Precise timing ready" : "Approximate timing", 14,
                exact ? UiKit.SUCCESS : UiKit.TEXT, true));
        TextView detail = UiKit.text(this, exact
                        ? "Android allows Orbit to schedule time triggers at the selected minute."
                        : "Time triggers still run, but Android may delay them, particularly while the phone is idle. Allow Alarms & reminders for minute-level scheduling.",
                12, UiKit.MUTED, false);
        detail.setPadding(0, UiKit.dp(this, 5), 0, 0);
        precisionCard.addView(detail);
        if (!exact) {
            Button allow = secondaryButton("Allow precise timing");
            allow.setOnClickListener(v -> RoutineTriggerScheduler.openExactAlarmAccess(this));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 42));
            lp.topMargin = UiKit.dp(this, 10);
            precisionCard.addView(allow, lp);
        }
        boolean notifications = RoutineTriggerNotifier.notificationsAllowed(this);
        boolean runtimeNotifications = RoutineTriggerNotifier.runtimePermissionGranted(this);
        TextView notifyState = UiKit.text(this, notifications
                        ? "Trigger alerts ready · Orbit can notify you when an automatic routine needs a foreground step."
                        : "Trigger alerts are off · fully background-safe routines can still run. Routines that need a tap are skipped rather than partially applied.",
                11, UiKit.MUTED, false);
        notifyState.setPadding(0, UiKit.dp(this, 9), 0, 0);
        precisionCard.addView(notifyState);
        if (!notifications) {
            Button alertSettings;
            if (!runtimeNotifications && Build.VERSION.SDK_INT >= 33) {
                alertSettings = secondaryButton("Allow trigger alerts");
                alertSettings.setOnClickListener(v -> requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_TRIGGER_ALERTS));
            } else {
                alertSettings = secondaryButton("Open trigger alert settings");
                alertSettings.setOnClickListener(v -> RoutineTriggerNotifier.openAlertSettings(this));
            }
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 42));
            nlp.topMargin = UiKit.dp(this, 9);
            precisionCard.addView(alertSettings, nlp);
        }
    }

    private void refreshLocationAccessCard() {
        if (locationAccessCard == null) return;
        locationAccessCard.removeAllViews();
        boolean fine = RoutineLocationTriggerScheduler.hasFineLocation(this);
        boolean background = RoutineLocationTriggerScheduler.hasBackgroundLocation(this);
        boolean locationOn = RoutineLocationTriggerScheduler.isLocationEnabled(this);
        boolean ready = fine && background && locationOn;
        locationAccessCard.addView(UiKit.text(this, ready ? "Location triggers ready" : "Location setup needed", 14,
                ready ? UiKit.SUCCESS : UiKit.TEXT, true));
        String detail;
        String button;
        if (!fine) {
            detail = "Arrive/leave automation needs precise location so Orbit can detect a saved radius.";
            button = "Allow precise location";
        } else if (!background) {
            String option = RoutineLocationTriggerScheduler.backgroundPermissionLabel(this);
            detail = "To work while Orbit is closed, Android requires Location permission set to “" + option + "”.";
            button = "Allow background location";
        } else if (!locationOn) {
            detail = "Android location services are off. Turn them on to monitor arrive/leave triggers.";
            button = "Turn on location";
        } else {
            detail = "Precise and background location are available. Enabled location triggers can be monitored while Orbit is closed.";
            button = "Manage location access";
        }
        TextView note = UiKit.text(this, detail, 12, UiKit.MUTED, false);
        note.setPadding(0, UiKit.dp(this, 5), 0, UiKit.dp(this, 10));
        locationAccessCard.addView(note);
        Button manage = secondaryButton(button);
        manage.setOnClickListener(v -> setupLocationAccess());
        locationAccessCard.addView(manage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 42)));
    }

    private void setupLocationAccess() {
        if (!RoutineLocationTriggerScheduler.hasFineLocation(this)) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION}, REQ_FINE_LOCATION);
            return;
        }
        if (!RoutineLocationTriggerScheduler.hasBackgroundLocation(this)) {
            if (Build.VERSION.SDK_INT == 29) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQ_BACKGROUND_LOCATION);
            } else {
                showBackgroundLocationExplanation();
            }
            return;
        }
        if (!RoutineLocationTriggerScheduler.isLocationEnabled(this)) {
            RoutineLocationTriggerScheduler.openLocationServices(this);
            return;
        }
        RoutineLocationTriggerScheduler.openAppLocationSettings(this);
    }

    private void showBackgroundLocationExplanation() {
        String option = RoutineLocationTriggerScheduler.backgroundPermissionLabel(this);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Allow background location")
                .setMessage("Android requires background access for location automation. Open Orbit's app settings, choose Permissions → Location, then select “" + option + "”.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Open settings", (d, w) -> RoutineLocationTriggerScheduler.openAppLocationSettings(this))
                .create();
        styleOrbitDialog(dialog, false);
        dialog.show();
    }

    private View triggerCard(RoutineTriggerStore.Trigger trigger) {
        LinearLayout card = card();
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView schedule = UiKit.text(this, RoutineTriggerSchedule.summary(trigger), 15, UiKit.TEXT, true);
        text.addView(schedule);
        TextView next = UiKit.text(this, triggerStateLabel(trigger), 12, UiKit.MUTED, false);
        next.setPadding(0, UiKit.dp(this, 4), 0, 0);
        text.addView(next);
        top.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        OrbitSwitch enabled = new OrbitSwitch(this);
        enabled.setChecked(trigger.enabled, false);
        enabled.setContentDescription(trigger.enabled ? "Disable trigger" : "Enable trigger");
        enabled.setOnCheckedChangeListener((button, checked) -> {
            RoutineTriggerStore.Trigger latest = RoutineTriggerStore.findById(this, trigger.id);
            if (latest == null || latest.enabled == checked) return;
            RoutineTriggerStore.Trigger changed = latest.withEnabled(checked);
            if (checked && RoutineTriggerStore.hasEnabledScheduleConflict(this, changed)) {
                Toast.makeText(this, "An enabled trigger with this exact setup already exists.", Toast.LENGTH_LONG).show();
                refresh();
                return;
            }
            if (RoutineTriggerStore.upsert(this, changed)) {
                if (checked) {
                    if (RoutineTriggerStore.TYPE_LOCATION.equals(changed.type)) {
                        boolean scheduled = RoutineLocationTriggerScheduler.schedule(this, changed);
                        if (!scheduled && !RoutineLocationTriggerScheduler.ready(this)) {
                            Toast.makeText(this, "Trigger enabled, but Location setup is still required before Android can monitor it.", Toast.LENGTH_LONG).show();
                        }
                    } else {
                        long scheduled = RoutineTriggerScheduler.schedule(this, changed);
                        if (scheduled <= 0L && RoutineTriggerStore.MODE_ONCE.equals(changed.mode)) {
                            Toast.makeText(this, "That one-time schedule has already passed. Edit its date or time to enable it again.", Toast.LENGTH_LONG).show();
                        }
                    }
                } else {
                    RoutineTriggerScheduler.cancel(this, changed.id);
                }
                refresh();
            }
        });
        // No press-scale here: the thumb and track already carry the interaction, and stacking a
        // second animation on top of them reads as busy.
        top.addView(enabled, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageButton more = iconButton(R.drawable.ic_more, "Trigger options");
        more.setOnClickListener(v -> showTriggerMenu(more, trigger));
        top.addView(more, new LinearLayout.LayoutParams(UiKit.dp(this, 44), UiKit.dp(this, 44)));
        card.addView(top);

        if (!trigger.lastResult.isEmpty()) {
            TextView status = UiKit.text(this, "Last: " + trigger.lastResult, 11,
                    trigger.lastResult.toLowerCase(Locale.US).contains("completed") ? UiKit.SUCCESS : UiKit.MUTED, false);
            status.setPadding(0, UiKit.dp(this, 8), 0, 0);
            card.addView(status);
        }
        return card;
    }

    private String triggerStateLabel(RoutineTriggerStore.Trigger trigger) {
        if (RoutineTriggerStore.TYPE_LOCATION.equals(trigger.type)) {
            if (!trigger.enabled) return "Off";
            if (!RoutineLocationTriggerScheduler.hasFineLocation(this)) return "Needs precise location access";
            if (!RoutineLocationTriggerScheduler.hasBackgroundLocation(this)) return "Needs background location access";
            if (!RoutineLocationTriggerScheduler.isLocationEnabled(this)) return "Phone location is off";
            return "Ready for background monitoring";
        }
        return nextTimeLabel(trigger);
    }

    private String nextTimeLabel(RoutineTriggerStore.Trigger t) {
        if (!t.enabled) {
            if (RoutineTriggerStore.MODE_ONCE.equals(t.mode) && t.lastRunAt > 0L) return "Finished";
            return "Off";
        }
        if (t.nextRunAt <= 0) return RoutineTriggerStore.MODE_ONCE.equals(t.mode) ? "Finished" : "Not scheduled";
        ZonedDateTime z = Instant.ofEpochMilli(t.nextRunAt).atZone(ZoneId.systemDefault());
        LocalDate d = z.toLocalDate();
        LocalDate today = LocalDate.now();
        String day;
        if (d.equals(today)) day = "Today";
        else if (d.equals(today.plusDays(1))) day = "Tomorrow";
        else day = z.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US));
        return "Next · " + day + " at " + RoutineActionCatalog.timeLabel(z.getHour(), z.getMinute());
    }

    private void showTriggerMenu(View anchor, RoutineTriggerStore.Trigger trigger) {
        String[] labels = {"Edit", "Duplicate", "Delete"};
        UiKit.showOrbitMenu(this, anchor, labels, -1, (index, label) -> {
            if (index == 0) openEditor(trigger);
            else if (index == 1) duplicate(trigger);
            else confirmDelete(trigger);
        });
    }

    private void duplicate(RoutineTriggerStore.Trigger source) {
        if (!canAddTrigger()) return;
        RoutineTriggerStore.Trigger copy;
        if (RoutineTriggerStore.TYPE_LOCATION.equals(source.type)) {
            copy = RoutineTriggerStore.createLocation(source.routineId, source.locationName,
                    source.latitude, source.longitude, source.radiusMeters,
                    source.locationTransition).withEnabled(false);
        } else {
            copy = RoutineTriggerStore.createTime(source.routineId, source.mode,
                    source.hour, source.minute, source.startYear, source.startMonth, source.startDay,
                    source.weekdayMask, source.intervalCount, source.intervalUnit).withEnabled(false);
        }
        if (RoutineTriggerStore.upsert(this, copy)) {
            Toast.makeText(this, "Trigger duplicated · off until you enable it.", Toast.LENGTH_SHORT).show();
            refresh();
        }
    }

    private void confirmDelete(RoutineTriggerStore.Trigger trigger) {
        String kind = RoutineTriggerStore.TYPE_LOCATION.equals(trigger.type) ? "location trigger" : "time trigger";
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Delete " + kind + "?")
                .setMessage(RoutineTriggerSchedule.summary(trigger))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    RoutineTriggerScheduler.cancel(this, trigger.id);
                    RoutineTriggerStore.delete(this, trigger.id);
                    refresh();
                }).create();
        styleOrbitDialog(dialog, true);
        dialog.show();
    }

    private void openEditor(RoutineTriggerStore.Trigger trigger) {
        if (RoutineTriggerStore.TYPE_LOCATION.equals(trigger.type)) {
            startActivity(new Intent(this, LocationTriggerEditorActivity.class)
                    .putExtra(LocationTriggerEditorActivity.EXTRA_ROUTINE_ID, routineId)
                    .putExtra(LocationTriggerEditorActivity.EXTRA_TRIGGER_ID, trigger.id));
        } else {
            startActivity(new Intent(this, TimeTriggerEditorActivity.class)
                    .putExtra(TimeTriggerEditorActivity.EXTRA_ROUTINE_ID, routineId)
                    .putExtra(TimeTriggerEditorActivity.EXTRA_TRIGGER_ID, trigger.id));
        }
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, UiKit.dp(this, 10));
        return lp;
    }

    private TextView sectionTitle(String s) {
        TextView t = UiKit.text(this, s, 12, UiKit.MUTED, true);
        t.setLetterSpacing(0.16f);
        t.setPadding(UiKit.dp(this, 5), UiKit.dp(this, 14), 0, UiKit.dp(this, 10));
        return t;
    }

    private ImageButton iconButton(int res, String desc) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setImageTintList(ColorStateList.valueOf(UiKit.accent(this)));
        b.setBackground(UiKit.ripple(UiKit.SURFACE_2, UiKit.accent(this), 18, this));
        b.setContentDescription(desc);
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
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
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
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        UiKit.pressScale(b);
        return b;
    }

    private void styleOrbitDialog(AlertDialog dialog, boolean destructive) {
        UiKit.styleOrbitDialog(dialog, this, destructive);
    }

    private void tint(View v) {
        if (v == null) return;
        if (v instanceof TextView && !(v instanceof Button)) ((TextView)v).setTextColor(UiKit.TEXT);
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)v).getChildCount(); i++) tint(((ViewGroup)v).getChildAt(i));
    }
}
