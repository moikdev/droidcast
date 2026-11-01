package dev.tinycast.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
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
                                  enabled: Boolean = true, actionsEnabled: Boolean = enabled, showActions: Boolean = true,
                                  modifier: Modifier = Modifier) {
    val reflow = compact || LocalDensity.current.fontScale > 1.3f
    val shape = RoundedCornerShape(24.dp)
    Row(modifier.widthIn(max = 420.dp).then(if (reflow) Modifier.fillMaxWidth() else Modifier)
        .height(IntrinsicSize.Min).heightIn(min = 48.dp)
        .clip(shape).background(PaletteTheme.control).border(1.dp,PaletteTheme.border,shape)
        .testTag("palette-action-capsule"), verticalAlignment=Alignment.CenterVertically) {
        Row(Modifier.weight(1f,fill=reflow).widthIn(min=48.dp).fillMaxHeight()
            .clickable(enabled=enabled,role=Role.Button,onClick=primary)
            .semantics { contentDescription=title }.testTag(primaryTag).padding(horizontal=14.dp,vertical=10.dp),
            verticalAlignment=Alignment.CenterVertically) {
            Text(title,color=if(enabled)PaletteTheme.ink else PaletteTheme.tertiary,fontSize=14.sp,lineHeight=20.sp,
                fontWeight=FontWeight.Medium,maxLines=if(reflow)2 else 1,overflow=TextOverflow.Ellipsis,
                modifier=Modifier.weight(1f,fill=false))
            if (!reflow) { Spacer(Modifier.width(6.dp)); KeyCap("↵") }
        }
        if (showActions) {
            Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical=12.dp).background(PaletteTheme.separator))
            Row(Modifier.widthIn(min=48.dp).fillMaxHeight().clickable(enabled=actionsEnabled,role=Role.Button,onClick=actions)
                .testTag(actionsTag).padding(horizontal=14.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("Actions",color=if(actionsEnabled)PaletteTheme.secondary else PaletteTheme.tertiary,
                    fontSize=13.sp,lineHeight=20.sp,maxLines=1)
                if (!reflow) { Spacer(Modifier.width(6.dp)); KeyCap("Ctrl"); Spacer(Modifier.width(2.dp)); KeyCap("K") }
            }
        }
    }
}
