package dev.tinycast.android

import android.content.Context
import android.provider.Settings
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ShortcutDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var original: Map<String, Any?> = emptyMap()
    private val ctrlAlt = KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON
    private val wifiChord = KeyChord(KeyEvent.KEYCODE_W, ShortcutRules.CTRL or ShortcutRules.ALT)
    @Before fun setup() {
        original = context.getSharedPreferences("palette", Context.MODE_PRIVATE).all.toMap()
        compose.activityRule.scenario.onActivity { it.store.resetShortcuts(); it.store.reset() }
        compose.waitForIdle()
    }
    @After fun restore() {
        if (compose.activityRule.scenario.state != Lifecycle.State.DESTROYED) compose.activityRule.scenario.onActivity { it.store.cancelShortcutRecording() }
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
    private fun evidence(name: String, marker: String) {
        compose.waitForIdle()
        assertTrue(device.wait(Until.hasObject(By.textContains(marker)), 7000))
        device.waitForIdle()
        val folder = File(context.filesDir, "shortcut-evidence").apply { mkdirs() }
        device.dumpWindowHierarchy(File(folder, "$name.xml"))
        assertTrue(device.takeScreenshot(File(folder, "$name.png")))
    }
    private fun key(code: Int, modifiers: Int = 0) { compose.waitForIdle(); device.waitForIdle(); device.pressKeyCode(code, modifiers); compose.waitForIdle() }
    private fun settings() {
        // Exercise the shared recorder component; SettingsRouteDeviceTest covers the real route.
        compose.activityRule.scenario.onActivity { it.store.dialog = "shortcut-settings" }
        compose.onNodeWithTag("shortcut-global-value").assertTextEquals("Ctrl + Space")
    }
    private fun load(document: BackupDocument) {
        val file = File(context.cacheDir, "shortcut-backup-test.json").apply { writeText(BackupCodec.encode(document.data, document.categories)) }
        compose.activityRule.scenario.onActivity { it.store.readBackup(android.net.Uri.fromFile(file)) }
        compose.waitUntil(10000) { !compose.activity.store.backupBusy && compose.activity.store.dialog == "backup-preview" }
    }
    @Test fun recorderSavesGlobalChordWithTabAndEnterAndPersistsIt() {
        settings(); evidence("manager", "Your commands, one key combination away.")
        compose.onNodeWithTag("shortcut-global-record").performClick()
        assertSame(compose.activity.store, ShortcutRuntime.recordingOwner)
        key(KeyEvent.KEYCODE_SPACE, ctrlAlt)
        assertEquals(KeyChord(KeyEvent.KEYCODE_SPACE, ShortcutRules.CTRL or ShortcutRules.ALT), compose.activity.store.recordingCandidate)
        compose.onNodeWithText("Ctrl + Alt + Space").assertIsDisplayed()
        evidence("record-global", "Record a shortcut")
        var tabs = 0
        while (runCatching { compose.onNodeWithTag("shortcut-save").assertIsFocused() }.isFailure && tabs++ < 8) key(KeyEvent.KEYCODE_TAB)
        compose.onNodeWithTag("shortcut-save").assertIsFocused()
        key(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON)
        compose.onNodeWithText("Cancel").assertIsFocused()
        assertEquals(KeyChord(KeyEvent.KEYCODE_SPACE, ShortcutRules.CTRL or ShortcutRules.ALT), compose.activity.store.recordingCandidate)
        assertNull(compose.activity.store.recordingError)
        compose.onNodeWithTag("shortcut-save").assertIsEnabled()
        evidence("backward-focus", "Ctrl + Alt + Space")
        key(KeyEvent.KEYCODE_TAB)
        compose.onNodeWithTag("shortcut-save").assertIsFocused()
        key(KeyEvent.KEYCODE_ENTER)
        compose.onNodeWithTag("shortcut-global-value").assertTextEquals("Ctrl + Alt + Space")
        assertNull(ShortcutRuntime.recordingOwner)
        assertEquals(KeyChord(KeyEvent.KEYCODE_SPACE, ShortcutRules.CTRL or ShortcutRules.ALT), PaletteStore(context.applicationContext as android.app.Application).shortcutConfiguration.bindings[ShortcutRules.PALETTE])
        evidence("configured-global", "Ctrl + Alt + Space")
    }
    @Test fun recorderActivationKeysPreserveCandidateUntilReleaseAndSave() {
        settings()
        val activationKeys = listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER)
            .flatMap { code -> listOf(code to 0, code to KeyEvent.META_SHIFT_ON) }
        activationKeys.forEachIndexed { index, (activation, activationModifiers) ->
            compose.onNodeWithTag("shortcut-global-record").performClick()
            val letter = KeyEvent.KEYCODE_G + index
            val expected = KeyChord(letter, ShortcutRules.CTRL or ShortcutRules.ALT)
            key(letter, ctrlAlt)
            if (activation == KeyEvent.KEYCODE_DPAD_CENTER) {
                listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_UP,
                    KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END).forEach {
                    key(it)
                    assertEquals(expected, compose.activity.store.recordingCandidate)
                    assertNull(compose.activity.store.recordingError)
                }
            }
            var tabs = 0
            while (runCatching { compose.onNodeWithTag("shortcut-save").assertIsFocused() }.isFailure && tabs++ < 8) key(KeyEvent.KEYCODE_TAB)
            compose.onNodeWithTag("shortcut-save").assertIsFocused()
            key(KeyEvent.KEYCODE_SPACE)
            assertEquals(expected, compose.activity.store.recordingCandidate)
            assertNull(compose.activity.store.recordingError)
            compose.onNodeWithTag("shortcut-save").assertIsEnabled().assertIsFocused()
            if (activation == KeyEvent.KEYCODE_DPAD_CENTER) {
                key(KeyEvent.KEYCODE_DPAD_LEFT)
                compose.onNodeWithText("Cancel").assertIsFocused()
                assertEquals(expected, compose.activity.store.recordingCandidate)
                assertNull(compose.activity.store.recordingError)
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                compose.onNodeWithTag("shortcut-save").assertIsFocused()
            }
            val downTime = android.os.SystemClock.uptimeMillis()
            fun inject(action: Int) {
                val event = KeyEvent(downTime, android.os.SystemClock.uptimeMillis(), action, activation, 0, activationModifiers,
                    android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, android.view.InputDevice.SOURCE_KEYBOARD)
                assertTrue("Inject activation $activation/$action", testAutomation().injectInputEvent(event, true))
            }
            try {
                inject(KeyEvent.ACTION_DOWN)
                compose.waitForIdle()
                assertEquals("Activation $activation must preserve the candidate", expected, compose.activity.store.recordingCandidate)
                assertNull(compose.activity.store.recordingError)
                compose.onNodeWithTag("shortcut-save").assertIsEnabled()
            } finally { inject(KeyEvent.ACTION_UP) }
            compose.waitForIdle()
            assertNull("Activation $activation must save", ShortcutRuntime.recordingOwner)
            compose.onNodeWithTag("shortcut-global-value").assertTextEquals(expected.label())
            assertEquals(expected, PaletteStore(context.applicationContext as android.app.Application).shortcutConfiguration.bindings[ShortcutRules.PALETTE])
            if (activation == KeyEvent.KEYCODE_NUMPAD_ENTER && activationModifiers == 0) evidence("keypad-enter-saved", expected.label())
        }
    }
    @Test fun actionsAssignCommandKeycapAndLocalKeyRunsTheSameCommand() {
        compose.onNodeWithTag("search").performTextReplacement("Create a note")
        key(KeyEvent.KEYCODE_K, KeyEvent.META_CTRL_ON)
        compose.onNodeWithTag("inline-menu-search").performTextReplacement("keyboard shortcut")
        compose.onNodeWithTag("action-Set keyboard shortcut").assertIsDisplayed()
        // The separate activity route is tested in SettingsRouteDeviceTest.
        compose.activityRule.scenario.onActivity { it.store.beginShortcutRecording("new-note") }
        key(KeyEvent.KEYCODE_N, ctrlAlt)
        compose.onNodeWithTag("shortcut-save").assertIsEnabled().performClick()
        key(KeyEvent.KEYCODE_ESCAPE)
        compose.onNodeWithText("Ctrl + Alt + N").assertIsDisplayed()
        evidence("command-keycap", "Ctrl + Alt + N")
        key(KeyEvent.KEYCODE_N, ctrlAlt)
        compose.onNodeWithTag("editor-title").assertIsDisplayed().assertIsFocused()
        assertEquals("note", compose.activity.store.dialog)
    }
    @Test fun conflictingAndReservedChordsNameTheirOwnerAndCannotSave() {
        compose.activityRule.scenario.onActivity { it.store.setShortcut("system:wifi", wifiChord); it.store.beginShortcutRecording("calculator") }
        key(KeyEvent.KEYCODE_W, ctrlAlt)
        compose.onNodeWithTag("shortcut-record-error").assertTextContains("Wi-Fi settings", substring = true)
        compose.onNodeWithTag("shortcut-save").assertIsNotEnabled()
        evidence("conflict", "Already assigned to Wi-Fi settings")
        key(KeyEvent.KEYCODE_K, KeyEvent.META_CTRL_ON)
        compose.onNodeWithTag("shortcut-record-error").assertTextContains("Reserved", substring = true)
        compose.onNodeWithTag("shortcut-save").assertIsNotEnabled()
        key(KeyEvent.KEYCODE_A)
        compose.onNodeWithTag("shortcut-record-error").assertTextContains("plain typing", substring = true)
        key(KeyEvent.KEYCODE_ESCAPE)
        assertEquals(wifiChord, compose.activity.store.shortcutConfiguration.bindings["system:wifi"])
        assertFalse("calculator" in compose.activity.store.shortcutConfiguration.bindings)
        assertNull(ShortcutRuntime.recordingOwner)
    }
    @Test fun recordingExistingGlobalChordDoesNotActivateItAndBackgroundingCancels() {
        compose.activityRule.scenario.onActivity { it.store.beginShortcutRecording(ShortcutRules.PALETTE) }
        key(KeyEvent.KEYCODE_SPACE, KeyEvent.META_CTRL_ON)
        compose.onNodeWithText("Record a shortcut").assertIsDisplayed()
        assertSame(compose.activity.store, ShortcutRuntime.recordingOwner)
        device.pressHome()
        compose.waitUntil(5000) { ShortcutRuntime.recordingOwner == null }
        assertNull(compose.activity.store.recordingTarget)
        assertEquals(ShortcutConfiguration(), compose.activity.store.shortcutConfiguration)
    }
    @Test fun clearAndResetKeepCollectionsAndAndroidConsentUnchanged() {
        val consent = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        lateinit var before: BackupData
        compose.activityRule.scenario.onActivity {
            before = it.store.backupSnapshot()
            it.store.setShortcut("system:wifi", wifiChord)
            it.store.beginShortcutRecording("system:wifi")
        }
        compose.onNodeWithTag("shortcut-clear").performClick()
        assertFalse("system:wifi" in compose.activity.store.shortcutConfiguration.bindings)
        compose.activityRule.scenario.onActivity { it.store.setShortcut("system:wifi", wifiChord) }
        compose.onNodeWithTag("shortcut-reset").performScrollTo().performClick()
        evidence("reset-confirmation", "Reset all shortcuts?")
        compose.onNodeWithTag("shortcut-reset-confirm").performClick()
        assertEquals(ShortcutConfiguration(), compose.activity.store.shortcutConfiguration)
        assertEquals(before, compose.activity.store.backupSnapshot())
        assertEquals(consent, Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
    }
    @Test fun leftRightModifiersAndLocksNormalizeAndCorruptStorageFailsClosed() {
        val event = KeyEvent(1, 1, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W, 0, KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_ALT_RIGHT_ON or KeyEvent.META_CAPS_LOCK_ON or KeyEvent.META_NUM_LOCK_ON)
        assertEquals(wifiChord, event.asChord())
        val configured = ShortcutConfiguration(mapOf("system:wifi" to wifiChord))
        assertEquals(configured, ShortcutCodec.decode(ShortcutCodec.encode(configured)))
        assertTrue(ShortcutCodec.load("invalid JSON").first.bindings.isEmpty())
        assertNotNull(ShortcutCodec.load("invalid JSON").second)
        assertThrows(IllegalArgumentException::class.java) { ShortcutCodec.decode(ShortcutCodec.encode(configured).put("enabled_accessibility_services", true)) }
    }
    @Test fun authenticatedInvocationUsesNormalFunnelAndRejectsReplayAndRebinding() {
        val chord = KeyChord(KeyEvent.KEYCODE_N, ShortcutRules.CTRL or ShortcutRules.ALT)
        compose.activityRule.scenario.onActivity { it.store.setShortcut("new-note", chord) }
        val token = ShortcutRuntime.requests.issue("new-note", chord)
        device.executeShellCommand("am start -W -n dev.tinycast.android/.MainActivity -a ${ShortcutRuntime.ACTION} --es ${ShortcutRuntime.TOKEN} $token")
        compose.onNodeWithTag("editor-title").assertIsDisplayed()
        device.executeShellCommand("am start -W -n dev.tinycast.android/.MainActivity -a ${ShortcutRuntime.ACTION} --es ${ShortcutRuntime.TOKEN} $token")
        compose.waitUntil(5000) { compose.activity.store.dialog == null }
        compose.onNodeWithText("Shortcut changed or expired. Try again.").assertIsDisplayed()
        val changed = ShortcutRuntime.requests.issue("new-note", chord)
        compose.activityRule.scenario.onActivity { it.store.setShortcut("new-note", KeyChord(KeyEvent.KEYCODE_M, chord.modifiers)) }
        device.executeShellCommand("am start -W -n dev.tinycast.android/.MainActivity -a ${ShortcutRuntime.ACTION} --es ${ShortcutRuntime.TOKEN} $changed")
        compose.onNodeWithTag("editor-title").assertDoesNotExist()
        assertEquals(Lifecycle.State.RESUMED, compose.activityRule.scenario.state)
    }
    @Test fun forgedExternalIntentCannotExecuteACommand() {
        compose.activityRule.scenario.onActivity { it.store.setShortcut("system:wifi", wifiChord) }
        device.executeShellCommand("am start -W -n dev.tinycast.android/.MainActivity -a ${ShortcutRuntime.ACTION} --es ${ShortcutRuntime.TOKEN} forged --es commandId system:wifi")
        compose.onNodeWithText("Shortcut changed or expired. Try again.").assertIsDisplayed()
        assertEquals(Lifecycle.State.RESUMED, compose.activityRule.scenario.state)
    }
    @Test fun backupRestoresBindingsOnlyAfterConfirmationAndPreservesDocumentBindings() {
        val consent = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val documentGrants = context.contentResolver.persistedUriPermissions.map { it.uri }.toSet()
        lateinit var fileId: String
        val fileChord = KeyChord(KeyEvent.KEYCODE_F, ShortcutRules.CTRL or ShortcutRules.ALT)
        compose.activityRule.scenario.onActivity {
            fileId = it.store.save("files", "Shortcut local document fixture", "content://dev.droidcast.test/local")
            it.store.setShortcut(fileId, fileChord)
        }
        val imported = ShortcutConfiguration(mapOf(ShortcutRules.PALETTE to KeyChord(KeyEvent.KEYCODE_SPACE, ShortcutRules.CTRL or ShortcutRules.ALT), "system:wifi" to wifiChord))
        val before = compose.activity.store.shortcutConfiguration
        load(BackupDocument(BackupData(shortcuts = imported), setOf(BackupCategory.SHORTCUTS)))
        assertEquals(before, compose.activity.store.shortcutConfiguration)
        compose.onNodeWithTag("backup-policy-REPLACE").performScrollTo().performClick()
        compose.onNodeWithTag("backup-review").performScrollTo().performClick()
        compose.onNodeWithTag("backup-shortcut-warning").performScrollTo().assertIsDisplayed()
        evidence("backup-confirmation", "keyboard bindings take effect")
        compose.onNodeWithTag("backup-confirm").performScrollTo().performClick()
        compose.waitUntil(10000) { !compose.activity.store.backupBusy && compose.activity.store.dialog == "backup" }
        assertEquals(imported.bindings + (fileId to fileChord), compose.activity.store.shortcutConfiguration.bindings)
        val portable = BackupCodec.encode(compose.activity.store.backupSnapshot(), setOf(BackupCategory.SHORTCUTS))
        assertFalse(portable.contains(fileId)); assertFalse(portable.contains("content://"))
        assertEquals(imported, BackupCodec.decode(portable).data.shortcuts)
        assertEquals(consent, Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        assertEquals(documentGrants, context.contentResolver.persistedUriPermissions.map { it.uri }.toSet())
        assertEquals(compose.activity.store.shortcutConfiguration, PaletteStore(context.applicationContext as android.app.Application).shortcutConfiguration)
    }
    @Test fun unavailableImportedCommandHasAnEditableDormantBinding() {
        val target = "app:dev.droidcast.missing/dev.droidcast.missing.Main"
        val chord = KeyChord(KeyEvent.KEYCODE_U, ShortcutRules.CTRL or ShortcutRules.ALT)
        load(BackupDocument(BackupData(shortcuts = ShortcutConfiguration(mapOf(target to chord))), setOf(BackupCategory.SHORTCUTS)))
        compose.onNodeWithTag("backup-policy-REPLACE").performScrollTo().performClick()
        compose.onNodeWithTag("backup-review").performScrollTo().performClick()
        compose.onNodeWithTag("backup-confirm").performScrollTo().performClick()
        compose.waitUntil(10000) { !compose.activity.store.backupBusy }
        val token = ShortcutRuntime.requests.issue(target, chord)
        compose.activityRule.scenario.onActivity { it.acceptShortcut(token) }
        compose.waitUntil(5000) { compose.activity.store.message?.contains("command is unavailable") == true }
        try {
        compose.onNodeWithText("This shortcut's command is unavailable. Clear or reassign it in Keyboard shortcuts.").assertIsDisplayed()
        } catch (failure: Throwable) {
            val folder = File(context.filesDir,"shortcut-evidence").apply { mkdirs() }
            File(folder,"unavailable-feedback-failure-semantics.tsv").writeText(compose.onRoot(useUnmergedTree=true).printToString())
            device.dumpWindowHierarchy(File(folder,"unavailable-feedback-failure.xml"))
            device.takeScreenshot(File(folder,"unavailable-feedback-failure.png"))
            throw failure
        }
        compose.activityRule.scenario.onActivity { it.store.dialog = "shortcut-settings" }
        // Scroll the outer form first; scrolling a row inside the nested LazyColumn
        // cannot reveal that LazyColumn when it is below the form's viewport.
        compose.onNodeWithTag("shortcut-bindings").performScrollTo()
        try {
        compose.onNodeWithTag("shortcut-remove-$target").performScrollTo().assertIsDisplayed().performClick()
        } catch (failure: Throwable) {
            val folder=File(context.filesDir,"shortcut-evidence").apply { mkdirs() }
            File(folder,"unavailable-clear-failure-semantics.tsv").writeText(compose.onRoot(useUnmergedTree=true).printToString())
            device.dumpWindowHierarchy(File(folder,"unavailable-clear-failure.xml"))
            device.takeScreenshot(File(folder,"unavailable-clear-failure.png"))
            throw failure
        }
        assertTrue(compose.activity.store.shortcutConfiguration.bindings.isEmpty())
    }
    @Test fun documentShortcutImportIsRejectedAndOldBackupKeepsCurrentBindings() {
        compose.activityRule.scenario.onActivity { it.store.setShortcut("system:wifi", wifiChord) }
        val before = compose.activity.store.shortcutConfiguration
        val fileConfig = ShortcutCodec.encode(ShortcutConfiguration(mapOf("files:foreign" to wifiChord)))
        val hostile = JSONObject().put("format", "dev.droidcast.backup").put("version", 1).put("categories", JSONObject().put("SHORTCUTS", fileConfig))
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(hostile.toString()) }
        load(BackupDocument(BackupData(notes = listOf(SavedItem("notes:old-format", "Old backup note", "No shortcut category"))), setOf(BackupCategory.NOTES)))
        compose.onNodeWithTag("backup-review").performScrollTo().performClick()
        compose.onNodeWithTag("backup-confirm").performScrollTo().performClick()
        compose.waitUntil(10000) { !compose.activity.store.backupBusy }
        assertEquals(before, compose.activity.store.shortcutConfiguration)
    }
    @OptIn(ExperimentalTestApi::class)
    @Test fun calculatorShortcutTogglesItsExistingPaletteMode() {
        compose.activityRule.scenario.onActivity { it.store.setShortcut("calculator", KeyChord(KeyEvent.KEYCODE_C, ShortcutRules.CTRL or ShortcutRules.ALT)) }
        key(KeyEvent.KEYCODE_C, ctrlAlt)
        compose.onNodeWithTag("search").assertTextEquals("= ")
        compose.onNodeWithTag("search").performTextInput("12*7")
        compose.onNodeWithTag("result-result").assertTextContains("84")
        compose.onNodeWithTag("search").performTextInputSelection(androidx.compose.ui.text.TextRange(2, 4))
        compose.onNodeWithTag("search").performTextInput("3")
        compose.onNodeWithTag("search").assertTextEquals("= 3*7")
        compose.onNodeWithTag("result-result").assertTextContains("21")
        compose.waitForIdle(); device.waitForIdle(); device.pressKeyCode(KeyEvent.KEYCODE_C, ctrlAlt)
        compose.waitUntil(5000) { compose.activityRule.scenario.state == Lifecycle.State.DESTROYED }
    }
}
