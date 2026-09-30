package com.snagar.easynotes.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Unit tests for the pure [TextSearch] helpers. */
class TextSearchTest {

    // region findMatches
    @Test
    fun `finds all case-insensitive occurrences`() {
        val matches = TextSearch.findMatches("Foo foo FOO", "foo")
        assertEquals(listOf(MatchRange(0, 3), MatchRange(4, 7), MatchRange(8, 11)), matches)
    }

    @Test
    fun `matches are non-overlapping`() {
        // "aa" in "aaaa" should match at 0..2 and 2..4, not 1..3.
        assertEquals(listOf(MatchRange(0, 2), MatchRange(2, 4)), TextSearch.findMatches("aaaa", "aa"))
    }

    @Test
    fun `blank query and empty text yield no matches`() {
        assertTrue(TextSearch.findMatches("hello", "   ").isEmpty())
        assertTrue(TextSearch.findMatches("", "x").isEmpty())
    }

    @Test
    fun `count matches`() {
        assertEquals(3, TextSearch.countMatches("a-a-a", "a"))
        assertEquals(0, TextSearch.countMatches("abc", "z"))
    }
    // endregion

    // region score / ranking
    @Test
    fun `exact title beats prefix beats contains beats body-only`() {
        val exact = TextSearch.score("todo", "irrelevant body", "todo")
        val prefix = TextSearch.score("todo list", "irrelevant body", "todo")
        val contains = TextSearch.score("my todo list", "irrelevant body", "todo")
        val bodyOnly = TextSearch.score("something else", "a todo here", "todo")

        assertTrue(exact > prefix, "exact($exact) > prefix($prefix)")
        assertTrue(prefix > contains, "prefix($prefix) > contains($contains)")
        assertTrue(contains > bodyOnly, "contains($contains) > bodyOnly($bodyOnly)")
        assertTrue(bodyOnly > 0)
    }

    @Test
    fun `non-matching note scores zero`() {
        assertEquals(0, TextSearch.score("alpha", "beta", "gamma"))
    }

    @Test
    fun `body frequency adds a bonus`() {
        val once = TextSearch.score("title", "cat", "cat")
        val many = TextSearch.score("title", "cat cat cat", "cat")
        assertTrue(many > once)
    }
    // endregion

    // region snippet
    @Test
    fun `snippet centers on first match with ellipses`() {
        val content = "x".repeat(100) + "needle" + "y".repeat(100)
        val snippet = TextSearch.snippet(content, "needle", radius = 5)!!
        assertTrue(snippet.startsWith("\u2026"))
        assertTrue(snippet.endsWith("\u2026"))
        assertTrue(snippet.contains("needle"))
    }

    @Test
    fun `snippet returns null when body has no match`() {
        assertNull(TextSearch.snippet("nothing here", "zzz"))
    }

    @Test
    fun `snippet collapses newlines to spaces`() {
        val snippet = TextSearch.snippet("line1\nkey\nline2", "key", radius = 10)!!
        assertFalse(snippet.contains('\n'))
    }
    // endregion

    // region html safety
    @Test
    fun `escapes html special characters`() {
        assertEquals(
            "&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;",
            TextSearch.escapeHtml("<script>alert('x')</script>"),
        )
    }

    @Test
    fun `highlightHtml wraps matches and escapes surrounding text`() {
        val html = TextSearch.highlightHtml("<b>cat</b>", "cat")
        // The literal angle brackets must be escaped...
        assertTrue(html.contains("&lt;b&gt;"))
        // ...and the match wrapped in a highlight span.
        assertTrue(html.contains("<span"))
        assertTrue(html.contains(">cat</span>"))
        // No raw, unescaped tag from the source text leaks through.
        assertFalse(html.contains("<b>"))
    }

    @Test
    fun `highlightHtml with no match is just escaped text`() {
        assertEquals("a &amp; b", TextSearch.highlightHtml("a & b", "zzz"))
    }
    // endregion
}
