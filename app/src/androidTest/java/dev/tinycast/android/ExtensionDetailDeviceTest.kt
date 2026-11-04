package dev.tinycast.android

import android.graphics.Bitmap
import android.content.Intent
import android.content.ContextWrapper
import android.content.ActivityNotFoundException
import android.webkit.WebView
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import java.util.Base64
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.font.FontFamily
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
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
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Owned native Detail interaction, image, lifecycle and typography coverage. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class ExtensionDetailDeviceTest {
    private companion object { const val IMAGE_HISTORY_PROPERTY = "dev.droidcast.detailImageHistory" }
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val testName = org.junit.rules.TestName()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val catalog get() = compose.activity.store.extensions
    private val leaseFile get() = File(context.filesDir, "reviewed-text-fixture-lease/owners.json")
    private val evidence get() = File(context.filesDir, "reviewed-text-evidence").apply { mkdirs() }
    private val record = JSONObject().put("scope", "Owned React Markdown/metadata through the real runtime and native UI; not Store or service certification")
    private var prepared = false
    private var originalImageHistoryProperty: String? = null
    private var imageHistoryPropertyOwned = false
    private var owner = ""
    private var ownedId: String? = null
    private val ownedIds = mutableListOf<String>()
    private var palette = emptyMap<String, Any?>()
    private var originalIds = emptySet<String>()
    private var preserved = emptyList<File>()
    private var fingerprints = emptyList<Map<String, String>>()
    private val caller = "detail-owned-caller"
    private val markdown = "# Owned heading\n\n**Bold value** with `inline  code` and café é 日本 🌱.\n\n```kotlin\n  val message = \"日本\"\n\n```\n"

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun fingerprint(root: File): Map<String, String> = if (!root.exists()) emptyMap() else root.walkTopDown().associate {
        it.relativeTo(root).path to if (it.isDirectory) "directory" else hash(it.readBytes())
    }
    private fun lease(): JSONObject {
        val value = JSONObject(leaseFile.readText())
        assertEquals(setOf("schema_version", "fixture_kind", "owner_nonce", "owned_extension_ids"), value.keys().asSequence().toSet())
        assertEquals(1, value.getInt("schema_version"))
        assertEquals("reviewed-text-device-test-v1", value.getString("fixture_kind"))
        assertEquals(owner, value.getString("owner_nonce"))
        return value
    }
    private fun dismissClipboard(phase: String) {
        val observations = JSONArray(); val actions = JSONArray()
        record.put("clipboard-$phase", JSONObject().put("observations", observations).put("actions", actions))
        dismissClipboardForEvidence(onObservation = { observations.put(it) }, onNodeAction = { id, accepted ->
            actions.put(JSONObject().put("id", id).put("accepted", accepted))
        })
    }
    @Before fun prepare() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Use the guarded owned-input runner", args.getString("reviewedTextFixture") == "true")
        assertEquals("Tinycast_Tablet_API_36", args.getString("dedicatedAvd"))
        owner = args.getString("expectedTextInputLeaseOwner").orEmpty()
        assertTrue(owner.matches(Regex("[0-9a-f]{32}")))
        assertEquals(owner, args.getString("expectedClipboardRecoveryOwner")); lease()
        compose.waitUntil(15000) { !compose.activity.store.loading }
        palette = context.getSharedPreferences("palette", Context.MODE_PRIVATE).all.toMap()
        originalIds = catalog.installed.map { it.listing.id }.toSet()
        preserved = catalog.installed.flatMap { listOf(it.directory, File(context.filesDir, "extension-data/${it.listing.id}"),
            File(context.applicationInfo.dataDir, "shared_prefs/extension-${it.listing.id}.xml")) }
        fingerprints = preserved.map(::fingerprint); prepared = true
        originalImageHistoryProperty = System.getProperty(IMAGE_HISTORY_PROPERTY)
        System.setProperty(IMAGE_HISTORY_PROPERTY, "true"); imageHistoryPropertyOwned = true
        dismissClipboard("before-owned-sentinel")
        compose.activityRule.scenario.onActivity {
            // Original clipboard recovery is durable in the foreground test-APK UID before this mutation.
            it.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Owned Markdown fixture", "Owned Markdown clipboard sentinel"))
            it.store.reset(); it.store.search(caller)
        }
        dismissClipboard("owned-sentinel")
        record.put("font_scale", context.resources.configuration.fontScale)
            .put("width_dp", context.resources.configuration.screenWidthDp).put("height_dp", context.resources.configuration.screenHeightDp)
            .put("installed_build_commit", context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData.getString("dev.droidcast.BUILD_COMMIT"))
    }
    private fun fixtureDirectory(): File {
        val id = "reviewed-text-" + UUID.randomUUID().toString().replace("-", "")
        val directory = File(context.cacheDir, id)
        listOf(directory, File(context.filesDir, "extensions/$id"), File(context.filesDir, "extension-data/$id"),
            File(context.applicationInfo.dataDir, "shared_prefs/extension-$id.xml"),
            File(context.applicationInfo.dataDir, "shared_prefs/extension-$id.xml.bak")).forEach {
            assertFalse(Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS))
        }
        val value = lease(); val ids = value.getJSONArray("owned_extension_ids")
        assertTrue(ids.length() < 100); assertTrue((0 until ids.length()).none { ids.getString(it) == id }); ids.put(id)
        val temporary = File(leaseFile.path + ".tmp"); assertFalse(temporary.exists())
        temporary.writeText(value.toString()); assertTrue(temporary.renameTo(leaseFile))
        ownedId = id; ownedIds += id; assertTrue(directory.mkdir())
        return directory
    }
    private fun launch(mode: String) {
        val directory = fixtureDirectory(); val id = directory.name
        val png = java.io.ByteArrayOutputStream().use { output ->
            val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(0xffe7582b.toInt()); assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); bitmap.recycle(); output.toByteArray()
        }
        val assets = File(directory, "assets"); assertTrue(assets.mkdir()); File(assets, "owned image.png").writeBytes(png)
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="120" height="80" viewBox="0 0 120 80"><rect width="120" height="80" fill="#228833"/></svg>"""
        File(assets, "owned.svg").writeText(svg)
        val dataSvg = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg.toByteArray())
        val images = """
            # Owned images

            ![Owned package image](owned%20image.png?raycast-width=96&raycast-height=64)

            ![Owned SVG image]($dataSvg?raycast-width=120&raycast-height=80)

            ![Owned tinted image](owned.svg?raycast-width=96&raycast-height=64&raycast-tintColor=%23ff0000)

            ![Owned unavailable image](../../outside.png)
        """.trimIndent()
        val typography = """
            # Owned readable heading

            Owned paragraph café é 日本 🌱. ${"Long readable content and Unicode 日本 remain reachable. ".repeat(12)}

            [Owned long link](javascript:owned-unused)

            ```text
              ${"Long_exact_code_日本_".repeat(12)}

            ```

            | First | Second | Third | Fourth | Fifth |
            | :--- | :---: | ---: | --- | --- |
            | Owned cell one | Owned cell two | Owned cell three | Owned cell four | Owned final cell 日本 |

            Owned final paragraph café é 日本 🌱.
        """.trimIndent()
        val gallery = """
            # Owned gallery heading

            Escaped \*literal\* and **bold _nested_** with ~~removed~~.

            3. Owned ordered item
            4. Owned next item
               - Owned nested item

            > Owned quote café 日本
            >
            > Owned second quotation

            ## Owned secondary heading

            | Owned image column | Owned text column |
            | --- | --- |
            | ![Owned table image](owned.svg) | Owned table image neighbor |

            Owned gallery final paragraph.
        """.trimIndent()
        val information = """
            ![Owned block image](owned.svg "Owned block description __DOCUMENT__ café 日本")

            Owned inline ![Owned inline image](owned.svg "Owned inline description __DOCUMENT__ café 日本") preview.

            Owned linked [![Owned linked image](owned.svg "Owned linked description __DOCUMENT__")](javascript:owned-image-information).
        """.trimIndent()
        val configuration = JSONObject().put("mode", mode).put("images", images).put("typography", typography)
            .put("gallery", gallery).put("imageInformation", information)
            .put("longMetadata", "Owned long metadata café é 日本 🌱. " + "Every natural line remains readable at the configured text size. ".repeat(8))
            .put("longUrl", "https://example.invalid/owned/" + "LongUnbrokenReference日本".repeat(18))
            .put("longInformation", "Owned bounded ![Owned long information image](owned.svg \"Owned long image information café 日本. " +
                "A persistent description stays readable while the user scrolls to Close. ".repeat(8) + "\") preview.")
            .put("imageScroll", "![Owned scrolling image](owned.svg)\n\n" +
                "Owned scroll-away tail café 日本. ".repeat(100) + "\n\nOwned scroll-away destination.")
            .put("tableOverflow", "|" + "x|".repeat(1001) + "\n|" + "-|".repeat(1001) + "\n" + "|x|\n".repeat(1000))
        val script = "globalThis.__ownedDetailConfiguration = " + configuration + ";\n" +
            InstrumentationRegistry.getInstrumentation().context.assets.open("owned-detail-fixture.js").bufferedReader().use { it.readText() }
        File(directory, "detail.js").writeText(script)
        val manifest = JSONObject("""{"commands":[{"name":"detail","title":"Owned Detail controls","mode":"view"}]}""")
        val extension = InstalledExtension(StoreExtension(id, "detail-fixture", "Owned Detail probe", "", "Droidcast tests", "test", "", "", "", 1), directory, manifest)
        record.put("fixture_mode", mode).put("fixture_command_sha256", hash(script.toByteArray(Charsets.UTF_8)))
            .put("fixture_manifest_sha256", hash(manifest.toString().toByteArray(Charsets.UTF_8))).put("owned_extension_id", id)
            .put("owned_png_sha256", hash(png)).put("owned_svg_sha256", hash(svg.toByteArray()))
        compose.activityRule.scenario.onActivity { it.store.openExtension(extension, extension.commands.single()) }
        compose.waitUntil(25000) { catalog.runtimeError != null || (catalog.runtimeReady && activeRoot(catalog.runtimeTree)?.props()?.optString("navigationTitle") == "Detail publication 0") }
        assertNull(catalog.runtimeError, catalog.runtimeError); compose.waitForIdle(); ime(false)
        witness("initial", 0)
    }
    private fun capture(phase: String) {
        compose.waitForIdle(); val tree = catalog.runtimeTree?.toString(); val epoch = catalog.launchEpoch
        val frames = CountDownLatch(1)
        compose.activityRule.scenario.onActivity { it.window.decorView.postOnAnimation { it.window.decorView.postOnAnimation { frames.countDown() } } }
        assertTrue(frames.await(5, TimeUnit.SECONDS)); compose.waitForIdle()
        assertEquals(epoch, catalog.launchEpoch); assertEquals(tree, catalog.runtimeTree?.toString())
        val name = "detail-${testName.methodName.lowercase(java.util.Locale.ROOT)}-$phase"
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()); testAutomation().clearCacheForTest()
        device.dumpWindowHierarchy(File(evidence, "$name.xml")); assertTrue(device.takeScreenshot(File(evidence, "$name.png")))
        assertEquals(epoch, catalog.launchEpoch); assertEquals(tree, catalog.runtimeTree?.toString())
        File(evidence, "$name-frame.json").writeText(JSONObject().put("scope", record.getString("scope")).put("phase", phase)
            .put("font_scale", context.resources.configuration.fontScale).put("launch_epoch", epoch)
            .put("ime_visible", ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true)
            .put("synchronization", "Committed semantics and two display frames; hierarchy before PNG; stable runtime tree").toString(2))
    }
    @After fun restore() {
        if (!prepared) return
        try {
            if (!record.optBoolean("passed") && ownedId != null) try { capture("failure") }
            catch (failure: Throwable) { record.put("failure_capture_error_type", failure.javaClass.simpleName) }
        } finally {
            try {
                compose.activityRule.scenario.onActivity { it.store.extensions.close(); it.store.dialog = null }
                restorePalettePreferences(context, palette)
                ownedIds.forEach { id ->
                    assertTrue(lease().getJSONArray("owned_extension_ids").let { ids -> (0 until ids.length()).any { ids.getString(it) == id } })
                    listOf(File(context.cacheDir, id), File(context.filesDir, "extension-data/$id")).forEach { it.deleteRecursively(); assertFalse(it.exists()) }
                    listOf(".xml", ".xml.bak").forEach { suffix -> File(context.applicationInfo.dataDir, "shared_prefs/extension-$id$suffix").let { it.delete(); assertFalse(it.exists()) } }
                }
                assertEquals(originalIds, catalog.installed.map { it.listing.id }.toSet())
                assertEquals(fingerprints, preserved.map(::fingerprint))
                record.put("owned_data_removed", true).put("preexisting_installations_and_data_preserved", true)
            } finally {
                try {
                    if (imageHistoryPropertyOwned) {
                        originalImageHistoryProperty?.let { System.setProperty(IMAGE_HISTORY_PROPERTY, it) }
                            ?: System.clearProperty(IMAGE_HISTORY_PROPERTY)
                        assertEquals(originalImageHistoryProperty, System.getProperty(IMAGE_HISTORY_PROPERTY))
                        imageHistoryPropertyOwned = false
                        record.put("image_history_process_property_restored", true)
                    }
                } finally {
                    File(evidence, "detail-${testName.methodName.lowercase(java.util.Locale.ROOT)}-record.json").writeText(record.toString(2))
                }
            }
        }
    }
    private fun show(text: String, substring: Boolean = false): SemanticsNodeInteraction {
        record.put("last_native_text_wait", text)
        compose.waitUntil(10000) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
        return reveal(compose.onNodeWithText(text, substring = substring))
    }
    private fun reveal(node: SemanticsNodeInteraction): SemanticsNodeInteraction {
        node.performScrollTo()
        // Compose1.7.6 stops at a code block's horizontal-only scroll owner.
        // Reveal through actual vertical ancestors too, keeping all assertions.
        val receipts = record.optJSONArray("axis_aware_scrolls") ?: JSONArray().also { record.put("axis_aware_scrolls", it) }
        for (attempt in 0 until 20) {
            compose.waitForIdle()
            val target = node.fetchSemanticsNode()
            val origin = compose.runOnUiThread { target.positionInRoot }
            val bounds = Rect(origin.x, origin.y, origin.x + target.size.width, origin.y + target.size.height)
            val ancestors = generateSequence(target.parent) { it.parent }.filter {
                SemanticsActions.ScrollBy in it.config && SemanticsProperties.VerticalScrollAxisRange in it.config
            }.toList()
            var moved = false
            for (ancestor in ancestors) {
                val viewport = ancestor.boundsInRoot
                if (viewport.height <= 0f) continue
                val top = bounds.top - viewport.top; val bottom = bounds.bottom - viewport.bottom
                val delta = if (top >= 0f && bottom >= 0f) minOf(top, bottom)
                    else if (top <= 0f && bottom <= 0f) maxOf(top, bottom) else 0f
                if (kotlin.math.abs(delta) < 1f) continue
                val range = ancestor.config[SemanticsProperties.VerticalScrollAxisRange]
                val action = ancestor.config[SemanticsActions.ScrollBy].action ?: continue
                val before = range.value()
                compose.runOnUiThread { action(0f, if (range.reverseScrolling) -delta else delta) }
                compose.waitForIdle()
                val after = range.value()
                receipts.put(JSONObject().put("attempt", attempt).put("ancestor_id", ancestor.id)
                    .put("requested_dy_px", delta.toDouble()).put("before", before.toDouble()).put("after", after.toDouble())
                    .put("viewport", JSONArray(listOf(viewport.left, viewport.top, viewport.right, viewport.bottom)))
                    .put("target", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))))
                if (kotlin.math.abs(after - before) >= 1f) { moved = true; break }
            }
            if (!moved) break
        }
        node.assertIsDisplayed(); return node
    }
    private fun ime(visible: Boolean) {
        compose.activityRule.scenario.onActivity {
            val controller = WindowCompat.getInsetsController(it.window, it.window.decorView)
            if (visible) controller.show(WindowInsetsCompat.Type.ime()) else controller.hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(5000) { (ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true) == visible }
    }
    private fun engineOrNull(): WebView? {
        var found: WebView? = null
        compose.activityRule.scenario.onActivity {
            fun visit(view: View) {
                if (view is WebView) { assertNull(found); found = view }
                if (view is ViewGroup) repeat(view.childCount) { index -> visit(view.getChildAt(index)) }
            }
            visit(it.window.decorView)
        }
        return found
    }
    private fun engine(): WebView = requireNotNull(engineOrNull())
    private fun evaluate(script: String): String {
        val done = CountDownLatch(1); var answer: String? = null; val current = engine()
        compose.activityRule.scenario.onActivity { current.evaluateJavascript(script) { answer = it; done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS)); return requireNotNull(answer)
    }
    private fun probe() = JSONObject(JSONArray("[${evaluate("JSON.stringify(globalThis.__ownedDetailRead())")}]").getString(0))
    private fun witness(phase: String, revision: Int) {
        val marker = "Detail publication $revision"
        compose.waitUntil(5000) { activeRoot(catalog.runtimeTree)?.props()?.optString("navigationTitle") == marker }
        compose.waitForIdle()
        val root = requireNotNull(activeRoot(catalog.runtimeTree))
        val node = compose.onAllNodes(hasText(marker, substring = true), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
        assertNotNull("Publication must reach native composition", node)
        val text = requireNotNull(node).config[SemanticsProperties.Text].joinToString("") { it.text }; assertTrue(text.contains(marker))
        val all = record.optJSONArray("publication_witnesses") ?: JSONArray().also { record.put("publication_witnesses", it) }
        all.put(JSONObject().put("phase", phase).put("counter", revision).put("root_id", root.optString("id"))
            .put("root_type", root.optString("type")).put("composed_marker_text", text).put("semantics_id", node.id))
    }
    private fun patch(phase: String, change: JSONObject) {
        val revision = probe().getInt("revision") + 1
        evaluate("globalThis.__ownedDetailPatch($change);true"); witness(phase, revision)
    }
    private fun touch(node: SemanticsNodeInteraction) { reveal(node).performTouchInput { click(center) }; compose.waitForIdle() }
    private fun touchTag(tag: String) = touch(compose.onNodeWithTag(tag))
    private fun backToResults() {
        if (compose.onAllNodesWithTag("extension-detail-back").fetchSemanticsNodes().isNotEmpty()) touchTag("extension-detail-back")
    }
    private fun tagCallback(row: String): () -> Boolean = requireNotNull(show("Owned tag $row").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
    private fun callStale(phase: String, callback: () -> Boolean) {
        val before = probe(); compose.runOnUiThread { callback() }; val after = probe()
        assertEquals("Stale tag dispatch at $phase", before.getInt("tagCalls"), after.getInt("tagCalls"))
        val receipts = record.optJSONArray("queued_callbacks") ?: JSONArray().also { record.put("queued_callbacks", it) }
        receipts.put(JSONObject().put("phase", phase).put("before", before).put("after", after).put("dispatch_rejected", true))
    }
    private fun clipboard(): String {
        var text = ""; compose.activityRule.scenario.onActivity {
            text = it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(it)?.toString().orEmpty()
        }; return text
    }
    @Test fun listTouchDetailPreservesQuerySelectionExactCopyAndCaller() {
        launch("list"); capture("list-entry")
        val keyboardButton = compose.onAllNodesWithContentDescription("Show on-screen keyboard")
        if (keyboardButton.fetchSemanticsNodes().isNotEmpty()) touch(keyboardButton[0]) else touchTag("extension-search")
        ime(true)
        val primary = compose.onNodeWithTag("extension-primary").fetchSemanticsNode()
        val imeGeometry = compose.runOnUiThread {
            val view = compose.activity.window.decorView
            val frame = android.graphics.Rect().also(view::getWindowVisibleDisplayFrame)
            val screen = IntArray(2).also(view::getLocationOnScreen); val window = IntArray(2).also(view::getLocationInWindow)
            primary.boundsInWindow to (frame.bottom - screen[1] + window[1])
        }
        record.put("list_search_actual_ime_open", true).put("primary_ime_geometry", JSONObject()
            .put("bottom", imeGeometry.first.bottom.toDouble()).put("visible_frame_bottom", imeGeometry.second))
        capture("list-search-ime-open")
        record.put("ime_toggle_contract_started", true)
        assertTrue("An open search keyboard needs a reachable Hide keyboard control",
            compose.onAllNodesWithContentDescription("Hide on-screen keyboard").fetchSemanticsNodes().isNotEmpty())
        touch(compose.onNodeWithContentDescription("Hide on-screen keyboard")); ime(false)
        compose.onNodeWithTag("extension-primary").assertIsDisplayed()
        capture("list-search-ime-closed")
        compose.onNodeWithTag("extension-search").performTextReplacement("beta"); compose.waitForIdle(); ime(false)
        touchTag("extension-row-0"); show("Owned document beta A"); show("Owned value beta A")
        capture("selected-detail"); assertEquals("beta", probe().getString("selected"))
        touchTag("extension-primary")
        compose.waitUntil(5000) { clipboard() == "Owned exact copy beta café é 日本 🌱" }
        dismissClipboard("exact-copy"); backToResults()
        compose.onNodeWithTag("extension-search").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("beta")))
        compose.onNodeWithTag("extension-row-0").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        capture("results-query-preserved"); touchTag("extension-back")
        assertNull(compose.activity.store.dialog); assertEquals(caller, compose.activity.store.query)
        record.put("exact_copy", "Owned exact copy beta café é 日本 🌱").put("query_selection_and_caller_preserved", true).put("passed", true)
        capture("caller-return")
    }
    @Test fun metadataTagActivatesByPointerAndUnsupportedLinkShowsAnOutcome() {
        launch("detail"); capture("metadata-entry")
        touch(show("Owned tag standalone")); witness("pointer-tag", 1)
        assertEquals(1, probe().getInt("tagCalls")); show("Owned tag count 1"); capture("tag-destination")
        touch(show("Owned link standalone"))
        compose.waitUntil(5000) { catalog.runtimeStatus == "This link is unavailable on Android" }
        assertFalse(probe().getBoolean("unexpectedLink")); assertEquals("Owned Markdown clipboard sentinel", clipboard())
        capture("unsupported-link-outcome"); record.put("pointer_tag_calls", 1).put("active_scheme_not_executed", true).put("passed", true)
    }
    @Test fun queuedTagCannotAdoptHiddenOtherChangedOrReplacedDocuments() {
        launch("list"); touchTag("extension-row-0"); val alpha = tagCallback("alpha")
        patch("detail-hidden", JSONObject().put("shown", false)); callStale("hidden-detail", alpha)
        patch("detail-visible", JSONObject().put("shown", true)); touchTag("extension-row-0"); val again = tagCallback("alpha")
        backToResults(); touchTag("extension-row-1"); show("Owned document beta A"); callStale("other-selected-row", again)
        val beta = tagCallback("beta"); val originalRoot = activeRoot(catalog.runtimeTree)!!.optString("id")
        patch("changed-document", JSONObject().put("document", "B")); show("Owned document beta B")
        assertEquals(originalRoot, activeRoot(catalog.runtimeTree)!!.optString("id")); callStale("same-row-new-document", beta)
        val changed = tagCallback("beta"); patch("replacement-root", JSONObject().put("rootKey", 1))
        assertNotEquals(originalRoot, activeRoot(catalog.runtimeTree)!!.optString("id")); callStale("replacement-root", changed)
        touchTag("extension-row-0"); val closing = tagCallback("alpha")
        compose.activityRule.scenario.onActivity { it.store.extensions.close(); assertTrue(closing()); it.store.dialog = null }
        // A successor command starts with its own WebView, owner and zero calls.
        launch("detail"); callStale("successor-session", closing)
        assertEquals(0, probe().getInt("tagCalls")); capture("successor-rejects-old-callback")
        record.put("passed", true)
    }
    private fun image(label: String) = compose.onNodeWithContentDescription(label, useUnmergedTree = true)
    private fun imageStatePublishedAndComposed(snapshot: JSONObject): Boolean {
        val events = snapshot.getJSONArray("events")
        fun last(stage: String) = (0 until events.length()).map { events.getJSONObject(it) }.lastOrNull { it.getString("stage") == stage }
        val written = last("state-ready") ?: return false
        val composed = last("composed") ?: return false
        return written.getBoolean("bitmap_ready") && composed.getBoolean("bitmap_ready") &&
            written.getInt("producer_generation") == snapshot.getInt("generation") &&
            written.getInt("value_token") == composed.getInt("value_token")
    }
    private fun observeImage(label: String, phase: String): JSONObject {
        val node = image(label).fetchSemanticsNode()
        val observations = record.optJSONArray("image_readiness") ?: JSONArray().also { record.put("image_readiness", it) }
        val debug = node.config.firstOrNull { it.key.name == "DetailImageLoadState" }?.value
        val snapshot = node.config.firstOrNull { it.key.name == "DetailImageLoadSnapshot" }?.value as? Function0<*>
        var loadSnapshot: String? = null
        val geometry = compose.runOnUiThread {
            loadSnapshot = snapshot?.invoke() as? String
            Triple(node.positionInRoot, compose.activity.lifecycle.currentState.name, debug?.toString() ?: "not available")
        }
        val svg = ownedId?.let { File(context.cacheDir, "$it/assets/owned.svg") }
        val observation = JSONObject().put("alternative", label).put("phase", phase)
            .put("state", node.config.getOrNull(SemanticsProperties.StateDescription)).put("activity_lifecycle", geometry.second)
            .put("debug_load_state", geometry.third)
            .put("load_snapshot", loadSnapshot?.let(::JSONObject) ?: JSONObject.NULL)
            .put("visible_bounds", JSONArray(listOf(node.boundsInRoot.left, node.boundsInRoot.top, node.boundsInRoot.right, node.boundsInRoot.bottom)))
            .put("unclipped_origin", JSONArray(listOf(geometry.first.x, geometry.first.y)))
            .put("size", JSONArray(listOf(node.size.width, node.size.height)))
            .put("owned_fixture_svg_assets_file_unchanged", svg?.isFile == true && hash(svg.readBytes()) == record.optString("owned_svg_sha256"))
        observations.put(observation)
        return observation
    }
    private fun readyImage(label: String) {
        // A root publication can precede the off-Main Markdown parse. Wait
        // for its actual image node before attempting any scroll action.
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        reveal(image(label)); observeImage(label, "before-wait")
        try { compose.waitUntil(10000) {
            val node = image(label).fetchSemanticsNode()
            if (node.config.getOrNull(SemanticsProperties.StateDescription) != "Image ready") false
            else {
                val snapshot = node.config.firstOrNull { it.key.name == "DetailImageLoadSnapshot" }?.value as? Function0<*>
                val encoded = compose.runOnUiThread { snapshot?.invoke() as? String }
                // Earlier frozen apps expose only native semantics. Preserve
                // that boundary rather than inventing a publication witness.
                encoded == null || imageStatePublishedAndComposed(JSONObject(encoded))
            }
        } }
        catch (failure: Throwable) { observeImage(label, "wait-failed"); throw failure }
        observeImage(label, "ready")
    }
    private fun imagePixel(label: String, expected: Int) {
        readyImage(label)
        val child = compose.onNode(hasTestTag("extension-detail-image-content") and hasAnyAncestor(hasContentDescription(label)), useUnmergedTree = true)
        val pixels = child.captureToImage().toPixelMap(); val actual = pixels[pixels.width / 2, pixels.height / 2]
        val color = androidx.compose.ui.graphics.Color(expected)
        assertEquals(color.red, actual.red, .02f); assertEquals(color.green, actual.green, .02f); assertEquals(color.blue, actual.blue, .02f)
        val receipts = record.optJSONArray("image_pixels") ?: JSONArray().also { record.put("image_pixels", it) }
        receipts.put(JSONObject().put("alternative", label).put("expected_argb", expected.toLong()).put("red", actual.red.toDouble())
            .put("green", actual.green.toDouble()).put("blue", actual.blue.toDouble()).put("width_px", pixels.width).put("height_px", pixels.height))
        val observation = observeImage(label, "pixels-captured")
        observation.optJSONObject("load_snapshot")?.let { snapshot ->
            val events = snapshot.getJSONArray("events")
            fun last(stage: String) = (0 until events.length()).map { events.getJSONObject(it) }.lastOrNull { it.getString("stage") == stage }
            val written = requireNotNull(last("state-ready")) { "Ready pixels need an actual state-publication witness" }
            val composed = requireNotNull(last("composed")) { "Ready pixels need an applied-composition witness" }
            val drawn = requireNotNull(last("draw-recorded")) { "Ready pixels need a draw-command witness" }
            assertTrue(written.getBoolean("bitmap_ready")); assertTrue(composed.getBoolean("bitmap_ready")); assertTrue(drawn.getBoolean("bitmap_ready"))
            assertEquals("Composition must consume the published value", written.getInt("value_token"), composed.getInt("value_token"))
            assertEquals("Draw must use the composed value", composed.getInt("value_token"), drawn.getInt("value_token"))
            assertEquals("The publication must belong to the current producer", snapshot.getInt("generation"), written.getInt("producer_generation"))
            observation.put("published_composed_drawn_value_and_pixels_agree", true)
        }
    }
    @Test fun packageSvgTintAndFailureImagesRenderWithBoundedAlternatives() {
        launch("images")
        imagePixel("Owned package image", 0xffe7582b.toInt()); capture("package-image")
        imagePixel("Owned SVG image", 0xff228833.toInt()); capture("svg-image")
        imagePixel("Owned tinted image", 0xffff0000.toInt()); capture("tinted-image")
        reveal(image("Owned unavailable image")); compose.waitUntil(5000) {
            image("Owned unavailable image").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription) == "Image unavailable"
        }
        show("Owned unavailable image"); show("Image unavailable"); capture("image-error-alternative")
        assertFalse(com.caverock.androidsvg.SVG.isInternalEntitiesEnabled())
        record.put("passed", true)
    }
    @Test fun metadataOnlyLoadingAndEmptyPublicationsHaveReachableStates() {
        launch("loading"); show("Loading details…"); compose.onNodeWithTag("extension-detail-loading").assertExists(); capture("loading")
        patch("empty-publication", JSONObject().put("contentMode", "empty").put("loading", false))
        show("No detail content"); compose.onNodeWithTag("extension-detail-loading").assertDoesNotExist(); capture("empty")
        patch("metadata-only-publication", JSONObject().put("contentMode", "metadata-only"))
        show("Owned value standalone A"); show("Owned tag standalone"); capture("metadata-only")
        assertTrue(compose.onAllNodesWithTag("extension-markdown").fetchSemanticsNodes().isEmpty())
        record.put("passed", true)
    }
    @Test fun recreationStartsAnewOwnerAndRejectsThePriorNativeCallback() {
        launch("detail"); val oldEngine = engine(); val oldCallback = tagCallback("standalone")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(25000) { engineOrNull()?.let { it !== oldEngine } == true && catalog.runtimeReady &&
            activeRoot(catalog.runtimeTree)?.props()?.optString("navigationTitle") == "Detail publication 0" }
        assertNull(catalog.runtimeError); witness("recreated", 0); ime(false)
        callStale("activity-recreation", oldCallback); touch(show("Owned tag standalone")); witness("fresh-owner-activation", 1)
        assertEquals(1, probe().getInt("tagCalls")); capture("recreated-fresh-owner")
        record.put("passed", true)
    }
    @Test fun sharedLinkPolicyNormalizesSupportedIntentsAndReportsHandlerFailure() {
        launch("detail")
        val intents = mutableListOf<Intent>()
        val receiver = object : ContextWrapper(context) { override fun startActivity(intent: Intent) { intents += intent } }
        for (target in listOf("HTTPS://example.invalid/owned", "http://example.invalid/owned", "mailto:owned@example.invalid", "geo:0,0?q=owned")) {
            openExtensionLink(receiver, target)
        }
        assertEquals(listOf("https", "http", "mailto", "geo"), intents.map { it.data!!.scheme })
        assertTrue(intents.all { it.action == Intent.ACTION_VIEW && it.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0 })
        val unavailable = object : ContextWrapper(context) { override fun startActivity(intent: Intent) { throw ActivityNotFoundException() } }
        try { openExtensionLink(unavailable, "https://example.invalid/private-owned"); fail("Missing handler was ignored") }
        catch (failure: IllegalStateException) { assertEquals("No app can open this link", failure.message) }
        for (target in listOf("javascript:action()", "data:text/html,owned", "file:///outside", "https://", "https://bad\n.example/")) {
            try { openExtensionLink(receiver, target); fail("Unsupported target accepted") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(4, intents.size); capture("link-policy-app-smoke")
        record.put("intent_boundary", "Owned ContextWrapper intercepts startActivity; no real external handler launch is claimed")
            .put("supported_intents", 4).put("passed", true)
    }
    private fun pointerSwipe(owner: SemanticsNodeInteraction, horizontal: Boolean) {
        val node = owner.fetchSemanticsNode(); val bounds = node.boundsInRoot
        val origin = compose.runOnUiThread { node.positionInRoot }
        assertTrue(bounds.width > 10f && bounds.height > 10f)
        val start = Offset(bounds.left + bounds.width * (if (horizontal) .85f else .5f) - origin.x,
            bounds.top + bounds.height * (if (horizontal) .5f else .85f) - origin.y)
        val end = Offset(bounds.left + bounds.width * (if (horizontal) .15f else .5f) - origin.x,
            bounds.top + bounds.height * (if (horizontal) .5f else .25f) - origin.y)
        owner.performTouchInput { swipe(start, end, 350) }; compose.waitForIdle()
    }
    private fun pointerParagraphLines() {
        val target = compose.onNodeWithText("Owned paragraph café", substring = true)
        val owner = compose.onNodeWithTag("extension-detail")
        val seen = mutableSetOf<Int>(); val receipts = JSONArray(); var lines = -1
        for (attempt in 0 until 40) {
            val node = target.fetchSemanticsNode(); val layouts = mutableListOf<TextLayoutResult>()
            compose.runOnUiThread { assertTrue(requireNotNull(node.config[SemanticsActions.GetTextLayoutResult].action)(layouts)) }
            val layout = layouts.single(); lines = layout.lineCount
            val origin = compose.runOnUiThread { node.positionInRoot }; val viewport = owner.fetchSemanticsNode().boundsInRoot
            for (line in 0 until lines) {
                assertFalse("Paragraph line was ellipsized", layout.isLineEllipsized(line))
                val top = origin.y + layout.getLineTop(line); val bottom = origin.y + layout.getLineBottom(line)
                val left = origin.x + layout.getLineLeft(line); val right = origin.x + layout.getLineRight(line)
                if (top >= viewport.top - 1f && bottom <= viewport.bottom + 1f && left >= viewport.left - 1f && right <= viewport.right + 1f && seen.add(line)) {
                    receipts.put(JSONObject().put("line", line).put("attempt", attempt)
                        .put("line_rect", JSONArray(listOf(left, top, right, bottom)))
                        .put("viewport", JSONArray(listOf(viewport.left, viewport.top, viewport.right, viewport.bottom))))
                }
            }
            if (attempt == 0) capture("paragraph-first-lines")
            if (seen.size == lines) break
            pointerSwipe(owner, false)
        }
        record.put("paragraph_line_witnesses", receipts).put("paragraph_natural_line_count", lines)
            .put("paragraph_reveal_method", "Real pointer swipes from initial document position; no semantic ScrollTo in the paragraph protocol")
        assertTrue(lines > 0); assertEquals("Every natural paragraph line must become fully visible", (0 until lines).toSet(), seen)
        capture("paragraph-final-lines")
    }
    private fun horizontalOwner(text: SemanticsNodeInteraction): SemanticsNodeInteraction? {
        val owner = generateSequence(text.fetchSemanticsNode().parent) { it.parent }
            .firstOrNull { SemanticsProperties.HorizontalScrollAxisRange in it.config } ?: return null
        return compose.onNode(SemanticsMatcher("Exact horizontal owner ${owner.id}") { it.id == owner.id }, useUnmergedTree = true)
    }
    @Test fun longParagraphCodeAndTablesRemainReachableByPointerAtNaturalTextSize() {
        launch("typography"); pointerParagraphLines()
        val code = reveal(compose.onNodeWithTag("extension-markdown-code"))
        val codeOwner = requireNotNull(horizontalOwner(code))
        val before = codeOwner.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
        pointerSwipe(codeOwner, true)
        val after = codeOwner.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
        assertTrue("Code must respond to horizontal pointer scrolling", after > before)
        capture("code-pointer-scroll")
        val firstCell = show("Owned cell one"); val table = requireNotNull(horizontalOwner(firstCell))
        val range = table.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        val tableBefore = range.value()
        if (range.maxValue() > 0f) {
            pointerSwipe(table, true)
            assertTrue(table.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value() > tableBefore)
        }
        show("Owned final cell 日本"); capture("table-final-column")
        record.put("code_scroll_before", before.toDouble()).put("code_scroll_after", after.toDouble())
            .put("table_overflowed", range.maxValue() > 0f).put("code_table_setup", "Axis-aware semantic reveal followed by real horizontal pointer gestures; final column uses semantic reveal")
            .put("passed", true)
    }
    @Test fun metadataLinkTextKeepsNaturalLinesInsideRoundedTouchTarget() {
        launch("detail")
        val label = compose.onNodeWithText("Owned link standalone", useUnmergedTree = true)
        reveal(label); capture("link-before-safe-area-assertions")
        record.put("contract_assertions_started", true)
        val probes = JSONArray(); record.put("link_line_safe_area", probes)
        for (text in listOf("Owned reference", "Owned link standalone")) {
            val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
            val button = requireNotNull(generateSequence(node.parent) { it.parent }.firstOrNull { SemanticsActions.OnClick in it.config })
            val origin = compose.runOnUiThread { node.positionInRoot }
            val buttonOrigin = compose.runOnUiThread { button.positionInRoot }
            val safe = 8f * context.resources.displayMetrics.density
            val layouts = mutableListOf<TextLayoutResult>()
            compose.runOnUiThread { assertTrue(requireNotNull(node.config[SemanticsActions.GetTextLayoutResult].action)(layouts)) }
            val layout = layouts.single()
            for (line in 0 until layout.lineCount) {
                val left = origin.x + layout.getLineLeft(line); val right = origin.x + layout.getLineRight(line)
                probes.put(JSONObject().put("text", text).put("line", line).put("left", left.toDouble()).put("right", right.toDouble())
                    .put("button_left", buttonOrigin.x.toDouble()).put("button_width", button.size.width).put("minimum_inset", safe.toDouble()))
                assertFalse(layout.isLineEllipsized(line))
                assertTrue("Link line must stay inside the rounded touch target's text safe area", left >= buttonOrigin.x + safe - 1f &&
                    right <= buttonOrigin.x + button.size.width - safe + 1f)
            }
        }
        capture("link-safe-area-verified"); record.put("passed", true)
    }
    @Test fun entityReferencesKeepParagraphFlowWithoutChangingHardBreaksOrCode() {
        launch("entity-lines"); capture("entity-before-contract-assertions")
        record.put("contract_assertions_started", true)
        assertTrue("Paragraph character references must not synthesize hard line breaks",
            compose.onAllNodesWithText("Owned entity foo  bar").fetchSemanticsNodes().isNotEmpty())
        show("Owned entity foo  bar"); capture("entity-paragraph-flow")
        show("Owned hard first\nOwned hard second"); capture("explicit-hard-lines")
        show("Owned code first\n\nOwned code third\n"); capture("exact-code-lines")
        record.put("paragraph_exact_text", "Owned entity foo  bar").put("hard_line_exact_text", "Owned hard first\nOwned hard second")
            .put("code_exact_text", "Owned code first\n\nOwned code third\n").put("passed", true)
    }
    @Test fun retainedMetadataIconUsesTheFreshPublishedImageSource() {
        launch("metadata-only"); val rootId = activeRoot(catalog.runtimeTree)!!.optString("id")
        fun icon(color: String): String = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(
            """<svg width="32" height="32" viewBox="0 0 32 32"><rect width="32" height="32" fill="$color"/></svg>""".toByteArray())
        patch("first-metadata-image", JSONObject().put("icon", icon("#228833")))
        imagePixel("Metadata icon", 0xff228833.toInt()); capture("metadata-green-image")
        patch("replacement-metadata-image", JSONObject().put("icon", icon("#ff0000")))
        assertEquals(rootId, activeRoot(catalog.runtimeTree)!!.optString("id"))
        assertEquals("", activeRoot(catalog.runtimeTree)!!.props().optString("markdown"))
        imagePixel("Metadata icon", 0xffff0000.toInt()); capture("metadata-red-image")
        record.put("same_root_and_empty_markdown_preserved", true)
            .put("image_transition_scope", "Fresh output pixels after witnessed publication and ready state; not a claim about every transient display frame")
            .put("passed", true)
    }
    private fun markdownLinkCallback(): () -> Unit {
        val text = show("Owned paragraph link").fetchSemanticsNode().config[SemanticsProperties.Text].single()
        val annotation = text.getLinkAnnotations(0, text.length).single().item as LinkAnnotation.Clickable
        val listener = requireNotNull(annotation.linkInteractionListener)
        return { listener.onClick(annotation) }
    }
    private fun touchMarkdownLink() {
        val target = show("Owned paragraph link"); val node = target.fetchSemanticsNode()
        val text = node.config[SemanticsProperties.Text].single()
        val range = text.getLinkAnnotations(0, text.length).single()
        val index = (range.start until range.end).first { !text.text[it].isWhitespace() }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.runOnUiThread { assertTrue(requireNotNull(node.config[SemanticsActions.GetTextLayoutResult].action)(layouts)) }
        val layout = layouts.single(); assertEquals(text, layout.layoutInput.text)
        val glyph = layout.getBoundingBox(index); val point = glyph.center
        val origin = compose.runOnUiThread { node.positionInRoot }
        val rootPoint = Offset(origin.x + point.x, origin.y + point.y)
        assertTrue("The actual linked glyph must be visible before its pointer tap", node.boundsInRoot.contains(rootPoint))
        record.put("markdown_link_pointer", JSONObject().put("range_start", range.start).put("range_end", range.end).put("glyph_index", index)
            .put("glyph_box", JSONArray(listOf(glyph.left, glyph.top, glyph.right, glyph.bottom)))
            .put("local_point", JSONArray(listOf(point.x, point.y))).put("root_point", JSONArray(listOf(rootPoint.x, rootPoint.y)))
            .put("clipped_text_bounds", JSONArray(listOf(node.boundsInRoot.left, node.boundsInRoot.top, node.boundsInRoot.right, node.boundsInRoot.bottom))))
        target.performTouchInput { click(point) }; compose.waitForIdle()
    }
    private fun structuredLinkCallback(): () -> Boolean = requireNotNull(show("Owned link standalone").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
    private fun staleLinks(phase: String, markdown: () -> Unit, metadata: () -> Boolean) {
        val marker = "Owned inactive link $phase"
        compose.runOnUiThread { catalog.runtimeStatus = marker; markdown(); metadata() }
        assertEquals(marker, catalog.runtimeStatus)
        val receipts = record.optJSONArray("stale_link_callbacks") ?: JSONArray().also { record.put("stale_link_callbacks", it) }
        receipts.put(JSONObject().put("phase", phase).put("markdown_and_metadata_rejected", true).put("unchanged_status", marker))
    }
    @Test fun markdownAndMetadataLinksRejectReplacedHiddenAndClosedOwners() {
        launch("links"); touchMarkdownLink()
        assertEquals("This link is unavailable on Android", catalog.runtimeStatus); capture("explicit-markdown-link-outcome")
        val originalMarkdown = markdownLinkCallback(); val originalMetadata = structuredLinkCallback()
        patch("new-document", JSONObject().put("document", "B")); staleLinks("changed-document", originalMarkdown, originalMetadata)
        val beforeSetupMarkdown = markdownLinkCallback(); val beforeSetupMetadata = structuredLinkCallback()
        touch(show("Preferences")); assertEquals("extension-setup", compose.activity.store.dialog)
        staleLinks("preferences-hidden", beforeSetupMarkdown, beforeSetupMetadata)
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack(); compose.waitForIdle()
        assertEquals("extension-run", compose.activity.store.dialog)
        staleLinks("reopened-slot", beforeSetupMarkdown, beforeSetupMetadata)
        val replacedMarkdown = markdownLinkCallback(); val replacedMetadata = structuredLinkCallback()
        val oldRoot = activeRoot(catalog.runtimeTree)!!.optString("id")
        patch("new-root", JSONObject().put("rootKey", 2)); assertNotEquals(oldRoot, activeRoot(catalog.runtimeTree)!!.optString("id"))
        staleLinks("replacement-root", replacedMarkdown, replacedMetadata)
        val closingMarkdown = markdownLinkCallback(); val closingMetadata = structuredLinkCallback()
        compose.activityRule.scenario.onActivity {
            it.store.extensions.close(); catalog.runtimeStatus = "Owned closed link"; closingMarkdown(); closingMetadata()
            assertEquals("Owned closed link", catalog.runtimeStatus); it.store.dialog = null
        }
        launch("links"); staleLinks("successor-session", closingMarkdown, closingMetadata)
        capture("successor-link-owner"); record.put("closed_before_compose_disposal_rejected", true).put("passed", true)
    }
    @Test fun frozenHtmlColorsRendersSvgMetadataCopiesAndReturns() {
        val archive = File(context.filesDir, "reviewed-text-fixture-lease/html-colors.zip")
        assertTrue("Use the guarded frozen HTML Colors archive", archive.isFile)
        val bytes = archive.readBytes()
        assertEquals("8a346fd2bd4ed01ceeacdeb71462b458014faa539a1f2b563638983843bdf0fb", hash(bytes))
        val directory = fixtureDirectory(); val id = directory.name
        val extracted = ExtensionArchive.extract(bytes, directory)
        val commandHash = "0271dcb698ac556e5973a4a2a412a1746e36e1f8f67994cd29b59a0338da3d67"
        assertEquals(commandHash, hash(File(extracted, "html-color.js").readBytes()))
        val extension = InstalledExtension(StoreExtension(id, "html-colors", "HTML Colors (owned frozen copy)", "", "Droidcast tests", "test", "", "", "", 1),
            extracted, JSONObject(File(extracted, "package.json").readText()))
        record.put("scope", "Unmodified frozen HTML Colors JavaScript in an owned namespace; default Blue search/detail/Copy path only")
            .put("archive_sha256", hash(bytes)).put("frozen_command_sha256", commandHash).put("owned_extension_id", id)
        compose.activityRule.scenario.onActivity { it.store.openExtension(extension, extension.commands.single { command -> command.name == "html-color" }) }
        compose.waitUntil(25000) { catalog.runtimeError != null || catalog.runtimeReady && activeRoot(catalog.runtimeTree)?.optString("type") == "List" }
        assertNull(catalog.runtimeError, catalog.runtimeError); compose.waitForIdle(); ime(false)
        assertEquals(false, activeRoot(catalog.runtimeTree)!!.props().opt("isShowingDetail"))
        compose.onNodeWithTag("extension-detail").assertDoesNotExist(); capture("html-colors-default-list")
        compose.onNodeWithTag("extension-search").performTextReplacement("Blue")
        compose.waitUntil(10000) { activeRoot(catalog.runtimeTree)?.let { root -> root.props().optString("searchText") == "Blue" &&
            root.nodes("List.Item").any { it.props().optString("title") == "Blue" } } == true }
        compose.waitForIdle(); ime(false)
        fun blue() = compose.onAllNodes(hasText("Blue") and hasAnyAncestor(hasTestTag("extension-list"))).onFirst()
        fun selectBlue() {
            compose.onNodeWithTag("extension-list").performScrollToNode(hasText("Blue"))
            touch(blue())
        }
        fun mode(title: String, expected: Boolean) {
            val root = requireNotNull(activeRoot(catalog.runtimeTree)); val row = root.nodes("List.Item").first { it.props().optString("title") == "Blue" }
            val scope = extensionActionScope(root, row, root.nodes("List.Item").size, "Blue")
            val index = scope.nodes.indexOfFirst { it.props().optString("title") == title }; assertTrue(index >= 0)
            touchTag("extension-actions"); compose.onNodeWithTag("inline-menu-search").performTextReplacement(title)
            touchTag("extension-action-$index")
            compose.waitUntil(5000) { activeRoot(catalog.runtimeTree)?.props()?.opt("isShowingDetail") == expected }; compose.waitForIdle()
        }
        fun metadataValue(text: String) {
            val target = compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("extension-detail-metadata")), useUnmergedTree = true)
            val node = reveal(target).fetchSemanticsNode(); val layouts = mutableListOf<TextLayoutResult>()
            compose.runOnUiThread { assertTrue(requireNotNull(node.config[SemanticsActions.GetTextLayoutResult].action)(layouts)) }
            val layout = layouts.single(); val origin = compose.runOnUiThread { node.positionInRoot }; val viewport = node.boundsInRoot
            assertTrue(node.size.height + 1 >= layout.multiParagraph.height)
            val receipts = record.optJSONArray("html_metadata_lines") ?: JSONArray().also { record.put("html_metadata_lines", it) }
            for (line in 0 until layout.lineCount) {
                assertFalse(layout.isLineEllipsized(line))
                val bounds = Rect(origin.x + layout.getLineLeft(line), origin.y + layout.getLineTop(line),
                    origin.x + layout.getLineRight(line), origin.y + layout.getLineBottom(line))
                receipts.put(JSONObject().put("text", text).put("line", line).put("line_bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                    .put("clipped_text_bounds", JSONArray(listOf(viewport.left, viewport.top, viewport.right, viewport.bottom))))
                assertTrue("Frozen metadata value must be completely readable", bounds.left >= viewport.left - 1f && bounds.right <= viewport.right + 1f &&
                    bounds.top >= viewport.top - 1f && bounds.bottom <= viewport.bottom + 1f)
            }
        }
        selectBlue(); mode("Show Details", true); selectBlue()
        imagePixel("Blue Color Preview", 0xff0000ff.toInt()); capture("html-colors-blue-svg")
        metadataValue("#0000FF"); capture("html-colors-hex-metadata")
        metadataValue("rgb(0, 0, 255)"); capture("html-colors-rgb-metadata")
        mode("Hide Details", false); compose.onNodeWithTag("extension-detail").assertDoesNotExist(); capture("html-colors-hidden-detail")
        mode("Show Details", true); selectBlue(); imagePixel("Blue Color Preview", 0xff0000ff.toInt())
        assertEquals("Owned Markdown clipboard sentinel", clipboard()); touchTag("extension-primary")
        compose.waitUntil(10000) { clipboard() == "#0000FF" }; dismissClipboard("html-colors-copy"); capture("html-colors-copied-hex")
        backToResults(); compose.onNodeWithTag("extension-search").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Blue")))
        touchTag("extension-back"); assertNull(compose.activity.store.dialog); assertEquals(caller, compose.activity.store.query)
        record.put("phase_outcomes", JSONObject().put("startup", "working").put("rendering", "Blue SVG and metadata working")
            .put("interaction", "search, select, detail toggle, Copy HEX working").put("destination", "exact #0000FF clipboard and caller return")
            .put("configuration", "no preferences or arguments required for this path").put("service", "not exercised; static-bundle path"))
            .put("overall_command", "partial").put("remaining_scope", "List dropdown/accessory modes, other colors/actions and complete command parity are untested")
            .put("passed", true)
        capture("html-colors-caller-return")
    }
    @Test fun emptyMetadataHasReadableValuesAndAnUnavailableLink() {
        launch("empty-metadata"); capture("empty-metadata-before-contract")
        record.put("contract_assertions_started", true)
        assertTrue("Empty metadata value needs a readable alternative", compose.onAllNodesWithText("Not provided").fetchSemanticsNodes().isNotEmpty())
        show("Not provided"); show("Link unavailable").assertIsNotEnabled(); show("No tags")
        capture("empty-metadata-alternatives"); record.put("passed", true)
    }
    @Test fun galleryRendersNativeListsQuotesEscapesAndCombinedMarks() {
        launch("gallery")
        show("Owned gallery heading").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        val text = show("Escaped *literal*", substring = true).fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertEquals("Escaped *literal* and bold nested with removed.", text.text)
        assertTrue(text.spanStyles.any { it.item.fontStyle == androidx.compose.ui.text.font.FontStyle.Italic &&
            (it.item.fontWeight?.weight ?: 0) >= 600 && text.text.substring(it.start, it.end) == "nested" })
        assertTrue(text.spanStyles.any { it.item.textDecoration == androidx.compose.ui.text.style.TextDecoration.LineThrough &&
            text.text.substring(it.start, it.end) == "removed" })
        show("3."); show("Owned ordered item"); show("Owned nested item"); capture("native-lists")
        show("Owned quote café 日本"); show("Owned second quotation"); capture("native-quotations")
        show("Owned secondary heading").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        imagePixel("Owned table image", 0xff228833.toInt()); show("Owned table image neighbor"); capture("native-table-image")
        show("Owned gallery final paragraph."); capture("native-gallery-destination")
        record.put("native_lists_quotes_escapes_marks_verified", true).put("passed", true)
    }
    @Test fun defaultImagesPaintWithoutExtendedHistory() {
        val requested = System.getProperty(IMAGE_HISTORY_PROPERTY)
        try {
            System.clearProperty(IMAGE_HISTORY_PROPERTY)
            launch("image-information"); imagePixel("Owned block image", 0xff228833.toInt())
            assertTrue("Ordinary images expose no extended history probe",
                image("Owned block image").fetchSemanticsNode().config.none { it.key.name == "DetailImageLoadSnapshot" })
            capture("default-image-smoke")
            record.put("default_image_pixels_verified_without_extended_history", true).put("passed", true)
        } finally {
            requested?.let { System.setProperty(IMAGE_HISTORY_PROPERTY, it) } ?: System.clearProperty(IMAGE_HISTORY_PROPERTY)
        }
    }
    @Test fun imageTitlesHaveTouchInformationAndCannotSurviveAnewDocument() {
        launch("image-information"); imagePixel("Owned block image", 0xff228833.toInt()); capture("image-information-before-contract")
        record.put("contract_assertions_started", true)
        assertTrue("Markdown image title needs a touch-readable equivalent",
            compose.onAllNodesWithText("Owned block description A café 日本").fetchSemanticsNodes().isNotEmpty())
        show("Owned block description A café 日本"); capture("image-block-description")
        readyImage("Owned inline image"); image("Owned inline image").performTouchInput { longClick(center) }
        compose.onNodeWithTag("extension-image-information").assertIsDisplayed()
        val focusedDocumentParents = generateSequence(image("Owned inline image").fetchSemanticsNode().parent) { it.parent }
            .filter { it.config.getOrNull(SemanticsProperties.Focused) == true }.map {
                JSONObject().put("id", it.id).put("test_tag", it.config.getOrNull(SemanticsProperties.TestTag) ?: JSONObject.NULL)
            }.toList()
        record.put("information_focused_ancestor_witnesses", JSONArray(focusedDocumentParents))
        // Pinned Compose 1.7.6 exposes this semantic key on its actual handle
        // Popups. Palette-frame focus may legitimately be restored underneath.
        val selectionHandles = compose.onAllNodes(SemanticsMatcher("Native text selection handle") { node ->
            node.config.any { it.key.name == "SelectionHandleInfo" }
        }, useUnmergedTree = true).fetchSemanticsNodes().size
        record.put("information_selection_handles", selectionHandles)
        assertEquals("Image information must release document selection handles", 0, selectionHandles)
        compose.onNodeWithText("Owned inline description A café 日本").assertIsDisplayed(); capture("inline-information-long-press")
        val oldClose = requireNotNull(compose.onNodeWithTag("extension-image-information-close").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        patch("image-information-new-document", JSONObject().put("document", "B"))
        compose.onNodeWithTag("extension-image-information").assertDoesNotExist()
        readyImage("Owned inline image"); image("Owned inline image").performTouchInput { longClick(center) }
        compose.onNodeWithText("Owned inline description B café 日本").assertIsDisplayed()
        compose.runOnUiThread { assertTrue(oldClose()) }
        compose.onNodeWithTag("extension-image-information").assertIsDisplayed(); capture("new-information-owner")
        touchTag("extension-image-information-close"); compose.onNodeWithTag("extension-image-information").assertDoesNotExist()
        readyImage("Owned linked image"); image("Owned linked image").performTouchInput { click(center) }
        compose.waitUntil(5000) { catalog.runtimeStatus == "This link is unavailable on Android" }
        capture("linked-image-explicit-activation")
        record.put("long_press_opens_information", true).put("old_close_cannot_dismiss_successor", true)
            .put("linked_inline_image_pointer_activation_preserved", true).put("passed", true)
    }
    @Test fun focusedMetadataReceivesEnterWithoutActivatingThePrimaryAction() {
        launch("detail"); val tag = show("Owned tag standalone")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var tabs = 0
        while (tag.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) != true && tabs < 30) {
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_TAB); compose.waitForIdle(); tabs++
        }
        assertTrue("The metadata tag must own actual keyboard focus", tag.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true)
        record.put("keyboard_tab_count", tabs).put("contract_assertions_started", true)
        capture("metadata-keyboard-focus")
        val before = probe(); assertEquals("Owned Markdown clipboard sentinel", clipboard())
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER); compose.waitForIdle()
        val after = probe()
        val message = "Focused metadata must handle Enter without activating the primary action"
        assertEquals(message, before.getInt("tagCalls") + 1, after.getInt("tagCalls"))
        assertEquals(message, "Owned Markdown clipboard sentinel", clipboard())
        witness("metadata-keyboard-enter", before.getInt("revision") + 1)
        show("Owned tag count 1"); capture("metadata-keyboard-activation")
        record.put("actual_tab_focus_and_enter", true).put("primary_copy_not_invoked", true).put("passed", true)
    }
    private fun assertVisibleEdgeGlyphs(node: androidx.compose.ui.semantics.SemanticsNode, layout: TextLayoutResult, line: Int, phase: String) {
        val origin = compose.runOnUiThread { node.positionInRoot }
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val actual = compose.onRoot().captureToImage().toPixelMap()
        val reference = Bitmap.createBitmap(layout.size.width.coerceAtLeast(1), layout.size.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val ink = layout.layoutInput.style.color.takeUnless { it == androidx.compose.ui.graphics.Color.Unspecified } ?: PaletteTheme.ink
        val expectedRed = ink.red * ink.alpha + PaletteTheme.surface.red * (1 - ink.alpha)
        val expectedGreen = ink.green * ink.alpha + PaletteTheme.surface.green * (1 - ink.alpha)
        val expectedBlue = ink.blue * ink.alpha + PaletteTheme.surface.blue * (1 - ink.alpha)
        try {
            // Paint the actual resolved paragraph without ancestor clipping. This
            // is a reference alpha mask, not an implementation screenshot.
            compose.runOnUiThread { layout.multiParagraph.paint(androidx.compose.ui.graphics.Canvas(android.graphics.Canvas(reference)),
                color = androidx.compose.ui.graphics.Color.White, decoration = layout.layoutInput.style.textDecoration) }
            val start = layout.getLineStart(line); val end = layout.getLineEnd(line, visibleEnd = true)
            val offsets = listOf(start, end - 1).filter { it in start until end && !layout.layoutInput.text[it].isWhitespace() }.distinct()
            for (offset in offsets) {
                val box = layout.getBoundingBox(offset)
                var expected = 0; var missing = 0
                for (y in kotlin.math.floor(box.top).toInt().coerceAtLeast(0) until kotlin.math.ceil(box.bottom).toInt().coerceAtMost(reference.height))
                    for (x in kotlin.math.floor(box.left).toInt().coerceAtLeast(0) until kotlin.math.ceil(box.right).toInt().coerceAtMost(reference.width)) {
                        if (android.graphics.Color.alpha(reference.getPixel(x, y)) < 200) continue
                        expected++
                        val px = kotlin.math.round(origin.x + x - root.left).toInt()
                        val py = kotlin.math.round(origin.y + y - root.top).toInt()
                        val found = (-1..1).any { dy -> (-1..1).any { dx ->
                            val ax = px + dx; val ay = py + dy
                            ax in 0 until actual.width && ay in 0 until actual.height && actual[ax, ay].let {
                                kotlin.math.abs(it.red - expectedRed) < .16f && kotlin.math.abs(it.green - expectedGreen) < .16f && kotlin.math.abs(it.blue - expectedBlue) < .16f
                            }
                        } }
                        if (!found) missing++
                    }
                val receipts = record.optJSONArray("native_text_edge_glyph_pixels") ?: JSONArray().also { record.put("native_text_edge_glyph_pixels", it) }
                receipts.put(JSONObject().put("phase", phase).put("line", line).put("character_offset", offset).put("expected_core_pixels", expected)
                    .put("missing_core_pixels", missing).put("alignment_neighborhood_px", 1)
                    .put("expected_rgb", JSONArray(listOf(expectedRed, expectedGreen, expectedBlue)))
                    .put("method", "Actual root capture versus unclipped resolved paragraph alpha mask; first/last visible glyph"))
                assertTrue("Every edge-glyph probe must contain reference ink", expected > 0)
                assertEquals("Visible metadata link glyphs must survive ancestor clipping (line $line, offset $offset)", 0, missing)
            }
        } finally { reference.recycle() }
    }
    private fun pointerEveryTextLine(prefix: String, phase: String, verifyGlyphs: Boolean = false) {
        val target = compose.onNodeWithText(prefix, substring = true, useUnmergedTree = true)
        val ancestor = generateSequence(target.fetchSemanticsNode().parent) { it.parent }
            .first { SemanticsProperties.VerticalScrollAxisRange in it.config && SemanticsActions.ScrollBy in it.config }
        val owner = compose.onNode(SemanticsMatcher("Exact long-text scroll owner ${ancestor.id}") { it.id == ancestor.id }, useUnmergedTree = true)
        // Reset only the setup position, then witness every complete natural line through pointer swipes.
        compose.runOnUiThread { requireNotNull(ancestor.config[SemanticsActions.ScrollBy].action)(0f, -ancestor.config[SemanticsProperties.VerticalScrollAxisRange].value()) }
        compose.waitForIdle()
        val seen = mutableSetOf<Int>(); val receipts = JSONArray(); var total = 0
        for (attempt in 0 until 80) {
            val node = target.fetchSemanticsNode(); val layouts = mutableListOf<TextLayoutResult>()
            compose.runOnUiThread { assertTrue(requireNotNull(node.config[SemanticsActions.GetTextLayoutResult].action)(layouts)) }
            val layout = layouts.single(); total = layout.lineCount
            val origin = compose.runOnUiThread { node.positionInRoot }; val viewport = owner.fetchSemanticsNode().boundsInRoot
            for (line in 0 until total) {
                assertFalse(layout.isLineEllipsized(line))
                val top = origin.y + layout.getLineTop(line); val bottom = origin.y + layout.getLineBottom(line)
                val left = origin.x + layout.getLineLeft(line); val right = origin.x + layout.getLineRight(line)
                if (top >= viewport.top - 1f && bottom <= viewport.bottom + 1f && left >= viewport.left - 1f && right <= viewport.right + 1f && seen.add(line)) {
                    if (verifyGlyphs) assertVisibleEdgeGlyphs(node, layout, line, phase)
                    receipts.put(JSONObject().put("line", line).put("attempt", attempt).put("line_rect", JSONArray(listOf(left, top, right, bottom)))
                        .put("viewport", JSONArray(listOf(viewport.left, viewport.top, viewport.right, viewport.bottom))))
                }
            }
            if (attempt == 0) capture("$phase-first-lines")
            if (seen.size == total) break
            pointerSwipe(owner, false)
        }
        record.put("$phase-line-witnesses", receipts).put("$phase-natural-lines", total)
        assertTrue(total > 0); assertEquals("Every $phase natural line must become fully visible", (0 until total).toSet(), seen)
        capture("$phase-last-lines")
    }
    @Test fun longMetadataUrlsTagsAndImageInformationRemainReachableByTouch() {
        launch("long-metadata"); show("Owned long metadata café", substring = true)
        pointerEveryTextLine("Owned long metadata title", "metadata-title", verifyGlyphs = true)
        pointerEveryTextLine("Owned long metadata café", "metadata-value")
        pointerEveryTextLine("https://example.invalid/owned/", "metadata-url", verifyGlyphs = true)
        touch(show("Owned wrapping tag 3 café 日本")); witness("long-metadata-tag", 1); assertEquals(1, probe().getInt("tagCalls"))
        capture("wrapping-tags-destination")
        patch("long-information-publication", JSONObject().put("contentMode", "long-information"))
        readyImage("Owned long information image"); image("Owned long information image").performTouchInput { longClick(center) }
        compose.onNodeWithTag("extension-image-information").assertIsDisplayed()
        pointerEveryTextLine("Owned long image information café", "image-information")
        touch(show("Close")); compose.onNodeWithTag("extension-image-information").assertDoesNotExist()
        record.put("long_metadata_url_and_image_information_pointer_reachability", true).put("passed", true)
    }
    @Test fun offscreenBlockImageRetainsGeometryAndReloadsOnReturn() {
        launch("image-scroll"); imagePixel("Owned scrolling image", 0xff228833.toInt())
        val initialHeight = image("Owned scrolling image").fetchSemanticsNode().size.height
        capture("block-ready-before-scroll")
        show("Owned scroll-away destination.")
        compose.waitUntil(5000) { image("Owned scrolling image").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription) == "Image loading" }
        val offscreen = image("Owned scrolling image").fetchSemanticsNode()
        assertEquals("The fixture must put the block completely offscreen", 0f, offscreen.boundsInRoot.height, .01f)
        record.put("offscreen_block_initial_height_px", initialHeight).put("offscreen_block_height_px", offscreen.size.height)
            .put("offscreen_view_bitmap_cleared", true).put("offscreen_geometry_contract_started", true)
        capture("block-offscreen-geometry")
        assertEquals("Offscreen block images must retain their layout height", initialHeight, offscreen.size.height)
        imagePixel("Owned scrolling image", 0xff228833.toInt())
        assertEquals(initialHeight, image("Owned scrolling image").fetchSemanticsNode().size.height)
        capture("block-returned-pixels")
        record.put("offscreen_geometry_and_returned_pixels_verified", true).put("passed", true)
    }
    @Test fun cachedBlockImageReturnsItsPixelsAfterRepeatedVisibilityChanges() {
        launch("image-scroll-information"); imagePixel("Owned scrolling image", 0xff228833.toInt())
        val height = image("Owned scrolling image").fetchSemanticsNode().size.height
        val cycles = JSONArray(); record.put("cached_image_visibility_cycles", cycles)
        repeat(6) { cycle ->
            show("Owned scroll-away destination.")
            compose.waitUntil(5000) {
                val node = image("Owned scrolling image").fetchSemanticsNode()
                node.boundsInRoot.height == 0f &&
                    node.config.getOrNull(SemanticsProperties.StateDescription) == "Image loading"
            }
            val hidden = image("Owned scrolling image").fetchSemanticsNode()
            val receipt = JSONObject().put("cycle", cycle).put("hidden_height_px", hidden.size.height)
                .put("hidden_visible_height_px", hidden.boundsInRoot.height)
            cycles.put(receipt)
            assertEquals("Cached image release must retain scroll geometry", height, hidden.size.height)
            imagePixel("Owned scrolling image", 0xff228833.toInt())
            val returned = image("Owned scrolling image").fetchSemanticsNode()
            receipt.put("returned_height_px", returned.size.height)
                .put("returned_state", returned.config.getOrNull(SemanticsProperties.StateDescription))
            assertEquals(height, returned.size.height)
        }
        capture("cached-image-sixth-return")
        record.put("cached_image_visibility_scope", "Six observed offscreen bitmap releases and visible green-pixel returns with unchanged document/source; semantic scroll positioning is setup")
            .put("passed", true)
    }
    @Test fun compactPaddedTableBecomesUnavailableAndTheNextDocumentStillWorks() {
        launch("table-limit"); show("Owned table limit sentinel"); capture("before-table-expansion")
        record.put("table_limit_source_units", 8008).put("table_limit_columns", 1001).put("table_limit_body_rows", 1000)
            .put("contract_assertions_started", true)
        // Durable before the old parser can abort the preview effect/process.
        File(evidence, "detail-${testName.methodName.lowercase(java.util.Locale.ROOT)}-record.json").writeText(record.toString(2))
        patch("table-allocation-limit", JSONObject().put("contentMode", "table-overflow"))
        show("This document is too large to preview."); compose.onNodeWithTag("extension-markdown-unavailable").assertIsDisplayed()
        assertNull(catalog.runtimeError); capture("table-unavailable")
        patch("normal-after-rejection", JSONObject().put("contentMode", "detail"))
        show("Owned document standalone A"); touch(show("Owned tag standalone")); witness("normal-tag-after-limit", 3)
        assertEquals(1, probe().getInt("tagCalls")); capture("normal-document-recovered")
        record.put("parser_limit_is_readable_and_next_document_works", true).put("passed", true)
    }
    @Test fun unchangedObjectTagCallbackSurvivesEquivalentNativePublication() {
        launch("object-tag"); val tag = show("Owned object tag")
        val callback = requireNotNull(tag.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val original = requireNotNull(catalog.runtimeTree); val replacement = JSONObject(original.toString())
        val before = objectTagBinding()
        assertNotSame(original, replacement)
        record.put("contract_assertions_started", true).put("object_tag_publication_scope", "Controlled equivalent native JSON replacement before recomposition; actual unchanged JavaScript callback dispatch")
        capture("object-tag-before-publication")
        compose.runOnUiThread {
            catalog.runtimeTree = replacement
            val after = objectTagBinding()
            record.put("object_tag_binding_before", before).put("object_tag_binding_after", after)
            assertEquals("Controlled publication preserves document, node, handle and visual values", before.toString(), after.toString())
            assertTrue(callback())
        }
        assertEquals("Equivalent object-valued visuals must not invalidate the visible callback", 1, probe().getInt("tagCalls"))
        witness("object-tag-after-equivalent-native-tree", 1); capture("object-tag-current-callback")
        patch("object-tag-visual-replaced", JSONObject().put("objectTagVariant", "B"))
        callStale("object-tag-real-visual-change", callback)
        touch(show("Owned object tag")); witness("object-tag-new-visual-action", 3)
        assertEquals(2, probe().getInt("tagCalls")); capture("object-tag-new-visual-owner")
        record.put("object_tag_unchanged_callback_preserved_and_changed_visual_rejected", true).put("passed", true)
    }
    private fun objectTagBinding(): JSONObject {
        val root = requireNotNull(activeRoot(catalog.runtimeTree))
        val tag = root.props().getJSONObject("metadata").nodes("Detail.Metadata.TagList.Item").single()
        return JSONObject().put("root_id", root.optString("id")).put("tag_id", tag.optString("id"))
            .put("markdown", root.props().getString("markdown")).put("handle", requireNotNull(tag.handle("onAction")))
            .put("icon", tag.props().getJSONObject("icon")).put("color", tag.props().getJSONObject("color"))
    }
    @Test fun objectTagKeepsActualKeyboardFocusAcrossUnrelatedReactPublication() {
        launch("object-tag"); val tag = show("Owned object tag")
        val instrumentation = InstrumentationRegistry.getInstrumentation(); var tabs = 0
        while (tag.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) != true && tabs < 30) {
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_TAB); compose.waitForIdle(); tabs++
        }
        assertTrue(tag.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true)
        val beforeId = tag.fetchSemanticsNode().id
        val beforeBinding = objectTagBinding()
        record.put("contract_assertions_started", true).put("object_tag_focus_before_id", beforeId).put("keyboard_tab_count", tabs)
        capture("object-tag-keyboard-before-publication")
        patch("object-tag-unrelated-loading-publication", JSONObject().put("loading", true))
        val afterBinding = objectTagBinding()
        record.put("object_tag_binding_before", beforeBinding).put("object_tag_binding_after", afterBinding)
        assertEquals("Unrelated publication preserves document, node, handle and visual values", beforeBinding.toString(), afterBinding.toString())
        val after = tag.fetchSemanticsNode()
        record.put("object_tag_focus_after_id", after.id)
        assertEquals("An unchanged object-valued tag keeps its composed control", beforeId, after.id)
        assertTrue("Unrelated React publication keeps actual tag focus", after.config.getOrNull(SemanticsProperties.Focused) == true)
        capture("object-tag-keyboard-after-publication")
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER); compose.waitForIdle()
        assertEquals(1, probe().getInt("tagCalls")); witness("object-tag-enter-after-publication", 2)
        assertEquals("Owned Markdown clipboard sentinel", clipboard()); capture("object-tag-enter-destination")
        record.put("object_tag_keyboard_focus_and_current_enter_preserved", true).put("primary_copy_not_invoked", true).put("passed", true)
    }
}
