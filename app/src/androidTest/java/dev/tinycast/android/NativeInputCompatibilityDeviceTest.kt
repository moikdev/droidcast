package dev.tinycast.android

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextDecoration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** Owned observations and separately labeled contracts; no production-fix credit. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class NativeInputCompatibilityDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val name = org.junit.rules.TestName()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private val evidence get() = File(context.filesDir, "native-input-evidence")
    private val trace = JSONArray()
    private val record = JSONObject().put("diagnostic_only", true)
        .put("scope", "Owned native input observations; semantic/model setup and injected keys are not physical typing; no clipboard or extension execution")
    private var prepared = false
    private var palette = emptyMap<String, Any?>()
    private var emoji = emptyMap<String, Any?>()
    private val hooks = mutableListOf<Triple<MainActivity, Window.Callback, Window.Callback>>()
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private fun ui(block: (MainActivity) -> Unit) = compose.activityRule.scenario.onActivity { block(it) }

    @Before fun prepare() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Use the owned native-input runner", args.getString("nativeInputFixture") == "true")
        val nonce = requireNotNull(args.getString("nativeInputOwner"))
        assertTrue(nonce.matches(Regex("[0-9a-f]{32}")))
        val owner = JSONObject(File(context.filesDir, "native-input-lease/owner.json").readText())
        assertEquals("native-input-v1", owner.getString("kind")); assertEquals(nonce, owner.getString("nonce"))
        assertEquals("native-input-$nonce", owner.getString("extension_id"))
        assertEquals(Build.VERSION.SDK_INT == 26, owner.getBoolean("fresh_disposable_avd"))
        assertEquals(args.getString("nativeInputMode"), owner.getString("input_mode"))
        compose.waitUntil(15000) { !compose.activity.store.loading && !compose.activity.store.emoji.loading }
        palette = context.getSharedPreferences("palette", Context.MODE_PRIVATE).all.toMap()
        emoji = context.getSharedPreferences("emoji", Context.MODE_PRIVATE).all.toMap()
        prepared = true
        ui { it.store.reset(); hook(it) }
        record.put("case", name.methodName).put("api", Build.VERSION.SDK_INT)
            .put("font_scale", context.resources.configuration.fontScale)
            .put("default_ime", android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD))
    }

    private fun hook(activity: MainActivity) {
        if (hooks.any { it.first === activity }) return
        val original = activity.window.callback
        val wrapper = object : Window.Callback by original {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                val handled = original.dispatchKeyEvent(event)
                trace.put(JSONObject().put("kind", "window-key").put("code", event.keyCode).put("action", event.action)
                    .put("flags", event.flags).put("source", event.source).put("device", event.deviceId)
                    .put("repeat", event.repeatCount).put("handled", handled).put("uptime_ms", SystemClock.uptimeMillis()))
                return handled
            }
        }
        hooks += Triple(activity, original, wrapper); activity.window.callback = wrapper
    }

    private fun observe(stage: String, tag: String) {
        compose.waitForIdle()
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val text = node.config[SemanticsProperties.EditableText]
        val range = node.config[SemanticsProperties.TextSelectionRange]
        val styles = JSONArray(text.spanStyles.map { style -> JSONObject().put("start", style.start).put("end", style.end)
            .put("decoration", style.item.textDecoration?.toString()).put("style", style.item.toString()) })
        val value = JSONObject().put("kind", "observation").put("stage", stage).put("tag", tag)
            .put("text_length", text.length).put("text_sha256", digest(text.text)).put("text_prefix", text.text.take(128))
            .put("plain_annotated_equal", text == AnnotatedString(text.text)).put("span_styles", styles)
            .put("paragraph_styles", text.paragraphStyles.toString()).put("string_annotations", text.getStringAnnotations(0, text.length).toString())
            .put("selection_start", range.start).put("selection_end", range.end)
            .put("focused", node.config.getOrNull(SemanticsProperties.Focused) ?: JSONObject.NULL)
            .put("no_input_connection_created_for_this_observation", true)
        if (text.length <= 128) value.put("codepoints", JSONArray(text.text.codePoints().toArray().toList()))
        ui {
            hook(it)
            val imm = it.getSystemService(InputMethodManager::class.java)
            value.put("route", it.store.dialog ?: JSONObject.NULL).put("window_focus", it.hasWindowFocus())
                .put("query", it.store.query).put("emoji_query", it.store.emoji.query).put("emoji_selected", it.store.emoji.selected)
                .put("ime_accepting_text", imm.isAcceptingText).put("uptime_ms", SystemClock.uptimeMillis())
            trace.put(value)
        }
    }

    private fun capture(phase: String) {
        compose.waitForIdle(); evidence.mkdirs()
        val stem = name.methodName.lowercase() + "-" + phase
        testAutomation().clearCacheForTest()
        device.dumpWindowHierarchy(File(evidence, "$stem.xml"))
        assertTrue(device.takeScreenshot(File(evidence, "$stem.png")))
        File(evidence, "$stem-frame.json").writeText(JSONObject().put("diagnostic_only", true).put("phase", phase)
            .put("synchronization", "Compose idle; hierarchy before PNG; XML geometry not an independent visibility witness")
            .put("scope", record.getString("scope")).toString(2))
    }

    private fun key(code: Int, direct: Boolean, meta: Int = 0) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val event = KeyEvent(down, SystemClock.uptimeMillis(), action, code, 0, meta,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
            if (direct) ui { trace.put(JSONObject().put("kind", "direct-key").put("code", code)
                .put("action", action).put("handled", it.dispatchKeyEvent(event))) }
            else trace.put(JSONObject().put("kind", "injected-key").put("code", code).put("action", action)
                .put("accepted", testAutomation().injectInputEvent(event, true)))
        }
    }

    @Test fun recordDefaultImeAndControlledCompositionNavigation() {
        ui { it.execute(Command("emoji", "Emoji & Symbols", "")) }
        compose.onNodeWithTag("emoji-search").performTextReplacement("face")
        observe("default-ime-after-semantic-replacement", "emoji-search")
        capture("default-before")
        key(KeyEvent.KEYCODE_DPAD_RIGHT, false); observe("default-after-injected-right", "emoji-search")
        key(KeyEvent.KEYCODE_ESCAPE, false); observe("default-after-injected-escape", "emoji-search")
        key(KeyEvent.KEYCODE_ESCAPE, true)
        ui { if (it.store.dialog != "emoji") it.execute(Command("emoji", "Emoji & Symbols", "")) }
        compose.onNodeWithTag("emoji-search").performTextReplacement("face")
        var connection: InputConnection? = null
        try {
            ui {
                val info = EditorInfo()
                connection = requireNotNull(it.window.decorView.findFocus()?.onCreateInputConnection(info))
                assertTrue(connection!!.finishComposingText())
                trace.put(JSONObject().put("kind", "controlled-input-connection-created").put("input_type", info.inputType)
                    .put("ime_options", info.imeOptions).put("initial_selection_start", info.initialSelStart).put("initial_selection_end", info.initialSelEnd))
            }
            observe("controlled-finish-composition", "emoji-search")
            key(KeyEvent.KEYCODE_DPAD_RIGHT, false); observe("controlled-after-injected-right", "emoji-search")
            key(KeyEvent.KEYCODE_DPAD_DOWN, false); observe("controlled-after-injected-down", "emoji-search")
            capture("committed-navigation")
            ui {
                assertTrue(connection!!.setSelection(0, it.store.emoji.query.length))
                assertTrue(connection!!.setComposingText("にほん", 1))
            }
            observe("controlled-japanese-composition", "emoji-search")
            key(KeyEvent.KEYCODE_DPAD_RIGHT, true); observe("japanese-after-direct-right", "emoji-search")
            capture("japanese-composition")
            ui { assertTrue(connection!!.finishComposingText()) }
        } finally { ui { connection?.closeConnection() } }
        record.put("diagnostic_completed", true)
    }

    @Test fun recordCallerSelectionAcrossNativeRoutes() {
        val query = "Owned caller 日本 012345"
        ui { it.store.search(query) }
        compose.onNodeWithTag("search").performTextInputSelection(TextRange(2, 8))
        observe("before-updates-route", "search")
        ui { it.store.dialog = "updates" }
        capture("updates-route-idle")
        compose.onNodeWithTag("palette-screen").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("palette-screen").assertIsFocused()
        record.put("updates_root_focus_requested_before_return", true)
        capture("updates-route-focused")
        compose.onNodeWithTag("inline-back").performTouchInput { click(center) }
        observe("after-updates-return", "search"); capture("updates-caller")
        compose.onNodeWithTag("search").performTextInputSelection(TextRange(2, 8))
        observe("before-note-route", "search")
        ui { it.execute(Command("new-note", "New note", "")) }
        capture("note-route")
        compose.onNodeWithTag("inline-back").performTouchInput { click(center) }
        observe("after-note-return", "search"); capture("note-caller")
        record.put("updates_scope", "Idle native Updates route, no public check/download/install requested")
            .put("selection_setup", "Semantic selection action; not physical selection")
            .put("diagnostic_completed", true)
    }

    @Test fun recordSameTurnLauncherActivationTextAndAnnotations() {
        val rows = ownedNotes()
        observe("before-same-turn-navigation", "search")
        ui { activity ->
            val down = SystemClock.uptimeMillis()
            for (code in listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_ENTER)) {
                for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    activity.dispatchKeyEvent(KeyEvent(down, down, action, code, 0, 0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD))
                }
            }
            record.put("expected_note_id", rows[1].id).put("actual_note_id", activity.store.editing?.id ?: JSONObject.NULL)
        }
        if (compose.onAllNodesWithTag("editor-title").fetchSemanticsNodes().isEmpty()) {
            observe("same-turn-query-retained", "search")
            record.put("destination_present", false).put("diagnostic_completed", true)
            capture("same-turn-no-destination")
            return
        }
        observe("same-turn-destination-title", "editor-title")
        val title = compose.onNodeWithTag("editor-title").fetchSemanticsNode().config[SemanticsProperties.EditableText]
        record.put("title_plain_exact", title.text == rows[1].title)
            .put("title_annotated_exact", title == AnnotatedString(rows[1].title))
            .put("diagnostic_completed", true)
        capture("same-turn-destination")
    }

    @Test fun recordInjectedTypingAndCommittedQueryNavigation() {
        ui { it.execute(Command("emoji", "Emoji & Symbols", "")) }
        compose.onNodeWithTag("emoji-search").assertIsFocused()
        observe("before-first-injected-character", "emoji-search")
        for (code in listOf(KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_E)) {
            key(code, false); compose.waitForIdle()
        }
        observe("after-four-injected-characters", "emoji-search")
        compose.onNodeWithTag("emoji-search").assert(editableCharactersExactly("face"))
        observe("injected-typing-before-right", "emoji-search")
        key(KeyEvent.KEYCODE_DPAD_RIGHT, false); observe("injected-typing-after-right", "emoji-search")
        capture("injected-typing")
        var connection: InputConnection? = null
        try {
            ui {
                connection = requireNotNull(it.window.decorView.findFocus()?.onCreateInputConnection(EditorInfo()))
                assertTrue(connection!!.finishComposingText())
            }
            observe("typed-query-explicitly-committed", "emoji-search")
            key(KeyEvent.KEYCODE_DPAD_RIGHT, false); observe("typed-committed-after-right", "emoji-search")
            key(KeyEvent.KEYCODE_L, false, KeyEvent.META_CTRL_ON)
            key(KeyEvent.KEYCODE_DPAD_LEFT, false); observe("typed-query-editing-left", "emoji-search")
            capture("typed-query-caret")
        } finally { ui { connection?.closeConnection() } }
        record.put("diagnostic_completed", true)
    }

    private fun contract(scope: String) {
        assertEquals("Use the explicitly owned silent IME for controlled contracts", "owned", InstrumentationRegistry.getArguments().getString("nativeInputMode"))
        assertEquals("dev.tinycast.android.test/dev.tinycast.android.NativeInputTestIme",
            android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD))
        record.put("diagnostic_only", false).put("scope", scope)
            .put("input_setup", "Owned silent test IME plus explicit native InputConnection readiness/commit; no system-keyboard or physical-typing credit")
    }

    private fun withControlledInput(tag: String, expected: String, block: (InputConnection) -> Unit) {
        compose.onNodeWithTag(tag).assertIsFocused()
        var connection: InputConnection? = null
        var attempts = 0
        compose.waitUntil(5000) {
            ui {
                attempts++
                val candidate = it.window.decorView.findFocus()?.onCreateInputConnection(EditorInfo())
                val extracted = candidate?.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString()
                if (extracted == expected) connection = candidate else candidate?.closeConnection()
            }
            connection != null
        }
        trace.put(JSONObject().put("kind", "controlled-input-ready").put("tag", tag)
            .put("expected_text_length", expected.length).put("readiness_attempts", attempts))
        try { block(requireNotNull(connection)) } finally { ui { connection?.closeConnection() } }
    }

    private fun committed(tag: String, text: String, connection: InputConnection) {
        ui { assertTrue(connection.finishComposingText()) }
        compose.waitUntil(5000) {
            compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText] == AnnotatedString(text)
        }
        observe("explicitly-committed-$tag", tag)
    }

    private fun ownedNotes(): List<Command> {
        val ids = mutableSetOf<String>()
        ui {
            ids += it.store.save("notes", "Owned rapid Alpha", "Owned first body")
            ids += it.store.save("notes", "Owned rapid Bravo", "Owned second body")
            it.store.filter("Notes"); it.store.search("Owned rapid")
        }
        compose.onNodeWithTag("search").assertIsFocused()
        return compose.activity.store.results().also { rows ->
            assertEquals(2, rows.size)
            assertEquals("Only this fixture's saved IDs may be observed", ids, rows.map { it.id }.toSet())
        }
    }

    @Test fun exactContentAssertionAcceptsCompositionAndRejectsChangedCharacters() {
        contract("Exact characters survive legitimate IME decoration; wrong Unicode/whitespace remains rejected")
        val title = "  Owned café 日本 👨‍👩‍👧‍👦 ❤️ 1️⃣  "
        ui { it.execute(Command("new-note", "New note", "")); it.store.editorDraft("note").title = title }
        withControlledInput("editor-title", title) { connection ->
            ui { assertTrue(connection.setComposingRegion(0, title.length)) }
            var checked = false
            compose.waitUntil(5000) {
                val node = compose.onNodeWithTag("editor-title").fetchSemanticsNode()
                ui {
                    // Native IME callbacks cannot interleave this witness and the
                    // matcher evaluations. A screenshot/idle wait here would
                    // allow composition to finish before the assertion.
                    val actual = node.config[SemanticsProperties.EditableText]
                    if (actual.text == title && actual.spanStyles.any { span ->
                        span.start == 0 && span.end == title.length && span.item.textDecoration == TextDecoration.Underline
                    }) {
                        record.put("strict_annotation_equal", actual == AnnotatedString(title))
                            .put("assertion_witness", JSONObject().put("single_ui_turn", true)
                                .put("text_length", actual.length).put("text_sha256", digest(actual.text))
                                .put("composition_underline_start", 0).put("composition_underline_end", title.length))
                        assertTrue("Exact character matcher must accept the composing title", editableCharactersExactly(title).matches(node))
                        val wrong = linkedMapOf(
                            "changed-character" to title.replace("日本", "日本語"),
                            "trimmed-whitespace" to title.trim(),
                            "removed-joiner" to title.replace("\u200d", ""),
                            "removed-variation-selector" to title.replace("\ufe0f", ""),
                            "normalized-combining-sequence" to title.replace("café", "cafe\u0301")
                        )
                        wrong.forEach { (label, value) ->
                            assertNotEquals(label, title, value)
                            assertFalse("Reject $label", editableCharactersExactly(value).matches(node))
                        }
                        record.put("wrong_content_rejected", JSONArray(wrong.keys.toList()))
                        checked = true
                    }
                }
                checked
            }
            observe("composed-title-after-content-assertion", "editor-title")
            capture("composed-title-after-assertion")
            ui { assertTrue(connection.finishComposingText()) }
        }
        record.put("contract_completed", true)
    }

    @Test fun committedEmojiNavigationAndCaretAreExact() {
        contract("Explicitly committed Emoji query supports injected grid navigation, exact caret and selected range")
        ui { it.execute(Command("emoji", "Emoji & Symbols", "")) }
        compose.onNodeWithTag("emoji-search").performTextReplacement("face")
        withControlledInput("emoji-search", "face") { connection ->
            committed("emoji-search", "face", connection)
            key(KeyEvent.KEYCODE_DPAD_RIGHT, false); compose.waitForIdle()
            assertEquals(1, compose.activity.store.emoji.selected)
            key(KeyEvent.KEYCODE_DPAD_DOWN, false); compose.waitForIdle()
            val row = compose.activity.store.emoji.selected; assertTrue(row > 1)
            key(KeyEvent.KEYCODE_DPAD_LEFT, false); compose.waitForIdle()
            assertEquals(row - 1, compose.activity.store.emoji.selected)
            key(KeyEvent.KEYCODE_DPAD_UP, false); compose.waitForIdle()
            assertEquals(0, compose.activity.store.emoji.selected)
            key(KeyEvent.KEYCODE_MOVE_END, false); compose.waitForIdle()
            assertEquals(compose.activity.store.emoji.results().lastIndex, compose.activity.store.emoji.selected)
            key(KeyEvent.KEYCODE_MOVE_HOME, false); compose.waitForIdle()
            assertEquals(0, compose.activity.store.emoji.selected)
            compose.onNodeWithTag("emoji-search").assert(editableCharactersExactly("face"))
            capture("committed-four-way-navigation")
            key(KeyEvent.KEYCODE_L, false, KeyEvent.META_CTRL_ON)
            key(KeyEvent.KEYCODE_DPAD_LEFT, false); compose.waitForIdle()
            compose.onNodeWithTag("emoji-search").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(3)))
            assertEquals(0, compose.activity.store.emoji.selected)
            compose.onNodeWithTag("emoji-search").performTextInputSelection(TextRange(0, 2))
            compose.onNodeWithTag("emoji-search").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(0, 2)))
            assertEquals(0, compose.activity.store.emoji.selected)
            observe("committed-query-selected-range", "emoji-search"); capture("committed-query-selected-range")
        }
        record.put("contract_completed", true)
    }

    @Test fun activeMultilingualCompositionKeepsResultSelection() {
        contract("Controlled active Japanese composition keeps direct native navigation with the editor, before and after composition publication")
        ui { it.execute(Command("emoji", "Emoji & Symbols", "")) }
        compose.onNodeWithTag("emoji-search").performTextReplacement("face")
        withControlledInput("emoji-search", "face") { connection ->
            committed("emoji-search", "face", connection)
            ui {
                assertTrue(connection.setSelection(0, 4))
                assertTrue(connection.setComposingText("にほん", 1))
                val down = SystemClock.uptimeMillis()
                for (code in listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN)) {
                    for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP))
                        it.dispatchKeyEvent(KeyEvent(down, down, action, code, 0))
                }
                assertEquals("Read current composition before redraw", 0, it.store.emoji.selected)
            }
            compose.waitUntil(5000) {
                val actual = compose.onNodeWithTag("emoji-search").fetchSemanticsNode().config[SemanticsProperties.EditableText]
                actual.text == "にほん" && actual.spanStyles.any { it.start == 0 && it.end == 3 && it.item.textDecoration == TextDecoration.Underline }
            }
            observe("active-japanese-composition", "emoji-search")
            key(KeyEvent.KEYCODE_DPAD_RIGHT, true); compose.waitForIdle()
            assertEquals(0, compose.activity.store.emoji.selected)
            compose.onNodeWithTag("emoji-search").assert(editableCharactersExactly("にほん"))
            observe("active-japanese-after-direct-navigation", "emoji-search"); capture("active-japanese-composition")
            ui { assertTrue(connection.finishComposingText()) }
        }
        record.put("contract_completed", true)
    }

    @Test fun committedCallerSelectionSurvivesIdleNativeRoutes() {
        contract("Committed caller query/range survives pointer Back from focused idle Updates and unsaved Note routes; no live network check")
        val query = "Owned caller 日本 012345"
        ui { it.store.search(query) }
        withControlledInput("search", query) { committed("search", query, it) }
        for (route in listOf("updates", "note")) {
            compose.onNodeWithTag("search").performTextInputSelection(TextRange(2, 8))
            observe("before-$route-route", "search")
            ui { if (route == "updates") it.store.dialog = "updates" else it.execute(Command("new-note", "New note", "")) }
            if (route == "updates") {
                compose.onNodeWithTag("palette-screen").performSemanticsAction(SemanticsActions.RequestFocus)
                compose.onNodeWithTag("palette-screen").assertIsFocused()
            }
            capture("$route-destination")
            compose.onNodeWithTag("inline-back").performTouchInput { click(center) }
            compose.onNodeWithTag("search").assertIsFocused().assert(editableCharactersExactly(query))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 8)))
            observe("after-$route-return", "search"); capture("$route-caller-return")
        }
        record.put("contract_completed", true)
    }

    @Test fun committedSameTurnLauncherActivationUsesCurrentNote() {
        contract("Explicitly committed owned launcher query opens the second note on Down/Enter within one main-thread turn")
        val rows = ownedNotes()
        val expectedBody = when (rows[1].title) {
            "Owned rapid Alpha" -> "Owned first body"
            "Owned rapid Bravo" -> "Owned second body"
            else -> error("Unexpected owned note title")
        }
        withControlledInput("search", "Owned rapid") { connection ->
            committed("search", "Owned rapid", connection)
            ui { activity ->
                val down = SystemClock.uptimeMillis()
                for (code in listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_ENTER)) {
                    for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP))
                        activity.dispatchKeyEvent(KeyEvent(down, down, action, code, 0, 0,
                            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD))
                }
                assertEquals(rows[1].id, activity.store.editing?.id)
            }
        }
        compose.onNodeWithTag("editor-title").assert(editableCharactersExactly(rows[1].title))
        compose.onNodeWithTag("editor-body").assert(editableCharactersExactly(expectedBody))
        observe("same-turn-exact-title", "editor-title"); capture("same-turn-current-note")
        record.put("contract_completed", true)
    }

    @Test fun largeDraftRestoresExactCharactersAfterRecreation() {
        contract("Owned unsaved 540000-unit multilingual draft survives recreation with exact title/body characters")
        val ownedTitle = "Owned large draft 日本"
        val ownedBody = "Owned draft line café 日本 🌱 314159.\n".repeat(15000)
        ui { it.execute(Command("new-note", "New note", "")); it.store.editorDraft("note").apply { title = ownedTitle; body = ownedBody } }
        compose.waitForIdle(); compose.activityRule.scenario.recreate(); compose.waitForIdle()
        compose.onNodeWithTag("editor-title").assert(editableCharactersExactly(ownedTitle))
        compose.onNodeWithTag("editor-body").assert(editableCharactersExactly(ownedBody))
        ui {
            assertEquals(ownedTitle, it.store.editorDraft("note").title)
            assertEquals(ownedBody, it.store.editorDraft("note").body)
            assertFalse(it.store.notes.any { note -> note.title == ownedTitle })
        }
        observe("exact-recreated-title", "editor-title"); observe("exact-recreated-body", "editor-body")
        capture("large-draft-exact-characters")
        record.put("expected_body_length", ownedBody.length).put("expected_body_sha256", digest(ownedBody)).put("contract_completed", true)
    }

    @Test fun recordLargeDraftTextAndAnnotationsAfterRecreation() {
        val ownedTitle = "Owned large draft 日本"
        val ownedBody = "Owned draft line café 日本 🌱 314159.\n".repeat(15000)
        ui { it.execute(Command("new-note", "New note", "")); it.store.editorDraft("note").apply { title = ownedTitle; body = ownedBody } }
        compose.waitForIdle(); compose.activityRule.scenario.recreate(); compose.waitForIdle()
        ui { hook(it) }
        observe("recreated-title", "editor-title"); observe("recreated-body", "editor-body")
        val actualTitle = compose.onNodeWithTag("editor-title").fetchSemanticsNode().config[SemanticsProperties.EditableText]
        val actualBody = compose.onNodeWithTag("editor-body").fetchSemanticsNode().config[SemanticsProperties.EditableText]
        record.put("title_plain_exact", actualTitle.text == ownedTitle).put("title_annotated_exact", actualTitle == AnnotatedString(ownedTitle))
            .put("body_plain_exact", actualBody.text == ownedBody).put("body_annotated_exact", actualBody == AnnotatedString(ownedBody))
            .put("expected_body_length", ownedBody.length).put("expected_body_sha256", digest(ownedBody)).put("diagnostic_completed", true)
        capture("large-draft-recreated")
    }

    @After fun restore() {
        if (!prepared) return
        try {
            if (!record.optBoolean("diagnostic_completed") && !record.optBoolean("contract_completed")) capture("failure")
        } finally {
            ui { current ->
                hooks.forEach { (activity, original, wrapper) -> if (activity.window.callback === wrapper) activity.window.callback = original }
                current.store.reset()
            }
            restorePalettePreferences(context, palette)
            assertEquals(palette, context.getSharedPreferences("palette", Context.MODE_PRIVATE).all)
            assertEquals(emoji, context.getSharedPreferences("emoji", Context.MODE_PRIVATE).all)
            record.put("palette_preferences_restored", true).put("emoji_preferences_unchanged", true).put("observations", trace)
            evidence.mkdirs(); File(evidence, "case-${name.methodName.lowercase()}.json").writeText(record.toString(2))
        }
    }
}
