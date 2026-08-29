package com.orbit.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The kinds of hard thinking a request can demand, detected from the user's own words.
 *
 * <p>Orbit's Auto router used to score a prompt with a handful of broad keyword buckets. That
 * treated "compare these two phones" and "compare three concurrent architectures, identify the
 * race conditions, and then challenge your own recommendation" as the same kind of work, because
 * both matched the word <em>compare</em> and nothing else accumulated. A genuinely difficult
 * architecture prompt therefore collapsed into the generic planning bucket and was answered by the
 * middle model.
 *
 * <p>A dimension is deliberately narrower than a keyword. Each one names a distinct axis of
 * difficulty, and each is matched by phrases that are hard to produce by accident. Depth is then
 * earned by <em>combining</em> several of them, which is what separates a hard request from a long
 * one: verbosity matches nothing here, and one polite "think carefully" matches nothing here
 * either.
 *
 * <p>Detection is local, deterministic, and reads only the prompt that was already going to be
 * sent. Nothing here is recorded; only the dimension's short label can reach diagnostics.
 */
public enum ReasoningDimension {

    /** Designing a system with several interacting parts, rather than answering about one. */
    ARCHITECTURE("complex architecture",
            "architecture", "architectural", "system design", "design a system",
            "state machine", "control flow", "data model", "schema design",
            "distributed system", "design an? [a-z ]{0,24}(system|pipeline|protocol|machine|architecture)",
            "component boundaries", "separation of concerns", "end.to.end design"),

    /** Two things happening at once, and what that can do to correctness. */
    CONCURRENCY("concurrency and race analysis",
            "race condition", "race conditions", "concurren", "deadlock", "livelock",
            "thread.safe", "thread safety", "atomicity", "idempotent", "idempotency",
            "at most once", "at least once", "exactly once", "mutual exclusion",
            "synchroniz", "critical section", "simultaneous", "at the same time that"),

    /** Correctness across a process or component that can disappear mid-operation. */
    LIFECYCLE("process and lifecycle correctness",
            "process death", "lifecycle", "recreated", "recreation", "cold start",
            "crash recovery", "resume correctly", "persistence boundar", "durable state",
            "survive a restart", "survives restart", "restored after", "recover(s|ing)? correctly"),

    /** Several candidate answers that have to be weighed against each other. */
    MULTI_OPTION("multi-option evaluation",
            "compare at least", "at least (two|three|four|2|3|4)",
            "(two|three|four|several|multiple) (possible |candidate |competing |different )?"
                    + "(options|approaches|architectures|designs|strategies|alternatives|solutions)",
            "alternatives", "trade.?offs", "pros and cons", "weigh the options",
            "which of these", "option a", "each approach"),

    /**
     * What breaks, not just what works.
     *
     * <p>Constructing a counterexample belongs here rather than with {@link #PROOF}: showing that
     * something fails without a given assumption is adversarial work, and it is a different skill
     * from arguing that it holds with one.
     */
    FAILURE_MODE("failure-mode analysis",
            "failure mode", "failure modes", "edge case", "corner case", "adversarial",
            "what could go wrong", "counterexample", "fault toleran", "error handling",
            "attack surface", "threat model", "how it breaks", "break(s)? down when"),

    /** Reasoning that has to hold, not merely sound reasonable. */
    PROOF("proof or invariant reasoning",
            "invariant", "prove ", "proof", "formally", "guarantee(s|d)? that",
            "correctness argument", "holds for all", "by induction"),

    /**
     * How the cost grows, which is its own kind of analysis.
     *
     * <p>Kept apart from {@link #PROOF} deliberately. Arguing that an algorithm is correct and
     * working out what it costs at scale are separate pieces of reasoning, and a request that asks
     * for both has asked for two hard things, not one.
     */
    COMPLEXITY("complexity analysis",
            "big.?o\\b", "o\\(n", "time complexity", "space complexity",
            "computational complexity", "asymptotic", "worst.?case (time|space|run.?time)",
            "scales? to (millions|billions)", "hot path"),

    /** Being asked to argue against the answer that was just given. */
    SELF_CRITIQUE("self-critique requested",
            "challenge your", "critique your own", "argue against your",
            "re.?evaluate your", "second.guess", "poke holes", "steelman",
            "devil's advocate", "where (are|could) you (be )?wrong",
            "then reconsider", "challenge (your|the) initial"),

    /** Debugging where several plausible causes have to be separated. */
    ROOT_CAUSE("multi-cause debugging",
            "root cause", "why (does|is) (it|this) (sometimes|intermittently|occasionally)",
            "intermittent", "non.?deterministic", "only happens when",
            "narrow down (the|which)", "isolate the cause", "reproduce the (bug|issue|failure)"),

    /** Planning where the constraints, not the steps, are the hard part. */
    CONSTRAINTS("multi-constraint planning",
            "constraints", "subject to", "within (a |the )?budget of", "optimi[sz]e for",
            "competing (priorities|requirements|goals)", "must (also )?satisfy",
            "without (breaking|sacrificing|compromising)");

    /** Short, non-identifying label used in the Auto routing reason shown in Diagnostics. */
    public final String label;

    private final Pattern[] patterns;

    ReasoningDimension(String label, String... expressions) {
        this.label = label;
        this.patterns = new Pattern[expressions.length];
        for (int i = 0; i < expressions.length; i++) {
            this.patterns[i] = Pattern.compile(expressions[i], Pattern.CASE_INSENSITIVE);
        }
    }

    /** True when this prompt shows this kind of difficulty. */
    public boolean matches(String normalizedPrompt) {
        if (normalizedPrompt == null || normalizedPrompt.isEmpty()) return false;
        for (Pattern p : patterns) {
            if (p.matcher(normalizedPrompt).find()) return true;
        }
        return false;
    }

    /**
     * Every dimension this prompt shows, in declaration order.
     *
     * <p>The count is the signal that matters. One dimension is ordinary work that the middle
     * model handles well; several at once is the shape of a request whose answer has to hold
     * together across concerns, which is what Deep exists for.
     */
    public static List<ReasoningDimension> detect(String normalizedPrompt) {
        List<ReasoningDimension> found = new ArrayList<>();
        if (normalizedPrompt == null || normalizedPrompt.isEmpty()) return found;
        String p = normalizedPrompt.toLowerCase(Locale.US);
        for (ReasoningDimension d : values()) if (d.matches(p)) found.add(d);
        return found;
    }
}
