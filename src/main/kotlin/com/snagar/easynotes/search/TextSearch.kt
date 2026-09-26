package com.snagar.easynotes.search

/** A half-open match range `[start, end)` (character offsets) within some text. */
data class MatchRange(val start: Int, val end: Int)

/**
 * Pure, dependency-free text search helpers shared by the in-note editor search
 * and the global list search.
 *
 * Everything here is a plain function of its inputs (no UI, no state), so it is
 * cheap to unit-test and safe to call from a background thread. Matching is
 * literal (not regex) and case-insensitive, which is what users expect from a
 * notepad "find".
 */
object TextSearch {

    /**
     * Finds every non-overlapping, case-insensitive occurrence of [query] in
     * [text]. Because matching compares character-by-character, each returned
     * range has exactly `query.length` characters, so callers can safely map the
     * ranges back onto the original [text].
     */
    fun findMatches(text: String, query: String): List<MatchRange> {
        val q = query.trim()
        if (q.isEmpty() || text.isEmpty()) return emptyList()
        val result = ArrayList<MatchRange>()
        var from = 0
        while (from <= text.length) {
            val idx = text.indexOf(q, from, ignoreCase = true)
            if (idx < 0) break
            result.add(MatchRange(idx, idx + q.length))
            // Advance past this match so occurrences never overlap. Guard against
            // a zero-length query (already excluded above) to avoid an infinite loop.
            from = idx + q.length
        }
        return result
    }

    /** Number of non-overlapping, case-insensitive occurrences of [query] in [text]. */
    fun countMatches(text: String, query: String): Int = findMatches(text, query).size

    /**
     * Relevance score for a note given already-lowercased [titleLower] /
     * [contentLower] and a lowercased, trimmed [queryLower]. Higher is better.
     *
     * The intent (per product spec) is: title matches outrank body matches, with
     * exact/prefix title hits ranked highest, and a small bonus for how often the
     * term appears in the body. Returns 0 when the note doesn't match at all, so
     * callers can use `score > 0` as the filter predicate.
     */
    fun score(titleLower: String, contentLower: String, queryLower: String): Int {
        if (queryLower.isEmpty()) return 0
        var score = 0

        val titleIdx = titleLower.indexOf(queryLower)
        when {
            titleIdx < 0 -> Unit
            titleLower.length == queryLower.length -> score += 1000 // exact title
            titleIdx == 0 -> score += 600                           // title starts with
            else -> score += 400                                    // title contains
        }

        val bodyHits = countMatches(contentLower, queryLower)
        if (bodyHits > 0) {
            score += 100                          // body contains at all
            score += bodyHits.coerceAtMost(20)    // frequency bonus, capped
        }
        return score
    }

    /**
     * A short single-line preview of [content] centered on the first occurrence
     * of [query], with ellipses where text was trimmed. Returns `null` when the
     * body contains no match (so the caller can fall back to its normal
     * subtitle). Newlines are collapsed to spaces for a compact one-line preview.
     */
    fun snippet(content: String, query: String, radius: Int = 40): String? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val idx = content.indexOf(q, ignoreCase = true)
        if (idx < 0) return null

        val start = (idx - radius).coerceAtLeast(0)
        val end = (idx + q.length + radius).coerceAtMost(content.length)
        val core = content.substring(start, end)
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()

        return buildString {
            if (start > 0) append("\u2026")
            append(core)
            if (end < content.length) append("\u2026")
        }
    }

    /**
     * Escapes [text] for safe inclusion in Swing HTML (`JLabel`/`JBLabel`).
     *
     * Swing renders any string starting with `<html>` as HTML, so unescaped note
     * content could otherwise break the label layout or inject markup. This is
     * the Swing analogue of guarding against HTML/XSS injection in a web view.
     */
    fun escapeHtml(text: String): String {
        val sb = StringBuilder(text.length + 16)
        for (c in text) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * Builds an HTML fragment (no surrounding `<html>` tag) where each
     * case-insensitive occurrence of [query] in [text] is wrapped in a
     * highlighted span. All non-match text is HTML-escaped, so the result is safe
     * to assign to a Swing label.
     *
     * [highlightBg] / [highlightFg] are `#RRGGBB` colors for the highlight span;
     * defaults are a readable amber-on-black that stays legible whether or not
     * the list row is selected.
     */
    fun highlightHtml(
        text: String,
        query: String,
        highlightBg: String = "#FFF176",
        highlightFg: String = "#1A1A1A",
    ): String {
        val matches = findMatches(text, query)
        if (matches.isEmpty()) return escapeHtml(text)

        val sb = StringBuilder(text.length + matches.size * 48)
        var cursor = 0
        for (m in matches) {
            if (m.start > cursor) sb.append(escapeHtml(text.substring(cursor, m.start)))
            sb.append("<span style='background-color:").append(highlightBg)
                .append(";color:").append(highlightFg).append(";'>")
            sb.append(escapeHtml(text.substring(m.start, m.end)))
            sb.append("</span>")
            cursor = m.end
        }
        if (cursor < text.length) sb.append(escapeHtml(text.substring(cursor)))
        return sb.toString()
    }
}
