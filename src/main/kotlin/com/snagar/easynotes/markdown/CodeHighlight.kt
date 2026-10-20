package com.snagar.easynotes.markdown

import com.snagar.easynotes.ui.PadColors
import java.awt.Color
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument

/**
 * Pad-safe syntax colors and a small tokenizer for fenced code.
 * Colors come from [PadColors] so tokens stay readable on the legal pad.
 */
internal object CodeHighlight {

    val INLINE_BG: Color get() = PadColors.inlineCodeBg
    val BLOCK_BG: Color get() = PadColors.blockCodeBg
    val BLOCK_BORDER: Color get() = PadColors.blockBorder
    val BLOCK_ACCENT: Color get() = PadColors.blockAccent

    val FG: Color get() = PadColors.ink
    val KEYWORD: Color get() = PadColors.keyword
    val STRING: Color get() = PadColors.string
    val COMMENT: Color get() = PadColors.comment
    val NUMBER: Color get() = PadColors.number
    val TYPE: Color get() = PadColors.type

    enum class Kind { PLAIN, KEYWORD, STRING, COMMENT, NUMBER, TYPE }

    data class Token(val start: Int, val end: Int, val kind: Kind)

    fun color(kind: Kind): Color = when (kind) {
        Kind.PLAIN -> FG
        Kind.KEYWORD -> KEYWORD
        Kind.STRING -> STRING
        Kind.COMMENT -> COMMENT
        Kind.NUMBER -> NUMBER
        Kind.TYPE -> TYPE
    }

    fun detectLanguage(text: String): String {
        val t = text.trim()
        return when {
            t.contains("fun ") || t.contains("val ") || t.contains("kotlin") -> "kotlin"
            t.contains("public class") || t.contains("System.out") -> "java"
            t.startsWith("{") || t.startsWith("[") -> "json"
            t.contains("def ") || t.contains("import ") && t.contains(":") -> "python"
            t.contains("function ") || t.contains("const ") || t.contains("=>") -> "javascript"
            t.contains("SELECT ") || t.contains("FROM ") -> "sql"
            else -> ""
        }
    }

    fun tokenize(text: String, language: String): List<Token> {
        val lang = language.trim().lowercase()
        val hashComments = lang in HASH_COMMENT_LANGS
        val keywords = keywordsFor(lang)
        val tokens = ArrayList<Token>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '/' && i + 1 < text.length && text[i + 1] == '/' && !hashComments -> {
                    val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                    tokens += Token(i, end, Kind.COMMENT)
                    i = end
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    val end = (text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 })
                    tokens += Token(i, end, Kind.COMMENT)
                    i = end
                }
                hashComments && c == '#' -> {
                    val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                    tokens += Token(i, end, Kind.COMMENT)
                    i = end
                }
                c == '"' || c == '\'' -> {
                    val end = scanString(text, i, c)
                    tokens += Token(i, end, Kind.STRING)
                    i = end
                }
                c == '`' && lang in setOf("js", "javascript", "ts", "typescript", "kotlin") -> {
                    val end = scanString(text, i, '`')
                    tokens += Token(i, end, Kind.STRING)
                    i = end
                }
                c.isDigit() -> {
                    var j = i + 1
                    while (j < text.length && (text[j].isDigit() || text[j] == '.' || text[j] == '_')) j++
                    tokens += Token(i, j, Kind.NUMBER)
                    i = j
                }
                c.isLetter() || c == '_' -> {
                    var j = i + 1
                    while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                    val word = text.substring(i, j)
                    val kind = when {
                        word in keywords -> Kind.KEYWORD
                        word.first().isUpperCase() -> Kind.TYPE
                        else -> Kind.PLAIN
                    }
                    tokens += Token(i, j, kind)
                    i = j
                }
                else -> i++
            }
        }
        return tokens
    }

    fun applyBlock(doc: StyledDocument, start: Int, end: Int, language: String) {
        if (start >= end || doc.length == 0) return
        // Use the last *character* in [start, end), never `end` itself.
        // `end` is often the next paragraph's startOffset, and
        // getParagraphElement(end) would style that following paragraph.
        val firstChar = start.coerceIn(0, doc.length - 1)
        val lastChar = (end - 1).coerceIn(0, doc.length - 1)
        if (lastChar < firstChar) return
        val firstPara = doc.getParagraphElement(firstChar)
        val lastPara = doc.getParagraphElement(lastChar)
        val clipStart = maxOf(firstPara.startOffset, firstChar)
        val clipEnd = minOf(lastPara.endOffset, end, doc.length)
        if (clipStart >= clipEnd) return

        val len = clipEnd - clipStart
        val base = codeCharacterAttrs(FG)
        doc.setCharacterAttributes(clipStart, len, base, false)
        val text = doc.getText(clipStart, len)
        val tokenAttrs = HashMap<Kind, SimpleAttributeSet>(Kind.entries.size)
        for (token in tokenize(text, language)) {
            if (token.kind == Kind.PLAIN) continue
            val attrs = tokenAttrs.getOrPut(token.kind) { codeCharacterAttrs(color(token.kind)) }
            doc.setCharacterAttributes(clipStart + token.start, token.end - token.start, attrs, false)
        }

        val paraAttrs = codeParagraphAttrs(language)
        var para = firstPara
        while (para.startOffset < clipEnd) {
            NoteMarkdown.setParagraphAttributesContained(doc, para.startOffset, 0, paraAttrs, false)
            if (para.endOffset >= clipEnd || para.endOffset >= doc.length) break
            val next = doc.getParagraphElement(para.endOffset)
            if (next.startOffset == para.startOffset || next.startOffset >= clipEnd) break
            para = next
        }
    }

    fun applyInline(doc: StyledDocument, start: Int, len: Int, on: Boolean) {
        val a = SimpleAttributeSet()
        if (on) {
            a.addAttribute(NoteMarkdown.CODE_ATTR, true)
            StyleConstants.setFontFamily(a, java.awt.Font.MONOSPACED)
            StyleConstants.setForeground(a, FG)
            StyleConstants.setBackground(a, INLINE_BG)
        } else {
            a.addAttribute(NoteMarkdown.CODE_ATTR, false)
            StyleConstants.setFontFamily(a, StyleConstants.getFontFamily(doc.getCharacterElement(start).attributes))
            StyleConstants.setForeground(a, FG)
            StyleConstants.setBackground(a, PadColors.transparent)
        }
        if (len > 0) doc.setCharacterAttributes(start, len, a, false)
    }

    private fun codeCharacterAttrs(fg: Color): SimpleAttributeSet {
        val a = SimpleAttributeSet()
        a.addAttribute(NoteMarkdown.CODE_ATTR, true)
        StyleConstants.setFontFamily(a, java.awt.Font.MONOSPACED)
        StyleConstants.setForeground(a, fg)
        StyleConstants.setBackground(a, PadColors.transparent)
        return a
    }

    private fun codeParagraphAttrs(language: String): SimpleAttributeSet {
        val p = SimpleAttributeSet()
        p.addAttribute(NoteMarkdown.CODE_BLOCK_ATTR, true)
        if (language.isNotBlank()) p.addAttribute(NoteMarkdown.CODE_LANG_ATTR, language)
        StyleConstants.setLeftIndent(p, PadColors.CODE_PAD_H)
        StyleConstants.setRightIndent(p, PadColors.CODE_PAD_RIGHT)
        StyleConstants.setSpaceAbove(p, 0f)
        StyleConstants.setSpaceBelow(p, 0f)
        StyleConstants.setLineSpacing(p, 0f)
        return p
    }

    private fun scanString(text: String, from: Int, quote: Char): Int {
        var j = from + 1
        while (j < text.length) {
            if (text[j] == '\\' && j + 1 < text.length) {
                j += 2
                continue
            }
            if (text[j] == quote) return j + 1
            if (text[j] == '\n' && quote != '`') return j
            j++
        }
        return text.length
    }

    private fun keywordsFor(lang: String): Set<String> = when (lang) {
        "kt", "kotlin" -> KW_KOTLIN
        "java" -> KW_JAVA
        "js", "javascript", "ts", "typescript" -> KW_JS
        "py", "python" -> KW_PYTHON
        "json" -> setOf("true", "false", "null")
        "go" -> KW_GO
        "rs", "rust" -> KW_RUST
        "sql" -> KW_SQL
        "xml", "html" -> setOf("true", "false", "null")
        else -> KW_GENERIC
    }

    private val HASH_COMMENT_LANGS = setOf("py", "python", "sh", "bash", "shell", "yaml", "yml", "toml", "rb")

    private val KW_GENERIC = setOf(
        "if", "else", "for", "while", "return", "class", "fun", "function", "var", "val",
        "let", "const", "true", "false", "null", "void", "new", "this", "import", "package",
        "public", "private", "protected", "static", "override", "try", "catch", "throw",
        "switch", "case", "break", "continue", "in", "is", "as", "when",
    )
    private val KW_KOTLIN = KW_GENERIC + setOf(
        "data", "object", "companion", "suspend", "inline", "reified", "sealed", "enum",
        "interface", "typealias", "by", "lateinit", "inner", "operator", "actual", "expect",
    )
    private val KW_JAVA = KW_GENERIC + setOf(
        "abstract", "extends", "implements", "interface", "enum", "final", "synchronized",
        "throws", "boolean", "int", "long", "double", "float", "char", "byte", "short",
    )
    private val KW_JS = KW_GENERIC + setOf(
        "async", "await", "export", "from", "of", "typeof", "instanceof", "undefined", "yield",
    )
    private val KW_PYTHON = setOf(
        "def", "class", "if", "elif", "else", "for", "while", "return", "import", "from",
        "as", "in", "not", "and", "or", "None", "True", "False", "pass", "with", "try",
        "except", "raise", "yield", "lambda", "global", "async", "await",
    )
    private val KW_GO = setOf(
        "func", "package", "import", "var", "const", "type", "struct", "interface", "map",
        "if", "else", "for", "range", "return", "go", "defer", "chan", "select", "case",
        "true", "false", "nil",
    )
    private val KW_RUST = setOf(
        "fn", "let", "mut", "pub", "struct", "enum", "impl", "trait", "use", "mod", "match",
        "if", "else", "for", "while", "loop", "return", "true", "false", "self", "Self",
    )
    private val KW_SQL = setOf(
        "SELECT", "FROM", "WHERE", "INSERT", "INTO", "UPDATE", "DELETE", "CREATE", "TABLE",
        "AND", "OR", "NOT", "NULL", "JOIN", "LEFT", "RIGHT", "INNER", "ON", "AS", "VALUES",
    ).map { it.lowercase() }.toSet()
}
