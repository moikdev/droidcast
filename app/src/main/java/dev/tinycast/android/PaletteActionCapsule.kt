package dev.tinycast.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The same primary/action affordance in launcher, Emoji and extension modes. */
@Composable
internal fun PaletteActionCapsule(title: String, primary: () -> Unit, actions: () -> Unit,
                                  primaryTag: String, actionsTag: String, compact: Boolean,
                                  enabled: Boolean = true, actionsEnabled: Boolean = enabled, showActions: Boolean = true) {
    Row(Modifier.widthIn(max = if(compact) 226.dp else 420.dp).height(48.dp)
        .clip(CircleShape).background(PaletteTheme.control).border(1.dp,PaletteTheme.border,CircleShape)
        .testTag("palette-action-capsule"), verticalAlignment=Alignment.CenterVertically) {
        Row(Modifier.weight(1f,fill=false).fillMaxHeight().clip(CircleShape)
            .clickable(enabled=enabled,role=Role.Button,onClick=primary)
            .semantics { contentDescription=title }.testTag(primaryTag).padding(horizontal=10.dp),
            verticalAlignment=Alignment.CenterVertically) {
            Text(title,color=if(enabled)PaletteTheme.ink else PaletteTheme.tertiary,fontSize=12.sp,
                fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f,fill=false))
            Spacer(Modifier.width(6.dp)); KeyCap("↵")
        }
        if (showActions) {
        Box(Modifier.width(1.dp).height(18.dp).background(PaletteTheme.separator))
        Row(Modifier.fillMaxHeight().clip(CircleShape).clickable(enabled=actionsEnabled,role=Role.Button,onClick=actions)
            .testTag(actionsTag).padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("Actions",color=if(actionsEnabled)PaletteTheme.secondary else PaletteTheme.tertiary,fontSize=12.sp)
            Spacer(Modifier.width(6.dp)); KeyCap("Ctrl"); Spacer(Modifier.width(2.dp)); KeyCap("K")
        }
        }
    }
}
