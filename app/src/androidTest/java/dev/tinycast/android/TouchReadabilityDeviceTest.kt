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
    @get:Rule val testName=org.junit.rules.TestName()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val device get()=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private var palette=emptyMap<String,Any?>()
    private var emoji=emptyMap<String,Any?>()
    private var clip:ClipData?=null
    private var fixture:File?=null
    private var fixtureId:String?=null
    private val geometry=JSONObject()
    private val actionTitle="Copy the selected audit value"
    private val nonce=UUID.randomUUID().toString().replace("-","").take(8)
    private val noteQuery="touchaudit$nonce"
    private val alias="touchauditalias$nonce"
    private val noteTitle="Touch audit note with a long, recognizable title $noteQuery"
    private var noteId=""
    private var preferencesCaptured=false
    private var clipboardCaptured=false

    @Before fun prepare() {
        assertEquals("Tinycast_Tablet_API_36",InstrumentationRegistry.getArguments().getString("dedicatedAvd"))
        palette=context.getSharedPreferences("palette",Context.MODE_PRIVATE).all.toMap()
        emoji=context.getSharedPreferences("emoji",Context.MODE_PRIVATE).all.toMap()
        preferencesCaptured=true
        InstrumentationRegistry.getArguments().getString("expectedFontScale")?.toFloat()?.let { requested ->
            compose.waitUntil(10000){kotlin.math.abs(context.resources.configuration.fontScale-requested)<0.01f}
        }
        compose.waitUntil(15000){!compose.activity.store.loading && !compose.activity.store.emoji.loading && !compose.activity.store.folderSearch.loading}
        // Connect and settle accessibility before producing the known preview,
        // matching the cold-start ordering used by ActivationDeviceTest.
        dismissClipboardOverlay("before-sentinel")
        compose.activityRule.scenario.onActivity {
            val manager=it.getSystemService(ClipboardManager::class.java)
            clip=manager.primaryClip
            clipboardCaptured=true
            manager.setPrimaryClip(ClipData.newPlainText("Touch fixture","touch-audit-sentinel"))
            it.store.resetShortcuts();it.store.reset()
            noteId=it.store.save("notes",noteTitle,"Touch audit body 271828")
            it.store.setAlias(noteId,alias)
            it.store.setShortcut(noteId,KeyChord(KeyEvent.KEYCODE_PAGE_DOWN,ShortcutRules.CTRL or ShortcutRules.ALT or ShortcutRules.SHIFT))
            it.store.search(noteQuery)
        }
        compose.waitForIdle();dismissClipboardOverlay("after-sentinel")
    }
    private fun dismissClipboardOverlay(phase:String) {
        val observations=JSONArray();val actions=JSONArray();var settled=false
        val entryFlags=testAutomation().serviceInfo.flags
        try {
            dismissClipboardForEvidence(onObservation={observations.put(it)}) { id,accepted ->
                actions.put(JSONObject().put("id",id).put("accepted",accepted))
            }
            settled=true
        } finally {
            val exitFlags=testAutomation().serviceInfo.flags
            val out=File(context.filesDir,"readiness-evidence").apply { mkdirs() }
            File(out,"touch-clipboard-${testName.methodName.lowercase(java.util.Locale.ROOT)}-$phase.json").writeText(JSONObject()
                .put("phase",phase).put("settled_dismissal",settled).put("font_scale",context.resources.configuration.fontScale)
                .put("entry_flags",entryFlags).put("exit_flags",exitFlags).put("observations",observations).put("actions",actions)
                .put("before_any_diagnostic_uidevice_dump",true).toString(2))
            assertEquals("Clipboard evidence must restore entry flags",entryFlags,exitFlags)
        }
    }
    @After fun restore() {
        if(!preferencesCaptured)return
        compose.activityRule.scenario.onActivity {
            it.store.extensions.close()
            if(clipboardCaptured) {
                val manager=it.getSystemService(ClipboardManager::class.java)
                clip?.let(manager::setPrimaryClip) ?: manager.resetClipboardForTest()
            }
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
    private fun fullBounds(tag:String)=fullBounds(compose.onNodeWithTag(tag))
    private fun fullBounds(node:SemanticsNodeInteraction):Rect {
        val semantics=node.fetchSemanticsNode()
        return compose.runOnUiThread { unclipped(semantics) }
    }
    // Same geometry as Compose 1.7.6 getUnclippedBoundsInRoot, in pixels.
    // Read alongside clipped/viewport bounds on the UI thread, without another
    // test-framework synchronization allowing a different layout frame.
    private fun unclipped(node:androidx.compose.ui.semantics.SemanticsNode):Rect {
        val origin=node.positionInRoot;val size=node.size
        return Rect(origin.x,origin.y,origin.x+size.width,origin.y+size.height)
    }
    private data class ScrollGeometry(val visible:Rect,val full:Rect,val outer:Rect,val parent:Rect,val parentFull:Rect,
                                      val outerScroll:Float,val outerMax:Float)
    private fun settledViewport() {
        var last:Pair<Rect,Boolean>?=null
        var since=0L
        compose.waitUntil(5000) {
            val route=compose.onNodeWithTag("palette-content-scroll").fetchSemanticsNode()
            val snapshot=compose.runOnUiThread { route.boundsInWindow to imeVisible() }
            val now=android.os.SystemClock.elapsedRealtime()
            if(snapshot!=last) { last=snapshot;since=now;false } else now-since>=300
        }
    }
    private fun reveal(tag:String):SemanticsNodeInteraction {
        // Platform IME animations are not part of Compose's idle detection.
        // Do not certify bounds from the outgoing, larger window frame.
        settledViewport()
        val target=compose.onNodeWithTag(tag)
        val initial=target.fetchSemanticsNode()
        val inRoute=generateSequence(initial.parent) { it.parent }.any {
            it.config.contains(SemanticsProperties.TestTag) && it.config[SemanticsProperties.TestTag]=="palette-content-scroll"
        }
        geometry.put("requested_target",tag).put("requested_target_in_route",inRoute)
        if(!inRoute)return target
        repeat(12) {
            val node=target.fetchSemanticsNode()
            val outerInteraction=compose.onNodeWithTag("palette-content-scroll")
            val outerNode=outerInteraction.fetchSemanticsNode()
            val scrollParent=generateSequence(node.parent) { it.parent }.firstOrNull {
                it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
            } ?: outerNode
            val outerRange=outerNode.config[SemanticsProperties.VerticalScrollAxisRange]
            val sample=compose.runOnUiThread { ScrollGeometry(node.boundsInRoot,unclipped(node),outerNode.boundsInRoot,
                scrollParent.boundsInRoot,unclipped(scrollParent),outerRange.value(),outerRange.maxValue()) }
            val visible=sample.visible;val full=sample.full;val outer=sample.outer
            geometry.put("reveal-$tag",JSONObject().put("visible_top",visible.top).put("visible_bottom",visible.bottom)
                .put("full_top",full.top).put("full_bottom",full.bottom).put("viewport_top",outer.top).put("viewport_bottom",outer.bottom)
                .put("parent_top",sample.parent.top).put("parent_bottom",sample.parent.bottom)
                .put("parent_full_top",sample.parentFull.top).put("parent_full_bottom",sample.parentFull.bottom))
            // Semantics may follow a padding modifier. Compare that same
            // coordinator's clipped/unclipped bounds, not its outer node size.
            if(visible.height>=full.height-1 && visible.width>=full.width-1 &&
                full.top>=outer.top-1 && full.bottom<=outer.bottom+1)return target
            val parentInteraction=compose.onNode(SemanticsMatcher("scroll surface ${scrollParent.id}") { it.id==scrollParent.id })
            val parentFull=sample.parentFull
            // An offscreen row can belong to a fully visible inner list. In
            // that case pan that list, rather than an already settled route.
            val parentOutside=parentFull.top<outer.top-1 || parentFull.bottom>outer.bottom+1
            val targetBelow=full.bottom>outer.bottom+1
            val targetAbove=full.top<outer.top-1
            val outerCanMove=if(targetBelow)sample.outerScroll<sample.outerMax-1 else sample.outerScroll>1
            val isOuter=scrollParent.id==outerNode.id || parentOutside && (targetBelow || targetAbove) && outerCanMove
            val scrollTag=if(isOuter)"palette-content-scroll" else if(scrollParent.config.contains(SemanticsProperties.TestTag))scrollParent.config[SemanticsProperties.TestTag] else "scroll-${scrollParent.id}"
            val route=if(isOuter)outerInteraction else parentInteraction
            val viewport=if(isOuter)outer else sample.parent
            // A form can be taller than the visible route. Its bottom is not
            // the direction to an input above the viewport; follow the target.
            val required=full
            val below=required.bottom>viewport.bottom+1
            val clippedDistance=(if(below)required.bottom-viewport.bottom else viewport.top-required.top).coerceAtLeast(0f)
            val pixelsPerDp=context.resources.displayMetrics.density
            val footer=if(isOuter)compose.onAllNodesWithTag("palette-footer").fetchSemanticsNodes().singleOrNull()
                ?.boundsInRoot?.takeIf { it.height>16 && it.width>16 } else null
            // Pan visible footer chrome to move the outer route, rather than
            // scrolling an editor field or list occupying its former header.
            route.performTouchInput {
                val travel=clippedDistance+viewConfiguration.touchSlop+2*pixelsPerDp
                val footerStart=footer?.let {
                    (if(below)footer.bottom-minOf(8*pixelsPerDp,footer.height*.25f) else footer.top+minOf(8*pixelsPerDp,footer.height*.25f))-viewport.top
                }
                // A sliver of footer at the edge cannot provide a useful
                // drag in that direction. Start farther inside the viewport
                // and let nested scrolling reach its boundary first.
                val footerTravel=footerStart?.let { if(below)it-8f else viewport.height-8f-it } ?: 0f
                val useFooter=footerStart!=null && footerTravel>=minOf(travel,viewport.height*.5f)
                val start=if(useFooter)footerStart!!
                else if(isOuter)viewport.height*(if(below).85f else .15f)
                else if(below)viewport.height*.80f else viewport.height*.20f
                val end=if(below)(start-travel).coerceAtLeast(8f) else (start+travel).coerceAtMost(viewport.height-8f)
                geometry.put("pan-$tag-$it",JSONObject().put("surface",scrollTag)
                    .put("scope_height",height).put("visible_height",viewport.height).put("clipped_distance",clippedDistance)
                    .put("target_top",full.top).put("target_bottom",full.bottom)
                    .put("outer_scroll",sample.outerScroll).put("outer_scroll_max",sample.outerMax)
                    .put("parent_full_top",sample.parentFull.top).put("parent_full_bottom",sample.parentFull.bottom)
                    .put("parent_top",sample.parent.top).put("parent_bottom",sample.parent.bottom)
                    .put("start_y",start).put("end_y",end).put("footer_pan",useFooter).put("end_velocity",0))
                swipeWithVelocity(androidx.compose.ui.geometry.Offset(center.x,start),androidx.compose.ui.geometry.Offset(center.x,end),endVelocity=0f,durationMillis=400)
            }
            compose.waitForIdle()
        }
        return target
    }
    private fun search(text:String) { reveal("search").performTextReplacement(text);compose.waitForIdle() }
    private fun touch(tag:String) { target(tag);compose.onNodeWithTag(tag).assertIsEnabled().performTouchInput { click(center) };compose.waitForIdle() }
    private fun target(tag:String):Rect {
        val node=reveal(tag).assertIsDisplayed().fetchSemanticsNode()
        val (bounds,full)=compose.runOnUiThread { node.boundsInWindow to unclipped(node) }
        val minimum=48*context.resources.displayMetrics.density-1
        assertTrue("$tag has a 48dp visible width: $bounds",bounds.width>=minimum)
        assertTrue("$tag has a 48dp visible height: $bounds",bounds.height>=minimum)
        geometry.put("full-$tag",JSONArray(listOf(full.left,full.top,full.right,full.bottom)))
        assertTrue("$tag must become fully visible through touch scrolling: ${node.boundsInRoot} vs $full",
            bounds.width>=full.width-1 && bounds.height>=full.height-1)
        geometry.put(tag,JSONArray(listOf(bounds.left,bounds.top,bounds.right,bounds.bottom)))
        return bounds
    }
    private fun inputFits(tag:String) {
        val results=mutableListOf<TextLayoutResult>()
        val field=reveal(tag).assertIsDisplayed()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        val layout=results.single()
        val bounds=field.fetchSemanticsNode().boundsInWindow
        val lineHeight=layout.getLineBottom(0)-layout.getLineTop(0)
        geometry.put("text-$tag",layoutMetrics(layout))
        assertTrue("$tag must not clip the actual text line ($lineHeight > ${bounds.height})",lineHeight<=bounds.height+1)
    }
    private fun fullLabel(parent:String,label:String) {
        reveal(parent)
        val results=mutableListOf<TextLayoutResult>()
        val node=compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(parent)),useUnmergedTree=true)
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        geometry.put("label-$label",layoutMetrics(results.single()))
        visibleTextFits(results.single(),node.fetchSemanticsNode().boundsInWindow,label)
    }
    private fun visibleTextFits(layout:TextLayoutResult,bounds:Rect,label:String) {
        // Compose 1.7.6 can retain unused paragraph width after wrapping the
        // Text node to its content. Inspect drawn lines and visible bounds,
        // rather than treating that unused layout space as clipped glyphs.
        for(index in 0 until layout.lineCount) {
            assertFalse("Visible label is ellipsized: $label",layout.isLineEllipsized(index))
            assertTrue("Visible label is horizontally clipped: $label",
                layout.getLineLeft(index)>=-1 && layout.getLineRight(index)<=bounds.width+1)
            assertTrue("Visible label is vertically clipped: $label",
                layout.getLineTop(index)>=-1 && layout.getLineBottom(index)<=bounds.height+1)
        }
    }
    private fun layoutMetrics(layout:TextLayoutResult)=JSONObject()
        .put("size",JSONArray(listOf(layout.size.width,layout.size.height)))
        .put("paragraph_width",layout.multiParagraph.width).put("paragraph_height",layout.multiParagraph.height)
        .put("overflow_width",layout.didOverflowWidth).put("overflow_height",layout.didOverflowHeight)
        .put("lines",JSONArray((0 until layout.lineCount).map { index -> JSONObject()
            .put("top",layout.getLineTop(index)).put("bottom",layout.getLineBottom(index))
            .put("left",layout.getLineLeft(index)).put("right",layout.getLineRight(index))
            .put("ellipsized",layout.isLineEllipsized(index)) }))
    private fun capsule(primary:String,actions:String) {
        target(primary);target(actions)
        val first=fullBounds(primary);val second=fullBounds(actions)
        assertFalse("Primary and Actions touch targets must not overlap",first.overlaps(second))
    }
    private fun selectedGridCellFits() {
        reveal("extension-grid-cell-0")
        val grid=compose.onNodeWithTag("extension-grid").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("extension-grid-cell-0").assertIsDisplayed()
        val full=fullBounds("extension-grid-cell-0")
        val top=full.top
        val bottom=full.bottom
        geometry.put("selected-grid-cell",JSONObject().put("top",top).put("bottom",bottom)
            .put("visible_grid_top",grid.top).put("visible_grid_bottom",grid.bottom))
        assertTrue("Selected Grid caption must fit the visible viewport: $top..$bottom within ${grid.top}..${grid.bottom}",
            top>=grid.top-1 && bottom<=grid.bottom+1)
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
            .put("ime_visible",imeVisible()).put("multi_window",compose.activity.isInMultiWindowMode)
            .put("geometry",geometry).put("input","Compose pointer clicks/swipes and text/lazy-list semantics; no hardware-key navigation").toString(2))
    }
    private fun imeVisible()=androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
        ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true
    private fun copied():String=compose.activity.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString()
    private fun checked(name:String,block:()->Unit) {
        try { block() } catch(failure:Throwable) { runCatching { capture("$name-failed") };throw failure }
    }

    @Test fun launcherAndEditorRemainReadableThroughTouchActions()=checked("launcher") {
        search(noteQuery)
        target("palette-menu");target("result-$noteId");inputFits("search")
        capsule("primary-action","actions-button");fullLabel("primary-action","Open Note")
        assertTrue(compose.onNodeWithTag("result-$noteId").fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
            .joinToString().contains(alias))
        capture("launcher")
        touch("actions-button")
        compose.onNodeWithTag("inline-menu-choices").performScrollToNode(hasTestTag("action-Set keyboard shortcut"))
        target("action-Set keyboard shortcut");fullLabel("action-Set keyboard shortcut","Set keyboard shortcut")
        capture("launcher-actions")
        compose.onNodeWithTag("inline-menu-choices").performScrollToNode(hasTestTag("action-Open Note"))
        touch("action-Open Note")
        assertEquals("Only edit the owned audit note",noteId,compose.activity.store.editing?.id)
        touch("editor-title")
        compose.waitUntil(10000){imeVisible()}
        inputFits("editor-title");target("inline-back");target("cancel-editor");target("save-editor")
        assertFalse("Editor actions must not overlap",fullBounds("cancel-editor").overlaps(fullBounds("save-editor")))
        touch("editor-title");compose.onNodeWithTag("editor-title").assertIsFocused()
        compose.onNodeWithTag("editor-title").performTextReplacement("Touch audit saved note")
        if(context.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
            context.resources.configuration.smallestScreenWidthDp<600)compose.waitUntil(10000){imeVisible()}
        inputFits("editor-title")
        capture("editor")
        touch("save-editor")
        compose.waitUntil(5000){compose.activity.store.notes.any { it.id==noteId && it.title=="Touch audit saved note" }}
        compose.onNodeWithTag("search").assertIsDisplayed()
    }
    @Test fun categoryAndEmptyResultsKeepTouchNavigationAvailable()=checked("categories") {
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
    @Test fun calculatorAnswerFitsAndCopiesWithoutHardwareKeys()=checked("calculator") {
        search("10 km to mi");inputFits("search")
        val calculation=compose.activity.store.results().first { it.kind==Kind.CALC }
        val answer=calculation.title
        val results=mutableListOf<TextLayoutResult>()
        val answerNode=compose.onNodeWithText(answer,useUnmergedTree=true).performScrollTo()
        answerNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        geometry.put("calculator-answer-text",layoutMetrics(results.single()))
        visibleTextFits(results.single(),answerNode.fetchSemanticsNode().boundsInWindow,"conversion answer")
        capsule("primary-action","actions-button");capture("calculator")
        touch("primary-action");compose.waitUntil(5000){copied()==calculation.payload}
    }
    @Test fun emojiCopyCategoriesAndEmptyStateRemainUsableByTouch()=checked("emoji") {
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
        capsule("extension-primary","extension-actions")
        if(type=="Grid")selectedGridCellFits()
        capture("extension-${type.lowercase()}")
        touch("extension-actions");fullLabel("extension-action-0",actionTitle);target("extension-action-0")
        capture("extension-${type.lowercase()}-actions")
        touch("extension-action-0");compose.waitUntil(5000){copied()=="touch-layout-audit-271828"}
        touch("extension-back");compose.onNodeWithTag("search").assertIsDisplayed()
    }
    @Test fun nativeListPreservesReadableActionsAndTouchReturn()=checked("list") { extensionFlow("List") }
    @Test fun nativeGridPreservesReadableActionsAndTouchReturn()=checked("grid") { extensionFlow("Grid") }
}
