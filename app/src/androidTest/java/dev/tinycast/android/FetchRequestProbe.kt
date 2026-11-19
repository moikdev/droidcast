package dev.tinycast.android

import dev.tinycast.android.extensions.ExtensionHttpClient
import dev.tinycast.android.extensions.fetch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

/** Exercises the shipping JSON bridge and redirect loop with an in-memory connection. */
internal class FetchRequestProbe {
    class Connection(target: URI, private val location: String?) : HttpURLConnection(target.toURL()) {
        val sent = ByteArrayOutputStream()
        override fun getOutputStream() = sent
        override fun getInputStream() = "ok".byteInputStream()
        override fun getResponseCode() = if (location == null) 200 else 307
        override fun getResponseMessage() = if (location == null) "OK" else "Temporary Redirect"
        override fun getHeaderFields(): Map<String, List<String>> = emptyMap()
        override fun getHeaderField(name: String?) = if (name == "Location") location else null
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }

    val first = "https://source.invalid/start"
    val destination = "https://destination.invalid/end"
    val connections = mutableListOf<Connection>()

    fun execute(envelope: JSONObject, redirect: Boolean = false): JSONObject =
        ExtensionHttpClient(connect = { uri ->
            check(uri.toString() == first || uri.toString() == destination)
            Connection(uri, destination.takeIf { redirect && connections.isEmpty() }).also(connections::add)
        }).use { client -> runBlocking { client.fetch(envelope) } }

    fun post() = JSONObject().put("url", first).put("method", "POST")
}
