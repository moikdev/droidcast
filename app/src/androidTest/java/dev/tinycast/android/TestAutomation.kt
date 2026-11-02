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

/** Guarded host runs restore the original clipboard independently after the
 * entire cohort. Do not re-expose it between legacy cases or in screenshots. */
internal fun hostRestoresTestClipboard(): Boolean {
    val args = InstrumentationRegistry.getArguments()
    if (args.getString("reviewedTextFixture") != "true") return false
    val owner = requireNotNull(args.getString("expectedTextInputLeaseOwner"))
    require(owner.matches(Regex("[0-9a-f]{32}")))
    require(args.getString("expectedClipboardRecoveryOwner") == owner)
    require(args.getString("dedicatedAvd") == "Tinycast_Tablet_API_36")
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val lease = org.json.JSONObject(java.io.File(context.filesDir, "reviewed-text-fixture-lease/owners.json").readText())
    require(lease.getInt("schema_version") == 1 && lease.getString("fixture_kind") == "reviewed-text-device-test-v1")
    require(lease.getString("owner_nonce") == owner)
    return true
}
