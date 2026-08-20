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

    // ---- editing --------------------------------------------------------------------------------

    /**
     * The routine read as a sequence of top-level units: either a whole branch block, or one
     * ordinary step.
     *
     * <p>This is what the editor lays out, and what reordering moves. Treating a branch as one unit
     * is what stops an ordinary step from being nudged into the middle of somebody's THEN path.
     */
    public static final class Unit {
        /** Half-open range of step indexes this unit covers. */
        public final int start;
        public final int end;
        /** True when this unit is an IF condition together with both of its paths. */
        public final boolean branch;

        Unit(int start, int end, boolean branch) {
            this.start = start;
            this.end = end;
            this.branch = branch;
        }

        public int size() { return end - start; }
    }

    public static List<Unit> units(List<AssistantReply.Action> actions) {
        List<Unit> out = new ArrayList<>();
        int size = actions == null ? 0 : actions.size();
        int i = 0;
        while (i < size) {
            Span span = spanAt(actions, i);
            if (span == null) {
                out.add(new Unit(i, i + 1, false));
                i++;
            } else {
                int end = Math.max(i + 1, span.spanEnd());
                out.add(new Unit(i, end, true));
                i = end;
            }
        }
        return out;
    }

    /** The unit containing {@code index}, or null. */
    public static Unit unitAt(List<AssistantReply.Action> actions, int index) {
        for (Unit unit : units(actions)) {
            if (index >= unit.start && index < unit.end) return unit;
        }
        return null;
    }

    /**
     * Reduces any declared count that reaches past the steps actually present.
     *
     * <p>Only ever reduces. A count is what defines its own path, so a count can never be inferred
     * back from the list — that would just restate whatever it already said. Growing a path is done
     * explicitly by the edit that adds to it; this exists for the one case where the stored number
     * is larger than reality, which a routine written before v0.7.5.0 is allowed to be.
     *
     * <p>Deliberately not called on load: opening a routine must never rewrite it, and such a gate
     * already executes as though it were clamped, so leaving it alone until the user actually edits
     * the branch changes nothing about how it runs.
     */
    public static void clampCounts(List<AssistantReply.Action> actions) {
        if (actions == null) return;
        for (int i = 0; i < actions.size(); i++) {
            Span span = spanAt(actions, i);
            if (span == null) continue;
            int trueCount = span.trueEnd - span.trueStart;
            int elseCount = span.elseEnd - span.elseStart;
            if (trueCount == span.declaredTrue && elseCount == span.declaredElse) continue;
            AssistantReply.Action rewritten = withCounts(actions.get(i), trueCount, elseCount);
            if (rewritten != null) actions.set(i, rewritten);
        }
    }

    /** Writes one path's size onto its condition, leaving the other path's size untouched. */
    private static void setPathCount(List<AssistantReply.Action> actions, int conditionIndex,
                                     int kind, int count) {
        if (actions == null || conditionIndex < 0 || conditionIndex >= actions.size()) return;
        AssistantReply.Action condition = actions.get(conditionIndex);
        if (!RoutineConditionEvaluator.isCondition(condition)) return;
        int trueCount = kind == BRANCH_TRUE ? count : trueSteps(condition);
        int elseCount = kind == BRANCH_ELSE ? count : elseSteps(condition);
        AssistantReply.Action rewritten = withCounts(condition, trueCount, elseCount);
        if (rewritten != null) actions.set(conditionIndex, rewritten);
    }

    /**
     * How many user actions sit inside a branch, across both of its paths.
     *
     * <p>Zero means removing the branch destroys nothing but the condition itself. Anything above
     * zero is what makes removal worth confirming, so the question is answered here rather than
     * recounted by whichever screen happens to be asking.
     */
    public static int branchActionCount(List<AssistantReply.Action> actions, int conditionIndex) {
        Span span = spanAt(actions, conditionIndex);
        if (span == null) return 0;
        return Math.max(0, span.spanEnd() - span.trueStart);
    }

    /** Steps currently on one path of the branch owned by {@code conditionIndex}. */
    public static int pathSize(List<AssistantReply.Action> actions, int conditionIndex, int kind) {
        Span span = spanAt(actions, conditionIndex);
        if (span == null) return 0;
        return kind == BRANCH_ELSE ? span.elseEnd - span.elseStart : span.trueEnd - span.trueStart;
    }

    /** Where a new action appended to one path would land. */
    public static int pathInsertIndex(List<AssistantReply.Action> actions, int conditionIndex, int kind) {
        Span span = spanAt(actions, conditionIndex);
        if (span == null) return -1;
        return kind == BRANCH_ELSE ? span.elseEnd : span.trueEnd;
    }

    /**
     * True when a path can take another action: it has room of its own, the routine has room, and
     * an ELSE path is only offered once the IF path has something to be an alternative to. The
     * stored model has no way to express an ELSE without an IF path, so the editor does not offer
     * one rather than silently miscounting the first ELSE action as an IF step.
     */
    public static boolean canAddTo(List<AssistantReply.Action> actions, int conditionIndex, int kind) {
        if (actions == null || actions.size() >= RoutineActionCatalog.MAX_STEPS) return false;
        Span span = spanAt(actions, conditionIndex);
        if (span == null) return false;
        if (pathSize(actions, conditionIndex, kind) >= MAX_BRANCH_STEPS) return false;
        return kind != BRANCH_ELSE || span.hasTruePath();
    }

    /**
     * Adds an action to one path of a branch and updates that path's count.
     *
     * @param kind {@link #BRANCH_TRUE} or {@link #BRANCH_ELSE}
     * @return true when the action was added
     */
    public static boolean addToPath(List<AssistantReply.Action> actions, int conditionIndex,
                                    int kind, AssistantReply.Action action) {
        if (action == null || !canAddTo(actions, conditionIndex, kind)) return false;
        // Bring an overrunning legacy count in line first, so the new action lands where the
        // editor is showing the path end rather than where a stale number claims it is.
        clampCounts(actions);
        int at = pathInsertIndex(actions, conditionIndex, kind);
        if (at < 0 || at > actions.size()) return false;
        int grown = pathSize(actions, conditionIndex, kind) + 1;
        actions.add(at, action);
        setPathCount(actions, conditionIndex, kind, grown);
        return true;
    }

    /** Appends an ordinary step after everything else, outside any branch. */
    public static boolean addStep(List<AssistantReply.Action> actions, AssistantReply.Action action) {
        if (actions == null || action == null) return false;
        if (actions.size() >= RoutineActionCatalog.MAX_STEPS) return false;
        clampCounts(actions);
        actions.add(action);
        return true;
    }

    /**
     * Removes one step, shrinking only the path it belonged to.
     *
     * <p>Refuses to empty an IF path, because the stored model has no way to say "guards nothing"
     * and the remaining ELSE actions would silently become the IF path. Removing the condition
     * itself is the way to delete a branch.
     */
    public static boolean removeStep(List<AssistantReply.Action> actions, int index) {
        if (actions == null || index < 0 || index >= actions.size()) return false;
        if (RoutineConditionEvaluator.isCondition(actions.get(index))) return false;
        clampCounts(actions);
        int kind = branchMap(actions)[index];
        int owner = branchOwners(actions)[index];
        // Emptying an IF path is refused outright. The count cannot go below one, so whatever
        // followed the branch would quietly become the IF path instead.
        if (kind == BRANCH_TRUE && pathSize(actions, owner, BRANCH_TRUE) <= 1) return false;
        int shrunk = kind == BRANCH_NONE ? 0 : pathSize(actions, owner, kind) - 1;
        actions.remove(index);
        // The owning condition always sits before its own steps, so its index is unaffected.
        if (kind != BRANCH_NONE) setPathCount(actions, owner, kind, shrunk);
        return true;
    }

    /** Removes a condition together with both of its paths. */
    public static boolean removeBranch(List<AssistantReply.Action> actions, int conditionIndex) {
        clampCounts(actions);
        Span span = spanAt(actions, conditionIndex);
        if (span == null) return false;
        for (int i = Math.max(conditionIndex + 1, span.spanEnd()) - 1; i >= conditionIndex; i--) {
            actions.remove(i);
        }
        return true;
    }

    /** Copies one step in place, into the same path it already belongs to. */
    public static boolean duplicateStep(List<AssistantReply.Action> actions, int index) {
        if (actions == null || index < 0 || index >= actions.size()) return false;
        AssistantReply.Action original = actions.get(index);
        if (RoutineConditionEvaluator.isCondition(original)) return false;
        if (actions.size() >= RoutineActionCatalog.MAX_STEPS) return false;
        clampCounts(actions);
        int kind = branchMap(actions)[index];
        int owner = branchOwners(actions)[index];
        if (kind != BRANCH_NONE && pathSize(actions, owner, kind) >= MAX_BRANCH_STEPS) return false;
        AssistantReply.Action copy = RoutineActionCatalog.copy(original);
        if (copy == null) return false;
        int grown = kind == BRANCH_NONE ? 0 : pathSize(actions, owner, kind) + 1;
        actions.add(index + 1, copy);
        if (kind != BRANCH_NONE) setPathCount(actions, owner, kind, grown);
        return true;
    }

    /**
     * True when {@code index} can move one slot in {@code direction} (-1 up, +1 down).
     *
     * <p>A step on a path moves only within that path. Anything else moves as a whole top-level
     * unit, so an ordinary step steps over an entire branch instead of landing inside it, and a
     * branch carries both of its paths with it.
     */
    public static boolean canMove(List<AssistantReply.Action> actions, int index, int direction) {
        if (actions == null || index < 0 || index >= actions.size()) return false;
        if (direction != -1 && direction != 1) return false;
        int branch = branchMap(actions)[index];
        if (branch != BRANCH_NONE) {
            Span span = spanAt(actions, branchOwners(actions)[index]);
            if (span == null) return false;
            int from = branch == BRANCH_ELSE ? span.elseStart : span.trueStart;
            int to = branch == BRANCH_ELSE ? span.elseEnd : span.trueEnd;
            int target = index + direction;
            return target >= from && target < to;
        }
        List<Unit> units = units(actions);
        int position = unitPosition(units, index);
        int neighbour = position + direction;
        return position >= 0 && neighbour >= 0 && neighbour < units.size();
    }

    /** Performs the move described by {@link #canMove}. */
    public static boolean move(List<AssistantReply.Action> actions, int index, int direction) {
        if (!canMove(actions, index, direction)) return false;
        int branch = branchMap(actions)[index];
        if (branch != BRANCH_NONE) {
            AssistantReply.Action moved = actions.remove(index);
            actions.add(index + direction, moved);
            clampCounts(actions);
            return true;
        }
        List<Unit> units = units(actions);
        int position = unitPosition(units, index);
        Unit self = units.get(position);
        Unit other = units.get(position + direction);
        Unit first = direction < 0 ? other : self;
        Unit second = direction < 0 ? self : other;

        List<AssistantReply.Action> firstSteps =
                new ArrayList<>(actions.subList(first.start, first.end));
        List<AssistantReply.Action> secondSteps =
                new ArrayList<>(actions.subList(second.start, second.end));
        for (int i = second.end - 1; i >= first.start; i--) actions.remove(i);
        actions.addAll(first.start, firstSteps);
        actions.addAll(first.start, secondSteps);
        clampCounts(actions);
        return true;
    }

    private static int unitPosition(List<Unit> units, int index) {
        for (int i = 0; i < units.size(); i++) {
            if (index >= units.get(i).start && index < units.get(i).end) return i;
        }
        return -1;
    }

    /**
     * A copy of one condition carrying the given path sizes.
     *
     * <p>{@code elseSteps} is removed rather than written as zero, so a branch that loses its last
     * ELSE action goes back to being stored exactly as an ordinary IF-only condition — the same
     * bytes every release before v0.7.5.0 wrote.
     */
    private static AssistantReply.Action withCounts(AssistantReply.Action condition,
                                                    int trueCount, int elseCount) {
        AssistantReply.Action copy = RoutineActionCatalog.copy(condition);
        if (copy == null || copy.params == null) return null;
        int safeTrue = Math.max(1, Math.min(MAX_BRANCH_STEPS, trueCount));
        int safeElse = Math.max(0, Math.min(MAX_BRANCH_STEPS, elseCount));
        try {
            copy.params.put("nextSteps", safeTrue);
            if (safeElse > 0) copy.params.put(KEY_ELSE_STEPS, safeElse);
            else copy.params.remove(KEY_ELSE_STEPS);
        } catch (Exception ignored) {
            return null;
        }
        return copy;
    }
}
