package dev.tinycast.android

import dev.tinycast.android.extensions.WeakValueTokens
import java.lang.ref.WeakReference
import org.junit.Assert.*
import org.junit.Test

class WeakValueTokensTest {
    private data class RenderValue(val source: String, val ready: Boolean)

    @Test fun equalReplacementSurvivesClearingTheOlderWrapper() {
        val references = mutableListOf<WeakReference<RenderValue>>()
        val tokens = WeakValueTokens<RenderValue>(reference = { WeakReference(it).also(references::add) })
        val original = RenderValue("owned image", true)
        val replacement = original.copy()
        val token = tokens.token(original)
        assertEquals(token, tokens.token(replacement))
        // Simulate retirement of the older wrapper without relying on GC timing.
        references.first().clear()
        assertEquals(token, tokens.token(replacement))
    }

    @Test fun changedValuesRemainDistinctAndAnExistingInstanceKeepsItsToken() {
        val tokens = WeakValueTokens<RenderValue>()
        val ready = RenderValue("owned image", true)
        val token = tokens.token(ready)
        assertEquals(token, tokens.token(ready))
        assertNotEquals(token, tokens.token(ready.copy(ready = false)))
        assertNotEquals(token, tokens.token(ready.copy(source = "another owned image")))
    }

    @Test fun boundedHistoryReportsEvictionWithoutChangingTheCurrentToken() {
        val tokens = WeakValueTokens<RenderValue>(capacity = 2)
        val values = (0..2).map { RenderValue("owned $it", true) }
        val labels = values.map(tokens::token)
        assertEquals(1, tokens.evictions)
        assertEquals(labels.last(), tokens.token(values.last()))
        assertEquals(1, tokens.evictions)
    }
}
