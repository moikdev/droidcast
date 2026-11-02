package dev.tinycast.android

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Give command/status text its own line when it would compete with touch actions. */
@Composable
internal fun PaletteFooter(
    metadata: (@Composable (Modifier) -> Unit)? = null,
    leading: @Composable () -> Unit = {},
    showActions: Boolean = true,
    actions: @Composable () -> Unit
) {
    val largeFont = LocalDensity.current.fontScale > 1.3f
    BoxWithConstraints(Modifier.fillMaxWidth().paletteFooterSurface().testTag("palette-footer")) {
        val stacked = maxWidth < 600.dp || largeFont
        if (stacked && metadata != null && showActions) {
            Column(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=6.dp)) {
                metadata(Modifier.fillMaxWidth().padding(start=6.dp,end=6.dp,bottom=6.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    leading()
                    Box(Modifier.weight(1f),contentAlignment=Alignment.CenterEnd) { actions() }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().heightIn(min=PaletteTheme.footer).padding(horizontal=8.dp,vertical=4.dp),
                verticalAlignment=Alignment.CenterVertically) {
                leading()
                if (metadata != null) metadata(Modifier.weight(1f).padding(horizontal=8.dp))
                else Spacer(Modifier.weight(1f))
                if (showActions) Box { actions() }
            }
        }
    }
}
