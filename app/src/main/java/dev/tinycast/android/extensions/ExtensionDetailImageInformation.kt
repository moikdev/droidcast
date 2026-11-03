package dev.tinycast.android.extensions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.PopupPositionProvider

/** A persistent touch/keyboard tooltip, owned by one image in one document. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailImageInformation(information: String, active: Boolean, modifier: Modifier,
    content: @Composable () -> Unit) {
    val state = rememberTooltipState(isPersistent = true)
    val view = LocalView.current
    val density = LocalDensity.current
    fun visibleFrame(): IntRect {
        val frame = android.graphics.Rect().also(view::getWindowVisibleDisplayFrame)
        val screen = IntArray(2).also(view::getLocationOnScreen)
        val window = IntArray(2).also(view::getLocationInWindow)
        return if (frame.isEmpty) IntRect(window[0], window[1], window[0] + view.width, window[1] + view.height)
        else IntRect(frame.left - screen[0] + window[0], frame.top - screen[1] + window[1],
            frame.right - screen[0] + window[0], frame.bottom - screen[1] + window[1])
    }
    var frame by remember(view) { mutableStateOf(visibleFrame()) }
    DisposableEffect(view, state) {
        val observer = view.viewTreeObserver
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener { frame = visibleFrame() }
        observer.addOnGlobalLayoutListener(listener)
        onDispose { if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener); state.dismiss() }
    }
    // IME insets also invalidate composition on windows that retain their size.
    val imeBottom = WindowInsets.ime.getBottom(density)
    val visible = remember(frame, imeBottom) { visibleFrame() }
    LaunchedEffect(active) { if (!active) state.dismiss() }
    val margin = with(density) { 8.dp.roundToPx() }
    val position = remember(visible, margin) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val left = maxOf(margin, visible.left + margin)
                val top = maxOf(margin, visible.top + margin)
                val right = minOf(windowSize.width - margin, visible.right - margin)
                val bottom = minOf(windowSize.height - margin, visible.bottom - margin)
                val x = anchorBounds.left.coerceIn(left, maxOf(left, right - popupContentSize.width))
                val above = anchorBounds.top - margin - popupContentSize.height
                val y = (if (above >= top) above else anchorBounds.bottom + margin)
                    .coerceIn(top, maxOf(top, bottom - popupContentSize.height))
                return IntOffset(x, y)
            }
        }
    }
    val width = with(density) { (visible.width - margin * 2).coerceAtLeast(1).toDp() }
    val height = with(density) { (visible.height - margin * 2).coerceAtLeast(1).toDp() }
    TooltipBox(position, state = state, enableUserInput = active, modifier = modifier, tooltip = {
        // A Popup has a different layout owner from the document. Its text
        // must not register with the document's SelectionContainer.
        DisableSelection {
        RichTooltip(Modifier.widthIn(max = width).heightIn(max = height)
            .verticalScroll(rememberScrollState()).testTag("extension-image-information")) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(information, fontSize = 14.sp, lineHeight = 22.sp)
                TextButton(onClick = state::dismiss,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("extension-image-information-close")) { Text("Close") }
            }
        }
        }
    }, content = content)
}
