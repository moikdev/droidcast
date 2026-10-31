package dev.tinycast.android.extensions

import org.json.JSONObject
import kotlin.math.ceil

internal data class ExtensionGridItem(val node: JSONObject, val key: String, val id: String, val title: String, val subtitle: String)
internal data class ExtensionGridSection(val key: String, val title: String, val subtitle: String, val items: List<ExtensionGridItem>)

internal fun gridSections(root: JSONObject, query: String): List<ExtensionGridSection> {
    val filtering = root.props().opt("filtering") != false
    val seen = mutableSetOf<String>()
    var generated = 0
    fun items(node: JSONObject) = node.nodes("Grid.Item").map { item ->
        val p = item.props()
        val id = p.optString("id", item.optString("id"))
        val key = id.takeIf { it.isNotBlank() && seen.add(it) } ?: "${item.optString("id")}-${generated++}"
        ExtensionGridItem(item, key, id, p.optString("title"), p.optString("subtitle"))
    }.filter { item ->
        !filtering || query.isBlank() || (item.title + " " + item.subtitle + " " + item.node.props().optJSONArray("keywords")?.toString().orEmpty()).contains(query, true)
    }
    val sections = mutableListOf<ExtensionGridSection>()
    val loose = mutableListOf<JSONObject>()
    fun flush() {
        if (loose.isNotEmpty()) {
            val found = loose.flatMap(::items)
            if (found.isNotEmpty()) sections.add(ExtensionGridSection("loose-${sections.size}", "", "", found))
            loose.clear()
        }
    }
    fun visit(node: JSONObject) {
        when (node.optString("type")) {
            "Grid.Section" -> {
                flush()
                val found = items(node)
                if (found.isNotEmpty()) sections.add(ExtensionGridSection(node.optString("id"), node.props().optString("title"), node.props().optString("subtitle"), found))
            }
            "Grid.Item" -> loose.add(node)
            else -> node.children().forEach(::visit)
        }
    }
    root.children().forEach(::visit); flush()
    return sections
}

internal enum class GridMove { LEFT, RIGHT, UP, DOWN, HOME, END }

internal data class GridIdentity(val key: String, val id: String)
internal data class GridSelectionChoice(val key: String?, val pendingId: String?)

/** Apply each remote request once; an unavailable target remains pending. */
internal fun gridSelection(items: List<GridIdentity>, currentKey: String?, remoteId: String?,
                           observedRemoteId: String?, pendingId: String?): GridSelectionChoice {
    val requested = if (remoteId != observedRemoteId) remoteId else pendingId
    val target = requested?.let { id -> items.firstOrNull { it.id == id } }
    val selected = target ?: items.firstOrNull { it.key == currentKey } ?: items.firstOrNull()
    return GridSelectionChoice(selected?.key, requested.takeIf { target == null })
}

internal sealed interface GridSlot {
    data class Boundary(val sectionIndex: Int, val heading: Boolean) : GridSlot
    data class Cell(val itemIndex: Int) : GridSlot
}

/** Full-span boundaries and cell indices are shared by rendering and scrolling. */
internal fun gridSlots(sectionSizes: List<Int>, headings: List<Boolean>): List<GridSlot> {
    require(sectionSizes.size == headings.size && sectionSizes.all { it >= 0 })
    return buildList {
        var flat = 0
        var previousSection = false
        sectionSizes.forEachIndexed { index, count ->
            if (count > 0) {
                if (previousSection || headings[index]) add(GridSlot.Boundary(index, headings[index]))
                repeat(count) { add(GridSlot.Cell(flat++)) }
                previousSection = true
            }
        }
    }
}

/** Visual rows restart at each section; short final rows never point into headers. */
internal fun gridMove(index: Int, move: GridMove, sectionSizes: List<Int>, columns: Int): Int {
    require(columns > 0)
    val rows = mutableListOf<IntRange>()
    var start = 0
    sectionSizes.forEach { count ->
        require(count >= 0)
        repeat(ceil(count.toDouble() / columns).toInt()) { row ->
            val first = start + row * columns
            rows.add(first..minOf(first + columns - 1, start + count - 1))
        }
        start += count
    }
    if (start == 0) return 0
    val current = index.coerceIn(0, start - 1)
    val rowIndex = rows.indexOfFirst { current in it }
    val column = current - rows[rowIndex].first
    return when (move) {
        GridMove.LEFT -> (current - 1).coerceAtLeast(0)
        GridMove.RIGHT -> (current + 1).coerceAtMost(start - 1)
        GridMove.HOME -> 0
        GridMove.END -> start - 1
        GridMove.UP, GridMove.DOWN -> {
            val target = rows[(rowIndex + if (move == GridMove.UP) -1 else 1).coerceIn(rows.indices)]
            (target.first + column).coerceAtMost(target.last)
        }
    }
}

internal fun gridColumns(widthDp: Float, requested: Int, size: String): Int {
    val available = ((widthDp - 22f).coerceAtLeast(82f) / 82f).toInt().coerceIn(1, 8)
    if (requested > 0) return requested.coerceIn(1, available)
    val target = when (size) { "small" -> 96; "large" -> 200; else -> 136 }
    return ((widthDp - 22f) / (target + 10)).toInt().coerceIn(1, available)
}

internal fun gridAspectRatio(value: String): Float = when (value) {
    "3/2" -> 1.5f; "2/3" -> 2f / 3; "4/3" -> 4f / 3; "3/4" -> .75f
    "16/9" -> 16f / 9; "9/16" -> 9f / 16; else -> 1f
}
