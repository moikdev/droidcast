@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.tinycast.android

import dev.tinycast.android.updates.UpdatesPalette

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ink = PaletteTheme.ink
private val muted = PaletteTheme.secondary
private val line = PaletteTheme.separator

@Composable
fun PaletteDialogs(store: PaletteStore, provided: Command?, globalEnabled: Boolean, enableGlobal: () -> Unit,
                   run: (Command) -> Unit, copy: (String) -> Unit, close: () -> Unit) {
    val dialog = store.dialog ?: return
    val selected = if (dialog in setOf("actions", "alias", "delete")) store.actionCommand ?: provided else provided
    if (dialog == "search-folders") { FolderSearchDialog(store, run, close); return }
    if (dialog.startsWith("extension")) { dev.tinycast.android.extensions.ExtensionDialogs(store, close); return }
    if (dialog.startsWith("backup")) { BackupDialog(store, run, close); return }
    if (dialog.startsWith("shortcut-") && dialog != "shortcut-help") { ShortcutDialogs(store, globalEnabled, enableGlobal, close); return }
    when (dialog) {
        "updates" -> UpdatesPalette(store.updates, close)
        "note", "link", "clip" -> Editor(store, dialog, close)
        "shortcut-help" -> Shell("Keyboard shortcut help", "Less reaching. More doing.", close) {
            Text("IN DROIDCAST", color = ink, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 8.dp))
            listOf("Focus search" to "Ctrl + L", "Open actions" to "Ctrl + K", "Navigate results" to "↑ / ↓", "Run selected command" to "Enter", "Pin / unpin selected" to "Ctrl + D", "Create a note" to "Ctrl + N", "Switch category" to "Ctrl + 1…8", "Save an editor" to "Ctrl + S / Ctrl + Enter", "Clear, go back, dismiss" to "Esc").forEach { ShortcutRow(it.first, it.second) }
            HorizontalDivider(color = line, modifier = Modifier.padding(vertical = 14.dp))
            ShortcutRow("Show or hide from another app", store.shortcutConfiguration.bindings[ShortcutRules.PALETTE]?.label() ?: "Not assigned")
            Text(if (globalEnabled) "Android approval is enabled. Check service connection in Settings → General." else "Enable the optional keyboard service in Preferences for the global shortcut.", color = muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.height(16.dp)); Button(onClick = close, modifier = Modifier.fillMaxWidth()) { Text("Got it") }
        }
        "preferences" -> Shell("Preferences", "Droidcast · ${BuildConfig.VERSION_NAME}", close) {
            Row(verticalAlignment = Alignment.CenterVertically) { Glyph("keyboard", ink, 26); Spacer(Modifier.width(12.dp)); Column { Text("Global keyboard shortcuts", fontSize = 16.sp, fontWeight = FontWeight.Medium); Text(if (globalEnabled) "Android approval enabled" else "Optional keyboard service", color = ink, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) } }
            Text("Open the palette and run assigned commands from another app with your hardware keyboard. Android requires you to enable the Droidcast keyboard shortcut accessibility service.", color = muted, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 18.dp))
            Text("The service filters your configured key combinations. It does not retrieve screen content or save keystrokes. You can turn it off in Android settings at any time.", color = muted, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 12.dp))
            TextButton(onClick = { store.dialog = "shortcut-settings" }) { Text("Configure keyboard shortcuts") }
            Button(onClick = enableGlobal, modifier = Modifier.fillMaxWidth().padding(top = 20.dp).testTag("enable-global")) { Text(if (globalEnabled) "Manage keyboard service" else "Open accessibility settings") }
            HorizontalDivider(color = line, modifier = Modifier.padding(vertical = 22.dp))
            Text("Your data stays on your device", fontWeight = FontWeight.Medium, fontSize = 15.sp)
            Text("No account, analytics or background clipboard monitoring. Your collections stay local. Store searches and downloads contact Raycast; extensions can make HTTPS requests when you run them. Files are limited to documents you choose and each extension's own data folder.", color = muted, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 10.dp))
            Text("Raycast Store extensions run with a subset of the Raycast API. Desktop automation, native Mac helpers and OAuth are unavailable. AI commands are not included.", color = muted, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 12.dp))
            TextButton(onClick = { store.refreshApps(); store.message = "Application list refreshed" }, modifier = Modifier.padding(top = 12.dp)) { Text("Refresh installed apps") }
            TextButton(onClick = { store.dialog = "search-folders" }) { Text("Search folders") }
            TextButton(onClick = { store.beginBackup() }) { Text("Backup and restore") }
            TextButton(onClick = { run(Command("web", "Source code", "", payload = "https://github.com/moikdev/droidcast")) }) { Text("Source code") }
            TextButton(onClick = { store.dialog = "licenses" }) { Text("License and attribution") }
        }
        "licenses" -> Shell("License and attribution", "Droidcast · Android adaptation of Tinycast", close) {
            Text("Tinycast Copyright (C) 2026 Abue Ammar. Android implementation and modifications: Droidcast contributors, 2026.", fontSize = 13.sp, lineHeight = 20.sp)
            Text("Licensed under the GNU Affero General Public License, version 3 or later. Distributed without warranty. React and the GitHub gemoji dataset are distributed under their MIT licenses. Full notices and corresponding source are included in the source repository and app assets.", color = muted, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 12.dp))
            TextButton(onClick = { run(Command("web", "License", "", payload = "https://github.com/moikdev/droidcast/blob/main/LICENSE")) }) { Text("View full license") }
            TextButton(onClick = { run(Command("web", "Source code", "", payload = "https://github.com/moikdev/droidcast")) }) { Text("Get source code") }
        }
        "alias" -> if (selected != null) {
            var alias by remember(selected.id) { mutableStateOf(selected.alias) }
            var error by remember { mutableStateOf<String?>(null) }
            Shell("Set an alias", "${selected.title} · a shorter way to find it", close) {
                Field("Alias", alias, { alias = it; error = null }, "e.g. calc", "alias-field")
                Text("Use one lowercase word. Leave empty to remove the alias.", color = muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                Button(onClick = { runCatching { store.setAlias(selected.id, alias) }.onSuccess { close() }.onFailure { error = it.message } }, modifier = Modifier.fillMaxWidth().padding(top = 18.dp).testTag("save-alias")) { Text("Save alias") }
            }
        }
        "delete" -> if (selected != null) Shell("Delete ${selected.kind.label.lowercase().removeSuffix("s")}?", selected.title, close) {
            Text("This removes the item from Droidcast. Original documents are kept on your device.", color = muted, fontSize = 13.sp, lineHeight = 20.sp)
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = close) { Text("Cancel") }; Spacer(Modifier.width(10.dp))
                Button(onClick = { store.delete(selected.id); close() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEBABAA)), modifier = Modifier.testTag("confirm-delete")) { Text("Delete") }
            }
        }
        "link-query" -> {
            val link = store.editing
            var argument by remember { mutableStateOf("") }
            Shell(link?.title ?: "Quicklink", "What would you like to search for?", close) {
                Field("Search terms", argument, { argument = it }, "Type your query", "link-query-field")
                Button(onClick = { link?.let { run(Command(it.id, it.title, it.body, Kind.LINK, "link", payload = it.body.replace("{query}", android.net.Uri.encode(argument)))) }; close() }, enabled = argument.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("Open quicklink") }
            }
        }
    }
}

@Composable
private fun Editor(store: PaletteStore, kind: String, close: () -> Unit) {
    val old = store.editing
    val draft = remember(store, kind, old?.id, store.focusEpoch) { store.editorDraft(kind) }
    var title by draft::title
    var body by draft::body
    var alias by draft::alias
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    var fieldReady by remember { mutableStateOf(false) }
    val type = when (kind) { "note" -> "note"; "link" -> "quicklink"; else -> "clip" }
    fun save() {
        runCatching { store.save(when (kind) { "note" -> "notes"; "link" -> "links"; else -> "clips" }, title, body, alias, old?.id?.takeIf { it.isNotEmpty() }) }
            .onSuccess { store.message = "${type.replaceFirstChar { it.uppercase() }} saved"; close() }
            .onFailure { error = it.message ?: "Could not save" }
    }
    Shell(if (old?.id.isNullOrEmpty()) "Create a $type" else "Edit $type", when (kind) { "note" -> "A place for the thought you don't want to lose."; "link" -> "Your favorite destination, one command away."; else -> "Keep useful text close. Stored only on this device." }, close,
        Modifier.onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && (it.isCtrlPressed xor it.isMetaPressed) && !it.isAltPressed && !it.isShiftPressed && (it.key == Key.S || it.key == Key.Enter)) { save(); true } else false
        }, footer = {
            if(LocalPaletteWide.current && LocalDensity.current.fontScale<=1.3f) Text("Ctrl + S", color=muted,fontSize=11.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = close) { Text("Cancel", color = muted) }
            Spacer(Modifier.width(8.dp))
            Button(onClick = ::save, modifier = Modifier.testTag("save-editor")) { Text("Save $type") }
        }) {
        LaunchedEffect(fieldReady) { if (fieldReady) focus.requestFocus() }
        Field("Title", title, { title = it; error = null }, "Give it a name", "editor-title", modifier = Modifier.focusRequester(focus).onGloballyPositioned { fieldReady = true })
        Spacer(Modifier.height(16.dp))
        if (kind == "link") {
            Field("URL", body, { body = it; error = null }, "https://example.com/search?q={query}", "editor-body")
            Text("Add {query} for a search command. Use an alias like g, then type g android tablets in the palette.", color = muted, fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.height(16.dp)); Field("Alias (optional)", alias, { alias = it; error = null }, "e.g. g", "editor-alias")
        } else {
            OutlinedTextField(body, { body = it }, label = { Text(if (kind == "note") "Note" else "Text") }, placeholder = { Text(if (kind == "note") "Start writing…" else "Your saved text") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 260.dp).testTag("editor-body"), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 22.sp), shape = RoundedCornerShape(10.dp), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp)) }

    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun ActionsMenu(store: PaletteStore, command: Command, run: (Command) -> Unit, copy: (String) -> Unit, close: () -> Unit) {
    data class Action(val label: String, val key: String, val perform: () -> Unit)
    val actions = buildList {
        add(Action(primaryActionTitle(command), "Enter") { close(); run(command) })
        if (command.id !in listOf("result", "web")) {
            add(Action(if (command.id in store.pins) "Unpin command" else "Pin command", "Ctrl D") { store.togglePin(command.id); close() })
            add(Action("Configure command", "Ctrl Shift ,") { close(); run(Command("configure-command", "Configure command", "", payload = command.id)) })
            add(Action("Set alias", "") { close(); run(Command("configure-alias", "Set alias", "", payload = command.id)) })
            if (ShortcutRules.targetAllowed(command.id)) add(Action("Set keyboard shortcut", "") { close(); run(Command("configure-shortcut", "Set keyboard shortcut", "", payload = command.id)) })
        }
        if (command.kind in listOf(Kind.NOTE, Kind.LINK, Kind.CLIP)) {
            add(Action("Edit ${command.kind.label.lowercase().removeSuffix("s")}", "") {
                store.editing = (store.notes + store.links + store.clips).first { it.id == command.id }
                store.dialog = when (command.kind) { Kind.NOTE -> "note"; Kind.LINK -> "link"; else -> "clip" }
            })
            add(Action("Copy content", "") { copy(command.payload); close() })
        }
        if (command.id.startsWith("files:index:")) add(Action("Manage search folder", "") { close(); run(Command("search-folders", "Search folders", "")) })
        else if (command.kind in listOf(Kind.NOTE, Kind.LINK, Kind.CLIP, Kind.FILE)) add(Action("Delete from Droidcast", "") { store.actionCommand = command; store.dialog = "delete" })
    }
    PaletteChoiceMenu(true, close, actions.mapIndexed { index, action ->
        val chord=when(action.key) {
            "Ctrl D" -> KeyChord(android.view.KeyEvent.KEYCODE_D,ShortcutRules.CTRL)
            "Ctrl Shift ," -> KeyChord(android.view.KeyEvent.KEYCODE_COMMA,ShortcutRules.CTRL or ShortcutRules.SHIFT)
            else -> null
        }
        PaletteChoice("action-${action.label}", action.label, shortcut=if(action.key.isBlank()) emptyList() else action.key.replace("Enter","↵").split(' '),
            icon=when { action.label.startsWith("Copy")->"clipboard"; action.label.startsWith("Delete")->"trash"; action.label.startsWith("Edit")->"note";
                action.label.contains("shortcut")->"keyboard"; action.label.contains("Pin")||action.label.startsWith("Unpin")->"star"; action.label.startsWith("Configure")->"settings"; else->"command" },
            separator=index==1,chord=chord) { action.perform() }
    }, title = command.title, searchable = true)
}

@Composable
internal fun Shell(title: String, subtitle: String, close: () -> Unit, modifier: Modifier = Modifier, embedded: Boolean = false, footer: (@Composable RowScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    if (embedded) {
        val focus = remember { FocusRequester() }; var ready by remember { mutableStateOf(false) }; var hasFocus by remember { mutableStateOf(false) }
        LaunchedEffect(ready) { if (ready && !hasFocus) focus.requestFocus() }
        Surface(shape = RoundedCornerShape(12.dp), color = PaletteTheme.surface, border = BorderStroke(1.dp, line), modifier = Modifier.fillMaxWidth().then(modifier)
            .focusRequester(focus).onFocusChanged { hasFocus = it.hasFocus }.onGloballyPositioned { ready = true }
            .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { close(); true } else false }.focusable()) {
            Column(Modifier.padding(16.dp)) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, fontSize = 11.sp, color = muted, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
                content()
                footer?.let { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = it) }
            }
        }
        return
    }
    val focus = remember { FocusRequester() }
    var ready by remember { mutableStateOf(false) }
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(ready) { if (ready && !hasFocus) focus.requestFocus() }
    PaletteFrame(close, modifier.focusRequester(focus).onFocusChanged { hasFocus = it.hasFocus }
        .onGloballyPositioned { ready = true }
        .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { close(); true } else false }.focusable()) {
        Row(Modifier.fillMaxWidth().heightIn(min=PaletteTheme.header).paletteHeaderSurface().padding(end=8.dp,top=4.dp,bottom=4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = close, modifier = Modifier.size(48.dp).semantics { contentDescription = "Back to commands" }.testTag("inline-back")) { Glyph("back",PaletteTheme.ink,24) }
            Spacer(Modifier.width(2.dp))
            Text(title, fontSize = 20.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        HorizontalDivider(color = line)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp)) {
            Text(subtitle, fontSize = 12.sp, color = muted, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 16.dp))
            content()
        }
        footer?.let {
            HorizontalDivider(color = line)
            Row(Modifier.fillMaxWidth().heightIn(min=PaletteTheme.footer).paletteFooterSurface().testTag("palette-footer").padding(horizontal=12.dp,vertical=4.dp), verticalAlignment = Alignment.CenterVertically, content = it)
        }
    }
}

@Composable
private fun Field(label: String, value: String, change: (String) -> Unit, placeholder: String, tag: String, modifier: Modifier = Modifier) {
    OutlinedTextField(value, change, label = { Text(label, fontSize = 12.sp) }, placeholder = { Text(placeholder, fontSize = 13.sp) }, singleLine = true,
        shape = RoundedCornerShape(10.dp), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp), modifier = modifier.paletteFocusBookmark().fillMaxWidth().testTag(tag))
}

@Composable
private fun ShortcutRow(label: String, key: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = PaletteTheme.ink, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(key, color = muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}
