package dev.tinycast.android

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.view.KeyEvent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** Measures only the two synthetic payloads delivered to this test-APK editor. */
class KeyboardObservationActivity : Activity() {
    private val events = JSONArray()
    private val edits = JSONArray()
    private lateinit var editor: EditText
    private lateinit var expected: String
    private var fixtureKeys = emptySet<Int>()
    private var overflow = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        expected = when (intent.getStringExtra("fixture")) {
            "short" -> "qwerty"
            "long" -> "menuKeyboardProbe271828"
            else -> { finish(); return }
        }
        fixtureKeys = expected.uppercase().map { KeyEvent.keyCodeFromString("KEYCODE_" + it) }.toSet() +
            setOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT)
        editor = object : EditText(this) {
            override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
                super.onTextChanged(text, start, lengthBefore, lengthAfter)
                if (edits.length() < 256) {
                    edits.put(JSONObject().put("length", text?.length ?: 0).put("ms", SystemClock.uptimeMillis()))
                } else overflow = true
            }
        }.apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(64))
            showSoftInputOnFocus = false
            isFocusableInTouchMode = true
            contentDescription = "Synthetic input"
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(TextView(context).apply { text = "Keyboard delivery test" })
            addView(editor)
        }
        setContentView(layout)
        editor.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::editor.isInitialized && hasWindowFocus() && editor.hasFocus() &&
            (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP)) {
            if (events.length() < 256) {
                val key = if (event.keyCode in fixtureKeys) KeyEvent.keyCodeToString(event.keyCode) else "other"
                events.put(JSONObject().put("key", key).put("action", event.action).put("repeat", event.repeatCount)
                    .put("down_ms", event.downTime).put("event_ms", event.eventTime)
                    .put("received_ms", SystemClock.uptimeMillis()))
            } else overflow = true
        }
        return super.dispatchKeyEvent(event)
    }

    fun sample(): JSONObject {
        val actual = editor.text.toString()
        val printable = actual == "qwerrty" || expected.startsWith(actual)
        return JSONObject().put("fixture", expected).put("exact", expected == actual)
            .put("observed", if (printable) actual else "outside-fixture").put("length", actual.length)
            .put("focused", hasWindowFocus() && editor.hasFocus()).put("ms", SystemClock.uptimeMillis())
            .put("overflow", overflow).put("events", JSONArray(events.toString())).put("edits", JSONArray(edits.toString()))
    }

    fun removeLastCharacterForControl() {
        check(editor.text.toString() == expected)
        editor.text.delete(expected.length - 1, expected.length)
    }
}
