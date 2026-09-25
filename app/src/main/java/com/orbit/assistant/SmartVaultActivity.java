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

/**
 * Smart Vault's setup and settings: one screen, with every privacy boundary its own switch.
 *
 * <p>Before Smart Vault is on, the switches below the main one are the choices the user is about
 * to make, and nothing happens until they press "Turn on Smart Vault". Afterwards each switch acts
 * immediately. Each describes, in plain words, what leaves the phone and where it goes - nothing,
 * Google Play services, a model server once, a website, or the user's AI provider.
 */
public final class SmartVaultActivity extends Activity {

    static final String TITLE = "Smart Vault";
    static final String TURN_ON = "Turn on Smart Vault";
    static final String TURN_OFF = "Turn off Smart Vault";

    static final String OCR_LABEL = "Read text in pictures";
    static final String OCR_HELP = "Finds the words in screenshots and photos you save, on this "
            + "phone, using Google ML Kit through Google Play services. Your pictures never leave "
            + "the phone. Google may collect anonymous statistics about how ML Kit is used.";
    static final String MEANING_LABEL = "Search by meaning";
    static final String MEANING_HELP = "Downloads a " + SmartVaultModel.sizeLabel() + " search "
            + "model once (potion-base-8M by Minish Lab, MIT licence) from Hugging Face. After "
            + "that, meaning search runs entirely on this phone. Works best in English.";
    static final String LINKS_LABEL = "Read saved links";
    static final String LINKS_HELP = "Opens the web page behind each link you save, once, to "
            + "read its title and text so you can search them. This contacts that website.";
    static final String AI_LABEL = "Suggest details for new items";
    static final String AI_HELP = "Sends each item you save from now on to your AI provider for a "
            + "suggested title, summary and topics. This uses your AI allowance. Items you saved "
            + "before are only sent if you ask.";

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
    private String lastSignature = "";

    private String statusSignature() {
        StringBuilder s = new StringBuilder();
        s.append(SmartVaultModel.state(this)).append(SmartVaultModel.percent(this));
        if (!SmartVault.databaseExists(this)) return s.toString();
        try {
            SmartVaultDb db = SmartVaultDb.get(this);
            s.append('|').append(db.vectorCount()).append('|').append(db.queuedCount())
                    .append('|').append(db.derivedCount(SmartVaultDb.KIND_OCR))
                    .append('|').append(db.derivedCount(SmartVaultDb.KIND_PAGE));
        } catch (Exception ignored) {
        }
        return s.toString();
    }

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
        rebuild();
        ticker.postDelayed(tick, 1500);
    }

    @Override protected void onPause() {
        ticker.removeCallbacks(tick);
        UiPresence.leave(this);
        super.onPause();
    }

    /** Keeps the model progress and index counts current while the screen is visible. */
    private void tick() {
        if (isFinishing() || isDestroyed()) return;
        // Redrawn only when something the page shows has changed, and at the same scroll position,
        // so a progress update never moves the page under the user's finger.
        if (Prefs.smartVaultEnabled(this) && !statusSignature().equals(lastSignature)) {
            int y = scroll == null ? 0 : scroll.getScrollY();
            rebuild();
            if (scroll != null) scroll.post(() -> scroll.scrollTo(0, y));
        }
        ticker.postDelayed(tick, SmartVaultModel.state(this) == SmartVaultModel.State.DOWNLOADING
                ? 1000 : 4000);
    }

    private void rebuild() {
        if (page == null) return;
        lastSignature = statusSignature();
        page.removeAllViews();
        boolean on = Prefs.smartVaultEnabled(this);
        page.addView(header(on));

        if (!Prefs.vaultEnabled(this)) {
            LinearLayout off = card();
            off.addView(UiKit.text(this, "Orbit Vault is turned off", 15, UiKit.TEXT, true));
            off.addView(muted("Turn Orbit Vault on in Settings to use Smart Vault."));
            page.addView(off, cardLp());
            return;
        }

        LinearLayout intro = card();
        intro.addView(UiKit.text(this, on ? "Smart Vault is on" : "Find what you saved, faster",
                15, UiKit.TEXT, true));
        intro.addView(muted(on
                ? "Search ranks your Vault by relevance, items show related saves, and you can "
                        + "ask questions about what you saved from the Vault's search."
                : "Smart Vault indexes your Vault on this phone so search finds more: words in "
                        + "screenshots, similar meanings, and topics. Choose what it may do below. "
                        + "Your saved items, notes and titles are never changed without you."));
        page.addView(intro, cardLp());

        LinearLayout options = card();
        options.addView(option(OCR_LABEL, OCR_HELP, on ? Prefs.smartVaultOcr(this) : choiceOcr,
                Prefs.SMART_VAULT_OCR, v -> choiceOcr = v));
        options.addView(option(MEANING_LABEL, MEANING_HELP,
                on ? Prefs.smartVaultMeaning(this) : choiceMeaning, Prefs.SMART_VAULT_MEANING,
                v -> choiceMeaning = v));
        if (on && Prefs.smartVaultMeaning(this)) options.addView(modelStatus());
        options.addView(option(LINKS_LABEL, LINKS_HELP,
                on ? Prefs.smartVaultReadLinks(this) : choiceLinks, Prefs.SMART_VAULT_READ_LINKS,
                v -> choiceLinks = v));
        options.addView(option(AI_LABEL, AI_HELP + providerNote(),
                on ? Prefs.smartVaultAiForNewItems(this) : choiceAi, Prefs.SMART_VAULT_AI_NEW,
                v -> choiceAi = v));
        page.addView(options, cardLp());

        if (!on) {
            Button turnOn = UiKit.button(this, TURN_ON, true);
            turnOn.setOnClickListener(v -> {
                SmartVault.enable(this, choiceOcr, choiceMeaning, choiceLinks, choiceAi);
                Toast.makeText(this, "Smart Vault is on. Indexing happens in the background.",
                        Toast.LENGTH_LONG).show();
                rebuild();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 50));
            lp.setMargins(0, UiKit.dp(this, 16), 0, 0);
            page.addView(turnOn, lp);
            return;
        }

        page.addView(statusCard(), cardLp());
        page.addView(existingItemsCard(), cardLp());

        LinearLayout manage = card();
        manage.addView(UiKit.text(this, "Manage", 15, UiKit.TEXT, true));
        Button off = UiKit.button(this, TURN_OFF, false);
        off.setOnClickListener(v -> {
            SmartVault.disable(this);
            Toast.makeText(this, "Smart Vault is off. Your Vault is unchanged.",
                    Toast.LENGTH_SHORT).show();
            rebuild();
        });
        manage.addView(off, buttonLp(10));
        Button delete = UiKit.button(this, "Delete Smart Vault data", false);
        delete.setTextColor(UiKit.DANGER);
        delete.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.DANGER, 90), UiKit.DANGER, 15, this));
        delete.setOnClickListener(v -> confirmDeleteData());
        manage.addView(delete, buttonLp(9));
        manage.addView(muted("Deleting Smart Vault data removes recognised text, page text, the "
                + "search index and every suggestion Orbit wrote. Your saved items, notes, your own "
                + "titles and the topics you kept stay exactly as they are."));
        page.addView(manage, cardLp());
    }

    private String providerNote() {
        String blocker = SmartVault.suggestionBlocker(this);
        if (blocker.isEmpty()) return " Currently: " + SmartVault.providerName(this) + ".";
        return " " + blocker + (blocker.endsWith(".") ? "" : ".");
    }

    interface Choice {
        void set(boolean value);
    }

    private View option(String label, String help, boolean checked, String key, Choice choice) {
        OrbitSwitch control = new OrbitSwitch(this);
        control.setChecked(checked, false);
        control.setOnCheckedChangeListener((button, value) -> {
            if (Prefs.smartVaultEnabled(this)) {
                SmartVault.setOption(this, key, value);
                if (Prefs.SMART_VAULT_MEANING.equals(key) && value) {
                    Toast.makeText(this, "Downloading the search model ("
                            + SmartVaultModel.sizeLabel() + ")", Toast.LENGTH_SHORT).show();
                }
                rebuild();
            } else {
                choice.set(value);
            }
        });
        return UiKit.switchRow(this, label, help, control);
    }

    private View modelStatus() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(this, 2), 0, UiKit.dp(this, 2), UiKit.dp(this, 8));
        SmartVaultModel.State state = SmartVaultModel.state(this);
        String text;
        switch (state) {
            case READY: text = "Search model ready on this phone."; break;
            case DOWNLOADING: text = "Downloading search model… " + SmartVaultModel.percent(this)
                    + "%. Waits for a connection if you are offline."; break;
            case FAILED: text = "Download failed: " + SmartVaultModel.error(this); break;
            default: text = "Search model not downloaded."; break;
        }
        TextView status = UiKit.text(this, text, 12,
                state == SmartVaultModel.State.READY ? UiKit.accent(this) : UiKit.MUTED, false);
        box.addView(status);
        if (state == SmartVaultModel.State.FAILED || state == SmartVaultModel.State.MISSING) {
            Button retry = UiKit.button(this, "Download model", false);
            retry.setOnClickListener(v -> {
                SmartVaultModel.requestDownload(this);
                rebuild();
            });
            box.addView(retry, buttonLp(8));
        } else if (state == SmartVaultModel.State.READY) {
            TextView remove = UiKit.text(this, "Remove model", 12, UiKit.accent(this), true);
            remove.setPadding(0, UiKit.dp(this, 6), 0, 0);
            remove.setOnClickListener(v -> {
                SmartVaultModel.delete(this);
                SmartVault.setOption(this, Prefs.SMART_VAULT_MEANING, false);
                rebuild();
            });
            box.addView(remove);
        }
        return box;
    }

    private View statusCard() {
        LinearLayout card = card();
        card.addView(UiKit.text(this, "Index", 15, UiKit.TEXT, true));
        int total = OrbitVaultStore.count(this);
        StringBuilder lines = new StringBuilder();
        lines.append(total).append(total == 1 ? " saved item" : " saved items").append('.');
        try {
            SmartVaultDb db = SmartVaultDb.get(this);
            if (Prefs.smartVaultMeaning(this) && SmartVaultModel.isReady(this)) {
                lines.append("\nSearchable by meaning: ").append(Math.min(total, db.vectorCount()))
                        .append(" of ").append(total).append('.');
            }
            if (Prefs.smartVaultOcr(this)) {
                lines.append("\nPictures with text found: ")
                        .append(db.derivedCount(SmartVaultDb.KIND_OCR)).append('.');
            }
            if (Prefs.smartVaultReadLinks(this)) {
                lines.append("\nLinks read: ").append(db.derivedCount(SmartVaultDb.KIND_PAGE))
                        .append('.');
            }
            int queued = db.queuedCount();
            if (queued > 0) {
                lines.append("\nWaiting for suggestions: ").append(queued)
                        .append(". These need a connection.");
            }
        } catch (Exception ignored) {
        }
        lines.append("\nIndexing runs in the background and pauses while the battery is low.");
        card.addView(muted(lines.toString()));
        return card;
    }

    /**
     * Suggestions for items saved before, and only when the user asks, with the exact number of
     * items that would be sent and to which provider.
     */
    private View existingItemsCard() {
        LinearLayout card = card();
        card.addView(UiKit.text(this, "Items you already saved", 15, UiKit.TEXT, true));
        List<String> without = SmartVault.itemsWithoutSuggestions(this);
        int queued = 0;
        try { queued = SmartVaultDb.get(this).queuedCount(); } catch (Exception ignored) { }
        if (without.isEmpty()) {
            card.addView(muted("Every item already has suggestions."));
        } else {
            card.addView(muted(without.size() + (without.size() == 1 ? " item has" : " items have")
                    + " no suggestions. Orbit only sends them to your AI provider if you ask."));
            Button suggest = UiKit.button(this, "Suggest details for these items", false);
            suggest.setOnClickListener(v -> confirmBatch(without));
            card.addView(suggest, buttonLp(10));
        }
        if (queued > 0) {
            Button stop = UiKit.button(this, "Stop " + queued + " waiting "
                    + (queued == 1 ? "suggestion" : "suggestions"), false);
            stop.setOnClickListener(v -> {
                try { SmartVaultDb.get(this).cancelQueued(); } catch (Exception ignored) { }
                rebuild();
            });
            card.addView(stop, buttonLp(9));
        }
        return card;
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
                    rebuild();
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
                    rebuild();
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
        titles.addView(UiKit.text(this, on ? "On" : "Off · free, optional, private by default",
                12, UiKit.MUTED, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.setMargins(UiKit.dp(this, 14), 0, 0, UiKit.dp(this, 6));
        top.addView(titles, lp);
        return top;
    }

    private TextView muted(String text) {
        TextView view = UiKit.text(this, text, 13, UiKit.MUTED, false);
        view.setPadding(0, UiKit.dp(this, 6), 0, 0);
        view.setLineSpacing(0, 1.12f);
        return view;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 14), UiKit.dp(this, 16), UiKit.dp(this, 14));
        c.setBackground(UiKit.outlined(UiKit.SURFACE,
                UiKit.withAlpha(UiKit.accent(this), 34), 20, this));
        return c;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, UiKit.dp(this, 12), 0, 0);
        return lp;
    }

    private LinearLayout.LayoutParams buttonLp(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 44));
        lp.setMargins(0, UiKit.dp(this, topDp), 0, 0);
        return lp;
    }
}
