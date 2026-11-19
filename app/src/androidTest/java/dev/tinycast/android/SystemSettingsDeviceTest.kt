package dev.tinycast.android

import android.app.Instrumentation
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference
import java.io.File

@RunWith(AndroidJUnit4::class)
class SystemSettingsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun launches(query: String, id: String, action: String, destinationPackage: String = "com.android.settings") {
        compose.onNodeWithTag("search").performTextReplacement(query)
        compose.waitForIdle()
        if (id in setOf("system:battery", "system:security")) {
            compose.waitUntil(7000) { !compose.activity.store.loading && !compose.activity.store.folderSearch.loading }
            capture(id.substringAfter(':') + "-search")
        }
        assertEquals(id, compose.activity.store.results().firstOrNull()?.id)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = compose.activity.packageName
        val launched = AtomicReference<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == action) launched.compareAndSet(null, Intent(intent))
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            compose.onNodeWithTag("result-$id").performClick()
            compose.waitUntil(7000) { launched.get() != null }
            assertEquals(action, launched.get().action)
            if (id == "system:app-info") assertEquals("package:$packageName", launched.get().dataString)
            if (id == "system:notifications") assertEquals(packageName, launched.get().getStringExtra(Settings.EXTRA_APP_PACKAGE))
            compose.waitUntil(7000) { compose.activityRule.scenario.state == Lifecycle.State.DESTROYED }
            assertTrue(device.wait(Until.hasObject(By.pkg(destinationPackage)), 7000))
            device.waitForIdle()
            assertEquals(destinationPackage, device.currentPackageName)
            val markers = when (id) {
                "system:battery" -> listOf(By.text("Battery usage"), By.text("Battery Saver"))
                "system:security" -> listOf(By.text("Device unlock"), By.text("App security"))
                "system:wifi" -> listOf(By.desc("Internet"), By.text("Wi-Fi"))
                "system:bluetooth" -> listOf(By.desc("Connected devices"), By.text("Pair new device"))
                "system:display" -> listOf(By.text("Brightness level"), By.text("Dark theme"))
                "system:sound" -> listOf(By.text("Media volume"), By.text("Alarm volume"))
                "system:notifications" -> listOf(By.text("All Droidcast notifications"))
                "system:apps" -> listOf(By.text("Android System WebView"), By.text("Calendar"))
                "system:app-info" -> listOf(By.text("App info"), By.text("Droidcast"), By.text("Force stop"), By.text("Storage & cache"))
                "system:storage" -> listOf(By.text("Free up space"), By.text("Go to Files app to manage and free up space"))
                "system:accessibility" -> listOf(By.text("Downloaded apps"), By.text("TalkBack"))
                "system:date" -> listOf(By.text("Automatic date and time"), By.text("Automatic time zone"))
                "system:language" -> listOf(By.text("Preferred language order"), By.text("Add a language"))
                else -> error("Define destination-specific assertions for $id")
            }
            markers.forEach { marker -> assertTrue("Missing destination-specific UI for $id: $marker", device.wait(Until.hasObject(marker.pkg(destinationPackage)), 10000)) }
            device.waitForIdle()
            assertFalse("Expected a dedicated page, not root Settings", device.hasObject(By.text("Search Settings")))
            capture(id.substringAfter(':'))
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun capture(name: String) {
        device.waitForIdle()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "system-settings-evidence").apply { mkdirs() }
        device.dumpWindowHierarchy(File(dir, "$name.xml"))
        assertTrue(device.takeScreenshot(File(dir, "$name.png")))
    }

    @Test fun deniedSecurityDashboardPreservesTheSearch() {
        val query = "Security settings"
        compose.onNodeWithTag("search").performTextReplacement(query)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val requested = SystemSettings.find("system:security")!!.action
        var denials = 0
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == requested) {
                    denials++
                    throw SecurityException("Test denied the security settings launch")
                }
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            compose.onNodeWithTag("result-system:security").performClick()
            compose.onNodeWithText("This device can’t open Security settings.").assertIsDisplayed()
            compose.onNodeWithTag("search").assertTextContains(query)
            assertEquals(1, denials)
            assertEquals(Lifecycle.State.RESUMED, compose.activityRule.scenario.state)
            capture("security-denied")
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test fun batterySearchOpensUsageOverview() =
        launches("Battery settings", "system:battery", Intent.ACTION_POWER_USAGE_SUMMARY)

    @Test fun securitySearchOpensSafetyCenter() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 33)
        val resolved = device.executeShellCommand("cmd package resolve-activity --brief -a ${Intent.ACTION_SAFETY_CENTER}")
        val owner = requireNotNull(android.content.ComponentName.unflattenFromString(resolved.trim().lineSequence().last())).packageName
        launches("Security settings", "system:security", Intent.ACTION_SAFETY_CENTER, owner)
    }

    @Test fun wifiSearchOpensWifiPage() = launches("wifi", "system:wifi", Settings.ACTION_WIFI_SETTINGS)
    @Test fun bluetoothSearchOpensBluetoothPage() = launches("bluetooth", "system:bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS)
    @Test fun brightnessSearchOpensDisplayPage() = launches("brightness", "system:display", Settings.ACTION_DISPLAY_SETTINGS)
    @Test fun appInfoReplacesAnExistingAccessibilityPageWithDroidcastDetails() {
        compose.activityRule.scenario.onActivity { it.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
        assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings").text("Downloaded apps")), 7000))
        device.executeShellCommand("am start -W -n dev.tinycast.android/.MainActivity")
        compose.waitForIdle()
        launches("Droidcast app info", "system:app-info", Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    }
    @Test fun soundOpensSoundPage() = launches("Sound settings", "system:sound", Settings.ACTION_SOUND_SETTINGS)
    @Test fun notificationsOpenDroidcastNotificationPage() = launches("Droidcast notifications", "system:notifications", Settings.ACTION_APP_NOTIFICATION_SETTINGS)
    @Test fun appsOpenApplicationManagement() = launches("Apps settings", "system:apps", Settings.ACTION_APPLICATION_SETTINGS)
    @Test fun storageOpensDeviceStoragePage() = launches("Storage settings", "system:storage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
    @Test fun accessibilityOpensAccessibilityPage() = launches("Accessibility settings", "system:accessibility", Settings.ACTION_ACCESSIBILITY_SETTINGS)
    @Test fun dateOpensDateTimePage() = launches("Date & time settings", "system:date", Settings.ACTION_DATE_SETTINGS)
    @Test fun languageOpensLanguagePage() = launches("Language settings", "system:language", Settings.ACTION_LOCALE_SETTINGS)

    @Test fun appScopedIntentsCarryOnlyTheRequiredPackageArgument() {
        val packageName = compose.activity.packageName
        val info = SystemSettings.find("system:app-info")!!.intent(packageName)
        assertEquals("package:$packageName", info.dataString)
        assertNull(info.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        val notifications = SystemSettings.find("system:notifications")!!.intent(packageName)
        assertEquals(packageName, notifications.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertNull(notifications.data)
        SystemSettings.destinations.filter { it.packageArgument == SettingsPackageArgument.NONE }.forEach {
            val intent = it.intent(packageName)
            assertNull(it.id, intent.data)
            assertNull(it.id, intent.extras)
        }
    }

    @Test fun missingDestinationReportsErrorAndKeepsPaletteQuery() {
        compose.onNodeWithTag("search").performTextReplacement("unavailable settings test")
        compose.activityRule.scenario.onActivity {
            it.openSystemSettings(SettingsDestination("test:unavailable", "Unavailable settings test", "", "dev.droidcast.NO_SETTINGS_HANDLER", ""))
        }
        compose.waitUntil(5000) {
            runCatching { compose.onNodeWithText("This device can’t open Unavailable settings test.").assertIsDisplayed(); true }.getOrDefault(false)
        }
        compose.onNodeWithTag("search").assertTextContains("unavailable settings test")
        assertEquals(Lifecycle.State.RESUMED, compose.activityRule.scenario.state)
        val evidence = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "system-settings-evidence").apply { mkdirs() }
        device.takeScreenshot(File(evidence, "unavailable.png"))
    }
}
