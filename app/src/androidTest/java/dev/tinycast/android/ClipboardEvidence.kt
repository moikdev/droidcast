package dev.tinycast.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertFalse

/** A disappearing SystemUI window must never turn cleanup into a backdrop tap. */
internal fun dismissClipboardForEvidence(device: UiDevice, onNodeAction: (String, Boolean) -> Unit = { _, _ -> }) {
    // UiDevice uses Configurator's mode. The zero-argument getter changes that
    // mode and reconnects automation, discarding its window-reporting flags.
    val mode=Configurator.getInstance().uiAutomationFlags
    val automation=InstrumentationRegistry.getInstrumentation().getUiAutomation(mode)
    val previousFlags=automation.serviceInfo.flags
    try {
        automation.serviceInfo=automation.serviceInfo.apply {
            flags=previousFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        fun dismissNode(node:AccessibilityNodeInfo):AccessibilityNodeInfo? {
            if(node.packageName?.toString()=="com.android.systemui" && node.viewIdResourceName in setOf("com.android.systemui:id/dismiss_button", "com.android.systemui:id/minimized_preview") &&
                node.isVisibleToUser && node.isEnabled && node.isClickable) return node
            for(index in 0 until node.childCount) node.getChild(index)?.let { child -> dismissNode(child)?.let { return it } }
            return null
        }
        val deadline=SystemClock.uptimeMillis()+5000
        while(device.hasObject(By.res("com.android.systemui","clipboard_ui")) && SystemClock.uptimeMillis()<deadline) {
            automation.clearCacheForTest()
            automation.windows.asSequence().mapNotNull { it.root }.mapNotNull(::dismissNode).firstOrNull()
                ?.let { node ->
                    // With an IME, SystemUI may expose only its minimized preview.
                    // Its native click expands the same overlay; the next iteration
                    // can then click Dismiss. Never send a backdrop coordinate tap.
                    val id=node.viewIdResourceName.orEmpty()
                    onNodeAction(id,node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                }
            device.wait(Until.gone(By.res("com.android.systemui","clipboard_ui")),500)
        }
        automation.clearCacheForTest()
        // Verify while all interactive windows are still available. Restoring
        // reporting first could make an existing overlay disappear from queries.
        assertFalse("Clipboard overlay must be dismissed",device.hasObject(By.res("com.android.systemui","clipboard_ui")))
    } finally {
        automation.serviceInfo=automation.serviceInfo.apply { flags=previousFlags }
    }
}
