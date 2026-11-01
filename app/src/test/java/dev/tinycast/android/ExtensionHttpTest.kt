package dev.tinycast.android

import dev.tinycast.android.extensions.ExtensionHttpClient
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ExtensionHttpTest {
    private class Connection(private val source: InputStream, private val code: Int = 200,
                             private val location: String? = null) : HttpURLConnection(URL("https://fixture.example/body")) {
        val started = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected.countDown() }
        override fun getResponseCode(): Int = code
        override fun getResponseMessage() = "Fixture"
        override fun getHeaderFields(): Map<String,List<String>> = mapOf("Content-Type" to listOf("text/plain"))
        override fun getHeaderField(name: String?): String? = if (name == "Location") location else null
        override fun getInputStream(): InputStream { started.countDown(); return source }
    }
    private fun await(latch: CountDownLatch) { assertTrue("Expected transport event", latch.await(5,TimeUnit.SECONDS)) }
    private suspend fun get(client: ExtensionHttpClient, headers: Map<String,String> = emptyMap(), limit: Int = 1024) =
        client.request("https://fixture.example/body", "GET", headers, null, limit)

    @Test fun absoluteDeadlineStopsAStreamThatKeepsDeliveringBytes() = runBlocking {
        val stream = object : InputStream() {
            override fun read(): Int { Thread.sleep(10); return 65 }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int { bytes[offset]=read().toByte(); return 1 }
        }
        val connection=Connection(stream)
        ExtensionHttpClient(deadlineMillis=200,connect={connection}).use { client ->
            val result=runCatching { withTimeout(5000) { get(client) } }
            assertTrue(result.exceptionOrNull() is IOException)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("timed out"))
            await(connection.disconnected)
        }
    }
    @Test fun cancellationDisconnectsAReadThatIgnoresThreadInterruption() = runBlocking {
        lateinit var connection: Connection
        val stream=object:InputStream() {
            override fun read(): Int {
                while(connection.disconnected.count > 0) {
                    try { connection.disconnected.await(20,TimeUnit.MILLISECONDS) } catch (_:InterruptedException) { /* Deliberately uncooperative read. */ }
                }
                throw IOException("Transport closed")
            }
        }
        connection=Connection(stream)
        ExtensionHttpClient(connect={connection}).use { client ->
            val job=launch(Dispatchers.Default) { get(client) }
            withContext(Dispatchers.IO) { await(connection.started) }
            job.cancelAndJoin()
            withContext(Dispatchers.IO) { await(connection.disconnected) }
            assertTrue(job.isCancelled)
        }
    }
    @Test fun repeatedCommandScopeCancellationReleasesEveryTransport() = runBlocking {
        val started=kotlinx.coroutines.channels.Channel<Connection>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        ExtensionHttpClient(connect={
            lateinit var connection: Connection
            connection=Connection(object:InputStream() {
                override fun read():Int {
                    started.trySend(connection)
                    while(connection.disconnected.count>0) {
                        try { connection.disconnected.await(20,TimeUnit.MILLISECONDS) } catch (_:InterruptedException) { }
                    }
                    throw IOException("Transport closed")
                }
            })
            connection
        }).use { client ->
            repeat(12) {
                val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
                val job=scope.launch { get(client) }
                val connection=withTimeout(5000) { started.receive() }
                scope.cancel(); job.join()
                withContext(Dispatchers.IO) { await(connection.disconnected) }
            }
        }
        started.close()
        Unit
    }
    @Test fun boundedBodyAlwaysClosesTheConnection() = runBlocking {
        val connection=Connection(ByteArrayInputStream(ByteArray(20)))
        ExtensionHttpClient(connect={connection}).use { client ->
            assertTrue(runCatching { get(client,limit=10) }.exceptionOrNull() is IllegalArgumentException)
            await(connection.disconnected)
        }
    }
    @Test fun credentialRedirectCannotChangePorts() = runBlocking {
        val calls=AtomicInteger()
        val connection=Connection(ByteArrayInputStream(byteArrayOf()),302,"https://fixture.example:444/next")
        ExtensionHttpClient(connect={ calls.incrementAndGet(); connection }).use { client ->
            assertTrue(runCatching { get(client,mapOf("Authorization" to "test fixture")) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(1,calls.get()); await(connection.disconnected)
        }
    }
    @Test fun completedResponseContainsNoLiveConnection() = runBlocking {
        val connection=Connection(ByteArrayInputStream("body".toByteArray()))
        ExtensionHttpClient(connect={connection}).use { client ->
            val response=get(client)
            assertEquals("body",String(response.bytes)); assertEquals(200,response.status)
            assertEquals(listOf("text/plain"),response.headers["Content-Type"])
            assertEquals(0,connection.disconnected.count.toInt())
        }
    }
    @Test fun plaintextIsRejectedBeforeCreatingATransport() = runBlocking {
        ExtensionHttpClient(connect={error("Must not open plaintext")}).use { client ->
            val failure=runCatching { client.request("http://fixture.example","GET",emptyMap(),null,1024) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        }
    }
}
