package com.orbit.assistant;

import android.net.Uri;

import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * One thing the user deliberately chose to keep.
 *
 * <p>Orbit Vault is not chat history and it is not Memory. A conversation is written because Orbit
 * answered; a memory is written because Orbit may need it later. A Vault item exists for exactly
 * one reason: somebody looked at something and decided to save it. Nothing in Orbit creates one on
 * its own, and nothing reads one back into a prompt.
 *
 * <p>The type is a stable internal id, never the display word. "Link" is what the screen says and
 * may be rewritten tomorrow; {@code link} is what is on disk and must not be. Everything a screen
 * shows - the name of a type, a hostname, a date - is derived here at display time from those
 * stable values, so no behaviour is ever encoded in a string a designer might change.
 */
public final class OrbitVaultItem {

    // ---- type ids (storage identity: never rename) ---------------------------------------------

    /** Something the user wrote or pasted. Body is the text. */
    public static final String TYPE_TEXT = "text";
    /** One ordinary http/https address. Body is the URL, exactly as it arrived. */
    public static final String TYPE_LINK = "link";
    /** A picture Orbit copied into its own private storage. Body is an optional note. */
    public static final String TYPE_IMAGE = "image";
    /** A reply the user saved from a conversation. Body is the visible reply text, read-only. */
    public static final String TYPE_ORBIT_REPLY = "orbit_reply";

    /** Every type this build understands. Anything else on disk is not shown. */
    public static final String[] TYPES = {TYPE_TEXT, TYPE_LINK, TYPE_IMAGE, TYPE_ORBIT_REPLY};

    // ---- bounds --------------------------------------------------------------------------------

    public static final int MAX_TITLE_CHARS = 120;
    /**
     * The most text one saved item may hold.
     *
     * <p>Deliberately below {@link SharedContentStore#MAX_TEXT_CHARS}: a share is one composer's
     * worth of material that is read once, while a Vault item is kept, listed, searched and backed
     * up indefinitely. Anything longer is trimmed with a visible notice rather than silently cut.
     */
    public static final int MAX_BODY_CHARS = 20000;
    public static final int MAX_SOURCE_CHARS = 60;
    /** The longest address Orbit will keep as a link rather than as ordinary text. */
    public static final int MAX_URL_CHARS = 2000;
    /** How long a derived title may be before it is trimmed at a word boundary. */
    private static final int AUTO_TITLE_CHARS = 60;

    public final String id;
    public final String type;
    public final String title;
    public final String body;
    public final String source;
    /** Absolute path to the private file this item owns, or empty. Never a foreign content URI. */
    public final String mediaPath;
    public final long createdAt;
    public final long modifiedAt;

    public OrbitVaultItem(String id, String type, String title, String body, String source,
                          String mediaPath, long createdAt, long modifiedAt) {
        this.id = trim(id);
        this.type = normalizeType(type);
        this.title = bound(collapse(title), MAX_TITLE_CHARS);
        this.body = boundBody(body);
        this.source = bound(collapse(source), MAX_SOURCE_CHARS);
        this.mediaPath = trim(mediaPath);
        this.createdAt = createdAt;
        this.modifiedAt = modifiedAt <= 0L ? createdAt : modifiedAt;
    }

    // ---- what a screen may ask ------------------------------------------------------------------

    public boolean isImage() { return TYPE_IMAGE.equals(type); }
    public boolean isLink() { return TYPE_LINK.equals(type); }
    public boolean isOrbitReply() { return TYPE_ORBIT_REPLY.equals(type); }
    public boolean isText() { return TYPE_TEXT.equals(type); }

    /**
     * Whether the stored body itself may be rewritten.
     *
     * <p>A saved answer is a record of what Orbit actually said. Letting it be edited would leave
     * the user holding something that looks like a quotation and is not one, so a saved reply keeps
     * an editable title and a body exactly as it was saved. A note the user wrote is theirs.
     */
    public boolean bodyIsEditable() { return isText(); }

    /** The word a person reads for this kind of item. Display only; never stored. */
    public String typeLabel() {
        switch (type) {
            case TYPE_LINK: return "Link";
            case TYPE_IMAGE: return "Image";
            case TYPE_ORBIT_REPLY: return "Orbit answer";
            default: return "Note";
        }
    }

    /** The title to draw: what the user set, or a local fallback derived from the content. */
    public String displayTitle() {
        if (!title.isEmpty()) return title;
        String derived = autoTitle(type, body);
        return derived.isEmpty() ? typeLabel() : derived;
    }

    /**
     * The host of a link, for display beside it.
     *
     * <p>Parsed locally and never resolved: Orbit does not contact the address to find out anything
     * about it, and an address it cannot parse simply shows nothing.
     */
    public String hostLabel() {
        if (!isLink()) return "";
        return hostOf(body);
    }

    /** A short one-line preview of the content, for a card. Never the whole body. */
    public String preview() {
        String flat = collapse(body);
        return flat.length() <= 140 ? flat : flat.substring(0, 140).trim();
    }

    /** "Saved 14 Aug 2026", in the device's own locale and format. */
    public String savedLabel() {
        return "Saved " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(createdAt));
    }

    /** The same for the last edit, shown only when it genuinely differs from the save. */
    public String modifiedLabel() {
        if (modifiedAt <= createdAt + 1000L) return "";
        return "Edited " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(modifiedAt));
    }

    /** Everything one item contributes to local search, lower-cased once. */
    String searchHaystack() {
        return (title + "\n" + body + "\n" + source + "\n" + typeLabel()).toLowerCase(Locale.US);
    }

    // ---- storage ---------------------------------------------------------------------------------

    JSONObject toJson() throws Exception {
        return new JSONObject()
                .put("id", id)
                .put("type", type)
                .put("title", title)
                .put("body", body)
                .put("source", source)
                .put("mediaPath", mediaPath)
                .put("createdAt", createdAt)
                .put("modifiedAt", modifiedAt);
    }

    static OrbitVaultItem fromJson(JSONObject o) {
        if (o == null) return null;
        OrbitVaultItem item = new OrbitVaultItem(
                o.optString("id", ""),
                o.optString("type", ""),
                o.optString("title", ""),
                o.optString("body", ""),
                o.optString("source", ""),
                o.optString("mediaPath", ""),
                o.optLong("createdAt", 0L),
                o.optLong("modifiedAt", 0L));
        return isStorable(item) ? item : null;
    }

    /**
     * Whether one item is worth keeping at all.
     *
     * <p>Read on the way in as well as the way out, so a store damaged by anything - a partial
     * write, a hand-edited file, a restored backup - loses the damaged rows and keeps the rest
     * rather than refusing to open.
     */
    static boolean isStorable(OrbitVaultItem item) {
        if (item == null || item.id.isEmpty() || item.createdAt <= 0L) return false;
        if (!knownType(item.type)) return false;
        if (item.isImage()) return !item.mediaPath.isEmpty();
        return !item.body.isEmpty() || !item.title.isEmpty();
    }

    static boolean knownType(String value) {
        for (String known : TYPES) if (known.equals(value)) return true;
        return false;
    }

    private static String normalizeType(String value) {
        String trimmed = trim(value);
        return knownType(trimmed) ? trimmed : TYPE_TEXT;
    }

    // ---- deriving things locally --------------------------------------------------------------

    /**
     * Whether some shared or pasted text is one ordinary web address and nothing else.
     *
     * <p>Deliberately strict. One token, no whitespace, {@code http} or {@code https} only, a real
     * host, and a sane length: a paragraph that happens to contain a URL is a note rather than a
     * link, and a {@code file://} or {@code javascript:} string is neither. Nothing here opens,
     * resolves, or contacts the address.
     */
    public static String singleLinkOrEmpty(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.length() > MAX_URL_CHARS) return "";
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) return "";
        }
        String lower = value.toLowerCase(Locale.US);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return "";
        return hostOf(value).isEmpty() ? "" : value;
    }

    /**
     * A useful title worked out on this device, with no request to anything.
     *
     * <p>The first line is what a person would have typed themselves, so that is what is used; a
     * link falls back to its host, and a picture to the word for its kind. Never an AI call: saving
     * something has to work with no account, no network, and no provider configured.
     */
    public static String autoTitle(String type, String body) {
        if (TYPE_LINK.equals(type)) return hostOf(body);
        String text = body == null ? "" : body.trim();
        if (text.isEmpty()) return "";
        int newline = text.indexOf('\n');
        String firstLine = collapse(newline >= 0 ? text.substring(0, newline) : text);
        // Markdown chrome makes a poor title. A saved answer beginning "## Battery health" should
        // be titled "Battery health", not repeated with its punctuation.
        firstLine = firstLine.replaceAll("^[#>*_\\-\\s]+", "").replaceAll("[*_`]+", "").trim();
        if (firstLine.isEmpty()) return "";
        if (firstLine.length() <= AUTO_TITLE_CHARS) return firstLine;
        String clipped = firstLine.substring(0, AUTO_TITLE_CHARS);
        int lastSpace = clipped.lastIndexOf(' ');
        if (lastSpace > AUTO_TITLE_CHARS / 2) clipped = clipped.substring(0, lastSpace);
        return clipped.trim();
    }

    /** The lower-cased host of an http/https address, without a leading {@code www.}. */
    private static String hostOf(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        try {
            Uri parsed = Uri.parse(value.trim());
            String scheme = parsed.getScheme();
            String host = parsed.getHost();
            if (scheme == null || host == null) return "";
            String loweredScheme = scheme.toLowerCase(Locale.US);
            if (!"http".equals(loweredScheme) && !"https".equals(loweredScheme)) return "";
            String lower = host.trim().toLowerCase(Locale.US);
            if (lower.isEmpty() || lower.indexOf('.') < 0) return "";
            return lower.startsWith("www.") ? lower.substring(4) : lower;
        } catch (Exception ignored) {
            return "";
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String collapse(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String bound(String value, int max) {
        String text = value == null ? "" : value;
        return text.length() <= max ? text : text.substring(0, max).trim();
    }

    /** Body keeps its line breaks, and says so when it had to be cut. */
    private static String boundBody(String value) {
        String text = value == null ? "" : value.trim();
        if (text.length() <= MAX_BODY_CHARS) return text;
        return text.substring(0, MAX_BODY_CHARS)
                + "\n\n[Orbit trimmed this saved item after "
                + String.format(Locale.US, "%,d", MAX_BODY_CHARS) + " characters.]";
    }
}
