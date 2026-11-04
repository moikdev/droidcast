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
    requireAction: Boolean = false,
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
        var performedActions=0
        val prefetch=if(Build.VERSION.SDK_INT>=33) AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID or
            AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE else 0
        while(SystemClock.uptimeMillis()<deadline) {
            automation.clearCacheForTest()
            val sampleStarted=SystemClock.uptimeMillis()
            val windows=automation.windows.filter {
                it.type!=AccessibilityWindowInfo.TYPE_APPLICATION && it.type!=AccessibilityWindowInfo.TYPE_INPUT_METHOD
            }.sortedByDescending { it.layer }
            // One bounded prefetched walk avoids three independent remote ID
            // searches per root. Never traverse application/IME or foreign roots.
            val roots=mutableListOf<Int>()
            var rootRequests=0
            var childRequests=0
            var walked=0
            var complete=true
            var overlay=false
            var target:AccessibilityNodeInfo?=null
            val found=mutableListOf<String>()
            val ids=setOf("com.android.systemui:id/dismiss_button","com.android.systemui:id/minimized_preview","com.android.systemui:id/clipboard_ui")
            for((windowIndex,window) in windows.withIndex()) {
                if(SystemClock.uptimeMillis()>=deadline || rootRequests>=16 || walked>=256){complete=false;break}
                rootRequests++
                val root=if(Build.VERSION.SDK_INT>=33)window.getRoot(prefetch) else window.root
                if(root==null){complete=false;continue}
                if(root.packageName?.toString()!="com.android.systemui")continue
                roots.add(root.windowId)
                val pending=ArrayDeque<AccessibilityNodeInfo>();pending.add(root)
                var minimized:AccessibilityNodeInfo?=null
                while(pending.isNotEmpty()) {
                    if(SystemClock.uptimeMillis()>=deadline || walked>=256){complete=false;break}
                    val node=pending.removeFirst();walked++
                    if(node.packageName?.toString()!="com.android.systemui")continue
                    val id=node.viewIdResourceName
                    if(id!=null && id in ids) {
                        val eligible=node.isVisibleToUser && node.isEnabled && node.isClickable
                        found.add("$id visible=${node.isVisibleToUser} enabled=${node.isEnabled} clickable=${node.isClickable}")
                        if(node.isVisibleToUser)overlay=true
                        if(eligible && id=="com.android.systemui:id/dismiss_button"){target=node;break}
                        if(eligible && id=="com.android.systemui:id/minimized_preview")minimized=node
                    }
                    for(index in 0 until node.childCount) {
                        if(SystemClock.uptimeMillis()>=deadline || walked+pending.size>=256){complete=false;break}
                        childRequests++
                        val child=if(Build.VERSION.SDK_INT>=33)node.getChild(index,prefetch) else node.getChild(index)
                        if(child==null)complete=false else pending.add(child)
                    }
                }
                if(pending.isNotEmpty())complete=false
                if(target!=null || minimized!=null) {
                    if(target==null)target=minimized
                    if(windowIndex<windows.lastIndex)complete=false
                    break
                }
            }
            // Sample time after all remote queries, so slow reads cannot count
            // as time during which an absent overlay was actually observed.
            val now=SystemClock.uptimeMillis()
            val observation="${now-started}ms query_ms=${now-sampleStarted} mode=$mode flags=$effectiveFlags windows=${windows.map { it.id to it.type }} systemui_roots=$roots queries=${rootRequests+childRequests} roots=$rootRequests children=$childRequests walked=$walked prefetch=$prefetch complete=$complete require_action=$requireAction actions=$performedActions nodes=$found"
            snapshots.addLast(observation);if(snapshots.size>16)snapshots.removeFirst()
            onObservation(observation)
            if(now>=deadline)break
            if(roots.isNotEmpty() && complete && !overlay) {
                if(absentSince==null)absentSince=now
                // The confirming observation belongs to this same state
                // machine: any reappearance resets the absence interval.
                if(now-started>=750 && now-absentSince!!>=250 && (!requireAction || performedActions>0))return
            } else absentSince=null
            target?.let { node ->
                val id=node.viewIdResourceName.orEmpty()
                if(id!=lastAction || now-lastActionAt>=500) {
                    val success=node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    onNodeAction(id,success)
                    if(success){lastAction=id;lastActionAt=SystemClock.uptimeMillis();performedActions++}
                }
            }
            SystemClock.sleep(minOf(50,(deadline-SystemClock.uptimeMillis()).coerceAtLeast(0)))
        }
        assertTrue("Interactive windows must establish settled clipboard dismissal: $snapshots",false)
    } finally {
        automation.serviceInfo=automation.serviceInfo.apply { flags=previousFlags }
    }
}
