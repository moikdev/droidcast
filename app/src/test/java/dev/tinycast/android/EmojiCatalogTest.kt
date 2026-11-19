package dev.tinycast.android

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EmojiCatalogTest {
    private val entries = File("src/main/assets/emoji-catalog.tsv").useLines { EmojiCatalog.parse(it) }
    private fun search(q: String, filter: String = "All", favorites: Set<String> = emptySet(), recent: List<String> = emptyList()) = EmojiCatalog.search(entries, q, filter, favorites, recent)

    @Test fun bundledCatalogPreservesSequencesAndStableIdentities() {
        assertEquals(4042, entries.size)
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

    @Test fun languagePacksSearchCompleteNamesAndKeepEnglishAndCanonicalSequences() {
        val terms = mutableMapOf<String, EmojiCatalog.Annotations>()
        val names = mapOf("de" to "Katzengesicht", "es" to "cara de gato", "fr" to "tête de chat",
            "ja" to "ネコの顔", "ko" to "고양이 얼굴", "pt" to "rosto de gato", "ru" to "морда кошки",
            "zh" to "猫脸", "zh_Hant" to "貓頭")
        for (pack in names.keys + "en") {
            File("src/main/assets/emoji-keywords/$pack.tsv").useLines { EmojiCatalog.readKeywords(it, terms) }
        }
        val catalog = File("src/main/assets/emoji-catalog.tsv").useLines { EmojiCatalog.parse(it, terms) }
        fun first(query: String) = EmojiCatalog.search(catalog, query, "All", emptySet(), emptyList()).first().glyph
        for (name in names.values) assertEquals(name, "🐱", first(name))
        assertEquals("🐱", first("te\u0302te de chat"))
        assertEquals("🫫", first("cracking face"))
        assertEquals("🫱🏽‍🫲🏻", first("handshake: medium skin tone, light skin tone"))
        assertEquals("👍", first(":+1:"))
    }

    @Test fun resolvesScriptAndRegionAndDeduplicatesPreferredLanguages() {
        val tags = listOf("ja-JP", "ja", "zh-TW", "zh-Hans-HK", "zh-Hant-CN", "tr", "en")
        assertEquals(listOf("en", "ja", "zh_Hant", "zh"), EmojiCatalog.keywordPacks(tags.map(java.util.Locale::forLanguageTag)))
    }

    @Test fun everyQualifiedToneHasOneFamilyAndUsesThePublishedSequence() {
        val families = File("src/main/assets/emoji-tone-families.tsv").useLines { EmojiCatalog.toneFamilies(it, entries) }
        val modified = entries.filter { it.glyph.length > 2 && it.glyph.codePoints().anyMatch { cp -> cp in 0x1f3fb..0x1f3ff } }
        assertEquals(2040, modified.size)
        assertTrue(modified.all { it in families.getValue(it.id) })
        assertEquals(332, families.values.map { it.first().id }.distinct().size)
        fun family(glyph: String) = families.getValue(search(glyph).first().id).map { it.glyph }
        val handshake = family("🤝")
        assertEquals(26, handshake.size)
        assertEquals("🤝🏽", handshake[13])
        assertEquals("🫱🏽‍🫲🏻", handshake[11])
        assertEquals("🫱🏻‍🫲🏽", handshake[3])
        assertEquals(handshake, family("🫱🏽‍🫲🏻"))
        assertEquals("🧑🏻‍❤️‍💋‍🧑🏿", family("💏")[5])
        assertEquals(listOf("✌️", "✌🏻", "✌🏼", "✌🏽", "✌🏾", "✌🏿"), family("✌🏽"))
        for (unsupported in listOf("🏻", "🐱", "🇯🇵", "π")) assertNull(families[search(unsupported).first().id])
    }

    @Test fun customPhrasesKeepExactGlyphFirstAndUseTheExistingTieOrderAndFilters() {
        val cat = search("🐱").first()
        val dog = search("🐶").first()
        val smile = search("😀").first()
        val terms = mapOf(cat.id to listOf("dog face", "hello world", "😀"), dog.id to listOf("hello world"))
        fun results(q: String, filter: String = "All") = EmojiCatalog.search(
            entries, q, filter, setOf(dog.id), emptyList(), listOf(dog.id), terms)
        assertEquals(cat, results("DOG-FACE").first())
        assertEquals(smile, results("😀").first())
        assertEquals(listOf(dog, cat), results(":hello_world:"))
        assertEquals(listOf(dog), results("hello wo", "Favorites"))
        assertEquals(cat, results("cat wor").single())
        assertTrue(results("hello world", "Recent").isEmpty())
    }

    @Test fun keywordValidationPreservesSpellingAndCombinesEquivalentPhrases() {
        assertEquals(listOf("Café  Club", "推し"), EmojiCatalog.cleanCustomKeywords(" Café  Club\nCAFE\u0301-club\n推し  \n"))
        assertTrue(EmojiCatalog.cleanCustomKeywords("  \n").isEmpty())
        assertTrue(EmojiCatalog.cleanCustomKeywords("🐈".repeat(64)).isNotEmpty())
        for (draft in listOf("::", "-_", "a\tb", "🐈".repeat(65), (1..17).joinToString("\n")))
            assertTrue(runCatching { EmojiCatalog.cleanCustomKeywords(draft) }.isFailure)
    }

    @Test fun malformedCatalogsAreRejected() {
        listOf(sequenceOf("bad"), sequenceOf("id\tx\tname\tcat\tkeys", "id\ty\tname\tcat\tkeys")).forEach { input ->
            assertTrue(runCatching { EmojiCatalog.parse(input) }.isFailure)
        }
    }
}
