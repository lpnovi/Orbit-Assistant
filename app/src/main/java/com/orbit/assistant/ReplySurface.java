package com.orbit.assistant;

import android.content.Context;
import android.provider.Telephony;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * What "use this reply" is allowed to mean, decided by the app the user is actually in.
 *
 * <h2>The bug this exists to end</h2>
 *
 * <p>A device test drafted an email reply in Gmail. The reply card offered <em>Use in chat</em>, and
 * pressing it opened Google Messages with an SMS to that person. The draft was correct; the
 * destination was invented.
 *
 * <p>The cause was a category error rather than a coding mistake. Orbit's only "use this reply" path
 * was {@code openReplyComposer}, which is a genuinely useful <em>SMS/RCS</em> helper: it reads a
 * contact name off the screen, resolves it to a phone number, and opens {@code smsto:}. Every step
 * is right on a Messages screen and wrong everywhere else, and nothing above it ever asked which
 * screen the user was on. A person's name is not a communication medium: the same name can be an
 * email sender, a Discord handle, and a saved contact, and only the foreground app says which one is
 * being replied to.
 *
 * <p>So the medium is decided here, from the foreground package Orbit already has, and never
 * inferred from screen text or from a matched contact.
 *
 * <h2>Why only SMS can insert</h2>
 *
 * <p>Android has no public API for putting text into another app's text field. {@code smsto:} works
 * because the platform defines a standard SMS composer intent with a body extra; there is no
 * equivalent for Gmail, Discord, WhatsApp or anything else. {@code mailto:} exists but opens a
 * <em>new</em> message, which is not a reply to the thread on screen and must never be presented as
 * one. Injecting into an arbitrary field would require an AccessibilityService, which Orbit does not
 * have and will not add for this.
 *
 * <p>That is why {@link #canInsert(Kind)} is true for exactly one kind. Everywhere else Orbit
 * copies the draft, says so truthfully, and leaves the user where they were.
 */
public final class ReplySurface {
    private ReplySurface() {}

    /** What kind of conversation the user is replying to. */
    public enum Kind {
        /** A messaging app Android lets Orbit hand a prefilled SMS/RCS draft to. */
        SMS,
        /** An email client. Orbit can draft for it and cannot insert into it. */
        EMAIL,
        /** A recognised communication app with no supported insertion path. */
        OTHER_APP,
        /** No usable foreground package, or an app Orbit knows nothing about. */
        UNKNOWN
    }

    /**
     * Messaging apps beyond whatever Android reports as the default SMS handler.
     *
     * <p>The default handler is the authoritative answer and is asked first; this covers the case
     * where the user is reading in a messaging app that is not currently the default, which is
     * still an SMS/RCS surface.
     */
    private static final Set<String> SMS_PACKAGES = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(
                    "com.google.android.apps.messaging",
                    "com.samsung.android.messaging",
                    "com.android.mms",
                    "com.android.messaging")));

    private static final Set<String> EMAIL_PACKAGES = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(
                    "com.google.android.gm",
                    "com.samsung.android.email.provider",
                    "com.samsung.android.email.composer",
                    "com.microsoft.office.outlook",
                    "com.yahoo.mobile.client.android.mail",
                    "ch.protonmail.android",
                    "me.proton.android.mail",
                    "com.fastmail.app",
                    "com.fsck.k9",
                    "com.android.email",
                    "com.google.android.apps.inbox")));

    /**
     * Other conversation apps.
     *
     * <p>Listed only so Orbit can say something accurate about where the draft is going. None of
     * them changes what Orbit does, because none of them can be inserted into.
     */
    private static final Set<String> CHAT_PACKAGES = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(
                    "com.discord",
                    "com.whatsapp",
                    "com.whatsapp.w4b",
                    "com.facebook.orca",
                    "com.instagram.android",
                    "org.telegram.messenger",
                    "com.Slack",
                    "org.thoughtcrime.securesms",
                    "com.snapchat.android",
                    "com.microsoft.teams",
                    "com.reddit.frontpage",
                    "com.twitter.android",
                    "com.linkedin.android")));

    /** The medium the user is replying in, from the foreground package and nothing else. */
    public static Kind of(Context context, String foregroundPackage) {
        String pkg = foregroundPackage == null ? "" : foregroundPackage.trim().toLowerCase(Locale.US);
        if (pkg.isEmpty()) return Kind.UNKNOWN;
        if (pkg.equals(defaultSmsPackage(context))) return Kind.SMS;
        if (SMS_PACKAGES.contains(pkg)) return Kind.SMS;
        if (EMAIL_PACKAGES.contains(pkg)) return Kind.EMAIL;
        if (CHAT_PACKAGES.contains(pkg)) return Kind.OTHER_APP;
        return Kind.UNKNOWN;
    }

    /** Whatever this device says handles SMS, lower-cased, or "" when it will not say. */
    private static String defaultSmsPackage(Context context) {
        if (context == null) return "";
        try {
            String pkg = Telephony.Sms.getDefaultSmsPackage(context);
            return pkg == null ? "" : pkg.trim().toLowerCase(Locale.US);
        } catch (Throwable t) {
            // A device with no telephony at all. Not an SMS surface, and not an error.
            return "";
        }
    }

    /**
     * Whether Orbit has a supported way to put the draft into this surface's composer.
     *
     * <p>One kind, for the reason in the class comment. This is the single check that decides
     * whether a "use it" control appears at all, so a surface Orbit cannot insert into can never
     * offer one.
     */
    public static boolean canInsert(Kind kind) {
        return kind == Kind.SMS;
    }

    /** The label for the control that inserts, where one exists. */
    public static String insertLabel(Kind kind) {
        return kind == Kind.SMS ? "Use in chat" : "";
    }

    /**
     * What Orbit says after copying, naming where the draft is going.
     *
     * <p>Truthful about the one thing that actually happened. It never claims insertion, and it
     * never names the person — only the app, which the user is looking at anyway.
     */
    public static String copiedMessage(Kind kind, String appLabel) {
        String app = appLabel == null ? "" : appLabel.trim();
        switch (kind) {
            case EMAIL:
                return app.isEmpty()
                        ? "Reply copied · paste it into your email"
                        : "Reply copied · paste it into " + app;
            case OTHER_APP:
            case SMS:
            case UNKNOWN:
            default:
                return app.isEmpty()
                        ? "Reply copied · paste it into the conversation"
                        : "Reply copied · paste it into " + app;
        }
    }

    /** The privacy-safe destination word for Diagnostics. Never a name, address or number. */
    public static String diagnosticsName(Kind kind, boolean inserted) {
        if (kind == Kind.SMS && inserted) return "sms";
        switch (kind) {
            case SMS: return "sms-copy-fallback";
            case EMAIL: return "email-copy";
            case OTHER_APP: return "app-copy";
            default: return "copy";
        }
    }
}
