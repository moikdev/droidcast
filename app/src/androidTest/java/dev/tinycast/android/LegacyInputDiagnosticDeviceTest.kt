package dev.tinycast.android

import android.os.SystemClock
import android.view.KeyEvent
import android.view.Window
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Diagnostic observations retain failed expectations; this is not a compatibility certificate. */
@RunWith(AndroidJUnit4::class)
class LegacyInputDiagnosticDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val trace=JSONArray()
    private var callback:Window.Callback?=null
    @After fun cleanup() {
        compose.activityRule.scenario.onActivity { callback?.let { original->it.window.callback=original };it.store.dialog=null }
    }
    private fun observe(label:String) {
        compose.waitForIdle()
        val node=compose.onAllNodesWithTag("emoji-search").fetchSemanticsNodes().singleOrNull()
        val text=node?.config?.get(SemanticsProperties.EditableText)
        compose.activityRule.scenario.onActivity {
            trace.put(JSONObject().put("stage",label).put("query",it.store.emoji.query).put("selected",it.store.emoji.selected)
                .put("emoji_route",it.store.dialog=="emoji").put("editable",text?.text).put("selection",node?.config?.get(SemanticsProperties.TextSelectionRange)?.toString())
                .put("focused",node?.config?.get(SemanticsProperties.Focused)).put("window_focus",it.window.decorView.hasWindowFocus())
                .put("extracted",it.window.decorView.findFocus()?.onCreateInputConnection(android.view.inputmethod.EditorInfo())
                    ?.getExtractedText(android.view.inputmethod.ExtractedTextRequest().apply { flags=android.view.inputmethod.InputConnection.GET_TEXT_WITH_STYLES },0)?.let { extracted ->
                        JSONObject().put("text",extracted.text?.toString()).put("selection_start",extracted.selectionStart).put("selection_end",extracted.selectionEnd)
                            .put("spans",(extracted.text as? android.text.Spanned)?.let { span -> JSONArray(span.getSpans(0,span.length,Any::class.java).map { value ->
                                JSONObject().put("class",value.javaClass.name).put("start",span.getSpanStart(value)).put("end",span.getSpanEnd(value)).put("flags",span.getSpanFlags(value)) }) })
                    }))
        }
    }
    private fun key(code:Int,direct:Boolean) {
        val down=SystemClock.uptimeMillis()
        for(action in listOf(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP)) {
            val event=KeyEvent(down,SystemClock.uptimeMillis(),action,code,0,0,android.view.KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,android.view.InputDevice.SOURCE_KEYBOARD)
            if(direct) compose.activityRule.scenario.onActivity {
                trace.put(JSONObject().put("direct_code",code).put("action",action).put("handled",it.dispatchKeyEvent(event)))
            } else assertTrue(testAutomation().injectInputEvent(event,true))
        }
    }
    @Test fun recordExactAnnotatedTextAndInjectedVersusActivityKeyDelivery() {
        compose.waitUntil(15000) { !compose.activity.store.loading && !compose.activity.store.emoji.loading }
        compose.activityRule.scenario.onActivity {
            it.store.reset();it.execute(Command("new-note","New note",""))
            it.store.editorDraft("note").apply { title="Audit diagnostic draft";body="Large draft line 314159.\n".repeat(25_000) }
        }
        compose.waitForIdle();compose.activityRule.scenario.recreate();compose.waitForIdle()
        val actual=compose.onNodeWithTag("editor-title").fetchSemanticsNode().config[SemanticsProperties.EditableText]
        val expected=AnnotatedString("Audit diagnostic draft")
        val record=JSONObject().put("diagnostic_only",true).put("api",android.os.Build.VERSION.SDK_INT)
            .put("title_actual",actual.text).put("title_plain_text_equal",actual.text==expected.text)
            .put("title_annotated_equal",actual==expected).put("title_span_styles",actual.spanStyles.toString())
            .put("title_paragraph_styles",actual.paragraphStyles.toString()).put("title_annotations",actual.getStringAnnotations(0,actual.length).toString()).put("title_dump",actual.toString())
        compose.activityRule.scenario.onActivity { it.store.dialog=null;it.store.search("emoji") }
        compose.onNodeWithTag("result-emoji").performClick()
        compose.onNodeWithTag("emoji-search").performTextReplacement("face")
        compose.activityRule.scenario.onActivity {
            callback=it.window.callback
            val original=callback!!
            it.window.callback=object:Window.Callback by original {
                override fun dispatchKeyEvent(event:KeyEvent):Boolean {
                    val result=original.dispatchKeyEvent(event)
                    trace.put(JSONObject().put("window_code",event.keyCode).put("action",event.action)
                        .put("meta",event.metaState).put("handled",result))
                    return result
                }
            }
        }
        observe("before-injected-right");key(KeyEvent.KEYCODE_DPAD_RIGHT,false);observe("after-injected-right-idle")
        SystemClock.sleep(250);observe("after-injected-right-250ms")
        key(KeyEvent.KEYCODE_DPAD_RIGHT,true);observe("after-direct-right")
        key(KeyEvent.KEYCODE_ESCAPE,false);observe("after-injected-escape-idle")
        SystemClock.sleep(250);observe("after-injected-escape-250ms")
        key(KeyEvent.KEYCODE_ESCAPE,true);observe("after-direct-escape")
        compose.activityRule.scenario.onActivity { if(it.store.dialog!="emoji")it.execute(Command("emoji","Emoji & Symbols","")) }
        compose.onNodeWithTag("emoji-search").performTextReplacement("face")
        compose.activityRule.scenario.onActivity { assertTrue(it.window.decorView.findFocus()!!.onCreateInputConnection(android.view.inputmethod.EditorInfo())!!.finishComposingText()) }
        observe("after-explicit-finish-composing")
        key(KeyEvent.KEYCODE_DPAD_RIGHT,false);observe("after-finished-composition-injected-right")
        key(KeyEvent.KEYCODE_ESCAPE,false);observe("after-finished-composition-injected-escape")
        record.put("observations",trace)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir,"readiness-evidence").apply { mkdirs() }
            .resolve("legacy-input-diagnostic.json").writeText(record.toString(2))
    }
}
