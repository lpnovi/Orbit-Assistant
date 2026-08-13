package com.orbit.assistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Canonical crop, coordinate-mapping, markup, undo, reset, and render engine. */
public final class ScreenSelectionView extends View {
    public enum Tool { CROP, MARKUP }

    public interface Listener {
        void onStateChanged();
        void onCropEstablished();
    }

    private static final int NONE = 0;
    private static final int MOVE = 1;
    private static final int LEFT = 2;
    private static final int TOP = 4;
    private static final int RIGHT = 8;
    private static final int BOTTOM = 16;
    private static final int CREATE = 32;

    private static final class Stroke {
        final Path path = new Path();
        final List<PointF> points = new ArrayList<>();
        final float width;

        Stroke(float width) { this.width = width; }

        void add(float x, float y) {
            if (points.isEmpty()) path.moveTo(x, y);
            else path.lineTo(x, y);
            points.add(new PointF(x, y));
        }
    }

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cropHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cropPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markupHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markupPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix bitmapToView = new Matrix();
    private final Matrix viewToBitmap = new Matrix();
    private final RectF imageRect = new RectF();
    private final List<Stroke> strokes = new ArrayList<>();

    private Bitmap original;
    private RectF crop;
    private Tool tool = Tool.CROP;
    private Listener listener;
    private float displayScale = 1f;
    private int dragMode = NONE;
    private final PointF touchStart = new PointF();
    private final PointF previous = new PointF();
    private RectF cropAtTouchStart;
    private Stroke activeStroke;

    public ScreenSelectionView(Context context) {
        super(context);
        setBackgroundColor(UiKit.BG);
        setForceDarkAllowed(false);
        setContentDescription("Screen preview. Drag to crop or mark up the screenshot.");
        overlayPaint.setColor(Color.argb(168, 0, 0, 0));
        cropHaloPaint.setStyle(Paint.Style.STROKE);
        int accent = UiKit.accent(context);
        cropHaloPaint.setColor(UiKit.withAlpha(UiKit.onAccent(accent), 220));
        cropPaint.setStyle(Paint.Style.STROKE);
        cropPaint.setColor(accent);
        markupHaloPaint.setStyle(Paint.Style.STROKE);
        markupHaloPaint.setStrokeCap(Paint.Cap.ROUND);
        markupHaloPaint.setStrokeJoin(Paint.Join.ROUND);
        markupHaloPaint.setColor(UiKit.withAlpha(UiKit.onAccent(accent), 220));
        markupPaint.setStyle(Paint.Style.STROKE);
        markupPaint.setStrokeCap(Paint.Cap.ROUND);
        markupPaint.setStrokeJoin(Paint.Join.ROUND);
        markupPaint.setColor(accent);
    }

    public void setListener(Listener listener) { this.listener = listener; }

    public void setOriginal(Bitmap bitmap) {
        original = bitmap;
        updateTransform();
        invalidate();
    }

    public void setTool(Tool next) {
        tool = next == null ? Tool.CROP : next;
        dragMode = NONE;
        activeStroke = null;
        invalidate();
    }

    public Tool getTool() { return tool; }
    public boolean hasCrop() { return crop != null && crop.width() > 0f && crop.height() > 0f; }
    public boolean hasMarkup() { return !strokes.isEmpty(); }
    public boolean canUndo() { return !strokes.isEmpty(); }

    public void undoMarkup() {
        if (strokes.isEmpty()) return;
        strokes.remove(strokes.size() - 1);
        notifyChanged();
        invalidate();
    }

    public void reset() {
        crop = null;
        strokes.clear();
        dragMode = NONE;
        activeStroke = null;
        notifyChanged();
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        updateTransform();
    }

    private void updateTransform() {
        if (original == null || getWidth() <= 0 || getHeight() <= 0) return;
        float sx = getWidth() / (float) original.getWidth();
        float sy = getHeight() / (float) original.getHeight();
        displayScale = Math.max(.0001f, Math.min(sx, sy));
        float drawWidth = original.getWidth() * displayScale;
        float drawHeight = original.getHeight() * displayScale;
        float left = (getWidth() - drawWidth) / 2f;
        float top = (getHeight() - drawHeight) / 2f;
        bitmapToView.reset();
        bitmapToView.setScale(displayScale, displayScale);
        bitmapToView.postTranslate(left, top);
        bitmapToView.invert(viewToBitmap);
        imageRect.set(left, top, left + drawWidth, top + drawHeight);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (original == null) return;
        canvas.drawBitmap(original, bitmapToView, bitmapPaint);
        drawMarkup(canvas);
        if (hasCrop()) drawCrop(canvas);
    }

    private void drawMarkup(Canvas canvas) {
        if (strokes.isEmpty()) return;
        canvas.save();
        canvas.clipRect(imageRect);
        canvas.concat(bitmapToView);
        for (Stroke stroke : strokes) {
            markupHaloPaint.setStrokeWidth(stroke.width + UiKit.dp(getContext(), 3) / displayScale);
            markupPaint.setStrokeWidth(stroke.width);
            canvas.drawPath(stroke.path, markupHaloPaint);
            canvas.drawPath(stroke.path, markupPaint);
        }
        canvas.restore();
    }

    private void drawCrop(Canvas canvas) {
        RectF shown = new RectF(crop);
        bitmapToView.mapRect(shown);
        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, shown.top, overlayPaint);
        canvas.drawRect(imageRect.left, shown.bottom, imageRect.right, imageRect.bottom, overlayPaint);
        canvas.drawRect(imageRect.left, shown.top, shown.left, shown.bottom, overlayPaint);
        canvas.drawRect(shown.right, shown.top, imageRect.right, shown.bottom, overlayPaint);

        cropHaloPaint.setStrokeWidth(UiKit.dp(getContext(), 5));
        cropPaint.setStrokeWidth(UiKit.dp(getContext(), 2));
        canvas.drawRect(shown, cropHaloPaint);
        canvas.drawRect(shown, cropPaint);

        float[][] handles = {
                {shown.left, shown.top}, {shown.centerX(), shown.top}, {shown.right, shown.top},
                {shown.left, shown.centerY()}, {shown.right, shown.centerY()},
                {shown.left, shown.bottom}, {shown.centerX(), shown.bottom},
                {shown.right, shown.bottom}
        };
        Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (float[] point : handles) {
            handle.setColor(UiKit.withAlpha(UiKit.onAccent(getContext()), 235));
            canvas.drawCircle(point[0], point[1], UiKit.dp(getContext(), 8), handle);
            handle.setColor(UiKit.accent(getContext()));
            canvas.drawCircle(point[0], point[1], UiKit.dp(getContext(), 5), handle);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (original == null) return false;
        if (tool == Tool.MARKUP) return handleMarkup(event);
        return handleCrop(event);
    }

    private boolean handleMarkup(MotionEvent event) {
        PointF point = bitmapPoint(event.getX(), event.getY(), false);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (point == null) return false;
                getParent().requestDisallowInterceptTouchEvent(true);
                activeStroke = new Stroke(UiKit.dp(getContext(), 7) / displayScale);
                activeStroke.add(point.x, point.y);
                strokes.add(activeStroke);
                notifyChanged();
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (activeStroke == null || point == null) return true;
                PointF last = activeStroke.points.get(activeStroke.points.size() - 1);
                if (distance(last.x, last.y, point.x, point.y) >= 1.5f / displayScale) {
                    activeStroke.add(point.x, point.y);
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (activeStroke != null && activeStroke.points.size() == 1) {
                    PointF only = activeStroke.points.get(0);
                    activeStroke.add(only.x + .1f, only.y + .1f);
                }
                activeStroke = null;
                getParent().requestDisallowInterceptTouchEvent(false);
                notifyChanged();
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private boolean handleCrop(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN &&
                !imageRect.contains(event.getX(), event.getY())) {
            return false;
        }
        PointF point = bitmapPoint(event.getX(), event.getY(), true);
        if (point == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                touchStart.set(point);
                previous.set(point);
                cropAtTouchStart = crop == null ? null : new RectF(crop);
                dragMode = crop == null ? CREATE : hitCrop(point.x, point.y);
                if (dragMode == NONE) dragMode = CREATE;
                if (dragMode == CREATE) crop = new RectF(point.x, point.y, point.x, point.y);
                return true;
            case MotionEvent.ACTION_MOVE:
                updateCrop(point.x, point.y);
                previous.set(point);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean created = dragMode == CREATE;
                updateCrop(point.x, point.y);
                enforceMinimumCrop();
                dragMode = NONE;
                cropAtTouchStart = null;
                getParent().requestDisallowInterceptTouchEvent(false);
                if (created && event.getActionMasked() == MotionEvent.ACTION_UP && listener != null) {
                    listener.onCropEstablished();
                }
                notifyChanged();
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private int hitCrop(float x, float y) {
        if (crop == null) return NONE;
        float hit = UiKit.dp(getContext(), 22) / displayScale;
        boolean nearLeft = Math.abs(x - crop.left) <= hit && y >= crop.top - hit && y <= crop.bottom + hit;
        boolean nearRight = Math.abs(x - crop.right) <= hit && y >= crop.top - hit && y <= crop.bottom + hit;
        boolean nearTop = Math.abs(y - crop.top) <= hit && x >= crop.left - hit && x <= crop.right + hit;
        boolean nearBottom = Math.abs(y - crop.bottom) <= hit && x >= crop.left - hit && x <= crop.right + hit;
        int edges = (nearLeft ? LEFT : 0) | (nearRight ? RIGHT : 0) |
                (nearTop ? TOP : 0) | (nearBottom ? BOTTOM : 0);
        if (edges != 0) return edges;
        return crop.contains(x, y) ? MOVE : NONE;
    }

    private void updateCrop(float x, float y) {
        if (original == null || crop == null) return;
        float maxX = original.getWidth();
        float maxY = original.getHeight();
        if (dragMode == CREATE) {
            crop.set(Math.min(touchStart.x, x), Math.min(touchStart.y, y),
                    Math.max(touchStart.x, x), Math.max(touchStart.y, y));
        } else if (dragMode == MOVE && cropAtTouchStart != null) {
            float dx = x - touchStart.x;
            float dy = y - touchStart.y;
            dx = Math.max(-cropAtTouchStart.left, Math.min(dx, maxX - cropAtTouchStart.right));
            dy = Math.max(-cropAtTouchStart.top, Math.min(dy, maxY - cropAtTouchStart.bottom));
            crop.set(cropAtTouchStart);
            crop.offset(dx, dy);
        } else {
            float min = minimumBitmapSize();
            if ((dragMode & LEFT) != 0) crop.left = Math.min(x, crop.right - min);
            if ((dragMode & RIGHT) != 0) crop.right = Math.max(x, crop.left + min);
            if ((dragMode & TOP) != 0) crop.top = Math.min(y, crop.bottom - min);
            if ((dragMode & BOTTOM) != 0) crop.bottom = Math.max(y, crop.top + min);
        }
        crop.left = clamp(crop.left, 0f, maxX);
        crop.right = clamp(crop.right, 0f, maxX);
        crop.top = clamp(crop.top, 0f, maxY);
        crop.bottom = clamp(crop.bottom, 0f, maxY);
    }

    private void enforceMinimumCrop() {
        if (crop == null || original == null) return;
        float min = minimumBitmapSize();
        if (crop.width() < min) {
            float center = crop.centerX();
            crop.left = center - min / 2f;
            crop.right = center + min / 2f;
        }
        if (crop.height() < min) {
            float center = crop.centerY();
            crop.top = center - min / 2f;
            crop.bottom = center + min / 2f;
        }
        if (crop.left < 0) crop.offset(-crop.left, 0);
        if (crop.top < 0) crop.offset(0, -crop.top);
        if (crop.right > original.getWidth()) crop.offset(original.getWidth() - crop.right, 0);
        if (crop.bottom > original.getHeight()) crop.offset(0, original.getHeight() - crop.bottom);
        crop.left = clamp(crop.left, 0f, original.getWidth());
        crop.right = clamp(crop.right, crop.left, original.getWidth());
        crop.top = clamp(crop.top, 0f, original.getHeight());
        crop.bottom = clamp(crop.bottom, crop.top, original.getHeight());
    }

    private float minimumBitmapSize() {
        return Math.min(original == null ? 1f : Math.min(original.getWidth(), original.getHeight()),
                UiKit.dp(getContext(), 54) / displayScale);
    }

    private PointF bitmapPoint(float x, float y, boolean clampToImage) {
        if (!clampToImage && !imageRect.contains(x, y)) return null;
        float shownX = clampToImage ? clamp(x, imageRect.left, imageRect.right) : x;
        float shownY = clampToImage ? clamp(y, imageRect.top, imageRect.bottom) : y;
        float[] point = {shownX, shownY};
        viewToBitmap.mapPoints(point);
        return new PointF(clamp(point[0], 0f, original.getWidth()),
                clamp(point[1], 0f, original.getHeight()));
    }

    public Bitmap renderResult(boolean forceFullScreen) {
        if (original == null) return null;
        if (forceFullScreen || (!hasCrop() && !hasMarkup())) return original;
        RectF area = !forceFullScreen && hasCrop()
                ? new RectF(crop) : new RectF(0, 0, original.getWidth(), original.getHeight());
        int left = Math.max(0, (int) Math.floor(area.left));
        int top = Math.max(0, (int) Math.floor(area.top));
        int right = Math.min(original.getWidth(), (int) Math.ceil(area.right));
        int bottom = Math.min(original.getHeight(), (int) Math.ceil(area.bottom));
        if (right <= left || bottom <= top) return null;
        Bitmap output = Bitmap.createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawBitmap(original, -left, -top, bitmapPaint);
        if (!forceFullScreen && hasMarkup()) {
            canvas.save();
            canvas.clipRect(0, 0, output.getWidth(), output.getHeight());
            canvas.translate(-left, -top);
            for (Stroke stroke : strokes) {
                markupHaloPaint.setStrokeWidth(stroke.width + UiKit.dp(getContext(), 3) / displayScale);
                markupPaint.setStrokeWidth(stroke.width);
                canvas.drawPath(stroke.path, markupHaloPaint);
                canvas.drawPath(stroke.path, markupPaint);
            }
            canvas.restore();
        }
        return output;
    }

    public void saveEditorState(Bundle out) {
        if (out == null) return;
        out.putString("selection_tool", tool.name());
        if (hasCrop()) {
            out.putFloatArray("selection_crop", new float[]{crop.left, crop.top, crop.right, crop.bottom});
        }
        JSONArray all = new JSONArray();
        try {
            for (Stroke stroke : strokes) {
                JSONObject item = new JSONObject().put("width", stroke.width);
                JSONArray points = new JSONArray();
                for (PointF point : stroke.points) {
                    points.put(new JSONArray().put(point.x).put(point.y));
                }
                item.put("points", points);
                all.put(item);
            }
            out.putString("selection_strokes", all.toString());
        } catch (Exception ignored) { }
    }

    public void restoreEditorState(Bundle saved) {
        if (saved == null) return;
        try { tool = Tool.valueOf(saved.getString("selection_tool", Tool.CROP.name())); }
        catch (Exception ignored) { tool = Tool.CROP; }
        float[] cropValues = saved.getFloatArray("selection_crop");
        if (cropValues != null && cropValues.length == 4) {
            crop = new RectF(cropValues[0], cropValues[1], cropValues[2], cropValues[3]);
            enforceMinimumCrop();
        }
        strokes.clear();
        try {
            JSONArray all = new JSONArray(saved.getString("selection_strokes", "[]"));
            for (int i = 0; i < all.length(); i++) {
                JSONObject item = all.getJSONObject(i);
                Stroke stroke = new Stroke((float) item.optDouble("width", 1d));
                JSONArray points = item.getJSONArray("points");
                for (int p = 0; p < points.length(); p++) {
                    JSONArray point = points.getJSONArray(p);
                    stroke.add((float) point.getDouble(0), (float) point.getDouble(1));
                }
                if (!stroke.points.isEmpty()) strokes.add(stroke);
            }
        } catch (Exception ignored) { }
        notifyChanged();
        invalidate();
    }

    private void notifyChanged() {
        if (listener != null) listener.onStateChanged();
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
