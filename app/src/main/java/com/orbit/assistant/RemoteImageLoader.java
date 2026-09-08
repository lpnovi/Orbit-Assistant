package com.orbit.assistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The one bounded, credential-free way Orbit fetches a picture from the public web.
 *
 * <p>Both image paths come through here - the structured Rich Answer image and the older
 * model-written Markdown one - and that is deliberate. Two HTTP implementations means two redirect
 * policies, two MIME rules and two sets of request-forgery checks, and the day they disagree is the
 * day one of them is wrong. There is one transport, and it is this.
 *
 * <p><b>What Beta 2 changed, and why.</b> Beta 1's transport was safe and too literal about the
 * web. Real-device testing against Wikimedia produced a card reading "Image could not be loaded"
 * for a picture that was perfectly fine, and reproducing the request exactly showed three separate
 * causes:
 *
 * <ul>
 *   <li>The address was a Commons <em>file page</em> rather than a raw image, so the server
 *       correctly answered {@code text/html} and Orbit correctly refused it - having asked the
 *       wrong question. A page that describes a picture now gets resolved, once, into the picture
 *       it declares.</li>
 *   <li>A filename containing an accent or a non-Latin character went onto the wire as raw bytes
 *       and the CDN answered <b>HTTP 400</b>. Addresses are now encoded the way a browser encodes
 *       them, in {@link RichAnswerUrlPolicy#normalizedForRequest}.</li>
 *   <li>A content type carrying parameters, or an ordinary JPEG served as
 *       {@code application/octet-stream}, was refused for not starting with {@code image/}.</li>
 * </ul>
 *
 * <p><b>None of it weakened the policy.</b> Every fetch still goes through
 * {@link RichAnswerUrlPolicy}: https only, no credentials, no {@code file:}/{@code content:}/
 * {@code javascript:}/{@code intent:}, loopback and link-local and RFC1918 and unique-local refused,
 * literal private addresses refused before DNS, and every redirect hop revalidated against all of
 * it. A website failing to load was never a reason to open any of that up, and none of it moved.
 */
public final class RemoteImageLoader {

    /**
     * Why a fetch did not produce a picture, in the only words Orbit will say about it.
     *
     * <p>Beta 1 had one message for every failure, which is fine for the user and useless for
     * working out what went wrong on somebody's phone. These are categories rather than
     * exceptions: each one names a class of outcome, none of them carries a URL, a header, a
     * server message or anything else from the network, and the two that describe a refusal say so
     * without describing the address that was refused.
     */
    public enum Failure {
        NONE(""),
        BLOCKED("Orbit blocked this private or unsafe image address"),
        REDIRECT_BLOCKED("Redirect blocked"),
        TOO_MANY_REDIRECTS("Too many redirects"),
        HTTP_ERROR("Image host refused the request"),
        NOT_AN_IMAGE("Not an image"),
        UNSUPPORTED_FORMAT("Unsupported image format"),
        TOO_LARGE("Image too large"),
        DECODE_FAILED("Image could not be read"),
        TIMEOUT("Image host did not respond"),
        NETWORK("Image could not be loaded");

        /** The short line a surface may show. Never a URL and never a server's own words. */
        public final String message;

        Failure(String message) { this.message = message; }

        public boolean failed() { return this != NONE; }
    }

    /**
     * What one fetch produced: a picture, or a reason there is not one - plus what was observed on
     * the way.
     *
     * <p><b>Nothing useful is thrown away here any more.</b> Beta 2 reduced every failed download
     * to a category, which was already a large improvement on Beta 1's single {@code null}, and was
     * still not enough to explain a real-device failure: "not an image" does not say whether the
     * server sent a PDF or an HTML error page, and "could not be read" does not say whether four
     * bytes arrived or four megabytes. The transport knows all of it, so it now keeps all of it.
     *
     * <p>The extra fields are for {@link RichAnswerTrace} and for nothing else. No surface shows
     * them, no message text is built from them, and none of them carries an address, a header or a
     * server's own words.
     */
    public static final class Result {
        public final Bitmap bitmap;
        public final Failure failure;
        /**
         * The HTTP status behind {@link Failure#HTTP_ERROR}, or 0.
         *
         * <p>Kept because "403" and "404" mean genuinely different things to somebody testing on a
         * real device - one is a host refusing Orbit, the other is an address that is simply wrong.
         */
        public final int status;
        /** The content type the server declared, base type only, or empty. */
        public final String contentType;
        /** How many bytes of body were actually read before this ended. */
        public final int bytesRead;
        /** How many redirects were followed and revalidated. */
        public final int redirects;
        /** Decoded width, or 0 when nothing decoded. A fact rather than a claim. */
        public final int decodedWidth;
        /** Decoded height, or 0. */
        public final int decodedHeight;

        Result(Bitmap bitmap, Failure failure, int status, String contentType, int bytesRead,
               int redirects) {
            this.bitmap = bitmap;
            this.failure = failure == null ? Failure.NONE : failure;
            this.status = status;
            this.contentType = contentType == null ? "" : contentType;
            this.bytesRead = Math.max(0, bytesRead);
            this.redirects = Math.max(0, redirects);
            this.decodedWidth = bitmap == null ? 0 : bitmap.getWidth();
            this.decodedHeight = bitmap == null ? 0 : bitmap.getHeight();
        }

        Result(Bitmap bitmap, Failure failure, int status) {
            this(bitmap, failure, status, "", 0, 0);
        }

        static Result ok(Bitmap bitmap) { return new Result(bitmap, Failure.NONE, 0); }

        static Result failed(Failure failure) { return new Result(null, failure, 0); }

        static Result http(int status) { return new Result(null, Failure.HTTP_ERROR, status); }

        public boolean loaded() { return bitmap != null && !failure.failed(); }

        /** The same outcome with the transport detail attached. Used by the transport itself. */
        Result observing(String contentType, int bytesRead, int redirects) {
            return new Result(bitmap, failure, status, RichAnswerImageFormat.baseType(contentType),
                    bytesRead, redirects);
        }

        /** The line a surface shows, with the status folded in where there is one. */
        public String describe() {
            if (!failure.failed()) return "";
            return status > 0 ? "HTTP " + status : failure.message;
        }
    }

    public interface Callback { void onComplete(Bitmap bitmap, String error); }

    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_DIMENSION = 1800;
    private static final long MAX_DISK_BYTES = 24L * 1024L * 1024L;
    /**
     * How many times one fetch may be handed a web page instead of a picture.
     *
     * <p>Exactly one. A page that describes a picture is resolved into that picture; a page that
     * resolves to another page is somebody's redirect loop wearing a different hat, and Orbit stops.
     */
    private static final int MAX_PAGE_RESOLUTIONS = 1;

    /**
     * How Orbit identifies itself when fetching a picture.
     *
     * <p>Truthful, and deliberately not a browser. Several large public hosts - Wikimedia among
     * them - refuse requests carrying no User-Agent or an anonymous one, and asking for a contact
     * address in it is a documented condition of using them politely rather than a trick to get
     * past a filter. Orbit says what it is and where it comes from; it never claims to be Chrome.
     */
    private static final String USER_AGENT = RichAnswerPageFetcher.USER_AGENT;

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(16 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };

    private RemoteImageLoader() {}

    /**
     * Syntax only: https, a real public-looking host, and no credentials.
     *
     * <p>Delegated to {@link RichAnswerUrlPolicy} rather than implemented twice. Two copies of a
     * request-forgery rule is how one of them ends up a hop behind the other, and this loader and
     * the Rich Answer fetches now guard the same thing.
     */
    public static boolean hasSafeHttpsSyntax(String value) {
        return RichAnswerUrlPolicy.hasSafeFetchSyntax(value);
    }

    /** The same question, plus every address the host actually resolves to. Performs DNS. */
    public static boolean isAllowedPublicHttpsUrl(String value) {
        return RichAnswerUrlPolicy.resolvesToPublicHost(value);
    }

    /**
     * A picture fetched for a Rich Answer, decoded and measured.
     *
     * <p>Separate from {@link #load} because the Rich Answer path keeps what it fetched in memory
     * for the redraw that follows immediately. Blocking, and background threads only.
     *
     * <p><b>It no longer judges what arrived.</b> Beta 2 refused a picture here for being too small
     * and rewrote the result as {@code UNSUPPORTED_FORMAT} - which threw away the decoded bounds
     * that were the entire reason for the refusal, so Diagnostics could never say "this was a
     * 60x60 icon". Whether a picture is big enough is a judgement about content, it belongs to
     * {@link RichAnswerCoordinator} with the other content rules, and it is made there against the
     * dimensions this now returns intact.
     *
     * @return the picture, or the category of what went wrong, in either case carrying the content
     *         type, byte count, redirect count and decoded size that were observed. A failure is
     *         always clean: the answer keeps its text, and the caller may try another candidate.
     */
    static Result fetchForRichAnswer(Context context, String url) {
        if (context == null) return Result.failed(Failure.BLOCKED);
        if (!RichAnswerUrlPolicy.isFetchableImageUrl(url)) return Result.failed(Failure.BLOCKED);
        Context app = context.getApplicationContext();
        Result result = fetchPicture(app, url);
        if (result.loaded()) MEMORY.put(url, result.bitmap);
        return result;
    }

    /**
     * A picture already decoded in memory, or null. Touches no disk and starts no fetch.
     *
     * <p>Exists so a rich image that has just been discovered can be drawn in the same frame the
     * answer redraws in, with no placeholder at all. Deliberately memory only: reading and decoding
     * a file is not something a view builder may do on the main thread, so a cache miss here means
     * the ordinary asynchronous load rather than a stall.
     */
    static Bitmap memoryCached(String url) {
        if (url == null || url.isEmpty()) return null;
        Bitmap memory = MEMORY.get(url);
        return memory != null && !memory.isRecycled() ? memory : null;
    }

    /** The asynchronous path both the Markdown renderer and the Rich Answer card draw through. */
    public static void load(Context context, String url, Callback callback) {
        loadDetailed(context, url, result ->
                callback.onComplete(result.bitmap, result.describe()));
    }

    /** What {@link #load} does, with the failure category kept rather than flattened to a string. */
    public static void loadDetailed(Context context, String url, java.util.function.Consumer<Result> callback) {
        Bitmap cached = memoryCached(url);
        if (cached != null) {
            MAIN.post(() -> callback.accept(Result.ok(cached)));
            return;
        }
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            Result result = fetchPicture(app, url);
            if (result.loaded()) MEMORY.put(url, result.bitmap);
            MAIN.post(() -> callback.accept(result));
        });
    }

    /**
     * The cache-then-network path, shared by every caller. Blocking.
     *
     * <p>A cached file that no longer decodes is <em>deleted</em> rather than merely stepped past.
     * Beta 1 left it there, so a picture whose bytes were truncated by a killed process or whose
     * format this device cannot read was re-downloaded on every single draw, forever, and the disk
     * entry sat in the way of the size budget the whole time. Only the entry for this exact address
     * is removed; nothing else in the cache is touched.
     */
    /**
     * The transport itself: cache, then network, then decode. Blocking.
     *
     * <p>Deliberately separate from {@link #fetchForRichAnswer}, which layers a judgement about
     * <em>content</em> on top - whether what arrived is big enough to be worth drawing. That is a
     * different question with a different answer, and keeping them apart is what lets each be
     * tested for what it actually decides.
     */
    static Result fetchPicture(Context app, String url) {
        File cache = null;
        try {
            cache = cacheFile(app, url);
            if (cache.isFile()) {
                if (isUsableCacheLength(cache.length())) {
                    Bitmap fromCache = decode(cache);
                    if (fromCache != null) {
                        cache.setLastModified(System.currentTimeMillis());
                        return record(app, Result.ok(fromCache));
                    }
                }
                // Unusable: empty, past the ceiling, or bytes that will not decode. All three are
                // what a process killed mid-write or a format this device cannot read leaves
                // behind, and Beta 1 left every one of them on disk forever - sitting inside the
                // size budget while every draw paid for a fresh download. Only this address's own
                // entry is ever removed.
                deleteQuietly(cache);
            }
        } catch (Exception ignored) {
            // A cache that cannot be reached is not a reason to refuse the picture.
        }

        Download download;
        try {
            download = download(url);
        } catch (FetchException e) {
            Result result = e.status > 0 ? Result.http(e.status) : Result.failed(e.failure);
            return record(app, result.observing(e.contentType, e.bytesRead, e.redirects));
        } catch (java.net.SocketTimeoutException e) {
            return record(app, Result.failed(Failure.TIMEOUT));
        } catch (Exception e) {
            return record(app, Result.failed(Failure.NETWORK));
        }

        // Decoded before it is stored, never after. The bytes themselves are the only authority on
        // whether this is a picture, and proving it first means undecodable bytes never reach the
        // disk at all - which is strictly better than writing them and deleting them again.
        Bitmap bitmap = decode(download.bytes);
        if (bitmap == null) {
            return record(app, Result.failed(
                    RichAnswerImageFormat.isDecodableContentType(download.contentType)
                            ? Failure.DECODE_FAILED : Failure.UNSUPPORTED_FORMAT)
                    .observing(download.contentType, download.bytes.length, download.redirects));
        }
        try {
            if (cache == null) cache = cacheFile(app, url);
            try (FileOutputStream output = new FileOutputStream(cache)) {
                output.write(download.bytes);
            }
            trimDiskCache(cache.getParentFile());
        } catch (Exception ignored) {
            // The picture is in hand; only keeping it failed. A full disk costs the next draw a
            // second fetch and costs this one nothing.
            deleteQuietly(cache);
        }
        return record(app, Result.ok(bitmap)
                .observing(download.contentType, download.bytes.length, download.redirects));
    }

    /** Records one outcome for Diagnostics and hands it straight back. */
    private static Result record(Context app, Result result) {
        RichAnswerImageStatus.record(app, result);
        return result;
    }

    /**
     * Whether a cached file is worth reading at all.
     *
     * <p>An entry outside this range is not a picture: zero bytes is what a process killed between
     * creating a file and writing it leaves behind, and anything past the ceiling could not have
     * been written by this loader.
     */
    static boolean isUsableCacheLength(long length) {
        return length > 0 && length <= MAX_BYTES;
    }

    /** What a completed fetch returned, and what was observed getting it. */
    private static final class Download {
        final byte[] bytes;
        final String contentType;
        final int redirects;
        Download(byte[] bytes, String contentType, int redirects) {
            this.bytes = bytes;
            this.contentType = contentType == null ? "" : contentType;
            this.redirects = redirects;
        }
    }

    /**
     * One HTTP response, reduced to the four things this loader actually reads.
     *
     * <p>Deliberately not {@link HttpURLConnection}. The loader's interesting behaviour is its
     * decision table - which statuses redirect, which content types are pictures, where the byte
     * ceiling bites, what a refused redirect does - and none of that should need a socket to
     * exercise. A response is data, so it is a small value type that a test can simply construct.
     */
    static final class Response {
        final int status;
        final String contentType;
        final String location;
        final long contentLength;
        final InputStream body;

        Response(int status, String contentType, String location, long contentLength,
                 InputStream body) {
            this.status = status;
            this.contentType = contentType;
            this.location = location;
            this.contentLength = contentLength;
            this.body = body;
        }
    }

    /**
     * Where a picture's bytes come from, and whether Orbit is allowed to ask.
     *
     * <p>A seam with exactly one production implementation, which is installed permanently and is
     * never replaced by anything in the app. It exists so the loader's rules can be tested against
     * a 403, a redirect chain, a chunked body that runs past the ceiling, and a page returned where
     * a picture was expected - none of which can be produced reliably by contacting a real server,
     * and all of which are exactly what broke on a real device.
     *
     * <p><b>The address check is part of the seam on purpose.</b> Splitting it out would mean a
     * test could exercise the transport while silently skipping the check that guards it. Keeping
     * them together means the production transport is the only thing that can ever answer both, and
     * it answers the second by asking {@link RichAnswerUrlPolicy} exactly as before.
     */
    interface Transport {
        /** Whether every address this host resolves to is on the public internet. */
        boolean allowsHost(String url);

        /** Opens the address with Orbit's own headers applied. Never follows redirects itself. */
        Response open(String url) throws Exception;
    }

    /** The real network. The only implementation the app ever installs. */
    private static final Transport NETWORK = new Transport() {
        @Override public boolean allowsHost(String url) {
            return RichAnswerUrlPolicy.resolvesToPublicHost(url);
        }

        @Override public Response open(String url) throws Exception {
            HttpURLConnection connection =
                    (HttpURLConnection) URI.create(url).toURL().openConnection();
            // Off, so Orbit follows every hop itself and can revalidate each one. This is the
            // whole reason the redirect handling below is hand-written.
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(12000);
            connection.setRequestProperty("Accept", RichAnswerImageFormat.acceptHeader());
            connection.setRequestProperty("Accept-Language", "en;q=0.9,*;q=0.5");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            // Explicitly emptied rather than merely not set, so a JVM-wide CookieHandler cannot
            // attach anything this device holds for the host. Confirmed harmless against real
            // public CDNs during the Beta 2 diagnosis.
            connection.setRequestProperty("Cookie", "");
            connection.setUseCaches(true);
            connection.setDoOutput(false);
            int status = connection.getResponseCode();
            InputStream body = status >= 200 && status < 300 ? connection.getInputStream() : null;
            return new Response(status, connection.getContentType(),
                    connection.getHeaderField("Location"), connection.getContentLengthLong(),
                    body == null ? null : new ClosingStream(body, connection));
        }
    };

    /** Keeps a connection alive exactly as long as the body being read from it. */
    private static final class ClosingStream extends java.io.FilterInputStream {
        private final HttpURLConnection connection;
        ClosingStream(InputStream in, HttpURLConnection connection) {
            super(in);
            this.connection = connection;
        }
        @Override public void close() throws java.io.IOException {
            try { super.close(); } finally { connection.disconnect(); }
        }
    }

    private static volatile Transport transport = NETWORK;

    /** Installs a transport for one test, and returns the one it replaced. Tests only. */
    static Transport installTransportForTest(Transport replacement) {
        Transport previous = transport;
        transport = replacement == null ? NETWORK : replacement;
        return previous;
    }

    /** Whether the real network transport is the one currently installed. */
    static boolean usingNetworkTransport() { return transport == NETWORK; }

    /**
     * A fetch that ended in a category rather than in bytes, carrying what was seen on the way.
     *
     * <p>The observations travel with the failure rather than being discarded at the throw, which
     * is the difference between Diagnostics reading "not an image" and reading "not an image ·
     * HTTP 200 · application/pdf · 41 KB · 2 redirects". The first needs a guess; the second does
     * not.
     */
    private static final class FetchException extends Exception {
        final Failure failure;
        final int status;
        String contentType = "";
        int bytesRead;
        int redirects;

        FetchException(Failure failure) { this(failure, 0); }

        FetchException(Failure failure, int status) {
            super(failure.name());
            this.failure = failure;
            this.status = status;
        }

        FetchException observing(String contentType, int bytesRead, int redirects) {
            this.contentType = contentType == null ? "" : contentType;
            this.bytesRead = bytesRead;
            this.redirects = redirects;
            return this;
        }
    }

    private static Download download(String initial) throws Exception {
        return download(initial, 0);
    }

    /**
     * One picture off the public web, with every hop revalidated.
     *
     * @param pageResolutions how many times this fetch has already been handed a web page instead
     *                        of a picture. Bounded by {@link #MAX_PAGE_RESOLUTIONS}.
     */
    private static Download download(String initial, int pageResolutions) throws Exception {
        String current = initial;
        for (int redirect = 0; redirect <= RichAnswerUrlPolicy.MAX_REDIRECTS; redirect++) {
            // Revalidated at every hop, never only at the first. A redirect chain that starts on a
            // public host and ends on this device's own network is exactly what one check at the
            // top would let through.
            if (!transport.allowsHost(current)) {
                throw new FetchException(Failure.BLOCKED).observing("", 0, redirect);
            }
            String request = RichAnswerUrlPolicy.normalizedForRequest(current);
            if (request.isEmpty()) {
                throw new FetchException(Failure.BLOCKED).observing("", 0, redirect);
            }

            Response response = transport.open(request);
            InputStream body = response == null ? null : response.body;
            try {
                if (response == null) {
                    throw new FetchException(Failure.NETWORK).observing("", 0, redirect);
                }
                if (response.status >= 300 && response.status < 400) {
                    // 301, 302, 303, 307 and 308 alike: Orbit follows them itself so that each one
                    // can be checked, which is the whole reason automatic redirects are off.
                    String next = RichAnswerUrlPolicy.redirectTarget(current, response.location);
                    if (next.isEmpty()) {
                        throw new FetchException(Failure.REDIRECT_BLOCKED)
                                .observing(response.contentType, 0, redirect);
                    }
                    current = next;
                    continue;
                }
                if (response.status < 200 || response.status >= 300) {
                    throw new FetchException(Failure.HTTP_ERROR, response.status)
                            .observing(response.contentType, 0, redirect);
                }

                String type = response.contentType;
                if (RichAnswerImageFormat.isHtmlContentType(type)) {
                    // A page, not a picture. This is what a Commons "File:" address is, and what a
                    // model writes far more often than a raw image URL. Reading the preview image
                    // the page declares about itself turns a dead card into the right picture, and
                    // it is the same bounded, policy-checked read Rich Answers already performs -
                    // not a special case for one website.
                    if (pageResolutions >= MAX_PAGE_RESOLUTIONS) {
                        throw new FetchException(Failure.NOT_AN_IMAGE)
                                .observing(type, 0, redirect);
                    }
                    // The markup is already arriving on this connection, so it is read here rather
                    // than fetched a second time: one request, one transport, and the bounded read
                    // stops at </head> exactly as the Rich Answer metadata reader does.
                    String declared = declaredImageOf(readHead(body), current);
                    closeQuietly(body);
                    body = null;
                    if (declared.isEmpty()) {
                        throw new FetchException(Failure.NOT_AN_IMAGE).observing(type, 0, redirect);
                    }
                    return download(declared, pageResolutions + 1);
                }
                if (!RichAnswerImageFormat.isDecodableContentType(type)) {
                    throw new FetchException(RichAnswerImageFormat.baseType(type).startsWith("image/")
                            ? Failure.UNSUPPORTED_FORMAT : Failure.NOT_AN_IMAGE)
                            .observing(type, 0, redirect);
                }

                // A declared length that is already past the ceiling saves reading a byte of it.
                if (response.contentLength > MAX_BYTES) {
                    throw new FetchException(Failure.TOO_LARGE).observing(type, 0, redirect);
                }
                if (body == null) {
                    throw new FetchException(Failure.NETWORK).observing(type, 0, redirect);
                }
                try (ByteArrayOutputStream output = new ByteArrayOutputStream(
                        response.contentLength > 0
                                ? (int) Math.min(response.contentLength, MAX_BYTES) : 32 * 1024)) {
                    byte[] buffer = new byte[16 * 1024];
                    long total = 0;
                    int read;
                    while ((read = body.read(buffer)) != -1) {
                        total += read;
                        // A declared length is only a claim. This is the bound that actually holds,
                        // and it is what stops a chunked response streaming without end.
                        if (total > MAX_BYTES) {
                            throw new FetchException(Failure.TOO_LARGE)
                                    .observing(type, (int) Math.min(total, Integer.MAX_VALUE), redirect);
                        }
                        output.write(buffer, 0, read);
                    }
                    return new Download(output.toByteArray(), type, redirect);
                }
            } finally {
                closeQuietly(body);
            }
        }
        throw new FetchException(Failure.TOO_MANY_REDIRECTS)
                .observing("", 0, RichAnswerUrlPolicy.MAX_REDIRECTS);
    }

    private static void closeQuietly(InputStream stream) {
        try { if (stream != null) stream.close(); }
        catch (Exception ignored) {}
    }

    /**
     * The picture a web page declares about itself, or empty.
     *
     * <p>Reuses the Rich Answer metadata reader rather than adding a second one: same bounded
     * read, same {@code </head>} stop, same refusal of anything the URL policy will not fetch. The
     * result is only ever used as the next address for this same fetch, so it inherits every check
     * the first address had to pass.
     */
    private static String declaredImageOf(String html, String pageUrl) {
        try {
            RichAnswerPageMetadata.Preview preview = RichAnswerPageMetadata.parse(html, pageUrl);
            for (String candidate : preview.imageUrls) {
                // A page's declaration is untrusted like everything else: the format has to be one
                // this device can read, and the address has to pass the same policy the original
                // did. Nothing is inherited just because a page said it.
                if (RichAnswerImageFormat.tierForUrl(candidate)
                        == RichAnswerImageFormat.TIER_UNSUPPORTED) continue;
                if (RichAnswerUrlPolicy.isFetchableImageUrl(candidate)) return candidate;
            }
        } catch (Exception ignored) {}
        return "";
    }

    /**
     * As much of a document as it takes to find its head, and no more.
     *
     * <p>Bounded by {@link RichAnswerPageFetcher#MAX_HEAD_BYTES} and stopping the moment the head
     * closes. Deliberately the small ceiling rather than the page reader's larger one: this caller
     * wants a declaration, not an article, and reading half a megabyte to find something that
     * arrived in the first packet would be paying an enormous cost for nothing.
     */
    private static String readHead(InputStream body) throws Exception {
        if (body == null) return "";
        StringBuilder out = new StringBuilder(8192);
        java.io.Reader reader =
                new java.io.InputStreamReader(body, java.nio.charset.StandardCharsets.UTF_8);
        char[] buffer = new char[4096];
        int read;
        while ((read = reader.read(buffer)) != -1) {
            out.append(buffer, 0, read);
            if (out.length() >= RichAnswerPageFetcher.MAX_HEAD_BYTES) break;
            if (out.indexOf("</head") >= 0 || out.indexOf("</HEAD") >= 0) break;
        }
        return out.toString();
    }

    private static Bitmap decode(File file) {
        try (FileInputStream bounds = new FileInputStream(file)) {
            BitmapFactory.Options measured = new BitmapFactory.Options();
            measured.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(bounds, null, measured);
            int sample = sampleSizeFor(measured.outWidth, measured.outHeight);
            if (sample <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (FileInputStream input = new FileInputStream(file)) {
                return BitmapFactory.decodeStream(input, null, options);
            }
        } catch (Exception ignored) {
            return null;
        } catch (OutOfMemoryError ignored) {
            // A decode that will not fit is a picture Orbit does not show, never a crashed chat.
            return null;
        }
    }

    /** The same decode for bytes that never reached the disk. */
    private static Bitmap decode(byte[] bytes) {
        try {
            BitmapFactory.Options measured = new BitmapFactory.Options();
            measured.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, measured);
            int sample = sampleSizeFor(measured.outWidth, measured.outHeight);
            if (sample <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (Exception | OutOfMemoryError ignored) {
            return null;
        }
    }

    /**
     * How far down a picture has to be sampled to be worth allocating, or 0 to refuse it.
     *
     * <p>The refusal is the important half. A file can declare dimensions far beyond anything a
     * phone should allocate, and a decode bomb is exactly a small download claiming to be an
     * enormous picture; sampling brings an honest large photograph inside {@link #MAX_DIMENSION},
     * and a declared size past {@link #MAX_DECLARED_DIMENSION} is refused before a single pixel is
     * allocated.
     */
    static int sampleSizeFor(int width, int height) {
        if (width <= 0 || height <= 0) return 0;
        if (width > MAX_DECLARED_DIMENSION || height > MAX_DECLARED_DIMENSION) return 0;
        int sample = 1;
        while (Math.max(width / sample, height / sample) > MAX_DIMENSION) sample *= 2;
        return sample;
    }

    /** The largest a picture may claim to be before Orbit will not decode it at all. */
    static final int MAX_DECLARED_DIMENSION = 20000;

    private static void deleteQuietly(File file) {
        try { if (file != null && file.isFile()) file.delete(); }
        catch (Exception ignored) {}
    }

    private static File cacheFile(Context context, String url) throws Exception {
        File dir = new File(context.getCacheDir(), "orbit_response_images");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("No cache directory");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder name = new StringBuilder();
        for (byte b : hash) name.append(String.format(Locale.US, "%02x", b));
        return new File(dir, name + ".img");
    }

    private static void trimDiskCache(File dir) {
        if (dir == null) return;
        File[] files = dir.listFiles(File::isFile);
        if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        long total = 0;
        for (File file : files) total += file.length();
        for (File file : files) {
            if (total <= MAX_DISK_BYTES) break;
            long length = file.length();
            if (file.delete()) total -= length;
        }
    }
}
