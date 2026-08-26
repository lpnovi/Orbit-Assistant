package com.orbit.assistant;

import android.os.Bundle;

/**
 * One snapshot of the Orbit Local component, read out of the status Bundle.
 *
 * <p>A Bundle rather than a parcelable crosses the boundary deliberately: a later protocol can add
 * a key and an older reader simply will not ask for it. This class is where those loose keys
 * become something the rest of Orbit can use without knowing they came from another process.
 *
 * <p>Model states mirror the component's own, plus {@link #IMPORTING} for a migration in
 * progress. An unrecognised state string resolves to {@link #NOT_INSTALLED} rather than being
 * trusted — Orbit will not present a model as ready on the strength of a word it does not know.
 */
public final class OrbitLocalStatus {

    public static final String NOT_INSTALLED = "NOT_INSTALLED";
    public static final String PAUSED = "PAUSED";
    public static final String DOWNLOADING = "DOWNLOADING";
    public static final String VALIDATING = "VALIDATING";
    public static final String IMPORTING = "IMPORTING";
    public static final String READY = "READY";
    public static final String ERROR = "ERROR";

    private static final String[] KNOWN_STATES = {
            NOT_INSTALLED, PAUSED, DOWNLOADING, VALIDATING, IMPORTING, READY, ERROR
    };

    public final int protocol;
    public final String componentVersionName;
    public final long componentVersionCode;
    public final String modelState;
    public final String modelId;
    public final String modelDisplayName;
    /** Bytes of the model present so far, partial or complete. */
    public final long modelBytes;
    /** Every byte the component's model directory holds. */
    public final long modelTotalBytes;
    /** The pinned full size of the model. */
    public final long modelSizeBytes;
    public final String modelError;
    public final long freeBytes;

    private OrbitLocalStatus(int protocol, String componentVersionName, long componentVersionCode,
                             String modelState, String modelId, String modelDisplayName,
                             long modelBytes, long modelTotalBytes, long modelSizeBytes,
                             String modelError, long freeBytes) {
        this.protocol = protocol;
        this.componentVersionName = componentVersionName == null ? "" : componentVersionName;
        this.componentVersionCode = componentVersionCode;
        this.modelState = normalizeState(modelState);
        this.modelId = modelId == null ? "" : modelId;
        this.modelDisplayName = modelDisplayName == null || modelDisplayName.isEmpty()
                ? "Local model" : modelDisplayName;
        this.modelBytes = Math.max(0L, modelBytes);
        this.modelTotalBytes = Math.max(0L, modelTotalBytes);
        this.modelSizeBytes = Math.max(0L, modelSizeBytes);
        this.modelError = modelError == null ? "" : modelError;
        this.freeBytes = freeBytes;
    }

    static OrbitLocalStatus from(Bundle bundle) {
        if (bundle == null) return null;
        return new OrbitLocalStatus(
                bundle.getInt("protocol", -1),
                bundle.getString("componentVersionName", ""),
                bundle.getLong("componentVersionCode", 0L),
                bundle.getString("modelState", NOT_INSTALLED),
                bundle.getString("modelId", ""),
                bundle.getString("modelDisplayName", ""),
                bundle.getLong("modelBytes", 0L),
                bundle.getLong("modelTotalBytes", 0L),
                bundle.getLong("modelSizeBytes", 0L),
                bundle.getString("modelError", ""),
                bundle.getLong("freeBytes", -1L));
    }

    /** An unknown state is treated as nothing installed, never as something usable. */
    static String normalizeState(String state) {
        if (state != null) {
            for (String known : KNOWN_STATES) if (known.equals(state)) return known;
        }
        return NOT_INSTALLED;
    }

    public boolean modelReady() {
        return READY.equals(modelState);
    }

    public boolean modelBusy() {
        return DOWNLOADING.equals(modelState) || VALIDATING.equals(modelState)
                || IMPORTING.equals(modelState);
    }

    /** Progress through the download or import, 0-1000, for the existing progress bar. */
    public int progressPerMille() {
        if (modelSizeBytes <= 0L) return 0;
        return (int) Math.max(0L, Math.min(1000L, modelBytes * 1000L / modelSizeBytes));
    }

    /** The short pill label the Orbit Local screen shows for the model. */
    public String stateLabel() {
        switch (modelState) {
            case READY: return "Ready";
            case DOWNLOADING: return "Downloading";
            case VALIDATING: return "Verifying";
            case IMPORTING: return "Moving";
            case PAUSED: return "Paused";
            case ERROR: return "Attention";
            default: return "Not installed";
        }
    }
}
