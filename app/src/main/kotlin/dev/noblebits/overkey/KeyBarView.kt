package dev.noblebits.overkey

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.SparseArray
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import java.util.function.Consumer

/** Where key events go: (action, keyCode, metaState, repeat). */
fun interface KeySink {
    fun key(action: Int, code: Int, meta: Int, repeat: Int)
}

/**
 * One key. [width] is in key units; a row's units are stretched to the view width. A key with
 * [hold] does not repeat: a tap sends it plainly, holding it sends it with those meta bits.
 */
class Key(val label: String, val code: Int, val meta: Int = 0, val width: Float = 1f, val hold: Int = 0) {
    val isModifier get() = meta != 0
    val isSpacer get() = code == 0
    var held = 0 // fingers currently on this key
}

/**
 * Modifier state shared by every [KeyBarView]. Modifier keys are sent down when they become
 * active and up when they stop, like a held physical key, so the app sees a real chord.
 */
class Mods(private val sink: KeySink, private val onChange: Runnable) {
    var sticky = 0 // armed for the next key
        private set
    var locked = 0 // stays on until tapped again
        private set
    var held = 0 // finger currently on the key
        private set
    val active get() = sticky or locked or held
    /** True while a held modifier has not been used in a chord; a long press locks only then. */
    var tap = false
        private set

    fun press(k: Key) {
        if ((active and k.meta) == 0) sink.key(KeyEvent.ACTION_DOWN, k.code, k.meta, 0)
        held = held or k.meta
        tap = true
        onChange.run()
    }

    /** Any non-modifier key went down, on any view. A held modifier is now a chord, not a tap. */
    fun used() {
        tap = false
    }

    /**
     * [arm]: a tap that was not a chord leaves the modifier armed for the next key, or
     * releases one already armed. A volume key passes false: letting go of it lets go of
     * the modifier, like a physical key.
     */
    fun release(k: Key, arm: Boolean = true) {
        held = held and k.meta.inv()
        if (tap && arm) {
            if (((sticky or locked) and k.meta) != 0) {
                sticky = sticky and k.meta.inv()
                locked = locked and k.meta.inv()
            } else {
                sticky = sticky or k.meta
            }
        }
        if ((active and k.meta) == 0) sink.key(KeyEvent.ACTION_UP, k.code, 0, 0)
        onChange.run()
    }

    fun toggleLock(k: Key) {
        locked = locked xor k.meta
        sticky = sticky and k.meta.inv()
        tap = false
        onChange.run()
    }

    /** A normal key was released: one-shot modifiers are used up. */
    fun spend() {
        val spent = sticky
        sticky = 0
        for (m in MODIFIERS) {
            if ((spent and m.meta) != 0 && (active and m.meta) == 0) sink.key(KeyEvent.ACTION_UP, m.code, 0, 0)
        }
        onChange.run()
    }

    /** Keyboard went away: let go of everything so it does not come back armed. */
    fun reset() {
        val was = active
        sticky = 0
        locked = 0
        held = 0
        tap = false
        for (m in MODIFIERS) if ((was and m.meta) != 0) sink.key(KeyEvent.ACTION_UP, m.code, 0, 0)
        onChange.run()
    }

    companion object {
        val SHIFT = Key("SHIFT", KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)
        val CTRL = Key("CTRL", KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        val ALT = Key("ALT", KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON)
        val META = Key("META", KeyEvent.KEYCODE_META_LEFT, KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON)
        val MODIFIERS = arrayOf(SHIFT, CTRL, ALT, META)

        /** Modifiers that need a letter to be useful. Shift alone works with the arrow keys. */
        const val CHORD_META = KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_META_ON
    }
}

/**
 * Rows of keys drawn on one canvas. Modifiers are sticky: tap arms them for the next key,
 * long-press locks them, tap again to release. Other keys auto-repeat while held.
 *
 * Every key press and release goes to [sink] as (action, keyCode, metaState, repeat).
 * [rowHeight] fixes the row height in px; 0 fills whatever height the window gives.
 *
 * With [calibrate] set, touches drag the window edges instead: top half moves the top edge,
 * bottom half the bottom edge, the outer tenth on each side moves that side.
 *
 * With [grip] set, the bottom strip of the view is a handle marked by a pill: drag moves the
 * window, tap is [onCollapse], long press is [gripHold]. A view with [collapsed] set is the
 * pill alone, a small capsule whose whole area is the handle.
 */
class KeyBarView(
    context: Context,
    private val rows: Array<Array<Key>>,
    private val mods: Mods,
    rowHeight: Int,
    private val sink: KeySink,
) : View(context) {

    var rowHeight = rowHeight
        set(v) {
            field = v
            requestLayout()
        }
    var theme: Theme = Prefs.THEMES[0]
        set(v) {
            field = v
            invalidate()
        }
    var bgAlpha = 0xFF
        set(v) {
            field = v
            invalidate()
        }
    var fgAlpha = 0xFF
        set(v) {
            field = v
            invalidate()
        }
    /** Receives (top, bottom, left, right) edge deltas in px while dragging, then null on release. */
    var calibrate: Consumer<IntArray?>? = null
        set(v) {
            field = v
            invalidate()
        }
    /**
     * Live vertical drag from the top strip of the view: receives dy in px per move, then null
     * on release. A tap on the strip without a drag still presses the key underneath.
     */
    var grip: Consumer<Int?>? = null
        set(v) {
            field = v
            invalidate()
        }
    /** Long press on the grip without moving. */
    var gripHold: Runnable? = null
    /** This view is the pill alone. */
    var collapsed = false
    /** Tap on the grip. */
    var onCollapse: Runnable? = null

    val gripHeight get() = gripPx
    val pillWidth get() = (80 * density).toInt()
    val pillHeight get() = (28 * density).toInt()

    private val density = resources.displayMetrics.density
    private val units = FloatArray(rows.size) { r -> rows[r].sumOf { it.width.toDouble() }.toFloat() }

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14 * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.1f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val fill = Paint()
    private val border = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
    }

    private val pointerKey = SparseArray<Key>() // pointerId -> key under that finger
    private val pointerTask = SparseArray<Runnable>() // pointerId -> repeat or long-press task
    private val pointerHeld = SparseArray<Key>() // pointerId -> hold key whose long press already fired
    private var dragX = 0f
    private var dragY = 0f
    private var dragZone = 0 // bit 0 top, 1 bottom, 2 left, 3 right
    private val gripPx = (14 * density).toInt()
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var gripPointer = -1
    private var gripStart = 0f
    private var gripLast = 0f
    private var gripMoved = false
    private var gripFired = false
    private val gripHoldTask = Runnable {
        gripFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        gripHold?.run()
    }
    private var repeat = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (collapsed) pillHeight else if (rowHeight > 0) rowHeight * rows.size + gripPx else MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onDraw(canvas: Canvas) {
        if (collapsed) {
            // A capsule smaller than the window, so the touch target is generous without looking
            // it, set in an even accent-tinted surround so it can be found against a dark
            // keyboard, a faint rim, the handle in the accent. Even on every side: the bar sits
            // anywhere on the screen, and a wall pushed one way reads as a slant.
            val cw = 64 * density
            val ch = 20 * density
            val l = (width - cw) / 2
            val t = (height - ch) / 2
            val lift = 1.5f * density
            val r = ch / 2
            fill.color = WordmarkView.blend(theme.bg, theme.accent, 0.2f)
            canvas.drawRoundRect(l - lift, t - lift, l + cw + lift, t + ch + lift, r + lift, r + lift, fill)
            fill.color = (theme.bg and 0xFFFFFF) or (bgAlpha shl 24)
            canvas.drawRoundRect(l, t, l + cw, t + ch, r, r, fill)
            border.color = (theme.fg and 0xFFFFFF) or 0x1C000000
            border.strokeWidth = density
            canvas.drawRoundRect(l + 0.5f, t + 0.5f, l + cw - 0.5f, t + ch - 0.5f, r, r, border)
            border.strokeWidth = 3 * density
            fill.color = theme.accent
            val w = 24 * density
            val h = 3 * density
            canvas.drawRoundRect((width - w) / 2, t + (ch - h) / 2, (width + w) / 2, t + (ch + h) / 2, h, h, fill)
            return
        }
        canvas.drawColor((theme.bg and 0xFFFFFF) or (bgAlpha shl 24))
        val rh = (height - if (grip != null) gripPx else 0).toFloat() / rows.size
        val baseline = (rh - text.descent() - text.ascent()) / 2
        val pad = 2 * density
        val active = mods.active
        for (r in rows.indices) {
            val unit = width / units[r]
            val y = r * rh
            var x = 0f
            for (k in rows[r]) {
                val w = k.width * unit
                if (!k.isSpacer) {
                    val on = k.isModifier && (active and k.meta) != 0
                    val lockedOn = on && (mods.locked and k.meta) != 0
                    if (k.held > 0 || on) {
                        fill.color = when {
                            k.held > 0 -> theme.pressed
                            lockedOn -> theme.accent
                            else -> (theme.accent and 0xFFFFFF) or 0x55000000
                        }
                        fill.alpha = fill.alpha * fgAlpha / 0xFF
                        canvas.drawRect(x + pad, y + pad, x + w - pad, y + rh - pad, fill)
                    }
                    text.color = ((if (lockedOn) theme.bg else theme.fg) and 0xFFFFFF) or (fgAlpha shl 24)
                    canvas.drawText(k.label, x + w / 2, y + baseline, text)
                }
                x += w
            }
        }
        if (calibrate != null) {
            border.color = theme.accent
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), border)
        }
        if (grip != null) drawPill(canvas)
    }

    /** The handle: a short bar centred in the bottom strip. */
    private fun drawPill(canvas: Canvas) {
        fill.color = (theme.accent and 0xFFFFFF) or 0xAA000000.toInt()
        val w = 24 * density
        val h = 3 * density
        val top = height - (gripPx + h) / 2
        canvas.drawRoundRect((width - w) / 2, top, (width + w) / 2, top + h, h, h, fill)
    }

    private fun keyAt(px: Float, py: Float): Key? {
        val r = (py / (height - if (grip != null) gripPx else 0) * rows.size).toInt()
        if (r < 0 || r >= rows.size) return null
        val unit = width / units[r]
        var x = 0f
        for (k in rows[r]) {
            x += k.width * unit
            if (px < x) return if (k.isSpacer) null else k
        }
        return null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (calibrate != null) return drag(e)
        // The handle accepts touches a little above its drawn strip.
        if (gripPointer >= 0 || (grip != null && e.actionMasked == MotionEvent.ACTION_DOWN &&
                (collapsed || e.y > height - gripPx - 6 * density))) {
            return gripTouch(e)
        }
        val i = e.actionIndex
        val id = e.getPointerId(i)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val k = keyAt(e.getX(i), e.getY(i)) ?: return true
                pointerKey.put(id, k)
                k.held++
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (k.isModifier) {
                    if (k.held == 1) mods.press(k)
                    schedule(id, LONG_PRESS_MS) {
                        // Held for a chord instead? Then it is not a lock request.
                        if (mods.tap) {
                            mods.toggleLock(k)
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }
                    }
                } else if (k.hold != 0) {
                    // Nothing goes out yet: a tap and a hold send different things.
                    mods.used()
                    schedule(id, LONG_PRESS_MS) {
                        pointerHeld.put(id, k)
                        sink.key(KeyEvent.ACTION_DOWN, k.code, mods.active or k.hold, 0)
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                } else {
                    mods.used()
                    sink.key(KeyEvent.ACTION_DOWN, k.code, mods.active, 0)
                    repeat = 0
                    schedule(id, REPEAT_DELAY_MS, REPEAT_RATE_MS) {
                        sink.key(KeyEvent.ACTION_DOWN, k.code, mods.active, ++repeat)
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> release(id)
            MotionEvent.ACTION_CANCEL -> {
                for (n in pointerKey.size() - 1 downTo 0) release(pointerKey.keyAt(n))
            }
        }
        return true
    }

    private fun gripTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gripPointer = e.getPointerId(0)
                gripStart = e.rawY
                gripLast = e.rawY
                gripMoved = false
                gripFired = false
                if (gripHold != null) postDelayed(gripHoldTask, LONG_PRESS_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(gripPointer)
                if (i < 0) return true
                // Raw coordinates: the window moves under the finger while dragging.
                val y = e.getY(i) + (e.rawY - e.y)
                if (!gripMoved && Math.abs(y - gripStart) > slop) {
                    gripMoved = true
                    removeCallbacks(gripHoldTask)
                }
                if (gripFired) return true
                if (gripMoved) {
                    // Whole pixels only; the remainder stays in the origin so slow drags add up.
                    val d = (y - gripLast).toInt()
                    if (d != 0) {
                        grip?.accept(d)
                        gripLast += d
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (e.getPointerId(e.actionIndex) != gripPointer && e.actionMasked != MotionEvent.ACTION_CANCEL) return true
                removeCallbacks(gripHoldTask)
                if (gripFired) {
                    // The long press already did its thing.
                } else if (gripMoved) {
                    grip?.accept(null)
                } else if (e.actionMasked != MotionEvent.ACTION_CANCEL) {
                    // A plain tap on the pill folds the bar away, or brings it back.
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onCollapse?.run()
                }
                gripPointer = -1
            }
        }
        return true
    }

    private fun drag(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragX = e.rawX
                dragY = e.rawY
                dragZone = (if (e.y < height / 2) 1 else 2) or
                    (if (e.x < width / 10) 4 else if (e.x > width * 9 / 10) 8 else 0)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.rawX - dragX).toInt()
                val dy = (e.rawY - dragY).toInt()
                dragX += dx
                dragY += dy
                if (dx == 0 && dy == 0) return true
                calibrate?.accept(intArrayOf(
                    if (dragZone and 1 != 0) dy else 0,
                    if (dragZone and 2 != 0) dy else 0,
                    if (dragZone and 4 != 0) dx else 0,
                    if (dragZone and 8 != 0) dx else 0,
                ))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> calibrate?.accept(null)
        }
        return true
    }

    private fun release(id: Int) {
        val k = pointerKey.get(id) ?: return
        pointerKey.remove(id)
        pointerTask.get(id)?.let { removeCallbacks(it) }
        pointerTask.remove(id)
        k.held--
        if (k.isModifier) {
            if (k.held == 0) mods.release(k)
        } else if (k.hold != 0) {
            if (pointerHeld.get(id) != null) {
                pointerHeld.remove(id)
                sink.key(KeyEvent.ACTION_UP, k.code, mods.active or k.hold, 0)
            } else {
                sink.key(KeyEvent.ACTION_DOWN, k.code, mods.active, 0)
                sink.key(KeyEvent.ACTION_UP, k.code, mods.active, 0)
            }
            mods.spend()
        } else {
            sink.key(KeyEvent.ACTION_UP, k.code, mods.active, 0)
            mods.spend()
        }
        invalidate()
    }

    private fun schedule(id: Int, delay: Long, every: Long = 0, task: Runnable) {
        val r = object : Runnable {
            override fun run() {
                task.run()
                if (every > 0) postDelayed(this, every)
            }
        }
        pointerTask.put(id, r)
        postDelayed(r, delay)
    }

    override fun onDetachedFromWindow() {
        // No ACTION_CANCEL arrives once the window is gone: let go of every key ourselves so
        // the app is not left with a key or modifier held down.
        for (n in pointerKey.size() - 1 downTo 0) release(pointerKey.keyAt(n))
        removeCallbacks(gripHoldTask)
        gripPointer = -1
        super.onDetachedFromWindow()
    }

    companion object {
        private const val LONG_PRESS_MS = 450L
        private const val REPEAT_DELAY_MS = 400L
        private const val REPEAT_RATE_MS = 50L

        const val BAR_ROW_DP = 38

        /** Termux's row with the drawer and keyboard toggle swapped for Shift and Meta. */
        val BAR = arrayOf(
            arrayOf(
                Key("ESC", KeyEvent.KEYCODE_ESCAPE), Mods.SHIFT, Mods.META,
                Key("HOME", KeyEvent.KEYCODE_MOVE_HOME), Key("↑", KeyEvent.KEYCODE_DPAD_UP),
                Key("END", KeyEvent.KEYCODE_MOVE_END), Key("PGUP", KeyEvent.KEYCODE_PAGE_UP),
            ),
            arrayOf(
                // A plain Tab moves focus in text fields; with the SYM bit TextView lets it through
                // to the key listener, which still maps it to a literal tab. Terminals take both.
                Key("TAB", KeyEvent.KEYCODE_TAB, hold = KeyEvent.META_SYM_ON), Mods.CTRL, Mods.ALT,
                Key("←", KeyEvent.KEYCODE_DPAD_LEFT), Key("↓", KeyEvent.KEYCODE_DPAD_DOWN),
                Key("→", KeyEvent.KEYCODE_DPAD_RIGHT), Key("PGDN", KeyEvent.KEYCODE_PAGE_DOWN),
            ),
        )

        /**
         * Covers the keyboard while Ctrl, Alt or Meta is armed, so the letter comes from us.
         * Staggered like a QWERTY keyboard so it can be aligned with the one underneath.
         */
        val CHORD = arrayOf(
            row("1234567890", KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4,
                KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_9,
                KeyEvent.KEYCODE_0),
            row("qwertyuiop", KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_R,
                KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_Y, KeyEvent.KEYCODE_U, KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_O,
                KeyEvent.KEYCODE_P),
            arrayOf(Key("", 0, 0, 0.5f)) + row("asdfghjkl", KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S,
                KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_J,
                KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_L) + Key("", 0, 0, 0.5f),
            arrayOf(Key("/", KeyEvent.KEYCODE_SLASH, 0, 1.5f)) + row("zxcvbnm", KeyEvent.KEYCODE_Z,
                KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_N,
                KeyEvent.KEYCODE_M) + Key("⌫", KeyEvent.KEYCODE_DEL, 0, 1.5f),
            arrayOf(
                Key("-", KeyEvent.KEYCODE_MINUS), Key("=", KeyEvent.KEYCODE_EQUALS),
                Key("[", KeyEvent.KEYCODE_LEFT_BRACKET), Key("]", KeyEvent.KEYCODE_RIGHT_BRACKET),
                Key(",", KeyEvent.KEYCODE_COMMA), Key("SPC", KeyEvent.KEYCODE_SPACE, 0, 2.5f),
                Key(".", KeyEvent.KEYCODE_PERIOD), Key("⏎", KeyEvent.KEYCODE_ENTER, 0, 1.5f),
            ),
        )

        private fun row(labels: String, vararg codes: Int) =
            Array(codes.size) { Key(labels[it].toString(), codes[it]) }
    }
}
