package com.orbit.assistant;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.Intent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Orbit Local's semantic device actions: the last stop before a request reaches a provider.
 *
 * <p>Orbit's deterministic parsers are fast, offline and exact, and they will never cover every way
 * a person phrases something. "Kill the torch", "this is way too loud, bring it down some" and
 * "could you make the screen a little dimmer" are all ordinary instructions and none of them
 * matches a regex Orbit would want to write. Before this, each of them cost a network round trip to
 * a cloud model to do something the phone could have done itself.
 *
 * <p>So there is now a small model on the device whose only job is to turn one short instruction
 * into one normalized Orbit action. It is a <em>fallback</em>, in the strict sense:
 *
 * <ol>
 *   <li>{@link LocalCommandRouter} and the other deterministic routers run first and keep every
 *       request they can handle. "Flashlight on" never wakes a neural network.</li>
 *   <li>This runs only when the action model is installed and enabled, and only when the message
 *       already looks like an instruction about something Orbit can actually control.</li>
 *   <li>Whatever the model writes goes through {@link LocalActionSchema}, which builds Orbit's own
 *       action from checked values or rejects the output entirely.</li>
 *   <li>The action runs on the same {@link DeviceActionExecutor} every other path uses. There is no
 *       second implementation of anything.</li>
 *   <li>Anything that fails, at any step, falls through to the provider exactly as before.</li>
 * </ol>
 *
 * <p>The model never sees a conversation, never produces prose the user reads, and never controls
 * Android. It reads one sentence and writes one small JSON object, and Orbit decides what that is
 * worth.
 */
public final class OrbitLocalActionRouter {
    private OrbitLocalActionRouter() {}

    /** How long Orbit waits for the small model before giving the request to the provider. */
    static final long TIMEOUT_MS = 6000L;
    /** Past this, a message is a conversation rather than an instruction. */
    static final int MAX_LENGTH = 140;

    /**
     * The subjects the Beta 1 allowlist can actually act on.
     *
     * <p>The gate that stops ordinary chat reaching the model at all. A message that mentions none
     * of these could not produce an allowed action even if the model tried, so running inference on
     * it would be pure cost.
     */
    private static final Pattern ACTIONABLE_SUBJECT = Pattern.compile(
            "\\b(flashlight|torch|light on|light off|brightness|screen|display|dimmer|dim|brighter|"
                    + "volume|loud|louder|quiet|quieter|sound|do not disturb|ringer|silent|silence|"
                    + "vibrate|timer|countdown|alarm|wake me|music|song|track|playing|playback|"
                    + "pause|resume|skip|settings|open|launch|start|pull up|fire up|bring up|"
                    // A duration with no other cue is how people ask for a timer: "give me ten
                    // minutes for the pasta" names nothing else Orbit could act on.
                    + "seconds?|minutes?|hours?)\\b");

    /**
     * Phrasings that are questions or discussion rather than instructions.
     *
     * <p>Deliberately more than {@link LanguageNormalizer#isConceptualQuestion}: "what's my
     * brightness" is already answered by {@link DeviceStatusRouter}, and anything else that opens
     * like a question is a conversation the provider should have.
     */
    private static final Pattern NOT_AN_INSTRUCTION = Pattern.compile(
            "^(?:what|why|when|who|which|whats|how come|tell me about|explain|do you|does it|"
                    + "did you|is there|are there|should i|could i|can i)\\b");

    /** Day words {@code SET_ALARM} cannot represent, so an alarm naming one is refused. */
    private static final Pattern ALARM_NAMES_A_DAY = Pattern.compile(
            "\\b(tomorrow|tonight|monday|tuesday|wednesday|thursday|friday|saturday|sunday|"
                    + "weekday|weekdays|weekend|every day|everyday|daily|next week|in \\d+ days?)\\b");

    // ---- availability -----------------------------------------------------------------------------

    /** Whether the action model is installed, enabled, and reachable right now. */
    public static boolean available(Context context) {
        if (context == null) return false;
        if (!Prefs.localDeviceActions(context)) return false;
        if (!OrbitLocalComponent.isUsable(context)) return false;
        OrbitLocalStatus status = OrbitLocalProvider.cachedStatus(context);
        return status != null && status.actionModelReady();
    }

    /**
     * Whether a message is worth handing to the action model.
     *
     * <p>Pure text, so the whole gate can be exercised without a device. This is the rule that
     * keeps normal conversation away from the model: it has to be short, has to name something
     * Orbit can control, and must not open like a question.
     */
    public static boolean looksActionable(String raw) {
        String q = LanguageNormalizer.stripPoliteness(LanguageNormalizer.canonical(raw));
        if (q.isEmpty() || q.length() > MAX_LENGTH) return false;
        if (LanguageNormalizer.isConceptualQuestion(q)) return false;
        if (NOT_AN_INSTRUCTION.matcher(q).find()) return false;
        return ACTIONABLE_SUBJECT.matcher(q).find();
    }

    /** Whether this request should be offered to the action model before the provider. */
    public static boolean shouldTry(Context context, String prompt) {
        return available(context) && looksActionable(prompt);
    }

    // ---- the attempt ------------------------------------------------------------------------------

    /**
     * Runs one action-model attempt, then either answers or hands the request on.
     *
     * <p>{@code fallback} is run exactly once for every outcome that is not a validated action:
     * a refusal, malformed output, an unreachable component, a model failure, or the timeout. A
     * request never disappears into this path.
     */
    public static void handle(Context context, String prompt,
                              AssistantClient.Callback callback, Runnable fallback) {
        final Context app = context.getApplicationContext();
        final AtomicBoolean settled = new AtomicBoolean(false);
        final long startedAt = System.currentTimeMillis();

        final Runnable giveUp = () -> {
            if (settled.compareAndSet(false, true)) fallback.run();
        };

        // The model is small and the prompt is one sentence, so this should take well under a
        // second. The deadline exists for the case where it does not: a device command must never
        // be the slowest thing Orbit does.
        TIMER.schedule(() -> {
            if (!settled.get()) {
                DiagnosticStore.recordLocalAction(app, "provider", "", "timeout",
                        System.currentTimeMillis() - startedAt);
                OrbitLocalClient.cancelActionGeneration(app);
                giveUp.run();
            }
        }, TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);

        OrbitLocalClient.generateAction(app, buildPrompt(app, prompt),
                new OrbitLocalClient.StreamCallback() {
                    @Override public void onPartial(String cumulativeText) {
                        // Nothing. Structured output has no meaning until it is complete, and none
                        // of it is ever shown to the user.
                    }

                    @Override public void onDone(String fullText) {
                        if (settled.get()) return;
                        long took = System.currentTimeMillis() - startedAt;
                        LocalActionSchema.Validation validation =
                                LocalActionSchema.validate(fullText, resolver(app));
                        if (!validation.accepted() || refusedByOrbit(prompt, validation.action)) {
                            DiagnosticStore.recordLocalAction(app, "provider", "",
                                    validation.accepted() ? "refused" : validation.rejection, took);
                            giveUp.run();
                            return;
                        }
                        if (!settled.compareAndSet(false, true)) return;
                        DiagnosticStore.recordLocalAction(app, "local-action-model",
                                validation.category, "accepted", took);
                        List<AssistantReply.Action> actions = new ArrayList<>();
                        actions.add(validation.action);
                        callback.onSuccess(new AssistantReply(speak(validation.action), actions));
                    }

                    @Override public void onError(String message) {
                        DiagnosticStore.recordLocalAction(app, "provider", "", "model-error",
                                System.currentTimeMillis() - startedAt);
                        giveUp.run();
                    }
                });
    }

    /** One shared timer for every action attempt. Daemon, so it never holds the process open. */
    private static final java.util.concurrent.ScheduledExecutorService TIMER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "orbit-local-action-timeout");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * Orbit's own last word on a validated action.
     *
     * <p>{@link LocalActionSchema} decides whether an action is well-formed and safe. This decides
     * whether Orbit is willing to claim it did what was asked, and there is exactly one rule so
     * far: {@code SET_ALARM} carries an hour and a minute and nothing else, so an instruction that
     * names a day is something Orbit cannot represent. {@link LocalCommandRouter} has refused those
     * since alarms existed, and the semantic path must not quietly start accepting them and then
     * report success for an alarm set on the wrong day.
     */
    static boolean refusedByOrbit(String prompt, AssistantReply.Action action) {
        if (action == null) return true;
        if (!"SET_ALARM".equals(action.type)) return false;
        String q = LanguageNormalizer.canonical(prompt);
        return ALARM_NAMES_A_DAY.matcher(q).find();
    }

    // ---- what Orbit says --------------------------------------------------------------------------

    /**
     * The sentence Orbit says while the action runs.
     *
     * <p>Written by Orbit from the validated action, never by the model. What actually happened is
     * still reported afterwards by the executor's own result, so this claims an intention and the
     * action result claims the outcome.
     */
    static String speak(AssistantReply.Action action) {
        org.json.JSONObject p = action.params;
        switch (action.type) {
            case "FLASHLIGHT":
                return p.optBoolean("on", true)
                        ? "Turning on the flashlight." : "Turning off the flashlight.";
            case "SET_BRIGHTNESS":
                return "Setting brightness to " + p.optInt("percent", 50) + "%.";
            case "SET_VOLUME":
                return "Setting media volume to " + p.optInt("percent", 50) + "%.";
            case "SET_DND":
                return p.optBoolean("enabled", true)
                        ? "Turning on Do Not Disturb." : "Turning off Do Not Disturb.";
            case "SET_RINGER_MODE": {
                String mode = p.optString("mode", "normal");
                return "normal".equals(mode) ? "Turning the ringer back on."
                        : "vibrate".equals(mode) ? "Putting the phone on vibrate."
                        : "Silencing the phone.";
            }
            case "MEDIA_CONTROL": {
                switch (p.optString("command", "")) {
                    case "PAUSE": return "Pausing playback.";
                    case "PLAY": return "Resuming playback.";
                    case "NEXT": return "Skipping to the next track.";
                    case "PREVIOUS": return "Going back a track.";
                    default: return "Sending that to the player.";
                }
            }
            case "SET_TIMER": {
                int seconds = Math.max(1, p.optInt("seconds", 60));
                String label = p.optString("label", "");
                String spoken = "Setting a "
                        + RoutineActionCatalog.durationModifierLabel(seconds) + " timer";
                return label.isEmpty() || "Orbit timer".equals(label)
                        ? spoken + "." : spoken + " for " + label.toLowerCase(Locale.US) + ".";
            }
            case "SET_ALARM":
                return "Opening your Clock app with that alarm.";
            case "OPEN_APP":
                return "Opening " + p.optString("app", "that app") + ".";
            case "OPEN_SETTINGS":
                return "Opening Settings.";
            default:
                return "Working on it.";
        }
    }

    // ---- the prompt -------------------------------------------------------------------------------

    /**
     * The whole instruction the action model receives.
     *
     * <p>Three things and nothing else: the fixed schema, the device readings that let a relative
     * request ("a little dimmer") become an absolute one, and the user's sentence. There is no
     * conversation history, no screen content, no memory, and no attachment, which is both a
     * privacy property and the reason a 0.5B model can be reliable here at all.
     */
    static String buildPrompt(Context context, String userText) {
        StringBuilder p = new StringBuilder();
        p.append("You turn one phone instruction into one JSON object. Reply with JSON only.\n");
        p.append("Allowed actions:\n");
        p.append("{\"action\":\"FLASHLIGHT\",\"params\":{\"on\":true}}\n");
        p.append("{\"action\":\"SET_BRIGHTNESS\",\"params\":{\"percent\":0-100}}\n");
        p.append("{\"action\":\"SET_VOLUME\",\"params\":{\"percent\":0-100}}\n");
        p.append("{\"action\":\"SET_DND\",\"params\":{\"enabled\":true}}\n");
        p.append("{\"action\":\"SET_RINGER_MODE\",\"params\":{\"mode\":\"normal|vibrate|silent\"}}\n");
        p.append("{\"action\":\"MEDIA_CONTROL\",\"params\":{\"command\":\"PLAY|PAUSE|NEXT|PREVIOUS\"}}\n");
        p.append("{\"action\":\"SET_TIMER\",\"params\":{\"seconds\":1-86400,\"label\":\"short name\"}}\n");
        p.append("{\"action\":\"SET_ALARM\",\"params\":{\"hour\":0-23,\"minute\":0-59}}\n");
        p.append("{\"action\":\"OPEN_APP\",\"params\":{\"app\":\"app name\"}}\n");
        p.append("{\"action\":\"OPEN_SETTINGS\",\"params\":{}}\n");
        p.append("Anything else: {\"action\":\"NONE\"}\n");

        // Real readings, so "a bit quieter" resolves against the phone rather than against a guess.
        int brightness = DeviceActionExecutor.currentBrightnessPercent(context, -1);
        int volume = DeviceStatusReader.mediaVolumePercent(context);
        if (brightness >= 0) p.append("Current brightness: ").append(brightness).append("%\n");
        if (volume >= 0) p.append("Current media volume: ").append(volume).append("%\n");

        p.append("Examples:\n");
        p.append("Instruction: kill the torch\n{\"action\":\"FLASHLIGHT\",\"params\":{\"on\":false}}\n");
        p.append("Instruction: give me ten minutes for the pasta\n")
                .append("{\"action\":\"SET_TIMER\",\"params\":{\"seconds\":600,\"label\":\"Pasta\"}}\n");
        p.append("Instruction: pull up Spotify\n")
                .append("{\"action\":\"OPEN_APP\",\"params\":{\"app\":\"Spotify\"}}\n");
        p.append("Instruction: what is the capital of France\n{\"action\":\"NONE\"}\n");
        p.append("Instruction: ").append(limit(userText, 200)).append('\n');
        return p.toString();
    }

    private static String limit(String s, int max) {
        if (s == null) return "";
        String value = s.trim().replace('\n', ' ');
        return value.length() <= max ? value : value.substring(0, max);
    }

    // ---- app resolution ---------------------------------------------------------------------------

    /**
     * The gate between a generated app name and anything being launched.
     *
     * <p>Matches against the labels of apps that are actually installed and actually launchable, and
     * returns that app's own label. A generated package name, a component name, or an app that is
     * not on this phone resolves to nothing and the action is rejected, so a string from a model can
     * never become something Orbit starts.
     */
    static LocalActionSchema.AppResolver resolver(Context context) {
        return wanted -> {
            if (wanted == null) return null;
            String want = wanted.trim().toLowerCase(Locale.US);
            if (want.isEmpty()) return null;
            try {
                PackageManager pm = context.getPackageManager();
                Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> infos = pm.queryIntentActivities(launcher, 0);
                String partial = null;
                for (ResolveInfo info : infos) {
                    CharSequence raw = info.loadLabel(pm);
                    String label = raw == null ? "" : raw.toString().trim();
                    if (label.isEmpty()) continue;
                    String lower = label.toLowerCase(Locale.US);
                    if (lower.equals(want)) return label;
                    if (partial == null && (lower.contains(want) || want.contains(lower))) {
                        partial = label;
                    }
                }
                return partial;
            } catch (Exception e) {
                return null;
            }
        };
    }
}
