package dev.tinycast.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.tinycast.android.extensions.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Owned real-runtime protocol probes. These are not Store/service workflows. */
@RunWith(AndroidJUnit4::class)
class ExtensionCommandHandoffDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val name = org.junit.rules.TestName()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private val evidence get() = File(context.filesDir, "reviewed-text-evidence").apply { mkdirs() }
    private val catalog get() = activity().store.extensions
    private val sentinel = "Owned command handoff clipboard sentinel"
    private val caller = "owned-command-handoff-caller"
    private val ownedText = "café é 日本 🌱\n\"owned\""
    private var id: String? = null
    private var prepared = false
    private var sentinelInstalled = false
    private var palette = emptyMap<String, Any?>()
    private var preserved = emptyList<File>()
    private var fingerprints = emptyList<Map<String, String>>()
    private var originalDirectories = emptySet<String>()
    private val record = JSONObject().put("scope", "Owned JavaScript/native handoff probes; no Store, service, physical-typing or broad parity credit")

    private fun hash(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private fun fingerprint(file: File): Map<String, String> = if (!file.exists()) emptyMap() else file.walkTopDown().associate {
        it.relativeTo(file).path to if (it.isDirectory) "directory" else hash(it.readBytes())
    }
    private fun lease(): JSONObject {
        val args = InstrumentationRegistry.getArguments()
        val value = JSONObject(File(context.filesDir, "reviewed-text-fixture-lease/owners.json").readText())
        assertEquals("reviewed-text-device-test-v1", value.getString("fixture_kind"))
        assertEquals(args.getString("expectedTextInputLeaseOwner"), value.getString("owner_nonce"))
        assertEquals(args.getString("expectedClipboardRecoveryOwner"), value.getString("owner_nonce"))
        return value
    }
    private fun currentActivity(): MainActivity? {
        var value: MainActivity? = null
        instrumentation.runOnMainSync {
            value = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().singleOrNull { it.window.decorView.hasWindowFocus() && !it.isFinishing }
        }
        return value
    }
    private fun activity(): MainActivity {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().single { it.window.decorView.hasWindowFocus() && !it.isFinishing }
        return requireNotNull(currentActivity())
    }
    private fun extension() = catalog.installed.single { it.listing.id == id }
    private fun command(value: String) = extension().commands.single { it.name == value }
    private fun clipboard(): String? {
        val value = AtomicReference<String?>()
        instrumentation.runOnMainSync { value.set(context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()) }
        return value.get()
    }
    @Before fun prepare() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Use the guarded input runner", args.getString("reviewedTextFixture") == "true")
        assertEquals("Tinycast_Tablet_API_36", args.getString("dedicatedAvd"))
        lease()
        val root = File(context.filesDir, "extensions")
        originalDirectories = root.listFiles().orEmpty().map { it.name }.toSet()
        preserved = root.listFiles().orEmpty().flatMap { directory -> listOf(directory,
            File(context.filesDir, "extension-data/${directory.name}"), File(context.applicationInfo.dataDir, "shared_prefs/extension-${directory.name}.xml")) }
        fingerprints = preserved.map(::fingerprint)
        palette = context.getSharedPreferences("palette", Context.MODE_PRIVATE).all.toMap()
        prepared = true
        val next = "reviewed-text-" + UUID.randomUUID().toString().replace("-", "")
        listOf(File(root, next), File(context.cacheDir, next), File(context.filesDir, "extension-data/$next"),
            File(context.applicationInfo.dataDir, "shared_prefs/extension-$next.xml"), File(context.applicationInfo.dataDir, "shared_prefs/extension-$next.xml.bak")).forEach {
            assertFalse(Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS))
        }
        val lease = lease(); lease.getJSONArray("owned_extension_ids").put(next)
        val file = File(context.filesDir, "reviewed-text-fixture-lease/owners.json")
        val temporary = File(file.path + ".tmp"); assertFalse(temporary.exists())
        temporary.writeText(lease.toString()); assertTrue(temporary.renameTo(file)); id = next
        val directory = File(root, next).apply { assertTrue(mkdir()) }
        val parent = """
            const React=require('react'),api=require('@raycast/api');
            globalThis.__ownedHandoffProbe={attempted:0,acks:0,copiesAttempted:0,copiesResolved:0,statusesAttempted:0,publicationsAttempted:0,rejections:[]};
            const options={name:'child',type:api.LaunchType.UserInitiated,arguments:{topic:'owned-topic'},
              context:{text:${JSONObject.quote(ownedText)},nested:[1,true,null]},fallbackText:'owned fallback'};
            module.exports.default=function(props){
              const [count,setCount]=React.useState(0);
              globalThis.__ownedTryLaunch=value=>api.launchCommand(value).then(()=>{globalThis.__ownedHandoffProbe.unexpected=true},error=>{
                globalThis.__ownedHandoffProbe.rejections.push(String(error.message));setCount(n=>n+1)});
              globalThis.__ownedLaunch=duplicate=>{
                const probe=globalThis.__ownedHandoffProbe;
                if(probe.attempted)return;
                probe.attempted++;
                api.launchCommand(options).then(()=>{
                  probe.acks++;
                  api.Clipboard.copy('Owned obsolete parent copy').then(()=>probe.copiesResolved++);
                  probe.copiesAttempted++;
                  api.showHUD('Owned obsolete parent status');probe.statusesAttempted++;
                  globalThis.__tinycastHost.render('owned-retired',JSON.stringify({id:999,type:'Detail',props:{markdown:'Owned obsolete parent publication'},children:[]}));
                  globalThis.__tinycastHost.finished();globalThis.__tinycastHost.failed('Owned obsolete parent failure');probe.publicationsAttempted++;
                },error=>{probe.rejections.push(String(error.message));setCount(n=>n+1)});
                if(duplicate){probe.attempted++;api.launchCommand({...options,context:{text:'Owned second request must not win'}}).then(()=>probe.acks++,()=>{})}
              };
              return React.createElement(api.Detail,{markdown:'Owned parent ready\n\nOwned rejection count '+count,
                actions:React.createElement(api.ActionPanel,null,React.createElement(api.Action,{title:'Launch owned child',onAction:()=>globalThis.__ownedLaunch(false)}))});
            };
        """.trimIndent()
        val child = """
            const React=require('react'),api=require('@raycast/api');
            module.exports.default=function(props){
              const [grant,setGrant]=React.useState('checking');
              React.useEffect(()=>{api.getSelectedText().then(()=>setGrant('unexpectedly available'),()=>setGrant('unavailable'))},[]);
              const payload=JSON.stringify({arguments:props.arguments,launchContext:props.launchContext??null,fallbackText:props.fallbackText??null,launchType:props.launchType});
              globalThis.__ownedChildPayload=payload;
              return React.createElement(api.Detail,{markdown:'Owned child ready\n\nGrant '+grant+'\n\n'+JSON.stringify(JSON.parse(payload),null,2),
                actions:React.createElement(api.ActionPanel,null,React.createElement(api.Action.CopyToClipboard,{title:'Copy owned context',content:payload}))});
            };
        """.trimIndent()
        val manifest = JSONObject("""{"name":"owned-handoff","commands":[
          {"name":"parent","title":"Owned handoff parent","mode":"view"},
          {"name":"child","title":"Owned context child","mode":"view","arguments":[{"name":"topic","type":"text","required":false}]},
          {"name":"configured","title":"Owned configuration target","mode":"view","preferences":[{"name":"account","type":"textfield","required":true}]},
          {"name":"required","title":"Owned required argument","mode":"view","arguments":[{"name":"input","type":"text","required":true}]},
          {"name":"disabled","title":"Owned disabled target","mode":"view","disabled":true},
          {"name":"menu","title":"Owned desktop target","mode":"menu-bar"},
          {"name":"gone","title":"Owned unavailable target","mode":"view"}]}""")
        File(directory, "package.json").writeText(manifest.toString())
        File(directory, "parent.js").writeText(parent)
        for (value in listOf("child", "configured", "required", "disabled", "menu")) File(directory, "$value.js").writeText(child)
        File(directory, ".tinycast-store.json").writeText(StoreExtension(next, "owned-handoff", "Owned command handoff probe", "", "Droidcast tests", "test", "", "", "", 7).json().toString())
        record.put("owned_extension_id", next).put("fixture_parent_sha256", hash(parent.toByteArray()))
            .put("fixture_child_sha256", hash(child.toByteArray())).put("fixture_manifest_sha256", hash(manifest.toString().toByteArray()))
        context.startActivity(Intent().setComponent(ComponentName(context.packageName, MainActivity::class.java.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), 10000))
        compose.waitUntil(15000) { currentActivity()?.store?.loading == false }
        assertTrue(catalog.installed.any { it.listing.id == next })
        dismissClipboardForEvidence()
        instrumentation.runOnMainSync {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Owned handoff fixture", sentinel))
            activity().store.reset(); activity().store.search(caller)
        }
        sentinelInstalled = true
        dismissClipboardForEvidence(requireAction = true)
        record.put("font_scale", context.resources.configuration.fontScale).put("width_dp", context.resources.configuration.screenWidthDp)
            .put("height_dp", context.resources.configuration.screenHeightDp)
        launch("parent")
    }
    private fun launch(value: String) {
        instrumentation.runOnMainSync { activity().store.openExtension(extension(), command(value)) }
        compose.waitUntil(10000) { activity().store.dialog in listOf("extension-run", "extension-setup") }
        if (activity().store.dialog == "extension-setup") {
            compose.onNodeWithTag("run-extension-setup").assertExists()
            tap("run-extension-setup")
            record.put("ordinary_launch_configuration_explicitly_run", true)
        }
        compose.waitUntil(25000) { catalog.runtimeError != null || activeRoot(catalog.runtimeTree)?.props()?.optString("markdown")?.contains(if (value == "parent") "Owned parent ready" else "Grant unavailable") == true }
        assertNull(catalog.runtimeError, catalog.runtimeError); compose.waitForIdle()
    }
    private fun engine(): WebView {
        val values = mutableListOf<WebView>()
        instrumentation.runOnMainSync {
            fun visit(view: View) { if (view is WebView) values += view; if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) } }
            WindowInspector.getGlobalWindowViews().forEach(::visit)
        }
        return values.single()
    }
    private fun evaluate(web: WebView, script: String): String {
        var result: String? = null; val done = CountDownLatch(1)
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { result = it; done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS)); return requireNotNull(result)
    }
    private fun probe(web: WebView) = JSONObject(JSONArray("[${evaluate(web, "JSON.stringify(globalThis.__ownedHandoffProbe)")}]").getString(0))
    private fun payload(): String = JSONArray("[${evaluate(engine(), "globalThis.__ownedChildPayload")}]").getString(0)
    private fun awaitChild() {
        compose.waitUntil(25000) { catalog.runtimeError != null || activeRoot(catalog.runtimeTree)?.props()?.optString("markdown")?.contains("Grant unavailable") == true }
        assertNull(catalog.runtimeError, catalog.runtimeError)
        assertEquals("child", catalog.running?.second?.name)
        compose.onNodeWithText("Owned child ready", substring = true, useUnmergedTree = true).assertIsDisplayed()
    }
    private fun capture(phase: String) {
        compose.waitForIdle()
        val stem = "handoff-" + name.methodName.lowercase(java.util.Locale.ROOT) + "-" + phase
        device.dumpWindowHierarchy(File(evidence, "$stem.xml")); assertTrue(device.takeScreenshot(File(evidence, "$stem.png")))
        File(evidence, "$stem-frame.json").writeText(JSONObject().put("phase", phase).put("scope", record.getString("scope"))
            .put("font_scale", context.resources.configuration.fontScale).put("launch_epoch", catalog.launchEpoch)
            .put("synchronization", "Compose idle; hierarchy before PNG").toString(2))
    }
    private fun tap(tag: String) {
        val node = compose.onNodeWithTag(tag); val minimum = context.resources.displayMetrics.density * 48f
        var steps = 0
        while (node.fetchSemanticsNode().boundsInRoot.let { it.width + 1 < minimum || it.height + 1 < minimum } && steps++ < 30) {
            compose.onNodeWithTag("palette-content-scroll").performTouchInput {
                if (tag.endsWith("back")) swipe(Offset(4f, height * .2f), Offset(4f, height * .8f), 300)
                else swipe(Offset(4f, height * .8f), Offset(4f, height * .2f), 300)
            }
        }
        val bounds = node.fetchSemanticsNode().boundsInRoot
        assertTrue("$tag requires a complete 48dp target", bounds.width + 1 >= minimum && bounds.height + 1 >= minimum)
        node.assertIsDisplayed().assertIsEnabled().performTouchInput { click(center) }
        val taps = record.optJSONArray("pointer_taps") ?: JSONArray().also { record.put("pointer_taps", it) }
        taps.put(JSONObject().put("tag", tag).put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))).put("minimum_px", minimum).put("reveal_gestures", steps))
    }
    private fun action(text: String): () -> Boolean = requireNotNull(compose.onNodeWithText(text).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
    private fun backAction(): () -> Boolean = requireNotNull(compose.onNodeWithTag("extension-back").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
    private fun finish() {
        tap("extension-back")
        assertNull(catalog.running); assertNull(activity().store.dialog); assertEquals(caller, activity().store.query)
        record.put("launcher_query_preserved", true).put("passed", true); capture("caller-return")
    }
    @After fun restore() {
        if (!prepared) return
        try { if (sentinelInstalled && !record.optBoolean("passed") && currentActivity() != null) capture("failure") }
        catch (failure: Throwable) { record.put("failure_capture_error_type", failure.javaClass.simpleName) }
        finally {
            compose.mainClock.autoAdvance = true
            instrumentation.runOnMainSync {
                val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                listOf(Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED).flatMap { monitor.getActivitiesInStage(it) }
                    .filterIsInstance<MainActivity>().distinct().forEach { it.store.extensions.close(); it.store.dialog = null; it.finish() }
            }
            val deadline = android.os.SystemClock.uptimeMillis() + 10000
            var remaining = true
            while (remaining && android.os.SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync { remaining = listOf(Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED)
                    .flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.any { it is MainActivity } }
                if (remaining) android.os.SystemClock.sleep(50)
            }
            assertFalse("Owned activities must close before restoring files", remaining)
            restorePalettePreferences(context, palette)
            id?.let { value ->
                assertTrue(lease().getJSONArray("owned_extension_ids").let { ids -> (0 until ids.length()).any { ids.getString(it) == value } })
                listOf(File(context.filesDir, "extensions/$value"), File(context.cacheDir, value), File(context.filesDir, "extension-data/$value")).forEach { it.deleteRecursively(); assertFalse(it.exists()) }
                context.deleteSharedPreferences("extension-$value")
                listOf(".xml", ".xml.bak").forEach { suffix ->
                    assertFalse(Files.exists(File(context.applicationInfo.dataDir, "shared_prefs/extension-$value$suffix").toPath(), LinkOption.NOFOLLOW_LINKS))
                }
            }
            assertEquals(originalDirectories, File(context.filesDir, "extensions").listFiles().orEmpty().map { it.name }.toSet())
            assertEquals(fingerprints, preserved.map(::fingerprint))
            record.put("owned_data_removed", true).put("preexisting_installations_and_data_preserved", true)
                .put("clipboard_restoration_scope", "Independent host/test-UID durable recovery; this class does not retain or publish the original clipboard")
            File(evidence, "handoff-${name.methodName.lowercase(java.util.Locale.ROOT)}-record.json").writeText(record.toString(2))
        }
    }

    @Test fun contextArgumentsAndFallbackReachChildAndCopy() {
        capture("parent-ready"); tap("extension-primary"); awaitChild()
        val text = payload(); val value = JSONObject(text)
        assertEquals("owned-topic", value.getJSONObject("arguments").getString("topic"))
        assertEquals(ownedText, value.getJSONObject("launchContext").getString("text"))
        assertEquals("[1,true,null]", value.getJSONObject("launchContext").getJSONArray("nested").toString())
        assertEquals("owned fallback", value.getString("fallbackText")); assertEquals("userInitiated", value.getString("launchType"))
        val current = requireNotNull(catalog.running)
        instrumentation.runOnMainSync { assertNull(catalog.captureText(current.first, current.second).input) }
        assertEquals(sentinel, clipboard()); capture("child-context")
        tap("extension-primary"); compose.waitUntil(10000) { clipboard() == text }
        dismissClipboardForEvidence(requireAction = true); capture("exact-context-copy")
        record.put("child_raw_grant_absent", true).put("context_argument_fallback_values_verified", true).put("copy_exact", true)
            .put("owned_payload_sha256", hash(text.toByteArray())); finish()
    }

    private fun pausedHandoff(duplicate: Boolean) {
        val web = engine(); val previous = catalog.launchEpoch; val back = backAction(); val preferences = action("Preferences")
        capture("parent-before-queued-handoff")
        compose.mainClock.autoAdvance = false
        try {
            evaluate(web, "globalThis.__ownedLaunch($duplicate);true")
            val deadline = android.os.SystemClock.uptimeMillis() + 10000
            var observed = probe(web)
            while (observed.optInt("acks") < 1 && android.os.SystemClock.uptimeMillis() < deadline) {
                android.os.SystemClock.sleep(50); observed = probe(web)
            }
            assertEquals(1, observed.getInt("acks")); assertEquals(if (duplicate) 2 else 1, observed.getInt("attempted"))
            assertEquals(1, observed.getInt("copiesAttempted")); assertEquals(0, observed.getInt("copiesResolved"))
            assertEquals(1, observed.getInt("statusesAttempted")); assertEquals(1, observed.getInt("publicationsAttempted"))
            assertEquals(previous + 1, catalog.launchEpoch); assertEquals("child", catalog.running?.second?.name)
            assertFalse(catalog.runtimeReady); assertNull(catalog.runtimeTree); assertNull(catalog.runtimeError)
            assertEquals("Starting extension…", catalog.runtimeStatus); assertEquals(sentinel, clipboard())
            instrumentation.runOnMainSync { back(); preferences() }
            assertEquals("child", catalog.running?.second?.name); assertEquals("extension-run", activity().store.dialog)
            record.put("paused_parent_probe", observed).put("one_transfer_before_native_composition", true)
                .put("obsolete_host_and_chrome_callbacks_rejected", true)
                .put("protocol_setup", "Compose frame clock paused for the logical handoff/acknowledgement and queued-callback probes; child native rendering follows after resuming")
        } finally { compose.mainClock.autoAdvance = true }
        awaitChild(); assertEquals(ownedText, JSONObject(payload()).getJSONObject("launchContext").getString("text"))
        capture("child-after-queued-callbacks"); finish()
    }
    @Test fun acknowledgementRevokesParentBeforeChildComposition() = pausedHandoff(false)
    @Test fun duplicateLaunchRequestsTransferOnlyOnce() = pausedHandoff(true)

    @Test fun queuedRequestCannotLaunchAfterParentCancellation() {
        val web = engine(); val previous = catalog.launchEpoch
        compose.mainClock.autoAdvance = false
        try {
            instrumentation.runOnMainSync {
                web.evaluateJavascript("globalThis.__ownedLaunch(false);true", null)
                activity().store.extensions.close(); activity().store.dialog = null
            }
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            var observed = probe(web)
            while (observed.optInt("attempted") == 0 && android.os.SystemClock.uptimeMillis() < deadline) { android.os.SystemClock.sleep(50); observed = probe(web) }
            assertEquals(1, observed.getInt("attempted")); assertEquals(0, observed.getInt("acks"))
            assertNull(catalog.running); assertNull(catalog.runtimeTree); assertEquals(previous, catalog.launchEpoch); assertEquals(sentinel, clipboard())
            record.put("cancelled_before_commit", true).put("cancelled_request_probe", observed)
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitForIdle(); assertEquals(caller, activity().store.query)
        record.put("passed", true); capture("cancelled-caller")
    }

    @Test fun invalidRequestsKeepTheParentAndClipboard() {
        val web = engine(); val epoch = catalog.launchEpoch
        val requests = listOf(
            JSONObject("""{"name":"child","type":"background"}"""),
            JSONObject("""{"name":"child","type":"userInitiated","extensionName":"another"}"""),
            JSONObject("""{"name":"missing","type":"userInitiated"}"""),
            JSONObject("""{"name":"disabled","type":"userInitiated"}"""),
            JSONObject("""{"name":"menu","type":"userInitiated"}"""),
            JSONObject("""{"name":"gone","type":"userInitiated"}"""),
            JSONObject("""{"name":"configured","type":"userInitiated"}"""),
            JSONObject("""{"name":"required","type":"userInitiated"}"""),
            JSONObject("""{"name":"child","type":"userInitiated","arguments":{"unknown":"owned"}}"""),
            JSONObject().put("name", "child").put("type", "userInitiated").put("context", JSONObject().put("text", "x".repeat(66000)))
        )
        val receipts = JSONArray()
        requests.forEachIndexed { index, options ->
            evaluate(web, "globalThis.__ownedTryLaunch(${options});true")
            compose.waitUntil(10000) { probe(web).getJSONArray("rejections").length() == index + 1 &&
                activeRoot(catalog.runtimeTree)?.props()?.optString("markdown")?.contains("Owned rejection count ${index + 1}") == true }
            compose.onNodeWithText("Owned rejection count ${index + 1}", substring = true, useUnmergedTree = true).assertIsDisplayed()
            assertNull(catalog.runtimeError); assertEquals(epoch, catalog.launchEpoch); assertEquals("parent", catalog.running?.second?.name); assertEquals(sentinel, clipboard())
            receipts.put(JSONObject().put("index", index).put("message", probe(web).getJSONArray("rejections").getString(index)).put("same_epoch_and_parent", true))
        }
        assertFalse(probe(web).optBoolean("unexpected")); record.put("rejection_receipts", receipts)
        capture("rejected-request-feedback"); finish()
    }

    @Test fun ordinaryRelaunchClearsContextAndFallback() {
        tap("extension-primary"); awaitChild(); assertEquals(ownedText, JSONObject(payload()).getJSONObject("launchContext").getString("text"))
        tap("extension-back"); launch("child")
        val value = JSONObject(payload())
        assertTrue(value.isNull("launchContext")); assertTrue(value.isNull("fallbackText")); assertEquals(0, value.getJSONObject("arguments").length())
        assertEquals(sentinel, clipboard()); record.put("ordinary_launch_has_no_old_context_arguments_or_fallback", true)
        capture("ordinary-child-without-context"); finish()
    }

    @Test fun recreatedChildRejectsDisposedBackCallback() {
        tap("extension-primary"); awaitChild(); val previous = activity(); val previousCatalog = catalog; val oldBack = backAction(); val expected = payload()
        instrumentation.runOnMainSync { previous.recreate() }
        compose.waitUntil(20000) { currentActivity()?.let { it !== previous && !it.store.loading } == true }
        awaitChild(); assertSame(previousCatalog, catalog); assertEquals(expected, payload())
        instrumentation.runOnMainSync { oldBack() }
        assertEquals("Disposed Back must not close the recreated child", "child", catalog.running?.second?.name)
        assertEquals("extension-run", activity().store.dialog); assertEquals(sentinel, clipboard())
        record.put("current_invocation_context_survived_recreation", true).put("disposed_back_rejected_after_recreation", true)
        capture("recreated-child-retains-context"); finish()
    }

    @Test fun returnedCommandRejectsDisposedPreferencesCallback() {
        val oldPreferences = action("Preferences"); val previous = catalog.launchEpoch
        compose.onNodeWithText("Preferences").performTouchInput { click(center) }
        compose.onNodeWithTag("extension-configuration").assertExists()
        tap("extension-back")
        compose.waitUntil(10000) { activity().store.dialog == "extension-run" }
        instrumentation.runOnMainSync { oldPreferences() }
        assertEquals("Disposed Preferences must not replace the restored command", "extension-run", activity().store.dialog)
        assertEquals(previous, catalog.launchEpoch); assertEquals("parent", catalog.running?.second?.name); assertEquals(sentinel, clipboard())
        record.put("disposed_preferences_rejected_after_route_return", true); capture("restored-parent-rejects-old-preferences"); finish()
    }
}
