package dev.silksong.shell;

import android.app.Activity;
import android.content.Context;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;

// Half folded on a book-style foldable: the game on the top half, the
// dual-screen panel on the bottom half.
//
// The dual-screen UI is built for a second physical display, and Unity draws
// to one only through Android's MediaRouter: Display.displays holds the main
// display plus, at most, the "presentation display" the system route picks,
// which is the first display flagged FLAG_PRESENTATION. So the bottom half is
// made into exactly that -- a private virtual display, created by this app
// with VIRTUAL_DISPLAY_FLAG_PRESENTATION and backed by a SurfaceView in our
// own window. MediaRouter offers it, Unity sees a second display appear, and
// DualScreenV2 brings the panel up through the same hot-plug path it uses for
// a handheld's detachable screen. The display is made before the engine
// starts and lives as long as the activity; unfolding hides it rather than
// removing it (see createPanelDisplay for why).
//
// The panel is centred and kept near the shape the dual-screen UI was designed
// at (1240x1080); the strips either side are where the thumbs are, and where
// the on-screen controls go in this posture.
//
// Detection needs no library: TYPE_HINGE_ANGLE is a public sensor (API 30).
// The hinge of a book-style foldable runs top to bottom in the inner screen's
// natural orientation, so it runs across a landscape window only when the
// display is rotated a quarter turn -- which is how the game runs on the Fold
// series. A device whose inner screen is naturally landscape would have the
// hinge down the middle of the game instead, and is left alone.
final class TabletopMode implements SensorEventListener, SurfaceHolder.Callback
{
    interface Listener { void onTabletopChanged(boolean active); }

    private static final String TAG = "SilksongTabletop";
    /** The dual-screen UI's design size; the panel keeps roughly this shape. */
    private static final float PANEL_ASPECT = 1240f / 1080f;
    /** Half open, with some hysteresis so a wobble at the edge does not flap. */
    private static final float ENTER_MIN = 50f, ENTER_MAX = 150f;
    private static final float EXIT_MIN = 35f, EXIT_MAX = 165f;
    private static final long SETTLE_MS = 350;

    private final Activity activity;
    private final FrameLayout root;
    private final View game;
    private final SecondaryDisplay secondary;
    private final Listener listener;
    private final SensorManager sensors;
    private final Sensor hinge;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private SurfaceView panel;
    private VirtualDisplay display;
    private boolean active, listening, sensorSaysHalf;
    private float angle = -1f;
    private volatile int gameWidth, gameHeight;
    private final RectF panelRect = new RectF();

    private final Runnable apply = this::apply;

    // A knob for testing without folding anything, read once a second:
    //
    //     F=/sdcard/Android/data/<package>/files/tabletop_force
    //     adb shell "echo 1 > $F"     # behave as if half open
    //     adb shell "echo 0 > $F"     # behave as if flat
    //     adb shell "rm $F"           # back to the hinge sensor
    private int forced = -1;
    private final Runnable pollKnob = new Runnable()
    {
        @Override public void run()
        {
            int next = readKnob();
            if (next != forced)
            {
                forced = next;
                Log.i(TAG, "tabletop_force=" + forced);
                handler.removeCallbacks(apply);
                handler.post(apply);
            }
            handler.postDelayed(this, 1000);
        }
    };

    private int readKnob()
    {
        java.io.File dir = activity.getExternalFilesDir(null);
        java.io.File f = dir != null ? new java.io.File(dir, "tabletop_force") : null;
        if (f == null || !f.isFile()) return -1;
        try { return ControlsLayout.readText(f).trim().startsWith("1") ? 1 : 0; }
        catch (java.io.IOException e) { return -1; }
    }

    /**
     * The panel's display, made before the engine starts.
     *
     * Unity only draws safely to a second display that existed when it
     * started: a presentation display that appeared mid-game was reported and
     * activated, and the render thread crashed (SIGSEGV in UnityGfxDeviceWorker)
     * on its first frame -- with or without a delay, while the same panel
     * present from launch worked. So when this mode is on, the display is
     * created here, before the player is constructed, and kept for the whole
     * session.
     *
     * Nor may the engine's surface on it change while it draws: hiding the
     * presentation, or giving the display no output, crashed it the same way
     * on unfolding. So the engine's side is never touched. What changes is
     * where the display's output goes -- the panel view when half folded, an
     * offscreen buffer that is thrown away when flat -- and that swap happens
     * in the system compositor, not in the engine. Flat, the game is told the
     * panel is folded away (getPanelState) and stops drawing its UI there.
     */
    static VirtualDisplay createPanelDisplay(Activity activity)
    {
        try
        {
            android.graphics.Rect b = activity.getWindowManager().getMaximumWindowMetrics().getBounds();
            int w = Math.max(b.width(), b.height()), h = Math.min(b.width(), b.height());
            int dpi = activity.getResources().getDisplayMetrics().densityDpi;
            DisplayManager dm = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
            VirtualDisplay d = dm.createVirtualDisplay("Silksong panel", panelWidth(w, h), h - h / 2, dpi, null,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
            Log.i(TAG, "Panel display " + d.getDisplay().getDisplayId() + " created at "
                + panelWidth(w, h) + "x" + (h - h / 2));
            return d;
        }
        catch (RuntimeException e)
        {
            Log.e(TAG, "Could not create the panel display", e);
            return null;
        }
    }

    static boolean hasHinge(Context context)
    {
        SensorManager sm = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        return sm != null && sm.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) != null;
    }

    TabletopMode(Activity activity, FrameLayout root, View game, SecondaryDisplay secondary,
                 VirtualDisplay display, Listener listener)
    {
        this.activity = activity;
        this.root = root;
        this.game = game;
        this.secondary = secondary;
        this.display = display;
        this.listener = listener;
        sensors = (SensorManager) activity.getSystemService(Context.SENSOR_SERVICE);
        hinge = sensors != null ? sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) : null;
        Log.i(TAG, hinge != null ? "Hinge sensor: " + hinge.getName() : "No hinge sensor; tabletop mode unavailable");
        // Flat until shown otherwise: the display draws into the sink.
        if (display != null)
        {
            android.graphics.Point size = new android.graphics.Point();
            display.getDisplay().getRealSize(size);
            sinkWidth = size.x;
            sinkHeight = size.y;
            sink = android.media.ImageReader.newInstance(sinkWidth, sinkHeight,
                android.graphics.PixelFormat.RGBA_8888, 2,
                android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
            sink.setOnImageAvailableListener(r -> {
                android.media.Image image = r.acquireLatestImage();
                if (image != null) image.close();
            }, handler);
            display.setSurface(sink.getSurface());
        }
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) handler.post(apply);
        });
    }

    boolean isActive() { return active; }

    /** The panel's rectangle in the root's coordinates; empty when not active. */
    RectF panelRect() { return panelRect; }

    /** 1 while the panel is on screen, 0 while it is folded away. For DualScreenV2. */
    int panelState() { return shown ? 1 : 0; }

    /**
     * The game view's size while it is not the whole window, else {0, 0}. Asked
     * for by the resolution patches (AndroidWindow), from Unity's thread.
     */
    int[] gameViewSize()
    {
        int w = gameWidth, h = gameHeight;
        // Never null: Unity's JNI array conversion is not trusted with one.
        return w > 0 && h > 0 ? new int[] { w, h } : new int[] { 0, 0 };
    }

    /** The panel's width for a window, shared with the controls editor. */
    static int panelWidth(int windowW, int windowH)
    {
        int half = windowH / 2;
        return Math.min(windowW, Math.round(half * PANEL_ASPECT));
    }

    void onResume()
    {
        handler.removeCallbacks(pollKnob);
        handler.post(pollKnob);
        if (hinge == null || listening) return;
        listening = sensors.registerListener(this, hinge, SensorManager.SENSOR_DELAY_UI);
    }

    void onPause()
    {
        handler.removeCallbacks(pollKnob);
        if (!listening) return;
        sensors.unregisterListener(this);
        listening = false;
    }

    /** After the player is destroyed: nothing is drawing to the panel any more. */
    void onDestroy()
    {
        onPause();
        handler.removeCallbacksAndMessages(null);
        active = false;
        if (display != null)
        {
            display.release();
            display = null;
        }
        if (sink != null)
        {
            sink.close();
            sink = null;
        }
    }

    @Override public void onSensorChanged(SensorEvent event)
    {
        angle = event.values[0];
        boolean half = sensorSaysHalf
            ? angle >= EXIT_MIN && angle <= EXIT_MAX
            : angle >= ENTER_MIN && angle <= ENTER_MAX;
        if (half == sensorSaysHalf) return;
        sensorSaysHalf = half;
        Log.i(TAG, "Hinge at " + Math.round(angle) + " degrees: " + (half ? "half open" : "not half open"));
        handler.removeCallbacks(apply);
        handler.postDelayed(apply, SETTLE_MS);
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private boolean hingeAcrossWindow()
    {
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        boolean quarterTurn = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270;
        return quarterTurn && root.getWidth() > root.getHeight();
    }

    private void apply()
    {
        boolean half = forced >= 0 ? forced == 1 : sensorSaysHalf;
        boolean want = display != null && half && hingeAcrossWindow() && root.getHeight() > 0;
        if (want) enter();
        else leave();
    }

    private final Runnable restoreGame = this::restoreGameLayout;

    private void restoreGameLayout()
    {
        if (active) return;
        gameWidth = gameHeight = 0;
        if (game.getLayoutParams() == null || game.getLayoutParams().height != FrameLayout.LayoutParams.MATCH_PARENT)
            game.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void enter()
    {
        handler.removeCallbacks(restoreGame);
        int w = root.getWidth(), h = root.getHeight(), half = h / 2;
        int pw = panelWidth(w, h);
        gameWidth = w;
        gameHeight = half;
        panelRect.set((w - pw) / 2f, half, (w + pw) / 2f, h);

        game.setLayoutParams(new FrameLayout.LayoutParams(w, half, Gravity.TOP));

        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(pw, h - half,
            Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        if (panel == null)
        {
            panel = new SurfaceView(activity);
            panel.getHolder().addCallback(this);
            panel.setOnTouchListener((v, e) -> {
                secondary.forwardTouch(e, v.getWidth(), v.getHeight());
                return true;
            });
            // Above the game, below the controls.
            root.addView(panel, 1, pp);
        }
        else panel.setLayoutParams(pp);

        if (!active)
        {
            active = true;
            Log.i(TAG, "Tabletop: game " + w + "x" + half + ", panel " + pw + "x" + (h - half));
            listener.onTabletopChanged(true);
        }
    }

    private void leave()
    {
        shown = false;
        if (panel != null)
        {
            root.removeView(panel);   // surfaceDestroyed sends the output to the sink
            panel = null;
        }
        panelRect.setEmpty();
        // The game's own surface is resized a moment later, never in the same
        // frame as the panel's goes: two of the engine's surfaces changing at
        // once crashed its render thread.
        handler.removeCallbacks(restoreGame);
        if (active) handler.postDelayed(restoreGame, 1000);
        else restoreGame.run();
        if (active)
        {
            active = false;
            Log.i(TAG, "Tabletop off");
            listener.onTabletopChanged(false);
        }
    }

    // -- the panel's display -------------------------------------------------

    private volatile boolean shown;
    private android.media.ImageReader sink;
    private int sinkWidth, sinkHeight;

    @Override public void surfaceCreated(SurfaceHolder holder) { }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height)
    {
        if (width <= 0 || height <= 0 || display == null || !active) return;
        // The display keeps the size it was made with: resizing it would
        // change the engine's surface on it. A view of another size (a window
        // that changed shape) is simply scaled into by the compositor.
        display.setSurface(holder.getSurface());
        shown = true;
        Log.i(TAG, "Panel shown at " + width + "x" + height);
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder)
    {
        shown = false;
        if (display != null) display.setSurface(sink != null ? sink.getSurface() : null);
    }
}
