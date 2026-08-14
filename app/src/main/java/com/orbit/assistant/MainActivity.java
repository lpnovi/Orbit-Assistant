package com.orbit.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/** Full Orbit companion app introduced in 0.4. */
public class MainActivity extends Activity {
    public static final String EXTRA_OPEN_CONVERSATION_ID = "open_conversation_id";
    private LinearLayout chatList;
    private LinearLayout pendingList;
    private TextView pendingHeader;
    private EditText search;
    private ScrollView chatScroller;
    private String appliedAccentName;
    private boolean rebuildingTheme;
    private boolean foregroundActive;
    private OrbitUpdater.Release pendingForegroundRelease;
    private AlertDialog foregroundUpdateDialog;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.syncTheme(this);
        boolean launchOnboarding = OnboardingState.shouldLaunchAutomatically(this);
        // AlarmManager state can be cleared by force-stop/reboot. Reconcile saved
        // routine schedules whenever the companion app is launched as an extra
        // recovery path in addition to the manifest reschedule receiver.
        RoutineTriggerScheduler.rescheduleAll(this);
        ReminderScheduler.rescheduleAll(this);
        OrbitUpdater.reconcilePendingInstall(this);
        OrbitUpdateWorker.schedule(this);
        Window w = getWindow();
        w.setStatusBarColor(UiKit.BG);
        w.setNavigationBarColor(UiKit.BG);
        View content = buildContent();
        setContentView(content);
        UiKit.applyActivityInsets(this, content, true);
        appliedAccentName = currentAccentName();
        maybeOpenConversation(getIntent());
        if (launchOnboarding) {
            getWindow().getDecorView().post(() ->
                    startActivity(OnboardingActivity.freshInstallIntent(this)));
        } else {
            getWindow().getDecorView().postDelayed(this::checkForForegroundUpdate, 900L);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        maybeOpenConversation(intent);
    }

    private void maybeOpenConversation(Intent intent) {
        if (intent == null) return;
        String id = intent.getStringExtra(EXTRA_OPEN_CONVERSATION_ID);
        if (id == null || id.trim().isEmpty()) return;
        intent.removeExtra(EXTRA_OPEN_CONVERSATION_ID);
        getWindow().getDecorView().post(() -> openChat(id));
    }

    @Override protected void onResume() {
        super.onResume();
        foregroundActive = true;
        UiPresence.enter(this);
        if (!syncThemeIfNeeded()) refresh();
        maybeShowForegroundUpdate();
    }

    @Override protected void onPause() {
        foregroundActive = false;
        UiPresence.leave(this);
        super.onPause();
    }

    private void checkForForegroundUpdate() {
        if (!Prefs.updateNotifications(this) || isFinishing() || isDestroyed()) return;
        OrbitUpdater.Release cached = OrbitUpdater.loadCachedAvailable(this);
        if (cached != null && !OrbitUpdater.wasNotified(this, cached.versionCode)) {
            pendingForegroundRelease = cached;
            maybeShowForegroundUpdate();
            return;
        }
        if (!OrbitUpdater.claimForegroundCheck(this)) return;
        OrbitUpdater.checkAsync(this, new OrbitUpdater.CheckCallback() {
            @Override public void onResult(OrbitUpdater.CheckResult result) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || !Prefs.updateNotifications(MainActivity.this)) {
                        return;
                    }
                    if (result.updateAvailable && result.release != null &&
                            !OrbitUpdater.wasNotified(MainActivity.this,
                                    result.release.versionCode)) {
                        pendingForegroundRelease = result.release;
                        maybeShowForegroundUpdate();
                    }
                });
            }

            @Override public void onError(String message) {
                // Foreground discovery is opportunistic. Manual checks retain errors.
            }
        });
    }

    private void maybeShowForegroundUpdate() {
        OrbitUpdater.Release release = pendingForegroundRelease;
        if (!foregroundActive || release == null || foregroundUpdateDialog != null ||
                !Prefs.updateNotifications(this) ||
                OrbitUpdater.wasNotified(this, release.versionCode)) return;

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Orbit update available")
                .setMessage("Orbit Assistant v" + release.versionName +
                        " is ready to view. Orbit will not download it without your approval.")
                .setNegativeButton("Later", null)
                .setPositiveButton("View update", (ignored, which) ->
                        startActivity(new Intent(this, UpdateActivity.class)))
                .create();
        foregroundUpdateDialog = dialog;
        UiKit.styleOrbitDialog(dialog, this, false);
        dialog.setOnDismissListener(ignored -> foregroundUpdateDialog = null);
        try {
            dialog.show();
            pendingForegroundRelease = null;
            OrbitUpdater.markNotified(this, release.versionCode);
        } catch (Exception ignored) {
            foregroundUpdateDialog = null;
        }
    }


    private String currentAccentName() {
        return Prefs.get(this).getString(Prefs.ACCENT, "dynamic") +
                "|amoled=" + Prefs.amoledMode(this) +
                "|font=" + Prefs.appFont(this);
    }

    /**
     * Rebuild the companion-app surface when Settings changes the accent.
     * MainActivity usually sits paused underneath Settings, so doing this here
     * makes the new theme ready before the user returns without recreating the
     * Activity, losing the search query, or jumping the chat list to the top.
     */
    private boolean syncThemeIfNeeded() {
        if (rebuildingTheme) return false;
        String desired = currentAccentName();
        if (desired.equals(appliedAccentName)) return false;
        rebuildingTheme = true;
        try {
            String query = search == null ? "" : search.getText().toString();
            int oldScrollY = chatScroller == null ? 0 : chatScroller.getScrollY();

            View content = buildContent();
            setContentView(content);
            UiKit.applyActivityInsets(this, content, true);
            appliedAccentName = desired;

            if (search != null && !query.isEmpty()) {
                search.setText(query);
                search.setSelection(search.length());
            }
            refresh();
            if (chatScroller != null) {
                chatScroller.post(() -> chatScroller.scrollTo(0, oldScrollY));
            }
            return true;
        } finally {
            rebuildingTheme = false;
        }
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.BG);
        int h = UiKit.dp(this, 18);
        root.setPadding(h, UiKit.dp(this, 10), h, 0);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        View mark = UiKit.orbitMark(this, 40);
        LinearLayout.LayoutParams markLp = new LinearLayout.LayoutParams(UiKit.dp(this, 46), UiKit.dp(this, 46));
        markLp.rightMargin = UiKit.dp(this, 8);
        top.addView(mark, markLp);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(UiKit.text(this, "Orbit", 26, UiKit.TEXT, true));
        titles.addView(UiKit.text(this, "Chats", 13, UiKit.MUTED, false));
        top.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton settings = iconButton(com.orbit.assistant.R.drawable.ic_settings, "Settings");
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        top.addView(settings, new LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 48)));
        root.addView(top);

        Button newChat = new Button(this);
        newChat.setText("+  New chat");
        newChat.setTextColor(UiKit.onAccent(this));
        newChat.setTextSize(15);
        newChat.setAllCaps(false);
        newChat.setBackground(UiKit.ripple(UiKit.accent(this), UiKit.onAccent(this), 18, this));
        newChat.setMinHeight(0); newChat.setMinimumHeight(0); newChat.setStateListAnimator(null);
        UiKit.pressScale(newChat);
        LinearLayout.LayoutParams ncLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 50));
        ncLp.setMargins(0, UiKit.dp(this, 16), 0, UiKit.dp(this, 12));
        root.addView(newChat, ncLp);
        newChat.setOnClickListener(v -> openChat(ConversationStore.newId()));

        LinearLayout searchBox = new LinearLayout(this);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setPadding(UiKit.dp(this, 12), 0, UiKit.dp(this, 12), 0);
        searchBox.setBackground(UiKit.outlined(UiKit.SURFACE, Color.rgb(47,52,66), 18, this));
        ImageButton searchIcon = new ImageButton(this);
        searchIcon.setImageResource(com.orbit.assistant.R.drawable.ic_search);
        searchIcon.setImageTintList(ColorStateList.valueOf(UiKit.MUTED));
        searchIcon.setBackgroundColor(Color.TRANSPARENT);
        searchBox.addView(searchIcon, new LinearLayout.LayoutParams(UiKit.dp(this, 34), UiKit.dp(this, 34)));
        search = new EditText(this);
        search.setHint("Search Orbit chats");
        search.setHintTextColor(Color.rgb(113,119,135));
        search.setTextColor(UiKit.TEXT);
        search.setTextSize(14);
        search.setSingleLine(true);
        search.setBackgroundColor(Color.TRANSPARENT);
        search.setPadding(UiKit.dp(this, 7), 0, 0, 0);
        searchBox.addView(search, new LinearLayout.LayoutParams(0, UiKit.dp(this, 48), 1));
        root.addView(searchBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 52)));

        // 0.6.3.9: Keep the divider on the exact chat-viewport boundary while giving
        // it a restrained Orbit treatment. The soft haze sits above the crisp bottom
        // rule, so the scrolling content still begins exactly at the visible cutoff.
        int dividerAccent = UiKit.accent(this);
        FrameLayout divider = new FrameLayout(this);
        divider.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        View dividerGlow = new View(this);
        GradientDrawable glowDrawable = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {
                        Color.TRANSPARENT,
                        UiKit.withAlpha(dividerAccent, 30),
                        UiKit.withAlpha(dividerAccent, 42),
                        UiKit.withAlpha(dividerAccent, 42),
                        UiKit.withAlpha(dividerAccent, 30),
                        Color.TRANSPARENT
                });
        glowDrawable.setCornerRadius(UiKit.dp(this, 3));
        dividerGlow.setBackground(glowDrawable);
        FrameLayout.LayoutParams glowLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 4), Gravity.BOTTOM);
        divider.addView(dividerGlow, glowLp);

        View dividerCore = new View(this);
        GradientDrawable coreDrawable = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {
                        Color.TRANSPARENT,
                        UiKit.withAlpha(dividerAccent, 120),
                        UiKit.withAlpha(dividerAccent, 170),
                        UiKit.withAlpha(dividerAccent, 170),
                        UiKit.withAlpha(dividerAccent, 120),
                        Color.TRANSPARENT
                });
        coreDrawable.setCornerRadius(UiKit.dp(this, 1));
        dividerCore.setBackground(coreDrawable);
        FrameLayout.LayoutParams coreLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 1), Gravity.BOTTOM);
        divider.addView(dividerCore, coreLp);

        // 25 dp top gap + 4 dp divider = the same 29 dp fixed height as v0.6.3.8,
        // keeping the divider's bottom edge (and therefore chat cutoff) unchanged.
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 4));
        dividerLp.setMargins(0, UiKit.dp(this, 25), 0, 0);
        root.addView(divider, dividerLp);

        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int before, int count) { refreshChats(); }
            public void afterTextChanged(Editable e) {}
        });

        chatScroller = new ScrollView(this);
        chatScroller.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 40));

        pendingHeader = sectionTitle("STILL THINKING");
        pendingList = new LinearLayout(this);
        pendingList.setOrientation(LinearLayout.VERTICAL);
        content.addView(pendingHeader);
        content.addView(pendingList);

        content.addView(sectionTitle("RECENT CHATS"));
        chatList = new LinearLayout(this);
        chatList.setOrientation(LinearLayout.VERTICAL);
        content.addView(chatList);

        chatScroller.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(chatScroller, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return root;
    }

    private void refresh() {
        refreshPending();
        refreshChats();
    }

    private void refreshPending() {
        if (pendingList == null) return;
        pendingList.removeAllViews();
        List<PendingRequestStore.Item> active = PendingRequestStore.active(this);
        boolean visible = !active.isEmpty();
        pendingHeader.setVisibility(visible ? View.VISIBLE : View.GONE);
        pendingList.setVisibility(visible ? View.VISIBLE : View.GONE);
        for (PendingRequestStore.Item item : active) {
            ConversationStore.Conversation chat = ConversationStore.load(this, item.conversationId);
            String title = chat == null ? compact(item.prompt, 44) : chat.title;
            LinearLayout card = card();
            TextView t = UiKit.text(this, title, 15, UiKit.TEXT, true);
            card.addView(t);
            TextView state = UiKit.text(this,
                    PendingRequestStore.RUNNING.equals(item.status) ? "Orbit is thinking..." : "Queued...",
                    12, UiKit.accent(this), false);
            state.setPadding(0, UiKit.dp(this, 5), 0, 0);
            card.addView(state);
            card.setOnClickListener(v -> openChat(item.conversationId));
            UiKit.pressScale(card);
            pendingList.addView(card, cardLp());
        }
    }

    private void refreshChats() {
        if (chatList == null) return;
        chatList.removeAllViews();
        String q = search == null ? "" : search.getText().toString();
        List<ConversationStore.Conversation> chats = ConversationStore.search(this, q);
        if (chats.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(UiKit.text(this, q.trim().isEmpty() ? "No chats yet" : "No matching chats", 16, UiKit.TEXT, true));
            TextView note = UiKit.text(this, q.trim().isEmpty() ? "Start a chat here or summon Orbit with the Side button." : "Try a different search.", 13, UiKit.MUTED, false);
            note.setPadding(0, UiKit.dp(this, 6), 0, 0);
            empty.addView(note);
            chatList.addView(empty, cardLp());
            return;
        }
        for (ConversationStore.Conversation chat : chats) addChatRow(chat);
    }

    private void addChatRow(ConversationStore.Conversation chat) {
        LinearLayout row = card();
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView title = UiKit.text(this, chat.title, 15, UiKit.TEXT, true);
        title.setMaxLines(1);
        text.addView(title);
        String preview = preview(chat);
        TextView p = UiKit.text(this, preview, 12, UiKit.MUTED, false);
        p.setMaxLines(2);
        p.setPadding(0, UiKit.dp(this, 5), 0, UiKit.dp(this, 4));
        text.addView(p);
        String when = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(chat.updatedAt));
        boolean active = PendingRequestStore.hasActiveForConversation(this, chat.id);
        PendingRequestStore.Item failed = PendingRequestStore.latestFailedForConversation(this, chat.id);
        boolean unresolvedFailure = failed != null && !chat.messages.isEmpty() &&
                chat.messages.get(chat.messages.size() - 1).content != null &&
                chat.messages.get(chat.messages.size() - 1).content.startsWith("Orbit could not finish");
        String suffix = active ? "  •  Thinking" : unresolvedFailure ? "  •  Needs attention" : "";
        int dateColor = active ? UiKit.accent(this) : unresolvedFailure ? Color.rgb(239,145,153) : UiKit.MUTED;
        TextView date = UiKit.text(this, when + suffix, 11, dateColor, false);
        text.addView(date);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        ImageButton more = iconButton(com.orbit.assistant.R.drawable.ic_more, "Chat options");
        more.setOnClickListener(v -> showChatMenu(more, chat));
        row.addView(more, new LinearLayout.LayoutParams(UiKit.dp(this, 44), UiKit.dp(this, 44)));
        row.setOnClickListener(v -> openChat(chat.id));
        UiKit.pressScale(row);
        chatList.addView(row, cardLp());
    }

    private void showChatMenu(View anchor, ConversationStore.Conversation chat) {
        String[] actions = {"Rename", "Delete"};
        UiKit.showOrbitMenu(this, anchor, actions, -1, (index, title) -> {
            if (index == 0) {
                EditText input = new EditText(this);
                input.setText(chat.title);
                input.setSelectAllOnFocus(true);
                input.setTextColor(UiKit.TEXT);
                input.setHintTextColor(UiKit.MUTED);
                input.setBackgroundTintList(ColorStateList.valueOf(UiKit.accent(this)));
                AlertDialog dialog = new AlertDialog.Builder(this)
                        .setTitle("Rename chat").setView(input)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Save", (d,w) -> {
                            ConversationStore.rename(this, chat.id,
                                    input.getText().toString());
                            refreshChats();
                        }).create();
                styleOrbitDialog(dialog);
                dialog.show();
            } else {
                AlertDialog dialog = new AlertDialog.Builder(this)
                        .setTitle("Delete chat?")
                        .setMessage("This removes the local Orbit conversation.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete", (d,w) -> {
                            ConversationStore.delete(this, chat.id);
                            refresh();
                        }).create();
                styleOrbitDialog(dialog);
                dialog.show();
            }
        });
    }

    private void styleOrbitDialog(AlertDialog dialog) {
        UiKit.styleOrbitDialog(dialog, this, false);
    }

    private void openChat(String id) {
        startActivity(new Intent(this, ChatActivity.class).putExtra(ChatActivity.EXTRA_CONVERSATION_ID, id));
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(UiKit.dp(this, 17), UiKit.dp(this, 15), UiKit.dp(this, 14), UiKit.dp(this, 15));
        c.setBackground(UiKit.outlined(UiKit.SURFACE, UiKit.withAlpha(UiKit.accent(this), 34), 20, this));
        return c;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, UiKit.dp(this, 10));
        return lp;
    }

    private TextView sectionTitle(String text) {
        TextView t = UiKit.text(this, text, 12, UiKit.MUTED, true);
        t.setLetterSpacing(0.18f);
        t.setPadding(UiKit.dp(this, 6), UiKit.dp(this, 12), 0, UiKit.dp(this, 10));
        return t;
    }

    private ImageButton iconButton(int res, String description) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setImageTintList(ColorStateList.valueOf(UiKit.accent(this)));
        b.setBackground(UiKit.ripple(UiKit.SURFACE, UiKit.accent(this), 18, this));
        b.setContentDescription(description);
        b.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 12), UiKit.dp(this, 12), UiKit.dp(this, 12));
        UiKit.pressScale(b);
        return b;
    }

    private String preview(ConversationStore.Conversation chat) {
        if (chat.messages.isEmpty()) return "Empty chat";
        AssistantClient.History h = chat.messages.get(chat.messages.size()-1);
        return compact(h.content, 100) + (h.screenAttached ? "  •  Screen attached" : "");
    }

    private String compact(String text, int max) {
        String s = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        return s.length() <= max ? s : s.substring(0, Math.max(0, max-1)).trim() + "…";
    }
}
