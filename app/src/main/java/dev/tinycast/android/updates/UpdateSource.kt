package dev.tinycast.android.updates

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import javax.net.ssl.HttpsURLConnection

/** Only public GitHub endpoints; no account, token, arbitrary URL or cleartext fallback. */
internal interface UpdateTransport {
    suspend fun text(url: String, max: Long): String
    suspend fun apk(asset: UpdateAsset, file: File, progress: (Long) -> Unit)
}
internal class UpdateHttp : UpdateTransport {
    private suspend fun copy(url: String, output: OutputStream, max: Long, exact: Long?, progress: (Long) -> Unit): Long =
        withTimeout(180_000) { withContext(Dispatchers.IO) {
            var location = url
            var count = 0L
            for (redirect in 0..5) {
                currentCoroutineContext().ensureActive()
                val connection = UpdatePolicy.transportUrl(location).toURL().openConnection() as HttpsURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000; connection.readTimeout = 8_000
                connection.setRequestProperty("User-Agent", "Droidcast-updater")
                connection.setRequestProperty("Accept", if (location.startsWith("https://api.github.com/")) "application/vnd.github+json" else "application/octet-stream")
                try {
                    val status = connection.responseCode
                    if (status in setOf(301, 302, 303, 307, 308)) {
                        require(redirect < 5) { "Too many update redirects" }
                        location = connection.url.toURI().resolve(connection.getHeaderField("Location") ?: error("Missing download redirect")).toString()
                        UpdatePolicy.transportUrl(location)
                        continue
                    }
                    require(status == 200) { if (status == 403 || status == 429) "GitHub rate limit reached. Try again later." else "Update request failed (HTTP $status)" }
                    val length = connection.contentLengthLong
                    require(length < 0 || length <= max) { "Update response is too large" }
                    require(exact == null || length < 0 || length == exact) { "Download size does not match the release" }
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            count += read
                            require(count <= max && (exact == null || count <= exact)) { "Update response exceeded its size limit" }
                            output.write(buffer, 0, read); progress(count)
                        }
                    }
                    require(exact == null || count == exact) { "Download was incomplete" }
                    return@withContext count
                } finally { connection.disconnect() }
            }
            error("Update redirect limit reached")
        } }
    override suspend fun text(url: String, max: Long): String {
        val output = ByteArrayOutputStream()
        copy(url, output, max, null) {}
        return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString()
    }
    override suspend fun apk(asset: UpdateAsset, file: File, progress: (Long) -> Unit) {
        withContext(Dispatchers.IO) {
            file.outputStream().use { copy(asset.url, it, UpdatePolicy.MAX_APK, asset.bytes, progress) }
        }
    }
}

internal interface UpdateSource {
    suspend fun newest(): UpdateCandidate?
    suspend fun checksum(candidate: UpdateCandidate): String
    suspend fun download(candidate: UpdateCandidate, file: File, progress: (Long) -> Unit)
}

internal class GithubUpdates(private val http: UpdateTransport = UpdateHttp()) : UpdateSource {
    override suspend fun newest(): UpdateCandidate? {
        val array = JSONArray(http.text(UpdatePolicy.FEED, 2L * 1024 * 1024))
        require(array.length() <= 100) { "Release listing exceeded its limit" }
        val releases = (0 until array.length()).map { array.getJSONObject(it) }.filter {
            !it.optBoolean("draft") && it.optString("tag_name").startsWith("nightly-")
        }.map { json ->
            val assets = json.getJSONArray("assets")
            require(assets.length() <= 100) { "Too many release assets" }
            NightlyRelease(json.getString("tag_name"), json.optString("name").take(200), json.optString("body").take(8_000),
                json.getString("published_at"), (0 until assets.length()).map { assets.getJSONObject(it) }.map {
                    UpdateAsset(it.getString("name"), it.getString("browser_download_url"), it.getLong("size"))
                })
        }.filter { release -> release.assets.any { it.name == UpdatePolicy.MANIFEST } }
            .sortedByDescending { it.published }.take(10)
        val candidates = releases.map { release ->
            val metadata = release.assets.single { it.name == UpdatePolicy.MANIFEST }
            UpdatePolicy.validateAsset(release, metadata, UpdatePolicy.MAX_METADATA)
            val manifest = decodeManifest(JSONObject(http.text(metadata.url, UpdatePolicy.MAX_METADATA)))
            UpdatePolicy.candidate(release, manifest)
        }
        return candidates.maxByOrNull { it.manifest.versionCode }
    }
    override suspend fun checksum(candidate: UpdateCandidate): String =
        UpdatePolicy.checksum(http.text(candidate.checksums.url, UpdatePolicy.MAX_METADATA), candidate.apk.name)
    override suspend fun download(candidate: UpdateCandidate, file: File, progress: (Long) -> Unit) = http.apk(candidate.apk, file, progress)
}
internal fun decodeManifest(json: JSONObject) = UpdateManifest(json.getInt("format"), json.getString("packageName"),
    json.getLong("versionCode"), json.getString("versionName"), json.getString("commit"), json.getString("apk"),
    json.getString("sha256").lowercase(), json.getLong("bytes"))
internal fun UpdateManifest.json() = JSONObject().put("format", format).put("packageName", packageName).put("versionCode", versionCode)
    .put("versionName", versionName).put("commit", commit).put("apk", apk).put("sha256", sha256).put("bytes", bytes)
