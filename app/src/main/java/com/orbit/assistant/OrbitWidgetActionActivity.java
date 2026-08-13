package com.orbit.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

/** Non-exported, fixed-action bridge for widget operations that need Activity permission UI. */
public final class OrbitWidgetActionActivity extends Activity {
    private static final String ACTION_REQUEST_FLASHLIGHT_PERMISSION =
            "com.orbit.assistant.widget.REQUEST_FLASHLIGHT_PERMISSION";
    private static final int REQUEST_CAMERA = 6901;

    static Intent permissionIntent(Context context) {
        return new Intent(context, OrbitWidgetActionActivity.class)
                .setAction(ACTION_REQUEST_FLASHLIGHT_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!ACTION_REQUEST_FLASHLIGHT_PERMISSION.equals(
                getIntent() == null ? null : getIntent().getAction())) {
            finish();
            return;
        }
        requestFlashlightPermission();
    }

    private void requestFlashlightPermission() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            toggleAfterPermission();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                     int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CAMERA) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            toggleAfterPermission();
        } else {
            Toast.makeText(this,
                    "Camera access is needed for Orbit to control the flashlight.",
                    Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void toggleAfterPermission() {
        OrbitWidgetExecutor.toggleFlashlight(this, result -> {
            Toast.makeText(this, result == null
                            ? "Flashlight action did not finish." : result.message,
                    Toast.LENGTH_SHORT).show();
            finish();
        });
    }
}
