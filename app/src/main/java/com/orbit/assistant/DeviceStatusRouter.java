package com.orbit.assistant;

import android.content.Context;

import java.util.regex.Pattern;

/**
 * Simple questions about the phone, answered from the phone.
 *
 * <p>"What's my battery at" needs no model, no network and no account: Android already knows, and
 * the only useful thing Orbit can add is asking it. Every answer here comes from
 * {@link DeviceStatusReader}, which reports what the platform actually returned or says plainly
 * that it could not.
 *
 * <h2>Why this runs before the device commands</h2>
 *
 * <p>{@link LocalCommandRouter} recognises "do not disturb" anywhere in a message and treats it as
 * an instruction, which is right for "turn on do not disturb" and wrong for "is do not disturb
 * on". A question would have switched DND on and then reported that it had. So the question form is
 * resolved first, and it is deliberately the narrowest thing in Orbit's pipeline: the message must
 * open like a question, must name one of the four states Orbit can read, and must contain no verb
 * that would change anything. Everything else falls straight through to the command router exactly
 * as before, which the routing tests pin one phrasing at a time.
 *
 * <h2>Why the two orderings below matter (v0.7.8.0 Beta 2)</h2>
 *
 * <p>A device test found "what is my media volume right now" going to the provider, which answered
 * that it could not read the volume, while "what's my media volume right now" was answered locally
 * a moment later. Two separate defects compounded, and both were about <em>order</em>:
 *
 * <ol>
 *   <li>{@link LanguageNormalizer#stripPoliteness} removes a trailing "right now", which is correct
 *       for a command ("turn the torch on right now" is the same instruction without it) and
 *       destructive here, because "now" is precisely the cue that makes a question about a current
 *       value. The cue is therefore read from the text <em>before</em> politeness is stripped.</li>
 *   <li>The generic conceptual-question guard fired on the expanded "what is …" and not on the
 *       contracted "what's …", so the same question classified two different ways. Contractions are
 *       now expanded before anything is decided, and the guard has been replaced by an explicit
 *       how-to rule that reads both forms identically.</li>
 * </ol>
 *
 * <p>Neither fix changes {@link LanguageNormalizer}, which every other router depends on. The
 * correction is local, and the equivalence of contracted and expanded phrasings is asserted as
 * pairs rather than left to coincidence.
 */
public final class DeviceStatusRouter {
    private DeviceStatusRouter() {}

    /** Past this, a message is a conversation about the phone rather than a question about a value. */
    private static final int MAX_LENGTH = 48;

    /** The question is about <em>this</em> phone. */
    private static final Pattern ABOUT_THIS_PHONE = Pattern.compile(
            "\\b(my|i|me|mine|this phone|the phone)\\b");
    /** Openers that can only be asking for a current value. */
    private static final Pattern STATE_OPENER = Pattern.compile(
            "^(?:is|am|are|how much|how many)\\b");
    /** Words that pin a question to a value right now rather than to the subject in general. */
    private static final Pattern STATE_WORD = Pattern.compile(
            "\\b(at|on|off|now|current|currently|set to|level|percent|percentage)\\b");
    /**
     * The "right now" cue, read before politeness stripping removes it.
     *
     * <p>{@code stripPoliteness} is right to drop this for a command and wrong to drop it here, so
     * this is matched against the un-stripped text and the stripped text is used for everything
     * else. That is the whole of the first Beta 2 fix.
     */
    private static final Pattern NOW_CUE = Pattern.compile(
            "\\b(right now|just now|now|currently|at the moment|at this moment|at present)\\b");
    /**
     * A how-to question, which is a conversation whatever state it names.
     *
     * <p>Replaces the blanket conceptual-question guard, which could not see "what's" and "what is"
     * as the same question. This reads both identically because contractions are expanded first,
     * and it is narrow enough that an ordinary current-state question never matches it.
     */
    private static final Pattern HOW_TO = Pattern.compile(
            "^(?:how (?:do|does|did|can|could|would|should)\\b|how to\\b)");
    /**
     * Words that turn a question about a value into a conversation about a subject.
     *
     * <p>"What is a good screen brightness for reading at night" names brightness, opens like a
     * question, and is nothing Orbit should answer with a number. The indefinite article is the
     * strongest signal in there and is deliberately part of this list.
     */
    private static final Pattern DISCUSSION = Pattern.compile(
            "\\b(a|an|should|would|could|good|bad|best|better|worse|typical|normal|average|"
                    + "for|when|if|about|why|schedule|schedules|tip|tips|advice|mean|means)\\b");

    /**
     * The shapes a status question takes.
     *
     * <p>Anchored at the start, so a status word buried in a longer request cannot trigger this.
     */
    private static final Pattern QUESTION = Pattern.compile(
            "^(?:what(?:'s| is| are)?|whats|how(?:'s| is| much| many)?|hows|is|are|am|do|does|"
                    + "did|tell me|check|show me|give me|are we)\\b.*");

    /**
     * Any verb that would change something.
     *
     * <p>The guard that keeps this router out of the command router's territory: a message that
     * asks Orbit to do something is never a question about state, however it is phrased.
     */
    private static final Pattern CHANGES_SOMETHING = Pattern.compile(
            "\\b(set|change|turn|make|put|raise|lower|increase|decrease|dim|brighten|mute|unmute|"
                    + "silence|adjust|enable|disable|switch|start|stop|toggle|drop|bump|crank)\\b");

    private static final Pattern BATTERY = Pattern.compile(
            "\\b(battery|charging|charged|charge level|plugged in|on charge)\\b");
    private static final Pattern BRIGHTNESS = Pattern.compile("\\b(brightness|screen brightness)\\b");
    /** "How loud is my phone" asks about the media stream without ever saying "volume". */
    private static final Pattern VOLUME = Pattern.compile("\\b(volume|loud|loudness)\\b");
    private static final Pattern DND = Pattern.compile("\\bdo not disturb\\b");
    private static final Pattern RINGER = Pattern.compile(
            "\\b(ringer|ring mode|ringer mode|on silent|on vibrate|silent mode|vibrate mode)\\b");

    // ---- Orbit pipeline entry points ------------------------------------------------------------

    /** Side-effect-free recognition, used to decide a request needs no network. */
    public static boolean canHandle(String raw) {
        return topic(raw) != null;
    }

    /** The reply Orbit gives, or null when this is a question for the AI provider. */
    public static AssistantReply tryHandle(Context context, String raw) {
        Topic topic = topic(raw);
        if (topic == null || context == null) return null;
        return new AssistantReply(read(context, topic).text);
    }

    /** The four device states Orbit can read, plus the ringer. */
    public enum Topic { BATTERY, BRIGHTNESS, MEDIA_VOLUME, DO_NOT_DISTURB, RINGER }

    /** The reading for one topic, so surfaces and tests share one answer. */
    public static DeviceStatusReader.Reading read(Context context, Topic topic) {
        switch (topic) {
            case BATTERY: return DeviceStatusReader.battery(context);
            case BRIGHTNESS: return DeviceStatusReader.brightness(context);
            case MEDIA_VOLUME: return DeviceStatusReader.mediaVolume(context);
            case DO_NOT_DISTURB: return DeviceStatusReader.doNotDisturb(context);
            case RINGER: return DeviceStatusReader.ringer(context);
            default: return DeviceStatusReader.Reading.unavailable("I couldn't read that.");
        }
    }

    /**
     * Which state a message is asking about, or null when it is not asking about one.
     *
     * <p>Pure text, no {@link Context}: recognition can then be exercised exhaustively without a
     * device, which is what the ambiguity tests do.
     */
    public static Topic topic(String raw) {
        // The un-stripped form, so a "right now" the politeness rule is about to remove can still
        // be seen. Contractions are expanded on both forms, so "what's" and "what is" are the same
        // question from here on and cannot take different paths through anything below.
        String spoken = expandContractions(LanguageNormalizer.canonical(raw));
        String q = expandContractions(LanguageNormalizer.stripPoliteness(
                LanguageNormalizer.canonical(raw)));
        if (q.isEmpty() || q.length() > MAX_LENGTH) return null;
        if (!QUESTION.matcher(q).matches()) return null;
        if (CHANGES_SOMETHING.matcher(q).find()) return null;
        if (DISCUSSION.matcher(q).find()) return null;
        if (HOW_TO.matcher(q).find()) return null;

        // A reading is being asked for when the question is about this phone, or when its shape can
        // only be asking for a current value. "What is media volume at" is the second kind and names
        // no possessive at all; "what is do not disturb" is neither, and is a question about a
        // feature that belongs to the provider.
        boolean stateShape = NOW_CUE.matcher(spoken).find()
                || STATE_OPENER.matcher(q).find()
                || STATE_WORD.matcher(q).find();
        if (!stateShape && !ABOUT_THIS_PHONE.matcher(q).find()) return null;

        // Ordered so the more specific topic wins: "is do not disturb on" names DND, not a ringer.
        if (DND.matcher(q).find()) return Topic.DO_NOT_DISTURB;
        if (BATTERY.matcher(q).find()) return Topic.BATTERY;
        if (BRIGHTNESS.matcher(q).find()) return Topic.BRIGHTNESS;
        if (VOLUME.matcher(q).find()) return Topic.MEDIA_VOLUME;
        if (RINGER.matcher(q).find()) return Topic.RINGER;
        return null;
    }

    /**
     * The contracted question openers, written out.
     *
     * <p>Deliberately local rather than in {@link LanguageNormalizer}: every other router already
     * behaves correctly on both forms, and widening a shared normalizer to fix one router's
     * classification would change text that six other matchers read. Only the openers are expanded,
     * because only the openers were classifying differently.
     */
    static String expandContractions(String normalized) {
        if (normalized == null || normalized.isEmpty()) return "";
        String value = normalized;
        value = value.replaceAll("\\bwhat'?s\\b", "what is");
        value = value.replaceAll("\\bwhats\\b", "what is");
        value = value.replaceAll("\\bhow'?s\\b", "how is");
        value = value.replaceAll("\\bhows\\b", "how is");
        value = value.replaceAll("\\bwhere'?s\\b", "where is");
        return value.replaceAll("\\s+", " ").trim();
    }
}
