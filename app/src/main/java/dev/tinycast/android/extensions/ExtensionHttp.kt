package dev.tinycast.android.extensions

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Immutable response: the connection is always closed before it reaches its caller. */
data class ExtensionHttpResponse(val status: Int, val message: String, val url: String,
                                 val headers: Map<String, List<String>>, val bytes: ByteArray)

/** Bounded process-wide workers. Closing a command cancels its queued/running requests. */
internal class ExtensionHttpClient(
    private val deadlineMillis: Long = 45_000,
    private val connect: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection }
) : Closeable {
    private val workers = pool("extension-http", 4, 32)
    // disconnect() can itself block in a platform implementation. Never call it on
    // the UI thread or deadline scheduler; those threads must remain responsive.
    private val closers = pool("extension-http-close", 4, 64)
    private val timer = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "extension-http-deadline").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }
    private val cancellations = ConcurrentHashMap.newKeySet<() -> Unit>()
    private val closed = AtomicBoolean(false)

    init { require(deadlineMillis > 0) }

    suspend fun request(url: String, method: String, headers: Map<String, String>, body: ByteArray?, limit: Int): ExtensionHttpResponse =
        suspendCancellableCoroutine { continuation ->
            require(limit > 0)
            val terminal = AtomicBoolean(false)
            val connection = AtomicReference<HttpURLConnection?>()
            val scheduled = AtomicReference<ScheduledFuture<*>?>()
            val work = AtomicReference<FutureTask<Unit>?>()
            fun disconnect() {
                connection.getAndSet(null)?.let { current ->
                    // Capacity exceeds the maximum number of outstanding requests.
                    runCatching { closers.execute { runCatching { current.disconnect() } } }
                }
            }
            fun stop() {
                work.get()?.let { it.cancel(true); workers.remove(it) }
                scheduled.getAndSet(null)?.cancel(false)
                disconnect()
            }
            val cancel: () -> Unit = {
                if (terminal.compareAndSet(false, true)) {
                    stop()
                    continuation.cancel(CancellationException("Extension request cancelled"))
                }
            }
            cancellations.add(cancel)
            continuation.invokeOnCancellation { cancel(); cancellations.remove(cancel) }
            val task = FutureTask<Unit> {
                fun checkActive() {
                    if (terminal.get() || !continuation.isActive || Thread.currentThread().isInterrupted) throw CancellationException()
                }
                try {
                    var target = URI(url)
                    for (redirect in 0 until 6) {
                        checkActive()
                        require(target.scheme == "https" && !target.host.isNullOrBlank()) { "Only HTTPS requests are supported" }
                        val current = connect(target)
                        connection.set(current)
                        var response: ExtensionHttpResponse? = null
                        try {
                            checkActive()
                            current.connectTimeout = minOf(15_000L, deadlineMillis).toInt()
                            current.readTimeout = minOf(25_000L, deadlineMillis).toInt()
                            current.instanceFollowRedirects = false; current.useCaches = false; current.requestMethod = method
                            current.setRequestProperty("User-Agent", "Droidcast Android/0.5.0-nightly")
                            current.setRequestProperty("Cache-Control", "no-cache")
                            headers.forEach { (key, value) -> current.setRequestProperty(key, value) }
                            if (body != null && method !in listOf("GET", "HEAD")) {
                                current.doOutput = true
                                current.outputStream.use { it.write(body) }
                            }
                            checkActive()
                            val status = current.responseCode
                            if (status in listOf(301, 302, 303, 307, 308)) {
                                val next = target.resolve(current.getHeaderField("Location") ?: error("Invalid redirect"))
                                val sameOrigin = next.scheme == target.scheme && next.host.equals(target.host, true) &&
                                    (if (next.port < 0) 443 else next.port) == (if (target.port < 0) 443 else target.port)
                                require(sameOrigin || (headers.isEmpty() && body == null)) { "Cross-origin redirects with credentials are unsupported" }
                                target = next
                            } else {
                                val stream = if (status >= 400) current.errorStream else current.inputStream
                                val bytes = stream?.use { input ->
                                    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                                    while (true) {
                                        checkActive()
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        require(count <= limit - out.size()) { "Response exceeds the download limit" }
                                        out.write(buffer, 0, count)
                                    }
                                    out.toByteArray()
                                } ?: byteArrayOf()
                                checkActive()
                                response = ExtensionHttpResponse(status, current.responseMessage.orEmpty(), current.url.toString(),
                                    current.headerFields.filterKeys { it != null }.mapValues { it.value.toList() }, bytes)
                            }
                        } finally {
                            // Leave it published until disconnect returns so cancellation or
                            // the absolute deadline can still unblock a slow platform close.
                            runCatching { current.disconnect() }
                            connection.compareAndSet(current, null)
                        }
                        response?.let { completed ->
                            if (terminal.compareAndSet(false, true)) continuation.resumeWith(Result.success(completed))
                            return@FutureTask
                        }
                    }
                    error("Too many redirects")
                } catch (failure: Throwable) {
                    if (terminal.compareAndSet(false, true)) continuation.resumeWith(Result.failure(failure))
                } finally {
                    scheduled.getAndSet(null)?.cancel(false)
                    cancellations.remove(cancel)
                }
            }
            work.set(task)
            try {
                check(!closed.get()) { "HTTP client closed" }
                scheduled.set(timer.schedule({
                    if (terminal.compareAndSet(false, true)) {
                        stop(); cancellations.remove(cancel)
                        continuation.resumeWith(Result.failure(IOException("Extension request timed out. Try again.")))
                    }
                }, deadlineMillis, TimeUnit.MILLISECONDS))
                if (!terminal.get()) workers.execute(task) else stop()
            } catch (failure: Throwable) {
                if (terminal.compareAndSet(false, true)) { stop(); continuation.resumeWith(Result.failure(failure)) }
                cancellations.remove(cancel)
            }
        }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            cancellations.toList().forEach { it() }
            workers.shutdownNow(); timer.shutdownNow(); closers.shutdown()
        }
    }
    private fun pool(name: String, threads: Int, queue: Int) = ThreadPoolExecutor(threads, threads, 10, TimeUnit.SECONDS,
        ArrayBlockingQueue(queue), { task -> Thread(task, name).apply { isDaemon = true } })
}

object ExtensionHttp {
    private val client = ExtensionHttpClient()
    suspend fun request(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(), body: ByteArray? = null,
                        limit: Int = 32 * 1024 * 1024) = client.request(url, method, headers, body, limit)
    suspend fun download(url: String, limit: Int = 32 * 1024 * 1024): ByteArray {
        val response = request(url, limit = limit)
        check(response.status in 200..299) { "Store request failed (HTTP ${response.status}). Try again." }
        return response.bytes
    }
}
