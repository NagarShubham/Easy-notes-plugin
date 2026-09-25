package com.snagar.easynotes.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Unit tests for the [Note] bean and the [SortKey] enum (pure, no platform). */
class NoteTest {

    @Test
    fun `new note has sensible defaults`() {
        val note = Note()

        assertTrue(note.id.isNotBlank())
        assertEquals("", note.title)
        assertEquals("", note.content)
        assertEquals(Note.DEFAULT_COLOR_RGB, note.colorRgb)
        assertFalse(note.pinned)
        assertFalse(note.favorite)
        assertTrue(note.createdAt > 0L)
        assertTrue(note.modifiedAt > 0L)
    }

    @Test
    fun `each new note gets a distinct id`() {
        assertNotSame(Note().id, Note().id)
        assertTrue(Note().id != Note().id)
    }

    @Test
    fun `displayTitle falls back to default when blank or whitespace`() {
        assertEquals(Note.DEFAULT_TITLE, Note().displayTitle())
        assertEquals(Note.DEFAULT_TITLE, Note().apply { title = "   " }.displayTitle())
    }

    @Test
    fun `displayTitle trims a non-blank title`() {
        assertEquals("Hello", Note().apply { title = "  Hello  " }.displayTitle())
    }

    @Test
    fun `copy duplicates every field including id`() {
        val original = Note().apply {
            title = "T"
            content = "C"
            colorRgb = 0xC8E6C9
            createdAt = 111L
            modifiedAt = 222L
            pinned = true
            favorite = true
        }

        val clone = original.copy()

        assertNotSame(original, clone)
        assertEquals(original.id, clone.id)
        assertEquals(original.title, clone.title)
        assertEquals(original.content, clone.content)
        assertEquals(original.colorRgb, clone.colorRgb)
        assertEquals(original.createdAt, clone.createdAt)
        assertEquals(original.modifiedAt, clone.modifiedAt)
        assertEquals(original.pinned, clone.pinned)
        assertEquals(original.favorite, clone.favorite)
    }

    @Test
    fun `copy is independent of the original`() {
        val original = Note().apply { title = "T"; content = "C" }
        val clone = original.copy()

        clone.title = "changed"
        clone.content = "changed too"

        assertEquals("T", original.title)
        assertEquals("C", original.content)
    }

    @Test
    fun `sort keys expose stable labels`() {
        assertEquals("Title", SortKey.TITLE.label)
        assertEquals("Created date", SortKey.CREATED.label)
        assertEquals("Last modified", SortKey.MODIFIED.label)
    }
}
