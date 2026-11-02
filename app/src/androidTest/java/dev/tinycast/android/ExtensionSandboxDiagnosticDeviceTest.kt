package dev.tinycast.android

import android.webkit.JavascriptInterface
import android.system.Os
import android.util.Base64
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    private fun cpuContract(bridge: ExtensionRuntime.Bridge): JSONObject {
        var sourceRows: Int? = null
        var readable = false
        try {
            File("/proc/stat").bufferedReader().use { reader ->
                val buffer = CharArray(8192)
                val count = reader.read(buffer)
                readable = count > 0
                if (count > 0) sourceRows = Regex("(?m)^cpu[0-9]+\\s").findAll(String(buffer, 0, count)).count()
            }
        } catch (_: Exception) { /* Only availability/count are recorded, never raw system data. */ }
        val response = JSONObject(bridge.invokeSync("os", "cpus", "[]"))
        val pathArgument = JSONObject(bridge.invokeSync("os", "cpus", "[\"/tmp/owned-control\"]"))
        val unsupported = JSONObject(bridge.invokeSync("os", "unsupported-owned-control", "[]"))
        val argumentsRejected = !pathArgument.optBoolean("ok") && !unsupported.optBoolean("ok")
        val cpus = response.optJSONArray("value")
        val shape = cpus != null && cpus.objects().size == cpus.length() && cpus.objects().all { cpu ->
            val times = cpu.optJSONObject("times")
            cpu.opt("model") is String && cpu.optString("model").isNotBlank() &&
                cpu.opt("speed") is Number && cpu.optDouble("speed", -1.0) >= 0 && times != null &&
                times.keys().asSequence().toSet() == setOf("user", "nice", "sys", "idle", "irq") &&
                listOf("user", "nice", "sys", "idle", "irq").all { key ->
                    times.opt(key) is Number && times.optDouble(key, -1.0) in 0.0..9_007_199_254_740_991.0
                }
        }
        val observed = if (readable && sourceRows != null && sourceRows!! > 0) cpus?.length() == sourceRows else cpus?.length() == 0
        return JSONObject().put("passed", response.optBoolean("ok") && shape && observed && argumentsRejected)
            .put("app_uid", android.os.Process.myUid()).put("proc_stat_readable", readable)
            .put("preview_cpu_rows", sourceRows ?: JSONObject.NULL)
            .put("runtime_available_processors", Runtime.getRuntime().availableProcessors())
            .put("arguments_and_unsupported_calls_rejected", argumentsRejected)
            .put("response", response).put("scope", "CPU array may be empty when per-core metadata is inaccessible; it is not a physical CPU count")
    }

    private fun moduleContract(): JSONObject {
        val latch = CountDownLatch(1)
        var observation = JSONObject().put("evaluation_error", "not-completed")
        val script = """
            JSON.stringify((function () {
              const shared = {STATUS_CODES: {}, request: function ownedRequest() {}};
              const other = {owned: true}, token = {};
              function makeRequire(http) {
                function req(name) {
                  if (name === "http" || name === "node:http") return http;
                  if (name === "other") return other;
                  if (name === "receiver") return this === token;
                  throw new Error("owned-module-missing");
                }
                req.resolve = function (name) { return "resolved:" + name; };
                req.cache = {};
                return req;
              }
              const body = '"use strict"; const h=require("http"); module.exports={strictThis:this===undefined, aliases:h===require("node:http"), identity:h===exports.http, request:h.request===exports.http.request, other:require("other")===exports.other, resolve:require.resolve("x")==="resolved:x", cache:require.cache===exports.cache, receiver:require.call(exports.token,"receiver"), filename:__filename==="owned-contract.js", dirname:__dirname==="owned", phrase:h.STATUS_CODES[404]==="Not Found", count:Object.keys(h.STATUS_CODES).length===63, originalError:(function(){try{require("missing");return false;}catch(e){return e.message==="owned-module-missing";}})()};';
              function run(http) {
                const req = makeRequire(http), module = {exports:{}};
                const compiled = __tinycastCompile(body, "owned-contract.js");
                compiled({http:http,other:other,token:token,cache:req.cache}, req, module, "owned-contract.js", "owned");
                return module.exports;
              }
              const flags = run(shared);
              shared.STATUS_CODES[404] = "Owned mutation";
              flags.mutationPreserved = !run(shared).phrase && shared.STATUS_CODES[404] === "Owned mutation";
              for (const key of Object.keys(shared.STATUS_CODES)) delete shared.STATUS_CODES[key];
              flags.deletionPreserved = !run(shared).count && Object.keys(shared.STATUS_CODES).length === 0;
              const existing = {STATUS_CODES:{404:"Existing implementation"},request:shared.request};
              run(existing);
              flags.existingPreserved = existing.STATUS_CODES[404] === "Existing implementation" && Object.keys(existing.STATUS_CODES).length === 1;
              return {flags:flags};
            })())
        """.trimIndent()
        compose.activityRule.scenario.onActivity {
            runtime!!.webView.evaluateJavascript(script) { raw ->
                try { observation = JSONObject(JSONArray("[$raw]").getString(0)) }
                catch (error: Exception) { observation = JSONObject().put("evaluation_error", error.javaClass.simpleName) }
                finally { latch.countDown() }
            }
        }
        if (!latch.await(10, TimeUnit.SECONDS)) return JSONObject().put("evaluation_error", "timeout")
        return observation
    }

    private fun filesystemChecks(bridge: ExtensionRuntime.Bridge, root: File, support: File,
                                 ownedCache: File, frozenCalls: List<JSONObject>): JSONArray {
        val checks = JSONArray()
        fun check(name: String, method: String, path: String, allowed: Boolean,
                  extra: List<Any> = emptyList(), valid: (JSONObject) -> Boolean = { true }) {
            val arguments = JSONArray().put(path).apply { extra.forEach(::put) }
            val response = JSONObject(bridge.invokeSync("fs", method, arguments.toString()))
            val ok = response.optBoolean("ok")
            val passed = if (allowed) ok && valid(response)
                else !ok && response.optString("error") == "Extensions can only access their own package and data folder"
            checks.put(JSONObject().put("name", name).put("method", method).put("input", path)
                .put("expected_allowed", allowed).put("observed_ok", ok).put("passed", passed)
                .put("error", response.opt("error") ?: JSONObject.NULL).put("code", response.opt("code") ?: JSONObject.NULL))
        }
        for ((name, directory) in listOf("data-raw" to support, "data-canonical" to support.canonicalFile,
                                        "package-raw" to root, "package-canonical" to root.canonicalFile)) {
            check("$name-realpath", "realpath", directory.path, true) { it.getString("value") == directory.canonicalPath }
            check("$name-exists", "exists", directory.path, true) { it.getBoolean("value") }
            check("$name-stat", "stat", directory.path, true) { it.getJSONObject("value").getBoolean("_isDirectory") }
            check("$name-readdir", "readdir", directory.path, true) { it.getJSONArray("value") != null }
        }
        check("data-root-mkdir", "mkdir", support.path, true)
        check("data-canonical-root-mkdir", "mkdir", support.canonicalPath, true)
        val child = File(support, "owned/marker.txt")
        val payload = "Droidcast owned marker ✓".toByteArray(Charsets.UTF_8)
        val encoded = Base64.encodeToString(payload, Base64.NO_WRAP)
        check("data-child-mkdir", "mkdir", child.parentFile!!.path, true)
        check("data-child-write", "writeFile", child.path, true, listOf(encoded))
        check("data-child-read", "readFile", child.path, true) { it.getString("value") == encoded }
        check("package-child-read", "readFile", File(root, "index.js").path, true) {
            hash(Base64.decode(it.getString("value"), Base64.DEFAULT)) == hash(File(root, "index.js").readBytes())
        }

        assertEquals(ownedCache.canonicalFile, root.parentFile!!.canonicalFile)
        val outside = File(ownedCache, "boundary-outside").apply { assertTrue(mkdir()) }
        val outsideMarker = File(outside, "marker.txt").apply { writeText("Owned outside-scope control") }
        check("absolute-outside-read", "readFile", outsideMarker.path, false)
        check("package-parent-traversal", "readFile", File(root, "../boundary-outside/marker.txt").path, false)
        check("data-parent-traversal", "exists", File(support, "../outside-sibling/marker.txt").path, false)
        check("data-prefix-sibling", "exists", support.path + "-sibling/marker.txt", false)
        check("global-tmp", "realpath", "/tmp", false)
        val packageWrite = File(root, "forbidden-write.txt")
        check("package-write", "writeFile", packageWrite.path, false, listOf(encoded))
        check("package-root-write", "mkdir", root.path, false)
        val link = File(support, "outside-link")
        Os.symlink(outside.path, link.path)
        try {
            check("symlink-outside-realpath", "realpath", link.path, false)
            check("symlink-outside-read", "readFile", File(link, "marker.txt").path, false)
            check("symlink-outside-write", "writeFile", File(link, "marker.txt").path, false, listOf(encoded))
        } finally { java.nio.file.Files.delete(link.toPath()) }
        checks.put(JSONObject().put("name", "outside-owned-marker-preserved").put("passed", outsideMarker.readText() == "Owned outside-scope control"))
        checks.put(JSONObject().put("name", "package-write-absent").put("passed", !packageWrite.exists()))
        checks.put(JSONObject().put("name", "frozen-temp-dir-call-owned-and-allowed").put("passed", frozenCalls.any {
            it.optString("java_canonical_input") == support.canonicalPath && it.getJSONObject("response").optBoolean("ok")
        }))
        return checks
    }

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
        assertFalse(File(context.applicationInfo.dataDir, "shared_prefs/extension-$id.xml").exists())
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
        lateinit var bridge: ExtensionRuntime.Bridge
        compose.activityRule.scenario.onActivity {
            catalog = ExtensionCatalog(context)
            catalog.savePreferences(extension, JSONObject().put("typeOfPerson", "none"))
            runtime = ExtensionRuntime(context, catalog, extension, extension.commands.single()).also { engine ->
                bridge = engine.Bridge()
                engine.webView.removeJavascriptInterface("AndroidHost")
                engine.webView.addJavascriptInterface(RecordingBridge(bridge, calls), "AndroidHost")
                engine.start()
            }
        }
        compose.waitUntil(20000) { catalog.runtimeError != null || (catalog.runtimeReady && catalog.runtimeTree != null) }
        assertTrue("The actual frozen import must reach the recorded filesystem call", calls.isNotEmpty())
        val support = File(context.filesDir, "extension-data/$id")
        val frozenCalls = calls.toList()
        val checks = filesystemChecks(bridge, root, support, File(context.cacheDir, id), frozenCalls)
        val cpu = cpuContract(bridge)
        val modules = moduleContract()
        val flags = modules.optJSONObject("flags")
        val modulePassed = flags != null && flags.length() == 16 && flags.keys().asSequence().all { flags.optBoolean(it) }
        val allPassed = checks.objects().all { it.getBoolean("passed") } && cpu.optBoolean("passed") && modulePassed
        val row = JSONObject().put("directory", "http-status-codes").put("command", "index")
            .put("archive_sha256", entry.getString("archive_sha256"))
            .put("command_sha256", declared.getString("file_sha256"))
            .put("status", if (catalog.runtimeError != null) "observed-failed" else "tested-partial")
            .put("scope", "Headless diagnostic of the real bundle and unchanged forwarding bridge; no UI/action workflow certified")
            .put("runtime_error", catalog.runtimeError ?: JSONObject.NULL)
            .put("runtime_ready", catalog.runtimeReady).put("native_root", activeRoot(catalog.runtimeTree)?.optString("type") ?: JSONObject.NULL)
            .put("support_path", support.path).put("support_canonical", support.canonicalPath)
            .put("package_path", root.path).put("package_canonical", root.canonicalPath)
            .put("realpath_calls", JSONArray(frozenCalls)).put("filesystem_checks", checks)
            .put("cpu_contract", cpu).put("module_contract", modules)
        compose.activityRule.scenario.onActivity { runtime?.close(); runtime = null }
        val evidence = File(context.filesDir, "compatibility-evidence").apply { mkdirs() }
        File(evidence, "diagnostic-record.json").writeText(row.toString(2))
        File(evidence, "collection.json").writeText(JSONObject().put("completed", allPassed)
            .put("fixture_sha256", fixtureHash).put("source_snapshot_sha256", fixture.getString("source_snapshot_sha256"))
            .put("workflows", JSONArray().put(row)).toString(2))
        assertTrue("Owned filesystem and Node metadata contracts must pass; filesystem failures=${checks.objects().filterNot { it.getBoolean("passed") }.map { it.getString("name") }}; cpu=${cpu.optBoolean("passed")}; modules=$modules", allPassed)
    }

    @After fun closeRuntime() {
        compose.activityRule.scenario.onActivity { runtime?.close(); runtime = null }
    }
}
