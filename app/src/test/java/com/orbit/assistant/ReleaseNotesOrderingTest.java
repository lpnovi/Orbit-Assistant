package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * What's New ordering and release validation. The GitHub collection endpoint returns Orbit's tags
 * lexicographically, so v0.7.2.15 sorts below v0.7.2.2 and taking the first few entries pinned the
 * screen at v0.7.2.9; Orbit therefore sorts by version itself and only accepts real Releases.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class ReleaseNotesOrderingTest {

    private static JSONObject release(String tag, boolean draft, boolean prerelease) {
        try {
            return new JSONObject()
                    .put("tag_name", tag)
                    .put("draft", draft)
                    .put("prerelease", prerelease)
                    .put("name", "Orbit Assistant " + tag)
                    .put("body", "Notes for " + tag)
                    .put("published_at", "2026-08-14T00:00:00Z");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JSONObject published(String tag) {
        return release(tag, false, false);
    }

    @Test public void fourPartVersionsCompareNumerically() {
        assertTrue("0.7.2.10 must rank above 0.7.2.9",
                ReleaseNotesRepository.compareVersions("0.7.2.10", "0.7.2.9") > 0);
        assertTrue(ReleaseNotesRepository.compareVersions("0.7.2.15", "0.7.2.2") > 0);
        assertTrue(ReleaseNotesRepository.compareVersions("0.7.2.15", "0.7.2.14") > 0);
        assertTrue(ReleaseNotesRepository.compareVersions("0.8.0.0", "0.7.9.9") > 0);
        assertEquals(0, ReleaseNotesRepository.compareVersions("0.7.2.9", "0.7.2.9"));
    }

    @Test public void lexicographicOrderWouldHaveBeenWrong() {
        // The exact comparison that froze the list: as strings, ".15" sorts below ".2".
        assertTrue("0.7.2.15".compareTo("0.7.2.2") < 0);
        assertTrue("the version comparison must disagree with string order",
                ReleaseNotesRepository.compareVersions("0.7.2.15", "0.7.2.2") > 0);
    }

    @Test public void theStaleListOrderCannotPinTheNewestRelease() {
        // Exactly what the collection endpoint returns for this repository today.
        String[] asReturned = {"v0.7.2.9", "v0.7.2.8", "v0.7.2.7", "v0.7.2.6", "v0.7.2.5",
                "v0.7.2.4", "v0.7.2.3", "v0.7.2.2", "v0.7.2.15", "v0.7.2.14"};
        List<ReleaseNotesRepository.ReleaseNote> notes = new ArrayList<>();
        for (String tag : asReturned) notes.add(ReleaseNotesRepository.parseRelease(published(tag)));

        notes.sort((a, b) -> ReleaseNotesRepository.compareVersions(b.versionName, a.versionName));

        assertEquals("v0.7.2.15", notes.get(0).tag);
        assertEquals("v0.7.2.14", notes.get(1).tag);
        assertEquals("v0.7.2.9", notes.get(2).tag);
    }

    @Test public void aFutureReleaseBecomesNewestWithoutAnyCodeChange() {
        List<ReleaseNotesRepository.ReleaseNote> notes = new ArrayList<>();
        for (String tag : new String[]{"v0.7.2.15", "v0.7.2.16", "v0.7.2.14"}) {
            notes.add(ReleaseNotesRepository.parseRelease(published(tag)));
        }
        notes.sort((a, b) -> ReleaseNotesRepository.compareVersions(b.versionName, a.versionName));
        assertEquals("v0.7.2.16", notes.get(0).tag);
    }

    @Test public void draftsAreNotReleases() {
        assertNull(ReleaseNotesRepository.parseRelease(release("v0.7.2.15", true, false)));
    }

    @Test public void prereleasesAreNotReleases() {
        assertNull(ReleaseNotesRepository.parseRelease(release("v0.7.2.15", false, true)));
    }

    @Test public void malformedTagsAreRejected() {
        assertNull(ReleaseNotesRepository.parseRelease(published("v0.7.2")));
        assertNull(ReleaseNotesRepository.parseRelease(published("0.7.2.15")));
        assertNull(ReleaseNotesRepository.parseRelease(published("release-15")));
        assertNull(ReleaseNotesRepository.parseRelease(published("")));
        assertNull(ReleaseNotesRepository.parseRelease(null));
    }

    @Test public void titleDateAndNotesComeFromTheRelease() {
        ReleaseNotesRepository.ReleaseNote note =
                ReleaseNotesRepository.parseRelease(published("v0.7.2.15"));
        assertNotNull(note);
        assertEquals("0.7.2.15", note.versionName);
        assertEquals("Orbit Assistant v0.7.2.15", note.title);
        assertEquals("Notes for v0.7.2.15", note.body);
        assertEquals("2026-08-14T00:00:00Z", note.publishedAt);
    }

    @Test public void aMissingTitleFallsBackToTheTag() throws Exception {
        JSONObject object = published("v0.7.2.15").put("name", "");
        assertEquals("Orbit Assistant v0.7.2.15",
                ReleaseNotesRepository.parseRelease(object).title);
    }

    @Test public void oneUnavailableCandidateDoesNotDiscardTheOthers() {
        // A per-tag lookup that fails simply contributes nothing.
        List<ReleaseNotesRepository.ReleaseNote> notes = new ArrayList<>();
        for (JSONObject candidate : new JSONObject[]{
                published("v0.7.2.15"), release("v0.7.2.14", true, false), published("v0.7.2.13")}) {
            ReleaseNotesRepository.ReleaseNote note = ReleaseNotesRepository.parseRelease(candidate);
            if (note != null) notes.add(note);
        }
        notes.sort((a, b) -> ReleaseNotesRepository.compareVersions(b.versionName, a.versionName));

        assertEquals(2, notes.size());
        assertEquals("v0.7.2.15", notes.get(0).tag);
        assertEquals("v0.7.2.13", notes.get(1).tag);
    }

    @Test public void newerThanCurrentUsesTheSameNumericComparison() {
        // Whatever this build is, a clearly older version is never newer than it.
        assertTrue(!ReleaseNotesRepository.isNewerThanCurrent("0.0.0.1"));
        assertTrue(!ReleaseNotesRepository.isNewerThanCurrent(BuildConfig.VERSION_NAME));
        assertTrue(!ReleaseNotesRepository.isNewerThanCurrent("not-a-version"));
        assertTrue(ReleaseNotesRepository.isNewerThanCurrent("99.0.0.0"));
    }
}
