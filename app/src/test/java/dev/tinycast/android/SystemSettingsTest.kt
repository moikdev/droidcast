package dev.tinycast.android

import org.junit.Assert.*
import org.junit.Test

class SystemSettingsTest {
    @Test fun searchFindsSpecificDestinations() {
        val commands = SystemSettings.commands() + Command("settings", "Android settings", "", keywords = "android system device configuration preferences", alias = "settings")
        listOf("wifi" to "system:wifi", "bluetooth" to "system:bluetooth", "brightness" to "system:display", "volume" to "system:sound",
            "battery" to "system:battery", "security" to "system:security", "safety center" to "system:security").forEach { (query, id) ->
            assertEquals(query, id, CommandSearch.find(commands, query, emptySet(), emptyMap()).first().id)
        }
        assertEquals("settings", CommandSearch.find(commands, "settings", emptySet(), emptyMap()).first().id)
    }

    @Test fun securityUsesTheDashboardWhenThePlatformSupportsIt() {
        assertEquals("android.settings.SECURITY_SETTINGS", SystemSettings.securityAction(26))
        assertEquals("android.settings.SECURITY_SETTINGS", SystemSettings.securityAction(32))
        assertEquals("android.intent.action.SAFETY_CENTER", SystemSettings.securityAction(33))
        assertEquals("android.intent.action.SAFETY_CENTER", SystemSettings.securityAction(36))
        assertEquals("android.intent.action.POWER_USAGE_SUMMARY", SystemSettings.find("system:battery")!!.action)
    }

    @Test fun catalogHasNoDuplicateOrReservedIdsOrNewAliases() {
        val commands = SystemSettings.commands()
        assertEquals(commands.size, commands.map { it.id }.toSet().size)
        assertTrue(commands.all { it.id.startsWith("system:") && it.kind == Kind.COMMAND && it.alias.isEmpty() })
        assertTrue(commands.all { it.title.isNotBlank() && it.subtitle.isNotBlank() })
        assertNull(SystemSettings.find("settings"))
    }

    @Test fun onlyAppSpecificTargetsRequirePackageArguments() {
        assertEquals(setOf("system:app-info", "system:notifications"), SystemSettings.destinations.filter { it.packageArgument != SettingsPackageArgument.NONE }.map { it.id }.toSet())
        assertEquals(SettingsPackageArgument.DATA, SystemSettings.find("system:app-info")!!.packageArgument)
        assertEquals(SettingsPackageArgument.EXTRA, SystemSettings.find("system:notifications")!!.packageArgument)
    }
}
