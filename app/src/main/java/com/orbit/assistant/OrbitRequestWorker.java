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
        if (PendingRequestStore.DONE.equals(item.status)) return Result.success();

        PendingRequestStore.markRunning(c, id);
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
            ConversationStore.appendMessage(c, item.conversationId, new AssistantClient.History("assistant", text));
            PendingRequestStore.markDone(c, id);
            AttachmentStore.delete(item.screenshotPath);
            OrbitRequestManager.dispatchSuccess(id, new AssistantReply(text, weatherReply.actions));
            if (Prefs.backgroundNotifications(c) && !UiPresence.isVisible()) {
                NotificationHelper.notifyResponseComplete(c, item.conversationId, item.prompt, text);
            }
            return Result.success();
        }

        RequestOutcome outcome = performRequest(c, item, screenshot, history,
                item.intelligenceMode, id, true, 4);

        // Hosted search can occasionally return a short-lived capacity error even
        // when ordinary ChatGPT requests are healthy. Voice and typed prompts now
        // use the exact same recovery path: retry the fresh-info request once on a
        // lighter model before surfacing an error to the user.
        if (outcome.reply == null && ChatGptClient.shouldOfferHostedWebSearch(item.prompt)
                && looksServerOverloaded(outcome.error)) {
            outcome = performRequest(c, item, screenshot, history,
                    Prefs.MODE_FAST, id, true, 2);
        }

        AssistantReply reply = outcome.reply;
        String error = outcome.error;
        if (reply != null) {
            String text = reply.text == null || reply.text.trim().isEmpty()
                    ? "Done."
                    : reply.text.trim().replace("—", "-");
            ConversationStore.appendMessage(c, item.conversationId,
                    new AssistantClient.History("assistant", text, false, "", "", "", "",
                            reply.memoryUsage, reply.suggestedMemoryText, reply.suggestedMemoryCategory));
            PendingRequestStore.markDone(c, id);
            AttachmentStore.delete(item.screenshotPath);
            OrbitRequestManager.dispatchSuccess(id, new AssistantReply(text, reply.actions,
                    reply.memoryUsage, reply.suggestedMemoryText, reply.suggestedMemoryCategory));
            if (Prefs.backgroundNotifications(c) && !UiPresence.isVisible()) {
                NotificationHelper.notifyResponseComplete(c, item.conversationId, item.prompt,
                        SourceLinkUtil.displayText(text));
            }
            return Result.success();
        }

        if (error == null) error = "Orbit could not finish this response.";
        String friendly = error.replace("—", "-");
        // Network/process/capacity disruptions get one durable retry before becoming
        // a visible chat error. Hosted-search overloads already received the quick
        // in-process fallback above, so this is the final safety net.
        if (getRunAttemptCount() < 1 && looksTransient(friendly)) return Result.retry();
        String visible = friendly.startsWith("Orbit could not finish")
                ? friendly
                : "Orbit could not finish this response: " + friendly;
        ConversationStore.appendMessage(c, item.conversationId, new AssistantClient.History("assistant", visible));
        PendingRequestStore.markFailed(c, id, visible);
        DiagnosticStore.recordError(c, visible);
        // Keep the pending screenshot temporarily so a visible Retry action can
        // recreate the failed request with the same context. Pending metadata is
        // pruned after seven days.
        OrbitRequestManager.dispatchError(id, visible);
        return Result.success();
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
                return new RequestOutcome(null, "Orbit timed out while waiting for ChatGPT.");
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
