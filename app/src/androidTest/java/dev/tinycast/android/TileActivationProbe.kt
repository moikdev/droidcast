package dev.tinycast.android

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Test-only snapshots. Keep system dumps, window tokens, and unrelated tile names out of status output. */
internal class TileActivationProbe(private val context: Context, private val device: UiDevice) {
    val component = ComponentName(context, PaletteTileService::class.java).flattenToShortString()
    private val tileComponent = ComponentName(context, PaletteTileService::class.java)
    private val tileSpec = "custom($component)"
    private val started = SystemClock.elapsedRealtime()
    private val phases = JSONArray()
    private val args = InstrumentationRegistry.getArguments()
    private val folder = args.getString("tileEvidence")?.let { id ->
        require(id.matches(Regex("[0-9a-f]{32}")))
        require(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == "Tinycast_Tablet_API_36")
        File(context.filesDir, "tile-activation-$id").also {
            require(it.isDirectory && it.list()?.isEmpty() == true) { "Expected an empty owned capture directory" }
        }
    }
    val mode = args.getString("tileAction") ?: "command"
    private val failureOnly = args.getString("tileSnapshots")?.let {
        require(folder != null && it == "failure")
        true
    } ?: false

    init {
        require(mode == "command" || (folder != null && mode in setOf("pointer", "omit")))
    }

    fun tileInstalled(): Boolean {
        val list = device.executeShellCommand("settings get secure sysui_qs_tiles").trim()
        check(list.isEmpty() || list == "null" || list.split(',').all {
            it.isNotBlank() && it.none(Char::isWhitespace)
        }) { "Could not read the configured tile list" }
        return list.split(',').any { spec ->
            spec.startsWith("custom(") && spec.endsWith(")") &&
                ComponentName.unflattenFromString(spec.removePrefix("custom(").dropLast(1)) == tileComponent
        }
    }

    private fun route(line: String?): String = when {
        line == null -> "unreported"
        listOf(ComponentName(context, MainActivity::class.java).flattenToShortString(),
            ComponentName(context, MainActivity::class.java).flattenToString()).any { line.contains(it) } -> "palette"
        "NotificationShade" in line -> "shade"
        "com.android.settings/" in line || "com.android.settings.intelligence/" in line -> "android-settings"
        else -> "other"
    }

    fun focusedWindow(): String = route(device.executeShellCommand("dumpsys window").lineSequence()
        .firstOrNull { it.trimStart().startsWith("mCurrentFocus=") })

    fun mark(phase: String) {
        phases.put(JSONObject().put("phase", phase).put("elapsed_ms", SystemClock.elapsedRealtime() - started))
    }

    fun snapshot(phase: String, failure: Boolean = false) {
        if (!failure && (folder == null || failureOnly)) return
        val from = SystemClock.elapsedRealtime() - started
        val ui = device.executeShellCommand("dumpsys activity service com.android.systemui/.SystemUIService")
        val tile = ui.lineSequence().firstOrNull { "CustomTile:" in it && tileSpec in it }
        val activity = device.executeShellCommand("dumpsys activity activities").lineSequence()
            .firstOrNull { it.trimStart().startsWith("topResumedActivity=") }
        val service = device.executeShellCommand("dumpsys activity services $component")
        val state = JSONObject().put("phase", phase).put("start_ms", from)
            .put("focused_window", focusedWindow()).put("resumed_activity", route(activity))
            .put("tile_in_settings", tileInstalled())
            .put("sysui_tile_state", tile?.let { Regex(",state=(\\d+),").find(it)?.groupValues?.get(1) } ?: JSONObject.NULL)
            .put("qs_expanded", Regex("mQsExpanded[:=] ?(true|false)").find(ui)?.groupValues?.get(1) ?: JSONObject.NULL)
            .put("service_record_present", "ServiceRecord{" in service)
            .put("service_process_attached", Regex("app=ProcessRecord\\{").containsMatchIn(service))
            .put("tile_node_visible", device.hasObject(By.pkg("com.android.systemui").text("Droidcast search")))
            .put("palette_editor_visible", device.hasObject(By.pkg(context.packageName).clazz("android.widget.EditText")))
            .put("guest_load_1m", device.executeShellCommand("cat /proc/loadavg").substringBefore(' ').toDoubleOrNull() ?: JSONObject.NULL)
            .put("observed_ms", SystemClock.elapsedRealtime() - started)
        phases.put(state)
        if (failure) InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("tile_activation_state", phases.toString())
        })
        if (folder != null) {
            File(folder, "phases.json").writeText(phases.toString(2))
            device.dumpWindowHierarchy(File(folder, "$phase.xml"))
            check(device.takeScreenshot(File(folder, "$phase.png")))
        }
    }

    fun awaitMembership(installed: Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (tileInstalled() != installed) {
            check(SystemClock.elapsedRealtime() < deadline) { "Tile membership did not settle" }
            SystemClock.sleep(50)
        }
    }

    val capturing get() = folder != null && !failureOnly
}
