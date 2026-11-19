package dev.tinycast.android

import android.view.KeyEvent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

@Composable
internal fun EmojiKeywordEditor(entry: EmojiEntry, store: EmojiStore, current: () -> Boolean, close: () -> Unit) {
    var original by rememberSaveable { mutableStateOf(store.customKeywordText(entry.id)) }
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(original)) }
    var conflict by rememberSaveable { mutableStateOf(false) }
    val error = remember(draft.text) {
        runCatching { EmojiCatalog.cleanCustomKeywords(draft.text) }.exceptionOrNull()?.message
    }
    val focus = remember { FocusRequester() }
    fun save() {
        if (!current() || draft.composition != null || error != null || conflict) return
        if (store.saveCustomKeywords(entry.id, original, draft.text)) close() else conflict = true
    }

    Shell("Custom keywords", "${entry.glyph}  ${entry.name}", close,
        Modifier.onPreviewKeyEvent {
            val e = it.nativeKeyEvent
            if (e.keyCode == KeyEvent.KEYCODE_S && (e.isCtrlPressed xor e.isMetaPressed) && !e.isAltPressed && !e.isShiftPressed) {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) save()
                true
            } else false
        }, backLabel = "Back to emoji picker", footer = {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = close, modifier = Modifier.heightIn(min = 48.dp).testTag("emoji-keywords-cancel")) { Text("Cancel") }
            Button(onClick = ::save, enabled = error == null && !conflict && draft.composition == null,
                modifier = Modifier.heightIn(min = 48.dp).testTag("emoji-keywords-save")) { Text("Save") }
        }) {
        LaunchedEffect(Unit) { focus.requestFocus() }
        OutlinedTextField(draft, { draft = it }, label = { Text("Keywords") },
            supportingText = { Text(error ?: "One word or phrase per line. Clear all lines to remove the keywords.") },
            isError = error != null, minLines = 2, maxLines = 5,
            modifier = Modifier.fillMaxWidth().paletteFocusBookmark(focus).testTag("emoji-keywords-field"))
        if (conflict) {
            Text("These keywords changed in another window.", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = {
                if (current()) {
                    original = store.customKeywordText(entry.id)
                    draft = TextFieldValue(original)
                    conflict = false
                }
            }, modifier = Modifier.heightIn(min = 48.dp).testTag("emoji-keywords-reload")) { Text("Load saved keywords") }
        }
    }
}
