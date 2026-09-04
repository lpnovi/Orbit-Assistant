package com.orbit.assistant;

import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LocalCommandRouter {
    private LocalCommandRouter() {}

    /** Side-effect-free recognition used to keep Custom Commands from shadowing core device commands. */
    public static boolean canHandle(String raw) {
        if (raw == null || raw.trim().isEmpty()) return false;
        List<String> parts = splitIntoCommandParts(raw.trim());
        if (parts.size() > 1) {
            for (String part : parts) if (parseSingleCommand(part) == null) return false;
            return true;
        }
        return parseSingleCommand(raw.trim()) != null;
    }

    public static AssistantReply tryHandle(Context context, String raw) {
        if (raw == null) return null;
        String normalized = raw.trim();
        if (normalized.isEmpty()) return null;

        List<String> parts = splitIntoCommandParts(normalized);
        if (parts.size() > 1) {
            List<AssistantReply.Action> actions = new ArrayList<>();
            List<String> spoken = new ArrayList<>();
            for (String part : parts) {
                ParsedCommand parsed = parseSingleCommand(part);
                if (parsed == null) return null;
                actions.add(parsed.action);
                spoken.add(parsed.spokenLabel);
            }
            if (!actions.isEmpty()) {
                return new AssistantReply(chainIntro(spoken), actions);
            }
        }

        ParsedCommand single = parseSingleCommand(normalized);
        if (single == null) return null;
        List<AssistantReply.Action> a = new ArrayList<>();
        a.add(single.action);
        return new AssistantReply(single.responseText, a);
    }

    private static ParsedCommand parseSingleCommand(String raw) {
        // Shared tidying, then the polite wrapper people put around a spoken instruction, so the
        // matchers below see the instruction itself rather than every way of asking for it.
        String q = LanguageNormalizer.stripPoliteness(LanguageNormalizer.canonical(raw));
        if (q.isEmpty()) return null;
        // A question about how something works is never a device command.
        if (LanguageNormalizer.isConceptualQuestion(q)) return null;
        try {
            if (q.equals("open settings") || q.equals("open my settings")
                    || q.equals("open my phone settings") || q.equals("open phone settings")
                    || q.equals("open android settings") || q.equals("open the settings")) {
                return new ParsedCommand(action("OPEN_SETTINGS", new JSONObject()),
                        "Opening Settings.", "open Settings");
            }
            if (q.contains("flashlight") || q.contains("torch")) {
                boolean on = !(q.contains("off") || q.contains("disable") || q.contains("turn it off"));
                return new ParsedCommand(action("FLASHLIGHT", new JSONObject().put("on", on)),
                        on ? "Turning on the flashlight." : "Turning off the flashlight.",
                        on ? "turn on the flashlight" : "turn off the flashlight");
            }
            // "dnd" has already been expanded to "do not disturb" by the shared normalizer, so
            // every spelling of the feature reaches one matcher.
            if (q.contains("do not disturb")) {
                boolean off = q.matches(".*\\b(off|disable|disabled|stop|end|cancel|exit)\\b.*");
                boolean enabled = !off;
                return new ParsedCommand(action("SET_DND", new JSONObject().put("enabled", enabled)),
                        enabled ? "Turning on Do Not Disturb." : "Turning off Do Not Disturb.",
                        enabled ? "turn on Do Not Disturb" : "turn off Do Not Disturb");
            }
            // A bare follow-up borrows the target Orbit last acted on, when there is exactly one
            // and the phrase names none of its own. Resolved before anything else so the rest of
            // the parsing sees an ordinary, fully-specified command.
            ParsedCommand followUp = parseRecentActionFollowUp(q);
            if (followUp != null) return followUp;

            // Relative requests are resolved first so clear relative grammar - "lower brightness
            // by 10%" - can never be read by the absolute matchers below as a level of 10%.
            // RelativeLevelCommand refuses anything naming a level with "to"/"at", so an
            // absolute command still falls straight through to them.
            RelativeLevelCommand relative = RelativeLevelCommand.parse(q);
            if (relative != null) {
                JSONObject params = new JSONObject();
                if (relative.absolute) params.put("percent", relative.percent);
                else params.put("delta", relative.delta);
                return new ParsedCommand(action(relative.actionType(), params),
                        relative.confirmation(), relative.summary());
            }
            Matcher brightness = Pattern.compile("(?:set|change|make|put|lower|raise|increase|decrease)?\\s*(?:my\\s+)?brightness(?:\\s*(?:to|at))?\\s*(\\d{1,3})\\s*%?").matcher(q);
            if (brightness.find()) {
                int percent = clampPercent(Integer.parseInt(brightness.group(1)));
                return new ParsedCommand(action("SET_BRIGHTNESS", new JSONObject().put("percent", percent)),
                        "Setting brightness to " + percent + "%.",
                        "set brightness to " + percent + "%");
            }
            Matcher lowerBrightness = Pattern.compile("(?:lower|decrease|dim) (?:my\\s+)?brightness(?:\\s+to)?\\s*(\\d{1,3})\\s*%?").matcher(q);
            if (lowerBrightness.find()) {
                int percent = clampPercent(Integer.parseInt(lowerBrightness.group(1)));
                return new ParsedCommand(action("SET_BRIGHTNESS", new JSONObject().put("percent", percent)),
                        "Lowering brightness to " + percent + "%.",
                        "lower brightness to " + percent + "%");
            }
            Matcher volume = Pattern.compile("(?:set|change|make|put|lower|raise|increase|decrease) (?:my\\s+)?(?:media\\s+)?volume(?:\\s*(?:to|at))?\\s*(\\d{1,3})\\s*%?").matcher(q);
            if (volume.find()) {
                int percent = clampPercent(Integer.parseInt(volume.group(1)));
                return new ParsedCommand(action("SET_VOLUME", new JSONObject().put("percent", percent)),
                        "Setting media volume to " + percent + "%.",
                        "set media volume to " + percent + "%");
            }
            if (q.contains("timer")) {
                ParsedCommand parsedTimer = parseTimer(q);
                if (parsedTimer != null) return parsedTimer;
            }
            ParsedCommand parsedAlarm = parseAlarm(q);
            if (parsedAlarm != null) return parsedAlarm;

            // Playback and the ringer, both of which are plain phrases with no numbers in them, so
            // they sit after every matcher that reads a level and cannot take a request from one.
            ParsedCommand media = parseMedia(q);
            if (media != null) return media;
            ParsedCommand ringer = parseRinger(q);
            if (ringer != null) return ringer;

            // Everyday ways of saying "launch this app". Anchored at the start so a sentence that
            // merely mentions opening something is not treated as an instruction.
            Matcher app = Pattern.compile(
                    "^(?:open|launch|start|run|bring up|pull up|fire up|take me to|go to)\\s+(.+)$")
                    .matcher(q);
            if (app.matches()) {
                String name = cleanAppName(app.group(1));
                if (!name.isEmpty() && name.length() < 60 && !looksLikeSentence(name)) {
                    return new ParsedCommand(action("OPEN_APP", new JSONObject().put("app", name)),
                            "Opening " + name + ".", "open " + name);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Resolves a short follow-up against the device target Orbit last acted on.
     *
     * <p>Only fires when the phrase names no target itself and something was acted on recently.
     * The operation still has to be one the remembered target supports, and "put it back" only
     * works when Orbit holds a real previous reading — it never guesses a level it did not
     * observe.
     */
    private static ParsedCommand parseRecentActionFollowUp(String q) throws org.json.JSONException {
        RecentActionContext.Target target = RecentActionContext.current();
        if (target == null) return null;
        if (!RecentActionContext.isBareFollowUp(q)) return null;

        boolean restore = q.matches("^(?:put|set|turn|change)\\s+(?:it|that)\\s+back$")
                || q.equals("put it back") || q.equals("undo that") || q.equals("revert that");
        if (restore) {
            if (target == RecentActionContext.Target.FLASHLIGHT) {
                boolean previous = RecentActionContext.previousFlashlightOn();
                return new ParsedCommand(
                        action("FLASHLIGHT", new JSONObject().put("on", previous)),
                        previous ? "Turning the flashlight back on." : "Turning the flashlight back off.",
                        previous ? "turn on the flashlight" : "turn off the flashlight");
            }
            int previous = RecentActionContext.previousPercent();
            // Orbit does not know where it was, so it does not pretend to.
            if (previous < 0) return null;
            String type = target == RecentActionContext.Target.BRIGHTNESS
                    ? "SET_BRIGHTNESS" : "SET_VOLUME";
            String noun = target == RecentActionContext.Target.BRIGHTNESS
                    ? "brightness" : "media volume";
            return new ParsedCommand(action(type, new JSONObject().put("percent", previous)),
                    "Putting " + noun + " back to " + previous + "%.",
                    "set " + noun + " back to " + previous + "%");
        }

        if (target == RecentActionContext.Target.FLASHLIGHT) {
            // Only an explicit on/off follow-up applies to a flashlight.
            if (q.matches("^(?:turn|switch)\\s+(?:it|that)\\s+off$") || q.equals("off")) {
                return new ParsedCommand(action("FLASHLIGHT", new JSONObject().put("on", false)),
                        "Turning off the flashlight.", "turn off the flashlight");
            }
            if (q.matches("^(?:turn|switch)\\s+(?:it|that)\\s+on$") || q.equals("on")) {
                return new ParsedCommand(action("FLASHLIGHT", new JSONObject().put("on", true)),
                        "Turning on the flashlight.", "turn on the flashlight");
            }
            return null;
        }

        // A level follow-up: reuse the ordinary relative grammar by naming the remembered target,
        // so magnitudes, extremes and exact deltas all behave exactly as they do when spoken in
        // full.
        String noun = target == RecentActionContext.Target.BRIGHTNESS ? "brightness" : "volume";
        RelativeLevelCommand resolved = RelativeLevelCommand.parse(q + " " + noun);
        if (resolved == null) {
            // "a little more" / "a bit less" carry a direction only in relation to what came
            // before, so they are mapped onto the same relative grammar explicitly.
            String direction = q.matches(".*\\b(more|higher|louder|brighter|up)\\b.*") ? "raise"
                    : q.matches(".*\\b(less|lower|quieter|dimmer|down)\\b.*") ? "lower" : null;
            if (direction == null) return null;
            resolved = RelativeLevelCommand.parse(direction + " " + noun + " " + q);
            if (resolved == null) return null;
        }
        JSONObject params = new JSONObject();
        if (resolved.absolute) params.put("percent", resolved.percent);
        else params.put("delta", resolved.delta);
        return new ParsedCommand(action(resolved.actionType(), params),
                resolved.confirmation(), resolved.summary());
    }

    // ---- playback ---------------------------------------------------------------------------------

    /** What is being played, in the words people use for it. */
    private static final String MEDIA_NOUN =
            "(?:the\\s+|my\\s+|this\\s+)?(?:music|song|songs|track|audio|video|playback|player|"
                    + "podcast|episode|it|this|that)";

    private static final Pattern MEDIA_PAUSE = Pattern.compile(
            "^(?:pause|hold)(?:\\s+" + MEDIA_NOUN + ")?$");
    private static final Pattern MEDIA_PLAY = Pattern.compile(
            "^(?:play|resume|unpause|carry on)(?:\\s+" + MEDIA_NOUN + ")?$");
    /**
     * "next song", "skip", "skip this".
     *
     * <p>A bare "next" is deliberately absent: it is an ordinary word in a conversation and the
     * cost of guessing wrong is the user's music jumping a track. "Skip" is included because it has
     * no other everyday meaning as a whole message.
     */
    private static final Pattern MEDIA_NEXT = Pattern.compile(
            "^(?:next\\s+(?:song|track|one|episode)"
                    + "|skip(?:\\s+(?:this|that|it|the)?\\s*(?:song|track|one|episode)?)?"
                    + "|forward\\s+a\\s+(?:song|track))$");
    private static final Pattern MEDIA_PREVIOUS = Pattern.compile(
            "^(?:previous(?:\\s+(?:song|track|one|episode))?"
                    + "|last\\s+(?:song|track)"
                    + "|(?:go\\s+)?back\\s+(?:a|one)\\s+(?:song|track|episode))$");

    private static ParsedCommand parseMedia(String q) throws org.json.JSONException {
        if (MEDIA_PAUSE.matcher(q).matches()) return media("PAUSE", "Pausing playback.", "pause playback");
        if (MEDIA_PLAY.matcher(q).matches()) return media("PLAY", "Resuming playback.", "resume playback");
        if (MEDIA_NEXT.matcher(q).matches()) return media("NEXT", "Skipping to the next track.", "skip to the next track");
        if (MEDIA_PREVIOUS.matcher(q).matches()) return media("PREVIOUS", "Going back a track.", "go back a track");
        return null;
    }

    private static ParsedCommand media(String command, String spoken, String label)
            throws org.json.JSONException {
        return new ParsedCommand(
                action("MEDIA_CONTROL", new JSONObject().put("command", command)), spoken, label);
    }

    // ---- ringer -----------------------------------------------------------------------------------

    /**
     * Naming the ringer itself, or one of Android's sound profiles by name.
     *
     * <p>Deliberately not "any quiet-sounding word". "Silence everything" and "be quiet" are things
     * people say to an assistant and are not requests to change a phone's ringer profile, so a bare
     * quiet word only counts when the phone is also named — which is what
     * {@link #RINGER_QUIET_WORD} plus {@link #NAMES_THE_PHONE} below require.
     */
    private static final Pattern RINGER_EXPLICIT = Pattern.compile(
            "\\b(ringer|ring mode|ringer mode|sound mode|silent mode|vibrate mode|"
                    + "on silent|on vibrate|off silent|off vibrate|to silent|to vibrate)\\b");
    private static final Pattern RINGER_QUIET_WORD = Pattern.compile(
            "\\b(silent|silence|vibrate|vibration|mute|unmute)\\b");
    private static final Pattern NAMES_THE_PHONE = Pattern.compile("\\b(phone|handset|mobile)\\b");
    /**
     * Words that mean the message is about something else.
     *
     * <p>"Mute the volume" is a media level, "silence notifications" is Do Not Disturb territory,
     * and neither is a request to change the phone's ringer profile.
     */
    private static final Pattern NOT_THE_RINGER = Pattern.compile(
            "\\b(volume|media|notification|notifications|do not disturb|alarm|timer)\\b");
    /** Ways of asking for the ringer back. Checked first, because they also name a quiet mode. */
    private static final Pattern RINGER_TO_NORMAL = Pattern.compile(
            "\\b(off (?:silent|vibrate)|out of (?:silent|vibrate)|un(?:mute|silence)|normal)\\b"
                    + "|\\bring(?:er)?\\b.*\\bon\\b|\\bon\\b.*\\bring(?:er)?\\b");

    private static ParsedCommand parseRinger(String q) throws org.json.JSONException {
        boolean explicit = RINGER_EXPLICIT.matcher(q).find();
        boolean aboutThePhone = RINGER_QUIET_WORD.matcher(q).find()
                && NAMES_THE_PHONE.matcher(q).find();
        if (!explicit && !aboutThePhone) return null;
        if (NOT_THE_RINGER.matcher(q).find()) return null;
        // A question about the ringer was already answered by DeviceStatusRouter; anything reaching
        // here that still reads as a question is left alone rather than acted on.
        if (q.startsWith("is ") || q.startsWith("am ") || q.startsWith("what")) return null;

        String mode;
        if (RINGER_TO_NORMAL.matcher(q).find()) mode = "normal";
        else if (q.matches(".*\\bvibrat(?:e|ion)\\b.*")) mode = "vibrate";
        else mode = "silent";

        String spoken = "normal".equals(mode) ? "Turning the ringer back on."
                : "vibrate".equals(mode) ? "Putting the phone on vibrate."
                : "Silencing the phone.";
        String label = "normal".equals(mode) ? "turn the ringer back on"
                : "vibrate".equals(mode) ? "put the phone on vibrate" : "silence the phone";
        return new ParsedCommand(
                action("SET_RINGER_MODE", new JSONObject().put("mode", mode)), spoken, label);
    }

    /**
     * Talking about an existing timer is not asking for a new one.
     *
     * <p>The counterpart patterns that used to read the duration itself are gone: how long a timer
     * runs for is now {@link DurationParser}'s single responsibility, and keeping a second unit
     * grammar here is what let the two disagree.
     */
    private static final Pattern TIMER_NOT_A_NEW_REQUEST = Pattern.compile(
            "\\b(cancel|cancelled|stop|stopped|pause|paused|resume|delete|remove|remaining|left|"
                    + "how long|check|list|show)\\b");

    /**
     * "a timer for the potatoes" — what the timer is for, said after it.
     *
     * <p>The determiner is required, and that is the whole guard: "for the potatoes" names
     * something, "for 10 minutes" is the duration, and only the first has one.
     */
    private static final Pattern TIMER_SUBJECT_AFTER = Pattern.compile(
            "\\bfor\\s+(?:the|my|our|some)\\s+([a-z][a-z' -]{1,24}?)(?=\\s+\\d|\\s+for\\b|\\s+and\\b|$)");
    /** "a steak timer" — what the timer is for, said in front of it. */
    private static final Pattern TIMER_SUBJECT_BEFORE = Pattern.compile(
            "\\b([a-z][a-z'-]{1,19})\\s+timer\\b");
    /**
     * Words that stand in front of "timer" without naming anything.
     *
     * <p>Counts are excluded separately through {@link LanguageNormalizer#wordNumber(String)}, so
     * "a five minute timer" cannot end up labelled "Five".
     */
    private static final java.util.Set<String> TIMER_SUBJECT_STOP_WORDS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "a", "an", "the", "my", "our", "your", "new", "another", "same", "that",
                    "this", "it", "quick", "short", "long", "second", "seconds", "minute",
                    "minutes", "hour", "hours", "min", "mins", "sec", "secs", "hr", "hrs",
                    "and", "or", "set", "start", "begin", "make", "create", "run", "orbit",
                    "countdown", "up", "also", "please", "for", "of", "to"));

    /**
     * What the user is timing, as a label for the Clock app, or "" when nothing was named.
     *
     * <p>Timing food is the common case for a phone timer, and a Clock notification that says
     * "Steak" is far more useful than four identical ones that all say "Orbit timer". Only an
     * explicitly named subject is used; nothing is inferred from the rest of the sentence.
     */
    static String timerSubject(String q) {
        if (q == null || q.isEmpty()) return "";
        Matcher after = TIMER_SUBJECT_AFTER.matcher(q);
        while (after.find()) {
            String named = cleanTimerSubject(after.group(1));
            if (!named.isEmpty()) return named;
        }
        Matcher before = TIMER_SUBJECT_BEFORE.matcher(q);
        while (before.find()) {
            String word = before.group(1);
            if (TIMER_SUBJECT_STOP_WORDS.contains(word)) continue;
            if (LanguageNormalizer.wordNumber(word) > 0) continue;
            String named = cleanTimerSubject(word);
            if (!named.isEmpty()) return named;
        }
        return "";
    }

    /** Trims a named subject and gives it the sentence case a Clock label is written in. */
    private static String cleanTimerSubject(String value) {
        String name = value == null ? "" : value.trim();
        name = name.replaceAll("\\s+", " ");
        if (name.isEmpty() || name.length() > 24) return "";
        // A subject made only of filler names nothing worth labelling.
        String[] words = name.split(" ");
        boolean meaningful = false;
        for (String word : words) {
            if (!TIMER_SUBJECT_STOP_WORDS.contains(word) && LanguageNormalizer.wordNumber(word) <= 0) {
                meaningful = true;
                break;
            }
        }
        if (!meaningful) return "";
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * Reads the duration a timer request states, wherever the user put it.
     *
     * <p>The three positions are unchanged from the pattern-matching version — after the word,
     * before it, or further along with the subject in between — because those are the three ways
     * people actually word it, and each carries a different risk of picking up a number that
     * belongs to something else. What changed is what reads each position: {@link DurationParser}
     * instead of one {@code (count)(unit)} regex.
     *
     * <p>That regex is why "set a timer for 4 minutes and 30 seconds" reached Samsung Clock as
     * 4:00. It matched the first pair and stopped, so "and 30 seconds" was discarded in silence,
     * and "4 and a half minutes" matched nothing at all. Both now sum every component in the single
     * run of duration words belonging to this timer, and stop at the end of that run, so a chained
     * "and remind me in 5 minutes" can never be added to it.
     */
    private static ParsedCommand parseTimer(String q) throws org.json.JSONException {
        int at = q.indexOf("timer");
        if (at < 0) return null;
        String after = q.substring(at + "timer".length());
        String before = q.substring(0, at);

        DurationParser.Parsed parsed = DurationParser.parseAdjacentAfter(after);
        if (!parsed.isValid()) parsed = DurationParser.parseTrailingBefore(before);
        // A duration said further along, with the subject in between: "timer for the bread, 30
        // minutes". Searched only when the sentence is not about an existing timer, so "how long is
        // left on my 20 minute timer" cannot start a new one from a number it merely mentioned.
        if (!parsed.isValid() && !TIMER_NOT_A_NEW_REQUEST.matcher(q).find()) {
            parsed = DurationParser.parseFirstRun(after);
        }
        if (!parsed.isValid()) return null;
        long seconds = parsed.seconds;

        // The duration modifies "timer" here, so a single unit is hyphenated and singular: "a
        // 20-minute timer", never "a 20 minutes timer". Built from the count and unit the user
        // actually said, so asking for 90 minutes is confirmed as 90 minutes rather than restated
        // as an hour and a half. A duration genuinely spanning units has no such original form to
        // preserve and is spoken in order instead: "a 4 minute 30 second timer".
        String spokenDuration = parsed.singleUnit
                ? RoutineActionCatalog.durationModifier(parsed.count, parsed.unit)
                : DurationParser.spokenModifier(seconds);
        // The timer itself is unchanged: Android's own Clock still owns it through SET_TIMER. Only
        // the label improves, so a Clock notification can say what is actually cooking.
        String subject = timerSubject(q);
        String label = subject.isEmpty() ? "Orbit timer" : subject;
        String forSubject = subject.isEmpty() ? "" : " for " + subject.toLowerCase(Locale.US);
        return new ParsedCommand(
                action("SET_TIMER", new JSONObject().put("seconds", seconds).put("label", label)),
                "Setting a " + spokenDuration + " timer" + forSubject + ".",
                "set a " + spokenDuration + " timer" + forSubject);
    }

    /** Day words Orbit's alarm action cannot represent, so it must not pretend otherwise. */
    private static final Pattern ALARM_DATE_WORDS = Pattern.compile(
            "\\b(tomorrow|tonight|monday|tuesday|wednesday|thursday|friday|saturday|sunday|" +
            "weekday|weekdays|weekend|every day|everyday|daily|next week|in \\d+ days?)\\b");
    private static final Pattern ALARM_TIME = Pattern.compile(
            "(?:alarm|wake me(?: up)?)\\s*(?:for|at)?\\s*(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b");

    private static ParsedCommand parseAlarm(String q) throws org.json.JSONException {
        if (!q.contains("alarm") && !q.contains("wake me")) return null;

        // The SET_ALARM action carries only an hour and a minute. Silently dropping "tomorrow"
        // would set an alarm for a different day than the user asked for and then report success,
        // so a request Orbit cannot represent is left to the normal assistant path instead.
        if (ALARM_DATE_WORDS.matcher(q).find()) return null;

        Matcher m = ALARM_TIME.matcher(q);
        if (!m.find()) return null;

        int hour = Integer.parseInt(m.group(1));
        int minute = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        if (hour > 23 || minute > 59) return null;
        String ap = m.group(3);
        if ("pm".equals(ap) && hour < 12) hour += 12;
        if ("am".equals(ap) && hour == 12) hour = 0;
        return new ParsedCommand(
                action("SET_ALARM", new JSONObject().put("hour", hour).put("minute", minute)
                        .put("label", "Orbit alarm")),
                "Opening your Clock app with that alarm.", "set an alarm");
    }

    /** Trims filler that trails an app name in ordinary speech. */
    private static String cleanAppName(String value) {
        String name = value == null ? "" : value.trim();
        name = name.replaceAll("^(?:the|my|up)\\s+", "");
        name = name.replaceAll("\\s+(?:app|application)$", "");
        return name.trim();
    }

    /**
     * Guards the app matcher against swallowing a sentence. "start a 10 minute timer" and
     * "open the pod bay doors and explain why" are not app names.
     */
    private static boolean looksLikeSentence(String name) {
        if (name.matches(".*\\b(?:timer|alarm|brightness|volume|flashlight|do not disturb)\\b.*")) {
            return true;
        }
        return name.split("\\s+").length > 4;
    }

    private static List<String> splitIntoCommandParts(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        String normalized = raw.trim().replaceAll("\\s+", " ");
        // Voice transcription often drops punctuation/conjunctions between commands,
        // for example: "set media volume to 25% turn on my flashlight and open YouTube".
        // Insert an implicit boundary after a percentage when another clear device
        // command immediately follows so the later command cannot swallow the first.
        normalized = normalized.replaceAll(
                "(?i)(\\d{1,3}\\s*%?)\\s+(?=(?:turn|open|set|change|make|put|lower|raise|increase|decrease|dim)\\b)",
                "$1 | ");
        // Which separator stood before each piece is remembered, because putting a piece back has
        // to put the word back too. Rejoining "set a timer for 4" and "a half minutes" without the
        // "and" produces "4 a half minutes", which reads as two minutes.
        List<String> pieces = new ArrayList<>();
        List<String> joiners = new ArrayList<>();
        Matcher separator = COMMAND_SEPARATOR.matcher(normalized);
        int from = 0;
        while (separator.find()) {
            pieces.add(normalized.substring(from, separator.start()).trim());
            joiners.add(separator.group(1).trim().toLowerCase(Locale.US));
            from = separator.end();
        }
        pieces.add(normalized.substring(from).trim());

        for (int i = 0; i < pieces.size(); i++) {
            String part = pieces.get(i);
            if (part.isEmpty()) continue;
            String joiner = i == 0 ? "" : joiners.get(i - 1);
            if (!out.isEmpty() && continuesDuration(out.get(out.size() - 1), joiner, part)) {
                out.set(out.size() - 1, out.get(out.size() - 1) + glue(joiner) + part);
                continue;
            }
            out.add(part);
        }
        return out;
    }

    /** The words and marks that separate two device commands, captured so they can be restored. */
    private static final Pattern COMMAND_SEPARATOR = Pattern.compile(
            "(?i)\\s*(\\||,| then | and then |\\band\\b)\\s*");

    private static String glue(String joiner) {
        return "and".equals(joiner) ? " and " : " ";
    }

    /**
     * Whether a separator was inside a duration rather than between two commands.
     *
     * <p>"Set a timer for 4 minutes and 30 seconds" and "set a timer for 4 and a half minutes" are
     * one request each, but "and" is also how two requests are chained, and splitting on it blindly
     * is the other half of why both produced a four-minute timer: the first lost "30 seconds" to a
     * second command part that parsed as nothing, and the second lost "a half minutes" the same
     * way. Neither failure was visible, because a chain whose parts do not all parse is simply
     * handed to the model.
     *
     * <p>Two conditions, and both are needed. The fragment must not be a command in its own right,
     * which is what keeps "turn on the flashlight and set a timer for 5 minutes" as two actions.
     * And putting it back must make the duration <em>longer</em> — a fragment that adds nothing was
     * never part of the duration to begin with.
     */
    private static boolean continuesDuration(String previous, String joiner, String part) {
        if (previous == null || part == null) return false;
        if (parseSingleCommand(part) != null) return false;
        long before = DurationParser.parseFirstRun(previous).seconds;
        long after = DurationParser.parseFirstRun(previous + glue(joiner) + part).seconds;
        return after > before;
    }

    private static String chainIntro(List<String> spoken) {
        if (spoken == null || spoken.isEmpty()) return "Working on it.";
        if (spoken.size() == 1) return "Okay, I'll " + spoken.get(0) + ".";
        StringBuilder b = new StringBuilder("Okay, I'll ");
        for (int i = 0; i < spoken.size(); i++) {
            if (i > 0) b.append(i == spoken.size() - 1 ? ", and " : ", ");
            b.append(spoken.get(i));
        }
        b.append('.');
        return b.toString();
    }

    private static AssistantReply.Action action(String type, JSONObject params) {
        return new AssistantReply.Action(type, params, false);
    }

    private static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    private static final class ParsedCommand {
        final AssistantReply.Action action;
        final String responseText;
        final String spokenLabel;

        ParsedCommand(AssistantReply.Action action, String responseText, String spokenLabel) {
            this.action = action;
            this.responseText = responseText;
            this.spokenLabel = spokenLabel;
        }
    }
}
