package dev.tinycast.android

import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class InlinePaletteDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private var original = emptyMap<String, Any?>()
    private var prepared = false
    private var originalClip: android.content.ClipData? = null
    private var hostClipboardRecovery = false
    private val touchEvidence = JSONArray()
    private val scrollEvidence = JSONArray()
    private val keyboardSetupEvidence = JSONArray()
    @Before fun prepare() {
        val avd = InstrumentationRegistry.getArguments().getString("dedicatedAvd")
        assertFalse("Explicit owned AVD required", avd.isNullOrBlank())
        assertEquals(avd, device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim())
        hostClipboardRecovery = hostRestoresTestClipboard()
        original = context.getSharedPreferences("palette", Context.MODE_PRIVATE).all.toMap()
        prepared = true
        compose.activityRule.scenario.onActivity {
            val manager = it.getSystemService(ClipboardManager::class.java)
            originalClip = manager.primaryClip; manager.resetClipboardForTest()
            it.store.reset(); it.store.filter("Apps")
        }
        if (hostClipboardRecovery) dismissClipboardForEvidence()
        compose.waitUntil(10000) { !compose.activity.store.loading }
    }
    @After fun restore() {
        if (!prepared) return
        restorePalettePreferences(context, original)
        compose.activityRule.scenario.onActivity {
            val manager = it.getSystemService(ClipboardManager::class.java)
            if (hostClipboardRecovery) manager.resetClipboardForTest()
            else originalClip?.let(manager::setPrimaryClip) ?: manager.resetClipboardForTest()
        }
    }
    private fun key(code: Int, meta: Int = 0) {
        val time = android.os.SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP))
            InstrumentationRegistry.getInstrumentation().sendKeySync(KeyEvent(time, time, action, code, 0, meta))
        compose.waitForIdle()
    }
    private fun sameWindow() {
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onAllNodes(isPopup()).assertCountEquals(0)
        compose.onAllNodes(isRoot()).assertCountEquals(1)
    }
    private fun hideKeyboard() {
        compose.activityRule.scenario.onActivity { androidx.core.view.WindowCompat.getInsetsController(it.window,it.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
        compose.waitUntil(5000) { android.view.inspector.WindowInspector.getGlobalWindowViews().filter{it.hasWindowFocus()}.none {
            androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true } }
    }
    private fun editorBounds(): Pair<androidx.compose.ui.geometry.Rect,androidx.compose.ui.geometry.Rect> {
        val save=compose.onNodeWithTag("save-editor").fetchSemanticsNode()
        val frame=compose.onNodeWithTag("palette-screen").fetchSemanticsNode()
        // Native inset delivery cannot split these two geometry reads.
        return compose.runOnUiThread { save.boundsInWindow to frame.boundsInWindow }
    }
    /** All geometry in one sample is read in one UI callback, without an inset delivery between nodes. */
    private fun menuBoundarySnapshot(cachedPalette: Rect? = null): JSONObject {
        val root = compose.onNode(isRoot() and hasAnyDescendant(hasTestTag("palette")), useUnmergedTree = true).fetchSemanticsNode()
        return compose.runOnUiThread {
            fun rect(value: Rect) = JSONArray(listOf(value.left, value.top, value.right, value.bottom))
            val nodes = JSONArray()
            var visited = 0
            val tags = setOf("palette", "palette-screen", "palette-header", "palette-footer", "palette-menu", "search", "palette-content-scroll",
                "actions-button", "primary-action", "inline-menu", "inline-menu-choices", "inline-menu-search", "inline-menu-empty", "inline-menu-keyboard")
            fun visit(node: androidx.compose.ui.semantics.SemanticsNode, insideMenu: Boolean = false) {
                if (visited >= 1024) return
                visited++
                val tag = node.config.getOrNull(SemanticsProperties.TestTag)
                val menu = insideMenu || tag == "inline-menu"
                if (menu || tag in tags) {
                    val coordinates = node.layoutInfo.coordinates
                    val item = JSONObject().put("id", node.id).put("tag", tag)
                        .put("placed", node.layoutInfo.isPlaced).put("attached", coordinates.isAttached)
                        .put("bounds_window", rect(node.boundsInWindow)).put("bounds_root", rect(node.boundsInRoot))
                        .put("layout_size", JSONArray(listOf(node.size.width, node.size.height)))
                        .put("focused", node.config.getOrNull(SemanticsProperties.Focused))
                        .put("selected", node.config.getOrNull(SemanticsProperties.Selected))
                        .put("text", node.config.getOrNull(SemanticsProperties.Text)?.joinToString("\n")?.take(512))
                        .put("editable_text", node.config.getOrNull(SemanticsProperties.EditableText)?.text?.take(512))
                        .put("selection", node.config.getOrNull(SemanticsProperties.TextSelectionRange)?.toString())
                    if(coordinates.isAttached) {
                        val origin = coordinates.localToRoot(androidx.compose.ui.geometry.Offset.Zero)
                        item.put("layout_origin_root", JSONArray(listOf(origin.x, origin.y)))
                    }
                    node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let {
                        item.put("vertical_scroll", JSONObject().put("value", it.value()).put("max", it.maxValue()))
                    }
                    val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                    item.put("text_layouts", JSONArray(layouts.map { layout ->
                        JSONObject().put("size", JSONArray(listOf(layout.size.width, layout.size.height)))
                            .put("overflow_width", layout.didOverflowWidth).put("overflow_height", layout.didOverflowHeight)
                            .put("lines", JSONArray((0 until layout.lineCount).map { line ->
                                JSONObject().put("left", layout.getLineLeft(line)).put("right", layout.getLineRight(line))
                                    .put("top", layout.getLineTop(line)).put("bottom", layout.getLineBottom(line))
                                    .put("ellipsized", layout.isLineEllipsized(line))
                            }))
                    }))
                    nodes.put(item)
                }
                node.children.forEach { visit(it, menu) }
            }
            visit(root)
            val view = compose.activity.window.decorView
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(view)
            val ime = insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            val visibleFrame = android.graphics.Rect().also(view::getWindowVisibleDisplayFrame)
            val location = IntArray(2).also(view::getLocationOnScreen)
            JSONObject().put("uptime_ms", android.os.SystemClock.uptimeMillis())
                .put("owner_root_semantics_id", root.id)
                .put("nodes", nodes).put("nodes_visited", visited).put("tree_limit_reached", visited >= 1024)
                .put("density", context.resources.displayMetrics.density).put("font_scale", context.resources.configuration.fontScale)
                .put("ime_visible", insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()))
                .put("ime_insets_px", ime?.let { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) })
                .put("decor_size", JSONArray(listOf(view.width, view.height)))
                .put("decor_screen_origin", JSONArray(location.toList())).put("window_visible_frame", visibleFrame.toShortString())
                .put("cached_palette", cachedPalette?.let(::rect)).put("route", compose.activity.store.dialog)
        }
    }
    private fun currentMenuBounds(): Pair<Rect, Rect> {
        val nodes = menuBoundarySnapshot().getJSONArray("nodes")
        fun bounds(tag: String): Rect {
            val node = (0 until nodes.length()).map(nodes::getJSONObject).single { it.optString("tag") == tag }
            assertTrue("The current $tag must be placed and attached", node.getBoolean("placed") && node.getBoolean("attached"))
            val value = node.getJSONArray("bounds_window")
            return Rect(value.getDouble(0).toFloat(), value.getDouble(1).toFloat(), value.getDouble(2).toFloat(), value.getDouble(3).toFloat())
        }
        return bounds("palette") to bounds("inline-menu")
    }
    /** Captures even clipped/absent markers; it must run before the original failing assertion. */
    private fun captureMenuBoundary(name: String, cachedPalette: Rect? = null, legacyMenu: Rect? = null) {
        compose.waitForIdle()
        val folder = File(context.filesDir, "extension-grid-evidence").apply { mkdirs() }
        val record = JSONObject().put("schema_version", 1).put("name", name)
            .put("scope", "Same-UI-callback geometry; native XML and PNG are subsequent observations, not an atomic pixel transaction")
            .put("pointer_taps", touchEvidence)
            .put("pointer_scrolls", scrollEvidence)
            .put("launcher_keyboard_setup", keyboardSetupEvidence)
            .put("legacy_menu_operand", legacyMenu?.let { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) })
            .put("installed_build_commit", context.packageManager.getApplicationInfo(context.packageName,
                android.content.pm.PackageManager.GET_META_DATA).metaData.getString("dev.droidcast.BUILD_COMMIT"))
            .put("before_native_capture", menuBoundarySnapshot(cachedPalette))
        testAutomation().clearCacheForTest()
        device.dumpWindowHierarchy(File(folder, "$name.xml"))
        val rendered = java.util.concurrent.CountDownLatch(1)
        compose.activityRule.scenario.onActivity {
            it.window.decorView.viewTreeObserver.registerFrameCommitCallback { rendered.countDown() }
            it.window.decorView.postInvalidateOnAnimation()
        }
        record.put("frame_commit_observed", rendered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            .put("before_png", menuBoundarySnapshot(cachedPalette))
            .put("png_captured", device.takeScreenshot(File(folder, "$name.png")))
            .put("after_png", menuBoundarySnapshot(cachedPalette))
        File(folder, "$name-frame.json").writeText(record.toString(2))
    }
    private fun imeVisible(): Boolean = compose.runOnUiThread {
        androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
    }
    private fun pointer(tag: String, scrollTag: String? = null) {
        val minimum = 48 * context.resources.displayMetrics.density
        var target: androidx.compose.ui.semantics.SemanticsNode? = null
        var direction = 1
        var seenTarget = false
        for(attempt in 0..20) {
            val node = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()
            if(node != null && node.layoutInfo.isPlaced && node.layoutInfo.coordinates.isAttached &&
                node.size.width + 1 >= minimum && node.size.height + 1 >= minimum &&
                node.boundsInRoot.width + 1 >= node.size.width && node.boundsInRoot.height + 1 >= node.size.height) {
                target = node; break
            }
            if(scrollTag == null || attempt == 20) break
            val scrollNode = compose.onNodeWithTag(scrollTag).fetchSemanticsNode()
            val viewport = scrollNode.boundsInRoot
            assertTrue("A pointer scroll needs a placed viewport", scrollNode.layoutInfo.isPlaced && viewport.height > 0)
            val visible = node?.takeIf { it.layoutInfo.isPlaced }?.boundsInRoot?.takeIf { it.height > 0 }
            if(visible != null) {
                direction = if(visible.top <= viewport.top + 1 && visible.bottom < viewport.bottom - 1) -1 else 1
                seenTarget = true
            } else if(seenTarget) direction = -direction
            val distance = minOf(viewport.height * .3f, if(visible != null)
                (requireNotNull(node).size.height - visible.height + 8 * context.resources.displayMetrics.density).coerceAtLeast(20f)
                else 60 * context.resources.displayMetrics.density)
            val start = viewport.center + androidx.compose.ui.geometry.Offset(0f, direction * distance / 2)
            val end = viewport.center - androidx.compose.ui.geometry.Offset(0f, direction * distance / 2)
            val owner = compose.onNode(isRoot() and hasAnyDescendant(hasTestTag(scrollTag)), useUnmergedTree = true)
            val root = owner.fetchSemanticsNode().boundsInRoot
            val observation = JSONObject().put("target", tag).put("viewport_tag", scrollTag).put("direction", if(direction > 0) "up" else "down")
                .put("start_root", JSONArray(listOf(start.x, start.y))).put("end_root", JSONArray(listOf(end.x, end.y)))
                .put("duration_ms", 600).put("target_visible_height_before", visible?.height)
                .put("accessibility_axis_before", scrollNode.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke())
            owner.performTouchInput { swipe(start - root.topLeft, end - root.topLeft, durationMillis = 600) }
            compose.waitForIdle()
            observation.put("accessibility_axis_after", compose.onNodeWithTag(scrollTag).fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke())
            scrollEvidence.put(observation)
        }
        val node = requireNotNull(target) { "A complete placed 48dp $tag target must be reachable by pointer scrolling" }
        val owner = compose.onNode(isRoot() and hasAnyDescendant(hasTestTag(tag)), useUnmergedTree = true)
        val rootNode = owner.fetchSemanticsNode()
        val root = rootNode.boundsInRoot
        val point = node.boundsInRoot.center
        assertTrue("Pointer target belongs to the existing root", root.contains(point))
        touchEvidence.put(JSONObject().put("tag", tag).put("node_id", node.id)
            .put("owner_root_semantics_id", rootNode.id)
            .put("control_size", JSONArray(listOf(node.size.width, node.size.height)))
            .put("visible_bounds", JSONArray(listOf(node.boundsInRoot.left, node.boundsInRoot.top, node.boundsInRoot.right, node.boundsInRoot.bottom)))
            .put("point_root", JSONArray(listOf(point.x, point.y))).put("ime_visible", imeVisible()))
        owner.performTouchInput { click(point - root.topLeft) }
        compose.waitForIdle()
    }
    private fun setMenuKeyboard(visible: Boolean) {
        if(imeVisible() != visible) {
            compose.onNodeWithTag("inline-menu-keyboard").assertContentDescriptionEquals(
                if(visible) "Show action search keyboard" else "Hide action search keyboard")
            pointer("inline-menu-keyboard")
        }
        compose.waitUntil(10000) { imeVisible() == visible }
        compose.onNodeWithTag("inline-menu-search").assertIsFocused()
    }
    private fun launcherInputReady(): Boolean = compose.runOnUiThread {
        val view = compose.activity.window.decorView.findFocus()
        val manager = compose.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        view != null && view.hasWindowFocus() && view.onCheckIsTextEditor() && manager.isActive(view) && manager.isAcceptingText
    }
    private fun awaitLauncherIme(visible: Boolean, requestRoute: String) {
        val done = java.util.concurrent.CountDownLatch(1)
        val view = compose.activity.window.decorView
        val deadline = android.os.SystemClock.uptimeMillis() + 10000
        var previous = emptyList<Int>(); var stableSince = 0L; var frames = 0; var settled = false
        val observer = object : Runnable {
            override fun run() {
                frames++
                val insets = androidx.core.view.ViewCompat.getRootWindowInsets(view)
                val actual = insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
                val bottom = insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: -1
                val geometry = listOf(if(actual == true) 1 else 0, bottom, view.width, view.height)
                val now = android.os.SystemClock.uptimeMillis()
                if(geometry != previous) { previous = geometry; stableSince = now }
                settled = actual == visible && now - stableSince >= 100
                if(settled || now >= deadline) done.countDown() else view.postOnAnimation(this)
            }
        }
        compose.activityRule.scenario.onActivity { view.postOnAnimation(observer) }
        val completed = done.await(11, java.util.concurrent.TimeUnit.SECONDS)
        compose.activityRule.scenario.onActivity { view.removeCallbacks(observer) }
        keyboardSetupEvidence.put(JSONObject().put("requested_visible", visible).put("settled", completed && settled)
            .put("request_route", requestRoute)
            .put("geometry", JSONArray(previous)).put("native_frames", frames).put("stable_for_at_least_ms", 100))
        assertTrue("Launcher IME visibility and geometry must settle before the starting-state assertion", completed && settled)
        compose.waitForIdle()
    }
    private fun setLauncherKeyboard(visible: Boolean) {
        compose.onNodeWithTag("search").performScrollTo().performClick()
        device.waitForIdle(2000)
        compose.waitUntil(10000) { launcherInputReady() }
        // A focus tap may enqueue a native show after Compose becomes idle.
        // Establish an observed shown state before requesting the closed fixture;
        // a passing replay does not explain the retained earlier hide timeout.
        val show = compose.onAllNodesWithContentDescription("Show on-screen keyboard").fetchSemanticsNodes()
        val route = if(show.isNotEmpty()) {
            // This also sets the launcher's wide-screen keyboard request. A
            // direct Insets show would otherwise conflict with its hide policy.
            compose.onNodeWithContentDescription("Show on-screen keyboard").performClick()
            "Existing launcher Show on-screen keyboard callback"
        } else {
            if(!imeVisible()) {
            compose.activityRule.scenario.onActivity {
                androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView)
                    .show(androidx.core.view.WindowInsetsCompat.Type.ime())
            }
            }
            "Focused narrow launcher; native show if needed"
        }
        awaitLauncherIme(true, route)
        if(!visible) { hideKeyboard(); awaitLauncherIme(false, "Native hide after the observed shown state") }
        assertTrue("The launcher editor remains active across fixture keyboard setup", launcherInputReady())
    }
    private fun menuTextHasCompleteNaturalLines(label: String? = null): Boolean {
        val nodes = menuBoundarySnapshot().getJSONArray("nodes")
        fun find(tag: String) = (0 until nodes.length()).map(nodes::getJSONObject).singleOrNull { it.optString("tag") == tag }
        val text = if(label == null) find("inline-menu-empty") else (0 until nodes.length()).map(nodes::getJSONObject)
            .singleOrNull { it.optString("text") == label && it.getJSONArray("text_layouts").length() == 1 }
        if(text == null) return false
        val body = find("inline-menu-choices") ?: return false
        val origin = text.optJSONArray("layout_origin_root") ?: return false
        val bounds = text.getJSONArray("bounds_root"); val viewport = body.getJSONArray("bounds_root")
        val left = maxOf(bounds.getDouble(0), viewport.getDouble(0)); val top = maxOf(bounds.getDouble(1), viewport.getDouble(1))
        val right = minOf(bounds.getDouble(2), viewport.getDouble(2)); val bottom = minOf(bounds.getDouble(3), viewport.getDouble(3))
        val layouts = text.getJSONArray("text_layouts")
        if(layouts.length() != 1 || right <= left || bottom <= top) return false
        val lines = layouts.getJSONObject(0).getJSONArray("lines")
        return lines.length() > 0 && (0 until lines.length()).all { index ->
            val line = lines.getJSONObject(index)
            !line.getBoolean("ellipsized") && origin.getDouble(0) + line.getDouble("left") >= left - 1 &&
                origin.getDouble(0) + line.getDouble("right") <= right + 1 &&
                origin.getDouble(1) + line.getDouble("top") >= top - 1 && origin.getDouble(1) + line.getDouble("bottom") <= bottom + 1
        }
    }
    private fun withTouchKeyboard(label: String, block: () -> Unit) {
        val previous = device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
        require(previous in setOf("null", "0", "1")) { "Unexpected dedicated-emulator keyboard setting" }
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
        try { block() } catch(failure: Throwable) {
            runCatching { captureMenuBoundary("menu-touch-$label-failed") }
            throw failure
        } finally {
            device.executeShellCommand(if(previous == "null") "settings delete secure show_ime_with_hard_keyboard" else "settings put secure show_ime_with_hard_keyboard $previous")
        }
    }
    private fun touchReceipt(name: String, observations: JSONArray) {
        val folder = File(context.filesDir, "extension-grid-evidence").apply { mkdirs() }
        File(folder, "$name-record.json").writeText(JSONObject().put("passed", true).put("pointer_taps", touchEvidence).put("pointer_scrolls", scrollEvidence)
            .put("launcher_keyboard_setup", keyboardSetupEvidence)
            .put("observations", observations).put("font_scale", context.resources.configuration.fontScale)
            .put("scope", "Owned menu workflows; host cleanup independently restores original clipboard/preferences/settings/evidence")
            .put("installed_build_commit", context.packageManager.getApplicationInfo(context.packageName,
                android.content.pm.PackageManager.GET_META_DATA).metaData.getString("dev.droidcast.BUILD_COMMIT")).toString(2))
    }
    @Test fun categoryPointerChoiceAndReturnWorkFromBothKeyboardStates() = withTouchKeyboard("category") {
        val title = "Menu category fixture 5041"
        var id = ""
        compose.activityRule.scenario.onActivity { id = it.store.save("notes", title, "Owned category destination") }
        val observations = JSONArray()
        for(initialKeyboard in listOf(false, true)) {
            compose.activityRule.scenario.onActivity { it.store.filter("Apps"); it.store.search("Settings") }
            setLauncherKeyboard(initialKeyboard)
            compose.onNodeWithTag("search").performTextInputSelection(TextRange(1, 5))
            assertEquals(initialKeyboard, imeVisible())
            pointer("palette-menu", "palette-content-scroll")
            compose.onNodeWithTag("inline-menu").assertIsDisplayed()
            val (frame, menu) = currentMenuBounds()
            assertTrue(frame.contains(menu.topLeft) && frame.contains(menu.bottomRight))
            captureMenuBoundary("menu-category-touch-${if(initialKeyboard) "open" else "closed"}")
            key(KeyEvent.KEYCODE_ESCAPE)
            compose.onNodeWithTag("search").assertIsFocused().assert(SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("Settings")))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(1, 5)))
            val escapedQuery = compose.onNodeWithTag("search").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
            setLauncherKeyboard(initialKeyboard)
            pointer("palette-menu", "palette-content-scroll")
            pointer("filter-Notes", "inline-menu-choices")
            compose.onNodeWithTag("inline-menu").assertDoesNotExist()
            assertEquals("Notes", compose.activity.store.category)
            compose.onNodeWithTag("search").assertIsFocused().assert(SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString(""))).performTextReplacement(title)
            compose.onNodeWithTag("search").assert(SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString(title)))
            compose.runOnUiThread {
                assertEquals("The owned note remains selected", id,
                    compose.activity.store.results().getOrNull(compose.activity.store.selected)?.id)
            }
            hideKeyboard()
            compose.onNodeWithTag("result-$id").assertIsDisplayed()
            captureMenuBoundary("menu-category-destination-${if(initialKeyboard) "open" else "closed"}")
            observations.put(JSONObject().put("initial_ime_visible", initialKeyboard).put("category", "Notes")
                .put("exact_owned_destination_id", id).put("escape_query_and_range_restored", true).put("caller_query_after_escape", escapedQuery))
        }
        touchReceipt("menu-category-touch", observations)
    }
    @Test fun touchActionSearchPreservesEmptyTextCaretKeyboardAndFreshCopy() = withTouchKeyboard("actions") {
        val title = "Menu action fixture 5041"; val payload = "Exact pointer menu payload 5041"
        var id = ""
        compose.activityRule.scenario.onActivity { id = it.store.save("notes", title, payload) }
        val observations = JSONArray()
        repeat(2) { cycle ->
            val sentinel = "Owned menu clipboard sentinel $cycle"
            compose.activityRule.scenario.onActivity {
                it.store.filter("All"); it.store.search(title)
                it.getSystemService(ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Owned menu fixture", sentinel))
            }
            dismissClipboardForEvidence()
            setLauncherKeyboard(false)
            compose.onNodeWithTag("search").performTextInputSelection(TextRange(2, 6))
            compose.onNodeWithTag("search").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 6)))
            captureMenuBoundary("menu-action-caller-before-open-$cycle")
            compose.onNodeWithTag("search").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 6)))
            compose.runOnUiThread {
                assertEquals("Actions belong to the live selected owned note", id,
                    compose.activity.store.results().getOrNull(compose.activity.store.selected)?.id)
            }
            pointer("actions-button", "palette-content-scroll")
            captureMenuBoundary("menu-action-caller-after-open-$cycle")
            setMenuKeyboard(true)
            compose.onNodeWithTag("inline-menu-search").performTextReplacement("no such action")
            compose.onNodeWithTag("inline-menu-search").performTextInputSelection(TextRange(3, 7))
            key(KeyEvent.KEYCODE_ENTER)
            compose.onNodeWithTag("inline-menu-search").assertIsFocused()
            compose.activityRule.scenario.onActivity {
                assertEquals(sentinel, it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString())
            }
            val readableWithIme = menuTextHasCompleteNaturalLines()
            if(cycle == 0) captureMenuBoundary("menu-empty-touch-ime")
            setMenuKeyboard(false)
            compose.onNodeWithTag("inline-menu-search").assert(SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("no such action")))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(3, 7)))
            compose.waitUntil(10000) { menuTextHasCompleteNaturalLines() }
            if(cycle == 0) captureMenuBoundary("menu-empty-touch-revealed")
            setMenuKeyboard(true); setMenuKeyboard(false)
            compose.onNodeWithTag("inline-menu-search").assertIsFocused().assert(SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("no such action")))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(3, 7)))
            val longLabel = "Set keyboard shortcut"
            compose.onNodeWithTag("inline-menu-search").performTextReplacement("keyboard shortcut")
            compose.onNodeWithTag("action-$longLabel").assertIsEnabled().assertIsSelected()
                .assertContentDescriptionEquals(longLabel)
            compose.waitUntil(10000) { menuTextHasCompleteNaturalLines(longLabel) }
            if(cycle == 0) captureMenuBoundary("menu-long-action-touch")
            compose.onNodeWithTag("inline-menu-search").performTextReplacement("content copy")
            compose.onNodeWithTag("action-Copy content").assertIsSelected()
            captureMenuBoundary("menu-action-caller-before-copy-$cycle")
            pointer("action-Copy content", "inline-menu-choices")
            compose.onNodeWithTag("inline-menu").assertDoesNotExist()
            compose.onNodeWithTag("search").assertIsFocused().assert(SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString(title)))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 6)))
            val returnedQuery = compose.onNodeWithTag("search").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
            compose.activityRule.scenario.onActivity {
                assertEquals(payload, it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString())
            }
            observations.put(JSONObject().put("cycle", cycle).put("empty_readable_with_ime", readableWithIme)
                .put("empty_natural_lines_reachable_after_hide", true).put("query_and_caret_preserved", true)
                .put("long_action_natural_lines_and_complete_semantics", true).put("long_action_label", longLabel)
                .put("empty_enter_left_sentinel_unchanged", true).put("exact_fresh_copy_and_caller_range", true).put("caller_query_after_return", returnedQuery))
        }
        touchReceipt("menu-actions-touch", observations)
    }
    private fun capture(name: String, marker: String, secondMarker: String? = null) {
        compose.waitForIdle(); sameWindow()
        if(name=="inline-emoji-actions") {
            val route = compose.activity.store.dialog
            val menu = compose.onAllNodesWithTag("inline-menu").fetchSemanticsNodes().size
            device.wait(Until.hasObject(By.res("com.android.systemui","dismiss_button")),1500)
            dismissClipboardForEvidence()
            assertEquals("Emoji cleanup retains its route", route, compose.activity.store.dialog)
            assertEquals("Emoji cleanup retains its menu", menu, compose.onAllNodesWithTag("inline-menu").fetchSemanticsNodes().size)
        }
        val folder = File(context.filesDir, "extension-grid-evidence").apply { mkdirs() }
        compose.waitUntil(10000) {
            testAutomation().clearCacheForTest()
            val xml = File(folder, "$name.xml"); device.dumpWindowHierarchy(xml)
            val nodes = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml).getElementsByTagName("node")
            val native = (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
                .filter { it.getAttribute("package") == context.packageName }
            listOfNotNull(marker, secondMarker).all { expected -> native.any { it.getAttribute("text").contains(expected) } }
        }
        if (secondMarker != null) {
            val rendered = java.util.concurrent.CountDownLatch(1)
            compose.activityRule.scenario.onActivity {
                assertTrue(it.window.decorView.isHardwareAccelerated)
                it.window.decorView.viewTreeObserver.registerFrameCommitCallback { rendered.countDown() }
                it.window.decorView.postInvalidateOnAnimation()
            }
            assertTrue("The native matching screen must render before its screenshot", rendered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }
        assertTrue(device.takeScreenshot(File(folder, "$name.png")))
    }
    @Test fun editorReplacesPaletteAndRestoresLauncherQueryCaretAndFrame() {
        compose.activityRule.scenario.onActivity { it.store.filter("All") }
        compose.onNodeWithTag("search").performTextReplacement("Create a note")
        compose.onNodeWithTag("search").performTextInputSelection(TextRange(3, 7))
        val frame = compose.onNodeWithTag("palette").fetchSemanticsNode().boundsInWindow
        compose.onNodeWithTag("result-new-note").performClick()
        compose.onNodeWithTag("palette").assertDoesNotExist()
        val editor = compose.onNodeWithTag("palette-screen").fetchSemanticsNode().boundsInWindow
        assertEquals(frame.left, editor.left, 1f); assertEquals(frame.top, editor.top, 1f); assertEquals(frame.width, editor.width, 1f)
        compose.onNodeWithTag("editor-title").performTextReplacement("Inline draft")
        compose.onNodeWithTag("editor-body").performTextReplacement("This draft stays inside the palette.")
        compose.onNodeWithTag("save-editor").assertIsDisplayed()
        val (save,typingFrame)=editorBounds()
        val diagnostics=File(context.filesDir,"extension-grid-evidence").apply{mkdirs()}
        File(diagnostics,"editor-current-measurement.json").writeText(org.json.JSONObject().put("save",save.toString()).put("frame",typingFrame.toString()).toString(2))
        assertTrue("Save stays inside the same measured palette",save.bottom<=typingFrame.bottom)
        compose.activityRule.scenario.onActivity {
            androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        compose.waitForIdle(); device.waitForIdle()
        compose.onNodeWithTag("editor-title").performScrollTo()
        capture("inline-editor", "Inline draft")
        compose.onNodeWithTag("inline-back").performClick()
        compose.onNodeWithTag("search").assertTextContains("Create a note").assertIsFocused()
        compose.onNodeWithTag("search").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(3,7)))
        val returned = compose.onNodeWithTag("palette").fetchSemanticsNode().boundsInWindow
        assertEquals(frame.left, returned.left, 1f); assertEquals(frame.top, returned.top, 1f); assertEquals(frame.width, returned.width, 1f)
    }
    @Test fun categoryMenuIsInsidePaletteAndKeyboardReturnsFocus() {
        compose.onNodeWithTag("search").performTextReplacement("Settings")
        val frame = compose.onNodeWithTag("palette").fetchSemanticsNode().boundsInWindow
        compose.onNodeWithTag("palette-menu").performClick()
        compose.onNodeWithTag("inline-menu").assertIsDisplayed()
        val menu = compose.onNodeWithTag("inline-menu").fetchSemanticsNode().boundsInWindow
        captureMenuBoundary("menu-category-current", frame, menu)
        val (currentFrame, currentMenu) = currentMenuBounds()
        assertTrue("The menu must fit the current palette after inset/focus changes",
            currentFrame.contains(currentMenu.topLeft) && currentFrame.contains(currentMenu.bottomRight))
        capture("inline-command-menu", "Apps")
        key(KeyEvent.KEYCODE_ESCAPE)
        compose.onNodeWithTag("search").assertIsFocused().assertTextContains("Settings")
        compose.onNodeWithTag("palette-menu").performClick()
        compose.onNodeWithTag("inline-menu").assertIsDisplayed()
        key(KeyEvent.KEYCODE_MOVE_HOME)
        compose.onNodeWithTag("filter-All").assertIsSelected()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("filter-Pinned").assertIsSelected()
        key(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON)
        compose.onNodeWithTag("filter-All").assertIsSelected()
        repeat(3) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.onNodeWithTag("filter-Notes").assertIsSelected()
        key(KeyEvent.KEYCODE_NUMPAD_ENTER)
        assertEquals("Notes", compose.activity.store.category)
        compose.onNodeWithTag("inline-menu").assertDoesNotExist()
        compose.onNodeWithTag("search").assertIsFocused().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        sameWindow()
    }
    @Test fun returningFromEditorRetainsTheScrolledLauncherViewport() {
        compose.activityRule.scenario.onActivity { activity ->
            repeat(24) { activity.store.save("notes","Scroll fixture %02d".format(it),"Public scroll fixture") }
            activity.store.filter("Notes"); activity.store.search("Scroll fixture")
        }
        hideKeyboard()
        compose.activityRule.scenario.onActivity { it.store.selected=9 }
        compose.onNodeWithTag("results").performScrollToIndex(5)
        compose.waitForIdle()
        val id=compose.activity.store.results()[5].id
        val before=compose.onNodeWithTag("result-$id").fetchSemanticsNode().boundsInWindow
        key(KeyEvent.KEYCODE_N,KeyEvent.META_CTRL_ON)
        compose.onNodeWithTag("editor-title").assertIsDisplayed()
        // Compare equal viewports: editor focus may have opened the IME.
        // Keyboard-shrink selection recovery has separate measured coverage.
        hideKeyboard()
        compose.onNodeWithTag("inline-back").performClick(); hideKeyboard()
        val after=compose.onAllNodesWithTag("result-$id").fetchSemanticsNodes().singleOrNull()?.boundsInWindow
        val folder=File(context.filesDir,"extension-grid-evidence").apply{mkdirs()}
        File(folder,"launcher-return-position.json").writeText(org.json.JSONObject().put("before",before.toString()).put("after",after?.toString()).put("selected",compose.activity.store.selected).toString(2))
        device.dumpWindowHierarchy(File(folder,"launcher-return-position.xml")); device.takeScreenshot(File(folder,"launcher-return-position.png"))
        assertEquals("Back retains the same scrolled launcher row",before,after)
        assertEquals(9,compose.activity.store.selected)
        compose.onNodeWithTag("search").assertIsFocused().assertTextContains("Scroll fixture")
    }
    @Test fun editorSaveRemainsReachableAboveTheRealDockedKeyboard() {
        val prior=device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
        try {
            compose.activityRule.scenario.onActivity { it.execute(Command("new-note","Create a note","")) }
            compose.onNodeWithTag("editor-title").performTextReplacement("Keyboard draft")
            compose.onNodeWithTag("editor-body").performTextReplacement("Save stays above the real keyboard.")
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            hideKeyboard()
            val (hiddenSave,hiddenFrame)=editorBounds()
            compose.onNodeWithTag("editor-body").performSemanticsAction(SemanticsActions.RequestFocus)
            compose.onNodeWithTag("editor-body").assertIsFocused()
            compose.activityRule.scenario.onActivity {
                androidx.core.view.WindowCompat.getInsetsController(it.window,it.window.decorView).show(androidx.core.view.WindowInsetsCompat.Type.ime())
            }
            compose.waitUntil(10000) { android.view.inspector.WindowInspector.getGlobalWindowViews().filter{it.hasWindowFocus()}.any {
                androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true } }
            compose.onNodeWithTag("save-editor").assertIsDisplayed().assertIsEnabled()
            val (save,frame)=editorBounds()
            val diagnostics=File(context.filesDir,"extension-grid-evidence").apply{mkdirs()}
            val splitReadWouldFail=hiddenSave.bottom>frame.bottom
            File(diagnostics,"editor-resize-measurement.json").writeText(org.json.JSONObject().put("before_save",hiddenSave.toString()).put("before_frame",hiddenFrame.toString()).put("after_save",save.toString()).put("after_frame",frame.toString()).put("cross_frame_comparison_would_fail",splitReadWouldFail).toString(2))
            if(context.resources.configuration.smallestScreenWidthDp>=600) assertTrue("Controlled mixed-frame comparison fails on tablet resize",splitReadWouldFail)
            assertTrue(frame.contains(save.topLeft) && frame.contains(save.bottomRight))
            val keyboard=device.wait(Until.findObject(By.pkg("com.google.android.inputmethod.latin")),5000)
            assertNotNull("Real Gboard is present",keyboard)
            assertTrue("Save stays above Gboard",save.bottom <= keyboard.visibleBounds.top)
            capture("inline-editor-ime","Save stays above the real keyboard.")
            compose.onNodeWithTag("save-editor").performClick()
            compose.onNodeWithTag("palette-screen").assertDoesNotExist()
            assertTrue(compose.activity.store.notes.any{it.title=="Keyboard draft" && it.body=="Save stays above the real keyboard."})
        } finally {
            device.executeShellCommand(if(prior=="null")"settings delete secure show_ime_with_hard_keyboard"else"settings put secure show_ime_with_hard_keyboard $prior")
        }
    }
    @Test fun programmaticCommandDismissesMenuBeforeEditingNewQuery() {
        compose.onNodeWithTag("palette-menu").performClick()
        compose.onNodeWithTag("inline-menu").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.execute(Command("calculator", "Calculator", "")) }
        compose.onNodeWithTag("inline-menu").assertDoesNotExist()
        compose.onNodeWithTag("search").assertIsFocused()
        assertEquals("= ",compose.activity.store.query)
    }
    @Test fun staleMenuOwnerCannotClearTheCurrentWindowState() {
        compose.onNodeWithTag("palette-menu").performClick()
        compose.onNodeWithTag("inline-menu").assertIsDisplayed()
        assertTrue(compose.activity.store.paletteMenuOpen)
        val epoch = ShortcutRuntime.recordingEpoch
        compose.activityRule.scenario.onActivity {
            assertFalse(it.store.paletteMenuChanged(Any(), false, true))
        }
        assertTrue(compose.activity.store.paletteMenuOpen)
        assertEquals(epoch, ShortcutRuntime.recordingEpoch)
        compose.onNodeWithTag("inline-menu").assertIsDisplayed()
        key(KeyEvent.KEYCODE_ESCAPE)
        compose.onNodeWithTag("inline-menu").assertDoesNotExist()
        assertFalse(compose.activity.store.paletteMenuOpen)
    }
    @Test fun menuOwnershipEpochCancelsModifierPairsAcrossOpeningAndClosing() {
        val detector = ModifierShortcutObserver()
        val binding = ShortcutRules.doubleTap(ShortcutRules.CTRL)
        val bindings = mapOf(binding to ShortcutRules.PALETTE)
        fun event(down: Boolean, time: Long, press: Long = time) = ShortcutEngine.Input(down, KeyEvent.KEYCODE_CTRL_LEFT,
            if(down) ShortcutRules.CTRL else 0, device=42, eventTime=time, downTime=press, receivedAt=time)
        val epoch = ShortcutRuntime.recordingEpoch
        assertNull(detector.handle(event(true,100),bindings,recordingEpoch=epoch))
        assertNull(detector.handle(event(false,130,100),bindings,recordingEpoch=epoch))
        compose.onNodeWithTag("palette-menu").performClick()
        compose.onNodeWithTag("inline-menu").assertIsDisplayed()
        val opened = ShortcutRuntime.recordingEpoch
        assertTrue(opened > epoch)
        assertTrue(ShortcutRuntime.menuOwnsInput())
        key(KeyEvent.KEYCODE_ESCAPE)
        compose.onNodeWithTag("inline-menu").assertDoesNotExist()
        val closed = ShortcutRuntime.recordingEpoch
        assertTrue(closed > opened)
        assertFalse(ShortcutRuntime.menuOwnsInput())
        assertNull(detector.handle(event(true,200),bindings,recordingEpoch=closed))
        assertNull("A pair cannot span menu ownership",detector.handle(event(false,230,200),bindings,recordingEpoch=closed))
        assertNull(detector.handle(event(true,300),bindings,recordingEpoch=closed))
        assertEquals(ShortcutRules.PALETTE,detector.handle(event(false,330,300),bindings,recordingEpoch=closed)?.first)
    }
    @Test fun inlineAliasConflictDeleteCancellationAndFeedbackStayInThePalette() {
        var noteId=""
        compose.activityRule.scenario.onActivity {
            noteId=it.store.save("notes","Inline recovery note","A public test note")
            it.store.actionCommand=it.store.command(noteId)
            it.store.dialog="alias"
        }
        compose.onNodeWithTag("alias-field").performTextReplacement("prefs")
        compose.onNodeWithTag("save-alias").performScrollTo().performClick()
        compose.onNodeWithText("That alias is already in use").assertIsDisplayed()
        capture("inline-alias-conflict","That alias is already in use")
        compose.onNodeWithTag("alias-field").performTextReplacement("inlinetest")
        compose.onNodeWithTag("save-alias").performScrollTo().performClick()
        assertEquals("inlinetest",compose.activity.store.command(noteId)?.alias)
        compose.activityRule.scenario.onActivity {
            it.store.actionCommand=it.store.command(noteId); it.store.dialog="delete"
        }
        compose.onNodeWithTag("confirm-delete").assertIsDisplayed()
        capture("inline-delete-review","Original documents are kept")
        compose.onNodeWithTag("inline-back").performClick()
        assertNotNull(compose.activity.store.command(noteId))
        compose.activityRule.scenario.onActivity {
            it.store.actionCommand=it.store.command(noteId); it.store.dialog="delete"
        }
        compose.onNodeWithTag("confirm-delete").performClick()
        assertNull(compose.activity.store.command(noteId))
        compose.activityRule.scenario.onActivity { it.store.dialog="shortcut-help"; it.store.message="Inline feedback stays in the palette" }
        compose.onNodeWithText("Inline feedback stays in the palette").assertIsDisplayed()
        val frame=compose.onNodeWithTag("palette-screen").fetchSemanticsNode().boundsInWindow
        val feedback=compose.onNodeWithTag("palette-feedback").fetchSemanticsNode().boundsInWindow
        assertTrue(frame.contains(feedback.topLeft) && frame.contains(feedback.bottomRight))
        capture("inline-feedback", "Keyboard shortcut help", "Inline feedback stays in the palette")
    }
    @Test fun actionSearchImeActivatesOnlyTheCurrentEnabledResultOutsideComposition() {
        compose.activityRule.scenario.onActivity {
            it.store.save("notes", "IME action note", "IME payload 161803")
            it.store.filter("All"); it.store.search("IME action note")
        }
        compose.onNodeWithTag("actions-button").performClick()
        compose.onNodeWithTag("inline-menu-search").assertIsFocused().performTextReplacement("no matching IME action")
        compose.onNodeWithTag("inline-menu-empty").assertIsDisplayed()
        compose.onNodeWithTag("inline-menu-search").performImeAction()
        compose.onNodeWithTag("inline-menu-search").assertIsFocused()
        compose.onNodeWithTag("inline-menu-search").performTextReplacement("content copy")
        compose.activityRule.scenario.onActivity {
            val connection = requireNotNull(it.window.decorView.findFocus()?.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
            // Keep composing-region delivery and submission in one main-thread
            // turn, so the live IME cannot finish composition between them.
            assertTrue(connection.setComposingRegion(0, 7))
            assertTrue(connection.performEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH))
            assertEquals("Search must preserve an actively composing menu", "actions", it.store.dialog)
            assertFalse("Composition must not copy an action payload", it.getSystemService(ClipboardManager::class.java).hasPrimaryClip())
            assertTrue(connection.finishComposingText())
        }
        compose.onNodeWithTag("inline-menu-search").assertIsFocused()
        compose.onNodeWithTag("inline-menu-search").performImeAction()
        compose.onNodeWithTag("inline-menu").assertDoesNotExist()
        compose.onNodeWithTag("search").assertIsFocused().assertTextContains("IME action note")
        compose.activityRule.scenario.onActivity {
            assertEquals("IME payload 161803", it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString())
        }
        compose.onNodeWithTag("actions-button").performClick()
        compose.onNodeWithTag("inline-menu-search").performTextReplacement("note")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("action-Edit note").assertIsSelected()
        capture("inline-ime-selected", "Edit note")
        compose.onNodeWithTag("inline-menu-search").performImeAction()
        compose.onNodeWithTag("editor-title").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("IME action note")))
        sameWindow()
        compose.onNodeWithTag("inline-back").performClick()
        compose.onNodeWithTag("search").assertIsFocused().assertTextContains("IME action note")
    }
    @Test fun actionCapsuleAndSearchableActionsPreserveTheSelectedCommand() {
        var id=""
        compose.activityRule.scenario.onActivity {
            id=it.store.save("notes","Action search note","Exact action payload 271828")
            it.store.filter("All"); it.store.search("Action search note")
        }
        val diagnostics=File(context.filesDir,"extension-grid-evidence").apply{mkdirs()}
        diagnostics.resolve("action-capsule-layout.json").writeText(org.json.JSONObject()
            .put("tree",compose.onRoot().printToString()).toString(2))
        assertTrue(device.takeScreenshot(File(diagnostics,"action-capsule-layout.png")))
        compose.onNodeWithTag("palette-action-capsule").assertIsDisplayed()
        compose.onNodeWithTag("primary-action").assertIsDisplayed()
        hideKeyboard()
        compose.onNodeWithTag("actions-button").assertIsDisplayed().performClick()
        capture("inline-command-actions","Action search note")
        if(compose.onAllNodes(hasTestTag("inline-menu-keyboard") and hasContentDescription("Show action search keyboard")).fetchSemanticsNodes().isNotEmpty()) {
            val before=compose.onNodeWithTag("inline-menu").fetchSemanticsNode().boundsInWindow.height
            val originalSetting=device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            try {
                compose.onNodeWithTag("inline-menu-keyboard").performClick()
                compose.waitUntil(10000) {
                    val (frame, menu) = currentMenuBounds()
                    menu.height < before-20 && frame.contains(menu.topLeft) && frame.contains(menu.bottomRight)
                }
                compose.onNodeWithTag("inline-menu-search").assertIsFocused()
            } finally { device.executeShellCommand(if(originalSetting=="null") "settings delete secure show_ime_with_hard_keyboard" else "settings put secure show_ime_with_hard_keyboard $originalSetting") }
        }
        compose.onNodeWithTag("inline-menu-search").assertIsFocused().performTextInput("no such action")
        captureMenuBoundary("menu-actions-no-match-legacy")
        compose.onNodeWithTag("inline-menu-empty").assertIsDisplayed()
        key(KeyEvent.KEYCODE_ENTER)
        compose.onNodeWithTag("inline-menu-search").assertIsFocused()
        compose.onNodeWithTag("inline-menu-search").performTextReplacement("content copy")
        compose.onNodeWithTag("action-Copy content").assertIsDisplayed()
        capture("inline-actions-search","Copy content")
        key(KeyEvent.KEYCODE_ENTER)
        compose.onNodeWithTag("inline-menu").assertDoesNotExist()
        compose.onNodeWithTag("search").assertIsFocused().assertTextContains("Action search note")
        var clip=""; compose.activityRule.scenario.onActivity { clip=it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString() }
        assertEquals("Exact action payload 271828",clip)
        compose.onNodeWithTag("actions-button").performClick()
        compose.onNodeWithTag("inline-menu-search").assertIsFocused()
        val time=android.os.SystemClock.uptimeMillis()
        InstrumentationRegistry.getInstrumentation().sendKeySync(KeyEvent(time,time,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_D,0,KeyEvent.META_CTRL_ON))
        repeat(4) { InstrumentationRegistry.getInstrumentation().sendKeySync(KeyEvent(time,android.os.SystemClock.uptimeMillis(),KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_D,it+1,0)) }
        InstrumentationRegistry.getInstrumentation().sendKeySync(KeyEvent(time,android.os.SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_D,0))
        compose.waitForIdle()
        assertTrue(id in compose.activity.store.pins)
        compose.onNodeWithTag("search").assertTextContains("Action search note")
        compose.onNodeWithTag("actions-button").performClick()
        compose.onNodeWithTag("inline-menu-search").performTextReplacement("delete")
        key(KeyEvent.KEYCODE_ENTER)
        compose.onNodeWithTag("confirm-delete").assertIsDisplayed()
        sameWindow(); compose.onNodeWithTag("inline-back").performClick()
        assertNotNull(compose.activity.store.command(id))
    }
    @Test fun emojiFilterUsesSameWindowAndEscapeRestoresTyping() {
        compose.activityRule.scenario.onActivity { it.execute(Command("emoji", "Search Emoji & Symbols", "")) }
        compose.waitUntil(10000) { !compose.activity.store.emoji.loading }
        compose.onNodeWithTag("emoji-search").performTextReplacement("face")
        val field = compose.onNodeWithTag("emoji-search").fetchSemanticsNode().boundsInWindow
        val frame = compose.onNodeWithTag("emoji-palette").fetchSemanticsNode().boundsInWindow
        compose.onNodeWithTag("emoji-filter").performClick()
        val menu = compose.onNodeWithTag("inline-menu").fetchSemanticsNode().boundsInWindow
        assertTrue(frame.contains(menu.topLeft) && frame.contains(menu.bottomRight))
        capture("inline-emoji-categories", "Emoji categories")
        key(KeyEvent.KEYCODE_ESCAPE)
        compose.onNodeWithTag("emoji-search").assertIsFocused().assertTextEquals("face")
        assertEquals(field, compose.onNodeWithTag("emoji-search").fetchSemanticsNode().boundsInWindow)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("emoji-search").assertIsFocused()
        val selected = compose.activity.store.emoji.results()[compose.activity.store.emoji.selected]
        key(KeyEvent.KEYCODE_ENTER)
        var value = ""; compose.activityRule.scenario.onActivity { value=it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString() }
        assertEquals(selected.glyph, value)
        hideKeyboard()
        key(KeyEvent.KEYCODE_K,KeyEvent.META_CTRL_ON)
        compose.onNodeWithTag("inline-menu-search").assertIsFocused()
        capture("inline-emoji-actions","Copy to Clipboard")
        compose.onNodeWithTag("inline-menu-search").performTextReplacement("unicode")
        key(KeyEvent.KEYCODE_ENTER)
        compose.onNodeWithTag("emoji-search").assertIsFocused().assertTextEquals("face")
        compose.activityRule.scenario.onActivity { value=it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString() }
        assertEquals(selected.glyph.codePoints().toArray().joinToString(" "){"U+"+it.toString(16).uppercase().padStart(4,'0')},value)
    }
}

/** Captures the released app with this test APK; no new implementation APIs are called. */
@RunWith(AndroidJUnit4::class)
class InlinePaletteBaselineTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun captureReleasedEditorAndActions() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val device=UiDevice.getInstance(instrumentation)
        assertEquals(InstrumentationRegistry.getArguments().getString("dedicatedAvd"),device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim())
        val original=context.getSharedPreferences("palette",Context.MODE_PRIVATE).all.toMap()
        var clip: android.content.ClipData?=null
        compose.activityRule.scenario.onActivity { clip=it.getSystemService(ClipboardManager::class.java).primaryClip; it.getSystemService(ClipboardManager::class.java).resetClipboardForTest() }
        fun capture(name:String,marker:String) {
            compose.waitForIdle(); device.waitForIdle()
            val dir=File(context.filesDir,"extension-grid-evidence").apply{mkdirs()}
            testAutomation().clearCacheForTest(); device.dumpWindowHierarchy(File(dir,"$name.xml"))
            assertTrue(File(dir,"$name.xml").readText().contains(marker))
            assertTrue(device.takeScreenshot(File(dir,"$name.png")))
        }
        try {
            compose.waitUntil(10000) {!compose.activity.store.loading}
            compose.activityRule.scenario.onActivity { it.store.reset(); it.store.filter("Apps"); it.store.editing=null; it.store.dialog="note" }
            compose.onNodeWithTag("editor-title").performTextReplacement("Inline draft")
            compose.onNodeWithTag("editor-body").performTextReplacement("This draft stays inside the palette.")
            compose.activityRule.scenario.onActivity { androidx.core.view.WindowCompat.getInsetsController(it.window,it.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
            compose.onNodeWithTag("editor-title").performScrollTo()
            capture("before-editor","Inline draft")
            compose.activityRule.scenario.onActivity {
                it.store.dialog=null; it.store.filter("All")
                it.store.save("notes","Action search note","Exact action payload 271828")
                it.store.search("Action search note")
            }
            compose.onNodeWithTag("actions-button").performClick()
            capture("before-actions","Copy content")
        } finally {
            compose.activityRule.scenario.onActivity { it.store.dialog=null; it.store.extensions.close(); clip?.let(it.getSystemService(ClipboardManager::class.java)::setPrimaryClip) ?: it.getSystemService(ClipboardManager::class.java).resetClipboardForTest() }
            restorePalettePreferences(context,original)
        }
    }
}
