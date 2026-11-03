package dev.tinycast.android.extensions

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** Command-owned static images; parsing, network and rasterization never run on Main. */
internal class ExtensionDetailImages(private val directory: File) : Closeable {
    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        const val MAX_EDGE = 1024
        const val MAX_CACHE_BYTES = 8 * 1024 * 1024
        init { SVG.setInternalEntitiesEnabled(false); SVG.deregisterExternalFileResolver() }
    }
    private val executor = ThreadPoolExecutor(2, 2, 10, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(24))
    private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val cache = LinkedHashMap<String, Bitmap>(16, .75f, true)
    private var cacheBytes = 0
    @Volatile private var closed = false

    suspend fun load(source: String, edge: Int): Bitmap = suspendCancellableCoroutine { continuation ->
        val work = AtomicReference<Future<*>?>()
        val requests = ConcurrentHashMap.newKeySet<HttpURLConnection>()
        fun cancel() { work.get()?.let { future -> future.cancel(true); (future as? Runnable)?.let(executor::remove) } }
        continuation.invokeOnCancellation { requests.forEach { it.disconnect() }; cancel() }
        try {
            require(edge in 16..MAX_EDGE && source.length <= 3 * 1024 * 1024)
            check(!closed)
            work.set(executor.submit {
                fun active() { check(!closed && continuation.isActive && !Thread.currentThread().isInterrupted) }
                try {
                    active()
                    val key = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) } + ":$edge"
                    val cached = synchronized(cache) { cache[key] }
                    if (cached != null) {
                        active(); continuation.resumeWith(Result.success(cached)); return@submit
                    }
                    val bytes = when {
                        source.startsWith("data:", true) -> data(source)
                        source.startsWith("https:", true) -> download(source, requests, ::active)
                        else -> {
                            val file = detailImageAsset(directory, source)
                            require(file.isFile && file.length() in 1L..MAX_BYTES.toLong())
                            file.inputStream().use { boundedRead(it, ::active) }
                        }
                    }
                    active()
                    val isSvg = bytes.firstOrNull { it.toInt().toChar() !in listOf(' ', '\t', '\r', '\n') } == '<'.code.toByte()
                    val bitmap = if (isSvg) svg(bytes, edge, ::active) else raster(bytes, edge)
                    active()
                    synchronized(cache) {
                        if (!closed && continuation.isActive) {
                            cache.put(key, bitmap)?.let { cacheBytes -= it.allocationByteCount }
                            cacheBytes += bitmap.allocationByteCount
                            while ((cacheBytes > MAX_CACHE_BYTES || cache.size > 32) && cache.isNotEmpty()) {
                                val iterator = cache.entries.iterator(); cacheBytes -= iterator.next().value.allocationByteCount; iterator.remove()
                            }
                        }
                    }
                    active(); continuation.resumeWith(Result.success(bitmap))
                } catch (error: Exception) { if (continuation.isActive) continuation.resumeWith(Result.failure(error)) }
            })
            if (!continuation.isActive) cancel()
        } catch (error: Exception) { if (continuation.isActive) continuation.resumeWith(Result.failure(error)) }
    }

    private fun data(source: String): ByteArray {
        val comma = source.indexOf(','); require(comma in 1..128)
        val header = source.substring(0, comma).lowercase()
        val mime = header.substringBefore(';')
        require(mime in setOf("data:image/png", "data:image/jpeg", "data:image/jpg", "data:image/gif", "data:image/webp", "data:image/svg+xml"))
        val payload = source.substring(comma + 1)
        val bytes = if (header.endsWith(";base64")) {
            require(header == "$mime;base64" && payload.length <= (MAX_BYTES + 2) / 3 * 4)
            Base64.getDecoder().decode(payload)
        } else {
            require(header == mime && mime == "data:image/svg+xml")
            URLDecoder.decode(payload.replace("+", "%2B"), "UTF-8").toByteArray(Charsets.UTF_8)
        }
        require(bytes.size in 1..MAX_BYTES)
        return bytes
    }

    private fun raster(bytes: ByteArray, edge: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..32768 && bounds.outHeight in 1..32768) { "Unsupported image" }
        val options = BitmapFactory.Options().apply { inSampleSize = 1; inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > edge) options.inSampleSize *= 2
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) { "Unsupported image" }
        if (bitmap.width > edge || bitmap.height > edge || bitmap.allocationByteCount > MAX_EDGE * MAX_EDGE * 4) {
            bitmap.recycle(); error("Image exceeds raster limit")
        }
        return bitmap
    }

    private fun svg(bytes: ByteArray, edge: Int, active: () -> Unit): Bitmap {
        require(bytes.size <= 256 * 1024)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        ExtensionSvgPolicy.inspect(text, active); active()
        val document = SVG.getFromString(text); active()
        val aspect = document.documentAspectRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
        require(aspect in (1f / MAX_EDGE)..MAX_EDGE.toFloat())
        val width = if (aspect >= 1f) edge else (edge * aspect).roundToInt().coerceAtLeast(1)
        val height = if (aspect >= 1f) (edge / aspect).roundToInt().coerceAtLeast(1) else edge
        if (document.documentViewBox == null) document.setDocumentViewBox(0f, 0f,
            document.documentWidth.takeIf { it.isFinite() && it > 0f } ?: 100f,
            document.documentHeight.takeIf { it.isFinite() && it > 0f } ?: 100f)
        document.setDocumentWidth(width.toFloat()); document.setDocumentHeight(height.toFloat())
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            try { document.renderToCanvas(Canvas(it)); active() }
            catch (failure: Exception) { it.recycle(); throw failure }
        }
    }

    private fun boundedRead(input: java.io.InputStream, active: () -> Unit): ByteArray {
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            active(); val count = input.read(buffer); if (count < 0) break
            require(output.size() + count <= MAX_BYTES); output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun download(source: String, requests: MutableSet<HttpURLConnection>, active: () -> Unit): ByteArray {
        var uri = URI(source)
        repeat(4) {
            require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.port in listOf(-1, 443))
            active()
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 5000; connection.readTimeout = 5000; connection.instanceFollowRedirects = false; connection.useCaches = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            requests.add(connection); connections.add(connection)
            try {
                active()
                if (connection.responseCode in listOf(301, 302, 303, 307, 308)) uri = uri.resolve(requireNotNull(connection.getHeaderField("Location")))
                else {
                    require(connection.responseCode in 200..299 && connection.contentLengthLong <= MAX_BYTES &&
                        connection.contentEncoding.let { it.isNullOrEmpty() || it.equals("identity", true) })
                    return connection.inputStream.use { boundedRead(it, active) }
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
