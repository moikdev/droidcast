package dev.tinycast.android

import android.content.Context
import android.os.Build
import android.os.SystemClock
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

    private fun capture(phase: String, search: String) {
        compose.waitForIdle(); instrumentation.waitForIdleSync(); device.waitForIdle()
        evidence.mkdirs()
        val stem = "${name.methodName.lowercase()}-$phase"
        testAutomation().clearCacheForTest()
        device.dumpWindowHierarchy(File(evidence, "$stem.xml"))
        val bounds = rect(search)
        assertTrue(device.takeScreenshot(File(evidence, "$stem.png")))
        File(evidence, "$stem-frame.json").writeText(JSONObject().put("phase", phase).put("search", json(bounds))
            .put("ime_visible", ime()).put("api", Build.VERSION.SDK_INT)
            .put("font_scale", compose.activity.resources.configuration.fontScale)
            .put("synchronization", "Compose and UiAutomator idle; hierarchy before PNG; XML geometry is not independent visibility proof")
            .put("scope", record.getString("scope")).toString(2))
    }

    private fun emoji() {
        ui { it.execute(Command("emoji", "Emoji & Symbols", "")) }
        node("emoji-search").assertIsFocused()
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
            if (emojiRoute) {
                completeFeedbackTarget(node("emoji-back"), "Emoji Back", surface)
                completeFeedbackTarget(node("emoji-filter"), "Emoji category", surface)
            }
            if (compose.activity.resources.configuration.screenWidthDp >= 700)
                completeFeedbackTarget(compose.onNodeWithContentDescription("Show on-screen keyboard"), "Keyboard", surface)
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
}
