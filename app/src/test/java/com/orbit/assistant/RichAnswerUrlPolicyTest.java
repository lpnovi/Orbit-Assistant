package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Rich Answers made Orbit fetch addresses nobody typed, so this is where that is fenced in.
 *
 * <p>A model names a page, Orbit reads that page's declared preview image, and then fetches the
 * image: three requests, none of them chosen by the user. That is a server-side request forgery
 * surface, and the only defence that holds is one policy every fetch passes through, applied again
 * at every redirect hop. These tests pin what it refuses.
 *
 * <p>Deliberately syntax-level. {@link RichAnswerUrlPolicy#resolvesToPublicHost} performs DNS and
 * is exercised through the addresses it classifies rather than by resolving names in a test, which
 * would make the suite depend on somebody else's nameserver.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class RichAnswerUrlPolicyTest {

    @Test public void ordinaryPublicHttpsIsFetchable() {
        assertTrue(RichAnswerUrlPolicy.isFetchableImageUrl("https://example.com/bird.jpg"));
        assertTrue(RichAnswerUrlPolicy.isFetchablePageUrl("https://en.example.org/wiki/Robin"));
        assertTrue(RichAnswerUrlPolicy.isFetchableImageUrl(
                "https://cdn.example.com/a/b/c.png?w=1200&h=800"));
    }

    /**
     * Orbit fetches over https only, and opens http as well.
     *
     * <p>Two different questions with two different answers. A picture on an answer is decoration
     * and is not worth a cleartext request Orbit chose to make; a link the user deliberately tapped
     * is an ordinary web page, and refusing to open an http site would be Orbit deciding something
     * the browser is entitled to decide.
     */
    @Test public void plainHttpIsOpenableButNeverFetched() {
        assertFalse(RichAnswerUrlPolicy.isFetchableImageUrl("http://example.com/bird.jpg"));
        assertFalse(RichAnswerUrlPolicy.isFetchablePageUrl("http://example.com/page"));
        assertTrue(RichAnswerUrlPolicy.isOpenableWebUrl("http://example.com/page"));
        assertTrue(RichAnswerUrlPolicy.isOpenableWebUrl("https://example.com/page"));
    }

    /** Everything that is not a web address, refused in both directions. */
    @Test public void nonWebSchemesAreRefusedEverywhere() {
        for (String url : new String[]{
                "file:///data/data/com.orbit.assistant/files/secret.txt",
                "file://localhost/etc/hosts",
                "content://com.orbit.assistant.files/vault/1.jpg",
                "javascript:alert(1)",
                "intent://scan/#Intent;scheme=zxing;end",
                "data:image/png;base64,AAAA",
                "ftp://example.com/x.jpg",
                "ws://example.com/socket",
                "about:blank",
                "chrome://settings"}) {
            assertFalse(url + " must never be fetched",
                    RichAnswerUrlPolicy.isFetchableImageUrl(url));
            assertFalse(url + " must never be handed to Android",
                    RichAnswerUrlPolicy.isOpenableWebUrl(url));
        }
    }

    /** This device, by every name it answers to. */
    @Test public void loopbackAndLocalHostnamesAreRefused() {
        for (String url : new String[]{
                "https://localhost/x.jpg",
                "https://localhost:8080/x.jpg",
                "https://api.localhost/x.jpg",
                "https://127.0.0.1/x.jpg",
                "https://printer.local/x.jpg",
                "https://vault.internal/x.jpg",
                "https://router.home.arpa/x.jpg",
                "https://[::1]/x.jpg"}) {
            assertFalse(url + " is this device or its network",
                    RichAnswerUrlPolicy.isFetchableImageUrl(url));
        }
    }

    /**
     * Credentials in a URL are refused rather than stripped.
     *
     * <p>Stripping would mean Orbit deciding to make a request somebody wrote deliberately, minus
     * the part that made it work. Refusing says the address is not one Orbit will fetch, which is
     * both simpler and correct.
     */
    @Test public void urlsCarryingCredentialsAreRefused() {
        assertFalse(RichAnswerUrlPolicy.isFetchableImageUrl("https://user:pass@example.com/x.jpg"));
        assertFalse(RichAnswerUrlPolicy.isFetchableImageUrl("https://token@example.com/x.jpg"));
        assertFalse(RichAnswerUrlPolicy.isOpenableWebUrl("https://user:pass@example.com/page"));
    }

    /** A URL containing whitespace or a control character is not a URL. */
    @Test public void controlCharactersAreRefusedAndSpacesAreEncoded() {
        assertFalse(RichAnswerUrlPolicy.isFetchableImageUrl("https://example.com/x.jpg\nHost: evil"));
        assertFalse(RichAnswerUrlPolicy.isFetchableImageUrl("https://exa\tmple.com/x.jpg"));
        assertEquals("a splitting attempt is refused rather than escaped", "",
                RichAnswerUrlPolicy.normalizedForRequest("https://example.com/a\r\nHost:evil"));

        // Beta 1 refused this too, which was safe and wrong. A space in a filename is something
        // people type and browsers encode; refusing it lost real public pictures for no gain.
        assertTrue("an ordinary space is encoded, as a browser encodes it",
                RichAnswerUrlPolicy.isFetchableImageUrl("https://example.com/a b.jpg"));
        assertEquals("https://example.com/a%20b.jpg",
                RichAnswerUrlPolicy.normalizedForRequest("https://example.com/a b.jpg"));

        assertTrue("surrounding whitespace is trimmed, exactly as any field would trim it",
                RichAnswerUrlPolicy.isFetchableImageUrl("  https://example.com/x.jpg\r\n"));
        assertFalse(RichAnswerUrlPolicy.isFetchableImageUrl(""));
        assertFalse(RichAnswerUrlPolicy.isFetchableImageUrl(null));
    }

    /** A relative reference is not something to fetch on its own. */
    @Test public void relativeAndHostlessAddressesAreRefused() {
        assertFalse(RichAnswerUrlPolicy.isFetchableImageUrl("/images/bird.jpg"));
        assertFalse(RichAnswerUrlPolicy.isFetchableImageUrl("//example.com/bird.jpg"));
        assertFalse(RichAnswerUrlPolicy.isFetchableImageUrl("https:///bird.jpg"));
        assertFalse(RichAnswerUrlPolicy.isOpenableWebUrl("https://nodot/page"));
    }

    // ---- private address ranges ------------------------------------------------------------------

    /**
     * Every address family that means "somewhere on this network".
     *
     * <p>Asserted against {@link RichAnswerUrlPolicy#isPublicAddress} directly rather than through
     * a hostname, so the test states which ranges are refused instead of depending on what a
     * resolver happens to answer.
     */
    @Test public void privateAndLocalAddressRangesAreNotPublic() throws Exception {
        for (String address : new String[]{
                "127.0.0.1", "127.53.1.9",              // loopback
                "0.0.0.0",                              // this host
                "10.0.0.5", "10.255.255.254",           // RFC1918
                "172.16.0.1", "172.31.255.254",         // RFC1918
                "192.168.1.1", "192.168.0.254",         // RFC1918
                "169.254.169.254",                      // link-local, and the metadata address
                "100.64.0.1", "100.127.255.254",        // carrier-grade NAT
                "192.0.0.1", "192.0.2.5",               // IETF protocol / documentation
                "198.18.0.1", "198.19.255.254",         // benchmarking
                "198.51.100.7", "203.0.113.9",          // documentation
                "224.0.0.1", "239.255.255.255",         // multicast
                "::1",                                  // IPv6 loopback
                "fd00::1", "fc00::1",                   // IPv6 unique local
                "fe80::1"}) {                           // IPv6 link-local
            assertFalse(address + " must never be treated as public",
                    RichAnswerUrlPolicy.isPublicAddress(
                            java.net.InetAddress.getByName(address)));
        }
    }

    /** And ordinary routable addresses still are. */
    @Test public void ordinaryPublicAddressesArePublic() throws Exception {
        for (String address : new String[]{
                "8.8.8.8", "1.1.1.1", "93.184.216.34", "172.15.0.1", "172.32.0.1",
                "192.167.1.1", "192.169.1.1", "2606:4700:4700::1111"}) {
            assertTrue(address + " is on the public internet",
                    RichAnswerUrlPolicy.isPublicAddress(
                            java.net.InetAddress.getByName(address)));
        }
    }

    // ---- redirects ---------------------------------------------------------------------------------

    /**
     * A redirect target is put through the whole policy again.
     *
     * <p>The hop that matters. Validating only the address Orbit was given proves nothing about
     * where the server sends it, and a redirect from a public host onto this device's own network
     * is precisely the attack a single check at the top invites.
     */
    @Test public void redirectTargetsAreRevalidated() {
        String from = "https://example.com/page";
        assertEquals("https://example.com/other",
                RichAnswerUrlPolicy.redirectTarget(from, "/other"));
        assertEquals("https://cdn.example.net/img.jpg",
                RichAnswerUrlPolicy.redirectTarget(from, "https://cdn.example.net/img.jpg"));

        for (String location : new String[]{
                "http://example.com/downgrade",
                "https://127.0.0.1/x",
                "https://localhost/x",
                "file:///etc/hosts",
                "javascript:alert(1)",
                "intent://x#Intent;end",
                "https://user:pass@example.com/x",
                "", "   ", null}) {
            assertEquals("a redirect to " + location + " must not be followed",
                    "", RichAnswerUrlPolicy.redirectTarget(from, location));
        }
    }

    /** Redirects are bounded, so a loop cannot hold a connection open indefinitely. */
    @Test public void redirectsAreBounded() {
        assertTrue("a fetch must give up eventually", RichAnswerUrlPolicy.MAX_REDIRECTS > 0);
        assertTrue("and long before a redirect loop becomes expensive",
                RichAnswerUrlPolicy.MAX_REDIRECTS <= 5);
    }

    /**
     * The image loader and the Rich Answer path share one implementation of this rule.
     *
     * <p>Two copies of a request-forgery check is how one of them ends up a hop behind the other.
     */
    @Test public void theImageLoaderDelegatesToThisPolicy() {
        assertTrue(RemoteImageLoader.hasSafeHttpsSyntax("https://example.com/x.jpg"));
        assertFalse(RemoteImageLoader.hasSafeHttpsSyntax("https://127.0.0.1/x.jpg"));
        assertFalse(RemoteImageLoader.hasSafeHttpsSyntax("http://example.com/x.jpg"));
        assertFalse(RemoteImageLoader.hasSafeHttpsSyntax("https://user:pw@example.com/x.jpg"));
    }
}
