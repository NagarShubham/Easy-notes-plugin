package com.snagar.easynotes.ui

import com.intellij.ui.components.JBTextArea
import com.snagar.easynotes.model.Note
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints

/**
 * A text area painted to look like a yellow ruled legal pad: a solid paper
 * background, faint horizontal rule lines aligned to the text rows, and a subtle
 * left margin line.
 */
class RuledTextArea : JBTextArea() {

    var paperColor: Color = Color(Note.DEFAULT_COLOR_RGB)
        set(value) {
            field = value
            repaint()
        }

    private val ruleColor = Color(90, 90, 90, 38)
    private val marginColor = Color(210, 110, 110, 80)

    init {
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = paperColor
            g2.fillRect(0, 0, width, height)

            val fm = getFontMetrics(font)
            val lineHeight = fm.height
            if (lineHeight > 0) {
                g2.color = ruleColor
                var y = insets.top + fm.ascent + fm.descent
                while (y < height) {
                    g2.drawLine(0, y, width, y)
                    y += lineHeight
                }
            }

            val marginX = insets.left - 6
            if (marginX > 2) {
                g2.color = marginColor
                g2.drawLine(marginX, 0, marginX, height)
            }
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}
