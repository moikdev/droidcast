package dev.tinycast.android.extensions

import org.json.JSONObject

private const val SUBMIT_KIND = "__tinycastFormSubmit"
private const val SUBMIT_OWNER = "__tinycastSubmitOwner"
private const val SUBMIT_SIGNATURE = "__tinycastSubmitSignature"

internal fun isFormSubmit(action: JSONObject?): Boolean = action?.props()?.opt(SUBMIT_KIND) == true

internal fun formSubmitBlocked(root: JSONObject?, action: JSONObject?): Boolean =
    isFormSubmit(action) && formHasErrors(root)

/** A stable SubmitForm instance can publish a fresh closure after an edit.
 * Its native node, private owner and visible descriptor must still match the
 * action the caller saw; a replacement action cannot inherit that request. */
internal fun liveFormSubmitAction(rootId: String, requested: JSONObject?, root: JSONObject?): JSONObject? {
    if (root?.optString("type") != "Form" || root.optString("id") != rootId || requested == null || !isFormSubmit(requested)) return null
    val owner = (requested.props().opt(SUBMIT_OWNER) as? String)?.takeIf(String::isNotBlank) ?: return null
    val signature = (requested.props().opt(SUBMIT_SIGNATURE) as? String)?.takeIf(String::isNotBlank) ?: return null
    return root.actions().firstOrNull {
        it.optString("id") == requested.optString("id") && it.handle("onAction") == requested.handle("onAction") &&
            isFormSubmit(it) && it.props().opt(SUBMIT_OWNER) == owner && it.props().opt(SUBMIT_SIGNATURE) == signature
    }
}

/** Bound by the current native Form only. Disposal of an old Form must not
 * remove its successor's preparation callback. Reentrant replacement during
 * blur also invalidates the outgoing submit request. */
internal class FormSubmitPreparation {
    private var owner: Any? = null
    private var callback: (() -> Unit)? = null
    fun attach(token: Any, prepare: () -> Unit) { owner = token; callback = prepare }
    fun detach(token: Any) { if (owner === token) { owner = null; callback = null } }
    fun prepare(): Boolean {
        val token = owner ?: return false
        val current = callback ?: return false
        current()
        return owner === token && callback === current
    }
}
