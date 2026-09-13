package dev.noblebits.overkey

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The Help screen, opened from the Help row in settings. The non-obvious features documented on
 * their own page rather than crowding the settings list.
 *
 * Each feature is a card; each card is a short list of tips rather than a paragraph. A tip is a
 * lead word in the accent and the rest in grey, so the eye can skim the leads (Tap, Hold, Drag)
 * down the card. Key names (Ctrl+C, Shift) are drawn as monospace keycaps wherever they appear.
 * The card look is the same plinth the settings screen draws, so the two screens read as one app.
 */
class HelpActivity : Activity() {

    private lateinit var root: LinearLayout
    private var dp = 1f

    private val white get() = getColor(R.color.white)
    private val gray get() = getColor(R.color.gray)
    private val accent get() = getColor(R.color.accent)

    /** A line in a card: an optional accent lead, then the grey body. */
    private class Tip(val lead: String?, val body: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Help"
        dp = resources.displayMetrics.density
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(24), px(24), px(24))
        }

        root.addView(label("Help", 22f, white).apply { typeface = Typeface.DEFAULT_BOLD })
        root.addView(label("What the bar does that isn't obvious.", 13f, gray)
            .apply { setLineSpacing(0f, 1.2f) }, lp(top = 6))

        card("Modifiers", listOf(
            Tip("Tap", "a modifier and it stays down for the next key."),
            Tip("Long-press", "to lock it down until you tap it again."),
            Tip("Hold and tap", "another key for a chord."),
            Tip(null, "Arrows and the other keys repeat while held."),
        ))
        card("The grid", listOf(
            Tip(null, "With Ctrl, Alt or Meta down, a grid of letters appears over the keyboard. Tap C there for Ctrl+C."),
            Tip("Why", "the keyboard's own letters type text rather than press keys, so they cannot join a chord."),
            Tip("Shift and the arrows", "select text."),
        ))
        card("The handle", listOf(
            Tip(null, "The strip under the keys."),
            Tip("Drag", "to move the bar."),
            Tip("Tap", "to fold the bar away to a pill; tap the pill to bring it back."),
            Tip("Drag the pill up", "onto the cross at the top to put everything away. Show in the notification brings it back."),
            Tip("Long-press", "for aim mode."),
        ))
        card("Aim mode", listOf(
            Tip(null, "The screen dims and takes one gesture, sent as a mouse through the helper."),
            Tip("Tap", "a click there, which gives an image in a browser the focus that Ctrl+C needs."),
            Tip("Drag", "a mouse drag, replayed when you lift. In a browser it selects text instead of scrolling."),
            Tip("Hold", "a menu: Right click, Select text, Copy image."),
            Tip("Select text", "lifts the text under your finger into a panel where you can select part of it and copy."),
            Tip("Copy image", "puts the picture under your finger on the clipboard as it appears on screen."),
            Tip(null, "A second finger cancels."),
        ))
        card("Share to clipboard", listOf(
            Tip(null, "Overkey shows up in any app's share sheet as Copy to clipboard."),
            Tip("Share in", "text, a picture, a file or several, and they go on the clipboard."),
            Tip("Then", "the next Ctrl+V pastes them, in an app that offers no copy of its own."),
            Tip(null, "Whether a paste lands is the receiving app's call: an image field takes a picture, an editor takes text."),
        ))
        card("The notification", listOf(
            Tip("Pin", "the bar up with no keyboard, for Ctrl+Z on a screen with no text field. The grid stacks above or below it."),
            Tip("Hide", "everything off the screen while the volume keys keep working."),
        ))

        setContentView(ScrollView(this).apply {
            clipToPadding = false
            addView(root)
        })
    }

    private fun card(title: String, tips: List<Tip>) {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            setPadding(px(16), px(14), px(16), px(14) + px(LIFT))
            addView(label(title, 15f, accent).apply { typeface = Typeface.DEFAULT_BOLD })
            for (t in tips) {
                addView(label(tip(t), 13f, gray).apply { setLineSpacing(0f, 1.25f) }, lp(top = 8))
            }
            root.addView(this, lp(top = 10))
        }
    }

    /** An accent, bold lead, then the body in grey, with key names drawn as keycaps throughout. */
    private fun tip(t: Tip): CharSequence {
        val sb = SpannableStringBuilder()
        if (t.lead != null) {
            sb.append(t.lead)
            sb.setSpan(ForegroundColorSpan(accent), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("  ")
        }
        sb.append(t.body)
        keycaps(sb)
        return sb
    }

    /** Draws every key name (Ctrl+C, Shift, the arrows) as a white monospace keycap. */
    private fun keycaps(sb: SpannableStringBuilder) {
        val text = sb.toString()
        for (m in KEYS.findAll(text)) {
            val s = m.range.first
            val e = m.range.last + 1
            sb.setSpan(ForegroundColorSpan(white), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(TypefaceSpan("monospace"), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    // The same building blocks as the settings screen, kept small and local to this screen.

    private fun px(v: Int) = (v * dp).toInt()

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = px(top) }

    private fun label(s: CharSequence, size: Float, color: Int) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
    }

    /** The settings screen's plinth, copied so both screens draw the same card. */
    private fun cardBackground(): Drawable {
        val r = px(14).toFloat()
        val ox = Math.round(WordmarkView.LIGHT_X * LIFT * dp)
        val oy = Math.round(WordmarkView.LIGHT_Y * LIFT * dp)
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

    companion object {
        private const val LIFT = 3
        // Key combos first so "Ctrl+C" is one keycap, then the standalone modifier and nav keys.
        private val KEYS = Regex("Ctrl\\+[A-Z]|Ctrl|Alt|Meta|Shift|Esc|Tab|Home|End|Page Up|Page Down")
    }
}
