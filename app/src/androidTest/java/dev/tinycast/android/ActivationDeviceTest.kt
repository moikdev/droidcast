package dev.tinycast.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.style.TextDecoration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.tinycast.android.extensions.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ActivationDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val device get()=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private var original=emptyMap<String,Any?>()
    private var clip:ClipData?=null
    private var fixture:File?=null
    private var support:File?=null
    @Before fun ready() {
        original=context.getSharedPreferences("palette",Context.MODE_PRIVATE).all.toMap()
        compose.waitUntil(15000){!compose.activity.store.loading && !compose.activity.store.folderSearch.loading}
        compose.activityRule.scenario.onActivity {
            val manager=it.getSystemService(ClipboardManager::class.java);clip=manager.primaryClip
            manager.setPrimaryClip(ClipData.newPlainText("Activation fixture","activation-sentinel"))
            it.store.resetShortcuts();it.store.reset()
        }
        dismissClipboardForEvidence(device)
    }
    @After fun restore() {
        compose.activityRule.scenario.onActivity {
            it.store.extensions.close()
            val manager=it.getSystemService(ClipboardManager::class.java)
            clip?.let(manager::setPrimaryClip) ?: manager.resetClipboardForTest()
        }
        restorePalettePreferences(context,original);fixture?.deleteRecursively();support?.deleteRecursively()
    }
    private fun key(activity:MainActivity,code:Int) {
        val time=android.os.SystemClock.uptimeMillis()
        for(action in listOf(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP))
            activity.dispatchKeyEvent(KeyEvent(time,time,action,code,0,0,android.view.KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,android.view.InputDevice.SOURCE_KEYBOARD))
    }
    private fun composing(tag:String,text:String) {
        compose.onNodeWithTag(tag).assertIsFocused()
        compose.activityRule.scenario.onActivity {
            val connection=it.window.decorView.findFocus()!!.onCreateInputConnection(android.view.inputmethod.EditorInfo())!!
            assertTrue(connection.beginBatchEdit());assertTrue(connection.setComposingText(text,1));connection.endBatchEdit()
        }
        compose.waitForIdle()
        val shown=compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText]
        assertEquals(text,shown.text)
        assertTrue("Exercise actual live IME composition",shown.spanStyles.any { it.item.textDecoration?.contains(TextDecoration.Underline)==true })
    }
    private fun copied():String=compose.activity.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString()
    private fun capture(name:String) {
        compose.waitForIdle();dismissClipboardForEvidence(device);device.waitForIdle()
        assertTrue("Native app hierarchy is rendered",device.wait(Until.hasObject(By.pkg(context.packageName)),5000))
        if(!name.endsWith("-failed")) {
            val marker=if(name=="composing-launcher-primary") By.pkg(context.packageName).clazz("android.widget.EditText").text("Audit composed note")
                else By.pkg(context.packageName).text("Copied to clipboard")
            assertTrue("Capture the actual destination/feedback frame",device.wait(Until.hasObject(marker),5000))
        }
        val dir=File(context.filesDir,"readiness-evidence").apply{mkdirs()};testAutomation().clearCacheForTest()
        device.dumpWindowHierarchy(File(dir,"$name.xml"));assertTrue(device.takeScreenshot(File(dir,"$name.png")))
    }
    private fun checkCapture(name:String,block:()->Unit) {
        try{block();capture(name)}catch(failure:Throwable){runCatching{capture("$name-failed")};throw failure}
    }
    private fun extension(type:String) {
        val id="activation-"+UUID.randomUUID();fixture=File(context.cacheDir,id).apply{mkdirs()}
        support=File(context.filesDir,"extension-data/$id");assertFalse(support!!.exists())
        File(fixture,"fixture.js").writeText("""
            const React=require('react');const {List,Grid,Action,ActionPanel,Clipboard}=require('@raycast/api');
            module.exports.default=function(){
              const latest=React.useRef(null),events=React.useRef([]);const [seen,setSeen]=React.useState('pending');
              const View=${type};
              return React.createElement(View,{columns:3,searchBarPlaceholder:'Audit $type callback',
                onSelectionChange:id=>{latest.current=id;events.current.push('selected:'+id);setSeen(id)},
                actions:React.createElement(ActionPanel,null,React.createElement(Action,{title:'Copy selection callback',
                  onAction:()=>Clipboard.copy(latest.current+'|'+events.current.join(',')+'|action:'+latest.current)}))},
                ['Alpha','Bravo'].map(id=>React.createElement(View.Item,{key:id,id,title:'Audit '+id+' · Callback '+seen,content:'🌱'})));
            };
        """.trimIndent())
        val listing=StoreExtension(id,id,"Audit $type activation","","Fixture","fixture","https://example.com","","",1)
        val installed=InstalledExtension(listing,fixture!!,JSONObject("""{"commands":[{"name":"fixture","title":"Audit $type activation","mode":"view"}]}"""))
        compose.activityRule.scenario.onActivity{it.store.openExtension(installed,installed.commands.single())}
        compose.waitUntil(15000){compose.onAllNodes(hasText("Callback Alpha",substring=true)).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("extension-search").assertIsFocused()
    }
    @Test fun explicitLauncherPrimaryCommitsComposedQueryAndActivates() {
        compose.activityRule.scenario.onActivity{
            it.store.save("notes","Audit composed note","Composed activation body 161803")
            it.store.filter("Notes")
        }
        composing("search","Audit composed")
        val chosen=compose.activity.store.results().first();assertEquals("Audit composed note",chosen.title)
        compose.activityRule.scenario.onActivity{key(it,KeyEvent.KEYCODE_ENTER);assertNull("Enter must leave live composition to IME",it.store.dialog)}
        compose.onNodeWithTag("primary-action").performClick()
        checkCapture("composing-launcher-primary") {
            compose.waitUntil(3000){compose.activity.store.dialog=="note"}
            assertEquals(chosen.id,compose.activity.store.editing?.id)
            assertEquals(chosen.title,compose.onNodeWithTag("editor-title").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        }
    }
    @Test fun explicitListPrimaryCommitsComposedQueryAndActivates() {
        extension("List");composing("extension-search","Audit Alpha")
        compose.activityRule.scenario.onActivity{key(it,KeyEvent.KEYCODE_ENTER)}
        assertEquals("Keyboard must leave composition to IME","activation-sentinel",copied())
        compose.onNodeWithTag("extension-primary").performClick()
        checkCapture("composing-list-primary") {
            compose.waitUntil(3000){copied().startsWith("Alpha|")}
            assertEquals("Alpha|selected:Alpha|action:Alpha",copied())
        }
    }
    private fun callbackBeforeAction(type:String,code:Int) {
        extension(type)
        compose.activityRule.scenario.onActivity{key(it,code);key(it,KeyEvent.KEYCODE_ENTER)}
        checkCapture("same-turn-${type.lowercase()}-callback") {
            compose.waitUntil(3000){copied()!="activation-sentinel"}
            assertEquals("New selection callback must run before activation, exactly once per item",
                "Bravo|selected:Alpha,selected:Bravo|action:Bravo",copied())
            if(type=="List") {
                compose.onNodeWithTag("extension-row-1").assertIsSelected()
                compose.onNodeWithTag("extension-row-0").assertIsNotSelected()
            } else compose.onNodeWithTag("extension-grid-cell-1").assertIsSelected()
        }
    }
    @Test fun listSelectionCallbackPrecedesSameTurnActivation()=callbackBeforeAction("List",KeyEvent.KEYCODE_DPAD_DOWN)
    @Test fun gridSelectionCallbackPrecedesSameTurnActivation()=callbackBeforeAction("Grid",KeyEvent.KEYCODE_DPAD_RIGHT)
}
