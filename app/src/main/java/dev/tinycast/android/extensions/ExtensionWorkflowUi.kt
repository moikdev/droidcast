package dev.tinycast.android.extensions

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tinycast.android.Glyph
import dev.tinycast.android.PaletteStore
import dev.tinycast.android.PaletteTheme
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private class RecordedViewLifetime { @Volatile var active = true; @Volatile var closed = false }
private sealed interface RecordedViewResult {
    data object Loading : RecordedViewResult
    data object Changed : RecordedViewResult
    data class Ready(val inspection: ExtensionWorkflowInspection) : RecordedViewResult
    data class Error(val catalog: Boolean) : RecordedViewResult
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ExtensionRecordedTests(store: PaletteStore, extension: InstalledExtension,
                                    ownsRoute: () -> Boolean, back: () -> Unit,
                                    source: ExtensionWorkflowSource? = null) {
    val context = LocalContext.current
    val window = LocalWindowInfo.current
    val view = LocalView.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val imeVisible = rememberUpdatedState(WindowInsets.isImeVisible)
    val lifetime = remember(extension) { RecordedViewLifetime() }
    var result by remember(extension) { mutableStateOf<RecordedViewResult>(RecordedViewResult.Loading) }
    var query by remember(extension) { mutableStateOf(TextFieldValue("")) }
    var retry by remember(extension) { mutableIntStateOf(0) }
    var showOtherVersions by remember(extension) { mutableStateOf(false) }
    var linkError by remember(extension) { mutableStateOf(false) }
    fun routeCurrent() = lifetime.active && !lifetime.closed && store.dialog == "extensions" && ownsRoute()
    fun targetCurrent() = routeCurrent() && !store.backupBusy && store.extensions.busy == null &&
        store.extensions.installed.any { it === extension }
    fun actionCurrent() = targetCurrent() && window.isWindowFocused && view.isAttachedToWindow && view.hasWindowFocus()
    fun close() { if (routeCurrent() && window.isWindowFocused && view.isAttachedToWindow && view.hasWindowFocus()) { lifetime.closed = true; back() } }
    DisposableEffect(lifetime) { onDispose { lifetime.active = false } }
    LaunchedEffect(lifetime) { focus.clearFocus(force = true); keyboard?.hide() }
    LaunchedEffect(extension, store.extensions.installed, store.extensions.busy, store.backupBusy, retry, source) {
        result = RecordedViewResult.Loading; showOtherVersions = false; linkError = false
        if (!targetCurrent()) { result = RecordedViewResult.Changed; return@LaunchedEffect }
        try {
            val next = withContext(Dispatchers.IO) {
                val scope = coroutineContext
                (source ?: ExtensionWorkflowSource { context.assets.open("extension-workflow-evidence-v1.json") }).inspect(
                    extension.listing.handle, extension.listing.name, extension.directory, File(context.filesDir, "extensions")) {
                    scope.ensureActive(); if (!targetCurrent()) throw CancellationException("Recorded test inspection ended")
                }
            }
            if (targetCurrent()) result = RecordedViewResult.Ready(next)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: RecordedTestsUnavailable) { if (targetCurrent()) result = RecordedViewResult.Error(true) }
        catch (_: InstalledPayloadUnavailable) { if (targetCurrent()) result = RecordedViewResult.Error(false) }
    }
    val current = targetCurrent()
    fun matches(title: String, record: RecordedWorkflowObservation?): Boolean = query.text.isBlank() ||
        (title + " " + record?.workflow.orEmpty() + " " + record?.outcome?.label.orEmpty()).contains(query.text, ignoreCase = true)
    fun open(record: RecordedWorkflowObservation, title: String) {
        val now = (result as? RecordedViewResult.Ready)?.inspection
        if (!actionCurrent() || now == null || now.evidence.observations.none { it === record } || !matches(title, record)) return
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(record.reportUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            linkError = false
        } catch (_: ActivityNotFoundException) { linkError = true }
        catch (_: SecurityException) { linkError = true }
    }
    ExtensionWindow("Recorded tests", extension.listing.title, ::close, onEscape = ::close) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp).background(PaletteTheme.control, RoundedCornerShape(10.dp)).padding(start = 12.dp, end = 4.dp)) {
            BasicTextField(query, { if (routeCurrent()) query = it }, singleLine = true,
                textStyle = TextStyle(color = PaletteTheme.ink, fontSize = 16.sp, lineHeight = 24.sp),
                cursorBrush = SolidColor(PaletteTheme.accent),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).padding(vertical = 12.dp).focusRequester(focusRequester).testTag("recorded-tests-search"),
                decorationBox = { inner -> Box { if (query.text.isEmpty()) Text("Search recorded commands…", color = PaletteTheme.secondary,
                    fontSize = 16.sp, lineHeight = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); inner() } })
            if (query.text.isNotEmpty()) IconButton(onClick = { if (actionCurrent()) query = TextFieldValue("") }, modifier = Modifier.size(48.dp).semantics { contentDescription = "Clear recorded-test search" }.testTag("recorded-tests-clear")) {
                Glyph("close", PaletteTheme.secondary, 18)
            }
            IconButton(onClick = { if (actionCurrent()) { if (imeVisible.value) keyboard?.hide() else { focusRequester.requestFocus(); keyboard?.show() } } },
                modifier = Modifier.size(48.dp).semantics { contentDescription = if (imeVisible.value) "Hide on-screen keyboard" else "Show on-screen keyboard" }.testTag("recorded-tests-keyboard")) { Glyph("keyboard", PaletteTheme.secondary, 18) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("recorded-tests-list"),
            contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Text(extension.listing.title, color = PaletteTheme.ink, fontSize = 18.sp, lineHeight = 26.sp)
                Text("These are earlier observations of specific workflows. Matching source files does not verify your current app or device.",
                    color = PaletteTheme.secondary, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 10.dp).testTag("recorded-tests-scope"))
            }
            if (linkError) item { Text("Couldn’t open the report. You can try again or return to installed extensions.", color = MaterialTheme.colorScheme.error,
                fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.testTag("recorded-tests-link-error")) }
            if (!current || result == RecordedViewResult.Changed) item {
                Text(if (store.extensions.busy != null || store.backupBusy) "An extension operation is in progress. Return to installed extensions and try again."
                    else "Installed extensions changed. Return to inspect the current version.", color = PaletteTheme.secondary,
                    fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.testTag("recorded-tests-changed"))
            } else when (val state = result) {
                RecordedViewResult.Loading -> item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("Checking local records and installed files…", color = PaletteTheme.secondary, fontSize = 14.sp, lineHeight = 22.sp)
                } }
                is RecordedViewResult.Error -> item {
                    Text(if (state.catalog) "Recorded tests are unavailable. No compatibility result has been determined."
                        else "Installed files could not be checked. This version remains unverified.", color = PaletteTheme.secondary,
                        fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.testTag("recorded-tests-error"))
                    OutlinedButton(onClick = { if (actionCurrent()) retry++ }, shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.padding(top = 12.dp).heightIn(min = 48.dp).testTag("recorded-tests-retry")) { Text("Try again", fontSize = 14.sp, lineHeight = 20.sp) }
                }
                is RecordedViewResult.Ready -> {
                    val inspection = state.inspection
                    item { Text(when {
                        inspection.candidates.isEmpty() -> "No source version is recorded for this extension. Its commands remain not tested in this catalog."
                        inspection.matches.isEmpty() -> "These installed files do not match a recorded source version. Its commands remain not tested here."
                        else -> "Installed files match a recorded source version. The workflow observations below remain historical."
                    }, color = PaletteTheme.ink, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.testTag("recorded-tests-match")) }
                    // Matching records use the immutable, payload-bound command definitions.
                    // An unreadable cached manifest must not become an empty-success state.
                    val commands = if (inspection.matches.isNotEmpty()) inspection.matches.first().commands.map {
                        it.name to it.title
                    } else try {
                        val count = extension.manifest.optJSONArray("commands")?.length()
                        require(count != null && count in 1..256)
                        extension.commands.map {
                            require(it.name.length in 1..96 && it.title.length in 1..256)
                            it.name to it.title
                        }
                    } catch (_: org.json.JSONException) { null } catch (_: IllegalArgumentException) { null }
                    if (commands == null) item { Text("The installed command list could not be read. No command result has been determined.",
                        color = PaletteTheme.secondary, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.testTag("recorded-tests-command-error")) }
                    var shown = 0
                    commands.orEmpty().forEachIndexed { index, command ->
                        val (commandName, commandTitle) = command
                        val records = inspection.matches.flatMap { inspection.evidence.records(it, commandName) }
                        if (records.isEmpty() && matches(commandTitle, null)) {
                            shown++
                            item("unknown-$index") { Column(Modifier.fillMaxWidth().background(PaletteTheme.control, RoundedCornerShape(12.dp)).padding(16.dp)) {
                                Text(commandTitle, color = PaletteTheme.ink, fontSize = 16.sp, lineHeight = 24.sp)
                                Text("Not tested in this catalog", color = PaletteTheme.secondary, fontSize = 14.sp, lineHeight = 22.sp,
                                    modifier = Modifier.padding(top = 8.dp).testTag("recorded-command-unknown-$index"))
                            } }
                        }
                        records.forEach { record -> if (matches(commandTitle, record)) {
                            shown++
                            item("known-$index-" + record.id) { RecordedWorkflowCard(record, commandTitle, ::actionCurrent,
                                { (result as? RecordedViewResult.Ready)?.inspection === inspection && matches(commandTitle, record) }, { open(record, commandTitle) }) }
                        } }
                    }
                    if (commands != null && shown == 0) item { Text("No installed commands match this search.", color = PaletteTheme.secondary, fontSize = 14.sp, lineHeight = 22.sp) }
                    if (inspection.matches.isEmpty() && inspection.candidates.isNotEmpty()) {
                        item { OutlinedButton(onClick = { if (actionCurrent()) showOtherVersions = !showOtherVersions }, shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("recorded-tests-other-versions")) {
                            Text(if (showOtherVersions) "Hide other recorded versions" else "See tests for other source versions", fontSize = 14.sp, lineHeight = 20.sp)
                        } }
                        if (showOtherVersions) {
                            item { Text("Historical records for different source files", color = PaletteTheme.ink, fontSize = 16.sp, lineHeight = 24.sp) }
                            inspection.candidates.forEach { snapshot -> snapshot.commands.forEach { command ->
                                inspection.evidence.records(snapshot, command.name).forEach { record -> if (matches(command.title, record)) {
                                    item("other-" + record.id) { RecordedWorkflowCard(record, command.title, ::actionCurrent,
                                        { (result as? RecordedViewResult.Ready)?.inspection === inspection && showOtherVersions && matches(command.title, record) }, { open(record, command.title) }) }
                                } }
                            } }
                        }
                    }
                }
                RecordedViewResult.Changed -> Unit
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun RecordedWorkflowCard(record: RecordedWorkflowObservation, title: String, actionCurrent: () -> Boolean,
                                  recordCurrent: () -> Boolean, open: () -> Unit) {
    val lifetime = remember(record) { RecordedViewLifetime() }
    var expanded by remember(record) { mutableStateOf(false) }
    DisposableEffect(lifetime) { onDispose { lifetime.active = false } }
    fun current() = lifetime.active && actionCurrent() && recordCurrent()
    Column(Modifier.fillMaxWidth().background(PaletteTheme.control, RoundedCornerShape(12.dp)).padding(16.dp).testTag("recorded-test-" + record.id),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = PaletteTheme.ink, fontSize = 16.sp, lineHeight = 24.sp)
        Text(record.outcome.label, color = PaletteTheme.secondary, fontSize = 14.sp, lineHeight = 22.sp)
        Text(record.workflow, color = PaletteTheme.ink, fontSize = 14.sp, lineHeight = 22.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { if (current()) expanded = !expanded }, enabled = current(), shape = RoundedCornerShape(10.dp),
                modifier = Modifier.heightIn(min = 48.dp).testTag("recorded-details-" + record.id)) { Text(if (expanded) "Hide details" else "Details", fontSize = 14.sp, lineHeight = 20.sp) }
            OutlinedButton(onClick = { if (current()) open() }, enabled = current(), shape = RoundedCornerShape(10.dp),
                modifier = Modifier.heightIn(min = 48.dp).testTag("recorded-report-" + record.id)) { Text("View report", fontSize = 14.sp, lineHeight = 20.sp) }
        }
        if (expanded) {
            Text(record.configuration, color = PaletteTheme.secondary, fontSize = 14.sp, lineHeight = 22.sp)
            record.phases.forEach { (name, phase) -> Text(name.replaceFirstChar { it.uppercase() } + " (" + phase.result.wire.replace('_', ' ') + "): " + phase.detail,
                color = PaletteTheme.secondary, fontSize = 14.sp, lineHeight = 22.sp) }
            record.limits.forEach { Text(it, color = PaletteTheme.secondary, fontSize = 14.sp, lineHeight = 22.sp) }
            val environment = record.environment
            Text("Recorded environment: Android API ${environment.androidApi}, ${environment.device}\n" +
                environment.profiles.joinToString("\n") + "\nWebView: " + (environment.webViewVersion?.let { "${environment.webViewPackage} $it" } ?: "not recorded"),
                color = PaletteTheme.secondary, fontSize = 14.sp, lineHeight = 22.sp)
            Text("App source: ${environment.appSource}\nApp APK: ${environment.appSha256}\nTest source: ${environment.testSource}\nTest APK: ${environment.testSha256}\nSource archive: ${record.archiveSha256}",
                color = PaletteTheme.secondary, fontSize = 14.sp, lineHeight = 22.sp)
        }
    }
}
