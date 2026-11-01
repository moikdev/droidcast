package dev.tinycast.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

abstract class CalculatorDeviceFixture {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    protected val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    protected val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    protected val store get() = compose.activity.store
    private var original = emptyMap<String, Any?>()
    private var originalClip: ClipData? = null
    private var captured = false

    @Before fun setup() {
        Assume.assumeTrue("Dedicated emulator only", device.executeShellCommand("getprop ro.kernel.qemu").trim() == "1" &&
            device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim().ifEmpty { device.executeShellCommand("getprop ro.kernel.qemu.avd_name").trim() } == InstrumentationRegistry.getArguments().getString("dedicatedAvd", "Tinycast_Tablet_API_36"))
        assertTrue(device.executeShellCommand("id").startsWith("uid=2000"))
        original = context.getSharedPreferences("palette", Context.MODE_PRIVATE).all.toMap()
        compose.activityRule.scenario.onActivity { originalClip = it.getSystemService(ClipboardManager::class.java).primaryClip }
        captured = true
        compose.waitUntil(10000) { !store.loading && !store.folderSearch.loading }
        compose.activityRule.scenario.onActivity { it.store.reset() }
        compose.waitForIdle()
    }
    @After fun restore() {
        if (!captured) return
        restorePalettePreferences(context, original)
        compose.activityRule.scenario.onActivity {
            val clipboard = it.getSystemService(ClipboardManager::class.java)
            originalClip?.let(clipboard::setPrimaryClip) ?: clipboard.resetClipboardForTest()
        }
        File(context.filesDir, "calculator-evidence").apply { mkdirs() }.resolve("restoration.json")
            .writeText("{\"palette_preferences_restored\":true,\"clipboard_restored\":true}")
    }
    protected fun search(query: String) { compose.onNodeWithTag("search").performTextReplacement(query); compose.waitForIdle() }
    protected fun result(query: String, expected: String, expression: String? = null) {
        search(query)
        assertEquals(query, store.query)
        assertEquals(expected, store.results().first().payload)
        assertEquals(Kind.CALC, store.results().first().kind)
        compose.onNodeWithTag("result-result").assertIsDisplayed().assertIsSelected().assertTextContains(expected, substring = true)
        if (expression != null) {
            assertEquals(expression, store.results().first().subtitle)
            compose.onNodeWithTag("result-result").assertTextContains(expression, substring = true)
        }
    }
    protected fun normalSearch(query: String, scrollToWeb: Boolean = false) {
        search(query)
        assertEquals(query, store.query)
        assertTrue(store.results().none { it.kind == Kind.CALC })
        compose.onNodeWithTag("result-result").assertDoesNotExist()
        if (scrollToWeb) compose.onNodeWithTag("results", useUnmergedTree = true).performScrollToNode(hasTestTag("result-web"))
        compose.onNodeWithTag("result-web").assertIsDisplayed()
        assertEquals("https://www.google.com/search?q=${android.net.Uri.encode(query)}", store.results().single { it.id == "web" }.payload)
    }
    protected fun key(code: Int, meta: Int = 0) {
        val down = android.os.SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            assertTrue(testAutomation().injectInputEvent(
                KeyEvent(down, android.os.SystemClock.uptimeMillis(), action, code, 0, meta,
                    android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, android.view.InputDevice.SOURCE_KEYBOARD), true))
        }
        compose.waitForIdle()
    }
    protected fun assertClipboard(expected: String) {
        compose.activityRule.scenario.onActivity {
            assertEquals(expected, it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString())
        }
    }
    protected fun capture(name: String, tag: String, vararg text: String) {
        compose.onNodeWithTag(tag).assertIsDisplayed()
        val dir = File(context.filesDir, "calculator-evidence").apply { mkdirs() }
        val xml = File(dir, "$name.xml")
        var valid = false
        repeat(4) {
            if (!valid) {
                compose.waitForIdle(); device.waitForIdle()
                if (android.os.Build.VERSION.SDK_INT >= 34) testAutomation().clearCacheForTest()
                device.wait(Until.hasObject(By.pkg(context.packageName).clazz("android.widget.EditText").text(store.query)), 1500)
                device.dumpWindowHierarchy(xml)
                val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
                val nodes = document.getElementsByTagName("node")
                val app = (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
                    .filter { it.getAttribute("package") == context.packageName }
                // The palette exposes the editable query and visible text to
                // Android, while its test tags remain Compose-only semantics.
                val search = app.singleOrNull { it.getAttribute("class") == "android.widget.EditText" }
                val visible = app.joinToString("\n") { it.getAttribute("text") }
                valid = search?.getAttribute("text") == store.query && text.all { it in visible }
            }
        }
        assertTrue("Native hierarchy must match the displayed query and result text", valid)
        assertTrue(device.takeScreenshot(File(dir, "$name.png")))
    }
}

/** Run explicitly against the recorded base APK, never as part of final tests. */
@RunWith(AndroidJUnit4::class)
class CalculatorBaselineDeviceTest : CalculatorDeviceFixture() {
    @Test fun captureOriginalNumberStarAndXBehavior() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("calculatorBaseline") == "true")
        result("123", "123", "123")
        capture("before-number", "result-result", "123")
        result("12*3", "36", "12*3")
        capture("before-star", "result-result", "12*3", "36")
        normalSearch("12x3")
        capture("before-x", "result-web", "12x3", "Search the web")
    }
}

@RunWith(AndroidJUnit4::class)
class CalculatorIntentDeviceTest : CalculatorDeviceFixture() {
    @Test fun constantOperandsSupportXInAutomaticAndExplicitCalculatorModes() {
        if (InstrumentationRegistry.getArguments().getString("calculatorConstantNegative") == "true") {
            normalSearch("pix2")
            capture("constant-before", "result-web", "pix2", "Search the web")
        }
        result("pix2", "6.2831853072", "pi × 2")
        result("ex2", "5.4365636569", "e × 2")
        result("= 2xpix3", "18.8495559215", "= 2 × pi × 3")
        capture("constant-multiplication", "result-result", "= 2xpix3", "= 2 × pi × 3", "18.8495559215")
        key(KeyEvent.KEYCODE_ENTER)
        assertClipboard("18.8495559215")
        for (query in listOf("pixel2", "extra12", "example2", "pie2", "ex", "pix", "exp(2)")) normalSearch(query, scrollToWeb = true)
    }
    @Test fun bareNumbersRemainSearchableAndCanFindSavedItems() {
        var id = ""
        compose.activityRule.scenario.onActivity { id = it.store.save("notes", "123 Reference", "Numeric search fixture") }
        normalSearch("123")
        assertEquals(id, store.results().first().id)
        compose.onNodeWithTag("result-$id").assertIsDisplayed().assertIsSelected()
        capture("number-search", "result-$id", "123", "123 Reference")
        for (query in listOf("12.5", ".5", "(123)")) normalSearch(query)
        normalSearch("12.5")
        capture("decimal-search", "result-web", "12.5", "Search the web")
    }
    @Test fun multiplicationVariantsShowTimesSignAndCopyOnlyTheNumber() {
        for (query in listOf("12x3", "12*3", "12×3", "12X3")) {
            result(query, "36", "12 × 3")
            if (query == "12x3") capture("x-multiplication", "result-result", "12x3", "12 × 3", "36")
            if (query == "12*3") capture("star-multiplication", "result-result", "12*3", "12 × 3", "36")
            key(KeyEvent.KEYCODE_ENTER)
            assertClipboard("36")
        }
    }
    @Test fun arithmeticAndPrecedenceKeepWorking() {
        mapOf("12+3" to "15", "12-3" to "9", "12/3" to "4", "2+3x4" to "14", "(2+3)x4" to "20",
            "2x-3" to "-6", "-12" to "-12", "2^3" to "8", "15%" to "0.15").forEach { (query, expected) -> result(query, expected) }
        result("(2+3)x4", "20", "(2+3) × 4")
        capture("precedence", "result-result", "(2+3) × 4", "20")
    }
    @Test fun theCalculatorCommandStillAcceptsSingleNumbers() {
        search("Calculator")
        compose.onNodeWithTag("result-calculator").assertIsDisplayed().performClick()
        assertEquals("= ", store.query)
        result("= 123", "123", "= 123")
        capture("explicit-calculator", "result-result", "= 123", "123")
        key(KeyEvent.KEYCODE_ENTER)
        assertClipboard("123")
    }
    @Test fun ordinaryXWordsAndIncompleteMathRemainSearches() {
        for (query in listOf("box12", "12px", "x12", "12x", "12+")) normalSearch(query)
        normalSearch("box12")
        capture("word-search", "result-web", "box12", "Search the web")
    }
    @Test fun deletingTheOperatorImmediatelyRestoresSearchWithoutMovingThePalette() {
        result("12x3", "36", "12 × 3")
        val palette = compose.onNodeWithTag("palette").fetchSemanticsNode().boundsInWindow
        val searchBounds = compose.onNodeWithTag("search").fetchSemanticsNode().boundsInWindow
        key(KeyEvent.KEYCODE_DEL)
        assertEquals("12x", store.query)
        compose.onNodeWithTag("result-result").assertDoesNotExist()
        key(KeyEvent.KEYCODE_DEL)
        assertEquals("12", store.query)
        compose.onNodeWithTag("result-result").assertDoesNotExist()
        compose.onNodeWithTag("result-web").assertIsDisplayed()
        assertEquals(palette, compose.onNodeWithTag("palette").fetchSemanticsNode().boundsInWindow)
        assertEquals(searchBounds, compose.onNodeWithTag("search").fetchSemanticsNode().boundsInWindow)
        capture("back-to-search", "result-web", "12", "Search the web")
    }
    @Test fun functionsUnitsAndCategoryFiltersKeepWorking() {
        result("sqrt(81)", "9")
        result("10 km to mi", "6.2137119224 mi")
        capture("unit-conversion", "result-result", "10 km", "6.2137119224 mi")
        search("@12x3")
        compose.onNodeWithTag("result-result").assertDoesNotExist()
        assertTrue(store.results().all { it.kind == Kind.APP })
        compose.activityRule.scenario.onActivity { it.store.filter("Pinned") }
        search("12x3")
        compose.onNodeWithTag("result-result").assertDoesNotExist()
        compose.activityRule.scenario.onActivity { it.store.filter("All") }
        result("12x3", "36")
    }
    @Test fun keyboardTypingXAndEnterWorksWithSearchFocused() {
        search("")
        for (code in listOf(KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_7)) key(code)
        compose.onNodeWithTag("search").assertIsFocused().assertTextContains("6x7")
        assertEquals("42", store.results().first().payload)
        compose.onNodeWithTag("result-result").assertTextContains("6 × 7", substring = true)
        capture("keyboard-x", "result-result", "6x7", "6 × 7", "42")
        key(KeyEvent.KEYCODE_ENTER)
        assertClipboard("42")
    }
}
