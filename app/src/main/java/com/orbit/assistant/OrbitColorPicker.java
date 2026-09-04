package com.orbit.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Orbit's own colour editor.
 *
 * <p>Built from {@link Canvas} and two gradients rather than a library, for the ordinary reason
 * that a colour picker is a small amount of well-understood drawing and adding a dependency to the
 * APK to get one would be a poor trade. It is also why it can look like Orbit instead of like
 * somebody else's dialog.
 *
 * <p>Three things are deliberate. Alpha is not offered: a translucent card or bubble is not a theme
 * decision, it is a rendering bug waiting to be reported, so every colour that leaves here is
 * opaque. The hex field accepts what people actually type — with or without the {@code #}, in
 * either case, three digits or six — but only ever emits the canonical form. And the dialog carries
 * both the colour that is in force and the colour being considered, side by side, because "is this
 * actually different from what I had" is the question a colour picker exists to answer.
 */
public final class OrbitColorPicker {
    private OrbitColorPicker() {}

    public interface Listener {
        /** The user accepted {@code color}. Always opaque. */
        void onColorChosen(int color);
    }

    /**
     * @param role     what is being coloured, e.g. "Accent". Used in the title and for TalkBack.
     * @param initial  the colour currently in force, shown alongside the one being chosen.
     * @param suggestions quick swatches offered under the editor. May be empty.
     */
    public static void show(Activity activity, String role, int initial,
                            List<Integer> suggestions, Listener listener) {
        if (activity == null || activity.isFinishing()) return;
        final Context c = activity;
        final float[] hsv = new float[3];
        Color.colorToHSV(initial | 0xFF000000, hsv);

        LinearLayout form = new LinearLayout(c);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(UiKit.dp(c, 20), UiKit.dp(c, 4), UiKit.dp(c, 20), 0);

        final SaturationValueField field = new SaturationValueField(c);
        final HueSlider hue = new HueSlider(c);
        final ComparisonSwatch comparison = new ComparisonSwatch(c, initial);
        final EditText hex = hexField(c);
        final TextView warning = UiKit.text(c, "", 12, UiKit.DANGER, false);
        warning.setPadding(0, UiKit.dp(c, 6), 0, 0);
        warning.setVisibility(View.GONE);

        // One owner of the live value, so the square, the slider, the hex field and the preview can
        // never be showing three different answers.
        final int[] current = {initial | 0xFF000000};
        final boolean[] editingHex = {false};

        final Runnable publish = () -> {
            comparison.setProposed(current[0]);
            field.setHue(hsv[0]);
            field.setPoint(hsv[1], hsv[2]);
            hue.setHue(hsv[0]);
            if (!editingHex[0]) {
                String token = OrbitTheme.colorToken(current[0]);
                if (!token.equalsIgnoreCase(hex.getText().toString())) hex.setText(token);
            }
        };

        field.setListener((saturation, value) -> {
            hsv[1] = saturation;
            hsv[2] = value;
            current[0] = Color.HSVToColor(hsv);
            publish.run();
        });
        hue.setListener(value -> {
            hsv[0] = value;
            current[0] = Color.HSVToColor(hsv);
            publish.run();
        });

        hex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) {}
            @Override public void afterTextChanged(Editable s) {
                Integer parsed = parseHex(s == null ? "" : s.toString());
                warning.setVisibility(parsed == null && s != null && s.length() > 0
                        ? View.VISIBLE : View.GONE);
                if (parsed == null) {
                    warning.setText("Use a colour like #8B7CFF.");
                    return;
                }
                if (parsed == current[0]) return;
                editingHex[0] = true;
                current[0] = parsed;
                Color.colorToHSV(parsed, hsv);
                publish.run();
                editingHex[0] = false;
            }
        });

        form.addView(comparison, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(c, 54)));

        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(c, 168));
        fieldLp.topMargin = UiKit.dp(c, 14);
        form.addView(field, fieldLp);

        LinearLayout.LayoutParams hueLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(c, 40));
        hueLp.topMargin = UiKit.dp(c, 12);
        form.addView(hue, hueLp);

        LinearLayout.LayoutParams hexLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(c, 50));
        hexLp.topMargin = UiKit.dp(c, 14);
        form.addView(hex, hexLp);
        form.addView(warning);

        if (suggestions != null && !suggestions.isEmpty()) {
            TextView label = UiKit.text(c, "Suggested", 12, UiKit.MUTED, false);
            label.setPadding(UiKit.dp(c, 2), UiKit.dp(c, 14), 0, UiKit.dp(c, 8));
            form.addView(label);
            form.addView(swatchRow(c, suggestions, chosen -> {
                current[0] = chosen | 0xFF000000;
                Color.colorToHSV(current[0], hsv);
                publish.run();
            }));
        }

        publish.run();

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(role == null || role.trim().isEmpty() ? "Choose a colour" : role)
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", null)
                .create();
        UiKit.styleOrbitDialog(dialog, c, false,
                UiKit.outlined(UiKit.SURFACE, UiKit.withAlpha(UiKit.accent(c), 55), 22, c),
                .72f, () -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    Integer typed = parseHex(hex.getText().toString());
                    int result = typed == null ? current[0] : typed;
                    dialog.dismiss();
                    if (listener != null) listener.onColorChosen(result | 0xFF000000);
                }));
        dialog.show();
    }

    /**
     * Whatever someone reasonably types, as an opaque colour, or null when it is not one yet.
     *
     * <p>Accepting the three-digit form and a missing {@code #} is not laxness: those are the two
     * ways people write colours, and rejecting them would mean the field only works for someone
     * pasting from a tool that already produced Orbit's exact format.
     */
    public static Integer parseHex(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.startsWith("#")) value = value.substring(1);
        if (value.length() == 3) {
            StringBuilder expanded = new StringBuilder();
            for (int i = 0; i < 3; i++) expanded.append(value.charAt(i)).append(value.charAt(i));
            value = expanded.toString();
        }
        if (value.length() != 6) return null;
        for (int i = 0; i < 6; i++) {
            char ch = value.charAt(i);
            boolean hex = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f')
                    || (ch >= 'A' && ch <= 'F');
            if (!hex) return null;
        }
        return Color.rgb(
                Integer.parseInt(value.substring(0, 2), 16),
                Integer.parseInt(value.substring(2, 4), 16),
                Integer.parseInt(value.substring(4, 6), 16));
    }

    private static EditText hexField(Context c) {
        EditText field = new EditText(c);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7)});
        field.setTextColor(UiKit.TEXT);
        field.setHintTextColor(UiKit.MUTED);
        field.setHint("#8B7CFF");
        field.setTextSize(15);
        field.setContentDescription("Colour value in hexadecimal");
        field.setPadding(UiKit.dp(c, 14), 0, UiKit.dp(c, 14), 0);
        field.setBackground(UiKit.outlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(c), 72), 14, c));
        UiKit.applyCodeTypeface(field);
        return field;
    }

    private interface SwatchListener { void onSwatch(int color); }

    private static View swatchRow(Context c, List<Integer> colors, SwatchListener listener) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        List<Integer> unique = new ArrayList<>();
        for (Integer color : colors) {
            if (color == null) continue;
            int opaque = color | 0xFF000000;
            if (!unique.contains(opaque)) unique.add(opaque);
        }
        for (int i = 0; i < unique.size() && i < 8; i++) {
            final int color = unique.get(i);
            View swatch = new View(c);
            swatch.setBackground(UiKit.outlined(color,
                    UiKit.withAlpha(OrbitContrast.inkOn(color), 60), 11, c));
            swatch.setContentDescription(OrbitColorName.describe("Use", color));
            UiKit.pressScale(swatch);
            swatch.setOnClickListener(v -> {
                UiKit.haptic(v, android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                listener.onSwatch(color);
            });
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(0, UiKit.dp(c, 40), 1f);
            if (i > 0) lp.leftMargin = UiKit.dp(c, 8);
            row.addView(swatch, lp);
        }
        return row;
    }

    // ---- drawing -------------------------------------------------------------------------------

    /** Before and after, so a small adjustment is visible as a small adjustment. */
    private static final class ComparisonSwatch extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final int original;
        private int proposed;

        ComparisonSwatch(Context c, int original) {
            super(c);
            this.original = original | 0xFF000000;
            this.proposed = this.original;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(UiKit.dp(c, 1));
            stroke.setColor(UiKit.withAlpha(UiKit.TEXT, 46));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            updateDescription();
        }

        void setProposed(int color) {
            proposed = color | 0xFF000000;
            updateDescription();
            invalidate();
        }

        private void updateDescription() {
            setContentDescription("Currently " + OrbitColorName.of(original)
                    + ". New colour " + OrbitColorName.describe("", proposed));
        }

        @Override protected void onDraw(Canvas canvas) {
            float radius = UiKit.dp(getContext(), 14);
            float half = getWidth() / 2f;
            rect.set(0, 0, getWidth(), getHeight());
            paint.setColor(proposed);
            canvas.drawRoundRect(rect, radius, radius, paint);
            // The old colour occupies the left half with square inner corners, so the join between
            // the two reads as one control rather than two chips that happen to touch.
            canvas.save();
            canvas.clipRect(0, 0, half, getHeight());
            paint.setColor(original);
            canvas.drawRoundRect(rect, radius, radius, paint);
            canvas.restore();
            canvas.drawRoundRect(rect, radius, radius, stroke);

            drawCaption(canvas, "Current", original, 0, half);
            drawCaption(canvas, "New", proposed, half, getWidth());
        }

        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private void drawCaption(Canvas canvas, String label, int on, float left, float right) {
            textPaint.setColor(OrbitContrast.inkOn(on));
            textPaint.setTextSize(UiKit.sp(getContext(), 11));
            textPaint.setTextAlign(Paint.Align.CENTER);
            float y = getHeight() / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;
            canvas.drawText(label, (left + right) / 2f, y, textPaint);
        }
    }

    private interface PointListener { void onPoint(float saturation, float value); }

    /** Saturation left to right, value top to bottom, for one hue. */
    private static final class SaturationValueField extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float hue;
        private float saturation = 1f;
        private float value = 1f;
        private PointListener listener;

        SaturationValueField(Context c) {
            super(c);
            knob.setStyle(Paint.Style.STROKE);
            knob.setStrokeWidth(UiKit.dp(c, 2));
            setContentDescription("Saturation and brightness. Drag to adjust.");
        }

        void setListener(PointListener value) { this.listener = value; }

        void setHue(float value) {
            if (hue == value) return;
            hue = value;
            invalidate();
        }

        void setPoint(float saturation, float value) {
            this.saturation = saturation;
            this.value = value;
            invalidate();
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            rebuild(w, h);
        }

        private void rebuild(int w, int h) {
            if (w <= 0 || h <= 0) return;
            // Two overlaid gradients rather than a ComposeShader: saturation across, then black
            // with rising alpha down. Same result, and no dependence on ComposeShader behaving
            // identically under every hardware-acceleration path Orbit runs on.
            shade.setShader(new LinearGradient(0, 0, 0, h,
                    Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP));
        }

        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            float radius = UiKit.dp(getContext(), 16);
            rect.set(0, 0, w, h);
            int pure = Color.HSVToColor(new float[]{hue, 1f, 1f});
            fill.setShader(new LinearGradient(0, 0, w, 0, Color.WHITE, pure, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, radius, radius, fill);
            if (shade.getShader() == null) rebuild(w, h);
            canvas.drawRoundRect(rect, radius, radius, shade);

            float x = saturation * w;
            float y = (1f - value) * h;
            int here = Color.HSVToColor(new float[]{hue, saturation, value});
            knob.setColor(OrbitContrast.inkOn(here));
            canvas.drawCircle(clamp(x, UiKit.dp(getContext(), 9), w - UiKit.dp(getContext(), 9)),
                    clamp(y, UiKit.dp(getContext(), 9), h - UiKit.dp(getContext(), 9)),
                    UiKit.dp(getContext(), 8), knob);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                getParent().requestDisallowInterceptTouchEvent(true);
                UiKit.haptic(this, android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            }
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE
                    || action == MotionEvent.ACTION_UP) {
                float w = Math.max(1, getWidth());
                float h = Math.max(1, getHeight());
                saturation = clamp(event.getX() / w, 0f, 1f);
                value = 1f - clamp(event.getY() / h, 0f, 1f);
                if (listener != null) listener.onPoint(saturation, value);
                invalidate();
                return true;
            }
            return super.onTouchEvent(event);
        }
    }

    private interface HueListener { void onHue(float hue); }

    /** The full hue circle as one bar. */
    private static final class HueSlider extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float hue;
        private HueListener listener;

        HueSlider(Context c) {
            super(c);
            knob.setStyle(Paint.Style.STROKE);
            knob.setStrokeWidth(UiKit.dp(c, 3));
            knob.setColor(Color.WHITE);
            setContentDescription("Hue. Drag to change the colour family.");
        }

        void setListener(HueListener value) { this.listener = value; }

        void setHue(float value) {
            if (hue == value) return;
            hue = value;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            int[] stops = new int[13];
            for (int i = 0; i < stops.length; i++) {
                stops[i] = Color.HSVToColor(new float[]{i * 30f % 360f, 1f, 1f});
            }
            fill.setShader(new LinearGradient(0, 0, w, 0, stops, null, Shader.TileMode.CLAMP));
            float radius = h / 2f;
            rect.set(0, 0, w, h);
            canvas.drawRoundRect(rect, radius, radius, fill);
            float x = clamp((hue / 360f) * w, radius, w - radius);
            canvas.drawCircle(x, h / 2f, radius - UiKit.dp(getContext(), 4), knob);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                getParent().requestDisallowInterceptTouchEvent(true);
                UiKit.haptic(this, android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            }
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE
                    || action == MotionEvent.ACTION_UP) {
                hue = clamp(event.getX() / Math.max(1, getWidth()), 0f, 0.9999f) * 360f;
                if (listener != null) listener.onHue(hue);
                invalidate();
                return true;
            }
            return super.onTouchEvent(event);
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Formats a colour the way the hex field shows it. Kept here so tests can assert on it. */
    public static String hexOf(int color) {
        return String.format(Locale.US, "#%02X%02X%02X",
                Color.red(color), Color.green(color), Color.blue(color));
    }
}
