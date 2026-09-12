package dev.noblebits.overkey

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
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
            if (dy == null) save() else {
                barY += dy
                layoutBar()
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
        ready = true
        reload()
        instance = this
    }

    fun destroy() {
        if (instance === this) instance = null
        aimView?.let { wm.removeView(it) }
        aimView = null
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
     * A held volume key is a finger on its bound modifier key, only while a keyboard is up so
     * the keys keep their normal job elsewhere. Both held with a binding of their own wins
     * over the single-key bindings. Letting go lets go: unlike a tap on the bar, a volume key
     * never leaves the modifier armed.
     */
    private fun volumeKeys(mask: Int) {
        val want = ArrayList<Key>(2)
        if (!imeBounds.isEmpty && onKeyboard) {
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
    private fun layoutBar() {
        val r = imeBounds
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
            val h = (GRID_ROW_DP * KeyBarView.CHORD.size * density).toInt()
            chordLp.x = r.left
            chordLp.y = barTop() - h
            chordLp.width = r.width()
            chordLp.height = h
        }
        if (chordShown) wm.updateViewLayout(chord, chordLp) else if (!add(chord, chordLp)) return
        chordShown = true
    }

    private var aimView: View? = null

    /**
     * Aim mode, from a long press on the grip: the whole screen takes the next touch and turns
     * it into a mouse gesture through the injector. A tap is a left click at that spot, a long
     * press a right click, a drag a left-button drag along the same path, replayed once the
     * finger lifts. A touch tap cannot give a page element focus the way a pointer click does,
     * which is what Ctrl+C on an image in a browser needs; a touch drag scrolls where a mouse
     * drag selects. A second finger cancels.
     *
     * Replayed rather than live because the finger is still down on this window: on Android
     * before 14 the dispatcher allows one pointer device at a time, and a mouse DOWN injected
     * mid-touch has the touch dropped.
     */
    private fun aim() {
        if (aimView != null) return
        val v = AimView(context) { button, path ->
            aimView?.let { wm.removeView(it) }
            aimView = null
            if (button == AimView.CANCEL) return@AimView
            // The window takes a frame or two to go; a click sent at once lands on it. Not
            // View.postDelayed: a detached view (the bar, while collapsed) holds the runnable
            // until it is attached again.
            handler.postDelayed({
                if (button == AimView.DRAG) client.drag(thin(path, 32)) else client.click(path[0], path[1], button)
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

    /** At most [n] of the x,y pairs in [path], evenly spaced, the first and last kept. */
    private fun thin(path: IntArray, n: Int): IntArray {
        val pairs = path.size / 2
        if (pairs <= n) return path
        val out = IntArray(n * 2)
        for (i in 0 until n) {
            val j = i * (pairs - 1) / (n - 1)
            out[2 * i] = path[2 * j]
            out[2 * i + 1] = path[2 * j + 1]
        }
        return out
    }

    /**
     * Full-screen tint that reports one gesture: [done] gets the button ([LEFT], [RIGHT],
     * [DRAG] or [CANCEL]) and the touched screen points as x,y pairs, first to last. A drag
     * draws its trail so the finger can see where the pointer will go.
     */
    private class AimView(context: Context, private val done: (Int, IntArray) -> Unit) : View(context) {
        private val tint = Paint().apply { color = 0x30000000 }
        private val ink = Paint().apply {
            color = 0xC0FFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3 * context.resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val points = ArrayList<Int>()
        private val trail = Path()
        private var moved = false
        private var over = false // reported; the rest of this gesture is nobody's
        private val hold = Runnable {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            finish(RIGHT)
        }

        private fun finish(button: Int) {
            if (over) return
            over = true
            removeCallbacks(hold)
            done(button, points.toIntArray())
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), tint)
            if (moved) canvas.drawPath(trail, ink)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    points.clear()
                    points.add(e.rawX.toInt())
                    points.add(e.rawY.toInt())
                    trail.reset()
                    trail.moveTo(e.x, e.y)
                    moved = false
                    over = false
                    postDelayed(hold, 500)
                }
                MotionEvent.ACTION_POINTER_DOWN -> finish(CANCEL)
                MotionEvent.ACTION_MOVE -> if (!over) {
                    if (!moved && (Math.abs(e.rawX - points[0]) > slop || Math.abs(e.rawY - points[1]) > slop)) {
                        moved = true
                        removeCallbacks(hold)
                    }
                    if (moved) {
                        points.add(e.rawX.toInt())
                        points.add(e.rawY.toInt())
                        trail.lineTo(e.x, e.y)
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> finish(if (moved) DRAG else LEFT)
                MotionEvent.ACTION_CANCEL -> finish(CANCEL)
            }
            return true
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(hold)
            super.onDetachedFromWindow()
        }

        companion object {
            const val CANCEL = 0
            const val LEFT = 1
            const val RIGHT = 2
            const val DRAG = 3
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

        /** The live overlays, for the settings screen. */
        var instance: Overlays? = null

    }
}
