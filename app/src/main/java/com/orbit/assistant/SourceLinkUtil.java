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

    private SourceLinkUtil() {}

    /** URL from Orbit's explicit hosted-search source marker, not an inline Markdown link. */
    public static String sourceUrl(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        Matcher source = SOURCE_LINE.matcher(raw);
        return source.find() ? validatedUrl(source.group(1)) : "";
    }

    public static String firstUrl(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
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
        String text = raw.trim();
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
