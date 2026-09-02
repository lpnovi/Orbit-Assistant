package com.orbit.assistant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An assistant response cut into the blocks Orbit draws it as, without drawing any of them.
 *
 * <p>This exists because Orbit had two different ideas of what a response was. While one was
 * arriving it was a single {@code TextView} holding raw Markdown, so the user watched
 * {@code ## Heading} and {@code - item} and triple backticks scroll past as literal characters;
 * when it finished, that view was thrown away and a completely separate rich tree was built in its
 * place. Every answer therefore ended with a visible raw-to-rich jump, and the nicer of the two
 * presentations was the one the user spent the least time looking at.
 *
 * <p>The fix is not a second renderer. It is to name the step both paths were missing: deciding
 * <em>what the blocks are</em>. That decision is pure text work — no Context, no View, no
 * measurement — so it lives here, is exercised directly by tests, and is used by exactly one
 * block builder in {@link OrbitRichResponseRenderer}. The completed path and the streaming path
 * cannot drift apart in what they think a response contains, because they ask the same question of
 * the same code.
 *
 * <p>The streaming half of the job is knowing when <em>not</em> to commit. A response arrives a
 * fragment at a time, so at any instant the tail of it is half-written: a fence with no closing
 * fence, a row of pipes that may or may not become a table, a {@code **} whose partner has not been
 * generated yet. Guessing early looks worse than not guessing at all — a paragraph that turns into
 * a table and back is more distracting than a paragraph that waits. So a construct is only
 * recognised once the text actually proves it, and the last block is allowed to be
 * {@link Block#complete false} until it does.
 */
public final class ResponseBlocks {

    /** What one block of a response is. */
    public enum Kind { PARAGRAPH, HEADING, LIST, QUOTE, CODE, TABLE, RULE, IMAGE }

    /**
     * Markdown Orbit recognises, in the exact forms the renderer already accepted.
     *
     * <p>Deliberately the same expressions rather than new ones. A second, subtly different idea of
     * what counts as a heading would show up as a response that formats one way while it streams
     * and another way once it lands, which is the bug this class exists to remove.
     */
    static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    static final Pattern LIST = Pattern.compile("^(\\s*)([-+*]|\\d+[.)])\\s+(.+)$");
    static final Pattern IMAGE = Pattern.compile("^!\\[([^]]*)]\\(([^\\s)]+)\\)\\s*$");
    static final Pattern TABLE_DIVIDER = Pattern.compile(
            "^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$");
    static final Pattern RULE = Pattern.compile("^[-*_]{3,}$");

    /**
     * A task item's checkbox, matched against the body of a list item and nowhere else.
     *
     * <p>Deliberately anchored, and deliberately only ever applied to {@link #LIST} group 3. The
     * risk with this syntax is false positives: a sentence such as "The token [x] means something"
     * contains the same three characters, and promoting that into a checkbox would rewrite the
     * assistant's prose. Requiring a list marker first means the construct has to have been
     * written as a list item to be read as one.
     *
     * <p>The body is optional so that {@code - [x]} is already a task item the moment it is
     * written, before its text arrives. That is what stops a streaming item flickering from bullet
     * to checkbox once the words catch up.
     */
    static final Pattern TASK = Pattern.compile("^\\[([ xX])](?:\\s+(.*))?$");

    /** List markers that may carry a checkbox. A numbered item is a step, not a task. */
    private static final String TASK_MARKERS = "-+*";

    /** One task item: whether it is ticked, and the Markdown that follows the box. */
    public static final class Task {
        public final boolean checked;
        public final String text;

        Task(boolean checked, String text) {
            this.checked = checked;
            this.text = text == null ? "" : text;
        }
    }

    /**
     * The task item a list item is, or null if it is an ordinary one.
     *
     * @param marker the list marker itself, from {@link #LIST} group 2
     * @param body   everything after it, from {@link #LIST} group 3
     */
    public static Task task(String marker, String body) {
        if (marker == null || body == null) return null;
        if (marker.length() != 1 || TASK_MARKERS.indexOf(marker.charAt(0)) < 0) return null;
        Matcher box = TASK.matcher(body);
        if (!box.matches()) return null;
        char state = box.group(1).charAt(0);
        return new Task(state == 'x' || state == 'X', box.group(2) == null ? "" : box.group(2));
    }

    /** A list item's body with its checkbox removed, for surfaces that cannot draw one. */
    static String withoutTaskMarker(String body) {
        Task task = task("-", body);
        return task == null ? body : task.text;
    }

    /** One block, and everything the builder needs to draw it. */
    public static final class Block {
        public final Kind kind;
        /**
         * The exact source this block was cut from, already normalised for display.
         *
         * <p>For a code block this is the code itself, with the fences removed: once Orbit knows a
         * fence opened, showing the backticks to the user would be showing them the syntax of a
         * decision Orbit has already made.
         */
        public final String source;
        /** For {@link Kind#CODE}, the declared language, or empty. */
        public final String language;
        /**
         * False only for the last block of a response still arriving.
         *
         * <p>An open code fence, or a paragraph the model is still writing. It is never a reason to
         * hide content — an incomplete block still shows everything that has arrived — only a
         * reason to keep the block open for more, and to hold back a dangling delimiter whose
         * partner has not been written yet.
         */
        public final boolean complete;

        Block(Kind kind, String source, String language, boolean complete) {
            this.kind = kind;
            this.source = source == null ? "" : source;
            this.language = language == null ? "" : language;
            this.complete = complete;
        }

        /**
         * The text this block actually displays.
         *
         * <p>Only blocks whose text is read as Markdown have a dangling delimiter withheld. Code is
         * deliberately excluded: a trailing backtick inside a snippet is part of the program, and
         * quietly deleting it to make the stream look tidier would hand the user code that does not
         * compile. A rule and an image have no inline markup to hold back either.
         */
        public String displaySource() {
            return complete || !rendersInlineMarkup() ? source : activeText(source);
        }

        private boolean rendersInlineMarkup() {
            return kind != Kind.CODE && kind != Kind.RULE && kind != Kind.IMAGE;
        }

        /**
         * What makes this block the same block as one already on screen.
         *
         * <p>The progressive view diffs on exactly this. Two blocks with the same signature are
         * interchangeable, so the one already drawn is left completely alone - which is what keeps
         * a code block's Copy control, a link's clickability, a table's horizontal scroll position
         * and a screen reader's focus alive while the rest of the answer is still arriving.
         *
         * <p>Built from what the block <em>displays</em> rather than from its raw state, because
         * those are not the same question. A settled paragraph stops being the last block the
         * moment a list starts below it, which flips {@link #complete} without changing a single
         * character on screen; keying on the flag would rebuild that paragraph - and drop a screen
         * reader out of it - for no visible reason at all. Completeness is part of the identity
         * only where it genuinely changes the block, which is code, whose Copy control waits for
         * the closing fence.
         */
        public String signature() {
            return kind.name() + "|" + language + "|"
                    + (kind == Kind.CODE ? complete + "|" : "") + displaySource();
        }

        @Override public String toString() { return kind + "(" + source + ")"; }
    }

    private ResponseBlocks() {}

    /** One plain paragraph, for the fallback path when parsing could not produce anything. */
    public static Block paragraph(String source, boolean complete) {
        return new Block(Kind.PARAGRAPH, source, "", complete);
    }

    /** The blocks of a finished response. */
    public static List<Block> parse(String source) {
        return parse(source, false);
    }

    /**
     * The blocks of a response.
     *
     * @param streaming true while the text is still arriving, which allows the final block to be
     *                  an open construct rather than forcing a decision the text has not earned.
     */
    public static List<Block> parse(String source, boolean streaming) {
        List<Block> blocks = new ArrayList<>();
        if (source == null) return blocks;
        String[] lines = source.replace("\r", "").split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.isEmpty()) { i++; continue; }

            // ---- code ------------------------------------------------------------------------
            if (trimmed.startsWith("```")) {
                String language = trimmed.length() > 3 ? trimmed.substring(3).trim() : "";
                StringBuilder code = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < lines.length) {
                    if (lines[i].trim().startsWith("```")) { closed = true; i++; break; }
                    if (code.length() > 0) code.append('\n');
                    code.append(lines[i++]);
                }
                // An unclosed fence is a code block that is still being written, not a paragraph
                // that happens to start with backticks. Orbit already knows enough to draw the
                // surface, so it draws it and keeps filling it.
                blocks.add(new Block(Kind.CODE, code.toString(), language, closed || !streaming));
                continue;
            }

            // ---- table -----------------------------------------------------------------------
            // A line full of pipes is not a table until the divider row proves it is. Promoting on
            // the first pipe would reshape ordinary prose containing "a | b" into a one-cell table
            // and then tear it down again a fragment later.
            if (i + 1 < lines.length && line.contains("|")
                    && TABLE_DIVIDER.matcher(lines[i + 1]).matches()) {
                StringBuilder table = new StringBuilder(line);
                table.append('\n').append(lines[i + 1]);
                i += 2;
                while (i < lines.length && lines[i].contains("|") && !lines[i].trim().isEmpty()) {
                    table.append('\n').append(lines[i++]);
                }
                boolean complete = !streaming || i < lines.length;
                blocks.add(new Block(Kind.TABLE, table.toString(), "", complete));
                continue;
            }

            // ---- image -----------------------------------------------------------------------
            // The pattern demands the whole construct, so a half-written URL cannot match and no
            // fetch can be started from one. Orbit's image safety rules are untouched.
            if (IMAGE.matcher(trimmed).matches()) {
                blocks.add(new Block(Kind.IMAGE, trimmed, "", true));
                i++;
                continue;
            }

            // ---- heading ---------------------------------------------------------------------
            if (HEADING.matcher(trimmed).matches()) {
                boolean last = isLastContentLine(lines, i);
                blocks.add(new Block(Kind.HEADING, trimmed, "", !streaming || !last));
                i++;
                continue;
            }

            // ---- horizontal rule -------------------------------------------------------------
            // Only when unambiguous. While streaming, a trailing "--" could still be growing into
            // a rule or into something else entirely, so a rule at the very end waits one more
            // fragment rather than flickering in and out.
            if (RULE.matcher(trimmed).matches()) {
                if (streaming && isLastContentLine(lines, i)) {
                    blocks.add(new Block(Kind.PARAGRAPH, trimmed, "", false));
                } else {
                    blocks.add(new Block(Kind.RULE, trimmed, "", true));
                }
                i++;
                continue;
            }

            // ---- quote -----------------------------------------------------------------------
            if (trimmed.startsWith(">")) {
                StringBuilder quote = new StringBuilder();
                int start = i;
                while (i < lines.length && lines[i].trim().startsWith(">")) {
                    if (quote.length() > 0) quote.append('\n');
                    quote.append(lines[i++]);
                }
                boolean complete = !streaming || i < lines.length || start == i;
                blocks.add(new Block(Kind.QUOTE, quote.toString(), "", complete));
                continue;
            }

            // ---- list ------------------------------------------------------------------------
            if (LIST.matcher(line).matches()) {
                StringBuilder list = new StringBuilder();
                while (i < lines.length && LIST.matcher(lines[i]).matches()) {
                    if (list.length() > 0) list.append('\n');
                    list.append(lines[i++]);
                }
                boolean complete = !streaming || i < lines.length;
                blocks.add(new Block(Kind.LIST, list.toString(), "", complete));
                continue;
            }

            // ---- paragraph -------------------------------------------------------------------
            StringBuilder paragraph = new StringBuilder(trimmed);
            i++;
            while (i < lines.length && !lines[i].trim().isEmpty() && !startsBlock(lines, i)) {
                paragraph.append('\n').append(lines[i].trim());
                i++;
            }
            boolean complete = !streaming || i < lines.length;
            blocks.add(new Block(Kind.PARAGRAPH, paragraph.toString(), "", complete));
        }
        return blocks;
    }

    /** True when nothing but blank lines follows this one. */
    private static boolean isLastContentLine(String[] lines, int index) {
        for (int i = index + 1; i < lines.length; i++) {
            if (!lines[i].trim().isEmpty()) return false;
        }
        return true;
    }

    /** Whether this line begins a construct, and therefore ends the paragraph above it. */
    static boolean startsBlock(String[] lines, int i) {
        String value = lines[i].trim();
        return value.startsWith("```") || value.startsWith(">")
                || HEADING.matcher(value).matches() || LIST.matcher(lines[i]).matches()
                || IMAGE.matcher(value).matches() || RULE.matcher(value).matches()
                || (i + 1 < lines.length && lines[i].contains("|")
                    && TABLE_DIVIDER.matcher(lines[i + 1]).matches());
    }

    /**
     * Text as it should be shown for a block that is still being written.
     *
     * <p>One narrow job: a delimiter whose partner has not arrived is withheld, and nothing else
     * changes. While a model writes "This is **important", the two asterisks are syntax for a
     * decision it has not finished making, and showing them is showing the user the machinery.
     * A fragment later they mean bold and the word formats.
     *
     * <p>Only the trailing run is touched, and only the characters of the delimiter itself. Content
     * is never hidden waiting for a closing marker: "This is **impor" still reads "This is impor",
     * so the answer never appears to stall or lose words.
     */
    public static String activeText(String source) {
        if (source == null || source.isEmpty()) return "";
        String text = source;
        // Longest first, so the three asterisks of an opening "***" are removed together rather
        // than leaving a shorter run behind that would immediately read as plain bold instead.
        // Each pass only ever considers runs of exactly its own length, so a completed "**bold**"
        // is invisible to the "*" pass and a half-written "***bold ital" is invisible to "**".
        for (String delimiter : new String[]{"~~", "***", "___", "**", "__", "`", "*", "_"}) {
            text = withoutDanglingDelimiter(text, delimiter);
        }
        return text;
    }

    /**
     * Removes one unmatched delimiter, and only when it is genuinely markup.
     *
     * <p>Two things have to be true. It must be unmatched — an even number of them means the text
     * already contains finished formatting and removing one would delete it. And it must actually
     * be a delimiter rather than a character in the prose: the risk here is real, because "2 * 4 =
     * 8" contains a lone asterisk and "some_var_name" contains underscores, and silently deleting
     * those would corrupt the answer to make the streaming look tidier.
     *
     * <p>So it is removed only when it ends the text — nothing has been written after it yet, so it
     * cannot be anything but a marker mid-thought — or when it sits where an opening delimiter sits:
     * after a space or the start of the line, and immediately before a non-space.
     */
    private static String withoutDanglingDelimiter(String text, String delimiter) {
        List<Integer> found = standaloneOccurrences(text, delimiter);
        if (found.isEmpty() || found.size() % 2 == 0) return text;
        int at = found.get(found.size() - 1);
        int after = at + delimiter.length();
        boolean endsText = after >= text.length();
        boolean opensEmphasis = !endsText
                && !Character.isWhitespace(text.charAt(after))
                && (at == 0 || Character.isWhitespace(text.charAt(at - 1)));
        if (!endsText && !opensEmphasis) return text;
        return text.substring(0, at) + text.substring(after);
    }

    /**
     * Where a delimiter appears in its own right.
     *
     * <p>A single asterisk inside "**" is not a single-asterisk delimiter, and counting it as one
     * would make every completed bold run look unmatched. Occurrences that are part of a longer run
     * of the same character are therefore skipped entirely.
     */
    private static List<Integer> standaloneOccurrences(String text, String delimiter) {
        List<Integer> found = new ArrayList<>();
        char marker = delimiter.charAt(0);
        int width = delimiter.length();
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) != marker) { i++; continue; }
            int run = 1;
            while (i + run < text.length() && text.charAt(i + run) == marker) run++;
            if (run == width) found.add(i);
            i += run;
        }
        return found;
    }

    /** The signatures of a block list, for diffing one render against the next. */
    public static List<String> signatures(List<Block> blocks) {
        List<String> out = new ArrayList<>();
        if (blocks == null) return out;
        for (Block block : blocks) out.add(block.signature());
        return Collections.unmodifiableList(out);
    }
}
