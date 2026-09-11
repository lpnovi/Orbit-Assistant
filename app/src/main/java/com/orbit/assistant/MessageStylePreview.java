package com.orbit.assistant;

import android.content.Context;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Two bubbles, sitting directly above the controls that shape them.
 *
 * <p>Beta 2 put bubble roundness and outline under a full page of controls with the only sample of
 * their effect at the very top of the screen. Judging a corner radius then meant dragging, losing
 * sight of the slider, scrolling up, scrolling back, and repeating - which is a real answer to why
 * the premium section felt stiff rather than a matter of taste. A control that changes how
 * something looks belongs next to that something.
 *
 * <p>Deliberately small and deliberately literal. There is no Markdown, no code pill, no link, no
 * card and no Deck tile, because this sample answers one question and the large preview at the top
 * of Theme Studio already answers the general one. It shows a message from the person and a reply
 * from Orbit, at the shape and outline the draft currently specifies, and nothing else.
 *
 * <p>The bubbles are drawn by {@link UiKit#bubbleSurface}, which is the same call a real
 * conversation makes. There is no radius or outline arithmetic in this file, and there must never
 * be: a sample that computed its own corners would eventually disagree with the messages it claims
 * to be previewing, which is worse than having no sample at all.
 *
 * <p>Built once and updated in place, like everything else in Theme Studio after Beta 3. A sample
 * that rebuilt itself per frame would reintroduce exactly the flicker this release removes.
 */
public final class MessageStylePreview extends LinearLayout {

    private final TextView userBubble;
    private final TextView assistantBubble;

    public MessageStylePreview(Context c) {
        super(c);
        setOrientation(VERTICAL);
        setPadding(UiKit.dp(c, 12), UiKit.dp(c, 11), UiKit.dp(c, 12), UiKit.dp(c, 12));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);

        userBubble = UiKit.text(c, "Looks right?", 12.5f, UiKit.TEXT, false);
        userBubble.setPadding(UiKit.dp(c, 12), UiKit.dp(c, 8), UiKit.dp(c, 12), UiKit.dp(c, 8));
        LinearLayout.LayoutParams userLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        userLp.gravity = Gravity.END;
        addView(userBubble, userLp);

        assistantBubble = UiKit.text(c, "That's your message shape.", 12.5f, UiKit.TEXT, false);
        assistantBubble.setPadding(UiKit.dp(c, 12), UiKit.dp(c, 8),
                UiKit.dp(c, 12), UiKit.dp(c, 8));
        LinearLayout.LayoutParams replyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        replyLp.gravity = Gravity.START;
        replyLp.topMargin = UiKit.dp(c, 8);
        addView(assistantBubble, replyLp);
    }

    /**
     * Redraws the sample for a draft.
     *
     * <p>{@code style} is passed in already resolved against entitlement by the caller, for the
     * same reason the large preview takes it that way: this sample must show what a conversation
     * would actually draw, not what the stored theme happens to contain.
     */
    public void render(OrbitThemeTokens tokens, OrbitProStyle style) {
        if (tokens == null) return;
        Context c = getContext();
        OrbitProStyle resolved = style == null ? OrbitProStyle.DEFAULT : style;

        setBackground(UiKit.rounded(tokens.background, 14, c));

        userBubble.setTextColor(tokens.userBubbleInk);
        userBubble.setBackground(
                UiKit.bubbleSurface(c, tokens.userBubble, tokens.accent, resolved));
        assistantBubble.setTextColor(tokens.assistantBubbleInk);
        assistantBubble.setBackground(
                UiKit.bubbleSurface(c, tokens.assistantBubble, tokens.accent, resolved));

        String outline = resolved.bubbleOutline == OrbitProStyle.OUTLINE_OFF
                ? "no outline"
                : resolved.bubbleOutlineLabel().toLowerCase(java.util.Locale.US) + " outline";
        setContentDescription("Message style sample. "
                + resolved.bubbleRadiusLabel() + " corners, " + outline + ".");
    }
}
