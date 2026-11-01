package dev.tinycast.android

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Keep the search origin independent of the IME; only the space below it changes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PaletteViewport(
    modifier: Modifier = Modifier,
    content: @Composable BoxWithConstraintsScope.(wide: Boolean, top: Dp, height: Dp) -> Unit
) {
    val bars = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    BoxWithConstraints(modifier.fillMaxSize().windowInsetsPadding(bars)) {
        val wide = maxWidth >= 700.dp
        val top = if (wide && maxHeight > 600.dp) maxHeight * .18f else 12.dp
        val density = LocalDensity.current
        // Read the destination inset, not each animated keyboard frame. Avoid
        // double-counting the navigation bar already consumed by the viewport.
        val keyboard = with(density) {
            (WindowInsets.imeAnimationTarget.getBottom(this) - bars.getBottom(this)).coerceAtLeast(0).toDp()
        }
        val preferredHeight=PaletteTheme.height*density.fontScale.coerceIn(1f,1.5f)
        val height = minOf(preferredHeight, (maxHeight - top - keyboard - 12.dp).coerceAtLeast(0.dp))
        content(wide, top, height)
    }
}
