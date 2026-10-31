package dev.tinycast.android

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import org.json.JSONObject
import java.lang.ref.WeakReference

object ShortcutCodec {
    private fun keys(value: JSONObject, expected: Set<String>) = require(value.keys().asSequence().toSet() == expected) { "Invalid keyboard shortcut fields" }
    fun decode(value: JSONObject): ShortcutConfiguration {
        keys(value, setOf("version", "bindings"))
        require(value.get("version") is Int && value.getInt("version") in 1..2) { "Unsupported keyboard shortcut version" }
        val version = value.getInt("version")
        val entries = value.getJSONObject("bindings")
        require(entries.length() <= ShortcutRules.MAX_BINDINGS) { "Too many keyboard shortcuts" }
        val configuration = ShortcutConfiguration(entries.keys().asSequence().associateWith { id ->
            val chord = entries.getJSONObject(id)
            keys(chord, if (version == 1) setOf("keyCode", "modifiers") else setOf("keyCode", "modifiers", "trigger", "side"))
            require(chord.get("keyCode") is Int && chord.get("modifiers") is Int) { "Invalid keyboard shortcut" }
            if (version == 1) KeyChord(chord.getInt("keyCode"), chord.getInt("modifiers")) else {
                require(chord.get("trigger") is String && chord.get("side") is String) { "Invalid modifier shortcut" }
                KeyChord(chord.getInt("keyCode"), chord.getInt("modifiers"), ShortcutTrigger.valueOf(chord.getString("trigger")), ModifierSide.valueOf(chord.getString("side")))
            }
        })
        ShortcutRules.validate(configuration)
        return configuration
    }
    fun encode(configuration: ShortcutConfiguration): JSONObject {
        ShortcutRules.validate(configuration)
        val version = if (configuration.bindings.values.any { it.trigger != ShortcutTrigger.CHORD }) 2 else 1
        val entries = JSONObject()
        configuration.bindings.toSortedMap().forEach { (id, chord) ->
            val item = JSONObject().put("keyCode", chord.keyCode).put("modifiers", chord.modifiers)
            if (version == 2) item.put("trigger", chord.trigger.name).put("side", chord.side.name)
            entries.put(id, item)
        }
        return JSONObject().put("version", version).put("bindings", entries)
    }
    fun load(text: String?): Pair<ShortcutConfiguration, String?> {
        if (text == null) return ShortcutConfiguration() to null
        return runCatching { decode(JSONObject(text)) }.fold({ it to null }, { ShortcutConfiguration(emptyMap()) to "Saved shortcuts could not be loaded. Reset them in Keyboard shortcuts." })
    }
}

fun KeyEvent.asChord(): KeyChord {
    val normalized = KeyEvent.normalizeMetaState(metaState)
    return KeyChord(keyCode, (if (normalized and KeyEvent.META_CTRL_ON != 0) ShortcutRules.CTRL else 0) or
        (if (normalized and KeyEvent.META_ALT_ON != 0) ShortcutRules.ALT else 0) or (if (normalized and KeyEvent.META_SHIFT_ON != 0) ShortcutRules.SHIFT else 0) or
        (if (normalized and KeyEvent.META_META_ON != 0) ShortcutRules.META else 0))
}
fun KeyEvent.asShortcutInput() = ShortcutEngine.Input(action == KeyEvent.ACTION_DOWN, keyCode, asChord().modifiers, repeatCount, deviceId,
    eventTime, downTime, android.os.SystemClock.uptimeMillis(), isCanceled)
fun KeyChord.nativeModifiers() = (if (modifiers and ShortcutRules.CTRL != 0) KeyEvent.META_CTRL_ON else 0) or
    (if (modifiers and ShortcutRules.ALT != 0) KeyEvent.META_ALT_ON else 0) or (if (modifiers and ShortcutRules.SHIFT != 0) KeyEvent.META_SHIFT_ON else 0) or
    (if (modifiers and ShortcutRules.META != 0) KeyEvent.META_META_ON else 0)

fun KeyChord.label(deviceId: Int? = null): String {
    if (trigger == ShortcutTrigger.DOUBLE_TAP) {
        val modifier = when (ShortcutRules.modifierFamily(keyCode)) {
            ShortcutRules.CTRL -> "Ctrl"; ShortcutRules.ALT -> "Alt"; ShortcutRules.SHIFT -> "Shift"; ShortcutRules.META -> "Meta"
            else -> "Unsupported modifier"
        }
        return "Double " + when (side) { ModifierSide.ANY -> ""; ModifierSide.LEFT -> "Left "; ModifierSide.RIGHT -> "Right " } + modifier
    }
    val special = mapOf(KeyEvent.KEYCODE_SPACE to "Space", KeyEvent.KEYCODE_TAB to "Tab", KeyEvent.KEYCODE_ENTER to "Enter", KeyEvent.KEYCODE_NUMPAD_ENTER to "Keypad Enter",
        KeyEvent.KEYCODE_DEL to "Backspace", KeyEvent.KEYCODE_FORWARD_DEL to "Delete", KeyEvent.KEYCODE_MOVE_HOME to "Home", KeyEvent.KEYCODE_MOVE_END to "End",
        KeyEvent.KEYCODE_PAGE_UP to "Page Up", KeyEvent.KEYCODE_PAGE_DOWN to "Page Down", KeyEvent.KEYCODE_DPAD_UP to "↑", KeyEvent.KEYCODE_DPAD_DOWN to "↓",
        KeyEvent.KEYCODE_DPAD_LEFT to "←", KeyEvent.KEYCODE_DPAD_RIGHT to "→")
    val keyboard = deviceId ?: InputDevice.getDeviceIds().asSequence().mapNotNull { InputDevice.getDevice(it) }
        .filter { it.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC }.sortedBy { if (android.os.Build.VERSION.SDK_INT >= 29) !it.isExternal else false }.firstOrNull()?.id ?: KeyCharacterMap.VIRTUAL_KEYBOARD
    val character = runCatching { KeyCharacterMap.load(keyboard).getDisplayLabel(keyCode) }.getOrDefault('\u0000')
    val key = special[keyCode] ?: if (character != '\u0000' && !character.isWhitespace()) character.uppercaseChar().toString() else KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
    return buildList { if (modifiers and ShortcutRules.CTRL != 0) add("Ctrl"); if (modifiers and ShortcutRules.ALT != 0) add("Alt"); if (modifiers and ShortcutRules.SHIFT != 0) add("Shift"); if (modifiers and ShortcutRules.META != 0) add("Meta"); add(key) }.joinToString(" + ")
}

object ShortcutRuntime {
    const val ACTION = "dev.droidcast.RUN_SHORTCUT"
    const val TOKEN = "dev.droidcast.shortcut_token"
    val requests = ShortcutInvocationQueue { android.os.SystemClock.elapsedRealtime() }
    @Volatile var recordingEpoch = 0L; private set
    // Same-window menus are input-ownership boundaries even without a window event.
    fun invalidateModifierSequences() { recordingEpoch++ }
    @Volatile var recordingOwner: Any? = null
        set(value) {
            if (field !== value) { recordingEpoch++; field = value }
        }
    val keyboardConnection = KeyboardServiceConnection()
    private var foreground = WeakReference<MainActivity>(null)
    private var settings = WeakReference<SettingsActivity>(null)
    private var settingsWorkspace = WeakReference<SettingsActivity>(null)
    fun settingsCreated(activity: SettingsActivity) { settingsWorkspace = WeakReference(activity) }
    fun settingsDestroyed(activity: SettingsActivity) { if (settingsWorkspace.get() === activity) settingsWorkspace.clear() }
    // Only one preferences snapshot may remain editable when the palette opens again.
    fun paletteOpening(): Boolean {
        val workspace = settingsWorkspace.get()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return true
        if (workspace.store.backupBusy) {
            Toast.makeText(workspace, "Finish the backup operation first.", Toast.LENGTH_SHORT).show()
            return false
        }
        workspace.store.cancelShortcutRecording(false)
        workspace.finish()
        return true
    }
    fun settingsResumed(activity: SettingsActivity) { settings = WeakReference(activity) }
    fun settingsPaused(activity: SettingsActivity) { if (settings.get() === activity) settings.clear() }
    fun passesSettingsNavigation(event: KeyEvent): Boolean {
        val chord = event.asChord()
        val commandModifier = chord.modifiers in setOf(ShortcutRules.CTRL, ShortcutRules.META, ShortcutRules.CTRL or ShortcutRules.SHIFT, ShortcutRules.META or ShortcutRules.SHIFT)
        if (!commandModifier || (event.keyCode != KeyEvent.KEYCODE_COMMA && (event.keyCode != KeyEvent.KEYCODE_F || event.isShiftPressed))) return false
        // An explicitly armed check owns only its configured navigation chord.
        // Normal Settings navigation regains priority immediately after receipt.
        val workspace = settings.get()?.takeUnless { it.isFinishing || it.isDestroyed }
        if (workspace?.isShortcutProbeArmed(chord) == true) return false
        val settingsVisible = settings.get()?.let { !it.isFinishing && !it.isDestroyed } == true
        val paletteVisible = foreground.get()?.let { !it.isFinishing && !it.isDestroyed } == true
        return (event.keyCode == KeyEvent.KEYCODE_COMMA && (settingsVisible || paletteVisible)) ||
            (event.keyCode == KeyEvent.KEYCODE_F && settingsVisible && !event.isShiftPressed)
    }
    fun resumed(activity: MainActivity) { foreground = WeakReference(activity) }
    fun paused(activity: MainActivity) { if (foreground.get() === activity) foreground.clear() }
    fun menuOwnsInput(): Boolean = foreground.get()?.let {
        !it.isFinishing && !it.isDestroyed && it.window.decorView.hasWindowFocus() && it.store.paletteMenuOpen
    } == true || settings.get()?.let {
        !it.isFinishing && !it.isDestroyed && it.window.decorView.hasWindowFocus() && it.store.paletteMenuOpen
    } == true
    fun dispatch(context: Context, target: String, chord: KeyChord) {
        if (settings.get()?.receiveShortcutProbe(target, chord) == true) return
        val token = requests.issue(target, chord)
        val activity = foreground.get()?.takeUnless { it.isFinishing || it.isDestroyed }
        if (activity != null) activity.acceptShortcut(token, toggleExisting = true)
        else try { context.startActivity(Intent(context, MainActivity::class.java).setAction(ACTION).putExtra(TOKEN, token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
        catch (_: Exception) { requests.consume(token); Toast.makeText(context, "Could not open Droidcast", Toast.LENGTH_SHORT).show() }
    }
}

class PaletteShortcutService : AccessibilityService(), InputManager.InputDeviceListener {
    private val engine = ShortcutEngine()
    private val modifierShortcuts = ModifierShortcutObserver()
    private val connectionOwner = Any()
    private val preferences by lazy { getSharedPreferences("palette", Context.MODE_PRIVATE) }
    private var configuration = ShortcutConfiguration()
    private val changed = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key -> if (key == null || key == "shortcuts") {
        modifierShortcuts.clear()
        configuration = ShortcutCodec.load(prefs.getString("shortcuts", null)).first
    } }
    override fun onCreate() {
        super.onCreate()
        configuration = ShortcutCodec.load(preferences.getString("shortcuts", null)).first
        preferences.registerOnSharedPreferenceChangeListener(changed)
        getSystemService(InputManager::class.java).registerInputDeviceListener(this, null)
    }
    override fun onServiceConnected() {
        modifierShortcuts.clear()
        configuration = ShortcutCodec.load(preferences.getString("shortcuts", null)).first
        ShortcutRuntime.keyboardConnection.connect(connectionOwner)
    }
    override fun onUnbind(intent: Intent?): Boolean {
        ShortcutRuntime.keyboardConnection.disconnect(connectionOwner); engine.clear(); modifierShortcuts.clear()
        return super.onUnbind(intent)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Only type, window class and event time are used; never text or content.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) modifierShortcuts.windowChanged(event.className, event.eventTime)
    }
    override fun onInterrupt() { modifierShortcuts.clear() }
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action !in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) return false
        val input = event.asShortcutInput()
        val connection = ShortcutRuntime.keyboardConnection
        val menuOpen = ShortcutRuntime.menuOwnsInput()
        val tap = modifierShortcuts.handle(input, configuration.byChord,
            connection.isOwner(connectionOwner) && ShortcutRuntime.recordingOwner == null && !menuOpen, connection.epoch, ShortcutRuntime.recordingEpoch)
        tap?.let { (target, binding) -> ShortcutRuntime.dispatch(this, target, binding) }
        val result = engine.handle(input, configuration.byChord, menuOpen || ShortcutRuntime.recordingOwner != null || ShortcutRuntime.passesSettingsNavigation(event))
        if (result.target != null) ShortcutRuntime.dispatch(this, result.target, result.chord!!)
        return result.consumed
    }
    override fun onInputDeviceAdded(deviceId: Int) = Unit
    override fun onInputDeviceChanged(deviceId: Int) { modifierShortcuts.clear() }
    override fun onInputDeviceRemoved(deviceId: Int) { engine.clearDevice(deviceId); modifierShortcuts.clear() }
    override fun onDestroy() {
        ShortcutRuntime.keyboardConnection.disconnect(connectionOwner)
        preferences.unregisterOnSharedPreferenceChangeListener(changed)
        getSystemService(InputManager::class.java).unregisterInputDeviceListener(this)
        engine.clear(); modifierShortcuts.clear(); super.onDestroy()
    }
}
