package com.orbit.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Orbit-native manager and SAF installer for declarative .orbitext packages. */
public final class ExtensionsActivity extends Activity {
    private static final String LOG_TAG = "OrbitExtensions";
    private static final int REQ_IMPORT_EXTENSION = 7010;
    private static final String FIRST_PARTY_ASSET_DIR = "orbit-extensions/";
    private static final String[] FIRST_PARTY_ASSETS = {
            "orbit-web-tools.orbitext",
            "developer-tools.orbitext",
            "quick-links.orbitext",
            "discord-webhook.orbitext",
            "ntfy-notifications.orbitext"
    };
    private ScrollView activeScroll;
    private int bundledLoadFailures;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.syncTheme(this);
        Window window = getWindow();
        window.setStatusBarColor(UiKit.BG);
        window.setNavigationBarColor(UiKit.BG);
        rebuild();
    }

    @Override protected void onResume() {
        super.onResume();
        UiPresence.enter(this);
    }

    @Override protected void onPause() {
        UiPresence.leave(this);
        super.onPause();
    }

    private void rebuild() {
        rebuildAt(-1);
    }

    private void rebuildPreservingPosition() {
        rebuildAt(activeScroll == null ? 0 : activeScroll.getScrollY());
    }

    private void rebuildAt(int requestedScrollY) {
        View content = buildContent();
        setContentView(content);
        UiKit.applyActivityInsets(this, content, true);
        if (requestedScrollY >= 0 && activeScroll != null) {
            ScrollView target = activeScroll;
            ViewTreeObserver.OnGlobalLayoutListener listener = new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    ViewTreeObserver observer = target.getViewTreeObserver();
                    if (observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
                    int childHeight = target.getChildCount() == 0 ? 0 : target.getChildAt(0).getHeight();
                    int maximum = Math.max(0, childHeight - target.getHeight());
                    target.scrollTo(0, Math.min(requestedScrollY, maximum));
                }
            };
            target.getViewTreeObserver().addOnGlobalLayoutListener(listener);
        }
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        activeScroll = scroll;
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(UiKit.BG);
        scroll.setForceDarkAllowed(false);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int padding = UiKit.dp(this, 20);
        page.setPadding(padding, UiKit.dp(this, 30), padding, UiKit.dp(this, 48));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_back);
        back.setImageTintList(ColorStateList.valueOf(UiKit.accent(this)));
        back.setBackground(UiKit.ripple(UiKit.SURFACE_2, UiKit.accent(this), 18, this));
        back.setContentDescription("Back to Settings");
        back.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 10),
                UiKit.dp(this, 10), UiKit.dp(this, 10));
        back.setOnClickListener(v -> finish());
        UiKit.pressScale(back);
        header.addView(back, new LinearLayout.LayoutParams(UiKit.dp(this, 44), UiKit.dp(this, 44)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(UiKit.dp(this, 13), 0, 0, 0);
        titles.addView(UiKit.text(this, "Extensions", 25, UiKit.TEXT, true));
        titles.addView(UiKit.text(this, "Add integrations and new actions to Orbit", 12, UiKit.MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(header);

        TextView intro = UiKit.text(this,
                "Orbit Extensions are reviewed, declarative action packages. They cannot run code or access chats, Memory, screen context, files, credentials, or Android permissions.",
                13, UiKit.MUTED, false);
        intro.setLineSpacing(0, 1.13f);
        LinearLayout.LayoutParams introLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        introLp.setMargins(UiKit.dp(this, 2), UiKit.dp(this, 17), UiKit.dp(this, 2), UiKit.dp(this, 14));
        page.addView(intro, introLp);

        page.addView(sectionHeading("FIRST-PARTY EXTENSIONS"));
        List<OrbitExtension> firstParty = loadFirstPartyExtensions();
        for (int i = 0; i < firstParty.size(); i++) {
            LinearLayout.LayoutParams lp = cardSpacing(i > 0);
            try {
                page.addView(firstPartyCard(firstParty.get(i)), lp);
            } catch (RuntimeException e) {
                bundledLoadFailures++;
                logFailure("first_party_card", e);
            }
        }
        if (bundledLoadFailures > 0) {
            LinearLayout unavailable = card();
            unavailable.addView(UiKit.text(this,
                    bundledLoadFailures == 1
                            ? "One official extension is unavailable in this build."
                            : bundledLoadFailures + " official extensions are unavailable in this build.",
                    13, UiKit.TEXT, true));
            page.addView(unavailable, cardSpacing(!firstParty.isEmpty()));
        }

        page.addView(sectionHeading("IMPORT"));
        LinearLayout importCard = card();
        importCard.addView(UiKit.text(this, "Import extension from file", 16, UiKit.TEXT, true));
        TextView importNote = UiKit.text(this,
                "Choose a user-created, community, or custom .orbitext file. Orbit validates it and shows the same complete review before installation.",
                12, UiKit.MUTED, false);
        importNote.setLineSpacing(0, 1.12f);
        importNote.setPadding(0, UiKit.dp(this, 5), 0, UiKit.dp(this, 11));
        importCard.addView(importNote);
        Button install = secondaryButton("Import extension from file");
        install.setContentDescription("Import extension from file using Android file picker");
        install.setOnClickListener(v -> chooseExtension());
        importCard.addView(install, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 44)));
        page.addView(importCard);

        page.addView(sectionHeading("INSTALLED EXTENSIONS"));

        OrbitExtensionStore.ManagerSnapshot snapshot = OrbitExtensionStore.managerSnapshot(this);
        if (snapshot.entries.isEmpty() && snapshot.unreadableStoreToken == null) {
            LinearLayout empty = card();
            empty.addView(UiKit.text(this, "No extensions installed", 16, UiKit.TEXT, true));
            TextView note = UiKit.text(this,
                    "Choose a .orbitext file to review its actions and network destinations before installing it.",
                    12, UiKit.MUTED, false);
            note.setPadding(0, UiKit.dp(this, 5), 0, 0);
            empty.addView(note);
            page.addView(empty);
        } else {
            int rendered = 0;
            for (OrbitExtensionStore.ManagerEntry entry : snapshot.entries) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (rendered++ > 0) lp.topMargin = UiKit.dp(this, 10);
                if (entry.isUnavailable()) {
                    page.addView(unavailableExtensionCard(entry.removalToken, false), lp);
                    continue;
                }
                try {
                    page.addView(extensionCard(entry.installed), lp);
                } catch (RuntimeException e) {
                    logFailure("installed_card", e);
                    page.addView(unavailableExtensionCard(
                            entry.installed.extension.id, true), lp);
                }
            }
            if (snapshot.unreadableStoreToken != null) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (rendered > 0) lp.topMargin = UiKit.dp(this, 10);
                page.addView(unavailableExtensionCard(snapshot.unreadableStoreToken, false), lp);
            }
        }

        TextView footer = UiKit.text(this,
                "Extensions v1 remains compatible. Extensions v2 adds Orbit-rendered setup and Routine parameters while keeping actions declarative, reviewed and non-executable.",
                11, UiKit.MUTED, false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(UiKit.dp(this, 6), UiKit.dp(this, 17), UiKit.dp(this, 6), 0);
        page.addView(footer);
        UiKit.applyTypography(page);
        return scroll;
    }

    private LinearLayout extensionCard(OrbitExtensionStore.Installed installed) {
        OrbitExtension extension = installed.extension;
        boolean configured = isConfiguredSafely(installed);
        LinearLayout card = card();

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(UiKit.text(this, extension.name, 17, UiKit.TEXT, true));
        copy.addView(UiKit.text(this,
                "v" + extension.version + " · " + extension.author, 12, UiKit.MUTED, false));
        top.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        String stateText = !configured ? "NEEDS SETUP" : installed.enabled ? "ENABLED" : "DISABLED";
        TextView state = UiKit.text(this, stateText, 11,
                configured && !installed.enabled ? UiKit.MUTED : UiKit.accent(this), true);
        state.setLetterSpacing(0.08f);
        top.addView(state);
        card.addView(top);

        TextView description = UiKit.text(this, extension.description, 13, UiKit.MUTED, false);
        description.setLineSpacing(0, 1.12f);
        description.setPadding(0, UiKit.dp(this, 9), 0, UiKit.dp(this, 7));
        card.addView(description);
        for (OrbitExtension.Action action : extension.actions) {
            TextView actionView = UiKit.text(this,
                    "• " + action.name + " · " + action.capabilityLabel(),
                    12, UiKit.TEXT, false);
            actionView.setPadding(0, UiKit.dp(this, 3), 0, 0);
            card.addView(actionView);
        }

        if (!extension.setupFields.isEmpty()) {
            Button configure = configured ? secondaryButton("Configure") : primaryButton("Set up");
            configure.setContentDescription((configured ? "Configure " : "Set up ") + extension.name);
            configure.setOnClickListener(v -> showConfiguration(installed));
            LinearLayout.LayoutParams configureLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 44));
            configureLp.topMargin = UiKit.dp(this, 14);
            card.addView(configure, configureLp);
        }

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, extension.setupFields.isEmpty() ? UiKit.dp(this, 14) :
                UiKit.dp(this, 9), 0, 0);
        Button toggle = installed.enabled ? warningButton("Disable") : primaryButton("Enable");
        toggle.setContentDescription((installed.enabled ? "Disable " : "Enable ") + extension.name);
        toggle.setOnClickListener(v -> {
            if (OrbitExtensionStore.setEnabled(this, extension.id, !installed.enabled))
                rebuildPreservingPosition();
        });
        buttons.addView(toggle, new LinearLayout.LayoutParams(0, UiKit.dp(this, 44), 1));
        Button remove = destructiveButton("Remove");
        remove.setContentDescription("Remove " + extension.name);
        LinearLayout.LayoutParams removeLp = new LinearLayout.LayoutParams(0, UiKit.dp(this, 44), 1);
        removeLp.leftMargin = UiKit.dp(this, 9);
        buttons.addView(remove, removeLp);
        remove.setOnClickListener(v -> confirmRemove(extension));
        card.addView(buttons);
        return card;
    }

    private LinearLayout firstPartyCard(OrbitExtension extension) {
        OrbitExtensionStore.Installed installed = OrbitExtensionStore.find(this, extension.id);
        boolean configured = installed == null || isConfiguredSafely(installed);
        LinearLayout card = card();
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(UiKit.text(this, extension.name, 16, UiKit.TEXT, true));
        copy.addView(UiKit.text(this,
                "v" + extension.version + " · " + extension.author, 12, UiKit.MUTED, false));
        top.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        String stateText = installed == null ? "NOT INSTALLED" :
                configured ? "INSTALLED" : "NEEDS SETUP";
        TextView state = UiKit.text(this, stateText, 10,
                installed == null ? UiKit.MUTED : UiKit.accent(this), true);
        state.setLetterSpacing(0.07f);
        top.addView(state);
        card.addView(top);

        TextView description = UiKit.text(this, extension.description, 12, UiKit.MUTED, false);
        description.setLineSpacing(0, 1.12f);
        description.setPadding(0, UiKit.dp(this, 7), 0, UiKit.dp(this, 12));
        card.addView(description);

        Button action;
        if (installed == null) {
            action = primaryButton("Install");
            action.setContentDescription("Review and install " + extension.name);
            action.setOnClickListener(v -> showInstallReview(extension));
        } else {
            action = installedButton(!configured ? "Installed · Needs setup" :
                    installed.enabled ? "Installed · Enabled" : "Installed · Disabled");
            action.setContentDescription(extension.name + " is installed and " +
                    (!configured ? "needs setup" : installed.enabled ? "enabled" : "disabled"));
        }
        card.addView(action, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 44)));
        return card;
    }

    private void chooseExtension() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/json", "application/octet-stream", "text/plain"});
        startActivityForResult(intent, REQ_IMPORT_EXTENSION);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT_EXTENSION || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            String displayName = displayName(uri);
            if (!displayName.toLowerCase(java.util.Locale.US).endsWith(".orbitext"))
                throw new IllegalArgumentException("Choose a file ending in .orbitext.");
            OrbitExtension extension = OrbitExtension.parse(readManifest(uri));
            if (OrbitExtensionStore.find(this, extension.id) != null)
                throw new IllegalArgumentException("An extension with this ID is already installed.");
            showInstallReview(extension);
        } catch (Exception e) {
            showError(e.getMessage() == null ? "Orbit could not read this extension." : e.getMessage());
        }
    }

    private void showInstallReview(OrbitExtension extension) {
        LinearLayout review = new LinearLayout(this);
        review.setOrientation(LinearLayout.VERTICAL);
        review.setPadding(UiKit.dp(this, 22), UiKit.dp(this, 8),
                UiKit.dp(this, 22), UiKit.dp(this, 4));
        review.addView(UiKit.text(this, extension.name + " · v" + extension.version,
                17, UiKit.TEXT, true));
        review.addView(spacedText("By " + extension.author, UiKit.MUTED));
        review.addView(spacedText(extension.description, UiKit.TEXT));

        if (!extension.setupFields.isEmpty()) {
            TextView setupTitle = spacedText("REQUIRES SETUP", UiKit.MUTED);
            setupTitle.setLetterSpacing(0.1f);
            review.addView(setupTitle);
            for (OrbitExtension.SetupField field : extension.setupFields) {
                String privacy = field.isSecret() ? "Secret " : "";
                String kind = OrbitExtension.SETUP_SECRET_URL.equals(field.type) ||
                        OrbitExtension.SETUP_URL.equals(field.type) ? "URL" : "Text";
                review.addView(spacedText("• " + field.label + " — " + privacy + kind +
                        (field.required ? " · Required" : " · Optional"), UiKit.TEXT));
            }
            review.addView(spacedText(
                    "Install first, then configure explicitly. Orbit encrypts secret values and does not expose them to chats, Memory, backups, Routines or other extensions.",
                    UiKit.TEXT));
        }

        TextView actionsTitle = spacedText("ACTIONS BEING ADDED", UiKit.MUTED);
        actionsTitle.setLetterSpacing(0.1f);
        review.addView(actionsTitle);
        for (OrbitExtension.Action action : extension.actions) {
            String fixedBody = OrbitExtension.TYPE_HTTPS_REQUEST.equals(action.type) &&
                    "POST".equals(action.method)
                    ? "\n  " + (action.parameters.isEmpty() ? "JSON body: " :
                    "Templated JSON body: ") +
                    (action.body == null ? "{}" : action.body.toString()) : "";
            String parameters = action.parameters.isEmpty() ? "" :
                    "\n  Routine parameters: " + parameterLabels(action);
            String headers = action.headers.isEmpty() ? "" :
                    "\n  Declared headers: " + headerNames(action);
            review.addView(spacedText("• " + action.name + " — " + action.capabilityLabel() +
                    "\n  " + action.url + parameters + headers + fixedBody, UiKit.TEXT));
        }
        List<String> hosts = extension.contactedHosts();
        String destinations = hosts.isEmpty() ? "None fixed in the manifest" :
                String.join("\n", hosts);
        if (extension.hasDynamicDestination())
            destinations += "\nUser-supplied during setup (validated public HTTPS only)";
        review.addView(spacedText("NETWORK DESTINATIONS\n" + destinations, UiKit.MUTED));
        TextView isolation = spacedText(
                "This extension cannot receive Orbit chats, Memory, screen context, files, other credentials, or Android permission state. Only declared configuration and Routine parameters can enter its reviewed request templates.",
                UiKit.TEXT);
        isolation.setBackground(UiKit.rounded(UiKit.SURFACE_2, 14, this));
        isolation.setPadding(UiKit.dp(this, 13), UiKit.dp(this, 11),
                UiKit.dp(this, 13), UiKit.dp(this, 11));
        review.addView(isolation);
        UiKit.applyTypography(review);

        ScrollView wrapper = new ScrollView(this);
        wrapper.addView(review);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Review extension")
                .setView(wrapper)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Install", (d, w) -> {
                    if (OrbitExtensionStore.install(this, extension)) {
                        Toast.makeText(this, extension.name + " installed", Toast.LENGTH_SHORT).show();
                        rebuildPreservingPosition();
                    } else {
                        showError("Orbit could not install this extension. It may already exist or the extension limit was reached.");
                    }
                }).create();
        UiKit.styleOrbitDialog(dialog, this, false);
        dialog.show();
    }

    private void showConfiguration(OrbitExtensionStore.Installed installed) {
        OrbitExtension extension = installed.extension;
        boolean initiallyConfigured = isConfiguredSafely(installed);
        JSONObject saved = OrbitExtensionStore.configuration(this, extension.id);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(UiKit.dp(this, 22), UiKit.dp(this, 5),
                UiKit.dp(this, 22), UiKit.dp(this, 5));

        TextView note = UiKit.text(this,
                "Secret values are encrypted by Android Keystore and are never shown again, backed up, or stored in Routines.",
                12, UiKit.MUTED, false);
        note.setLineSpacing(0, 1.12f);
        note.setPadding(0, 0, 0, UiKit.dp(this, 8));
        form.addView(note);

        Map<String, EditText> inputs = new LinkedHashMap<>();
        Map<String, Boolean> hadSecret = new HashMap<>();
        Set<String> clearRequested = new HashSet<>();
        for (OrbitExtension.SetupField field : extension.setupFields) {
            TextView label = UiKit.text(this, field.label +
                    (field.required ? " · Required" : " · Optional"),
                    12, UiKit.MUTED, true);
            label.setPadding(UiKit.dp(this, 2), UiKit.dp(this, 10), 0, UiKit.dp(this, 5));
            form.addView(label);
            if (!field.description.isEmpty()) {
                TextView description = UiKit.text(this, field.description,
                        11, UiKit.MUTED, false);
                description.setPadding(UiKit.dp(this, 2), 0, 0, UiKit.dp(this, 6));
                form.addView(description);
            }

            boolean secret = field.isSecret();
            String storedSecret = secret ? OrbitExtensionSecretStore.load(
                    this, extension.id, field.id) : null;
            boolean hasSecret = storedSecret != null;
            hadSecret.put(field.id, hasSecret);
            EditText input = configurationInput(field, secret ? "" :
                    saved.optString(field.id, field.defaultValue));
            if (secret) input.setHint(hasSecret ? safeSecretStatus(field, storedSecret) :
                    "Enter securely");
            inputs.put(field.id, input);
            form.addView(input, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 52)));

            if (secret && hasSecret) {
                Button clear = secondaryButton("Clear saved value");
                LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 40));
                clearLp.topMargin = UiKit.dp(this, 6);
                form.addView(clear, clearLp);
                clear.setOnClickListener(v -> {
                    if (clearRequested.remove(field.id)) {
                        clear.setText("Clear saved value");
                        input.setHint(safeSecretStatus(field,
                                OrbitExtensionSecretStore.load(this, extension.id, field.id)));
                    } else {
                        clearRequested.add(field.id);
                        input.setText("");
                        input.setHint("Will be cleared");
                        clear.setText("Keep saved value");
                    }
                });
                input.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                        if (s != null && s.length() > 0 && clearRequested.remove(field.id)) {
                            clear.setText("Clear saved value");
                        }
                    }
                    @Override public void afterTextChanged(Editable s) {}
                });
            }
        }
        UiKit.applyTypography(form);
        ScrollView wrapper = new ScrollView(this);
        wrapper.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle((initiallyConfigured ? "Configure " : "Set up ") + extension.name)
                .setView(wrapper)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create();
        UiKit.styleOrbitDialog(dialog, this, false, () -> {
            Button saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (saveButton != null) saveButton.setOnClickListener(v -> {
                JSONObject nonSecret = new JSONObject();
                Map<String, String> secretReplacements = new LinkedHashMap<>();
                try {
                    for (OrbitExtension.SetupField field : extension.setupFields) {
                        EditText input = inputs.get(field.id);
                        String entered = input == null ? "" : input.getText().toString();
                        if (field.isSecret()) {
                            String safe = entered.trim().isEmpty() ? "" :
                                    OrbitExtensionV2.validateSetupValue(field, entered);
                            if (!safe.isEmpty()) secretReplacements.put(field.id, safe);
                            boolean willExist = !safe.isEmpty() ||
                                    (Boolean.TRUE.equals(hadSecret.get(field.id)) &&
                                            !clearRequested.contains(field.id));
                            if (field.required && !willExist &&
                                    !(initiallyConfigured && clearRequested.contains(field.id)))
                                throw new IllegalArgumentException("Enter " + field.label + ".");
                        } else {
                            String safe = OrbitExtensionV2.validateSetupValue(field, entered);
                            if (field.required && safe.isEmpty())
                                throw new IllegalArgumentException("Enter " + field.label + ".");
                            if (!safe.isEmpty()) nonSecret.put(field.id, safe);
                        }
                    }
                } catch (IllegalArgumentException e) {
                    Toast.makeText(this, e.getMessage() == null ?
                            "Check the setup values." : e.getMessage(), Toast.LENGTH_SHORT).show();
                    return;
                } catch (Exception e) {
                    Toast.makeText(this, "Check the setup values.", Toast.LENGTH_SHORT).show();
                    return;
                }

                JSONObject previous = OrbitExtensionStore.configuration(this, extension.id);
                if (!OrbitExtensionStore.setConfiguration(this, extension.id, nonSecret)) {
                    configurationSaveFailed();
                    return;
                }
                boolean secretsSaved = secretReplacements.isEmpty() && clearRequested.isEmpty() ||
                        OrbitExtensionSecretStore.save(this, extension.id,
                                secretReplacements, clearRequested);
                if (!secretsSaved) {
                    OrbitExtensionStore.setConfiguration(this, extension.id, previous);
                    configurationSaveFailed();
                    return;
                }
                dialog.dismiss();
                Toast.makeText(this, extension.name + " configuration saved",
                        Toast.LENGTH_SHORT).show();
                rebuildPreservingPosition();
            });
        });
        dialog.show();
    }

    private EditText configurationInput(OrbitExtension.SetupField field, String value) {
        EditText input = new EditText(this);
        input.setText(value == null ? "" : value);
        input.setTextColor(UiKit.TEXT);
        input.setHintTextColor(UiKit.MUTED);
        input.setTextSize(14);
        input.setSingleLine(true);
        input.setPadding(UiKit.dp(this, 15), 0, UiKit.dp(this, 15), 0);
        input.setBackground(UiKit.outlined(UiKit.SURFACE_2,
                Color.rgb(53, 58, 72), 15, this));
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(field.maxLength)});
        if (field.isSecret()) {
            input.setInputType(InputType.TYPE_CLASS_TEXT |
                    InputType.TYPE_TEXT_VARIATION_PASSWORD |
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        } else if (OrbitExtension.SETUP_URL.equals(field.type)) {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        } else {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        }
        return input;
    }

    private String safeSecretStatus(OrbitExtension.SetupField field, String value) {
        if (value == null || value.isEmpty()) return "Enter securely";
        if (OrbitExtension.SETUP_SECRET_URL.equals(field.type)) {
            try {
                String host = new java.net.URI(value).getHost();
                if (host != null && !host.trim().isEmpty()) return "Configured · " + host;
            } catch (Exception ignored) {}
        }
        return "Saved";
    }

    private String parameterLabels(OrbitExtension.Action action) {
        List<String> labels = new ArrayList<>();
        for (OrbitExtension.ActionParameter parameter : action.parameters)
            labels.add(parameter.label + (parameter.required ? " (required)" : " (optional)"));
        return String.join(", ", labels);
    }

    private String headerNames(OrbitExtension.Action action) {
        List<String> names = new ArrayList<>();
        for (OrbitExtension.RequestHeader header : action.headers) names.add(header.name);
        return String.join(", ", names);
    }

    private void configurationSaveFailed() {
        AlertDialog error = new AlertDialog.Builder(this)
                .setTitle("Configuration not saved")
                .setMessage("Orbit could not securely save this extension configuration. No credential was stored in plaintext.")
                .setPositiveButton("OK", null)
                .create();
        UiKit.styleOrbitDialog(error, this, false);
        error.show();
    }

    private void confirmRemove(OrbitExtension extension) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Remove " + extension.name + "?")
                .setMessage("Routines that reference its actions will remain saved and report “Extension action unavailable” until a matching extension is installed again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (d, w) -> {
                    if (OrbitExtensionStore.remove(this, extension.id))
                        rebuildPreservingPosition();
                    else Toast.makeText(this, "Orbit could not safely remove this extension.",
                            Toast.LENGTH_SHORT).show();
                }).create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
    }

    private LinearLayout unavailableExtensionCard(String removalKey, boolean validExtension) {
        LinearLayout card = card();
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = UiKit.text(this, "Extension unavailable", 16, UiKit.TEXT, true);
        top.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView state = UiKit.text(this, "UNAVAILABLE", 10, UiKit.MUTED, true);
        state.setLetterSpacing(0.07f);
        top.addView(state);
        card.addView(top);

        TextView guidance = UiKit.text(this,
                "Orbit could not safely read this installed extension. It remains inactive and its saved data has not been changed.",
                12, UiKit.MUTED, false);
        guidance.setLineSpacing(0, 1.12f);
        guidance.setPadding(0, UiKit.dp(this, 8), 0, UiKit.dp(this, 12));
        card.addView(guidance);
        Button remove = destructiveButton("Remove");
        remove.setContentDescription("Remove unavailable extension");
        remove.setOnClickListener(v -> confirmRemoveUnavailable(removalKey, validExtension));
        card.addView(remove, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 44)));
        return card;
    }

    private void confirmRemoveUnavailable(String removalKey, boolean validExtension) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Remove unavailable extension?")
                .setMessage("Only this unavailable installed entry will be removed. Existing Routines remain saved.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (d, w) -> {
                    boolean removed = validExtension
                            ? OrbitExtensionStore.remove(this, removalKey)
                            : OrbitExtensionStore.removeUnavailable(this, removalKey);
                    if (removed) rebuildPreservingPosition();
                    else Toast.makeText(this, "Orbit could not safely remove this extension.",
                            Toast.LENGTH_SHORT).show();
                }).create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
    }

    private boolean isConfiguredSafely(OrbitExtensionStore.Installed installed) {
        try { return OrbitExtensionStore.isConfigured(this, installed); }
        catch (RuntimeException e) {
            logFailure("configuration_state", e);
            return false;
        }
    }

    private void logFailure(String stage, RuntimeException error) {
        Log.w(LOG_TAG, "stage=" + stage + " error=" +
                (error == null ? "unknown" : error.getClass().getSimpleName()));
    }

    private void showError(String message) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Extension not installed")
                .setMessage(message)
                .setPositiveButton("OK", null).create();
        UiKit.styleOrbitDialog(dialog, this, false);
        dialog.show();
    }

    private String readManifest(Uri uri) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalArgumentException("Android could not open the selected file.");
            return readManifest(input);
        }
    }

    private List<OrbitExtension> loadFirstPartyExtensions() {
        List<OrbitExtension> out = new ArrayList<>();
        bundledLoadFailures = 0;
        for (String assetName : FIRST_PARTY_ASSETS) {
            try (InputStream input = getAssets().open(FIRST_PARTY_ASSET_DIR + assetName)) {
                // First-party packages use the exact same size limit and untrusted
                // OrbitExtension parser as files selected through Android SAF.
                out.add(OrbitExtension.parse(readManifest(input)));
            } catch (Exception error) {
                // A damaged bundled manifest must remain unavailable, never privileged.
                bundledLoadFailures++;
                Log.w(LOG_TAG, "stage=first_party_manifest asset=" + assetName +
                        " error=" + error.getClass().getSimpleName());
            }
        }
        return out;
    }

    private String readManifest(InputStream input) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > OrbitExtension.MAX_MANIFEST_BYTES)
                    throw new IllegalArgumentException("The extension manifest is too large.");
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null) return name;
            }
        } catch (Exception ignored) {}
        return uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment();
    }

    private TextView spacedText(String text, int color) {
        TextView view = UiKit.text(this, text, 12, color, false);
        view.setLineSpacing(0, 1.12f);
        view.setPadding(0, UiKit.dp(this, 8), 0, UiKit.dp(this, 2));
        return view;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(UiKit.dp(this, 17), UiKit.dp(this, 16),
                UiKit.dp(this, 17), UiKit.dp(this, 16));
        card.setBackground(UiKit.outlined(UiKit.SURFACE,
                UiKit.withAlpha(UiKit.accent(this), 38), 22, this));
        card.setElevation(UiKit.dp(this, 2));
        return card;
    }

    private TextView sectionHeading(String text) {
        TextView heading = UiKit.text(this, text, 12, UiKit.MUTED, true);
        heading.setLetterSpacing(0.13f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(UiKit.dp(this, 4), UiKit.dp(this, 24), 0, UiKit.dp(this, 9));
        heading.setLayoutParams(lp);
        return heading;
    }

    private LinearLayout.LayoutParams cardSpacing(boolean separated) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (separated) lp.topMargin = UiKit.dp(this, 10);
        return lp;
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
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                Color.rgb(53, 58, 72), UiKit.accent(this), 14, this));
        button.setMinHeight(0); button.setMinimumHeight(0); button.setStateListAnimator(null);
        UiKit.pressScale(button);
        return button;
    }

    private Button warningButton(String text) {
        int warning = Color.rgb(224, 157, 78);
        Button button = outlinedActionButton(text, warning,
                UiKit.blend(warning, UiKit.SURFACE_2, 0.10f));
        return button;
    }

    private Button destructiveButton(String text) {
        int destructive = Color.rgb(226, 100, 108);
        return outlinedActionButton(text, destructive,
                UiKit.blend(destructive, UiKit.SURFACE_2, 0.08f));
    }

    private Button installedButton(String text) {
        Button button = secondaryButton(text);
        button.setEnabled(false);
        button.setAlpha(0.72f);
        return button;
    }

    private Button outlinedActionButton(String text, int color, int fill) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(color);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setBackground(UiKit.rippleOutlined(fill,
                UiKit.withAlpha(color, 190), color, 14, this));
        button.setMinHeight(0); button.setMinimumHeight(0); button.setStateListAnimator(null);
        UiKit.pressScale(button);
        return button;
    }
}
