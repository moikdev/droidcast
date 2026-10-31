package dev.tinycast.android

/** Non-snapshot bookkeeping: reading scroll layout must not cause a recompose loop. */
internal class PaletteScrollHistory {
    private var query: String? = null
    private var category: String? = null
    private var ids = emptyList<String>()
    private var selected = -1
    private var viewportHeight = -1

    // Layout observations arrive after measurement, independently of selection
    // and query recomposition. Ordinary scrolling must not force a selection back.
    fun viewportMeasured(height: Int, fullyVisible: Boolean): Boolean {
        // A detached/re-entering lazy layout reports zero before its first real
        // measure. That is not an IME shrink and must not replace its anchor.
        if (height <= 0) return false
        val shrank = viewportHeight > 0 && height < viewportHeight
        viewportHeight = height
        return shrank && !fullyVisible
    }

    fun update(query: String, category: String, ids: List<String>, selected: Int, fullyVisible: Boolean): Boolean {
        val dataChanged = this.query != query || this.category != category || this.ids != ids
        val moved = this.selected != selected
        this.query = query; this.category = category; this.ids = ids; this.selected = selected
        return dataChanged || (moved && !fullyVisible)
    }
}
