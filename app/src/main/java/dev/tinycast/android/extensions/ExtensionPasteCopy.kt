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
                    JSONObject.numberToString(content)
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
            var depth = 0
            var quoted = false
            var escaped = false
            var started = false
            var ended = false
            for (character in raw) {
                if (quoted) {
                    require(character >= ' ') { "Paste requires valid content parameters" }
                    if (escaped) escaped = false
                    else if (character == '\\') escaped = true
                    else if (character == '"') quoted = false
                    continue
                }
                if (character in " \t\r\n") continue
                require(!ended) { "Paste requires one content parameter array" }
                if (!started) {
                    require(character == '[') { "Paste requires a content parameter array" }
                    started = true
                }
                when (character) {
                    '"' -> quoted = true
                    '[', '{' -> require(++depth <= 4) { "Paste content is too deeply nested" }
                    ']', '}' -> {
                        require(--depth >= 0) { "Paste requires valid content parameters" }
                        if (depth == 0) ended = true
                    }
                    '\'', '/', '#' -> throw IllegalArgumentException("Paste requires JSON without comments or single-quoted strings")
                }
            }
            require(started && ended && !quoted && depth == 0) { "Paste requires complete content parameters" }
        }
    }
}
