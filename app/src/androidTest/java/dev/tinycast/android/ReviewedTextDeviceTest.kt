package dev.tinycast.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.tinycast.android.extensions.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.UUID

/** The workflow tests deliberately use only pre-feature production APIs.
 * The same test APK can therefore fail at the missing review UI on the old app.
 */
@RunWith(AndroidJUnit4::class)
class ReviewedTextDeviceTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private val root get() = File(context.filesDir, "reviewed-text-fixture-lease")
    private val evidence get() = File(context.filesDir, "reviewed-text-evidence").apply { mkdirs() }
    private var method = ""
    private var prepared = false
    private var capturedClipboard = false
    private var originalClipboard: ClipData? = null
    private var originalPalette = emptyMap<String, Any?>()
    private var preserved = emptyList<File>()
    private var fingerprints = emptyList<Map<String, String>>()
    private val ids = mutableListOf<String>()
    private lateinit var formatterId: String
    private lateinit var probeId: String
    private lateinit var record: JSONObject
    private val sentinel = "Owned reviewed-text clipboard sentinel"
    private val input = " \n\t{\"message\":\"こんにちは 🌱 café é\",\"items\":[1,2]}  \n"
    private val output = "{\n  \"message\": \"こんにちは 🌱 café é\",\n  \"items\": [1, 2]\n}"
    private val prefix = "Owned prefix\n"
    private val suffix = "\nOwned suffix"
    private val callerQuery = "reviewed-text-caller-query"

    @get:Rule val rules: TestRule = RuleChain.outerRule(TestRule { base, description ->
        object : Statement() {
            override fun evaluate() {
                method = description.methodName.lowercase(java.util.Locale.ROOT)
                prepareFiles()
                try { base.evaluate() } finally { restoreFiles() }
            }
        }
    }).around(compose)

    private fun hash(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private fun fingerprint(file: File) = if (!file.exists()) emptyMap() else file.walkTopDown().associate {
        it.relativeTo(file).path to if (it.isDirectory) "directory" else hash(it.readBytes())
    }
    private fun lease(): JSONObject {
        val args = InstrumentationRegistry.getArguments()
        val owner = args.getString("expectedTextInputLeaseOwner")
        assertTrue(owner?.matches(Regex("[0-9a-f]{32}")) == true)
        val result = JSONObject(File(root, "owners.json").readText())
        assertEquals("reviewed-text-device-test-v1", result.getString("fixture_kind"))
        assertEquals(owner, result.getString("owner_nonce"))
        return result
    }
    private fun newId(): String {
        val id = "reviewed-text-" + UUID.randomUUID().toString().replace("-", "")
        assertFalse(File(context.filesDir, "extensions/$id").exists())
        assertFalse(File(context.cacheDir, id).exists())
        assertFalse(File(context.filesDir, "extension-data/$id").exists())
        assertFalse(File(context.applicationInfo.dataDir, "shared_prefs/extension-$id.xml").exists())
        val value = lease(); value.getJSONArray("owned_extension_ids").put(id)
        val temporary = File(root, "owners.json.tmp"); assertFalse(temporary.exists())
        temporary.writeText(value.toString()); assertTrue(temporary.renameTo(File(root, "owners.json")))
        ids += id
        return id
    }
    private fun listing(id: String, name: String, title: String) = StoreExtension(id, name, title, "Owned test copy", "Droidcast tests", "test", "", "", "", 1)
    private fun prepareFiles() {
        assumeTrue("Use the guarded reviewed-text runner", InstrumentationRegistry.getArguments().getString("reviewedTextFixture") == "true")
        assertEquals("Tinycast_Tablet_API_36", InstrumentationRegistry.getArguments().getString("dedicatedAvd"))
        lease()
        record = JSONObject().put("scope", "Owned external Android caller and frozen JSON formatter; probe commands are separately identified")
        originalPalette = context.getSharedPreferences("palette", Context.MODE_PRIVATE).all.toMap()
        preserved = File(context.filesDir, "extensions").listFiles().orEmpty().flatMap {
            listOf(it, File(context.filesDir, "extension-data/${it.name}"), File(context.applicationInfo.dataDir, "shared_prefs/extension-${it.name}.xml"))
        }
        fingerprints = preserved.map(::fingerprint)
        prepared = true
        val bytes = File(root, "json-format.zip").readBytes()
        assertEquals("fc420406a59251daee0c9af32e216026724631480b3a4e2f5d65d0c507b09a58", hash(bytes))
        formatterId = newId()
        val extracted = ExtensionArchive.extract(bytes, File(context.cacheDir, formatterId))
        assertEquals("bb859bf5193664df28734d7982ff540ba7da4fc92be0f3958afd7e717c84ac92", hash(File(extracted, "formatSelectionAndShow.js").readBytes()))
        val destination = File(context.filesDir, "extensions/$formatterId")
        assertTrue(extracted.renameTo(destination))
        File(destination, ".tinycast-store.json").writeText(listing(formatterId, "json-format", "Format JSON (owned copy)").json().toString())
        context.getSharedPreferences("extension-$formatterId", Context.MODE_PRIVATE).edit()
            .putString("preferences", """{"autopaste":false,"indent":"2","maxInitialLines":"10000"}""").commit().also { assertTrue(it) }
        probeId = newId()
        val probe = File(context.filesDir, "extensions/$probeId").apply { assertTrue(mkdir()) }
        File(probe, ".tinycast-store.json").writeText(listing(probeId, "reviewed-text-probe", "Owned text probe").json().toString())
        File(probe, "package.json").writeText("""{"name":"reviewed-text-probe","commands":[{"name":"inspect","title":"Inspect reviewed text","mode":"view"},{"name":"configure","title":"Configure reviewed text","mode":"view","preferences":[{"name":"label","title":"Owned label","type":"textfield","required":true}]}]}""")
        val script = """
            const React=require('react'),api=require('@raycast/api');
            globalThis.__reviewRuns=(globalThis.__reviewRuns||0)+1;
            module.exports.default=function(){
              const [message,setMessage]=React.useState('Waiting for input');
              React.useEffect(()=>{api.getSelectedText().then(value=>{globalThis.__reviewInput=value;setMessage('Approved input: '+JSON.stringify(value))},error=>{globalThis.__reviewInputError=error.message;setMessage('Input unavailable: '+error.message)})},[]);
              return React.createElement(api.Detail,{markdown:message,actions:React.createElement(api.ActionPanel,null,
                React.createElement(api.Action.CopyToClipboard,{title:'Copy inspected text',content:globalThis.__reviewInput||''}))});
            };
        """.trimIndent()
        listOf("inspect", "configure").forEach { File(probe, "$it.js").writeText(script) }
        record.put("archive_sha256", hash(bytes)).put("command_sha256", hash(File(destination, "formatSelectionAndShow.js").readBytes()))
            .put("probe_command_sha256", hash(script.toByteArray())).put("owned_extension_ids", JSONArray(ids))
    }
    @Before fun ready() {
        compose.waitUntil(15000) { !compose.activity.store.loading }
        dismissClipboardForEvidence()
        compose.activityRule.scenario.onActivity {
            val manager = it.getSystemService(ClipboardManager::class.java)
            originalClipboard = manager.primaryClip; capturedClipboard = true
            manager.setPrimaryClip(ClipData.newPlainText("Owned text fixture", sentinel))
            it.store.reset(); it.store.search(callerQuery)
        }
        dismissClipboardForEvidence()
        record.put("font_scale", context.resources.configuration.fontScale).put("width_dp", context.resources.configuration.screenWidthDp)
            .put("height_dp", context.resources.configuration.screenHeightDp)
            .put("installed_build_commit", context.packageManager.getApplicationInfo(context.packageName, android.content.pm.PackageManager.GET_META_DATA).metaData.getString("dev.droidcast.BUILD_COMMIT"))
    }
    private fun restoreFiles() {
        if (!prepared) return
        ids.forEach { id ->
            assertTrue(lease().getJSONArray("owned_extension_ids").let { a -> (0 until a.length()).any { a.getString(it) == id } })
            listOf(File(context.filesDir, "extensions/$id"), File(context.cacheDir, id), File(context.filesDir, "extension-data/$id")).forEach {
                it.deleteRecursively(); assertFalse(it.exists())
            }
            assertTrue(context.deleteSharedPreferences("extension-$id"))
        }
        restorePalettePreferences(context, originalPalette)
        assertEquals(fingerprints, preserved.map(::fingerprint))
        record.put("owned_data_removed", true).put("preexisting_installations_and_data_preserved", true)
        File(evidence, "text-$method-record.json").writeText(record.toString(2))
    }
    @After fun restoreClipboard() {
        if (!capturedClipboard) return
        // Reopen the target only after observations, so clipboard restoration is
        // performed while its UID is foreground. This is not workflow evidence.
        context.startActivity(Intent().setComponent(ComponentName(context.packageName, MainActivity::class.java.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), 5000)
        instrumentation.runOnMainSync {
            val manager = context.getSystemService(ClipboardManager::class.java)
            originalClipboard?.let(manager::setPrimaryClip) ?: manager.resetClipboardForTest()
        }
    }
    private fun caller(source: String = prefix + input + suffix, kind: String? = null, mime: String = "text/plain", readOnly: Boolean = true) {
        val value = Intent().setComponent(ComponentName(instrumentation.context.packageName, ReviewedTextCallerActivity::class.java.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("source", source).putExtra("selectionStart", if (source == prefix + input + suffix) prefix.length else 0)
            .putExtra("selectionEnd", if (source == prefix + input + suffix) prefix.length + input.length else source.length)
            .putExtra("mime", mime).putExtra("readOnly", readOnly).putExtra("payloadKind", kind)
        instrumentation.context.startActivity(value)
        assertTrue(device.wait(Until.hasObject(By.text("Owned Android caller")), 10000))
        assertEquals(source, device.findObject(By.res("android:id/edit")).text)
    }
    private fun send(share: Boolean = false) {
        val label = if (share) "Share owned text" else "Process selected text"
        device.findObject(By.text(label)).click()
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), 10000))
        compose.waitForIdle()
    }
    private fun capture(phase: String, external: Boolean = false) {
        if (!external) compose.waitForIdle() else device.waitForIdle()
        val name = "text-$method-$phase"
        device.dumpWindowHierarchy(File(evidence, "$name.xml"))
        assertTrue(device.takeScreenshot(File(evidence, "$name.png")))
        File(evidence, "$name-frame.json").writeText(JSONObject().put("phase", phase).put("external_caller", external)
            .put("font_scale", context.resources.configuration.fontScale).put("scope", record.getString("scope"))
            .put("synchronization", "Idle native/Compose state; hierarchy captured before PNG").toString(2))
    }
    private fun clipboardText(): String? {
        var text: String? = null
        instrumentation.runOnMainSync { text = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString() }
        return text
    }
    private fun assertReview() {
        capture("entry") // Also retained on a matched old-app failure.
        compose.onNodeWithText("Review text").assertIsDisplayed()
        compose.onNodeWithTag("text-input-content").assertTextEquals(input)
        compose.onNodeWithTag("text-input-run").assertIsNotEnabled()
        assertEquals(sentinel, clipboardText())
        assertNull(compose.activity.store.extensions.running)
        var webViews = 0
        fun visit(v: View) { if (v is WebView) webViews++; if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i)) }
        instrumentation.runOnMainSync { WindowInspector.getGlobalWindowViews().forEach(::visit) }
        assertEquals("Preview must not construct a JavaScript engine", 0, webViews)
        record.put("preview_no_execution_and_clipboard_unchanged", true)
    }
    private fun touch(tag: String) {
        val node = compose.onNodeWithTag(tag)
        var steps = 0
        while (!node.isDisplayed() && steps++ < 30) {
            compose.onNodeWithTag("shell-content-scroll").performTouchInput {
                swipe(Offset(4f, size.height * .82f), Offset(4f, size.height * .18f), 300)
            }
        }
        node.assertIsDisplayed().performTouchInput { click(center) }
    }
    private fun choose(id: String, name: String) {
        touch("text-input-choose")
        val tag = "text-command-$id/$name"
        val choice = compose.onNodeWithTag(tag)
        // The searchable native menu must be navigated by touch, including large text.
        var steps = 0
        while (!choice.isDisplayed() && steps++ < 35) {
            compose.onNodeWithTag("inline-menu-choices").performTouchInput { swipeUp() }
        }
        choice.assertIsDisplayed().performTouchInput { click(center) }
        assertNull(compose.activity.store.extensions.running)
        assertEquals(sentinel, clipboardText())
    }
    private fun jsonWorkflow(share: Boolean, readOnly: Boolean) {
        caller(readOnly = readOnly); capture("caller", true); send(share); assertReview()
        choose(formatterId, "formatSelectionAndShow"); capture("chosen")
        touch("text-input-run")
        val catalog = compose.activity.store.extensions
        compose.waitUntil(30000) { catalog.runtimeError != null || activeRoot(catalog.runtimeTree)?.props()?.optString("markdown")?.contains(output) == true }
        assertNull(catalog.runtimeError, catalog.runtimeError)
        val markdown = activeRoot(catalog.runtimeTree)!!.props().getString("markdown")
        assertEquals("```json\n$output\n```", markdown)
        assertEquals(sentinel, clipboardText())
        compose.onNodeWithTag("extension-primary").assertTextContains("Copy Full JSON", substring = true)
        capture("formatted")
        compose.onNodeWithTag("extension-primary").performTouchInput { click(center) }
        compose.waitUntil(10000) { clipboardText() == output }
        dismissClipboardForEvidence(); capture("copied")
        compose.onNodeWithTag("extension-back").performTouchInput { click(center) }
        assertTrue(device.wait(Until.hasObject(By.text("Owned Android caller")), 10000))
        assertEquals(prefix + input + suffix, device.findObject(By.res("android:id/edit")).text)
        assertEquals("Selection ${prefix.length} to ${prefix.length + input.length}", device.findObject(By.res("android:id/text2")).text)
        record.put("exact_input_sha256", hash(input.toByteArray())).put("exact_output_sha256", hash(output.toByteArray()))
            .put("clipboard_unchanged_until_copy", true).put("exact_unicode_output_copied", true)
            .put("source_text_and_selection_unchanged", true).put("returned_to_external_caller", true).put("passed", true)
        capture("return", true)
    }
    @Test fun selectedJsonRunsOnlyAfterReviewThenCopiesAndReturns() = jsonWorkflow(false, true)
    @Test fun sharedJsonRunsOnlyAfterReviewThenCopiesAndReturns() = jsonWorkflow(true, true)
    @Test fun writableSelectionStillUsesCopyWithoutReplacingSource() = jsonWorkflow(false, false)

    @Test fun malformedAndOversizedInputCannotRunOrChangeClipboard() {
        for ((kind, mime, expected) in listOf(Triple("missing", "text/plain", "No text was provided"), Triple("integer", "text/plain", "needs plain text"),
            Triple("oversize", "text/plain", "too long"), Triple(null, "text/html", "needs plain text"))) {
            caller(kind = kind, mime = mime); send()
            compose.onNodeWithTag("text-input-error").assertTextContains(expected, substring = true)
            compose.onNodeWithTag("text-input-run").assertDoesNotExist()
            assertNull(compose.activity.store.extensions.running); assertEquals(sentinel, clipboardText())
            capture("invalid-${kind ?: "mime"}")
        }
        record.put("invalid_payloads_rejected", 4).put("passed", true)
    }
    @Test fun cancelBeforeRunReturnsWithoutExecutingOrSaving() {
        caller(); send(); assertReview(); choose(formatterId, "formatSelectionAndShow")
        touch("text-input-cancel")
        assertTrue(device.wait(Until.hasObject(By.text("Owned Android caller")), 10000))
        assertEquals(prefix + input + suffix, device.findObject(By.res("android:id/edit")).text)
        capture("cancelled-return", true)
        record.put("cancelled_before_run", true).put("passed", true)
    }
    @Test fun ordinaryCommandDoesNotReceiveClipboardAsSelectedText() {
        compose.activityRule.scenario.onActivity {
            val extension = it.store.extensions.installed.single { e -> e.listing.id == probeId }
            it.store.openExtension(extension, extension.commands.single { c -> c.name == "inspect" })
        }
        val catalog = compose.activity.store.extensions
        compose.waitUntil(30000) { activeRoot(catalog.runtimeTree)?.props()?.optString("markdown")?.contains("Input unavailable:") == true || catalog.runtimeError != null }
        assertNull(catalog.runtimeError, catalog.runtimeError)
        assertTrue(activeRoot(catalog.runtimeTree)!!.props().getString("markdown").contains("No reviewed text is available"))
        assertFalse(catalog.runtimeTree.toString().contains(sentinel)); assertEquals(sentinel, clipboardText())
        capture("ordinary-unavailable"); record.put("ordinary_session_specific_unavailable_error", true).put("passed", true)
    }
}
