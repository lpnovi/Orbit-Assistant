package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.provider.Settings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNotificationManager;

/**
 * Reading the phone back, and knowing when Orbit cannot.
 *
 * <p>Two halves. The recognizer decides whether a message is a question about a state at all, and it
 * has to be narrow enough that "is Do Not Disturb on" never becomes an instruction to switch it on
 * — which is exactly what would have happened before this existed, because the command router
 * recognises those words anywhere in a message. The reader turns Android's own values into a
 * sentence, and reports honestly when the platform will not give one up.
 *
 * <p>No provider is involved anywhere below, which is the point: these answers cost nothing and work
 * with the phone in flight mode.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class DeviceStatusTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
    }

    // ---- recognition -------------------------------------------------------------------------------

    @Test public void batteryQuestionsAreRecognised() {
        assertEquals(DeviceStatusRouter.Topic.BATTERY, DeviceStatusRouter.topic("what's my battery at?"));
        assertEquals(DeviceStatusRouter.Topic.BATTERY, DeviceStatusRouter.topic("how much battery do I have"));
        assertEquals(DeviceStatusRouter.Topic.BATTERY, DeviceStatusRouter.topic("am I charging"));
        assertEquals(DeviceStatusRouter.Topic.BATTERY, DeviceStatusRouter.topic("is my phone charging"));
    }

    @Test public void theOtherStatesAreRecognised() {
        assertEquals(DeviceStatusRouter.Topic.BRIGHTNESS,
                DeviceStatusRouter.topic("what's my brightness"));
        assertEquals(DeviceStatusRouter.Topic.BRIGHTNESS,
                DeviceStatusRouter.topic("what brightness am I at"));
        assertEquals(DeviceStatusRouter.Topic.MEDIA_VOLUME,
                DeviceStatusRouter.topic("what's my volume"));
        assertEquals(DeviceStatusRouter.Topic.MEDIA_VOLUME,
                DeviceStatusRouter.topic("what is media volume at"));
        assertEquals(DeviceStatusRouter.Topic.DO_NOT_DISTURB,
                DeviceStatusRouter.topic("is do not disturb on"));
        assertEquals(DeviceStatusRouter.Topic.DO_NOT_DISTURB,
                DeviceStatusRouter.topic("is dnd on"));
        assertEquals(DeviceStatusRouter.Topic.RINGER,
                DeviceStatusRouter.topic("is my phone on silent"));
    }

    /**
     * The whole reason this router runs before the command router.
     *
     * <p>Both of these contain the words that make {@link LocalCommandRouter} act. One is a question
     * and one is an instruction, and the difference has to survive being routed.
     */
    @Test public void aQuestionAboutDndIsNeverAnInstruction() {
        assertEquals(DeviceStatusRouter.Topic.DO_NOT_DISTURB,
                DeviceStatusRouter.topic("is do not disturb on"));
        assertNull("but turning it on is a command, not a question",
                DeviceStatusRouter.topic("turn on do not disturb"));

        AssistantReply command = LocalCommandRouter.tryHandle(context, "turn on do not disturb");
        assertNotNull(command);
        assertEquals("SET_DND", command.actions.get(0).type);
    }

    /** An instruction that happens to be phrased as a question is still an instruction. */
    @Test public void anythingThatWouldChangeSomethingIsNotAStatusQuestion() {
        assertNull(DeviceStatusRouter.topic("can you turn the brightness down"));
        assertNull(DeviceStatusRouter.topic("set my volume to 40"));
        assertNull(DeviceStatusRouter.topic("put my phone on silent"));
        assertNull(DeviceStatusRouter.topic("is it possible to turn off do not disturb"));
    }

    /** A conversation about a state is a conversation, and belongs to the provider. */
    @Test public void conversationalQuestionsStillReachTheProvider() {
        assertNull(DeviceStatusRouter.topic("why does my battery seem worse lately"));
        assertNull(DeviceStatusRouter.topic("how do batteries degrade"));
        assertNull(DeviceStatusRouter.topic("what is a good screen brightness for reading at night"));
        assertNull(DeviceStatusRouter.topic("tell me about do not disturb schedules on Samsung phones"));
        assertNull(DeviceStatusRouter.topic(""));
        assertNull(DeviceStatusRouter.topic(null));
    }

    @Test public void recognitionMatchesHandling() {
        assertTrue(DeviceStatusRouter.canHandle("what's my battery at"));
        assertFalse(DeviceStatusRouter.canHandle("why does my battery seem worse lately"));
    }

    // ---- the readings ------------------------------------------------------------------------------

    private void broadcastBattery(int level, int scale, int status, int plugged) {
        Intent battery = new Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, level)
                .putExtra(BatteryManager.EXTRA_SCALE, scale)
                .putExtra(BatteryManager.EXTRA_STATUS, status)
                .putExtra(BatteryManager.EXTRA_PLUGGED, plugged);
        // A sticky broadcast is exactly how Android answers a battery query, so the reader is
        // exercised through the same registerReceiver(null, ...) path it uses on a device.
        context.sendStickyBroadcast(battery);
    }

    @Test public void aDischargingBatteryIsReportedAsSuch() {
        broadcastBattery(41, 100, BatteryManager.BATTERY_STATUS_DISCHARGING, 0);
        DeviceStatusReader.Reading reading = DeviceStatusReader.battery(context);
        assertTrue(reading.available);
        assertEquals("Your battery is at 41% and isn't charging.", reading.text);
    }

    @Test public void aChargingBatteryNamesItsSource() {
        broadcastBattery(68, 100, BatteryManager.BATTERY_STATUS_CHARGING,
                BatteryManager.BATTERY_PLUGGED_USB);
        assertEquals("Your battery is at 68% and charging over USB.",
                DeviceStatusReader.battery(context).text);

        broadcastBattery(90, 100, BatteryManager.BATTERY_STATUS_CHARGING,
                BatteryManager.BATTERY_PLUGGED_WIRELESS);
        assertEquals("Your battery is at 90% and charging wirelessly.",
                DeviceStatusReader.battery(context).text);

        broadcastBattery(100, 100, BatteryManager.BATTERY_STATUS_FULL,
                BatteryManager.BATTERY_PLUGGED_AC);
        assertEquals("Your battery is at 100% and fully charged over mains.",
                DeviceStatusReader.battery(context).text);
    }

    /** A device that reports on a different scale still produces the right percentage. */
    @Test public void thePercentageComesFromLevelAndScale() {
        broadcastBattery(50, 200, BatteryManager.BATTERY_STATUS_DISCHARGING, 0);
        assertEquals("Your battery is at 25% and isn't charging.",
                DeviceStatusReader.battery(context).text);
    }

    /** No value is better than an invented one. */
    @Test public void anUnreadableBatteryIsReportedAsUnavailable() {
        broadcastBattery(-1, -1, BatteryManager.BATTERY_STATUS_UNKNOWN, 0);
        DeviceStatusReader.Reading reading = DeviceStatusReader.battery(context);
        assertFalse(reading.available);
        assertTrue(reading.text.contains("didn't report"));
        assertFalse("and never a percentage", reading.text.contains("%"));
    }

    @Test public void plugSourcesAreNamedOrLeftUnnamed() {
        assertEquals("mains", DeviceStatusReader.plugSource(BatteryManager.BATTERY_PLUGGED_AC));
        assertEquals("USB", DeviceStatusReader.plugSource(BatteryManager.BATTERY_PLUGGED_USB));
        assertEquals("wireless", DeviceStatusReader.plugSource(BatteryManager.BATTERY_PLUGGED_WIRELESS));
        assertEquals("a plug Orbit has no word for is left unnamed rather than guessed",
                "", DeviceStatusReader.plugSource(64));
    }

    @Test public void brightnessNormalisesTheRawAndroidValue() {
        Settings.System.putInt(context.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, 255);
        assertEquals("Your brightness is at 100%.", DeviceStatusReader.brightness(context).text);

        Settings.System.putInt(context.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, 128);
        assertEquals("Your brightness is at 50%.", DeviceStatusReader.brightness(context).text);
    }

    @Test public void mediaVolumeIsAPercentageOfTheRealStream() {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0);
        assertEquals(100, DeviceStatusReader.mediaVolumePercent(context));
        assertEquals("Your media volume is at 100%.", DeviceStatusReader.mediaVolume(context).text);

        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
        assertEquals(0, DeviceStatusReader.mediaVolumePercent(context));
        assertTrue(DeviceStatusReader.mediaVolume(context).text.contains("silent"));
    }

    @Test public void theRingerModeIsReadDirectly() {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        audio.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
        assertEquals("Vibrate", DeviceStatusReader.ringerModeName(context));
        assertEquals("Your phone is on vibrate.", DeviceStatusReader.ringer(context).text);

        audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        assertEquals("Normal", DeviceStatusReader.ringerModeName(context));
    }

    /** Without Do Not Disturb access Orbit says it cannot see, rather than guessing "off". */
    @Test public void doNotDisturbNeedsAccessAndSaysSo() {
        ShadowNotificationManager shadow = Shadows.shadowOf(
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE));
        shadow.setNotificationPolicyAccessGranted(false);
        DeviceStatusReader.Reading blocked = DeviceStatusReader.doNotDisturb(context);
        assertFalse(blocked.available);
        assertTrue("and names the access it would need", blocked.needsAccess);
        assertFalse("never a claim about the state", blocked.text.contains("is off"));
    }

    @Test public void doNotDisturbIsReadWhenAccessIsGranted() {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        ShadowNotificationManager shadow = Shadows.shadowOf(manager);
        shadow.setNotificationPolicyAccessGranted(true);

        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
        assertEquals("Do Not Disturb is off.", DeviceStatusReader.doNotDisturb(context).text);

        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY);
        assertTrue(DeviceStatusReader.doNotDisturb(context).text.startsWith("Do Not Disturb is on"));
    }

    /** The router hands back exactly the reader's sentence, so the two cannot disagree. */
    @Test public void theRouterAnswersWithTheReading() {
        broadcastBattery(55, 100, BatteryManager.BATTERY_STATUS_DISCHARGING, 0);
        AssistantReply reply = DeviceStatusRouter.tryHandle(context, "what's my battery at");
        assertNotNull(reply);
        assertEquals("Your battery is at 55% and isn't charging.", reply.text);
        assertTrue("a status answer performs no action", reply.actions.isEmpty());
    }
}
