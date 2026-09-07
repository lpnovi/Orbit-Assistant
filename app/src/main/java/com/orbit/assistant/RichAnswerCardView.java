package com.orbit.assistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * One sourced picture, drawn as part of an answer rather than as an attachment on one.
 *
 * <p>The difference is the whole design. An attachment card announces itself: a heavy container, a
 * filename, a type icon, a border that says "a file is here". A picture inside an answer should
 * read the way a picture inside an article reads - the image at a comfortable width with rounded
 * corners, one quiet line of attribution under it, and nothing else. So there is no outer card, no
 * icon, no chrome and no label; the image and its source line sit in the flow of the response and
 * are separated from the text around them by spacing alone.
 *
 * <p>Width is clamped rather than left to the parent. On a Galaxy S25 Ultra the bubble width is the
 * right width; on a Tab S9 Plus in landscape it is not, and a photograph stretched to nine hundred
 * points is a photograph nobody wants to look at. {@link #MAX_WIDTH_DP} is the ceiling, applied in
 * {@code onMeasure} so it holds in both orientations and on both devices without either of them
 * being detected.
 *
 * <p>Nothing in here trusts what it is drawing. The caption and the domain are display text; the
 * tap opens Orbit's own image viewer rather than a link; and a picture that is no longer in the
 * cache degrades into a quiet placeholder that keeps the attribution, because the source is still
 * true even when the file is gone.
 */
public final class RichAnswerCardView extends LinearLayout {

    /** The widest a sourced picture is drawn, whatever room the surface offers. */
    public static final int MAX_WIDTH_DP = 460;
    /** The tallest, so a portrait image cannot push the rest of the answer off screen. */
    private static final int MAX_HEIGHT_DP = 300;
    /** What a picture still resolving reserves, so the answer does not jump when it lands. */
    private static final int PLACEHOLDER_HEIGHT_DP = 132;

    private final int maxWidthPx;

    private RichAnswerCardView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        maxWidthPx = UiKit.dp(context, MAX_WIDTH_DP);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int mode = MeasureSpec.getMode(widthSpec);
        int width = MeasureSpec.getSize(widthSpec);
        if (mode != MeasureSpec.UNSPECIFIED && width > maxWidthPx) {
            widthSpec = MeasureSpec.makeMeasureSpec(maxWidthPx, mode);
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    /**
     * The pictures belonging after one block of an answer, as views ready to be added.
     *
     * <p>Returns an empty list for the overwhelming majority of answers, which is the point: an
     * ordinary reply pays one list check for a feature it is not using.
     *
     * @param all      every picture this message carries, for the viewer's own left/right paging.
     * @param here     the ones anchored to this block.
     */
    static List<View> viewsFor(Context context, List<RichAnswerImage> all,
                               List<RichAnswerImage> here, int foreground) {
        List<View> views = new ArrayList<>();
        if (context == null || here == null || here.isEmpty()) return views;
        for (RichAnswerImage image : here) {
            if (image == null || !image.isUsable()) continue;
            views.add(build(context, all, image, foreground));
        }
        return views;
    }

    /** One picture and its attribution line. */
    static View build(Context context, List<RichAnswerImage> all, RichAnswerImage image,
                      int foreground) {
        RichAnswerCardView card = new RichAnswerCardView(context);

        FrameLayout frame = new FrameLayout(context);
        frame.setBackground(UiKit.rounded(UiKit.SURFACE_2, 16, context));
        frame.setClipToOutline(true);

        ImageView view = new ImageView(context);
        view.setAdjustViewBounds(true);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setMaxHeight(UiKit.dp(context, MAX_HEIGHT_DP));
        view.setContentDescription(image.contentDescription());
        frame.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // A modest reserved height rather than a spinner. The answer above is what the user is
        // reading; a large indeterminate indicator in the middle of it would compete with the
        // words for attention while adding nothing they can act on.
        TextView resolving = UiKit.text(context, "Loading image",
                Prefs.chatTextSp(context, 12), UiKit.MUTED, false);
        resolving.setGravity(Gravity.CENTER);
        resolving.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        frame.addView(resolving, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout.LayoutParams frameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(context, PLACEHOLDER_HEIGHT_DP));
        card.addView(frame, frameLp);

        String attribution = image.attributionLine();
        if (!attribution.isEmpty()) {
            TextView caption = UiKit.text(context, attribution,
                    Prefs.chatTextSp(context, 11.5f), UiKit.withAlpha(foreground, 190), false);
            caption.setLineSpacing(0, 1.1f);
            caption.setContentDescription(image.isGenerated()
                    ? "Generated image"
                    : "Image source: " + attribution);
            LinearLayout.LayoutParams captionLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            captionLp.setMargins(UiKit.dp(context, 3), UiKit.dp(context, 6), UiKit.dp(context, 3), 0);
            card.addView(caption, captionLp);
        }

        Bitmap ready = RemoteImageLoader.memoryCached(image.imageUrl);
        if (ready != null) {
            settle(card, frame, view, resolving, ready, all, image);
        } else {
            RemoteImageLoader.loadDetailed(context, image.imageUrl, result -> {
                if (!result.loaded()) {
                    showUnavailable(frame, resolving, image);
                    return;
                }
                settle(card, frame, view, resolving, result.bitmap, all, image);
            });
        }

        UiKit.watchTypography(card);
        return card;
    }

    /**
     * The quiet state a picture that would not load settles into.
     *
     * <p>Two deliberate differences from the Markdown path's failure card. It offers <b>Open
     * source</b> rather than the raw file, because for a structured picture Orbit knows the page
     * the picture belongs to and that page is what a person actually wants - a bare asset on a CDN
     * is not somewhere to send anybody. And it says nothing about why: the category is recorded for
     * Diagnostics, and an answer somebody is reading is not the place for a transport error.
     *
     * <p>The attribution above it is untouched, because it is still true. The answer keeps its
     * shape rather than developing a hole where a picture was going to be.
     */
    private static void showUnavailable(FrameLayout frame, TextView resolving,
                                        RichAnswerImage image) {
        Context context = frame.getContext();
        boolean openable = RichAnswerUrlPolicy.isOpenableWebUrl(image.sourceUrl);
        resolving.setText("Image unavailable");
        if (!openable) return;
        frame.setClickable(true);
        frame.setFocusable(true);
        frame.setContentDescription("Open the page this image came from");
        frame.setOnClickListener(v -> {
            UiKit.haptic(v, HapticFeedbackConstants.CLOCK_TICK);
            // Revalidated at the tap, exactly as the viewer does: a stored address is data, and a
            // check performed when it was saved is not a check performed now.
            if (!RichAnswerUrlPolicy.isOpenableWebUrl(image.sourceUrl)) return;
            try {
                context.startActivity(new android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(image.sourceUrl))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {}
        });
        UiKit.pressScale(frame);
        TextView open = UiKit.text(context, "Open source",
                Prefs.chatTextSp(context, 12), UiKit.accent(context), false);
        open.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        lp.bottomMargin = UiKit.dp(context, 14);
        frame.addView(open, lp);
    }

    /**
     * Puts the picture on screen once it is genuinely there.
     *
     * <p>The reserved height is released to {@code WRAP_CONTENT} at the same moment the bitmap is
     * set, so the card settles to the image's real aspect ratio in one layout pass rather than
     * snapping twice. The fade is short and shallow: a picture appearing inside text somebody is
     * already reading should register as having arrived, not as an animation.
     */
    private static void settle(View card, FrameLayout frame, ImageView view, View resolving,
                               Bitmap bitmap, List<RichAnswerImage> all, RichAnswerImage image) {
        view.setImageBitmap(bitmap);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        resolving.setVisibility(View.GONE);
        ViewGroup.LayoutParams lp = frame.getLayoutParams();
        if (lp != null) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            frame.setLayoutParams(lp);
        }
        view.setAlpha(0f);
        view.animate().alpha(1f).setDuration(180L).start();
        frame.setBackground(UiKit.rounded(UiKit.SURFACE_2, 16, frame.getContext()));
        frame.setClickable(true);
        frame.setFocusable(true);
        frame.setContentDescription(image.contentDescription());
        frame.setOnClickListener(v -> {
            UiKit.haptic(v, HapticFeedbackConstants.CLOCK_TICK);
            openViewer(v, all, image);
        });
        UiKit.pressScale(frame);
        card.requestLayout();
    }

    /**
     * Opens Orbit's existing image viewer on this picture.
     *
     * <p>The existing one, deliberately. Orbit already has a full-screen viewer with pinch zoom,
     * pan, paging and accessibility controls, and a second viewer built for response images would
     * be a worse copy of it that then had to be maintained beside it. What the viewer gained for
     * this release is a source of its own, not a sibling.
     */
    private static void openViewer(View anchor, List<RichAnswerImage> all, RichAnswerImage image) {
        android.app.Activity host = activityOf(anchor);
        if (host == null) return;
        List<RichAnswerImage> set = all == null || all.isEmpty()
                ? java.util.Collections.singletonList(image) : all;
        AttachmentViewerActivity.openRichAnswer(host, set, image.id);
    }

    private static android.app.Activity activityOf(View view) {
        Context context = view == null ? null : view.getContext();
        while (context instanceof android.content.ContextWrapper) {
            if (context instanceof android.app.Activity) return (android.app.Activity) context;
            context = ((android.content.ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}
