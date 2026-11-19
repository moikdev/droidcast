package dev.tinycast.android

import java.util.Locale

data class EmojiEntry(val id: String, val glyph: String, val name: String, val category: String, val keywords: String) {
    internal val words = normalize("$name $keywords $category").split(' ').filter(String::isNotEmpty)
    internal val normalizedName = normalize(name)
}

internal fun normalize(value: String): String = value.lowercase(Locale.ROOT).replace('_', ' ').replace('-', ' ').trim()

object EmojiCatalog {
    fun parse(lines: Sequence<String>): List<EmojiEntry> = lines.filter { it.isNotBlank() && !it.startsWith('#') }.map { line ->
        val fields = line.split('\t')
        require(fields.size == 5 && fields.take(4).all { it.isNotBlank() }) { "Invalid emoji catalog" }
        EmojiEntry(fields[0], fields[1], fields[2], fields[3], fields[4])
    }.toList().also { entries ->
        require(entries.map { it.id }.distinct().size == entries.size && entries.map { it.glyph }.distinct().size == entries.size)
    }

    fun search(entries: List<EmojiEntry>, query: String, filter: String, favorites: Set<String>, recent: List<String>, favoriteOrder: List<String> = emptyList()): List<EmojiEntry> {
        val q = normalize(query.trim().removeSurrounding(":"))
        val terms = q.split(Regex("\\s+")).filter(String::isNotBlank)
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
            val score = when {
                query.trim() == entry.glyph || q == entry.normalizedName -> 4
                q in normalize(entry.keywords).split(' ') -> 3
                entry.normalizedName.startsWith(q) -> 2
                terms.all { term -> entry.words.any { it.startsWith(term) } } -> 1
                else -> return@mapNotNull null
            }
            entry to score
        }.sortedWith(compareByDescending<Pair<EmojiEntry, Int>> { it.second }
            .thenBy { it.first.id !in favorites }.thenBy { if (filter == "Recent") Int.MAX_VALUE else pinRank[it.first.id] ?: Int.MAX_VALUE }.thenBy { recentOrder[it.first.id] ?: Int.MAX_VALUE })
            .map { it.first }
    }

    fun recordRecent(recent: List<String>, id: String) = (listOf(id) + recent.filterNot { it == id }).take(32)
}
