package dev.tinycast.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity(), android.hardware.input.InputManager.InputDeviceListener {
    val store: PaletteStore by viewModels()
    var page by mutableStateOf("general"); private set
    var requestedCommand by mutableStateOf<String?>(null); private set
    var requestedEditor by mutableStateOf(""); private set
    var serviceConsent by mutableStateOf(KeyboardConsent.OFF); private set
    val serviceEnabled get() = serviceConsent == KeyboardConsent.APPROVED
    val shortcutProbe = ShortcutProbe { android.os.SystemClock.elapsedRealtime() }
    private var probeTimeout: Job? = null
    private var shortcutProbeVisible = false
    private var observingConsent = false
    private val consentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { refreshServiceConsent() }
    }
    var assistantSelected by mutableStateOf(false); private set
    var searchFocusEpoch by mutableStateOf(0); private set
    private val navigationKeys = mutableSetOf<Pair<Int, Int>>()
    private var pendingOperation: String? = null
    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { store.writeExport(it) }
    private val importBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { store.readBackup(it) }
    private val chooseAssistantRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { assistantSelected = AssistantActivation.isSelected(this) }
    private val chooseFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { store.folderSearch.selected(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getSystemService(android.hardware.input.InputManager::class.java).registerInputDeviceListener(this, null)
        ShortcutRuntime.settingsCreated(this)
        if (resources.configuration.smallestScreenWidthDp >= 600) window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        if (savedInstanceState == null) receive(intent) else {
            page = savedInstanceState.getString("page", "general")
            requestedCommand = savedInstanceState.getString("command")
            requestedEditor = savedInstanceState.getString("editor", "")
            pendingOperation = savedInstanceState.getString("operation")
        }
        setContent { AdvancedSettings(this) }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page); outState.putString("command", requestedCommand)
        outState.putString("editor", requestedEditor); outState.putString("operation", pendingOperation)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume()
        ShortcutRuntime.settingsResumed(this)
        assistantSelected = AssistantActivation.isSelected(this)
        store.folderSearch.revalidate()
        refreshServiceConsent()
        if (pendingOperation == "import") { pendingOperation = null; store.beginBackup(); importBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
    }
    override fun onStart() {
        super.onStart()
        runCatching {
            contentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, consentObserver)
            observingConsent = true
            contentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED), false, consentObserver)
        }
    }
    private fun refreshServiceConsent() {
        serviceConsent = KeyboardServiceConsent.read(this)
        if (!serviceEnabled) cancelShortcutProbe()
    }
    override fun onPause() { cancelShortcutProbe(); store.clearModifierRecording(); navigationKeys.clear(); ShortcutRuntime.settingsPaused(this); super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (!hasFocus) { cancelShortcutProbe(); store.clearModifierRecording() } }
    // Public Activity/Window.Callback hook; AndroidX core's hidden base class
    // carries a class-wide restriction that lint also applies to this override.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val key = event.deviceId to event.keyCode
        if (event.action == KeyEvent.ACTION_UP && navigationKeys.remove(key)) return true
        val modifier = (event.isCtrlPressed xor event.isMetaPressed) && !event.isAltPressed
        if (event.action == KeyEvent.ACTION_DOWN && modifier && store.recordingTarget == null && !store.paletteMenuOpen &&
            (event.keyCode == KeyEvent.KEYCODE_COMMA || (event.keyCode == KeyEvent.KEYCODE_F && !event.isShiftPressed))) {
            navigationKeys.add(key)
            if (event.keyCode == KeyEvent.KEYCODE_F && event.repeatCount == 0) searchFocusEpoch++
            return true
        }
        if (super.dispatchKeyEvent(event)) return true
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ESCAPE && event.asChord().modifiers == 0) {
            onBackPressedDispatcher.onBackPressed(); return true
        }
        return false
    }
    override fun onInputDeviceAdded(deviceId: Int) = Unit
    override fun onInputDeviceChanged(deviceId: Int) { store.clearModifierRecording() }
    override fun onInputDeviceRemoved(deviceId: Int) { store.clearModifierRecording() }
    override fun onDestroy() {
        getSystemService(android.hardware.input.InputManager::class.java).unregisterInputDeviceListener(this)
        ShortcutRuntime.settingsDestroyed(this); super.onDestroy()
    }
    override fun onStop() {
        if (observingConsent) { contentResolver.unregisterContentObserver(consentObserver); observingConsent = false }
        if (!isChangingConfigurations) store.cancelShortcutRecording(toManager = false)
        super.onStop()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (store.backupBusy) { store.message = "Finish the backup operation first."; return }
        cancelShortcutProbe(); store.cancelShortcutRecording(toManager = false); receive(intent)
    }
    private fun receive(intent: Intent) {
        page = intent.getStringExtra("page")?.takeIf { it in setOf("general", "shortcuts", "folders", "backup", "about") } ?: "general"
        requestedCommand = intent.getStringExtra("command")?.takeIf(ShortcutRules::targetAllowed)
        requestedEditor = intent.getStringExtra("editor")?.takeIf { it in setOf("alias", "hotkey") }.orEmpty()
        pendingOperation = intent.getStringExtra("operation")?.takeIf { it == "import" }
        if (page == "backup") store.beginBackup()
    }
    fun selectPage(value: String) {
        if (store.backupBusy) { store.message = "Finish the backup operation first."; return }
        cancelShortcutProbe(); store.cancelShortcutRecording(toManager = false)
        page = value; requestedEditor = ""; requestedCommand = null
        if (value == "backup") store.beginBackup() else { store.discardImport(); store.dialog = null }
    }
    fun configure(target: String, editor: String = "") {
        if (store.backupBusy) { store.message = "Finish the backup operation first."; return }
        cancelShortcutProbe(); store.cancelShortcutRecording(toManager = false)
        page = "shortcuts"; requestedCommand = target; requestedEditor = editor
    }
    fun clearCommandRequest() { requestedCommand = null; requestedEditor = "" }
    fun consumeEditorRequest() { requestedEditor = "" }
    fun back() {
        when {
            store.backupBusy -> Unit
            store.recordingTarget != null -> store.cancelShortcutRecording(toManager = false)
            store.dialog == "shortcut-reset" -> store.dialog = null
            page == "backup" && store.dialog == "backup-confirm" -> store.dialog = "backup-preview"
            page == "backup" && store.backupDocument != null -> store.discardImport()
            else -> finish()
        }
    }
    fun chooseAssistant() {
        runCatching { chooseAssistantRole.launch(AssistantActivation.selectionIntent()) }.onFailure { store.message = "Assistant selection is unavailable on this device. Use the app icon or Quick Settings tile." }
    }
    fun openNavigationSettings() = navigationFeedback(AssistantActivation.openNavigationSettings(::startActivity))
    internal fun navigationFeedback(result: AssistantActivation.NavigationLaunch) {
        when (result) {
            AssistantActivation.NavigationLaunch.NAVIGATION -> Unit
            AssistantActivation.NavigationLaunch.ANDROID_SETTINGS -> store.message = "Open System → Gestures or Navigation mode. Labels vary by device."
            AssistantActivation.NavigationLaunch.UNAVAILABLE -> store.message = "Navigation settings could not open. Open Android Settings → System → Gestures or Navigation mode manually."
        }
    }
    fun enableService() = openAndroidSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    fun openAppInfo() = openAndroidSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    private fun openAndroidSettings(intent: Intent) {
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { store.message = "This Android settings page is unavailable. Open Android Settings → Apps → Droidcast manually." }
    }
    fun beginShortcutProbe() {
        refreshServiceConsent()
        val chord = store.shortcutConfiguration.bindings[ShortcutRules.PALETTE]
        if (!shortcutProbeVisible || page != "general" || !window.decorView.hasWindowFocus() || !ShortcutReadiness(chord, serviceConsent, ShortcutRuntime.keyboardConnection.connected).ready || store.recordingTarget != null || store.backupBusy) return
        probeTimeout?.cancel()
        val token = shortcutProbe.begin(chord!!, ShortcutRuntime.keyboardConnection.epoch)
        probeTimeout = lifecycleScope.launch { delay(15_000); shortcutProbe.expire(token) }
    }
    internal fun shortcutProbeVisibilityChanged(visible: Boolean) {
        shortcutProbeVisible = visible
        if (!visible) cancelShortcutProbe()
    }
    internal fun isShortcutProbeArmed(chord: KeyChord): Boolean {
        val connection = ShortcutRuntime.keyboardConnection
        if (!shortcutProbeVisible || !window.decorView.hasWindowFocus() || page != "general" ||
            !connection.connected || KeyboardServiceConsent.read(this) != KeyboardConsent.APPROVED) {
            if (shortcutProbe.status == ShortcutProbeStatus.LISTENING) cancelShortcutProbe()
            return false
        }
        val armed = shortcutProbe.isArmedFor(chord, store.shortcutConfiguration.bindings[ShortcutRules.PALETTE], connection.epoch)
        if (shortcutProbe.status != ShortcutProbeStatus.LISTENING) { probeTimeout?.cancel(); probeTimeout = null }
        return armed
    }
    internal fun receiveShortcutProbe(target: String, chord: KeyChord): Boolean {
        if (target != ShortcutRules.PALETTE || !isShortcutProbeArmed(chord)) return false
        val accepted = shortcutProbe.receive(target, chord, store.shortcutConfiguration.bindings[ShortcutRules.PALETTE], ShortcutRuntime.keyboardConnection.epoch)
        if (accepted) { probeTimeout?.cancel(); probeTimeout = null }
        return accepted
    }
    fun cancelShortcutProbe() { probeTimeout?.cancel(); probeTimeout = null; shortcutProbe.cancel() }
    fun returnToPalette() { if (!store.backupBusy) { startActivity(Intent(this, MainActivity::class.java)); finish() } }
    fun openFileSearch() { if (!store.backupBusy) { startActivity(Intent(this, MainActivity::class.java).setAction("dev.droidcast.SHOW_FILES")); finish() } }
    fun command(command: Command) {
        when (command.id) {
            "open-file-search" -> openFileSearch()
            "backup-write" -> store.prepareExport { createBackup.launch("Droidcast-${java.time.LocalDate.now()}.json") }
            "backup-read" -> importBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            "folder-add", "folder-reauthorize" -> {
                val root = command.payload.takeIf { it.isNotBlank() }
                if (store.folderSearch.beginSelection(root)) runCatching { chooseFolder.launch(root?.let(Uri::parse)) }
                    .onFailure { store.folderSearch.selected(null); store.message = "Folder chooser is unavailable." }
            }
            "web" -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(command.payload))) }.onFailure { store.message = "No browser is available." }
        }
    }
}
