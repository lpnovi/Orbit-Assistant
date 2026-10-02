package com.orbit.assistant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How one Orbit Local request fits inside the local model's context window.
 *
 * <p>A cloud model reads tens of thousands of tokens; Orbit Local's chat model reads 4096, and its
 * answer has to fit in the same window. Concatenating everything Orbit prepared for a turn - memory,
 * history, a document, five saved items, a day of notifications - would overflow it, and cutting
 * the result at a fixed length would throw away whatever happened to come last, which is often the
 * passage that answers the question.
 *
 * <p>So every part of the prompt gets an explicit share, in this order of priority:
 *
 * <ol>
 *   <li>Orbit's own instructions, and headroom for the answer. Never negotiable.</li>
 *   <li>The user's current question. Only an extraordinarily long one is shortened, and then its
 *       beginning and end are both kept.</li>
 *   <li>The evidence the turn is about: Ask Vault passages, notification history, attachment text,
 *       or screen text. Long evidence is reduced to its passages most relevant to the question
 *       rather than truncated.</li>
 *   <li>Orbit Memory, bounded.</li>
 *   <li>Recent conversation, newest first, with whatever room is left.</li>
 * </ol>
 *
 * <p>Every piece of user or third-party content is placed inside a clearly marked untrusted block
 * after being neutralised, so a saved note, a notification or a document cannot close that block
 * early or impersonate a conversation turn. Nothing it contains can reach the instructions above it.
 *
 * <p>Pure Java with no Android dependency, so the whole budget is exercised by ordinary tests. The
 * window size is a parameter rather than a constant, because a future Enhanced model with a larger
 * window should get a larger budget from the same rules, not a second implementation.
 */
final class LocalContextBudget {

    /** The packaged chat model's window: Qwen 2.5 1.5B Instruct, exported with a 4096-token cache. */
    static final int CHAT_MODEL_CONTEXT_TOKENS = 4096;
    /** Reserved for the answer. A reply longer than this is rare on a phone and still fits. */
    static final int OUTPUT_HEADROOM_TOKENS = 1024;

    /** Most saved items one local answer draws on. Two or three good passages beat five weak ones. */
    static final int MAX_VAULT_ITEMS = 3;
    /** Most recent conversation turns carried. */
    static final int MAX_HISTORY_TURNS = 6;
    /** Longest single history turn carried, in characters. */
    static final int MAX_HISTORY_TURN_CHARS = 600;
    /** Longest single notification line carried, in characters. */
    static final int MAX_NOTIFICATION_LINE_CHARS = 260;

    /** The paths a local request can take, as Diagnostics tokens. */
    static final String PATH_CHAT = "chat";
    static final String PATH_ASK_VAULT = "ask-vault";
    static final String PATH_NOTIFICATIONS = "notifications";
    static final String PATH_ATTACHMENTS = "attachments";
    static final String PATH_SCREEN = "screen";
    static final String PATH_IMAGE_ONLY = "image-only";

    private LocalContextBudget() {}

    /** Everything Orbit prepared for this turn, before any of it is fitted. */
    static final class Input {
        String system = "";
        String memory = "";
        List<AssistantClient.History> history = Collections.emptyList();
        /** Attachment text when {@link #explicitAttachment}, otherwise live screen text. */
        String screenText = "";
        boolean explicitAttachment;
        /** Whether standing screen context is allowed; explicit attachments ignore it. */
        boolean screenContextAllowed;
        String notificationContext = "";
        String trustedTaskContext = "";
        String prompt = "";
        /** The earlier message the current turn replies to, as an untrusted block, or "". */
        String quote = "";
        /** Images the turn carries. Orbit Local cannot see any of them. */
        int imageCount;
        /** Optional meaning scorer for passage choice; lexical overlap is used without it. */
        PassageScorer scorer;
        int contextTokens = CHAT_MODEL_CONTEXT_TOKENS;
    }

    /** Optional semantic similarity between a question and a passage, higher is closer. */
    interface PassageScorer {
        double similarity(String question, String passage);
    }

    /** One saved item that reached the model, as the user will see it named. */
    static final class VaultSource {
        final int number;
        final String title;

        VaultSource(int number, String title) {
            this.number = number;
            this.title = title == null ? "" : title;
        }
    }

    /** The fitted prompt, plus a description of it made of counts and nothing private. */
    static final class Result {
        String prompt = "";
        String path = PATH_CHAT;
        int estimatedTokens;
        int inputBudgetTokens;
        int vaultItemsOffered;
        final List<VaultSource> vaultSources = new ArrayList<>();
        int notificationsFound;
        int notificationsUsed;
        int attachmentSegments;
        int attachmentCharsIn;
        int attachmentCharsUsed;
        boolean evidenceTrimmed;
        boolean memoryUsed;
        boolean memoryTrimmed;
        int historyTurnsUsed;
        boolean promptShortened;
        boolean screenUsed;
        int imageCount;
        /** True when the turn is pictures and nothing Orbit Local could read. */
        boolean imageOnly;

        /** A one-line, content-free summary for Diagnostics. */
        String sourcesSummary() {
            StringBuilder s = new StringBuilder();
            s.append("vault ").append(vaultSources.size()).append('/').append(vaultItemsOffered);
            s.append(", notifications ").append(notificationsUsed).append('/').append(notificationsFound);
            s.append(", attachments ").append(attachmentSegments);
            if (attachmentCharsIn > 0) {
                s.append(" (").append(attachmentCharsUsed).append(" of ")
                        .append(attachmentCharsIn).append(" chars)");
            }
            s.append(", images ").append(imageCount);
            s.append(", screen ").append(screenUsed ? "yes" : "no");
            s.append(", memory ").append(memoryUsed ? (memoryTrimmed ? "trimmed" : "yes") : "no");
            s.append(", history ").append(historyTurnsUsed);
            return s.toString();
        }
    }

    // ---- token accounting -------------------------------------------------------------------------

    /**
     * A deliberately pessimistic token estimate.
     *
     * <p>English runs at about four characters per token in Qwen's tokenizer; counting 3.3 leaves a
     * margin for numbers, punctuation and names. Anything outside ASCII - accented words, emoji,
     * Chinese, Japanese - is counted as a whole token per character, because for many scripts that
     * is close to the truth and an overflow is worse than a slightly shorter excerpt.
     */
    static int estimateTokens(String s) {
        if (s == null || s.isEmpty()) return 0;
        double tokens = 0;
        for (int i = 0; i < s.length(); i++) {
            tokens += s.charAt(i) < 128 ? 0.3 : 1.0;
        }
        return (int) Math.ceil(tokens);
    }

    /** The longest prefix of {@code s} whose estimate fits {@code tokens}, cut at a word if possible. */
    static String fitTokens(String s, int tokens) {
        if (s == null || s.isEmpty() || tokens <= 0) return "";
        if (estimateTokens(s) <= tokens) return s;
        double used = 0;
        int end = 0;
        while (end < s.length()) {
            double next = used + (s.charAt(end) < 128 ? 0.3 : 1.0);
            if (next > tokens) break;
            used = next;
            end++;
        }
        int space = s.lastIndexOf(' ', end);
        if (space > end * 3 / 4) end = space;
        return s.substring(0, end).trim();
    }

    /** Roughly how many characters of this text fit a token budget. */
    private static int charsFor(String text, int tokens) {
        if (tokens <= 0) return 0;
        int t = Math.max(1, estimateTokens(text));
        double density = t / (double) Math.max(1, text.length());
        return (int) Math.floor(tokens / Math.max(0.3, density));
    }

    static int inputBudgetTokens(int contextTokens) {
        return Math.max(512, contextTokens - OUTPUT_HEADROOM_TOKENS);
    }

    /**
     * A share tuned for the packaged 4096-token model, grown or shrunk with the actual budget, so a
     * model with a larger window gets proportionally more of everything from the same rules.
     */
    static int scaled(int tokensAt4k, int budget) {
        return (int) Math.round(tokensAt4k * (budget / (double) inputBudgetTokens(CHAT_MODEL_CONTEXT_TOKENS)));
    }

    // ---- neutralising untrusted text --------------------------------------------------------------

    private static final Pattern TAG_LIKE = Pattern.compile(
            "(?i)<\\s*(/?)\\s*(untrusted|vault_item|orbit_|system|instructions)");
    private static final Pattern ROLE_LINE = Pattern.compile(
            "(?im)^(\\s*)(user|orbit|assistant|system)\\s*:");

    /**
     * Makes a piece of untrusted text inert inside the prompt.
     *
     * <p>Two things are neutralised. A tag that looks like one of Orbit's own delimiters could close
     * the untrusted block early and put the rest of a saved note or notification outside it; its
     * angle bracket is replaced so it reads as text. And a line beginning "User:" or "Orbit:" would
     * look to a small model like a turn of the conversation; it is rewritten so it cannot. Nothing
     * else about the text changes.
     */
    static String neutralize(String s) {
        if (s == null || s.isEmpty()) return "";
        String out = TAG_LIKE.matcher(s).replaceAll("[$1$2");
        out = ROLE_LINE.matcher(out).replaceAll("$1$2 said -");
        return out;
    }

    // ---- the build --------------------------------------------------------------------------------

    static Result build(Input in) {
        Result r = new Result();
        int budget = inputBudgetTokens(in.contextTokens);
        r.inputBudgetTokens = budget;
        r.imageCount = Math.max(0, in.imageCount);

        String attachmentText = in.explicitAttachment ? safe(in.screenText).trim() : "";
        String screenText = !in.explicitAttachment && in.screenContextAllowed
                ? safe(in.screenText).trim() : "";
        String notifications = safe(in.notificationContext).trim();
        String question = safe(in.prompt).trim();

        if (in.explicitAttachment && r.imageCount > 0 && attachmentText.isEmpty()
                && notifications.isEmpty()) {
            // Pictures and nothing Orbit could read from them. The provider answers this honestly
            // itself; nothing is sent to the model to guess about an image it cannot see.
            r.imageOnly = true;
            r.path = PATH_IMAGE_ONLY;
            return r;
        }

        // ---- 1. fixed parts ------------------------------------------------------------------
        StringBuilder trusted = new StringBuilder();
        String task = safe(in.trustedTaskContext).trim();
        if (!task.isEmpty()) {
            trusted.append("\n\nOrbit task state from the user's own corrections:\n")
                    .append(fitTokens(task, 120));
        }
        if (in.explicitAttachment && r.imageCount > 0) {
            trusted.append("\n\nThe user attached ").append(r.imageCount)
                    .append(r.imageCount == 1 ? " picture" : " pictures")
                    .append(". You cannot see pictures. You only have any text Orbit read from them, ")
                    .append("shown below. If the question needs the picture itself, say so plainly.");
        }

        // A quoted message is bounded data the user pointed at. It rides with the question so the
        // small model knows what "this" refers to, and is counted in the fixed share like it.
        String quoteBlock = fitTokens(safe(in.quote).trim(), Math.max(60, budget / 8));
        String shownQuestion = question;
        int questionCap = Math.max(200, budget / 4);
        if (estimateTokens(question) > questionCap) {
            // Both ends of a long message usually matter: the context at the top and the actual
            // request at the bottom. The middle is what gives way.
            int head = questionCap * 2 / 3;
            String headPart = fitTokens(question, head);
            String tail = lastTokens(question, questionCap - head - 4);
            shownQuestion = headPart + " [...] " + tail;
            r.promptShortened = true;
        }

        String scaffold = "\n\nConversation so far:\n\nUser: \nOrbit:";
        int fixed = estimateTokens(in.system) + estimateTokens(trusted.toString())
                + estimateTokens(shownQuestion) + estimateTokens(quoteBlock)
                + estimateTokens(scaffold) + 8;
        int remaining = Math.max(0, budget - fixed);

        List<AssistantClient.History> turns = priorTurns(in.history, question);
        boolean hasHistory = !turns.isEmpty();
        String memory = safe(in.memory).trim();

        boolean hasEvidence = !attachmentText.isEmpty() || !notifications.isEmpty()
                || !screenText.isEmpty();
        int memoryShare = memory.isEmpty() ? 0 : Math.min(estimateTokens(memory),
                Math.min(scaled(220, budget), remaining / 6));
        int historyFloor = hasHistory ? Math.min(remaining / 6, scaled(260, budget)) : 0;
        int evidenceShare = hasEvidence
                ? Math.max(0, Math.min(scaled(1900, budget),
                        remaining - memoryShare - historyFloor)) : 0;

        // ---- 2. evidence ---------------------------------------------------------------------
        StringBuilder evidence = new StringBuilder();
        if (hasEvidence) {
            int notificationShare = 0;
            int attachmentShare = 0;
            int screenShare = 0;
            int parts = (notifications.isEmpty() ? 0 : 1) + (attachmentText.isEmpty() ? 0 : 1)
                    + (screenText.isEmpty() ? 0 : 1);
            if (parts == 1) {
                notificationShare = notifications.isEmpty() ? 0 : evidenceShare;
                attachmentShare = attachmentText.isEmpty() ? 0 : evidenceShare;
                screenShare = screenText.isEmpty() ? 0 : Math.min(evidenceShare, scaled(1000, budget));
            } else {
                // What the user explicitly handed over outranks what Orbit gathered around it.
                attachmentShare = attachmentText.isEmpty() ? 0 : evidenceShare * 3 / 5;
                int rest = evidenceShare - attachmentShare;
                int others = (notifications.isEmpty() ? 0 : 1) + (screenText.isEmpty() ? 0 : 1);
                notificationShare = notifications.isEmpty() ? 0 : rest / Math.max(1, others);
                screenShare = screenText.isEmpty() ? 0 : Math.min(scaled(1000, budget), rest / Math.max(1, others));
            }

            if (!attachmentText.isEmpty()) {
                evidence.append(attachments(attachmentText, question, attachmentShare, in.scorer, r));
            }
            if (!notifications.isEmpty()) {
                evidence.append(notifications(notifications, notificationShare, r));
            }
            if (!screenText.isEmpty()) {
                String excerpt = excerpt(screenText, question, screenShare, null);
                if (!excerpt.isEmpty()) {
                    r.screenUsed = true;
                    if (excerpt.length() < screenText.length()) r.evidenceTrimmed = true;
                    evidence.append("\n\n<untrusted_screen_content>\n")
                            .append(neutralize(excerpt))
                            .append("\n</untrusted_screen_content>");
                }
            }
        }

        // ---- 3. memory -----------------------------------------------------------------------
        String memoryBlock = "";
        if (memoryShare > 0) {
            String fitted = fitTokens(memory, memoryShare);
            if (!fitted.isEmpty()) {
                r.memoryUsed = true;
                r.memoryTrimmed = fitted.length() < memory.length();
                memoryBlock = "\n\n" + fitted;
            }
        }

        // ---- 4. history, newest first, with what is left ---------------------------------------
        int used = fixed + estimateTokens(evidence.toString()) + estimateTokens(memoryBlock);
        int historyShare = Math.max(0, budget - used);
        List<String> lines = new ArrayList<>();
        for (int i = turns.size() - 1; i >= 0 && lines.size() < MAX_HISTORY_TURNS; i--) {
            AssistantClient.History h = turns.get(i);
            String role = "assistant".equalsIgnoreCase(h.role) ? "Orbit: " : "User: ";
            String content = h.content.trim();
            if (content.length() > MAX_HISTORY_TURN_CHARS) {
                content = content.substring(0, MAX_HISTORY_TURN_CHARS).trim() + " [...]";
            }
            String rendered = RenderedResultContext.block(h, i == turns.size() - 1)
                    .replace('\n', ' ');
            String line = role + neutralize(RenderedResultContext.neutralizeMarkers(content))
                    .replace('\n', ' ') + rendered + "\n";
            int cost = estimateTokens(line);
            if (cost > historyShare) break;
            historyShare -= cost;
            lines.add(0, line);
        }
        r.historyTurnsUsed = lines.size();

        // ---- 5. assemble -------------------------------------------------------------------------
        StringBuilder p = new StringBuilder();
        p.append(in.system);
        p.append(trusted);
        p.append(memoryBlock);
        p.append(evidence);
        p.append("\n\nConversation so far:\n");
        for (String line : lines) p.append(line);
        p.append("\nUser: ").append(shownQuestion);
        if (!quoteBlock.isEmpty()) p.append("\n").append(quoteBlock);
        p.append("\nOrbit:");
        r.prompt = p.toString();
        r.estimatedTokens = estimateTokens(r.prompt);
        r.path = !r.vaultSources.isEmpty() ? PATH_ASK_VAULT
                : r.notificationsUsed > 0 ? PATH_NOTIFICATIONS
                : r.attachmentSegments > 0 ? PATH_ATTACHMENTS
                : r.screenUsed ? PATH_SCREEN : PATH_CHAT;
        return r;
    }

    // ---- attachments and Ask Vault ----------------------------------------------------------------

    private static final Pattern ATTACHMENT_HEADER = Pattern.compile(
            "\\n\\n--- Attachment (\\d+) of (\\d+): (.*?) ---\\n");
    private static final Pattern VAULT_ITEM = Pattern.compile(
            "<vault_item number=\"(\\d+)\" title=\"([^\"]*)\">\\n?(.*?)</vault_item>",
            Pattern.DOTALL);

    /** One attachment in the order the user attached it. */
    private static final class Segment {
        final String label;
        final String text;

        Segment(String label, String text) {
            this.label = label;
            this.text = text;
        }
    }

    /** Splits the combined attachment text Orbit builds back into the attachments it came from. */
    static List<String[]> splitAttachments(String text) {
        List<String[]> out = new ArrayList<>();
        Matcher m = ATTACHMENT_HEADER.matcher(text);
        int last = -1;
        String lastLabel = "";
        while (m.find()) {
            if (last >= 0) out.add(new String[]{lastLabel, text.substring(last, m.start()).trim()});
            lastLabel = m.group(3);
            last = m.end();
        }
        if (last < 0) {
            out.add(new String[]{"", text.trim()});
        } else {
            out.add(new String[]{lastLabel, text.substring(last).trim()});
        }
        return out;
    }

    private static String attachments(String text, String question, int share,
                                      PassageScorer scorer, Result r) {
        List<Segment> segments = new ArrayList<>();
        for (String[] part : splitAttachments(text)) {
            if (!part[1].isEmpty()) segments.add(new Segment(part[0], part[1]));
        }
        if (segments.isEmpty()) return "";
        r.attachmentSegments = segments.size();

        StringBuilder out = new StringBuilder();
        // Shares are handed out smallest first, so a short clipboard note travels whole and the
        // room it did not need goes to the long document beside it.
        List<Segment> bySize = new ArrayList<>(segments);
        bySize.sort((a, b) -> Integer.compare(estimateTokens(a.text), estimateTokens(b.text)));
        java.util.Map<Segment, Integer> shares = new java.util.IdentityHashMap<>();
        int left = share;
        for (int i = 0; i < bySize.size(); i++) {
            Segment s = bySize.get(i);
            int fair = left / (bySize.size() - i);
            int want = estimateTokens(s.text) + 20;
            int given = Math.min(fair, want);
            shares.put(s, given);
            left -= given;
        }

        int position = 0;
        for (Segment s : segments) {
            position++;
            int segmentShare = shares.get(s);
            r.attachmentCharsIn += s.text.length();
            if (s.text.startsWith(SmartVaultAsk.FRAMING)) {
                out.append(vault(s.text, question, segmentShare, scorer, r));
                continue;
            }
            String excerpt = excerpt(s.text, question, segmentShare, scorer);
            r.attachmentCharsUsed += excerpt.length();
            if (excerpt.length() < s.text.length()) r.evidenceTrimmed = true;
            out.append("\n\n<untrusted_attachment");
            if (segments.size() > 1) out.append(" number=\"").append(position).append('"');
            if (!s.label.isEmpty()) {
                out.append(" label=\"").append(neutralize(s.label).replace("\"", "'")).append('"');
            }
            out.append(">\n").append(neutralize(excerpt)).append("\n</untrusted_attachment>");
        }
        return out.toString();
    }

    /**
     * Ask Vault, fitted for a small model.
     *
     * <p>Smart Vault has already searched and ranked. What happens here is the last, local step:
     * the best few items are kept, any whose content shares nothing with the question while a
     * better-ranked item does is dropped, and each survivor is reduced to its passages most relevant
     * to the question. Items keep the numbers the user was shown, so a citation still points at the
     * right thing.
     */
    private static String vault(String text, String question, int share, PassageScorer scorer,
                                Result r) {
        List<String[]> items = new ArrayList<>();
        Matcher m = VAULT_ITEM.matcher(text);
        while (m.find()) items.add(new String[]{m.group(1), m.group(2), m.group(3).trim()});
        r.vaultItemsOffered += items.size();
        if (items.isEmpty()) return "";

        java.util.Set<String> wanted = new java.util.HashSet<>(SmartVaultText.terms(question));
        double[] score = new double[items.size()];
        double best = 0;
        for (int i = 0; i < items.size(); i++) {
            String body = items.get(i)[1] + " " + items.get(i)[2];
            for (String t : new java.util.HashSet<>(SmartVaultText.terms(body))) {
                if (wanted.contains(t)) score[i] += 1;
            }
            if (scorer != null) score[i] += 4 * Math.max(0, scorer.similarity(question, body));
            best = Math.max(best, score[i]);
        }
        List<Integer> kept = new ArrayList<>();
        for (int i = 0; i < items.size() && kept.size() < MAX_VAULT_ITEMS; i++) {
            // Rank order is Smart Vault's and is kept. The only thing added here is refusing to
            // spend a scarce share on an item with nothing in common with the question when a
            // better one exists.
            if (best > 0 && score[i] < best * 0.25) continue;
            kept.add(i);
        }
        if (kept.isEmpty()) kept.add(0);

        StringBuilder out = new StringBuilder();
        out.append("\n\n<untrusted_vault_items>\n");
        int each = Math.max(60, (share - 30) / kept.size());
        for (int index : kept) {
            String[] item = items.get(index);
            int number;
            try { number = Integer.parseInt(item[0]); } catch (NumberFormatException e) { number = index + 1; }
            String title = item[1];
            String excerpt = excerpt(item[2], question, each - estimateTokens(title) - 12, scorer);
            r.attachmentCharsUsed += excerpt.length();
            if (excerpt.length() < item[2].length()) r.evidenceTrimmed = true;
            r.vaultSources.add(new VaultSource(number, title));
            out.append("[").append(number).append(": ")
                    .append(neutralize(title).replace('\n', ' ')).append("]\n")
                    .append(neutralize(excerpt)).append("\n\n");
        }
        out.append("</untrusted_vault_items>");
        return out.toString();
    }

    /**
     * The part of a text most relevant to the question, within a token share.
     *
     * <p>Short text travels whole. Longer text is reduced to its most relevant passages by
     * {@link #relevantPart}, then fitted exactly, so an excerpt can never exceed its share.
     */
    static String excerpt(String text, String question, int tokens, PassageScorer scorer) {
        if (text == null || text.isEmpty() || tokens <= 0) return "";
        if (estimateTokens(text) <= tokens) return text;
        int chars = charsFor(text, tokens);
        return fitTokens(relevantPart(text, question, scorer, Math.max(80, chars - 12)), tokens);
    }

    /** Characters per passage: a few sentences, small enough to be about one thing. */
    static final int PASSAGE_CHARS = 480;
    static final int PASSAGE_OVERLAP = 80;
    /**
     * Passages considered in one text. Enough to cover all the text Orbit extracts from a PDF, so a
     * fact on the last page competes on equal terms with one on the first.
     */
    static final int MAX_PASSAGES = 260;
    /** Passages a meaning scorer is asked about, because each one costs an embedding. */
    static final int MAX_SCORED_PASSAGES = 48;

    /**
     * Overlapping windows over the whole of a text, cut at whitespace where one is near.
     *
     * <p>Deliberately separate from Smart Vault's indexing windows, which stop early to bound
     * index size. Here nothing is stored, and stopping early would mean a question about the end
     * of a long document could never find its answer.
     */
    static List<String> passages(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        String flat = text.replaceAll("\s+", " ").trim();
        int start = 0;
        while (start < flat.length() && out.size() < MAX_PASSAGES) {
            int end = Math.min(flat.length(), start + PASSAGE_CHARS);
            if (end < flat.length()) {
                int space = flat.lastIndexOf(' ', end);
                if (space > start + PASSAGE_CHARS / 2) end = space;
            }
            out.add(flat.substring(start, end).trim());
            if (end >= flat.length()) break;
            int next = end - PASSAGE_OVERLAP;
            if (next <= start) next = end;
            int space = flat.indexOf(' ', next);
            start = space > 0 && space < end ? space + 1 : next;
        }
        return out;
    }

    /**
     * The passages of a text most relevant to a question, in their original order, within
     * {@code maxChars}.
     *
     * <p>A passage scores a point for each distinct question word it contains, and - when Smart
     * Vault's meaning model is available - up to four more for closeness in meaning. The opening
     * passage gets a small bonus because it usually says what the text is. Gaps between passages
     * that were not adjacent are marked, so the model does not read two distant sentences as one.
     */
    static String relevantPart(String text, String question, PassageScorer scorer, int maxChars) {
        List<String> passages = passages(text);
        if (passages.isEmpty()) return "";
        java.util.Set<String> wanted = new java.util.HashSet<>(SmartVaultText.terms(question));
        double[] score = new double[passages.size()];
        for (int i = 0; i < passages.size(); i++) {
            for (String t : new java.util.HashSet<>(SmartVaultText.terms(passages.get(i)))) {
                if (wanted.contains(t)) score[i] += 1;
            }
            if (i == 0) score[i] += 0.5;
        }
        if (scorer != null) {
            // Meaning is asked only about the most promising passages by words, plus the opening,
            // so a long document cannot turn one question into hundreds of embeddings.
            Integer[] byWords = order(score);
            for (int k = 0; k < Math.min(MAX_SCORED_PASSAGES, byWords.length); k++) {
                int i = byWords[k];
                score[i] += 4 * Math.max(0, scorer.similarity(question, passages.get(i)));
            }
        }
        java.util.TreeSet<Integer> chosen = new java.util.TreeSet<>();
        int used = 0;
        for (int i : order(score)) {
            int len = passages.get(i).length() + 3;
            if (used + len > maxChars && !chosen.isEmpty()) continue;
            chosen.add(i);
            used += len;
            if (used >= maxChars) break;
        }
        StringBuilder out = new StringBuilder();
        int previous = -2;
        for (int i : chosen) {
            if (out.length() > 0) {
                out.append(i == previous + 1 ? " " : " " + SmartVaultAsk.ELLIPSIS + " ");
            }
            // Overlapping neighbours repeat a few words; that costs less than a lost sentence.
            out.append(passages.get(i));
            previous = i;
        }
        return out.toString();
    }

    /** Passage indexes, best score first; ties keep document order. */
    private static Integer[] order(double[] score) {
        Integer[] order = new Integer[score.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> {
            int byScore = Double.compare(score[b], score[a]);
            return byScore != 0 ? byScore : Integer.compare(a, b);
        });
        return order;
    }

    // ---- notifications -----------------------------------------------------------------------------

    private static final Pattern WINDOW = Pattern.compile("The requested time window is (.*?)\\. ");

    /**
     * Notification history, one whole notification at a time.
     *
     * <p>{@link NotificationQueryHelper} has already applied the user's app exclusions and retention,
     * chosen the time window, and put notifications matching the question first. This keeps that
     * order and adds whole lines until the share is spent, then says how many were left out, so the
     * model never mistakes a partial list for everything that arrived.
     */
    private static String notifications(String context, int share, Result r) {
        String window = "";
        Matcher w = WINDOW.matcher(context);
        if (w.find()) window = w.group(1).trim();
        List<String> lines = new ArrayList<>();
        for (String line : context.split("\n")) {
            if (line.startsWith("[")) lines.add(line.trim());
        }
        r.notificationsFound = lines.size();
        boolean moreExisted = context.contains("were omitted from this AI context");

        StringBuilder body = new StringBuilder();
        int left = share - 60;
        int used = 0;
        for (String line : lines) {
            String bounded = line.length() > MAX_NOTIFICATION_LINE_CHARS
                    ? line.substring(0, MAX_NOTIFICATION_LINE_CHARS).trim() + " [...]" : line;
            String entry = neutralize(bounded) + "\n";
            int cost = estimateTokens(entry);
            if (cost > left) break;
            left -= cost;
            body.append(entry);
            used++;
        }
        r.notificationsUsed = used;
        if (used < lines.size() || moreExisted) r.evidenceTrimmed = true;

        StringBuilder out = new StringBuilder();
        out.append("\n\n<untrusted_notifications");
        if (!window.isEmpty()) {
            out.append(" window=\"").append(neutralize(window).replace("\"", "'")).append('"');
        }
        out.append(">\n").append(body);
        out.append("Showing ").append(used).append(" of ")
                .append(moreExisted ? "more than " + lines.size() : String.valueOf(lines.size()))
                .append(" notifications Orbit found for this question.\n");
        out.append("</untrusted_notifications>");
        return out.toString();
    }

    // ---- helpers -------------------------------------------------------------------------------------

    /** Prior turns only: the current question travels separately and is never counted twice. */
    private static List<AssistantClient.History> priorTurns(List<AssistantClient.History> history,
                                                            String question) {
        List<AssistantClient.History> out = new ArrayList<>();
        if (history == null) return out;
        int end = history.size();
        if (end > 0) {
            AssistantClient.History last = history.get(end - 1);
            if (last != null && "user".equalsIgnoreCase(last.role)
                    && question.equals(last.content == null ? "" : last.content.trim())) end--;
        }
        for (int i = 0; i < end; i++) {
            AssistantClient.History h = history.get(i);
            if (h == null || h.content == null || h.content.trim().isEmpty()) continue;
            out.add(h);
        }
        return out;
    }

    private static String lastTokens(String s, int tokens) {
        if (tokens <= 0 || s.isEmpty()) return "";
        double used = 0;
        int start = s.length();
        while (start > 0) {
            double next = used + (s.charAt(start - 1) < 128 ? 0.3 : 1.0);
            if (next > tokens) break;
            used = next;
            start--;
        }
        int space = s.indexOf(' ', start);
        if (space > 0 && space - start < 40) start = space + 1;
        return s.substring(start).trim();
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
