package com.orbit.assistant;

import android.os.Handler;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The Orbit Local screen's live view of the component, bound to the screen's visibility.
 *
 * <p>One loop, owned here: {@link #start} when the screen becomes visible, {@link #stop} when it
 * stops being visible. While running it reads the component's status off the main thread, hands
 * every reading to the screen, and says whether anything the screen draws actually changed. It
 * reads every {@link #ACTIVE_MS} while either model is queued, downloading, waiting, verifying or
 * importing, and every {@link #IDLE_MS} otherwise, which is slow enough to cost nothing and fast
 * enough to notice a download started from somewhere else.
 *
 * <p>Nothing about downloading lives here. The component's own WorkManager job does the download
 * whether or not this screen exists; this only looks at it.
 *
 * <p>Single-flight: a read is never started while another is outstanding, and every reschedule
 * first removes the pending one, so repeated start/stop, resume and button taps can never build up
 * parallel loops. A reading that lands after {@link #stop} is dropped and schedules nothing.
 */
final class OrbitLocalStatusPoller {

    /** How often a model that is moving is re-read. */
    static final long ACTIVE_MS = 700L;
    /** How often a settled component is re-read while the screen is visible. */
    static final long IDLE_MS = 2500L;
    /** The short delay after a button press, so its effect shows almost at once. */
    static final long NUDGE_MS = 250L;

    /** Reads the status asynchronously. The answer may arrive on any thread; null is "unreachable". */
    interface Source {
        void read(Consumer<OrbitLocalStatus> answer);
    }

    /** Receives each reading on the main thread. */
    interface Sink {
        void onStatus(OrbitLocalStatus status, boolean changed);
    }

    private final Handler main;
    private final Source source;
    private final Sink sink;
    /** Something the screen itself is doing that also needs the fast rate. */
    private final BooleanSupplier alsoActive;

    private boolean running;
    private boolean inFlight;
    private boolean hasReading;
    private OrbitLocalStatus last;
    private int reads;

    private final Runnable tick = this::readNow;

    OrbitLocalStatusPoller(Handler main, Source source, Sink sink, BooleanSupplier alsoActive) {
        this.main = main;
        this.source = source;
        this.sink = sink;
        this.alsoActive = alsoActive == null ? () -> false : alsoActive;
    }

    /** Starts watching, with an immediate read. Calling it again while running changes nothing. */
    void start() {
        if (running) return;
        running = true;
        main.removeCallbacks(tick);
        readNow();
    }

    /** Stops watching. Nothing is scheduled afterwards, and a late answer is ignored. */
    void stop() {
        running = false;
        main.removeCallbacks(tick);
    }

    /** Reads again shortly, e.g. right after the user started, paused or removed something. */
    void nudge() {
        if (!running) return;
        main.removeCallbacks(tick);
        main.postDelayed(tick, NUDGE_MS);
    }

    boolean isRunning() { return running; }

    /** Status reads started so far, for tests. */
    int reads() { return reads; }

    /** The last reading delivered, or null. */
    OrbitLocalStatus last() { return last; }

    /** The delay the next read will use, from the latest reading. */
    long nextDelay() {
        boolean active = alsoActive.getAsBoolean() || (last != null && last.anyInFlight());
        return active ? ACTIVE_MS : IDLE_MS;
    }

    private void readNow() {
        if (!running || inFlight) return;
        inFlight = true;
        reads++;
        source.read(fresh -> main.post(() -> deliver(fresh)));
    }

    private void deliver(OrbitLocalStatus fresh) {
        inFlight = false;
        if (!running) return;
        boolean changed = !hasReading || !fingerprint(last).equals(fingerprint(fresh));
        hasReading = true;
        last = fresh;
        sink.onStatus(fresh, changed);
        // Scheduled from the reading that just arrived and never from the one it replaced:
        // deciding before the answer lands is what once froze the progress bar in 0.7.7.5.
        if (!running) return;
        main.removeCallbacks(tick);
        main.postDelayed(tick, nextDelay());
    }

    static String fingerprint(OrbitLocalStatus status) {
        return status == null ? "unreachable" : status.displayFingerprint();
    }
}
