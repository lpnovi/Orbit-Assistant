package com.orbit.assistant;

import android.content.Context;
import android.widget.ImageButton;

/**
 * The single rule deciding whether the composer's one control offers Send or Stop, shared by the
 * Side-button overlay and full chat so the two surfaces cannot drift apart.
 *
 * <p>Applying the decision only swaps the icon and the accessibility description. Size, position,
 * tint, and background are deliberately left alone: Stop takes over the existing button's
 * footprint rather than adding a control or moving the composer.
 */
public final class ComposerActionState {
    public static final String SEND_DESCRIPTION = "Send message";
    public static final String STOP_DESCRIPTION = "Stop response";

    private ComposerActionState() {}

    /**
     * True when the control should offer Stop.
     *
     * @param uiBusy the surface's own view of an in-flight turn, for the overlay, which knows it is
     *               waiting before the request has been written down. Full chat passes false and
     *               relies on the durable record alone.
     */
    public static boolean shouldShowStop(Context c, String conversationId, boolean uiBusy) {
        if (c == null || !Prefs.showStopButton(c)) return false;
        return uiBusy || PendingRequestStore.hasActiveForConversation(c, conversationId);
    }

    public static int iconFor(boolean stop) {
        return stop ? R.drawable.ic_stop : R.drawable.ic_send;
    }

    public static String descriptionFor(boolean stop) {
        return stop ? STOP_DESCRIPTION : SEND_DESCRIPTION;
    }

    /** Puts the decision on the control without touching anything that would move the composer. */
    public static void apply(ImageButton control, boolean stop) {
        if (control == null) return;
        control.setImageResource(iconFor(stop));
        control.setContentDescription(descriptionFor(stop));
    }
}
