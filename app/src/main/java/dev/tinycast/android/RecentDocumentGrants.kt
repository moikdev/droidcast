package dev.tinycast.android

import android.content.ContentResolver
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.UUID

/** Only the recent-document picker acquires individual document grants. Folder roots
 * have their own owner. All file-reference edits are published on the main thread. */
internal class RecentDocumentGrants(
    private val prefs: SharedPreferences,
    private val resolver: ContentResolver,
    private val persist: suspend () -> Boolean = {
        withContext(Dispatchers.IO) {
            prefs.edit().putString(CHECKPOINT, UUID.randomUUID().toString()).commit()
        }
    },
    private val releaseRead: (Uri) -> Unit = {
        resolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
) {
    companion object {
        private const val OWNED = "recentDocumentOwnedReads"
        private const val CHECKPOINT = "recentDocumentCheckpoint"
        private val operations = Mutex()
        private var referenceRevision = 0L

        fun referencesChanged() { referenceRevision++ }
    }

    private fun owned() = prefs.getStringSet(OWNED, emptySet()).orEmpty()
    private fun record(uris: Set<String>) = prefs.edit().putStringSet(OWNED, uris).apply()
    private fun hasRead(uri: Uri) = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    // Release must fail closed if the saved references cannot all be decoded.
    private fun referenced(): Set<String> {
        val rows = JSONArray(prefs.getString("files", "[]"))
        return (0 until rows.length()).mapTo(mutableSetOf()) { rows.getJSONObject(it).getString("body") }
    }

    private suspend fun checkpoint() {
        // A unique value forces an actual write even after an earlier failed apply.
        // If a file was added and removed again across this barrier, repeat it.
        do {
            val revision = referenceRevision
            check(persist()) { "Recent document changes could not be saved." }
        } while (revision != referenceRevision)
    }

    suspend fun acquire(uri: Uri) = operations.withLock {
        val value = uri.toString()
        if (value !in referenced() || hasRead(uri)) return@withLock
        // A folder picker returns a bare tree. Never acquire one for a recent.
        if (DocumentsContract.isTreeUri(uri) && uri.pathSegments.getOrNull(2) != "document") return@withLock
        record(owned() + value)
        try {
            checkpoint()
            if (value !in referenced() || hasRead(uri)) {
                // Deleted during the barrier, or another owner supplied access first.
                record(owned() - value)
                return@withLock
            }
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // No successful take. A cancellation at the disk barrier also ends here.
            record(owned() - value)
            throw e
        }
    }

    suspend fun releaseUnused() = operations.withLock {
        if (owned().isEmpty() || (owned() - referenced()).isEmpty()) return@withLock
        checkpoint()
        var failure: RuntimeException? = null
        for (value in owned() - referenced()) {
            val uri = Uri.parse(value)
            try {
                if (hasRead(uri)) releaseRead(uri)
                record(owned() - value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                // Keep this URI for a later retry, and still clean up other grants.
                failure = e
            }
        }
        failure?.let { throw it }
        Unit
    }
}
