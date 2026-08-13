package com.orbit.assistant;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Shared native Crop + Mark up editor for overlay and full-chat screen attachments. */
public final class ScreenSelectionActivity extends Activity {
    private String sourcePath = "";
    private String callbackToken = "";
    private ScreenSelectionView editor;
    private TextView instruction;
    private Button cropButton;
    private Button markupButton;
    private Button undoButton;
    private boolean finished;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.syncTheme(this);
        Intent intent = getIntent();
        sourcePath = intent == null ? "" : intent.getStringExtra(
                ScreenSelectionStore.EXTRA_SOURCE_PATH);
        callbackToken = intent == null ? "" : intent.getStringExtra(
                ScreenSelectionStore.EXTRA_CALLBACK_TOKEN);
        if (sourcePath == null) sourcePath = "";
        if (callbackToken == null) callbackToken = "";
        Bitmap original = ScreenSelectionStore.load(this, sourcePath);
        if (original == null) {
            Toast.makeText(this, "Orbit could not open this screen image", Toast.LENGTH_LONG).show();
            cancelAndFinish();
            return;
        }

        Window window = getWindow();
        window.setStatusBarColor(UiKit.BG);
        window.setNavigationBarColor(UiKit.BG);
        View content = buildContent(original, savedInstanceState);
        setContentView(content);
        UiKit.applyActivityInsets(this, content, false);
        UiKit.applyTypography(content);
    }

    private View buildContent(Bitmap original, Bundle savedState) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.BG);
        root.setForceDarkAllowed(false);
        root.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 10),
                UiKit.dp(this, 14), UiKit.dp(this, 12));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button cancel = quietButton("Cancel", "Cancel screen selection");
        cancel.setOnClickListener(v -> cancelAndFinish());
        top.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 44)));
        TextView title = UiKit.text(this, "Screen selection", 18, UiKit.TEXT, true);
        title.setGravity(Gravity.CENTER);
        top.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button reset = quietButton("Reset", "Reset crop and markup");
        reset.setOnClickListener(v -> {
            editor.reset();
            selectTool(ScreenSelectionView.Tool.CROP, false);
        });
        top.addView(reset, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 44)));
        root.addView(top);

        editor = new ScreenSelectionView(this);
        editor.setOriginal(original);
        editor.setListener(new ScreenSelectionView.Listener() {
            @Override public void onStateChanged() { refreshControls(); }
            @Override public void onCropEstablished() { performTick(); }
        });
        // Fit-center letterboxing is intentional for exact bitmap mapping. Blend
        // that unused canvas into the page rather than showing raised gray bars.
        editor.setBackground(UiKit.rounded(UiKit.BG, 18, this));
        LinearLayout.LayoutParams editorLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        editorLp.setMargins(0, UiKit.dp(this, 8), 0, UiKit.dp(this, 8));
        root.addView(editor, editorLp);

        instruction = UiKit.text(this, "Drag to select an area", 12, UiKit.MUTED, false);
        instruction.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams instructionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        instructionLp.setMargins(0, 0, 0, UiKit.dp(this, 7));
        root.addView(instruction, instructionLp);

        LinearLayout undoRow = new LinearLayout(this);
        undoRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        undoButton = secondaryButton("Undo", "Undo last markup stroke");
        undoButton.setOnClickListener(v -> editor.undoMarkup());
        undoRow.addView(undoButton, new LinearLayout.LayoutParams(
                UiKit.dp(this, 88), UiKit.dp(this, 38)));
        root.addView(undoRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout tools = new LinearLayout(this);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setPadding(UiKit.dp(this, 3), UiKit.dp(this, 3),
                UiKit.dp(this, 3), UiKit.dp(this, 3));
        tools.setBackground(UiKit.outlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), 70), 17, this));
        cropButton = toolButton("Crop", "Crop tool");
        cropButton.setOnClickListener(v -> selectTool(ScreenSelectionView.Tool.CROP, true));
        tools.addView(cropButton, new LinearLayout.LayoutParams(0, UiKit.dp(this, 44), 1));
        markupButton = toolButton("Mark up", "Mark up tool");
        markupButton.setOnClickListener(v -> selectTool(ScreenSelectionView.Tool.MARKUP, true));
        LinearLayout.LayoutParams markupLp = new LinearLayout.LayoutParams(0,
                UiKit.dp(this, 44), 1);
        markupLp.setMargins(UiKit.dp(this, 3), 0, 0, 0);
        tools.addView(markupButton, markupLp);
        root.addView(tools, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 50)));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.setMargins(0, UiKit.dp(this, 10), 0, 0);
        Button full = secondaryButton("Use full screen", "Use full screen");
        full.setOnClickListener(v -> complete(true));
        actions.addView(full, new LinearLayout.LayoutParams(0, UiKit.dp(this, 48), 1));
        Button use = primaryButton("Use selection", "Use screen selection");
        use.setOnClickListener(v -> complete(false));
        LinearLayout.LayoutParams useLp = new LinearLayout.LayoutParams(0,
                UiKit.dp(this, 48), 1);
        useLp.setMargins(UiKit.dp(this, 8), 0, 0, 0);
        actions.addView(use, useLp);
        root.addView(actions, actionsLp);

        if (savedState != null) editor.restoreEditorState(savedState);
        selectTool(editor.getTool(), false);
        return root;
    }

    private void selectTool(ScreenSelectionView.Tool tool, boolean haptic) {
        if (editor == null) return;
        boolean changed = editor.getTool() != tool;
        editor.setTool(tool);
        if (haptic && changed) performTick();
        refreshControls();
    }

    private void refreshControls() {
        if (editor == null || cropButton == null) return;
        boolean crop = editor.getTool() == ScreenSelectionView.Tool.CROP;
        styleTool(cropButton, crop, "Crop tool");
        styleTool(markupButton, !crop, "Mark up tool");
        instruction.setText(crop
                ? (editor.hasCrop() ? "Drag to move or use the handles to resize"
                : "Drag to select an area")
                : "Mark what you want Orbit to notice");
        boolean canUndo = !crop && editor.canUndo();
        undoButton.setVisibility(canUndo ? View.VISIBLE : View.GONE);
        undoButton.setEnabled(canUndo);
    }

    private void styleTool(Button button, boolean selected, String description) {
        button.setSelected(selected);
        button.setContentDescription(description + (selected ? ", selected" : ", not selected"));
        button.setTextColor(selected ? UiKit.onAccent(this) : UiKit.TEXT);
        button.setBackground(selected
                ? UiKit.ripple(UiKit.accent(this), UiKit.onAccent(this), 14, this)
                : UiKit.ripple(Color.TRANSPARENT, UiKit.accent(this), 14, this));
    }

    private void complete(boolean useFullScreen) {
        if (finished || editor == null) return;
        Bitmap result;
        try { result = editor.renderResult(useFullScreen); }
        catch (Exception e) { result = null; }
        if (result == null) {
            Toast.makeText(this, "Orbit could not render this selection", Toast.LENGTH_LONG).show();
            return;
        }
        final Bitmap renderedResult = result;
        boolean precise = !useFullScreen && (editor.hasCrop() || editor.hasMarkup());
        finished = true;
        if (!callbackToken.isEmpty()) {
            ScreenSelectionBridge.deliver(callbackToken, result, precise);
            performTick();
            ScreenSelectionStore.delete(this, sourcePath);
            finish();
            return;
        }
        instruction.setText("Preparing selection...");
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        new Thread(() -> {
            String resultPath = ScreenSelectionStore.saveResult(this, renderedResult);
            runOnUiThread(() -> {
                if (resultPath.isEmpty()) {
                    finished = false;
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                    refreshControls();
                    Toast.makeText(this, "Orbit could not save this selection",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                Intent data = new Intent()
                        .putExtra(ScreenSelectionStore.EXTRA_RESULT_PATH, resultPath)
                        .putExtra(ScreenSelectionStore.EXTRA_PRECISE, precise)
                        .putExtra(ScreenSelectionStore.EXTRA_APP_PACKAGE,
                                getIntent().getStringExtra(ScreenSelectionStore.EXTRA_APP_PACKAGE))
                        .putExtra(ScreenSelectionStore.EXTRA_APP_LABEL,
                                getIntent().getStringExtra(ScreenSelectionStore.EXTRA_APP_LABEL))
                        .putExtra(ScreenSelectionStore.EXTRA_AGE_LABEL,
                                getIntent().getStringExtra(ScreenSelectionStore.EXTRA_AGE_LABEL));
                setResult(RESULT_OK, data);
                performTick();
                ScreenSelectionStore.delete(this, sourcePath);
                finish();
            });
        }, "orbit-screen-selection-result").start();
    }

    private void cancelAndFinish() {
        if (finished) return;
        finished = true;
        if (!callbackToken.isEmpty()) {
            ScreenSelectionBridge.deliver(callbackToken, null, false);
        }
        ScreenSelectionStore.delete(this, sourcePath);
        setResult(RESULT_CANCELED);
        finish();
    }

    @Override public void onBackPressed() { cancelAndFinish(); }

    @Override protected void onSaveInstanceState(Bundle outState) {
        if (editor != null) editor.saveEditorState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onDestroy() {
        if (isFinishing() && !finished) cancelAndFinish();
        super.onDestroy();
    }

    private void performTick() {
        if (!Prefs.haptics(this)) return;
        try { getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); }
        catch (Exception ignored) { }
    }

    private Button quietButton(String text, String description) {
        Button button = secondaryButton(text, description);
        button.setTextColor(UiKit.accent(this));
        button.setBackground(UiKit.ripple(Color.TRANSPARENT, UiKit.accent(this), 13, this));
        return button;
    }

    private Button toolButton(String text, String description) {
        return secondaryButton(text, description);
    }

    private Button secondaryButton(String text, String description) {
        Button button = new Button(this);
        button.setText(text);
        button.setContentDescription(description);
        button.setTextColor(UiKit.TEXT);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setStateListAnimator(null);
        button.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), 85), UiKit.accent(this), 14, this));
        UiKit.pressScale(button);
        return button;
    }

    private Button primaryButton(String text, String description) {
        Button button = new Button(this);
        button.setText(text);
        button.setContentDescription(description);
        button.setTextColor(UiKit.onAccent(this));
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setStateListAnimator(null);
        button.setBackground(UiKit.ripple(UiKit.accent(this), UiKit.onAccent(this), 14, this));
        button.setBackgroundTintList((ColorStateList) null);
        UiKit.pressScale(button);
        return button;
    }
}
