package dev.tinycast.android.extensions

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal data class FormFileStorage(val filesDirectory: File, val extensionId: String) {
    fun root(): File = formFileStorageRoot(filesDirectory, extensionId)
}

/** Only user-selected content URIs enter this adapter. Tree descendants retain
 * the selected tree's grant, rather than acquiring unrelated document access.
 */
internal class AndroidFormDocuments(private val resolver: ContentResolver, private val signal: CancellationSignal) : FormDocumentSource {
    private fun uri(key: String): Uri = Uri.parse(key).also {
        require(it.scheme == ContentResolver.SCHEME_CONTENT && !it.authority.isNullOrBlank()) { "Choose a document from Android's file picker" }
    }
    private fun normalized(key: String): Uri {
        val uri = uri(key)
        return if (DocumentsContract.isTreeUri(uri) && uri.pathSegments.size == 2)
            DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)) else uri
    }
    private fun Cursor.string(column: String): String? = getColumnIndex(column).takeIf { it >= 0 }?.let { if (isNull(it)) null else getString(it) }
    private fun Cursor.long(column: String): Long? = getColumnIndex(column).takeIf { it >= 0 }?.let { if (isNull(it)) null else getLong(it) }
    private fun readDocument(cursor: Cursor, uri: Uri): FormDocument {
        val flags = cursor.long(DocumentsContract.Document.COLUMN_FLAGS) ?: 0L
        require(flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT.toLong() == 0L) { "Choose a document with downloadable file contents" }
        val name = cursor.string(OpenableColumns.DISPLAY_NAME) ?: throw IOException("The document has no file name")
        val size = cursor.long(OpenableColumns.SIZE)?.takeIf { it >= 0 }
        val directory = cursor.string(DocumentsContract.Document.COLUMN_MIME_TYPE) == DocumentsContract.Document.MIME_TYPE_DIR
        return FormDocument(uri.toString(), name, directory, size)
    }
    override fun document(key: String): FormDocument {
        signal.throwIfCanceled()
        val uri = normalized(key)
        return resolver.query(uri, null, null, null, null, signal)?.use { cursor ->
            if (!cursor.moveToFirst()) throw IOException("The selected document is unavailable")
            readDocument(cursor, uri)
        } ?: throw IOException("The selected document is unavailable")
    }
    override fun children(document: FormDocument, visit: (FormDocument) -> Unit) {
        val parent = uri(document.key)
        require(DocumentsContract.isTreeUri(parent)) { "Choose the folder using Android's folder picker" }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        resolver.query(children, null, null, null, null, signal)?.use { cursor ->
            // A cloud provider can return an incomplete listing while fetching.
            // Never silently certify such a partial copy as a complete folder.
            if (cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) throw IOException("This folder is still loading. Try again when it is available.")
            while (cursor.moveToNext()) {
                signal.throwIfCanceled()
                val id = cursor.string(DocumentsContract.Document.COLUMN_DOCUMENT_ID) ?: throw IOException("A folder entry is unavailable")
                visit(readDocument(cursor, DocumentsContract.buildDocumentUriUsingTree(parent, id)))
            }
        } ?: throw IOException("This folder could not be listed")
    }
    override fun open(document: FormDocument): java.io.InputStream {
        signal.throwIfCanceled()
        val descriptor = resolver.openFileDescriptor(uri(document.key), "r", signal) ?: throw IOException("This document could not be opened")
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
    }
}

/** Stuck providers cannot consume an unbounded number of threads, even across
 * replacement Activities. Provider cancellation itself must also stay off Main.
 */
private object FormImportWorkers {
    val copies = ThreadPoolExecutor(2, 2, 10, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(4),
        { task -> Thread(task, "Droidcast-form-import").apply { isDaemon = true } })
    val cancellations = ThreadPoolExecutor(2, 2, 10, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(8),
        { task -> Thread(task, "Droidcast-form-import-cancel").apply { isDaemon = true } })
    val cleanup = ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(8),
        { task -> Thread(task, "Droidcast-form-import-cleanup").apply { isDaemon = true } })
}

/** A request keeps ownership through coroutine resumption and field delivery.
 * Cancellation racing with resumption cannot strand a live provider result in
 * the next field/session. Accepted paths remain available to the extension.
 */
internal class FormDocumentImportRequest(private val storage: FormFileStorage, private val keys: List<String>,
    private val files: Boolean, private val directories: Boolean,
    private val source: (CancellationSignal) -> FormDocumentSource) {
    private val lock = Any()
    private val signal = CancellationSignal()
    @Volatile private var cancelled = false
    private var started = false
    private var candidate: Pair<PreparedFormFiles, List<String>>? = null
    private var task: FutureTask<Unit>? = null

    suspend fun prepare() = suspendCancellableCoroutine<Unit> { continuation ->
        val work = FutureTask<Unit> {
            var prepared: PreparedFormFiles? = null
            try {
                signal.throwIfCanceled()
                val root = storage.root()
                recoverFormFileImports(root)
                val owned = FormFileImport(root).prepare(keys, source(signal), files, directories) { cancelled }
                prepared = owned
                if (cancelled) throw IOException("File import was cancelled")
                val paths = owned.commit()
                synchronized(lock) {
                    if (!cancelled) { candidate = owned to paths; prepared = null }
                }
                if (continuation.isActive) continuation.resumeWith(Result.success(Unit))
            } catch (failure: Exception) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(failure))
            } finally {
                prepared?.let { runCatching { it.discard() } }
            }
        }
        synchronized(lock) {
            check(!started) { "A file import request can only run once" }
            started = true; task = work
        }
        continuation.invokeOnCancellation { cancel() }
        try { FormImportWorkers.copies.execute(work) }
        catch (failure: RejectedExecutionException) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(IOException("File imports are busy. Try again.", failure)))
        }
    }

    /** Runs on Main. The acceptance callback and cancellation are serialized;
     * retaining a committed copy performs no I/O after callback dispatch.
     */
    fun deliver(accept: (List<String>) -> Boolean): Boolean = synchronized(lock) {
        val ready = candidate ?: return@synchronized false
        if (cancelled || !accept(ready.second)) return@synchronized false
        ready.first.retain(); candidate = null
        true
    }

    fun cancel() {
        val discarded = synchronized(lock) {
            if (cancelled) return
            cancelled = true
            task?.let { it.cancel(true); FormImportWorkers.copies.remove(it) }
            candidate.also { candidate = null }
        }
        runCatching { FormImportWorkers.cancellations.execute { runCatching { signal.cancel() } } }
        if (discarded != null) {
            // Rejection leaves only a private, undelivered copy. Never fall back
            // to blocking the UI thread or deleting a delivered import.
            runCatching { FormImportWorkers.cleanup.execute { runCatching { discarded.first.discard() } } }
        }
    }
}
