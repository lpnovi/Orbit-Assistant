package com.orbit.assistant;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/** Executes automatic routines without violating Android's background-activity rules. */
public final class RoutineTriggerExecution {
    private RoutineTriggerExecution() {}

    public static void execute(Context c, RoutineStore.Routine routine, RoutineTriggerStore.Trigger trigger) {
        execute(c, routine, trigger, null);
    }

    public static void execute(Context c, RoutineStore.Routine routine,
                               RoutineTriggerStore.Trigger trigger, Runnable completion) {
        if (c == null || routine == null || trigger == null || routine.actions.isEmpty()) {
            finish(completion);
            return;
        }
        final long firedAt = System.currentTimeMillis();
        final boolean visible = UiPresence.isVisible();
        RoutineStore.markRun(c, routine.id);

        // Do not partially change the device if this routine will inevitably need
        // a user handoff but Android cannot currently show Orbit's trigger alert.
        if (!visible && containsForegroundOrConfirmationStep(c, routine) &&
                !RoutineTriggerNotifier.notificationsAllowed(c)) {
            updateStatus(c, trigger.id, firedAt, "Skipped · trigger alerts unavailable");
            finish(completion);
            return;
        }

        // Preflight the special access required by background-safe settings. This
        // prevents an automatic routine from applying earlier steps and only then
        // discovering a known permission problem halfway through the chain.
        int missingAccessIndex = firstKnownMissingAccess(c, routine, visible);
        if (missingAccessIndex >= 0) {
            AssistantReply.Action blocked = routine.actions.get(missingAccessIndex);
            String actionName = RoutineActionCatalog.title(blocked);
            String reason = "Grant access for " + actionName + " before this routine can run automatically.";
            updateStatus(c, trigger.id, firedAt, "Needs access · " + actionName);
            RoutineTriggerNotifier.notifyFailure(c, routine, trigger.id, missingAccessIndex, reason);
            finish(completion);
            return;
        }

        List<AssistantReply.Action> allowed = new ArrayList<>();
        int blockedIndex = -1;
        int conditionSkippedSteps = 0;
        for (int i = 0; i < routine.actions.size(); i++) {
            AssistantReply.Action action = RoutineActionCatalog.copy(routine.actions.get(i));
            if (conditionSkippedSteps > 0) {
                // Keep condition-gated steps in the contiguous prefix so OrbitActionEngine
                // can report them as skipped, but do not let a currently-false branch
                // force an unnecessary foreground handoff.
                allowed.add(action);
                conditionSkippedSteps--;
                continue;
            }
            if (RoutineConditionEvaluator.isCondition(action)) {
                RoutineConditionEvaluator.Result condition = RoutineConditionEvaluator.evaluate(c, action);
                if (condition.evaluable && action.params != null) {
                    try {
                        // Lock the branch decision for this automatic run so a time/location
                        // boundary cannot flip between the background-safety scan and execution.
                        action.params.put("_orbitLockedMatch", condition.matched);
                        action.params.put("_orbitLockedMessage", condition.message);
                    } catch (Exception ignored) {}
                }
                allowed.add(action);
                if (condition.evaluable && !condition.matched) {
                    conditionSkippedSteps = RoutineConditionEvaluator.gatedSteps(action);
                }
                continue;
            }
            // A saved action that ever requires confirmation must never bypass that
            // confirmation just because it was reached by an automatic trigger.
            if (action != null && action.requiresConfirmation) {
                blockedIndex = i;
                break;
            }
            if (!visible && requiresForeground(c, action)) {
                blockedIndex = i;
                break;
            }
            allowed.add(action);
        }
        final int firstBlocked = blockedIndex;

        if (allowed.isEmpty() && firstBlocked >= 0) {
            AssistantReply.Action blocked = routine.actions.get(firstBlocked);
            String actionName = RoutineActionCatalog.title(blocked);
            String state = blocked != null && blocked.requiresConfirmation
                    ? "Needs attention · " + actionName + " requires confirmation"
                    : "Needs attention · " + actionName + " needs Orbit visible";
            updateStatus(c, trigger.id, firedAt, state);
            RoutineTriggerNotifier.notifyNeedsContinuation(c, routine, trigger.id, firstBlocked,
                    "Tap to continue at step " + (firstBlocked + 1) + ": " + actionName + ".");
            finish(completion);
            return;
        }

        try {
            OrbitActionEngine.execute(c, allowed, null, new OrbitActionEngine.Listener() {
            private String lastFailure = "";
            private int lastFailureIndex = 0;

            @Override public void onStep(AssistantReply.Action action, DeviceActionExecutor.Result result, int index, int total) {
                if (result != null && !result.success) {
                    lastFailure = result.message;
                    lastFailureIndex = Math.max(0, index);
                }
            }

            @Override public void onFinished(boolean completedAllSteps, int completedSteps, int totalSteps) {
                try {
                    if (!completedAllSteps) {
                        String reason = lastFailure == null || lastFailure.trim().isEmpty()
                                ? "Stopped at step " + completedSteps
                                : lastFailure.trim();
                        updateStatus(c, trigger.id, firedAt, "Stopped · " + reason);
                        RoutineTriggerNotifier.notifyFailure(c, routine, trigger.id, lastFailureIndex, reason);
                        return;
                    }
                    if (firstBlocked >= 0) {
                        String actionName = RoutineActionCatalog.title(routine.actions.get(firstBlocked));
                        updateStatus(c, trigger.id, firedAt, "Partially ran · waiting for " + actionName);
                        RoutineTriggerNotifier.notifyNeedsContinuation(c, routine, trigger.id, firstBlocked,
                                "Orbit completed the background-safe steps. Tap to continue at step " +
                                        (firstBlocked + 1) + ": " + actionName + ".");
                    } else {
                        updateStatus(c, trigger.id, firedAt, "Completed automatically");
                    }
                } finally {
                    finish(completion);
                }
            }
            });
        } catch (Exception ignored) {
            updateStatus(c, trigger.id, firedAt, "Stopped · Orbit could not run this Routine");
            finish(completion);
        }
    }


    private static boolean containsForegroundOrConfirmationStep(Context c, RoutineStore.Routine routine) {
        if (routine == null || routine.actions == null) return false;
        int skipped = 0;
        for (AssistantReply.Action action : routine.actions) {
            if (skipped > 0) { skipped--; continue; }
            if (RoutineConditionEvaluator.isCondition(action)) {
                RoutineConditionEvaluator.Result condition = RoutineConditionEvaluator.evaluate(c, action);
                if (condition.evaluable && !condition.matched) skipped = RoutineConditionEvaluator.gatedSteps(action);
                continue;
            }
            if (action != null && action.requiresConfirmation) return true;
            if (requiresForeground(c, action)) return true;
        }
        return false;
    }

    private static int firstKnownMissingAccess(Context c, RoutineStore.Routine routine, boolean visible) {
        if (c == null || routine == null || routine.actions == null) return -1;
        int skipped = 0;
        for (int i = 0; i < routine.actions.size(); i++) {
            AssistantReply.Action action = routine.actions.get(i);
            if (skipped > 0) { skipped--; continue; }
            if (action == null || action.type == null) continue;
            if (RoutineConditionEvaluator.isCondition(action)) {
                if (RoutineConditionEvaluator.needsLocation(action)) {
                    if (!RoutineLocationTriggerScheduler.hasFineLocation(c)) return i;
                    if (!visible && !RoutineLocationTriggerScheduler.hasBackgroundLocation(c)) return i;
                }
                RoutineConditionEvaluator.Result condition = RoutineConditionEvaluator.evaluate(c, action);
                if (condition.evaluable && !condition.matched) skipped = RoutineConditionEvaluator.gatedSteps(action);
                continue;
            }
            if (RoutineActionCatalog.SET_BRIGHTNESS.equals(action.type) &&
                    !OrbitPermissionHelper.canWriteSystemSettings(c)) return i;
            if (RoutineActionCatalog.SET_DND.equals(action.type) &&
                    !OrbitPermissionHelper.hasDndAccess(c)) return i;
        }
        return -1;
    }

    public static boolean requiresForeground(AssistantReply.Action action) {
        return requiresForeground(null, action);
    }

    public static boolean requiresForeground(Context context, AssistantReply.Action action) {
        if (action == null || action.type == null) return true;
        if (RoutineActionCatalog.EXTENSION_ACTION.equals(action.type)) {
            if (context == null || action.params == null) return false;
            OrbitExtensionStore.Installed installed = OrbitExtensionStore.find(context,
                    action.params.optString("extensionId", ""));
            OrbitExtension.Action extensionAction = installed == null || !installed.enabled
                    ? null : installed.extension.findAction(action.params.optString("actionId", ""));
            // Missing/disabled references should execute in place and report the
            // normal unavailable result, not wake the user with a continuation UI.
            return extensionAction != null &&
                    !OrbitExtension.TYPE_HTTPS_REQUEST.equals(extensionAction.type);
        }
        switch (action.type) {
            case RoutineActionCatalog.IF_CONDITION:
            case RoutineActionCatalog.SET_BRIGHTNESS:
            case RoutineActionCatalog.SET_DND:
            case RoutineActionCatalog.SET_VOLUME:
                return false;
            case RoutineActionCatalog.FLASHLIGHT:
                // CameraManager torch state is tied to the app that owns it and can
                // disappear when that app process is closed. Do not promise a durable
                // scheduled flashlight state from a short-lived background receiver.
                return true;
            default:
                // App launches, Android panels, timers, and alarms use activity intents.
                // Modern Android restricts those from arbitrary background launches.
                return true;
        }
    }

    private static void updateStatus(Context c, String triggerId, long firedAt, String result) {
        RoutineTriggerStore.Trigger latest = RoutineTriggerStore.findById(c, triggerId);
        if (latest == null) return;
        RoutineTriggerStore.updateRunState(c, latest.id, firedAt, latest.nextRunAt, result, latest.enabled);
    }

    private static void finish(Runnable completion) {
        if (completion != null) completion.run();
    }
}
