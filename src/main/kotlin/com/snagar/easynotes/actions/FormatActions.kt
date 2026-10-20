package com.snagar.easynotes.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import com.snagar.easynotes.ui.NotesPanel
import com.snagar.easynotes.ui.RuledTextPane

abstract class FormatToggleAction : ToggleAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabled = NotesPanel.from(e)?.isDetailView() == true
    }

    protected fun panel(e: AnActionEvent): NotesPanel? = NotesPanel.from(e)
}

class BoldFormatAction : FormatToggleAction() {
    override fun isSelected(e: AnActionEvent): Boolean =
        panel(e)?.selectionHas(RuledTextPane.InlineKind.BOLD) == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        panel(e)?.toggleInline(RuledTextPane.InlineKind.BOLD, state)
    }
}

class ItalicFormatAction : FormatToggleAction() {
    override fun isSelected(e: AnActionEvent): Boolean =
        panel(e)?.selectionHas(RuledTextPane.InlineKind.ITALIC) == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        panel(e)?.toggleInline(RuledTextPane.InlineKind.ITALIC, state)
    }
}

class UnderlineFormatAction : FormatToggleAction() {
    override fun isSelected(e: AnActionEvent): Boolean =
        panel(e)?.selectionHas(RuledTextPane.InlineKind.UNDERLINE) == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        panel(e)?.toggleInline(RuledTextPane.InlineKind.UNDERLINE, state)
    }
}

class StrikeFormatAction : FormatToggleAction() {
    override fun isSelected(e: AnActionEvent): Boolean =
        panel(e)?.selectionHas(RuledTextPane.InlineKind.STRIKE) == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        panel(e)?.toggleInline(RuledTextPane.InlineKind.STRIKE, state)
    }
}

class CodeFormatAction : FormatToggleAction() {
    override fun isSelected(e: AnActionEvent): Boolean =
        panel(e)?.selectionHas(RuledTextPane.InlineKind.CODE) == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        panel(e)?.toggleInline(RuledTextPane.InlineKind.CODE, state)
    }
}

class BulletListFormatAction : FormatToggleAction() {
    override fun isSelected(e: AnActionEvent): Boolean =
        panel(e)?.selectionInBulletList() == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        panel(e)?.setBulletList(state)
    }
}

class NumberedListFormatAction : FormatToggleAction() {
    override fun isSelected(e: AnActionEvent): Boolean =
        panel(e)?.selectionInNumberedList() == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        panel(e)?.setNumberedList(state)
    }
}

class QuoteFormatAction : FormatToggleAction() {
    override fun isSelected(e: AnActionEvent): Boolean =
        panel(e)?.selectionInQuote() == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        panel(e)?.setQuote(state)
    }
}

class CodeBlockFormatAction : FormatToggleAction() {
    override fun isSelected(e: AnActionEvent): Boolean =
        panel(e)?.selectionInCodeBlock() == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        panel(e)?.setCodeBlock(state)
    }
}
