package com.snagar.easynotes.ui

import com.intellij.ide.CopyProvider
import com.intellij.ide.CutProvider
import com.intellij.ide.PasteProvider
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.ide.CopyPasteManager
import com.snagar.easynotes.markdown.CodeHighlight
import com.snagar.easynotes.markdown.NoteMarkdown
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JTextPane
import javax.swing.SwingUtilities
import javax.swing.TransferHandler
import java.awt.geom.RoundRectangle2D
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.AttributeSet
import javax.swing.text.BadLocationException
import javax.swing.text.DefaultCaret
import javax.swing.text.DefaultHighlighter
import javax.swing.text.MutableAttributeSet
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyleContext
import javax.swing.text.StyledDocument

/**
 * A styled text pane painted as a yellow ruled legal pad, used as the note body
 * editor. Formatting is stored in the [StyledDocument] and persisted as the
 * closed Markdown subset in [NoteMarkdown].
 */
class RuledTextPane : JTextPane(), PasteProvider, CopyProvider, CutProvider, UiDataProvider {

    var paperColor: Color = PadColors.defaultPaper
        set(value) {
            field = PadColors.ensureReadablePaper(value)
            repaint()
        }

    var emptyText: String = ""

    /** Selection captured before the format toolbar takes focus. */
    private var rememberedStart = 0
    private var rememberedEnd = 0

    init {
        isOpaque = false
        editorKit = javax.swing.text.StyledEditorKit()
        // IntelliJ's LaF otherwise paints the component Font and ignores Bold/Italic/etc.
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, false)
        caret = object : DefaultCaret() {
            override fun getSelectionPainter() =
                DefaultHighlighter.DefaultHighlightPainter(PadColors.selectionOn(paperColor))
        }.also { it.blinkRate = 500 }
        applyInk()
        // StyledEditorKit otherwise pastes HTML/RTF, which restyles the whole
        // note (often as one wrapped paragraph or a leaked code block).
        transferHandler = PlainTextHandler()
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown && !e.isControlDown && !e.isMetaDown) {
                    if (continueOrExitList()) e.consume()
                }
            }
        })
        addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                rememberedStart = selectionStart
                rememberedEnd = selectionEnd
            }

            override fun focusGained(e: FocusEvent) {
                rememberedStart = selectionStart
                rememberedEnd = selectionEnd
            }
        })
        addCaretListener {
            if (hasFocus()) {
                rememberedStart = selectionStart
                rememberedEnd = selectionEnd
            }
        }
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = scheduleCodeRestyle(e.offset)
            override fun removeUpdate(e: DocumentEvent) = scheduleCodeRestyle(e.offset)
            override fun changedUpdate(e: DocumentEvent) = Unit
        })
    }

    private var restylingCode = false
    private var codeRestyleQueued = false
    /** Offset to restyle, or -1 to restyle every code block. */
    private var restyleOffset = -1

    private fun scheduleCodeRestyle(offset: Int = -1) {
        if (restylingCode) return
        if (codeRestyleQueued) {
            // Two edits before the pass runs: restyle everything so neither block is missed.
            if (restyleOffset != offset) restyleOffset = -1
            return
        }
        codeRestyleQueued = true
        restyleOffset = offset
        SwingUtilities.invokeLater {
            codeRestyleQueued = false
            val at = restyleOffset
            restyleOffset = -1
            restyleCode(at)
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun uiDataSnapshot(sink: DataSink) {
        sink[PlatformDataKeys.PASTE_PROVIDER] = this
        sink[PlatformDataKeys.COPY_PROVIDER] = this
        sink[PlatformDataKeys.CUT_PROVIDER] = this
    }

    override fun paste() {
        insertPlainText(clipboardText() ?: return)
    }

    override fun copy() {
        selectedText?.let { CopyPasteManager.getInstance().setContents(StringSelection(it)) }
    }

    override fun cut() {
        copy()
        replaceSelection("")
    }

    override fun performPaste(dataContext: DataContext) = paste()
    override fun isPastePossible(dataContext: DataContext) = isEnabled && isEditable
    override fun isPasteEnabled(dataContext: DataContext) = isPastePossible(dataContext)

    override fun performCopy(dataContext: DataContext) = copy()
    override fun isCopyEnabled(dataContext: DataContext) = isEnabled && !selectedText.isNullOrEmpty()
    override fun isCopyVisible(dataContext: DataContext) = true

    override fun performCut(dataContext: DataContext) = cut()
    override fun isCutEnabled(dataContext: DataContext) = isCopyEnabled(dataContext) && isEditable
    override fun isCutVisible(dataContext: DataContext) = true

    private fun clipboardText(): String? =
        CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor)

    private fun insertPlainText(raw: String) {
        if (!isEditable || !isEnabled) return
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
        if (text.isEmpty()) return
        val doc = styledDocument
        val start = selectionStart.coerceIn(0, doc.length)
        val end = selectionEnd.coerceIn(0, doc.length)
        try {
            if (end > start) doc.remove(start, end - start)
            doc.insertString(start, text, defaultBase())
            caretPosition = start + text.length
        } catch (_: BadLocationException) {
            return
        }
    }

    /** Drops and Swing paste: string flavor only, never HTML/RTF. */
    private inner class PlainTextHandler : TransferHandler() {
        override fun getSourceActions(c: JComponent) = COPY_OR_MOVE

        override fun createTransferable(c: JComponent): Transferable? =
            selectedText?.let { StringSelection(it) }

        override fun exportDone(source: JComponent?, data: Transferable?, action: Int) {
            if (action == MOVE) replaceSelection("")
        }

        override fun canImport(support: TransferSupport) =
            support.isDataFlavorSupported(DataFlavor.stringFlavor)

        override fun importData(comp: JComponent, t: Transferable): Boolean {
            val text = try {
                t.getTransferData(DataFlavor.stringFlavor) as? String
            } catch (_: Exception) {
                null
            } ?: return false
            insertPlainText(text)
            return true
        }

        override fun importData(support: TransferSupport): Boolean {
            val comp = support.component as? JComponent ?: return false
            return importData(comp, support.transferable)
        }
    }

    override fun getForeground(): Color = PadColors.inkOn(paperColor)

    override fun getDisabledTextColor(): Color = PadColors.inkOn(paperColor)

    override fun getCaretColor(): Color = PadColors.inkOn(paperColor)

    override fun getSelectionColor(): Color = PadColors.selectionOn(paperColor)

    override fun getSelectedTextColor(): Color = PadColors.inkOn(paperColor)

    fun toMarkdown(): String = NoteMarkdown.fromDocument(styledDocument)

    fun setMarkdown(src: String, base: AttributeSet = defaultBase()) {
        restylingCode = true
        try {
            NoteMarkdown.applyTo(styledDocument, src, base)
        } finally {
            restylingCode = false
        }
        caretPosition = 0
        // applyTo already tokenizes each code block. A second pass here only
        // duplicated that work on every note open.
    }

    fun applyBaseFont(family: String, size: Int) {
        font = Font(family, Font.PLAIN, size)
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, false)
        val doc = styledDocument
        val def = doc.getStyle(StyleContext.DEFAULT_STYLE)
        if (def != null) {
            StyleConstants.setFontFamily(def, family)
            StyleConstants.setFontSize(def, size)
            StyleConstants.setBold(def, false)
            StyleConstants.setItalic(def, false)
        }
        var i = 0
        while (i < doc.length) {
            val el = doc.getCharacterElement(i)
            val end = el.endOffset
            val a = el.attributes
            val patch = SimpleAttributeSet()
            StyleConstants.setFontSize(patch, size)
            val code = a.getAttribute(NoteMarkdown.CODE_ATTR) == true ||
                a.getAttribute(NoteMarkdown.CODE_BLOCK_ATTR) == true
            StyleConstants.setFontFamily(patch, if (code) Font.MONOSPACED else family)
            doc.setCharacterAttributes(i, end - i, patch, false)
            i = end
        }
        (inputAttributes as MutableAttributeSet).let { input ->
            StyleConstants.setFontFamily(input, family)
            StyleConstants.setFontSize(input, size)
            StyleConstants.setForeground(input, PadColors.inkOn(paperColor))
        }
        applyInk()
    }

    /**
     * Legal-pad ink is always dark, including in a dark IDE theme. Without this,
     * JTextPane bakes the theme's light-gray foreground into every character.
     */
    fun applyInk() {
        val ink = PadColors.inkOn(paperColor)
        foreground = ink
        caretColor = ink
        disabledTextColor = ink
        selectionColor = PadColors.selectionOn(paperColor)
        selectedTextColor = ink
        val def = styledDocument.getStyle(StyleContext.DEFAULT_STYLE)
        if (def != null) {
            StyleConstants.setForeground(def, ink)
        }
        (inputAttributes as MutableAttributeSet).let { StyleConstants.setForeground(it, ink) }
        val doc = styledDocument
        var i = 0
        while (i < doc.length) {
            val el = doc.getCharacterElement(i)
            val end = el.endOffset
            val attrs = el.attributes
            val isCode = attrs.getAttribute(NoteMarkdown.CODE_ATTR) == true ||
                attrs.getAttribute(NoteMarkdown.CODE_BLOCK_ATTR) == true
            if (!isCode) {
                val patch = SimpleAttributeSet()
                StyleConstants.setForeground(patch, PadColors.inkOn(paperColor))
                doc.setCharacterAttributes(i, end - i, patch, false)
            }
            i = end
        }
        restyleCode()
        repaint()
    }

    /**
     * Re-tokenizes code blocks. [around] limits the pass to the contiguous code
     * region containing that offset; `-1` restyles every code block (load, color,
     * font). Typing only retokenizes the block being edited.
     */
    fun restyleCode(around: Int = -1) {
        if (restylingCode) return
        restylingCode = true
        try {
            val doc = styledDocument
            if (doc.length == 0) return
            if (around >= 0) {
                val at = around.coerceIn(0, doc.length - 1)
                if (isCodeBlockParagraph(at)) restyleCodeRegion(doc, at)
            } else {
                var i = 0
                while (i < doc.length) {
                    if (!isCodeBlockParagraph(i)) {
                        i = doc.getParagraphElement(i).endOffset
                        continue
                    }
                    i = restyleCodeRegion(doc, i)
                }
            }
        } finally {
            restylingCode = false
        }
        repaint()
    }

    /** Styles the contiguous code block containing [offset] and returns its end offset. */
    private fun restyleCodeRegion(doc: javax.swing.text.StyledDocument, offset: Int): Int {
        val anchor = doc.getParagraphElement(offset)
        var start = anchor.startOffset
        var end = anchor.endOffset
        val lang = (anchor.attributes.getAttribute(NoteMarkdown.CODE_LANG_ATTR) as? String) ?: ""
        while (start > 0) {
            val prev = doc.getParagraphElement(start - 1)
            if (prev.endOffset == start && isCodeBlockParagraph(prev.startOffset)) {
                start = prev.startOffset
            } else break
        }
        while (end < doc.length) {
            val next = doc.getParagraphElement(end)
            if (next.startOffset == end && isCodeBlockParagraph(next.startOffset)) {
                end = next.endOffset
            } else break
        }
        CodeHighlight.applyBlock(doc, start, end.coerceAtMost(doc.length), lang)
        return end
    }

    fun defaultBase(): AttributeSet {
        val a = SimpleAttributeSet()
        StyleConstants.setFontFamily(a, font.family)
        StyleConstants.setFontSize(a, font.size)
        StyleConstants.setForeground(a, PadColors.inkOn(paperColor))
        return a
    }

    fun toggleInline(kind: InlineKind, on: Boolean? = null) {
        restoreFormatSelection()
        val doc = styledDocument
        val range = formatRange()
        val start = range.first
        val len = range.second - start
        val enable = on ?: !selectionHas(kind)
        val patch = SimpleAttributeSet()
        applyKind(patch, kind, enable)
        if (len <= 0) {
            applyKind(inputAttributes as MutableAttributeSet, kind, enable)
        } else if (kind == InlineKind.CODE) {
            applyCodeRange(doc, start, len, enable)
        } else {
            doc.setCharacterAttributes(start, len, patch, false)
        }
        select(start, start + len.coerceAtLeast(0))
        repaint()
    }

    fun selectionHas(kind: InlineKind): Boolean {
        val doc = styledDocument
        if (doc.length == 0) return flagOf(inputAttributes, kind)
        val start = selectionStart
        val end = selectionEnd
        if (start == end) {
            val i = (start - 1).coerceAtLeast(0)
            return flagOf(doc.getCharacterElement(i).attributes, kind)
        }
        var i = start
        while (i < end) {
            val el = doc.getCharacterElement(i)
            if (!flagOf(el.attributes, kind)) return false
            i = el.endOffset
        }
        return true
    }

    fun toggleBulletList() = setBulletList(!selectionInBulletList())

    fun toggleNumberedList() = setNumberedList(!selectionInNumberedList())

    fun setBulletList(on: Boolean) {
        if (selectionInBulletList() == on) return
        toggleList(bullet = true)
    }

    fun setNumberedList(on: Boolean) {
        if (selectionInNumberedList() == on) return
        toggleList(bullet = false)
    }

    fun selectionInBulletList(): Boolean {
        val paras = paragraphsInSelection()
        return paras.isNotEmpty() && paras.all { it.text.startsWith(NoteMarkdown.BULLET_PREFIX) }
    }

    fun selectionInNumberedList(): Boolean {
        val paras = paragraphsInSelection()
        return paras.isNotEmpty() && paras.all { DOC_NUMBERED.containsMatchIn(it.text) }
    }

    fun selectionInQuote(): Boolean {
        val paras = paragraphsInSelection()
        return paras.isNotEmpty() && paras.all { it.text.startsWith(NoteMarkdown.QUOTE_PREFIX) }
    }

    fun selectionInCodeBlock(): Boolean {
        val paras = paragraphsInSelection()
        return paras.isNotEmpty() && paras.all { isCodeBlockParagraph(it.start) }
    }

    fun restoreFormatSelection() {
        if (selectionStart != selectionEnd) return
        if (rememberedStart != rememberedEnd) {
            select(
                rememberedStart.coerceIn(0, document.length),
                rememberedEnd.coerceIn(0, document.length),
            )
            return
        }
        val word = currentWordRange()
        if (word.second > word.first) select(word.first, word.second)
    }

    private fun formatRange(): Pair<Int, Int> {
        if (selectionStart != selectionEnd) return selectionStart to selectionEnd
        if (rememberedStart != rememberedEnd) {
            return rememberedStart.coerceIn(0, document.length) to rememberedEnd.coerceIn(0, document.length)
        }
        return currentWordRange()
    }

    private fun currentWordRange(): Pair<Int, Int> {
        val doc = styledDocument
        if (doc.length == 0) return 0 to 0
        val text = doc.getText(0, doc.length)
        var s = caretPosition.coerceIn(0, text.length)
        var e = s
        fun isWord(c: Char) = c.isLetterOrDigit() || c == '_'
        while (s > 0 && isWord(text[s - 1])) s--
        while (e < text.length && isWord(text[e])) e++
        return s to e
    }

    fun setQuote(on: Boolean) {
        if (selectionInQuote() == on) return
        toggleQuote()
    }

    fun setCodeBlock(on: Boolean) {
        if (selectionInCodeBlock() == on) return
        toggleCodeBlock()
    }

    fun toggleQuote() {
        restoreFormatSelection()
        val paras = paragraphsInSelection()
        if (paras.isEmpty()) return
        val allOn = paras.all { it.text.startsWith(NoteMarkdown.QUOTE_PREFIX) }
        val doc = styledDocument
        for (para in paras.asReversed()) {
            clearCodeBlock(para)
            val text = paragraphAt(para.start)?.text ?: continue
            val stripped = stripBlockPrefix(text)
            val prefixLen = text.length - stripped.length
            if (prefixLen > 0) doc.remove(para.start, prefixLen)
            if (!allOn) {
                doc.insertString(para.start, NoteMarkdown.QUOTE_PREFIX, defaultBase())
            }
        }
    }

    fun toggleCodeBlock() {
        restoreFormatSelection()
        val paras = paragraphsInSelection()
        if (paras.isEmpty()) return
        val allOn = paras.all { isCodeBlockParagraph(it.start) }
        val doc = styledDocument
        for (para in paras.asReversed()) {
            val live = paragraphAt(para.start) ?: continue
            val stripped = stripBlockPrefix(live.text)
            val prefixLen = live.text.length - stripped.length
            if (prefixLen > 0) doc.remove(live.start, prefixLen)
            val start = live.start
            val end = (paragraphAt(start)?.end ?: start)
            val len = (end - start).coerceAtLeast(1)
            val patch = SimpleAttributeSet()
            if (allOn) {
                patch.addAttribute(NoteMarkdown.CODE_BLOCK_ATTR, false)
                patch.addAttribute(NoteMarkdown.CODE_ATTR, false)
                StyleConstants.setFontFamily(patch, font.family)
                StyleConstants.setForeground(patch, PadColors.inkOn(paperColor))
                StyleConstants.setBackground(patch, PadColors.transparent)
                doc.setCharacterAttributes(start, len, patch, false)
                val paraPatch = SimpleAttributeSet()
                paraPatch.addAttribute(NoteMarkdown.CODE_BLOCK_ATTR, false)
                paraPatch.addAttribute(NoteMarkdown.CODE_LANG_ATTR, "")
                StyleConstants.setLeftIndent(paraPatch, 0f)
                StyleConstants.setRightIndent(paraPatch, 0f)
                StyleConstants.setSpaceAbove(paraPatch, 0f)
                StyleConstants.setSpaceBelow(paraPatch, 0f)
                NoteMarkdown.setParagraphAttributesContained(doc, start, 0, paraPatch, false)
            } else {
                NoteMarkdown.markParagraph(doc, start, NoteMarkdown.CODE_BLOCK_ATTR)
            }
        }
        if (!allOn) {
            val text = paras.joinToString("\n") { stripBlockPrefix(it.text) }
            val lang = CodeHighlight.detectLanguage(text)
            if (lang.isNotBlank()) {
                val first = paragraphAt(paras.first().start)
                val last = paragraphAt(paras.last().start)
                if (first != null && last != null) {
                    val a = SimpleAttributeSet()
                    a.addAttribute(NoteMarkdown.CODE_LANG_ATTR, lang)
                    NoteMarkdown.setParagraphAttributesContained(
                        doc,
                        first.start,
                        (last.end - first.start).coerceAtLeast(0),
                        a,
                        false,
                    )
                }
            }
            restyleCode()
        }
    }

    private fun toggleList(bullet: Boolean) {
        restoreFormatSelection()
        val paras = paragraphsInSelection()
        if (paras.isEmpty()) return
        val allOn = if (bullet) paras.all { it.text.startsWith(NoteMarkdown.BULLET_PREFIX) }
        else paras.all { DOC_NUMBERED.containsMatchIn(it.text) }
        val doc = styledDocument
        var indexFromEnd = 0
        for (para in paras.asReversed()) {
            clearCodeBlock(para)
            val text = paragraphAt(para.start)?.text ?: para.text
            val stripped = stripListPrefix(text)
            val prefixLen = text.length - stripped.length
            if (prefixLen > 0) doc.remove(para.start, prefixLen)
            if (!allOn) {
                val prefix = if (bullet) {
                    NoteMarkdown.BULLET_PREFIX
                } else {
                    "${paras.size - indexFromEnd}. "
                }
                doc.insertString(para.start, prefix, defaultBase())
            }
            indexFromEnd++
        }
    }

    private fun continueOrExitList(): Boolean {
        val para = paragraphAt(caretPosition) ?: return false
        val text = para.text
        val bullet = text.startsWith(NoteMarkdown.BULLET_PREFIX)
        val quote = text.startsWith(NoteMarkdown.QUOTE_PREFIX)
        val numbered = DOC_NUMBERED.find(text)
        if (!bullet && !quote && numbered == null) return false

        val prefix = when {
            bullet -> NoteMarkdown.BULLET_PREFIX
            quote -> NoteMarkdown.QUOTE_PREFIX
            else -> numbered?.value ?: return false
        }
        val body = text.drop(prefix.length)
        val atEnd = caretPosition >= para.end
        if (!atEnd) return false

        val doc = styledDocument
        if (body.isEmpty()) {
            replaceParagraph(para.start, para.end, "")
            caretPosition = para.start
            return true
        }
        val insert = when {
            bullet -> "\n" + NoteMarkdown.BULLET_PREFIX
            quote -> "\n" + NoteMarkdown.QUOTE_PREFIX
            else -> {
                val num = (numbered?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0) + 1
                "\n$num. "
            }
        }
        val at = selectionStart
        doc.insertString(at, insert, defaultBase())
        caretPosition = at + insert.length
        return true
    }

    private fun applyCodeRange(doc: StyledDocument, start: Int, len: Int, on: Boolean) {
        CodeHighlight.applyInline(doc, start, len, on)
        if (!on) {
            val a = SimpleAttributeSet()
            StyleConstants.setFontFamily(a, font.family)
            StyleConstants.setForeground(a, PadColors.inkOn(paperColor))
            StyleConstants.setBackground(a, PadColors.transparent)
            doc.setCharacterAttributes(start, len, a, false)
        }
    }

    private fun applyKind(a: MutableAttributeSet, kind: InlineKind, on: Boolean) {
        when (kind) {
            InlineKind.BOLD -> StyleConstants.setBold(a, on)
            InlineKind.ITALIC -> StyleConstants.setItalic(a, on)
            InlineKind.UNDERLINE -> StyleConstants.setUnderline(a, on)
            InlineKind.STRIKE -> StyleConstants.setStrikeThrough(a, on)
            InlineKind.CODE -> {
                if (on) {
                    a.addAttribute(NoteMarkdown.CODE_ATTR, true)
                    StyleConstants.setFontFamily(a, Font.MONOSPACED)
                    StyleConstants.setForeground(a, CodeHighlight.FG)
                    StyleConstants.setBackground(a, CodeHighlight.INLINE_BG)
                } else {
                    a.addAttribute(NoteMarkdown.CODE_ATTR, false)
                    StyleConstants.setFontFamily(a, font.family)
                    StyleConstants.setForeground(a, PadColors.inkOn(paperColor))
                    StyleConstants.setBackground(a, PadColors.transparent)
                }
            }
        }
    }

    private fun flagOf(a: AttributeSet, kind: InlineKind): Boolean = when (kind) {
        InlineKind.BOLD -> StyleConstants.isBold(a)
        InlineKind.ITALIC -> StyleConstants.isItalic(a)
        InlineKind.UNDERLINE -> StyleConstants.isUnderline(a)
        InlineKind.STRIKE -> StyleConstants.isStrikeThrough(a)
        InlineKind.CODE -> a.getAttribute(NoteMarkdown.CODE_ATTR) == true
    }

    private data class Para(val start: Int, val end: Int, val text: String)

    private fun paragraphsInSelection(): List<Para> {
        val doc = styledDocument
        if (doc.length == 0) return emptyList()
        val from = selectionStart
        val to = maxOf(selectionEnd, from)
        val first = doc.getParagraphElement(from)
        val last = doc.getParagraphElement((to - 1).coerceAtLeast(from))
        val out = ArrayList<Para>()
        var el = first
        while (true) {
            val start = el.startOffset
            val end = el.endOffset
            val raw = doc.getText(start, end - start)
            val text = raw.trimEnd('\n')
            out += Para(start, start + text.length, text)
            if (el.endOffset >= last.endOffset) break
            el = doc.getParagraphElement(el.endOffset)
        }
        return out
    }

    private fun paragraphAt(offset: Int): Para? {
        val doc = styledDocument
        if (doc.length == 0) return null
        val el = doc.getParagraphElement(offset.coerceIn(0, doc.length))
        val start = el.startOffset
        val raw = doc.getText(start, el.endOffset - start)
        val text = raw.trimEnd('\n')
        return Para(start, start + text.length, text)
    }

    private fun stripListPrefix(text: String): String = stripBlockPrefix(text)

    private fun stripBlockPrefix(text: String): String {
        if (text.startsWith(NoteMarkdown.BULLET_PREFIX)) return text.drop(NoteMarkdown.BULLET_PREFIX.length)
        if (text.startsWith(NoteMarkdown.QUOTE_PREFIX)) return text.drop(NoteMarkdown.QUOTE_PREFIX.length)
        val m = DOC_NUMBERED.find(text)
        return if (m != null) text.drop(m.value.length) else text
    }

    private fun isCodeBlockParagraph(offset: Int): Boolean {
        val doc = styledDocument
        if (doc.length == 0) return false
        val el = doc.getParagraphElement(offset.coerceIn(0, maxOf(0, doc.length - 1)))
        return el.attributes.getAttribute(NoteMarkdown.CODE_BLOCK_ATTR) == true
    }

    private fun clearCodeBlock(para: Para) {
        if (!isCodeBlockParagraph(para.start)) return
        val patch = SimpleAttributeSet()
        patch.addAttribute(NoteMarkdown.CODE_BLOCK_ATTR, false)
        patch.addAttribute(NoteMarkdown.CODE_ATTR, false)
        StyleConstants.setFontFamily(patch, font.family)
        val len = (para.end - para.start).coerceAtLeast(1)
        styledDocument.setCharacterAttributes(para.start, len, patch, false)
        val paraPatch = SimpleAttributeSet(patch)
        StyleConstants.setLeftIndent(paraPatch, 0f)
        StyleConstants.setRightIndent(paraPatch, 0f)
        StyleConstants.setSpaceAbove(paraPatch, 0f)
        StyleConstants.setSpaceBelow(paraPatch, 0f)
        NoteMarkdown.setParagraphAttributesContained(styledDocument, para.start, 0, paraPatch, false)
    }

    private fun replaceParagraph(start: Int, end: Int, next: String) {
        val doc = styledDocument
        val len = end - start
        if (len > 0) doc.remove(start, len)
        doc.insertString(start, next, defaultBase())
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = paperColor
            g2.fillRect(0, 0, width, height)
            paintCodeBlockChrome(g2)

            val fm = getFontMetrics(font)
            val lineHeight = fm.height
            if (lineHeight > 0) {
                g2.color = PadColors.rule
                var y = insets.top + fm.ascent + fm.descent
                while (y < height) {
                    g2.drawLine(0, y, width, y)
                    y += lineHeight
                }
            }

            val marginX = insets.left - 6
            if (marginX > 2) {
                g2.color = PadColors.margin
                g2.drawLine(marginX, 0, marginX, height)
            }

            if (document.length == 0 && emptyText.isNotEmpty() && !hasFocus()) {
                g2.color = PadColors.mutedInk
                g2.font = font
                g2.drawString(emptyText, insets.left, insets.top + fm.ascent)
            }
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }

    private fun paintCodeBlockChrome(g2: Graphics2D) {
        val doc = styledDocument
        if (doc.length == 0) return
        var i = 0
        while (i < doc.length) {
            if (!isCodeBlockParagraph(i)) {
                i = doc.getParagraphElement(i).endOffset
                continue
            }
            val start = doc.getParagraphElement(i).startOffset
            var end = doc.getParagraphElement(i).endOffset
            while (end < doc.length) {
                val next = doc.getParagraphElement(end)
                if (next.startOffset == end && isCodeBlockParagraph(next.startOffset)) {
                    end = next.endOffset
                } else {
                    break
                }
            }
            try {
                val a = modelToView2D(start)
                if (a == null) {
                    i = end
                    continue
                }
                val last = lastVisibleCodeOffset(start, end)
                val b = modelToView2D(last) ?: a
                val padV = PadColors.CODE_PAD_V.toDouble()
                val x = (insets.left - 4).toDouble().coerceAtLeast(2.0)
                val y = a.y - padV
                val w = (width - insets.right - x - 4).coerceAtLeast(8.0)
                val h = (b.y + b.height - a.y) + padV * 2
                val box = RoundRectangle2D.Double(x, y, w, h, 8.0, 8.0)
                g2.color = CodeHighlight.BLOCK_BG
                g2.fill(box)
                g2.color = CodeHighlight.BLOCK_BORDER
                g2.draw(box)
                g2.color = CodeHighlight.BLOCK_ACCENT
                g2.fillRect(x.toInt() + 1, y.toInt() + 4, 3, h.toInt() - 8)
            } catch (_: BadLocationException) {
                // layout not ready
            }
            i = end
        }
    }

    /**
     * Paragraph [endOffset] is past the trailing newline, whose view is a blank
     * extra line. Use the last real character so the chrome hugs the text.
     */
    private fun lastVisibleCodeOffset(start: Int, end: Int): Int {
        val doc = styledDocument
        var o = (end - 1).coerceAtLeast(start)
        while (o > start) {
            val ch = try {
                doc.getText(o, 1)
            } catch (_: BadLocationException) {
                break
            }
            if (ch.isNotEmpty() && ch[0] != '\n') return o
            o--
        }
        return o
    }

    enum class InlineKind { BOLD, ITALIC, UNDERLINE, STRIKE, CODE }

    companion object {
        val INK: Color get() = PadColors.ink
        val SELECTION_BG: Color get() = PadColors.selection

        private val DOC_NUMBERED = Regex("^(\\d+)\\. ")
    }
}
