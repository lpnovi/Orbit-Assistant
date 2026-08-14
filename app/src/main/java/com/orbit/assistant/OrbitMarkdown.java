package com.orbit.assistant;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small native Markdown renderer for Orbit-owned presentation surfaces. */
public final class OrbitMarkdown {
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern BULLET = Pattern.compile("^[-*+]\\s+(.+)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\d+[.)])\\s+(.+)$");
    private static final Pattern INLINE = Pattern.compile(
            "\\[([^\\]]+)]\\((https?://[^\\s)]+)\\)" +
            "|\\*\\*([^*\\n]+)\\*\\*" +
            "|__([^_\\n]+)__" +
            "|(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)" +
            "|(?<!_)_([^_\\n]+)_(?!_)" +
            "|~~([^~\\n]+)~~" +
            "|`([^`\\n]+)`");

    private static final Pattern HRULE = Pattern.compile("^([-*_])\\1{2,}$");
    /**
     * Inline markup for compact previews. Deliberately stricter than {@link #INLINE}: emphasis
     * must not begin or end on whitespace and underscore emphasis must sit on word boundaries, so
     * ordinary prose such as "2 * 4 = 8 * 2" and identifiers such as "some_var_name" survive
     * untouched rather than being read as formatting.
     */
    private static final Pattern PREVIEW_INLINE = Pattern.compile(
            "\\[([^\\]]+)]\\((https?://[^\\s)]+)\\)" +
            "|\\[\\s*]\\((https?://[^\\s)]+)\\)" +
            "|\\*\\*(?!\\s)([^*\\n]*[^*\\s])\\*\\*" +
            "|(?<![\\w_])__(?!\\s)([^_\\n]*[^_\\s])__(?![\\w_])" +
            "|(?<![\\w*])\\*(?!\\s)([^*\\n]*[^*\\s])\\*(?![\\w*])" +
            "|(?<![\\w_])_(?!\\s)([^_\\n]*[^_\\s])_(?![\\w_])" +
            "|~~(?!\\s)([^~\\n]*[^~\\s])~~" +
            "|`([^`\\n]+)`");
    private static final int PREVIEW_INLINE_PASSES = 3;

    private OrbitMarkdown() {}

    /**
     * Flattens a stored message into one clean line for compact previews such as the Chats list.
     *
     * <p>Presentation only: the message itself is never altered, and opening the conversation
     * still renders the original Markdown in full. Headings, list items, quoted lines, and
     * paragraphs become readable segments joined by a separator; emphasis, links, and inline code
     * give up their syntax and keep their words; fenced code is left out entirely, falling back to
     * a short label only when a message is nothing but code. Normalization happens before the
     * length limit so the visible characters are spent on words rather than on syntax.
     */
    public static String toPreviewText(String source, int maxChars) {
        if (source == null || source.trim().isEmpty()) return "";
        String[] lines = source.replace("\r", "").split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean codeBlock = false;
        boolean sawCode = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("```")) {
                codeBlock = !codeBlock;
                sawCode = true;
                continue;
            }
            if (codeBlock) {
                sawCode = true;
                continue;
            }
            if (line.isEmpty() || HRULE.matcher(line).matches()) continue;

            while (line.startsWith(">")) line = line.substring(1).trim();
            if (line.isEmpty()) continue;

            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                line = heading.group(2);
            } else {
                Matcher bullet = BULLET.matcher(line);
                Matcher numbered = NUMBERED.matcher(line);
                if (bullet.matches()) line = bullet.group(1);
                else if (numbered.matches()) line = numbered.group(2);
            }

            line = flattenTableRow(line);
            if (line.isEmpty()) continue;

            String cleaned = stripPreviewInline(line).trim();
            if (cleaned.isEmpty()) continue;
            if (out.length() > 0) out.append(" · ");
            out.append(cleaned);
            // Plenty of material for any sensible limit; no need to walk a long response.
            if (maxChars > 0 && out.length() > maxChars * 2) break;
        }

        String text = out.toString().replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) return sawCode ? "Code snippet" : "";
        if (maxChars > 0 && text.length() > maxChars) {
            text = text.substring(0, Math.max(0, maxChars - 1)).trim() + "…";
        }
        return text;
    }

    /** Turns a table row into its cell text, and drops the dashed separator rows entirely. */
    private static String flattenTableRow(String line) {
        if (!line.startsWith("|")) return line;
        String body = line.substring(1);
        if (body.endsWith("|")) body = body.substring(0, body.length() - 1);
        String[] cells = body.split("\\|", -1);
        StringBuilder joined = new StringBuilder();
        for (String cell : cells) {
            String value = cell.trim();
            if (value.isEmpty() || value.matches("^:?-{1,}:?$")) continue;
            if (joined.length() > 0) joined.append(' ');
            joined.append(value);
        }
        return joined.toString();
    }

    /** Replaces inline markup with the words it was decorating, including one level of nesting. */
    private static String stripPreviewInline(String line) {
        String current = line;
        for (int pass = 0; pass < PREVIEW_INLINE_PASSES; pass++) {
            Matcher matcher = PREVIEW_INLINE.matcher(current);
            if (!matcher.find()) return current;
            matcher.reset();
            StringBuilder out = new StringBuilder();
            int cursor = 0;
            while (matcher.find()) {
                out.append(current, cursor, matcher.start());
                String replacement = "";
                for (int group = 1; group <= matcher.groupCount(); group++) {
                    // The first populated group is the visible text; a link's URL only wins when
                    // the link carried no text of its own.
                    if (matcher.group(group) != null) {
                        replacement = matcher.group(group);
                        break;
                    }
                }
                out.append(replacement);
                cursor = matcher.end();
            }
            out.append(current, cursor, current.length());
            current = out.toString();
        }
        return current;
    }

    /** Headings, emphasis, lists, inline/code blocks, and safe HTTP(S) links. */
    public static CharSequence render(Context context, String source) {
        SpannableStringBuilder output = new SpannableStringBuilder();
        if (source == null || source.trim().isEmpty()) return output;
        String[] lines = source.replace("\r", "").split("\n", -1);
        boolean codeBlock = false;
        for (String raw : lines) {
            String trimmed = raw.trim();
            if (trimmed.startsWith("```")) {
                codeBlock = !codeBlock;
                continue;
            }
            int start = output.length();
            if (codeBlock) {
                output.append(raw);
                applyCode(context, output, start, output.length());
            } else {
                Matcher heading = HEADING.matcher(trimmed);
                Matcher bullet = BULLET.matcher(trimmed);
                Matcher numbered = NUMBERED.matcher(trimmed);
                if (heading.matches()) {
                    appendInline(context, output, heading.group(2));
                    if (output.length() > start) {
                        output.setSpan(new StyleSpan(Typeface.BOLD), start, output.length(),
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        float scale = heading.group(1).length() == 1 ? 1.28f :
                                heading.group(1).length() == 2 ? 1.18f : 1.10f;
                        output.setSpan(new RelativeSizeSpan(scale), start, output.length(),
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                } else if (bullet.matches()) {
                    output.append("•  ");
                    appendInline(context, output, bullet.group(1));
                } else if (numbered.matches()) {
                    output.append(numbered.group(1)).append("  ");
                    appendInline(context, output, numbered.group(2));
                } else if (!trimmed.matches("^[-*_]{3,}$")) {
                    appendInline(context, output, trimmed);
                }
            }
            output.append('\n');
        }
        while (output.length() > 0 && output.charAt(output.length() - 1) == '\n') {
            output.delete(output.length() - 1, output.length());
        }
        return output;
    }

    /** Inline-only Markdown for native rich-message blocks. */
    public static CharSequence renderInline(Context context, String source, int foreground) {
        SpannableStringBuilder output = new SpannableStringBuilder();
        appendInline(context, output, source == null ? "" : source);
        return output;
    }

    /** Lightweight readable text for TTS; stored/copied Markdown remains unchanged. */
    public static String toSpeechText(String source) {
        if (source == null) return "";
        return source.replaceAll("(?m)^```[^\\n]*$", "")
                .replaceAll("!\\[([^]]*)]\\(https?://[^\\s)]+\\)", "$1")
                .replaceAll("\\[([^]]+)]\\(https?://[^\\s)]+\\)", "$1")
                .replaceAll("(?m)^#{1,6}\\s+", "")
                .replaceAll("(?m)^>\\s?", "")
                .replaceAll("(?m)^[-+*]\\s+", "")
                .replace("**", "").replace("__", "")
                .replace("~~", "").replace("`", "")
                .replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static void appendInline(Context context, SpannableStringBuilder output, String line) {
        Matcher matcher = INLINE.matcher(line);
        int cursor = 0;
        while (matcher.find()) {
            output.append(line, cursor, matcher.start());
            int start = output.length();
            if (matcher.group(1) != null) {
                output.append(matcher.group(1));
                output.setSpan(new URLSpan(matcher.group(2)), start, output.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (matcher.group(3) != null || matcher.group(4) != null) {
                output.append(matcher.group(3) != null ? matcher.group(3) : matcher.group(4));
                output.setSpan(new StyleSpan(Typeface.BOLD), start, output.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (matcher.group(5) != null || matcher.group(6) != null) {
                output.append(matcher.group(5) != null ? matcher.group(5) : matcher.group(6));
                output.setSpan(new StyleSpan(Typeface.ITALIC), start, output.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (matcher.group(7) != null) {
                output.append(matcher.group(7));
                output.setSpan(new StrikethroughSpan(), start, output.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                output.append(matcher.group(8));
                applyCode(context, output, start, output.length());
            }
            cursor = matcher.end();
        }
        output.append(line, cursor, line.length());
    }

    private static void applyCode(Context context, SpannableStringBuilder output,
                                  int start, int end) {
        if (end <= start) return;
        output.setSpan(new TypefaceSpan("monospace"), start, end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        output.setSpan(new ForegroundColorSpan(UiKit.TEXT), start, end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        output.setSpan(new BackgroundColorSpan(UiKit.SURFACE_2), start, end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
