package com.snagar.easynotes.ui

import com.snagar.easynotes.model.Note
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

internal class DeleteNotesDialog(
    project: Project?,
    private val notes: List<Note>,
    preselected: Set<String>
) : DialogWrapper(project) {

    private val checkBoxList = CheckBoxList<Note>()

    init {
        title = "Delete Notes"
        setOKButtonText("Delete")
        notes.forEach { checkBoxList.addItem(it, it.displayTitle(), it.id in preselected) }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        val hint = JBLabel("Select the notes you want to delete:")
        hint.border = JBUI.Borders.emptyBottom(6)
        panel.add(hint, BorderLayout.NORTH)
        val scroll = JBScrollPane(checkBoxList)
        scroll.preferredSize = Dimension(360, 320)
        panel.add(scroll, BorderLayout.CENTER)
        return panel
    }

    fun selectedNotes(): List<Note> = notes.filter { checkBoxList.isItemSelected(it) }

    override fun doValidate(): ValidationInfo? =
        if (notes.none { checkBoxList.isItemSelected(it) }) {
            ValidationInfo("Select at least one note to delete.")
        } else {
            null
        }
}
