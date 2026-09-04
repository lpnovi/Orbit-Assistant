package com.orbit.assistant;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What Back means on every full-app Orbit screen, written down in one place.
 *
 * <p>v0.7.7.9 Beta 2 proved the interaction on one screen. Beta 3 spreads it, and the risk in
 * spreading it is not the animation — it is applying it somewhere Back does not actually mean "go up
 * one Orbit page". A screen that hands its result back to a caller, asks whether to discard, or
 * exists only to open an Android-owned surface has a different contract, and sliding it away as
 * though leaving were unconditional would be wrong however good it looked.
 *
 * <p>So every Activity is classified here rather than at each call site. The table is the audit: a
 * new screen that is not in it gets no gesture at all, which is the safe default, and the test suite
 * asserts the classification of every screen Orbit ships so that a future one cannot quietly land in
 * the wrong class. Diagnostics counts from the same table, so what it reports and what is installed
 * cannot disagree.
 *
 * <p>The names here are screen categories, never content. "Settings" and "Memory" say which page a
 * gesture happened on; nothing in this file can say what was on it.
 */
public final class OrbitNavigation {

    /** How one screen answers Back. */
    public enum Policy {
        /**
         * Ordinary hierarchical navigation. Back finishes this screen and the previous Orbit
         * screen is genuinely underneath it, so the gesture may move the page and reveal it.
         */
        PREDICTIVE,
        /**
         * An editor. Back is unconditional only while nothing is unsaved; once there is something
         * to lose, the screen's own save/discard contract runs instead and the page does not move.
         */
        GUARDED,
        /**
         * Back cancels an operation or steps within the screen rather than leaving it. The screen
         * keeps whatever it does today and gains nothing.
         */
        LOCAL,
        /** Orbit's root full-app destination. Back belongs to Android and the task. */
        ROOT
    }

    /** One screen's classification and the category name Diagnostics may report. */
    public static final class Screen {
        public final Policy policy;
        public final String label;
        Screen(Policy policy, String label) {
            this.policy = policy;
            this.label = label;
        }
    }

    private static final Map<String, Screen> SCREENS = new LinkedHashMap<>();

    private static void put(Class<? extends Activity> type, Policy policy, String label) {
        SCREENS.put(type.getName(), new Screen(policy, label));
    }

    static {
        // ---- PREDICTIVE: a real Orbit screen is underneath and Back simply returns to it -------

        // Both the hub and a section detail are SettingsActivity; a section is opened as a second
        // instance on top of the hub, so the hub really is the screen underneath a detail.
        put(SettingsActivity.class, Policy.PREDICTIVE, "Settings");
        put(ChatActivity.class, Policy.PREDICTIVE, "Chat");
        // Deck writes every edit the moment it is made, so it never holds unsaved work and Back is
        // unconditional. Its sheets and edit mode are closed by its own handler before this applies.
        put(DeckActivity.class, Policy.PREDICTIVE, "Deck");
        put(DiagnosticsActivity.class, Policy.PREDICTIVE, "Diagnostics");
        put(AiProvidersActivity.class, Policy.PREDICTIVE, "AI providers");
        put(LocalAiActivity.class, Policy.PREDICTIVE, "Orbit Local");
        put(CapabilitiesActivity.class, Policy.PREDICTIVE, "Capabilities");
        put(MemoryActivity.class, Policy.PREDICTIVE, "Memory");
        put(AppsActivity.class, Policy.PREDICTIVE, "Apps");
        put(NotificationsActivity.class, Policy.PREDICTIVE, "Notifications");
        put(SavedPlacesActivity.class, Policy.PREDICTIVE, "Saved places");
        put(RemindersActivity.class, Policy.PREDICTIVE, "Reminders");
        put(RoutinesActivity.class, Policy.PREDICTIVE, "Routines");
        put(RoutineTemplatesActivity.class, Policy.PREDICTIVE, "Routine templates");
        put(RoutineTriggersActivity.class, Policy.PREDICTIVE, "Routine triggers");
        put(RoutineRunHistoryActivity.class, Policy.PREDICTIVE, "Routine history");
        put(CustomCommandsActivity.class, Policy.PREDICTIVE, "Custom commands");
        put(ExtensionsActivity.class, Policy.PREDICTIVE, "Extensions");
        put(UpdateActivity.class, Policy.PREDICTIVE, "Updates");
        put(WhatsNewActivity.class, Policy.PREDICTIVE, "What's new");
        put(RoadmapActivity.class, Policy.PREDICTIVE, "Roadmap");

        // ---- GUARDED: editors, where leaving is only unconditional while nothing is unsaved ----

        // These three already ask before discarding. The gesture is offered while they are clean
        // and stands aside once they are not, so the existing confirmation is reached by the same
        // route it always was rather than being animated past.
        put(RoutineEditorActivity.class, Policy.GUARDED, "Routine editor");
        put(TimeTriggerEditorActivity.class, Policy.GUARDED, "Time trigger editor");
        put(LocationTriggerEditorActivity.class, Policy.GUARDED, "Location trigger editor");
        // These three do not ask, because nothing is written until Save. They still hold typing the
        // user would lose, so they follow the same rule: the page moves only when leaving costs
        // nothing. That is stricter than their behaviour before Beta 3 and never looser.
        // Theme Studio edits a draft and writes nothing until Apply, so leaving it with unapplied
        // colours would silently discard them. Same contract as the three above: the page moves
        // while the draft matches what is applied, and stands aside once it does not.
        put(ThemeStudioActivity.class, Policy.GUARDED, "Theme Studio");
        put(RoutineBuilderActivity.class, Policy.GUARDED, "Routine builder");
        put(CustomCommandEditorActivity.class, Policy.GUARDED, "Custom command editor");
        put(AppProfileActivity.class, Policy.GUARDED, "App behaviour");

        // ---- LOCAL: Back cancels or steps, and does not leave for a previous Orbit screen ------

        // Back is "cancel this selection" and returns a result to whoever asked for it.
        put(ScreenSelectionActivity.class, Policy.LOCAL, "Screen selection");
        // Invisible bridges to Android-owned surfaces. Back is a denial or a cancellation, and
        // there is deliberately no Orbit page to reveal because these draw nothing.
        put(AttachmentPickerActivity.class, Policy.LOCAL, "Attachment picker");
        // The external Share doorway. Not a page in Orbit's hierarchy at all: it arrives from
        // another app, draws nothing, and finishes as soon as it has opened the conversation, so
        // there is no Orbit screen underneath it for a page gesture to reveal. The conversation it
        // opens has a real Chats stack behind it and keeps the ordinary predictive Back.
        put(ShareToOrbitActivity.class, Policy.LOCAL, "Share to Orbit");
        // The external selected-text doorway. Exactly the same shape as the share bridge above:
        // it arrives from another app, draws nothing, and finishes once it has opened a
        // conversation, so there is no Orbit page beneath it for a gesture to reveal.
        put(ProcessTextToOrbitActivity.class, Policy.LOCAL, "Ask Orbit from text");
        // The full-screen attachment viewer. Not a page in the hierarchy but a detail surface laid
        // over one, and — the reason this matters more than the classification usually does — it
        // owns live pan and zoom gestures for the whole width of the screen. An app-wide back
        // gesture that moved the page as a function of horizontal progress would be competing for
        // the same finger as panning a zoomed photo. Back here closes the viewer, plainly.
        put(AttachmentViewerActivity.class, Policy.LOCAL, "Attachment viewer");
        put(DocumentViewerActivity.class, Policy.LOCAL, "Document viewer");
        put(CalendarPermissionActivity.class, Policy.LOCAL, "Calendar permission");
        put(OrbitWidgetActionActivity.class, Policy.LOCAL, "Widget action");
        // Widget configuration answers the launcher with a result; leaving it is not navigation.
        put(OrbitWidgetConfigureActivity.class, Policy.LOCAL, "Widget setup");
        // Onboarding owns Back for its own steps: it walks backwards through setup and confirms
        // before leaving it. A page-level gesture would be a second, competing meaning.
        put(OnboardingActivity.class, Policy.LOCAL, "Onboarding");
        // A full-screen scene laid over Chats rather than a page in the hierarchy. Back closes it
        // at once; animating it away as a page would be describing it as navigation, which it is
        // not, and would put a decorative canvas in contention with the app-wide gesture.
        put(OrbitLaunchSequenceActivity.class, Policy.LOCAL, "Launch sequence");

        // ---- ROOT ------------------------------------------------------------------------------

        // Chats is where the app starts. Back from here is the task's business, and inventing an
        // Orbit screen to reveal behind it would be a screen the user never navigated from.
        put(MainActivity.class, Policy.ROOT, "Chats");
    }

    private OrbitNavigation() {}

    /** The classification of one screen, or null when Orbit does not know it. */
    public static Screen screenFor(Class<?> type) {
        return type == null ? null : SCREENS.get(type.getName());
    }

    /** How Back behaves on one screen. Anything unlisted is treated as owning its own Back. */
    public static Policy policyFor(Class<?> type) {
        Screen screen = screenFor(type);
        return screen == null ? Policy.LOCAL : screen.policy;
    }

    /** The privacy-safe category name for one screen. */
    public static String labelFor(Class<?> type) {
        Screen screen = screenFor(type);
        return screen == null ? "Unknown" : screen.label;
    }

    /** True when this screen's Back means "return to the Orbit screen underneath". */
    public static boolean usesPredictive(Class<?> type) {
        Policy policy = policyFor(type);
        return policy == Policy.PREDICTIVE || policy == Policy.GUARDED;
    }

    /** How many screens can offer the gesture. Reported by Diagnostics. */
    public static int eligibleScreenCount() {
        int count = 0;
        for (Screen screen : SCREENS.values()) {
            if (screen.policy == Policy.PREDICTIVE || screen.policy == Policy.GUARDED) count++;
        }
        return count;
    }

    /** Every screen Orbit has classified. For the test matrix. */
    public static Map<String, Screen> all() {
        return java.util.Collections.unmodifiableMap(SCREENS);
    }

    /**
     * The stack a surface outside the app must open an Orbit screen with.
     *
     * <p>A notification, a widget, a Quick Settings tile or the assistant overlay starts from
     * nowhere. Launching a screen on its own makes it the root of a fresh task, so Back ends at the
     * launcher — and the gesture then has nothing of Orbit's to reveal, correctly, because there
     * genuinely is nothing there. {@code parentActivityName} does not help: the platform reads it
     * for Up navigation and for stacks built with {@code TaskStackBuilder}, never for an ordinary
     * Back. So Orbit builds the stack it wants explicitly, deepest intent last, and starts the whole
     * thing at once so only one transition plays.
     *
     * <p>{@code SINGLE_TOP} alongside {@code CLEAR_TOP} on Chats is what stops an existing Chats
     * screen being torn down and rebuilt: the user returns to the one they left, scroll position and
     * all, rather than to a second copy of it.
     */
    public static Intent[] stackFor(Context c, Intent... deeper) {
        Intent home = new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Intent[] stack = new Intent[deeper.length + 1];
        stack[0] = home;
        System.arraycopy(deeper, 0, stack, 1, deeper.length);
        return stack;
    }
}
