package com.clipforge.app

import com.clipforge.app.core.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JsonTest {

    @Test
    fun floatsMatchPythonRepr() {
        val text = Golden.text("floats.json")
        val parsed = Json.parse(text) as List<*>
        // Python wrote each double with repr(); re-serializing must give the same text
        // (json.dumps without separators uses ", " between items).
        assertEquals(text.replace(", ", ","), Json.canonical(parsed))
        val reprs = text.removePrefix("[").removeSuffix("]").split(", ")
        assertEquals(parsed.size, reprs.size)
        for ((i, v) in parsed.withIndex()) {
            assertEquals("value $i", reprs[i], Json.pyFloat((v as Number).toDouble()))
        }
    }

    @Test
    fun canonicalMatchesPythonBytes() {
        val doc = Json.parse(Golden.text("canonical_input.json"))
        assertArrayEquals(Golden.bytes("canonical_expected.txt"), Json.canonicalBytes(doc))
    }

    @Test
    fun prettyRoundTripsToSameCanonical() {
        val doc = Json.parse(Golden.text("canonical_input.json"))
        assertEquals(Json.canonical(doc), Json.canonical(Json.parse(Json.pretty(doc))))
    }

    @Test
    fun knownFloatForms() {
        assertEquals("30.0", Json.pyFloat(30.0))
        assertEquals("0.1", Json.pyFloat(0.1))
        assertEquals("1e+16", Json.pyFloat(1e16))
        assertEquals("1000000000000000.0", Json.pyFloat(1e15))
        assertEquals("0.0001", Json.pyFloat(1e-4))
        assertEquals("1e-05", Json.pyFloat(1e-5))
        assertEquals("-0.0", Json.pyFloat(-0.0))
        assertEquals("5e-324", Json.pyFloat(Double.MIN_VALUE))
        assertEquals("1.7976931348623157e+308", Json.pyFloat(Double.MAX_VALUE))
    }

    @Test
    fun rejectsMalformedInput() {
        for (bad in listOf("", "{", "[1,]", "{\"a\" 1}", "tru", "\"unterminated", "1 2", "{\"a\":NaN}")) {
            assertThrows(bad, IllegalArgumentException::class.java) { Json.parse(bad) }
        }
    }

    @Test
    fun integersStayIntegers() {
        assertEquals("[1,-2,12345678901234567890,2.5]", Json.canonical(Json.parse("[1, -2, 12345678901234567890, 2.5]")))
    }
}
