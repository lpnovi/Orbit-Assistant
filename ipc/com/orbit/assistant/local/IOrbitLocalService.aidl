package com.orbit.assistant.local;

import com.orbit.assistant.local.IOrbitLocalCallback;

/**
 * The whole contract between Orbit and its optional Orbit Local component.
 *
 * <p>Deliberately small. Orbit keeps the provider, the conversation, the prompt shape, memory,
 * and the entire Action Engine; the component owns only the inference runtime and the model file
 * that runtime needs. Nothing about Orbit's assistant behaviour is duplicated across this
 * boundary.
 *
 * <p>Nothing large crosses it either. The model is never passed as a byte array: it is downloaded
 * by the component itself, or streamed in once through a file descriptor during migration.
 *
 * <p>Versioned by {@link #protocolVersion}, which is checked before any other call. Orbit refuses
 * to run inference through an interface it does not understand rather than guessing.
 */
interface IOrbitLocalService {

    /**
     * The IPC contract version this component implements.
     *
     * <p>Intentionally not the app's semantic version: the component and Orbit can be released at
     * different versions and still speak the same protocol.
     */
    int protocolVersion();

    /**
     * Everything Orbit's management screen and provider need, in one call.
     *
     * <p>A Bundle rather than a parcelable so a later protocol can add a key without breaking an
     * older reader. Keys are documented in Orbit's OrbitLocalStatus.
     *
     * <p>Cheap enough to poll while the Orbit Local screen is open: the component answers from its
     * own preferences and file sizes, and consults WorkManager only when that answer could change
     * the outcome.
     */
    Bundle status();

    /**
     * Starts or resumes the model download inside the component.
     *
     * <p>Idempotent. Calling it while a download is genuinely running withdraws any standing pause
     * request and changes nothing else, so a second tap can never produce a second download.
     */
    void startModelDownload();

    /**
     * The user asked to stop. Partial bytes are kept so it can resume.
     *
     * <p>The only call that may put the component into PAUSED. From protocol 2 that state means
     * exactly one thing — someone chose it — and a lost network, an exhausted retry, a truncated
     * stream, or an unanswerable WorkManager query report themselves as what they are instead.
     */
    void pauseModelDownload();

    /** Stops a download and discards its partial bytes. */
    void cancelModelDownload();

    /** Deletes the model, its partial bytes, and its state. The component itself stays installed. */
    void deleteModel();

    /**
     * Copies an existing Orbit model into the component through a read-only descriptor.
     *
     * <p>Returns immediately; the copy runs in the component's own background work and reports
     * through {@link #status}. The component verifies size and SHA-256 before promoting the copy,
     * so Orbit can only delete its legacy file after seeing a READY state here.
     */
    boolean startModelImport(in ParcelFileDescriptor source, long expectedBytes);

    /** Abandons an import in progress and removes its partial destination file. */
    void abortModelImport();

    /** Runs one streaming generation. The prompt is already fully built by Orbit. */
    void generate(String prompt, IOrbitLocalCallback callback);

    /** Stops the generation in progress, if any. Whatever was produced is still delivered. */
    void cancelGeneration();

    /** Frees the loaded model from memory. Safe at any time. */
    void unloadEngine();
}
