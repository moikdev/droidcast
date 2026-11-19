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

class EmojiStore(context: Context, scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("emoji", Context.MODE_PRIVATE)
    var entries by mutableStateOf<List<EmojiEntry>>(emptyList()); private set
    var loading by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    var favorites by mutableStateOf(prefs.getStringSet("favorites", emptySet())!!.toSet()); private set
    var historyEnabled by mutableStateOf(prefs.getBoolean("rememberRecent", false)); private set
    var hasSavedHistory by mutableStateOf(prefs.contains("recent")); private set
    var recent by mutableStateOf(if (historyEnabled) readRecent() else emptyList()); private set
    private val historyListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "rememberRecent" || key == "recent") refreshHistory()
    }
    var query by mutableStateOf("")
    var filter by mutableStateOf("All")
    var selected by mutableIntStateOf(0)

    init {
        prefs.registerOnSharedPreferenceChangeListener(historyListener)
        scope.coroutineContext.job.invokeOnCompletion { prefs.unregisterOnSharedPreferenceChangeListener(historyListener) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                context.assets.open("emoji-catalog.tsv").bufferedReader().use { EmojiCatalog.parse(it.lineSequence()) }
            } }.onSuccess { entries = it }.onFailure { error = "Could not load the offline catalog. Reopen Droidcast to try again." }
            loading = false
        }
    }
    fun results() = EmojiCatalog.search(entries, query, filter, favorites, recent)
    fun search(value: String) { query = value; selected = 0 }
    fun chooseFilter(value: String) { filter = value; selected = 0 }
    fun toggleFavorite(entry: EmojiEntry) {
        val next = favorites.toMutableSet().apply { if (!add(entry.id)) remove(entry.id) }.toSet()
        prefs.edit().putStringSet("favorites", next).apply(); favorites = next
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
