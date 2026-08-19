package com.orbit.assistant;

import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

public class OrbitSessionService extends VoiceInteractionSessionService {
    /**
     * The earliest Orbit-owned point of a Side-button invocation, and therefore where the
     * persistent launch trace begins. Construction is instrumented but not handled: a throwable is
     * recorded and immediately rethrown, so Android sees exactly what it would have seen.
     */
    @Override
    public VoiceInteractionSession onNewSession(Bundle args) {
        OverlayLaunchTrace.begin(this, OverlayLaunchTrace.STAGE_NEW_SESSION);
        OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_NEW_SESSION);
        try {
            OrbitSession session = new OrbitSession(this);
            OverlayLaunchTrace.event(OverlayLaunchTrace.STAGE_SESSION_CONSTRUCTED);
            return session;
        } catch (Throwable t) {
            throw OverlayLaunchTrace.rethrow(OverlayLaunchTrace.STAGE_SESSION_CONSTRUCTED, t);
        }
    }
}
