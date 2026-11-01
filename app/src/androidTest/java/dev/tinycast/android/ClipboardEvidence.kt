package dev.tinycast.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** A disappearing SystemUI window must never turn cleanup into a backdrop tap. */
internal fun dismissClipboardForEvidence(onNodeAction: (String, Boolean) -> Unit = { _, _ -> }) {
    // UiDevice uses Configurator's mode. The zero-argument getter changes that
    // mode and reconnects automation, discarding its window-reporting flags.
    val mode=Configurator.getInstance().uiAutomationFlags
    val automation=InstrumentationRegistry.getInstrumentation().getUiAutomation(mode)
    val previousFlags=automation.serviceInfo.flags
    try {
        automation.serviceInfo=automation.serviceInfo.apply {
            flags=previousFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        fun dismissNode(node:AccessibilityNodeInfo,id:String):AccessibilityNodeInfo? {
            if(node.packageName?.toString()=="com.android.systemui" && node.viewIdResourceName==id &&
                node.isVisibleToUser && node.isEnabled && node.isClickable) return node
            for(index in 0 until node.childCount) node.getChild(index)?.let { child -> dismissNode(child,id)?.let { return it } }
            return null
        }
        fun overlayPresent(node:AccessibilityNodeInfo):Boolean {
            if(node.packageName?.toString()=="com.android.systemui" && node.viewIdResourceName=="com.android.systemui:id/clipboard_ui") return true
            for(index in 0 until node.childCount) node.getChild(index)?.let { if(overlayPresent(it))return true }
            return false
        }
        fun roots()=automation.windows.asSequence()
            .filter { it.type != AccessibilityWindowInfo.TYPE_APPLICATION &&
                it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            .mapNotNull { it.root }.toList()
        val started=SystemClock.uptimeMillis()
        val deadline=started+5000
        var absentSince:Long?=null
        var settled=false
        var lastAction:String?=null
        var lastActionAt=0L
        while(SystemClock.uptimeMillis()<deadline) {
            automation.clearCacheForTest()
            val windows=roots()
            val now=SystemClock.uptimeMillis()
            if(windows.isNotEmpty() && windows.none(::overlayPresent)) {
                if(absentSince==null)absentSince=now
                if(now-started>=750 && now-absentSince!!>=250){settled=true;break}
            } else absentSince=null
            val target=listOf("com.android.systemui:id/dismiss_button","com.android.systemui:id/minimized_preview")
                .firstNotNullOfOrNull { id -> windows.asSequence().mapNotNull { dismissNode(it,id) }.firstOrNull() }
            target?.let { node ->
                val id=node.viewIdResourceName.orEmpty()
                if(id!=lastAction || now-lastActionAt>=500) {
                    val success=node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    onNodeAction(id,success)
                    if(success){lastAction=id;lastActionAt=now}
                }
            }
            SystemClock.sleep(50)
        }
        automation.clearCacheForTest()
        val finalWindows=roots()
        assertTrue("Interactive windows must establish settled clipboard dismissal",settled && finalWindows.isNotEmpty())
        assertFalse("Clipboard overlay must be dismissed",finalWindows.any(::overlayPresent))
    } finally {
        automation.serviceInfo=automation.serviceInfo.apply { flags=previousFlags }
    }
}
