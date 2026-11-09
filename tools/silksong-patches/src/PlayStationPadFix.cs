// PlayStationPadFix -- the inventory and pause on a PlayStation pad.
//
// A PlayStation pad on Android arrives through Unity's Input System as
// DualShock4GamepadAndroid, which the game's NewUnityInputDevice wraps with
// the PlayStation 4 style. Every button then reaches the game (measured on a
// DualSense Edge, logging every press at each layer) -- but the game's InputHandler only binds
// Open Inventory and Pause for the gamepad types it ships consoles and PCs
// with, and a PlayStation pad on this platform is none of them. Both actions
// had no controller binding at all, so the inventory, the map inside it and
// the pause menu could not be opened. GameHub does not have the problem
// because it presents the pad to the game as an Xbox controller.
//
// So they go where an Xbox pad has them: Open Inventory on Create (Share,
// View's position) and Pause on Options (Menu's). Only when the game left
// the action without any controller binding, so a player's own remap wins.
//
// Separately, the touchpad click: Android never delivers it -- the touchpad is
// a separate input device, its click is not a gamepad key, and the Input
// System's Android DualShock layout has no control for it -- so anything the
// game binds to it (or to a Select/Back/View this pad does not have) is also
// bound to Create.
//
// The bindings are the game's own, read at runtime. The game rebuilds them
// whenever a controller connects or the player remaps, so this checks again
// every second, and adds nothing twice; each binding it adds is logged once.

#if UNITY_ANDROID && !UNITY_EDITOR
using System;
using System.Collections.Generic;
using InControl;
using UnityEngine;

public class PlayStationPadFix : MonoBehaviour
{
    const string Tag = "[PlayStationPadFix] ";

    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
    static void Bootstrap()
    {
        var go = new GameObject("PlayStationPadFix");
        DontDestroyOnLoad(go);
        go.AddComponent<PlayStationPadFix>();
    }

    // Controls a PlayStation pad on Android cannot produce, that the game may
    // bind for one: the touchpad, and the generic names for the left command
    // button, which on this pad is called Share.
    // By name, because InControl's enum has grown over versions and a name
    // this version lacks should be skipped, not fail the whole build.
    static readonly InputControlType[] Unreachable =
        Controls("TouchPadButton", "Select", "Back", "View");

    static readonly InputControlType Replacement = Controls("Share")[0];

    static InputControlType[] Controls(params string[] names)
    {
        var found = new List<InputControlType>();
        foreach (string name in names)
        {
            try { found.Add((InputControlType)Enum.Parse(typeof(InputControlType), name)); }
            catch (Exception) { }
        }
        return found.ToArray();
    }

    float _next;

    void Update()
    {
        if (Time.unscaledTime < _next) return;
        _next = Time.unscaledTime + 1f;
        try { Check(); }
        catch (Exception e) { Debug.LogWarning(Tag + e.Message); }
    }

    void Check()
    {
        InputDevice device = InputManager.ActiveDevice;
        if (device == null || !IsPlayStation(device.DeviceStyle)) return;
        if (device.GetControl(Replacement) == InputControl.Null) return;

        GameManager gm = GameManager.instance;
        InputHandler handler = gm != null ? gm.inputHandler : null;
        if (handler == null || handler.inputActions == null) return;

        int added = 0;
        foreach (PlayerAction action in handler.inputActions.Actions)
        {
            bool wantsReplacement = false, hasReplacement = false;
            foreach (BindingSource binding in action.Bindings)
            {
                var source = binding as DeviceBindingSource;
                if (source == null) continue;
                if (source.Control == Replacement) hasReplacement = true;
                else if (Array.IndexOf(Unreachable, source.Control) >= 0
                         && device.GetControl(source.Control) == InputControl.Null)
                    wantsReplacement = true;
            }
            if (!wantsReplacement || hasReplacement) continue;
            action.AddBinding(new DeviceBindingSource(Replacement));
            added++;
            Debug.Log(Tag + "'" + action.Name + "' is bound to a control '" + device.Name
                      + "' cannot press on Android; Create (" + Replacement + ") does it too");
        }

        // The two the game binds for some gamepad types only, and never for a
        // PlayStation pad on this platform: measured, the DualSense Edge's
        // action set had no controller binding for either, so the inventory
        // (and the map in it) and the pause menu could not be opened at all.
        // They go where an Xbox pad has them: View -> Create, Menu -> Options.
        added += BindIfUnbound(handler.inputActions.OpenInventory, Replacement, device);
        added += BindIfUnbound(handler.inputActions.Pause, Options, device);

    }

    static readonly InputControlType Options = Controls("Options")[0];

    static int BindIfUnbound(PlayerAction action, InputControlType control, InputDevice device)
    {
        if (action == null || device.GetControl(control) == InputControl.Null) return 0;
        foreach (BindingSource binding in action.Bindings)
            if (binding is DeviceBindingSource) return 0;
        action.AddBinding(new DeviceBindingSource(control));
        Debug.Log(Tag + "'" + action.Name + "' had no controller binding; now " + control);
        return 1;
    }

    static bool IsPlayStation(InputDeviceStyle style)
    {
        return style.ToString().StartsWith("PlayStation", StringComparison.Ordinal);
    }
}
#endif
