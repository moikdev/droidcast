package dev.tinycast.android.extensions

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.tinycast.android.PaletteTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

private data class FileOptions(val files: Boolean, val directories: Boolean, val multiple: Boolean, val hidden: Boolean)
private fun fileOptions(field: JSONObject): FileOptions = field.props().let {
    FileOptions(it.optBoolean("canChooseFiles", true), it.optBoolean("canChooseDirectories", false),
        it.optBoolean("allowMultipleSelection", false), it.optBoolean("showHiddenFiles", false))
}
private fun fileValues(value: Any?): List<String>? {
    val array = value as? JSONArray ?: return null
    if (array.length() > 512) return null
    return (0 until array.length()).map { array.opt(it) as? String ?: return null }
}

/** Every launch gets a fresh unsaved key. A recreated/replacement command
 * cannot register the key of an earlier request and inherit its result. A
 * disposed field leaves only a tombstone to consume and unregister that result.
 */
internal class FormDocumentPicker(private val registry: ActivityResultRegistry) {
    private class Pending(var receive: ((ActivityResult) -> Unit)?, var launcher: ActivityResultLauncher<Intent>? = null)
    private var pending: Pending? = null
    private var closed = false
    fun launch(intent: Intent, receive: (ActivityResult) -> Unit) {
        check(!closed && pending == null) { "A document picker is already open" }
        val entry = Pending(receive)
        pending = entry
        try {
            entry.launcher = registry.register("extension-form-documents-${UUID.randomUUID()}", ActivityResultContracts.StartActivityForResult()) { result ->
                if (pending === entry) pending = null
                entry.launcher?.unregister()
                val callback = entry.receive; entry.receive = null
                if (!closed) callback?.invoke(result)
            }
            entry.launcher!!.launch(intent)
        } catch (failure: Exception) {
            if (pending === entry) pending = null
            entry.receive = null; entry.launcher?.unregister()
            throw failure
        }
    }
    fun close() { closed = true; pending?.receive = null }
}

private class FilePick(val options: FileOptions, val previous: List<String>, val directory: Boolean)

@Composable
internal fun NativeFormFilePicker(root: JSONObject, field: JSONObject, owner: FormFieldOwner?, liveRoot: () -> JSONObject?,
    storage: () -> FormFileStorage?, modifier: Modifier, onOpen: () -> Unit, currentValue: () -> Any?, change: (List<String>) -> Boolean) {
    val p = field.props()
    val id = p.optString("id")
    val title = (p.opt("title") as? String).orEmpty().ifBlank { "Choose documents" }
    val options = fileOptions(field)
    val registry = LocalActivityResultRegistryOwner.current?.activityResultRegistry
    val picker = remember(owner, registry) { registry?.let(::FormDocumentPicker) }
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    var selection by remember(owner, root) { mutableStateOf(fileValues(p.opt("value"))) }
    var opening by remember(owner) { mutableStateOf<Any?>(null) }
    var pick by remember(owner) { mutableStateOf<FilePick?>(null) }
    var operation by remember(owner) { mutableStateOf<FormDocumentImportRequest?>(null) }
    var job by remember(owner) { mutableStateOf<Job?>(null) }
    var problem by remember(owner) { mutableStateOf<String?>(null) }
    DisposableEffect(picker, owner) {
        onDispose { opening = null; pick = null; operation?.cancel(); job?.cancel(); picker?.close() }
    }
    fun matches(expected: FilePick): Boolean {
        if (pick !== expected || opening == null || owner == null) return false
        val current = liveFormField(owner, liveRoot()) ?: return false
        return fileOptions(current) == expected.options && fileValues(currentValue()) == expected.previous
    }
    fun stop() { pick = null; operation?.cancel(); job?.cancel(); operation = null; job = null }
    fun choose(directory: Boolean) {
        if (operation != null || pick != null || picker == null || owner == null) return
        val current = liveFormField(owner, liveRoot()) ?: return
        val latest = fileOptions(current)
        if (if (directory) !latest.directories else !latest.files) return
        val previous = fileValues(currentValue()) ?: return
        val expected = FilePick(latest, previous, directory)
        pick = expected; problem = null
        val intent = if (directory) Intent(Intent.ACTION_OPEN_DOCUMENT_TREE) else
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, latest.multiple)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            picker.launch(intent) result@{ result ->
                if (!matches(expected)) { if (pick === expected) pick = null; return@result }
                if (result.resultCode != Activity.RESULT_OK) { pick = null; return@result }
                val data = result.data
                val clip = data?.clipData
                if (clip != null && clip.itemCount > 512) { problem = "Choose up to 512 documents at a time."; pick = null; return@result }
                val keys = (if (clip != null) (0 until clip.itemCount).map { clip.getItemAt(it).uri?.toString().orEmpty() }
                    else listOfNotNull(data?.data?.toString())).distinct()
                if (keys.isEmpty() || keys.any { it.isBlank() } || ((!latest.multiple || directory) && keys.size != 1)) {
                    problem = "The picker did not return a supported selection."; pick = null; return@result
                }
                val location = storage()
                if (location == null) { pick = null; return@result }
                val request = FormDocumentImportRequest(location, keys, !directory && latest.files, directory && latest.directories) { signal ->
                    AndroidFormDocuments(resolver, signal)
                }
                operation = request
                job = scope.launch {
                    try {
                        withTimeout(30_000) { request.prepare() }
                        request.deliver { paths ->
                            if (!matches(expected) || storage() != location) return@deliver false
                            val next = if (latest.multiple) expected.previous + paths else paths
                            if (next.size > 512) { problem = "Remove some selections before adding more documents."; return@deliver false }
                            if (change(next)) { selection = next; true } else false
                        }
                    } catch (_: TimeoutCancellationException) {
                        if (pick === expected) problem = "The document provider took too long. You can try again."
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (_: Exception) {
                        // Provider exceptions can include private URIs or paths.
                        if (pick === expected) problem = "Could not import this selection. Check access, document names and the import limits."
                    } finally {
                        request.cancel()
                        if (operation === request) { operation = null; job = null }
                        if (pick === expected) pick = null
                    }
                }
            }
        } catch (_: Exception) { pick = null; problem = "Android's document picker is unavailable." }
    }
    val values = selection.orEmpty()
    val label = when {
        selection == null -> "Selection unavailable"
        values.isEmpty() -> "Choose documents"
        values.size == 1 -> File(values.single()).name.ifBlank { "Selected document" }
        else -> "${values.size} documents selected"
    }
    OutlinedButton(onClick = {
        if (owner != null && liveFormField(owner, liveRoot()) != null) { onOpen(); problem = null; opening = Any() }
    }, enabled = owner != null && picker != null && (options.files || options.directories || values.isNotEmpty()),
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("extension-files-$id")
            .semantics { contentDescription = title; stateDescription = label }) { Text(label, fontSize = 14.sp, lineHeight = 20.sp) }
    if (!options.files && !options.directories) Text("This field allows no new documents.", color = PaletteTheme.secondary, fontSize = 13.sp, lineHeight = 20.sp)
    val token = opening
    if (token != null) Dialog(onDismissRequest = { if (opening === token) { stop(); opening = null } },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(12.dp).widthIn(max = 560.dp).fillMaxWidth()
            .heightIn(max = (LocalConfiguration.current.screenHeightDp - 48).coerceAtLeast(160).dp)
            .testTag("extension-files-dialog-$id"), shape = MaterialTheme.shapes.extraLarge, color = PaletteTheme.surface) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, fontSize = 18.sp, lineHeight = 26.sp, color = PaletteTheme.ink)
                Text("Import copies for this extension. Originals stay in their current location. Up to 16 MB per file and 64 MB per import.",
                    color = PaletteTheme.secondary, fontSize = 13.sp, lineHeight = 20.sp)
                if (options.hidden) Text("Android's picker controls which hidden files are visible.", color = PaletteTheme.secondary, fontSize = 13.sp, lineHeight = 20.sp)
                if (operation != null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Importing documents…", fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.testTag("extension-files-progress-$id"))
                    TextButton(onClick = { if (opening === token) stop() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("extension-files-cancel-$id")) { Text("Cancel import") }
                } else {
                    problem?.let { ExtensionFieldFeedback(it, "", "files-import-$id") }
                    if (selection == null) Text("This selection cannot be displayed. Clear it to choose again.", color = MaterialTheme.colorScheme.error, fontSize = 13.sp, lineHeight = 20.sp)
                    values.forEachIndexed { index, path ->
                        Column(Modifier.fillMaxWidth().testTag("extension-files-selected-$id-$index")) {
                            Text(File(path).name.ifBlank { "Selected document" }, color = PaletteTheme.ink, fontSize = 14.sp, lineHeight = 20.sp)
                            TextButton(onClick = {
                                if (opening !== token || pick != null || owner == null || liveFormField(owner, liveRoot()) == null) return@TextButton
                                val current = fileValues(currentValue()) ?: return@TextButton
                                if (current != values) return@TextButton
                                val next = current.filterIndexed { item, _ -> item != index }
                                if (change(next)) selection = next
                            }, modifier = Modifier.heightIn(min = 48.dp).testTag("extension-files-remove-$id-$index")
                                .semantics { contentDescription = "Remove ${File(path).name.ifBlank { "document" }}" }) { Text("Remove") }
                        }
                    }
                    if (options.files) Button(onClick = { if (opening === token) choose(false) }, enabled = pick == null && selection != null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("extension-files-choose-$id")) { Text(if (options.multiple) "Add files" else "Choose file") }
                    if (options.directories) Button(onClick = { if (opening === token) choose(true) }, enabled = pick == null && selection != null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("extension-files-folder-$id")) { Text(if (options.multiple) "Add folder" else "Choose folder") }
                    TextButton(onClick = {
                        if (opening === token && pick == null && owner != null && liveFormField(owner, liveRoot()) != null && change(emptyList())) selection = emptyList()
                    }, enabled = pick == null && (selection == null || values.isNotEmpty()), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("extension-files-clear-$id")) { Text("Clear selection") }
                    Button(onClick = { if (opening === token) { stop(); opening = null } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("extension-files-done-$id")) { Text("Done") }
                }
            }
        }
    }
}
