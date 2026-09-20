/*
 * A minimal JSON value type + recursive-descent parser + serializer.
 *
 * stdlib-only stand-in for kotlinx.serialization: just enough to build
 * LLM API request bodies and read tool-call / text responses back out.
 */

sealed interface JsonValue {
    data class JsonObject(val entries: Map<String, JsonValue>) : JsonValue {
        operator fun get(key: String): JsonValue? = entries[key]
    }
    data class JsonArray(val items: List<JsonValue>) : JsonValue
    data class JsonString(val value: String) : JsonValue
    data class JsonNumber(val value: Double) : JsonValue
    data class JsonBool(val value: Boolean) : JsonValue
    data object JsonNull : JsonValue
}

fun JsonValue.asStringOrNull(): String? = (this as? JsonValue.JsonString)?.value
fun JsonValue.asString(): String = asStringOrNull() ?: error("expected JSON string, got $this")
fun JsonValue.asArray(): List<JsonValue> = (this as? JsonValue.JsonArray)?.items ?: error("expected JSON array, got $this")
fun JsonValue.asObject(): JsonValue.JsonObject = this as? JsonValue.JsonObject ?: error("expected JSON object, got $this")

// ---------------------------------------------------------------------
// Builder DSL
// ---------------------------------------------------------------------

class JsonObjectBuilder {
    private val entries = LinkedHashMap<String, JsonValue>()
    infix fun String.to(value: String) { entries[this] = JsonValue.JsonString(value) }
    infix fun String.to(value: Double) { entries[this] = JsonValue.JsonNumber(value) }
    infix fun String.to(value: Boolean) { entries[this] = JsonValue.JsonBool(value) }
    infix fun String.to(value: JsonValue) { entries[this] = value }
    fun build(): JsonValue.JsonObject = JsonValue.JsonObject(entries)
}

fun jsonObject(block: JsonObjectBuilder.() -> Unit): JsonValue.JsonObject = JsonObjectBuilder().apply(block).build()
fun jsonArray(vararg items: JsonValue): JsonValue.JsonArray = JsonValue.JsonArray(items.toList())
fun jsonArrayOf(items: List<JsonValue>): JsonValue.JsonArray = JsonValue.JsonArray(items)

// ---------------------------------------------------------------------
// Serialization
// ---------------------------------------------------------------------

fun JsonValue.render(): String = StringBuilder().also { renderTo(it) }.toString()

private fun JsonValue.renderTo(sb: StringBuilder) {
    when (this) {
        is JsonValue.JsonObject -> {
            sb.append('{')
            entries.entries.forEachIndexed { index, (key, value) ->
                if (index > 0) sb.append(',')
                sb.append('"').append(escape(key)).append("\":")
                value.renderTo(sb)
            }
            sb.append('}')
        }
        is JsonValue.JsonArray -> {
            sb.append('[')
            items.forEachIndexed { index, item ->
                if (index > 0) sb.append(',')
                item.renderTo(sb)
            }
            sb.append(']')
        }
        is JsonValue.JsonString -> sb.append('"').append(escape(value)).append('"')
        is JsonValue.JsonNumber -> sb.append(if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString())
        is JsonValue.JsonBool -> sb.append(value)
        JsonValue.JsonNull -> sb.append("null")
    }
}

private fun escape(s: String): String = buildString {
    for (c in s) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }
}

// ---------------------------------------------------------------------
// Parsing
// ---------------------------------------------------------------------

fun parseJson(text: String): JsonValue {
    val parser = JsonParser(text)
    val value = parser.parseValue()
    parser.skipWhitespace()
    check(parser.atEnd()) { "trailing content after JSON value at position ${parser.pos}" }
    return value
}

private class JsonParser(private val text: String) {
    var pos = 0
        private set

    fun atEnd() = pos >= text.length

    fun skipWhitespace() {
        while (!atEnd() && text[pos].isWhitespace()) pos++
    }

    private fun expect(c: Char) {
        check(!atEnd() && text[pos] == c) { "expected '$c' at position $pos, found '${if (atEnd()) "<eof>" else text[pos]}'" }
        pos++
    }

    fun parseValue(): JsonValue {
        skipWhitespace()
        return when {
            atEnd() -> error("unexpected end of input")
            text[pos] == '{' -> parseObject()
            text[pos] == '[' -> parseArray()
            text[pos] == '"' -> JsonValue.JsonString(parseStringLiteral())
            text.startsWith("true", pos) -> { pos += 4; JsonValue.JsonBool(true) }
            text.startsWith("false", pos) -> { pos += 5; JsonValue.JsonBool(false) }
            text.startsWith("null", pos) -> { pos += 4; JsonValue.JsonNull }
            else -> parseNumber()
        }
    }

    private fun parseObject(): JsonValue.JsonObject {
        expect('{')
        val entries = LinkedHashMap<String, JsonValue>()
        skipWhitespace()
        if (!atEnd() && text[pos] == '}') { pos++; return JsonValue.JsonObject(entries) }
        while (true) {
            skipWhitespace()
            val key = parseStringLiteral()
            skipWhitespace()
            expect(':')
            entries[key] = parseValue()
            skipWhitespace()
            if (!atEnd() && text[pos] == ',') { pos++; continue }
            break
        }
        skipWhitespace()
        expect('}')
        return JsonValue.JsonObject(entries)
    }

    private fun parseArray(): JsonValue.JsonArray {
        expect('[')
        val items = mutableListOf<JsonValue>()
        skipWhitespace()
        if (!atEnd() && text[pos] == ']') { pos++; return JsonValue.JsonArray(items) }
        while (true) {
            items += parseValue()
            skipWhitespace()
            if (!atEnd() && text[pos] == ',') { pos++; continue }
            break
        }
        skipWhitespace()
        expect(']')
        return JsonValue.JsonArray(items)
    }

    private fun parseStringLiteral(): String {
        expect('"')
        val sb = StringBuilder()
        while (!atEnd() && text[pos] != '"') {
            val c = text[pos]
            if (c == '\\') {
                pos++
                check(!atEnd()) { "unterminated escape at position $pos" }
                when (val esc = text[pos]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        val hex = text.substring(pos + 1, pos + 5)
                        sb.append(hex.toInt(16).toChar())
                        pos += 4
                    }
                    else -> error("unsupported escape '\\$esc' at position $pos")
                }
                pos++
            } else {
                sb.append(c)
                pos++
            }
        }
        expect('"')
        return sb.toString()
    }

    private fun parseNumber(): JsonValue.JsonNumber {
        val start = pos
        if (!atEnd() && text[pos] == '-') pos++
        while (!atEnd() && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
        val slice = text.substring(start, pos)
        check(slice.isNotEmpty()) { "expected number at position $start" }
        return JsonValue.JsonNumber(slice.toDouble())
    }
}
