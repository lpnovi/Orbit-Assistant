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
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.List;

public class NotificationsActivity extends Activity {
    private LinearLayout page;
    private TextView subtitle;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.syncTheme(this);
        Window w = getWindow();
        w.setStatusBarColor(UiKit.BG);
        w.setNavigationBarColor(UiKit.BG);
        rebuild();
    }

    @Override protected void onResume() {
        super.onResume();
        UiPresence.enter(this);
        rebuild();
    }

    @Override protected void onPause() {
        UiPresence.leave(this);
        super.onPause();
    }

    private void rebuild() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(UiKit.BG);

        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 18);
        page.setPadding(p, UiKit.dp(this, 18), p, UiKit.dp(this, 48));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);

        ImageButton back = new ImageButton(this);
        back.setImageResource(com.orbit.assistant.R.drawable.ic_back);
        back.setImageTintList(ColorStateList.valueOf(UiKit.accent(this)));
        back.setBackground(UiKit.ripple(UiKit.SURFACE, UiKit.accent(this), 18, this));
        back.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 12),
                UiKit.dp(this, 12), UiKit.dp(this, 12));
        UiKit.pressScale(back);
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 48)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(UiKit.dp(this, 8), 0, 0, 0);
        titles.addView(UiKit.text(this, "Notifications", 25, UiKit.TEXT, true));
        subtitle = UiKit.text(this, "", 12, UiKit.MUTED, false);
        titles.addView(subtitle);
        top.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(top);

        addAccessCard();
        addPrivacyCard();
        addAppFilters();
        addRecentHistory();

        setContentView(scroll);
        UiKit.applyActivityInsets(this, scroll, true);
        refreshSubtitle();
    }

    private void addAccessCard() {
        page.addView(sectionTitle("ACCESS"));
        LinearLayout card = card();

        boolean enabled = NotificationAccess.enabled(this);
        TextView status = UiKit.text(this,
                enabled ? "Notification access is enabled" : "Notification access is not enabled",
                15, enabled ? UiKit.SUCCESS : UiKit.TEXT, true);
        card.addView(status);

        TextView desc = UiKit.text(this,
                enabled
                        ? "Orbit can now build a private local history from notifications that arrive on this phone."
                        : "Android requires you to explicitly grant Notification access before Orbit can see notification content.",
                13, UiKit.MUTED, false);
        desc.setPadding(0, UiKit.dp(this, 7), 0, UiKit.dp(this, 12));
        card.addView(desc);

        Button access = primaryButton(enabled ? "Open notification access settings"
                : "Open notification access");
        access.setOnClickListener(v -> {
            try { startActivity(NotificationAccess.settingsIntent()); }
            catch (Exception e) { Toast.makeText(this,
                    "Could not open notification access settings", Toast.LENGTH_SHORT).show(); }
        });
        card.addView(access, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48)));

        page.addView(card, cardLp());
    }

    private void addPrivacyCard() {
        page.addView(sectionTitle("PRIVACY & RETENTION"));
        LinearLayout card = card();

        CheckBox ai = new CheckBox(this);
        ai.setText("Use relevant notification history in AI answers");
        ai.setTextColor(UiKit.TEXT);
        ai.setTextSize(14);
        ai.setButtonTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{UiKit.accent(this), Color.rgb(90,94,105)}));
        ai.setChecked(Prefs.notificationAiEnabled(this));
        ai.setOnCheckedChangeListener((button, checked) ->
                Prefs.get(this).edit().putBoolean(Prefs.NOTIFICATION_AI_ENABLED, checked).apply());
        card.addView(ai);

        TextView help = UiKit.text(this,
                "Orbit stores notification history locally. It only supplies that history to the AI when your prompt is clearly asking about notifications, missed messages, or what you missed.",
                12, UiKit.MUTED, false);
        help.setPadding(0, UiKit.dp(this, 7), 0, UiKit.dp(this, 12));
        card.addView(help);

        Button retention = secondaryButton("Keep history: " +
                retentionLabel(Prefs.notificationRetentionDays(this)));
        retention.setOnClickListener(v -> {
            String[] labels = {"1 day", "3 days", "7 days", "14 days", "30 days"};
            int[] values = {1,3,7,14,30};
            int selected = 2;
            int current = Prefs.notificationRetentionDays(this);
            for (int i = 0; i < values.length; i++) if (values[i] == current) selected = i;
            final int selectedIndex = selected;
            UiKit.showOrbitMenu(this, retention, labels, selectedIndex, (index, label) -> {
                Prefs.get(this).edit().putInt(Prefs.NOTIFICATION_RETENTION_DAYS,
                        values[index]).apply();
                NotificationStore.all(this); // prune immediately
                rebuild();
            });
        });
        card.addView(retention, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 46)));

        TextView warning = UiKit.text(this,
                "For sensitive apps such as banking, authenticator, password-manager, or private-work apps, exclude them below. Excluded apps are not kept in Orbit notification history.",
                12, UiKit.MUTED, false);
        warning.setPadding(0, UiKit.dp(this, 10), 0, 0);
        card.addView(warning);

        Button clear = secondaryButton("Clear local notification history");
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 46));
        clearLp.setMargins(0, UiKit.dp(this, 12), 0, 0);
        card.addView(clear, clearLp);
        clear.setOnClickListener(v -> {
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("Clear notification history?")
                    .setMessage("This deletes the locally saved notification contents. Your app include/exclude choices stay unchanged.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear", (d,w) -> {
                        NotificationStore.clear(this);
                        rebuild();
                    }).create();
            styleOrbitDialog(dialog);
            dialog.show();
        });

        page.addView(card, cardLp());
    }

    private void addAppFilters() {
        List<NotificationStore.AppSummary> apps = NotificationStore.appSummaries(this);
        if (apps.isEmpty()) return;

        page.addView(sectionTitle("APPS"));
        for (NotificationStore.AppSummary app : apps) {
            LinearLayout row = card();
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.addView(UiKit.text(this,
                    app.appLabel.isEmpty() ? app.packageName : app.appLabel,
                    14, UiKit.TEXT, true));
            String count = app.count == 0 ? "No saved notifications"
                    : app.count + (app.count == 1 ? " saved notification" : " saved notifications");
            TextView meta = UiKit.text(this, count, 11, UiKit.MUTED, false);
            meta.setPadding(0, UiKit.dp(this, 4), 0, 0);
            info.addView(meta);
            row.addView(info, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            Button toggle = smallButton(app.blocked ? "Excluded" : "Included");
            if (!app.blocked) toggle.setTextColor(UiKit.accent(this));
            toggle.setOnClickListener(v -> {
                NotificationStore.setBlocked(this, app.packageName, !app.blocked);
                Toast.makeText(this,
                        app.blocked ? app.appLabel + " included"
                                : app.appLabel + " excluded and its saved notifications removed",
                        Toast.LENGTH_SHORT).show();
                rebuild();
            });
            row.addView(toggle, new LinearLayout.LayoutParams(
                    UiKit.dp(this, 92), UiKit.dp(this, 40)));
            page.addView(row, cardLp());
        }
    }

    private void addRecentHistory() {
        page.addView(sectionTitle("RECENT HISTORY"));
        List<NotificationStore.Item> items = NotificationStore.all(this);

        if (items.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(UiKit.text(this, "Nothing captured yet", 15, UiKit.TEXT, true));
            TextView body = UiKit.text(this,
                    NotificationAccess.enabled(this)
                            ? "New notifications will appear here as they arrive."
                            : "Grant Notification access first. Orbit cannot read past notifications retroactively.",
                    12, UiKit.MUTED, false);
            body.setPadding(0, UiKit.dp(this, 6), 0, 0);
            empty.addView(body);
            page.addView(empty, cardLp());
            return;
        }

        int shown = 0;
        for (NotificationStore.Item n : items) {
            if (shown >= 35) break;
            LinearLayout card = card();

            LinearLayout head = new LinearLayout(this);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(UiKit.text(this,
                    n.appLabel.isEmpty() ? n.packageName : n.appLabel,
                    12, UiKit.accent(this), true),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            head.addView(UiKit.text(this,
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(n.postedAt),
                    10, UiKit.MUTED, false));
            card.addView(head);

            String title = !n.conversationTitle.isEmpty() ? n.conversationTitle : n.title;
            if (!title.isEmpty()) {
                TextView t = UiKit.text(this, title, 14, UiKit.TEXT, true);
                t.setPadding(0, UiKit.dp(this, 6), 0, 0);
                card.addView(t);
            }

            String body = n.compactBody();
            if (!body.isEmpty()) {
                TextView b = UiKit.text(this, body, 12, UiKit.MUTED, false);
                b.setPadding(0, UiKit.dp(this, 4), 0, 0);
                card.addView(b);
            }

            page.addView(card, cardLp());
            shown++;
        }
    }

    private void refreshSubtitle() {
        if (subtitle == null) return;
        subtitle.setText(NotificationStore.count(this) +
                (NotificationStore.count(this) == 1 ? " saved notification" : " saved notifications"));
    }

    private String retentionLabel(int days) {
        return days == 1 ? "1 day" : days + " days";
    }

    private TextView sectionTitle(String text) {
        TextView t = UiKit.text(this, text, 11, UiKit.MUTED, true);
        t.setLetterSpacing(.16f);
        t.setPadding(UiKit.dp(this, 5), UiKit.dp(this, 18), 0, UiKit.dp(this, 9));
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 14),
                UiKit.dp(this, 16), UiKit.dp(this, 14));
        c.setBackground(UiKit.outlined(UiKit.SURFACE,
                UiKit.withAlpha(UiKit.accent(this), 34), 20, this));
        return c;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, UiKit.dp(this, 10));
        return lp;
    }

    private Button primaryButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTextColor(UiKit.onAccent(this));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setBackground(UiKit.ripple(UiKit.accent(this), UiKit.onAccent(this), 16, this));
        UiKit.pressScale(b);
        return b;
    }

    private Button secondaryButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTextColor(UiKit.TEXT);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setBackground(UiKit.rippleOutlined(UiKit.SURFACE,
                UiKit.withAlpha(UiKit.accent(this), 65), UiKit.accent(this), 16, this));
        UiKit.pressScale(b);
        return b;
    }

    private Button smallButton(String text) {
        Button b = secondaryButton(text);
        b.setTextSize(11);
        return b;
    }

    private void styleOrbitDialog(AlertDialog dialog) {
        if (dialog.getWindow() != null) {
            dialog.getWindow().setWindowAnimations(R.style.OrbitPopupAnimation);
            dialog.getWindow().setBackgroundDrawable(
                    UiKit.rounded(UiKit.SURFACE, 22, NotificationsActivity.this));
        }
        dialog.setOnShowListener(ignore -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(
                        UiKit.rounded(UiKit.SURFACE, 22, NotificationsActivity.this));
                tintDialogText(dialog.getWindow().getDecorView());
            }

            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (positive != null) positive.setTextColor(UiKit.accent(this));
            if (negative != null) negative.setTextColor(UiKit.accent(this));
        });
    }

    private void tintDialogText(View view) {
        if (view == null) return;

        if (view instanceof TextView && !(view instanceof Button)) {
            ((TextView) view).setTextColor(UiKit.TEXT);
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                tintDialogText(group.getChildAt(i));
            }
        }
    }
}
