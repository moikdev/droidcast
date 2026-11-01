package dev.tinycast.android

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.Menu
import android.view.WindowManager
import android.hardware.input.InputManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), InputManager.InputDeviceListener {
    val store: PaletteStore by viewModels()
    var shortcutEnabled by mutableStateOf(false)
    private var sharedText by mutableStateOf<String?>(null)
    private val modifierShortcuts = ModifierShortcutObserver()
    private var documentNameJob: Job? = null
    private var documentOpenJob: Job? = null
    private val documentReader by lazy { DocumentProviderQueries(applicationContext.contentResolver) }
    private fun cancelDocumentOpen() { documentOpenJob?.cancel(); documentOpenJob = null }
    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { store.writeExport(it) }
    private val importBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { store.readBackup(it) }
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { rememberDocument(it) }
    private val browseIndexedFolder = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) rememberDocument(result.data?.data)
    }
    internal fun rememberDocument(uri: Uri?, reader: RecentDocumentReader = documentReader) {
        if (uri == null) return
        documentNameJob?.cancel()
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val existing = store.files.firstOrNull { it.body == uri.toString() }
        // Track the granted URI immediately. A cancelled metadata lookup must not
        // leave an untracked permission or discard the user's selected document.
        val id = store.save("files", existing?.title ?: "Document", uri.toString(), id = existing?.id)
        store.message = "Document added to recent files"
        documentNameJob = lifecycleScope.launch {
            try {
                val title = reader.displayName(uri)
                // Do not resurrect a reference removed while its provider was busy.
                store.files.firstOrNull { it.id == id && it.body == uri.toString() }?.let { saved ->
                    if (title != null) store.save("files", title, saved.body, saved.alias, saved.id)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { store.message = "Document saved. Its name could not be read; choose it again to retry." }
        }
    }
    internal fun openRecentDocument(command: Command, reader: RecentDocumentReader = documentReader) {
        cancelDocumentOpen()
        val uri = Uri.parse(command.payload)
        val query = store.query; val category = store.category; val route = store.dialog; val epoch = store.focusEpoch
        documentOpenJob = lifecycleScope.launch {
            try {
                val type = command.mimeType ?: reader.mimeType(uri) ?: "*/*"
                // A delayed provider response cannot open a file after the user has
                // navigated elsewhere or a newer operation has taken ownership.
                if (store.query == query && store.category == category && store.dialog == route && store.focusEpoch == epoch &&
                    !isFinishing && window.decorView.hasWindowFocus()) {
                    open(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: DocumentProviderTimeout) { store.message = "Storage provider timed out. Select the document again to retry." }
            catch (_: Exception) { store.message = "Document access expired. Choose it again with Open a document." }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ShortcutRuntime.paletteOpening()) { finish(); return }
        getSystemService(InputManager::class.java).registerInputDeviceListener(this, null)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        if (resources.configuration.smallestScreenWidthDp >= 600) window.setSoftInputMode((window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE.inv()) or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        if (savedInstanceState == null) receive(intent)
        setContent {
            LaunchedEffect(store.pendingShortcut, store.loading, store.folderSearch.loading) {
                val request = store.pendingShortcut
                if (request != null && !store.loading && !store.folderSearch.loading) {
                    store.pendingShortcut = null
                    if (!ShortcutRuntime.requests.isFresh(request) || store.shortcutConfiguration.bindings[request.target] != request.chord) store.message = "Shortcut changed or expired. Try again."
                    else store.command(request.target)?.let(::execute) ?: run { store.message = "This shortcut's command is unavailable. Clear or reassign it in Keyboard shortcuts." }
                }
            }
            LaunchedEffect(sharedText) {
                sharedText?.let {
                    store.editing = SavedItem("", "Shared text", it)
                    store.dialog = "clip"
                    sharedText = null
                }
            }
            TinycastApp(store, shortcutEnabled, ::execute, ::copyText, ::enableShortcut, { finish() }, { target, chord -> ShortcutRuntime.dispatch(this, target, chord) })
        }
    }
    override fun onResume() {
        super.onResume()
        ShortcutRuntime.resumed(this)
        store.folderSearch.revalidate()
        if (store.dialog == "updates") store.updates.refresh()
        shortcutEnabled = KeyboardServiceConsent.read(this) == KeyboardConsent.APPROVED
    }
    override fun onPause() { cancelDocumentOpen(); modifierShortcuts.clear(); store.clearModifierRecording(); ShortcutRuntime.paused(this); super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) { modifierShortcuts.clear(); store.clearModifierRecording() }
    }
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val connection = ShortcutRuntime.keyboardConnection
            // The connected service owns taps globally, including this window.
            // Modifier pairs continue to Android unchanged; never consume only UP.
            val tap = modifierShortcuts.handle(event.asShortcutInput(), store.shortcutConfiguration.byChord,
                !connection.connected && window.decorView.hasWindowFocus() && !isFinishing &&
                    !store.backupBusy && !store.paletteMenuOpen && ShortcutRuntime.recordingOwner == null, connection.epoch, ShortcutRuntime.recordingEpoch)
            tap?.let { (target, binding) -> ShortcutRuntime.dispatch(this, target, binding) }
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onInputDeviceAdded(deviceId: Int) = Unit
    override fun onInputDeviceChanged(deviceId: Int) { modifierShortcuts.clear(); store.clearModifierRecording() }
    override fun onInputDeviceRemoved(deviceId: Int) { modifierShortcuts.clear(); store.clearModifierRecording() }
    override fun onDestroy() { getSystemService(InputManager::class.java).unregisterInputDeviceListener(this); super.onDestroy() }
    override fun onStop() { if (!isChangingConfigurations) store.cancelShortcutRecording(); super.onStop() }
    fun acceptShortcut(token: String?, toggleExisting: Boolean = false) {
        val request = ShortcutRuntime.requests.consume(token)
        cancelDocumentOpen()
        if (request == null || !ShortcutRuntime.requests.isFresh(request) || store.shortcutConfiguration.bindings[request.target] != request.chord) { store.message = "Shortcut changed or expired. Try again."; return }
        if (store.backupBusy || ShortcutRuntime.recordingOwner != null) { store.message = "Finish the current operation before running a shortcut."; return }
        if (request.target == ShortcutRules.PALETTE) { if (toggleExisting) finish() else store.reset() }
        else if (toggleExisting && request.target == "calculator" && store.dialog == null && store.category == "All" && store.query.trimStart().startsWith("=")) finish()
        else { store.reset(); store.pendingShortcut = request }
    }
    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        cancelDocumentOpen()
        modifierShortcuts.clear(); store.clearModifierRecording()
        if (store.backupBusy) { store.message = "Backup in progress. Please try again when it finishes."; return }
        store.pendingShortcut = null
        store.reset(); receive(intent)
    }
    private fun receive(intent: Intent) {
        if (intent.action == "dev.droidcast.SHOW_FILES") { store.filter("Files"); return }
        if (intent.action == ShortcutRuntime.ACTION) { acceptShortcut(intent.getStringExtra(ShortcutRuntime.TOKEN)); return }
        sharedText = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }
    }
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>?, menu: Menu?, deviceId: Int) {
        data?.add(KeyboardShortcutGroup("Droidcast", listOf(
            KeyboardShortcutInfo("Focus search", KeyEvent.KEYCODE_L, KeyEvent.META_CTRL_ON),
            KeyboardShortcutInfo("Actions", KeyEvent.KEYCODE_K, KeyEvent.META_CTRL_ON),
            KeyboardShortcutInfo("New note", KeyEvent.KEYCODE_N, KeyEvent.META_CTRL_ON),
            KeyboardShortcutInfo("Pin or unpin", KeyEvent.KEYCODE_D, KeyEvent.META_CTRL_ON)
        )))
        val titles = store.commands().associate { it.id to it.title } + (ShortcutRules.PALETTE to "Show or hide Droidcast")
        val bindings = store.shortcutConfiguration.bindings.entries.filter { it.value.trigger == ShortcutTrigger.CHORD }.take(50)
            .map { (id, chord) -> KeyboardShortcutInfo(titles[id] ?: "Unavailable command · $id", chord.keyCode, chord.nativeModifiers()) }
        if (bindings.isNotEmpty()) data?.add(KeyboardShortcutGroup(if (store.shortcutConfiguration.bindings.size > 50) "Configured shortcuts (first 50)" else "Configured shortcuts", bindings))
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
    }
    private fun enableShortcut() { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    fun copyText(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Droidcast", text))
        store.message = "Copied to clipboard"
    }
    private fun open(intent: Intent, unavailableMessage: String = "No available app can open this item") {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        } catch (_: ActivityNotFoundException) {
            store.message = unavailableMessage
        } catch (_: SecurityException) {
            store.message = unavailableMessage
        }
    }
    fun openSystemSettings(destination: SettingsDestination) = open(destination.intent(packageName), "${destination.title} is unavailable on this device.")
    private fun openSettings(page: String = "general", command: String? = null, editor: String = "", operation: String? = null) {
        if (store.backupBusy) return
        startActivity(Intent(this, SettingsActivity::class.java).putExtra("page", page).putExtra("command", command)
            .putExtra("editor", editor).putExtra("operation", operation).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
    fun execute(command: Command) {
        if (store.backupBusy) return
        cancelDocumentOpen()
        if (command.id !in setOf("backup", "backup-export", "backup-import", "backup-write", "backup-read")) store.used(command.id)
        if (command.kind == Kind.COMMAND) {
            SystemSettings.find(command.id)?.let { openSystemSettings(it); return }
        }
        when (command.kind) {
            Kind.CALC, Kind.CLIP -> copyText(command.payload)
            Kind.APP -> open(Intent.makeMainActivity(ComponentName.unflattenFromString(command.payload)))
            Kind.NOTE -> { store.editing = store.notes.firstOrNull { it.id == command.id }; store.dialog = "note" }
            Kind.LINK -> {
                if (command.payload.contains("{query}")) { store.editing = store.links.firstOrNull { it.id == command.id }; store.dialog = "link-query" }
                else open(Intent(Intent.ACTION_VIEW, Uri.parse(command.payload)))
            }
            Kind.FILE -> {
                val uri = Uri.parse(command.payload)
                if (command.mimeType == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                    browseIndexedFolder.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                        .putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, uri))
                    return
                }
                openRecentDocument(command)
            }
            Kind.EXTENSION -> {
                val parts = command.payload.split('/', limit = 2)
                val extension = store.extensions.installed.firstOrNull { it.listing.id == parts[0] }
                val entry = extension?.commands?.firstOrNull { it.name == parts.getOrNull(1) }
                if (extension != null && entry != null) openExtension(extension, entry) else store.message = "This extension is no longer installed"
            }
            Kind.COMMAND -> when (command.id) {
                "check-updates" -> { store.updates.check(); store.dialog = "updates" }
                "backup", "backup-export" -> openSettings("backup")
                "backup-import" -> openSettings("backup", operation = "import")
                "backup-write" -> store.prepareExport { createBackup.launch("Droidcast-${java.time.LocalDate.now()}.json") }
                "backup-read" -> importBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                "extensions" -> store.dialog = "extensions"
                "calculator" -> { store.category = "All"; store.search("= "); store.focusEpoch++ }
                "emoji" -> { store.emoji.search(""); store.emoji.chooseFilter("All"); store.dialog = "emoji" }
                "new-note" -> { store.editing = null; store.dialog = "note" }
                "new-link" -> { store.editing = null; store.dialog = "link" }
                "browse-files" -> openDocument.launch(arrayOf("*/*"))
                "search-files" -> store.filter("Files")
                "search-folders" -> openSettings("folders")
                "folder-add", "folder-reauthorize" -> openSettings("folders")
                "save-clip" -> {
                    val manager = getSystemService(ClipboardManager::class.java)
                    val text = manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
                    if (text.isNullOrBlank()) store.message = "No clipboard text available. Copy some text first."
                    else { store.editing = SavedItem("", text.take(60).lineSequence().first(), text); store.dialog = "clip" }
                }
                "shortcuts" -> openSettings("shortcuts")
                "configure-command" -> openSettings("shortcuts", command.payload)
                "configure-shortcut" -> openSettings("shortcuts", command.payload, "hotkey")
                "configure-alias" -> openSettings("shortcuts", command.payload, "alias")
                "settings" -> open(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                "app-settings" -> openSettings()
                "web" -> open(Intent(Intent.ACTION_VIEW, Uri.parse(command.payload)))
            }
        }
    }
    private fun openExtension(extension: dev.tinycast.android.extensions.InstalledExtension, command: dev.tinycast.android.extensions.ExtensionCommand) { store.openExtension(extension, command) }
}
