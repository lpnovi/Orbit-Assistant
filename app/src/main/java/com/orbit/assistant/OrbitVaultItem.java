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
    /**
     * One page of a document the user was reading. Body is that page's extracted text.
     *
     * <p>Its own type rather than a text item with a helpful title, or an image item with a caption
     * glued on. A saved page is genuinely a different thing from both: it has a document it came
     * from, a place inside that document, words that were extracted from it and a picture of how it
     * actually looked, and every one of those is needed to show it, to search it, and to describe
     * it honestly to a model later. Encoding "Page 7 of Health behavior theory" into a title and
     * hoping to read it back would lose the page number the first time somebody renamed the item.
     *
     * <p>The saved page is self-contained. The document it came from may be moved, deleted, or
     * never opened again; what the Vault holds is the extracted text and Orbit's own copy of the
     * rendering, so the item stays useful with the original long gone.
     */
    public static final String TYPE_DOCUMENT_PAGE = "document_page";

    /** Every type this build understands. Anything else on disk is not shown. */
    public static final String[] TYPES = {
            TYPE_TEXT, TYPE_LINK, TYPE_IMAGE, TYPE_ORBIT_REPLY, TYPE_DOCUMENT_PAGE};

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
    /**
     * The most a user's own note about a saved item may hold.
     *
     * <p>Far smaller than the body on purpose. A note is why the user kept something, not a second
     * copy of it: a sentence or two, written in a dialog, read at a glance under the content. Given
     * the whole body budget it would stop being a note and start being a place to paste things,
     * which is what the item itself is already for.
     */
    public static final int MAX_NOTE_CHARS = 600;
    /** The longest address Orbit will keep as a link rather than as ordinary text. */
    public static final int MAX_URL_CHARS = 2000;
    /**
     * How much of a document's own name a saved page keeps.
     *
     * <p>The same ceiling a title has, because that is what it is used as. A filename is not chosen
     * by Orbit and can be arbitrarily long; a page saved from one has to stay drawable on a card.
     */
    public static final int MAX_DOCUMENT_NAME_CHARS = 120;
    /**
     * The largest page number a saved page may claim.
     *
     * <p>A bound rather than a judgement about documents: the viewer already refuses to index a PDF
     * past {@code DocumentViewerActivity.MAX_PAGES}, and this stops a hand-edited store or a
     * restored backup asserting page two billion of a four page file.
     */
    public static final int MAX_PAGE_COUNT = 100000;
    /** How long a derived title may be before it is trimmed at a word boundary. */
    private static final int AUTO_TITLE_CHARS = 60;

    public final String id;
    public final String type;
    public final String title;
    public final String body;
    public final String source;
    /**
     * What the user wrote about this item, or empty.
     *
     * <p>A distinct field rather than something folded into the title or the body, because it is a
     * different thing with a different owner. The body is what was saved - an address, a picture,
     * the words Orbit actually said - and it stays exactly as it arrived. The note is the user's
     * own reason for keeping it, written afterwards, editable at any time, and shown as clearly
     * theirs. Overloading either field to fake the other would lose that distinction the first
     * time somebody searched, backed up, or asked Orbit about a saved item.
     *
     * <p>Absent from every Beta 1 item on disk, and that is not a migration: a missing note reads
     * as an empty one, which is exactly what it means.
     */
    public final String note;
    /** Absolute path to the private file this item owns, or empty. Never a foreign content URI. */
    public final String mediaPath;
    /**
     * The display name of the document a saved page came from, or empty for every other type.
     *
     * <p>A name and nothing else. Never a path, never a content URI, never anything that could be
     * used to reach the original file: the document may be gone, and a saved page that depended on
     * it would be a broken row rather than something the user kept.
     */
    public final String documentName;
    /** Zero-based page inside that document, or 0 when this item is not a page. */
    public final int pageIndex;
    /** How many pages the document had when the page was saved, or 0 when it was not known. */
    public final int pageCount;
    /**
     * Whether the user asked for this item to stay near the top of their Vault.
     *
     * <p>One boolean, and deliberately not a collection. A pin is the smallest possible answer to
     * "I keep coming back to this one": it needs no folder to live in, no name to be given, and no
     * decision about where something belongs before it can be kept. A saved item is in exactly one
     * place either way - the Vault - and pinning only changes where it is drawn.
     *
     * <p>Absent from every Beta 1, Beta 2 and Beta 3 item on disk, and absent from every item
     * nobody has pinned. A missing key reads as unpinned, which is what it means, so there is no
     * migration and an older Orbit reading the same document finds nothing it does not understand.
     *
     * <p>Pinning is not an edit. It never moves {@link #modifiedAt}, because the user did not
     * change the thing they saved - "Edited today" on a link somebody merely pinned would be Orbit
     * stating something untrue about their own collection.
     */
    public final boolean pinned;
    public final long createdAt;
    public final long modifiedAt;

    /** One item with no note of its own. What every Beta 1 item on disk is. */
    public OrbitVaultItem(String id, String type, String title, String body, String source,
                          String mediaPath, long createdAt, long modifiedAt) {
        this(id, type, title, body, source, "", mediaPath, createdAt, modifiedAt);
    }

    /** One item that is not a saved document page, which is every type but one. */
    public OrbitVaultItem(String id, String type, String title, String body, String source,
                          String note, String mediaPath, long createdAt, long modifiedAt) {
        this(id, type, title, body, source, note, mediaPath, "", 0, 0, createdAt, modifiedAt);
    }

    /** One item nobody has pinned, which is what every item saved before Beta 4 is. */
    public OrbitVaultItem(String id, String type, String title, String body, String source,
                          String note, String mediaPath, String documentName, int pageIndex,
                          int pageCount, long createdAt, long modifiedAt) {
        this(id, type, title, body, source, note, mediaPath, documentName, pageIndex, pageCount,
                false, createdAt, modifiedAt);
    }

    public OrbitVaultItem(String id, String type, String title, String body, String source,
                          String note, String mediaPath, String documentName, int pageIndex,
                          int pageCount, boolean pinned, long createdAt, long modifiedAt) {
        this.id = trim(id);
        this.type = normalizeType(type);
        this.title = bound(collapse(title), MAX_TITLE_CHARS);
        this.body = boundBody(body);
        this.source = bound(collapse(source), MAX_SOURCE_CHARS);
        this.note = boundNote(note);
        this.mediaPath = trim(mediaPath);
        // Page metadata is kept only for the type it describes, so a text item cannot be handed a
        // page number by a hand-edited store and start claiming to be part of a document.
        boolean page = TYPE_DOCUMENT_PAGE.equals(this.type);
        this.documentName = page ? bound(collapse(documentName), MAX_DOCUMENT_NAME_CHARS) : "";
        this.pageCount = page ? Math.max(0, Math.min(pageCount, MAX_PAGE_COUNT)) : 0;
        this.pageIndex = page ? clampPage(pageIndex, this.pageCount) : 0;
        this.pinned = pinned;
        this.createdAt = createdAt;
        this.modifiedAt = modifiedAt <= 0L ? createdAt : modifiedAt;
    }

    /**
     * The page a saved item may legitimately claim.
     *
     * <p>A known page count is the ceiling; an unknown one still has {@link #MAX_PAGE_COUNT}, so a
     * page saved from a document whose length Orbit could not read keeps its number instead of
     * silently becoming page one.
     */
    private static int clampPage(int value, int count) {
        if (value < 0) return 0;
        if (count > 0) return Math.min(value, count - 1);
        return Math.min(value, MAX_PAGE_COUNT - 1);
    }

    // ---- derived copies ---------------------------------------------------------------------------

    /**
     * The same item with one field changed and everything else carried over.
     *
     * <p>Here rather than at each editing call site, because the fields an edit must <em>not</em>
     * touch are the ones nobody thinks about. Renaming a saved page has to keep its document, its
     * page number and its rendering; a rebuild written by hand at three call sites would drop one
     * of them the first time a field was added, and the user would find a page that had forgotten
     * which page it was.
     */
    OrbitVaultItem copyWith(String newTitle, String newBody, String newNote, long modifiedNow) {
        return new OrbitVaultItem(id, type, newTitle, newBody, source, newNote, mediaPath,
                documentName, pageIndex, pageCount, pinned, createdAt, modifiedNow);
    }

    /**
     * The same item, pinned or unpinned, and identical in every other respect.
     *
     * <p>{@link #modifiedAt} is carried across untouched rather than stamped with now. Pinning is
     * a statement about where the user wants to find something, not a change to the thing itself,
     * and moving the timestamp would quietly reorder an Oldest-first Vault and make every pinned
     * item claim to have been edited.
     */
    OrbitVaultItem copyPinned(boolean nowPinned) {
        return new OrbitVaultItem(id, type, title, body, source, note, mediaPath,
                documentName, pageIndex, pageCount, nowPinned, createdAt, modifiedAt);
    }

    // ---- what a screen may ask ------------------------------------------------------------------

    public boolean isImage() { return TYPE_IMAGE.equals(type); }
    public boolean isLink() { return TYPE_LINK.equals(type); }
    public boolean isOrbitReply() { return TYPE_ORBIT_REPLY.equals(type); }
    public boolean isText() { return TYPE_TEXT.equals(type); }
    public boolean isDocumentPage() { return TYPE_DOCUMENT_PAGE.equals(type); }

    /**
     * Whether this kind of item keeps a private file of its own.
     *
     * <p>Asked by type rather than by looking for a path, so backup, restore and deletion all agree
     * about which items are supposed to have media before any of them reads the disk. A picture
     * without its file is nothing; a saved page without its rendering is still its text.
     */
    public static boolean typeOwnsMedia(String value) {
        return TYPE_IMAGE.equals(value) || TYPE_DOCUMENT_PAGE.equals(value);
    }

    /** Whether this particular item is one of those. */
    public boolean ownsMedia() { return typeOwnsMedia(type); }

    /** Whether the file this item owns is required for it to exist at all. */
    public boolean requiresMedia() { return isImage(); }

    /** Whether the user has written anything of their own about this item. */
    public boolean hasNote() { return !note.isEmpty(); }

    /** Whether the user asked for this one to stay near the top. */
    public boolean isPinned() { return pinned; }

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
            case TYPE_DOCUMENT_PAGE: return "Document page";
            default: return "Note";
        }
    }

    /** "Page 7 of 388", "Page 7" when the length was not known, or "" for anything else. */
    public String pageLabel() {
        if (!isDocumentPage()) return "";
        int human = pageIndex + 1;
        return pageCount > 0 ? "Page " + human + " of " + pageCount : "Page " + human;
    }

    /** The human page number, counting from one, or 0 when this item is not a page. */
    public int pageNumber() { return isDocumentPage() ? pageIndex + 1 : 0; }

    /** The title to draw: what the user set, or a local fallback derived from the content. */
    public String displayTitle() {
        if (!title.isEmpty()) return title;
        // A saved page is named after the document and the page rather than after its first line.
        // "Chapter 4 continued" is what page 118 happens to start with; it is not what the user
        // would look for when they go back for the page they kept.
        if (isDocumentPage() && !documentName.isEmpty()) {
            return documentName + " · " + pageLabel();
        }
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

    /**
     * Everything one item contributes to local search, lower-cased once.
     *
     * <p>The note is in here because it is very often the only words the user would think to
     * search for. A saved short-video address contains nothing anybody remembers; "animation
     * idea", which they typed themselves, is exactly what they will type again.
     */
    String searchHaystack() {
        return (title + "\n" + body + "\n" + note + "\n" + source + "\n" + documentName
                + "\n" + pageLabel() + "\n" + typeLabel())
                .toLowerCase(Locale.US);
    }

    // ---- storage ---------------------------------------------------------------------------------

    JSONObject toJson() throws Exception {
        JSONObject out = new JSONObject()
                .put("id", id)
                .put("type", type)
                .put("title", title)
                .put("body", body)
                .put("source", source)
                .put("note", note)
                .put("mediaPath", mediaPath)
                .put("createdAt", createdAt)
                .put("modifiedAt", modifiedAt);
        // Written only when it is true, so a Vault nobody has pinned anything in is byte-for-byte
        // the document Beta 3 wrote and an older build reading it finds nothing new.
        if (pinned) out.put("pinned", true);
        // Written only for the type that has them, so a Vault of notes and links is byte-for-byte
        // the document Beta 2 wrote and an older build reading it finds nothing new.
        if (isDocumentPage()) {
            out.put("documentName", documentName)
               .put("pageIndex", pageIndex)
               .put("pageCount", pageCount);
        }
        return out;
    }

    static OrbitVaultItem fromJson(JSONObject o) {
        if (o == null) return null;
        OrbitVaultItem item = new OrbitVaultItem(
                o.optString("id", ""),
                o.optString("type", ""),
                o.optString("title", ""),
                o.optString("body", ""),
                o.optString("source", ""),
                // Absent in every Beta 1 document. Missing means the user never wrote one, which
                // is what an empty note already means, so nothing has to be migrated.
                o.optString("note", ""),
                o.optString("mediaPath", ""),
                // Absent in every Beta 1 and Beta 2 document, and absent from every item that is
                // not a saved page. The constructor drops them for any other type, so a missing
                // key and a wrong key reach the same place.
                o.optString("documentName", ""),
                o.optInt("pageIndex", 0),
                o.optInt("pageCount", 0),
                // Absent in every document written before Beta 4, and absent from every item
                // nobody has pinned. Missing means unpinned, which is what unpinned already means.
                o.optBoolean("pinned", false),
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
        // A picture is its file: an image row without one is a broken thumbnail and nothing else.
        if (item.requiresMedia()) return !item.mediaPath.isEmpty();
        // A saved page is not. Its rendering is half of what makes it useful and its text is the
        // other half, so either one alone is still the page the user kept - and a page whose
        // rendering is lost must not take the rest of the Vault down with it.
        if (item.isDocumentPage()) {
            return !item.mediaPath.isEmpty() || !item.body.isEmpty() || !item.documentName.isEmpty();
        }
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

    /**
     * A note keeps its line breaks and is cut silently at the ceiling.
     *
     * <p>Silently, unlike the body, because a note is written in a field the user is looking at
     * and cannot reach the limit without typing six hundred characters into it on purpose. The
     * body arrives from a share or a paste and can be cut without anybody watching, which is why
     * that one announces itself.
     */
    private static String boundNote(String value) {
        String text = value == null ? "" : value.trim();
        return text.length() <= MAX_NOTE_CHARS ? text : text.substring(0, MAX_NOTE_CHARS).trim();
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
