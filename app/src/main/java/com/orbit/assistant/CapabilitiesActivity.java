package com.orbit.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Current Orbit capability and Android-access dashboard. */
public final class CapabilitiesActivity extends Activity {
    private static final int REQ_TRIGGER_ALERTS = 864;
    private static final int REQ_MICROPHONE = 870;
    private static final int REQ_LOCATION = 871;
    private static final int REQ_CAMERA = 872;
    private static final int REQ_CONTACTS = 873;
    private static final int REQ_CALENDAR = 876;
    private static final int REQ_ROUTINE_FINE_LOCATION = 874;
    private static final int REQ_ROUTINE_BACKGROUND_LOCATION = 875;

    private TextView microphoneState;
    private Button microphoneManage;
    private TextView notificationAccessState;
    private Button notificationAccessManage;
    private TextView locationState;
    private Button locationManage;
    private TextView flashlightState;
    private Button flashlightManage;
    private TextView contactsState;
    private Button contactsManage;
    private TextView calendarState;
    private Button calendarManage;
    private TextView brightnessState;
    private TextView dndState;
    private Button brightnessSetup;
    private Button dndSetup;
    private TextView preciseTimingState;
    private TextView triggerAlertsState;
    private Button preciseTimingManage;
    private Button triggerAlertsManage;
    private TextView locationAutomationState;
    private Button locationAutomationManage;
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.syncTheme(this);
        View root = build();
        setContentView(root);
        UiKit.applyActivityInsets(this, root, false);
    }

    @Override protected void onResume() {
        super.onResume();
        UiPresence.enter(this);
        refreshRuntimeAccessRows();
        refreshNotificationAccessRow();
        refreshSpecialAccessRows();
        refreshRoutineAutomationRows();
        RoutineTriggerScheduler.rescheduleAll(this);
    }
    @Override protected void onPause() { UiPresence.leave(this); super.onPause(); }

    private View build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(UiKit.BG);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(UiKit.dp(this,18), UiKit.dp(this,10), UiKit.dp(this,18), UiKit.dp(this,40));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = iconButton(R.drawable.ic_back, "Back");
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(UiKit.dp(this,48), UiKit.dp(this,48)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(UiKit.text(this, "Capabilities", 24, UiKit.TEXT, true));
        titles.addView(UiKit.text(this, "Permissions & access", 12, UiKit.MUTED, false));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleLp.setMargins(UiKit.dp(this,14),0,0,0);
        header.addView(titles, titleLp);
        page.addView(header);

        TextView intro = UiKit.text(this,
                "See what Orbit can do now and manage the Android permissions or special access each capability needs.",
                13, UiKit.MUTED, false);
        intro.setPadding(UiKit.dp(this,4), UiKit.dp(this,14), UiKit.dp(this,4), UiKit.dp(this,4));
        page.addView(intro);

        page.addView(section("VOICE BETA"));
        LinearLayout voice = card();
        voice.addView(runtimePermissionRow("Microphone", Manifest.permission.RECORD_AUDIO, REQ_MICROPHONE));
        voice.addView(status("Pause-friendly listening", Prefs.voicePauseFriendly(this)));
        voice.addView(status("Spoken replies", Prefs.speak(this)));
        voice.addView(status("Hands-free follow-ups", Prefs.autoListen(this)));
        page.addView(voice, cardLp());

        page.addView(section("CONTEXT"));
        LinearLayout context = card();
        context.addView(status("Screen text", Prefs.screenContext(this)));
        context.addView(status("Screenshots", Prefs.screenshot(this)));
        TextView screenshotNote = UiKit.text(this,
                "Visual context, previews, and screen-region selection.",
                11, UiKit.MUTED, false);
        screenshotNote.setPadding(UiKit.dp(this, 4), 0, 0, UiKit.dp(this, 7));
        context.addView(screenshotNote);
        context.addView(notificationAccessRow());
        context.addView(runtimePermissionRow("Approximate location", Manifest.permission.ACCESS_COARSE_LOCATION, REQ_LOCATION));
        context.addView(status("Per-app behavior", true));
        page.addView(context, cardLp());

        page.addView(section("AI & PERSONALIZATION"));
        LinearLayout ai = card();
        ai.addView(status("ChatGPT account", ChatGptAuth.getAccountInfo(this) != null));
        ai.addView(status("Smart Auto routing", true));
        ai.addView(status("Orbit Memory", Prefs.memoryEnabled(this)));
        ai.addView(status("Attachments & PDF", true));
        page.addView(ai, cardLp());

        page.addView(section("DEVICE ACTIONS"));
        LinearLayout actions = card();
        actions.addView(runtimePermissionRow("Flashlight control", Manifest.permission.CAMERA, REQ_CAMERA));
        actions.addView(status("Timers & alarms", true));
        actions.addView(status("Open apps", true));
        actions.addView(runtimePermissionRow("Contact lookup", Manifest.permission.READ_CONTACTS, REQ_CONTACTS));
        // Orbit writes events itself once this is granted, rather than only opening the composer.
        // Nothing here requests it on its own; the row exists so the state is visible and
        // manageable, and Orbit still asks at the moment a Calendar action is confirmed.
        actions.addView(runtimePermissionRow("Calendar events", Manifest.permission.WRITE_CALENDAR, REQ_CALENDAR));
        actions.addView(status("Chained action engine", true));
        actions.addView(specialAccessRow("Brightness control", true));
        actions.addView(specialAccessRow("Do Not Disturb control", false));
        actions.addView(status("Background completions", true));
        page.addView(actions, cardLp());

        page.addView(section("ROUTINE AUTOMATION"));
        LinearLayout automation = card();
        automation.addView(statusWithText("Time triggers", "Available", true));
        automation.addView(statusWithText("Location triggers", "Available", true));
        automation.addView(routineAutomationAccessRow("Precise timing", true));
        automation.addView(locationAutomationAccessRow());
        automation.addView(routineAutomationAccessRow("Trigger alerts", false));
        TextView automationNote = UiKit.text(this,
                "Precise timing uses Android's Alarms & reminders access for time triggers and Orbit reminders. Location triggers require precise plus background location, while Trigger alerts let Orbit hand off any automatic routine that needs a visible app or confirmation.",
                12, UiKit.MUTED, false);
        automationNote.setPadding(0, UiKit.dp(this,10), 0, 0);
        automation.addView(automationNote);
        page.addView(automation, cardLp());

        return scroll;
    }

    private boolean permission(String permission) {
        return CapabilityAccessHelper.permissionGranted(this, permission);
    }

    private LinearLayout runtimePermissionRow(String name, String permission, int requestCode) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(UiKit.dp(this, 48));
        row.setPadding(0, UiKit.dp(this, 2), 0, UiKit.dp(this, 2));

        TextView label = UiKit.text(this, name, 14, UiKit.TEXT, false);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView state = UiKit.text(this, "Checking…", 12, UiKit.MUTED, true);
        row.addView(state);

        Button manage = compactSetupButton("Set up");
        LinearLayout.LayoutParams manageLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 36));
        manageLp.setMargins(UiKit.dp(this, 10), 0, 0, 0);
        row.addView(manage, manageLp);

        if (requestCode == REQ_MICROPHONE) {
            microphoneState = state;
            microphoneManage = manage;
        } else if (requestCode == REQ_LOCATION) {
            locationState = state;
            locationManage = manage;
        } else if (requestCode == REQ_CAMERA) {
            flashlightState = state;
            flashlightManage = manage;
        } else if (requestCode == REQ_CONTACTS) {
            contactsState = state;
            contactsManage = manage;
        } else if (requestCode == REQ_CALENDAR) {
            calendarState = state;
            calendarManage = manage;
        }

        if (requestCode == REQ_CALENDAR) {
            manage.setOnClickListener(v ->
                    CapabilityAccessHelper.requestOrManageCalendar(this, requestCode));
        } else {
            manage.setOnClickListener(v -> CapabilityAccessHelper.requestOrManageRuntimePermission(
                    this, permission, requestCode));
        }
        refreshRuntimeAccessRows();
        return row;
    }

    private LinearLayout notificationAccessRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(UiKit.dp(this, 48));
        row.setPadding(0, UiKit.dp(this, 2), 0, UiKit.dp(this, 2));

        TextView label = UiKit.text(this, "Notification intelligence", 14, UiKit.TEXT, false);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        notificationAccessState = UiKit.text(this, "Checking…", 12, UiKit.MUTED, true);
        row.addView(notificationAccessState);

        notificationAccessManage = compactSetupButton("Set up");
        LinearLayout.LayoutParams manageLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 36));
        manageLp.setMargins(UiKit.dp(this, 10), 0, 0, 0);
        row.addView(notificationAccessManage, manageLp);
        notificationAccessManage.setOnClickListener(v ->
                CapabilityAccessHelper.openNotificationIntelligence(this));
        refreshNotificationAccessRow();
        return row;
    }

    private void refreshRuntimeAccessRows() {
        refreshRuntimeRow(microphoneState, microphoneManage, permission(Manifest.permission.RECORD_AUDIO));
        refreshRuntimeRow(locationState, locationManage, permission(Manifest.permission.ACCESS_COARSE_LOCATION));
        refreshRuntimeRow(flashlightState, flashlightManage, permission(Manifest.permission.CAMERA));
        refreshRuntimeRow(contactsState, contactsManage, permission(Manifest.permission.READ_CONTACTS));
        // Both halves are needed before Orbit can choose a calendar and verify what it wrote,
        // so a partial grant reads as "Needs access" rather than as ready.
        refreshRuntimeRow(calendarState, calendarManage, OrbitCalendarStore.hasAccess(this));
    }

    private void refreshRuntimeRow(TextView state, Button manage, boolean ready) {
        if (state != null) {
            state.setText(ready ? "Ready" : "Needs access");
            state.setTextColor(ready ? UiKit.SUCCESS : UiKit.MUTED);
        }
        if (manage != null) manage.setText(ready ? "Manage" : "Set up");
    }

    private void refreshNotificationAccessRow() {
        boolean ready = NotificationAccess.enabled(this);
        if (notificationAccessState != null) {
            notificationAccessState.setText(ready ? "Ready" : "Needs access");
            notificationAccessState.setTextColor(ready ? UiKit.SUCCESS : UiKit.MUTED);
        }
        if (notificationAccessManage != null) notificationAccessManage.setText(ready ? "Manage" : "Set up");
    }

    private LinearLayout specialAccessRow(String name, boolean brightness) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(UiKit.dp(this, 48));
        row.setPadding(0, UiKit.dp(this, 2), 0, UiKit.dp(this, 2));

        TextView label = UiKit.text(this, name, 14, UiKit.TEXT, false);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView state = UiKit.text(this, "Checking…", 12, UiKit.MUTED, true);
        row.addView(state);

        Button setup = compactSetupButton("Set up");
        LinearLayout.LayoutParams setupLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 36));
        setupLp.setMargins(UiKit.dp(this, 10), 0, 0, 0);
        row.addView(setup, setupLp);

        if (brightness) {
            brightnessState = state;
            brightnessSetup = setup;
            setup.setOnClickListener(v -> CapabilityAccessHelper.openWriteSettings(this));
        } else {
            dndState = state;
            dndSetup = setup;
            setup.setOnClickListener(v -> CapabilityAccessHelper.openDndAccess(this));
        }
        refreshSpecialAccessRows();
        return row;
    }

    private LinearLayout routineAutomationAccessRow(String name, boolean preciseTiming) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(UiKit.dp(this, 48));
        row.setPadding(0, UiKit.dp(this, 2), 0, UiKit.dp(this, 2));

        TextView label = UiKit.text(this, name, 14, UiKit.TEXT, false);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView state = UiKit.text(this, "Checking…", 12, UiKit.MUTED, true);
        row.addView(state);

        Button manage = compactSetupButton("Set up");
        LinearLayout.LayoutParams manageLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 36));
        manageLp.setMargins(UiKit.dp(this, 10), 0, 0, 0);
        row.addView(manage, manageLp);

        if (preciseTiming) {
            preciseTimingState = state;
            preciseTimingManage = manage;
            manage.setOnClickListener(v -> CapabilityAccessHelper.openExactAlarmAccess(this));
        } else {
            triggerAlertsState = state;
            triggerAlertsManage = manage;
            manage.setOnClickListener(v -> CapabilityAccessHelper.requestOrManageTriggerAlerts(
                    this, REQ_TRIGGER_ALERTS));
        }
        refreshRoutineAutomationRows();
        return row;
    }

    private LinearLayout locationAutomationAccessRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(UiKit.dp(this, 48));
        row.setPadding(0, UiKit.dp(this, 2), 0, UiKit.dp(this, 2));

        TextView label = UiKit.text(this, "Location automation", 14, UiKit.TEXT, false);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        locationAutomationState = UiKit.text(this, "Checking…", 12, UiKit.MUTED, true);
        row.addView(locationAutomationState);

        locationAutomationManage = compactSetupButton("Set up");
        LinearLayout.LayoutParams manageLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 36));
        manageLp.setMargins(UiKit.dp(this, 10), 0, 0, 0);
        row.addView(locationAutomationManage, manageLp);
        locationAutomationManage.setOnClickListener(v -> CapabilityAccessHelper.setupLocationAutomation(
                this, REQ_ROUTINE_FINE_LOCATION, REQ_ROUTINE_BACKGROUND_LOCATION));
        refreshRoutineAutomationRows();
        return row;
    }

    private void refreshRoutineAutomationRows() {
        boolean fineLocation = RoutineLocationTriggerScheduler.hasFineLocation(this);
        boolean backgroundLocation = RoutineLocationTriggerScheduler.hasBackgroundLocation(this);
        boolean locationOn = RoutineLocationTriggerScheduler.isLocationEnabled(this);
        boolean locationReady = fineLocation && backgroundLocation && locationOn;
        if (locationAutomationState != null) {
            String state = CapabilityAccessHelper.locationAutomationStatus(this);
            locationAutomationState.setText(state);
            locationAutomationState.setTextColor(locationReady ? UiKit.SUCCESS : UiKit.MUTED);
        }
        if (locationAutomationManage != null) locationAutomationManage.setText(locationReady ? "Manage" : "Set up");

        boolean exact = RoutineTriggerScheduler.canScheduleExact(this);
        if (preciseTimingState != null) {
            preciseTimingState.setText(exact ? "Ready" : "Approximate");
            preciseTimingState.setTextColor(exact ? UiKit.SUCCESS : UiKit.MUTED);
        }
        if (preciseTimingManage != null) {
            if (Build.VERSION.SDK_INT < 31) {
                preciseTimingManage.setVisibility(View.GONE);
            } else {
                preciseTimingManage.setVisibility(View.VISIBLE);
                preciseTimingManage.setText(exact ? "Manage" : "Set up");
            }
        }

        boolean alerts = RoutineTriggerNotifier.notificationsAllowed(this);
        if (triggerAlertsState != null) {
            triggerAlertsState.setText(alerts ? "Ready" : "Needs access");
            triggerAlertsState.setTextColor(alerts ? UiKit.SUCCESS : UiKit.MUTED);
        }
        if (triggerAlertsManage != null) {
            triggerAlertsManage.setText(alerts ? "Manage" : "Set up");
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_TRIGGER_ALERTS || requestCode == REQ_ROUTINE_FINE_LOCATION ||
                requestCode == REQ_ROUTINE_BACKGROUND_LOCATION) {
            RoutineTriggerScheduler.rescheduleAll(this);
            refreshRoutineAutomationRows();
        }
        if (requestCode == REQ_MICROPHONE || requestCode == REQ_LOCATION ||
                requestCode == REQ_CAMERA || requestCode == REQ_CONTACTS ||
                requestCode == REQ_CALENDAR) {
            refreshRuntimeAccessRows();
        }
    }

    private void tintDialogText(View v) {
        if (v == null) return;
        if (v instanceof TextView && !(v instanceof Button)) ((TextView) v).setTextColor(UiKit.TEXT);
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) tintDialogText(group.getChildAt(i));
        }
    }

    private Button compactSetupButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(11);
        b.setTextColor(UiKit.accent(this));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setPadding(UiKit.dp(this, 11), 0, UiKit.dp(this, 11), 0);
        b.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), 72), UiKit.accent(this), 12, this));
        UiKit.pressScale(b);
        return b;
    }

    private void refreshSpecialAccessRows() {
        boolean brightnessReady = OrbitPermissionHelper.canWriteSystemSettings(this);
        if (brightnessState != null) {
            brightnessState.setText(brightnessReady ? "Ready" : "Needs access");
            brightnessState.setTextColor(brightnessReady ? UiKit.SUCCESS : UiKit.MUTED);
        }
        if (brightnessSetup != null) {
            brightnessSetup.setVisibility(View.VISIBLE);
            brightnessSetup.setText(brightnessReady ? "Manage" : "Set up");
        }

        boolean dndReady = OrbitPermissionHelper.hasDndAccess(this);
        if (dndState != null) {
            dndState.setText(dndReady ? "Ready" : "Needs access");
            dndState.setTextColor(dndReady ? UiKit.SUCCESS : UiKit.MUTED);
        }
        if (dndSetup != null) {
            dndSetup.setVisibility(View.VISIBLE);
            dndSetup.setText(dndReady ? "Manage" : "Set up");
        }
    }

    private LinearLayout status(String name, boolean enabled) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(UiKit.dp(this, 48));
        row.setPadding(0, UiKit.dp(this, 2), 0, UiKit.dp(this, 2));
        TextView label = UiKit.text(this, name, 14, UiKit.TEXT, false);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView state = UiKit.text(this, enabled ? "Ready" : "Off / unavailable", 12,
                enabled ? UiKit.SUCCESS : UiKit.MUTED, true);
        row.addView(state);
        return row;
    }

    private LinearLayout statusWithText(String name, String stateText, boolean positive) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(UiKit.dp(this, 48));
        row.setPadding(0, UiKit.dp(this, 2), 0, UiKit.dp(this, 2));
        TextView label = UiKit.text(this, name, 14, UiKit.TEXT, false);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView state = UiKit.text(this, stateText, 12, positive ? UiKit.SUCCESS : UiKit.MUTED, true);
        row.addView(state);
        return row;
    }

    private TextView section(String text) {
        TextView t = UiKit.text(this, text, 11, UiKit.MUTED, true);
        t.setLetterSpacing(0.13f);
        t.setPadding(UiKit.dp(this,4), UiKit.dp(this,18), 0, UiKit.dp(this,8));
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(UiKit.dp(this,16), UiKit.dp(this,10), UiKit.dp(this,16), UiKit.dp(this,10));
        c.setBackground(UiKit.outlined(UiKit.SURFACE,
                UiKit.withAlpha(UiKit.accent(this),38),20,this));
        return c;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0,0,0,UiKit.dp(this,2));
        return lp;
    }

    private ImageButton iconButton(int res, String desc) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setImageTintList(ColorStateList.valueOf(UiKit.accent(this)));
        b.setBackground(UiKit.ripple(UiKit.SURFACE,UiKit.accent(this),18,this));
        b.setContentDescription(desc);
        b.setPadding(UiKit.dp(this,11),UiKit.dp(this,11),UiKit.dp(this,11),UiKit.dp(this,11));
        UiKit.pressScale(b);
        return b;
    }
}
