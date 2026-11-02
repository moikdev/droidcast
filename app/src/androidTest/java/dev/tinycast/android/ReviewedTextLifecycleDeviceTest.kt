package dev.tinycast.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.tinycast.android.extensions.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Controlled native Activity/runtime boundary probes, separate from Store workflows. */
@RunWith(AndroidJUnit4::class)
class ReviewedTextLifecycleDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val testName = org.junit.rules.TestName()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val catalog get() = compose.activity.store.extensions
    private val root get() = File(context.filesDir, "reviewed-text-fixture-lease")
    private val evidence get() = File(context.filesDir, "reviewed-text-evidence").apply { mkdirs() }
    private lateinit var extension: InstalledExtension
    private var originalPalette = emptyMap<String, Any?>()
    private var clipboard: ClipData? = null
    private var prepared = false
    private var ownedId: String? = null
    private var directRuntime: ExtensionRuntime? = null
    private var preserved = emptyList<File>()
    private var fingerprints = emptyList<Map<String, String>>()
    private val record = JSONObject().put("scope", "Controlled Activity/runtime ownership probes using an owned JavaScript fixture; not a Store compatibility result")
    private val input = " \tLifecycle café é 🌱\nsecond line \n"
    private fun hash(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private fun fingerprint(file: File) = if (!file.exists()) emptyMap() else file.walkTopDown().associate {
        it.relativeTo(file).path to if (it.isDirectory) "directory" else hash(it.readBytes())
    }
    private fun ui(block: (MainActivity) -> Unit) = compose.activityRule.scenario.onActivity { block(it) }

    @Before fun prepare() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Use the guarded reviewed-text runner", args.getString("reviewedTextFixture") == "true")
        assertEquals("Tinycast_Tablet_API_36", args.getString("dedicatedAvd"))
        val leaseFile = File(root, "owners.json"); val lease = JSONObject(leaseFile.readText())
        assertEquals(args.getString("expectedTextInputLeaseOwner"), lease.getString("owner_nonce"))
        assertEquals("reviewed-text-device-test-v1", lease.getString("fixture_kind"))
        compose.waitUntil(15000) { !compose.activity.store.loading }
        originalPalette = context.getSharedPreferences("palette", Context.MODE_PRIVATE).all.toMap()
        preserved = catalog.installed.flatMap { listOf(it.directory, File(context.filesDir, "extension-data/${it.listing.id}"),
            File(context.applicationInfo.dataDir, "shared_prefs/extension-${it.listing.id}.xml")) }
        fingerprints = preserved.map(::fingerprint)
        dismissClipboardForEvidence()
        ui { clipboard = it.getSystemService(ClipboardManager::class.java).primaryClip; it.store.reset(); it.store.search("reviewed-lifecycle-caller") }
        prepared = true
        val id = "reviewed-text-" + UUID.randomUUID().toString().replace("-", "")
        listOf(File(context.cacheDir, id), File(context.filesDir, "extension-data/$id"),
            File(context.applicationInfo.dataDir, "shared_prefs/extension-$id.xml")).forEach { assertFalse(it.exists()) }
        lease.getJSONArray("owned_extension_ids").put(id)
        val temporary = File(root, "owners.json.tmp"); assertFalse(temporary.exists())
        temporary.writeText(lease.toString()); assertTrue(temporary.renameTo(leaseFile)); ownedId = id
        val directory = File(context.cacheDir, id).apply { assertTrue(mkdir()) }
        val manifest = JSONObject("""{"commands":[{"name":"inspect","title":"Owned input inspector","mode":"view"},{"name":"configure","title":"Owned input configuration","mode":"view","preferences":[{"name":"label","title":"Owned label","type":"textfield","required":true}]},{"name":"background","title":"Owned background input","mode":"no-view"},{"name":"error","title":"Owned host error","mode":"view"}]}""")
        val script = """
            const React=require('react'),api=require('@raycast/api');
            module.exports.default=function(){const [message,setMessage]=React.useState('Waiting for approved input');
              React.useEffect(()=>{api.getSelectedText().then(v=>setMessage('Approved input: '+JSON.stringify(v)),e=>setMessage('Input error: '+e.message))},[]);
              return React.createElement(api.Detail,{markdown:message});};
        """.trimIndent()
        listOf("inspect", "configure").forEach { File(directory, "$it.js").writeText(script) }
        File(directory, "background.js").writeText("const api=require('@raycast/api');module.exports.default=async()=>{const text=await api.getSelectedText();await api.showHUD('Approved input: '+JSON.stringify(text));};")
        File(directory, "error.js").writeText("""
            const React=require('react'),api=require('@raycast/api');
            module.exports.default=function(){const [message,setMessage]=React.useState('Waiting for owned error');
              React.useEffect(()=>{(async()=>{await api.getSelectedText();try{await api.open('file:///owned-denied-outside')}catch(error){}
                try{await api.getSelectedText();setMessage('Unexpected retained input')}catch(error){setMessage('Released input: '+error.message)}})()},[]);
              return React.createElement(api.Detail,{markdown:message});};
        """.trimIndent())
        extension = InstalledExtension(StoreExtension(id, "owned-reviewed-input", "Owned reviewed input", "", "Droidcast tests", "test", "", "", "", 4), directory, manifest)
        record.put("owned_extension_id", id).put("view_probe_sha256", hash(script.toByteArray())).put("font_scale", context.resources.configuration.fontScale)
    }
    private fun receive(text: String = input): TextInputPreview {
        var preview: TextInputPreview? = null
        ui {
            val incoming = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, text as CharSequence).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            it.onNewIntent(incoming)
            assertFalse(incoming.hasExtra(Intent.EXTRA_PROCESS_TEXT))
            assertEquals(Intent.ACTION_MAIN, it.intent.action); assertNull(it.intent.extras); assertNull(it.intent.clipData)
            assertEquals(setOf(Intent.CATEGORY_LAUNCHER), it.intent.categories)
            preview = it.store.extensions.reviewedText.preview
        }
        return requireNotNull(preview)
    }
    private fun approve(preview: TextInputPreview, name: String = "inspect", needsSetup: Boolean = false, show: Boolean = true): ExtensionCatalog.RuntimeText {
        var captured: ExtensionCatalog.RuntimeText? = null
        ui {
            val command = extension.commands.single { c -> c.name == name }
            catalog.reviewedText.choose(preview, TextInputTarget(extension.listing.id, command.name))
            catalog.launchReviewed(extension, command, preview, needsSetup)
            it.store.dialog = if (show) { if (needsSetup) "extension-setup" else "extension-run" } else null
            captured = catalog.captureText(extension, command)
        }
        return requireNotNull(captured)
    }
    private fun rejected(grant: ExtensionCatalog.RuntimeText) {
        try { grant.input!!.read(grant.owner, grant.target); fail("Revoked input remained readable") }
        catch (expected: TextInputRejected) { assertEquals(TextInputProblem.UNAVAILABLE, expected.problem) }
    }
    private fun awaitInput(text: String = input) {
        compose.waitUntil(30000) { catalog.runtimeError != null || activeRoot(catalog.runtimeTree)?.props()?.optString("markdown")?.contains(JSONObject.quote(text)) == true }
        assertNull(catalog.runtimeError, catalog.runtimeError)
        assertEquals("Approved input: ${JSONObject.quote(text)}", activeRoot(catalog.runtimeTree)!!.props().getString("markdown"))
    }
    private fun capture(phase: String) {
        compose.waitForIdle()
        val name = "text-lifecycle-${testName.methodName.lowercase(java.util.Locale.ROOT)}-$phase"
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.dumpWindowHierarchy(File(evidence, "$name.xml")); assertTrue(device.takeScreenshot(File(evidence, "$name.png")))
        File(evidence, "$name-frame.json").writeText(JSONObject().put("scope", record.getString("scope"))
            .put("phase", phase).put("launch_epoch", catalog.launchEpoch).put("route", compose.activity.store.dialog)
            .put("synchronization", "Committed native/Compose state, hierarchy before PNG").toString(2))
    }
    @After fun restore() {
        if (!prepared) return
        try {
            if (!record.optBoolean("passed")) try { capture("failure") } catch (failure: Throwable) { record.put("failure_capture_error_type", failure.javaClass.simpleName) }
        } finally {
            ui {
                directRuntime?.close(); directRuntime = null
                it.store.clearTextReview(); it.store.extensions.close(); it.store.dialog = null
                val manager = it.getSystemService(ClipboardManager::class.java)
                clipboard?.let(manager::setPrimaryClip) ?: manager.resetClipboardForTest()
            }
            restorePalettePreferences(context, originalPalette)
            ownedId?.let { id ->
                listOf(File(context.cacheDir, id), File(context.filesDir, "extension-data/$id")).forEach { it.deleteRecursively(); assertFalse(it.exists()) }
                assertTrue(context.deleteSharedPreferences("extension-$id"))
            }
            assertEquals(fingerprints, preserved.map(::fingerprint))
            record.put("owned_data_removed", true).put("preexisting_installations_and_data_preserved", true)
            File(evidence, "text-lifecycle-${testName.methodName.lowercase(java.util.Locale.ROOT)}-record.json").writeText(record.toString(2))
        }
    }

    @Test fun pendingRecreationExpiresWithoutRunning() {
        receive(); compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("text-input-error").assertTextEquals(TextInputProblem.EXPIRED.message)
        assertNull(catalog.reviewedText.preview); assertNull(catalog.running)
        assertEquals("reviewed-lifecycle-caller", compose.activity.store.query)
        capture("expired"); record.put("recreation_did_not_replay_pending_input", true).put("passed", true)
    }
    @Test fun approvedRecreationRevokesInputWithoutRerunning() {
        val grant = approve(receive()); awaitInput(); capture("approved")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("text-input-error").assertTextEquals(TextInputProblem.EXPIRED.message)
        rejected(grant); assertNull(catalog.running); assertNull(catalog.reviewedText.preview)
        capture("expired"); record.put("recreation_did_not_replay_approved_input", true).put("passed", true)
    }
    @Test fun queuedPreviewActionsCannotChangeANewerReview() {
        val old = receive()
        ui {
            val flow = it.store.textReview!!
            val command = extension.commands.first()
            catalog.reviewedText.choose(old, TextInputTarget(extension.listing.id, command.name))
            flow.choice = TextReviewChoice(extension, command)
        }
        compose.waitForIdle()
        val run = compose.onNodeWithTag("text-input-run").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val cancel = compose.onNodeWithTag("text-input-cancel").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val save = compose.onNodeWithTag("text-input-save-clip").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val next = receive("New reviewed input ✓")
        ui { run(); cancel(); save() }
        assertSame(next, catalog.reviewedText.preview); assertEquals("New reviewed input ✓", next.text)
        assertNull(catalog.running); assertEquals("text-input", compose.activity.store.dialog)
        capture("new-review-preserved"); record.put("three_queued_callbacks_blocked", true).put("passed", true)
    }
    @Test fun newAndMalformedIntentsRevokeThePreviousGrant() {
        val first = approve(receive()); awaitInput()
        val next = receive("Replacement input ✓"); rejected(first)
        ui { catalog.reviewedText.release(first.input!!) }
        assertSame(next, catalog.reviewedText.preview)
        val second = approve(next); awaitInput("Replacement input ✓")
        ui { it.onNewIntent(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").putExtra(Intent.EXTRA_PROCESS_TEXT, 42)) }
        rejected(second); assertNull(catalog.reviewedText.preview); assertNull(catalog.running)
        compose.onNodeWithTag("text-input-error").assertTextEquals(TextInputProblem.INVALID_TYPE.message)
        capture("invalid-replacement"); record.put("replacement_and_invalid_replacement_revoked_grants", true).put("passed", true)
    }
    @Test fun resetCloseErrorAndOrdinaryLaunchRevokeInput() {
        for (boundary in listOf("reset", "close", "error", "ordinary-launch")) {
            var grant: ExtensionCatalog.RuntimeText? = null
            val preview = receive()
            ui {
                val command = extension.commands.first()
                catalog.reviewedText.choose(preview, TextInputTarget(extension.listing.id, command.name))
                catalog.launchReviewed(extension, command, preview, false)
                grant = catalog.captureText(extension, command)
                when (boundary) {
                    "reset" -> it.store.reset()
                    "close" -> catalog.close()
                    "error" -> catalog.runtimeError = "Owned fatal error"
                    else -> catalog.launch(extension, extension.commands.last())
                }
                it.store.dialog = null
            }
            rejected(grant!!); assertNull(catalog.reviewedText.preview)
            if (boundary == "ordinary-launch") assertNull(catalog.captureText(extension, catalog.running!!.second).input)
        }
        record.put("four_revocation_boundaries_verified", true).put("passed", true)
    }
    @Test fun requiredConfigurationNeedsAnotherExplicitRun() {
        val preview = receive(); val prepared = approve(preview, "configure", true)
        assertNull(prepared.input); assertSame(preview, catalog.reviewedText.preview)
        assertNull(catalog.runtimeTree); assertFalse(catalog.runtimeReady)
        compose.onNodeWithTag("run-extension-setup").performTouchInput { click(center) }
        compose.onNodeWithText("Complete the required arguments and preferences.").assertIsDisplayed()
        assertSame(preview, catalog.reviewedText.preview); capture("configuration-required")
        compose.onNodeWithTag("preference-label").performTextReplacement("Owned configuration")
        val submit = compose.onNodeWithTag("run-extension-setup").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.onNodeWithTag("run-extension-setup").performTouchInput { click(center) }
        awaitInput(); capture("configured-input")
        val current = receive("Another pending review")
        val saved = context.getSharedPreferences("extension-${extension.listing.id}", Context.MODE_PRIVATE).all.toMap()
        ui { submit() }
        assertSame(current, catalog.reviewedText.preview); assertNull(catalog.running)
        assertEquals(saved, context.getSharedPreferences("extension-${extension.listing.id}", Context.MODE_PRIVATE).all)
        record.put("configuration_does_not_consume_or_execute_until_valid_run", true).put("stale_configuration_submit_blocked", true).put("passed", true)
    }
    @Test fun staleRuntimeCannotPublishOrWriteAfterReplacement() {
        val first = approve(receive(), show = false)
        lateinit var bridge: ExtensionRuntime.Bridge
        val liveFile = File(context.filesDir, "extension-data/${extension.listing.id}/live-control.txt")
        val staleFile = File(context.filesDir, "extension-data/${extension.listing.id}/stale-control.txt")
        fun write(file: File) = JSONArray().put(file.path).put(Base64.encodeToString("owned control".toByteArray(), Base64.NO_WRAP)).toString()
        ui {
            val command = catalog.running!!.second
            directRuntime = ExtensionRuntime(it, catalog, extension, command)
            bridge = directRuntime!!.Bridge()
            assertTrue(JSONObject(bridge.invokeSync("fs", "writeFile", write(liveFile))).getBoolean("ok"))
            bridge.render("""{"type":"Detail","id":"live-control","props":{"markdown":"Owned live control"}}""")
            assertEquals("live-control", catalog.runtimeTree!!.getString("id"))
        }
        assertTrue(liveFile.isFile)
        val second = approve(receive("Second invocation input"), show = false)
        ui {
            assertFalse(JSONObject(bridge.invokeSync("fs", "writeFile", write(staleFile))).getBoolean("ok"))
            bridge.render("""{"type":"Detail","id":"stale-control","props":{}}""")
            bridge.failed("Owned stale failure"); bridge.depth("7")
            directRuntime!!.close(); directRuntime = null
        }
        rejected(first); assertFalse(staleFile.exists()); assertNull(catalog.runtimeTree); assertNull(catalog.runtimeError); assertEquals(1, catalog.navigationDepth)
        assertEquals("Second invocation input", second.input!!.read(second.owner, second.target))
        record.put("live_write_and_publication_controls_succeeded", true).put("stale_write_render_error_depth_and_close_blocked", true).put("passed", true)
    }
    @Test fun noViewCompletionReleasesItsApprovedInput() {
        val grant = approve(receive(), "background")
        compose.waitUntil(30000) { catalog.runtimeError != null || (catalog.runtimeReady && catalog.runtimeStatus.contains(JSONObject.quote(input))) }
        compose.waitForIdle()
        assertNull(catalog.runtimeError, catalog.runtimeError)
        rejected(grant)
        capture("completed"); record.put("no_view_completion_released_input", true).put("passed", true)
    }
    @Test fun anotherActivityStoreRevokesThePreviousOwnerButOldCleanupCannotRevokeItsSuccessor() {
        val first = approve(receive()); awaitInput()
        val holder = androidx.lifecycle.ViewModelStore()
        lateinit var successor: PaletteStore
        lateinit var second: ExtensionCatalog.RuntimeText
        try {
            ui {
                successor = PaletteStore(it.application)
                holder.put("owned-other-activity-store", successor)
                successor.receiveTextReview(TextInputOrigin.SHARE) { TextIntentPayload("Other activity input ✓", false) }
                val preview = successor.extensions.reviewedText.preview!!
                assertTrue(preview.readOnly)
                val command = extension.commands.first()
                successor.extensions.reviewedText.choose(preview, TextInputTarget(extension.listing.id, command.name))
                successor.extensions.launchReviewed(extension, command, preview, false)
                second = successor.extensions.captureText(extension, command)
                // A delayed onDestroy/close from the original Activity is local.
                it.store.clearTextReview(); catalog.close()
            }
            rejected(first); assertNull(catalog.running)
            assertEquals("Other activity input ✓", second.input!!.read(second.owner, second.target))
        } finally { ui { holder.clear() } }
        rejected(second)
        record.put("different_activity_store_revoked_previous_grant", true).put("old_activity_cleanup_preserved_new_grant", true).put("passed", true)
    }
    @Test fun runtimeCancellationRegistrationCannotBeDetachedByAnOldRuntime() {
        var oldStops = 0; var newStops = 0
        val first = approve(receive(), show = false)
        lateinit var oldRegistration: Any
        ui {
            oldRegistration = catalog.attachRuntime(first.owner, extension, catalog.running!!.second) { oldStops++ }!!
        }
        val second = approve(receive("Next invocation"), show = false)
        assertEquals(1, oldStops)
        ui {
            assertNotNull(catalog.attachRuntime(second.owner, extension, catalog.running!!.second) { newStops++ })
            catalog.detachRuntime(oldRegistration)
            catalog.close()
        }
        assertEquals(1, oldStops); assertEquals(1, newStops)
        record.put("old_cancellation_registration_did_not_detach_new_runtime", true).put("passed", true)
    }
    @Test fun rejectedAsyncAndSyncHostCallsReleaseApprovedInput() {
        val first = approve(receive(), "error")
        compose.waitUntil(30000) { catalog.runtimeError != null || activeRoot(catalog.runtimeTree)?.props()?.optString("markdown")?.startsWith("Released input:") == true }
        assertNull(catalog.runtimeError, catalog.runtimeError)
        assertTrue(activeRoot(catalog.runtimeTree)!!.props().getString("markdown").contains(TextInputProblem.UNAVAILABLE.message))
        rejected(first); capture("async-host-error")
        val second = approve(receive("Next owned input"), show = false)
        ui {
            directRuntime = ExtensionRuntime(it, catalog, extension, catalog.running!!.second)
            assertFalse(JSONObject(directRuntime!!.Bridge().invokeSync("fs", "exists", JSONArray().put("/owned-denied-outside").toString())).getBoolean("ok"))
        }
        compose.waitForIdle(); rejected(second)
        record.put("async_and_sync_host_api_rejections_released_input", true).put("passed", true)
    }
    @Test fun historyLaunchExpiresInsteadOfReplayingItsPayload() {
        val grant = approve(receive()); awaitInput()
        ui {
            it.onNewIntent(Intent(Intent.ACTION_SEND).setType("text/plain").addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
                .putExtra(Intent.EXTRA_TEXT, "Owned historical payload"))
        }
        rejected(grant); assertNull(catalog.running); assertNull(catalog.reviewedText.preview)
        compose.onNodeWithTag("text-input-error").assertTextEquals(TextInputProblem.EXPIRED.message)
        assertEquals(TextInputOrigin.SHARE, compose.activity.store.textReview!!.origin)
        capture("history-expired"); record.put("history_intent_did_not_replay_input", true).put("passed", true)
    }
}
