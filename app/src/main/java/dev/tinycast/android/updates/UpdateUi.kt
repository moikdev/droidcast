package dev.tinycast.android.updates

import android.content.Intent
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
internal fun ColumnScope.UpdateContent(updates: AppUpdates) {
    val context = LocalContext.current
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
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag("updates-progress"))
        Text(String.format(Locale.ROOT, "%.1f / %.1f MB", state.bytes / 1_000_000.0, candidate.manifest.bytes / 1_000_000.0),
            color = PaletteTheme.secondary, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    } else if (state.phase in setOf(UpdatePhase.CHECKING, UpdatePhase.VERIFYING, UpdatePhase.INSTALLING, UpdatePhase.CANCELLING)) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
    }
    if (state.verified) Text("Checksum, package, version and signing certificate verified.", fontSize = 11.sp,
        color = PaletteTheme.secondary, modifier = Modifier.padding(top = 12.dp).testTag("updates-verified"))
    Spacer(Modifier.height(18.dp))
    when {
        state.phase == UpdatePhase.CONFIRM -> Button(onClick = { updates.confirm(context) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("updates-confirm")) { Text("Continue with Android") }
        !state.busy && state.verified -> {
            if (!updates.approvalGranted) Button(onClick = {
                try { context.startActivity(updates.approvalIntent()) }
                catch (_: Exception) { updates.report("Android's install approval page is unavailable on this device.") }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("updates-permission")) { Text("Allow app updates") }
            else Button(onClick = updates::install, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("updates-install")) { Text("Install update") }
            Text("Android may ask you to confirm installation. Updates do not need the keyboard service.", color = PaletteTheme.secondary, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        }
        !state.busy && candidate != null -> Button(onClick = updates::download, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("updates-download")) { Text("Download and verify") }
    }
    if (state.busy) {
        TextButton(onClick = updates::cancel, enabled = state.phase != UpdatePhase.CANCELLING,
            modifier = Modifier.heightIn(min = 48.dp).testTag("updates-cancel")) { Text(if (state.phase == UpdatePhase.CONFIRM) "Cancel installation" else "Cancel") }
    } else TextButton(onClick = updates::check, modifier = Modifier.heightIn(min = 48.dp).testTag("updates-check")) { Text(if (state.phase == UpdatePhase.IDLE) "Check now" else "Check again") }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Confirm each update", fontSize = 12.sp)
            Text("Turn off to let Android skip a second confirmation when supported.", color = PaletteTheme.secondary, fontSize = 11.sp, lineHeight = 16.sp)
        }
        Switch(checked = updates.confirmEveryUpdate, onCheckedChange = updates::chooseConfirmation, enabled = !state.busy,
            modifier = Modifier.testTag("updates-confirm-every-time"))
    }
    if (candidate != null) {
        HorizontalDivider(color = PaletteTheme.separator, modifier = Modifier.padding(vertical = 16.dp))
        Text("Release notes", fontWeight = FontWeight.Medium, fontSize = 13.sp)
        Text(candidate.release.notes.ifBlank { "No release notes were supplied." }, color = PaletteTheme.secondary, fontSize = 12.sp, lineHeight = 19.sp,
            modifier = Modifier.padding(top = 8.dp).testTag("updates-notes"))
    }
}
