package dev.tinycast.android

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertFalse

/** A disappearing SystemUI window must never turn cleanup into a backdrop tap. */
internal fun dismissClipboardForEvidence(device: UiDevice) {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val deadline = SystemClock.uptimeMillis() + 5000
    while (device.hasObject(By.res("com.android.systemui", "clipboard_ui")) && SystemClock.uptimeMillis() < deadline) {
        automation.clearCache()
        automation.windows.asSequence().mapNotNull { it.root }
            .flatMap { it.findAccessibilityNodeInfosByViewId("com.android.systemui:id/dismiss_button").asSequence() }
            .firstOrNull { it.isVisibleToUser }?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        device.wait(Until.gone(By.res("com.android.systemui", "clipboard_ui")), 500)
    }
    automation.clearCache()
    assertFalse("Clipboard overlay must be dismissed", device.hasObject(By.res("com.android.systemui", "clipboard_ui")))
}
