package dev.tinycast.android

import android.content.Intent
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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaletteOverlayTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val device get()=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    @Test fun compactTranslucentPaletteDismissesOutsideToPreviousApp(){
        compose.activityRule.scenario.onActivity{it.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))}
        assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings")),5000))
        device.executeShellCommand("am start -n dev.tinycast.android/.MainActivity")
        compose.waitForIdle()
        compose.onNodeWithText("Command center").assertDoesNotExist()
        val bounds=compose.onNodeWithTag("palette").fetchSemanticsNode().boundsInWindow
        if (compose.activity.resources.configuration.screenWidthDp >= 700) {
            assertTrue("Tablet palette stays compact", bounds.width < device.displayWidth * .8f)
        } else {
            assertTrue("Phone palette retains clear side margins", bounds.left > 0 && bounds.right < device.displayWidth)
            assertTrue("Phone palette uses the available width", bounds.width > device.displayWidth * .8f)
        }
        compose.activityRule.scenario.onActivity{
            val style=it.obtainStyledAttributes(intArrayOf(android.R.attr.windowIsTranslucent))
            assertTrue(style.getBoolean(0,false));style.recycle()
        }
        device.waitForIdle()
        val evidence = java.io.File(compose.activity.filesDir, "fullscreen-settings-evidence").apply { mkdirs() }
        assertTrue(device.takeScreenshot(java.io.File(evidence, "floating-palette.png")))
        device.click((bounds.left-24).toInt().coerceAtLeast(1),bounds.center.y.toInt())
        compose.waitUntil(5000){compose.activityRule.scenario.state==Lifecycle.State.DESTROYED}
        assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings")),5000))
    }
    @Test fun escapeDismissesEmptyPalette(){
        compose.activityRule.scenario.onActivity{it.store.reset()};compose.waitForIdle()
        device.pressKeyCode(KeyEvent.KEYCODE_ESCAPE)
        compose.waitUntil(5000){compose.activityRule.scenario.state==Lifecycle.State.DESTROYED}
    }
    @Test fun quickSettingsTileSummonsPalette() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val probe = TileActivationProbe(context, device)
        compose.activityRule.scenario.onActivity {
            it.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
        assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings")), 5000))
        val installed = probe.tileInstalled()
        try {
            if (!installed) {
                device.executeShellCommand("cmd statusbar add-tile ${probe.component}")
                probe.awaitMembership(true)
            }
            probe.snapshot("settings")
            assertTrue("Could not open Quick Settings", device.openQuickSettings())
            // openQuickSettings() returns before SystemUI finishes opening. Injecting
            // click-tile during that transition can leave the shade above the resumed palette.
            assertTrue("Quick Settings panel did not appear", device.wait(Until.hasObject(
                By.res(java.util.regex.Pattern.compile("com.android.systemui:id/(qs_pager|qs_panel|quick_settings_panel|expanded_qs_scroll_view)"))
            ), 5000))
            device.waitForIdle()
            probe.snapshot("shade")
            probe.mark("before_${probe.mode}")
            when (probe.mode) {
                "command" -> device.executeShellCommand("cmd statusbar click-tile ${probe.component}")
                "pointer" -> requireNotNull(device.wait(Until.findObject(By.pkg("com.android.systemui")
                    .text("Droidcast search").clickable(true).enabled(true)), 5000)) { "Droidcast tile is not on this page" }.click()
            }
            probe.mark("after_${probe.mode}")
            assertTrue("Tile did not open the palette search editor",
                device.wait(Until.hasObject(By.pkg(context.packageName).clazz("android.widget.EditText")), 10000))
            assertEquals("palette", probe.focusedWindow())
            compose.onNodeWithTag("search").assertIsDisplayed()
            val palette = compose.onNodeWithTag("palette").assertIsDisplayed()
            probe.snapshot("palette")
            if (probe.capturing) {
                val bounds = palette.fetchSemanticsNode().boundsInWindow
                assertTrue(bounds.left > 1f)
                device.click((bounds.left / 2).toInt(), bounds.center.y.toInt())
                assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings")), 5000))
                assertEquals("android-settings", probe.focusedWindow())
                probe.snapshot("returned")
            }
        } catch (failure: Throwable) {
            runCatching { probe.snapshot("failure", failure = true) }.onFailure(failure::addSuppressed)
            throw failure
        } finally {
            try {
                device.executeShellCommand("cmd statusbar collapse")
            } finally {
                if (!installed) {
                    device.executeShellCommand("cmd statusbar remove-tile ${probe.component}")
                    probe.awaitMembership(false)
                }
            }
        }
    }
    @Test fun typingAndSelectionUpdateWithoutAnimatedScrollOrMovingSearchFrame() {
        compose.waitUntil(10000) { !compose.activity.store.loading && !compose.activity.store.folderSearch.loading }
        compose.activityRule.scenario.onActivity { it.store.filter("All") }
        compose.activityRule.scenario.onActivity {
            androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        compose.waitForIdle(); device.waitForIdle()
        val palette = compose.onNodeWithTag("palette").fetchSemanticsNode().boundsInWindow
        val search = compose.onNodeWithTag("search").fetchSemanticsNode().boundsInWindow
        compose.mainClock.autoAdvance = false
        try {
            compose.activityRule.scenario.onActivity { it.store.selected = minOf(15, it.store.results().lastIndex) }
            repeat(2) { compose.mainClock.advanceTimeByFrame() }; compose.waitForIdle()
            val selected = compose.activity.store.results()[compose.activity.store.selected]
            compose.onNodeWithTag("result-${selected.id}").assertIsDisplayed().assertIsSelected()
            for (query in listOf("Wi", "Wi-Fi", "calc", "no-such-result-938172", "", "Settings")) {
                compose.activityRule.scenario.onActivity { it.store.search(query) }
                // Two layout frames, not an animation-duration advance.
                repeat(2) { compose.mainClock.advanceTimeByFrame() }; compose.waitForIdle()
                assertEquals(palette, compose.onNodeWithTag("palette").fetchSemanticsNode().boundsInWindow)
                assertEquals(search, compose.onNodeWithTag("search").fetchSemanticsNode().boundsInWindow)
                compose.activity.store.results().firstOrNull()?.let {
                    compose.onNodeWithTag("result-${it.id}").assertIsDisplayed().assertIsSelected()
                }
            }
            val dir = java.io.File(compose.activity.filesDir, "assistant-evidence").apply { mkdirs() }
            device.waitForIdle()
            assertTrue(device.takeScreenshot(java.io.File(dir, "snappy-search.png")))
        } finally { compose.mainClock.autoAdvance = true }
    }

}
