package com.orbit.assistant;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.NetworkType;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Durable request broker shared by the Side-button sheet and the full Orbit app.
 * WorkManager owns execution so a request can survive UI dismissal and process death.
 */
public final class OrbitRequestManager {
    private OrbitRequestManager() {}

    public interface Listener {
        default void onStarted(String requestId) {}
        default void onDelta(String requestId, String delta) {}
        default void onSuccess(String requestId, AssistantReply reply) {}
        default void onError(String requestId, String message) {}
    }

    private static final ConcurrentHashMap<String, CopyOnWriteArrayList<Listener>> LISTENERS = new ConcurrentHashMap<>();

    public static String enqueue(Context c, String conversationId, String prompt, String screenText, Bitmap screenshot,
                                 boolean voiceRequest, boolean draftReply) {
        return enqueue(c, conversationId, prompt, screenText, screenshot, voiceRequest, draftReply,
                ConversationStore.modeFor(c, conversationId), null);
    }

    public static String enqueue(Context c, String conversationId, String prompt, String screenText, Bitmap screenshot,
                                 boolean voiceRequest, boolean draftReply, Listener listener) {
        return enqueue(c, conversationId, prompt, screenText, screenshot, voiceRequest, draftReply,
                ConversationStore.modeFor(c, conversationId), listener);
    }

    public static String enqueue(Context c, String conversationId, String prompt, String screenText, Bitmap screenshot,
                                 boolean voiceRequest, boolean draftReply, String intelligenceMode, Listener listener) {
        return enqueue(c, conversationId, prompt, screenText, screenshot, voiceRequest, draftReply,
                intelligenceMode, false, listener);
    }

    public static String enqueue(Context c, String conversationId, String prompt, String screenText, Bitmap screenshot,
                                 boolean voiceRequest, boolean draftReply, String intelligenceMode,
                                 boolean explicitAttachment, Listener listener) {
        ConversationStore.Conversation conversation = ConversationStore.load(c, conversationId);
        List<AssistantClient.History> history = conversation == null
                ? java.util.Collections.emptyList() : conversation.messages;
        String trustedTaskContext = ReplyDraftContext.observeAndGet(
                c, conversationId, prompt, screenText, screenshot, history);
        return enqueueFrozen(c, conversationId, prompt, screenText, screenshot, voiceRequest,
                draftReply, intelligenceMode, explicitAttachment, trustedTaskContext, listener);
    }

    private static String enqueueFrozen(Context c, String conversationId, String prompt, String screenText,
                                        Bitmap screenshot, boolean voiceRequest, boolean draftReply,
                                        String intelligenceMode, boolean explicitAttachment,
                                        String trustedTaskContext, Listener listener) {
        String pendingScreen = AttachmentStore.savePendingScreen(c, screenshot);
        PendingRequestStore.Item item = PendingRequestStore.create(c, conversationId, prompt, screenText,
                pendingScreen, voiceRequest, draftReply, intelligenceMode, explicitAttachment,
                trustedTaskContext);
        if (listener != null) addListener(item.id, listener);
        Data input = new Data.Builder().putString(OrbitRequestWorker.KEY_REQUEST_ID, item.id).build();
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(MemoryCommandRouter.canHandle(prompt)
                        ? NetworkType.NOT_REQUIRED : NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(OrbitRequestWorker.class)
                .setConstraints(constraints)
                .setInputData(input)
                .addTag("orbit-ai")
                .addTag("orbit-conversation-" + conversationId)
                .build();
        WorkManager.getInstance(c.getApplicationContext()).enqueueUniqueWork(
                "orbit-request-" + item.id, ExistingWorkPolicy.KEEP, work);
        return item.id;
    }

    public static String retry(Context c, String failedRequestId, Listener listener) {
        PendingRequestStore.Item failed = PendingRequestStore.load(c, failedRequestId);
        if (failed == null) return "";
        Bitmap screenshot = AttachmentStore.load(failed.screenshotPath);
        PendingRequestStore.markSuperseded(c, failedRequestId);
        String next = enqueueFrozen(c, failed.conversationId, failed.prompt, failed.screenText, screenshot,
                failed.voiceRequest, failed.draftReply, failed.intelligenceMode,
                failed.explicitAttachment, failed.trustedTaskContext, listener);
        AttachmentStore.delete(failed.screenshotPath);
        return next;
    }

    public static boolean hasListeners(String requestId) {
        CopyOnWriteArrayList<Listener> list = LISTENERS.get(requestId);
        return list != null && !list.isEmpty();
    }

    public static void addListener(String requestId, Listener listener) {
        if (requestId == null || listener == null) return;
        LISTENERS.computeIfAbsent(requestId, k -> new CopyOnWriteArrayList<>()).addIfAbsent(listener);
    }

    public static void removeListener(String requestId, Listener listener) {
        if (requestId == null || listener == null) return;
        CopyOnWriteArrayList<Listener> list = LISTENERS.get(requestId);
        if (list == null) return;
        list.remove(listener);
        if (list.isEmpty()) LISTENERS.remove(requestId);
    }

    static void dispatchStarted(String id) {
        for (Listener l : listeners(id)) try { l.onStarted(id); } catch (Exception ignored) {}
    }
    static void dispatchDelta(String id, String delta) {
        for (Listener l : listeners(id)) try { l.onDelta(id, delta); } catch (Exception ignored) {}
    }
    static void dispatchSuccess(String id, AssistantReply reply) {
        for (Listener l : listeners(id)) try { l.onSuccess(id, reply); } catch (Exception ignored) {}
        LISTENERS.remove(id);
    }
    static void dispatchError(String id, String error) {
        for (Listener l : listeners(id)) try { l.onError(id, error); } catch (Exception ignored) {}
        LISTENERS.remove(id);
    }
    private static List<Listener> listeners(String id) {
        CopyOnWriteArrayList<Listener> list = LISTENERS.get(id);
        return list == null ? java.util.Collections.emptyList() : list;
    }
}
