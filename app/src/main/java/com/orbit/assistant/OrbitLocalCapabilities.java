package com.orbit.assistant;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What Orbit Local can do on this phone right now, stated from real readiness rather than from the
 * existence of code.
 *
 * <p>The Orbit Local screen shows this as two short lists, "Works on this phone" and "Needs a cloud
 * provider", and Diagnostics prints its summary. Every "works" answer is derived from state Orbit
 * can observe - a verified component, a ready model, a switch the user has turned on, a permission
 * that is granted - so a capability is never advertised because it could work in principle.
 *
 * <p>{@link #evaluate(State)} is pure, so every combination is testable without a device; the
 * {@link #evaluate(Context)} overload only gathers the inputs.
 */
final class OrbitLocalCapabilities {

    /** How one capability stands. */
    enum Availability {
        /** Works now, entirely on this phone. */
        READY,
        /** Works on this phone once something local is installed, allowed, or switched on. */
        NEEDS_SETUP,
        /** Needs a cloud provider. Orbit Local does not do this, and does not pretend to. */
        CLOUD_ONLY
    }

    /** One line of the capability list. */
    static final class Capability {
        final String name;
        final Availability availability;
        /** What stands in the way, or a short qualifier. Empty when there is nothing to say. */
        final String detail;

        Capability(String name, Availability availability, String detail) {
            this.name = name;
            this.availability = availability;
            this.detail = detail == null ? "" : detail;
        }
    }

    /** Everything the answer depends on, observed once. */
    static final class State {
        boolean chatModelReady;
        boolean actionModelReady;
        boolean deviceActionsEnabled;
        boolean memoryEnabled;
        boolean screenContextEnabled;
        boolean smartVaultEnabled;
        boolean notificationAccess;
        boolean notificationIntelligenceEnabled;
    }

    static final String CHAT = "Chat";
    static final String MEMORY = "Orbit Memory";
    static final String SCREEN = "Screen text";
    static final String ASK_VAULT = "Ask Vault";
    static final String ATTACHMENTS = "Text, PDF and document attachments";
    static final String NOTIFICATIONS = "Notification questions";
    static final String DEVICE_ACTIONS = "Device actions in your own words";
    static final String WEB = "Web search and current information";
    static final String PICTURES = "Looking at pictures";
    static final String ROUTINES = "Planning Routines";
    static final String SUGGESTIONS = "Smart Vault suggestions";

    private OrbitLocalCapabilities() {}

    static List<Capability> evaluate(State s) {
        List<Capability> out = new ArrayList<>();
        String needsModel = "After the local model is installed";
        if (!s.chatModelReady) {
            out.add(new Capability(CHAT, Availability.NEEDS_SETUP, needsModel));
            out.add(new Capability(MEMORY, Availability.NEEDS_SETUP, needsModel));
            out.add(new Capability(SCREEN, Availability.NEEDS_SETUP, needsModel));
            out.add(new Capability(ASK_VAULT, Availability.NEEDS_SETUP, needsModel));
            out.add(new Capability(ATTACHMENTS, Availability.NEEDS_SETUP, needsModel));
            out.add(new Capability(NOTIFICATIONS, Availability.NEEDS_SETUP, needsModel));
        } else {
            out.add(new Capability(CHAT, Availability.READY, ""));
            out.add(s.memoryEnabled
                    ? new Capability(MEMORY, Availability.READY, "")
                    : new Capability(MEMORY, Availability.NEEDS_SETUP, "Orbit Memory is off"));
            out.add(new Capability(SCREEN, Availability.READY, s.screenContextEnabled
                    ? "" : "When you attach the screen"));
            out.add(s.smartVaultEnabled
                    ? new Capability(ASK_VAULT, Availability.READY, "")
                    : new Capability(ASK_VAULT, Availability.NEEDS_SETUP, "Turn on Smart Vault"));
            out.add(new Capability(ATTACHMENTS, Availability.READY, "The text Orbit reads from them"));
            if (!s.notificationAccess) {
                out.add(new Capability(NOTIFICATIONS, Availability.NEEDS_SETUP,
                        "Needs notification access"));
            } else if (!s.notificationIntelligenceEnabled) {
                out.add(new Capability(NOTIFICATIONS, Availability.NEEDS_SETUP,
                        "Notification Intelligence is off"));
            } else {
                out.add(new Capability(NOTIFICATIONS, Availability.READY, ""));
            }
        }
        // Independent of the chat model: the action model is its own download.
        if (!s.actionModelReady) {
            out.add(new Capability(DEVICE_ACTIONS, Availability.NEEDS_SETUP,
                    "Needs the action model"));
        } else if (!s.deviceActionsEnabled) {
            out.add(new Capability(DEVICE_ACTIONS, Availability.NEEDS_SETUP, "Turned off"));
        } else {
            out.add(new Capability(DEVICE_ACTIONS, Availability.READY, ""));
        }
        out.add(new Capability(WEB, Availability.CLOUD_ONLY, ""));
        out.add(new Capability(PICTURES, Availability.CLOUD_ONLY,
                "Text Orbit reads from a picture still works"));
        out.add(new Capability(ROUTINES, Availability.CLOUD_ONLY, ""));
        out.add(new Capability(SUGGESTIONS, Availability.CLOUD_ONLY, ""));
        return Collections.unmodifiableList(out);
    }

    static List<Capability> evaluate(Context c) {
        State s = new State();
        OrbitLocalStatus status = OrbitLocalComponent.isUsable(c)
                ? OrbitLocalProvider.cachedStatus(c) : null;
        s.chatModelReady = OrbitDistribution.supportsOrbitLocal()
                && status != null && status.modelReady();
        s.actionModelReady = OrbitDistribution.supportsOrbitLocal()
                && status != null && status.actionModelReady();
        s.deviceActionsEnabled = Prefs.localDeviceActions(c);
        s.memoryEnabled = Prefs.memoryEnabled(c);
        s.screenContextEnabled = Prefs.screenContext(c);
        s.smartVaultEnabled = Prefs.smartVaultEnabled(c);
        s.notificationAccess = NotificationAccess.enabled(c);
        s.notificationIntelligenceEnabled = Prefs.notificationAiEnabled(c);
        return evaluate(s);
    }

    /** The names that work locally now, comma separated, for Diagnostics. */
    static String summary(List<Capability> capabilities) {
        StringBuilder out = new StringBuilder();
        for (Capability capability : capabilities) {
            if (capability.availability != Availability.READY) continue;
            if (out.length() > 0) out.append(", ");
            out.append(capability.name);
        }
        return out.length() == 0 ? "none" : out.toString();
    }
}
