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

/** Bounded, credential-free loader for untrusted public HTTPS response images. */
public final class RemoteImageLoader {
    public interface Callback { void onComplete(Bitmap bitmap, String error); }

    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_DIMENSION = 1800;
    private static final long MAX_DISK_BYTES = 24L * 1024L * 1024L;
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
     * <p>Separate from {@link #load} because the Rich Answer path has a decision to make that the
     * Markdown path does not: a picture that turns out to be a 48-pixel icon is refused rather than
     * drawn small, and refusing it needs the decoded bounds. Blocking, and background threads only.
     *
     * @return the decoded bitmap, or null when the address is refused, the fetch fails, the content
     *         is not an image, or what came back is too small or too oddly shaped to be worth
     *         showing. Null is always a clean outcome: the answer keeps its text.
     */
    static Bitmap fetchForRichAnswer(Context context, String url) {
        if (context == null || !RichAnswerUrlPolicy.isFetchableImageUrl(url)) return null;
        try {
            Context app = context.getApplicationContext();
            File cache = cacheFile(app, url);
            Bitmap bitmap = null;
            if (cache.isFile() && cache.length() > 0 && cache.length() <= MAX_BYTES) {
                bitmap = decode(cache);
                if (bitmap != null) cache.setLastModified(System.currentTimeMillis());
            }
            if (bitmap == null) {
                if (!RichAnswerUrlPolicy.resolvesToPublicHost(url)) return null;
                byte[] bytes = download(url);
                try (FileOutputStream output = new FileOutputStream(cache)) { output.write(bytes); }
                bitmap = decode(cache);
                trimDiskCache(cache.getParentFile());
            }
            if (bitmap == null) return null;
            if (!RichAnswerRelevance.hasUsefulDimensions(bitmap.getWidth(), bitmap.getHeight())) {
                return null;
            }
            MEMORY.put(url, bitmap);
            return bitmap;
        } catch (Exception ignored) {
            return null;
        }
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

    public static void load(Context context, String url, Callback callback) {
        Bitmap cached = MEMORY.get(url);
        if (cached != null && !cached.isRecycled()) {
            MAIN.post(() -> callback.onComplete(cached, ""));
            return;
        }
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            Bitmap bitmap = null;
            String error = "Image could not be loaded";
            try {
                if (!isAllowedPublicHttpsUrl(url)) throw new SecurityException("Blocked image address");
                File cache = cacheFile(app, url);
                if (cache.isFile() && cache.length() > 0 && cache.length() <= MAX_BYTES) {
                    bitmap = decode(cache);
                    if (bitmap != null) cache.setLastModified(System.currentTimeMillis());
                }
                if (bitmap == null) {
                    byte[] bytes = download(url);
                    try (FileOutputStream output = new FileOutputStream(cache)) { output.write(bytes); }
                    bitmap = decode(cache);
                    trimDiskCache(cache.getParentFile());
                }
                if (bitmap == null) throw new IllegalArgumentException("Invalid image data");
                MEMORY.put(url, bitmap);
                error = "";
            } catch (SecurityException e) {
                error = "Orbit blocked this private or unsafe image address";
            } catch (Exception ignored) {}
            Bitmap result = bitmap;
            String finalError = error;
            MAIN.post(() -> callback.onComplete(result, finalError));
        });
    }

    private static byte[] download(String initial) throws Exception {
        String current = initial;
        for (int redirect = 0; redirect <= RichAnswerUrlPolicy.MAX_REDIRECTS; redirect++) {
            // Revalidated at every hop, never only at the first. A redirect chain that starts on a
            // public host and ends on this device's own network is exactly what one check at the
            // top would let through.
            if (!isAllowedPublicHttpsUrl(current)) throw new SecurityException();
            HttpURLConnection connection = (HttpURLConnection) URI.create(current).toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(12000);
            connection.setRequestProperty("Accept", "image/*");
            connection.setRequestProperty("User-Agent", "Orbit-Assistant-Image/1.0");
            connection.setRequestProperty("Cookie", "");
            connection.setUseCaches(true);
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                String next = RichAnswerUrlPolicy.redirectTarget(current, location);
                if (next.isEmpty()) throw new SecurityException();
                current = next;
                continue;
            }
            if (status < 200 || status >= 300) {
                connection.disconnect();
                throw new IllegalStateException("HTTP " + status);
            }
            String type = connection.getContentType();
            if (type == null || !type.toLowerCase(Locale.US).startsWith("image/")) {
                connection.disconnect();
                throw new IllegalArgumentException("Not an image");
            }
            int length = connection.getContentLength();
            if (length > MAX_BYTES) {
                connection.disconnect();
                throw new IllegalArgumentException("Image too large");
            }
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream(
                         length > 0 ? Math.min(length, MAX_BYTES) : 32 * 1024)) {
                byte[] buffer = new byte[16 * 1024];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_BYTES) throw new IllegalArgumentException("Image too large");
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            } finally {
                connection.disconnect();
            }
        }
        throw new SecurityException("Too many redirects");
    }

    private static Bitmap decode(File file) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (FileInputStream input = new FileInputStream(file)) {
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_DIMENSION) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (FileInputStream input = new FileInputStream(file)) {
            return BitmapFactory.decodeStream(input, null, options);
        }
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
