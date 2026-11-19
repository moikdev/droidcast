package dev.tinycast.android

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewModelScope

/** A standard-mode activity retains the editor's start-for-result relationship. */
class EmojiSelectionActivity : ComponentActivity() {
    private val request by viewModels<EmojiSelectionRequest>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        if (!request.started) {
            request.started = true
            request.problem = if (savedInstanceState != null) "Select the text again to start a new replacement."
                else selectionProblem(intent)
        }
        // The selected text is never needed to search the catalog or send the result.
        setIntent(Intent(this, EmojiSelectionActivity::class.java))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = PaletteTheme.accent, surface = PaletteTheme.surface)) {
                SelectionScreen(request, ::finish) { confirmation ->
                    if (!isFinishing && hasWindowFocus() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                        request.problem == null && request.choice === confirmation) {
                        request.choice = null
                        setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, confirmation.entry.glyph))
                        finish()
                    }
                }
            }
        }
    }

    private fun selectionProblem(value: Intent): String? {
        val invalid = "Select text in an editable field, then choose Replace with emoji."
        if (callingActivity == null || value.action != Intent.ACTION_PROCESS_TEXT ||
            !value.type.equals("text/plain", ignoreCase = true) ||
            value.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return invalid
        return try {
            @Suppress("DEPRECATION") val writable = value.extras?.get(Intent.EXTRA_PROCESS_TEXT_READONLY) == false
            val text = value.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            when {
                text == null || text.isEmpty() -> invalid
                text.length > ReviewedTextInput.MAX_CODE_UNITS -> TextInputProblem.TOO_LONG.message
                !writable -> "This selection is read-only. Return to the editor to choose editable text."
                else -> null
            }
        } catch (_: RuntimeException) {
            invalid
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        request.choice = null
        finish()
    }
}

// Each opening has its own identity, even when the user chooses the same glyph twice.
internal class EmojiConfirmation(val entry: EmojiEntry)

internal class EmojiSelectionRequest(application: Application) : AndroidViewModel(application) {
    var started = false
    var problem: String? = null
    var choice by mutableStateOf<EmojiConfirmation?>(null)
    val store by lazy { EmojiStore(application, viewModelScope) }
}

@Composable
private fun SelectionScreen(request: EmojiSelectionRequest, cancel: () -> Unit, replace: (EmojiConfirmation) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val choice = request.choice
    BackHandler(enabled = choice != null) { request.choice = null }
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = PaletteTheme.width).fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Replace with emoji", style = MaterialTheme.typography.titleLarge)
                val problem = request.problem
                if (problem != null || choice != null) {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (problem != null) {
                            Text(problem)
                            Button(onClick = cancel) { Text("Return to editor") }
                        } else if (choice != null) {
                            Text(choice.entry.glyph, style = MaterialTheme.typography.displayMedium)
                            Text(choice.entry.name, style = MaterialTheme.typography.titleMedium)
                            Button(onClick = { replace(choice) }) { Text("Replace selection") }
                            TextButton(onClick = { request.choice = null }) { Text("Choose another emoji") }
                            TextButton(onClick = cancel) { Text("Cancel") }
                        }
                    }
                } else {
                    val store = request.store
                    val languages = LocalConfiguration.current.locales.toLanguageTags()
                    LaunchedEffect(languages) { store.loadLanguages(languages) }
                    val results by remember(store) { derivedStateOf { store.results() } }
                    OutlinedTextField(value = store.query, onValueChange = store::search, singleLine = true,
                        label = { Text("Search emoji or symbols") }, modifier = Modifier.fillMaxWidth())
                    if (results.isEmpty()) Text(store.error ?: if (store.loading) "Loading emoji…" else "No matching emoji")
                    LazyColumn(Modifier.weight(1f)) {
                        items(results, key = { it.id }) { entry ->
                            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) {
                                keyboard?.hide()
                                request.choice = EmojiConfirmation(entry)
                            }.heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(entry.glyph, style = MaterialTheme.typography.headlineSmall)
                                Spacer(Modifier.width(12.dp))
                                Text(entry.name)
                            }
                        }
                    }
                    TextButton(onClick = cancel) { Text("Cancel") }
                }
            }
        }
    }
}
