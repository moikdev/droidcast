package dev.tinycast.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp

/** A route in the existing activity window, sharing the launcher's fixed origin. */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun PaletteFrame(back: () -> Unit, modifier: Modifier = Modifier, tag: String = "palette-screen",
                          content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = back)
    PaletteViewport { _, top, height ->
        Box(Modifier.fillMaxSize().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = back).testTag("inline-backdrop"))
        Column(Modifier.padding(top = top).padding(horizontal = 12.dp).widthIn(max = PaletteTheme.width)
            .fillMaxWidth().height(height).align(Alignment.TopCenter)
            .shadow(24.dp, RoundedCornerShape(PaletteTheme.radius)).clip(RoundedCornerShape(PaletteTheme.radius))
            .background(PaletteTheme.surface).border(1.dp, PaletteTheme.border, RoundedCornerShape(PaletteTheme.radius))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
            .semantics { testTagsAsResourceId = true }.testTag(tag).then(modifier), content = content)
    }
}
