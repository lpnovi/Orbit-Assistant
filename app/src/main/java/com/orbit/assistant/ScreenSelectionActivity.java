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

    /** The Vault control's label, in the words every other Save to Vault in Orbit uses. */
    static final String SAVE_TO_VAULT = "Save to Vault";

    private String sourcePath = "";
    private String callbackToken = "";
    private ScreenSelectionView editor;
    private TextView instruction;
    private Button cropButton;
    private Button markupButton;
    private Button undoButton;
    private Button vaultButton;
    private LinearLayout undoRow;
    private boolean finished;
    private boolean bridgeDeliveryPending;
    private boolean bridgeFailurePending;
    private Bitmap bridgeResult;
    private boolean bridgePrecise;

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
            if (callbackToken.isEmpty()) cancelAndFinish();
            else failAndFinish();
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
        root.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 2),
                UiKit.dp(this, 12), UiKit.dp(this, 2));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button cancel = quietButton("Cancel", "Cancel screen selection");
        cancel.setOnClickListener(v -> cancelAndFinish());
        top.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 40)));
        TextView title = UiKit.text(this, "Screen selection", 17, UiKit.TEXT, true);
        title.setGravity(Gravity.CENTER);
        top.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button reset = quietButton("Reset", "Reset crop and markup");
        reset.setOnClickListener(v -> {
            editor.reset();
            selectTool(ScreenSelectionView.Tool.CROP, false);
        });
        top.addView(reset, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 40)));
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
        editorLp.setMargins(0, UiKit.dp(this, 1), 0, UiKit.dp(this, 1));
        root.addView(editor, editorLp);

        instruction = UiKit.text(this, "Drag to select an area", 12, UiKit.MUTED, false);
        instruction.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams instructionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        instructionLp.setMargins(0, 0, 0, 0);
        root.addView(instruction, instructionLp);

        undoRow = new LinearLayout(this);
        undoRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        undoButton = secondaryButton("Undo", "Undo last markup stroke");
        undoButton.setOnClickListener(v -> editor.undoMarkup());
        undoRow.addView(undoButton, new LinearLayout.LayoutParams(
                UiKit.dp(this, 84), UiKit.dp(this, 34)));
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
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 46)));

        // Save to Vault sits on its own row above the two "use this now" actions, not beside them.
        // It is a different kind of answer: Use selection and Use full screen both finish this
        // editor and hand the picture to the message being written, while this one keeps the crop
        // and leaves the user exactly where they were, free to carry on and still attach it.
        //
        // Absent entirely while the Vault is switched off. Screen Selection's ordinary path is
        // untouched by that preference.
        vaultButton = secondaryButton(SAVE_TO_VAULT, "Save this selection to your Vault");
        vaultButton.setOnClickListener(v -> saveSelectionToVault());
        vaultButton.setVisibility(Prefs.vaultEnabled(this) ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams vaultLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 42));
        vaultLp.setMargins(0, UiKit.dp(this, 4), 0, 0);
        root.addView(vaultButton, vaultLp);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.setMargins(0, UiKit.dp(this, 3), 0, 0);
        Button full = secondaryButton("Use full screen", "Use full screen");
        full.setOnClickListener(v -> complete(true));
        actions.addView(full, new LinearLayout.LayoutParams(0, UiKit.dp(this, 46), 1));
        Button use = primaryButton("Use selection", "Use screen selection");
        use.setOnClickListener(v -> complete(false));
        LinearLayout.LayoutParams useLp = new LinearLayout.LayoutParams(0,
                UiKit.dp(this, 46), 1);
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
        undoRow.setVisibility(canUndo ? View.VISIBLE : View.GONE);
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

    /**
     * Keeps the current selection in Orbit Vault, and stays here.
     *
     * <p>Speed is the whole point. Somebody who has just cropped the thing they wanted has already
     * done the work; asking them to name it and write a note before it is saved would make keeping
     * a screenshot slower than asking about one. So it saves immediately, says so, and leaves the
     * editor open - the note, the title and the rest are all still there on the item afterwards,
     * and the crop the user made is still available to attach to a message.
     *
     * <p>Nothing is saved automatically. There is exactly one route into this method and it is a
     * tap on a control the user can see.
     */
    private void saveSelectionToVault() {
        if (editor == null || finished) return;
        if (!Prefs.vaultEnabled(this)) {
            Toast.makeText(this, "Orbit Vault is turned off", Toast.LENGTH_SHORT).show();
            return;
        }
        Bitmap result;
        try { result = editor.renderResult(false); }
        catch (Exception e) { result = null; }
        if (result == null) {
            Toast.makeText(this, "Orbit could not render this selection", Toast.LENGTH_LONG).show();
            return;
        }
        OrbitVaultItem saved = OrbitVaultStore.saveImage(this, result, "",
                OrbitVaultSource.SCREEN_SELECTION);
        if (!result.isRecycled()) result.recycle();
        if (saved == null) {
            Toast.makeText(this, "Orbit could not save this selection", Toast.LENGTH_LONG).show();
            return;
        }
        performTick();
        Toast.makeText(this, "Saved to Vault", Toast.LENGTH_SHORT).show();
    }

    private void complete(boolean useFullScreen) {
        if (finished || editor == null) return;
        Bitmap result;
        try { result = editor.renderResult(useFullScreen); }
        catch (Exception e) { result = null; }
        if (result == null) {
            Toast.makeText(this, "Orbit could not render this selection", Toast.LENGTH_LONG).show();
            if (!callbackToken.isEmpty()) failAndFinish();
            return;
        }
        final Bitmap renderedResult = result;
        boolean precise = !useFullScreen && (editor.hasCrop() || editor.hasMarkup());
        finished = true;
        if (!callbackToken.isEmpty()) {
            bridgeResult = result;
            bridgePrecise = precise;
            bridgeDeliveryPending = true;
            performTick();
            ScreenSelectionStore.delete(this, sourcePath);
            finishAndRemoveTask();
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
            bridgeDeliveryPending = true;
            bridgeResult = null;
            bridgePrecise = false;
        }
        ScreenSelectionStore.delete(this, sourcePath);
        setResult(RESULT_CANCELED);
        if (callbackToken.isEmpty()) finish();
        else finishAndRemoveTask();
    }

    private void failAndFinish() {
        if (finished) return;
        finished = true;
        bridgeDeliveryPending = true;
        bridgeFailurePending = true;
        bridgeResult = null;
        bridgePrecise = false;
        ScreenSelectionStore.delete(this, sourcePath);
        setResult(RESULT_CANCELED);
        finishAndRemoveTask();
    }

    @Override public void onBackPressed() { cancelAndFinish(); }

    @Override protected void onSaveInstanceState(Bundle outState) {
        if (editor != null) editor.saveEditorState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onDestroy() {
        if (isFinishing() && !finished) cancelAndFinish();
        deliverPendingBridge();
        super.onDestroy();
    }

    @Override protected void onStop() {
        super.onStop();
        if (isFinishing()) deliverPendingBridge();
    }

    private void deliverPendingBridge() {
        if (!bridgeDeliveryPending) return;
        bridgeDeliveryPending = false;
        if (bridgeFailurePending) ScreenSelectionBridge.fail(callbackToken);
        else ScreenSelectionBridge.deliver(callbackToken, bridgeResult, bridgePrecise);
        bridgeFailurePending = false;
        bridgeResult = null;
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
