package com.orbit.assistant;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns whatever an Android picker or an external Share hands back into one ordered, deduplicated
 * list of URIs.
 *
 * <p>There is no single place a selection arrives. A picker returning one item puts it in
 * {@code getData()}; the same picker returning several puts them in {@code getClipData()}; Android's
 * photo picker can populate both, with the first item appearing twice; a share of several images
 * arrives as a {@code Parcelable} list under {@code EXTRA_STREAM}, and a share of one arrives as a
 * single {@code Parcelable} under the same key. Reading only one of those is how a four-photo
 * selection becomes a one-photo message.
 *
 * <p>Order is the user's order and is preserved exactly: the first time a URI is seen fixes its
 * position, and seeing it again through another field changes nothing. Identity is the normalized
 * URI, never the filename - two different photos routinely share {@code IMG_0042.jpg}, and treating
 * that as identity would silently drop one of them.
 *
 * <p>Everything here treats its input as hostile. An external app can put anything at all in an
 * Intent, including a malformed {@code Parcelable} array, a {@code file://} URI aimed at Orbit's own
 * storage, or ten thousand items. Deliberately free of Android I/O so the whole boundary can be
 * exercised in ordinary tests.
 */
public final class AttachmentUriCollector {

    /**
     * A hard ceiling on how many items are even looked at.
     *
     * <p>Distinct from {@link ComposerAttachments#MAX_PER_TURN}, which is the product rule about
     * how many a message may carry. This is the parsing rule: an external app that offers a
     * thousand URIs should cost Orbit a bounded amount of work before the product rule is applied,
     * and it is deliberately larger than the product limit so "you selected too many" stays a
     * truthful count rather than an artefact of where the reading stopped.
     */
    public static final int MAX_SCANNED_ITEMS = 64;

    private AttachmentUriCollector() {}

    /** URI schemes Orbit will read an attachment from. */
    private static boolean isReadableScheme(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        if (scheme == null) return false;
        String lower = scheme.toLowerCase(Locale.US);
        // content:// is what every legitimate picker and share produces, and it is the only scheme
        // that carries a permission grant Orbit can actually hold. android.resource:// is accepted
        // because a few first-party apps share built-in images that way. file:// is refused: a
        // shared file path is not something the sender proved it may read, and honouring one would
        // let any app aim Orbit's own reader at an arbitrary path.
        return "content".equals(lower) || "android.resource".equals(lower);
    }

    /**
     * Identity for deduplication.
     *
     * <p>The full URI, minus a fragment, compared case-sensitively on the path because content
     * providers are entitled to case-sensitive ids, and case-insensitively on the scheme and
     * authority because those are not. A query string is part of identity: two thumbnails of one
     * image can differ only there and are genuinely different items to read.
     */
    static String identity(Uri uri) {
        if (uri == null) return "";
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
        String authority = uri.getAuthority() == null ? "" : uri.getAuthority().toLowerCase(Locale.US);
        String path = uri.getEncodedPath() == null ? "" : uri.getEncodedPath();
        String query = uri.getEncodedQuery() == null ? "" : uri.getEncodedQuery();
        return scheme + "://" + authority + path + (query.isEmpty() ? "" : "?" + query);
    }

    /**
     * Every URI a picker result carries, first-seen order, deduplicated.
     *
     * <p>{@code getData()} is read first because a picker that populates both fields puts the
     * primary selection there, and reading it first is what keeps a duplicate from reordering the
     * batch.
     */
    public static List<Uri> fromPickerResult(Intent data) {
        List<Uri> out = new ArrayList<>();
        if (data == null) return out;
        Set<String> seen = new LinkedHashSet<>();
        add(out, seen, data.getData());
        addClipData(out, seen, data.getClipData());
        return out;
    }

    /**
     * Every URI an external Share carries, first-seen order, deduplicated.
     *
     * <p>Reads {@code EXTRA_STREAM} in both of its legitimate shapes and the {@code ClipData} the
     * platform attaches alongside it, because a sharing app may populate any combination of them
     * and Orbit must neither miss an image nor attach one twice.
     */
    public static List<Uri> fromShare(Intent intent) {
        List<Uri> out = new ArrayList<>();
        if (intent == null) return out;
        Set<String> seen = new LinkedHashSet<>();

        // A malformed or hostile extra must cost a rejected item, never a crash: reading an extra
        // whose class the sender chose can throw on its own.
        try {
            Parcelable single = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (single instanceof Uri) add(out, seen, (Uri) single);
        } catch (Exception ignored) {}

        try {
            ArrayList<Parcelable> many = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (many != null) {
                for (Parcelable item : many) {
                    if (out.size() >= MAX_SCANNED_ITEMS) break;
                    if (item instanceof Uri) add(out, seen, (Uri) item);
                }
            }
        } catch (Exception ignored) {}

        try {
            addClipData(out, seen, intent.getClipData());
        } catch (Exception ignored) {}

        return out;
    }

    private static void addClipData(List<Uri> out, Set<String> seen, ClipData clip) {
        if (clip == null) return;
        int count;
        try { count = clip.getItemCount(); } catch (Exception ignored) { return; }
        for (int i = 0; i < count; i++) {
            if (out.size() >= MAX_SCANNED_ITEMS) return;
            try {
                ClipData.Item item = clip.getItemAt(i);
                if (item != null) add(out, seen, item.getUri());
            } catch (Exception ignored) {}
        }
    }

    private static void add(List<Uri> out, Set<String> seen, Uri uri) {
        if (uri == null || out.size() >= MAX_SCANNED_ITEMS) return;
        if (!isReadableScheme(uri)) return;
        String id = identity(uri);
        if (id.isEmpty() || !seen.add(id)) return;
        out.add(uri);
    }
}
