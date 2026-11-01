package dev.tinycast.android.extensions

import android.content.Context
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
    var runtimeError by mutableStateOf<String?>(null)
    var runtimeStatus by mutableStateOf("")
    var runtimeReady by mutableStateOf(false)
    var navigationDepth by mutableIntStateOf(1)
    var launchEpoch by mutableIntStateOf(0)
    var arguments by mutableStateOf<Map<String, String>>(emptyMap())
    var preferencesEpoch by mutableIntStateOf(0)

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
        running = extension to command; arguments = args; runtimeTree = null; runtimeError = null; runtimeStatus = "Starting extension…"; runtimeReady = false; navigationDepth = 1; launchEpoch++
    }
    fun close() { running = null; runtimeTree = null; runtimeError = null; runtimeReady = false }
}
