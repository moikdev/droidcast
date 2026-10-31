package dev.tinycast.android

import org.junit.Assert.*
import org.junit.Test

class PaletteScrollHistoryTest {
    @Test fun detachedLayoutDoesNotScrollOrForgetTheLastMeasuredViewport() {
        val history=PaletteScrollHistory()
        assertFalse(history.viewportMeasured(500,false))
        assertFalse("Leaving composition is not a shrink",history.viewportMeasured(0,false))
        assertFalse("Re-entry at the same size preserves manual scrolling",history.viewportMeasured(500,false))
        assertFalse(history.viewportMeasured(0,false))
        assertTrue("A real smaller viewport still recovers hidden selection",history.viewportMeasured(300,false))
        assertFalse(history.viewportMeasured(200,true))
    }
}
