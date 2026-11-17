# Silksong Android — Fold edition

> **This is a fork of [jakobkhansen/SilksongAndroid](https://github.com/jakobkhansen/SilksongAndroid).**
> All the core work (the port itself, dual screen, Steam integration, mod support) is
> upstream: go there for the official releases, issues and the latest changes. This fork
> adds on-screen controls and foldable support on top of it, on the `fold` branch.

## What this fork adds

- **Half-folded (tabletop) mode for foldables**: hold the phone half open like a laptop and
  the game moves to the top half, with the dual-screen menu on the bottom half. Opening the
  phone flat gives the game the whole screen again. Fold position and posture come from
  Android's window extensions (Jetpack WindowManager), so it is not tied to one model.
- **On-screen touch controls**: buttons and a floating stick drawn over the game, with
  separate *Full screen* and *Half folded* layouts, opacity, and a layout editor in the
  launcher.
- **In-game controls editor**: hold ✎ at the top of the screen to move and resize the
  controls without leaving the game (the game pauses while you edit).
- **GameHub layout import**: `.gtheme` layouts made in GameHub's *Keys and layout* editor
  import as-is, icons included; buttons mapped to keyboard keys or Xbox buttons both work.
- **Controller improvements**: rumble on the controller itself (both motors), and
  **Create**/**Options** on PlayStation controllers (DualShock 4, DualSense, DualSense Edge)
  open the inventory and pause.

Details are in [On-screen controls and foldables](#on-screen-controls-and-foldables) below.
The APKs on the upstream Releases page don't include these features; build this branch
yourself (see [Building from source](#building-from-source)).

### Verified on

- **Samsung Galaxy Z Fold 8 Ultra**: half-folded mode, on-screen controls, in-game editor
  and GameHub layout import tested on device.

Other foldables (passport-shaped ones like the Pixel Fold or OnePlus Open, and flip phones)
should work through the same code path but have not been tested on real hardware yet.
Reports are welcome.

### Demo

<!-- Video from the Galaxy Z Fold 8 Ultra goes here (drag the file into GitHub's README editor to upload it). -->

---

**Hollow Knight: Silksong** Android port with dual-screen capabilites and optional
Steam integration for game files and cloud saves.

<p align="center">
  <img src="docs/icon.png" alt="Silksong Android app icon" width="180" />
  <br />
  <em>Thanks to Kaz Kirigiri for artwork and design sketches!</em>
</p>

<p align="center">
  <img width="676" height="751" alt="IMG_1698 (3)" src="https://github.com/user-attachments/assets/54d383c5-69fd-48d2-b201-54bbd94a2e43" />
</p>


## Features

- **Dual screen**: Full implementation of the in-game menu for dual screen devices. Show map, use items, change your crest and more
- **Foldables (half-folded)**: Hold a foldable half open like a laptop and the game moves to the top half, with the dual-screen menu on the bottom half (see [On-screen controls and foldables](#on-screen-controls-and-foldables))
- **On-screen controls (optional)**: Touch buttons and a floating stick, movable and resizable, and GameHub layouts (`.gtheme`) can be imported
- **Steam integration (optional)**: Sign in to Steam if you want the app to download game files and/or your Steam cloud saves for you
- **High performance**: Compiles to native arm64 via IL2CPP and uses Vulkan shaders
- **Fully open source and legal**: Supply your own game files either manually or through Steam sign-in (the app downloads them for you)
- **Compilation on device**: Download the APK and supply the game files, porting happens on device (20–30 min on Snapdragon 8 Gen 2)
- **Mod support (beta)**: BepInEx 5 plugin support (see [Mod support](#modsupport))
- **QoL settings**: Skip intro, set resolution, auto upload/download cloud saves etc.
- **Any device**: Any Android device works, single screen as well.

## Setup

1. Download the latest APK from
   [Releases](https://github.com/jakobkhansen/SilksongAndroid/releases/latest)
   and install it.
2. Supply your own game files. Either copy them across from your PC yourself,
   or let the app fetch them for you by signing in to Steam.
3. Press the button. The app fetches everything it needs and builds the game.
   The build takes 20–30 minutes on a Snapdragon 8 Gen 2.
4. Play!

Any device should do (tested on the AYN Thor and the Retroid Pocket Flip 2). Open an issue
if your device doesn't work.

### Supplying the game files yourself

If you'd rather not sign in, download the game's **Linux** depot on a PC with
[DepotDownloader](https://github.com/SteamRE/DepotDownloader). The Linux files are the
ones the port is built from; the Windows (1030301) and macOS (1030302) depots will not do,
and the app rejects them:

```sh
DepotDownloader -app 1030300 -depot 1030303 -username <your account> -password <your password> -dir silksong
```

Copy the whole `silksong` folder onto the device, as long as it is on
the device's own storage. Then press **Choose folder** in the app and pick it.

```
silksong/                          <-- pick THIS one
├── Hollow Knight Silksong.x86_64
├── UnityPlayer.so
└── Hollow Knight Silksong_Data/
    ├── Managed/
    ├── MonoBleedingEdge/
    ├── Plugins/
    ├── Resources/
    ├── StreamingAssets/
    ├── globalgamemanagers
    ├── resources.assets
    └── ...
```

**Wherever you put them, leave them there.** The game is several gigabytes of content that
is never copied into the app: it is read from that folder every time you play, and every
app update that rebuilds the game reads it too. Deleting or moving the folder stops the
game from starting. The app leaves a `SILKSONG-DO-NOT-DELETE.txt` in there saying so.

## Mod support (beta)

BepInEx 5 plugins work, with one catch: since the games compiles to C++ code to get
maximum performance, mods need to be compiled too, so installing or removing a mod means
rebuilding. You can still enable/disable mods without rebuilding. 

1. Open **Mods** from the launcher and press **Install a mod from a folder**.
   Pick the folder the mod came in — the whole folder is copied in, so a mod
   that is a plugin plus its own libraries and config needs no sorting out.
2. Say yes when it offers to rebuild, or carry on and rebuild later.
3. Config files appear in `mods/config` after the first launch.

The Mods screen lists everything it found, marks each one **built** or **not built**, and
says how many patches it applied and every one it could not. The rebuild is incremental,
so installing a new mod should not take long.

**What does not work**: transpilers, patch targets computed at runtime,
`Reflection.Emit`, and loading a DLL discovered at runtime. There is no IL left
by the time the game runs, so nothing can be patched then. The mods screen
names every patch a plugin could not apply, before the build starts.
Plugins with definite missing APIs or required dependencies are marked failed
and left out, rather than breaking compilation for the other mods.

### The configuration menu

Mods that expose settings expect [BepInEx's Configuration
Manager](https://github.com/BepInEx/BepInEx.ConfigurationManager/releases) to be there to
draw them. Install it the same way as any other mod.

Open it with L3+R3. That binding lives in `mods/config/BepInEx.cfg` and because it is a
BepInEx setting like any other, the menu lists it, under Advanced settings.

### Tested mods

Built and played on device. Links go to the page each was downloaded from.

- [BepInEx 5 + Configuration Manager](https://www.nexusmods.com/hollowknightsilksong/mods/26) (only needed for configuration menu)
- [AutoMap](https://www.nexusmods.com/hollowknightsilksong/mods/31)
- [SaveScopedConfig](https://www.nexusmods.com/hollowknightsilksong/mods/1123) (required by AutoMap)
- [Bonfire Teleport](https://www.nexusmods.com/hollowknightsilksong/mods/156) (use the normal in-game map to teleport)
- [Stakes of Marika – Rebirth Anywhere](https://www.nexusmods.com/hollowknightsilksong/mods/46) (partial, no custom spawnpoint)
- [Healthbar & Damage Show](https://www.nexusmods.com/hollowknightsilksong/mods/28)

If a mod is not working for you, feel free to open an issue, but I cannot guarantee that
all mods will be supported.

## On-screen controls and foldables

Both are in the launcher's settings, and both are off by default.

**On-screen controls.** Touch buttons and a floating stick drawn over the game.
They press the same keyboard keys the PC version uses (arrows to move, Z, X, C
and so on), so the game's own bindings apply. **Edit layout** opens the editor:
tap a control to select it, drag to move it, pinch or use −/+ to resize it, hide
the ones you don't need, and set the opacity. There are two layouts to edit,
*Full screen* and *Half folded*, because a half-open foldable is held
differently. You can also adjust the layout without leaving the game: hold the
✎ button at the top of the screen, then drag and pinch the controls. The game
pauses while you edit. **Done** (or Back) saves the layout, **Cancel** discards
your changes. This edits whichever layout is in use (full screen or half
folded). It only moves and resizes controls. The controls hide while a
controller is in use and come back on the next touch.

**GameHub layouts.** A layout made in GameHub's *Keys and layout* editor for the
PC game works as-is: export or download it as a `.gtheme` file and choose
**Import GameHub layout**. Buttons mapped to keyboard keys press those keys;
buttons mapped to Xbox controller buttons do what that button does in the
game (A jumps, X attacks, View opens the inventory, and so on). Its icons come along, and **Use built-in** goes back
to the plain layout. Your position edits are kept separately from the layout
file. In the editor, **Action** changes what the selected button does and
**+ Add** adds a button for any of the game's actions (Dream Nail, Cast,
Super Dash and the rest), so a layout that lacks one can still do it.

**Half-folded mode** (needs *Dual screen* on). Hold a foldable half open like a
laptop and the game takes the half above the fold, with the dual-screen menu in
the middle of the half below it and room for the on-screen controls on either
side. Open the phone flat and the game takes the whole screen again. The fold's
position and posture come from the phone itself, so this works on every kind of
foldable: on a Galaxy Z Fold up to 7 or a Fold Ultra the fold already runs
across the game, while on a wider, passport-shaped one (Galaxy Z Fold 8, Pixel
Fold, OnePlus Open) or a flip phone you stand the phone up, and the screen turns
upright to put the fold across it. The game itself stays landscape. Phones that
don't fold are unaffected.

**Controllers.** The game's rumble plays on the controller itself (both
motors), falling back to the phone when no controller is in use. On a
PlayStation controller (DualShock 4, DualSense, DualSense Edge), **Create**
opens the inventory and **Options** pauses -- the game only gives those two a
controller button on the platforms it shipped for, and Android's DualSense
touchpad click never reaches it.

## Steam Cloud Saves

If you sign in to Steam, you also get your Steam Cloud saves. Pull before you play, push
when you're done, or let the launcher do both automatically (see settings). Save conflicts
are handled, but back your saves up if you want to be certain nothing is lost.

Signing in, by QR code or password, goes through
[JavaSteam](https://github.com/Longi94/JavaSteam), an open-source Steam client library.
Your password is never stored: the only thing kept is the login token Steam issues in
return, encrypted, and it never leaves the device.

## AI assistance

Much of this project was written with AI assistance. Everything in it is reviewed and
tested on real hardware before release.

## Legal

This repository and the APK contain **no game content and nothing Unity-made**. Silksong
is © Team Cherry, and none of its code, art or audio is distributed here. The APK is a
build system: it downloads Unity's toolchain, takes *your* game files, and compiles a
playable build on your own device.

The tooling is MIT-licensed; see [LICENSE](LICENSE). Third-party open-source
components shipped in the APK are listed in [NOTICE.md](NOTICE.md), which also
records what the APK deliberately does *not* contain.

## Building from source

You don't need Unity or any game files to build the APK:

```bash
make player     # once: fetch Unity's Android player module (~642 MB)
make surgery    # once: build bundle-surgery
make dev        # rebuild, repackage, install
```

Requires an Android SDK, JDK 17+ and the .NET 8 SDK; on Windows use Git Bash.
`make docker-apk` does the same in a container. See [COPILOT.md](COPILOT.md)
for the full development loop.
