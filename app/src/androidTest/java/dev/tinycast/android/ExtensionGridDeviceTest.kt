package dev.tinycast.android

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextRange
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.tinycast.android.extensions.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking

internal fun gridFixture(context: Context, mode: String = "normal"): InstalledExtension {
    require(mode in listOf("normal", "loading", "form", "controlled", "deferred", "untitled", "late-grid", "late-list"))
    val directory = File(context.cacheDir, "extension-grid-fixture").apply { mkdirs() }
    val assets = File(directory, "assets").apply { mkdirs() }
    val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap); canvas.drawColor(Color.rgb(30, 80, 150))
    canvas.drawCircle(235f, 52f, 30f, Paint().apply { color = Color.rgb(255, 210, 70) })
    canvas.drawRect(0f, 130f, 320f, 180f, Paint().apply { color = Color.rgb(30, 140, 95) })
    File(assets, "landscape.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    File(assets, "landscape.png").copyTo(File(context.filesDir, "grid-private-fixture.png"), overwrite = true)
    File(assets, "unsupported.svg").writeText("<svg xmlns='http://www.w3.org/2000/svg'><circle r='8'/></svg>")
    File(directory, "grid.js").writeText("""
        const React=require('react'); const {Grid,Form,Action,ActionPanel,Detail,Clipboard}=require('@raycast/api');
        module.exports.default=function(){
          if(${mode == "form"})return React.createElement(Form,{actions:React.createElement(ActionPanel,null,React.createElement(Action.SubmitForm,{title:'Submit fixture',onSubmit:v=>Clipboard.copy('form:'+v.note)}))},React.createElement(Form.TextField,{id:'note',title:'Fixture note'}));
          if(${mode == "untitled"}){
            const cell=(id,symbol)=>React.createElement(Grid.Item,{key:id,id,content:symbol,
              actions:React.createElement(ActionPanel,null,React.createElement(Action,{title:'Copy identity',onAction:()=>Clipboard.copy('untitled:'+id)}))});
            return React.createElement(Grid,{columns:3,aspectRatio:'16/9',searchBarPlaceholder:'Untitled section fixture'},
              React.createElement(Grid.Section,{key:'one'},cell('u0','🌱')),
              React.createElement(Grid.Section,{key:'two'},cell('u1','🌿'),cell('u2','🍀')));
          }
          if(${mode.startsWith("late-")}){
            const type=${JSONObject.quote(if(mode == "late-list") "List" else "Grid")}; const View=require('@raycast/api')[type];
            return React.createElement(View,{columns:3,searchBarPlaceholder:'Late '+type+' fixture'},React.createElement(View.Item,{id:'loaded',title:'Loaded '+type+' view',content:'🌱',
              actions:React.createElement(ActionPanel,null,React.createElement(Action,{title:'Fail after render',onAction:()=>globalThis.__tinycastHost.failed('fixture','Public late '+type+' failure. Close this command and try again.')}))}));
          }
          const [query,setQuery]=React.useState(''); const [selected,setSelected]=React.useState('');
          const [reverse,setReverse]=React.useState(false); const [count,setCount]=React.useState(${if(mode == "deferred") 4 else 30});
          const [requested,setRequested]=React.useState('${if(mode == "deferred") "image-6" else "image-12"}');
          const [category,setCategory]=React.useState('all'); const activations=React.useRef(0);
          let data=Array.from({length:count},(_,i)=>({id:'image-'+i,title:'Landscape '+String(i).padStart(2,'0'),
            content:i===1?'https://raw.githubusercontent.com/moikdev/droidcast/d6fccdc4989d4d6e2203f8fa331872a9c11d7105/docs/screenshots/parity-modifier-taps/tablet-record-double-ctrl.png':
             i===2?${JSONObject.quote(File(context.filesDir, "grid-private-fixture.png").path)}:i===3?'missing.png':i===4?'unsupported.svg':
             i===5?{color:{light:'#33bb88',dark:'#33bb88'}}:i===6?'🌻':i===7?{source:'landscape.png',mask:'circle'}:'landscape.png'}));
          if(reverse)data.reverse(); if(category==='even')data=data.filter(x=>Number(x.id.split('-')[1])%2===0);
          function cell(x){return React.createElement(Grid.Item,{key:x.id,id:x.id,title:x.title,subtitle:'Preview '+x.id,keywords:['fixture',x.id],content:x.content,
            actions:React.createElement(ActionPanel,null,
              React.createElement(Action,{title:'Copy identity',onAction:()=>{activations.current++;Clipboard.copy('grid-fixture:'+x.id)}}),
              React.createElement(Action.Push,{title:'Inspect item',target:React.createElement(Detail,{markdown:'Grid fixture detail '+x.id})}),
              React.createElement(Action,{title:'Reverse items',onAction:()=>setReverse(v=>!v)}),
              React.createElement(Action,{title:'Clear search',onAction:()=>setQuery('')}),
              React.createElement(Action,{title:'Report activation count',onAction:()=>Clipboard.copy('activations:'+activations.current)}),
              ${mode == "controlled"}&&React.createElement(Action,{title:'Select requested item',onAction:()=>setRequested('image-18')}))});}
          return React.createElement(Grid,{columns:3,filtering:true,searchText:query,onSearchTextChange:setQuery,onSelectionChange:setSelected,
            selectedItemId:${if(mode in listOf("controlled","deferred")) "requested" else "undefined"},
            searchBarPlaceholder:'Search grid fixtures',isLoading:${mode == "loading"},
            pagination:{hasMore:count<36,onLoadMore:()=>setCount(n=>n+3),pageSize:3},
            searchBarAccessory:React.createElement(Grid.Dropdown,{id:'category',value:category,onChange:setCategory,tooltip:'Fixture category'},
              React.createElement(Grid.Dropdown.Item,{title:'All cards',value:'all'}),React.createElement(Grid.Dropdown.Item,{title:'Even cards',value:'even'}))},
            ${if (mode == "loading") "null" else "React.createElement(Grid.Section,{title:'Selected: '+selected},data.slice(0,8).map(cell)),React.createElement(Grid.Section,{title:'More previews'},data.slice(8).map(cell))"},
            React.createElement(Grid.EmptyView,{title:'No matching fixture cards',description:'Try Landscape or an image identifier'}));
        };
    """.trimIndent())
    val listing = StoreExtension("grid-renderer-fixture", "grid-fixture", "Grid fixture", "", "Droidcast tests", "test", "https://example.com", "", "", 1)
    return InstalledExtension(listing, directory, JSONObject("""{"commands":[{"name":"grid","title":"Image Grid Fixture","mode":"view"}]}"""))
}

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ExtensionGridDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private var original = emptyMap<String, Any?>()
    private var originalExtension = emptyMap<String, Any?>()
    private var clipboard: android.content.ClipData? = null
    @Before fun setup() {
        device.executeShellCommand("cmd statusbar collapse")
        original = context.getSharedPreferences("palette", Context.MODE_PRIVATE).all.toMap()
        originalExtension = context.getSharedPreferences("extension-grid-renderer-fixture", Context.MODE_PRIVATE).all.toMap()
        compose.activityRule.scenario.onActivity {
            val manager = it.getSystemService(ClipboardManager::class.java)
            clipboard = manager.primaryClip
            manager.clearPrimaryClip() // Never include an existing clipboard suggestion in evidence.
        }
        compose.waitUntil(10000) { !compose.activity.store.loading }
    }
    @After fun restore() {
        compose.activityRule.scenario.onActivity { it.store.extensions.close(); it.store.dialog = null }
        compose.waitForIdle()
        restorePalettePreferences(context, original)
        val prefs = context.getSharedPreferences("extension-grid-renderer-fixture", Context.MODE_PRIVATE)
        val edit = prefs.edit().clear(); originalExtension.forEach { (key, value) -> if (value is String) edit.putString(key, value) }; assertTrue(edit.commit())
        compose.activityRule.scenario.onActivity { val manager = it.getSystemService(ClipboardManager::class.java); clipboard?.let(manager::setPrimaryClip) ?: manager.clearPrimaryClip() }
        File(context.cacheDir, "extension-grid-fixture").deleteRecursively()
        File(context.filesDir, "grid-private-fixture.png").delete()
    }
    private fun launch(mode: String = "normal") {
        val fixture = gridFixture(context, mode)
        compose.activityRule.scenario.onActivity { it.store.openExtension(fixture, fixture.commands.single()) }
        compose.waitUntil(25000) { compose.activity.store.extensions.runtimeTree != null || compose.activity.store.extensions.runtimeError != null }
        assertNull(compose.activity.store.extensions.runtimeError, compose.activity.store.extensions.runtimeError)
        compose.onNodeWithTag(if (mode == "form") "extension-field-note" else "extension-search").assertIsDisplayed()
    }
    private fun key(code: Int, meta: Int = 0, wait: Boolean = true) {
        val time = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) InstrumentationRegistry.getInstrumentation().sendKeySync(KeyEvent(time, time, action, code, 0, meta))
        if (wait) compose.waitForIdle()
    }
    private fun clip(): String { var value = ""; compose.activityRule.scenario.onActivity { value = it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString() }; return value }
    private fun capture(name: String, marker: String) {
        try { captureChecked(name, marker) } catch (error: Throwable) {
            val folder = File(context.filesDir, "extension-grid-evidence").apply { mkdirs() }
            device.dumpWindowHierarchy(File(folder, "capture-failure-$name.xml")); device.takeScreenshot(File(folder, "capture-failure-$name.png"))
            File(folder, "capture-failure-$name.json").writeText(JSONObject().put("grid_count", activeRoot(compose.activity.store.extensions.runtimeTree)?.nodes("Grid.Item")?.size).put("compose", runCatching { compose.onNodeWithTag("extension-grid-screen").printToString() }.getOrDefault("No Grid semantics")).toString(2))
            throw error
        }
    }
    private fun captureChecked(name: String, marker: String) {
        compose.waitForIdle(); device.waitForIdle()
        val captureRoute = compose.activity.store.dialog
        val captureEpoch = compose.activity.store.extensions.launchEpoch
        compose.onAllNodesWithTag("extension-primary").fetchSemanticsNodes().singleOrNull()?.let { primary ->
            val frame=compose.onNodeWithTag("extension-palette").fetchSemanticsNode().boundsInWindow
            assertTrue("Primary action retains a 48dp touch target",primary.boundsInWindow.height >= 48*context.resources.displayMetrics.density-1)
            assertTrue("Primary action stays inside the palette",frame.contains(primary.boundsInWindow.topLeft) && frame.contains(primary.boundsInWindow.bottomRight))
        }
        if (name in setOf("filtered-copy", "caret-preserved", "reordered-selection", "keyboard-selected-visible", "store-palette-contrast", "store-legacy-oklch", "form-regression", "inline-filter-focus", "inline-preferences-return", "single-activation"))
            device.wait(Until.findObject(By.res("com.android.systemui", "dismiss_button")), 1500)
        dismissClipboardForEvidence(device)
        val folder = File(context.filesDir, "extension-grid-evidence").apply { mkdirs() }
        File(folder, "capture-state-$name.json").writeText(JSONObject()
            .put("route_before", captureRoute).put("route_after", compose.activity.store.dialog)
            .put("epoch_before", captureEpoch).put("epoch_after", compose.activity.store.extensions.launchEpoch).toString(2))
        assertEquals("Evidence collection must retain its extension route", captureRoute, compose.activity.store.dialog)
        assertEquals("Evidence collection must retain its command session", captureEpoch, compose.activity.store.extensions.launchEpoch)
        InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
        if (name != "image-errors") compose.waitUntil(10000) {
            val selected = compose.onAllNodes(isSelected()).fetchSemanticsNodes().firstOrNull { it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("extension-grid-cell-") }
            if (selected == null) true else {
                val full = compose.onNodeWithTag(selected.config[SemanticsProperties.TestTag]).getUnclippedBoundsInRoot()
                val viewport = compose.onNodeWithTag("extension-grid").fetchSemanticsNode().boundsInRoot
                kotlin.math.abs(selected.boundsInRoot.height - (full.bottom.value - full.top.value) * context.resources.displayMetrics.density) < 2 &&
                    selected.boundsInRoot.top >= viewport.top && selected.boundsInRoot.bottom <= viewport.bottom
            }
        }
        val expectedCount = compose.onAllNodesWithTag("extension-grid-count").fetchSemanticsNodes().singleOrNull()?.config?.get(SemanticsProperties.Text)?.joinToString("") { it.text }
        val expectedQuery = if (expectedCount != null) compose.onNodeWithTag("extension-search").fetchSemanticsNode().config[SemanticsProperties.EditableText].text else null
        compose.waitUntil(10000) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
            val xml = File(folder, "$name.xml"); device.dumpWindowHierarchy(xml)
            val nodes = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml).getElementsByTagName("node")
            val appNodes = (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }.filter { it.getAttribute("package") == context.packageName }
            fun tagged(tag: String) = appNodes.singleOrNull { it.getAttribute("resource-id").substringAfterLast('/') == tag }
            appNodes.any { it.getAttribute("text").contains(marker) } && (expectedCount == null ||
                (tagged("extension-grid-count")?.getAttribute("text") == expectedCount && tagged("extension-search")?.getAttribute("text") == expectedQuery))
        }
        assertTrue(device.takeScreenshot(File(folder, "$name.png")))
    }
    @Test fun initialControlledSelectionActivatesTheRequestedItem() {
        launch("controlled")
        capture("controlled-initial", "Grid fixture")
        compose.onNodeWithTag("extension-grid-cell-12").assertIsSelected()
        key(KeyEvent.KEYCODE_ENTER)
        compose.waitUntil(5000) { clip() == "grid-fixture:image-12" }
    }
    @Test fun changedControlledSelectionActivatesTheNewRequestedItem() {
        launch("controlled")
        compose.onNodeWithTag("extension-actions").performClick()
        compose.onNodeWithTag("inline-menu-search").performTextReplacement("Select requested item")
        key(KeyEvent.KEYCODE_ENTER)
        compose.waitUntil(5000) { activeRoot(compose.activity.store.extensions.runtimeTree)?.props()?.optString("selectedItemId") == "image-18" }
        capture("controlled-changed", "Grid fixture")
        compose.onNodeWithTag("extension-grid-cell-18").assertIsSelected()
        key(KeyEvent.KEYCODE_ENTER)
        compose.waitUntil(5000) { clip() == "grid-fixture:image-18" }
    }
    @Test fun deferredControlledSelectionSurvivesUntilItsItemArrives() {
        launch("deferred")
        compose.onNodeWithTag("extension-grid-count").assertTextEquals("4 results")
        capture("controlled-pending", "4 results")
        compose.onNodeWithTag("extension-grid-more").performClick()
        compose.waitUntil(5000) { activeRoot(compose.activity.store.extensions.runtimeTree)?.nodes("Grid.Item")?.size == 7 }
        capture("controlled-deferred", "7 results")
        compose.onNodeWithTag("extension-grid-cell-6").assertIsSelected()
        key(KeyEvent.KEYCODE_ENTER)
        compose.waitUntil(5000) { clip() == "grid-fixture:image-6" }
    }
    @Test fun untitledSectionsStartNewVisualRowsAndMatchKeyboardMovement() {
        launch("untitled")
        val nodes=(0..2).map { compose.onNodeWithTag("extension-grid-cell-$it").fetchSemanticsNode() }
        val bounds=compose.runOnUiThread { nodes.map { it.boundsInRoot } }
        File(context.filesDir,"extension-grid-evidence").apply { mkdirs() }.resolve("untitled-geometry.json")
            .writeText(JSONObject().put("cells",JSONArray(bounds.map { listOf(it.left,it.top,it.right,it.bottom) })).toString(2))
        capture("untitled-sections", "3 results")
        assertTrue("An untitled section starts below the preceding partial row", bounds[1].top >= bounds[0].bottom)
        assertEquals("The second section shares its own row", bounds[1].top, bounds[2].top, 1f)
        key(KeyEvent.KEYCODE_DPAD_DOWN); compose.onNodeWithTag("extension-grid-cell-1").assertIsSelected()
        key(KeyEvent.KEYCODE_DPAD_RIGHT); compose.onNodeWithTag("extension-grid-cell-2").assertIsSelected()
        key(KeyEvent.KEYCODE_DPAD_UP); compose.onNodeWithTag("extension-grid-cell-0").assertIsSelected()
        key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_ENTER)
        compose.waitUntil(5000) { clip() == "untitled:u1" }
    }
    private fun renderedCommandFailsAndReturnsToCaller(type: String, storeCaller: Boolean) {
        compose.activityRule.scenario.onActivity { it.store.filter("All"); it.store.search("Public error caller") }
        compose.onNodeWithTag("search").performTextInputSelection(TextRange(2, 7))
        if (storeCaller) {
            compose.activityRule.scenario.onActivity { it.store.dialog="extensions" }
            compose.onNodeWithTag("store-installed").performClick()
            compose.onNodeWithTag("store-search").performTextReplacement("Public Store caller")
        }
        launch("late-${type.lowercase()}")
        compose.onNodeWithTag("extension-primary").assertIsDisplayed()
        assertEquals(type, activeRoot(compose.activity.store.extensions.runtimeTree)?.optString("type"))
        compose.onNodeWithTag("extension-primary").performClick()
        compose.waitUntil(5000) { compose.activity.store.extensions.runtimeError != null }
        // Set the controlled depth after the failure callback, then require a
        // native exit that never needs the failed engine's back() operation.
        compose.activityRule.scenario.onActivity { it.store.extensions.navigationDepth=2 }
        assertNotNull("Late failure retains the rendered tree", compose.activity.store.extensions.runtimeTree)
        compose.onNodeWithTag("extension-error").assertTextEquals("Public late $type failure. Close this command and try again.")
        File(context.filesDir,"extension-grid-evidence").apply { mkdirs() }.resolve("late-${type.lowercase()}-state.json")
            .writeText(JSONObject().put("navigation_depth",compose.activity.store.extensions.navigationDepth)
                .put("root_type",activeRoot(compose.activity.store.extensions.runtimeTree)?.optString("type"))
                .put("error",compose.activity.store.extensions.runtimeError).toString(2))
        capture("late-${type.lowercase()}-error", "Public late $type failure")
        compose.onNodeWithTag("extension-back").assertIsDisplayed()
        compose.onNodeWithTag("extension-error-close").assertIsDisplayed()
        compose.onNodeWithTag("extension-actions").assertDoesNotExist()
        assertEquals(2, compose.activity.store.extensions.navigationDepth)
        compose.onNodeWithTag(if(storeCaller) "extension-error-close" else "extension-back").performClick()
        assertNull("Native error exit closes the command session", compose.activity.store.extensions.running)
        if (storeCaller) {
            compose.onNodeWithTag("store-search").assertIsFocused().assertTextEquals("Public Store caller")
            capture("late-list-return", "Public Store caller")
            compose.onNodeWithTag("extension-back").performClick()
        }
        compose.onNodeWithTag("search").assertIsFocused().assertTextEquals("Public error caller")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2,7)))
        if(!storeCaller) capture("late-grid-return", "Public error caller")
    }
    @Test fun lateGridFailureHasNativeBackAndRestoresLauncher() = renderedCommandFailsAndReturnsToCaller("Grid",false)
    @Test fun lateListFailureHasNativeCloseAndRestoresStore() = renderedCommandFailsAndReturnsToCaller("List",true)

    @Test fun inlineFilterAndActionsReturnKeyboardFocusToGridSearch() {
        launch()
        compose.onNodeWithTag("extension-search").performTextReplacement("Landscape")
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("extension-grid-filter").performClick()
        compose.onNodeWithTag("inline-menu").assertIsDisplayed()
        val focusRecord = JSONObject().put("menuFocused",compose.onNodeWithTag("inline-menu").fetchSemanticsNode().config[SemanticsProperties.Focused])
            .put("focusedTags",JSONArray(compose.onAllNodes(isFocused()).fetchSemanticsNodes().map { it.config.getOrElse(SemanticsProperties.TestTag){"untagged"} }))
        File(context.filesDir,"extension-grid-evidence").apply{mkdirs()}.resolve("inline-menu-focus-state.json").writeText(focusRecord.toString(2))
        compose.onNodeWithTag("inline-menu").assertIsFocused()
        compose.onNodeWithTag("extension-grid-filter-1").performClick()
        compose.onNodeWithTag("extension-search").assertIsFocused().assertTextEquals("Landscape")
        compose.onNodeWithTag("extension-actions").performClick()
        compose.onNodeWithTag("inline-menu").assertIsDisplayed().assert(hasAnyDescendant(isFocused()) or isFocused())
        val actionPanel=compose.onNodeWithTag("inline-menu").fetchSemanticsNode().boundsInWindow
        val primary=compose.onNodeWithTag("extension-primary").fetchSemanticsNode().boundsInWindow
        File(context.filesDir,"extension-grid-evidence").resolve("action-panel-bounds.json").writeText(JSONObject()
            .put("panel_bottom",actionPanel.bottom).put("primary_top",primary.top).put("overlap",actionPanel.bottom>primary.top).toString(2))
        if(actionPanel.bottom>primary.top) device.takeScreenshot(File(context.filesDir,"extension-grid-evidence/action-panel-overlap.png"))
        assertTrue("Actions panel stays above the footer controls",actionPanel.bottom <= primary.top)
        key(KeyEvent.KEYCODE_ESCAPE)
        compose.onNodeWithTag("extension-search").assertIsFocused()
        key(KeyEvent.KEYCODE_ENTER)
        compose.waitUntil(5000) { clip().startsWith("grid-fixture:image-") }
        compose.onNodeWithTag("extension-grid-screen").assertIsDisplayed()
        capture("inline-filter-focus", "Landscape 00")
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onAllNodes(isPopup()).assertCountEquals(0)
    }
    @Test fun cancellingInlinePreferencesRetainsRuntimeAndGridSelection() {
        launch()
        fun engine(): android.webkit.WebView {
            var found: android.webkit.WebView?=null
            compose.activityRule.scenario.onActivity { activity ->
                fun visit(view:android.view.View) {
                    if(view is android.webkit.WebView) { assertNull("One command engine",found);found=view }
                    if(view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
                }
                visit(activity.window.decorView)
            }
            return requireNotNull(found) { "Attached JavaScript engine" }
        }
        fun activationCount(): String {
            compose.onNodeWithTag("extension-actions").performClick()
            compose.onNodeWithTag("inline-menu-search").performTextReplacement("Report activation count")
            key(KeyEvent.KEYCODE_ENTER)
            compose.waitUntil(5000) { clip().startsWith("activations:") }
            return clip()
        }
        compose.onNodeWithTag("extension-search").performTextReplacement("Landscape")
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_ENTER)
        compose.waitUntil(5000) { clip()=="grid-fixture:image-1" }
        assertEquals("activations:1",activationCount())
        val engineBefore=engine()
        val epoch=compose.activity.store.extensions.launchEpoch
        compose.onNodeWithContentDescription("Extension preferences").performClick()
        compose.onNodeWithTag("run-extension-setup").assertIsDisplayed()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithTag("extension-back").performClick()
        compose.onNodeWithTag("extension-search").assertTextEquals("Landscape").assertIsFocused()
        assertEquals(epoch,compose.activity.store.extensions.launchEpoch)
        assertSame("Cancel retains the actual JavaScript engine",engineBefore,engine())
        compose.onNodeWithTag("extension-grid-cell-1").assertIsSelected()
        key(KeyEvent.KEYCODE_ENTER)
        compose.waitUntil(5000) { clip()=="grid-fixture:image-1" }
        assertEquals("JavaScript state survives preferences", "activations:2",activationCount())
        val folder=File(context.filesDir,"extension-grid-evidence").apply{mkdirs()}
        File(folder,"preferences-runtime-state.json").writeText(JSONObject()
            .put("same_engine",true).put("same_epoch",true).put("activations_before",1).put("activations_after",2).toString(2))
        capture("inline-preferences-return","Landscape 01")
    }
    @Test fun cancellingPreferencesRetainsManualGridScrollPosition() {
        launch()
        compose.waitUntil(5000) { activeRoot(compose.activity.store.extensions.runtimeTree)?.nodes("Grid.Section")?.firstOrNull()?.props()?.optString("title")=="Selected: image-0" }
        compose.onNodeWithTag("extension-grid").performScrollToIndex(12)
        compose.waitForIdle()
        val before=compose.onNodeWithTag("extension-grid-cell-8").fetchSemanticsNode().boundsInWindow
        val epoch=compose.activity.store.extensions.launchEpoch
        compose.onNodeWithContentDescription("Extension preferences").performClick()
        compose.onNodeWithTag("run-extension-setup").assertIsDisplayed()
        compose.onNodeWithTag("extension-back").performClick()
        compose.waitForIdle()
        val after=compose.onAllNodesWithTag("extension-grid-cell-8").fetchSemanticsNodes().singleOrNull()?.boundsInWindow
        val folder=File(context.filesDir,"extension-grid-evidence").apply{mkdirs()}
        File(folder,"grid-return-position.json").writeText(JSONObject().put("before",before.toString()).put("after",after?.toString()).put("epoch_retained",epoch==compose.activity.store.extensions.launchEpoch).toString(2))
        device.dumpWindowHierarchy(File(folder,"grid-return-position.xml"));device.takeScreenshot(File(folder,"grid-return-position.png"))
        assertEquals("Cancel retains manual grid scrolling",before,after)
        assertEquals(epoch,compose.activity.store.extensions.launchEpoch)
    }
    @Test fun onePhysicalEnterActivatesOnlyOnceWithTheKeyboardVisible() {
        launch()
        val originalSetting = device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
        try {
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            if(compose.onAllNodesWithTag("extension-grid-keyboard").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithTag("extension-grid-keyboard").performClick()
            else compose.activityRule.scenario.onActivity { activity ->
                val view=WindowInspector.getGlobalWindowViews().single { it.hasWindowFocus() }
                activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(view.findFocus(),android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
            compose.waitUntil(10000) { WindowInspector.getGlobalWindowViews().filter { it.hasWindowFocus() }.any {
                androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true } }
            val time=SystemClock.uptimeMillis()
            InstrumentationRegistry.getInstrumentation().sendKeySync(KeyEvent(time,time,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER,0))
            repeat(5) { InstrumentationRegistry.getInstrumentation().sendKeySync(KeyEvent(time,SystemClock.uptimeMillis(),KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER,it+1)) }
            InstrumentationRegistry.getInstrumentation().sendKeySync(KeyEvent(time,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER,0))
            compose.waitUntil(5000) { clip()=="grid-fixture:image-0" }
            compose.onNodeWithTag("extension-actions").performClick()
            compose.onNodeWithTag("inline-menu").assertIsDisplayed().assert(hasAnyDescendant(isFocused()) or isFocused())
            key(KeyEvent.KEYCODE_DPAD_UP); key(KeyEvent.KEYCODE_ENTER)
            // The second JS action is a FIFO barrier; it observes all preceding activation callbacks.
            compose.waitUntil(5000) { clip().startsWith("activations:") }
            assertEquals("activations:1",clip())
            capture("single-activation","Landscape 00")
        } finally {
            device.executeShellCommand(if(originalSetting=="null") "settings delete secure show_ime_with_hard_keyboard" else "settings put secure show_ime_with_hard_keyboard $originalSetting")
        }
    }
    @Test fun nativeGridDisplaysColumnsImagesAndUnavailableSources() {
        launch()
        compose.onNodeWithTag("extension-grid").assertExists()
        for (index in listOf(0, 1)) compose.waitUntil(20000) { compose.onAllNodesWithTag("extension-grid-image-$index", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val first = compose.onNodeWithTag("extension-grid-cell-0").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("extension-grid-cell-1").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, second.top, 1f); assertTrue(second.left > first.right)
        compose.onNodeWithTag("extension-grid-image-error-2", useUnmergedTree = true).assertExists()
        capture("native-grid", "Landscape 00")
        compose.onNodeWithTag("extension-grid").performScrollToIndex(4)
        compose.waitUntil(10000) { compose.onAllNodesWithTag("extension-grid-image-error-3", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("extension-grid-image-error-4", useUnmergedTree = true).assertExists()
        capture("image-errors", "Image unavailable")
    }
    @Test fun typingFourWaySelectionAndSameTurnActivationUseCurrentItem() {
        launch()
        compose.activityRule.scenario.onActivity { activity ->
            val view = WindowInspector.getGlobalWindowViews().single { it.hasWindowFocus() }
            val time = SystemClock.uptimeMillis()
            // Both complete pairs in one main-thread turn, before recomposition.
            for (code in listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_ENTER)) for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP))
                view.dispatchKeyEvent(KeyEvent(time, time, action, code, 0))
        }
        compose.waitUntil(5000) { clip() == "grid-fixture:image-1" }
        key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_LEFT); key(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithTag("extension-grid-cell-0").assertIsSelected()
        compose.onNodeWithTag("extension-search").performTextReplacement("image-12")
        compose.onNodeWithTag("extension-grid-count").assertTextEquals("1 result")
        key(KeyEvent.KEYCODE_ENTER); compose.waitUntil(5000) { clip() == "grid-fixture:image-12" }
        capture("filtered-copy", "image-12")
    }
    @Test fun actionsInspectAndBackRestoreGridSelectionAndQuery() {
        launch(); compose.onNodeWithTag("extension-search").performTextReplacement("image-12")
        compose.onNodeWithTag("extension-actions").performClick(); compose.onNodeWithTag("extension-action-1").performClick()
        compose.onNodeWithText("Grid fixture detail image-12").assertIsDisplayed(); capture("detail-destination", "Grid fixture detail image-12")
        key(KeyEvent.KEYCODE_ESCAPE)
        try {
            compose.waitUntil(5000) { compose.onAllNodesWithTag("extension-search").fetchSemanticsNodes().isNotEmpty() }
        } catch (error: Throwable) {
            val folder = File(context.filesDir, "extension-grid-evidence").apply { mkdirs() }
            val catalog = compose.activity.store.extensions
            File(folder, "back-state.json").writeText(JSONObject().put("depth", catalog.navigationDepth).put("dialog", compose.activity.store.dialog).put("root", activeRoot(catalog.runtimeTree)?.optString("type")).toString())
            device.dumpWindowHierarchy(File(folder, "back-failure.xml")); device.takeScreenshot(File(folder, "back-failure.png"))
            throw error
        }
        assertEquals(1, compose.activity.store.extensions.navigationDepth)
        compose.onNodeWithTag("extension-search").assertTextEquals("image-12")
        compose.onNodeWithTag("extension-grid-cell-0").assertIsSelected(); capture("back-to-grid", "image-12")
        assertTrue(device.click(5, 300))
        compose.waitUntil(5000) { compose.activity.store.dialog == null && compose.activity.store.extensions.running == null }
    }
    @Test fun emptyAndLoadingStatesExposeSearchAndDisabledActions() {
        launch(); compose.onNodeWithTag("extension-search").performTextReplacement("no-fixture-match")
        compose.onNodeWithTag("extension-grid-count").assertTextEquals("0 results")
        compose.onNodeWithTag("extension-primary").assertIsNotEnabled(); capture("empty-grid", "No matching fixture cards")
        compose.activityRule.scenario.onActivity { it.store.extensions.close(); it.store.dialog = null }
        launch("loading"); compose.onNodeWithTag("extension-grid-loading").assertExists(); capture("loading-grid", "No matching fixture cards")
    }
    @Test fun caretSelectionAndCompositionDoNotResetTheGridSelection() {
        launch(); compose.onNodeWithTag("extension-search").performTextReplacement("Landscape")
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("extension-grid-cell-1").assertIsSelected()
        key(KeyEvent.KEYCODE_L, KeyEvent.META_CTRL_ON); key(KeyEvent.KEYCODE_DPAD_LEFT)
        compose.onNodeWithTag("extension-search").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(8)))
        compose.onNodeWithTag("extension-grid-cell-1").assertIsSelected()
        compose.onNodeWithTag("extension-search").performTextInputSelection(TextRange(0, 4))
        compose.activityRule.scenario.onActivity {
            val view = WindowInspector.getGlobalWindowViews().single { it.hasWindowFocus() }
            val connection = requireNotNull(view.findFocus()?.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
            assertTrue(connection.setComposingRegion(0, 4))
            val now = SystemClock.uptimeMillis()
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) view.dispatchKeyEvent(KeyEvent(now, now, action, KeyEvent.KEYCODE_DPAD_RIGHT, 0))
            assertTrue(connection.finishComposingText())
        }
        compose.waitForIdle(); compose.onNodeWithTag("extension-grid-cell-1").assertIsSelected()
        key(KeyEvent.KEYCODE_ENTER); compose.waitUntil(5000) { clip() == "grid-fixture:image-1" }
        capture("caret-preserved", "Landscape")
    }
    @Test fun filteringPaginationAndReorderingRetainItemIdentity() {
        launch(); compose.onNodeWithTag("extension-grid-more").performClick()
        compose.waitUntil(5000) { activeRoot(compose.activity.store.extensions.runtimeTree)?.nodes("Grid.Item")?.size == 33 }
        compose.onNodeWithTag("extension-grid-count").assertTextEquals("33 results")
        compose.onNodeWithTag("extension-grid-filter").performClick(); compose.onNodeWithTag("extension-grid-filter-1").performClick()
        compose.waitUntil(5000) { activeRoot(compose.activity.store.extensions.runtimeTree)?.nodes("Grid.Item")?.size == 17 }
        compose.onNodeWithTag("extension-grid-count").assertTextEquals("17 results")
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("extension-actions").performClick(); compose.onNodeWithTag("extension-action-2").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("extension-grid-cell-15").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("extension-grid-cell-15").assertIsSelected()
        key(KeyEvent.KEYCODE_ENTER); compose.waitUntil(5000) { clip() == "grid-fixture:image-2" }
        capture("reordered-selection", "Landscape 02")
    }
    @Test fun selectedCellStaysFullyVisibleWhenTheKeyboardShrinksTheGrid() {
        launch()
        val originalSetting = device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
        try {
            // End the editable input session before measuring the hidden-IME baseline.
            // Otherwise a delayed phone auto-show can make "before" already shrunk.
            compose.onNodeWithTag("extension-grid").performSemanticsAction(SemanticsActions.RequestFocus)
            compose.onNodeWithTag("extension-grid").assertIsFocused()
            compose.activityRule.scenario.onActivity {
                val view = WindowInspector.getGlobalWindowViews().single { it.hasWindowFocus() }
                androidx.core.view.ViewCompat.getWindowInsetsController(view)?.hide(androidx.core.view.WindowInsetsCompat.Type.ime())
            }
            compose.waitUntil(5000) { WindowInspector.getGlobalWindowViews().filter { it.hasWindowFocus() }.none {
                androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true } }
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithTag("extension-grid-cell-6").assertIsSelected()
            compose.onNodeWithTag("extension-grid").assertIsFocused()
            assertTrue("Hidden-keyboard baseline must still be hidden at measurement",WindowInspector.getGlobalWindowViews().filter{it.hasWindowFocus()}.none {
                androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true })
            val before = compose.onNodeWithTag("extension-grid").fetchSemanticsNode().boundsInRoot.height
            val expectShrink=compose.activity.resources.configuration.smallestScreenWidthDp>=600
            File(context.filesDir,"extension-grid-evidence").apply{mkdirs()}.resolve("keyboard-before.json").writeText(JSONObject().put("grid_height",before).put("grid_focused",true).put("ime_visible",false).put("expect_shrink",expectShrink).toString(2))
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            if (compose.activity.resources.configuration.smallestScreenWidthDp >= 600) compose.onNodeWithTag("extension-grid-keyboard").performClick()
            else {
              compose.onNodeWithTag("extension-search").performSemanticsAction(SemanticsActions.RequestFocus)
              compose.onNodeWithTag("extension-search").assertIsFocused()
              compose.activityRule.scenario.onActivity { activity ->
                val view = WindowInspector.getGlobalWindowViews().single { it.hasWindowFocus() }
                activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(view.findFocus(), android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
              }
            }
            try { compose.waitUntil(10000) {
                val viewport = compose.onNodeWithTag("extension-grid").fetchSemanticsNode().boundsInRoot
                val cell = compose.onAllNodesWithTag("extension-grid-cell-6").fetchSemanticsNodes().singleOrNull()?.boundsInRoot
                val ime=WindowInspector.getGlobalWindowViews().filter{it.hasWindowFocus()}.any {
                    androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true }
                ime && (if(expectShrink) viewport.height < before-20 else viewport.height <= before+1) && cell != null && cell.height > 70 && cell.top >= viewport.top && cell.bottom <= viewport.bottom &&
                    kotlin.math.abs(cell.height - compose.onNodeWithTag("extension-grid-cell-6").getUnclippedBoundsInRoot().let { it.bottom.value - it.top.value } * context.resources.displayMetrics.density) < 2
            }
            } catch (error: Throwable) {
                val folder = File(context.filesDir, "extension-grid-evidence").apply { mkdirs() }
                val viewport = compose.onNodeWithTag("extension-grid").fetchSemanticsNode().boundsInRoot
                val cell = compose.onAllNodesWithTag("extension-grid-cell-6").fetchSemanticsNodes().singleOrNull()?.boundsInRoot
                File(folder, "keyboard-layout.json").writeText(JSONObject().put("screen", "${device.displayWidth}x${device.displayHeight}").put("grid_screen", compose.onNodeWithTag("extension-grid-screen").fetchSemanticsNode().boundsInRoot.toString()).put("footer", compose.onNodeWithTag("extension-primary").getUnclippedBoundsInRoot().toString()).put("windows", WindowInspector.getGlobalWindowViews().map { val location=IntArray(2); it.getLocationOnScreen(location); "${it.width}x${it.height},location=${location.toList()},focused=${it.hasWindowFocus()},ime=${androidx.core.view.ViewCompat.getRootWindowInsets(it)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())}" }).toString(2))
                File(folder, "keyboard-failure.json").writeText(JSONObject().put("before_height", before).put("viewport", viewport.toString()).put("cell", cell?.toString()).put("windows", WindowInspector.getGlobalWindowViews().map { "focused=${it.hasWindowFocus()},ime=${androidx.core.view.ViewCompat.getRootWindowInsets(it)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())}" }).toString(2))
                device.dumpWindowHierarchy(File(folder, "keyboard-failure.xml")); device.takeScreenshot(File(folder, "keyboard-failure.png"))
                throw error
            }
            compose.onNodeWithTag("extension-grid-cell-6").assertIsSelected()
            key(KeyEvent.KEYCODE_ENTER); compose.waitUntil(5000) { clip() == "grid-fixture:image-6" }
            val folder = File(context.filesDir, "extension-grid-evidence").apply { mkdirs() }
            File(folder, "keyboard-layout.json").writeText(JSONObject().put("screen", "${device.displayWidth}x${device.displayHeight}").put("grid_screen", compose.onNodeWithTag("extension-grid-screen").fetchSemanticsNode().boundsInRoot.toString()).put("footer", compose.onNodeWithTag("extension-primary").getUnclippedBoundsInRoot().toString()).put("windows", WindowInspector.getGlobalWindowViews().map { val location=IntArray(2); it.getLocationOnScreen(location); "${it.width}x${it.height},location=${location.toList()},focused=${it.hasWindowFocus()},ime=${androidx.core.view.ViewCompat.getRootWindowInsets(it)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())}" }).toString(2))
            compose.onNodeWithTag("extension-primary").assertIsDisplayed().assertIsEnabled()
            val footer = device.wait(Until.findObject(By.res("extension-primary")), 5000)
            assertNotNull(footer)
            val ime = device.findObject(By.pkg("com.google.android.inputmethod.latin"))
            assertNotNull("Real docked keyboard", ime)
            assertTrue("Footer must remain above the keyboard", footer.visibleBounds.bottom <= ime.visibleBounds.top)
            capture("keyboard-selected-visible", "Landscape 06")
        } finally {
            device.executeShellCommand(if (originalSetting == "null") "settings delete secure show_ime_with_hard_keyboard" else "settings put secure show_ime_with_hard_keyboard $originalSetting")
        }
    }
    @Test fun nativeFormCanEditAndSubmitAfterTheWindowChanges() {
        launch("form")
        compose.onNodeWithTag("extension-field-note").performClick().performTextInput("Grid regression")
        compose.onNodeWithTag("extension-primary").assertIsDisplayed().performClick()
        compose.waitUntil(5000) { clip() == "form:Grid regression" }
        capture("form-regression", "Grid regression")
    }
    @Test fun storeInstallReviewAndCommandReturnStayInsideThePalette() {
        val catalog = compose.activity.store.extensions
        val name = "palette-colors"
        assertFalse("Never replace an existing installation", catalog.installed.any { it.listing.name == name })
        var installed: InstalledExtension? = null
        fun sameWindow() {
            compose.onAllNodes(isDialog()).assertCountEquals(0)
            compose.onAllNodes(isPopup()).assertCountEquals(0)
            compose.onAllNodes(isRoot()).assertCountEquals(1)
        }
        fun show(tag: String) {
            compose.onNodeWithTag("store-results").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }
        try {
            compose.activityRule.scenario.onActivity { it.store.dialog = "extensions" }
            compose.onNodeWithTag("store-search").performTextReplacement("palette colors")
            compose.waitUntil(30000) { compose.onAllNodesWithTag("install-$name").fetchSemanticsNodes().isNotEmpty() }
            show("install-$name")
            val browser = compose.onNodeWithTag("extension-palette").fetchSemanticsNode().boundsInWindow
            compose.onNodeWithTag("install-$name").performClick()
            compose.onNodeWithTag("extension-review").assertIsDisplayed(); sameWindow()
            val review = compose.onNodeWithTag("extension-palette").fetchSemanticsNode().boundsInWindow
            assertEquals(browser.left, review.left, 1f); assertEquals(browser.top, review.top, 1f); assertEquals(browser.width, review.width, 1f)
            capture("store-inline-install-review", "This installs third-party JavaScript")
            compose.onNodeWithTag("extension-back").performClick()
            compose.onNodeWithTag("store-search").assertTextEquals("palette colors")
            assertFalse(catalog.installed.any { it.listing.name == name })
            show("install-$name"); compose.onNodeWithTag("install-$name").performClick()
            compose.onNodeWithTag("confirm-install").performClick()
            compose.waitUntil(60000) { catalog.busy == null && (catalog.installed.any { it.listing.name == name } || catalog.error != null) }
            assertNull(catalog.error, catalog.error)
            installed = catalog.installed.single { it.listing.name == name }
            File(context.filesDir,"extension-grid-evidence").resolve("store-inline-source.json").writeText(JSONObject()
                .put("id",installed.listing.id).put("source_url",installed.listing.sourceUrl).put("commit_sha",installed.listing.sha)
                .put("command_sha256",MessageDigest.getInstance("SHA-256").digest(File(installed.directory,"index.js").readBytes()).joinToString(""){"%02x".format(it)}).toString(2))
            compose.waitUntil(10000) { compose.onAllNodesWithTag("extension-command-index").fetchSemanticsNodes().isNotEmpty() }
            show("extension-command-index"); compose.onNodeWithTag("extension-command-index").performClick()
            compose.waitUntil(25000) { compose.onAllNodesWithTag("extension-grid").fetchSemanticsNodes().isNotEmpty() || catalog.runtimeError != null }
            assertNull(catalog.runtimeError,catalog.runtimeError); sameWindow()
            capture("store-inline-command", "Palette Colors")
            compose.onNodeWithTag("extension-back").performClick()
            compose.onNodeWithTag("store-search").assertTextEquals("palette colors"); sameWindow()
            show("uninstall-$name"); capture("store-inline-return", "Palette Colors")
            compose.onNodeWithTag("uninstall-$name").performClick()
            compose.onNodeWithTag("confirm-uninstall").assertIsDisplayed(); sameWindow()
            capture("store-inline-uninstall-review", "Its commands, preferences and local extension data")
            compose.onNodeWithTag("extension-back").performClick()
            assertTrue(catalog.installed.any { it.listing.name == name })
            show("uninstall-$name"); compose.onNodeWithTag("uninstall-$name").performClick()
            compose.onNodeWithTag("confirm-uninstall").performClick()
            compose.waitUntil(30000) { catalog.busy == null && catalog.installed.none { it.listing.name == name } }
            assertFalse(installed.directory.exists())
            compose.onNodeWithTag("store-search").assertTextEquals("palette colors")
        } finally {
            compose.activityRule.scenario.onActivity { it.store.extensions.close(); it.store.dialog=null }
            compose.waitForIdle()
            installed?.let { if(catalog.installed.any { current -> current.listing.id == it.listing.id }) runBlocking { catalog.uninstall(it) } }
        }
    }

    private fun realGrid(query: String, name: String, command: String, expectedTag: String = "extension-grid", check: (InstalledExtension) -> Unit) {
        val catalog = compose.activity.store.extensions
        assertFalse("Do not overwrite a preexisting installation", catalog.installed.any { it.listing.name == name })
        var installed: InstalledExtension? = null
        try {
            val listing = runBlocking { catalog.search(query).first { it.name == name } }
            runBlocking { catalog.install(listing) }
            installed = catalog.installed.single { it.listing.id == listing.id }
            val record = JSONObject().put("name", name).put("id", installed.listing.id).put("source_url", installed.listing.sourceUrl).put("commit_sha", installed.listing.sha)
                .put("command", command).put("command_sha256", MessageDigest.getInstance("SHA-256").digest(File(installed.directory, "$command.js").readBytes()).joinToString("") { "%02x".format(it) })
            File(context.filesDir, "extension-grid-evidence").apply { mkdirs() }.resolve("store-$name.json").writeText(record.toString(2))
            compose.activityRule.scenario.onActivity { it.store.openExtension(installed, installed.commands.first { c -> c.name == command }) }
            compose.waitUntil(30000) { compose.onAllNodesWithTag(expectedTag).fetchSemanticsNodes().isNotEmpty() || catalog.runtimeError != null }
            assertNull(catalog.runtimeError, catalog.runtimeError)
            compose.onNodeWithTag(expectedTag).assertExists()
            check(installed)
        } finally {
            compose.activityRule.scenario.onActivity { it.store.extensions.close(); it.store.dialog = null }
            compose.waitForIdle()
            installed?.let { runBlocking { catalog.uninstall(it) }; assertFalse(it.directory.exists()) }
        }
    }
    @Test fun realPaletteColorsGridCopiesChangesSchemeAndRemembersDropdown() = realGrid("palette colors", "palette-colors", "index") { installed ->
        if (context.resources.configuration.smallestScreenWidthDp >= 600) compose.waitUntil(10000) {
            WindowInspector.getGlobalWindowViews().filter { it.hasWindowFocus() }.none {
                androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true }
        }
        fun titles() = activeRoot(compose.activity.store.extensions.runtimeTree)?.nodes("Grid.Item")?.map { it.props().optString("title") }.orEmpty()
        capture("store-palette-colors", "Palette Colors")
        val monoTitles = titles()
        assertTrue(monoTitles.isNotEmpty())
        compose.onNodeWithTag("extension-primary").performClick(); compose.waitUntil(5000) { Regex("#[0-9a-fA-F]{6}").matches(clip()) }
        compose.onNodeWithTag("extension-grid-filter").performClick(); compose.onNodeWithTag("extension-grid-filter-1").performClick()
        compose.onNodeWithTag("extension-grid-filter").assertTextContains("Contrast")
        compose.waitUntil(10000) { titles().isNotEmpty() && titles() != monoTitles }
        val contrastTitles = titles()
        capture("store-palette-contrast", "Contrast")
        compose.activityRule.scenario.onActivity { it.store.extensions.close(); it.store.dialog = null }
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity { it.store.openExtension(installed, installed.commands.single()) }
        compose.waitUntil(25000) { compose.onAllNodesWithTag("extension-grid-filter").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("extension-grid-filter").assertTextContains("Contrast")
        compose.waitUntil(10000) { titles() == contrastTitles }
        capture("store-palette-reopened", "Contrast")
    }
    @Test fun realLegacyListStillSearchesAndCopiesAfterTheWindowChanges() = realGrid("oklch", "oklch-color-converter", "convert-color", "extension-list") {
        compose.onNodeWithTag("extension-search").performTextReplacement("#ff0000")
        compose.waitUntil(10000) { compose.activity.store.extensions.runtimeTree?.toString()?.contains("oklch(") == true }
        val rows = activeRoot(compose.activity.store.extensions.runtimeTree)!!.nodes("List.Item")
        val index = rows.indexOfFirst { it.props().optString("title").startsWith("oklch(") }
        assertTrue("Select the OKLCH result, not the first Figma P3 result", index >= 0)
        val expected = rows[index].props().getString("title")
        compose.onNodeWithTag("extension-row-$index").performClick()
        compose.onNodeWithTag("extension-primary").performClick()
        compose.waitUntil(5000) { clip() == expected }
        capture("store-legacy-oklch", expected)
    }
    @Test fun oversizedTailwindBundleReportsTheExistingInstallLimit() {
        val catalog = compose.activity.store.extensions
        val originalIds = catalog.installed.map { it.listing.id }.toSet()
        assertFalse(catalog.installed.any { it.listing.name == "tailwindcss" })
        val listing = runBlocking { catalog.search("tailwind").first { it.name == "tailwindcss" } }
        compose.activityRule.scenario.onActivity { it.store.dialog = "extensions"; it.store.installExtension(listing) }
        compose.waitUntil(30000) { catalog.busy == null && catalog.error != null }
        assertTrue(catalog.error.orEmpty(), catalog.error.orEmpty().contains("Expanded extension exceeds the size limit"))
        assertEquals(originalIds, catalog.installed.map { it.listing.id }.toSet())
        assertFalse(File(context.filesDir, "extensions/${listing.id}").exists())
        capture("store-tailwind-unavailable", "Expanded extension exceeds the size limit")
        compose.activityRule.scenario.onActivity { catalog.error = null }
    }

}

@RunWith(AndroidJUnit4::class)
class ExtensionGridBaselineTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun captureFlattenedBaseline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = gridFixture(context)
        var originalClip: android.content.ClipData? = null
        compose.activityRule.scenario.onActivity { val manager = it.getSystemService(ClipboardManager::class.java); originalClip = manager.primaryClip; manager.clearPrimaryClip() }
        try {
            compose.activityRule.scenario.onActivity { it.store.openExtension(fixture, fixture.commands.single()) }
            compose.waitUntil(25000) { compose.activity.store.extensions.runtimeTree != null }
            compose.onNodeWithTag("extension-list").assertExists(); compose.onNodeWithText("Landscape 00").assertIsDisplayed()
            val folder = File(context.filesDir, "extension-grid-evidence").apply { mkdirs() }
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()); device.waitForIdle()
            device.dumpWindowHierarchy(File(folder, "before-grid.xml")); assertTrue(device.takeScreenshot(File(folder, "before-grid.png")))
        } finally {
            compose.activityRule.scenario.onActivity { it.store.extensions.close(); it.store.dialog = null }
            compose.waitForIdle()
            compose.activityRule.scenario.onActivity { val manager = it.getSystemService(ClipboardManager::class.java); originalClip?.let(manager::setPrimaryClip) ?: manager.clearPrimaryClip() }
            fixture.directory.deleteRecursively(); File(context.filesDir, "grid-private-fixture.png").delete()
        }
    }
}
