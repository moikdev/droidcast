package dev.tinycast.android

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
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

/** Observations only: completion does not certify a functional compatibility fix. */
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

    private fun key(code: Int, direct: Boolean) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val event = KeyEvent(down, SystemClock.uptimeMillis(), action, code, 0, 0,
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
            if (!record.optBoolean("diagnostic_completed")) capture("failure")
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
