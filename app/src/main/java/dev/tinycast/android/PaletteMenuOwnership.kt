package dev.tinycast.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** UI-thread ownership: callbacks from an outgoing composition cannot affect its replacement. */
internal class PaletteMenuOwnership(private val inputBoundary: () -> Unit) {
    private var owner: Any? = null
    var open by mutableStateOf(false); private set
    fun attach(value: Any) { owner = value; open = false }
    fun update(value: Any, expanded: Boolean, windowFocused: Boolean): Boolean {
        if(owner !== value || open == expanded) return false
        open = expanded
        // A background/disposed window is not a new boundary in the window
        // currently receiving keys. Native window transitions handle that case.
        if(windowFocused) inputBoundary()
        return true
    }
    fun detach(value: Any) { if(owner === value) { owner = null; open = false } }
}
