package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The device assessment must stay evidence-based: it only reasons from facts Android exposes,
 * and its tiers follow those facts deterministically.
 */
public final class DeviceCapabilityCheckTest {
    private static final long GB = 1024L * 1024 * 1024;
    private static final String[] ARM64 = {"arm64-v8a", "armeabi-v7a"};
    private static final long ROOMY_STORAGE = 64L * GB;

    @Test public void a32BitOnlyDeviceIsUnsupported() {
        DeviceCapabilityCheck.Assessment a = DeviceCapabilityCheck.assess(
                new String[]{"armeabi-v7a"}, 35, 8 * GB, ROOMY_STORAGE, false);
        assertEquals(DeviceCapabilityCheck.Tier.UNSUPPORTED, a.tier);
        assertFalse(DeviceCapabilityCheck.allowsLocalAi(a));
    }

    @Test public void veryOldAndroidIsNotRecommendedButStillAllowed() {
        DeviceCapabilityCheck.Assessment a = DeviceCapabilityCheck.assess(
                ARM64, 29, 8 * GB, ROOMY_STORAGE, false);
        assertEquals(DeviceCapabilityCheck.Tier.NOT_RECOMMENDED, a.tier);
        assertTrue(DeviceCapabilityCheck.allowsLocalAi(a));
    }

    @Test public void tooLittleMemoryIsNotRecommended() {
        DeviceCapabilityCheck.Assessment a = DeviceCapabilityCheck.assess(
                ARM64, 35, 3 * GB, ROOMY_STORAGE, false);
        assertEquals(DeviceCapabilityCheck.Tier.NOT_RECOMMENDED, a.tier);
    }

    @Test public void tightStorageIsLimitedUntilTheModelIsInstalled() {
        long tight = LocalModelStore.MODEL_SIZE_BYTES / 2;
        DeviceCapabilityCheck.Assessment before = DeviceCapabilityCheck.assess(
                ARM64, 35, 12 * GB, tight, false);
        assertEquals(DeviceCapabilityCheck.Tier.LIMITED, before.tier);
        // Once installed, the same free space is no longer a download blocker.
        DeviceCapabilityCheck.Assessment after = DeviceCapabilityCheck.assess(
                ARM64, 35, 12 * GB, tight, true);
        assertEquals(DeviceCapabilityCheck.Tier.EXCELLENT, after.tier);
    }

    @Test public void aModernFlagshipIsExcellent() {
        DeviceCapabilityCheck.Assessment a = DeviceCapabilityCheck.assess(
                ARM64, 35, 12 * GB, ROOMY_STORAGE, false);
        assertEquals(DeviceCapabilityCheck.Tier.EXCELLENT, a.tier);
        assertEquals("Excellent", a.tierLabel());
    }

    @Test public void anOrdinaryModernPhoneIsSupported() {
        DeviceCapabilityCheck.Assessment a = DeviceCapabilityCheck.assess(
                ARM64, 33, 8 * GB, ROOMY_STORAGE, false);
        assertEquals(DeviceCapabilityCheck.Tier.SUPPORTED, a.tier);
    }

    @Test public void unknownMemoryDoesNotPretendToKnow() {
        // Zero total memory means the fact was unavailable; the device is not condemned for it.
        DeviceCapabilityCheck.Assessment a = DeviceCapabilityCheck.assess(
                ARM64, 35, 0, ROOMY_STORAGE, false);
        assertEquals(DeviceCapabilityCheck.Tier.SUPPORTED, a.tier);
    }
}
