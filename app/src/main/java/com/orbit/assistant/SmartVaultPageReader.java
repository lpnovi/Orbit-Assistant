package com.orbit.assistant;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the web page behind a saved link, once, so the link can be found by what the page says.
 *
 * <p>Only when the user turned on "Read saved links" in Smart Vault, and only for links they
 * saved. It uses Rich Answers' existing page fetcher unchanged - the same URL policy, the same
 * public-host check on every redirect, the same 512 KB ceiling, no cookies and no JavaScript - and
 * keeps nothing but plain text. What it keeps is untrusted: it is indexed and can be shown, and
 * when it ever reaches a model it is fenced as data.
 */
final class SmartVaultPageReader {

    static final int MAX_TEXT_CHARS = 16000;

    static final class Page {
        final boolean ok;
        final String title;
        final String text;
        final String failure;

        Page(boolean ok, String title, String text, String failure) {
            this.ok = ok;
            this.title = title == null ? "" : title.trim();
            this.text = text == null ? "" : text.trim();
            this.failure = failure == null ? "" : failure;
        }
    }

    /** The seam tests replace. Production always reads through {@link RichAnswerPageFetcher}. */
    interface Source {
        Page read(String url);
    }

    private static volatile Source source = SmartVaultPageReader::readOverNetwork;

    private SmartVaultPageReader() {}

    static Source installForTest(Source replacement) {
        Source previous = source;
        source = replacement;
        return previous;
    }

    static Page read(String url) {
        return source.read(url);
    }

    private static Page readOverNetwork(String url) {
        return RichAnswerPageFetcher.fetchMarkup(url,
                new RichAnswerPageFetcher.MarkupParser<Page>() {
                    @Override public Page parse(String html, String finalUrl, int status,
                                                String contentType, int redirects) {
                        return extract(html, finalUrl);
                    }

                    @Override public Page failed(RichAnswerTrace.Reason reason, String u,
                                                 int status, String contentType, int redirects) {
                        return new Page(false, "", "", reason == null ? "" : reason.name());
                    }
                });
    }

    private static final Pattern DROP = Pattern.compile(
            "<(script|style|noscript|svg|nav|header|footer|aside|form|template|iframe|button|"
                    + "select)\\b[^>]*>.*?</\\1\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern ARTICLE = Pattern.compile("<article\\b[^>]*>(.*)</article>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern MAIN = Pattern.compile("<main\\b[^>]*>(.*)</main>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern BODY = Pattern.compile("<body\\b[^>]*>(.*)</body>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern BREAKS = Pattern.compile(
            "<\\s*(br|/p|/h[1-6]|/li|/div|/tr|/blockquote|/section|/pre)\\b[^>]*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]*>");

    /** Title, description and the readable body of one page, from its markup alone. */
    static Page extract(String html, String url) {
        if (html == null || html.trim().isEmpty()) return new Page(false, "", "", "EMPTY");
        RichAnswerPageMetadata.Preview preview = RichAnswerPageMetadata.parse(html, url);
        String cleaned = COMMENT.matcher(html).replaceAll(" ");
        cleaned = DROP.matcher(cleaned).replaceAll(" ");
        String region = firstGroup(ARTICLE, cleaned);
        if (region.isEmpty()) region = firstGroup(MAIN, cleaned);
        if (region.isEmpty()) region = firstGroup(BODY, cleaned);
        if (region.isEmpty()) region = cleaned;
        String withBreaks = BREAKS.matcher(region).replaceAll("\n");
        String plain = RichAnswerPageMetadata.decode(TAG.matcher(withBreaks).replaceAll(" "));
        StringBuilder text = new StringBuilder();
        for (String line : plain.split("\n")) {
            String l = line.replaceAll("[ \\t\\u00a0]+", " ").trim();
            // Menus, buttons and cookie notices are mostly short fragments; prose is not.
            if (l.split(" ").length < 4) continue;
            if (text.length() > 0) text.append('\n');
            text.append(l);
            if (text.length() >= MAX_TEXT_CHARS) break;
        }
        String body = text.length() > MAX_TEXT_CHARS
                ? text.substring(0, MAX_TEXT_CHARS) : text.toString();
        String description = metaDescription(html);
        if (!description.isEmpty() && !body.toLowerCase(Locale.ROOT)
                .contains(description.toLowerCase(Locale.ROOT))) {
            body = description + (body.isEmpty() ? "" : "\n" + body);
        }
        String title = preview.title == null ? "" : preview.title.trim();
        if (title.length() > VaultSuggestions.MAX_TITLE_CHARS) {
            title = title.substring(0, VaultSuggestions.MAX_TITLE_CHARS).trim();
        }
        return new Page(true, title, body, "");
    }

    private static final Pattern META_TAG = Pattern.compile("<meta\\b[^>]*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern META_NAME = Pattern.compile(
            "(?:name|property)\\s*=\\s*[\"']?(description|og:description)[\"'\\s>]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern META_CONTENT = Pattern.compile(
            "content\\s*=\\s*(\"([^\"]*)\"|'([^']*)')", Pattern.CASE_INSENSITIVE);

    /** The page's own one-line description, from its description or og:description meta tag. */
    static String metaDescription(String html) {
        Matcher tags = META_TAG.matcher(html);
        while (tags.find()) {
            String tag = tags.group();
            if (!META_NAME.matcher(tag).find()) continue;
            Matcher content = META_CONTENT.matcher(tag);
            if (!content.find()) continue;
            String value = content.group(2) != null ? content.group(2) : content.group(3);
            String decoded = RichAnswerPageMetadata.decode(value == null ? "" : value).trim();
            if (!decoded.isEmpty()) return decoded.length() > 400 ? decoded.substring(0, 400) : decoded;
        }
        return "";
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : "";
    }
}
