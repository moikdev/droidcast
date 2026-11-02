package dev.tinycast.android.extensions

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tinycast.android.Glyph
import dev.tinycast.android.PaletteTheme
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** An override is hidden during initial loading, but can describe a filtered empty result. */
internal fun extensionEmptyView(root: JSONObject?, visibleCount: Int, query: String): JSONObject? {
    if (root == null || visibleCount != 0 || (root.props().optBoolean("isLoading") && query.isEmpty())) return null
    val type = root.optString("type")
    if (type !in listOf("List", "Grid")) return null
    return root.nodes("$type.EmptyView").firstOrNull()
}

internal data class ExtensionActionScope(val rootId: String, val owner: String, val nodes: List<JSONObject>) {
    fun resolve(requested: JSONObject?): JSONObject? = requested?.let { wanted ->
        nodes.firstOrNull { it.opt("id") == wanted.opt("id") && it.handle("onAction") == wanted.handle("onAction") }
    }
}

/** Item, visible empty-view, then root actions; hidden empty views never supply callbacks. */
internal fun extensionActionScope(root: JSONObject?, selected: JSONObject?, visibleCount: Int, query: String): ExtensionActionScope {
    if (root == null) return ExtensionActionScope("", "", emptyList())
    val rootId = root.optString("id")
    selected?.actions()?.takeIf { it.isNotEmpty() }?.let { return ExtensionActionScope(rootId, "item:${selected.optString("id")}", it) }
    if (selected == null) extensionEmptyView(root, visibleCount, query)?.let { empty ->
        empty.actions().takeIf { it.isNotEmpty() }?.let { return ExtensionActionScope(rootId, "empty:${empty.optString("id")}", it) }
    }
    return ExtensionActionScope(rootId, "root", root.actions())
}

@Composable
internal fun NativeExtensionEmptyView(node: JSONObject?, initialLoading: Boolean, images: ExtensionGridImages,
                                     tag: String, modifier: Modifier = Modifier) {
    val title = (node?.props()?.opt("title") as? String)?.takeIf { it.isNotBlank() }
        ?: if (initialLoading) "Loading…" else "No results"
    val description = (node?.props()?.opt("description") as? String)?.takeIf { it.isNotBlank() }
    val icon = node?.props()?.opt("icon")?.takeUnless { it == JSONObject.NULL }
    key(node?.optString("id"), title, description, initialLoading) {
        val scroll = rememberScrollState()
        BoxWithConstraints(modifier.clipToBounds()) {
            Column(Modifier.fillMaxWidth().verticalScroll(scroll).heightIn(min = maxHeight).padding(24.dp)
                .testTag("$tag-scroll"), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                if (icon != null) EmptyViewIcon(icon, images, tag)
                Text(title, color = PaletteTheme.ink, fontSize = 16.sp, lineHeight = 24.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().testTag("$tag-title"))
                if (description != null) Text(description, color = PaletteTheme.secondary, fontSize = 13.sp, lineHeight = 20.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().testTag("$tag-description"))
            }
        }
    }
}

@Composable
private fun EmptyViewIcon(icon: Any, images: ExtensionGridImages, tag: String) {
    val visual = gridVisual(icon)
    val loaded by key(visual) { produceState<Result<Bitmap>?>(null, images, visual) {
        if (visual is GridVisual.Image) value = try { Result.success(images.load(visual.source)) }
            catch (cancel: CancellationException) { throw cancel } catch (error: Exception) { Result.failure(error) }
    } }
    val unavailable = visual is GridVisual.Unsupported || visual is GridVisual.Swatch || loaded?.isFailure == true
    Box(Modifier.size(64.dp).testTag("$tag-icon").clearAndSetSemantics {
        contentDescription = if (unavailable) "Icon unavailable" else "Extension icon"
    }, contentAlignment = Alignment.Center) {
        when (visual) {
            is GridVisual.Symbol -> {
                val size = with(LocalDensity.current) { 40.dp.toSp() }
                Text(visual.value, fontSize = size, lineHeight = size, maxLines = 1, overflow = TextOverflow.Clip, color = PaletteTheme.ink)
            }
            is GridVisual.Image -> {
                val bitmap = loaded?.getOrNull()
                if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize()
                    .clip(if (visual.mask == "circle") CircleShape else RoundedCornerShape(if (visual.mask == "roundedRectangle") 10.dp else 0.dp)), contentScale = ContentScale.Fit)
                else if (loaded == null) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else Glyph("extension", PaletteTheme.secondary, 28)
            }
            else -> Glyph("extension", PaletteTheme.secondary, 28)
        }
    }
}
