package dev.tinycast.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.tinycast.android.extensions.ExtensionCommand
import dev.tinycast.android.extensions.InstalledExtension
import dev.tinycast.android.extensions.objects

/** Metadata for one external handoff. Text belongs only to ReviewedTextInput. */
internal class TextReviewFlow(val origin: TextInputOrigin) {
    var readOnly by mutableStateOf(true)
    var problem by mutableStateOf<String?>(null)
    var choice by mutableStateOf<TextReviewChoice?>(null)
}

internal class TextReviewChoice(val extension: InstalledExtension, val command: ExtensionCommand) {
    val target get() = TextInputTarget(extension.listing.id, command.name)
    val label get() = "${extension.listing.title} · ${command.title}"
}

internal class TextIntentPayload(val value: Any?, val readOnly: Boolean) {
    override fun toString() = "TextIntentPayload(readOnly=$readOnly)"
}

internal fun PaletteStore.receiveTextReview(origin: TextInputOrigin, decode: () -> TextIntentPayload) {
    // Replacement happens before decoding, including malformed replacement intents.
    extensions.close()
    extensionReturnTo = null; extensionSetupFromRun = false
    discardEditorDraft(); editing = null; actionCommand = null; message = null
    val flow = TextReviewFlow(origin)
    textReview = flow
    try {
        val payload = decode()
        val preview = extensions.reviewedText.receive(payload.value, origin, payload.readOnly)
        flow.readOnly = preview.readOnly
    } catch (failure: TextInputRejected) {
        flow.problem = failure.problem.message
    } catch (_: Exception) {
        // Do not echo malformed parcel contents or exception messages.
        flow.problem = TextInputProblem.INVALID_TYPE.message
    }
    dialog = "text-input"; focusEpoch++
}

internal fun PaletteStore.clearTextReview() {
    if (textReview != null) {
        extensions.close()
        textReview = null
        if (dialog in setOf("text-input", "extension-run", "extension-setup")) dialog = null
        extensionReturnTo = null; extensionSetupFromRun = false
    } else extensions.reviewedText.invalidate()
}

internal fun PaletteStore.expireTextReview(origin: TextInputOrigin = textReview?.origin ?: TextInputOrigin.SELECTION) {
    clearTextReview()
    textReview = TextReviewFlow(origin).apply { problem = TextInputProblem.EXPIRED.message }
    dialog = "text-input"; focusEpoch++
}

internal fun PaletteStore.returnTextReview(expected: TextReviewFlow): Boolean {
    if (textReview !== expected) return false
    clearTextReview()
    dialog = null; actionCommand = null; focusEpoch++
    return true
}

private fun PaletteStore.currentChoice(choice: TextReviewChoice): Boolean =
    extensions.installed.any { it === choice.extension } && choice.extension.commands.any {
        it.name == choice.command.name && it.definition === choice.command.definition && it.mode in listOf("view", "no-view")
    }

internal fun PaletteStore.chooseTextCommand(expected: TextReviewFlow, preview: TextInputPreview, choice: TextReviewChoice) {
    if (textReview !== expected || extensions.reviewedText.preview !== preview) return
    if (extensions.busy != null) { expected.problem = "Wait for the extension installation to finish."; return }
    if (!currentChoice(choice)) { expected.problem = TextInputProblem.CHANGED_COMMAND.message; return }
    extensions.reviewedText.choose(preview, choice.target)
    expected.choice = choice; expected.problem = null
}

internal fun PaletteStore.runTextCommand(expected: TextReviewFlow, preview: TextInputPreview) {
    if (textReview !== expected || extensions.reviewedText.preview !== preview) return
    val choice = expected.choice
    if (choice == null) { expected.problem = TextInputProblem.NO_COMMAND.message; return }
    if (backupBusy || extensions.busy != null) { expected.problem = "Finish the current operation before running this text."; return }
    if (!currentChoice(choice)) { expected.problem = TextInputProblem.CHANGED_COMMAND.message; return }
    val extension = choice.extension; val command = choice.command
    try {
        val prefs = extensions.preferences(extension, command)
        val needsSetup = command.definition.optJSONArray("arguments").objects().isNotEmpty() || extension.fields(command).any {
            it.optBoolean("required") && (!prefs.has(it.getString("name")) || prefs.optString(it.getString("name")).isBlank())
        }
        extensions.launchReviewed(extension, command, preview, needsSetup)
        extensionReturnTo = null; extensionSetupFromRun = false
        expected.problem = null
        dialog = if (needsSetup) "extension-setup" else "extension-run"
    } catch (_: TextInputRejected) {
        expected.problem = TextInputProblem.EXPIRED.message
    } catch (_: Exception) {
        extensions.close()
        expected.problem = "This command could not start. Select or share text again."
    }
}

internal fun PaletteStore.saveReviewedClip(expected: TextReviewFlow, preview: TextInputPreview) {
    if (textReview !== expected || extensions.reviewedText.preview !== preview) return
    val text = preview.text ?: return
    clearTextReview()
    discardEditorDraft()
    editing = SavedItem("", if (expected.origin == TextInputOrigin.SHARE) "Shared text" else "Selected text", text)
    dialog = "clip"; focusEpoch++
}
