package dev.silksong.shell;

import android.os.Build;
import android.os.CombinedVibration;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;

// Rumble on the controller in the player's hands.
//
// The game mixes its rumble every frame and hands it to the controller through
// Unity's Input System, whose Android backend has no rumble at all, so a
// DualSense or an Xbox pad stays still however hard Hornet is hit. Android
// itself can drive a controller's motors -- InputDevice.getVibratorManager(),
// API 31 -- and reports them for any pad whose kernel driver supports force
// feedback (hid-playstation, xpadneo, hid-microsoft...). So AndroidRumble asks
// this to play the game's two motor values on the last controller used, and
// only falls back to the phone's own motor when there is no such controller.
//
// Two vibrators are taken to be the strong (left) and weak (right) motors, the
// order Android gives them for a FF_RUMBLE device; one vibrator gets the
// stronger of the two.
final class PadRumble
{
    private static final String TAG = "SilksongPadRumble";
    /** Slightly longer than the game's refresh, so a held rumble has no gaps. */
    private static final long EFFECT_MS = 90;

    private int deviceId = -1;
    private VibratorManager manager;
    private int[] ids = new int[0];
    private boolean playing;

    /** Called for every key and motion event the activity sees. */
    void notice(InputEvent event)
    {
        int source = event.getSource();
        boolean pad = (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
        if (!pad || event.getDeviceId() == deviceId) return;
        deviceId = event.getDeviceId();
        manager = null;
        ids = new int[0];
        if (Build.VERSION.SDK_INT < 31) return;
        InputDevice device = InputDevice.getDevice(deviceId);
        if (device == null) return;
        VibratorManager vm = device.getVibratorManager();
        int[] found = vm.getVibratorIds();
        if (found.length == 0)
        {
            Log.i(TAG, "'" + device.getName() + "' has no motors Android can drive");
            return;
        }
        manager = vm;
        ids = found;
        Log.i(TAG, "Rumble on '" + device.getName() + "': " + found.length + " motor(s)");
    }

    /**
     * Plays one refresh of the game's rumble. Returns false when there is no
     * controller to play it on, so the caller can use the phone instead.
     */
    synchronized boolean play(float large, float small)
    {
        VibratorManager vm = manager;
        int[] motors = ids;
        if (vm == null || motors.length == 0) return false;
        // The device may have gone (switched off, out of range).
        if (InputDevice.getDevice(deviceId) == null)
        {
            manager = null;
            ids = new int[0];
            return false;
        }
        try
        {
            int strong = amplitude(large), weak = amplitude(small);
            if (strong == 0 && weak == 0)
            {
                if (playing) vm.cancel();
                playing = false;
                return true;
            }
            CombinedVibration.ParallelCombination all = CombinedVibration.startParallel();
            if (motors.length >= 2)
            {
                if (strong > 0) all.addVibrator(motors[0], effect(vm.getVibrator(motors[0]), strong));
                if (weak > 0) all.addVibrator(motors[1], effect(vm.getVibrator(motors[1]), weak));
            }
            else
            {
                all.addVibrator(motors[0], effect(vm.getVibrator(motors[0]), Math.max(strong, weak)));
            }
            vm.vibrate(all.combine());
            playing = true;
            return true;
        }
        catch (RuntimeException e)
        {
            Log.w(TAG, "Controller rumble failed; using the phone", e);
            manager = null;
            ids = new int[0];
            return false;
        }
    }

    private static int amplitude(float v)
    {
        if (v < 0.02f) return 0;
        return Math.max(1, Math.min(255, Math.round(v * 255f)));
    }

    private static VibrationEffect effect(Vibrator motor, int amplitude)
    {
        return VibrationEffect.createOneShot(EFFECT_MS,
            motor.hasAmplitudeControl() ? amplitude : VibrationEffect.DEFAULT_AMPLITUDE);
    }
}
