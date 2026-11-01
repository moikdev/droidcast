package dev.tinycast.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.provider.Settings
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@androidx.test.filters.SdkSuppress(minSdkVersion = 29)
@RunWith(AndroidJUnit4::class)
class AssistantTouchDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<SettingsActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val automation get() = testAutomation()
    private val dir get() = File(context.filesDir, "assistant-touch-evidence").apply { mkdirs() }
    private var captured = false
    private var originalRoles = emptyList<String>()
    private var originalSettings = emptyMap<String, String>()
    private var originalOverlay = ""
    private var originalFlags = 0
    private var originalServiceFlags = 0
    private var automationCaptured = false
    private val lifecycleEvents = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val lifecycleCallback = ActivityLifecycleCallback { activity, stage ->
        if (activity is MainActivity) lifecycleEvents += "${android.os.SystemClock.elapsedRealtime()} MainActivity $stage"
    }
    private fun shell(command: String) = device.executeShellCommand(command).trim()
    private fun roles() = shell("cmd role get-role-holders android.app.role.ASSISTANT").lineSequence().filter { it.isNotBlank() }.toList()
    private fun setting(key: String, value: String) {
        require(key in originalSettings.keys)
        require(value == "null" || value.matches(Regex("[A-Za-z0-9_./:$-]+")))
        shell(if (value == "null") "settings delete secure $key" else "settings put secure $key $value")
        assertEquals(value, shell("settings get secure $key"))
    }
    @Before fun setup() {
        Assume.assumeTrue(shell("getprop ro.kernel.qemu") == "1" && shell("getprop ro.boot.qemu.avd_name") ==
            InstrumentationRegistry.getArguments().getString("dedicatedAvd", "Tinycast_Tablet_API_36"))
        assertTrue(shell("id").startsWith("uid=2000"))
        originalFlags = Configurator.getInstance().uiAutomationFlags
        Configurator.getInstance().uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        originalServiceFlags = automation.serviceInfo.flags
        automationCaptured = true
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        originalRoles = roles()
        originalSettings = listOf("assistant", "voice_interaction_service", "voice_recognition_service", "navigation_mode",
            "assist_touch_gesture_enabled", "assist_long_press_home_enabled", "search_all_entrypoints_enabled",
            "enabled_accessibility_services", "accessibility_enabled").associateWith { shell("settings get secure $it") }
        val enabled = Regex("(?m)^\\[x\\] (com\\.android\\.internal\\.systemui\\.navbar\\.\\w+)$").findAll(shell("cmd overlay list")).map { it.groupValues[1] }.toList()
        assertEquals(1, enabled.size); originalOverlay = enabled.single()
        captured = true
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(lifecycleCallback)
        setting("enabled_accessibility_services", "null"); setting("accessibility_enabled", "0")
        roles().filter { it == context.packageName }.forEach { shell("cmd role remove-role-holder android.app.role.ASSISTANT $it") }
        compose.activityRule.scenario.onActivity { it.selectPage("general") }
        compose.waitForIdle()
    }
    @After fun restore() {
        try {
            if (captured) {
                device.pressBack()
                roles().filter { it !in originalRoles }.forEach { shell("cmd role remove-role-holder android.app.role.ASSISTANT $it") }
                originalRoles.forEach { require(it.matches(Regex("[A-Za-z0-9_.]+"))); if (it !in roles()) shell("cmd role add-role-holder android.app.role.ASSISTANT $it") }
                shell("cmd overlay enable-exclusive --category $originalOverlay")
                originalSettings.forEach(::setting)
                assertEquals(originalRoles, roles())
                assertEquals(originalSettings, originalSettings.keys.associateWith { shell("settings get secure $it") })
                dir.resolve("restoration.json").writeText("{\"assistant_restored\":true,\"navigation_restored\":true,\"keyboard_consent_restored\":true}")
            }
        } finally {
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(lifecycleCallback)
            if (automationCaptured) {
                automation.serviceInfo = automation.serviceInfo.apply { flags = originalServiceFlags }
                Configurator.getInstance().uiAutomationFlags = originalFlags
            }
        }
    }
    private fun capture(name: String, marker: String? = null, expectedPackage: String = context.packageName) {
        device.waitForIdle()
        if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache()
        if (marker != null) assertTrue("Pictured native state: $marker", device.wait(Until.hasObject(By.pkg(expectedPackage).textContains(marker)), 5000))
        device.dumpWindowHierarchy(dir.resolve("$name.xml"))
        if (marker != null) {
            val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(dir.resolve("$name.xml"))
            val nodes = doc.getElementsByTagName("node")
            assertTrue((0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }.any {
                it.getAttribute("package") == expectedPackage && marker in it.getAttribute("text")
            })
        }
        assertTrue(device.takeScreenshot(dir.resolve("$name.png")))
    }
    private fun chooseDroidcast() {
        compose.onNodeWithTag("settings-choose-assistant").performScrollTo().performClick()
        device.wait(Until.findObject(By.text("Default digital assistant app")), 7000)?.click()
            ?: throw AssertionError("Assistant selection entry missing")
        val option = device.wait(Until.findObject(By.text("Droidcast")), 7000) ?: throw AssertionError("Droidcast role choice missing")
        option.click()
        device.wait(Until.findObject(By.text("OK")), 3000)?.click()
        compose.waitUntil(10000) { context.packageName in roles() }
        assertEquals("null", shell("settings get secure enabled_accessibility_services"))
        assertFalse(ShortcutRuntime.keyboardConnection.connected)
    }
    private fun navigation(buttons: Boolean) {
        val overlay = if (buttons) "threebutton" else "gestural"
        shell("cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.$overlay")
        compose.waitUntil(10000) { shell("settings get secure navigation_mode") == if (buttons) "0" else "2" }
        setting(if (buttons) "assist_long_press_home_enabled" else "assist_touch_gesture_enabled", "1")
    }
    private fun destination(home: Boolean) {
        if (home) device.pressHome()
        else context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        assertTrue(device.wait(Until.gone(By.pkg(context.packageName)), 7000))
        device.waitForIdle()
    }
    private fun palette(name: String) {
        try {
            // UiDevice polling alone does not advance Compose's test clock for
            // a cold MainActivity outside this SettingsActivity rule.
            compose.waitForIdle()
            compose.waitUntil(10000) { device.hasObject(By.pkg(context.packageName).clazz("android.widget.EditText")) }
            assertTrue(shell("dumpsys window").lineSequence().any { "mCurrentFocus=" in it && context.packageName in it && "MainActivity" in it })
            assertEquals("null", shell("settings get secure enabled_accessibility_services"))
            assertFalse(ShortcutRuntime.keyboardConnection.connected)
        } finally {
            capture(name)
            dir.resolve("$name-activity.txt").writeText(shell("dumpsys activity activities"))
            dir.resolve("$name-lifecycle.json").writeText(org.json.JSONArray(lifecycleEvents.toList()).toString())
        }
        device.pressBack()
    }
    private fun holdHome(x: Int, y: Int, name: String) {
        val down = android.os.SystemClock.uptimeMillis()
        fun touch(action: Int) {
            val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        var frames = 0
        touch(android.view.MotionEvent.ACTION_DOWN)
        try {
            // A blocking UiObject2.longClick would freeze the target's Compose
            // clock while the assistant opens. Keep real frame cadence during
            // the actual held touch, just as an uninstrumented app renders.
            while (android.os.SystemClock.uptimeMillis() - down < 1200) {
                compose.mainClock.advanceTimeByFrame(); frames++
                android.os.SystemClock.sleep(16)
            }
        } finally { touch(android.view.MotionEvent.ACTION_UP) }
        dir.resolve("$name-touch.json").writeText(org.json.JSONObject().put("x", x).put("y", y)
            .put("held_ms", android.os.SystemClock.uptimeMillis() - down).put("source", "touchscreen")
            .put("down_injected", true).put("up_injected", true).put("render_frames", frames).toString())
    }
    @Test fun touchInstructionsRemainInlineAndDoNotEnableKeyboardAccess() {
        @Suppress("DEPRECATION") val requested = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertTrue(requested.intersect(setOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.SYSTEM_ALERT_WINDOW)).isEmpty())
        assertFalse(Settings.canDrawOverlays(context))
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithTag("settings-assistant-swipe").performScrollTo().assertIsDisplayed().assertTextContains("either bottom corner", substring = true)
        capture("swipe-instructions", "either bottom corner")
        compose.onNodeWithTag("settings-assistant-home").performScrollTo().assertIsDisplayed().assertTextContains("on-screen Home button", substring = true)
        compose.onNodeWithTag("settings-assistant-limitations").performScrollTo().assertTextContains("Circle to Search", substring = true)
        compose.onNodeWithTag("settings-assistant-navigation").performScrollTo().assertIsDisplayed()
        capture("home-instructions", "on-screen Home button")
        assertEquals(originalRoles.filterNot { it == context.packageName }, roles())
        assertEquals("null", shell("settings get secure enabled_accessibility_services"))
        assertFalse(ShortcutRuntime.keyboardConnection.connected)
    }
    @Test fun navigationLinkOpensTheActualAndroidNavigationPage() {
        compose.onNodeWithTag("settings-assistant-navigation").performScrollTo().performClick()
        for (label in listOf("Gesture navigation", "3-button navigation")) {
            assertTrue("Specific navigation destination: $label", device.wait(Until.hasObject(By.pkg("com.android.settings").text(label)), 7000))
        }
        assertTrue(shell("dumpsys window").lineSequence().any { "mCurrentFocus=" in it && "NavigationModeSettingsActivity" in it })
        capture("navigation-settings", "3-button navigation", "com.android.settings")
    }
    @Test fun unsupportedNavigationPageFallsBackToRootSettingsWithoutChangingRoles() {
        val actions = mutableListOf<String>()
        var result: AssistantActivation.NavigationLaunch? = null
        compose.activityRule.scenario.onActivity {
            result = AssistantActivation.openNavigationSettings { intent ->
                actions += intent.action!!
                assertNull(intent.data); assertNull(intent.extras)
                if (actions.size == 1) { assertEquals("com.android.settings", intent.`package`); throw ActivityNotFoundException("Controlled unavailable navigation page") }
                it.startActivity(intent)
            }
            it.navigationFeedback(result!!)
        }
        assertEquals(listOf("com.android.settings.NAVIGATION_MODE_SETTINGS", Settings.ACTION_SETTINGS), actions)
        assertEquals(AssistantActivation.NavigationLaunch.ANDROID_SETTINGS, result)
        assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings").text("Search Settings")), 7000))
        capture("root-settings-fallback", "Search Settings", "com.android.settings")
        assertEquals(originalRoles.filterNot { it == context.packageName }, roles())
    }
    @Test fun rejectedSettingsLaunchShowsReadableManualRecovery() {
        compose.onNodeWithTag("settings-assistant-navigation").performScrollTo()
        val attempted = mutableListOf<String>()
        compose.activityRule.scenario.onActivity {
            val result = AssistantActivation.openNavigationSettings { intent -> attempted += intent.action!!; throw SecurityException("Controlled OEM rejection") }
            assertEquals(AssistantActivation.NavigationLaunch.UNAVAILABLE, result)
            it.navigationFeedback(result)
        }
        assertEquals(2, attempted.size)
        compose.onNodeWithText("Navigation settings could not open. Open Android Settings → System → Gestures or Navigation mode manually.").assertIsDisplayed()
        capture("navigation-unavailable", "Navigation settings could not open.")
    }
    @Test fun cornerSwipesOpenFromHomeAndSettingsWithKeyboardServiceOff() {
        chooseDroidcast(); navigation(false)
        for (home in listOf(true, false)) for (left in listOf(true, false)) {
            destination(home)
            val w = device.displayWidth; val h = device.displayHeight
            val offset = (128 * context.resources.displayMetrics.density).toInt()
            val x = if (left) 3 else w - 3
            assertTrue(device.swipe(x, h - 3, if (left) x + offset else x - offset, h - 3 - offset, 70))
            val name = "corner-${if (left) "left" else "right"}-${if (home) "home" else "settings"}"
            dir.resolve("$name-touch.json").writeText(org.json.JSONObject().put("from_x", x).put("from_y", h - 3)
                .put("to_x", if (left) x + offset else x - offset).put("to_y", h - 3 - offset)
                .put("steps", 70).put("source", "UiDevice touchscreen swipe").toString())
            palette(name)
        }
    }
    @Test fun actualOnScreenHomeHoldOpensFromHomeAndSettingsWithKeyboardServiceOff() {
        chooseDroidcast(); navigation(true)
        for (home in listOf(true, false)) {
            destination(home)
            var button: UiObject2? = null
            fun findHome(): UiObject2? = device.findObjects(By.desc("Home")).firstOrNull {
                val b = it.visibleBounds
                it.resourceName in setOf("com.android.systemui:id/home", "com.google.android.apps.nexuslauncher:id/home") &&
                    b.width() in 1 until device.displayWidth / 3 && b.height() in 1 until device.displayHeight / 4
            }
            try {
                compose.waitUntil(10000) {
                    if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache()
                    button = findHome()
                    button != null
                }
            } finally {
                if (button == null) {
                    println("Home candidates: " + device.findObjects(By.desc("Home")).map { "${it.resourceName} ${it.visibleBounds}" })
                    capture("home-button-not-found")
                }
            }
            val before = "before-home-hold-${if (home) "home" else "settings"}"
            val resource = button!!.resourceName
            var exposed = false
            repeat(4) {
                if (!exposed) {
                    capture(before)
                    val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(dir.resolve("$before.xml"))
                    val nodes = doc.getElementsByTagName("node")
                    exposed = (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }.any {
                        it.getAttribute("resource-id") == resource && it.getAttribute("content-desc") == "Home"
                    }
                }
            }
            // Capturing invalidates Accessibility nodes; reacquire before touch.
            compose.waitUntil(10000) { button = findHome(); button != null }
            val b = button!!.visibleBounds
            dir.resolve("$before-control.json").writeText(org.json.JSONObject().put("resource", button!!.resourceName)
                .put("left", b.left).put("top", b.top).put("right", b.right).put("bottom", b.bottom)
                .put("home_exposed_in_xml", exposed).toString())
            println("Actual Home button ${button!!.resourceName} bounds=$b")
            holdHome(b.centerX(), b.centerY(), "home-hold-${if (home) "home" else "settings"}")
            palette("home-hold-${if (home) "home" else "settings"}")
        }
    }
}
