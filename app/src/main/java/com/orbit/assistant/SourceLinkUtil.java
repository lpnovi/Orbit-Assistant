package com.orbit.assistant;

import android.net.Uri;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small parser for source URLs emitted by hosted web-search answers. */
public final class SourceLinkUtil {
    private static final Pattern MARKDOWN = Pattern.compile("\\[([^\\]]{1,100})\\]\\((https?://[^\\s)]+)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAREN_LINK = Pattern.compile("\\(([^()]{1,100})\\)\\((https?://[^\\s)]+)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SOURCE_LINE = Pattern.compile("(?im)^\\s*(?:source|read more|learn more)\\s*:\\s*(https?://\\S+)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RAW_URL = Pattern.compile("https?://[^\\s<>]+", Pattern.CASE_INSENSITIVE);

    /**
     * A final fenced block holding nothing but one source marker, e.g.
     * <pre>```text\nSource: https://...\n```</pre> at the very end of an answer. Group 1 is the
     * language label, group 2 the marker line's URL. Deliberately narrow: the fence must close the
     * answer, its only content must be one marker line, and the label must be empty or a plain-text
     * one ({@link #PLAIN_LABELS}).
     */
    private static final Pattern FENCED_SOURCE_ONLY = Pattern.compile(
            "(?:^|\\n)[ \\t]*(```|~~~)[ \\t]*([A-Za-z]*)[ \\t]*\\n"
                    + "[ \\t]*(?:source|read more|learn more)[ \\t]*:[ \\t]*(https?://\\S+)[ \\t]*\\n"
                    + "[ \\t]*\\1[ \\t]*\\s*$",
            Pattern.CASE_INSENSITIVE);
    /** Language labels that mean "plain text". Anything else is real code and stays a code block. */
    private static final java.util.Set<String> PLAIN_LABELS = new java.util.HashSet<>(
            java.util.Arrays.asList("", "text", "txt", "plaintext", "plain"));
    /** A fence line: an opening or closing ``` or ~~~, with or without a label. */
    private static final Pattern FENCE_LINE = Pattern.compile("(?m)^[ \\t]*(```|~~~)");

    private SourceLinkUtil() {}

    /**
     * Hosted search asks the model to end with a {@code Source: https://...} line. A model sometimes
     * wraps that one line in a plain-text code fence, which would render as a copyable code block
     * ("text · Copy") instead of Orbit's native source link. This unwraps exactly that case back to
     * the plain marker line and changes nothing else: a fence with any other content, a code
     * language label, a marker that is not the answer's last content, or an invalid URL is left as
     * it is, so real code blocks keep their Copy button.
     */
    static String normalizeFencedSourceMarker(String raw) {
        if (raw == null || raw.indexOf("```") < 0 && raw.indexOf("~~~") < 0) return raw;
        Matcher fenced = FENCED_SOURCE_ONLY.matcher(raw);
        if (!fenced.find()) return raw;
        String label = fenced.group(2) == null ? "" : fenced.group(2).toLowerCase(Locale.US);
        if (!PLAIN_LABELS.contains(label)) return raw;
        String url = validatedUrl(fenced.group(3));
        if (url.isEmpty()) return raw;
        String before = raw.substring(0, fenced.start()).replaceAll("\\s+$", "");
        // The fence must not be closing some earlier unbalanced block: everything before it has to
        // have its own fences paired, or this "opening" fence is really the end of another block.
        int fences = 0;
        Matcher lines = FENCE_LINE.matcher(before);
        while (lines.find()) fences++;
        if (fences % 2 != 0) return raw;
        return before + (before.isEmpty() ? "" : "\n\n") + "Source: " + url;
    }

    /** URL from Orbit's explicit hosted-search source marker, not an inline Markdown link. */
    public static String sourceUrl(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String text = normalizeFencedSourceMarker(raw);
        Matcher source = SOURCE_LINE.matcher(text);
        while (source.find()) {
            // A "Source:" line that is part of a real code block is code, not Orbit's marker.
            if (insideFence(text, source.start())) continue;
            return validatedUrl(source.group(1));
        }
        return "";
    }

    /** True when {@code index} falls inside a fenced code block of {@code text}. */
    private static boolean insideFence(String text, int index) {
        int fences = 0;
        Matcher lines = FENCE_LINE.matcher(text.substring(0, Math.max(0, index)));
        while (lines.find()) fences++;
        return fences % 2 != 0;
    }

    public static String firstUrl(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        raw = normalizeFencedSourceMarker(raw);
        Matcher markdown = MARKDOWN.matcher(raw);
        if (markdown.find()) return validatedUrl(markdown.group(2));
        Matcher paren = PAREN_LINK.matcher(raw);
        if (paren.find()) return validatedUrl(paren.group(2));
        Matcher source = SOURCE_LINE.matcher(raw);
        if (source.find()) return validatedUrl(source.group(1));
        Matcher plain = RAW_URL.matcher(raw);
        while (plain.find()) {
            String candidate = validatedUrl(plain.group());
            if (!candidate.isEmpty()) return candidate;
        }
        return "";
    }

    public static String sourceLabel(String raw) {
        if (raw == null) return "Source";
        String explicit = sourceUrl(raw);
        if (!explicit.isEmpty()) {
            try {
                String host = Uri.parse(explicit).getHost();
                if (host != null && !host.trim().isEmpty()) {
                    host = host.toLowerCase(Locale.US);
                    if (host.startsWith("www.")) host = host.substring(4);
                    return compactLabel(host);
                }
            } catch (Exception ignored) {}
        }
        Matcher markdown = MARKDOWN.matcher(raw);
        if (markdown.find()) {
            String label = markdown.group(1) == null ? "" : markdown.group(1).trim();
            if (!label.isEmpty()) return compactLabel(label);
        }
        Matcher paren = PAREN_LINK.matcher(raw);
        if (paren.find()) {
            String label = paren.group(1) == null ? "" : paren.group(1).trim();
            if (!label.isEmpty()) return compactLabel(label);
        }
        String url = firstUrl(raw);
        if (!url.isEmpty()) {
            try {
                String host = Uri.parse(url).getHost();
                if (host != null && !host.trim().isEmpty()) {
                    host = host.toLowerCase(Locale.US);
                    if (host.startsWith("www.")) host = host.substring(4);
                    return compactLabel(host);
                }
            } catch (Exception ignored) {}
        }
        return "Source";
    }

    /**
     * Keeps the answer readable while moving a trailing source URL into a native
     * tappable source chip. Links in the middle of prose are left alone.
     */
    public static String displayText(String raw) {
        if (raw == null) return "";
        String text = normalizeFencedSourceMarker(raw).trim();
        text = text.replaceAll("(?is)\\s*(?:source|read more|learn more)\\s*:\\s*https?://\\S+\\s*$", "");
        return text.trim();
    }

    public static String copyText(String raw) {
        String display = displayText(raw);
        String url = sourceUrl(raw);
        if (url.isEmpty()) return display;
        if (display.isEmpty()) return url;
        return display + "\n\nSource: " + url;
    }


    private static String validatedUrl(String raw) {
        String url = cleanUrl(raw);
        if (url.isEmpty()) return "";
        try {
            Uri parsed = Uri.parse(url);
            String scheme = parsed.getScheme();
            String host = parsed.getHost();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) return "";
            if (host == null || host.trim().isEmpty()) return "";
            if (!host.matches(".*[A-Za-z0-9].*")) return "";
            if (!host.contains(".")) return "";
            return url;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String cleanUrl(String raw) {
        if (raw == null) return "";
        String url = raw.trim();
        while (!url.isEmpty()) {
            char last = url.charAt(url.length() - 1);
            if (last == '.' || last == ',' || last == ';' || last == '!' || last == '?' || last == ']' || last == '}') {
                url = url.substring(0, url.length() - 1);
            } else break;
        }
        return url;
    }

    private static String compactLabel(String raw) {
        String label = raw == null ? "Source" : raw.replaceAll("\\s+", " ").trim();
        if (label.length() > 30) label = label.substring(0, 29).trim() + "…";
        return label.isEmpty() ? "Source" : label;
    }
}
