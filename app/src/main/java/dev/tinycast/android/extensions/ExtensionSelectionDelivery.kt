package dev.tinycast.android.extensions

/** Share event-time and post-render notifications without emitting a selection twice. */
internal class ExtensionSelectionDelivery {
    private var delivered: Pair<String, String?>? = null
    fun deliver(rootId: String, selectedId: String?, notify: () -> Unit) {
        val next = rootId to selectedId
        if (delivered == next) return
        delivered = next
        notify()
    }
}
