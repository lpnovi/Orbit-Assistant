package com.orbit.assistant;

/**
 * The milestones Orbit is actually working towards, written once.
 *
 * <p>Orbit keeps two roadmaps and they drifted. `ROADMAP.md` is where the plan is argued out and
 * where the development history lives; {@link RoadmapActivity} is what a user opens in About &amp;
 * updates. By the Vault line the in-app page was still presenting 0.7.7-era priorities as if they
 * were next, which is worse than having no page at all: somebody reading it came away with a
 * confident and wrong idea of where the project was going.
 *
 * <p>This is the smallest thing that stops it happening again. The names of the active milestones
 * live here, the in-app page draws its headings from them, and one test asserts that each of them
 * appears in `ROADMAP.md` as well. It is deliberately not a Markdown parser and deliberately not a
 * runtime fetch: no network, no schema, and nothing that could fail on a user's phone. If a
 * milestone is renamed or dropped in one place and not the other, the build says so.
 */
public final class OrbitRoadmap {

    /** What is being built right now. */
    public static final String CURRENT = "Vault organization";
    /** The next major line after the Vault reaches Stable. */
    public static final String NEXT = "Rich Answers";
    /**
     * The smaller piece of the same release as {@link #NEXT}.
     *
     * <p>Listed here rather than left to prose because it is exactly the kind of entry that gets
     * quietly promoted or quietly dropped. Settings search ships alongside Rich Answers in
     * {@code 0.7.8.5}; it is not the headline of that release and it is not a milestone of its own.
     */
    public static final String ALONGSIDE = "Settings search";
    /** And the one after that. */
    public static final String AFTER = "Smart Vault";

    /**
     * Every milestone both roadmaps must agree about.
     *
     * <p>The list is short on purpose. It is a guard against silent drift on the things a reader
     * would be actively misled by, not an index of everything either document says.
     */
    public static final String[] MILESTONES = {CURRENT, NEXT, ALONGSIDE, AFTER};

    private OrbitRoadmap() {}
}
