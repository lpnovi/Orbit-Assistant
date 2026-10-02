package com.orbit.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.widget.EditText;
import android.widget.FrameLayout;

/**
 * Rename chat, shared by Chats and the conversation itself (0.8.3.0-beta.3+).
 *
 * <p>The field used to be handed to the dialog bare, which let its underline run almost to both
 * edges of the dialog while the title sat inset. It now sits in a container with the dialog's own
 * content inset on both sides, so the field, its accent underline and the title line up. Padding
 * inside the field would only have moved the text and left the underline where it was.
 */
final class OrbitRenameDialog {
    /** Matches the inset of an Orbit dialog's title and message. */
    static final int CONTENT_INSET_DP = 22;

    interface OnSave {
        void onSave(String title);
    }

    private OrbitRenameDialog() {}

    static AlertDialog show(Activity a, String currentTitle, OnSave onSave) {
        EditText input = new EditText(a);
        input.setText(currentTitle == null ? "" : currentTitle);
        input.setSelectAllOnFocus(true);
        input.setSingleLine(true);
        input.setTextColor(UiKit.TEXT);
        input.setHintTextColor(UiKit.MUTED);
        input.setHint("Chat name");
        input.setBackgroundTintList(ColorStateList.valueOf(UiKit.accent(a)));
        input.setContentDescription("Chat name");

        FrameLayout inset = new FrameLayout(a);
        int side = UiKit.dp(a, CONTENT_INSET_DP);
        inset.setPadding(side, UiKit.dp(a, 6), side, UiKit.dp(a, 2));
        inset.addView(input, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle("Rename chat").setView(inset)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> onSave.onSave(input.getText().toString()))
                .create();
        UiKit.styleOrbitDialog(dialog, a, false);
        dialog.show();
        return dialog;
    }
}
