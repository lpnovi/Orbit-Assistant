package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Orbit Local 2.0 and the Google Play edition.
 *
 * <p>Orbit Local does not exist on Play, so none of its new abilities may be claimed there, Ask
 * Vault keeps the cloud providers' item count, and the local action model is never offered.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class PlayOrbitLocal2Test {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    @Test public void noLocalCapabilityIsClaimedOnPlay() {
        assertEquals("none", OrbitLocalCapabilities.summary(OrbitLocalCapabilities.evaluate(context)));
    }

    @Test public void askVaultKeepsTheCloudItemCountOnPlay() {
        Prefs.get(context).edit().putString(Prefs.PROVIDER, Prefs.PROVIDER_LOCAL).commit();
        assertFalse(SmartVault.localProviderActive(context));
        assertEquals(SmartVaultAsk.MAX_ITEMS, SmartVaultAsk.maxItems(context));
    }

    @Test public void theLocalActionModelIsNeverOfferedOnPlay() {
        assertFalse(OrbitLocalActionRouter.available(context));
        assertFalse(OrbitLocalActionRouter.shouldTry(context, "turn on dnd and dim the screen"));
    }
}
