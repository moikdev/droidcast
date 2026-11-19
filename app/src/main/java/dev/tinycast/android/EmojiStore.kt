package dev.tinycast.android

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class EmojiStore(private val context: Context, private val scope: CoroutineScope) {
    internal companion object {
        const val FAVORITES_KEY = "emojiFavoriteOrder"
        private const val KEYWORD_PREFIX = "keyword:"
        const val GRID_KEY = "emojiGridColumns"
    }
    private val portable = context.getSharedPreferences("palette", Context.MODE_PRIVATE)
    private val catalogIdsWhileLoading by lazy {
        context.assets.open("emoji-catalog.tsv").bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith('#') }.map { it.substringBefore('\t') }.toList()
        }
    }
    private val prefs = context.getSharedPreferences("emoji", Context.MODE_PRIVATE)
    private var keywordIndex by mutableStateOf(readKeywordIndex())

    private fun readKeywordIndex(): Map<String, List<String>> = buildMap {
        for ((key, value) in prefs.all) {
            if (key.startsWith(KEYWORD_PREFIX) && value is String)
                put(key.removePrefix(KEYWORD_PREFIX), value.lines().map(EmojiCatalog::searchPhrase))
        }
    }

    internal fun customKeywordText(id: String) = prefs.getString(KEYWORD_PREFIX + id, "").orEmpty()

    internal fun saveCustomKeywords(id: String, original: String, draft: String): Boolean {
        check(android.os.Looper.myLooper() === android.os.Looper.getMainLooper())
        if (entries.none { it.id == id } || customKeywordText(id) != original) return false
        val words = EmojiCatalog.cleanCustomKeywords(draft)
        val editor = prefs.edit()
        if (words.isEmpty()) editor.remove(KEYWORD_PREFIX + id)
        else editor.putString(KEYWORD_PREFIX + id, words.joinToString("\n"))
        editor.apply()
        return true
    }

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
    private fun readGridColumns(): Int? {
        val value = if (portable.contains(GRID_KEY)) portable.getInt(GRID_KEY, 0) else prefs.getInt("gridColumns", 0)
        return value.takeIf { it in 1..10 }
    }

    fun saveGridColumns(columns: Int?) {
        require(columns == null || columns in 1..10)
        PalettePreferences.write(portable, GRID_KEY, columns ?: 0)
    }

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "favorites" || key == "favoriteOrder" || key == FAVORITES_KEY) {
            val next = readFavorites()
            if (next != savedFavorites) {
                val active = results().getOrNull(selected)?.id
                savedFavorites = next
                val updated = results()
                selected = updated.indexOfFirst { it.id == active }.takeIf { it >= 0 }
                    ?: selected.coerceAtMost(updated.lastIndex.coerceAtLeast(0))
            }
        }
        if (key == null || key.startsWith(KEYWORD_PREFIX)) {
            val active = results().getOrNull(selected)?.id
            keywordIndex = readKeywordIndex()
            val updated = results()
            selected = updated.indexOfFirst { it.id == active }.takeIf { it >= 0 }
                ?: selected.coerceAtMost(updated.lastIndex.coerceAtLeast(0))
        }
        if (key == null || key == "gridColumns" || key == GRID_KEY) defaultGridColumns = readGridColumns()
        if (key == null || key == "rememberRecent" || key == "recent") refreshHistory()
    }
    var query by mutableStateOf("")
    var filter by mutableStateOf("All")
    var selected by mutableIntStateOf(0)

    private var toneFamilies: Map<String, List<EmojiEntry>> = emptyMap()
    internal fun skinTones(id: String?) = toneFamilies[id].orEmpty()

    private var keywordPacks: List<String> = emptyList()
    private var catalogJob: kotlinx.coroutines.Job? = null

    init {
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        portable.registerOnSharedPreferenceChangeListener(preferenceListener)
        scope.coroutineContext.job.invokeOnCompletion {
            portable.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        }
        loadLanguages(context.resources.configuration.locales.toLanguageTags())
    }

    internal fun loadLanguages(tags: String) {
        val packs = EmojiCatalog.keywordPacks(tags.split(',').map(java.util.Locale::forLanguageTag))
        if (packs == keywordPacks) return
        keywordPacks = packs
        catalogJob?.cancel()
        loading = true
        error = null
        catalogJob = scope.launch {
            try {
                val (catalog, families) = withContext(Dispatchers.IO) {
                    val annotations = mutableMapOf<String, EmojiCatalog.Annotations>()
                    for (pack in packs) {
                        context.assets.open("emoji-keywords/$pack.tsv").bufferedReader().use {
                            EmojiCatalog.readKeywords(it.lineSequence(), annotations)
                        }
                    }
                    val entries = context.assets.open("emoji-catalog.tsv").bufferedReader().use {
                        EmojiCatalog.parse(it.lineSequence(), annotations)
                    }
                    entries to context.assets.open("emoji-tone-families.tsv").bufferedReader().use {
                        EmojiCatalog.toneFamilies(it.lineSequence(), entries)
                    }
                }
                val previous = results().getOrNull(selected)?.id
                toneFamilies = families
                entries = catalog
                selected = previous?.let { id -> results().indexOfFirst { it.id == id } }?.coerceAtLeast(0) ?: 0
                loading = false
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "Could not load the offline catalog. Reopen Droidcast to try again."
                keywordPacks = emptyList()
                loading = false
            }
        }
    }
    fun results() = EmojiCatalog.search(entries, query, filter, favorites, recent, savedFavorites.order, keywordIndex)
    fun search(value: String) { query = value; selected = 0 }
    fun chooseFilter(value: String) { filter = value; selected = 0 }
    private fun readFavorites(): SavedFavorites {
        portable.getString(FAVORITES_KEY, null)?.let { value ->
            val array = JSONArray(value)
            val order = (0 until array.length()).map(array::getString)
            return SavedFavorites(order.toSet(), order)
        }
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
        if (size < saved.members.size) {
            val ids = if (entries.isEmpty()) catalogIdsWhileLoading.asSequence() else entries.asSequence().map { it.id }
            ids.filterTo(this) { it in saved.members }
        }
        addAll((saved.members - this).sorted())
    }.toList()

    private fun writeFavorites(order: List<String>) {
        PalettePreferences.write(portable, FAVORITES_KEY, JSONArray(order).toString())
    }

    fun portableSettings() = PortableEmojiSettings(orderedMembers(savedFavorites), defaultGridColumns)

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
