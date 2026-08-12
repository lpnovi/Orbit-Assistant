package com.orbit.assistant;

import android.content.Context;

import java.util.List;

/**
 * Foundation for 0.6 chained actions.
 *
 * Orbit already produced arrays of actions, but each surface executed them in an ad hoc way.
 * This engine centralizes ordering, confirmation, per-step results, and stop/continue behavior
 * so future routines, widgets, and Quick Settings tiles can share the same execution path.
 */
public final class OrbitActionEngine {
    public interface ConfirmationHandler {
        void request(AssistantReply.Action action, Runnable onAllow, Runnable onCancel);
    }

    public interface Listener {
        void onStep(AssistantReply.Action action, DeviceActionExecutor.Result result, int index, int total);
        void onFinished(boolean completedAllSteps, int completedSteps, int totalSteps);
    }

    private OrbitActionEngine() {}

    public static void execute(Context context, List<AssistantReply.Action> actions,
                               ConfirmationHandler confirmationHandler, Listener listener) {
        if (actions == null || actions.isEmpty()) {
            if (listener != null) listener.onFinished(true, 0, 0);
            return;
        }
        runStep(context, actions, 0, confirmationHandler, listener);
    }

    private static void runStep(Context context, List<AssistantReply.Action> actions, int index,
                                ConfirmationHandler confirmationHandler, Listener listener) {
        if (actions == null) {
            if (listener != null) listener.onFinished(true, 0, 0);
            return;
        }
        if (index >= actions.size()) {
            if (listener != null) listener.onFinished(true, actions.size(), actions.size());
            return;
        }

        AssistantReply.Action action = actions.get(index);
        if (action == null) {
            DeviceActionExecutor.Result skipped = DeviceActionExecutor.Result.failed("Empty action step").withContinuation(true);
            if (listener != null) listener.onStep(null, skipped, index, actions.size());
            runStep(context, actions, index + 1, confirmationHandler, listener);
            return;
        }

        Runnable executeNow = () -> {
            if (RoutineConditionEvaluator.isCondition(action)) {
                RoutineConditionEvaluator.Result condition = RoutineConditionEvaluator.evaluate(context, action);
                if (!condition.evaluable) {
                    DeviceActionExecutor.Result failed = condition.permissionRequired
                            ? DeviceActionExecutor.Result.permission(condition.message)
                            : DeviceActionExecutor.Result.unavailable(condition.message);
                    if (listener != null) listener.onStep(action, failed, index, actions.size());
                    if (listener != null) listener.onFinished(false, index + 1, actions.size());
                    return;
                }
                int gated = RoutineConditionEvaluator.gatedSteps(action);
                if (condition.matched) {
                    DeviceActionExecutor.Result met = DeviceActionExecutor.Result.success("Condition matched");
                    if (listener != null) listener.onStep(action, met, index, actions.size());
                    runStep(context, actions, index + 1, confirmationHandler, listener);
                    return;
                }

                DeviceActionExecutor.Result notMet = DeviceActionExecutor.Result.success(
                        condition.message + " · skipped " + Math.min(gated, Math.max(0, actions.size() - index - 1)) +
                                (gated == 1 ? " step" : " steps"));
                if (listener != null) listener.onStep(action, notMet, index, actions.size());
                int skipEnd = Math.min(actions.size(), index + 1 + gated);
                if (listener != null) {
                    for (int skippedIndex = index + 1; skippedIndex < skipEnd; skippedIndex++) {
                        AssistantReply.Action skippedAction = actions.get(skippedIndex);
                        listener.onStep(skippedAction, DeviceActionExecutor.Result.success("Skipped · condition not met"),
                                skippedIndex, actions.size());
                    }
                }
                runStep(context, actions, skipEnd, confirmationHandler, listener);
                return;
            }

            DeviceActionExecutor.Result result = DeviceActionExecutor.executeDetailed(context, action);
            if (listener != null) listener.onStep(action, result, index, actions.size());
            if (result.shouldContinue) {
                runStep(context, actions, index + 1, confirmationHandler, listener);
            } else if (listener != null) {
                listener.onFinished(false, index + 1, actions.size());
            }
        };

        if (action.requiresConfirmation && confirmationHandler != null) {
            confirmationHandler.request(action, executeNow, () -> {
                DeviceActionExecutor.Result cancelled = DeviceActionExecutor.Result.cancelled("Cancelled");
                if (listener != null) listener.onStep(action, cancelled, index, actions.size());
                runStep(context, actions, index + 1, confirmationHandler, listener);
            });
        } else {
            executeNow.run();
        }
    }
}
