package com.orbit.assistant;

/**
 * Provider, model and strength: exactly which AI a request goes to and how hard it is asked to think.
 *
 * <p>Immutable and dumb on purpose. Whether a selection is legal is {@link AiSelections}'s question;
 * this only carries the three values and knows how to write itself down. A request builder that
 * receives one from {@link AiSelections#resolve} sends it as it is.
 */
public final class AiSelection {
    private static final String SEPARATOR = "|";
    private static final String VERSION = "1";

    public final String provider;
    public final String model;
    /** Null exactly when the model exposes no reasoning control. */
    public final AiStrength strength;

    public AiSelection(String provider, String model, AiStrength strength) {
        this.provider = provider == null ? "" : provider.trim();
        this.model = model == null ? "" : model.trim();
        this.strength = strength;
    }

    public static AiSelection of(String provider, String model, AiStrength strength) {
        return new AiSelection(provider, model, strength);
    }

    public AiSelection withModel(String value) { return new AiSelection(provider, value, strength); }

    public AiSelection withStrength(AiStrength value) { return new AiSelection(provider, model, value); }

    /** The backend effort id, or "" when no reasoning parameter should be sent. */
    public String effortId() { return strength == null ? "" : strength.id; }

    /** "GPT-6.1 Sol", or the raw id only when Orbit has no name for it. */
    public String modelName() {
        String name = OrbitModelCatalog.displayName(model);
        return name.isEmpty() ? model : name;
    }

    /** "Sol", "Luna", "Local": the last word of the model's name, for tight spaces. */
    public String shortModelName() {
        String name = modelName();
        int space = name.lastIndexOf(' ');
        return space < 0 ? name : name.substring(space + 1);
    }

    /** The provider's user-facing name. */
    public String providerName() {
        return AiProviders.byId(provider).displayName();
    }

    /** "GPT-6.1 Sol · High", or just the model when there is no strength. */
    public String label() {
        return strength == null ? modelName() : modelName() + " · " + strength.label;
    }

    /** "Sol · High", for the overlay's compact chip. */
    public String shortLabel() {
        return strength == null ? shortModelName() : shortModelName() + " · " + strength.label;
    }

    /** One storable line. Read back with {@link #decode}. */
    public String encode() {
        return VERSION + SEPARATOR + provider + SEPARATOR + model + SEPARATOR
                + (strength == null ? "" : strength.id);
    }

    /** The selection {@link #encode} wrote, or null for anything empty or malformed. */
    public static AiSelection decode(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String[] parts = value.trim().split("\\|", -1);
        if (parts.length != 4 || !VERSION.equals(parts[0])) return null;
        if (parts[1].trim().isEmpty() || parts[2].trim().isEmpty()) return null;
        return new AiSelection(parts[1], parts[2], AiStrength.fromId(parts[3]));
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof AiSelection)) return false;
        AiSelection o = (AiSelection) other;
        return provider.equals(o.provider) && model.equals(o.model) && strength == o.strength;
    }

    @Override public int hashCode() {
        return (provider.hashCode() * 31 + model.hashCode()) * 31
                + (strength == null ? 0 : strength.hashCode());
    }

    @Override public String toString() { return encode(); }
}
