package com.orbit.assistant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Everything Orbit needs to know about one model, in one place.
 *
 * <p>Pickers ask it what to show, the resolver asks it what is legal, and request builders send what
 * it has already validated. No screen or request path checks a model's name to decide what it can do.
 */
public final class AiModelSpec {
    /** Sent to the backend. Never shown when a friendly name exists. */
    public final String id;
    /** What the user reads, e.g. "GPT-6.1 Sol". */
    public final String displayName;
    /** Quiet picker section label, e.g. "GPT-6". */
    public final String familyLabel;
    /** The {@link Prefs} provider id this model belongs to. */
    public final String providerId;
    /** One short line for the picker. Never a ranking or a benchmark claim. */
    public final String descriptor;
    /**
     * The reasoning strengths this model accepts, weakest first. Empty means the model exposes no
     * reasoning control at all, and no strength is ever sent or shown for it.
     */
    public final List<AiStrength> strengths;
    /** Used when nothing more specific is known. Null exactly when {@link #strengths} is empty. */
    public final AiStrength defaultStrength;
    /** True when whether this model answers depends on the user's account or a rollout. */
    public final boolean availabilityVaries;
    /** Official input context window, in tokens; zero only when Orbit does not know it. */
    public final int contextWindowTokens;
    /** Model-level capabilities. Unknown values stay false rather than being guessed. */
    public final boolean vision;
    public final boolean nativeFiles;
    public final boolean extractedDocuments;
    public final boolean tools;
    public final boolean webSearch;
    public final boolean streaming;
    /** static_official or dynamic_provider. */
    public final String metadataSource;
    /** active, deprecated, unavailable, or unknown. */
    public final String availability;

    AiModelSpec(String id, String displayName, String familyLabel, String providerId,
                String descriptor, AiStrength defaultStrength, boolean availabilityVaries,
                int contextWindowTokens, AiStrength... strengths) {
        this(id, displayName, familyLabel, providerId, descriptor, defaultStrength,
                availabilityVaries, contextWindowTokens, false, false, true, false, false, true,
                "static_official", "active", strengths);
    }

    AiModelSpec(String id, String displayName, String familyLabel, String providerId,
                String descriptor, AiStrength defaultStrength, boolean availabilityVaries,
                int contextWindowTokens, boolean vision, boolean nativeFiles,
                boolean extractedDocuments, boolean tools, boolean webSearch, boolean streaming,
                String metadataSource, String availability, AiStrength... strengths) {
        this.id = id;
        this.displayName = displayName;
        this.familyLabel = familyLabel == null ? "" : familyLabel;
        this.providerId = providerId;
        this.descriptor = descriptor == null ? "" : descriptor;
        List<AiStrength> list = new ArrayList<>(Arrays.asList(strengths));
        Collections.sort(list);
        this.strengths = Collections.unmodifiableList(list);
        this.defaultStrength = list.isEmpty() ? null : defaultStrength;
        this.availabilityVaries = availabilityVaries;
        this.contextWindowTokens = Math.max(0, contextWindowTokens);
        this.vision = vision;
        this.nativeFiles = nativeFiles;
        this.extractedDocuments = extractedDocuments;
        this.tools = tools;
        this.webSearch = webSearch;
        this.streaming = streaming;
        this.metadataSource = metadataSource == null ? "" : metadataSource;
        this.availability = availability == null ? "unknown" : availability;
    }

    /** Whether the user can be offered a strength control for this model at all. */
    public boolean hasStrengths() { return !strengths.isEmpty(); }

    public boolean selectable() { return !"unavailable".equals(availability); }

    public boolean supports(AiStrength strength) {
        return strength != null && strengths.contains(strength);
    }

    /**
     * The strength Orbit will actually send for a request asking for {@code requested}.
     *
     * <p>Supported values pass through. Null means "the default". Anything this model refuses moves
     * to the nearest strength it accepts, preferring the weaker of two equally near ones, so
     * None on a model without None becomes Low rather than something more expensive. A model with
     * no strengths always answers null.
     */
    public AiStrength resolveStrength(AiStrength requested) {
        if (strengths.isEmpty()) return null;
        if (requested == null) return defaultStrength;
        if (strengths.contains(requested)) return requested;
        AiStrength best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (AiStrength candidate : strengths) {
            int distance = Math.abs(candidate.ordinal() - requested.ordinal());
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }
}
