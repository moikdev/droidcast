package dev.tinycast.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Keep content bounded, with a scrollable route when its chrome cannot fit. */
@Composable
internal fun PaletteColumn(modifier:Modifier,content:@Composable ColumnScope.()->Unit) {
    val menus=LocalPaletteMenus.current
    val density=LocalDensity.current
    val scroll=rememberScrollState()
    BoxWithConstraints(modifier) {
        val viewport=maxHeight
        val chrome=with(density) { (menus.headerHeight+menus.footerHeight).toDp() }
        val minimumBody=maxOf(96.dp,72.dp*density.fontScale.coerceAtLeast(1f))
        val contentHeight=maxOf(viewport,chrome+minimumBody)
        // A finite inner height lets nested lazy lists and scrolling forms keep
        // their own viewports. Extra chrome stays reachable by scrolling this
        // route instead of being squeezed below its minimum touch dimensions.
        Column(Modifier.fillMaxSize().verticalScroll(scroll).testTag("palette-content-scroll")) {
            Column(Modifier.fillMaxWidth().height(contentHeight),content=content)
        }
        if(contentHeight>viewport) Canvas(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(3.dp).padding(vertical=8.dp)) {
            val ratio=(viewport/contentHeight).coerceIn(0f,1f)
            val thumb=(size.height*ratio).coerceIn(minOf(16.dp.toPx(),size.height),size.height)
            val progress=if(scroll.maxValue>0)scroll.value.toFloat()/scroll.maxValue else 0f
            drawRoundRect(PaletteTheme.secondary,Offset(0f,(size.height-thumb)*progress),Size(size.width,thumb),CornerRadius(size.width/2))
        }
    }
}
