package dev.silksong.shell;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

// An on-screen controls layout, and the player's own edits to it.
//
// The format is GameHub's ("VJoy", a .gtheme zip: layout.json at formatVersion
// 2 plus an assets/ folder of icons), read as-is, so a layout someone built for
// the PC game under GameHub works here unchanged. Every control maps to
// keyboard keys, identified by Windows virtual-key codes, and those are what
// the game's own keyboard bindings expect -- so a layout made for the PC build
// already presses the right buttons here.
//
// Two files, both under <external files>/controls/:
//
//   theme/            an imported .gtheme, extracted (layout.json + assets/).
//                     Absent means the built-in layout, which has the same
//                     positions and keys and no art.
//   user.json         what the player changed in the editor: per profile, per
//                     control id, a position, a size and whether it is hidden;
//                     plus one opacity for everything. The theme is never
//                     rewritten, so "reset" is always possible and importing
//                     a different theme simply makes old edits not apply.
//
// Positions are fractions of the whole window, the centre of the control, as
// GameHub stores them. Sizes are density-independent, so a button is the same
// size under the thumb on a phone and on a foldable's inner screen.
final class ControlsLayout
{
    static final String TAG = "SilksongControls";

    static final String PROFILE_NORMAL = "normal";
    static final String PROFILE_TABLETOP = "tabletop";

    /** A button's height at scale 1, in dp. Its width is this times its aspect. */
    static final float BUTTON_DP = 48f;
    /** A joystick's diameter at scale 1, in dp. */
    static final float JOYSTICK_DP = 120f;

    /**
     * The game's actions and the key each one has in its default keyboard
     * layout (InputHandler.MapDefaultKeyboardLayout), which is what a button
     * presses. Names are the game's own, for the editor's picker; the letters
     * are the PC defaults, so a layout made for the PC game agrees with them.
     */
    static final class Action
    {
        final String name;
        final int vk;
        Action(String name, int vk) { this.name = name; this.vk = vk; }
    }

    static final Action[] ACTIONS = {
        new Action("Jump", 'Z'),
        new Action("Attack", 'X'),
        new Action("Dash", 'C'),
        new Action("Cast", 'A'),
        new Action("Super Dash", 'S'),
        new Action("Dream Nail", 'D'),
        new Action("Quick Cast", 'F'),
        new Action("Quick Map", 0x09),
        new Action("Taunt", 'V'),
        new Action("Inventory", 'I'),
        new Action("Inventory: Map", 'M'),
        new Action("Inventory: Journal", 'J'),
        new Action("Inventory: Tools", 'Q'),
        new Action("Inventory: Quests", 'T'),
        new Action("Pause", 0x1B),
        new Action("Confirm", 0x0D),
    };

    /** The action a key is the default for, or the key's own name. */
    static String actionName(int vk)
    {
        for (Action a : ACTIONS) if (a.vk == vk) return a.name;
        return android.view.KeyEvent.keyCodeToString(fromVirtualKey(vk)).replace("KEYCODE_", "");
    }

    static final class Icon
    {
        String path;
        float scale = 1f, alpha = 1f;
        /** GameHub's "crop" fills the control and trims the image; "fit" shows all of it. */
        boolean crop = true;
        Bitmap bitmap;
    }

    static final class Control
    {
        String id, type, label, shape;
        float x, y, scale = 1f, aspect = 1f;
        boolean hidden;
        int fill = 0x26000000, stroke = 0x3DFFFFFF, pressedFill = 0x15FFFFFF,
            pressedStroke = 0x80FFFFFF, stickFill = 0x4DFFFFFF, stickPressedFill = 0x15FFFFFF;
        float strokeWidth = 1f, alpha = 1f, activeAlpha = 1f;
        /** Button: { key }. Joystick: { up, down, left, right }. Android key codes. */
        int[] keys = new int[0];
        Icon icon, activeIcon, innerIcon, outerIcon, activeInnerIcon, activeOuterIcon;
        boolean dynamic, followFinger;
        float dynamicW, dynamicH;
        /** Where the layout put it, for "reset". */
        float defX, defY, defScale;
        /** For a button: the Windows virtual key it presses, and the layout's own. */
        int vk, defVk;
        /** Added by the player in the editor rather than by the layout. */
        boolean added;
        /** Hidden by the layout itself (a preset's arrangement), for "reset". */
        boolean defHidden;

        boolean rebound() { return !isJoystick() && vk != defVk; }

        void setKey(int newVk)
        {
            vk = newVk;
            keys = new int[] { fromVirtualKey(newVk) };
        }

        boolean isJoystick() { return "joystick".equals(type); }
    }

    final String profile;
    final List<Control> controls = new ArrayList<>();
    float opacity = 0.85f;
    String name = "Built-in";
    boolean themed;

    private ControlsLayout(String profile) { this.profile = profile; }

    static File dir(Context context)
    {
        File ext = context.getExternalFilesDir(null);
        return new File(ext != null ? ext : context.getFilesDir(), "controls");
    }

    static File themeDir(Context context) { return new File(dir(context), "theme"); }

    /**
     * A preset's own arrangement, inside its .gtheme beside layout.json:
     * { "name": ..., "profiles": { "normal"|"tabletop": { id: {x, y, scale, hidden} } } }
     * GameHub has one arrangement per layout; this port has two postures, so a
     * preset can say where its controls go in each. These are the layout's
     * defaults -- what Reset returns to -- not the player's edits.
     */
    static final String PRESET_FILE = "silksong.json";
    static File userFile(Context context) { return new File(dir(context), "user.json"); }

    /**
     * The layout for a profile, with the player's edits applied.
     *
     * @param tabletop for the tabletop profile: how the window is split (null:
     *        across the middle). Used only to place controls the player has
     *        not positioned yet.
     */
    static ControlsLayout load(Context context, String profile, int windowW, int windowH, TabletopGeometry tabletop)
    {
        ControlsLayout layout = new ControlsLayout(profile);
        JSONObject theme = null;
        File themeDir = themeDir(context);
        File themeJson = new File(themeDir, "layout.json");
        if (themeJson.isFile())
        {
            try
            {
                theme = new JSONObject(readText(themeJson));
                layout.themed = true;
            }
            catch (Exception e)
            {
                Log.e(TAG, "Imported layout is unreadable; using the built-in one", e);
            }
        }
        if (theme == null)
        {
            try { theme = new JSONObject(DEFAULT_LAYOUT); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
        layout.parse(theme, layout.themed ? themeDir : null, context);
        layout.addExtras(context);

        if (PROFILE_TABLETOP.equals(profile))
        {
            float density = context.getResources().getDisplayMetrics().density;
            TabletopGeometry geo = tabletop != null ? tabletop
                : TabletopGeometry.halves(windowW, windowH, density);
            List<Control> middle = new ArrayList<>();
            for (Control c : layout.controls)
                if (!placeForTabletop(c, geo)) middle.add(c);
            rowInStrip(middle, windowW, windowH, geo, density);
        }
        if (layout.themed) layout.applyPresetDefaults(new File(themeDir, PRESET_FILE));
        for (Control c : layout.controls)
        {
            c.defX = c.x;
            c.defY = c.y;
            c.defScale = c.scale;
            c.defHidden = c.hidden;
        }
        layout.applyEdits(context);
        return layout;
    }

    /** Puts every control back where the layout has it. */
    void reset()
    {
        List<Control> extras = new ArrayList<>();
        for (Control c : controls) if (c.added) extras.add(c);
        controls.removeAll(extras);
        for (Control c : controls)
        {
            if (!c.isJoystick()) c.setKey(c.defVk);
            c.x = c.defX;
            c.y = c.defY;
            c.scale = c.defScale;
            c.hidden = c.defHidden;
        }
        opacity = 0.85f;
    }

    /** Writes this profile's positions; other profiles' edits are kept. */
    void save(Context context)
    {
        try
        {
            File file = userFile(context);
            JSONObject root = file.isFile() ? new JSONObject(readText(file)) : new JSONObject();
            JSONObject profiles = root.optJSONObject("profiles");
            if (profiles == null) profiles = new JSONObject();
            JSONObject mine = new JSONObject();
            for (Control c : controls)
            {
                // Only what the player changed: a control left alone follows
                // the layout, including a new layout that reuses its id.
                boolean moved = Math.abs(c.x - c.defX) > 1e-4f || Math.abs(c.y - c.defY) > 1e-4f
                    || Math.abs(c.scale - c.defScale) > 1e-4f || c.hidden != c.defHidden;
                if (!moved && !c.added) continue;
                JSONObject o = new JSONObject();
                o.put("x", c.x);
                o.put("y", c.y);
                o.put("scale", c.scale);
                o.put("hidden", c.hidden);
                mine.put(c.id, o);
            }
            profiles.put(profile, mine);
            root.put("profiles", profiles);
            // What each button presses, and the buttons the player added, are
            // the same in every profile: only where things sit differs.
            JSONObject keys = new JSONObject();
            JSONArray extras = new JSONArray();
            for (Control c : controls)
            {
                if (c.added)
                {
                    JSONObject o = new JSONObject();
                    o.put("id", c.id);
                    o.put("vk", c.vk);
                    extras.put(o);
                }
                else if (c.rebound()) keys.put(c.id, c.vk);
            }
            root.put("keys", keys);
            root.put("extras", extras);
            root.put("opacity", opacity);
            file.getParentFile().mkdirs();
            File tmp = new File(file.getPath() + ".part");
            try (FileOutputStream out = new FileOutputStream(tmp))
            {
                out.write(root.toString(1).getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(file)) throw new java.io.IOException("rename to " + file);
            Log.i(TAG, "Saved the " + profile + " controls layout");
        }
        catch (Exception e)
        {
            Log.e(TAG, "Could not save the controls layout", e);
        }
    }

    /** A new button for an action, in the middle of the screen, selected by the editor. */
    Control addButton(int vk)
    {
        Control c = new Control();
        c.id = "added-" + Long.toHexString(System.currentTimeMillis());
        initAdded(c, vk);
        c.x = c.defX = 0.5f;
        c.y = c.defY = PROFILE_TABLETOP.equals(profile) ? 0.75f : 0.5f;
        controls.add(c);
        return c;
    }

    private static void initAdded(Control c, int vk)
    {
        c.type = "button";
        c.added = true;
        c.shape = "circle";
        c.scale = c.defScale = 1.1f;
        c.fill = 0x55000000;
        c.stroke = 0x80FFFFFF;
        c.strokeWidth = 1.5f;
        c.defVk = vk;
        c.setKey(vk);
    }

    private void addExtras(Context context)
    {
        File file = userFile(context);
        if (!file.isFile()) return;
        try
        {
            JSONArray extras = new JSONObject(readText(file)).optJSONArray("extras");
            if (extras == null) return;
            for (int i = 0; i < extras.length(); i++)
            {
                JSONObject o = extras.optJSONObject(i);
                if (o == null || !o.has("id")) continue;
                Control c = new Control();
                c.id = o.optString("id");
                initAdded(c, o.optInt("vk", 'D'));
                // Unplaced in this profile: the middle, as when it was added.
                c.x = 0.5f;
                c.y = PROFILE_TABLETOP.equals(profile) ? 0.75f : 0.5f;
                controls.add(c);
            }
        }
        catch (Exception e)
        {
            Log.e(TAG, "Ignoring unreadable added controls", e);
        }
    }

    private void applyPresetDefaults(File file)
    {
        if (!file.isFile()) return;
        try
        {
            JSONObject root = new JSONObject(readText(file));
            String presetName = root.optString("name", "");
            if (!presetName.isEmpty()) name = presetName;
            JSONObject profiles = root.optJSONObject("profiles");
            JSONObject mine = profiles != null ? profiles.optJSONObject(profile) : null;
            if (mine == null) return;
            for (Control c : controls)
            {
                JSONObject o = mine.optJSONObject(c.id);
                if (o == null) continue;
                c.x = clamp((float) o.optDouble("x", c.x), 0f, 1f);
                c.y = clamp((float) o.optDouble("y", c.y), 0f, 1f);
                c.scale = clamp((float) o.optDouble("scale", c.scale), 0.3f, 4f);
                c.hidden = o.optBoolean("hidden", c.hidden);
            }
        }
        catch (Exception e)
        {
            Log.e(TAG, "Ignoring an unreadable preset arrangement", e);
        }
    }

    private void applyEdits(Context context)
    {
        File file = userFile(context);
        if (!file.isFile()) return;
        try
        {
            JSONObject root = new JSONObject(readText(file));
            opacity = clamp((float) root.optDouble("opacity", opacity), 0.1f, 1f);
            JSONObject keys = root.optJSONObject("keys");
            if (keys != null)
                for (Control c : controls)
                    if (!c.isJoystick() && !c.added && keys.has(c.id)) c.setKey(keys.optInt(c.id, c.vk));
            JSONObject profiles = root.optJSONObject("profiles");
            JSONObject mine = profiles != null ? profiles.optJSONObject(profile) : null;
            if (mine == null) return;
            for (Control c : controls)
            {
                JSONObject o = mine.optJSONObject(c.id);
                if (o == null) continue;
                c.x = clamp((float) o.optDouble("x", c.x), 0f, 1f);
                c.y = clamp((float) o.optDouble("y", c.y), 0f, 1f);
                c.scale = clamp((float) o.optDouble("scale", c.scale), 0.3f, 4f);
                c.hidden = o.optBoolean("hidden", c.hidden);
            }
        }
        catch (Exception e)
        {
            Log.e(TAG, "Ignoring unreadable control edits", e);
        }
    }

    // -- tabletop placement --------------------------------------------------
    //
    // Half folded, the area under the fold holds the dual-screen panel in its
    // middle and leaves a strip either side, which is where the thumbs are
    // (TabletopGeometry). A layout authored for a 16:9 screen is laid into
    // those strips by keeping each control's distance from its own side of the
    // screen, measured in a 16:9 box as tall as that area -- which keeps a
    // right-hand cluster's shape instead of squashing it. Controls authored
    // near the middle (menu keys, usually) have no strip of their own; they
    // are lined up along the top of the left one. The player can move all of it.

    /** Places a side control; false for a middle one, which rowInStrip places. */
    private static boolean placeForTabletop(Control c, TabletopGeometry geo)
    {
        float top = geo.areaTopFraction(), strip = geo.stripFraction();
        float areaAspect = geo.windowW / (float) Math.max(1, geo.windowH - geo.areaTop);
        float boxW = Math.min(1f, (16f / 9f) / areaAspect);   // a 16:9 box, as a fraction of width
        if (c.x >= 0.35f && c.x <= 0.65f) return false;
        if (c.x < 0.35f) c.x = clamp(c.x * boxW, 0f, strip);
        else c.x = clamp(1f - (1f - c.x) * boxW, 1f - strip, 1f);
        c.y = top + c.y * (1f - top);
        return true;
    }

    private static void rowInStrip(List<Control> middle, int windowW, int windowH, TabletopGeometry geo, float density)
    {
        if (middle.isEmpty() || windowW <= 0 || windowH <= 0) return;
        java.util.Collections.sort(middle, (a, b) -> Float.compare(a.x, b.x));
        float stripPx = geo.stripFraction() * windowW, gap = 10f * density;
        float x = gap, y = windowH * geo.areaTopFraction() + gap, rowH = 0f;
        for (Control c : middle)
        {
            float h = (c.isJoystick() ? JOYSTICK_DP : BUTTON_DP) * c.scale * density;
            float w = c.isJoystick() ? h : h * c.aspect;
            if (x + w > stripPx - gap && x > gap)
            {
                x = gap;
                y += rowH + gap;
                rowH = 0f;
            }
            c.x = clamp((x + w / 2f) / windowW, 0f, 1f);
            c.y = clamp((y + h / 2f) / windowH, 0f, 1f);
            x += w + gap;
            rowH = Math.max(rowH, h);
        }
    }

    // -- parsing -------------------------------------------------------------

    private void parse(JSONObject root, File assets, Context context)
    {
        JSONObject n = root.optJSONObject("name");
        JSONObject locales = n != null ? n.optJSONObject("locales") : null;
        if (locales != null) name = locales.optString("default", name);
        float global = (float) optObject(root, "settings").optDouble("globalScale", 1.0);

        JSONArray list = root.optJSONArray("controls");
        if (list == null) return;
        int target = Math.round(context.getResources().getDisplayMetrics().density * JOYSTICK_DP * 2f);
        for (int i = 0; i < list.length(); i++)
        {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            Control c = new Control();
            c.id = o.optString("id", "control" + i);
            c.type = o.optString("type", "button");
            if (!"button".equals(c.type) && !"joystick".equals(c.type))
            {
                Log.w(TAG, "Skipping unsupported control type " + c.type);
                continue;
            }
            JSONObject pos = optObject(o, "position");
            c.x = clamp((float) pos.optDouble("x", 0.5), 0f, 1f);
            c.y = clamp((float) pos.optDouble("y", 0.5), 0f, 1f);
            c.scale = (float) pos.optDouble("scale", 1.0) * global;

            JSONObject props = optObject(o, "properties");
            c.aspect = clamp(parseFloat(props.optString("aspectRatio", "1"), 1f), 0.25f, 6f);
            c.dynamic = "true".equals(props.optString("isDynamic"));
            c.followFinger = "true".equals(props.optString("followFinger"));
            c.dynamicW = parseFloat(props.optString("dynamicRangeWidth", "0"), 0f);
            c.dynamicH = parseFloat(props.optString("dynamicRangeHeight", "0"), 0f);

            JSONObject look = optObject(o, "appearance");
            c.label = look.isNull("label") ? null : look.optString("label", null);
            c.shape = look.optString("shape", "circle");
            c.fill = color(look, "fillColor", c.fill);
            c.stroke = color(look, "strokeColor", c.stroke);
            c.pressedFill = color(look, "pressedFillColor", c.pressedFill);
            c.pressedStroke = color(look, "pressedStrokeColor", c.pressedStroke);
            c.stickFill = color(look, "stickFillColor", c.stickFill);
            c.stickPressedFill = color(look, "stickPressedFillColor", c.stickPressedFill);
            c.strokeWidth = (float) look.optDouble("strokeWidth", c.strokeWidth);
            c.alpha = (float) look.optDouble("alpha", 1.0);
            c.activeAlpha = (float) look.optDouble("activeAlpha", 1.0);
            if (assets != null)
            {
                c.icon = icon(look, "icon", assets, target);
                if (c.icon == null && props.has("backgroundImage"))
                {
                    JSONObject bg = new JSONObject();
                    try
                    {
                        bg.put("iconName", props.optString("backgroundImage"));
                        bg.put("scale", parseFloat(props.optString("backgroundScale", "1"), 1f));
                        bg.put("alpha", parseFloat(props.optString("backgroundAlpha", "1"), 1f));
                        bg.put("scaleMode", props.optString("backgroundScaleMode", "fit").toLowerCase(java.util.Locale.ROOT));
                        JSONObject holder = new JSONObject();
                        holder.put("icon", bg);
                        c.icon = icon(holder, "icon", assets, target);
                    }
                    catch (org.json.JSONException ignored) { }
                }
                c.activeIcon = icon(look, "activeIcon", assets, target);
                c.innerIcon = icon(look, "innerIcon", assets, target);
                c.outerIcon = icon(look, "outerIcon", assets, target);
                c.activeInnerIcon = icon(look, "activeInnerIcon", assets, target);
                c.activeOuterIcon = icon(look, "activeOuterIcon", assets, target);
            }

            JSONObject map = optObject(o, "mapping");
            if (c.isJoystick())
            {
                c.keys = new int[] {
                    key(optObject(map, "up")), key(optObject(map, "down")),
                    key(optObject(map, "left")), key(optObject(map, "right")) };
            }
            else
            {
                c.vk = virtualKey(map);
                c.defVk = c.vk;
                c.keys = new int[] { key(map) };
            }
            controls.add(c);
        }
    }

    private static Icon icon(JSONObject look, String field, File assets, int target)
    {
        JSONObject o = look.optJSONObject(field);
        if (o == null) return null;
        String path = o.optString("iconName", null);
        if (path == null || path.isEmpty()) return null;
        File file = new File(assets, path);
        // A theme is somebody else's zip: its paths stay inside its folder.
        try
        {
            if (!file.getCanonicalPath().startsWith(assets.getCanonicalPath() + File.separator))
                return null;
        }
        catch (java.io.IOException e) { return null; }
        if (!file.isFile()) return null;
        Icon icon = new Icon();
        icon.path = path;
        icon.scale = (float) o.optDouble("scale", 1.0);
        icon.alpha = (float) o.optDouble("alpha", 1.0);
        icon.crop = !"fit".equals(o.optString("scaleMode", "crop"));
        icon.bitmap = decode(file, target);
        return icon.bitmap != null ? icon : null;
    }

    /** Decoded no larger than needed: theme icons are often 1-2 MB PNGs. */
    private static Bitmap decode(File file, int target)
    {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getPath(), opts);
    }

    /** A GameHub mapping to an Android key code, or 0 if it is not a key. */
    private static int key(JSONObject mapping)
    {
        return fromVirtualKey(virtualKey(mapping));
    }

    /**
     * The key a mapping presses, as a Windows virtual key. GameHub maps a
     * control either to a keyboard key or to an Xbox controller button; a
     * controller button is turned into the key for the same action, using the
     * game's default controller bindings (measured: InputHandler's bindings for
     * a pad, A=Jump B=Cast X=Attack Y=Dream Nail, RT=Dash LT=Super Dash,
     * LB=Quick Map RB=Quick Cast, R3=Taunt, Start=Pause, View=Inventory).
     */
    static int virtualKey(JSONObject mapping)
    {
        if (mapping == null) return 0;
        String kind = mapping.optString("class");
        if ("keyboard".equals(kind)) return mapping.optInt("keyCode", 0);
        if (!"gamepad".equals(kind)) return 0;
        switch (mapping.optString("button").toLowerCase(java.util.Locale.ROOT))
        {
            case "a": return 'Z';
            case "b": return 'A';
            case "x": return 'X';
            case "y": return 'D';
            case "lb": case "l1": return 0x09;
            case "rb": case "r1": return 'F';
            case "lt": case "l2": return 'S';
            case "rt": case "r2": return 'C';
            case "rightstickclick": case "rs": case "r3": return 'V';
            case "start": case "menu": case "options": return 0x1B;
            case "select": case "back": case "view": case "share": return 'I';
            case "dpadup": case "up": return 0x26;
            case "dpaddown": case "down": return 0x28;
            case "dpadleft": case "left": return 0x25;
            case "dpadright": case "right": return 0x27;
            default:
                Log.w(TAG, "No key for controller button " + mapping.optString("button"));
                return 0;
        }
    }

    /** Windows virtual-key code to Android key code. */
    static int fromVirtualKey(int vk)
    {
        if (vk >= 'A' && vk <= 'Z') return KeyEvent.KEYCODE_A + (vk - 'A');
        if (vk >= '0' && vk <= '9') return KeyEvent.KEYCODE_0 + (vk - '0');
        if (vk >= 0x70 && vk <= 0x7B) return KeyEvent.KEYCODE_F1 + (vk - 0x70);
        if (vk >= 0x60 && vk <= 0x69) return KeyEvent.KEYCODE_NUMPAD_0 + (vk - 0x60);
        switch (vk)
        {
            case 0x08: return KeyEvent.KEYCODE_DEL;
            case 0x09: return KeyEvent.KEYCODE_TAB;
            case 0x0D: return KeyEvent.KEYCODE_ENTER;
            case 0x10: case 0xA0: return KeyEvent.KEYCODE_SHIFT_LEFT;
            case 0xA1: return KeyEvent.KEYCODE_SHIFT_RIGHT;
            case 0x11: case 0xA2: return KeyEvent.KEYCODE_CTRL_LEFT;
            case 0xA3: return KeyEvent.KEYCODE_CTRL_RIGHT;
            case 0x12: case 0xA4: return KeyEvent.KEYCODE_ALT_LEFT;
            case 0xA5: return KeyEvent.KEYCODE_ALT_RIGHT;
            case 0x1B: return KeyEvent.KEYCODE_ESCAPE;
            case 0x20: return KeyEvent.KEYCODE_SPACE;
            case 0x21: return KeyEvent.KEYCODE_PAGE_UP;
            case 0x22: return KeyEvent.KEYCODE_PAGE_DOWN;
            case 0x23: return KeyEvent.KEYCODE_MOVE_END;
            case 0x24: return KeyEvent.KEYCODE_MOVE_HOME;
            case 0x25: return KeyEvent.KEYCODE_DPAD_LEFT;
            case 0x26: return KeyEvent.KEYCODE_DPAD_UP;
            case 0x27: return KeyEvent.KEYCODE_DPAD_RIGHT;
            case 0x28: return KeyEvent.KEYCODE_DPAD_DOWN;
            case 0x2D: return KeyEvent.KEYCODE_INSERT;
            case 0x2E: return KeyEvent.KEYCODE_FORWARD_DEL;
            case 0xBA: return KeyEvent.KEYCODE_SEMICOLON;
            case 0xBB: return KeyEvent.KEYCODE_EQUALS;
            case 0xBC: return KeyEvent.KEYCODE_COMMA;
            case 0xBD: return KeyEvent.KEYCODE_MINUS;
            case 0xBE: return KeyEvent.KEYCODE_PERIOD;
            case 0xBF: return KeyEvent.KEYCODE_SLASH;
            case 0xC0: return KeyEvent.KEYCODE_GRAVE;
            case 0xDB: return KeyEvent.KEYCODE_LEFT_BRACKET;
            case 0xDC: return KeyEvent.KEYCODE_BACKSLASH;
            case 0xDD: return KeyEvent.KEYCODE_RIGHT_BRACKET;
            case 0xDE: return KeyEvent.KEYCODE_APOSTROPHE;
            default: return 0;
        }
    }

    /** GameHub stores ARGB as an unsigned 32-bit number, which may not fit an int. */
    private static int color(JSONObject o, String field, int fallback)
    {
        if (!o.has(field) || o.isNull(field)) return fallback;
        return (int) o.optLong(field, fallback & 0xFFFFFFFFL);
    }

    private static JSONObject optObject(JSONObject o, String field)
    {
        JSONObject v = o != null ? o.optJSONObject(field) : null;
        return v != null ? v : new JSONObject();
    }

    private static float parseFloat(String s, float fallback)
    {
        try { return Float.parseFloat(s); }
        catch (Exception e) { return fallback; }
    }

    static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    static String readText(File file) throws java.io.IOException
    {
        try (InputStream in = new FileInputStream(file))
        {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    // The built-in layout: the controls of a widely used community layout for
    // the PC game, as positions and key codes only. Arrow keys on a floating
    // stick on the left; the game's default keyboard bindings on the right.
    private static final String DEFAULT_LAYOUT = "{\"formatVersion\":2,"
        + "\"name\":{\"locales\":{\"default\":\"Built-in\"}},\"controls\":["
        + joystick("move", 0.2179f, 0.6606f, 1.2f)
        + "," + button("jump", 0.8481f, 0.6315f, 1.1f, 1f, "Jump", 'Z', "circle")
        + "," + button("attack", 0.7824f, 0.7842f, 1.1f, 1f, "Attack", 'X', "circle")
        + "," + button("dash", 0.8770f, 0.4523f, 1.1f, 1f, "Dash", 'C', "circle")
        + "," + button("cast", 0.7000f, 0.6200f, 1.1f, 1f, "Cast", 'A', "circle")
        + "," + button("superdash", 0.7000f, 0.4500f, 1.0f, 1f, "Super Dash", 'S', "circle")
        + "," + button("dreamnail", 0.7850f, 0.4700f, 1.0f, 1f, "Dream Nail", 'D', "circle")
        + "," + button("q", 0.9168f, 0.7509f, 1.1f, 1f, "Tools", 'Q', "circle")
        + "," + button("f", 0.8512f, 0.9037f, 1.1f, 1f, "Quick Cast", 'F', "circle")
        + "," + button("inventory", 0.9551f, 0.4592f, 1.1f, 1f, "Inventory", 'I', "circle")
        + "," + button("map", 0.9199f, 0.2995f, 1.2f, 1f, "Map", 0x09, "circle")
        + "," + button("pause", 0.5405f, 0.8690f, 0.6f, 2.25f, "Pause", 0x1B, "rect")
        + "]}";

    private static String button(String id, float x, float y, float scale, float aspect,
                                 String label, int vk, String shape)
    {
        return "{\"id\":\"" + id + "\",\"type\":\"button\",\"position\":{\"x\":" + x
            + ",\"y\":" + y + ",\"scale\":" + scale + "},\"appearance\":{\"label\":\"" + label
            + "\",\"shape\":\"" + shape + "\",\"fillColor\":1426063360,\"strokeColor\":2164260863,"
            + "\"strokeWidth\":1.5},\"mapping\":{\"class\":\"keyboard\",\"keyCode\":" + vk
            + "},\"properties\":{\"aspectRatio\":\"" + aspect + "\"}}";
    }

    private static String joystick(String id, float x, float y, float scale)
    {
        return "{\"id\":\"" + id + "\",\"type\":\"joystick\",\"position\":{\"x\":" + x
            + ",\"y\":" + y + ",\"scale\":" + scale + "},\"appearance\":{\"fillColor\":855638016,"
            + "\"strokeColor\":2164260863,\"strokeWidth\":2,\"stickFillColor\":1728053247},"
            + "\"mapping\":{\"class\":\"directional\","
            + "\"up\":{\"class\":\"keyboard\",\"keyCode\":38},"
            + "\"down\":{\"class\":\"keyboard\",\"keyCode\":40},"
            + "\"left\":{\"class\":\"keyboard\",\"keyCode\":37},"
            + "\"right\":{\"class\":\"keyboard\",\"keyCode\":39}},"
            + "\"properties\":{\"isDynamic\":\"true\",\"followFinger\":\"true\","
            + "\"dynamicRangeWidth\":\"0.3\",\"dynamicRangeHeight\":\"0.6\"}}";
    }
}
