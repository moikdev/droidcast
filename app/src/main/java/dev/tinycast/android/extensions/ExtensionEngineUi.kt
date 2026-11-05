package dev.tinycast.android.extensions

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tinycast.android.LocalPaletteWide
import dev.tinycast.android.PaletteTheme
import dev.tinycast.android.paletteFooterSurface

private class EngineRecoveryLifetime { var active = true; var returned = false }

/** The OS provider identity is the only input to this host-constructed intent. */
internal fun engineSettingsIntent(engine: ExtensionEngineIdentity): Intent = engine.settingsPackage?.let {
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", it, null))
} ?: Intent(Settings.ACTION_APPLICATION_SETTINGS)

@Composable
internal fun ColumnScope.EngineRecovery(catalog: ExtensionCatalog, problem: ExtensionEngineProblem,
                                         isCurrent: () -> Boolean, close: () -> Unit) {
    val lifetime = remember(problem) { EngineRecoveryLifetime() }
    var settingsOpened by remember(problem) { mutableStateOf(false) }
    var settingsError by remember(problem) { mutableStateOf(false) }
    val context = LocalContext.current
    val window = LocalWindowInfo.current
    val view = LocalView.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(lifetime) { onDispose { lifetime.active = false } }
    LaunchedEffect(problem) {
        if (window.isWindowFocused && isCurrent()) { focus.clearFocus(force = true); keyboard?.hide() }
    }
    fun current() = lifetime.active && !lifetime.returned && isCurrent() &&
        catalog.runtimeError != null && catalog.runtimeEngineProblem === problem &&
        window.isWindowFocused && view.isAttachedToWindow && view.hasWindowFocus()
    val unsupported = problem.failure in setOf(ExtensionEngineFailure.SYNTAX, ExtensionEngineFailure.MISSING_APIS)
    Column(Modifier.weight(1f).fillMaxWidth().testTag("extension-engine-problem")) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (unsupported) "WebView update needed" else "Couldn’t check WebView",
                fontSize = 18.sp, lineHeight = 26.sp, color = PaletteTheme.ink,
                modifier = Modifier.testTag("extension-engine-title"))
            Text(problem.engine.display, fontSize = 13.sp, lineHeight = 20.sp, color = PaletteTheme.secondary,
                modifier = Modifier.testTag("extension-engine-provider"))
            Text(when (problem.failure) {
                ExtensionEngineFailure.SYNTAX -> "This WebView cannot parse the bundled JavaScript runtime. The command was not started."
                ExtensionEngineFailure.MISSING_APIS -> "This WebView is missing JavaScript features required by the bundled runtime. The command was not started."
                ExtensionEngineFailure.CHECK_UNAVAILABLE -> "The startup check did not return a valid result. The command was not started."
                ExtensionEngineFailure.CHECK_TIMED_OUT -> "The startup check did not finish in time. The command was not started."
            }, fontSize = 14.sp, lineHeight = 22.sp, color = PaletteTheme.secondary,
                modifier = Modifier.testTag("extension-engine-explanation"))
            if (problem.missingApis.isNotEmpty()) Text("Missing: ${problem.missingApis.joinToString(", ")}",
                fontSize = 12.sp, lineHeight = 19.sp, color = PaletteTheme.secondary)
            Text("Update Android System WebView or Chrome through your device’s app store. Engine settings shows the provider used here. On older Android versions, an adequate update may be unavailable.",
                fontSize = 14.sp, lineHeight = 22.sp, color = PaletteTheme.secondary,
                modifier = Modifier.testTag("extension-engine-guidance"))
            Text("Return, then run the command again after updating. Any selected text needs a new review.",
                fontSize = 14.sp, lineHeight = 22.sp, color = PaletteTheme.secondary)
            if (settingsError) Text("Couldn’t open engine settings. Open your device’s Settings app to manage WebView or Chrome.",
                color = MaterialTheme.colorScheme.error, fontSize = 14.sp, lineHeight = 22.sp,
                modifier = Modifier.testTag("extension-engine-settings-error"))
        }
        val settings: @Composable (Modifier) -> Unit = { modifier ->
            OutlinedButton(onClick = {
                if (current() && !settingsOpened) {
                    settingsOpened = true
                    try { context.startActivity(engineSettingsIntent(problem.engine)) }
                    catch (_: ActivityNotFoundException) { settingsOpened = false; settingsError = true }
                    catch (_: SecurityException) { settingsOpened = false; settingsError = true }
                }
            }, enabled = current() && !settingsOpened, shape = RoundedCornerShape(10.dp),
                modifier = modifier.heightIn(min = 48.dp).testTag("extension-engine-settings")) {
                Text("Engine settings", fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
        val back: @Composable (Modifier) -> Unit = { modifier ->
            Button(onClick = { if (current()) { lifetime.returned = true; close() } }, enabled = current(),
                shape = RoundedCornerShape(10.dp), modifier = modifier.heightIn(min = 48.dp).testTag("extension-engine-return")) {
                Text("Return", fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
        if (LocalPaletteWide.current) {
            Row(Modifier.fillMaxWidth().paletteFooterSurface().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                settings(Modifier.weight(1f)); back(Modifier.weight(1f))
            }
        } else {
            Column(Modifier.fillMaxWidth().paletteFooterSurface().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                settings(Modifier.fillMaxWidth()); back(Modifier.fillMaxWidth())
            }
        }
    }
}
