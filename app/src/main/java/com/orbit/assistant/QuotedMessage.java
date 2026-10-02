package com.orbit.assistant;

import org.json.JSONObject;

/**
 * An earlier message the user is replying to.
 *
 * <p>The point is that the model never has to guess what "this" means: the turn carries an explicit
 * copy of the message being pointed at. That copy is conversation <em>data</em>. It travels inside
 * the user's own message, wrapped in a tag marked untrusted, exactly like screen context and
 * attachments, so an earlier answer that happened to contain instructions cannot become one.
 */
public final class QuotedMessage {
    /** Enough to identify and reason about the message, small enough not to crowd the request. */
    public static final int MAX_CHARS = 1500;
    /** What the composer card and the sent bubble show. */
    public static final int PREVIEW_CHARS = 140;
    static final String TAG = "orbit_quoted_message";

    public final String role;
    public final String text;

    QuotedMessage(String role, String text) {
        this.role = "assistant".equalsIgnoreCase(role) ? "assistant" : "user";
        String clean = text == null ? "" : text.trim();
        this.text = clean.length() <= MAX_CHARS ? clean : clean.substring(0, MAX_CHARS).trim() + "…";
    }

    /** A quote of what the user can see in that message, or null when there is nothing to quote. */
    public static QuotedMessage of(AssistantClient.History message) {
        if (message == null || message.content == null) return null;
        boolean assistant = "assistant".equalsIgnoreCase(message.role);
        String visible = assistant ? SourceLinkUtil.displayText(message.content) : message.content;
        visible = visible == null ? "" : visible.replace("—", "-").trim();
        return visible.isEmpty() ? null : new QuotedMessage(message.role, visible);
    }

    public boolean fromAssistant() { return "assistant".equals(role); }

    /** "Orbit" or "You". */
    public String speaker() { return fromAssistant() ? "Orbit" : "You"; }

    /** One line, short enough for a compact card. */
    public String preview() {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= PREVIEW_CHARS ? flat : flat.substring(0, PREVIEW_CHARS).trim() + "…";
    }

    /**
     * The block appended to the user's text when this turn is sent.
     *
     * <p>A closing tag inside the quoted text is neutralised so the quote cannot end its own
     * wrapper early and have the rest read as something the user wrote.
     */
    public String promptBlock() {
        String safe = text.replace("</" + TAG, "&lt;/" + TAG).replace("<" + TAG, "&lt;" + TAG);
        return "\n\n<" + TAG + " from=\"" + role + "\" untrusted=\"true\">\n" + safe
                + "\n</" + TAG + ">";
    }

    JSONObject toJson() throws Exception {
        return new JSONObject().put("role", role).put("text", text);
    }

    /** The quote a stored message carries, or null for none or anything malformed. */
    static QuotedMessage fromJson(JSONObject o) {
        if (o == null) return null;
        String text = o.optString("text", "").trim();
        return text.isEmpty() ? null : new QuotedMessage(o.optString("role", "user"), text);
    }
}
