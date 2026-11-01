package dev.tinycast.android

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

internal fun modifierKey(code: Int, down: Boolean, start: Long, repeat: Int = 0): KeyEvent {
    val family = ShortcutRules.modifierFamily(code) ?: 0
    val meta = when (family) {
        ShortcutRules.CTRL -> KeyEvent.META_CTRL_ON
        ShortcutRules.ALT -> KeyEvent.META_ALT_ON
        ShortcutRules.SHIFT -> KeyEvent.META_SHIFT_ON
        ShortcutRules.META -> KeyEvent.META_META_ON
        else -> 0
    }
    return KeyEvent(start, if (down && repeat == 0) start else SystemClock.uptimeMillis(),
        if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, repeat, if (down) meta else 0,
        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
}
internal fun doubleModifier(code: Int = KeyEvent.KEYCODE_CTRL_LEFT, holdMs: Long = 30, tapCount: Int = 2) {
    val automation = testAutomation()
    repeat(tapCount) {
        val start = SystemClock.uptimeMillis()
        assertTrue(automation.injectInputEvent(modifierKey(code, true, start), true))
        try { SystemClock.sleep(holdMs) } finally { assertTrue(automation.injectInputEvent(modifierKey(code, false, start), true)) }
        if (it < tapCount - 1) SystemClock.sleep(60)
    }
}

@RunWith(AndroidJUnit4::class)
class ModifierTapDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<SettingsActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private var original = emptyMap<String, Any?>()
    @Before fun setup() {
        original = context.getSharedPreferences("palette", Context.MODE_PRIVATE).all.toMap()
        compose.waitUntil(10000) { !compose.activity.store.loading && !compose.activity.store.folderSearch.loading }
        compose.activityRule.scenario.onActivity { it.store.resetShortcuts(); it.selectPage("general") }
    }
    @After fun restore() {
        compose.activityRule.scenario.onActivity { it.store.cancelShortcutRecording(false) }
        restorePalettePreferences(context, original)
    }
    private fun recordGlobal() {
        compose.onNodeWithTag("settings-edit-global").performScrollTo().performClick()
        compose.onNodeWithTag("shortcut-record-help").assertIsDisplayed()
    }
    private fun capture(name: String, marker: String) {
        compose.waitForIdle()
        val folder = File(context.filesDir, "modifier-tap-evidence").apply { mkdirs() }
        device.waitForIdle()
        if (android.os.Build.VERSION.SDK_INT >= 34) testAutomation().clearCache()
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName).textContains(marker)), 5000))
        device.dumpWindowHierarchy(folder.resolve("$name.xml"))
        val text = folder.resolve("$name.xml").readText()
        assertTrue("Native marker before PNG: $marker", marker in text)
        assertTrue(device.takeScreenshot(folder.resolve("$name.png")))
    }
    @Test fun doubleCtrlRecordsWithoutPopupAndSavesWithKeyboardNavigation() {
        recordGlobal(); doubleModifier(); compose.waitForIdle()
        val expected = ShortcutRules.doubleTap(ShortcutRules.CTRL)
        assertEquals(expected, compose.activity.store.recordingCandidate)
        compose.onNodeWithTag("shortcut-candidate").onChild().assertTextEquals("Double Ctrl")
        compose.onNodeWithTag("shortcut-side-ANY").assertIsSelected()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        capture("record-double-ctrl", "Double Ctrl")
        compose.onNodeWithTag("shortcut-save").performScrollTo()
        var tabs = 0
        while (runCatching { compose.onNodeWithTag("shortcut-save").assertIsFocused() }.isFailure && tabs++ < 20) device.pressKeyCode(KeyEvent.KEYCODE_TAB)
        compose.onNodeWithTag("shortcut-save").assertIsFocused()
        device.pressKeyCode(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON)
        compose.onNodeWithText("Cancel").assertIsFocused()
        assertEquals(expected, compose.activity.store.recordingCandidate)
        device.pressKeyCode(KeyEvent.KEYCODE_TAB); device.pressKeyCode(KeyEvent.KEYCODE_ENTER)
        compose.onNodeWithTag("settings-selected-shortcut").assertTextEquals("Double Ctrl")
        assertEquals(expected, PaletteStore(context.applicationContext as Application).shortcutConfiguration.bindings[ShortcutRules.PALETTE])
        capture("saved-double-ctrl", "Double Ctrl")
    }
    @Test fun actualRightModifierCanBeRestrictedToRightAndReopened() {
        recordGlobal(); doubleModifier(KeyEvent.KEYCODE_CTRL_RIGHT); compose.waitForIdle()
        compose.onNodeWithTag("shortcut-side-RIGHT").performClick()
        compose.onNodeWithTag("shortcut-candidate").onChild().assertTextEquals("Double Right Ctrl")
        capture("right-only-recording", "Double Right Ctrl")
        compose.onNodeWithTag("shortcut-save").performScrollTo().performClick()
        val expected = ShortcutRules.doubleTap(ShortcutRules.CTRL, ModifierSide.RIGHT)
        assertEquals(expected, PaletteStore(context.applicationContext as Application).shortcutConfiguration.bindings[ShortcutRules.PALETTE])
        compose.onNodeWithTag("settings-record").performScrollTo().performClick()
        compose.onNodeWithTag("shortcut-side-RIGHT").assertIsSelected()
        compose.onNodeWithTag("shortcut-candidate").onChild().assertTextEquals("Double Right Ctrl")
    }
    @Test fun eitherSideConflictNamesOwnerAndCanBeResolvedToUnusedSide() {
        compose.activityRule.scenario.onActivity { it.store.setShortcut("new-note", ShortcutRules.doubleTap(ShortcutRules.CTRL, ModifierSide.LEFT)) }
        recordGlobal(); doubleModifier(); compose.waitForIdle()
        compose.onNodeWithTag("shortcut-record-error").performScrollTo().assertIsDisplayed().assertTextContains("Create a note", substring = true)
        compose.onNodeWithTag("shortcut-save").assertIsNotEnabled()
        capture("either-side-conflict", "Already assigned to Create a note")
        compose.onNodeWithTag("shortcut-side-RIGHT").performScrollTo().performClick()
        compose.onNodeWithTag("shortcut-record-error").assertDoesNotExist()
        compose.onNodeWithTag("shortcut-save").assertIsEnabled()
        compose.onNodeWithTag("shortcut-candidate").performScrollTo()
        capture("right-side-resolved", "Double Right Ctrl")
        compose.onNodeWithTag("shortcut-save").performScrollTo().performClick()
        assertEquals(ShortcutRules.doubleTap(ShortcutRules.CTRL, ModifierSide.LEFT), compose.activity.store.shortcutConfiguration.bindings["new-note"])
    }
    @Test fun holdingModifierDoesNotRecordAndExistingGlobalTapIsPausedDuringRecording() {
        val ctrl = ShortcutRules.doubleTap(ShortcutRules.CTRL)
        compose.activityRule.scenario.onActivity { it.store.setShortcut(ShortcutRules.PALETTE, ctrl) }
        recordGlobal(); doubleModifier(holdMs = 300); compose.waitForIdle()
        assertEquals(ctrl, compose.activity.store.recordingCandidate)
        compose.activityRule.scenario.onActivity { it.store.captureShortcut(KeyChord(KeyEvent.KEYCODE_F6, 0)) }
        doubleModifier(holdMs = 300); compose.waitForIdle()
        assertEquals(KeyChord(KeyEvent.KEYCODE_F6, 0), compose.activity.store.recordingCandidate)
        doubleModifier(); compose.waitForIdle()
        assertEquals(ctrl, compose.activity.store.recordingCandidate)
        assertSame(compose.activity.store, ShortcutRuntime.recordingOwner)
        compose.onNodeWithTag("shortcut-save").assertIsEnabled()
        capture("recording-pauses-tap", "Double Ctrl")
    }
    @Test fun doubleBindingsCanBeClearedAndResetThroughSettings() {
        compose.activityRule.scenario.onActivity {
            it.store.setShortcut(ShortcutRules.PALETTE, ShortcutRules.doubleTap(ShortcutRules.CTRL))
            it.store.setShortcut("new-note", ShortcutRules.doubleTap(ShortcutRules.SHIFT, ModifierSide.LEFT))
            it.configure(ShortcutRules.PALETTE, "hotkey")
        }
        compose.onNodeWithTag("shortcut-clear").performScrollTo().performClick()
        compose.onNodeWithTag("settings-selected-shortcut").assertTextEquals("Not assigned")
        assertNull(PaletteStore(context.applicationContext as Application).shortcutConfiguration.bindings[ShortcutRules.PALETTE])
        capture("cleared-double-ctrl", "Not assigned")
        compose.onNodeWithTag("settings-reset-shortcuts").assertIsDisplayed().performClick()
        compose.onNodeWithTag("shortcut-reset-confirm").performScrollTo().performClick()
        assertEquals(ShortcutConfiguration(), PaletteStore(context.applicationContext as Application).shortcutConfiguration)
        compose.activityRule.scenario.onActivity { it.selectPage("general") }
        compose.onNodeWithTag("settings-edit-global").performScrollTo()
        capture("reset-default-chord", "Ctrl + Space")
    }
    @Test fun settingsDeviceChangeAndRemovalDiscardPartialRecording() {
        recordGlobal()
        val initial = compose.activity.store.recordingCandidate
        for (removed in listOf(false, true)) {
            doubleModifier(tapCount = 1)
            compose.activityRule.scenario.onActivity {
                if (removed) it.onInputDeviceRemoved(KeyCharacterMap.VIRTUAL_KEYBOARD)
                else it.onInputDeviceChanged(KeyCharacterMap.VIRTUAL_KEYBOARD)
            }
            doubleModifier(tapCount = 1); compose.waitForIdle()
            assertEquals(initial, compose.activity.store.recordingCandidate)
            compose.activityRule.scenario.onActivity { it.store.clearModifierRecording() }
        }
        doubleModifier(); compose.waitForIdle()
        assertEquals(ShortcutRules.doubleTap(ShortcutRules.CTRL), compose.activity.store.recordingCandidate)
    }
    @Test fun sideChoicesAndSaveAreReachableWithKeyboardWithoutPreparingScroll() {
        recordGlobal(); doubleModifier(); compose.waitForIdle()
        var tabs = 0
        while (runCatching { compose.onNodeWithTag("shortcut-side-RIGHT").assertIsFocused() }.isFailure && tabs++ < 30) device.pressKeyCode(KeyEvent.KEYCODE_TAB)
        compose.onNodeWithTag("shortcut-side-RIGHT").assertIsFocused().assertIsDisplayed()
        device.pressKeyCode(KeyEvent.KEYCODE_ENTER)
        compose.onNodeWithTag("shortcut-side-RIGHT").assertIsSelected()
        val expected = ShortcutRules.doubleTap(ShortcutRules.CTRL, ModifierSide.RIGHT)
        assertEquals(expected, compose.activity.store.recordingCandidate)
        tabs = 0
        while (runCatching { compose.onNodeWithTag("shortcut-save").assertIsFocused() }.isFailure && tabs++ < 10) device.pressKeyCode(KeyEvent.KEYCODE_TAB)
        compose.onNodeWithTag("shortcut-save").assertIsFocused().assertIsDisplayed()
        device.pressKeyCode(KeyEvent.KEYCODE_ENTER)
        assertEquals(expected, PaletteStore(context.applicationContext as Application).shortcutConfiguration.bindings[ShortcutRules.PALETTE])
    }
    @Test fun codecMigratesOldChordsAndBackupRetainsDoubleTapSemantics() {
        val normal = ShortcutConfiguration()
        assertEquals(1, ShortcutCodec.encode(normal).getInt("version"))
        assertEquals(normal, ShortcutCodec.decode(ShortcutCodec.encode(normal)))
        val config = ShortcutConfiguration(mapOf(ShortcutRules.PALETTE to ShortcutRules.doubleTap(ShortcutRules.CTRL),
            "new-note" to KeyChord(KeyEvent.KEYCODE_N, ShortcutRules.CTRL or ShortcutRules.ALT)))
        val encoded = ShortcutCodec.encode(config)
        assertEquals(2, encoded.getInt("version")); assertEquals(config, ShortcutCodec.decode(encoded))
        val backup = BackupCodec.encode(BackupData(shortcuts = config), setOf(BackupCategory.SHORTCUTS))
        val doc = BackupCodec.decode(backup)
        assertEquals(config, doc.data.shortcuts)
        val file = File(context.cacheDir, "modifier-shortcuts-backup.json").apply { writeText(backup) }
        compose.activityRule.scenario.onActivity { it.store.readBackup(android.net.Uri.fromFile(file)) }
        compose.waitUntil(10000) { !compose.activity.store.backupBusy && compose.activity.store.backupDocument != null }
        compose.activityRule.scenario.onActivity { it.store.restorePolicy = RestorePolicy.REPLACE; it.store.applyBackup() }
        compose.waitUntil(10000) { !compose.activity.store.backupBusy && compose.activity.store.shortcutConfiguration == config }
        assertEquals(config, PaletteStore(context.applicationContext as Application).shortcutConfiguration)
        for (bad in listOf(JSONObject(encoded.toString()).put("version", 3), JSONObject(encoded.toString()).apply {
            getJSONObject("bindings").getJSONObject(ShortcutRules.PALETTE).put("side", "BOTH")
        }, JSONObject(encoded.toString()).apply {
            getJSONObject("bindings").getJSONObject(ShortcutRules.PALETTE).put("keyCode", KeyEvent.KEYCODE_A)
        })) assertThrows(IllegalArgumentException::class.java) { ShortcutCodec.decode(bad) }
        compose.activityRule.scenario.onActivity { it.selectPage("general") }
        compose.onNodeWithTag("settings-edit-global").performScrollTo()
        capture("restored-double-ctrl", "Double Ctrl")
    }
}

@RunWith(AndroidJUnit4::class)
class ModifierTapPaletteDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private var original = emptyMap<String, Any?>()
    private var originalClip: android.content.ClipData? = null
    @Before fun setup() {
        original = context.getSharedPreferences("palette", Context.MODE_PRIVATE).all.toMap()
        compose.waitUntil(10000) { !compose.activity.store.loading && !compose.activity.store.folderSearch.loading }
        compose.onNodeWithTag("search").performClick().assertIsFocused()
        originalClip = context.getSystemService(android.content.ClipboardManager::class.java).primaryClip
        compose.activityRule.scenario.onActivity { it.store.resetShortcuts(); it.store.reset() }
    }
    @After fun restore() {
        restorePalettePreferences(context, original)
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        originalClip?.let(clipboard::setPrimaryClip) ?: run {
            if (android.os.Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
            else clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
        }
    }
    @Test fun doubleCtrlRunsCommandLocallyWithoutKeyboardService() {
        assertFalse(ShortcutRuntime.keyboardConnection.connected)
        val before = compose.activity.store.usage["new-note"] ?: 0
        compose.activityRule.scenario.onActivity { it.store.setShortcut("new-note", ShortcutRules.doubleTap(ShortcutRules.CTRL)) }
        doubleModifier(); compose.waitForIdle()
        compose.onNodeWithTag("editor-title").assertIsDisplayed().assertIsFocused()
        assertEquals(before + 1, compose.activity.store.usage["new-note"])
        val folder = File(context.filesDir, "modifier-tap-evidence").apply { mkdirs() }
        device.dumpWindowHierarchy(folder.resolve("local-note-command.xml")); assertTrue(device.takeScreenshot(folder.resolve("local-note-command.png")))
    }
    @Test fun ordinaryCtrlShortcutsAndLongHoldDoNotLaunchButNextDoubleTapWorks() {
        compose.activityRule.scenario.onActivity { it.store.setShortcut("new-note", ShortcutRules.doubleTap(ShortcutRules.CTRL)) }
        compose.onNodeWithTag("search").performTextReplacement("ordinary control fixture")
        compose.onNodeWithTag("search").performClick().assertIsFocused()
        val automation = testAutomation()
        val start = SystemClock.uptimeMillis()
        assertTrue(automation.injectInputEvent(modifierKey(KeyEvent.KEYCODE_CTRL_LEFT, true, start), true))
        try {
            device.pressKeyCode(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)
            compose.waitForIdle()
            compose.onNodeWithTag("search").assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange, androidx.compose.ui.text.TextRange(0, "ordinary control fixture".length)))
            device.pressKeyCode(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON)
        } finally { assertTrue(automation.injectInputEvent(modifierKey(KeyEvent.KEYCODE_CTRL_LEFT, false, start), true)) }
        doubleModifier(holdMs = 300); compose.waitForIdle()
        assertNull(compose.activity.store.dialog)
        assertEquals("ordinary control fixture", compose.activity.store.query)
        assertEquals("ordinary control fixture", context.getSystemService(android.content.ClipboardManager::class.java)
            .primaryClip?.getItemAt(0)?.coerceToText(context)?.toString())
        doubleModifier(); compose.waitForIdle()
        compose.onNodeWithTag("editor-title").assertIsDisplayed()
    }
}
