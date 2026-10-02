package com.orbit.assistant;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Executes one invisible, non-critical conversation-title request. */
public final class ConversationTitleWorker extends Worker {
    static final String KEY_CONVERSATION_ID = "conversation_id";
    static final String KEY_TOKEN = "title_token";
    static final String KEY_USER = "first_user";
    static final String KEY_ASSISTANT = "first_assistant";
    static final String KEY_USE_CHATGPT = "use_chatgpt";

    public ConversationTitleWorker(@NonNull Context appContext, @NonNull WorkerParameters params) {
        super(appContext, params);
    }

    @NonNull @Override public Result doWork() {
        Context app = getApplicationContext();
        String conversationId = getInputData().getString(KEY_CONVERSATION_ID);
        String token = getInputData().getString(KEY_TOKEN);
        String user = getInputData().getString(KEY_USER);
        String assistant = getInputData().getString(KEY_ASSISTANT);
        if (conversationId == null || token == null || user == null || assistant == null) {
            return Result.success();
        }

        ConversationStore.Conversation current = ConversationStore.load(app, conversationId);
        if (current == null || !ConversationStore.TITLE_DEFAULT.equals(current.titleOwner)
                || !ConversationStore.TITLE_JOB_PENDING.equals(current.titleJobState)
                || !token.equals(current.titleJobToken)) return Result.success();

        String title = "";
        if (getInputData().getBoolean(KEY_USE_CHATGPT, false) && ChatGptAuth.isSignedIn(app)) {
            title = generateWithChatGpt(app, user, assistant);
        }
        title = ConversationTitlePolicy.normalizeGenerated(title);
        if (title.isEmpty()) title = ConversationTitlePolicy.fallback(user);
        ConversationTitleManager.commit(app, conversationId, token, title);
        return Result.success();
    }

    private String generateWithChatGpt(Context context, String user, String assistant) {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> answer = new AtomicReference<>("");
        ChatGptClient.complete(context, ConversationTitlePolicy.INSTRUCTIONS,
                ConversationTitlePolicy.prompt(user, assistant),
                ConversationTitlePolicy.CHATGPT_TITLE_SELECTION,
                new AssistantClient.PlanCallback() {
                    @Override public void onText(String text, String providerLabel) {
                        answer.set(text == null ? "" : text);
                        done.countDown();
                    }
                    @Override public void onError(String message) { done.countDown(); }
                });
        try { done.await(60, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        return answer.get();
    }
}
