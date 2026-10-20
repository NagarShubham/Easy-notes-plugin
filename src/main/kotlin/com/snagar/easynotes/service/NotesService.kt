package com.snagar.easynotes.service

import com.snagar.easynotes.model.Note
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.annotations.XCollection
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Application-level (global) storage for notes.
 *
 * Notes are persisted to `NotesPlugin.xml` inside the IDE configuration
 * directory, so they are shared across every project and survive IDE restarts.
 * Roaming is disabled so Settings Sync does not upload note bodies.
 */
@State(
    name = "NotesPluginStorage",
    storages = [Storage(value = "NotesPlugin.xml", roamingType = RoamingType.DISABLED)],
)
@Service(Service.Level.APP)
class NotesService : PersistentStateComponent<NotesService.State> {

    /** Serialized state container. */
    class State {
        @get:XCollection(style = XCollection.Style.v2)
        var notes: MutableList<Note> = mutableListOf()

        var lastOpenedNoteId: String = ""
        var fontFamily: String = ""
        var fontSize: Int = 0
    }

    private var state = State()

    /** Id lookup rebuilt after the note list changes. Avoids a scan on every save and navigation. */
    private var byId: HashMap<String, Note>? = null

    private val listeners = CopyOnWriteArrayList<NotesChangeListener>()

    /** Nested [batch] calls suppress change events until the outermost batch ends. */
    private var batchDepth = 0

    private fun index(): HashMap<String, Note> {
        val existing = byId
        if (existing != null) return existing
        val map = HashMap<String, Note>(state.notes.size * 2)
        for (note in state.notes) map[note.id] = note
        byId = map
        return map
    }

    private fun copyState(source: State): State {
        val copy = State()
        copy.notes = source.notes.map { it.copy() }.toMutableList()
        copy.lastOpenedNoteId = source.lastOpenedNoteId
        copy.fontFamily = source.fontFamily
        copy.fontSize = source.fontSize
        dedupeIds(copy.notes)
        return copy
    }

    private fun dedupeIds(notes: MutableList<Note>) {
        val seen = HashSet<String>(notes.size * 2)
        for (note in notes) {
            if (note.id.isBlank() || !seen.add(note.id)) {
                var id: String
                do {
                    id = UUID.randomUUID().toString()
                } while (!seen.add(id))
                note.id = id
            }
        }
    }

    private fun adoptUniqueId(note: Note) {
        val map = index()
        if (note.id.isBlank() || map.containsKey(note.id)) {
            var id: String
            do {
                id = UUID.randomUUID().toString()
            } while (map.containsKey(id))
            note.id = id
        }
    }

    private fun notifyIfNeeded() {
        if (batchDepth == 0) fireChanged()
    }

    /**
     * Runs [block] with a single change event at the end (used by import).
     */
    fun batch(block: () -> Unit) {
        batchDepth++
        try {
            block()
        } finally {
            batchDepth--
            if (batchDepth == 0) {
                byId = null
                fireChanged()
            }
        }
    }

    // region PersistentStateComponent
    override fun getState(): State = copyState(state)

    override fun loadState(loaded: State) {
        state = copyState(loaded)
        byId = null
        fireChanged()
    }
    // endregion

    // region CRUD
    /** Returns a snapshot copy of all notes (unordered). */
    fun getAllNotes(): List<Note> = state.notes.toList()

    /** Cheap emptiness check that avoids copying the backing list. */
    fun isEmpty(): Boolean = state.notes.isEmpty()

    fun findNote(id: String): Note? = index()[id]

    fun existsById(id: String): Boolean = index().containsKey(id)

    /** Creates a new empty note and returns it. */
    fun createNote(title: String? = null): Note {
        val note = Note()
        title?.trim()?.takeIf { it.isNotEmpty() }?.let { note.title = it }
        val now = System.currentTimeMillis()
        note.createdAt = now
        note.modifiedAt = now
        adoptUniqueId(note)
        state.notes.add(note)
        byId?.put(note.id, note)
        notifyIfNeeded()
        return note
    }

    /** Adds an already-constructed note (used by import). Duplicate ids are reminted. */
    fun addNote(note: Note) {
        adoptUniqueId(note)
        state.notes.add(note)
        byId?.put(note.id, note)
        notifyIfNeeded()
    }

    /** Adds several notes at once, firing a single change event (used by undo). */
    fun addNotes(notes: Collection<Note>) {
        if (notes.isEmpty()) return
        batch {
            for (note in notes) addNote(note)
        }
    }

    /**
     * Marks a note as changed. The note instance is the same object held in the
     * store, so its fields are already updated by the caller; this just bumps the
     * modified timestamp and notifies listeners.
     */
    fun touchNote(note: Note) {
        note.modifiedAt = System.currentTimeMillis()
        notifyIfNeeded()
    }

    fun deleteNotes(ids: Collection<String>) {
        val idSet = ids.toSet()
        if (state.notes.removeAll { it.id in idSet }) {
            val map = byId
            if (map != null) {
                for (id in idSet) map.remove(id)
            }
            notifyIfNeeded()
        }
    }
    // endregion

    // region Last opened note
    fun getLastOpenedNoteId(): String? = state.lastOpenedNoteId.ifBlank { null }

    fun setLastOpenedNoteId(id: String) {
        state.lastOpenedNoteId = id
    }
    // endregion

    // region Font settings
    fun getFontFamily(): String? = state.fontFamily.ifBlank { null }

    fun getFontSize(): Int? = state.fontSize.takeIf { it > 0 }

    fun setFont(family: String?, size: Int) {
        state.fontFamily = family?.trim().orEmpty()
        state.fontSize = if (size > 0) size else 0
        notifyIfNeeded()
    }
    // endregion

    // region Change notifications
    fun interface NotesChangeListener {
        fun notesChanged()
    }

    fun addChangeListener(listener: NotesChangeListener) {
        listeners.add(listener)
    }

    fun removeChangeListener(listener: NotesChangeListener) {
        listeners.remove(listener)
    }

    private fun fireChanged() {
        for (l in listeners) {
            l.notesChanged()
        }
    }
    // endregion

    companion object {
        @JvmStatic
        fun getInstance(): NotesService = service()
    }
}
