package dev.tinycast.android

import android.app.Application
import android.content.Intent
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import dev.tinycast.android.extensions.*

data class SavedItem(val id: String, val title: String, val body: String, val alias: String = "")

internal data class EditorIdentity(val kind: String, val item: String?, val epoch: Int)
internal class PaletteEditorDraft(val identity: EditorIdentity, item: SavedItem?) {
    var title by mutableStateOf(item?.title.orEmpty())
    var body by mutableStateOf(item?.body.orEmpty())
    var alias by mutableStateOf(item?.alias.orEmpty())
    var argument by mutableStateOf("")
}

class PaletteStore(application: Application) : AndroidViewModel(application) {
    internal val updates = dev.tinycast.android.updates.Updates.get(application)
    val extensions = ExtensionCatalog(application)
    val folderSearch = FolderSearch(application, viewModelScope)
    val emoji = EmojiStore(application, viewModelScope)
    private val prefs = application.getSharedPreferences("palette", Context.MODE_PRIVATE)
    private val recentDocumentGrants = RecentDocumentGrants(
        prefs, application.contentResolver, folderSearch::configuredTreeUrisForGrantCleanup
    )
    var apps by mutableStateOf<List<Command>>(emptyList()); private set
    private var loadingApps by mutableStateOf(true)
    val loading get() = loadingApps || !extensions.inventoryReady
    var notes by mutableStateOf(read("notes")); private set
    var links by mutableStateOf(read("links")); private set
    var clips by mutableStateOf(read("clips")); private set
    var files by mutableStateOf(read("files")); private set
    var pins by mutableStateOf(readPins()); private set
    var usage by mutableStateOf(readMap("usage")); private set
    var aliases by mutableStateOf(readAliases()); private set
    private var queryValue by mutableStateOf("")
    private var categoryValue by mutableStateOf("All")
    private var selectedValue by mutableIntStateOf(0)
    private var calculatorSelectionRevision = 0L
    var query: String
        get() = queryValue
        set(value) { if (value != queryValue) { calculatorSelectionRevision++; queryValue = value } }
    var category: String
        get() = categoryValue
        set(value) { if (value != categoryValue) { calculatorSelectionRevision++; categoryValue = value } }
    var selected: Int
        get() = selectedValue
        set(value) { if (value != selectedValue) { calculatorSelectionRevision++; selectedValue = value } }
    var focusEpoch by mutableIntStateOf(0)
    var extensionReturnTo: String? = null
    var extensionSetupFromRun = false
    internal var textReview by mutableStateOf<TextReviewFlow?>(null)
    private val menuOwnership = PaletteMenuOwnership { ShortcutRuntime.invalidateModifierSequences() }
    val paletteMenuOpen get() = menuOwnership.open
    fun attachPaletteMenuHost(owner: Any) { menuOwnership.attach(owner); clearModifierRecording() }
    fun paletteMenuChanged(owner: Any, open: Boolean, windowFocused: Boolean): Boolean {
        val changed = menuOwnership.update(owner, open, windowFocused)
        if(changed) clearModifierRecording()
        return changed
    }
    fun detachPaletteMenuHost(owner: Any) { menuOwnership.detach(owner) }
    var dialog by mutableStateOf<String?>(null)
    var editing by mutableStateOf<SavedItem?>(null)
    // Draft text belongs to this palette session. Keep large bodies out of the
    // Activity saved-state Bundle; recreation retains this ViewModel.
    private var retainedEditorDraft: PaletteEditorDraft? = null
    internal fun editorDraft(kind: String): PaletteEditorDraft {
        val identity = EditorIdentity(kind, editing?.id, focusEpoch)
        return retainedEditorDraft?.takeIf { it.identity == identity }
            ?: PaletteEditorDraft(identity, editing).also { retainedEditorDraft = it }
    }
    internal fun ownsEditorDraft(draft: PaletteEditorDraft) = retainedEditorDraft === draft &&
        dialog == draft.identity.kind && editing?.id == draft.identity.item && focusEpoch == draft.identity.epoch
    internal fun discardEditorDraft() { retainedEditorDraft = null }

    var actionCommand by mutableStateOf<Command?>(null)
    var message by mutableStateOf<String?>(null)
    var backupCategories by mutableStateOf(BackupCategory.entries.toSet())
    var restoreCategories by mutableStateOf<Set<BackupCategory>>(emptySet())
    var restorePolicy by mutableStateOf(RestorePolicy.MERGE)
    var backupDocument by mutableStateOf<BackupDocument?>(null); private set
    var backupError by mutableStateOf<String?>(null)
    var backupBusy by mutableStateOf(false); private set
    var pendingExport: String? = null; private set
    private val loadedShortcuts = ShortcutCodec.load(prefs.getString("shortcuts", null))
    var shortcutConfiguration by mutableStateOf(loadedShortcuts.first); private set
    var shortcutError by mutableStateOf(loadedShortcuts.second)
    var recordingTarget by mutableStateOf<String?>(null); private set
    var recordingCandidate by mutableStateOf<KeyChord?>(null)
    var recordingTap by mutableStateOf<KeyChord?>(null); private set
    private val modifierRecorder = ModifierTapDetector()
    var recordingError by mutableStateOf<String?>(null)
    var pendingShortcut by mutableStateOf<ShortcutInvocation?>(null)

    // MainActivity and SettingsActivity keep separate ViewModels over the same preferences.
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "notes") notes = read("notes")
        if (key == null || key == "links") links = read("links")
        if (key == null || key == "clips") clips = read("clips")
        if (key == null || key == "files") files = read("files")
        if (key == null || key == "pins") pins = readPins()
        if (key == null || key == "aliases") aliases = readAliases()
        if (key == null || key == "usage") usage = readMap("usage")
        if (key == null || key == "shortcuts") {
            val (configuration, error) = ShortcutCodec.load(prefs.getString("shortcuts", null))
            shortcutConfiguration = configuration
            shortcutError = error
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        retryRecentDocumentCleanup()
        viewModelScope.launch { extensions.loadInstalled() }
        refreshApps()
    }

    fun refreshApps() {
        viewModelScope.launch {
            apps = withContext(Dispatchers.IO) {
                val context = getApplication<Application>()
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentActivities(intent, 0)
                    .filter { it.activityInfo.packageName != context.packageName }
                    .distinctBy { "${it.activityInfo.packageName}/${it.activityInfo.name}" }
                    .map { Command("app:${it.activityInfo.packageName}/${it.activityInfo.name}", it.loadLabel(context.packageManager).toString(), "Open application", Kind.APP, "app", it.activityInfo.packageName, "${it.activityInfo.packageName}/${it.activityInfo.name}") }
                    .sortedBy { it.title.lowercase() }
            }
            loadingApps = false
        }
    }

    private fun catalog(): List<Command> = (listOf(
        Command("calculator", "Calculator", "Math, percentages & unit conversions", icon = "calculator", keywords = "math convert units temperature length weight", alias = "calc"),
        Command("emoji", "Search Emoji & Symbols", "Offline emoji, flags and common text symbols", icon = "keyboard", keywords = "emoji smiley face unicode characters picker symbols"),
        Command("new-note", "Create a note", "Capture a thought without breaking your flow", icon = "note", keywords = "write markdown new memo", alias = "note"),
        Command("browse-files", "Open a document", "Browse device & connected storage", icon = "folder", keywords = "file document storage picker", alias = "files"),
        Command("search-files", "Search files", "Search names and paths in folders you choose", icon = "folder", keywords = "file folder filename index find"),
        Command("search-folders", "Search folders", "Choose, refresh and manage searchable folders", icon = "folder", keywords = "directory scope grant index"),
        Command("folder-add", "Add search folder", "Choose a folder with Android's system picker", icon = "folder", keywords = "directory scope choose grant"),
        Command("new-link", "Create a quicklink", "Turn a website or search into a command", icon = "link", keywords = "url custom bookmark new", alias = "link"),
        Command("save-clip", "Save clipboard text", "Keep the current clip in your local collection", icon = "clipboard", keywords = "copy paste history save", alias = "clip"),
        Command("shortcuts", "Keyboard shortcuts", "Record global and per-command shortcuts", icon = "keyboard", keywords = "help hotkey keys ctrl space global configure bindings", alias = "keys"),
        Command("settings", "Android settings", "Open device settings", icon = "settings", keywords = "android system device configuration preferences", alias = "settings"),
        Command("app-settings", "Droidcast preferences", "Global shortcut, privacy & app information", icon = "settings", keywords = "accessibility global keyboard permission setup", alias = "prefs"),
        Command("check-updates", "Check for Updates", "Find, verify and install a newer Droidcast nightly", icon = "command", keywords = "ota upgrade download version nightly"),
        Command("backup", "Backup and restore", "Export your collections or preview an import", icon = "folder", keywords = "export import restore json data transfer"),
        Command("backup-export", "Export Droidcast backup", "Choose categories and save a JSON backup", icon = "folder", keywords = "backup save collections"),
        Command("backup-import", "Import Droidcast backup", "Preview a backup before applying changes", icon = "folder", keywords = "backup restore collections")
    ) + SystemSettings.commands() + listOf(Command("extensions", "Raycast Store", "Find, install & manage extensions", icon = "extension", keywords = "plugins addons marketplace extensions store", alias = "store")) + apps +
        extensions.installed.flatMap { extension -> extension.commands.map { cmd -> Command("ext:${extension.listing.id}/${cmd.name}", cmd.title, extension.listing.title, Kind.EXTENSION, "extension", "${extension.listing.title} ${cmd.description}", "${extension.listing.id}/${cmd.name}") } } +
        notes.map { Command(it.id, it.title, it.body.lineSequence().firstOrNull().orEmpty().ifBlank { "Empty note" }, Kind.NOTE, "note", it.body, it.body) } +
        links.map { Command(it.id, it.title, it.body, Kind.LINK, "link", it.body, it.body, it.alias) } +
        clips.map { Command(it.id, it.title, "Saved text · Enter to copy", Kind.CLIP, "clipboard", it.body, it.body) } +
        files.map { Command(it.id, it.title, "Recent document · permission granted by you", Kind.FILE, "folder", it.title, it.body) } +
        folderSearch.matches.map { it.command() } + folderSearch.retainedCommands(pins + aliases.keys)
    ).distinctBy { it.id }
    fun commands(): List<Command> = catalog().map { it.copy(alias = aliases[it.id] ?: it.alias) }
    fun configurationCommands(): List<Command> = (commands() + folderSearch.configurationCommands().map { it.copy(alias = aliases[it.id] ?: it.alias) }).distinctBy { it.id }
    fun command(id: String): Command? = commands().firstOrNull { it.id == id } ?: folderSearch.command(id)?.let { it.copy(alias = aliases[id].orEmpty()) }

    fun shortcutTitle(id: String): String = if (id == ShortcutRules.PALETTE) "Show or hide Droidcast" else command(id)?.title ?: "Unavailable command · $id"
    private fun saveShortcuts(configuration: ShortcutConfiguration) {
        ShortcutRules.validate(configuration)
        PalettePreferences.write(prefs, "shortcuts", ShortcutCodec.encode(configuration).toString())
        shortcutConfiguration = configuration; shortcutError = null
    }
    fun setShortcut(target: String, chord: KeyChord?) {
        require(!backupBusy) { "Wait for backup to finish" }
        require(target == ShortcutRules.PALETTE || target in shortcutConfiguration.bindings || command(target) != null) { "Command is unavailable" }
        if (chord != null) {
            require(ShortcutRules.error(chord) == null) { ShortcutRules.error(chord)!! }
            val owner = shortcutConfiguration.bindings.entries.firstOrNull { it.key != target && ShortcutRules.overlaps(it.value, chord) }?.key
            require(owner == null) { "Already assigned to ${shortcutTitle(owner!!)}" }
        }
        saveShortcuts(ShortcutConfiguration(if (chord == null) shortcutConfiguration.bindings - target else shortcutConfiguration.bindings + (target to chord)))
    }
    fun resetShortcuts() { require(!backupBusy); saveShortcuts(ShortcutConfiguration()) }
    fun beginShortcutRecording(target: String) {
        require(target == ShortcutRules.PALETTE || target in shortcutConfiguration.bindings || command(target) != null) { "Command is unavailable" }
        modifierRecorder.clear()
        recordingTarget = target; recordingCandidate = shortcutConfiguration.bindings[target]
        recordingTap = recordingCandidate?.takeIf { it.trigger == ShortcutTrigger.DOUBLE_TAP }; recordingError = null
        ShortcutRuntime.recordingOwner = this; dialog = "shortcut-record"
    }
    fun cancelShortcutRecording(toManager: Boolean = true) {
        modifierRecorder.clear()
        if (recordingTarget == null) return
        recordingTarget = null; recordingCandidate = null; recordingTap = null; recordingError = null
        if (ShortcutRuntime.recordingOwner === this) ShortcutRuntime.recordingOwner = null
        if (toManager) dialog = "shortcut-settings"
    }
    fun captureShortcut(chord: KeyChord) {
        val target = recordingTarget ?: return
        recordingTap = chord.takeIf { it.trigger == ShortcutTrigger.DOUBLE_TAP }
        val error = ShortcutRules.error(chord)
        val owner = shortcutConfiguration.bindings.entries.firstOrNull { it.key != target && ShortcutRules.overlaps(it.value, chord) }?.key
        recordingError = error ?: owner?.let { "Already assigned to ${shortcutTitle(it)}" }
        recordingCandidate = if (recordingError == null) chord else null
    }
    fun observeRecordingKey(input: ShortcutEngine.Input) {
        if (recordingTarget == null) { modifierRecorder.clear(); return }
        modifierRecorder.handle(input)?.let { captureShortcut(it.copy(side = ModifierSide.ANY)) }
    }
    fun clearModifierRecording() = modifierRecorder.clear()
    fun setRecordingModifierSide(side: ModifierSide) { recordingTap?.let { captureShortcut(it.copy(side = side)) } }
    fun saveRecordedShortcut() {
        val target = recordingTarget ?: return
        val chord = recordingCandidate ?: return
        runCatching { setShortcut(target, chord) }.onSuccess { cancelShortcutRecording() }.onFailure { recordingError = it.message }
    }
    override fun onCleared() {
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        clearTextReview()
        extensions.close()
        retainedEditorDraft = null
        editing = null
        if (ShortcutRuntime.recordingOwner === this) ShortcutRuntime.recordingOwner = null
        super.onCleared()
    }

    fun backupSnapshot() = BackupData(notes, links, clips, pins.filterNot { it.startsWith("files:") }.toSet(), aliases.filterKeys { !it.startsWith("files:") }, usage.filterKeys { !it.startsWith("files:") }, ShortcutConfiguration(shortcutConfiguration.bindings.filterKeys { !it.startsWith("files:") }), emoji.portableSettings())
    fun restorePlan(): RestorePlan = BackupPlanner.plan(backupSnapshot(), requireNotNull(backupDocument), restoreCategories, restorePolicy,
        catalog().filter { it.kind !in listOf(Kind.NOTE, Kind.LINK, Kind.CLIP) }.associate { it.id to it.alias } + aliases.filterKeys { it.startsWith("files:") }, shortcutConfiguration.bindings.filterKeys { it.startsWith("files:") })
    fun beginBackup() { backupError = null; backupDocument = null; dialog = "backup" }
    fun discardImport() { if (!backupBusy) { backupDocument = null; backupError = null; dialog = "backup" } }
    fun prepareExport(ready: () -> Unit) {
        if (backupBusy) return
        val snapshot = backupSnapshot(); val selected = backupCategories.toSet()
        backupBusy = true; backupError = null
        viewModelScope.launch {
            try { pendingExport = withContext(Dispatchers.IO) { BackupCodec.encode(snapshot, selected) }; ready() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { backupError = e.message ?: "Could not prepare backup" }
            finally { backupBusy = false }
        }
    }
    fun writeExport(uri: android.net.Uri?) {
        val text = pendingExport; pendingExport = null
        if (uri == null) { message = "Export cancelled"; return }
        if (text == null) { backupError = "Export expired. Please try again."; return }
        backupBusy = true; backupError = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri, "wt")) { "Cannot write this document" }.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
                message = "Backup exported"
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { backupError = e.message ?: "Could not export backup" }
            finally { backupBusy = false }
        }
    }
    fun readBackup(uri: android.net.Uri?) {
        if (uri == null) { message = "Import cancelled"; return }
        if (backupBusy) return
        // The new operation replaces prior feedback, which could cover preview controls.
        message = null
        backupBusy = true; backupError = null; backupDocument = null
        viewModelScope.launch {
            try {
                val document = withContext(Dispatchers.IO) {
                    requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)) { "Cannot read this document" }.use(BackupCodec::read)
                }
                backupDocument = document; restoreCategories = document.categories; restorePolicy = RestorePolicy.MERGE; dialog = "backup-preview"
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { backupError = e.message ?: "Could not read backup"; dialog = "backup" }
            finally { backupBusy = false }
        }
    }
    fun applyBackup() {
        if (backupBusy) return
        if (PalettePreferences.restoring) {
            backupError = "A backup is being applied in another window. Try again when it finishes."
            return
        }
        val plan = runCatching { restorePlan() }.getOrElse { backupError = it.message; return }
        // One SharedPreferences transaction: no category is published independently.
        val data = plan.data
        val restoredShortcuts = ShortcutConfiguration(data.shortcuts.bindings + shortcutConfiguration.bindings.filterKeys { it.startsWith("files:") })
        val changes = buildMap<String, Any> {
            for (category in plan.categories) when (category) {
                BackupCategory.NOTES -> put("notes", encodeItems(data.notes))
                BackupCategory.LINKS -> put("links", encodeItems(data.links))
                BackupCategory.CLIPS -> put("clips", encodeItems(data.clips))
                BackupCategory.PREFERENCES -> {
                    put("pins", data.pins + pins.filter { it.startsWith("files:") })
                    put("aliases", JSONObject(data.aliases + aliases.filterKeys { it.startsWith("files:") }).toString())
                }
                BackupCategory.LEARNING -> put("usage", JSONObject(data.usage + usage.filterKeys { it.startsWith("files:") }).toString())
                BackupCategory.SHORTCUTS -> put("shortcuts", ShortcutCodec.encode(restoredShortcuts).toString())
                BackupCategory.EMOJI -> {
                    put(EmojiStore.FAVORITES_KEY, JSONArray(data.emoji.favorites).toString())
                    put(EmojiStore.GRID_KEY, data.emoji.gridColumns ?: 0)
                }
            }
        }
        backupBusy = true; backupError = null
        viewModelScope.launch {
            try {
                if (!PalettePreferences.restore(prefs, changes)) {
                    backupError = "Could not save the backup. Check available storage and try again."
                    return@launch
                }
                backupDocument = null; dialog = "backup"; selected = 0
                message = plan.completionMessage()
            } finally { backupBusy = false }
        }
    }

    fun results(): List<Command> {
        val prefixKind = when (query.firstOrNull()) { '@' -> Kind.APP; '#' -> Kind.NOTE; ':' -> Kind.LINK; '>' -> Kind.COMMAND; else -> null }
        val q = if (prefixKind != null) query.drop(1).trimStart() else query
        val filter = prefixKind ?: when (category) { "Apps" -> Kind.APP; "Notes" -> Kind.NOTE; "Quicklinks" -> Kind.LINK; "Clips" -> Kind.CLIP; "Files" -> Kind.FILE; "Extensions" -> Kind.EXTENSION; else -> null }
        val all = commands().filter { filter == null || it.kind == filter }.filter { category != "Pinned" || it.id in pins }
            .filter { category != "Files" || folderSearch.type == FileSearchType.ALL || it.mimeType?.let(folderSearch.type::acceptsMime) == true }
        val result = CommandSearch.find(all, q, pins, usage).toMutableList()
        if (filter == null && category != "Pinned") {
            val calculation = Calculator.evaluateSearch(q)
            calculation?.let { result.add(0, Command("result", it.result, it.expression, Kind.CALC, "calculator", payload = it.result)) }
            val word = q.substringBefore(' ')
            all.firstOrNull { it.kind == Kind.LINK && it.alias.isNotBlank() && it.alias.equals(word, true) && q.contains(' ') }?.let {
                result.removeAll { c -> c.id == it.id }
                result.add(0, it.copy(subtitle = "Search for ${q.substringAfter(' ')}", payload = it.payload.replace("{query}", android.net.Uri.encode(q.substringAfter(' ')))))
            }
            if (q.isNotBlank() && calculation == null && !q.startsWith('=')) result.add(Command("web", "Search the web", q, icon = "search", payload = "https://www.google.com/search?q=${android.net.Uri.encode(q)}"))
        }
        return if(q.isBlank()) result.sortedWith(compareBy<Command> { if(it.id in pins) 0 else 1 }.thenBy { if(it.id in pins) -1 else it.kind.ordinal }) else result
    }

    fun search(value: String) { query = value; selected = 0; syncFileSearch() }
    internal fun calculatorSelection(command: Command?) = CalculatorSelection(query, category, selected,
        calculatorSelectionRevision, focusEpoch, dialog, command)
    internal fun currentCalculatorSelection(): CalculatorSelection {
        val current = results()
        return calculatorSelection(current.getOrNull(selected.coerceIn(0, current.lastIndex.coerceAtLeast(0))))
    }
    fun syncFileSearch() { folderSearch.search(query, category == "Files" || (folderSearch.includeInLauncher && query.isNotBlank() && category in setOf("All", "Pinned"))) }
    fun openExtension(extension: InstalledExtension, command: ExtensionCommand) {
        TextReviewOwnership.revoke()
        clearTextReview()
        extensionReturnTo = when (dialog) { "extensions" -> "extensions"; "extension-run", "extension-setup" -> extensionReturnTo; else -> null }
        extensionSetupFromRun = false
        extensions.launch(extension, command)
        val prefs = extensions.preferences(extension, command)
        val needsSetup = command.definition.optJSONArray("arguments").objects().isNotEmpty() || extension.fields(command).any { it.optBoolean("required") && (!prefs.has(it.getString("name")) || prefs.optString(it.getString("name")).isBlank()) }
        dialog = if (needsSetup) "extension-setup" else "extension-run"
    }
    fun installExtension(listing: StoreExtension) { viewModelScope.launch {
        try { extensions.install(listing); message = "${listing.title} installed" }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Throwable) { extensions.error = e.message ?: "Installation failed" }
    } }
    fun uninstallExtension(extension: InstalledExtension) { viewModelScope.launch {
        try { extensions.uninstall(extension) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Throwable) { extensions.error = e.message ?: "Uninstallation failed" }
    } }
    fun filter(value: String) { category = value; folderSearch.filterType(FileSearchType.ALL); search(""); focusEpoch++ }
    fun reset() { clearTextReview(); extensions.close(); if (backupBusy) return; discardEditorDraft(); cancelShortcutRecording(toManager = false); category = "All"; folderSearch.filterType(FileSearchType.ALL); search(""); dialog = null; actionCommand = null; focusEpoch++ }
    fun used(id: String) { usage = usage + (id to ((usage[id] ?: 0).coerceAtMost(Int.MAX_VALUE - 1) + 1)); PalettePreferences.write(prefs, "usage", JSONObject(usage).toString()) }
    fun togglePin(id: String) { pins = if (id in pins) pins - id else pins + id; PalettePreferences.write(prefs, "pins", pins) }
    fun setAlias(id: String, value: String) {
        val alias = value.trim().lowercase()
        require(alias.isEmpty() || Regex("[a-z][a-z0-9_-]{0,23}").matches(alias)) { "Use one word: letters, numbers, _ or -" }
        require(alias.isEmpty() || (commands().none { it.id != id && it.alias == alias } && aliases.none { it.key != id && it.value == alias })) { "That alias is already in use" }
        aliases = aliases + (id to alias)
        PalettePreferences.write(prefs, "aliases", JSONObject(aliases).toString())
    }

    fun save(kind: String, title: String, body: String, alias: String = "", id: String? = null): String {
        val item = SavedItem(id ?: "$kind:${UUID.randomUUID()}", title.trim(), body, alias.trim())
        require(item.title.isNotEmpty()) { "Give it a name first" }
        if (kind == "links") {
            val uri = android.net.Uri.parse(body.replace("{query}", "test"))
            require(uri.scheme in listOf("https", "http") && !uri.host.isNullOrBlank()) { "Enter a complete https:// or http:// URL" }
            require(alias.isBlank() || Regex("[a-z][a-z0-9_-]{0,23}").matches(alias)) { "Alias must be one lowercase word" }
            require(alias.isBlank() || commands().none { it.id != id && it.alias == alias }) { "That alias is already in use" }
            aliases = aliases - item.id
            PalettePreferences.write(prefs, "aliases", JSONObject(aliases).toString())
        }
        val old = when (kind) { "notes" -> notes; "links" -> links; "clips" -> clips; else -> read("files") }
        val next = (listOf(item) + old.filter { it.id != item.id }).let { if (kind == "clips") it.take(100) else it }
        write(kind, next)
        when (kind) { "notes" -> notes = next; "links" -> links = next; "clips" -> clips = next; else -> files = next }
        return item.id
    }
    fun delete(id: String) {
        listOf("notes", "links", "clips", "files").forEach { key ->
            val old = read(key)
            val next = old.filter { it.id != id }
            if (next.size == old.size) return@forEach
            write(key, next)
            if (key == "files") {
                message = "Removed from recent files. Your original file was kept."
                retryRecentDocumentCleanup()
            }
            when (key) { "notes" -> notes = next; "links" -> links = next; "clips" -> clips = next; else -> files = next }
        }
        pins = pins - id; PalettePreferences.write(prefs, "pins", pins)
        aliases = aliases - id; PalettePreferences.write(prefs, "aliases", JSONObject(aliases).toString())
        if (id in shortcutConfiguration.bindings) saveShortcuts(ShortcutConfiguration(shortcutConfiguration.bindings - id))
        selected = 0
    }
    internal fun acquireRecentDocumentGrant(uri: android.net.Uri) {
        viewModelScope.launch {
            try { recentDocumentGrants.acquire(uri) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { message = "Document access could not be saved. Choose the document again to retry." }
        }
    }
    private fun retryRecentDocumentCleanup() {
        viewModelScope.launch {
            try { recentDocumentGrants.releaseUnused() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { message = "Document permission cleanup is pending. Reopen Droidcast to retry." }
        }
    }
    private fun read(key: String): List<SavedItem> = runCatching {
        val array = JSONArray(prefs.getString(key, "[]"))
        (0 until array.length()).map { array.getJSONObject(it).let { o -> SavedItem(o.getString("id"), o.getString("title"), o.getString("body"), o.optString("alias")) } }
    }.getOrDefault(emptyList())
    private fun write(key: String, items: List<SavedItem>) {
        if (key == "files") RecentDocumentGrants.referencesChanged()
        PalettePreferences.write(prefs, key, encodeItems(items))
    }
    private fun encodeItems(items: List<SavedItem>): String {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("id", it.id).put("title", it.title).put("body", it.body).put("alias", it.alias)) }
        return array.toString()
    }
    private fun readPins(): Set<String> =
        prefs.getStringSet("pins", setOf("calculator", "new-note", "browse-files", "new-link"))!!.toSet()

    private fun readMap(key: String): Map<String, Int> = runCatching { val o = JSONObject(prefs.getString(key, "{}")!!); o.keys().asSequence().associateWith { o.getInt(it) } }.getOrDefault(emptyMap())
    private fun readAliases(): Map<String, String> = runCatching { val o = JSONObject(prefs.getString("aliases", "{}")!!); o.keys().asSequence().associateWith { o.getString(it) } }.getOrDefault(emptyMap())
}
