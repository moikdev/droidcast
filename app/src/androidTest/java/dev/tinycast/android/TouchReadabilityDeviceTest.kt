package dev.tinycast.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.tinycast.android.extensions.InstalledExtension
import dev.tinycast.android.extensions.StoreExtension
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** The runner controls font scale and viewport, then restores the original settings. */
@RunWith(AndroidJUnit4::class)
class TouchReadabilityDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val device get()=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private var palette=emptyMap<String,Any?>()
    private var emoji=emptyMap<String,Any?>()
    private var clip:ClipData?=null
    private var fixture:File?=null
    private var fixtureId:String?=null
    private val geometry=JSONObject()
    private val actionTitle="Copy the selected audit value"
    private val noteTitle="Touch audit note with a long, recognizable title"
    private var noteId=""

    @Before fun prepare() {
        assertEquals("Tinycast_Tablet_API_36",InstrumentationRegistry.getArguments().getString("dedicatedAvd"))
        palette=context.getSharedPreferences("palette",Context.MODE_PRIVATE).all.toMap()
        emoji=context.getSharedPreferences("emoji",Context.MODE_PRIVATE).all.toMap()
        compose.waitUntil(15000){!compose.activity.store.loading && !compose.activity.store.emoji.loading && !compose.activity.store.folderSearch.loading}
        compose.activityRule.scenario.onActivity {
            clip=it.getSystemService(ClipboardManager::class.java).primaryClip
            it.store.resetShortcuts();it.store.reset()
            noteId=it.store.save("notes",noteTitle,"Touch audit body 271828")
            it.store.setAlias(noteId,"touchauditlongalias")
            it.store.setShortcut(noteId,KeyChord(KeyEvent.KEYCODE_PAGE_DOWN,ShortcutRules.CTRL or ShortcutRules.ALT or ShortcutRules.SHIFT))
        }
        compose.waitForIdle()
    }
    @After fun restore() {
        compose.activityRule.scenario.onActivity {
            it.store.extensions.close()
            val manager=it.getSystemService(ClipboardManager::class.java)
            clip?.let(manager::setPrimaryClip) ?: manager.resetClipboardForTest()
        }
        restorePalettePreferences(context,palette)
        val edit=context.getSharedPreferences("emoji",Context.MODE_PRIVATE).edit().clear()
        emoji.forEach { (key,value) -> when(value) {
            is String -> edit.putString(key,value)
            is Set<*> -> edit.putStringSet(key,value.filterIsInstance<String>().toSet())
            is Boolean -> edit.putBoolean(key,value)
            is Int -> edit.putInt(key,value)
            is Long -> edit.putLong(key,value)
            is Float -> edit.putFloat(key,value)
        } }
        assertTrue(edit.commit())
        fixture?.let { it.deleteRecursively();assertFalse(it.exists()) }
        fixtureId?.let {
            val support=File(context.filesDir,"extension-data/$it");support.deleteRecursively();assertFalse(support.exists())
            context.deleteSharedPreferences("extension-$it")
        }
    }
    private fun search(text:String) { compose.onNodeWithTag("search").performTextReplacement(text);compose.waitForIdle() }
    private fun touch(tag:String) { compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performTouchInput { click(center) };compose.waitForIdle() }
    private fun target(tag:String):Rect {
        val node=compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
        val bounds=node.boundsInWindow
        val minimum=48*context.resources.displayMetrics.density-1
        assertTrue("$tag has a 48dp visible width: $bounds",bounds.width>=minimum)
        assertTrue("$tag has a 48dp visible height: $bounds",bounds.height>=minimum)
        geometry.put(tag,JSONArray(listOf(bounds.left,bounds.top,bounds.right,bounds.bottom)))
        return bounds
    }
    private fun inputFits(tag:String) {
        val results=mutableListOf<TextLayoutResult>()
        val field=compose.onNodeWithTag(tag).assertIsDisplayed()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        val layout=results.single()
        val bounds=field.fetchSemanticsNode().boundsInWindow
        val lineHeight=layout.getLineBottom(0)-layout.getLineTop(0)
        assertTrue("$tag must not clip the actual text line ($lineHeight > ${bounds.height})",lineHeight<=bounds.height+1)
    }
    private fun fullLabel(parent:String,label:String) {
        val results=mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(parent)),useUnmergedTree=true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        assertFalse("The visible label must fit: $label",results.single().hasVisualOverflow)
    }
    private fun capsule(primary:String,actions:String) {
        val first=target(primary);val second=target(actions)
        assertFalse("Primary and Actions touch targets must not overlap",first.overlaps(second))
    }
    private fun capture(name:String) {
        compose.waitForIdle();dismissClipboardForEvidence();device.waitForIdle()
        val out=File(context.filesDir,"readiness-evidence").apply{mkdirs()}
        testAutomation().clearCacheForTest();device.dumpWindowHierarchy(File(out,"touch-$name.xml"))
        assertTrue(device.takeScreenshot(File(out,"touch-$name.png")))
        val installed=context.packageManager.getApplicationInfo(context.packageName,android.content.pm.PackageManager.GET_META_DATA)
        File(out,"touch-$name.json").writeText(JSONObject().put("installed_build_commit",installed.metaData.getString("dev.droidcast.BUILD_COMMIT"))
            .put("font_scale",context.resources.configuration.fontScale)
            .put("width_dp",context.resources.configuration.screenWidthDp).put("height_dp",context.resources.configuration.screenHeightDp)
            .put("geometry",geometry).put("input","Compose pointer injection and text semantics; no hardware-key navigation").toString(2))
    }
    private fun copied():String=compose.activity.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString()

    @Test fun launcherAndEditorRemainReadableThroughTouchActions() {
        search("Touch audit")
        target("palette-menu");target("result-$noteId");inputFits("search")
        capsule("primary-action","actions-button");fullLabel("primary-action","Open Note")
        assertTrue(compose.onNodeWithTag("result-$noteId").fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
            .joinToString().contains("touchauditlongalias"))
        capture("launcher")
        touch("actions-button")
        compose.onNodeWithTag("inline-menu-choices").performScrollToNode(hasTestTag("action-Set keyboard shortcut"))
        target("action-Set keyboard shortcut");fullLabel("action-Set keyboard shortcut","Set keyboard shortcut")
        capture("launcher-actions")
        compose.onNodeWithTag("inline-menu-choices").performScrollToNode(hasTestTag("action-Open Note"))
        touch("action-Open Note")
        inputFits("editor-title");target("inline-back");target("save-editor")
        compose.onNodeWithTag("editor-title").performTextReplacement("Touch audit saved note")
        capture("editor")
        touch("save-editor")
        compose.waitUntil(5000){compose.activity.store.notes.any { it.id==noteId && it.title=="Touch audit saved note" }}
        compose.onNodeWithTag("search").assertIsDisplayed()
    }
    @Test fun categoryAndEmptyResultsKeepTouchNavigationAvailable() {
        touch("palette-menu")
        compose.onNodeWithTag("inline-menu-choices").performScrollToNode(hasTestTag("filter-Notes"))
        target("filter-Notes");touch("filter-Notes")
        assertEquals("Notes",compose.activity.store.category)
        search("no-touch-audit-result-314159")
        compose.onNodeWithText("No matching commands").assertIsDisplayed()
        target("palette-menu");capture("launcher-empty")
        touch("palette-menu")
        compose.onNodeWithTag("inline-menu-choices").performScrollToNode(hasTestTag("filter-All"))
        touch("filter-All");assertEquals("All",compose.activity.store.category)
    }
    @Test fun calculatorAnswerFitsAndCopiesWithoutHardwareKeys() {
        search("10 km to mi");inputFits("search")
        val calculation=compose.activity.store.results().first { it.kind==Kind.CALC }
        val answer=calculation.title
        val results=mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(answer,useUnmergedTree=true).performScrollTo()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        assertFalse("The conversion answer must remain readable",results.single().hasVisualOverflow)
        capsule("primary-action","actions-button");capture("calculator")
        touch("primary-action");compose.waitUntil(5000){copied()==calculation.payload}
    }
    @Test fun emojiCopyCategoriesAndEmptyStateRemainUsableByTouch() {
        search("emoji");touch("result-emoji")
        compose.onNodeWithTag("emoji-search").performTextReplacement("family")
        inputFits("emoji-search");target("emoji-back");target("emoji-filter");target("emoji-favorite")
        capsule("emoji-copy","emoji-actions");capture("emoji")
        val expected=compose.activity.store.emoji.let { it.results()[it.selected].glyph }
        touch("emoji-actions");fullLabel("emoji-action-copy","Copy to Clipboard")
        touch("emoji-action-copy");compose.waitUntil(5000){copied()==expected}
        touch("emoji-filter")
        compose.onNodeWithTag("inline-menu-choices").performScrollToNode(hasTestTag("emoji-filter-Text symbols"))
        target("emoji-filter-Text symbols");touch("emoji-filter-Text symbols")
        compose.onNodeWithTag("emoji-search").performTextReplacement("no-touch-audit-symbol-314159")
        compose.onNodeWithTag("emoji-copy").assertIsNotEnabled()
        compose.onNodeWithTag("emoji-favorite").assertIsNotEnabled()
        target("emoji-copy");target("emoji-actions");capture("emoji-empty")
        touch("emoji-back");compose.onNodeWithTag("search").assertIsDisplayed()
    }
    private fun extension(type:String) {
        val id="touch-layout-"+UUID.randomUUID()
        val directory=File(context.cacheDir,id);assertFalse(directory.exists())
        assertFalse(File(context.filesDir,"extension-data/$id").exists())
        assertFalse(File(context.applicationInfo.dataDir,"shared_prefs/extension-$id.xml").exists())
        assertTrue(directory.mkdirs());fixture=directory;fixtureId=id
        File(directory,"fixture.js").writeText("""
            const React=require('react');const {List,Grid,Action,ActionPanel,Clipboard}=require('@raycast/api');
            const View=$type;
            module.exports.default=()=>React.createElement(View,{columns:3,searchBarPlaceholder:'Find an audit item',
              actions:React.createElement(ActionPanel,null,React.createElement(Action,{title:${JSONObject.quote(actionTitle)},onAction:()=>Clipboard.copy('touch-layout-audit-271828')}))},
              ['Alpha','Bravo'].map(id=>React.createElement(View.Item,{key:id,id,title:'Audit '+id+' with a long descriptive title',subtitle:'A useful descriptive subtitle',content:'🌱'})));
        """.trimIndent())
        val listing=StoreExtension(id,id,"Touch layout extension","","Fixture","fixture","https://example.com","","",1)
        val installed=InstalledExtension(listing,directory,JSONObject("""{"commands":[{"name":"fixture","title":"Touch layout command","mode":"view"}]}"""))
        compose.activityRule.scenario.onActivity { it.store.openExtension(installed,installed.commands.single()) }
        compose.waitUntil(15000){compose.onAllNodesWithTag(if(type=="Grid")"extension-grid"else"extension-list").fetchSemanticsNodes().isNotEmpty()}
    }
    private fun extensionFlow(type:String) {
        extension(type)
        compose.onNodeWithTag("extension-search").performTextReplacement("Audit Alpha")
        inputFits("extension-search");target("extension-back")
        capsule("extension-primary","extension-actions");capture("extension-${type.lowercase()}")
        touch("extension-actions");fullLabel("extension-action-0",actionTitle);target("extension-action-0")
        capture("extension-${type.lowercase()}-actions")
        touch("extension-action-0");compose.waitUntil(5000){copied()=="touch-layout-audit-271828"}
        touch("extension-back");compose.onNodeWithTag("search").assertIsDisplayed()
    }
    @Test fun nativeListPreservesReadableActionsAndTouchReturn()=extensionFlow("List")
    @Test fun nativeGridPreservesReadableActionsAndTouchReturn()=extensionFlow("Grid")
}
