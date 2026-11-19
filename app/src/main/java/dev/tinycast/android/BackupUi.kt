package dev.tinycast.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun BackupDialog(store: PaletteStore, run: (Command) -> Unit, close: () -> Unit, embedded: Boolean = false) {
    val preview = store.backupDocument
    val busy = store.backupBusy
    fun dismiss() { if (!busy) { if (embedded && preview != null) store.discardImport() else { store.discardImport(); close() } } }
    fun command(id: String) = run(Command(id, "Backup", ""))
    if (store.dialog == "backup-confirm" && preview != null) {
        val plan = runCatching { store.restorePlan() }
        Shell("Apply this backup?", "Only your selected categories will change.", { if (!busy) store.dialog = "backup-preview" }, embedded = embedded) {
            Text(if (store.restorePolicy == RestorePolicy.REPLACE) "Replace deletes the current contents of selected categories and uses the backup instead. Other categories and recent documents are kept." else "Merge keeps existing items and their identities. Duplicate or conflicting items keep the existing version.", fontSize = 13.sp, lineHeight = 20.sp)
            plan.getOrNull()?.let { Text(it.reviewSummary(), color = PaletteTheme.secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp)) }
            Text("Pins, aliases, ranking and keyboard bindings follow your selected merge/replace policy. Import never grants permissions or runs a command.", color = PaletteTheme.secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
            if (BackupCategory.SHORTCUTS in store.restoreCategories) Text("Selected keyboard bindings take effect after applying. They can work globally if the keyboard service is already enabled. Import never enables that Android service.", color = PaletteTheme.ink, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp).testTag("backup-shortcut-warning"))
            store.backupError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { store.dialog = "backup-preview" }, enabled = !busy) { Text("Back") }
                Button(onClick = store::applyBackup, enabled = plan.isSuccess && !busy, modifier = Modifier.testTag("backup-confirm")) { Text("Apply backup") }
            }
        }
        return
    }
    val importing = preview != null && store.dialog == "backup-preview"
    Shell(if (importing) "Preview backup" else "Backup and restore", if (importing) "Review categories and conflicts before applying." else "Portable collections · Droidcast JSON format v1", ::dismiss, embedded = embedded) {
        val available = if (importing) preview!!.categories else BackupCategory.entries.toSet()
        val selected = if (importing) store.restoreCategories else store.backupCategories
        val data = if (importing) preview!!.data else store.backupSnapshot()
        BackupCategory.entries.forEach { category ->
            val enabled = category in available && !busy
            Row(Modifier.fillMaxWidth().toggleable(value = category in selected, enabled = enabled, role = Role.Checkbox, onValueChange = { checked ->
                val next = if (checked) selected + category else selected - category
                if (importing) store.restoreCategories = next else store.backupCategories = next
                store.backupError = null
            }).testTag("backup-category-${category.name}").padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = category in selected, enabled = enabled, onCheckedChange = null)
                Text(category.label, modifier = Modifier.weight(1f), color = if (enabled) PaletteTheme.ink else PaletteTheme.secondary)
                Text(if (category in available) data.count(category).toString() else "Not included", fontSize = 12.sp, color = PaletteTheme.secondary)
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp), color = PaletteTheme.separator)
        Text("Backups contain readable text, including saved clips and notes. Choose a storage location you trust. Extension credentials/data, recent documents and their permissions, and accessibility consent are excluded.", color = PaletteTheme.secondary, fontSize = 12.sp, lineHeight = 18.sp)
        if (importing) {
            var inspect by remember(preview) { mutableStateOf(false) }
            TextButton(onClick = { inspect = !inspect }, modifier = Modifier.testTag("backup-inspect")) { Text(if (inspect) "Hide backup contents" else "Inspect backup contents") }
            if (inspect) {
                val entries = remember(preview, selected) { buildList<Pair<String, String>> {
                    if (BackupCategory.NOTES in selected) data.notes.forEach { add("Note · ${it.title}" to it.body.take(200)) }
                    if (BackupCategory.LINKS in selected) data.links.forEach { add("Quicklink · ${it.title}" to "${it.body}\nAlias: ${it.alias.ifEmpty { "None" }}") }
                    if (BackupCategory.CLIPS in selected) data.clips.forEach { add("Saved clip · ${it.title}" to it.body.take(200)) }
                    if (BackupCategory.PREFERENCES in selected) {
                        data.pins.sorted().forEach { add("Pin" to it) }
                        data.aliases.toSortedMap().forEach { (id, alias) -> add("Alias · $id" to alias.ifEmpty { "Disabled" }) }
                    }
                    if (BackupCategory.LEARNING in selected) data.usage.toSortedMap().forEach { (id, count) -> add("Usage · $id" to count.toString()) }
                    if (BackupCategory.SHORTCUTS in selected) data.shortcuts.bindings.toSortedMap().forEach { (id, chord) ->
                        val name = (data.notes + data.links + data.clips).firstOrNull { it.id == id }?.title ?: store.shortcutTitle(id)
                        add("Keyboard shortcut · $name" to chord.label())
                    }
                } }
                Text("All selected entries. Note and clip text previews show the first 200 characters.", fontSize = 11.sp, color = PaletteTheme.secondary)
                LazyColumn(Modifier.fillMaxWidth().height(180.dp).testTag("backup-contents")) {
                    items(entries) { (title, detail) ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(title, fontSize = 12.sp)
                            Text(detail, fontSize = 11.sp, color = PaletteTheme.secondary)
                        }
                    }
                }
            }
            listOf(RestorePolicy.MERGE to "Merge · keep existing conflicts", RestorePolicy.REPLACE to "Replace selected categories").forEach { (policy, label) ->
                Row(Modifier.fillMaxWidth().toggleable(store.restorePolicy == policy, role = Role.RadioButton, onValueChange = { store.restorePolicy = policy; store.backupError = null }).testTag("backup-policy-${policy.name}"), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = store.restorePolicy == policy, onClick = null)
                    Text(label, fontSize = 13.sp)
                }
            }
            val plan = runCatching { store.restorePlan() }
            plan.exceptionOrNull()?.message?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp).testTag("backup-plan-error")) }
            plan.getOrNull()?.let { Text(it.reviewSummary(), fontSize = 12.sp, color = PaletteTheme.secondary, modifier = Modifier.padding(top = 8.dp).testTag("backup-plan-summary")) }
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = store::discardImport, modifier = Modifier.testTag("backup-cancel-import")) { Text("Cancel import") }
                Button(onClick = { store.dialog = "backup-confirm" }, enabled = plan.isSuccess, modifier = Modifier.testTag("backup-review")) { Text("Review changes") }
            }
        } else {
            store.backupError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp).testTag("backup-error")) }
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
            Button(onClick = { command("backup-write") }, enabled = selected.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth().padding(top = 20.dp).testTag("backup-export")) { Text("Export selected categories…") }
            OutlinedButton(onClick = { command("backup-read") }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("backup-import")) { Text("Choose backup to preview…") }
            TextButton(onClick = ::dismiss, enabled = !busy, modifier = Modifier.align(Alignment.End).padding(top = 8.dp)) { Text("Done") }
        }
    }
}
