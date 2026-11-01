package dev.tinycast.android

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Consent is changed only on the dedicated emulator, then restored exactly. */
@RunWith(AndroidJUnit4::class)
class ShortcutReadinessDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<SettingsActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private var original = emptyMap<String, Any?>()
    private var originalServices = "null"
    private var originalEnabled = "null"
    private var originalFlags = 0
    private var captured = false
    private val component get() = ComponentName(context, PaletteShortcutService::class.java)
    private val altQ = KeyChord(KeyEvent.KEYCODE_Q, ShortcutRules.ALT)
    private fun shell(value: String) = device.executeShellCommand(value).trim()
    private fun setting(name: String, value: String) {
        require(name in setOf("enabled_accessibility_services", "accessibility_enabled"))
        // UiDevice uses a direct command parser, not a shell: quoting would
        // persist literal quote characters. Only validated single tokens enter it.
        require(value == "null" || if (name == "accessibility_enabled") value in setOf("0", "1")
            else value.matches(Regex("[A-Za-z0-9_.$/]+(:[A-Za-z0-9_.$/]+)*")) && value.split(':').all { ComponentName.unflattenFromString(it) != null })
        shell(if (value == "null") "settings delete secure $name" else "settings put secure $name $value")
        assertEquals(value, shell("settings get secure $name"))
    }
    private fun services(enabled: Boolean, short: Boolean = false) {
        val keep = originalServices.split(':').filter { it != "null" && it.isNotBlank() && ComponentName.unflattenFromString(it) != component }
        val activeKeep = if (enabled && originalEnabled != "1") emptyList() else keep
        val own = if (short) component.flattenToShortString() else component.flattenToString()
        setting("enabled_accessibility_services", (activeKeep + if (enabled) listOf(own) else emptyList()).joinToString(":").ifEmpty { "null" })
        setting("accessibility_enabled", if (enabled) "1" else if (keep.isEmpty()) "0" else originalEnabled)
    }
    @Before fun setup() {
        originalFlags = Configurator.getInstance().uiAutomationFlags
        Configurator.getInstance().uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        Assume.assumeTrue("Dedicated emulator only", shell("getprop ro.kernel.qemu") == "1" && shell("getprop ro.boot.qemu.avd_name") == InstrumentationRegistry.getArguments().getString("dedicatedAvd", "Tinycast_Tablet_API_36"))
        original = context.getSharedPreferences("palette", Context.MODE_PRIVATE).all.toMap()
        originalServices = shell("settings get secure enabled_accessibility_services")
        originalEnabled = shell("settings get secure accessibility_enabled")
        captured = true
        services(false)
        compose.waitUntil(10000) { !ShortcutRuntime.keyboardConnection.connected && !compose.activity.store.loading }
        compose.activityRule.scenario.onActivity { it.store.resetShortcuts(); it.store.setShortcut(ShortcutRules.PALETTE, altQ); it.selectPage("general") }
        compose.waitUntil(5000) { compose.activity.serviceConsent == KeyboardConsent.OFF }
    }
    @After fun restore() {
        try {
            if (captured) {
                restorePalettePreferences(context, original)
                setting("enabled_accessibility_services", originalServices)
                setting("accessibility_enabled", originalEnabled)
                assertEquals(originalServices, shell("settings get secure enabled_accessibility_services"))
                assertEquals(originalEnabled, shell("settings get secure accessibility_enabled"))
                File(context.filesDir, "readiness-evidence").apply { mkdirs() }.resolve("restoration.json")
                    .writeText("{\"keyboard_consent_restored\":true,\"palette_preferences_restored\":true}")
            }
        } finally { Configurator.getInstance().uiAutomationFlags = originalFlags }
    }
    private fun capture(name: String, tag: String, scroll: Boolean = true) {
        val target = compose.onNodeWithTag(tag)
        if (scroll) target.performScrollTo()
        target.assertIsDisplayed()
        compose.waitForIdle(); device.waitForIdle()
        val dir = File(context.filesDir, "readiness-evidence").apply { mkdirs() }
        val xml = File(dir, "$name.xml")
        var present = false
        repeat(4) {
            if (!present) {
                if (android.os.Build.VERSION.SDK_INT >= 34) testAutomation().clearCacheForTest()
                device.wait(Until.hasObject(By.pkg(context.packageName).res(tag)), 1500)
                device.dumpWindowHierarchy(xml)
                present = xml.readText().contains("resource-id=\"$tag\"") &&
                    xml.readText().contains("package=\"${context.packageName}\"")
            }
        }
        assertTrue("Native hierarchy must include the pictured app control $tag", present)
        assertTrue(device.takeScreenshot(File(dir, "$name.png")))
    }
    private fun connected(short: Boolean = false) {
        services(true, short)
        compose.waitUntil(10000) { ShortcutRuntime.keyboardConnection.connected && compose.activity.serviceConsent == KeyboardConsent.APPROVED }
        compose.onNodeWithTag("settings-service-connection").performScrollTo().assertTextEquals("Connected")
        compose.onNodeWithTag("settings-shortcut-readiness").assertTextEquals("Ready to check shortcut")
    }
    private fun backToWorkspace() {
        repeat(5) {
            if (device.currentPackageName == context.packageName) return
            device.pressBack(); device.waitForIdle()
        }
        assertEquals(context.packageName, device.currentPackageName)
    }
    @Test fun componentParsingAcceptsFullAndShortNamesButNeverSimilarOwners() {
        val full = component.flattenToString(); val short = component.flattenToShortString()
        assertTrue(KeyboardServiceConsent.contains(full, true, component))
        assertTrue(KeyboardServiceConsent.contains("dev.other/.Service:$short", true, component))
        assertFalse(KeyboardServiceConsent.contains(short, false, component))
        assertFalse(KeyboardServiceConsent.contains(full + "Extra", true, component))
        assertFalse(KeyboardServiceConsent.contains("dev.tinycast.android.extra/.PaletteShortcutService", true, component))
        assertFalse(KeyboardServiceConsent.contains("malformed::", true, component))
        assertFalse(KeyboardServiceConsent.contains(null, true, component))
    }
    @Test fun setupExplainsApprovalConnectionRestrictedSettingsAndAlternativesInline() {
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithTag("settings-global-value").performScrollTo().assertTextEquals("Alt + Q")
        compose.onNodeWithTag("settings-service-connection").assertTextEquals("Not connected")
        compose.onNodeWithTag("settings-shortcut-readiness").assertTextEquals("Android setup required")
        compose.onNodeWithTag("settings-shortcut-test").assertIsNotEnabled()
        capture("approval-off", "settings-shortcut-readiness")
        compose.onNodeWithTag("settings-shortcut-steps").performScrollTo().assertTextContains("Allow restricted settings", substring = true)
        compose.onNodeWithTag("settings-shortcut-app-info").performScrollTo().assertIsDisplayed()
        capture("setup-steps", "settings-shortcut-app-info")
        compose.onNodeWithTag("settings-shortcut-alternatives").performScrollTo().assertTextContains("Quick Settings", substring = true)
        capture("alternatives", "settings-shortcut-open-palette")
    }
    @Test fun appInfoAndAccessibilityLinksOpenSpecificAndroidDestinations() {
        compose.onNodeWithTag("settings-service").performScrollTo().performClick()
        listOf("Downloaded apps", "Droidcast keyboard shortcuts", "TalkBack").forEach { marker ->
            assertTrue("Accessibility destination must show $marker", device.wait(Until.hasObject(By.pkg("com.android.settings").text(marker)), 7000))
        }
        File(context.filesDir, "readiness-evidence").apply { mkdirs() }.let { dir -> device.dumpWindowHierarchy(File(dir, "accessibility.xml")); assertTrue(device.takeScreenshot(File(dir, "accessibility.png"))) }
        backToWorkspace()
        compose.onNodeWithTag("settings-shortcut-app-info").performScrollTo().performClick()
        assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings").text("App info")), 7000))
        assertTrue(device.wait(Until.hasObject(By.text("Droidcast")), 5000))
        assertTrue(device.wait(Until.hasObject(By.text("Force stop")), 5000))
        assertTrue(device.wait(Until.hasObject(By.text("Storage & cache")), 5000))
        File(context.filesDir, "readiness-evidence").let { dir -> device.dumpWindowHierarchy(File(dir, "app-info.xml")); assertTrue(device.takeScreenshot(File(dir, "app-info.png"))) }
        backToWorkspace()
        compose.onNodeWithTag("settings-workspace").assertIsDisplayed()
    }
    @Test fun approvalAndLiveConnectionUpdateAfterDisableAndShortNameReconnect() {
        connected()
        compose.onNodeWithTag("settings-shortcut-test").performScrollTo().assertIsEnabled()
        capture("connected", "settings-shortcut-readiness")
        services(false)
        compose.waitUntil(10000) { !ShortcutRuntime.keyboardConnection.connected && compose.activity.serviceConsent == KeyboardConsent.OFF }
        compose.onNodeWithTag("settings-shortcut-test").assertIsNotEnabled()
        capture("disconnected", "settings-shortcut-readiness")
        connected(short = true)
        capture("reconnected-short-name", "settings-shortcut-readiness")
    }
    @Test fun inlineCheckCancelsOnNavigationAndDoesNotEnableConsent() {
        connected()
        compose.onNodeWithTag("settings-shortcut-test").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.activity.shortcutProbe.status == ShortcutProbeStatus.LISTENING }
        assertEquals(altQ, compose.activity.shortcutProbe.expected)
        capture("listening", "settings-shortcut-test-result")
        compose.activityRule.scenario.onActivity { it.selectPage("shortcuts") }
        assertEquals(ShortcutProbeStatus.IDLE, compose.activity.shortcutProbe.status)
        compose.activityRule.scenario.onActivity { it.selectPage("general") }
        compose.onNodeWithTag("settings-shortcut-test").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.activity.shortcutProbe.status == ShortcutProbeStatus.LISTENING }
        device.pressHome()
        compose.waitUntil(5000) { compose.activity.shortcutProbe.status == ShortcutProbeStatus.IDLE }
        assertEquals(KeyboardConsent.APPROVED, KeyboardServiceConsent.read(context))
    }
    @Test fun inlineCheckExpiresWithoutClaimingGlobalSuccess() {
        connected()
        compose.onNodeWithTag("settings-shortcut-test").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.activity.shortcutProbe.status == ShortcutProbeStatus.LISTENING }
        compose.waitUntil(20000) { compose.activity.shortcutProbe.status == ShortcutProbeStatus.EXPIRED }
        compose.onNodeWithTag("settings-shortcut-test-result").assertTextContains("Nothing received", substring = true)
        capture("timeout", "settings-shortcut-test-result")
    }
    @Test fun settingsSearchCancelsHiddenProbeAndLeavesNormalActivationAvailable() {
        connected()
        val activity = compose.activity
        compose.onNodeWithTag("settings-shortcut-test").performScrollTo().performClick()
        compose.waitUntil(5000) { activity.shortcutProbe.status == ShortcutProbeStatus.LISTENING }
        device.pressKeyCode(KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON)
        compose.onNodeWithTag("settings-search").assertIsFocused().performTextInput("backup")
        compose.waitForIdle()
        capture("search-probe-cancelled", "settings-search", scroll = false)
        assertEquals("Search must cancel the hidden check", ShortcutProbeStatus.IDLE, activity.shortcutProbe.status)
        compose.runOnUiThread { ShortcutRuntime.dispatch(context, ShortcutRules.PALETTE, altQ) }
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName).text("Search for apps and commands…")), 7000))
        assertEquals(ShortcutProbeStatus.IDLE, activity.shortcutProbe.status)
    }
    @Test fun disconnectReconnectBeforeRedrawCannotCompleteAnOldProbe() {
        connected()
        compose.onNodeWithTag("settings-shortcut-test").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.activity.shortcutProbe.status == ShortcutProbeStatus.LISTENING }
        val connection = ShortcutRuntime.keyboardConnection
        // Deterministic service-callback ordering, not a claim about OEM timing.
        val ownerField = KeyboardServiceConnection::class.java.getDeclaredField("owner").apply { isAccessible = true }
        compose.runOnUiThread {
            val actualOwner = ownerField.get(connection)!!
            val replacement = Any()
            try {
                connection.disconnect(actualOwner); connection.connect(replacement)
                val accepted = compose.activity.receiveShortcutProbe(ShortcutRules.PALETTE, altQ)
                assertFalse("A replacement connection must reject the old probe before recomposition", accepted)
                assertEquals(ShortcutProbeStatus.IDLE, compose.activity.shortcutProbe.status)
            } finally {
                connection.disconnect(replacement); connection.connect(actualOwner)
            }
        }
        compose.waitForIdle()
    }
    @Test fun explicitlyArmedNavigationChordsReachProbeAndRestoreNavigationAfterReceipt() {
        connected()
        val chords = listOf(
            KeyChord(KeyEvent.KEYCODE_F, ShortcutRules.CTRL), KeyChord(KeyEvent.KEYCODE_F, ShortcutRules.META),
            KeyChord(KeyEvent.KEYCODE_COMMA, ShortcutRules.CTRL), KeyChord(KeyEvent.KEYCODE_COMMA, ShortcutRules.CTRL or ShortcutRules.SHIFT),
            KeyChord(KeyEvent.KEYCODE_COMMA, ShortcutRules.META), KeyChord(KeyEvent.KEYCODE_COMMA, ShortcutRules.META or ShortcutRules.SHIFT))
        for (chord in chords) {
            compose.runOnUiThread { compose.activity.store.setShortcut(ShortcutRules.PALETTE, chord) }
            compose.waitForIdle()
            compose.onNodeWithTag("settings-shortcut-test").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.activity.shortcutProbe.status == ShortcutProbeStatus.LISTENING }
            val meta = (if (chord.modifiers and ShortcutRules.CTRL != 0) KeyEvent.META_CTRL_ON else KeyEvent.META_META_ON) or
                (if (chord.modifiers and ShortcutRules.SHIFT != 0) KeyEvent.META_SHIFT_ON else 0)
            val down = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, chord.keyCode, 0, meta)
            compose.runOnUiThread {
                assertFalse("Armed ${chord.label()} must reach the service", ShortcutRuntime.passesSettingsNavigation(down))
                val engine = ShortcutEngine()
                val result = engine.handle(down.asShortcutInput(), compose.activity.store.shortcutConfiguration.byChord,
                    paused = ShortcutRuntime.passesSettingsNavigation(down))
                assertEquals(ShortcutRules.PALETTE, result.target)
                ShortcutRuntime.dispatch(context, result.target!!, result.chord!!)
                assertEquals(ShortcutProbeStatus.RECEIVED, compose.activity.shortcutProbe.status)
                assertTrue("Normal navigation resumes after receipt", ShortcutRuntime.passesSettingsNavigation(down))
                val up = KeyEvent(0, 0, KeyEvent.ACTION_UP, chord.keyCode, 0, 0)
                assertTrue("Matching release stays owned even after the check completes", engine.handle(up.asShortcutInput(), emptyMap(), paused = true).consumed)
            }
        }
        capture("navigation-chord-received", "settings-shortcut-test-result")
    }
    @Test fun unassignedShortcutAndPermissionFreePaletteRemainAvailable() {
        compose.activityRule.scenario.onActivity { it.store.setShortcut(ShortcutRules.PALETTE, null) }
        compose.onNodeWithTag("settings-global-value").performScrollTo().assertTextEquals("Not assigned")
        compose.onNodeWithTag("settings-shortcut-readiness").assertTextEquals("Choose a global shortcut")
        compose.onNodeWithTag("settings-shortcut-test").assertIsNotEnabled()
        capture("unassigned", "settings-shortcut-readiness")
        compose.onNodeWithTag("settings-shortcut-open-palette").performScrollTo().performClick()
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName).text("Search for apps and commands…")), 7000))
        assertEquals(KeyboardConsent.OFF, KeyboardServiceConsent.read(context))
        File(context.filesDir, "readiness-evidence").let { dir -> device.dumpWindowHierarchy(File(dir, "palette-fallback.xml")); assertTrue(device.takeScreenshot(File(dir, "palette-fallback.png"))) }
    }
}
