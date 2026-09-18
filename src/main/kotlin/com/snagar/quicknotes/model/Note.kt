package com.snagar.quicknotes.model

import java.util.UUID

/**
 * A single note.
 *
 * This is a plain mutable bean so it can be serialized by the IntelliJ Platform
 * XML serializer (for persistence) and mapped by our in-house JSON reader/writer
 * (for import/export).
 */
class Note {
    var id: String = UUID.randomUUID().toString()
    var title: String = ""
    var content: String = ""

    /** Background color stored as a packed 0xRRGGBB integer. */
    var colorRgb: Int = DEFAULT_COLOR_RGB

    var createdAt: Long = System.currentTimeMillis()
    var modifiedAt: Long = System.currentTimeMillis()

    var pinned: Boolean = false
    var favorite: Boolean = false

    /** Title shown in the UI; falls back to a default when the user left it blank. */
    fun displayTitle(): String = title.trim().ifEmpty { DEFAULT_TITLE }

    /** Deep copy, used when exporting or duplicating notes. */
    fun copy(): Note {
        val c = Note()
        c.id = id
        c.title = title
        c.content = content
        c.colorRgb = colorRgb
        c.createdAt = createdAt
        c.modifiedAt = modifiedAt
        c.pinned = pinned
        c.favorite = favorite
        return c
    }

    companion object {
        const val DEFAULT_TITLE: String = "Untitled Note"

        /** Pale legal-pad yellow, the default paper color for the editor. */
        const val DEFAULT_COLOR_RGB: Int = 0xFFFDE0
    }
}

/** Ways the notes list can be ordered. */
enum class SortKey(val label: String) {
    TITLE("Title"),
    CREATED("Created date"),
    MODIFIED("Last modified");

    override fun toString(): String = label
}
