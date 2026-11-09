package dev.silksong.shell;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

// The launcher's settings, as the game process sees them: the same
// game-settings.txt the patches read (see Settings.cs). The launcher runs in
// its own process and writes this file immediately before starting the game,
// so reading it once at startup is the whole protocol.
final class ShellSettings
{
    static final String TOUCH_CONTROLS = "touch_controls";
    static final String TABLETOP = "tabletop_mode";
    static final String DUAL_SCREEN = "dualscreen_enabled";

    private ShellSettings() { }

    static Map<String, String> read(Context context)
    {
        Map<String, String> values = new HashMap<>();
        File dir = context.getExternalFilesDir(null);
        File file = dir != null ? new File(dir, "game-settings.txt") : null;
        if (file == null || !file.isFile()) return values;
        try
        {
            for (String line : ControlsLayout.readText(file).split("\n"))
            {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                values.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        catch (Exception e)
        {
            Log.e("SilksongShell", "Could not read the game settings", e);
        }
        return values;
    }

    static boolean bool(Map<String, String> values, String key, boolean fallback)
    {
        String v = values.get(key);
        if (v == null) return fallback;
        return "true".equals(v) || "1".equals(v);
    }
}
