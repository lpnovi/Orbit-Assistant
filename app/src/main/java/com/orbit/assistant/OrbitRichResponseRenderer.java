package com.orbit.assistant;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared native assistant-response renderer for full chat and the assistant sheet. */
public final class OrbitRichResponseRenderer {
    // What a block is lives in ResponseBlocks, which both this renderer and the progressive one
    // parse with. Keeping a second copy of these expressions here is exactly how a response comes
    // to format one way while it streams and another way once it lands.
    private static final int MAX_IMAGES = 4;

    private OrbitRichResponseRenderer() {}

    /** How many images one response may actually load, shared with the progressive path. */
    static int maxImages() { return MAX_IMAGES; }

    public static boolean prefersWideLayout(String raw) {
        if (raw == null) return false;
        return raw.length() > 140 || raw.contains("\n") || raw.contains("```") ||
                raw.contains("|") || raw.contains("![");
    }

    public static View render(Context context, String rawText, int bubbleFill, boolean compact) {
        LinearLayout bubble = new LinearLayout(context);
        applyBubbleChrome(bubble, bubbleFill, compact);

        String source = rawText == null ? "" : rawText.replace("\r", "");
        try { renderBlocks(context, bubble, source, bubbleFill, compact); }
        catch (Throwable ignored) {
            bubble.removeAllViews();
            bubble.addView(text(context, source, chatSize(context, compact ? 14 : 15),
                    UiKit.onBubble(bubbleFill), false));
        }
        trimTrailingBlockSpacing(bubble);
        if (!prefersWideLayout(source)) {
            for (int i = 0; i < bubble.getChildCount(); i++) {
                View child = bubble.getChildAt(i);
                ViewGroup.LayoutParams lp = child.getLayoutParams();
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                child.setLayoutParams(lp);
            }
        }
        UiKit.watchTypography(bubble);
        return bubble;
    }

    private static void renderBlocks(Context c, LinearLayout out, String source,
                                     int fill, boolean compact) {
        int foreground = UiKit.onBubble(fill);
        int images = 0;
        for (ResponseBlocks.Block block : ResponseBlocks.parse(source)) {
            boolean asImage = block.kind == ResponseBlocks.Kind.IMAGE && images < MAX_IMAGES;
            if (block.kind == ResponseBlocks.Kind.IMAGE) images++;
            addBlock(out, buildBlock(c, block, fill, compact, asImage), c,
                    spacingFor(block, asImage), topSpacingFor(block));
        }
        if (out.getChildCount() == 0) out.addView(text(c, source,
                chatSize(c, compact ? 14 : 15),
                foreground, false));
    }

    /**
     * Builds the view for exactly one block.
     *
     * <p>The single place a block becomes a View, used by the completed render above and by
     * {@link ProgressiveResponseView} while a response is still arriving. That is the whole reason
     * it is separated out: before this, a streaming answer was a raw {@code TextView} and a
     * finished one was this tree, so they could not help but look different. Now the only
     * difference between the two paths is <em>when</em> they ask, not <em>what</em> they get.
     *
     * @param asImage whether an image block may load its picture. Beyond {@link #MAX_IMAGES} a
     *                response falls back to a link, exactly as it always has.
     */
    static View buildBlock(Context c, ResponseBlocks.Block block, int fill, boolean compact,
                           boolean asImage) {
        int foreground = UiKit.onBubble(fill);
        // A block still being written shows everything that has arrived, minus a delimiter whose
        // partner has not. Completed blocks, and blocks whose text is not Markdown at all, are
        // never touched - which is what stops a trailing backtick being trimmed out of a snippet.
        String source = block.displaySource();
        switch (block.kind) {
            case CODE:
                return codeBlock(c, source, block.language, block.complete);
            case TABLE:
                return table(c, tableRows(source), foreground, fill);
            case IMAGE: {
                Matcher image = ResponseBlocks.IMAGE.matcher(source.trim());
                if (!image.matches()) break;
                return asImage
                        ? image(c, image.group(1), image.group(2), foreground)
                        : linkedFallback(c, image.group(1), image.group(2), foreground, fill);
            }
            case HEADING: {
                Matcher heading = ResponseBlocks.HEADING.matcher(source.trim());
                if (!heading.matches()) break;
                int level = heading.group(1).length();
                float size = level == 1 ? (compact ? 18 : 19)
                        : level == 2 ? (compact ? 16.5f : 17.5f) : 15.5f;
                return richText(c, heading.group(2), chatSize(c, size), foreground, true, fill);
            }
            case RULE: {
                View rule = new View(c);
                rule.setBackgroundColor(UiKit.withAlpha(foreground, 60));
                rule.setMinimumHeight(UiKit.dp(c, 1));
                return rule;
            }
            case QUOTE: {
                StringBuilder quote = new StringBuilder();
                for (String line : source.split("\n", -1)) {
                    String part = line.trim();
                    if (part.startsWith(">")) part = part.substring(1).trim();
                    if (quote.length() > 0) quote.append('\n');
                    quote.append(part);
                }
                return quote(c, quote.toString(), foreground, fill);
            }
            case LIST: {
                LinearLayout listBlock = new LinearLayout(c);
                listBlock.setOrientation(LinearLayout.VERTICAL);
                for (String line : source.split("\n", -1)) {
                    Matcher item = ResponseBlocks.LIST.matcher(line);
                    if (!item.matches()) continue;
                    int indent = Math.min(3, item.group(1).replace("\t", "    ").length() / 2);
                    ResponseBlocks.Task task = ResponseBlocks.task(item.group(2), item.group(3));
                    float size = chatSize(c, compact ? 14 : 15);
                    TextView itemView = task != null
                            ? taskItem(c, task, size, foreground, fill)
                            : richText(c, (item.group(2).matches("\\d+.*") ? item.group(2) : "•")
                                    + "  " + item.group(3), size, foreground, false, fill);
                    // Identical padding either way, so a task list and a bullet list sitting one
                    // above the other line their text up rather than stepping in and out.
                    itemView.setPadding(UiKit.dp(c, 8 + indent * 14), UiKit.dp(c, 2), 0,
                            UiKit.dp(c, 2));
                    listBlock.addView(itemView);
                }
                return listBlock;
            }
            default:
                break;
        }
        return richText(c, source, chatSize(c, compact ? 14 : 15), foreground, false, fill);
    }

    /** The gap below one block, matching the spacing the completed renderer has always used. */
    static int spacingFor(ResponseBlocks.Block block, boolean asImage) {
        switch (block.kind) {
            case CODE:
            case TABLE:
                return 7;
            case IMAGE:
                return asImage ? 8 : 4;
            case HEADING:
                return ResponseBlocks.HEADING.matcher(block.source.trim()).matches()
                        && block.source.trim().startsWith("# ") ? 9 : 6;
            case RULE:
                return 7;
            case LIST:
                return 5;
            default:
                return 6;
        }
    }

    /** A rule needs its own vertical breathing room above as well as below. */
    static int topSpacingFor(ResponseBlocks.Block block) {
        return block.kind == ResponseBlocks.Kind.RULE ? 7 : 0;
    }

    private static List<String[]> tableRows(String source) {
        List<String[]> rows = new ArrayList<>();
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            // The divider row is structure, never content, and is not drawn as a row.
            if (i == 1 && ResponseBlocks.TABLE_DIVIDER.matcher(lines[i]).matches()) continue;
            if (lines[i].trim().isEmpty()) continue;
            rows.add(splitTableRow(lines[i]));
        }
        return rows;
    }

    /**
     * One Markdown task item: a read-only checkbox followed by the item's own rich text.
     *
     * <p>The same {@link TextView} an ordinary bullet gets, with the box drawn by a
     * {@link TaskBoxSpan} over a leading placeholder rather than added as a second view. That is
     * what keeps the box and the words one row: they wrap together, scale together, indent
     * together, and the box cannot end up beside the wrong line of a three-line task.
     *
     * <p>The text after the box is rendered by the ordinary inline renderer, so bold, italic,
     * combined emphasis, inline code and links all work inside a task exactly as they do anywhere
     * else. Accessibility is handled by describing the row as its state plus its words; the box
     * itself is never announced as something to operate, because it is not.
     */
    private static TextView taskItem(Context c, ResponseBlocks.Task task, float size,
                                     int foreground, int surface) {
        TextView view = text(c, "", size, foreground, false);
        SpannableStringBuilder line = new SpannableStringBuilder(TaskBoxSpan.PLACEHOLDER);
        line.setSpan(TaskBoxSpan.on(c, task.checked, surface), 0, line.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        CharSequence body = OrbitMarkdown.renderInline(c, task.text, foreground, surface);
        line.append(body);
        view.setText(line);
        view.setMovementMethod(LinkMovementMethod.getInstance());
        view.setLinksClickable(true);
        view.setLinkTextColor(UiKit.linkColorOn(c, surface));
        view.setContentDescription((task.checked ? "Checked, " : "Unchecked, ") + body);
        UiKit.applyBubbleTextMetrics(view);
        return view;
    }

    private static TextView richText(Context c, String value, float size, int color, boolean bold,
                                     int surface) {
        TextView view = text(c, "", size, color, bold);
        // Inline code is tinted from the surface it lands on, so the same reply reads correctly on
        // a classic bubble, an accent one, a pastel one, and on AMOLED.
        CharSequence rendered = OrbitMarkdown.renderInline(c, value, color, surface);
        view.setText(rendered);
        view.setMovementMethod(LinkMovementMethod.getInstance());
        view.setLinksClickable(true);
        // Coloured against the surface this text actually sits on. Using the raw accent here made
        // links invisible whenever the accent and the bubble fill were the same colour.
        view.setLinkTextColor(UiKit.linkColorOn(c, surface));
        UiKit.applyBubbleTextMetrics(view);
        return view;
    }

    private static TextView text(Context c, CharSequence value, float size, int color, boolean bold) {
        TextView view = UiKit.text(c, "", size, color, bold);
        view.setText(value);
        view.setTextIsSelectable(true);
        return view;
    }

    private static View quote(Context c, String value, int foreground, int surface) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        View rule = new View(c);
        // Same collision as inline links: this bar sits on the bubble fill, which the user can
        // set to the accent itself, and a bar the colour of its background is no bar at all.
        rule.setBackgroundColor(UiKit.linkColorOn(c, surface));
        row.addView(rule, new LinearLayout.LayoutParams(UiKit.dp(c, 3),
                ViewGroup.LayoutParams.MATCH_PARENT));
        TextView body = richText(c, value, chatSize(c, 14),
                UiKit.withAlpha(foreground, 220), false, surface);
        body.setPadding(UiKit.dp(c, 10), UiKit.dp(c, 4), UiKit.dp(c, 3), UiKit.dp(c, 4));
        row.addView(body, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private static View codeBlock(Context c, String code, String language) {
        return codeBlock(c, code, language, true);
    }

    /**
     * @param complete false while the closing fence has not arrived. The block is drawn either
     *                 way - once Orbit knows a fence opened, the user should be looking at a code
     *                 surface rather than at backticks - but Copy waits, because copying a
     *                 half-written snippet silently gives someone code that will not compile.
     */
    private static View codeBlock(Context c, String code, String language, boolean complete) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(UiKit.dp(c, 11), UiKit.dp(c, 7), UiKit.dp(c, 8), UiKit.dp(c, 9));
        card.setBackground(UiKit.rounded(UiKit.SURFACE_2, UiKit.RADIUS_CARD, c));

        LinearLayout header = new LinearLayout(c);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = UiKit.text(c, language.isEmpty() ? "Code" : language,
                10.5f, UiKit.MUTED, true);
        header.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button copy = new Button(c);
        copy.setText("Copy");
        copy.setAllCaps(false);
        copy.setTextSize(10.5f);
        copy.setTextColor(UiKit.accent(c));
        copy.setMinHeight(0); copy.setMinimumHeight(0); copy.setMinWidth(0); copy.setMinimumWidth(0);
        copy.setPadding(UiKit.dp(c, 9), 0, UiKit.dp(c, 9), 0);
        copy.setBackground(UiKit.ripple(Color.TRANSPARENT, UiKit.accent(c), 10, c));
        copy.setContentDescription("Copy code block");
        copy.setOnClickListener(v -> {
            ClipboardManager manager = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) manager.setPrimaryClip(ClipData.newPlainText("Orbit code", code));
            Toast.makeText(c, "Code copied", Toast.LENGTH_SHORT).show();
        });
        // Held back rather than hidden, so the header does not change shape when the block closes
        // and the code below it does not shift up and down as the answer finishes.
        copy.setVisibility(complete ? View.VISIBLE : View.INVISIBLE);
        copy.setEnabled(complete);
        header.addView(copy, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                UiKit.dp(c, 32)));
        card.addView(header);

        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(true);
        TextView body = text(c, code, chatSize(c, 13), UiKit.TEXT, false);
        UiKit.applyCodeTypeface(body);
        body.setHorizontallyScrolling(true);
        body.setPadding(0, UiKit.dp(c, 4), UiKit.dp(c, 10), 0);
        scroll.addView(body, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(scroll);
        return card;
    }

    private static String[] splitTableRow(String line) {
        String clean = line.trim();
        if (clean.startsWith("|")) clean = clean.substring(1);
        if (clean.endsWith("|")) clean = clean.substring(0, clean.length() - 1);
        return clean.split("\\|", -1);
    }

    /**
     * A Markdown table, with every cell in a row sharing that row's height.
     *
     * <p>Each cell used to be laid out at {@code WRAP_CONTENT} height, so a row whose columns held
     * different amounts of text ended up as cells of four different heights. Each cell's background
     * and border stopped where its own words did, and the assistant bubble showed through
     * underneath the shorter ones — on a purple bubble, a row of dark cards floating over purple
     * gutters rather than one table row.
     *
     * <p>The fix is layout behaviour, not measurement. A {@link TableRow} is a horizontal
     * {@link LinearLayout}, and a horizontal LinearLayout whose own height wraps already knows how
     * to give a {@code MATCH_PARENT} child the height of the tallest sibling: it measures the row
     * once, then re-measures exactly those children against the height it found. So the row still
     * grows from its own content and its own text size — nothing here is a fixed height — and the
     * work is one extra measure of the cells in one row, not a walk of the table on every token.
     *
     * <p>Because that resolution happens per row, rows keep their independent heights: a short
     * header row stays short above a tall body row. Widths, wrapping, header styling, borders and
     * the horizontal scroller around the whole table are all untouched.
     */
    private static View table(Context c, List<String[]> rows, int foreground, int surface) {
        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(true);
        TableLayout table = new TableLayout(c);
        table.setStretchAllColumns(false);
        for (int r = 0; r < rows.size(); r++) {
            TableRow row = new TableRow(c);
            String[] cells = rows.get(r);
            for (String cell : cells) {
                TextView view = richText(c, cell.trim(), chatSize(c, 12.5f), foreground, r == 0, surface);
                view.setGravity(Gravity.TOP | Gravity.START);
                float scale = Prefs.chatTextScale(c);
                view.setMinWidth(UiKit.dp(c, Math.round(104 * scale)));
                view.setMaxWidth(UiKit.dp(c, Math.round(220 * scale)));
                view.setPadding(UiKit.dp(c, 9), UiKit.dp(c, 7),
                        UiKit.dp(c, 9), UiKit.dp(c, 7));
                view.setBackground(UiKit.outlined(r == 0 ? UiKit.SURFACE_3 : UiKit.SURFACE_2,
                        UiKit.withAlpha(UiKit.MUTED, 50), 0, c));
                row.addView(view, new TableRow.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            table.addView(row);
        }
        scroll.addView(table, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.setContentDescription("Markdown table");
        return scroll;
    }

    private static View image(Context c, String alt, String url, int foreground) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiKit.rounded(UiKit.SURFACE_2, 13, c));

        FrameLayout frame = new FrameLayout(c);
        ProgressBar progress = new ProgressBar(c);
        progress.setIndeterminateTintList(ColorStateList.valueOf(UiKit.accent(c)));
        FrameLayout.LayoutParams progressLp = new FrameLayout.LayoutParams(
                UiKit.dp(c, 28), UiKit.dp(c, 28), Gravity.CENTER);
        frame.addView(progress, progressLp);
        TextView loading = UiKit.text(c, "Loading image…", chatSize(c, 12), UiKit.MUTED, false);
        FrameLayout.LayoutParams loadingLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        loadingLp.bottomMargin = UiKit.dp(c, 13);
        frame.addView(loading, loadingLp);
        card.addView(frame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(c, 150)));

        String description = alt == null ? "" : alt.trim();
        if (!description.isEmpty()) {
            TextView caption = UiKit.text(c, description, chatSize(c, 11),
                    UiKit.withAlpha(foreground, 205), false);
            caption.setPadding(UiKit.dp(c, 10), UiKit.dp(c, 7), UiKit.dp(c, 10), UiKit.dp(c, 8));
            caption.setContentDescription("Image caption: " + description);
            card.addView(caption);
        }

        if (!RemoteImageLoader.hasSafeHttpsSyntax(url)) {
            showImageFailure(c, frame, description, url,
                    "Orbit blocked this private or unsafe image address", foreground);
            return card;
        }
        RemoteImageLoader.load(c, url, (bitmap, error) -> {
            if (bitmap == null) {
                showImageFailure(c, frame, description, url, error, foreground);
                return;
            }
            frame.removeAllViews();
            ImageView view = new ImageView(c);
            view.setImageBitmap(bitmap);
            view.setAdjustViewBounds(true);
            view.setMaxHeight(UiKit.dp(c, 360));
            view.setScaleType(ImageView.ScaleType.FIT_CENTER);
            view.setBackground(UiKit.rounded(UiKit.SURFACE_2, 13, c));
            view.setClipToOutline(true);
            view.setContentDescription(description.isEmpty() ? "Assistant response image" : description);
            view.setOnClickListener(v -> openUrl(c, url));
            frame.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ViewGroup.LayoutParams lp = frame.getLayoutParams();
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            frame.setLayoutParams(lp);
        });
        return card;
    }

    private static void showImageFailure(Context c, FrameLayout frame, String alt, String url,
                                         String error, int foreground) {
        frame.removeAllViews();
        LinearLayout failure = new LinearLayout(c);
        failure.setOrientation(LinearLayout.VERTICAL);
        failure.setGravity(Gravity.CENTER);
        failure.setPadding(UiKit.dp(c, 12), UiKit.dp(c, 12), UiKit.dp(c, 12), UiKit.dp(c, 12));
        String label = alt.isEmpty() ? "Image unavailable" : alt;
        failure.addView(UiKit.text(c, label, chatSize(c, 12), foreground, true));
        failure.addView(UiKit.text(c, error == null || error.isEmpty()
                ? "Image could not be loaded" : error, chatSize(c, 11), UiKit.MUTED, false));
        if (RemoteImageLoader.hasSafeHttpsSyntax(url) &&
                (error == null || !error.startsWith("Orbit blocked"))) {
            Button open = new Button(c);
            open.setText("Open image");
            open.setAllCaps(false);
            open.setTextSize(11);
            open.setTextColor(UiKit.accent(c));
            open.setContentDescription("Open original image in browser");
            open.setBackground(UiKit.ripple(Color.TRANSPARENT, UiKit.accent(c), 12, c));
            open.setOnClickListener(v -> openUrl(c, url));
            failure.addView(open, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(c, 38)));
        }
        frame.addView(failure, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private static View linkedFallback(Context c, String alt, String url, int foreground,
                                       int surface) {
        TextView view = richText(c, "[" + (alt == null || alt.isEmpty() ? "Image" : alt) +
                "](" + url + ")", chatSize(c, 13), foreground, false, surface);
        view.setContentDescription("Additional response image link");
        return view;
    }

    private static void openUrl(Context c, String url) {
        try { c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception ignored) { Toast.makeText(c, "Could not open link", Toast.LENGTH_SHORT).show(); }
    }

    private static void addBlock(LinearLayout out, View child, Context c, int bottomDp) {
        addBlock(out, child, c, bottomDp, 0);
    }

    private static void addBlock(LinearLayout out, View child, Context c, int bottomDp, int topDp) {
        out.addView(child, blockLayout(c, bottomDp, topDp));
    }

    /** The layout one block sits under, shared with the progressive path so spacing cannot drift. */
    static LinearLayout.LayoutParams blockLayout(Context c, int bottomDp, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, UiKit.dp(c, topDp), 0, UiKit.dp(c, bottomDp));
        return lp;
    }

    /** The bubble's own padding, shared so a streamed answer and a stored one sit identically. */
    static void applyBubbleChrome(LinearLayout bubble, int bubbleFill, boolean compact) {
        Context context = bubble.getContext();
        int horizontal = UiKit.dp(context, compact ? 13 : 15);
        int vertical = UiKit.dp(context, compact ? 10 : 12);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(horizontal, vertical, horizontal, vertical);
        bubble.setBackground(UiKit.rounded(bubbleFill, UiKit.RADIUS_BUBBLE, context));
        bubble.setForceDarkAllowed(false);
    }

    /**
     * Block spacing separates blocks. It must not hang off the bottom of the last one.
     *
     * <p>Every block was added with a bottom margin, the final one included, so a rich assistant
     * bubble carried that margin below its last line <em>on top of</em> the bubble's own symmetric
     * vertical padding. The bubble therefore always had more empty space under its text than above
     * it — a small "chin" — which is invisible in a long answer and unmissable in a one-line one
     * such as "13.5", where nothing else fills the bubble.
     *
     * <p>This is why the user bubble sitting directly above looked correctly balanced: it is a
     * plain TextView with the same symmetric padding and no trailing margin. The two paths were
     * never using different font metrics, different {@code includeFontPadding}, or different
     * padding values — one of them simply had an extra gap after its content.
     */
    private static void trimTrailingBlockSpacing(LinearLayout bubble) {
        for (int i = bubble.getChildCount() - 1; i >= 0; i--) {
            View child = bubble.getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (lp instanceof LinearLayout.LayoutParams) {
                ((LinearLayout.LayoutParams) lp).bottomMargin = 0;
                child.setLayoutParams(lp);
            }
            return;
        }
    }

    private static float chatSize(Context context, float defaultSp) {
        return Prefs.chatTextSp(context, defaultSp);
    }
}
