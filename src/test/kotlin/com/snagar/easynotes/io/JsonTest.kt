package com.snagar.easynotes.io

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Unit tests for the in-house [Json] reader/writer. */
class JsonTest {

    // region parse: value types
    @Test
    fun `parses an object with mixed value types`() {
        val obj = Json.parse(
            """{"i":1,"d":2.5,"b":true,"n":null,"s":"x","a":[1,2,3]}"""
        ) as Map<*, *>

        assertEquals(1L, obj["i"])
        assertEquals(2.5, obj["d"])
        assertEquals(true, obj["b"])
        assertNull(obj["n"])
        assertEquals("x", obj["s"])
        assertEquals(listOf(1L, 2L, 3L), obj["a"])
    }

    @Test
    fun `integers parse as Long and decimals as Double`() {
        assertEquals(42L, Json.parse("42"))
        assertEquals(-7L, Json.parse("-7"))
        assertEquals(3.14, Json.parse("3.14"))
    }

    @Test
    fun `exponent notation parses as Double`() {
        assertEquals(1000.0, Json.parse("1e3"))
        assertEquals(0.0015, Json.parse("1.5e-3"))
    }

    @Test
    fun `booleans and null parse`() {
        assertEquals(true, Json.parse("true"))
        assertEquals(false, Json.parse("false"))
        assertNull(Json.parse("null"))
    }

    @Test
    fun `nested containers parse`() {
        val root = Json.parse("""{"a":{"b":[true,null,{"c":1}]}}""") as Map<*, *>
        val a = root["a"] as Map<*, *>
        val b = a["b"] as List<*>

        assertEquals(true, b[0])
        assertNull(b[1])
        assertEquals(1L, (b[2] as Map<*, *>)["c"])
    }

    @Test
    fun `empty object and array parse`() {
        assertTrue((Json.parse("{}") as Map<*, *>).isEmpty())
        assertTrue((Json.parse("[]") as List<*>).isEmpty())
    }
    // endregion

    // region parse: strings and escapes
    @Test
    fun `string escapes are decoded including unicode`() {
        assertEquals(
            "line1\nline2\ttab\r\"q\"\\\u2605/",
            Json.parse(""""line1\nline2\ttab\r\"q\"\\\u2605\/"""")
        )
    }

    @Test
    fun `leading and trailing whitespace is ignored`() {
        assertEquals(1L, Json.parse("   1   "))
    }
    // endregion

    // region parse: errors
    @Test
    fun `malformed input throws JsonException`() {
        assertThrows(JsonException::class.java) { Json.parse("") }
        assertThrows(JsonException::class.java) { Json.parse("   ") }
        assertThrows(JsonException::class.java) { Json.parse("{") }
        assertThrows(JsonException::class.java) { Json.parse("""{"a":1""") }
        assertThrows(JsonException::class.java) { Json.parse(""""unterminated""") }
        assertThrows(JsonException::class.java) { Json.parse(""""bad\xescape"""") }
        assertThrows(JsonException::class.java) { Json.parse(""""trunc\u12"""") }
        assertThrows(JsonException::class.java) { Json.parse("nul") }
        assertThrows(JsonException::class.java) { Json.parse("true false") }
    }
    // endregion

    // region escape
    @Test
    fun `escape encodes the special characters`() {
        assertEquals("""a\"b\\c\nd\te\r\b\f""", Json.escape("a\"b\\c\nd\te\r\b\u000C"))
    }

    @Test
    fun `escape encodes other control characters as unicode`() {
        assertEquals("\\u0001", Json.escape("\u0001"))
    }

    @Test
    fun `escape leaves ordinary and non-ascii characters intact`() {
        assertEquals("café ★ 日本語", Json.escape("café ★ 日本語"))
    }

    @Test
    fun `escape round-trips through parse`() {
        val original = "quote \" slash \\ tab \t newline \n star \u2605 日本語"
        val json = "\"" + Json.escape(original) + "\""

        assertEquals(original, Json.parse(json))
    }
    // endregion
}
