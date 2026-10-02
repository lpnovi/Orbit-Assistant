package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Small on-device conversation archive. Nothing is synced by Orbit itself.
 * Conversations are stored in the app's private SharedPreferences as JSON.
 */
public final class ConversationStore {
    private static final String FILE = "orbit_conversations";
    private static final String KEY = "items_v1";
    private static final int MAX_CONVERSATIONS = 100;
    private static final int MAX_MESSAGES_PER_CHAT = 40;
    private static final int MAX_MESSAGE_CHARS = 12000;
    public static final String NEW_CHAT_TITLE = "New Chat";
    static final String TITLE_DEFAULT = "default";
    static final String TITLE_AUTOMATIC = "automatic";
    static final String TITLE_MANUAL = "manual";
    static final String TITLE_LEGACY = "legacy";
    static final String TITLE_JOB_IDLE = "idle";
    static final String TITLE_JOB_PENDING = "pending";
    static final String TITLE_JOB_DONE = "done";

    private ConversationStore() {}

    public static final class Conversation {
        public final String id;
        public final String title;
        public final long updatedAt;
        public final List<AssistantClient.History> messages;
        /** Empty means this chat follows the current global default until the user changes it. */
        public final String intelligenceMode;
        /**
         * Whether the user has pinned this chat to the top of Chats.
         *
         * <p>Absent from every conversation written before this release, and that is the whole
         * migration: a stored chat with no {@code pinned} key reads as false, which is what an
         * unpinned chat is. Nothing has to be rewritten to gain the field.
         */
        public final boolean pinned;
        /**
         * This chat's own provider, model and strength (0.8.3.0+), or null for a chat that has not
         * been given one yet. Null is resolved to the global default by {@link AiSelections}.
         *
         * <p>{@link #intelligenceMode} is the retired field it replaced. It is still read, for the
         * one-time migration, and still written back unchanged so a downgrade finds what it wrote.
         */
        public final AiSelection aiSelection;
        /** Who owns the title. Legacy and manual titles are never eligible for automatic rewrite. */
        public final String titleOwner;
        /** Durable one-shot title work state. */
        public final String titleJobState;
        /** Identity of the one in-flight title result allowed to commit. */
        public final String titleJobToken;
        /**
         * The forks along {@link #messages} (0.8.3.0-beta.3+): every stored alternative of this
         * chat's edited messages and retried answers. Empty for a chat that has never branched,
         * which is every chat written before branches existed. See {@link ConversationBranches}.
         */
        final List<ConversationBranches.Fork> forks;
        /** Context the user kept for this whole chat (0.8.3.0-beta.3+). See {@link KeptContext}. */
        final List<KeptContext> kept;

        public Conversation(String id, String title, long updatedAt, List<AssistantClient.History> messages) {
            this(id, title, updatedAt, messages, "");
        }

        public Conversation(String id, String title, long updatedAt, List<AssistantClient.History> messages, String intelligenceMode) {
            this(id, title, updatedAt, messages, intelligenceMode, false);
        }

        public Conversation(String id, String title, long updatedAt, List<AssistantClient.History> messages,
                            String intelligenceMode, boolean pinned) {
            this(id, title, updatedAt, messages, intelligenceMode, pinned, null);
        }

        public Conversation(String id, String title, long updatedAt, List<AssistantClient.History> messages,
                            String intelligenceMode, boolean pinned, AiSelection aiSelection) {
            this(id, title, updatedAt, messages, intelligenceMode, pinned, aiSelection,
                    TITLE_LEGACY, TITLE_JOB_DONE, "");
        }

        Conversation(String id, String title, long updatedAt, List<AssistantClient.History> messages,
                     String intelligenceMode, boolean pinned, AiSelection aiSelection,
                     String titleOwner, String titleJobState, String titleJobToken) {
            this(id, title, updatedAt, messages, intelligenceMode, pinned, aiSelection, titleOwner,
                    titleJobState, titleJobToken, null, null);
        }

        Conversation(String id, String title, long updatedAt, List<AssistantClient.History> messages,
                     String intelligenceMode, boolean pinned, AiSelection aiSelection,
                     String titleOwner, String titleJobState, String titleJobToken,
                     List<ConversationBranches.Fork> forks, List<KeptContext> kept) {
            this.id = id == null || id.isEmpty() ? UUID.randomUUID().toString() : id;
            this.title = title == null || title.trim().isEmpty() ? NEW_CHAT_TITLE : title.trim();
            this.updatedAt = updatedAt;
            this.messages = messages == null ? new ArrayList<>() : new ArrayList<>(messages);
            this.intelligenceMode = intelligenceMode == null ? "" : intelligenceMode.trim();
            this.pinned = pinned;
            this.aiSelection = aiSelection;
            this.titleOwner = validTitleOwner(titleOwner);
            this.titleJobState = validTitleJobState(titleJobState);
            this.titleJobToken = titleJobToken == null ? "" : titleJobToken.trim();
            // Validated against the very messages they hang off, so a stale or damaged fork can
            // never graft an alternative onto the wrong place; it is dropped instead.
            this.forks = Collections.unmodifiableList(
                    ConversationBranches.validate(this.messages, forks, true));
            this.kept = Collections.unmodifiableList(KeptContext.normalize(kept));
        }

        /** The fork at this position of {@link #messages}, or null when it has no alternatives. */
        ConversationBranches.Fork forkAt(int index) {
            for (ConversationBranches.Fork fork : forks) if (fork.at == index) return fork;
            return null;
        }

        /** Whether this chat has any stored branch or answer variant. */
        boolean isBranched() { return !forks.isEmpty(); }

        /** Kept-context items, oldest first. */
        public List<KeptContext> keptItems() { return kept; }

        /** The same chat with a new active path and forks, everything else carried across. */
        Conversation withState(ConversationBranches.State state, long updated) {
            return new Conversation(id, title, updated, state.messages, intelligenceMode, pinned,
                    aiSelection, titleOwner, titleJobState, titleJobToken, state.forks, kept);
        }

        /** The same chat with these messages, its forks and kept context carried across. */
        Conversation withMessages(List<AssistantClient.History> list, long updated) {
            return new Conversation(id, title, updated, list, intelligenceMode, pinned,
                    aiSelection, titleOwner, titleJobState, titleJobToken, forks, kept);
        }

        Conversation withKept(List<KeptContext> items) {
            return new Conversation(id, title, updatedAt, messages, intelligenceMode, pinned,
                    aiSelection, titleOwner, titleJobState, titleJobToken, forks, items);
        }

        ConversationBranches.State state() {
            return new ConversationBranches.State(messages, forks);
        }
    }

    /** Frozen input and ownership token for one automatic-title job. */
    static final class TitleJob {
        final String conversationId;
        final String token;
        final String firstUserMessage;
        final String firstAssistantResponse;
        final AiSelection conversationSelection;

        TitleJob(String conversationId, String token, String firstUserMessage,
                 String firstAssistantResponse, AiSelection conversationSelection) {
            this.conversationId = conversationId;
            this.token = token;
            this.firstUserMessage = firstUserMessage;
            this.firstAssistantResponse = firstAssistantResponse;
            this.conversationSelection = conversationSelection;
        }
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    public static synchronized void save(Context c, String id, List<AssistantClient.History> history) {
        if (!Prefs.historyEnabled(c) || history == null || !hasUserMessage(history)) return;
        // Read before the store is: a first-ever call may run the one-time selection migration, which
        // rewrites the store, and reading afterwards keeps this save from undoing it. A new chat is
        // pinned to the default it started under, so changing the default later leaves it alone.
        AiSelection startingSelection = AiSelections.globalDefault(c);
        List<Conversation> all = readAll(c);
        String wantedId = id == null || id.isEmpty() ? newId() : id;
        Conversation existing = null;
        for (Conversation item : all) {
            if (wantedId.equals(item.id)) {
                existing = item;
                break;
            }
        }
        List<AssistantClient.History> incoming = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            AssistantClient.History h = history.get(i);
            if (h == null || h.content == null || h.content.trim().isEmpty()) continue;
            incoming.add(new AssistantClient.History(
                    "assistant".equalsIgnoreCase(h.role) ? "assistant" : "user",
                    clip(h.content, MAX_MESSAGE_CHARS),
                    h.screenAttached,
                    h.attachmentPaths,
                    h.attachmentKind,
                    h.attachmentLabel,
                    clip(h.attachmentText, 105000),
                    // Memory fields keep their existing save behaviour; only the stopped-turn
                    // anchor is added here, because losing it would move a mark off its turn.
                    "", "", "",
                    h.stoppedRequestId,
                    h.documents,
                    // Carried through the clip for the same reason the stopped anchor is: a
                    // lifecycle save must not be able to rub a picture off an answer that has one.
                    h.richImages, h.replyRequestId, h.sourceUrls, h.quote, h.details));
        }
        // A branched chat's active path is changed only by the branch operations below. A surface
        // still holding a copy from before a branch switch would otherwise write that old path back
        // over the new one and leave the forks describing a path that is no longer there. Every
        // ordinary save of such a chat is an append to, or a shorter copy of, what is stored; one
        // that disagrees with it is stale and changes nothing.
        boolean branched = existing != null && existing.isBranched();
        if (branched && !agreesAsPrefix(incoming, existing.messages)) {
            // The one legitimate disagreement: the stored path was clipped from the front by a
            // background write since this copy was read. Line the copy up with what is stored and
            // keep its newer messages; anything else is a stale branch and changes nothing.
            int offset = frontClipOffset(incoming, existing.messages);
            if (offset <= 0) return;
            incoming = new ArrayList<>(incoming.subList(offset, incoming.size()));
        }
        all.removeIf(item -> wantedId.equals(item.id));

        int start = Math.max(0, incoming.size() - MAX_MESSAGES_PER_CHAT);
        List<AssistantClient.History> clipped = new ArrayList<>(incoming.subList(start, incoming.size()));
        // A background response may be appended to disk after the assistant sheet
        // is hidden, while that old sheet still holds a shorter in-memory copy.
        // Never let a later lifecycle save erase that newer persisted suffix.
        // Orbit chats are append-only at the message level, so keeping the longer
        // stored copy is correct whenever the shorter copy is an exact prefix.
        if (existing != null && existing.messages.size() > clipped.size()
                && isExactPrefix(clipped, existing.messages)) {
            clipped = new ArrayList<>(existing.messages);
        }
        // A stop is recorded straight to disk by the request manager, which can happen while a
        // screen still holds an in-memory copy of the conversation from before it. Saving that
        // copy must not quietly rub the mark out, so stored anchors are carried back onto the
        // messages they belong to.
        if (existing != null) carryStoppedMarks(clipped, existing.messages);
        // A picture resolves seconds after the answer it belongs to and is written straight to
        // disk, so the same race applies to it. Carried back by the same alignment for the same
        // reason: a screen holding a copy from before the picture arrived must not erase it.
        if (existing != null) carryRichImages(clipped, existing.messages);

        // Forks are positions on the stored path. The incoming copy agrees with it from the first
        // message (checked above), so they keep their positions, less whatever the clip removed.
        // A copy no longer than the stored path adds nothing to a branched chat, so the stored path
        // and its forks stand exactly as they are.
        List<ConversationBranches.Fork> forks = new ArrayList<>();
        if (branched) {
            if (incoming.size() > existing.messages.size()) {
                ConversationBranches.State shifted = ConversationBranches.clipFront(
                        new ConversationBranches.State(incoming, existing.forks), start);
                forks.addAll(shifted.forks);
            } else {
                clipped = new ArrayList<>(existing.messages);
                forks.addAll(existing.forks);
            }
        }
        String finalTitle = existing == null ? NEW_CHAT_TITLE : existing.title;
        all.add(new Conversation(wantedId, finalTitle, System.currentTimeMillis(), clipped,
                existing == null ? "" : existing.intelligenceMode, existing != null && existing.pinned,
                existing == null ? startingSelection : existing.aiSelection,
                existing == null ? TITLE_DEFAULT : existing.titleOwner,
                existing == null ? TITLE_JOB_IDLE : existing.titleJobState,
                existing == null ? "" : existing.titleJobToken,
                forks, existing == null ? null : existing.kept));
        all.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        if (all.size() > MAX_CONVERSATIONS) all = new ArrayList<>(all.subList(0, MAX_CONVERSATIONS));
        writeAll(c, all);
    }

    public static synchronized List<Conversation> list(Context c) {
        List<Conversation> all = readAll(c);
        all.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return all;
    }

    public static synchronized Conversation load(Context c, String id) {
        if (id == null) return null;
        for (Conversation item : readAll(c)) if (id.equals(item.id)) return item;
        return null;
    }

    public static synchronized Conversation latest(Context c) {
        List<Conversation> all = list(c);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Removes a conversation and the private image files only it referred to.
     *
     * <p>Deleting a chat used to leave its stored attachment JPEGs behind forever, which was
     * tolerable while a turn held one small screenshot and is not once a turn can hold ten photos.
     * The files are collected from this conversation's own record first, then every other stored
     * conversation is checked, so a file a restored backup happens to share with another chat is
     * never removed out from under it.
     *
     * <p>This is the commit point, not the gesture. Chats defers the delete for the length of the
     * Undo window and calls here only once that window has genuinely closed, so an undone deletion
     * never reaches this method and nothing it could have destroyed is at risk.
     */
    public static synchronized void delete(Context c, String id) {
        List<Conversation> all = readAll(c);
        List<String> owned = new ArrayList<>();
        for (Conversation item : all) {
            if (item.id.equals(id)) owned.addAll(ownedAttachmentPaths(item));
        }
        all.removeIf(item -> item.id.equals(id));
        writeAll(c, all);
        ActionResultStore.clearConversation(c, id);

        if (owned.isEmpty()) return;
        Set<String> stillReferenced = new HashSet<>();
        for (Conversation item : all) stillReferenced.addAll(ownedAttachmentPaths(item));
        for (String path : owned) {
            if (!stillReferenced.contains(path)) AttachmentStore.delete(path);
        }
    }

    public static synchronized void appendMessage(Context c, String id, AssistantClient.History message) {
        if (!Prefs.historyEnabled(c) || id == null || id.isEmpty() || message == null ||
                message.content == null || message.content.trim().isEmpty()) return;
        Conversation existing = load(c, id);
        List<AssistantClient.History> messages = existing == null
                ? new ArrayList<>()
                : new ArrayList<>(existing.messages);
        messages.add(message);
        save(c, id, messages);
    }

    /**
     * Attaches sourced pictures to the assistant message that produced them.
     *
     * <p>A picture resolves after its answer has already been written, spoken and drawn, which
     * means the message it belongs to has to be found again rather than held onto. It is found by
     * completed request id as well as its text. Regeneration can return identical words, so
     * text alone cannot distinguish a removed answer from its replacement.
     *
     * <p>Deliberately refuses a message that already has pictures. Discovery runs once per
     * completed request, and a second write onto the same answer could only come from a request
     * that no longer owns it.
     *
     * @return true when this call is what attached them.
     */
    public static synchronized boolean attachRichImages(Context c, String id, String answerText,
                                                        List<RichAnswerImage> images) {
        return attachRichImages(c, id, "", answerText, images);
    }

    public static synchronized boolean attachRichImages(Context c, String id, String requestId,
                                                        String answerText, List<RichAnswerImage> images) {
        return attachRichImages(c, id, requestId, answerText, images, java.util.Collections.emptyList());
    }

    /**
     * The same attach, additionally recording where the picture's page came from.
     *
     * <p>{@code recoveredSources} is written only onto a message that has no provenance of its own,
     * and only when discovery had to recover the page from the answer's explicit {@code Source:}
     * marker because the provider reported no structured sources. Without it, reopening the chat
     * would show the picture with nothing saying which page it belongs to. A message that already
     * carries structured provenance is never rewritten, no URL is ever duplicated, the visible
     * answer text is untouched, and an older stored message that predates the field is unaffected.
     */
    public static synchronized boolean attachRichImages(Context c, String id, String requestId,
                                                        String answerText, List<RichAnswerImage> images,
                                                        List<String> recoveredSources) {
        if (c == null || id == null || id.isEmpty() || images == null || images.isEmpty()) return false;
        String wanted = answerText == null ? "" : answerText.trim();
        if (wanted.isEmpty()) return false;
        List<Conversation> all = readAll(c);
        for (int x = 0; x < all.size(); x++) {
            Conversation existing = all.get(x);
            if (!id.equals(existing.id)) continue;
            List<AssistantClient.History> messages = new ArrayList<>(existing.messages);
            for (int i = messages.size() - 1; i >= 0; i--) {
                AssistantClient.History message = messages.get(i);
                if (message == null || !"assistant".equalsIgnoreCase(message.role)) continue;
                // Empty ownership is only for legacy messages; it must never match a new reply.
                if (!safe(requestId).trim().equals(message.replyRequestId)) continue;
                if (!wanted.equals(safe(message.content).trim())) continue;
                if (message.hasRichImages()) return false;
                AssistantClient.History attached = message.withRichImages(images);
                if (!attached.hasRichImages()) return false;
                if (attached.sourceUrls.isEmpty() && recoveredSources != null
                        && !recoveredSources.isEmpty()) {
                    attached = attached.withReplyProvenance(attached.replyRequestId, recoveredSources);
                }
                messages.set(i, attached);
                // updatedAt is carried across untouched: a picture arriving is not the user doing
                // something, and reordering Chats because one resolved would be wrong.
                all.set(x, new Conversation(existing.id, existing.title, existing.updatedAt,
                        messages, existing.intelligenceMode, existing.pinned, existing.aiSelection,
                        existing.titleOwner, existing.titleJobState, existing.titleJobToken, existing.forks, existing.kept));
                writeAll(c, all);
                return true;
            }
            return false;
        }
        return false;
    }

    /**
     * Records that the user stopped {@code requestId} at this conversation's current end.
     *
     * <p>The one durable write behind a stopped mark, and the reason the mark stays where it
     * belongs. It anchors to the message that ends the stopped turn — the question itself when no
     * text arrived, or the partial answer when some did, since the manager persists that first.
     * Later turns are appended after this message, so they push the mark nowhere.
     *
     * <p>Nothing is written as content: only the anchor field changes, and no assistant message is
     * created. {@code updatedAt} is deliberately left alone, because stopping a reply is not new
     * activity and must not reorder the chat list.
     *
     * <p>Idempotent, and it never overwrites another turn's anchor: stopping the same request
     * twice, or a second request whose turn already carries a mark, changes nothing.
     *
     * @return true when this call is what recorded the mark.
     */
    public static synchronized boolean markTurnStopped(Context c, String id, String requestId) {
        if (c == null || id == null || id.isEmpty() || requestId == null) return false;
        String wanted = requestId.trim();
        if (wanted.isEmpty()) return false;
        List<Conversation> all = readAll(c);
        for (int x = 0; x < all.size(); x++) {
            Conversation existing = all.get(x);
            if (!id.equals(existing.id)) continue;
            List<AssistantClient.History> messages = new ArrayList<>(existing.messages);
            if (messages.isEmpty()) return false;
            // Already recorded, in this process or an earlier one.
            for (AssistantClient.History h : messages) {
                if (h != null && wanted.equals(h.stoppedRequestId)) return false;
            }
            AssistantClient.History last = messages.get(messages.size() - 1);
            if (last == null || last.isStopped()) return false;
            messages.set(messages.size() - 1, last.withStoppedRequestId(wanted));
            all.set(x, new Conversation(existing.id, existing.title, existing.updatedAt, messages,
                    existing.intelligenceMode, existing.pinned, existing.aiSelection,
                    existing.titleOwner, existing.titleJobState, existing.titleJobToken, existing.forks, existing.kept));
            writeAll(c, all);
            return true;
        }
        return false;
    }

    /** Every request id this conversation has a stopped mark for, oldest turn first. */
    public static synchronized List<String> stoppedRequestIds(Context c, String id) {
        List<String> out = new ArrayList<>();
        Conversation existing = load(c, id);
        if (existing == null) return out;
        for (AssistantClient.History h : existing.messages) {
            if (h != null && h.isStopped()) out.add(h.stoppedRequestId);
        }
        return out;
    }

    /**
     * Pins or unpins a chat, leaving everything else about it alone.
     *
     * <p>Deliberately does not touch {@code updatedAt}: pinning is not activity, and moving a chat
     * to the top of Recent as a side effect of pinning it to the top of Pinned would be two
     * different reorderings for one gesture. Unpinning therefore returns the chat to exactly the
     * position in Recent it would have held all along.
     *
     * @return the resulting pinned state, or {@code false} when there is no such chat.
     */
    public static synchronized boolean setPinned(Context c, String id, boolean pinned) {
        if (id == null || id.trim().isEmpty()) return false;
        List<Conversation> all = readAll(c);
        for (int i = 0; i < all.size(); i++) {
            Conversation item = all.get(i);
            if (!id.equals(item.id)) continue;
            if (item.pinned == pinned) return pinned;
            all.set(i, new Conversation(item.id, item.title, item.updatedAt, item.messages,
                    item.intelligenceMode, pinned, item.aiSelection, item.titleOwner,
                    item.titleJobState, item.titleJobToken, item.forks, item.kept));
            writeAll(c, all);
            return pinned;
        }
        return false;
    }

    /** True when this chat is pinned. False for an unknown chat, which is not an error. */
    public static synchronized boolean isPinned(Context c, String id) {
        Conversation item = load(c, id);
        return item != null && item.pinned;
    }

    public static synchronized void rename(Context c, String id, String title) {
        if (id == null || title == null || title.trim().isEmpty()) return;
        List<Conversation> all = readAll(c);
        for (int i = 0; i < all.size(); i++) {
            Conversation item = all.get(i);
            if (!id.equals(item.id)) continue;
            all.set(i, new Conversation(item.id, title.trim(), System.currentTimeMillis(), item.messages,
                    item.intelligenceMode, item.pinned, item.aiSelection, TITLE_MANUAL,
                    TITLE_JOB_DONE, "", item.forks, item.kept));
            break;
        }
        // An explicit rename is the strongest title write. Commit it before returning so an
        // already-running background result can never survive a process death and beat it later.
        writeAll(c, all, true);
    }

    /**
     * Claims the one automatic title job this new chat may create.
     *
     * <p>The successful request id must own a persisted assistant message. Failed and cancelled
     * requests never have one, so they cannot reach the pending state. Existing records from before
     * this feature are legacy-owned and refused; a manually renamed record is refused for the same
     * reason. The token is the compare-and-set half of manual-rename race protection.
     */
    static synchronized TitleJob beginAutomaticTitle(Context c, String id,
                                                     String successfulRequestId) {
        if (c == null || id == null || id.isEmpty() || successfulRequestId == null
                || successfulRequestId.trim().isEmpty()) return null;
        List<Conversation> all = readAll(c);
        for (int x = 0; x < all.size(); x++) {
            Conversation item = all.get(x);
            if (!id.equals(item.id)) continue;
            if (!TITLE_DEFAULT.equals(item.titleOwner)
                    || !TITLE_JOB_IDLE.equals(item.titleJobState)) return null;

            int completed = -1;
            for (int i = 0; i < item.messages.size(); i++) {
                AssistantClient.History h = item.messages.get(i);
                if (h != null && "assistant".equalsIgnoreCase(h.role)
                        && successfulRequestId.equals(h.replyRequestId)) {
                    completed = i;
                    break;
                }
            }
            if (completed < 0) return null;
            AssistantClient.History firstUser = null;
            // The nearest preceding substantive user turn is the user half of this successful
            // exchange. An earlier failed/cancelled prompt must not title a later successful chat.
            for (int i = completed - 1; i >= 0; i--) {
                AssistantClient.History h = item.messages.get(i);
                if (h != null && "user".equalsIgnoreCase(h.role)
                        && ConversationTitlePolicy.isSubstantive(h.content)) {
                    firstUser = h;
                    break;
                }
            }
            if (firstUser == null) return null;
            AssistantClient.History answer = item.messages.get(completed);
            if (answer.content == null || answer.content.trim().isEmpty()) return null;

            String token = UUID.randomUUID().toString();
            Conversation pending = new Conversation(item.id, item.title, item.updatedAt,
                    item.messages, item.intelligenceMode, item.pinned, item.aiSelection,
                    item.titleOwner, TITLE_JOB_PENDING, token, item.forks, item.kept);
            all.set(x, pending);
            writeAll(c, all, true);
            return new TitleJob(item.id, token, firstUser.content, answer.content,
                    item.aiSelection);
        }
        return null;
    }

    /** Applies a generated/fallback result only while its exact default-owned job still exists. */
    static synchronized boolean applyAutomaticTitle(Context c, String id, String token,
                                                     String title) {
        if (c == null || id == null || token == null || token.isEmpty()
                || title == null || title.trim().isEmpty()) return false;
        List<Conversation> all = readAll(c);
        for (int i = 0; i < all.size(); i++) {
            Conversation item = all.get(i);
            if (!id.equals(item.id)) continue;
            if (!TITLE_DEFAULT.equals(item.titleOwner)
                    || !TITLE_JOB_PENDING.equals(item.titleJobState)
                    || !token.equals(item.titleJobToken)) return false;
            all.set(i, new Conversation(item.id, title.trim(), item.updatedAt, item.messages,
                    item.intelligenceMode, item.pinned, item.aiSelection, TITLE_AUTOMATIC,
                    TITLE_JOB_DONE, "", item.forks, item.kept));
            writeAll(c, all, true);
            return true;
        }
        return false;
    }


    /** This chat's stored selection, unresolved, or null when it has none. See {@link AiSelections}. */
    public static synchronized AiSelection selectionFor(Context c, String id) {
        if (id == null || id.isEmpty()) return null;
        Conversation existing = load(c, id);
        return existing == null ? null : existing.aiSelection;
    }

    /**
     * Stores one chat's selection. Only that chat changes; no other chat, and not the global
     * default. A chat with no record yet keeps its selection in the caller's memory until its first
     * message is saved, exactly as before.
     */
    public static synchronized void setSelection(Context c, String id, AiSelection selection) {
        if (id == null || id.isEmpty() || selection == null) return;
        List<Conversation> all = readAll(c);
        for (int i = 0; i < all.size(); i++) {
            Conversation item = all.get(i);
            if (!id.equals(item.id)) continue;
            if (selection.equals(item.aiSelection)) return;
            all.set(i, new Conversation(item.id, item.title, item.updatedAt, item.messages,
                    item.intelligenceMode, item.pinned, selection, item.titleOwner,
                    item.titleJobState, item.titleJobToken, item.forks, item.kept));
            writeAll(c, all);
            return;
        }
    }

    /** Maps a chat's retired intelligence mode (possibly empty) to its explicit selection. */
    interface LegacySelectionMapper {
        AiSelection map(String legacyMode);
    }

    /**
     * Gives every chat that has no selection yet the one its retired mode stood for. Chats that
     * already have a selection are never touched, so running this again changes nothing.
     */
    static synchronized void migrateSelections(Context c, LegacySelectionMapper mapper) {
        List<Conversation> all = readAll(c);
        boolean changed = false;
        for (int i = 0; i < all.size(); i++) {
            Conversation item = all.get(i);
            if (item.aiSelection != null) continue;
            all.set(i, new Conversation(item.id, item.title, item.updatedAt, item.messages,
                    item.intelligenceMode, item.pinned, mapper.map(item.intelligenceMode),
                    item.titleOwner, item.titleJobState, item.titleJobToken, item.forks, item.kept));
            changed = true;
        }
        if (changed) writeAll(c, all);
    }

    public static synchronized void clearMessages(Context c, String id) {
        if (id == null || id.isEmpty()) return;
        List<Conversation> all = readAll(c);
        for (int i = 0; i < all.size(); i++) {
            Conversation item = all.get(i);
            if (!id.equals(item.id)) continue;
            all.set(i, new Conversation(item.id, NEW_CHAT_TITLE, System.currentTimeMillis(),
                    new ArrayList<>(), item.intelligenceMode, item.pinned, item.aiSelection,
                    TITLE_DEFAULT, TITLE_JOB_IDLE, ""));
            writeAll(c, all);
            ActionResultStore.clearConversation(c, id);
            return;
        }
    }

    public static synchronized List<AssistantClient.History> removeLastAssistantTurn(Context c, String id) {
        List<Conversation> all = readAll(c);
        for (int x = 0; x < all.size(); x++) {
            Conversation existing = all.get(x);
            if (!id.equals(existing.id)) continue;
            List<AssistantClient.History> messages = new ArrayList<>(existing.messages);
            for (int i = messages.size() - 1; i >= 0; i--) {
                if ("assistant".equalsIgnoreCase(messages.get(i).role)) {
                    // A message that starts or precedes a stored alternative is part of a branch,
                    // and removing it would orphan that branch. Nothing is removed; the caller
                    // still gets the conversation exactly as it is.
                    boolean anchorsBranch = false;
                    for (ConversationBranches.Fork fork : existing.forks) {
                        if (fork.at >= i) { anchorsBranch = true; break; }
                    }
                    if (anchorsBranch) return messages;
                    messages.remove(i);
                    ActionResultStore.removeAssistantIndex(c, id, i);
                    break;
                }
            }
            // This is an intentional edit, not a lifecycle save, so bypass the
            // stale-prefix protection used by save().
            all.set(x, new Conversation(existing.id, existing.title, System.currentTimeMillis(), messages,
                    existing.intelligenceMode, existing.pinned, existing.aiSelection,
                    existing.titleOwner, existing.titleJobState, existing.titleJobToken, existing.forks, existing.kept));
            writeAll(c, all);
            return messages;
        }
        return new ArrayList<>();
    }

    // ---- branches and answer variants (0.8.3.0-beta.3) -------------------------------------------

    /** What a branch operation produced: the new visible path, or why nothing changed. */
    static final class BranchResult {
        final List<AssistantClient.History> messages;
        final String error;

        private BranchResult(List<AssistantClient.History> messages, String error) {
            this.messages = messages == null ? new ArrayList<>() : new ArrayList<>(messages);
            this.error = error == null ? "" : error;
        }

        static BranchResult ok(List<AssistantClient.History> messages) { return new BranchResult(messages, ""); }
        static BranchResult refused(String why) { return new BranchResult(null, why); }
        boolean ok() { return error.isEmpty(); }
    }

    /**
     * Sends an edited copy of an earlier user message as a new branch.
     *
     * <p>The original message and everything after it are kept, untouched, as the original branch;
     * the edited message becomes the visible continuation from that point and gets its own answer.
     * {@code expectedKey} is the fingerprint of the message the user long-pressed, so an edit can
     * never land on a different message than the one they chose.
     */
    static synchronized BranchResult branchFromUserMessage(Context c, String id, int index,
                                                          String expectedKey,
                                                          AssistantClient.History edited) {
        if (c == null || id == null || edited == null || edited.content == null
                || edited.content.trim().isEmpty() || !"user".equalsIgnoreCase(edited.role)) {
            return BranchResult.refused("Nothing to send");
        }
        if (!Prefs.historyEnabled(c)) return BranchResult.refused("Chat history is off");
        List<Conversation> all = readAll(c);
        for (int x = 0; x < all.size(); x++) {
            Conversation existing = all.get(x);
            if (!id.equals(existing.id)) continue;
            if (index < 0 || index >= existing.messages.size()
                    || !"user".equalsIgnoreCase(existing.messages.get(index).role)
                    || !ConversationBranches.fingerprint(existing.messages.get(index)).equals(expectedKey)) {
                return BranchResult.refused("That message has changed. Try again.");
            }
            try {
                ConversationBranches.State next = ConversationBranches.branch(existing.state(), index,
                        new ConversationBranches.Path(Collections.singletonList(edited), null));
                ActionResultStore.bindToMessages(c, id, existing.messages);
                Conversation updated = existing.withState(next, System.currentTimeMillis());
                all.set(x, updated);
                writeAll(c, all, true);
                return BranchResult.ok(updated.messages);
            } catch (ConversationBranches.Refusal refusal) {
                return BranchResult.refused(refusal.getMessage());
            }
        }
        return BranchResult.refused("This chat is no longer available");
    }

    /**
     * Adds a finished retry as a new answer variant, keeping the answer it was asked beside.
     *
     * <p>Called only for a retry that produced an answer, so a failed or empty retry never touches
     * the conversation and the existing answer simply stays. {@code at} is the position of the
     * answer being retried and {@code parentKey} the fingerprint of the question before it; if the
     * conversation no longer has that question there, nothing is written.
     */
    static synchronized boolean commitAnswerVariant(Context c, String id, int at, String parentKey,
                                                    AssistantClient.History answer) {
        if (c == null || id == null || answer == null || answer.content == null
                || answer.content.trim().isEmpty() || !Prefs.historyEnabled(c)) return false;
        List<Conversation> all = readAll(c);
        for (int x = 0; x < all.size(); x++) {
            Conversation existing = all.get(x);
            if (!id.equals(existing.id)) continue;
            if (at <= 0 || at > existing.messages.size()
                    || !"user".equalsIgnoreCase(existing.messages.get(at - 1).role)
                    || !ConversationBranches.parentKey(existing.messages, at).equals(parentKey)) {
                return false;
            }
            if (at < existing.messages.size()
                    && !"assistant".equalsIgnoreCase(existing.messages.get(at).role)) return false;
            try {
                ConversationBranches.State next = ConversationBranches.branch(existing.state(), at,
                        new ConversationBranches.Path(Collections.singletonList(answer), null));
                ActionResultStore.bindToMessages(c, id, existing.messages);
                all.set(x, existing.withState(next, System.currentTimeMillis()));
                writeAll(c, all, true);
                return true;
            } catch (ConversationBranches.Refusal refusal) {
                return false;
            }
        }
        return false;
    }

    /** Whether one more answer variant can be added at {@code at}, before a retry is started. */
    static synchronized boolean canAddVariant(Context c, String id, int at) {
        Conversation existing = load(c, id);
        if (existing == null) return true;
        ConversationBranches.Fork fork = existing.forkAt(at);
        if (fork != null) return fork.count() < ConversationBranches.MAX_VARIANTS;
        return ConversationBranches.countForks(existing.forks) < ConversationBranches.MAX_FORKS;
    }

    /**
     * Shows another version of the message at {@code at}. Only the visible path changes: every
     * alternative, including the one being left, stays stored exactly as it was.
     */
    static synchronized BranchResult selectVariant(Context c, String id, int at, int target) {
        if (c == null || id == null) return BranchResult.refused("This chat is no longer available");
        List<Conversation> all = readAll(c);
        for (int x = 0; x < all.size(); x++) {
            Conversation existing = all.get(x);
            if (!id.equals(existing.id)) continue;
            try {
                ConversationBranches.State next =
                        ConversationBranches.select(existing.state(), at, target);
                ActionResultStore.bindToMessages(c, id, existing.messages);
                // Not new activity: looking at another version must not reorder Chats.
                Conversation updated = existing.withState(next, existing.updatedAt);
                all.set(x, updated);
                writeAll(c, all, true);
                return BranchResult.ok(updated.messages);
            } catch (ConversationBranches.Refusal refusal) {
                return BranchResult.refused(refusal.getMessage());
            }
        }
        return BranchResult.refused("This chat is no longer available");
    }

    // ---- kept context (0.8.3.0-beta.3) -----------------------------------------------------------

    /** The context kept for this chat, oldest first. Empty for an unknown chat. */
    static synchronized List<KeptContext> kept(Context c, String id) {
        Conversation existing = load(c, id);
        return existing == null ? new ArrayList<>() : new ArrayList<>(existing.kept);
    }

    /**
     * Keeps one item for this whole chat.
     *
     * <p>Refused for a chat with no saved record yet, a full list, or an item that is not keepable.
     * Keeping the same item from the same message twice keeps it once.
     */
    static synchronized boolean keep(Context c, String id, KeptContext item) {
        if (c == null || id == null || item == null || !item.isUsable()) return false;
        List<Conversation> all = readAll(c);
        for (int x = 0; x < all.size(); x++) {
            Conversation existing = all.get(x);
            if (!id.equals(existing.id)) continue;
            for (KeptContext kept : existing.kept) {
                if (kept.kind.equals(item.kind) && kept.label.equals(item.label)
                        && kept.originKey.equals(item.originKey)) return true;
            }
            if (existing.kept.size() >= KeptContext.MAX_ITEMS) return false;
            List<KeptContext> items = new ArrayList<>(existing.kept);
            items.add(item);
            all.set(x, existing.withKept(items));
            writeAll(c, all, true);
            return true;
        }
        return false;
    }

    /** Stops keeping one item. Later requests no longer carry it; nothing already sent changes. */
    static synchronized boolean removeKept(Context c, String id, String keptId) {
        if (c == null || id == null || keptId == null) return false;
        List<Conversation> all = readAll(c);
        for (int x = 0; x < all.size(); x++) {
            Conversation existing = all.get(x);
            if (!id.equals(existing.id)) continue;
            List<KeptContext> items = new ArrayList<>(existing.kept);
            if (!items.removeIf(item -> keptId.equals(item.id))) return false;
            all.set(x, existing.withKept(items));
            writeAll(c, all, true);
            return true;
        }
        return false;
    }

    /**
     * Creates a new, empty chat that starts with context carried forward (Continue in new chat).
     *
     * <p>The chat has no messages, an ordinary New Chat title that the normal automatic-title
     * lifecycle replaces after its first exchange, and the given selection. Nothing about the chat
     * it continues from is touched.
     */
    static synchronized boolean createContinuation(Context c, String newId, AiSelection selection,
                                                   List<KeptContext> items) {
        if (c == null || newId == null || newId.trim().isEmpty() || !Prefs.historyEnabled(c)) return false;
        List<Conversation> all = readAll(c);
        for (Conversation item : all) if (newId.equals(item.id)) return false;
        all.add(new Conversation(newId, NEW_CHAT_TITLE, System.currentTimeMillis(),
                new ArrayList<>(), "", false, selection, TITLE_DEFAULT, TITLE_JOB_IDLE, "",
                null, items));
        all.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        if (all.size() > MAX_CONVERSATIONS) all = new ArrayList<>(all.subList(0, MAX_CONVERSATIONS));
        writeAll(c, all, true);
        return true;
    }

    public static synchronized List<Conversation> search(Context c, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.US);
        if (q.isEmpty()) return list(c);
        List<Conversation> out = new ArrayList<>();
        for (Conversation item : list(c)) {
            if (item.title.toLowerCase(java.util.Locale.US).contains(q)) { out.add(item); continue; }
            boolean match = false;
            for (AssistantClient.History h : item.messages) {
                if (h != null && h.content != null && h.content.toLowerCase(java.util.Locale.US).contains(q)) { match = true; break; }
            }
            if (match) out.add(item);
        }
        return out;
    }

    public static synchronized void clear(Context c) {
        List<String> owned = new ArrayList<>();
        for (Conversation conversation : readAll(c)) {
            owned.addAll(ownedAttachmentPaths(conversation));
        }
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY).apply();
        c.getSharedPreferences("orbit_action_results", Context.MODE_PRIVATE).edit().clear().apply();
        // Clearing history is the final ownership boundary for its private images and PDFs.
        // De-duplicate because one retained file can legitimately be referenced by more than one
        // stored turn while the conversation is being regenerated or migrated.
        for (String path : new HashSet<>(owned)) AttachmentStore.delete(path);
    }

    /** The raw stored form, for a test that checks what old data does and does not contain. */
    static String backupJsonForTest(Context c) { return backupJson(c); }

    static synchronized String backupJson(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, "[]");
    }

    static synchronized boolean restoreBackupJson(Context c, String raw) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString(KEY, raw == null ? "[]" : raw).commit();
    }


    /**
     * Restores stopped anchors from the stored copy onto the messages being saved.
     *
     * <p>Orbit conversations are append-only and are clipped from the front, so the two lists are
     * windows onto one sequence and differ only by a shift. The shift is found by matching role and
     * content, and the longest fully agreeing alignment wins; an anchor is then copied only onto a
     * message that agrees with the one that carried it and does not already have an anchor of its
     * own. When no alignment agrees — an edit rewrote the tail, say — nothing is copied, because a
     * mark guessed onto the wrong message would be worse than a mark that is gone.
     *
     * <p>Position is used, never prompt text: identical questions asked twice are different
     * messages here, distinguished by everything around them and by the request id they carry.
     */
    private static void carryStoppedMarks(List<AssistantClient.History> incoming,
                                          List<AssistantClient.History> stored) {
        if (incoming == null || stored == null || incoming.isEmpty() || stored.isEmpty()) return;
        boolean anyStored = false;
        for (AssistantClient.History h : stored) if (h != null && h.isStopped()) { anyStored = true; break; }
        if (!anyStored) return;

        int bestShift = alignmentShift(incoming, stored);
        if (bestShift == NO_ALIGNMENT) return;

        for (int i = 0; i < stored.size(); i++) {
            AssistantClient.History from = stored.get(i);
            if (from == null || !from.isStopped()) continue;
            int j = i + bestShift;
            if (j < 0 || j >= incoming.size()) continue;
            AssistantClient.History to = incoming.get(j);
            if (to == null || to.isStopped()) continue;
            incoming.set(j, to.withStoppedRequestId(from.stoppedRequestId));
        }
    }

    /**
     * Restores sourced pictures from the stored copy onto the messages being saved.
     *
     * <p>The same problem the stopped anchor has, arriving from the other direction. A picture is
     * discovered after the answer is already on screen and is written straight to disk; a surface
     * holding the conversation from a moment earlier then saves it back, and without this the
     * picture would exist for three seconds and then quietly vanish on the next lifecycle save.
     *
     * <p>Uses the same alignment and the same rule: copied only onto a message that genuinely
     * agrees with the one that carried it, and never over pictures the incoming copy already has.
     */
    private static void carryRichImages(List<AssistantClient.History> incoming,
                                        List<AssistantClient.History> stored) {
        if (incoming == null || stored == null || incoming.isEmpty() || stored.isEmpty()) return;
        boolean anyStored = false;
        for (AssistantClient.History h : stored) if (h != null
                && (h.hasRichImages() || !h.replyRequestId.isEmpty())) { anyStored = true; break; }
        if (!anyStored) return;

        int shift = alignmentShift(incoming, stored);
        if (shift == NO_ALIGNMENT) return;

        for (int i = 0; i < stored.size(); i++) {
            AssistantClient.History from = stored.get(i);
            if (from == null) continue;
            int j = i + shift;
            if (j < 0 || j >= incoming.size()) continue;
            AssistantClient.History to = incoming.get(j);
            if (to == null) continue;
            if (!to.replyRequestId.isEmpty() && !to.replyRequestId.equals(from.replyRequestId)) continue;
            if (to.replyRequestId.isEmpty() && !from.replyRequestId.isEmpty()) {
                to = to.withReplyProvenance(from.replyRequestId, from.sourceUrls);
            }
            if (!to.hasRichImages() && from.hasRichImages()) to = to.withRichImages(from.richImages);
            incoming.set(j, to);
        }
    }

    /** No shift aligns the two windows well enough to move anything between them. */
    private static final int NO_ALIGNMENT = Integer.MIN_VALUE;

    /**
     * How far the stored window sits from the incoming one, or {@link #NO_ALIGNMENT}.
     *
     * <p>Orbit conversations are append-only and clipped from the front, so the two lists are
     * windows onto one sequence and differ by a shift. The longest fully agreeing shift wins.
     * One matching message is a coincidence rather than an alignment - with the same question asked
     * twice it is the coincidence that would move a mark, or a picture, onto the wrong occurrence -
     * so a single-message agreement between two real conversations is refused.
     */
    private static int alignmentShift(List<AssistantClient.History> incoming,
                                      List<AssistantClient.History> stored) {
        int bestShift = 0;
        int bestOverlap = 0;
        for (int shift = -(stored.size() - 1); shift < incoming.size(); shift++) {
            int overlap = 0;
            boolean agrees = true;
            for (int i = 0; i < stored.size(); i++) {
                int j = i + shift;
                if (j < 0 || j >= incoming.size()) continue;
                if (!sameMessage(stored.get(i), incoming.get(j))) { agrees = false; break; }
                overlap++;
            }
            if (agrees && overlap > bestOverlap) { bestOverlap = overlap; bestShift = shift; }
        }
        if (bestOverlap == 0) return NO_ALIGNMENT;
        if (bestOverlap < 2 && incoming.size() > 1 && stored.size() > 1) return NO_ALIGNMENT;
        return bestShift;
    }

    /** Same place in the conversation, for alignment purposes: same speaker, same words. */
    private static boolean sameMessage(AssistantClient.History a, AssistantClient.History b) {
        if (a == null || b == null) return a == b;
        if (!a.replyRequestId.isEmpty() && !b.replyRequestId.isEmpty()
                && !a.replyRequestId.equals(b.replyRequestId)) return false;
        return safe(a.role).equalsIgnoreCase(safe(b.role)) && safe(a.content).equals(safe(b.content));
    }

    private static boolean isExactPrefix(List<AssistantClient.History> shorter, List<AssistantClient.History> longer) {
        if (shorter == null || longer == null || shorter.size() > longer.size()) return false;
        for (int i = 0; i < shorter.size(); i++) {
            AssistantClient.History a = shorter.get(i);
            AssistantClient.History b = longer.get(i);
            if (a == null || b == null) {
                if (a != b) return false;
                continue;
            }
            if (!sameMessage(a, b)) return false;
            String ar = a.role == null ? "" : a.role;
            String br = b.role == null ? "" : b.role;
            String ac = a.content == null ? "" : a.content;
            String bc = b.content == null ? "" : b.content;
            if (!ar.equals(br) || !ac.equals(bc) || a.screenAttached != b.screenAttached ||
                    !safe(a.attachmentPath).equals(safe(b.attachmentPath)) ||
                    !safe(a.attachmentKind).equals(safe(b.attachmentKind)) ||
                    !safe(a.attachmentLabel).equals(safe(b.attachmentLabel)) ||
                    !safe(a.attachmentText).equals(safe(b.attachmentText)) ||
                    !safe(a.memoryUsage).equals(safe(b.memoryUsage)) ||
                    !safe(a.memorySuggestionText).equals(safe(b.memorySuggestionText)) ||
                    !safe(a.memorySuggestionCategory).equals(safe(b.memorySuggestionCategory))) return false;
        }
        return true;
    }

    private static boolean hasUserMessage(List<AssistantClient.History> history) {
        for (AssistantClient.History h : history) {
            if (h != null && "user".equalsIgnoreCase(h.role) && h.content != null && !h.content.trim().isEmpty()) return true;
        }
        return false;
    }

    private static List<Conversation> readAll(Context c) {
        ArrayList<Conversation> result = new ArrayList<>();
        try {
            SharedPreferences p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
            String raw = p.getString(KEY, "[]");
            JSONArray arr = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                List<AssistantClient.History> history = readMessages(o.optJSONArray("messages"));
                // A chat stored before pinning existed simply has no "pinned" key, and false is
                // exactly what an unpinned chat means, so old data needs no migration step.
                result.add(new Conversation(o.optString("id"), o.optString("title"),
                        o.optLong("updatedAt", 0), history, o.optString("intelligenceMode", ""),
                        o.optBoolean("pinned", false),
                        // Absent from every chat written before 0.8.3.0; null means "not migrated
                        // yet" and is filled in once by AiSelections.ensureMigrated.
                        AiSelection.decode(o.optString("aiSelection", "")),
                        // A record from before automatic titles is protected as legacy. Orbit
                        // cannot prove whether its text was chosen by the user, so it never guesses.
                        o.has("titleOwner") ? o.optString("titleOwner", TITLE_LEGACY) : TITLE_LEGACY,
                        o.optString("titleJobState", TITLE_JOB_DONE),
                        o.optString("titleJobToken", ""),
                        // Both absent before 0.8.3.0-beta.3, and absent means a linear chat with
                        // nothing kept: exactly what every older chat is, so nothing is migrated.
                        readForks(o.optJSONArray("forks")),
                        readKept(o.optJSONArray("kept"))));
            }
        } catch (Exception ignored) {}
        return result;
    }

    /** One stored message list, dropping anything empty or damaged. */
    private static List<AssistantClient.History> readMessages(JSONArray msgs) {
        ArrayList<AssistantClient.History> history = new ArrayList<>();
        if (msgs == null) return history;
        for (int j = 0; j < msgs.length(); j++) {
            AssistantClient.History message = readMessage(msgs.optJSONObject(j));
            if (message != null) history.add(message);
        }
        return history;
    }

    private static AssistantClient.History readMessage(JSONObject m) {
        if (m == null) return null;
        String content = m.optString("content", "");
        if (content.isEmpty()) return null;
        boolean attached = m.optBoolean("screenAttached", false);
        return new AssistantClient.History(
                "assistant".equals(m.optString("role")) ? "assistant" : "user",
                content,
                attached,
                // A conversation written before v0.7.8.0 Beta 3 has no
                // attachmentPaths array and simply reads back as the one path it
                // always had. Nothing stored is rewritten and no migration runs.
                readAttachmentPaths(m),
                m.optString("attachmentKind", attached ? "screen" : ""),
                m.optString("attachmentLabel", attached ? "Screen attached" : ""),
                m.optString("attachmentText", ""),
                m.optString("memoryUsage", ""),
                m.optString("memorySuggestionText", ""),
                m.optString("memorySuggestionCategory", ""),
                m.optString("stoppedRequestId", ""),
                readDocuments(m),
                // Absent from every message written before v0.7.8.5, and absent
                // from every answer that never had a picture. Missing means none,
                // which is what none already means, so nothing is migrated.
                readRichImages(m), m.optString("replyRequestId", ""), readSourceUrls(m),
                // Both absent before 0.8.3.0, and absent means none.
                QuotedMessage.fromJson(m.optJSONObject("quote")),
                ResponseDetails.fromJson(m.optJSONObject("details")));
    }

    /**
     * Stored forks, or none at all when anything about them is unreadable.
     *
     * <p>Damaged branch data costs the hidden alternatives, never the conversation: the visible path
     * is read separately and loads exactly as it would for a chat that never branched.
     */
    private static List<ConversationBranches.Fork> readForks(JSONArray stored) {
        List<ConversationBranches.Fork> forks = new ArrayList<>();
        if (stored == null) return forks;
        try {
            for (int i = 0; i < stored.length(); i++) {
                JSONObject f = stored.optJSONObject(i);
                if (f == null) continue;
                JSONArray variants = f.optJSONArray("variants");
                if (variants == null) continue;
                List<ConversationBranches.Path> paths = new ArrayList<>();
                for (int v = 0; v < variants.length(); v++) {
                    JSONObject path = variants.optJSONObject(v);
                    paths.add(path == null || path.optBoolean("active", false) ? null
                            : new ConversationBranches.Path(readMessages(path.optJSONArray("messages")),
                                    readForks(path.optJSONArray("forks"))));
                }
                forks.add(new ConversationBranches.Fork(f.optInt("at", -1), f.optString("parent", ""),
                        f.optInt("selected", -1), paths));
            }
        } catch (Exception damaged) {
            return new ArrayList<>();
        }
        return forks;
    }

    private static List<KeptContext> readKept(JSONArray stored) {
        List<KeptContext> items = new ArrayList<>();
        if (stored == null) return items;
        for (int i = 0; i < stored.length(); i++) {
            KeptContext item = KeptContext.fromJson(stored.optJSONObject(i));
            if (item != null) items.add(item);
        }
        return items;
    }

    private static void writeAll(Context c, List<Conversation> all) {
        writeAll(c, all, false);
    }

    private static void writeAll(Context c, List<Conversation> all, boolean sync) {
        JSONArray arr = new JSONArray();
        try {
            for (Conversation item : all) {
                JSONObject o = new JSONObject();
                o.put("id", item.id);
                o.put("title", item.title);
                o.put("titleOwner", item.titleOwner);
                o.put("titleJobState", item.titleJobState);
                if (!item.titleJobToken.isEmpty()) o.put("titleJobToken", item.titleJobToken);
                o.put("updatedAt", item.updatedAt);
                o.put("intelligenceMode", item.intelligenceMode);
                if (item.aiSelection != null) o.put("aiSelection", item.aiSelection.encode());
                // Written only when true, so an unpinned chat's record is byte-for-byte what it
                // was before pinning existed and a downgrade reads it back unchanged.
                if (item.pinned) o.put("pinned", true);
                o.put("messages", writeMessages(item.messages));
                // Written only when present, for the same reason: a chat that never branched and
                // keeps nothing is byte-for-byte what Beta 2 wrote.
                if (!item.forks.isEmpty()) o.put("forks", writeForks(item.forks));
                if (!item.kept.isEmpty()) {
                    JSONArray kept = new JSONArray();
                    for (KeptContext k : item.kept) kept.put(k.toJson());
                    o.put("kept", kept);
                }
                arr.put(o);
            }
        } catch (Exception ignored) {}
        SharedPreferences.Editor edit = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString());
        if (sync) edit.commit(); else edit.apply();
    }

    private static JSONArray writeForks(List<ConversationBranches.Fork> forks) throws Exception {
        JSONArray out = new JSONArray();
        for (ConversationBranches.Fork fork : forks) {
            JSONArray variants = new JSONArray();
            for (ConversationBranches.Path path : fork.variants) {
                if (path == null) {
                    // The visible alternative is the active path itself, stored once, above.
                    variants.put(new JSONObject().put("active", true));
                    continue;
                }
                JSONObject stored = new JSONObject().put("messages", writeMessages(path.messages));
                if (!path.forks.isEmpty()) stored.put("forks", writeForks(path.forks));
                variants.put(stored);
            }
            out.put(new JSONObject().put("at", fork.at).put("parent", fork.parent)
                    .put("selected", fork.selected).put("variants", variants));
        }
        return out;
    }

    private static JSONArray writeMessages(List<AssistantClient.History> messages) throws Exception {
        JSONArray msgs = new JSONArray();
        for (AssistantClient.History h : messages) {
            JSONObject message = new JSONObject();
            // Both are written: attachmentPath keeps a turn readable by anything that only
            // knows the old shape, and attachmentPaths is what a current Orbit reads. They
            // can never disagree because History derives the first from the list.
            if (h.attachmentPaths.size() > 1) {
                JSONArray paths = new JSONArray();
                for (String path : h.attachmentPaths) paths.put(path);
                message.put("attachmentPaths", paths);
            }
            if (!h.documents.isEmpty()) {
                JSONArray documents = new JSONArray();
                for (DocumentReference document : h.documents) {
                    // "page" is written only when the reference names one, so a record
                    // saved before page context existed reads back exactly as it was.
                    JSONObject entry = new JSONObject()
                            .put("path", safe(document.path))
                            .put("label", safe(document.label))
                            .put("pageCount", document.pageCount);
                    if (document.namesPage()) entry.put("page", document.page);
                    documents.put(entry);
                }
                message.put("documents", documents);
            }
            // Written only for an answer that has one, so a conversation of ordinary text
            // is byte-for-byte the document v0.7.8.4 wrote and an older build reading the
            // same store finds nothing new in it.
            if (!h.richImages.isEmpty()) {
                JSONArray pictures = new JSONArray();
                for (RichAnswerImage image : h.richImages) pictures.put(image.toJson());
                message.put("richImages", pictures);
            }
            if (!h.replyRequestId.isEmpty()) message.put("replyRequestId", h.replyRequestId);
            if (!h.sourceUrls.isEmpty()) message.put("sourceUrls", new JSONArray(h.sourceUrls));
            if (h.quote != null) message.put("quote", h.quote.toJson());
            if (h.details != null) message.put("details", h.details.toJson());
            msgs.put(message
                    .put("role", h.role)
                    .put("content", h.content)
                    .put("screenAttached", h.screenAttached)
                    .put("attachmentPath", safe(h.attachmentPath))
                    .put("attachmentKind", safe(h.attachmentKind))
                    .put("attachmentLabel", safe(h.attachmentLabel))
                    .put("attachmentText", safe(h.attachmentText))
                    .put("memoryUsage", safe(h.memoryUsage))
                    .put("memorySuggestionText", safe(h.memorySuggestionText))
                    .put("memorySuggestionCategory", safe(h.memorySuggestionCategory))
                    .put("stoppedRequestId", safe(h.stoppedRequestId)));
        }
        return msgs;
    }

    /**
     * A stored message's ordered image paths, whichever shape it was written in.
     *
     * <p>The array wins when present, and the single legacy field is the answer when it is not. A
     * record that somehow carries both keeps the array, because the array is a superset by
     * construction and the scalar is only ever its head.
     */
    private static List<String> readAttachmentPaths(JSONObject message) {
        List<String> paths = new ArrayList<>();
        JSONArray stored = message.optJSONArray("attachmentPaths");
        if (stored != null) {
            for (int i = 0; i < stored.length(); i++) {
                String path = stored.optString(i, "");
                if (path != null && !path.trim().isEmpty()) paths.add(path);
            }
        }
        if (paths.isEmpty()) {
            String legacy = message.optString("attachmentPath", "");
            if (legacy != null && !legacy.trim().isEmpty()) paths.add(legacy);
        }
        return paths;
    }

    private static List<String> readSourceUrls(JSONObject message) {
        List<String> urls = new ArrayList<>();
        JSONArray stored = message.optJSONArray("sourceUrls");
        if (stored != null) {
            for (int i = 0; i < stored.length() && i < AssistantReply.MAX_SOURCE_URLS; i++) {
                urls.add(stored.optString(i, ""));
            }
        }
        return urls;
    }

    /**
     * The sourced pictures a stored message carries, dropping anything damaged.
     *
     * <p>A malformed entry is skipped rather than allowed to fail the read, and a message whose
     * whole picture array is unreadable simply loads as text. Losing a picture is a small thing;
     * losing a conversation because a picture was written badly is not, and this is the boundary
     * where that choice is made.
     */
    private static List<RichAnswerImage> readRichImages(JSONObject message) {
        List<RichAnswerImage> images = new ArrayList<>();
        JSONArray stored = message.optJSONArray("richImages");
        if (stored == null) return images;
        for (int i = 0; i < stored.length(); i++) {
            JSONObject item = stored.optJSONObject(i);
            if (item == null) continue;
            RichAnswerImage image = RichAnswerImage.fromJson(item);
            if (image != null) images.add(image);
        }
        return images;
    }

    private static List<DocumentReference> readDocuments(JSONObject message) {
        List<DocumentReference> documents = new ArrayList<>();
        JSONArray stored = message.optJSONArray("documents");
        if (stored == null) return documents;
        for (int i = 0; i < stored.length(); i++) {
            JSONObject item = stored.optJSONObject(i);
            if (item == null) continue;
            DocumentReference document = new DocumentReference(item.optString("path", ""),
                    item.optString("label", "PDF"), item.optInt("pageCount", 0),
                    item.optInt("page", DocumentReference.WHOLE_DOCUMENT));
            if (document.isUsable()) documents.add(document);
        }
        return documents;
    }

    /**
     * Every private image file one conversation owns.
     *
     * <p>Read from the conversation's own record rather than from the filesystem, so nothing that
     * belongs to another chat, to a pending request, or to the last-screen cache can be caught up
     * in a deletion.
     */
    private static List<String> ownedAttachmentPaths(Conversation conversation) {
        List<String> paths = new ArrayList<>();
        if (conversation == null) return paths;
        // Hidden branches and answer variants own their files exactly as the visible path does,
        // so deleting a chat cleans them up and deleting another chat never touches them.
        List<AssistantClient.History> all = new ArrayList<>(conversation.messages);
        ConversationBranches.collectStoredMessages(conversation.forks, all);
        for (AssistantClient.History h : all) {
            if (h != null) {
                paths.addAll(h.attachmentPaths);
                for (DocumentReference document : h.documents) paths.add(document.path);
            }
        }
        for (KeptContext item : conversation.kept) {
            if (!item.documentPath.isEmpty()) paths.add(item.documentPath);
        }
        return paths;
    }

    /**
     * True when one list is a prefix of the other, message by message.
     *
     * <p>The test a save of a branched chat must pass: an append to the stored path, or a shorter
     * copy of it. Uses the same notion of "same message" the alignment code does.
     */
    /**
     * How many leading messages of {@code incoming} the stored path has since clipped away, or -1
     * when the two do not line up that way. At least two stored messages must agree, for the same
     * reason the alignment code refuses a single matching message: it could be a coincidence.
     */
    private static int frontClipOffset(List<AssistantClient.History> incoming,
                                       List<AssistantClient.History> stored) {
        if (incoming == null || stored == null || stored.size() < 2) return -1;
        for (int k = 1; k + 1 < incoming.size(); k++) {
            List<AssistantClient.History> rest = incoming.subList(k, incoming.size());
            if (Math.min(rest.size(), stored.size()) >= 2 && agreesAsPrefix(rest, stored)) return k;
        }
        return -1;
    }

    private static boolean agreesAsPrefix(List<AssistantClient.History> a,
                                          List<AssistantClient.History> b) {
        if (a == null || b == null) return false;
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) if (!sameMessage(a.get(i), b.get(i))) return false;
        return true;
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String validTitleOwner(String value) {
        if (TITLE_DEFAULT.equals(value) || TITLE_AUTOMATIC.equals(value)
                || TITLE_MANUAL.equals(value) || TITLE_LEGACY.equals(value)) return value;
        return TITLE_LEGACY;
    }

    private static String validTitleJobState(String value) {
        if (TITLE_JOB_IDLE.equals(value) || TITLE_JOB_PENDING.equals(value)
                || TITLE_JOB_DONE.equals(value)) return value;
        return TITLE_JOB_DONE;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
