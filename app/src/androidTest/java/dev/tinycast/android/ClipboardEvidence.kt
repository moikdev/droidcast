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
        fun nodes(roots:List<AccessibilityNodeInfo>,id:String)=roots.flatMap { it.findAccessibilityNodeInfosByViewId(id) }
            .filter { it.packageName?.toString()=="com.android.systemui" }
        fun overlayPresent(roots:List<AccessibilityNodeInfo>)=nodes(roots,"com.android.systemui:id/clipboard_ui").any { it.isVisibleToUser }
        val started=SystemClock.uptimeMillis()
        val snapshots=mutableListOf<String>()
        fun roots():List<AccessibilityNodeInfo> {
            val windows=automation.windows
            val roots=windows.asSequence()
                .filter { it.type != AccessibilityWindowInfo.TYPE_APPLICATION &&
                    it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                .mapNotNull { it.root }.toList()
            snapshots.add("${SystemClock.uptimeMillis()-started}ms windows=${windows.map { "${it.type}:${it.title}" }} roots=${roots.size}")
            if(snapshots.size>16)snapshots.removeAt(0)
            return roots
        }
        val deadline=started+5000
        var absentSince:Long?=null
        var settled=false
        var lastAction:String?=null
        var lastActionAt=0L
        while(SystemClock.uptimeMillis()<deadline) {
            automation.clearCacheForTest()
            val windows=roots()
            val now=SystemClock.uptimeMillis()
            val overlay=overlayPresent(windows)
            if(windows.isNotEmpty() && !overlay) {
                if(absentSince==null)absentSince=now
                if(now-started>=750 && now-absentSince!!>=250){settled=true;break}
            } else absentSince=null
            val target=if(overlay)listOf("com.android.systemui:id/dismiss_button","com.android.systemui:id/minimized_preview")
                .firstNotNullOfOrNull { id -> nodes(windows,id).firstOrNull { it.isVisibleToUser && it.isEnabled && it.isClickable } }
                else null
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
        assertTrue("Interactive windows must establish settled clipboard dismissal: $snapshots",settled && finalWindows.isNotEmpty())
        assertFalse("Clipboard overlay must be dismissed",overlayPresent(finalWindows))
    } finally {
        automation.serviceInfo=automation.serviceInfo.apply { flags=previousFlags }
    }
}
