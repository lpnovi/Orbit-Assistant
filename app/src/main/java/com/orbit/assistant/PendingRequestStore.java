package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Durable metadata for AI requests that can outlive an assistant sheet or activity. */
public final class PendingRequestStore {
    private static final String FILE = "orbit_pending_requests";
    private static final String KEY = "items_v1";
    public static final String QUEUED = "queued";
    public static final String RUNNING = "running";
    public static final String DONE = "done";
    public static final String FAILED = "failed";
    /**
     * The user stopped this request before it finished. Terminal like {@link #DONE} and
     * {@link #FAILED}, but deliberately neither of them: a cancelled request is not active, so it
     * never shows a thinking state, and it is not a failure, so it never offers Retry or an error.
     */
    public static final String CANCELLED = "cancelled";

    private PendingRequestStore() {}

    public static final class Item {
        public final String id, conversationId, prompt, screenText, screenshotPath, status, error, intelligenceMode, trustedTaskContext;
        /**
         * Every image frozen for this request, in the order the user attached them.
         *
         * <p>{@link #screenshotPath} is always its head, and a request written before v0.7.8.0
         * Beta 3 reads back as the single path it recorded, so an in-flight request that survives
         * an app update is not lost or misread.
         */
        public final List<String> screenshotPaths;
        public final long createdAt, updatedAt;
        public final boolean voiceRequest, draftReply, explicitAttachment;
        /**
         * True once this request's one and only completion has been claimed.
         *
         * <p>Separate from {@link #status} on purpose. Status is what the request looks like;
         * this is the irreversible fact that an answer for it has already been written. It is
         * claimed synchronously, before the answer is appended, so a worker re-run by WorkManager
         * after a process death cannot produce a second assistant message for one user turn.
         */
        public final boolean committed;

        public Item(String id, String conversationId, String prompt, String screenText, String screenshotPath,
                    String status, String error, long createdAt, long updatedAt, boolean voiceRequest, boolean draftReply,
                    String intelligenceMode, boolean explicitAttachment, String trustedTaskContext) {
            this(id, conversationId, prompt, screenText, screenshotPath, status, error, createdAt,
                    updatedAt, voiceRequest, draftReply, intelligenceMode, explicitAttachment,
                    trustedTaskContext, false);
        }

        public Item(String id, String conversationId, String prompt, String screenText, String screenshotPath,
                    String status, String error, long createdAt, long updatedAt, boolean voiceRequest, boolean draftReply,
                    String intelligenceMode, boolean explicitAttachment, String trustedTaskContext,
                    boolean committed) {
            this(id, conversationId, prompt, screenText,
                    screenshotPath == null || screenshotPath.trim().isEmpty()
                            ? java.util.Collections.emptyList()
                            : java.util.Collections.singletonList(screenshotPath),
                    status, error, createdAt, updatedAt, voiceRequest, draftReply, intelligenceMode,
                    explicitAttachment, trustedTaskContext, committed);
        }

        /** The full constructor: a request frozen with any number of images. */
        public Item(String id, String conversationId, String prompt, String screenText,
                    List<String> screenshotPaths, String status, String error, long createdAt,
                    long updatedAt, boolean voiceRequest, boolean draftReply,
                    String intelligenceMode, boolean explicitAttachment, String trustedTaskContext,
                    boolean committed) {
            this.committed = committed;
            this.id = id;
            this.conversationId = conversationId;
            this.prompt = prompt;
            this.screenText = screenText;
            List<String> paths = new ArrayList<>();
            if (screenshotPaths != null) {
                for (String path : screenshotPaths) {
                    if (path != null && !path.trim().isEmpty()) paths.add(path);
                }
            }
            this.screenshotPaths = java.util.Collections.unmodifiableList(paths);
            this.screenshotPath = paths.isEmpty() ? "" : paths.get(0);
            this.status = status;
            this.error = error;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.voiceRequest = voiceRequest;
            this.draftReply = draftReply;
            this.intelligenceMode = Prefs.normalizeMode(intelligenceMode);
            this.explicitAttachment = explicitAttachment;
            this.trustedTaskContext = trustedTaskContext == null ? "" : trustedTaskContext;
        }
    }

    public static synchronized Item create(Context c, String conversationId, String prompt, String screenText,
                                           String screenshotPath, boolean voiceRequest, boolean draftReply,
                                           String intelligenceMode, boolean explicitAttachment,
                                           String trustedTaskContext) {
        return create(c, conversationId, prompt, screenText,
                screenshotPath == null || screenshotPath.trim().isEmpty()
                        ? java.util.Collections.emptyList()
                        : java.util.Collections.singletonList(screenshotPath),
                voiceRequest, draftReply, intelligenceMode, explicitAttachment, trustedTaskContext);
    }

    public static synchronized Item create(Context c, String conversationId, String prompt, String screenText,
                                           List<String> screenshotPaths, boolean voiceRequest, boolean draftReply,
                                           String intelligenceMode, boolean explicitAttachment,
                                           String trustedTaskContext) {
        long now = System.currentTimeMillis();
        Item item = new Item(UUID.randomUUID().toString(), conversationId, prompt == null ? "" : prompt,
                screenText == null ? "" : screenText, screenshotPaths,
                QUEUED, "", now, now, voiceRequest, draftReply, intelligenceMode, explicitAttachment,
                trustedTaskContext, false);
        List<Item> all = readAll(c);
        all.add(0, item);
        trim(all);
        writeAll(c, all);
        return item;
    }

    public static synchronized Item load(Context c, String id) {
        for (Item i : readAll(c)) if (i.id.equals(id)) return i;
        return null;
    }

    public static synchronized List<Item> active(Context c) {
        List<Item> result = new ArrayList<>();
        for (Item i : readAll(c)) if (QUEUED.equals(i.status) || RUNNING.equals(i.status)) result.add(i);
        result.sort((a,b) -> Long.compare(b.createdAt, a.createdAt));
        return result;
    }

    public static synchronized List<Item> activeForConversation(Context c, String conversationId) {
        List<Item> result = new ArrayList<>();
        if (conversationId == null) return result;
        for (Item i : readAll(c)) {
            if (conversationId.equals(i.conversationId) && (QUEUED.equals(i.status) || RUNNING.equals(i.status))) result.add(i);
        }
        result.sort((a,b) -> Long.compare(a.createdAt, b.createdAt));
        return result;
    }

    public static synchronized boolean hasActiveForConversation(Context c, String conversationId) {
        if (conversationId == null) return false;
        for (Item i : readAll(c)) {
            if (conversationId.equals(i.conversationId) && (QUEUED.equals(i.status) || RUNNING.equals(i.status))) return true;
        }
        return false;
    }

    public static synchronized List<Item> failedForConversation(Context c, String conversationId) {
        List<Item> result = new ArrayList<>();
        if (conversationId == null) return result;
        for (Item i : readAll(c)) {
            if (conversationId.equals(i.conversationId) && FAILED.equals(i.status)) result.add(i);
        }
        result.sort((a,b) -> Long.compare(b.updatedAt, a.updatedAt));
        return result;
    }

    public static synchronized Item latestFailedForConversation(Context c, String conversationId) {
        List<Item> failed = failedForConversation(c, conversationId);
        return failed.isEmpty() ? null : failed.get(0);
    }

    /**
     * True when this request is one the user stopped.
     *
     * <p>Cancellation lives here; <em>where</em> the resulting mark belongs lives on the
     * conversation, as {@link AssistantClient.History#stoppedRequestId}. Beta 2 tried to answer
     * both questions from this store by treating "the conversation's newest request is cancelled"
     * as the stopped state. That could only ever describe one stopped turn, and it stopped
     * describing even that one the moment the next turn was queued, which is how a mark ended up
     * below a question it had nothing to do with.
     */
    public static synchronized boolean isStoppedRequest(Context c, String id) {
        Item item = load(c, id);
        return item != null && CANCELLED.equals(item.status);
    }

    /** True once a request can no longer change state, whichever way it ended. */
    public static boolean isTerminal(String status) {
        return DONE.equals(status) || FAILED.equals(status) || CANCELLED.equals(status);
    }

    /**
     * Claims the one completion this request is allowed, returning true only for the first caller.
     *
     * <p>This is the durable half of the invariant "one accepted submission produces at most one
     * persisted assistant completion". The claim is written with {@code commit()} rather than
     * {@code apply()} so it is on disk before the answer is appended: if the process dies in
     * between, the retried worker sees the claim and abandons the turn instead of asking the model
     * again and appending a second, similar-looking answer.
     *
     * <p>Refused for a request that has already been claimed, and for one that is already
     * terminal, which covers a completion arriving after the user stopped the request.
     */
    public static synchronized boolean claimCompletion(Context c, String id) {
        if (c == null || id == null || id.isEmpty()) return false;
        List<Item> all = readAll(c);
        for (int x = 0; x < all.size(); x++) {
            Item i = all.get(x);
            if (!i.id.equals(id)) continue;
            if (i.committed || isTerminal(i.status)) return false;
            all.set(x, new Item(i.id, i.conversationId, i.prompt, i.screenText, i.screenshotPaths,
                    i.status, i.error, i.createdAt, System.currentTimeMillis(), i.voiceRequest,
                    i.draftReply, i.intelligenceMode, i.explicitAttachment, i.trustedTaskContext,
                    true));
            writeAll(c, all, true);
            return true;
        }
        return false;
    }

    /** True once this request's completion has been claimed, in this process or an earlier one. */
    public static synchronized boolean isCommitted(Context c, String id) {
        Item item = load(c, id);
        return item != null && item.committed;
    }

    /** Durable cancellation check, readable by the worker after the UI is long gone. */
    public static synchronized boolean isCancelled(Context c, String id) {
        Item item = load(c, id);
        return item != null && CANCELLED.equals(item.status);
    }

    public static synchronized void markSuperseded(Context c, String id) { update(c, id, DONE, ""); }

    public static synchronized void markRunning(Context c, String id) { update(c, id, RUNNING, ""); }
    public static synchronized void markDone(Context c, String id) { update(c, id, DONE, ""); }
    public static synchronized void markFailed(Context c, String id, String error) { update(c, id, FAILED, error == null ? "" : error); }
    public static synchronized void markCancelled(Context c, String id) { update(c, id, CANCELLED, ""); }

    private static void update(Context c, String id, String status, String error) {
        List<Item> all = readAll(c);
        long now = System.currentTimeMillis();
        for (int x = 0; x < all.size(); x++) {
            Item i = all.get(x);
            if (!i.id.equals(id)) continue;
            all.set(x, new Item(i.id, i.conversationId, i.prompt, i.screenText, i.screenshotPaths,
                    status, error, i.createdAt, now, i.voiceRequest, i.draftReply,
                    i.intelligenceMode, i.explicitAttachment, i.trustedTaskContext, i.committed));
            break;
        }
        trim(all);
        writeAll(c, all);
    }

    private static void trim(List<Item> all) {
        long cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000;
        all.removeIf(i -> isTerminal(i.status) && i.updatedAt < cutoff);
        if (all.size() > 100) all.subList(100, all.size()).clear();
    }

    private static List<Item> readAll(Context c) {
        List<Item> result = new ArrayList<>();
        try {
            SharedPreferences p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(p.getString(KEY, "[]"));
            for (int x=0; x<arr.length(); x++) {
                JSONObject o = arr.optJSONObject(x); if (o == null) continue;
                result.add(new Item(o.optString("id"), o.optString("conversationId"), o.optString("prompt"),
                        o.optString("screenText"), readScreenshotPaths(o), o.optString("status", QUEUED),
                        o.optString("error"), o.optLong("createdAt"), o.optLong("updatedAt"),
                        o.optBoolean("voiceRequest"), o.optBoolean("draftReply"),
                        o.optString("intelligenceMode", Prefs.MODE_BALANCED),
                        o.optBoolean("explicitAttachment", false),
                        o.optString("trustedTaskContext", ""),
                        o.optBoolean("committed", false)));
            }
        } catch (Exception ignored) {}
        return result;
    }

    /** A stored request's ordered image paths, whichever shape it was written in. */
    private static List<String> readScreenshotPaths(JSONObject record) {
        List<String> paths = new ArrayList<>();
        JSONArray stored = record.optJSONArray("screenshotPaths");
        if (stored != null) {
            for (int i = 0; i < stored.length(); i++) {
                String path = stored.optString(i, "");
                if (path != null && !path.trim().isEmpty()) paths.add(path);
            }
        }
        if (paths.isEmpty()) {
            String legacy = record.optString("screenshotPath", "");
            if (legacy != null && !legacy.trim().isEmpty()) paths.add(legacy);
        }
        return paths;
    }

    private static void writeAll(Context c, List<Item> all) {
        writeAll(c, all, false);
    }

    /** {@code sync} writes through before returning, for a claim that must survive a crash. */
    private static void writeAll(Context c, List<Item> all, boolean sync) {
        JSONArray arr = new JSONArray();
        try {
            for (Item i : all) {
                JSONObject record = new JSONObject();
                // Written only for a genuinely multi-image request, so a one-image request's
                // record is byte-for-byte what it was before this existed.
                if (i.screenshotPaths.size() > 1) {
                    JSONArray paths = new JSONArray();
                    for (String path : i.screenshotPaths) paths.put(path);
                    record.put("screenshotPaths", paths);
                }
                arr.put(record.put("id", i.id).put("conversationId", i.conversationId)
                        .put("prompt", i.prompt).put("screenText", i.screenText).put("screenshotPath", i.screenshotPath)
                        .put("status", i.status).put("error", i.error).put("createdAt", i.createdAt).put("updatedAt", i.updatedAt)
                        .put("voiceRequest", i.voiceRequest).put("draftReply", i.draftReply)
                        .put("intelligenceMode", i.intelligenceMode)
                        .put("explicitAttachment", i.explicitAttachment)
                        .put("trustedTaskContext", i.trustedTaskContext)
                        .put("committed", i.committed));
            }
        } catch (Exception ignored) {}
        SharedPreferences.Editor edit = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString());
        if (sync) edit.commit(); else edit.apply();
    }
}
