package com.orbit.assistant;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.CopyOnWriteArrayList;

/** Owns automatic-title scheduling and the small live-update signal for visible chat surfaces. */
final class ConversationTitleManager {
    interface Listener { void onTitleChanged(String conversationId, String title); }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private ConversationTitleManager() {}

    /** Called only after a successful assistant completion has been durably appended. */
    static void onSuccessfulExchange(Context context, String conversationId, String requestId) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        ConversationStore.TitleJob job = ConversationStore.beginAutomaticTitle(
                app, conversationId, requestId);
        if (job == null) return;

        boolean cloud = job.conversationSelection != null
                && Prefs.PROVIDER_CHATGPT.equals(job.conversationSelection.provider)
                && ChatGptAuth.isSignedIn(app);
        Data input = new Data.Builder()
                .putString(ConversationTitleWorker.KEY_CONVERSATION_ID, job.conversationId)
                .putString(ConversationTitleWorker.KEY_TOKEN, job.token)
                .putString(ConversationTitleWorker.KEY_USER, clip(job.firstUserMessage, 1200))
                .putString(ConversationTitleWorker.KEY_ASSISTANT, clip(job.firstAssistantResponse, 1200))
                .putBoolean(ConversationTitleWorker.KEY_USE_CHATGPT, cloud)
                .build();
        Constraints constraints = new Constraints.Builder()
                .build();
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(ConversationTitleWorker.class)
                .setInputData(input)
                .setConstraints(constraints)
                .addTag("orbit-conversation-title")
                .build();
        try {
            WorkManager.getInstance(app).enqueueUniqueWork("orbit-title-" + conversationId,
                    ExistingWorkPolicy.KEEP, work);
        } catch (Throwable unavailable) {
            // WorkManager can be unavailable in a stripped test host. Title failure is non-critical;
            // the deterministic local result still closes the pending state without touching chat.
            commit(app, job.conversationId, job.token,
                    ConversationTitlePolicy.fallback(job.firstUserMessage));
        }
    }

    static boolean commit(Context context, String conversationId, String token, String title) {
        if (!ConversationStore.applyAutomaticTitle(context, conversationId, token, title)) return false;
        for (Listener listener : LISTENERS) {
            try { listener.onTitleChanged(conversationId, title); } catch (Exception ignored) {}
        }
        return true;
    }

    static void addListener(Listener listener) {
        if (listener != null) LISTENERS.addIfAbsent(listener);
    }

    static void removeListener(Listener listener) {
        if (listener != null) LISTENERS.remove(listener);
    }

    static void resetForTest() { LISTENERS.clear(); }

    private static String clip(String value, int max) {
        String s = value == null ? "" : value;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
