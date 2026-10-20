package com.snagar.easynotes.search

import com.snagar.easynotes.model.Note
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NoteSearchIndexTest {

    @AfterEach
    fun tearDown() {
        NoteSearchIndex.clear()
    }

    @Test
    fun `retain drops replaced ids even when the cache size does not shrink`() {
        val a = Note().apply { title = "alpha"; content = "secret-a"; modifiedAt = 1 }
        val b = Note().apply { title = "beta"; content = "secret-b"; modifiedAt = 1 }
        assertTrue(NoteSearchIndex.matches(a, "secret-a"))
        assertTrue(NoteSearchIndex.matches(b, "secret-b"))
        NoteSearchIndex.retain(listOf(b))
        assertFalse(NoteSearchIndex.isCached(a.id))
        assertTrue(NoteSearchIndex.isCached(b.id))
    }

    @Test
    fun `score uses cached projection until modifiedAt changes`() {
        val note = Note().apply { title = "Hello"; content = "world"; modifiedAt = 10 }
        assertEquals(1000, NoteSearchIndex.score(note, "hello"))
        note.title = "Other"
        assertEquals(1000, NoteSearchIndex.score(note, "hello"))
        note.modifiedAt = 11
        assertEquals(0, NoteSearchIndex.score(note, "hello"))
        assertTrue(NoteSearchIndex.score(note, "other") > 0)
    }
}
