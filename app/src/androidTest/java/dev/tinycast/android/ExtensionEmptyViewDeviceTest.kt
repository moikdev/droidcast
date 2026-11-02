package dev.tinycast.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.webkit.WebView
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.tinycast.android.extensions.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real Android rendering fixtures; no network service or Store compatibility is simulated. */
@RunWith(AndroidJUnit4::class)
class ExtensionEmptyViewDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val testName = org.junit.rules.TestName()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val catalog get() = compose.activity.store.extensions
    private val evidence get() = File(context.filesDir, "readiness-evidence")
    private val ownerFile get() = File(context.filesDir, "empty-view-fixture-lease/owners.json")
    private val nonce = UUID.randomUUID().toString().replace("-", "")
    private val caller = "empty-state-caller-$nonce"
    private var palette = emptyMap<String, Any?>()
    private var clipboard: ClipData? = null
    private var prepared = false
    private var ownedId: String? = null
    private var originalIds = emptySet<String>()
    private var preserved = emptyList<File>()
    private var fingerprints = emptyList<Map<String, String>>()
    private var kind = ""
    private val record = JSONObject().put("scope", "Owned JavaScript fixture rendered by the real runtime and native UI; not a service or Store workflow")
    private val shortTitle = "Owned request needs attention"
    private val shortDescription = "The owned request did not complete. Choose Retry request to load the owned result."
    private val longTitle = "The owned request did not complete. Review the connection and retry when you are ready."
    private val longDescription = "This is a controlled error explanation for the native extension view. " +
        "Every line should stay readable at the system text size. You can scroll this explanation by touch without changing the search query. " +
        "The request has not changed any saved information. The final line confirms that the complete explanation is reachable."

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun fingerprint(root: File): Map<String, String> = if (!root.exists()) emptyMap() else root.walkTopDown().associate {
        it.relativeTo(root).path to if (it.isDirectory) "directory" else hash(it.readBytes())
    }

    @Before fun prepare() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Use the explicit guarded empty-view runner", args.getString("emptyViewFixture") == "true")
        assertEquals("Tinycast_Tablet_API_36", args.getString("dedicatedAvd"))
        val owner = args.getString("expectedEmptyViewLeaseOwner")
        assertTrue(owner?.matches(Regex("[0-9a-f]{32}")) == true)
        assertTrue(ownerFile.isFile && ownerFile.length() in 1..16384)
        val lease = JSONObject(ownerFile.readText())
        assertEquals("empty-view-device-test-v1", lease.getString("fixture_kind"))
        assertEquals(owner, lease.getString("owner_nonce"))
        compose.waitUntil(15000) { !compose.activity.store.loading }
        args.getString("expectedFontScale")?.toFloat()?.let { expected ->
            compose.waitUntil(10000) { kotlin.math.abs(context.resources.configuration.fontScale - expected) < .01f }
        }
        palette = context.getSharedPreferences("palette", Context.MODE_PRIVATE).all.toMap()
        originalIds = catalog.installed.map { it.listing.id }.toSet()
        preserved = catalog.installed.flatMap { listOf(it.directory, File(context.filesDir, "extension-data/${it.listing.id}"),
            File(context.applicationInfo.dataDir, "shared_prefs/extension-${it.listing.id}.xml")) }
        fingerprints = preserved.map(::fingerprint)
        dismissClipboardForEvidence()
        compose.activityRule.scenario.onActivity {
            val manager = it.getSystemService(ClipboardManager::class.java)
            clipboard = manager.primaryClip
            prepared = true
            manager.setPrimaryClip(ClipData.newPlainText("Owned empty-view fixture", "Droidcast empty-state fixture"))
            it.store.extensions.close(); it.store.dialog = null; it.store.search(caller)
        }
        dismissClipboardForEvidence()
        record.put("font_scale", context.resources.configuration.fontScale)
            .put("width_dp", context.resources.configuration.screenWidthDp).put("height_dp", context.resources.configuration.screenHeightDp)
            .put("installed_build_commit", context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData.getString("dev.droidcast.BUILD_COMMIT"))
    }

    private fun launch(type: String, mode: String = "error") {
        kind = type
        val id = "empty-view-" + UUID.randomUUID().toString().replace("-", "")
        val root = File(context.cacheDir, id)
        val support = File(context.filesDir, "extension-data/$id")
        val prefs = File(context.applicationInfo.dataDir, "shared_prefs/extension-$id.xml")
        assertFalse(root.exists()); assertFalse(support.exists()); assertFalse(prefs.exists())
        val lease = JSONObject(ownerFile.readText())
        val ids = lease.getJSONArray("owned_extension_ids")
        assertTrue(ids.length() < 100); assertTrue((0 until ids.length()).none { ids.getString(it) == id }); ids.put(id)
        val temporary = File(ownerFile.path + ".tmp"); assertFalse(temporary.exists())
        temporary.writeText(lease.toString()); assertTrue(temporary.renameTo(ownerFile))
        ownedId = id
        assertTrue(root.mkdirs()); assertTrue(support.mkdirs())
        val assets = File(root, "assets").apply { assertTrue(mkdir()) }
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.rgb(35, 95, 150))
        canvas.drawCircle(32f, 32f, 18f, Paint().apply { color = Color.rgb(255, 205, 65) })
        File(assets, "owned-icon.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        val outside = File(support, "outside-package-control.png")
        File(assets, "owned-icon.png").copyTo(outside)
        val icon = when (mode) { "image" -> "owned-icon.png"; "unavailable" -> outside.path; else -> "⛈️" }
        val title = if (mode == "long") longTitle else shortTitle
        val description = if (mode == "long") longDescription else shortDescription
        val script = """
            const React=require('react'); const api=require('@raycast/api');
            const View=api[${JSONObject.quote(type)}], {Action,ActionPanel,Clipboard}=api;
            globalThis.__emptyFixtureAttempts=0; globalThis.__emptyFixtureDispatches=[];
            const dispatch=globalThis.__tinycast.dispatch;
            globalThis.__tinycast.dispatch=function(...args){globalThis.__emptyFixtureDispatches.push(args[1]);return dispatch.apply(this,args)};
            module.exports.default=function(){
              const [query,setQuery]=React.useState(''),[count,setCount]=React.useState(0);
              const panel=React.createElement(ActionPanel,null,React.createElement(Action,{title:count?'Retry owned result':'Retry request',onAction:()=>{globalThis.__emptyFixtureAttempts++;setCount(n=>n+1)}}));
              const empty=React.createElement(View.EmptyView,{key:'empty',title:count?'No owned matches':${JSONObject.quote(title)},
                description:count?'Change the search to owned to see the retry result.':${JSONObject.quote(description)},icon:${JSONObject.quote(icon)},actions:panel});
              const item=React.createElement(View.Item,{key:'result',id:'owned-result',title:'Owned retry result '+count,content:'🌱',
                actions:React.createElement(ActionPanel,null,React.createElement(Action.CopyToClipboard,{title:'Copy owned result',content:'owned-retry:'+count}))});
              return React.createElement(View,{columns:2,filtering:true,searchText:query,onSearchTextChange:setQuery,
                searchBarPlaceholder:'Search the owned empty-view fixture',isLoading:${mode == "loading"}&&count===0},count?item:null,empty);
            };
        """.trimIndent()
        File(root, "empty.js").writeText(script)
        val extension = InstalledExtension(StoreExtension(id, "empty-view-fixture", "Owned empty-view fixture", "", "Droidcast tests", "test", "", "", "", 1),
            root, JSONObject("""{"commands":[{"name":"empty","title":"Native empty-view fixture","mode":"view"}]}"""))
        record.put("fixture_type", type).put("fixture_mode", mode).put("fixture_command_sha256", hash(script.toByteArray(Charsets.UTF_8)))
            .put("owned_extension_id", id).put("outside_package_control_sha256", hash(outside.readBytes()))
        compose.activityRule.scenario.onActivity { it.store.openExtension(extension, extension.commands.single()) }
        compose.waitUntil(25000) { catalog.runtimeError != null || (catalog.runtimeReady && activeRoot(catalog.runtimeTree)?.optString("type") == type) }
        assertNull(catalog.runtimeError, catalog.runtimeError)
        compose.onNodeWithTag("extension-search").assertIsDisplayed()
    }

    private fun touch(tag: String) = compose.onNodeWithTag(tag).performTouchInput { click(center) }
    private fun query(value: String) {
        compose.onNodeWithTag("extension-search").performTextReplacement(value)
        compose.waitUntil(10000) { activeRoot(catalog.runtimeTree)?.props()?.optString("searchText") == value }
        compose.waitForIdle()
    }
    private fun returnToCaller() {
        touch("extension-back")
        compose.waitUntil(10000) { catalog.running == null && compose.activity.store.dialog == null }
        compose.onNodeWithTag("search").assertTextEquals(caller)
        record.put("touch_return_and_caller_query_verified", true)
        capture("return")
    }

    private fun retry(type: String, mode: String = "error") {
        launch(type, mode); query("owned")
        compose.onNodeWithText(shortTitle).assertIsDisplayed(); compose.onNodeWithText(shortDescription).assertIsDisplayed()
        if (mode == "image" || mode == "unavailable") {
            val label = if (mode == "image") "Extension icon" else "Icon unavailable"
            compose.waitUntil(10000) { compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(label).assertIsDisplayed()
        }
        capture("error")
        compose.onNodeWithTag("extension-actions").assertIsEnabled(); touch("extension-actions")
        compose.onNodeWithTag("extension-action-0").assertTextContains("Retry request", substring = true)
        capture("actions"); touch("extension-action-0")
        compose.waitUntil(10000) { activeRoot(catalog.runtimeTree)?.nodes("$type.Item")?.singleOrNull()?.props()?.optString("title") == "Owned retry result 1" }
        compose.onNodeWithText("Owned retry result 1").assertIsDisplayed()
        compose.onNodeWithText(shortTitle).assertDoesNotExist()
        compose.onNodeWithTag("extension-search").assertTextEquals("owned")
        record.put("retry_count", 1).put("native_retry_destination_verified", true)
        capture("result"); returnToCaller(); record.put("passed", true)
    }

    private fun loading(type: String) {
        launch(type, "loading")
        compose.onNodeWithText(shortTitle).assertDoesNotExist(); compose.onNodeWithText("Loading…").assertIsDisplayed()
        if (compose.onAllNodesWithTag("extension-primary").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithTag("extension-primary").assertIsNotEnabled()
        compose.onNodeWithTag("extension-action-0").assertDoesNotExist(); capture("initial-loading")
        query("owned")
        compose.onNodeWithText(shortTitle).assertIsDisplayed(); compose.onNodeWithTag("extension-primary").assertIsEnabled()
        capture("loading-query"); touch("extension-primary")
        compose.waitUntil(10000) { activeRoot(catalog.runtimeTree)?.nodes("$type.Item")?.singleOrNull()?.props()?.optString("title") == "Owned retry result 1" }
        query("absent-match")
        compose.onNodeWithText("No owned matches").assertIsDisplayed(); compose.onNodeWithTag("extension-primary").assertTextContains("Retry owned result", substring = true)
        capture("filtered-empty")
        query("owned"); compose.onNodeWithText("Owned retry result 1").assertIsDisplayed()
        compose.onNodeWithTag("extension-primary").assertTextContains("Copy owned result", substring = true)
        record.put("initial_loading_override_hidden", true).put("loading_query_override_visible", true).put("filtered_empty_override_and_action_verified", true)
        returnToCaller(); record.put("passed", true)
    }

    private fun imeVisible() = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    private fun ime(show: Boolean) {
        if (show) {
            touch("extension-search")
            if (compose.onAllNodesWithContentDescription("Show on-screen keyboard").fetchSemanticsNodes().isNotEmpty())
                compose.onNodeWithContentDescription("Show on-screen keyboard").performTouchInput { click(center) }
        } else if (imeVisible()) assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack())
        compose.waitUntil(10000) { imeVisible() == show }
        var previous: Rect? = null
        var since = 0L
        val viewport = if (kind == "List") "extension-list" else "extension-grid-empty"
        compose.waitUntil(10000) {
            val bounds = compose.onNodeWithTag(viewport).fetchSemanticsNode().boundsInRoot
            val now = android.os.SystemClock.elapsedRealtime()
            if (bounds != previous || imeVisible() != show) { previous = bounds; since = now; false } else now - since >= 300
        }
        assertNotNull("IME setup must retain the command", catalog.running)
    }

    private fun fullBounds(interaction: SemanticsNodeInteraction): Rect {
        val node = interaction.fetchSemanticsNode()
        return compose.runOnUiThread {
            val coordinates = node.layoutInfo.coordinates
            val origin = coordinates.localToRoot(Offset.Zero)
            Rect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height)
        }
    }

    private fun drag(viewport: String, direction: Int) {
        compose.onNodeWithTag(viewport).performTouchInput {
            down(center); moveTo(Offset(center.x, center.y + direction * height * .25f), delayMillis = 300)
            advanceEventTime(200); up()
        }
        compose.waitForIdle()
    }

    private fun inspectLines(viewport: String, shown: Boolean) {
        val title = compose.onNodeWithText(longTitle, useUnmergedTree = true)
        val description = compose.onNodeWithText(longDescription, useUnmergedTree = true)
        title.assertExists(); description.assertExists()
        var attempts = 0
        while (attempts++ < 100) {
            val before = fullBounds(title).top; drag(viewport, 1)
            if (kotlin.math.abs(fullBounds(title).top - before) < 1) break
        }
        val layouts = listOf(title, description).map { node ->
            val values = mutableListOf<TextLayoutResult>(); node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(values) }
            values.single().also { text ->
                assertTrue(text.lineCount > 0); assertTrue(fullBounds(node).height + 1 >= text.size.height)
                assertTrue((0 until text.lineCount).none(text::isLineEllipsized))
            }
        }
        val witnessed = layouts.map { BooleanArray(it.lineCount) }
        val receipts = JSONArray(); capture(if (shown) "ime-top" else "closed-top")
        attempts = 0
        while (attempts++ < 160) {
            val viewportBounds = compose.onNodeWithTag(viewport).fetchSemanticsNode().boundsInRoot
            assertTrue("The text viewport needs visible height", viewportBounds.height > 0)
            val step = JSONObject().put("viewport", JSONArray(listOf(viewportBounds.left, viewportBounds.top, viewportBounds.right, viewportBounds.bottom)))
            listOf(title, description).forEachIndexed { index, interaction ->
                val bounds = fullBounds(interaction); val layout = layouts[index]
                for (line in 0 until layout.lineCount) {
                    val left = bounds.left + layout.getLineLeft(line); val right = bounds.left + layout.getLineRight(line)
                    val top = bounds.top + layout.getLineTop(line); val bottom = bounds.top + layout.getLineBottom(line)
                    assertTrue("Text line exceeds the viewport width", left >= viewportBounds.left - 1 && right <= viewportBounds.right + 1)
                    if (top >= viewportBounds.top - 1 && bottom <= viewportBounds.bottom + 1) witnessed[index][line] = true
                }
                step.put(if (index == 0) "title_lines_seen" else "description_lines_seen", witnessed[index].count { it })
            }
            receipts.put(step)
            if (witnessed.all { it.all { seen -> seen } }) break
            val before = fullBounds(title).top; drag(viewport, -1)
            if (kotlin.math.abs(fullBounds(title).top - before) < 1) break
        }
        record.put(if (shown) "ime_line_probe" else "closed_line_probe", JSONObject().put("ime_visible", imeVisible())
            .put("title_lines", layouts[0].lineCount).put("description_lines", layouts[1].lineCount)
            .put("all_lines_reached", witnessed.all { it.all { seen -> seen } }).put("pointer_scroll_steps", receipts))
        capture(if (shown) "ime-bottom" else "closed-bottom")
        assertEquals(shown, imeVisible())
        assertTrue("Every natural title/description line must become fully visible through pointer scrolling", witnessed.all { it.all { seen -> seen } })
    }

    private fun typography(type: String) {
        launch(type, "long"); query("owned")
        ime(false); inspectLines(if (type == "List") "extension-list" else "extension-grid-empty", false)
        ime(true); inspectLines(if (type == "List") "extension-list" else "extension-grid-empty", true)
        compose.onNodeWithTag("extension-search").assertTextEquals("owned")
        returnToCaller(); record.put("passed", true)
    }

    private fun probe(): JSONObject {
        var engine: WebView? = null
        compose.activityRule.scenario.onActivity { activity ->
            fun visit(view: android.view.View) {
                if (view is WebView) { assertNull(engine); engine = view }
                if (view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            visit(activity.window.decorView)
        }
        val result = AtomicReference<String>()
        compose.activityRule.scenario.onActivity {
            requireNotNull(engine).evaluateJavascript("JSON.stringify({attempts:globalThis.__emptyFixtureAttempts,dispatches:globalThis.__emptyFixtureDispatches.length})") { result.set(it) }
        }
        compose.waitUntil(5000) { result.get() != null }
        return JSONObject(JSONArray("[${result.get()}]").getString(0))
    }

    private fun queued(type: String) {
        launch(type); query("owned"); touch("extension-actions")
        val click = requireNotNull(compose.onNodeWithTag("extension-action-0").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val before = probe()
        compose.activityRule.scenario.onActivity {
            val replacement = JSONObject(catalog.runtimeTree.toString())
            val root = requireNotNull(activeRoot(replacement))
            val replacementItem = JSONObject().put("id", "owned-replacement").put("type", "$type.Item")
                .put("props", JSONObject().put("id", "owned-replacement").put("title", "Owned replacement result").put("content", "🌱"))
                .put("children", JSONArray())
            root.put("children", JSONArray().put(replacementItem))
            catalog.runtimeTree = replacement
            assertTrue(click())
        }
        val after = probe()
        assertEquals(before.toString(), after.toString())
        assertEquals(0, after.getInt("attempts"))
        compose.onNodeWithText("Owned replacement result").assertIsDisplayed()
        record.put("queued_callback_scope", "Controlled native replacement and retained menu callback in one main-thread turn; no new Compose frame before activation")
            .put("before", before).put("after", after)
        capture("queued-blocked"); returnToCaller(); record.put("passed", true)
    }

    private fun capture(phase: String) {
        compose.waitForIdle()
        val tree = catalog.runtimeTree?.toString(); val epoch = catalog.launchEpoch
        val frames = CountDownLatch(1)
        compose.activityRule.scenario.onActivity { it.window.decorView.postOnAnimation { it.window.decorView.postOnAnimation { frames.countDown() } } }
        assertTrue(frames.await(5, TimeUnit.SECONDS)); compose.waitForIdle()
        assertEquals(epoch, catalog.launchEpoch); assertEquals(tree, catalog.runtimeTree?.toString())
        val name = "empty-${testName.methodName.lowercase(java.util.Locale.ROOT)}-$phase"
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.dumpWindowHierarchy(File(evidence, "$name.xml")); assertTrue(device.takeScreenshot(File(evidence, "$name.png")))
        assertEquals(epoch, catalog.launchEpoch); assertEquals(tree, catalog.runtimeTree?.toString())
        File(evidence, "$name-frame.json").writeText(JSONObject().put("fixture_type", kind).put("launch_epoch", epoch)
            .put("runtime_tree_sha256", tree?.let { hash(it.toByteArray(Charsets.UTF_8)) } ?: JSONObject.NULL)
            .put("ime_visible", imeVisible()).put("font_scale", context.resources.configuration.fontScale)
            .put("scope", record.getString("scope")).put("synchronization", "Committed semantics, two native display frames, hierarchy before PNG, stable runtime tree").toString(2))
    }

    @After fun restore() {
        if (!prepared) return
        try {
            if (!record.optBoolean("passed") && ownedId != null) try { capture("failure") }
            catch (error: Exception) { record.put("failure_capture_error", error.javaClass.simpleName) }
            catch (error: AssertionError) { record.put("failure_capture_error", error.javaClass.simpleName) }
        } finally {
            compose.activityRule.scenario.onActivity {
                it.store.extensions.close(); it.store.dialog = null
                val manager = it.getSystemService(ClipboardManager::class.java)
                clipboard?.let(manager::setPrimaryClip) ?: manager.resetClipboardForTest()
            }
            restorePalettePreferences(context, palette)
            ownedId?.let { id ->
                listOf(File(context.cacheDir, id), File(context.filesDir, "extension-data/$id")).forEach { it.deleteRecursively(); assertFalse(it.exists()) }
                listOf(".xml", ".xml.bak").forEach { suffix -> val file = File(context.applicationInfo.dataDir, "shared_prefs/extension-$id$suffix"); file.delete(); assertFalse(file.exists()) }
            }
            assertEquals(originalIds, catalog.installed.map { it.listing.id }.toSet())
            assertEquals(fingerprints, preserved.map(::fingerprint))
            record.put("owned_data_removed", true).put("preexisting_installations_and_data_preserved", true)
            File(evidence, "empty-${testName.methodName.lowercase(java.util.Locale.ROOT)}-record.json").writeText(record.toString(2))
        }
    }

    @Test fun listErrorRetriesThroughTouchActions() = retry("List")
    @Test fun gridErrorRetriesThroughTouchActions() = retry("Grid")
    @Test fun listLoadingAndFilteredEmptySwitchActions() = loading("List")
    @Test fun gridLoadingAndFilteredEmptySwitchActions() = loading("Grid")
    @Test fun listLongErrorLinesRemainReachableByTouch() = typography("List")
    @Test fun gridLongErrorLinesRemainReachableByTouch() = typography("Grid")
    @Test fun listPackageImageKeepsErrorAndRetry() = retry("List", "image")
    @Test fun gridPackageImageKeepsErrorAndRetry() = retry("Grid", "image")
    @Test fun listOutsidePackageIconKeepsErrorAndRetry() = retry("List", "unavailable")
    @Test fun gridOutsidePackageIconKeepsErrorAndRetry() = retry("Grid", "unavailable")
    @Test fun listQueuedEmptyActionCannotRunAfterItemsReturn() = queued("List")
    @Test fun gridQueuedEmptyActionCannotRunAfterItemsReturn() = queued("Grid")
}
