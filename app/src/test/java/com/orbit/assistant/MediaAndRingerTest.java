package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioManager;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNotificationManager;

/**
 * Playback and the ringer: the phrases, the actions, and the wording that must stay honest.
 *
 * <p>Both are new shared actions rather than anything Orbit Local owns, so both are reachable from
 * a spoken phrase, from a cloud tool request, and from the on-device action model, and all three
 * arrive at the same executor. What is asserted here is that the recognition is narrow, that the
 * ringer refuses rather than pretends when Android will not allow the change, and that the media
 * volume Orbit has always controlled is a completely separate thing from the ringer profile.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class MediaAndRingerTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        RecentActionContext.clear();
    }

    private AssistantReply.Action action(String phrase) {
        AssistantReply reply = LocalCommandRouter.tryHandle(context, phrase);
        return reply == null || reply.actions.isEmpty() ? null : reply.actions.get(0);
    }

    private String command(String phrase) {
        AssistantReply.Action a = action(phrase);
        return a == null ? null : a.params.optString("command", "");
    }

    private String ringerMode(String phrase) {
        AssistantReply.Action a = action(phrase);
        return a == null ? null : a.params.optString("mode", "");
    }

    // ---- media phrases -------------------------------------------------------------------------

    @Test public void everydayPlaybackPhrasesAreRecognised() {
        assertEquals("PAUSE", command("pause the music"));
        assertEquals("PAUSE", command("pause"));
        assertEquals("PLAY", command("resume"));
        assertEquals("PLAY", command("play"));
        assertEquals("PLAY", command("play the music"));
        assertEquals("NEXT", command("next song"));
        assertEquals("NEXT", command("skip this"));
        assertEquals("NEXT", command("skip"));
        assertEquals("PREVIOUS", command("previous track"));
        assertEquals("PREVIOUS", command("go back a song"));
    }

    @Test public void everyMediaPhraseProducesTheSharedAction() {
        for (String phrase : new String[]{"pause the music", "resume", "next song", "previous track"}) {
            AssistantReply.Action a = action(phrase);
            assertNotNull(phrase, a);
            assertEquals("MEDIA_CONTROL", a.type);
            assertNotNull(MediaControl.parse(a.params.optString("command", "")));
        }
    }

    /** A bare "next" is an ordinary word in a conversation and must not move somebody's music. */
    @Test public void ambiguousWordsAreNotPlaybackCommands() {
        assertNull(action("next"));
        assertNull(action("back"));
        assertNull(action("what should I play next"));
        assertNull(action("play devil's advocate for a second"));
        assertNull(action("pause for thought"));
    }

    @Test public void theCommandVocabularyIsClosed() {
        assertEquals(MediaControl.Command.PLAY, MediaControl.parse("play"));
        assertEquals(MediaControl.Command.NEXT, MediaControl.parse("NEXT_TRACK"));
        assertEquals(MediaControl.Command.PREVIOUS, MediaControl.parse("previous_track"));
        assertNull(MediaControl.parse("seek"));
        assertNull(MediaControl.parse("open spotify"));
        assertNull(MediaControl.parse(""));
        assertNull(MediaControl.parse(null));
    }

    /** Nothing here names a music app. Orbit controls whatever Android says is playing. */
    @Test public void nothingIsTiedToAParticularApp() throws Exception {
        String source = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/MediaControl.java");
        for (String app : new String[]{"com.spotify", "com.google.android.apps.youtube",
                "com.samsung.android.app.music"}) {
            assertFalse("media control must not target " + app, source.contains(app));
        }
    }

    /** With no active session and no notification access, Orbit says what it actually did. */
    @Test public void anUnobservableMediaCommandIsNotReportedAsDone() {
        DeviceActionExecutor.Result result = MediaControl.execute(context, MediaControl.Command.PAUSE);
        assertNotNull(result);
        assertFalse("a dispatched key is not a confirmed pause", result.message.startsWith("Paused"));
        assertTrue(result.message.contains("Sent pause")
                || result.message.contains("Nothing is playing"));
    }

    // ---- ringer phrases ------------------------------------------------------------------------

    @Test public void ringerPhrasesAreRecognised() {
        assertEquals("vibrate", ringerMode("put my phone on vibrate"));
        assertEquals("vibrate", ringerMode("vibrate mode"));
        assertEquals("silent", ringerMode("silence my phone"));
        assertEquals("silent", ringerMode("put my phone on silent"));
        assertEquals("normal", ringerMode("turn the ringer back on"));
        assertEquals("normal", ringerMode("take my phone off silent"));
    }

    @Test public void everyRingerPhraseProducesTheSharedAction() {
        AssistantReply.Action a = action("put my phone on vibrate");
        assertNotNull(a);
        assertEquals("SET_RINGER_MODE", a.type);
    }

    /**
     * The restraint that a real test caught.
     *
     * <p>"Silence everything" is something people say to an assistant and is not an instruction to
     * change the phone's sound profile, so a quiet-sounding word only counts when the ringer or the
     * phone is actually named.
     */
    @Test public void vagueQuietRequestsAreStillNotRingerCommands() {
        assertNull(action("be quiet"));
        assertNull(action("silence everything"));
        assertNull(action("leave me alone"));
        assertNull(action("silence the notifications"));
    }

    /** Media volume and the ringer profile are different things and must stay different. */
    @Test public void mediaVolumeIsNotTheRinger() {
        AssistantReply.Action volume = action("set volume to 30%");
        assertNotNull(volume);
        assertEquals("SET_VOLUME", volume.type);
        assertEquals(30, volume.params.optInt("percent"));

        AssistantReply.Action muted = action("mute the volume");
        assertTrue("muting the volume is a level, not a ringer profile",
                muted == null || !"SET_RINGER_MODE".equals(muted.type));
    }

    /** And the relative media-volume grammar is untouched by any of this. */
    @Test public void relativeVolumeCommandsAreUnaffected() {
        AssistantReply.Action quieter = action("turn the volume down a bit");
        assertNotNull(quieter);
        assertEquals("SET_VOLUME", quieter.type);
        assertTrue("a relative request must still move by a delta", quieter.params.has("delta"));
    }

    // ---- the ringer executor -------------------------------------------------------------------

    private DeviceActionExecutor.Result setRinger(String mode) throws Exception {
        return DeviceActionExecutor.executeDetailed(context, new AssistantReply.Action(
                "SET_RINGER_MODE", new JSONObject().put("mode", mode), false));
    }

    @Test public void theRingerIsConfirmedByReadingItBack() throws Exception {
        ShadowNotificationManager shadow = Shadows.shadowOf(
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE));
        shadow.setNotificationPolicyAccessGranted(true);

        assertEquals("Ringer set to Vibrate", setRinger("vibrate").message);
        assertEquals("Vibrate", DeviceStatusReader.ringerModeName(context));

        assertEquals("Ringer set to Silent", setRinger("silent").message);
        assertEquals("Silent", DeviceStatusReader.ringerModeName(context));

        assertEquals("Ringer set to Normal", setRinger("normal").message);
        assertEquals("Normal", DeviceStatusReader.ringerModeName(context));
    }

    /** Android's policy decides. Orbit reports the refusal rather than working around it. */
    @Test public void aQuietModeWithoutDndAccessIsAPermissionResult() throws Exception {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        ShadowNotificationManager shadow = Shadows.shadowOf(
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE));
        shadow.setNotificationPolicyAccessGranted(false);

        DeviceActionExecutor.Result silent = setRinger("silent");
        assertEquals(DeviceActionExecutor.STATUS_PERMISSION, silent.status);
        assertFalse(silent.success);
        assertTrue(silent.message.contains("Do Not Disturb access"));
        assertEquals("and the ringer is exactly where it was",
                AudioManager.RINGER_MODE_NORMAL, audio.getRingerMode());
    }

    @Test public void anUnknownRingerModeIsRefused() throws Exception {
        DeviceActionExecutor.Result result = setRinger("loudest");
        assertFalse(result.success);
        assertTrue(result.message.contains("not a ringer mode"));
    }

    @Test public void anUnknownMediaCommandIsRefused() throws Exception {
        DeviceActionExecutor.Result result = DeviceActionExecutor.executeDetailed(context,
                new AssistantReply.Action("MEDIA_CONTROL",
                        new JSONObject().put("command", "SEEK"), false));
        assertFalse(result.success);
        assertTrue(result.message.contains("not a media command"));
    }

    // ---- the shared catalog --------------------------------------------------------------------

    /** Both actions are offered to the cloud providers as well, from one catalog. */
    @Test public void bothActionsAreInTheCloudToolCatalog() {
        String chatgpt = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/ChatGptClient.java");
        String relay = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/RelayProvider.java");
        for (String type : new String[]{"MEDIA_CONTROL", "SET_RINGER_MODE"}) {
            assertTrue(type + " must be offered to ChatGPT", chatgpt.contains("\"" + type + "\""));
            assertTrue(type + " must be offered to the relay", relay.contains("\"" + type + "\""));
        }
        assertTrue("and described in the system prompt", chatgpt.contains("MEDIA_CONTROL {command}"));
    }
}
