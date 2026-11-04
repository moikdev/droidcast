package dev.tinycast.android

import dev.tinycast.android.extensions.ExtensionPasteCopy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExtensionPasteCopyTest {
    private fun request(value: Any) = ExtensionPasteCopy.parse(JSONArray().put(value).toString())
    private fun rejected(block: () -> Unit) = assertThrows(IllegalArgumentException::class.java) { block() }

    @Test fun exactUnicodeNewlinesAndEscapesCanBeConsumedOnlyOnce() {
        val text = "café é 日本 🌱\n\"quoted\"\\path"
        val output = request(text)
        assertTrue(output.available); assertEquals(text, output.take())
        assertFalse(output.available); assertNull(output.take())
    }
    @Test fun clearingDropsOutputEvenWhenTheOldCapabilityIsRetained() {
        val output = request("Owned obsolete output")
        output.clear(); assertFalse(output.available); assertNull(output.take())
        output.clear(); assertNull(output.take())
    }
    @Test fun plainAlternativeTextDoesNotBecomeHtmlOrAFileName() {
        val input = JSONObject().put("text", "Owned plain text").put("html", "<b>not rendered</b>")
        val output = request(input); input.put("text", "later mutation")
        assertEquals("Owned plain text", output.take())
        rejected { request(JSONObject().put("html", "<b>no alternative text</b>")) }
        rejected { request(JSONObject().put("file", "/owned/not-opened")) }
        rejected { request(JSONObject().put("text", "owned").put("file", "/owned/not-opened")) }
    }
    @Test fun numericTextAndEmptyTextHaveExplicitSupportedRepresentations() {
        assertEquals("7", request(7).take()); assertEquals("1.25", request(1.25).take())
        assertEquals("1e-7", ExtensionPasteCopy.parse("[1e-7]").take())
        assertEquals("1e+21", ExtensionPasteCopy.parse("[1e+21]").take())
        assertEquals("", request("").take())
        rejected { request(JSONObject.NULL) }; rejected { request(true) }; rejected { request(JSONArray().put("not text")) }
    }
    @Test fun optionalWindowPreferenceDoesNotGrantAutomaticPasteOrCopy() {
        for (raw in listOf("[\"owned\"]", "[\"owned\",null]", "[\"owned\",{}]", "[\"owned\",{\"closeMainWindow\":false}]")) {
            val output = ExtensionPasteCopy.parse(raw); assertTrue(output.available); assertEquals("owned", output.take())
        }
        rejected { ExtensionPasteCopy.parse("[\"owned\",{\"closeMainWindow\":\"yes\"}]") }
        rejected { ExtensionPasteCopy.parse("[\"owned\",{\"unsupported\":true}]") }
        rejected { ExtensionPasteCopy.parse("[\"owned\",{},{}]") }
    }
    @Test fun textAndRawRequestBoundsAreSeparateAndCheckedBeforeAllocation() {
        assertEquals(ExtensionPasteCopy.MAX_TEXT_UNITS, request("x".repeat(ExtensionPasteCopy.MAX_TEXT_UNITS)).take()!!.length)
        rejected { request("x".repeat(ExtensionPasteCopy.MAX_TEXT_UNITS + 1)) }
        rejected { request("界".repeat(45000)) }
        rejected { ExtensionPasteCopy.parse(" ".repeat(ExtensionPasteCopy.MAX_REQUEST_UNITS + 1)) }
        rejected { ExtensionPasteCopy.parse("[".repeat(10000) + "]".repeat(10000)) }
    }
    @Test fun malformedParametersDoNotLeakRawValuesIntoErrors() {
        for (raw in listOf("[{", "[/* private marker */true]", "['private marker']", "[]", "[] []",
            "[unquoted]", "[{text:\"owned\"}]", "[NaN]", "[1e999]", "[01]", "[1,]")) {
            val error = assertThrows(IllegalArgumentException::class.java) { ExtensionPasteCopy.parse(raw) }
            assertFalse(error.message.orEmpty().contains("private marker"))
        }
    }
    @Test fun utf8RequestBytesAreBoundedEvenWhenThePlainAlternativeIsSmall() {
        rejected { request(JSONObject().put("text", "owned").put("html", "界".repeat(180000))) }
    }
    @Test fun unpairedSurrogatesAreRejectedWithoutChangingCombiningText() {
        rejected { request("\uD800") }; rejected { request("\uDC00") }
        assertEquals("é", request("é").take())
    }
}
