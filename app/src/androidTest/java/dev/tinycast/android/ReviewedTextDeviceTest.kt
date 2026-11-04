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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
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
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

/** The workflow tests deliberately use only pre-feature production APIs.
 * The same test APK can therefore fail at the missing review UI on the old app.
 */
@RunWith(AndroidJUnit4::class)
class ReviewedTextDeviceTest {
    private val compose = createEmptyComposeRule()
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
    private lateinit var translatorId: String
    private lateinit var weatherId: String
    private val translationCommand get() = InstrumentationRegistry.getArguments().getString("reviewedTranslationCommand") ?: "instant-translate-copy"
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
    private fun foregroundActivity(): MainActivity {
        fun current() = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<MainActivity>().single { it.window.decorView.hasWindowFocus() && !it.isFinishing }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return current()
        val result = AtomicReference<MainActivity>()
        instrumentation.runOnMainSync { result.set(current()) }
        return result.get()
    }
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
        if (method == "selectedtranslationrunsonlyafterreviewandreportsexactcopyorservicefailure") {
            val translation = File(root, "translate.zip").readBytes()
            assertEquals("9ecbb40257fbc1d0d32778efa95c1392fc6c46d03eb6099e3995a73b6e16eec2", hash(translation))
            translatorId = newId()
            val extractedTranslation = ExtensionArchive.extract(translation, File(context.cacheDir, translatorId))
            val commandHash = mapOf(
                "instant-translate-copy" to "7b1fb777536b39ea9837ac0f996eb023a053f32283415d65b7bbedc36c75f610",
                "translate" to "54c37e972a14f280ddbe953285a9cda50c3ccf0563e4ea5d54bcaef2a7d84d64",
                "quick-translate" to "597e079da9f40604482472c94e3810d6654415aaf04477371441c9c7fbfdf521",
                "quick-translate-view" to "456de7764bc2012c92883700260f5044ce169205b00bb9daf110e81a2c54cfec",
                "instant-translate-paste" to "0ff7fcfdd0c6d1adb8b229b038ffe9fc9b26b47fb4b30543a6b4cf48892688dd",
                "instant-translate-view" to "6c8da836ed165e4469aed98710b82bbdca28834cc0f30fbc34cac30e94c7e4ce").getValue(translationCommand)
            assertEquals(commandHash, hash(File(extractedTranslation, "$translationCommand.js").readBytes()))
            val target = File(context.filesDir, "extensions/$translatorId"); assertTrue(extractedTranslation.renameTo(target))
            val item = listing(translatorId, "translate", "Translate (owned frozen copy)")
            File(target, ".tinycast-store.json").writeText(item.json().toString())
            val extension = InstalledExtension(item, target, JSONObject(File(target, "package.json").readText()))
            val seeds = JSONObject("""{"langFrom":"en","lang1":"ja","lang2":"en","autoInput":false,"defaultAction":"copy","prioritizeCrossLanguage":false,"proxy":""}""")
            seeds.put("autoInput", translationCommand in setOf("translate", "quick-translate", "quick-translate-view"))
            extension.fields(extension.commands.single { it.name == translationCommand }).forEach { field ->
                val key = field.getString("name"); if (!seeds.has(key)) return@forEach
                val options = field.optJSONArray("data")
                assertTrue(field.optString("type") != "dropdown" || options != null &&
                    (0 until options.length()).any { options.getJSONObject(it).optString("value") == seeds.get(key).toString() })
            }
            assertTrue(context.getSharedPreferences("extension-$translatorId", Context.MODE_PRIVATE).edit().putString("preferences", seeds.toString()).commit())
            record.put("scope", "Unmodified frozen $translationCommand after explicit review/Run from a separate owned Android caller; collector success is separate from service success")
                .put("executed_command", translationCommand)
                .put("translation_archive_sha256", hash(translation)).put("translation_command_sha256", commandHash)
                .put("translation_preferences", seeds).put("owned_extension_ids", JSONArray(ids))
        }
        if (method == "desktopmenubarisunavailableinreviewedchooserandreturnstosource") {
            val bytes = File(root, "weather.zip").readBytes()
            assertEquals("20ecbb18633829b80aa5cf3668a9876d1cacee9510548f7c2ba01ea695ec2b0c", hash(bytes))
            weatherId = newId()
            val extracted = ExtensionArchive.extract(bytes, File(context.cacheDir, weatherId))
            assertEquals("750f3914568354a8b7358a267e830ddaac896fa1935567269919a9a4d1a9933f", hash(File(extracted, "menubar.js").readBytes()))
            val destination = File(context.filesDir, "extensions/$weatherId"); assertTrue(extracted.renameTo(destination))
            File(destination, ".tinycast-store.json").writeText(listing(weatherId, "weather", "Owned unavailable weather $weatherId").json().toString())
            record.put("scope", "Frozen Weather menu-bar reviewed-entry availability only; no forced launch, bytecode execution or forecast certification")
                .put("weather_archive_sha256", hash(bytes)).put("weather_command_sha256", hash(File(destination, "menubar.js").readBytes()))
                .put("observed_declaration", "weather/menubar").put("owned_extension_ids", JSONArray(ids))
        }
    }
    @Before fun ready() {
        context.startActivity(Intent(Intent.ACTION_MAIN).setComponent(ComponentName(context.packageName, MainActivity::class.java.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), 10000))
        compose.waitUntil(15000) { !foregroundActivity().store.loading }
        assertNotEquals(context.applicationInfo.uid, context.packageManager.getApplicationInfo(instrumentation.context.packageName, 0).uid)
        record.put("external_caller_uses_separate_package_and_uid", true)
        dismissClipboardForEvidence()
        instrumentation.runOnMainSync {
            val activity = foregroundActivity()
            val manager = activity.getSystemService(ClipboardManager::class.java)
            originalClipboard = manager.primaryClip; capturedClipboard = true
            manager.setPrimaryClip(ClipData.newPlainText("Owned text fixture", sentinel))
            activity.store.reset(); activity.store.search(callerQuery)
        }
        val translationFixture = method == "selectedtranslationrunsonlyafterreviewandreportsexactcopyorservicefailure"
        val actions = JSONArray()
        dismissClipboardForEvidence(requireAction = translationFixture, onNodeAction = { id, accepted ->
            actions.put(JSONObject().put("id", id).put("accepted", accepted))
        })
        record.put("owned_sentinel_dismiss_actions", actions)
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
        if (!record.optBoolean("passed")) {
            try {
                val activity = foregroundActivity()
                val catalog = activity.store.extensions
                record.put("failure_route", activity.store.dialog)
                    .put("failure_runtime_tree", catalog.runtimeTree ?: JSONObject.NULL)
                    .put("failure_runtime_error", catalog.runtimeError ?: JSONObject.NULL)
                    .put("failure_runtime_status", catalog.runtimeStatus)
                capture("failure")
            } catch (failure: Throwable) { record.put("failure_capture_error_type", failure.javaClass.simpleName) }
        }
        // Reopen the target only after observations, so clipboard restoration is
        // performed while its UID is foreground. This is not workflow evidence.
        context.startActivity(Intent().setComponent(ComponentName(context.packageName, MainActivity::class.java.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), 5000)
        instrumentation.runOnMainSync {
            val manager = context.getSystemService(ClipboardManager::class.java)
            originalClipboard?.let(manager::setPrimaryClip) ?: manager.resetClipboardForTest()
            // An external caller can create a second task/activity. Finish all
            // owned MainActivity instances before the outer file-restoration rule.
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            listOf(Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED)
                .flatMap { monitor.getActivitiesInStage(it) }.filterIsInstance<MainActivity>().distinct().forEach {
                it.store.extensions.close(); it.store.dialog = null; it.finish()
            }
        }
        // EmptyComposeRule deliberately has no ActivityScenario: Android can
        // create several no-affinity tasks for the different exported actions.
        val deadline = android.os.SystemClock.uptimeMillis() + 10000
        var remaining = true
        while (remaining && android.os.SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                remaining = listOf(Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED)
                    .flatMap { monitor.getActivitiesInStage(it) }.any { it is MainActivity }
            }
            if (remaining) android.os.SystemClock.sleep(50)
        }
        assertFalse("All owned target activities must close before restoring files", remaining)
    }
    private fun caller(source: String = prefix + input + suffix, kind: String? = null, mime: String = "text/plain", readOnly: Boolean = true) {
        val value = Intent().setComponent(ComponentName(instrumentation.context.packageName, ReviewedTextCallerActivity::class.java.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("source", source).putExtra("selectionStart", if (source == prefix + input + suffix) prefix.length else 0)
            .putExtra("selectionEnd", if (source == prefix + input + suffix) prefix.length + input.length else source.length)
            .putExtra("mime", mime).putExtra("readOnly", readOnly).putExtra("payloadKind", kind)
        instrumentation.context.startActivity(value)
        awaitCaller()
        assertEquals(source, device.findObject(By.res("android:id/edit")).text ?: "")
    }
    private fun awaitCaller() {
        assertTrue(device.wait(Until.hasObject(By.pkg(instrumentation.context.packageName).depth(0)), 10000))
        val scroll = UiScrollable(UiSelector().scrollable(true))
        if (scroll.exists()) scroll.scrollToBeginning(10)
    }
    private fun send(share: Boolean = false) {
        val label = if (share) "Share owned text" else "Process selected text"
        val scroll = UiScrollable(UiSelector().scrollable(true))
        if (scroll.exists()) assertTrue(scroll.scrollTextIntoView(label))
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
            .put("ime_visible", if (external) JSONObject.NULL else imeVisible())
            .put("synchronization", "Idle native/Compose state; hierarchy captured before PNG").toString(2))
    }
    private fun clipboardText(): String? {
        var text: String? = null
        instrumentation.runOnMainSync { text = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString() }
        return text
    }
    private fun assertReview(expected: String = input) {
        capture("entry") // Also retained on a matched old-app failure.
        compose.onNodeWithText("Review text").assertIsDisplayed()
        compose.onNodeWithTag("text-input-content").assertTextEquals(expected)
        compose.onNodeWithTag("text-input-run").assertIsNotEnabled()
        assertEquals(sentinel, clipboardText())
        assertNull(foregroundActivity().store.extensions.running)
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
            val scroll = if (foregroundActivity().store.dialog == "text-input") "shell-content-scroll" else "palette-content-scroll"
            compose.onNodeWithTag(scroll).performTouchInput {
                if (tag == "extension-back") swipe(Offset(4f, height * .18f), Offset(4f, height * .82f), 300)
                else swipe(Offset(4f, height * .82f), Offset(4f, height * .18f), 300)
            }
        }
        node.assertIsDisplayed().performTouchInput { click(center) }
    }
    private fun touchCompletionReturn() {
        val tag = "extension-completion-return"
        assertTrue("A completed no-view command must expose its touch Return action",
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1)
        val node = compose.onNodeWithTag(tag); val minimum = 48f * context.resources.displayMetrics.density
        val initial = node.fetchSemanticsNode().boundsInRoot
        assertTrue("Return needs a complete initial 48dp visible target", initial.height + 1 >= minimum && initial.width + 1 >= minimum)
        record.put("completion_return_initially_complete", true)
        // Both the palette and completed body may scroll at enlarged text.
        // Reveal the current target with real gestures before injecting its center.
        for (attempt in 0 until 60) {
            val target = node.fetchSemanticsNode()
            if (target.boundsInRoot.height + 1 >= minimum) break
            val body = compose.onNodeWithTag("extension-completion")
            if (body.fetchSemanticsNode().boundsInRoot.height >= minimum) {
                body.performTouchInput { swipe(Offset(4f, height * .8f), Offset(4f, height * .2f), 300) }
            } else compose.onNodeWithTag("palette-content-scroll").performTouchInput {
                swipe(Offset(4f, height * .8f), Offset(4f, height * .2f), 300)
            }
            compose.waitForIdle()
        }
        node.assertIsDisplayed().assertIsEnabled()
        val target = node.fetchSemanticsNode(); val bounds = target.boundsInRoot
        assertTrue("Return needs a complete 48dp visible target", bounds.height + 1 >= minimum && bounds.width + 1 >= minimum)
        val label = compose.onNodeWithText("Return", useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        instrumentation.runOnMainSync { assertTrue(requireNotNull(label.config[SemanticsActions.GetTextLayoutResult].action)(layouts)) }
        val layout = layouts.single(); val origin = compose.runOnUiThread { label.positionInRoot }; val visible = label.boundsInRoot
        assertTrue(label.size.height + 1 >= layout.multiParagraph.height)
        val lines = JSONArray()
        for (line in 0 until layout.lineCount) {
            assertFalse(layout.isLineEllipsized(line))
            val left = origin.x + layout.getLineLeft(line); val right = origin.x + layout.getLineRight(line)
            val top = origin.y + layout.getLineTop(line); val bottom = origin.y + layout.getLineBottom(line)
            assertTrue(left >= visible.left - 1 && right <= visible.right + 1 && top >= visible.top - 1 && bottom <= visible.bottom + 1)
            lines.put(JSONArray(listOf(left, top, right, bottom)))
        }
        record.put("completion_return_visible_target", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
            .put("completion_return_line_bounds", lines).put("completion_return_activation", "Actual pointer on exact completed Return tag")
        capture("completion-return-visible")
        val owner = compose.onNode(isRoot() and hasAnyDescendant(hasTestTag(tag)), useUnmergedTree = true)
        val root = owner.fetchSemanticsNode().boundsInRoot; assertTrue(root.contains(bounds.center))
        owner.performTouchInput { click(bounds.center - root.topLeft) }
    }
    private fun choose(id: String, name: String) {
        touch("text-input-choose")
        val extension = foregroundActivity().store.extensions.installed.single { it.listing.id == id }
        val title = extension.commands.single { it.name == name }.title
        compose.onNodeWithTag("inline-menu-search").performTextReplacement(title)
        compose.waitForIdle()
        val tag = "text-command-$id/$name"
        val choice = compose.onNodeWithTag(tag)
        // The searchable native menu must be navigated by touch, including large text.
        var steps = 0
        while (!choice.isDisplayed() && steps++ < 35) {
            compose.onNodeWithTag("inline-menu-choices").performTouchInput { swipeUp() }
        }
        choice.assertIsDisplayed().performTouchInput { click(center) }
        assertNull(foregroundActivity().store.extensions.running)
        assertEquals(sentinel, clipboardText())
    }
    private fun jsonWorkflow(share: Boolean, readOnly: Boolean) {
        caller(readOnly = readOnly); capture("caller", true); send(share); assertReview()
        val commandName = InstrumentationRegistry.getArguments().getString("reviewedJsonCommand") ?: "formatSelectionAndShow"
        assertTrue(commandName in setOf("formatSelectionAndShow", "formatSelection"))
        if (commandName == "formatSelection") { assertFalse(share); assertTrue(readOnly) }
        val extension = foregroundActivity().store.extensions.installed.single { it.listing.id == formatterId }
        val commandHash = hash(File(extension.directory, "$commandName.js").readBytes())
        assertEquals(if (commandName == "formatSelection") "b445fd841a7a81d29fa1db2b6f6107ed332edc3174cd3ce788267270f771ccdb"
            else "bb859bf5193664df28734d7982ff540ba7da4fc92be0f3958afd7e717c84ac92", commandHash)
        record.put("executed_command", commandName).put("command_sha256", commandHash)
        choose(formatterId, commandName); capture("chosen"); touch("text-input-run")
        val catalog = foregroundActivity().store.extensions
        if (commandName == "formatSelection") {
            compose.waitUntil(30000) { catalog.runtimeError != null || catalog.runtimeReady && clipboardText() == output }
            assertNull(catalog.runtimeError, catalog.runtimeError); assertEquals(output, clipboardText())
            assertEquals("no-view", extension.commands.single { it.name == commandName }.mode)
            record.put("scope", "Unmodified frozen formatSelection after explicit owned review/Run; exact Copy and unchanged external source return; desktop auto-close is not certified")
                .put("copy_trigger", "Explicit reviewed Run executes the no-view command's own Copy")
            capture("formatted-no-view-status")
        } else {
            compose.waitUntil(30000) { catalog.runtimeError != null || activeRoot(catalog.runtimeTree)?.props()?.optString("markdown")?.isNotEmpty() == true }
            assertNull(catalog.runtimeError, catalog.runtimeError)
            val markdown = activeRoot(catalog.runtimeTree)!!.props().getString("markdown")
            assertEquals("```json\n$output\n```", markdown)
            assertEquals(sentinel, clipboardText())
            compose.onNodeWithTag("extension-primary").assertTextContains("Copy Full JSON", substring = true)
            compose.waitForIdle()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("extension-markdown-loading").fetchSemanticsNodes().isEmpty() }
            record.put("native_format_contract_started", true)
            assertEquals("Frozen JSON must expose exact selectable native code without Markdown fences", 1,
                compose.onAllNodesWithTag("extension-markdown-code", useUnmergedTree = true).fetchSemanticsNodes().size)
            val code = compose.onNodeWithTag("extension-markdown-code", useUnmergedTree = true).fetchSemanticsNode()
            val rendered = code.config[SemanticsProperties.Text].single().text
            assertEquals(output + "\n", rendered)
            val layouts = mutableListOf<TextLayoutResult>()
            instrumentation.runOnMainSync { assertTrue(requireNotNull(code.config[SemanticsActions.GetTextLayoutResult].action)(layouts)) }
            assertEquals(FontFamily.Monospace, layouts.single().layoutInput.style.fontFamily)
            record.put("native_code_exact_output_sha256", hash(rendered.toByteArray())).put("native_code_monospace", true)
            capture("formatted")
            touch("extension-primary")
        }
        compose.waitUntil(10000) { clipboardText() == output }
        dismissClipboardForEvidence(); capture("copied")
        if (commandName == "formatSelection" && InstrumentationRegistry.getArguments().getString("expectCompletionReturn") == "true")
            touchCompletionReturn()
        else touch("extension-back")
        awaitCaller()
        assertEquals(prefix + input + suffix, device.findObject(By.res("android:id/edit")).text)
        assertEquals("Selection ${prefix.length} to ${prefix.length + input.length}", device.findObject(By.res("android:id/text2")).text)
        record.put("exact_input_sha256", hash(input.toByteArray())).put("exact_output_sha256", hash(output.toByteArray()))
            .put("clipboard_unchanged_until_copy", true).put("exact_unicode_output_copied", true)
            .put("source_text_and_selection_unchanged", true).put("returned_to_external_caller", true)
        capture("return", true)
        record.put("passed", true)
    }
    @Test fun selectedJsonRunsOnlyAfterReviewThenCopiesAndReturns() = jsonWorkflow(false, true)
    @Test fun sharedJsonRunsOnlyAfterReviewThenCopiesAndReturns() = jsonWorkflow(true, true)
    @Test fun writableSelectionStillUsesCopyWithoutReplacingSource() = jsonWorkflow(false, false)

    @Test fun desktopMenuBarIsUnavailableInReviewedChooserAndReturnsToSource() {
        caller(source = "hello"); send(); assertReview("hello")
        val catalog = foregroundActivity().store.extensions
        val extension = catalog.installed.single { it.listing.id == weatherId }
        val command = extension.commands.single { it.name == "menubar" }; assertEquals("menu-bar", command.mode)
        touch("text-input-choose")
        val query = extension.listing.title + " · " + command.title
        compose.onNodeWithTag("inline-menu-search").performTextReplacement(query)
        compose.waitForIdle()
        compose.onNodeWithTag("text-command-$weatherId/menubar").assertDoesNotExist()
        compose.onNodeWithTag("inline-menu-empty").assertIsDisplayed()
        assertNull(catalog.running); assertEquals(sentinel, clipboardText()); capture("menu-bar-unavailable")
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ESCAPE)
        compose.onNodeWithTag("inline-menu").assertDoesNotExist()
        assertEquals("text-input", foregroundActivity().store.dialog)
        touch("text-input-cancel"); awaitCaller()
        assertEquals("hello", device.findObject(By.res("android:id/edit")).text)
        assertEquals("Selection 0 to 5", device.findObject(By.res("android:id/text2")).text)
        assertEquals(sentinel, clipboardText())
        record.put("workflow_outcome", "blocked-menu-bar-reviewed-entry").put("extension_bytecode_executed", false)
            .put("native_entry_scope", "Actual reviewed-command chooser excludes the declared desktop-only mode; no forced execution")
            .put("source_text_and_selection_unchanged", true).put("returned_to_external_caller", true)
            .put("observation_collector_passed", true).put("passed", true)
        capture("menu-bar-source-return", true)
    }
    private fun contextEngine(): WebView? {
        var engine: WebView? = null
        instrumentation.runOnMainSync {
            fun visit(view: View) {
                if (view is WebView) { assertNull(engine); engine = view }
                if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            WindowInspector.getGlobalWindowViews().forEach(::visit)
        }
        return engine
    }
    private fun contextEvaluate(web: WebView, script: String): String {
        var result: String? = null; val done = CountDownLatch(1)
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { result = it; done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS)); return requireNotNull(result)
    }
    private fun contextObservation(web: WebView): JSONObject = JSONObject(JSONArray("[${contextEvaluate(web,
        "JSON.stringify(globalThis.__ownedContextTranslationRead())")}]").getString(0))
    private fun observeContextTranslation(commandName: String) {
        val catalog = foregroundActivity().store.extensions
        val view = commandName in setOf("translate", "quick-translate", "quick-translate-view")
        compose.waitUntil(30000) { catalog.runtimeError != null ||
            (if (view) activeRoot(catalog.runtimeTree)?.optString("type") == "List" else contextEngine() != null) }
        record.put("startup_gate", "Compose-driven wait for native List, JavaScript engine or runtime error")
        if (catalog.runtimeError != null) {
            val error = requireNotNull(catalog.runtimeError)
            record.put("observed_runtime_error", error).put("workflow_outcome",
                if (commandName == "quick-translate" && error.contains("launchCommand is not supported on Android")) "blocked-command-handoff" else "observed-runtime-failure")
            assertEquals(sentinel, clipboardText()); capture("translation-runtime-boundary")
        } else {
            if (commandName == "quick-translate") {
                assertEquals("quick-translate-view", catalog.running?.second?.name)
                val current = requireNotNull(catalog.running)
                instrumentation.runOnMainSync { assertNull("A handoff must not forward the raw reviewed-input grant", catalog.captureText(current.first, current.second).input) }
                record.put("handoff_target", "quick-translate-view").put("child_raw_input_grant_absent", true)
            }
            if (view) {
                assertEquals("List", activeRoot(catalog.runtimeTree)?.optString("type"))
                compose.waitUntil(10000) { activeRoot(catalog.runtimeTree)?.props()?.optString("searchText") == "hello" }
                record.put("reviewed_input_initially_consumed", true)
            }
            val web = requireNotNull(contextEngine()); val query = if (view) "good morning" else "hello"
            compose.waitUntil(10000) { contextEvaluate(web, "Boolean(globalThis.__tinycastHost && globalThis.__tinycast)") == "true" }
            assertEquals("true", contextEvaluate(web, """
                (()=>{
                  if(globalThis.__ownedContextTranslationRead)throw new Error('Observer already installed');
                  const host=globalThis.__tinycastHost,bridge=globalThis.__tinycast,invoke=host.invoke,settle=bridge.settle;
                  const requests=[],pending=new Map(),wanted=${JSONObject.quote(query)};let errors=0,overflow=0,decodeErrors=0,expected='',copied='',paste='',pasteRejected=false;
                  host.invoke=function(id,api,method,encoded){try{
                    if(api==='fetch'&&method==='request'){
                      if(requests.length>=32)overflow++;else{const request=JSON.parse(encoded)[0],url=new URL(request.url);
                        const row={sequence:requests.length+1,endpoint:url.origin==='https://translate.google.com'&&url.pathname==='/translate_a/single'?'google-translate':'other-redacted',
                          ownedInputQuery:url.searchParams.get('q')===wanted,ownedJapaneseTarget:url.searchParams.get('tl')==='ja',completed:false};requests.push(row);pending.set(String(id),row);}
                    }
                    if(api==='clipboard'&&['copy','paste'].includes(method)){const value=JSON.parse(encoded)[0],text=typeof value==='string'?value:value?.text||'';
                      if(text.length>4096)throw new Error('Owned output bound');if(method==='copy')copied=text;else{paste=text;pending.set(String(id),{kind:'paste'});}}
                  }catch(_){errors++;}return Reflect.apply(invoke,this,arguments)};
                  bridge.settle=function(id,ok,encoded){try{
                    const row=pending.get(String(id));if(row){pending.delete(String(id));
                      if(row.kind==='paste')pasteRejected=!ok&&String(encoded).includes('Clipboard paste into another app is unavailable');
                      else{row.completed=true;row.transportSucceeded=Boolean(ok);if(ok&&typeof encoded==='string'&&encoded.length<=12*1024*1024){
                        const response=JSON.parse(encoded);row.status=response.status;
                        if(row.ownedInputQuery&&row.ownedJapaneseTarget&&response.status>=200&&response.status<300)try{
                          if(typeof response.bodyBase64!=='string'||response.bodyBase64.length>1024*1024)throw new Error('Owned response bound');
                          const raw=JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(response.bodyBase64),c=>c.charCodeAt(0))));
                          if(!Array.isArray(raw[0]))throw new Error('Owned response shape');
                          const text=raw[0].filter(Array.isArray).map(cell=>typeof cell[0]==='string'?cell[0]:'').join('');
                          if(!text||text.length>4096)throw new Error('Owned translation bound');expected=text;
                        }catch(_){decodeErrors++;}
                      }}
                    }
                  }catch(_){errors++;}return Reflect.apply(settle,this,arguments)};
                  globalThis.__ownedContextTranslationRead=()=>({errors,overflow,decodeErrors,requests:requests.map(row=>({...row})),_expected:expected,_copied:copied,_paste:paste,pasteRejected});return true;
                })()
            """.trimIndent()))
            if (view) {
                touch("extension-search"); compose.onNodeWithTag("extension-search").performTextReplacement(query)
                val activity = foregroundActivity()
                instrumentation.runOnMainSync { ViewCompat.getWindowInsetsController(activity.window.decorView)?.hide(WindowInsetsCompat.Type.ime()) }
                compose.waitUntil(10000) { !imeVisible() }
                record.put("input_setup", "Native editor test replacement after actual pointer focus; no physical-typing claim").put("owned_edited_query", query)
            }
            val deadline = android.os.SystemClock.uptimeMillis() + 45000
            var observed = contextObservation(web)
            while (android.os.SystemClock.uptimeMillis() < deadline && catalog.runtimeError == null) {
                observed = contextObservation(web)
                val expected = observed.optString("_expected")
                val content = activeRoot(catalog.runtimeTree)?.takeUnless { it.props().optBoolean("isLoading") }?.nodes("List.Item")
                    ?.any { it.props().optString("title") == expected && expected.isNotBlank() } == true
                if (view && content || !view && catalog.runtimeReady) break
                android.os.SystemClock.sleep(100)
            }
            assertEquals(0, observed.getInt("errors")); assertEquals(0, observed.getInt("overflow"))
            val expected = observed.getString("_expected"); val copied = observed.getString("_copied"); val paste = observed.getString("_paste")
            listOf("_expected", "_copied", "_paste").forEach(observed::remove)
            record.put("network_observation", observed).put("network_observer_scope", "Only subsequent requests; known owned response translation decoded for native output/Copy equality; unrelated payloads, queries, headers and bodies are not retained")
                .put("observation_deadline_reached", android.os.SystemClock.uptimeMillis() >= deadline).put("runtime_status", catalog.runtimeStatus)
            if (expected.isNotBlank() && view && catalog.runtimeError == null) {
                val rows = activeRoot(catalog.runtimeTree)!!.nodes("List.Item")
                val index = rows.indexOfFirst { it.props().optString("title") == expected }; assertTrue(index >= 0)
                assertEquals(sentinel, clipboardText()); touch("extension-row-$index")
                assertEquals(sentinel, clipboardText()); record.put("selected_owned_response_row", index)
                capture("translation-native-result"); touch("extension-primary")
                compose.waitUntil(10000) { clipboardText() == expected }
                assertEquals(expected, contextObservation(web).getString("_copied"))
                dismissClipboardForEvidence(requireAction = true); capture("translation-exact-copy")
                record.put("copy_exact", true).put("workflow_outcome", "working-observed-path")
            } else if (commandName == "instant-translate-paste" && expected.isNotBlank() && paste == expected && observed.getBoolean("pasteRejected")) {
                assertEquals(sentinel, clipboardText()); capture("translation-paste-unavailable")
                record.put("copy_exact", false).put("workflow_outcome", "partial-paste-unsupported").put("owned_service_output_matched_paste_attempt", true)
            } else if (commandName == "instant-translate-view" && expected.isNotBlank() && catalog.runtimeReady && catalog.runtimeStatus == expected) {
                assertEquals(sentinel, clipboardText()); capture("translation-visible-feedback")
                record.put("copy_exact", false).put("workflow_outcome", "working-display-observed-path")
            } else {
                assertEquals(sentinel, clipboardText()); capture("translation-uncompleted-observation")
                record.put("copy_exact", false).put("workflow_outcome", "partial-no-witnessed-output")
            }
            if (expected.isNotBlank()) record.put("owned_provider_output_sha256", hash(expected.toByteArray())).put("owned_provider_output_characters", expected.length)
        }
        touch("extension-back"); awaitCaller()
        assertEquals("hello", device.findObject(By.res("android:id/edit")).text)
        assertEquals("Selection 0 to 5", device.findObject(By.res("android:id/text2")).text)
        record.put("source_text_and_selection_unchanged", true).put("returned_to_external_caller", true)
            .put("observation_collector_passed", true).put("passed", true)
        capture("translation-context-return", true)
    }
    @Test fun selectedTranslationRunsOnlyAfterReviewAndReportsExactCopyOrServiceFailure() {
        caller(source = "hello"); capture("translation-caller", true); send(); assertReview("hello")
        choose(translatorId, translationCommand); capture("translation-chosen"); touch("text-input-run")
        val catalog = foregroundActivity().store.extensions
        if (translationCommand != "instant-translate-copy") { observeContextTranslation(translationCommand); return }
        fun engine(): WebView? {
            var found: WebView? = null
            instrumentation.runOnMainSync {
                fun visit(view: View) {
                    if (view is WebView) { assertNull(found); found = view }
                    if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
                }
                WindowInspector.getGlobalWindowViews().forEach(::visit)
            }; return found
        }
        compose.waitUntil(15000) { engine() != null || catalog.runtimeError != null }; assertNull(catalog.runtimeError, catalog.runtimeError)
        val web = requireNotNull(engine())
        fun evaluate(script: String): String {
            var answer: String? = null; val done = CountDownLatch(1)
            instrumentation.runOnMainSync { web.evaluateJavascript(script) { answer = it; done.countDown() } }
            assertTrue(done.await(5, TimeUnit.SECONDS)); return requireNotNull(answer)
        }
        compose.waitUntil(10000) { evaluate("Boolean(globalThis.__tinycastHost && globalThis.__tinycast)") == "true" }
        assertEquals("true", evaluate("""
            (() => {
              const host=globalThis.__tinycastHost, bridge=globalThis.__tinycast;
              const invoke=host.invoke, settle=bridge.settle, rows=[], pending=new Map();
              let copied="", errors=0, overflow=0;
              host.invoke=function(id,api,method,encoded) {
                try {
                  if(api==="fetch" && method==="request") {
                    if(rows.length>=32) overflow++;
                    else {
                      const request=JSON.parse(encoded)[0], url=new URL(request.url);
                      const row={sequence:rows.length+1,endpoint:url.origin==="https://translate.google.com" && url.pathname==="/translate_a/single" ? "google-translate" : "other-redacted",
                        ownedInputQuery:url.searchParams.get("q")==="hello",completed:false};
                      rows.push(row);pending.set(String(id),row);
                    }
                  }
                  if(api==="clipboard" && method==="copy") {
                    const value=JSON.parse(encoded)[0];copied=typeof value==="string" ? value : value?.text || "";
                  }
                } catch(_) {errors++;}
                return Reflect.apply(invoke,this,arguments);
              };
              bridge.settle=function(id,ok,encoded) {
                try {
                  const row=pending.get(String(id));
                  if(row) {row.completed=true;row.transportSucceeded=Boolean(ok);pending.delete(String(id));
                    if(ok && typeof encoded==="string" && encoded.length<=12*1024*1024) row.status=JSON.parse(encoded).status;}
                } catch(_) {errors++;}
                return Reflect.apply(settle,this,arguments);
              };
              globalThis.__ownedSelectedTranslationRead=()=>({errors,overflow,requests:rows.map(row=>({...row})),copied});
              return true;
            })()
        """.trimIndent()))
        val deadline = android.os.SystemClock.uptimeMillis() + 105000
        while (android.os.SystemClock.uptimeMillis() < deadline && catalog.runtimeError == null &&
            !catalog.runtimeStatus.contains("Copied To Clipboard") && !catalog.runtimeStatus.contains("Translation failed")) android.os.SystemClock.sleep(100)
        val observed = JSONObject(JSONArray("[${evaluate("JSON.stringify(globalThis.__ownedSelectedTranslationRead())")}]").getString(0))
        val copied = observed.getString("copied"); observed.remove("copied")
        record.put("network_observation", observed).put("network_observer_scope", "Only requests after observer attachment; initial requests can be excluded; no query/header/body capture")
            .put("owned_selected_input", "hello").put("runtime_status", catalog.runtimeStatus)
            .put("observation_deadline_reached", android.os.SystemClock.uptimeMillis() >= deadline)
        assertEquals(0, observed.getInt("errors")); assertEquals(0, observed.getInt("overflow"))
        val requests = observed.getJSONArray("requests")
        val inputWitness = (0 until requests.length()).any { requests.getJSONObject(it).optBoolean("ownedInputQuery") }
        record.put("selected_input_in_observed_request", inputWitness)
        if (copied.isNotBlank() && catalog.runtimeStatus.contains("Copied To Clipboard")) {
            assertTrue("The selected input must reach an observed service request", inputWitness)
            assertEquals(copied, clipboardText()); assertNotEquals(sentinel, copied)
            dismissClipboardForEvidence(requireAction = true); capture("translation-copied")
            record.put("copy_exact", true).put("owned_translated_output_sha256", hash(copied.toByteArray()))
                .put("translated_output_characters", copied.length).put("workflow_outcome", "working-observed-path")
        } else {
            assertEquals(sentinel, clipboardText()); capture("translation-no-copy")
            record.put("copy_exact", false).put("workflow_outcome", "partial-no-copy")
        }
        touch("extension-back"); awaitCaller()
        assertEquals("hello", device.findObject(By.res("android:id/edit")).text)
        assertEquals("Selection 0 to 5", device.findObject(By.res("android:id/text2")).text)
        record.put("source_text_and_selection_unchanged", true).put("returned_to_external_caller", true)
            .put("observation_collector_passed", true).put("passed", true)
        capture("translation-return", true)
    }

    @Test fun malformedAndOversizedInputCannotRunOrChangeClipboard() {
        for ((kind, mime, expected) in listOf(Triple("missing", "text/plain", "No text was provided"), Triple("integer", "text/plain", "needs plain text"),
            Triple("oversize", "text/plain", "too long"), Triple("badflag", "text/plain", "needs plain text"), Triple("empty", "text/plain", "No text was provided"), Triple(null, "text/html", "needs plain text"))) {
            if (kind == "empty") caller(source = "", mime = mime) else caller(kind = kind, mime = mime)
            send()
            compose.onNodeWithTag("text-input-error").assertTextContains(expected, substring = true)
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("text-input-error").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue((0 until layouts.single().lineCount).none(layouts.single()::isLineEllipsized))
            compose.onNodeWithTag("text-input-run").assertDoesNotExist()
            assertNull(foregroundActivity().store.extensions.running); assertEquals(sentinel, clipboardText())
            capture("invalid-${kind ?: "mime"}")
        }
        record.put("invalid_payloads_rejected", 6).put("passed", true)
    }
    @Test fun cancelBeforeRunReturnsWithoutExecutingOrSaving() {
        caller(); send(); assertReview(); choose(formatterId, "formatSelectionAndShow")
        touch("text-input-cancel")
        awaitCaller()
        assertEquals(prefix + input + suffix, device.findObject(By.res("android:id/edit")).text)
        capture("cancelled-return", true)
        record.put("cancelled_before_run", true).put("passed", true)
    }
    @Test fun ordinaryCommandDoesNotReceiveClipboardAsSelectedText() {
        instrumentation.runOnMainSync {
            val activity = foregroundActivity()
            val extension = activity.store.extensions.installed.single { e -> e.listing.id == probeId }
            activity.store.openExtension(extension, extension.commands.single { c -> c.name == "inspect" })
        }
        val catalog = foregroundActivity().store.extensions
        compose.waitUntil(30000) { activeRoot(catalog.runtimeTree)?.props()?.optString("markdown")?.contains("Input unavailable:") == true || catalog.runtimeError != null }
        assertNull(catalog.runtimeError, catalog.runtimeError)
        assertTrue(activeRoot(catalog.runtimeTree)!!.props().getString("markdown").contains("No reviewed text is available"))
        assertFalse(catalog.runtimeTree.toString().contains(sentinel)); assertEquals(sentinel, clipboardText())
        capture("ordinary-unavailable"); record.put("ordinary_session_specific_unavailable_error", true).put("passed", true)
    }
    @Test fun explicitSaveAsClipOpensADraftWithoutRunningOrSavingIt() {
        caller(); send(); assertReview()
        val before = foregroundActivity().store.clips
        touch("text-input-save-clip")
        compose.onNodeWithTag("editor-body").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(input)))
        assertEquals(before, foregroundActivity().store.clips)
        assertNull(foregroundActivity().store.extensions.running)
        assertEquals(sentinel, clipboardText())
        capture("explicit-clip-draft")
        touch("cancel-editor")
        assertEquals(before, foregroundActivity().store.clips)
        assertNull(foregroundActivity().store.editing)
        record.put("explicit_clip_draft_preserved_exact_input_without_saving", true).put("passed", true)
    }
    @Test fun aNewExternalShareEndsThePriorSelectionCommand() {
        caller(); send(); assertReview(); choose(formatterId, "formatSelectionAndShow"); touch("text-input-run")
        val oldActivity = foregroundActivity(); val previous = oldActivity.store.extensions
        compose.waitUntil(30000) { previous.runtimeError != null || activeRoot(previous.runtimeTree)?.props()?.optString("markdown")?.isNotEmpty() == true }
        assertNull(previous.runtimeError, previous.runtimeError)
        caller(); send(share = true); assertReview()
        record.put("external_actions_created_different_activity_instances", oldActivity !== foregroundActivity())
        assertNull("A new external handoff must end the older Activity's command", previous.running)
        assertNull(previous.runtimeTree)
        capture("replacement-preview")
        touch("text-input-cancel"); awaitCaller()
        record.put("new_handoff_closed_previous_activity_command", true).put("passed", true)
    }

    private fun imeVisible() = ViewCompat.getRootWindowInsets(foregroundActivity().window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    private fun fullBounds(interaction: SemanticsNodeInteraction): Rect {
        val node = interaction.fetchSemanticsNode()
        return compose.runOnUiThread {
            val coordinates = node.layoutInfo.coordinates
            val origin = coordinates.localToRoot(Offset.Zero)
            Rect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height)
        }
    }
    private fun witnessPreviewLines() {
        val text = compose.onNodeWithTag("text-input-content")
        val preview = compose.onNodeWithTag("text-input-preview")
        val values = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(values) }
        val layout = values.single()
        assertTrue(fullBounds(text).height + 1 >= layout.size.height)
        assertTrue((0 until layout.lineCount).none(layout::isLineEllipsized))
        for (line in 0 until layout.lineCount) {
            assertTrue(layout.getLineLeft(line) >= -1 && layout.getLineRight(line) <= layout.size.width + 1)
        }
        val height = (0 until layout.lineCount).maxOf { layout.getLineBottom(it) - layout.getLineTop(it) }
        var reveal = 0
        while (preview.fetchSemanticsNode().boundsInRoot.height < height + 2 && reveal++ < 30) {
            compose.onNodeWithTag("shell-content-scroll").performTouchInput {
                swipe(Offset(4f, this.height * .8f), Offset(4f, this.height * .4f), 300)
            }
        }
        assertTrue("Preview needs room for a complete line", preview.fetchSemanticsNode().boundsInRoot.height >= height)
        val seen = BooleanArray(layout.lineCount)
        val receipts = JSONArray()
        capture("long-preview-top")
        var steps = 0
        while (seen.any { !it } && steps++ < 160) {
            val visible = preview.fetchSemanticsNode().boundsInRoot
            val origin = fullBounds(text)
            for (line in 0 until layout.lineCount) {
                if (origin.top + layout.getLineTop(line) >= visible.top - 1 && origin.top + layout.getLineBottom(line) <= visible.bottom + 1 &&
                    origin.left + layout.getLineLeft(line) >= visible.left - 1 && origin.left + layout.getLineRight(line) <= visible.right + 1) seen[line] = true
            }
            receipts.put(JSONObject().put("text_top", origin.top).put("viewport_top", visible.top).put("viewport_bottom", visible.bottom)
                .put("witnessed_lines", seen.count { it }))
            if (seen.any { !it }) preview.performTouchInput {
                down(center); moveTo(Offset(center.x, center.y - this.height * .25f), 300); advanceEventTime(150); up()
            }
            compose.waitForIdle()
        }
        assertTrue("Every natural non-ellipsized preview line must be fully visible through touch scrolling", seen.all { it })
        record.put("long_preview_line_count", layout.lineCount).put("long_preview_all_lines_witnessed", true).put("long_preview_pointer_receipts", receipts)
        capture("long-preview-bottom")
    }
    @Test fun longPreviewAndCommandSearchRemainReachableByTouch() {
        val text = (1..16).joinToString("\n") { "Owned line $it · café é 🌱 · exact whitespace  " } + "\nOwned final line ✓"
        caller(source = text); send(); assertReview(text)
        if (imeVisible()) { assertTrue(device.pressBack()); compose.waitUntil(10000) { !imeVisible() } }
        witnessPreviewLines()
        touch("text-input-choose")
        if (compose.onAllNodesWithTag("inline-menu-keyboard").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("inline-menu-keyboard").performTouchInput { click(center) }
        else compose.onNodeWithTag("inline-menu-search").performTouchInput { click(center) }
        compose.waitUntil(10000) { imeVisible() }
        compose.onNodeWithTag("inline-menu-search").performTextReplacement("Inspect reviewed text")
        compose.waitForIdle(); capture("command-search-ime")
        val tag = "text-command-$probeId/inspect"
        compose.onNodeWithTag(tag).assertIsDisplayed().performTouchInput { click(center) }
        record.put("chooser_search_used_real_ime", true)
        touch("text-input-run")
        val catalog = foregroundActivity().store.extensions
        compose.waitUntil(30000) { catalog.runtimeError != null || activeRoot(catalog.runtimeTree)?.props()?.optString("markdown")?.contains(JSONObject.quote(text)) == true }
        assertNull(catalog.runtimeError, catalog.runtimeError)
        assertEquals("Approved input: ${JSONObject.quote(text)}", activeRoot(catalog.runtimeTree)!!.props().getString("markdown"))
        assertEquals(sentinel, clipboardText())
        touch("extension-primary"); compose.waitUntil(10000) { clipboardText() == text }
        dismissClipboardForEvidence(); capture("long-probe-copy")
        touch("extension-back"); awaitCaller()
        assertEquals(text, device.findObject(By.res("android:id/edit")).text)
        capture("long-source-return", true)
        record.put("owned_probe_received_and_copied_exact_long_input", true).put("passed", true)
    }
}
