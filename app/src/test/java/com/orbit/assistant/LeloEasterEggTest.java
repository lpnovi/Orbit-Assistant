package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Lelo-mode header: whose app it says it is, and the one line underneath it.
 *
 * <p>Lelo mode is a hidden preference that has existed since v0.4.6 and until now only changed how
 * the model writes. This adds the only two visible things it does: the companion app's title reads
 * {@code Lelo's Cutie}, and a small muted line sits under it. Both hang off {@link Prefs#LELO_MODE}
 * and nothing else, so there is exactly one thing to turn on and exactly one thing to turn off.
 *
 * <p>The half of this that has to be proved is the half nobody looks at. With the mode off, Chats
 * must be the screen it has always been — not the same screen with an invisible view in it, not the
 * same screen with a gap where a line would go, and not the same screen with a message a screen
 * reader can still reach. So the note is <i>added</i> in Lelo mode rather than built and hidden, and
 * these check the header's actual children rather than any pixel it lands on.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class LeloEasterEggTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        ConversationStore.clear(context);
        OrbitRequestManager.resetForTest();
        OrbitRequestManager.setWorkCanceller(name -> {});
        TestWorkManager.ensureInitialized(context);
    }

    private void leloMode(boolean enabled) {
        Prefs.get(context).edit().putBoolean(Prefs.LELO_MODE, enabled).commit();
    }

    private ActivityController<MainActivity> chats() {
        return Robolectric.buildActivity(MainActivity.class).setup();
    }

    /**
     * The header's title column, found by the subtitle that has always been in it.
     *
     * <p>Deliberately not by index, size or position: the point of these tests is the column's
     * contents, and anchoring on "Chats" keeps them from passing or failing on layout.
     */
    private static ViewGroup titleColumn(MainActivity activity) {
        for (TextView t : textViews(activity)) {
            if ("Chats".contentEquals(t.getText())) {
                assertNotNull("the Chats subtitle must live in the header column", t.getParent());
                return (ViewGroup) t.getParent();
            }
        }
        throw new AssertionError("the Chats header subtitle was not found");
    }

    private static List<TextView> textViews(MainActivity activity) {
        List<TextView> found = new ArrayList<>();
        collect(activity.getWindow().getDecorView(), found);
        return found;
    }

    private static void collect(View view, List<TextView> into) {
        if (view instanceof TextView) into.add((TextView) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), into);
        }
    }

    private static String titleOf(MainActivity activity) {
        return titleColumn(activity).getChildAt(0) instanceof TextView
                ? ((TextView) titleColumn(activity).getChildAt(0)).getText().toString()
                : "";
    }

    /** Every view anywhere on screen carrying the note, hidden ones included. */
    private static List<TextView> notes(MainActivity activity) {
        List<TextView> found = new ArrayList<>();
        for (TextView t : textViews(activity)) {
            String value = t.getText() == null ? "" : t.getText().toString();
            if (value.contains("wanted and needed")) found.add(t);
        }
        return found;
    }

    // ---- Lelo mode off: Orbit is Orbit -----------------------------------------------------------

    @Test public void withLeloModeOffTheTitleIsTheOrbitTitle() {
        ActivityController<MainActivity> controller = chats();
        assertEquals("Orbit", titleOf(controller.get()));
        assertEquals("Orbit", UiKit.appTitle(context));
        controller.pause().stop().destroy();
    }

    @Test public void withLeloModeOffTheLeloTitleIsNowhereOnScreen() {
        ActivityController<MainActivity> controller = chats();
        for (TextView t : textViews(controller.get())) {
            String value = t.getText() == null ? "" : t.getText().toString();
            assertFalse("Lelo's Cutie must not appear with the mode off",
                    value.contains(UiKit.LELO_TITLE));
        }
        controller.pause().stop().destroy();
    }

    /**
     * Not hidden, not empty, not there. A hidden view would still be a view somebody could find.
     */
    @Test public void withLeloModeOffTheNoteDoesNotExistAtAll() {
        ActivityController<MainActivity> controller = chats();
        MainActivity activity = controller.get();

        assertTrue("no view may carry the note", notes(activity).isEmpty());
        assertEquals("and the header column keeps its title and subtitle only",
                2, titleColumn(activity).getChildCount());
        controller.pause().stop().destroy();
    }

    /** No reserved gap either: the column's height is its two lines and nothing more. */
    @Test public void withLeloModeOffNoBlankSpaceIsHeldOpenForIt() {
        ActivityController<MainActivity> controller = chats();
        ViewGroup column = titleColumn(controller.get());

        int measured = 0;
        for (int i = 0; i < column.getChildCount(); i++) {
            View child = column.getChildAt(i);
            assertEquals("no child of the header column may be an invisible placeholder",
                    View.VISIBLE, child.getVisibility());
            measured++;
        }
        assertEquals(2, measured);
        controller.pause().stop().destroy();
    }

    // ---- Lelo mode on: the app is hers -----------------------------------------------------------

    @Test public void withLeloModeOnTheTitleIsExactlyLelosCutie() {
        leloMode(true);
        ActivityController<MainActivity> controller = chats();

        assertEquals("Lelo's Cutie", titleOf(controller.get()));
        assertEquals("Lelo's Cutie", UiKit.LELO_TITLE);
        assertEquals("Lelo's Cutie", UiKit.appTitle(context));
        controller.pause().stop().destroy();
    }

    @Test public void withLeloModeOnThereIsExactlyOneNote() {
        leloMode(true);
        ActivityController<MainActivity> controller = chats();

        assertEquals("the note must appear once and only once",
                1, notes(controller.get()).size());
        controller.pause().stop().destroy();
    }

    @Test public void theNoteSaysTheThingItIsForAndSaysItOnce() {
        leloMode(true);
        ActivityController<MainActivity> controller = chats();
        String note = notes(controller.get()).get(0).getText().toString();

        String lower = note.toLowerCase(Locale.US);
        assertTrue(lower.contains("wanted and needed"));
        assertTrue(lower.contains("never forget"));
        assertEquals("and it is the one string Orbit keeps for it", UiKit.LELO_NOTE, note);
        controller.pause().stop().destroy();
    }

    /** It sits with the title rather than somewhere else on the page. */
    @Test public void theNoteBelongsToTheTitleColumn() {
        leloMode(true);
        ActivityController<MainActivity> controller = chats();
        MainActivity activity = controller.get();

        ViewGroup column = titleColumn(activity);
        assertEquals("title, subtitle, note", 3, column.getChildCount());
        assertEquals("the note is the last line of the header",
                column.getChildAt(2), notes(activity).get(0));
        controller.pause().stop().destroy();
    }

    /**
     * It is a message, not a control and not an announcement.
     *
     * <p>A person using TalkBack should meet it the way they meet the subtitle above it: read once,
     * in order, as ordinary text. Anything clickable would invite a tap that does nothing, and a
     * live region would say it again every time the header was rebuilt.
     */
    @Test public void theNoteIsStaticText() {
        leloMode(true);
        ActivityController<MainActivity> controller = chats();
        TextView note = notes(controller.get()).get(0);

        assertFalse("the note is not a button", note instanceof Button);
        assertFalse(note.isClickable());
        assertFalse(note.hasOnClickListeners());
        assertFalse(note.isLongClickable());
        assertEquals("its own text is what a screen reader should read",
                null, note.getContentDescription());
        assertEquals("and it must not re-announce itself",
                View.ACCESSIBILITY_LIVE_REGION_NONE, note.getAccessibilityLiveRegion());
        controller.pause().stop().destroy();
    }

    /** The presentation is the existing hidden preference, not a second one beside it. */
    @Test public void thePresentationRidesOnTheExistingLeloPreference() {
        assertEquals("lelo_mode", Prefs.LELO_MODE);
        leloMode(true);
        assertTrue(Prefs.leloMode(context));

        ActivityController<MainActivity> controller = chats();
        assertTrue("showing it must not have turned the mode off", Prefs.leloMode(context));
        assertEquals("Lelo's Cutie", titleOf(controller.get()));
        controller.pause().stop().destroy();

        String prefs = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/Prefs.java");
        assertEquals("there must be exactly one Lelo preference key",
                1, occurrences(prefs, "LELO_MODE = "));
    }

    // ---- turning it on and off -------------------------------------------------------------------

    /**
     * Settings is a different Activity, so Chats picks the change up the way it already picks up an
     * accent change: on resume, by rebuilding its own content in place.
     */
    @Test public void enablingItFromSettingsChangesTheHeaderOnReturn() {
        ActivityController<MainActivity> controller = chats();
        assertEquals("Orbit", titleOf(controller.get()));

        controller.pause();
        leloMode(true);
        controller.resume();

        assertEquals("Lelo's Cutie", titleOf(controller.get()));
        assertEquals(1, notes(controller.get()).size());
        controller.pause().stop().destroy();
    }

    @Test public void disablingItPutsOrbitBackExactlyAsItWas() {
        leloMode(true);
        ActivityController<MainActivity> controller = chats();
        assertEquals("Lelo's Cutie", titleOf(controller.get()));

        controller.pause();
        leloMode(false);
        controller.resume();

        MainActivity activity = controller.get();
        assertEquals("Orbit", titleOf(activity));
        assertTrue("the note must be gone, not hidden", notes(activity).isEmpty());
        assertEquals(2, titleColumn(activity).getChildCount());
        controller.pause().stop().destroy();
    }

    /** The preference is what persists, so a fresh process reads the right header. */
    @Test public void aRecreatedActivityReadsThePersistedState() {
        leloMode(true);
        ActivityController<MainActivity> first = chats();
        assertEquals("Lelo's Cutie", titleOf(first.get()));
        first.pause().stop().destroy();

        ActivityController<MainActivity> second = chats();
        assertEquals("Lelo's Cutie", titleOf(second.get()));
        assertEquals(1, notes(second.get()).size());
        second.pause().stop().destroy();
    }

    /** Rebuilding the header repeatedly must not stack copies of the note in it. */
    @Test public void repeatedRebuildsNeverAccumulateNotes() {
        leloMode(true);
        ActivityController<MainActivity> controller = chats();

        for (String accent : new String[]{"mint", "rose", "blurple", "mint"}) {
            controller.pause();
            Prefs.get(context).edit().putString(Prefs.ACCENT, accent).commit();
            controller.resume();

            assertEquals("still exactly one note after rebuilding for " + accent,
                    1, notes(controller.get()).size());
            assertEquals(3, titleColumn(controller.get()).getChildCount());
            assertEquals("Lelo's Cutie", titleOf(controller.get()));
        }
        controller.pause().stop().destroy();
    }

    // ---- what it is not ---------------------------------------------------------------------------

    /**
     * The message is Orbit's, not the model's.
     *
     * <p>Lelo mode's conversational instructions were settled long before this and are deliberately
     * untouched: the point of the easter egg is a line she can find in her own app, not an
     * assistant that keeps telling her something.
     */
    @Test public void theModelWasNotToldToSayIt() {
        String client = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/ChatGptClient.java");
        int lelo = client.indexOf("LELO_SYSTEM =");
        assertTrue("the Lelo system instruction must still be there", lelo > 0);

        String instruction = client.substring(lelo, client.indexOf(';', lelo)).toLowerCase(Locale.US);
        assertFalse(instruction.contains("wanted"));
        assertFalse(instruction.contains("needed"));
        assertFalse(instruction.contains("never forget"));
        assertTrue("and it must still be the casual style it always was",
                instruction.contains("casual, playful, friend-like"));
    }

    /** It is a title and a line of text. It does not rename Orbit. */
    @Test public void orbitsIdentityIsUnchanged() {
        assertEquals("com.orbit.assistant", BuildConfig.APPLICATION_ID);
        String strings = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/res/values/strings.xml");
        assertTrue("the app name is still Orbit's",
                strings.contains("<string name=\"app_name\">Orbit Assistant</string>"));
        assertFalse("and nothing in resources knows about Lelo",
                strings.contains("Lelo"));
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) count++;
        return count;
    }
}
