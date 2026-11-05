package dev.tinycast.android

import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

private class SearchKeyboardLifetime { var active = true }

/** Set an explicit visibility state without replacing the editor's text or focus. */
@Composable
internal fun SearchKeyboardButton(
    imeVisible: Boolean,
    owner: Any,
    tag: String,
    focus: FocusRequester,
    current: () -> Boolean,
    requested: (Boolean) -> Unit
) {
    val view = LocalView.current
    val keyboard = LocalSoftwareKeyboardController.current
    // A callback from an earlier mode stays obsolete even after visible/hidden
    // returns to its former value within the same search owner.
    val lifetime = remember(owner, imeVisible) { SearchKeyboardLifetime() }
    DisposableEffect(lifetime) { onDispose { lifetime.active = false } }
    val activate: () -> Boolean = {
        val insets = ViewCompat.getRootWindowInsets(view)
        if (lifetime.active && view.hasWindowFocus() && current() &&
            insets != null && insets.isVisible(WindowInsetsCompat.Type.ime()) == imeVisible) {
            val show = !imeVisible
            requested(show)
            val controller = ViewCompat.getWindowInsetsController(view)
            if (show) {
                focus.requestFocus()
                keyboard?.show()
                controller?.show(WindowInsetsCompat.Type.ime())
            } else {
                keyboard?.hide()
                controller?.hide(WindowInsetsCompat.Type.ime())
            }
            true
        } else false
    }
    // Publish the same owned action for accessibility. Foundation's default
    // click semantics forwards through a mutable node to its latest callback;
    // a retained action must instead keep this visibility/owner snapshot.
    IconButton(onClick = { activate() }, modifier = Modifier.size(48.dp).testTag(tag).clearAndSetSemantics {
        role = Role.Button
        contentDescription = if (imeVisible) "Hide on-screen keyboard" else "Show on-screen keyboard"
        onClick { activate() }
    }) {
        Glyph(if (imeVisible) "keyboard-hide" else "keyboard", PaletteTheme.tertiary, 20)
    }
}
