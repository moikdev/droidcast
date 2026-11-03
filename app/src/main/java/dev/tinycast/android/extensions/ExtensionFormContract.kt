package dev.tinycast.android.extensions

import org.json.JSONObject

internal data class FormFieldOwner(val rootId: String, val nativeId: String, val logicalId: String, val type: String)
internal data class FormChoice(val nativeId: String, val value: String, val title: String)

internal fun sameFormField(owner: FormFieldOwner, rootId: String, nativeId: String, logicalId: Any?, type: String): Boolean =
    owner.rootId == rootId && owner.nativeId == nativeId && owner.logicalId == logicalId && owner.type == type

internal fun currentFormChoice(expected: FormChoice, choices: List<FormChoice>): FormChoice? = choices.firstOrNull { it == expected }

private val inputTypes = setOf("Form.TextField", "Form.PasswordField", "Form.TextArea", "Form.Checkbox", "Form.Dropdown",
    "Form.DatePicker", "Form.TagPicker", "Form.FilePicker")
private fun formProps(node: JSONObject) = node.optJSONObject("props") ?: JSONObject()
private fun formChildren(node: JSONObject): List<JSONObject> {
    val children = node.optJSONArray("children") ?: return emptyList()
    return (0 until children.length()).mapNotNull { children.optJSONObject(it) }
}
private fun descendants(node: JSONObject): Sequence<JSONObject> = sequence {
    yield(node)
    formChildren(node).forEach { yieldAll(descendants(it)) }
}

internal fun formFieldOwner(root: JSONObject, field: JSONObject): FormFieldOwner? {
    val type = field.optString("type")
    val rootId = root.optString("id")
    val nativeId = field.optString("id")
    val logicalId = formProps(field).opt("id") as? String
    if (root.optString("type") != "Form" || type !in inputTypes || rootId.isBlank() || nativeId.isBlank() || logicalId.isNullOrBlank()) return null
    return FormFieldOwner(rootId, nativeId, logicalId, type)
}

/** Resolve continuous field events against the latest props for the same field.
 * Callback handles can change on an ordinary React render; field/root/session
 * identity, rather than an old callback closure, determines its authority.
 */
internal fun liveFormField(owner: FormFieldOwner, root: JSONObject?): JSONObject? {
    if (root == null || root.optString("type") != "Form" || root.optString("id") != owner.rootId) return null
    return descendants(root).firstOrNull {
        sameFormField(owner, root.optString("id"), it.optString("id"), formProps(it).opt("id"), it.optString("type"))
    }
}

internal fun formHasErrors(root: JSONObject?): Boolean = root?.optString("type") == "Form" && descendants(root).any {
    it.optString("type") in inputTypes && (formProps(it).opt("error") as? String)?.isNotBlank() == true
}

/** Keep typed field values intact for event-time focus/blur delivery. */
internal fun formFieldValue(field: JSONObject): Any {
    val props = formProps(field)
    return when (field.optString("type")) {
        "Form.Checkbox" -> props.optBoolean("value")
        "Form.DatePicker" -> props.opt("value") ?: JSONObject.NULL
        "Form.TagPicker", "Form.FilePicker" -> props.opt("value") ?: org.json.JSONArray()
        else -> (props.opt("value") as? String).orEmpty()
    }
}

private fun formItemChoices(field: JSONObject, itemType: String): List<FormChoice> = descendants(field).filter { it.optString("type") == itemType }.mapNotNull {
    val p = formProps(it)
    val value = p.opt("value") as? String ?: return@mapNotNull null
    val id = it.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
    FormChoice(id, value, (p.opt("title") as? String).orEmpty().ifBlank { "Untitled option" })
}.toList()

internal fun formChoices(field: JSONObject): List<FormChoice> = formItemChoices(field, "Form.Dropdown.Item")
internal fun formTagChoices(field: JSONObject): List<FormChoice> = formItemChoices(field, "Form.TagPicker.Item")

internal fun formChoiceTitle(value: String, choices: List<FormChoice>, placeholder: String = "Choose…"): String =
    choices.firstOrNull { it.value == value }?.title ?: if (value.isBlank()) placeholder else "Selection unavailable"

internal fun liveFormChoice(owner: FormFieldOwner, expected: FormChoice, root: JSONObject?): Pair<JSONObject, FormChoice>? {
    val field = liveFormField(owner, root) ?: return null
    val choices = when (owner.type) {
        "Form.Dropdown" -> formChoices(field)
        "Form.TagPicker" -> formTagChoices(field)
        else -> return null
    }
    val choice = currentFormChoice(expected, choices) ?: return null
    return field to choice
}

/** Native edits precede asynchronous React publication. Preserve their event
 * value through recomposition of the same tree; a fresh publication becomes
 * the authoritative controlled/uncontrolled value again. */
internal class FormEditorValues {
    private data class Entry(val render: Any, var value: Any)
    private val entries = mutableMapOf<FormFieldOwner, Entry>()
    fun observe(owner: FormFieldOwner, render: Any, value: Any) {
        if (entries[owner]?.render !== render) entries[owner] = Entry(render, value)
    }
    fun edit(owner: FormFieldOwner, value: Any) { entries[owner]?.value = value }
    fun value(owner: FormFieldOwner): Any? = entries[owner]?.value
    fun remove(owner: FormFieldOwner) { entries.remove(owner) }
    fun clear() { entries.clear() }
}

/** One logical field focus across native text input and pointer-only controls. */
internal class FormFocusDelivery(private val current: (FormFieldOwner) -> Boolean,
                                 private val deliver: (FormFieldOwner, Boolean) -> Unit) {
    private var active: FormFieldOwner? = null
    private var generation = 0L
    fun focus(owner: FormFieldOwner) {
        if (!current(owner) || active == owner) return
        val turn = ++generation
        val previous = active
        active = null
        if (previous != null && current(previous)) deliver(previous, false)
        // Validation/imperative focus from blur can replace the next field or
        // start a newer transition; neither may inherit this outgoing request.
        if (generation != turn || !current(owner)) return
        active = owner
        deliver(owner, true)
    }
    fun blur(owner: FormFieldOwner) {
        if (active != owner) return
        generation++
        active = null
        if (current(owner)) deliver(owner, false)
    }
    fun blurCurrent() { active?.let(::blur) }
    fun clear() { generation++; active = null }
}
