package dev.tinycast.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

enum class BackupCategory(val label: String) {
    NOTES("Notes"), LINKS("Quicklinks"), CLIPS("Saved clips"), PREFERENCES("Pins and aliases"), LEARNING("Usage ranking"), SHORTCUTS("Keyboard shortcuts")
}

data class BackupData(
    val notes: List<SavedItem> = emptyList(), val links: List<SavedItem> = emptyList(),
    val clips: List<SavedItem> = emptyList(), val pins: Set<String> = emptySet(),
    val aliases: Map<String, String> = emptyMap(), val usage: Map<String, Int> = emptyMap(), val shortcuts: ShortcutConfiguration = ShortcutConfiguration()
) {
    fun count(category: BackupCategory): Int = when (category) {
        BackupCategory.NOTES -> notes.size; BackupCategory.LINKS -> links.size; BackupCategory.CLIPS -> clips.size
        BackupCategory.PREFERENCES -> pins.size + aliases.size; BackupCategory.LEARNING -> usage.size; BackupCategory.SHORTCUTS -> shortcuts.bindings.size
    }
}

data class BackupDocument(val data: BackupData, val categories: Set<BackupCategory>)
enum class RestorePolicy { MERGE, REPLACE }
data class RestorePlan(
    val data: BackupData, val added: Int, val skipped: Int, val removed: Int,
    val categories: Set<BackupCategory>
) {
    private companion object {
        val collectionCategories = setOf(BackupCategory.NOTES, BackupCategory.LINKS, BackupCategory.CLIPS)
    }

    fun reviewSummary(): String {
        fun count(n: Int, singular: String, plural: String) = "$n ${if (n == 1) singular else plural}"
        val settings = buildList {
            if (BackupCategory.PREFERENCES in categories) {
                add(count(data.pins.size, "pin", "pins"))
                add(count(data.aliases.size, "alias override", "alias overrides"))
            }
            if (BackupCategory.LEARNING in categories) add(count(data.usage.size, "usage ranking entry", "usage ranking entries"))
            if (BackupCategory.SHORTCUTS in categories) add(count(data.shortcuts.bindings.size, "keyboard shortcut", "keyboard shortcuts"))
        }
        return buildList {
            if (categories.any { it in collectionCategories }) {
                add("Collection items · $added to apply · ${count(skipped, "duplicate", "duplicates")} kept · $removed to replace")
            }
            if (settings.isNotEmpty()) add("Portable settings after restore · " + settings.joinToString(", "))
        }.joinToString("\n")
    }

    fun completionMessage(): String {
        if (categories.all { it in collectionCategories }) return "Backup restored · $added items applied, $skipped duplicates kept"
        val selection = categories.singleOrNull()?.label ?: "${categories.size} categories"
        return "Backup restored · $selection"
    }
}

/** Closed, versioned Android format. No code, capabilities, file grants or extension data. */
object BackupCodec {
    const val MAX_BYTES = 5 * 1024 * 1024
    private val aliasPattern = Regex("[a-z][a-z0-9_-]{0,23}")
    private val referencePattern = Regex("[A-Za-z0-9._:/$-]{1,1024}")
    private fun reference(id: String): Boolean = referencePattern.matches(id) && !id.startsWith("files:")
    private fun keys(value: JSONObject, allowed: Set<String>) {
        require(value.keys().asSequence().toSet() == allowed) { "Unexpected or missing backup fields" }
    }
    private fun string(value: JSONObject, key: String, max: Int): String {
        val text = value.get(key)
        require(text is String && text.length <= max) { "Invalid $key" }
        return text
    }
    private fun parse(reader: android.util.JsonReader, depth: Int = 0): Any {
        require(depth <= 8) { "Backup is nested too deeply" }
        return when (reader.peek()) {
            android.util.JsonToken.BEGIN_OBJECT -> JSONObject().also { result ->
                reader.beginObject()
                while (reader.hasNext()) {
                    require(result.length() < 5000) { "Too many backup fields" }
                    val name = reader.nextName()
                    require(name.length <= 1024 && !result.has(name)) { "Invalid or duplicate backup field" }
                    result.put(name, parse(reader, depth + 1))
                }
                reader.endObject()
            }
            android.util.JsonToken.BEGIN_ARRAY -> JSONArray().also { result ->
                reader.beginArray()
                while (reader.hasNext()) {
                    require(result.length() < 5000) { "Too many backup entries" }
                    result.put(parse(reader, depth + 1))
                }
                reader.endArray()
            }
            android.util.JsonToken.STRING -> reader.nextString().also { require(it.length <= 131072) { "Backup text is too long" } }
            android.util.JsonToken.NUMBER -> reader.nextString().let { number ->
                require(Regex("0|[1-9][0-9]*").matches(number)) { "Backup numbers must be nonnegative integers" }
                number.toIntOrNull() ?: throw IllegalArgumentException("Backup number is too large")
            }
            else -> throw IllegalArgumentException("Invalid backup value")
        }
    }
    fun read(input: InputStream): BackupDocument {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= MAX_BYTES) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        require(bytes.size <= MAX_BYTES) { "Backup exceeds the 5 MiB limit" }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        return decode(decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString())
    }
    fun decode(text: String): BackupDocument {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Backup exceeds the 5 MiB limit" }
        try {
            val root = android.util.JsonReader(java.io.StringReader(text)).use { reader ->
                reader.isLenient = false
                val value = parse(reader) as? JSONObject ?: throw IllegalArgumentException("Choose a Droidcast JSON backup")
                require(reader.peek() == android.util.JsonToken.END_DOCUMENT) { "Unexpected data after backup" }
                value
            }
            keys(root, setOf("format", "version", "categories"))
            require(root.get("format") == "dev.droidcast.backup") { "Choose a Droidcast JSON backup" }
            require(root.get("version") is Int && root.getInt("version") == 1) { "Unsupported backup version" }
            val sections = root.getJSONObject("categories")
            val names = sections.keys().asSequence().toSet()
            require(names.isNotEmpty() && names.all { name -> BackupCategory.entries.any { it.name == name } }) { "Unknown or empty backup categories" }
            val categories = names.map { BackupCategory.valueOf(it) }.toSet()
            fun items(category: BackupCategory, prefix: String): List<SavedItem> {
                if (category !in categories) return emptyList()
                val array = sections.getJSONArray(category.name)
                require(array.length() <= if (category == BackupCategory.CLIPS) 100 else 2000) { "Too many ${category.label}" }
                return (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    keys(item, setOf("id", "title", "body", "alias"))
                    SavedItem(string(item, "id", 100), string(item, "title", 512), string(item, "body", 131072), string(item, "alias", 24)).also {
                        require(Regex("$prefix:[A-Za-z0-9_-]{1,80}").matches(it.id) && it.title.isNotBlank()) { "Invalid ${category.label} item" }
                        require(it.alias.isEmpty() || (category == BackupCategory.LINKS && aliasPattern.matches(it.alias))) { "Invalid item alias" }
                        if (category == BackupCategory.LINKS) {
                            val uri = android.net.Uri.parse(it.body.replace("{query}", "test"))
                            require(uri.scheme in listOf("https", "http") && !uri.host.isNullOrBlank()) { "Quicklinks must use complete HTTP or HTTPS URLs" }
                        }
                    }
                }.also { require(it.map(SavedItem::id).toSet().size == it.size) { "Duplicate item identities" } }
            }
            var pins = emptySet<String>(); var aliases = emptyMap<String, String>()
            if (BackupCategory.PREFERENCES in categories) {
                val preferences = sections.getJSONObject("PREFERENCES")
                keys(preferences, setOf("pins", "aliases"))
                val array = preferences.getJSONArray("pins")
                require(array.length() <= 5000) { "Too many pins" }
                pins = (0 until array.length()).map { require(array.get(it) is String); array.getString(it).also { id -> require(reference(id)) { "Invalid pin reference" } } }.toSet()
                val map = preferences.getJSONObject("aliases")
                require(map.length() <= 5000) { "Too many aliases" }
                aliases = map.keys().asSequence().associateWith { id ->
                    require(reference(id)) { "Invalid alias reference" }
                    string(map, id, 24).also { require(it.isEmpty() || aliasPattern.matches(it)) { "Invalid alias" } }
                }
            }
            var usage = emptyMap<String, Int>()
            if (BackupCategory.LEARNING in categories) {
                val map = sections.getJSONObject("LEARNING")
                require(map.length() <= 5000) { "Too many ranking entries" }
                usage = map.keys().asSequence().associateWith { id ->
                    require(reference(id) && map.get(id) is Int && map.getInt(id) >= 0) { "Invalid ranking entry" }
                    map.getInt(id)
                }
            }
            val shortcuts = if (BackupCategory.SHORTCUTS in categories) ShortcutCodec.decode(sections.getJSONObject("SHORTCUTS")) else ShortcutConfiguration()
            require(shortcuts.bindings.keys.none { it.startsWith("files:") }) { "Document shortcuts cannot be imported" }
            return BackupDocument(BackupData(items(BackupCategory.NOTES, "notes"), items(BackupCategory.LINKS, "links"), items(BackupCategory.CLIPS, "clips"), pins, aliases, usage, shortcuts), categories)
        } catch (e: IllegalArgumentException) { throw e }
        catch (_: Exception) { throw IllegalArgumentException("Invalid Droidcast backup") }
    }
    fun encode(data: BackupData, categories: Set<BackupCategory>): String {
        require(categories.isNotEmpty()) { "Select at least one category" }
        val sections = JSONObject()
        categories.forEach { category ->
            when (category) {
                BackupCategory.NOTES, BackupCategory.LINKS, BackupCategory.CLIPS -> {
                    val items = when (category) { BackupCategory.NOTES -> data.notes; BackupCategory.LINKS -> data.links; else -> data.clips }
                    val array = JSONArray()
                    items.forEach { array.put(JSONObject().put("id", it.id).put("title", it.title).put("body", it.body).put("alias", it.alias)) }
                    sections.put(category.name, array)
                }
                BackupCategory.PREFERENCES -> sections.put(category.name, JSONObject().put("pins", JSONArray(data.pins.filterNot { it.startsWith("files:") }.sorted())).put("aliases", JSONObject(data.aliases.filterKeys { !it.startsWith("files:") })))
                BackupCategory.LEARNING -> sections.put(category.name, JSONObject(data.usage.filterKeys { !it.startsWith("files:") }))
                BackupCategory.SHORTCUTS -> sections.put(category.name, ShortcutCodec.encode(ShortcutConfiguration(data.shortcuts.bindings.filterKeys { !it.startsWith("files:") })))
            }
        }
        val text = JSONObject().put("format", "dev.droidcast.backup").put("version", 1).put("categories", sections).toString(2)
        decode(text) // Export must meet the same limits and schema as import.
        return text
    }
}

object BackupPlanner {
    /** Merge preserves existing identities/content. Replacement affects only selected categories. */
    fun plan(current: BackupData, document: BackupDocument, selected: Set<BackupCategory>, policy: RestorePolicy, defaults: Map<String, String>, preservedShortcuts: Map<String, KeyChord> = emptyMap()): RestorePlan {
        require(selected.isNotEmpty() && document.categories.containsAll(selected)) { "Select available categories" }
        var added = 0; var skipped = 0; var removed = 0
        val remap = mutableMapOf<String, String>()
        fun items(category: BackupCategory, old: List<SavedItem>, incoming: List<SavedItem>): List<SavedItem> {
            if (category !in selected) return old
            if (policy == RestorePolicy.REPLACE) { added += incoming.size; removed += old.size; return incoming }
            val next = old.toMutableList()
            incoming.forEach { item ->
                val existing = next.firstOrNull { it.id == item.id }
                    ?: next.firstOrNull { it.title == item.title && it.body == item.body && it.alias == item.alias }
                if (existing != null) { skipped++; remap[item.id] = existing.id } else { next.add(item); added++ }
            }
            require(category != BackupCategory.CLIPS || next.size <= 100) { "Merge would exceed 100 saved clips; replace this category or remove clips first" }
            require(next.size <= 2000) { "Too many items after merge" }
            return next
        }
        val notes = items(BackupCategory.NOTES, current.notes, document.data.notes)
        val links = items(BackupCategory.LINKS, current.links, document.data.links)
        val clips = items(BackupCategory.CLIPS, current.clips, document.data.clips)
        fun ref(id: String) = remap[id] ?: id
        val preferences = BackupCategory.PREFERENCES in selected
        val pins = if (!preferences) current.pins else document.data.pins.map(::ref).toSet().let { if (policy == RestorePolicy.MERGE) current.pins + it else it }
        val importedAliases = mutableMapOf<String, String>()
        if (preferences) document.data.aliases.forEach { (id, alias) ->
            val target = ref(id)
            require(target !in importedAliases || importedAliases[target] == alias) { "Duplicate content has conflicting alias overrides; exclude pins and aliases to merge it" }
            importedAliases[target] = alias
        }
        val aliases = if (!preferences) current.aliases else if (policy == RestorePolicy.MERGE) importedAliases + current.aliases else importedAliases
        val importedUsage = mutableMapOf<String, Int>()
        document.data.usage.forEach { (id, count) -> importedUsage[ref(id)] = maxOf(importedUsage[ref(id)] ?: 0, count) }
        val usage = if (BackupCategory.LEARNING !in selected) current.usage else if (policy == RestorePolicy.MERGE) importedUsage + current.usage else importedUsage
        val effective = defaults + links.associate { it.id to it.alias } + aliases
        require(effective.values.filter { it.isNotEmpty() }.groupingBy { it }.eachCount().values.none { it > 1 }) { "Alias conflict: exclude pins and aliases or quicklinks, or change the conflicting alias first" }
        val shortcuts = if (BackupCategory.SHORTCUTS !in selected) current.shortcuts else {
            val incoming = mutableMapOf<String, KeyChord>()
            document.data.shortcuts.bindings.forEach { (id, chord) ->
                val target = ref(id)
                require(target !in incoming || incoming[target] == chord) { "Duplicate content has conflicting keyboard shortcuts" }
                incoming[target] = chord
            }
            ShortcutConfiguration(if (policy == RestorePolicy.MERGE) incoming + current.shortcuts.bindings else incoming.toMap())
        }
        ShortcutRules.validate(ShortcutConfiguration(shortcuts.bindings + preservedShortcuts))
        return RestorePlan(BackupData(notes, links, clips, pins, aliases, usage, shortcuts), added, skipped, removed, selected.toSet())
    }
}
