package dev.silksong.shell;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;

import java.lang.reflect.Method;
import java.util.List;

// A foldable held half open like a laptop: the game above the fold, the
// dual-screen panel below it (see TabletopGeometry for how that area is
// shared out on each shape of device).
//
// The dual-screen UI is built for a second physical display, and Unity draws
// to one only through Android's MediaRouter: Display.displays holds the main
// display plus, at most, the "presentation display" the system route picks,
// which is the first display flagged FLAG_PRESENTATION. So the area under the
// fold is made into exactly that -- a private virtual display, created by this
// app with VIRTUAL_DISPLAY_FLAG_PRESENTATION and backed by a SurfaceView in our
// own window. MediaRouter offers it, Unity sees a second display appear, and
// DualScreenV2 brings the panel up through the same hot-plug path it uses for
// a handheld's detachable screen. The display is made before the engine
// starts and lives as long as the activity; unfolding hides it rather than
// removing it (see createPanelDisplay for why).
//
// Where the fold is and whether it is half open come from the device itself,
// through the window extensions library (FoldInfo), in the window's own
// coordinates. That is what makes every shape of foldable work:
//
//   - A Fold with a portrait inner screen runs the game a quarter turn round,
//     so the fold already runs across the landscape window. Half open, the
//     game moves up and the panel appears.
//   - A passport-shaped Fold (Fold 8, Pixel Fold) or a Flip runs the game with
//     the fold DOWN the landscape window. Half open, that is either a book
//     held sideways (nothing to do) or a laptop the player is about to stand
//     up. So while half open the window is allowed to turn upright (the Host
//     lifts the landscape lock); if the player stands the device up, the
//     system rotates the window, the fold now runs across it, and the game
//     takes the upper half in landscape -- the game itself never sees a
//     portrait surface. Opened flat, the landscape lock comes back.
//
// A device without the library falls back to the hinge-angle sensor, and to
// the one shape that can be inferred without it: a near-square inner screen
// turned a quarter turn (the older Folds).
final class TabletopMode implements SensorEventListener, SurfaceHolder.Callback, FoldInfo.Listener
{
    interface Host
    {
        void onTabletopChanged(boolean active);

        /**
         * True: let the window turn upright (any orientation the sensor
         * picks). False: give the engine's own orientation back.
         */
        void setUprightAllowed(boolean allowed);
    }

    private static final String TAG = "SilksongTabletop";
    /** Hinge sensor fallback: half open, with hysteresis so a wobble does not flap. */
    private static final float ENTER_MIN = 50f, ENTER_MAX = 150f;
    private static final float EXIT_MIN = 35f, EXIT_MAX = 165f;
    private static final long SETTLE_MS = 350;
    /** The fold report is debounced by the system already. */
    private static final long FOLD_SETTLE_MS = 150;
    /** Turned upright but the fold still not across: give up after this long. */
    private static final long UPRIGHT_GIVE_UP_MS = 2000;
    /** The landscape lock comes back this long after the panel goes. */
    private static final long RELEASE_UPRIGHT_MS = 300;
    /** A near-square inner screen: the fallback's only safe guess. */
    private static final float BOOK_MAX_ASPECT = 1.4f;

    private final Activity activity;
    private final FrameLayout root;
    private final View game;
    private final SecondaryDisplay secondary;
    private final Host host;
    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // One of these two sources.
    private final FoldInfo foldInfo;
    private FoldInfo.Fold fold;
    private final SensorManager sensors;
    private final Sensor hinge;
    private boolean listening, sensorSaysHalf;

    private SurfaceView panel;
    private VirtualDisplay display;
    private boolean active;
    private TabletopGeometry geometry;
    private volatile int gameWidth, gameHeight;
    private final RectF panelRect = new RectF();

    // Upright: the landscape lock is lifted. uprightRefused: it was, the window
    // turned, and the fold still did not run across it; not again until the
    // posture changes.
    private boolean upright, uprightRefused;
    private boolean restorePending;
    private int restoreTries;

    private final Runnable apply = this::apply;
    private final Runnable restoreGame = this::restoreGameLayout;
    private final Runnable releaseUpright = this::releaseUprightNow;
    private final Runnable giveUpUpright = this::giveUpUprightNow;

    private void releaseUprightNow()
    {
        if (!upright) host.setUprightAllowed(false);
    }

    private void giveUpUprightNow()
    {
        if (!upright || active || root.getHeight() <= root.getWidth()) return;
        Log.i(TAG, "Upright, but the fold does not run across the window; back to landscape");
        uprightRefused = true;
        setUpright(false);
    }

    // A knob for testing without folding anything, read once a second:
    //
    //     F=/sdcard/Android/data/<package>/files/tabletop_force
    //     adb shell "echo 1 > $F"     # behave as if half open
    //     adb shell "echo 0 > $F"     # behave as if flat
    //     adb shell "rm $F"           # back to the device's own posture
    //
    // Forced half open, the device's fold is used when it runs across the
    // window, and the window's middle otherwise.
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
     *
     * Its size is fixed here, so it has the panel's shape whatever the device
     * (TabletopGeometry.displaySize), and the compositor scales it into the
     * panel's view without distortion.
     */
    static VirtualDisplay createPanelDisplay(Activity activity)
    {
        try
        {
            Rect b = activity.getWindowManager().getMaximumWindowMetrics().getBounds();
            int[] size = TabletopGeometry.displaySize(
                Math.max(b.width(), b.height()), Math.min(b.width(), b.height()));
            int dpi = activity.getResources().getDisplayMetrics().densityDpi;
            DisplayManager dm = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
            VirtualDisplay d = dm.createVirtualDisplay("Silksong panel", size[0], size[1], dpi, null,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
            Log.i(TAG, "Panel display " + d.getDisplay().getDisplayId() + " created at "
                + size[0] + "x" + size[1]);
            return d;
        }
        catch (RuntimeException e)
        {
            Log.e(TAG, "Could not create the panel display", e);
            return null;
        }
    }

    /**
     * Whether this device folds, decided before the engine starts (the panel
     * display has to exist by then). A hinge-angle sensor says so; failing
     * that, the device-state service (public from Android 15) listing a
     * half-open state does.
     */
    static boolean supported(Context context)
    {
        SensorManager sm = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (sm != null && sm.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) != null) return true;
        return deviceStatesIncludeHalfOpen(context);
    }

    private static boolean deviceStatesIncludeHalfOpen(Context context)
    {
        if (Build.VERSION.SDK_INT < 35) return false;
        try
        {
            Class<?> managerClass = Class.forName("android.hardware.devicestate.DeviceStateManager");
            Object manager = context.getSystemService(managerClass);
            if (manager == null) return false;
            Class<?> state = Class.forName("android.hardware.devicestate.DeviceState");
            int halfOpen = state.getField("PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_HALF_OPEN").getInt(null);
            Method has = state.getMethod("hasProperty", int.class);
            for (Object s : (List<?>) managerClass.getMethod("getSupportedDeviceStates").invoke(manager))
                if ((Boolean) has.invoke(s, halfOpen)) return true;
        }
        catch (Throwable t)
        {
            Log.i(TAG, "Device states unavailable: " + t);
        }
        return false;
    }

    TabletopMode(Activity activity, FrameLayout root, View game, SecondaryDisplay secondary,
                 VirtualDisplay display, Host host)
    {
        this.activity = activity;
        this.root = root;
        this.game = game;
        this.secondary = secondary;
        this.display = display;
        this.host = host;
        density = activity.getResources().getDisplayMetrics().density;
        if (FoldInfo.available())
        {
            foldInfo = new FoldInfo(activity, this);
            sensors = null;
            hinge = null;
            Log.i(TAG, "Following the fold reported by the window extensions");
        }
        else
        {
            foldInfo = null;
            sensors = (SensorManager) activity.getSystemService(Context.SENSOR_SERVICE);
            hinge = sensors != null ? sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) : null;
            Log.i(TAG, hinge != null ? "No window extensions; hinge sensor: " + hinge.getName()
                : "No window extensions and no hinge sensor; tabletop mode only by tabletop_force");
        }
        // Flat until shown otherwise: the display draws into the sink.
        if (display != null)
        {
            android.graphics.Point size = new android.graphics.Point();
            display.getDisplay().getRealSize(size);
            sink = android.media.ImageReader.newInstance(size.x, size.y,
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

    /** The current split while active, else null. */
    TabletopGeometry geometry() { return active ? geometry : null; }

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

    void onResume()
    {
        handler.removeCallbacks(pollKnob);
        handler.post(pollKnob);
        if (foldInfo != null)
        {
            // Reports the current state straight away.
            foldInfo.start();
            return;
        }
        if (hinge == null || listening) return;
        listening = sensors.registerListener(this, hinge, SensorManager.SENSOR_DELAY_UI);
    }

    void onPause()
    {
        handler.removeCallbacks(pollKnob);
        if (foldInfo != null) foldInfo.stop();
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

    // -- posture -------------------------------------------------------------

    @Override public void onFoldChanged(FoldInfo.Fold next)
    {
        boolean wasHalf = fold != null && fold.halfOpen, half = next != null && next.halfOpen;
        if (wasHalf != half || (fold == null) != (next == null))
            Log.i(TAG, next != null ? "Fold: " + next : "No fold across the window");
        fold = next;
        handler.removeCallbacks(apply);
        handler.postDelayed(apply, FOLD_SETTLE_MS);
    }

    @Override public void onSensorChanged(SensorEvent event)
    {
        float angle = event.values[0];
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

    private boolean postureHalfOpen()
    {
        if (forced >= 0) return forced == 1;
        return foldInfo != null ? fold != null && fold.halfOpen : sensorSaysHalf;
    }

    /** The reported fold in the root's coordinates, or null. */
    private Rect foldInRoot()
    {
        if (fold == null) return null;
        Rect b = new Rect(fold.bounds);
        int[] at = new int[2];
        root.getLocationInWindow(at);
        b.offset(-at[0], -at[1]);
        return b;
    }

    /**
     * The fold when it runs right across the window, else null. The whole
     * width is required: just after the window turns, the last report is in
     * the old window's coordinates and must not be mistaken for a new one.
     */
    private Rect foldAcross(int w, int h)
    {
        if (foldInfo != null)
        {
            Rect b = foldInRoot();
            if (b != null && b.width() > b.height() && b.left <= 1 && b.right >= w - 1
                && b.top > 0 && b.bottom < h)
                return b;
        }
        else if (legacyHingeAcross(w, h)) return new Rect(0, h / 2, w, h / 2);
        return forced == 1 ? new Rect(0, h / 2, w, h / 2) : null;
    }

    /** Half open with the fold down a landscape window: standing up would put it across. */
    private boolean couldStandUpright(int w, int h)
    {
        if (foldInfo == null || uprightRefused || w <= h) return false;
        if (activity.isInMultiWindowMode()) return false;
        Rect b = foldInRoot();
        return b != null && b.height() > b.width() && b.top <= 1 && b.bottom >= h - 1;
    }

    /**
     * Without the window extensions: the hinge of a book-style foldable runs
     * top to bottom in its inner screen's natural orientation, so across a
     * landscape window only when the display is turned a quarter turn. That
     * holds for near-square inner screens only; a tall one is a clamshell,
     * whose fold runs the other way, and is left alone.
     */
    private boolean legacyHingeAcross(int w, int h)
    {
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        boolean quarterTurn = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270;
        Rect b = activity.getWindowManager().getMaximumWindowMetrics().getBounds();
        float aspect = Math.max(b.width(), b.height()) / (float) Math.max(1, Math.min(b.width(), b.height()));
        return quarterTurn && w > h && aspect <= BOOK_MAX_ASPECT;
    }

    private void apply()
    {
        int w = root.getWidth(), h = root.getHeight();
        if (display == null || w <= 0 || h <= 0) { leave(); return; }

        if (!postureHalfOpen())
        {
            uprightRefused = false;
            leave();
            setUpright(false);
            return;
        }

        Rect across = foldAcross(w, h);
        if (across != null)
        {
            handler.removeCallbacks(giveUpUpright);
            enter(new TabletopGeometry(w, h, across.top, across.bottom, density));
            return;
        }

        leave();
        if (couldStandUpright(w, h)) setUpright(true);
        else if (upright && h > w)
        {
            // Turned upright, and the fold not (yet) across: the report may
            // still be on its way. Give it a moment before giving up.
            handler.removeCallbacks(giveUpUpright);
            handler.postDelayed(giveUpUpright, UPRIGHT_GIVE_UP_MS);
        }
    }

    // -- the game's and the panel's views ------------------------------------

    /**
     * The engine's surface changes size only when it must, and never in the
     * same frame as anything else: two of the engine's surfaces changing at
     * once crashed its render thread. So the game's view is pinned to an
     * explicit size whenever the window is about to turn (the window turning
     * then changes nothing for it), and put back to the whole window a moment
     * after the panel goes, once the window is landscape again.
     */
    private void setUpright(boolean allowed)
    {
        if (upright == allowed) return;
        upright = allowed;
        handler.removeCallbacks(releaseUpright);
        if (allowed)
        {
            if (!active) pinGame(root.getWidth(), root.getHeight());
            Log.i(TAG, "Half open with the fold down the window: may turn upright");
            host.setUprightAllowed(true);
        }
        else
        {
            handler.removeCallbacks(giveUpUpright);
            handler.postDelayed(releaseUpright, RELEASE_UPRIGHT_MS);
            if (!active && !restorePending)
            {
                restorePending = true;
                handler.postDelayed(restoreGame, RELEASE_UPRIGHT_MS * 2);
            }
        }
    }

    private void pinGame(int w, int h)
    {
        if (w <= 0 || h <= 0) return;
        gameWidth = w;
        gameHeight = h;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) game.getLayoutParams();
        if (lp != null && lp.width == w && lp.height == h) return;
        game.setLayoutParams(new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.LEFT));
    }

    private void restoreGameLayout()
    {
        restorePending = false;
        if (active) return;
        android.view.ViewGroup.LayoutParams lp = game.getLayoutParams();
        if (!upright && lp != null && lp.width == FrameLayout.LayoutParams.MATCH_PARENT
            && lp.height == FrameLayout.LayoutParams.MATCH_PARENT)
        {
            // Already the whole window; nothing to wait for.
            restoreTries = 0;
            gameWidth = gameHeight = 0;
            return;
        }
        int w = root.getWidth(), h = root.getHeight();
        if (h > w && restoreTries++ < 10)
        {
            // Still upright: wait for the window to turn back first.
            restorePending = true;
            handler.postDelayed(restoreGame, 300);
            return;
        }
        restoreTries = 0;
        if (upright)
        {
            // Book posture on a device that may still be stood up: keep the
            // game pinned, at the window's size.
            if (w > h) pinGame(w, h);
            return;
        }
        gameWidth = gameHeight = 0;
        game.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void enter(TabletopGeometry geo)
    {
        handler.removeCallbacks(restoreGame);
        restorePending = false;
        restoreTries = 0;
        geometry = geo;
        int w = geo.windowW;
        gameWidth = w;
        gameHeight = geo.gameHeight();
        panelRect.set(geo.panelF());

        FrameLayout.LayoutParams gl = (FrameLayout.LayoutParams) game.getLayoutParams();
        if (gl == null || gl.width != w || gl.height != geo.gameHeight() || gl.gravity != (Gravity.TOP | Gravity.LEFT))
            game.setLayoutParams(new FrameLayout.LayoutParams(w, geo.gameHeight(), Gravity.TOP | Gravity.LEFT));

        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(
            geo.panel.width(), geo.panel.height(), Gravity.TOP | Gravity.LEFT);
        pp.leftMargin = geo.panel.left;
        pp.topMargin = geo.panel.top;
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
        else
        {
            FrameLayout.LayoutParams cur = (FrameLayout.LayoutParams) panel.getLayoutParams();
            if (cur == null || cur.width != pp.width || cur.height != pp.height
                || cur.leftMargin != pp.leftMargin || cur.topMargin != pp.topMargin)
                panel.setLayoutParams(pp);
        }

        if (!active)
        {
            active = true;
            Log.i(TAG, "Tabletop: " + geo);
            host.onTabletopChanged(true);
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
        // frame as the panel's goes.
        if (active)
        {
            handler.removeCallbacks(restoreGame);
            restorePending = true;
            handler.postDelayed(restoreGame, 1000);
            active = false;
            Log.i(TAG, "Tabletop off");
            host.onTabletopChanged(false);
        }
        else if (!restorePending) restoreGameLayout();
    }

    // -- the panel's display -------------------------------------------------

    private volatile boolean shown;
    private android.media.ImageReader sink;

    @Override public void surfaceCreated(SurfaceHolder holder) { }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height)
    {
        if (width <= 0 || height <= 0 || display == null || !active) return;
        // The display keeps the size it was made with: resizing it would
        // change the engine's surface on it. The view has the same shape and
        // is simply scaled into by the compositor.
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
