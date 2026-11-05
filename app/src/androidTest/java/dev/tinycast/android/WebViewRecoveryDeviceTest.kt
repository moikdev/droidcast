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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
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
            .put("fixture_bytecode_scope", "ES5 owned List/Grid; local React-state action only; no preferences, storage, clipboard or networking")
    }

    private fun capture(phase: String) {
        compose.waitForIdle()
        val frames = java.util.concurrent.CountDownLatch(1)
        instrumentation.runOnMainSync { compose.activity.window.decorView.postOnAnimation {
            compose.activity.window.decorView.postOnAnimation { frames.countDown() }
        } }
        assertTrue("Two UI frames", frames.await(5, java.util.concurrent.TimeUnit.SECONDS))
        compose.waitForIdle()
        evidence.mkdirs()
        testAutomation().clearCacheForTest()
        device.dumpWindowHierarchy(File(evidence, "$phase.xml"))
        assertTrue(device.takeScreenshot(File(evidence, "$phase.png")))
        File(evidence, "$phase-frame.json").writeText(JSONObject().put("phase", phase)
            .put("synchronization", "Compose idle and two UI frames; accessibility cache reset where supported; hierarchy before PNG; XML geometry is not a visibility witness on API26")
            .put("api", Build.VERSION.SDK_INT).put("scope", record.getString("scope")).toString(2))
    }

    private fun recoveryTargetsAndText() {
        val viewport = compose.onNodeWithTag("extension-engine-problem").fetchSemanticsNode().boundsInRoot
        val density = context.resources.displayMetrics.density
        for (tag in listOf("extension-engine-settings", "extension-engine-return")) {
            val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("Full 48dp recovery target: $tag", bounds.height >= 48 * density - 1 &&
                bounds.top >= viewport.top - 1 && bounds.bottom <= viewport.bottom + 1 &&
                bounds.left >= viewport.left - 1 && bounds.right <= viewport.right + 1)
            record.append("target_witnesses", org.json.JSONArray().put(tag).put(bounds.width).put(bounds.height))
        }
        val body = compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag("extension-engine-problem")))
        for (tag in listOf("extension-engine-title", "extension-engine-provider", "extension-engine-explanation", "extension-engine-guidance")) {
            val text = compose.onNodeWithTag(tag, useUnmergedTree = true)
            text.performScrollTo(); compose.waitForIdle()
            val node = text.fetchSemanticsNode(); val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(requireNotNull(node.config[SemanticsActions.GetTextLayoutResult].action).invoke(layouts))
            val layout = layouts.single()
            assertFalse(layout.didOverflowHeight); assertEquals(layout.size.height, node.size.height)
            repeat(layout.lineCount) { line ->
                assertFalse(layout.isLineEllipsized(line))
                assertTrue(layout.getLineLeft(line) >= -1 && layout.getLineRight(line) <= node.size.width + 1)
                var reached = false
                for (attempt in 0..30) {
                    val current = text.fetchSemanticsNode(); val visible = body.fetchSemanticsNode().boundsInRoot
                    val origin = compose.runOnUiThread { current.layoutInfo.coordinates.localToRoot(Offset.Zero) }
                    val top = origin.y + layout.getLineTop(line); val bottom = origin.y + layout.getLineBottom(line)
                    if (top >= visible.top - 1 && bottom <= visible.bottom + 1) { reached = true; break }
                    val delta = (if (top < visible.top) top - visible.top - 4 else bottom - visible.bottom + 4)
                        .coerceIn(-visible.height * .4f, visible.height * .4f)
                    body.performTouchInput { swipe(Offset(centerX, centerY + delta / 2), Offset(centerX, centerY - delta / 2), 250) }
                    compose.waitForIdle()
                }
                assertTrue("Full natural line $line of $tag", reached)
            }
            record.append("text_witnesses", JSONObject().put("tag", tag).put("lines", layout.lineCount).put("all_lines_reached", true))
        }
        record.put("text_setup", "Semantic scroll to each paragraph; pointer swipes establish sequential full-line reachability")
        capture("engine-content-end")
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
        recoveryTargetsAndText()
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
            compose.waitUntil(10000) {
                var complete = false
                instrumentation.runOnMainSync { complete = engine?.progress == 100 }
                complete
            }
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
        if (type == "Grid") compose.onNodeWithTag("extension-grid-cell-0").assertTextContains(row, substring = true)
        else compose.onNodeWithText(row).assertExists()
        capture("$name-ready")
        compose.onNodeWithTag("extension-primary").performTouchInput { click(center) }
        compose.waitUntil(10000) { compose.activity.store.extensions.runtimeTree?.toString()?.contains(updated) == true }
        if (type == "Grid") compose.onNodeWithTag("extension-grid-cell-0").assertTextContains(updated, substring = true)
        else compose.onNodeWithText(updated).assertExists()
        capture("$name-action")
        compose.onNodeWithTag("extension-back").performTouchInput { click(center) }
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

    @Test fun bridgeCannotPublishOrReadHostApisBeforeApproval() {
        lateinit var runtime: ExtensionRuntime
        lateinit var bridge: ExtensionRuntime.Bridge
        val file = File(extension.directory, "index.js").canonicalFile
        assertTrue("The harmless supported probe targets the existing owned fixture", file.isFile)
        val arguments = org.json.JSONArray().put(file.path).toString()
        try {
            compose.activityRule.scenario.onActivity { activity ->
                val catalog = activity.store.extensions
                activity.store.reset()
                activity.store.openExtension(extension, extension.commands.single { it.name == "index" })
                // This test owns its engine directly; keep Compose from
                // constructing another runtime for the same invocation.
                activity.store.dialog = null
                runtime = ExtensionRuntime(activity, catalog, extension, catalog.running!!.second)
                bridge = runtime.Bridge()
                runtime.Bridge().render("{\"type\":\"List\",\"children\":[]}")
                val response = JSONObject(bridge.invokeSync("fs", "exists", arguments))
                assertFalse("Supported fs.exists must remain blocked before approval", response.getBoolean("ok"))
                assertNull(catalog.runtimeTree)
                runtime.dispatch("not-approved")
                record.put("preflight_bridge_render_rejected", true).put("preflight_host_api_rejected", true)
                    .put("supported_probe_operation", "fs.exists").put("owned_probe_file_exists", true)
                    .put("preflight_response_ok", response.getBoolean("ok"))
                if (Build.VERSION.SDK_INT == 36) runtime.start()
            }
            if (Build.VERSION.SDK_INT == 36) {
                compose.waitUntil(20000) { compose.activity.store.extensions.runtimeError != null ||
                    (runtime.isCurrentInvocation() && compose.activity.store.extensions.runtimeTree != null) }
                compose.activityRule.scenario.onActivity { activity ->
                    assertNull(activity.store.extensions.runtimeError)
                    assertTrue(runtime.isCurrentInvocation())
                    val response = JSONObject(bridge.invokeSync("fs", "exists", arguments))
                    assertTrue("The same supported probe succeeds after actual approval", response.getBoolean("ok"))
                    assertTrue("The same owned fixture still exists", response.getBoolean("value"))
                    record.put("approved_response_ok", true).put("approved_owned_file_exists", true)
                        .put("same_engine_bridge_operation_and_arguments", true)
                }
            } else record.put("approved_control_scope", "The paired approved control runs on API36; this actual API26 engine cannot pass preflight")
        } finally {
            compose.activityRule.scenario.onActivity { activity ->
                if (::runtime.isInitialized) runtime.close()
                activity.store.extensions.close(); activity.store.dialog = null
            }
        }
        compose.waitForIdle()
        assertNull(compose.activity.store.extensions.runtimeTree)
        record.put("passed", true)
    }

    @Test fun replacedRecoveryRejectsRetainedSettingsAndReturnCallbacks() {
        assumeTrue(Build.VERSION.SDK_INT == 26)
        compose.activityRule.scenario.onActivity { it.store.openExtension(extension, extension.commands.single { c -> c.name == "index" }) }
        compose.waitUntil(15000) { compose.activity.store.extensions.runtimeError != null }
        val settings = requireNotNull(compose.onNodeWithTag("extension-engine-settings").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val back = requireNotNull(compose.onNodeWithTag("extension-engine-return").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val monitor = instrumentation.addMonitor(android.content.IntentFilter(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS),
            android.app.Instrumentation.ActivityResult(0, null), true)
        try {
            compose.activityRule.scenario.onActivity {
                val next = extension.commands.single { c -> c.name == "grid" }
                it.store.openExtension(extension, next)
                // Invoke retained callbacks before Compose disposes the old UI.
                settings(); back()
                assertEquals("extension-run", it.store.dialog)
                assertSame(next, it.store.extensions.running!!.second)
            }
            compose.waitForIdle()
            assertEquals(0, monitor.hits)
            record.put("stale_settings_launches", 0).put("replacement_not_closed", true).put("passed", true)
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun equivalentFailureGetsFreshRecoveryActionsBeforeRecomposition() {
        assumeTrue(Build.VERSION.SDK_INT == 26)
        compose.activityRule.scenario.onActivity { it.store.openExtension(extension, extension.commands.single { c -> c.name == "index" }) }
        compose.waitUntil(15000) { compose.activity.store.extensions.runtimeEngineProblem != null }
        // Keep newer application types out of reflected synthetic method
        // signatures so the common old-app case can initialize under JUnit.
        val first: Any = requireNotNull(compose.activity.store.extensions.runtimeEngineProblem)
        compose.onNodeWithTag("extension-engine-settings").performTouchInput { click(center) }
        assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.pkg("com.android.settings")), 10000))
        device.pressBack(); compose.waitUntil(10000) { compose.activity.hasWindowFocus() }
        compose.onNodeWithTag("extension-engine-settings").assertIsNotEnabled()
        compose.activityRule.scenario.onActivity {
            val next = extension.commands.single { c -> c.name == "index" }
            it.store.openExtension(extension, next)
            val catalog = it.store.extensions
            val current = requireNotNull(catalog.running).second
            assertEquals("index", current.name)
            val owner = catalog.captureText(extension, current).owner
            val previous = first as ExtensionEngineProblem
            assertTrue(catalog.reportEngineProblem(owner, extension, current,
                ExtensionEngineProblem(previous.engine, previous.failure, previous.missingApis)))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("extension-engine-settings").assertIsEnabled()
        compose.onNodeWithTag("extension-engine-return").assertIsEnabled()
        record.put("second_failure_scope", "Controlled native publication of an equivalent failure before recomposition; first failure used the actual legacy engine")
            .put("fresh_recovery_actions", true)
        capture("equivalent-failure-ready")
        compose.onNodeWithTag("extension-engine-return").performTouchInput { click(center) }
        compose.waitUntil(10000) { compose.activity.store.dialog == null }
        record.put("passed", true)
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
