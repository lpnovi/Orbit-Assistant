package com.orbit.assistant;

import android.app.Activity;
import android.os.Build;

/**
 * Orbit's single answer to "what does Back do on this screen", for the screens that need one.
 *
 * <p>The point of this class is mostly what it refuses to do. Android already owns the back
 * gesture: it tracks the finger, it animates the finishing activity out, and it reveals the real
 * activity underneath. A screen that keeps a back callback registered takes that away, because the
 * system can no longer know the gesture will end in a navigation and so cannot animate one. The
 * rule here is therefore that a callback exists only while a screen genuinely has something of its
 * own to close, and the moment it does not, back belongs to the platform again.
 *
 * <p>That is the entire mechanism behind the conversation's predictive back. {@code ChatActivity}
 * arms this while an attachment chooser is open and disarms it when the chooser closes; the rest
 * of the time nothing is registered, the system sees an ordinary finishable activity, and the back
 * gesture becomes the real finger-tracked transition that reveals the real Chats screen. No
 * screenshot of Chats is drawn, no touch listener competes with system navigation, and Orbit
 * writes no animation code for it at all.
 *
 * <p>Below API 33 there is no {@code OnBackInvokedDispatcher}. The handler still tracks whether it
 * is armed and the activity's own {@code onBackPressed} override asks it, so one description of the
 * behaviour serves both paths and older devices simply get ordinary back.
 *
 * <p>The platform types are held as {@link Object} and only ever touched inside a version guard, so
 * this class loads on Orbit's minimum API without resolving a class that does not exist there.
 */
public final class OrbitBackHandler {

    /** What a screen does when it consumes back itself. Called only while armed. */
    public interface Handler {
        void onBack();
    }

    private final Activity activity;
    private final Handler handler;
    /** An {@code android.window.OnBackInvokedCallback} on API 33+, null below it. */
    private final Object callback;
    private boolean armed;
    private boolean detached;

    private OrbitBackHandler(Activity activity, Handler handler) {
        this.activity = activity;
        this.handler = handler;
        this.callback = supported() ? Api33.newCallback(handler) : null;
    }

    /** True when this device has the platform back-callback API Orbit registers against. */
    public static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    /**
     * True when this device can run the system's finger-tracked predictive back transition.
     *
     * <p>Progress-driven back animations arrive with API 34. Below that the platform still runs
     * back correctly, it simply commits without an interactive transition, so Orbit keeps its own
     * page transition there rather than offering something the device cannot actually do.
     */
    public static boolean predictiveTransitionAvailable() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
    }

    public static OrbitBackHandler attach(Activity activity, Handler handler) {
        return new OrbitBackHandler(activity, handler);
    }

    /**
     * Arms or disarms this screen's own back behaviour.
     *
     * <p>Arming registers a platform callback and hands back to the screen; disarming unregisters
     * it and gives back to the system, which is what lets the predictive transition run. Repeated
     * calls with the same value do nothing, so a screen may call this from any state change without
     * tracking what it last asked for.
     */
    public void setArmed(boolean value) {
        if (detached || armed == value) return;
        armed = value;
        if (callback == null) return;
        try {
            Api33.setRegistered(activity, callback, value);
        } catch (Exception ignored) {
            // A dispatcher that will not take the callback leaves back with the system, which is
            // the safe direction: the screen loses a refinement, never its ability to go back.
        }
    }

    public boolean isArmed() { return armed; }

    /** Releases the callback for good. Safe to call more than once. */
    public void detach() {
        setArmed(false);
        detached = true;
    }

    /**
     * The legacy path, for devices with no back-callback API.
     *
     * @return true when this handler consumed the press and the activity should not finish.
     */
    public boolean consumeLegacyBack() {
        if (supported() || !armed) return false;
        handler.onBack();
        return true;
    }

    /**
     * What Orbit's own Back control does: exactly what Back does.
     *
     * <p>Asks whatever is armed first and otherwise performs the ordinary back action, so a button
     * press and a gesture cannot drift into two different destinations. It does not try to fake a
     * gesture: a tap is not a drag, and the platform's committed transition is the honest result.
     */
    public void performBack() {
        if (armed) {
            handler.onBack();
            return;
        }
        activity.onBackPressed();
    }

    /** Everything that touches an API 33+ type, kept where an older runtime never loads it. */
    private static final class Api33 {
        static Object newCallback(Handler handler) {
            return (android.window.OnBackInvokedCallback) handler::onBack;
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
}
