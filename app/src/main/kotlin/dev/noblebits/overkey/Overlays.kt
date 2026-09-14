package dev.noblebits.overkey

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.function.Consumer

/**
 * The bar and the chord grid, and where they go. Both are "display over other apps" windows,
 * which Android draws above the keyboard (the keyboard is layered just above the app it
 * serves, overlays above all apps), so the chord grid lies on the keyboard.
 *
 * [OverlayService] reports the keyboard's on-screen rectangle through [keyboard]; everything
 * else happens here. Same process as the settings screen, so a plain [instance] field will do.
 */
class Overlays(private val context: Context, private val onLost: Runnable) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val client = InjectorClient(context)
    private val mods: Mods
    private val bar: KeyBarView
    private val pill: KeyBarView // the bar folded away: just its handle
    private val chord: KeyBarView
    private val barLp = params()
    private val pillLp = params()
    private val chordLp = params()
    private var barShown = false
    private var pillShown = false
    private var chordShown = false
    private var collapsed = false
    private var volDown: Key? = null
    private var volUp: Key? = null
    private var volBoth: Key? = null
    private val volActive = ArrayList<Key>(2) // modifier keys currently held by volume keys
    private var volMask = 0 // the volume keys as last reported, applied whenever the rules change

    /** Nothing on screen at all; the volume chord and its grid still work. */
    var hidden = false
        set(v) {
            field = v
            if (v) letGo()
            layoutBar()
            layoutChord()
        }
    private val imeBounds = Rect()
    private var onKeyboard = true // false: bar pinned at the screen bottom with no keyboard under it
    private val density = context.resources.displayMetrics.density
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var ready = false

    // Calibration in px. barY moves the bar, grid[] moves the chord grid's top, bottom, left
    // and right edges, all relative to where they would sit by default.
    private var barY = 0
    private val grid = IntArray(4)

    // A drag of the handle in progress: where it started, and how far an upward pull has
    // gone past the top clamp (negative), which is what dismisses the pill.
    private var dragging = false
    private var dragFrom = 0
    private var overshoot = 0f
    /** The pill was dragged off the top; the host hides the overlays and says so. */
    var onDismiss: Runnable? = null

    /** Shows both overlays for dragging, whatever the keyboard and modifiers are doing. */
    var calibrating = false
        set(v) {
            field = v
            bar.calibrate = if (v) Consumer { d -> dragBar(d) } else null
            chord.calibrate = if (v) Consumer { d -> dragGrid(d) } else null
            layoutChord()
        }

    init {
        val sink = KeySink { action, code, meta, repeat -> send(action, code, meta, repeat) }
        mods = Mods(sink) {
            if (ready) {
                bar.invalidate()
                chord.invalidate()
                layoutChord()
            }
        }
        bar = KeyBarView(context, KeyBarView.BAR, mods, 1, sink)
        pill = KeyBarView(context, KeyBarView.BAR, mods, 1, sink)
        pill.collapsed = true
        chord = KeyBarView(context, KeyBarView.CHORD, mods, 0, sink)
        val grip = Consumer<Int?> { dy ->
            if (dy == null) {
                // Let go. The pill pulled past the top goes away, as a picture-in-picture
                // player does, and comes back with Show at the place it was picked up from.
                val dismiss = collapsed && overshoot <= -DISMISS_DP * density
                if (dragging) hideTarget()
                dragging = false
                if (dismiss) {
                    barY = dragFrom
                    onDismiss?.run()
                } else save()
            } else {
                if (!dragging) {
                    dragging = true
                    dragFrom = barY
                    overshoot = 0f
                    if (collapsed) showTarget()
                }
                // How far the finger has pulled past the clamp, kept so the gesture is
                // symmetric: coming back down pays that pull back before the bar itself
                // moves, the way a picture-in-picture player does. Forgetting it on any
                // downward movement meant a wobble out of the target and back in left the
                // pull at nothing, so the target would not light up a second time.
                var move = dy.toFloat()
                if (move > 0f && overshoot < 0f) {
                    val give = minOf(move, -overshoot)
                    overshoot += give
                    move -= give
                }
                val want = barY + move.toInt()
                barY = want
                layoutBar()
                // What the top clamp took off an upward pull. Only that: the floor clamp holds
                // the bar on a downward drag too, and counting that would build a positive debt
                // nothing ever pays back, leaving the cross unable to arm afterwards.
                val taken = (want - barY).toFloat()
                if (taken < 0f) overshoot += taken
                if (collapsed) armTarget(overshoot <= -DISMISS_DP * density)
                // Pinned, the grid stacks on the bar and rides along; on a keyboard it stays
                // where the user lined it up with the keys.
                if (!onKeyboard) layoutChord()
            }
        }
        val hold = Runnable { aim() }
        val fold = Runnable {
            collapsed = !collapsed
            if (collapsed) letGo()
            layoutBar()
            layoutChord()
        }
        bar.grip = grip
        bar.gripHold = hold
        bar.onCollapse = fold
        pill.grip = grip
        pill.gripHold = hold
        pill.onCollapse = fold
        client.onVolumeKeys = Consumer { mask ->
            volMask = mask
            volumeKeys(mask)
        }
        client.onText = Consumer { text -> if (text.isEmpty()) toast("No text there") else showText(text) }
        client.onImage = Consumer { png ->
            shy(false)
            if (png.isEmpty()) toast("Nothing to copy there") else copyImage(png)
        }
        ready = true
        reload()
        instance = this
    }

    fun destroy() {
        if (instance === this) instance = null
        aimView?.let { wm.removeView(it) }
        aimView = null
        hideTarget()
        closeText()
        // An answer that arrives after this must not open a panel from a dead service.
        client.onText = null
        client.onImage = null
        handler.removeCallbacks(unshy)
        letGo() // the UPs are queued before the socket closes below
        imeBounds.setEmpty()
        layoutBar()
        layoutChord()
        client.shutdown()
    }

    /** Re-reads theme, transparency and calibration. The settings screen calls this. */
    fun reload() {
        val p = Prefs.of(context)
        val theme = Prefs.theme(context)
        bar.theme = theme
        pill.theme = theme
        chord.theme = theme
        chord.bgAlpha = Prefs.alpha(context)
        chord.fgAlpha = Prefs.fgAlpha(context)
        bar.rowHeight = (Prefs.rowDp(context) * density).toInt()
        volDown = Prefs.volMod(context, Prefs.VOL_DOWN)
        volUp = Prefs.volMod(context, Prefs.VOL_UP)
        volBoth = Prefs.volMod(context, Prefs.VOL_BOTH)
        barY = (p.getInt(Prefs.BAR_Y, 0) * density).toInt()
        grid[0] = (p.getInt(Prefs.TOP, 0) * density).toInt()
        grid[1] = (p.getInt(Prefs.BOTTOM, 0) * density).toInt()
        grid[2] = (p.getInt(Prefs.LEFT, 0) * density).toInt()
        grid[3] = (p.getInt(Prefs.RIGHT, 0) * density).toInt()
        volumeKeys(volMask) // the bindings may have changed
        layoutBar()
        layoutChord()
    }

    private fun barTop() = imeBounds.top - barHeight() + barY

    /**
     * Where the bar should sit: on top of [r], the keyboard's visible rectangle, or empty to
     * hide. With [keyboard] false, [r] is a stand-in at the screen bottom (the bar was pinned
     * from the notification); the chord grid then stacks above the bar instead of over [r].
     */
    fun keyboard(r: Rect, keyboard: Boolean = true) {
        val rect = if (r.top <= 0) Rect() else r
        if (rect == imeBounds && keyboard == onKeyboard) return
        imeBounds.set(rect)
        onKeyboard = keyboard
        // A volume key already held takes effect now, or stops, as the new state allows.
        volumeKeys(volMask)
        if (rect.isEmpty) mods.reset()
        layoutBar()
        layoutChord()
    }

    private fun barHeight() = bar.rowHeight * KeyBarView.BAR.size + bar.gripHeight

    /**
     * Every modifier up, from the volume keys first. The Key objects are shared by every
     * Overlays there will ever be in this process, so `held` counts left behind would make
     * the next bar draw a key as pressed and never send its DOWN.
     */
    private fun letGo() {
        volumeKeys(0)
        mods.reset()
    }

    /**
     * A held volume key is a finger on its bound modifier key, only while the bar has a place
     * (a keyboard up, or pinned) so the keys keep their normal job elsewhere. Both held with a
     * binding of their own wins over the single-key bindings. Letting go lets go: unlike a
     * tap on the bar, a volume key never leaves the modifier armed.
     */
    private fun volumeKeys(mask: Int) {
        // The bound keys are kept from the system while they have a job, so the volume
        // stays put; elsewhere they are the system's again.
        var bound = 0
        if (volDown != null) bound = bound or 1
        if (volUp != null) bound = bound or 2
        if (volBoth != null) bound = 3
        client.swallow(if (imeBounds.isEmpty) 0 else bound)
        val want = ArrayList<Key>(2)
        if (!imeBounds.isEmpty) {
            if (mask == 3 && volBoth != null) {
                want.add(volBoth!!)
            } else {
                if (mask and 1 != 0) volDown?.let { want.add(it) }
                if (mask and 2 != 0) volUp?.let { if (!want.contains(it)) want.add(it) }
            }
        }
        for (k in ArrayList(volActive)) if (!want.contains(k)) {
            volActive.remove(k)
            k.held--
            if (k.held == 0) mods.release(k, arm = false)
        }
        for (k in want) if (!volActive.contains(k)) {
            volActive.add(k)
            k.held++
            if (k.held == 1) mods.press(k)
        }
        bar.invalidate()
    }

    private fun dragBar(d: IntArray?) {
        if (d == null) {
            save()
            return
        }
        barY += d[0] + d[1] // either half of the bar moves the whole bar
        layoutBar()
        if (!onKeyboard) layoutChord()
    }

    private fun dragGrid(d: IntArray?) {
        if (d == null) {
            save()
            return
        }
        for (i in 0..3) grid[i] += d[i]
        layoutChord()
    }

    private fun save() {
        Prefs.of(context).edit()
            .putInt(Prefs.BAR_Y, (barY / density).toInt())
            .putInt(Prefs.TOP, (grid[0] / density).toInt())
            .putInt(Prefs.BOTTOM, (grid[1] / density).toInt())
            .putInt(Prefs.LEFT, (grid[2] / density).toInt())
            .putInt(Prefs.RIGHT, (grid[3] / density).toInt())
            .apply()
    }

    private var target: TargetView? = null

    /**
     * The dismiss target: a ring with a cross at the top of the screen, shown while the
     * pill is dragged, filled once the pull has gone far enough past the top to count.
     * Not touchable; the finger is on the pill.
     */
    private fun showTarget() {
        if (target != null) return
        val v = TargetView(context, Prefs.theme(context))
        val size = (TARGET_DP * density).toInt()
        val statusBottom = wm.currentWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
        val lp = WindowManager.LayoutParams(size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.y = statusBottom + (12 * density).toInt()
        lp.windowAnimations = R.style.OverlayFade
        if (add(v, lp)) target = v
    }

    private fun armTarget(armed: Boolean) {
        val t = target ?: return
        if (t.armed != armed) {
            t.armed = armed
            t.invalidate()
            if (armed) t.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun hideTarget() {
        target?.let { wm.removeView(it) }
        target = null
    }

    private class TargetView(context: Context, private val theme: Theme) : View(context) {
        var armed = false
        private val density = context.resources.displayMetrics.density
        private val fill = Paint().apply { isAntiAlias = true }
        private val line = Paint().apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = width / 2f - density
            fill.color = if (armed) theme.accent else (theme.bg and 0xFFFFFF) or 0xD0000000.toInt()
            canvas.drawCircle(cx, cy, r, fill)
            line.color = if (armed) theme.bg else theme.fg
            line.strokeWidth = density
            canvas.drawCircle(cx, cy, r, line)
            line.strokeWidth = 2f * density
            val a = r * 0.28f
            canvas.drawLine(cx - a, cy - a, cx + a, cy + a, line)
            canvas.drawLine(cx - a, cy + a, cx + a, cy - a, line)
        }
    }

    private val unshy = Runnable { shy(false) }

    /**
     * Takes the bar, pill and grid out of the picture (window alpha 0, so the layout is
     * untouched) while a screenshot is taken, and back after. Back on its own after fifteen
     * seconds in case no answer ever comes; the lookup before the shot can take a few.
     */
    private fun shy(on: Boolean) {
        val a = if (on) 0f else 1f
        handler.removeCallbacks(unshy)
        if (barLp.alpha == a) return
        barLp.alpha = a
        pillLp.alpha = a
        chordLp.alpha = a
        if (barShown) wm.updateViewLayout(bar, barLp)
        if (pillShown) wm.updateViewLayout(pill, pillLp)
        if (chordShown) wm.updateViewLayout(chord, chordLp)
        if (on) handler.postDelayed(unshy, 15000)
    }

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        windowAnimations = R.style.OverlayFade
    }

    /**
     * Two windows, never both: the bar, or its pill. Swapping windows rather than resizing one
     * gives a fade instead of a frame with the small window at the old left edge.
     */
    /**
     * Keeps the bar (and so the pill) below the status bar and above the screen's bottom.
     * The status bar's window lies over every overlay, so a pill dragged under it could be
     * seen but never touched: every tap there pulled the shade down instead.
     */
    private fun clampBar() {
        val m = wm.currentWindowMetrics
        val statusBottom = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
        val floor = m.bounds.height() - barHeight()
        val top = imeBounds.top - barHeight() // barTop() with barY = 0
        barY = barY.coerceIn(statusBottom - top, maxOf(statusBottom - top, floor - top))
    }

    private fun layoutBar() {
        val r = imeBounds
        if (!r.isEmpty) clampBar()
        if (r.isEmpty || collapsed || hidden) {
            if (barShown) wm.removeView(bar)
            barShown = false
        }
        if (r.isEmpty || !collapsed || hidden) {
            if (pillShown) wm.removeView(pill)
            pillShown = false
        }
        if (r.isEmpty || hidden) return
        if (collapsed) {
            // Centred on where the bar's handle strip would be.
            pillLp.x = r.left + (r.width() - pill.pillWidth) / 2
            pillLp.y = barTop() + barHeight() - bar.gripHeight / 2 - pill.pillHeight / 2
            pillLp.width = pill.pillWidth
            if (pillShown) wm.updateViewLayout(pill, pillLp) else if (!add(pill, pillLp)) return
            pillShown = true
        } else {
            barLp.x = r.left
            barLp.y = barTop()
            barLp.width = r.width()
            if (barShown) wm.updateViewLayout(bar, barLp) else if (!add(bar, barLp)) return
            barShown = true
        }
    }

    private fun layoutChord() {
        val r = imeBounds
        // The grid comes up even with the bar folded or hidden: the volume chord may have armed it.
        val want = !r.isEmpty && (calibrating || (mods.active and Mods.CHORD_META) != 0)
        if (!want) {
            if (chordShown) wm.removeView(chord)
            chordShown = false
            return
        }
        if (onKeyboard) {
            chordLp.x = r.left + grid[2]
            chordLp.y = r.top + grid[0]
            chordLp.width = maxOf(r.width() - grid[2] + grid[3], MIN_PX)
            chordLp.height = maxOf(r.height() - grid[0] + grid[1], MIN_PX)
        } else {
            // Stacked on the bar; under it when the bar sits too high for the grid to fit
            // above, which a pill dragged near the top does.
            val h = (GRID_ROW_DP * KeyBarView.CHORD.size * density).toInt()
            val statusBottom = wm.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
            chordLp.x = r.left
            chordLp.y = if (barTop() - h >= statusBottom) barTop() - h else barTop() + barHeight()
            chordLp.width = r.width()
            chordLp.height = h
        }
        if (chordShown) wm.updateViewLayout(chord, chordLp) else if (!add(chord, chordLp)) return
        chordShown = true
    }

    private var aimView: View? = null

    /**
     * Aim mode, from a long press on the grip: the whole screen takes the next touch and turns
     * it into a mouse gesture through the injector. A tap is a left click at that spot, a drag
     * a left-button drag along the same path, replayed once the finger lifts, and a long press
     * offers a right click or the text under the finger, lifted out of the app's accessibility
     * tree into a panel where it can be selected in part. A touch tap cannot give a page
     * element focus the way a pointer click does, which is what Ctrl+C on an image in a
     * browser needs; a touch drag scrolls where a mouse drag selects; and a message in a chat
     * app is selectable by no pointer at all, only whole by long press. A second finger
     * cancels. The one gesture that stays a finger's is a double tap that drags: replayed as
     * a touch, at the finger's own pace, it scrolls and flings the way it would with no
     * aim mode at all, for getting the target on screen. A double tap in place is a
     * double click.
     *
     * Replayed rather than live because the finger is still down on this window: on Android
     * before 14 the dispatcher allows one pointer device at a time, and a mouse DOWN injected
     * mid-touch has the touch dropped.
     */
    private fun aim() {
        if (aimView != null) return
        val v = AimView(context, Prefs.theme(context)) { button, path, times ->
            aimView?.let { wm.removeView(it) }
            aimView = null
            if (button == AimView.CANCEL) return@AimView
            if (button == AimView.TEXT || button == AimView.IMAGE) {
                if (!client.connected) toast("No injector running")
                else {
                    // Asked once the window is gone: its tint would be in the screenshot
                    // and its node in the tree. The bar and grid go out of the picture too,
                    // for the same reason, until the answer comes.
                    if (button == AimView.IMAGE) shy(true)
                    handler.postDelayed({
                        if (button == AimView.TEXT) client.grab(path[0], path[1]) else client.grabImage(path[0], path[1])
                    }, 150)
                }
                return@AimView
            }
            // The window takes a frame or two to go; a click sent at once lands on it. Not
            // View.postDelayed: a detached view (the bar, while collapsed) holds the runnable
            // until it is attached again.
            handler.postDelayed({
                when (button) {
                    AimView.DRAG -> client.drag(thin(path, 32))
                    // More points than a drag, and their times: a scroll's feel, and
                    // whether it flings, is in the pace of the finger.
                    AimView.SWIPE -> {
                        val keep = spread(path.size / 2, 64)
                        client.swipe(IntArray(keep.size * 2) { path[2 * keep[it / 2] + it % 2] }, IntArray(keep.size) { times[keep[it]] })
                    }
                    AimView.DOUBLE -> {
                        client.click(path[0], path[1], AimView.LEFT)
                        client.click(path[0], path[1], AimView.LEFT)
                    }
                    else -> client.click(path[0], path[1], button)
                }
            }, 150)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        lp.windowAnimations = R.style.OverlayFade
        if (add(v, lp)) aimView = v
    }

    private var textPanel: Dialog? = null

    /**
     * The text lifted from under the finger, in a panel with Android's own selection: long
     * press a word, drag the handles, Copy from the toolbar or COPY below. A Dialog rather
     * than a view added to the window manager: the floating selection toolbar is an action
     * mode, and only a DecorView starts one. The window takes focus so the selection works,
     * but tells the keyboard it has no use for it, so a keyboard that was up stays up behind.
     * A touch outside or Back closes it.
     */
    private fun showText(text: String) {
        closeText()
        val theme = Prefs.theme(context)
        val pad = (16 * density).toInt()
        val maxH = (context.resources.displayMetrics.heightPixels * 0.45f).toInt()
        val body = TextView(context).apply {
            this.text = text
            setTextColor(theme.fg)
            textSize = 16f
            setTextIsSelectable(true)
            setPadding(pad, pad, pad, pad)
            highlightColor = (theme.accent and 0x00FFFFFF) or 0x60000000
        }
        val scroll = object : ScrollView(context) {
            override fun onMeasure(w: Int, h: Int) =
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
        }.apply { addView(body) }
        fun button(label: String, onClick: () -> Unit) = TextView(context).apply {
            this.text = label
            setTextColor(theme.accent)
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(pad, pad * 3 / 4, pad, pad * 3 / 4)
            setOnClickListener { onClick() }
        }
        val buttons = LinearLayout(context).apply {
            gravity = Gravity.END
            // Besides the toolbar: COPY with nothing selected takes all of it. Ctrl+C from
            // the bar works too; the panel holds focus, and a selectable TextView takes
            // that shortcut itself.
            addView(button("COPY") {
                val a = body.selectionStart
                val b = body.selectionEnd
                val part = a in 0 until b
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("text", if (part) text.substring(a, b) else text))
                toast(if (part) "Copied the selection" else "Copied all of it")
                closeText()
            })
            addView(button("CLOSE") { closeText() })
        }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(theme.bg)
                cornerRadius = 12 * density
                setStroke(density.toInt(), theme.accent)
            }
            addView(scroll)
            addView(buttons)
        }
        // The dialog theme only sets the selection toolbar and handles; the panel paints itself.
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val d = Dialog(context, if (night) android.R.style.Theme_DeviceDefault_Dialog_NoActionBar
            else android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar)
        d.setContentView(panel)
        d.setCanceledOnTouchOutside(true)
        d.setOnDismissListener { if (textPanel === d) textPanel = null }
        val w = d.window ?: return
        w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        w.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        w.setLayout(context.resources.displayMetrics.widthPixels - 2 * pad, WindowManager.LayoutParams.WRAP_CONTENT)
        w.setDimAmount(0.3f)
        w.setWindowAnimations(R.style.OverlayFade)
        try {
            d.show()
            textPanel = d
        } catch (_: WindowManager.BadTokenException) {
            onLost.run()
        }
    }

    private fun closeText() {
        textPanel?.dismiss()
        textPanel = null
    }

    /**
     * The picture under the finger goes on the clipboard as a PNG served by [ClipProvider].
     * Apps that take an image paste (a chat's message box, a note, a browser) get the pixels
     * as they were on screen; the file itself is not what was copied.
     */
    private fun copyImage(png: ByteArray) {
        // A fresh name each time: an app still reading the last clip would get a torn file
        // if it were overwritten, and image caches keyed on the URI would show the old one.
        val name = ClipProvider.name("png")
        try {
            ClipProvider.file(context, name).writeBytes(png)
        } catch (_: java.io.IOException) {
            ClipProvider.file(context, name).delete()
            toast("Could not save the image")
            return
        }
        ClipProvider.note(context, name, "image/png", "screenshot.png")
        // The older clips go only now, so a failure above leaves the last one pasteable.
        ClipProvider.sweep(context, setOf(name))
        val uri = ClipProvider.uri(context, name)
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newUri(context.contentResolver, "image", uri))
        toast("Image copied")
    }

    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    /** At most [n] of the x,y pairs in [path], evenly spaced, the first and last kept. */
    private fun thin(path: IntArray, n: Int): IntArray {
        val keep = spread(path.size / 2, n)
        return IntArray(keep.size * 2) { path[2 * keep[it / 2] + it % 2] }
    }

    /** The indices of at most [n] out of [count] items, evenly spaced, the first and last kept. */
    private fun spread(count: Int, n: Int): IntArray {
        if (count <= n) return IntArray(count) { it }
        return IntArray(n) { it * (count - 1) / (n - 1) }
    }

    /**
     * Full-screen tint that reports one gesture: [done] gets the button ([LEFT], [RIGHT],
     * [DRAG], [SWIPE], [DOUBLE], [TEXT], [IMAGE] or [CANCEL]), the touched screen points as
     * x,y pairs, first to last, and for each point its time since the touch began in ms. A
     * drag draws its trail so the finger can see where the pointer will go. A long press
     * opens a menu at the finger, right click, select text or copy image; the next tap picks
     * one, anywhere else cancels.
     *
     * A tap is not reported until the double-tap window has passed with no second touch: a
     * second tap that goes on to drag is a [SWIPE], a finger's own scroll rather than the
     * mouse's selection, and a second tap that lifts in place is a [DOUBLE] click.
     */
    private class AimView(context: Context, private val theme: Theme, private val done: (Int, IntArray, IntArray) -> Unit) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val tint = Paint().apply { color = 0x30000000 }
        private val ink = Paint().apply {
            color = 0xC0FFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3 * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }
        // A swipe's trail in the accent, so the finger knows it is scrolling, not selecting.
        private val swipeInk = Paint(ink).apply { color = theme.accent }
        private val fill = Paint().apply { color = theme.bg; isAntiAlias = true }
        private val rim = Paint().apply {
            color = theme.accent
            style = Paint.Style.STROKE
            strokeWidth = density
            isAntiAlias = true
        }
        private val label = Paint().apply {
            color = theme.fg
            textSize = 14 * density
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val doubleSlop = ViewConfiguration.get(context).scaledDoubleTapSlop
        private val doubleTapMs = ViewConfiguration.getDoubleTapTimeout().toLong()
        private val points = ArrayList<Int>()
        private val times = ArrayList<Int>()
        private val trail = Path()
        private var downX = 0f // where the finger went down, in view coordinates
        private var downY = 0f
        private var downAt = 0L // when, in event time
        private var moved = false
        private var over = false // reported; the rest of this gesture is nobody's
        private var menu = false // the long-press menu is up; the next tap chooses
        private var swipe = false // this touch began as the second tap of a double tap
        private var tapped = false // a tap lifted and waits out the double-tap window
        private var tapX = 0f // where that tap was, in view coordinates
        private var tapY = 0f
        private val tap = Runnable {
            tapped = false
            finish(LEFT)
        }
        private val items = arrayOf("Right click", "Select text", "Copy image")
        private val choices = intArrayOf(RIGHT, TEXT, IMAGE)
        private val boxes = Array(items.size) { RectF() } // in view coordinates
        private val hold = Runnable {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            over = true
            menu = true
            layoutMenu()
            invalidate()
        }

        /**
         * Pills in rows, centred over the finger and clear of it, kept on screen. Rows wrap
         * where the screen is too narrow for one: three pills are about 355 dp, and a
         * 320 dp-class screen or a large display size has less than that.
         */
        private fun layoutMenu() {
            val h = 40 * density
            val gap = 8 * density
            val pad = 16 * density
            val w = FloatArray(items.size) { label.measureText(items[it]) + 2 * pad }
            // Rows: each a run of indices whose widths and gaps fit in the width.
            val rows = ArrayList<IntRange>()
            var start = 0
            var run = 0f
            for (i in items.indices) {
                val next = if (i == start) w[i] else run + gap + w[i]
                if (i > start && next > width - 2 * gap) {
                    rows.add(start until i)
                    start = i
                    run = w[i]
                } else run = next
            }
            rows.add(start until items.size)
            val block = rows.size * h + (rows.size - 1) * gap
            var top = downY - 56 * density - block
            if (top < gap) top = downY + 56 * density
            for (row in rows) {
                val total = row.sumOf { w[it].toDouble() }.toFloat() + gap * (row.count() - 1)
                var left = (downX - total / 2).coerceAtMost(width - total - gap).coerceAtLeast(gap)
                for (i in row) {
                    boxes[i].set(left, top, left + w[i], top + h)
                    left += w[i] + gap
                }
                top += h + gap
            }
        }

        private fun finish(button: Int) {
            if (over && !menu) return
            over = true
            menu = false
            removeCallbacks(hold)
            removeCallbacks(tap)
            done(button, points.toIntArray(), times.toIntArray())
        }

        private fun record(e: MotionEvent) {
            points.add(e.rawX.toInt())
            points.add(e.rawY.toInt())
            times.add((e.eventTime - downAt).toInt())
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), tint)
            if (moved) canvas.drawPath(trail, if (swipe) swipeInk else ink)
            if (menu) for (i in items.indices) {
                val b = boxes[i]
                val r = b.height() / 2
                canvas.drawRoundRect(b, r, r, fill)
                canvas.drawRoundRect(b, r, r, rim)
                canvas.drawText(items[i], b.centerX(), b.centerY() - (label.ascent() + label.descent()) / 2, label)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (menu) {
                        // The point reported is still the long-pressed one, not the tap.
                        val i = boxes.indexOfFirst { it.contains(e.x, e.y) }
                        finish(if (i < 0) CANCEL else choices[i])
                        return true
                    }
                    if (tapped) {
                        // A second touch close to the first tap makes a double tap; one
                        // elsewhere means the first was a click after all, which goes now,
                        // and this touch is nobody's.
                        removeCallbacks(tap)
                        tapped = false
                        if (Math.abs(e.x - tapX) > doubleSlop || Math.abs(e.y - tapY) > doubleSlop) {
                            finish(LEFT)
                            return true
                        }
                        swipe = true
                    } else swipe = false
                    points.clear()
                    times.clear()
                    downAt = e.eventTime
                    record(e)
                    downX = e.x
                    downY = e.y
                    trail.reset()
                    trail.moveTo(e.x, e.y)
                    moved = false
                    over = false
                    if (!swipe) postDelayed(hold, 500)
                }
                MotionEvent.ACTION_POINTER_DOWN -> finish(CANCEL)
                MotionEvent.ACTION_MOVE -> if (!over) {
                    if (!moved && (Math.abs(e.rawX - points[0]) > slop || Math.abs(e.rawY - points[1]) > slop)) {
                        moved = true
                        removeCallbacks(hold)
                    }
                    if (moved) {
                        record(e)
                        trail.lineTo(e.x, e.y)
                        invalidate()
                    }
                }
                // The long press's own lift, with the menu up, is not the choice.
                MotionEvent.ACTION_UP -> if (!menu && !over) {
                    removeCallbacks(hold)
                    if (moved) finish(if (swipe) SWIPE else DRAG)
                    else if (swipe) finish(DOUBLE)
                    else {
                        // A click only once no second tap has come: the wait is what a
                        // double tap costs, the same as anywhere else on the phone.
                        tapped = true
                        tapX = e.x
                        tapY = e.y
                        postDelayed(tap, doubleTapMs)
                    }
                }
                MotionEvent.ACTION_CANCEL -> if (!menu) finish(CANCEL)
            }
            return true
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(hold)
            removeCallbacks(tap)
            super.onDetachedFromWindow()
        }

        companion object {
            const val CANCEL = 0
            const val LEFT = 1
            const val RIGHT = 2
            const val DRAG = 3
            const val TEXT = 4
            const val IMAGE = 5
            const val SWIPE = 6
            const val DOUBLE = 7
        }
    }

    /** "Display over other apps" can be revoked while we run; then the host has to stop. */
    private fun add(v: View, lp: WindowManager.LayoutParams): Boolean = try {
        wm.addView(v, lp)
        true
    } catch (_: WindowManager.BadTokenException) {
        onLost.run()
        false
    }

    private fun send(action: Int, code: Int, meta: Int, repeat: Int) {
        if (client.connected) client.send(action, code, meta, repeat) else client.ensure()
    }

    companion object {
        private const val MIN_PX = 100
        private const val GRID_ROW_DP = 40
        private const val TARGET_DP = 48
        /** How far past the top a pull has to go, in dp, before letting go dismisses the pill. */
        private const val DISMISS_DP = 32

        /** The live overlays, for the settings screen. */
        var instance: Overlays? = null

    }
}
