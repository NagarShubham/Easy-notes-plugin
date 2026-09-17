package com.example.notes.io

import com.example.notes.model.Note
import com.example.notes.service.NotesService
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What to do when an imported note collides with an existing one (same id). */
enum class ImportConflictPolicy {
    SKIP,
    OVERWRITE,
    KEEP_BOTH
}

/** Summary of an import run, for user feedback. */
data class ImportSummary(
    var added: Int = 0,
    var overwritten: Int = 0,
    var skipped: Int = 0,
    var renamed: Int = 0
) {
    val total: Int get() = added + overwritten + skipped + renamed
}

/**
 * Serialization helpers for notes (JSON + Markdown) and the import merge logic
 * with duplicate detection and conflict handling.
 */
object NotesIO {

    private val isoFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneId.systemDefault())

    // region JSON
    /** Serializes notes as a backup document: {"version":1,"notes":[...]}. */
    fun toJsonBackup(notes: List<Note>): String = buildString {
        append("{\n  \"version\": 1,\n  \"notes\": [")
        notes.forEachIndexed { i, note ->
            append(if (i == 0) "\n" else ",\n")
            appendNote(note, "    ")
        }
        if (notes.isNotEmpty()) append("\n  ")
        append("]\n}")
    }

    /** Serializes a single note as a JSON object. */
    fun noteToJson(note: Note): String = buildString { appendNote(note, "") }

    private fun StringBuilder.appendNote(note: Note, indent: String) {
        val inner = "$indent  "
        append(indent).append("{\n")
        append(inner).append("\"id\": \"").append(Json.escape(note.id)).append("\",\n")
        append(inner).append("\"title\": \"").append(Json.escape(note.title)).append("\",\n")
        append(inner).append("\"content\": \"").append(Json.escape(note.content)).append("\",\n")
        append(inner).append("\"colorRgb\": ").append(note.colorRgb).append(",\n")
        append(inner).append("\"createdAt\": ").append(note.createdAt).append(",\n")
        append(inner).append("\"modifiedAt\": ").append(note.modifiedAt).append(",\n")
        append(inner).append("\"pinned\": ").append(note.pinned).append(",\n")
        append(inner).append("\"favorite\": ").append(note.favorite).append("\n")
        append(indent).append("}")
    }

    /**
     * Parses notes from JSON. Accepts a single note object, a bare array of
     * notes, or a backup object with a "notes" array. Malformed input yields an
     * empty list rather than throwing.
     */
    fun parseJson(text: String): List<Note> {
        val root = try {
            Json.parse(text)
        } catch (_: JsonException) {
            return emptyList()
        }
        val objects: List<Any?> = when {
            root is List<*> -> root
            root is Map<*, *> && root["notes"] is List<*> -> root["notes"] as List<*>
            root is Map<*, *> -> listOf(root)
            else -> emptyList()
        }
        return objects.filterIsInstance<Map<*, *>>().map { normalize(noteFromMap(it)) }
    }

    private fun noteFromMap(map: Map<*, *>): Note {
        val note = Note()
        (map["id"] as? String)?.let { note.id = it }
        (map["title"] as? String)?.let { note.title = it }
        (map["content"] as? String)?.let { note.content = it }
        asLong(map["colorRgb"])?.let { note.colorRgb = it.toInt() and 0xFFFFFF }
        asLong(map["createdAt"])?.let { note.createdAt = it }
        asLong(map["modifiedAt"])?.let { note.modifiedAt = it }
        (map["pinned"] as? Boolean)?.let { note.pinned = it }
        (map["favorite"] as? Boolean)?.let { note.favorite = it }
        return note
    }

    private fun asLong(value: Any?): Long? = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Double -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }
    // endregion

    // region Markdown
    /** Serializes a single note to Markdown with a YAML-style frontmatter header. */
    fun noteToMarkdown(note: Note): String = buildString {
        append("---\n")
        append("id: ").append(note.id).append('\n')
        append("color: ").append(colorToHex(note.colorRgb)).append('\n')
        append("created: ").append(isoFormatter.format(Instant.ofEpochMilli(note.createdAt))).append('\n')
        append("modified: ").append(isoFormatter.format(Instant.ofEpochMilli(note.modifiedAt))).append('\n')
        append("pinned: ").append(note.pinned).append('\n')
        append("favorite: ").append(note.favorite).append('\n')
        append("---\n\n")
        append("# ").append(note.displayTitle()).append("\n\n")
        append(note.content)
        if (!note.content.endsWith("\n")) append('\n')
    }

    /**
     * Parses a single note from Markdown. Frontmatter is optional; when absent,
     * the title is taken from the first `# ` heading (or [fallbackTitle]) and the
     * rest is treated as content.
     */
    fun parseMarkdown(text: String, fallbackTitle: String): Note {
        val note = Note()
        var body = text.replace("\r\n", "\n")

        val fm = frontmatter(body)
        if (fm != null) {
            body = fm.second
            fm.first["id"]?.let { note.id = it }
            fm.first["color"]?.let { note.colorRgb = hexToColor(it) ?: note.colorRgb }
            fm.first["created"]?.let { parseInstant(it)?.let { ms -> note.createdAt = ms } }
            fm.first["modified"]?.let { parseInstant(it)?.let { ms -> note.modifiedAt = ms } }
            fm.first["pinned"]?.let { note.pinned = it.trim().toBoolean() }
            fm.first["favorite"]?.let { note.favorite = it.trim().toBoolean() }
        }

        val lines = body.trimStart('\n').split("\n").toMutableList()
        if (lines.isNotEmpty() && lines[0].startsWith("# ")) {
            note.title = lines.removeAt(0).removePrefix("# ").trim()
            if (lines.isNotEmpty() && lines[0].isBlank()) lines.removeAt(0)
        } else {
            note.title = fallbackTitle
        }
        note.content = lines.joinToString("\n").trimEnd('\n')
        return normalize(note)
    }

    private fun frontmatter(text: String): Pair<Map<String, String>, String>? {
        if (!text.startsWith("---")) return null
        val afterFirst = text.indexOf('\n')
        if (afterFirst < 0) return null
        val closing = text.indexOf("\n---", afterFirst)
        if (closing < 0) return null
        val header = text.substring(afterFirst + 1, closing)
        val rest = text.substring(closing + 4).trimStart('\n')
        val map = LinkedHashMap<String, String>()
        for (line in header.split("\n")) {
            val idx = line.indexOf(':')
            if (idx > 0) {
                map[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
            }
        }
        return map to rest
    }
    // endregion

    // region Import merge
    /**
     * Merges [incoming] notes into the [service], applying [policy] to conflicts.
     * Exact duplicates (same id and identical content) are silently skipped.
     */
    fun importNotes(
        service: NotesService,
        incoming: List<Note>,
        policy: ImportConflictPolicy
    ): ImportSummary {
        val summary = ImportSummary()
        for (note in incoming) {
            val existing = service.findNote(note.id)
            if (existing == null) {
                service.addNote(note)
                summary.added++
                continue
            }
            if (isSameContent(existing, note)) {
                summary.skipped++
                continue
            }
            when (policy) {
                ImportConflictPolicy.SKIP -> summary.skipped++
                ImportConflictPolicy.OVERWRITE -> {
                    existing.title = note.title
                    existing.content = note.content
                    existing.colorRgb = note.colorRgb
                    existing.pinned = note.pinned
                    existing.favorite = note.favorite
                    existing.modifiedAt = System.currentTimeMillis()
                    service.fireChanged()
                    summary.overwritten++
                }
                ImportConflictPolicy.KEEP_BOTH -> {
                    note.id = java.util.UUID.randomUUID().toString()
                    service.addNote(note)
                    summary.renamed++
                }
            }
        }
        return summary
    }

    /** True if any imported note collides with an existing (non-identical) note. */
    fun hasConflicts(service: NotesService, incoming: List<Note>): Boolean =
        incoming.any { n ->
            val existing = service.findNote(n.id)
            existing != null && !isSameContent(existing, n)
        }

    private fun isSameContent(a: Note, b: Note): Boolean =
        a.title == b.title &&
            a.content == b.content &&
            a.colorRgb == b.colorRgb &&
            a.pinned == b.pinned &&
            a.favorite == b.favorite
    // endregion

    // region helpers
    fun colorToHex(rgb: Int): String = String.format("#%06X", rgb and 0xFFFFFF)

    fun hexToColor(hex: String): Int? {
        val cleaned = hex.trim().removePrefix("#")
        return cleaned.toIntOrNull(16)?.and(0xFFFFFF)
    }

    private fun parseInstant(value: String): Long? = try {
        java.time.LocalDateTime.parse(value.trim(), isoFormatter)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (_: Exception) {
        try {
            Instant.parse(value.trim()).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    /** Ensures required fields are sane after deserialization. */
    private fun normalize(note: Note): Note {
        if (note.id.isBlank()) note.id = java.util.UUID.randomUUID().toString()
        if (note.createdAt <= 0L) note.createdAt = System.currentTimeMillis()
        if (note.modifiedAt <= 0L) note.modifiedAt = note.createdAt
        return note
    }
    // endregion
}
