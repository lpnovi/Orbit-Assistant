package com.orbit.assistant;

import android.content.Context;

/**
 * One legitimate way a device can be entitled to Orbit Pro.
 *
 * <p>This interface is the whole reason {@link OrbitProEntitlement} exists. A premium feature asks
 * one question and gets one answer; how that answer was arrived at is this layer's business and
 * nobody else's. Google Play Billing will arrive as an implementation of this interface, and when
 * it does, not one Pro feature will change, because none of them will ever have known that a store
 * was involved.
 *
 * <p>Three rules keep that boundary real:
 *
 * <ul>
 *   <li>A provider answers for the build it is running in. A provider that only makes sense on a
 *       prerelease must not be registered in a Stable build at all, rather than registered and
 *       then filtered downstream.
 *   <li>A provider answers quickly and without blocking. {@link #hasPro(Context)} is called from
 *       UI code, so an implementation reads state it already holds. Acquiring that state, such as
 *       talking to a billing service, is the implementation's own background work.
 *   <li>A provider never leaks how it decided. {@link #name()} is a short, human, public word for
 *       the entitlement's origin, shown in Diagnostics. It is never a purchase token, an order id,
 *       an account identifier, or anything else that would be unsafe to read out loud.
 * </ul>
 *
 * <p>There are deliberately no implementations yet. Orbit has no commercial entitlement mechanism,
 * so the honest resolution on a Stable build is Free, and an empty provider list says exactly that
 * without pretending otherwise.
 */
public interface OrbitProEntitlementProvider {

    /**
     * How this entitlement reads to a person, for example {@code Google Play}.
     *
     * <p>Shown in Diagnostics beside the effective state. Safe to display, safe to copy into a
     * support conversation, and never derived from anything private.
     */
    String name();

    /** Whether this provider currently entitles this device to Orbit Pro. */
    boolean hasPro(Context context);
}
