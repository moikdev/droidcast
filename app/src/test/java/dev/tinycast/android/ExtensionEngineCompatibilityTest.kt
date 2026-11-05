package dev.tinycast.android

import dev.tinycast.android.extensions.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExtensionEngineCompatibilityTest {
    private val adapter = ExtensionEngineProbe.Stage.ADAPTER
    private val runtime = ExtensionEngineProbe.Stage.RUNTIME
    private val apis = ExtensionEngineProbe.Stage.APIS
    private fun wire(stage: ExtensionEngineProbe.Stage, problem: Any = JSONObject.NULL, missing: JSONArray = JSONArray()) =
        JSONObject.quote(JSONObject().put("v", 1).put("stage", stage.wire).put("problem", problem).put("missing", missing).toString())
    private fun unavailable(raw: String?, stage: ExtensionEngineProbe.Stage = adapter) =
        assertEquals(ExtensionEngineProbe.Result.Rejected(ExtensionEngineFailure.CHECK_UNAVAILABLE), ExtensionEngineProbe.decode(raw, stage))

    @Test fun noDispatchAuthorityUntilAllOrderedChecksPass() {
        val gate = ExtensionEngineGate()
        assertFalse(gate.approved)
        assertFalse(gate.accept(adapter))
        assertTrue(gate.begin()); assertFalse(gate.begin())
        assertFalse(gate.accept(apis)); assertFalse(gate.approved)
        assertTrue(gate.accept(adapter)); assertFalse(gate.accept(adapter)); assertFalse(gate.approved)
        assertTrue(gate.accept(runtime)); assertFalse(gate.approved)
        assertTrue(gate.accept(apis)); assertTrue(gate.approved)
        assertFalse(gate.accept(apis)); assertFalse(gate.begin())
    }
    @Test fun cancelledOrRejectedGateCannotBeReopenedByLateCallbacks() {
        for (closedAt in 0..3) {
            val gate = ExtensionEngineGate(); gate.begin()
            listOf(adapter, runtime, apis).take(closedAt).forEach { assertTrue(gate.accept(it)) }
            gate.block(); assertFalse(gate.approved)
            gate.close(); gate.block()
            assertFalse(gate.begin())
            listOf(adapter, runtime, apis).forEach { assertFalse(gate.accept(it)) }
            assertFalse(gate.approved)
        }
    }
    @Test fun resultsAreBoundToTheirExactStageAndFailureClass() {
        for (stage in listOf(adapter, runtime, apis)) assertEquals(ExtensionEngineProbe.Result.Passed, ExtensionEngineProbe.decode(wire(stage), stage))
        assertEquals(ExtensionEngineProbe.Result.Rejected(ExtensionEngineFailure.SYNTAX), ExtensionEngineProbe.decode(wire(adapter, "syntax"), adapter))
        assertEquals(ExtensionEngineProbe.Result.Rejected(ExtensionEngineFailure.MISSING_APIS, listOf("Object.hasOwn", "Array.prototype.at")),
            ExtensionEngineProbe.decode(wire(apis, "apis", JSONArray().put("Object.hasOwn").put("Array.prototype.at")), apis))
        unavailable(wire(runtime), adapter); unavailable(wire(apis, "syntax"), apis)
        unavailable(wire(adapter, "apis", JSONArray().put("Map")))
        unavailable(wire(apis, "apis"), apis)
    }
    @Test fun invalidOrUnboundedBridgeResultsCannotApproveAnEngine() {
        for (raw in listOf(null, "null", "{}", "true", "\"\"", "\"not JSON\"", "x".repeat(8193), wire(adapter) + " true")) unavailable(raw)
        unavailable(JSONObject.quote("{\"v\":1,\"stage\":\"adapter\",\"problem\":null,\"missing\":[]} false"))
        val original = JSONObject("""{"v":1,"stage":"adapter","problem":null,"missing":[]}""")
        for (key in listOf("v", "stage", "problem", "missing")) {
            val value = JSONObject(original.toString()); value.remove(key); unavailable(JSONObject.quote(value.toString()))
        }
        for (version in listOf<Any>("1", 2, true)) unavailable(JSONObject.quote(JSONObject(original.toString()).put("v", version).toString()))
        unavailable(JSONObject.quote(JSONObject(original.toString()).put("extra", true).toString()))
    }
    @Test fun missingApiResultsContainOnlyDistinctKnownNames() {
        for (missing in listOf(JSONArray().put("unknown"), JSONArray().put(7), JSONArray().put("Map").put("Map")))
            unavailable(wire(apis, "apis", missing), apis)
        unavailable(wire(apis, JSONObject.NULL, JSONArray().put("Map")), apis)
    }
    @Test fun compileInputCannotEscapeItsStringOrRunAsPartOfTheProbe() {
        val source = "globalThis.called=true;\n'\"\\\u2028\u2029</script>"
        val script = ExtensionEngineProbe.compileScript(adapter, source)
        assertTrue(script.contains("new Function("))
        assertFalse(script.contains('\u2028')); assertFalse(script.contains('\u2029'))
        assertTrue(script.contains("\\u2028")); assertTrue(script.contains("\\u2029"))
        assertThrows(IllegalArgumentException::class.java) { ExtensionEngineProbe.compileScript(apis, "") }
        assertThrows(IllegalArgumentException::class.java) { ExtensionEngineProbe.compileScript(adapter, "x".repeat(1_000_001)) }
    }
    @Test fun providerIdentityNeverAcceptsAnIntentOrDisplaysControlCharacters() {
        assertEquals("com.google.android.webview", ExtensionEngineIdentity("com.google.android.webview", "58.0.3029.125").settingsPackage)
        for (pkg in listOf("https://evil.test", "com.chrome/Activity", "com.chrome?x=1", "package:com.chrome", "com. chrome", ""))
            assertNull(ExtensionEngineIdentity(pkg, null).settingsPackage)
        assertEquals("58.0", ExtensionEngineIdentity(null, "\n58.0\u0000").display)
        assertEquals("Provider information unavailable", ExtensionEngineIdentity(null, null).display)
        assertTrue(ExtensionEngineIdentity(null, "x".repeat(500)).display.length <= 120)
    }
}
