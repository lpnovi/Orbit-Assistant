package com.orbit.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.Rect;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.SystemFonts;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.OvershootInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.TextView;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import java.io.File;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.ArrayList;
import java.util.List;
import android.graphics.drawable.ColorDrawable;

public final class UiKit {
    private static final long ORBIT_POPUP_EXIT_MS = 85L;
    private static final int NORMAL_BG = Color.rgb(10, 12, 17);
    public static int BG = NORMAL_BG;
    public static final int SURFACE = Color.rgb(22, 25, 33);
    public static final int SURFACE_2 = Color.rgb(30, 34, 44);
    public static final int SURFACE_3 = Color.rgb(37, 41, 53);
    public static final int TEXT = Color.rgb(244, 244, 248);
    public static final int MUTED = Color.rgb(166, 171, 185);
    public static final int DEFAULT_ACCENT = Color.rgb(139, 124, 255);
    public static final int BLURPLE = Color.rgb(88, 101, 242); // Discord-style blurple
    public static final int PASTEL_PINK = Color.rgb(255, 209, 220); // #FFD1DC
    public static final int PASTEL_BLUE = Color.rgb(203, 229, 242); // #CBE5F2
    public static final int SUCCESS = Color.rgb(87, 214, 146);
    /** Restrained failure/destructive tone, matching Orbit's destructive dialog action. */
    public static final int DANGER = Color.rgb(239, 105, 105);

    private static final String[] ACCENT_KEYS = {
            "dynamic", "blurple", "violet", "blue", "mint", "rose", "nova",
            "pastel_pink", "pastel_blue"
    };
    private static final String[] ACCENT_LABELS = {
            "Dynamic", "Blurple", "Violet", "Blue", "Mint", "Rose", "Nova",
            "Pastel Pink", "Pastel Blue"
    };
    private static final String[] BUBBLE_COLOR_KEYS = {
            "classic", "accent", "blurple", "violet", "blue", "mint", "rose", "nova",
            "pastel_pink", "pastel_blue"
    };
    private static final String[] BUBBLE_COLOR_LABELS = {
            "Classic", "Accent", "Blurple", "Violet", "Blue", "Mint", "Rose", "Nova",
            "Pastel Pink", "Pastel Blue"
    };
    private static final String[] PROVIDER_KEYS = {
            Prefs.PROVIDER_CHATGPT, Prefs.PROVIDER_RELAY
    };
    private static final String[] PROVIDER_LABELS = {
            "ChatGPT account (recommended)", "OpenAI API relay (fallback)"
    };

    private static final Map<TextView, FontPreview> FONT_PREVIEWS = new WeakHashMap<>();
    private static final Map<TextView, Boolean> INTENTIONAL_MONOSPACE = new WeakHashMap<>();
    private static final Map<TextView, Boolean> TYPOGRAPHY_APPLIED = new WeakHashMap<>();
    private static final Map<View, Boolean> TYPOGRAPHY_WATCHED = new WeakHashMap<>();
    private static final Map<AppearanceListener, Boolean> APPEARANCE_LISTENERS = new WeakHashMap<>();

    private UiKit() {}

    /**
     * Sync the process-wide Orbit canvas to the saved appearance preference.
     * AMOLED mode uses true black for large background areas while keeping
     * raised surfaces intact for readable card separation.
     */
    public static void syncTheme(Context c) {
        BG = c != null && Prefs.amoledMode(c) ? Color.BLACK : NORMAL_BG;
    }

    /** Shared signal used by active Orbit Settings surfaces after an appearance preference changes. */
    public interface AppearanceListener {
        void onOrbitAppearanceChanged();
    }

    public static String appearanceSignature(Context c) {
        if (c == null) return "";
        return Prefs.get(c).getString(Prefs.ACCENT, "dynamic") +
                "|resolved=" + Integer.toHexString(accent(c)) +
                "|amoled=" + Prefs.amoledMode(c) +
                "|font=" + Prefs.appFont(c) +
                "|userBubble=" + Prefs.userBubbleColor(c) +
                "|assistantBubble=" + Prefs.assistantBubbleColor(c);
    }

    /**
     * The part of the appearance that is baked into views when they are built.
     *
     * <p>Accent and AMOLED both change {@code UiKit}'s shared colour constants and the drawables
     * created from them, so a surface has to rebuild to pick them up. App font and conversation
     * bubble colours are deliberately excluded: the font can be re-applied to the views already on
     * screen, and bubble colours do not appear in Settings at all. Keeping them out of this
     * signature is what stops routine appearance changes from replacing the whole screen.
     */
    public static String structuralAppearanceSignature(Context c) {
        if (c == null) return "";
        return "resolved=" + Integer.toHexString(accent(c)) + "|amoled=" + Prefs.amoledMode(c);
    }

    /** Shared appearance/provider presentation catalogs used by Settings and onboarding. */
    public static String[] accentKeys() { return ACCENT_KEYS.clone(); }
    public static String[] accentLabels() { return ACCENT_LABELS.clone(); }
    public static String[] bubbleColorKeys() { return BUBBLE_COLOR_KEYS.clone(); }
    public static String[] bubbleColorLabels() { return BUBBLE_COLOR_LABELS.clone(); }
    public static String[] providerKeys() { return PROVIDER_KEYS.clone(); }
    public static String[] providerLabels() { return PROVIDER_LABELS.clone(); }

    public static void registerAppearanceListener(AppearanceListener listener) {
        if (listener == null) return;
        synchronized (APPEARANCE_LISTENERS) {
            APPEARANCE_LISTENERS.put(listener, true);
        }
    }

    public static void unregisterAppearanceListener(AppearanceListener listener) {
        if (listener == null) return;
        synchronized (APPEARANCE_LISTENERS) {
            APPEARANCE_LISTENERS.remove(listener);
        }
    }

    public static void notifyAppearanceChanged(Context c) {
        syncTheme(c);
        OrbitWidgets.updateAll(c);
        List<AppearanceListener> listeners;
        synchronized (APPEARANCE_LISTENERS) {
            listeners = new ArrayList<>(APPEARANCE_LISTENERS.keySet());
        }
        Runnable notify = () -> {
            for (AppearanceListener listener : listeners) {
                if (listener != null) listener.onOrbitAppearanceChanged();
            }
        };
        // Post so popup-menu selection and checkbox callbacks can finish cleanly
        // before an active Settings surface replaces its view hierarchy.
        new Handler(Looper.getMainLooper()).post(notify);
    }

    /** Current-accent tint for Orbit-owned checkbox/radio controls. */
    public static ColorStateList accentControlTint(Context c) {
        return new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{accent(c), Color.rgb(90, 94, 105)});
    }

    private static volatile Typeface SYSTEM_LIGHT_BASE;
    private static volatile Typeface SYSTEM_MONO_BASE;

    /**
     * Build a Typeface from an actual font file registered by Android instead of
     * trusting an OEM family alias. Samsung can remap generic aliases to the user's
     * normal system face, which made Light and Monospace look identical to Orbit
     * Default on-device. Orbit targets/minSdk 29, so SystemFonts is always available.
     */
    private static Typeface systemTypefaceFromRegisteredFont(boolean monospace) {
        try {
            Set<Font> fonts = SystemFonts.getAvailableFonts();
            if (fonts == null || fonts.isEmpty()) return null;

            // Run ordered passes so common Latin Android faces win before broad
            // fallbacks. We use the real registered Font object, preserving TTC
            // indices and avoiding hard-coded /system font paths.
            String[][] patterns = monospace
                    ? new String[][]{
                    {"roboto", "mono"}, {"droidsansmono"}, {"notosansmono"},
                    {"sourcecodepro"}, {"cutivemono"}, {"mono"}
            }
                    : new String[][]{
                    {"roboto", "light"}, {"roboto", "thin"},
                    {"notosans", "light"}, {"notosans", "thin"},
                    {"sans", "light"}
            };

            for (String[] required : patterns) {
                Font best = null;
                int bestWeightDistance = Integer.MAX_VALUE;
                for (Font font : fonts) {
                    File file = font.getFile();
                    if (file == null) continue;
                    String name = file.getName().toLowerCase(Locale.US).replace("-", "").replace("_", "");
                    boolean matches = true;
                    for (String token : required) {
                        if (!name.contains(token.replace("-", "").replace("_", ""))) {
                            matches = false;
                            break;
                        }
                    }
                    if (!matches) continue;

                    // Prefer an upright regular-ish mono face and a genuinely light
                    // face. This keeps the result readable while still visually clear.
                    int targetWeight = monospace ? 400 : 300;
                    int distance = Math.abs(font.getStyle().getWeight() - targetWeight);
                    if (best == null || distance < bestWeightDistance) {
                        best = font;
                        bestWeightDistance = distance;
                    }
                }
                if (best != null) {
                    FontFamily family = new FontFamily.Builder(best).build();
                    return new Typeface.CustomFallbackBuilder(family)
                            .setSystemFallback(monospace ? "monospace" : "sans-serif")
                            .build();
                }
            }
        } catch (Throwable ignored) {
            // The generic fallback below keeps Orbit usable on unusual OEM builds.
        }
        return null;
    }

    private static Typeface lightBase() {
        Typeface cached = SYSTEM_LIGHT_BASE;
        if (cached != null) return cached;
        synchronized (UiKit.class) {
            if (SYSTEM_LIGHT_BASE == null) {
                Typeface resolved = systemTypefaceFromRegisteredFont(false);
                SYSTEM_LIGHT_BASE = resolved != null
                        ? resolved
                        : Typeface.create("sans-serif-thin", Typeface.NORMAL);
            }
            return SYSTEM_LIGHT_BASE;
        }
    }

    private static Typeface monoBase() {
        Typeface cached = SYSTEM_MONO_BASE;
        if (cached != null) return cached;
        synchronized (UiKit.class) {
            if (SYSTEM_MONO_BASE == null) {
                Typeface resolved = systemTypefaceFromRegisteredFont(true);
                SYSTEM_MONO_BASE = resolved != null ? resolved : Typeface.MONOSPACE;
            }
            return SYSTEM_MONO_BASE;
        }
    }

    /** Resolve a specific Orbit interface font choice without bundling font assets. */
    public static Typeface typefaceForFontChoice(String choice, int style) {
        String resolved = choice == null ? "orbit_default" : choice;
        if ("times_new_roman".equals(resolved)) {
            return Typeface.create(Typeface.SERIF, style);
        }
        if ("light".equals(resolved)) {
            return Typeface.create(lightBase(), style);
        }
        if ("condensed".equals(resolved)) {
            return Typeface.create("sans-serif-condensed", style);
        }
        if ("monospace".equals(resolved)) {
            return Typeface.create(monoBase(), style);
        }
        if ("casual".equals(resolved)) {
            // Restore the Samsung/OEM casual family used in v0.6.3.10. On the
            // target Galaxy this is the cleaner handwritten face the user preferred.
            return Typeface.create("casual", style);
        }
        return Typeface.create(Typeface.SANS_SERIF, style);
    }

    /** Resolve Orbit's currently saved interface font. */
    public static Typeface appTypeface(Context c, int style) {
        return typefaceForFontChoice(c == null ? "orbit_default" : Prefs.appFont(c), style);
    }

    public static float textScaleXForFontChoice(String choice) {
        // Keep Condensed visibly distinct even on OEM builds that alias the
        // sans-serif-condensed family back to the standard sans face.
        return "condensed".equals(choice) ? 0.88f : 1.0f;
    }

    public static float letterSpacingForFontChoice(String choice) {
        // Small spacing differences reinforce the intended families without
        // distorting layout. Monospace gets a subtle code-like rhythm; Light gets
        // a touch of air so it cannot visually collapse into Orbit Default.
        if ("monospace".equals(choice)) return 0.035f;
        if ("light".equals(choice)) return 0.012f;
        return 0.0f;
    }

    private static float appTextScaleX(Context c) {
        return textScaleXForFontChoice(c == null ? "orbit_default" : Prefs.appFont(c));
    }

    private static float appLetterSpacing(Context c) {
        return letterSpacingForFontChoice(c == null ? "orbit_default" : Prefs.appFont(c));
    }

    private static void applyTypography(TextView text, int style) {
        if (text == null) return;
        if (INTENTIONAL_MONOSPACE.containsKey(text)) {
            text.setTypeface(Typeface.MONOSPACE, style);
            text.setTextScaleX(1f);
            text.setLetterSpacing(0f);
            TYPOGRAPHY_APPLIED.put(text, true);
            return;
        }
        FontPreview preview = FONT_PREVIEWS.get(text);
        if (preview != null) {
            text.setTypeface(typefaceForFontChoice(preview.choice, preview.style));
            text.setTextScaleX(textScaleXForFontChoice(preview.choice));
            text.setLetterSpacing(letterSpacingForFontChoice(preview.choice));
        } else {
            text.setTypeface(appTypeface(text.getContext(), style));
            text.setTextScaleX(appTextScaleX(text.getContext()));
            text.setLetterSpacing(appLetterSpacing(text.getContext()));
        }
        TYPOGRAPHY_APPLIED.put(text, true);
    }

    /** Marks a Look & Feel sample as an intentional font preview. */
    public static void applyFontPreview(TextView text, String choice, int style) {
        if (text == null) return;
        FONT_PREVIEWS.put(text, new FontPreview(choice, style));
        applyTypography(text, style);
    }

    /** Preserve intentional code typography while global Orbit font watchers run. */
    public static void applyCodeTypeface(TextView text) {
        if (text == null) return;
        INTENTIONAL_MONOSPACE.put(text, true);
        applyTypography(text, Typeface.NORMAL);
    }

    /** Apply the saved Orbit font to every text-bearing child in a rendered surface. */
    public static void applyTypography(View root) {
        if (root == null) return;
        if (root instanceof TextView) {
            TextView text = (TextView) root;
            Typeface current = text.getTypeface();
            int style = current == null ? Typeface.NORMAL : current.getStyle();
            applyTypography(text, style);
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) applyTypography(group.getChildAt(i));
        }
    }

    /**
     * Keeps programmatically added Orbit controls on the selected app font. The
     * watcher is installed only on Orbit-owned Activity/dialog roots, never on
     * system pickers, permission panels, or other Android-owned windows.
     */
    public static void watchTypography(View root) {
        if (root == null) return;
        applyTypography(root);
        if (TYPOGRAPHY_WATCHED.put(root, true) != null) return;

        ViewTreeObserver.OnGlobalLayoutListener listener = () -> applyTypographyToNewViews(root);
        root.getViewTreeObserver().addOnGlobalLayoutListener(listener);
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}

            @Override public void onViewDetachedFromWindow(View v) {
                ViewTreeObserver observer = v.getViewTreeObserver();
                if (observer.isAlive()) observer.removeOnGlobalLayoutListener(listener);
                TYPOGRAPHY_WATCHED.remove(v);
                v.removeOnAttachStateChangeListener(this);
            }
        });
    }

    private static void applyTypographyToNewViews(View root) {
        if (root == null) return;
        if (root instanceof TextView && !TYPOGRAPHY_APPLIED.containsKey(root)) {
            TextView text = (TextView) root;
            Typeface current = text.getTypeface();
            applyTypography(text, current == null ? Typeface.NORMAL : current.getStyle());
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyTypographyToNewViews(group.getChildAt(i));
            }
        }
    }

    /** Applies Orbit's established window motion before an app-owned dialog is shown. */
    public static void prepareOrbitDialog(AlertDialog dialog, Drawable background) {
        if (dialog == null || dialog.getWindow() == null) return;
        Window window = dialog.getWindow();
        window.setWindowAnimations(R.style.OrbitPopupAnimation);
        if (background != null) window.setBackgroundDrawable(background);
        window.getDecorView().setForceDarkAllowed(false);
        watchTypography(window.getDecorView());
    }

    /** Immediately applies the selected font after Android inflates dialog actions/title views. */
    public static void applyDialogTypography(AlertDialog dialog) {
        if (dialog == null || dialog.getWindow() == null) return;
        watchTypography(dialog.getWindow().getDecorView());
        applyTypography(dialog.getWindow().getDecorView());
    }

    /** Applies Orbit's readable dialog foregrounds without flattening custom-view hierarchy. */
    public static void applyOrbitDialogColors(AlertDialog dialog, Context c) {
        if (dialog == null) return;
        if (dialog.getWindow() != null) {
            ensureReadableDialogText(dialog.getWindow().getDecorView());
        }
        TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) message.setTextColor(TEXT);
        if (c != null) {
            int titleId = c.getResources().getIdentifier("alertTitle", "id", "android");
            if (titleId != 0) {
                TextView title = dialog.findViewById(titleId);
                if (title != null) title.setTextColor(TEXT);
            }
        }
        int actionColor = accent(c);
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        Button neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (positive != null) positive.setTextColor(actionColor);
        if (negative != null) negative.setTextColor(actionColor);
        if (neutral != null) neutral.setTextColor(actionColor);
    }

    /**
     * One hardened styling path for normal Orbit-owned dialogs. It owns the
     * surface, motion, Force Dark behavior, selected typography, readable text,
     * and action colors so callers cannot accidentally omit one of those pieces.
     */
    public static void styleOrbitDialog(AlertDialog dialog, Context c, boolean destructive) {
        styleOrbitDialog(dialog, c, destructive, null);
    }

    public static void styleOrbitDialog(AlertDialog dialog, Context c, boolean destructive,
                                        Runnable afterShown) {
        styleOrbitDialog(dialog, c, destructive, rounded(SURFACE, 22, c), -1f, afterShown);
    }

    /** Shared styling with an optional Orbit-owned surface variant and dim strength. */
    public static void styleOrbitDialog(AlertDialog dialog, Context c, boolean destructive,
                                        Drawable background, float dimAmount,
                                        Runnable afterShown) {
        if (dialog == null || c == null) return;
        prepareOrbitDialog(dialog, background);
        if (dimAmount >= 0f && dialog.getWindow() != null) {
            dialog.getWindow().setDimAmount(dimAmount);
        }
        dialog.setOnShowListener(ignore -> {
            applyDialogTypography(dialog);
            applyOrbitDialogColors(dialog, c);
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (destructive && positive != null) {
                positive.setTextColor(Color.rgb(239, 105, 105));
            }
            if (afterShown != null) afterShown.run();
        });
    }

    /** Corrects only low-contrast inherited OEM colors, preserving readable accent/muted text. */
    private static void ensureReadableDialogText(View view) {
        if (view == null) return;
        if (view instanceof TextView &&
                (!(view instanceof Button) || view instanceof CompoundButton)) {
            TextView text = (TextView) view;
            if (contrastRatio(text.getCurrentTextColor(), SURFACE) < 3.0d) {
                text.setTextColor(TEXT);
            }
            if (text.getHint() != null && contrastRatio(
                    text.getHintTextColors().getDefaultColor(), SURFACE) < 3.0d) {
                text.setHintTextColor(MUTED);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ensureReadableDialogText(group.getChildAt(i));
            }
        }
    }

    /** WCAG-style contrast ratio between two opaque colors, 1.0 when identical. */
    public static double contrastRatio(int foreground, int background) {
        double lighter = Math.max(relativeLuminance(foreground), relativeLuminance(background));
        double darker = Math.min(relativeLuminance(foreground), relativeLuminance(background));
        return (lighter + 0.05d) / (darker + 0.05d);
    }

    private static double relativeLuminance(int color) {
        double r = linearChannel(Color.red(color) / 255d);
        double g = linearChannel(Color.green(color) / 255d);
        double b = linearChannel(Color.blue(color) / 255d);
        return 0.2126d * r + 0.7152d * g + 0.0722d * b;
    }

    private static double linearChannel(double channel) {
        return channel <= 0.04045d ? channel / 12.92d :
                Math.pow((channel + 0.055d) / 1.055d, 2.4d);
    }

    /** Orbit-styled determinate progress for measurable foreground work. */
    public static ProgressBar horizontalProgress(Context c) {
        ProgressBar bar = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        GradientDrawable track = rounded(SURFACE_3, 99, c);
        GradientDrawable fill = rounded(accent(c), 99, c);
        ClipDrawable clippedFill = new ClipDrawable(
                fill, android.view.Gravity.START, ClipDrawable.HORIZONTAL);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{track, clippedFill});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        bar.setProgressDrawable(layers);
        bar.setIndeterminate(false);
        bar.setMax(100);
        bar.setProgress(0);
        bar.setMinimumHeight(0);
        bar.setMinimumWidth(0);
        return bar;
    }

    private static final class FontPreview {
        final String choice;
        final int style;

        FontPreview(String choice, int style) {
            this.choice = choice == null ? "orbit_default" : choice;
            this.style = style;
        }
    }


    private static GradientDrawable orbitCircle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    public static int orbitShellColor() {
        return Color.rgb(17, 19, 26);
    }

    public static int orbitCoreColor() {
        return BG;
    }

    public static int orbitSatelliteColor(Context c) {
        return blend(accent(c), Color.WHITE, 0.16f);
    }

    /**
     * Accent-aware Orbit brand mark used in headers and the overlay. Geometry is
     * intentionally identical to res/drawable/ic_orbit.xml so the in-app mark
     * always looks like the actual Orbit app icon. Only the brand ring (and the
     * very subtle satellite tint) follows the user's selected accent.
     */
    public static View orbitMark(Context c, float sizeDp) {
        syncTheme(c);
        final int markSize = dp(c, sizeDp);
        final int shellColor = orbitShellColor();

        View mark = new View(c) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);

                // ic_orbit.xml uses a 108 x 108 viewport. Draw those exact same
                // circles inside the requested mark size, centered in the host view.
                float viewportScale = markSize / 108f;
                float left = (getWidth() - markSize) / 2f;
                float top = (getHeight() - markSize) / 2f;

                paint.setColor(shellColor);
                canvas.drawCircle(left + 54f * viewportScale, top + 54f * viewportScale,
                        50f * viewportScale, paint);

                // Resolve theme colors at draw time so an already-visible Orbit mark
                // reacts immediately when Settings changes the accent.
                paint.setColor(accent(c));
                canvas.drawCircle(left + 54f * viewportScale, top + 54f * viewportScale,
                        32f * viewportScale, paint);

                // The app icon uses the same dark fill for its shell and inner core.
                paint.setColor(shellColor);
                canvas.drawCircle(left + 54f * viewportScale, top + 54f * viewportScale,
                        20f * viewportScale, paint);

                paint.setColor(orbitSatelliteColor(c));
                canvas.drawCircle(left + 78f * viewportScale, top + 34f * viewportScale,
                        7f * viewportScale, paint);
            }
        };
        mark.setTag("orbit_mark");
        return mark;
    }


    /**
     * Applies Android system-bar/display-cutout insets to an Activity content view
     * while preserving the view's design padding. When imeAware is true, the bottom
     * padding tracks the larger of the navigation bar and IME so composers stay
     * above Samsung Keyboard on edge-to-edge Android 15/16 windows.
     */
    public static void applyActivityInsets(Activity activity, View root, boolean imeAware) {
        if (activity == null || root == null) return;
        syncTheme(activity);
        applyPageTransition(activity);
        watchTypography(root);
        Window window = activity.getWindow();
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
        window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false);

        final int baseLeft = root.getPaddingLeft();
        final int baseTop = root.getPaddingTop();
        final int baseRight = root.getPaddingRight();
        final int baseBottom = root.getPaddingBottom();

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = imeAware ? Math.max(bars.bottom, ime.bottom) : bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(baseLeft + left, baseTop + top, baseRight + right, baseBottom + bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    public static int dp(Context c, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, c.getResources().getDisplayMetrics()));
    }

    public static int sp(Context c, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, c.getResources().getDisplayMetrics()));
    }

    public static int accent(Context c) {
        String chosen = Prefs.get(c).getString(Prefs.ACCENT, "dynamic");
        return accentForName(c, chosen);
    }

    public static int accentForName(Context c, String chosen) {
        if ("blurple".equals(chosen)) return BLURPLE;
        if ("violet".equals(chosen)) return Color.rgb(139, 124, 255);
        if ("blue".equals(chosen)) return Color.rgb(80, 151, 255);
        if ("mint".equals(chosen)) return Color.rgb(69, 204, 166);
        if ("rose".equals(chosen)) return Color.rgb(244, 110, 150);
        if ("nova".equals(chosen)) return Color.rgb(76, 0, 255);
        if ("pastel_pink".equals(chosen)) return PASTEL_PINK;
        if ("pastel_blue".equals(chosen)) return PASTEL_BLUE;
        if (Build.VERSION.SDK_INT >= 31) {
            int id = c.getResources().getIdentifier("system_accent1_500", "color", "android");
            if (id != 0) {
                try { return c.getColor(id); } catch (Exception ignored) {}
            }
        }
        return DEFAULT_ACCENT;
    }

    /**
     * Foreground color for controls filled with the current accent. Light pastel
     * accents need dark content to preserve contrast; darker accents use white.
     */
    public static int onAccent(int accent) {
        double luminance = (0.2126 * Color.red(accent) + 0.7152 * Color.green(accent) + 0.0722 * Color.blue(accent)) / 255.0;
        return luminance > 0.68 ? Color.rgb(22, 25, 33) : Color.WHITE;
    }

    public static int onAccent(Context c) {
        return onAccent(accent(c));
    }

    /** Resolve a conversation bubble preference while preserving Orbit's classic defaults. */
    public static int bubbleFill(Context c, String choice, int classicFill) {
        if (choice == null || "classic".equals(choice)) return classicFill;
        if ("accent".equals(choice)) return accent(c);
        return accentForName(c, choice);
    }

    public static int userBubbleFill(Context c, int classicFill) {
        return bubbleFill(c, Prefs.userBubbleColor(c), classicFill);
    }

    public static int assistantBubbleFill(Context c, int classicFill) {
        return bubbleFill(c, Prefs.assistantBubbleColor(c), classicFill);
    }

    /** Text color chosen for readable contrast on a custom bubble fill. */
    public static int onBubble(int fill) {
        double luminance = (0.2126 * Color.red(fill) + 0.7152 * Color.green(fill) + 0.0722 * Color.blue(fill)) / 255.0;
        return luminance > 0.62 ? Color.rgb(24, 27, 34) : TEXT;
    }


    /**
     * Minimum contrast a link must reach against the surface it sits on. Matches the threshold
     * {@link #syncTheme} already uses when correcting inherited OEM colours.
     */
    private static final double LINK_MIN_CONTRAST = 3.0d;

    /**
     * A readable link colour for text drawn on {@code background}.
     *
     * <p>Links used to be painted with the raw accent regardless of what was behind them. With a
     * purple accent and a purple assistant bubble that is accent-on-accent, and the link becomes
     * effectively invisible. The accent is still preferred whenever it actually reads; only when
     * it does not is the colour moved, and then it is moved as little as possible so a link still
     * looks like part of Orbit rather than a generic fallback.
     *
     * <p>Colour is never the only cue: inline links keep their underline, so a link remains
     * identifiable even where hue alone would not carry it.
     */
    public static int linkColorOn(Context c, int background) {
        int accent = accent(c);
        if (contrastRatio(accent, background) >= LINK_MIN_CONTRAST) return accent;

        // Walk from mostly-accent toward the readable foreground for this surface, stopping at
        // the first mix that reads clearly. This keeps some accent character where it can.
        int readable = bestInkOn(background);
        for (float accentShare = 0.75f; accentShare >= 0.15f; accentShare -= 0.15f) {
            int mixed = blend(accent, readable, accentShare);
            if (contrastRatio(mixed, background) >= LINK_MIN_CONTRAST) return mixed;
        }
        return readable;
    }

    /**
     * Whichever of Orbit's dark or light ink actually contrasts more with this surface.
     * {@link #onBubble} picks by a single luminance cutoff, which leaves mid-luminance colours
     * such as the rose accent with the weaker of the two.
     */
    private static int bestInkOn(int background) {
        int darkInk = Color.rgb(18, 20, 26);
        int lightInk = Color.rgb(240, 243, 250);
        return contrastRatio(darkInk, background) >= contrastRatio(lightInk, background)
                ? darkInk : lightInk;
    }

    public static int blend(int a, int b, float amountA) {
        float t = Math.max(0, Math.min(1, amountA));
        int r = Math.round(Color.red(a) * t + Color.red(b) * (1 - t));
        int g = Math.round(Color.green(a) * t + Color.green(b) * (1 - t));
        int bl = Math.round(Color.blue(a) * t + Color.blue(b) * (1 - t));
        return Color.rgb(r, g, bl);
    }

    public static int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)), Color.red(color), Color.green(color), Color.blue(color));
    }

    public static GradientDrawable rounded(int color, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static GradientDrawable gradientSheet(Context c) {
        return gradientSheet(c, 30f);
    }

    public static GradientDrawable gradientSheet(Context c, float radiusDp) {
        int accent = accent(c);
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{blend(accent, BG, 0.055f), BG});
        d.setCornerRadius(dp(c, Math.max(0f, radiusDp)));
        d.setStroke(dp(c, 1), withAlpha(blend(accent, Color.WHITE, 0.32f), 58));
        return d;
    }

    public static GradientDrawable outlined(int fill, int stroke, float radiusDp, Context c) {
        GradientDrawable d = rounded(fill, radiusDp, c);
        d.setStroke(dp(c, 1), stroke);
        return d;
    }

    public static Drawable ripple(int fill, int rippleColor, float radiusDp, Context c) {
        GradientDrawable content = rounded(fill, radiusDp, c);
        GradientDrawable mask = rounded(Color.WHITE, radiusDp, c);
        return new RippleDrawable(ColorStateList.valueOf(withAlpha(rippleColor, 56)), content, mask);
    }

    public static Drawable rippleOutlined(int fill, int stroke, int rippleColor, float radiusDp, Context c) {
        GradientDrawable content = outlined(fill, stroke, radiusDp, c);
        GradientDrawable mask = rounded(Color.WHITE, radiusDp, c);
        return new RippleDrawable(ColorStateList.valueOf(withAlpha(rippleColor, 56)), content, mask);
    }

    public interface OrbitMenuChoice {
        void onChoice(int index, String label);
    }

    /**
     * Orbit's dark popup menu used instead of Android's stock white PopupMenu.
     * selectedIndex < 0 creates a normal action menu; otherwise the active row
     * gets an accent dot and accent-tinted surface.
     */
    public static void showOrbitMenu(Context c, View anchor, String[] labels,
                                     int selectedIndex, OrbitMenuChoice choice) {
        showOrbitMenuInternal(c, anchor, labels, null, selectedIndex, -1, choice);
    }

    /**
     * Action-menu variant for a row that opens an Orbit dialog. That one callback
     * begins only after the shared popup exit motion finishes, preventing two
     * independently positioned windows from fading/scaling over each other.
     */
    public static void showOrbitMenuWithDialogHandoff(Context c, View anchor, String[] labels,
                                                       int dialogIndex, OrbitMenuChoice choice) {
        showOrbitMenuInternal(c, anchor, labels, null, -1, dialogIndex, choice);
    }

    /** Font-picker variant whose labels preview the font they represent. */
    public static void showOrbitFontMenu(Context c, View anchor, String[] fontKeys, String[] labels,
                                         int selectedIndex, OrbitMenuChoice choice) {
        if (fontKeys == null || labels == null || fontKeys.length != labels.length) {
            showOrbitMenu(c, anchor, labels, selectedIndex, choice);
            return;
        }
        showOrbitMenuInternal(c, anchor, labels, fontKeys, selectedIndex, -1, choice);
    }

    private static void showOrbitMenuInternal(Context c, View anchor, String[] labels,
                                              String[] previewFontKeys, int selectedIndex,
                                              int dialogHandoffIndex, OrbitMenuChoice choice) {
        if (c == null || anchor == null || labels == null || labels.length == 0) return;

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 6));
        box.setBackground(outlined(SURFACE_2, withAlpha(accent(c), 72), 18, c));

        int maxChars = 0;
        for (String label : labels) {
            if (label != null) maxChars = Math.max(maxChars, label.length());
        }
        // Compact enough for the strength selector, but still wide enough for
        // actions such as "Rename chat". Avoid the oversized stock-like panel.
        int widthDp = Math.max(180, Math.min(300, 132 + (maxChars * 7)));
        int width = dp(c, widthDp);
        PopupWindow popup = new PopupWindow(box, width,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setTouchable(true);
        popup.setClippingEnabled(true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setAnimationStyle(R.style.OrbitPopupAnimation);
        if (Build.VERSION.SDK_INT >= 21) popup.setElevation(dp(c, 12));

        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            final String labelText = labels[i];
            boolean selected = i == selectedIndex;

            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(c, 13), 0, dp(c, 10), 0);
            row.setBackground(ripple(
                    selected ? blend(accent(c), SURFACE_2, 0.16f) : SURFACE_2,
                    accent(c), 13, c));

            TextView label = text(c, labelText, 14, TEXT, selected);
            if (previewFontKeys != null) {
                String previewKey = previewFontKeys[i];
                // Font-choice rows are previews first; keep every label at its
                // normal face so the selected row does not disguise Light or
                // otherwise change the font the user is trying to compare.
                applyFontPreview(label, previewKey, Typeface.NORMAL);
            }
            row.addView(label, new LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            TextView marker = text(c, selected ? "●" : "", 14, accent(c), true);
            marker.setGravity(android.view.Gravity.CENTER);
            row.addView(marker, new LinearLayout.LayoutParams(dp(c, 26),
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT));

            pressScale(row);
            row.setOnClickListener(v -> {
                if (choice == null) {
                    popup.dismiss();
                    return;
                }
                if (index == dialogHandoffIndex) {
                    // Drive this one exit in the popup's own content hierarchy and
                    // hand off from the real animation-completion callback. The
                    // transparent window is dismissed before the dialog is posted,
                    // avoiding Samsung WindowManager timing guesses and overlap.
                    popup.setTouchable(false);
                    box.animate().cancel();
                    box.setPivotX(box.getWidth() / 2f);
                    box.setPivotY(box.getHeight() / 2f);
                    box.animate()
                            .alpha(0f)
                            .scaleX(0.992f)
                            .scaleY(0.992f)
                            .setDuration(ORBIT_POPUP_EXIT_MS)
                            .setInterpolator(new AccelerateInterpolator())
                            .withEndAction(() -> {
                                // The content has already completed its exit. Do not
                                // ask WindowManager to run a second popup exit while
                                // the confirmation dialog begins.
                                popup.setAnimationStyle(0);
                                popup.dismiss();
                                anchor.postOnAnimation(
                                        () -> choice.onChoice(index, labelText));
                            })
                            .start();
                } else {
                    popup.dismiss();
                    choice.onChoice(index, labelText);
                }
            });

            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 44));
            if (i > 0) rowLp.topMargin = dp(c, 2);
            box.addView(row, rowLp);
        }

        showAnchoredOrbitPopup(c, anchor, popup, width, labels.length, 44);
    }

    /**
     * Compact icon+label action menu for a small set of choices, such as the actions on a held
     * message. It shares {@link #showOrbitMenu}'s accent, motion, and screen-aware placement, and
     * is deliberately a softer, more finished surface than a utility list: an accent-warmed sheet
     * on Orbit's largest corner radius, each action's icon on its own accent chip, and generous
     * touch rows. It does not take input focus, so the keyboard is left alone.
     */
    public static PopupWindow showOrbitActionMenu(Context c, View anchor, String[] labels, int[] icons,
                                           OrbitMenuChoice choice) {
        if (c == null || anchor == null || labels == null || labels.length == 0) return null;
        boolean showIcons = icons != null && icons.length == labels.length;
        int accent = accent(c);
        int rowHeightDp = 46;

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 6));
        box.setBackground(actionMenuSheet(c, accent));

        int maxChars = 0;
        for (String label : labels) {
            if (label != null) maxChars = Math.max(maxChars, label.length());
        }
        int widthDp = Math.max(170, Math.min(258, 100 + (showIcons ? 36 : 0) + (maxChars * 7)));
        int width = dp(c, widthDp);
        PopupWindow popup = new PopupWindow(box, width,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, false);
        popup.setOutsideTouchable(true);
        popup.setTouchable(true);
        popup.setFocusable(false);
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        popup.setClippingEnabled(true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setAnimationStyle(R.style.OrbitPopupAnimation);
        if (Build.VERSION.SDK_INT >= 21) popup.setElevation(dp(c, 14));

        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            final String labelText = labels[i];

            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(c, 8), 0, dp(c, 14), 0);
            // Transparent fill so the sheet's accent warmth reads through the row rather than
            // being covered by a second flat surface.
            row.setBackground(ripple(Color.TRANSPARENT, accent, 15, c));

            if (showIcons && icons[i] != 0) {
                ImageView icon = new ImageView(c);
                icon.setImageResource(icons[i]);
                icon.setImageTintList(ColorStateList.valueOf(accent));
                icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                icon.setBackground(rounded(withAlpha(accent, 34), 11, c));
                icon.setPadding(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 6));
                row.addView(icon, new LinearLayout.LayoutParams(dp(c, 30), dp(c, 30)));
            }

            TextView label = text(c, labelText, 14.5f, TEXT, false);
            label.setSingleLine(true);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (showIcons && icons[i] != 0) labelLp.setMargins(dp(c, 11), 0, 0, 0);
            row.addView(label, labelLp);

            pressScale(row);
            row.setOnClickListener(v -> {
                popup.dismiss();
                if (choice != null) choice.onChoice(index, labelText);
            });

            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(c, rowHeightDp));
            if (i > 0) rowLp.topMargin = dp(c, 2);
            box.addView(row, rowLp);
        }

        showAnchoredOrbitPopup(c, anchor, popup, width, labels.length, rowHeightDp);
        return popup;
    }

    /**
     * The action menu's own surface: Orbit's raised panel warmed towards the accent at the top,
     * with a hairline accent edge. Built from the resolved accent so AMOLED, Light, Dynamic, and
     * custom accents all produce a sheet that belongs to the current appearance.
     */
    private static GradientDrawable actionMenuSheet(Context c, int accent) {
        GradientDrawable sheet = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{blend(accent, SURFACE_2, 0.12f), SURFACE_2});
        sheet.setCornerRadius(dp(c, 20));
        sheet.setStroke(dp(c, 1), withAlpha(accent, 96));
        return sheet;
    }

    /**
     * Orbit's themed color-choice popup. It uses the same dark menu language as
     * showOrbitMenu, while adding a compact color swatch to make long color lists
     * easier to scan without returning to a large grid.
     */
    public static void showOrbitColorMenu(Context c, View anchor, String[] labels, int[] colors,
                                          int selectedIndex, OrbitMenuChoice choice) {
        if (c == null || anchor == null || labels == null || labels.length == 0) return;
        if (colors == null || colors.length != labels.length) {
            showOrbitMenu(c, anchor, labels, selectedIndex, choice);
            return;
        }

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 6));
        box.setBackground(outlined(SURFACE_2, withAlpha(accent(c), 72), 18, c));

        int maxChars = 0;
        for (String label : labels) if (label != null) maxChars = Math.max(maxChars, label.length());
        int widthDp = Math.max(210, Math.min(300, 150 + (maxChars * 7)));
        int width = dp(c, widthDp);
        PopupWindow popup = new PopupWindow(box, width,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setTouchable(true);
        popup.setClippingEnabled(true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setAnimationStyle(R.style.OrbitPopupAnimation);
        if (Build.VERSION.SDK_INT >= 21) popup.setElevation(dp(c, 12));

        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            final String labelText = labels[i];
            final int swatchColor = colors[i];
            boolean selected = i == selectedIndex;

            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(c, 12), 0, dp(c, 10), 0);
            row.setBackground(ripple(
                    selected ? blend(accent(c), SURFACE_2, 0.16f) : SURFACE_2,
                    accent(c), 13, c));

            TextView swatch = text(c, "●", 18, swatchColor, true);
            swatch.setGravity(android.view.Gravity.CENTER);
            row.addView(swatch, new LinearLayout.LayoutParams(dp(c, 30),
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT));

            TextView label = text(c, labelText, 14, TEXT, selected);
            row.addView(label, new LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            TextView markerView = text(c, selected ? "✓" : "", 15, accent(c), true);
            markerView.setGravity(android.view.Gravity.CENTER);
            row.addView(markerView, new LinearLayout.LayoutParams(dp(c, 28),
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT));

            pressScale(row);
            row.setOnClickListener(v -> {
                popup.dismiss();
                if (choice != null) choice.onChoice(index, labelText);
            });

            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 42));
            if (i > 0) rowLp.topMargin = dp(c, 2);
            box.addView(row, rowLp);
        }

        showAnchoredOrbitPopup(c, anchor, popup, width, labels.length, 42);
    }

    private static void showAnchoredOrbitPopup(Context c, View anchor, PopupWindow popup,
                                                int width, int rowCount, int rowHeightDp) {
        try {
            View windowRoot = anchor.getRootView();
            if (windowRoot == null) throw new IllegalStateException("No popup root");

            Rect visibleFrame = new Rect();
            anchor.getWindowVisibleDisplayFrame(visibleFrame);
            if (visibleFrame.width() <= 0 || visibleFrame.height() <= 0) {
                int sw = c.getResources().getDisplayMetrics().widthPixels;
                int sh = c.getResources().getDisplayMetrics().heightPixels;
                visibleFrame.set(0, 0, sw, sh);
            }

            int[] anchorScreen = new int[2];
            int[] rootScreen = new int[2];
            anchor.getLocationOnScreen(anchorScreen);
            windowRoot.getLocationOnScreen(rootScreen);

            int margin = dp(c, 12);
            int gap = dp(c, 6);
            int popupHeight = dp(c, 12) + (rowCount * dp(c, rowHeightDp)) +
                    (Math.max(0, rowCount - 1) * dp(c, 2));

            int safeLeft = visibleFrame.left + margin;
            int safeRight = visibleFrame.right - margin;
            int safeTop = visibleFrame.top + margin;
            int safeBottom = visibleFrame.bottom - margin;

            Rect anchorVisible = new Rect(
                    anchorScreen[0],
                    anchorScreen[1],
                    anchorScreen[0] + Math.max(1, anchor.getWidth()),
                    anchorScreen[1] + Math.max(1, anchor.getHeight()));
            if (!anchorVisible.intersect(visibleFrame)) {
                anchorVisible.set(
                        Math.max(visibleFrame.left, anchorScreen[0]),
                        Math.max(visibleFrame.top, anchorScreen[1]),
                        Math.min(visibleFrame.right, anchorScreen[0] + Math.max(1, anchor.getWidth())),
                        Math.min(visibleFrame.bottom, anchorScreen[1] + Math.max(1, anchor.getHeight())));
            }
            if (anchorVisible.width() <= 0 || anchorVisible.height() <= 0) {
                anchorVisible.set(anchorScreen[0], anchorScreen[1],
                        anchorScreen[0] + Math.max(1, anchor.getWidth()),
                        anchorScreen[1] + Math.max(1, anchor.getHeight()));
            }

            int xScreen = anchorVisible.centerX() - (width / 2);
            xScreen = Math.max(safeLeft, Math.min(safeRight - width, xScreen));

            int belowY = anchorVisible.bottom + gap;
            int aboveY = anchorVisible.top - popupHeight - gap;
            int roomBelow = safeBottom - belowY;
            int roomAbove = anchorVisible.top - gap - safeTop;

            int yScreen;
            if (roomBelow >= popupHeight || roomBelow >= roomAbove) {
                yScreen = Math.min(belowY, safeBottom - popupHeight);
            } else {
                yScreen = Math.max(safeTop, aboveY);
            }

            int xInRoot = xScreen - rootScreen[0];
            int yInRoot = yScreen - rootScreen[1];

            popup.setClippingEnabled(false);
            popup.showAtLocation(windowRoot,
                    android.view.Gravity.TOP | android.view.Gravity.START, xInRoot, yInRoot);
        } catch (Exception ignored) {
            popup.setClippingEnabled(false);
            popup.showAsDropDown(anchor, 0, dp(c, 6));
        }
    }

    public static TextView text(Context c, String value, float sizeSp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(color);
        t.setFontFeatureSettings("kern");
        applyTypography(t, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    /**
     * A binary setting presented Orbit's way: the label, an optional supporting line, and an
     * {@link OrbitSwitch} on the trailing edge.
     *
     * <p>The whole row is the target, so the label toggles the setting as readily as the switch
     * does. The row forwards to {@link OrbitSwitch#toggle()} rather than setting state directly,
     * which keeps one path for reporting a user change.
     *
     * @param description optional; pass null or empty for a label-only row.
     */
    public static LinearLayout switchRow(Context c, String label, String description, OrbitSwitch control) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, 2), dp(c, 6), dp(c, 2), dp(c, 6));
        row.setMinimumHeight(dp(c, 48));
        row.setBackground(ripple(Color.TRANSPARENT, accent(c), 14, c));

        LinearLayout labels = new LinearLayout(c);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(c, label, 14, TEXT, false));
        if (description != null && !description.trim().isEmpty()) {
            TextView note = text(c, description, 12, MUTED, false);
            note.setPadding(0, dp(c, 2), 0, 0);
            labels.addView(note);
        }
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        labelLp.rightMargin = dp(c, 12);
        row.addView(labels, labelLp);

        row.addView(control, new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

        // The label is decoration for the control, so the row itself is not a second
        // announced target competing with the switch.
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        control.setContentDescription(label);
        row.setOnClickListener(v -> control.toggle());
        return row;
    }

    /** Window animation style backing the current Page transitions preference. */
    public static int pageTransitionStyle(Context c) {
        String choice = Prefs.pageTransition(c);
        if (Prefs.PAGE_TRANSITION_FADE.equals(choice)) return R.style.OrbitWindowAnimation_Fade;
        if (Prefs.PAGE_TRANSITION_NONE.equals(choice)) return R.style.OrbitWindowAnimation_None;
        return R.style.OrbitWindowAnimation_Slide;
    }

    /**
     * Applies the selected page transition to one Orbit page window.
     *
     * <p>Called from {@link #applyActivityInsets} so every full-screen Orbit page picks it up from
     * one place and none can drift onto a different style. Invisible bridge activities do not go
     * through that path and stay instant. Setting it per window rather than on the theme is what
     * lets a new selection take effect on the very next navigation without restarting Orbit.
     */
    public static void applyPageTransition(Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        try { activity.getWindow().setWindowAnimations(pageTransitionStyle(activity)); }
        catch (Exception ignored) {}
    }

    /**
     * Suppresses the page transition for one specific launch, for a handoff where another surface
     * has already played the movement. Applies to this window only and reads no preference, so the
     * user's Page transitions choice is neither changed nor consulted and every other navigation
     * is unaffected. Call before the window is added, i.e. during {@code onCreate}.
     */
    public static void suppressPageTransition(Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        try { activity.getWindow().setWindowAnimations(R.style.OrbitWindowAnimation_None); }
        catch (Exception ignored) {}
    }

    /**
     * Subtle system-respecting haptic, honouring Orbit's Haptics preference. Use
     * {@link HapticFeedbackConstants} so each surface stays consistent with the platform.
     */
    public static void haptic(View v, int feedbackConstant) {
        if (v == null || !Prefs.haptics(v.getContext())) return;
        try { v.performHapticFeedback(feedbackConstant); }
        catch (Exception ignored) {}
    }

    /** True when the system is running with animations enabled (reduced-motion aware). */
    public static boolean animationsEnabled() {
        try { return android.animation.ValueAnimator.areAnimatorsEnabled(); }
        catch (Exception ignored) { return true; }
    }

    // Orbit's shared motion timings. Keep new UI on these rather than inventing durations, so the
    // whole app accelerates and settles at the same rate. Screen-to-screen navigation is the one
    // piece the framework owns rather than this class: it mirrors MOTION_STANDARD from
    // res/anim/orbit_activity_*.xml, wired up through Theme.Orbit's windowAnimationStyle. Orbit
    // dialogs and popup menus share R.style.OrbitPopupAnimation.
    /** Immediate feedback: press states, small swaps. */
    public static final long MOTION_FAST = 120L;
    /** Standard transition: reordering, state changes. */
    public static final long MOTION_STANDARD = 190L;
    /** Content arriving on screen. */
    public static final long MOTION_ENTER = 240L;

    /** Orbit's standard easing for content that arrives and settles. */
    public static Interpolator motionEasing() {
        return new DecelerateInterpolator(1.6f);
    }

    /**
     * Subtle arrival for newly added content: a short fade with a small upward settle. Applies
     * only to the view given, so existing content is never re-animated, and becomes a no-op when
     * the system has animations turned off.
     */
    public static void enterContent(View v) {
        if (v == null) return;
        if (!animationsEnabled()) {
            v.setAlpha(1f);
            v.setTranslationY(0f);
            return;
        }
        v.setAlpha(0f);
        v.setTranslationY(dp(v.getContext(), 6));
        v.animate().cancel();
        v.animate().alpha(1f).translationY(0f)
                .setDuration(MOTION_ENTER)
                .setInterpolator(motionEasing())
                .start();
    }

    /** Cross-fades a status label so state changes read as a transition rather than a flicker. */
    public static void swapText(TextView label, String text) {
        if (label == null) return;
        String next = text == null ? "" : text;
        if (next.contentEquals(label.getText()) ) return;
        if (!animationsEnabled()) {
            label.setText(next);
            return;
        }
        label.animate().cancel();
        label.animate().alpha(0f).setDuration(MOTION_FAST / 2).withEndAction(() -> {
            label.setText(next);
            label.animate().alpha(1f).setDuration(MOTION_FAST / 2)
                    .setInterpolator(motionEasing()).start();
        }).start();
    }

    /**
     * Small, tactile interaction used across Orbit. The view compresses on press,
     * springs back on release, and emits a subtle system-respecting haptic tick.
     */
    public static void pressScale(View v) {
        v.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().cancel();
                    view.animate()
                            .scaleX(0.94f).scaleY(0.94f)
                            .alpha(0.84f)
                            .translationY(dp(view.getContext(), 1))
                            .setDuration(65)
                            .start();
                    break;
                case MotionEvent.ACTION_UP:
                    if (Prefs.haptics(view.getContext())) {
                        try { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); }
                        catch (Exception ignored) {}
                    }
                    view.animate().cancel();
                    view.animate()
                            .scaleX(1f).scaleY(1f)
                            .alpha(1f)
                            .translationY(0f)
                            .setInterpolator(new OvershootInterpolator(1.35f))
                            .setDuration(180)
                            .start();
                    break;
                case MotionEvent.ACTION_CANCEL:
                    view.animate().cancel();
                    view.animate()
                            .scaleX(1f).scaleY(1f)
                            .alpha(1f)
                            .translationY(0f)
                            .setDuration(110)
                            .start();
                    break;
            }
            return false;
        });
    }
}
