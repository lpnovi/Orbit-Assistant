package com.orbit.assistant;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The full-screen look at an image attachment.
 *
 * <p>Orbit could already stage ten photos on one message and show them back in a strip, and a strip
 * is the right shape for staging and removing - but it is 40dp tall, which is enough to tell two
 * photos apart and not nearly enough to see what is in either. The thumbnail was the only view of
 * an attachment that existed, so a screenshot shared to ask a question about a line of small text
 * could not actually be read inside Orbit.
 *
 * <p>This is a viewer and stays one. It has no crop, no rotation, no markup, no filter and no
 * export: Orbit already has Screen Selection for marking up a capture, and adding a second, weaker
 * editor here would be exactly the duplicate system the project keeps out. What it does is show one
 * image at a time, large, with pinch and double-tap zoom, panning while zoomed, and a swipe between
 * the images of one message.
 *
 * <p>Two sources, one screen. An unsent message is live: its images come from the composer's own
 * decoded bitmaps and Remove here removes from the message. A sent turn is a record: its images are
 * loaded from Orbit's private store, and there is no Remove at all, because history is not edited
 * from a picture viewer.
 */
public final class AttachmentViewerActivity extends Activity {

    /** The private token naming what this viewer is showing. Minted inside Orbit only. */
    public static final String EXTRA_TOKEN = "orbit_viewer_token";

    private static final String STATE_TOKEN = "viewer_token";
    private static final String STATE_INDEX = "viewer_index";

    /** How many pages either side of the current one keep a decoded image. */
    private static final int WINDOW = 1;

    private String token = "";
    private AttachmentViewerStore.Session session;
    private AttachmentViewerModel model;
    private AttachmentPagerView pager;
    private final List<Page> pages = new ArrayList<>();
    private View chrome;
    private TextView counter;
    private ImageButton remove;
    private ImageButton previous;
    private ImageButton next;
    /** The attribution line under a sourced picture, or null for an ordinary attachment viewer. */
    private TextView sourceLine;
    private android.widget.Button openSource;
    private android.widget.Button saveToVault;
    private boolean chromeVisible = true;

    /** One page: the image, and the line shown instead when it can no longer be produced. */
    private static final class Page {
        final FrameLayout container;
        final ZoomableImageView image;
        final TextView unavailable;
        boolean loaded;

        Page(FrameLayout container, ZoomableImageView image, TextView unavailable) {
            this.container = container;
            this.image = image;
            this.unavailable = unavailable;
        }
    }

    /**
     * Opens the viewer over an unsent message, on the image that was tapped.
     *
     * <p>Returns quietly without opening when the composer holds nothing viewable, which is what
     * makes it safe to call from a card that might be a PDF.
     */
    public static void openComposer(Activity host, ComposerAttachments attachments, String tappedId) {
        if (host == null) return;
        String token = AttachmentViewerStore.openComposer(attachments, tappedId);
        start(host, token);
    }

    /** Opens a read-only viewer over the stored images of a turn that has already been sent. */
    public static void openHistory(Activity host, List<String> paths, String kind, String label,
                                   int position) {
        if (host == null) return;
        String token = AttachmentViewerStore.openHistory(paths, kind, label, position);
        start(host, token);
    }

    /**
     * Opens this same viewer over the sourced pictures inside an assistant answer.
     *
     * <p>Deliberately a third entry point into one screen rather than a second screen. Everything a
     * user expects of a picture full screen - pinch, double tap, pan, swipe between, the accessible
     * previous and next controls, the chrome that gets out of the way - already exists here and is
     * already tested. What a sourced picture adds is a line saying where it came from and two
     * things to do about it, which is a bottom bar, not an architecture.
     */
    public static void openRichAnswer(Activity host, List<RichAnswerImage> images, String tappedId) {
        if (host == null) return;
        start(host, AttachmentViewerStore.openRichAnswer(images, tappedId));
    }

    private static void start(Activity host, String token) {
        if (token == null || token.isEmpty()) return;
        AttachmentViewerStore.Session session = AttachmentViewerStore.peek(token);
        if (session == null) return;
        DiagnosticStore.recordAttachmentViewer(host, session.source, session.model.size(),
                session.model.index());
        host.startActivity(new Intent(host, AttachmentViewerActivity.class)
                .putExtra(EXTRA_TOKEN, token));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        UiKit.syncTheme(this);
        token = state != null ? state.getString(STATE_TOKEN, "") : "";
        if (token == null || token.isEmpty()) {
            token = getIntent() == null ? "" : getIntent().getStringExtra(EXTRA_TOKEN);
        }
        session = AttachmentViewerStore.peek(token);
        if (session == null || session.model.isEmpty()) {
            // The session is gone - a process death, or a viewer relaunched from a stale task. The
            // honest response is to close rather than to draw an empty screen.
            finish();
            return;
        }
        model = session.model;
        if (state != null) model.moveTo(state.getInt(STATE_INDEX, model.index()));

        // Black rather than Orbit's near-black surface, and the system bars given away entirely:
        // this is one image on nothing, and on an AMOLED panel true black is the frame
        // disappearing rather than a dark grey rectangle around the photo. Orbit's usual
        // applyActivityInsets is deliberately not used here, because it paints the bars in the
        // app's surface colour, which is exactly the frame this screen exists without.
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (android.os.Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        pager = new AttachmentPagerView(this);
        root.addView(pager, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildPages();
        chrome = buildChrome();
        root.addView(chrome, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        applyChromeInsets();

        pager.setOnPageChanged(index -> {
            model.moveTo(index);
            updateWindow();
            updateChrome();
        });
        pager.setCurrentPage(model.index(), false);
        updateWindow();
        updateChrome();
    }

    private void buildPages() {
        pages.clear();
        for (int i = 0; i < model.size(); i++) pages.add(addPage(i));
    }

    private Page addPage(int position) {
        FrameLayout container = new FrameLayout(this);
        ZoomableImageView image = new ZoomableImageView(this);
        image.setListener(new ZoomableImageView.Listener() {
            @Override public void onTapped() { toggleChrome(); }
            @Override public void onTransformChanged() { /* the pager reads the transform live */ }
        });
        container.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView unavailable = UiKit.text(this, "This image is no longer available", 13,
                UiKit.MUTED, false);
        unavailable.setGravity(Gravity.CENTER);
        unavailable.setVisibility(View.GONE);
        FrameLayout.LayoutParams unavailableLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        unavailableLp.gravity = Gravity.CENTER;
        unavailableLp.setMargins(UiKit.dp(this, 32), 0, UiKit.dp(this, 32), 0);
        container.addView(unavailable, unavailableLp);

        container.setContentDescription(model.descriptionAt(position));
        Page page = new Page(container, image, unavailable);
        pager.addPage(container, new AttachmentPagerView.Page() {
            @Override public boolean canPanHorizontally(int direction) {
                return image.canPanHorizontally(direction);
            }

            @Override public void resetTransform() { image.resetTransform(); }
        });
        return page;
    }

    /**
     * Keeps exactly the pages around the current one decoded, and lets go of the rest.
     *
     * <p>This is the whole memory policy. Orbit stores history images at up to 1280px, so decoding
     * a ten photo turn at once is tens of megabytes of pictures nobody is looking at; decoding only
     * on arrival makes a swipe show an empty frame first. One page either side is the smallest
     * window that is never visibly late, and it holds three images however long the message was.
     *
     * <p>A composer image is not decoded here at all. It is already in memory because the composer
     * is holding it, and the viewer borrows the same bitmap rather than making a second copy of a
     * photo the user is about to send.
     */
    private void updateWindow() {
        int index = model.index();
        for (int i = 0; i < pages.size(); i++) {
            Page page = pages.get(i);
            boolean wanted = Math.abs(i - index) <= WINDOW;
            if (wanted && !page.loaded) {
                Bitmap bitmap = session.imageAt(i);
                page.image.setBitmap(bitmap);
                page.unavailable.setVisibility(bitmap == null ? View.VISIBLE : View.GONE);
                page.loaded = true;
                if (bitmap == null) DiagnosticStore.recordAttachmentViewerMissing(this);
            } else if (!wanted && page.loaded) {
                page.image.setBitmap(null);
                page.unavailable.setVisibility(View.GONE);
                session.release(i);
                page.loaded = false;
            }
        }
    }

    private View buildChrome() {
        FrameLayout host = new FrameLayout(this);
        host.setPadding(UiKit.dp(this, 6), UiKit.dp(this, 6), UiKit.dp(this, 6), UiKit.dp(this, 6));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        ImageButton close = iconButton(com.orbit.assistant.R.drawable.ic_back, "Close image viewer");
        close.setOnClickListener(v -> {
            UiKit.haptic(v, HapticFeedbackConstants.CLOCK_TICK);
            finish();
        });
        top.addView(close, new LinearLayout.LayoutParams(UiKit.dp(this, 44), UiKit.dp(this, 44)));

        counter = UiKit.text(this, "", 13, UiKit.TEXT, true);
        counter.setGravity(Gravity.CENTER);
        // The counter states a position the page itself already announces, so a screen reader is
        // not made to read the same fact twice as it moves through the controls.
        counter.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        top.addView(counter, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        remove = iconButton(com.orbit.assistant.R.drawable.ic_delete, "Remove image");
        remove.setColorFilter(UiKit.DANGER);
        remove.setOnClickListener(v -> removeCurrent());
        // Only an unsent message has a Remove. A sent turn is a record of what happened, and this
        // screen is not where records are edited.
        remove.setVisibility(session.removable() ? View.VISIBLE : View.GONE);
        top.addView(remove, new LinearLayout.LayoutParams(UiKit.dp(this, 44), UiKit.dp(this, 44)));

        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        topLp.gravity = Gravity.TOP;
        host.addView(top, topLp);

        // Explicit previous/next controls, not decoration. A screen reader cannot reliably perform
        // a horizontal swipe on a custom pager, so without these the only way to reach the second
        // photo would be a gesture some users cannot make.
        previous = iconButton(com.orbit.assistant.R.drawable.ic_back, "Previous image");
        previous.setOnClickListener(v -> step(-1));
        FrameLayout.LayoutParams previousLp = new FrameLayout.LayoutParams(
                UiKit.dp(this, 44), UiKit.dp(this, 44));
        previousLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        host.addView(previous, previousLp);

        next = iconButton(com.orbit.assistant.R.drawable.ic_back, "Next image");
        next.setRotation(180f);
        next.setOnClickListener(v -> step(1));
        FrameLayout.LayoutParams nextLp = new FrameLayout.LayoutParams(
                UiKit.dp(this, 44), UiKit.dp(this, 44));
        nextLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        host.addView(next, nextLp);

        View sourceBar = buildSourceBar();
        if (sourceBar != null) {
            FrameLayout.LayoutParams sourceLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sourceLp.gravity = Gravity.BOTTOM;
            host.addView(sourceBar, sourceLp);
        }

        // The chrome floats above the image and must never eat a pinch or a swipe aimed at it.
        host.setClickable(false);
        host.setFocusable(false);
        return host;
    }

    /**
     * The bottom bar a sourced picture gets: where it came from, and what can be done about it.
     *
     * <p>Built only for a rich-answer session, so an ordinary attachment viewer is byte-for-byte
     * the screen it was before. It is deliberately quiet - one line of attribution and two text
     * buttons - because the picture is what the user opened this screen to look at, and a
     * full-width action bar under it would be competing with the thing it describes.
     */
    private View buildSourceBar() {
        if (session == null || !session.isRichAnswer()) return null;
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackground(UiKit.rounded(UiKit.withAlpha(Color.BLACK, 175), 18, this));
        bar.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 12),
                UiKit.dp(this, 14), UiKit.dp(this, 12));

        sourceLine = UiKit.text(this, "", 13, UiKit.TEXT, false);
        sourceLine.setLineSpacing(0, 1.12f);
        sourceLine.setMaxLines(3);
        bar.addView(sourceLine);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = UiKit.dp(this, 8);
        bar.addView(actions, actionsLp);

        openSource = textAction("Open source", "Open the page this image came from");
        openSource.setOnClickListener(v -> openCurrentSource());
        actions.addView(openSource, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 40)));

        saveToVault = textAction("Save to Vault", "Save this image to Orbit Vault");
        saveToVault.setOnClickListener(v -> saveCurrentToVault());
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 40));
        saveLp.leftMargin = UiKit.dp(this, 6);
        actions.addView(saveToVault, saveLp);
        return bar;
    }

    /** A flat accent text control, in the same shape the viewer's icon buttons already use. */
    private android.widget.Button textAction(String label, String description) {
        android.widget.Button button = new android.widget.Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTextColor(UiKit.accent(this));
        button.setStateListAnimator(null);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(UiKit.dp(this, 12), 0, UiKit.dp(this, 12), 0);
        button.setBackground(UiKit.ripple(UiKit.withAlpha(Color.BLACK, 90),
                UiKit.withAlpha(UiKit.accent(this), 80), 14, this));
        button.setContentDescription(description);
        UiKit.pressScale(button);
        return button;
    }

    /**
     * Opens the page a sourced picture came from, in the browser.
     *
     * <p>The page rather than the raw image file, because the page is what the answer actually
     * used and what a person can read. The address is validated again immediately before the
     * launch: it has been sitting in a store since the answer arrived, and a check performed at
     * save time is not a check performed now.
     */
    private void openCurrentSource() {
        RichAnswerImage image = session == null ? null : session.richImageAt(model.index());
        if (image == null || !RichAnswerUrlPolicy.isOpenableWebUrl(image.sourceUrl)) return;
        UiKit.haptic(openSource, HapticFeedbackConstants.CLOCK_TICK);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(image.sourceUrl))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
            android.widget.Toast.makeText(this, "Could not open this source",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Copies the picture on screen into Orbit Vault.
     *
     * <p>Through the Vault's own media path, so what is kept is a file the Vault owns rather than
     * a pointer into a cache that is trimmed by size and will be gone next week. The saved item is
     * an ordinary image with an ordinary note field; what marks it out is its source, which is
     * Orbit's own canonical {@link OrbitVaultSource#RICH_ANSWER}, and the page address kept beside
     * it so the item can still say where it came from.
     */
    private void saveCurrentToVault() {
        RichAnswerImage image = session == null ? null : session.richImageAt(model.index());
        if (image == null) return;
        Bitmap bitmap = session.imageAt(model.index());
        if (bitmap == null) {
            android.widget.Toast.makeText(this, "This image is no longer available",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        UiKit.haptic(saveToVault, HapticFeedbackConstants.CLOCK_TICK);
        OrbitVaultItem saved = OrbitVaultStore.saveRichAnswerImage(this, bitmap, image);
        android.widget.Toast.makeText(this,
                saved == null ? "Orbit could not save this image" : "Saved to Vault",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    /**
     * Keeps the controls clear of the status bar, the navigation bar and the cutout.
     *
     * <p>Only the chrome is inset. The image itself deliberately fills the whole window, including
     * behind the bars, because insetting a photo to make room for a transparent status bar would
     * shrink the one thing this screen exists to show.
     */
    private void applyChromeInsets() {
        if (chrome == null) return;
        final int left = chrome.getPaddingLeft();
        final int top = chrome.getPaddingTop();
        final int right = chrome.getPaddingRight();
        final int bottom = chrome.getPaddingBottom();
        chrome.setOnApplyWindowInsetsListener((view, insets) -> {
            int barsLeft, barsTop, barsRight, barsBottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars()
                                | android.view.WindowInsets.Type.displayCutout());
                barsLeft = bars.left;
                barsTop = bars.top;
                barsRight = bars.right;
                barsBottom = bars.bottom;
            } else {
                barsLeft = insets.getSystemWindowInsetLeft();
                barsTop = insets.getSystemWindowInsetTop();
                barsRight = insets.getSystemWindowInsetRight();
                barsBottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left + barsLeft, top + barsTop, right + barsRight, bottom + barsBottom);
            return insets;
        });
        chrome.requestApplyInsets();
    }

    private ImageButton iconButton(int icon, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setBackground(UiKit.ripple(UiKit.withAlpha(Color.BLACK, 140),
                UiKit.withAlpha(UiKit.accent(this), 70), 99, this));
        button.setColorFilter(UiKit.TEXT);
        button.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 10),
                UiKit.dp(this, 10), UiKit.dp(this, 10));
        button.setContentDescription(description);
        return button;
    }

    /** Moves one image, from a control rather than a gesture. */
    private void step(int delta) {
        int target = model.index() + delta;
        if (target < 0 || target >= model.size()) return;
        pager.setCurrentPage(target, true);
        model.moveTo(target);
        updateWindow();
        updateChrome();
    }

    private void toggleChrome() {
        chromeVisible = !chromeVisible;
        if (chrome == null) return;
        if (!UiKit.animationsEnabled()) {
            chrome.setAlpha(chromeVisible ? 1f : 0f);
            chrome.setVisibility(chromeVisible ? View.VISIBLE : View.INVISIBLE);
            return;
        }
        chrome.animate().cancel();
        if (chromeVisible) chrome.setVisibility(View.VISIBLE);
        chrome.animate().alpha(chromeVisible ? 1f : 0f)
                .setDuration(UiKit.MOTION_FAST)
                .setInterpolator(UiKit.motionEasing())
                .withEndAction(() -> chrome.setVisibility(
                        chromeVisible ? View.VISIBLE : View.INVISIBLE))
                .start();
    }

    private void updateChrome() {
        if (counter == null) return;
        String text = model.counterText();
        counter.setText(text);
        counter.setVisibility(text.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        if (remove != null) {
            remove.setVisibility(session.removable() ? View.VISIBLE : View.GONE);
            remove.setContentDescription(model.removeDescription());
        }
        boolean many = model.size() > 1;
        if (previous != null) {
            previous.setVisibility(many && model.hasPrevious() ? View.VISIBLE : View.INVISIBLE);
        }
        if (next != null) {
            next.setVisibility(many && model.hasNext() ? View.VISIBLE : View.INVISIBLE);
        }
        for (int i = 0; i < pages.size(); i++) {
            pages.get(i).container.setContentDescription(model.descriptionAt(i));
        }
        updateSourceBar();
    }

    /** Keeps the attribution and its actions pointed at whichever picture is on screen. */
    private void updateSourceBar() {
        if (sourceLine == null) return;
        RichAnswerImage image = session == null ? null : session.richImageAt(model.index());
        String line = image == null ? "" : image.attributionLine();
        sourceLine.setText(line);
        sourceLine.setVisibility(line.isEmpty() ? View.GONE : View.VISIBLE);
        if (openSource != null) {
            // Offered only when there is genuinely a page to open. A generated picture has no
            // source, and a control that opened nothing would be Orbit implying one exists.
            boolean openable = image != null && RichAnswerUrlPolicy.isOpenableWebUrl(image.sourceUrl);
            openSource.setVisibility(openable ? View.VISIBLE : View.GONE);
        }
        if (saveToVault != null) {
            saveToVault.setVisibility(image != null && OrbitVaultStore.enabled(this)
                    ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Removes the image on screen from the message being written.
     *
     * <p>Exactly this attachment, out of the composer's one canonical collection. The composer text
     * is untouched, any standing screen policy is untouched, and the other attachments keep their
     * order. Removing the last one leaves nothing to look at, so the viewer closes back to the
     * composer rather than sitting on an empty screen.
     */
    private void removeCurrent() {
        if (!session.removable()) return;
        int position = model.index();
        UiKit.haptic(remove, HapticFeedbackConstants.CLOCK_TICK);
        if (!session.removeCurrent()) return;
        pages.remove(position);
        pager.removePage(position);
        if (model.isEmpty()) { finish(); return; }
        pager.setCurrentPage(model.index(), false);
        updateWindow();
        updateChrome();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        // Enough to reopen on the same image of the same message. Zoom is deliberately not saved:
        // a transform restored against a rotated viewport is not the same transform, and starting
        // a rotated image fitted is both correct and what people expect.
        out.putString(STATE_TOKEN, token);
        out.putInt(STATE_INDEX, model == null ? 0 : model.index());
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        // A configuration change is not the end of the viewer, so the session survives one; only a
        // real finish releases what it was holding.
        if (isFinishing()) AttachmentViewerStore.close(token);
    }

    /** For tests: which image the viewer has settled on. */
    int currentIndex() { return model == null ? -1 : model.index(); }

    /** For tests: how many images this viewer is showing. */
    int imageCount() { return model == null ? 0 : model.size(); }

    /** For tests: whether this viewer offers a Remove at all. */
    boolean offersRemove() {
        return remove != null && remove.getVisibility() == View.VISIBLE;
    }

    /** For tests: what the counter currently reads. */
    String counterText() { return counter == null ? "" : counter.getText().toString(); }

    /** For tests: the page currently on screen. */
    ZoomableImageView currentImageView() {
        int index = model == null ? -1 : model.index();
        return index < 0 || index >= pages.size() ? null : pages.get(index).image;
    }

    /** For tests: whether a page is showing the unavailable state. */
    boolean showsUnavailable(int position) {
        return position >= 0 && position < pages.size()
                && pages.get(position).unavailable.getVisibility() == View.VISIBLE;
    }

    /** For tests: press the viewer's Remove control. */
    void removeCurrentForTest() { removeCurrent(); }

    /** For tests: use the accessible next/previous controls. */
    void stepForTest(int delta) { step(delta); }

    /** For tests: the pager, so a settled page can be asserted. */
    AttachmentPagerView pagerForTest() { return pager; }
}
