package com.snagar.easynotes.ui

import com.snagar.easynotes.model.Note
import java.awt.Color
import kotlin.math.pow

/**
 * Colors for the legal-pad surface. Chrome (tool window, list, format bar)
 * follows the IDE theme; everything on the pad uses this palette so dark
 * Darcula text never lands on yellow paper.
 *
 * Ink and token colors are chosen for contrast on light paper (WCAG-ish
 * against the default `#FFFDE0` pad).
 */
object PadColors {

    val ink: Color = Color(0x1E1E1E)
    val mutedInk: Color = Color(30, 30, 30, 120)
    val selection: Color = Color(0xE4C04A)

    val rule: Color = Color(80, 80, 80, 40)
    val margin: Color = Color(188, 92, 92, 95)

    val findAll: Color = Color(0xFFE082)
    val findActive: Color = Color(0xFFB300)

    val inlineCodeBg: Color = Color(30, 30, 30, 32)
    val blockCodeBg: Color = Color(30, 30, 30, 24)
    val blockBorder: Color = Color(0xB59A4E)
    val blockAccent: Color = Color(0xC9A227)
    /** Left inset so text clears the accent bar. Keep small so long lines wrap less. */
    const val CODE_PAD_H: Float = 10f
    const val CODE_PAD_RIGHT: Float = 2f
    const val CODE_PAD_V: Float = 3f

    val keyword: Color = Color(0x0B4F9C)
    val string: Color = Color(0x1B5E20)
    val comment: Color = Color(0x5D4E37)
    val number: Color = Color(0x00695C)
    val type: Color = Color(0xBF360C)

    val transparent: Color = Color(0, true)

    fun paper(rgb: Int): Color = ensureReadablePaper(Color(rgb and 0xFFFFFF))

    /**
     * Custom colors from the picker can be dark. Blend them toward white so
     * dark ink and amber selection stay readable.
     */
    fun ensureReadablePaper(paper: Color): Color {
        if (relativeLuminance(paper) >= 0.55) return paper
        val t = 0.68
        return Color(
            blend(paper.red, 255, t),
            blend(paper.green, 255, t),
            blend(paper.blue, 255, t),
        )
    }

    fun inkOn(paper: Color?): Color {
        val p = paper ?: defaultPaper
        return if (relativeLuminance(p) < 0.45) Color(0xF3F3F3) else ink
    }

    fun selectionOn(paper: Color?): Color {
        val p = paper ?: defaultPaper
        return if (relativeLuminance(p) < 0.45) Color(0x5C6BC0) else selection
    }

    fun relativeLuminance(c: Color): Double {
        fun chan(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * chan(c.red) + 0.7152 * chan(c.green) + 0.0722 * chan(c.blue)
    }

    private fun blend(from: Int, to: Int, t: Double): Int =
        (from + (to - from) * t).toInt().coerceIn(0, 255)

    val defaultPaper: Color = Color(Note.DEFAULT_COLOR_RGB)
}
