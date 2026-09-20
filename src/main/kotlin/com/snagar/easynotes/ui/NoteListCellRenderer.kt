package com.snagar.easynotes.ui

import com.snagar.easynotes.model.Note
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/** Two-line list renderer showing a color swatch, title, dates, and pin/star. */
class NoteListCellRenderer : JPanel(BorderLayout()), ListCellRenderer<Note> {

    private val dateFormat = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())

    private val swatch = object : JPanel() {
        init {
            preferredSize = Dimension(12, 12)
            maximumSize = Dimension(12, 12)
            minimumSize = Dimension(12, 12)
        }
    }
    private val titleLabel = JLabel()
    private val subtitleLabel = JLabel()
    private val pinIcon = JLabel("\uD83D\uDCCC").apply { toolTipText = "Pinned" }
    private val starIcon = JLabel("\u2605").apply { toolTipText = "Favorite" }

    private val iconsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
    }

    init {
        border = JBUI.Borders.empty(6, 8)

        val swatchWrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyRight(8)
            add(swatch, BorderLayout.CENTER)
        }

        val textPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        titleLabel.font = titleLabel.font.deriveFont(java.awt.Font.BOLD)
        subtitleLabel.font = subtitleLabel.font.deriveFont(subtitleLabel.font.size2D - 1f)
        textPanel.add(titleLabel)
        textPanel.add(Box.createVerticalStrut(2))
        textPanel.add(subtitleLabel)

        iconsPanel.add(pinIcon)
        iconsPanel.add(Box.createHorizontalStrut(4))
        iconsPanel.add(starIcon)

        add(swatchWrapper, BorderLayout.WEST)
        add(textPanel, BorderLayout.CENTER)
        add(iconsPanel, BorderLayout.EAST)
    }

    override fun getListCellRendererComponent(
        list: JList<out Note>,
        value: Note,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        val bg = if (isSelected) UIUtil.getListSelectionBackground(true) else UIUtil.getListBackground()
        val fg = if (isSelected) UIUtil.getListSelectionForeground(true) else UIUtil.getListForeground()
        isOpaque = true
        background = bg
        titleLabel.foreground = fg
        subtitleLabel.foreground = if (isSelected) fg else UIUtil.getContextHelpForeground()

        swatch.background = JBColor(Color(value.colorRgb), Color(value.colorRgb))
        swatch.border = BorderFactory.createLineBorder(JBColor.border())

        titleLabel.text = value.displayTitle()
        subtitleLabel.text = buildString {
            append("Created ").append(dateFormat.format(Date(value.createdAt)))
            if (value.modifiedAt > value.createdAt) {
                append("   ·   Modified ").append(dateFormat.format(Date(value.modifiedAt)))
            }
        }

        pinIcon.isVisible = value.pinned
        starIcon.isVisible = value.favorite

        return this
    }
}
