package com.orbit.assistant;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Auto's two small Orbit sheets (0.8.3.0-beta.5+): which providers Auto can use, and the one-time
 * introduction shown the first time Auto is chosen.
 *
 * <p>Deliberately not a router control panel. Auto's only settings are the four provider switches;
 * there are no weights, rankings or rules to program. A separately billed provider says "May use
 * API credits" while it is off and stops saying it once the user has opted in.
 */
final class AutoSheets {

    static final String INTRO_TITLE = "Auto";
    static final String INTRO_BODY = AiSelection.AUTO_DESCRIPTION
            + "\n\nYou control which providers Auto may use."
            + "\n\nSeparately billed API providers stay off until you enable them.";
    static final String METERED_NOTE = "May use API credits";

    private AutoSheets() {}

    /** The provider switches. {@code onChanged} runs after any switch moves. */
    static AlertDialog showSettings(Context c, Runnable onChanged) {
        View content = settingsContent(c, onChanged);
        AlertDialog dialog = new AlertDialog.Builder(c)
                .setTitle("Auto can use")
                .setView(content)
                .setPositiveButton("Done", null)
                .create();
        UiKit.styleOrbitDialog(dialog, c, false);
        dialog.show();
        return dialog;
    }

    /** The settings sheet's body, also used by tests. */
    static View settingsContent(Context c, Runnable onChanged) {
        ScrollView scroll = new ScrollView(c);
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dp(c, 20);
        body.setPadding(pad, UiKit.dp(c, 4), pad, UiKit.dp(c, 8));
        scroll.addView(body);

        TextView intro = UiKit.text(c, AiSelection.AUTO_DESCRIPTION, 13, UiKit.MUTED, false);
        intro.setLineSpacing(0, 1.12f);
        intro.setPadding(0, 0, 0, UiKit.dp(c, 10));
        body.addView(intro);

        for (String provider : AutoPermissions.PROVIDERS) {
            if (Prefs.PROVIDER_LOCAL.equals(provider) && !OrbitDistribution.supportsOrbitLocal()) {
                continue;
            }
            body.addView(providerRow(c, provider, onChanged));
        }

        TextView note = UiKit.text(c,
                "Auto only uses a provider that is switched on here and ready. Choosing a model "
                        + "yourself always uses exactly that model.",
                12, UiKit.MUTED, false);
        note.setLineSpacing(0, 1.12f);
        note.setPadding(0, UiKit.dp(c, 10), 0, 0);
        body.addView(note);
        return scroll;
    }

    /**
     * One provider's switch, laid out like {@link UiKit#switchRow} but with a description line that
     * always exists, so it can change in place when the switch moves.
     */
    private static View providerRow(Context c, String provider, Runnable onChanged) {
        String name = AutoPermissions.shortName(provider);
        OrbitSwitch control = new OrbitSwitch(c);
        control.setChecked(AutoPermissions.allows(c, provider), false);
        control.setTag(provider);

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(UiKit.dp(c, 2), UiKit.dp(c, 6), UiKit.dp(c, 2), UiKit.dp(c, 6));
        row.setMinimumHeight(UiKit.dp(c, 48));
        row.setBackground(UiKit.ripple(android.graphics.Color.TRANSPARENT, UiKit.accent(c), 14, c));
        LinearLayout labels = new LinearLayout(c);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(UiKit.text(c, name, 14, UiKit.TEXT, false));
        TextView description = UiKit.text(c, "", 12, UiKit.MUTED, false);
        description.setPadding(0, UiKit.dp(c, 2), 0, 0);
        labels.addView(description);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        labelLp.rightMargin = UiKit.dp(c, 12);
        row.addView(labels, labelLp);
        row.addView(control, new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.setOnClickListener(v -> control.toggle());

        Runnable describe = () -> {
            String text = describe(c, provider, control.isChecked());
            description.setText(text);
            description.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
            control.setContentDescription(text.isEmpty() ? name : name + ". " + text);
        };
        describe.run();
        control.setOnCheckedChangeListener((button, checked) -> {
            AutoPermissions.set(c, provider, checked);
            describe.run();
            if (onChanged != null) onChanged.run();
        });
        return row;
    }

    /**
     * One quiet line under a provider: whether it is ready, and for a separately billed provider
     * that is still off, that it may use API credits. Never repeated once the user opted in.
     */
    static String describe(Context c, String provider, boolean allowed) {
        AiProvider p = AiProviders.byId(provider);
        boolean ready;
        try {
            ready = p.id().equals(provider) && p.status(c) == AiProvider.Status.READY;
        } catch (RuntimeException e) {
            ready = false;
        }
        String status = ready ? "" : p.statusDetail(c);
        if (AutoPermissions.metered(provider) && !allowed) {
            return status.isEmpty() ? METERED_NOTE : METERED_NOTE + " · " + status;
        }
        return status;
    }

    /** Current switch states, for tests. */
    static Map<String, Boolean> switchesForTest(View content) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        collect(content, out);
        return out;
    }

    private static void collect(View view, Map<String, Boolean> out) {
        if (view instanceof OrbitSwitch && view.getTag() instanceof String) {
            out.put((String) view.getTag(), ((OrbitSwitch) view).isChecked());
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out);
        }
    }

    // ---- first use -------------------------------------------------------------------------------

    /** True until the introduction has been shown once. */
    static boolean introNeeded(Context c) {
        return !Prefs.get(c).getBoolean(Prefs.AUTO_INTRO_SEEN, false);
    }

    /**
     * Runs {@code proceed} after the one-sheet introduction the first time Auto is chosen, and
     * straight away every time after. Cancelling the sheet does not choose Auto.
     */
    static void introThen(Context c, Runnable proceed) {
        if (!introNeeded(c)) {
            proceed.run();
            return;
        }
        TextView body = UiKit.text(c, INTRO_BODY, 14, UiKit.TEXT, false);
        body.setLineSpacing(0, 1.15f);
        int pad = UiKit.dp(c, 22);
        body.setPadding(pad, UiKit.dp(c, 4), pad, UiKit.dp(c, 6));
        AlertDialog dialog = new AlertDialog.Builder(c)
                .setTitle(INTRO_TITLE)
                .setView(body)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Providers", (d, w) -> {
                    markIntroSeen(c);
                    showSettings(c, null);
                    proceed.run();
                })
                .setPositiveButton("Use Auto", (d, w) -> {
                    markIntroSeen(c);
                    proceed.run();
                })
                .create();
        UiKit.styleOrbitDialog(dialog, c, false);
        dialog.show();
    }

    static void markIntroSeen(Context c) {
        Prefs.get(c).edit().putBoolean(Prefs.AUTO_INTRO_SEEN, true).apply();
    }
}
