package dev.tinycast.android.extensions

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A copied, bounded foreground launch request. It contains no input grant. */
internal class ExtensionCommandLaunch private constructor(
    val name: String,
    private val arguments: Map<String, String>,
    private val contextJson: String?,
    private val fallbackText: String?,
) {
    fun argumentsFor(definition: JSONObject): Map<String, String> {
        val fields = definition.optJSONArray("arguments").objects()
        require(fields.size <= MAX_ARGUMENTS) { "This command has too many arguments for an Android launch" }
        val names = fields.map { it.getString("name") }
        require(names.distinct().size == names.size && names.all { it.isNotBlank() && it.length <= MAX_KEY_UNITS }) {
            "This command has unsupported argument definitions"
        }
        require(arguments.keys.all { it in names }) { "The launch contains an undeclared command argument" }
        require(fields.none { it.optBoolean("required") && arguments[it.getString("name")].isNullOrBlank() }) {
            "Complete the target command's required arguments before launching it"
        }
        return LinkedHashMap(arguments)
    }

    fun launchProperties(definition: JSONObject): String {
        val value = JSONObject().put("arguments", JSONObject(argumentsFor(definition))).put("launchType", "userInitiated")
        contextJson?.let { value.put("launchContext", JSONObject(it)) }
        fallbackText?.let { value.put("fallbackText", it) }
        return value.toString().also {
            require(it.length <= MAX_REQUEST_UNITS && it.toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES) {
                "The command launch data exceeds the Android size limit"
            }
        }
    }

    companion object {
        internal const val MAX_REQUEST_UNITS = 64 * 1024
        internal const val MAX_REQUEST_BYTES = 128 * 1024
        private const val MAX_ARGUMENTS = 64
        private const val MAX_KEY_UNITS = 256
        private const val MAX_CONTEXT_VALUES = 2048
        private const val MAX_CONTEXT_DEPTH = 16
        private const val MAX_FALLBACK_UNITS = 16 * 1024
        private val allowedOptions = setOf("name", "type", "arguments", "context", "fallbackText")

        fun parse(raw: String): ExtensionCommandLaunch {
            // Check before JSON parsing, including direct calls to the JS bridge.
            require(raw.length <= MAX_REQUEST_UNITS && raw.toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES) {
                "The command launch data exceeds the Android size limit"
            }
            requireBoundedJsonNesting(raw)
            val parameters = try { JSONArray(raw) } catch (_: JSONException) {
                throw IllegalArgumentException("Command launch requires valid JSON parameters")
            }
            require(parameters.length() == 1 && parameters.opt(0) is JSONObject) { "Command launch requires one options object" }
            val options = parameters.getJSONObject(0)
            require(!options.has("extensionName") && !options.has("ownerOrAuthorName")) {
                "Launching another extension is unavailable on Android"
            }
            require(options.keys().asSequence().all { it in allowedOptions }) { "This command requests unsupported launch options" }
            require(options.opt("type") == "userInitiated") { "Only foreground user-initiated command launches are supported on Android" }
            val name = options.opt("name") as? String
            require(name != null && Regex("[A-Za-z0-9_-]{1,128}").matches(name)) { "Command launch requires a valid command name" }
            val values = if (options.has("arguments")) {
                require(options.opt("arguments") is JSONObject) { "Command arguments must be a string-valued object" }
                options.getJSONObject("arguments")
            } else JSONObject()
            require(values.length() <= MAX_ARGUMENTS) { "The command launch has too many arguments" }
            val args = linkedMapOf<String, String>()
            values.keys().forEach { key ->
                require(key.isNotBlank() && key.length <= MAX_KEY_UNITS && values.opt(key) is String) {
                    "Command arguments must have bounded names and string values"
                }
                args[key] = values.getString(key)
            }
            val context = if (options.has("context")) {
                require(options.opt("context") is JSONObject) { "Launch context must be a JSON object" }
                options.getJSONObject("context").also(::validateContext).toString()
            } else null
            val fallback = if (options.has("fallbackText")) {
                val text = options.opt("fallbackText") as? String
                require(text != null && text.length <= MAX_FALLBACK_UNITS) { "Fallback text must be a bounded string" }
                text
            } else null
            return ExtensionCommandLaunch(name, args, context, fallback)
        }

        private fun requireBoundedJsonNesting(raw: String) {
            var depth = 0
            var quoted = false
            var escaped = false
            var started = false
            var ended = false
            for (character in raw) {
                if (quoted) {
                    require(character >= ' ') { "Command launch requires valid JSON parameters" }
                    if (escaped) escaped = false
                    else if (character == '\\') escaped = true
                    else if (character == '"') quoted = false
                    continue
                }
                if (character in " \t\r\n") continue
                require(!ended) { "Command launch requires one JSON parameter array" }
                if (!started) {
                    require(character == '[') { "Command launch requires a JSON parameter array" }
                    started = true
                }
                when (character) {
                    '"' -> quoted = true
                    '[', '{' -> require(++depth <= MAX_CONTEXT_DEPTH + 4) { "Command launch JSON is too deeply nested" }
                    ']', '}' -> {
                        require(--depth >= 0) { "Command launch requires valid JSON parameters" }
                        if (depth == 0) ended = true
                    }
                    '\'', '/', '#' -> throw IllegalArgumentException("Command launch requires JSON without comments or single-quoted strings")
                }
            }
            require(started && ended && !quoted && depth == 0) { "Command launch requires complete JSON parameters" }
        }

        private fun validateContext(root: JSONObject) {
            var values = 0
            fun visit(value: Any?, depth: Int) {
                require(depth <= MAX_CONTEXT_DEPTH && ++values <= MAX_CONTEXT_VALUES) { "Launch context is too deeply nested or has too many values" }
                when (value) {
                    null, JSONObject.NULL -> Unit
                    is JSONObject -> value.keys().forEach { key ->
                        require(key.length <= MAX_KEY_UNITS) { "Launch context has an oversized property name" }
                        visit(value.get(key), depth + 1)
                    }
                    is JSONArray -> repeat(value.length()) { visit(value.get(it), depth + 1) }
                    is Number -> require(value.toDouble().isFinite()) { "Launch context numbers must be finite" }
                    is String, is Boolean -> Unit
                    else -> error("Launch context contains an unsupported value")
                }
            }
            visit(root, 0)
        }

        fun requireConfigured(fields: List<JSONObject>, preferences: JSONObject) {
            fields.forEach { field ->
                val name = field.getString("name")
                val value = preferences.opt(name)
                require(!field.optBoolean("required") || field.optString("type") == "checkbox" ||
                    value != null && value != JSONObject.NULL && value.toString().isNotBlank()) {
                    "Configure the target command from the launcher before launching it here"
                }
                if (field.optString("type") == "dropdown" && value != null && value != JSONObject.NULL && value.toString().isNotBlank()) {
                    require(field.optJSONArray("data").objects().any { it.optString("value") == value.toString() }) {
                        "Choose an available target-command preference before launching it here"
                    }
                }
            }
        }
    }
}
