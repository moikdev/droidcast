package dev.tinycast.android

import dev.tinycast.android.extensions.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExtensionMetadataVisualsTest {
    @Test fun freshlySerializedAdaptiveIconsRetainTheSameVisualValue() {
        val first = JSONObject("""{"source":{"light":"light.svg","dark":"dark.svg"},"mask":"circle","tintColor":{"light":"#ffffff","dark":"#228833"}}""")
        val second = JSONObject("""{"tintColor":{"dark":"#228833","light":"#ffffff"},"mask":"circle","source":{"dark":"dark.svg","light":"light.svg"}}""")
        assertEquals("dark.svg", first.getJSONObject("source").getString("dark"))
        assertNotSame(first, second)
        assertEquals(metadataIconSignature(first), metadataIconSignature(second))
    }
    @Test fun changedSourceMaskAndTintDoNotAdoptThePreviousVisualIdentity() {
        fun icon(source: String = "owned.svg", mask: String = "circle", tint: String = "#228833") =
            JSONObject().put("source", source).put("mask", mask).put("tintColor", JSONObject().put("dark", tint))
        val original = metadataIconSignature(icon())
        assertNotEquals(original, metadataIconSignature(icon(source = "replacement.svg")))
        assertNotEquals(original, metadataIconSignature(icon(mask = "roundedRectangle")))
        assertNotEquals(original, metadataIconSignature(icon(tint = "#dd3322")))
    }
    @Test fun adaptiveColorIdentityUsesTheSameShadeAsItsCurrentRenderer() {
        assertEquals("#228833", metadataColorName(JSONObject("""{"light":"#ffffff","dark":"#228833"}""")))
        assertEquals(metadataColorName(JSONObject("""{"dark":"#228833","light":"#ffffff"}""")),
            metadataColorName(JSONObject("""{"light":"#000000","dark":"#228833"}""")))
        assertEquals("#ffffff", metadataColorName(JSONObject("""{"light":"#ffffff"}""")))
        assertEquals("", metadataColorName(JSONObject.NULL))
    }
}
