package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.view.ViewGroup;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowTextToSpeech;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Read Aloud: what is said, one reader at a time, and stopping that always works. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public class ReadAloudTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        ReadAloud.resetForTest();
        ShadowTextToSpeech.reset();
    }

    @After public void tearDown() {
        ReadAloud.resetForTest();
    }

    // ---- What is said ----

    @Test public void markdownIsSpokenAsWordsNotSymbols() {
        String spoken = ReadAloudText.clean("## Getting **started**\n\n- First `step`\n- [The docs](https://example.com/a/b?c=1)\n\n"
                + "1. Numbered\n\n---\n\n> Quoted ~~text~~");
        assertEquals("Getting started.\nFirst step\nThe docs\nNumbered\nQuoted text", spoken);
    }

    @Test public void codeAndBareLinksAreNamedRatherThanSpelledOut() {
        String spoken = ReadAloudText.clean("Run this:\n```kotlin\nval x = listOf<Int>()\n```\nThen see https://www.example.com/path/to?page=2 for more.");
        assertFalse(spoken, spoken.contains("listOf"));
        assertTrue(spoken, spoken.contains(ReadAloudText.CODE_PLACEHOLDER));
        assertTrue(spoken, spoken.contains("see link to example.com for more."));
        assertFalse(spoken.contains("http"));
    }

    @Test public void aTableReadsAsItsCells() {
        String spoken = ReadAloudText.clean("| Day | High |\n|---|---|\n| Mon | 80 |");
        assertEquals("Day, High\nMon, 80", spoken);
    }

    @Test public void aHeadingThatAlreadyEndsASentenceIsLeftAlone() {
        assertEquals("What next?\nThis.", ReadAloudText.clean("# What next?\nThis."));
    }

    @Test public void aLongAnswerIsCutIntoSafePiecesWithoutLosingWords() {
        StringBuilder answer = new StringBuilder();
        for (int i = 0; i < 400; i++) answer.append("Sentence number ").append(i).append(" is here. ");
        answer.append("\n\n");
        for (int i = 0; i < 300; i++) answer.append("unbroken").append(i).append(' ');
        List<String> pieces = ReadAloudText.chunks(answer.toString());
        assertTrue(pieces.size() > 10);
        StringBuilder joined = new StringBuilder();
        for (String piece : pieces) {
            assertTrue(piece.length() <= ReadAloudText.MAX_CHUNK);
            assertFalse(piece.isEmpty());
            joined.append(piece).append(' ');
        }
        assertEquals(answer.toString().replaceAll("\\s+", " ").trim(), joined.toString().trim());
        assertTrue("sentences stay whole", pieces.get(0).endsWith("."));
    }

    @Test public void aShortAnswerIsOnePieceAndNothingIsNothing() {
        assertEquals(Arrays.asList("Hello there."), ReadAloudText.chunks("Hello **there**."));
        assertTrue(ReadAloudText.chunks("").isEmpty());
        assertTrue(ReadAloudText.chunks(null).isEmpty());
        assertTrue(ReadAloudText.chunks("  \n ").isEmpty());
    }

    // ---- Reading ----

    @Test public void itReadsTheWholeAnswerThenGoesQuiet() {
        List<Integer> states = watch();
        ReadAloud.start(context, "First paragraph.\n\nSecond paragraph.", this);
        assertEquals(ReadAloud.STARTING, ReadAloud.state());
        assertTrue(ReadAloud.isReading("First paragraph.\n\nSecond paragraph."));
        engineAnswers(TextToSpeech.SUCCESS);
        assertEquals(Arrays.asList("First paragraph.", "Second paragraph."), engine().getSpokenTextList());
        assertTrue(states.contains(ReadAloud.READING));
        assertEquals("finished", ReadAloud.IDLE, ReadAloud.state());
        assertFalse(ReadAloud.isActive());
    }

    @Test public void aSecondAnswerReplacesTheFirstRatherThanJoiningIt() {
        ReadAloud.start(context, "One.", this);
        ReadAloud.start(context, "Two.", this);
        assertFalse(ReadAloud.isReading("One."));
        assertTrue(ReadAloud.isReading("Two."));
        engineAnswers(TextToSpeech.SUCCESS);
        assertEquals("only the chosen answer is ever queued", Arrays.asList("Two."), engine().getSpokenTextList());
        assertEquals(TextToSpeech.QUEUE_FLUSH, engine().getQueueMode());
    }

    @Test public void readAloudOnTheAnswerBeingReadStopsIt() {
        ReadAloud.start(context, "Stay.", this);
        ReadAloud.toggle(context, "Stay.", this);
        assertEquals(ReadAloud.IDLE, ReadAloud.state());
        engineAnswers(TextToSpeech.SUCCESS);
        assertTrue("a stop before the engine was ready still holds", engine().getSpokenTextList().isEmpty());
        ReadAloud.toggle(context, "Stay.", this);
        assertTrue(ReadAloud.isReading("Stay."));
    }

    @Test public void stopWorksFromEveryStateAndTellsTheEngine() {
        ReadAloud.stop();
        assertEquals(ReadAloud.IDLE, ReadAloud.state());
        final boolean[] stoppedWhileReading = {false};
        ReadAloud.addListener(() -> {
            if (ReadAloud.state() == ReadAloud.READING && !stoppedWhileReading[0]) {
                stoppedWhileReading[0] = true;
                ReadAloud.stop();
            }
        });
        ReadAloud.start(context, "A.\n\nB.\n\nC.", this);
        engineAnswers(TextToSpeech.SUCCESS);
        assertTrue(stoppedWhileReading[0]);
        assertTrue(engine().isStopped());
        assertEquals(ReadAloud.IDLE, ReadAloud.state());
        assertFalse(ReadAloud.isReading("A.\n\nB.\n\nC."));
    }

    @Test public void pauseHoldsItsPlaceAndResumeCarriesOnFromThere() {
        final boolean[] paused = {false};
        ReadAloud.addListener(() -> {
            if (ReadAloud.state() == ReadAloud.READING && !paused[0]) {
                paused[0] = true;
                ReadAloud.pause();
            }
        });
        ReadAloud.start(context, "A.\n\nB.\n\nC.", this);
        engineAnswers(TextToSpeech.SUCCESS);
        assertEquals("late callbacks from before the pause change nothing",
                ReadAloud.PAUSED, ReadAloud.state());
        assertTrue(ReadAloud.isReading("A.\n\nB.\n\nC."));
        int before = engine().getSpokenTextList().size();
        ReadAloud.resume();
        ShadowLooper.idleMainLooper();
        List<String> said = engine().getSpokenTextList();
        assertEquals("the paused piece again, then the rest", Arrays.asList("A.", "B.", "C."),
                said.subList(before, said.size()));
        assertEquals(ReadAloud.IDLE, ReadAloud.state());
    }

    @Test public void aMissingEngineSaysSoAndLeavesNothingRunning() {
        ReadAloud.start(context, "Hello.", this);
        engineAnswers(TextToSpeech.ERROR);
        assertEquals(ReadAloud.IDLE, ReadAloud.state());
        assertEquals(ReadAloud.NO_ENGINE_MESSAGE, ShadowToast.getTextOfLatestToast());
        assertNull("the dead engine is released", ReadAloud.engineForTest());
    }

    @Test public void closingOneSurfaceNeverSilencesAnother() {
        Object chat = new Object(), overlay = new Object();
        ReadAloud.start(context, "Hello.", chat);
        ReadAloud.stopOwnedBy(overlay);
        assertTrue(ReadAloud.isActive());
        ReadAloud.stopOwnedBy(chat);
        assertFalse(ReadAloud.isActive());
    }

    @Test public void theChosenSpeedReachesNothingButReadAloudAndDefaultsToNormal() {
        assertEquals(1f, Prefs.readAloudRate(context), 0f);
        Prefs.get(context).edit().putString(Prefs.READ_ALOUD_SPEED, "faster").commit();
        assertEquals(1.5f, Prefs.readAloudRate(context), 0f);
        Prefs.get(context).edit().putString(Prefs.READ_ALOUD_SPEED, "nonsense").commit();
        assertEquals(1f, Prefs.readAloudRate(context), 0f);
        assertEquals(Prefs.READ_ALOUD_SPEEDS.length, Prefs.READ_ALOUD_SPEED_LABELS.length);
    }

    // ---- Preferences ----

    @Test public void nothingIsEverReadAutomaticallyUnlessTurnedOn() throws Exception {
        assertFalse(Prefs.readAloudAuto(context));
        Prefs.get(context).edit().putBoolean(Prefs.READ_ALOUD_AUTO, true)
                .putString(Prefs.READ_ALOUD_SPEED, "fast")
                .putString(Prefs.READ_ALOUD_VOICE, "en-us-x-abc-local").commit();
        JSONObject backup = Prefs.backupSnapshot(context);
        assertFalse("a voice belongs to one phone's engine", backup.has(Prefs.READ_ALOUD_VOICE));
        Prefs.get(context).edit().clear().commit();
        assertTrue(Prefs.restoreBackupSnapshot(context, backup));
        assertTrue(Prefs.readAloudAuto(context));
        assertEquals(2, Prefs.readAloudSpeedIndex(context));
        assertEquals("", Prefs.readAloudVoice(context));
    }

    @Test public void settingsSearchFindsReadAloud() {
        assertEquals("Read Aloud", SettingsSearchIndex.search("read aloud").get(0).title);
    }

    // ---- The action and the pill ----

    @Test public void everyBoundAnswerOffersReadAloudAndTheOneBeingReadOffersStop() {
        Activity host = Robolectric.buildActivity(Activity.class).setup().get();
        View bubble = new View(host);
        MessageActions.AssistantActions actions = new MessageActions.AssistantActions();
        MessageActions.bindAssistant(bubble, "An answer.", actions);
        assertNotNull(actions.readAloud);
        List<String> labels = Arrays.asList(MessageActions.labelsOf(MessageActions.assistantMenu(actions, true)));
        assertTrue(labels.contains(MessageActions.READ_ALOUD_MENU_LABEL));
        assertEquals("Copy stays first", MessageActions.COPY_MENU_LABEL, labels.get(0));

        actions.readAloud.run();
        assertTrue(ReadAloud.isReading("An answer."));
        labels = Arrays.asList(MessageActions.labelsOf(MessageActions.moreMenu(actions)));
        assertTrue(labels.contains(MessageActions.STOP_READING_MENU_LABEL));
        assertFalse(labels.contains(MessageActions.READ_ALOUD_MENU_LABEL));

        actions.readAloud.run();
        assertFalse(ReadAloud.isActive());
    }

    @Test public void thePillExistsOnlyWhileReadingAndItsStopStops() {
        Activity host = Robolectric.buildActivity(Activity.class).setup().get();
        ReadAloudBar bar = new ReadAloudBar(host);
        host.setContentView(bar);
        ShadowLooper.idleMainLooper();
        assertEquals(View.GONE, bar.getVisibility());

        ReadAloud.start(host, "Hello.", host);
        assertEquals(View.VISIBLE, bar.getVisibility());
        View stop = find(bar, ReadAloudBar.STOP_DESCRIPTION);
        assertNotNull(stop);
        assertNotNull(find(bar, "Pause reading aloud"));
        ReadAloud.pause();
        assertNotNull(find(bar, "Resume reading aloud"));
        stop.performClick();
        assertFalse(ReadAloud.isActive());
        assertEquals(View.GONE, bar.getVisibility());
    }

    // ---- Helpers ----

    private static List<Integer> watch() {
        List<Integer> states = new ArrayList<>();
        ReadAloud.addListener(() -> states.add(ReadAloud.state()));
        return states;
    }

    /** Robolectric's engine never answers by itself; this is Android reporting it is (not) ready. */
    private static void engineAnswers(int status) {
        engine().getOnInitListener().onInit(status);
        ShadowLooper.idleMainLooper();
    }

    private static ShadowTextToSpeech engine() {
        TextToSpeech tts = ReadAloud.engineForTest();
        return Shadows.shadowOf(tts != null ? tts : ShadowTextToSpeech.getLastTextToSpeechInstance());
    }

    private static View find(View view, String description) {
        if (view.getContentDescription() != null
                && description.contentEquals(view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }
}
