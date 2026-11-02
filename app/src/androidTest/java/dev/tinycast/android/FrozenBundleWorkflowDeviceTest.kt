package dev.tinycast.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.tinycast.android.extensions.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A collector pass certifies collection/cleanup, not that every extension works. */
@RunWith(AndroidJUnit4::class)
class FrozenBundleWorkflowDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val catalog get() = compose.activity.store.extensions
    private val fixtureRoot get() = File(context.filesDir, "compatibility-batch-fixtures")
    private val evidence get() = File(context.filesDir, "compatibility-evidence").apply { mkdirs() }
    private lateinit var fixture: JSONObject
    private lateinit var fixtureHash: String
    private var originalPalette = emptyMap<String, Any?>()
    private var originalClipboard: ClipData? = null
    private var captured = false
    private var installedIds = emptySet<String>()
    private var preserved = emptyList<File>()
    private var preservedHashes = emptyList<Map<String, String>>()
    private val ownedIds = mutableListOf<String>()
    private val rows = JSONArray()
    private var currentObservation: JSONObject? = null
    private var currentCaptureName = ""
    private val callerQuery = "compatibility-audit-" + UUID.randomUUID().toString().replace("-", "")
    private val inputJson = "{\"droidcast\":271828,\"items\":[1,2]}"
    private val unicodeText = "Droidcast ✓"

    private class RuntimeFailure(message: String) : RuntimeException(message)
    private class ConfigurationNeeded(val fields: List<String>) : RuntimeException()

    private fun safeMessage(value: String) = value.replace(Regex("https?://\\S+"), "[URL omitted]")
        .replace(Regex("(?i)(verify|token|api[_-]?key|authorization|password)\\s*[:=]\\s*[^\\s,;]+"), "[credential omitted]").take(1500)

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fingerprint(root: File): Map<String, String> = if (!root.exists()) emptyMap() else root.walkTopDown().associate { file ->
        file.relativeTo(root).path to if (file.isDirectory) "directory" else {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(65536)
                while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    @Before fun prepare() {
        val arguments = InstrumentationRegistry.getArguments()
        assertEquals("Tinycast_Tablet_API_36", arguments.getString("dedicatedAvd"))
        val file = File(fixtureRoot, "fixture.json")
        assertTrue(file.isFile && file.length() in 1..4L * 1024 * 1024)
        val bytes = file.readBytes()
        fixtureHash = hash(bytes)
        assertEquals(arguments.getString("expectedBundleFixtureSha256"), fixtureHash)
        fixture = JSONObject(String(bytes, Charsets.UTF_8))
        assertEquals(1, fixture.getInt("schema_version"))
        compose.waitUntil(15000) { !compose.activity.store.loading }
        compose.waitUntil(10000) { kotlin.math.abs(context.resources.configuration.fontScale - 1f) < 0.01f }
        originalPalette = context.getSharedPreferences("palette", Context.MODE_PRIVATE).all.toMap()
        dismissClipboardForEvidence()
        compose.activityRule.scenario.onActivity { originalClipboard = it.getSystemService(ClipboardManager::class.java).primaryClip }
        captured = true
        installedIds = catalog.installed.map { it.listing.id }.toSet()
        preserved = catalog.installed.flatMap { extension -> listOf(extension.directory,
            File(context.filesDir, "extension-data/${extension.listing.id}"),
            File(context.applicationInfo.dataDir, "shared_prefs/extension-${extension.listing.id}.xml")) }
        preservedHashes = preserved.map(::fingerprint)
    }

    private fun lease(): String {
        val id = "compat-batch-" + UUID.randomUUID().toString().replace("-", "")
        assertFalse(File(context.cacheDir, id).exists())
        assertFalse(File(context.filesDir, "extension-data/$id").exists())
        assertFalse(File(context.applicationInfo.dataDir, "shared_prefs/extension-$id.xml").exists())
        val file = File(fixtureRoot, "owners.json")
        val value = JSONObject(file.readText())
        val ids = value.getJSONArray("owned_extension_ids")
        assertTrue(ids.length() < 100)
        ids.put(id)
        val temporary = File(fixtureRoot, "owners.json.tmp")
        temporary.writeText(value.toString())
        assertTrue("Register ownership before creating extension files", temporary.renameTo(file))
        ownedIds += id
        return id
    }

    private fun prepareExtension(entry: JSONObject, command: String): InstalledExtension {
        val archive = File(fixtureRoot, "archives/${entry.getString("archive_name")}")
        assertTrue(archive.isFile && archive.length() in 1..32L * 1024 * 1024)
        val bytes = archive.readBytes()
        assertEquals(entry.getString("archive_sha256"), hash(bytes))
        val id = lease()
        val root = ExtensionArchive.extract(bytes, File(context.cacheDir, id))
        val manifest = File(root, "package.json").readBytes()
        assertEquals(entry.getString("manifest_sha256"), hash(manifest))
        val definition = JSONObject(String(manifest, Charsets.UTF_8))
        assertEquals(entry.getString("store_name"), definition.getString("name"))
        val declared = entry.getJSONArray("commands").objects().single { it.getString("name") == command }
        assertEquals(declared.getString("file_sha256"), hash(File(root, "$command.js").readBytes()))
        val listing = StoreExtension(id, entry.getString("store_name"), definition.optString("title", entry.getString("store_name")),
            "Frozen real Store bundle in isolated storage", entry.getString("store_handle"), entry.getString("store_handle"), "",
            "https://www.raycast.com/${entry.getString("store_handle")}/${entry.getString("store_name")}",
            entry.getString("store_reported_commit_sha"), definition.getJSONArray("commands").length())
        return InstalledExtension(listing, root, definition)
    }

    private fun setClipboard(value: String) {
        compose.activityRule.scenario.onActivity { it.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Droidcast compatibility fixture", value)) }
        dismissClipboardForEvidence()
    }

    private fun clipboard(): String {
        var value = ""
        compose.activityRule.scenario.onActivity { value = it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(it)?.toString().orEmpty() }
        return value
    }

    private fun await(timeout: Long = 30000, predicate: () -> Boolean) {
        compose.waitUntil(timeout) { catalog.runtimeError != null || predicate() }
        catalog.runtimeError?.let { throw RuntimeFailure(it) }
    }

    private fun open(extension: InstalledExtension, name: String, arguments: Map<String, String> = emptyMap()) {
        val command = extension.commands.single { it.name == name }
        val seeds = mapOf<String, Any>("defaultAction" to "copy", "upperCaseLetters" to false,
            "uuidNamespace" to "6ba7b810-9dad-11d1-80b4-00c04fd430c8", "autopaste" to false,
            "indent" to "2", "action" to "clipboard", "langFrom" to "en", "lang1" to "ja", "lang2" to "en",
            "autoInput" to false, "defaultquery" to "Tokyo", "unitsystem" to "metric", "typeOfPerson" to "none")
        val values = JSONObject()
        extension.fields(command).forEach { field ->
            val key = field.getString("name")
            val seed = seeds[key] ?: return@forEach
            val options = field.optJSONArray("data").objects()
            if (field.optString("type") != "dropdown" || options.any { it.optString("value") == seed.toString() }) values.put(key, seed)
        }
        compose.activityRule.scenario.onActivity {
            it.store.extensions.close(); it.store.dialog = null; it.store.search(callerQuery)
            catalog.savePreferences(extension, values)
            it.store.openExtension(extension, command)
        }
        currentObservation?.put("configuration_method", "Nonsecret preferences seeded in isolated storage; native preference editing is not exercised")
            ?.put("seeded_preferences", values)
        currentObservation?.let { observation ->
            if (name != observation.getString("command")) {
                val supporting = observation.optJSONArray("supporting_commands") ?: JSONArray().also { observation.put("supporting_commands", it) }
                supporting.put(JSONObject().put("command", name).put("file_sha256", hash(File(extension.directory, "$name.js").readBytes())))
            }
        }
        if (compose.activity.store.dialog == "extension-setup") {
            val prefs = catalog.preferences(extension, command)
            val missing = extension.fields(command).filter { it.optBoolean("required") && it.optString("type") != "checkbox" && prefs.optString(it.getString("name")).isBlank() }
            if (missing.isNotEmpty()) throw ConfigurationNeeded(missing.map { it.getString("name") })
            command.definition.optJSONArray("arguments").objects().forEach { field ->
                val key = field.getString("name")
                val value = arguments[key]
                if (value != null) compose.onNodeWithTag("argument-$key").performTextReplacement(value)
                else if (field.optBoolean("required")) throw ConfigurationNeeded(listOf(key))
            }
            currentObservation?.let { observation ->
                val steps = observation.optJSONArray("native_argument_steps") ?: JSONArray().also { observation.put("native_argument_steps", it) }
                steps.put(JSONObject().put("command", name).put("values", JSONObject(arguments)))
            }
            compose.onNodeWithTag("run-extension-setup").performClick()
        }
        await { catalog.runtimeReady }
        currentObservation?.getJSONObject("phases")?.put("startup", "Host runtime reported ready")
            ?.put("rendering", if (command.mode == "no-view") "not-applicable" else "awaiting native capture")
    }

    private fun capture(name: String) {
        dismissClipboardForEvidence()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        repeat(3) {
            compose.waitForIdle()
            val epoch = catalog.launchEpoch
            val tree = catalog.runtimeTree?.toString()
            val error = catalog.runtimeError
            val status = catalog.runtimeStatus
            val root = activeRoot(catalog.runtimeTree)
            if (catalog.running == null) compose.onNodeWithTag("extension-palette").assertDoesNotExist()
            else if (error != null) compose.onNodeWithTag("extension-error").assertTextContains(error.lineSequence().first(), substring = true)
            else when (root?.optString("type")) {
                "List" -> compose.onNodeWithTag("extension-list").assertIsDisplayed()
                "Grid" -> compose.onNodeWithTag("extension-grid-screen").assertIsDisplayed()
                "Form" -> {
                    val field = root.nodes("Form.TextArea").firstOrNull() ?: root.nodes("Form.TextField").firstOrNull()
                    if (field != null) compose.onNodeWithTag("extension-field-${field.props().getString("id")}").assertExists()
                    compose.onNodeWithTag("extension-status").assertDoesNotExist()
                }
                "Detail" -> compose.onNodeWithTag("extension-status").assertDoesNotExist()
                null -> compose.onNodeWithTag("extension-status").assertTextEquals(status)
            }
            // A runtime callback is not a native draw receipt. Let two actual
            // display frames follow the committed semantics before capturing.
            val drawn = CountDownLatch(1)
            compose.activityRule.scenario.onActivity { activity ->
                activity.window.decorView.postOnAnimation {
                    activity.window.decorView.postOnAnimation { drawn.countDown() }
                }
            }
            assertTrue("Native frames must advance before capture", drawn.await(5, TimeUnit.SECONDS))
            compose.waitForIdle()
            if (epoch != catalog.launchEpoch || tree != catalog.runtimeTree?.toString() || error != catalog.runtimeError || status != catalog.runtimeStatus) return@repeat
            device.dumpWindowHierarchy(File(evidence, "$name.xml"))
            assertTrue(device.takeScreenshot(File(evidence, "$name.png")))
            compose.waitForIdle()
            if (epoch == catalog.launchEpoch && tree == catalog.runtimeTree?.toString() && error == catalog.runtimeError && status == catalog.runtimeStatus) {
                File(evidence, "$name-frame.json").writeText(JSONObject().put("launch_epoch", epoch)
                    .put("native_root", root?.optString("type") ?: JSONObject.NULL)
                    .put("runtime_tree_sha256", tree?.let { hash(it.toByteArray(Charsets.UTF_8)) } ?: JSONObject.NULL)
                    .put("runtime_status", safeMessage(status)).put("runtime_error", error?.let(::safeMessage) ?: JSONObject.NULL)
                    .put("synchronization", "Committed native semantics, two display frames, hierarchy before PNG, stable runtime state throughout capture").toString(2))
                if (tree != null && tree.length <= 2 * 1024 * 1024) File(evidence, "$name-tree.json").writeText(tree)
                return
            }
        }
        fail("Command state did not remain stable during native capture")
    }

    private fun supportingCapture(suffix: String) {
        val name = "$currentCaptureName-$suffix"
        capture(name)
        currentObservation?.let { row ->
            val captures = row.optJSONArray("supporting_captures") ?: JSONArray().also { row.put("supporting_captures", it) }
            captures.put("$name.png")
        }
    }

    private fun hideImeForCapture() {
        compose.activityRule.scenario.onActivity {
            WindowInspector.getGlobalWindowViews().forEach { view ->
                androidx.core.view.ViewCompat.getWindowInsetsController(view)?.hide(androidx.core.view.WindowInsetsCompat.Type.ime())
            }
        }
        compose.waitUntil(10000) {
            WindowInspector.getGlobalWindowViews().none { view ->
                androidx.core.view.ViewCompat.getRootWindowInsets(view)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            }
        }
        currentObservation?.put("capture_ime_method", "Native inset controller hides IME for the evidence frame; this is not a touch IME-transition certification")
    }

    private fun checkedCopy(extension: InstalledExtension, command: String, expected: String, args: Map<String, String> = emptyMap()) {
        open(extension, command, args)
        await { clipboard() == expected }
        assertEquals(expected, clipboard())
    }

    private fun checkFormattedJson() {
        val value = clipboard()
        assertTrue(value, value.contains("\n  \"droidcast\""))
        assertEquals(271828, JSONObject(value).getInt("droidcast"))
        assertEquals(listOf(1, 2), JSONObject(value).getJSONArray("items").let { listOf(it.getInt(0), it.getInt(1)) })
    }

    private fun workflow(entry: JSONObject, extension: InstalledExtension, command: String, row: JSONObject): Boolean {
        val directory = entry.getString("directory")
        when ("$directory/$command") {
            "base64/encode" -> {
                setClipboard(unicodeText)
                checkedCopy(extension, command, Base64.encodeToString(unicodeText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                row.put("workflow", "Encode owned Unicode clipboard text and verify the exact Base64 output")
            }
            "base64/decode" -> {
                setClipboard(Base64.encodeToString(unicodeText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                checkedCopy(extension, command, unicodeText)
                row.put("workflow", "Decode owned Base64 clipboard input and verify exact Unicode output")
            }
            "base64/index" -> {
                val encoded = Base64.encodeToString(unicodeText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                setClipboard(unicodeText)
                open(extension, command)
                await { catalog.runtimeTree?.toString()?.contains(encoded) == true }
                compose.onNodeWithTag("extension-primary").performClick()
                await { clipboard() == encoded }
                row.put("clipboard_bootstrap_copy_verified", true).put("action_output_verified", true)
                val typed = "Droidcast touch"
                val encodedTyped = Base64.encodeToString(typed.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                compose.onNodeWithTag("extension-search").performTextReplacement(typed)
                await { catalog.runtimeTree?.toString()?.contains(encodedTyped) == true }
                assertFalse(activeRoot(catalog.runtimeTree)!!.props().getBoolean("filtering"))
                compose.onNodeWithTag("extension-row-0").assertIsDisplayed()
                compose.onNodeWithTag("extension-primary").performClick()
                await { clipboard() == encodedTyped }
                row.put("workflow", "Copy the encoded owned clipboard value, type new input through native search, and copy the exact new Base64 output")
            }
            "oklch-color-converter/convert-color" -> {
                open(extension, command)
                compose.onNodeWithTag("extension-search").performTextReplacement("#ff0000")
                await { activeRoot(catalog.runtimeTree)?.nodes("List.Item")?.any { it.props().optString("title").startsWith("oklch(") } == true }
                val root = activeRoot(catalog.runtimeTree)!!
                assertFalse(root.props().getBoolean("filtering"))
                val items = root.nodes("List.Item")
                val index = items.indexOfFirst { it.props().optString("title").startsWith("oklch(") }
                val expected = items[index].props().getString("title")
                val values = Regex("[0-9.]+").findAll(expected).map { it.value.toDouble() }.toList()
                assertEquals(3, values.size)
                assertEquals(62.80, values[0], 0.05)
                assertEquals(0.2577, values[1], 0.0005)
                assertEquals(29.23, values[2], 0.1)
                compose.onNodeWithTag("extension-list").performScrollToNode(hasTestTag("extension-row-$index"))
                compose.onNodeWithTag("extension-row-$index").performClick()
                compose.onNodeWithTag("extension-primary").performClick()
                await { clipboard() == expected }
                row.put("workflow", "Type red, choose its OKLCH conversion, verify the numeric conversion and copy the exact displayed value")
            }
            "palette-colors/index" -> {
                fun titles() = activeRoot(catalog.runtimeTree)?.nodes("Grid.Item")?.map { it.props().optString("title") }.orEmpty()
                open(extension, command)
                await { titles().isNotEmpty() }
                val initial = titles()
                compose.onNodeWithTag("extension-primary").performClick()
                await { Regex("#[0-9a-fA-F]{6}").matches(clipboard()) }
                assertTrue(catalog.runtimeTree.toString().contains(clipboard(), ignoreCase = true))
                row.put("action_output_verified", true)
                compose.onNodeWithTag("extension-grid-filter").performClick()
                compose.onNodeWithTag("extension-grid-filter-1").performClick()
                await { titles().isNotEmpty() && titles() != initial }
                val contrast = titles()
                compose.onNodeWithTag("extension-grid-filter").assertTextContains("Contrast")
                setClipboard("Droidcast contrast-copy sentinel")
                compose.onNodeWithTag("extension-primary").performClick()
                await { Regex("#[0-9a-fA-F]{6}").matches(clipboard()) }
                assertTrue(catalog.runtimeTree.toString().contains(clipboard(), ignoreCase = true))
                open(extension, command)
                await { titles() == contrast }
                compose.onNodeWithTag("extension-grid-filter").assertTextContains("Contrast")
                row.put("persistence_verified", true).put("workflow", "Copy a displayed color, change to Contrast by touch, copy again, and reopen with the same scheme and items")
            }
            "uuid-generator/generateV5" -> {
                checkedCopy(extension, command, "27f7df43-8cb5-5412-9ea0-8d9cf2c6f447", mapOf("name" to "droidcast.example", "numberOfUUIDsToGenerate" to "1"))
                row.put("workflow", "Generate DNS-namespace UUIDv5 for the owned name and verify the deterministic clipboard value")
            }
            "uuid-generator/generate", "uuid-generator/viewHistory" -> {
                open(extension, "generate", mapOf("numberOfUUIDsToGenerate" to "2"))
                val pattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}", RegexOption.IGNORE_CASE)
                await { clipboard().trim().split(Regex("\\s+")).let { it.size == 2 && it.all(pattern::matches) } }
                val generated = clipboard().trim().split(Regex("\\s+"))
                assertEquals(2, generated.toSet().size)
                if (command == "viewHistory") {
                    setClipboard("Droidcast history-copy sentinel")
                    open(extension, command)
                    await { catalog.runtimeTree?.toString()?.contains(generated.first()) == true }
                    compose.onNodeWithTag("extension-list").assertExists()
                    compose.onNodeWithTag("extension-primary").performClick()
                    await { clipboard() in generated }
                    row.put("persistence_verified", true)
                }
                row.put("workflow", if (command == "generate") "Enter a count of two through native setup and verify two distinct UUIDv4 clipboard values" else "Generate owned values, reopen the command session as history, and copy a persisted UUID through the native List action")
            }
            "lorem-ipsum/words" -> {
                open(extension, command, mapOf("numberOfWords" to "7"))
                await { clipboard().trim().split(Regex("\\s+")).let { it.size == 7 && it.all { word -> word.matches(Regex("[A-Za-z]+")) } } }
                row.put("workflow", "Enter seven words through native argument setup and verify the clipboard word count")
            }
            "json-format/formatClipboard" -> {
                setClipboard(inputJson)
                open(extension, command)
                await { clipboard() != inputJson && clipboard().contains("\n  \"droidcast\"") }
                checkFormattedJson()
                row.put("workflow", "Format owned clipboard JSON with two-space indentation and copy without changing values")
            }
            "json-format/index" -> {
                open(extension, command)
                await { activeRoot(catalog.runtimeTree)?.optString("type") == "Form" }
                compose.onNodeWithTag("extension-field-input").performTextReplacement(inputJson)
                await { activeRoot(catalog.runtimeTree)?.nodes("Form.TextArea")?.any { it.props().optString("id") == "input" && it.props().optString("value") == inputJson } == true }
                hideImeForCapture()
                supportingCapture("form-input")
                val actions = activeRoot(catalog.runtimeTree)!!.actions()
                val index = actions.indexOfFirst { it.props().optString("title") == "View Result" }
                assertTrue(index >= 0)
                compose.onNodeWithTag("extension-actions").performClick()
                compose.onNodeWithTag("extension-action-$index").performClick()
                await { activeRoot(catalog.runtimeTree)?.optString("type") == "Detail" && catalog.runtimeTree.toString().contains("271828") }
                assertTrue(catalog.navigationDepth > 1)
                row.put("destination_verified", "Native Detail with the owned formatted JSON")
                setClipboard("Droidcast Detail-copy sentinel")
                compose.onNodeWithTag("extension-primary").performClick()
                await { clipboard().contains("\n  \"droidcast\"") }
                checkFormattedJson()
                row.put("workflow", "Enter owned JSON in the native Form, choose View Result, verify the Detail destination, and copy its formatted content")
            }
            "http-status-codes/index" -> {
                row.put("workflow", "Load the native status-code List, filter to 404 and choose Copy Code through Actions; browser/image actions are not exercised")
                open(extension, command)
                await { activeRoot(catalog.runtimeTree)?.nodes("List.Item")?.isNotEmpty() == true }
                compose.onNodeWithTag("extension-search").performTextReplacement("404")
                compose.onNodeWithTag("extension-row-0").assertTextContains("404").performClick()
                compose.onNodeWithTag("extension-actions").performClick()
                compose.onNodeWithTag("extension-action-1").assertTextContains("Copy Code").performClick()
                await { clipboard() == "404" }
                row.put("workflow", "Filter the native status-code List to 404 and choose Copy Code through Actions; browser/image actions are not exercised")
            }
            "google-translate/translate-form" -> {
                row.put("workflow", "Enter owned English hello in the Form, await Japanese service output and copy the exact visible response; translation quality is not certified")
                open(extension, command)
                await { activeRoot(catalog.runtimeTree)?.optString("type") == "Form" }
                compose.onNodeWithTag("extension-field-text").performTextReplacement("hello")
                await { activeRoot(catalog.runtimeTree)?.nodes("Form.TextArea")?.any { it.props().optString("id") == "text" && it.props().optString("value") == "hello" } == true }
                row.put("interaction_verified", "Owned hello input reached the extension's Form state")
                hideImeForCapture()
                supportingCapture("form-input")
                var translated = ""
                await(60000) {
                    translated = activeRoot(catalog.runtimeTree)?.nodes("Form.TextArea")?.firstOrNull { it.props().optString("id") == "result" }?.props()?.optString("value").orEmpty()
                    translated.contains(Regex("[\\u3040-\\u30ff\\u3400-\\u9fff]"))
                }
                hideImeForCapture()
                compose.onNodeWithTag("extension-field-result").performScrollTo().assertIsDisplayed().assertTextContains(translated)
                compose.onNodeWithTag("extension-primary").performClick()
                await { clipboard() == translated }
                row.put("translation_text", translated).put("workflow", "Translate owned English hello to Japanese in the Form and copy the exact visible service response; translation quality is reviewed separately")
            }
            "json-format/formatSelectionAndShow" -> {
                setClipboard(inputJson)
                open(extension, command)
                await { activeRoot(catalog.runtimeTree)?.optString("type") == "Detail" }
                var settled = false
                try {
                    await(5000) { activeRoot(catalog.runtimeTree)?.props()?.optBoolean("isLoading") == false }
                    settled = true
                } catch (_: ComposeTimeoutException) {
                    // Record this bounded observation without promoting an
                    // empty/loading screen to a successful selection workflow.
                }
                assertEquals(inputJson, clipboard())
                row.put("workflow", "Observe the selected-text Detail for up to five seconds and verify that owned clipboard input is not silently substituted or changed")
                    .put("settled_within_observation", settled).put("selection_observation_ms", 5000)
                    .put("observed_markdown", activeRoot(catalog.runtimeTree)!!.props().optString("markdown"))
                    .put("remaining_workflow", "No external selection was supplied and no formatted selected-text output was verified. Desktop selectedText is unavailable in this host; this row does not certify an Android replacement or an observed bridge error.")
                return false
            }
            "google-translate/instant-translate-copy" -> {
                setClipboard("hello")
                open(extension, command)
                await { catalog.runtimeStatus == "No text selected. Please select text to translate." }
                assertEquals("hello", clipboard())
                row.put("workflow", "Observe the no-selection HUD and verify that Instant Translate Copy leaves owned clipboard text unchanged")
                    .put("remaining_workflow", "Translation and selected-text access are not certified. The bundle catches selectedText errors and shows the no-selection HUD; no Android selection replacement is provided.")
                return false
            }
            "weather/index" -> {
                row.put("workflow", "Request Tokyo weather in metric units, wait for the service result, and open a daily forecast's hourly List by touch")
                open(extension, command)
                await(60000) {
                    activeRoot(catalog.runtimeTree)?.let { it.optString("type") == "List" && !it.props().optBoolean("isLoading") && (it.nodes("List.Item").isNotEmpty() || it.nodes("List.EmptyView").isNotEmpty()) } == true
                }
                val root = activeRoot(catalog.runtimeTree)!!
                val empty = root.nodes("List.EmptyView").firstOrNull()
                if (empty != null) {
                    row.put("workflow", "Request Tokyo weather and wait for the service result")
                        .put("service_error", safeMessage(empty.props().optString("title") + " · " + empty.props().optString("description")))
                    throw RuntimeFailure("Weather service workflow: " + row.getString("service_error"))
                }
                val items = root.nodes("List.Item")
                val index = items.indexOfFirst { item -> item.actions().any { it.props().optString("title") == "Show Details" } }
                assertTrue("A daily forecast must offer its hourly destination", index >= 0)
                supportingCapture("forecast-list")
                compose.onNodeWithTag("extension-list").performScrollToNode(hasTestTag("extension-row-$index"))
                compose.onNodeWithTag("extension-row-$index").performClick()
                compose.onNodeWithTag("extension-primary").assertTextContains("Show Details").performClick()
                await { catalog.navigationDepth > 1 && activeRoot(catalog.runtimeTree)?.nodes("List.Item")?.any { it.props().optString("subtitle").contains(Regex("-?[0-9]+.*°")) } == true }
                row.put("destination_verified", "Native hourly forecast List with temperature-bearing rows")
                    .put("action_destination", "Native hourly forecast List; no clipboard output expected")
                    .put("workflow", "Load Tokyo weather in metric units, select a daily forecast by touch, and verify its hourly List destination; service data accuracy is not certified")
            }
            else -> error("No workflow is defined for $directory/$command")
        }
        row.put("action_output_verified", true)
        return true
    }

    private fun cleanOwner(id: String) {
        File(context.cacheDir, id).deleteRecursively()
        File(context.filesDir, "extension-data/$id").deleteRecursively()
        context.deleteSharedPreferences("extension-$id")
        assertFalse(File(context.cacheDir, id).exists())
        assertFalse(File(context.filesDir, "extension-data/$id").exists())
    }

    private fun returnByTouch() {
        var attempts = 0
        while (catalog.running != null && attempts++ < 4) {
            val depth = catalog.navigationDepth
            val tag = if (catalog.runtimeError != null) "extension-error-close" else "extension-back"
            compose.onNodeWithTag(tag).performClick()
            compose.waitUntil(10000) { catalog.running == null || catalog.navigationDepth < depth }
            compose.waitForIdle()
        }
        assertNull("Visible Back/Close must end the command session", catalog.running)
        assertNull("Touch return must restore the launcher caller", compose.activity.store.dialog)
        assertEquals("Touch return must preserve the caller query", callerQuery, compose.activity.store.query)
    }

    @Test fun frozenWorkflowsProduceIndependentObservations() {
        for (entry in fixture.getJSONArray("extensions").objects()) {
            val commands = entry.getJSONArray("planned_commands")
            for (index in 0 until commands.length()) {
                val command = commands.getString(index)
                val directory = entry.getString("directory")
                val suffix = hash("$directory/$command".toByteArray(Charsets.UTF_8)).take(8)
                val name = "workflow-$directory-${command.lowercase(java.util.Locale.ROOT)}-$suffix"
                currentCaptureName = name
                val extension = prepareExtension(entry, command)
                val row = JSONObject().put("directory", directory).put("command", command)
                    .put("archive_sha256", entry.getString("archive_sha256"))
                    .put("command_sha256", entry.getJSONArray("commands").objects().single { it.getString("name") == command }.getString("file_sha256"))
                    .put("installation", "Native archive extraction into isolated cache; Store UI installation not exercised")
                    .put("status", "untested").put("action_output_verified", false)
                    .put("phases", JSONObject().put("archive_extraction", "verified")
                        .put("startup", "not-checked").put("rendering", "not-checked")
                        .put("interaction_and_action", "not-checked").put("destination", "not-checked")
                        .put("persistence", "not-checked").put("touch_return", "not-checked")
                        .put("isolated_data_cleanup", "pending"))
                currentObservation = row
                try {
                    setClipboard("Droidcast workflow sentinel")
                    row.put("status", if (workflow(entry, extension, command, row)) "tested-working-for-listed-workflow" else "tested-partial")
                } catch (error: ConfigurationNeeded) {
                    row.put("status", "requires-user-configuration/authentication").put("missing_fields", JSONArray(error.fields))
                } catch (error: RuntimeFailure) {
                    val reason = safeMessage(error.message.orEmpty())
                    row.put("status", if (reason.contains("requires a desktop API unavailable") || reason.contains("unsupported desktop capability") || reason.contains("Clipboard paste into another app is unavailable")) "platform-unsupported-with-specific-reason" else "observed-failed")
                        .put("runtime_error", reason)
                } catch (error: Throwable) {
                    // A harness timeout/assertion is not automatically a claim
                    // that an extension is incompatible. Retain for triage.
                    row.put("status", if (row.optBoolean("action_output_verified") || row.has("interaction_verified")) "tested-partial" else "untested")
                        .put("attempted", true).put("needs_triage", true)
                        .put("observation_error_type", error.javaClass.simpleName).put("observation_message", safeMessage(error.message.orEmpty()))
                }
                val phases = row.getJSONObject("phases")
                if (row.optBoolean("action_output_verified")) {
                    phases.put("interaction_and_action", if (row.getString("status") == "tested-working-for-listed-workflow") "verified for listed workflow" else "partial; see per-step checks")
                        .put("destination", row.optString("action_destination", "owned clipboard output verified"))
                }
                if (row.has("interaction_verified")) phases.put("native_input", row.getString("interaction_verified"))
                if (row.has("destination_verified")) phases.put("native_destination", row.getString("destination_verified"))
                if (row.optBoolean("persistence_verified")) phases.put("persistence", "verified across a new command session in the same app process")
                try {
                    capture(name)
                    row.put("runtime_status", safeMessage(catalog.runtimeStatus)).put("capture", "$name.png")
                        .put("capture_receipt", "$name-frame.json").put("native_root", activeRoot(catalog.runtimeTree)?.optString("type") ?: JSONObject.NULL)
                    if (activeRoot(catalog.runtimeTree) != null) phases.put("rendering", "native command screen captured; inspect the listed workflow checks")
                    try {
                        returnByTouch()
                        phases.put("touch_return", "verified caller query and closed command session")
                        capture("$name-return")
                        row.put("return_capture", "$name-return.png")
                    } catch (error: Throwable) {
                        phases.put("touch_return", "failed observation; requires triage")
                        row.put("needs_triage", true).put("return_error", safeMessage(error.message.orEmpty()))
                        if (row.getString("status") == "tested-working-for-listed-workflow") row.put("status", "tested-partial")
                    }
                } finally {
                    compose.activityRule.scenario.onActivity { catalog.close(); it.store.dialog = null }
                    compose.waitForIdle()
                    cleanOwner(extension.listing.id)
                }
                row.put("isolated_data_removed", true)
                phases.put("isolated_data_cleanup", "verified")
                rows.put(row)
                File(evidence, "$name.json").writeText(row.toString(2))
                writeCollection(false)
                currentObservation = null
            }
        }
        assertEquals(installedIds, catalog.installed.map { it.listing.id }.toSet())
        assertEquals(preservedHashes, preserved.map(::fingerprint))
        writeCollection(true)
    }

    private fun writeCollection(completed: Boolean) {
        File(evidence, "collection.json").writeText(JSONObject().put("completed", completed).put("fixture_sha256", fixtureHash)
            .put("source_snapshot_sha256", fixture.getString("source_snapshot_sha256"))
            .put("api", android.os.Build.VERSION.SDK_INT).put("abi", android.os.Build.SUPPORTED_ABIS.first())
            .put("webview_package", WebView.getCurrentWebViewPackage()?.packageName)
            .put("webview_version", WebView.getCurrentWebViewPackage()?.versionName)
            .put("font_scale", context.resources.configuration.fontScale)
            .put("density_dpi", context.resources.displayMetrics.densityDpi)
            .put("input_method", "Compose pointer actions and text semantics; no physical touchscreen/keyboard certification")
            .put("workflows", rows).put("scope", "Collector completion is not a blanket compatibility pass; each status names its actual exercised workflow.").toString(2))
    }

    @After fun restore() {
        if (!captured) return
        compose.activityRule.scenario.onActivity {
            catalog.close(); it.store.dialog = null
            val clipboard = it.getSystemService(ClipboardManager::class.java)
            originalClipboard?.let(clipboard::setPrimaryClip) ?: clipboard.resetClipboardForTest()
        }
        ownedIds.forEach(::cleanOwner)
        restorePalettePreferences(context, originalPalette)
        assertEquals(installedIds, catalog.installed.map { it.listing.id }.toSet())
        assertEquals(preservedHashes, preserved.map(::fingerprint))
    }
}
