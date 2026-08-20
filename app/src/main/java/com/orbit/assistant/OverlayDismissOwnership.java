package com.orbit.assistant;

/**
 * Lifecycle ownership rules for closing the Side-button overlay.
 *
 * <p>Android reuses one {@code VoiceInteractionSession} across every Side-button invocation, so
 * dismissal work armed by one invocation outlives it. Kept out of {@link OrbitSession} only so the
 * rules can be tested without a live session, a real window, or a running animator. This class
 * decides nothing about how the overlay closes — when it says yes, {@code OrbitSession} runs
 * exactly the dismissal it always ran.
 */
public final class OverlayDismissOwnership {
    private OverlayDismissOwnership() {}

    /** What a {@code onCloseSystemDialogs()} callback should do. */
    public enum CloseSystemDialogs {
        /** Orbit is genuinely on screen and Android is legitimately asking it to close. */
        DISMISS,
        /**
         * The callback belongs to an invocation that is already hidden. Arming a dismissal here
         * would start an exit animation whose end action calls {@code hide()} on the shared
         * session, which is how a later invocation used to be closed by an older one.
         */
        IGNORE_NOT_VISIBLE,
        /**
         * Some Samsung builds deliver the tail of the Side-button invocation as
         * close-system-dialogs just after the fresh sheet becomes visible.
         */
        IGNORE_STABILIZING
    }

    /**
     * Whether a close-system-dialogs callback may dismiss the overlay.
     *
     * <p>A hidden session never dismisses. That is the invariant the reproduced failure needed:
     * the Side-button press itself delivers close-system-dialogs to the still-hidden session
     * moments before the new invocation is prepared, and the exit animation it used to arm
     * completed a few hundred milliseconds later, hiding the brand-new overlay.
     *
     * @param sessionVisible          the session is currently showing
     * @param freshExternalShowAtMs   {@code elapsedRealtime} of the last fresh external show,
     *                                or {@code 0} when this show was an internal resume
     * @param nowMs                   current {@code elapsedRealtime}
     * @param stabilizationMs         length of the existing fresh-show suppression window
     */
    public static CloseSystemDialogs onCloseSystemDialogs(boolean sessionVisible,
                                                          long freshExternalShowAtMs,
                                                          long nowMs,
                                                          long stabilizationMs) {
        if (!sessionVisible) return CloseSystemDialogs.IGNORE_NOT_VISIBLE;
        long sinceShow = nowMs - freshExternalShowAtMs;
        if (freshExternalShowAtMs > 0L && sinceShow >= 0L && sinceShow < stabilizationMs) {
            return CloseSystemDialogs.IGNORE_STABILIZING;
        }
        return CloseSystemDialogs.DISMISS;
    }

    /**
     * Whether dismissal work armed earlier still owns the session.
     *
     * <p>An exit animation's end action calls {@code hide()}. Orbit invalidates pending dismissal
     * work whenever the sheet is rebuilt or returned to its hidden starting state, which is
     * exactly when a new invocation takes ownership. An end action that was armed before that
     * point belongs to an invocation that is over and must not hide whatever is on screen now.
     *
     * @param armedToken   the ownership token captured when the dismissal was armed
     * @param currentToken the session's current ownership token
     */
    public static boolean dismissalStillOwnsSession(int armedToken, int currentToken) {
        return armedToken == currentToken;
    }
}
