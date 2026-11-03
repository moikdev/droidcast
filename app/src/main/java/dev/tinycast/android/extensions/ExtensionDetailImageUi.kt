package dev.tinycast.android.extensions

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.tinycast.android.Glyph
import dev.tinycast.android.PaletteTheme
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

private data class DetailImageResult(val source: String, val bitmap: Bitmap? = null, val failed: Boolean = false)

/** Offscreen/stopped views retain their aspect ratio, not decoded bitmap references or jobs. */
@Composable
internal fun NativeDetailImage(source: String, alternative: String, images: ExtensionDetailImages,
    modifier: Modifier = Modifier, inline: Boolean = false, tint: Color? = null, information: String? = null) {
    val request = remember(source) { try { detailImageRequest(source) } catch (_: Exception) { null } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var started by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    // Visibility belongs to the attached layout, not its image URI. Updating
    // the source in an unchanged slot need not cause a position callback.
    var visible by remember { mutableStateOf(false) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    fun updateVisibility(bounds: LayoutCoordinates?) {
        val clipped = bounds?.takeIf { it.isAttached }?.boundsInWindow()
        visible = clipped != null && clipped.width > 0f && clipped.height > 0f
    }
    LaunchedEffect(source, images, started, coordinates) {
        // Allow ancestor clipping/scroll bounds to settle after publication.
        // This is one recheck per change, with no continuous polling or load
        // permitted for a detached/offscreen/stopped element.
        withFrameNanos { }
        updateVisibility(coordinates)
    }
    var aspect by remember(source) { mutableFloatStateOf(1.6f) }
    val observed by produceState(DetailImageResult(source), source, images, visible, started, inline) {
        value = DetailImageResult(source)
        if (request == null) value = DetailImageResult(source, failed = true)
        else if (visible && started) try {
            val bitmap = images.load(request.source, if (inline) 128 else ExtensionDetailImages.MAX_EDGE)
            aspect = bitmap.width.toFloat() / bitmap.height
            value = DetailImageResult(source, bitmap)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { value = DetailImageResult(source, failed = true) }
    }
    // A retained metadata icon can receive a new source before its producer's
    // restart executes. Never paint the preceding source during that interval.
    val result = observed.takeIf { it.source == source } ?: DetailImageResult(source)
    val label = alternative.ifBlank { "Image" }
    val color = tint ?: request?.tint?.let(::metadataTint)
    val content: @Composable (Modifier) -> Unit = { imageModifier ->
    BoxWithConstraints(imageModifier.onGloballyPositioned { bounds ->
        coordinates = bounds; updateVisibility(bounds)
    }.testTag("extension-detail-image").semantics {
        contentDescription = label
        stateDescription = when { result.failed -> "Image unavailable"; result.bitmap != null -> "Image ready"; else -> "Image loading" }
    }) {
        val width = (request?.width?.dp ?: maxWidth).coerceAtMost(maxWidth)
        val height = (request?.height?.dp ?: (width / aspect.coerceIn(1f / 1024, 1024f))).coerceIn(1.dp, 2048.dp)
        val bitmap = result.bitmap
        if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = null,
            modifier = (if (inline) Modifier.fillMaxSize() else Modifier.width(width).height(height)).testTag("extension-detail-image-content"),
            contentScale = ContentScale.Fit, colorFilter = color?.let { ColorFilter.tint(it) })
        else if (inline) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Glyph("extension", PaletteTheme.secondary, 20) }
        else Column(Modifier.fillMaxWidth().heightIn(min = 60.dp).background(PaletteTheme.ink.copy(alpha = .05f), RoundedCornerShape(8.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, color = PaletteTheme.ink, fontSize = 14.sp, lineHeight = 22.sp)
            if (result.failed) Text("Image unavailable", color = PaletteTheme.secondary, fontSize = 12.sp, lineHeight = 18.sp)
            else LinearProgressIndicator(Modifier.widthIn(max = 160.dp).fillMaxWidth())
        }
    }
    }
    if (information.isNullOrBlank()) content(modifier)
    else key(images, source, information) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val bounds = if (inline) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
            DetailImageInformation(information, visible && started, bounds) { content(bounds) }
            if (!inline) Text(information, color = PaletteTheme.secondary, fontSize = 14.sp, lineHeight = 22.sp)
        }
    }
}

@Composable
internal fun NativeDetailIcon(value: Any?, images: ExtensionDetailImages, modifier: Modifier = Modifier) {
    val source = if (value is JSONObject && value.has("value")) value.opt("value") else value
    val tint = (value as? JSONObject)?.opt("tintColor")?.takeIf { it != JSONObject.NULL }?.let(::metadataTint)
    if (source in listOf("circle-16", "circle-filled-16", "circle-ellipsis-16")) {
        Canvas(modifier.size(24.dp).semantics { contentDescription = "Category icon" }) {
            val color = tint ?: PaletteTheme.ink
            if (source == "circle-filled-16") drawCircle(color, radius = size.minDimension * .4f)
            else {
                drawCircle(color, radius = size.minDimension * .4f, style = Stroke(1.5.dp.toPx()))
                if (source == "circle-ellipsis-16") for (x in listOf(.32f, .5f, .68f))
                    drawCircle(color, radius = size.minDimension * .045f, center = Offset(size.width * x, size.height / 2))
            }
        }
    } else when (val visual = gridVisual(value)) {
        is GridVisual.Image -> NativeDetailImage(visual.source, "Metadata icon", images,
            modifier.size(24.dp).then(if (visual.mask == "circle") Modifier.clip(CircleShape) else Modifier), inline = true, tint = tint)
        is GridVisual.Symbol -> Text(visual.value, modifier, color = tint ?: PaletteTheme.ink, fontSize = 20.sp)
        is GridVisual.Swatch -> Canvas(modifier.size(24.dp).semantics { contentDescription = "Color icon" }) {
            drawCircle(metadataTint(visual.value), size.minDimension * .4f)
        }
        GridVisual.Unsupported -> Box(modifier.size(24.dp).semantics { contentDescription = "Icon unavailable" }, contentAlignment = Alignment.Center) {
            Glyph("extension", PaletteTheme.secondary, 20)
        }
    }
}
