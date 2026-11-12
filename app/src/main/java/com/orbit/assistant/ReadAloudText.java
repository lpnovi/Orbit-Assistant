package com.orbit.assistant;

import java.util.ArrayList;
import java.util.List;

/**
 * What Read Aloud actually says: the answer's words without its Markdown, cut into pieces a
 * speech engine takes comfortably. The stored and copied answer is never changed.
 */
final class ReadAloudText {
    /** Well under {@code TextToSpeech.getMaxSpeechInputLength()} (4000), and a short resume step. */
    static final int MAX_CHUNK = 500;
    static final String CODE_PLACEHOLDER = "Code omitted.";

    private ReadAloudText() {}

    /** The speakable text, paragraphs kept as lines. Empty when there is nothing to say. */
    static String clean(String raw) {
        if (raw == null) return "";
        String text = SourceLinkUtil.displayText(raw).replace("\r\n", "\n");
        // Code is not prose: a fenced block is named, not spelled out symbol by symbol.
        text = text.replaceAll("(?s)```.*?(?:```|$)", "\n\n" + CODE_PLACEHOLDER + "\n\n");
        // A heading ends like a sentence, so the engine pauses after it.
        text = text.replaceAll("(?m)^(#{1,6}[ \\t]+.*?[^\\s:.!?])[ \\t:.]*$", "$1.");
        text = OrbitMarkdown.toSpeechText(text);
        // A bare address is read as where it points, not letter by letter.
        text = text.replaceAll("https?://(?:www\\.)?([^/\\s)>\\]]+)[^\\s)>\\]]*", "link to $1");
        text = text.replaceAll("(?m)^\\s*\\d+[.)]\\s+", "").replaceAll("[ \\t]+", " ");
        return text.replaceAll("\\n{2,}", "\n").trim();
    }

    /** {@link #clean} in speaking order, no piece longer than {@link #MAX_CHUNK}. */
    static List<String> chunks(String raw) {
        List<String> out = new ArrayList<>();
        for (String paragraph : clean(raw).split("\n")) {
            String rest = paragraph.trim();
            while (rest.length() > MAX_CHUNK) {
                int cut = lastBreak(rest, ". ", "! ", "? ", "; ", ": ");
                if (cut <= 0) cut = lastBreak(rest, ", ", " ");
                if (cut <= 0) cut = MAX_CHUNK;
                out.add(rest.substring(0, cut).trim());
                rest = rest.substring(cut).trim();
            }
            if (!rest.isEmpty()) out.add(rest);
        }
        return out;
    }

    /** The end of the last of these separators that still fits in one chunk, or 0. */
    private static int lastBreak(String text, String... separators) {
        int best = 0;
        for (String separator : separators) {
            int at = text.lastIndexOf(separator, MAX_CHUNK - separator.length());
            if (at > 0) best = Math.max(best, at + separator.length());
        }
        return best;
    }
}
