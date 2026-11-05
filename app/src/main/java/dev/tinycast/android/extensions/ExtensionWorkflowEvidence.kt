package dev.tinycast.android.extensions

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Collections
import org.json.JSONArray
import org.json.JSONObject

internal enum class RecordedWorkflowOutcome(val wire: String, val label: String) {
    WORKED("worked_for_workflow", "Worked in recorded test"),
    PARTIAL("partial", "Partly worked in recorded test"),
    FAILED("observed_failure", "Failed in recorded test"),
    ENTRY_UNAVAILABLE("entry_unavailable", "Entry unavailable in recorded test"),
    CONFIGURATION_REQUIRED("configuration_required", "Configuration was required"),
}
internal enum class RecordedPhaseResult(val wire: String) {
    PASSED("passed"), FAILED("failed"), BLOCKED("blocked"), NOT_EXERCISED("not_exercised")
}
internal data class RecordedWorkflowPhase(val result: RecordedPhaseResult, val detail: String)
internal data class RecordedWorkflowCommand(val name: String, val title: String, val mode: String, val sha256: String)
internal data class RecordedWorkflowSnapshot(
    val archiveSha256: String, val handle: String, val name: String, val packageName: String,
    val payload: ExtensionPayloadIdentity, val commands: List<RecordedWorkflowCommand>,
)
internal data class RecordedWorkflowEnvironment(
    val appSource: String, val appSha256: String, val testSource: String, val testSha256: String,
    val androidApi: Int, val device: String, val webViewPackage: String?, val webViewVersion: String?, val profiles: List<String>,
)
internal data class RecordedWorkflowObservation(
    val id: String, val archiveSha256: String, val command: String, val outcome: RecordedWorkflowOutcome,
    val entryProbe: Boolean, val workflow: String, val configuration: String, val limits: List<String>,
    val phases: Map<String, RecordedWorkflowPhase>, val environment: RecordedWorkflowEnvironment,
    val evidenceCommit: String, val casePath: String, val reportPath: String,
) {
    val reportUrl: String get() = "https://github.com/moikdev/droidcast/blob/$evidenceCommit/$reportPath"
    val caseUrl: String get() = "https://github.com/moikdev/droidcast/blob/$evidenceCommit/$casePath"
}

/** Reviewed historical observations. No record certifies the current app/environment. */
internal class ExtensionWorkflowEvidence private constructor(
    val snapshots: List<RecordedWorkflowSnapshot>, val observations: List<RecordedWorkflowObservation>,
) {
    fun candidates(handle: String, name: String): List<RecordedWorkflowSnapshot> = snapshots.filter { it.handle == handle && it.name == name }
    fun records(snapshot: RecordedWorkflowSnapshot, command: String): List<RecordedWorkflowObservation> =
        observations.filter { it.archiveSha256 == snapshot.archiveSha256 && it.command == command }

    companion object {
        const val MAX_BYTES = 1024 * 1024
        private val sha256 = Regex("[0-9a-f]{64}")
        private val commit = Regex("[0-9a-f]{40}")
        private val name = Regex("[A-Za-z0-9_-]{1,96}")
        private val id = Regex("[a-z0-9-]{1,96}")
        internal val phaseNames = listOf("setup", "startup", "interaction", "destination", "persistence", "lifecycle")

        fun parse(bytes: ByteArray, checkActive: () -> Unit = {}): ExtensionWorkflowEvidence {
            require(bytes.size in 1..MAX_BYTES) { "Recorded tests catalog exceeds its size limit" }
            checkActive()
            val source = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: java.nio.charset.CharacterCodingException) { invalid() }
            StrictEvidenceJson(source, checkActive).validate()
            val root = try { JSONObject(source) } catch (_: org.json.JSONException) { invalid() }
            keys(root, setOf("schema_version", "snapshots", "observations"))
            require(integer(root, "schema_version") == 1L) { "Recorded tests catalog version is unavailable" }
            val snapshotRows = array(root, "snapshots", 1, 64)
            val snapshots = ArrayList<RecordedWorkflowSnapshot>()
            var files = 0; var commands = 0
            for (at in 0 until snapshotRows.length()) {
                checkActive(); val row = objectAt(snapshotRows, at)
                keys(row, setOf("archive_sha256", "handle", "name", "package_name", "manifest_sha256", "payload_sha256", "files", "commands"))
                val archive = digest(row, "archive_sha256"); val handle = identifier(row, "handle"); val storeName = identifier(row, "name")
                val packageName = identifier(row, "package_name")
                val fileRows = array(row, "files", 1, ExtensionPayloadIdentity.MAX_FILES)
                files += fileRows.length(); require(files <= 8000) { "Recorded tests catalog has too many files" }
                val entries = List(fileRows.length()) { index ->
                    val file = objectAt(fileRows, index); keys(file, setOf("path", "size", "sha256"))
                    ExtensionPayloadFile(text(file, "path", 512), integer(file, "size"), digest(file, "sha256"))
                }
                val payload = ExtensionPayloadIdentity.fromFiles(entries)
                require(payload.sha256 == digest(row, "payload_sha256") &&
                    payload.files.single { it.path == "package.json" }.sha256 == digest(row, "manifest_sha256")) { "Recorded payload identity differs" }
                val commandRows = array(row, "commands", 1, 256); commands += commandRows.length()
                require(commands <= 2048) { "Recorded tests catalog has too many commands" }
                val declared = List(commandRows.length()) { index ->
                    val value = objectAt(commandRows, index); keys(value, setOf("name", "title", "mode", "sha256"))
                    val commandName = identifier(value, "name"); val hash = digest(value, "sha256")
                    require(payload.files.singleOrNull { it.path == "$commandName.js" }?.sha256 == hash) { "Recorded command identity differs" }
                    val mode = text(value, "mode", 16); require(mode in setOf("view", "no-view", "menu-bar")) { "Recorded command mode is unavailable" }
                    RecordedWorkflowCommand(commandName, text(value, "title", 256), mode, hash)
                }
                require(declared.map { it.name }.distinct().size == declared.size) { "Recorded commands are ambiguous" }
                snapshots += RecordedWorkflowSnapshot(archive, handle, storeName, packageName, payload, immutable(declared))
            }
            require(snapshots.map { it.archiveSha256 }.distinct().size == snapshots.size) { "Recorded snapshots are ambiguous" }
            val rows = array(root, "observations", 1, 512)
            val observations = List(rows.length()) { at ->
                checkActive(); val row = objectAt(rows, at)
                keys(row, setOf("id", "archive_sha256", "command", "outcome", "kind", "workflow", "configuration", "limits", "phases", "environment", "evidence"))
                val recordId = text(row, "id", 96); require(id.matches(recordId)) { "Recorded test ID is invalid" }
                val archive = digest(row, "archive_sha256"); val command = identifier(row, "command")
                val snapshot = snapshots.singleOrNull { it.archiveSha256 == archive } ?: invalid()
                require(snapshot.commands.any { it.name == command }) { "Recorded test refers to an unknown command" }
                val outcome = RecordedWorkflowOutcome.entries.singleOrNull { it.wire == text(row, "outcome", 40) } ?: invalid()
                val kind = text(row, "kind", 20); require(kind in setOf("execution", "entry_probe")) { "Recorded test kind is invalid" }
                val phasesObject = obj(row, "phases"); keys(phasesObject, phaseNames.toSet())
                val phases = phaseNames.associateWith { key ->
                    val phase = obj(phasesObject, key); keys(phase, setOf("result", "detail"))
                    val result = RecordedPhaseResult.entries.singleOrNull { it.wire == text(phase, "result", 20) } ?: invalid()
                    RecordedWorkflowPhase(result, text(phase, "detail", 1000))
                }
                if (outcome == RecordedWorkflowOutcome.WORKED) {
                    require(kind == "execution" && listOf("startup", "interaction", "destination").all { phases.getValue(it).result == RecordedPhaseResult.PASSED } &&
                        phases.values.none { it.result == RecordedPhaseResult.FAILED || it.result == RecordedPhaseResult.BLOCKED }) { "A recorded success lacks its workflow outcomes" }
                }
                if (outcome == RecordedWorkflowOutcome.FAILED) require(phases.values.any { it.result == RecordedPhaseResult.FAILED }) { "A recorded failure lacks its failing phase" }
                if (outcome == RecordedWorkflowOutcome.CONFIGURATION_REQUIRED) require(phases.values.any { it.result == RecordedPhaseResult.BLOCKED }) { "Required configuration lacks its blocked phase" }
                if (outcome == RecordedWorkflowOutcome.ENTRY_UNAVAILABLE) require(kind == "entry_probe" && phases.values.any { it.result == RecordedPhaseResult.BLOCKED } &&
                    phases.getValue("interaction").result == RecordedPhaseResult.NOT_EXERCISED) { "Unavailable entry cannot certify execution" }
                val environment = obj(row, "environment")
                keys(environment, setOf("app_source", "app_sha256", "test_source", "test_sha256", "android_api", "device", "webview_package", "webview_version", "profiles"))
                val appSource = revision(environment, "app_source"); val testSource = revision(environment, "test_source")
                val api = integer(environment, "android_api"); require(api in 26..100) { "Recorded Android API is invalid" }
                val provider = nullableText(environment, "webview_package", 160); val version = nullableText(environment, "webview_version", 160)
                require((provider == null) == (version == null)) { "Recorded WebView identity is incomplete" }
                val profiles = strings(environment, "profiles", 1, 12, 160)
                require(profiles.distinct().size == profiles.size) { "Recorded profiles are ambiguous" }
                val evidence = obj(row, "evidence"); keys(evidence, setOf("commit", "case_path", "report_path"))
                RecordedWorkflowObservation(recordId, archive, command, outcome, kind == "entry_probe", text(row, "workflow", 2000),
                    text(row, "configuration", 1000), immutable(strings(row, "limits", 1, 12, 1000)), Collections.unmodifiableMap(phases),
                    RecordedWorkflowEnvironment(appSource, digest(environment, "app_sha256"), testSource, digest(environment, "test_sha256"),
                        api.toInt(), text(environment, "device", 160), provider, version, immutable(profiles)),
                    revision(evidence, "commit"), evidencePath(evidence, "case_path"), evidencePath(evidence, "report_path"))
            }
            require(observations.map { it.id }.distinct().size == observations.size) { "Recorded test IDs are ambiguous" }
            checkActive(); return ExtensionWorkflowEvidence(immutable(snapshots), immutable(observations))
        }

        private fun keys(value: JSONObject, expected: Set<String>) { require(value.keys().asSequence().toSet() == expected) { "Recorded tests catalog fields are unavailable" } }
        private fun text(value: JSONObject, key: String, max: Int): String = (value.opt(key) as? String)?.also {
            require(it.isNotBlank() && it.length <= max && validText(it)) { "Recorded tests catalog text is invalid" }
        } ?: invalid()
        private fun nullableText(value: JSONObject, key: String, max: Int): String? = if (value.isNull(key)) null else text(value, key, max)
        private fun integer(value: JSONObject, key: String): Long { val number = value.opt(key); require(number is Int || number is Long) { "Recorded tests catalog integer is invalid" }; return (number as Number).toLong() }
        private fun identifier(value: JSONObject, key: String): String = text(value, key, 96).also { require(name.matches(it)) { "Recorded catalog identity is invalid" } }
        private fun digest(value: JSONObject, key: String): String = text(value, key, 64).also { require(sha256.matches(it)) { "Recorded catalog digest is invalid" } }
        private fun revision(value: JSONObject, key: String): String = text(value, key, 40).also { require(commit.matches(it)) { "Recorded catalog revision is invalid" } }
        private fun evidencePath(value: JSONObject, key: String): String = text(value, key, 500).also {
            require(it.startsWith("docs/") && Regex("[A-Za-z0-9._/-]+").matches(it) && it.split('/').none { p -> p.isEmpty() || p == "." || p == ".." } &&
                listOf(".md", ".json", ".xml", ".png").any(it::endsWith)) { "Recorded evidence path is invalid" }
        }
        private fun obj(value: JSONObject, key: String): JSONObject = value.optJSONObject(key) ?: invalid()
        private fun objectAt(value: JSONArray, at: Int): JSONObject = value.optJSONObject(at) ?: invalid()
        private fun array(value: JSONObject, key: String, min: Int, max: Int): JSONArray = (value.optJSONArray(key) ?: invalid()).also { require(it.length() in min..max) { "Recorded catalog array exceeds its limits" } }
        private fun strings(value: JSONObject, key: String, min: Int, max: Int, length: Int): List<String> {
            val rows = array(value, key, min, max)
            return List(rows.length()) { at -> (rows.opt(at) as? String)?.also { require(it.isNotBlank() && it.length <= length && validText(it)) { "Recorded catalog text is invalid" } } ?: invalid() }
        }
        private fun validText(value: String): Boolean = value.none { it == '\u007f' || it < ' ' && it !in "\n\r\t" }
        private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())
        private fun invalid(): Nothing = throw IllegalArgumentException("Recorded tests catalog is invalid")
    }
}

/** Reject platform JSON leniency, duplicate keys, deep/large tokens and invalid escapes first. */
private class StrictEvidenceJson(private val source: String, private val checkActive: () -> Unit) {
    private var at = 0
    private var tokens = 0
    fun validate() { value(0); space(); require(at == source.length) { "Recorded catalog has trailing content" } }
    private fun bad(): Nothing = throw IllegalArgumentException("Recorded tests catalog JSON is invalid")
    private fun space() { while (at < source.length && source[at] in " \t\r\n") at++ }
    private fun token(c: Char) { space(); if (at >= source.length || source[at++] != c) bad() }
    private fun string(): String {
        token('"'); val result = StringBuilder()
        while (at < source.length) {
            if (result.length % 512 == 0) checkActive()
            val c = source[at++]
            if (c == '"') {
                var i = 0
                while (i < result.length) {
                    val v = result[i++]
                    if (Character.isHighSurrogate(v)) { if (i >= result.length || !Character.isLowSurrogate(result[i++])) bad() }
                    else if (Character.isLowSurrogate(v)) bad()
                }
                return result.toString()
            }
            if (c < ' ') bad()
            if (c != '\\') result.append(c) else {
                if (at >= source.length) bad()
                result.append(when (val escape = source[at++]) {
                    '"', '\\', '/' -> escape
                    'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                    'u' -> { if (at + 4 > source.length) bad(); val digits = source.substring(at, at + 4); if (digits.any { it !in "0123456789abcdefABCDEF" }) bad(); at += 4; digits.toInt(16).toChar() }
                    else -> bad()
                })
            }
            if (result.length > 4096) bad()
        }
        bad()
    }
    private fun value(depth: Int) {
        checkActive(); require(depth <= 10 && ++tokens <= 80000) { "Recorded catalog structure exceeds its limits" }
        space(); if (at >= source.length) bad()
        when (source[at]) {
            '{' -> {
                at++; space(); if (at < source.length && source[at] == '}') { at++; return }
                val keys = HashSet<String>()
                while (true) {
                    if (!keys.add(string()) || keys.size > 8192) bad()
                    token(':'); value(depth + 1); space(); if (at >= source.length) bad()
                    when (source[at++]) { '}' -> return; ',' -> Unit; else -> bad() }
                }
            }
            '[' -> {
                at++; space(); if (at < source.length && source[at] == ']') { at++; return }
                while (true) { value(depth + 1); space(); if (at >= source.length) bad(); when (source[at++]) { ']' -> return; ',' -> Unit; else -> bad() } }
            }
            '"' -> string()
            '-', in '0'..'9' -> {
                val start = at
                if (source[at] == '-') at++
                if (at >= source.length) bad()
                if (source[at] == '0') at++ else { if (source[at] !in '1'..'9') bad(); while (at < source.length && source[at] in '0'..'9') at++ }
                if (at < source.length && source[at] == '.') { at++; val digits = at; while (at < source.length && source[at] in '0'..'9') at++; if (digits == at) bad() }
                if (at < source.length && source[at] in "eE") { at++; if (at < source.length && source[at] in "+-") at++; val digits = at; while (at < source.length && source[at] in '0'..'9') at++; if (digits == at) bad() }
                if (at - start > 128 || source.substring(start, at).toDoubleOrNull()?.isFinite() != true) bad()
            }
            else -> { val word = listOf("true", "false", "null").firstOrNull { source.startsWith(it, at) } ?: bad(); at += word.length }
        }
    }
}
