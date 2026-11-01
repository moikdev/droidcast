package dev.tinycast.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
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
    private var fixture: File? = null
    private var support: File? = null
    @Before fun prepare() {
        original = context.getSharedPreferences("palette", Context.MODE_PRIVATE).all.toMap()
        compose.waitUntil(15000) { !compose.activity.store.loading && !compose.activity.store.folderSearch.loading }
        compose.activityRule.scenario.onActivity {
            clipboard = it.getSystemService(ClipboardManager::class.java).primaryClip
            it.store.resetShortcuts(); it.store.reset()
        }
        compose.waitForIdle()
    }
    @After fun restore() {
        compose.activityRule.scenario.onActivity {
            it.store.extensions.close()
            val manager = it.getSystemService(ClipboardManager::class.java)
            clipboard?.let(manager::setPrimaryClip) ?: manager.clearPrimaryClip()
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
        if (android.os.Build.VERSION.SDK_INT >= 34) InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
        device.dumpWindowHierarchy(File(directory,"$name.xml"))
        assertTrue(device.takeScreenshot(File(directory,"$name.png")))
    }
    private fun editable(tag: String, value: String) {
        compose.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)))
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
        compose.onNodeWithTag("inline-back").performClick()
        compose.activityRule.scenario.onActivity { it.execute(Command("new-note","New note","")) }
        editable("editor-title", "")
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
