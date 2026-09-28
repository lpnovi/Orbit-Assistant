package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.robolectric.RuntimeEnvironment;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

/**
 * The Orbit Local capability list: every "works on this phone" comes from observed readiness.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class OrbitLocalCapabilitiesTest {

    private static OrbitLocalCapabilities.State everythingReady() {
        OrbitLocalCapabilities.State s = new OrbitLocalCapabilities.State();
        s.chatModelReady = true;
        s.actionModelReady = true;
        s.deviceActionsEnabled = true;
        s.memoryEnabled = true;
        s.screenContextEnabled = true;
        s.smartVaultEnabled = true;
        s.notificationAccess = true;
        s.notificationIntelligenceEnabled = true;
        return s;
    }

    private static OrbitLocalCapabilities.Availability of(
            List<OrbitLocalCapabilities.Capability> list, String name) {
        for (OrbitLocalCapabilities.Capability c : list) if (c.name.equals(name)) return c.availability;
        throw new AssertionError("missing " + name);
    }

    @Test public void whenEverythingIsReadyEveryLocalLineWorks() {
        List<OrbitLocalCapabilities.Capability> all =
                OrbitLocalCapabilities.evaluate(everythingReady());
        for (String name : new String[]{OrbitLocalCapabilities.CHAT, OrbitLocalCapabilities.MEMORY,
                OrbitLocalCapabilities.SCREEN, OrbitLocalCapabilities.ASK_VAULT,
                OrbitLocalCapabilities.ATTACHMENTS, OrbitLocalCapabilities.NOTIFICATIONS,
                OrbitLocalCapabilities.DEVICE_ACTIONS}) {
            assertEquals(name, OrbitLocalCapabilities.Availability.READY, of(all, name));
        }
    }

    @Test public void cloudOnlyThingsAreNeverAdvertisedAsLocal() {
        List<OrbitLocalCapabilities.Capability> all =
                OrbitLocalCapabilities.evaluate(everythingReady());
        for (String name : new String[]{OrbitLocalCapabilities.WEB, OrbitLocalCapabilities.PICTURES,
                OrbitLocalCapabilities.ROUTINES, OrbitLocalCapabilities.SUGGESTIONS}) {
            assertEquals(name, OrbitLocalCapabilities.Availability.CLOUD_ONLY, of(all, name));
        }
        assertFalse(OrbitLocalCapabilities.summary(all).contains("pictures"));
    }

    @Test public void withoutTheChatModelNothingConversationalIsReady() {
        OrbitLocalCapabilities.State s = everythingReady();
        s.chatModelReady = false;
        List<OrbitLocalCapabilities.Capability> all = OrbitLocalCapabilities.evaluate(s);
        assertEquals(OrbitLocalCapabilities.Availability.NEEDS_SETUP,
                of(all, OrbitLocalCapabilities.ASK_VAULT));
        assertEquals(OrbitLocalCapabilities.Availability.NEEDS_SETUP,
                of(all, OrbitLocalCapabilities.NOTIFICATIONS));
        assertEquals("the action model is its own download",
                OrbitLocalCapabilities.Availability.READY,
                of(all, OrbitLocalCapabilities.DEVICE_ACTIONS));
    }

    @Test public void eachLineFollowsItsOwnPrerequisite() {
        OrbitLocalCapabilities.State s = everythingReady();
        s.smartVaultEnabled = false;
        s.notificationAccess = false;
        s.actionModelReady = false;
        s.memoryEnabled = false;
        List<OrbitLocalCapabilities.Capability> all = OrbitLocalCapabilities.evaluate(s);
        assertEquals(OrbitLocalCapabilities.Availability.NEEDS_SETUP,
                of(all, OrbitLocalCapabilities.ASK_VAULT));
        assertEquals(OrbitLocalCapabilities.Availability.NEEDS_SETUP,
                of(all, OrbitLocalCapabilities.NOTIFICATIONS));
        assertEquals(OrbitLocalCapabilities.Availability.NEEDS_SETUP,
                of(all, OrbitLocalCapabilities.DEVICE_ACTIONS));
        assertEquals(OrbitLocalCapabilities.Availability.NEEDS_SETUP,
                of(all, OrbitLocalCapabilities.MEMORY));
        s.actionModelReady = true;
        s.deviceActionsEnabled = false;
        assertEquals(OrbitLocalCapabilities.Availability.NEEDS_SETUP,
                of(OrbitLocalCapabilities.evaluate(s), OrbitLocalCapabilities.DEVICE_ACTIONS));
    }

    @Test public void onAPhoneWithNoComponentNothingIsClaimed() {
        Context context = RuntimeEnvironment.getApplication();
        List<OrbitLocalCapabilities.Capability> all = OrbitLocalCapabilities.evaluate(context);
        assertEquals("none", OrbitLocalCapabilities.summary(all));
        assertTrue(all.size() >= 10);
    }

    @Test public void theScreenShowsTheCardOnlyForATrustedComponent() {
        String screen = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/LocalAiActivity.java");
        assertTrue(screen.contains(
                "if (OrbitLocalComponent.isUsable(this)) cards.addView(capabilityCard(), cardLp());"));
        assertTrue(screen.contains("OrbitLocalCapabilities.evaluate(this)"));
    }
}
