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
        /**
         * The user stopped this request. {@code partialText} is whatever had already streamed and
         * has already been persisted by the manager, or empty when nothing arrived in time.
         */
        default void onCancelled(String requestId, String partialText) {}
    }

    /**
     * Cancels the durable work behind a request. Replaced in tests so cancellation can be proven
     * without standing up a real WorkManager.
     */
    interface WorkCanceller {
        void cancelUniqueWork(String uniqueWorkName);
    }

    private static final ConcurrentHashMap<String, CopyOnWriteArrayList<Listener>> LISTENERS = new ConcurrentHashMap<>();
    /**
     * Latest streamed snapshot per request. Deltas arrive as cumulative text, so the newest one is
     * the whole partial answer, which is what a stopped request keeps.
     */
    private static final ConcurrentHashMap<String, String> PARTIALS = new ConcurrentHashMap<>();
    /**
     * In-process record of stopped requests. The durable copy lives in {@link PendingRequestStore};
     * this exists so a delta or completion already in flight is refused without a disk read.
     */
    private static final java.util.Set<String> CANCELLED = ConcurrentHashMap.newKeySet();
    /**
     * One lock per request, held across the whole of either finishing or cancelling it. This is
     * what makes an accepted Stop and a completion that lands microseconds later mutually
     * exclusive rather than interleaved.
     */
    private static final ConcurrentHashMap<String, Object> COMPLETION_LOCKS = new ConcurrentHashMap<>();

    private static volatile WorkCanceller workCanceller;

    static String uniqueWorkName(String requestId) { return "orbit-request-" + requestId; }

    /** The object both {@link #completeIfNotCancelled} and {@link #cancel} synchronize on. */
    private static Object completionLock(String requestId) {
        return COMPLETION_LOCKS.computeIfAbsent(requestId == null ? "" : requestId, k -> new Object());
    }

    /**
     * The one gate every irreversible completion step passes through.
     *
     * <p>{@code completion} runs only if the user has not stopped the request <em>and</em> no
     * completion for it has already been written, and runs while the request's cancellation lock
     * is held. A Stop arriving at the same instant either lands first and skips the work entirely
     * or waits and then finds the request already finished. There is no interleaving, which is
     * what keeps a stopped request from persisting an answer, running response actions, posting a
     * completion notification, or speaking.
     *
     * <p>The completion claim is the other half, and it is what enforces
     * "one accepted submission produces one request id produces at most one persisted assistant
     * completion". It is durable, so a worker WorkManager re-runs after a process death finds the
     * turn already answered instead of asking the model again and appending a second, similar
     * reply to a single visible user message. A callback that fires twice is refused for the same
     * reason, without anyone having to compare response text.
     *
     * @return true if the completion ran. False means the user's Stop won, or this request had
     *         already been answered.
     */
    public static boolean completeIfNotCancelled(Context c, String requestId, Runnable completion) {
        return completeIfNotCancelled(c, requestId, CompletionSource.UNKNOWN, -1, completion);
    }

    /**
     * As above, told which code path is calling and which WorkManager attempt it is on.
     *
     * <p>The guard itself is unchanged and stays unconditional — this only records who arrived at
     * it. A refused completion previously left nothing behind but a counter, so the one seen on a
     * real device could not be attributed to anything; now the refusal names its own origin.
     *
     * @param workAttempt WorkManager's run attempt for a worker caller, or -1 where there is none.
     */
    public static boolean completeIfNotCancelled(Context c, String requestId,
                                                 CompletionSource source, int workAttempt,
                                                 Runnable completion) {
        if (requestId == null || requestId.isEmpty() || completion == null) return false;
        synchronized (completionLock(requestId)) {
            if (isCancelled(c, requestId)) {
                RequestTrace.completionIgnored(c, requestId, source, workAttempt,
                        priorState(c, requestId), RequestTrace.REASON_CANCELLED);
                return false;
            }
            if (c != null && !PendingRequestStore.claimCompletion(c, requestId)) {
                RequestTrace.completionIgnored(c, requestId, source, workAttempt,
                        priorState(c, requestId), RequestTrace.REASON_ALREADY_COMPLETED);
                return false;
            }
            completion.run();
            RequestTrace.completionCommitted(c, requestId, source, workAttempt);
            return true;
        }
    }

    /**
     * What the request already was when a completion for it was refused.
     *
     * <p>Read only on the refusal path, which is rare, so the disk read costs nothing that
     * matters. "committed" is reported separately from the status because they answer different
     * questions: the status is how the request ended, and the claim is whether an answer for it
     * had already been written.
     */
    private static String priorState(Context c, String requestId) {
        PendingRequestStore.Item item = c == null ? null : PendingRequestStore.load(c, requestId);
        if (item == null) return "missing";
        return item.committed ? "committed-" + item.status : item.status;
    }

    /** True once the user's Stop has been accepted for this request, in this process. */
    public static boolean isCancelled(String requestId) {
        return requestId != null && CANCELLED.contains(requestId);
    }

    /** True for a request stopped in this process or in an earlier one. */
    public static boolean isCancelled(Context c, String requestId) {
        if (isCancelled(requestId)) return true;
        return c != null && requestId != null && PendingRequestStore.isCancelled(c, requestId);
    }

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
        // An offline-capable provider (Orbit Local) must not wait for connectivity: its whole
        // point is answering with none. Cloud providers keep the connectivity constraint so a
        // request survives a dead network instead of failing instantly.
        boolean offlineOk = MemoryCommandRouter.canHandle(prompt)
                || KitchenMathRouter.canHandle(prompt)
                || AiProviders.active(c).capabilities().offline;
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(offlineOk ? NetworkType.NOT_REQUIRED : NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(OrbitRequestWorker.class)
                .setConstraints(constraints)
                .setInputData(input)
                .addTag("orbit-ai")
                .addTag("orbit-conversation-" + conversationId)
                .build();
        // KEEP, not REPLACE: the unique name is derived from a request id that was just created,
        // so nothing can already be enqueued under it. Should anything ever collide, keeping the
        // existing work is the answer that cannot produce a second run of the same turn.
        WorkManager.getInstance(c.getApplicationContext()).enqueueUniqueWork(
                uniqueWorkName(item.id), ExistingWorkPolicy.KEEP, work);
        RequestTrace.lifecycle(item.id, "queued");
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

    /**
     * The one authoritative way to stop an Orbit request. Both surfaces call this; neither owns any
     * cancellation logic of its own.
     *
     * <p>Everything that makes the stop real happens while the request's completion lock is held,
     * so a worker that is midway through finishing either committed its answer before this ran or
     * finds the request cancelled and abandons it. There is no window in between.
     *
     * @return true if this call is what stopped the request. False means there was nothing to stop:
     *         no such request, or it had already finished, failed, or been stopped.
     */
    public static boolean cancel(Context c, String requestId) {
        if (c == null || requestId == null || requestId.isEmpty()) return false;
        PendingRequestStore.Item item = PendingRequestStore.load(c, requestId);
        if (item == null) return false;

        String partial;
        synchronized (completionLock(requestId)) {
            PendingRequestStore.Item current = PendingRequestStore.load(c, requestId);
            // committed as well as terminal: an answer that has been claimed is already being
            // written, so there is nothing left for a Stop to prevent.
            if (current == null || current.committed
                    || PendingRequestStore.isTerminal(current.status)) return false;
            // Refuse deltas and completions from this point on, before anything else can observe
            // a half-cancelled request.
            CANCELLED.add(requestId);
            PendingRequestStore.markCancelled(c, requestId);
            partial = takePartial(requestId);
            // The manager is the single owner of partial persistence: whatever had streamed is
            // written here exactly once, so reopening the conversation shows it and no other path
            // can append it a second time.
            if (!partial.isEmpty()) {
                ConversationStore.appendMessage(c, current.conversationId,
                        new AssistantClient.History("assistant", partial));
            }
            cancelWork(c, requestId);
        }
        AttachmentStore.delete(item.screenshotPath);
        dispatchCancelled(requestId, partial);
        return true;
    }

    /**
     * Stops every request still running for one conversation. Convenience over {@link #cancel};
     * the decision and the persistence still happen there.
     *
     * @return true if at least one request was stopped.
     */
    public static boolean cancelActiveForConversation(Context c, String conversationId) {
        if (c == null || conversationId == null) return false;
        boolean cancelled = false;
        for (PendingRequestStore.Item item : PendingRequestStore.activeForConversation(c, conversationId)) {
            if (cancel(c, item.id)) cancelled = true;
        }
        return cancelled;
    }

    private static void cancelWork(Context c, String requestId) {
        String name = uniqueWorkName(requestId);
        WorkCanceller canceller = workCanceller;
        if (canceller != null) { canceller.cancelUniqueWork(name); return; }
        try {
            WorkManager.getInstance(c.getApplicationContext()).cancelUniqueWork(name);
        } catch (Throwable ignored) {}
    }

    static void setWorkCanceller(WorkCanceller canceller) { workCanceller = canceller; }

    private static String takePartial(String requestId) {
        String partial = PARTIALS.remove(requestId);
        return partial == null ? "" : partial.trim();
    }

    /** Test seam: clears the in-process cancellation and streaming records between cases. */
    static void resetForTest() {
        LISTENERS.clear();
        PARTIALS.clear();
        CANCELLED.clear();
        COMPLETION_LOCKS.clear();
        workCanceller = null;
    }

    public static boolean hasListeners(String requestId) {
        CopyOnWriteArrayList<Listener> list = LISTENERS.get(requestId);
        return list != null && !list.isEmpty();
    }

    /**
     * Attaches a surface to an existing request.
     *
     * <p>A recreated Activity or a reopened overlay attaches here rather than starting anything.
     * Listening is purely observational: it cannot enqueue work and it cannot persist an answer,
     * so any number of surfaces may watch one request and the request still produces exactly one
     * completion.
     */
    public static void addListener(String requestId, Listener listener) {
        if (requestId == null || listener == null) return;
        // A stopped request has nothing left to report, so nothing new attaches to it.
        if (isCancelled(requestId)) return;
        LISTENERS.computeIfAbsent(requestId, k -> new CopyOnWriteArrayList<>()).addIfAbsent(listener);
        RequestTrace.listener(requestId, true);
    }

    public static void removeListener(String requestId, Listener listener) {
        if (requestId == null || listener == null) return;
        CopyOnWriteArrayList<Listener> list = LISTENERS.get(requestId);
        if (list == null) return;
        list.remove(listener);
        if (list.isEmpty()) LISTENERS.remove(requestId);
        RequestTrace.listener(requestId, false);
    }

    static void dispatchStarted(String id) {
        if (isCancelled(id)) return;
        for (Listener l : listeners(id)) try { l.onStarted(id); } catch (Exception ignored) {}
    }
    static void dispatchDelta(String id, String delta) {
        if (isCancelled(id)) return;
        // Deltas are cumulative, so the newest one replaces the last as the whole partial answer.
        if (delta != null && !delta.isEmpty()) PARTIALS.put(id, delta);
        for (Listener l : listeners(id)) try { l.onDelta(id, delta); } catch (Exception ignored) {}
    }
    static void dispatchSuccess(String id, AssistantReply reply) {
        if (isCancelled(id)) { forget(id); return; }
        for (Listener l : listeners(id)) try { l.onSuccess(id, reply); } catch (Exception ignored) {}
        forget(id);
    }
    static void dispatchError(String id, String error) {
        if (isCancelled(id)) { forget(id); return; }
        for (Listener l : listeners(id)) try { l.onError(id, error); } catch (Exception ignored) {}
        forget(id);
    }
    static void dispatchCancelled(String id, String partialText) {
        String partial = partialText == null ? "" : partialText;
        for (Listener l : listeners(id)) try { l.onCancelled(id, partial); } catch (Exception ignored) {}
        forget(id);
    }
    private static void forget(String id) {
        LISTENERS.remove(id);
        PARTIALS.remove(id);
        COMPLETION_LOCKS.remove(id);
    }
    private static List<Listener> listeners(String id) {
        CopyOnWriteArrayList<Listener> list = LISTENERS.get(id);
        return list == null ? java.util.Collections.emptyList() : list;
    }
}
