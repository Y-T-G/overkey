package dev.noblebits.overkey

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View

/**
 * The app name lit the same way as the icon: copies of the word stepped along the light
 * vector fill in a solid side, a faint copy the other way is the lit top edge, and the face
 * carries a gradient from white toward the accent so it ends lighter than the side begins.
 */
class WordmarkView(context: Context) : View(context) {

    private val word = "overkey"
    private val density = resources.displayMetrics.density
    private val depth = 5 * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 44 * resources.displayMetrics.scaledDensity
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.06f
    }
    private val textWidth = paint.measureText(word)
    private val ascent = -paint.ascent()
    private val textHeight = ascent + paint.descent()

    private val accent = context.getColor(R.color.accent)
    private val white = context.getColor(R.color.white)
    private val wall = blend(accent, context.getColor(R.color.surface), 0.45f)
    private val face = LinearGradient(0f, 0f, textWidth, textHeight, white, blend(white, accent, 0.55f), Shader.TileMode.CLAMP)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((textWidth + depth + 2).toInt(), (textHeight + depth + 2).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val x = (width - textWidth - depth) / 2
        val y = ascent + 1
        paint.shader = null
        paint.color = wall
        val steps = Math.ceil(depth.toDouble()).toInt()
        for (i in steps downTo 1) {
            val along = depth * i / steps
            canvas.drawText(word, x + LIGHT_X * along, y + LIGHT_Y * along, paint)
        }
        paint.color = (white and 0xFFFFFF) or 0x59000000
        canvas.drawText(word, x - LIGHT_X * depth / 12, y - LIGHT_Y * depth / 12, paint)
        paint.shader = face
        canvas.drawText(word, x, y, paint)
    }

    companion object {
        // One light, down and to the right, the same one the launcher icon is lit by.
        const val LIGHT_X = 0.62f
        const val LIGHT_Y = 0.78f

        /** [t] of the way from [a] to [b]. */
        fun blend(a: Int, b: Int, t: Float): Int {
            fun ch(shift: Int) = Math.round(((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t)
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
