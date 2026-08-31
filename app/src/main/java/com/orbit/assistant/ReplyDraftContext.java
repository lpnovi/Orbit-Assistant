package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conversation-scoped state for reply drafting against an attached screen.
 *
 * The important distinction is that this state is derived from the user's own
 * corrections (for example, "wrong person" or "I'm Lou"), not from untrusted
 * screen contents. It is therefore supplied to the model separately from the
 * screenshot/text attachment and remains stable across follow-up turns.
 */
public final class ReplyDraftContext {
    private static final String FILE = "orbit_reply_draft_context";
    private static final long MAX_AGE_MS = 6L * 60L * 60L * 1000L;

    public static final String CONVERSATION_DRAFT_PROMPT =
            "Draft the message I should send next in the conversation on my screen. " +
            "Write from my perspective as the phone owner/user, addressed to the other participant. " +
            "Never write the other participant's reply as though it were mine. " +
            "Use visible message direction, layout, labels, names, and conversation flow to determine which side is mine. " +
            "Do not send anything; just give me the draft. " +
            "If you cannot confidently determine which participant is me, ask me which participant I am instead of guessing.";

    public static final String EMAIL_DRAFT_PROMPT =
            "Draft the reply I should send to the email on my screen. " +
            "Write from my perspective as the phone owner/user, addressed to the sender or appropriate recipient. " +
            "Never write as the sender when the sender is the other person. " +
            "Do not send anything; just give me the draft. " +
            "If sender/recipient direction is genuinely unclear, ask me before guessing.";

    private static final Pattern REPLY_AS = Pattern.compile(
            "(?i)^(?:please\\s+)?(?:reply|respond|write|draft|compose)(?:\\s+(?:the\\s+)?(?:reply|response|message))?\\s+as(?:\\s+if\\s+you\\s+were)?\\s+(.{1,64}?)[.!?]*$");
    private static final Pattern NAME_IS = Pattern.compile(
            "(?i)^(?:actually\\s+)?(?:my\\s+name\\s+is|i['’]?m\\s+the\\s+person\\s+named|im\\s+the\\s+person\\s+named|i\\s+am\\s+the\\s+person\\s+named)\\s+(.{1,64}?)[.!?]*$");
    private static final Pattern SIMPLE_SELF = Pattern.compile(
            "(?i)^(?:actually\\s+)?(?:i['’]?m|im|i\\s+am)\\s+(.{1,64}?)[.!?]*$");

    private ReplyDraftContext() {}

    private static final class State {
        String identity = "";
        boolean perspectiveCorrection = false;
        String screenFingerprint = "";
        long updatedAt = 0L;
        /**
         * Orbit's last reply-draft turn was a question back to the user.
         *
         * <p>What makes the <em>answer</em> to that question still part of the drafting flow. "Im
         * neither im Lou and this is a gc" is not a draft request by any wording rule, and without
         * this the turn that finally produces the sendable draft would not be treated as one — which
         * is exactly what left the controls attached to the clarification on the device.
         */
        boolean awaitingClarification = false;
    }

    /** Observe a user turn and return the trusted context frozen for this request. */
    public static synchronized String observeAndGet(Context context, String conversationId,
                                                    String prompt, String screenText,
                                                    Bitmap screenshot,
                                                    List<AssistantClient.History> history) {
        if (context == null || conversationId == null || conversationId.trim().isEmpty()) return "";
        State state = load(context, conversationId);
        long now = System.currentTimeMillis();
        if (state.updatedAt > 0 && now - state.updatedAt > MAX_AGE_MS) state = new State();

        boolean hasAttachedScreen = (screenText != null && !screenText.trim().isEmpty()) || screenshot != null;
        if (hasAttachedScreen) {
            String fingerprint = fingerprint(screenText, screenshot);
            if (!state.screenFingerprint.isEmpty() && !fingerprint.isEmpty() &&
                    !state.screenFingerprint.equals(fingerprint)) {
                // A genuinely different attachment starts a new screen task. Do
                // not let a participant identity leak into the next conversation.
                state = new State();
            }
            if (!fingerprint.isEmpty()) state.screenFingerprint = fingerprint;
        }

        String text = prompt == null ? "" : prompt.trim();
        if (isPerspectiveCorrection(text)) state.perspectiveCorrection = true;

        String identity = extractStrongIdentity(text);
        if (identity.isEmpty() && state.perspectiveCorrection) {
            identity = extractSimpleSelfIdentity(text);
        }
        if (identity.isEmpty() && hasRecentDraftTask(history)) {
            String candidate = extractSimpleSelfIdentity(text);
            if (!candidate.isEmpty() && candidateAppearsOnScreen(candidate, screenText)) identity = candidate;
        }
        if (!identity.isEmpty()) {
            state.identity = identity;
            // Keep perspectiveCorrection=true. It is useful context that the
            // previous draft used the wrong side and must not simply be repeated.
        }

        // Whether this turn belongs to a reply-drafting flow at all: either the user asked for a
        // draft, or Orbit asked them something last time and this is the answer.
        boolean draftTurn = isDraftRequest(text) || state.awaitingClarification;
        if (draftTurn) state.updatedAt = now;

        if (!state.identity.isEmpty() || state.perspectiveCorrection
                || !state.screenFingerprint.isEmpty() || state.awaitingClarification) {
            state.updatedAt = now;
            save(context, conversationId, state);
        }
        String trusted = buildTrustedContext(state);
        if (!draftTurn) return trusted;
        // The classification contract rides along with the trusted context, so it is Orbit-authored,
        // per-request, and reaches every provider on the path the request already takes.
        return trusted.isEmpty()
                ? ReplyDraftOutcome.contractInstruction()
                : trusted + "\n\n" + ReplyDraftOutcome.contractInstruction();
    }

    /** True when this turn is part of a reply-drafting flow, request or clarification answer. */
    public static synchronized boolean isReplyDraftTurn(Context context, String conversationId,
                                                        String prompt) {
        if (isDraftRequest(prompt)) return true;
        return awaitingClarification(context, conversationId);
    }

    /** Whether Orbit's last reply-draft turn asked the user a question. */
    public static synchronized boolean awaitingClarification(Context context, String conversationId) {
        State state = load(context, conversationId);
        if (state.updatedAt > 0 && System.currentTimeMillis() - state.updatedAt > MAX_AGE_MS) {
            return false;
        }
        return state.awaitingClarification;
    }

    /**
     * Records what Orbit's reply-draft turn turned out to be.
     *
     * <p>Set when a clarification is shown, cleared the moment a sendable draft arrives, so the flow
     * extends exactly as far as the questions do and no further.
     */
    public static synchronized void recordOutcome(Context context, String conversationId,
                                                  ReplyDraftOutcome.Kind kind) {
        if (context == null || conversationId == null || conversationId.trim().isEmpty()) return;
        State state = load(context, conversationId);
        boolean awaiting = kind == ReplyDraftOutcome.Kind.CLARIFICATION;
        if (state.awaitingClarification == awaiting && state.updatedAt > 0) return;
        state.awaitingClarification = awaiting;
        state.updatedAt = System.currentTimeMillis();
        save(context, conversationId, state);
    }

    /** Return previously established context without changing it. */
    public static synchronized String get(Context context, String conversationId) {
        State state = load(context, conversationId);
        if (state.updatedAt > 0 && System.currentTimeMillis() - state.updatedAt > MAX_AGE_MS) {
            clear(context, conversationId);
            return "";
        }
        return buildTrustedContext(state);
    }

    public static synchronized void clear(Context context, String conversationId) {
        if (context == null || conversationId == null) return;
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(conversationId).apply();
    }

    public static boolean isDraftRequest(String prompt) {
        if (prompt == null) return false;
        String lower = prompt.toLowerCase(Locale.US).trim();
        if (lower.isEmpty()) return false;
        if (lower.contains("reply as if") || lower.startsWith("reply as ") ||
                lower.contains("respond as if") || lower.startsWith("respond as ")) return true;
        boolean draft = lower.contains("draft") || lower.contains("write") ||
                lower.contains("compose") || lower.contains("suggest");
        boolean reply = lower.contains("reply") || lower.contains("response") ||
                lower.contains("message back") || lower.contains("respond");
        boolean context = lower.contains("conversation") || lower.contains("screen") ||
                lower.contains("message") || lower.contains("chat") || lower.contains("email");
        return draft && reply && context;
    }

    public static boolean hasRecentDraftTask(List<AssistantClient.History> history) {
        if (history == null || history.isEmpty()) return false;
        int start = Math.max(0, history.size() - 8);
        for (int i = history.size() - 1; i >= start; i--) {
            AssistantClient.History item = history.get(i);
            if (item == null || !"user".equalsIgnoreCase(item.role)) continue;
            if (isDraftRequest(item.content)) return true;
        }
        return false;
    }

    private static boolean isPerspectiveCorrection(String prompt) {
        String lower = prompt == null ? "" : prompt.toLowerCase(Locale.US)
                .replace('’', '\'').trim();
        return lower.contains("wrong person") || lower.contains("wrong side") ||
                lower.contains("wrong perspective") || lower.contains("other person") ||
                lower.contains("you replied as them") || lower.contains("you responded as them") ||
                lower.contains("you wrote as them") || lower.contains("that's not me") ||
                lower.contains("thats not me") || lower.contains("not my side") ||
                lower.contains("you replied for the wrong person");
    }

    private static String extractStrongIdentity(String prompt) {
        if (prompt == null) return "";
        Matcher replyAs = REPLY_AS.matcher(prompt.trim());
        if (replyAs.matches()) return sanitizeIdentity(replyAs.group(1));
        Matcher nameIs = NAME_IS.matcher(prompt.trim());
        if (nameIs.matches()) return sanitizeIdentity(nameIs.group(1));
        return "";
    }

    private static String extractSimpleSelfIdentity(String prompt) {
        if (prompt == null) return "";
        Matcher m = SIMPLE_SELF.matcher(prompt.trim());
        if (!m.matches()) return "";
        return sanitizeIdentity(m.group(1));
    }

    private static String sanitizeIdentity(String raw) {
        if (raw == null) return "";
        String value = raw.replace('\n', ' ').replace('\r', ' ').trim();
        value = value.replaceAll("\\s+", " ");
        // Participant labels need only ordinary name/descriptor characters. This
        // also prevents arbitrary multi-line text from being promoted into the
        // trusted task-state instruction.
        value = value.replaceAll("[^\\p{L}\\p{N} .,'’_\\-]", "").trim();
        if (value.length() > 48) value = value.substring(0, 48).trim();
        return value;
    }

    private static boolean candidateAppearsOnScreen(String candidate, String screenText) {
        if (candidate == null || candidate.isEmpty() || screenText == null || screenText.isEmpty()) return false;
        String c = candidate.toLowerCase(Locale.US);
        String screen = screenText.toLowerCase(Locale.US);
        if (screen.contains(c)) return true;
        // Positional/visual self-identifiers are also valid even if OCR phrases
        // them slightly differently from the user's correction.
        return c.contains("right") || c.contains("left") || c.contains("blue message") ||
                c.contains("purple message") || c.contains("green message") ||
                c.contains("my messages") || c.contains("phone owner");
    }

    private static String buildTrustedContext(State state) {
        if (state == null) return "";
        StringBuilder out = new StringBuilder();
        if (state.perspectiveCorrection) {
            out.append("The user explicitly said that a previous reply draft used the wrong participant perspective. ")
                    .append("Treat that correction as authoritative. Re-evaluate which visible messages belong to the user, ")
                    .append("and do not repeat or lightly rephrase the previous wrong-perspective draft. ");
        }
        if (!state.identity.isEmpty()) {
            out.append("For this current attached-screen reply task, the user explicitly identified their own participant as '")
                    .append(state.identity.replace("'", "’"))
                    .append("'. Treat that participant identity as authoritative. When drafting a reply, write what '")
                    .append(state.identity.replace("'", "’"))
                    .append("' (the user/phone owner) should send next to the other participant. Never draft the other participant's reply as the user's message. ");
        } else if (state.perspectiveCorrection) {
            out.append("If the user's side still cannot be determined confidently from the attached screen, ask which participant the user is instead of guessing. ");
        }
        return out.toString().trim();
    }

    private static String fingerprint(String screenText, Bitmap screenshot) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String normalized = screenText == null ? "" : screenText.replaceAll("\\s+", " ").trim();
            if (!normalized.isEmpty()) {
                if (normalized.length() > 16000) normalized = normalized.substring(0, 16000);
                digest.update(normalized.getBytes(StandardCharsets.UTF_8));
            } else if (screenshot != null && screenshot.getWidth() > 0 && screenshot.getHeight() > 0) {
                digest.update((screenshot.getWidth() + "x" + screenshot.getHeight()).getBytes(StandardCharsets.UTF_8));
                int[] xs = {1, 2, 3, 4, 5};
                int[] ys = {1, 2, 3, 4, 5};
                for (int y : ys) {
                    for (int x : xs) {
                        int px = Math.min(screenshot.getWidth() - 1, Math.max(0, screenshot.getWidth() * x / 6));
                        int py = Math.min(screenshot.getHeight() - 1, Math.max(0, screenshot.getHeight() * y / 6));
                        int color = screenshot.getPixel(px, py);
                        digest.update((byte) (color >> 24));
                        digest.update((byte) (color >> 16));
                        digest.update((byte) (color >> 8));
                        digest.update((byte) color);
                    }
                }
            } else {
                return "";
            }
            byte[] bytes = digest.digest();
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 10 && i < bytes.length; i++) b.append(String.format(Locale.US, "%02x", bytes[i] & 0xff));
            return b.toString();
        } catch (Exception ignored) {
            String fallback = screenText == null ? "" : screenText.trim();
            return fallback.isEmpty() ? "" : Integer.toHexString(fallback.hashCode());
        }
    }

    private static State load(Context context, String conversationId) {
        State state = new State();
        if (context == null || conversationId == null || conversationId.isEmpty()) return state;
        try {
            SharedPreferences p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
            String raw = p.getString(conversationId, "");
            if (raw == null || raw.isEmpty()) return state;
            JSONObject o = new JSONObject(raw);
            state.identity = o.optString("identity", "");
            state.perspectiveCorrection = o.optBoolean("perspectiveCorrection", false);
            state.screenFingerprint = o.optString("screenFingerprint", "");
            state.updatedAt = o.optLong("updatedAt", 0L);
            state.awaitingClarification = o.optBoolean("awaitingClarification", false);
        } catch (Exception ignored) {}
        return state;
    }

    private static void save(Context context, String conversationId, State state) {
        try {
            JSONObject o = new JSONObject()
                    .put("identity", state.identity)
                    .put("perspectiveCorrection", state.perspectiveCorrection)
                    .put("screenFingerprint", state.screenFingerprint)
                    .put("awaitingClarification", state.awaitingClarification)
                    .put("updatedAt", state.updatedAt);
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                    .putString(conversationId, o.toString()).apply();
        } catch (Exception ignored) {}
    }
}
