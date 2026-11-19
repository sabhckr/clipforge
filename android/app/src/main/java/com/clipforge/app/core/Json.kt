package com.clipforge.app.core

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

/**
 * Minimal JSON model + parser + writers whose canonical output is byte-identical to
 * Python's `json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False)`.
 *
 * That equivalence lets the desktop verifier check manifests signed on Android and
 * vice versa. Value types:
 *   object -> Map<String, Any?> (insertion ordered), array -> List<Any?>, string -> String,
 *   integer -> Long (or BigInteger), float -> Double, true/false -> Boolean, null -> null.
 */
object Json {

    class ParseException(message: String) : IllegalArgumentException(message)

    // ------------------------------------------------------------------ parse
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val value = p.readValue()
        p.skipWs()
        if (!p.atEnd()) throw ParseException("Unexpected trailing data at ${p.pos}")
        return value
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> =
        parse(text) as? Map<String, Any?> ?: throw ParseException("Expected a JSON object")

    private class Parser(val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) pos++
        }

        fun readValue(): Any? {
            if (atEnd()) throw ParseException("Unexpected end of input")
            return when (val c = s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') readNumber() else throw ParseException("Unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) throw ParseException("Invalid literal at $pos")
            pos += word.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return out }
            while (true) {
                skipWs()
                if (atEnd() || s[pos] != '"') throw ParseException("Expected string key at $pos")
                val key = readString()
                skipWs()
                if (atEnd() || s[pos] != ':') throw ParseException("Expected ':' at $pos")
                pos++
                skipWs()
                out[key] = readValue()
                skipWs()
                if (atEnd()) throw ParseException("Unterminated object")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return out }
                    else -> throw ParseException("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val out = ArrayList<Any?>()
            pos++ // [
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return out }
            while (true) {
                skipWs()
                out.add(readValue())
                skipWs()
                if (atEnd()) throw ParseException("Unterminated array")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return out }
                    else -> throw ParseException("Expected ',' or ']' at $pos")
                }
            }
        }

        private fun readString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw ParseException("Unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (atEnd()) throw ParseException("Bad escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw ParseException("Bad unicode escape")
                                val hex = s.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16) ?: throw ParseException("Bad unicode escape")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw ParseException("Bad escape '\\$e'")
                        }
                    }
                    c < ' ' -> throw ParseException("Control character in string at ${pos - 1}")
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && s[pos] in "0123456789+-.eE") pos++
            val text = s.substring(start, pos)
            val isFloat = text.any { it == '.' || it == 'e' || it == 'E' }
            return if (isFloat) {
                text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw ParseException("Bad number '$text'")
            } else {
                text.toLongOrNull() ?: text.toBigIntegerOrNull() ?: throw ParseException("Bad number '$text'")
            }
        }
    }

    // ---------------------------------------------------------------- writers
    /** Canonical form used for signing (matches Python, see class doc). */
    fun canonical(value: Any?): String = StringBuilder().also { write(it, value, null, 0) }.toString()

    fun canonicalBytes(value: Any?): ByteArray = canonical(value).toByteArray(Charsets.UTF_8)

    /** Human-readable form for files. Any formatting is fine: verification re-canonicalizes. */
    fun pretty(value: Any?): String = StringBuilder().also { write(it, value, "  ", 0) }.toString()

    private fun write(sb: StringBuilder, v: Any?, indent: String?, depth: Int) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (v) "true" else "false")
            is String -> writeString(sb, v)
            is Double -> sb.append(pyFloat(v))
            is Float -> sb.append(pyFloat(v.toDouble()))
            is BigDecimal -> sb.append(pyFloat(v.toDouble()))
            is Int, is Long, is Short, is Byte, is BigInteger -> sb.append(v.toString())
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                val keys = v.keys.map { it as? String ?: throw IllegalArgumentException("JSON keys must be strings") }.sorted()
                sb.append('{')
                keys.forEachIndexed { i, k ->
                    if (i > 0) sb.append(',')
                    newline(sb, indent, depth + 1)
                    writeString(sb, k)
                    sb.append(if (indent == null) ":" else ": ")
                    write(sb, v[k], indent, depth + 1)
                }
                newline(sb, indent, depth)
                sb.append('}')
            }
            is Iterable<*> -> {
                val items = v.toList()
                if (items.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    newline(sb, indent, depth + 1)
                    write(sb, item, indent, depth + 1)
                }
                newline(sb, indent, depth)
                sb.append(']')
            }
            else -> throw IllegalArgumentException("Unsupported JSON value: ${v::class.java.name}")
        }
    }

    private fun newline(sb: StringBuilder, indent: String?, depth: Int) {
        if (indent == null) return
        sb.append('\n')
        repeat(depth) { sb.append(indent) }
    }

    /** Python json string escaping with ensure_ascii=False. */
    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') {
                    sb.append("\\u").append(String.format(java.util.Locale.ROOT, "%04x", c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
    }

    /**
     * Python `repr(float)`: the shortest decimal that round-trips to the same double
     * (closest one when several have the same length), fixed notation when the decimal
     * exponent is in [-4, 16), otherwise `d.ddde±XX`. Integral values keep ".0".
     */
    fun pyFloat(d: Double): String {
        require(d.isFinite()) { "NaN and Infinity are not valid JSON" }
        if (d == 0.0) return if (1.0 / d < 0) "-0.0" else "0.0"
        val exact = BigDecimal(d)
        var shortest: BigDecimal? = null
        for (p in 1..17) {
            val nearest = exact.round(MathContext(p, RoundingMode.HALF_EVEN))
            if (nearest.toDouble() == d) { shortest = nearest; break }
            // Next to a power of two the round-trip interval is asymmetric, so a p-digit
            // neighbour of the nearest candidate can round-trip when the nearest does not.
            val unit = BigDecimal.ONE.movePointLeft(nearest.scale())
            val ok = listOf(nearest.subtract(unit), nearest.add(unit)).filter { it.signum() != 0 && it.toDouble() == d }
            if (ok.isNotEmpty()) {
                shortest = ok.minWith(compareBy { it.subtract(exact).abs() })
                break
            }
        }
        val bd = (shortest ?: exact.round(MathContext(17, RoundingMode.HALF_EVEN))).stripTrailingZeros()
        val negative = bd.signum() < 0
        val digits = bd.unscaledValue().abs().toString()
        val exp10 = digits.length - 1 - bd.scale()
        val body = if (exp10 < -4 || exp10 >= 16) {
            val mantissa = if (digits.length > 1) digits[0] + "." + digits.substring(1) else digits
            val sign = if (exp10 < 0) "-" else "+"
            val e = kotlin.math.abs(exp10).toString().padStart(2, '0')
            "${mantissa}e$sign$e"
        } else if (exp10 >= 0) {
            if (digits.length <= exp10 + 1) {
                digits + "0".repeat(exp10 + 1 - digits.length) + ".0"
            } else {
                digits.substring(0, exp10 + 1) + "." + digits.substring(exp10 + 1)
            }
        } else {
            "0." + "0".repeat(-exp10 - 1) + digits
        }
        return if (negative) "-$body" else body
    }

    // --------------------------------------------------------------- accessors
    @Suppress("UNCHECKED_CAST")
    fun obj(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun arr(v: Any?): List<Any?>? = v as? List<Any?>

    fun num(v: Any?): Double? = (v as? Number)?.toDouble()
}
