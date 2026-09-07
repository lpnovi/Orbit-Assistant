package com.orbit.assistant;

import java.util.Locale;

/**
 * What the user is currently choosing to look at in their Vault.
 *
 * <p>Three independent questions in one immutable value: what kind of thing, where it came from,
 * and what words are in it. They compose rather than override - "images I took from a screen
 * selection with peak in them" is one filter, not three screens - and none of them is a folder. A
 * saved item is never moved, copied, tagged or filed by any of this; the collection stays one flat
 * list and a filter is only ever a question asked about it.
 *
 * <p><b>Everything here is decided on the item's canonical fields.</b> The type is matched against
 * the stable ids in {@link OrbitVaultItem}, never against the word a card happens to draw, so
 * rewording "Orbit answer" tomorrow cannot silently empty a filter. The source is matched through
 * {@link OrbitVaultSource}, which is a closed vocabulary Orbit writes itself, so no filter can ever
 * be steered by a string another app chose.
 *
 * <p>No provider is involved in any of it. Filtering is a loop over a list already in memory.
 */
public final class OrbitVaultFilter {

    /**
     * The six type choices the Vault's Type selector offers.
     *
     * <p>Six rather than one per stored type, because two of them are what a person would ask for
     * rather than what the store happens to hold: "Documents" is the saved-page type, and "Orbit
     * answers" is the saved-answer type under the words people actually use for it.
     *
     * <p>The labels are what a selector draws, and Beta 5 changed two of them. "All" became "All
     * items" so the closed selector states what it is showing rather than only that nothing is
     * chosen, and "Orbit" became "Orbit answers" because "Orbit" beside a source list that also
     * contains Orbit's own surfaces named the app rather than the kind of thing. The ids under
     * both are stored and are unchanged, so a filter somebody left in force before this release is
     * still the same filter afterwards.
     */
    public enum Type {
        /** Everything, and the default. */
        ALL("all", "All items"),
        TEXT("text", "Text"),
        LINKS("links", "Links"),
        IMAGES("images", "Images"),
        DOCUMENTS("documents", "Documents"),
        ORBIT("orbit", "Orbit answers");

        /** Stored identity: never rename. */
        public final String id;
        public final String label;

        Type(String id, String label) {
            this.id = id;
            this.label = label;
        }

        /** Decided on the item's canonical type id, never on the word a card draws. */
        public boolean matches(OrbitVaultItem item) {
            if (item == null) return false;
            switch (this) {
                case TEXT: return item.isText();
                case LINKS: return item.isLink();
                case IMAGES: return item.isImage();
                case DOCUMENTS: return item.isDocumentPage();
                case ORBIT: return item.isOrbitReply();
                default: return true;
            }
        }

        public static Type fromId(String value) {
            for (Type type : values()) if (type.id.equals(value)) return type;
            return ALL;
        }
    }

    /** The default: everything, in whatever order the user chose. */
    public static final OrbitVaultFilter NONE = new OrbitVaultFilter(Type.ALL, "", "");

    public final Type type;
    /** A canonical {@link OrbitVaultSource} family, or empty for any source. */
    public final String source;
    /** What the user typed, or empty. */
    public final String query;

    public OrbitVaultFilter(Type type, String source, String query) {
        this.type = type == null ? Type.ALL : type;
        // Anything that is not one of Orbit's own source words is not a source filter at all. A
        // restored backup may legitimately carry a label an older Orbit wrote, and a hand-edited
        // store could carry anything; neither may become a filter this screen offers.
        String family = OrbitVaultSource.family(source);
        this.source = family;
        this.query = query == null ? "" : query.trim();
    }

    public OrbitVaultFilter withType(Type newType) {
        return new OrbitVaultFilter(newType, source, query);
    }

    public OrbitVaultFilter withSource(String newSource) {
        return new OrbitVaultFilter(type, newSource, query);
    }

    public OrbitVaultFilter withQuery(String newQuery) {
        return new OrbitVaultFilter(type, source, newQuery);
    }

    /** Whether the user has narrowed the Vault by anything other than words. */
    public boolean hasTypeOrSource() {
        return type != Type.ALL || !source.isEmpty();
    }

    /** Whether anything at all is currently hiding part of the Vault. */
    public boolean isNarrowed() {
        return hasTypeOrSource() || !query.isEmpty();
    }

    public boolean hasQuery() { return !query.isEmpty(); }

    /** Whether one saved item survives every part of this filter at once. */
    public boolean matches(OrbitVaultItem item) {
        if (item == null) return false;
        if (!type.matches(item)) return false;
        if (!source.isEmpty() && !source.equals(OrbitVaultSource.family(item.source))) return false;
        if (query.isEmpty()) return true;
        return item.searchHaystack().contains(query.toLowerCase(Locale.US));
    }

    /** "Images · Screen selection", for the line that says what is being shown. */
    public String describe() {
        if (!hasTypeOrSource()) return "";
        String from = OrbitVaultSource.displayLabel(source);
        if (from.isEmpty()) return type.label;
        if (type == Type.ALL) return from;
        return type.label + " · " + from;
    }
}
