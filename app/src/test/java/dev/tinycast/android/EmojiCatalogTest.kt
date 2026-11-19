package dev.tinycast.android

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EmojiCatalogTest {
    private val entries = File("src/main/assets/emoji-catalog.tsv").useLines { EmojiCatalog.parse(it) }
    private fun search(q: String, filter: String = "All", favorites: Set<String> = emptySet(), recent: List<String> = emptyList()) = EmojiCatalog.search(entries, q, filter, favorites, recent)

    @Test fun bundledCatalogPreservesSequencesAndStableIdentities() {
        assertEquals(1940, entries.size)
        listOf("👨‍👩‍👧‍👦", "🇯🇵", "❤️", "1️⃣", "π").forEach { glyph ->
            val entry = entries.single { it.glyph == glyph }
            assertEquals(glyph.codePoints().toArray().joinToString("-") { it.toString(16) }, entry.id)
            assertEquals(entry, search(glyph).first())
        }
        assertEquals("👨‍👩‍👧‍👦", search("family man woman girl boy").first().glyph)
    }
    @Test fun namesAliasesKeywordsAndMultipleWordsAreSearchable() {
        assertEquals("😀", search("grinning face").first().glyph)
        assertEquals("👍", search(":+1:").first().glyph)
        assertEquals("👍", search("thumbsup").first().glyph)
        assertTrue(search("happy").any { it.glyph == "😀" })
        assertEquals("π", search("pi").first().glyph)
        assertTrue(search("currency money").any { it.glyph == "€" })
        assertTrue(search("no-such-emoji-314159").isEmpty())
    }
    @Test fun filtersFavoritesAndRecentsHaveDeterministicOrder() {
        val flag = entries.single { it.glyph == "🇯🇵" }
        val heart = entries.single { it.glyph == "❤️" }
        val favorites = setOf(heart.id)
        val recent = listOf(flag.id, heart.id, "unknown-newer-emoji")
        assertEquals(listOf(heart), search("", "Favorites", favorites, recent))
        assertEquals(listOf(flag, heart), search("", "Recent", favorites, recent))
        assertEquals(heart, search("", favorites = favorites, recent = recent).first())
        assertTrue(search("", "Text symbols").all { it.category == "Text symbols" })
        assertTrue(search("Japan", "Text symbols").isEmpty())
    }
    @Test fun exactMatchesOutrankHistoryAndRecentIsBoundedWithoutDuplicates() {
        val other = entries.single { it.glyph == "😃" }
        assertEquals("😀", search("grinning face", favorites = setOf(other.id), recent = listOf(other.id)).first().glyph)
        val history = (0..40).map(Int::toString)
        val result = EmojiCatalog.recordRecent(history, "5")
        assertEquals(32, result.size); assertEquals("5", result.first()); assertEquals(1, result.count { it == "5" })
    }
    @Test fun explicitFavoriteOrderBreaksTextTiesAndKeepsRecentChronology() {
        val matches = entries.filter { it.glyph in listOf("😀", "😃", "😄") }
        val order = matches.reversed().map { it.id }
        val members = order.toSet()
        assertEquals(order, EmojiCatalog.search(matches, "", "Favorites", members, emptyList(), order).map { it.id })
        assertEquals(order, EmojiCatalog.search(matches, "", "All", members, emptyList(), order).map { it.id })
        assertEquals(order.reversed(), EmojiCatalog.search(matches, "", "Recent", members, order.reversed(), order).map { it.id })
        assertEquals("😀", EmojiCatalog.search(matches, "grinning face", "All", members, emptyList(), order).first().glyph)
    }

    @Test fun malformedCatalogsAreRejected() {
        listOf(sequenceOf("bad"), sequenceOf("id\tx\tname\tcat\tkeys", "id\ty\tname\tcat\tkeys")).forEach { input ->
            assertTrue(runCatching { EmojiCatalog.parse(input) }.isFailure)
        }
    }
}
