package com.snagar.quicknotes.actions

import com.snagar.quicknotes.ui.NotesPanel
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import javax.swing.JComponent

/**
 * Base action that resolves the [NotesPanel] from the event data context.
 *
 * Extends [DumbAwareAction] so the note toolbar stays usable while the IDE is
 * indexing (notes are unrelated to indexes).
 */
abstract class NotesPanelAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = NotesPanel.from(e) != null
    }

    protected fun panel(e: AnActionEvent): NotesPanel? = NotesPanel.from(e)
}

class NewNoteAction : NotesPanelAction() {
    override fun actionPerformed(e: AnActionEvent) {
        panel(e)?.createNote()
    }
}

class DeleteNoteAction : NotesPanelAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = panel(e)?.canDelete() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        panel(e)?.deleteContextual()
    }
}

class NextNoteAction : NotesPanelAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = panel(e)?.hasNotesForNav() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        panel(e)?.selectNext()
    }
}

class PrevNoteAction : NotesPanelAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = panel(e)?.hasNotesForNav() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        panel(e)?.selectPrevious()
    }
}

class SearchNotesAction : NotesPanelAction() {
    override fun actionPerformed(e: AnActionEvent) {
        panel(e)?.focusSearch()
    }
}

class ListNotesAction : NotesPanelAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = panel(e)?.isDetailView() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        panel(e)?.goToList()
    }
}

class MoreNotesAction : NotesPanelAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val p = panel(e) ?: return
        val anchor = (e.inputEvent?.component as? JComponent) ?: p
        p.showMoreMenu(anchor)
    }
}
