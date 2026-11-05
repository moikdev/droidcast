package dev.tinycast.android

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.KeyCharacterMap
import android.view.InputDevice
import android.view.Window
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextDecoration
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** Explicit pointer actions; editor replacement and native composition are labeled setup. */
@RunWith(AndroidJUnit4::class)
class TouchSearchDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val name = org.junit.rules.TestName()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private val evidence get() = File(context.filesDir, "touch-search-evidence")
    private var palette = emptyMap<String, Any?>()
    private var prepared = false
    private val events = JSONArray()
    private val record = JSONObject().put("scope", "Owned launcher/Emoji pointer Clear; editor and IME visibility setup are controlled, not physical typing")
    private fun ui(block: (MainActivity) -> Unit) = compose.activityRule.scenario.onActivity { block(it) }
    private fun node(tag: String) = compose.onNodeWithTag(tag)
    private fun rect(tag: String) = node(tag).fetchSemanticsNode().boundsInWindow
    private fun json(rect: Rect) = JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))
    private fun ime() = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true

    @Before fun prepare() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Use the owned touch-search runner", args.getString("touchSearchFixture") == "true")
        val nonce = requireNotNull(args.getString("touchSearchOwner"))
        assertTrue(nonce.matches(Regex("[0-9a-f]{32}")))
        val owner = JSONObject(File(context.filesDir, "touch-search-lease/owner.json").readText())
        assertEquals("touch-search-v1", owner.getString("kind"))
        assertEquals(nonce, owner.getString("nonce"))
        assertEquals("touch-search-$nonce", owner.getString("extension_id"))
        assertEquals(Build.VERSION.SDK_INT == 26, owner.getBoolean("fresh_disposable_avd"))
        val font = requireNotNull(args.getString("touchSearchFontScale")).toFloat()
        compose.waitUntil(10000) { kotlin.math.abs(compose.activity.resources.configuration.fontScale - font) < .01f }
        compose.waitUntil(15000) { !compose.activity.store.loading && !compose.activity.store.emoji.loading }
        palette = context.getSharedPreferences("palette", Context.MODE_PRIVATE).all.toMap()
        prepared = true
        // Empty-query captures must not reveal unrelated saved notes or clips.
        ui { it.store.reset(); it.store.filter("Apps") }
        record.put("case", name.methodName).put("api", Build.VERSION.SDK_INT).put("font_scale", font)
    }

    @After fun restore() {
        if (!prepared) return
        restorePalettePreferences(context, palette)
        evidence.mkdirs()
        File(evidence, "${name.methodName.lowercase()}-case.json").writeText(record.put("events", events).toString(2))
    }

    private fun keyboard(visible: Boolean, search: String) {
        node(search).assertIsFocused()
        if (visible && compose.activity.resources.configuration.screenWidthDp >= 700) {
            compose.onNodeWithContentDescription("Show on-screen keyboard").performTouchInput { click(center) }
        } else ui { activity ->
            val view = requireNotNull(activity.window.decorView.findFocus())
            val controller = ViewCompat.getWindowInsetsController(activity.window.decorView)
            val imm = activity.getSystemService(InputMethodManager::class.java)
            if (visible) { imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT); controller?.show(WindowInsetsCompat.Type.ime()) }
            else { imm.hideSoftInputFromWindow(view.windowToken, 0); controller?.hide(WindowInsetsCompat.Type.ime()) }
        }
        compose.waitUntil(10000) { ime() == visible }
        compose.waitForIdle()
        events.put(JSONObject().put("kind", "explicit-ime-visibility-setup").put("visible", visible))
    }

    private fun text(tag: String, expected: String) {
        node(tag).assert(editableCharactersExactly(expected))
    }

    private fun cleared(tag: String, clear: String) {
        compose.waitForIdle()
        text(tag, "")
        node(tag).assertIsFocused().assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange.Zero))
        node(clear).assertDoesNotExist()
        val layouts = mutableListOf<TextLayoutResult>()
        node(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertTrue(layouts.single().layoutInput.text.spanStyles.isEmpty())
    }

    private fun geometry(search: String, clear: String, palette: String, adjacent: List<String>): Rect {
        node(clear).assertIsDisplayed().assertHasClickAction().assertContentDescriptionEquals("Clear search")
        val target = rect(clear); val editor = rect(search); val surface = rect(palette)
        val dp = compose.activity.resources.displayMetrics.density
        assertTrue("Clear has a complete 48 dp target", target.width >= 48 * dp - 1 && target.height >= 48 * dp - 1)
        assertTrue("Clear is fully within the palette", target.left >= surface.left && target.right <= surface.right && target.top >= surface.top && target.bottom <= surface.bottom)
        assertTrue("Search keeps at least 80 dp", editor.width >= 80 * dp - 1)
        assertTrue("Clear does not overlap the editor", editor.right <= target.left + 1)
        for (tag in adjacent) {
            val other = rect(tag)
            assertFalse("Clear does not overlap $tag", target.overlaps(other))
        }
        events.put(JSONObject().put("kind", "target-geometry").put("tag", clear).put("target", json(target))
            .put("search", json(editor)).put("palette", json(surface)).put("density", dp))
        return editor
    }

    private fun capture(phase: String, search: String, boundsRole: String = "search") {
        compose.waitForIdle(); instrumentation.waitForIdleSync(); device.waitForIdle()
        evidence.mkdirs()
        val stem = "${name.methodName.lowercase()}-$phase"
        testAutomation().clearCacheForTest()
        device.dumpWindowHierarchy(File(evidence, "$stem.xml"))
        val bounds = rect(search)
        assertTrue(device.takeScreenshot(File(evidence, "$stem.png")))
        File(evidence, "$stem-frame.json").writeText(JSONObject().put("phase", phase).put(boundsRole, json(bounds))
            .put("ime_visible", ime()).put("api", Build.VERSION.SDK_INT)
            .put("font_scale", compose.activity.resources.configuration.fontScale)
            .put("synchronization", "Compose and UiAutomator idle; hierarchy before PNG; XML geometry is not independent visibility proof")
            .put("scope", record.getString("scope")).toString(2))
    }

    private fun emoji() {
        ui { it.execute(Command("emoji", "Emoji & Symbols", "")) }
        node("emoji-search").assertIsFocused()
    }

    /** Observe delivery without adding a dispatcher callback or changing key handling. */
    private fun observeBackWindow(ownedQuery: String = "face", block: () -> Unit) {
        var original: Window.Callback? = null
        var observer: Window.Callback? = null
        var originalTraceProperty: String? = null
        var traceClaimed = false
        try {
            ui { activity ->
                originalTraceProperty = System.getProperty(PaletteBackTrace.property)
                check(originalTraceProperty != "true") { "An existing Back trace has another owner" }
                check(PaletteBackTrace.snapshot().events.isEmpty())
                System.setProperty(PaletteBackTrace.property, "true")
                traceClaimed = true
                val previous = activity.window.callback
                original = previous
                fun sample(stage: String, event: KeyEvent, handled: Boolean? = null) {
                    // This probe owns only this literal query; do not collect unrelated text.
                    val query = if (activity.store.dialog == "emoji") activity.store.emoji.query else activity.store.query
                    check(query == ownedQuery || query.isEmpty())
                    if (events.length() < 256) events.put(JSONObject().put("kind", stage)
                        .put("action", event.action).put("key_code", event.keyCode)
                        .put("down_time", event.downTime).put("event_time", event.eventTime)
                        .put("device_id", event.deviceId).put("source", event.source)
                        .put("repeat", event.repeatCount).put("canceled", event.isCanceled)
                        .put("handled", handled ?: JSONObject.NULL)
                        .put("window_focused", activity.window.decorView.hasWindowFocus())
                        .put("menu_bookkeeping_open", activity.store.paletteMenuOpen)
                        .put("query", query).put("route", activity.store.dialog))
                }
                val installed = object : Window.Callback by previous {
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                        if (event.keyCode != KeyEvent.KEYCODE_BACK) return previous.dispatchKeyEvent(event)
                        sample("back-window-before", event)
                        val handled = previous.dispatchKeyEvent(event)
                        sample("back-window-after", event, handled)
                        return handled
                    }
                }
                observer = installed
                activity.window.callback = installed
            }
            block()
        } finally {
            if (traceClaimed) ui { activity ->
                try {
                    val trace = PaletteBackTrace.snapshot()
                    val samples = JSONArray(trace.events.map { sample ->
                        JSONObject().put("stage", sample.stage).put("uptime_ms", sample.uptime)
                            .put("action", sample.action ?: JSONObject.NULL)
                            .put("down_time", sample.downTime ?: JSONObject.NULL)
                            .put("event_time", sample.eventTime ?: JSONObject.NULL)
                            .put("repeat", sample.repeat ?: JSONObject.NULL)
                            .put("canceled", sample.canceled ?: JSONObject.NULL)
                            .put("menu_open", sample.menuOpen ?: JSONObject.NULL)
                            .put("query_length", sample.queryLength ?: JSONObject.NULL)
                            .put("handled", sample.handled ?: JSONObject.NULL)
                    })
                    events.put(JSONObject().put("kind", "back-stage-trace").put("enabled", trace.enabled)
                        .put("dropped", trace.dropped).put("samples", samples))
                    assertTrue("The owned debug trace must be enabled", trace.enabled)
                    assertEquals("No Back stage witness may be evicted", 0, trace.dropped)
                } finally {
                    try {
                        assertTrue("Restore only the owned window observer", activity.window.callback === observer || activity.window.callback === original)
                        if (activity.window.callback === observer) activity.window.callback = requireNotNull(original)
                        events.put(JSONObject().put("kind", "back-window-observer-restored").put("passed", activity.window.callback === original))
                    } finally {
                        PaletteBackTrace.clear()
                        originalTraceProperty?.let { System.setProperty(PaletteBackTrace.property, it) }
                            ?: System.clearProperty(PaletteBackTrace.property)
                        val restored = System.getProperty(PaletteBackTrace.property) == originalTraceProperty &&
                            PaletteBackTrace.snapshot().events.isEmpty()
                        events.put(JSONObject().put("kind", "back-trace-property-restored").put("passed", restored))
                        assertTrue(restored)
                    }
                }
            }
        }
    }

    private fun emptyFeedback(node: SemanticsNodeInteraction, palette: String, keyboardVisible: Boolean) {
        val height = rect(palette).height
        val constrained = keyboardVisible && height < 180 * compose.activity.resources.displayMetrics.density
        val displayed = try { node.assertIsDisplayed(); true } catch (_: AssertionError) { false }
        events.put(JSONObject().put("kind", "empty-feedback-visibility").put("palette_height_px", height)
            .put("constrained_by_ime", constrained).put("displayed", displayed)
            .put("known_layout_gap", if (constrained && !displayed) "#111" else JSONObject.NULL))
        // #111 retains the actual clipped-body failures. This contract exercises
        // Clear in the header even when the IME leaves too little body space.
        // Outside that measured condition, feedback must still be displayed.
        if (!constrained) node.assertIsDisplayed()
    }

    @Test fun launcherTouchClearPreservesFocusCategoryAndKeyboard() {
        ui { it.store.filter("Apps") }
        for (visible in listOf(false, true)) {
            node("search").performTextReplacement("zzownednomatchingapp")
            keyboard(visible, "search")
            ui { events.put(JSONObject().put("kind", "empty-state-before-clear").put("search", "search")
                .put("ime_visible", visible).put("expected_query_matches", it.store.query == "zzownednomatchingapp")
                .put("result_count", it.store.results().size))
                assertTrue("Owned launcher query must remain exact", it.store.query == "zzownednomatchingapp")
                assertTrue("Owned launcher query must produce no results", it.store.results().isEmpty()) }
            capture("${if (visible) "shown" else "hidden"}-before", "search")
            emptyFeedback(compose.onNodeWithText("No matching commands"), "palette", visible)
            val before = geometry("search", "search-clear", "palette", emptyList())
            node("search-clear").performTouchInput { click(center) }
            cleared("search", "search-clear")
            assertEquals(before, rect("search")); assertEquals(visible, ime())
            ui { assertEquals("Apps", it.store.category); assertEquals(0, it.store.selected); assertNull(it.store.dialog); assertTrue(it.store.results().isNotEmpty()) }
            capture("${if (visible) "shown" else "hidden"}-after", "search")
            assertEquals(visible, ime())
        }
        record.put("completed", true)
    }

    @Test fun emojiTouchClearPreservesCategoryGeometryAndKeyboard() {
        ui { it.store.search("Owned launcher caller") }
        emoji()
        val origin = rect("emoji-search")
        val longest = compose.activity.store.emoji.entries.map { it.category }.distinct().maxBy { it.length }
        ui { it.store.emoji.chooseFilter(longest) }
        for (visible in listOf(false, true)) {
            node("emoji-search").performTextReplacement("zzownednomatchingemoji")
            keyboard(visible, "emoji-search")
            ui { events.put(JSONObject().put("kind", "empty-state-before-clear").put("search", "emoji-search")
                .put("ime_visible", visible).put("expected_query_matches", it.store.emoji.query == "zzownednomatchingemoji")
                .put("result_count", it.store.emoji.results().size))
                assertTrue("Owned Emoji query must remain exact", it.store.emoji.query == "zzownednomatchingemoji")
                assertTrue("Owned Emoji query must produce no results", it.store.emoji.results().isEmpty()) }
            capture("${if (visible) "shown" else "hidden"}-before", "emoji-search")
            emptyFeedback(node("emoji-empty"), "emoji-palette", visible)
            val before = geometry("emoji-search", "emoji-search-clear", "emoji-palette", listOf("emoji-back", "emoji-filter"))
            node("emoji-search-clear").performTouchInput { click(center) }
            cleared("emoji-search", "emoji-search-clear")
            assertEquals(before, rect("emoji-search")); assertEquals(visible, ime())
            ui { assertEquals(longest, it.store.emoji.filter); assertEquals(0, it.store.emoji.selected); assertEquals("emoji", it.store.dialog); assertTrue(it.store.emoji.results().isNotEmpty()); assertEquals("Owned launcher caller", it.store.query) }
            capture("${if (visible) "shown" else "hidden"}-after", "emoji-search")
            assertEquals(visible, ime())
        }
        node("emoji-back").performTouchInput { click(center) }
        text("search", "Owned launcher caller"); node("search").assertIsFocused()
        val launcher = rect("search")
        assertEquals("Returning retains the search column", origin.left, launcher.left, 1f)
        assertEquals("Returning retains the search baseline", origin.top, launcher.top, 1f)
        assertEquals("Both editors have the same natural height", origin.height, launcher.height, 1f)
        events.put(JSONObject().put("kind", "emoji-launcher-origin").put("emoji", json(origin)).put("launcher", json(launcher)))
        capture("caller-return", "search")
        record.put("completed", true)
    }

    private fun withInput(tag: String, expected: String, block: (InputConnection) -> Unit) {
        var connection: InputConnection? = null
        compose.waitUntil(5000) {
            ui {
                val candidate = it.window.decorView.findFocus()?.onCreateInputConnection(EditorInfo())
                if (candidate?.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString() == expected) connection = candidate
                else candidate?.closeConnection()
            }
            connection != null
        }
        try { block(requireNotNull(connection)) } finally { ui { connection?.closeConnection() } }
    }

    @Test fun pointerClearRemovesControlledCompositionAndWhitespace() {
        for (emojiRoute in listOf(false, true)) {
            if (emojiRoute) emoji()
            val search = if (emojiRoute) "emoji-search" else "search"
            val clear = if (emojiRoute) "emoji-search-clear" else "search-clear"
            keyboard(false, search)
            withInput(search, "") { connection ->
                ui { assertTrue(connection.setComposingText("にほん", 1)) }
                text(search, "にほん")
                val layouts = mutableListOf<TextLayoutResult>()
                node(search).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
                assertTrue("Controlled composition is actually exposed before Clear", layouts.single().layoutInput.text.spanStyles.any { it.item.textDecoration == TextDecoration.Underline })
                capture("${if (emojiRoute) "emoji" else "launcher"}-composition", search)
                node(clear).performTouchInput { click(center) }
                cleared(search, clear)
                capture("${if (emojiRoute) "emoji" else "launcher"}-cleared", search)
            }
            node(search).performTextReplacement("   ")
            node(clear).assertIsDisplayed().performTouchInput { click(center) }
            cleared(search, clear)
            events.put(JSONObject().put("kind", "controlled-composition-and-whitespace-cleared").put("search", search))
        }
        record.put("completed", true)
    }

    private fun callback(tag: String) = requireNotNull(node(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action)

    @Test fun retainedClearCallbacksRejectReplacedSurfacesAndMenus() {
        node("search").performTextReplacement("Owned launcher query")
        val launcher = callback("search-clear")
        ui { it.store.dialog = "updates"; launcher(); assertEquals("Owned launcher query", it.store.query) }
        node("inline-back").performTouchInput { click(center) }
        ui { it.store.search("Owned replacement query"); launcher(); assertEquals("Owned replacement query", it.store.query) }
        val current = callback("search-clear")
        node("palette-menu").performTouchInput { click(center) }
        node("inline-menu").assertIsDisplayed()
        ui { current(); assertEquals("Owned replacement query", it.store.query) }
        capture("covered-by-menu", "search")
        device.pressBack(); compose.waitForIdle()
        emoji()
        node("emoji-search").performTextReplacement("face")
        val oldEmoji = callback("emoji-search-clear")
        ui { it.store.dialog = "updates"; oldEmoji(); assertEquals("face", it.store.emoji.query) }
        node("inline-back").assertIsDisplayed()
        ui { it.store.dialog = "emoji"; it.store.emoji.search("happy") }
        node("emoji-search").assertIsDisplayed()
        ui { oldEmoji(); assertEquals("happy", it.store.emoji.query) }
        capture("replacement-emoji", "emoji-search")
        record.put("rejected_callbacks", 5).put("completed", true)
    }

    @Test fun currentClearUsesLiveQueryAndRecreationRejectsOldCallback() {
        node("search").performTextReplacement("Owned initial query")
        val clear = callback("search-clear")
        ui { it.store.search("Owned changed before redraw"); clear(); assertEquals("", it.store.query) }
        cleared("search", "search-clear")
        node("search").performTextReplacement("Owned before recreation")
        val old = callback("search-clear")
        compose.activityRule.scenario.recreate()
        node("search").assertIsFocused()
        ui { it.store.search("Owned after recreation"); old(); assertEquals("Owned after recreation", it.store.query) }
        val epoch = callback("search-clear")
        ui { it.store.filter("Apps"); it.store.search("Owned after epoch"); epoch(); assertEquals("Owned after epoch", it.store.query) }
        emoji()
        node("emoji-search").performTextReplacement("face")
        val emojiClear = callback("emoji-search-clear")
        ui { it.store.emoji.search("happy"); emojiClear(); assertEquals("", it.store.emoji.query) }
        cleared("emoji-search", "emoji-search-clear")
        record.put("live_query_clears", 2).put("rejected_callbacks", 2).put("completed", true)
    }

    private fun settledFeedbackViewport(paletteTag: String) {
        var last: Pair<Rect, Boolean>? = null
        var since = 0L
        compose.waitUntil(5000) {
            val sample = rect(paletteTag) to ime()
            val now = SystemClock.elapsedRealtime()
            if (sample != last) { last = sample; since = now; false } else now - since >= 300
        }
    }

    private fun foregroundFeedback() {
        ui { assertTrue("Feedback actions require the app's foreground window", it.window.decorView.hasWindowFocus()) }
    }

    private fun completeFeedbackTarget(target: SemanticsNodeInteraction, label: String, paletteTag: String) {
        val semantics = target.fetchSemanticsNode()
        val surface = node(paletteTag).fetchSemanticsNode().boundsInRoot
        val sample = compose.runOnUiThread {
            val origin = semantics.positionInRoot
            Rect(origin.x, origin.y, origin.x + semantics.size.width, origin.y + semantics.size.height) to semantics.boundsInRoot
        }
        val full = sample.first; val visible = sample.second
        val dp = context.resources.displayMetrics.density
        events.put(JSONObject().put("kind", "feedback-control").put("label", label)
            .put("full", json(full)).put("visible", json(visible)).put("palette", json(surface)))
        assertTrue("$label retains 48 dp", full.width >= 48 * dp - 1 && full.height >= 48 * dp - 1)
        assertTrue("$label is complete in its palette", full.left >= surface.left - 1 && full.right <= surface.right + 1 &&
            full.top >= surface.top - 1 && full.bottom <= surface.bottom + 1 &&
            visible.width >= full.width - 1 && visible.height >= full.height - 1)
    }

    private fun naturalFeedback(text: String, fontSp: Float, paletteTag: String, phase: String) {
        val target = compose.onNodeWithText(text)
        val layouts = mutableListOf<TextLayoutResult>()
        target.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        val semantics = target.fetchSemanticsNode()
        val surface = node(paletteTag).fetchSemanticsNode().boundsInRoot
        val sample = compose.runOnUiThread { semantics.positionInRoot to semantics.boundsInRoot }
        assertEquals(text, layout.layoutInput.text.text)
        assertEquals("Keep the original status font", fontSp, layout.layoutInput.style.fontSize.value, .01f)
        assertTrue(layout.lineCount > 0)
        for (line in 0 until layout.lineCount) {
            val bounds = Rect(sample.first.x + layout.getLineLeft(line), sample.first.y + layout.getLineTop(line),
                sample.first.x + layout.getLineRight(line), sample.first.y + layout.getLineBottom(line))
            val visible = sample.second
            val complete = bounds.left >= visible.left - 1 && bounds.right <= visible.right + 1 &&
                bounds.top >= visible.top - 1 && bounds.bottom <= visible.bottom + 1 &&
                bounds.left >= surface.left - 1 && bounds.right <= surface.right + 1 &&
                bounds.top >= surface.top - 1 && bounds.bottom <= surface.bottom + 1 &&
                visible.width > 0 && visible.height > 0
            events.put(JSONObject().put("kind", "natural-feedback-line").put("phase", phase).put("text", text)
                .put("line", line).put("font_sp", fontSp).put("line_bounds", json(bounds))
                .put("declared_line_height", layout.layoutInput.style.lineHeight.toString())
                .put("visible", json(visible)).put("palette", json(surface)).put("complete", complete)
                .put("ellipsized", layout.isLineEllipsized(line)))
            assertFalse("Primary empty feedback must not ellipsize", layout.isLineEllipsized(line))
            assertTrue("Primary empty feedback line $line is fully visible", complete)
        }
    }

    private fun panFeedback(up: Boolean, paletteTag: String) {
        foregroundFeedback()
        val route = node("palette-content-scroll")
        val before = route.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        route.performTouchInput {
            val x = width * .65f
            swipeWithVelocity(androidx.compose.ui.geometry.Offset(x, height * if (up) .85f else .15f),
                androidx.compose.ui.geometry.Offset(x, height * if (up) .15f else .85f), endVelocity = 0f, durationMillis = 400)
        }
        settledFeedbackViewport(paletteTag)
        val after = route.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        events.put(JSONObject().put("kind", "feedback-pointer-pan").put("up", up).put("before", before).put("after", after))
    }

    private fun feedbackTop(paletteTag: String) {
        repeat(16) {
            val range = node("palette-content-scroll").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            if (range.value() <= 1) return
            panFeedback(false, paletteTag)
        }
        fail("Pointer panning must return the header to its original scroll origin")
    }

    private fun feedbackReveal(target: SemanticsNodeInteraction, paletteTag: String) {
        repeat(16) {
            val semantics = target.fetchSemanticsNode()
            val surface = node(paletteTag).fetchSemanticsNode().boundsInRoot
            val full = compose.runOnUiThread {
                val origin = semantics.positionInRoot
                Rect(origin.x, origin.y, origin.x + semantics.size.width, origin.y + semantics.size.height)
            }
            if (full.top >= surface.top - 1 && full.bottom <= surface.bottom + 1) return
            panFeedback(full.bottom > surface.bottom, paletteTag)
        }
        fail("A pointer scroll must expose the complete control")
    }

    private fun feedbackFlow(emojiRoute: Boolean) {
        if (emojiRoute) emoji()
        val search = if (emojiRoute) "emoji-search" else "search"
        val clear = if (emojiRoute) "emoji-search-clear" else "search-clear"
        val surface = if (emojiRoute) "emoji-palette" else "palette"
        val absent = if (emojiRoute) "zzownedemptyfeedbackemoji" else "zzownedemptyfeedbackapp"
        val message = if (emojiRoute) "No matching emoji or symbols" else "No matching commands"
        var origin: Rect? = null
        for (visible in listOf(false, true)) {
            feedbackTop(surface)
            node(search).performTextReplacement(absent)
            keyboard(visible, search); settledFeedbackViewport(surface)
            capture("feedback-${if(visible)"shown"else"hidden"}-empty", search)
            foregroundFeedback()
            if (origin == null) origin = rect(search) else assertEquals("Opening the IME retains the search origin", origin, rect(search))
            text(search, absent); node(search).assertIsFocused()
            ui { if(emojiRoute)assertTrue(it.store.emoji.results().isEmpty())else assertTrue(it.store.results().isEmpty()) }
            geometry(search, clear, surface, if(emojiRoute)listOf("emoji-back", "emoji-filter")else emptyList())
            naturalFeedback(message, if(emojiRoute)13f else 14f, surface, "initial-${if(visible)"shown"else"hidden"}")
            if(rect(surface).height < 180 * context.resources.displayMetrics.density) {
                if(emojiRoute) node("emoji-count").assertDoesNotExist()
                else compose.onNodeWithText("Try another name or category.").assertDoesNotExist()
                events.put(JSONObject().put("kind", "compact-primary-status-only").put("route", if(emojiRoute)"emoji"else"launcher"))
            }
            if (emojiRoute) {
                completeFeedbackTarget(node("emoji-back"), "Emoji Back", surface)
                completeFeedbackTarget(node("emoji-filter"), "Emoji category", surface)
            }
            if (compose.activity.resources.configuration.screenWidthDp >= 700)
                completeFeedbackTarget(compose.onNodeWithContentDescription(if (visible) "Hide on-screen keyboard" else "Show on-screen keyboard"), "Keyboard", surface)
            val footer = if(emojiRoute)node("emoji-favorite")else node("palette-menu")
            feedbackReveal(footer, surface)
            completeFeedbackTarget(footer, if(emojiRoute)"Emoji favorite"else"Droidcast menu", surface)
            capture("feedback-${if(visible)"shown"else"hidden"}-footer", search)
            feedbackTop(surface)
            node(search).assertIsFocused().assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(absent.length)))
            assertEquals(visible, ime()); assertEquals(origin, rect(search))
            node(search).performTextReplacement(if(emojiRoute)"face"else"settings")
            compose.onNodeWithText(message).assertDoesNotExist()
            ui { if(emojiRoute)assertTrue(it.store.emoji.results().isNotEmpty())else assertTrue(it.store.results().isNotEmpty()) }
            capture("feedback-${if(visible)"shown"else"hidden"}-matching", search)
            foregroundFeedback(); node(clear).performTouchInput { click(center) }
            cleared(search, clear); assertEquals(origin, rect(search)); assertEquals(visible, ime())
            ui { if(emojiRoute)assertEquals(0,it.store.emoji.selected)else { assertEquals("Apps",it.store.category);assertEquals(0,it.store.selected) } }
            capture("feedback-${if(visible)"shown"else"hidden"}-cleared", search)
        }
        record.put("completed", true)
    }

    @Test fun launcherEmptyFeedbackIsReadableAboveImeAndControlsRemainReachable() = feedbackFlow(false)
    @Test fun emojiEmptyFeedbackIsReadableAboveImeAndControlsRemainReachable() = feedbackFlow(true)

    private fun keyboardControl() = compose.onNode(
        hasContentDescription("Show on-screen keyboard") or hasContentDescription("Hide on-screen keyboard"))

    private fun keyboardState(search: String, query: String, range: TextRange, emojiRoute: Boolean, phase: String) {
        text(search, query)
        node(search).assertIsFocused().assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, range))
        ui {
            assertTrue("Keyboard controls need the owned foreground window", it.window.decorView.hasWindowFocus())
            if (emojiRoute) {
                assertEquals("emoji", it.store.dialog)
                assertEquals("All", it.store.emoji.filter)
                assertEquals(query, it.store.emoji.query)
                assertEquals(0, it.store.emoji.selected)
            } else {
                assertNull(it.store.dialog)
                assertEquals("Apps", it.store.category)
                assertEquals(query, it.store.query)
                assertEquals(0, it.store.selected)
            }
        }
        events.put(JSONObject().put("kind", "keyboard-search-state").put("phase", phase)
            .put("query", query).put("range", JSONArray(listOf(range.start, range.end)))
            .put("focused", true).put("ime_visible", ime()).put("route", if (emojiRoute) "emoji" else "launcher"))
    }

    private fun pointerKeyboard(visible: Boolean, search: String, surface: String, phase: String, checkLabel: Boolean = true) {
        val before = ime()
        assertEquals("Start with the opposite actual IME state", !visible, before)
        completeFeedbackTarget(keyboardControl(), "Keyboard", surface)
        val label = keyboardControl().fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        keyboardControl().performTouchInput { click(center) }
        var reached = true
        try { compose.waitUntil(6000) { ime() == visible } }
        catch (_: ComposeTimeoutException) { reached = false }
        settledFeedbackViewport(surface)
        // Retain the actual post-tap screen even when the old Show-only action
        // leaves the visible IME unchanged. A new label is not the control gate.
        capture(phase, search)
        events.put(JSONObject().put("kind", "keyboard-pointer-action").put("phase", phase)
            .put("label_before", label).put("ime_before", before).put("expected_visible", visible)
            .put("ime_after", ime()).put("reached_expected_state", reached))
        assertTrue(if (visible) "Header keyboard action must reopen the IME" else "Header keyboard action must hide the visible IME", reached)
        if (checkLabel) keyboardControl().assertContentDescriptionEquals(if (visible) "Hide on-screen keyboard" else "Show on-screen keyboard")
    }

    private fun keyboardResultTitle(value: String, surface: String) {
        val target = compose.onNodeWithText(value, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        target.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single(); val semantics = target.fetchSemanticsNode()
        val origin = compose.runOnUiThread { semantics.positionInRoot }
        val visible = semantics.boundsInRoot; val palette = node(surface).fetchSemanticsNode().boundsInRoot
        assertEquals(value, layout.layoutInput.text.text)
        assertEquals(14f, layout.layoutInput.style.fontSize.value, .01f)
        for (line in 0 until layout.lineCount) {
            val bounds = Rect(origin.x + layout.getLineLeft(line), origin.y + layout.getLineTop(line),
                origin.x + layout.getLineRight(line), origin.y + layout.getLineBottom(line))
            val complete = bounds.left >= visible.left - 1 && bounds.right <= visible.right + 1 &&
                bounds.top >= visible.top - 1 && bounds.bottom <= visible.bottom + 1 &&
                bounds.left >= palette.left - 1 && bounds.right <= palette.right + 1 &&
                bounds.top >= palette.top - 1 && bounds.bottom <= palette.bottom + 1
            events.put(JSONObject().put("kind", "keyboard-result-line").put("text", value).put("line", line)
                .put("full", json(bounds)).put("visible", json(visible)).put("palette", json(palette))
                .put("complete", complete).put("ellipsized", layout.isLineEllipsized(line)))
            assertFalse("Revealed result title must not ellipsize", layout.isLineEllipsized(line))
            assertTrue("Revealed result title line is complete", complete)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun keyboardToggleFlow(emojiRoute: Boolean) {
        assumeTrue("This contract exercises the existing wide-screen keyboard slot", compose.activity.resources.configuration.screenWidthDp >= 700)
        record.put("scope", "Owned header pointer Hide/Show; editor/range and initial IME setup are controlled. Selected result target, not full list/grid typography or physical typing.")
        if (emojiRoute) { emoji(); ui { it.store.emoji.chooseFilter("All") } }
        val search = if (emojiRoute) "emoji-search" else "search"
        val surface = if (emojiRoute) "emoji-palette" else "palette"
        for ((index, query) in listOf(if (emojiRoute) "face" else "settings", "").withIndex()) {
            feedbackTop(surface); keyboard(false, search)
            node(search).performTextReplacement(query)
            settledFeedbackViewport(surface)
            // Initial opening is an explicit pointer contract too. Delay the
            // new state-label assertion until the old app has attempted Hide.
            pointerKeyboard(true, search, surface, "keyboard-$index-initial-show", checkLabel = false)
            val range = if (query.isEmpty()) TextRange.Zero else TextRange(1, minOf(5, query.length - 1))
            node(search).performTextInputSelection(range)
            keyboardState(search, query, range, emojiRoute, "before-$index")
            val origin = rect(search); val beforeSurface = rect(surface)
            capture("keyboard-$index-before-hide", search)
            pointerKeyboard(false, search, surface, "keyboard-$index-after-hide")
            keyboardState(search, query, range, emojiRoute, "hidden-$index")
            assertEquals("Hide retains search geometry", origin, rect(search))
            val button = keyboardControl().fetchSemanticsNode().boundsInWindow
            assertFalse("Keyboard target and editor do not overlap", button.overlaps(rect(search)))
            assertTrue("Editor retains at least 80 dp", rect(search).width >= 80 * context.resources.displayMetrics.density - 1)
            if (query.isNotEmpty()) {
                if (emojiRoute) {
                    var id = ""
                    ui { id = it.store.emoji.results()[it.store.emoji.selected].id }
                    completeFeedbackTarget(node("emoji-cell-$id"), "Selected Emoji result", surface)
                } else {
                    var id = ""
                    ui { id = it.store.results()[it.store.selected].id }
                    completeFeedbackTarget(node("result-$id"), "Selected launcher result", surface)
                    keyboardResultTitle("Settings", surface)
                }
            }
            events.put(JSONObject().put("kind", "keyboard-result-viewport").put("query", query)
                .put("before", json(beforeSurface)).put("after", json(rect(surface))))
            pointerKeyboard(true, search, surface, "keyboard-$index-after-show")
            keyboardState(search, query, range, emojiRoute, "shown-$index")
            assertEquals("Show retains search geometry", origin, rect(search))
        }
        record.put("completed", true)
    }

    @Test fun launcherKeyboardToggleRevealsResultsAndPreservesSearch() = keyboardToggleFlow(false)
    @Test fun emojiKeyboardToggleRevealsResultsAndPreservesSearch() = keyboardToggleFlow(true)

    private fun rejectedKeyboardCallback(
        action: () -> Boolean, expectedVisible: Boolean, search: String, surface: String, phase: String
    ) {
        var accepted = true
        ui { accepted = action() }
        compose.waitForIdle(); instrumentation.waitForIdleSync()
        settledFeedbackViewport(surface)
        capture(phase, search)
        assertEquals("Obsolete or obstructed keyboard callback must not change visibility: $phase", expectedVisible, ime())
        assertFalse("The published action must report rejection", accepted)
        events.put(JSONObject().put("kind", "keyboard-callback-rejected").put("phase", phase)
            .put("visible", expectedVisible).put("accepted", accepted))
    }

    private fun dismissKeyboardMenuByPointer(search: String, surface: String) {
        val menu = node("inline-menu").fetchSemanticsNode().boundsInRoot
        val editor = node(search).fetchSemanticsNode().boundsInRoot
        val palette = node(surface).fetchSemanticsNode().boundsInRoot
        val inset = 12 * context.resources.displayMetrics.density
        val candidates = listOf(
            androidx.compose.ui.geometry.Offset(editor.left + minOf(inset, editor.width / 2), editor.center.y),
            androidx.compose.ui.geometry.Offset(editor.right - minOf(inset, editor.width / 2), editor.center.y))
        val point = candidates.firstOrNull { p -> palette.contains(p) && !menu.contains(p) }
        assertNotNull("An owned palette backdrop point remains outside the menu", point)
        compose.onRoot().performTouchInput { click(requireNotNull(point)) }
        node("inline-menu").assertDoesNotExist()
        events.put(JSONObject().put("kind", "keyboard-menu-pointer-dismissal")
            .put("point", JSONArray(listOf(point!!.x, point.y))).put("menu", json(menu)).put("palette", json(palette)))
    }

    private fun keyboardCallbackFlow(emojiRoute: Boolean) {
        assumeTrue("Owned wide-screen callback contract", compose.activity.resources.configuration.screenWidthDp >= 700)
        record.put("scope", "Controlled retained native callbacks; visibility and menu-backdrop dismissal use actual pointers. Injected system Back failure remains #115; no queued-input or cross-window certification.")
        if (emojiRoute) { emoji(); ui { it.store.emoji.chooseFilter("All") } }
        val search = if (emojiRoute) "emoji-search" else "search"
        val surface = if (emojiRoute) "emoji-palette" else "palette"
        val query = if (emojiRoute) "face" else "settings"
        keyboard(false, search); node(search).performTextReplacement(query); settledFeedbackViewport(surface)
        pointerKeyboard(true, search, surface, "keyboard-guard-first-show")
        val oldHide = requireNotNull(keyboardControl().fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        pointerKeyboard(false, search, surface, "keyboard-guard-live-hide")
        pointerKeyboard(true, search, surface, "keyboard-guard-live-show")
        rejectedKeyboardCallback(oldHide, true, search, surface, "keyboard-guard-obsolete-mode")

        // Change the epoch and invoke before Compose can dispose the old owner.
        val beforeEpoch = requireNotNull(keyboardControl().fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        var epochAccepted = true
        ui { it.store.focusEpoch++; epochAccepted = beforeEpoch() }
        settledFeedbackViewport(surface)
        assertTrue("Changed search epoch rejects Hide before disposal", ime())
        assertFalse("Changed epoch reports rejection before disposal", epochAccepted)
        capture("keyboard-guard-epoch", search)
        events.put(JSONObject().put("kind", "keyboard-callback-rejected").put("phase", "changed-epoch-before-disposal").put("visible", true).put("accepted", epochAccepted))

        pointerKeyboard(false, search, surface, "keyboard-guard-before-menu")
        val obstructedShow = requireNotNull(keyboardControl().fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        // Semantics is deliberate menu setup, not pointer-navigation evidence.
        node(if (emojiRoute) "emoji-filter" else "palette-menu").performClick()
        node("inline-menu").assertIsDisplayed()
        rejectedKeyboardCallback(obstructedShow, false, search, surface, "keyboard-guard-open-menu")
        node("inline-menu").assertIsDisplayed()
        // Injected system Back cleared Emoji's caller query in the retained #115
        // cohort. This pointer-backdrop protocol earns no Back repair credit.
        dismissKeyboardMenuByPointer(search, surface)
        node(search).assertIsFocused()
        text(search, query)
        pointerKeyboard(true, search, surface, "keyboard-guard-after-menu")
        pointerKeyboard(false, search, surface, "keyboard-guard-before-replacement")

        val replacedShow = requireNotNull(keyboardControl().fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        ui { it.store.dialog = "updates"; replacedShow(); assertEquals("updates", it.store.dialog) }
        node("inline-back").assertIsDisplayed().performTouchInput { click(center) }
        if (emojiRoute) { emoji(); ui { it.store.emoji.chooseFilter("All") } }
        node(search).performTextReplacement(query); keyboard(false, search); settledFeedbackViewport(surface)
        rejectedKeyboardCallback(replacedShow, false, search, surface, "keyboard-guard-replaced-route")
        pointerKeyboard(true, search, surface, "keyboard-guard-current-replacement-show")
        pointerKeyboard(false, search, surface, "keyboard-guard-before-recreation")
        val recreatedShow = requireNotNull(keyboardControl().fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.activityRule.scenario.recreate()
        node(search).performTextReplacement(query); keyboard(false, search); settledFeedbackViewport(surface)
        rejectedKeyboardCallback(recreatedShow, false, search, surface, "keyboard-guard-recreated-owner")
        pointerKeyboard(true, search, surface, "keyboard-guard-current-recreated-show")
        record.put("rejected_callbacks", 5).put("completed", true)
    }

    @Test fun launcherKeyboardCallbacksRejectObsoleteModesOwnersAndMenus() = keyboardCallbackFlow(false)
    @Test fun emojiKeyboardCallbacksRejectObsoleteModesOwnersAndMenus() = keyboardCallbackFlow(true)

    @Test fun currentKeyboardAccessibilityActionsApplyTheirPublishedVisibility() {
        assumeTrue("Owned wide-screen accessibility action contract", compose.activity.resources.configuration.screenWidthDp >= 700)
        record.put("scope", "Explicit native accessibility action invocation with accepted return and actual IME transition; editor setup is controlled. No TalkBack gesture or physical-typing certification.")
        for (emojiRoute in listOf(false, true)) {
            if (emojiRoute) { emoji(); ui { it.store.emoji.chooseFilter("All") } }
            val search = if (emojiRoute) "emoji-search" else "search"
            val surface = if (emojiRoute) "emoji-palette" else "palette"
            val query = if (emojiRoute) "face" else "settings"
            keyboard(false, search); node(search).performTextReplacement(query); settledFeedbackViewport(surface)
            val origin = rect(search)
            for ((index, visible) in listOf(true, false, true).withIndex()) {
                val semantics = keyboardControl().fetchSemanticsNode()
                assertEquals(androidx.compose.ui.semantics.Role.Button, semantics.config[SemanticsProperties.Role])
                val action = requireNotNull(semantics.config[SemanticsActions.OnClick].action)
                var accepted = false
                ui { accepted = action() }
                assertTrue("The current published keyboard action is supported and accepted", accepted)
                compose.waitUntil(6000) { ime() == visible }
                settledFeedbackViewport(surface)
                keyboardState(search, query, TextRange(query.length), emojiRoute, "accessibility-$emojiRoute-$index")
                assertEquals(origin, rect(search))
                keyboardControl().assertContentDescriptionEquals(if (visible) "Hide on-screen keyboard" else "Show on-screen keyboard")
                events.put(JSONObject().put("kind", "current-keyboard-accessibility-action").put("route", if (emojiRoute) "emoji" else "launcher")
                    .put("expected_visible", visible).put("actual_visible", ime()).put("accepted", accepted))
                capture("keyboard-accessibility-${if (emojiRoute) "emoji" else "launcher"}-$index", search)
            }
        }
        record.put("completed", true)
    }

    private fun menuBackdrop(surface: String) {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val popup = node("inline-menu").fetchSemanticsNode().boundsInRoot
        val palette = node(surface).fetchSemanticsNode().boundsInRoot
        val candidates = listOf(
            androidx.compose.ui.geometry.Offset((root.left + palette.left) / 2, palette.center.y),
            androidx.compose.ui.geometry.Offset((palette.right + root.right) / 2, palette.center.y),
            androidx.compose.ui.geometry.Offset(root.center.x, (root.top + palette.top) / 2),
            androidx.compose.ui.geometry.Offset(root.center.x, (palette.bottom + root.bottom) / 2))
        val point = requireNotNull(candidates.firstOrNull { root.contains(it) && !popup.contains(it) })
        compose.onRoot().performTouchInput { click(point) }
        node("inline-menu").assertDoesNotExist()
        events.put(JSONObject().put("kind", "menu-backdrop-pointer").put("point", JSONArray(listOf(point.x, point.y)))
            .put("popup", json(popup)).put("palette", json(palette)).put("root", json(root)))
    }

    private fun ownedRange(phase: String, search: String, query: String): TextRange {
        val semantics = node(search).fetchSemanticsNode().config
        assertTrue("Owned query retained at $phase", semantics[SemanticsProperties.EditableText].text == query)
        val range = semantics[SemanticsProperties.TextSelectionRange]
        events.put(JSONObject().put("kind", "owned-caller-range").put("phase", phase).put("query", query)
            .put("selection", JSONArray(listOf(range.start, range.end)))
            .put("focused", semantics[SemanticsProperties.Focused]).put("ime_visible", ime()))
        return range
    }

    @OptIn(ExperimentalTestApi::class)
    private fun menuDismissals(emojiRoute: Boolean) {
        val search = if (emojiRoute) "emoji-search" else "search"
        val surface = if (emojiRoute) "emoji-palette" else "palette"
        val button = if (emojiRoute) "emoji-filter" else "palette-menu"
        val query = if (emojiRoute) "face" else "settings"
        val range = TextRange(1, 3)
        val wide = compose.activity.resources.configuration.screenWidthDp >= 700
        record.put("scope", "Owned range/editor setup; actual pointer menu open, injected Back/Escape and pointer backdrop. Emoji starts with explicitly shown IME; launcher starts hidden. Functional return/input coverage, not full menu typography or physical typing.")
        observeBackWindow(query) {
            if (emojiRoute) { emoji(); ui { it.store.emoji.chooseFilter("All") } }
            node(search).performTextReplacement(query)
            var selected = ""
            ui { selected = if (emojiRoute) it.store.emoji.results()[it.store.emoji.selected].id else it.store.results()[it.store.selected].id }
            for (action in listOf("back", "escape", "backdrop")) {
                feedbackTop(surface)
                keyboard(emojiRoute, search)
                node(search).performTextInputSelection(range)
                assertEquals(range, ownedRange("$action-before-menu", search, query))
                capture("dismiss-$action-before", search)
                if (!emojiRoute) feedbackReveal(node(button), surface)
                completeFeedbackTarget(node(button), "Menu opener", surface)
                node(button).performTouchInput { click(center) }
                node("inline-menu").assertIsDisplayed()
                compose.waitUntil(10000) { !ime() }
                capture("dismiss-$action-menu", search)
                when (action) {
                    "back" -> events.put(JSONObject().put("kind", "menu-dismiss-back").put("press_back_return", device.pressBack()))
                    "escape" -> events.put(JSONObject().put("kind", "menu-dismiss-escape").put("key_injection_return", device.pressKeyCode(KeyEvent.KEYCODE_ESCAPE)))
                    else -> menuBackdrop(surface)
                }
                compose.waitForIdle()
                node("inline-menu").assertDoesNotExist()
                text(search, query)
                node(search).assertIsFocused().assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, range))
                ui { activity ->
                    assertEquals(if (emojiRoute) "emoji" else null, activity.store.dialog)
                    assertEquals(if (emojiRoute) "All" else "Apps", if (emojiRoute) activity.store.emoji.filter else activity.store.category)
                    val now = if (emojiRoute) activity.store.emoji.results()[activity.store.emoji.selected].id else activity.store.results()[activity.store.selected].id
                    assertEquals(selected, now)
                }
                val expectedIme = emojiRoute || !wide
                compose.waitUntil(10000) { ime() == expectedIme }
                feedbackTop(surface)
                text(search, query)
                node(search).assertIsFocused()
                assertEquals(range, ownedRange("$action-return", search, query))
                capture("dismiss-$action-return", search)
                events.put(JSONObject().put("kind", "menu-caller-preserved").put("dismissal", action)
                    .put("query", query).put("selection", JSONArray(listOf(range.start, range.end)))
                    .put("filter", if (emojiRoute) "All" else "Apps").put("selected_id", selected)
                    .put("ime_visible", ime()).put("expected_ime_visible", expectedIme))
            }
            record.put("completed", true)
        }
    }

    @Test fun emojiMenuDismissalsPreserveRangeAndImeIntent() = menuDismissals(true)
    @Test fun launcherMenuDismissalsPreserveRangeAndFilter() = menuDismissals(false)

    @OptIn(ExperimentalTestApi::class)
    @Test fun emojiCanceledRepeatedBackKeepsActiveMenu() {
        record.put("scope", "Controlled editor/range setup and injected Back DOWN/repeats/canceled UP; no physical hold or predictive gesture certification.")
        observeBackWindow {
            emoji(); ui { it.store.emoji.chooseFilter("All") }
            node("emoji-search").performTextReplacement("face")
            keyboard(false, "emoji-search")
            node("emoji-search").performTextInputSelection(TextRange(1, 3))
            assertEquals(TextRange(1, 3), ownedRange("cancel-before-menu", "emoji-search", "face"))
            node("emoji-filter").performTouchInput { click(center) }
            node("inline-menu").assertIsDisplayed()
            capture("cancel-menu-before", "emoji-search")
            val down = SystemClock.uptimeMillis()
            for ((action, repeat) in listOf(KeyEvent.ACTION_DOWN to 0, KeyEvent.ACTION_DOWN to 1,
                KeyEvent.ACTION_DOWN to 2, KeyEvent.ACTION_UP to 0)) {
                val flags = if (action == KeyEvent.ACTION_UP) KeyEvent.FLAG_CANCELED else 0
                val event = KeyEvent(down, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_BACK,
                    repeat, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags, InputDevice.SOURCE_KEYBOARD)
                val accepted = testAutomation().injectInputEvent(event, true)
                events.put(JSONObject().put("kind", "canceled-back-injection").put("action", action)
                    .put("repeat", repeat).put("canceled", action == KeyEvent.ACTION_UP).put("accepted", accepted))
                assertTrue("Each controlled key injection must succeed", accepted)
            }
            compose.waitForIdle()
            node("inline-menu").assertIsDisplayed()
            text("emoji-search", "face")
            capture("cancel-menu-retained", "emoji-search")
            events.put(JSONObject().put("kind", "normal-back-after-cancel").put("press_back_return", device.pressBack()))
            compose.waitForIdle()
            node("inline-menu").assertDoesNotExist()
            text("emoji-search", "face")
            node("emoji-search").assertIsFocused().assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(1, 3)))
            capture("cancel-normal-back-return", "emoji-search")
            keyboard(false, "emoji-search")
            events.put(JSONObject().put("kind", "normal-back-clears-query").put("press_back_return", device.pressBack()))
            compose.waitForIdle(); text("emoji-search", "")
            node("emoji-palette").assertIsDisplayed()
            capture("cancel-next-back-cleared", "emoji-search")
            events.put(JSONObject().put("kind", "normal-back-returns-route").put("press_back_return", device.pressBack()))
            compose.waitForIdle(); node("emoji-palette").assertDoesNotExist(); node("search").assertIsFocused()
            capture("cancel-final-back-launcher", "search")
            record.put("completed", true)
        }
    }

    @Test fun menuBackPrecedesLaterRegisteredRouteCallback() {
        record.put("scope", "Controlled native fixture using the production PaletteMenuHost, PaletteFrame and PaletteChoiceMenu. Actual pointers enter a later route and open a menu; injected Back must dismiss the menu before the route callback. This is callback-priority evidence, not ordinary-screen execution.")
        observeBackWindow {
            var route by mutableStateOf(false)
            var menu by mutableStateOf(false)
            var routeBacks = 0
            var menuDismissals = 0
            ui { activity ->
                activity.setContent {
                    androidx.compose.material3.MaterialTheme {
                        PaletteMenuHost(activity.store) {
                            if (!route) PaletteFrame({}, tag = "back-fixture-home") {
                                androidx.compose.material3.Text("Controlled native Back ownership fixture")
                                androidx.compose.material3.Button(onClick = { route = true }, modifier = Modifier.testTag("back-fixture-enter")) {
                                    androidx.compose.material3.Text("Enter owned route")
                                }
                            } else PaletteFrame({ routeBacks++; route = false }, tag = "back-fixture-route") {
                                androidx.compose.material3.Text("Later registered route callback")
                                androidx.compose.material3.Button(onClick = { menu = true }, modifier = Modifier.testTag("back-fixture-menu")) {
                                    androidx.compose.material3.Text("Open owned menu")
                                }
                                PaletteChoiceMenu(menu, { menuDismissals++; menu = false },
                                    listOf(PaletteChoice("back-fixture-choice", "Owned unchanged choice") {}), title = "Owned menu")
                            }
                        }
                    }
                }
            }
            node("back-fixture-enter").performTouchInput { click(center) }
            node("back-fixture-route").assertIsDisplayed()
            node("back-fixture-menu").performTouchInput { click(center) }
            node("inline-menu").assertIsDisplayed()
            capture("priority-menu-before", "back-fixture-route", "fixture_route")
            events.put(JSONObject().put("kind", "priority-first-back").put("press_back_return", device.pressBack()))
            compose.waitForIdle()
            val afterFirst = JSONObject().put("kind", "priority-after-first-back").put("route_retained", route)
                .put("route_backs", routeBacks).put("menu_dismissals", menuDismissals)
            events.put(afterFirst)
            capture("priority-menu-return", if (route) "back-fixture-route" else "back-fixture-home", "fixture_route")
            assertTrue("One Back dismisses the menu while retaining its caller", route)
            assertEquals(0, routeBacks)
            assertEquals(1, menuDismissals)
            node("inline-menu").assertDoesNotExist()
            events.put(JSONObject().put("kind", "priority-second-back").put("press_back_return", device.pressBack()))
            compose.waitForIdle()
            node("back-fixture-route").assertDoesNotExist()
            node("back-fixture-home").assertIsDisplayed()
            assertEquals(1, routeBacks)
            assertEquals(1, menuDismissals)
            capture("priority-second-back-home", "back-fixture-home", "fixture_route")
            record.put("completed", true)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun diagnoseRangePlacementBeforeAndAfterImeSetup() {
        record.put("scope", "Diagnostic range setup comparison on the same app. Earlier ordering is observed without preservation credit; later ordering must establish the actual range before opening. No keyboard-typing or app-repair claim.")
        observeBackWindow {
            emoji(); ui { it.store.emoji.chooseFilter("All") }
            node("emoji-search").performTextReplacement("face")
            val range = TextRange(1, 3)
            for (after in listOf(false, true)) {
                keyboard(false, "emoji-search")
                if (!after) node("emoji-search").performTextInputSelection(range)
                keyboard(true, "emoji-search")
                if (after) node("emoji-search").performTextInputSelection(range)
                val before = ownedRange("range-order-$after-before-menu", "emoji-search", "face")
                capture("range-order-$after-before-menu", "emoji-search")
                node("emoji-filter").performTouchInput { click(center) }
                node("inline-menu").assertIsDisplayed()
                ownedRange("range-order-$after-menu", "emoji-search", "face")
                menuBackdrop("emoji-palette")
                node("emoji-search").assertIsFocused()
                val returned = ownedRange("range-order-$after-return", "emoji-search", "face")
                capture("range-order-$after-return", "emoji-search")
                events.put(JSONObject().put("kind", "range-order-observation").put("selection_after_ime_setup", after)
                    .put("before", JSONArray(listOf(before.start, before.end))).put("returned", JSONArray(listOf(returned.start, returned.end))))
                if (after) {
                    assertEquals("Corrected setup must establish the range before menu opening", range, before)
                    assertEquals("Corrected setup must retain the witnessed range", range, returned)
                }
            }
            record.put("completed", true)
        }
    }

    @Test fun emojiMenuBackPreservesCallerQueryWithoutKeyboardToggle() {
        assumeTrue("Owned wide-screen Back diagnostic", compose.activity.resources.configuration.screenWidthDp >= 700)
        record.put("scope", "Injected system Back diagnostic with no header keyboard activation. Editor and epoch setup are controlled; it earns no Back repair, hardware-key or TalkBack certification.")
        observeBackWindow {
            emoji(); ui { it.store.emoji.chooseFilter("All") }
            node("emoji-search").performTextReplacement("face")
            keyboard(false, "emoji-search")
            ui { val before = it.store.focusEpoch; it.store.focusEpoch++
                events.put(JSONObject().put("kind", "back-probe-epoch-setup").put("before", before).put("after", it.store.focusEpoch)) }
            settledFeedbackViewport("emoji-palette")
            text("emoji-search", "face"); node("emoji-search").assertIsFocused()
            capture("back-probe-before-menu", "emoji-search")
            node("emoji-filter").assertIsDisplayed().performTouchInput { click(center) }
            node("inline-menu").assertIsDisplayed()
            capture("back-probe-menu", "emoji-search")
            val injected = device.pressBack()
            compose.waitForIdle(); settledFeedbackViewport("emoji-palette")
            capture("back-probe-after", "emoji-search")
            ui { events.put(JSONObject().put("kind", "back-probe-after-injection").put("press_back_return", injected)
                .put("query", it.store.emoji.query).put("route", it.store.dialog).put("ime_visible", ime())) }
            node("inline-menu").assertDoesNotExist()
            text("emoji-search", "face")
            node("emoji-search").assertIsFocused().assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(4)))
            record.put("completed", true)
        }
    }
}
