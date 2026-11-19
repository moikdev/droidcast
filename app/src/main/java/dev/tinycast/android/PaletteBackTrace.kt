package dev.tinycast.android

import android.os.SystemClock
import android.view.KeyEvent

internal data class PaletteBackEvent(
    val stage: String,
    val uptime: Long,
    val action: Int? = null,
    val downTime: Long? = null,
    val eventTime: Long? = null,
    val repeat: Int? = null,
    val canceled: Boolean? = null,
    val menuOpen: Boolean? = null,
    val queryLength: Int? = null,
    val handled: Boolean? = null
)

internal data class PaletteBackSnapshot(val enabled: Boolean, val dropped: Int, val events: List<PaletteBackEvent>)

/** Debug-only, opt-in primitive metadata. Never retains text, callbacks or views. */
internal object PaletteBackTrace {
    const val property = "dev.droidcast.paletteBackTrace"
    private var history: ArrayDeque<PaletteBackEvent>? = null
    private var dropped = 0
    private fun enabled() = BuildConfig.DEBUG && System.getProperty(property) == "true"

    @Synchronized private fun append(event: PaletteBackEvent) {
        val events = history ?: ArrayDeque<PaletteBackEvent>().also { history = it }
        if (events.size == 128) { events.removeFirst(); dropped++ }
        events.addLast(event)
    }

    fun key(stage: String, phase: String, event: KeyEvent, menuOpen: Boolean, handled: Boolean? = null) {
        if (!enabled() || event.keyCode != KeyEvent.KEYCODE_BACK) return
        append(PaletteBackEvent("$stage-$phase", SystemClock.uptimeMillis(), event.action, event.downTime,
            event.eventTime, event.repeatCount, event.isCanceled, menuOpen, handled = handled))
    }

    fun handler(stage: String, menuOpen: Boolean, queryLength: Int? = null) {
        if (enabled()) append(PaletteBackEvent(stage, SystemClock.uptimeMillis(),
            menuOpen = menuOpen, queryLength = queryLength))
    }

    @Synchronized fun snapshot() = PaletteBackSnapshot(enabled(), dropped, history?.toList().orEmpty())
    @Synchronized fun clear() { history = null; dropped = 0 }
}
