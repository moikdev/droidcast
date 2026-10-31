package dev.tinycast.android

import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
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

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class InlinePaletteDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private var original = emptyMap<String, Any?>()
    private var prepared = false
    private var originalClip: android.content.ClipData? = null
    @Before fun prepare() {
        val avd = InstrumentationRegistry.getArguments().getString("dedicatedAvd")
        assertFalse("Explicit owned AVD required", avd.isNullOrBlank())
        assertEquals(avd, device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim())
        original = context.getSharedPreferences("palette", Context.MODE_PRIVATE).all.toMap()
        prepared = true
        compose.activityRule.scenario.onActivity {
            val manager = it.getSystemService(ClipboardManager::class.java)
            originalClip = manager.primaryClip; manager.clearPrimaryClip()
            it.store.reset(); it.store.filter("Apps")
        }
        compose.waitUntil(10000) { !compose.activity.store.loading }
    }
    @After fun restore() {
        if (!prepared) return
        restorePalettePreferences(context, original)
        compose.activityRule.scenario.onActivity {
            val manager = it.getSystemService(ClipboardManager::class.java)
            originalClip?.let(manager::setPrimaryClip) ?: manager.clearPrimaryClip()
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
    private fun capture(name: String, marker: String) {
        compose.waitForIdle(); sameWindow()
        if(name=="inline-emoji-actions") {
            device.wait(Until.hasObject(By.res("com.android.systemui","dismiss_button")),1500)
            val deadline=android.os.SystemClock.uptimeMillis()+5000
            while(device.hasObject(By.res("com.android.systemui","clipboard_ui")) && android.os.SystemClock.uptimeMillis()<deadline) {
                InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
                try { device.findObject(By.res("com.android.systemui","dismiss_button"))?.click() }
                catch(_:androidx.test.uiautomator.StaleObjectException) { }
                device.wait(Until.gone(By.res("com.android.systemui","clipboard_ui")),500)
            }
            assertFalse(device.hasObject(By.res("com.android.systemui","clipboard_ui")))
        }
        val folder = File(context.filesDir, "extension-grid-evidence").apply { mkdirs() }
        compose.waitUntil(10000) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
            val xml = File(folder, "$name.xml"); device.dumpWindowHierarchy(xml)
            val nodes = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml).getElementsByTagName("node")
            (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }.any {
                it.getAttribute("package") == context.packageName && it.getAttribute("text").contains(marker)
            }
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
        val save = compose.onNodeWithTag("save-editor").fetchSemanticsNode().boundsInWindow
        val typingFrame = compose.onNodeWithTag("palette-screen").fetchSemanticsNode().boundsInWindow
        assertTrue("Save stays inside the palette with the keyboard", save.bottom <= typingFrame.bottom)
        capture("inline-editor-keyboard", "This draft stays inside the palette.")
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
        assertTrue(frame.contains(menu.topLeft) && frame.contains(menu.bottomRight))
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
            compose.onNodeWithTag("editor-body").performSemanticsAction(SemanticsActions.RequestFocus)
            compose.onNodeWithTag("editor-body").assertIsFocused()
            compose.activityRule.scenario.onActivity {
                androidx.core.view.WindowCompat.getInsetsController(it.window,it.window.decorView).show(androidx.core.view.WindowInsetsCompat.Type.ime())
            }
            compose.waitUntil(10000) { android.view.inspector.WindowInspector.getGlobalWindowViews().filter{it.hasWindowFocus()}.any {
                androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true } }
            compose.onNodeWithTag("save-editor").assertIsDisplayed().assertIsEnabled()
            val save=compose.onNodeWithTag("save-editor").fetchSemanticsNode().boundsInWindow
            val frame=compose.onNodeWithTag("palette-screen").fetchSemanticsNode().boundsInWindow
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
        sameWindow()
        val folder=File(context.filesDir,"extension-grid-evidence").apply{mkdirs()}
        assertTrue(device.takeScreenshot(File(folder,"inline-feedback.png")))
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
        if(compose.onAllNodesWithTag("inline-menu-keyboard").fetchSemanticsNodes().isNotEmpty()) {
            val before=compose.onNodeWithTag("inline-menu").fetchSemanticsNode().boundsInWindow.height
            val originalSetting=device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            try {
                compose.onNodeWithTag("inline-menu-keyboard").performClick()
                compose.waitUntil(10000) {
                    val frame=compose.onNodeWithTag("palette").fetchSemanticsNode().boundsInWindow
                    val menu=compose.onNodeWithTag("inline-menu").fetchSemanticsNode().boundsInWindow
                    menu.height < before-20 && frame.contains(menu.topLeft) && frame.contains(menu.bottomRight)
                }
                compose.onNodeWithTag("inline-menu-search").assertIsFocused()
            } finally { device.executeShellCommand(if(originalSetting=="null") "settings delete secure show_ime_with_hard_keyboard" else "settings put secure show_ime_with_hard_keyboard $originalSetting") }
        }
        compose.onNodeWithTag("inline-menu-search").assertIsFocused().performTextInput("no such action")
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
        compose.activityRule.scenario.onActivity { clip=it.getSystemService(ClipboardManager::class.java).primaryClip; it.getSystemService(ClipboardManager::class.java).clearPrimaryClip() }
        fun capture(name:String,marker:String) {
            compose.waitForIdle(); device.waitForIdle()
            val dir=File(context.filesDir,"extension-grid-evidence").apply{mkdirs()}
            instrumentation.uiAutomation.clearCache(); device.dumpWindowHierarchy(File(dir,"$name.xml"))
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
            compose.activityRule.scenario.onActivity { it.store.dialog=null; it.store.extensions.close(); clip?.let(it.getSystemService(ClipboardManager::class.java)::setPrimaryClip) ?: it.getSystemService(ClipboardManager::class.java).clearPrimaryClip() }
            restorePalettePreferences(context,original)
        }
    }
}
