package com.orbit.assistant;

import android.os.Bundle;

/**
 * One snapshot of the Orbit Local component, read out of the status Bundle.
 *
 * <p>A Bundle rather than a parcelable crosses the boundary deliberately: a later protocol can add
 * a key and an older reader simply will not ask for it. This class is where those loose keys
 * become something the rest of Orbit can use without knowing they came from another process.
 *
 * <p>Model states mirror the component's own. An unrecognised state string resolves to
 * {@link #NOT_INSTALLED} rather than being trusted — Orbit will not present a model as ready on
 * the strength of a word it does not know.
 *
 * <p>Protocol 2 split what used to be one overloaded word. {@link #PAUSED} now means a pause the
 * user asked for and nothing else; a download that is queued, offline, or was cut short reports
 * {@link #QUEUED}, {@link #WAITING_FOR_NETWORK} or {@link #INTERRUPTED}. That distinction is the
 * whole reason Orbit can stop telling someone they paused a download they never touched.
 */
public final class OrbitLocalStatus {

    public static final String NOT_INSTALLED = "NOT_INSTALLED";
    /** Work exists and is waiting its turn. */
    public static final String QUEUED = "QUEUED";
    /** Work exists and is waiting for the device to come back online. */
    public static final String WAITING_FOR_NETWORK = "WAITING_FOR_NETWORK";
    public static final String DOWNLOADING = "DOWNLOADING";
    /** Stopped before finishing for a reason nobody chose. Resumable. */
    public static final String INTERRUPTED = "INTERRUPTED";
    /** The user asked for this, and only ever that. */
    public static final String PAUSED = "PAUSED";
    public static final String VALIDATING = "VALIDATING";
    public static final String IMPORTING = "IMPORTING";
    public static final String READY = "READY";
    public static final String ERROR = "ERROR";

    private static final String[] KNOWN_STATES = {
            NOT_INSTALLED, QUEUED, WAITING_FOR_NETWORK, DOWNLOADING, INTERRUPTED,
            PAUSED, VALIDATING, IMPORTING, READY, ERROR
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
    /** Diagnostics only: what the component's background work was doing. */
    public final String workState;
    /** Diagnostics only: whether a pause was explicitly requested. */
    public final boolean pauseRequested;
    /** Diagnostics only: a short token naming why the last attempt stopped. */
    public final String lastFailure;

    // ---- the action model, from protocol 3 --------------------------------------------------

    /** The device-action model's state, in exactly the same vocabulary as the chat model's. */
    public final String actionModelState;
    public final String actionModelId;
    public final String actionModelDisplayName;
    public final long actionModelBytes;
    public final long actionModelTotalBytes;
    public final long actionModelSizeBytes;
    public final String actionModelError;
    /** The model's licence, reported by the component rather than restated by Orbit. */
    public final String actionModelLicense;
    /** Diagnostics only. */
    public final String actionWorkState;
    public final boolean actionPauseRequested;
    public final String actionLastFailure;

    private OrbitLocalStatus(int protocol, String componentVersionName, long componentVersionCode,
                             String modelState, String modelId, String modelDisplayName,
                             long modelBytes, long modelTotalBytes, long modelSizeBytes,
                             String modelError, long freeBytes, String workState,
                             boolean pauseRequested, String lastFailure,
                             String actionModelState, String actionModelId,
                             String actionModelDisplayName, long actionModelBytes,
                             long actionModelTotalBytes, long actionModelSizeBytes,
                             String actionModelError, String actionModelLicense,
                             String actionWorkState, boolean actionPauseRequested,
                             String actionLastFailure) {
        this.actionModelState = normalizeState(actionModelState);
        this.actionModelId = actionModelId == null ? "" : actionModelId;
        this.actionModelDisplayName = actionModelDisplayName == null || actionModelDisplayName.isEmpty()
                ? "Local action model" : actionModelDisplayName;
        this.actionModelBytes = Math.max(0L, actionModelBytes);
        this.actionModelTotalBytes = Math.max(0L, actionModelTotalBytes);
        this.actionModelSizeBytes = Math.max(0L, actionModelSizeBytes);
        this.actionModelError = actionModelError == null ? "" : actionModelError;
        this.actionModelLicense = actionModelLicense == null ? "" : actionModelLicense;
        this.actionWorkState = actionWorkState == null ? "" : actionWorkState;
        this.actionPauseRequested = actionPauseRequested;
        this.actionLastFailure = actionLastFailure == null ? "" : actionLastFailure;
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
        this.workState = workState == null ? "" : workState;
        this.pauseRequested = pauseRequested;
        this.lastFailure = lastFailure == null ? "" : lastFailure;
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
                bundle.getLong("freeBytes", -1L),
                bundle.getString("workState", ""),
                bundle.getBoolean("pauseRequested", false),
                bundle.getString("lastFailure", ""),
                // Absent on a protocol-2 component, which reads back as NOT_INSTALLED — the
                // honest answer for a component that cannot hold an action model at all.
                bundle.getString("actionModelState", NOT_INSTALLED),
                bundle.getString("actionModelId", ""),
                bundle.getString("actionModelDisplayName", ""),
                bundle.getLong("actionModelBytes", 0L),
                bundle.getLong("actionModelTotalBytes", 0L),
                bundle.getLong("actionModelSizeBytes", 0L),
                bundle.getString("actionModelError", ""),
                bundle.getString("actionModelLicense", ""),
                bundle.getString("actionWorkState", ""),
                bundle.getBoolean("actionPauseRequested", false),
                bundle.getString("actionLastFailure", ""));
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

    /** Something is actively happening to the model right now. */
    public boolean modelBusy() {
        return DOWNLOADING.equals(modelState) || VALIDATING.equals(modelState)
                || IMPORTING.equals(modelState);
    }

    /**
     * The download exists and is going somewhere, even if no bytes are moving this second.
     *
     * <p>What the Orbit Local screen keeps watching. A queued or offline download is still live
     * work that will resume by itself, so the screen must keep looking at it rather than settling
     * on a stale figure.
     */
    public boolean modelInFlight() {
        return modelBusy() || QUEUED.equals(modelState) || WAITING_FOR_NETWORK.equals(modelState);
    }

    /** Stopped part-way with bytes worth keeping, whoever or whatever stopped it. */
    public boolean modelResumable() {
        return PAUSED.equals(modelState) || INTERRUPTED.equals(modelState);
    }

    /** Whether a progress bar belongs on screen for this state. */
    public boolean showsProgress() {
        return modelInFlight() || modelResumable();
    }

    /** Progress through the download or import, 0-1000, for the existing progress bar. */
    public int progressPerMille() {
        if (modelSizeBytes <= 0L) return 0;
        return (int) Math.max(0L, Math.min(1000L, modelBytes * 1000L / modelSizeBytes));
    }

    // ---- the action model, read through the same rules ------------------------------------------

    public boolean actionModelReady() { return READY.equals(actionModelState); }

    public boolean actionModelBusy() {
        return DOWNLOADING.equals(actionModelState) || VALIDATING.equals(actionModelState);
    }

    public boolean actionModelInFlight() {
        return actionModelBusy() || QUEUED.equals(actionModelState)
                || WAITING_FOR_NETWORK.equals(actionModelState);
    }

    public boolean actionModelResumable() {
        return PAUSED.equals(actionModelState) || INTERRUPTED.equals(actionModelState);
    }

    public boolean actionShowsProgress() { return actionModelInFlight() || actionModelResumable(); }

    /** Progress through the action model's download, 0-1000, for the shared progress bar. */
    public int actionProgressPerMille() {
        if (actionModelSizeBytes <= 0L) return 0;
        return (int) Math.max(0L, Math.min(1000L,
                actionModelBytes * 1000L / actionModelSizeBytes));
    }

    public String actionStateLabel() { return labelFor(actionModelState); }

    /** The short pill label the Orbit Local screen shows for the model. */
    public String stateLabel() { return labelFor(modelState); }

    /** One vocabulary for both models, so the two cards can never describe a state differently. */
    static String labelFor(String state) {
        switch (state == null ? "" : state) {
            case READY: return "Ready";
            case DOWNLOADING: return "Downloading";
            case QUEUED: return "Preparing";
            case WAITING_FOR_NETWORK: return "Waiting to connect";
            case INTERRUPTED: return "Interrupted";
            case VALIDATING: return "Verifying";
            case IMPORTING: return "Moving";
            case PAUSED: return "Paused";
            case ERROR: return "Attention";
            default: return "Not installed";
        }
    }
}
