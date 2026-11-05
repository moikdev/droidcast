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
class WebViewRecoveryDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val testName = org.junit.rules.TestName()
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
        assertTrue(Build.VERSION.SDK_INT in listOf(26, 36))
        val nonce = requireNotNull(args.getString("webViewOwner"))
        assertTrue(nonce.matches(Regex("[0-9a-f]{32}")))
        val owner = JSONObject(File(context.filesDir, "webview-compatibility-lease/owner.json").readText())
        assertEquals("webview-compatibility-v1", owner.getString("kind"))
        assertEquals(nonce, owner.getString("nonce"))
        assertEquals(Build.VERSION.SDK_INT == 26, owner.getBoolean("fresh_disposable_avd"))
        val id = owner.getString("extension_id")
        assertEquals("webview-engine-$nonce", id)
        compose.waitUntil(15000) { !compose.activity.store.loading }
        extension = compose.activity.store.extensions.installed.single { it.listing.id == id }
        assertEquals("Owned engine fixture", extension.listing.title)
        palette = context.getSharedPreferences("palette", Context.MODE_PRIVATE).all.toMap()
        prepared = true
        val provider = WebView.getCurrentWebViewPackage()
        record.put("case", testName.methodName).put("android_api", Build.VERSION.SDK_INT).put("provider", provider?.packageName)
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
        assumeTrue(Build.VERSION.SDK_INT == 26)
        val caller = "owned-engine-caller-日本"
        compose.activityRule.scenario.onActivity { it.store.reset(); it.store.search(caller) }
        val start = SystemClock.uptimeMillis()
        compose.activityRule.scenario.onActivity { it.store.openExtension(extension, extension.commands.single { it.name == "index" }) }
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

    @Test fun incompatibleEngineOpensOnlyActualProviderSettingsAndReturns() {
        assumeTrue(Build.VERSION.SDK_INT == 26)
        val caller = "engine-settings-caller-日本"
        compose.activityRule.scenario.onActivity { it.store.reset(); it.store.search(caller); it.store.openExtension(extension, extension.commands.single { c -> c.name == "index" }) }
        compose.waitUntil(15000) { compose.activity.store.extensions.runtimeError != null }
        compose.onNodeWithTag("extension-engine-problem").assertExists()
        capture("settings-before")
        compose.onNodeWithTag("extension-engine-settings").performTouchInput { click(center) }
        assertTrue("Explicit touch opens OS settings", device.wait(androidx.test.uiautomator.Until.hasObject(
            androidx.test.uiautomator.By.pkg("com.android.settings")), 10000))
        assertEquals("com.android.settings", device.currentPackageName)
        record.put("explicit_settings_destination", device.currentPackageName)
        capture("settings-destination")
        device.pressBack()
        compose.waitUntil(10000) { compose.activity.hasWindowFocus() }
        compose.onNodeWithTag("extension-engine-return").performTouchInput { click(center) }
        compose.waitUntil(10000) { compose.activity.store.dialog == null }
        assertEquals(caller, compose.activity.store.query)
        record.put("caller_query_restored", true).put("passed", true)
        capture("settings-returned")
    }

    @Test fun actualProviderParsesProbeWithoutRunningItsBodyAndRejectsSyntax() {
        var engine: WebView? = null
        fun evaluate(script: String): String? {
            var completed = false
            var result: String? = null
            instrumentation.runOnMainSync { engine!!.evaluateJavascript(script) { result = it; completed = true } }
            compose.waitUntil(10000) { completed }
            return result
        }
        try {
            instrumentation.runOnMainSync {
                engine = WebView(context).apply { settings.javaScriptEnabled = true; loadData("<html></html>", "text/html", "UTF-8") }
            }
            compose.waitUntil(10000) { engine?.progress == 100 }
            val stage = ExtensionEngineProbe.Stage.ADAPTER
            val result = ExtensionEngineProbe.decode(evaluate(ExtensionEngineProbe.compileScript(stage,
                "window.__ownedProbeExecuted=true;throw new Error('must not execute');")), stage)
            assertEquals(ExtensionEngineProbe.Result.Passed, result)
            assertEquals("\"undefined\"", evaluate("typeof window.__ownedProbeExecuted"))
            assertEquals(ExtensionEngineProbe.Result.Rejected(ExtensionEngineFailure.SYNTAX),
                ExtensionEngineProbe.decode(evaluate(ExtensionEngineProbe.compileScript(stage, "function (")), stage))
            val apiResult = ExtensionEngineProbe.decode(evaluate(ExtensionEngineProbe.apiScript()), ExtensionEngineProbe.Stage.APIS)
            if (Build.VERSION.SDK_INT == 26) {
                assertTrue(apiResult is ExtensionEngineProbe.Result.Rejected && apiResult.failure == ExtensionEngineFailure.MISSING_APIS)
            } else assertEquals(ExtensionEngineProbe.Result.Passed, apiResult)
            record.put("compile_body_executed", false).put("invalid_syntax_caught", true).put("api_probe_result", apiResult.toString()).put("passed", true)
        } finally { instrumentation.runOnMainSync { engine?.destroy() } }
    }

    private fun modernCommand(name: String, type: String, row: String, updated: String) {
        assumeTrue(Build.VERSION.SDK_INT == 36)
        val caller = "modern-$name-caller-日本"
        compose.activityRule.scenario.onActivity { it.store.reset(); it.store.search(caller); it.store.openExtension(extension, extension.commands.single { c -> c.name == name }) }
        compose.waitUntil(20000) { compose.activity.store.extensions.runtimeTree != null || compose.activity.store.extensions.runtimeError != null }
        assertNull(compose.activity.store.extensions.runtimeError)
        assertEquals(type, activeRoot(compose.activity.store.extensions.runtimeTree)?.optString("type"))
        compose.onNodeWithText(row).assertExists()
        capture("$name-ready")
        compose.onNodeWithTag("extension-primary").performTouchInput { click(center) }
        compose.waitUntil(10000) { compose.activity.store.extensions.runtimeTree?.toString()?.contains(updated) == true }
        compose.onNodeWithText(updated).assertExists()
        capture("$name-action")
        device.pressBack()
        compose.waitUntil(10000) { compose.activity.store.dialog == null }
        assertEquals(caller, compose.activity.store.query)
        record.put("live_action_published", true).put("caller_query_restored", true).put("passed", true)
        capture("$name-returned")
    }

    @Test fun modernEnginePublishesListAndRunsLiveAction() = modernCommand("index", "List", "Owned engine row one", "Owned List action published")
    @Test fun modernEnginePublishesGridAndRunsLiveAction() = modernCommand("grid", "Grid", "Owned grid row one", "Owned Grid action published")

    @Test fun closingBeforePreflightFinishesRejectsLatePublication() {
        val caller = "cancelled-engine-caller-日本"
        compose.activityRule.scenario.onActivity {
            it.store.reset(); it.store.search(caller)
            it.store.openExtension(extension, extension.commands.single { c -> c.name == "index" })
            val runtime = ExtensionRuntime(it, it.store.extensions, extension, it.store.extensions.running!!.second)
            runtime.start()
            runtime.close()
            it.store.extensions.close(); it.store.dialog = null
        }
        // Two UI-loop turns plus the bounded startup interval permit a queued
        // page callback to arrive; the old owner must stay closed.
        val deadline = SystemClock.uptimeMillis() + 1200
        compose.waitUntil(3000) { SystemClock.uptimeMillis() >= deadline }
        assertNull(compose.activity.store.extensions.runtimeTree)
        assertNull(compose.activity.store.extensions.runtimeError)
        assertNull(compose.activity.store.dialog)
        assertEquals(caller, compose.activity.store.query)
        record.put("closed_owner_remained_inactive", true).put("passed", true)
        capture("cancelled-caller")
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
            val case = testName.methodName.replace(Regex("[A-Z]")) { "-${it.value.lowercase()}" }
            File(evidence, "case-$case.json").writeText(record.toString(2))
        }
    }
}
