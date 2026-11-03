package dev.tinycast.android.extensions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tinycast.android.PaletteChoice
import dev.tinycast.android.PaletteChoiceMenu
import dev.tinycast.android.PaletteTheme
import dev.tinycast.android.paletteFocusBookmark
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun ExtensionFieldLabel(title: String, tag: String) {
    if (title.isNotBlank()) Text(title, color = PaletteTheme.ink, fontSize = 14.sp, lineHeight = 20.sp,
        modifier = Modifier.fillMaxWidth().testTag(tag))
}

@Composable
internal fun ExtensionFieldFeedback(errorText: String, info: String, id: String) {
    if (errorText.isNotBlank()) Text(errorText, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, lineHeight = 20.sp,
        modifier = Modifier.fillMaxWidth().testTag("extension-field-error-$id").semantics { error(errorText) })
    if (info.isNotBlank()) Text(info, color = PaletteTheme.secondary, fontSize = 13.sp, lineHeight = 20.sp,
        modifier = Modifier.fillMaxWidth().testTag("extension-field-info-$id"))
}

@Composable
internal fun ExtensionCheckboxRow(label: String, checked: Boolean, change: (Boolean) -> Unit,
                                  modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(checked, role = Role.Checkbox, onValueChange = change).padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Checkbox(checked, onCheckedChange = null) }
        Text(label, color = PaletteTheme.ink, fontSize = 14.sp, lineHeight = 20.sp,
            modifier = Modifier.weight(1f).padding(start = 8.dp))
    }
}

@Composable
internal fun NativeExtensionForm(root: JSONObject, liveRoot: () -> JSONObject?, dispatch: (String?, JSONArray) -> Unit,
                                  dispatchFocus: (String?, String, Boolean, Any) -> Unit,
                                  submission: FormSubmitPreparation, fileStorage: () -> FormFileStorage?, modifier: Modifier = Modifier) {
    val rootId = root.optString("id")
    val scroll = remember(rootId) { androidx.compose.foundation.ScrollState(0) }
    val values = remember(rootId) { FormEditorValues() }
    // This provider belongs to CommandRunner's captured runtime/launch epoch.
    // Keeping it with this form prevents an old callback adopting a new session.
    val focus = remember(rootId) {
        FormFocusDelivery({ liveFormField(it, liveRoot()) != null }) { owner, focused ->
            liveFormField(owner, liveRoot())?.handle(if (focused) "onTinycastFocus" else "onTinycastBlur")?.let {
                val value = values.value(owner) ?: return@let
                dispatchFocus(it, owner.logicalId, focused, value)
            }
        }
    }
    val nativeFocus = LocalFocusManager.current
    DisposableEffect(focus, values, submission, nativeFocus) {
        val token = Any()
        submission.attach(token) {
            focus.blurCurrent()
            nativeFocus.clearFocus(force = true)
        }
        onDispose { submission.detach(token); focus.clear(); values.clear() }
    }
    Column(modifier.fillMaxWidth().verticalScroll(scroll).padding(20.dp).testTag("extension-form"),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        root.children().forEach { node -> key(node.optString("id")) { NativeFormNode(root, node, liveRoot, dispatch, focus, values, fileStorage) } }
    }
}

@Composable
private fun NativeFormNode(root: JSONObject, node: JSONObject, liveRoot: () -> JSONObject?,
                           dispatch: (String?, JSONArray) -> Unit, focus: FormFocusDelivery, values: FormEditorValues, fileStorage: () -> FormFileStorage?) {
    val p = node.props()
    val type = node.optString("type")
    val id = p.optString("id")
    val title = (p.opt("title") as? String).orEmpty()
    val owner = formFieldOwner(root, node)
    val errorText = (p.opt("error") as? String).orEmpty()
    val info = (p.opt("info") as? String).orEmpty()
    owner?.let { values.observe(it, root, formFieldValue(node)) }
    DisposableEffect(owner, values) { onDispose { owner?.let(values::remove) } }
    var nativeFocused by remember(owner) { mutableStateOf(false) }
    val focusModifier = Modifier.onFocusChanged {
        if (nativeFocused != it.isFocused) {
            nativeFocused = it.isFocused
            owner?.let { current -> if (nativeFocused) focus.focus(current) else focus.blur(current) }
        }
    }
    val nativeFocus = LocalFocusManager.current
    fun beginPickerFocus() {
        val fieldOwner = owner ?: return
        if (liveFormField(fieldOwner, liveRoot()) == null) return
        // Touch buttons can leave the previous text editor natively focused.
        // Release it before moving logical focus so returning to the same
        // editor delivers onFocus. Preserve an already focused picker control.
        if (!nativeFocused) nativeFocus.clearFocus(force = true)
        focus.focus(fieldOwner)
    }
    fun change(value: Any, observedValue: Any = value): Boolean {
        val fieldOwner = owner ?: return false
        val current = liveFormField(fieldOwner, liveRoot()) ?: return false
        val handle = current.handle("onTinycastChange") ?: return false
        values.edit(fieldOwner, observedValue)
        dispatch(handle, JSONArray().put(value))
        return true
    }
    fun currentValue(): Any? {
        val fieldOwner = owner ?: return null
        val latest = liveRoot() ?: return null
        val current = liveFormField(fieldOwner, latest) ?: return null
        values.observe(fieldOwner, latest, formFieldValue(current))
        return values.value(fieldOwner)
    }
    when (type) {
        "Form.TextField", "Form.PasswordField", "Form.TextArea" -> {
            val supplied = (p.opt("value") as? String).orEmpty()
            // A new publication is authoritative even when a controlled field
            // rejects an edit and repeats its previous prop value.
            var text by remember(owner, root) { mutableStateOf(supplied) }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ExtensionFieldLabel(title, "extension-field-title-$id")
                OutlinedTextField(text, { next -> if (change(next)) text = next },
                    placeholder = { Text(p.optString("placeholder")) }, singleLine = type != "Form.TextArea",
                    minLines = 1, maxLines = if (type == "Form.TextArea") 8 else 1,
                    isError = errorText.isNotBlank(),
                    visualTransformation = if (type == "Form.PasswordField") PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = if (type == "Form.PasswordField") KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false) else KeyboardOptions.Default,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).paletteFocusBookmark().then(focusModifier)
                        .testTag("extension-field-$id").semantics {
                            contentDescription = title
                            if (errorText.isNotBlank()) error(errorText)
                        })
                ExtensionFieldFeedback(errorText, info, id)
            }
        }
        "Form.Checkbox" -> {
            val requester = remember(owner) { FocusRequester() }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ExtensionFieldLabel(title, "extension-field-title-$id")
                ExtensionCheckboxRow(p.optString("label", title), p.optBoolean("value"), { next ->
                    if (owner != null && liveFormField(owner, liveRoot()) != null) {
                        // A touch checkbox normally leaves the text editor's
                        // native focus in place. Move it with our logical Form
                        // focus so returning to that editor delivers onFocus.
                        requester.requestFocus(); focus.focus(owner); change(next)
                    }
                }, Modifier.paletteFocusBookmark(requester).focusProperties { canFocus = true }
                    .then(focusModifier).testTag("extension-checkbox-$id"))
                ExtensionFieldFeedback(errorText, info, id)
            }
        }
        "Form.DatePicker" -> {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ExtensionFieldLabel(title, "extension-field-title-$id")
                NativeFormDatePicker(node, owner, liveRoot, Modifier.paletteFocusBookmark().then(focusModifier),
                    onOpen = ::beginPickerFocus, change = { selected ->
                        val iso = selected?.toString()
                        change(iso ?: JSONObject.NULL, iso?.let { JSONObject().put("\$date", it) } ?: JSONObject.NULL)
                    })
                ExtensionFieldFeedback(errorText, info, id)
            }
        }
        "Form.TagPicker" -> {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ExtensionFieldLabel(title, "extension-field-title-$id")
                NativeFormTagPicker(root, node, owner, liveRoot, Modifier.paletteFocusBookmark().then(focusModifier),
                    onOpen = ::beginPickerFocus, currentValue = ::currentValue,
                    change = { selected -> change(JSONArray(selected)) })
                ExtensionFieldFeedback(errorText, info, id)
            }
        }
        "Form.FilePicker" -> {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ExtensionFieldLabel(title, "extension-field-title-$id")
                NativeFormFilePicker(root, node, owner, liveRoot, fileStorage, Modifier.paletteFocusBookmark().then(focusModifier),
                    onOpen = ::beginPickerFocus, currentValue = ::currentValue,
                    change = { selected -> change(JSONArray(selected)) })
                ExtensionFieldFeedback(errorText, info, id)
            }
        }
        "Form.Dropdown" -> {
            val choices = formChoices(node)
            val value = (p.opt("value") as? String).orEmpty()
            val label = formChoiceTitle(value, choices, p.optString("placeholder", "Choose…"))
            var menuOwner by remember(owner) { mutableStateOf<Any?>(null) }
            val menu = menuOwner
            DisposableEffect(owner) { onDispose { menuOwner = null } }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ExtensionFieldLabel(title, "extension-field-title-$id")
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = {
                        if (owner != null && liveFormField(owner, liveRoot()) != null) { focus.focus(owner); menuOwner = Any() }
                    }, enabled = choices.isNotEmpty() && owner != null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).paletteFocusBookmark().then(focusModifier)
                            .testTag("extension-choice-$id").semantics { contentDescription = title; stateDescription = label }) {
                        Text(label, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                    PaletteChoiceMenu(menu != null, { if (menuOwner === menu) menuOwner = null },
                        choices.map { choice ->
                            PaletteChoice("form-option-${node.optString("id")}-${choice.nativeId}-${choice.value}", choice.title,
                                checked = value == choice.value, labelLines = Int.MAX_VALUE, activateBeforeDismiss = true) choose@{
                                if (menu == null || menuOwner !== menu || owner == null) return@choose
                                val current = liveFormChoice(owner, choice, liveRoot())
                                menuOwner = null
                                if (current != null) {
                                    values.edit(owner, current.second.value)
                                    dispatch(current.first.handle("onTinycastChange"), JSONArray().put(current.second.value))
                                }
                            }
                        }, title = title)
                }
                if (choices.isEmpty()) Text("No choices available", color = PaletteTheme.secondary, fontSize = 13.sp, lineHeight = 20.sp)
                ExtensionFieldFeedback(errorText, info, id)
            }
        }
        "Form.Description" -> Text(p.optString("text"), color = PaletteTheme.secondary, fontSize = 13.sp, lineHeight = 20.sp)
        "Form.Separator" -> HorizontalDivider(color = PaletteTheme.separator)
        else -> if (node.children().isNotEmpty()) node.children().forEach { child ->
            key(child.optString("id")) { NativeFormNode(root, child, liveRoot, dispatch, focus, values, fileStorage) }
        } else Text("$type is not supported yet", color = PaletteTheme.secondary, fontSize = 13.sp, lineHeight = 20.sp)
    }
}
