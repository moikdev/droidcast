package dev.tinycast.android

import android.app.Activity
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Test-APK UID, read-only fixture. No root is advertised without the guarded
 * host lease. DocumentsUI must grant access through its real selection flow.
 */
class OwnedFormDocumentsProvider : DocumentsProvider() {
    companion object {
        internal fun fixture(context: Context, expected: String? = null): File {
            val root = File(context.filesDir.canonicalFile, "form-document-fixture")
            require(root.absoluteFile == root.canonicalFile && Files.isDirectory(root.toPath(), NOFOLLOW_LINKS))
            val marker = File(root, "owner.json")
            require(Files.isRegularFile(marker.toPath(), NOFOLLOW_LINKS) && marker.length() in 1..4096)
            val owner = JSONObject(marker.readText())
            require(owner.keys().asSequence().toSet() == setOf("schema_version", "fixture_kind", "owner_nonce"))
            require(owner.getInt("schema_version") == 1 && owner.getString("fixture_kind") == "form-document-provider-v1")
            val nonce = owner.getString("owner_nonce")
            require(nonce.matches(Regex("[0-9a-f]{32}")) && (expected == null || nonce == expected))
            return root
        }
    }
    private data class Entry(val name: String, val path: String, val directory: Boolean = false)
    private val entries = mapOf(
        "root" to Entry("Droidcast owned files", "data", true),
        "text" to Entry("café 日本.txt", "data/text.bin"),
        "binary" to Entry("owned-bytes.bin", "data/binary.bin"),
        "oversize" to Entry("Owned too large.bin", "data/oversize.bin"),
        "folder" to Entry("Owned folder", "data/folder", true),
        "nested" to Entry("nested.txt", "data/folder/nested.bin")
    )
    private val documentColumns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED)
    override fun onCreate() = true
    private fun root(): File = try { fixture(requireNotNull(context)) } catch (_: Exception) { throw FileNotFoundException("Owned document fixture is unavailable") }
    private fun entry(id: String): Pair<Entry, File> {
        val parent = root(); val entry = entries[id] ?: throw FileNotFoundException("Unknown owned document")
        val file = File(parent, entry.path)
        if (file.absoluteFile != file.canonicalFile || !(if (entry.directory) Files.isDirectory(file.toPath(), NOFOLLOW_LINKS) else Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)))
            throw FileNotFoundException("Owned document is unavailable")
        return entry to file
    }
    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_ICON, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES)
        val result = MatrixCursor(columns)
        if (runCatching { root() }.isFailure) return result
        val values = mapOf(DocumentsContract.Root.COLUMN_ROOT_ID to "owned-form-root", DocumentsContract.Root.COLUMN_DOCUMENT_ID to "root",
            DocumentsContract.Root.COLUMN_TITLE to "Droidcast owned files", DocumentsContract.Root.COLUMN_FLAGS to DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD,
            DocumentsContract.Root.COLUMN_MIME_TYPES to "*/*", DocumentsContract.Root.COLUMN_ICON to android.R.drawable.ic_menu_agenda,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES to 64L * 1024 * 1024)
        result.addRow(columns.map { values[it] }.toTypedArray()); return result
    }
    private fun add(cursor: MatrixCursor, id: String) {
        val (entry, file) = entry(id)
        val values = mapOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID to id, DocumentsContract.Document.COLUMN_DISPLAY_NAME to entry.name,
            DocumentsContract.Document.COLUMN_MIME_TYPE to if (entry.directory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream",
            DocumentsContract.Document.COLUMN_FLAGS to 0, DocumentsContract.Document.COLUMN_LAST_MODIFIED to 1_700_000_000_000L,
            DocumentsContract.Document.COLUMN_SIZE to if (id == "oversize") 17L * 1024 * 1024 else if (entry.directory) null else file.length())
        cursor.addRow(cursor.columnNames.map { values[it] }.toTypedArray())
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = MatrixCursor(projection ?: documentColumns).apply { add(this, documentId) }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        entry(parentDocumentId)
        val ids = when (parentDocumentId) { "root" -> listOf("folder", "text", "binary", "oversize"); "folder" -> listOf("nested"); else -> throw FileNotFoundException("Not a fixture directory") }
        return MatrixCursor(projection ?: documentColumns).apply { ids.forEach { add(this, it) } }
    }
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        entry(parentDocumentId); entry(documentId)
        return (parentDocumentId == "root" && documentId != "root") || (parentDocumentId == "folder" && documentId == "nested")
    }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        signal?.throwIfCanceled()
        val (entry, file) = entry(documentId)
        if (mode != "r" || entry.directory) throw FileNotFoundException("Fixture documents are read-only")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
}

/** Refresh DocumentsUI's root cache only after the host has established its
 * matching private lease. This helper neither reads nor modifies the clipboard.
 */
class FormDocumentsFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            val owner = requireNotNull(intent.getStringExtra("owner_nonce"))
            OwnedFormDocumentsProvider.fixture(this, owner)
            contentResolver.notifyChange(DocumentsContract.buildRootsUri(packageName + ".formdocuments"), null)
        }
        finish()
    }
}
