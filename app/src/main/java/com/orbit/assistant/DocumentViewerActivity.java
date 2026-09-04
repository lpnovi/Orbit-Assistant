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
import java.util.LinkedHashMap;
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

    /**
     * One page's place in the pager, and the views that fill it while it is near enough to matter.
     *
     * <p>The container is permanent because the pager positions pages by child index, so every page
     * needs a slot whether or not anything is in it. What is inside is not permanent, and that is
     * the point: {@code AttachmentPagerView} measures and lays out every child on every traversal,
     * so a page kept furnished with an image view and a status label costs a measure pass each time
     * the document is rotated, the search panel opens, or the window insets change.
     *
     * <p>At the few hundred pages a textbook has, that was acceptable. At the three thousand this
     * viewer is willing to open it is not: three thousand text labels re-measuring on every layout
     * is a visible stall, on top of the megabytes those views cost simply to exist. An empty
     * container measures in nothing at all, so only the pages inside the render window — the
     * current page and the one either side of it, the furthest a single swipe can reach — are
     * furnished.
     */
    private static final class Page {
        final FrameLayout container;
        ZoomableImageView image;
        TextView state;
        Bitmap bitmap;
        boolean requested;

        Page(FrameLayout container) {
            this.container = container;
        }

        boolean furnished() { return image != null; }

        void release() {
            if (image != null) image.setBitmap(null);
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            bitmap = null;
            requested = false;
        }

        /** Releases the page's bitmap and takes its views back out of the hierarchy. */
        void unfurnish() {
            release();
            container.removeAllViews();
            image = null;
            state = null;
        }

        void showState(String message) {
            if (state == null) return;
            state.setText(message);
            state.setVisibility(View.VISIBLE);
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

    /**
     * Character geometry for the last couple of pages a search landed on.
     *
     * <p>Extracted per page rather than with the index, because geometry costs four floats per
     * character: keeping it for a 388-page book would cost tens of megabytes to answer a question
     * about one page. Two pages is enough for Next and Previous to move between neighbours without
     * re-reading, and small enough to be free.
     */
    private static final int GEOMETRY_CACHE_PAGES = 2;
    /** A page dense with matches must not turn one search into thousands of drawn rectangles. */
    private static final int MAX_HIGHLIGHTS_PER_PAGE = 240;
    private final LinkedHashMap<Integer, DocumentTextGeometry> geometry = new LinkedHashMap<>();
    private final ExecutorService geometryExecutor = Executors.newSingleThreadExecutor();
    /** Which page's highlights are currently drawn, so they can be taken down again. */
    private int highlightedPage = -1;
    /** Bumped whenever the query or selection changes, so a slow extraction cannot arrive late. */
    private long highlightGeneration;
    private String currentQuery = "";

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
        titleLp.setMarginEnd(UiKit.dp(this, 4));
        header.addView(title, titleLp);
        // The title is what gives way when a long filename meets a large text size, because the
        // two controls beside it are the only way out of this screen and into a chat about it.
        // Without this the header lays itself out title-first and pushes them off the edge.
        title.setMinWidth(0);

        searchButton = iconButton(R.drawable.ic_search, "Search document");
        searchButton.setOnClickListener(v -> toggleSearch());
        LinearLayout.LayoutParams searchButtonLp = new LinearLayout.LayoutParams(
                UiKit.dp(this, 48), UiKit.dp(this, 48));
        header.addView(searchButton, searchButtonLp);

        // The visible label stays two words. The whole action — "Ask Orbit about this page" — is
        // what a screen reader is told, which is where the extra words are worth their room.
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
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                0, UiKit.dp(this, 44), 1f);
        // The field yields space to the count rather than the other way round: "1284 of 1863 · p311"
        // being clipped to "1284 of 18…" makes the one thing the panel exists to say unreadable,
        // while a search box a few characters narrower costs nothing.
        inputLp.setMarginEnd(UiKit.dp(this, 4));
        panel.addView(searchInput, inputLp);
        searchResult = UiKit.text(this, "Indexing…", 11.5f, UiKit.MUTED, true);
        searchResult.setGravity(Gravity.CENTER);
        searchResult.setMaxLines(2);
        // Sized to its own content, with a floor so the panel does not shuffle sideways on every
        // keystroke, and no ceiling so a long count and a large accessibility text size both fit.
        searchResult.setMinWidth(UiKit.dp(this, 78));
        panel.addView(searchResult, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 44)));
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
        container.setFocusable(true);
        container.setContentDescription("Page " + (pageNumber + 1) + " of " + model.pageCount());
        Page page = new Page(container);
        pages.add(page);
        pager.addPage(container, new AttachmentPagerView.Page() {
            // An unfurnished page has no transform to speak of, so it can absorb nothing and
            // hands every sideways drag straight to the pager, which is the right answer.
            @Override public boolean canPanHorizontally(int direction) {
                return page.furnished() && page.image.canPanHorizontally(direction);
            }
            @Override public void resetTransform() {
                if (page.furnished()) page.image.resetTransform();
            }
        });
    }

    /** Gives a page the views it needs to show something, if it does not already have them. */
    private void furnish(Page page) {
        if (page == null || page.furnished()) return;
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
        page.container.addView(image, new FrameLayout.LayoutParams(
                maxWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        TextView state = UiKit.text(this, "Rendering page…", 13, UiKit.MUTED, false);
        state.setGravity(Gravity.CENTER);
        page.container.addView(state, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        page.image = image;
        page.state = state;
    }

    private void updateRenderWindow() {
        if (destroyed || model == null || renderer == null || pager.getWidth() <= 0) return;
        List<Integer> wantedList = DocumentRenderWindow.around(model.page(), model.pageCount());
        Set<Integer> wanted = new HashSet<>(wantedList);
        long generation = renderWindow.nextGeneration();
        for (int i = 0; i < pages.size(); i++) {
            Page page = pages.get(i);
            if (!wanted.contains(i)) {
                // Views as well as bitmaps: a page well outside the window contributes nothing to
                // the screen and should contribute nothing to a layout pass either.
                if (page.furnished()) page.unfurnish();
                continue;
            }
            furnish(page);
            if (page.bitmap != null) continue;
            page.requested = true;
            page.showState("Rendering page…");
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
        furnish(page);
        page.release();
        page.bitmap = bitmap;
        page.image.setBitmap(bitmap);
        page.state.setVisibility(View.GONE);
        page.container.setContentDescription("Page " + (pageNumber + 1) + " of "
                + model.pageCount());
        // A page re-rendered while a search result is on it gets its highlights back; the bitmap
        // changed, the geometry did not.
        if (pageNumber == highlightedPage) showSelectedHighlight();
    }

    private void showPageError(long generation, int pageNumber, String message) {
        if (!renderWindow.accepts(generation) || pageNumber < 0 || pageNumber >= pages.size()) return;
        Page page = pages.get(pageNumber);
        page.requested = false;
        page.showState(message);
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
            showSelectedHighlight();
            return;
        }
        // Closing search puts the document back the way it was to read. A highlight left behind
        // would follow the reader through the rest of the book.
        highlightGeneration++;
        clearHighlights();
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
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
        currentQuery = query == null ? "" : query.trim();
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
            clearHighlights();
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
        showSelectedHighlight();
    }

    // ---- search highlights ------------------------------------------------------------------------

    /**
     * Draws the selected result on the page it is on.
     *
     * <p>Knowing which page a result is on was never the hard part — the viewer already jumped to
     * it. What was missing is the part the user actually needs: on a printed page of a textbook,
     * "8 of 186 · p31" leaves them to find the word themselves. The selected occurrence is drawn
     * strongly, and the other matches on the same page faintly, so Next visibly moves rather than
     * appearing to do nothing when two results share a page.
     */
    private void showSelectedHighlight() {
        if (destroyed || matches.isEmpty() || selectedMatch < 0 || selectedMatch >= matches.size()) {
            clearHighlights();
            return;
        }
        int page = matches.get(selectedMatch).page;
        DocumentTextGeometry known = geometry.get(page);
        if (known == null) {
            // Nothing is drawn until the geometry arrives; a stale highlight from the previous
            // result would be worse than none, so the page is cleared first either way.
            clearHighlights();
            requestGeometry(page);
            return;
        }
        drawHighlights(page, known);
    }

    private void drawHighlights(int page, DocumentTextGeometry known) {
        clearHighlights();
        if (page < 0 || page >= pages.size() || !known.hasGeometry()
                || currentQuery.isEmpty()) {
            return;
        }
        boolean offsetsAlign = known.text.equals(textIndex == null ? "" : textIndex.pageText(page));
        List<android.graphics.RectF> all = new ArrayList<>();
        int selectedFrom = 0;
        int selectedTo = 0;
        for (int i = 0; i < matches.size() && all.size() < MAX_HIGHLIGHTS_PER_PAGE; i++) {
            DocumentTextIndex.Match match = matches.get(i);
            if (match.page != page) continue;
            // The offset is trusted only when the two extractions produced the same string. When
            // they did not, counting occurrences is stable where counting characters is not, and a
            // highlight one word out would be worse than an honest miss.
            int offset = offsetsAlign
                    ? match.offset : known.offsetOfOccurrence(currentQuery, match.onPage);
            if (offset < 0) continue;
            List<android.graphics.RectF> rects = known.rectsFor(offset, match.length);
            if (rects.isEmpty()) continue;
            if (i == selectedMatch) {
                selectedFrom = all.size();
                selectedTo = all.size() + rects.size();
            }
            all.addAll(rects);
        }
        if (all.isEmpty()) return;
        Page target = pages.get(page);
        // Outside the render window a page has no image view to draw on. The page it is about to
        // become the current one, so this resolves on the next window update.
        if (!target.furnished()) return;
        final ZoomableImageView image = target.image;
        image.setHighlights(all, selectedFrom, selectedTo);
        highlightedPage = page;
        // A reader who has zoomed in keeps their magnification; the page is only nudged, and only
        // when the result would otherwise be off screen.
        image.post(() -> {
            if (!destroyed && highlightedPage == page && target.image == image) {
                image.revealSelectedHighlight(UiKit.dp(this, 28));
            }
        });
    }

    /**
     * Reads one page's character geometry off the UI thread.
     *
     * <p>Generation-checked on the way back, so a slow extraction for a result the user has already
     * moved past cannot paint the wrong page.
     */
    private void requestGeometry(int page) {
        if (page < 0) return;
        final long generation = ++highlightGeneration;
        geometryExecutor.execute(() -> {
            DocumentTextGeometry extracted;
            try {
                extracted = PdfPageTextExtractor.geometry(this, session.document.path, page);
            } catch (Exception error) {
                extracted = DocumentTextGeometry.EMPTY;
            }
            final DocumentTextGeometry result = extracted;
            runOnUiThread(() -> {
                if (destroyed || generation != highlightGeneration) return;
                remember(page, result);
                if (!matches.isEmpty() && selectedMatch >= 0 && selectedMatch < matches.size()
                        && matches.get(selectedMatch).page == page) {
                    drawHighlights(page, result);
                }
            });
        });
    }

    private void remember(int page, DocumentTextGeometry extracted) {
        geometry.remove(page);
        geometry.put(page, extracted);
        while (geometry.size() > GEOMETRY_CACHE_PAGES) {
            geometry.remove(geometry.keySet().iterator().next());
        }
    }

    /**
     * Takes every highlight down.
     *
     * <p>Nothing to undo on the page itself: highlights are drawn over the rendered bitmap, never
     * into it, so the cached render stays clean and can be reused for ordinary reading without any
     * colour leaking into it.
     */
    private void clearHighlights() {
        for (Page page : pages) if (page.furnished()) page.image.clearHighlights();
        highlightedPage = -1;
    }

    private void setSearchNavigationEnabled(boolean enabled) {
        searchPrevious.setEnabled(enabled);
        searchNext.setEnabled(enabled);
        searchPrevious.setAlpha(enabled ? 1f : 0.35f);
        searchNext.setAlpha(enabled ? 1f : 0.35f);
    }

    /**
     * Ask Orbit is available for any page Orbit can actually show.
     *
     * <p>It used to require extractable text, which turned it off for exactly the pages a reader
     * most wants to ask about: a scan, a full-page figure, a chart. Those pages can be sent as an
     * image, and a provider with vision can read them. A provider without vision is told plainly
     * that the page had no extractable text rather than being handed a page and left to guess.
     */
    private void updateAskEnabled() {
        boolean enabled = model != null && model.pageCount() > 0;
        askButton.setEnabled(enabled);
        askButton.setAlpha(enabled ? 1f : 0.5f);
        boolean hasText = model != null && textIndex != null
                && !textIndex.pageText(model.page()).trim().isEmpty();
        // The visible label stays "Ask Orbit"; the whole action is only ever spoken here.
        askButton.setContentDescription(enabled && !hasText
                ? "Ask Orbit about this page, no extractable text on this page"
                : "Ask Orbit about this page");
    }

    /**
     * Stages this exact page for a new chat. Nothing is ever sent from here.
     *
     * <p>The page travels as one structured object holding its text, its bounded rendering and the
     * reference needed to reopen the document at it — one thing the user attached, described
     * completely, rather than a sentence that happens to mention a page number.
     */
    private void askAboutPage() {
        if (model == null || model.pageCount() <= 0) return;
        int pageIndex = model.page();
        String text = textIndex == null ? "" : textIndex.pageText(pageIndex);
        // Rendered on the UI thread deliberately: it is one bounded page, the user has just tapped
        // a button and is waiting for the next screen, and staging without it would silently drop
        // the visual half of the context.
        Bitmap rendered = PdfPageImage.render(session.document.path, pageIndex);
        DocumentPageContext page = new DocumentPageContext(session.document.label, pageIndex,
                model.pageCount(), text, session.document, rendered);
        if (!page.isUsable()) {
            Toast.makeText(this, "Orbit could not read or render this page",
                    Toast.LENGTH_SHORT).show();
            return;
        }
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
        geometryExecutor.shutdownNow();
        geometry.clear();
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
