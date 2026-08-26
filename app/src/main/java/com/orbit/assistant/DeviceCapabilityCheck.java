package com.orbit.assistant;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;

/**
 * Honest, evidence-based assessment of whether this device can run Orbit Local.
 *
 * <p>Only facts Android actually exposes are used: CPU architecture, OS version, total memory,
 * and free storage. NPU/DSP details are not reliably visible to apps, so nothing here pretends
 * to know them; the tiers reflect what those observable facts genuinely predict for on-device
 * language-model inference.
 */
public final class DeviceCapabilityCheck {

    public enum Tier { EXCELLENT, SUPPORTED, LIMITED, NOT_RECOMMENDED, UNSUPPORTED }

    public static final class Assessment {
        public final Tier tier;
        /** One plain sentence explaining the tier to the user. */
        public final String summary;

        Assessment(Tier tier, String summary) {
            this.tier = tier;
            this.summary = summary;
        }

        public String tierLabel() {
            switch (tier) {
                case EXCELLENT: return "Excellent";
                case SUPPORTED: return "Supported";
                case LIMITED: return "Limited";
                case NOT_RECOMMENDED: return "Not recommended";
                default: return "Unsupported";
            }
        }
    }

    private DeviceCapabilityCheck() {}

    public static Assessment assess(Context c) {
        long totalMem = 0L;
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(info);
                totalMem = info.totalMem;
            }
        } catch (Exception ignored) {}
        // A model already present anywhere — the component's, or one an older Orbit downloaded and
        // has not migrated yet — means the storage for it has already been found once, so the
        // assessment must not warn about needing room it does not need.
        OrbitLocalStatus status = OrbitLocalProvider.cachedStatus(c);
        boolean modelPresent = (status != null && status.modelReady())
                || LocalModelStore.hasLegacyModel(c);
        return assess(Build.SUPPORTED_ABIS, Build.VERSION.SDK_INT, totalMem,
                LocalModelStore.freeStorageBytes(c), modelPresent);
    }

    /** Pure decision logic, separated so it can be tested without a device. */
    static Assessment assess(String[] abis, int sdkInt, long totalMemBytes, long freeStorageBytes,
                             boolean modelAlreadyInstalled) {
        boolean arm64 = false;
        if (abis != null) {
            for (String abi : abis) if ("arm64-v8a".equals(abi)) { arm64 = true; break; }
        }
        if (!arm64) {
            return new Assessment(Tier.UNSUPPORTED,
                    "This device's processor type does not support Orbit's on-device AI runtime.");
        }
        if (sdkInt < 31) {
            return new Assessment(Tier.NOT_RECOMMENDED,
                    "On-device AI works best on Android 12 or newer. This Android version may be slow or unstable for it.");
        }

        long needed = LocalModelStore.MODEL_SIZE_BYTES + LocalModelStore.STORAGE_MARGIN_BYTES;
        boolean storageKnown = freeStorageBytes >= 0;
        boolean storageTight = storageKnown && !modelAlreadyInstalled && freeStorageBytes < needed;

        final long GB = 1024L * 1024 * 1024;
        boolean memKnown = totalMemBytes > 0;
        if (memKnown && totalMemBytes < 4L * GB) {
            return new Assessment(Tier.NOT_RECOMMENDED,
                    "This device has too little memory to run the local model comfortably.");
        }
        if (storageTight) {
            return new Assessment(Tier.LIMITED,
                    "This device can run local AI, but it needs about "
                            + LocalModelStore.formatBytes(needed) + " of free storage first.");
        }
        if (memKnown && totalMemBytes < 6L * GB) {
            return new Assessment(Tier.LIMITED,
                    "This device can run the local model, but memory is tight and responses may be slow.");
        }
        if (memKnown && totalMemBytes >= 10L * GB) {
            return new Assessment(Tier.EXCELLENT,
                    "This device is well suited to on-device AI, with plenty of memory for the local model.");
        }
        return new Assessment(Tier.SUPPORTED,
                "This device meets the requirements for Orbit's on-device AI.");
    }

    /** True when the tier allows installing and running the local model at all. */
    public static boolean allowsLocalAi(Assessment a) {
        return a != null && a.tier != Tier.UNSUPPORTED;
    }
}
