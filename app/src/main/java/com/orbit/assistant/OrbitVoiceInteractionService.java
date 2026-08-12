package com.orbit.assistant;

import android.os.Bundle;
import android.service.voice.VoiceInteractionService;
import android.service.voice.VoiceInteractionSession;

public class OrbitVoiceInteractionService extends VoiceInteractionService {
    @Override
    public void onReady() {
        super.onReady();
        try { setDisabledShowContext(0); } catch (Exception ignored) {}
    }

    @Override
    public void onLaunchVoiceAssistFromKeyguard() {
        showSession(new Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST | VoiceInteractionSession.SHOW_WITH_SCREENSHOT);
    }
}
