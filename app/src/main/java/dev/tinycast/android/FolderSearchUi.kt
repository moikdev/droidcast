package dev.tinycast.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun FolderSearchDialog(store: PaletteStore, run: (Command) -> Unit, close: () -> Unit, embedded: Boolean = false) {
    val model = store.folderSearch
    var removing by remember { mutableStateOf<SearchFolder?>(null) }
    BackHandler(enabled = removing != null) { removing = null }
    if (removing != null) {
        val folder = removing!!
        Shell("Remove search folder?", folder.title, { removing = null }, embedded = embedded) {
            Text("This removes its indexed metadata. Droidcast releases the folder read grant if it acquired that grant here. Your files and individually picked recent documents are kept.", fontSize = 13.sp)
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { removing = null }) { Text("Cancel") }
                Button(onClick = { model.remove(folder); removing = null }, modifier = Modifier.testTag("folder-remove-confirm")) { Text("Remove folder") }
            }
        }
        return
    }
    Shell("Search folders", "Search filenames and paths in folders you choose.", close, embedded = embedded) {
        Text("Android controls access through its folder chooser. Indexing reads metadata, not file contents. No whole-device storage permission is requested.", fontSize = 12.sp, color = PaletteTheme.secondary)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Enable folder search", Modifier.weight(1f), fontSize = 14.sp)
            Switch(model.enabled, onCheckedChange = { model.changeEnabled(it) }, enabled = !model.loading && !model.changing, modifier = Modifier.testTag("folder-enabled"))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Include in main search", Modifier.weight(1f), fontSize = 13.sp)
            Switch(model.includeInLauncher, onCheckedChange = { model.changeIncludeInLauncher(it) }, enabled = !model.loading && !model.changing, modifier = Modifier.testTag("folder-launcher"))
        }
        Text("The Files filter always searches enabled folders. Main search includes explicit file favorites even when this switch is off.", fontSize = 11.sp, color = PaletteTheme.secondary)
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { run(Command("folder-add", "Add search folder", "")) }, enabled = !model.changing && !model.loading && !model.selecting, modifier = Modifier.testTag("folder-add")) { Text("Add folder") }
            TextButton(onClick = model::refresh, enabled = model.enabled && !model.changing && !model.busy, modifier = Modifier.testTag("folder-refresh")) { Text("Refresh") }
            if (model.busy) TextButton(onClick = model::cancelRefresh, modifier = Modifier.testTag("folder-cancel")) { Text("Cancel scan") }
        }
        if (model.busy || model.loading || model.changing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
        Text(model.status, fontSize = 12.sp, color = PaletteTheme.secondary, modifier = Modifier.padding(top = 10.dp).testTag("folder-status"))
        model.error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp).testTag("folder-error")) }
        if (model.selecting) Text("Complete or cancel Android's folder chooser to continue.", fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        if (model.folders.isEmpty()) Text("No folders selected", color = PaletteTheme.secondary, fontSize = 13.sp, modifier = Modifier.padding(top = 18.dp).testTag("folder-empty"))
        model.folders.forEach { folder ->
            HorizontalDivider(color = PaletteTheme.separator, modifier = Modifier.padding(top = 16.dp, bottom = 10.dp))
            Text(folder.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(folder.uri, fontSize = 10.sp, color = PaletteTheme.secondary, modifier = Modifier.padding(top = 4.dp))
            model.errors[folder.uri]?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
            Row {
                TextButton(onClick = { run(Command("folder-reauthorize", "Reauthorize folder", "", payload = folder.uri)) }, enabled = !model.changing && !model.selecting) { Text("Reauthorize") }
                TextButton(onClick = { removing = folder }, enabled = !model.changing, modifier = Modifier.testTag("folder-remove-${folder.uri}")) { Text("Remove") }
            }
        }
        Text("Index limits: ${FolderIndexRules.MAX_ENTRIES} items, ${FolderIndexRules.MAX_DEPTH} levels, ${FolderIndexRules.MAX_FOLDERS} folders. Cloud availability and metadata completeness depend on the provider. Refresh checks changes; this does not watch every filesystem event.", fontSize = 11.sp, color = PaletteTheme.secondary, modifier = Modifier.padding(top = 18.dp))
        TextButton(onClick = { if (embedded) run(Command("open-file-search", "Search files", "")) else { close(); store.filter("Files") } }, enabled = !model.busy && !model.changing, modifier = Modifier.testTag("folder-search-now")) { Text("Search files") }
    }
}

@Composable
internal fun FileSearchToolbar(store: PaletteStore, run: (Command) -> Unit) {
    val model = store.folderSearch
    var filter by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (model.busy) "Indexing…" else if (model.searching) "Searching…" else if (!model.enabled) "Recent documents" else "${model.count} indexed items", fontSize = 11.sp, color = PaletteTheme.secondary, modifier = Modifier.weight(1f).testTag("file-search-status"))
        Box {
            TextButton(onClick = { filter = true }, modifier = Modifier.testTag("file-type").paletteFocusBookmark()) { Text(model.type.label, fontSize = 11.sp) }
            PaletteChoiceMenu(filter, { filter = false }, FileSearchType.entries.map { type ->
                PaletteChoice("file-type-${type.name}", type.label, checked = model.type == type) { model.filterType(type); filter = false }
            }, title = "File type")
        }
        TextButton(onClick = { run(Command("search-folders", "Search folders", "")) }, modifier = Modifier.testTag("file-search-folders")) { Text("Folders", fontSize = 11.sp) }
    }
}
