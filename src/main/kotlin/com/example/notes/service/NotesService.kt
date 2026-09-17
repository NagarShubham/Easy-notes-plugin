package com.example.notes.service

import com.example.notes.model.Note
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
