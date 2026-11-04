package dev.tinycast.android

import dev.tinycast.android.extensions.ExtensionCommandLaunch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExtensionCommandLaunchTest {
    private fun options() = JSONObject().put("name", "target").put("type", "userInitiated")
    private fun request(options: JSONObject = options()) = ExtensionCommandLaunch.parse(JSONArray().put(options).toString())
    private val noArguments get() = JSONObject()
    private fun rejected(block: () -> Unit) = assertThrows(IllegalArgumentException::class.java) { block() }

    @Test fun foregroundContextAndArgumentsArriveAsSeparateCopiedLaunchProperties() {
        val context = JSONObject().put("text", "café é 日本 🌱\n\"owned\"")
            .put("nested", JSONArray().put(7).put(true).put(JSONObject.NULL))
        val definition = JSONObject().put("arguments", JSONArray().put(JSONObject().put("name", "topic").put("required", true)))
        val launch = request(options().put("arguments", JSONObject().put("topic", "owned"))
            .put("context", context).put("fallbackText", "owned fallback"))
        context.put("text", "later mutation")
        val first = JSONObject(launch.launchProperties(definition))
        assertEquals("userInitiated", first.getString("launchType"))
        assertEquals("owned", first.getJSONObject("arguments").getString("topic"))
        assertEquals("owned fallback", first.getString("fallbackText"))
        assertEquals("café é 日本 🌱\n\"owned\"", first.getJSONObject("launchContext").getString("text"))
        first.getJSONObject("launchContext").put("text", "consumer mutation")
        assertEquals("café é 日本 🌱\n\"owned\"", JSONObject(launch.launchProperties(definition)).getJSONObject("launchContext").getString("text"))
        assertFalse(first.has("selectedText"))
    }

    @Test fun ordinaryEmptyRequestDoesNotInventContextOrFallback() {
        val properties = JSONObject(request().launchProperties(noArguments))
        assertEquals(0, properties.getJSONObject("arguments").length())
        assertFalse(properties.has("launchContext")); assertFalse(properties.has("fallbackText"))
    }

    @Test fun crossExtensionRoutingAndBackgroundLaunchesAreRejected() {
        rejected { request(options().put("extensionName", "another")) }
        rejected { request(options().put("ownerOrAuthorName", "another")) }
        rejected { request(options().put("type", "background")) }
        rejected { request(JSONObject().put("name", "target")) }
    }

    @Test fun unsafeNamesAndUnknownOptionsNeverBecomeLaunches() {
        for (name in listOf("", "../outside", "folder/target", "folder\\target", "x".repeat(129))) {
            rejected { request(options().put("name", name)) }
        }
        rejected { request(options().put("unexpected", true)) }
        rejected { ExtensionCommandLaunch.parse("[]") }
        rejected { ExtensionCommandLaunch.parse("[{},{}]") }
        rejected { ExtensionCommandLaunch.parse("[null]") }
    }

    @Test fun undeclaredNonstringAndMissingRequiredArgumentsAreRejected() {
        val definition = JSONObject("""{"arguments":[{"name":"topic","required":true}]}""")
        rejected { request().argumentsFor(definition) }
        rejected { request(options().put("arguments", JSONObject().put("topic", " "))).argumentsFor(definition) }
        rejected { request(options().put("arguments", JSONObject().put("topic", 7))) }
        rejected { request(options().put("arguments", JSONObject().put("other", "owned"))).argumentsFor(definition) }
        rejected { request(options().put("arguments", JSONArray())) }
        assertEquals(mapOf("topic" to "owned"), request(options().put("arguments", JSONObject().put("topic", "owned"))).argumentsFor(definition))
    }

    @Test fun argumentConsumersCannotChangeThePreparedValues() {
        val definition = JSONObject("""{"arguments":[{"name":"a"},{"name":"b"}]}""")
        val launch = request(options().put("arguments", JSONObject().put("a", "one").put("b", "two")))
        val values = launch.argumentsFor(definition) as MutableMap<String, String>
        values["a"] = "changed"
        assertEquals("one", launch.argumentsFor(definition)["a"])
    }

    @Test fun argumentCountsAndAmbiguousDefinitionsAreBounded() {
        val values = JSONObject().also { value -> repeat(65) { value.put("key$it", "owned") } }
        rejected { request(options().put("arguments", values)) }
        rejected { request().argumentsFor(JSONObject("""{"arguments":[{"name":"a"},{"name":"a"}]}""")) }
    }

    @Test fun contextMustBeAnObjectAndNestedCollectionsAreBounded() {
        rejected { request(options().put("context", JSONArray())) }
        rejected { request(options().put("context", JSONObject.NULL)) }
        var nested: Any = "owned"
        repeat(18) { nested = JSONArray().put(nested) }
        rejected { request(options().put("context", JSONObject().put("deep", nested))) }
        val many = JSONArray().also { array -> repeat(2048) { array.put(it) } }
        rejected { request(options().put("context", JSONObject().put("many", many))) }
        rejected { request(options().put("context", JSONObject().put("x".repeat(257), true))) }
    }

    @Test fun requestIsBoundedBeforeParsingByUnitsAndUtf8Bytes() {
        rejected { ExtensionCommandLaunch.parse(" ".repeat(ExtensionCommandLaunch.MAX_REQUEST_UNITS + 1)) }
        rejected { request(options().put("context", JSONObject().put("text", "界".repeat(45000)))) }
        rejected { request(options().put("fallbackText", "x".repeat(16385))) }
    }

    @Test fun parserDepthIsCheckedBeforeJsonAllocationAndQuotedBracketsRemainData() {
        rejected { ExtensionCommandLaunch.parse("[".repeat(10000) + "]".repeat(10000)) }
        rejected { ExtensionCommandLaunch.parse("[] []") }
        rejected { ExtensionCommandLaunch.parse("[{\"context\":") }
        rejected { ExtensionCommandLaunch.parse("[/* comment */{}]") }
        rejected { ExtensionCommandLaunch.parse("[{'name':'target'}]") }
        val launch = request(options().put("context", JSONObject().put("text", "[{}] \\\" quoted")))
        assertEquals("[{}] \\\" quoted", JSONObject(launch.launchProperties(noArguments)).getJSONObject("launchContext").getString("text"))
    }

    @Test fun requiredPreferencesAndDropdownSelectionsMustBeConfigured() {
        val fields = listOf(JSONObject("""{"name":"language","type":"dropdown","required":true,"data":[{"value":"ja"}]}"""),
            JSONObject("""{"name":"enabled","type":"checkbox","required":true}"""))
        rejected { ExtensionCommandLaunch.requireConfigured(fields, JSONObject()) }
        rejected { ExtensionCommandLaunch.requireConfigured(fields, JSONObject().put("language", "unknown")) }
        ExtensionCommandLaunch.requireConfigured(fields, JSONObject().put("language", "ja").put("enabled", false))
    }
}
