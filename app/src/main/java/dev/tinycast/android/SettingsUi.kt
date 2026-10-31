package dev.tinycast.android

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LocalSettingsCompact = compositionLocalOf { false }
private val settingsBackground = Color(0xFF18181B)
private val settingsSidebar = Color(0xFF222225)
private data class SettingsPage(val id: String, val title: String, val icon: String, val keywords: String)
private val settingsPages = listOf(
    SettingsPage("general", "General", "settings", "global hotkey keyboard service accessibility privacy activation"),
    SettingsPage("shortcuts", "Shortcuts", "keyboard", "commands apps hotkeys aliases bindings reset"),
    SettingsPage("folders", "Folders", "folder", "search files directory index scope permission refresh"),
    SettingsPage("backup", "Backup", "clipboard", "import export restore notes links clips preferences"),
    SettingsPage("about", "About", "command", "version source license tinycast raycast")
)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun AdvancedSettings(activity: SettingsActivity) {
    val store = activity.store
    var query by rememberSaveable { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(activity.searchFocusEpoch) { if (activity.searchFocusEpoch > 0) searchFocus.requestFocus() }
    val snackbar = remember { SnackbarHostState() }
    val initialFocus = remember { FocusRequester() }
    var placed by remember { mutableStateOf(false) }
    var hasFocus by remember { mutableStateOf(false) }
    val focusBackground = remember { activity.requestedEditor !in setOf("alias", "hotkey") }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    fun changeSearch(value: String) { activity.cancelShortcutProbe(); store.cancelShortcutRecording(false); query = value }
    fun leaveSearch() { query = ""; focusManager.clearFocus(); keyboard?.hide() }
    LaunchedEffect(placed) { if (placed && focusBackground && !hasFocus) { initialFocus.requestFocus(); keyboard?.hide() } }
    BackHandler { activity.back() }
    MaterialTheme(colorScheme = darkColorScheme(primary = PaletteTheme.ink, onPrimary = PaletteTheme.background,
        background = settingsBackground, surface = PaletteTheme.surface, onSurface = PaletteTheme.ink,
        secondary = PaletteTheme.secondary, outline = PaletteTheme.border, surfaceContainer = PaletteTheme.surface),
        shapes = Shapes(extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(10.dp), large = RoundedCornerShape(10.dp), extraLarge = RoundedCornerShape(10.dp)),
        typography = Typography(bodyLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp), bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
            bodySmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp), labelLarge = TextStyle(fontSize = 12.sp, lineHeight = 16.sp))) {
        LaunchedEffect(store.message) { store.message?.let { snackbar.showSnackbar(it); if (store.message == it) store.message = null } }
        CompositionLocalProvider(LocalContentColor provides PaletteTheme.ink) {
        PaletteMenuHost(store, openChanged = { if(it) activity.cancelShortcutProbe() }) {
        BoxWithConstraints(Modifier.fillMaxSize().focusRequester(initialFocus).onFocusChanged { hasFocus = it.hasFocus }.onGloballyPositioned { placed = true }.background(settingsBackground).safeDrawingPadding().semantics { testTagsAsResourceId = true }
            .testTag("settings-workspace").onPreviewKeyEvent { event ->
                val key = event.nativeKeyEvent
                val command = (key.isCtrlPressed xor key.isMetaPressed) && !key.isAltPressed
                if (key.action != KeyEvent.ACTION_DOWN || !command || store.recordingTarget != null) false
                else when {
                    key.keyCode == KeyEvent.KEYCODE_F && !key.isShiftPressed -> { searchFocus.requestFocus(); true }
                    key.keyCode == KeyEvent.KEYCODE_COMMA -> true
                    else -> false
                }
            }.onKeyEvent { if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ESCAPE && it.nativeKeyEvent.asChord().modifiers == 0) { activity.onBackPressedDispatcher.onBackPressed(); true } else false }.focusable()) {
            val wide = maxWidth >= 760.dp
            val compactHeight = maxHeight < 540.dp
            CompositionLocalProvider(LocalSettingsCompact provides compactHeight) {
            Box(Modifier.fillMaxSize().testTag("settings-viewport")) {
            Row(Modifier.fillMaxSize().background(settingsBackground).testTag("settings-frame").paletteMenuSurface()) {
                if (wide) Column(Modifier.width(204.dp).fillMaxHeight().background(settingsSidebar).padding(12.dp)) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Row(Modifier.padding(10.dp, 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(32.dp).background(Color(0xFFEF5B50), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) { Glyph("command", Color.White, 21) }
                        Spacer(Modifier.width(10.dp))
                        Column { Text("Droidcast", fontSize = 14.sp, fontWeight = FontWeight.SemiBold); Text("Settings", fontSize = 11.sp, color = PaletteTheme.secondary, modifier = Modifier.padding(top = 2.dp)) }
                    }
                    Spacer(Modifier.height(8.dp))
                    SettingsSearchField(query, ::changeSearch, searchFocus)
                    Spacer(Modifier.height(20.dp))
                    Text("PREFERENCES", fontSize = 10.sp, color = PaletteTheme.tertiary, modifier = Modifier.padding(10.dp, 8.dp))
                    settingsPages.forEach { page -> SettingsNav(page, activity.page == page.id && query.isEmpty()) { leaveSearch(); activity.selectPage(page.id) } }
                    }
                    TextButton(onClick = activity::returnToPalette, modifier = Modifier.testTag("settings-return-palette")) { Text("Open palette", fontSize = 12.sp) }
                    Text("Development build", color = PaletteTheme.tertiary, fontSize = 10.sp, modifier = Modifier.padding(10.dp, 6.dp))
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (!wide) {
                        Row(Modifier.fillMaxWidth().padding(12.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Droidcast Settings", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            TextButton(onClick = activity::returnToPalette, modifier = Modifier.testTag("settings-return-palette")) { Text("Palette", fontSize = 12.sp) }
                        }
                        Box(Modifier.padding(horizontal = 14.dp)) { SettingsSearchField(query, ::changeSearch, searchFocus) }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp, 6.dp)) {
                            settingsPages.forEach { page ->
                                TextButton(onClick = { leaveSearch(); activity.selectPage(page.id) }, modifier = Modifier.background(if (page.id == activity.page && query.isEmpty()) PaletteTheme.selection else Color.Transparent, RoundedCornerShape(8.dp)).testTag("settings-nav-${page.id}")) { Text(page.title, fontSize = 12.sp) }
                            }
                        }
                    }
                    if (query.isNotEmpty()) SettingsSearchResults(activity, query, ::leaveSearch)
                    else when (activity.page) {
                        "shortcuts" -> SettingsShortcuts(activity)
                        "folders" -> SettingsScrollPane("Folders", "Choose where file search can look.") { FolderSearchDialog(store, activity::command, activity::back, embedded = true) }
                        "backup" -> SettingsScrollPane("Backup", "Export your data or inspect an import before applying it.") {
                            BackupDialog(store, activity::command, activity::back, embedded = true)
                        }
                        "about" -> SettingsAbout(activity)
                        else -> SettingsGeneral(activity)
                    }
                }
            }
            }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(20.dp))
        }
        }
        }
    }
}

@Composable
private fun SettingsSearchField(value: String, change: (String) -> Unit, focus: FocusRequester) {
    SettingsInput(value, change, "Search settings", "settings-search", Modifier.focusRequester(focus), leading = true)
}
@Composable
private fun SettingsInput(value: String, change: (String) -> Unit, placeholder: String, tag: String,
                          modifier: Modifier = Modifier, leading: Boolean = false, enabled: Boolean = true, done: (() -> Unit)? = null) {
    BasicTextField(value, change, singleLine = true, enabled = enabled,
        textStyle = TextStyle(color = PaletteTheme.ink, fontSize = 13.sp, lineHeight = 18.sp),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = if (done != null) ImeAction.Done else ImeAction.Default),
        keyboardActions = KeyboardActions(onDone = { done?.invoke() }), cursorBrush = SolidColor(PaletteTheme.ink),
        modifier = modifier.fillMaxWidth().heightIn(min = 40.dp).testTag(tag), decorationBox = { inner ->
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = .035f)).border(1.dp, Color.White.copy(alpha = .12f), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                if (leading) { Glyph("search", PaletteTheme.tertiary, 15); Spacer(Modifier.width(9.dp)) }
                Box(Modifier.weight(1f)) { if (value.isEmpty()) Text(placeholder, fontSize = 12.sp, color = PaletteTheme.tertiary, maxLines = 1); inner() }
            }
        })
}
@Composable
private fun SettingsNav(page: SettingsPage, selected: Boolean, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp))
        .background(if (selected) Color.White.copy(alpha = .085f) else Color.Transparent)
        .clickable(onClick = click).heightIn(min = 44.dp).padding(horizontal = 10.dp, vertical = 7.dp).testTag("settings-nav-${page.id}"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).background(Color.White.copy(alpha = if (selected) .10f else .045f), RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
            Glyph(page.icon, if (selected) PaletteTheme.ink else PaletteTheme.secondary, 16)
        }
        Text(page.title, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal, modifier = Modifier.padding(start = 10.dp))
    }
}
@Composable
private fun SettingsHeading(title: String, subtitle: String) {
    val compact = LocalSettingsCompact.current
    Text(title, fontSize = if (compact) 18.sp else 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.3).sp)
    if (compact) Spacer(Modifier.height(12.dp))
    else Text(subtitle, fontSize = 12.sp, color = PaletteTheme.secondary, modifier = Modifier.padding(top = 5.dp, bottom = 20.dp))
}
@Composable
private fun SettingsScrollPane(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 800.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(26.dp)) { SettingsHeading(title, subtitle); content() }
    }
}
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Color.White.copy(alpha = .025f), shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Color.White.copy(alpha = .08f)), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(16.dp), content = content)
    }
}
@Composable
private fun SettingsSectionLabel(title: String) {
    Text(title.uppercase(), fontSize = 10.sp, letterSpacing = .9.sp, fontWeight = FontWeight.Medium,
        color = PaletteTheme.tertiary, modifier = Modifier.padding(start = 2.dp, top = 8.dp, bottom = 10.dp))
}
@Composable
private fun SettingsPreferenceRow(title: String, detail: String, controls: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(detail, fontSize = 11.sp, color = PaletteTheme.secondary, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
        }
        controls()
    }
}
@Composable
private fun SettingsGeneral(activity: SettingsActivity) {
    val store = activity.store
    val chord = store.shortcutConfiguration.bindings[ShortcutRules.PALETTE]
    val connection = ShortcutRuntime.keyboardConnection.connected
    val connectionEpoch = ShortcutRuntime.keyboardConnection.epoch
    val readiness = ShortcutReadiness(chord, activity.serviceConsent, connection)
    val probe = activity.shortcutProbe
    var showSetup by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(activity) {
        activity.shortcutProbeVisibilityChanged(true)
        onDispose { activity.shortcutProbeVisibilityChanged(false) }
    }
    LaunchedEffect(chord, activity.serviceConsent, connection, connectionEpoch) {
        if (probe.status == ShortcutProbeStatus.LISTENING && (!readiness.ready || probe.expected != chord || !probe.usesConnection(connectionEpoch))) activity.cancelShortcutProbe()
    }
    SettingsScrollPane("General", "Make Droidcast fit the way you work.") {
        SettingsSectionLabel("Activation")
        SettingsCard {
            SettingsPreferenceRow("Android assistant", if (activity.assistantSelected) "Droidcast is selected · use your system assistant gesture or key." else "Open with Android’s assistant gesture or key. No keyboard service needed.") {
                TextButton(onClick = activity::chooseAssistant, modifier = Modifier.testTag("settings-choose-assistant")) { Text(if (activity.assistantSelected) "Manage" else "Choose Droidcast", fontSize = 12.sp) }
            }
            HorizontalDivider(color = PaletteTheme.separator)
            Column(Modifier.fillMaxWidth().padding(16.dp).testTag("settings-assistant-touch")) {
                Text("Swipe from a bottom corner", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text("With gesture navigation, swipe diagonally inward from either bottom corner. Enable ‘Swipe to invoke assistant’ in Android navigation settings if offered.",
                    color = PaletteTheme.secondary, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp).testTag("settings-assistant-swipe"))
                Spacer(Modifier.height(12.dp))
                Text("Press and hold Home", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text("With button navigation, hold the on-screen Home button. Enable ‘Hold Home for assistant’ if offered.",
                    color = PaletteTheme.secondary, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp).testTag("settings-assistant-home"))
                Text("Choose Droidcast as your digital assistant first. Android controls these gestures; some devices assign Home to Circle to Search or another system action. Adjust that mapping in Android settings. Names and availability vary by device.",
                    color = PaletteTheme.tertiary, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 12.dp).testTag("settings-assistant-limitations"))
                TextButton(onClick = activity::openNavigationSettings, modifier = Modifier.testTag("settings-assistant-navigation")) { Text("Navigation settings", fontSize = 12.sp) }
                Text("Manual path: Android Settings → System → Gestures or Navigation mode.", color = PaletteTheme.tertiary, fontSize = 11.sp, lineHeight = 16.sp)
            }
            HorizontalDivider(color = PaletteTheme.separator)
            Text("Choosing Droidcast replaces your current digital assistant. It does not request microphone access, screen contents or screenshots. Use the app icon or Quick Settings tile to keep your existing assistant.", color = PaletteTheme.tertiary, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(16.dp))
        }
        Spacer(Modifier.height(16.dp))
        SettingsCard {
            SettingsPreferenceRow("Global shortcut", "Show or hide the palette from another app.") {
                Text(store.shortcutConfiguration.bindings[ShortcutRules.PALETTE]?.label() ?: "Not assigned", fontSize = 12.sp, color = PaletteTheme.ink,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(PaletteTheme.control).padding(8.dp, 6.dp).testTag("settings-global-value"))
                OutlinedButton(onClick = { activity.configure(ShortcutRules.PALETTE, "hotkey") }, contentPadding = PaddingValues(12.dp, 4.dp), modifier = Modifier.heightIn(min = 36.dp).testTag("settings-edit-global")) { Text("Change", fontSize = 12.sp) }
            }
            HorizontalDivider(color = PaletteTheme.separator, modifier = Modifier.padding(vertical = 8.dp))
            SettingsPreferenceRow("Android approval", when (activity.serviceConsent) {
                KeyboardConsent.APPROVED -> "Approved in Android Accessibility settings."
                KeyboardConsent.OFF -> "Not enabled · required for custom hotkeys in other apps."
                KeyboardConsent.UNKNOWN -> "Could not read approval. Reopen Android Accessibility settings."
            }) {
                TextButton(onClick = activity::enableService, contentPadding = PaddingValues(8.dp), modifier = Modifier.testTag("settings-service")) { Text(if (activity.serviceEnabled) "Manage service" else "Enable service", fontSize = 12.sp) }
            }
            SettingsPreferenceRow("Service connection", "Live connection reported by the keyboard service.") {
                Text(if (connection) "Connected" else "Not connected", fontSize = 12.sp,
                    color = if (connection) PaletteTheme.accent else PaletteTheme.secondary, modifier = Modifier.testTag("settings-service-connection"))
            }
            HorizontalDivider(color = PaletteTheme.separator, modifier = Modifier.padding(vertical = 8.dp))
            Text(readiness.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.testTag("settings-shortcut-readiness"))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Check delivery in this Settings screen.", fontSize = 11.sp, color = PaletteTheme.secondary, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = activity::beginShortcutProbe, enabled = readiness.ready && probe.status != ShortcutProbeStatus.LISTENING,
                    modifier = Modifier.testTag("settings-shortcut-test")) { Text(if (probe.status == ShortcutProbeStatus.LISTENING) "Listening…" else "Check shortcut", fontSize = 12.sp) }
            }
            Text(when (probe.status) {
                ShortcutProbeStatus.IDLE -> "After checking here, switch to another app and press your saved shortcut to test global activation."
                ShortcutProbeStatus.LISTENING -> "Press ${probe.expected?.label()} now. Waiting up to 15 seconds."
                ShortcutProbeStatus.RECEIVED -> "Received ${probe.expected?.label()} in Settings. Now try it from another app."
                ShortcutProbeStatus.EXPIRED -> "Nothing received. Check the service connection and your tablet’s keyboard mappings."
            }, fontSize = 11.sp, lineHeight = 16.sp, color = PaletteTheme.secondary,
                modifier = Modifier.padding(top = 6.dp).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }.testTag("settings-shortcut-test-result"))
            if (readiness.ready) TextButton(onClick = { showSetup = !showSetup }, modifier = Modifier.testTag("settings-shortcut-help")) { Text(if (showSetup) "Hide setup steps" else "Setup steps", fontSize = 12.sp) }
            if (!readiness.ready || showSetup) {
                Text("1. Save a global shortcut with Change above (for example Alt + Q).\n\n2. If Android blocks access, open App info → ⋮ → Allow restricted settings, when available. Only approve an APK you trust. If that menu is missing, use Android Settings → Apps → Droidcast; tablet labels may differ.\n\n3. Open Accessibility, choose Droidcast keyboard shortcuts under Installed / Downloaded apps and enable it.\n\n4. Check delivery here, then try the shortcut from another app.",
                    color = PaletteTheme.secondary, fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.testTag("settings-shortcut-steps"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = activity::openAppInfo, modifier = Modifier.testTag("settings-shortcut-app-info")) { Text("App info", fontSize = 12.sp) }
                    TextButton(onClick = { activity.command(Command("web", "Android help", "", payload = "https://support.google.com/android/answer/12623953?hl=en")) }) { Text("Android’s instructions", fontSize = 12.sp) }
                }
            }
        }
        Text("The keyboard service filters matching key pairs. The inline check observes only your configured palette shortcut. It does not retrieve screen content or save keystrokes. Android controls approval.", color = PaletteTheme.tertiary, fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(start = 2.dp, bottom = 12.dp))
        Text("Without keyboard approval, open the app icon or choose Droidcast as your Android assistant above. You can also add Droidcast search from Quick Settings → Edit tiles.", color = PaletteTheme.secondary, fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 6.dp).testTag("settings-shortcut-alternatives"))
        TextButton(onClick = activity::returnToPalette, modifier = Modifier.testTag("settings-shortcut-open-palette")) { Text("Open palette", fontSize = 12.sp) }
        SettingsSectionLabel("Settings navigation")
        SettingsCard {
            listOf("Open settings" to "Ctrl / Meta + ,", "Configure selected command" to "Ctrl / Meta + Shift + ,", "Search settings" to "Ctrl / Meta + F").forEachIndexed { index, (title, chord) ->
                if (index > 0) HorizontalDivider(color = PaletteTheme.separator)
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontSize = 12.sp, modifier = Modifier.weight(1f)); KeyCap(chord)
                }
            }
        }
        Text("These keys take precedence inside Droidcast. Existing global assignments still work in other apps.", color = PaletteTheme.tertiary, fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(start = 2.dp, bottom = 18.dp))
        SettingsSectionLabel("Your workspace")
        SettingsCard {
            SettingsPreferenceRow("Search folders", "Choose which folders Droidcast can index.") { TextButton(onClick = { activity.selectPage("folders") }) { Text("Manage", fontSize = 12.sp) } }
            HorizontalDivider(color = PaletteTheme.separator, modifier = Modifier.padding(vertical = 6.dp))
            SettingsPreferenceRow("Backup and restore", "Export collections or inspect an import.") { TextButton(onClick = { activity.selectPage("backup") }) { Text("Open", fontSize = 12.sp) } }
        }
        Text("Collections and folder indexes stay on this device. Store requests contact Raycast; running extensions can make HTTPS requests. No account or analytics.", color = PaletteTheme.tertiary, fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(start = 2.dp, top = 4.dp))
        store.shortcutError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
    }
}

private data class ShortcutSettingsRow(val id: String, val title: String, val detail: String, val alias: String, val kind: Kind?, val available: Boolean)
private fun shortcutRows(store: PaletteStore): List<ShortcutSettingsRow> {
    val commands = store.configurationCommands()
    val rows = commands.filter { ShortcutRules.targetAllowed(it.id) }.map { ShortcutSettingsRow(it.id, it.title, it.subtitle, it.alias, it.kind, true) }
    val known = rows.map { it.id }.toSet() + ShortcutRules.PALETTE
    val unavailableIds = store.shortcutConfiguration.bindings.keys + store.aliases.filterValues { it.isNotBlank() }.keys
    val unavailable = unavailableIds.filterNot { it in known }.map { ShortcutSettingsRow(it, "Unavailable command", it, store.aliases[it].orEmpty(), if (it.startsWith("files:")) Kind.FILE else null, false) }
    return listOf(ShortcutSettingsRow(ShortcutRules.PALETTE, "Show or hide Droidcast", "Global palette shortcut", "", null, true)) + rows.sortedBy { it.title.lowercase() } + unavailable
}
@Composable
private fun SettingsShortcuts(activity: SettingsActivity) {
    val store = activity.store
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf("") }
    var assigned by rememberSaveable { mutableStateOf(false) }
    var category by rememberSaveable { mutableStateOf("all") }
    var categoryMenu by remember { mutableStateOf(false) }
    var mobileDetail by rememberSaveable { mutableStateOf(false) }
    val rows = shortcutRows(store)
    val selectedId = activity.requestedCommand ?: ShortcutRules.PALETTE
    val selected = rows.firstOrNull { it.id == selectedId } ?: ShortcutSettingsRow(selectedId, "Unavailable command", selectedId, "", null, false)
    LaunchedEffect(activity.requestedCommand) { if (activity.requestedCommand != null) mobileDetail = true }
    LaunchedEffect(activity.requestedCommand, activity.requestedEditor, store.loading, store.folderSearch.loading) {
        if (!store.loading && !store.folderSearch.loading && activity.requestedEditor == "hotkey" && (selected.available || selected.id in store.shortcutConfiguration.bindings)) {
            store.beginShortcutRecording(selected.id); activity.consumeEditorRequest()
        }
    }
    val visible = rows.filter { row ->
        (category == "all" || row.kind?.name == category || (category == "global" && row.id == ShortcutRules.PALETTE)) &&
            (!assigned || row.id in store.shortcutConfiguration.bindings || row.alias.isNotBlank()) &&
            (query.isBlank() || "${row.title} ${row.detail} ${row.alias} ${store.shortcutConfiguration.bindings[row.id]?.label().orEmpty()}".contains(query, true))
    }
    Column(Modifier.fillMaxSize().padding(if (LocalSettingsCompact.current) 16.dp else 24.dp)) {
        SettingsHeading("Shortcuts", "Manage command aliases and keyboard shortcuts in one place.")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SettingsInput(query, { query = it }, "Search commands, aliases or hotkeys", "settings-command-search", Modifier.weight(1f), leading = true)
            Spacer(Modifier.width(8.dp))
            Box {
                OutlinedButton(onClick = { focusManager.clearFocus(); keyboard?.hide(); categoryMenu = true }, contentPadding = PaddingValues(10.dp, 5.dp), modifier = Modifier.heightIn(min = 40.dp).testTag("settings-category").paletteFocusBookmark()) {
                    Text(Kind.entries.firstOrNull { it.name == category }?.label ?: if (category == "global") "Activation" else "All commands", fontSize = 11.sp)
                }
                PaletteChoiceMenu(categoryMenu, { categoryMenu = false },
                    (listOf("all" to "All commands", "global" to "Activation") + Kind.entries.filter { it != Kind.CALC }.map { it.name to it.label }).map { (id, label) ->
                        PaletteChoice("settings-category-$id", label, checked = category == id) { category = id; categoryMenu = false }
                    }, title = "Command categories")
            }
            Spacer(Modifier.width(10.dp))
            FilterChip(assigned, onClick = { assigned = !assigned }, label = { Text("Assigned", fontSize = 11.sp) }, modifier = Modifier.testTag("settings-assigned"))
        }
        Spacer(Modifier.height(16.dp))
        BoxWithConstraints(Modifier.weight(1f)) {
            val split = maxWidth >= 740.dp
            if (!split && mobileDetail) {
                BackHandler(enabled = store.recordingTarget == null && store.dialog != "shortcut-reset") { mobileDetail = false; activity.clearCommandRequest() }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).onPreviewKeyEvent {
                    if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ESCAPE && store.recordingTarget == null && store.dialog != "shortcut-reset") {
                        mobileDetail = false; activity.clearCommandRequest(); true
                    } else false
                }) {
                    TextButton(onClick = { store.cancelShortcutRecording(false); mobileDetail = false; activity.clearCommandRequest() }, modifier = Modifier.testTag("settings-command-back")) { Text("← All commands") }
                    ShortcutInspector(activity, selected)
                }
            } else Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Surface(Modifier.weight(1f).fillMaxHeight(), color = Color.White.copy(alpha = .018f), shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, PaletteTheme.separator)) {
                    Column {
                        Row(Modifier.fillMaxWidth().height(36.dp).background(Color.White.copy(alpha = .025f)).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("COMMAND", fontSize = 9.sp, letterSpacing = .7.sp, color = PaletteTheme.tertiary, modifier = Modifier.weight(1f))
                            if (split) { Text("ALIAS", fontSize = 9.sp, color = PaletteTheme.tertiary, modifier = Modifier.width(70.dp)); Text("HOTKEY", fontSize = 9.sp, color = PaletteTheme.tertiary, modifier = Modifier.width(112.dp)) }
                        }
                        HorizontalDivider(color = PaletteTheme.separator)
                        LazyColumn(Modifier.fillMaxSize().testTag("settings-command-list")) {
                            val grouped = visible.groupBy { if (it.id == ShortcutRules.PALETTE) "Activation" else it.kind?.label ?: "Unavailable" }
                            grouped.forEach { (group, commands) ->
                                item(key = "section:$group") { Text(group, color = PaletteTheme.tertiary, fontSize = 10.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(14.dp, 10.dp)) }
                                items(commands, key = { it.id }) { row ->
                                    Row(Modifier.fillMaxWidth().background(if (row.id == selectedId) Color.White.copy(alpha = .08f) else Color.Transparent)
                                        .clickable { focusManager.clearFocus(); keyboard?.hide(); activity.configure(row.id); mobileDetail = true }
                                        .heightIn(min = 46.dp).padding(horizontal = 14.dp, vertical = 8.dp).testTag("settings-command-${row.id}"), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).background(Color.White.copy(alpha = .06f)), contentAlignment = Alignment.Center) { Glyph(shortcutIcon(row), shortcutTint(row), 16) }
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(row.title, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            if (!split && row.alias.isNotBlank()) Text(row.alias, fontSize = 10.sp, color = PaletteTheme.tertiary, modifier = Modifier.padding(top = 3.dp))
                                        }
                                        if (split) Text(row.alias.ifEmpty { "—" }, fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = PaletteTheme.secondary, modifier = Modifier.width(70.dp).padding(end = 8.dp))
                                        Box(Modifier.width(if (split) 112.dp else 104.dp)) { store.shortcutConfiguration.bindings[row.id]?.let { KeyCap(it.label()) } }
                                    }
                                }
                            }
                            if (visible.isEmpty()) item { Text("No matching commands", fontSize = 12.sp, color = PaletteTheme.secondary, modifier = Modifier.padding(18.dp)) }
                        }
                    }
                }
                if (split) Column(Modifier.width(310.dp).fillMaxHeight().verticalScroll(rememberScrollState())) { ShortcutInspector(activity, selected) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${visible.size} commands", fontSize = 11.sp, color = PaletteTheme.secondary, modifier = Modifier.weight(1f))
            TextButton(onClick = { store.cancelShortcutRecording(false); store.dialog = "shortcut-reset"; mobileDetail = true }, modifier = Modifier.testTag("settings-reset-shortcuts")) { Text("Reset shortcuts…", fontSize = 11.sp) }
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun ShortcutInspector(activity: SettingsActivity, row: ShortcutSettingsRow) {
    val store = activity.store
    val recording = store.recordingTarget == row.id
    val recordFocus = remember(row.id) { FocusRequester() }
    val aliasFocus = remember(row.id) { FocusRequester() }
    var aliasReady by remember(row.id) { mutableStateOf(false) }
    var wasRecording by remember(row.id) { mutableStateOf(false) }
    var alias by remember(row.id, row.alias) { mutableStateOf(row.alias) }
    var aliasError by remember(row.id) { mutableStateOf<String?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    fun saveAlias(value: String = alias) {
        keyboard?.hide()
        runCatching { store.setAlias(row.id, value) }.onSuccess {
            alias = value.trim().lowercase(); aliasError = null
            store.message = if (alias.isEmpty()) "Alias cleared" else "Alias saved"
        }.onFailure { aliasError = it.message }
    }
    val aliasErrorVisibility = remember(row.id) { BringIntoViewRequester() }
    var aliasErrorPlaced by remember(row.id, aliasError) { mutableStateOf(false) }
    LaunchedEffect(aliasError, aliasErrorPlaced) { if (aliasError != null && aliasErrorPlaced) aliasErrorVisibility.bringIntoView() }
    LaunchedEffect(recording) {
        if (wasRecording && !recording && store.dialog != "shortcut-reset") recordFocus.requestFocus()
        wasRecording = recording
    }
    LaunchedEffect(row.id, activity.requestedEditor, aliasReady) {
        if (aliasReady && activity.requestedEditor == "alias" && row.available && row.id != ShortcutRules.PALETTE) { aliasFocus.requestFocus(); activity.consumeEditorRequest() }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).background(PaletteTheme.control, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) { Glyph(shortcutIcon(row), shortcutTint(row), 19) }
        Text(row.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp, modifier = Modifier.padding(start = 10.dp))
    }
    Text(row.detail, fontSize = 11.sp, color = PaletteTheme.secondary, modifier = Modifier.padding(top = 10.dp, bottom = 18.dp))
    if (!row.available) Text("This command is unavailable. Its saved shortcut and alias are retained. You can clear them here.", fontSize = 12.sp, color = PaletteTheme.secondary, modifier = Modifier.padding(bottom = 14.dp))
    if (store.dialog == "shortcut-reset") {
        ShortcutDialogs(store, activity.serviceEnabled, activity::enableService, { store.dialog = null }, embedded = true)
        return
    }
    if (recording) ShortcutDialogs(store, activity.serviceEnabled, activity::enableService, { store.cancelShortcutRecording(false) }, embedded = true)
    else SettingsCard {
        Text("KEYBOARD SHORTCUT", fontSize = 10.sp, color = PaletteTheme.secondary)
        Text(store.shortcutConfiguration.bindings[row.id]?.label() ?: "Not assigned", fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp).clip(RoundedCornerShape(6.dp)).background(PaletteTheme.control).padding(8.dp, 6.dp).testTag("settings-selected-shortcut"))
        Row(Modifier.padding(top = 12.dp)) {
            OutlinedButton(onClick = { store.beginShortcutRecording(row.id) }, enabled = row.available || row.id in store.shortcutConfiguration.bindings,
                modifier = Modifier.focusRequester(recordFocus).testTag("settings-record")) { Text("Record", fontSize = 12.sp) }
            if (row.id in store.shortcutConfiguration.bindings) TextButton(onClick = { store.setShortcut(row.id, null) }, modifier = Modifier.testTag("settings-clear-shortcut")) { Text("Clear", fontSize = 12.sp) }
        }
    }
    if (row.id != ShortcutRules.PALETTE) {
        Spacer(Modifier.height(16.dp))
        SettingsCard {
            Text("ALIAS", fontSize = 10.sp, color = PaletteTheme.secondary)
            SettingsInput(alias, { alias = it; aliasError = null }, "Add an alias", "settings-alias", enabled = !recording && row.available, done = { saveAlias() },
                modifier = Modifier.padding(top = 10.dp).focusRequester(aliasFocus).onGloballyPositioned { aliasReady = true })
            aliasError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp).bringIntoViewRequester(aliasErrorVisibility).onGloballyPositioned { aliasErrorPlaced = true }.testTag("settings-alias-error")) }
            Row(Modifier.padding(top = 10.dp)) {
                Button(onClick = { saveAlias() }, enabled = !recording && row.available, modifier = Modifier.testTag("settings-save-alias")) { Text("Save alias", fontSize = 12.sp) }
                TextButton(onClick = { saveAlias("") }, enabled = !recording && (row.available || row.alias.isNotBlank()), modifier = Modifier.testTag("settings-clear-alias")) { Text("Clear", fontSize = 12.sp) }
            }
        }
    }
    Text("Tab / Shift+Tab to move · Enter to activate.\nEscape cancels a recording. Global shortcuts pause while recording.", fontSize = 11.sp, color = PaletteTheme.secondary, modifier = Modifier.padding(top = 16.dp))
}

private fun shortcutIcon(row: ShortcutSettingsRow) = when (row.kind) {
    Kind.APP -> "app"; Kind.NOTE -> "note"; Kind.LINK -> "link"; Kind.FILE -> "folder"; Kind.CLIP -> "clipboard"; Kind.EXTENSION -> "extension"; else -> if (row.id == ShortcutRules.PALETTE) "keyboard" else "command"
}
private fun shortcutTint(row: ShortcutSettingsRow) = when (row.kind) {
    Kind.APP -> Color(0xFF88ADDB); Kind.NOTE -> Color(0xFFDDC68C); Kind.LINK -> Color(0xFFB6A1E5); Kind.FILE -> Color(0xFF8EBCCD); Kind.EXTENSION -> Color(0xFFE5A193); else -> PaletteTheme.secondary
}

@Composable
private fun SettingsSearchResults(activity: SettingsActivity, query: String, selected: () -> Unit) {
    val pages = settingsPages.filter { "${it.title} ${it.keywords}".contains(query, true) }
    val commands = shortcutRows(activity.store).filter { "${it.title} ${it.detail} ${it.alias}".contains(query, true) }.take(80)
    Column(Modifier.fillMaxSize().padding(28.dp)) {
        SettingsHeading("Search settings", "Jump to a page or configure a command.")
        LazyColumn {
            items(pages, key = { "page:${it.id}" }) { page ->
                ListItem(headlineContent = { Text(page.title) }, supportingContent = { Text("Settings page") }, modifier = Modifier.clickable { activity.selectPage(page.id); selected() })
            }
            items(commands, key = { "command:${it.id}" }) { command ->
                ListItem(headlineContent = { Text(command.title) }, supportingContent = { Text("Command shortcuts and alias") }, modifier = Modifier.clickable { activity.configure(command.id); selected() })
            }
            if (pages.isEmpty() && commands.isEmpty()) item { Text("No matching settings or commands.", color = PaletteTheme.secondary, fontSize = 13.sp) }
        }
    }
}
@Composable
private fun SettingsAbout(activity: SettingsActivity) {
    SettingsScrollPane("About", "Droidcast · native Android command palette") {
        SettingsCard {
            Text("0.4.0-preview · Development", fontWeight = FontWeight.Medium, fontSize = 16.sp)
            Text("An unofficial Android adaptation of Tinycast. Preferences take inspiration from Raycast; the launcher keeps its compact transparent palette.", fontSize = 12.sp, color = PaletteTheme.secondary, modifier = Modifier.padding(top = 12.dp))
            Text("AGPL-3.0-or-later. Tinycast © 2026 Abue Ammar. Android modifications © Droidcast contributors. React and the GitHub gemoji dataset are distributed under their MIT licenses. Full notices are bundled with the source and app. No warranty.", fontSize = 12.sp, color = PaletteTheme.secondary, modifier = Modifier.padding(top = 12.dp))
            TextButton(onClick = { activity.command(Command("web", "Source", "", payload = "https://github.com/moikdev/droidcast")) }) { Text("Source and notices") }
        }
        SettingsCard { Text("Compatibility remains partial", fontSize = 14.sp, fontWeight = FontWeight.Medium); Text("Android equivalents, extension APIs and advanced hotkeys are still being developed. See the repository's parity tracker for the tested scope.", fontSize = 12.sp, color = PaletteTheme.secondary, modifier = Modifier.padding(top = 10.dp)) }
    }
}
