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
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern LIST = Pattern.compile("^(\\s*)([-+*]|\\d+[.)])\\s+(.+)$");
    private static final Pattern IMAGE = Pattern.compile("^!\\[([^]]*)]\\(([^\\s)]+)\\)\\s*$");
    private static final Pattern TABLE_DIVIDER = Pattern.compile(
            "^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$");
    private static final int MAX_IMAGES = 4;

    private OrbitRichResponseRenderer() {}

    public static boolean prefersWideLayout(String raw) {
        if (raw == null) return false;
        return raw.length() > 140 || raw.contains("\n") || raw.contains("```") ||
                raw.contains("|") || raw.contains("![");
    }

    public static View render(Context context, String rawText, int bubbleFill, boolean compact) {
        LinearLayout bubble = new LinearLayout(context);
        bubble.setOrientation(LinearLayout.VERTICAL);
        int horizontal = UiKit.dp(context, compact ? 13 : 15);
        int vertical = UiKit.dp(context, compact ? 10 : 12);
        bubble.setPadding(horizontal, vertical, horizontal, vertical);
        bubble.setBackground(UiKit.rounded(bubbleFill, 18, context));
        bubble.setForceDarkAllowed(false);

        String source = rawText == null ? "" : rawText.replace("\r", "");
        try { renderBlocks(context, bubble, source, bubbleFill, compact); }
        catch (Throwable ignored) {
            bubble.removeAllViews();
            bubble.addView(text(context, source, chatSize(context, compact ? 14 : 15),
                    UiKit.onBubble(bubbleFill), false));
        }
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
        String[] lines = source.split("\n", -1);
        int foreground = UiKit.onBubble(fill);
        int i = 0;
        int images = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.isEmpty()) { i++; continue; }

            if (trimmed.startsWith("```")) {
                String language = trimmed.length() > 3 ? trimmed.substring(3).trim() : "";
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].trim().startsWith("```")) {
                    if (code.length() > 0) code.append('\n');
                    code.append(lines[i++]);
                }
                if (i < lines.length) i++;
                addBlock(out, codeBlock(c, code.toString(), language), c, 7);
                continue;
            }

            if (i + 1 < lines.length && line.contains("|") &&
                    TABLE_DIVIDER.matcher(lines[i + 1]).matches()) {
                List<String[]> rows = new ArrayList<>();
                rows.add(splitTableRow(line));
                i += 2;
                while (i < lines.length && lines[i].contains("|") &&
                        !lines[i].trim().isEmpty()) rows.add(splitTableRow(lines[i++]));
                addBlock(out, table(c, rows, foreground, fill), c, 7);
                continue;
            }

            Matcher image = IMAGE.matcher(trimmed);
            if (image.matches()) {
                if (images++ < MAX_IMAGES) addBlock(out,
                        image(c, image.group(1), image.group(2), foreground), c, 8);
                else addBlock(out, linkedFallback(c, image.group(1), image.group(2), foreground, fill), c, 4);
                i++;
                continue;
            }

            Matcher heading = HEADING.matcher(trimmed);
            if (heading.matches()) {
                int level = heading.group(1).length();
                float size = level == 1 ? (compact ? 18 : 19) :
                        level == 2 ? (compact ? 16.5f : 17.5f) : 15.5f;
                TextView view = richText(c, heading.group(2), chatSize(c, size), foreground, true, fill);
                addBlock(out, view, c, level == 1 ? 9 : 6);
                i++;
                continue;
            }

            if (trimmed.matches("^[-*_]{3,}$")) {
                View rule = new View(c);
                rule.setBackgroundColor(UiKit.withAlpha(foreground, 60));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(c, 1));
                lp.setMargins(0, UiKit.dp(c, 7), 0, UiKit.dp(c, 7));
                out.addView(rule, lp);
                i++;
                continue;
            }

            if (trimmed.startsWith(">")) {
                StringBuilder quote = new StringBuilder();
                while (i < lines.length && lines[i].trim().startsWith(">")) {
                    String part = lines[i++].trim().substring(1).trim();
                    if (quote.length() > 0) quote.append('\n');
                    quote.append(part);
                }
                addBlock(out, quote(c, quote.toString(), foreground, fill), c, 6);
                continue;
            }

            Matcher list = LIST.matcher(line);
            if (list.matches()) {
                LinearLayout listBlock = new LinearLayout(c);
                listBlock.setOrientation(LinearLayout.VERTICAL);
                while (i < lines.length) {
                    Matcher item = LIST.matcher(lines[i]);
                    if (!item.matches()) break;
                    int indent = Math.min(3, item.group(1).replace("\t", "    ").length() / 2);
                    String marker = item.group(2).matches("\\d+.*") ? item.group(2) : "•";
                    TextView itemView = richText(c, marker + "  " + item.group(3),
                            chatSize(c, compact ? 14 : 15), foreground, false, fill);
                    itemView.setPadding(UiKit.dp(c, 8 + indent * 14), UiKit.dp(c, 2), 0,
                            UiKit.dp(c, 2));
                    listBlock.addView(itemView);
                    i++;
                }
                addBlock(out, listBlock, c, 5);
                continue;
            }

            StringBuilder paragraph = new StringBuilder(line.trim());
            i++;
            while (i < lines.length && !lines[i].trim().isEmpty() &&
                    !startsBlock(lines, i)) {
                paragraph.append('\n').append(lines[i].trim());
                i++;
            }
            addBlock(out, richText(c, paragraph.toString(), chatSize(c, compact ? 14 : 15),
                    foreground, false, fill), c, 6);
        }
        if (out.getChildCount() == 0) out.addView(text(c, source,
                chatSize(c, compact ? 14 : 15),
                foreground, false));
    }

    private static boolean startsBlock(String[] lines, int i) {
        String value = lines[i].trim();
        return value.startsWith("```") || value.startsWith(">") ||
                HEADING.matcher(value).matches() || LIST.matcher(lines[i]).matches() ||
                IMAGE.matcher(value).matches() || value.matches("^[-*_]{3,}$") ||
                (i + 1 < lines.length && lines[i].contains("|") &&
                        TABLE_DIVIDER.matcher(lines[i + 1]).matches());
    }

    private static TextView richText(Context c, String value, float size, int color, boolean bold,
                                     int surface) {
        TextView view = text(c, "", size, color, bold);
        CharSequence rendered = OrbitMarkdown.renderInline(c, value, color);
        view.setText(rendered);
        view.setMovementMethod(LinkMovementMethod.getInstance());
        view.setLinksClickable(true);
        // Coloured against the surface this text actually sits on. Using the raw accent here made
        // links invisible whenever the accent and the bubble fill were the same colour.
        view.setLinkTextColor(UiKit.linkColorOn(c, surface));
        view.setLineSpacing(0, 1.12f);
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
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(UiKit.dp(c, 11), UiKit.dp(c, 7), UiKit.dp(c, 8), UiKit.dp(c, 9));
        card.setBackground(UiKit.rounded(UiKit.SURFACE_2, 12, c));

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
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, UiKit.dp(c, bottomDp));
        out.addView(child, lp);
    }

    private static float chatSize(Context context, float defaultSp) {
        return Prefs.chatTextSp(context, defaultSp);
    }
}
