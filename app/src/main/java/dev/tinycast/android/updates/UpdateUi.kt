package dev.tinycast.android.updates

import android.content.Context
import android.view.KeyEvent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import dev.tinycast.android.LocalPaletteWide
import dev.tinycast.android.PaletteActionCapsule
import dev.tinycast.android.Shell
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tinycast.android.BuildConfig
import dev.tinycast.android.PaletteTheme
import java.util.Locale

@Composable
internal fun ColumnScope.UpdateContent(updates: AppUpdates, inlineActions: Boolean = true) {
    val state = updates.state
    val candidate = state.candidate
    LaunchedEffect(updates) { updates.refresh() }
    Surface(color = PaletteTheme.selection, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, PaletteTheme.border)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Droidcast ${updates.installed.versionName}", fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.testTag("updates-installed"))
            Text("Build ${updates.installed.versionCode} · ${if (BuildConfig.BUILD_COMMIT == "local") "Local build" else BuildConfig.BUILD_COMMIT.take(7)}",
                fontSize = 11.sp, color = PaletteTheme.secondary, modifier = Modifier.padding(top = 5.dp))
            Text("Nightly channel", fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
            Text("Includes preview releases from moikdev/droidcast. Checking and downloading happen only when you choose.",
                fontSize = 12.sp, lineHeight = 18.sp, color = PaletteTheme.secondary, modifier = Modifier.padding(top = 5.dp))
        }
    }
    Spacer(Modifier.height(18.dp))
    Text(when (state.phase) {
        UpdatePhase.CHECKING -> "Checking for updates…"
        UpdatePhase.DOWNLOADING -> "Downloading update…"
        UpdatePhase.VERIFYING -> "Verifying download…"
        UpdatePhase.CURRENT -> "You're up to date"
        UpdatePhase.READY -> "Ready to install"
        UpdatePhase.APPROVAL -> "Android approval needed"
        UpdatePhase.INSTALLING -> "Installing update…"
        UpdatePhase.CONFIRM -> "Confirm with Android"
        UpdatePhase.CANCELLING -> "Cancelling…"
        UpdatePhase.COMPLETE -> "Update complete"
        UpdatePhase.ERROR -> "Couldn't finish the update"
        UpdatePhase.AVAILABLE -> "A new nightly is available"
        else -> "Check for Updates"
    }, fontWeight = FontWeight.Medium, fontSize = 17.sp, modifier = Modifier.testTag("updates-status"))
    Text(state.message, color = PaletteTheme.secondary, fontSize = 12.sp, lineHeight = 18.sp,
        modifier = Modifier.padding(top = 8.dp).testTag("updates-message"))
    if (candidate != null) {
        Text("${candidate.manifest.versionName} · build ${candidate.manifest.versionCode}", fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp).testTag("updates-latest"))
        Text(candidate.release.title, color = PaletteTheme.secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
    if (state.phase == UpdatePhase.DOWNLOADING && candidate != null) {
        LinearProgressIndicator(progress = { (state.bytes.toFloat() / candidate.manifest.bytes).coerceIn(0f, 1f) },
            color = PaletteTheme.accent, trackColor = PaletteTheme.separator,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag("updates-progress"))
        Text(String.format(Locale.ROOT, "%.1f / %.1f MB", state.bytes / 1_000_000.0, candidate.manifest.bytes / 1_000_000.0),
            color = PaletteTheme.secondary, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    } else if (state.phase in setOf(UpdatePhase.CHECKING, UpdatePhase.VERIFYING, UpdatePhase.INSTALLING, UpdatePhase.CANCELLING)) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
    }
    if (state.verified) Text("Checksum, package, version and signing certificate verified.", fontSize = 11.sp,
        color = PaletteTheme.secondary, modifier = Modifier.padding(top = 12.dp).testTag("updates-verified"))
    Spacer(Modifier.height(18.dp))
    if (inlineActions) UpdateInlineActions(updates)
    if (state.verified) Text("Android may ask you to confirm installation. Updates do not need the keyboard service.",
        color = PaletteTheme.secondary, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Confirm each update", fontSize = 12.sp)
            Text("Turn off to let Android skip a second confirmation when supported.", color = PaletteTheme.secondary, fontSize = 11.sp, lineHeight = 16.sp)
        }
        Switch(checked = updates.confirmEveryUpdate, onCheckedChange = updates::chooseConfirmation, enabled = !updates.busy,
            modifier = Modifier.testTag("updates-confirm-every-time"))
    }
    if (candidate != null) {
        HorizontalDivider(color = PaletteTheme.separator, modifier = Modifier.padding(vertical = 16.dp))
        Text("Release notes", fontWeight = FontWeight.Medium, fontSize = 13.sp)
        Text(candidate.release.notes.ifBlank { "No release notes were supplied." }, color = PaletteTheme.secondary, fontSize = 12.sp, lineHeight = 19.sp,
            modifier = Modifier.padding(top = 8.dp).testTag("updates-notes"))
    }
}


private data class UpdateAction(val title: String, val tag: String, val enabled: Boolean = true, val invoke: () -> Unit)
private fun primaryUpdateAction(updates: AppUpdates, context: Context): UpdateAction {
    val state = updates.state
    return when {
        state.phase == UpdatePhase.CONFIRM -> UpdateAction("Continue with Android", "updates-confirm") { updates.confirm(context) }
        updates.busy -> UpdateAction(if (state.phase == UpdatePhase.CANCELLING) "Cancelling…" else "Cancel", "updates-cancel", state.phase != UpdatePhase.CANCELLING, updates::cancel)
        state.verified && !updates.approvalGranted -> UpdateAction("Allow app updates", "updates-permission") {
            try { updates.approvalIntent()?.let(context::startActivity) }
            catch (_: Exception) { updates.report("Android's install approval page is unavailable on this device.") }
        }
        state.verified -> UpdateAction("Install update", "updates-install", invoke = updates::install)
        state.candidate != null -> UpdateAction("Download and verify", "updates-download", invoke = updates::download)
        else -> UpdateAction(if (state.phase == UpdatePhase.IDLE) "Check now" else "Check again", "updates-check", invoke = updates::check)
    }
}
@Composable
private fun UpdateInlineActions(updates: AppUpdates) {
    val action = primaryUpdateAction(updates, LocalContext.current)
    Button(onClick = action.invoke, enabled = action.enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(action.tag)) { Text(action.title) }
    if (updates.state.phase == UpdatePhase.CONFIRM) TextButton(onClick = updates::cancel, modifier = Modifier.heightIn(min = 48.dp).testTag("updates-cancel")) { Text("Cancel installation") }
    else if (!updates.busy && action.tag != "updates-check") TextButton(onClick = updates::check, modifier = Modifier.heightIn(min = 48.dp).testTag("updates-check")) { Text("Check again") }
}
@Composable
internal fun UpdatesPalette(updates: AppUpdates, close: () -> Unit) {
    val context = LocalContext.current
    var rootFocused by remember { mutableStateOf(false) }
    val claims = remember { mutableMapOf<Pair<Int, Int>, Long>() }
    Shell("Updates", "Droidcast nightlies", close, modifier = Modifier.onFocusChanged { rootFocused = it.isFocused }
        .onPreviewKeyEvent { event ->
            val key = event.nativeKeyEvent
            val id = key.deviceId to key.keyCode
            if (claims[id] == key.downTime) {
                if (key.action == KeyEvent.ACTION_UP) claims.remove(id)
                true
            } else if (rootFocused && key.action == KeyEvent.ACTION_DOWN &&
                !key.isCtrlPressed && !key.isAltPressed && !key.isMetaPressed && !key.isShiftPressed &&
                key.keyCode in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER)) {
                val action = primaryUpdateAction(updates, context)
                if (key.repeatCount == 0 && action.enabled) action.invoke()
                claims[id] = key.downTime
                true
            } else false
        }, footer = {
            val action = primaryUpdateAction(updates, context)
            if (updates.state.phase == UpdatePhase.CONFIRM) TextButton(onClick = updates::cancel, modifier = Modifier.testTag("updates-cancel")) { Text("Cancel") }
            else if (!updates.busy && action.tag != "updates-check") TextButton(onClick = updates::check, modifier = Modifier.testTag("updates-check")) { Text("Check again") }
            Spacer(Modifier.weight(1f))
            PaletteActionCapsule(action.title, action.invoke, {}, action.tag, "updates-no-actions", compact = !LocalPaletteWide.current,
                enabled = action.enabled, actionsEnabled = false, showActions = false)
        }) { UpdateContent(updates, inlineActions = false) }
}
