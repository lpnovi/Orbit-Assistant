package com.orbit.assistant;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** Native, informational view of recent stable Orbit GitHub Release notes. */
public final class WhatsNewActivity extends Activity {
    private LinearLayout notesContainer;
    private TextView status;
    private Button retry;

    /** Interactive Back for this page. Its classification lives in OrbitNavigation. */
    private OrbitPredictiveBack navigation;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.syncTheme(this);
        Window window = getWindow();
        window.setStatusBarColor(UiKit.BG);
        window.setNavigationBarColor(UiKit.BG);
        View content = buildContent();
        setContentView(content);
        UiKit.applyActivityInsets(this, content, true);
        navigation = OrbitPredictiveBack.install(this);
        loadNotes();
    }

    @Override protected void onResume() {
        super.onResume();
        UiPresence.enter(this);
    }

    @Override protected void onPause() {
        UiPresence.leave(this);
        super.onPause();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(UiKit.BG);
        scroll.setForceDarkAllowed(false);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 20);
        page.setPadding(p, UiKit.dp(this, 30), p, UiKit.dp(this, 48));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = iconButton();
        back.setOnClickListener(v -> navigation.performBack());
        header.addView(back, new LinearLayout.LayoutParams(UiKit.dp(this, 44), UiKit.dp(this, 44)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(UiKit.dp(this, 13), 0, 0, 0);
        titles.addView(UiKit.text(this, "What's New", 24, UiKit.TEXT, true));
        titles.addView(UiKit.text(this, "Recent stable Orbit releases", 13, UiKit.MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(header);

        TextView current = UiKit.text(this,
                "Current version: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")",
                13, UiKit.MUTED, false);
        LinearLayout.LayoutParams currentLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        currentLp.setMargins(UiKit.dp(this, 3), UiKit.dp(this, 20), UiKit.dp(this, 3), UiKit.dp(this, 12));
        page.addView(current, currentLp);

        status = UiKit.text(this, "Loading stable release notes…", 13, UiKit.MUTED, false);
        status.setLineSpacing(0, 1.1f);
        page.addView(status);

        retry = secondaryButton("Retry");
        retry.setVisibility(View.GONE);
        retry.setOnClickListener(v -> loadNotes());
        LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 44));
        retryLp.setMargins(0, UiKit.dp(this, 10), 0, 0);
        page.addView(retry, retryLp);

        notesContainer = new LinearLayout(this);
        notesContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams notesLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        notesLp.setMargins(0, UiKit.dp(this, 14), 0, 0);
        page.addView(notesContainer, notesLp);

        TextView source = UiKit.text(this,
                "Release notes come from the public lpnovi/Orbit-Assistant GitHub Releases page. Orbit caches the last successful result for offline viewing.",
                11, UiKit.MUTED, false);
        source.setLineSpacing(0, 1.1f);
        LinearLayout.LayoutParams sourceLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sourceLp.setMargins(UiKit.dp(this, 3), UiKit.dp(this, 10), UiKit.dp(this, 3), 0);
        page.addView(source, sourceLp);
        UiKit.applyTypography(page);
        return scroll;
    }

    private void loadNotes() {
        status.setText("Loading stable release notes…");
        status.setTextColor(UiKit.MUTED);
        retry.setVisibility(View.GONE);
        notesContainer.removeAllViews();
        ReleaseNotesRepository.load(this, new ReleaseNotesRepository.Callback() {
            @Override public void onLoaded(List<ReleaseNotesRepository.ReleaseNote> releases,
                                           boolean fromCache) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    status.setText(fromCache
                            ? "Offline · showing the last successfully cached release notes."
                            : "Latest stable release notes from GitHub.");
                    status.setTextColor(fromCache ? Color.rgb(236, 187, 95) : UiKit.SUCCESS);
                    renderReleases(releases);
                });
            }

            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    status.setText(message);
                    status.setTextColor(Color.rgb(239, 105, 105));
                    retry.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void renderReleases(List<ReleaseNotesRepository.ReleaseNote> releases) {
        notesContainer.removeAllViews();
        if (releases == null || releases.isEmpty()) {
            status.setText("No stable Orbit release notes are available yet.");
            retry.setVisibility(View.VISIBLE);
            return;
        }
        if (ReleaseNotesRepository.isNewerThanCurrent(releases.get(0).versionName)) {
            LinearLayout update = card();
            update.addView(UiKit.text(this,
                    "Update available · v" + releases.get(0).versionName, 15, UiKit.TEXT, true));
            TextView detail = UiKit.text(this,
                    "Return to About & updates to use Orbit's verified updater.", 12, UiKit.MUTED, false);
            detail.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 10));
            update.addView(detail);
            Button open = primaryButton("Open About & updates");
            open.setOnClickListener(v -> {
                startActivity(new Intent(this, UpdateActivity.class));
                finish();
            });
            update.addView(open, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 44)));
            notesContainer.addView(update, cardLp());
        }
        for (ReleaseNotesRepository.ReleaseNote release : releases) {
            notesContainer.addView(releaseCard(release), cardLp());
        }
        UiKit.applyTypography(notesContainer);
    }

    private View releaseCard(ReleaseNotesRepository.ReleaseNote release) {
        LinearLayout card = card();
        card.addView(UiKit.text(this, release.title, 17, UiKit.TEXT, true));
        String metadata = "v" + release.versionName;
        if (release.versionName.equals(BuildConfig.VERSION_NAME)) metadata += " · Installed";
        if (release.publishedAt.length() >= 10) metadata += " · " + release.publishedAt.substring(0, 10);
        TextView version = UiKit.text(this, metadata, 11, UiKit.MUTED, false);
        version.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 11));
        card.addView(version);

        CharSequence notes = markdown(release.body);
        TextView body = UiKit.text(this, "", 13, UiKit.TEXT, false);
        body.setText(notes.length() == 0 ? "No release notes were provided." : notes);
        body.setLineSpacing(UiKit.dp(this, 2), 1.08f);
        card.addView(body);
        return card;
    }

    /** Small native Markdown subset: headings, bullets, bold, links-as-labels, and inline code. */
    private CharSequence markdown(String source) {
        SpannableStringBuilder output = new SpannableStringBuilder();
        if (source == null) return output;
        String cleaned = source.replaceAll("\\[([^\\]]+)\\]\\([^\\)]+\\)", "$1");
        String[] lines = cleaned.replace("\r", "").split("\n", -1);
        for (String raw : lines) {
            String line = raw.trim();
            if (line.matches("^[-*_]{3,}$")) continue;
            boolean heading = false;
            while (line.startsWith("#")) {
                heading = true;
                line = line.substring(1).trim();
            }
            if (line.startsWith("- ") || line.startsWith("* ")) line = "• " + line.substring(2).trim();
            int start = output.length();
            appendInlineMarkdown(output, line);
            if (heading && output.length() > start)
                output.setSpan(new StyleSpan(Typeface.BOLD), start, output.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (output.length() > 0 || line.isEmpty()) output.append('\n');
        }
        while (output.length() > 0 && output.charAt(output.length() - 1) == '\n')
            output.delete(output.length() - 1, output.length());
        return output;
    }

    private void appendInlineMarkdown(SpannableStringBuilder output, String line) {
        int index = 0;
        while (index < line.length()) {
            String marker = line.startsWith("**", index) ? "**" :
                    line.startsWith("__", index) ? "__" :
                            line.startsWith("`", index) ? "`" : "";
            if (!marker.isEmpty()) {
                int close = line.indexOf(marker, index + marker.length());
                if (close > index + marker.length()) {
                    int start = output.length();
                    output.append(line, index + marker.length(), close);
                    if ("`".equals(marker)) {
                        output.setSpan(new TypefaceSpan("monospace"), start, output.length(),
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        output.setSpan(new ForegroundColorSpan(UiKit.accent(this)), start, output.length(),
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        output.setSpan(new BackgroundColorSpan(UiKit.SURFACE_2), start, output.length(),
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    } else {
                        output.setSpan(new StyleSpan(Typeface.BOLD), start, output.length(),
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    index = close + marker.length();
                    continue;
                }
            }
            output.append(line.charAt(index++));
        }
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(UiKit.dp(this, 17), UiKit.dp(this, 16), UiKit.dp(this, 17), UiKit.dp(this, 16));
        card.setBackground(UiKit.outlined(UiKit.SURFACE,
                UiKit.withAlpha(UiKit.accent(this), 40), 20, this));
        card.setElevation(UiKit.dp(this, 2));
        return card;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, UiKit.dp(this, 10));
        return lp;
    }

    private ImageButton iconButton() {
        ImageButton button = new ImageButton(this);
        button.setImageResource(R.drawable.ic_back);
        button.setImageTintList(ColorStateList.valueOf(UiKit.accent(this)));
        button.setBackground(UiKit.ripple(UiKit.SURFACE_2, UiKit.accent(this), 18, this));
        button.setContentDescription("Back");
        button.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 10), UiKit.dp(this, 10), UiKit.dp(this, 10));
        UiKit.pressScale(button);
        return button;
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(UiKit.onAccent(this));
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setBackground(UiKit.ripple(UiKit.accent(this), UiKit.onAccent(this), 15, this));
        button.setMinHeight(0); button.setMinimumHeight(0); button.setStateListAnimator(null);
        UiKit.pressScale(button);
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(UiKit.TEXT);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                Color.rgb(53, 58, 72), UiKit.accent(this), 15, this));
        button.setMinHeight(0); button.setMinimumHeight(0); button.setStateListAnimator(null);
        UiKit.pressScale(button);
        return button;
    }
}
