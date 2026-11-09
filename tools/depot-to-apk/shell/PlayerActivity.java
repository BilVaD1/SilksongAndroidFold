package dev.silksong.shell;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import com.unity3d.player.IUnityPlayerLifecycleEvents;
import com.unity3d.player.UnityPlayerForActivityOrService;

// Hosts the Unity player in an Activity.
//
// Unity ships an activity that does this, as a .java file in the Android
// player module, and the build used to compile it. That is Unity's code, and
// compiling it put it in our dex -- so the APK carried Unity-authored classes.
// The whole point of this port is that it ships nothing Unity-made: the engine,
// the player classes and the game are all fetched or built on the device from
// the user's own copy. So the activity is ours, written against the player's
// public API rather than derived from theirs.
//
// The API is small and entirely public: construct the player with a Context
// and a lifecycle-events callback, hand its FrameLayout to setContentView,
// and forward the Activity lifecycle to the matching player methods.
//
// Two details are not free choices:
//
//   - The field must be called mUnityPlayer. The engine's native side looks it
//     up on the activity by name, and Unity's own source carries a comment
//     saying as much. Renaming it compiles perfectly and fails at runtime.
//
//   - com.unity3d.player.* is not in this APK. These types resolve at runtime
//     from the dex the app builds on the device out of the player module it
//     downloads (see SilksongApp and UnityDex). They are on the compile
//     classpath here and nowhere in the shipped dex, which is exactly the
//     distinction that matters.
public class PlayerActivity extends Activity implements IUnityPlayerLifecycleEvents
{
    // Name fixed by the engine's native code. Do not rename.
    protected UnityPlayerForActivityOrService mUnityPlayer;
    private SecondaryDisplay secondaryDisplay;
    // The player's view sits in a root of ours rather than being the content
    // view itself, so that the on-screen controls can be drawn over it and,
    // half folded, the panel can sit beside it.
    private FrameLayout root;
    private TouchControlsView controls;
    private TabletopMode tabletop;
    private boolean controlsHiddenForPad;
    private final PadRumble padRumble = new PadRumble();

    @Override protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        // Constructing the player is what starts the engine: it loads the
        // native libraries and brings up the render surface. Everything that
        // has to be in place first -- the staged libraries, the data package -- 
        // is done by GameActivity before it calls up into here.
        secondaryDisplay = new SecondaryDisplay(this);
        java.util.Map<String, String> settings = ShellSettings.read(this);
        // Before the player: the engine can only use a second display it
        // finds when it starts (see TabletopMode.createPanelDisplay).
        boolean tabletopWanted = ShellSettings.bool(settings, ShellSettings.TABLETOP, false)
            && ShellSettings.bool(settings, ShellSettings.DUAL_SCREEN, true)
            && TabletopMode.hasHinge(this);
        android.hardware.display.VirtualDisplay panelDisplay =
            tabletopWanted ? TabletopMode.createPanelDisplay(this) : null;
        mUnityPlayer = new UnityPlayerForActivityOrService(this, this);
        FrameLayout game = mUnityPlayer.getFrameLayout();
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(game, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        if (ShellSettings.bool(settings, ShellSettings.TOUCH_CONTROLS, false))
        {
            controls = new TouchControlsView(this);
            controls.setKeySink(this::injectKey);
            root.addView(controls, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (r - l != or - ol || b - t != ob - ot) root.post(this::loadControls);
            });
        }
        if (panelDisplay != null)
        {
            tabletop = new TabletopMode(this, root, game, secondaryDisplay, panelDisplay, active -> loadControls());
            if (controls != null) controls.setPassthrough(new PanelPassthrough());
        }

        setContentView(root);
        game.requestFocus();
    }

    /** The controls for the current posture, re-read so the editor's changes apply. */
    private void loadControls()
    {
        if (controls == null || root.getWidth() <= 0 || root.getHeight() <= 0) return;
        boolean half = tabletop != null && tabletop.isActive();
        int w = root.getWidth(), h = root.getHeight();
        float strip = (w - TabletopMode.panelWidth(w, h)) / 2f / w;
        controls.setLayout(ControlsLayout.load(this,
            half ? ControlsLayout.PROFILE_TABLETOP : ControlsLayout.PROFILE_NORMAL,
            w, h, strip));
    }

    private void injectKey(int keyCode, boolean down)
    {
        if (mUnityPlayer == null) return;
        long now = SystemClock.uptimeMillis();
        KeyEvent event = new KeyEvent(now, now, down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
            keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE, InputDevice.SOURCE_KEYBOARD);
        mUnityPlayer.injectEvent(event);
    }

    /** Fingers the controls had to take, handed on to the half-folded panel. */
    private final class PanelPassthrough implements TouchControlsView.Passthrough
    {
        @Override public boolean claims(float x, float y)
        {
            return tabletop != null && tabletop.isActive() && tabletop.panelRect().contains(x, y);
        }

        @Override public void touch(int id, int phase, float x, float y, long time)
        {
            RectF r = tabletop.panelRect();
            if (r.isEmpty()) return;
            secondaryDisplay.forwardPointer(id, phase,
                (x - r.left) / r.width(), (y - r.top) / r.height(), time);
        }

        @Override public void cancel(int id)
        {
            secondaryDisplay.forwardPointer(id, 4, 0f, 0f, SystemClock.uptimeMillis());
        }
    }

    /**
     * Called by DualScreenV2 through JNI: -1 when there is no foldable panel
     * (the second screen, if any, is a real one), 1 while the half-folded
     * panel is on screen, 0 while it is folded away.
     */
    public int getPanelState()
    {
        return tabletop != null ? tabletop.panelState() : -1;
    }

    /** Called by the resolution patch (AndroidWindow) through JNI, from Unity's thread. */
    public int[] getGameViewSize()
    {
        return tabletop != null ? tabletop.gameViewSize() : new int[] { 0, 0 };
    }

    // A physical controller in use: get the touch controls out of the way
    // until the screen is touched again.
    private void noticePad(android.view.InputEvent event)
    {
        if (controls == null || controlsHiddenForPad) return;
        int source = event.getSource();
        if ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
        {
            controlsHiddenForPad = true;
            controls.releaseAll();
            controls.setVisibility(View.GONE);
        }
    }

    // Controller events are watched on their way in rather than in onKeyDown:
    // the player's view takes most of them first, and onKeyDown only sees the
    // ones it leaves.
    // The pad_probe knob (see PadProbe.cs) also logs what Android hands the
    // activity, below Unity, for anything that is not the touchscreen.
    private boolean padProbe;

    private void probe(android.view.InputEvent event)
    {
        if (!padProbe) return;
        InputDevice d = event.getDevice();
        String name = d != null ? d.getName() : "?";
        if (event instanceof KeyEvent)
        {
            KeyEvent k = (KeyEvent) event;
            if (k.getRepeatCount() > 0) return;
            android.util.Log.i("SilksongPadProbe", "key " + KeyEvent.keyCodeToString(k.getKeyCode())
                + (k.getAction() == KeyEvent.ACTION_DOWN ? " DOWN" : " UP") + " src=0x"
                + Integer.toHexString(k.getSource()) + " '" + name + "'");
        }
        else if (event instanceof MotionEvent)
        {
            MotionEvent m = (MotionEvent) event;
            int a = m.getActionMasked();
            if (a == MotionEvent.ACTION_MOVE || a == MotionEvent.ACTION_HOVER_MOVE) return;
            android.util.Log.i("SilksongPadProbe", "motion " + MotionEvent.actionToString(a)
                + " buttons=0x" + Integer.toHexString(m.getButtonState()) + " src=0x"
                + Integer.toHexString(m.getSource()) + " '" + name + "'");
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event)
    {
        probe(event);
        padRumble.notice(event);
        noticePad(event);
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event)
    {
        probe(event);
        padRumble.notice(event);
        noticePad(event);
        return super.dispatchGenericMotionEvent(event);
    }

    /**
     * Called by AndroidRumble through JNI, from Unity's thread: the game's two
     * motor values for the controller in use. False when there is no
     * controller that can rumble, so the caller can use the phone instead.
     */
    public boolean playPadRumble(float large, float small)
    {
        return padRumble.play(large, small);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event)
    {
        if (event.getSource() != InputDevice.SOURCE_TOUCHSCREEN) probe(event);
        if (controlsHiddenForPad && event.getActionMasked() == MotionEvent.ACTION_DOWN)
        {
            controlsHiddenForPad = false;
            controls.setVisibility(View.VISIBLE);
        }
        return super.dispatchTouchEvent(event);
    }

    @Override protected void onDestroy()
    {
        // Before super: the player tears down the native side, and the
        // Activity is still fully alive for that.
        if (secondaryDisplay != null) secondaryDisplay.onDestroy();
        if (mUnityPlayer != null) mUnityPlayer.destroy();
        // After the player: it must not be drawing to the panel when that goes.
        if (tabletop != null) tabletop.onDestroy();
        super.onDestroy();
    }

    @Override protected void onStart()
    {
        super.onStart();
        if (secondaryDisplay != null) secondaryDisplay.onStart();
        if (mUnityPlayer != null) mUnityPlayer.onStart();
    }

    @Override protected void onStop()
    {
        if (secondaryDisplay != null) secondaryDisplay.onStop();
        super.onStop();
        if (mUnityPlayer != null) mUnityPlayer.onStop();
    }

    // Called by the dual-screen patch through JNI, from Unity's thread.
    public void setSecondaryDisplayEnabled(boolean enabled)
    {
        secondaryDisplay.setEnabled(enabled);
    }

    public double[] pollSecondaryDisplayTouches()
    {
        return secondaryDisplay.poll();
    }

    @Override protected void onResume()
    {
        super.onResume();
        if (mUnityPlayer != null) mUnityPlayer.onResume();
        if (tabletop != null) tabletop.onResume();
        // Back from the editor, or from anywhere the layout could have changed.
        loadControls();
        java.io.File ext = getExternalFilesDir(null);
        padProbe = ext != null && new java.io.File(ext, "pad_probe").isFile();
    }

    @Override protected void onPause()
    {
        super.onPause();
        if (mUnityPlayer != null) mUnityPlayer.onPause();
        if (tabletop != null) tabletop.onPause();
        if (controls != null) controls.releaseAll();
    }

    @Override protected void onNewIntent(Intent intent)
    {
        super.onNewIntent(intent);
        // The player reads the launch intent back off the activity, so this
        // has to be the current one before it is told about the change.
        setIntent(intent);
        if (mUnityPlayer != null) mUnityPlayer.newIntent(intent);
    }

    @Override public void onConfigurationChanged(Configuration newConfig)
    {
        super.onConfigurationChanged(newConfig);
        if (mUnityPlayer != null) mUnityPlayer.configurationChanged(newConfig);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus)
    {
        super.onWindowFocusChanged(hasFocus);
        if (mUnityPlayer != null) mUnityPlayer.windowFocusChanged(hasFocus);
    }

    @Override public void onRequestPermissionsResult(
        int requestCode, String[] permissions, int[] grantResults)
    {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (mUnityPlayer != null)
            mUnityPlayer.permissionResponse(this, requestCode, permissions, grantResults);
    }

    // Input. The render surface receives most events on its own, but events
    // that arrive at the Activity instead have to be handed over explicitly -- 
    // gamepad buttons and sticks in particular, which is the difference
    // between a controller working and appearing dead.
    @Override public boolean onKeyDown(int keyCode, KeyEvent event)
    {
        return injected(event) || super.onKeyDown(keyCode, event);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event)
    {
        return injected(event) || super.onKeyUp(keyCode, event);
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event)
    {
        return injected(event) || super.onGenericMotionEvent(event);
    }

    @Override public boolean onTouchEvent(MotionEvent event)
    {
        return injected(event) || super.onTouchEvent(event);
    }

    private boolean injected(android.view.InputEvent event)
    {
        return mUnityPlayer != null && mUnityPlayer.injectEvent(event);
    }

    // IUnityPlayerLifecycleEvents. The engine calls these when it unloads or
    // quits itself, which is not the same as the Activity being destroyed.
    @Override public void onUnityPlayerUnloaded()
    {
        // Unloaded but not finished: the process stays, so drop to the
        // background rather than leaving a blank window in front of the user.
        moveTaskToBack(true);
    }

    @Override public void onUnityPlayerQuitted()
    {
        // The engine has finished shutting down. Nothing to add: the process
        // is on its way out and the Activity teardown is already running.
    }
}
