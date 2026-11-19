package dev.tinycast.android

import java.text.Normalizer
import java.util.Locale

data class EmojiEntry(
    val id: String, val glyph: String, val name: String, val category: String, val keywords: String,
    internal val translatedNames: String = "", private val annotationKeywords: String = ""
) {
    internal val normalizedName = normalize(name)
    // Tabs bound complete CLDR phrases. Spaces bound individual words used for prefix search.
    internal val exactKeywords = "\t" + normalize(keywords).replace(' ', '\t') + "\t" + annotationKeywords
    internal val wordIndex = " $normalizedName " + normalize(category) + " " +
        (translatedNames + exactKeywords).replace('\t', ' ')
}

internal fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT).replace('_', ' ').replace('-', ' ').trim()

object EmojiCatalog {
    private val whitespace = Regex("\\s+")

    internal fun searchPhrase(raw: String) = normalize(raw.trim().removeSurrounding(":")).replace(whitespace, " ")

    internal fun cleanCustomKeywords(text: String): List<String> {
        require(text.length <= 4096) { "Shorten the keyword list before saving." }
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        require(lines.all { it.codePointCount(0, it.length) <= 64 }) { "Use up to 64 characters per keyword." }
        require(lines.all { searchPhrase(it).isNotBlank() && it.none(Char::isISOControl) }) {
            "Each keyword needs searchable text without control characters."
        }
        val unique = lines.distinctBy(::searchPhrase)
        require(unique.size <= 16) { "Use up to 16 different keywords per emoji." }
        return unique
    }

    internal fun keywordPacks(locales: List<Locale>): List<String> = buildSet {
        add("en")
        for (locale in locales) when (locale.language) {
            "de", "es", "fr", "ja", "ko", "pt", "ru" -> add(locale.language)
            "zh" -> {
                val traditional = locale.script == "Hant" ||
                    (locale.script.isEmpty() && locale.country in setOf("TW", "HK", "MO"))
                add(if (traditional) "zh_Hant" else "zh")
            }
        }
    }.toList()

    class Annotations {
        internal val names = StringBuilder()
        internal val keywords = StringBuilder()
    }

    internal fun readKeywords(lines: Sequence<String>, into: MutableMap<String, Annotations>) {
        for (line in lines) {
            val idEnd = line.indexOf('\t')
            require(idEnd > 0)
            val nameEnd = line.indexOf('\t', idEnd + 1).let { if (it == -1) line.length else it }
            val entry = into.getOrPut(line.substring(0, idEnd)) { Annotations() }
            if (nameEnd > idEnd + 1) entry.names.append(line, idEnd, nameEnd).append('\t')
            if (nameEnd < line.length) entry.keywords.append(line, nameEnd, line.length).append('\t')
        }
    }

    fun parse(lines: Sequence<String>, annotations: Map<String, Annotations> = emptyMap()): List<EmojiEntry> = lines.filter { it.isNotBlank() && !it.startsWith('#') }.map { line ->
        val fields = line.split('\t')
        require(fields.size == 5 && fields.take(4).all { it.isNotBlank() }) { "Invalid emoji catalog" }
        val words = annotations[fields[0]]
        EmojiEntry(fields[0], fields[1], fields[2], fields[3], fields[4], words?.names?.toString().orEmpty(), words?.keywords?.toString().orEmpty())
    }.toList().also { entries ->
        require(entries.map { it.id }.distinct().size == entries.size && entries.map { it.glyph }.distinct().size == entries.size)
    }

    internal fun toneFamilies(lines: Sequence<String>, catalog: List<EmojiEntry>): Map<String, List<EmojiEntry>> {
        val byId = catalog.associateBy { it.id }
        return buildMap {
            for (line in lines) {
                val variants = line.split('\t').map(byId::getValue)
                require(variants.size == 6 || variants.size == 26)
                for (variant in variants) require(put(variant.id, variants) == null)
            }
        }
    }

    fun search(entries: List<EmojiEntry>, query: String, filter: String, favorites: Set<String>, recent: List<String>, favoriteOrder: List<String> = emptyList(), customKeywords: Map<String, List<String>> = emptyMap()): List<EmojiEntry> {
        val rawQuery = query.trim()
        val q = searchPhrase(rawQuery)
        val phrase = "\t$q\t"
        val prefixes = q.split(' ').filter(String::isNotBlank).map { " $it" }
        val recentOrder = recent.withIndex().associate { it.value to it.index }
        val pinRank = favoriteOrder.withIndex().associate { it.value to it.index }
        val filtered = entries.filter { entry -> when (filter) {
            "Favorites" -> entry.id in favorites
            "Recent" -> entry.id in recentOrder
            "All" -> true
            else -> entry.category == filter
        } }
        if (q.isEmpty()) return when (filter) {
            "Favorites" -> filtered.sortedBy { pinRank[it.id] ?: Int.MAX_VALUE }
            "Recent" -> filtered.sortedBy { recentOrder[it.id] }
            "All" -> filtered.sortedWith(compareBy<EmojiEntry> { it.id !in favorites }.thenBy { pinRank[it.id] ?: Int.MAX_VALUE }.thenBy { recentOrder[it.id] ?: Int.MAX_VALUE })
            else -> filtered
        }
        return filtered.mapNotNull { entry ->
            val personal = customKeywords[entry.id].orEmpty()
            val score = when {
                rawQuery == entry.glyph -> 7
                q in personal -> 6
                q == entry.normalizedName -> 5
                phrase in entry.translatedNames -> 4
                phrase in entry.exactKeywords -> 3
                entry.normalizedName.startsWith(q) -> 2
                prefixes.all { prefix -> prefix in entry.wordIndex || personal.any { term -> " $term".contains(prefix) } } -> 1
                else -> return@mapNotNull null
            }
            entry to score
        }.sortedWith(compareByDescending<Pair<EmojiEntry, Int>> { it.second }
            .thenBy { it.first.id !in favorites }.thenBy { if (filter == "Recent") Int.MAX_VALUE else pinRank[it.first.id] ?: Int.MAX_VALUE }.thenBy { recentOrder[it.first.id] ?: Int.MAX_VALUE })
            .map { it.first }
    }

    fun recordRecent(recent: List<String>, id: String) = (listOf(id) + recent.filterNot { it == id }).take(32)
}
