package dev.tinycast.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import org.junit.Assert.assertTrue

/** A disappearing SystemUI window must never turn cleanup into a backdrop tap. */
internal fun dismissClipboardForEvidence(
    onObservation: (String) -> Unit = {},
    onNodeAction: (String, Boolean) -> Unit = { _, _ -> }
) {
    // Keep UiDevice's mode: changing it reconnects automation and loses flags.
    val mode=Configurator.getInstance().uiAutomationFlags
    val automation=InstrumentationRegistry.getInstrumentation().getUiAutomation(mode)
    val previousFlags=automation.serviceInfo.flags
    val started=SystemClock.uptimeMillis()
    val deadline=started+5000
    val snapshots=ArrayDeque<String>()
    try {
        val required=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        automation.serviceInfo=automation.serviceInfo.apply { flags=previousFlags or required }
        val effectiveFlags=automation.serviceInfo.flags
        var absentSince:Long?=null
        var lastAction:String?=null
        var lastActionAt=0L
        while(SystemClock.uptimeMillis()<deadline) {
            automation.clearCacheForTest()
            val sampleStarted=SystemClock.uptimeMillis()
            val windows=automation.windows
            // Query exact IDs independently in SystemUI's non-app/non-IME
            // windows. Fetching unrelated descendants can itself exhaust the
            // deadline on a busy emulator; never scan app or IME trees.
            val roots=windows.asSequence().filter {
                it.type!=AccessibilityWindowInfo.TYPE_APPLICATION && it.type!=AccessibilityWindowInfo.TYPE_INPUT_METHOD
            }.mapNotNull {
                if(Build.VERSION.SDK_INT>=33)it.getRoot(0) else it.root
            }.filter { it.packageName?.toString()=="com.android.systemui" }.toList()
            var queries=0
            var complete=true
            var overlay=false
            var target:AccessibilityNodeInfo?=null
            val found=mutableListOf<String>()
            for(id in listOf("com.android.systemui:id/dismiss_button","com.android.systemui:id/minimized_preview","com.android.systemui:id/clipboard_ui")) {
                for(root in roots) {
                    if(SystemClock.uptimeMillis()>=deadline){complete=false;break}
                    val nodes=if(root.viewIdResourceName==id)listOf(root) else root.findAccessibilityNodeInfosByViewId(id)
                    queries++
                    for(node in nodes) {
                        if(node.packageName?.toString()!="com.android.systemui" || node.viewIdResourceName!=id)continue
                        val eligible=node.isVisibleToUser && node.isEnabled && node.isClickable
                        found.add("$id visible=${node.isVisibleToUser} enabled=${node.isEnabled} clickable=${node.isClickable}")
                        if(node.isVisibleToUser)overlay=true
                        if(eligible && id!="com.android.systemui:id/clipboard_ui")target=node
                    }
                    if(target!=null)break
                }
                if(target!=null || !complete)break
            }
            // Sample time after all remote queries, so slow reads cannot count
            // as time during which an absent overlay was actually observed.
            val now=SystemClock.uptimeMillis()
            val observation="${now-started}ms query_ms=${now-sampleStarted} mode=$mode flags=$effectiveFlags windows=${windows.map { it.id to it.type }} systemui_roots=${roots.map { it.windowId }} queries=$queries complete=$complete nodes=$found"
            snapshots.addLast(observation);if(snapshots.size>16)snapshots.removeFirst()
            onObservation(observation)
            if(now>=deadline)break
            if(roots.isNotEmpty() && complete && !overlay) {
                if(absentSince==null)absentSince=now
                // The confirming observation belongs to this same state
                // machine: any reappearance resets the absence interval.
                if(now-started>=750 && now-absentSince!!>=250)return
            } else absentSince=null
            target?.let { node ->
                val id=node.viewIdResourceName.orEmpty()
                if(id!=lastAction || now-lastActionAt>=500) {
                    val success=node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    onNodeAction(id,success)
                    if(success){lastAction=id;lastActionAt=SystemClock.uptimeMillis()}
                }
            }
            SystemClock.sleep(minOf(50,(deadline-SystemClock.uptimeMillis()).coerceAtLeast(0)))
        }
        assertTrue("Interactive windows must establish settled clipboard dismissal: $snapshots",false)
    } finally {
        automation.serviceInfo=automation.serviceInfo.apply { flags=previousFlags }
    }
}
