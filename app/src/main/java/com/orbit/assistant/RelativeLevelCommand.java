package com.orbit.assistant;

import java.util.Locale;

/**
 * Natural relative brightness and media-volume requests.
 *
 * <p>"Lower my brightness" is answerable on the device: Orbit already knows the current level, so
 * asking "how much?" is a question the phone can answer for itself. This turns those phrases into
 * an ordinary SET_BRIGHTNESS / SET_VOLUME action carrying a signed {@code delta} instead of an
 * absolute {@code percent}, which {@link DeviceActionExecutor} applies to the live value.
 *
 * <p>An explicit percentage is never reinterpreted: a phrase containing a number is refused here
 * and left to the existing absolute matchers, so "set my brightness to 30%" still means exactly 30.
 */
public final class RelativeLevelCommand {
    /** "a little", "a bit", "slightly". */
    public static final int SMALL_STEP = 5;
    /** Plain "lower it" / "raise it". */
    public static final int DEFAULT_STEP = 15;
    /** "a lot", "way down", "much higher". */
    public static final int LARGE_STEP = 30;

    public enum Target { BRIGHTNESS, VOLUME }

    public final Target target;
    /** True for "maximum"/"mute" style requests, which name a level rather than a movement. */
    public final boolean absolute;
    /** Absolute level when {@link #absolute}; otherwise unused. */
    public final int percent;
    /** Signed percentage-point movement when relative; otherwise unused. */
    public final int delta;

    private RelativeLevelCommand(Target target, boolean absolute, int percent, int delta) {
        this.target = target;
        this.absolute = absolute;
        this.percent = percent;
        this.delta = delta;
    }

    /** The action type this request maps onto. */
    public String actionType() {
        return target == Target.BRIGHTNESS ? "SET_BRIGHTNESS" : "SET_VOLUME";
    }

    private String noun() {
        return target == Target.BRIGHTNESS ? "brightness" : "media volume";
    }

    /** What Orbit says it is doing, in the same voice as the existing local commands. */
    public String confirmation() {
        if (absolute) {
            if (percent >= 100) return "Setting " + noun() + " to maximum.";
            if (percent <= 0) {
                return target == Target.VOLUME ? "Muting media volume." : "Setting brightness to minimum.";
            }
            return "Setting " + noun() + " to " + percent + "%.";
        }
        return (delta < 0 ? "Lowering " : "Raising ") + noun() + ".";
    }

    /** Short description used where Orbit names the action it performed. */
    public String summary() {
        if (absolute) {
            if (percent >= 100) return "set " + noun() + " to maximum";
            if (percent <= 0) return target == Target.VOLUME ? "mute media volume" : "set brightness to minimum";
            return "set " + noun() + " to " + percent + "%";
        }
        return (delta < 0 ? "lower " : "raise ") + noun();
    }

    /**
     * Parses one command phrase, or returns null when it is not a relative level request.
     *
     * <p>Both a target and a direction have to be present, so ordinary conversation about
     * brightness or volume never turns into a device action.
     */
    public static RelativeLevelCommand parse(String raw) {
        if (raw == null) return null;
        String q = raw.toLowerCase(Locale.US).trim();
        if (q.isEmpty()) return null;
        // An explicit number means the user already said the level they want.
        if (q.matches(".*\\d.*")) return null;

        // A question about the device is not an instruction to change it.
        if (q.matches("^(what|how|why|when|where|who|whose|which)\\b.*")) return null;

        Target target = targetOf(q);
        if (target == null) return null;

        // Extremes name a level rather than a movement.
        boolean allTheWay = q.contains("all the way");
        if (word(q, "maximum") || word(q, "max") || q.contains("full blast")
                || q.contains("as high as") || q.contains("as loud as") || q.contains("as bright as")
                || (allTheWay && directionOf(q) > 0)) {
            return new RelativeLevelCommand(target, true, 100, 0);
        }
        if (word(q, "minimum") || word(q, "min")
                || q.contains("as low as") || q.contains("as dim as")
                || (target == Target.VOLUME && (word(q, "mute") || word(q, "silence")))
                || (allTheWay && directionOf(q) < 0)) {
            return new RelativeLevelCommand(target, true, 0, 0);
        }

        int direction = directionOf(q);
        if (direction == 0) return null;
        return new RelativeLevelCommand(target, false, 0, direction * magnitudeOf(q));
    }

    private static Target targetOf(String q) {
        // Orbit's "volume" has always meant the media stream. Naming another stream is left to
        // the existing behaviour rather than silently redirected here.
        if (q.contains("ringtone") || q.contains("ringer") || q.contains("alarm volume")
                || q.contains("call volume") || q.contains("notification volume")) {
            return null;
        }
        // "dim" and "brighten" are ordinary English words, so on their own they are not enough:
        // "dim sum for lunch" must never reach the screen. They only name this target alongside
        // an explicit surface.
        boolean surface = word(q, "screen") || word(q, "display") || word(q, "backlight");
        boolean brightness = word(q, "brightness")
                || ((word(q, "dim") || word(q, "brighten") || word(q, "dimmer")
                || word(q, "brighter") || word(q, "darker")) && surface);
        boolean volume = word(q, "volume") || word(q, "sound") || word(q, "louder")
                || word(q, "quieter") || word(q, "mute") || word(q, "audio");
        if (brightness && volume) return null;
        if (brightness) return Target.BRIGHTNESS;
        if (volume) return Target.VOLUME;
        return null;
    }

    /** Whole-word match, so "dim" never fires on "dim sum" and "sound" never on "sounds good". */
    private static boolean word(String q, String value) {
        return q.matches(".*\\b" + value + "\\b.*");
    }

    /** -1 for down, 1 for up, 0 when the phrase does not ask for a movement. */
    private static int directionOf(String q) {
        boolean down = word(q, "lower") || word(q, "decrease") || word(q, "reduce")
                || word(q, "dim") || word(q, "quieter") || word(q, "softer")
                || word(q, "down") || word(q, "darker") || word(q, "dimmer")
                || word(q, "mute") || word(q, "quiet");
        boolean up = word(q, "raise") || word(q, "increase") || word(q, "brighten")
                || word(q, "louder") || word(q, "brighter") || word(q, "up")
                || word(q, "boost");
        if (down && up) return 0;
        if (down) return -1;
        if (up) return 1;
        return 0;
    }

    private static int magnitudeOf(String q) {
        if (q.contains("a little") || q.contains("a bit") || q.contains("slightly")
                || q.contains("a touch") || q.contains("a tad") || q.contains("a smidge")
                || q.contains("little bit") || q.contains("bit lower") || q.contains("bit higher")) {
            return SMALL_STEP;
        }
        if (q.contains("a lot") || q.contains("a bunch") || q.contains("way down")
                || q.contains("way up") || q.contains("much lower") || q.contains("much higher")
                || q.contains("much brighter") || q.contains("much dimmer")
                || q.contains("significantly") || q.contains("considerably")
                || q.contains("a great deal") || q.contains("loads")) {
            return LARGE_STEP;
        }
        return DEFAULT_STEP;
    }

    /** Clamps a resulting level to the safe 0-100 range. */
    public static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
