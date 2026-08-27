package com.orbit.assistant;

import android.content.Context;

/**
 * The step between "the user approved this Calendar write" and "the write may run".
 *
 * <p>Orbit asks for Calendar permission only when a real Calendar action is in hand, never during
 * onboarding or installation. By the time an approved batch reaches this gate the surfaces have
 * normally resolved permission already, while preparing the confirmation; this remains as the
 * backstop for every other route into an approved Calendar action — a restored request, a widget,
 * a routine — so none of them can reach the executor without the prompt having been offered.
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
        CalendarTargetResolver.ensureAccess(context, continuation);
    }
}
