package dev.noblebits.overkey

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var overlayAction: TextView
    private lateinit var batteryAction: TextView
    private lateinit var rootAction: TextView
    private lateinit var shizukuDesc: TextView
    private lateinit var shizukuAction: TextView
    private lateinit var adbDesc: TextView
    private lateinit var command: TextView
    private lateinit var calibrateField: EditText
    private var regaining = false
    private var dp = 1f

    private val white get() = getColor(R.color.white)
    private val gray get() = getColor(R.color.gray)
    private val accent get() = getColor(R.color.accent)

    private val shizukuResult = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == android.content.pm.PackageManager.PERMISSION_GRANTED) startShizuku()
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dp = resources.displayMetrics.density
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(24), px(24), px(24))
            setOnApplyWindowInsetsListener { v, insets ->
                val s = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(px(24), px(24) + s.top, px(24), px(24) + s.bottom)
                insets
            }
        }

        root.addView(WordmarkView(this), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = px(24)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        status = label("", 16f, gray).apply { gravity = Gravity.CENTER_HORIZONTAL }
        root.addView(status, lp(top = 12))

        header("Setup")
        overlayAction = row("Display over other apps",
            "Draws the key bar above the keyboard. This is all the app needs besides an injector.", "GRANT") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        batteryAction = row("Battery optimisation",
            "Exempt Overkey so the system does not kill the overlay in the background. Opens the " +
                "system list; find Overkey and choose Don't optimise.", "OPEN") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        header("Key delivery")
        note("Keys are injected as if from a hardware keyboard, so they work in every app. That " +
            "needs one of the three below; without one the bar shows but keys go nowhere.")
        rootAction = row("Root", "Starts the injector with su. Restarts itself after every boot.", "START") {
            Thread {
                val ok = InjectorClient.launchWithRoot(this)
                runOnUiThread {
                    if (!ok) Toast.makeText(this, "su failed", Toast.LENGTH_SHORT).show()
                    status.postDelayed({ refresh() }, 1000)
                }
            }.start()
        }
        val shizuku = row("Shizuku", "", "GRANT") { onShizukuAction() }
        shizukuAction = shizuku
        shizukuDesc = (shizuku.parent as LinearLayout).getChildAt(0).let { (it as LinearLayout).getChildAt(1) as TextView }
        val adb = row("ADB from a PC", "", "COPY") {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("overkey", adbCommand()))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
        adbDesc = (adb.parent as LinearLayout).getChildAt(0).let { (it as LinearLayout).getChildAt(1) as TextView }
        command = label("", 11f, gray).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            background = card()
            setPadding(px(16), px(12), px(16), px(12) + EDGE)
        }
        root.addView(command, lp(top = 8))

        header("No root? Shizuku, step by step")
        surface().apply {
            for (step in arrayOf(
                "1. Install Shizuku from the Play Store or github.com/RikkaApps/Shizuku.",
                "2. Settings > Developer options > turn on Wireless debugging.",
                "3. In Shizuku, tap Start via Wireless debugging. Pair when asked (the pairing code " +
                    "appears in the Wireless debugging screen; a notification lets you enter it).",
                "4. Come back here and tap GRANT on the Shizuku row. Allow it.",
                "5. After every reboot, open Shizuku and tap Start again. overkey reconnects on its own.",
            )) addView(label(step, 13f, white).apply { setLineSpacing(0f, 1.2f) }, lp(top = 6))
            addView(label("If you have a PC instead, the ADB row above does the same thing with one " +
                "command, no Shizuku needed.", 12f, gray), lp(top = 10))
        }

        header("Look")
        surface().apply {
            addView(label("Theme", 16f, white))
            addView(Spinner(this@MainActivity).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                    Array(Prefs.THEMES.size) { Prefs.THEMES[it].name })
                setSelection(Prefs.of(this@MainActivity).getInt(Prefs.THEME, 0))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                        Prefs.of(this@MainActivity).edit().putInt(Prefs.THEME, pos).apply()
                        Overlays.instance?.reload()
                    }
                    override fun onNothingSelected(p: AdapterView<*>) {}
                }
            }, lp(top = 8))
        }
        surface().apply {
            addView(label("Volume keys as modifiers", 16f, white))
            addView(label("While a keyboard is up, holding a volume key holds the modifier, with the " +
                "bar hidden or not; letting go lets go. The volume still changes by a step. Elsewhere " +
                "the keys do their normal job.", 12f, gray), lp(top = 4))
            for ((title, key) in arrayOf("Volume down" to Prefs.VOL_DOWN, "Volume up" to Prefs.VOL_UP,
                "Both together" to Prefs.VOL_BOTH)) {
                addView(label(title, 13f, gray), lp(top = 10))
                addView(Spinner(this@MainActivity).apply {
                    adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, Prefs.VOL_MODS)
                    setSelection(Prefs.of(this@MainActivity).getInt(key, if (key == Prefs.VOL_DOWN) 1 else 0))
                    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                            Prefs.of(this@MainActivity).edit().putInt(key, pos).apply()
                            Overlays.instance?.reload()
                        }
                        override fun onNothingSelected(p: AdapterView<*>) {}
                    }
                }, lp(top = 2))
            }
        }
        slider("Bar row height", "dp", Prefs.ROW_DP, 20, 48, 38)
        slider("Chord grid background opacity", "", Prefs.ALPHA, 0, 255, 0xE0)
        slider("Chord grid text opacity", "", Prefs.FG_ALPHA, 0, 255, 0xFF)

        header("Position")
        surface().apply {
            addView(label("Tap the field, then drag on the overlays: the bar up or down, the grid's " +
                "top half to move its top edge, bottom half for the bottom edge, outer columns for " +
                "the sides. Line the grid's letters up with the keyboard's. Move the bar down onto " +
                "the keyboard's toolbar row if it covers your text field. Saved when you let go.",
                12f, gray).apply { setLineSpacing(0f, 1.2f) })
            calibrateField = EditText(this@MainActivity).apply {
                hint = "Tap here to calibrate"
                textSize = 14f
                setTextColor(white)
                setHintTextColor(gray)
                setOnFocusChangeListener { _, focused -> Overlays.instance?.calibrating = focused }
            }
            addView(calibrateField, lp(top = 8))
            addView(action("RESET") {
                Prefs.of(this@MainActivity).edit().remove(Prefs.BAR_Y).remove(Prefs.TOP)
                    .remove(Prefs.BOTTOM).remove(Prefs.LEFT).remove(Prefs.RIGHT).apply()
                Overlays.instance?.reload()
            }, lp(top = 4).apply { gravity = Gravity.END })
        }

        setContentView(ScrollView(this).apply {
            clipToPadding = false
            addView(root)
        })
        try { Shizuku.addRequestPermissionResultListener(shizukuResult) } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        try { Shizuku.removeRequestPermissionResultListener(shizukuResult) } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        Overlays.instance?.calibrating = false
        calibrateField.clearFocus()
        super.onPause()
    }

    private fun onShizukuAction() {
        when {
            !shizukuInstalled() -> startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/RikkaApps/Shizuku/releases")))
            !ShizukuInjector.running() -> packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)?.let { startActivity(it) }
            !ShizukuInjector.granted() -> try { Shizuku.requestPermission(1) } catch (_: Throwable) {}
            else -> startShizuku()
        }
    }

    private fun startShizuku() {
        ShizukuInjector.start(packageName, InjectorClient.token(this))
        status.postDelayed({ refresh() }, 1500)
    }

    private fun shizukuInstalled() = try {
        packageManager.getPackageInfo(SHIZUKU_PKG, 0)
        true
    } catch (_: Exception) {
        false
    }

    private fun adbCommand() = "adb shell \"${InjectorClient.launchCommand(this)}\""

    private fun refresh() {
        val overlay = Settings.canDrawOverlays(this)
        overlayAction.text = if (overlay) "ON" else "GRANT"
        if (!overlay && Prefs.root(this) && !regaining) {
            // The service would try the same thing; one su prompt is enough.
            regaining = true
            Thread {
                val ok = InjectorClient.regainOverlay(this)
                runOnUiThread {
                    regaining = false
                    if (ok && !isDestroyed) refresh()
                }
            }.start()
            return
        }
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        batteryAction.text = if (pm.isIgnoringBatteryOptimizations(packageName)) "ON" else "OPEN"
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        OverlayService.startIfWanted(this)
        // The token stays off the screen: anyone who can read it can drive the injector.
        command.text = adbCommand().replace(InjectorClient.token(this), "<token>")
        adbDesc.text = "Run the command below once after each reboot."
        shizukuDesc.text = when {
            !shizukuInstalled() -> "Not installed. Runs the injector as shell, no root needed."
            !ShizukuInjector.running() -> "Installed but not running. Start it in the Shizuku app."
            !ShizukuInjector.granted() -> "Running. Allow overkey to use it."
            else -> "Ready. Starts the injector whenever it is missing."
        }
        shizukuAction.text = when {
            !shizukuInstalled() -> "GET"
            !ShizukuInjector.running() -> "OPEN"
            !ShizukuInjector.granted() -> "GRANT"
            else -> "START"
        }
        Thread {
            val peer = InjectorClient.probe(this)
            runOnUiThread {
                rootAction.text = if (peer == InjectorClient.OURS) "RUNNING" else "START"
                status.text = when {
                    !overlay -> "Grant display over other apps to start"
                    peer == InjectorClient.OURS -> "Injector running, every key reaches every app"
                    peer == InjectorClient.FOREIGN -> "Another process holds port 27301, restart the injector"
                    else -> "No injector, keys have nowhere to go"
                }
            }
        }.start()
    }

    // Building blocks in a plain settings style: black page, dark surfaces, gray copy, accent text actions.

    private fun px(v: Int) = (v * dp).toInt()

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = px(top) }

    private fun label(s: CharSequence, size: Float, color: Int) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
    }

    private fun header(s: String) {
        root.addView(label(s.uppercase(), 12f, gray).apply {
            letterSpacing = 0.1f
            typeface = Typeface.DEFAULT_BOLD
        }, lp(top = 28))
    }

    private fun note(s: String) {
        root.addView(label(s, 12f, gray).apply { setLineSpacing(0f, 1.2f) }, lp(top = 8))
    }

    private fun surface() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = card()
        setPadding(px(16), px(16), px(16), px(16) + EDGE)
        root.addView(this, lp(top = 10))
    }

    /**
     * a plinth: one solid copy of the card's shape pushed down and to the right by
     * [LIFT] dp along a fixed light vector, coloured with the accent pulled toward the surface,
     * plus a 1dp rim that shows only along the top-left edge. The face itself stays flat.
     */
    private fun card(): Drawable {
        val r = px(14).toFloat()
        val ox = Math.round(LIGHT_X * LIFT * dp)
        val oy = Math.round(LIGHT_Y * LIFT * dp)
        val wall = GradientDrawable().apply {
            cornerRadius = r
            setColor(WordmarkView.blend(accent, getColor(R.color.surface), 0.45f))
        }
        val rim = GradientDrawable().apply {
            cornerRadius = r
            setStroke(px(1), (white and 0xFFFFFF) or 0x2E000000)
        }
        val face = GradientDrawable().apply {
            cornerRadius = r
            setColor(getColor(R.color.surface))
        }
        return LayerDrawable(arrayOf(wall, rim, face)).apply {
            setLayerInset(0, ox, oy, 0, 0)
            setLayerInset(1, 0, 0, ox + px(1), oy + px(1))
            setLayerInset(2, px(1), px(1), ox, oy)
        }
    }

    private fun action(s: String, onClick: () -> Unit) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(accent)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(px(16), px(8), px(16), px(8))
        val out = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
        setBackgroundResource(out.resourceId)
        setOnClickListener { onClick() }
    }

    /** Title and description on the left, a accent action on the right. Returns the action. */
    private fun row(title: String, desc: String, actionText: String, onClick: () -> Unit): TextView {
        val a = action(actionText, onClick)
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = card()
            setPadding(px(16), px(16), px(8), px(16) + EDGE)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(title, 16f, white))
                addView(label(desc, 12f, gray), lp(top = 4))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(a, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = px(8) })
            root.addView(this, lp(top = 10))
        }
        return a
    }

    private fun slider(title: String, unit: String, key: String, min: Int, max: Int, default: Int) {
        val value = Prefs.of(this).getInt(key, default)
        surface().apply {
            val t = label("$title: $value $unit", 16f, white)
            addView(t)
            addView(SeekBar(this@MainActivity).apply {
                this.max = max - min
                progress = value - min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                        t.text = "$title: ${p + min} $unit"
                        Prefs.of(this@MainActivity).edit().putInt(key, p + min).apply()
                        Overlays.instance?.reload()
                    }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            }, lp(top = 8))
        }
    }

    private val EDGE get() = px(LIFT)

    companion object {
        private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

        private const val LIGHT_X = WordmarkView.LIGHT_X
        private const val LIGHT_Y = WordmarkView.LIGHT_Y
        private const val LIFT = 3
    }
}
