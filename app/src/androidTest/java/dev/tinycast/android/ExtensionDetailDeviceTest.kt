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
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val testName = org.junit.rules.TestName()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val catalog get() = compose.activity.store.extensions
    private val leaseFile get() = File(context.filesDir, "reviewed-text-fixture-lease/owners.json")
    private val evidence get() = File(context.filesDir, "reviewed-text-evidence").apply { mkdirs() }
    private val record = JSONObject().put("scope", "Owned React Markdown/metadata through the real runtime and native UI; not Store or service certification")
    private var prepared = false
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
    private fun launch(mode: String) {
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
        val configuration = JSONObject().put("mode", mode).put("images", images).put("typography", typography)
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
                File(evidence, "detail-${testName.methodName.lowercase(java.util.Locale.ROOT)}-record.json").writeText(record.toString(2))
            }
        }
    }
    private fun show(text: String, substring: Boolean = false): SemanticsNodeInteraction {
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
    private fun readyImage(label: String) {
        reveal(image(label)); compose.waitUntil(10000) { image(label).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription) == "Image ready" }
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
}
