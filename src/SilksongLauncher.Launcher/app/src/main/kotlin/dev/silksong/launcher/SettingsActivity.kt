// SettingsActivity — a tiny standalone Activity for the launcher's
// user-tweakable behaviour flags. Currently:
//
//   Auto-pull before game launch — when enabled AND a Steam token is
//     on file, tapping Launch runs a pull FIRST and only then starts
//     the game, so the player always boots on their freshest cloud
//     save — the same model Steam uses. analyzePull downloads files
//     where cloud is strictly newer; if a local save is newer than the
//     cloud, the conflict dialog asks the user to keep local or keep
//     remote (or cancel, which aborts the launch).
//
//   Auto-push on game exit — when enabled AND a Steam token is on
//     file, the launcher runs a `safe push` the next time it gains
//     focus after the user returned from playing the game. A conflict
//     (cloud newer than local) raises the same keep-local / keep-remote
//     dialog as the manual Push button; Cancel aborts, nothing uploads.
//
//   Show perf overlay — toggles the in-game OnGUI HUD.
//
// All toggles default to OFF so the launcher behaves identically to
// the manual-button-only baseline until the user opts in. Persisted
// via SettingsStore (SharedPreferences).

package dev.silksong.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    private lateinit var settings: SettingsStore
    private lateinit var swAutoPull: Switch
    private lateinit var swAutoPush: Switch
    private lateinit var swPerfOverlay: Switch
    private lateinit var swSkipIntro: Switch
    private lateinit var swDualScreen: Switch
    private lateinit var swShowTopHud: Switch
    private lateinit var swWideAspect: Switch
    private lateinit var swTabletop: Switch
    private lateinit var swTouchControls: Switch
    private lateinit var btnClearBuild: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = SettingsStore(this)

        swAutoPull = findViewById(R.id.sw_auto_pull)
        swAutoPush = findViewById(R.id.sw_auto_push)
        swPerfOverlay = findViewById(R.id.sw_perf_overlay)
        swSkipIntro = findViewById(R.id.sw_skip_intro)
        swDualScreen = findViewById(R.id.sw_dual_screen)
        swShowTopHud = findViewById(R.id.sw_show_top_hud)
        swWideAspect = findViewById(R.id.sw_wide_aspect)

        val btnBack: Button = findViewById(R.id.btn_settings_back)

        swAutoPull.isChecked = settings.autoPull
        swAutoPush.isChecked = settings.autoPush
        swPerfOverlay.isChecked = settings.perfOverlay
        swSkipIntro.isChecked = settings.skipIntro
        swDualScreen.isChecked = settings.dualScreen
        swShowTopHud.isChecked = settings.showTopHud
        swShowTopHud.isEnabled = swDualScreen.isChecked
        swWideAspect.isChecked = settings.wideAspect

        // Persist on every toggle — no separate Save button; the
        // settings screen is tiny enough that "click to toggle" is
        // its own commit. setOnCheckedChangeListener fires for the
        // initial isChecked = ... assignment too, so set listeners
        // AFTER seeding the initial state to avoid logging spurious
        // writes.
        swAutoPull.setOnCheckedChangeListener { _, checked ->
            settings.autoPull = checked
            LauncherLog.log("Settings: auto-pull → $checked")
        }
        swAutoPush.setOnCheckedChangeListener { _, checked ->
            settings.autoPush = checked
            LauncherLog.log("Settings: auto-push → $checked")
        }
        swPerfOverlay.setOnCheckedChangeListener { _, checked ->
            settings.perfOverlay = checked
            LauncherLog.log("Settings: perf overlay → $checked (takes effect on next game launch)")
        }
        swSkipIntro.setOnCheckedChangeListener { _, checked ->
            settings.skipIntro = checked
            LauncherLog.log("Settings: skip intro → $checked (takes effect on next game launch)")
        }
        swDualScreen.setOnCheckedChangeListener { _, checked ->
            settings.dualScreen = checked
            swShowTopHud.isEnabled = checked
            swTabletop.isEnabled = checked
            LauncherLog.log("Settings: dual screen → $checked (next game launch; requires DualScreen build)")
        }
        swShowTopHud.setOnCheckedChangeListener { _, checked ->
            settings.showTopHud = checked
            LauncherLog.log("Settings: show health HUD on top screen → $checked (takes effect on next game launch)")
        }
        swWideAspect.setOnCheckedChangeListener { _, checked ->
            settings.wideAspect = checked
            LauncherLog.log("Settings: unlock aspect ratio → $checked (takes effect on next game launch)")
        }

        swTabletop = findViewById(R.id.sw_tabletop)
        swTabletop.isChecked = settings.tabletopMode
        swTabletop.isEnabled = swDualScreen.isChecked
        swTabletop.setOnCheckedChangeListener { _, checked ->
            settings.tabletopMode = checked
            LauncherLog.log("Settings: half-folded mode → $checked (takes effect on next game launch)")
        }

        swTouchControls = findViewById(R.id.sw_touch_controls)
        swTouchControls.isChecked = settings.touchControls
        swTouchControls.setOnCheckedChangeListener { _, checked ->
            settings.touchControls = checked
            LauncherLog.log("Settings: on-screen controls → $checked (takes effect on next game launch)")
        }
        findViewById<Button>(R.id.btn_edit_controls).setOnClickListener { openControlsEditor() }
        findViewById<Button>(R.id.btn_import_controls).setOnClickListener {
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
            runCatching { startActivityForResult(pick, REQUEST_IMPORT_CONTROLS) }
                .onFailure { LauncherLog.log("controls: no file picker", it) }
        }
        findViewById<Button>(R.id.btn_builtin_controls).setOnClickListener { chooseLayout() }
        showControlsLayout()

        btnBack.setOnClickListener { finish() }

        btnClearBuild = findViewById(R.id.btn_clear_build)
        btnClearBuild.setOnClickListener { confirmClearBuild() }
        // Offered only when there is a build to clear. A destructive-looking
        // button that does nothing is worse than no button.
        btnClearBuild.isEnabled = BuildReset.hasBuild(this)
        btnClearBuild.alpha = if (btnClearBuild.isEnabled) 1f else 0.4f

        // The same screen the porting flow offers, because the person who
        // needs a log is as likely to have reached this screen as that one.
        findViewById<Button>(R.id.btn_settings_logs).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
    }

    /**
     * The built-in layout, or one of the presets built into this APK (see
     * ControlsImport.presets). Choosing a preset is an import from the APK,
     * so it starts from the preset's own arrangement, as an import does.
     */
    private fun chooseLayout() {
        val presets = ControlsImport.presets(this)
        val names = listOf(getString(R.string.settings_controls_builtin_name)) +
            presets.map { ControlsImport.presetLabel(it) }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_choose_controls)
            .setItems(names.toTypedArray()) { _, which ->
                if (which == 0) {
                    ControlsImport.useBuiltIn(this)
                    showControlsLayout()
                    return@setItems
                }
                val file = presets[which - 1]
                Thread({
                    val result = runCatching { ControlsImport.importPreset(this, file) }
                    runOnUiThread {
                        showControlsLayout()
                        val message = result.fold(
                            { getString(R.string.settings_controls_imported, it) },
                            { getString(R.string.settings_controls_import_failed, it.message ?: it.javaClass.simpleName) },
                        )
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                }, "preset-controls").start()
            }
            .setNegativeButton(R.string.settings_cancel, null)
            .show()
    }

    private fun showControlsLayout() {
        val name = ControlsImport.currentName(this)
        findViewById<TextView>(R.id.tv_controls_layout).text =
            if (name != null) getString(R.string.settings_controls_layout, name)
            else getString(R.string.settings_controls_builtin)
    }

    /**
     * The editor lives with the controls in the shell (dev.silksong.shell), which
     * this module is not compiled against, so it is started by name.
     */
    private fun openControlsEditor() {
        try {
            startActivity(Intent().setClassName(this, "dev.silksong.shell.ControlsEditorActivity"))
        } catch (t: Throwable) {
            LauncherLog.log("controls: could not open the editor", t)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT_CONTROLS || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread({
            val result = runCatching { ControlsImport.import(this, uri) }
            runOnUiThread {
                showControlsLayout()
                val message = result.fold(
                    { getString(R.string.settings_controls_imported, it) },
                    { getString(R.string.settings_controls_import_failed, it.message ?: it.javaClass.simpleName) },
                )
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }, "import-controls").start()
    }

    private companion object {
        const val REQUEST_IMPORT_CONTROLS = 41
    }

    /**
     * Asks first, and says what will and will not survive.
     *
     * The scary half of the sentence is what is kept, not what is deleted:
     * "clear data" on Android means the saves go too, and this is the same
     * words for a very different thing.
     */
    private fun confirmClearBuild() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_clear_build_title)
            .setMessage(R.string.settings_clear_build_message)
            .setNegativeButton(R.string.settings_cancel, null)
            .setPositiveButton(R.string.settings_clear_build_confirm) { _, _ -> clearBuild() }
            .show()
    }

    private fun clearBuild() {
        // Gigabytes across thousands of small files, so not on the main
        // thread -- and not cancellable either, because a half-deleted build
        // is the state this exists to get out of.
        val progress = AlertDialog.Builder(this)
            .setMessage(getString(R.string.settings_clear_build_working))
            .setCancelable(false)
            .show()

        Thread({
            var error: Throwable? = null
            try {
                BuildReset.clear(this)
            } catch (t: Throwable) {
                error = t
                LauncherLog.log("could not clear the build", t)
            }
            runOnUiThread {
                runCatching { progress.dismiss() }
                val failure = error
                if (failure != null) {
                    AlertDialog.Builder(this)
                        .setMessage(getString(R.string.settings_clear_build_failed,
                                              failure.message ?: failure.javaClass.simpleName))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    return@runOnUiThread
                }
                startPorting()
            }
        }, "clear-build").start()
    }

    /**
     * Back to the porting screen, with nothing stale behind it.
     *
     * SetupActivity is the app's entry point and works out what to show from
     * what is on disk, which is now "a game that needs building"; its onResume
     * re-reads that, so a reused instance is as correct as a new one.
     *
     * CLEAR_TOP finishes everything above it in the task -- this screen and the
     * launcher behind it, both of which are describing a game that no longer
     * exists -- and SINGLE_TOP delivers to the existing instance rather than
     * stacking a second. finishAffinity is deliberately not used: it would
     * close the task this has just started an activity into.
     */
    private fun startPorting() {
        try {
            startActivity(
                Intent(this, SetupActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            finish()
        } catch (t: Throwable) {
            LauncherLog.log("could not open the porting screen: $t")
            finish()
        }
    }
}
