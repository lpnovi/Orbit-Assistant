package com.orbit.assistant;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
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

import java.util.ArrayList;
import java.util.List;

/** Create/edit surface for a Custom Command; all draft fields survive Activity recreation. */
public final class CustomCommandEditorActivity extends Activity {
    public static final String EXTRA_COMMAND_ID = "custom_command_id";
    private static final String STATE_PRIMARY = "primary";
    private static final String STATE_ALIASES = "aliases";
    private static final String STATE_ROUTINE = "routine";
    private static final String STATE_ENABLED = "enabled";

    private CustomCommandStore.Command existing;
    private EditText primaryField;
    private EditText aliasesField;
    private Button routineButton;
    private CheckBox enabledField;
    private TextView errorText;
    private String selectedRoutineId = "";

    /** Interactive Back for this page. Its classification lives in OrbitNavigation. */
    private OrbitPredictiveBack navigation;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.syncTheme(this);
        Window window = getWindow();
        window.setStatusBarColor(UiKit.BG);
        window.setNavigationBarColor(UiKit.BG);

        existing = CustomCommandStore.findById(this, getIntent().getStringExtra(EXTRA_COMMAND_ID));
        if (savedInstanceState != null) {
            selectedRoutineId = savedInstanceState.getString(STATE_ROUTINE, "");
        } else if (existing != null) {
            selectedRoutineId = existing.routineId;
        }

        View content = buildContent(savedInstanceState);
        setContentView(content);
        UiKit.applyActivityInsets(this, content, true);
        // Nothing is stored until Save, so Back has always discarded a half-written command without
        // asking, and still does. The page only slides away while there is nothing to discard.
        navigation = OrbitPredictiveBack.install(this, new OrbitPredictiveBack.Screen() {
            @Override public boolean canNavigate() { return !hasUnsavedWork(); }
            @Override public void navigateBack() { finish(); }
            @Override public String screenName() {
                return OrbitNavigation.labelFor(CustomCommandEditorActivity.class);
            }
        });
    }

    /** True while this screen differs from what is stored, so leaving would lose something. */
    private boolean hasUnsavedWork() {
        String primary = primaryField == null ? "" : primaryField.getText().toString().trim();
        String aliases = aliasesField == null ? "" : aliasesField.getText().toString().trim();
        boolean enabled = enabledField == null || enabledField.isChecked();
        if (existing == null) {
            return !primary.isEmpty() || !aliases.isEmpty() || !selectedRoutineId.isEmpty();
        }
        return !primary.equals(existing.primaryPhrase.trim())
                || !aliases.equals(joinAliases(existing).trim())
                || !selectedRoutineId.equals(existing.routineId)
                || enabled != existing.enabled;
    }

    @Override protected void onResume() {
        super.onResume();
        UiPresence.enter(this);
        refreshRoutineButton();
    }

    @Override protected void onPause() {
        UiPresence.leave(this);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putString(STATE_PRIMARY, primaryField.getText().toString());
        outState.putString(STATE_ALIASES, aliasesField.getText().toString());
        outState.putString(STATE_ROUTINE, selectedRoutineId);
        outState.putBoolean(STATE_ENABLED, enabledField.isChecked());
        super.onSaveInstanceState(outState);
    }

    private View buildContent(Bundle state) {
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
        back.setOnClickListener(v -> navigation.performBack());
        header.addView(back, new LinearLayout.LayoutParams(UiKit.dp(this, 44), UiKit.dp(this, 44)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(UiKit.dp(this, 13), 0, 0, 0);
        titles.addView(UiKit.text(this, existing == null ? "New Custom Command" : "Edit Custom Command",
                23, UiKit.TEXT, true));
        titles.addView(UiKit.text(this, "Run one saved Routine", 13, UiKit.MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(header);

        TextView explanation = UiKit.text(this,
                "Orbit matches these phrases exactly after ignoring capitalization, extra spaces, ending punctuation, and optional “Orbit” or “please” wrappers.",
                13, UiKit.MUTED, false);
        explanation.setLineSpacing(0, 1.12f);
        LinearLayout.LayoutParams explanationLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        explanationLp.setMargins(UiKit.dp(this, 2), UiKit.dp(this, 18), UiKit.dp(this, 2), UiKit.dp(this, 18));
        page.addView(explanation, explanationLp);

        page.addView(label("PRIMARY PHRASE"));
        String primary = state != null ? state.getString(STATE_PRIMARY, "") :
                existing == null ? "" : existing.primaryPhrase;
        primaryField = inputField("Bedtime", primary, false);
        page.addView(primaryField, fieldLp());

        page.addView(label("ADDITIONAL PHRASES (OPTIONAL)"));
        String aliases = state != null ? state.getString(STATE_ALIASES, "") : joinAliases(existing);
        aliasesField = inputField("Good night\nWind down", aliases, true);
        aliasesField.setMinLines(3);
        aliasesField.setGravity(Gravity.TOP | Gravity.START);
        page.addView(aliasesField, fieldLp());
        TextView aliasHint = UiKit.text(this, "One phrase per line · up to 5", 11, UiKit.MUTED, false);
        aliasHint.setPadding(UiKit.dp(this, 4), 0, 0, UiKit.dp(this, 14));
        page.addView(aliasHint);

        page.addView(label("RUN ROUTINE"));
        routineButton = secondaryButton("");
        routineButton.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        routineButton.setPadding(UiKit.dp(this, 16), 0, UiKit.dp(this, 16), 0);
        routineButton.setOnClickListener(v -> chooseRoutine());
        page.addView(routineButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48)));

        enabledField = new CheckBox(this);
        enabledField.setText("Enabled");
        enabledField.setTextColor(UiKit.TEXT);
        enabledField.setTextSize(14);
        enabledField.setButtonTintList(UiKit.accentControlTint(this));
        enabledField.setChecked(state != null ? state.getBoolean(STATE_ENABLED, true) :
                existing == null || existing.enabled);
        LinearLayout.LayoutParams enabledLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        enabledLp.setMargins(0, UiKit.dp(this, 14), 0, 0);
        page.addView(enabledField, enabledLp);

        errorText = UiKit.text(this, "", 12, Color.rgb(239, 105, 105), false);
        errorText.setVisibility(View.GONE);
        errorText.setPadding(UiKit.dp(this, 3), UiKit.dp(this, 8), UiKit.dp(this, 3), 0);
        page.addView(errorText);

        Button save = primaryButton(existing == null ? "Save command" : "Save changes");
        save.setOnClickListener(v -> save());
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 50));
        saveLp.setMargins(0, UiKit.dp(this, 22), 0, 0);
        page.addView(save, saveLp);

        refreshRoutineButton();
        UiKit.applyTypography(page);
        return scroll;
    }

    private void chooseRoutine() {
        List<RoutineStore.Routine> routines = RoutineStore.list(this);
        if (routines.isEmpty()) {
            Toast.makeText(this, "Create a Routine first.", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[routines.size()];
        int selected = -1;
        for (int i = 0; i < routines.size(); i++) {
            labels[i] = routines.get(i).name;
            if (routines.get(i).id.equals(selectedRoutineId)) selected = i;
        }
        UiKit.showOrbitMenu(this, routineButton, labels, selected, (index, label) -> {
            selectedRoutineId = routines.get(index).id;
            refreshRoutineButton();
            hideError();
        });
    }

    private void refreshRoutineButton() {
        if (routineButton == null) return;
        RoutineStore.Routine routine = RoutineStore.findById(this, selectedRoutineId);
        if (routine != null) {
            routineButton.setText(routine.name);
            routineButton.setTextColor(UiKit.TEXT);
        } else if (!selectedRoutineId.isEmpty()) {
            routineButton.setText("Routine unavailable — choose another");
            routineButton.setTextColor(Color.rgb(239, 105, 105));
        } else {
            routineButton.setText(RoutineStore.list(this).isEmpty() ?
                    "No saved Routines available" : "Choose a Routine");
            routineButton.setTextColor(UiKit.MUTED);
        }
    }

    private void save() {
        List<String> aliases = new ArrayList<>();
        String rawAliases = aliasesField.getText().toString();
        for (String line : rawAliases.split("\\r?\\n", -1)) {
            String value = line.trim().replaceAll("\\s+", " ");
            if (!value.isEmpty()) aliases.add(value);
        }
        long now = System.currentTimeMillis();
        CustomCommandStore.Command command = existing == null
                ? CustomCommandStore.create(enabledField.isChecked(), selectedRoutineId,
                primaryField.getText().toString(), aliases)
                : new CustomCommandStore.Command(existing.id, enabledField.isChecked(), selectedRoutineId,
                primaryField.getText().toString(), aliases, existing.createdAt, now);

        CustomCommandStore.Validation validation = CustomCommandStore.validate(this, command,
                existing == null ? null : existing.id);
        if (!validation.valid) {
            showError(validation.message);
            return;
        }
        if (RoutineStore.findById(this, selectedRoutineId) == null) {
            showError("Choose an available Routine before saving.");
            return;
        }
        if (!CustomCommandStore.upsert(this, command)) {
            showError("Orbit could not save this Custom Command.");
            return;
        }
        Toast.makeText(this, existing == null ? "Custom Command created." : "Custom Command updated.",
                Toast.LENGTH_SHORT).show();
        finish();
    }

    private void showError(String message) {
        errorText.setText(message);
        errorText.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        errorText.setText("");
        errorText.setVisibility(View.GONE);
    }

    private String joinAliases(CustomCommandStore.Command command) {
        if (command == null || command.aliases.isEmpty()) return "";
        return String.join("\n", command.aliases);
    }

    private TextView label(String text) {
        TextView label = UiKit.text(this, text, 12, UiKit.MUTED, true);
        label.setLetterSpacing(0.12f);
        label.setPadding(UiKit.dp(this, 4), UiKit.dp(this, 7), 0, UiKit.dp(this, 8));
        return label;
    }

    private EditText inputField(String hint, String value, boolean multiline) {
        EditText field = new EditText(this);
        field.setText(value);
        field.setHint(hint);
        field.setTextColor(UiKit.TEXT);
        field.setHintTextColor(UiKit.MUTED);
        field.setTextSize(14);
        field.setSingleLine(!multiline);
        field.setInputType(InputType.TYPE_CLASS_TEXT |
                (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        : InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));
        field.setPadding(UiKit.dp(this, 15), UiKit.dp(this, 11), UiKit.dp(this, 15), UiKit.dp(this, 11));
        field.setBackground(UiKit.outlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), 90), 15, this));
        return field;
    }

    private LinearLayout.LayoutParams fieldLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, UiKit.dp(this, 12));
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
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                Color.rgb(53, 58, 72), UiKit.accent(this), 15, this));
        button.setMinHeight(0); button.setMinimumHeight(0); button.setStateListAnimator(null);
        UiKit.pressScale(button);
        return button;
    }
}
