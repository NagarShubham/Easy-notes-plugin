package com.snagar.easynotes.search

import com.snagar.easynotes.markdown.NoteMarkdown
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

    private class Entry(
        val stamp: Long,
        val titleLower: String,
        val plain: String,
        val contentLower: String,
    )

    private val cache = HashMap<String, Entry>()

    private fun entryFor(note: Note): Entry {
        val cached = cache[note.id]
        if (cached != null && cached.stamp == note.modifiedAt) return cached
        val plain = NoteMarkdown.plainText(note.content)
        val fresh = Entry(
            stamp = note.modifiedAt,
            titleLower = note.displayTitle().lowercase(),
            plain = plain,
            contentLower = plain.lowercase(),
        )
        cache[note.id] = fresh
        return fresh
    }

    /**
     * Drops cached projections for notes that are no longer in [live].
     * Called when the list is recomputed so deleted notes do not keep a
     * lowercased copy of their body in memory.
     */
    fun retain(live: List<Note>) {
        val ids = HashSet<String>(live.size * 2)
        for (note in live) ids.add(note.id)
        cache.keys.retainAll(ids)
    }

    internal fun isCached(id: String): Boolean = cache.containsKey(id)

    internal fun clear() {
        cache.clear()
    }

    /** Lowercased display title, cached with the rest of the search projection. */
    fun titleLower(note: Note): String = entryFor(note).titleLower

    /** Markdown stripped to plain text, cached so the list renderer does not reparse. */
    fun plain(note: Note): String = entryFor(note).plain

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
