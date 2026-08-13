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

    private OrbitMarkdown() {}

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
