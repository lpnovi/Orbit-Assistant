package com.orbit.assistant;

import android.content.Context;

/**
 * The step between "the user approved this Calendar write" and "the write may run".
 *
 * <p>Orbit asks for Calendar permission at exactly this moment: after the user has confirmed a
 * specific batch, and never during onboarding or installation. The approved action is held here,
 * in this process, while Android's prompt is open, and continues only once the prompt has closed
 * and reported a real answer.
 *
 * <p>Note what this class does <em>not</em> do: it does not decide whether the write is allowed.
 * On a denial it still continues to the executor, because {@link CalendarActionExecutor} re-checks
 * permission itself and refuses. Keeping the refusal in one place means the guarantee "a denial
 * produces zero writes" holds for every route into the executor, not only this one.
 */
public final class CalendarActionGate {

    private CalendarActionGate() {}

    /**
     * Wraps an approved action so a Calendar write resolves permission first.
     *
     * <p>Every other action type continues immediately, so wrapping a surface's confirmation
     * handler with this changes nothing for timers, flashlights, or anything else.
     */
    public static void afterApproval(Context context, AssistantReply.Action action,
                                     Runnable continuation) {
        if (continuation == null) return;
        if (context == null || !CalendarActionExecutor.isCalendarWrite(action)) {
            continuation.run();
            return;
        }
        if (OrbitCalendarStore.hasAccess(context)) {
            continuation.run();
            return;
        }
        final Context app = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        String token = CalendarAccessBridge.register(granted -> continuation.run());
        if (!CalendarPermissionActivity.start(context, token)) {
            // Android could not open the prompt. Deliver a denial rather than leaving the approved
            // action waiting forever; the executor then reports that permission is required.
            CalendarAccessBridge.deliver(token, OrbitCalendarStore.hasAccess(app));
        }
    }
}
