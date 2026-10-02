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

    /** What this execution sends from the chat's kept context. Set once per execution. */
    private KeptContext.Prepared kept = KeptContext.Prepared.NONE;

    public OrbitRequestWorker(@NonNull Context appContext, @NonNull WorkerParameters params) {
        super(appContext, params);
    }

    @NonNull @Override public Result doWork() {
        String id = getInputData().getString(KEY_REQUEST_ID);
        if (id == null || id.isEmpty()) return Result.failure();
        Context c = getApplicationContext();
        PendingRequestStore.Item item = PendingRequestStore.load(c, id);
        RequestTrace.workerStarted(id, attempt(), state(item));
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

        // An earlier execution of this same request may still be alive in this process. WorkManager
        // never runs one unique work item twice at once, so if the claim is already held it can
        // only mean that execution was stopped and its thread has not returned yet — and for a
        // cloud provider it will not return until its HTTP response arrives, because nothing
        // cancels it. It still holds a live model request and will finish it, so standing down
        // here is what stops one user turn being asked of the model twice.
        if (!OrbitRequestManager.beginWorkerAttempt(id)) {
            RequestTrace.attemptSuperseded(c, id, attempt(), RequestTrace.STAGE_ALREADY_RUNNING);
            // Retry, never success: if the execution in flight dies with the process instead of
            // committing, this reschedule is what still answers the request.
            return Result.retry();
        }
        try {
            return execute(c, item, id);
        } finally {
            OrbitRequestManager.endWorkerAttempt(id);
        }
    }

    /** The body of one execution, with the in-process claim above held for all of it. */
    private Result execute(Context c, PendingRequestStore.Item item, String id) {
        // Stopped before a single question was asked. WorkManager has already re-enqueued this
        // work, so anything done from here is thrown away; asking the model would only be paying
        // twice for the answer the next execution is about to fetch.
        if (superseded()) {
            RequestTrace.attemptSuperseded(c, id, attempt(), RequestTrace.STAGE_BEFORE_REQUEST);
            return Result.retry();
        }

        PendingRequestStore.markRunning(c, id);
        RequestTrace.lifecycle(id, "running");
        OrbitRequestManager.dispatchStarted(id);

        ConversationStore.Conversation chat = ConversationStore.load(c, item.conversationId);
        List<AssistantClient.History> history = chat == null ? new ArrayList<>() : new ArrayList<>(chat.messages);
        if (item.isAnswerVariant()) {
            // A retry is asked of the conversation as it stood before the answer being retried,
            // so neither that answer nor anything after it reaches the model.
            int at = item.variantAt();
            if (at > history.size()
                    || !ConversationBranches.parentKey(history, at).equals(item.variantParent())) {
                failVariant(c, item, id, "This chat changed before the retry could run.");
                return Result.success();
            }
            history = new ArrayList<>(history.subList(0, at));
        }
        // Read with the history, at the same moment and from the same record: what the user kept
        // in this chat is sent with this turn. See KeptContext.
        kept = keptFor(chat, history);
        List<Bitmap> images = AttachmentStore.loadAll(item.screenshotPaths);

        // Handle deterministic weather requests before invoking the language model.
        // This keeps weather fast, current, and available directly inside Orbit chat.
        if (WeatherService.shouldHandle(item.prompt, history)) {
            AssistantReply weatherReply = WeatherService.handle(c, item.prompt, history);
            String text = weatherReply.text == null || weatherReply.text.trim().isEmpty()
                    ? "I couldn't load the weather right now."
                    : weatherReply.text.trim().replace("—", "-");
            commit(c, item, id, new AssistantClient.History("assistant", text),
                    new AssistantReply(text, weatherReply.actions), text, attempt());
            return Result.success();
        }

        RequestOutcome outcome = performRequest(c, item, images, history,
                item.selection, id, true, 4);
        if (cancelled(c, id)) return Result.success();

        // Hosted search can occasionally return a short-lived capacity error even when ordinary
        // ChatGPT requests are healthy, so a fresh-info request gets one quick second attempt.
        // Same provider, same model, same strength: before 0.8.3.0 this retried on a lighter
        // model, which answered under the name of a model the user had not chosen.
        if (outcome.reply == null
                && AiProviders.forSelection(c, item.selection).capabilities().hostedWebSearch
                && ChatGptClient.shouldOfferHostedWebSearch(item.prompt)
                && looksServerOverloaded(outcome.error)) {
            outcome = performRequest(c, item, images, history,
                    item.selection, id, true, 2);
            if (cancelled(c, id)) return Result.success();
        }

        AssistantReply reply = outcome.reply;
        String error = outcome.error;
        if (reply != null) {
            completeProviderReply(c, item, reply, attempt());
            return Result.success();
        }

        if (error == null) error = "Orbit could not finish this response.";
        String friendly = error.replace("—", "-");

        // A stopped execution's "error" may be nothing but the stop itself. The cancellation
        // signal Orbit hands the provider is `isStopped() || user pressed Stop`, and Orbit Local
        // honours it by cancelling generation, which comes back here as an ordinary failure.
        // Writing that as a visible answer would be wrong twice over: the request has not failed,
        // and the write would claim its one completion and pre-empt the execution WorkManager has
        // already scheduled to replace this one.
        if (superseded()) {
            RequestTrace.attemptSuperseded(c, id, attempt(), RequestTrace.STAGE_ERROR_DISCARDED);
            return Result.retry();
        }

        // Network/process/capacity disruptions get one durable retry before becoming a visible
        // chat error. Hosted-search overloads already received the quick in-process fallback
        // above, so this is the final safety net. The count is a start count rather than a retry
        // count (see WorkerAttempt), so this reads as "only a first execution may ask for one
        // more" — deliberately conservative: a request that has already been restarted for any
        // reason does not accumulate extra retries on top.
        if (getRunAttemptCount() < 1 && looksTransient(friendly)) {
            if (cancelled(c, id)) return Result.success();
            RequestTrace.retryRequested(id, attempt(), "transient");
            return Result.retry();
        }
        String visible = friendly.startsWith("Orbit could not finish")
                ? friendly
                : "Orbit could not finish this response: " + friendly;
        // Stopping a request is not a failure. Cancelling interrupts this worker, which surfaces
        // here as an ordinary error, so the same gate the success path uses decides whether
        // anything visible gets written at all.
        if (item.isAnswerVariant()) {
            // A failed retry writes nothing into the conversation: the answer it was asked beside
            // is still there, which is the whole point of keeping variants.
            failVariant(c, item, id, visible);
            return Result.success();
        }
        OrbitRequestManager.completeIfNotCancelled(c, id, CompletionSource.WORKER_ERROR,
                attempt(), () -> {
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
     *
     * <p>Deliberately not refused when this execution has been stopped. A model reply already in
     * hand is a correct answer to this request id, and the completion claim guarantees at most one
     * of them is ever written; throwing a paid-for answer away to honour an abstract notion of
     * obsolescence would only make the user wait for the same answer twice. What Beta 4 removes is
     * the overlap that produced two replies in the first place, not the right of whichever
     * execution holds one to write it.
     */
    static void completeProviderReply(Context c, PendingRequestStore.Item item,
                                      AssistantReply reply, WorkerAttempt attempt) {
        String text = reply.text.trim().isEmpty() ? "Done." : reply.text.trim().replace("—", "-");
        commit(c, item, item.id,
                new AssistantClient.History("assistant", text, false, "", "", "", "",
                        reply.memoryUsage, reply.suggestedMemoryText, reply.suggestedMemoryCategory).withDetails(reply.details),
                reply.withText(text), SourceLinkUtil.displayText(text), attempt);
    }

    private static void commit(Context c, PendingRequestStore.Item item, String id,
                        AssistantClient.History message, AssistantReply reply, String notificationText,
                        WorkerAttempt attempt) {
        RequestTrace.responseReady(id, attempt, state(PendingRequestStore.load(c, id)));
        OrbitRequestManager.completeIfNotCancelled(c, id, CompletionSource.WORKER_RESPONSE,
                attempt, () -> {
            if (item.isAnswerVariant()) {
                boolean stored = ConversationStore.commitAnswerVariant(c, item.conversationId,
                        item.variantAt(), item.variantParent(),
                        message.withReplyProvenance(id, reply.sourceUrls));
                if (!stored) {
                    // The chat moved on (cleared, edited, or already at its version limit) while
                    // the retry ran. Nothing is written over it; the retry reports that it failed.
                    String why = "Orbit could not keep this retry because the chat changed.";
                    PendingRequestStore.markFailed(c, id, why);
                    AttachmentStore.deleteAll(item.screenshotPaths);
                    OrbitRequestManager.dispatchError(id, why);
                    return;
                }
            } else {
                ConversationStore.appendMessage(c, item.conversationId,
                        message.withReplyProvenance(id, reply.sourceUrls));
            }
            PendingRequestStore.markDone(c, id);
            RequestTrace.lifecycle(id, "completed");
            AttachmentStore.deleteAll(item.screenshotPaths);
            OrbitRequestManager.dispatchSuccess(id, reply);
            // Metadata work begins only after the answer is persisted and delivered. It uses its
            // own fixed lightweight policy and never enters visible history or response details.
            ConversationTitleManager.onSuccessfulExchange(c, item.conversationId, id);
            // Started here and nowhere else, which is what subordinates a picture to the request
            // that earned it: this runs only for a completion that won the claim, so a stopped or
            // superseded execution never begins a lookup, and a picture can never arrive for an
            // answer that was not written. It returns immediately; the answer is already delivered.
            RichAnswerCoordinator.discover(c, item.conversationId, id, item.prompt, reply);
            if (Prefs.backgroundNotifications(c) && !UiPresence.isVisible()) {
                NotificationHelper.notifyResponseComplete(c, item.conversationId, item.prompt, notificationText);
            }
        });
    }

    /** Reports a retry that produced no answer, writing nothing into the conversation. */
    private void failVariant(Context c, PendingRequestStore.Item item, String id, String visible) {
        OrbitRequestManager.completeIfNotCancelled(c, id, CompletionSource.WORKER_ERROR,
                attempt(), () -> {
            PendingRequestStore.markFailed(c, id, visible);
            DiagnosticStore.recordError(c, visible);
            AttachmentStore.deleteAll(item.screenshotPaths);
            OrbitRequestManager.dispatchError(id, visible);
        });
    }

    /**
     * The kept-context part of this request, read from the same conversation record as its history.
     * The message being answered is the last user message in that history.
     */
    static KeptContext.Prepared keptFor(ConversationStore.Conversation chat,
                                        List<AssistantClient.History> history) {
        if (chat == null || chat.kept.isEmpty()) return KeptContext.Prepared.NONE;
        String current = "";
        if (history != null) {
            for (int i = history.size() - 1; i >= 0; i--) {
                AssistantClient.History h = history.get(i);
                if (h != null && "user".equalsIgnoreCase(h.role)) {
                    current = ConversationBranches.fingerprint(h);
                    break;
                }
            }
        }
        return KeptContext.prepare(chat.kept, current);
    }

    /** True once the user's Stop has been accepted, in this process or a previous one. */
    private boolean cancelled(Context c, String id) {
        return OrbitRequestManager.isCancelled(c, id);
    }

    /**
     * True once WorkManager has taken this execution's turn away and given it to another one.
     *
     * <p>Distinct from {@link #cancelled}, and the distinction is the whole point. A cancelled
     * request is finished and must produce nothing; a superseded execution is a live request whose
     * work has been handed to a replacement, so it must produce nothing <em>here</em> while the
     * request itself goes on. Conflating the two is how a stopped execution came to write a
     * visible failure for a request that had not failed.
     *
     * <p>Stopping does not end the thread: WorkManager sets this flag, re-enqueues the work and
     * starts the next execution, and {@code doWork()} keeps running until it returns of its own
     * accord. Everything past this point is therefore checked rather than assumed.
     */
    private boolean superseded() {
        return isStopped();
    }

    /** What WorkManager can say about this execution, for the trace and the completion gate. */
    private WorkerAttempt attempt() {
        return WorkerAttempt.of(getRunAttemptCount(), isStopped());
    }

    /** A request's state as one diagnostics token, with the completion claim folded in. */
    private static String state(PendingRequestStore.Item item) {
        if (item == null) return "missing";
        return item.committed ? "committed-" + item.status : item.status;
    }

    private RequestOutcome performRequest(Context c, PendingRequestStore.Item item,
                                          List<Bitmap> images, List<AssistantClient.History> history,
                                          AiSelection selection, String requestId,
                                          boolean streamDeltas, int timeoutMinutes) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AssistantReply> replyRef = new AtomicReference<>();
        AtomicReference<String> errorRef = new AtomicReference<>();
        // Measured on this phone from hand-off to finished answer: what Response details calls
        // response time. Never presented as the backend's own processing time.
        final long startedAt = android.os.SystemClock.elapsedRealtime();

        AssistantClient.send(c, item.prompt, item.screenText, images, history,
                selection, item.explicitAttachment, item.trustedTaskContext,
                kept,
                () -> isStopped() || OrbitRequestManager.isCancelled(c, requestId),
                new AssistantClient.Callback() {
                    /**
                     * True once any answer text has streamed. A status update after that point is
                     * dropped here rather than at the UI, so no surface has to defend itself
                     * against a summary that arrives beside an answer already on screen.
                     */
                    private boolean answerStarted = false;
                    private boolean showedStatus = false;

                    @Override public void onDelta(String text) {
                        if (streamDeltas && text != null && !text.isEmpty()) {
                            if (!answerStarted && showedStatus) {
                                ReasoningSummarySupport.recordHandoff(c, true);
                            }
                            answerStarted = true;
                            OrbitRequestManager.dispatchDelta(requestId, text);
                        }
                    }

                    @Override public void onThinking(ThinkingUpdate update) {
                        // Tied to the same switch as the deltas: an execution whose output is not
                        // being shown has no business narrating itself either.
                        if (!streamDeltas || update == null || answerStarted) return;
                        // Written pessimistically at the first status and corrected to true only
                        // if answer text actually follows, so the diagnostic reads "did this end
                        // in an answer" rather than "did it start".
                        if (!showedStatus) ReasoningSummarySupport.recordHandoff(c, false);
                        showedStatus = true;
                        ReasoningSummarySupport.recordDisplayed(c, update);
                        // Observational only: the manager forwards this to whatever is watching
                        // and does nothing else with it. The request's own lifecycle - its
                        // execution claim, its completion claim, its retries - is untouched.
                        OrbitRequestManager.dispatchThinking(requestId, update);
                    }

                    @Override public void onSuccess(AssistantReply reply) {
                        long elapsed = android.os.SystemClock.elapsedRealtime() - startedAt;
                        // Every provider records what it sent with; a reply without that record was
                        // produced by Orbit itself (a calculation, a device command), which is what
                        // an empty provider and model say.
                        ResponseDetails details = reply == null || reply.details == null
                                ? new ResponseDetails("", "", "", elapsed) : reply.details.withElapsed(elapsed);
                        replyRef.set(reply == null ? null : reply.withDetails(details));
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
                        + AiProviders.forSelection(c, selection).displayName() + ".");
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
