package com.snagar.easynotes.search

import com.snagar.easynotes.model.Note

/**
 * A tiny in-memory search index that memoizes the lowercased title and content
 * of each note so global (list) search doesn't re-lowercase every note's full
 * body on every keystroke.
 *
 * This is the lightweight equivalent of the "indexing / memoization" a web app
 * might push onto a web worker: for the note counts a notepad realistically
 * holds, a cached lowercase projection keyed by `(id, modifiedAt)` keeps
 * filtering/ranking effectively free while the user types.
 *
 * Entries are invalidated automatically when a note's [Note.modifiedAt] changes,
 * so edits are always reflected. Access is expected from the Swing EDT (where
 * all note reads/writes happen), so a plain [HashMap] is sufficient.
 */
object NoteSearchIndex {

    private class Entry(val stamp: Long, val titleLower: String, val contentLower: String)

    private val cache = HashMap<String, Entry>()

    private fun entryFor(note: Note): Entry {
        val cached = cache[note.id]
        if (cached != null && cached.stamp == note.modifiedAt) return cached
        val fresh = Entry(
            stamp = note.modifiedAt,
            titleLower = note.displayTitle().lowercase(),
            contentLower = note.content.lowercase(),
        )
        cache[note.id] = fresh
        return fresh
    }

    /** True when [queryLower] (already lowercased/trimmed) appears in the title or body. */
    fun matches(note: Note, queryLower: String): Boolean {
        if (queryLower.isEmpty()) return true
        val e = entryFor(note)
        return e.titleLower.contains(queryLower) || e.contentLower.contains(queryLower)
    }

    /** Relevance score for ranking; see [TextSearch.score]. */
    fun score(note: Note, queryLower: String): Int {
        val e = entryFor(note)
        return TextSearch.score(e.titleLower, e.contentLower, queryLower)
    }
}
