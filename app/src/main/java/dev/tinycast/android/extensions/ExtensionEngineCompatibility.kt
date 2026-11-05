package dev.tinycast.android.extensions

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal data class ExtensionEngineIdentity(val packageName: String?, val version: String?) {
    val settingsPackage: String? get() = packageName?.takeIf {
        it.length <= 255 && it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))
    }
    val display: String get() = listOfNotNull(settingsPackage,
        version?.filterNot(Char::isISOControl)?.trim()?.take(120)?.takeIf(String::isNotBlank))
        .joinToString(" ").ifBlank { "Provider information unavailable" }
}

internal enum class ExtensionEngineFailure { SYNTAX, MISSING_APIS, CHECK_UNAVAILABLE, CHECK_TIMED_OUT }
// Each failure owns a fresh recovery lifetime, even when its display values
// equal a preceding failure. Compose must not retain a consumed action slot.
internal class ExtensionEngineProblem(val engine: ExtensionEngineIdentity, val failure: ExtensionEngineFailure,
                                     val missingApis: List<String> = emptyList())

/** Only this ordered check can enable host dispatch. An ended check never reopens. */
internal class ExtensionEngineGate {
    private enum class State { NEW, ADAPTER, RUNTIME, APIS, APPROVED, BLOCKED, CLOSED }
    @Volatile private var state = State.NEW
    val approved: Boolean get() = state == State.APPROVED
    val stage: ExtensionEngineProbe.Stage? get() = when (state) {
        State.ADAPTER -> ExtensionEngineProbe.Stage.ADAPTER
        State.RUNTIME -> ExtensionEngineProbe.Stage.RUNTIME
        State.APIS -> ExtensionEngineProbe.Stage.APIS
        else -> null
    }
    @Synchronized fun begin(): Boolean {
        if (state != State.NEW) return false
        state = State.ADAPTER
        return true
    }
    @Synchronized fun accept(stage: ExtensionEngineProbe.Stage): Boolean {
        state = when {
            state == State.ADAPTER && stage == ExtensionEngineProbe.Stage.ADAPTER -> State.RUNTIME
            state == State.RUNTIME && stage == ExtensionEngineProbe.Stage.RUNTIME -> State.APIS
            state == State.APIS && stage == ExtensionEngineProbe.Stage.APIS -> State.APPROVED
            else -> return false
        }
        return true
    }
    @Synchronized fun block() { if (state != State.CLOSED) state = State.BLOCKED }
    @Synchronized fun close() { state = State.CLOSED }
}

/** Probe wrappers use ES5 syntax. Modern vendor source appears only in escaped strings. */
internal object ExtensionEngineProbe {
    enum class Stage(val wire: String) { ADAPTER("adapter"), RUNTIME("runtime"), APIS("apis") }
    sealed interface Result {
        data object Passed : Result
        data class Rejected(val failure: ExtensionEngineFailure, val missingApis: List<String> = emptyList()) : Result
    }

    // Browser primitives used by the bundled adapter/runtime, not desktop APIs
    // supplied by the host or browser facilities replaced by the portable runtime.
    private val functions = listOf("Map", "Set", "WeakMap", "WeakSet", "Proxy", "Symbol", "Symbol.for",
        "Promise", "Promise.all", "Promise.resolve", "Promise.reject", "ArrayBuffer", "Uint8Array",
        "Array.from", "Array.isArray", "Array.prototype.includes", "Array.prototype.at",
        "Object.assign", "Object.create", "Object.defineProperties", "Object.defineProperty", "Object.entries",
        "Object.freeze", "Object.fromEntries", "Object.getOwnPropertyDescriptor", "Object.getOwnPropertyDescriptors",
        "Object.getOwnPropertyNames", "Object.getPrototypeOf", "Object.hasOwn", "Object.is", "Object.keys", "Object.setPrototypeOf",
        "Reflect.apply", "Reflect.construct", "Reflect.get", "Reflect.getOwnPropertyDescriptor", "Reflect.ownKeys",
        "Number.isFinite", "Number.isInteger", "Number.isNaN", "Number.parseInt", "Math.clz32", "Math.trunc",
        "String.fromCharCode", "String.fromCodePoint", "String.prototype.includes", "String.prototype.startsWith", "String.prototype.endsWith")
    private val contract = functions.associateWith { "function" } + mapOf("globalThis" to "object", "Symbol.iterator" to "symbol")

    private fun literal(value: String) = JSONObject.quote(value).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
    fun compileScript(stage: Stage, source: String): String {
        require(stage != Stage.APIS && source.length <= 1_000_000)
        val body = literal(source)
        // Constructing the function parses its body; the returned function is
        // never invoked. No extension bundle is supplied to this check.
        return """(function(){var problem=null;try{new Function($body);}catch(error){problem=error instanceof SyntaxError?'syntax':'unavailable';}return JSON.stringify({v:1,stage:'${stage.wire}',problem:problem,missing:[]});})()"""
    }
    fun apiScript(): String {
        val checks = JSONArray(contract.map { (path, type) -> JSONArray().put(path).put(type) })
        return """(function(){var checks=$checks,missing=[],problem=null;try{for(var i=0;i<checks.length;i++){var parts=checks[i][0].split('.'),value=window;for(var j=0;j<parts.length;j++){if(value==null){value=undefined;break;}value=value[parts[j]];}if(typeof value!==checks[i][1])missing.push(checks[i][0]);}if(missing.length)problem='apis';}catch(error){problem='unavailable';missing=[];}return JSON.stringify({v:1,stage:'apis',problem:problem,missing:missing});})()"""
    }
    fun decode(raw: String?, expected: Stage): Result {
        fun unavailable() = Result.Rejected(ExtensionEngineFailure.CHECK_UNAVAILABLE)
        if (raw == null || raw.length > 8192) return unavailable()
        return try {
            val outer = JSONTokener(raw)
            val text = outer.nextValue() as? String ?: return unavailable()
            if (outer.nextClean() != '\u0000') return unavailable()
            val inner = JSONTokener(text)
            val value = inner.nextValue() as? JSONObject ?: return unavailable()
            if (inner.nextClean() != '\u0000') return unavailable()
            if (value.keys().asSequence().toSet() != setOf("v", "stage", "problem", "missing") ||
                value.opt("v") != 1 || value.opt("stage") != expected.wire) return unavailable()
            val array = value.opt("missing") as? JSONArray ?: return unavailable()
            if (array.length() > contract.size) return unavailable()
            val missing = (0 until array.length()).map { array.opt(it) as? String ?: return unavailable() }
            if (missing.distinct().size != missing.size || missing.any { it !in contract }) return unavailable()
            when (value.opt("problem")) {
                JSONObject.NULL -> if (missing.isEmpty()) Result.Passed else unavailable()
                "syntax" -> if (expected != Stage.APIS && missing.isEmpty()) Result.Rejected(ExtensionEngineFailure.SYNTAX) else unavailable()
                "apis" -> if (expected == Stage.APIS && missing.isNotEmpty()) Result.Rejected(ExtensionEngineFailure.MISSING_APIS, missing) else unavailable()
                "unavailable" -> unavailable()
                else -> unavailable()
            }
        } catch (_: Exception) { unavailable() }
    }
}
