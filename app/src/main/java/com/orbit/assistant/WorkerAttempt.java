package com.orbit.assistant;

/**
 * What WorkManager can tell Orbit about the worker execution it is currently inside.
 *
 * <h2>What the run count actually counts</h2>
 *
 * <p>This exists mainly to stop Orbit misreporting one number. Beta 3 recorded
 * {@code getRunAttemptCount()} in Diagnostics and labelled it "attempt", describing it in the code
 * as WorkManager's retry counter. It is not a retry counter. In work-runtime 2.11.2 the count is
 * incremented in {@code WorkerWrapper.trySetRunning()}, on the transition from ENQUEUED to RUNNING
 * — that is, every single time this work is <em>started</em>, whatever caused the start:
 *
 * <ul>
 *   <li>an explicit {@code Result.retry()} from Orbit,</li>
 *   <li>a system interruption — a lost constraint, doze, memory pressure, the execution time
 *       limit — after which WorkManager re-enqueues the same work,</li>
 *   <li>the process being killed and the work being picked up again afterwards.</li>
 * </ul>
 *
 * <p>The running worker sees the value from before its own increment, so the first execution reads
 * 0, the second reads 1, and so on. "attempt 3" therefore means "this is the fourth time this work
 * has been started", not "Orbit has retried three times" — a distinction that matters, because
 * {@link OrbitRequestWorker} only ever asks for one retry of its own. It is reported as a run
 * number for that reason.
 *
 * <h2>Why the stopped flag is here too</h2>
 *
 * <p>Stopping a {@code Worker} does not end it. WorkManager sets the stopped flag, re-enqueues the
 * work, and lets the next attempt start; the thread already inside {@code doWork()} keeps running
 * until it returns on its own. So "which run is this" and "has this run already been superseded"
 * are two halves of one question, and a refusal that records only the first cannot distinguish the
 * competing attempts.
 *
 * <p>Both values are Orbit's own observations of its own worker. Neither derives from anything the
 * user typed, said, or was told.
 */
public final class WorkerAttempt {

    /** A caller with no worker behind it, such as a test or a future non-worker path. */
    public static final WorkerAttempt NONE = new WorkerAttempt(-1, false);

    /** {@code getRunAttemptCount()}: how many times this work had been started before this one. */
    public final int priorRuns;

    /** {@code isStopped()}: WorkManager has taken this attempt's execution away. */
    public final boolean stopped;

    private WorkerAttempt(int priorRuns, boolean stopped) {
        this.priorRuns = priorRuns;
        this.stopped = stopped;
    }

    public static WorkerAttempt of(int priorRuns, boolean stopped) {
        return priorRuns < 0 ? NONE : new WorkerAttempt(priorRuns, stopped);
    }

    /** A worker's raw count, with no stopped observation attached. */
    public static WorkerAttempt of(int priorRuns) {
        return of(priorRuns, false);
    }

    /** False for {@link #NONE}, where there is no worker and therefore nothing to report. */
    public boolean known() { return priorRuns >= 0; }

    /** The human-readable ordinal: the first execution is run 1, not run 0. */
    public int runNumber() { return priorRuns + 1; }

    /** Compact, stable, and safe to show: {@code "run 4"}, or {@code "run 4 stopped"}. */
    public String describe() {
        if (!known()) return "";
        return "run " + runNumber() + (stopped ? " stopped" : "");
    }
}
