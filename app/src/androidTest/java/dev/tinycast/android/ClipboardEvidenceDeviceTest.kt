package dev.tinycast.android

import android.app.UiAutomation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Exercise the same automation mode FolderDeviceTest selects, without granting consent. */
@RunWith(AndroidJUnit4::class)
class ClipboardEvidenceDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val device get()=UiDevice.getInstance(instrumentation)
    private var originalFlags=0
    private var original=emptyMap<String,Any?>()
    private var clipboard:ClipData?=null
    @Before fun preserve() {
        originalFlags=Configurator.getInstance().uiAutomationFlags
        original=context.getSharedPreferences("palette",Context.MODE_PRIVATE).all.toMap()
        compose.waitUntil(15_000) { !compose.activity.store.loading }
        compose.activityRule.scenario.onActivity {
            clipboard=it.getSystemService(ClipboardManager::class.java).primaryClip
            it.store.resetShortcuts();it.store.reset();it.store.search("clipboard controlled fixture")
        }
    }
    @After fun restore() {
        try {
            compose.activityRule.scenario.onActivity {
                val manager=it.getSystemService(ClipboardManager::class.java)
                clipboard?.let(manager::setPrimaryClip) ?: manager.clearPrimaryClip()
            }
            restorePalettePreferences(context,original)
        } finally { Configurator.getInstance().uiAutomationFlags=originalFlags }
    }
    private fun exercise(mode:Int,label:String) {
        Configurator.getInstance().uiAutomationFlags=mode
        val automation=instrumentation.getUiAutomation(mode)
        var epoch=0
        compose.activityRule.scenario.onActivity { epoch=it.store.focusEpoch;it.copyText("Droidcast controlled clipboard fixture 271828") }
        assertTrue("Clipboard fixture really opens Android's overlay",device.wait(Until.hasObject(By.res("com.android.systemui","clipboard_ui")),7000))
        val beforeServiceFlags=automation.serviceInfo.flags
        val folder=File(context.filesDir,"readiness-evidence").apply { mkdirs() }
        var failure:Throwable?=null
        try {
            dismissClipboardForEvidence(device)
            assertEquals(mode,Configurator.getInstance().uiAutomationFlags)
            assertNull(compose.activity.store.dialog);assertEquals(epoch,compose.activity.store.focusEpoch)
        } catch(error:Throwable) { failure=error }
        val after=instrumentation.getUiAutomation(mode)
        val afterServiceFlags=after.serviceInfo.flags
        File(folder,"clipboard-$label.json").writeText(JSONObject()
            .put("configured_mode",mode).put("service_flags_before",beforeServiceFlags)
            .put("service_flags_after",afterServiceFlags)
            .put("overlay_after",device.hasObject(By.res("com.android.systemui","clipboard_ui")))
            .put("route_preserved",compose.activity.store.dialog==null && compose.activity.store.focusEpoch==epoch)
            .put("failure",failure?.message ?: JSONObject.NULL).toString(2))
        device.dumpWindowHierarchy(File(folder,"clipboard-$label.xml"))
        assertTrue(device.takeScreenshot(File(folder,"clipboard-$label.png")))
        failure?.let { throw it }
        assertEquals("Cleanup restores accessibility service flags",beforeServiceFlags,afterServiceFlags)
    }
    @Test fun defaultModeClipboardCleanupRetainsThePalette() { exercise(0,"default") }
    @Test fun nonSuppressingModeClipboardCleanupRetainsThePalette() {
        exercise(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,"nonsuppressing")
    }
    @Test fun modeChangesDoNotStrandTheClipboardOverlay() {
        exercise(0,"cycle-default-before")
        exercise(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,"cycle-nonsuppressing")
        exercise(0,"cycle-default-after")
    }
}
