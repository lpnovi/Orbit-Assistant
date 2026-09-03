package com.orbit.assistant;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Small in-process capability store for non-exported document viewer launches. */
public final class DocumentViewerStore {
    private static final int MAX = 2;
    public static final class Session {
        public final DocumentReference document;
        public final int initialPage;
        Session(DocumentReference document, int initialPage) {
            this.document = document;
            this.initialPage = Math.max(0, initialPage);
        }
    }

    private static final LinkedHashMap<String, Session> SESSIONS = new LinkedHashMap<>();
    private DocumentViewerStore() {}

    public static synchronized String open(DocumentReference document, int page) {
        if (document == null || !document.isUsable()) return "";
        while (SESSIONS.size() >= MAX) {
            String oldest = SESSIONS.keySet().iterator().next();
            SESSIONS.remove(oldest);
        }
        String token = "document-" + UUID.randomUUID();
        SESSIONS.put(token, new Session(document, page));
        return token;
    }

    public static synchronized Session peek(String token) { return SESSIONS.get(token); }
    public static synchronized void close(String token) { if (token != null) SESSIONS.remove(token); }
    public static synchronized void clear() { SESSIONS.clear(); }
}
