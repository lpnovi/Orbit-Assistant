package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.media.AudioManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The same question, asked every way, reaching the same answer.
 *
 * <h2>The device failure this file exists for</h2>
 *
 * <p>"What is my media volume right now" was sent to the provider, which replied that it could not
 * read the media volume. A moment later "what's my media volume right now" was answered locally with
 * the real figure. Same question, same phone, seconds apart, two different behaviours — which is far
 * worse than a feature that never worked, because the user cannot tell what Orbit will do.
 *
 * <p>Two things had compounded. {@code stripPoliteness} removed the trailing "right now", taking the
 * cue that marked the question as being about a current value; and the shared conceptual-question
 * rule matched the expanded "what is …" while not matching the contracted "what's …", so the two
 * forms classified differently once that cue was gone.
 *
 * <p>Everything below is therefore written as <em>pairs</em>. A single-phrasing test would have
 * passed happily through the original bug.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class DeviceStatusPhrasingTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
    }

    private static void expect(DeviceStatusRouter.Topic topic, String... phrasings) {
        for (String phrase : phrasings) {
            assertEquals("\"" + phrase + "\" must be answered from the phone",
                    topic, DeviceStatusRouter.topic(phrase));
        }
    }

    private static void expectProvider(String... phrasings) {
        for (String phrase : phrasings) {
            assertNull("\"" + phrase + "\" is a conversation, not a reading",
                    DeviceStatusRouter.topic(phrase));
        }
    }

    /** Contracted and expanded openers are the same question and must classify identically. */
    private static void pair(DeviceStatusRouter.Topic topic, String contracted, String expanded) {
        assertEquals("\"" + contracted + "\" must be answered from the phone",
                topic, DeviceStatusRouter.topic(contracted));
        assertEquals("\"" + expanded + "\" must behave identically to its contraction",
                topic, DeviceStatusRouter.topic(expanded));
    }

    // ---- the reported failure, exactly ----------------------------------------------------------

    /** The four phrasings from the device report, which must now be indistinguishable. */
    @Test public void theReportedMediaVolumePhrasingsAllResolveLocally() {
        expect(DeviceStatusRouter.Topic.MEDIA_VOLUME,
                "what's my media volume?",
                "what is my media volume?",
                "what's my media volume right now?",
                "what is my media volume right now?");
    }

    @Test public void everyMediaVolumePhrasingInTheBriefResolvesLocally() {
        expect(DeviceStatusRouter.Topic.MEDIA_VOLUME,
                "what is my volume at?",
                "what's my volume at?",
                "what is my phone volume?",
                "what's my phone volume?",
                "how loud is my phone right now?",
                "tell me my media volume",
                "check my media volume",
                "show me my current volume");
    }

    /**
     * The regression that names the cause.
     *
     * <p>Politeness stripping is correct and must keep working; what must never happen again is a
     * trailing courtesy changing what kind of question Orbit thinks it was asked. Each phrase is
     * asserted with and without the trailing words, and the two must agree.
     */
    @Test public void trailingPolitenessCannotChangeWhatAQuestionIs() {
        String[] bases = {
                "what is my media volume",
                "what's my media volume",
                "what is my brightness",
                "what's my battery at",
        };
        String[] tails = {"", " right now", " real quick", " please", " right now please"};
        for (String base : bases) {
            DeviceStatusRouter.Topic bare = DeviceStatusRouter.topic(base);
            assertNotNull("\"" + base + "\" must be a reading to begin with", bare);
            for (String tail : tails) {
                assertEquals("\"" + base + tail + "\" must be the same question as \"" + base + "\"",
                        bare, DeviceStatusRouter.topic(base + tail));
            }
        }
    }

    /** And the shared normalizer still strips those words, so the fix is genuinely local. */
    @Test public void theSharedPolitenessRuleIsUnchanged() {
        assertEquals("turn on the flashlight", LanguageNormalizer.stripPoliteness(
                LanguageNormalizer.canonical("please turn on the flashlight right now")));
        assertEquals("set a timer for 10 minutes", LanguageNormalizer.stripPoliteness(
                LanguageNormalizer.canonical("Set a timer for 10 minutes, real quick")));
    }

    /** Contractions are expanded before anything is classified, and only the openers. */
    @Test public void onlyQuestionOpenersAreExpanded() {
        assertEquals("what is my volume", DeviceStatusRouter.expandContractions("what's my volume"));
        assertEquals("what is my volume", DeviceStatusRouter.expandContractions("whats my volume"));
        assertEquals("how is my battery", DeviceStatusRouter.expandContractions("how's my battery"));
        assertEquals("an ordinary contraction is left alone",
                "it's fine", DeviceStatusRouter.expandContractions("it's fine"));
        assertEquals("", DeviceStatusRouter.expandContractions(null));
    }

    // ---- every state, in both forms ---------------------------------------------------------------

    @Test public void batteryPairs() {
        pair(DeviceStatusRouter.Topic.BATTERY, "what's my battery at", "what is my battery at");
        pair(DeviceStatusRouter.Topic.BATTERY, "what's my battery", "what is my battery");
        pair(DeviceStatusRouter.Topic.BATTERY,
                "what's my battery right now", "what is my battery right now");
        expect(DeviceStatusRouter.Topic.BATTERY,
                "how much battery do I have", "tell me my battery level", "check my battery");
    }

    @Test public void chargingPairs() {
        expect(DeviceStatusRouter.Topic.BATTERY,
                "am I charging", "is my phone charging", "am I charging right now");
    }

    @Test public void brightnessPairs() {
        pair(DeviceStatusRouter.Topic.BRIGHTNESS, "what's my brightness", "what is my brightness");
        pair(DeviceStatusRouter.Topic.BRIGHTNESS,
                "what's my brightness right now", "what is my brightness right now");
        expect(DeviceStatusRouter.Topic.BRIGHTNESS,
                "what brightness am I at", "tell me my brightness", "check my brightness");
    }

    @Test public void ringerPairs() {
        expect(DeviceStatusRouter.Topic.RINGER,
                "is my phone on silent", "is my phone on vibrate",
                "what's my ringer mode", "what is my ringer mode");
    }

    @Test public void doNotDisturbPairs() {
        expect(DeviceStatusRouter.Topic.DO_NOT_DISTURB,
                "is do not disturb on", "is dnd on",
                "what's my do not disturb", "what is my do not disturb",
                "is do not disturb on right now");
    }

    // ---- what must still reach the provider --------------------------------------------------------

    /** The false positives named in the brief. Every one of these is a conversation. */
    @Test public void discussionAboutAStateIsNeverARead() {
        expectProvider(
                "why is my phone volume weird?",
                "why is my volume changing?",
                "what is a good volume for headphones?",
                "what is a good media volume?",
                "should my volume be this high?",
                "should my brightness be lower at night?",
                "explain Android volume controls",
                "why does volume change between apps?",
                "why is my battery draining quickly?",
                "explain Do Not Disturb",
                "what does ringer mode mean?");
    }

    /** A how-to is a how-to in both forms, which was the point of replacing the old guard. */
    @Test public void howToQuestionsGoToTheProviderInEitherForm() {
        expectProvider(
                "how do I check my battery",
                "how do I change my volume",
                "how can I see my brightness",
                "how to turn off do not disturb");
    }

    /** An instruction is still an instruction, however politely it is phrased. */
    @Test public void instructionsAreStillInstructions() {
        expectProvider(
                "turn on do not disturb",
                "set my volume to 40",
                "put my phone on silent right now",
                "can you turn the brightness down please");

        AssistantReply command = LocalCommandRouter.tryHandle(context, "turn on do not disturb");
        assertNotNull("and the command router still owns it", command);
        assertEquals("SET_DND", command.actions.get(0).type);
    }

    // ---- the answers themselves ---------------------------------------------------------------------

    /**
     * Every phrasing produces the same reading from the same Android value, with no provider.
     *
     * <p>The reading is compared across phrasings rather than to a fixed string, so this asserts the
     * property that failed on the device — that how you ask cannot change what you are told.
     */
    @Test public void everyPhrasingProducesTheSameAnswerAndChangesNothing() {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, max / 2, 0);
        int before = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        int expected = DeviceStatusReader.mediaVolumePercent(context);

        String answer = null;
        for (String phrase : new String[]{
                "what's my media volume", "what is my media volume",
                "what's my media volume right now", "what is my media volume right now",
                "what is my volume at", "tell me my media volume"}) {
            AssistantReply reply = DeviceStatusRouter.tryHandle(context, phrase);
            assertNotNull("\"" + phrase + "\" must be answered locally", reply);
            assertTrue("a reading performs no action", reply.actions.isEmpty());
            assertTrue("and reports the real Android value",
                    reply.text.contains(String.valueOf(expected)));
            if (answer == null) answer = reply.text;
            else assertEquals("every phrasing must give the same answer", answer, reply.text);
        }
        assertEquals("and reading a value must never change it",
                before, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
    }

    // ---- the voice ------------------------------------------------------------------------------------

    /**
     * The wording is Orbit's, not a debug dump, and not a model's.
     *
     * <p>Semantic rather than punctuation-exact: what matters is that a reading addresses the user,
     * carries the real number, and never reads like a status line.
     */
    @Test public void readingsAreWrittenAsAnAssistantWouldSayThem() {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
        String volume = DeviceStatusReader.mediaVolume(context).text;
        assertTrue("a reading speaks to the user", volume.toLowerCase().startsWith("your "));
        assertTrue("and carries the real value", volume.contains("100%"));
        assertFalse("and never reads as a status line", volume.startsWith("Media volume is"));

        audio.setRingerMode(AudioManager.RINGER_MODE_SILENT);
        String ringer = DeviceStatusReader.ringer(context).text;
        assertEquals("Your phone is on silent.", ringer);
        audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        assertEquals("Your ringer is on.", DeviceStatusReader.ringer(context).text);
    }

    /** A failure is Orbit saying it could not, not a report about a component called Orbit. */
    @Test public void unavailableReadingsAreFirstPersonAndNeverFakeAPermission() {
        DeviceStatusReader.Reading none = DeviceStatusReader.battery(null);
        assertFalse(none.available);
        assertTrue(none.text.startsWith("I couldn't"));
        assertFalse("an unreadable value is not a permission problem", none.needsAccess);
        assertFalse(none.text.contains("Orbit could not"));
    }
}
