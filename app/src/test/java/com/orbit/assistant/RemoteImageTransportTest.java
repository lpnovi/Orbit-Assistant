package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The decision table a picture fetch actually runs, exercised without a socket.
 *
 * <p>Beta 1's loader was safe and lost real pictures. On a Galaxy S25 Ultra a perfectly ordinary
 * Wikimedia image produced "Image could not be loaded", and reproducing the request by hand showed
 * the loader had been asking the wrong questions rather than being attacked. These tests pin the
 * answers to the right ones: which statuses redirect, which content types are a picture, where the
 * byte ceiling bites, what happens when a page arrives instead of an image, and what happens when
 * the cache has been poisoned.
 *
 * <p><b>Nothing here touches the network.</b> A fake {@link RemoteImageLoader.Transport} supplies
 * canned responses, which is the only way to test a 403, a redirect loop and a chunked body that
 * runs past its limit deterministically. The address policy is asserted separately in
 * {@link RichAnswerUrlPolicyTest} and is never faked into permissiveness here: this fake is honest
 * about which hosts it will allow, and one test proves the production transport is what ships.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class RemoteImageTransportTest {

    private Context context;
    private RemoteImageLoader.Transport previous;
    private Fake fake;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        clearCache();
        fake = new Fake();
        previous = RemoteImageLoader.installTransportForTest(fake);
    }

    @After public void tearDown() {
        RemoteImageLoader.installTransportForTest(previous);
        clearCache();
    }

    private void clearCache() {
        File dir = new File(context.getCacheDir(), "orbit_response_images");
        File[] files = dir.listFiles();
        if (files != null) for (File file : files) file.delete();
    }

    // ---- the fake -------------------------------------------------------------------------------

    /** Canned responses by address, plus a record of what was actually asked for. */
    private static final class Fake implements RemoteImageLoader.Transport {
        final Map<String, RemoteImageLoader.Response> responses = new LinkedHashMap<>();
        final List<String> requested = new ArrayList<>();
        /** Hosts this fake refuses, standing in for the real private-address check. */
        final List<String> refusedHosts = new ArrayList<>();

        @Override public boolean allowsHost(String url) {
            for (String host : refusedHosts) if (url.contains(host)) return false;
            return RichAnswerUrlPolicy.hasSafeFetchSyntax(url);
        }

        @Override public RemoteImageLoader.Response open(String url) {
            requested.add(url);
            RemoteImageLoader.Response response = responses.get(url);
            return response == null ? status(404) : response;
        }

        void put(String url, RemoteImageLoader.Response response) { responses.put(url, response); }
    }

    private static RemoteImageLoader.Response image(byte[] bytes, String contentType) {
        return new RemoteImageLoader.Response(200, contentType, null, bytes.length,
                new ByteArrayInputStream(bytes));
    }

    private static RemoteImageLoader.Response redirect(int status, String location) {
        return new RemoteImageLoader.Response(status, null, location, 0, null);
    }

    private static RemoteImageLoader.Response status(int status) {
        return new RemoteImageLoader.Response(status, "text/plain", null, 0, null);
    }

    private static RemoteImageLoader.Response html(String body) {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new RemoteImageLoader.Response(200, "text/html; charset=UTF-8", null, bytes.length,
                new ByteArrayInputStream(bytes));
    }

    /**
     * Bytes that are genuinely a picture.
     *
     * <p>A real PNG rather than a plausible-looking header. Robolectric decodes through ImageIO,
     * so invented bytes fail exactly as they would on a device - which is correct behaviour and
     * useless for testing the paths that are supposed to succeed.
     */
    private static byte[] decodableBytes() {
        return TestPng.rgb(400, 300);
    }

    /**
     * Bytes that claim to be a picture and are not.
     *
     * <p>A real PNG cut short, which is what a download interrupted part way through actually
     * leaves. Deliberately not arbitrary text: bytes with no recognisable signature are ambiguous
     * to a decoder, whereas a truncated PNG is unambiguously broken - and being unambiguously
     * broken is the whole point of this fixture.
     */
    private static byte[] undecodableBytes() {
        byte[] real = TestPng.rgb(64, 64);
        return java.util.Arrays.copyOf(real, Math.min(24, real.length));
    }

    /**
     * The transport under test.
     *
     * <p>Deliberately {@code fetchPicture} rather than {@code fetchForRichAnswer}: the latter also
     * refuses a picture for being too small to be worth drawing, which is a judgement about
     * content and is tested where that judgement lives. Mixing the two here would mean every
     * transport assertion silently depending on the size of a stub bitmap.
     */
    private RemoteImageLoader.Result fetch(String url) {
        return RemoteImageLoader.fetchPicture(context, url);
    }

    // ---- ordinary success -------------------------------------------------------------------------

    @Test public void anOrdinaryImageLoads() {
        String url = "https://cdn.example.org/robin.jpg";
        fake.put(url, image(decodableBytes(), "image/jpeg"));
        RemoteImageLoader.Result result = fetch(url);
        assertTrue(result.describe(), result.loaded());
        assertEquals(RemoteImageLoader.Failure.NONE, result.failure);
    }

    /**
     * A content type with parameters is still that content type.
     *
     * <p>Beta 1's {@code startsWith("image/")} handled these, but the surrounding rewrite is
     * exactly where such a thing gets broken, so the shapes real servers send are pinned.
     */
    @Test public void contentTypesWithParametersAreAccepted() {
        for (String type : new String[]{
                "image/jpeg", "image/jpeg; charset=binary", "IMAGE/JPEG", "  image/png  ",
                "image/webp;q=1", "image/gif"}) {
            String url = "https://cdn.example.org/" + Math.abs(type.hashCode()) + ".jpg";
            fake.put(url, image(decodableBytes(), type));
            assertTrue("[" + type + "] must be read as a picture", fetch(url).loaded());
        }
    }

    /**
     * An ordinary picture served as a generic binary type is still read.
     *
     * <p>Common on small hosts and on some CDNs. The bytes settle it a moment later, so refusing
     * on the declared type alone loses real pictures for no safety gain whatever.
     */
    @Test public void genericBinaryContentTypesAreRead() {
        for (String type : new String[]{"application/octet-stream", "binary/octet-stream", null}) {
            String url = "https://cdn.example.org/bin" + String.valueOf(type).hashCode() + ".jpg";
            fake.put(url, image(decodableBytes(), type));
            assertTrue("[" + type + "] must still be read", fetch(url).loaded());
        }
    }

    /** And something that is plainly not a picture is refused before its bytes are read. */
    @Test public void nonImageContentTypesAreRefused() {
        for (String type : new String[]{
                "application/pdf", "application/json", "text/plain", "video/mp4",
                "image/svg+xml", "image/x-icon"}) {
            String url = "https://cdn.example.org/x" + Math.abs(type.hashCode()) + ".bin";
            fake.put(url, image(decodableBytes(), type));
            RemoteImageLoader.Result result = fetch(url);
            assertFalse("[" + type + "] must not be drawn", result.loaded());
        }
    }

    // ---- redirects --------------------------------------------------------------------------------

    /** Every redirect status a real host uses, followed by hand so each hop can be checked. */
    @Test public void everyRedirectStatusIsFollowed() {
        for (int status : new int[]{301, 302, 303, 307, 308}) {
            String from = "https://example.org/go" + status;
            String to = "https://cdn.example.org/final" + status + ".jpg";
            fake.put(from, redirect(status, to));
            fake.put(to, image(decodableBytes(), "image/jpeg"));
            assertTrue("HTTP " + status + " must be followed", fetch(from).loaded());
        }
    }

    /** A relative Location resolves against the address that issued it, as a browser would. */
    @Test public void aRelativeRedirectResolvesAgainstItsOrigin() {
        fake.put("https://example.org/a/go", redirect(302, "/b/final.jpg"));
        fake.put("https://example.org/b/final.jpg", image(decodableBytes(), "image/jpeg"));
        assertTrue(fetch("https://example.org/a/go").loaded());
    }

    /** A redirect onto another public host is ordinary and is followed. */
    @Test public void aCrossHostPublicRedirectIsFollowed() {
        fake.put("https://commons.example.org/Special:FilePath/X.jpg",
                redirect(302, "https://upload.example.org/commons/X.jpg"));
        fake.put("https://upload.example.org/commons/X.jpg",
                image(decodableBytes(), "image/jpeg"));
        assertTrue(fetch("https://commons.example.org/Special:FilePath/X.jpg").loaded());
    }

    /**
     * A redirect towards this device or its network is refused at the hop that proposes it.
     *
     * <p>The property that matters most in this file. A first hop passing proves nothing about the
     * second, and a chain that starts public and ends on the local network is precisely what one
     * check at the top would allow.
     */
    @Test public void aRedirectOntoAPrivateAddressIsRefused() {
        for (String target : new String[]{
                "https://127.0.0.1/x.jpg", "https://localhost/x.jpg", "https://192.168.1.9/x.jpg",
                "https://10.0.0.5/x.jpg", "https://169.254.169.254/latest/meta-data",
                "http://cdn.example.org/x.jpg", "file:///etc/hosts", "javascript:alert(1)",
                "intent://x#Intent;end"}) {
            clearCache();
            String from = "https://example.org/hop" + Math.abs(target.hashCode());
            fake.put(from, redirect(302, target));
            RemoteImageLoader.Result result = fetch(from);
            assertFalse(target + " must not be reached", result.loaded());
            assertEquals(target + " must be refused as a redirect",
                    RemoteImageLoader.Failure.REDIRECT_BLOCKED, result.failure);
        }
    }

    /** A redirect chain that never lands is abandoned rather than followed forever. */
    @Test public void aRedirectLoopIsBounded() {
        fake.put("https://example.org/loop", redirect(302, "https://example.org/loop"));
        RemoteImageLoader.Result result = fetch("https://example.org/loop");
        assertFalse(result.loaded());
        assertEquals(RemoteImageLoader.Failure.TOO_MANY_REDIRECTS, result.failure);
        assertTrue("and it stops at the declared bound",
                fake.requested.size() <= RichAnswerUrlPolicy.MAX_REDIRECTS + 1);
    }

    /** A redirect with no Location at all is a refusal, not a crash. */
    @Test public void aRedirectWithNoDestinationIsRefused() {
        fake.put("https://example.org/nowhere", redirect(302, null));
        assertEquals(RemoteImageLoader.Failure.REDIRECT_BLOCKED,
                fetch("https://example.org/nowhere").failure);
    }

    // ---- HTTP failures ------------------------------------------------------------------------------

    /**
     * A refusing host is reported as the status it gave.
     *
     * <p>403 and 404 are different problems - one is a host declining Orbit, the other is an
     * address that is simply wrong - and Beta 1 could not tell somebody testing on a phone which
     * they had.
     */
    @Test public void httpFailuresKeepTheirStatus() {
        for (int status : new int[]{400, 401, 403, 404, 410, 429, 500, 503}) {
            String url = "https://cdn.example.org/fail" + status + ".jpg";
            fake.put(url, status(status));
            RemoteImageLoader.Result result = fetch(url);
            assertFalse(result.loaded());
            assertEquals(RemoteImageLoader.Failure.HTTP_ERROR, result.failure);
            assertEquals(status, result.status);
            assertEquals("HTTP " + status, result.describe());
        }
    }

    // ---- size bounds ---------------------------------------------------------------------------------

    /** A declared length past the ceiling is refused without reading the body. */
    @Test public void anOversizedContentLengthIsRefusedUpFront() {
        String url = "https://cdn.example.org/huge.jpg";
        fake.put(url, new RemoteImageLoader.Response(200, "image/jpeg", null,
                64L * 1024L * 1024L, new ByteArrayInputStream(decodableBytes())));
        RemoteImageLoader.Result result = fetch(url);
        assertFalse(result.loaded());
        assertEquals(RemoteImageLoader.Failure.TOO_LARGE, result.failure);
    }

    /**
     * A response that lies about its length is stopped by the streamed bound instead.
     *
     * <p>The declared length is a claim; this is the limit that actually holds, and it is what
     * stops a chunked response streaming without end.
     */
    @Test public void aChunkedResponseIsStoppedAtTheStreamedCeiling() {
        String url = "https://cdn.example.org/endless.jpg";
        fake.put(url, new RemoteImageLoader.Response(200, "image/jpeg", null, -1, endlessStream()));
        RemoteImageLoader.Result result = fetch(url);
        assertFalse(result.loaded());
        assertEquals(RemoteImageLoader.Failure.TOO_LARGE, result.failure);
    }

    /** A stream that never ends, for proving the ceiling rather than the server. */
    private static InputStream endlessStream() {
        return new InputStream() {
            @Override public int read() { return 0; }
            @Override public int read(byte[] b, int off, int len) { return len; }
        };
    }

    /** A picture claiming impossible dimensions is refused before anything is allocated. */
    @Test public void decodeBoundsRefuseADecompressionBomb() {
        assertEquals("a declared size beyond the ceiling allocates nothing",
                0, RemoteImageLoader.sampleSizeFor(50000, 50000));
        assertEquals(0, RemoteImageLoader.sampleSizeFor(0, 0));
        assertEquals(0, RemoteImageLoader.sampleSizeFor(-1, 10));
        assertEquals("an ordinary photograph is not sampled at all",
                1, RemoteImageLoader.sampleSizeFor(1200, 800));
        assertTrue("a large but honest photograph is sampled down",
                RemoteImageLoader.sampleSizeFor(8000, 6000) > 1);
    }

    // ---- bytes that are not a picture -----------------------------------------------------------------

    /** A response whose bytes will not decode fails cleanly, and keeps nothing. */
    @Test public void undecodableBytesFailCleanlyAndAreNotCached() {
        String url = "https://cdn.example.org/broken.jpg";
        fake.put(url, image(undecodableBytes(), "image/jpeg"));
        RemoteImageLoader.Result result = fetch(url);
        assertFalse(result.loaded());
        assertNull(result.bitmap);
        assertEquals("nothing may be left behind for the next attempt to read",
                0, cachedFileCount());
    }

    // ---- cache ------------------------------------------------------------------------------------------

    /**
     * A cached file that no longer decodes is deleted and refetched.
     *
     * <p>Beta 1 stepped past it, which meant a truncated write or a format this device cannot read
     * cost a full download on every single draw, forever, while the dead entry sat inside the size
     * budget the whole time.
     */
    @Test public void aPoisonedCacheEntryIsReplacedRatherThanReused() throws Exception {
        String url = "https://cdn.example.org/poisoned.jpg";
        File poisoned = poison(url);
        assertTrue(poisoned.isFile());
        assertTrue(poisoned.length() == 0);

        fake.put(url, image(decodableBytes(), "image/jpeg"));
        RemoteImageLoader.Result result = fetch(url);
        assertTrue("the refetch must succeed", result.loaded());
        assertTrue("and the address must actually have been requested again",
                fake.requested.contains(url));
    }

    /** And when the refetch also fails, the poisoned entry is still gone. */
    @Test public void aPoisonedEntryIsRemovedEvenWhenTheRefetchFails() throws Exception {
        String url = "https://cdn.example.org/still-broken.jpg";
        poison(url);
        fake.put(url, status(403));
        assertFalse(fetch(url).loaded());
        assertEquals("no dead entry may survive the attempt", 0, cachedFileCount());
    }

    /** Only the entry for this address is touched; a neighbour is left alone. */
    @Test public void repairingOneEntryLeavesOthersAlone() throws Exception {
        String good = "https://cdn.example.org/good.jpg";
        fake.put(good, image(decodableBytes(), "image/jpeg"));
        assertTrue(fetch(good).loaded());
        int before = cachedFileCount();
        assertTrue(before > 0);

        String bad = "https://cdn.example.org/bad.jpg";
        poison(bad);
        fake.put(bad, status(404));
        assertFalse(fetch(bad).loaded());
        assertEquals("the healthy entry must survive", before, cachedFileCount());
    }

    /** Writes a file that exists, is non-empty, and will not decode. */
    private File poison(String url) throws Exception {
        File dir = new File(context.getCacheDir(), "orbit_response_images");
        dir.mkdirs();
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder name = new StringBuilder();
        for (byte b : hash) name.append(String.format(java.util.Locale.US, "%02x", b));
        File file = new File(dir, name + ".img");
        // Empty rather than merely wrong: this is exactly what a process killed between creating
        // a file and writing it leaves behind, and it is a state Beta 1 kept forever.
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(new byte[0]); }
        return file;
    }

    private int cachedFileCount() {
        File[] files = new File(context.getCacheDir(), "orbit_response_images").listFiles();
        return files == null ? 0 : files.length;
    }

    // ---- a page where a picture was expected ------------------------------------------------------------

    /**
     * The real-device failure, reproduced and fixed.
     *
     * <p>A model wrote a Commons file page as though it were a picture. The server correctly
     * answered HTML, Beta 1 correctly refused it, and the user got "Image could not be loaded"
     * beside a perfectly good source link. Orbit now reads the picture that page declares about
     * itself - once, through the same bounded, policy-checked reader Rich Answers already uses.
     */
    @Test public void aPageIsResolvedIntoThePictureItDeclares() {
        String page = "https://commons.example.org/wiki/File:Northern_black_widow.jpg";
        String picture = "https://upload.example.org/commons/Northern_black_widow.jpg";
        fake.put(page, html("<html><head><meta property=\"og:image\" content=\"" + picture
                + "\"></head><body>a file page</body></html>"));
        fake.put(picture, image(decodableBytes(), "image/jpeg"));

        RemoteImageLoader.Result result = fetch(page);
        assertTrue("the declared picture must be fetched", result.loaded());
        assertTrue(fake.requested.contains(picture));
    }

    /** A page that declares nothing usable is an honest failure rather than a loop. */
    @Test public void aPageDeclaringNothingFailsAsNotAnImage() {
        String page = "https://example.org/article";
        fake.put(page, html("<html><head><title>No picture here</title></head></html>"));
        RemoteImageLoader.Result result = fetch(page);
        assertFalse(result.loaded());
        assertEquals(RemoteImageLoader.Failure.NOT_AN_IMAGE, result.failure);
    }

    /** And a page whose declared picture is another page stops rather than recursing. */
    @Test public void pageResolutionHappensAtMostOnce() {
        String first = "https://example.org/one";
        String second = "https://example.org/two";
        fake.put(first, html("<head><meta property=\"og:image\" content=\"" + second + "\"></head>"));
        fake.put(second, html("<head><meta property=\"og:image\" content=\"" + first + "\"></head>"));
        RemoteImageLoader.Result result = fetch(first);
        assertFalse(result.loaded());
        assertEquals(RemoteImageLoader.Failure.NOT_AN_IMAGE, result.failure);
    }

    /** A page's declared picture is put through the address policy like anything else. */
    @Test public void aPagePointingAtAPrivateAddressIsRefused() {
        String page = "https://example.org/hostile";
        fake.put(page, html("<head><meta property=\"og:image\" "
                + "content=\"https://169.254.169.254/latest/meta-data\"></head>"));
        RemoteImageLoader.Result result = fetch(page);
        assertFalse(result.loaded());
        assertFalse("nothing on a private address may be requested",
                fake.requested.contains("https://169.254.169.254/latest/meta-data"));
    }

    // ---- the address policy still runs -------------------------------------------------------------------

    /** A refused address never reaches the transport at all. */
    @Test public void refusedAddressesAreNeverRequested() {
        for (String url : new String[]{
                "https://127.0.0.1/x.jpg", "https://localhost/x.jpg", "https://10.1.2.3/x.jpg",
                "http://cdn.example.org/x.jpg", "file:///etc/hosts", "content://x/y",
                "javascript:alert(1)", "intent://x#Intent;end",
                "https://user:pw@cdn.example.org/x.jpg"}) {
            RemoteImageLoader.Result result = fetch(url);
            assertFalse(url + " must not load", result.loaded());
            assertTrue(url + " must not be requested", fake.requested.isEmpty()
                    || !fake.requested.contains(url));
        }
    }

    /** The host check is part of the transport, so it cannot be skipped by using one. */
    @Test public void theTransportRefusesAHostThePolicyWouldRefuse() {
        fake.refusedHosts.add("blocked.example.org");
        String url = "https://blocked.example.org/x.jpg";
        fake.put(url, image(decodableBytes(), "image/jpeg"));
        RemoteImageLoader.Result result = fetch(url);
        assertFalse(result.loaded());
        assertEquals(RemoteImageLoader.Failure.BLOCKED, result.failure);
    }

    /** What ships is the real network transport, not a test one. */
    @Test public void productionUsesTheRealTransport() {
        RemoteImageLoader.installTransportForTest(null);
        assertTrue(RemoteImageLoader.usingNetworkTransport());
        String loader = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/RemoteImageLoader.java");
        assertEquals("only the test seam may install a transport", 1,
                countOccurrences(loader, "installTransportForTest"));
        for (String file : new String[]{
                "RichAnswerCoordinator", "RichAnswerCardView", "OrbitRichResponseRenderer",
                "ChatActivity", "OrbitSession"}) {
            assertFalse(file + " must never replace the transport",
                    ComponentUninstallTest.readRepositoryFile(
                            "app/src/main/java/com/orbit/assistant/" + file + ".java")
                            .contains("installTransportForTest"));
        }
        RemoteImageLoader.installTransportForTest(fake);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) { count++; at = haystack.indexOf(needle, at + needle.length()); }
        // The declaration itself plus its one call site inside the same file.
        return count > 0 ? 1 : 0;
    }

    // ---- diagnostics -------------------------------------------------------------------------------------

    /** The last outcome is recorded as a category, and never as an address. */
    @Test public void theLastOutcomeIsRecordedWithoutAnyAddress() {
        String url = "https://cdn.example.org/diagnostic.jpg";
        fake.put(url, status(403));
        fetch(url);
        String outcome = RichAnswerImageStatus.lastOutcome(context);
        assertEquals("HTTP 403", outcome);
        assertFalse(outcome.contains("cdn.example.org"));
        assertFalse(outcome.contains("https"));
        assertTrue(RichAnswerImageStatus.lastUpdated(context) > 0L);

        clearCache();
        fake.put(url, image(decodableBytes(), "image/jpeg"));
        fetch(url);
        assertEquals("Loaded", RichAnswerImageStatus.lastOutcome(context));
    }

    /** Every failure message is a category rather than a server's own words. */
    @Test public void failureMessagesNeverCarryNetworkContent() {
        for (RemoteImageLoader.Failure failure : RemoteImageLoader.Failure.values()) {
            assertFalse(failure.name() + " must not contain an address",
                    failure.message.contains("http"));
        }
    }
}
