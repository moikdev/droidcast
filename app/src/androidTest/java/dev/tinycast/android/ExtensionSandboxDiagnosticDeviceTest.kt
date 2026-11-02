package dev.tinycast.android

import android.webkit.JavascriptInterface
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.tinycast.android.extensions.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/** Diagnostic receipts only; this headless probe does not certify a UI workflow. */
@RunWith(AndroidJUnit4::class)
class ExtensionSandboxDiagnosticDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var runtime: ExtensionRuntime? = null

    /** Forward every response unchanged while recording bounded filesystem calls. */
    class RecordingBridge(private val delegate: ExtensionRuntime.Bridge,
                          private val calls: ConcurrentLinkedQueue<JSONObject>) {
        @JavascriptInterface fun render(tree: String) = delegate.render(tree)
        @JavascriptInterface fun failed(message: String) = delegate.failed(message)
        @JavascriptInterface fun finished() = delegate.finished()
        @JavascriptInterface fun depth(value: String) = delegate.depth(value)
        @JavascriptInterface fun log(message: String) = delegate.log(message)
        @JavascriptInterface fun invoke(id: String, api: String, method: String, raw: String) = delegate.invoke(id, api, method, raw)
        @JavascriptInterface fun invokeSync(api: String, method: String, raw: String): String {
            val response = delegate.invokeSync(api, method, raw)
            if (api == "fs" && method == "realpath" && calls.size < 64) {
                val input = JSONArray(raw).optString(0)
                val call = JSONObject().put("api", api).put("method", method).put("input", input)
                    .put("response", JSONObject(response))
                try { call.put("java_canonical_input", File(input).canonicalPath) }
                catch (error: Exception) { call.put("canonicalization_error_type", error.javaClass.simpleName) }
                calls.add(call)
            }
            return response
        }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun frozenHttpBundleRecordsRealpathRequest() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit sandbox diagnostic only", arguments.getString("sandboxDiagnostic") == "true")
        assertEquals("Tinycast_Tablet_API_36", arguments.getString("dedicatedAvd"))
        val fixtureRoot = File(context.filesDir, "compatibility-batch-fixtures")
        val bytes = File(fixtureRoot, "fixture.json").readBytes()
        val fixtureHash = hash(bytes)
        assertEquals(arguments.getString("expectedBundleFixtureSha256"), fixtureHash)
        val fixture = JSONObject(String(bytes, Charsets.UTF_8))
        val entry = fixture.getJSONArray("extensions").objects().single()
        assertEquals("http-status-codes", entry.getString("directory"))
        assertEquals(listOf("index"), entry.getJSONArray("planned_commands").let { (0 until it.length()).map(it::getString) })
        val archive = File(fixtureRoot, "archives/${entry.getString("archive_name")}").readBytes()
        assertEquals(entry.getString("archive_sha256"), hash(archive))

        val id = "compat-batch-" + UUID.randomUUID().toString().replace("-", "")
        assertFalse(File(context.cacheDir, id).exists())
        assertFalse(File(context.filesDir, "extension-data/$id").exists())
        val leaseFile = File(fixtureRoot, "owners.json")
        val lease = JSONObject(leaseFile.readText())
        assertEquals(0, lease.getJSONArray("owned_extension_ids").length())
        lease.getJSONArray("owned_extension_ids").put(id)
        val temporary = File(fixtureRoot, "owners.json.tmp")
        temporary.writeText(lease.toString())
        assertTrue(temporary.renameTo(leaseFile))

        val root = ExtensionArchive.extract(archive, File(context.cacheDir, id))
        val manifestBytes = File(root, "package.json").readBytes()
        assertEquals(entry.getString("manifest_sha256"), hash(manifestBytes))
        val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
        val declared = entry.getJSONArray("commands").objects().single { it.getString("name") == "index" }
        assertEquals(declared.getString("file_sha256"), hash(File(root, "index.js").readBytes()))
        val listing = StoreExtension(id, "http-status-codes", "HTTP sandbox diagnostic", "Owned frozen fixture",
            entry.getString("store_handle"), entry.getString("store_handle"), "", "",
            entry.getString("store_reported_commit_sha"), 1)
        val extension = InstalledExtension(listing, root, manifest)
        val calls = ConcurrentLinkedQueue<JSONObject>()
        lateinit var catalog: ExtensionCatalog
        compose.activityRule.scenario.onActivity {
            catalog = ExtensionCatalog(context)
            catalog.savePreferences(extension, JSONObject().put("typeOfPerson", "none"))
            runtime = ExtensionRuntime(context, catalog, extension, extension.commands.single()).also { engine ->
                val bridge = engine.Bridge()
                engine.webView.removeJavascriptInterface("AndroidHost")
                engine.webView.addJavascriptInterface(RecordingBridge(bridge, calls), "AndroidHost")
                engine.start()
            }
        }
        compose.waitUntil(20000) { catalog.runtimeError != null || (catalog.runtimeReady && catalog.runtimeTree != null) }
        assertTrue("The actual frozen import must reach the recorded filesystem call", calls.isNotEmpty())
        val support = File(context.filesDir, "extension-data/$id")
        val row = JSONObject().put("directory", "http-status-codes").put("command", "index")
            .put("archive_sha256", entry.getString("archive_sha256"))
            .put("command_sha256", declared.getString("file_sha256"))
            .put("status", if (catalog.runtimeError != null) "observed-failed" else "tested-partial")
            .put("scope", "Headless diagnostic of the real bundle and unchanged forwarding bridge; no UI/action workflow certified")
            .put("runtime_error", catalog.runtimeError ?: JSONObject.NULL)
            .put("runtime_ready", catalog.runtimeReady).put("native_root", activeRoot(catalog.runtimeTree)?.optString("type") ?: JSONObject.NULL)
            .put("support_path", support.path).put("support_canonical", support.canonicalPath)
            .put("package_path", root.path).put("package_canonical", root.canonicalPath)
            .put("realpath_calls", JSONArray(calls.toList()))
        compose.activityRule.scenario.onActivity { runtime?.close(); runtime = null }
        val evidence = File(context.filesDir, "compatibility-evidence").apply { mkdirs() }
        File(evidence, "diagnostic-record.json").writeText(row.toString(2))
        File(evidence, "collection.json").writeText(JSONObject().put("completed", true)
            .put("fixture_sha256", fixtureHash).put("source_snapshot_sha256", fixture.getString("source_snapshot_sha256"))
            .put("workflows", JSONArray().put(row)).toString(2))
    }

    @After fun closeRuntime() {
        compose.activityRule.scenario.onActivity { runtime?.close(); runtime = null }
    }
}
