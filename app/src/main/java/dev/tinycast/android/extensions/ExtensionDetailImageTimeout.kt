package dev.tinycast.android.extensions

import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Bounds the complete job, including queue time and a continuously trickling response. */
internal suspend fun <T : Any> withDetailImageTimeout(timeoutMillis: Long = 15_000,
    operation: suspend () -> T): T {
    require(timeoutMillis in 1L..15_000L)
    // Only this deadline becomes a readable image failure. Cancellation from
    // a stopped/offscreen view still propagates to its owning producer.
    val result = withTimeoutOrNull(timeoutMillis) { operation() }
    currentCoroutineContext().ensureActive()
    return result ?: throw IOException("Image request timed out")
}
