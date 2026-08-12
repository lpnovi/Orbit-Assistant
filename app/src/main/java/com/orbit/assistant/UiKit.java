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
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
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
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.PopupWindow;
import java.io.File;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import android.graphics.drawable.ColorDrawable;

public final class UiKit {
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

    private static final Map<TextView, FontPreview> FONT_PREVIEWS = new WeakHashMap<>();
    private static final Map<TextView, Boolean> TYPOGRAPHY_APPLIED = new WeakHashMap<>();
    private static final Map<View, Boolean> TYPOGRAPHY_WATCHED = new WeakHashMap<>();

    private UiKit() {}

    /**
     * Sync the process-wide Orbit canvas to the saved appearance preference.
     * AMOLED mode uses true black for large background areas while keeping
     * raised surfaces intact for readable card separation.
     */
    public static void syncTheme(Context c) {
        BG = c != null && Prefs.amoledMode(c) ? Color.BLACK : NORMAL_BG;
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
        showOrbitMenuInternal(c, anchor, labels, null, selectedIndex, choice);
    }

    /** Font-picker variant whose labels preview the font they represent. */
    public static void showOrbitFontMenu(Context c, View anchor, String[] fontKeys, String[] labels,
                                         int selectedIndex, OrbitMenuChoice choice) {
        if (fontKeys == null || labels == null || fontKeys.length != labels.length) {
            showOrbitMenu(c, anchor, labels, selectedIndex, choice);
            return;
        }
        showOrbitMenuInternal(c, anchor, labels, fontKeys, selectedIndex, choice);
    }

    private static void showOrbitMenuInternal(Context c, View anchor, String[] labels,
                                              String[] previewFontKeys, int selectedIndex,
                                              OrbitMenuChoice choice) {
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
                popup.dismiss();
                if (choice != null) choice.onChoice(index, labelText);
            });

            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 44));
            if (i > 0) rowLp.topMargin = dp(c, 2);
            box.addView(row, rowLp);
        }

        showAnchoredOrbitPopup(c, anchor, popup, width, labels.length, 44);
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

            int xScreen = anchorScreen[0] + (anchor.getWidth() / 2) - (width / 2);
            xScreen = Math.max(safeLeft, Math.min(safeRight - width, xScreen));

            int belowY = anchorScreen[1] + anchor.getHeight() + gap;
            int aboveY = anchorScreen[1] - popupHeight - gap;
            int roomBelow = safeBottom - belowY;
            int roomAbove = anchorScreen[1] - gap - safeTop;

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
