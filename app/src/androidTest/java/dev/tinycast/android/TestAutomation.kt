package dev.tinycast.android

import android.app.UiAutomation
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator

/** Match UiDevice's connection mode instead of implicitly reconnecting with flags=0. */
internal fun testAutomation(): UiAutomation = InstrumentationRegistry.getInstrumentation()
    .getUiAutomation(Configurator.getInstance().uiAutomationFlags)

/** API28+ supports true clearing. Older test VMs use an explicit empty-text fixture. */
internal fun android.content.ClipboardManager.resetClipboardForTest() {
    if(android.os.Build.VERSION.SDK_INT>=28) clearPrimaryClip()
    else setPrimaryClip(android.content.ClipData.newPlainText("Droidcast test reset", ""))
}

/** Reset cached nodes on the modern path; legacy automation has no such requirement. */
internal fun UiAutomation.clearCacheForTest() {
    if(android.os.Build.VERSION.SDK_INT>=34) clearCache()
}
