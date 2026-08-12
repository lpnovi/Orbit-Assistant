package com.orbit.assistant;

import android.os.Bundle;
import android.service.voice.VoiceInteractionService;
import android.service.voice.VoiceInteractionSession;

public class OrbitVoiceInteractionService extends VoiceInteractionService {
    private static volatile OrbitVoiceInteractionService readyInstance;
    private boolean destroyed;

    @Override
    public void onCreate() {
        super.onCreate();
        destroyed = false;
    }

    @Override
    public void onReady() {
        super.onReady();
        if (!destroyed) readyInstance = this;
        try { setDisabledShowContext(0); } catch (Exception ignored) {}
    }

    @Override
    public void onShutdown() {
        destroyed = true;
        if (readyInstance == this) readyInstance = null;
        super.onShutdown();
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        if (readyInstance == this) readyInstance = null;
        super.onDestroy();
    }

    static boolean showOrbitSession(android.content.Context context) {
        android.content.ComponentName component = new android.content.ComponentName(
                context, OrbitVoiceInteractionService.class);
        if (!VoiceInteractionService.isActiveService(context, component)) return false;
        OrbitVoiceInteractionService service = readyInstance;
        if (service == null || service.destroyed) return false;
        try {
            service.showSession(new Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST |
                    VoiceInteractionSession.SHOW_WITH_SCREENSHOT);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public void onLaunchVoiceAssistFromKeyguard() {
        showSession(new Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST | VoiceInteractionSession.SHOW_WITH_SCREENSHOT);
    }
}
