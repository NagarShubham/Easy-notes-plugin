package com.snagar.easynotes.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

internal class FontSettingsDialog(
    project: Project?,
    private val initialFamily: String,
    private val initialSize: Int
) : DialogWrapper(project) {

    private val families: Array<String> =
        GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames

    private val familyCombo = JComboBox(families)
    private val sizeCombo = JComboBox(arrayOf(8, 9, 10, 11, 12, 13, 14, 16, 18, 20, 22, 24, 28, 32, 36))
    private val preview = JBLabel()

    init {
        title = "Note Font"
        familyCombo.selectedItem = initialFamily.takeIf { families.contains(it) } ?: families.firstOrNull()
        sizeCombo.selectedItem = initialSize
        sizeCombo.isEditable = true
        familyCombo.addActionListener { updatePreview() }
        sizeCombo.addActionListener { updatePreview() }
        init()
        updatePreview()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 10))
        panel.preferredSize = Dimension(380, 200)

        val controls = JPanel(GridLayout(2, 2, 8, 8))
        controls.add(JBLabel("Font:"))
        controls.add(familyCombo)
        controls.add(JBLabel("Size:"))
        controls.add(sizeCombo)
        panel.add(controls, BorderLayout.NORTH)

        preview.text = "The quick brown fox jumps over the lazy dog."
        preview.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.border()),
            JBUI.Borders.empty(10)
        )
        preview.verticalAlignment = JBLabel.CENTER
        panel.add(preview, BorderLayout.CENTER)
        return panel
    }

    private fun currentSize(): Int {
        val raw = sizeCombo.selectedItem
        val value = when (raw) {
            is Int -> raw
            else -> raw?.toString()?.trim()?.toIntOrNull() ?: initialSize
        }
        return value.coerceIn(6, 96)
    }

    private fun updatePreview() {
        val family = familyCombo.selectedItem as? String ?: initialFamily
        preview.font = Font(family, Font.PLAIN, currentSize())
    }

    fun selectedFamily(): String = familyCombo.selectedItem as? String ?: initialFamily

    fun selectedSize(): Int = currentSize()
}
