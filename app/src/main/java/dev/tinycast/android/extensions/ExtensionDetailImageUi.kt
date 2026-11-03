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
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.tinycast.android.Glyph
import dev.tinycast.android.BuildConfig
import dev.tinycast.android.PaletteTheme
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference

private data class DetailImageResult(val source: String, val bitmap: Bitmap? = null, val failed: Boolean = false)
private class DetailImageLoadTrace {
    companion object { private val owners = java.util.concurrent.atomic.AtomicLong() }
    private val owner = owners.incrementAndGet()
    @Volatile var generation = 0
    @Volatile var cancellations = 0
    @Volatile var phase = "new"
    @Volatile var sample: (() -> String)? = null
    private var nextToken = 0
    private val tokens = mutableListOf<Pair<WeakReference<Any>, Int>>()
    private val events = ArrayDeque<JSONObject>()
    private var previousEvent = ""

    // Match produceState's rendered-value equality: a fresh equal wrapper
    // need not recompose. Weak references never retain a decoded bitmap.
    @Synchronized fun token(value: Any?): Int {
        if (value == null) return 0
        tokens.removeAll { it.first.get() == null }
        tokens.firstOrNull { it.first.get() == value }?.let { return it.second }
        val token = ++nextToken
        tokens += WeakReference(value) to token
        if (tokens.size > 128) tokens.removeAt(0)
        return token
    }
    @Synchronized fun event(stage: String, producer: Int = generation, value: Any? = null, ready: Boolean = false) {
        val valueToken = token(value)
        val signature = "$stage:$producer:$valueToken:$ready"
        if (previousEvent == signature) return
        previousEvent = signature
        events.addLast(JSONObject().put("stage", stage).put("producer_generation", producer)
            .put("value_token", valueToken).put("bitmap_ready", ready)
            .put("uptime_ms", android.os.SystemClock.uptimeMillis()))
        while (events.size > 64) events.removeFirst()
    }
    fun snapshot(): String {
        val live = runCatching { sample?.invoke() ?: "detached" }.getOrDefault("detached")
        return synchronized(this) {
            JSONObject().put("schema_version", 1).put("trace_owner", owner)
                .put("generation", generation).put("phase", phase).put("cancellations", cancellations)
                .put("live_state", live).put("events", JSONArray(events.map { JSONObject(it.toString()) })).toString()
        }
    }
    override fun toString() = "generation=$generation;phase=$phase;cancellations=$cancellations;" +
        runCatching { sample?.invoke().orEmpty() }.getOrDefault("detached")
}
private val imageLoadTraceKey = SemanticsPropertyKey<DetailImageLoadTrace>("DetailImageLoadState")
private val imageLoadSnapshotKey = SemanticsPropertyKey<() -> String>("DetailImageLoadSnapshot")

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
    val view = LocalView.current
    val trace = remember { if (BuildConfig.DEBUG) DetailImageLoadTrace() else null }
    if (trace != null) DisposableEffect(trace) { onDispose { trace.sample = null; trace.event("disposed") } }
    fun updateVisibility(bounds: LayoutCoordinates?) {
        val clipped = bounds?.takeIf { it.isAttached }?.boundsInWindow()
        visible = clipped != null && clipped.width > 0f && clipped.height > 0f
    }
    val sampleVisibility = rememberUpdatedState { updateVisibility(coordinates) }
    val refresh = remember(view) { Runnable { sampleVisibility.value() } }
    fun refreshAfterPlacement() {
        // Coalesce position events into one sample after ancestor clipping has
        // settled. Posting does not continuously request frames or poll.
        view.removeCallbacks(refresh); view.postOnAnimation(refresh)
    }
    DisposableEffect(view, refresh) { onDispose { view.removeCallbacks(refresh) } }
    LaunchedEffect(source, images, started) { refreshAfterPlacement() }
    var aspect by remember(source) { mutableFloatStateOf(1.6f) }
    val observed by produceState(DetailImageResult(source), source, images, visible, started, inline) {
        val generation = trace?.let { ++it.generation } ?: 0
        fun phase(value: String) {
            if (trace != null && trace.generation == generation) trace.phase = value
            trace?.event("producer-$value", generation)
        }
        value = DetailImageResult(source)
        trace?.event("state-reset", generation, value, value.bitmap != null)
        if (request == null) {
            phase("invalid-request"); value = DetailImageResult(source, failed = true)
            trace?.event("state-unavailable", generation, value)
        }
        else if (visible && started) try {
            phase("loading")
            val bitmap = images.load(request.source, if (inline) 128 else ExtensionDetailImages.MAX_EDGE)
            aspect = bitmap.width.toFloat() / bitmap.height
            phase("ready")
            value = DetailImageResult(source, bitmap)
            trace?.event("state-ready", generation, value, value.bitmap != null)
        } catch (cancelled: CancellationException) {
            trace?.let { it.cancellations++ }; phase("cancelled"); throw cancelled
        } catch (failure: Exception) {
            phase("failed:${failure.javaClass.simpleName}"); value = DetailImageResult(source, failed = true)
            trace?.event("state-unavailable", generation, value)
        }
        else phase(if (started) "waiting-visibility" else "waiting-lifecycle")
    }
    // A retained metadata icon can receive a new source before its producer's
    // restart executes. Never paint the preceding source during that interval.
    val result = observed.takeIf { it.source == source } ?: DetailImageResult(source)
    SideEffect {
        if (trace != null) {
            trace.event("composed", value = result, ready = result.bitmap != null)
            // A read-only DEBUG semantic probe carries no URL, content or bitmap.
            // Plain trace fields deliberately cause no recomposition of their own.
            trace.sample = {
                val bounds = coordinates?.takeIf { it.isAttached }?.boundsInWindow()
                "visible=$visible;started=$started;request=${request != null};source_matches=${observed.source == source};" +
                    "observed_ready=${observed.bitmap != null};composed_ready=${result.bitmap != null};" +
                    "observed_value_token=${trace.token(observed)};composed_value_token=${trace.token(result)};" +
                    "attached=${coordinates?.isAttached == true};window_bounds=$bounds;lifecycle=${lifecycle.currentState}"
            }
        }
    }
    val label = alternative.ifBlank { "Image" }
    val color = tint ?: request?.tint?.let(::metadataTint)
    val content: @Composable (Modifier) -> Unit = { imageModifier ->
    BoxWithConstraints(imageModifier.onGloballyPositioned { bounds ->
        coordinates = bounds; updateVisibility(bounds); refreshAfterPlacement()
    }.then(if (trace == null) Modifier else Modifier.drawWithContent {
        drawContent()
        // This records a Compose draw command, not display scanout. Tests must
        // separately inspect the committed pixels before crediting rendering.
        trace.event("draw-recorded", value = result, ready = result.bitmap != null)
    }).testTag("extension-detail-image").semantics {
        contentDescription = label
        stateDescription = when { result.failed -> "Image unavailable"; result.bitmap != null -> "Image ready"; else -> "Image loading" }
        if (trace != null) {
            this[imageLoadTraceKey] = trace
            this[imageLoadSnapshotKey] = trace::snapshot
        }
    }) {
        val width = (request?.width?.dp ?: maxWidth).coerceAtMost(maxWidth)
        val height = (request?.height?.dp ?: (width / aspect.coerceIn(1f / 1024, 1024f))).coerceIn(1.dp, 2048.dp)
        val bitmap = result.bitmap
        if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = null,
            modifier = (if (inline) Modifier.fillMaxSize() else Modifier.width(width).height(height)).testTag("extension-detail-image-content"),
            contentScale = ContentScale.Fit, colorFilter = color?.let { ColorFilter.tint(it) })
        else if (inline) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Glyph("extension", PaletteTheme.secondary, 20) }
        else if (result.failed) Column(Modifier.fillMaxWidth().heightIn(min = 60.dp).background(PaletteTheme.ink.copy(alpha = .05f), RoundedCornerShape(8.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, color = PaletteTheme.ink, fontSize = 14.sp, lineHeight = 22.sp)
            Text("Image unavailable", color = PaletteTheme.secondary, fontSize = 12.sp, lineHeight = 18.sp)
        } else Box(Modifier.width(width).height(height).background(PaletteTheme.ink.copy(alpha = .05f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center) {
            // Releasing an offscreen bitmap must not collapse its scroll space.
            LinearProgressIndicator(Modifier.widthIn(max = 160.dp).fillMaxWidth().padding(horizontal = 12.dp))
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
