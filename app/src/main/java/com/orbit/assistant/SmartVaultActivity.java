package com.orbit.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * Smart Vault's setup and settings: one screen, with every privacy boundary its own switch.
 *
 * <p>Before Smart Vault is on, the switches are the choices the user is about to make, and nothing
 * happens until they press "Turn on Smart Vault". Afterwards each switch acts immediately. Each
 * says in one line what leaves the phone and where it goes - nothing, Google Play services, a model
 * server once, a website, or the user's AI provider - and the longer technical detail sits under
 * Privacy details.
 *
 * <p>Since v0.8.1.0-beta.2 the page is grouped into Search, Capture, AI suggestions, Saved items
 * and Manage, and is built once. A switch, the model download and the index counts all update the
 * views already on screen. Beta 1 rebuilt the whole page on every tap, which destroyed the switch
 * mid-animation and swallowed its haptic tick.
 */
public final class SmartVaultActivity extends Activity {

    static final String TITLE = "Smart Vault";
    static final String TURN_ON = "Turn on Smart Vault";
    static final String TURN_OFF = "Turn off Smart Vault";

    static final String SECTION_SEARCH = "SEARCH";
    static final String SECTION_CAPTURE = "CAPTURE";
    static final String SECTION_AI = "AI SUGGESTIONS";
    static final String SECTION_ITEMS = "SAVED ITEMS";
    static final String SECTION_MANAGE = "MANAGE";

    static final String OCR_LABEL = "Read text in pictures";
    static final String OCR_HELP = "Reads the words in screenshots and photos on this phone, with "
            + "Google ML Kit through Google Play services. Pictures never leave the phone.";
    static final String MEANING_LABEL = "Search by meaning";
    static final String MEANING_HELP = "Finds items by what they mean. Downloads a "
            + SmartVaultModel.sizeLabel() + " model once from Hugging Face; after that, search "
            + "runs on this phone.";
    static final String LINKS_LABEL = "Read saved links";
    static final String LINKS_HELP = "Opens each saved link once to read its title and text. "
            + "This contacts that website.";
    static final String AI_LABEL = "Suggest details for new items";
    static final String AI_HELP = "Sends new items to your AI provider for a suggested title, "
            + "summary and topics. This uses your AI allowance.";

    static final String DETAILS_LABEL = "Privacy details";
    static final String DETAILS_BODY = "Search model: potion-base-8M by Minish Lab, MIT licence, "
            + SmartVaultModel.sizeLabel() + ". Downloaded once from Hugging Face and checked "
            + "against a fixed checksum. Works best in English.\n\n"
            + "Text in pictures: read on this phone. Google may collect anonymous statistics about "
            + "how ML Kit is used.\n\n"
            + "AI suggestions: only items saved after you turn this on are sent. Items you saved "
            + "before are only sent if you ask, under Saved items.\n\n"
            + "Your saved items, notes and titles are never changed without you. Indexing runs in "
            + "the background and pauses while the battery is low.";

    /** Model states, as the row under Search by meaning words them. */
    static final String MODEL_READY = "Search model ready on this phone";
    static final String MODEL_PREPARING = "Preparing download…";
    static final String MODEL_DOWNLOADING = "Downloading search model";
    static final String MODEL_WAITING = "Waiting to continue";
    static final String MODEL_FAILED = "Download didn't finish";
    static final String MODEL_MISSING = "Search model not downloaded";
    static final String ACTION_RETRY = "Retry";
    static final String ACTION_DOWNLOAD = "Download";
    static final String ACTION_REMOVE = "Remove";

    private LinearLayout page;
    private boolean choiceOcr = true;
    private boolean choiceMeaning = true;
    private boolean choiceLinks = false;
    private boolean choiceAi = false;
    private String appearanceSignature = "";
    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::tick;
    private OrbitPredictiveBack navigation;
    private ScrollView scroll;

    /** Views that update in place, present only while Smart Vault is on. */
    private OrbitSwitch meaningSwitch;
    private ModelRow modelRow;
    private LinearLayout itemsBody;
    private String itemsSignature = "";
    private boolean detailsOpen;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.syncTheme(this);
        appearanceSignature = UiKit.appearanceSignature(this);
        Window window = getWindow();
        window.setStatusBarColor(UiKit.BG);
        window.setNavigationBarColor(UiKit.BG);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        OrbitBackground.applyPage(scroll);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int side = UiKit.dp(this, 18);
        page.setPadding(side, UiKit.dp(this, 10), side, UiKit.dp(this, 40));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        UiKit.applyActivityInsets(this, scroll, true);
        navigation = OrbitPredictiveBack.install(this);
        rebuild();
    }

    @Override protected void onResume() {
        super.onResume();
        UiPresence.enter(this);
        if (!UiKit.appearanceSignature(this).equals(appearanceSignature)) {
            recreate();
            return;
        }
        if (Prefs.smartVaultEnabled(this) && Prefs.smartVaultMeaning(this)) {
            SmartVaultModel.ensureScheduled(this);
        }
        // Rebuilt at the same scroll position: coming back from the Vault or Settings may have
        // changed what the page shows, but it should not move under the user.
        int y = scroll == null ? 0 : scroll.getScrollY();
        rebuild();
        if (scroll != null && y > 0) scroll.post(() -> scroll.scrollTo(0, y));
        ticker.removeCallbacks(tick);
        ticker.postDelayed(tick, 1000);
    }

    @Override protected void onPause() {
        ticker.removeCallbacks(tick);
        UiPresence.leave(this);
        super.onPause();
    }

    /**
     * Keeps the model row and the index counts current while the screen is visible. Only those two
     * change, in place, so nothing else on the page moves or is rebuilt.
     */
    private void tick() {
        if (isFinishing() || isDestroyed()) return;
        refreshLive();
        SmartVaultModel.Phase phase = SmartVaultModel.phase(this);
        boolean busy = phase == SmartVaultModel.Phase.DOWNLOADING
                || phase == SmartVaultModel.Phase.PREPARING;
        ticker.postDelayed(tick, busy ? 700 : 3000);
    }

    /** Brings the model row and the Saved items card up to date without rebuilding either card. */
    void refreshLive() {
        if (!Prefs.smartVaultEnabled(this)) return;
        if (modelRow != null) modelRow.bind();
        if (itemsBody != null && !itemsSignature().equals(itemsSignature)) fillItems();
    }

    private String itemsSignature() {
        StringBuilder s = new StringBuilder();
        s.append(OrbitVaultStore.count(this)).append('|').append(SmartVaultModel.isReady(this))
                .append('|').append(Prefs.smartVaultMeaning(this))
                .append(Prefs.smartVaultOcr(this)).append(Prefs.smartVaultReadLinks(this));
        if (!SmartVault.databaseExists(this)) return s.toString();
        try {
            SmartVaultDb db = SmartVaultDb.get(this);
            s.append('|').append(db.vectorCount()).append('|').append(db.queuedCount())
                    .append('|').append(db.derivedCount(SmartVaultDb.KIND_OCR))
                    .append('|').append(db.derivedCount(SmartVaultDb.KIND_PAGE))
                    .append('|').append(SmartVault.itemsWithoutSuggestions(this).size());
        } catch (Exception ignored) {
        }
        return s.toString();
    }

    private void rebuild() {
        if (page == null) return;
        page.removeAllViews();
        meaningSwitch = null;
        modelRow = null;
        itemsBody = null;
        boolean on = Prefs.smartVaultEnabled(this);
        page.addView(header(on));

        if (!Prefs.vaultEnabled(this)) {
            LinearLayout off = card();
            off.addView(UiKit.text(this, "Orbit Vault is turned off", 15, UiKit.TEXT, true));
            off.addView(muted("Turn Orbit Vault on in Settings to use Smart Vault."));
            page.addView(off, cardLp());
            return;
        }

        if (!on) {
            LinearLayout intro = card();
            intro.addView(UiKit.text(this, "Find what you saved, faster", 15, UiKit.TEXT, true));
            intro.addView(muted("Smart Vault indexes your Vault on this phone: words in "
                    + "screenshots, similar meanings and topics. Choose what it may do below. "
                    + "Nothing you saved is changed without you."));
            page.addView(intro, cardLp());
        }

        page.addView(sectionLabel(SECTION_SEARCH, !on));
        LinearLayout search = card();
        meaningSwitch = new OrbitSwitch(this);
        search.addView(option(meaningSwitch, MEANING_LABEL, MEANING_HELP,
                on ? Prefs.smartVaultMeaning(this) : choiceMeaning, Prefs.SMART_VAULT_MEANING,
                v -> choiceMeaning = v));
        if (on) {
            modelRow = new ModelRow();
            search.addView(modelRow.root);
            modelRow.bind();
        }
        page.addView(search, cardLp());

        page.addView(sectionLabel(SECTION_CAPTURE, true));
        LinearLayout capture = card();
        capture.addView(option(new OrbitSwitch(this), OCR_LABEL, OCR_HELP,
                on ? Prefs.smartVaultOcr(this) : choiceOcr, Prefs.SMART_VAULT_OCR,
                v -> choiceOcr = v));
        capture.addView(option(new OrbitSwitch(this), LINKS_LABEL, LINKS_HELP,
                on ? Prefs.smartVaultReadLinks(this) : choiceLinks, Prefs.SMART_VAULT_READ_LINKS,
                v -> choiceLinks = v));
        page.addView(capture, cardLp());

        page.addView(sectionLabel(SECTION_AI, true));
        LinearLayout ai = card();
        ai.addView(option(new OrbitSwitch(this), AI_LABEL, AI_HELP,
                on ? Prefs.smartVaultAiForNewItems(this) : choiceAi, Prefs.SMART_VAULT_AI_NEW,
                v -> choiceAi = v));
        ai.addView(providerLine());
        page.addView(ai, cardLp());

        if (!on) {
            page.addView(details());
            Button turnOn = UiKit.button(this, TURN_ON, true);
            turnOn.setOnClickListener(v -> {
                SmartVault.enable(this, choiceOcr, choiceMeaning, choiceLinks, choiceAi);
                Toast.makeText(this, "Smart Vault is on. Indexing happens in the background.",
                        Toast.LENGTH_LONG).show();
                rebuild();
                scroll.post(() -> scroll.scrollTo(0, 0));
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 50));
            lp.setMargins(0, UiKit.dp(this, 16), 0, 0);
            page.addView(turnOn, lp);
            return;
        }

        page.addView(sectionLabel(SECTION_ITEMS, true));
        LinearLayout items = card();
        itemsBody = new LinearLayout(this);
        itemsBody.setOrientation(LinearLayout.VERTICAL);
        items.addView(itemsBody);
        fillItems();
        page.addView(items, cardLp());

        page.addView(sectionLabel(SECTION_MANAGE, true));
        LinearLayout manage = card();
        Button off = UiKit.button(this, TURN_OFF, false);
        off.setOnClickListener(v -> {
            SmartVault.disable(this);
            Toast.makeText(this, "Smart Vault is off. Your Vault is unchanged.",
                    Toast.LENGTH_SHORT).show();
            rebuild();
        });
        manage.addView(off, buttonLp(0));
        Button delete = UiKit.button(this, "Delete Smart Vault data", false);
        delete.setTextColor(UiKit.DANGER);
        delete.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.DANGER, 90), UiKit.DANGER, 15, this));
        delete.setOnClickListener(v -> confirmDeleteData());
        manage.addView(delete, buttonLp(9));
        manage.addView(muted("Deleting removes recognised text, page text, the index and "
                + "Orbit's suggestions. Your items, notes, own titles and kept topics stay."));
        page.addView(manage, cardLp());

        page.addView(details());
    }

    private View providerLine() {
        String blocker = SmartVault.suggestionBlocker(this);
        String text = blocker.isEmpty()
                ? "Provider: " + SmartVault.providerName(this)
                : blocker + (blocker.endsWith(".") ? "" : ".");
        TextView line = UiKit.text(this, text, 12, UiKit.MUTED, false);
        line.setPadding(UiKit.dp(this, 2), UiKit.dp(this, 2), UiKit.dp(this, 2), UiKit.dp(this, 4));
        return line;
    }

    interface Choice {
        void set(boolean value);
    }

    /**
     * One Smart Vault switch, as an ordinary Orbit switch row.
     *
     * <p>The listener records the choice and updates only what depends on it. It never rebuilds
     * the page, so the control that was tapped stays on screen: its thumb slides with Orbit's
     * standard motion (or jumps, with animations off), and {@link OrbitSwitch#toggle()} gives the
     * one light tick every Settings switch gives, from a view that is still attached.
     */
    private View option(OrbitSwitch control, String label, String help, boolean checked,
                        String key, Choice choice) {
        control.setChecked(checked, false);
        control.setOnCheckedChangeListener((button, value) -> {
            if (!Prefs.smartVaultEnabled(this)) {
                choice.set(value);
                return;
            }
            SmartVault.setOption(this, key, value);
            if (Prefs.SMART_VAULT_MEANING.equals(key) && modelRow != null) modelRow.bind();
            refreshLive();
        });
        return UiKit.switchRow(this, label, help, control);
    }

    /**
     * The search model's state under Search by meaning: a small accent ring with its percentage
     * while downloading, and a single restrained line once ready.
     */
    final class ModelRow {
        final LinearLayout root = new LinearLayout(SmartVaultActivity.this);
        final OrbitProgressRing ring = new OrbitProgressRing(SmartVaultActivity.this);
        final TextView title;
        final TextView detail;
        final TextView trailing;
        SmartVaultModel.Phase shown;

        ModelRow() {
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(UiKit.dp(SmartVaultActivity.this, 2), UiKit.dp(SmartVaultActivity.this, 4),
                    UiKit.dp(SmartVaultActivity.this, 2), UiKit.dp(SmartVaultActivity.this, 8));
            ring.setContentDescription("Search model download");
            LinearLayout.LayoutParams ringLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ringLp.setMargins(0, 0, UiKit.dp(SmartVaultActivity.this, 12), 0);
            root.addView(ring, ringLp);

            LinearLayout texts = new LinearLayout(SmartVaultActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            title = UiKit.text(SmartVaultActivity.this, "", 13, UiKit.TEXT, false);
            // Announced when the state changes, not on every percent.
            title.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            detail = UiKit.text(SmartVaultActivity.this, "", 12, UiKit.MUTED, false);
            detail.setPadding(0, UiKit.dp(SmartVaultActivity.this, 1), 0, 0);
            texts.addView(title);
            texts.addView(detail);
            root.addView(texts, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            trailing = UiKit.text(SmartVaultActivity.this, "", 13, UiKit.accent(SmartVaultActivity.this), true);
            int pad = UiKit.dp(SmartVaultActivity.this, 10);
            trailing.setPadding(pad, pad, UiKit.dp(SmartVaultActivity.this, 4), pad);
            trailing.setMinWidth(UiKit.dp(SmartVaultActivity.this, 44));
            trailing.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            root.addView(trailing, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        void bind() {
            boolean meaning = Prefs.smartVaultMeaning(SmartVaultActivity.this);
            root.setVisibility(meaning ? View.VISIBLE : View.GONE);
            if (!meaning) return;
            SmartVaultModel.Phase phase = SmartVaultModel.phase(SmartVaultActivity.this);
            int percent = SmartVaultModel.percent(SmartVaultActivity.this);
            String size = mb(SmartVaultModel.doneBytes(SmartVaultActivity.this)) + " of "
                    + SmartVaultModel.sizeLabel();
            int accent = UiKit.accent(SmartVaultActivity.this);
            String titleText;
            String detailText;
            String action = "";
            View.OnClickListener onAction = null;
            boolean showRing = true;
            int titleColor = UiKit.TEXT;
            int trailingColor = accent;
            switch (phase) {
                case PREPARING:
                    titleText = MODEL_PREPARING;
                    detailText = SmartVaultModel.sizeLabel() + ", once. Runs in the background.";
                    ring.setIndeterminate();
                    break;
                case DOWNLOADING:
                    titleText = MODEL_DOWNLOADING;
                    detailText = size + " · runs in the background";
                    ring.setProgress(percent);
                    action = percent + "%";
                    break;
                case WAITING:
                    titleText = MODEL_WAITING;
                    detailText = "Continues by itself when the phone is online"
                            + (percent > 0 ? " · " + size : "");
                    ring.setProgress(percent);
                    action = percent > 0 ? percent + "%" : "";
                    trailingColor = UiKit.MUTED;
                    break;
                case FAILED:
                    titleText = MODEL_FAILED;
                    detailText = SmartVaultModel.error(SmartVaultActivity.this);
                    showRing = false;
                    action = ACTION_RETRY;
                    onAction = v -> {
                        SmartVaultModel.requestDownload(SmartVaultActivity.this);
                        bind();
                    };
                    break;
                case READY:
                    titleText = "✓  " + MODEL_READY;
                    detailText = "";
                    titleColor = accent;
                    showRing = false;
                    action = ACTION_REMOVE;
                    onAction = v -> {
                        SmartVaultModel.delete(SmartVaultActivity.this);
                        SmartVault.setOption(SmartVaultActivity.this, Prefs.SMART_VAULT_MEANING, false);
                        if (meaningSwitch != null) meaningSwitch.setChecked(false);
                        bind();
                        refreshLive();
                    };
                    break;
                case MISSING:
                default:
                    titleText = MODEL_MISSING;
                    detailText = SmartVaultModel.sizeLabel() + ", once, from Hugging Face";
                    showRing = false;
                    action = ACTION_DOWNLOAD;
                    onAction = v -> {
                        SmartVaultModel.requestDownload(SmartVaultActivity.this);
                        bind();
                    };
                    break;
            }
            ring.setVisibility(showRing ? View.VISIBLE : View.GONE);
            title.setText(titleText);
            title.setTextColor(titleColor);
            detail.setText(detailText);
            detail.setVisibility(detailText.isEmpty() ? View.GONE : View.VISIBLE);
            trailing.setText(action);
            trailing.setTextColor(trailingColor);
            trailing.setVisibility(action.isEmpty() ? View.GONE : View.VISIBLE);
            trailing.setOnClickListener(onAction);
            trailing.setClickable(onAction != null);
            trailing.setBackground(onAction == null ? null
                    : UiKit.ripple(android.graphics.Color.TRANSPARENT, accent, 12, SmartVaultActivity.this));
            trailing.setContentDescription(onAction == null ? null
                    : action + (phase == SmartVaultModel.Phase.READY ? " search model"
                            : " search model download"));
            shown = phase;
        }
    }

    static String mb(long bytes) {
        return String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0);
    }

    /** Index counts, then suggestions for the items saved before Smart Vault. */
    private void fillItems() {
        if (itemsBody == null) return;
        itemsSignature = itemsSignature();
        itemsBody.removeAllViews();
        int total = OrbitVaultStore.count(this);
        itemsBody.addView(stat("Saved items", String.valueOf(total)));
        int queued = 0;
        try {
            SmartVaultDb db = SmartVaultDb.get(this);
            if (Prefs.smartVaultMeaning(this) && SmartVaultModel.isReady(this)) {
                itemsBody.addView(stat("Searchable by meaning",
                        Math.min(total, db.vectorCount()) + " of " + total));
            }
            if (Prefs.smartVaultOcr(this)) {
                itemsBody.addView(stat("Pictures with text found",
                        String.valueOf(db.derivedCount(SmartVaultDb.KIND_OCR))));
            }
            if (Prefs.smartVaultReadLinks(this)) {
                itemsBody.addView(stat("Links read",
                        String.valueOf(db.derivedCount(SmartVaultDb.KIND_PAGE))));
            }
            queued = db.queuedCount();
            if (queued > 0) {
                itemsBody.addView(stat("Waiting for suggestions", queued + " · needs a connection"));
            }
        } catch (Exception ignored) {
        }

        // Suggestions for items saved before, only when the user asks, with the exact number of
        // items that would be sent and to which provider.
        List<String> without = SmartVault.itemsWithoutSuggestions(this);
        if (without.isEmpty()) {
            itemsBody.addView(muted("Every item already has suggestions."));
        } else {
            itemsBody.addView(muted(without.size() + (without.size() == 1 ? " item has"
                    : " items have") + " no suggestions. Orbit only sends them to your AI "
                    + "provider if you ask."));
            Button suggest = UiKit.button(this, "Suggest details for these items", false);
            suggest.setOnClickListener(v -> confirmBatch(without));
            itemsBody.addView(suggest, buttonLp(10));
        }
        if (queued > 0) {
            Button stop = UiKit.button(this, "Stop " + queued + " waiting "
                    + (queued == 1 ? "suggestion" : "suggestions"), false);
            stop.setOnClickListener(v -> {
                try { SmartVaultDb.get(this).cancelQueued(); } catch (Exception ignored) { }
                fillItems();
            });
            itemsBody.addView(stop, buttonLp(9));
        }
    }

    private View stat(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, UiKit.dp(this, 3), 0, UiKit.dp(this, 3));
        row.addView(UiKit.text(this, label, 13, UiKit.MUTED, false), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(UiKit.text(this, value, 13, UiKit.TEXT, true));
        return row;
    }

    /**
     * The longer technical detail, one tap away instead of under every switch. Each switch still
     * says in its own line what leaves the phone; this holds the model's name and licence, ML Kit's
     * statistics, and what happens to items saved before.
     */
    private View details() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView toggle = UiKit.text(this, "", 13, UiKit.accent(this), true);
        int pad = UiKit.dp(this, 12);
        toggle.setPadding(UiKit.dp(this, 4), pad, UiKit.dp(this, 4), pad);
        toggle.setBackground(UiKit.ripple(android.graphics.Color.TRANSPARENT, UiKit.accent(this), 12, this));
        TextView body = muted(DETAILS_BODY);
        body.setPadding(UiKit.dp(this, 4), 0, UiKit.dp(this, 4), UiKit.dp(this, 6));
        Runnable apply = () -> {
            toggle.setText((detailsOpen ? "▾  " : "▸  ") + DETAILS_LABEL);
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                toggle.setStateDescription(detailsOpen ? "Expanded" : "Collapsed");
            }
            body.setVisibility(detailsOpen ? View.VISIBLE : View.GONE);
        };
        toggle.setOnClickListener(v -> {
            detailsOpen = !detailsOpen;
            apply.run();
        });
        apply.run();
        box.addView(toggle);
        box.addView(body);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, UiKit.dp(this, 8), 0, 0);
        box.setLayoutParams(lp);
        return box;
    }

    private void confirmBatch(List<String> ids) {
        String blocker = SmartVault.suggestionBlocker(this);
        if (!blocker.isEmpty()) {
            Toast.makeText(this, blocker, Toast.LENGTH_LONG).show();
            return;
        }
        String providerName = SmartVault.providerName(this);
        List<String> batch = ids.size() > SmartVault.MAX_BATCH
                ? ids.subList(0, SmartVault.MAX_BATCH) : ids;
        String message = "Orbit will send " + batch.size()
                + (batch.size() == 1 ? " saved item" : " saved items")
                + " to " + providerName + ", one at a time, for a suggested title, "
                + "summary and topics. This uses your AI allowance: about one small request per "
                + "item. You can stop at any time."
                + (ids.size() > batch.size() ? "\n\nThe newest " + SmartVault.MAX_BATCH
                        + " items are sent now; the rest can be sent afterwards." : "");
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Suggest details for " + batch.size()
                        + (batch.size() == 1 ? " item?" : " items?"))
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Send " + batch.size(), (d, w) -> {
                    int queued = SmartVault.queueSuggestions(this, batch);
                    Toast.makeText(this, queued + " queued. Suggestions arrive in the background.",
                            Toast.LENGTH_LONG).show();
                    fillItems();
                })
                .create();
        UiKit.styleOrbitDialog(dialog, this, false);
        dialog.show();
    }

    private void confirmDeleteData() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Delete Smart Vault data?")
                .setMessage("Removes recognised text, page text, the search index and every "
                        + "suggestion Orbit wrote. Your saved items, notes, your own titles and "
                        + "kept topics are not touched. The search model stays until you remove it.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    int cleared = SmartVault.deleteData(this);
                    Toast.makeText(this, "Smart Vault data deleted"
                            + (cleared > 0 ? " (" + cleared + " items had suggestions)" : ""),
                            Toast.LENGTH_SHORT).show();
                    fillItems();
                })
                .create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
    }

    // ---- small helpers ----------------------------------------------------------------------------

    private View header(boolean on) {
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_back);
        back.setImageTintList(ColorStateList.valueOf(UiKit.accent(this)));
        back.setBackground(UiKit.ripple(UiKit.SURFACE, UiKit.accent(this), 18, this));
        back.setContentDescription("Back");
        back.setPadding(UiKit.dp(this, 11), UiKit.dp(this, 11), UiKit.dp(this, 11), UiKit.dp(this, 11));
        back.setOnClickListener(v -> navigation.performBack());
        UiKit.pressScale(back);
        top.addView(back, new LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 48)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(UiKit.text(this, TITLE, 24, UiKit.TEXT, true));
        titles.addView(UiKit.text(this, on ? "On · indexes your Vault on this phone"
                : "Off · free, optional, private by default", 12, UiKit.MUTED, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.setMargins(UiKit.dp(this, 14), 0, 0, UiKit.dp(this, 6));
        top.addView(titles, lp);
        return top;
    }

    /** The same small spaced capitals the Vault uses to head its groups. */
    private TextView sectionLabel(String name, boolean spacedAbove) {
        TextView label = UiKit.text(this, name, 12, UiKit.MUTED, true);
        label.setLetterSpacing(0.18f);
        label.setPadding(UiKit.dp(this, 4), UiKit.dp(this, spacedAbove ? 16 : 8), 0, 0);
        label.setAccessibilityHeading(true);
        return label;
    }

    private TextView muted(String text) {
        TextView view = UiKit.text(this, text, 12, UiKit.MUTED, false);
        view.setPadding(0, UiKit.dp(this, 6), 0, 0);
        view.setLineSpacing(0, 1.12f);
        return view;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 8), UiKit.dp(this, 14), UiKit.dp(this, 10));
        c.setBackground(UiKit.outlined(UiKit.SURFACE,
                UiKit.withAlpha(UiKit.accent(this), 34), 20, this));
        return c;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, UiKit.dp(this, 8), 0, 0);
        return lp;
    }

    private LinearLayout.LayoutParams buttonLp(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 44));
        lp.setMargins(0, UiKit.dp(this, topDp), 0, 0);
        return lp;
    }
}
