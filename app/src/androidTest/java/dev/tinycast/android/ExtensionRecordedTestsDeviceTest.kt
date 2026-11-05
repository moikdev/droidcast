package dev.tinycast.android

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.tinycast.android.extensions.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** Owned full-source inspection. No extension command or service is executed. */
@RunWith(AndroidJUnit4::class)
class ExtensionRecordedTestsDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val name = org.junit.rules.TestName()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private val evidence get() = File(context.filesDir, "reviewed-text-evidence").apply { mkdirs() }
    private val root get() = File(context.filesDir, "extensions")
    private val leaseRoot get() = File(context.filesDir, "reviewed-text-fixture-lease")
    private val catalog get() = activity().store.extensions
    private val ids = mutableListOf<String>()
    private var primary: String? = null
    private var viewMonitor: android.app.Instrumentation.ActivityMonitor? = null
    private var prepared = false
    private var palette = emptyMap<String, Any?>()
    private var preserved = emptyList<File>()
    private var fingerprints = emptyList<Map<String,String>>()
    private var originalDirectories = emptySet<String>()
    private var payloads = JSONObject()
    private val record = JSONObject().put("scope", "Owned source-copy inspection; no extension execution, Store/service request, or current-app compatibility certification")
    private fun hash(bytes:ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun fingerprint(file:File):Map<String,String> = if(!file.exists()) emptyMap() else file.walkTopDown().associate {
        it.relativeTo(file).path to if(it.isDirectory) "directory" else hash(it.readBytes())
    }
    private fun lease():JSONObject {
        val args=InstrumentationRegistry.getArguments()
        val value=JSONObject(File(leaseRoot,"owners.json").readText())
        assertEquals("reviewed-text-device-test-v1",value.getString("fixture_kind"))
        assertEquals(args.getString("expectedTextInputLeaseOwner"),value.getString("owner_nonce"))
        assertEquals(args.getString("expectedClipboardRecoveryOwner"),value.getString("owner_nonce"))
        return value
    }
    private fun activityOrNull():MainActivity? {
        var value:MainActivity?=null
        instrumentation.runOnMainSync { value=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<MainActivity>().singleOrNull { it.window.decorView.hasWindowFocus() && !it.isFinishing } }
        return value
    }
    private fun activity():MainActivity = if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>()
            .single { it.window.decorView.hasWindowFocus() && !it.isFinishing } else requireNotNull(activityOrNull())
    private fun extension() = catalog.installed.single { it.listing.id==primary }
    private fun sourceRow(value:String):JSONObject = payloads.getJSONArray("snapshots").let { rows ->
        (0 until rows.length()).map(rows::getJSONObject).single { it.getString("name")==value }
    }
    private fun seed(value:String):Pair<String,File> {
        val source=sourceRow(value)
        val bytes=File(leaseRoot,"recorded-$value.zip").readBytes()
        assertEquals(source.getString("archive_sha256"),hash(bytes))
        val id="reviewed-text-"+UUID.randomUUID().toString().replace("-","")
        val targets=listOf(File(root,id),File(context.cacheDir,id),File(context.filesDir,"extension-data/$id"),
            File(context.applicationInfo.dataDir,"shared_prefs/extension-$id.xml"),File(context.applicationInfo.dataDir,"shared_prefs/extension-$id.xml.bak"))
        targets.forEach { assertFalse(Files.exists(it.toPath(),NOFOLLOW_LINKS)) }
        val owner=lease();owner.getJSONArray("owned_extension_ids").put(id)
        val ownerFile=File(leaseRoot,"owners.json");val temporary=File(leaseRoot,"owners.json.tmp");assertFalse(temporary.exists())
        temporary.writeText(owner.toString());assertTrue(temporary.renameTo(ownerFile));ids+=id
        val stage=File(context.cacheDir,id)
        val extracted=ExtensionArchive.extract(bytes,stage)
        val directory=File(root,id);assertTrue(extracted.renameTo(directory));stage.deleteRecursively()
        val title=if(value=="json-format") "Owned JSON Format source" else "Owned $value source"
        File(directory,".tinycast-store.json").writeText(StoreExtension(id,value,title,"Full frozen source copy; commands are not run by this inspection test",
            "Droidcast test fixture",source.getString("handle"),"","","",source.getJSONArray("commands").length()).json().toString())
        return id to directory
    }
    @Before fun prepare() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Use the guarded source-inspection runner",args.getString("reviewedTextFixture")=="true")
        assertEquals("Tinycast_Tablet_API_36",args.getString("dedicatedAvd"));lease()
        viewMonitor=instrumentation.addMonitor(android.content.IntentFilter(Intent.ACTION_VIEW),android.app.Instrumentation.ActivityResult(0,null),true)
        originalDirectories=root.listFiles().orEmpty().map { it.name }.toSet()
        preserved=root.listFiles().orEmpty().flatMap { listOf(it,File(context.filesDir,"extension-data/${it.name}"),File(context.applicationInfo.dataDir,"shared_prefs/extension-${it.name}.xml")) }
        fingerprints=preserved.map(::fingerprint);palette=context.getSharedPreferences("palette",Context.MODE_PRIVATE).all.toMap();prepared=true
        // Host-staged immutable metadata allows the common harness's old-app
        // entry control to reach the intended missing-button assertion first.
        payloads=JSONObject(File(leaseRoot,"recorded-catalog.json").readText())
        primary=seed(if(name.methodName=="weatherHistoricalIdentityMatchesTheRecordedApk") "weather" else "json-format").first
        context.startActivity(Intent(Intent.ACTION_MAIN).setComponent(ComponentName(context.packageName,MainActivity::class.java.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)),10000))
        compose.waitUntil(15000) { activityOrNull()?.store?.loading==false }
        assertTrue(catalog.installed.any { it.listing.id==primary })
        dismissClipboardForEvidence()
        instrumentation.runOnMainSync { activity().store.reset();activity().store.search("owned-recorded-tests-caller");activity().store.dialog="extensions" }
        compose.onNodeWithTag("store-installed").performClick()
        compose.onNodeWithTag("store-search").performTextReplacement(extension().listing.title)
        instrumentation.runOnMainSync { androidx.core.view.WindowCompat.getInsetsController(activity().window,activity().window.decorView)
            .hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
        compose.waitForIdle();geometry(false)
        record.put("font_scale",context.resources.configuration.fontScale).put("width_dp",context.resources.configuration.screenWidthDp)
            .put("height_dp",context.resources.configuration.screenHeightDp).put("extension_bytecode_executed",false)
    }
    private fun screenBounds(node:SemanticsNode):List<Float> {
        val bounds=node.boundsInWindow
        val offset=compose.runOnUiThread {
            val view=activity().window.decorView
            val screen=IntArray(2);val window=IntArray(2)
            view.getLocationOnScreen(screen);view.getLocationInWindow(window)
            Offset((screen[0]-window[0]).toFloat(),(screen[1]-window[1]).toFloat())
        }
        return listOf(bounds.left+offset.x,bounds.top+offset.y,bounds.right+offset.x,bounds.bottom+offset.y)
    }
    private fun capture(phase:String,anchorText:String?=null):File {
        compose.waitForIdle()
        val frames=java.util.concurrent.CountDownLatch(1)
        instrumentation.runOnMainSync { val view=activity().window.decorView
            view.postOnAnimation { view.postOnAnimation { frames.countDown() } } }
        assertTrue("Two committed display frames",frames.await(5,java.util.concurrent.TimeUnit.SECONDS));compose.waitForIdle()
        val before=anchorText?.let { screenBounds(compose.onNodeWithText(it,useUnmergedTree=true).fetchSemanticsNode()) }
        val stem="recorded-"+name.methodName.lowercase(java.util.Locale.ROOT)+"-"+phase
        val ui=device // Connect with UiDevice's flags before clearing its node cache.
        testAutomation().clearCacheForTest()
        val xml=File(evidence,"$stem.xml");ui.dumpWindowHierarchy(xml)
        val geometry=anchorText?.let { text ->
            val matches=mutableListOf<List<Int>>()
            xml.inputStream().use { input ->
                val parser=android.util.Xml.newPullParser();parser.setInput(input,"UTF-8")
                while(parser.eventType!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if(parser.eventType==org.xmlpull.v1.XmlPullParser.START_TAG&&parser.name=="node"&&parser.getAttributeValue(null,"text")==text) {
                        val bounds=requireNotNull(Regex("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]").matchEntire(parser.getAttributeValue(null,"bounds")))
                        matches+=bounds.groupValues.drop(1).map(String::toInt)
                    }
                    parser.next()
                }
            }
            val actual=matches.single()
            val after=screenBounds(compose.onNodeWithText(text,useUnmergedTree=true).fetchSemanticsNode())
            assertTrue("Visible identity bounds",after[2]>after[0]&&after[3]>after[1])
            for(index in 0..3) {
                assertEquals("Stable composed identity bounds",requireNotNull(before)[index],after[index],1f)
                assertEquals("Fresh accessibility identity bounds",after[index],actual[index].toFloat(),2f)
            }
            JSONObject().put("anchor_text_sha256",hash(text.toByteArray(Charsets.UTF_8)))
                .put("compose_screen_bounds_before",JSONArray(before)).put("compose_screen_bounds_after",JSONArray(after))
                .put("xml_screen_bounds",JSONArray(actual)).put("bounds_agree_within_pixels",2)
        }
        val image=File(evidence,"$stem.png");assertTrue(ui.takeScreenshot(image))
        val actualIme=compose.runOnUiThread { androidx.core.view.ViewCompat.getRootWindowInsets(activity().window.decorView)
            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true }
        File(evidence,"$stem-frame.json").writeText(JSONObject().put("phase",phase).put("font_scale",context.resources.configuration.fontScale).put("observed_ime_visible",actualIme)
            .put("scope",record.getString("scope")).put("synchronization","Compose idle and two display frames; accessibility cache reset before XML; XML written before PNG")
            .put("accessibility_cache_reset",android.os.Build.VERSION.SDK_INT>=34).put("accessibility_geometry",geometry?:JSONObject.NULL).toString(2))
        return image
    }
    private fun reveal(tag:String,scroll:String="recorded-tests-list") {
        val minimum=48*context.resources.displayMetrics.density
        for(attempt in 0..45) {
            val node=compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()
            if(node!=null&&node.layoutInfo.isPlaced&&node.boundsInRoot.width+1>=node.size.width&&node.boundsInRoot.height+1>=node.size.height&&node.size.height+1>=minimum&&node.size.width+1>=minimum) return
            assertTrue("Complete 48dp target: $tag",attempt<45)
            val fixed=tag in setOf("recorded-tests-keyboard","recorded-tests-clear","recorded-tests-search","extension-back")
            val inner=compose.onNodeWithTag(scroll).fetchSemanticsNode().boundsInRoot
            val surface=if(fixed||inner.height<minimum) "palette-content-scroll" else scroll
            val viewport=compose.onNodeWithTag(surface).fetchSemanticsNode().boundsInRoot
            assertTrue("Positive pointer-scroll viewport",viewport.height>0)
            val y=node?.takeIf { it.layoutInfo.isPlaced }?.let { compose.runOnUiThread { it.layoutInfo.coordinates.localToRoot(Offset.Zero).y } }
            val distance=viewport.height*.55f*(if(y!=null&&y<viewport.top) -1 else 1)
            record.append("reveal_scrolls",JSONObject().put("target",tag).put("surface",surface).put("viewport",viewport.toString())
                .put("raw_target_y",y?:JSONObject.NULL).put("distance",distance))
            swipe(surface,distance)
        }
    }
    private fun swipe(scroll:String,distance:Float) {
        val viewport=compose.onNodeWithTag(scroll).fetchSemanticsNode().boundsInRoot
        val owner=compose.onNode(isRoot() and hasAnyDescendant(hasTestTag(scroll)),useUnmergedTree=true)
        val origin=owner.fetchSemanticsNode().boundsInRoot.topLeft
        owner.performTouchInput { swipe(viewport.center+Offset(0f,distance/2)-origin,viewport.center-Offset(0f,distance/2)-origin,350) }
        compose.waitForIdle()
    }
    private fun tap(tag:String,scroll:String="recorded-tests-list") {
        reveal(tag,scroll)
        val node=compose.onNodeWithTag(tag).fetchSemanticsNode()
        record.append("pointer_taps",JSONObject().put("tag",tag).put("size",JSONArray(listOf(node.size.width,node.size.height))).put("visible",node.boundsInRoot.toString()))
        compose.onNodeWithTag(tag).assertIsEnabled().performTouchInput { click(center) };compose.waitForIdle()
    }
    private fun enter(captureBefore:Boolean=false) {
        if(captureBefore)capture("installed-before-entry")
        // First new-API assertion: the old-app common control fails here.
        val entry="recorded-tests-"+extension().listing.name
        compose.onNodeWithTag(entry).assertExists()
        tap(entry,"store-results")
        compose.waitUntil(20000) {
            try {
                // Readiness can be below a tall intro. Realize its lazy item;
                // this is positioning setup, not a second entry tap.
                compose.onNodeWithTag("recorded-tests-list").performScrollToNode(
                    hasTestTag("recorded-tests-match") or hasTestTag("recorded-tests-error"))
                true
            } catch (_: AssertionError) { false }
        }
        assertNull(catalog.running);assertNull(catalog.runtimeTree)
    }
    private fun geometry(wanted:Boolean?=null):Boolean {
        val view=activity().window.decorView
        val done=java.util.concurrent.CountDownLatch(1)
        var stable=false;var visible=false;var last=emptyList<Int>();var since=0L
        val deadline=android.os.SystemClock.uptimeMillis()+11000
        val observer=object:Runnable { override fun run() {
            val insets=androidx.core.view.ViewCompat.getRootWindowInsets(view)
            visible=insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true
            val values=listOf(view.width,view.height,insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom?:0,if(visible)1 else 0)
            val now=android.os.SystemClock.uptimeMillis()
            if(values!=last) { last=values;since=now }
            if(now-since>=200&&(wanted==null||visible==wanted)) { stable=true;done.countDown() }
            else if(now>=deadline)done.countDown() else view.postOnAnimation(this)
        } }
        instrumentation.runOnMainSync { view.postOnAnimation(observer) }
        val completed=done.await(12,java.util.concurrent.TimeUnit.SECONDS)
        instrumentation.runOnMainSync { view.removeCallbacks(observer) }
        assertTrue("Observed stable actual IME/window geometry",completed&&stable)
        record.append("geometry",JSONObject().put("wanted",wanted?:JSONObject.NULL).put("visible",visible).put("values",JSONArray(last)).put("stable_ms",200))
        return visible
    }
    private fun filtered(text:String) {
        compose.onNodeWithTag("recorded-tests-search").performTextReplacement(text);compose.waitForIdle()
        // Editor setup must not leave a delayed IME request racing the text
        // witnesses. Actual pointer show/hide is a separate protocol below.
        compose.onNodeWithTag("extension-palette").performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        compose.waitForIdle()
        instrumentation.runOnMainSync { androidx.core.view.WindowCompat.getInsetsController(activity().window,activity().window.decorView)
            .hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
        geometry(false)
        record.put("filter_setup","Semantic text replacement; palette focus; native IME hide; stable actual closed-IME geometry. This is not physical typing or a pointer-hide claim.")
    }
    private fun passed() { record.put("passed",true).put("inspection_did_not_start_runtime",catalog.running==null&&catalog.runtimeTree==null) }
    private fun checkNatural(text:String) {
        compose.onNodeWithTag("recorded-tests-list").performScrollToNode(hasText(text))
        val interaction=compose.onNodeWithText(text,useUnmergedTree=true)
        interaction.performScrollTo();compose.waitForIdle()
        val node=interaction.fetchSemanticsNode();val layouts=mutableListOf<TextLayoutResult>()
        assertTrue(requireNotNull(node.config[SemanticsActions.GetTextLayoutResult].action).invoke(layouts))
        val layout=layouts.single();assertFalse(layout.didOverflowHeight)
        assertEquals(layout.size.height,node.size.height)
        repeat(layout.lineCount) { line ->
            assertFalse(layout.isLineEllipsized(line))
            assertTrue(layout.getLineLeft(line)>=-1&&layout.getLineRight(line)<=node.size.width+1)
            var reached=false
            for(attempt in 0..40) {
                val current=interaction.fetchSemanticsNode()
                val viewport=compose.onNodeWithTag("recorded-tests-list").fetchSemanticsNode().boundsInRoot
                val origin=compose.runOnUiThread { current.layoutInfo.coordinates.localToRoot(Offset.Zero) }
                val top=origin.y+layout.getLineTop(line);val bottom=origin.y+layout.getLineBottom(line)
                if(top>=viewport.top-1&&bottom<=viewport.bottom+1) { reached=true;break }
                swipe("recorded-tests-list",((if(top<viewport.top) top-viewport.top else bottom-viewport.bottom)+if(top<viewport.top)-4 else 4).coerceIn(-viewport.height*.5f,viewport.height*.5f))
            }
            assertTrue("Full natural line $line of $text",reached)
        }
        record.append("natural_text_witnesses",JSONObject().put("text",text).put("lines",layout.lineCount).put("all_lines_reached",true).put("positioning_setup","Semantic scroll-to-text, then pointer swipes for sequential full-line reachability"))
    }
    @Test fun matchingRecordsSearchDetailsAndReturn() {
        enter(true)
        compose.onNodeWithTag("recorded-tests-match").assertTextContains("Installed files match",substring=true)
        capture("matched-source")
        filtered("clipboard")
        val id="batch001-json-format-formatclipboard"
        tap("recorded-details-$id");compose.waitUntil(10000) { compose.onAllNodesWithText("Sample inputs and configured preferences from the published workflow report.",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty() };capture("workflow-details")
        tap("extension-back")
        compose.onNodeWithTag("store-search").assertTextEquals("Owned JSON Format source")
        compose.onNodeWithTag("recorded-tests-json-format").assertExists()
        passed();record.put("installed_search_preserved",true);capture("installed-return")
    }
    @Test fun clearSearchShowsCloseAndRestoresResults() {
        enter();filtered("manual JSON");reveal("recorded-tests-clear")
        val bounds=screenBounds(compose.onNodeWithTag("recorded-tests-clear").fetchSemanticsNode())
        val image=capture("clear-search-control")
        val bitmap=requireNotNull(android.graphics.BitmapFactory.decodeFile(image.absolutePath))
        try {
            val density=context.resources.displayMetrics.density
            val tint=PaletteTheme.secondary.toArgb()
            val centerX=(bounds[0]+bounds[2])/2;val centerY=(bounds[1]+bounds[3])/2
            // The secondary tint is translucent. Sample the same control's
            // background outside the 18dp glyph, then composite its RGB.
            val background=bitmap.getPixel((centerX+8*density).roundToInt(),(centerY+8*density).roundToInt())
            val alpha=android.graphics.Color.alpha(tint)/255f
            fun blend(front:Int,back:Int)=(front*alpha+back*(1-alpha)).roundToInt()
            val expected=android.graphics.Color.rgb(blend(android.graphics.Color.red(tint),android.graphics.Color.red(background)),
                blend(android.graphics.Color.green(tint),android.graphics.Color.green(background)),
                blend(android.graphics.Color.blue(tint),android.graphics.Color.blue(background)))
            fun ink(dx:Float,dy:Float):Int {
                val x=(centerX+dx*density).roundToInt()
                val y=(centerY+dy*density).roundToInt()
                val radius=maxOf(1,(density*.8f).roundToInt())
                var count=0
                for(py in y-radius..y+radius)for(px in x-radius..x+radius) {
                    assertTrue(px in 0 until bitmap.width&&py in 0 until bitmap.height)
                    val pixel=bitmap.getPixel(px,py)
                    if(kotlin.math.abs(android.graphics.Color.red(pixel)-android.graphics.Color.red(expected))<=32&&
                       kotlin.math.abs(android.graphics.Color.green(pixel)-android.graphics.Color.green(expected))<=32&&
                       kotlin.math.abs(android.graphics.Color.blue(pixel)-android.graphics.Color.blue(expected))<=32)count++
                }
                return count
            }
            val diagonal=listOf(ink(-3f,-3f),ink(3f,-3f),ink(-3f,3f),ink(3f,3f))
            val axes=listOf(ink(0f,-3f),ink(0f,3f),ink(-3f,0f),ink(3f,0f))
            record.put("clear_glyph_pixels",JSONObject().put("diagonal_ink",JSONArray(diagonal)).put("axis_ink",JSONArray(axes))
                .put("screen_bounds",JSONArray(bounds)).put("background_argb",background).put("composited_tint_argb",expected).put("channel_tolerance",32)
                .put("pixel_probe_scope","Four diagonal and four axis neighborhoods of the actual 18dp clear glyph; not a full-image comparison"))
            assertTrue("Clear search must draw a close glyph rather than the fallback plus",diagonal.all { it>0 }&&axes.all { it==0 })
        } finally { bitmap.recycle() }
        tap("recorded-tests-clear");compose.onNodeWithTag("recorded-tests-search").assertTextEquals("")
        compose.onNodeWithTag("recorded-tests-list").performScrollToNode(hasTestTag("recorded-test-batch001-json-format-index"))
        compose.onNodeWithTag("recorded-test-batch001-json-format-index").assertExists();capture("clear-search-restored-results")
        record.put("clear_search_pointer_restores_results",true);passed()
    }
    @Test fun allNineCompleteSourceCopiesMatchWithoutExecution() {
        val source=ExtensionWorkflowSource { context.assets.open("extension-workflow-evidence-v1.json") }
        val snapshots=ExtensionWorkflowEvidence.parse(context.assets.open("extension-workflow-evidence-v1.json").use { it.readBytes() }).snapshots
        val results=JSONArray()
        for(snapshot in snapshots) {
            val directory=if(snapshot.name=="json-format")extension().directory else seed(snapshot.name).second
            val inspected=source.inspect(snapshot.handle,snapshot.name,directory,root) {}
            assertEquals(1,inspected.matches.size);assertEquals(snapshot.payload.sha256,inspected.matches.single().payload.sha256)
            results.put(JSONObject().put("name",snapshot.name).put("archive_sha256",snapshot.archiveSha256).put("files",snapshot.payload.files.size))
        }
        record.put("native_complete_payload_matches",results);enter();capture("source-smoke");passed()
    }
    @Test fun assetOnlyEditStaysUnknownAndOtherVersionsStayHistorical() {
        val directory=extension().directory
        val candidate=directory.walkTopDown().first { it.isFile&&it.relativeTo(directory).path.startsWith("assets/") }
        candidate.appendText("\nOwned inspection-only asset modification")
        enter();compose.onNodeWithTag("recorded-tests-match").assertTextContains("do not match",substring=true)
        capture("asset-change-unknown")
        tap("recorded-tests-other-versions")
        filtered("manual JSON")
        compose.onNodeWithTag("recorded-tests-list").performScrollToNode(
            hasText("No commands match this search.") or hasText("No installed commands match this search."))
        capture("other-source-empty-message")
        compose.onNodeWithText("No installed commands match this search.").assertExists()
        checkNatural("No installed commands match this search.")
        compose.onNodeWithTag("recorded-tests-list").performScrollToNode(hasTestTag("recorded-test-batch001-json-format-index"))
        compose.onNodeWithTag("recorded-test-batch001-json-format-index").assertExists();capture("other-source-historical")
        record.put("asset_only_change_not_promoted",true);passed()
    }
    @Test fun missingCommandDoesNotBorrowRecords() {
        assertTrue(File(extension().directory,"index.js").delete())
        enter();compose.onNodeWithTag("recorded-tests-match").assertTextContains("do not match",substring=true)
        capture("missing-command-unknown");passed()
    }
    @Test fun linkedPayloadShowsRetryError() {
        val directory=extension().directory;val index=File(directory,"index.js");assertTrue(index.delete())
        Files.createSymbolicLink(index.toPath(),File(directory,"formatClipboard.js").toPath())
        enter();compose.onNodeWithTag("recorded-tests-error").assertTextContains("Installed files could not",substring=true)
        capture("linked-source-error")
        tap("recorded-tests-retry");compose.waitUntil(20000) { compose.onAllNodesWithTag("recorded-tests-error").fetchSemanticsNodes().isNotEmpty() };passed()
    }
    private fun replaceInstalled(next:InstalledExtension) {
        val field=catalog.javaClass.getDeclaredField("installed\$delegate").apply { isAccessible=true }
        @Suppress("UNCHECKED_CAST") val state=field.get(catalog) as MutableState<List<InstalledExtension>>
        state.value=state.value.map { if(it.listing.id==primary) next else it }
    }
    @Test fun unknownListingIdentityCannotBorrowThePayloadMatch() {
        val old=extension();val next=old.copy(listing=old.listing.copy(handle="owned-unknown-author"))
        instrumentation.runOnMainSync { replaceInstalled(next) }
        enter();compose.onNodeWithTag("recorded-tests-match").assertTextContains("No source version",substring=true)
        capture("unknown-listing");passed()
    }
    @Test fun replacementAndQueryChangeRejectQueuedActions() {
        enter();filtered("manual JSON")
        val id="batch001-json-format-index";reveal("recorded-details-$id")
        val details=requireNotNull(compose.onNodeWithTag("recorded-details-$id").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val reportAction=requireNotNull(compose.onNodeWithTag("recorded-report-$id").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val queryAction=requireNotNull(compose.onNodeWithTag("recorded-tests-search").fetchSemanticsNode().config[SemanticsActions.SetText].action)
        compose.mainClock.autoAdvance=false
        try { instrumentation.runOnMainSync { queryAction(androidx.compose.ui.text.AnnotatedString("owned-no-match"));details();reportAction() } }
        finally { compose.mainClock.autoAdvance=true }
        compose.waitForIdle();compose.onNodeWithTag("recorded-tests-search").assertTextEquals("owned-no-match")
        compose.onNodeWithTag("recorded-tests-list").performScrollToNode(hasText("No installed commands match this search."))
        compose.onNodeWithText("No installed commands match this search.").assertExists()
        filtered("manual JSON");reveal("recorded-details-$id")
        val replacementDetails=requireNotNull(compose.onNodeWithTag("recorded-details-$id").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val replacementReport=requireNotNull(compose.onNodeWithTag("recorded-report-$id").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        instrumentation.runOnMainSync { replacementDetails() };compose.waitForIdle()
        compose.onNodeWithText("Sample inputs and configured preferences from the published workflow report.",useUnmergedTree=true).assertExists()
        instrumentation.runOnMainSync { replacementDetails() };compose.waitForIdle()
        compose.mainClock.autoAdvance=false
        try { instrumentation.runOnMainSync { replaceInstalled(extension().copy(manifest=JSONObject(extension().manifest.toString())));replacementDetails();replacementReport() } }
        finally { compose.mainClock.autoAdvance=true }
        compose.onNodeWithTag("recorded-tests-changed").assertExists();capture("replacement-rejected")
        tap("extension-back");compose.onNodeWithTag("store-search").assertTextEquals("Owned JSON Format source")
        record.put("queued_old_actions_rejected",true);passed()
    }
    private fun injected(source:(() -> java.io.InputStream)?, reportFailure:Boolean):java.util.concurrent.atomic.AtomicInteger {
        val calls=java.util.concurrent.atomic.AtomicInteger(0);val current=activity();val ext=extension()
        val wrapped=object:ContextWrapper(current) { override fun startActivity(intent:Intent) {
            assertEquals(Intent.ACTION_VIEW,intent.action);assertTrue(requireNotNull(intent.dataString).startsWith("https://github.com/moikdev/droidcast/blob/"))
            calls.incrementAndGet();record.put("requested_report_url",intent.dataString)
            if(reportFailure)throw android.content.ActivityNotFoundException("Owned no-handler fixture")
        } }
        instrumentation.runOnMainSync { current.setContent { MaterialTheme(colorScheme=androidx.compose.material3.darkColorScheme(
            primary=PaletteTheme.ink,onPrimary=PaletteTheme.background,surface=PaletteTheme.surface,background=PaletteTheme.background,
            onSurface=PaletteTheme.ink,onBackground=PaletteTheme.ink,outline=PaletteTheme.border,secondary=PaletteTheme.secondary,
            surfaceContainer=PaletteTheme.surface)) { CompositionLocalProvider(LocalContext provides wrapped) {
            PaletteMenuHost(current.store) { ExtensionRecordedTests(current.store,ext,{true},{current.store.dialog=null},source?.let(::ExtensionWorkflowSource)) }
        } } } }
        compose.waitForIdle();return calls
    }
    @Test fun unavailableCatalogAndReportErrorsRemainExplicit() {
        injected({ throw IOException("Owned unavailable catalog") },false)
        compose.waitUntil(10000) { compose.onAllNodesWithTag("recorded-tests-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("recorded-tests-error").assertTextContains("No compatibility result has been determined",substring=true)
        capture("injected-catalog-error");tap("recorded-tests-retry")
        compose.onNodeWithTag("recorded-tests-error").assertExists()
        val calls=injected(null,true)
        compose.waitUntil(10000) { compose.onAllNodesWithTag("recorded-tests-match").fetchSemanticsNodes().isNotEmpty() }
        filtered("manual JSON");tap("recorded-report-batch001-json-format-index")
        assertEquals(1,calls.get());compose.onNodeWithTag("recorded-tests-link-error").assertExists()
        capture("injected-report-handler-error")
        record.put("injected_error_scope","Production Compose component with a failing local catalog stream and a ContextWrapper that rejects explicit ACTION_VIEW; no real browser destination is certified")
        passed()
    }
    @Test fun criticalTextAndTouchTargetsAreReachable() {
        enter();checkNatural("These are earlier observations of specific workflows. Matching source files does not verify your current app or device.")
        filtered("manual JSON")
        val id="batch001-json-format-index"
        checkNatural("Worked in recorded test")
        checkNatural("Use the manual JSON formatter and verify its clipboard output.")
        tap("recorded-details-$id");compose.waitUntil(10000) { compose.onAllNodesWithText("Only this listed workflow was checked. Other arguments/actions and your current app/device remain unverified.",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty() }
        checkNatural("Only this listed workflow was checked. Other arguments/actions and your current app/device remain unverified.")
        capture("enlarged-historical-details")
        tap("extension-back");record.put("natural_text_and_full_targets",true);passed()
    }
    @Test fun searchAndKeyboardRemainReachableAfterScrolling() {
        File(extension().directory,"owned-extra.txt").writeText("Owned mismatched source fixture")
        enter();tap("recorded-tests-other-versions")
        capture("scrolled-before-search-assertion")
        compose.onNodeWithTag("recorded-tests-search").assertIsDisplayed()
        tap("recorded-tests-keyboard");geometry(true)
        val view=activity().window.decorView
        val bottom=compose.runOnUiThread { requireNotNull(androidx.core.view.ViewCompat.getRootWindowInsets(view)).getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom }
        val button=compose.onNodeWithTag("recorded-tests-keyboard").fetchSemanticsNode().boundsInWindow
        assertTrue("Fixed keyboard target stays above the actual IME",button.bottom<=view.height-bottom+1)
        capture("fixed-search-ime-open");tap("recorded-tests-keyboard");geometry(false)
        record.put("fixed_search_and_keyboard_reachable",true);passed()
    }
    @Test fun weatherHistoricalIdentityMatchesTheRecordedApk() {
        enter();filtered("Tokyo")
        tap("recorded-details-weather-tokyo-observation")
        compose.onNodeWithTag("recorded-tests-list").performScrollToNode(hasText("Test source:",substring=true))
        val textNode=compose.onAllNodes(hasText("Test source:",substring=true),useUnmergedTree=true).fetchSemanticsNodes()
            .single { it.config.contains(SemanticsActions.GetTextLayoutResult) }
        val text=textNode.config[SemanticsProperties.Text].joinToString("\n") { it.text }
        val observedSource=requireNotNull(Regex("Test source: ([0-9a-f]{40})").find(text)).groupValues[1]
        val observedApk=requireNotNull(Regex("Test APK: ([0-9a-f]{64})").find(text)).groupValues[1]
        val layouts=mutableListOf<TextLayoutResult>()
        assertTrue(requireNotNull(textNode.config[SemanticsActions.GetTextLayoutResult].action).invoke(layouts))
        val layout=layouts.single()
        for((label,value) in listOf("Test source: " to observedSource,"Test APK: " to observedApk)) {
            val start=text.indexOf(label);val end=start+label.length+value.length-1
            for(line in layout.getLineForOffset(start)..layout.getLineForOffset(end)) {
                assertFalse(layout.isLineEllipsized(line))
                assertTrue(layout.getLineLeft(line)>=-1&&layout.getLineRight(line)<=textNode.size.width+1)
                var reached=false
                for(attempt in 0..40) {
                    val now=compose.onNodeWithText(text,useUnmergedTree=true).fetchSemanticsNode()
                    val viewport=compose.onNodeWithTag("recorded-tests-list").fetchSemanticsNode().boundsInRoot
                    val origin=compose.runOnUiThread { now.layoutInfo.coordinates.localToRoot(Offset.Zero) }
                    val top=origin.y+layout.getLineTop(line);val bottom=origin.y+layout.getLineBottom(line)
                    if(top>=viewport.top-1&&bottom<=viewport.bottom+1) { reached=true;break }
                    val raw=if(top<viewport.top) top-viewport.top else bottom-viewport.bottom
                    val magnitude=maxOf(kotlin.math.abs(raw)+4,24*context.resources.displayMetrics.density).coerceAtMost(viewport.height*.5f)
                    swipe("recorded-tests-list",if(raw<0)-magnitude else magnitude)
                }
                assertTrue("Complete recorded identity line $line",reached)
            }
            capture(if(label.startsWith("Test source"))"weather-test-source" else "weather-test-apk",text)
        }
        record.put("observed_historical_test_source",observedSource).put("observed_historical_test_apk",observedApk)
            .put("identity_setup","Semantic text positioning; native pointer scrolling for complete identity lines; no Weather code or service is executed")
        assertEquals("Weather historical source must match its recorded test APK","d806004eb4d65a43e08c3966fff16ebd79d9affb",observedSource)
        assertEquals("e26b3fe6b97575edbbc2fda121b6615d65316264db48b87dd5d87614ea16969d",observedApk)
        passed()
    }
    @After fun restore() {
        if(!prepared)return
        try { if(!record.optBoolean("passed")&&activityOrNull()!=null)capture("failure") }
        catch(failure:Throwable) { record.put("failure_capture_error_type",failure.javaClass.simpleName) }
        finally {
            compose.mainClock.autoAdvance=true
            val viewRequests=viewMonitor?.let { monitor -> instrumentation.removeMonitor(monitor);monitor.hits } ?: 0;viewMonitor=null
            instrumentation.runOnMainSync {
                listOf(Stage.CREATED,Stage.STARTED,Stage.RESUMED,Stage.PAUSED,Stage.STOPPED)
                    .flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.filterIsInstance<MainActivity>().distinct()
                    .forEach { it.store.extensions.close();it.store.dialog=null;it.finish() }
            }
            val deadline=android.os.SystemClock.uptimeMillis()+10000;var remaining=true
            while(remaining&&android.os.SystemClock.uptimeMillis()<deadline) {
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync { remaining=listOf(Stage.CREATED,Stage.STARTED,Stage.RESUMED,Stage.PAUSED,Stage.STOPPED)
                    .flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.any { it is MainActivity } }
                if(remaining)android.os.SystemClock.sleep(50)
            }
            assertFalse("Close owned activities before restoring files",remaining)
            restorePalettePreferences(context,palette)
            val owned=lease().getJSONArray("owned_extension_ids").let { array -> (0 until array.length()).map(array::getString).toSet() }
            for(id in ids) {
                assertTrue(id in owned)
                listOf(File(root,id),File(context.cacheDir,id),File(context.filesDir,"extension-data/$id")).forEach { it.deleteRecursively();assertFalse(Files.exists(it.toPath(),NOFOLLOW_LINKS)) }
                context.deleteSharedPreferences("extension-$id")
                listOf(".xml",".xml.bak").forEach { assertFalse(Files.exists(File(context.applicationInfo.dataDir,"shared_prefs/extension-$id$it").toPath(),NOFOLLOW_LINKS)) }
            }
            assertEquals(originalDirectories,root.listFiles().orEmpty().map { it.name }.toSet());assertEquals(fingerprints,preserved.map(::fingerprint))
            record.put("owned_source_copies_removed",true).put("preexisting_installations_and_data_preserved",true)
                .put("clipboard_scope","No clipboard reads or writes in this class; host independently restores/verifies its durable recovery snapshot")
            File(evidence,"recorded-${name.methodName.lowercase(java.util.Locale.ROOT)}-record.json").writeText(record.toString(2))
            assertEquals("No uncaptured ACTION_VIEW request",0,viewRequests)
        }
    }
}
