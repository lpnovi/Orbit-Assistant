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
import java.util.ArrayList;
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
    private boolean postUpdatePromptShown;
    private OrbitUpdater.Release pendingForegroundRelease;
    private AlertDialog foregroundUpdateDialog;

    /** How long a deleted chat can be taken back before the deletion is actually carried out. */
    private static final long UNDO_WINDOW_MS = 5200L;
    private LinearLayout undoBar;
    private TextView undoLabel;
    /**
     * The chat that is being deleted but has not been yet.
     *
     * <p>Held by id rather than by a copy of the conversation, because the conversation has not
     * gone anywhere: it is simply hidden from the list while the offer to keep it stands.
     */
    private String pendingDeletionId;
    private final Runnable undoTimeout = this::commitPendingDeletion;

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
        // Leaving Chats ends the offer. A deletion the user walked away from is a deletion they
        // meant, and leaving it pending would make it depend on this process staying alive.
        commitPendingDeletion();
        OrbitSwipeRow.resetActive();
        UiPresence.leave(this);
        super.onPause();
    }

    /**
     * Acknowledges a completed Orbit update once, before any newer-release prompt, so the two can
     * never stack. Returns true when it took over this launch's startup prompt.
     */
    private boolean maybeShowPostUpdatePrompt() {
        if (postUpdatePromptShown || isFinishing() || isDestroyed()) return false;
        String version = OrbitUpdater.pendingPostUpdateVersion(this);
        if (version.isEmpty()) return false;
        // Marked immediately, so a recreation or a background/foreground cycle cannot show it
        // twice, and consumed from storage so it never returns for this version.
        postUpdatePromptShown = true;
        OrbitUpdater.clearPostUpdateVersion(this);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Orbit updated")
                .setMessage("You're now on Orbit v" + version + ".")
                .setNegativeButton("Done", null)
                .setPositiveButton("What's New", (d, w) ->
                        startActivity(new Intent(this, WhatsNewActivity.class)))
                .create();
        UiKit.styleOrbitDialog(dialog, this, false);
        dialog.show();
        return true;
    }

    private void checkForForegroundUpdate() {
        // A completed update is acknowledged first; the newer-release check resumes next launch.
        if (maybeShowPostUpdatePrompt()) return;
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

        // The Pinned and Recent headings are written by refreshChats now, because whether there is
        // a Pinned section at all depends on the chats themselves. An empty heading is worse than
        // no heading, so neither is part of the fixed layout any more.
        chatList = new LinearLayout(this);
        chatList.setOrientation(LinearLayout.VERTICAL);
        content.addView(chatList);

        chatScroller.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(chatScroller, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(buildUndoBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    /**
     * The short window in which a deleted chat can be brought back.
     *
     * <p>Deliberately not a toast. A toast is the system's voice, it lands wherever the system puts
     * it, and it cannot be operated by anyone who is not looking at it; this is Orbit's own bar,
     * anchored under the list where the chat just left from, and its Undo is a real focusable
     * control. It occupies no space at all until there is something to undo.
     */
    private View buildUndoBar() {
        undoBar = new LinearLayout(this);
        undoBar.setOrientation(LinearLayout.HORIZONTAL);
        undoBar.setGravity(Gravity.CENTER_VERTICAL);
        undoBar.setVisibility(View.GONE);
        undoBar.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 12), UiKit.dp(this, 10), UiKit.dp(this, 12));
        undoBar.setBackground(UiKit.outlined(UiKit.SURFACE_2, UiKit.withAlpha(UiKit.accent(this), 46), 18, this));

        undoLabel = UiKit.text(this, "Chat deleted", 14, UiKit.TEXT, false);
        undoBar.addView(undoLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button undo = new Button(this);
        undo.setText("Undo");
        undo.setAllCaps(false);
        undo.setTextSize(14);
        undo.setTextColor(UiKit.accent(this));
        undo.setMinHeight(0);
        undo.setMinimumHeight(0);
        undo.setStateListAnimator(null);
        undo.setBackground(UiKit.ripple(UiKit.SURFACE_3, UiKit.accent(this), 14, this));
        undo.setContentDescription("Undo deleting this chat");
        undo.setOnClickListener(v -> undoPendingDeletion());
        UiKit.pressScale(undo);
        LinearLayout.LayoutParams undoLp = new LinearLayout.LayoutParams(
                UiKit.dp(this, 88), UiKit.dp(this, 40));
        undoLp.leftMargin = UiKit.dp(this, 10);
        undoBar.addView(undo, undoLp);

        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.setMargins(0, 0, 0, UiKit.dp(this, 12));
        undoBar.setLayoutParams(barLp);
        return undoBar;
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
        List<ConversationStore.Conversation> chats = new ArrayList<>(ConversationStore.search(this, q));
        // A chat waiting to be deleted is out of the list but still in storage, which is what makes
        // Undo complete rather than a reconstruction.
        if (pendingDeletionId != null) chats.removeIf(item -> pendingDeletionId.equals(item.id));
        if (chats.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(UiKit.text(this, q.trim().isEmpty() ? "No chats yet" : "No matching chats", 16, UiKit.TEXT, true));
            TextView note = UiKit.text(this, q.trim().isEmpty() ? "Start a chat here or summon Orbit with the Side button." : "Try a different search.", 13, UiKit.MUTED, false);
            note.setPadding(0, UiKit.dp(this, 6), 0, 0);
            empty.addView(note);
            chatList.addView(empty, cardLp());
            return;
        }
        // Pinned first, under their own heading, each group keeping the recent-activity order the
        // list already uses. Grouping happens here rather than in ConversationStore's ordering
        // because "most recently updated" is what the rest of Orbit means by the newest chat, and
        // pinning something is not a claim that it just happened.
        List<ConversationStore.Conversation> pinned = new ArrayList<>();
        List<ConversationStore.Conversation> recent = new ArrayList<>();
        for (ConversationStore.Conversation chat : chats) {
            (chat.pinned ? pinned : recent).add(chat);
        }
        if (!pinned.isEmpty()) {
            chatList.addView(sectionTitle("PINNED"));
            for (ConversationStore.Conversation chat : pinned) addChatRow(chat);
        }
        if (!recent.isEmpty()) {
            chatList.addView(sectionTitle(pinned.isEmpty() ? "RECENT CHATS" : "RECENT"));
            for (ConversationStore.Conversation chat : recent) addChatRow(chat);
        }
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

        // The card is wrapped rather than replaced, so everything above this line is the same card
        // it has always been and turning the gesture off returns it to exactly that.
        OrbitSwipeRow swipe = new OrbitSwipeRow(this, row);
        boolean gestures = Prefs.chatSwipeActions(this);
        final String chatId = chat.id;
        swipe.configure(
                gestures ? OrbitSwipeRow.ACTION_DELETE : OrbitSwipeRow.ACTION_NONE,
                gestures ? OrbitSwipeRow.ACTION_PIN : OrbitSwipeRow.ACTION_NONE,
                chat.pinned,
                // Identity, never position and never the visible title: the list can rebuild under
                // a gesture and two chats are allowed to be called the same thing.
                (source, action) -> {
                    if (action == OrbitSwipeRow.ACTION_DELETE) deleteChatWithUndo(chatId);
                    else togglePinned(chatId);
                });
        chatList.addView(swipe, cardLp());
    }

    /**
     * Pins or unpins one chat and puts it where it now belongs.
     *
     * <p>Reversible by doing the same thing again, so there is nothing to confirm and nothing to
     * undo. The list is rebuilt rather than the card moved, which is also what clears the drag: a
     * card that changed section while still translated would otherwise arrive in its new group
     * holding the offset the finger left it at.
     */
    private void togglePinned(String chatId) {
        boolean nowPinned = ConversationStore.setPinned(this, chatId,
                !ConversationStore.isPinned(this, chatId));
        DiagnosticStore.recordGesture(this, nowPinned ? "pin" : "unpin");
        OrbitSwipeRow.resetActive();
        refreshChats();
        if (chatList != null) {
            UiKit.haptic(chatList, android.view.HapticFeedbackConstants.CONTEXT_CLICK);
            // Announced as a result, not as movement. Nothing is said while the finger is moving.
            chatList.announceForAccessibility(nowPinned ? "Chat pinned" : "Chat unpinned");
        }
    }

    /**
     * Removes a chat from the list and gives the user a moment to take it back.
     *
     * <p>Nothing is destroyed here. The chat is held aside by id and stays exactly where it is in
     * storage, so Undo is not a restore at all: it is Orbit forgetting it was asked. That is the
     * reason this is safe. A snapshot-and-rebuild scheme would have to serialise the messages, the
     * attachment references, the stopped-turn anchors, the mode, the pinned state and the recorded
     * action results, and would silently drop whichever of those someone forgot to add later.
     * Deferring the existing delete cannot lose a field that was never touched.
     *
     * <p>The deletion is committed when the window ends, when another chat is deleted, or when
     * Chats leaves the foreground, so it is never left indefinitely pending and never depends on
     * this process still being alive to eventually happen.
     */
    private void deleteChatWithUndo(String chatId) {
        if (chatId == null || chatId.trim().isEmpty()) return;
        // A second delete while the first is still undoable commits the first rather than
        // discarding it, so the offer always belongs to the newest action and the older one is
        // honoured rather than quietly forgotten.
        commitPendingDeletion();
        pendingDeletionId = chatId;
        DiagnosticStore.recordGesture(this, "delete");
        OrbitSwipeRow.resetActive();
        refreshChats();
        showUndoBar();
    }

    private void showUndoBar() {
        if (undoBar == null) return;
        undoBar.setVisibility(View.VISIBLE);
        if (undoLabel != null) undoLabel.setText("Chat deleted");
        undoBar.announceForAccessibility("Chat deleted. Undo is available.");
        undoBar.removeCallbacks(undoTimeout);
        undoBar.postDelayed(undoTimeout, UNDO_WINDOW_MS);
        if (UiKit.animationsEnabled()) UiKit.enterContent(undoBar);
    }

    private void hideUndoBar() {
        if (undoBar == null) return;
        undoBar.removeCallbacks(undoTimeout);
        undoBar.setVisibility(View.GONE);
    }

    private void undoPendingDeletion() {
        if (pendingDeletionId == null) {
            hideUndoBar();
            return;
        }
        // Nothing to restore, because nothing was removed. The chat comes back complete because it
        // never stopped existing: messages, attachments, stopped marks, mode and pinned state
        // included.
        pendingDeletionId = null;
        DiagnosticStore.recordGesture(this, "delete undone");
        hideUndoBar();
        refreshChats();
        if (chatList != null) {
            UiKit.haptic(chatList, android.view.HapticFeedbackConstants.CONTEXT_CLICK);
            chatList.announceForAccessibility("Chat restored");
        }
    }

    /** Carries out a deletion the user did not take back. Uses the ordinary delete path. */
    private void commitPendingDeletion() {
        String id = pendingDeletionId;
        pendingDeletionId = null;
        hideUndoBar();
        if (id == null) return;
        ConversationStore.delete(this, id);
    }

    /**
     * Everything a swipe can do, without swiping.
     *
     * <p>Pin and Delete are on this menu as well as on the gesture, which is not a duplicate but
     * the point: a gesture is a shortcut for people who can make it, and TalkBack, a keyboard, an
     * assistive service, and anyone who has turned chat swipes off all reach the same two actions
     * here. Delete keeps its confirmation, because a menu tap is not a deliberate drag.
     */
    private void showChatMenu(View anchor, ConversationStore.Conversation chat) {
        boolean pinned = chat.pinned;
        String[] actions = {"Rename", pinned ? "Unpin" : "Pin", "Delete"};
        UiKit.showOrbitMenu(this, anchor, actions, -1, (index, title) -> {
            if (index == 1) {
                togglePinned(chat.id);
                return;
            }
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
                            // The same undoable path the swipe uses, so one action cannot be
                            // recoverable from one entry point and final from the other.
                            deleteChatWithUndo(chat.id);
                            refreshPending();
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

    /** Deletes with the same undoable path a swipe uses. For tests. */
    void deleteChatForTest(String chatId) { deleteChatWithUndo(chatId); }

    /** Takes back the pending deletion, as tapping Undo does. For tests. */
    void undoDeletionForTest() { undoPendingDeletion(); }

    /** Ends the Undo window, as its timeout does. For tests. */
    void commitDeletionsForTest() { commitPendingDeletion(); }

    /** Rebuilds the chat list, as a change to it does. For tests. */
    void refreshChatsForTest() { refreshChats(); }

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
        // Display only. The stored message keeps its Markdown and still renders in full when the
        // conversation is opened; this just spends the card's characters on words, not syntax.
        String body = OrbitMarkdown.toPreviewText(h.content, 100);
        if (body.isEmpty()) body = compact(h.content, 100);
        return body + (h.screenAttached ? "  •  Screen attached" : "");
    }

    private String compact(String text, int max) {
        String s = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        return s.length() <= max ? s : s.substring(0, Math.max(0, max-1)).trim() + "…";
    }
}
