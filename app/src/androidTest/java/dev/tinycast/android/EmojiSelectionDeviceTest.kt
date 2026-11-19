package dev.tinycast.android

import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EmojiSelectionDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val source = "Before chosen words after"
    private val glyph = "🫱🏽‍🫲🏻"

    private fun capture(name: String, pkg: String) {
        assertTrue(device.wait(Until.hasObject(By.pkg(pkg).depth(0)), 10000))
        device.waitForIdle()
        val folder = File(instrumentation.targetContext.filesDir, "emoji-evidence").apply { mkdirs() }
        testAutomation().clearCacheForTest()
        device.dumpWindowHierarchy(File(folder, "$name.xml"))
        assertTrue(device.takeScreenshot(File(folder, "$name.png")))
    }

    private fun launchCaller(text: String, start: Int, end: Int, readOnly: Boolean, toolbar: Boolean = false) {
        val owner = instrumentation.context
        owner.startActivity(Intent().setComponent(ComponentName(owner.packageName, ReviewedTextCallerActivity::class.java.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("emojiReplacement", true).putExtra("systemToolbar", toolbar).putExtra("source", text)
            .putExtra("selectionStart", start).putExtra("selectionEnd", end).putExtra("readOnly", readOnly))
        assertNotNull(device.wait(Until.findObject(By.res("android:id/edit").text(text)), 10000))
    }

    private fun start(readOnly: Boolean = false) {
        launchCaller(source, 7, 19, readOnly)
        val owner = instrumentation.context
        val button = device.wait(Until.findObject(By.text("Replace with emoji").pkg(owner.packageName)), 10000)
        assertNotNull(button)
        assertEquals(source, device.findObject(By.res("android:id/edit")).text)
        button.click()
        if (!device.wait(Until.hasObject(By.pkg(instrumentation.targetContext.packageName).depth(0)), 5000)) {
            assertTrue(device.hasObject(By.text("Emoji replacement is unavailable")))
            capture("unavailable", owner.packageName)
            fail("The installed app has no emoji replacement activity")
        }
    }

    private fun choose() {
        compose.onNode(hasSetTextAction()).performTextReplacement(glyph)
        compose.waitUntil(15000) { compose.onAllNodesWithText(glyph).fetchSemanticsNodes().any {
            it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        } }
        compose.onNode(hasText(glyph) and hasClickAction() and !hasSetTextAction()).performTouchInput { click(center) }
        compose.onNodeWithText("Replace selection").assertIsDisplayed()
    }

    private fun returned(text: String, status: String, phase: String) {
        assertTrue(device.wait(Until.hasObject(By.text("$status · Clipboard preserved")), 10000))
        assertEquals(text, device.findObject(By.res("android:id/edit")).text)
        capture(phase, instrumentation.context.packageName)
    }

    @Test fun replacesOnlyTheRequestedRangeWithTheExactMixedToneSequence() {
        start()
        choose()
        capture("confirm", instrumentation.targetContext.packageName)
        compose.onNodeWithText("Replace selection").performTouchInput { click(center) }
        returned("Before $glyph after", "Replacement returned", "replaced")
    }

    @Test fun cancellationPreservesTheSourceAfterChoosingAnEmoji() {
        start()
        choose()
        compose.onNodeWithText("Cancel").performTouchInput { click(center) }
        returned(source, "Canceled", "canceled")
    }

    @Test fun androidTextSelectionMenuReplacesTheWordInANativeEditor() {
        val owner = instrumentation.context
        launchCaller("sunshine", 0, 0, readOnly = false, toolbar = true)
        val editor = device.findObject(By.res("android:id/edit"))
        val bounds = editor.visibleBounds
        // Long press the first word in the stock EditText to open Android's toolbar.
        val x = bounds.left + (bounds.width() / 12).coerceIn(16, 32)
        val y = bounds.top + (bounds.height() / 4).coerceAtLeast(12)
        device.swipe(x, y, x, y, 130)
        val match = By.text(java.util.regex.Pattern.compile("Replace with emoji", java.util.regex.Pattern.CASE_INSENSITIVE))
        if (!device.wait(Until.hasObject(match), 1000)) {
            val more = device.wait(Until.findObject(By.desc("More options")), 3000)
            assertNotNull("Android text selection overflow is missing", more)
            more.click()
        }
        val target = device.wait(Until.findObject(match), 4000)
        capture("system-menu", owner.packageName)
        assertNotNull("Android's selection menu must offer the installed emoji handler", target)
        target.click()
        choose()
        compose.onNodeWithText("Replace selection").performTouchInput { click(center) }
        assertNotNull(device.wait(Until.findObject(By.res("android:id/edit").text(glyph)), 10000))
        capture("system-return", owner.packageName)
    }

    @Test fun readOnlySelectionReturnsUnchanged() {
        start(readOnly = true)
        compose.onNodeWithText("This selection is read-only.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Replace selection").assertDoesNotExist()
        capture("read-only", instrumentation.targetContext.packageName)
        compose.onNodeWithText("Return to editor").performTouchInput { click(center) }
        returned(source, "Canceled", "read-only-return")
    }
}
