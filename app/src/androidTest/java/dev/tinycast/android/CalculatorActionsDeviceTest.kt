package dev.tinycast.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File

/** Owned calculator input only. The host snapshots/restores the clipboard
 * outside instrumentation and retains both recovery owners on cleanup failure. */
@RunWith(AndroidJUnit4::class)
class CalculatorActionsDeviceTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private val store get() = compose.activity.store
    private val evidence get() = File(context.filesDir, "reviewed-text-evidence").apply { mkdirs() }
    private val sentinel = "Owned calculator clipboard sentinel 6704"
    private var method = ""
    private var prepared = false
    private var ownedClipboardWritten = false
    private var original = emptyMap<String, Any?>()
    private lateinit var record: JSONObject
    private val pointers = JSONArray()
    private val textWitnesses = JSONArray()
    private val scrollSteps = JSONArray()
    private val nativeKeys = JSONArray()
    private var windowCallback: android.view.Window.Callback? = null

    @get:Rule val rules: TestRule = RuleChain.outerRule(TestRule { base, description ->
        object : Statement() {
            override fun evaluate() {
                method = description.methodName.lowercase(java.util.Locale.ROOT)
                Assume.assumeTrue("Use the guarded calculator cohort", hostRestoresTestClipboard())
                assertEquals("Tinycast_Tablet_API_36", device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim())
                assertTrue(device.executeShellCommand("id").startsWith("uid=2000("))
                record = JSONObject().put("case", description.methodName).put("passed", false)
                    .put("input_setup", "Compose editor test actions; pointer and native input-connection steps are recorded separately")
                try { base.evaluate() } finally {
                    record.put("pointers", pointers).put("text_line_witnesses", textWitnesses).put("scroll_steps",scrollSteps).put("native_keys",nativeKeys)
                    File(evidence, "calc-$method-case.json").writeText(record.toString(2))
                }
            }
        }
    }).around(compose)

    @Before fun prepare() {
        testAutomation() // Establish accessibility before producing a clipboard overlay.
        original = context.getSharedPreferences("palette", Context.MODE_PRIVATE).all.toMap()
        prepared = true
        compose.waitUntil(10000) { !store.loading && !store.folderSearch.loading }
        dismissClipboardForEvidence() // Settle earlier overlays before producing the owned sentinel.
        compose.runOnUiThread {
            val previous=compose.activity.window.callback
            windowCallback=previous
            compose.activity.window.callback=object : android.view.Window.Callback by previous {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    val result=previous.dispatchKeyEvent(event)
                    if(event.keyCode in listOf(KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER) && nativeKeys.length()<64)
                        nativeKeys.put(JSONObject().put("key_code",event.keyCode).put("action",event.action).put("meta",event.metaState)
                            .put("repeat",event.repeatCount).put("consumed",result).put("window_focused",compose.activity.window.decorView.hasWindowFocus()))
                    return result
                }
            }
            store.reset(); store.resetShortcuts()
            clipboard().setPrimaryClip(ClipData.newPlainText("Owned calculator fixture", sentinel))
            ownedClipboardWritten=true
        }
        dismissOwnedClipboard()
    }
    @After fun restore() {
        if (!prepared) return
        try {
            if (!record.getBoolean("passed") && ownedClipboardWritten) capture("failure")
            else if(!ownedClipboardWritten)record.put("failure_before_owned_clipboard_no_capture",true)
        } finally {
            restorePalettePreferences(context, original)
            compose.runOnUiThread { clipboard().resetClipboardForTest(); windowCallback?.let { compose.activity.window.callback=it } }
            record.put("native_preferences_restored", context.getSharedPreferences("palette", Context.MODE_PRIVATE).all == original)
        }
    }
    private fun clipboard() = context.getSystemService(ClipboardManager::class.java)
    private fun dismissOwnedClipboard() {
        val observations=ArrayDeque<String>();val actions=JSONArray()
        try {
            dismissClipboardForEvidence(onObservation={
                if(observations.size==16)observations.removeFirst()
                observations.addLast(it)
            },requireAction=true,onNodeAction={ id,success -> actions.put(JSONObject().put("node",id).put("success",success)) })
        } finally {
            record.append("owned_clipboard_dismissals",JSONObject().put("actions",actions).put("observations",JSONArray(observations.toList())))
        }
    }
    private fun assertClip(expected: String) = compose.runOnUiThread {
        assertEquals(expected, clipboard().primaryClip?.getItemAt(0)?.text?.toString())
    }
    private fun done() { record.put("passed", true) }
    private fun search(query: String, answer: String) {
        compose.onNodeWithTag("search").performTextReplacement(query)
        compose.waitForIdle()
        compose.runOnUiThread {
            assertEquals(query, store.query)
            assertEquals(answer, store.results().first { it.kind == Kind.CALC }.payload)
            assertEquals(0, store.selected)
        }
        val visible = compose.onAllNodesWithTag("result-result").fetchSemanticsNodes().singleOrNull()
            ?.let { it.layoutInfo.isPlaced && it.boundsInRoot.height>0 } == true
        if(!visible) {
            // Chaining explicitly reopens the IME. A subsequent short-landscape
            // starting fixture must hide it before asserting the result card.
            rootIme(false)
            record.append("explicit_search_result_setup_hide",query)
        }
        compose.onNodeWithTag("result-result").assertIsDisplayed().assertIsSelected()
    }
    private fun key(code: Int, modifiers: Int = 0) {
        val time = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            assertTrue(testAutomation().injectInputEvent(KeyEvent(time, SystemClock.uptimeMillis(), action, code, 0, modifiers), true))
        }
        compose.waitForIdle()
    }
    private fun connection(block: (InputConnection) -> Unit) = compose.runOnUiThread {
        block(requireNotNull(compose.activity.window.decorView.findFocus()?.onCreateInputConnection(EditorInfo())))
    }
    private fun imeVisible(): Boolean = compose.runOnUiThread {
        ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }
    private fun rootIme(visible: Boolean) {
        compose.onNodeWithTag("search").performScrollTo().performClick()
        device.waitForIdle(2000)
        compose.waitUntil(10000) { compose.runOnUiThread {
            val view=compose.activity.window.decorView.findFocus()
            val manager=compose.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            view != null && view.hasWindowFocus() && view.onCheckIsTextEditor() && manager.isActive(view) && manager.isAcceptingText
        } }
        if (compose.onAllNodesWithContentDescription("Show on-screen keyboard").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription("Show on-screen keyboard").performClick()
        } else compose.runOnUiThread {
            val controller = ViewCompat.getWindowInsetsController(compose.activity.window.decorView)
            if(!imeVisible()) controller?.show(WindowInsetsCompat.Type.ime())
        }
        awaitIme(true)
        if(!visible) {
            compose.runOnUiThread { ViewCompat.getWindowInsetsController(compose.activity.window.decorView)?.hide(WindowInsetsCompat.Type.ime()) }
            awaitIme(false)
        }
        compose.waitForIdle()
    }
    private fun awaitIme(visible: Boolean) {
        val latch=java.util.concurrent.CountDownLatch(1);val view=compose.activity.window.decorView
        var last=emptyList<Int>();var since=0L;var settled=false;val deadline=SystemClock.uptimeMillis()+10000
        val observer=object : Runnable {
            override fun run() {
                val insets=ViewCompat.getRootWindowInsets(view);val now=SystemClock.uptimeMillis()
                val geometry=listOf(if(insets?.isVisible(WindowInsetsCompat.Type.ime())==true) 1 else 0,
                    insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: -1,view.width,view.height)
                if(geometry!=last){last=geometry;since=now}
                settled=(geometry[0]==if(visible) 1 else 0) && now-since>=100
                if(settled || now>=deadline)latch.countDown() else view.postOnAnimation(this)
            }
        }
        compose.runOnUiThread { view.postOnAnimation(observer) }
        val completed=latch.await(11,java.util.concurrent.TimeUnit.SECONDS)
        compose.runOnUiThread { view.removeCallbacks(observer) }
        record.append("ime_starting_states",JSONObject().put("requested_visible",visible).put("settled",completed&&settled)
            .put("geometry",JSONArray(last)).put("stable_ms",100))
        assertTrue("Observed stable IME geometry before starting fixture",completed&&settled)
    }
    private fun rect(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
    private fun fullLines(tag: String) {
        val label=tag.removePrefix("action-")
        val text = compose.onAllNodes(hasAnyAncestor(hasTestTag(tag)) and hasText(label), useUnmergedTree=true)
            .fetchSemanticsNodes().single { it.config.contains(SemanticsActions.GetTextLayoutResult) }
        run {
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(text.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
            val layout = layouts.single()
            assertFalse(layout.hasVisualOverflow)
            repeat(layout.lineCount) {
                assertFalse(layout.isLineEllipsized(it))
                assertTrue(layout.getLineLeft(it) >= -1 && layout.getLineRight(it) <= text.boundsInRoot.width+1)
            }
            assertTrue(text.boundsInRoot.height + 1 >= layout.size.height)
            textWitnesses.put(JSONObject().put("tag", tag).put("lines", layout.lineCount).put("non_ellipsized", true)
                .put("natural_height", layout.size.height).put("visible_height", text.boundsInRoot.height))
        }
    }
    private fun pointer(tag: String, scroll: String? = null) {
        val minimum = 48 * context.resources.displayMetrics.density
        var target: androidx.compose.ui.semantics.SemanticsNode? = null
        var direction=1;var seen=false
        val caller=scroll=="palette-content-scroll"
        val attempts=if(caller) 40 else 18
        for (attempt in 0..attempts) {
            val node = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()
            if (node != null && node.layoutInfo.isPlaced && node.size.width+1 >= minimum && node.size.height+1 >= minimum &&
                node.boundsInRoot.width+1 >= node.size.width && node.boundsInRoot.height+1 >= node.size.height) {
                target=node; break
            }
            if (scroll == null || attempt == attempts) break
            val viewport = compose.onNodeWithTag(scroll).fetchSemanticsNode().boundsInRoot
            assertTrue("Placed pointer-scroll viewport", viewport.height > 0)
            // A short landscape viewport can be smaller than a natural-height
            // enlarged row. Use the existing, fully reachable keyboard control.
            if (tag != "inline-menu-keyboard" && scroll == "inline-menu-choices" &&
                viewport.height+1 < (node?.size?.height?.toFloat() ?: minimum) && imeVisible()) {
                pointer("inline-menu-keyboard")
                awaitIme(false)
                record.append("explicit_menu_keyboard_hide_for_full_target",tag)
                continue
            }
            val clipped=node?.takeIf { it.layoutInfo.isPlaced }?.boundsInRoot?.takeIf { it.height>0 }
            if(caller && node?.layoutInfo?.coordinates?.isAttached == true) {
                val origin=compose.runOnUiThread { node.layoutInfo.coordinates.localToRoot(Offset.Zero).y }
                direction=if(origin<viewport.top) -1 else 1
            }
            else if(clipped!=null) { direction=if(clipped.top<=viewport.top+1 && clipped.bottom<viewport.bottom-1) -1 else 1;seen=true }
            else if(seen) direction=-direction
            val distance=minOf(viewport.height*(if(caller) .6f else .3f),if(clipped!=null)
                (requireNotNull(node).size.height-clipped.height+8*context.resources.displayMetrics.density).coerceAtLeast(20f)
                else (if(caller) 150 else 60)*context.resources.displayMetrics.density)
            val delta=distance*direction
            val axis=compose.onNodeWithTag(scroll).fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
            val step=JSONObject().put("target",tag).put("attempt",attempt).put("viewport",viewport.toString())
                .put("target_size",node?.size.toString()).put("target_visible",node?.boundsInRoot.toString()).put("distance",delta)
                .put("axis_before",axis?.value?.invoke()).put("max_before",axis?.maxValue?.invoke())
            val owner = compose.onNode(isRoot() and hasAnyDescendant(hasTestTag(scroll)), useUnmergedTree=true)
            val root = owner.fetchSemanticsNode().boundsInRoot
            owner.performTouchInput { swipe(viewport.center + Offset(0f,delta/2)-root.topLeft,
                viewport.center - Offset(0f,delta/2)-root.topLeft, durationMillis=500) }
            compose.waitForIdle()
            step.put("axis_after",compose.onNodeWithTag(scroll).fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke())
            scrollSteps.put(step)
        }
        val node = requireNotNull(target) { "Complete 48dp pointer target required: $tag" }
        compose.onNodeWithTag(tag).assertIsEnabled()
        val owner = compose.onNode(isRoot() and hasAnyDescendant(hasTestTag(tag)), useUnmergedTree=true)
        val point = node.boundsInRoot.center-owner.fetchSemanticsNode().boundsInRoot.topLeft
        pointers.put(JSONObject().put("tag",tag).put("size",JSONArray(listOf(node.size.width,node.size.height)))
            .put("bounds",node.boundsInRoot.toString()).put("ime_visible",imeVisible()))
        if (tag in listOf("action-Put Answer in Search Bar","action-Copy Calculation","action-Copy Answer")) fullLines(tag)
        owner.performTouchInput { click(point) }
        compose.waitForIdle()
    }
    private fun actions() {
        pointer("actions-button","palette-content-scroll")
        compose.onNodeWithTag("inline-menu-search").assertIsFocused()
    }
    private fun capture(phase: String) {
        compose.waitForIdle()
        testAutomation().clearCacheForTest()
        val name = "calc-$method-$phase"
        device.dumpWindowHierarchy(File(evidence,"$name.xml"))
        assertTrue(device.takeScreenshot(File(evidence,"$name.png")))
        File(evidence,"$name-frame.json").writeText(JSONObject().put("phase",phase).put("query",store.query)
            .put("route",store.dialog).put("category",store.category).put("selected",store.selected)
            .put("build_commit",BuildConfig.BUILD_COMMIT).put("font_scale",context.resources.configuration.fontScale)
            .put("ime_visible",imeVisible()).put("pointers",pointers).put("text_line_witnesses",textWitnesses).put("scroll_steps",scrollSteps).put("native_keys",nativeKeys)
            .put("capture_order","Compose idle, native XML, then PNG; sequential observations").toString(2))
    }

    @Test fun touchChainingReturnsToFocusedSearchAndKeepsLauncherState() {
        search("12x3","36"); rootIme(true)
        val before = rect("palette")
        val unrelated = compose.runOnUiThread { listOf(store.notes,store.links,store.clips,store.files,store.pins,store.aliases,store.shortcutConfiguration) }
        actions(); capture("actions") // Same harness retains the old app's one-action state before failing.
        pointer("action-Put Answer in Search Bar","inline-menu-choices")
        compose.onNodeWithTag("search").assertIsFocused().assertTextEquals("= 36")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange,TextRange(4)))
        assertEquals("= 36",store.query); assertEquals("All",store.category); assertEquals(0,store.selected)
        assertTrue("Unrelated launcher state must remain unchanged",unrelated==compose.runOnUiThread {
            listOf(store.notes,store.links,store.clips,store.files,store.pins,store.aliases,store.shortcutConfiguration)
        })
        awaitIme(true)
        compose.onNodeWithTag("search").assertIsDisplayed()
        val after=rect("palette")
        assertEquals(before.left,after.left,1f); assertEquals(before.top,after.top,1f); assertEquals(before.width,after.width,1f)
        assertClip(sentinel); capture("chained-search")
        connection { assertTrue(it.commitText("*4",1)) }
        compose.waitForIdle(); assertEquals("144",store.results().first().payload)
        connection { assertTrue(it.performEditorAction(EditorInfo.IME_ACTION_SEARCH)) }
        compose.waitForIdle(); assertClip("144"); dismissOwnedClipboard(); capture("next-calculation")
        record.put("unrelated_launcher_state_unchanged",true).put("caret_after_answer",true).put("native_ime_next_result_copy",true)
        done()
    }
    @Test fun menuSearchCopiesTheFullCalculationThroughNativeIme() {
        search("= sqrt(81)+12x3","45"); actions(); capture("full-copy-menu")
        compose.onNodeWithTag("inline-menu-search").performTextReplacement("no such calculator action")
        key(KeyEvent.KEYCODE_ENTER); assertEquals("actions",store.dialog); assertClip(sentinel)
        compose.onNodeWithTag("inline-menu-search").performTextReplacement("calculation copy")
        connection { assertTrue(it.performEditorAction(EditorInfo.IME_ACTION_SEARCH)) }
        compose.waitForIdle(); assertClip("sqrt(81)+12 × 3 = 45")
        assertEquals("= sqrt(81)+12x3",store.query)
        compose.onNodeWithTag("search").assertIsFocused()
        dismissOwnedClipboard(); capture("full-copy-destination"); done()
    }
    @Test fun unitAnswerChainsWithItsUnitAndCanBeConvertedAgain() {
        search("1 m to cm","100 cm"); actions(); capture("unit-actions")
        pointer("action-Put Answer in Search Bar","inline-menu-choices")
        compose.onNodeWithTag("search").assertIsFocused().assertTextEquals("= 100 cm")
        assertEquals("100 cm",store.results().first().payload); capture("unit-chained")
        connection { assertTrue(it.commitText(" to m",1)) }
        compose.waitForIdle(); assertEquals("1 m",store.results().first().payload)
        key(KeyEvent.KEYCODE_ENTER,KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
        assertClip("100 cm to m = 1 m"); dismissOwnedClipboard(); capture("unit-converted"); done()
    }
    @Test fun rootAcceleratorsHandleBothEnterKeysAndNeverRepeatAnAction() {
        for (code in listOf(KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            search("6x7","42"); rootIme(false)
            val usage=store.usage["result"] ?: 0
            val time=SystemClock.uptimeMillis()
            assertTrue(testAutomation().injectInputEvent(KeyEvent(time,time,KeyEvent.ACTION_DOWN,code,0,KeyEvent.META_CTRL_ON),true))
            compose.waitForIdle(); assertEquals("= 42",store.query)
            repeat(8) { repeat -> assertTrue(testAutomation().injectInputEvent(KeyEvent(time,SystemClock.uptimeMillis(),KeyEvent.ACTION_DOWN,code,repeat+1,KeyEvent.META_CTRL_ON),true)) }
            assertTrue(testAutomation().injectInputEvent(KeyEvent(time,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,code,0,0),true))
            compose.waitForIdle(); assertEquals(usage+1,store.usage["result"]); assertClip(sentinel)
            search("6x7","42")
            key(code,KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
            assertEquals("6x7",store.query); assertClip("6 × 7 = 42")
            if(code==KeyEvent.KEYCODE_ENTER) {
                compose.runOnUiThread { clipboard().setPrimaryClip(ClipData.newPlainText("Owned calculator fixture",sentinel)) }
                dismissOwnedClipboard()
            }
        }
        record.put("enter_variants",2).put("held_repeats_per_variant",8); done()
    }
    @Test fun rootCompositionBlocksBothAcceleratorsAndImeSubmission() {
        search("= 6x7","42"); rootIme(true)
        connection { input ->
            assertTrue(input.setComposingRegion(0,2))
            val time=SystemClock.uptimeMillis()
            for (meta in listOf(KeyEvent.META_CTRL_ON,KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)) {
                for (action in listOf(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP)) compose.activity.dispatchKeyEvent(KeyEvent(time,time,action,KeyEvent.KEYCODE_ENTER,0,meta))
                assertEquals("= 6x7",store.query); assertEquals(sentinel,clipboard().primaryClip?.getItemAt(0)?.text?.toString())
            }
            assertTrue(input.performEditorAction(EditorInfo.IME_ACTION_SEARCH))
            assertEquals(sentinel,clipboard().primaryClip?.getItemAt(0)?.text?.toString())
            assertTrue(input.finishComposingText())
        }
        compose.waitForIdle(); key(KeyEvent.KEYCODE_ENTER,KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
        assertClip("6 × 7 = 42"); record.put("atomic_native_composition_protocol",true); done()
    }
    @Test fun anUnmatchedModifiedEnterRepeatNeverFallsThroughToCopyAnswer() {
        search("6x7","42");rootIme(false)
        val usage=store.usage["result"] ?: 0
        for (code in listOf(KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            for (meta in listOf(KeyEvent.META_CTRL_ON,KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)) {
                val time=SystemClock.uptimeMillis()
                assertTrue(testAutomation().injectInputEvent(KeyEvent(time,time,KeyEvent.ACTION_DOWN,code,3,meta),true))
                assertTrue(testAutomation().injectInputEvent(KeyEvent(time,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,code,0,0),true))
                compose.waitForIdle();assertClip(sentinel);assertEquals("6x7",store.query);assertEquals(usage,store.usage["result"] ?: 0)
            }
        }
        record.put("unmatched_repeat_variants",4);done()
    }
    @Test fun menuCompositionBlocksAcceleratorsAndImeUntilCommitted() {
        search("6x7","42"); actions()
        compose.onNodeWithTag("inline-menu-search").performTextReplacement("Copy Calculation")
        connection { input ->
            assertTrue(input.setComposingRegion(0,4))
            val time=SystemClock.uptimeMillis()
            for(action in listOf(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP)) compose.activity.dispatchKeyEvent(
                KeyEvent(time,time,action,KeyEvent.KEYCODE_ENTER,0,KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON))
            assertEquals("actions",store.dialog)
            assertTrue(input.performEditorAction(EditorInfo.IME_ACTION_SEARCH))
            assertEquals("actions",store.dialog); assertEquals(sentinel,clipboard().primaryClip?.getItemAt(0)?.text?.toString())
            assertTrue(input.finishComposingText())
        }
        compose.waitForIdle()
        connection { assertTrue(it.performEditorAction(EditorInfo.IME_ACTION_SEARCH)) }
        compose.waitForIdle(); assertClip("6 × 7 = 42"); done()
    }
    @Test fun stalePointerCallbacksRejectQueryCategorySelectionAndReplacementRoutes() {
        for (change in listOf("query","query-aba","selection-aba","category-aba","route")) {
            search("6x7","42"); actions()
            val click = compose.onNodeWithTag("action-Copy Calculation").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            compose.runOnUiThread {
                when(change) {
                    "query" -> store.search("8x8")
                    "query-aba" -> { store.search("8x8");store.search("6x7") }
                    "selection-aba" -> { store.selected=1;store.selected=0 }
                    "category-aba" -> { store.category="Notes";store.category="All" }
                    "route" -> { store.dialog="settings";store.focusEpoch++ }
                }
                val route=store.dialog; val query=store.query; val used=store.usage
                click.invoke()
                assertEquals(route,store.dialog);assertEquals(query,store.query);assertTrue("Usage must remain unchanged",used==store.usage)
                assertEquals(sentinel,clipboard().primaryClip?.getItemAt(0)?.text?.toString())
                store.dialog=null;store.actionCommand=null;store.focusEpoch++
            }
            compose.waitForIdle()
        }
        record.put("same_turn_stale_callback_transitions",5); done()
    }
    @Test fun oversizedAnswerDisablesChainingWithoutFallingThroughToCopyAnswer() {
        search("10^308","1"+"0".repeat(308))
        key(KeyEvent.KEYCODE_ENTER,KeyEvent.META_CTRL_ON)
        assertEquals("10^308",store.query);assertClip(sentinel)
        actions();compose.onNodeWithTag("action-Put Answer in Search Bar").assertIsNotEnabled()
        key(KeyEvent.KEYCODE_ENTER,KeyEvent.META_CTRL_ON)
        assertEquals("actions",store.dialog);assertClip(sentinel)
        compose.onNodeWithTag("inline-menu-search").performTextReplacement("Put Answer")
        key(KeyEvent.KEYCODE_NUMPAD_ENTER,KeyEvent.META_CTRL_ON)
        assertEquals("actions",store.dialog);assertClip(sentinel);capture("disabled-chain")
        compose.onNodeWithTag("inline-menu-search").performTextReplacement("Copy Calculation")
        connection { assertTrue(it.performEditorAction(EditorInfo.IME_ACTION_SEARCH)) }
        compose.waitForIdle();assertClip("10^308 = 1"+"0".repeat(308));done()
    }
    @Test fun aConfiguredCtrlShiftEnterBindingKeepsPrecedenceAtTheLauncher() {
        search("6x7","42")
        compose.runOnUiThread { store.setShortcut("calculator",KeyChord(KeyEvent.KEYCODE_ENTER,ShortcutRules.CTRL or ShortcutRules.SHIFT)) }
        key(KeyEvent.KEYCODE_ENTER,KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
        assertEquals("= ",store.query);assertClip(sentinel);done()
    }
    @Test fun copyAnswerAndBareNumberSearchKeepTheirExistingBehavior() {
        search("12x3","36"); actions();pointer("action-Copy Answer","inline-menu-choices")
        assertClip("36");assertEquals("12x3",store.query);dismissOwnedClipboard();capture("copy-answer")
        compose.onNodeWithTag("search").performTextReplacement("36")
        assertTrue(store.results().none { it.kind == Kind.CALC });compose.onNodeWithTag("result-result").assertDoesNotExist()
        capture("bare-number-search");done()
    }
    @Test fun menuAcceleratorsSelectTheirNamedActionRatherThanTheHighlightedRow() {
        search("6x7","42");actions()
        compose.onNodeWithTag("inline-menu-search").performTextReplacement("Copy Answer")
        key(KeyEvent.KEYCODE_NUMPAD_ENTER,KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
        assertClip("6 × 7 = 42");assertEquals("6x7",store.query);dismissOwnedClipboard()
        actions();key(KeyEvent.KEYCODE_ENTER,KeyEvent.META_CTRL_ON)
        assertEquals("= 42",store.query);compose.onNodeWithTag("search").assertIsFocused();done()
    }
    @Test fun obsoleteMenuOpeningsAndDisabledChordsHaveNoFallbackAction() {
        compose.runOnUiThread {
            val menus=PaletteMenus();var copies=0;var dismissals=0
            fun request(owner: Any)=PaletteMenus.Request(owner,mutableStateOf(listOf(
                PaletteChoice("copy","Copy Answer") { copies++ },
                PaletteChoice("chain","Put Answer in Search Bar",enabled=false,chord=KeyChord(KeyEvent.KEYCODE_ENTER,ShortcutRules.CTRL)) { error("Disabled") })),
                mutableStateOf(null),mutableStateOf(null),mutableStateOf({dismissals++}),mutableStateOf(null),true)
            val old=request(Any());menus.open(old);val opening=menus.activation;val choice=old.choices.value.first()
            val replacement=request(Any());menus.open(replacement)
            menus.choose(old,opening,choice);assertSame(replacement,menus.current);assertEquals(0,dismissals)
            val time=SystemClock.uptimeMillis()
            for(action in listOf(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP)) assertTrue(menus.key(KeyEvent(time,time,action,KeyEvent.KEYCODE_ENTER,0,KeyEvent.META_CTRL_ON)))
            assertEquals(0,copies);assertEquals(0,dismissals);assertSame(replacement,menus.current)
        }
        done()
    }
    @Test fun revealedLargeTextCalculatorActionsHaveCompleteVisibleLabels() {
        search("= sqrt(81)+12x3","45");actions()
        if(!imeVisible()) pointer("inline-menu-keyboard")
        awaitIme(true)
        pointer("inline-menu-keyboard");awaitIme(false)
        for((label,phase) in listOf("Put Answer in Search Bar" to "revealed-put-answer", "Copy Calculation" to "revealed-copy-calculation")) {
            compose.onNodeWithTag("inline-menu-search").performTextReplacement(label)
            compose.waitForIdle()
            val tag="action-$label";val node=compose.onNodeWithTag(tag).fetchSemanticsNode()
            val bounds=node.boundsInRoot;val minimum=48*context.resources.displayMetrics.density
            assertTrue(node.size.width+1>=minimum && node.size.height+1>=minimum)
            assertTrue(bounds.width+1>=node.size.width && bounds.height+1>=node.size.height)
            compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled()
            fullLines(tag);capture(phase)
        }
        pointer("action-Copy Calculation","inline-menu-choices")
        assertClip("sqrt(81)+12 × 3 = 45");assertEquals("= sqrt(81)+12x3",store.query)
        dismissOwnedClipboard();capture("revealed-copy-destination")
        record.put("scope","Supplementary filtered-menu rendering witnesses; editor test actions position the labels, separate matrix receipts establish pointer reachability")
        done()
    }
}
