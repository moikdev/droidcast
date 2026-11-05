package dev.tinycast.android

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.tinycast.android.extensions.*
import java.io.File
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** The host owns this disposable API26 fixture; it has no clipboard/network actions. */
@RunWith(AndroidJUnit4::class)
class WebViewCompatibilityDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private var prepared = false
    private var palette = emptyMap<String, Any?>()
    private lateinit var extension: InstalledExtension
    private val record = JSONObject().put("scope", "Owned parser/engine boundary and native recovery; no Store, service, clipboard or Android8 execution-parity credit")
    private val evidence get() = File(context.filesDir, "webview-compatibility-evidence")

    @Before fun prepare() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Use the owned WebView compatibility runner", args.getString("webViewFixture") == "true")
        assertEquals(26, Build.VERSION.SDK_INT)
        val nonce = requireNotNull(args.getString("webViewOwner"))
        assertTrue(nonce.matches(Regex("[0-9a-f]{32}")))
        val owner = JSONObject(File(context.filesDir, "webview-compatibility-lease/owner.json").readText())
        assertEquals("webview-compatibility-v1", owner.getString("kind"))
        assertEquals(nonce, owner.getString("nonce"))
        assertTrue(owner.getBoolean("fresh_disposable_avd"))
        val id = owner.getString("extension_id")
        assertEquals("webview-engine-$nonce", id)
        compose.waitUntil(15000) { !compose.activity.store.loading }
        extension = compose.activity.store.extensions.installed.single { it.listing.id == id }
        assertEquals("Owned engine fixture", extension.listing.title)
        palette = context.getSharedPreferences("palette", Context.MODE_PRIVATE).all.toMap()
        prepared = true
        val provider = WebView.getCurrentWebViewPackage()
        record.put("android_api", Build.VERSION.SDK_INT).put("provider", provider?.packageName)
            .put("provider_version", provider?.versionName).put("font_scale", context.resources.configuration.fontScale)
            .put("fixture_bytecode_scope", "ES5 two-row List; no actions, preferences, storage, clipboard or networking")
    }

    private fun capture(phase: String) {
        compose.waitForIdle()
        evidence.mkdirs()
        testAutomation().clearCacheForTest()
        device.dumpWindowHierarchy(File(evidence, "$phase.xml"))
        assertTrue(device.takeScreenshot(File(evidence, "$phase.png")))
        File(evidence, "$phase-frame.json").writeText(JSONObject().put("phase", phase)
            .put("synchronization", "Compose idle; accessibility cache reset where supported; hierarchy before PNG")
            .put("api", Build.VERSION.SDK_INT).put("scope", record.getString("scope")).toString(2))
    }

    @Test fun incompatibleEngineShowsNativeRecoveryWithoutDispatch() {
        val caller = "owned-engine-caller-日本"
        compose.activityRule.scenario.onActivity { it.store.reset(); it.store.search(caller) }
        val start = SystemClock.uptimeMillis()
        compose.activityRule.scenario.onActivity { it.store.openExtension(extension, extension.commands.single()) }
        compose.waitUntil(25000) { compose.activity.store.extensions.runtimeError != null ||
            compose.activity.store.extensions.runtimeTree != null }
        val catalog = compose.activity.store.extensions
        record.put("time_to_result_ms", SystemClock.uptimeMillis() - start).put("runtime_error", catalog.runtimeError)
            .put("published_native_tree", catalog.runtimeTree != null)
        assertNotNull("The actual legacy engine must report its boundary", catalog.runtimeError)
        assertNull("An incompatible engine must not publish the owned command", catalog.runtimeTree)
        capture("engine-result")
        // Common-harness old-app control reaches the captured late error, then
        // fails here. No new application type/method is referenced before it.
        compose.onNodeWithTag("extension-engine-problem").assertExists()
        compose.onNodeWithTag("extension-engine-provider").assertTextContains(record.getString("provider"), substring = true)
        compose.onNodeWithTag("extension-engine-provider").assertTextContains(record.getString("provider_version"), substring = true)
        compose.onNodeWithTag("extension-engine-return").performTouchInput { click(center) }
        compose.waitUntil(10000) { compose.activity.store.dialog == null }
        assertEquals(caller, compose.activity.store.query)
        record.put("caller_query_restored", true).put("passed", true)
        capture("returned-caller")
    }

    @After fun restore() {
        if (!prepared) return
        try {
            if (!record.optBoolean("passed")) capture("failure")
        } finally {
            compose.activityRule.scenario.onActivity { it.store.extensions.close(); it.store.dialog = null }
            compose.waitForIdle()
            restorePalettePreferences(context, palette)
            assertEquals(palette, context.getSharedPreferences("palette", Context.MODE_PRIVATE).all)
            record.put("palette_preferences_restored", true).put("fixture_cleanup_owner", "Host retains ownership until evidence is pulled and registered paths are removed")
            evidence.mkdirs()
            File(evidence, "case-record.json").writeText(record.toString(2))
        }
    }
}
