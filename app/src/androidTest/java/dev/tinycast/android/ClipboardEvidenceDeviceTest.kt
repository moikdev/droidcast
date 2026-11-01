package dev.tinycast.android

import android.app.UiAutomation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.*
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
@androidx.test.filters.SdkSuppress(minSdkVersion=33)
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
                clipboard?.let(manager::setPrimaryClip) ?: manager.resetClipboardForTest()
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
            dismissClipboardForEvidence()
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
    @Test fun minimizedClipboardExpandsAndDismissesWithoutChangingTheCaller() {
        val priorIme=device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
        assertTrue(priorIme in setOf("null","0","1"))
        try {
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
        Configurator.getInstance().uiAutomationFlags=0
        compose.onNodeWithTag("search").performTextReplacement("clipboard controlled fixture")
        if(compose.activity.resources.configuration.smallestScreenWidthDp>=600)
            compose.onNodeWithContentDescription("Show on-screen keyboard").performClick()
        else compose.onNodeWithTag("search").performClick()
        compose.waitUntil(7000) {
            androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true
        }
        val epoch=compose.activity.store.focusEpoch
        // Reproduce the missing-Dismiss shape: SystemUI creates its overlay
        // while accessibility is off, then automation reconnects after entry.
        // No application keyboard service or consent is enabled by this fixture.
        Configurator.getInstance().uiAutomationFlags=UiAutomation.FLAG_DONT_USE_ACCESSIBILITY
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY)
        val deadline=android.os.SystemClock.uptimeMillis()+5000
        var accessibilityOn=true
        while(accessibilityOn && android.os.SystemClock.uptimeMillis()<deadline) {
            instrumentation.runOnMainSync { accessibilityOn=compose.activity.getSystemService(android.view.accessibility.AccessibilityManager::class.java).isEnabled }
            if(accessibilityOn)android.os.SystemClock.sleep(20)
        }
        assertFalse("Fixture disables automation accessibility before copy",accessibilityOn)
        instrumentation.runOnMainSync { compose.activity.copyText("Droidcast minimized clipboard fixture 161803") }
        // Let the native overlay's entry run before reconnecting. The exact
        // minimized/no-Dismiss hierarchy below is the required fixture gate.
        android.os.SystemClock.sleep(350)
        Configurator.getInstance().uiAutomationFlags=0
        instrumentation.getUiAutomation(0)
        assertTrue("Exercise the actual minimized SystemUI shape",device.wait(Until.hasObject(By.res("com.android.systemui","minimized_preview")),7000))
        assertFalse("Fixture has no expanded Dismiss target",device.hasObject(By.res("com.android.systemui","dismiss_button")))
        val directory=File(context.filesDir,"readiness-evidence").apply { mkdirs() }
        device.dumpWindowHierarchy(File(directory,"clipboard-minimized-before.xml"))
        assertTrue(device.takeScreenshot(File(directory,"clipboard-minimized-before.png")))
        val actions=org.json.JSONArray()
        try {
            dismissClipboardForEvidence { id,success -> actions.put(JSONObject().put("node",id).put("success",success)) }
        } finally {
            File(directory,"clipboard-minimized-actions.json").writeText(JSONObject().put("actions",actions).toString(2))
        }
        val performed=(0 until actions.length()).map { actions.getJSONObject(it) }
        assertTrue("Must really expand the minimized node, not await expiry",performed.any { it.getString("node").endsWith(":id/minimized_preview") && it.getBoolean("success") })
        assertTrue("Must really dismiss the expanded node",performed.any { it.getString("node").endsWith(":id/dismiss_button") && it.getBoolean("success") })
        assertEquals("clipboard controlled fixture",compose.activity.store.query)
        assertEquals(epoch,compose.activity.store.focusEpoch);assertNull(compose.activity.store.dialog)
        compose.onNodeWithTag("search").assertIsFocused().assertTextEquals("clipboard controlled fixture")
        testAutomation().clearCacheForTest();device.dumpWindowHierarchy(File(directory,"clipboard-minimized-after.xml"))
        assertTrue(device.takeScreenshot(File(directory,"clipboard-minimized-after.png")))
        } finally {
            device.executeShellCommand(if(priorIme=="null") "settings delete secure show_ime_with_hard_keyboard"
                else "settings put secure show_ime_with_hard_keyboard $priorIme")
        }
    }

}
