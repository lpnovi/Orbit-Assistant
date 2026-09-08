package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The shapes the real web actually serves, and what Orbit does with them.
 *
 * <p>Beta 1 was tested against invented URLs and passed. A Galaxy S25 Ultra then asked about a
 * Northern black widow, got the right decision and the right attribution, and showed no picture -
 * because the addresses a real encyclopedia hands out do not look like the ones in a test fixture.
 * They carry percent escapes, parentheses, commas, accents, thumbnail path segments and tracking
 * query strings, and one of them is a <em>page about</em> a picture rather than the picture.
 *
 * <p>Every URL below is shaped like one Wikimedia genuinely produces, and every one is on an
 * example host. <b>Nothing here contacts the internet</b>, and Wikimedia gets no special treatment
 * anywhere in the code: it is simply the site that exposed the gaps, and the fixes are general.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class RichAnswerCompatibilityTest {

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
        File[] files = new File(context.getCacheDir(), "orbit_response_images").listFiles();
        if (files != null) for (File file : files) file.delete();
    }

    private static final class Fake implements RemoteImageLoader.Transport {
        final Map<String, RemoteImageLoader.Response> responses = new LinkedHashMap<>();
        final List<String> requested = new ArrayList<>();

        @Override public boolean allowsHost(String url) {
            return RichAnswerUrlPolicy.hasSafeFetchSyntax(url);
        }

        @Override public RemoteImageLoader.Response open(String url) {
            requested.add(url);
            RemoteImageLoader.Response canned = responses.get(url);
            return canned != null ? canned
                    : new RemoteImageLoader.Response(404, "text/plain", null, 0, null);
        }
    }

    private void serveImage(String url) {
        byte[] bytes = TestPng.rgb(400, 300);
        fake.responses.put(url, new RemoteImageLoader.Response(
                200, "image/jpeg", null, bytes.length, new ByteArrayInputStream(bytes)));
    }

    private void serveHtml(String url, String html) {
        byte[] bytes = html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        fake.responses.put(url, new RemoteImageLoader.Response(
                200, "text/html; charset=UTF-8", null, bytes.length,
                new ByteArrayInputStream(bytes)));
    }

    // ---- addresses a real encyclopedia hands out --------------------------------------------------

    /**
     * A percent-encoded thumbnail path, which is the ordinary Commons shape.
     *
     * <p>{@code %28} and {@code %2C} for the parentheses and comma in the filename, the
     * {@code /thumb/} segment, and the width prefix repeated on the leaf.
     */
    @Test public void aPercentEncodedThumbnailPathLoads() {
        String url = "https://upload.example.org/wikipedia/commons/thumb/a/a5/"
                + "Latrodectus_variolus_%28Northern_Black_Widow%29%2C_F_Theridiidae.jpg/"
                + "330px-Latrodectus_variolus_%28Northern_Black_Widow%29%2C_F_Theridiidae.jpg";
        assertTrue("a real thumbnail address must be fetchable",
                RichAnswerUrlPolicy.isFetchableImageUrl(url));
        serveImage(url);
        assertTrue(RemoteImageLoader.fetchPicture(context, url).loaded());
        assertEquals("an already-escaped address must go out unchanged", url, fake.requested.get(0));
    }

    /** Unencoded parentheses and commas are legal in a path and must survive untouched. */
    @Test public void unencodedPunctuationInAFilenameIsPreserved() {
        String url = "https://commons.example.org/wiki/"
                + "Special:FilePath/Latrodectus_variolus_(Northern_Black_Widow),_F_Theridiidae.jpg";
        assertTrue(RichAnswerUrlPolicy.isFetchableImageUrl(url));
        assertEquals(url, RichAnswerUrlPolicy.normalizedForRequest(url));
    }

    /**
     * A filename with an accent is encoded rather than sent raw.
     *
     * <p>The second confirmed real-device failure. Java put the raw UTF-8 bytes into the request
     * line and the CDN answered HTTP 400 - a perfectly good public picture, refused for a reason
     * that had nothing to do with safety.
     */
    @Test public void nonAsciiFilenamesAreEncodedBeforeTheyAreSent() {
        String url = "https://upload.example.org/wikipedia/commons/e/e0/Café_de_Flore.jpg";
        String sent = RichAnswerUrlPolicy.normalizedForRequest(url);
        assertEquals("https://upload.example.org/wikipedia/commons/e/e0/Caf%C3%A9_de_Flore.jpg", sent);
        assertTrue(RichAnswerUrlPolicy.isFetchableImageUrl(url));

        serveImage(sent);
        assertTrue(RemoteImageLoader.fetchPicture(context, url).loaded());
        assertEquals("the encoded form is what goes on the wire", sent, fake.requested.get(0));
    }

    /** A non-Latin script filename is encoded the same way. */
    @Test public void nonLatinFilenamesAreEncoded() {
        String url = "https://upload.example.org/wikipedia/commons/1/12/日本.jpg";
        String sent = RichAnswerUrlPolicy.normalizedForRequest(url);
        assertEquals("https://upload.example.org/wikipedia/commons/1/12/%E6%97%A5%E6%9C%AC.jpg", sent);
        assertTrue(RichAnswerUrlPolicy.isFetchableImageUrl(url));
    }

    /** A space becomes %20 rather than a refusal. */
    @Test public void spacesAreEncodedRatherThanRefused() {
        String url = "https://upload.example.org/wikipedia/commons/a/a5/Northern Black Widow.jpg";
        assertEquals("https://upload.example.org/wikipedia/commons/a/a5/"
                + "Northern%20Black%20Widow.jpg", RichAnswerUrlPolicy.normalizedForRequest(url));
        assertTrue(RichAnswerUrlPolicy.isFetchableImageUrl(url));
    }

    /** An escape that is already correct is never escaped a second time. */
    @Test public void existingEscapesAreNotDoubleEncoded() {
        String url = "https://upload.example.org/a/b/X_%28Y%29%2C_Z.jpg";
        assertEquals(url, RichAnswerUrlPolicy.normalizedForRequest(url));
        assertFalse(RichAnswerUrlPolicy.normalizedForRequest(url).contains("%25"));
    }

    /** A stray percent sign is encoded so it cannot be read as the start of an escape. */
    @Test public void aStrayPercentIsEncoded() {
        assertEquals("https://cdn.example.org/50%25-off.jpg",
                RichAnswerUrlPolicy.normalizedForRequest("https://cdn.example.org/50%-off.jpg"));
    }

    /** Query strings survive, because a CDN routinely needs them. */
    @Test public void queryStringsArePreserved() {
        String url = "https://upload.example.org/commons/X.jpg"
                + "?utm_source=example.org&utm_campaign=api&width=330";
        assertEquals(url, RichAnswerUrlPolicy.normalizedForRequest(url));
        assertTrue(RichAnswerUrlPolicy.isFetchableImageUrl(url));
        serveImage(url);
        assertTrue(RemoteImageLoader.fetchPicture(context, url).loaded());
    }

    /**
     * Encoding is not a relaxation: request splitting is still refused outright.
     *
     * <p>The characters that make a second request line possible are never encoded and never sent.
     * This is the assertion that keeps the HTTP 400 fix from having quietly become a hole.
     */
    @Test public void controlCharactersAreStillRefusedRatherThanEncoded() {
        for (String url : new String[]{
                "https://cdn.example.org/a\r\nHost:evil/x.jpg",
                "https://cdn.example.org/a\nx.jpg",
                "https://cdn.example.org/a\tx.jpg",
                "https://cdn.example.org/a x.jpg",
                "https://cdn.example.org/ax.jpg"}) {
            assertEquals("[" + url + "] must be refused, not encoded",
                    "", RichAnswerUrlPolicy.normalizedForRequest(url));
            assertFalse(RichAnswerUrlPolicy.isFetchableImageUrl(url));
        }
    }

    /** And a private address is still private however it is written. */
    @Test public void encodingNeverTalksThePolicyIntoAPrivateAddress() {
        for (String url : new String[]{
                "https://127.0.0.1/Café.jpg",
                "https://192.168.0.4/日本.jpg",
                "https://localhost/a b.jpg",
                "https://user:pw@cdn.example.org/Café.jpg",
                "http://cdn.example.org/Café.jpg"}) {
            assertFalse(url + " must stay refused", RichAnswerUrlPolicy.isFetchableImageUrl(url));
        }
    }

    // ---- a page where a picture was expected --------------------------------------------------------

    /**
     * The exact real-device failure, end to end.
     *
     * <p>A Commons file page answers as HTML. Beta 1 refused it and drew "Image could not be
     * loaded" beside a working source link. Orbit now reads the picture that page declares.
     */
    @Test public void aCommonsFilePageResolvesToItsPicture() {
        String page = "https://commons.example.org/wiki/"
                + "File:Latrodectus_variolus_(Northern_Black_Widow),_F_Theridiidae.jpg";
        String picture = "https://upload.example.org/wikipedia/commons/a/a5/"
                + "Latrodectus_variolus_%28Northern_Black_Widow%29%2C_F_Theridiidae.jpg";
        serveHtml(page, "<html><head><meta property=\"og:image\" content=\"" + picture
                + "\"><title>File:Northern black widow</title></head><body>page</body></html>");
        serveImage(picture);

        RemoteImageLoader.Result result = RemoteImageLoader.fetchPicture(context, page);
        assertTrue("the declared picture must be shown", result.loaded());
        assertTrue(fake.requested.contains(picture));
    }

    /** The resolution reads only the head, and only once. */
    @Test public void pageResolutionIsBoundedToOneHopAndOneHead() {
        String first = "https://example.org/a";
        String second = "https://example.org/b";
        serveHtml(first, "<head><meta property=\"og:image\" content=\"" + second + "\"></head>");
        serveHtml(second, "<head><meta property=\"og:image\" content=\"" + first + "\"></head>");
        RemoteImageLoader.Result result = RemoteImageLoader.fetchPicture(context, first);
        assertFalse(result.loaded());
        assertEquals(RemoteImageLoader.Failure.NOT_AN_IMAGE, result.failure);
        assertTrue("two requests at most", fake.requested.size() <= 2);
    }

    // ---- codec awareness ------------------------------------------------------------------------------

    /** Formats every supported Android version can decode rank as universal. */
    @Test public void ordinaryRasterFormatsAreUniversal() {
        for (String extension : new String[]{"jpg", "jpeg", "png", "webp", "gif", "bmp"}) {
            assertEquals(extension + " must decode everywhere",
                    RichAnswerImageFormat.TIER_UNIVERSAL,
                    RichAnswerImageFormat.tierForExtension(extension));
        }
    }

    /** Documents and icons are never a photograph, whatever a server calls them. */
    @Test public void nonPictureFormatsAreUnsupported() {
        for (String extension : new String[]{"svg", "svgz", "ico", "pdf", "xml"}) {
            assertEquals(extension + " must never be chosen",
                    RichAnswerImageFormat.TIER_UNSUPPORTED,
                    RichAnswerImageFormat.tierForExtension(extension));
        }
    }

    /** AVIF tracks the platform rather than being assumed. */
    @Test public void avifFollowsWhatTheDeviceCanActuallyDecode() {
        int tier = RichAnswerImageFormat.tierForExtension("avif");
        if (RichAnswerImageFormat.supportsAvif()) {
            assertEquals(RichAnswerImageFormat.TIER_CONDITIONAL, tier);
        } else {
            assertEquals("AVIF before Android 12 must never be chosen",
                    RichAnswerImageFormat.TIER_UNSUPPORTED, tier);
            assertEquals("and it must not even score as a candidate", -1,
                    RichAnswerRelevance.score("https://cdn.example.org/photographs/robin.avif",
                            "https://example.org/birds/robin", "A robin", true));
        }
    }

    /** An address with no extension is common on CDNs and is not punished for it. */
    @Test public void anExtensionlessAddressIsStillACandidate() {
        assertEquals(RichAnswerImageFormat.TIER_CONDITIONAL,
                RichAnswerImageFormat.tierForUrl("https://cdn.example.org/media/9f2ab41c"));
        assertTrue(RichAnswerRelevance.score("https://cdn.example.org/media/9f2ab41c",
                "https://example.org/p", "A robin", true) > 0);
    }

    /** A query string does not turn into a file extension. */
    @Test public void extensionsAreReadFromThePathRatherThanTheQuery() {
        assertEquals("jpg", RichAnswerImageFormat.extensionOf(
                "https://cdn.example.org/a/b.jpg?format=svg&v=2"));
        assertEquals("png", RichAnswerImageFormat.extensionOf("https://cdn.example.org/x.png#frag"));
        assertEquals("", RichAnswerImageFormat.extensionOf("https://cdn.example.org/media/9f2ab41c"));
    }

    /** A universally decodable format outranks a conditional one, all else equal. */
    @Test public void universalFormatsOutrankConditionalOnes() {
        String page = "https://example.org/birds/robin";
        int jpeg = RichAnswerRelevance.score(
                "https://cdn.example.org/photographs/european-robin.jpg", page, "A robin", true);
        int heic = RichAnswerRelevance.score(
                "https://cdn.example.org/photographs/european-robin.heic", page, "A robin", true);
        if (RichAnswerImageFormat.supportsHeif()) {
            assertTrue("a JPEG must be preferred to a HEIC", jpeg > heic);
            assertTrue("but the HEIC is still a candidate", heic > 0);
        } else {
            assertEquals(-1, heic);
        }
    }

    /** The Accept header offers only what this device can genuinely decode. */
    @Test public void theAcceptHeaderIsHonestAboutThisDevice() {
        String accept = RichAnswerImageFormat.acceptHeader();
        assertTrue(accept.contains("image/jpeg"));
        assertTrue(accept.contains("image/png"));
        assertTrue(accept.contains("image/webp"));
        assertTrue("a host that ignores negotiation must still be able to answer",
                accept.contains("*/*"));
        assertEquals("AVIF is offered only where it can be decoded",
                RichAnswerImageFormat.supportsAvif(), accept.contains("image/avif"));
    }

    /** Orbit identifies itself truthfully and never impersonates a browser. */
    @Test public void theUserAgentIsTruthful() {
        String agent = RichAnswerPageFetcher.USER_AGENT;
        assertTrue(agent.startsWith("OrbitAssistant/"));
        assertTrue("a contact address is what large public hosts ask for",
                agent.contains("https://github.com/lpnovi/Orbit-Assistant"));
        for (String impersonation : new String[]{
                "Mozilla", "Chrome", "Safari", "AppleWebKit", "Gecko", "Edg/"}) {
            assertFalse("Orbit must not claim to be " + impersonation,
                    agent.contains(impersonation));
        }
        String loader = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/RemoteImageLoader.java");
        assertTrue("both fetchers must send the same identification",
                loader.contains("RichAnswerPageFetcher.USER_AGENT"));
    }

    // ---- candidate fallback ---------------------------------------------------------------------------

    private static final String ROBIN_PAGE = "https://example.org/birds/robin";

    /** A fetched page that declares these preview images and contains nothing else. */
    private RichAnswerPageFetcher.PageResult pageOf(String... imageUrls) {
        StringBuilder head = new StringBuilder("<head>");
        for (String url : imageUrls) {
            head.append("<meta property=\"og:image\" content=\"").append(url).append("\">");
        }
        head.append("<title>A page about robins</title></head>");
        return RichAnswerPageFetcher.parse(head.toString(), ROBIN_PAGE, 200, "text/html", 0);
    }

    /** The best usable picture on a page, with no subject words and nothing seen before. */
    private RichAnswerImage bestOf(RichAnswerPageFetcher.PageResult page,
                                   java.util.Set<String> seen) {
        return RichAnswerCoordinator.bestFrom(context, page, ROBIN_PAGE, 0,
                java.util.Collections.emptyList(), seen, new RichAnswerTrace.PageRecord());
    }

    /**
     * A page's second declared picture is used when its first cannot be shown.
     *
     * <p>Beta 1 committed to the single best-scoring candidate and abandoned the page when it
     * failed, which is how an ordinary JPEG sitting in the same head got lost behind a 403.
     */
    @Test public void aFailedFirstCandidateFallsThroughToTheNext() {
        String first = "https://cdn.example.org/photographs/robin-primary.jpg";
        String second = "https://cdn.example.org/photographs/robin-secondary.jpg";
        fake.responses.put(first,
                new RemoteImageLoader.Response(403, "text/plain", null, 0, null));
        serveImage(second);

        RichAnswerImage image = bestOf(pageOf(first, second), new LinkedHashSet<>());
        assertNotNull("the page must not lose its picture", image);
        assertEquals(second, image.imageUrl);
    }

    /** A first candidate this device cannot decode is not even attempted. */
    @Test public void anUndecodableFormatIsSkippedForOneThatWorks() {
        String unsupported = "https://cdn.example.org/photographs/robin-primary.svg";
        String usable = "https://cdn.example.org/photographs/robin-secondary.jpg";
        serveImage(usable);

        RichAnswerImage image = bestOf(pageOf(unsupported, usable), new LinkedHashSet<>());
        assertNotNull(image);
        assertEquals(usable, image.imageUrl);
        assertFalse("an unusable format must not cost a request",
                fake.requested.contains(unsupported));
    }

    /** When nothing on the page works, the page contributes nothing and the answer is unharmed. */
    @Test public void aPageWhereEveryCandidateFailsYieldsNothing() {
        String a = "https://cdn.example.org/photographs/a.jpg";
        String b = "https://cdn.example.org/photographs/b.jpg";
        fake.responses.put(a, new RemoteImageLoader.Response(403, "text/plain", null, 0, null));
        fake.responses.put(b, new RemoteImageLoader.Response(404, "text/plain", null, 0, null));

        assertNull(bestOf(pageOf(a, b), new LinkedHashSet<>()));
    }

    /** Attempts per page are bounded, so a hostile page cannot become a request storm. */
    @Test public void candidateAttemptsAreBounded() {
        List<String> many = new ArrayList<>();
        StringBuilder head = new StringBuilder("<head>");
        for (int i = 0; i < 12; i++) {
            String url = "https://cdn.example.org/photographs/candidate-" + i + ".jpg";
            many.add(url);
            head.append("<meta property=\"og:image\" content=\"").append(url).append("\">");
            fake.responses.put(url,
                    new RemoteImageLoader.Response(404, "text/plain", null, 0, null));
        }
        head.append("</head>");
        RichAnswerPageFetcher.PageResult page = RichAnswerPageFetcher.parse(
                head.toString(), "https://example.org/p", 200, "text/html", 0);

        assertNull(RichAnswerCoordinator.bestFrom(context, page, "https://example.org/p", 0,
                java.util.Collections.emptyList(), new LinkedHashSet<>(),
                new RichAnswerTrace.PageRecord()));
        assertTrue("at most the declared number of attempts",
                fake.requested.size() <= RichAnswerCoordinator.MAX_FETCHED_CANDIDATES_PER_PAGE);
        assertTrue("and a page cannot declare unbounded candidates anyway",
                page.preview.imageUrls.size() <= RichAnswerPageMetadata.MAX_CANDIDATES);
    }

    /** A picture already used earlier in the same answer is not used again. */
    @Test public void anAlreadyUsedPictureIsNotRepeated() {
        String only = "https://cdn.example.org/photographs/robin.jpg";
        serveImage(only);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        seen.add(only);
        assertNull(bestOf(pageOf(only), seen));
    }

    /** Ranking is by score, and stable when scores tie. */
    @Test public void candidatesAreRankedBestFirstAndStably() {
        String logo = "https://cdn.example.org/logo.png";
        String good = "https://cdn.example.org/photographs/european-robin-in-snow.jpg";
        List<String> ranked = rankedUrls(logo, good);
        assertFalse("chrome must never be offered", ranked.contains(logo));
        assertEquals(1, ranked.size());
        assertEquals(good, ranked.get(0));
        assertEquals(ranked, rankedUrls(logo, good));
    }

    private List<String> rankedUrls(String... declared) {
        List<String> out = new ArrayList<>();
        for (RichAnswerCoordinator.Ranked ranked : RichAnswerCoordinator.rank(pageOf(declared),
                ROBIN_PAGE, java.util.Collections.emptyList(), new LinkedHashSet<>(),
                new RichAnswerTrace.PageRecord())) {
            out.add(ranked.candidate.url);
        }
        return out;
    }
}
