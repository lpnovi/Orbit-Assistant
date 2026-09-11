package com.orbit.assistant;

import android.content.Context;

import java.util.Collections;
import java.util.List;

/**
 * The one place Orbit decides whether this device has Orbit Pro.
 *
 * <p>Free Orbit is the real, complete assistant. Orbit Pro adds advanced customization and
 * power-user extras on top of it, and nothing that was free before ever moves behind it. This
 * class is the boundary that makes that arrangement maintainable: every premium feature Orbit ever
 * ships asks {@link #hasPro(Context)} and nothing else.
 *
 * <p>A Pro feature must never know <em>why</em> it is unlocked. Not Google Play, not a purchase
 * token, not a checkout state, not a developer preference, not a supporter mechanism that does not
 * exist yet. It asks one question, it gets a boolean, and when billing finally arrives it arrives
 * here as an {@link OrbitProEntitlementProvider} without a single feature changing.
 *
 * <h2>How an answer is reached</h2>
 *
 * <p>There are exactly two paths, and the order between them is the important part.
 *
 * <ol>
 *   <li><b>The developer preview override</b>, and only on a build eligible for it: a debug build
 *       or an Orbit Beta. It is an override in both directions. Selecting Free forces Free even on
 *       a device that legitimately owns Pro, because testing the locked experience is exactly what
 *       this exists for and a developer who bought Pro would otherwise be unable to see what
 *       everybody else sees. Selecting Pro Preview forces Pro.
 *   <li><b>The registered providers</b>, otherwise. Any one of them entitling the device entitles
 *       it. There are none today, so a Stable build resolves to Free, which is the truth: Orbit
 *       has no checkout, no billing library, and no way to buy anything.
 * </ol>
 *
 * <h2>Stable safety</h2>
 *
 * <p>A Stable build must never honor the preview override, and this is a hard rule rather than a
 * convention. Someone installs a Beta, selects Pro Preview, and then installs Stable over it; the
 * preference survives the upgrade, because Android preferences do. The Stable build ignores it
 * completely and resolves through providers alone. The value stays stored, so a later Beta finds
 * the tester's choice where they left it, and it buys that tester nothing on Stable.
 *
 * <p>That rule protects the future Google Play line as much as it protects today's, which is why
 * eligibility is a property of the running build rather than something a preference can assert.
 *
 * <h2>Not a DRM system</h2>
 *
 * <p>Orbit is MPL-2.0 open source and this class is in the open source. Somebody determined enough
 * to edit it can edit it, and no amount of obfuscation, fingerprinting or server round-tripping
 * would change that for a project that ships its own source. This is an honest boundary for honest
 * builds, not an attempt to fight the person holding the phone.
 */
public final class OrbitProEntitlement {

    private OrbitProEntitlement() {}

    /**
     * Every legitimate entitlement provider, in the order they are consulted.
     *
     * <p>Empty, and deliberately so. Orbit has no commercial entitlement mechanism yet, and a
     * placeholder that pretended otherwise would be the one thing capable of making a Stable build
     * report Pro for no reason. Google Play Billing joins this list when it is real.
     */
    private static final List<OrbitProEntitlementProvider> PROVIDERS = Collections.emptyList();

    // ---- the public contract ----------------------------------------------------------------------

    /**
     * Whether Orbit Pro is available on this device right now.
     *
     * <p>The entire question a premium feature is allowed to ask. Reads current state on every
     * call rather than caching, so changing the preview selection takes effect immediately and no
     * screen has to be told to invalidate anything.
     */
    public static boolean hasPro(Context context) {
        return resolve(previewAvailable(), Prefs.proPreviewSelected(context), PROVIDERS, context);
    }

    /**
     * The effective entitlement written for a person, for Diagnostics.
     *
     * <p>Says where the answer came from, because on a tester's device that is the only thing that
     * makes the answer checkable. Never names a token, an order, or an account.
     */
    public static String status(Context context) {
        if (previewAvailable()) {
            return Prefs.proPreviewSelected(context) ? "Pro Preview" : "Free (preview override)";
        }
        OrbitProEntitlementProvider granting = grantingProvider(PROVIDERS, context);
        return granting == null ? "Free" : "Pro (" + granting.name() + ")";
    }

    /**
     * Whether this build may offer the developer Free / Pro Preview override at all.
     *
     * <p>Centralized here so that no premium feature, and no screen, ever re-derives it. A second
     * copy of this condition somewhere else is how a Stable build eventually starts honoring a
     * preference it must ignore.
     */
    public static boolean previewAvailable() {
        return previewAvailable(BuildConfig.DEBUG, BuildConfig.VERSION_NAME);
    }

    // ---- the decisions, separated from the build they run in ---------------------------------------

    /**
     * The eligibility rule itself, with the build it is asking about passed in.
     *
     * <p>Split out so it can be tested against a Stable version name, which is impossible to
     * reach through {@link BuildConfig} from a debug unit test. The rule is one line and stays
     * one line: a debug build, or an Orbit Beta as {@link OrbitVersion} defines one. A malformed
     * version is not a Beta, so it is not eligible either.
     */
    static boolean previewAvailable(boolean debugBuild, String versionName) {
        return debugBuild || OrbitVersion.isBeta(versionName);
    }

    /**
     * The resolution rule, with everything it depends on passed in.
     *
     * <p>The override comes first and is absolute. That is the part worth stating plainly: when
     * the preview is allowed, the providers are not consulted at all, so a developer device that
     * owns Pro through a store can still be put into the locked state on purpose. When the preview
     * is not allowed, the stored selection is not read at all, which is what keeps a Beta's saved
     * Pro Preview from unlocking a Stable build.
     */
    static boolean resolve(boolean previewAllowed, boolean previewSelectsPro,
                           List<OrbitProEntitlementProvider> providers, Context context) {
        if (previewAllowed) return previewSelectsPro;
        return grantingProvider(providers, context) != null;
    }

    /** The first provider that entitles this device, or null when none does. */
    static OrbitProEntitlementProvider grantingProvider(
            List<OrbitProEntitlementProvider> providers, Context context) {
        if (providers == null) return null;
        for (OrbitProEntitlementProvider provider : providers) {
            if (provider != null && provider.hasPro(context)) return provider;
        }
        return null;
    }

    /** The registered providers, for tests that assert the shipped list is what it claims to be. */
    static List<OrbitProEntitlementProvider> providers() {
        return PROVIDERS;
    }
}
