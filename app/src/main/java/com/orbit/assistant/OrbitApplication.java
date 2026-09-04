package com.orbit.assistant;

import android.app.Application;

/**
 * Orbit's process start-up, kept deliberately almost empty.
 *
 * <p>Its whole reason to exist is that Android will not list a notification category in Settings
 * until the app has registered it, and Orbit registered each one on the way to posting that kind of
 * notification. A user therefore saw a different list of categories depending on which Orbit
 * features had happened to fire on that device, which is why one Samsung device showed "Orbit
 * updates" and another did not.
 *
 * <p>Registration is metadata only: it declares what Orbit can send, posts nothing, asks for no
 * permission, and touches no network. Every other subsystem still starts when something actually
 * needs it — this is not a place to move initialization to for its own sake, and anything added
 * here runs before every Activity, Service, Worker and widget update in the process.
 */
public final class OrbitApplication extends Application {

    @Override public void onCreate() {
        super.onCreate();
        OrbitNotificationChannels.ensureAll(this);
    }
}
