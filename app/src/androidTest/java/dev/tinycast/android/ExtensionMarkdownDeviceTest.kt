package dev.tinycast.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.geometry.Rect
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

/** Pre-feature production APIs only, so identical instrumentation can test old/new apps. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class ExtensionMarkdownDeviceTest {
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
    private var palette = emptyMap<String, Any?>()
    private var originalIds = emptySet<String>()
    private var preserved = emptyList<File>()
    private var fingerprints = emptyList<Map<String, String>>()
    private val caller = "markdown-owned-caller"
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
        ownedId = id; assertTrue(directory.mkdir())
        val script = """
            const React=require('react'), api=require('@raycast/api');
            const h=React.createElement, mode=${JSONObject.quote(mode)}, markdown=${JSONObject.quote(markdown)};
            module.exports.default=function OwnedMarkdown(){
              const [count,setCount]=React.useState(0);
              const [shown,setShown]=React.useState(mode==='list-true');
              const actions=content=>h(api.ActionPanel,null,
                h(api.Action.CopyToClipboard,{title:'Copy owned Markdown',content}),
                h(api.Action,{title:'Toggle owned detail',onAction:()=>setShown(value=>!value)}));
              if(mode.startsWith('list-')){
                const props={navigationTitle:'Owned visibility',actions:actions('Owned list copy')};
                if(mode!=='list-omitted')props.isShowingDetail=shown;
                return h(api.List,props,
                  h(api.List.Item,{id:'owned-alpha',title:'Owned alpha',detail:h(api.List.Item.Detail,{markdown:'Owned hidden detail alpha'}),actions:actions('Owned alpha copy')}),
                  h(api.List.Item,{id:'owned-beta',title:'Owned beta',detail:h(api.List.Item.Detail,{markdown:'Owned hidden detail beta'}),actions:actions('Owned beta copy')}));
              }
              const M=api.Detail.Metadata;
              const metadata=mode==='metadata'?h(M,null,
                h(M.Label,{title:'Owned label',text:'Owned value café 日本'}),
                h(M.Link,{title:'Owned reference',text:'Owned documentation',target:'https://example.invalid/owned'}),
                h(M.Separator),
                h(M.TagList,{title:'Owned tags'},h(M.TagList.Item,{text:'Owned tag 🌱',color:'#228833',onAction:()=>setCount(value=>value+1)})),
                h(M.Label,{title:'Owned activation',text:'Owned action count '+count})):undefined;
              return h(api.Detail,{navigationTitle:'Owned content',markdown:mode==='metadata'?'Owned metadata fixture':markdown,
                metadata,actions:actions(markdown)});
            };
        """.trimIndent()
        File(directory, "markdown.js").writeText(script)
        val manifest = JSONObject("""{"commands":[{"name":"markdown","title":"Owned Markdown controls","mode":"view"}]}""")
        val extension = InstalledExtension(StoreExtension(id, "markdown-fixture", "Owned Markdown probe", "", "Droidcast tests", "test", "", "", "", 1), directory, manifest)
        record.put("fixture_mode", mode).put("fixture_command_sha256", hash(script.toByteArray(Charsets.UTF_8)))
            .put("fixture_manifest_sha256", hash(manifest.toString().toByteArray(Charsets.UTF_8))).put("owned_extension_id", id)
        compose.activityRule.scenario.onActivity { it.store.openExtension(extension, extension.commands.single()) }
        val expected = if (mode.startsWith("list-")) "List" else "Detail"
        compose.waitUntil(25000) { catalog.runtimeError != null || (catalog.runtimeReady && activeRoot(catalog.runtimeTree)?.optString("type") == expected) }
        assertNull(catalog.runtimeError, catalog.runtimeError); compose.waitForIdle()
        compose.activityRule.scenario.onActivity { WindowCompat.getInsetsController(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
        compose.waitUntil(5000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) != true }
        record.put("root_type", expected).put("input_boundary", "Owned React runtime; baseline view captured with Activity IME explicitly hidden")
    }
    private fun capture(phase: String) {
        compose.waitForIdle(); val tree = catalog.runtimeTree?.toString(); val epoch = catalog.launchEpoch
        val frames = CountDownLatch(1)
        compose.activityRule.scenario.onActivity { it.window.decorView.postOnAnimation { it.window.decorView.postOnAnimation { frames.countDown() } } }
        assertTrue(frames.await(5, TimeUnit.SECONDS)); compose.waitForIdle()
        assertEquals(epoch, catalog.launchEpoch); assertEquals(tree, catalog.runtimeTree?.toString())
        val name = "markdown-${testName.methodName.lowercase(java.util.Locale.ROOT)}-$phase"
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
                ownedId?.let { id ->
                    assertTrue(lease().getJSONArray("owned_extension_ids").let { ids -> (0 until ids.length()).any { ids.getString(it) == id } })
                    listOf(File(context.cacheDir, id), File(context.filesDir, "extension-data/$id")).forEach { it.deleteRecursively(); assertFalse(it.exists()) }
                    listOf(".xml", ".xml.bak").forEach { suffix -> File(context.applicationInfo.dataDir, "shared_prefs/extension-$id$suffix").let { it.delete(); assertFalse(it.exists()) } }
                }
                assertEquals(originalIds, catalog.installed.map { it.listing.id }.toSet())
                assertEquals(fingerprints, preserved.map(::fingerprint))
                record.put("owned_data_removed", true).put("preexisting_installations_and_data_preserved", true)
            } finally {
                File(evidence, "markdown-${testName.methodName.lowercase(java.util.Locale.ROOT)}-record.json").writeText(record.toString(2))
            }
        }
    }
    private fun show(text: String, substring: Boolean = false): SemanticsNodeInteraction {
        val node = compose.onNodeWithText(text, substring = substring)
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
    @Test fun commonMarkHeadingEmphasisAndCodeRenderAsNativeText() {
        launch("markdown"); capture("before-contract-assertions")
        record.put("contract_assertions_started", true)
        assertTrue("CommonMark must expose a native heading without raw syntax", compose.onAllNodesWithText("Owned heading").fetchSemanticsNodes().isNotEmpty())
        show("Owned heading").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        val paragraph = show("Bold value", substring = true).fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertFalse(paragraph.text.contains("**"))
        assertTrue(paragraph.spanStyles.any { (it.item.fontWeight?.weight ?: 0) >= 600 && paragraph.text.substring(it.start, it.end).contains("Bold value") })
        assertTrue(paragraph.spanStyles.any { it.item.fontFamily == FontFamily.Monospace && paragraph.text.substring(it.start, it.end) == "inline  code" })
        show("  val message = \"日本\"\n\n")
        record.put("native_heading_emphasis_code_and_unicode_verified", true); capture("formatted-content"); record.put("passed", true)
    }
    @Test fun metadataLabelLinkAndTagRenderAndTagActivatesByTouch() {
        launch("metadata"); capture("before-contract-assertions")
        record.put("contract_assertions_started", true)
        assertTrue("Detail metadata must expose its owned label", compose.onAllNodesWithText("Owned value café 日本").fetchSemanticsNodes().isNotEmpty())
        show("Owned value café 日本"); show("Owned documentation").assertHasClickAction()
        show("Owned tag 🌱").assertHasClickAction().performTouchInput { click() }
        compose.waitUntil(5000) { catalog.runtimeTree?.toString()?.contains("Owned action count 1") == true }
        show("Owned action count 1"); record.put("pointer_tag_activation_count", 1)
        capture("metadata-tag-activated"); record.put("passed", true)
    }
    private fun hiddenDetail(mode: String) {
        launch(mode)
        val props = requireNotNull(activeRoot(catalog.runtimeTree)).props()
        if (mode == "list-omitted") record.put("serialized_detail_flag_present", props.has("isShowingDetail"))
        else assertFalse(props.getBoolean("isShowingDetail"))
        assertFalse(props.optBoolean("isShowingDetail", false))
        val hidden = compose.onAllNodesWithText("Owned hidden detail alpha", substring = true)
        // Reveal a violation before capturing it; the absence assertion is identical on both apps.
        if (hidden.fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("Owned hidden detail alpha", substring = true).performScrollTo()
        capture("hidden-detail-contract")
        record.put("contract_assertions_started", true)
        assertTrue("Hidden List.Detail content must be absent", hidden.fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Owned hidden detail alpha", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Owned hidden detail beta", substring = true).assertDoesNotExist()
        record.put("hidden_detail_absent", true).put("passed", true)
    }
    @Test fun listDetailIsHiddenWhenTheParentFlagIsFalse() = hiddenDetail("list-false")
    @Test fun listDetailIsHiddenWhenTheParentFlagIsOmitted() = hiddenDetail("list-omitted")
}
