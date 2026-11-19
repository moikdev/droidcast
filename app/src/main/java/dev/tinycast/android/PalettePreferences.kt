package dev.tinycast.android

import android.content.SharedPreferences
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Orders portable-data edits across activity stores in the app's main process. */
internal object PalettePreferences {
    // A changed internal bit forces commit() to persist the current preference map, even
    // when a preceding apply() had an asynchronous disk failure. It is never exported.
    private const val CHECKPOINT = "restorePersistenceCheckpoint"
    private var ownedKeys: MutableSet<String>? = null
    val restoring: Boolean get() = ownedKeys != null

    fun write(prefs: SharedPreferences, key: String, value: Any) = write(prefs, mapOf(key to value))

    fun write(prefs: SharedPreferences, values: Map<String, Any>) {
        check(Looper.getMainLooper().isCurrentThread)
        // Even a deliberate write of the same value transfers ownership to this writer.
        ownedKeys?.removeAll(values.keys)
        prefs.edit().also { editor -> values.forEach { (key, value) -> editor.putValue(key, value) } }.apply()
    }

    suspend fun restore(prefs: SharedPreferences, values: Map<String, Any>): Boolean =
        withContext(NonCancellable) {
            check(Looper.getMainLooper().isCurrentThread && !restoring)
            val before = prefs.all
            val owned = values.keys.toMutableSet()
            ownedKeys = owned
            try {
                // Publish once on the main thread, so every open store sees the new model
                // before accepting the next user action. Only the disk barrier runs on IO.
                prefs.edit().also { edit ->
                    values.forEach { (key, value) -> edit.putValue(key, value) }
                }.apply()
                val next = before[CHECKPOINT] != true
                val saved = withContext(Dispatchers.IO) {
                    try {
                        prefs.edit().putBoolean(CHECKPOINT, next).commit()
                    } catch (_: RuntimeException) {
                        false
                    }
                }
                if (!saved) {
                    val current = prefs.all
                    prefs.edit().also { undo ->
                        for (key in owned) {
                            if (current[key] == values[key]) undo.putValue(key, before[key])
                        }
                        undo.putValue(CHECKPOINT, before[CHECKPOINT])
                    }.apply()
                }
                saved
            } finally {
                ownedKeys = null
            }
        }

    private fun SharedPreferences.Editor.putValue(key: String, value: Any?) {
        when (value) {
            null -> remove(key)
            is String -> putString(key, value)
            is Int -> putInt(key, value)
            is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
            is Boolean -> putBoolean(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            else -> error("Unsupported palette preference type")
        }
    }
}
