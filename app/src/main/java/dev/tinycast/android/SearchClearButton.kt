package dev.tinycast.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private class SearchClearLifetime { var active = true }

/** Keep the editor's width stable, including when there is nothing to clear. */
@Composable
internal fun SearchClearButton(
    visible: Boolean,
    owner: Any,
    tag: String,
    current: () -> Boolean,
    clear: () -> Unit
) {
    val lifetime = remember(owner) { SearchClearLifetime() }
    DisposableEffect(lifetime) { onDispose { lifetime.active = false } }
    Box(Modifier.size(48.dp)) {
        if (visible) IconButton(
            onClick = { if (lifetime.active && current()) clear() },
            modifier = Modifier.size(48.dp).semantics { contentDescription = "Clear search" }.testTag(tag)
        ) { Glyph("close", PaletteTheme.secondary, 18) }
    }
}
