package com.orbit.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native, local PDF reading: bounded rendering, page navigation, search, zoom and page context. */
public final class DocumentViewerActivity extends Activity {
    public static final String EXTRA_TOKEN = "orbit_document_token";
    private static final String STATE_TOKEN = "document_token";
    private static final String STATE_PAGE = "document_page";
    /** A corrupt PDF claiming millions of pages must not create millions of Views. */
    static final int MAX_PAGES = 3000;

    private static final class Page {
        final FrameLayout container;
        final ZoomableImageView image;
        final TextView state;
        Bitmap bitmap;
        boolean requested;

        Page(FrameLayout container, ZoomableImageView image, TextView state) {
            this.container = container;
            this.image = image;
            this.state = state;
        }

        void release() {
            image.setBitmap(null);
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            bitmap = null;
            requested = false;
        }
    }

    private String token = "";
    private DocumentViewerStore.Session session;
    private DocumentViewerModel model;
    private PdfRenderEngine renderer;
    private final DocumentRenderWindow renderWindow = new DocumentRenderWindow();
    private final ExecutorService textExecutor = Executors.newSingleThreadExecutor();
    private final List<Page> pages = new ArrayList<>();
    private DocumentTextIndex textIndex;
    private List<DocumentTextIndex.Match> matches = Collections.emptyList();
    private int selectedMatch = -1;
    private int restoredPage;
    private boolean destroyed;

    private LinearLayout root;
    private AttachmentPagerView pager;
    private TextView title;
    private TextView opening;
    private ImageButton previous;
    private ImageButton next;
    private ImageButton searchButton;
    private Button askButton;
    private Button pageButton;
    private LinearLayout searchPanel;
    private EditText searchInput;
    private TextView searchResult;
    private ImageButton searchPrevious;
    private ImageButton searchNext;

    public static void open(Activity host, DocumentReference document, int page) {
        if (host == null || document == null || !document.isUsable()) return;
        String token = DocumentViewerStore.open(document, page);
        if (token.isEmpty()) return;
        host.startActivity(new Intent(host, DocumentViewerActivity.class)
                .putExtra(EXTRA_TOKEN, token));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        UiKit.syncTheme(this);
        token = state == null ? "" : state.getString(STATE_TOKEN, "");
        if (token == null || token.isEmpty()) {
            token = getIntent() == null ? "" : getIntent().getStringExtra(EXTRA_TOKEN);
        }
        session = DocumentViewerStore.peek(token);
        if (session == null || session.document == null) { finish(); return; }
        restoredPage = state == null ? session.initialPage : state.getInt(STATE_PAGE, 0);

        Window window = getWindow();
        window.setStatusBarColor(UiKit.BG);
        window.setNavigationBarColor(UiKit.BG);
        setContentView(buildContent());
        UiKit.applyActivityInsets(this, root, true);

        renderer = new PdfRenderEngine(session.document.path, new PdfRenderEngine.Callback() {
            @Override public void onOpened(int pageCount) { documentOpened(pageCount); }
            @Override public void onRendered(long generation, int page, Bitmap bitmap) {
                pageRendered(generation, page, bitmap);
            }
            @Override public void onOpenError(String message) { showOpenError(message); }
            @Override public void onRenderError(long generation, int page, String message) {
                showPageError(generation, page, message);
            }
        });
        renderer.open();
        indexText();
    }

    private View buildContent() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.BG);
        root.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 8),
                UiKit.dp(this, 12), UiKit.dp(this, 10));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = iconButton(R.drawable.ic_back, "Close document viewer");
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 48)));

        title = UiKit.text(this, session.document.label, 18, UiKit.TEXT, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.setMarginStart(UiKit.dp(this, 8));
        header.addView(title, titleLp);

        searchButton = iconButton(R.drawable.ic_search, "Search document");
        searchButton.setOnClickListener(v -> toggleSearch());
        header.addView(searchButton,
                new LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 48)));

        askButton = actionButton("Ask Orbit", "Ask Orbit about this page");
        askButton.setEnabled(false);
        askButton.setAlpha(0.5f);
        askButton.setOnClickListener(v -> askAboutPage());
        LinearLayout.LayoutParams askLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 48));
        askLp.setMarginStart(UiKit.dp(this, 4));
        header.addView(askButton, askLp);
        root.addView(header);

        searchPanel = buildSearchPanel();
        searchPanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchLp.setMargins(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 8));
        root.addView(searchPanel, searchLp);

        opening = UiKit.text(this, "Opening document…", 13, UiKit.MUTED, false);
        opening.setGravity(Gravity.CENTER);
        root.addView(opening, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        pager = new AttachmentPagerView(this);
        pager.setBackgroundColor(Prefs.amoledMode(this) ? android.graphics.Color.BLACK
                : UiKit.blend(UiKit.BG, android.graphics.Color.BLACK, 0.32f));
        pager.setVisibility(View.GONE);
        root.addView(pager, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        previous = iconButton(R.drawable.ic_back, "Previous page");
        previous.setOnClickListener(v -> step(-1));
        controls.addView(previous,
                new LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 48)));
        pageButton = actionButton("Page", "Choose page");
        pageButton.setOnClickListener(v -> showPagePicker());
        LinearLayout.LayoutParams pageLp = new LinearLayout.LayoutParams(
                0, UiKit.dp(this, 44), 1f);
        pageLp.setMargins(UiKit.dp(this, 10), 0, UiKit.dp(this, 10), 0);
        controls.addView(pageButton, pageLp);
        next = iconButton(R.drawable.ic_back, "Next page");
        next.setRotation(180f);
        next.setOnClickListener(v -> step(1));
        controls.addView(next,
                new LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 48)));
        root.addView(controls);
        UiKit.watchTypography(root);
        return root;
    }

    private LinearLayout buildSearchPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 5),
                UiKit.dp(this, 5), UiKit.dp(this, 5));
        panel.setBackground(UiKit.outlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), 100), 16, this));
        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setHint("Search document");
        searchInput.setTextColor(UiKit.TEXT);
        searchInput.setHintTextColor(UiKit.MUTED);
        searchInput.setTextSize(14);
        searchInput.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        searchInput.setEnabled(false);
        panel.addView(searchInput, new LinearLayout.LayoutParams(0, UiKit.dp(this, 44), 1f));
        searchResult = UiKit.text(this, "Indexing…", 11.5f, UiKit.MUTED, true);
        searchResult.setGravity(Gravity.CENTER);
        panel.addView(searchResult, new LinearLayout.LayoutParams(
                UiKit.dp(this, 82), UiKit.dp(this, 44)));
        searchPrevious = iconButton(R.drawable.ic_back, "Previous search result");
        searchPrevious.setOnClickListener(v -> moveSearch(-1));
        panel.addView(searchPrevious,
                new LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 48)));
        searchNext = iconButton(R.drawable.ic_back, "Next search result");
        searchNext.setRotation(180f);
        searchNext.setOnClickListener(v -> moveSearch(1));
        panel.addView(searchNext,
                new LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 48)));
        ImageButton close = iconButton(R.drawable.ic_close, "Close document search");
        close.setOnClickListener(v -> toggleSearch());
        panel.addView(close,
                new LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 48)));
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                performSearch(s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        setSearchNavigationEnabled(false);
        return panel;
    }

    private void documentOpened(int count) {
        if (destroyed) return;
        if (count <= 0 || count > MAX_PAGES) {
            showOpenError(count <= 0 ? "This PDF has no readable pages."
                    : "This PDF has too many pages to open safely.");
            return;
        }
        model = new DocumentViewerModel(count, restoredPage);
        pages.clear();
        for (int i = 0; i < count; i++) addPage(i);
        opening.setVisibility(View.GONE);
        pager.setVisibility(View.VISIBLE);
        pager.setOnPageChanged(index -> {
            if (model == null) return;
            model.moveTo(index);
            updateChrome();
            updateRenderWindow();
        });
        pager.setCurrentPage(model.page(), false);
        updateChrome();
        pager.post(this::updateRenderWindow);
    }

    private void addPage(int pageNumber) {
        FrameLayout container = new FrameLayout(this);
        int screenDp = getResources().getConfiguration().screenWidthDp;
        int maxWidth = getResources().getConfiguration().smallestScreenWidthDp >= 600
                ? UiKit.dp(this, Math.min(760, Math.max(320, screenDp - 32)))
                : ViewGroup.LayoutParams.MATCH_PARENT;
        ZoomableImageView image = new ZoomableImageView(this);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        image.setListener(new ZoomableImageView.Listener() {
            @Override public void onTapped() {}
            @Override public void onTransformChanged() {}
        });
        FrameLayout.LayoutParams imageLp = new FrameLayout.LayoutParams(
                maxWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
        container.addView(image, imageLp);
        TextView state = UiKit.text(this, "Rendering page…", 13, UiKit.MUTED, false);
        state.setGravity(Gravity.CENTER);
        container.addView(state, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        container.setFocusable(true);
        container.setContentDescription("Page " + (pageNumber + 1) + " of "
                + model.pageCount());
        Page page = new Page(container, image, state);
        pages.add(page);
        pager.addPage(container, new AttachmentPagerView.Page() {
            @Override public boolean canPanHorizontally(int direction) {
                return image.canPanHorizontally(direction);
            }
            @Override public void resetTransform() { image.resetTransform(); }
        });
    }

    private void updateRenderWindow() {
        if (destroyed || model == null || renderer == null || pager.getWidth() <= 0) return;
        List<Integer> wantedList = DocumentRenderWindow.around(model.page(), model.pageCount());
        Set<Integer> wanted = new HashSet<>(wantedList);
        long generation = renderWindow.nextGeneration();
        for (int i = 0; i < pages.size(); i++) {
            Page page = pages.get(i);
            if (!wanted.contains(i)) {
                if (page.bitmap != null || page.requested) page.release();
                page.state.setVisibility(View.VISIBLE);
                page.state.setText("Rendering page…");
                continue;
            }
            if (page.bitmap != null) continue;
            page.requested = true;
            page.state.setVisibility(View.VISIBLE);
            page.state.setText("Rendering page…");
            renderer.render(generation, i, Math.max(1200, pager.getWidth() * 2));
        }
    }

    private void pageRendered(long generation, int pageNumber, Bitmap bitmap) {
        if (destroyed || bitmap == null || !renderWindow.accepts(generation)
                || model == null || !DocumentRenderWindow.around(model.page(), model.pageCount())
                .contains(pageNumber) || pageNumber < 0 || pageNumber >= pages.size()) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            return;
        }
        Page page = pages.get(pageNumber);
        page.release();
        page.bitmap = bitmap;
        page.image.setBitmap(bitmap);
        page.state.setVisibility(View.GONE);
        page.container.setContentDescription("Page " + (pageNumber + 1) + " of "
                + model.pageCount());
    }

    private void showPageError(long generation, int pageNumber, String message) {
        if (!renderWindow.accepts(generation) || pageNumber < 0 || pageNumber >= pages.size()) return;
        Page page = pages.get(pageNumber);
        page.requested = false;
        page.state.setText(message);
        page.state.setVisibility(View.VISIBLE);
    }

    private void showOpenError(String message) {
        opening.setText(message == null ? "Orbit could not open this PDF." : message);
        opening.setTextColor(UiKit.MUTED);
        pager.setVisibility(View.GONE);
        previous.setEnabled(false);
        next.setEnabled(false);
        pageButton.setEnabled(false);
        askButton.setEnabled(false);
    }

    private void step(int delta) {
        if (model == null) return;
        goToPage(model.page() + delta, true);
    }

    private void goToPage(int page, boolean animate) {
        if (model == null || pager == null) return;
        int target = Math.max(0, Math.min(page, model.pageCount() - 1));
        if (target == model.page()) return;
        pager.setCurrentPage(target, animate);
    }

    private void updateChrome() {
        if (model == null) return;
        pageButton.setText(model.counterText());
        pageButton.setContentDescription(model.counterText() + ", choose page");
        previous.setEnabled(model.hasPrevious());
        previous.setAlpha(model.hasPrevious() ? 1f : 0.35f);
        next.setEnabled(model.hasNext());
        next.setAlpha(model.hasNext() ? 1f : 0.35f);
        updateAskEnabled();
    }

    private void showPagePicker() {
        if (model == null || model.pageCount() <= 0) return;
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dp(this, 20);
        body.setPadding(pad, UiKit.dp(this, 8), pad, 0);
        TextView chosen = UiKit.text(this, model.counterText(), 15, UiKit.TEXT, true);
        chosen.setGravity(Gravity.CENTER);
        body.addView(chosen);
        SeekBar seek = new SeekBar(this);
        seek.setMax(Math.max(0, model.pageCount() - 1));
        seek.setProgress(model.page());
        seek.setContentDescription("Choose document page");
        seek.setProgressTintList(ColorStateList.valueOf(UiKit.accent(this)));
        seek.setThumbTintList(ColorStateList.valueOf(UiKit.accent(this)));
        body.addView(seek, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 54)));
        final int[] target = {model.page()};
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                target[0] = progress;
                chosen.setText("Page " + (progress + 1) + " of " + model.pageCount());
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Choose page")
                .setView(body)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Go", (d, which) -> goToPage(target[0], true))
                .create();
        UiKit.styleOrbitDialog(dialog, this, false);
        dialog.show();
    }

    private void toggleSearch() {
        boolean show = searchPanel.getVisibility() != View.VISIBLE;
        searchPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            searchInput.requestFocus();
            if (searchInput.isEnabled()) {
                InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (keyboard != null) keyboard.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
            }
        }
    }

    private void indexText() {
        textExecutor.execute(() -> {
            try {
                DocumentTextIndex index = PdfPageTextExtractor.extract(this, session.document.path);
                runOnUiThread(() -> {
                    if (destroyed) return;
                    textIndex = index;
                    searchInput.setEnabled(true);
                    searchResult.setText(index.hasSearchableText()
                            ? "Type to search" : "No searchable text found");
                    performSearch(searchInput.getText().toString());
                    updateAskEnabled();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (destroyed) return;
                    searchInput.setEnabled(false);
                    searchResult.setText("Search unavailable");
                    setSearchNavigationEnabled(false);
                    updateAskEnabled();
                });
            }
        });
    }

    private void performSearch(String query) {
        if (textIndex == null) {
            searchResult.setText("Indexing…");
            setSearchNavigationEnabled(false);
            return;
        }
        matches = textIndex.search(query);
        selectedMatch = matches.isEmpty() ? -1 : firstMatchOnOrAfterCurrentPage();
        updateSearchResult(!matches.isEmpty());
    }

    private int firstMatchOnOrAfterCurrentPage() {
        int page = model == null ? 0 : model.page();
        for (int i = 0; i < matches.size(); i++) if (matches.get(i).page >= page) return i;
        return 0;
    }

    private void moveSearch(int delta) {
        if (matches.isEmpty()) return;
        selectedMatch = (selectedMatch + delta + matches.size()) % matches.size();
        updateSearchResult(true);
    }

    private void updateSearchResult(boolean jump) {
        if (matches.isEmpty()) {
            boolean blank = searchInput.getText().toString().trim().isEmpty();
            searchResult.setText(blank ? (textIndex != null && !textIndex.hasSearchableText()
                    ? "No searchable text found" : "Type to search") : "No results");
            setSearchNavigationEnabled(false);
            return;
        }
        DocumentTextIndex.Match match = matches.get(selectedMatch);
        String announcement = "Result " + (selectedMatch + 1) + " of " + matches.size()
                + ", page " + (match.page + 1);
        searchResult.setText((selectedMatch + 1) + " of " + matches.size()
                + " · p" + (match.page + 1));
        searchResult.setContentDescription(announcement);
        setSearchNavigationEnabled(true);
        if (jump) {
            goToPage(match.page, true);
            searchResult.announceForAccessibility(announcement);
        }
    }

    private void setSearchNavigationEnabled(boolean enabled) {
        searchPrevious.setEnabled(enabled);
        searchNext.setEnabled(enabled);
        searchPrevious.setAlpha(enabled ? 1f : 0.35f);
        searchNext.setAlpha(enabled ? 1f : 0.35f);
    }

    private void updateAskEnabled() {
        boolean enabled = model != null && textIndex != null
                && !textIndex.pageText(model.page()).trim().isEmpty();
        askButton.setEnabled(enabled);
        askButton.setAlpha(enabled ? 1f : 0.5f);
        askButton.setContentDescription(enabled ? "Ask Orbit about this page"
                : "Ask Orbit about this page, no searchable page text available");
    }

    private void askAboutPage() {
        if (model == null || textIndex == null) return;
        String text = textIndex.pageText(model.page());
        if (text.trim().isEmpty()) {
            Toast.makeText(this, "No searchable text found on this page", Toast.LENGTH_SHORT).show();
            return;
        }
        DocumentPageContext page = new DocumentPageContext(session.document.label, model.page(),
                model.pageCount(), text);
        String staged = SharedContentStore.stageDocumentPage(page);
        if (staged.isEmpty()) return;
        UiKit.haptic(askButton, HapticFeedbackConstants.CLOCK_TICK);
        Intent chat = new Intent(this, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, ConversationStore.newId())
                .putExtra(ChatActivity.EXTRA_SHARE_TOKEN, staged)
                .putExtra(ChatActivity.EXTRA_FOCUS_COMPOSER, true);
        // This is only a local composer handoff. The ordinary Send button remains the sole route
        // to SubmissionGate and OrbitRequestManager.
        startActivities(OrbitNavigation.stackFor(this, chat));
    }

    private ImageButton iconButton(int icon, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setColorFilter(UiKit.TEXT);
        button.setBackground(UiKit.ripple(android.graphics.Color.TRANSPARENT,
                UiKit.withAlpha(UiKit.accent(this), 70), 99, this));
        button.setPadding(UiKit.dp(this, 11), UiKit.dp(this, 11),
                UiKit.dp(this, 11), UiKit.dp(this, 11));
        button.setContentDescription(description);
        return button;
    }

    private Button actionButton(String text, String description) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTextColor(UiKit.TEXT);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setStateListAnimator(null);
        button.setPadding(UiKit.dp(this, 14), 0, UiKit.dp(this, 14), 0);
        button.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), 100), UiKit.accent(this), 15, this));
        button.setContentDescription(description);
        UiKit.pressScale(button);
        return button;
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(STATE_TOKEN, token);
        out.putInt(STATE_PAGE, model == null ? restoredPage : model.page());
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (renderer != null) renderer.close();
        textExecutor.shutdownNow();
        for (Page page : pages) page.release();
        pages.clear();
        if (isFinishing()) DocumentViewerStore.close(token);
        super.onDestroy();
    }

    // Test seams: state and privacy behavior, not rendered pixels.
    int currentPageForTest() { return model == null ? -1 : model.page(); }
    int loadedPageCountForTest() {
        int count = 0;
        for (Page page : pages) if (page.bitmap != null) count++;
        return count;
    }
    boolean askEnabledForTest() { return askButton != null && askButton.isEnabled(); }
}
