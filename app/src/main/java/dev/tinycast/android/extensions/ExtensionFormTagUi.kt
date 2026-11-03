package dev.tinycast.android.extensions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.tinycast.android.PaletteTheme
import org.json.JSONArray
import org.json.JSONObject

private fun tagValues(value: Any?): List<String>? {
    val array = value as? JSONArray ?: return null
    return (0 until array.length()).map { array.opt(it) as? String ?: return null }
}

@Composable
internal fun NativeFormTagPicker(root: JSONObject, field: JSONObject, owner: FormFieldOwner?, liveRoot: () -> JSONObject?,
                                 modifier: Modifier, onOpen: () -> Unit, currentValue: () -> Any?, change: (List<String>) -> Boolean) {
    val p = field.props()
    val id = p.optString("id")
    val title = (p.opt("title") as? String).orEmpty().ifBlank { "Choose categories" }
    val choices = formTagChoices(field)
    var selected by remember(owner, root) { mutableStateOf(tagValues(p.opt("value"))) }
    var opening by remember(owner) { mutableStateOf<Any?>(null) }
    DisposableEffect(owner) { onDispose { opening = null } }
    val values = selected.orEmpty()
    val label = when {
        selected == null -> "Selection unavailable"
        values.isEmpty() -> "Choose categories"
        values.size > 2 -> "${values.size} categories selected"
        else -> values.joinToString(", ") { value -> choices.firstOrNull { it.value == value }?.title ?: "Unavailable selection" }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = {
            if (owner != null && liveFormField(owner, liveRoot()) != null) { onOpen(); opening = Any() }
        }, enabled = owner != null, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("extension-tags-$id")
            .semantics { contentDescription = title; stateDescription = label }) { Text(label, fontSize = 14.sp, lineHeight = 20.sp) }
    }
    val token = opening
    if (token != null) Dialog(onDismissRequest = { if (opening === token) opening = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(12.dp).widthIn(max = 560.dp).fillMaxWidth()
            .heightIn(max = (LocalConfiguration.current.screenHeightDp - 48).coerceAtLeast(160).dp)
            .testTag("extension-tags-dialog-$id"), shape = MaterialTheme.shapes.extraLarge, color = PaletteTheme.surface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, fontSize = 18.sp, lineHeight = 26.sp, color = PaletteTheme.ink)
                val unavailable = values.filter { value -> choices.none { it.value == value } }
                if (selected == null) Text("This selection cannot be displayed. Clear it to choose again.", color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp, lineHeight = 20.sp)
                if (choices.isEmpty() && unavailable.isEmpty()) Text("No categories available", color = PaletteTheme.secondary,
                    fontSize = 13.sp, lineHeight = 20.sp)
                else LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("extension-tags-options-$id"),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(unavailable, key = { index, value -> "unavailable-$index-$value" }) { index, value ->
                        TextButton(onClick = {
                            if (opening !== token || owner == null || liveFormField(owner, liveRoot()) == null) return@TextButton
                            val latest = tagValues(currentValue()) ?: return@TextButton
                            if (value in latest) {
                                val next = updateFormTags(latest, value, false)
                                if (change(next)) selected = next
                            }
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("extension-tags-unavailable-$id-$index")) {
                            Text("Remove unavailable selection", fontSize = 14.sp, lineHeight = 20.sp)
                        }
                    }
                    itemsIndexed(choices, key = { _, choice -> choice.nativeId }) { index, choice ->
                        val checked = choice.value in values
                        ExtensionCheckboxRow(choice.title, checked, choose@{ nextChecked ->
                            if (opening !== token || owner == null || liveFormChoice(owner, choice, liveRoot()) == null) return@choose
                            val latest = tagValues(currentValue()) ?: return@choose
                            if ((choice.value in latest) != checked) return@choose
                            val next = updateFormTags(latest, choice.value, nextChecked)
                            if (change(next)) selected = next
                        }, Modifier.testTag("extension-tags-option-$id-$index"))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        if (opening === token && owner != null && liveFormField(owner, liveRoot()) != null && change(emptyList())) selected = emptyList()
                    }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("extension-tags-clear-$id")) { Text("Clear all") }
                    Button(onClick = { if (opening === token) opening = null }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        .testTag("extension-tags-done-$id")) { Text("Done") }
                }
            }
        }
    }
}
