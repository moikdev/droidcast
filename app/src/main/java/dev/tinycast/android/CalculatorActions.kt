package dev.tinycast.android

import android.view.KeyEvent

internal enum class CalculatorAction { COPY_ANSWER, PUT_ANSWER, COPY_CALCULATION }

/** A result ID is shared by every calculation, so it cannot identify an action's owner. */
internal data class CalculatorSelection(
    val query: String, val category: String, val index: Int, val revision: Long,
    val focusEpoch: Int, val route: String?, val command: Command?
)

internal data class CalculatorActionSnapshot private constructor(val selection: CalculatorSelection) {
    val answer: String get() = selection.command!!.payload
    val calculation: String get() = selection.command!!.subtitle.trim().removePrefix("=").trimStart() + " = " + answer
    val chainQuery: String? = ("= " + answer).takeIf { Calculator.evaluateSearch(it)?.result == answer }
    fun isCurrent(current: CalculatorSelection): Boolean = selection == current

    companion object {
        fun capture(selection: CalculatorSelection): CalculatorActionSnapshot? =
            selection.takeIf { it.command?.let { c -> c.kind == Kind.CALC && c.id == "result" && c.payload.isNotEmpty() } == true }
                ?.let(::CalculatorActionSnapshot)
    }
}

internal object CalculatorAccelerators {
    // Existing configured shortcuts keep precedence. In particular, do not
    // invalidate a saved Ctrl+Shift+Enter binding to introduce this default.
    val bindings = listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER).flatMap { key ->
        listOf(KeyChord(key, ShortcutRules.CTRL) to CalculatorAction.PUT_ANSWER.name,
            KeyChord(key, ShortcutRules.CTRL or ShortcutRules.SHIFT) to CalculatorAction.COPY_CALCULATION.name)
    }.toMap()
}
