package com.orbit.assistant;

import java.util.Locale;

/**
 * One short, safe statement about what Orbit is doing while it prepares an answer.
 *
 * <p>Two kinds of thing can produce one, and the difference is the whole reason this type exists
 * rather than a bare string. A {@link Source#PROVIDER_SUMMARY} update is text the provider itself
 * published <em>for the user to read</em>: a reasoning summary, which is a deliberately produced
 * description of the work, not the work itself. A {@link Source#ORBIT_PROGRESS} update is Orbit
 * describing its own execution — a request it has actually sent, a search it has actually seen
 * start, a model that is actually running on this phone.
 *
 * <p><b>What must never become one of these.</b> Raw hidden chain-of-thought, internal reasoning
 * tokens, encrypted reasoning content, scratchpad text, system or developer instructions, tool
 * payloads, and model debugging traces are all out of bounds, and being reachable in some response
 * protocol is not permission to render them. Only fields a provider defines as user-facing
 * summaries or progress may be turned into a {@code PROVIDER_SUMMARY}; everything else Orbit shows
 * is a fact about Orbit's own execution that Orbit already knew.
 *
 * <p>Every stage here has a real producer. None is invented to make the animation look busier, and
 * an Orbit-progress stage is only emitted at a moment when the thing it names is genuinely
 * happening. Where Orbit knows nothing more specific than "a request is running", the honest
 * answer is {@link Stage#WORKING} and the orbital animation carries the rest.
 *
 * <p>Instances are immutable, ephemeral, and never persisted: they exist only to be shown while a
 * request is in flight and are dropped the moment it becomes terminal.
 */
public final class ThinkingUpdate {

    /** Who produced this update, which is also what it is allowed to be. */
    public enum Source {
        /** The provider published it for display. Its wording is the provider's, not Orbit's. */
        PROVIDER_SUMMARY,
        /** Orbit describing its own execution in Orbit's words. Never a claim about the model. */
        ORBIT_PROGRESS
    }

    /**
     * What is happening. Each constant names a specific, observable event; the default text is
     * Orbit's own wording for it, and is empty for the stages whose text comes from elsewhere.
     */
    public enum Stage {
        /** A request is running and Orbit knows nothing more specific yet. */
        WORKING(Source.ORBIT_PROGRESS, "Thinking…"),
        /** Screen text or a screenshot was genuinely attached to this request. */
        SCREEN_CONTEXT(Source.ORBIT_PROGRESS, "Using your screen for context…"),
        /** A cloud request has been sent at a known reasoning level. Text names the model. */
        MODEL_REASONING(Source.ORBIT_PROGRESS, ""),
        /** The provider's hosted search tool reported that it started a search. */
        WEB_SEARCH(Source.ORBIT_PROGRESS, "Searching the web…"),
        /** That search reported completion, so results are what the model is working from. */
        WEB_RESULTS(Source.ORBIT_PROGRESS, "Reading the search results…"),
        /** Generation is running on this phone. */
        LOCAL_INFERENCE(Source.ORBIT_PROGRESS, "Running on your phone…"),
        /** Text the provider published as a user-facing reasoning summary. */
        PROVIDER_REASONING_SUMMARY(Source.PROVIDER_SUMMARY, "");

        private final Source source;
        private final String defaultText;

        Stage(Source source, String defaultText) {
            this.source = source;
            this.defaultText = defaultText;
        }

        public Source source() { return source; }

        /** Orbit's own wording, or "" for a stage whose text always comes from its producer. */
        public String defaultText() { return defaultText; }

        /** The short token used in diagnostics. Never carries any update's text. */
        public String token() { return name().toLowerCase(Locale.US).replace('_', '-'); }
    }

    /**
     * How much text may reach the UI. Long enough for a real summary sentence, short enough that
     * two lines of a compact overlay bubble hold it whatever a provider sends.
     */
    static final int MAX_TEXT_CHARS = 120;

    public final Stage stage;
    public final String text;

    private ThinkingUpdate(Stage stage, String text) {
        this.stage = stage;
        this.text = text;
    }

    public Source source() { return stage.source(); }

    /** True for text the provider published, false for Orbit describing its own execution. */
    public boolean fromProvider() { return stage.source() == Source.PROVIDER_SUMMARY; }

    /**
     * An Orbit-progress update in Orbit's own wording.
     *
     * @return null for a stage that has no wording of its own, so nothing empty can be shown.
     */
    public static ThinkingUpdate progress(Stage stage) {
        if (stage == null) return null;
        return progress(stage, stage.defaultText());
    }

    /** An Orbit-progress update with wording the caller supplies, e.g. a resolved model name. */
    public static ThinkingUpdate progress(Stage stage, String text) {
        if (stage == null || stage.source() != Source.ORBIT_PROGRESS) return null;
        String clean = sanitize(text);
        return clean.isEmpty() ? null : new ThinkingUpdate(stage, clean);
    }

    /**
     * A provider-published reasoning summary, sanitized for display.
     *
     * <p>Deliberately the only way to construct a {@code PROVIDER_SUMMARY}: there is exactly one
     * door, so a caller cannot accidentally label Orbit's own text as the provider's, or hand
     * unsanitized provider text to the UI.
     *
     * @return null when nothing displayable survives sanitizing.
     */
    public static ThinkingUpdate providerSummary(String text) {
        String clean = sanitize(text);
        return clean.isEmpty() ? null : new ThinkingUpdate(Stage.PROVIDER_REASONING_SUMMARY, clean);
    }

    /**
     * Reduces arbitrary provider text to one short line of plain display text.
     *
     * <p>Treated as data throughout. Control characters and line breaks are removed rather than
     * rendered, runs of whitespace collapse, the lightweight markdown a summary may arrive in is
     * stripped to its words, and the result is cut at a word boundary. Nothing here interprets the
     * text or lets it become markup: the status line is a label, not a document.
     */
    static String sanitize(String raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder(Math.min(raw.length(), 512));
        boolean pendingSpace = false;
        for (int i = 0; i < raw.length() && out.length() < 512; i++) {
            char c = raw.charAt(i);
            // isWhitespace misses the non-breaking and zero-width spaces, which would otherwise
            // survive as invisible padding and defeat the collapse below.
            if (Character.isWhitespace(c) || c == '\u00A0' || c == '\u202F'
                    || c == '\u2007' || c == '\uFEFF') {
                pendingSpace = out.length() > 0;
                continue;
            }
            // Control and formatting characters never reach a TextView, including the
            // bidirectional overrides that could otherwise reorder what the user reads.
            if (Character.isISOControl(c)) { pendingSpace = out.length() > 0; continue; }
            int type = Character.getType(c);
            // Surrogates are deliberately kept: each is half of an ordinary non-BMP character,
            // and dropping them would corrupt legitimate text rather than clean it.
            if (type == Character.FORMAT || type == Character.UNASSIGNED) continue;
            if (pendingSpace) { out.append(' '); pendingSpace = false; }
            out.append(c);
        }
        String text = stripLightMarkdown(out.toString()).trim();
        text = text.replace("—", "-");
        if (text.isEmpty()) return "";
        return truncate(text, MAX_TEXT_CHARS);
    }

    /**
     * Removes the emphasis, heading, and list marks a summary may carry, leaving the words.
     *
     * <p>Orbit renders this as plain text, so a stray {@code **} would simply be visible. Nothing
     * is converted into styling: markdown is discarded, not honoured.
     */
    private static String stripLightMarkdown(String value) {
        String text = value;
        // Leading heading, quote, and list marks.
        int start = 0;
        while (start < text.length()) {
            char c = text.charAt(start);
            if (c == '#' || c == '>' || c == '*' || c == '-' || c == '+' || c == ' ') start++;
            else break;
        }
        text = text.substring(start);
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '*' || c == '`' || c == '_' || c == '#') continue;
            out.append(c);
        }
        return out.toString();
    }

    /** Cuts to a word boundary and marks the cut, so nothing is silently half-shown. */
    private static String truncate(String value, int max) {
        if (value.length() <= max) return value;
        int cut = value.lastIndexOf(' ', max - 1);
        if (cut < max / 2) cut = max - 1;
        return value.substring(0, cut).trim() + "…";
    }

    /**
     * Orbit's own wording for "a cloud model is reasoning", named where Orbit genuinely knows
     * which model it sent the request to.
     *
     * @return null when the model id is not one Orbit has a name for, so nothing is guessed.
     */
    public static ThinkingUpdate modelReasoning(String modelId) {
        String name = modelDisplayName(modelId);
        return name.isEmpty() ? null : progress(Stage.MODEL_REASONING, "Reasoning with " + name + "…");
    }

    /** Luna, Terra, or Sol, or "" for a model Orbit has no name for. Never invents one. */
    static String modelDisplayName(String modelId) {
        String id = modelId == null ? "" : modelId.toLowerCase(Locale.US);
        if (id.contains("luna")) return "Luna";
        if (id.contains("terra")) return "Terra";
        if (id.contains("sol")) return "Sol";
        return "";
    }

    @Override public String toString() {
        // Deliberately without the text: this type ends up in log-shaped contexts, and an
        // update's wording is never something Orbit records.
        return "ThinkingUpdate{" + stage.token() + "}";
    }
}
