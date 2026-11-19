package dev.tinycast.android

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.compose.ui.semantics.SemanticsProperties
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SettingsRouteDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private var original = emptyMap<String, Any?>()
    private var main: ActivityScenario<MainActivity>? = null
    @Before fun launch() {
        original = context.getSharedPreferences("palette", Context.MODE_PRIVATE).all.toMap()
        main = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        compose.waitUntil(10000) { runCatching { main!!.onActivity { check(!it.store.loading && !it.store.folderSearch.loading) } }.isSuccess }
    }
    @After fun restore() {
        instrumentation.runOnMainSync {
            (ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED) +
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.STARTED)).toList().forEach { it.finish() }
        }
        main?.close()
        restorePalettePreferences(context, original)
    }
    private fun key(code: Int, meta: Int = 0) { compose.waitForIdle(); device.pressKeyCode(code, meta); compose.waitForIdle() }
    private fun settings(): SettingsActivity {
        var result: SettingsActivity? = null
        compose.waitUntil(5000) {
            instrumentation.runOnMainSync { result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<SettingsActivity>().firstOrNull() }
            result != null
        }
        return result!!
    }
    private fun evidence(name: String, marker: String) {
        compose.waitForIdle()
        assertTrue(device.wait(Until.hasObject(By.textContains(marker)), 5000)); device.waitForIdle()
        val dir = File(context.filesDir, "settings-evidence").apply { mkdirs() }
        device.dumpWindowHierarchy(File(dir, "$name.xml")); assertTrue(device.takeScreenshot(File(dir, "$name.png")))
    }
    @Test fun heldCtrlFindDoesNotTypeIntoSettingsSearch() {
        checkHeldFind(KeyEvent.META_CTRL_ON, "ctrl")
    }

    @Test fun heldMetaFindDoesNotTypeIntoSettingsSearch() {
        checkHeldFind(KeyEvent.META_META_ON, "meta")
    }

    private fun checkHeldFind(modifier: Int, label: String) {
        key(KeyEvent.KEYCODE_COMMA, KeyEvent.META_CTRL_ON)
        compose.onNodeWithTag("settings-workspace").assertIsDisplayed()
        val search = compose.onNodeWithTag("settings-search")
        search.assertIsNotFocused().assert(editableCharactersExactly(""))
        val workspace = settings()
        val down = android.os.SystemClock.uptimeMillis()
        fun send(action: Int, repeat: Int, meta: Int) {
            val event = KeyEvent(down, android.os.SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_F,
                repeat, meta, android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, android.view.InputDevice.SOURCE_KEYBOARD)
            // Android can reserve Meta+F. Test the app route directly for Meta and inject Ctrl through Android.
            if (modifier == KeyEvent.META_META_ON) instrumentation.runOnMainSync { workspace.dispatchKeyEvent(event) }
            else assertTrue(testAutomation().injectInputEvent(event, true))
            compose.waitForIdle()
        }
        fun capture(phase: String) {
            if (!hostRestoresTestClipboard()) return
            val dir = File(context.filesDir, "reviewed-text-evidence").apply { mkdirs() }
            device.waitForIdle()
            testAutomation().clearCacheForTest()
            device.dumpWindowHierarchy(File(dir, "$label-$phase.xml"))
            assertTrue(device.takeScreenshot(File(dir, "$label-$phase.png")))
        }
        try {
            send(KeyEvent.ACTION_DOWN, 0, modifier)
            search.assertIsFocused().assert(editableCharactersExactly(""))
            for (repeat in 1..3) send(KeyEvent.ACTION_DOWN, repeat, 0)
            capture("held")
            search.assertIsFocused().assert(editableCharactersExactly(""))
        } finally {
            send(KeyEvent.ACTION_UP, 0, 0)
        }
        key(KeyEvent.KEYCODE_F)
        search.assertIsFocused().assert(editableCharactersExactly("f"))
        capture("fresh")
    }

    @Test fun ctrlCommaOpensSeparateSettingsAndPaletteReturnsWithFreshPreferences() {
        key(KeyEvent.KEYCODE_COMMA, KeyEvent.META_CTRL_ON)
        compose.onNodeWithTag("settings-workspace").assertIsDisplayed()
        assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, main!!.state)
        compose.onNodeWithTag("settings-edit-global").performClick()
        key(KeyEvent.KEYCODE_G, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        compose.onNodeWithTag("shortcut-save").performScrollTo().performClick()
        val activity = settings()
        assertEquals("Ctrl + Alt + G", activity.store.shortcutConfiguration.bindings[ShortcutRules.PALETTE]!!.label())
        compose.onNodeWithTag("settings-return-palette").performClick()
        compose.onNodeWithTag("search").assertIsDisplayed()
        compose.waitUntil(5000) { activity.isDestroyed }
        evidence("palette-return", "Search for apps")
    }
    @Test fun configureSelectedCommandAndActionRouteOpenInlineInspector() {
        compose.onNodeWithTag("search").performTextReplacement("Create a note")
        key(KeyEvent.KEYCODE_COMMA, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
        compose.onNodeWithTag("settings-alias").performScrollTo().assertIsDisplayed()
        assertEquals("new-note", settings().requestedCommand)
        compose.onNodeWithTag("settings-return-palette").performClick()
        compose.onNodeWithTag("search").performTextReplacement("Wi-Fi settings")
        key(KeyEvent.KEYCODE_K, KeyEvent.META_CTRL_ON)
        compose.onNodeWithTag("action-Set keyboard shortcut").performClick()
        compose.onNodeWithTag("shortcut-candidate").assertIsDisplayed()
        assertEquals("system:wifi", settings().store.recordingTarget)
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        evidence("configure-command-route", "Wi-Fi settings")
    }
    @Test fun findWaitsForInstalledCommandBeforeShowingEmptyResults() {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        HeldCatalogFixture(context).use { fixture ->
            fixture.writeCommand()
            lateinit var injectedStore: PaletteStore
            val callback = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
                if (stage == Stage.PRE_ON_CREATE && activity is SettingsActivity) {
                    injectedStore = fixture.createStore(activity.viewModelStore)
                }
            }
            instrumentation.runOnMainSync { monitor.addLifecycleCallback(callback) }
            try {
                ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)).use { scenario ->
                    try {
                        scenario.onActivity { assertSame(injectedStore, it.store) }
                        assertTrue("Catalog worker reached the scan", fixture.awaitWorker())
                        key(KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON)
                        val field = compose.onNodeWithTag("settings-search")
                        field.assertIsFocused().performTextReplacement(fixture.title)
                        val loading = "Loading installed extensions…"
                        val empty = "No matching settings or commands."
                        fun capture(name: String) {
                            if (!hostRestoresTestClipboard()) return
                            val dir = File(context.filesDir, "reviewed-text-evidence").apply { mkdirs() }
                            testAutomation().clearCacheForTest()
                            device.dumpWindowHierarchy(File(dir, "find-$name.xml"))
                            assertTrue(device.takeScreenshot(File(dir, "find-$name.png")))
                        }
                        compose.waitForIdle()
                        val pending = compose.onAllNodesWithText(loading).fetchSemanticsNodes().isNotEmpty()
                        compose.onNodeWithText(if (pending) loading else empty).assertIsDisplayed()
                        val earlyEmpty = compose.onAllNodesWithText(empty).fetchSemanticsNodes().isNotEmpty()
                        scenario.onActivity { assertFalse(it.store.extensions.inventoryReady) }
                        capture("pending")
                        fixture.release()
                        compose.waitUntil(10000) { injectedStore.extensions.inventoryReady }
                        field.assertIsFocused().assert(editableCharactersExactly(fixture.title))
                        compose.onNodeWithText("Command shortcuts and alias").assertIsDisplayed()
                        compose.onNodeWithText(loading).assertDoesNotExist()
                        capture("published")
                        field.performTextReplacement("Owned no result 71429")
                        compose.onNodeWithText(empty).assertIsDisplayed()
                        capture("empty")
                        field.performTextReplacement(fixture.title)
                        compose.onNodeWithText("Command shortcuts and alias").performClick()
                        scenario.onActivity { assertEquals(fixture.id, it.requestedCommand) }
                        assertTrue("Settings Find must display pending inventory feedback", pending)
                        assertFalse("Settings Find must defer definitive empty results", earlyEmpty)
                    } finally { fixture.release() }
                }
            } finally {
                instrumentation.runOnMainSync { monitor.removeLifecycleCallback(callback) }
            }
        }
    }

    @Test fun pendingAliasRequestFocusesWhenTheCommandBecomesAvailable() {
        key(KeyEvent.KEYCODE_COMMA, KeyEvent.META_CTRL_ON)
        val workspace = settings()
        val id = "notes:${java.util.UUID.randomUUID()}"
        instrumentation.runOnMainSync { workspace.configure(id, "alias") }
        val alias = compose.onNodeWithTag("settings-alias")
        alias.performScrollTo().assertIsNotEnabled()
        compose.onAllNodes(hasTestTag("settings-alias") and isFocused()).assertCountEquals(0)
        assertEquals("alias", workspace.requestedEditor)

        // Publish a local command after the unavailable inspector has been laid out.
        // This exercises the shared inspector without relying on a scheduler or Store download.
        instrumentation.runOnMainSync { workspace.store.save("notes", "Owned late command", "Fixture", id = id) }
        alias.performScrollTo().assertIsEnabled()
        compose.waitForIdle()
        if (hostRestoresTestClipboard()) {
            val dir = File(context.filesDir, "reviewed-text-evidence").apply { mkdirs() }
            device.waitForIdle()
            testAutomation().clearCacheForTest()
            device.dumpWindowHierarchy(File(dir, "late-command.xml"))
            assertTrue(device.takeScreenshot(File(dir, "late-command.png")))
        }
        alias.assertIsFocused()
        assertEquals("", workspace.requestedEditor)
        assertEquals(id, workspace.requestedCommand)
    }

    @Test fun aliasActionRevealsFieldAboveKeyboard() {
        val id = "notes:${java.util.UUID.randomUUID()}"
        main!!.onActivity { it.store.save("notes", "Owned alias viewport", "Fixture", id = id) }
        compose.onNodeWithTag("search").performTextReplacement("Owned alias viewport")
        key(KeyEvent.KEYCODE_K, KeyEvent.META_CTRL_ON)
        compose.onNodeWithTag("action-Set alias").performClick()
        checkAliasViewport(settings(), id, "ordinary")
    }

    @Test fun lateAliasRequestRevealsFieldAboveKeyboard() {
        key(KeyEvent.KEYCODE_COMMA, KeyEvent.META_CTRL_ON)
        val workspace = settings()
        val id = "notes:${java.util.UUID.randomUUID()}"
        instrumentation.runOnMainSync { workspace.configure(id, "alias") }
        compose.onNodeWithTag("settings-alias").performScrollTo().assertIsNotEnabled()
        assertEquals("alias", workspace.requestedEditor)
        instrumentation.runOnMainSync { workspace.store.save("notes", "Owned alias viewport", "Fixture", id = id) }
        checkAliasViewport(workspace, id, "late")
    }

    private fun checkAliasViewport(workspace: SettingsActivity, id: String, path: String) {
        val alias = compose.onNodeWithTag("settings-alias")
        val start = SystemClock.uptimeMillis()
        fun imeVisible() = ViewCompat.getRootWindowInsets(workspace.window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        compose.waitUntil(5000) { runCatching { alias.assertIsFocused() }.isSuccess }
        alias.assert(editableCharactersExactly(""))
        compose.waitUntil(5000) { compose.runOnUiThread { imeVisible() } }
        val samples = JSONArray()
        fun measure(): Boolean {
            val node = alias.fetchSemanticsNode()
            return compose.runOnUiThread {
                val clipped = node.boundsInWindow
                val whole = node.size
                val decor = workspace.window.decorView
                val frame = Rect().also(decor::getWindowVisibleDisplayFrame)
                val screen = IntArray(2).also(decor::getLocationOnScreen)
                val window = IntArray(2).also(decor::getLocationInWindow)
                frame.offset(window[0] - screen[0], window[1] - screen[1])
                val full = whole.height > 0 && whole.width > 0 &&
                    clipped.left + 1 >= frame.left && clipped.right <= frame.right + 1 &&
                    clipped.top + 1 >= frame.top && clipped.bottom <= frame.bottom + 1 &&
                    clipped.height + 1 >= whole.height && clipped.width + 1 >= whole.width
                samples.put(JSONObject().put("elapsed_ms", SystemClock.uptimeMillis() - start)
                    .put("ime", imeVisible()).put("height", whole.height).put("width", whole.width)
                    .put("visible_height", clipped.height.toDouble()).put("visible_width", clipped.width.toDouble())
                    .put("clipped_xyxy", JSONArray(listOf(clipped.left, clipped.top, clipped.right, clipped.bottom)))
                    .put("display_frame_xyxy", JSONArray(listOf(frame.left, frame.top, frame.right, frame.bottom)))
                    .put("full", full))
                full && imeVisible()
            }
        }
        val dest = if (hostRestoresTestClipboard()) File(context.filesDir, "reviewed-text-evidence").apply { mkdirs() } else null
        fun capture(phase: String) {
            if (dest == null) return
            testAutomation().clearCacheForTest()
            device.dumpWindowHierarchy(File(dest, "$path-$phase.xml"))
            assertTrue(device.takeScreenshot(File(dest, "$path-$phase.png")))
        }
        measure()
        val controller = WindowCompat.getInsetsController(workspace.window, workspace.window.decorView)
        compose.runOnUiThread { controller.hide(WindowInsetsCompat.Type.ime()) }
        compose.waitUntil(5000) { !compose.runOnUiThread { imeVisible() } }
        alias.assertIsFocused()
        measure()
        compose.runOnUiThread { controller.show(WindowInsetsCompat.Type.ime()) }
        compose.waitUntil(5000) { measure(); compose.runOnUiThread { imeVisible() } }
        // Allow both opening animation and automatic scrolling to settle. Never scroll to the focused field here.
        val until = SystemClock.uptimeMillis() + 1600
        while (SystemClock.uptimeMillis() < until) {
            SystemClock.sleep(120)
            measure()
        }
        val visibleBefore = measure()
        capture("settled")
        assertTrue(device.pressKeyCode(KeyEvent.KEYCODE_X))
        alias.assertIsFocused().assert(editableCharactersExactly("x"))
        assertEquals(id, workspace.requestedCommand)
        assertEquals("", workspace.requestedEditor)
        val visibleAfter = measure()
        capture("typed")
        // Move the inspector back to the top while the IME is open. An offscreen
        // field may still be focused and editable; both dimensions must catch it.
        val inspector = compose.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("settings-alias")))
        fun scrollOffset() = inspector.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val beforePan = scrollOffset()
        inspector.performTouchInput { swipeDown() }
        compose.waitForIdle()
        val afterPan = scrollOffset()
        val inspectorClipped = !measure()
        alias.assertIsFocused().assert(editableCharactersExactly("x"))
        assertEquals(id, workspace.requestedCommand)
        val stillOpen = compose.runOnUiThread { imeVisible() }
        SystemClock.sleep(350)
        val scrollStable = kotlin.math.abs(scrollOffset() - afterPan) <= 1f &&
            compose.runOnUiThread { imeVisible() }
        if (inspectorClipped && stillOpen) capture("panned")
        dest?.let { File(it, "$path-probe.json").writeText(JSONObject().put("samples", samples)
            .put("visible_before_typing", visibleBefore).put("visible_after_typing", visibleAfter)
            .put("offset_before_pan", beforePan.toDouble()).put("offset_after_pan", afterPan.toDouble())
            .put("panned_clipped", inspectorClipped).put("keyboard_after_pan", stillOpen)
            .put("user_scroll_preserved", scrollStable).put("typed_exactly_x", true).toString()) }
        assertTrue("The focused alias field must be fully visible with the keyboard before typing", visibleBefore)
        assertTrue("The typed alias field must remain fully visible with the keyboard", visibleAfter)
        assertTrue("User scroll must be preserved with focus and keyboard intact", scrollStable)
    }

    @Test fun aliasActionFocusesCorrectCommandAndPersistsWithoutPopup() {
        compose.onNodeWithTag("search").performTextReplacement("Create a note")
        key(KeyEvent.KEYCODE_K, KeyEvent.META_CTRL_ON)
        compose.onNodeWithTag("action-Set alias").performClick()
        compose.waitUntil(5000) { runCatching { compose.onNodeWithTag("settings-alias").assertIsFocused() }.isSuccess }
        compose.onNodeWithTag("settings-alias").assertIsFocused().performTextReplacement("settingsnote")
        var tabs = 0
        while (runCatching { compose.onNodeWithTag("settings-save-alias").assertIsFocused() }.isFailure && tabs++ < 8) key(KeyEvent.KEYCODE_TAB)
        compose.onNodeWithTag("settings-save-alias").assertIsFocused()
        key(KeyEvent.KEYCODE_ENTER)
        assertEquals("new-note", settings().requestedCommand)
        assertEquals("settingsnote", settings().store.command("new-note")!!.alias)
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }
    @Test fun settingsIsNotExportedAndOldGlobalNavigationBindingsArePreserved() {
        @Suppress("DEPRECATION") val info = context.packageManager.getActivityInfo(android.content.ComponentName(context, SettingsActivity::class.java), 0)
        assertFalse(info.exported)
        main!!.onActivity {
            it.store.setShortcut("calculator", KeyChord(KeyEvent.KEYCODE_COMMA, ShortcutRules.CTRL))
        }
        key(KeyEvent.KEYCODE_COMMA, KeyEvent.META_CTRL_ON)
        compose.onNodeWithTag("settings-workspace").assertIsDisplayed()
        assertEquals("general", settings().page)
        assertEquals(KeyChord(KeyEvent.KEYCODE_COMMA, ShortcutRules.CTRL), settings().store.shortcutConfiguration.bindings["calculator"])
        key(KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON)
        compose.onNodeWithTag("settings-search").assertIsFocused()
    }
}
