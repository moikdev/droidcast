package dev.tinycast.android.extensions

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Plain output held once in memory. This object never formats its contents for logging. */
internal class ExtensionPasteCopy private constructor(text: String) {
    private var value: String? = text
    val available: Boolean get() = value != null

    fun take(): String? = value.also { value = null }
    fun clear() { value = null }

    companion object {
        internal const val MAX_TEXT_UNITS = 64 * 1024
        internal const val MAX_TEXT_BYTES = 128 * 1024
        internal const val MAX_REQUEST_UNITS = 512 * 1024
        internal const val MAX_REQUEST_BYTES = 512 * 1024

        fun parse(raw: String): ExtensionPasteCopy {
            require(raw.length <= MAX_REQUEST_UNITS && raw.toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES) {
                "This paste result exceeds the Android size limit"
            }
            boundedNesting(raw)
            val args = try { JSONArray(raw) } catch (_: JSONException) {
                throw IllegalArgumentException("Paste requires valid content parameters")
            }
            require(args.length() in 1..2) { "Paste requires content and optional options" }
            if (args.length() == 2 && !args.isNull(1)) {
                val options = args.optJSONObject(1)
                require(options != null && options.keys().asSequence().all { it == "closeMainWindow" } &&
                    (!options.has("closeMainWindow") || options.opt("closeMainWindow") is Boolean)) {
                    "These paste options are unavailable on Android"
                }
            }
            val text = when (val content = args.opt(0)) {
                is String -> content
                is Number -> {
                    require(content.toDouble().isFinite()) { "Paste requires finite text content" }
                    // The portable API serializes numbers with JSON.stringify. Keep
                    // that exact representation, including small/large exponents.
                    raw.substringAfter('[').trimStart().substringBefore(',').substringBefore(']').trim()
                }
                is JSONObject -> {
                    require(content.keys().asSequence().all { it in setOf("text", "html") } &&
                        content.opt("text") is String && (!content.has("html") || content.opt("html") is String)) {
                        "Only a plain text result can be offered for Copy on Android"
                    }
                    content.getString("text")
                }
                else -> throw IllegalArgumentException("Only a plain text result can be offered for Copy on Android")
            }
            require(text.length <= MAX_TEXT_UNITS) { "This paste result exceeds the Android text limit" }
            var index = 0
            while (index < text.length) {
                val character = text[index++]
                if (Character.isHighSurrogate(character)) {
                    require(index < text.length && Character.isLowSurrogate(text[index++])) { "Paste requires valid Unicode text" }
                } else require(!Character.isLowSurrogate(character)) { "Paste requires valid Unicode text" }
            }
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES) { "This paste result exceeds the Android text limit" }
            return ExtensionPasteCopy(text)
        }

        private fun boundedNesting(raw: String) {
            var at = 0
            fun invalid(): Nothing = throw IllegalArgumentException("Paste requires valid JSON content parameters")
            fun space() { while (at < raw.length && raw[at] in " \t\r\n") at++ }
            fun token(character: Char) { space(); if (at >= raw.length || raw[at++] != character) invalid() }
            fun string() {
                token('"')
                while (at < raw.length) {
                    val character = raw[at++]
                    if (character == '"') return
                    if (character < ' ') invalid()
                    if (character == '\\') {
                        if (at >= raw.length) invalid()
                        when (raw[at++]) {
                            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                            'u' -> repeat(4) {
                                if (at >= raw.length || raw[at++] !in "0123456789abcdefABCDEF") invalid()
                            }
                            else -> invalid()
                        }
                    }
                }
                invalid()
            }
            fun number() {
                val start = at
                if (raw[at] == '-') at++
                if (at >= raw.length) invalid()
                if (raw[at] == '0') at++ else {
                    if (raw[at] !in '1'..'9') invalid()
                    while (at < raw.length && raw[at] in '0'..'9') at++
                }
                if (at < raw.length && raw[at] == '.') {
                    at++; val digits = at
                    while (at < raw.length && raw[at] in '0'..'9') at++
                    if (at == digits) invalid()
                }
                if (at < raw.length && raw[at] in "eE") {
                    at++; if (at < raw.length && raw[at] in "+-") at++
                    val digits = at
                    while (at < raw.length && raw[at] in '0'..'9') at++
                    if (at == digits) invalid()
                }
                if (raw.substring(start, at).toDoubleOrNull()?.isFinite() != true) invalid()
            }
            fun value(depth: Int) {
                require(depth <= 4) { "Paste content is too deeply nested" }
                space(); if (at >= raw.length) invalid()
                when (raw[at]) {
                    '"' -> string()
                    '[' -> {
                        at++; space()
                        if (at < raw.length && raw[at] == ']') { at++; return }
                        while (true) {
                            value(depth + 1); space(); if (at >= raw.length) invalid()
                            when (raw[at++]) { ']' -> return; ',' -> Unit; else -> invalid() }
                        }
                    }
                    '{' -> {
                        at++; space()
                        if (at < raw.length && raw[at] == '}') { at++; return }
                        while (true) {
                            string(); token(':'); value(depth + 1); space(); if (at >= raw.length) invalid()
                            when (raw[at++]) { '}' -> return; ',' -> Unit; else -> invalid() }
                        }
                    }
                    '-', in '0'..'9' -> number()
                    else -> {
                        val literal = listOf("true", "false", "null").firstOrNull { raw.startsWith(it, at) } ?: invalid()
                        at += literal.length
                    }
                }
            }
            space(); if (at >= raw.length || raw[at] != '[') invalid()
            value(0); space(); if (at != raw.length) invalid()
        }
    }
}
