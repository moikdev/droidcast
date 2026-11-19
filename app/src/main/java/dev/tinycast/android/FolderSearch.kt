package dev.tinycast.android

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.util.AtomicFile
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private class ListingStillLoading : IOException()

private data class ProviderDocument(val id: String, val title: String, val mime: String, val modified: Long)
private data class ProviderListing(val documents: List<ProviderDocument>, val partial: Boolean)

/** One bounded worker for the process, even if a provider ignores cancellation. */
private object FolderProviderQueries {
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1), { task ->
        Thread(task, "Droidcast-folder-provider").apply { isDaemon = true }
    })
    suspend fun query(app: Application, uri: Uri): ProviderListing = withTimeout(5000) {
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            val task = FutureTask<Unit> {
                try {
                    val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    val rows = ArrayList<ProviderDocument>(); var inspected = 0; var partial = false
                    val cursor = app.contentResolver.query(uri, columns, null, null, null, signal) ?: throw IOException("Provider returned no listing")
                    cursor.use {
                        val extras = it.extras
                        if (extras.getString(DocumentsContract.EXTRA_ERROR) != null) throw IOException("Provider reported a listing error")
                        if (extras.getBoolean(DocumentsContract.EXTRA_LOADING)) throw ListingStillLoading()
                        val id = it.getColumnIndexOrThrow(columns[0]); val name = it.getColumnIndexOrThrow(columns[1])
                        val mime = it.getColumnIndexOrThrow(columns[2]); val modified = it.getColumnIndex(columns[3])
                        while (it.moveToNext()) {
                            signal.throwIfCanceled()
                            if (++inspected > FolderIndexRules.MAX_ENTRIES) { partial = true; break }
                            val documentId = it.getString(id) ?: continue
                            if (documentId.isEmpty() || documentId.length > 2048) { partial = true; continue }
                            val title = (it.getString(name) ?: "Untitled document").replace(Regex("[\\p{Cntrl}]"), " ").take(512)
                            rows.add(ProviderDocument(documentId, title, (it.getString(mime) ?: "application/octet-stream").take(128).lowercase(Locale.ROOT),
                                if (modified >= 0 && !it.isNull(modified)) it.getLong(modified).coerceAtLeast(0) else 0))
                        }
                    }
                    if (continuation.isActive) continuation.resume(ProviderListing(rows, partial))
                } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
            }
            continuation.invokeOnCancellation { signal.cancel(); task.cancel(true); executor.remove(task) }
            try { executor.execute(task) } catch (error: java.util.concurrent.RejectedExecutionException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("A storage provider is still busy. Try again later.", error))
            }
        }
    }
}

class FolderSearch internal constructor(
    private val app: Application,
    private val scope: CoroutineScope,
    atomicFile: (File) -> AtomicFile = { AtomicFile(it) },
    private val initialReadDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val beforeFolderValidation: suspend () -> Unit = {}
) {
    private val directory = File(app.filesDir, "folder-search")
    private val configFile = atomicFile(File(directory, "config.json"))
    private val indexFile = atomicFile(File(directory, "index.json"))
    private val disk = FolderStorageCoordinator.disk
    private val scanRevision = AtomicLong()
    private val searchRevision = AtomicLong()
    private var scanJob: Job? = null
    private var searchJob: Job? = null
    private var entries by mutableStateOf<List<IndexedDocument>>(emptyList())
    // Retained pins/aliases participate in derived UI results even when there
    // is no active folder query. Keep their lookup observable with the index.
    private val byId by derivedStateOf { entries.associateBy { it.id } }
    private var readable: Set<String> = emptySet()
    private var currentQuery = ""
    var navigationRevision = 0L
        private set
    private var searchActive = false
    var folders by mutableStateOf<List<SearchFolder>>(emptyList()); private set
    var enabled by mutableStateOf(false); private set
    var includeInLauncher by mutableStateOf(false); private set
    var loading by mutableStateOf(true); private set
    var busy by mutableStateOf(false); private set
    var changing by mutableStateOf(false); private set
    var searching by mutableStateOf(false); private set
    var matches by mutableStateOf<List<IndexedDocument>>(emptyList()); private set
    var type by mutableStateOf(FileSearchType.ALL); private set
    var status by mutableStateOf("Choose folders to search."); private set
    var errors by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var error by mutableStateOf<String?>(null); private set
    var selecting by mutableStateOf(false); private set
    private var reauthorizing: String? = null
    val count get() = entries.size
    val countLabel get() = app.resources.getQuantityString(R.plurals.folder_index_count, count, count)

    init { scope.launch {
        try {
            val loaded = withContext(initialReadDispatcher) { disk.withLock {
                val config = read(configFile, 65536)
                val roots = config?.getJSONArray("folders")?.let { values ->
                    require(values.length() <= FolderIndexRules.MAX_FOLDERS)
                    (0 until values.length()).map { i -> values.getJSONObject(i).let { SearchFolder(it.getString("uri"), it.getString("title"), it.getBoolean("ownsGrant")) } }
                } ?: emptyList()
                require(roots.all { validTree(it.uri) && it.title.length <= 512 } && roots.map { it.uri }.distinct().size == roots.size)
                val on = config?.getBoolean("enabled") ?: false
                val launcher = config?.getBoolean("launcher") ?: false
                val grants = configuredGrants(roots)
                var cacheFailed = false
                val cached = try { if (on) {
                    val index = read(indexFile, FolderIndexRules.MAX_CACHE_BYTES)
                    val rows = decodeIndex(index, roots, grants)
                    try { pruneIndex(index, roots.map { it.uri }.toSet() - grants) }
                    catch (_: Exception) { cacheFailed = true }
                    rows
                } else {
                    deleteIndex(); emptyList()
                } } catch (_: Exception) { cacheFailed = true; emptyList() }
                Loaded(roots, on, launcher, grants, cached, cacheFailed)
            } }
            folders = loaded.folders; enabled = loaded.enabled; includeInLauncher = loaded.launcher; readable = loaded.grants
            publish(loaded.entries)
            if (loaded.cacheFailed) error = "The local index could not be loaded or cleared. Refresh folders to rebuild it."
            errors = folders.filter { it.uri !in readable }.associate { it.uri to "Folder access expired. Reauthorize this folder." }
            status = if (enabled) "$countLabel · Refresh to check for changes." else "Folder search is off."
        } catch (_: Exception) { error = "Folder settings or index could not be loaded. Remove the local index and choose folders again." }
        finally { loading = false; scheduleSearch() }
    } }

    private data class Loaded(val folders: List<SearchFolder>, val enabled: Boolean, val launcher: Boolean, val grants: Set<String>, val entries: List<IndexedDocument>, val cacheFailed: Boolean)
    private fun grants() = app.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
    private fun configuredGrants(roots: List<SearchFolder>) = roots.map { it.uri }.toSet().intersect(grants())
    private suspend fun readGrants(roots: List<SearchFolder>) =
        withContext(Dispatchers.IO) { disk.withLock { configuredGrants(roots) } }
    // Called under FolderStorageCoordinator.disk alongside the grant operation.
    internal suspend fun configuredTreeUrisForGrantCleanup(): Set<String> = withContext(Dispatchers.IO) {
        val config = read(configFile, 65536) ?: return@withContext emptySet()
        val roots = config.getJSONArray("folders")
        (0 until roots.length()).mapTo(mutableSetOf()) { roots.getJSONObject(it).getString("uri") }
    }
    private fun validTree(value: String): Boolean = runCatching {
        val uri = Uri.parse(value)
        value.length <= 8192 && uri.scheme == "content" && !uri.authority.isNullOrBlank() && DocumentsContract.isTreeUri(uri) && DocumentsContract.getTreeDocumentId(uri).isNotEmpty()
    }.getOrDefault(false)
    private fun read(file: AtomicFile, limit: Int): JSONObject? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return JSONObject(readBytes(file, limit).toString(Charsets.UTF_8)).also { require(it.getInt("version") == 1) }
    }
    private fun readBytes(file: AtomicFile, limit: Int): ByteArray = file.openRead().use { input ->
            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
                if (count < 0) break
                if (count == 0 || output.size() + count > limit) throw IOException("Index exceeds its limit")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
    }
    private fun deleteIndex() {
        indexFile.delete()
        check(!indexFile.baseFile.exists() && !File(indexFile.baseFile.path + ".bak").exists() && !File(indexFile.baseFile.path + ".new").exists()) { "Cannot remove index" }
    }
    private fun write(file: AtomicFile, json: JSONObject, limit: Int, current: () -> Boolean = { true }): Boolean {
        val bytes = json.toString().toByteArray(Charsets.UTF_8); require(bytes.size <= limit)
        if (!current()) return false
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create private index folder" }
        val stream = file.startWrite()
        var finished = false
        try {
            stream.write(bytes)
            if (!current()) { file.failWrite(stream); return false }
            file.finishWrite(stream); finished = true
            // AtomicFile can log a failed rename without throwing. Do not transfer
            // permission ownership until the saved bytes have been verified.
            val saved = try { readBytes(file, limit) } catch (_: Exception) {
                // Reconcile a transient verification failure while storage remains serialized.
                try { readBytes(file, limit) } catch (failure: Exception) { throw IndeterminateFolderWrite(failure) }
            }
            check(saved.contentEquals(bytes)) { "Folder storage write could not be verified" }
            return true
        } catch (error: Exception) { if (!finished) file.failWrite(stream); throw error }
    }
    private fun config(roots: List<SearchFolder>, on: Boolean, launcher: Boolean) = JSONObject().put("version", 1).put("enabled", on).put("launcher", launcher)
        .put("folders", JSONArray().also { a -> roots.forEach { a.put(JSONObject().put("uri", it.uri).put("title", it.title).put("ownsGrant", it.ownsGrant)) } })
    private fun encode(entry: IndexedDocument) = JSONObject().put("id", entry.id).put("folder", entry.folder).put("uri", entry.uri)
        .put("title", entry.title).put("path", entry.path).put("mime", entry.mime).put("modified", entry.modified)
    private fun encodedSize(entry: IndexedDocument) = encode(entry).toString().toByteArray(Charsets.UTF_8).size + 1
    private fun indexJson(items: List<IndexedDocument>, roots: List<SearchFolder>) = JSONObject().put("version", 1)
        .put("folders", JSONArray(roots.map { it.uri }.sorted())).put("entries", JSONArray().also { a -> items.forEach { a.put(encode(it)) } })
    private fun decodeIndex(json: JSONObject?, roots: List<SearchFolder>, permissions: Set<String>): List<IndexedDocument> {
        if (json == null) return emptyList()
        val values = json.getJSONArray("folders")
        if ((0 until values.length()).map { values.getString(it) }.sorted() != roots.map { it.uri }.sorted()) return emptyList()
        val items = json.getJSONArray("entries"); require(items.length() <= FolderIndexRules.MAX_ENTRIES)
        return (0 until items.length()).map { i -> items.getJSONObject(i).let {
            val entry = IndexedDocument(it.getString("id"), it.getString("folder"), it.getString("uri"), it.getString("title"), it.getString("path"), it.getString("mime"), it.getLong("modified"))
            require(roots.any { root -> root.uri == entry.folder } && entry.uri.length <= 8192 && entry.title.length <= 512 && entry.path.length <= 4096 && entry.mime.length <= 128 && entry.modified >= 0)
            val root = Uri.parse(entry.folder); val uri = Uri.parse(entry.uri)
            require(uri.scheme == "content" && uri.authority == root.authority && DocumentsContract.isTreeUri(uri) && DocumentsContract.getTreeDocumentId(uri) == DocumentsContract.getTreeDocumentId(root))
            val id = DocumentsContract.getDocumentId(uri)
            require(entry.id == FolderIndexRules.id(requireNotNull(uri.authority), id) && DocumentsContract.buildDocumentUriUsingTree(root, id) == uri)
            entry
        } }.filter { it.folder in permissions }.distinctBy { it.id }
    }
    // Work on the persisted index under the shared disk lock. Another controller may
    // already have refreshed the surviving roots since this controller loaded them.
    private fun pruneIndex(index: JSONObject?, revoked: Set<String>) {
        if (index == null || revoked.isEmpty()) return
        val rows = index.getJSONArray("entries")
        val kept = JSONArray()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            if (row.getString("folder") !in revoked) kept.put(row)
        }
        if (kept.length() != rows.length()) {
            if (kept.length() == 0) deleteIndex()
            else check(write(indexFile, index.put("entries", kept), FolderIndexRules.MAX_CACHE_BYTES))
        }
    }
    private fun publish(items: List<IndexedDocument>) { entries = items; scheduleSearch() }
    fun configurationCommands() = if (enabled) entries.map { it.command() } else emptyList()
    fun command(id: String) = if (enabled) byId[id]?.command() else null
    fun retainedCommands(ids: Set<String>) = if (enabled) ids.mapNotNull { byId[it]?.command() } else emptyList()
    fun filterType(value: FileSearchType) {
        if (type == value) return
        navigationRevision++
        type = value
        scheduleSearch()
    }
    fun search(query: String, active: Boolean) {
        if (currentQuery == query && searchActive == active) return
        navigationRevision++
        currentQuery = query
        searchActive = active
        scheduleSearch()
    }
    private fun scheduleSearch() {
        val revision = searchRevision.incrementAndGet(); searchJob?.cancel(); matches = emptyList()
        if (!enabled || !searchActive || loading) { searching = false; return }
        val snapshot = entries; val query = currentQuery.take(256); val filter = type
        searching = true
        searchJob = scope.launch {
            try {
                delay(75)
                val result = withContext(Dispatchers.Default) { val context = currentCoroutineContext(); FolderIndexRules.search(snapshot, query, filter) { context.ensureActive() } }
                if (searchRevision.get() == revision) matches = result
            } finally { if (searchRevision.get() == revision) searching = false }
        }
    }
    private fun stopScan() { scanRevision.incrementAndGet(); scanJob?.cancel(); scanJob = null; busy = false }
    fun cancelRefresh() { stopScan(); status = "Refresh cancelled. Previous results kept." }
    fun beginSelection(uri: String?): Boolean {
        if (loading || changing || selecting) return false
        reauthorizing = uri; selecting = true; error = null; return true
    }
    fun selected(uri: Uri?) {
        val replace = reauthorizing; reauthorizing = null; selecting = false
        if (uri == null) { status = "Folder selection cancelled."; return }
        val value = uri.toString()
        if (!validTree(value)) { error = "Choose a document-provider folder."; return }
        if (replace != null && replace != value) { error = "Choose the same folder to reauthorize it, or use Add folder."; return }
        if (changing || (folders.size >= FolderIndexRules.MAX_FOLDERS && folders.none { it.uri == value })) { error = "You can select up to ${FolderIndexRules.MAX_FOLDERS} folders."; return }
        stopScan(); changing = true
        scope.launch {
            val ownership = FolderGrantTransaction(
                hasReadGrant = { value in grants() },
                takeReadGrant = { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) },
                releaseReadGrant = { app.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) },
                storageMutex = disk
            )
            try {
                val next: List<SearchFolder>
                try {
                    ownership.acquire()
                    beforeFolderValidation()
                    val root = FolderProviderQueries.query(app, DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))).documents.firstOrNull()
                        ?: throw IOException("Folder unavailable")
                    require(root.mime == DocumentsContract.Document.MIME_TYPE_DIR)
                    val existing = folders.firstOrNull { it.uri == value }
                    val added = SearchFolder(value, root.title, existing?.ownsGrant == true || ownership.newlyAcquired)
                    next = folders.filterNot { it.uri == value } + added
                    ownership.commit(disk) { check(write(configFile, config(next, true, includeInLauncher), 65536)) }
                } finally { ownership.releaseIfUncommitted() }
                folders = next; enabled = true; readable = readGrants(next); errors = errors - value; error = null
                refresh()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IndeterminateFolderWrite) { error = "Folder save could not be verified. Permission was retained. Reopen Settings to check the saved folders." }
            catch (_: Exception) { error = "Folder access could not be kept. Choose an available folder from a provider that supports persistent access." }
            finally { changing = false }
        }
    }
    fun changeEnabled(value: Boolean) = changeSettings(folders, value, includeInLauncher)
    fun changeIncludeInLauncher(value: Boolean) = changeSettings(folders, enabled, value)
    private fun changeSettings(next: List<SearchFolder>, on: Boolean, launcher: Boolean, removed: SearchFolder? = null) {
        if (loading || changing) return
        stopScan(); changing = true
        scope.launch {
            try {
                val cacheCleared = commitFolderConfiguration(disk,
                    writeConfiguration = {
                        check(write(configFile, config(next, on, launcher), 65536))
                        if (!on || removed != null) runCatching { deleteIndex() }.isSuccess else true
                    },
                    onCommitted = {
                        if (removed?.ownsGrant == true) runCatching {
                            app.contentResolver.releasePersistableUriPermission(Uri.parse(removed.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    })
                folders = next; enabled = on; includeInLauncher = launcher
                readable = readGrants(next)
                publish(if (on) entries.filter { it.folder in next.map(SearchFolder::uri) && it.folder in readable } else emptyList())
                errors = errors.filterKeys { key -> next.any { it.uri == key } }; error = if (cacheCleared) null else "Settings saved, but the old private index could not be cleared. Try disabling again."
                status = if (on) "$countLabel · Refresh to check for changes." else "Folder search is off; cached index removed."
                if (on && (removed != null || entries.isEmpty())) refresh()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Folder settings could not be saved. Try again." }
            finally { changing = false }
        }
    }
    fun remove(folder: SearchFolder) = changeSettings(folders.filterNot { it.uri == folder.uri }, enabled, includeInLauncher, folder)
    fun revalidate() {
        if (loading || changing) return
        val requestedRevision = scanRevision.get()
        scope.launch {
            val permissions = readGrants(folders)
            if (scanRevision.get() != requestedRevision || changing) return@launch
            if (permissions != readable) {
                val revoked = readable - permissions
                stopScan(); readable = permissions
                publish(entries.filter { it.folder in permissions })
                errors = folders.filter { it.uri !in permissions }.associate { it.uri to "Folder access expired. Reauthorize this folder." }
                status = if (!enabled) "Folder search is off." else countLabel +
                    if (errors.isNotEmpty()) " · Some folders need attention." else " · Refresh to check for changes."
                val revision = scanRevision.get()
                if (revoked.isNotEmpty()) {
                    val pruned = withContext(Dispatchers.IO) { disk.withLock {
                        runCatching {
                            val stillRevoked = revoked - grants()
                            if (stillRevoked.isNotEmpty()) pruneIndex(read(indexFile, FolderIndexRules.MAX_CACHE_BYTES), stillRevoked)
                        }.isSuccess
                    } }
                    if (!pruned && scanRevision.get() == revision) error = "Expired folder entries could not be removed from the local index. Refresh to rebuild it."
                }
            }
        }
    }
    fun refresh() {
        if (!enabled || loading) return
        stopScan(); val revision = scanRevision.get(); val roots = folders.toList()
        busy = true; error = null; status = "Indexing selected folders…"
        scanJob = scope.launch {
            try {
                val permissions = readGrants(roots); val result = ArrayList<IndexedDocument>(); val problems = linkedMapOf<String, String>()
                val retryFolders = mutableSetOf<String>()
                var limited = false; var remainingBytes = FolderIndexRules.MAX_CACHE_BYTES - 128 * 1024
                withTimeout(60000) {
                    for (root in roots) {
                        ensureActive()
                        if (root.uri !in permissions) { problems[root.uri] = "Folder access expired. Reauthorize this folder."; continue }
                        if (result.size >= FolderIndexRules.MAX_ENTRIES) { limited = true; break }
                        try {
                            val built = withTimeout(15000) { withContext(Dispatchers.Default) {
                                crawl(root, FolderIndexRules.MAX_ENTRIES - result.size, remainingBytes)
                            } }
                            result.addAll(built.items); remainingBytes -= built.bytes; limited = limited || built.limited
                        } catch (_: TimeoutCancellationException) {
                            currentCoroutineContext().ensureActive()
                            retryFolders.add(root.uri)
                            problems[root.uri] = "Storage provider took too long. Refresh when it is available."
                        } catch (_: ListingStillLoading) {
                            retryFolders.add(root.uri)
                            problems[root.uri] = "The provider is still loading this folder. Refresh to try again."
                        }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: SecurityException) { problems[root.uri] = "Folder access expired. Reauthorize this folder." }
                        catch (_: Exception) {
                            retryFolders.add(root.uri)
                            problems[root.uri] = "Folder could not be listed. Check the provider and refresh."
                        }
                    }
                }
                val snapshot = withContext(Dispatchers.IO) { disk.withLock {
                    // A peer may have removed a scope or disabled search while this provider was busy.
                    val stored = read(configFile, 65536)
                    val storedRoots = stored?.getJSONArray("folders")
                    if (stored == null || !stored.getBoolean("enabled") || storedRoots == null ||
                        (0 until storedRoots.length()).map { storedRoots.getJSONObject(it).getString("uri") } != roots.map { it.uri }) {
                        return@withLock null
                    }
                    val authorized = configuredGrants(roots)
                    val fresh = result.filter { it.folder in authorized }.associateByTo(linkedMapOf()) { it.id }
                    if (retryFolders.any { it in authorized }) {
                        // Read the last committed version under the same lock as the replacement.
                        val cached = decodeIndex(read(indexFile, FolderIndexRules.MAX_CACHE_BYTES), roots, authorized)
                        for (entry in cached) {
                            if (entry.folder !in retryFolders || entry.id in fresh) continue
                            val bytes = encodedSize(entry)
                            if (fresh.size >= FolderIndexRules.MAX_ENTRIES || bytes > remainingBytes) { limited = true; continue }
                            fresh[entry.id] = entry
                            remainingBytes -= bytes
                        }
                    }
                    val items = fresh.values.toList()
                    if (write(indexFile, indexJson(items, roots), FolderIndexRules.MAX_CACHE_BYTES) { scanRevision.get() == revision })
                        authorized to items else null
                } }
                if (scanRevision.get() != revision) return@launch
                if (snapshot == null) {
                    status = "Search folder settings changed. Reopen Search folders to refresh."
                } else {
                    val (authorized, items) = snapshot
                    for (root in roots) if (root.uri !in authorized) problems[root.uri] = "Folder access expired. Reauthorize this folder."
                    readable = authorized; publish(items); errors = problems
                    status = countLabel + if (limited) " · Some items omitted by metadata or safety limits." else if (problems.isNotEmpty()) " · Some folders need attention." else " · Metadata only."
                }
            } catch (cancelled: CancellationException) { if (scanRevision.get() == revision) status = "Refresh cancelled or timed out. Previous results kept."; throw cancelled }
            catch (_: Exception) { if (scanRevision.get() == revision) error = "The index could not be saved. Previous results kept." }
            finally { if (scanRevision.get() == revision) busy = false }
        }
    }
    private data class Built(val items: List<IndexedDocument>, val bytes: Int, val limited: Boolean)
    private suspend fun crawl(folder: SearchFolder, maximum: Int, byteBudget: Int): Built {
        data class Pending(val id: String, val path: String, val depth: Int)
        val tree = Uri.parse(folder.uri); val queue = ArrayDeque<Pending>(); val seen = HashSet<String>(); val items = ArrayList<IndexedDocument>()
        queue.add(Pending(DocumentsContract.getTreeDocumentId(tree), folder.title, 0))
        var bytes = 0; var directories = 0; var limited = false
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            if (++directories > FolderIndexRules.MAX_DIRECTORIES) { limited = true; break }
            val parent = queue.removeFirst()
            if (!seen.add(parent.id)) continue
            val children = FolderProviderQueries.query(app, DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent.id))
            limited = limited || children.partial
            for (child in children.documents) {
                currentCoroutineContext().ensureActive()
                val path = parent.path + "/" + child.title
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, child.id)
                if (path.length > 4096 || uri.toString().length > 8192) { limited = true; continue }
                val entry = IndexedDocument(FolderIndexRules.id(requireNotNull(tree.authority), child.id), folder.uri, uri.toString(), child.title, path, child.mime, child.modified)
                val size = encodedSize(entry)
                if (items.size >= maximum || bytes + size > byteBudget) return Built(items, bytes, true)
                items.add(entry); bytes += size
                if (entry.directory && child.id !in seen) {
                    if (parent.depth < FolderIndexRules.MAX_DEPTH) queue.add(Pending(child.id, path, parent.depth + 1)) else limited = true
                }
            }
        }
        return Built(items, bytes, limited)
    }
}
