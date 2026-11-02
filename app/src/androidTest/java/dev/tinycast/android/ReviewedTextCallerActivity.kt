package dev.tinycast.android

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** A different Android package/UID. Uses only owned test text, no target-app internals. */
class ReviewedTextCallerActivity : Activity() {
    private lateinit var source: EditText
    private lateinit var selection: TextView
    override fun onResume() {
        super.onResume()
        selection.text = "Selection ${source.selectionStart} to ${source.selectionEnd}"
    }
    override fun onCreate(state: Bundle?) { super.onCreate(state); showCaller() }
    override fun onNewIntent(value: Intent) { super.onNewIntent(value); intent = value; showCaller() }
    private fun showCaller() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 28); setBackgroundColor(Color.rgb(240, 244, 249))
        }
        column.addView(TextView(this).apply { text = "Owned Android caller"; textSize = 22f; setTextColor(Color.BLACK) })
        column.addView(TextView(this).apply { text = "Select or share the owned text. Returning must leave this source unchanged."; textSize = 15f })
        val text = intent.getStringExtra("source") ?: "Owned caller source"
        source = EditText(this).apply {
            id = android.R.id.edit; textSize = 16f; minLines = 4; setText(text)
            setSelection(intent.getIntExtra("selectionStart", 0).coerceIn(0, text.length), intent.getIntExtra("selectionEnd", text.length).coerceIn(0, text.length))
        }
        column.addView(source, LinearLayout.LayoutParams(-1, -2))
        selection = TextView(this).apply { id = android.R.id.text2; textSize = 14f }
        column.addView(selection)
        fun button(label: String, id: Int, action: () -> Unit) {
            column.addView(Button(this).apply { text = label; isAllCaps = false; this.id = id; setOnClickListener { action() } }, LinearLayout.LayoutParams(-1, -2))
        }
        button("Process selected text", android.R.id.button1) { send(Intent.ACTION_PROCESS_TEXT) }
        button("Share owned text", android.R.id.button2) { send(Intent.ACTION_SEND) }
        button("Edit owned source", android.R.id.button3) {
            source.requestFocus(); getSystemService(InputMethodManager::class.java).showSoftInput(source, InputMethodManager.SHOW_IMPLICIT)
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }
    private fun send(action: String) {
        val selected = source.text.subSequence(minOf(source.selectionStart, source.selectionEnd).coerceAtLeast(0),
            maxOf(source.selectionStart, source.selectionEnd).coerceAtLeast(0)).toString()
        val value = Intent(action).setComponent(ComponentName("dev.tinycast.android", "dev.tinycast.android.MainActivity"))
            .setType(intent.getStringExtra("mime") ?: "text/plain")
        val key = if (action == Intent.ACTION_PROCESS_TEXT) Intent.EXTRA_PROCESS_TEXT else Intent.EXTRA_TEXT
        when (intent.getStringExtra("payloadKind")) {
            "missing" -> Unit
            "integer" -> value.putExtra(key, 42)
            "oversize" -> value.putExtra(key, "x".repeat(65_537))
            else -> value.putExtra(key, selected as CharSequence)
        }
        value.putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, intent.getBooleanExtra("readOnly", true))
        startActivity(value)
    }
}
