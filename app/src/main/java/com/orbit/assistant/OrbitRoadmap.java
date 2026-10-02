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

    /**
     * The active milestone: AI Control, through v0.8.3.0-beta.5. Orbit Local 2.0 (Stable in 0.8.2.0)
     * and Smart Vault (Stable in 0.8.1.0) stay listed beside it for the work still ahead.
     */
    public static final String CURRENT = "AI Control";

    /** The previous headline line, still NOW for what remains of it. */
    public static final String ORBIT_LOCAL_2 = "Orbit Local 2.0";

    /** The previous headline line, still NOW for what remains of it. */
    public static final String SMART_VAULT = "Smart Vault";

    /**
     * Every milestone both roadmaps must agree about.
     *
     * <p>The list is short on purpose. It is a guard against silent drift on the things a reader
     * would be actively misled by, not an index of everything either document says.
     */
    public static final String[] MILESTONES = {CURRENT, ORBIT_LOCAL_2, SMART_VAULT};

    private OrbitRoadmap() {}
}
