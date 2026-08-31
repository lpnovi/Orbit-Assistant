package com.orbit.assistant;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;

/**
 * Orbit's back gesture: one page, drawn by Orbit from the finger's own progress.
 *
 * <p><b>Why Orbit draws it.</b> v0.7.7.9 Beta 1 tried the better-looking thing: register nothing,
 * declare no close animation, and let Android's own cross-activity predictive back track the
 * finger. Every check that could be made off-device passed — manifest opted in, API present, no
 * callback armed — and on the acceptance device, a Galaxy S25 Ultra on One UI, nothing moved at
 * all. Orbit has no way to observe whether the system rendered those frames, so it also had no way
 * to know it had shipped nothing. A transition Orbit only <i>requests</i> is a transition Orbit
 * cannot promise. This one it draws, which means it can also count the progress events that drew it
 * and report them.
 *
 * <p><b>What it does.</b> On API 34+ it registers an {@code OnBackAnimationCallback} and moves the
 * page horizontally as a direct function of {@code BackEvent.progress}: no timers, no fling,
 * nothing waiting for the finger to lift. Reversing the gesture reverses the picture because the
 * picture is only ever a function of the number the platform last reported. Beta 2 tuned that
 * motion on the device and Beta 3 changed none of it — the travel, scale, corner and commit timings
 * below are the approved ones. Every screen now gets that same interaction rather than an imitation
 * of it, which is the whole reason this is one class and not one per screen.
 *
 * <p><b>What is revealed.</b> The real screen underneath, never a picture of one.
 * {@link Activity#setTranslucent(boolean)} converts this window to a translucent one for the length
 * of the gesture, which is what lets the activity below become visible and resume; the window
 * background is made transparent to match and both are put back exactly on cancel. Orbit screenshots
 * nothing and rebuilds nothing, so what appears behind Look &amp; Feel is the genuine Settings hub.
 * If the platform refuses the conversion the gesture still tracks the finger, over Orbit's own
 * background rather than over the previous page, and Diagnostics records which of the two happened.
 *
 * <p><b>What a screen decides, and what this decides.</b> This owns the mechanics: capability,
 * callback, lifecycle, motion, translucency, restoration, reduced motion, committing without a
 * second animation, and the counters. A {@link Screen} supplies only policy — whether leaving is
 * unconditional right now, and what Back actually means here. That is how an editor keeps its
 * discard confirmation: it reports that it cannot navigate while it is dirty, so the page does not
 * move, and Back still reaches the same confirmation by the same route it always did.
 *
 * <p><b>What it refuses.</b> While the keyboard is up, back belongs to the keyboard: no horizontal
 * motion starts and a committed back closes the keyboard instead of leaving. Dialogs and popups own
 * their own windows and take Back before this is ever consulted.
 *
 * <p>Every platform type older devices do not have is reached only from {@link Api34}, so this class
 * loads on Orbit's minimum API without resolving a class that is not there.
 */
public final class OrbitPredictiveBack {

    /** How much of the width the page has travelled at full gesture progress. */
    private static final float TRAVEL = 0.90f;
    /** How far the page shrinks at full progress. Restrained on purpose. */
    private static final float SCALE_AT_FULL = 0.94f;
    /** Corner radius, in dp, the page reaches at full progress. */
    private static final int CORNER_DP = 28;
    /** Longest the commit may take to carry the page the rest of the way out. */
    private static final long COMMIT_MAX_MS = 190L;

    /**
     * Everything one screen decides for itself.
     *
     * <p>Deliberately two questions and a name. Anything more and screens start owning pieces of the
     * mechanism, which is exactly how an app ends up with ten slightly different predictive back
     * implementations that drift apart.
     */
    public interface Screen {
        /**
         * True when the page may visibly move: Back right now unconditionally means "leave, and the
         * screen underneath is where I arrive". An editor holding unsaved work answers false, and so
         * does a screen with something of its own still open.
         */
        default boolean canNavigate() { return true; }

        /** What Back means on this screen. Called once per Back, whether gesture or button. */
        void navigateBack();

        /** Privacy-safe category name for Diagnostics. Never content, never a title. */
        String screenName();
    }

    /** The gesture callbacks, in a shape that mentions no API 34 type. */
    interface Progress {
        void started();
        void progressed(float progress);
        void cancelled();
        void invoked();
    }

    private final Activity activity;
    private final Screen screen;
    /** An {@code android.window.OnBackAnimationCallback} on API 34+, null below it. */
    private final Object callback;

    private boolean armed;
    /** True when what is registered is the animation callback rather than the plain one. */
    private boolean drawsGesture;
    /** An {@code android.window.OnBackInvokedCallback} for the non-drawn path, built on demand. */
    private Object plain;
    private boolean detached;
    /** True between onBackStarted and whichever of cancel/commit ends it. */
    private boolean gesturing;
    /** True when this gesture belongs to the keyboard and must not move the page. */
    private boolean imeOwnsGesture;
    /** True when the screen declined to move, but Back still has to mean what it means there. */
    private boolean screenDeclined;
    /** True while the window is dressed for the gesture and must be undressed again. */
    private boolean revealing;
    /** True when the platform converted the window and the previous page is genuinely behind. */
    private boolean revealedRealPage;
    private boolean navigated;
    private int progressEvents;
    private float corner;
    private ValueAnimator settle;
    /**
     * The page being moved, resolved when a gesture starts rather than remembered.
     *
     * <p>Several Orbit screens call {@code setContentView} again in place — Settings for an accent
     * change, Extensions for a re-render — so a view captured at install time can be the previous
     * page's root by the time anyone swipes. Reading it from the window at the moment the gesture
     * begins is always the page actually on screen.
     */
    private View moving;
    /** A background this class added because the page had none, and must take away again. */
    private boolean addedBackground;

    private OrbitPredictiveBack(Activity activity, Screen screen) {
        this.activity = activity;
        this.screen = screen;
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
     * with, so Orbit keeps its ordinary page transition rather than inventing a gesture the platform
     * cannot report.
     */
    public static boolean available() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
    }

    /** True when this device and this user's preference both call for the Orbit-drawn gesture. */
    public static boolean enabled(Context c) {
        return available() && Prefs.swipeToGoBack(c);
    }

    /** Attaches without arming, for a screen that decides for itself when to hold Back. */
    public static OrbitPredictiveBack attach(Activity activity, Screen screen) {
        return new OrbitPredictiveBack(activity, screen);
    }

    /**
     * The whole of what an ordinary hierarchical screen needs: one call in {@code onCreate}.
     *
     * <p>Arms the gesture and releases it when the Activity is destroyed, without that Activity
     * having to remember to. A screen whose classification in {@link OrbitNavigation} does not use
     * the gesture gets a handler that is never armed, so calling this from the wrong place is inert
     * rather than wrong — the classification is the single source of truth, not the call site.
     */
    public static OrbitPredictiveBack install(Activity activity, Screen screen) {
        OrbitPredictiveBack handler = new OrbitPredictiveBack(activity, screen);
        if (!OrbitNavigation.usesPredictive(activity.getClass())) return handler;
        handler.setArmed(true);
        handler.detachWhenDestroyed();
        return handler;
    }

    /** The ordinary case: Back finishes this screen, and Orbit already knows its category. */
    public static OrbitPredictiveBack install(Activity activity) {
        return install(activity, new Screen() {
            @Override public void navigateBack() { activity.finish(); }
            @Override public String screenName() {
                return OrbitNavigation.labelFor(activity.getClass());
            }
        });
    }

    /** Releases the callback and undresses the window when the screen goes, by whatever route. */
    private void detachWhenDestroyed() {
        try {
            activity.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(Activity a, Bundle b) {}
                @Override public void onActivityStarted(Activity a) {}
                @Override public void onActivityResumed(Activity a) {}
                @Override public void onActivityPaused(Activity a) {}
                @Override public void onActivityStopped(Activity a) {}
                @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
                @Override public void onActivityDestroyed(Activity a) { detach(); }
            });
        } catch (Exception ignored) {
            // Worst case nothing outlives anything: the dispatcher is per-window and goes with the
            // window. The screen keeps its gesture either way.
        }
    }

    /**
     * Takes or releases Back for this screen.
     *
     * <p>Two different callbacks can be registered here, and which one matters more than it looks.
     * Where the device reports gesture progress and the user has the gesture on, it is the animation
     * callback and the page moves. Everywhere else on API 33+ it is a plain callback that does
     * nothing but perform the screen's own Back.
     *
     * <p>That plain callback is not a nicety. A screen that declares
     * {@code enableOnBackInvokedCallback} stops having {@code onBackPressed} called at all, and if
     * nothing is registered the platform simply finishes it — which on a routine editor would mean
     * Back silently discarding unsaved changes that used to be protected by a confirmation. So every
     * screen that opts in registers something on API 33+, and what it registers always ends at
     * {@link Screen#navigateBack()}. The gesture is a refinement on top of that, never the thing
     * that makes Back correct.
     *
     * <p>Below API 33 there is no dispatcher, nothing is registered, and the screens that override
     * {@code onBackPressed} keep reaching it by the legacy path exactly as before.
     *
     * <p>Registering last wins on a shared priority, so a screen that arms this must first disarm
     * anything else it owns and vice versa. {@code ChatActivity} does exactly that in one place.
     */
    public void setArmed(boolean value) {
        if (detached || armed == value) return;
        if (value && !OrbitBackHandler.supported()) return;
        boolean wantsGesture = value && enabled(activity);
        armed = value;
        try {
            if (value) {
                if (wantsGesture && callback != null) {
                    Api34.setRegistered(activity, callback, true);
                    drawsGesture = true;
                } else {
                    Api33.setRegistered(activity, plainCallback(), true);
                    drawsGesture = false;
                }
            } else {
                if (drawsGesture && callback != null) Api34.setRegistered(activity, callback, false);
                else if (plain != null) Api33.setRegistered(activity, plain, false);
                drawsGesture = false;
            }
        } catch (Exception ignored) {
            // A dispatcher that will not take the callback leaves back with the system, which is
            // the safe direction: the screen loses a refinement, never its ability to go back.
            armed = false;
            drawsGesture = false;
        }
        if (!armed) reset();
    }

    /** True when this screen currently owns Back at all, with or without the drawn motion. */
    public boolean isArmed() { return armed; }

    /** True when Back on this screen will actually be drawn with the finger. */
    public boolean drawsGesture() { return drawsGesture; }

    /** The plain API 33+ callback, created once and only when it is needed. */
    private Object plainCallback() {
        if (plain == null) plain = Api33.newCallback(this::navigateOnceFromPlainBack);
        return plain;
    }

    /** A plain Back has no gesture behind it, so nothing is animated and nothing is reset. */
    private void navigateOnceFromPlainBack() {
        DiagnosticStore.recordBackGesture(activity, screen.screenName(), "committed", 0, false);
        try { screen.navigateBack(); }
        catch (Exception ignored) {}
    }

    /** Releases the callback for good and puts the window back. Safe to call more than once. */
    public void detach() {
        setArmed(false);
        detached = true;
        reset();
    }

    /**
     * What this screen's own Back control does: exactly what Back does.
     *
     * <p>Routed through the same policy the gesture commits to, so a tap and a swipe cannot arrive
     * at two different destinations, and an editor's discard confirmation is reached from both. It
     * does not imitate the gesture — a tap is not a drag, and the ordinary committed transition is
     * the honest result of one.
     */
    public void performBack() {
        DiagnosticStore.recordBackButton(activity, screen.screenName());
        screen.navigateBack();
    }

    // ---- the gesture ----------------------------------------------------------------------------

    private void started() {
        if (gesturing) return;
        gesturing = true;
        navigated = false;
        progressEvents = 0;
        imeOwnsGesture = keyboardVisible();
        if (imeOwnsGesture) return;
        // Asked once, at the start. A screen cannot become safe to leave halfway through a gesture,
        // and a page that began moving must not stop because a field changed under it.
        screenDeclined = !screen.canNavigate();
        if (screenDeclined) return;
        cancelSettle();
        beginReveal();
        apply(0f);
    }

    private void progressed(float progress) {
        if (!gesturing || imeOwnsGesture || screenDeclined) return;
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
        if (screenDeclined) {
            screenDeclined = false;
            return;
        }
        DiagnosticStore.recordBackGesture(activity, screen.screenName(), "cancelled",
                progressEvents, true);
        settleBack();
    }

    private void invoked() {
        boolean wasGesturing = gesturing;
        gesturing = false;
        if (imeOwnsGesture) {
            // Back belongs to the keyboard first. The page has not moved and does not leave; the
            // next back is the one that navigates.
            imeOwnsGesture = false;
            hideKeyboard();
            return;
        }
        if (screenDeclined) {
            // The page never moved, because leaving was not unconditional. Back still means what it
            // means here, which for an editor is its own save-or-discard question.
            screenDeclined = false;
            DiagnosticStore.recordBackGesture(activity, screen.screenName(), "committed", 0, false);
            navigateOnce();
            return;
        }
        DiagnosticStore.recordBackGesture(activity, screen.screenName(), "committed",
                wasGesturing ? progressEvents : 0, true);
        View page = moving;
        if (!revealing || page == null || !UiKit.animationsEnabled()) {
            navigateOnce();
            return;
        }
        // The same motion, carried the rest of the way. Not a second animation: it starts from
        // exactly where the finger left the page and continues in the same direction.
        cancelSettle();
        float width = Math.max(1f, page.getWidth());
        float from = page.getTranslationX();
        float to = width * TRAVEL;
        float remaining = Math.max(0f, (to - from) / Math.max(1f, to));
        long duration = Math.max(60L, (long) (COMMIT_MAX_MS * remaining));
        settle = ValueAnimator.ofFloat(progressOf(from), 1f);
        settle.setDuration(duration);
        settle.setInterpolator(UiKit.motionEasing());
        settle.addUpdateListener(a -> apply((float) a.getAnimatedValue()));
        settle.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { navigateOnce(); }
        });
        settle.start();
    }

    // ---- drawing --------------------------------------------------------------------------------

    /**
     * The whole picture, as a pure function of gesture progress. Nothing else feeds it.
     *
     * <p>Four view properties and an outline invalidation. Nothing here measures, lays out, reads
     * storage or rebuilds content, which is why a long Settings page moves exactly as smoothly as a
     * short one: the cost of a frame does not depend on what is on the page.
     */
    private void apply(float progress) {
        View page = moving;
        if (page == null) return;
        float p = Math.max(0f, Math.min(1f, progress));
        float width = Math.max(1f, page.getWidth());
        float scale = 1f - (1f - SCALE_AT_FULL) * p;
        page.setPivotX(0f);
        page.setPivotY(page.getHeight() / 2f);
        page.setTranslationX(width * TRAVEL * p);
        page.setScaleX(scale);
        page.setScaleY(scale);
        corner = UiKit.dp(activity, CORNER_DP) * p;
        page.invalidateOutline();
    }

    /** The progress a given translation stands for, so a commit continues rather than restarts. */
    private float progressOf(float translationX) {
        View page = moving;
        float width = page == null ? 1f : Math.max(1f, page.getWidth());
        return Math.max(0f, Math.min(1f, translationX / (width * TRAVEL)));
    }

    private void settleBack() {
        cancelSettle();
        View page = moving;
        if (page == null || !UiKit.animationsEnabled()) {
            endReveal();
            return;
        }
        settle = ValueAnimator.ofFloat(progressOf(page.getTranslationX()), 0f);
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

    /** The page currently on screen, whatever this Activity last set as its content. */
    private View resolvePage() {
        try {
            View host = activity.findViewById(android.R.id.content);
            if (host instanceof ViewGroup && ((ViewGroup) host).getChildCount() > 0) {
                return ((ViewGroup) host).getChildAt(0);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Dresses the window so the activity underneath can be seen, and remembers whether it worked.
     *
     * <p>The page's own root keeps its opaque background: it is the thing being moved. What has to
     * become see-through is everything behind it, which is the window itself. A page that never gave
     * itself a background is given Orbit's for the duration, because a see-through page over a
     * see-through window would show the screen below through the page rather than beside it.
     */
    private void beginReveal() {
        if (revealing) return;
        moving = resolvePage();
        if (moving == null) return;
        revealing = true;
        try { revealedRealPage = activity.setTranslucent(true); }
        catch (Throwable t) { revealedRealPage = false; }
        if (revealedRealPage) {
            try { activity.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); }
            catch (Exception ignored) {}
        }
        if (moving.getBackground() == null) {
            moving.setBackgroundColor(UiKit.BG);
            addedBackground = true;
        }
        moving.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), corner);
            }
        });
        moving.setClipToOutline(true);
        moving.setElevation(UiKit.dp(activity, 12));
        DiagnosticStore.recordBackReveal(activity, revealedRealPage);
    }

    /** Puts everything back exactly as it was, so a cancelled gesture leaves no trace. */
    private void endReveal() {
        cancelSettle();
        corner = 0f;
        View page = moving;
        if (page != null) {
            page.setTranslationX(0f);
            page.setScaleX(1f);
            page.setScaleY(1f);
            page.setElevation(0f);
            page.setClipToOutline(false);
            page.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            if (addedBackground) page.setBackground(null);
        }
        addedBackground = false;
        moving = null;
        if (!revealing) return;
        revealing = false;
        try { activity.getWindow().setBackgroundDrawable(new ColorDrawable(UiKit.BG)); }
        catch (Exception ignored) {}
        if (revealedRealPage) {
            try { activity.setTranslucent(false); }
            catch (Throwable ignored) {}
        }
        revealedRealPage = false;
    }

    /** Performs this screen's Back once, however many times the platform asks. */
    private void navigateOnce() {
        if (navigated) return;
        navigated = true;
        // The gesture already carried the page out. A window close animation on top of that is the
        // "disconnected second animation" this feature exists to avoid.
        if (revealing) {
            try { Api34.clearCloseTransition(activity); }
            catch (Exception ignored) {}
        }
        try { screen.navigateBack(); }
        catch (Exception ignored) {}
    }

    /** Undresses the window and forgets the gesture, without navigating. */
    private void reset() {
        gesturing = false;
        imeOwnsGesture = false;
        screenDeclined = false;
        endReveal();
    }

    // ---- the keyboard ---------------------------------------------------------------------------

    /**
     * Stands in for the platform's IME insets where there are none to read.
     *
     * <p>Only a test ever sets this. Robolectric reports no window insets at all, and the rule this
     * guards — that back belongs to the keyboard first, and the page must not start sliding while it
     * does — is too important to leave unasserted for want of a real IME.
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

    /** Where the page currently sits. Zero at rest. */
    float translationForTest() {
        View page = moving;
        return page == null ? 0f : page.getTranslationX();
    }

    /** True while a gesture is being drawn. */
    boolean gesturingForTest() { return gesturing; }

    /** True while the window is dressed for a reveal. */
    boolean revealingForTest() { return revealing; }

    /** True once this gesture has performed the screen's Back. Never true twice for one gesture. */
    boolean navigatedForTest() { return navigated; }

    /** True when the screen refused to move for the gesture in progress. */
    boolean declinedForTest() { return screenDeclined; }

    void startedForTest() { started(); }
    void progressedForTest(float p) { progressed(p); }
    void cancelledForTest() { cancelled(); }
    void invokedForTest() { invoked(); }

    /** The plain back callback, for every opted-in screen that is not drawing the gesture. */
    private static final class Api33 {
        static Object newCallback(Runnable onBack) {
            return (android.window.OnBackInvokedCallback) onBack::run;
        }

        static void setRegistered(Activity activity, Object callback, boolean registered) {
            android.window.OnBackInvokedDispatcher dispatcher = activity.getOnBackInvokedDispatcher();
            android.window.OnBackInvokedCallback typed = (android.window.OnBackInvokedCallback) callback;
            if (registered) {
                dispatcher.registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, typed);
            } else {
                dispatcher.unregisterOnBackInvokedCallback(typed);
            }
        }
    }

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
