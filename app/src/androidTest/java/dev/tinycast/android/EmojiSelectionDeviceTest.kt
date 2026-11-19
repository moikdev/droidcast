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

    private fun start(readOnly: Boolean = false) {
        val owner = instrumentation.context
        owner.startActivity(Intent().setComponent(ComponentName(owner.packageName, ReviewedTextCallerActivity::class.java.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("emojiReplacement", true).putExtra("source", source)
            .putExtra("selectionStart", 7).putExtra("selectionEnd", 19).putExtra("readOnly", readOnly))
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

    @Test fun readOnlySelectionReturnsUnchanged() {
        start(readOnly = true)
        compose.onNodeWithText("This selection is read-only.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Replace selection").assertDoesNotExist()
        capture("read-only", instrumentation.targetContext.packageName)
        compose.onNodeWithText("Return to editor").performTouchInput { click(center) }
        returned(source, "Canceled", "read-only-return")
    }
}
