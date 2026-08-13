package com.orbit.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONObject;

/** Non-exported, fixed-action bridge for widget operations that need Activity permission UI. */
public final class OrbitWidgetActionActivity extends Activity {
    static final String ACTION_TOGGLE_FLASHLIGHT = "com.orbit.assistant.widget.TOGGLE_FLASHLIGHT";
    private static final int REQUEST_CAMERA = 6901;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private CameraManager cameraManager;
    private CameraManager.TorchCallback torchCallback;
    private boolean finishedAction;

    static Intent intent(Context context, String action) {
        return new Intent(context, OrbitWidgetActionActivity.class).setAction(action);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!ACTION_TOGGLE_FLASHLIGHT.equals(getIntent() == null ? null : getIntent().getAction())) {
            finish();
            return;
        }
        startFlashlightToggle();
    }

    private void startFlashlightToggle() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
            return;
        }
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) {
            fail("Flashlight controls are unavailable on this device.");
            return;
        }
        torchCallback = new CameraManager.TorchCallback() {
            @Override public void onTorchModeChanged(String cameraId, boolean enabled) {
                if (finishedAction) return;
                finishedAction = true;
                unregister();
                JSONObject params = new JSONObject();
                try { params.put("on", !enabled); }
                catch (Exception ignored) {}
                AssistantReply.Action action = new AssistantReply.Action(
                        RoutineActionCatalog.FLASHLIGHT, params, false);
                DeviceActionExecutor.Result result = DeviceActionExecutor.executeDetailed(
                        OrbitWidgetActionActivity.this, action);
                Toast.makeText(OrbitWidgetActionActivity.this,
                        result == null ? "Flashlight action did not finish." : result.message,
                        Toast.LENGTH_SHORT).show();
                finish();
            }
        };
        try {
            cameraManager.registerTorchCallback(torchCallback, handler);
            handler.postDelayed(() -> {
                if (!finishedAction) fail("Orbit could not read the flashlight state.");
            }, 1500L);
        } catch (Exception e) {
            fail("Flashlight controls are unavailable on this device.");
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                     int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CAMERA) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startFlashlightToggle();
        } else {
            fail("Camera access is needed for Orbit to control the flashlight.");
        }
    }

    private void fail(String message) {
        if (finishedAction) return;
        finishedAction = true;
        unregister();
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }

    private void unregister() {
        handler.removeCallbacksAndMessages(null);
        if (cameraManager != null && torchCallback != null) {
            try { cameraManager.unregisterTorchCallback(torchCallback); }
            catch (Exception ignored) {}
        }
    }

    @Override protected void onDestroy() {
        unregister();
        super.onDestroy();
    }
}
