package com.snagar.easynotes.service

import com.snagar.easynotes.model.Note
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [NotesService] exercised as a plain in-memory store. Its CRUD,
 * change-notification, last-opened and state methods only touch local state, so
 * they run without booting the IntelliJ Platform application.
 */
class NotesServiceTest {

    // region CRUD
    @Test
    fun `createNote adds a note and returns it`() {
        val service = NotesService()

        val note = service.createNote()

        assertFalse(service.isEmpty())
        assertEquals(1, service.getAllNotes().size)
        assertSame(note, service.findNote(note.id))
        assertTrue(service.existsById(note.id))
    }

    @Test
    fun `createNote applies a trimmed non-blank title`() {
        val service = NotesService()

        assertEquals("Hello", service.createNote("  Hello  ").title)
    }

    @Test
    fun `createNote leaves title empty for null or blank input`() {
        val service = NotesService()

        assertEquals("", service.createNote(null).title)
        assertEquals("", service.createNote("   ").title)
    }

    @Test
    fun `createNote stamps created and modified with the same time`() {
        val note = NotesService().createNote()

        assertEquals(note.createdAt, note.modifiedAt)
    }

    @Test
    fun `getAllNotes returns a detached snapshot`() {
        val service = NotesService()
        service.createNote()
        val snapshot = service.getAllNotes()

        // Mutating the store afterwards must not change an earlier snapshot.
        service.createNote()

        assertEquals(1, snapshot.size)
        assertEquals(2, service.getAllNotes().size)
    }

    @Test
    fun `findNote and existsById return nothing for unknown ids`() {
        val service = NotesService()

        assertNull(service.findNote("missing"))
        assertFalse(service.existsById("missing"))
    }

    @Test
    fun `addNote stores an existing instance`() {
        val service = NotesService()
        val note = Note().apply { title = "imported" }

        service.addNote(note)

        assertSame(note, service.findNote(note.id))
    }

    @Test
    fun `addNotes stores every note`() {
        val service = NotesService()

        service.addNotes(listOf(Note(), Note(), Note()))

        assertEquals(3, service.getAllNotes().size)
    }

    @Test
    fun `touchNote bumps modifiedAt`() {
        val service = NotesService()
        val note = service.createNote().apply { modifiedAt = 0L }

        service.touchNote(note)

        assertTrue(note.modifiedAt > 0L)
    }

    @Test
    fun `deleteNotes removes only the requested ids`() {
        val service = NotesService()
        val a = service.createNote("A")
        val b = service.createNote("B")

        service.deleteNotes(listOf(a.id))

        assertNull(service.findNote(a.id))
        assertSame(b, service.findNote(b.id))
    }
    // endregion

    // region change notifications
    @Test
    fun `mutations notify a registered listener`() {
        val service = NotesService()
        var events = 0
        val listener = NotesService.NotesChangeListener { events++ }
        service.addChangeListener(listener)

        val note = service.createNote() // 1
        service.addNote(Note())         // 2
        service.addNotes(listOf(Note()))// 3
        service.touchNote(note)         // 4
        service.deleteNotes(listOf(note.id)) // 5

        assertEquals(5, events)
    }

    @Test
    fun `no-op mutations do not notify`() {
        val service = NotesService()
        var events = 0
        service.addChangeListener { events++ }

        service.addNotes(emptyList())          // empty: no event
        service.deleteNotes(listOf("missing")) // nothing removed: no event

        assertEquals(0, events)
    }

    @Test
    fun `removed listener stops receiving events`() {
        val service = NotesService()
        var events = 0
        val listener = NotesService.NotesChangeListener { events++ }
        service.addChangeListener(listener)
        service.createNote()
        service.removeChangeListener(listener)

        service.createNote()

        assertEquals(1, events)
    }
    // endregion

    // region last opened note
    @Test
    fun `last opened id defaults to null`() {
        assertNull(NotesService().getLastOpenedNoteId())
    }

    @Test
    fun `last opened id round-trips and blank reads back as null`() {
        val service = NotesService()

        service.setLastOpenedNoteId("abc")
        assertEquals("abc", service.getLastOpenedNoteId())

        service.setLastOpenedNoteId("")
        assertNull(service.getLastOpenedNoteId())
    }
    // endregion

    // region state persistence
    @Test
    fun `state survives a save and load round-trip`() {
        val source = NotesService()
        source.createNote("Kept")
        source.setLastOpenedNoteId("note-id")

        val restored = NotesService()
        restored.loadState(source.getState())

        assertEquals(1, restored.getAllNotes().size)
        assertEquals("Kept", restored.getAllNotes()[0].title)
        assertEquals("note-id", restored.getLastOpenedNoteId())
    }
    // endregion
}
