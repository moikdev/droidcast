package dev.tinycast.android

import android.content.Context
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SettingsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<SettingsActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private var original = emptyMap<String, Any?>()
    private val ctrlAlt = KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON
    @Before fun setup() {
        device.executeShellCommand("cmd statusbar collapse")
        original = context.getSharedPreferences("palette", Context.MODE_PRIVATE).all.toMap()
        compose.waitUntil(10000) { !compose.activity.store.loading && !compose.activity.store.folderSearch.loading }
        compose.activityRule.scenario.onActivity { it.store.resetShortcuts(); it.selectPage("general") }
    }
    @After fun restore() { restorePalettePreferences(context, original) }
    private fun key(code: Int, meta: Int = 0) { compose.waitForIdle(); device.pressKeyCode(code, meta); compose.waitForIdle() }
    private fun cancelDocumentPicker() {
        repeat(6) {
            if (device.currentPackageName != "com.google.android.documentsui") return
            device.pressBack()
            if (device.wait(Until.gone(By.pkg("com.google.android.documentsui")), 1000)) return
        }
        assertFalse("Document picker must actually close", device.hasObject(By.pkg("com.google.android.documentsui")))
    }
    private fun evidence(name: String, marker: String) {
        compose.waitForIdle()
        assertTrue(device.wait(Until.hasObject(By.textContains(marker)), 5000))
        device.waitForIdle()
        val dir = File(context.filesDir, "settings-evidence").apply { mkdirs() }
        device.dumpWindowHierarchy(File(dir, "$name.xml"))
        assertTrue(device.takeScreenshot(File(dir, "$name.png")))
    }
    @Test fun sidebarAndSettingsSearchNavigateWithoutDialogs() {
        compose.onNodeWithTag("settings-workspace").assertIsDisplayed()
        val viewport = compose.onNodeWithTag("settings-viewport").fetchSemanticsNode().boundsInRoot
        val frame = compose.onNodeWithTag("settings-frame").fetchSemanticsNode().boundsInRoot
        assertEquals("Settings fills the entire safe viewport", viewport, frame)
        assertEquals("Settings uses the tablet/phone width", device.displayWidth.toFloat(), frame.width, 1f)
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithTag("settings-edit-global").performScrollTo().assertIsDisplayed()
        evidence("general", "Global shortcut")
        compose.onNodeWithTag("settings-workspace").performSemanticsAction(SemanticsActions.RequestFocus)
        key(KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON)
        compose.onNodeWithTag("settings-search").assertIsFocused().performTextReplacement("Wi-Fi settings")
        compose.onNode(hasText("Wi-Fi settings") and !hasTestTag("settings-search")).performClick()
        compose.onNodeWithTag("settings-alias").performScrollTo().assertIsDisplayed()
        assertEquals("system:wifi", compose.activity.requestedCommand)
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        evidence("command-inspector", "KEYBOARD SHORTCUT")
        if (compose.activity.resources.configuration.screenWidthDp < 760) {
            compose.onNodeWithTag("settings-workspace").performSemanticsAction(SemanticsActions.RequestFocus)
            key(KeyEvent.KEYCODE_ESCAPE)
            compose.onNodeWithTag("settings-command-list").assertIsDisplayed()
        }
        compose.onNodeWithTag("settings-nav-folders").performClick()
        compose.onNodeWithTag("folder-add").assertIsDisplayed()
        compose.onNodeWithTag("settings-nav-backup").performClick()
        compose.onNodeWithTag("backup-export").performScrollTo().assertIsDisplayed()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }
    @Test fun commandCategoryAndAssignedFiltersReflectStoredBindings() {
        compose.onNodeWithTag("settings-nav-shortcuts").performClick()
        compose.onNodeWithTag("settings-command-search").performTextReplacement("Wi-Fi")
        compose.onNodeWithTag("settings-category").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("settings-category-APP").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("settings-category-APP").performScrollTo().performClick()
        compose.onNodeWithText("No matching commands").assertIsDisplayed()
        compose.onNodeWithTag("settings-category").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("settings-category-COMMAND").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("settings-category-COMMAND").performScrollTo().performClick()
        compose.onNodeWithTag("settings-command-system:wifi").assertIsDisplayed()
        compose.onNodeWithTag("settings-assigned").performClick()
        compose.onNodeWithText("No matching commands").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.store.setShortcut("system:wifi", KeyChord(KeyEvent.KEYCODE_W, ShortcutRules.CTRL or ShortcutRules.ALT)) }
        compose.onNodeWithTag("settings-command-system:wifi").assertIsDisplayed().performClick()
        compose.onNodeWithTag("settings-selected-shortcut").assertTextEquals("Ctrl + Alt + W")
        evidence("filtered-commands", "Ctrl + Alt + W")
    }
    @Test fun inlineRecorderPreservesBackwardFocusAndActivationKeys() {
        compose.onNodeWithTag("settings-edit-global").performScrollTo().assertIsDisplayed().performClick()
        val keys = listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER)
            .flatMap { listOf(it to 0, it to KeyEvent.META_SHIFT_ON) }
        keys.forEachIndexed { index, (activation, meta) ->
            if (index > 0) compose.onNodeWithTag("settings-record").performScrollTo().performClick()
            key(KeyEvent.KEYCODE_G + index, ctrlAlt)
            val expected = KeyChord(KeyEvent.KEYCODE_G + index, ShortcutRules.CTRL or ShortcutRules.ALT)
            compose.onNodeWithTag("shortcut-save").performScrollTo()
            var tabs = 0
            while (runCatching { compose.onNodeWithTag("shortcut-save").assertIsFocused() }.isFailure && tabs++ < 20) key(KeyEvent.KEYCODE_TAB)
            compose.onNodeWithTag("shortcut-save").assertIsFocused()
            key(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON)
            compose.onNodeWithText("Cancel").assertIsFocused()
            assertEquals(expected, compose.activity.store.recordingCandidate)
            key(KeyEvent.KEYCODE_TAB)
            compose.onNodeWithTag("shortcut-save").assertIsFocused()
            val downTime = android.os.SystemClock.uptimeMillis()
            fun inject(action: Int) = assertTrue(testAutomation().injectInputEvent(
                KeyEvent(downTime, android.os.SystemClock.uptimeMillis(), action, activation, 0, meta,
                    android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, android.view.InputDevice.SOURCE_KEYBOARD), true))
            try {
                inject(KeyEvent.ACTION_DOWN); compose.waitForIdle()
                assertEquals(expected, compose.activity.store.recordingCandidate)
                assertNull(compose.activity.store.recordingError)
                compose.onNodeWithTag("shortcut-save").assertIsEnabled()
                if (index == 0) evidence("inline-recording", expected.label())
            } finally { inject(KeyEvent.ACTION_UP) }
            compose.waitForIdle()
            assertNull(ShortcutRuntime.recordingOwner)
            compose.onNodeWithTag("settings-selected-shortcut").assertTextEquals(expected.label())
            assertEquals(expected, PaletteStore(context.applicationContext as android.app.Application).shortcutConfiguration.bindings[ShortcutRules.PALETTE])
        }
        evidence("saved-global", "Ctrl + Alt + L")
    }
    @Test fun conflictAndAliasErrorsAreInlineAndDoNotReplaceExistingValues() {
        compose.activityRule.scenario.onActivity {
            it.store.setShortcut("system:wifi", KeyChord(KeyEvent.KEYCODE_W, ShortcutRules.CTRL or ShortcutRules.ALT))
            it.configure("calculator", "hotkey")
        }
        key(KeyEvent.KEYCODE_W, ctrlAlt)
        compose.onNodeWithTag("shortcut-record-error").assertTextContains("Wi-Fi settings", substring = true)
        compose.onNodeWithTag("shortcut-save").assertIsNotEnabled()
        evidence("inline-conflict", "Already assigned")
        key(KeyEvent.KEYCODE_ESCAPE)
        compose.onNodeWithTag("settings-alias").performScrollTo().performClick().performTextReplacement("prefs")
        compose.onNodeWithTag("settings-alias").performImeAction()
        try {
            compose.waitUntil(5000) { runCatching { compose.onNodeWithTag("settings-alias-error").isDisplayed() }.getOrDefault(false) }
        } catch (error: Throwable) {
            val dir = File(context.filesDir, "settings-evidence").apply { mkdirs() }
            device.takeScreenshot(File(dir, "failed-alias.png")); device.dumpWindowHierarchy(File(dir, "failed-alias.xml"))
            File(dir, "failed-alias-semantics.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString())
            throw error
        }
        compose.onNodeWithTag("settings-alias-error").assertIsDisplayed()
        evidence("alias-conflict", "That alias is already in use")
        assertEquals("calc", compose.activity.store.command("calculator")!!.alias)
        compose.onNodeWithTag("settings-clear-alias").performScrollTo().performClick()
        compose.onNodeWithTag("settings-alias").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("settings-alias-error").assertDoesNotExist()
        assertEquals("", compose.activity.store.command("calculator")!!.alias)
        assertEquals("", PaletteStore(context.applicationContext as android.app.Application).command("calculator")!!.alias)
        evidence("alias-cleared", "Alias cleared")
        compose.onNodeWithTag("settings-alias").performScrollTo().performTextReplacement("mathinline")
        compose.onNodeWithTag("settings-alias").performImeAction()
        assertEquals("mathinline", PaletteStore(context.applicationContext as android.app.Application).command("calculator")!!.alias)
        compose.onNodeWithTag("settings-record").performScrollTo().performClick()
        key(KeyEvent.KEYCODE_M, ctrlAlt)
        key(KeyEvent.KEYCODE_ESCAPE)
        assertFalse("calculator" in compose.activity.store.shortcutConfiguration.bindings)
        assertNull(ShortcutRuntime.recordingOwner)
        compose.onNodeWithTag("settings-alias").performScrollTo().assertTextEquals("mathinline")
        evidence("alias-saved", "mathinline")
    }
    @Test fun backupPreviewEscapeAndSystemPickerCancellationStayInWorkspace() {
        compose.onNodeWithTag("settings-nav-backup").performClick()
        val before = compose.activity.store.backupSnapshot()
        val file = File(context.cacheDir, "settings-preview.json").apply {
            writeText(BackupCodec.encode(BackupData(notes = listOf(SavedItem("notes:settings-test", "Settings preview fixture", "Never imported"))), setOf(BackupCategory.NOTES)))
        }
        compose.activityRule.scenario.onActivity { it.store.readBackup(Uri.fromFile(file)) }
        compose.waitUntil(10000) { !compose.activity.store.backupBusy }
        compose.onNodeWithTag("backup-review").performScrollTo().performClick()
        evidence("inline-backup-confirm", "Apply this backup?")
        key(KeyEvent.KEYCODE_ESCAPE)
        compose.onNodeWithText("Preview backup").assertIsDisplayed()
        key(KeyEvent.KEYCODE_ESCAPE)
        compose.onNodeWithText("Backup and restore").assertIsDisplayed()
        assertFalse(compose.activity.isFinishing)
        assertEquals(before, compose.activity.store.backupSnapshot())
        compose.onNodeWithTag("backup-import").performScrollTo().performClick()
        assertTrue(device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 7000))
        cancelDocumentPicker()
        compose.onNodeWithTag("backup-export").performScrollTo().performClick()
        assertTrue(device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 7000))
        cancelDocumentPicker()
        compose.waitUntil(5000) { !compose.activity.store.backupBusy && compose.activity.store.pendingExport == null }
        assertEquals(before, compose.activity.store.backupSnapshot())
        evidence("inline-backup", "Portable collections")
    }
    @Test fun resetAndRecreationPreserveCollectionAndServiceConsent() {
        val consent = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val before = compose.activity.store.backupSnapshot()
        compose.activityRule.scenario.onActivity {
            it.store.setShortcut("calculator", KeyChord(KeyEvent.KEYCODE_M, ShortcutRules.CTRL or ShortcutRules.ALT))
            it.configure("calculator")
        }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("settings-selected-shortcut").assertTextEquals("Ctrl + Alt + M")
        compose.onNodeWithTag("settings-record").performScrollTo().performClick()
        key(KeyEvent.KEYCODE_Z, ctrlAlt)
        assertNotNull(compose.activity.store.recordingCandidate)
        compose.onNodeWithTag("settings-reset-shortcuts").performClick()
        compose.onNodeWithTag("shortcut-reset-confirm").assertIsDisplayed()
        assertNull(ShortcutRuntime.recordingOwner)
        compose.waitForIdle(); device.pressBack(); compose.waitForIdle()
        assertNull(compose.activity.store.dialog)
        compose.onNodeWithTag("settings-selected-shortcut").assertTextEquals("Ctrl + Alt + M")
        compose.onNodeWithTag("settings-reset-shortcuts").performClick()
        compose.onNodeWithTag("shortcut-reset-confirm").performScrollTo().performClick()
        assertEquals(ShortcutConfiguration(), compose.activity.store.shortcutConfiguration)
        assertEquals(before.notes, compose.activity.store.backupSnapshot().notes)
        assertEquals(before.links, compose.activity.store.backupSnapshot().links)
        assertEquals(consent, android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }
    @Test fun escapeClosesSettingsWithContainerFocus() {
        compose.onNodeWithTag("settings-workspace").performSemanticsAction(SemanticsActions.RequestFocus)
        key(KeyEvent.KEYCODE_ESCAPE)
        compose.waitUntil(5000) { compose.activityRule.scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
    }
    @Test fun backgroundCancelsInlineRecordingAndKeepsPriorChord() {
        compose.onNodeWithTag("settings-edit-global").performScrollTo().assertIsDisplayed().performClick()
        key(KeyEvent.KEYCODE_Z, ctrlAlt)
        assertNotNull(ShortcutRuntime.recordingOwner)
        device.pressHome()
        compose.waitUntil(5000) { ShortcutRuntime.recordingOwner == null }
        assertEquals(ShortcutConfiguration(), compose.activity.store.shortcutConfiguration)
    }
}

internal fun restorePalettePreferences(context: Context, original: Map<String, Any?>) {
    val editor = context.getSharedPreferences("palette", Context.MODE_PRIVATE).edit().clear()
    original.forEach { (key, value) -> when (value) {
        is String -> editor.putString(key, value)
        is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        is Boolean -> editor.putBoolean(key, value)
        is Int -> editor.putInt(key, value)
        is Long -> editor.putLong(key, value)
        is Float -> editor.putFloat(key, value)
    } }
    assertTrue(editor.commit())
}
