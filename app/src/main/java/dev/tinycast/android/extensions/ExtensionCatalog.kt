package dev.tinycast.android.extensions

import android.content.Context
import dev.tinycast.android.CommandTextInput
import dev.tinycast.android.ReviewedTextInput
import dev.tinycast.android.TextInputPreview
import dev.tinycast.android.TextInputTarget
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import java.util.zip.ZipInputStream

data class StoreExtension(val id: String, val name: String, val title: String, val description: String,
    val author: String, val handle: String, val downloadUrl: String, val sourceUrl: String, val sha: String, val commandCount: Int) {
    fun json() = JSONObject().put("id", id).put("name", name).put("title", title).put("description", description)
        .put("author", author).put("handle", handle).put("download_url", downloadUrl).put("source_url", sourceUrl).put("commit_sha", sha).put("command_count", commandCount)
    companion object {
        fun parse(o: JSONObject): StoreExtension {
            val author = o.optJSONObject("author")
            return StoreExtension(o.getString("id"), o.getString("name"), o.optString("title", o.getString("name")), o.optString("description"),
                author?.optString("name") ?: o.optString("author"), author?.optString("handle") ?: o.optString("handle"),
                o.getString("download_url"), o.optString("source_url"), o.optString("commit_sha"), o.optJSONArray("commands")?.length() ?: o.optInt("command_count"))
        }
    }
}
data class ExtensionCommand(val name: String, val title: String, val description: String, val mode: String, val definition: JSONObject)
data class InstalledExtension(val listing: StoreExtension, val directory: File, val manifest: JSONObject) {
    val commands: List<ExtensionCommand> get() = manifest.optJSONArray("commands").objects().map {
        ExtensionCommand(it.getString("name"), it.optString("title", it.getString("name")), it.optString("description"), it.optString("mode", "view"), it)
    }
    fun fields(command: ExtensionCommand) = (manifest.optJSONArray("preferences").objects() + command.definition.optJSONArray("preferences").objects()).distinctBy { it.optString("name") }
}
fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

object ExtensionArchive {
    fun extract(bytes: ByteArray, destination: File): File {
        destination.mkdirs()
        var count = 0; var total = 0L
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= 4000) { "Extension has too many files" }
                val file = File(destination, entry.name).canonicalFile
                require(file.path.startsWith(destination.canonicalPath + File.separator)) { "Archive contains an unsafe path" }
                if (!entry.isDirectory) {
                    file.parentFile!!.mkdirs()
                    file.outputStream().use { out ->
                        val buffer = ByteArray(8192); var fileSize = 0
                        while (true) { val n = zip.read(buffer); if (n < 0) break; total += n; fileSize += n
                            require(total <= 96L * 1024 * 1024 && fileSize <= 16 * 1024 * 1024) { "Expanded extension exceeds the size limit" }; out.write(buffer, 0, n) }
                    }
                }
                zip.closeEntry()
            }
        }
        val manifests = destination.walkTopDown().maxDepth(3).filter { it.name == "package.json" && it.isFile }.toList()
        require(manifests.size == 1) { "Download is not a prebuilt Raycast extension" }
        val root = manifests.single().parentFile!!
        val manifest = JSONObject(File(root, "package.json").readText())
        require(manifest.optJSONArray("commands").objects().isNotEmpty()) { "Extension has no commands" }
        manifest.optJSONArray("commands").objects().forEach {
            val name = it.getString("name")
            require(Regex("[a-zA-Z0-9_-]+").matches(name)) { "Unsafe command name" }
            require(File(root, "$name.js").isFile) { "The Store did not provide the built $name command" }
        }
        return root
    }
}

class ExtensionCatalog(private val context: Context) {
    private val root = File(context.filesDir, "extensions").apply { mkdirs() }
    var installed by mutableStateOf(readInstalled()); private set
    var busy by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var running by mutableStateOf<Pair<InstalledExtension, ExtensionCommand>?>(null)
    var runtimeTree by mutableStateOf<JSONObject?>(null)
    private var runtimeErrorState by mutableStateOf<String?>(null)
    var runtimeError: String?
        get() = runtimeErrorState
        set(value) {
            if (value != null) revokeReviewedText()
            runtimeErrorState = value
        }
    var runtimeStatus by mutableStateOf("")
    var runtimeReady by mutableStateOf(false)
    var navigationDepth by mutableIntStateOf(1)
    var launchEpoch by mutableIntStateOf(0)
    var arguments by mutableStateOf<Map<String, String>>(emptyMap())
    var preferencesEpoch by mutableIntStateOf(0)
    internal val reviewedText = ReviewedTextInput()
    @Volatile private var invocationOwner: Any = Any()
    private var runtimeStop: Pair<Any, () -> Unit>? = null
    private var commandText: CommandTextInput? = null
    private var invocationLaunchProperties = ordinaryLaunchProperties(emptyMap())
    private var commandHandoffDepth = 0
    private var preparedCommandLaunch: PreparedCommandLaunch? = null
    private class PreparedText(val preview: TextInputPreview, val target: TextInputTarget, val epoch: Int)
    private var preparedText: PreparedText? = null
    internal class RuntimeText(val owner: Any, val target: TextInputTarget, val input: CommandTextInput?, val launchProperties: String)
    internal class PreparedCommandLaunch internal constructor(
        val owner: Any, val extension: InstalledExtension, val source: ExtensionCommand, val target: ExtensionCommand,
        val epoch: Int, val preferenceEpoch: Int, val sourceDefinition: String, val targetDefinition: String,
        val launchProperties: String, val depth: Int,
    )

    suspend fun search(query: String, page: Int = 1): List<StoreExtension> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8")
        val json = JSONObject(String(ExtensionHttp.download("https://www.raycast.com/frontend_api/extensions/search?q=$q&page=$page&platform=macOS&_=${System.currentTimeMillis()}"), Charsets.UTF_8))
        json.optJSONArray("data").objects().filter { it.optString("status", "active") == "active" && it.optString("download_url").startsWith("https://") }.map(StoreExtension::parse)
    }
    suspend fun install(listing: StoreExtension) {
        check(busy == null) { "Another installation is in progress" }
        busy = listing.id; error = null
        try {
            withContext(Dispatchers.IO) {
                val handle = URLEncoder.encode(listing.handle, "UTF-8")
                val name = URLEncoder.encode(listing.name, "UTF-8")
                val fresh = StoreExtension.parse(JSONObject(String(ExtensionHttp.download("https://www.raycast.com/api/v1/extensions/$handle/$name?_=${System.currentTimeMillis()}"), Charsets.UTF_8)))
                require(fresh.id == listing.id) { "Store identity changed; search again before installing" }
                require(URI(fresh.downloadUrl).host == "extensions.raycast-releases.com") { "Unexpected Store download host" }
                val bytes = ExtensionHttp.download(fresh.downloadUrl)
                val stage = File(context.cacheDir, "extension-${UUID.randomUUID()}")
                try {
                    val prepared = ExtensionArchive.extract(bytes, stage)
                    require(JSONObject(File(prepared, "package.json").readText()).getString("name") == fresh.name) { "Downloaded package does not match the Store listing" }
                    File(prepared, ".tinycast-store.json").writeText(fresh.json().toString())
                    val destination = directory(fresh.id)
                    val backup = File(root, ".backup-${fresh.id}")
                    backup.deleteRecursively()
                    if (destination.exists()) check(destination.renameTo(backup)) { "Could not prepare the update" }
                    if (!prepared.renameTo(destination)) { backup.renameTo(destination); error("Could not finish installation") }
                    backup.deleteRecursively()
                } finally { stage.deleteRecursively() }
            }
            installed = withContext(Dispatchers.IO) { readInstalled() }
        } finally { busy = null }
    }
    suspend fun uninstall(extension: InstalledExtension) {
        check(busy == null) { "Wait for the installation to finish" }
        busy = "uninstall:${extension.listing.id}"
        try {
            if (running?.first?.listing?.id == extension.listing.id) close()
            withContext(Dispatchers.IO) {
                directory(extension.listing.id).deleteRecursively()
                File(context.filesDir, "extension-data/${extension.listing.id}").deleteRecursively()
                context.getSharedPreferences("extension-${extension.listing.id}", Context.MODE_PRIVATE).edit().clear().apply()
            }
            installed = withContext(Dispatchers.IO) { readInstalled() }
        } finally { busy = null }
    }
    private fun directory(id: String): File { require(Regex("[a-zA-Z0-9_-]+").matches(id)); return File(root, id) }
    private fun readInstalled(): List<InstalledExtension> = root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith('.') }.mapNotNull { dir ->
        runCatching { InstalledExtension(StoreExtension.parse(JSONObject(File(dir, ".tinycast-store.json").readText())), dir, JSONObject(File(dir, "package.json").readText())) }.getOrNull()
    }.sortedBy { it.listing.title.lowercase() }
    fun preferences(extension: InstalledExtension, command: ExtensionCommand): JSONObject {
        preferencesEpoch
        val saved = JSONObject(context.getSharedPreferences("extension-${extension.listing.id}", Context.MODE_PRIVATE).getString("preferences", "{}")!!)
        return JSONObject().apply { extension.fields(command).forEach { f -> val name = f.getString("name"); if (saved.has(name)) put(name, saved.get(name)) else if (f.has("default")) put(name, f.get("default")) } }
    }
    fun savePreferences(extension: InstalledExtension, values: JSONObject) {
        val prefs = context.getSharedPreferences("extension-${extension.listing.id}", Context.MODE_PRIVATE)
        val merged = JSONObject(prefs.getString("preferences", "{}")!!)
        values.keys().forEach { merged.put(it, values.get(it)) }
        prefs.edit().putString("preferences", merged.toString()).apply(); preferencesEpoch++
    }
    fun launch(extension: InstalledExtension, command: ExtensionCommand, args: Map<String, String> = emptyMap()) {
        stopRuntime(Any())
        reviewedText.invalidate(); preparedText = null
        begin(extension,command,args,Any(),null)
    }
    private fun ordinaryLaunchProperties(args: Map<String, String>) = JSONObject().put("arguments", JSONObject(args)).put("launchType", "userInitiated").toString()
    private fun begin(extension: InstalledExtension, command: ExtensionCommand, args: Map<String,String>, owner: Any, input: CommandTextInput?,
                      launchProperties: String = ordinaryLaunchProperties(args), handoffDepth: Int = 0) {
        stopRuntime(owner)
        preparedCommandLaunch = null
        commandText?.let(reviewedText::release)
        commandText = input
        invocationLaunchProperties = launchProperties; commandHandoffDepth = handoffDepth
        running = extension to command; arguments = args.toMap(); runtimeTree = null; runtimeError = null; runtimeStatus = "Starting extension…"; runtimeReady = false; navigationDepth = 1; launchEpoch++
    }
    internal fun launchReviewed(extension: InstalledExtension, command: ExtensionCommand, preview: TextInputPreview, needsSetup: Boolean) {
        val target = TextInputTarget(extension.listing.id,command.name)
        reviewedText.checkApproval(preview,target)
        val owner = Any()
        if (needsSetup) {
            begin(extension,command,emptyMap(),owner,null)
            preparedText = PreparedText(preview,target,launchEpoch)
        } else {
            val input = reviewedText.approve(preview,target,owner)
            preparedText = null
            begin(extension,command,emptyMap(),owner,input)
        }
    }
    internal fun launchConfigured(extension: InstalledExtension, command: ExtensionCommand, preferences: JSONObject,
                                  args: Map<String,String>, expectedEpoch: Int): Boolean {
        if (launchEpoch != expectedEpoch || running?.first !== extension || running?.second !== command) return false
        val pending = preparedText
        if (pending != null) {
            if (pending.epoch != expectedEpoch) return false
            try {
                val owner = Any()
                val input = reviewedText.approve(pending.preview,pending.target,owner)
                savePreferences(extension,preferences)
                preparedText = null
                begin(extension,command,args,owner,input)
            } catch (failure: Throwable) {
                // Keep setup visible, with no text authority. Its consumed
                // preview cannot be reused if saving configuration failed.
                reviewedText.invalidate()
                throw failure
            }
        } else {
            savePreferences(extension,preferences)
            launch(extension,command,args)
        }
        return true
    }
    internal fun captureText(extension: InstalledExtension, command: ExtensionCommand): RuntimeText {
        val current = running?.first === extension && running?.second === command
        return RuntimeText(invocationOwner,TextInputTarget(extension.listing.id,command.name),if (current) commandText else null,
            if (current) invocationLaunchProperties else ordinaryLaunchProperties(emptyMap()))
    }
    private fun requireLaunchFile(extension: InstalledExtension, command: ExtensionCommand) {
        val directory = extension.directory.canonicalFile
        require(directory.parentFile == root.canonicalFile && directory.name == extension.listing.id) { "The target extension is no longer installed" }
        val script = File(directory, "${command.name}.js")
        require(script.canonicalFile == script.absoluteFile && script.isFile && script.canRead() && script.length() <= 16L * 1024 * 1024) {
            "The target command is unavailable; reinstall the extension before launching it"
        }
    }
    internal fun prepareCommandLaunch(owner: Any, extension: InstalledExtension, source: ExtensionCommand,
                                      request: ExtensionCommandLaunch): PreparedCommandLaunch {
        check(ownsRuntime(owner, extension, source) && runtimeError == null) { "This command session has ended" }
        preparedCommandLaunch = null
        check(busy == null && installed.any { it === extension }) { "Wait for extension installation to finish before launching a command" }
        check(extension.commands.any { it.name == source.name && it.definition === source.definition }) { "The source command has changed" }
        val target = extension.commands.filter { it.name == request.name }.singleOrNull()
            ?: error("The requested command is unavailable in this extension")
        require(target.mode in listOf("view", "no-view")) { "The target command requires an unsupported desktop mode" }
        require(!target.definition.optBoolean("disabled") && target.definition.opt("enabled") != false) { "The target command is disabled" }
        require(commandHandoffDepth < 16) { "The command launch chain reached its Android limit" }
        requireLaunchFile(extension, target)
        ExtensionCommandLaunch.requireConfigured(extension.fields(target), preferences(extension, target))
        val prepared = PreparedCommandLaunch(owner, extension, source, target, launchEpoch, preferencesEpoch,
            source.definition.toString(), target.definition.toString(), request.launchProperties(target.definition), commandHandoffDepth + 1)
        preparedCommandLaunch = prepared
        return prepared
    }
    internal fun commitCommandLaunch(prepared: PreparedCommandLaunch): Boolean {
        if (preparedCommandLaunch !== prepared) return false
        preparedCommandLaunch = null // Every commit attempt consumes its reservation.
        if (!ownsRuntime(prepared.owner, prepared.extension, prepared.source) || runtimeError != null || busy != null ||
            launchEpoch != prepared.epoch || preferencesEpoch != prepared.preferenceEpoch || commandHandoffDepth + 1 != prepared.depth ||
            installed.none { it === prepared.extension }) return false
        if (prepared.extension.commands.none { it.name == prepared.source.name && it.definition === prepared.source.definition &&
                it.definition.toString() == prepared.sourceDefinition }) return false
        val target = prepared.extension.commands.filter { it.name == prepared.target.name }.singleOrNull() ?: return false
        if (target.definition !== prepared.target.definition || target.definition.toString() != prepared.targetDefinition) return false
        requireLaunchFile(prepared.extension, target)
        ExtensionCommandLaunch.requireConfigured(prepared.extension.fields(target), preferences(prepared.extension, target))
        // Context is explicit output of the approved parent. Its raw input lease
        // and pending approval are never inherited by the new invocation.
        val arguments = JSONObject(prepared.launchProperties).getJSONObject("arguments")
        val values = arguments.keys().asSequence().associateWith { arguments.getString(it) }
        reviewedText.invalidate(); preparedText = null
        begin(prepared.extension, prepared.target, values, Any(), null, prepared.launchProperties, prepared.depth)
        return true
    }
    internal fun ownsRuntime(owner: Any, extension: InstalledExtension, command: ExtensionCommand): Boolean =
        invocationOwner === owner && running?.first === extension && running?.second === command
    internal fun attachRuntime(owner: Any, extension: InstalledExtension, command: ExtensionCommand, stop: () -> Unit): Any? {
        if (!ownsRuntime(owner, extension, command)) return null
        runtimeStop?.second?.invoke()
        val registration = Any()
        runtimeStop = registration to stop
        return registration
    }
    internal fun detachRuntime(registration: Any?) { if (registration != null && runtimeStop?.first === registration) runtimeStop = null }
    private fun stopRuntime(nextOwner: Any) {
        invocationOwner = nextOwner
        val stop = runtimeStop?.second
        runtimeStop = null
        stop?.invoke()
    }
    internal fun releaseText(owner: Any) {
        if (invocationOwner === owner) { commandText?.let(reviewedText::release); commandText = null }
    }
    private fun revokeReviewedText() {
        stopRuntime(Any())
        reviewedText.invalidate(); commandText = null; preparedText = null
        preparedCommandLaunch = null; invocationLaunchProperties = ordinaryLaunchProperties(emptyMap()); commandHandoffDepth = 0
    }
    fun close() { revokeReviewedText(); running = null; runtimeTree = null; runtimeError = null; runtimeStatus = ""; runtimeReady = false }
}
