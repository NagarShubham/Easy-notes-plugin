package com.snagar.easynotes.markdown

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.text.DefaultStyledDocument
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

class CodeHighlightTest {

    @Test
    fun `tokenizes kotlin keywords strings and comments`() {
        val src = "fun x() = \"hi\" // c\n"
        val kinds = CodeHighlight.tokenize(src, "kotlin").associate { src.substring(it.start, it.end) to it.kind }
        assertEquals(CodeHighlight.Kind.KEYWORD, kinds["fun"])
        assertEquals(CodeHighlight.Kind.STRING, kinds["\"hi\""])
        assertEquals(CodeHighlight.Kind.COMMENT, kinds["// c"])
    }

    @Test
    fun `detects kotlin from fun and val`() {
        assertEquals("kotlin", CodeHighlight.detectLanguage("fun main() {\n  val x = 1\n}"))
    }

    @Test
    fun `code block attributes do not leak onto the next paragraph`() {
        val doc = DefaultStyledDocument()
        val base = SimpleAttributeSet()
        StyleConstants.setFontFamily(base, "Dialog")
        doc.insertString(0, "val x = 1\nplain line after\nthird line", base)
        val codeEnd = "val x = 1".length
        NoteMarkdown.markParagraph(doc, 0, NoteMarkdown.CODE_BLOCK_ATTR)
        CodeHighlight.applyBlock(doc, 0, codeEnd, "kotlin")
        val codePara = doc.getParagraphElement(0)
        val nextPara = doc.getParagraphElement(codePara.endOffset)
        val third = doc.getParagraphElement(nextPara.endOffset)
        assertTrue(codePara.attributes.getAttribute(NoteMarkdown.CODE_BLOCK_ATTR) == true)
        assertFalse(nextPara.attributes.getAttribute(NoteMarkdown.CODE_BLOCK_ATTR) == true)
        assertFalse(third.attributes.getAttribute(NoteMarkdown.CODE_BLOCK_ATTR) == true)
        assertEquals(
            "```kotlin\nval x = 1\n```\nplain line after\nthird line",
            NoteMarkdown.fromDocument(doc),
        )
    }

    @Test
    fun `loading a fenced block does not mark surrounding paragraphs`() {
        val doc = DefaultStyledDocument()
        val base = SimpleAttributeSet()
        StyleConstants.setFontFamily(base, "Dialog")
        NoteMarkdown.applyTo(doc, "hello\n```\nfun x() {}\n```\nworld", base)
        assertEquals("hello\n```\nfun x() {}\n```\nworld", NoteMarkdown.fromDocument(doc))
        val hello = doc.getParagraphElement(0)
        val world = doc.getParagraphElement(doc.length - 1)
        assertFalse(hello.attributes.getAttribute(NoteMarkdown.CODE_BLOCK_ATTR) == true)
        assertFalse(world.attributes.getAttribute(NoteMarkdown.CODE_BLOCK_ATTR) == true)
    }
}

