// PadProbe -- what a controller's buttons actually arrive as, at every layer.
//
// A controller's input crosses three translations before the game sees it:
//
//     Android key code  -> Unity Input System Gamepad control
//                       -> the game's NewUnityInputDevice (InControl controls)
//                       -> the game's bindings
//
// and a button that "does nothing" can be lost at any of them. InputProbe
// watches InControl's devices coming and going; this watches individual
// presses, at each layer, so one pass over every button on a pad answers
// which translation drops which button. Written for the DualSense Edge, which
// Android reports perfectly well and the game only half understands.
//
// Off unless asked for; the knob file is re-read every second.
//
//     F=/sdcard/Android/data/com.jakobkhansen.silksong/files/pad_probe
//     adb shell "echo on=1 > $F"
//     adb logcat -s Unity | grep PadProbe

#if UNITY_ANDROID && !UNITY_EDITOR
using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using UnityEngine;
using UnityEngine.InputSystem;
using UnityEngine.InputSystem.Controls;

public class PadProbe : MonoBehaviour
{
    const string FILE = "pad_probe";
    const string Tag = "[PadProbe] ";

    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
    static void Bootstrap()
    {
        var go = new GameObject("PadProbe");
        DontDestroyOnLoad(go);
        go.AddComponent<PadProbe>();
    }

    bool _on;
    float _nextKnob;
    string _devicesSeen = "";
    readonly bool[] _legacy = new bool[20];
    readonly Dictionary<string, bool> _incontrol = new Dictionary<string, bool>();

    void Update()
    {
        if (Time.unscaledTime >= _nextKnob)
        {
            _nextKnob = Time.unscaledTime + 1f;
            bool on = false;
            try
            {
                string path = Path.Combine(Application.persistentDataPath, FILE);
                on = File.Exists(path) && File.ReadAllText(path).Contains("on=1");
            }
            catch (Exception) { }
            if (on && !_on) Debug.Log(Tag + "on");
            _on = on;
        }
        if (!_on) return;

        try { Devices(); } catch (Exception e) { Debug.Log(Tag + "devices: " + e.Message); }
        try { InputSystemEdges(); } catch (Exception e) { Debug.Log(Tag + "input system: " + e.Message); }
        try { LegacyEdges(); } catch (Exception e) { Debug.Log(Tag + "legacy: " + e.Message); }
        try { InControlEdges(); } catch (Exception e) { Debug.Log(Tag + "incontrol: " + e.Message); }
    }

    void Devices()
    {
        var sb = new StringBuilder();
        foreach (var g in Gamepad.all)
        {
            var d = g.description;
            sb.Append(" [IS '").Append(g.name).Append("' layout=").Append(g.layout)
              .Append(" type=").Append(g.GetType().Name)
              .Append(" product='").Append(d.product).Append("' iface=").Append(d.interfaceName).Append(']');
        }
        var ic = InControl.InputManager.Devices;
        if (ic != null)
            foreach (var dev in ic)
                if (dev != null)
                    sb.Append(" [IC '").Append(dev.Name).Append("' type=").Append(dev.GetType().Name)
                      .Append(" style=").Append(dev.DeviceStyle).Append(" attached=").Append(dev.IsAttached).Append(']');
        string now = sb.ToString();
        if (now == _devicesSeen) return;
        _devicesSeen = now;
        Debug.Log(Tag + "devices:" + now);
    }

    void InputSystemEdges()
    {
        foreach (var g in Gamepad.all)
            foreach (var c in g.allControls)
            {
                var b = c as ButtonControl;
                if (b == null) continue;
                if (b.wasPressedThisFrame) Debug.Log(Tag + "IS DOWN " + c.path);
                else if (b.wasReleasedThisFrame) Debug.Log(Tag + "IS UP   " + c.path);
            }
    }

    void LegacyEdges()
    {
        for (int i = 0; i < _legacy.Length; i++)
        {
            bool down = Input.GetKey(KeyCode.JoystickButton0 + i);
            if (down == _legacy[i]) continue;
            _legacy[i] = down;
            Debug.Log(Tag + "LEGACY " + (down ? "DOWN" : "UP  ") + " joystick button " + i);
        }
    }

    void InControlEdges()
    {
        var ic = InControl.InputManager.Devices;
        if (ic == null) return;
        foreach (var dev in ic)
        {
            if (dev == null || !dev.IsAttached) continue;
            foreach (var control in dev.Controls)
            {
                if (control == null) continue;
                string key = dev.GetHashCode() + ":" + control.Target;
                bool down = control.IsPressed;
                bool was;
                _incontrol.TryGetValue(key, out was);
                if (down == was) continue;
                _incontrol[key] = down;
                Debug.Log(Tag + "IC " + (down ? "DOWN " : "UP   ") + control.Target + " on '" + dev.Name + "'");
            }
        }
    }
}
#endif
