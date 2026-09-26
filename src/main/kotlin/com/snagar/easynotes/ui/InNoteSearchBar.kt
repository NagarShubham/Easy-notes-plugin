package com.snagar.easynotes.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.snagar.easynotes.search.MatchRange
import com.snagar.easynotes.search.TextSearch
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Point
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.text.BadLocationException
import javax.swing.text.DefaultHighlighter
import javax.swing.text.JTextComponent
import javax.swing.event.DocumentEvent
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A find bar for searching *within* a single note's body.
 *
 * Design highlights (mapping to the product requirements):
 *  - **Debounced live search** ([DEBOUNCE_MS]) so highlighting keeps up with
 *    typing without re-scanning on every keystroke.
 *  - **Safe highlighting**: matches are drawn with the Swing [DefaultHighlighter]
 *    over document offsets, so no markup is ever injected into the text and the
 *    underlying component/document is never mutated (the Swing analogue of
 *    avoiding DOM/XSS breakage in a web view).
 *  - **Next/previous navigation** with a `"3 of 12"` style counter and wrap-around.
 *  - **Smooth auto-scroll** that animates the viewport to center the active match.
 *
 * The bar is hidden by default; [open] shows and focuses it (optionally
 * pre-filled), and Esc / the close button hides it and clears highlights.
 */
class InNoteSearchBar(
    private val target: JTextComponent,
    private val onClose: () -> Unit,
    disposable: Disposable,
) : JPanel(BorderLayout()) {

    private val field = SearchTextField()
    private val counter = JBLabel()
    private val prevButton = iconButton(AllIcons.Actions.PreviousOccurence, "Previous match (Shift+Enter)")
    private val nextButton = iconButton(AllIcons.Actions.NextOccurence, "Next match (Enter)")
    private val closeButton = iconButton(AllIcons.Actions.Close, "Close (Esc)")

    private val searchAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)

    private val allPainter = DefaultHighlighter.DefaultHighlightPainter(HL_ALL)
    private val activePainter = DefaultHighlighter.DefaultHighlightPainter(HL_ACTIVE)

    private var matches: List<MatchRange> = emptyList()
    private var activeIndex: Int = -1
    private val highlightTags = ArrayList<Any>()

    private var scrollTimer: Timer? = null

    init {
        border = JBUI.Borders.empty(4, 8)
        isOpaque = false

        field.textEditor.emptyText.text = "Find in note"

        val controls = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            counter.border = JBUI.Borders.empty(0, 6)
            add(counter)
            add(prevButton)
            add(nextButton)
            add(closeButton)
        }

        add(field, BorderLayout.CENTER)
        add(controls, BorderLayout.EAST)

        wire()
        updateCounter()
    }

    // Keep the bar a single row tall inside a vertical BoxLayout stack.
    override fun getMaximumSize(): Dimension =
        Dimension(Int.MAX_VALUE, super.getPreferredSize().height)

    private fun wire() {
        field.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = scheduleSearch()
        })

        // Enter / Shift+Enter navigate; Esc closes. Registered on the inner
        // editor so they fire while the field has focus and are consumed before
        // the panel-level Esc (which returns to the list).
        val editor = field.textEditor
        editor.registerKeyboardAction(
            { navigate(1) },
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            JComponent.WHEN_FOCUSED,
        )
        editor.registerKeyboardAction(
            { navigate(-1) },
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK),
            JComponent.WHEN_FOCUSED,
        )
        editor.registerKeyboardAction(
            { close() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_FOCUSED,
        )

        prevButton.addActionListener { navigate(-1) }
        nextButton.addActionListener { navigate(1) }
        closeButton.addActionListener { close() }
    }

    /** The current query text (used to preserve context across prev/next note nav). */
    fun currentQuery(): String = field.text

    /**
     * Shows the bar, pre-filling [query] (or keeping the existing text when
     * [query] is null), selects the text for quick replacement, focuses the
     * field, and runs the search immediately so highlights/scroll appear at once.
     */
    fun open(query: String? = null) {
        if (query != null) field.text = query
        isVisible = true
        revalidate()
        repaint()
        SwingUtilities.invokeLater {
            field.textEditor.requestFocusInWindow()
            field.textEditor.selectAll()
            runSearch(immediate = true)
        }
    }

    /** Hides the bar, clears highlights, and returns focus to the editor. */
    fun close() {
        searchAlarm.cancelAllRequests()
        clearHighlights()
        matches = emptyList()
        activeIndex = -1
        isVisible = false
        revalidate()
        repaint()
        onClose()
    }

    /** Hides and clears without moving focus (used when switching views). */
    fun closeQuietly() {
        searchAlarm.cancelAllRequests()
        clearHighlights()
        matches = emptyList()
        activeIndex = -1
        isVisible = false
    }

    /** Re-runs the search against the (possibly changed) target text, if visible. */
    fun refresh() {
        if (isVisible) runSearch(immediate = true)
    }

    private fun scheduleSearch() {
        searchAlarm.cancelAllRequests()
        searchAlarm.addRequest({ runSearch() }, DEBOUNCE_MS)
    }

    private fun runSearch(immediate: Boolean = false) {
        if (immediate) searchAlarm.cancelAllRequests()
        val query = field.text
        matches = TextSearch.findMatches(target.text, query)

        if (matches.isEmpty()) {
            activeIndex = -1
            applyHighlights()
            updateCounter()
            return
        }

        // Prefer the first match at/after the caret so navigation feels anchored
        // to where the user is, wrapping to the first match otherwise.
        val caret = target.caretPosition
        val fromCaret = matches.indexOfFirst { it.start >= caret }
        activeIndex = if (fromCaret >= 0) fromCaret else 0

        applyHighlights()
        updateCounter()
        scrollToActive()
    }

    private fun navigate(delta: Int) {
        if (matches.isEmpty()) {
            runSearch(immediate = true)
            return
        }
        val size = matches.size
        activeIndex = ((activeIndex + delta) % size + size) % size // wrap-around
        applyHighlights()
        updateCounter()
        scrollToActive()
    }

    private fun applyHighlights() {
        clearHighlights()
        val highlighter = target.highlighter
        matches.forEachIndexed { i, m ->
            val painter = if (i == activeIndex) activePainter else allPainter
            try {
                highlightTags.add(highlighter.addHighlight(m.start, m.end, painter))
            } catch (_: BadLocationException) {
                // Text changed underneath us; the next search pass will re-sync.
            }
        }
    }

    private fun clearHighlights() {
        val highlighter = target.highlighter
        for (tag in highlightTags) highlighter.removeHighlight(tag)
        highlightTags.clear()
    }

    private fun updateCounter() {
        val query = field.text.trim()
        when {
            query.isEmpty() -> {
                counter.text = ""
                counter.foreground = JBColor.foreground()
            }
            matches.isEmpty() -> {
                counter.text = "No results"
                counter.foreground = JBColor.namedColor("Label.errorForeground", JBColor.RED)
            }
            else -> {
                counter.text = "${activeIndex + 1} of ${matches.size}"
                counter.foreground = JBColor.foreground()
            }
        }
        val hasMatches = matches.isNotEmpty()
        prevButton.isEnabled = hasMatches
        nextButton.isEnabled = hasMatches
    }

    /** Animates the viewport so the active match is centered and clearly visible. */
    private fun scrollToActive() {
        val m = matches.getOrNull(activeIndex) ?: return
        val viewport = SwingUtilities.getAncestorOfClass(JViewport::class.java, target) as? JViewport
            ?: return
        val rect = try {
            val start = target.modelToView2D(m.start) ?: return
            val end = target.modelToView2D(m.end) ?: return
            start.createUnion(end)
        } catch (_: BadLocationException) {
            return
        }

        val viewH = viewport.extentSize.height
        val maxY = max(0, target.height - viewH)
        val targetY = (rect.y - (viewH - rect.height) / 2.0).roundToInt().coerceIn(0, maxY)
        smoothScrollTo(viewport, targetY)
    }

    private fun smoothScrollTo(viewport: JViewport, targetY: Int) {
        scrollTimer?.stop()
        val startY = viewport.viewPosition.y
        if (startY == targetY) return

        val startTime = System.currentTimeMillis()
        val timer = Timer(SCROLL_STEP_MS) { e ->
            val t = ((System.currentTimeMillis() - startTime).toFloat() / SCROLL_DURATION_MS)
                .coerceIn(0f, 1f)
            val eased = easeInOutQuad(t)
            val y = (startY + (targetY - startY) * eased).roundToInt()
            viewport.viewPosition = Point(0, y)
            if (t >= 1f) (e.source as Timer).stop()
        }
        timer.isRepeats = true
        scrollTimer = timer
        timer.start()
    }

    private fun easeInOutQuad(t: Float): Float =
        if (t < 0.5f) 2f * t * t else -1f + (4f - 2f * t) * t

    private fun iconButton(icon: javax.swing.Icon, tooltip: String): JButton =
        JButton(icon).apply {
            toolTipText = tooltip
            isOpaque = false
            isContentAreaFilled = false
            isBorderPainted = false
            border = JBUI.Borders.empty(2)
            isFocusable = false
            val size = Dimension(icon.iconWidth + 8, icon.iconHeight + 8)
            preferredSize = size
            minimumSize = size
        }

    companion object {
        private const val DEBOUNCE_MS = 200
        private const val SCROLL_STEP_MS = 12
        private const val SCROLL_DURATION_MS = 180f

        // The note editor is a "legal pad": it always paints dark ink on a
        // light paper color, in both light and dark IDE themes. So highlights
        // must stay light (never dark) or the dark ink becomes unreadable — hence
        // the same light color is used for both JBColor variants.
        //  - all matches: a soft, clear yellow;
        //  - active match: a brighter amber that stands out while staying legible.
        private val HL_ALL = JBColor(java.awt.Color(0xFFF176), java.awt.Color(0xFFF176))
        private val HL_ACTIVE = JBColor(java.awt.Color(0xFFC64B), java.awt.Color(0xFFC64B))
    }
}
