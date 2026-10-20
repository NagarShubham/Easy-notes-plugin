package com.snagar.easynotes.markdown

import com.snagar.easynotes.ui.PadColors
import java.awt.Font
import javax.swing.text.AttributeSet
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument

/**
 * Closed Markdown subset used as the on-disk form of a note body:
 *
 *  - `**bold**`, `*italic*`, `__underline__`, `~~strike~~`, `` `code` ``
 *  - `- ` / `* ` bullets, `1. ` numbered lists, `> ` quotes
 *  - fenced ` ``` ` code blocks
 *
 * Underline (`__…__`) is an Easy Notes extension; italic uses `*` only so the
 * two do not collide. Unmatched markers are left literal.
 */
internal object NoteMarkdown {

    const val BULLET_PREFIX = "• "
    const val QUOTE_PREFIX = "│ "
    const val CODE_ATTR = "easy-notes-code"
    const val CODE_BLOCK_ATTR = "easy-notes-code-block"
    const val CODE_LANG_ATTR = "easy-notes-code-lang"

    enum class BlockKind { PARAGRAPH, BULLET, NUMBERED, QUOTE, CODE }

    data class InlineSpan(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strike: Boolean = false,
        val code: Boolean = false,
    )

    data class Block(
        val kind: BlockKind,
        val number: Int = 0,
        val language: String = "",
        val spans: List<InlineSpan> = emptyList(),
    )

    private data class Style(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strike: Boolean = false,
        val code: Boolean = false,
    )

    fun parse(src: String): List<Block> {
        val blocks = parseBlocks(src)
        return unwrapAccidentalFullFence(blocks)
    }

    private fun parseBlocks(src: String): List<Block> {
        if (src.isEmpty()) return emptyList()
        val lines = src.replace("\r\n", "\n").split('\n')
        val blocks = ArrayList<Block>()
        var i = 0
        while (i < lines.size) {
            if (lines[i].startsWith("```")) {
                val language = lines[i].removePrefix("```").trim()
                i++
                val body = ArrayList<String>()
                while (i < lines.size && !lines[i].startsWith("```")) {
                    body += lines[i]
                    i++
                }
                if (i < lines.size) i++
                val text = body.joinToString("\n")
                blocks += Block(
                    kind = BlockKind.CODE,
                    language = language,
                    spans = listOf(InlineSpan(text, code = true)),
                )
            } else {
                blocks += parseLine(lines[i])
                i++
            }
        }
        return blocks
    }

    /**
     * A leaky code-block style used to persist the whole note as one empty-language
     * fence. If that fence clearly contains other markdown (lists, quotes),
     * unwrap it so the note opens as normal text again.
     */
    private fun unwrapAccidentalFullFence(blocks: List<Block>): List<Block> {
        if (blocks.size != 1) return blocks
        val only = blocks[0]
        if (only.kind != BlockKind.CODE || only.language.isNotBlank()) return blocks
        val inner = only.spans.joinToString("") { it.text }
        if (!inner.contains('\n')) return blocks
        val looksLikeProse = inner.lineSequence().any { line ->
            line.startsWith("- ") || line.startsWith("* ") || line.startsWith("> ") ||
                NUMBERED_PREFIX.containsMatchIn(line)
        }
        return if (looksLikeProse) parseBlocks(inner) else blocks
    }

    fun render(blocks: List<Block>): String {
        val sb = StringBuilder()
        var i = 0
        while (i < blocks.size) {
            if (sb.isNotEmpty()) sb.append('\n')
            if (blocks[i].kind == BlockKind.CODE) {
                val language = blocks[i].language
                val lines = ArrayList<String>()
                while (i < blocks.size && blocks[i].kind == BlockKind.CODE &&
                    (blocks[i].language.isBlank() || blocks[i].language == language)
                ) {
                    lines += blocks[i].spans.joinToString("") { it.text }
                    i++
                }
                sb.append("```")
                if (language.isNotBlank()) sb.append(language)
                sb.append('\n')
                sb.append(lines.joinToString("\n"))
                sb.append("\n```")
            } else {
                sb.append(renderBlock(blocks[i]))
                i++
            }
        }
        return sb.toString()
    }

    fun plainText(src: String): String =
        parse(src).joinToString("\n") { block ->
            block.spans.joinToString("") { it.text }
        }

    fun applyTo(doc: StyledDocument, src: String, base: AttributeSet) {
        if (doc.length > 0) doc.remove(0, doc.length)
        val blocks = parse(src)
        if (blocks.isEmpty()) return
        blocks.forEachIndexed { i, block ->
            if (i > 0) {
                doc.insertString(doc.length, "\n", base)
                if (blocks[i - 1].kind == BlockKind.CODE && block.kind != BlockKind.CODE) {
                    // A newline after a code paragraph copies CODE_BLOCK onto the new
                    // paragraph. Clear it before inserting following body text.
                    unmarkCodeBlockParagraph(doc, doc.length)
                }
            }
            when (block.kind) {
                BlockKind.CODE -> {
                    val text = block.spans.joinToString("") { it.text }
                    val start = doc.length
                    val codeBase = attributesFor(InlineSpan(text, code = true), base, codeBlock = true)
                    val lines = text.split('\n')
                    lines.forEachIndexed { li, line ->
                        if (li > 0) doc.insertString(doc.length, "\n", codeBase)
                        doc.insertString(doc.length, line, codeBase)
                    }
                    val end = doc.length
                    var markAt = start
                    while (markAt < end) {
                        markParagraph(doc, markAt, CODE_BLOCK_ATTR)
                        val nextEnd = doc.getParagraphElement(markAt.coerceIn(0, maxOf(0, doc.length - 1))).endOffset
                        if (nextEnd <= markAt) break
                        markAt = nextEnd
                    }
                    CodeHighlight.applyBlock(doc, start, end, block.language)
                }
                else -> {
                    val prefix = when (block.kind) {
                        BlockKind.BULLET -> BULLET_PREFIX
                        BlockKind.NUMBERED -> "${block.number}. "
                        BlockKind.QUOTE -> QUOTE_PREFIX
                        else -> ""
                    }
                    val start = doc.length
                    if (prefix.isNotEmpty()) doc.insertString(doc.length, prefix, base)
                    for (span in block.spans) {
                        doc.insertString(doc.length, span.text, attributesFor(span, base))
                    }
                    if (block.kind == BlockKind.QUOTE) markParagraph(doc, start, "easy-notes-quote")
                    if (i > 0 && blocks[i - 1].kind == BlockKind.CODE) {
                        unmarkCodeBlockParagraph(doc, start)
                    }
                }
            }
        }
    }

    fun fromDocument(doc: StyledDocument): String {
        if (doc.length == 0) return ""
        return render(blocksFromDocument(doc))
    }

    fun attributesFor(span: InlineSpan, base: AttributeSet, codeBlock: Boolean = false): AttributeSet {
        val a = SimpleAttributeSet(base)
        StyleConstants.setBold(a, span.bold)
        StyleConstants.setItalic(a, span.italic)
        StyleConstants.setUnderline(a, span.underline)
        StyleConstants.setStrikeThrough(a, span.strike)
        if (span.code || codeBlock) {
            a.addAttribute(CODE_ATTR, true)
            StyleConstants.setFontFamily(a, Font.MONOSPACED)
            StyleConstants.setForeground(a, CodeHighlight.FG)
            StyleConstants.setBackground(
                a,
                if (codeBlock) PadColors.transparent else CodeHighlight.INLINE_BG,
            )
        }
        return a
    }

    fun unmarkCodeBlockParagraph(doc: StyledDocument, offset: Int) {
        val a = SimpleAttributeSet()
        a.addAttribute(CODE_BLOCK_ATTR, false)
        a.addAttribute(CODE_LANG_ATTR, "")
        StyleConstants.setLeftIndent(a, 0f)
        StyleConstants.setRightIndent(a, 0f)
        StyleConstants.setSpaceAbove(a, 0f)
        StyleConstants.setSpaceBelow(a, 0f)
        val el = doc.getParagraphElement(offset.coerceIn(0, maxOf(0, doc.length)))
        setParagraphAttributesContained(doc, el.startOffset, 0, a, false)
    }

    fun markParagraph(doc: StyledDocument, offset: Int, key: String) {
        val a = SimpleAttributeSet()
        a.addAttribute(key, true)
        val el = doc.getParagraphElement(offset.coerceIn(0, maxOf(0, doc.length)))
        setParagraphAttributesContained(doc, el.startOffset, 0, a, false)
    }

    /**
     * Swing's [StyledDocument.setParagraphAttributes] walks `pos <= offset + length`.
     * Passing a paragraph's `endOffset - startOffset` therefore also restyles the
     * following paragraph. Length 0 (or a range that stops before the next start)
     * keeps the change inside the intended paragraphs.
     */
    fun setParagraphAttributesContained(
        doc: StyledDocument,
        start: Int,
        length: Int,
        attrs: AttributeSet,
        replace: Boolean = false,
    ) {
        if (doc.length == 0) {
            doc.setParagraphAttributes(0, 0, attrs, replace)
            return
        }
        val from = start.coerceIn(0, doc.length)
        val lastChar = if (length <= 0) {
            from
        } else {
            (start + length - 1).coerceIn(0, maxOf(0, doc.length - 1))
        }
        var pos = doc.getParagraphElement(from).startOffset
        val lastStart = doc.getParagraphElement(lastChar).startOffset
        while (true) {
            doc.setParagraphAttributes(pos, 0, attrs, replace)
            if (pos >= lastStart) break
            val el = doc.getParagraphElement(pos)
            if (el.endOffset >= doc.length) break
            val next = doc.getParagraphElement(el.endOffset)
            if (next.startOffset == el.startOffset) break
            pos = next.startOffset
        }
    }

    private fun parseLine(line: String): Block {
        when {
            line.startsWith("> ") || line == ">" -> {
                val rest = if (line.startsWith("> ")) line.substring(2) else ""
                return Block(BlockKind.QUOTE, spans = parseInline(rest))
            }
            line.startsWith("- ") || line.startsWith("* ") ->
                return Block(BlockKind.BULLET, spans = parseInline(line.substring(2)))
        }
        val numbered = NUMBERED_PREFIX.find(line)
        if (numbered != null) {
            val n = numbered.groupValues[1].toInt()
            return Block(BlockKind.NUMBERED, number = n, spans = parseInline(line.substring(numbered.value.length)))
        }
        return Block(BlockKind.PARAGRAPH, spans = parseInline(line))
    }

    private fun parseInline(text: String): List<InlineSpan> =
        merge(Parser(text).parse(Style()))

    private class Parser(val s: String) {
        var i = 0

        fun parse(style: Style): List<InlineSpan> {
            val out = ArrayList<InlineSpan>()
            val buf = StringBuilder()
            fun flush() {
                if (buf.isNotEmpty()) {
                    out += span(buf.toString(), style)
                    buf.clear()
                }
            }
            while (i < s.length) {
                when {
                    !style.code && s[i] == '`' -> {
                        val close = s.indexOf('`', i + 1)
                        if (close < 0) {
                            buf.append(s[i]); i++
                        } else {
                            flush()
                            out += span(s.substring(i + 1, close), style.copy(code = true))
                            i = close + 1
                        }
                    }
                    !style.strike && s.startsWith("~~", i) -> {
                        val close = findClose("~~", i + 2)
                        if (close < 0) {
                            buf.append(s[i]); i++
                        } else {
                            flush()
                            val inner = s.substring(i + 2, close)
                            out += Parser(inner).parse(style.copy(strike = true))
                            i = close + 2
                        }
                    }
                    !style.underline && s.startsWith("__", i) -> {
                        val close = findClose("__", i + 2)
                        if (close < 0) {
                            buf.append(s[i]); i++
                        } else {
                            flush()
                            val inner = s.substring(i + 2, close)
                            out += Parser(inner).parse(style.copy(underline = true))
                            i = close + 2
                        }
                    }
                    !style.bold && !style.italic && s.startsWith("***", i) -> {
                        val close = findClose("***", i + 3)
                        if (close < 0) {
                            buf.append(s[i]); i++
                        } else {
                            flush()
                            val inner = s.substring(i + 3, close)
                            out += Parser(inner).parse(style.copy(bold = true, italic = true))
                            i = close + 3
                        }
                    }
                    !style.bold && s.startsWith("**", i) -> {
                        val close = findClose("**", i + 2)
                        if (close < 0) {
                            buf.append(s[i]); i++
                        } else {
                            flush()
                            val inner = s.substring(i + 2, close)
                            out += Parser(inner).parse(style.copy(bold = true))
                            i = close + 2
                        }
                    }
                    !style.italic && s[i] == '*' && !s.startsWith("**", i) -> {
                        val close = findClose("*", i + 1)
                        if (close < 0) {
                            buf.append(s[i]); i++
                        } else {
                            flush()
                            val inner = s.substring(i + 1, close)
                            out += Parser(inner).parse(style.copy(italic = true))
                            i = close + 1
                        }
                    }
                    else -> {
                        buf.append(s[i]); i++
                    }
                }
            }
            flush()
            return out
        }

        private fun findClose(delim: String, from: Int): Int {
            var j = from
            while (j <= s.length - delim.length) {
                if (s[j] == '`') {
                    val end = s.indexOf('`', j + 1)
                    if (end < 0) return -1
                    j = end + 1
                    continue
                }
                if (delim == "*" && s.startsWith("**", j)) {
                    j += 2
                    continue
                }
                if (s.startsWith(delim, j)) return j
                j++
            }
            return -1
        }
    }

    private fun span(text: String, style: Style) = InlineSpan(
        text = text,
        bold = style.bold,
        italic = style.italic,
        underline = style.underline,
        strike = style.strike,
        code = style.code,
    )

    private fun merge(spans: List<InlineSpan>): List<InlineSpan> {
        if (spans.isEmpty()) return spans
        val out = ArrayList<InlineSpan>()
        for (span in spans) {
            val last = out.lastOrNull()
            if (last != null &&
                last.bold == span.bold &&
                last.italic == span.italic &&
                last.underline == span.underline &&
                last.strike == span.strike &&
                last.code == span.code
            ) {
                out[out.lastIndex] = last.copy(text = last.text + span.text)
            } else {
                out += span
            }
        }
        return out
    }

    private fun renderBlock(block: Block): String {
        val inner = renderSpans(block.spans)
        return when (block.kind) {
            BlockKind.BULLET -> "- $inner"
            BlockKind.NUMBERED -> "${block.number}. $inner"
            BlockKind.QUOTE -> if (inner.isEmpty()) ">" else "> $inner"
            BlockKind.PARAGRAPH -> inner
            BlockKind.CODE -> inner
        }
    }

    private fun renderSpans(spans: List<InlineSpan>): String = renderPlainSpans(spans)

    /**
     * Emits delimiters only when flags change so adjacent runs share wrappers.
     * Marker order (outer → inner): strike, underline, bold, italic, code.
     */
    private fun renderPlainSpans(spans: List<InlineSpan>): String {
        val sb = StringBuilder()
        val current = BooleanArray(5)
        val marks = arrayOf("~~", "__", "**", "*", "`")

        fun flagsOf(span: InlineSpan) = booleanArrayOf(
            span.strike, span.underline, span.bold, span.italic, span.code
        )

        fun sync(target: BooleanArray) {
            for (idx in 4 downTo 0) {
                if (current[idx] && !target[idx]) {
                    sb.append(marks[idx])
                    current[idx] = false
                }
            }
            for (idx in 0..4) {
                if (!current[idx] && target[idx]) {
                    for (j in 4 downTo idx + 1) {
                        if (current[j]) {
                            sb.append(marks[j])
                            current[j] = false
                        }
                    }
                    sb.append(marks[idx])
                    current[idx] = true
                }
            }
            for (idx in 0..4) {
                if (!current[idx] && target[idx]) {
                    sb.append(marks[idx])
                    current[idx] = true
                }
            }
        }

        for (span in spans) {
            sync(flagsOf(span))
            sb.append(span.text)
        }
        sync(BooleanArray(5))
        return sb.toString()
    }

    private fun blocksFromDocument(doc: StyledDocument): List<Block> {
        val full = doc.getText(0, doc.length)
        val lines = full.split('\n')
        val blocks = ArrayList<Block>(lines.size)
        var offset = 0
        for (line in lines) {
            val para = doc.getParagraphElement(offset.coerceAtMost(doc.length))
            val paraCode = para.attributes.getAttribute(CODE_BLOCK_ATTR) == true
            if (paraCode) {
                val lang = para.attributes.getAttribute(CODE_LANG_ATTR) as? String ?: ""
                blocks += Block(BlockKind.CODE, language = lang, spans = listOf(InlineSpan(line, code = true)))
            } else {
                val numbered = DOC_NUMBERED.find(line)
                val (kind, number, prefixLen) = when {
                    line.startsWith(QUOTE_PREFIX) -> Triple(BlockKind.QUOTE, 0, QUOTE_PREFIX.length)
                    line.startsWith(BULLET_PREFIX) -> Triple(BlockKind.BULLET, 0, BULLET_PREFIX.length)
                    numbered != null -> Triple(BlockKind.NUMBERED, numbered.groupValues[1].toInt(), numbered.value.length)
                    else -> Triple(BlockKind.PARAGRAPH, 0, 0)
                }
                val bodyStart = offset + prefixLen
                val bodyEnd = offset + line.length
                blocks += Block(kind, number, spans = spansFromDoc(doc, bodyStart, bodyEnd))
            }
            offset += line.length + 1
        }
        return blocks
    }

    private fun spansFromDoc(doc: StyledDocument, start: Int, end: Int): List<InlineSpan> {
        if (start >= end) return emptyList()
        val spans = ArrayList<InlineSpan>()
        var i = start
        while (i < end) {
            val el = doc.getCharacterElement(i)
            val runEnd = minOf(el.endOffset, end)
            val a = el.attributes
            val text = doc.getText(i, runEnd - i)
            spans += InlineSpan(
                text = text,
                bold = StyleConstants.isBold(a),
                italic = StyleConstants.isItalic(a),
                underline = StyleConstants.isUnderline(a),
                strike = StyleConstants.isStrikeThrough(a),
                code = a.getAttribute(CODE_ATTR) == true && a.getAttribute(CODE_BLOCK_ATTR) != true,
            )
            i = runEnd
        }
        return merge(spans)
    }

    private val NUMBERED_PREFIX = Regex("^(\\d+)\\. ")
    private val DOC_NUMBERED = Regex("^(\\d+)\\. ")
}
