package com.snagar.easynotes.service

import com.snagar.easynotes.model.Note
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.XCollection
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Application-level (global) storage for notes.
 *
 * Notes are persisted to `NotesPlugin.xml` inside the IDE configuration
 * directory, so they are shared across every project and survive IDE restarts.
 * The IntelliJ Platform persists [getState] automatically (periodically and on
 * shutdown), which gives us "auto-save" for free once the in-memory model is
 * kept up to date.
 */
@State(name = "NotesPluginStorage", storages = [Storage("NotesPlugin.xml")])
@Service(Service.Level.APP)
class NotesService : PersistentStateComponent<NotesService.State> {

    /** Serialized state container. */
    class State {
        @get:XCollection(style = XCollection.Style.v2)
        var notes: MutableList<Note> = mutableListOf()

        /**
         * Id of the note the user most recently opened in the editor, so it can
         * be reopened on the next IDE launch. Empty only on a fresh install (no
         * note ever opened); in that case the list is shown by default. The id is
         * kept even when the user navigates back to the list, so the last note is
         * always restored unless it has since been deleted.
         */
        var lastOpenedNoteId: String = ""

        /**
         * Font family used to render every note's title and body. Empty means
         * "use the IDE default font"; otherwise it is a font family name such as
         * "Monospaced" or "SansSerif". Applied globally across all notes.
         */
        var fontFamily: String = ""

        /**
         * Base font size (points) for note bodies; titles are rendered slightly
         * larger and bold. `0` means "use the IDE default size".
         */
        var fontSize: Int = 0
    }

    private var state = State()

    private val listeners = CopyOnWriteArrayList<NotesChangeListener>()

    // region PersistentStateComponent
    override fun getState(): State = state

    override fun loadState(loaded: State) {
        XmlSerializerUtil.copyBean(loaded, state)
    }
    // endregion

    // region CRUD
    /** Returns a snapshot copy of all notes (unordered). */
    fun getAllNotes(): List<Note> = state.notes.toList()

    /** Cheap emptiness check that avoids copying the backing list. */
    fun isEmpty(): Boolean = state.notes.isEmpty()

    fun findNote(id: String): Note? = state.notes.firstOrNull { it.id == id }

    fun existsById(id: String): Boolean = state.notes.any { it.id == id }

    /** Creates a new empty note and returns it. */
    fun createNote(title: String? = null): Note {
        val note = Note()
        title?.trim()?.takeIf { it.isNotEmpty() }?.let { note.title = it }
        val now = System.currentTimeMillis()
        note.createdAt = now
        note.modifiedAt = now
        state.notes.add(note)
        fireChanged()
        return note
    }

    /** Adds an already-constructed note (used by import). */
    fun addNote(note: Note) {
        state.notes.add(note)
        fireChanged()
    }

    /** Adds several notes at once, firing a single change event (used by undo). */
    fun addNotes(notes: Collection<Note>) {
        if (notes.isEmpty()) return
        state.notes.addAll(notes)
        fireChanged()
    }

    /**
     * Marks a note as changed. The note instance is the same object held in the
     * store, so its fields are already updated by the caller; this just bumps the
     * modified timestamp and notifies listeners.
     */
    fun touchNote(note: Note) {
        note.modifiedAt = System.currentTimeMillis()
        fireChanged()
    }

    fun deleteNotes(ids: Collection<String>) {
        val idSet = ids.toSet()
        if (state.notes.removeAll { it.id in idSet }) {
            fireChanged()
        }
    }
    // endregion

    // region Last opened note
    /**
     * The note the user most recently opened, or `null` on a fresh install where
     * no note has been opened yet. Used to restore the editor on the next launch.
     * Persisted with the rest of the state; setting it does not fire a change
     * event.
     */
    fun getLastOpenedNoteId(): String? = state.lastOpenedNoteId.ifBlank { null }

    fun setLastOpenedNoteId(id: String) {
        state.lastOpenedNoteId = id
    }
    // endregion

    // region Font settings
    /**
     * The font family applied to every note, or `null` to use the IDE default.
     */
    fun getFontFamily(): String? = state.fontFamily.ifBlank { null }

    /** The base body font size in points, or `null` to use the IDE default. */
    fun getFontSize(): Int? = state.fontSize.takeIf { it > 0 }

    /**
     * Updates the global note font. `family` of `null`/blank and `size` of `0`
     * mean "use the IDE default". Notifies listeners so open panels restyle
     * immediately.
     */
    fun setFont(family: String?, size: Int) {
        state.fontFamily = family?.trim().orEmpty()
        state.fontSize = if (size > 0) size else 0
        fireChanged()
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

    fun fireChanged() {
        for (l in listeners) {
            l.notesChanged()
        }
    }
    // endregion

    companion object {
        @JvmStatic
        fun getInstance(): NotesService =
            ApplicationManager.getApplication().getService(NotesService::class.java)
    }
}
