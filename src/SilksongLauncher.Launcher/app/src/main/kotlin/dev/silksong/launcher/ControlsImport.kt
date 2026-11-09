// ControlsImport — brings a GameHub on-screen controls layout (.gtheme) in.
//
// A .gtheme is a zip: layout.json (GameHub's "VJoy" format, version 2) and an
// assets/ folder of icons. GameHub keeps its layouts in its own private
// storage, but it can share a layout as a .gtheme file, and community layouts
// download as exactly that. The game process reads the result from
// <external files>/controls/theme (see shell/ControlsLayout.java), where the
// player's position edits are kept separately, so importing never loses them
// for a layout that has the same controls.
//
// The file is somebody else's zip, so it is extracted defensively: entries
// must stay inside the folder, the whole thing has a size ceiling, and it goes
// into a scratch folder first, replacing the current theme only once it is
// complete and has a layout.json that parses.

package dev.silksong.launcher

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

object ControlsImport {

    /** Generous for a theme (the popular ones are ~10 MB) and small for a phone. */
    private const val MAX_BYTES = 64L * 1024 * 1024
    private const val MAX_ENTRIES = 2000

    fun controlsDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "controls")

    fun themeDir(context: Context): File = File(controlsDir(context), "theme")

    /** The imported layout's name, or null when the built-in layout is in use. */
    fun currentName(context: Context): String? {
        val json = File(themeDir(context), "layout.json")
        if (!json.isFile) return null
        return runCatching {
            JSONObject(json.readText()).optJSONObject("name")
                ?.optJSONObject("locales")?.optString("default")
                ?.takeIf { it.isNotBlank() } ?: "Imported layout"
        }.getOrNull()
    }

    /** Extracts [uri] as the current theme and returns its name. Throws with a readable message. */
    fun import(context: Context, uri: Uri): String {
        val dir = controlsDir(context)
        dir.mkdirs()
        val staging = File(dir, "theme.importing")
        staging.deleteRecursively()
        staging.mkdirs()
        val root = staging.canonicalPath + File.separator
        var total = 0L
        var entries = 0
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("could not open the file")
            ZipInputStream(input.buffered()).use { zip ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entries > MAX_ENTRIES) throw IllegalArgumentException("too many files in it")
                    val out = File(staging, entry.name)
                    if (!out.canonicalPath.startsWith(root)) {
                        throw IllegalArgumentException("it contains a path outside its own folder")
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                        continue
                    }
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o ->
                        while (true) {
                            val n = zip.read(buf)
                            if (n <= 0) break
                            total += n
                            if (total > MAX_BYTES) throw IllegalArgumentException("it is larger than 64 MB")
                            o.write(buf, 0, n)
                        }
                    }
                }
            }
            if (entries == 0) throw IllegalArgumentException("it is not a .gtheme (zip) file")
            val layout = File(staging, "layout.json")
            if (!layout.isFile) throw IllegalArgumentException("it has no layout.json, so it is not a GameHub layout")
            val json = JSONObject(layout.readText())
            val controls = json.optJSONArray("controls")
            if (controls == null || controls.length() == 0) {
                throw IllegalArgumentException("its layout has no controls")
            }
            val theme = themeDir(context)
            val old = File(dir, "theme.old")
            old.deleteRecursively()
            if (theme.exists() && !theme.renameTo(old)) throw IllegalStateException("could not replace the current layout")
            if (!staging.renameTo(theme)) {
                old.renameTo(theme)
                throw IllegalStateException("could not install the layout")
            }
            old.deleteRecursively()
            // The player's edits belong to the layout they were made on.
            // Layouts are often made from one another and keep its control
            // ids, so stale edits would otherwise drag the new layout's
            // controls back to where the old one had them.
            if (File(dir, "user.json").delete()) {
                LauncherLog.log("controls: cleared the edits made to the previous layout")
            }
            val name = currentName(context) ?: "Imported layout"
            LauncherLog.log("controls: imported \"$name\" (${controls.length()} controls, $total bytes)")
            return name
        } catch (t: Throwable) {
            staging.deleteRecursively()
            LauncherLog.log("controls: import failed", t)
            throw t
        }
    }

    /** Back to the built-in layout. The player's edits stay on file. */
    fun useBuiltIn(context: Context) {
        themeDir(context).deleteRecursively()
        LauncherLog.log("controls: using the built-in layout")
    }
}
