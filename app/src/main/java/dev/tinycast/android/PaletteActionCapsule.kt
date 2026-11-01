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
    val largeFont = LocalDensity.current.fontScale > 1.3f
    val reflow = compact || largeFont
    val shape = RoundedCornerShape(24.dp)
    BoxWithConstraints(modifier.widthIn(max=420.dp).then(if(reflow)Modifier.fillMaxWidth() else Modifier)) {
        val surface=Modifier.clip(shape).background(PaletteTheme.control).border(1.dp,PaletteTheme.border,shape)
            .testTag("palette-action-capsule")
        if (reflow && largeFont && maxWidth < 300.dp) {
            Column(surface.fillMaxWidth()) {
                PrimaryControl(title,primary,primaryTag,enabled,3,false,Modifier.fillMaxWidth().heightIn(min=48.dp))
                if(showActions) {
                    Box(Modifier.fillMaxWidth().height(1.dp).padding(horizontal=14.dp).background(PaletteTheme.separator))
                    ActionsControl(actions,actionsTag,actionsEnabled,false,Modifier.fillMaxWidth().heightIn(min=48.dp))
                }
            }
        } else {
            Row(surface.height(IntrinsicSize.Min).heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
                PrimaryControl(title,primary,primaryTag,enabled,if(reflow)2 else 1,!reflow,
                    Modifier.weight(1f,fill=reflow).widthIn(min=48.dp).fillMaxHeight())
                if(showActions) {
                    Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical=12.dp).background(PaletteTheme.separator))
                    ActionsControl(actions,actionsTag,actionsEnabled,!reflow,Modifier.widthIn(min=48.dp).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun PrimaryControl(title:String,click:()->Unit,tag:String,enabled:Boolean,lines:Int,hints:Boolean,modifier:Modifier) {
    Row(modifier.clickable(enabled=enabled,role=Role.Button,onClick=click).semantics { contentDescription=title }
        .testTag(tag).padding(horizontal=14.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(title,color=if(enabled)PaletteTheme.ink else PaletteTheme.tertiary,fontSize=14.sp,lineHeight=20.sp,
            fontWeight=FontWeight.Medium,maxLines=lines,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f,fill=false))
        if(hints) { Spacer(Modifier.width(6.dp));KeyCap("↵") }
    }
}

@Composable
private fun ActionsControl(click:()->Unit,tag:String,enabled:Boolean,hints:Boolean,modifier:Modifier) {
    Row(modifier.clickable(enabled=enabled,role=Role.Button,onClick=click).testTag(tag)
        .padding(horizontal=14.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
        Text("Actions",color=if(enabled)PaletteTheme.secondary else PaletteTheme.tertiary,fontSize=13.sp,lineHeight=20.sp,maxLines=1)
        if(hints) { Spacer(Modifier.width(6.dp));KeyCap("Ctrl");Spacer(Modifier.width(2.dp));KeyCap("K") }
    }
}
