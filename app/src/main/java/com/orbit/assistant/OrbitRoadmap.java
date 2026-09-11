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
     * The active line: the optional paid layer that begins the {@code 0.8} series.
     *
     * <p>Smart Vault held this constant until {@code 0.8.0.0-beta.1} and was deliberately moved
     * out of it rather than dropped. It is still planned, still a free Orbit feature, and still
     * listed on both roadmaps; what changed is that it is no longer the thing being built next.
     * Presenting it as current while the whole project had moved to Orbit Pro is exactly the
     * drift this constant exists to prevent.
     */
    public static final String CURRENT = "Orbit Pro";

    /**
     * Every milestone both roadmaps must agree about.
     *
     * <p>The list is short on purpose. It is a guard against silent drift on the things a reader
     * would be actively misled by, not an index of everything either document says.
     */
    public static final String[] MILESTONES = {CURRENT};

    private OrbitRoadmap() {}
}
