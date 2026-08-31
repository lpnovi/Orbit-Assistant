package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Where a drafted reply is allowed to go, decided by the app rather than by the person.
 *
 * <h2>The device failure this file exists for</h2>
 *
 * <p>An email was open in Gmail. Orbit drafted the reply correctly. Pressing <em>Use in chat</em>
 * opened Google Messages and started an SMS to the sender. Nothing about the draft was wrong; the
 * destination was invented from a name.
 *
 * <p>The old path went: name visible on screen → look that name up in Contacts → open {@code smsto:}
 * with the number. Every step is right in Google Messages and wrong everywhere else, and the medium
 * was never consulted. So the tests below deliberately hold the <em>person</em> constant and vary
 * only the <em>foreground app</em>: a contact who is also an email sender must produce two entirely
 * different behaviours, and the only input that differs is the package.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class ReplyRoutingTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
    }

    private static final String GMAIL = "com.google.android.gm";
    private static final String MESSAGES = "com.google.android.apps.messaging";
    private static final String DISCORD = "com.discord";
    private static final String WHATSAPP = "com.whatsapp";

    // ---- the medium comes from the app ------------------------------------------------------------

    @Test public void gmailIsEmailAndMessagesIsSms() {
        assertEquals(ReplySurface.Kind.EMAIL, ReplySurface.of(context, GMAIL));
        assertEquals(ReplySurface.Kind.SMS, ReplySurface.of(context, MESSAGES));
    }

    @Test public void everyRecognisedEmailClientIsEmail() {
        for (String pkg : new String[]{
                "com.google.android.gm",
                "com.samsung.android.email.provider",
                "com.microsoft.office.outlook",
                "ch.protonmail.android",
                "com.fastmail.app"}) {
            assertEquals(pkg + " is an email client",
                    ReplySurface.Kind.EMAIL, ReplySurface.of(context, pkg));
        }
    }

    @Test public void otherConversationAppsAreNeitherSmsNorEmail() {
        for (String pkg : new String[]{DISCORD, WHATSAPP, "com.facebook.orca", "com.Slack",
                "org.telegram.messenger", "com.instagram.android"}) {
            ReplySurface.Kind kind = ReplySurface.of(context, pkg);
            assertNotEquals(pkg + " must never be treated as SMS", ReplySurface.Kind.SMS, kind);
            assertNotEquals(pkg + " must never be treated as email", ReplySurface.Kind.EMAIL, kind);
        }
    }

    @Test public void anUnknownOrAbsentPackageGuessesNothing() {
        for (String pkg : new String[]{"com.example.mystery", "", "   ", null}) {
            assertEquals(ReplySurface.Kind.UNKNOWN, ReplySurface.of(context, pkg));
        }
    }

    /**
     * The whole bug, as one assertion.
     *
     * <p>Identical everything except the foreground package. One may insert; the other may not.
     */
    @Test public void theSamePersonInTwoAppsGetsTwoDifferentBehaviours() {
        ReplySurface.Kind inMessages = ReplySurface.of(context, MESSAGES);
        ReplySurface.Kind inGmail = ReplySurface.of(context, GMAIL);

        assertTrue("a real SMS thread can take a prefilled draft",
                ReplySurface.canInsert(inMessages));
        assertFalse("the same person's email thread cannot, and must not pretend to",
                ReplySurface.canInsert(inGmail));
    }

    // ---- only SMS may insert -----------------------------------------------------------------------

    /**
     * One kind can be inserted into, because Android provides exactly one way to do it.
     *
     * <p>{@code smsto:} is a defined composer intent with a body extra. There is no equivalent for
     * Gmail, Discord or anything else, {@code mailto:} opens a new message rather than a reply, and
     * injecting into an arbitrary field needs an AccessibilityService Orbit does not have.
     */
    @Test public void onlySmsCanBeInsertedInto() {
        assertTrue(ReplySurface.canInsert(ReplySurface.Kind.SMS));
        assertFalse(ReplySurface.canInsert(ReplySurface.Kind.EMAIL));
        assertFalse(ReplySurface.canInsert(ReplySurface.Kind.OTHER_APP));
        assertFalse(ReplySurface.canInsert(ReplySurface.Kind.UNKNOWN));
    }

    @Test public void onlyTheInsertableSurfaceHasALabel() {
        assertEquals("Use in chat", ReplySurface.insertLabel(ReplySurface.Kind.SMS));
        for (ReplySurface.Kind kind : new ReplySurface.Kind[]{
                ReplySurface.Kind.EMAIL, ReplySurface.Kind.OTHER_APP, ReplySurface.Kind.UNKNOWN}) {
            assertEquals("a surface Orbit cannot insert into offers no control at all",
                    "", ReplySurface.insertLabel(kind));
        }
    }

    /** What Orbit says instead is true, and names the app rather than the person. */
    @Test public void theCopyMessageIsTruthfulAndNamesNobody() {
        String email = ReplySurface.copiedMessage(ReplySurface.Kind.EMAIL, "Gmail");
        assertTrue(email.startsWith("Reply copied"));
        assertTrue("it says what to do with it", email.contains("paste"));
        assertTrue(email.contains("Gmail"));
        assertFalse("and never claims it was inserted", email.toLowerCase().contains("inserted"));
        assertFalse(email.toLowerCase().contains("sent"));

        assertTrue(ReplySurface.copiedMessage(ReplySurface.Kind.UNKNOWN, "")
                .startsWith("Reply copied"));
    }

    // ---- the code path -------------------------------------------------------------------------------

    /**
     * The SMS helper is named for what it is, so it cannot be reached for generically again.
     *
     * <p>The old name, {@code openReplyComposer}, is what let a general "use this reply" button call
     * an SMS-only implementation without anybody noticing.
     */
    @Test public void theSmsHelperIsVisiblySmsSpecific() {
        String executor = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/DeviceActionExecutor.java");
        assertTrue("the SMS path must be named for SMS",
                executor.contains("public static String openSmsReplyComposer("));
        assertFalse("and the old general-sounding name must be gone",
                executor.contains("public static String openReplyComposer("));
    }

    /**
     * The overlay reaches the SMS helper only behind the surface check.
     *
     * <p>{@code OrbitSession} is a VoiceInteractionSession and is not constructible in a unit test,
     * so its wiring is asserted from the source in the same way the Calendar confirmation rules are.
     */
    @Test public void theOverlayGuardsTheSmsPathBehindTheSurfaceCheck() {
        String session = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OrbitSession.java");
        assertTrue("the medium must be decided from the foreground package",
                session.contains("ReplySurface.of(c, foregroundPackage)"));
        assertTrue("the insert control must be gated on the surface",
                session.contains("ReplySurface.canInsert(surface)"));
        assertTrue("and the SMS composer is the only thing behind it",
                session.contains("DeviceActionExecutor.openSmsReplyComposer("));

        int gate = session.indexOf("ReplySurface.canInsert(surface)");
        int call = session.indexOf("DeviceActionExecutor.openSmsReplyComposer(");
        assertTrue("the SMS call must sit inside the gate, not before it", gate > 0 && call > gate);
    }

    /** Nothing in the reply path reaches for a phone number outside the SMS helper. */
    @Test public void noPhoneNumberIsResolvedOutsideTheSmsPath() {
        String session = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OrbitSession.java");
        assertFalse("the overlay must not resolve contacts itself",
                session.contains("findPhoneNumber"));
        assertFalse("nor build an SMS intent of its own", session.contains("smsto:"));
        assertFalse("nor a mailto:, which would be a new email rather than a reply",
                session.contains("mailto:"));
    }

    /** And Orbit still has no AccessibilityService, which is why insertion is limited to SMS. */
    @Test public void orbitHasNoAccessibilityServiceToInjectWith() {
        String manifest = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/AndroidManifest.xml");
        assertFalse("an accessibility service would be an invasive way to solve this",
                manifest.contains("AccessibilityService"));
        assertFalse(manifest.contains("BIND_ACCESSIBILITY_SERVICE"));
    }

    // ---- diagnostics -----------------------------------------------------------------------------------

    /** Diagnostics learns the destination category and nothing about the person or the text. */
    @Test public void theDiagnosticsDestinationIsACategoryOnly() {
        assertEquals("sms", ReplySurface.diagnosticsName(ReplySurface.Kind.SMS, true));
        assertEquals("sms-copy-fallback", ReplySurface.diagnosticsName(ReplySurface.Kind.SMS, false));
        assertEquals("email-copy", ReplySurface.diagnosticsName(ReplySurface.Kind.EMAIL, false));
        assertEquals("app-copy", ReplySurface.diagnosticsName(ReplySurface.Kind.OTHER_APP, false));
        assertEquals("copy", ReplySurface.diagnosticsName(ReplySurface.Kind.UNKNOWN, false));

        DiagnosticStore.recordReplyDestination(context,
                ReplySurface.diagnosticsName(ReplySurface.Kind.EMAIL, false));
        String stored = DiagnosticStore.lastReplyDestination(context);
        assertEquals("email-copy", stored);
        assertFalse("a destination is never an address", stored.contains("@"));
        assertFalse("nor a package name", stored.contains("."));
    }
}
