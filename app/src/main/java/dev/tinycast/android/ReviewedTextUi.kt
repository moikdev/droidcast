package dev.tinycast.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ReviewedTextScreen(store: PaletteStore, close: () -> Unit) {
    val flow = store.textReview ?: return
    val preview = store.extensions.reviewedText.preview
    var choosing by remember(flow) { mutableStateOf(false) }
    val commands = store.extensions.installed.flatMap { extension ->
        extension.commands.filter { it.mode in listOf("view", "no-view") }.map { TextReviewChoice(extension, it) }
    }
    Shell("Review text", if (flow.origin == TextInputOrigin.SELECTION) "Selected text · choose where it goes." else "Shared text · choose where it goes.", close) {
        if (preview != null) {
            Text("Nothing runs until you press Run. The chosen extension can use, save, or send this text.",
                color = PaletteTheme.secondary, fontSize = 13.sp, lineHeight = 20.sp)
            Text("Preview", fontSize = 12.sp,
                color = PaletteTheme.secondary, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            Box(Modifier.fillMaxWidth().heightIn(min = 64.dp, max = 180.dp)
                .background(PaletteTheme.control, RoundedCornerShape(8.dp))
                .verticalScroll(rememberScrollState()).padding(12.dp).testTag("text-input-preview")) {
                Text(preview.text.orEmpty(), fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 22.sp,
                    modifier = Modifier.testTag("text-input-content"))
            }
            Text(if (flow.readOnly && flow.origin == TextInputOrigin.SELECTION)
                "Your source is read only. Use the command’s Copy action, then Back to return to your app."
                else "Use the command’s Copy action, then Back to return to your app. Replacing the source text is not supported.",
                color = PaletteTheme.secondary, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 12.dp))
            OutlinedButton(onClick = { choosing = true }, enabled = commands.isNotEmpty() && store.extensions.busy == null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 16.dp).testTag("text-input-choose")) {
                Text(flow.choice?.label ?: "Choose extension command", fontSize = 14.sp, lineHeight = 21.sp)
            }
            if (commands.isEmpty()) Text("No supported commands are installed. Install an extension from Raycast Store, then select or share text again.",
                color = PaletteTheme.secondary, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 10.dp))
            flow.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, lineHeight = 20.sp,
                modifier = Modifier.padding(top = 12.dp).testTag("text-input-error")) }
            Button(onClick = { store.runTextCommand(flow, preview) }, enabled = flow.choice != null && store.extensions.busy == null && !store.backupBusy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 12.dp).testTag("text-input-run")) {
                Text("Run with this text", fontSize = 14.sp, lineHeight = 21.sp)
            }
            TextButton(onClick = { store.saveReviewedClip(flow, preview) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("text-input-save-clip")) {
                Text("Save as a clip…", fontSize = 14.sp, lineHeight = 21.sp)
            }
        } else {
            Text(flow.problem ?: TextInputProblem.EXPIRED.message, color = MaterialTheme.colorScheme.error,
                fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.testTag("text-input-error"))
        }
        TextButton(onClick = close, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("text-input-cancel")) {
            Text("Return to source", fontSize = 14.sp, lineHeight = 21.sp)
        }
        PaletteChoiceMenu(choosing, { choosing = false }, commands.map { choice ->
            PaletteChoice("text-command-${choice.extension.listing.id}/${choice.command.name}", choice.label,
                checked = flow.choice?.target == choice.target, enabled = store.extensions.busy == null, icon = "extension") {
                if (preview != null) store.chooseTextCommand(flow, preview, choice)
                choosing = false
            }
        }, title = "Choose extension command", searchable = true)
    }
}
