package dev.tinycast.android

import android.app.Dialog
import android.view.KeyEvent
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.atomic.AtomicInteger
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
class ExtensionPasteCopyDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val name = org.junit.rules.TestName()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private val evidence get() = File(context.filesDir, "reviewed-text-evidence").apply { mkdirs() }
    private val catalog get() = activity().store.extensions
    private val sentinel = "Owned paste copy clipboard sentinel"
    private val caller = "owned-paste-copy-caller"
    private val ownedText = "café é 日本 🌱\n\"owned\""
    private val notifications = AtomicInteger(0)
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private val dialogs = mutableListOf<Dialog>()
    private val retainedEngines = mutableListOf<ExtensionRuntime>()
    private var id: String? = null
    private var prepared = false
    private var sentinelInstalled = false
    private var palette = emptyMap<String, Any?>()
    private var preserved = emptyList<File>()
    private var fingerprints = emptyList<Map<String, String>>()
    private var originalDirectories = emptySet<String>()
    private val record = JSONObject().put("scope", "Owned JavaScript/native paste/Copy protocols; no service, physical-typing or broad parity credit")

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
            const output=${JSONObject.quote(ownedText)};
            globalThis.__ownedPasteProbe={attempted:0,rejected:0,resolved:0};
            module.exports.default=function(){
              const [count,setCount]=React.useState(0);
              globalThis.__ownedTryPaste=(value=output,options)=>{
                const probe=globalThis.__ownedPasteProbe;probe.attempted++;
                api.Clipboard.paste(value,options).then(()=>probe.resolved++,error=>{
                  probe.rejected++;probe.lastError=String(error.message);setCount(probe.rejected)});
              };
              const actions=React.createElement(api.ActionPanel,null,React.createElement(api.Action,{title:'Try owned paste',onAction:()=>globalThis.__ownedTryPaste()}));
              if(api.environment.commandName==='editor')return React.createElement(api.Form,{actions},
                React.createElement(api.Form.TextField,{id:'owned-input',title:'Owned input',defaultValue:'Owned editor text'}));
              return React.createElement(api.Detail,{markdown:'Owned paste ready\n\nOwned rejection '+count,actions});
            };
        """.trimIndent()
        val crash = """
            const api=require('@raycast/api');
            globalThis.__ownedPasteProbe={attempted:0,rejected:0,resolved:0};
            module.exports.default=async()=>{globalThis.__ownedPasteProbe.attempted++;
              await api.Clipboard.paste(${JSONObject.quote(ownedText)});globalThis.__ownedPasteProbe.resolved++};
        """.trimIndent()
        val manifest = JSONObject("""{"name":"owned-paste-copy","commands":[
          {"name":"parent","title":"Owned paste parent","mode":"view"},
          {"name":"steady","title":"Owned replacement parent","mode":"view"},
          {"name":"editor","title":"Owned input parent","mode":"view"},
          {"name":"crash","title":"Owned uncaught paste","mode":"no-view"}]}""")
        File(directory,"package.json").writeText(manifest.toString())
        for(value in listOf("parent","steady","editor"))File(directory,"$value.js").writeText(parent)
        File(directory,"crash.js").writeText(crash)
        File(directory,".tinycast-store.json").writeText(StoreExtension(next,"owned-paste-copy","Owned paste Copy probe","","Droidcast tests","test","","","",4).json().toString())
        record.put("owned_extension_id",next).put("fixture_parent_sha256",hash(parent.toByteArray()))
            .put("fixture_crash_sha256",hash(crash.toByteArray())).put("fixture_manifest_sha256",hash(manifest.toString().toByteArray()))
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
        instrumentation.waitForIdleSync()
        listener = ClipboardManager.OnPrimaryClipChangedListener { notifications.incrementAndGet() }
        instrumentation.runOnMainSync { context.getSystemService(ClipboardManager::class.java).addPrimaryClipChangedListener(requireNotNull(listener)) }
        launch("parent")
    }
    private fun launch(value:String) {
        instrumentation.runOnMainSync { activity().store.openExtension(extension(),command(value)) }
        if(value=="crash"){
            compose.waitUntil(20000){catalog.runtimeError!=null && catalog.pasteCopyOffer!=null}
        }else{
            compose.waitUntil(20000){activeRoot(catalog.runtimeTree)?.let { if(value=="editor")it.optString("type")=="Form" else it.props().optString("markdown").contains("Owned paste ready") }==true}
            assertNull(catalog.runtimeError,catalog.runtimeError)
        }
        compose.waitForIdle()
    }
    private fun engine():WebView {
        val values=mutableListOf<WebView>()
        instrumentation.runOnMainSync {
            fun visit(view:View){if(view is WebView)values+=view;if(view is ViewGroup)repeat(view.childCount){visit(view.getChildAt(it))}}
            WindowInspector.getGlobalWindowViews().forEach(::visit)
        }
        return values.single()
    }
    private fun evaluate(script:String):String {
        var value:String?=null;val done=CountDownLatch(1)
        val web=engine()
        instrumentation.runOnMainSync { web.evaluateJavascript(script){value=it;done.countDown()} }
        assertTrue(done.await(5,TimeUnit.SECONDS));return requireNotNull(value)
    }
    private fun probe():JSONObject=JSONObject(JSONArray("[${evaluate("JSON.stringify(globalThis.__ownedPasteProbe)")}]").getString(0))
    private fun rejected(count:Int){
        compose.waitUntil(15000){probe().optInt("rejected")==count && activeRoot(catalog.runtimeTree)?.props()?.optString("markdown")?.contains("Owned rejection $count")==true}
    }
    private fun pending():ExtensionCatalog.PasteCopyOffer {
        tap("extension-primary")
        compose.waitUntil(15000){catalog.pasteCopyOffer!=null && probe().optInt("rejected")==1}
        compose.onNodeWithTag("extension-paste-copy").assertExists()
        assertEquals(sentinel,clipboard());assertEquals(0,probe().getInt("resolved"))
        return requireNotNull(catalog.pasteCopyOffer)
    }
    private fun oldCopy():()->Boolean=requireNotNull(compose.onNodeWithTag("extension-paste-copy").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
    private fun copyExpected(expected:String=ownedText){
        tap("extension-paste-copy")
        compose.waitUntil(10000){clipboard()==expected}
        assertArrayEquals(expected.toByteArray(Charsets.UTF_8),requireNotNull(clipboard()).toByteArray(Charsets.UTF_8))
        compose.onNodeWithTag("extension-paste-title").assertTextEquals("Result copied")
        compose.onNodeWithTag("extension-paste-copy").assertDoesNotExist()
        dismissClipboardForEvidence(requireAction=true)
        record.put("copy_exact",true).put("owned_output_sha256",hash(expected.toByteArray())).put("clipboard_notifications",notifications.get())
    }
    private fun finish(){
        tap("extension-paste-return");assertNull(catalog.running);assertNull(activity().store.dialog);assertEquals(caller,activity().store.query)
        record.put("passed",true).put("launcher_query_preserved",true);capture("caller-return")
    }
    private fun capture(phase: String) {
        compose.waitForIdle()
        val stem = "pastecopy-" + name.methodName.lowercase(java.util.Locale.ROOT) + "-" + phase
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
    @After fun restore() {
        if (!prepared) return
        try { if (sentinelInstalled && !record.optBoolean("passed") && currentActivity() != null) capture("failure") }
        catch (failure: Throwable) { record.put("failure_capture_error_type", failure.javaClass.simpleName) }
        finally {
            compose.mainClock.autoAdvance = true
            instrumentation.runOnMainSync {
                listener?.let { context.getSystemService(ClipboardManager::class.java).removePrimaryClipChangedListener(it) };listener=null
                dialogs.forEach { it.dismiss() };retainedEngines.forEach { it.close() }
            }
            record.put("clipboard_notifications",notifications.get())
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
            File(evidence, "pastecopy-${name.methodName.lowercase(java.util.Locale.ROOT)}-record.json").writeText(record.toString(2))
        }
    }

    @Test fun pointerCopyIsExactOnceAndReturns() {
        val held=pending();val obsolete=oldCopy();capture("pending-before-copy")
        copyExpected();assertFalse(requireNotNull(held.output).available)
        capture("unicode-exact-copy")
        val afterCopySentinel="Owned fresh sentinel after real Unicode Copy"
        instrumentation.runOnMainSync {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Owned duplicate probe",afterCopySentinel))
        }
        dismissClipboardForEvidence(requireAction=true)
        instrumentation.runOnMainSync { obsolete();obsolete() }
        instrumentation.waitForIdleSync();assertEquals(afterCopySentinel,clipboard())
        record.put("duplicate_after_copy_rejected",true)
            .put("duplicate_probe_setup","After the real exact Copy, a separate owned sentinel is written; retained callbacks must leave it unchanged. Listener notifications are diagnostics, not native write counts.")
        capture("duplicate-sentinel-preserved");finish()
    }
    @Test fun returnAndEscapeCancelWithoutCopy() {
        val held=pending();val obsolete=oldCopy();capture("pending-before-return")
        tap("extension-paste-return");assertNull(catalog.running);assertEquals(caller,activity().store.query)
        instrumentation.runOnMainSync { obsolete() };assertFalse(requireNotNull(held.output).available)
        assertEquals(sentinel,clipboard())
        launch("parent");val second=pending();val oldSecond=oldCopy()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ESCAPE)
        compose.waitUntil(10000){activity().store.dialog==null}
        instrumentation.runOnMainSync { oldSecond() };assertFalse(requireNotNull(second.output).available)
        assertNull(catalog.running);assertEquals(sentinel,clipboard())
        record.put("return_and_escape_cancelled_without_copy",true).put("passed",true);capture("cancelled-caller")
    }
    @Test fun replacementRejectsQueuedCopyBeforeRecomposition() {
        val held=pending();val obsolete=oldCopy();val before=catalog.launchEpoch
        compose.mainClock.autoAdvance=false
        try {
            instrumentation.runOnMainSync { catalog.launch(extension(),command("steady"));obsolete() }
            assertEquals(before+1,catalog.launchEpoch);assertFalse(requireNotNull(held.output).available)
            assertEquals(sentinel,clipboard())
        } finally { compose.mainClock.autoAdvance=true }
        compose.waitUntil(20000){activeRoot(catalog.runtimeTree)?.props()?.optString("markdown")?.contains("Owned paste ready")==true}
        compose.onNodeWithTag("extension-paste-copy").assertDoesNotExist()
        pending();copyExpected();record.put("queued_replaced_copy_rejected",true);capture("fresh-replacement-copy");finish()
    }
    @Test fun sameSessionReplacementCannotCopyThePriorOutput() {
        val held=pending();val obsolete=oldCopy();val newer="Owned newer output β é 🦊"
        val epoch=catalog.launchEpoch
        compose.mainClock.autoAdvance=false
        try {
            evaluate("globalThis.__ownedTryPaste(${JSONObject.quote(newer)});true")
            val deadline=android.os.SystemClock.uptimeMillis()+15000
            while(probe().optInt("rejected")<2 && android.os.SystemClock.uptimeMillis()<deadline)android.os.SystemClock.sleep(50)
            assertEquals(2,probe().getInt("rejected"));assertEquals(epoch,catalog.launchEpoch)
            assertFalse(requireNotNull(held.output).available)
            instrumentation.runOnMainSync { obsolete() };assertEquals(sentinel,clipboard())
        } finally { compose.mainClock.autoAdvance=true }
        compose.waitForIdle();copyExpected(newer)
        record.put("same_session_old_output_rejected",true).put("protocol_setup","Compose clock paused for replacement/old-callback assertions; actual current pointer Copy follows")
        capture("newest-output-copied");finish()
    }
    @Test fun invalidRequestsClearPriorOutputWithoutCopy() {
        val held=pending();val obsolete=oldCopy()
        val values=listOf<Any>(true,JSONObject.NULL,JSONArray().put("owned"),JSONObject().put("file","/owned/not-opened"),
            JSONObject().put("html","<b>owned without plain alternative</b>"),"x".repeat(65537),"界".repeat(45000))
        for((index,value) in values.withIndex()) {
            val encoded=JSONArray().put(value).toString().drop(1).dropLast(1)
            evaluate("globalThis.__ownedTryPaste($encoded);true");rejected(index+2)
            compose.onNodeWithText("Owned rejection ${index+2}",substring=true,useUnmergedTree=true).assertIsDisplayed()
            compose.onNodeWithTag("extension-paste-copy").assertDoesNotExist()
            instrumentation.runOnMainSync { obsolete() }
            assertEquals(sentinel,clipboard());assertFalse(requireNotNull(held.output).available)
        }
        assertEquals(0,probe().getInt("resolved"));assertNull(catalog.pasteCopyOffer)
        record.put("invalid_request_count",values.size).put("old_capability_cleared",true);capture("rejections-without-copy")
        tap("extension-back");assertNull(catalog.running);record.put("passed",true);capture("cancelled-caller")
    }
    @Test fun preferencesDisposalAndHiddenRequestsCannotCopy() {
        val held=pending();val obsolete=oldCopy()
        instrumentation.runOnMainSync { activity().store.extensionSetupFromRun=true;activity().store.dialog="extension-setup" }
        compose.onNodeWithTag("extension-configuration").assertExists();assertNull(catalog.pasteCopyOffer)
        assertFalse(requireNotNull(held.output).available)
        evaluate("globalThis.__ownedTryPaste();true")
        val deadline=android.os.SystemClock.uptimeMillis()+10000
        while(probe().optInt("rejected")<2 && android.os.SystemClock.uptimeMillis()<deadline)android.os.SystemClock.sleep(50)
        assertEquals(2,probe().getInt("rejected"));assertTrue(probe().getString("lastError").contains("foreground"))
        instrumentation.runOnMainSync { obsolete() };assertEquals(sentinel,clipboard())
        tap("extension-back");compose.waitUntil(10000){activity().store.dialog=="extension-run"}
        instrumentation.runOnMainSync { obsolete() };compose.onNodeWithTag("extension-paste-copy").assertDoesNotExist()
        record.put("hidden_route_rejected",true).put("disposed_old_copy_rejected_after_route_return",true)
            .put("route_setup","Controlled native Preferences route replaces pending body; no pointer-navigation claim for that transition")
        capture("restored-parent-without-output")
        tap("extension-primary");compose.waitUntil(10000){catalog.pasteCopyOffer!=null};copyExpected();finish()
    }
    @Test fun losingWindowFocusRejectsRetainedCopy() {
        pending();val obsolete=oldCopy();val previous=activity()
        val dialog=Dialog(previous);dialogs+=dialog
        instrumentation.runOnMainSync { dialog.setContentView(TextView(previous).apply{text="Owned focus boundary"});dialog.show() }
        compose.waitUntil(10000){!previous.window.decorView.hasWindowFocus()}
        instrumentation.runOnMainSync { obsolete() };instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { dialog.dismiss() }
        compose.waitUntil(10000){previous.window.decorView.hasWindowFocus()}
        assertEquals(sentinel,clipboard());copyExpected();record.put("unfocused_retained_copy_rejected",true)
            .put("focus_scope","Real owned modal window removes command-window focus; this is not another-app background certification")
        capture("focused-current-copy");finish()
    }
    @Test fun recreationClearsTheBufferAndDisposedCopy() {
        val held=pending();val obsolete=oldCopy();val previous=activity();val originalTree=catalog.runtimeTree
        val previousCatalog=catalog
        instrumentation.runOnMainSync { previous.recreate() }
        compose.waitUntil(20000){currentActivity()?.let{it!==previous && !it.store.loading}==true}
        compose.waitUntil(20000){catalog.runtimeTree!==originalTree && activeRoot(catalog.runtimeTree)?.props()?.optString("markdown")?.contains("Owned paste ready")==true}
        assertSame(previousCatalog,catalog);assertNull(catalog.pasteCopyOffer);assertFalse(requireNotNull(held.output).available)
        instrumentation.runOnMainSync { obsolete() };assertEquals(sentinel,clipboard())
        record.put("new_native_publication_after_recreation",true).put("old_buffer_cleared",true)
        capture("recreated-without-old-offer");pending();copyExpected();finish()
    }
    @Test fun uncaughtPasteFailureStillRequiresExplicitCopy() {
        launch("crash");val held=requireNotNull(catalog.pasteCopyOffer)
        assertTrue(requireNotNull(catalog.runtimeError).contains("Clipboard paste into another app is unavailable"))
        assertEquals(sentinel,clipboard());assertEquals(0,probe().getInt("resolved"))
        capture("uncaught-rejection-offer");copyExpected();assertFalse(requireNotNull(held.output).available)
        assertEquals(0,probe().getInt("resolved"));record.put("paste_promise_never_resolved",true)
        capture("uncaught-rejection-explicit-copy");finish()
    }
    @Test fun keyboardCopyHidesImeAndEscapeReturns() {
        launch("editor");tap("extension-field-owned-input")
        compose.waitUntil(10000){ViewCompat.getRootWindowInsets(activity().window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==true}
        record.put("actual_ime_open_before_paste",true)
        pending()
        compose.waitUntil(10000){ViewCompat.getRootWindowInsets(activity().window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==false}
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
        compose.waitUntil(10000){!activity().window.decorView.isInTouchMode}
        val button=compose.onNodeWithTag("extension-paste-copy")
        button.performSemanticsAction(SemanticsActions.RequestFocus){assertTrue(it())};button.assertIsFocused()
        capture("copy-keyboard-focused");instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        compose.waitUntil(10000){clipboard()==ownedText}
        compose.onNodeWithTag("extension-paste-title").assertTextEquals("Result copied")
        dismissClipboardForEvidence(requireAction=true)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ESCAPE)
        compose.waitUntil(10000){activity().store.dialog==null};assertNull(catalog.running);assertEquals(caller,activity().store.query)
        record.put("actual_ime_hidden_for_fallback",true).put("focused_enter_copied_exactly",true)
            .put("focus_setup","Instrumentation Tab enters keyboard mode, then RequestFocus semantics; Enter/Escape are actual injected key pairs, not physical typing")
            .put("passed",true);capture("keyboard-caller-return")
    }
    @Test fun closingOtherEnginesCannotClearTheCurrentOwner() {
        val current=requireNotNull(catalog.running);val owner=catalog.captureText(current.first,current.second).owner
        lateinit var stale:ExtensionRuntime
        lateinit var held:ExtensionCatalog.PasteCopyOffer
        instrumentation.runOnMainSync {
            stale=ExtensionRuntime(context,catalog,current.first,current.second);retainedEngines+=stale
            val registration=requireNotNull(catalog.attachRuntime(owner,current.first,current.second){})
            assertTrue(catalog.offerPasteCopy(registration,owner,current.first,current.second,ExtensionPasteCopy.parse(JSONArray().put(ownedText).toString())))
            held=requireNotNull(catalog.pasteCopyOffer)
            stale.close();assertSame(held,catalog.pasteCopyOffer)
            val ghost=ExtensionRuntime(context,catalog,current.first,command("steady"));retainedEngines+=ghost;ghost.close()
            assertSame(held,catalog.pasteCopyOffer);assertTrue(requireNotNull(held.output).available)
        }
        compose.onNodeWithTag("extension-paste-copy").assertExists();assertEquals(sentinel,clipboard());copyExpected()
        record.put("foreign_and_unregistered_engine_disposal_rejected",true)
            .put("publication_scope","Controlled native owner/offer publication with an owned buffer; not a JavaScript producer workflow")
        capture("current-native-owner-copy");finish()
    }
}
