package com.orbit.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Inspectable local memory manager. Expanded as Memory 2.0 in Orbit 0.5.9. */
public class MemoryActivity extends Activity {
    private LinearLayout list;
    private TextView count;
    private EditText searchInput;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.syncTheme(this);
        Window w = getWindow();
        w.setStatusBarColor(UiKit.BG);
        w.setNavigationBarColor(UiKit.BG);
        View root = build();
        setContentView(root);
        UiKit.applyActivityInsets(this, root, true);
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
        titles.addView(UiKit.text(this, "Memory", 24, UiKit.TEXT, true));
        count = UiKit.text(this, "", 12, UiKit.MUTED, false);
        titles.addView(count);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleLp.setMargins(UiKit.dp(this,14), 0, 0, 0);
        top.addView(titles, titleLp);

        Button add = smallButton("+ Add");
        add.setOnClickListener(v -> editMemory(null));
        top.addView(add, new LinearLayout.LayoutParams(UiKit.dp(this,96), UiKit.dp(this,44)));
        root.addView(top);

        TextView explainer = UiKit.text(this,
                "Orbit Memory stores user-approved facts and preferences locally. " +
                        "0.5.9 now selects only memories that look relevant to each request.",
                13, UiKit.MUTED, false);
        explainer.setPadding(UiKit.dp(this,4), UiKit.dp(this,14),
                UiKit.dp(this,4), UiKit.dp(this,10));
        root.addView(explainer);

        LinearLayout toggleCard = card();

        CheckBox enabled = orbitCheckBox("Use Orbit Memory in AI responses");
        enabled.setChecked(Prefs.memoryEnabled(this));
        enabled.setOnCheckedChangeListener((b, checked) ->
                Prefs.get(this).edit().putBoolean(Prefs.MEMORY_ENABLED, checked).apply());
        toggleCard.addView(enabled);

        CheckBox suggestions = orbitCheckBox("Suggest useful memories");
        suggestions.setChecked(Prefs.memorySuggestions(this));
        suggestions.setOnCheckedChangeListener((b, checked) ->
                Prefs.get(this).edit().putBoolean(Prefs.MEMORY_SUGGESTIONS, checked).apply());
        toggleCard.addView(suggestions);

        CheckBox usage = orbitCheckBox("Show used-memory indicator in chats");
        usage.setChecked(Prefs.memoryUsageIndicator(this));
        usage.setOnCheckedChangeListener((b, checked) ->
                Prefs.get(this).edit().putBoolean(Prefs.MEMORY_USAGE_INDICATOR, checked).apply());
        toggleCard.addView(usage);

        TextView note = UiKit.text(this,
                "Suggested memories are never saved automatically. Disabled memories stay on this device but are not supplied to the AI.",
                12, UiKit.MUTED, false);
        note.setPadding(0, UiKit.dp(this,6), 0, 0);
        toggleCard.addView(note);
        root.addView(toggleCard, cardLp());

        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setHint("Search memories");
        searchInput.setTextColor(UiKit.TEXT);
        searchInput.setHintTextColor(UiKit.MUTED);
        searchInput.setTextSize(14);
        searchInput.setPadding(UiKit.dp(this,14), 0, UiKit.dp(this,14), 0);
        searchInput.setBackground(UiKit.outlined(UiKit.SURFACE,
                UiKit.withAlpha(UiKit.accent(this), 52), 16, this));
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                refresh();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this,46));
        searchLp.setMargins(0, 0, 0, UiKit.dp(this,8));
        root.addView(searchInput, searchLp);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, UiKit.dp(this,4), 0, UiKit.dp(this,36));
        scroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return root;
    }

    private void refresh() {
        if (list == null) return;
        list.removeAllViews();

        List<MemoryStore.Memory> allMemories = MemoryStore.list(this);
        String query = searchInput == null ? "" : searchInput.getText().toString().trim();
        List<MemoryStore.Memory> shown = query.isEmpty()
                ? allMemories
                : MemoryStore.search(this, query);

        int pinned = 0;
        int disabled = 0;
        for (MemoryStore.Memory m : allMemories) {
            if (m.pinned) pinned++;
            if (!m.enabled) disabled++;
        }

        StringBuilder countText = new StringBuilder();
        countText.append(allMemories.size())
                .append(allMemories.size() == 1 ? " saved memory" : " saved memories");
        if (pinned > 0) countText.append(" · ").append(pinned).append(" pinned");
        if (disabled > 0) countText.append(" · ").append(disabled).append(" off");
        count.setText(countText.toString());

        if (allMemories.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(UiKit.text(this, "Nothing saved yet", 16, UiKit.TEXT, true));
            TextView t = UiKit.text(this,
                    "Try saying “Remember that I prefer Google Maps” or tap Add.",
                    13, UiKit.MUTED, false);
            t.setPadding(0, UiKit.dp(this,6), 0, 0);
            empty.addView(t);
            list.addView(empty, cardLp());
            return;
        }

        if (!query.isEmpty() && shown.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(UiKit.text(this, "No matching memories", 15, UiKit.TEXT, true));
            TextView t = UiKit.text(this,
                    "Try a name, app, device, preference, or another keyword.",
                    12, UiKit.MUTED, false);
            t.setPadding(0, UiKit.dp(this,5), 0, 0);
            empty.addView(t);
            list.addView(empty, cardLp());
            return;
        }

        for (MemoryStore.Memory m : shown) {
            LinearLayout row = card();
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);

            TextView body = UiKit.text(this, m.text, 14,
                    m.enabled ? UiKit.TEXT : UiKit.MUTED, false);
            text.addView(body);

            StringBuilder status = new StringBuilder(m.category);
            if (m.pinned) status.append(" · Pinned");
            if (!m.enabled) status.append(" · Off");
            TextView meta = UiKit.text(this, status.toString(), 11,
                    m.pinned ? UiKit.accent(this) : UiKit.MUTED, false);
            meta.setPadding(0, UiKit.dp(this,5), 0, 0);
            text.addView(meta);

            row.addView(text, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            Button onOff = smallButton(m.enabled ? "On" : "Off");
            onOff.setTextColor(m.enabled ? UiKit.SUCCESS : UiKit.MUTED);
            onOff.setContentDescription((m.enabled ? "Disable " : "Enable ") + m.text);
            onOff.setOnClickListener(v -> {
                MemoryStore.setEnabled(this, m.id, !m.enabled);
                refresh();
            });
            LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(
                    UiKit.dp(this,56), UiKit.dp(this,40));
            toggleLp.setMargins(UiKit.dp(this,6), 0, UiKit.dp(this,5), 0);
            row.addView(onOff, toggleLp);

            Button edit = smallButton("Edit");
            edit.setOnClickListener(v -> editMemory(m));
            row.addView(edit, new LinearLayout.LayoutParams(
                    UiKit.dp(this,68), UiKit.dp(this,40)));

            row.setOnLongClickListener(v -> {
                confirmDelete(m);
                return true;
            });

            list.addView(row, cardLp());
        }

        if (query.isEmpty()) {
            Button clear = secondaryButton("Clear all Orbit memories");
            clear.setOnClickListener(v -> confirmClearAll());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this,48));
            lp.setMargins(0, UiKit.dp(this,14), 0, 0);
            list.addView(clear, lp);
        }
    }

    private void editMemory(MemoryStore.Memory existing) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(UiKit.dp(this,20), UiKit.dp(this,4),
                UiKit.dp(this,20), UiKit.dp(this,14));
        form.setBackgroundColor(UiKit.SURFACE);

        EditText text = new EditText(this);
        text.setHint("What should Orbit remember?");
        text.setText(existing == null ? "" : existing.text);
        text.setTextColor(UiKit.TEXT);
        text.setHintTextColor(UiKit.MUTED);
        text.setSingleLine(false);
        text.setMaxLines(4);
        text.setBackgroundTintList(ColorStateList.valueOf(UiKit.accent(this)));
        form.addView(text, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView categoryLabel = UiKit.text(this, "Category", 12, UiKit.MUTED, true);
        categoryLabel.setPadding(0, UiKit.dp(this,12), 0, UiKit.dp(this,4));
        form.addView(categoryLabel);

        String[] categories = {
                MemoryStore.CATEGORY_PREFERENCE, MemoryStore.CATEGORY_PERSON,
                MemoryStore.CATEGORY_PLACE, MemoryStore.CATEGORY_DEVICE,
                MemoryStore.CATEGORY_OTHER
        };
        String chosen = existing == null ? MemoryStore.CATEGORY_OTHER : existing.category;
        final String[] selectedCategory = {chosen};
        LinearLayout category = memoryCategorySelector(categories, chosen,
                value -> selectedCategory[0] = value);
        form.addView(category, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this,52)));

        CheckBox enabled = orbitCheckBox("Use this memory in AI responses");
        enabled.setChecked(existing == null || existing.enabled);
        form.addView(enabled);

        CheckBox pinned = orbitCheckBox("Pin as important");
        pinned.setChecked(existing != null && existing.pinned);
        form.addView(pinned);

        TextView customTitle = UiKit.text(this,
                existing == null ? "Add memory" : "Edit memory",
                20, UiKit.TEXT, true);
        customTitle.setPadding(UiKit.dp(this,20), UiKit.dp(this,18),
                UiKit.dp(this,20), UiKit.dp(this,8));
        customTitle.setBackgroundColor(UiKit.SURFACE);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setCustomTitle(customTitle)
                .setView(form)
                .setNegativeButton("Cancel", null);

        if (existing != null) {
            b.setNeutralButton("Delete", (d,w) -> {
                MemoryStore.delete(this, existing.id);
                refresh();
            });
        }

        b.setPositiveButton("Save", null);
        AlertDialog dialog = b.create();
        UiKit.styleOrbitDialog(dialog, this, false, () -> {
            Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Button delete = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (delete != null) delete.setTextColor(Color.rgb(244, 110, 150));

            if (save != null) {
                save.setOnClickListener(v -> {
                    String value = text.getText().toString().trim();
                    if (value.isEmpty()) {
                        Toast.makeText(this, "Memory cannot be empty",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }

                    MemoryStore.Memory duplicate = MemoryStore.findDuplicate(
                            this, value, existing == null ? null : existing.id);
                    if (duplicate != null) {
                        Toast.makeText(this,
                                "A very similar memory is already saved",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (existing == null) {
                        MemoryStore.add(this, selectedCategory[0],
                                value, pinned.isChecked(), enabled.isChecked());
                    } else {
                        MemoryStore.update(this, existing.id,
                                selectedCategory[0], value,
                                pinned.isChecked(), enabled.isChecked());
                    }
                    dialog.dismiss();
                    refresh();
                });
            }
        });
        dialog.show();
    }

    private interface MemoryCategoryCallback {
        void selected(String value);
    }

    private LinearLayout memoryCategorySelector(String[] labels, String selected,
                                                MemoryCategoryCallback callback) {
        int selectedIndex = 0;
        for (int i = 0; i < labels.length; i++) {
            if (labels[i].equals(selected)) {
                selectedIndex = i;
                break;
            }
        }
        final int[] current = {selectedIndex};

        LinearLayout field = new LinearLayout(this);
        field.setOrientation(LinearLayout.HORIZONTAL);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPadding(UiKit.dp(this, 14), 0, UiKit.dp(this, 12), 0);
        field.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), 72), UiKit.accent(this), 15, this));

        TextView value = UiKit.text(this, labels[selectedIndex], 14, UiKit.TEXT, false);
        field.addView(value, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView arrow = UiKit.text(this, "▾", 17, UiKit.MUTED, true);
        arrow.setPadding(UiKit.dp(this, 10), 0, 0, 0);
        field.addView(arrow);

        field.setOnClickListener(v -> UiKit.showOrbitMenu(this, field, labels,
                current[0], (index, label) -> {
                    current[0] = index;
                    value.setText(label);
                    if (callback != null) callback.selected(label);
                }));
        UiKit.pressScale(field);
        return field;
    }

    private void confirmDelete(MemoryStore.Memory m) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Delete this memory?")
                .setMessage(m.text)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d,w) -> {
                    MemoryStore.delete(this, m.id);
                    refresh();
                }).create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
    }

    private void confirmClearAll() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Clear all memories?")
                .setMessage("This removes every local Orbit memory from this device.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (d,w) -> {
                    MemoryStore.clear(this);
                    refresh();
                }).create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
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

    private CheckBox orbitCheckBox(String text) {
        CheckBox cb = new CheckBox(this);
        cb.setText(text);
        cb.setTextColor(UiKit.TEXT);
        cb.setTextSize(14);
        cb.setButtonTintList(new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{}
                },
                new int[]{UiKit.accent(this), Color.rgb(90,94,105)}
        ));
        cb.setPadding(0, UiKit.dp(this,2), 0, UiKit.dp(this,2));
        return cb;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(UiKit.dp(this,16), UiKit.dp(this,14),
                UiKit.dp(this,16), UiKit.dp(this,14));
        c.setBackground(UiKit.outlined(UiKit.SURFACE,
                UiKit.withAlpha(UiKit.accent(this), 34), 20, this));
        return c;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0,0,0,UiKit.dp(this,10));
        return lp;
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTextColor(UiKit.TEXT);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this),90),
                UiKit.accent(this), 15, this));
        UiKit.pressScale(b);
        return b;
    }

    private Button secondaryButton(String text) {
        Button b = smallButton(text);
        b.setTextSize(14);
        return b;
    }

    private ImageButton iconButton(int res, String desc) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setImageTintList(ColorStateList.valueOf(UiKit.accent(this)));
        b.setBackground(UiKit.ripple(
                UiKit.SURFACE, UiKit.accent(this), 18, this));
        b.setContentDescription(desc);
        b.setPadding(UiKit.dp(this,11), UiKit.dp(this,11),
                UiKit.dp(this,11), UiKit.dp(this,11));
        UiKit.pressScale(b);
        return b;
    }
}
