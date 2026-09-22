package com.snagar.easynotes.ui

import com.snagar.easynotes.io.ImportConflictPolicy
import com.snagar.easynotes.io.NotesIO
import com.snagar.easynotes.model.Note
import com.snagar.easynotes.model.SortKey
import com.snagar.easynotes.service.NotesService
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.CheckBoxList
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.GridLayout
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JColorChooser
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent

/**
 * The Notes UI, built as a master-detail that toggles inside a single tool
 * window using a [CardLayout]:
 *  - the LIST view shows all notes and fills the window;
 *  - clicking a note swaps to the DETAIL view (a yellow ruled legal pad),
 *    hiding the list; the toolbar's list button returns to the list.
 */
class NotesPanel(private val project: Project?) : JPanel(BorderLayout()), UiDataProvider {

    private enum class View { LIST, DETAIL }

    private val service = NotesService.getInstance()
    private val dateFormat = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())

    private val cards = JPanel(CardLayout())
    private var view = View.LIST

    // List view
    private val listModel = DefaultListModel<Note>()
    private val notesList = JBList(listModel)
    private val listSearch = SearchTextField()

    // Detail view
    private val indexLabel = JBLabel()
    private val titleField = JBTextField()
    private val contentArea = RuledTextArea()
    private val paperPanel = JPanel(BorderLayout())
    private val contentScroll = JBScrollPane(contentArea)

    // Fonts used when the user hasn't chosen a custom font; captured once so we
    // can always fall back to the IDE default family/size.
    private lateinit var defaultTitleFont: Font
    private lateinit var defaultContentFont: Font

    // Shared state
    private var ordered: List<Note> = emptyList()
    private var currentId: String? = null
    private var loading = false
    private var searchQuery: String = ""
    private var favoritesOnly: Boolean = false
    private var sortKey: SortKey = SortKey.CREATED

    private val disposable = Disposer.newDisposable("NotesPanel")
    private val saveAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)
    private val searchAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)

    private val serviceListener = NotesService.NotesChangeListener {
        SwingUtilities.invokeLater { onNotesChanged() }
    }

    private val predefinedColors = intArrayOf(
        0xFFFDE0, 0xFFF6B8, 0xC8E6C9, 0xBBDEFB, 0xF8BBD0, 0xFFE0B2,
        0xE1BEE7, 0xB2DFDB, 0xFFCDD2, 0xD7CCC8, 0xE0E0E0, 0xFFFFFF
    )

    init {
        border = JBUI.Borders.empty()
        cards.add(buildListCard(), View.LIST.name)
        cards.add(buildDetailCard(), View.DETAIL.name)
        add(cards, BorderLayout.CENTER)

        wireListeners()
        service.addChangeListener(serviceListener)

        reloadList()
        restoreLastView()
    }

    /**
     * On startup, reopen the note the user last had open (if it still exists);
     * otherwise show the list. This makes the tool window come back exactly where
     * you left it, and only defaults to the list on a fresh install.
     */
    private fun restoreLastView() {
        val lastNote = service.getLastOpenedNoteId()?.let { service.findNote(it) }
        if (lastNote != null) {
            openDetail(lastNote)
        } else {
            showListView()
        }
    }

    // region UI construction
    private fun createToolbar(groupId: String): ActionToolbar {
        val group = ActionManager.getInstance().getAction(groupId) as ActionGroup
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true)
        toolbar.targetComponent = this
        toolbar.component.isOpaque = false
        return toolbar
    }

    private fun buildListCard(): JComponent {
        val card = JPanel(BorderLayout())

        val north = JPanel(BorderLayout())
        north.border = JBUI.Borders.empty(4, 6)
        north.add(createToolbar("Notes.ListToolbar").component, BorderLayout.WEST)
        north.add(listSearch, BorderLayout.CENTER)
        card.add(north, BorderLayout.NORTH)

        notesList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        notesList.cellRenderer = NoteListCellRenderer()
        notesList.emptyText.text = "No notes yet. Click + to create one."
        card.add(JBScrollPane(notesList), BorderLayout.CENTER)
        return card
    }

    private fun buildDetailCard(): JComponent {
        val card = JPanel(BorderLayout())

        val north = JPanel(BorderLayout())
        north.border = JBUI.Borders.empty(4, 8)
        indexLabel.border = JBUI.Borders.emptyLeft(2)
        north.add(indexLabel, BorderLayout.WEST)
        north.add(createToolbar("Notes.DetailToolbar").component, BorderLayout.EAST)
        card.add(north, BorderLayout.NORTH)

        defaultTitleFont = titleField.font.deriveFont(Font.BOLD, titleField.font.size2D + 3f)
        titleField.border = JBUI.Borders.empty(8, 14, 4, 10)
        titleField.emptyText.text = Note.DEFAULT_TITLE

        defaultContentFont = contentArea.font.deriveFont(contentArea.font.size2D + 1f)
        contentArea.border = JBUI.Borders.empty(6, 18, 8, 10)
        contentArea.emptyText.text = "Enter your notes here..."

        applyFont()

        contentScroll.border = JBUI.Borders.empty()
        contentScroll.viewport.isOpaque = true

        paperPanel.add(titleField, BorderLayout.NORTH)
        paperPanel.add(contentScroll, BorderLayout.CENTER)
        card.add(paperPanel, BorderLayout.CENTER)
        return card
    }
    // endregion

    // region wiring
    private fun wireListeners() {
        val autoSave = object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = scheduleSave()
        }
        titleField.document.addDocumentListener(autoSave)
        contentArea.document.addDocumentListener(autoSave)

        listSearch.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                // Debounce so we don't re-filter/re-sort on every keystroke.
                searchAlarm.cancelAllRequests()
                searchAlarm.addRequest({
                    val text = listSearch.text
                    if (text != searchQuery) {
                        searchQuery = text
                        reloadList()
                    }
                }, 200)
            }
        })

        notesList.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showListContextMenu(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    showListContextMenu(e)
                    return
                }
                if (e.button == MouseEvent.BUTTON1 && e.clickCount == 1 &&
                    !e.isShiftDown && !e.isControlDown && !e.isMetaDown
                ) {
                    val idx = notesList.locationToIndex(e.point)
                    val bounds = if (idx >= 0) notesList.getCellBounds(idx, idx) else null
                    if (idx >= 0 && bounds != null && bounds.contains(e.point)) {
                        openDetail(listModel.get(idx))
                    }
                }
            }
        })

        notesList.registerKeyboardAction(
            { notesList.selectedValue?.let { openDetail(it) } },
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            JComponent.WHEN_FOCUSED
        )
        notesList.registerKeyboardAction(
            { deleteSelectedInList() },
            KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0),
            JComponent.WHEN_FOCUSED
        )
        notesList.registerKeyboardAction(
            { deleteSelectedInList() },
            KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0),
            JComponent.WHEN_FOCUSED
        )

        // Tool-window-scoped shortcuts: these fire only when focus is inside the
        // Easy Notes panel (WHEN_ANCESTOR_OF_FOCUSED_COMPONENT), so they never
        // clash with the IDE's global keymap. The primary modifier is Cmd on
        // macOS and Ctrl elsewhere, matching platform conventions.
        val menuMask = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        registerPanelShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_N, menuMask)) { createNote() }
        registerPanelShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_F, menuMask)) { focusSearch() }
        registerPanelShortcut(
            KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK)
        ) { if (view == View.DETAIL) selectPrevious() }
        registerPanelShortcut(
            KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK)
        ) { if (view == View.DETAIL) selectNext() }

        // Esc returns to the list, but only while a note is open, so it doesn't
        // swallow Esc (return focus to editor) when the list is already showing.
        paperPanel.registerKeyboardAction(
            { goToList() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT
        )
    }

    private fun registerPanelShortcut(keyStroke: KeyStroke, run: () -> Unit) {
        registerKeyboardAction({ run() }, keyStroke, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
    }

    private fun scheduleSave() {
        if (loading) return
        saveAlarm.cancelAllRequests()
        saveAlarm.addRequest({ saveCurrent() }, 400)
    }

    private fun saveCurrent() {
        val note = currentNote() ?: return
        var changed = false
        if (note.title != titleField.text) { note.title = titleField.text; changed = true }
        if (note.content != contentArea.text) { note.content = contentArea.text; changed = true }
        if (changed) service.touchNote(note)
    }

    private fun currentNote(): Note? = currentId?.let { service.findNote(it) }

    private fun onNotesChanged() {
        // Keep the font in sync when it is changed here or in another open panel.
        applyFont()
        if (view == View.LIST) {
            reloadList()
        } else {
            if (currentNote() == null) {
                showListView()
            } else {
                // Recompute the ordered view so the [index / total] counter (and
                // prev/next navigation) reflect notes added, removed, restored via
                // Undo, or reordered by pin/star while a note is open.
                ordered = computeOrdered()
                updateHeader()
            }
        }
    }
    // endregion

    // region ordering
    private fun computeOrdered(): List<Note> {
        var notes = service.getAllNotes()
        if (favoritesOnly) notes = notes.filter { it.favorite }
        val q = searchQuery.trim().lowercase()
        if (q.isNotEmpty()) {
            notes = notes.filter {
                it.displayTitle().lowercase().contains(q) || it.content.lowercase().contains(q)
            }
        }
        val base: Comparator<Note> = when (sortKey) {
            SortKey.TITLE -> compareBy { it.displayTitle().lowercase() }
            // FIFO: oldest created first, newest appended at the bottom.
            SortKey.CREATED -> compareBy { it.createdAt }
            SortKey.MODIFIED -> compareByDescending { it.modifiedAt }
        }
        return notes.sortedWith(compareByDescending<Note> { it.pinned }.then(base))
    }
    // endregion

    // region list view
    private fun reloadList() {
        ordered = computeOrdered()
        val selectedIds = notesList.selectedValuesList.map { it.id }.toSet()
        listModel.clear()
        ordered.forEach { listModel.addElement(it) }
        if (selectedIds.isNotEmpty()) {
            val indices = ordered.indices.filter { ordered[it].id in selectedIds }
            if (indices.isNotEmpty()) notesList.selectedIndices = indices.toIntArray()
        }
    }

    private fun showListView() {
        if (view == View.DETAIL) {
            saveAlarm.cancelAllRequests()
            saveCurrent()
        }
        // Note: we deliberately keep the last-opened note id here. Navigating back
        // to the list should not make the IDE forget which note to reopen next
        // launch; the list is the default only on a fresh install (no id yet) or
        // when that note has since been deleted (findNote fails on restore).
        view = View.LIST
        reloadList()
        (cards.layout as CardLayout).show(cards, View.LIST.name)
        SwingUtilities.invokeLater {
            if (currentId != null) {
                val idx = ordered.indexOfFirst { it.id == currentId }
                if (idx >= 0) {
                    notesList.selectedIndex = idx
                    notesList.ensureIndexIsVisible(idx)
                }
            }
            notesList.requestFocusInWindow()
        }
    }

    private fun deleteSelectedInList() {
        val selected = notesList.selectedValuesList
        if (selected.isEmpty()) return
        // No confirmation dialog: the delete is immediately reversible via the
        // "Undo" action on the notification shown by performDelete().
        performDelete(selected)
    }

    /**
     * Deletes [notes] and shows a balloon notification with an "Undo" action that
     * restores them. This is the single funnel for every delete path, so notes
     * are never lost to a stray keystroke or misclick.
     */
    private fun performDelete(notes: List<Note>) {
        if (notes.isEmpty()) return
        val snapshots = notes.map { it.copy() }
        if (notes.any { it.id == currentId }) currentId = null
        service.deleteNotes(notes.map { it.id })
        notifyUndoableDelete(snapshots)
    }

    private fun notifyUndoableDelete(snapshots: List<Note>) {
        val title = if (snapshots.size == 1) {
            "Deleted \u201C${snapshots[0].displayTitle()}\u201D"
        } else {
            "Deleted ${snapshots.size} notes"
        }
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("Easy Notes")
            .createNotification(title, NotificationType.INFORMATION)
        notification.addAction(NotificationAction.createSimpleExpiring("Undo") {
            // Restore only notes that are still missing, so pressing Undo twice or
            // re-importing in the meantime can't create duplicates.
            val toRestore = snapshots.filterNot { service.existsById(it.id) }
            if (toRestore.isNotEmpty()) service.addNotes(toRestore)
        })
        notification.notify(project)
    }

    /**
     * List-screen delete: lets the user tick the specific notes to remove in a
     * checkbox dialog (pre-checking any currently highlighted rows), rather than
     * deleting everything at once.
     */
    private fun deleteWithSelection() {
        val notes = computeOrdered()
        if (notes.isEmpty()) {
            Messages.showInfoMessage(project, "There are no notes to delete.", "Delete Notes")
            return
        }
        val preselected = notesList.selectedValuesList.map { it.id }.toSet()
        val dialog = DeleteNotesDialog(project, notes, preselected)
        if (!dialog.showAndGet()) return
        val chosen = dialog.selectedNotes()
        if (chosen.isEmpty()) return
        performDelete(chosen)
    }

    /** Deletes every note (respecting no filter), after a single confirmation. */
    fun deleteAll() {
        val all = service.getAllNotes()
        if (all.isEmpty()) {
            Messages.showInfoMessage(project, "There are no notes to delete.", "Delete All Notes")
            return
        }
        if (Messages.showYesNoDialog(
                project,
                "Delete all ${all.size} note(s)? You can undo this from the notification.",
                "Delete All Notes", "Delete All", "Cancel", Messages.getWarningIcon()
            ) != Messages.YES
        ) return
        performDelete(all)
    }

    private fun showListContextMenu(e: MouseEvent) {
        val idx = notesList.locationToIndex(e.point)
        val bounds = if (idx >= 0) notesList.getCellBounds(idx, idx) else null
        val onItem = idx >= 0 && bounds != null && bounds.contains(e.point)
        if (onItem && !notesList.isSelectedIndex(idx)) {
            notesList.selectedIndex = idx
        }

        val selected = notesList.selectedValuesList
        val group = DefaultActionGroup()
        if (selected.size == 1) {
            group.add(action("Open") { openDetail(selected[0]) })
        }
        if (selected.isNotEmpty()) {
            val label = if (selected.size == 1) "Delete" else "Delete ${selected.size} Selected"
            group.add(action(label) { deleteSelectedInList() })
        }
        if (!service.isEmpty()) {
            if (group.childrenCount > 0) group.addSeparator()
            group.add(action("Delete All Notes...") { deleteAll() })
        }
        if (group.childrenCount == 0) return

        JBPopupFactory.getInstance().createActionGroupPopup(
            null, group, DataContext.EMPTY_CONTEXT,
            JBPopupFactory.ActionSelectionAid.MNEMONICS, true
        ).show(RelativePoint(e))
    }
    // endregion

    // region detail view
    private fun openDetail(note: Note) {
        ordered = computeOrdered()
        currentId = note.id
        loadNote(note)
        view = View.DETAIL
        (cards.layout as CardLayout).show(cards, View.DETAIL.name)
        SwingUtilities.invokeLater { contentArea.requestFocusInWindow() }
    }

    private fun loadNote(note: Note?) {
        loading = true
        try {
            currentId = note?.id
            if (note == null) {
                titleField.text = ""
                contentArea.text = ""
                applyPaperColor(Note.DEFAULT_COLOR_RGB)
                updateHeader()
                return
            }
            titleField.text = note.title
            contentArea.text = note.content
            contentArea.caretPosition = 0
            applyPaperColor(note.colorRgb)
            updateHeader()
            // Remember exactly which note is on screen so it reopens on the next
            // IDE launch. This lives here (not in openDetail) so it also tracks
            // Previous/Next navigation, which loads notes without reopening them.
            service.setLastOpenedNoteId(note.id)
        } finally {
            loading = false
        }
    }

    private fun updateHeader() {
        val note = currentNote()
        val total = ordered.size
        val index = if (note == null) 0 else ordered.indexOfFirst { it.id == note.id } + 1
        val stamp = note?.let { dateFormat.format(Date(it.modifiedAt)) } ?: "No note"
        val pin = if (note?.pinned == true) "  \uD83D\uDCCC" else ""
        val star = if (note?.favorite == true) "  \u2605" else ""
        indexLabel.text = "<html><b>[$index / $total]</b>&nbsp;&nbsp;$stamp$pin$star</html>"
    }

    private fun applyPaperColor(rgb: Int) {
        val paper = Color(rgb)
        val fg = Color(0x2B2B2B)
        contentArea.paperColor = paper
        contentArea.foreground = fg
        contentArea.caretColor = fg
        contentScroll.viewport.background = paper
        contentScroll.background = paper
        titleField.background = paper
        titleField.foreground = fg
        titleField.caretColor = fg
        paperPanel.background = paper
        paperPanel.repaint()
    }

    private fun moveBy(delta: Int) {
        if (view != View.DETAIL) return
        saveAlarm.cancelAllRequests()
        saveCurrent()
        ordered = computeOrdered()
        if (ordered.isEmpty()) { showListView(); return }
        val cur = ordered.indexOfFirst { it.id == currentId }
        val next = when {
            cur < 0 -> if (delta > 0) 0 else ordered.size - 1
            else -> (cur + delta).coerceIn(0, ordered.size - 1)
        }
        loadNote(ordered[next])
        contentArea.requestFocusInWindow()
    }
    // endregion

    // region public actions (invoked by IDE actions)
    fun createNote() {
        if (view == View.DETAIL) { saveAlarm.cancelAllRequests(); saveCurrent() }
        searchQuery = ""
        listSearch.text = ""
        favoritesOnly = false
        val note = service.createNote()
        openDetail(note)
        titleField.requestFocusInWindow()
        titleField.selectAll()
    }

    fun deleteContextual() {
        if (view == View.LIST) {
            deleteWithSelection()
            return
        }
        val note = currentNote() ?: return
        // No confirmation dialog: the delete is immediately reversible via the
        // "Undo" action on the notification shown by performDelete().
        saveAlarm.cancelAllRequests()
        ordered = computeOrdered()
        val idx = ordered.indexOfFirst { it.id == note.id }
        val neighbor = ordered.getOrNull(idx + 1) ?: ordered.getOrNull(idx - 1)
        performDelete(listOf(note))
        if (neighbor != null && neighbor.id != note.id) {
            openDetail(neighbor)
        } else {
            currentId = null
            showListView()
        }
    }

    fun selectNext() = moveBy(1)

    fun selectPrevious() = moveBy(-1)

    /** Toggle to the list view (the toolbar list/back button). */
    fun goToList() = showListView()

    fun focusSearch() {
        showListView()
        SwingUtilities.invokeLater { listSearch.requestFocusInWindow() }
    }

    fun isDetailView(): Boolean = view == View.DETAIL

    fun hasNotesForNav(): Boolean = view == View.DETAIL && ordered.isNotEmpty()

    fun canDelete(): Boolean =
        if (view == View.LIST) !service.isEmpty() else currentNote() != null
    // endregion

    // region "more" menu
    fun showMoreMenu(anchor: JComponent) {
        val note = if (view == View.DETAIL) currentNote() else null
        val group = DefaultActionGroup()

        if (note != null) {
            group.add(action(if (note.pinned) "Unpin" else "Pin") {
                note.pinned = !note.pinned; service.touchNote(note); updateHeader()
            })
            group.add(action(if (note.favorite) "Remove Star" else "Add Star") {
                note.favorite = !note.favorite; service.touchNote(note); updateHeader()
            })
            group.add(action("Change Color...") { showColorPopup(anchor) })
            group.addSeparator()
        }

        group.add(action((if (favoritesOnly) "\u2713 " else "") + "Favorites Only") {
            favoritesOnly = !favoritesOnly
            if (view == View.LIST) reloadList()
        })
        val sortGroup = DefaultActionGroup("Sort By", true)
        for (key in SortKey.values()) {
            sortGroup.add(action((if (sortKey == key) "\u2713 " else "") + key.label) {
                sortKey = key
                if (view == View.LIST) reloadList()
            })
        }
        group.add(sortGroup)
        group.addSeparator()
        group.add(action("Font...") { showFontDialog() })
        group.addSeparator()
        group.add(action("Import Notes...") { importNotes() })
        group.add(action("Export Notes...") { exportNotes() })
        if (!service.isEmpty()) {
            group.addSeparator()
            group.add(action("Delete All Notes...") { deleteAll() })
        }

        JBPopupFactory.getInstance().createActionGroupPopup(
            "Notes Options", group, DataContext.EMPTY_CONTEXT,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false
        ).showUnderneathOf(anchor)
    }

    private fun action(text: String, run: () -> Unit): DumbAwareAction =
        object : DumbAwareAction(text) {
            override fun actionPerformed(e: AnActionEvent) = run()
        }
    // endregion

    // region color
    private fun showColorPopup(anchor: JComponent) {
        val note = currentNote() ?: return
        val grid = JPanel(GridLayout(0, 6, 6, 6))
        grid.border = JBUI.Borders.empty(8)
        val popupRef = arrayOfNulls<JBPopup>(1)

        for (rgb in predefinedColors) {
            val swatch = JButton()
            swatch.preferredSize = Dimension(28, 28)
            swatch.background = Color(rgb)
            swatch.isOpaque = true
            swatch.border = BorderFactory.createLineBorder(JBColor.border())
            swatch.toolTipText = NotesIO.colorToHex(rgb)
            swatch.addActionListener {
                applyColor(note, rgb)
                popupRef[0]?.cancel()
            }
            grid.add(swatch)
        }

        val custom = JButton("Custom...")
        custom.addActionListener {
            popupRef[0]?.cancel()
            val picked = JColorChooser.showDialog(this, "Choose Note Color", Color(note.colorRgb))
            if (picked != null) applyColor(note, picked.rgb and 0xFFFFFF)
        }

        val wrapper = JPanel(BorderLayout())
        wrapper.add(grid, BorderLayout.CENTER)
        wrapper.add(custom, BorderLayout.SOUTH)

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(wrapper, grid)
            .setRequestFocus(true)
            .setTitle("Note Color")
            .createPopup()
        popupRef[0] = popup
        popup.showUnderneathOf(anchor)
    }

    private fun applyColor(note: Note, rgb: Int) {
        note.colorRgb = rgb
        applyPaperColor(rgb)
        service.touchNote(note)
    }
    // endregion

    // region font
    /**
     * Applies the globally-configured note font (family and size) to the title
     * and body of every note, falling back to the IDE defaults for whichever of
     * family/size the user hasn't overridden.
     */
    private fun applyFont() {
        val family = service.getFontFamily()
        val size = service.getFontSize()
        if (family == null && size == null) {
            titleField.font = defaultTitleFont
            contentArea.font = defaultContentFont
        } else {
            val bodySize = size ?: defaultContentFont.size
            val bodyFamily = family ?: defaultContentFont.family
            val titleFamily = family ?: defaultTitleFont.family
            contentArea.font = Font(bodyFamily, Font.PLAIN, bodySize)
            titleField.font = Font(titleFamily, Font.BOLD, bodySize + 2)
        }
        contentArea.revalidate()
        contentArea.repaint()
        titleField.revalidate()
        titleField.repaint()
    }

    private fun showFontDialog() {
        val current = FontChoice(
            family = service.getFontFamily() ?: defaultContentFont.family,
            size = service.getFontSize() ?: defaultContentFont.size
        )
        val dialog = FontSettingsDialog(project, current)
        if (!dialog.showAndGet()) return
        val chosen = dialog.selectedFont()
        service.setFont(chosen.family, chosen.size)
        applyFont()
    }
    // endregion

    // region import / export
    fun importNotes() {
        val descriptor: FileChooserDescriptor =
            FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor()
                .withTitle("Import Notes")
                .withDescription("Select JSON or Markdown note files")
                .withFileFilter { it.extension == "json" || it.extension == "md" || it.extension == "markdown" }
        val files = FileChooser.chooseFiles(descriptor, project, null)
        if (files.isEmpty()) return

        val incoming = ArrayList<Note>()
        for (vf in files) {
            val file = File(vf.path)
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            when (vf.extension?.lowercase()) {
                "json" -> incoming.addAll(runCatching { NotesIO.parseJson(text) }.getOrDefault(emptyList()))
                "md", "markdown" -> runCatching {
                    NotesIO.parseMarkdown(text, file.nameWithoutExtension)
                }.getOrNull()?.let { incoming.add(it) }
            }
        }

        if (incoming.isEmpty()) {
            Messages.showWarningDialog(project, "No valid notes were found in the selected files.", "Import Notes")
            return
        }

        var policy = ImportConflictPolicy.KEEP_BOTH
        if (NotesIO.hasConflicts(service, incoming)) {
            val choice = Messages.showDialog(
                project,
                "Some imported notes already exist. How should conflicts be handled?",
                "Import Conflicts",
                arrayOf("Keep Both", "Overwrite", "Skip"),
                0,
                Messages.getQuestionIcon()
            )
            policy = when (choice) {
                1 -> ImportConflictPolicy.OVERWRITE
                2 -> ImportConflictPolicy.SKIP
                else -> ImportConflictPolicy.KEEP_BOTH
            }
        }

        val summary = NotesIO.importNotes(service, incoming, policy)
        reloadList()
        Messages.showInfoMessage(
            project,
            "Imported ${summary.total} note(s):\n" +
                "Added: ${summary.added}, Overwritten: ${summary.overwritten}, " +
                "Kept both: ${summary.renamed}, Skipped: ${summary.skipped}",
            "Import Complete"
        )
    }

    fun exportNotes() {
        val all = service.getAllNotes()
        if (all.isEmpty()) {
            Messages.showInfoMessage(project, "There are no notes to export.", "Export Notes")
            return
        }
        val current = if (view == View.DETAIL) currentNote() else null
        val selected = if (view == View.LIST) notesList.selectedValuesList else emptyList()

        val options = buildList {
            if (current != null) {
                add("Export current note as JSON")
                add("Export current note as Markdown")
            }
            if (selected.isNotEmpty()) {
                add("Export selected (${selected.size}) as JSON")
                add("Export selected (${selected.size}) as Markdown")
            }
            add("Export all (${all.size}) as JSON")
            add("Export all (${all.size}) as Markdown")
        }

        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(options)
            .setTitle("Export Notes")
            .setItemChosenCallback { choice ->
                val notes = when {
                    choice.startsWith("Export current") && current != null -> listOf(current)
                    choice.startsWith("Export selected") -> selected
                    else -> all
                }
                if (choice.endsWith("JSON")) exportJson(notes) else exportMarkdown(notes)
            }
            .createPopup()
            .showUnderneathOf(this)
    }

    private fun exportJson(notes: List<Note>) {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle("Choose Export Folder")
        val dir = FileChooser.chooseFile(descriptor, project, null) ?: return
        val fileName = if (notes.size == 1) sanitize(notes[0].displayTitle()) + ".json" else "notes-backup.json"
        val target = File(dir.path, fileName)
        try {
            target.writeText(NotesIO.toJsonBackup(notes))
            Messages.showInfoMessage(project, "Exported ${notes.size} note(s) to\n${target.path}", "Export Complete")
        } catch (e: IOException) {
            Messages.showErrorDialog(project, "Could not write ${target.path}:\n${e.message}", "Export Failed")
        }
    }

    private fun exportMarkdown(notes: List<Note>) {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle("Choose Export Folder")
        val dir = FileChooser.chooseFile(descriptor, project, null) ?: return
        val used = HashSet<String>()
        try {
            for (note in notes) {
                val name = sanitize(note.displayTitle())
                var candidate = "$name.md"
                var i = 1
                while (!used.add(candidate)) {
                    candidate = "$name-${i++}.md"
                }
                File(dir.path, candidate).writeText(NotesIO.noteToMarkdown(note))
            }
            Messages.showInfoMessage(project, "Exported ${notes.size} note(s) to\n${dir.path}", "Export Complete")
        } catch (e: IOException) {
            Messages.showErrorDialog(project, "Could not write to ${dir.path}:\n${e.message}", "Export Failed")
        }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9-_ ]"), "_").trim().ifEmpty { "note" }.take(80)
    // endregion

    override fun uiDataSnapshot(sink: DataSink) {
        sink[NOTES_PANEL_KEY] = this
    }

    fun dispose() {
        // Flush any pending edit so nothing is lost when the tool window closes.
        saveAlarm.cancelAllRequests()
        if (view == View.DETAIL) saveCurrent()
        service.removeChangeListener(serviceListener)
        Disposer.dispose(disposable)
    }

    companion object {
        val NOTES_PANEL_KEY: DataKey<NotesPanel> = DataKey.create("com.snagar.easynotes.panel")

        fun from(e: AnActionEvent): NotesPanel? = e.getData(NOTES_PANEL_KEY)
    }
}

/**
 * A checkbox dialog for choosing which specific notes to delete from the list
 * screen. OK ("Delete") stays disabled until at least one note is checked.
 */
private class DeleteNotesDialog(
    project: Project?,
    private val notes: List<Note>,
    preselected: Set<String>
) : DialogWrapper(project) {

    private val checkBoxList = CheckBoxList<Note>()

    init {
        title = "Delete Notes"
        setOKButtonText("Delete")
        notes.forEach { checkBoxList.addItem(it, it.displayTitle(), it.id in preselected) }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        val hint = JBLabel("Select the notes you want to delete:")
        hint.border = JBUI.Borders.emptyBottom(6)
        panel.add(hint, BorderLayout.NORTH)
        val scroll = JBScrollPane(checkBoxList)
        scroll.preferredSize = Dimension(360, 320)
        panel.add(scroll, BorderLayout.CENTER)
        return panel
    }

    fun selectedNotes(): List<Note> = notes.filter { checkBoxList.isItemSelected(it) }

    override fun doValidate(): ValidationInfo? =
        if (notes.none { checkBoxList.isItemSelected(it) }) {
            ValidationInfo("Select at least one note to delete.")
        } else {
            null
        }
}

/** The font family + base size chosen for all notes. */
internal data class FontChoice(val family: String, val size: Int)

/**
 * A dialog to pick the font family and size applied to every note. Shows a live
 * preview so the effect is visible before committing.
 */
private class FontSettingsDialog(
    project: Project?,
    private val initial: FontChoice
) : DialogWrapper(project) {

    private val families: Array<String> =
        GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames

    private val familyCombo = JComboBox(families)
    private val sizeCombo = JComboBox(FONT_SIZES.toTypedArray())
    private val preview = JBLabel()

    init {
        title = "Note Font"
        familyCombo.selectedItem = initial.family.takeIf { families.contains(it) } ?: families.firstOrNull()
        sizeCombo.selectedItem = initial.size
        sizeCombo.isEditable = true
        familyCombo.addActionListener { updatePreview() }
        sizeCombo.addActionListener { updatePreview() }
        init()
        updatePreview()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 10))
        panel.preferredSize = Dimension(380, 200)

        val controls = JPanel(GridLayout(2, 2, 8, 8))
        controls.add(JBLabel("Font:"))
        controls.add(familyCombo)
        controls.add(JBLabel("Size:"))
        controls.add(sizeCombo)
        panel.add(controls, BorderLayout.NORTH)

        preview.text = "The quick brown fox jumps over the lazy dog."
        preview.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.border()),
            JBUI.Borders.empty(10)
        )
        preview.verticalAlignment = JBLabel.CENTER
        panel.add(preview, BorderLayout.CENTER)
        return panel
    }

    private fun currentSize(): Int {
        val raw = sizeCombo.selectedItem
        val value = when (raw) {
            is Int -> raw
            else -> raw?.toString()?.trim()?.toIntOrNull() ?: initial.size
        }
        return value.coerceIn(6, 96)
    }

    private fun updatePreview() {
        val family = familyCombo.selectedItem as? String ?: initial.family
        preview.font = Font(family, Font.PLAIN, currentSize())
    }

    fun selectedFont(): FontChoice =
        FontChoice(familyCombo.selectedItem as? String ?: initial.family, currentSize())

    private companion object {
        val FONT_SIZES = listOf(8, 9, 10, 11, 12, 13, 14, 16, 18, 20, 22, 24, 28, 32, 36)
    }
}
