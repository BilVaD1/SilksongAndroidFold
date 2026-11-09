# On-screen controls presets

`.gtheme` files in this folder are built into the APK and offered in the
launcher's **Settings → Choose layout**, so a layout can be picked without
finding and downloading the file on the phone.

They are **not committed** (`*.gtheme` is ignored here). Community GameHub
layouts carry their authors' art, often drawn from the game's, and this
repository should not redistribute it. A build from a checkout without presets
simply offers the built-in layout and the file import.

## Making a preset

A preset is an ordinary GameHub `.gtheme` (a zip with `layout.json` and
`assets/`). Download or export one from GameHub and copy it here; the file
name is what the list shows.

It can also carry this port's own arrangement in a `silksong.json` beside
`layout.json`, because GameHub has one arrangement per layout and this port
has two postures:

```json
{
  "name": "hollow2",
  "profiles": {
    "normal":   { "<control id>": { "x": 0.5, "y": 0.5, "scale": 1.1, "hidden": false } },
    "tabletop": { "<control id>": { "x": 0.9, "y": 0.8, "scale": 1.4, "hidden": true } }
  }
}
```

`normal` is full screen and `tabletop` is half folded; positions are fractions
of the window, of the control's centre. Controls a profile leaves out keep the
layout's own position (or, half folded, the automatic one). These are the
preset's defaults, so **Reset** in the editor returns to them.

The easiest way to write one is to arrange the layout in the editor and copy
the `profiles` section of `controls/user.json` from the app's files directory
(`/sdcard/Android/data/<package>/files/controls/user.json`).
