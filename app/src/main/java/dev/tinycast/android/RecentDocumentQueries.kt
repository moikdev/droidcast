package dev.tinycast.android

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal interface RecentDocumentReader {
    suspend fun displayName(uri: Uri): String?
    suspend fun mimeType(uri: Uri): String?
}

internal class DocumentProviderTimeout : IOException("Storage provider timed out")

/** Shared bounds also apply when an Activity is replaced while a provider is stuck. */
private object RecentDocumentWorkers {
    val queries = ThreadPoolExecutor(2, 2, 10, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(4),
        { task -> Thread(task, "Droidcast-document-provider").apply { isDaemon = true } })
    val cancellations = ThreadPoolExecutor(2, 2, 10, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(8),
        { task -> Thread(task, "Droidcast-document-cancel").apply { isDaemon = true } })
}

/** getType has no provider cancellation API; abandoned calls still occupy bounded workers. */
internal class DocumentProviderQueries(private val resolver: ContentResolver, private val timeoutMillis: Long = 3_000) : RecentDocumentReader {
    private data class Value<T>(val value: T)
    private suspend fun <T> query(operation: (CancellationSignal) -> T): T {
        val result = withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine<Value<T>> { continuation ->
                val signal = CancellationSignal()
                val task = FutureTask<Unit> {
                    try {
                        signal.throwIfCanceled()
                        val value = operation(signal)
                        if (continuation.isActive) continuation.resumeWith(Result.success(Value(value)))
                    } catch (failure: Exception) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(failure))
                    }
                }
                continuation.invokeOnCancellation {
                    task.cancel(true); RecentDocumentWorkers.queries.remove(task)
                    // A provider cancellation listener may block. Keep it off the caller/main thread.
                    runCatching { RecentDocumentWorkers.cancellations.execute { runCatching { signal.cancel() } } }
                }
                try { RecentDocumentWorkers.queries.execute(task) }
                catch (failure: java.util.concurrent.RejectedExecutionException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(IOException("Storage provider is busy. Try again.", failure)))
                }
            }
        } ?: throw DocumentProviderTimeout()
        return result.value
    }
    override suspend fun displayName(uri: Uri): String? = query { signal ->
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, signal)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.replace(Regex("[\\p{Cntrl}]"), " ")?.take(512)?.takeIf { it.isNotBlank() } else null
        }
    }
    override suspend fun mimeType(uri: Uri): String? = query { resolver.getType(uri) }
}
