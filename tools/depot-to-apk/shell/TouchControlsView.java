package dev.silksong.shell;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

// The on-screen controls: drawn over the game, and turned into key presses.
//
// Touches that land on no control are not taken -- the view returns false for
// them -- so they fall through to whatever is underneath: the game, which has
// its own touch handling for menus, or the dual-screen panel when half folded.
//
// The same view is the editor. In edit mode nothing is pressed: a touch picks
// a control up and moves it, a second finger pinches it bigger or smaller.
final class TouchControlsView extends View
{
    interface KeySink { void onKey(int keyCode, boolean down); }
    interface SelectionListener { void onSelectionChanged(ControlsLayout.Control selected); }

    /**
     * Somewhere to send a finger that touched no control after this view had
     * already taken an earlier one. Android delivers every later finger of a
     * gesture to the view that owns the first, so without this a tap on the
     * dual-screen panel while a thumb is on the stick would vanish.
     */
    interface Passthrough
    {
        boolean claims(float x, float y);
        void touch(int pointerId, int phase, float x, float y, long time);  // Unity TouchPhase
        void cancel(int pointerId);
    }

    private ControlsLayout layout;
    private KeySink sink;
    private Passthrough passthrough;
    private final android.util.SparseBooleanArray passed = new android.util.SparseBooleanArray();
    private SelectionListener selectionListener;
    private boolean editing;
    private final float density;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guideText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    /** Areas the editor outlines, with a caption each (tabletop: game / panel). */
    private final List<RectF> guides = new ArrayList<>();
    private final List<String> guideNames = new ArrayList<>();

    // -- play state ----------------------------------------------------------

    private static final class Stick
    {
        float cx, cy;            // where the stick's base is now
        float dx, dy;            // knob offset, as a fraction of the radius
        boolean[] held = new boolean[4];
        int pointer = -1;
    }

    private final SparseArray<ControlsLayout.Control> owner = new SparseArray<>();
    private final List<ControlsLayout.Control> pressed = new ArrayList<>();
    private final java.util.IdentityHashMap<ControlsLayout.Control, Stick> sticks =
        new java.util.IdentityHashMap<>();

    // -- edit state ----------------------------------------------------------

    private ControlsLayout.Control selected;
    private int dragPointer = -1;
    private float grabX, grabY;
    private float pinchStart, pinchScale;

    TouchControlsView(Context context)
    {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        text.setColor(0xFFFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        guide.setStyle(Paint.Style.STROKE);
        guide.setColor(0x88FFFFFF);
        guide.setStrokeWidth(2f * density);
        guide.setPathEffect(new DashPathEffect(new float[] { 12f * density, 8f * density }, 0));
        guideText.setColor(0x88FFFFFF);
        guideText.setTextSize(16f * density);
        guideText.setTextAlign(Paint.Align.CENTER);
        setWillNotDraw(false);
    }

    void setLayout(ControlsLayout layout)
    {
        releaseAll();
        this.layout = layout;
        sticks.clear();
        selected = null;
        if (selectionListener != null) selectionListener.onSelectionChanged(null);
        invalidate();
    }

    ControlsLayout getLayout() { return layout; }
    void setKeySink(KeySink sink) { this.sink = sink; }
    void setPassthrough(Passthrough p) { passthrough = p; }
    void setSelectionListener(SelectionListener l) { selectionListener = l; }
    void setEditing(boolean editing) { releaseAll(); this.editing = editing; invalidate(); }
    ControlsLayout.Control getSelected() { return selected; }

    void setGuides(List<RectF> rects, List<String> names)
    {
        guides.clear();
        guideNames.clear();
        guides.addAll(rects);
        guideNames.addAll(names);
        invalidate();
    }

    /** Lets go of every key this view is holding down. */
    void releaseAll()
    {
        for (ControlsLayout.Control c : new ArrayList<>(pressed)) release(c);
        for (ControlsLayout.Control c : new ArrayList<>(sticks.keySet())) endStick(c);
        if (passthrough != null)
            for (int i = 0; i < passed.size(); i++) passthrough.cancel(passed.keyAt(i));
        passed.clear();
        owner.clear();
        pressed.clear();
        invalidate();
    }

    @Override protected void onDetachedFromWindow()
    {
        releaseAll();
        super.onDetachedFromWindow();
    }

    @Override public void onWindowFocusChanged(boolean hasWindowFocus)
    {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) releaseAll();
    }

    // -- geometry ------------------------------------------------------------

    private float height(ControlsLayout.Control c)
    {
        return (c.isJoystick() ? ControlsLayout.JOYSTICK_DP : ControlsLayout.BUTTON_DP) * c.scale * density;
    }

    private float width(ControlsLayout.Control c)
    {
        return c.isJoystick() ? height(c) : height(c) * c.aspect;
    }

    private float cx(ControlsLayout.Control c) { return c.x * getWidth(); }
    private float cy(ControlsLayout.Control c) { return c.y * getHeight(); }

    private void bounds(ControlsLayout.Control c, float x, float y, RectF out)
    {
        float w = width(c) / 2f, h = height(c) / 2f;
        out.set(x - w, y - h, x + w, y + h);
    }

    /** The control under a point: buttons first, then a stick's own circle, then its floating zone. */
    private ControlsLayout.Control hit(float x, float y, boolean includeZones)
    {
        if (layout == null) return null;
        List<ControlsLayout.Control> list = layout.controls;
        for (int i = list.size() - 1; i >= 0; i--)
        {
            ControlsLayout.Control c = list.get(i);
            if ((c.hidden && !editing) || c.isJoystick()) continue;
            bounds(c, cx(c), cy(c), rect);
            float slop = 6f * density;
            rect.inset(-slop, -slop);
            if (rect.contains(x, y)) return c;
        }
        for (int i = list.size() - 1; i >= 0; i--)
        {
            ControlsLayout.Control c = list.get(i);
            if ((c.hidden && !editing) || !c.isJoystick()) continue;
            float r = width(c) / 2f;
            float ddx = x - cx(c), ddy = y - cy(c);
            if (ddx * ddx + ddy * ddy <= r * r * 1.1f) return c;
        }
        if (!includeZones) return null;
        // A floating stick's zone never reaches into the dual-screen panel:
        // the panel is a touch screen of its own.
        if (passthrough != null && passthrough.claims(x, y)) return null;
        for (int i = list.size() - 1; i >= 0; i--)
        {
            ControlsLayout.Control c = list.get(i);
            if (c.hidden || !c.isJoystick() || !c.dynamic) continue;
            zone(c, rect);
            if (rect.contains(x, y)) return c;
        }
        return null;
    }

    /** A floating stick's catchment: its range around where it sits, kept on screen. */
    private void zone(ControlsLayout.Control c, RectF out)
    {
        float w = Math.max(c.dynamicW * getWidth(), width(c));
        float h = Math.max(c.dynamicH * getHeight(), height(c));
        float x = cx(c), y = cy(c);
        out.set(x - w / 2f, y - h / 2f, x + w / 2f, y + h / 2f);
        if (out.left < 0) out.offset(-out.left, 0);
        if (out.right > getWidth()) out.offset(getWidth() - out.right, 0);
        if (out.top < 0) out.offset(0, -out.top);
        if (out.bottom > getHeight()) out.offset(0, getHeight() - out.bottom);
    }

    // -- input ---------------------------------------------------------------

    @Override public boolean onTouchEvent(MotionEvent e)
    {
        if (layout == null) return false;
        return editing ? onEditTouch(e) : onPlayTouch(e);
    }

    private boolean onPlayTouch(MotionEvent e)
    {
        int action = e.getActionMasked();
        int index = e.getActionIndex();
        switch (action)
        {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
            {
                int id = e.getPointerId(index);
                float x = e.getX(index), y = e.getY(index);
                ControlsLayout.Control c = hit(x, y, true);
                if (c == null)
                {
                    // Nothing here: let the game (or the panel) have the touch.
                    if (action == MotionEvent.ACTION_DOWN) return false;
                    if (passthrough != null && passthrough.claims(x, y))
                    {
                        passed.put(id, true);
                        passthrough.touch(id, 0, x, y, e.getEventTime());
                    }
                    return true;
                }
                if (c.isJoystick()) startStick(c, id, x, y);
                else press(c);
                owner.put(id, c);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++)
                {
                    int id = e.getPointerId(i);
                    ControlsLayout.Control c = owner.get(id);
                    if (c != null && c.isJoystick()) moveStick(c, e.getX(i), e.getY(i));
                    else if (passed.get(id))
                        passthrough.touch(id, 1, e.getX(i), e.getY(i), e.getEventTime());
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            {
                int id = e.getPointerId(index);
                if (passed.get(id))
                {
                    passed.delete(id);
                    passthrough.touch(id, 3, e.getX(index), e.getY(index), e.getEventTime());
                }
                ControlsLayout.Control c = owner.get(id);
                owner.remove(id);
                if (c != null)
                {
                    if (c.isJoystick()) endStick(c);
                    else if (!stillHeld(c)) release(c);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                return true;
            default:
                return true;
        }
    }

    private boolean stillHeld(ControlsLayout.Control c)
    {
        for (int i = 0; i < owner.size(); i++) if (owner.valueAt(i) == c) return true;
        return false;
    }

    private void press(ControlsLayout.Control c)
    {
        if (pressed.contains(c)) return;
        pressed.add(c);
        if (sink != null) for (int k : c.keys) if (k != 0) sink.onKey(k, true);
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private void release(ControlsLayout.Control c)
    {
        if (!pressed.remove(c)) return;
        if (sink != null) for (int k : c.keys) if (k != 0) sink.onKey(k, false);
    }

    private Stick stick(ControlsLayout.Control c)
    {
        Stick s = sticks.get(c);
        if (s == null)
        {
            s = new Stick();
            sticks.put(c, s);
        }
        return s;
    }

    private void startStick(ControlsLayout.Control c, int pointer, float x, float y)
    {
        Stick s = stick(c);
        s.pointer = pointer;
        float r = width(c) / 2f;
        float ddx = x - cx(c), ddy = y - cy(c);
        boolean onStick = ddx * ddx + ddy * ddy <= r * r;
        if (c.dynamic && !onStick)
        {
            // Floating: the stick comes to the thumb rather than the other way.
            s.cx = Math.max(r, Math.min(getWidth() - r, x));
            s.cy = Math.max(r, Math.min(getHeight() - r, y));
        }
        else
        {
            s.cx = cx(c);
            s.cy = cy(c);
        }
        moveStick(c, x, y);
    }

    private void moveStick(ControlsLayout.Control c, float x, float y)
    {
        Stick s = stick(c);
        float r = width(c) / 2f;
        float ddx = x - s.cx, ddy = y - s.cy;
        float dist = (float) Math.sqrt(ddx * ddx + ddy * ddy);
        if (dist > r)
        {
            if (c.followFinger)
            {
                // Drag the base along behind the thumb, so reversing direction
                // never needs the thumb to travel back past the old centre.
                float pull = (dist - r) / dist;
                s.cx += ddx * pull;
                s.cy += ddy * pull;
            }
            ddx *= r / dist;
            ddy *= r / dist;
        }
        s.dx = ddx / r;
        s.dy = ddy / r;
        float mag = (float) Math.sqrt(s.dx * s.dx + s.dy * s.dy);
        boolean live = mag > 0.25f;
        // 0 up, 1 down, 2 left, 3 right. Horizontal is the generous axis:
        // a slightly-off-level thumb should run, not look up or down.
        setDirection(c, s, 0, live && s.dy < -0.5f);
        setDirection(c, s, 1, live && s.dy > 0.5f);
        setDirection(c, s, 2, live && s.dx < -0.38f);
        setDirection(c, s, 3, live && s.dx > 0.38f);
    }

    private void endStick(ControlsLayout.Control c)
    {
        Stick s = stick(c);
        for (int i = 0; i < 4; i++) setDirection(c, s, i, false);
        s.pointer = -1;
        s.dx = s.dy = 0f;
    }

    private void setDirection(ControlsLayout.Control c, Stick s, int dir, boolean down)
    {
        if (s.held[dir] == down) return;
        s.held[dir] = down;
        if (c != null && sink != null && dir < c.keys.length && c.keys[dir] != 0)
            sink.onKey(c.keys[dir], down);
    }

    private boolean onEditTouch(MotionEvent e)
    {
        int action = e.getActionMasked();
        int index = e.getActionIndex();
        switch (action)
        {
            case MotionEvent.ACTION_DOWN:
            {
                float x = e.getX(), y = e.getY();
                ControlsLayout.Control c = hit(x, y, false);
                select(c);
                if (c != null)
                {
                    dragPointer = e.getPointerId(0);
                    grabX = x - cx(c);
                    grabY = y - cy(c);
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                if (selected != null && e.getPointerCount() == 2)
                {
                    pinchStart = spacing(e);
                    pinchScale = selected.scale;
                    dragPointer = -1;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (selected == null) return true;
                if (e.getPointerCount() >= 2 && pinchStart > 0)
                {
                    float s = pinchScale * spacing(e) / pinchStart;
                    selected.scale = ControlsLayout.clamp(s, 0.3f, 4f);
                    invalidate();
                }
                else if (dragPointer >= 0)
                {
                    int i = e.findPointerIndex(dragPointer);
                    if (i < 0) return true;
                    selected.x = ControlsLayout.clamp((e.getX(i) - grabX) / getWidth(), 0f, 1f);
                    selected.y = ControlsLayout.clamp((e.getY(i) - grabY) / getHeight(), 0f, 1f);
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (e.getPointerCount() <= 2) pinchStart = 0;
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragPointer = -1;
                pinchStart = 0;
                return true;
            default:
                return true;
        }
    }

    private static float spacing(MotionEvent e)
    {
        float x = e.getX(0) - e.getX(1), y = e.getY(0) - e.getY(1);
        return (float) Math.sqrt(x * x + y * y);
    }

    void select(ControlsLayout.Control c)
    {
        selected = c;
        if (selectionListener != null) selectionListener.onSelectionChanged(c);
        invalidate();
    }

    // -- drawing -------------------------------------------------------------

    @Override protected void onDraw(Canvas canvas)
    {
        if (editing)
        {
            for (int i = 0; i < guides.size(); i++)
            {
                RectF g = guides.get(i);
                canvas.drawRect(g, guide);
                canvas.drawText(guideNames.get(i), g.centerX(), g.centerY(), guideText);
            }
        }
        if (layout == null) return;
        for (ControlsLayout.Control c : layout.controls)
        {
            if (c.hidden && !editing) continue;
            float a = layout.opacity * (c.hidden ? 0.3f : 1f);
            if (c.isJoystick()) drawStick(canvas, c, a);
            else drawButton(canvas, c, a);
            if (editing && c == selected)
            {
                bounds(c, cx(c), cy(c), rect);
                rect.inset(-6f * density, -6f * density);
                stroke.setColor(0xFFE0B04A);
                stroke.setStrokeWidth(3f * density);
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setPathEffect(null);
                canvas.drawRect(rect, stroke);
                if (c.isJoystick() && c.dynamic)
                {
                    zone(c, rect);
                    canvas.drawRect(rect, guide);
                }
            }
        }
    }

    private void drawButton(Canvas canvas, ControlsLayout.Control c, float alpha)
    {
        boolean down = pressed.contains(c);
        float x = cx(c), y = cy(c);
        bounds(c, x, y, rect);
        if (down) rect.inset(rect.width() * 0.03f, rect.height() * 0.03f);
        int a = Math.round(255 * alpha * (down ? c.activeAlpha : c.alpha));

        ControlsLayout.Icon icon = down && c.activeIcon != null ? c.activeIcon : c.icon;
        if (icon != null)
        {
            drawBitmap(canvas, icon, rect, a);
            if (down)
            {
                shape(c, rect);
                fill.setColor(c.pressedFill);
                fill.setAlpha(Math.round(Math.min(255, (c.pressedFill >>> 24)) * alpha));
                canvas.drawPath(path, fill);
            }
            // Its art shows what it used to do; say what it does now.
            if (c.rebound()) drawBadge(canvas, ControlsLayout.actionName(c.vk), rect, alpha);
            return;
        }

        shape(c, rect);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(down ? blend(c.fill, c.pressedFill) : c.fill);
        fill.setAlpha(Math.round(((down ? 0x60 : (c.fill >>> 24)) & 0xFF) * alpha * 1.6f) & 0xFF);
        canvas.drawPath(path, fill);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setPathEffect(null);
        stroke.setStrokeWidth(Math.max(1f, c.strokeWidth) * density);
        stroke.setColor(down ? c.pressedStroke : c.stroke);
        stroke.setAlpha(Math.round(Math.max(0x60, stroke.getAlpha()) * alpha));
        canvas.drawPath(path, stroke);
        String label = c.added || c.rebound() ? ControlsLayout.actionName(c.vk) : c.label;
        if (label != null && !label.isEmpty())
        {
            text.setTextSize(Math.min(rect.height() * 0.38f, rect.width() * 0.8f / Math.max(1, label.length()) * 1.6f));
            text.setAlpha(Math.round(230 * alpha));
            canvas.drawText(label, rect.centerX(), rect.centerY() - (text.descent() + text.ascent()) / 2f, text);
        }
    }

    private void drawBadge(Canvas canvas, String label, RectF r, float alpha)
    {
        text.setTextSize(Math.max(10f * density, r.height() * 0.2f));
        float w = text.measureText(label) + 8f * density, h = text.getTextSize() * 1.3f;
        RectF b = new RectF(r.centerX() - w / 2f, r.bottom - h * 0.6f, r.centerX() + w / 2f, r.bottom + h * 0.4f);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xCC000000);
        fill.setAlpha(Math.round(0xCC * alpha));
        canvas.drawRoundRect(b, h / 2f, h / 2f, fill);
        text.setAlpha(Math.round(240 * alpha));
        canvas.drawText(label, b.centerX(), b.centerY() - (text.descent() + text.ascent()) / 2f, text);
    }

    private void drawStick(Canvas canvas, ControlsLayout.Control c, float alpha)
    {
        Stick s = sticks.get(c);
        boolean active = s != null && s.pointer >= 0;
        float x = active ? s.cx : cx(c), y = active ? s.cy : cy(c);
        float r = width(c) / 2f;
        rect.set(x - r, y - r, x + r, y + r);
        int a = Math.round(255 * alpha * (active ? c.activeAlpha : Math.max(c.alpha, 0.5f)));

        ControlsLayout.Icon outer = active && c.activeOuterIcon != null ? c.activeOuterIcon : c.outerIcon;
        if (outer != null) drawBitmap(canvas, outer, rect, a);
        else
        {
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(c.fill);
            fill.setAlpha(Math.round(Math.max(0x30, c.fill >>> 24) * alpha));
            canvas.drawCircle(x, y, r, fill);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setPathEffect(null);
            stroke.setStrokeWidth(Math.max(1f, c.strokeWidth) * density);
            stroke.setColor(c.stroke);
            stroke.setAlpha(Math.round(Math.max(0x60, c.stroke >>> 24) * alpha));
            canvas.drawCircle(x, y, r, stroke);
        }

        float kx = x + (active ? s.dx * r : 0f), ky = y + (active ? s.dy * r : 0f);
        ControlsLayout.Icon inner = active && c.activeInnerIcon != null ? c.activeInnerIcon : c.innerIcon;
        if (inner != null)
        {
            float kr = r * inner.scale;
            rect.set(kx - kr, ky - kr, kx + kr, ky + kr);
            drawBitmap(canvas, inner, rect, a);
        }
        else
        {
            float kr = r * 0.42f;
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(active ? blend(c.stickFill, 0xFFFFFFFF) : c.stickFill);
            fill.setAlpha(Math.round(Math.max(0x60, c.stickFill >>> 24) * alpha));
            canvas.drawCircle(kx, ky, kr, fill);
        }
    }

    private void drawBitmap(Canvas canvas, ControlsLayout.Icon icon, RectF dst, int alpha)
    {
        boolean crop = icon.crop;
        Bitmap b = icon.bitmap;
        bitmapPaint.setAlpha(Math.round(alpha * Math.max(0.05f, icon.alpha)));
        float w = dst.width(), h = dst.height();
        float bw = b.getWidth(), bh = b.getHeight();
        float scale = crop ? Math.max(w / bw, h / bh) : Math.min(w / bw, h / bh);
        float sw = bw * scale, sh = bh * scale;
        RectF out = new RectF(dst.centerX() - sw / 2f, dst.centerY() - sh / 2f,
                              dst.centerX() + sw / 2f, dst.centerY() + sh / 2f);
        canvas.save();
        if (crop) canvas.clipRect(dst);
        canvas.drawBitmap(b, null, out, bitmapPaint);
        canvas.restore();
    }

    private void shape(ControlsLayout.Control c, RectF r)
    {
        path.reset();
        String shape = c.shape == null ? "circle" : c.shape;
        if (shape.startsWith("trigger"))
        {
            // A slanted tab, leaning the way GameHub draws shoulder buttons.
            float slant = r.height() * 0.35f;
            boolean left = shape.endsWith("lb") || shape.endsWith("lt");
            if (left)
            {
                path.moveTo(r.left, r.top);
                path.lineTo(r.right, r.top);
                path.lineTo(r.right - slant, r.bottom);
                path.lineTo(r.left, r.bottom);
            }
            else
            {
                path.moveTo(r.left + slant, r.top);
                path.lineTo(r.right, r.top);
                path.lineTo(r.right, r.bottom);
                path.lineTo(r.left, r.bottom);
            }
            path.close();
        }
        else if ("circle".equals(shape) && Math.abs(r.width() - r.height()) < 1f)
            path.addOval(r, Path.Direction.CW);
        else
        {
            float radius = Math.min(r.width(), r.height()) * 0.3f;
            path.addRoundRect(r, radius, radius, Path.Direction.CW);
        }
    }

    private static int blend(int a, int b)
    {
        int ra = (a >> 16) & 0xFF, ga = (a >> 8) & 0xFF, ba = a & 0xFF;
        int rb = (b >> 16) & 0xFF, gb = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | (((ra + rb) / 2) << 16) | (((ga + gb) / 2) << 8) | ((ba + bb) / 2);
    }
}
