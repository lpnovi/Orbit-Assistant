package com.orbit.assistant;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;

/**
 * The conversation's back gesture, drawn by Orbit from the finger's own progress.
 *
 * <p><b>Why this exists at all.</b> v0.7.7.9 Beta 1 tried the opposite and better-looking thing:
 * register nothing, declare no close animation, and let Android's own cross-activity predictive
 * back track the finger and reveal Chats. Every check that could be made off-device passed, the
 * manifest opted in, the API was present, no callback was armed — and on the acceptance device, a
 * Galaxy S25 Ultra on One UI, the conversation did not move at all. Orbit has no way to observe
 * whether the system rendered those frames, so it also had no way to know it had shipped nothing.
 * That is the real lesson of Beta 1: a transition Orbit only *requests* is a transition Orbit
 * cannot promise. This one it draws, which means it can also count the progress events that drew
 * it and say so in Diagnostics.
 *
 * <p><b>What it does.</b> On API 34+ it registers an {@code OnBackAnimationCallback} and moves the
 * conversation horizontally as a direct function of {@code BackEvent.progress}: no timers, no
 * fling, nothing that waits for the finger to lift. Reversing the gesture reverses the picture
 * because the picture is only ever a function of the number the platform last reported.
 *
 * <p><b>What is revealed.</b> The real Chats screen, not a picture of it. {@link
 * Activity#setTranslucent(boolean)} converts the conversation's window to a translucent one for the
 * length of the gesture, which is what lets the activity underneath become visible and resume; the
 * window background is made transparent to match, and both are put back exactly on cancel. Orbit
 * never screenshots Chats and never rebuilds it, so what appears behind the conversation is
 * whatever Chats genuinely looks like right now. If the platform refuses the conversion the
 * gesture still runs and the conversation still tracks the finger, over Orbit's own background
 * rather than over Chats, and Diagnostics records which of the two happened.
 *
 * <p><b>What it refuses.</b> While the keyboard is up, back belongs to the keyboard: the gesture
 * starts no horizontal motion and a committed back closes the keyboard instead of the
 * conversation, so the old two-step behaviour survives byte for byte. While the attachment chooser
 * is open this is not armed at all — {@link OrbitBackHandler} is, and exactly one of the two ever
 * holds back.
 *
 * <p>Every platform type older devices do not have is reached only from {@link Api34}, so this
 * class loads on Orbit's minimum API without resolving a class that is not there.
 */
public final class OrbitPredictiveBack {

    /** How much of the width the conversation has travelled at full gesture progress. */
    private static final float TRAVEL = 0.90f;
    /** How far the conversation shrinks at full progress. Restrained on purpose. */
    private static final float SCALE_AT_FULL = 0.94f;
    /** Corner radius, in dp, the conversation reaches at full progress. */
    private static final int CORNER_DP = 28;
    /** Longest the commit may take to carry the conversation the rest of the way out. */
    private static final long COMMIT_MAX_MS = 190L;

    /** The gesture callbacks, in a shape that mentions no API 34 type. */
    interface Progress {
        void started();
        void progressed(float progress);
        void cancelled();
        void invoked();
    }

    private final Activity activity;
    private final View content;
    /** An {@code android.window.OnBackAnimationCallback} on API 34+, null below it. */
    private final Object callback;

    private boolean armed;
    private boolean detached;
    /** True between onBackStarted and whichever of cancel/commit ends it. */
    private boolean gesturing;
    /** True when this gesture belongs to the keyboard and must not move the conversation. */
    private boolean imeOwnsGesture;
    /** True while the window is dressed for the gesture and must be undressed again. */
    private boolean revealing;
    /** True when the platform actually converted the window and Chats is genuinely behind it. */
    private boolean revealedRealChats;
    private boolean finished;
    private int progressEvents;
    private float corner;
    private ValueAnimator settle;

    private OrbitPredictiveBack(Activity activity, View content) {
        this.activity = activity;
        this.content = content;
        this.callback = available() ? Api34.newCallback(new Progress() {
            @Override public void started() { OrbitPredictiveBack.this.started(); }
            @Override public void progressed(float p) { OrbitPredictiveBack.this.progressed(p); }
            @Override public void cancelled() { OrbitPredictiveBack.this.cancelled(); }
            @Override public void invoked() { OrbitPredictiveBack.this.invoked(); }
        }) : null;
    }

    /**
     * True when this device has the progress-reporting back API Orbit draws from.
     *
     * <p>API 34 is where {@code onBackProgressed} arrives. Below it there is no progress to draw
     * with, so Orbit keeps its ordinary page transition rather than inventing a gesture the
     * platform cannot report.
     */
    public static boolean available() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
    }

    /** True when this device and this user's preference both call for the Orbit-drawn gesture. */
    public static boolean enabled(Context c) {
        return available() && Prefs.enhancedChatBack(c);
    }

    public static OrbitPredictiveBack attach(Activity activity, View content) {
        return new OrbitPredictiveBack(activity, content);
    }

    /**
     * Arms or disarms the gesture.
     *
     * <p>Registering last wins on a shared priority, so a screen that arms this must first disarm
     * anything else it owns and vice versa. {@code ChatActivity} does exactly that in one place.
     */
    public void setArmed(boolean value) {
        if (detached || armed == value) return;
        if (value && !enabled(activity)) return;
        armed = value;
        if (callback == null) return;
        try {
            Api34.setRegistered(activity, callback, value);
        } catch (Exception ignored) {
            // A dispatcher that will not take the callback leaves back with the system, which is
            // the safe direction: the screen loses a refinement, never its ability to go back.
            armed = false;
        }
        if (!armed) reset();
    }

    public boolean isArmed() { return armed; }

    /** Releases the callback for good and puts the window back. Safe to call more than once. */
    public void detach() {
        setArmed(false);
        detached = true;
        reset();
    }

    // ---- the gesture ----------------------------------------------------------------------------

    private void started() {
        if (gesturing) return;
        gesturing = true;
        finished = false;
        progressEvents = 0;
        imeOwnsGesture = keyboardVisible();
        if (imeOwnsGesture) return;
        cancelSettle();
        beginReveal();
        apply(0f);
    }

    private void progressed(float progress) {
        if (!gesturing || imeOwnsGesture) return;
        progressEvents++;
        apply(progress);
    }

    private void cancelled() {
        if (!gesturing) return;
        gesturing = false;
        if (imeOwnsGesture) {
            imeOwnsGesture = false;
            return;
        }
        DiagnosticStore.recordBackGesture(activity, "cancelled", progressEvents);
        settleBack();
    }

    private void invoked() {
        boolean wasGesturing = gesturing;
        gesturing = false;
        if (imeOwnsGesture) {
            // Back belongs to the keyboard first. The conversation has not moved and does not
            // leave; the next back is the one that navigates.
            imeOwnsGesture = false;
            hideKeyboard();
            return;
        }
        DiagnosticStore.recordBackGesture(activity, "committed", wasGesturing ? progressEvents : 0);
        if (!revealing || !UiKit.animationsEnabled()) {
            finishOnce();
            return;
        }
        // The same motion, carried the rest of the way. Not a second animation: it starts from
        // exactly where the finger left the conversation and continues in the same direction.
        cancelSettle();
        float width = Math.max(1f, content.getWidth());
        float from = content.getTranslationX();
        float to = width * TRAVEL;
        float remaining = Math.max(0f, (to - from) / Math.max(1f, to));
        long duration = Math.max(60L, (long) (COMMIT_MAX_MS * remaining));
        settle = ValueAnimator.ofFloat(progressOf(from), 1f);
        settle.setDuration(duration);
        settle.setInterpolator(UiKit.motionEasing());
        settle.addUpdateListener(a -> apply((float) a.getAnimatedValue()));
        settle.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { finishOnce(); }
        });
        settle.start();
    }

    // ---- drawing --------------------------------------------------------------------------------

    /** The whole picture, as a pure function of gesture progress. Nothing else feeds it. */
    private void apply(float progress) {
        float p = Math.max(0f, Math.min(1f, progress));
        float width = Math.max(1f, content.getWidth());
        float scale = 1f - (1f - SCALE_AT_FULL) * p;
        content.setPivotX(0f);
        content.setPivotY(content.getHeight() / 2f);
        content.setTranslationX(width * TRAVEL * p);
        content.setScaleX(scale);
        content.setScaleY(scale);
        corner = UiKit.dp(activity, CORNER_DP) * p;
        content.invalidateOutline();
    }

    /** The progress a given translation stands for, so a commit continues rather than restarts. */
    private float progressOf(float translationX) {
        float width = Math.max(1f, content.getWidth());
        return Math.max(0f, Math.min(1f, translationX / (width * TRAVEL)));
    }

    private void settleBack() {
        cancelSettle();
        if (!UiKit.animationsEnabled()) {
            endReveal();
            return;
        }
        settle = ValueAnimator.ofFloat(progressOf(content.getTranslationX()), 0f);
        settle.setDuration(UiKit.MOTION_STANDARD);
        settle.setInterpolator(UiKit.motionEasing());
        settle.addUpdateListener(a -> apply((float) a.getAnimatedValue()));
        settle.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { endReveal(); }
        });
        settle.start();
    }

    private void cancelSettle() {
        if (settle != null) {
            settle.cancel();
            settle = null;
        }
    }

    /**
     * Dresses the window so the activity underneath can be seen, and remembers whether it worked.
     *
     * <p>The conversation's own root keeps its opaque background: it is the thing being moved. What
     * has to become see-through is everything behind it, which is the window itself.
     */
    private void beginReveal() {
        if (revealing) return;
        revealing = true;
        try { revealedRealChats = activity.setTranslucent(true); }
        catch (Throwable t) { revealedRealChats = false; }
        if (revealedRealChats) {
            try { activity.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); }
            catch (Exception ignored) {}
        }
        content.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), corner);
            }
        });
        content.setClipToOutline(true);
        content.setElevation(UiKit.dp(activity, 12));
        DiagnosticStore.recordBackReveal(activity, revealedRealChats);
    }

    /** Puts everything back exactly as it was, so a cancelled gesture leaves no trace. */
    private void endReveal() {
        cancelSettle();
        corner = 0f;
        content.setTranslationX(0f);
        content.setScaleX(1f);
        content.setScaleY(1f);
        content.setElevation(0f);
        content.setClipToOutline(false);
        content.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        if (!revealing) return;
        revealing = false;
        try { activity.getWindow().setBackgroundDrawable(new ColorDrawable(UiKit.BG)); }
        catch (Exception ignored) {}
        if (revealedRealChats) {
            try { activity.setTranslucent(false); }
            catch (Throwable ignored) {}
        }
        revealedRealChats = false;
    }

    /** Leaves the conversation once, however many times the platform asks. */
    private void finishOnce() {
        if (finished) return;
        finished = true;
        // The gesture already carried the conversation out. A window close animation on top of
        // that is the "disconnected second animation" this feature exists to avoid.
        try { Api34.clearCloseTransition(activity); }
        catch (Exception ignored) {}
        try { activity.finish(); }
        catch (Exception ignored) {}
    }

    /** Undresses the window and forgets the gesture, without navigating. */
    private void reset() {
        gesturing = false;
        imeOwnsGesture = false;
        endReveal();
    }

    // ---- the keyboard ---------------------------------------------------------------------------

    /**
     * Stands in for the platform's IME insets where there are none to read.
     *
     * <p>Only a test ever sets this. Robolectric reports no window insets at all, and the rule this
     * guards — that back belongs to the keyboard first, and the conversation must not start sliding
     * while it does — is too important to leave unasserted for want of a real IME.
     */
    static Boolean keyboardVisibleForTest;

    private boolean keyboardVisible() {
        if (keyboardVisibleForTest != null) return keyboardVisibleForTest;
        try {
            WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
            if (insets != null) return insets.isVisible(WindowInsets.Type.ime());
        } catch (Exception ignored) {}
        return false;
    }

    private void hideKeyboard() {
        try {
            View focus = activity.getCurrentFocus();
            View target = focus != null ? focus : activity.getWindow().getDecorView();
            InputMethodManager imm =
                    (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(target.getWindowToken(), 0);
        } catch (Exception ignored) {}
    }

    // ---- what tests and Diagnostics may ask ------------------------------------------------------

    /** How many progress events this gesture has actually been given. */
    int progressEventsForTest() { return progressEvents; }

    /** Where the conversation currently sits. Zero at rest. */
    float translationForTest() { return content.getTranslationX(); }

    /** True while a gesture is being drawn. */
    boolean gesturingForTest() { return gesturing; }

    /** True while the window is dressed for a reveal. */
    boolean revealingForTest() { return revealing; }

    /** True once this gesture has committed. Never true twice for one gesture. */
    boolean finishedForTest() { return finished; }

    void startedForTest() { started(); }
    void progressedForTest(float p) { progressed(p); }
    void cancelledForTest() { cancelled(); }
    void invokedForTest() { invoked(); }

    /** Everything that touches an API 34+ type, kept where an older runtime never loads it. */
    private static final class Api34 {
        static Object newCallback(Progress progress) {
            return new android.window.OnBackAnimationCallback() {
                @Override public void onBackStarted(android.window.BackEvent event) {
                    progress.started();
                }
                @Override public void onBackProgressed(android.window.BackEvent event) {
                    progress.progressed(event.getProgress());
                }
                @Override public void onBackCancelled() { progress.cancelled(); }
                @Override public void onBackInvoked() { progress.invoked(); }
            };
        }

        static void setRegistered(Activity activity, Object callback, boolean registered) {
            android.window.OnBackInvokedDispatcher dispatcher = activity.getOnBackInvokedDispatcher();
            android.window.OnBackAnimationCallback typed =
                    (android.window.OnBackAnimationCallback) callback;
            if (registered) {
                dispatcher.registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, typed);
            } else {
                dispatcher.unregisterOnBackInvokedCallback(typed);
            }
        }

        static void clearCloseTransition(Activity activity) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0);
        }
    }
}
