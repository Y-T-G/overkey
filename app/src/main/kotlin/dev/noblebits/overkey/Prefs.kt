package dev.noblebits.overkey

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build

/** Colours of one look. [accent] marks armed modifiers, [pressed] a key under a finger. */
class Theme(val name: String, val bg: Int, val fg: Int, val accent: Int, val pressed: Int)

object Prefs {
    val THEMES = arrayOf(
        // The default. A placeholder here; theme() resolves it from the wallpaper palette on
        // 12+, and falls back to Termux below that.
        Theme("System accent", 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFF3A3A3A.toInt()),
        Theme("Termux", 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFF3A3A3A.toInt()),
        Theme("Dracula", 0xFF282A36.toInt(), 0xFFF8F8F2.toInt(), 0xFFBD93F9.toInt(), 0xFF44475A.toInt()),
        Theme("Monokai", 0xFF272822.toInt(), 0xFFF8F8F2.toInt(), 0xFFA6E22E.toInt(), 0xFF49483E.toInt()),
        Theme("One Dark", 0xFF282C34.toInt(), 0xFFABB2BF.toInt(), 0xFF61AFEF.toInt(), 0xFF3E4451.toInt()),
        Theme("Nord", 0xFF2E3440.toInt(), 0xFFD8DEE9.toInt(), 0xFF88C0D0.toInt(), 0xFF3B4252.toInt()),
        Theme("Gruvbox Dark", 0xFF282828.toInt(), 0xFFEBDBB2.toInt(), 0xFFFABD2F.toInt(), 0xFF3C3836.toInt()),
        Theme("Solarized Dark", 0xFF002B36.toInt(), 0xFF93A1A1.toInt(), 0xFF268BD2.toInt(), 0xFF073642.toInt()),
        Theme("Solarized Light", 0xFFFDF6E3.toInt(), 0xFF586E75.toInt(), 0xFF268BD2.toInt(), 0xFFEEE8D5.toInt()),
        Theme("Catppuccin Mocha", 0xFF1E1E2E.toInt(), 0xFFCDD6F4.toInt(), 0xFFCBA6F7.toInt(), 0xFF313244.toInt()),
        Theme("Tokyo Night", 0xFF1A1B26.toInt(), 0xFFA9B1D6.toInt(), 0xFF7AA2F7.toInt(), 0xFF414868.toInt()),
        Theme("GitHub Light", 0xFFFFFFFF.toInt(), 0xFF24292E.toInt(), 0xFF0366D6.toInt(), 0xFFE1E4E8.toInt()),
    )
    private const val SYSTEM = 0
    private const val TERMUX = 1

    const val THEME = "theme"
    const val ALPHA = "alpha" // grid background alpha, 0..255
    const val FG_ALPHA = "fg_alpha" // grid text alpha, 0..255
    const val ROW_DP = "row" // bar row height
    // Modifier held by a volume key while a keyboard is up: index into VOL_MODS, 0 is off.
    const val VOL_DOWN = "vol_down"
    const val VOL_UP = "vol_up"
    const val VOL_BOTH = "vol_both"
    const val ROOT = "root" // su has worked once: root may be used to put things right
    const val BAR_Y = "bar_y" // bar offset in dp, positive moves it down over the keyboard
    const val TOP = "top" // grid edge offsets in dp, relative to the keyboard window
    const val BOTTOM = "bottom"
    const val LEFT = "left"
    const val RIGHT = "right"

    fun of(c: Context): SharedPreferences = c.getSharedPreferences("overkey", Context.MODE_PRIVATE)

    fun theme(c: Context): Theme {
        val i = of(c).getInt(THEME, SYSTEM)
        if (i == SYSTEM || i !in THEMES.indices) return system(c) ?: THEMES[TERMUX]
        return THEMES[i]
    }

    /** The phone's own palette (Android 12+), dark or light to match the system setting. */
    private fun system(c: Context): Theme? {
        if (Build.VERSION.SDK_INT < 31) return null
        val night = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (night) Theme("System accent",
            c.getColor(android.R.color.system_neutral1_900), c.getColor(android.R.color.system_neutral1_50),
            c.getColor(android.R.color.system_accent1_200), c.getColor(android.R.color.system_neutral1_700))
        else Theme("System accent",
            c.getColor(android.R.color.system_neutral1_50), c.getColor(android.R.color.system_neutral1_900),
            c.getColor(android.R.color.system_accent1_600), c.getColor(android.R.color.system_neutral1_200))
    }

    fun root(c: Context) = of(c).getBoolean(ROOT, false)

    fun alpha(c: Context) = of(c).getInt(ALPHA, 0xE0)

    fun fgAlpha(c: Context) = of(c).getInt(FG_ALPHA, 0xFF)

    fun rowDp(c: Context) = of(c).getInt(ROW_DP, 38)

    val VOL_MODS = arrayOf("Off", "Ctrl", "Alt", "Meta", "Shift")

    /** The modifier key bound to a volume key setting, or null. Vol- defaults to Ctrl, as in Termux. */
    fun volMod(c: Context, key: String): Key? = when (of(c).getInt(key, if (key == VOL_DOWN) 1 else 0)) {
        1 -> Mods.CTRL
        2 -> Mods.ALT
        3 -> Mods.META
        4 -> Mods.SHIFT
        else -> null
    }
}
