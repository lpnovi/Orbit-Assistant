package com.orbit.assistant;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What Orbit suggested about one saved item, kept apart from everything the user wrote.
 *
 * <p>A separate object rather than values poured into the item's own fields, because the whole of
 * Smart Vault's promise rests on the difference. {@link OrbitVaultItem#title} is the user's; a
 * suggested title lives here and is only ever <em>displayed</em> in its place while the user has
 * not named the item themselves. Topics the user confirmed live on the item; topics Orbit merely
 * proposed live here, and so do the ones the user rejected, so regenerating can never bring back
 * something they already removed.
 *
 * <p>{@link #basis} is the content fingerprint the suggestions were written from. When the saved
 * content changes, the suggestions describe something that no longer exists, and the screen can
 * say so rather than presenting a stale summary as current.
 */
final class VaultSuggestions {

    static final int MAX_TITLE_CHARS = 80;
    static final int MAX_SUMMARY_CHARS = 320;
    static final int MAX_TOPICS = 3;
    static final int MAX_REJECTED = 24;

    final String title;
    final String summary;
    /** Proposed topics, normalised, never containing a rejected one. */
    final List<String> topics;
    /** Topics the user removed. Never suggested again for this item. */
    final List<String> rejected;
    final String basis;
    final long generatedAt;
    /** Which provider wrote them, for the item's own details. Display only. */
    final String provider;

    VaultSuggestions(String title, String summary, List<String> topics, List<String> rejected,
                     String basis, long generatedAt, String provider) {
        this.title = bound(collapse(title), MAX_TITLE_CHARS);
        this.summary = bound(collapse(summary), MAX_SUMMARY_CHARS);
        List<String> cleanRejected = new ArrayList<>();
        if (rejected != null) {
            for (String topic : rejected) {
                String t = VaultTopics.normalize(topic);
                if (!t.isEmpty() && !cleanRejected.contains(t)) cleanRejected.add(t);
                if (cleanRejected.size() >= MAX_REJECTED) break;
            }
        }
        List<String> cleanTopics = new ArrayList<>();
        if (topics != null) {
            for (String topic : topics) {
                String t = VaultTopics.normalize(topic);
                if (t.isEmpty() || cleanTopics.contains(t) || cleanRejected.contains(t)) continue;
                cleanTopics.add(t);
                if (cleanTopics.size() >= MAX_TOPICS) break;
            }
        }
        this.topics = Collections.unmodifiableList(cleanTopics);
        this.rejected = Collections.unmodifiableList(cleanRejected);
        this.basis = basis == null ? "" : basis.trim();
        this.generatedAt = Math.max(0L, generatedAt);
        this.provider = bound(collapse(provider), 40);
    }

    /** Whether anything is left worth showing. Rejections alone are remembered, never shown. */
    boolean hasContent() {
        return !title.isEmpty() || !summary.isEmpty() || !topics.isEmpty();
    }

    /** Whether anything at all needs to be stored, including remembered rejections. */
    boolean isEmpty() {
        return !hasContent() && rejected.isEmpty();
    }

    VaultSuggestions withoutTitle() {
        return new VaultSuggestions("", summary, topics, rejected, basis, generatedAt, provider);
    }

    VaultSuggestions withoutSummary() {
        return new VaultSuggestions(title, "", topics, rejected, basis, generatedAt, provider);
    }

    /** The same suggestions with one topic removed and remembered as unwanted. */
    VaultSuggestions rejecting(String topic) {
        String t = VaultTopics.normalize(topic);
        List<String> nextTopics = new ArrayList<>(topics);
        nextTopics.remove(t);
        List<String> nextRejected = new ArrayList<>(rejected);
        if (!t.isEmpty() && !nextRejected.contains(t)) nextRejected.add(0, t);
        return new VaultSuggestions(title, summary, nextTopics, nextRejected, basis, generatedAt,
                provider);
    }

    /** The same suggestions with one topic moved out because the user confirmed it. */
    VaultSuggestions without(String topic) {
        List<String> nextTopics = new ArrayList<>(topics);
        nextTopics.remove(VaultTopics.normalize(topic));
        return new VaultSuggestions(title, summary, nextTopics, rejected, basis, generatedAt,
                provider);
    }

    /** Only what the user removed, so clearing suggestions still never brings a rejection back. */
    VaultSuggestions onlyRejections() {
        return new VaultSuggestions("", "", null, rejected, basis, 0L, "");
    }

    JSONObject toJson() throws Exception {
        JSONObject out = new JSONObject();
        if (!title.isEmpty()) out.put("title", title);
        if (!summary.isEmpty()) out.put("summary", summary);
        if (!topics.isEmpty()) out.put("topics", new JSONArray(topics));
        if (!rejected.isEmpty()) out.put("rejected", new JSONArray(rejected));
        if (!basis.isEmpty()) out.put("basis", basis);
        if (generatedAt > 0L) out.put("generatedAt", generatedAt);
        if (!provider.isEmpty()) out.put("provider", provider);
        return out;
    }

    static VaultSuggestions fromJson(JSONObject o) {
        if (o == null) return null;
        VaultSuggestions parsed = new VaultSuggestions(o.optString("title", ""),
                o.optString("summary", ""), strings(o.optJSONArray("topics")),
                strings(o.optJSONArray("rejected")), o.optString("basis", ""),
                o.optLong("generatedAt", 0L), o.optString("provider", ""));
        return parsed.isEmpty() ? null : parsed;
    }

    static List<String> strings(JSONArray array) {
        List<String> out = new ArrayList<>();
        if (array == null) return out;
        for (int i = 0; i < array.length() && i < 64; i++) {
            String value = array.optString(i, "");
            if (!value.trim().isEmpty()) out.add(value);
        }
        return out;
    }

    private static String collapse(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String bound(String value, int max) {
        if (value.length() <= max) return value;
        String clipped = value.substring(0, max);
        int space = clipped.lastIndexOf(' ');
        if (space > max / 2) clipped = clipped.substring(0, space);
        return clipped.trim();
    }
}
