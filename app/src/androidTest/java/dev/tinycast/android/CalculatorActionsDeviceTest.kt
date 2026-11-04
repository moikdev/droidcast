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
    private var original = emptyMap<String, Any?>()
    private lateinit var record: JSONObject
    private val pointers = JSONArray()
    private val textWitnesses = JSONArray()

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
                    record.put("pointers", pointers).put("text_line_witnesses", textWitnesses)
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
        compose.runOnUiThread {
            store.reset(); store.resetShortcuts()
            clipboard().setPrimaryClip(ClipData.newPlainText("Owned calculator fixture", sentinel))
        }
        dismissClipboardForEvidence()
    }
    @After fun restore() {
        if (!prepared) return
        try {
            if (!record.getBoolean("passed")) capture("failure")
        } finally {
            restorePalettePreferences(context, original)
            compose.runOnUiThread { clipboard().resetClipboardForTest() }
            record.put("native_preferences_restored", context.getSharedPreferences("palette", Context.MODE_PRIVATE).all == original)
        }
    }
    private fun clipboard() = context.getSystemService(ClipboardManager::class.java)
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
        compose.onNodeWithTag("search").performClick()
        if (visible && compose.onAllNodesWithContentDescription("Show on-screen keyboard").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription("Show on-screen keyboard").performClick()
        } else compose.runOnUiThread {
            val controller = ViewCompat.getWindowInsetsController(compose.activity.window.decorView)
            if (visible) controller?.show(WindowInsetsCompat.Type.ime()) else controller?.hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(10000) { imeVisible() == visible }
        compose.waitForIdle()
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
        for (attempt in 0..18) {
            val node = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()
            if (node != null && node.layoutInfo.isPlaced && node.size.width+1 >= minimum && node.size.height+1 >= minimum &&
                node.boundsInRoot.width+1 >= node.size.width && node.boundsInRoot.height+1 >= node.size.height) {
                target=node; break
            }
            if (scroll == null || attempt == 18) break
            val viewport = compose.onNodeWithTag(scroll).fetchSemanticsNode().boundsInRoot
            assertTrue("Placed pointer-scroll viewport", viewport.height > 0)
            val reverse = node != null && node.boundsInRoot.top < viewport.top+1 && node.boundsInRoot.bottom < viewport.bottom-1
            val delta = minOf(60*context.resources.displayMetrics.density, viewport.height*.35f) * if(reverse) -1 else 1
            val owner = compose.onNode(isRoot() and hasAnyDescendant(hasTestTag(scroll)), useUnmergedTree=true)
            val root = owner.fetchSemanticsNode().boundsInRoot
            owner.performTouchInput { swipe(viewport.center + Offset(0f,delta/2)-root.topLeft,
                viewport.center - Offset(0f,delta/2)-root.topLeft, durationMillis=500) }
            compose.waitForIdle()
        }
        val node = requireNotNull(target) { "Complete 48dp pointer target required: $tag" }
        val owner = compose.onNode(isRoot() and hasAnyDescendant(hasTestTag(tag)), useUnmergedTree=true)
        val point = node.boundsInRoot.center-owner.fetchSemanticsNode().boundsInRoot.topLeft
        pointers.put(JSONObject().put("tag",tag).put("size",JSONArray(listOf(node.size.width,node.size.height)))
            .put("bounds",node.boundsInRoot.toString()).put("ime_visible",imeVisible()))
        if (tag in listOf("action-Put Answer in Search Bar","action-Copy Calculation","action-Copy Answer")) fullLines(tag)
        owner.performTouchInput { click(point) }
        compose.waitForIdle()
    }
    private fun actions() {
        pointer("actions-button")
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
            .put("ime_visible",imeVisible()).put("pointers",pointers).put("text_line_witnesses",textWitnesses)
            .put("capture_order","Compose idle, native XML, then PNG; sequential observations").toString(2))
    }

    @Test fun touchChainingReturnsToFocusedSearchAndKeepsLauncherState() {
        search("12x3","36"); rootIme(true)
        val before = rect("palette")
        val unrelated = compose.runOnUiThread { listOf(store.notes,store.links,store.clips,store.files,store.pins,store.aliases,store.shortcutConfiguration) }
        actions(); capture("actions") // Same harness retains the old app's one-action state before failing.
        compose.onNodeWithTag("action-Put Answer in Search Bar").assertIsEnabled()
        pointer("action-Put Answer in Search Bar","inline-menu-choices")
        compose.onNodeWithTag("search").assertIsFocused().assertTextEquals("= 36")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange,TextRange(4)))
        assertEquals("= 36",store.query); assertEquals("All",store.category); assertEquals(0,store.selected)
        assertEquals(unrelated,compose.runOnUiThread { listOf(store.notes,store.links,store.clips,store.files,store.pins,store.aliases,store.shortcutConfiguration) })
        val after=rect("palette")
        assertEquals(before.left,after.left,1f); assertEquals(before.top,after.top,1f); assertEquals(before.width,after.width,1f)
        compose.waitUntil(10000) { imeVisible() }; assertClip(sentinel); capture("chained-search")
        connection { assertTrue(it.commitText("*4",1)) }
        compose.waitForIdle(); assertEquals("144",store.results().first().payload)
        connection { assertTrue(it.performEditorAction(EditorInfo.IME_ACTION_SEARCH)) }
        compose.waitForIdle(); assertClip("144"); dismissClipboardForEvidence(); capture("next-calculation")
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
        dismissClipboardForEvidence(); capture("full-copy-destination"); done()
    }
    @Test fun unitAnswerChainsWithItsUnitAndCanBeConvertedAgain() {
        search("1 m to cm","100 cm"); actions(); capture("unit-actions")
        pointer("action-Put Answer in Search Bar","inline-menu-choices")
        compose.onNodeWithTag("search").assertIsFocused().assertTextEquals("= 100 cm")
        assertEquals("100 cm",store.results().first().payload); capture("unit-chained")
        connection { assertTrue(it.commitText(" to m",1)) }
        compose.waitForIdle(); assertEquals("1 m",store.results().first().payload)
        key(KeyEvent.KEYCODE_ENTER,KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
        assertClip("100 cm to m = 1 m"); dismissClipboardForEvidence(); capture("unit-converted"); done()
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
            compose.runOnUiThread { clipboard().setPrimaryClip(ClipData.newPlainText("Owned calculator fixture",sentinel)) }
            dismissClipboardForEvidence()
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
                assertEquals(route,store.dialog);assertEquals(query,store.query);assertEquals(used,store.usage)
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
        assertClip("36");assertEquals("12x3",store.query);dismissClipboardForEvidence();capture("copy-answer")
        compose.onNodeWithTag("search").performTextReplacement("36")
        assertTrue(store.results().none { it.kind == Kind.CALC });compose.onNodeWithTag("result-result").assertDoesNotExist()
        capture("bare-number-search");done()
    }
    @Test fun menuAcceleratorsSelectTheirNamedActionRatherThanTheHighlightedRow() {
        search("6x7","42");actions()
        compose.onNodeWithTag("inline-menu-search").performTextReplacement("Copy Answer")
        key(KeyEvent.KEYCODE_NUMPAD_ENTER,KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
        assertClip("6 × 7 = 42");assertEquals("6x7",store.query);dismissClipboardForEvidence()
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
}
