package com.snagar.easynotes.ui

import com.snagar.easynotes.model.Note
import com.snagar.easynotes.search.TextSearch
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
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

    /**
     * The active global-search query. When non-blank, matching terms are
     * highlighted in the title and the subtitle shows a matched content snippet
     * instead of the timestamps. Set by [NotesPanel] before each list reload.
     */
    var query: String = ""

    private val dateFormat = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())

    private val swatch = JPanel().apply {
        val size = Dimension(20, 20)
        preferredSize = size
        maximumSize = size
        minimumSize = size
        border = BorderFactory.createLineBorder(JBColor.border())
    }
    private val titleLabel = JLabel().apply { font = font.deriveFont(Font.BOLD) }
    private val subtitleLabel = JLabel().apply { font = font.deriveFont(font.size2D - 1f) }
    private val pinIcon = JLabel("\uD83D\uDCCC").apply { toolTipText = "Pinned" }
    private val starIcon = JLabel("\u2605").apply { toolTipText = "Favorite" }

    init {
        isOpaque = true
        border = JBUI.Borders.empty(6, 8)

        // GridBagLayout keeps the swatch at its preferred size instead of
        // stretching it to the cell height the way BorderLayout.CENTER would.
        val swatchWrapper = JPanel(GridBagLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyRight(8)
            add(swatch, GridBagConstraints())
        }

        val textPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(titleLabel)
            add(Box.createVerticalStrut(2))
            add(subtitleLabel)
        }

        val iconsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(pinIcon)
            add(Box.createHorizontalStrut(4))
            add(starIcon)
        }

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
        background = bg
        titleLabel.foreground = fg
        subtitleLabel.foreground = if (isSelected) fg else UIUtil.getContextHelpForeground()

        swatch.background = JBColor(Color(value.colorRgb), Color(value.colorRgb))

        val q = query.trim()
        if (q.isEmpty()) {
            // Plain text (JLabel treats non-<html> strings as literal, so note
            // titles/content can't accidentally inject markup).
            titleLabel.text = value.displayTitle()
            subtitleLabel.text = datesText(value)
        } else {
            titleLabel.text = "<html>" + TextSearch.highlightHtml(value.displayTitle(), q) + "</html>"
            val snippet = TextSearch.snippet(value.content, q)
            subtitleLabel.text = if (snippet != null) {
                "<html>" + TextSearch.highlightHtml(snippet, q) + "</html>"
            } else {
                datesText(value)
            }
        }

        pinIcon.isVisible = value.pinned
        starIcon.isVisible = value.favorite
        return this
    }

    private fun datesText(value: Note): String = buildString {
        append("Created ").append(dateFormat.format(Date(value.createdAt)))
        if (value.modifiedAt > value.createdAt) {
            append("   \u00B7   Modified ").append(dateFormat.format(Date(value.modifiedAt)))
        }
    }
}
