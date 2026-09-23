package com.snagar.easynotes.io

import com.snagar.easynotes.model.Note
import com.snagar.easynotes.service.NotesService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure import/export logic. These deliberately avoid any
 * IntelliJ Platform UI/application services: [NotesService] is exercised as a
 * plain in-memory store (its CRUD methods only touch local state).
 */
class NotesIOTest {

    // region Markdown
    @Test
    fun `markdown round-trips title, content and flags`() {
        val original = Note().apply {
            title = "Meeting notes"
            content = "line 1\nline 2\n\nline 4"
            pinned = true
            favorite = true
            colorRgb = 0xC8E6C9
        }

        val parsed = NotesIO.parseMarkdown(NotesIO.noteToMarkdown(original), "fallback")

        assertEquals(original.id, parsed.id)
        assertEquals("Meeting notes", parsed.title)
        assertEquals("line 1\nline 2\n\nline 4", parsed.content)
        assertTrue(parsed.pinned)
        assertTrue(parsed.favorite)
        assertEquals(0xC8E6C9, parsed.colorRgb)
    }

    @Test
    fun `markdown without frontmatter uses heading as title`() {
        val md = "# My Heading\n\nBody text here."
        val parsed = NotesIO.parseMarkdown(md, "fallback")

        assertEquals("My Heading", parsed.title)
        assertEquals("Body text here.", parsed.content)
    }

    @Test
    fun `markdown without heading falls back to provided title`() {
        val parsed = NotesIO.parseMarkdown("just some content", "fallback-title")

        assertEquals("fallback-title", parsed.title)
        assertEquals("just some content", parsed.content)
    }

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

    // region JSON
    @Test
    fun `json parses single object, array and backup wrapper`() {
        val note = Note().apply { title = "One"; content = "c" }

        val single = NotesIO.parseJson(NotesIO.noteToJson(note))
        assertEquals(1, single.size)
        assertEquals("One", single[0].title)

        val backup = NotesIO.parseJson(NotesIO.toJsonBackup(listOf(note, Note().apply { title = "Two" })))
        assertEquals(2, backup.size)

        val bareArray = NotesIO.parseJson("""[{"title":"A"},{"title":"B"}]""")
        assertEquals(listOf("A", "B"), bareArray.map { it.title })
    }

    @Test
    fun `json parse normalizes blank id and timestamps`() {
        val parsed = NotesIO.parseJson("""{"title":"X","id":""}""")
        assertEquals(1, parsed.size)
        assertTrue(parsed[0].id.isNotBlank())
        assertTrue(parsed[0].createdAt > 0L)
        assertTrue(parsed[0].modifiedAt > 0L)
    }

    @Test
    fun `json round-trips strings needing escaping`() {
        val note = Note().apply {
            title = "Quote \" and \\ backslash"
            content = "line1\nline2\ttab\r\n\"quoted\""
        }
        val parsed = NotesIO.parseJson(NotesIO.noteToJson(note))
        assertEquals(1, parsed.size)
        assertEquals(note.title, parsed[0].title)
        assertEquals(note.content, parsed[0].content)
    }

    @Test
    fun `json parses unicode escape and preserves non-ascii`() {
        val parsed = NotesIO.parseJson("""{"title":"caf\u00e9 \u2605","content":"日本語"}""")
        assertEquals("café \u2605", parsed[0].title)
        assertEquals("日本語", parsed[0].content)
    }

    @Test
    fun `json preserves large timestamps as long`() {
        val note = Note().apply { createdAt = 1_700_000_000_000L; modifiedAt = 1_700_000_050_000L }
        val parsed = NotesIO.parseJson(NotesIO.noteToJson(note))
        assertEquals(1_700_000_000_000L, parsed[0].createdAt)
        assertEquals(1_700_000_050_000L, parsed[0].modifiedAt)
    }

    @Test
    fun `malformed json yields empty list instead of throwing`() {
        assertTrue(NotesIO.parseJson("{ not valid json").isEmpty())
        assertTrue(NotesIO.parseJson("").isEmpty())
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

    // region color helpers
    @Test
    fun `color hex round-trips`() {
        assertEquals("#FFFDE0", NotesIO.colorToHex(0xFFFDE0))
        assertEquals("#FFFFFF", NotesIO.colorToHex(-1))
        assertEquals(0xFFFDE0, NotesIO.hexToColor("#FFFDE0"))
        assertEquals(0xFFFDE0, NotesIO.hexToColor("FFFDE0"))
        assertEquals(0xC8E6C9, NotesIO.hexToColor("#c8e6c9"))
        assertNull(NotesIO.hexToColor("not-a-color"))
    }
    // endregion

    // region import merge
    @Test
    fun `import adds brand new notes`() {
        val service = NotesService()
        val incoming = listOf(Note().apply { title = "New" })

        val summary = NotesIO.importNotes(service, incoming, ImportConflictPolicy.KEEP_BOTH)

        assertEquals(1, summary.added)
        assertEquals(1, summary.total)
        assertEquals(1, service.getAllNotes().size)
    }

    @Test
    fun `identical note is skipped regardless of policy`() {
        val service = NotesService()
        val existing = service.createNote("Same").apply { content = "body" }
        val incoming = existing.copy()

        val summary = NotesIO.importNotes(service, listOf(incoming), ImportConflictPolicy.OVERWRITE)

        assertEquals(1, summary.skipped)
        assertEquals(0, summary.overwritten)
        assertEquals(1, service.getAllNotes().size)
    }

    @Test
    fun `overwrite policy updates existing note in place`() {
        val service = NotesService()
        val existing = service.createNote("Title").apply {
            content = "old"; colorRgb = 0xFFFDE0; pinned = false; favorite = false
        }
        val incoming = existing.copy().apply {
            title = "New title"; content = "new"; colorRgb = 0xC8E6C9; pinned = true; favorite = true
        }

        val summary = NotesIO.importNotes(service, listOf(incoming), ImportConflictPolicy.OVERWRITE)

        val stored = service.findNote(existing.id)!!
        assertEquals(1, summary.overwritten)
        assertEquals(1, service.getAllNotes().size)
        assertEquals("New title", stored.title)
        assertEquals("new", stored.content)
        assertEquals(0xC8E6C9, stored.colorRgb)
        assertTrue(stored.pinned)
        assertTrue(stored.favorite)
    }

    @Test
    fun `skip policy leaves existing note untouched`() {
        val service = NotesService()
        val existing = service.createNote("Title").apply { content = "old" }
        val incoming = existing.copy().apply { content = "new" }

        val summary = NotesIO.importNotes(service, listOf(incoming), ImportConflictPolicy.SKIP)

        assertEquals(1, summary.skipped)
        assertEquals("old", service.findNote(existing.id)?.content)
    }

    @Test
    fun `keep-both policy imports a copy with a new id`() {
        val service = NotesService()
        val existing = service.createNote("Title").apply { content = "old" }
        val incoming = existing.copy().apply { content = "new" }

        val summary = NotesIO.importNotes(service, listOf(incoming), ImportConflictPolicy.KEEP_BOTH)

        assertEquals(1, summary.renamed)
        assertEquals(2, service.getAllNotes().size)
        assertEquals("old", service.findNote(existing.id)?.content)
        assertNotEquals(existing.id, incoming.id)
    }

    @Test
    fun `hasConflicts detects only non-identical collisions`() {
        val service = NotesService()
        val existing = service.createNote("Title").apply { content = "old" }

        assertFalse(NotesIO.hasConflicts(service, listOf(existing.copy())))
        assertTrue(NotesIO.hasConflicts(service, listOf(existing.copy().apply { content = "changed" })))
        assertFalse(NotesIO.hasConflicts(service, listOf(Note().apply { title = "unrelated" })))
    }

    @Test
    fun `import summary total sums all outcomes for a mixed batch`() {
        val service = NotesService()
        val existing = service.createNote("Existing").apply { content = "old" }

        val summary = NotesIO.importNotes(
            service,
            listOf(
                Note().apply { title = "Fresh 1" },
                Note().apply { title = "Fresh 2" },
                existing.copy()
            ),
            ImportConflictPolicy.KEEP_BOTH
        )

        assertEquals(2, summary.added)
        assertEquals(1, summary.skipped)
        assertEquals(0, summary.overwritten)
        assertEquals(0, summary.renamed)
        assertEquals(3, summary.total)
    }
    // endregion
}
