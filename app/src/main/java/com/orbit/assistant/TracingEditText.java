package com.orbit.assistant;

import android.content.Context;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;

/**
 * An {@link EditText} that records the moments Android's input-method machinery interacts with it.
 *
 * <p>The question every previous attempt failed to answer is which of these is happening when the
 * Samsung keyboard is visibly alive but the text goes nowhere:
 *
 * <ul>
 *   <li>Android asked for an input connection once and it later stopped working;</li>
 *   <li>Android asked for a new one during the response and something rejected it;</li>
 *   <li>Android never asked again, so the keyboard is still talking to a dead one.</li>
 * </ul>
 *
 * <p>{@link #onCreateInputConnection} is where Android asks, so recording that call distinguishes
 * all three. Behaviour is deliberately unchanged: the superclass builds the connection and this
 * class returns exactly what it produced.
 */
public class TracingEditText extends EditText {
    private int connectionsCreated;

    public TracingEditText(Context context) {
        super(context);
    }

    /** How many times Android has asked this editor for an input connection. */
    public int connectionsCreated() {
        return connectionsCreated;
    }

    @Override public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        InputConnection connection = super.onCreateInputConnection(outAttrs);
        connectionsCreated++;
        ComposerTrace.event("editor.onCreateInputConnection #" + connectionsCreated
                + " returned=" + (connection != null)
                + " focus=" + hasFocus()
                + " windowFocus=" + hasWindowFocus());
        return connection;
    }

    @Override protected void onFocusChanged(boolean focused, int direction,
                                            android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect);
        ComposerTrace.event("editor.onFocusChanged focus=" + focused);
    }

    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        // If the window stops being focused while the editor still reports view focus, the
        // keyboard can keep drawing against a target that no longer accepts input.
        ComposerTrace.event("editor.onWindowFocusChanged windowFocus=" + hasWindowFocus
                + " focus=" + hasFocus());
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ComposerTrace.event("editor.onAttachedToWindow");
    }

    @Override protected void onDetachedFromWindow() {
        ComposerTrace.event("editor.onDetachedFromWindow");
        super.onDetachedFromWindow();
    }
}
