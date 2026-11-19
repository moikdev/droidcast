package dev.tinycast.android

import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.tinycast.android.extensions.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReadinessRegressionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private var original = emptyMap<String, Any?>()
    private var clipboard: ClipData? = null
    private var hostClipboardRecovery = false
    private var fixture: File? = null
    private var support: File? = null
    @Before fun prepare() {
        hostClipboardRecovery = hostRestoresTestClipboard()
        original = context.getSharedPreferences("palette", Context.MODE_PRIVATE).all.toMap()
        compose.waitUntil(15000) { !compose.activity.store.loading && !compose.activity.store.folderSearch.loading }
        compose.activityRule.scenario.onActivity {
            clipboard = it.getSystemService(ClipboardManager::class.java).primaryClip
            if (hostClipboardRecovery) it.getSystemService(ClipboardManager::class.java).resetClipboardForTest()
            it.store.resetShortcuts(); it.store.reset()
        }
        if (hostClipboardRecovery) dismissClipboardForEvidence()
        compose.waitForIdle()
    }
    @After fun restore() {
        val scenario = compose.activityRule.scenario
        if (scenario.state == Lifecycle.State.DESTROYED) {
            assertTrue("The runner owns clipboard recovery after a successful launch", hostClipboardRecovery)
        } else scenario.onActivity {
            it.store.extensions.close()
            val manager = it.getSystemService(ClipboardManager::class.java)
            if (hostClipboardRecovery) manager.resetClipboardForTest()
            else clipboard?.let(manager::setPrimaryClip) ?: manager.resetClipboardForTest()
        }
        restorePalettePreferences(context, original)
        fixture?.deleteRecursively(); support?.deleteRecursively()
    }
    private fun key(activity: MainActivity, code: Int, meta: Int = 0) {
        val time = android.os.SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP))
            activity.dispatchKeyEvent(KeyEvent(time,time,action,code,0,meta,android.view.KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,android.view.InputDevice.SOURCE_KEYBOARD))
    }
    private fun capture(name: String) {
        compose.waitForIdle(); device.waitForIdle()
        val directory = File(context.filesDir,"readiness-evidence").apply { mkdirs() }
        if (android.os.Build.VERSION.SDK_INT >= 34) testAutomation().clearCacheForTest()
        device.dumpWindowHierarchy(File(directory,"$name.xml"))
        assertTrue(device.takeScreenshot(File(directory,"$name.png")))
    }
    private fun editable(tag: String, value: String) {
        compose.onNodeWithTag(tag).assert(editableCharactersExactly(value))
    }
    private fun checkWithCapture(name: String, assertion: () -> Unit) {
        try { assertion(); capture(name) } catch (failure: Throwable) { capture("$name-failed"); throw failure }
    }
    private fun twoNotes(): List<Command> {
        compose.activityRule.scenario.onActivity {
            it.store.save("notes","Audit rapid Alpha","Alpha fixture body")
            it.store.save("notes","Audit rapid Bravo","Bravo fixture body")
            it.store.filter("Notes"); it.store.search("Audit rapid")
        }
        compose.waitForIdle(); compose.onNodeWithTag("search").assertIsFocused()
        val results = compose.activity.store.results(); assertEquals(2,results.size)
        return results
    }
    @Test fun installedCatalogLoadsWithoutBlockingTheUiThread() {
        val holder = androidx.lifecycle.ViewModelStore()
        HeldCatalogFixture(context).use { fixture ->
            try {
                fixture.writeCommand()
                val model = compose.runOnUiThread { fixture.createStore(holder) }
                val entered = fixture.uiAccesses.get() == 0 && fixture.awaitWorker()
                val pending = entered && compose.runOnUiThread { model.loading && model.command(fixture.id) == null }
                fixture.release()
                compose.waitUntil(15000) { !model.loading }
                assertEquals(fixture.title, compose.runOnUiThread { model.command(fixture.id)?.title })
                compose.onNodeWithTag("search").performTextReplacement("= 6 * 7")
                compose.onNodeWithTag("result-result").assertIsDisplayed()
                val observed = JSONObject().put("catalog_files_dir_on_main", fixture.uiAccesses.get())
                    .put("catalog_files_dir_off_main", fixture.workerAccesses.get()).put("ui_responded_while_inventory_pending", pending)
                    .put("installed_command_resolved", true)
                if (hostClipboardRecovery) {
                    capture("catalog-startup")
                    File(context.filesDir, "readiness-evidence/catalog-startup-probe.json").writeText(observed.toString())
                }
                assertEquals("Installed catalog storage accessed from UI thread", 0, fixture.uiAccesses.get())
                assertTrue("Worker accessed the controlled catalog root", fixture.workerAccesses.get() > 0)
                assertTrue("UI remained available before command publication", pending)
            } finally {
                fixture.release()
                compose.runOnUiThread { holder.clear() }
            }
        }
    }

    @Test fun rapidLauncherDownThenEnterUsesTheCurrentSelection() {
        val rows=twoNotes()
        compose.activityRule.scenario.onActivity { key(it,KeyEvent.KEYCODE_DPAD_DOWN); key(it,KeyEvent.KEYCODE_ENTER) }
        checkWithCapture("rapid-launcher-enter") {
            assertEquals("Enter must use the logical selection before recomposition",rows[1].id,compose.activity.store.editing?.id)
            editable("editor-title",rows[1].title)
        }
    }
    @Test fun rapidLauncherDownThenActionsUsesTheCurrentSelection() {
        val rows=twoNotes()
        compose.activityRule.scenario.onActivity { key(it,KeyEvent.KEYCODE_DPAD_DOWN); key(it,KeyEvent.KEYCODE_K,KeyEvent.META_CTRL_ON) }
        checkWithCapture("rapid-launcher-actions") {
            assertEquals("Actions must belong to the new logical selection",rows[1].id,compose.activity.store.actionCommand?.id)
            compose.onNodeWithTag("inline-menu").assertIsDisplayed()
        }
    }
    @Test fun rapidLauncherDownThenPinUsesTheCurrentSelection() {
        val rows=twoNotes()
        compose.activityRule.scenario.onActivity { key(it,KeyEvent.KEYCODE_DPAD_DOWN); key(it,KeyEvent.KEYCODE_D,KeyEvent.META_CTRL_ON) }
        checkWithCapture("rapid-launcher-pin") {
            assertTrue("Pin must change the new logical selection", rows[1].id in compose.activity.store.pins)
            assertFalse("The previous selection must stay unpinned", rows[0].id in compose.activity.store.pins)
        }
    }
    @Test fun unsavedNewNoteSurvivesActivityRecreation() {
        compose.activityRule.scenario.onActivity { it.execute(Command("new-note","New note","")) }
        compose.onNodeWithTag("editor-title").performTextReplacement("Audit unsaved note")
        compose.onNodeWithTag("editor-body").performTextReplacement("Unsaved draft body 314159")
        compose.activityRule.scenario.recreate()
        checkWithCapture("new-note-recreated") {
            editable("editor-title","Audit unsaved note")
            editable("editor-body","Unsaved draft body 314159")
            assertFalse(compose.activity.store.notes.any { it.title=="Audit unsaved note" })
        }
        compose.activityRule.scenario.onActivity { key(it,KeyEvent.KEYCODE_S,KeyEvent.META_CTRL_ON) }
        compose.waitUntil(5000) { compose.activity.store.dialog==null }
        val saved=PaletteStore(context.applicationContext as android.app.Application).notes.single { it.title=="Audit unsaved note" }
        assertEquals("Unsaved draft body 314159",saved.body)
    }
    private fun openArgumentPrompt(): String {
        val id = compose.runOnUiThread {
            compose.activity.store.save("links", "Garden journal", "https://example.invalid/lookup?q={query}")
        }
        compose.onNodeWithTag("search").performTextReplacement("Garden journal")
        compose.onNodeWithTag("result-$id").performClick()
        return id
    }

    @Test fun quicklinkArgumentSurvivesRecreationAndCancelClearsIt() {
        val id = openArgumentPrompt()
        val field = compose.onNodeWithTag("link-query-field")
        val argument = "Café & 日本"
        field.performTextReplacement(argument)
        compose.activityRule.scenario.recreate()
        capture("quicklink-argument-recreated")
        editable("link-query-field", argument)
        compose.onNodeWithTag("inline-back").performClick()
        compose.onNodeWithTag("result-$id").performClick()
        editable("link-query-field", "")
        assertEquals(0, compose.activity.store.usage[id] ?: 0)
    }

    @Test fun rejectedQuicklinkKeepsArgumentForAnEncodedRetry() {
        Assume.assumeTrue("Runner restores the clipboard after the palette finishes", hostClipboardRecovery)
        val id = openArgumentPrompt()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val requests = mutableListOf<String>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_VIEW || intent.data?.host != "example.invalid") return null
                requests.add(requireNotNull(intent.dataString))
                when (requests.size) {
                    1 -> throw ActivityNotFoundException("Fixture has no handler")
                    2 -> throw SecurityException("Fixture denied this attempt")
                }
                return Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            val field = compose.onNodeWithTag("link-query-field")
            val open = compose.onNodeWithText("Open quicklink")
            field.performTextReplacement("A & B")
            open.performClick()
            compose.onNodeWithText("No available app can open this item").assertIsDisplayed()
            capture("quicklink-argument-missing")
            editable("link-query-field", "A & B")
            assertEquals(0, compose.activity.store.usage[id] ?: 0)
            field.performTextReplacement("A/B")
            open.performClick()
            editable("link-query-field", "A/B")
            assertEquals(0, compose.activity.store.usage[id] ?: 0)
            field.performTextReplacement("Café #2")
            val store = compose.activity.store
            open.performClick()
            compose.waitUntil(5000) { compose.activityRule.scenario.state == Lifecycle.State.DESTROYED }
            assertEquals(listOf("https://example.invalid/lookup?q=A%20%26%20B",
                "https://example.invalid/lookup?q=A%2FB", "https://example.invalid/lookup?q=Caf%C3%A9%20%232"), requests)
            assertEquals(1, store.usage[id])
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun editLink(id: String, title: String) {
        compose.onNodeWithTag("search").performTextReplacement(title)
        compose.onNodeWithTag("result-$id").performTouchInput { longClick() }
        compose.onNodeWithTag("action-Edit quicklink").performScrollTo().performClick()
        compose.onNodeWithTag("editor-alias").performScrollTo()
    }

    @Test fun quicklinkEditorKeepsRenamedSettingsAlias() = preserveSettingsAlias("garden")
    @Test fun quicklinkEditorKeepsClearedSettingsAlias() = preserveSettingsAlias("")

    private fun preserveSettingsAlias(override: String) {
        val store = compose.activity.store
        val title = "Garden reference"
        val url = "https://example.invalid/find?q={query}"
        val id = compose.runOnUiThread {
            store.save("links", title, url, "journal").also { store.setAlias(it, override) }
        }
        editLink(id, title)
        val aliasNode = compose.onNodeWithTag("editor-alias")
        val opened = aliasNode.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        val phase = if (override.isEmpty()) "cleared" else "renamed"
        // Wait for the focus-triggered IME transition, then expose the full alias for capture.
        aliasNode.performClick()
        val activity = compose.activity
        val view = activity.window.decorView
        val ime = WindowInsetsCompat.Type.ime()
        val controller = WindowCompat.getInsetsController(activity.window, view)
        compose.runOnUiThread { controller.show(ime) }
        compose.waitUntil(5000) { compose.runOnUiThread { ViewCompat.getRootWindowInsets(view)?.isVisible(ime) == true } }
        compose.runOnUiThread { controller.hide(ime) }
        compose.waitUntil(5000) { compose.runOnUiThread { ViewCompat.getRootWindowInsets(view)?.isVisible(ime) == false } }
        aliasNode.performScrollTo().assertIsDisplayed()
        capture("alias-$phase-open")
        val newTitle = "Garden reference revised"
        compose.onNodeWithTag("editor-title").performScrollTo().performTextReplacement(newTitle)
        compose.onNodeWithTag("save-editor").performClick()
        compose.waitUntil(5000) { store.dialog == null }
        compose.runOnIdle {
            assertEquals("Title was saved", newTitle, store.links.single { it.id == id }.title)
            assertEquals("Title-only save must keep the effective alias", override, store.command(id)?.alias)
            assertEquals(override, opened)
            assertEquals("journal", store.backupSnapshot().links.single { it.id == id }.alias)
            assertEquals(override, store.backupSnapshot().aliases[id])
            store.search("journal spring")
            assertFalse(store.results().any { it.id == id && it.payload.endsWith("q=spring") })
            if (override.isNotEmpty()) {
                store.search("$override spring")
                assertEquals(url.replace("{query}", "spring"), store.results().first().payload)
            }
        }
    }

    @Test fun quicklinkEditorKeepsPeerAliasAndValidatesDeliberateChanges() {
        val store = compose.activity.store
        val app = context.applicationContext as android.app.Application
        val holder = androidx.lifecycle.ViewModelStore()
        val factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(app)
        fun peer() = androidx.lifecycle.ViewModelProvider(holder, factory)[PaletteStore::class.java]
        val title = "Garden reference"
        val url = "https://example.invalid/find?q={query}"
        val revised = "https://example.invalid/articles?q={query}"
        val id = compose.runOnUiThread {
            store.save("links", title, url, "journal").also { store.setAlias(it, "garden") }
        }
        try {
            editLink(id, title)
            compose.runOnIdle { peer().setAlias(id, "flowers") }
            compose.onNodeWithTag("editor-body").performScrollTo().performTextReplacement(revised)
            compose.onNodeWithTag("save-editor").performClick()
            compose.waitUntil(5000) { store.dialog == null }
            compose.runOnIdle {
                assertEquals(revised, peer().links.single { it.id == id }.body)
                assertEquals("flowers", peer().aliases[id])
                assertEquals("journal", peer().links.single { it.id == id }.alias)
            }
            editLink(id, title)
            val field = compose.onNodeWithTag("editor-alias")
            editable("editor-alias", "flowers")
            field.performTextReplacement("calc")
            compose.onNodeWithTag("save-editor").performClick()
            compose.onNodeWithText("That alias is already in use").assertExists()
            compose.runOnIdle { assertEquals("flowers", peer().command(id)?.alias) }
            field.performScrollTo().performTextReplacement("pets")
            compose.runOnIdle { peer().setAlias(id, "plants") }
            compose.onNodeWithTag("save-editor").performClick()
            compose.onNodeWithText("The alias changed in Settings. Reopen the quicklink to edit it.").assertExists()
            compose.runOnIdle { assertEquals("plants", peer().command(id)?.alias) }
            compose.onNodeWithTag("cancel-editor").performClick()
            editLink(id, title)
            field.performTextReplacement("pets")
            compose.onNodeWithTag("save-editor").performClick()
            compose.waitUntil(5000) { store.dialog == null }
            compose.runOnIdle {
                holder.clear()
                val reopened = peer()
                assertEquals("pets", reopened.links.single { it.id == id }.alias)
                assertFalse(reopened.aliases.containsKey(id))
                reopened.search("pets spring")
                assertEquals(revised.replace("{query}", "spring"), reopened.results().first().payload)
            }
        } finally {
            compose.runOnUiThread { holder.clear() }
        }
    }

    @Test fun unsavedQuicklinkFieldsSurviveActivityRecreation() {
        var id=""
        compose.activityRule.scenario.onActivity {
            id=it.store.save("links","Audit original link","https://example.com/original", "audit"+UUID.randomUUID().toString().take(6))
        }
        // Enter through the same inline editor route used by Actions, without launching a browser.
        compose.activityRule.scenario.onActivity { it.store.editing=it.store.links.first { link->link.id==id }; it.store.dialog="link" }
        compose.onNodeWithTag("editor-title").performTextReplacement("Audit edited link draft")
        compose.onNodeWithTag("editor-body").performTextReplacement("https://example.com/draft?q={query}")
        compose.onNodeWithTag("editor-alias").performTextReplacement("auditdraft")
        compose.activityRule.scenario.recreate()
        checkWithCapture("quicklink-recreated") {
            editable("editor-title","Audit edited link draft")
            editable("editor-body","https://example.com/draft?q={query}")
            editable("editor-alias","auditdraft")
            assertEquals("Audit original link",compose.activity.store.links.first { it.id==id }.title)
        }
        compose.activityRule.scenario.onActivity { key(it,KeyEvent.KEYCODE_S,KeyEvent.META_CTRL_ON) }
        compose.waitUntil(5000) { compose.activity.store.dialog==null }
        val saved=PaletteStore(context.applicationContext as android.app.Application).links.single { it.id==id }
        assertEquals("Audit edited link draft",saved.title); assertEquals("https://example.com/draft?q={query}",saved.body)
        assertEquals("auditdraft",saved.alias)
    }
    @Test fun largeUnsavedDraftSurvivesRecreationWithoutBundleSerialization() {
        val text = "Large draft line 314159.\n".repeat(25_000)
        compose.activityRule.scenario.onActivity {
            it.execute(Command("new-note","New note",""))
            it.store.editorDraft("note").apply { title="Audit large draft"; body=text }
        }
        compose.waitForIdle()
        compose.activityRule.scenario.recreate()
        editable("editor-title", "Audit large draft")
        val shown=compose.onNodeWithTag("editor-body").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals(text.length,shown.length); assertTrue("Exact large draft text", text==shown)
        assertFalse(compose.activity.store.notes.any { it.title=="Audit large draft" })
        capture("large-draft-recreated")
    }
    @Test fun cancellingADraftDoesNotRestoreItInANewEditor() {
        compose.activityRule.scenario.onActivity { it.execute(Command("new-note","New note","")) }
        compose.onNodeWithTag("editor-title").performTextReplacement("Discard this audit draft")
        val back=compose.onNodeWithTag("inline-back").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        val abandoned=compose.activity.store.editorDraft("note")
        compose.activityRule.scenario.onActivity {
            assertTrue(back())
            it.execute(Command("new-note","New note",""))
            assertNotSame("Same-turn reopening owns a fresh draft",abandoned,it.store.editorDraft("note"))
        }
        editable("editor-title", "")
        editable("editor-body", "")
        assertFalse(compose.activity.store.notes.any { it.title=="Discard this audit draft" })
    }
    @Test fun rapidExtensionListDownThenEnterRunsTheCurrentAction() {
        val id="audit-list-"+UUID.randomUUID()
        fixture=File(context.cacheDir,id).apply { mkdirs() }
        support=File(context.filesDir,"extension-data/$id"); assertFalse(support!!.exists())
        File(fixture,"fixture.js").writeText("""
            const React = require('react');
            const { List, Action, ActionPanel, Clipboard } = require('@raycast/api');
            module.exports.default = function() { return React.createElement(List, {},
              ['Alpha','Bravo'].map(value => React.createElement(List.Item, {key:value,id:value,title:'Audit '+value,
                actions:React.createElement(ActionPanel, {}, React.createElement(Action, {title:'Copy '+value,onAction:()=>Clipboard.copy('Audit '+value)}))}))); };
        """.trimIndent())
        val listing=StoreExtension(id,id,"Audit List","","Fixture","fixture","https://example.com","","",1)
        val ext=InstalledExtension(listing,fixture!!,JSONObject("""{"commands":[{"name":"fixture","title":"Audit List","mode":"view"}]}"""))
        compose.activityRule.scenario.onActivity { it.store.openExtension(ext,ext.commands.single()) }
        compose.waitUntil(15000) { compose.onAllNodesWithTag("extension-row-1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("extension-search").assertIsFocused()
        compose.activityRule.scenario.onActivity { key(it,KeyEvent.KEYCODE_DPAD_DOWN); key(it,KeyEvent.KEYCODE_ENTER) }
        compose.waitUntil(10000) { compose.activity.store.extensions.runtimeStatus.contains("Copied") }
        checkWithCapture("rapid-extension-list") {
            compose.activityRule.scenario.onActivity {
                assertEquals("List activation must use the newly selected item's action","Audit Bravo",it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString())
            }
        }
    }
}
