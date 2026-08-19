package com.orbit.assistant;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The geometry of an IF / ELSE branch inside a saved Routine.
 *
 * <p>A Routine is still one flat ordered list of actions. An {@code IF_CONDITION} step already
 * declared {@code nextSteps}, the number of following steps it guards; v0.7.5.0 adds an optional
 * {@code elseSteps}, the number of steps immediately after those that run instead when the
 * condition is false. Nothing else about the model changes, so an existing Routine — which has no
 * {@code elseSteps} key at all — describes exactly the same execution it described in v0.7.4.2.
 *
 * <pre>
 *   index c      IF condition        (trueSteps = N, elseSteps = M)
 *   c+1 … c+N    the IF path         run when the condition is true
 *   c+N+1 … c+N+M the ELSE path      run when the condition is false
 *   c+N+M+1 …    the rest of the Routine, which always runs
 * </pre>
 *
 * <p>Exactly one branch executes. This class is the single authority on where each branch starts
 * and ends: the Action Engine, the background trigger scan, the editor, and the planner all read
 * their answer from here rather than each re-deriving it, which is how the three copies of the
 * old "skip the gated steps" arithmetic are kept from drifting apart.
 *
 * <p>Every span is clamped to the real list, so a Routine whose declared counts overrun its steps
 * still executes deterministically instead of reading past the end.
 */
public final class RoutineBranch {
    /** Steps a single branch may hold, matching the existing IF gate limit. */
    public static final int MAX_BRANCH_STEPS = 5;

    /** Params key holding the ELSE step count. Absent, or 0, means the Routine has no ELSE path. */
    public static final String KEY_ELSE_STEPS = "elseSteps";

    public static final int BRANCH_NONE = 0;
    public static final int BRANCH_TRUE = 1;
    public static final int BRANCH_ELSE = 2;

    private RoutineBranch() {}

    // ---- reading one condition ---------------------------------------------------------------

    /** Steps on the IF path. Always at least one, exactly as the existing gate has always been. */
    public static int trueSteps(AssistantReply.Action condition) {
        return RoutineConditionEvaluator.gatedSteps(condition);
    }

    /** Steps on the ELSE path, or 0 when this condition has no ELSE. */
    public static int elseSteps(AssistantReply.Action condition) {
        if (!RoutineConditionEvaluator.isCondition(condition)) return 0;
        JSONObject p = condition.params == null ? new JSONObject() : condition.params;
        return Math.max(0, Math.min(MAX_BRANCH_STEPS, p.optInt(KEY_ELSE_STEPS, 0)));
    }

    public static boolean hasElse(AssistantReply.Action condition) {
        return elseSteps(condition) > 0;
    }

    // ---- geometry -----------------------------------------------------------------------------

    /** Where each branch of one condition begins and ends, clamped to the Routine's real length. */
    public static final class Span {
        public final int conditionIndex;
        /** IF path, as a half-open range. */
        public final int trueStart;
        public final int trueEnd;
        /** ELSE path, as a half-open range. Empty when the condition has no ELSE. */
        public final int elseStart;
        public final int elseEnd;
        /** Declared counts, before clamping, so validation can tell short from malformed. */
        public final int declaredTrue;
        public final int declaredElse;

        Span(int conditionIndex, int trueStart, int trueEnd, int elseStart, int elseEnd,
             int declaredTrue, int declaredElse) {
            this.conditionIndex = conditionIndex;
            this.trueStart = trueStart;
            this.trueEnd = trueEnd;
            this.elseStart = elseStart;
            this.elseEnd = elseEnd;
            this.declaredTrue = declaredTrue;
            this.declaredElse = declaredElse;
        }

        public boolean hasElsePath() { return elseEnd > elseStart; }
        public boolean hasTruePath() { return trueEnd > trueStart; }
        /** The first step after the whole branch, where the Routine continues either way. */
        public int continueAt() { return elseEnd; }
        /** Steps covered by either branch, as a half-open range end. */
        public int spanEnd() { return elseEnd; }
    }

    /** The branch beginning at {@code conditionIndex}, or null when that step is not a condition. */
    public static Span spanAt(List<AssistantReply.Action> actions, int conditionIndex) {
        if (actions == null || conditionIndex < 0 || conditionIndex >= actions.size()) return null;
        AssistantReply.Action condition = actions.get(conditionIndex);
        if (!RoutineConditionEvaluator.isCondition(condition)) return null;
        int size = actions.size();
        int declaredTrue = trueSteps(condition);
        int declaredElse = elseSteps(condition);
        int trueStart = Math.min(size, conditionIndex + 1);
        int trueEnd = Math.min(size, trueStart + declaredTrue);
        int elseStart = trueEnd;
        int elseEnd = Math.min(size, elseStart + declaredElse);
        return new Span(conditionIndex, trueStart, trueEnd, elseStart, elseEnd,
                declaredTrue, declaredElse);
    }

    /** Every condition's branch, in order. */
    public static List<Span> spans(List<AssistantReply.Action> actions) {
        List<Span> out = new ArrayList<>();
        if (actions == null) return out;
        for (int i = 0; i < actions.size(); i++) {
            Span span = spanAt(actions, i);
            if (span != null) out.add(span);
        }
        return out;
    }

    /**
     * Which branch, if any, each step belongs to. Used by the editor so a step's membership is
     * visible rather than something the user has to count out by hand.
     */
    public static int[] branchMap(List<AssistantReply.Action> actions) {
        int size = actions == null ? 0 : actions.size();
        int[] map = new int[size];
        int[] owner = new int[size];
        for (int i = 0; i < size; i++) owner[i] = -1;
        for (int i = 0; i < size; i++) {
            Span span = spanAt(actions, i);
            if (span == null) continue;
            // Gated steps are labelled for every condition, with or without an ELSE, because
            // "these steps are the ones the IF guards" was always true and was never shown.
            for (int j = span.trueStart; j < span.trueEnd; j++) {
                if (map[j] == BRANCH_NONE) { map[j] = BRANCH_TRUE; owner[j] = i; }
            }
            for (int j = span.elseStart; j < span.elseEnd; j++) {
                if (map[j] == BRANCH_NONE) { map[j] = BRANCH_ELSE; owner[j] = i; }
            }
        }
        return map;
    }

    /** The condition each step belongs to, or -1. Parallel to {@link #branchMap}. */
    public static int[] branchOwners(List<AssistantReply.Action> actions) {
        int size = actions == null ? 0 : actions.size();
        int[] owner = new int[size];
        for (int i = 0; i < size; i++) owner[i] = -1;
        for (int i = 0; i < size; i++) {
            Span span = spanAt(actions, i);
            if (span == null) continue;
            for (int j = span.trueStart; j < span.elseEnd; j++) if (owner[j] < 0) owner[j] = i;
        }
        return owner;
    }

    // ---- validation ---------------------------------------------------------------------------

    /**
     * Why this Routine's branching is malformed, or an empty string when it is fine.
     *
     * <p>Deliberately narrow: the rules constrain only conditions that actually declare an ELSE.
     * A Routine written before v0.7.5.0 has no {@code elseSteps} anywhere, so it cannot fail this
     * and keeps its v0.7.4.2 behaviour exactly, including the long-standing tolerance for an IF
     * whose gate reaches past the last step.
     */
    public static String structureProblem(List<AssistantReply.Action> actions) {
        if (actions == null || actions.isEmpty()) return "";
        int size = actions.size();
        for (int i = 0; i < size; i++) {
            AssistantReply.Action condition = actions.get(i);
            if (!RoutineConditionEvaluator.isCondition(condition)) continue;
            int declaredElse = elseSteps(condition);
            if (declaredElse <= 0) continue;
            int declaredTrue = trueSteps(condition);
            int end = i + 1 + declaredTrue + declaredElse;
            if (end > size) {
                return "Step " + (i + 1) + " needs " + declaredTrue + " IF "
                        + (declaredTrue == 1 ? "step" : "steps") + " and " + declaredElse + " ELSE "
                        + (declaredElse == 1 ? "step" : "steps") + " after it. Add the missing "
                        + "steps or reduce the branch.";
            }
            // One dependable level of branching. A condition inside a branch would make the paths
            // overlap, which is neither drawable in the editor nor worth the execution ambiguity.
            for (int j = i + 1; j < end; j++) {
                if (RoutineConditionEvaluator.isCondition(actions.get(j))) {
                    return "Step " + (j + 1) + " is another IF condition inside the branch that "
                            + "starts at step " + (i + 1) + ". Branches cannot be nested.";
                }
            }
            // And the condition itself must not sit inside another condition's window, whether or
            // not that outer one has an ELSE: an outer gate that skips part of an inner branch
            // would leave the inner ELSE half-executed.
            for (int j = 0; j < i; j++) {
                AssistantReply.Action outer = actions.get(j);
                if (!RoutineConditionEvaluator.isCondition(outer)) continue;
                int outerEnd = Math.min(size, j + 1 + trueSteps(outer) + elseSteps(outer));
                if (i < outerEnd) {
                    return "Step " + (i + 1) + " is an IF condition inside the branch that starts "
                            + "at step " + (j + 1) + ". Branches cannot be nested.";
                }
            }
        }
        return "";
    }

    public static boolean structureValid(List<AssistantReply.Action> actions) {
        return structureProblem(actions).isEmpty();
    }

    // ---- execution flow -------------------------------------------------------------------------

    /**
     * Where execution goes after each step, so the Action Engine never has to look backwards.
     *
     * <p>Only one entry is ever non-default: the last step of an IF path that has an ELSE. Reaching
     * it means the IF path ran, so the ELSE path must be stepped over. That is a fixed property of
     * the branch, not of how the condition evaluated, which is what keeps it safe to precompute.
     */
    public static final class Flow {
        private final int[] next;
        private final int[] skipFrom;
        private final int[] skipTo;

        Flow(int[] next, int[] skipFrom, int[] skipTo) {
            this.next = next;
            this.skipFrom = skipFrom;
            this.skipTo = skipTo;
        }

        /** The index to continue at after normally finishing the step at {@code index}. */
        public int nextAfter(int index) {
            if (next == null || index < 0 || index >= next.length) return index + 1;
            return next[index];
        }

        /** Start of the region to report as skipped before continuing past {@code index}. */
        public int skipFromAfter(int index) {
            if (skipFrom == null || index < 0 || index >= skipFrom.length) return 0;
            return skipFrom[index];
        }

        /** End of that region. Equal to the start when nothing is skipped. */
        public int skipToAfter(int index) {
            if (skipTo == null || index < 0 || index >= skipTo.length) return 0;
            return skipTo[index];
        }
    }

    public static Flow flow(List<AssistantReply.Action> actions) {
        int size = actions == null ? 0 : actions.size();
        int[] next = new int[size];
        int[] skipFrom = new int[size];
        int[] skipTo = new int[size];
        for (int i = 0; i < size; i++) {
            next[i] = i + 1;
            skipFrom[i] = 0;
            skipTo[i] = 0;
        }
        for (int i = 0; i < size; i++) {
            Span span = spanAt(actions, i);
            if (span == null || !span.hasElsePath() || !span.hasTruePath()) continue;
            int lastTrue = span.trueEnd - 1;
            next[lastTrue] = span.elseEnd;
            skipFrom[lastTrue] = span.elseStart;
            skipTo[lastTrue] = span.elseEnd;
        }
        return new Flow(next, skipFrom, skipTo);
    }

    // ---- which steps a run will not execute -----------------------------------------------------

    /** How a condition evaluated. {@code null} means Orbit could not decide. */
    public interface Outcome {
        Boolean matched(int conditionIndex, AssistantReply.Action condition);
    }

    /**
     * The steps a run will step over, given how each condition evaluates right now.
     *
     * <p>Background execution needs this before it starts: a step on the path that will not run
     * must not force a foreground handoff, demand a confirmation, or fail a special-access
     * preflight for something the user will never see happen.
     */
    public static boolean[] skippedSteps(List<AssistantReply.Action> actions, Outcome outcome) {
        int size = actions == null ? 0 : actions.size();
        boolean[] skipped = new boolean[size];
        if (size == 0 || outcome == null) return skipped;
        for (int i = 0; i < size; i++) {
            if (skipped[i]) continue;
            Span span = spanAt(actions, i);
            if (span == null) continue;
            Boolean matched = outcome.matched(i, actions.get(i));
            if (matched == null) continue;
            int from = matched ? span.elseStart : span.trueStart;
            int to = matched ? span.elseEnd : span.trueEnd;
            for (int j = from; j < to; j++) skipped[j] = true;
        }
        return skipped;
    }
}
