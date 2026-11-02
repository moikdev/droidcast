package dev.tinycast.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal enum class TextInputOrigin { SELECTION, SHARE }
internal data class TextInputTarget(val extensionId: String, val commandName: String)

internal enum class TextInputProblem(val message: String) {
    MISSING("No text was provided. Select or share text and try again."),
    INVALID_TYPE("Droidcast needs plain text. Select or share text and try again."),
    TOO_LONG("This text is too long. Select a shorter passage and try again."),
    EXPIRED("This text review has ended. Select or share text again."),
    NO_COMMAND("Choose an extension command before running this text."),
    CHANGED_COMMAND("The chosen command changed. Review it and try again."),
    UNAVAILABLE("No reviewed text is available for this command. Select or share text, then choose Run.")
}

internal class TextInputRejected(val problem: TextInputProblem) : IllegalArgumentException(problem.message)

/** Pending text is separate from the capability handed to an approved invocation. */
internal class TextInputPreview internal constructor(text: String, val origin: TextInputOrigin, val readOnly: Boolean) {
    private var content: String? by mutableStateOf<String?>(text)
    var target: TextInputTarget? by mutableStateOf<TextInputTarget?>(null); private set
    val text: String? get() = content
    internal fun choose(value: TextInputTarget?) { target = value }
    internal fun take(): String {
        val result = content ?: throw TextInputRejected(TextInputProblem.EXPIRED)
        revoke()
        return result
    }
    internal fun revoke() { content = null; target = null }
    override fun toString() = "TextInputPreview(origin=$origin, readOnly=$readOnly)"
}

/** The caller must hold the original invocation owner as well as the selected target. */
internal class CommandTextInput internal constructor(
    private val owner: Any, val target: TextInputTarget, text: String, val readOnly: Boolean,
) {
    @Volatile private var content: String? = text
    internal fun read(requester: Any, requestedTarget: TextInputTarget): String {
        if (requester !== owner || requestedTarget != target) throw TextInputRejected(TextInputProblem.UNAVAILABLE)
        return content ?: throw TextInputRejected(TextInputProblem.UNAVAILABLE)
    }
    internal fun revoke() { content = null }
    override fun toString() = "CommandTextInput(readOnly=$readOnly)"
}

/** UI-thread lifecycle; no saved state, preferences, clipboard reads or external execution. */
internal class ReviewedTextInput {
    companion object { const val MAX_CODE_UNITS = 65_536 }
    var preview: TextInputPreview? by mutableStateOf<TextInputPreview?>(null); private set
    private var active: CommandTextInput? = null

    fun receive(value: Any?, origin: TextInputOrigin, readOnly: Boolean = true): TextInputPreview {
        // Even an invalid replacement ends the previous input's authority.
        invalidate()
        val source = value as? CharSequence ?: throw TextInputRejected(
            if (value == null) TextInputProblem.MISSING else TextInputProblem.INVALID_TYPE)
        if (source.length > MAX_CODE_UNITS) throw TextInputRejected(TextInputProblem.TOO_LONG)
        if (source.isEmpty()) throw TextInputRejected(TextInputProblem.MISSING)
        val text = source.toString()
        // Copy mutable CharSequences and validate the resulting snapshot too.
        if (text.length > MAX_CODE_UNITS) throw TextInputRejected(TextInputProblem.TOO_LONG)
        if (text.isEmpty()) throw TextInputRejected(TextInputProblem.MISSING)
        return TextInputPreview(text, origin, origin == TextInputOrigin.SHARE || readOnly).also { preview = it }
    }

    fun choose(expected: TextInputPreview, target: TextInputTarget?) {
        requireCurrent(expected)
        expected.choose(target)
    }

    internal fun checkApproval(expected: TextInputPreview, expectedTarget: TextInputTarget): TextInputTarget {
        requireCurrent(expected)
        val target = expected.target ?: throw TextInputRejected(TextInputProblem.NO_COMMAND)
        if (target != expectedTarget) throw TextInputRejected(TextInputProblem.CHANGED_COMMAND)
        return target
    }

    fun approve(expected: TextInputPreview, expectedTarget: TextInputTarget, owner: Any): CommandTextInput {
        val target = checkApproval(expected, expectedTarget)
        val text = expected.take()
        preview = null
        active?.revoke()
        return CommandTextInput(owner, target, text, expected.readOnly).also { active = it }
    }

    fun cancel(expected: TextInputPreview): Boolean {
        if (preview !== expected) return false
        expected.revoke(); preview = null
        return true
    }

    fun release(input: CommandTextInput) {
        input.revoke()
        if (active === input) active = null
    }

    fun invalidate() {
        preview?.revoke(); preview = null
        active?.revoke(); active = null
    }

    private fun requireCurrent(expected: TextInputPreview) {
        if (preview !== expected || expected.text == null) throw TextInputRejected(TextInputProblem.EXPIRED)
    }
}
