package com.orbit.assistant;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Executes an Orbit AI turn independently of any visible Activity or assistant sheet. */
public final class OrbitRequestWorker extends Worker {
    public static final String KEY_REQUEST_ID = "request_id";

    private static final class RequestOutcome {
        final AssistantReply reply;
        final String error;

        RequestOutcome(AssistantReply reply, String error) {
            this.reply = reply;
            this.error = error;
        }
    }

    public OrbitRequestWorker(@NonNull Context appContext, @NonNull WorkerParameters params) {
        super(appContext, params);
    }

    @NonNull @Override public Result doWork() {
        String id = getInputData().getString(KEY_REQUEST_ID);
        if (id == null || id.isEmpty()) return Result.failure();
        Context c = getApplicationContext();
        PendingRequestStore.Item item = PendingRequestStore.load(c, id);
        if (item == null) return Result.success();
        // Covers a request that already finished and one the user stopped before this ran at all.
        if (PendingRequestStore.isTerminal(item.status)) return Result.success();
        // And the case WorkManager creates on its own: this worker was re-run after the process
        // died, and the turn's one completion has already been claimed. Asking the model again
        // here is exactly how one visible user message ended up with two similar answers.
        if (item.committed) {
            RequestTrace.lifecycle(id, "rerun-abandoned");
            return Result.success();
        }
        if (cancelled(c, id)) return Result.success();

        PendingRequestStore.markRunning(c, id);
        RequestTrace.lifecycle(id, "running");
        OrbitRequestManager.dispatchStarted(id);

        ConversationStore.Conversation chat = ConversationStore.load(c, item.conversationId);
        List<AssistantClient.History> history = chat == null ? new ArrayList<>() : new ArrayList<>(chat.messages);
        Bitmap screenshot = AttachmentStore.load(item.screenshotPath);

        // Handle deterministic weather requests before invoking the language model.
        // This keeps weather fast, current, and available directly inside Orbit chat.
        if (WeatherService.shouldHandle(item.prompt, history)) {
            AssistantReply weatherReply = WeatherService.handle(c, item.prompt, history);
            String text = weatherReply.text == null || weatherReply.text.trim().isEmpty()
                    ? "I couldn't load the weather right now."
                    : weatherReply.text.trim().replace("—", "-");
            commit(c, item, id, new AssistantClient.History("assistant", text),
                    new AssistantReply(text, weatherReply.actions), text);
            return Result.success();
        }

        RequestOutcome outcome = performRequest(c, item, screenshot, history,
                item.intelligenceMode, id, true, 4);
        if (cancelled(c, id)) return Result.success();

        // Hosted search can occasionally return a short-lived capacity error even
        // when ordinary ChatGPT requests are healthy. Voice and typed prompts now
        // use the exact same recovery path: retry the fresh-info request once on a
        // lighter model before surfacing an error to the user. This is ChatGPT-specific
        // recovery, so it only runs when the active provider actually offers hosted search.
        if (outcome.reply == null && AiProviders.active(c).capabilities().hostedWebSearch
                && ChatGptClient.shouldOfferHostedWebSearch(item.prompt)
                && looksServerOverloaded(outcome.error)) {
            outcome = performRequest(c, item, screenshot, history,
                    Prefs.MODE_FAST, id, true, 2);
            if (cancelled(c, id)) return Result.success();
        }

        AssistantReply reply = outcome.reply;
        String error = outcome.error;
        if (reply != null) {
            String text = reply.text == null || reply.text.trim().isEmpty()
                    ? "Done."
                    : reply.text.trim().replace("—", "-");
            commit(c, item, id,
                    new AssistantClient.History("assistant", text, false, "", "", "", "",
                            reply.memoryUsage, reply.suggestedMemoryText, reply.suggestedMemoryCategory),
                    new AssistantReply(text, reply.actions, reply.memoryUsage,
                            reply.suggestedMemoryText, reply.suggestedMemoryCategory),
                    SourceLinkUtil.displayText(text));
            return Result.success();
        }

        if (error == null) error = "Orbit could not finish this response.";
        String friendly = error.replace("—", "-");
        // Network/process/capacity disruptions get one durable retry before becoming
        // a visible chat error. Hosted-search overloads already received the quick
        // in-process fallback above, so this is the final safety net.
        if (getRunAttemptCount() < 1 && looksTransient(friendly)) {
            return cancelled(c, id) ? Result.success() : Result.retry();
        }
        String visible = friendly.startsWith("Orbit could not finish")
                ? friendly
                : "Orbit could not finish this response: " + friendly;
        // Stopping a request is not a failure. Cancelling interrupts this worker, which surfaces
        // here as an ordinary error, so the same gate the success path uses decides whether
        // anything visible gets written at all.
        OrbitRequestManager.completeIfNotCancelled(c, id, () -> {
            ConversationStore.appendMessage(c, item.conversationId, new AssistantClient.History("assistant", visible));
            PendingRequestStore.markFailed(c, id, visible);
            DiagnosticStore.recordError(c, visible);
            // Keep the pending screenshot temporarily so a visible Retry action can
            // recreate the failed request with the same context. Pending metadata is
            // pruned after seven days.
            OrbitRequestManager.dispatchError(id, visible);
        });
        return Result.success();
    }

    /**
     * Finishes a request for good: persists the answer, marks it done, tells any visible surface,
     * and only then lets background completion run.
     *
     * <p>All of it happens under the request's completion lock, so this either wins outright
     * against a Stop the user is tapping right now or finds the request already cancelled and does
     * nothing at all. Because the visible dispatch is inside the lock too, a cancelled request can
     * never reach a listener, which is what keeps response actions, the completion notification,
     * and a spoken reply from running for an answer the user stopped waiting for.
     */
    private void commit(Context c, PendingRequestStore.Item item, String id,
                        AssistantClient.History message, AssistantReply reply, String notificationText) {
        OrbitRequestManager.completeIfNotCancelled(c, id, () -> {
            ConversationStore.appendMessage(c, item.conversationId, message);
            PendingRequestStore.markDone(c, id);
            RequestTrace.lifecycle(id, "completed");
            AttachmentStore.delete(item.screenshotPath);
            OrbitRequestManager.dispatchSuccess(id, reply);
            if (Prefs.backgroundNotifications(c) && !UiPresence.isVisible()) {
                NotificationHelper.notifyResponseComplete(c, item.conversationId, item.prompt, notificationText);
            }
        });
    }

    /** True once the user's Stop has been accepted, in this process or a previous one. */
    private boolean cancelled(Context c, String id) {
        return OrbitRequestManager.isCancelled(c, id);
    }

    private RequestOutcome performRequest(Context c, PendingRequestStore.Item item,
                                          Bitmap screenshot, List<AssistantClient.History> history,
                                          String mode, String requestId,
                                          boolean streamDeltas, int timeoutMinutes) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AssistantReply> replyRef = new AtomicReference<>();
        AtomicReference<String> errorRef = new AtomicReference<>();

        AssistantClient.send(c, item.prompt, item.screenText, screenshot, history,
                mode, item.explicitAttachment, item.trustedTaskContext,
                () -> isStopped() || OrbitRequestManager.isCancelled(c, requestId),
                new AssistantClient.Callback() {
                    @Override public void onDelta(String text) {
                        if (streamDeltas && text != null && !text.isEmpty()) {
                            OrbitRequestManager.dispatchDelta(requestId, text);
                        }
                    }

                    @Override public void onSuccess(AssistantReply reply) {
                        replyRef.set(reply);
                        latch.countDown();
                    }

                    @Override public void onError(String message) {
                        errorRef.set(message == null ? "Orbit could not finish this response." : message);
                        latch.countDown();
                    }
                });

        try {
            if (!latch.await(timeoutMinutes, TimeUnit.MINUTES)) {
                return new RequestOutcome(null, "Orbit timed out while waiting for "
                        + AiProviders.active(c).displayName() + ".");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new RequestOutcome(null, "Orbit request was interrupted.");
        }
        return new RequestOutcome(replyRef.get(), errorRef.get());
    }

    private boolean looksServerOverloaded(String s) {
        String lower = s == null ? "" : s.toLowerCase(Locale.US);
        return lower.contains("overloaded") || lower.contains("server busy") ||
                lower.contains("temporarily unavailable") || lower.contains("capacity");
    }

    private boolean looksTransient(String s) {
        String lower = s == null ? "" : s.toLowerCase(Locale.US);
        return lower.contains("could not reach") || lower.contains("request failed") ||
                lower.contains("timeout") || lower.contains("timed out") ||
                lower.contains("network") || lower.contains("connection") ||
                lower.contains("overloaded") || lower.contains("temporarily unavailable") ||
                lower.contains("server busy");
    }
}
