package com.orbit.assistant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Page-aware local PDF text plus deterministic case-insensitive search. */
public final class DocumentTextIndex {
    /** A hostile one-character query must not turn bounded text into millions of Match objects. */
    static final int MAX_MATCHES = 20_000;
    public static final class Match {
        public final int page;
        public final int offset;
        Match(int page, int offset) { this.page = page; this.offset = offset; }
    }

    private final List<String> pages;

    public DocumentTextIndex(List<String> pageText) {
        List<String> copy = new ArrayList<>();
        if (pageText != null) {
            for (String text : pageText) copy.add(text == null ? "" : text.trim());
        }
        pages = Collections.unmodifiableList(copy);
    }

    public int pageCount() { return pages.size(); }
    public String pageText(int page) {
        return page < 0 || page >= pages.size() ? "" : pages.get(page);
    }
    public boolean hasSearchableText() {
        for (String page : pages) if (!page.trim().isEmpty()) return true;
        return false;
    }

    public List<Match> search(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return Collections.emptyList();
        List<Match> out = new ArrayList<>();
        for (int page = 0; page < pages.size(); page++) {
            String haystack = pages.get(page).toLowerCase(Locale.ROOT);
            int from = 0;
            while (from <= haystack.length() - needle.length()) {
                int found = haystack.indexOf(needle, from);
                if (found < 0) break;
                out.add(new Match(page, found));
                if (out.size() >= MAX_MATCHES) return Collections.unmodifiableList(out);
                from = found + Math.max(1, needle.length());
            }
        }
        return Collections.unmodifiableList(out);
    }
}
