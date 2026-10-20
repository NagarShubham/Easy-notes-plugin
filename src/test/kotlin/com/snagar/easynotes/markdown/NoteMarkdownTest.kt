package com.snagar.easynotes.markdown

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.text.DefaultStyledDocument
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

class NoteMarkdownTest {

    @Test
    fun `plain text round-trips unchanged`() {
        assertEquals("hello world", roundTrip("hello world"))
        assertEquals("line 1\n\nline 3", roundTrip("line 1\n\nline 3"))
        assertEquals("", roundTrip(""))
    }

    @Test
    fun `bold italic underline strike and code round-trip`() {
        assertEquals("**bold**", roundTrip("**bold**"))
        assertEquals("*italic*", roundTrip("*italic*"))
        assertEquals("__under__", roundTrip("__under__"))
        assertEquals("~~gone~~", roundTrip("~~gone~~"))
        assertEquals("`code`", roundTrip("`code`"))
    }

    @Test
    fun `nested bold italic round-trips`() {
        assertEquals("***both***", roundTrip("***both***"))
        assertEquals("**bold *and italic* still**", roundTrip("**bold *and italic* still**"))
    }

    @Test
    fun `unmatched markers stay literal`() {
        assertEquals("see *this", roundTrip("see *this"))
        assertEquals("half **bold", roundTrip("half **bold"))
    }

    @Test
    fun `lists round-trip`() {
        assertEquals("- one\n- two", roundTrip("- one\n- two"))
        assertEquals("- one\n- two", roundTrip("* one\n* two"))
        assertEquals("1. first\n2. second", roundTrip("1. first\n2. second"))
    }

    @Test
    fun `plainText strips markers`() {
        assertEquals("bold", NoteMarkdown.plainText("**bold**"))
        assertEquals("a b", NoteMarkdown.plainText("**a** *b*"))
        assertEquals("item", NoteMarkdown.plainText("- item"))
        assertEquals("hello", NoteMarkdown.plainText("`hello`"))
    }

    @Test
    fun `document round-trip preserves styles`() {
        val doc = DefaultStyledDocument()
        val base = SimpleAttributeSet()
        StyleConstants.setFontFamily(base, "Dialog")
        StyleConstants.setFontSize(base, 14)
        val src = "Hello **world** and *more*\n- a `code` item\n1. numbered"
        NoteMarkdown.applyTo(doc, src, base)
        assertEquals("Hello **world** and *more*\n- a `code` item\n1. numbered", NoteMarkdown.fromDocument(doc))
        assertTrue(StyleConstants.isBold(doc.getCharacterElement("Hello ".length).attributes))
        assertFalse(StyleConstants.isBold(doc.getCharacterElement(0).attributes))
    }

    @Test
    fun `quotes and fenced code round-trip`() {
        assertEquals("> quoted", roundTrip("> quoted"))
        assertEquals("[Docs](https://example.com)", roundTrip("[Docs](https://example.com)"))
        assertEquals("```\nfun x() {}\n```", roundTrip("```\nfun x() {}\n```"))
        assertEquals("```kotlin\nline 1\n\nline 3\n```", roundTrip("```kotlin\nline 1\n\nline 3\n```"))
    }

    @Test
    fun `markdown link syntax is left as literal text`() {
        assertEquals("[Docs](https://example.com)", NoteMarkdown.plainText("[Docs](https://example.com)"))
        assertEquals("quoted", NoteMarkdown.plainText("> quoted"))
        assertEquals("fun x() {}", NoteMarkdown.plainText("```\nfun x() {}\n```"))
    }

    @Test
    fun `document round-trip preserves quotes and code blocks`() {
        val doc = DefaultStyledDocument()
        val base = SimpleAttributeSet()
        StyleConstants.setFontFamily(base, "Dialog")
        StyleConstants.setFontSize(base, 14)
        val src = "> hello world\n```\nval x = 1\n```"
        NoteMarkdown.applyTo(doc, src, base)
        assertEquals(src, NoteMarkdown.fromDocument(doc))
    }

    @Test
    fun `existing notes without markup load as a single paragraph`() {
        val blocks = NoteMarkdown.parse("just a sticky note")
        assertEquals(1, blocks.size)
        assertEquals(NoteMarkdown.BlockKind.PARAGRAPH, blocks[0].kind)
        assertEquals("just a sticky note", blocks[0].spans.single().text)
    }

    private fun roundTrip(src: String): String = NoteMarkdown.render(NoteMarkdown.parse(src))
}
