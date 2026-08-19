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
        public final long createdAt, updatedAt;
        public final boolean voiceRequest, draftReply, explicitAttachment;

        public Item(String id, String conversationId, String prompt, String screenText, String screenshotPath,
                    String status, String error, long createdAt, long updatedAt, boolean voiceRequest, boolean draftReply,
                    String intelligenceMode, boolean explicitAttachment, String trustedTaskContext) {
            this.id = id;
            this.conversationId = conversationId;
            this.prompt = prompt;
            this.screenText = screenText;
            this.screenshotPath = screenshotPath;
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
        long now = System.currentTimeMillis();
        Item item = new Item(UUID.randomUUID().toString(), conversationId, prompt == null ? "" : prompt,
                screenText == null ? "" : screenText, screenshotPath == null ? "" : screenshotPath,
                QUEUED, "", now, now, voiceRequest, draftReply, intelligenceMode, explicitAttachment,
                trustedTaskContext);
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

    /** True once a request can no longer change state, whichever way it ended. */
    public static boolean isTerminal(String status) {
        return DONE.equals(status) || FAILED.equals(status) || CANCELLED.equals(status);
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
            all.set(x, new Item(i.id, i.conversationId, i.prompt, i.screenText, i.screenshotPath,
                    status, error, i.createdAt, now, i.voiceRequest, i.draftReply,
                    i.intelligenceMode, i.explicitAttachment, i.trustedTaskContext));
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
                        o.optString("screenText"), o.optString("screenshotPath"), o.optString("status", QUEUED),
                        o.optString("error"), o.optLong("createdAt"), o.optLong("updatedAt"),
                        o.optBoolean("voiceRequest"), o.optBoolean("draftReply"),
                        o.optString("intelligenceMode", Prefs.MODE_BALANCED),
                        o.optBoolean("explicitAttachment", false),
                        o.optString("trustedTaskContext", "")));
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static void writeAll(Context c, List<Item> all) {
        JSONArray arr = new JSONArray();
        try {
            for (Item i : all) {
                arr.put(new JSONObject().put("id", i.id).put("conversationId", i.conversationId)
                        .put("prompt", i.prompt).put("screenText", i.screenText).put("screenshotPath", i.screenshotPath)
                        .put("status", i.status).put("error", i.error).put("createdAt", i.createdAt).put("updatedAt", i.updatedAt)
                        .put("voiceRequest", i.voiceRequest).put("draftReply", i.draftReply)
                        .put("intelligenceMode", i.intelligenceMode)
                        .put("explicitAttachment", i.explicitAttachment)
                        .put("trustedTaskContext", i.trustedTaskContext));
            }
        } catch (Exception ignored) {}
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply();
    }
}
