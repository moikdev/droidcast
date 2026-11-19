package dev.tinycast.android

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class EmojiStore(context: Context, scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("emoji", Context.MODE_PRIVATE)
    var entries by mutableStateOf<List<EmojiEntry>>(emptyList()); private set
    var loading by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    private data class SavedFavorites(val members: Set<String>, val order: List<String>)
    private var savedFavorites by mutableStateOf(readFavorites())
    val favorites: Set<String> get() = savedFavorites.members
    var historyEnabled by mutableStateOf(prefs.getBoolean("rememberRecent", false)); private set
    var hasSavedHistory by mutableStateOf(prefs.contains("recent")); private set
    var recent by mutableStateOf(if (historyEnabled) readRecent() else emptyList()); private set
    var defaultGridColumns by mutableStateOf(readGridColumns()); private set
    private fun readGridColumns(): Int? = prefs.getInt("gridColumns", 0).takeIf { it in 1..10 }

    fun saveGridColumns(columns: Int?) {
        require(columns == null || columns in 1..10)
        prefs.edit {
            if (columns == null) remove("gridColumns") else putInt("gridColumns", columns)
        }
    }

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "favorites" || key == "favoriteOrder") {
            val next = readFavorites()
            if (next != savedFavorites) {
                val active = results().getOrNull(selected)?.id
                savedFavorites = next
                val updated = results()
                selected = updated.indexOfFirst { it.id == active }.takeIf { it >= 0 }
                    ?: selected.coerceAtMost(updated.lastIndex.coerceAtLeast(0))
            }
        }
        if (key == null || key == "gridColumns") defaultGridColumns = readGridColumns()
        if (key == null || key == "rememberRecent" || key == "recent") refreshHistory()
    }
    var query by mutableStateOf("")
    var filter by mutableStateOf("All")
    var selected by mutableIntStateOf(0)

    init {
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        scope.coroutineContext.job.invokeOnCompletion { prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                context.assets.open("emoji-catalog.tsv").bufferedReader().use { EmojiCatalog.parse(it.lineSequence()) }
            } }.onSuccess { entries = it }.onFailure { error = "Could not load the offline catalog. Reopen Droidcast to try again." }
            loading = false
        }
    }
    fun results() = EmojiCatalog.search(entries, query, filter, favorites, recent, savedFavorites.order)
    fun search(value: String) { query = value; selected = 0 }
    fun chooseFilter(value: String) { filter = value; selected = 0 }
    private fun readFavorites(): SavedFavorites {
        val members = prefs.getStringSet("favorites", emptySet()).orEmpty().toSet()
        val order = runCatching {
            val array = JSONArray(prefs.getString("favoriteOrder", "[]"))
            (0 until array.length()).mapNotNull { array.opt(it) as? String }.filter { it in members }.distinct()
        }.getOrDefault(emptyList())
        return SavedFavorites(members, order)
    }

    // Older installs stored only a set. Keep their displayed catalog order and
    // preserve unknown IDs for catalogs that can display them in the future.
    private fun orderedMembers(saved: SavedFavorites): List<String> = buildSet {
        addAll(saved.order)
        entries.forEach { if (it.id in saved.members) add(it.id) }
        addAll((saved.members - this).sorted())
    }.toList()

    private fun writeFavorites(order: List<String>) {
        prefs.edit {
            putStringSet("favorites", order.toSet())
            putString("favoriteOrder", JSONArray(order).toString())
        }
    }

    fun toggleFavorite(entry: EmojiEntry) {
        val current = orderedMembers(readFavorites())
        writeFavorites(if (entry.id in current) current - entry.id else current + entry.id)
    }

    fun moveFavorite(id: String, neighbor: String, offset: Int) {
        if (filter != "Favorites" || query.isNotBlank() || offset !in listOf(-1, 1)) return
        val order = orderedMembers(readFavorites()).toMutableList()
        val known = entries.mapTo(hashSetOf()) { it.id }
        val visible = order.filter { it in known }
        val index = visible.indexOf(id)
        if (index < 0 || visible.getOrNull(index + offset) != neighbor) return
        java.util.Collections.swap(order, order.indexOf(id), order.indexOf(neighbor))
        writeFavorites(order)
    }

    private fun readRecent(): List<String> = runCatching {
        val array = JSONArray(prefs.getString("recent", "[]"))
        (0 until minOf(array.length(), 32)).map { array.getString(it) }.distinct()
    }.getOrDefault(emptyList())

    private fun refreshHistory() {
        historyEnabled = prefs.getBoolean("rememberRecent", false)
        hasSavedHistory = prefs.contains("recent")
        recent = if (historyEnabled) readRecent() else emptyList()
    }

    fun rememberRecent(enabled: Boolean) {
        prefs.edit().putBoolean("rememberRecent", enabled).apply()
    }

    fun clearHistory() {
        prefs.edit().remove("recent").apply()
    }

    fun copied(entry: EmojiEntry) {
        if (!prefs.getBoolean("rememberRecent", false)) return
        val next = EmojiCatalog.recordRecent(readRecent(), entry.id)
        prefs.edit().putString("recent", JSONArray(next).toString()).apply()
    }
}
