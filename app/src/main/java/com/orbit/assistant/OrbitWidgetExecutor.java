package com.orbit.assistant;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lifecycle-safe orchestration that keeps direct widget actions off Orbit's UI path. */
final class OrbitWidgetExecutor {
    interface Completion { void finish(); }
    interface FlashlightCompletion { void finish(DeviceActionExecutor.Result result); }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private OrbitWidgetExecutor() {}

    static void runRoutine(Context context, RoutineStore.Routine routine, Completion completion) {
        if (context == null || routine == null) {
            if (completion != null) completion.finish();
            return;
        }
        List<AssistantReply.Action> actions = RoutineStore.copyActions(routine.actions);
        for (AssistantReply.Action action : actions) {
            if (action != null && action.requiresConfirmation) {
                try { openRoutineUi(context, routine.id, 0); }
                catch (Exception e) {
                    Toast.makeText(context, "Orbit could not open the Routine runner.",
                            Toast.LENGTH_LONG).show();
                }
                finally { if (completion != null) completion.finish(); }
                return;
            }
        }

        EXECUTOR.execute(() -> {
            try {
                RoutineStore.markRun(context, routine.id);
                final int[] failedIndex = {-1};
                final DeviceActionExecutor.Result[] failedResult = {null};
                OrbitActionEngine.execute(context, actions, null, new OrbitActionEngine.Listener() {
                    @Override public void onStep(AssistantReply.Action action,
                                                 DeviceActionExecutor.Result result,
                                                 int index, int total) {
                        if (result != null && !result.success && failedIndex[0] < 0) {
                            failedIndex[0] = index;
                            failedResult[0] = result;
                        }
                    }

                    @Override public void onFinished(boolean completedAllSteps, int completedSteps,
                                                     int totalSteps) {
                        try {
                            DeviceActionExecutor.Result failure = failedResult[0];
                            if (failure != null && DeviceActionExecutor.STATUS_PERMISSION.equals(failure.status)) {
                                try {
                                    openRoutineUi(context, routine.id, Math.max(0, failedIndex[0]));
                                } catch (Exception e) {
                                    MAIN.post(() -> Toast.makeText(context,
                                            "Orbit could not open the Routine permission flow.",
                                            Toast.LENGTH_LONG).show());
                                }
                            } else if (failure != null) {
                                MAIN.post(() -> Toast.makeText(context, failure.message,
                                        Toast.LENGTH_LONG).show());
                            }
                        } finally {
                            if (completion != null) completion.finish();
                        }
                    }
                });
            } catch (Exception e) {
                MAIN.post(() -> Toast.makeText(context,
                        "Orbit could not run this Routine.", Toast.LENGTH_LONG).show());
                if (completion != null) completion.finish();
            }
        });
    }

    static boolean hasCameraPermission(Context context) {
        return context != null && context.checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    static void toggleFlashlight(Context context, FlashlightCompletion completion) {
        if (context == null || !hasCameraPermission(context)) {
            finishFlashlight(completion,
                    DeviceActionExecutor.Result.permission("Camera access is needed for flashlight control."));
            return;
        }
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            finishFlashlight(completion,
                    DeviceActionExecutor.Result.unavailable("Flashlight controls are unavailable on this device."));
            return;
        }

        try {
            String targetId = null;
            for (String id : manager.getCameraIdList()) {
                Boolean available = manager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(available)) {
                    targetId = id;
                    break;
                }
            }
            if (targetId == null) {
                finishFlashlight(completion,
                        DeviceActionExecutor.Result.unavailable("No flashlight found"));
                return;
            }
            final String flashlightId = targetId;
            final boolean[] completed = {false};
            final CameraManager.TorchCallback[] callbackHolder = new CameraManager.TorchCallback[1];
            Runnable timeout = () -> {
                if (completed[0]) return;
                completed[0] = true;
                try { manager.unregisterTorchCallback(callbackHolder[0]); }
                catch (Exception ignored) {}
                finishFlashlight(completion,
                        DeviceActionExecutor.Result.failed("Orbit could not read the flashlight state."));
            };
            CameraManager.TorchCallback callback = new CameraManager.TorchCallback() {
                @Override public void onTorchModeChanged(String cameraId, boolean enabled) {
                    if (completed[0] || !flashlightId.equals(cameraId)) return;
                    completed[0] = true;
                    MAIN.removeCallbacks(timeout);
                    try { manager.unregisterTorchCallback(this); }
                    catch (Exception ignored) {}
                    JSONObject params = new JSONObject();
                    try { params.put("on", !enabled); }
                    catch (Exception ignored) {}
                    AssistantReply.Action action = new AssistantReply.Action(
                            RoutineActionCatalog.FLASHLIGHT, params, false);
                    finishFlashlight(completion,
                            DeviceActionExecutor.executeDetailed(context, action));
                }
            };
            callbackHolder[0] = callback;
            manager.registerTorchCallback(callback, MAIN);
            MAIN.postDelayed(timeout, 1500L);
        } catch (Exception e) {
            finishFlashlight(completion,
                    DeviceActionExecutor.Result.unavailable("Flashlight controls are unavailable on this device."));
        }
    }

    private static void openRoutineUi(Context context, String routineId, int startIndex) {
        Intent intent = new Intent(context, RoutinesActivity.class)
                .putExtra(RoutinesActivity.EXTRA_AUTORUN_ROUTINE_ID, routineId)
                .putExtra(RoutinesActivity.EXTRA_AUTORUN_START_INDEX, startIndex)
                .putExtra(RoutinesActivity.EXTRA_AUTORUN_SCHEDULED, false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(intent);
    }

    private static void finishFlashlight(FlashlightCompletion completion,
                                         DeviceActionExecutor.Result result) {
        if (completion != null) MAIN.post(() -> completion.finish(result));
    }
}
