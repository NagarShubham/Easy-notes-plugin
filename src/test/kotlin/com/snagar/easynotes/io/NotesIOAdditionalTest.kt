package com.snagar.easynotes.io

import com.snagar.easynotes.model.Note
import com.snagar.easynotes.service.NotesService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Additional edge-case coverage for [NotesIO] beyond the core round-trips. */
class NotesIOAdditionalTest {

    // region JSON
    @Test
    fun `json backup preserves order and every field`() {
        val a = Note().apply {
            title = "A"; content = "body a"; colorRgb = 0xC8E6C9
            createdAt = 1_700_000_000_000L; modifiedAt = 1_700_000_050_000L
            pinned = true; favorite = false
        }
        val b = Note().apply {
            title = "B"; content = "body b"; colorRgb = 0xBBDEFB
            pinned = false; favorite = true
        }

        val parsed = NotesIO.parseJson(NotesIO.toJsonBackup(listOf(a, b)))

        assertEquals(listOf("A", "B"), parsed.map { it.title })
        assertEquals(a.id, parsed[0].id)
        assertEquals(0xC8E6C9, parsed[0].colorRgb)
        assertEquals(1_700_000_000_000L, parsed[0].createdAt)
        assertEquals(1_700_000_050_000L, parsed[0].modifiedAt)
        assertTrue(parsed[0].pinned)
        assertFalse(parsed[0].favorite)
        assertTrue(parsed[1].favorite)
    }

    @Test
    fun `json backup of an empty list parses to nothing`() {
        assertTrue(NotesIO.parseJson(NotesIO.toJsonBackup(emptyList())).isEmpty())
    }

    @Test
    fun `json parse masks colorRgb to 24 bits`() {
        val parsed = NotesIO.parseJson("""{"title":"X","colorRgb":301989887}""")
        assertEquals(0xFFFFFF, parsed[0].colorRgb)
    }

    @Test
    fun `json parse ignores unknown fields and keeps defaults`() {
        val parsed = NotesIO.parseJson("""{"title":"only title","extra":"ignored"}""")
        assertEquals(1, parsed.size)
        assertEquals("only title", parsed[0].title)
        assertEquals("", parsed[0].content)
        assertEquals(Note.DEFAULT_COLOR_RGB, parsed[0].colorRgb)
    }
    // endregion

    // region Markdown
    @Test
    fun `markdown output has heading and a trailing newline`() {
        val md = NotesIO.noteToMarkdown(Note().apply { title = "Title"; content = "no newline" })

        assertTrue(md.contains("# Title"))
        assertTrue(md.endsWith("\n"))
    }

    @Test
    fun `markdown timestamps round-trip at second precision`() {
        val note = Note().apply {
            title = "T"; content = "body"
            createdAt = 1_700_000_000_000L; modifiedAt = 1_700_000_050_000L
        }

        val parsed = NotesIO.parseMarkdown(NotesIO.noteToMarkdown(note), "fallback")

        assertEquals(note.createdAt, parsed.createdAt)
        assertEquals(note.modifiedAt, parsed.modifiedAt)
    }

    @Test
    fun `markdown frontmatter is parsed even without a heading`() {
        val md = "---\nid: abc\npinned: true\nfavorite: true\ncolor: #C8E6C9\n---\n\nBody without heading"

        val parsed = NotesIO.parseMarkdown(md, "fallback")

        assertEquals("fallback", parsed.title)
        assertEquals("abc", parsed.id)
        assertTrue(parsed.pinned)
        assertTrue(parsed.favorite)
        assertEquals(0xC8E6C9, parsed.colorRgb)
        assertEquals("Body without heading", parsed.content)
    }

    @Test
    fun `markdown normalizes CRLF line endings`() {
        val parsed = NotesIO.parseMarkdown("# Title\r\n\r\nline1\r\nline2", "fallback")

        assertEquals("Title", parsed.title)
        assertEquals("line1\nline2", parsed.content)
    }
    // endregion

    // region color helpers
    @Test
    fun `colorToHex masks high bits`() {
        assertEquals("#123456", NotesIO.colorToHex(0x123456))
        assertEquals("#FFFFFF", NotesIO.colorToHex(-1))
    }

    @Test
    fun `hexToColor accepts case and optional hash and rejects garbage`() {
        assertEquals(0xC8E6C9, NotesIO.hexToColor("#c8e6c9"))
        assertEquals(0xC8E6C9, NotesIO.hexToColor("C8E6C9"))
        assertNull(NotesIO.hexToColor("nope"))
    }
    // endregion

    // region import merge
    @Test
    fun `import summary total sums all outcomes for a mixed batch`() {
        val service = NotesService()
        val existing = service.createNote("Existing").apply { content = "old" }

        val incoming = listOf(
            Note().apply { title = "Fresh 1" },        // added
            Note().apply { title = "Fresh 2" },        // added
            existing.copy()                            // identical -> skipped
        )

        val summary = NotesIO.importNotes(service, incoming, ImportConflictPolicy.KEEP_BOTH)

        assertEquals(2, summary.added)
        assertEquals(1, summary.skipped)
        assertEquals(0, summary.overwritten)
        assertEquals(0, summary.renamed)
        assertEquals(3, summary.total)
    }

    @Test
    fun `overwrite policy replaces all mutable fields but keeps id and count`() {
        val service = NotesService()
        val existing = service.createNote("Old title").apply {
            content = "old"; colorRgb = 0xFFFDE0; pinned = false; favorite = false
        }
        val incoming = existing.copy().apply {
            title = "New title"; content = "new"; colorRgb = 0xC8E6C9; pinned = true; favorite = true
        }

        NotesIO.importNotes(service, listOf(incoming), ImportConflictPolicy.OVERWRITE)

        val stored = service.findNote(existing.id)!!
        assertEquals(1, service.getAllNotes().size)
        assertEquals("New title", stored.title)
        assertEquals("new", stored.content)
        assertEquals(0xC8E6C9, stored.colorRgb)
        assertTrue(stored.pinned)
        assertTrue(stored.favorite)
    }

    @Test
    fun `keep-both policy leaves the original untouched`() {
        val service = NotesService()
        val existing = service.createNote("Title").apply { content = "old" }
        val incoming = existing.copy().apply { content = "new" }

        NotesIO.importNotes(service, listOf(incoming), ImportConflictPolicy.KEEP_BOTH)

        assertEquals(2, service.getAllNotes().size)
        assertEquals("old", service.findNote(existing.id)?.content)
        assertNotEquals(existing.id, incoming.id)
    }
    // endregion
}
