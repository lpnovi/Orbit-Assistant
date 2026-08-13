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
import java.net.IDN;
import java.net.Inet6Address;
import java.net.InetAddress;
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

    public static boolean hasSafeHttpsSyntax(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null ||
                    uri.getHost() == null || uri.getHost().trim().isEmpty()) return false;
            String host = IDN.toASCII(uri.getHost()).toLowerCase(Locale.US);
            return !host.equals("localhost") && !host.endsWith(".localhost") &&
                    !host.endsWith(".local") && !host.endsWith(".internal");
        } catch (Exception ignored) { return false; }
    }

    public static boolean isAllowedPublicHttpsUrl(String value) {
        try {
            if (!hasSafeHttpsSyntax(value)) return false;
            URI uri = URI.create(value == null ? "" : value.trim());
            String host = IDN.toASCII(uri.getHost()).toLowerCase(Locale.US);
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) return false;
            for (InetAddress address : addresses) if (!isPublic(address)) return false;
            return true;
        } catch (Exception ignored) {
            return false;
        }
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
        for (int redirect = 0; redirect <= 4; redirect++) {
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
                if (location == null || location.trim().isEmpty()) throw new SecurityException();
                current = URI.create(current).resolve(location).toString();
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

    private static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() ||
                address.isLinkLocalAddress() || address.isSiteLocalAddress() ||
                address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int a = bytes[0] & 0xff;
            int b = bytes[1] & 0xff;
            int d = bytes[2] & 0xff;
            if (a == 0 || a == 10 || a == 127 || a >= 224 ||
                    (a == 100 && b >= 64 && b <= 127) ||
                    (a == 169 && b == 254) ||
                    (a == 172 && b >= 16 && b <= 31) ||
                    (a == 192 && ((b == 0 && d == 0) || b == 168)) ||
                    (a == 198 && (b == 18 || b == 19 || (b == 51 && d == 100))) ||
                    (a == 203 && b == 0 && d == 113)) return false;
        }
        if (address instanceof Inet6Address && bytes.length == 16) {
            int first = bytes[0] & 0xff;
            if ((first & 0xfe) == 0xfc) return false;
        }
        return true;
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
