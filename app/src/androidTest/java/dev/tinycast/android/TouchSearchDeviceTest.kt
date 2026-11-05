package dev.tinycast.android

import android.content.Context
import android.os.Build
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

    @Test fun launcherTouchClearPreservesFocusCategoryAndKeyboard() {
        ui { it.store.filter("Apps") }
        for (visible in listOf(false, true)) {
            node("search").performTextReplacement("zzownednomatchingapp")
            keyboard(visible, "search")
            ui { events.put(JSONObject().put("kind", "empty-state-before-clear").put("search", "search")
                .put("ime_visible", visible).put("expected_query_matches", it.store.query == "zzownednomatchingapp")
                .put("result_count", it.store.results().size)) }
            capture("${if (visible) "shown" else "hidden"}-before", "search")
            compose.onNodeWithText("No matching commands").assertIsDisplayed()
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
                .put("result_count", it.store.emoji.results().size)) }
            capture("${if (visible) "shown" else "hidden"}-before", "emoji-search")
            node("emoji-empty").assertIsDisplayed()
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
}
