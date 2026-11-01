package dev.tinycast.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.pm.PackageManager
import dev.tinycast.android.updates.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

private class TextUpdateTransport(val responses: Map<String, String>) : UpdateTransport {
    val requests = mutableListOf<String>()
    var apkCalls = 0
    override suspend fun text(url: String, max: Long): String {
        requests += url
        val text = responses[url] ?: error("Unexpected request")
        require(text.toByteArray().size <= max)
        return text
    }
    override suspend fun apk(asset: UpdateAsset, file: File, progress: (Long) -> Unit) { apkCalls++; error("Discovery must not download an APK") }
}

@RunWith(AndroidJUnit4::class)
class UpdateSourceDeviceTest {
    private fun release(tag: String, code: Int, draft: Boolean = false, metadata: Boolean = true): Pair<JSONObject, Pair<String, String>> {
        val apk = "Droidcast-fixture-$code.apk"
        val base = "https://github.com/moikdev/droidcast/releases/download/$tag/"
        val manifest = UpdateManifest(1, UpdatePolicy.PACKAGE, code.toLong(), "fixture-$code", "b".repeat(40), apk, "a".repeat(64), 1234).json().toString()
        val assets = JSONArray().put(JSONObject().put("name", apk).put("browser_download_url", base+apk).put("size", 1234))
            .put(JSONObject().put("name", UpdatePolicy.CHECKSUMS).put("browser_download_url", base+UpdatePolicy.CHECKSUMS).put("size", 128))
        if (metadata) assets.put(JSONObject().put("name", UpdatePolicy.MANIFEST).put("browser_download_url", base+UpdatePolicy.MANIFEST).put("size", manifest.toByteArray().size))
        return JSONObject().put("tag_name", tag).put("name", "Fixture $code").put("body", "Controlled release notes")
            .put("published_at", "2026-11-01T00:00:00Z").put("draft", draft).put("prerelease", true).put("assets", assets) to
            ((base+UpdatePolicy.MANIFEST) to manifest)
    }
    @Test fun discoverySkipsDraftLegacyAndStableAndSelectsHighestVersion() = runBlocking {
        val fixtures = listOf(release("nightly-code-7", 7), release("nightly-code-8", 8), release("nightly-draft", 999, draft = true),
            release("v1.0", 100), release("nightly-legacy", 5, metadata = false))
        val feed = JSONArray().apply { fixtures.forEach { put(it.first) } }.toString()
        val transport = TextUpdateTransport(fixtures.associate { it.second } + (UpdatePolicy.FEED to feed))
        val candidate = requireNotNull(GithubUpdates(transport).newest())
        assertEquals(8L, candidate.manifest.versionCode)
        assertEquals(3, transport.requests.size); assertEquals(0, transport.apkCalls)
    }
    @Test fun discoveryRejectsForeignMetadataAddressBeforeFetchingIt() {
        val fixture = release("nightly-foreign", 7)
        fixture.first.getJSONArray("assets").getJSONObject(2).put("browser_download_url", "https://evil.example/update.json")
        val transport = TextUpdateTransport(mapOf(UpdatePolicy.FEED to JSONArray().put(fixture.first).toString()))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { GithubUpdates(transport).newest() } }
        assertEquals(listOf(UpdatePolicy.FEED), transport.requests); assertEquals(0, transport.apkCalls)
    }
    @Test fun metadataAndFeedSizeLimitsAreEnforced() {
        val fixture = release("nightly-size", 7)
        fixture.first.getJSONArray("assets").getJSONObject(2).put("size", UpdatePolicy.MAX_METADATA+1)
        var transport = TextUpdateTransport(mapOf(UpdatePolicy.FEED to JSONArray().put(fixture.first).toString()))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { GithubUpdates(transport).newest() } }
        assertEquals(1, transport.requests.size)
        val feed = JSONArray().apply { repeat(101) { put(fixture.first) } }
        transport = TextUpdateTransport(mapOf(UpdatePolicy.FEED to feed.toString()))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { GithubUpdates(transport).newest() } }
    }
    @Test fun realHttpsReleaseDownloadMatchesThePublishedLegacyApk() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "updates-live-legacy.apk")
        val url = "https://github.com/moikdev/droidcast/releases/download/nightly-2026-11-01-r9/Droidcast-nightly-2026-11-01-r9-af12a71.apk"
        try {
            UpdateHttp().apk(UpdateAsset("Droidcast-nightly-2026-11-01-r9-af12a71.apk", url, 10505237), file) {}
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals("0c6bdd79174ed7582c8adde6f8d5bd14ae627f5ad09f3d3348dd6710307433a8", hash)
            @Suppress("DEPRECATION") val archive = requireNotNull(context.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES))
            assertEquals(UpdatePolicy.PACKAGE, archive.packageName)
            assertEquals(5L, if (android.os.Build.VERSION.SDK_INT >= 28) archive.longVersionCode else @Suppress("DEPRECATION") archive.versionCode.toLong())
            File(context.filesDir, "updates-evidence").apply { mkdirs() }.resolve("live-legacy-download.json").writeText(
                JSONObject().put("url", url).put("bytes", file.length()).put("sha256", hash)
                    .put("versionCode", 5).put("installed_or_staged", false).toString(2))
        } finally { file.delete() }
    }
}
