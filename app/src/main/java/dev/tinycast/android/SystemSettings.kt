package dev.tinycast.android

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

enum class SettingsPackageArgument { NONE, DATA, EXTRA }

data class SettingsDestination(
    val id: String,
    val title: String,
    val description: String,
    val action: String,
    val keywords: String,
    val packageArgument: SettingsPackageArgument = SettingsPackageArgument.NONE
) {
    fun command() = Command(id, title, description, icon = "settings", keywords = keywords)

    fun intent(packageName: String) = Intent(action).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP).apply {
        when (packageArgument) {
            SettingsPackageArgument.DATA -> data = Uri.fromParts("package", packageName, null)
            SettingsPackageArgument.EXTRA -> putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            SettingsPackageArgument.NONE -> Unit
        }
    }
}

object SystemSettings {
    internal fun securityAction(sdk: Int): String =
        if (sdk >= Build.VERSION_CODES.TIRAMISU) Intent.ACTION_SAFETY_CENTER else Settings.ACTION_SECURITY_SETTINGS

    val destinations = listOf(
        SettingsDestination("system:wifi", "Wi-Fi settings", "Open Android Wi-Fi controls", Settings.ACTION_WIFI_SETTINGS, "wifi wi fi wireless internet network"),
        SettingsDestination("system:bluetooth", "Bluetooth settings", "Open Android Bluetooth controls", Settings.ACTION_BLUETOOTH_SETTINGS, "bluetooth bt pairing connected devices"),
        SettingsDestination("system:display", "Display settings", "Open brightness and display controls", Settings.ACTION_DISPLAY_SETTINGS, "display screen brightness dark theme"),
        SettingsDestination("system:sound", "Sound settings", "Open sound and volume controls", Settings.ACTION_SOUND_SETTINGS, "sound volume audio ringtone vibration"),
        SettingsDestination("system:notifications", "Droidcast notifications", "Manage notifications for Droidcast", Settings.ACTION_APP_NOTIFICATION_SETTINGS, "notifications alerts permissions", SettingsPackageArgument.EXTRA),
        SettingsDestination("system:apps", "Apps settings", "Manage installed Android apps", Settings.ACTION_APPLICATION_SETTINGS, "apps applications installed manage"),
        SettingsDestination("system:app-info", "Droidcast app info", "Open permissions and storage for Droidcast", Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "app info permissions storage defaults droidcast", SettingsPackageArgument.DATA),
        SettingsDestination("system:storage", "Storage settings", "Open device storage management", Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "storage disk space memory files"),
        SettingsDestination("system:accessibility", "Accessibility settings", "Open Android accessibility controls", Settings.ACTION_ACCESSIBILITY_SETTINGS, "accessibility keyboard service talkback"),
        SettingsDestination("system:date", "Date & time settings", "Open date, time and time-zone controls", Settings.ACTION_DATE_SETTINGS, "date time clock timezone zone"),
        SettingsDestination("system:battery", "Battery settings", "View battery usage and power controls", Intent.ACTION_POWER_USAGE_SUMMARY, "battery power charge usage saver"),
        SettingsDestination("system:security", "Security settings", "Review device security and screen lock", securityAction(Build.VERSION.SDK_INT), "security privacy device unlock screen lock safety center"),
        SettingsDestination("system:language", "Language settings", "Open device language controls", Settings.ACTION_LOCALE_SETTINGS, "language locale translation region")
    )

    fun commands() = destinations.map(SettingsDestination::command)
    fun find(id: String) = destinations.firstOrNull { it.id == id }
}
