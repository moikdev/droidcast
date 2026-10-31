package dev.tinycast.android.extensions

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal sealed interface GridVisual {
    data class Image(val source: String, val mask: String = "") : GridVisual
    data class Symbol(val value: String) : GridVisual
    data class Swatch(val value: String) : GridVisual
    data object Unsupported : GridVisual
}

internal fun gridVisual(value: Any?, depth: Int = 0): GridVisual {
    if (depth > 4) return GridVisual.Unsupported
    if (value is JSONObject) {
        if (value.has("value")) return gridVisual(value.opt("value"), depth + 1)
        if (value.has("color")) {
            val color = value.opt("color")
            return GridVisual.Swatch(if (color is JSONObject) color.optString("dark", color.optString("light")) else color.toString())
        }
        var source = value.opt("source")
        if (source is JSONObject) source = source.opt("dark") ?: source.opt("light")
        return (gridVisual(source, depth + 1) as? GridVisual.Image)?.copy(mask = value.optString("mask"))
            ?: gridVisual(source, depth + 1)
    }
    if (value !is String || value.isBlank() || value.length > 8192) return GridVisual.Unsupported
    if (value.endsWith("-16")) return GridVisual.Symbol(when (value) {
        "star-16", "star-filled-16" -> "★"; "heart-16", "heart-filled-16" -> "♥"
        "circle-16", "circle-filled-16" -> "●"; "square-16", "square-filled-16" -> "■"
        "checkmark-16" -> "✓"; "x-mark-16" -> "×"; else -> return GridVisual.Unsupported
    })
    if (value.codePoints().anyMatch { it > 127 } && !value.contains('/') && !value.contains(':') && !value.contains('.')) return GridVisual.Symbol(value)
    return GridVisual.Image(value)
}

/** The package boundary is checked after resolving symlinks and URI escaping. */
internal fun gridAsset(packageDirectory: File, source: String): File {
    require(!source.contains('\u0000'))
    val root = packageDirectory.canonicalFile
    val uri = URI(source.replace(" ", "%20"))
    require(uri.scheme == null || uri.scheme == "file") { "Unsupported image source" }
    if (uri.scheme == "file") require(uri.authority.isNullOrBlank() && uri.query == null && uri.fragment == null)
    val path = if (uri.scheme == "file") uri.path else source
    val candidate = if (File(path).isAbsolute) File(path) else File(File(root, "assets"), path)
    val file = candidate.canonicalFile
    require(file.path.startsWith(root.path + File.separator)) { "Image is outside its extension package" }
    return file
}

/** Bounded thumbnails, scoped to one command session. No WebView/file-provider image loads. */
internal class ExtensionGridImages(private val directory: File) : Closeable {
    companion object { const val MAX_BYTES = 2 * 1024 * 1024; const val MAX_CACHE = 12 * 1024 * 1024 }
    private val executor = ThreadPoolExecutor(3, 3, 10, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(32))
    private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val cache = object : LinkedHashMap<String, Bitmap>(16, .75f, true) {}
    private var cacheBytes = 0
    @Volatile private var closed = false

    suspend fun load(source: String): Bitmap = suspendCancellableCoroutine { continuation ->
        val cached = synchronized(cache) { cache[source] }
        if (cached != null && !closed) {
            continuation.resumeWith(Result.success(cached)); return@suspendCancellableCoroutine
        }
        val work = java.util.concurrent.atomic.AtomicReference<Future<*>?>()
        val requestConnections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
        fun cancelWork() { work.get()?.let { future -> future.cancel(true); (future as? Runnable)?.let(executor::remove) } }
        continuation.invokeOnCancellation { requestConnections.forEach { it.disconnect() }; cancelWork() }
        try {
            check(!closed)
            work.set(executor.submit {
                try {
                    check(!closed && continuation.isActive)
                    val bytes = if (source.startsWith("https:", true)) download(source, requestConnections) else {
                        val file = gridAsset(directory, source)
                        require(file.isFile && file.length() in 1L..MAX_BYTES.toLong())
                        file.inputStream().use { boundedRead(it) }
                    }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    require(bounds.outWidth in 1..32768 && bounds.outHeight in 1..32768) { "Unsupported image" }
                    val options = BitmapFactory.Options().apply { inSampleSize = 1 }
                    while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 256) options.inSampleSize *= 2
                    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) { "Unsupported image" }
                    synchronized(cache) {
                        if (!closed && continuation.isActive) {
                            cache.put(source, bitmap)?.let { cacheBytes -= it.allocationByteCount }
                            cacheBytes += bitmap.allocationByteCount
                            while (cacheBytes > MAX_CACHE && cache.isNotEmpty()) {
                                val entry = cache.entries.iterator(); cacheBytes -= entry.next().value.allocationByteCount; entry.remove()
                            }
                        }
                    }
                    if (continuation.isActive) continuation.resumeWith(Result.success(bitmap))
                } catch (error: Exception) { if (continuation.isActive) continuation.resumeWith(Result.failure(error)) }
            })
            if (!continuation.isActive) cancelWork()
        } catch (error: Exception) { if (continuation.isActive) continuation.resumeWith(Result.failure(error)) }
    }

    private fun boundedRead(input: java.io.InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            check(!closed && !Thread.currentThread().isInterrupted)
            val count = input.read(buffer); if (count < 0) break
            require(output.size() + count <= MAX_BYTES) { "Image exceeds size limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun download(source: String, requests: MutableSet<HttpURLConnection>): ByteArray {
        var uri = URI(source)
        repeat(4) {
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null) { "Only HTTPS image URLs are supported" }
            check(!closed && !Thread.currentThread().isInterrupted)
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 5000; connection.readTimeout = 5000; connection.instanceFollowRedirects = false
            connection.useCaches = false
            requests.add(connection); connections.add(connection)
            try {
                check(!closed && !Thread.currentThread().isInterrupted)
                if (connection.responseCode in listOf(301, 302, 303, 307, 308)) {
                    uri = uri.resolve(requireNotNull(connection.getHeaderField("Location")))
                } else {
                    require(connection.responseCode in 200..299 && connection.contentLengthLong <= MAX_BYTES) { "Image unavailable" }
                    return connection.inputStream.use(::boundedRead)
                }
            } finally { connections.remove(connection); requests.remove(connection); connection.disconnect() }
        }
        error("Too many image redirects")
    }

    override fun close() {
        closed = true; connections.forEach { it.disconnect() }; executor.shutdownNow()
        synchronized(cache) { cache.clear(); cacheBytes = 0 }
    }
}
