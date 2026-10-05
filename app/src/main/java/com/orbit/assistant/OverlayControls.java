package com.orbit.assistant;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Which optional overlay controls sit in their own place, and which wait in the More menu.
 *
 * <p>The one place this is decided, for the overlay and for Settings' preview alike. Hiding a quick
 * control only moves it: everything it does stays one tap away in More, or, for the screen buttons,
 * in Attach's Screen entry (which More carries in turn when Attach is hidden too). The composer,
 * Send/Stop and Close are not options at all, so no combination can leave an overlay that cannot
 * type, send, stop or close.
 */
final class OverlayControls {
    /** More menu entries, in the order the menu lists them. */
    static final String MODEL = "AI model";
    static final String HISTORY = "Recent chats";
    static final String NEW_CHAT = "New chat";
    static final String ATTACH = "Attach";
    static final String VOICE = "Voice input";

    final boolean model;
    final boolean history;
    final boolean newChat;
    final boolean screen;
    final boolean attach;
    final boolean voice;
    /** What More holds; empty means the overlay shows no More button at all. */
    final List<String> overflow;

    private OverlayControls(boolean model, boolean history, boolean newChat, boolean screen,
                            boolean attach, boolean voice) {
        this.model = model;
        this.history = history;
        this.newChat = newChat;
        this.screen = screen;
        this.attach = attach;
        this.voice = voice;
        List<String> more = new ArrayList<>();
        if (!model) more.add(MODEL);
        if (!history) more.add(HISTORY);
        if (!newChat) more.add(NEW_CHAT);
        if (!attach) more.add(ATTACH);
        if (!voice) more.add(VOICE);
        overflow = Collections.unmodifiableList(more);
    }

    /**
     * The arrangement for {@code style} given what the user wants visible. A compact style keeps
     * Recent chats and New chat in More whatever the switches say: that is its density choice.
     */
    static OverlayControls resolve(OverlayStyle style, boolean model, boolean history,
                                   boolean newChat, boolean screen, boolean attach, boolean voice) {
        boolean ownSlots = !style.compact;
        return new OverlayControls(model, ownSlots && history, ownSlots && newChat, screen, attach,
                voice);
    }

    static OverlayControls of(Context c, OverlayStyle style) {
        android.content.SharedPreferences p = Prefs.get(c);
        return resolve(style,
                p.getBoolean(Prefs.OVERLAY_SHOW_MODEL, true),
                p.getBoolean(Prefs.OVERLAY_SHOW_HISTORY, true),
                p.getBoolean(Prefs.OVERLAY_SHOW_NEW_CHAT, true),
                p.getBoolean(Prefs.OVERLAY_SHOW_SCREEN, true),
                p.getBoolean(Prefs.OVERLAY_SHOW_ATTACH, true),
                p.getBoolean(Prefs.OVERLAY_SHOW_VOICE, true));
    }

    boolean hasOverflow() {
        return !overflow.isEmpty();
    }
}
