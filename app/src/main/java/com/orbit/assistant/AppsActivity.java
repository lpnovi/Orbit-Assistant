package com.orbit.assistant;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Per-app behavior browser introduced in Orbit 0.5. */
public class AppsActivity extends Activity {
    private LinearLayout list;
    private EditText search;
    private final List<AppRow> apps = new ArrayList<>();

    private static final class AppRow {
        final String label, pkg;
        AppRow(String label, String pkg) { this.label = label; this.pkg = pkg; }
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.syncTheme(this);
        Window w = getWindow();
        w.setStatusBarColor(UiKit.BG);
        w.setNavigationBarColor(UiKit.BG);
        loadApps();
        View root = build();
        setContentView(root);
        UiKit.applyActivityInsets(this, root, true);
        refresh();
    }

    @Override protected void onResume() { super.onResume(); UiPresence.enter(this); refresh(); }
    @Override protected void onPause() { UiPresence.leave(this); super.onPause(); }

    private void loadApps() {
        apps.clear();
        Map<String,AppRow> unique = new HashMap<>();
        try {
            Intent launcher = new Intent(Intent.ACTION_MAIN);
            launcher.addCategory(Intent.CATEGORY_LAUNCHER);
            PackageManager pm = getPackageManager();
            List<ResolveInfo> found = pm.queryIntentActivities(launcher, 0);
            for (ResolveInfo r : found) {
                if (r.activityInfo == null || r.activityInfo.packageName == null) continue;
                String pkg = r.activityInfo.packageName;
                if (getPackageName().equals(pkg)) continue;
                CharSequence l = r.loadLabel(pm);
                unique.put(pkg, new AppRow(l == null ? pkg : l.toString(), pkg));
            }
        } catch (Exception ignored) {}

        for (AppProfileStore.Profile p : AppProfileStore.list(this)) {
            if (!unique.containsKey(p.packageName)) {
                unique.put(p.packageName,
                        new AppRow(p.label.isEmpty() ? p.packageName : p.label, p.packageName));
            }
        }

        String lastPkg = DiagnosticStore.lastForegroundPackage(this);
        String lastLabel = DiagnosticStore.lastForegroundAppLabel(this);
        if (lastPkg != null && !lastPkg.isEmpty() &&
                !getPackageName().equals(lastPkg) && !unique.containsKey(lastPkg)) {
            unique.put(lastPkg, new AppRow(
                    lastLabel == null || lastLabel.isEmpty() ? lastPkg : lastLabel, lastPkg));
        }

        apps.addAll(unique.values());
        apps.sort(Comparator.comparing(a -> a.label.toLowerCase(Locale.US)));
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.BG);
        root.setPadding(UiKit.dp(this,18), UiKit.dp(this,10), UiKit.dp(this,18), 0);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = iconButton(R.drawable.ic_back, "Back");
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(UiKit.dp(this,48), UiKit.dp(this,48)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(UiKit.text(this, "Apps", 24, UiKit.TEXT, true));
        titles.addView(UiKit.text(this, "Per-app behavior", 12, UiKit.MUTED, false));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleLp.setMargins(UiKit.dp(this,14), 0, 0, 0);
        top.addView(titles, titleLp);
        root.addView(top);

        TextView note = UiKit.text(this,
                "Orbit adapts automatically to each app. Open any app below to customize privacy, AI strength, screen behavior, or all three quick actions.",
                13, UiKit.MUTED, false);
        note.setPadding(UiKit.dp(this,4), UiKit.dp(this,12), UiKit.dp(this,4), UiKit.dp(this,12));
        root.addView(note);

        LinearLayout searchBox = new LinearLayout(this);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setPadding(UiKit.dp(this,12),0,UiKit.dp(this,12),0);
        searchBox.setBackground(UiKit.outlined(UiKit.SURFACE, Color.rgb(47,52,66),18,this));

        ImageButton icon = new ImageButton(this);
        icon.setImageResource(R.drawable.ic_search);
        icon.setImageTintList(ColorStateList.valueOf(UiKit.MUTED));
        icon.setBackgroundColor(Color.TRANSPARENT);
        searchBox.addView(icon, new LinearLayout.LayoutParams(UiKit.dp(this,34),UiKit.dp(this,34)));

        search = new EditText(this);
        search.setHint("Search installed apps");
        search.setHintTextColor(Color.rgb(113,119,135));
        search.setTextColor(UiKit.TEXT);
        search.setTextSize(14);
        search.setSingleLine(true);
        search.setBackgroundColor(Color.TRANSPARENT);
        searchBox.addView(search, new LinearLayout.LayoutParams(0,UiKit.dp(this,48),1));
        root.addView(searchBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,UiKit.dp(this,52)));

        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int st,int c,int a){}
            public void onTextChanged(CharSequence s,int st,int b,int c){ refresh(); }
            public void afterTextChanged(Editable e){}
        });

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0,UiKit.dp(this,12),0,UiKit.dp(this,36));
        scroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,0,1));
        return root;
    }

    private void refresh() {
        if (list == null) return;
        list.removeAllViews();
        String q = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.US);
        int shown = 0;

        for (AppRow app : apps) {
            if (!q.isEmpty() && !app.label.toLowerCase(Locale.US).contains(q) &&
                    !app.pkg.toLowerCase(Locale.US).contains(q)) continue;

            AppProfileStore.Profile p = AppProfileStore.get(this, app.pkg);
            LinearLayout card = card();
            card.setOrientation(LinearLayout.VERTICAL);

            TextView title = UiKit.text(this, app.label,15,UiKit.TEXT,true);
            title.setMaxLines(1);
            card.addView(title);

            TextView pkg = UiKit.text(this, app.pkg,11,UiKit.MUTED,false);
            pkg.setMaxLines(1);
            pkg.setPadding(0,UiKit.dp(this,3),0,0);
            card.addView(pkg);

            ScreenContextClassifier.Result inferred = ScreenContextClassifier.classify(
                    this, "", false, app.pkg, app.label);
            String categorySummary;
            if (p.isDefault()) {
                categorySummary = inferred.confidence >= 65 &&
                        !AppProfileStore.CATEGORY_GENERIC.equals(inferred.category)
                        ? "Automatic · " + inferred.label : "Automatic";
            } else {
                categorySummary = AppProfileStore.CATEGORY_AUTO.equals(p.category)
                        ? "Automatic context" : AppProfileStore.categoryLabel(p.category);
            }
            String summary = categorySummary + "  •  " +
                    AppProfileStore.effectivePrivacyLabel(this, app.pkg) + " privacy" +
                    (AppProfileStore.hasCustomActions(p) ? "  •  Custom actions" : "");

            TextView state = UiKit.text(this, summary, 12,
                    p.isDefault() ? UiKit.MUTED : UiKit.accent(this), false);
            state.setPadding(0,UiKit.dp(this,6),0,0);
            state.setMaxLines(2);
            card.addView(state);

            card.setOnClickListener(v -> startActivity(new Intent(this, AppProfileActivity.class)
                    .putExtra(AppProfileActivity.EXTRA_PACKAGE, app.pkg)
                    .putExtra(AppProfileActivity.EXTRA_LABEL, app.label)));
            UiKit.pressScale(card);
            list.addView(card, cardLp());
            shown++;
        }

        if (shown == 0) {
            LinearLayout empty = card();
            empty.addView(UiKit.text(this, "No matching apps", 15, UiKit.TEXT, true));
            list.addView(empty, cardLp());
        }
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setPadding(UiKit.dp(this,16),UiKit.dp(this,14),UiKit.dp(this,16),UiKit.dp(this,14));
        c.setBackground(UiKit.outlined(UiKit.SURFACE,
                UiKit.withAlpha(UiKit.accent(this),34),20,this));
        return c;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0,0,0,UiKit.dp(this,10));
        return lp;
    }

    private ImageButton iconButton(int res,String desc) {
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
